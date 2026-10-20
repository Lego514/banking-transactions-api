package com.ray.banking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable ledger entry. One row per account per money movement — a transfer
 * writes two rows (TRANSFER_OUT on the source, TRANSFER_IN on the destination),
 * each carrying the account's balance after the movement for auditability.
 */
@Entity
@Table(name = "account_transactions",
        indexes = {
                @Index(name = "idx_txn_account", columnList = "accountId"),
                @Index(name = "idx_txn_transfer", columnList = "transferId")
        })
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    /** The other account in a transfer; null for deposits and withdrawals. */
    private Long counterpartyAccountId;

    /**
     * Shared by the two legs of one transfer, so they can be paired exactly;
     * null for deposits and withdrawals. The counterparty alone can't do it:
     * two transfers of the same amount between the same accounts look alike.
     */
    private UUID transferId;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    protected TransactionRecord() {
        // required by JPA
    }

    public TransactionRecord(Long accountId, TransactionType type, BigDecimal amount,
                             BigDecimal balanceAfter, Long counterpartyAccountId, UUID transferId) {
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.counterpartyAccountId = counterpartyAccountId;
        this.transferId = transferId;
        this.timestamp = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Long getCounterpartyAccountId() {
        return counterpartyAccountId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
