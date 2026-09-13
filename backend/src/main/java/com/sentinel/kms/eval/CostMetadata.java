package com.sentinel.kms.eval;

/** Cost accounting supplied by the execution adapter; unknown values stay null rather than invented. */
public record CostMetadata(
        String currency,
        String amount,
        boolean estimated,
        String costSource,
        Long inputTokens,
        Long outputTokens) {

    public CostMetadata {
        currency = currency == null || currency.isBlank() ? "USD" : currency.trim();
        amount = amount == null || amount.isBlank() ? null : amount.trim();
        costSource = costSource == null || costSource.isBlank() ? "unknown" : costSource.trim();
    }

    public static CostMetadata unknown() {
        return new CostMetadata("USD", null, true, "unknown", null, null);
    }
}
