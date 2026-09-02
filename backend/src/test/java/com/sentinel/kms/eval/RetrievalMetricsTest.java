package com.sentinel.kms.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricsTest {

    private final List<String> ranked = List.of("A", "B", "C", "D");
    private final Set<String> relevant = Set.of("B", "D");

    @Test
    void recallAtKCountsHitsWithinTheWindow() {
        assertThat(RetrievalMetrics.recallAtK(ranked, relevant, 2)).isEqualTo(0.5);  // B hit
        assertThat(RetrievalMetrics.recallAtK(ranked, relevant, 4)).isEqualTo(1.0);  // B + D
        assertThat(RetrievalMetrics.recallAtK(ranked, Set.of("Z"), 4)).isEqualTo(0.0);
    }

    @Test
    void mrrUsesFirstRelevantRank() {
        assertThat(RetrievalMetrics.mrr(ranked, relevant)).isEqualTo(0.5);           // B at rank 2
        assertThat(RetrievalMetrics.mrr(ranked, Set.of("A"))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.mrr(ranked, Set.of("Z"))).isEqualTo(0.0);
    }

    @Test
    void ndcgRewardsHigherPlacement() {
        double dFirst = RetrievalMetrics.ndcgAtK(List.of("B", "X", "D", "Y"), relevant, 4);
        double dLast = RetrievalMetrics.ndcgAtK(List.of("X", "Y", "B", "D"), relevant, 4);
        double perfect = RetrievalMetrics.ndcgAtK(List.of("B", "D", "X", "Y"), relevant, 4);

        assertThat(perfect).isEqualTo(1.0);
        assertThat(dFirst).isGreaterThan(dLast);
        assertThat(dFirst).isLessThanOrEqualTo(1.0);
        assertThat(RetrievalMetrics.ndcgAtK(ranked, Set.of(), 4)).isEqualTo(0.0);
    }
}
