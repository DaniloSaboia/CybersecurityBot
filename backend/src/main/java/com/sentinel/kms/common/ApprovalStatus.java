package com.sentinel.kms.common;

/** Review state of the revision currently exposed by an article. */
public enum ApprovalStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    SUPERSEDED,
    ROLLED_BACK
}
