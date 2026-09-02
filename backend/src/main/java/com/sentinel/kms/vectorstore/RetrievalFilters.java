package com.sentinel.kms.vectorstore;

/**
 * Optional scope applied to retrieval: only chunks whose backing article is in
 * the given category, and whose severity ranks at or above the minimum. Both
 * fields are nullable ("no filter").
 */
public record RetrievalFilters(String category, String minSeverity) {

    public static final RetrievalFilters NONE = new RetrievalFilters(null, null);

    public boolean isEmpty() {
        return (category == null || category.isBlank())
                && (minSeverity == null || minSeverity.isBlank());
    }

    /** Rank used for the severity threshold: critical &gt; high &gt; medium &gt; low &gt; info. */
    public static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toLowerCase()) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
