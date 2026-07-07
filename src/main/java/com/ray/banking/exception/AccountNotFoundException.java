package com.ray.banking.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long accountId) {
        super("Account %d not found".formatted(accountId));
    }
}
