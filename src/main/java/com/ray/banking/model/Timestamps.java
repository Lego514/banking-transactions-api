package com.ray.banking.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The clock for stored timestamps. The JVM's clock has nanosecond digits but
 * PostgreSQL and H2 timestamps keep microseconds, so an untruncated value
 * changes on its way to the database: a receipt returned when a transfer is
 * made would then disagree with the same receipt read back later.
 */
final class Timestamps {

    private Timestamps() {
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
