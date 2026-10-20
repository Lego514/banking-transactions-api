package com.ray.banking.service;

import com.ray.banking.exception.IdempotencyKeyInProgressException;
import com.ray.banking.exception.IdempotencyKeyReusedException;
import com.ray.banking.exception.InvalidTransactionException;
import com.ray.banking.model.IdempotencyRecord;
import com.ray.banking.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Runs a money-moving operation at most once per Idempotency-Key.
 *
 * <p>The key's row is inserted first, inside the same transaction as the
 * operation, and the primary key does the deduplication: a second request with
 * the same key cannot insert its row. On PostgreSQL a concurrent second insert
 * waits for the first transaction and fails once it commits. Either way the
 * second request replays the stored response instead of moving money again.
 *
 * <p>If the operation fails (insufficient funds, say), the key's row rolls back
 * with it, so retrying the same key runs the operation for real. Nothing moved,
 * so there is nothing to protect.
 *
 * <p>The transaction is opened here with a {@link TransactionTemplate} rather
 * than {@code @Transactional}, because the duplicate-key failure has to be
 * caught outside the transaction it aborted. PostgreSQL refuses every further
 * statement in an aborted transaction, so the lookup for the replay needs a
 * fresh one.
 */
@Service
public class IdempotencyService {

    static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyRecordRepository records;
    private final TransactionTemplate transaction;

    public IdempotencyService(IdempotencyRecordRepository records,
                              PlatformTransactionManager transactionManager) {
        this.records = records;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The response body to send, and whether it is a replay of an earlier request. */
    public record Result(String responseBody, boolean replayed) {
    }

    /**
     * @param request   a canonical description of the request (endpoint and
     *                  parameters). The same key with a different request is
     *                  rejected rather than replayed.
     * @param operation runs inside the transaction and returns the response body
     *                  to store
     */
    public Result execute(String key, String request, Supplier<String> operation) {
        validateKey(key);
        String requestHash = sha256(request);
        try {
            String responseBody = transaction.execute(status -> {
                IdempotencyRecord record = claim(key, requestHash);
                String body = operation.get();
                record.complete(body);
                return body;
            });
            return new Result(responseBody, false);
        } catch (KeyAlreadyClaimedException e) {
            return replay(key, requestHash);
        }
    }

    private IdempotencyRecord claim(String key, String requestHash) {
        try {
            // Flush now so the INSERT runs before the operation takes any account
            // locks: a duplicate waits here, holding nothing, instead of queueing
            // behind the row locks.
            return records.saveAndFlush(new IdempotencyRecord(key, requestHash));
        } catch (DataIntegrityViolationException duplicateKey) {
            throw new KeyAlreadyClaimedException();
        }
    }

    private Result replay(String key, String requestHash) {
        IdempotencyRecord record = records.findById(key)
                .orElseThrow(() -> new IdempotencyKeyInProgressException(key));
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        return new Result(record.getResponseBody(), true);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidTransactionException(
                    "Idempotency-Key must be 1 to %d characters".formatted(MAX_KEY_LENGTH));
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }

    /** Rolls the transaction back; caught outside it to replay the stored response. */
    private static class KeyAlreadyClaimedException extends RuntimeException {
    }
}
