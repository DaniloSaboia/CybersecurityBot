package com.sentinel.kms.eval;

/** Human-review metadata carried with a fixture or result. */
public record ExpertReview(
        ExpertReviewStatus status,
        String reviewerId,
        String reviewedAt,
        String note) {

    public ExpertReview {
        status = status == null ? ExpertReviewStatus.PENDING : status;
        reviewerId = blankToNull(reviewerId);
        reviewedAt = blankToNull(reviewedAt);
        note = blankToNull(note);
        if (status != ExpertReviewStatus.PENDING && (reviewerId == null || reviewedAt == null)) {
            throw new IllegalArgumentException("accepted/rejected expert reviews require reviewerId and reviewedAt");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
