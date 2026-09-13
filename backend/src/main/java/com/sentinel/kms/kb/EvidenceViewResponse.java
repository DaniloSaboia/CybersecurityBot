package com.sentinel.kms.kb;

/** Canonical immutable article provenance and the exact authorized evidence passage. */
public record EvidenceViewResponse(
        String articleId,
        String articleRevisionId,
        int revisionNumber,
        String evidenceSnapshotId,
        int sourceSpanStart,
        int sourceSpanEnd,
        String sourceLabel,
        String sourceUri,
        String sourceVersion,
        String revisionStatus,
        String passage) {
}
