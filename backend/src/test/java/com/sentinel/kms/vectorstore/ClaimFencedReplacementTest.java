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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit regressions for the final write fence.  The service may calculate embeddings
 * before taking the database lock, but a worker that lost its durable claim must
 * never delete or add vector rows afterwards.
 */
@ExtendWith(MockitoExtension.class)
class ClaimFencedReplacementTest {

    private static final String JOB_ID = "job-42";
    private static final String CLAIM_TOKEN = "claim-token-42";

    @Mock
    private VectorStore vectorStore;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private EmbeddingModel embeddingModel;

    @Test
    void rejectsAStaleGenerationBeforeRemovingOrIndexingPassages() {
        SpringAiEmbeddingIndexService index = indexWithNoLiveClaim();

        assertThatThrownBy(() -> index.replaceForClaim(
                JOB_ID, 7L, CLAIM_TOKEN, List.of("passage"), Map.of("source_key", "forged")))
                .isInstanceOf(StaleIndexGenerationException.class);

        String claimFence = assertNoVectorWrite(7L, CLAIM_TOKEN);
        assertThat(claimFence).contains("index_generation = ?", "claim_token = ?", "for update");
    }

    @Test
    void rejectsAnExpiredClaimBeforeRemovingOrIndexingPassages() {
        SpringAiEmbeddingIndexService index = indexWithNoLiveClaim();

        assertThatThrownBy(() -> index.replaceForClaim(
                JOB_ID, 7L, CLAIM_TOKEN, List.of("passage"), Map.of()))
                .isInstanceOf(StaleIndexGenerationException.class);

        String claimFence = assertNoVectorWrite(7L, CLAIM_TOKEN);
        assertThat(claimFence).contains("claim_until > now()", "for update");
    }

    @Test
    void rejectsATombstonedClaimBeforeRemovingOrIndexingPassages() {
        SpringAiEmbeddingIndexService index = indexWithNoLiveClaim();

        assertThatThrownBy(() -> index.replaceForClaim(
                JOB_ID, 7L, CLAIM_TOKEN, List.of("passage"), Map.of()))
                .isInstanceOf(StaleIndexGenerationException.class);

        String claimFence = assertNoVectorWrite(7L, CLAIM_TOKEN);
        assertThat(claimFence).contains("deleted_at is null", "cancelled_at is null", "for update");
    }

    private SpringAiEmbeddingIndexService indexWithNoLiveClaim() {
        when(embeddingModel.embed(List.of("passage"))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        doAnswer(invocation -> List.of())
                .when(jdbc).query(contains("from ingest_jobs"), any(RowMapper.class),
                        any(), any(), any());
        return new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);
    }

    private String assertNoVectorWrite(long generation, String claimToken) {
        // Precomputation is expected before the fence; no DB mutation or VectorStore
        // write may occur once the locked claim is rejected.
        verify(embeddingModel).embed(List.of("passage"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(JOB_ID), eq(generation), eq(claimToken));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(vectorStore);
        return sql.getValue();
    }
}
