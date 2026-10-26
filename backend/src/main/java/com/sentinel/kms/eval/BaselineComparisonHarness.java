package com.sentinel.kms.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs supplied answer candidates over exactly the same fixture set. It is
 * offline by default; it never knows how to call a model or provider itself.
 */
public final class BaselineComparisonHarness {

    private final AnswerEvaluationService evaluator;

    public BaselineComparisonHarness(AnswerEvaluationService evaluator) {
        this.evaluator = evaluator == null ? new AnswerEvaluationService() : evaluator;
    }

    /** Requires all declared baselines for a meaningful offline comparison. */
    public BaselineComparisonReport runOffline(AnswerEvalDataset dataset,
                                               Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates) {
        return run(dataset, candidates, EvaluationExecutionMode.OFFLINE_DETERMINISTIC,
                EnumSet.allOf(AnswerBaseline.class));
    }

    BaselineComparisonReport run(AnswerEvalDataset dataset,
                                 Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates,
                                 EvaluationExecutionMode mode,
                                 Set<AnswerBaseline> requiredBaselines) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset is required");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates are required");
        }
        Set<AnswerBaseline> baselines = requiredBaselines == null || requiredBaselines.isEmpty()
                ? EnumSet.allOf(AnswerBaseline.class) : EnumSet.copyOf(requiredBaselines);
        List<BaselineResult> results = new ArrayList<>();
        for (AnswerBaseline baseline : baselines) {
            Map<String, AnswerCandidate> answers = candidates.get(baseline);
            if (answers == null) {
                throw new IllegalArgumentException("missing baseline candidates: " + baseline);
            }
            List<BaselineCaseResult> caseResults = new ArrayList<>();
            for (AnswerEvalCase evalCase : dataset.cases()) {
                AnswerCandidate candidate = answers.get(evalCase.id());
                if (candidate == null) {
                    throw new IllegalArgumentException("missing candidate for baseline " + baseline + " case " + evalCase.id());
                }
                AnswerEvaluationResult evaluation = evaluator.evaluate(evalCase, candidate);
                caseResults.add(new BaselineCaseResult(evalCase.id(), evaluation, candidate.metadata(),
                        CandidateFingerprint.sha256(baseline, evalCase.id(), candidate)));
            }
            double passRate = caseResults.stream().filter(result -> result.evaluation().deterministicPass()).count()
                    / (double) caseResults.size();
            results.add(new BaselineResult(baseline, passRate, caseResults));
        }
        ExpertAnnotationImportResult annotations = ExpertAnnotationImportResult.missing();
        ScientificCompleteness completeness = ScientificCompletenessGate.assess(dataset, results, annotations);
        return new BaselineComparisonReport("2.0", dataset.datasetId(), dataset.schemaVersion(), Instant.now(), mode,
                completeness, results, annotations, List.of(AnswerEvaluationService.HEURISTIC_CAVEAT,
                        "All compared baselines must use the same cases and captured evidence snapshots.",
                        "Fixture review metadata is not an imported expert annotation; no scientific claim is complete without a validated sidecar."));
    }

    /**
     * Attaches only a structurally validated external annotation import. The
     * completeness gate re-checks every report cell against the sidecar; this
     * method does not infer labels or turn a fixture review into expert data.
     */
    public BaselineComparisonReport withExpertAnnotations(AnswerEvalDataset dataset,
                                                           BaselineComparisonReport report,
                                                           ExpertAnnotationImportResult annotations) {
        if (dataset == null || report == null) {
            throw new IllegalArgumentException("dataset and report are required");
        }
        ExpertAnnotationImportResult effective = annotations == null
                ? ExpertAnnotationImportResult.missing() : annotations;
        ScientificCompleteness completeness = ScientificCompletenessGate.assess(dataset, report.baselines(), effective);
        List<String> limitations = new ArrayList<>(report.limitations());
        limitations.addAll(effective.limitations());
        return new BaselineComparisonReport(report.schemaVersion(), report.datasetId(), report.datasetSchemaVersion(),
                report.generatedAt(), report.executionMode(), completeness, report.baselines(), effective, limitations);
    }
}
