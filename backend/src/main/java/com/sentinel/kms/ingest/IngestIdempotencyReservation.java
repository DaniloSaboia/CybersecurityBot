package com.sentinel.kms.ingest;

/** Result of atomically reserving an owner-scoped idempotency key. */
public record IngestIdempotencyReservation(boolean newlyClaimed, String jobId) {

    static IngestIdempotencyReservation claimed(String jobId) {
        return new IngestIdempotencyReservation(true, jobId);
    }

    static IngestIdempotencyReservation replay(String jobId) {
        return new IngestIdempotencyReservation(false, jobId);
    }
}
