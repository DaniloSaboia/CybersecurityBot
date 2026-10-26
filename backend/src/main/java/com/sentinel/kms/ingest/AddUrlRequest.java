package com.sentinel.kms.ingest;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/ingest/url}.
 */
public record AddUrlRequest(
        @NotBlank String url
) {
}
