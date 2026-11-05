package com.ray.banking.service;

import com.ray.banking.exception.AccountNotFoundException;
import com.ray.banking.exception.InvalidTransactionException;
import com.ray.banking.exception.TransferNotFoundException;
import com.ray.banking.model.Account;
import com.ray.banking.model.TransactionRecord;
import com.ray.banking.model.TransactionType;
import com.ray.banking.repository.AccountRepository;
import com.ray.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Account createAccount(String ownerName, BigDecimal initialDeposit) {
        Account account = accountRepository.save(new Account(ownerName.trim()));
        if (initialDeposit != null && initialDeposit.signum() > 0) {
            validateAmount(initialDeposit);
            account.credit(initialDeposit);
            record(account, TransactionType.DEPOSIT, initialDeposit, null, null);
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Account> listAccounts() {
        return accountRepository.findAll();
    }

    @Transactional
    public Account deposit(Long accountId, BigDecimal amount) {
        validateAmount(amount);
        Account account = lock(accountId);
        account.credit(amount);
        record(account, TransactionType.DEPOSIT, amount, null, null);
        return account;
    }

    @Transactional
    public Account withdraw(Long accountId, BigDecimal amount) {
        validateAmount(amount);
        Account account = lock(accountId);
        account.debit(amount);
        record(account, TransactionType.WITHDRAWAL, amount, null, null);
        return account;
    }

    /**
     * Moves money atomically between two accounts. Both rows are locked in
     * ascending id order — a fixed lock order means two opposite transfers
     * running concurrently cannot deadlock. If the debit fails the whole
     * transaction rolls back, so money is never created or destroyed.
     */
    @Transactional
    public Transfer transfer(Long fromId, Long toId, BigDecimal amount) {
        validateAmount(amount);
        if (fromId.equals(toId)) {
            throw new InvalidTransactionException("Cannot transfer to the same account");
        }

        Account first = lock(Math.min(fromId, toId));
        Account second = lock(Math.max(fromId, toId));
        Account from = first.getId().equals(fromId) ? first : second;
        Account to = first.getId().equals(fromId) ? second : first;

        from.debit(amount);
        to.credit(amount);
        UUID transferId = UUID.randomUUID();
        TransactionRecord outLeg = record(from, TransactionType.TRANSFER_OUT, amount, to.getId(), transferId);
        record(to, TransactionType.TRANSFER_IN, amount, from.getId(), transferId);
        return Transfer.from(outLeg);
    }

    /** The receipt of a past transfer, read back from the payer's ledger row. */
    @Transactional(readOnly = true)
    public Transfer getTransfer(UUID transferId) {
        return transactionRepository.findByTransferIdAndType(transferId, TransactionType.TRANSFER_OUT)
                .map(Transfer::from)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    @Transactional(readOnly = true)
    public List<TransactionRecord> getTransactions(Long accountId) {
        getAccount(accountId); // 404 if the account does not exist
        return transactionRepository.findByAccountIdOrderByTimestampDescIdDesc(accountId);
    }

    private Account lock(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private TransactionRecord record(Account account, TransactionType type, BigDecimal amount,
                                     Long counterpartyId, UUID transferId) {
        return transactionRepository.save(new TransactionRecord(
                account.getId(), type, amount, account.getBalance(), counterpartyId, transferId));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransactionException("Amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidTransactionException("Amount cannot have more than 2 decimal places");
        }
    }

    /**
     * A transfer receipt. It is built from the payer's ledger row alone, so it
     * carries the payer's balance after the transfer and nothing about the
     * payee beyond the account id the payer already knew: a transfer must not
     * tell the payer someone else's name or balance.
     */
    public record Transfer(UUID transferId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                           BigDecimal fromBalanceAfter, Instant timestamp) {

        static Transfer from(TransactionRecord outLeg) {
            return new Transfer(outLeg.getTransferId(), outLeg.getAccountId(), outLeg.getCounterpartyAccountId(),
                    outLeg.getAmount(), outLeg.getBalanceAfter(), outLeg.getTimestamp());
        }
    }
}
