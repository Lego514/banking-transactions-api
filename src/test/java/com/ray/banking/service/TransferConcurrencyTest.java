package com.ray.banking.service;

import com.ray.banking.model.Account;
import org.junit.jupiter.api.Disabled;
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

    @Test
    @Disabled("TODO(you): we write this one together, one step at a time")
    void oppositeTransfersAtTheSameTime_neitherDeadlockNorCreateMoney() throws Exception {
        // Step 1 - Arrange: two accounts with known balances, and their total.
        // Step 2 - The work: a list of tasks, half moving A to B, half moving B to A.
        // Step 3 - Start them together: a thread pool, plus a CountDownLatch as the starting gate.
        // Step 4 - Collect outcomes: count the successes, keep any unexpected exception.
        // Step 5 - Assert: no unexpected exceptions, the total is unchanged, no balance below
        //          zero, and one ledger entry per side of every successful transfer.
        // Step 6 - Prove the test can fail: break the lock ordering in AccountService, watch
        //          this test catch the deadlock, then put the ordering back.
    }
}
