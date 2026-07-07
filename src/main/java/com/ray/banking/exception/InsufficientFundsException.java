package com.ray.banking.exception;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId, BigDecimal balance, BigDecimal requested) {
        super("Account %d has insufficient funds: balance %s, requested %s"
                .formatted(accountId, balance.toPlainString(), requested.toPlainString()));
    }
}
