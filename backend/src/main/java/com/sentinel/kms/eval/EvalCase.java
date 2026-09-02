package com.sentinel.kms.eval;

import java.util.List;

/**
 * One golden-set case: a question, the article ids a correct retrieval must
 * surface, and the optional scope the question implies (category / minimum
 * severity). Mirrors what the Assistant sends in a {@code ChatRequest}.
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedArticleIds,
        String category,
        String minSeverity) {
}
