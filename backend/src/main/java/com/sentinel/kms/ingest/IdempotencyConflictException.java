package com.sentinel.kms.ingest;

/** Raised when an owner reuses a request key for a different payload. */
final class IdempotencyConflictException extends RuntimeException {

    IdempotencyConflictException(String message) {
        super(message);
    }
}
