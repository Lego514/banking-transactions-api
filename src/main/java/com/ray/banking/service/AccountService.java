package com.ray.banking.service;

import com.ray.banking.exception.AccountNotFoundException;
import com.ray.banking.exception.InvalidTransactionException;
import com.ray.banking.model.Account;
import com.ray.banking.model.TransactionRecord;
import com.ray.banking.model.TransactionType;
import com.ray.banking.repository.AccountRepository;
import com.ray.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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
            record(account, TransactionType.DEPOSIT, initialDeposit, null);
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
        record(account, TransactionType.DEPOSIT, amount, null);
        return account;
    }

    @Transactional
    public Account withdraw(Long accountId, BigDecimal amount) {
        validateAmount(amount);
        Account account = lock(accountId);
        account.debit(amount);
        record(account, TransactionType.WITHDRAWAL, amount, null);
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
        record(from, TransactionType.TRANSFER_OUT, amount, to.getId());
        record(to, TransactionType.TRANSFER_IN, amount, from.getId());
        return new Transfer(from, to);
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

    private void record(Account account, TransactionType type, BigDecimal amount, Long counterpartyId) {
        transactionRepository.save(new TransactionRecord(
                account.getId(), type, amount, account.getBalance(), counterpartyId));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidTransactionException("Amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidTransactionException("Amount cannot have more than 2 decimal places");
        }
    }

    public record Transfer(Account from, Account to) {
    }
}
