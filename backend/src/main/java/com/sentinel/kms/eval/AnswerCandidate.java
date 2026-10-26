package com.sentinel.kms.eval;

import java.util.List;

/** Captured answer plus both provenance-rich and legacy citation representations. */
public record AnswerCandidate(
        String answer,
        List<CitationReference> citationReferences,
        List<String> legacyCitationIds,
        EvaluationMetadata metadata) {

    public AnswerCandidate {
        answer = answer == null ? "" : answer;
        citationReferences = citationReferences == null ? List.of() : List.copyOf(citationReferences);
        legacyCitationIds = legacyCitationIds == null ? List.of() : legacyCitationIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        metadata = metadata == null
                ? new EvaluationMetadata(EvaluationExecutionMode.OFFLINE_DETERMINISTIC, "unknown", "unknown", null, 0, CostMetadata.unknown())
                : metadata;
    }

    public boolean hasAnyCitation() {
        return !citationReferences.isEmpty() || !legacyCitationIds.isEmpty();
    }
}
