package com.ray.banking.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class Requests {

    private Requests() {
    }

    public record CreateAccountRequest(
            @NotBlank(message = "ownerName is required")
            @Size(max = 100, message = "ownerName must be at most 100 characters")
            String ownerName,
            @PositiveOrZero(message = "initialDeposit cannot be negative")
            BigDecimal initialDeposit) {
    }

    public record AmountRequest(
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be positive")
            BigDecimal amount) {
    }

    public record TransferRequest(
            @NotNull(message = "fromAccountId is required")
            Long fromAccountId,
            @NotNull(message = "toAccountId is required")
            Long toAccountId,
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be positive")
            BigDecimal amount) {
    }
}
