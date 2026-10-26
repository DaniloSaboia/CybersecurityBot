package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic records exercise gate mechanics; they are not expert findings. */
class ScientificCompletenessGateTest {

    private final AnswerEvaluationService evaluator = new AnswerEvaluationService();
    private final ExpertAnnotationImportService importer = new ExpertAnnotationImportService();

    @Test
    void failsClosedWithoutAnImportedArtifactEvenWhenFixtureMetadataSaysAccepted() {
        AnswerEvalDataset dataset = dataset(new ExpertReview(ExpertReviewStatus.ACCEPTED, "fixture-reviewer",
                "2026-09-06T00:00:00Z", "Synthetic fixture metadata only."));
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        BaselineComparisonReport preliminary = run(dataset, answers);

        assertThat(preliminary.scientificCompleteness().complete()).isFalse();
        assertThat(preliminary.scientificCompleteness().blockers())
                .anyMatch(blocker -> blocker.contains("No external expert-annotation artifact"));
    }

    @Test
    void requiresTwoIndependentReviewsAndASeparateAdjudicatorForDisagreement() {
        AnswerEvalDataset dataset = dataset(pending());
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        BaselineComparisonReport preliminary = run(dataset, answers);
        String candidateSha = preliminary.baselines().get(0).results().get(0).candidateSha256();

        ExpertAnnotationBundle oneReview = bundle(candidateSha, List.of(annotation("reviewer-a", candidateSha,
                labels(ExpertVerdict.PASS))), List.of());
        ExpertAnnotationImportResult oneImported = importer.validate(oneReview, dataset, answers, DIGEST_A, DIGEST_B);
        BaselineComparisonReport oneAttached = new BaselineComparisonHarness(evaluator)
                .withExpertAnnotations(dataset, preliminary, oneImported);
        assertThat(oneAttached.scientificCompleteness().complete()).isFalse();
        assertThat(oneAttached.scientificCompleteness().blockers())
                .anyMatch(blocker -> blocker.contains("two independent"));

        ExpertAnnotationBundle disagreement = bundle(candidateSha, List.of(
                annotation("reviewer-a", candidateSha, labels(ExpertVerdict.PASS)),
                annotation("reviewer-b", candidateSha, labels(ExpertVerdict.FAIL))), List.of());
        ExpertAnnotationImportResult disagreementImported = importer.validate(disagreement, dataset, answers, DIGEST_A, DIGEST_B);
        BaselineComparisonReport disagreementAttached = new BaselineComparisonHarness(evaluator)
                .withExpertAnnotations(dataset, preliminary, disagreementImported);
        assertThat(disagreementAttached.scientificCompleteness().complete()).isFalse();
        assertThat(disagreementAttached.scientificCompleteness().blockers())
                .anyMatch(blocker -> blocker.contains("adjudication"));

        ExpertAnnotationBundle adjudicated = bundle(candidateSha, List.of(
                annotation("reviewer-a", candidateSha, labels(ExpertVerdict.PASS)),
                annotation("reviewer-b", candidateSha, labels(ExpertVerdict.FAIL))),
                List.of(adjudication(candidateSha, labels(ExpertVerdict.PASS))));
        ExpertAnnotationImportResult adjudicatedImported = importer.validate(adjudicated, dataset, answers, DIGEST_A, DIGEST_B);
        BaselineComparisonReport complete = new BaselineComparisonHarness(evaluator)
                .withExpertAnnotations(dataset, preliminary, adjudicatedImported);

        assertThat(adjudicatedImported.validated()).isTrue();
        assertThat(complete.scientificCompleteness().complete()).isTrue();
        assertThat(complete.expertAnnotationImport().limitations())
                .anyMatch(limitation -> limitation.contains("external verification"));
    }

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    private BaselineComparisonReport run(AnswerEvalDataset dataset,
                                         Map<AnswerBaseline, Map<String, AnswerCandidate>> answers) {
        return new BaselineComparisonHarness(evaluator).run(dataset, answers,
                EvaluationExecutionMode.OFFLINE_DETERMINISTIC, EnumSet.of(AnswerBaseline.RAG));
    }

