package com.sentinel.kms.eval;

/**
 * Status of a real human review. Fixtures start as {@link #PENDING}; code must
 * never upgrade this status merely because a deterministic check passes.
 */
public enum ExpertReviewStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
