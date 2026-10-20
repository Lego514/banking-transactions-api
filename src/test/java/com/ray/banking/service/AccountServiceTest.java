package com.ray.banking.service;

import com.ray.banking.exception.AccountNotFoundException;
import com.ray.banking.exception.InsufficientFundsException;
import com.ray.banking.exception.InvalidTransactionException;
import com.ray.banking.model.Account;
import com.ray.banking.model.TransactionRecord;
import com.ray.banking.model.TransactionType;
import com.ray.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(AccountService.class)
class AccountServiceTest {

    @Autowired
    private AccountService service;

    @Autowired
    private TransactionRepository transactionRepository;

    @Nested
    class CreateAccount {

        @Test
        void withInitialDeposit_setsBalanceAndRecordsDeposit() {
            Account account = service.createAccount("Alice", new BigDecimal("500.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
            List<TransactionRecord> txns = service.getTransactions(account.getId());
            assertThat(txns).hasSize(1);
            assertThat(txns.get(0).getType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(txns.get(0).getBalanceAfter()).isEqualByComparingTo("500.00");
        }

        @Test
        void withoutInitialDeposit_startsAtZeroWithEmptyLedger() {
            Account account = service.createAccount("Bob", null);

            assertThat(account.getBalance()).isEqualByComparingTo("0");
            assertThat(service.getTransactions(account.getId())).isEmpty();
        }
    }

    @Nested
    class Deposit {

        @Test
        void increasesBalanceAndWritesLedgerEntry() {
            Account account = service.createAccount("Alice", new BigDecimal("100.00"));

            Account updated = service.deposit(account.getId(), new BigDecimal("25.50"));

            assertThat(updated.getBalance()).isEqualByComparingTo("125.50");
            assertThat(service.getTransactions(account.getId()))
                    .extracting(TransactionRecord::getType)
                    .containsExactly(TransactionType.DEPOSIT, TransactionType.DEPOSIT);
        }

        @Test
        void nonPositiveAmount_isRejected() {
            Account account = service.createAccount("Alice", null);

            assertThatThrownBy(() -> service.deposit(account.getId(), BigDecimal.ZERO))
                    .isInstanceOf(InvalidTransactionException.class);
        }

        @Test
        void amountWithMoreThanTwoDecimals_isRejected() {
            Account account = service.createAccount("Alice", null);

            assertThatThrownBy(() -> service.deposit(account.getId(), new BigDecimal("10.001")))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("2 decimal places");
        }
    }

    @Nested
    class Withdraw {

        @Test
        void decreasesBalanceAndWritesLedgerEntry() {
            Account account = service.createAccount("Alice", new BigDecimal("100.00"));

            Account updated = service.withdraw(account.getId(), new BigDecimal("40.00"));

            assertThat(updated.getBalance()).isEqualByComparingTo("60.00");
            List<TransactionRecord> txns = service.getTransactions(account.getId());
            assertThat(txns.get(0).getType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(txns.get(0).getBalanceAfter()).isEqualByComparingTo("60.00");
        }

        @Test
        void moreThanBalance_throwsAndLeavesBalanceUntouched() {
            Account account = service.createAccount("Alice", new BigDecimal("30.00"));

            assertThatThrownBy(() -> service.withdraw(account.getId(), new BigDecimal("30.01")))
                    .isInstanceOf(InsufficientFundsException.class);
            assertThat(service.getAccount(account.getId()).getBalance()).isEqualByComparingTo("30.00");
        }
    }

    @Nested
    class TransferMoney {

        @Test
        void movesMoneyAtomicallyAndWritesBothLedgerEntries() {
            Account alice = service.createAccount("Alice", new BigDecimal("200.00"));
            Account bob = service.createAccount("Bob", null);

            AccountService.Transfer result =
                    service.transfer(alice.getId(), bob.getId(), new BigDecimal("75.00"));

            assertThat(result.from().getBalance()).isEqualByComparingTo("125.00");
            assertThat(result.to().getBalance()).isEqualByComparingTo("75.00");

            List<TransactionRecord> aliceTxns = service.getTransactions(alice.getId());
            assertThat(aliceTxns.get(0).getType()).isEqualTo(TransactionType.TRANSFER_OUT);
            assertThat(aliceTxns.get(0).getCounterpartyAccountId()).isEqualTo(bob.getId());

            List<TransactionRecord> bobTxns = service.getTransactions(bob.getId());
            assertThat(bobTxns.get(0).getType()).isEqualTo(TransactionType.TRANSFER_IN);
            assertThat(bobTxns.get(0).getCounterpartyAccountId()).isEqualTo(alice.getId());

            // Both legs carry the same transfer id; the initial deposit has none.
            assertThat(aliceTxns.get(0).getTransferId())
                    .isNotNull()
                    .isEqualTo(bobTxns.get(0).getTransferId());
            assertThat(aliceTxns.get(1).getTransferId()).isNull();
        }

        @Test
        void insufficientFunds_leavesBothBalancesUnchanged() {
            Account alice = service.createAccount("Alice", new BigDecimal("50.00"));
            Account bob = service.createAccount("Bob", new BigDecimal("10.00"));

            assertThatThrownBy(() -> service.transfer(alice.getId(), bob.getId(), new BigDecimal("50.01")))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(service.getAccount(alice.getId()).getBalance()).isEqualByComparingTo("50.00");
            assertThat(service.getAccount(bob.getId()).getBalance()).isEqualByComparingTo("10.00");
            assertThat(transactionRepository.findByAccountIdOrderByTimestampDescIdDesc(alice.getId()))
                    .extracting(TransactionRecord::getType)
                    .containsExactly(TransactionType.DEPOSIT); // only the initial deposit
        }

        @Test
        void toSameAccount_isRejected() {
            Account alice = service.createAccount("Alice", new BigDecimal("100.00"));

            assertThatThrownBy(() -> service.transfer(alice.getId(), alice.getId(), BigDecimal.ONE))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("same account");
        }

        @Test
        void unknownDestination_throwsNotFound() {
            Account alice = service.createAccount("Alice", new BigDecimal("100.00"));

            assertThatThrownBy(() -> service.transfer(alice.getId(), 99999L, BigDecimal.ONE))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class Lookups {

        @Test
        void getAccount_unknownId_throwsNotFound() {
            assertThatThrownBy(() -> service.getAccount(99999L))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void transactionHistory_isNewestFirst() {
            Account account = service.createAccount("Alice", new BigDecimal("100.00"));
            service.deposit(account.getId(), new BigDecimal("1.00"));
            service.withdraw(account.getId(), new BigDecimal("2.00"));

            List<TransactionRecord> txns = service.getTransactions(account.getId());

            assertThat(txns).extracting(TransactionRecord::getType).containsExactly(
                    TransactionType.WITHDRAWAL, TransactionType.DEPOSIT, TransactionType.DEPOSIT);
        }
    }
}
