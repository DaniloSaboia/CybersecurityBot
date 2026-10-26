package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

/**
 * Citation emitted by an answer candidate. Null provenance fields represent a
 * legacy article-id-only citation and are retained for compatibility, but do
 * not satisfy a fixture that requires source version and passage offsets.
 */
public record CitationReference(
        String articleId,
        String articleRevisionId,
        Integer revisionNumber,
        String evidenceSnapshotId,
        Integer sourceSpanStart,
        Integer sourceSpanEnd,
        String sourceUri,
        String sourceVersion,
        String revisionStatus) {

    public CitationReference {
        if (articleId == null || articleId.isBlank()) {
            throw new IllegalArgumentException("articleId is required");
        }
        articleId = articleId.trim();
        articleRevisionId = blankToNull(articleRevisionId);
        evidenceSnapshotId = blankToNull(evidenceSnapshotId);
        sourceUri = blankToNull(sourceUri);
        sourceVersion = blankToNull(sourceVersion);
        revisionStatus = blankToNull(revisionStatus);
        if (revisionNumber != null && revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive when supplied");
        }
        if ((sourceSpanStart == null) != (sourceSpanEnd == null)
                || (sourceSpanStart != null && (sourceSpanStart < 0 || sourceSpanEnd < sourceSpanStart))) {
            throw new IllegalArgumentException("citation offsets must be both absent or a non-empty range");
        }
    }

    public CitationReference(EvidenceReference evidence) {
        this(evidence.articleId(), evidence.articleRevisionId(), evidence.revisionNumber(), evidence.evidenceSnapshotId(),
                evidence.sourceSpanStart(), evidence.sourceSpanEnd(), evidence.sourceUri(), evidence.sourceVersion(),
                evidence.revisionStatus());
    }

    public static CitationReference legacyArticleId(String articleId) {
        return new CitationReference(articleId, null, null, null, null, null, null, null, null);
    }

    public boolean hasCompleteProvenance() {
        return articleRevisionId != null && revisionNumber != null && evidenceSnapshotId != null
                && sourceVersion != null && sourceSpanStart != null && sourceSpanEnd != null;
    }

    public boolean covers(EvidenceReference expected) {
        if (expected == null
                || !articleId.equals(expected.articleId())
                || !same(articleRevisionId, expected.articleRevisionId())
                || !same(revisionNumber, expected.revisionNumber())
                || !same(evidenceSnapshotId, expected.evidenceSnapshotId())
                || !same(sourceUri, expected.sourceUri())
                || !same(sourceVersion, expected.sourceVersion())) {
            return false;
        }
        if (!same(revisionStatus, expected.revisionStatus())) {
            return false;
        }
        if (expected.sourceSpanStart() == null) {
            return sourceSpanStart == null && sourceSpanEnd == null;
        }
        return sourceSpanStart != null && sourceSpanEnd != null
                && sourceSpanStart <= expected.sourceSpanStart()
                && sourceSpanEnd >= expected.sourceSpanEnd();
    }

    private static boolean same(Object left, Object right) {
        return left != null && left.equals(right);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
