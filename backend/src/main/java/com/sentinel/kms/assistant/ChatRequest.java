package com.sentinel.kms.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/assistant/chat[.stream].
 * {@code conversationId} may be null/blank to start a new conversation; it is
 * echoed back (and in the stream's done event) so the client can continue it.
 * {@code category} / {@code minSeverity} optionally scope retrieval to a KB
 * category and a minimum article severity.
 */
public record ChatRequest(
        @NotBlank String message,
        String conversationId,
        @Size(max = 200) String model,
        @Min(2) @Max(15) Integer topK,
        @DecimalMin("0.10") @DecimalMax("0.80") Double minScore,
        @Min(1) @Max(8) Integer historyTurns,
        @Size(max = 40) String category,
        @Pattern(regexp = "^(critical|high|medium|low|info)?$", message = "minSeverity must be a severity id")
        String minSeverity
) {
    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null, null, null, null, null, null);
    }
}
