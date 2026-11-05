package com.ray.banking.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class Requests {

    /**
     * The largest amount a money column, numeric(19,2), can hold. A larger one
     * used to reach the database and come back as a 500. The two-decimal rule
     * stays in AccountService, which ignores trailing zeros (10.500 is fine);
     * {@code @Digits} would count them and reject it.
     */
    private static final String MAX_AMOUNT = "99999999999999999.99";

    private Requests() {
    }

    public record CreateAccountRequest(
            @NotBlank(message = "ownerName is required")
            @Size(max = 100, message = "ownerName must be at most 100 characters")
            String ownerName,
            @PositiveOrZero(message = "initialDeposit cannot be negative")
            @DecimalMax(value = MAX_AMOUNT, message = "initialDeposit must be at most " + MAX_AMOUNT)
            BigDecimal initialDeposit) {
    }

    public record AmountRequest(
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be positive")
            @DecimalMax(value = MAX_AMOUNT, message = "amount must be at most " + MAX_AMOUNT)
            BigDecimal amount) {
    }

    public record TransferRequest(
            @NotNull(message = "fromAccountId is required")
            Long fromAccountId,
            @NotNull(message = "toAccountId is required")
            Long toAccountId,
            @NotNull(message = "amount is required")
            @Positive(message = "amount must be positive")
            @DecimalMax(value = MAX_AMOUNT, message = "amount must be at most " + MAX_AMOUNT)
            BigDecimal amount) {
    }
}
