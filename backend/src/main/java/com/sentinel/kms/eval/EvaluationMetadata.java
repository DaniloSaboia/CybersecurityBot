package com.sentinel.kms.eval;

import java.time.Instant;

/** Reproducibility metadata for a single answer candidate. */
public record EvaluationMetadata(
        EvaluationExecutionMode executionMode,
        String provider,
        String model,
        Instant startedAt,
        long latencyMs,
        CostMetadata cost) {

    public EvaluationMetadata {
        executionMode = executionMode == null ? EvaluationExecutionMode.OFFLINE_DETERMINISTIC : executionMode;
        provider = provider == null || provider.isBlank() ? "unknown" : provider.trim();
        model = model == null || model.isBlank() ? "unknown" : model.trim();
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be non-negative");
        }
        cost = cost == null ? CostMetadata.unknown() : cost;
    }
}
