package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

import java.util.List;

/** A single versioned answer-level regression fixture. */
public record AnswerEvalCase(
        String schemaVersion,
        String id,
        AnswerCaseType caseType,
        String question,
        List<EvidenceReference> evidence,
        AnswerExpectation expectation,
        ExpertReview expertReview) {

    public AnswerEvalCase {
        if (schemaVersion == null || schemaVersion.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("schemaVersion and id are required");
        }
        schemaVersion = schemaVersion.trim();
        id = id.trim();
        caseType = caseType == null ? AnswerCaseType.ANSWERABLE : caseType;
        question = question == null ? "" : question;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        expectation = expectation == null
                ? new AnswerExpectation(Answerability.ANSWER, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                : expectation;
        expertReview = expertReview == null
                ? new ExpertReview(ExpertReviewStatus.PENDING, null, null, "No expert review recorded.")
                : expertReview;
    }
}
