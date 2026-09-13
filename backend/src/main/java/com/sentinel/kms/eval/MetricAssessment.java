package com.sentinel.kms.eval;

import java.util.List;

/**
 * A transparent check result. For deterministic methods, {@code notTruthProof}
 * must remain true: matching a fixture never establishes factual truth.
 */
public record MetricAssessment(
        String metric,
        MetricStatus status,
        double score,
        EvaluationMethod method,
        boolean notTruthProof,
        String caveat,
        List<String> observations) {

    public MetricAssessment {
        metric = metric == null ? "unknown" : metric;
        status = status == null ? MetricStatus.NOT_APPLICABLE : status;
        method = method == null ? EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC : method;
        caveat = caveat == null ? "" : caveat;
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
