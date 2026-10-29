# Banking Transactions API

[![CI](https://github.com/Lego514/banking-transactions-api/actions/workflows/ci.yml/badge.svg)](https://github.com/Lego514/banking-transactions-api/actions/workflows/ci.yml)

**RESTful banking transactions service with atomic transfers — Spring Boot 3, JPA, row-level locking tested under concurrency on real PostgreSQL, CI on every push, and a zero-dependency web console.**

A small but production-shaped core banking service: open accounts, move money, and audit every cent through an immutable transaction ledger. Built to demonstrate the engineering concerns banks actually care about — atomicity, concurrency safety, and auditability — not framework tourism.

![UI screenshot](docs/ui-screenshot.png)

## Features

- **Accounts** — open an account (optional initial deposit), list accounts, check balances
- **Deposits / withdrawals** — with overdraft rejection
- **Atomic transfers** — both legs succeed or neither does; money is never created or destroyed
- **Immutable ledger** — every movement writes an audit row with the post-transaction balance
- **Web console** — a vanilla-JS dashboard served by the same app (no build step) at `http://localhost:8080`

## API

| Method | Path | Description | Errors |
|---|---|---|---|
| `POST` | `/api/accounts` | Open account `{ownerName, initialDeposit?}` | 400 validation |
| `GET` | `/api/accounts` | List all accounts | |
| `GET` | `/api/accounts/{id}` | Account details + balance | 404 |
| `POST` | `/api/accounts/{id}/deposit` | `{amount}` | 400, 404 |
| `POST` | `/api/accounts/{id}/withdraw` | `{amount}` | 400, 404, **409 insufficient funds** |
| `GET` | `/api/accounts/{id}/transactions` | Ledger, newest first | 404 |
| `POST` | `/api/transfers` | `{fromAccountId, toAccountId, amount}`; optional `Idempotency-Key` header | 400, 404, 409, **422 key reused** |

Business and validation errors (the codes in the table) return a consistent JSON body:

```json
{ "status": 409, "code": "INSUFFICIENT_FUNDS", "message": "Account 1 has insufficient funds: balance 5.00, requested 10.00", "timestamp": "..." }
```

Errors raised by the framework or the database don't go through that handler yet: a non-numeric id such as `/api/accounts/abc` returns a 400 with an empty body, and an amount too large for the `numeric(19,2)` column returns a 500.

## Engineering decisions worth asking me about

- **`BigDecimal` for money, never `double`.** Binary floating point cannot represent 0.10 exactly; ledgers drift. Amounts are validated to ≤ 2 decimal places at the service boundary.
- **Pessimistic row locks for concurrent updates.** Balance updates use `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) so two simultaneous withdrawals can't both read the same balance and double-spend.
- **Deadlock-free transfers.** A transfer locks both accounts **in ascending id order**. Two opposite transfers (A→B and B→A) therefore acquire locks in the same order and can never deadlock each other. `TransferConcurrencyTest` checks this on a real PostgreSQL in CI: 100 opposite transfers released at once, with no deadlock, no money created or destroyed, and no balance below zero.
- **Transfers are one database transaction.** The debit, credit, and both ledger rows commit together; if the debit fails everything rolls back (`@Transactional`).
- **Idempotent transfers.** A client that times out can't tell whether its transfer happened, so it retries. With an `Idempotency-Key` header, the key's row is inserted first, in the same transaction as the transfer, and its primary key rejects a second use. A retry with the same key and body gets the stored response back (`Idempotent-Replayed: true`) and moves no money; the same key with a different body is rejected with 422; a transfer that fails rolls its key back, so retrying it runs it for real. `TransferConcurrencyTest` sends one request 8 times at once on a real PostgreSQL and checks the money moves once. The web console sends a key with every transfer, so a double-click can't pay twice.
- **Ledger rows are immutable and store `balanceAfter`.** You can audit any account's history without replaying it, and no code path ever updates or deletes a ledger row. Both legs of a transfer share a `transferId`, so they can be paired exactly.

## Ledger reconciliation (SQL)

[`sql/reconciliation.sql`](sql/reconciliation.sql) holds five checks. Each returns the rows that break one rule, so an empty result means the ledger is consistent:

| Check | Rule | SQL used |
|---|---|---|
| `balance_matches_ledger` | Every stored balance equals the sum of its account's ledger | `LEFT JOIN`, `GROUP BY … HAVING` |
| `balance_after_is_running_total` | Every ledger row's `balance_after` equals the running total up to that row | window function `SUM() OVER (PARTITION BY account_id ORDER BY id)` |
| `transfer_legs_pair_up` | Every transfer has exactly one out leg and one in leg, same amount, each naming the other account | `COUNT(*) FILTER (WHERE …)` |
| `no_negative_balances` | No balance, and no point in any ledger history, is below zero | `UNION ALL` |
| `money_is_conserved` | Money in the bank = deposits − withdrawals; transfers only move it | CTE with scalar subqueries |

The running total is ordered by ledger `id`, not timestamp: a ledger row is inserted while its account's row lock is held, so within one account the id order is the order the balance changes were applied.

```bash
psql -d banking -f sql/reconciliation.sql
```

`LedgerReconciliationTest` runs every check on PostgreSQL in CI after a workload that includes concurrent transfers. A check that never fires proves nothing, so the test also breaks the data once per check (an edited balance, an edited ledger row, a deleted transfer leg, an overdraft, money from nowhere), inside a rolled-back transaction, and asserts the check reports exactly that row.

## Running locally

Requires JDK 21+. No database install needed — defaults to in-memory H2.

```bash
mvn spring-boot:run
# UI:  http://localhost:8080
# API: http://localhost:8080/api/accounts
```

To run against PostgreSQL instead:

```bash
createdb banking
mvn spring-boot:run -Dspring-boot.run.profiles=postgres   # DB_USER / DB_PASSWORD env vars
```

## Operational endpoints (Spring Boot Actuator)

Production services need to prove they're healthy before a load balancer sends them traffic. Actuator exposes:

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall status, including a real database connectivity check (`db: UP`), not just "the process exists" |
| `/actuator/metrics` | JVM memory, HTTP request latencies (`http.server.requests`), connection-pool stats |
| `/actuator/info` | App name/version for deployment verification |

As configured here, all three are public and `health` shows full details (database type, disk space), which suits a local demo. A real deployment would expose only `health` (`management.endpoints.web.exposure.include=health`), hide its details from anonymous callers (`management.endpoint.health.show-details=when-authorized`), and serve metrics to Prometheus on an internal management port (`management.server.port`). On Kubernetes, Spring Boot also adds `/actuator/health/liveness` and `/actuator/health/readiness` for the probes.

## Tests & CI

```bash
mvn test                          # 23 tests on H2; the 9 PostgreSQL tests are skipped
POSTGRES_TESTS=true mvn test      # all 32 (needs Docker)
```

Every push and pull request triggers a GitHub Actions pipeline (`.github/workflows/ci.yml`) that builds the project and runs all 32 tests on JDK 21, including the PostgreSQL ones (Testcontainers starts PostgreSQL 16 in Docker); failed runs upload the surefire reports as an artifact for debugging.

32 tests across three layers:

- **Service layer** (`AccountServiceTest`, 13 tests) — balance math, overdraft rejection, amount validation, transfer atomicity (failed transfers leave both balances and the ledger untouched), same-account rejection, ledger ordering
- **Web layer** (`AccountControllerTest`, 10 tests) — full HTTP round-trips through MockMvc asserting status codes and JSON error contracts (201/400/404/409/422), plus idempotency: a retry replays the first response and moves money once, a reused key with a different body gets 422, a failed transfer can be retried under the same key, and an over-long key is rejected
- **Concurrency on real PostgreSQL** (`TransferConcurrencyTest`, 3 tests) — a smoke test that the datasource really is PostgreSQL, and two races. Opposite transfers: 50 A→B and 50 B→A released together on 8 threads, asserting that nothing failed except for insufficient funds (a deadlock would fail it), the total is unchanged, no balance went below zero, and the ledger has one entry per side of every transfer that went through. Retry storm: one request with one `Idempotency-Key` sent 8 times at once, asserting exactly one copy moved money and the other 7 got its response back. H2 can't stand in here, because the claims under test are about PostgreSQL's row locks and unique index.
- **Ledger reconciliation on real PostgreSQL** (`LedgerReconciliationTest`, 6 tests) — every check in `sql/reconciliation.sql` passes on a clean ledger, and each one catches the corruption it is written for (see above).

## Repository structure

```
src/main/java/com/ray/banking/
├── model/        Account, TransactionRecord, IdempotencyRecord (JPA entities), TransactionType
├── repository/   Spring Data repositories (incl. SELECT FOR UPDATE lock)
├── service/      AccountService — business rules and transaction boundaries; IdempotencyService
├── web/          REST controllers, DTOs, global exception handler
src/main/resources/static/   web console (vanilla HTML/CSS/JS)
src/test/java/               service, controller, PostgreSQL concurrency and reconciliation tests
sql/reconciliation.sql       ledger reconciliation checks
```

## What I'd do next with more time

- JWT authentication and per-user account ownership
- Route every error through the JSON error body: cap amounts at the column's 17 integer digits (`@Digits`) and handle type mismatches and database errors
- Expire idempotency keys after 24 hours, scope them per client once there is authentication, and extend them to deposits and withdrawals
- Docker Compose (app + Postgres)
- Pagination on the transactions endpoint

## About me

Ray — MSCS, University of Delaware (May 2026), based in Newark, DE. Open to software engineering and data engineering roles. Related work: [credit card default risk analysis](https://github.com/Lego514/credit-card-default-risk) (SQL, XGBoost + SHAP, Tableau).
