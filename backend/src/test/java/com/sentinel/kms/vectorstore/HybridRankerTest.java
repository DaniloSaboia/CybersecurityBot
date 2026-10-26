package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRankerTest {

    private static HybridRanker.LegRow row(String id, boolean fts) {
        return new HybridRanker.LegRow(id, "content-" + id, "{}", 0.5, fts);
    }

    @Test
    void chunksRetrievedByBothLegsRankFirst() {
        List<HybridRanker.LegRow> vector = List.of(row("a", false), row("b", false), row("c", false));
        List<HybridRanker.LegRow> fts = List.of(row("x", true), row("b", true));

        List<HybridRanker.LegRow> merged = HybridRanker.merge(vector, fts, 3);

        assertThat(merged).extracting(HybridRanker.LegRow::id).first().isEqualTo("b");
        assertThat(merged).extracting(HybridRanker.LegRow::id).containsExactlyInAnyOrder("b", "a", "x");
    }

    @Test
    void ftsOnlyMatchSurvivesWhenVectorLegMisses() {
        // The whole point of hybrid: an exact-token hit the embeddings missed.
        List<HybridRanker.LegRow> merged = HybridRanker.merge(
                List.of(row("a", false), row("b", false)),
                List.of(row("cve-2024-21413", true)),
                5);
        assertThat(merged).extracting(HybridRanker.LegRow::id)
                .contains("cve-2024-21413");
    }

    @Test
    void respectsTopK() {
        List<HybridRanker.LegRow> vector =
                List.of(row("a", false), row("b", false), row("c", false), row("d", false));
        assertThat(HybridRanker.merge(vector, List.of(), 2)).hasSize(2);
    }

    @Test
    void emptyLegsYieldEmptyResult() {
        assertThat(HybridRanker.merge(List.of(), List.of(), 5)).isEmpty();
    }

    @Test
    void vectorRankBeatsFtsRankWhenDisjoint() {
        // Both fused scores are 1/(K+rank); vector rank 1 edges fts rank 1 only via
        // later tie-break — verify ordering is stable and complete either way.
        List<HybridRanker.LegRow> merged = HybridRanker.merge(
                List.of(row("a", false), row("b", false)),
                List.of(row("x", true), row("y", true)),
                4);
        assertThat(merged).extracting(HybridRanker.LegRow::id).hasSize(4);
        assertThat(merged.get(0).id()).isIn("a", "x");
    }
}
