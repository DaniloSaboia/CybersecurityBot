package com.sentinel.kms.assistant;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for POST /api/assistant/chat.
 * {@code conversationId} may be null to start a new conversation;
 * {@code contextRefs} are optional attachment handles (future use).
 */
public record ChatRequest(
        @NotBlank String message,
        String conversationId,
        List<String> contextRefs
) {
}
