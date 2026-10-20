package com.ray.banking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One row per Idempotency-Key a client has used. It is written in the same
 * database transaction as the money movement it guards, so the two commit or
 * roll back together: a key only counts as used once its transfer happened.
 *
 * <p>Why {@link Persistable}: the id is assigned by the client, not generated,
 * so Spring Data would otherwise call {@code merge} on save. Merge looks the
 * row up first and, if a committed one exists, UPDATES it - a retry would
 * silently overwrite the record and move the money again. Reporting the
 * entity as always new forces a plain INSERT, and the primary key then
 * rejects the duplicate.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(length = 255)
    private String idempotencyKey;

    /** SHA-256 of the request the key was first used with. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    /** The JSON body returned the first time, replayed on every retry. */
    @Column(length = 4000)
    private String responseBody;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() {
        // required by JPA
    }

    public IdempotencyRecord(String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    public void complete(String responseBody) {
        this.responseBody = responseBody;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
