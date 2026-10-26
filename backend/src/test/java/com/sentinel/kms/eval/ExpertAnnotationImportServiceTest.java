package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic-only data proves importer validation mechanics, never expert findings. */
class ExpertAnnotationImportServiceTest {

    private final ExpertAnnotationImportService importer = new ExpertAnnotationImportService();

    @Test
    void bindsTwoIndependentQualifiedSyntheticReviewsToExactDatasetAnswersCandidateAndEvidence() {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        String datasetSha = "a".repeat(64);
        String answersSha = "b".repeat(64);
        ExpertAnnotationBundle bundle = bundle(datasetSha, answersSha, List.of(
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.PASS)),
                annotation("synthetic-reviewer-b", candidateSha, labels(ExpertVerdict.PASS))), List.of());

        ExpertAnnotationImportResult imported = importer.validate(bundle, dataset, answers, datasetSha, answersSha);

        assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.VALIDATED);
        assertThat(imported.bundle()).isEqualTo(bundle);
        assertThat(imported.sourceSha256()).matches("[0-9a-f]{64}");
        assertThat(imported.limitations()).anyMatch(limitation -> limitation.contains("identity"));
        assertThat(imported.issues()).isEmpty();
    }

    @Test
    void rejectsAnnotationBoundToWrongSavedCandidateOrWrongImmutableEvidence() {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        EvidenceReference wrongEvidence = new EvidenceReference("synthetic-other", "other-revision", 1,
                "other-snapshot", 0, 1, "fixture://other", "v1", "approved");
        ExpertAnnotationBundle.Annotation badDigest = annotation("synthetic-reviewer-a", "0".repeat(64), labels(ExpertVerdict.PASS));
        ExpertAnnotationBundle.Annotation badEvidence = new ExpertAnnotationBundle.Annotation(
                "case-1", AnswerBaseline.RAG, candidateSha, ExpertReviewStatus.ACCEPTED,
                "synthetic-reviewer-b", "Synthetic test qualification", true,
                Instant.parse("2026-09-06T00:00:00Z"), List.of(wrongEvidence), labels(ExpertVerdict.PASS),
                "Synthetic test rationale.");

        ExpertAnnotationImportResult imported = importer.validate(bundle("a".repeat(64), "b".repeat(64),
                        List.of(badDigest, badEvidence), List.of()), dataset, answers, "a".repeat(64), "b".repeat(64));

        assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("candidate digest"));
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("immutable evidence"));
    }

    @Test
    void rejectsDuplicateReviewerAndAdjudicatorWhoAlsoReviewedTheCandidate() {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        ExpertAnnotationBundle.Adjudication conflicted = new ExpertAnnotationBundle.Adjudication(
                "case-1", AnswerBaseline.RAG, candidateSha, ExpertReviewStatus.ACCEPTED,
                "synthetic-reviewer-a", "Synthetic test adjudicator qualification",
                true, Instant.parse("2026-09-06T00:01:00Z"), List.of(evidence()), labels(ExpertVerdict.PASS),
                "Synthetic test adjudication.");

        ExpertAnnotationImportResult imported = importer.validate(bundle("a".repeat(64), "b".repeat(64), List.of(
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.UNCERTAIN)),
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.FAIL))), List.of(conflicted)),
                dataset, answers, "a".repeat(64), "b".repeat(64));

        assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("Duplicate reviewer"));
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("must not also be a reviewer"));
    }

    @Test
    void importsJsonWithItsRawSourceDigestAndFailsClosedForMalformedOrMissingFiles() throws Exception {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        ExpertAnnotationBundle valid = bundle("a".repeat(64), "b".repeat(64), List.of(
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.PASS)),
                annotation("synthetic-reviewer-b", candidateSha, labels(ExpertVerdict.PASS))), List.of());
        Path file = Files.createTempFile("synthetic-expert-annotations", ".json");
        try {
            Files.writeString(file, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(valid));

            ExpertAnnotationImportResult imported = importer.importFile(file, dataset, answers,
                    "a".repeat(64), "b".repeat(64));
            ExpertAnnotationImportResult wrongCapturedDataset = importer.importFile(file, dataset, answers,
                    "c".repeat(64), "b".repeat(64));
            ExpertAnnotationImportResult malformed = importer.importFile(writeMalformed(), dataset, answers);
            ExpertAnnotationImportResult missing = importer.importFile(null, dataset, answers);

            assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.VALIDATED);
            assertThat(imported.sourceSha256()).isEqualTo(CandidateFingerprint.sha256Bytes(Files.readAllBytes(file)));
            assertThat(wrongCapturedDataset.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
            assertThat(wrongCapturedDataset.issues()).anyMatch(issue -> issue.contains("dataset SHA-256"));
            assertThat(malformed.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
            assertThat(missing.status()).isEqualTo(ExpertAnnotationImportStatus.MISSING);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void threeArgumentImportUsesDocumentedCanonicalModelHashesForFixtureOnlyUse() throws Exception {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        ExpertAnnotationBundle canonical = bundle(ExpertAnnotationImportService.datasetSha256(dataset),
                ExpertAnnotationImportService.answersSha256(answers), List.of(
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.PASS)),
                annotation("synthetic-reviewer-b", candidateSha, labels(ExpertVerdict.PASS))), List.of());
        Path file = Files.createTempFile("synthetic-expert-annotations-canonical", ".json");
        try {
            Files.writeString(file, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(canonical));

            ExpertAnnotationImportResult imported = importer.importFile(file, dataset, answers);

            assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.VALIDATED);
            assertThat(imported.limitations()).anyMatch(limitation -> limitation.contains("not a source-file-byte digest"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsPendingDatasetReviewsRatherThanTreatingTheSidecarAsExpertEvidence() {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        ExpertAnnotationBundle.DatasetReview pending = new ExpertAnnotationBundle.DatasetReview(
                ExpertReviewStatus.PENDING, "synthetic-pending-reviewer", "Synthetic test qualification", true,
                Instant.parse("2026-09-06T00:00:00Z"), "Synthetic pending review.");
        ExpertAnnotationBundle bundle = new ExpertAnnotationBundle("1.0", "synthetic-expert-import-v1", "1.0",
                "a".repeat(64), "b".repeat(64), List.of(pending), List.of(
                annotation("synthetic-reviewer-a", candidateSha, labels(ExpertVerdict.PASS))), List.of());

        ExpertAnnotationImportResult imported = importer.validate(bundle, dataset, answers,
                "a".repeat(64), "b".repeat(64));

        assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("Dataset review 0") && issue.contains("PENDING"));
    }

    @Test
    void rejectsPendingCandidateReviewsRatherThanSilentlyPromotingThemToExpertLabels() {
        AnswerEvalDataset dataset = dataset();
        Map<AnswerBaseline, Map<String, AnswerCandidate>> answers = answers();
        String candidateSha = CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", answers.get(AnswerBaseline.RAG).get("case-1"));
        ExpertAnnotationBundle.Annotation pending = new ExpertAnnotationBundle.Annotation(
                "case-1", AnswerBaseline.RAG, candidateSha, ExpertReviewStatus.PENDING,
                "synthetic-pending-reviewer", "Synthetic test qualification", true,
                Instant.parse("2026-09-06T00:00:00Z"), List.of(evidence()), labels(ExpertVerdict.PASS),
                "Synthetic pending review.");

        ExpertAnnotationImportResult imported = importer.validate(bundle("a".repeat(64), "b".repeat(64),
                        List.of(pending), List.of()), dataset, answers, "a".repeat(64), "b".repeat(64));

        assertThat(imported.status()).isEqualTo(ExpertAnnotationImportStatus.INVALID);
        assertThat(imported.issues()).anyMatch(issue -> issue.contains("Annotation 0") && issue.contains("PENDING"));
    }

    private static Path writeMalformed() throws Exception {
        Path malformed = Files.createTempFile("synthetic-expert-annotations-malformed", ".json");
        Files.writeString(malformed, "{not-json");
        malformed.toFile().deleteOnExit();
        return malformed;
    }

    private static ExpertAnnotationBundle bundle(String datasetSha, String answersSha,
                                                  List<ExpertAnnotationBundle.Annotation> annotations,
                                                  List<ExpertAnnotationBundle.Adjudication> adjudications) {
        return new ExpertAnnotationBundle("1.0", "synthetic-expert-import-v1", "1.0", datasetSha, answersSha,
                List.of(datasetReview("synthetic-dataset-reviewer-a"), datasetReview("synthetic-dataset-reviewer-b")),
                annotations, adjudications);
    }

    private static ExpertAnnotationBundle.DatasetReview datasetReview(String reviewer) {
        return new ExpertAnnotationBundle.DatasetReview(ExpertReviewStatus.ACCEPTED, reviewer,
                "Synthetic test qualification", true, Instant.parse("2026-09-06T00:00:00Z"),
                "Synthetic dataset review.");
    }

    private static ExpertAnnotationBundle.Annotation annotation(String reviewer, String candidateSha,
                                                                 ExpertAnnotationBundle.MetricLabels labels) {
        return new ExpertAnnotationBundle.Annotation("case-1", AnswerBaseline.RAG, candidateSha,
                ExpertReviewStatus.ACCEPTED, reviewer, "Synthetic test qualification",
                true, Instant.parse("2026-09-06T00:00:00Z"), List.of(evidence()), labels, "Synthetic test rationale.");
    }

    private static ExpertAnnotationBundle.MetricLabels labels(ExpertVerdict correctness) {
        return new ExpertAnnotationBundle.MetricLabels(ExpertVerdict.PASS, ExpertVerdict.PASS, correctness,
                ExpertVerdict.NOT_APPLICABLE, ExpertVerdict.PASS, ExpertVerdict.PASS, ExpertVerdict.PASS);
    }

    private static AnswerEvalDataset dataset() {
        EvidenceReference evidence = evidence();
        return new AnswerEvalDataset("1.0", "synthetic-expert-import-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Synthetic question", List.of(evidence),
                new AnswerExpectation(Answerability.ANSWER, List.of("synthetic answer"), List.of("FIX-SYN"),
                        List.of(evidence), List.of(), List.of(), List.of(), List.of()), pending())), pending());
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
