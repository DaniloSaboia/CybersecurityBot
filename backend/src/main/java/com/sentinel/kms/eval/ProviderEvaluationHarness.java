package com.sentinel.kms.eval;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter for real-provider evaluations. It intentionally performs no provider
 * discovery, credential loading, or calls unless a caller supplies both a
 * source and an explicit opt-in request.
 */
public final class ProviderEvaluationHarness {

    private final BaselineComparisonHarness comparisonHarness;
    private final ProviderAnswerSource source;

    public ProviderEvaluationHarness(BaselineComparisonHarness comparisonHarness, ProviderAnswerSource source) {
        this.comparisonHarness = comparisonHarness == null
                ? new BaselineComparisonHarness(new AnswerEvaluationService()) : comparisonHarness;
        this.source = source;
    }

    public Optional<BaselineComparisonReport> run(AnswerEvalDataset dataset, ProviderEvaluationRequest request) {
        return runCaptured(dataset, request).map(ProviderEvaluationRun::report);
    }

    /**
     * Executes only after explicit opt-in and returns a caller-owned candidate
     * capture for optional restricted persistence. It does not write files.
     */
    public Optional<ProviderEvaluationRun> runCaptured(AnswerEvalDataset dataset, ProviderEvaluationRequest request) {
        if (request == null || !request.explicitOptIn()) {
            return Optional.empty();
        }
        if (source == null) {
            throw new IllegalStateException("explicit opt-in was supplied but no ProviderAnswerSource is configured");
        }
        Set<AnswerBaseline> baselines = request.baselines().isEmpty()
                ? EnumSet.allOf(AnswerBaseline.class) : EnumSet.copyOf(request.baselines());
        Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates = new LinkedHashMap<>();
        for (AnswerBaseline baseline : baselines) {
            Map<String, AnswerCandidate> byCase = new LinkedHashMap<>();
            for (AnswerEvalCase evalCase : dataset.cases()) {
                AnswerCandidate candidate = source.answer(evalCase, baseline);
                if (candidate == null) {
                    throw new IllegalStateException("provider returned no candidate for " + baseline + " / " + evalCase.id());
                }
                byCase.put(evalCase.id(), candidate);
            }
            candidates.put(baseline, byCase);
        }
        BaselineComparisonReport report = comparisonHarness.run(dataset, candidates,
                EvaluationExecutionMode.PROVIDER_OPT_IN, baselines);
        return Optional.of(new ProviderEvaluationRun(report, candidates));
    }
}
