package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Regression guard: vector and lexical legs must apply the same DB policy gate. */
@ExtendWith(MockitoExtension.class)
class GovernedHybridSearchSqlTest {

    @Mock VectorStore vectorStore;
    @Mock JdbcTemplate jdbc;
    @Mock EmbeddingModel embeddingModel;

    @Test
    void bothRetrievalLegsFenceTombstonedJobsAndUngovernedArticles() {
        when(embeddingModel.embed("CVE-2026-9999")).thenReturn(new float[]{1f, 0f});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        SpringAiEmbeddingIndexService service =
                new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        service.hybridSearch("CVE-2026-9999", 3, 0.2d, "analyst-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> {
            assertThat(statement).contains("article_governance");
            assertThat(statement).contains("ij.deleted_at is null");
            assertThat(statement).contains("article_revisions");
        });
    }

    @Test
    void bothRetrievalLegsRequireStoredRevisionAndExactEvidenceSpanBinding() {
        when(embeddingModel.embed("CVE-2026-9999")).thenReturn(new float[]{1f, 0f});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        SpringAiEmbeddingIndexService service =
                new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        service.hybridSearch("CVE-2026-9999", 3, 0.2d, "analyst-a");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> {
            // A current governance pointer alone is insufficient: stale chunks
            // must prove that their stored revision is that exact pointer.
            assertThat(statement).contains("vs.metadata->>'article_revision_id' = ag.current_revision_id");
            assertThat(statement).contains("vs.metadata->>'source_key' = 'article:' || ag.article_id");
            assertThat(statement).contains("evidence.id = vs.metadata->>'evidence_snapshot_id'");
            assertThat(statement).contains("source_span_start");
            assertThat(statement).contains("source_span_end");
            assertThat(statement).contains("substring(evidence.content");
        });
    }

    @Test
    void blankSubjectFailsClosedBeforeAnyEmbeddingOrSqlWork() {
        SpringAiEmbeddingIndexService service =
                new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        assertThat(service.hybridSearch("CVE-2026-9999", 3, 0.2d, " ")).isEmpty();

        verifyNoInteractions(embeddingModel, jdbc);
    }

    @Test
    void articleHitIsReplacedByItsGovernedPassagesInDocumentOrder() {
        String meta = "{\"article_id\":\"PB-051\",\"article_revision_id\":\"rev:PB-051:1\",\"chunk_index\":%d}";
        when(embeddingModel.embed("emergency patch")).thenReturn(new float[]{1f, 0f});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String statement = call.getArgument(0);
            if (statement.contains("order by (vs.metadata->>'chunk_index')::integer")) {
                return List.of(new HybridRanker.LegRow("a", "title", meta.formatted(0), 0, false),
                        new HybridRanker.LegRow("b", "when to invoke", meta.formatted(1), 0, false),
                        new HybridRanker.LegRow("c", "approval", meta.formatted(2), 0, false));
            }
            return statement.contains("ts_rank")
                    ? List.of(new HybridRanker.LegRow("c", "approval", meta.formatted(2), 0.4, true))
                    : List.of();
        });

        SpringAiEmbeddingIndexService service =
                new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        List<RetrievedChunk> out = service.hybridSearch("emergency patch", 3, 0.2d, "analyst-a");

        assertThat(out).extracting(RetrievedChunk::content).containsExactly("title", "when to invoke", "approval");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(3)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).filteredOn(s -> s.contains("chunk_index')::integer"))
                .singleElement().satisfies(s -> {
                    assertThat(s).contains("article_governance");
                    assertThat(s).contains("evidence.id = vs.metadata->>'evidence_snapshot_id'");
                });

        service.setArticlePassages(0);
        assertThat(service.hybridSearch("emergency patch", 3, 0.2d, "analyst-a"))
                .extracting(RetrievedChunk::content).containsExactly("approval");
    }
}
