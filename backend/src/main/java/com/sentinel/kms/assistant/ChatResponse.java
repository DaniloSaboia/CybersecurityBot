package com.sentinel.kms.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sentinel.kms.common.EvidenceReference;

import java.util.List;

/**
 * Response body for POST /api/assistant/chat. {@code blocks} is the ordered
 * answer; {@code citations} are the KB article ids the answer is grounded in
 * (also emitted inline as {@code [ARTICLE-ID]} tokens within block text).
 */
public record ChatResponse(
        String conversationId,
        List<Block> blocks,
        List<String> citations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<EvidenceReference> citationReferences
) {
    /** Keeps source and JSON callers using the original three-field contract working. */
    public ChatResponse(String conversationId, List<Block> blocks, List<String> citations) {
        this(conversationId, blocks, citations, List.of());
    }
}
