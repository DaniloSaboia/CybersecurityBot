package com.sentinel.kms.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinel.kms.common.EvidenceReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Imports a versioned external expert-review sidecar without turning review
 * labels into inferred facts. Validation is intentionally fail closed: only a
 * file bound to the exact fixture, saved answer artifact, candidate, and full
 * immutable evidence tuple is accepted structurally.
 */
public final class ExpertAnnotationImportService {

    public static final String SCHEMA_VERSION = "1.0";
    private static final long MAX_IMPORT_BYTES = 2L * 1024L * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Reads an annotation file using canonical in-memory model hashes. This is
     * useful for fixture-only tests, but it does not bind to the original
     * dataset/output file bytes; production callers should use the overload
     * that supplies their captured artifact hashes explicitly.
     */
    public ExpertAnnotationImportResult importFile(Path path, AnswerEvalDataset dataset,
                                                    Map<AnswerBaseline, Map<String, AnswerCandidate>> answers) {
        if (path == null || !Files.isRegularFile(path)) {
            return ExpertAnnotationImportResult.missing();
        }
        try {
            return importFile(path, dataset, answers, datasetSha256(dataset), answersSha256(answers));
        } catch (RuntimeException ignored) {
            return ExpertAnnotationImportResult.invalid(null, null, List.of(
                    "Supplied dataset or saved answers could not be canonically fingerprinted."));
        }
    }

    /**
     * Reads an annotation file and validates it against caller-supplied SHA-256
     * digests for the exact dataset and saved-answer artifacts. No provider or
     * network call is made.
     */
    public ExpertAnnotationImportResult importFile(Path path, AnswerEvalDataset dataset,
                                                    Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                                    String datasetSha256, String answersSha256) {
        if (path == null || !Files.isRegularFile(path)) {
            return ExpertAnnotationImportResult.missing();
        }
        byte[] raw;
        try {
            if (Files.size(path) > MAX_IMPORT_BYTES) {
                return ExpertAnnotationImportResult.invalid(null, null,
                        List.of("Expert-annotation artifact exceeds the supported size limit."));
            }
            raw = Files.readAllBytes(path);
        } catch (IOException ignored) {
            return ExpertAnnotationImportResult.invalid(null, null,
                    List.of("Expert-annotation artifact could not be read."));
        }
        if (raw.length > MAX_IMPORT_BYTES) {
            return ExpertAnnotationImportResult.invalid(null, null,
                    List.of("Expert-annotation artifact exceeds the supported size limit."));
        }
        String sourceSha256 = CandidateFingerprint.sha256Bytes(raw);
        ExpertAnnotationBundle bundle;
        try {
            bundle = JSON.readValue(raw, ExpertAnnotationBundle.class);
        } catch (IOException ignored) {
            return ExpertAnnotationImportResult.invalid(sourceSha256, null,
                    List.of("Expert-annotation artifact is malformed or uses an unsupported field."));
        }
        try {
            return validateInternal(bundle, dataset, answers, datasetSha256, answersSha256, sourceSha256);
        } catch (RuntimeException ignored) {
            return ExpertAnnotationImportResult.invalid(sourceSha256, bundle, List.of(
                    "Supplied dataset, saved answers, or expected artifact hash could not be validated."));
        }
    }

    /**
     * Validates an already parsed bundle against caller-supplied SHA-256 values.
     * Callers ordinarily use {@link #importFile(Path, AnswerEvalDataset, Map)};
     * this overload supports controlled offline imports whose input artifacts
     * have already been fingerprinted.
     */
    public ExpertAnnotationImportResult validate(ExpertAnnotationBundle bundle, AnswerEvalDataset dataset,
                                                 Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                                 String datasetSha256, String answersSha256) {
        try {
            String sourceSha256 = bundle == null ? null : canonicalBundleSha256(bundle);
            return validateInternal(bundle, dataset, answers, datasetSha256, answersSha256, sourceSha256);
        } catch (RuntimeException ignored) {
            return ExpertAnnotationImportResult.invalid(null, bundle,
                    List.of("Expert-annotation bundle could not be canonically validated."));
        }
    }

