package com.sentinel.kms.eval;

import java.util.List;

/** Explicit authorization boundary before an injected provider can be called. */
public record ProviderEvaluationRequest(boolean explicitOptIn, List<AnswerBaseline> baselines) {

    public ProviderEvaluationRequest {
        baselines = baselines == null ? List.of() : List.copyOf(baselines);
    }
}