    private static ExpertAnnotationBundle bundle(String candidateSha,
                                                  List<ExpertAnnotationBundle.Annotation> annotations,
                                                  List<ExpertAnnotationBundle.Adjudication> adjudications) {
        return new ExpertAnnotationBundle("1.0", "synthetic-gate-v1", "1.0", DIGEST_A, DIGEST_B,
                List.of(datasetReview("dataset-reviewer-a"), datasetReview("dataset-reviewer-b")),
                annotations, adjudications);
    }

    private static ExpertAnnotationBundle.DatasetReview datasetReview(String id) {
        return new ExpertAnnotationBundle.DatasetReview(ExpertReviewStatus.ACCEPTED, id,
                "Synthetic test qualification", true, Instant.parse("2026-09-06T00:00:00Z"),
                "Synthetic dataset/rubric review.");
    }

    private static ExpertAnnotationBundle.Annotation annotation(String reviewer, String candidateSha,
                                                                  ExpertAnnotationBundle.MetricLabels labels) {
        return new ExpertAnnotationBundle.Annotation("case-1", AnswerBaseline.RAG, candidateSha,
                ExpertReviewStatus.ACCEPTED, reviewer, "Synthetic test qualification", true,
                Instant.parse("2026-09-06T00:00:00Z"), List.of(evidence()), labels,
                "Synthetic candidate review.");
    }

    private static ExpertAnnotationBundle.Adjudication adjudication(String candidateSha,
                                                                       ExpertAnnotationBundle.MetricLabels labels) {
        return new ExpertAnnotationBundle.Adjudication("case-1", AnswerBaseline.RAG, candidateSha,
                ExpertReviewStatus.ACCEPTED, "adjudicator-c", "Synthetic test qualification", true,
                Instant.parse("2026-09-06T00:01:00Z"), List.of(evidence()), labels,
                "Synthetic adjudication.");
    }

    private static ExpertAnnotationBundle.MetricLabels labels(ExpertVerdict correctness) {
        return new ExpertAnnotationBundle.MetricLabels(ExpertVerdict.PASS, ExpertVerdict.PASS, correctness,
                ExpertVerdict.NOT_APPLICABLE, ExpertVerdict.PASS, ExpertVerdict.PASS, ExpertVerdict.PASS);
    }

    private static AnswerEvalDataset dataset(ExpertReview review) {
        return new AnswerEvalDataset("1.0", "synthetic-gate-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Synthetic question", List.of(evidence()),
                new AnswerExpectation(Answerability.ANSWER, List.of("synthetic answer"), List.of("FIX-SYN"),
                        List.of(evidence()), List.of(), List.of(), List.of(), List.of()), review)), review);
    }

    private static Map<AnswerBaseline, Map<String, AnswerCandidate>> answers() {
        AnswerCandidate candidate = new AnswerCandidate("Synthetic answer", List.of(new CitationReference(evidence())),
                List.of(), new EvaluationMetadata(EvaluationExecutionMode.OFFLINE_DETERMINISTIC, "fixture", "synthetic",
                Instant.EPOCH, 0, CostMetadata.unknown()));
        return Map.of(AnswerBaseline.RAG, Map.of("case-1", candidate));
    }

    private static EvidenceReference evidence() {
        return new EvidenceReference("FIX-SYN", "fixture-synthetic-rev-1", 1, "fixture-synthetic-snapshot-1",
                0, 16, "fixture://synthetic", "fixture-v1", "approved");
    }

    private static ExpertReview pending() {
        return new ExpertReview(ExpertReviewStatus.PENDING, null, null, "Synthetic fixture remains pending externally.");
    }
}
