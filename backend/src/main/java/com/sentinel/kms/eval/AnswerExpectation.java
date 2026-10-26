package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

import java.util.List;

/**
 * Fixture assertions used by the deterministic harness. They are deliberately
 * not expert labels and phrase matching against them is not a truth test.
 */
public record AnswerExpectation(
        Answerability answerability,
        List<String> requiredPhrases,
        List<String> requiredCitationIds,
        List<EvidenceReference> requiredEvidence,
        List<String> allowedAbstentionPhrases,
        List<String> requiredTemporalMarkers,
        List<String> forbiddenPhrases,
        List<String> requiredSafeResponsePhrases,
        List<String> forbiddenCitationIds) {

    public AnswerExpectation {
        answerability = answerability == null ? Answerability.ANSWER : answerability;
        requiredPhrases = immutable(requiredPhrases);
        requiredCitationIds = immutable(requiredCitationIds);
        requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
        allowedAbstentionPhrases = immutable(allowedAbstentionPhrases);
        requiredTemporalMarkers = immutable(requiredTemporalMarkers);
        forbiddenPhrases = immutable(forbiddenPhrases);
        requiredSafeResponsePhrases = immutable(requiredSafeResponsePhrases);
        forbiddenCitationIds = immutable(forbiddenCitationIds);
    }

    /** Backward-compatible fixture constructor before authorization assertions existed. */
    public AnswerExpectation(Answerability answerability,
                             List<String> requiredPhrases,
                             List<String> requiredCitationIds,
                             List<EvidenceReference> requiredEvidence,
                             List<String> allowedAbstentionPhrases,
                             List<String> requiredTemporalMarkers,
                             List<String> forbiddenPhrases,
                             List<String> requiredSafeResponsePhrases) {
        this(answerability, requiredPhrases, requiredCitationIds, requiredEvidence, allowedAbstentionPhrases,
                requiredTemporalMarkers, forbiddenPhrases, requiredSafeResponsePhrases, List.of());
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
}