    /** Canonical SHA-256 for the in-memory versioned fixture supplied to an import. */
    public static String datasetSha256(AnswerEvalDataset dataset) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset is required");
        }
        return canonicalJsonSha256(dataset);
    }

    /**
     * Canonical SHA-256 for all saved baseline outputs. Candidate maps and case
     * identifiers are sorted, while each candidate’s own list ordering remains
     * part of its {@link CandidateFingerprint}.
     */
    public static String answersSha256(Map<AnswerBaseline, Map<String, AnswerCandidate>> answers) {
        if (answers == null) {
            throw new IllegalArgumentException("saved answers are required");
        }
        StringBuilder canonical = new StringBuilder("sentinel-evaluation-answers-v1");
        for (AnswerBaseline baseline : AnswerBaseline.values()) {
            Map<String, AnswerCandidate> candidates = answers.get(baseline);
            append(canonical, "baseline", baseline.name());
            if (candidates == null) {
                append(canonical, "present", false);
                continue;
            }
            append(canonical, "present", true);
            List<String> caseIds = new ArrayList<>(candidates.keySet());
            caseIds.sort(Comparator.nullsFirst(String::compareTo));
            append(canonical, "caseCount", caseIds.size());
            for (String caseId : caseIds) {
                append(canonical, "caseId", caseId);
                AnswerCandidate candidate = candidates.get(caseId);
                append(canonical, "candidateSha256", candidate == null ? null : CandidateFingerprint.sha256(baseline, caseId, candidate));
            }
        }
        return CandidateFingerprint.sha256Bytes(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ExpertAnnotationImportResult validateInternal(ExpertAnnotationBundle bundle, AnswerEvalDataset dataset,
                                                           Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                                           String expectedDatasetSha256, String expectedAnswersSha256,
                                                           String sourceSha256) {
        if (bundle == null) {
            return ExpertAnnotationImportResult.invalid(sourceSha256, null,
                    List.of("Expert-annotation bundle is required."));
        }
        List<String> issues = new ArrayList<>();
        String datasetHash = normalizeHash(expectedDatasetSha256, "Expected dataset SHA-256", issues);
        String answersHash = normalizeHash(expectedAnswersSha256, "Expected answers SHA-256", issues);
        String bundleDatasetHash = normalizeHash(bundle.datasetSha256(), "Bundle dataset SHA-256", issues);
        String bundleAnswersHash = normalizeHash(bundle.answersSha256(), "Bundle answers SHA-256", issues);

        if (!SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            issues.add("Unsupported expert-annotation schema version.");
        }
        if (dataset == null) {
            issues.add("Dataset is required to validate expert annotations.");
        } else {
            if (!same(bundle.datasetId(), dataset.datasetId())) {
                issues.add("Annotation dataset identifier does not match the supplied dataset.");
            }
            if (!same(bundle.datasetSchemaVersion(), dataset.schemaVersion())) {
                issues.add("Annotation dataset schema version does not match the supplied dataset.");
            }
        }
        if (!hashEquals(bundleDatasetHash, datasetHash)) {
            issues.add("Annotation dataset SHA-256 does not match the saved dataset artifact.");
        }
        if (!hashEquals(bundleAnswersHash, answersHash)) {
            issues.add("Annotation answers SHA-256 does not match the saved answers artifact.");
        }

        validateDatasetReviews(bundle.datasetReviews(), issues);
        Map<Target, Set<String>> reviewerIds = new HashMap<>();
        Map<Target, Set<String>> adjudicatorIds = new HashMap<>();
        for (int index = 0; index < bundle.annotations().size(); index++) {
            ExpertAnnotationBundle.Annotation annotation = bundle.annotations().get(index);
            validateAnnotation(annotation, index, dataset, answers, reviewerIds, issues);
        }
        for (int index = 0; index < bundle.adjudications().size(); index++) {
            ExpertAnnotationBundle.Adjudication adjudication = bundle.adjudications().get(index);
            validateAdjudication(adjudication, index, dataset, answers, reviewerIds, adjudicatorIds, issues);
        }
        return issues.isEmpty()
                ? ExpertAnnotationImportResult.validated(sourceSha256, bundle)
                : ExpertAnnotationImportResult.invalid(sourceSha256, bundle, issues);
    }

    private static void validateDatasetReviews(List<ExpertAnnotationBundle.DatasetReview> reviews, List<String> issues) {
        if (reviews == null || reviews.isEmpty()) {
            issues.add("At least one completed dataset review is required.");
            return;
        }
        Set<String> reviewerIds = new HashSet<>();
        for (int index = 0; index < reviews.size(); index++) {
            ExpertAnnotationBundle.DatasetReview review = reviews.get(index);
            String prefix = "Dataset review " + index;
            if (review == null) {
                issues.add(prefix + " is missing.");
                continue;
            }
            if (review.status() == null) {
                issues.add(prefix + " status is required.");
            } else if (review.status() == ExpertReviewStatus.PENDING) {
                issues.add(prefix + " must be a completed external review, not PENDING.");
            }
            validateReviewerFields(prefix, review.reviewerId(), review.reviewerQualification(), review.reviewedAt(),
                    review.rationale(), issues);
            if (nonBlank(review.reviewerId()) && !reviewerIds.add(review.reviewerId())) {
                issues.add("Duplicate dataset reviewer identifier.");
            }
        }
    }

    private static void validateAnnotation(ExpertAnnotationBundle.Annotation annotation, int index,
                                           AnswerEvalDataset dataset,
                                           Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                           Map<Target, Set<String>> reviewerIds, List<String> issues) {
        String prefix = "Annotation " + index;
        if (annotation == null) {
            issues.add(prefix + " is missing.");
            return;
        }
        Target target = new Target(annotation.baseline(), annotation.caseId());
        validateTarget(prefix, target, annotation.candidateSha256(), annotation.reviewedEvidence(), dataset, answers, issues);
        validateReviewRecord(prefix, annotation.status(), annotation.reviewerId(), annotation.reviewerQualification(),
                annotation.reviewedAt(), annotation.labels(), annotation.rationale(), issues);
        if (target.valid() && nonBlank(annotation.reviewerId())) {
            Set<String> reviewers = reviewerIds.computeIfAbsent(target, ignored -> new HashSet<>());
            if (!reviewers.add(annotation.reviewerId())) {
                issues.add("Duplicate reviewer for the same baseline candidate.");
            }
        }
    }

    private static void validateAdjudication(ExpertAnnotationBundle.Adjudication adjudication, int index,
                                             AnswerEvalDataset dataset,
                                             Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                             Map<Target, Set<String>> reviewerIds,
                                             Map<Target, Set<String>> adjudicatorIds, List<String> issues) {
        String prefix = "Adjudication " + index;
        if (adjudication == null) {
            issues.add(prefix + " is missing.");
            return;
        }
        Target target = new Target(adjudication.baseline(), adjudication.caseId());
        validateTarget(prefix, target, adjudication.candidateSha256(), adjudication.reviewedEvidence(), dataset, answers, issues);
        validateReviewRecord(prefix, adjudication.status(), adjudication.adjudicatorId(),
                adjudication.adjudicatorQualification(), adjudication.adjudicatedAt(), adjudication.labels(),
                adjudication.rationale(), issues);
        if (target.valid() && nonBlank(adjudication.adjudicatorId())) {
            if (reviewerIds.getOrDefault(target, Set.of()).contains(adjudication.adjudicatorId())) {
                issues.add("Adjudicator must not also be a reviewer for the same baseline candidate.");
            }
            Set<String> adjudicators = adjudicatorIds.computeIfAbsent(target, ignored -> new HashSet<>());
            if (!adjudicators.add(adjudication.adjudicatorId())) {
                issues.add("Duplicate adjudicator for the same baseline candidate.");
            }
        }
    }

    private static void validateTarget(String prefix, Target target, String declaredCandidateSha256,
                                       List<EvidenceReference> declaredEvidence,
                                       AnswerEvalDataset dataset,
                                       Map<AnswerBaseline, Map<String, AnswerCandidate>> answers,
                                       List<String> issues) {
        if (!target.valid()) {
            issues.add(prefix + " baseline and case identifier are required.");
            return;
        }
        if (dataset == null || answers == null) {
            issues.add(prefix + " cannot bind without a dataset and saved answers.");
            return;
        }
        AnswerEvalCase evalCase = dataset.cases().stream()
                .filter(candidate -> target.caseId().equals(candidate.id()))
                .findFirst().orElse(null);
        if (evalCase == null) {
            issues.add(prefix + " references an unknown dataset case.");
            return;
        }
        Map<String, AnswerCandidate> baselineCandidates = answers.get(target.baseline());
        AnswerCandidate candidate = baselineCandidates == null ? null : baselineCandidates.get(target.caseId());
        if (candidate == null) {
            issues.add(prefix + " references a missing saved baseline candidate.");
            return;
        }
        String declared = normalizeHash(declaredCandidateSha256, prefix + " candidate digest", issues);
        String actual = CandidateFingerprint.sha256(target.baseline(), target.caseId(), candidate);
        if (!hashEquals(declared, actual)) {
            issues.add(prefix + " candidate digest does not bind to the saved candidate.");
        }
        if (!fullEvidenceEquals(evalCase.evidence(), declaredEvidence)) {
            issues.add(prefix + " immutable evidence does not exactly match the dataset evidence tuple.");
        }
    }

    private static void validateReviewRecord(String prefix, ExpertReviewStatus status, String reviewerId,
                                             String qualification, java.time.Instant reviewedAt,
                                             ExpertAnnotationBundle.MetricLabels labels, String rationale,
                                             List<String> issues) {
        if (status == null) {
            issues.add(prefix + " review status is required.");
        } else if (status == ExpertReviewStatus.PENDING) {
            issues.add(prefix + " must be a completed external review, not PENDING.");
        }
        validateReviewerFields(prefix, reviewerId, qualification, reviewedAt, rationale, issues);
        if (labels == null || !labels.complete()) {
            issues.add(prefix + " must supply all seven named expert labels.");
        }
    }

    private static void validateReviewerFields(String prefix, String reviewerId, String qualification,
                                               java.time.Instant reviewedAt, String rationale, List<String> issues) {
        if (!nonBlank(reviewerId)) {
            issues.add(prefix + " reviewer identifier is required.");
        }
        if (!nonBlank(qualification)) {
            issues.add(prefix + " reviewer qualification is required.");
        }
        if (reviewedAt == null) {
            issues.add(prefix + " review timestamp is required.");
        }
        if (!nonBlank(rationale)) {
            issues.add(prefix + " reviewer rationale is required.");
        }
    }

    private static boolean fullEvidenceEquals(List<EvidenceReference> expected, List<EvidenceReference> declared) {
        if (expected == null || declared == null || expected.size() != declared.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            EvidenceReference expectedReference = expected.get(index);
            EvidenceReference declaredReference = declared.get(index);
            if (!validEvidenceReference(expectedReference) || !validEvidenceReference(declaredReference)
                    || !expectedReference.equals(declaredReference)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validEvidenceReference(EvidenceReference reference) {
        return reference != null && nonBlank(reference.articleId()) && nonBlank(reference.articleRevisionId())
                && reference.revisionNumber() > 0 && nonBlank(reference.evidenceSnapshotId())
                && reference.sourceSpanStart() != null && reference.sourceSpanEnd() != null
                && reference.sourceSpanStart() >= 0 && reference.sourceSpanEnd() >= reference.sourceSpanStart();
    }

    private static String canonicalBundleSha256(ExpertAnnotationBundle bundle) {
        return canonicalJsonSha256(bundle);
    }

    private static String canonicalJsonSha256(Object value) {
        try {
            return CandidateFingerprint.sha256Bytes(JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Canonical annotation serialization failed", serializationFailure);
        }
    }

    private static String normalizeHash(String value, String fieldName, List<String> issues) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            issues.add(fieldName + " must be a SHA-256 hex digest.");
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean hashEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void append(StringBuilder target, String name, Object value) {
        target.append('|').append(name.length()).append(':').append(name).append('=');
        if (value == null) {
            target.append("-1:");
            return;
        }
        String text = value.toString();
        target.append(text.length()).append(':').append(text);
    }

    private record Target(AnswerBaseline baseline, String caseId) {
        private boolean valid() {
            return baseline != null && nonBlank(caseId);
        }
    }
}
