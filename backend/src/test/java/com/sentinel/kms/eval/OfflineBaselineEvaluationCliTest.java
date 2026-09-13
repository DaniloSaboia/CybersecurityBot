package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineBaselineEvaluationCliTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void consumesSavedOutputsWritesAComparisonReportAndFailsScientificGateWhileReviewIsPending(@TempDir Path temp) throws Exception {
        EvidenceReference evidence = evidence();
        AnswerEvalDataset dataset = new AnswerEvalDataset("1.0", "cli-fixture-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Which control?", List.of(evidence),
                new AnswerExpectation(Answerability.ANSWER, List.of("phishing-resistant MFA"), List.of("FIX-MFA"),
                        List.of(evidence), List.of(), List.of(), List.of(), List.of()), pendingReview())), pendingReview());
        Path datasetFile = temp.resolve("dataset.json");
        Path answersFile = temp.resolve("answers.json");
        Path reportFile = temp.resolve("report.json");
        mapper.writeValue(datasetFile.toFile(), dataset);
        mapper.writeValue(answersFile.toFile(), savedOutputs(evidence));

        OfflineBaselineEvaluationCli.main(new String[]{"--dataset", datasetFile.toString(), "--answers", answersFile.toString(),
                "--report", reportFile.toString()});

        BaselineComparisonReport report = mapper.readValue(reportFile.toFile(), BaselineComparisonReport.class);
        assertThat(report.baselines()).hasSize(3);
        assertThat(mapper.readTree(reportFile.toFile()).path("generatedAt").isTextual()).isTrue();
        assertThat(report.scientificCompleteness().complete()).isFalse();
        assertThat(report.scientificCompleteness().blockers())
                .anyMatch(blocker -> blocker.contains("No external expert-annotation artifact"));
        assertThatThrownBy(() -> ScientificCompletenessGate.requireComplete(report.scientificCompleteness()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Scientific-completeness gate failed");
        Path gatedReport = temp.resolve("gated-report.json");
        assertThatThrownBy(() -> OfflineBaselineEvaluationCli.main(new String[]{
                "--dataset", datasetFile.toString(), "--answers", answersFile.toString(),
                "--report", gatedReport.toString(), "--require-scientific-completeness"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Scientific-completeness gate failed");
        assertThat(Files.exists(gatedReport)).isFalse();
    }

    @Test
    void rejectsMissingSchemaAndUnexpectedSavedCaseBeforeRunningTheHarness(@TempDir Path temp) throws Exception {
        EvidenceReference evidence = evidence();
        AnswerEvalDataset dataset = new AnswerEvalDataset("1.0", "cli-fixture-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Which control?", List.of(evidence),
                new AnswerExpectation(Answerability.ANSWER, List.of("phishing-resistant MFA"), List.of("FIX-MFA"),
                        List.of(evidence), List.of(), List.of(), List.of(), List.of()), pendingReview())), pendingReview());
        Path datasetFile = temp.resolve("dataset.json");
        Path answersFile = temp.resolve("answers.json");
        mapper.writeValue(datasetFile.toFile(), dataset);
        ObjectNode malformed = savedOutputs(evidence);
        malformed.remove("schemaVersion");
        mapper.writeValue(answersFile.toFile(), malformed);

        assertThatThrownBy(() -> OfflineBaselineEvaluationCli.run(datasetFile, answersFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");

        ObjectNode unexpected = savedOutputs(evidence);
        ((ArrayNode) unexpected.path("baselines").path("RAG")).addObject()
                .put("caseId", "not-in-dataset")
                .put("answer", "synthetic");
        mapper.writeValue(answersFile.toFile(), unexpected);

        assertThatThrownBy(() -> OfflineBaselineEvaluationCli.run(datasetFile, answersFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected case");
    }

    @Test
    void importsAnExplicitlySyntheticBoundExpertSidecarBeforeAllowingTheScientificGate(@TempDir Path temp) throws Exception {
        EvidenceReference evidence = evidence();
        AnswerEvalDataset dataset = new AnswerEvalDataset("1.0", "cli-fixture-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Which control?", List.of(evidence),
                new AnswerExpectation(Answerability.ANSWER, List.of("phishing-resistant MFA"), List.of("FIX-MFA"),
                        List.of(evidence), List.of(), List.of(), List.of(), List.of()), pendingReview())), pendingReview());
        Path datasetFile = temp.resolve("dataset.json");
        Path answersFile = temp.resolve("answers.json");
        Path annotationsFile = temp.resolve("synthetic-annotations.json");
        Path reportFile = temp.resolve("report.json");
        mapper.writeValue(datasetFile.toFile(), dataset);
        mapper.writeValue(answersFile.toFile(), savedOutputs(evidence));
        BaselineComparisonReport preliminary = OfflineBaselineEvaluationCli.run(datasetFile, answersFile);
        mapper.writeValue(annotationsFile.toFile(), sidecar(datasetFile, answersFile, evidence, preliminary));

        OfflineBaselineEvaluationCli.main(new String[]{"--dataset", datasetFile.toString(), "--answers", answersFile.toString(),
                "--expert-annotations", annotationsFile.toString(), "--report", reportFile.toString(),
                "--require-scientific-completeness"});

        BaselineComparisonReport report = mapper.readValue(reportFile.toFile(), BaselineComparisonReport.class);
        assertThat(report.scientificCompleteness().complete()).isTrue();
        assertThat(report.expertAnnotationImport().status()).isEqualTo(ExpertAnnotationImportStatus.VALIDATED);
        assertThat(report.baselines()).allSatisfy(baseline -> assertThat(baseline.results())
                .allSatisfy(result -> assertThat(result.candidateSha256()).matches("[0-9a-f]{64}")));
    }

    private ObjectNode savedOutputs(EvidenceReference evidence) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("datasetId", "cli-fixture-v1");
        ObjectNode baselines = root.putObject("baselines");
        for (AnswerBaseline baseline : AnswerBaseline.values()) {
            ArrayNode outputs = baselines.putArray(baseline.name());
            AnswerCandidate candidate = new AnswerCandidate("Privileged accounts require phishing-resistant MFA.",
                    List.of(new CitationReference(evidence)), List.of(), metadata(baseline.name()));
            ObjectNode output = mapper.valueToTree(candidate);
            output.put("caseId", "case-1");
            outputs.add(output);
        }
        return root;
    }

    private static EvidenceReference evidence() {
        return new EvidenceReference("FIX-MFA", "fixture-policy-rev-1", 1, "fixture-policy-snapshot-1",
                0, 53, "fixture://policy", "fixture-v1", "approved");
    }

    private static ExpertReview pendingReview() {
        return new ExpertReview(ExpertReviewStatus.PENDING, null, null, "Pending external review.");
    }

    private static EvaluationMetadata metadata(String model) {
        return new EvaluationMetadata(EvaluationExecutionMode.OFFLINE_DETERMINISTIC, "fixture", model,
                Instant.parse("2026-09-05T00:00:00Z"), 1,
                new CostMetadata("USD", "0.000000", true, "fixture", 1L, 1L));
    }

    private ExpertAnnotationBundle sidecar(Path datasetFile, Path answersFile, EvidenceReference evidence,
                                            BaselineComparisonReport preliminary) throws Exception {
        List<ExpertAnnotationBundle.Annotation> annotations = new ArrayList<>();
        for (BaselineResult baseline : preliminary.baselines()) {
            String candidateSha = baseline.results().get(0).candidateSha256();
            annotations.add(annotation(baseline.baseline(), candidateSha, "synthetic-reviewer-a", evidence));
            annotations.add(annotation(baseline.baseline(), candidateSha, "synthetic-reviewer-b", evidence));
        }
        return new ExpertAnnotationBundle("1.0", "cli-fixture-v1", "1.0",
                CandidateFingerprint.sha256Bytes(Files.readAllBytes(datasetFile)),
                CandidateFingerprint.sha256Bytes(Files.readAllBytes(answersFile)),
                List.of(datasetReview("synthetic-dataset-reviewer-a"), datasetReview("synthetic-dataset-reviewer-b")),
                annotations, List.of());
    }

    private static ExpertAnnotationBundle.DatasetReview datasetReview(String id) {
        return new ExpertAnnotationBundle.DatasetReview(ExpertReviewStatus.ACCEPTED, id,
                "Synthetic test qualification", true, Instant.parse("2026-09-06T00:00:00Z"),
                "Synthetic dataset review.");
    }

    private static ExpertAnnotationBundle.Annotation annotation(AnswerBaseline baseline, String candidateSha,
                                                                 String reviewer, EvidenceReference evidence) {
        ExpertAnnotationBundle.MetricLabels labels = new ExpertAnnotationBundle.MetricLabels(
                ExpertVerdict.PASS, ExpertVerdict.PASS, ExpertVerdict.PASS, ExpertVerdict.NOT_APPLICABLE,
                ExpertVerdict.PASS, ExpertVerdict.PASS, ExpertVerdict.PASS);
        return new ExpertAnnotationBundle.Annotation("case-1", baseline, candidateSha, ExpertReviewStatus.ACCEPTED,
                reviewer, "Synthetic test qualification", true, Instant.parse("2026-09-06T00:00:00Z"),
                List.of(evidence), labels, "Synthetic answer review.");
    }
}
