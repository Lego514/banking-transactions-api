package com.ray.banking.web.dto;

import com.ray.banking.model.Account;
import com.ray.banking.model.TransactionRecord;
import com.ray.banking.model.TransactionType;
import com.ray.banking.service.AccountService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Responses {

    private Responses() {
    }

    public record AccountResponse(Long id, String ownerName, BigDecimal balance, Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getOwnerName(),
                    account.getBalance(), account.getCreatedAt());
        }
    }

    public record TransactionResponse(Long id, TransactionType type, BigDecimal amount,
                                      BigDecimal balanceAfter, Long counterpartyAccountId,
                                      UUID transferId, Instant timestamp) {

        public static TransactionResponse from(TransactionRecord record) {
            return new TransactionResponse(record.getId(), record.getType(), record.getAmount(),
                    record.getBalanceAfter(), record.getCounterpartyAccountId(), record.getTransferId(),
                    record.getTimestamp());
        }
    }

    /** A transfer receipt: the payee appears only as an account id. */
    public record TransferResponse(UUID transferId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                                   BigDecimal fromBalanceAfter, Instant timestamp) {

        public static TransferResponse from(AccountService.Transfer transfer) {
            return new TransferResponse(transfer.transferId(), transfer.fromAccountId(), transfer.toAccountId(),
                    transfer.amount(), transfer.fromBalanceAfter(), transfer.timestamp());
        }
    }
}
