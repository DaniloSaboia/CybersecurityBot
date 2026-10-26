package com.sentinel.kms.eval;

import java.util.List;
import java.util.Set;

/**
 * Standard retrieval-quality metrics over a ranked list of article ids.
 * Pure/static so the math is unit-testable without a database or model.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * Recall@k: the fraction of the relevant ids that appear in the first k
     * ranked positions.
     */
    public static double recallAtK(List<String> ranked, Set<String> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (int i = 0; i < ranked.size() && i < k; i++) {
            if (relevant.contains(ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / relevant.size();
    }

    /**
     * Mean reciprocal rank: 1 / rank of the first relevant result, 0 when no
     * relevant result is retrieved at all.
     */
    public static double mrr(List<String> ranked, Set<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Normalized discounted cumulative gain at k with binary gains: DCG =
     * Σ rel_i / log2(i + 1) over the ranked list, normalized by the ideal
     * DCG (all relevant ids ranked first). Ties in ordering are not rewarded.
     */
    public static double ndcgAtK(List<String> ranked, Set<String> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        for (int i = 0; i < ranked.size() && i < k; i++) {
            if (relevant.contains(ranked.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(relevant.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }
}
