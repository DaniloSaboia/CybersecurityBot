package com.sentinel.kms.kb;

import com.sentinel.kms.common.EvidenceReference;

/** Client-supplied immutable article evidence identity for the read-only passage viewer. */
public record EvidenceViewRequest(
        String articleId,
        String articleRevisionId,
        int revisionNumber,
        String evidenceSnapshotId,
        int sourceSpanStart,
        int sourceSpanEnd) {

    public boolean isWellFormed() {
        return present(articleId)
                && present(articleRevisionId)
                && revisionNumber > 0
                && present(evidenceSnapshotId)
                && sourceSpanStart >= 0
                && sourceSpanEnd > sourceSpanStart;
    }

    EvidenceReference candidate() {
        return new EvidenceReference(articleId, articleRevisionId, revisionNumber, evidenceSnapshotId,
                sourceSpanStart, sourceSpanEnd, null, null, null);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
