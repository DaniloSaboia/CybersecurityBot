package com.sentinel.kms.common;

import java.util.Map;
import java.util.Optional;

/** Immutable source/version/span identity carried with governed retrieval results. */
public record EvidenceReference(
        String articleId,
        String articleRevisionId,
        int revisionNumber,
        String evidenceSnapshotId,
        Integer sourceSpanStart,
        Integer sourceSpanEnd,
        String sourceUri,
        String sourceVersion,
        String revisionStatus) {

    /**
     * Parses only complete, immutable evidence identity. Missing provenance is not
     * silently converted into an article-level citation.
     */
    public static Optional<EvidenceReference> fromMetadata(Map<String, ?> metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        String articleId = value(metadata, "article_id");
        String revisionId = firstValue(metadata, "article_revision_id", "revision_id");
        String snapshotId = value(metadata, "evidence_snapshot_id");
        Integer revisionNumber = integer(metadata.get("article_revision_number"));
        if (blank(articleId) || blank(revisionId) || blank(snapshotId) || revisionNumber == null
                || revisionNumber < 1) {
            return Optional.empty();
        }
        Integer start = integer(metadata.get("source_span_start"));
        Integer end = integer(metadata.get("source_span_end"));
        if (start == null || end == null || start < 0 || end < start) {
            return Optional.empty();
        }
        return Optional.of(new EvidenceReference(articleId, revisionId, revisionNumber, snapshotId,
                start, end, value(metadata, "source_uri"), value(metadata, "source_version"),
                firstValue(metadata, "revision_status", "approval_status")));
    }

    private static String firstValue(Map<String, ?> metadata, String first, String second) {
        String value = value(metadata, first);
        return blank(value) ? value(metadata, second) : value;
    }

    private static String value(Map<String, ?> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
