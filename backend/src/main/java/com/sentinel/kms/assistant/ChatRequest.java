package com.sentinel.kms.assistant;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/assistant/chat[.stream].
 * {@code conversationId} may be null/blank to start a new conversation; it is
 * echoed back (and in the stream's done event) so the client can continue it.
 */
public record ChatRequest(
        @NotBlank String message,
        String conversationId
) {
}
