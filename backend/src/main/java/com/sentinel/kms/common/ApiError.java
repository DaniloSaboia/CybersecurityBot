package com.sentinel.kms.common;

import java.time.OffsetDateTime;

/**
 * Uniform error body returned by {@code GlobalExceptionHandler}.
 */
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path);
    }
}
