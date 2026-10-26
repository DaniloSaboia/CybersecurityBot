package com.sentinel.kms.vectorstore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal-rank fusion of the two retrieval legs (vector + full-text).
 * Fused score = Σ 1 / (k + rank) with k = 60 (the standard constant); a chunk
 * retrieved by both legs outranks anything retrieved by one. Pure/static so
 * the ranking rules are unit-testable without a database.
 */
final class HybridRanker {

    private HybridRanker() {
    }

    static final int K = 60;

    /** One row from a retrieval leg, keyed by vector_store id. */
    record LegRow(String id, String content, String metadataJson, double score, boolean fromFts) {
    }

    static List<LegRow> merge(List<LegRow> vectorLeg, List<LegRow> ftsLeg, int topK) {
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, LegRow> rows = new LinkedHashMap<>();
        accumulate(vectorLeg, fused, rows);
        accumulate(ftsLeg, fused, rows);

        List<Map.Entry<String, Double>> ordered = new ArrayList<>(fused.entrySet());
        ordered.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<LegRow> out = new ArrayList<>(Math.min(topK, ordered.size()));
        for (Map.Entry<String, Double> e : ordered) {
            if (out.size() >= topK) {
                break;
            }
            out.add(rows.get(e.getKey()));
        }
        return out;
    }

    private static void accumulate(List<LegRow> leg, Map<String, Double> fused, Map<String, LegRow> rows) {
        if (leg == null) {
            return;
        }
        for (int rank = 1; rank <= leg.size(); rank++) {
            LegRow row = leg.get(rank - 1);
            fused.merge(row.id(), 1.0 / (K + rank), Double::sum);
            rows.putIfAbsent(row.id(), row);
        }
    }
}
