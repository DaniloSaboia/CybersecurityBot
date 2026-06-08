package com.sentinel.kms.assistant;

import java.util.List;

/**
 * Response body for POST /api/assistant/chat. {@code blocks} is the ordered
 * answer; {@code citations} are the KB article ids the answer is grounded in
 * (also emitted inline as {@code [ARTICLE-ID]} tokens within block text).
 */
public record ChatResponse(
        String conversationId,
        List<Block> blocks,
        List<String> citations
) {
}
