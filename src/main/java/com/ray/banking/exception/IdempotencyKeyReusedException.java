package com.ray.banking.exception;

/** The key was already used for a different request. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key %s was already used with a different request".formatted(key));
    }
}
