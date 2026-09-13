package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

import java.time.Instant;
import java.util.List;

/**
 * Versioned, externally supplied human-review sidecar. The importer validates
 * its binding to saved inputs; it cannot authenticate a reviewer or establish
 * their real-world qualifications.
 */
public record ExpertAnnotationBundle(
        String schemaVersion,
        String datasetId,
        String datasetSchemaVersion,
        String datasetSha256,
        String answersSha256,
        List<DatasetReview> datasetReviews,
        List<Annotation> annotations,
        List<Adjudication> adjudications) {

    public ExpertAnnotationBundle {
        schemaVersion = trimToNull(schemaVersion);
        datasetId = trimToNull(datasetId);
        datasetSchemaVersion = trimToNull(datasetSchemaVersion);
        datasetSha256 = trimToNull(datasetSha256);
        answersSha256 = trimToNull(answersSha256);
        datasetReviews = datasetReviews == null ? List.of() : List.copyOf(datasetReviews);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        adjudications = adjudications == null ? List.of() : List.copyOf(adjudications);
    }

    /** Source-compatible constructor for callers that have exactly one review. */
    public ExpertAnnotationBundle(String schemaVersion, String datasetId, String datasetSchemaVersion,
                                  String datasetSha256, String answersSha256, DatasetReview datasetReview,
                                  List<Annotation> annotations, List<Adjudication> adjudications) {
        this(schemaVersion, datasetId, datasetSchemaVersion, datasetSha256, answersSha256,
                datasetReview == null ? List.of() : List.of(datasetReview), annotations, adjudications);
    }

    /** Dataset-level review metadata, distinct from a per-candidate verdict. */
    public record DatasetReview(
            ExpertReviewStatus status,
            String reviewerId,
            String reviewerQualification,
            boolean independent,
            Instant reviewedAt,
            String rationale) {

        public DatasetReview {
            reviewerId = trimToNull(reviewerId);
            reviewerQualification = trimToNull(reviewerQualification);
            rationale = trimToNull(rationale);
        }
    }

    /** One reviewer’s complete labels for exactly one saved baseline candidate. */
    public record Annotation(
            String caseId,
            AnswerBaseline baseline,
            String candidateSha256,
            ExpertReviewStatus status,
            String reviewerId,
            String reviewerQualification,
            boolean independent,
            Instant reviewedAt,
            List<EvidenceReference> reviewedEvidence,
            MetricLabels labels,
            String rationale) {

        public Annotation {
            caseId = trimToNull(caseId);
            candidateSha256 = trimToNull(candidateSha256);
            reviewerId = trimToNull(reviewerId);
            reviewerQualification = trimToNull(reviewerQualification);
            reviewedEvidence = reviewedEvidence == null ? List.of() : List.copyOf(reviewedEvidence);
            rationale = trimToNull(rationale);
        }
    }

    /**
     * A separate reviewer’s resolution of an uncertain or disagreeing candidate
     * review. It repeats the immutable evidence tuple to prevent an unbound
     * adjudication from being attached to a different snapshot.
     */
    public record Adjudication(
            String caseId,
            AnswerBaseline baseline,
            String candidateSha256,
            ExpertReviewStatus status,
            String adjudicatorId,
            String adjudicatorQualification,
            boolean independent,
            Instant adjudicatedAt,
            List<EvidenceReference> reviewedEvidence,
            MetricLabels labels,
            String rationale) {

        public Adjudication {
            caseId = trimToNull(caseId);
            candidateSha256 = trimToNull(candidateSha256);
            adjudicatorId = trimToNull(adjudicatorId);
            adjudicatorQualification = trimToNull(adjudicatorQualification);
            reviewedEvidence = reviewedEvidence == null ? List.of() : List.copyOf(reviewedEvidence);
            rationale = trimToNull(rationale);
        }
    }

    /** All answer-level dimensions must be explicitly labelled by a reviewer. */
    public record MetricLabels(
            ExpertVerdict citationSupport,
            ExpertVerdict faithfulness,
            ExpertVerdict correctness,
            ExpertVerdict abstention,
            ExpertVerdict temporalAccuracy,
            ExpertVerdict adversarialResistance,
            ExpertVerdict authorizationLeakage) {

        public boolean complete() {
            return citationSupport != null && faithfulness != null && correctness != null
                    && abstention != null && temporalAccuracy != null && adversarialResistance != null
                    && authorizationLeakage != null;
        }

        public boolean hasUncertain() {
            return citationSupport == ExpertVerdict.UNCERTAIN || faithfulness == ExpertVerdict.UNCERTAIN
                    || correctness == ExpertVerdict.UNCERTAIN || abstention == ExpertVerdict.UNCERTAIN
                    || temporalAccuracy == ExpertVerdict.UNCERTAIN || adversarialResistance == ExpertVerdict.UNCERTAIN
                    || authorizationLeakage == ExpertVerdict.UNCERTAIN;
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
