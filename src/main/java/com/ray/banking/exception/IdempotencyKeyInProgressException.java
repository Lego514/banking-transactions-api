package com.ray.banking.exception;

/** Another request with the same key has not finished yet; the client should retry shortly. */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException(String key) {
        super("A request with Idempotency-Key %s is still in progress; retry shortly".formatted(key));
    }
}
