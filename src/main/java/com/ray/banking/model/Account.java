package com.ray.banking.model;

import com.ray.banking.exception.InsufficientFundsException;
import com.ray.banking.exception.InvalidTransactionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    /** The largest value the balance column, numeric(19,2), can hold. */
    public static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ownerName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // required by JPA
    }

    public Account(String ownerName) {
        this.ownerName = ownerName;
        this.balance = BigDecimal.ZERO;
        this.createdAt = Timestamps.now();
    }

    /**
     * Adds funds to the account. Amount must already be validated as positive.
     * Each amount fits the column on its own, but a sum of them may not, so the
     * new balance is checked here rather than left to fail in the database.
     */
    public void credit(BigDecimal amount) {
        BigDecimal newBalance = this.balance.add(amount);
        if (newBalance.compareTo(MAX_BALANCE) > 0) {
            throw new InvalidTransactionException("Account %d cannot hold more than %s"
                    .formatted(id, MAX_BALANCE.toPlainString()));
        }
        this.balance = newBalance;
    }

    /** Removes funds from the account, rejecting overdrafts. */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public Long getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
