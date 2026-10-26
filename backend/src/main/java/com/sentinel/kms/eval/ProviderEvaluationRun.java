package com.sentinel.kms.eval;

import java.util.EnumMap;
import java.util.Map;

/**
 * An opt-in provider run plus caller-owned captured candidates. The public
 * comparison report deliberately contains digests and metadata, not answers;
 * callers may persist this capture only under their approved data-handling
 * process when later expert review needs to bind to it.
 */
public record ProviderEvaluationRun(
        BaselineComparisonReport report,
        Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates) {

    public ProviderEvaluationRun {
        if (report == null) {
            throw new IllegalArgumentException("report is required");
        }
        EnumMap<AnswerBaseline, Map<String, AnswerCandidate>> copied = new EnumMap<>(AnswerBaseline.class);
        if (candidates != null) {
            candidates.forEach((baseline, answers) -> {
                if (baseline != null && answers != null) {
                    copied.put(baseline, Map.copyOf(answers));
                }
            });
        }
        candidates = Map.copyOf(copied);
    }
}
