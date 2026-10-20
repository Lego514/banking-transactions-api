package com.ray.banking.service;

import com.ray.banking.exception.InsufficientFundsException;
import com.ray.banking.model.TransactionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the checks in {@code sql/reconciliation.sql} against a real PostgreSQL.
 *
 * <p>A check that never finds anything proves nothing, so besides running every
 * check over a clean ledger, each check gets a test that breaks the data in the
 * one way it is written to catch, inside a transaction that is rolled back
 * afterwards, and asserts the check reports exactly the broken row.
 *
 * <p>Needs Docker, so it only runs when {@code POSTGRES_TESTS=true}; CI sets it.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "true",
        disabledReason = "needs Docker; CI sets POSTGRES_TESTS=true")
class LedgerReconciliationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Pattern CHECK_HEADER = Pattern.compile("(?m)^-- check: (\\w+)\\s*$");

    /** Check name to SQL, in file order. */
    private static final Map<String, String> CHECKS = loadChecks(Path.of("sql/reconciliation.sql"));

    @Autowired
    private AccountService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // One committed transfer for the corruption tests to break: payer -> payee, 30.00.
    private long payer;
    private long payee;
    private TransactionRecord outLeg;

    @BeforeEach
    void commitOneTransfer() {
        payer = service.createAccount("Payer", new BigDecimal("100.00")).getId();
        payee = service.createAccount("Payee", null).getId();
        service.transfer(payer, payee, new BigDecimal("30.00"));
        outLeg = service.getTransactions(payer).get(0); // newest first: the TRANSFER_OUT
    }

    @Test
    void cleanLedger_passesEveryCheck() {
        long a = service.createAccount("A", new BigDecimal("500.00")).getId();
        long b = service.createAccount("B", new BigDecimal("50.00")).getId();
        long c = service.createAccount("C", null).getId();
        service.deposit(a, new BigDecimal("120.25"));
        service.withdraw(b, new BigDecimal("20.00"));
        service.transfer(a, c, new BigDecimal("75.50"));
        service.transfer(c, b, new BigDecimal("25.00"));
        assertThatThrownBy(() -> service.transfer(b, a, new BigDecimal("1000.00")))
                .isInstanceOf(InsufficientFundsException.class);
        // Concurrent opposite transfers, so the running-total check sees ledger
        // rows written by racing transactions, not just one at a time.
        IntStream.range(0, 40).parallel()
                .forEach(i -> tryTransfer(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a));

        assertThat(CHECKS).hasSize(5);
        CHECKS.forEach((name, sql) -> assertThat(jdbc.queryForList(sql)).as(name).isEmpty());
    }

    @Test
    void balanceMatchesLedger_catchesABalanceChangedWithoutALedgerRow() {
        List<Map<String, Object>> rows = withCorruption(
                "UPDATE accounts SET balance = balance + 0.01 WHERE id = " + payer,
                "balance_matches_ledger");

        assertThat(rows).extracting(row -> row.get("account_id")).containsExactly(payer);
    }

    @Test
    void balanceAfterIsRunningTotal_catchesAnEditedLedgerRow() {
        List<Map<String, Object>> rows = withCorruption(
                "UPDATE account_transactions SET balance_after = balance_after + 5 WHERE id = " + outLeg.getId(),
                "balance_after_is_running_total");

        assertThat(rows).extracting(row -> row.get("ledger_id")).containsExactly(outLeg.getId());
    }

    @Test
    void transferLegsPairUp_catchesAMissingLeg() {
        List<Map<String, Object>> rows = withCorruption(
                "DELETE FROM account_transactions WHERE type = 'TRANSFER_IN' AND transfer_id = '%s'"
                        .formatted(outLeg.getTransferId()),
                "transfer_legs_pair_up");

        assertThat(rows).extracting(row -> row.get("transfer_id")).containsExactly(outLeg.getTransferId());
    }

    @Test
    void noNegativeBalances_catchesAnOverdraft() {
        List<Map<String, Object>> rows = withCorruption(
                "UPDATE accounts SET balance = -1.00 WHERE id = " + payee,
                "no_negative_balances");

        assertThat(rows).extracting(row -> row.get("row_id")).containsExactly(payee);
    }

    @Test
    void moneyIsConserved_catchesMoneyThatAppearedFromNowhere() {
        List<Map<String, Object>> rows = withCorruption(
                "UPDATE accounts SET balance = balance + 1000 WHERE id = " + payee,
                "money_is_conserved");

        assertThat(rows).hasSize(1);
    }

    /**
     * Applies the corruption and runs the check inside one transaction, then rolls
     * it back, so the other tests keep a clean ledger.
     */
    private List<Map<String, Object>> withCorruption(String corruptingSql, String check) {
        assertThat(CHECKS).containsKey(check);
        return inRolledBackTransaction(() -> {
            assertThat(jdbc.update(corruptingSql)).as("rows corrupted").isEqualTo(1);
            return jdbc.queryForList(CHECKS.get(check));
        });
    }

    private <T> T inRolledBackTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    private void tryTransfer(long from, long to) {
        try {
            service.transfer(from, to, new BigDecimal("10.00"));
        } catch (InsufficientFundsException e) {
            // allowed: the race can drain an account
        }
    }

    /** Splits the file on its "-- check: name" headers. */
    private static Map<String, String> loadChecks(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file.toAbsolutePath(), e);
        }
        Map<String, String> checks = new LinkedHashMap<>();
        Matcher header = CHECK_HEADER.matcher(text);
        String name = null;
        int start = 0;
        while (header.find()) {
            if (name != null) {
                checks.put(name, statement(text.substring(start, header.start())));
            }
            name = header.group(1);
            start = header.end();
        }
        if (name != null) {
            checks.put(name, statement(text.substring(start)));
        }
        return checks;
    }

    private static String statement(String chunk) {
        String sql = chunk.strip();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }
}
