package com.sentinel.kms.eval;

import java.util.List;

/** Per-case report section compatible with aggregate baseline reports. */
public record AnswerEvaluationResult(
        String caseId,
        AnswerCaseType caseType,
        EvaluationMetadata candidateMetadata,
        MetricAssessment citationSupportHeuristic,
        MetricAssessment faithfulnessHeuristic,
        MetricAssessment correctnessHeuristic,
        MetricAssessment abstentionHeuristic,
        MetricAssessment temporalHeuristic,
        MetricAssessment adversarialHeuristic,
        MetricAssessment authorizationLeakageHeuristic,
        ExpertReview expertReview,
        boolean deterministicPass,
        List<String> limitations) {

    public AnswerEvaluationResult {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** Backward-compatible report constructor before authorization fixtures existed. */
    public AnswerEvaluationResult(String caseId,
                                  AnswerCaseType caseType,
                                  EvaluationMetadata candidateMetadata,
                                  MetricAssessment citationSupportHeuristic,
                                  MetricAssessment faithfulnessHeuristic,
                                  MetricAssessment correctnessHeuristic,
                                  MetricAssessment abstentionHeuristic,
                                  MetricAssessment temporalHeuristic,
                                  MetricAssessment adversarialHeuristic,
                                  ExpertReview expertReview,
                                  boolean deterministicPass,
                                  List<String> limitations) {
        this(caseId, caseType, candidateMetadata, citationSupportHeuristic, faithfulnessHeuristic,
                correctnessHeuristic, abstentionHeuristic, temporalHeuristic, adversarialHeuristic,
                new MetricAssessment("authorization_leakage_heuristic", MetricStatus.NOT_APPLICABLE, 0.0,
                        EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC, true,
                        AnswerEvaluationService.HEURISTIC_CAVEAT,
                        List.of("Authorization was not asserted by this legacy fixture.")),
                expertReview, deterministicPass, limitations);
    }
}
