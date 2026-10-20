package com.ray.banking.service;

import com.ray.banking.exception.InsufficientFundsException;
import com.ray.banking.model.Account;
import com.ray.banking.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers under real concurrency, on a real PostgreSQL.
 *
 * <p>Why not {@code @DataJpaTest} like {@link AccountServiceTest}: it runs every
 * test inside one transaction that is rolled back at the end, and it swaps the
 * database for H2. A concurrency test needs the opposite on both counts. Each
 * thread's transfer has to commit on its own, and the row locks have to be
 * PostgreSQL's, because the claim under test is how PostgreSQL's
 * {@code SELECT ... FOR UPDATE} behaves when two transfers collide.
 *
 * <p>Needs Docker, so it only runs when {@code POSTGRES_TESTS=true}; CI sets it.
 * On a machine with Docker: {@code set POSTGRES_TESTS=true} then {@code mvn test}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "true",
        disabledReason = "needs Docker; CI sets POSTGRES_TESTS=true")
class TransferConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountService service;

    @Autowired
    private DataSource dataSource;

    /** Proves the setup itself: these tests really talk to PostgreSQL, and a transfer commits. */
    @Test
    void runsAgainstRealPostgres() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        Account alice = service.createAccount("Alice", new BigDecimal("100.00"));
        Account bob = service.createAccount("Bob", null);
        service.transfer(alice.getId(), bob.getId(), new BigDecimal("40.00"));

        assertThat(service.getAccount(alice.getId()).getBalance()).isEqualByComparingTo("60.00");
        assertThat(service.getAccount(bob.getId()).getBalance()).isEqualByComparingTo("40.00");
    }

    private static final int THREADS = 8;
    private static final int TRANSFERS_EACH_WAY = 50;
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    /**
     * Many A-to-B and B-to-A transfers released at the same instant.
     *
     * <p>The deadlock this guards against: if a transfer locked its rows in
     * request order (from, then to), an A-to-B holding A and a B-to-A holding B
     * would each wait for the other forever. PostgreSQL detects the cycle and
     * aborts one of them, which this test reports as an unexpected exception.
     * {@code AccountService.transfer} avoids it by always locking the lower id
     * first, so both directions queue on the same row.
     *
     * <p>Step 6, done by hand rather than in code: to see this test fail, make
     * {@code transfer} lock {@code fromId} then {@code toId}, run it, watch it
     * report the deadlock, then put the ordering back.
     */
    @Test
    void oppositeTransfersAtTheSameTime_neitherDeadlockNorCreateMoney() throws Exception {
        // Step 1 - Arrange: two committed accounts, and their total before the race.
        // createAccount commits on return, and this method has no transaction of its
        // own, so the worker threads (each on its own connection) can see both rows.
        // Balances are tight on purpose: either side can run dry mid-race, so some
        // transfers may be refused, and "no balance below zero" is a real check.
        long a = service.createAccount("A", new BigDecimal("100.00")).getId();
        long b = service.createAccount("B", new BigDecimal("100.00")).getId();
        BigDecimal totalBefore = balanceOf(a).add(balanceOf(b));

        // Step 2 - The work: half the tasks move A to B, half move B to A.
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < TRANSFERS_EACH_WAY; i++) {
            tasks.add(() -> tryTransfer(a, b));
            tasks.add(() -> tryTransfer(b, a));
        }

        // Step 3 - Start them together: every task waits at the gate, then all go at once.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        int succeeded = 0;
        List<Throwable> unexpected = new ArrayList<>();
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                outcomes.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();

            // Step 4 - Collect outcomes. A transfer that hangs fails the test here,
            // on the timeout, instead of hanging the build.
            for (Future<Boolean> outcome : outcomes) {
                try {
                    if (outcome.get(30, TimeUnit.SECONDS)) {
                        succeeded++;
                    }
                } catch (ExecutionException e) {
                    unexpected.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Step 5 - Assert: nothing failed except for lack of funds, no money was created
        // or destroyed, nobody went below zero, and the ledger has one entry per side of
        // every transfer that went through.
        assertThat(unexpected).as("exceptions other than insufficient funds").isEmpty();
        assertThat(succeeded).as("transfers that went through").isPositive();

        BigDecimal balanceA = balanceOf(a);
        BigDecimal balanceB = balanceOf(b);
        assertThat(balanceA.add(balanceB)).isEqualByComparingTo(totalBefore);
        assertThat(balanceA).isNotNegative();
        assertThat(balanceB).isNotNegative();
        assertThat(ledgerEntries(a, b, TransactionType.TRANSFER_OUT)).isEqualTo(succeeded);
        assertThat(ledgerEntries(a, b, TransactionType.TRANSFER_IN)).isEqualTo(succeeded);
    }

    /** True if the transfer went through; false if it was refused for lack of funds, the one failure allowed here. */
    private boolean tryTransfer(long from, long to) {
        try {
            service.transfer(from, to, AMOUNT);
            return true;
        } catch (InsufficientFundsException e) {
            return false;
        }
    }

    private BigDecimal balanceOf(long accountId) {
        return service.getAccount(accountId).getBalance();
    }

    private long ledgerEntries(long firstAccountId, long secondAccountId, TransactionType type) {
        return Stream.of(firstAccountId, secondAccountId)
                .flatMap(id -> service.getTransactions(id).stream())
                .filter(entry -> entry.getType() == type)
                .count();
    }
}
