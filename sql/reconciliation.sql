-- Ledger reconciliation checks (PostgreSQL).
--
-- Each check returns the rows that break one rule, so an empty result means
-- the rule holds. Run them all against a database:
--
--   psql -d banking -f sql/reconciliation.sql
--
-- LedgerReconciliationTest runs every check against PostgreSQL in CI, after a
-- workload that includes concurrent transfers, and also corrupts the data on
-- purpose to prove each check catches the problem it is written for.
--
-- Signed amounts: DEPOSIT and TRANSFER_IN add to a balance; WITHDRAWAL and
-- TRANSFER_OUT subtract from it.


-- check: balance_matches_ledger
-- Every account's stored balance equals the sum of its ledger. Accounts with
-- no ledger rows must have a zero balance, hence the LEFT JOIN.
SELECT a.id                         AS account_id,
       a.balance                    AS stored_balance,
       COALESCE(SUM(l.signed), 0)   AS ledger_balance
FROM accounts a
LEFT JOIN (
    SELECT account_id,
           CASE WHEN type IN ('DEPOSIT', 'TRANSFER_IN') THEN amount ELSE -amount END AS signed
    FROM account_transactions
) l ON l.account_id = a.id
GROUP BY a.id, a.balance
HAVING a.balance <> COALESCE(SUM(l.signed), 0)
ORDER BY a.id;


-- check: balance_after_is_running_total
-- Every ledger row's balance_after equals the running total of that account's
-- ledger up to and including the row. Rows are ordered by id, not timestamp:
-- a ledger row is inserted while its account's row lock is held, so within one
-- account the id order is the order the balance changes were applied, and ids
-- never tie.
WITH signed AS (
    SELECT id,
           account_id,
           balance_after,
           CASE WHEN type IN ('DEPOSIT', 'TRANSFER_IN') THEN amount ELSE -amount END AS signed
    FROM account_transactions
),
running AS (
    SELECT id,
           account_id,
           balance_after,
           SUM(signed) OVER (PARTITION BY account_id ORDER BY id
                             ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_total
    FROM signed
)
SELECT id AS ledger_id, account_id, balance_after, running_total
FROM running
WHERE balance_after <> running_total
ORDER BY account_id, id;


-- check: transfer_legs_pair_up
-- Every transfer has exactly one TRANSFER_OUT and one TRANSFER_IN, for the
-- same amount, each naming the other's account as its counterparty. A leg
-- with no transfer_id shows up as a group whose transfer_id is NULL.
SELECT transfer_id,
       COUNT(*) FILTER (WHERE type = 'TRANSFER_OUT') AS out_legs,
       COUNT(*) FILTER (WHERE type = 'TRANSFER_IN')  AS in_legs,
       MIN(amount)                                   AS min_amount,
       MAX(amount)                                   AS max_amount
FROM account_transactions
WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
GROUP BY transfer_id
HAVING transfer_id IS NULL
    OR COUNT(*) FILTER (WHERE type = 'TRANSFER_OUT') <> 1
    OR COUNT(*) FILTER (WHERE type = 'TRANSFER_IN') <> 1
    OR MIN(amount) <> MAX(amount)
    OR MAX(account_id) FILTER (WHERE type = 'TRANSFER_OUT')
       <> MAX(counterparty_account_id) FILTER (WHERE type = 'TRANSFER_IN')
    OR MAX(account_id) FILTER (WHERE type = 'TRANSFER_IN')
       <> MAX(counterparty_account_id) FILTER (WHERE type = 'TRANSFER_OUT')
ORDER BY transfer_id;


-- check: no_negative_balances
-- Overdrafts are rejected, so neither a balance nor any point in its history
-- may be below zero.
SELECT 'account' AS source, id AS row_id, balance AS amount
FROM accounts
WHERE balance < 0
UNION ALL
SELECT 'ledger', id, balance_after
FROM account_transactions
WHERE balance_after < 0
ORDER BY source, row_id;


-- check: money_is_conserved
-- Transfers only move money between accounts, so the money in the bank must
-- equal everything deposited minus everything withdrawn.
WITH totals AS (
    SELECT (SELECT COALESCE(SUM(balance), 0) FROM accounts) AS total_balances,
           (SELECT COALESCE(SUM(CASE type WHEN 'DEPOSIT'    THEN amount
                                          WHEN 'WITHDRAWAL' THEN -amount
                                          ELSE 0 END), 0)
            FROM account_transactions)                      AS deposits_minus_withdrawals
)
SELECT total_balances, deposits_minus_withdrawals
FROM totals
WHERE total_balances <> deposits_minus_withdrawals;
