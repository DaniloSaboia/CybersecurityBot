package com.sentinel.kms.eval;

/** One baseline output evaluated against one fixture. */
public record BaselineCaseResult(
        String caseId,
        AnswerEvaluationResult evaluation,
        EvaluationMetadata candidateMetadata,
        String candidateSha256) {

    /** Backward-compatible constructor for reports created before output binding. */
    public BaselineCaseResult(String caseId,
                              AnswerEvaluationResult evaluation,
                              EvaluationMetadata candidateMetadata) {
        this(caseId, evaluation, candidateMetadata, null);
    }
}
