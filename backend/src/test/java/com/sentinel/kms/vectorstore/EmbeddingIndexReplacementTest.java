package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingIndexReplacementTest {

    @Mock VectorStore vectorStore;
    @Mock JdbcTemplate jdbc;
    @Mock EmbeddingModel embeddingModel;

    @Test
    void replaceBySourceUsesStableDocumentIdsAndOverridesReservedMetadata() {
        SpringAiEmbeddingIndexService index = new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(2);

        int indexed = index.replaceBySource("d2f4:revision-7", List.of("alpha", "beta"), Map.of(
                "source_key", "forged-by-caller",
                "chunk_index", 99,
                "title", "trusted persisted title"));

        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        List<Document> stored = documents.getValue();
        assertThat(indexed).isEqualTo(2);
        assertThat(stored).extracting(Document::getId).doesNotHaveDuplicates();
        assertThat(stored).allSatisfy(document -> {
            assertThat(document.getMetadata()).containsEntry("source_key", "d2f4:revision-7");
            assertThat(document.getMetadata()).containsEntry("title", "trusted persisted title");
        });
        assertThat(stored.get(0).getMetadata()).containsEntry("chunk_index", 0);
        assertThat(stored.get(1).getMetadata()).containsEntry("chunk_index", 1);
    }

    @Test
    void genericSourceReplacementCannotOverwriteAnArticleWithCallerSuppliedProvenance() {
        SpringAiEmbeddingIndexService index = new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        assertThatThrownBy(() -> index.replaceBySource("article:article-1", List.of("forged passage"),
                Map.of("article_revision_id", "forged-revision", "evidence_snapshot_id", "forged-evidence")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replaceForArticleRevision");

        verifyNoInteractions(jdbc, vectorStore, embeddingModel);
    }

    @Test
    void replaceForArticleRevisionBuildsOnlyDatabaseCanonicalEvidenceMetadata() throws Exception {
        ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
        when(row.getString("article_id")).thenReturn("article-1");
        when(row.getString("article_revision_id")).thenReturn("revision-2");
        when(row.getInt("revision_number")).thenReturn(2);
        when(row.getString("revision_status")).thenReturn("approved");
        when(row.getString("evidence_snapshot_id")).thenReturn("evidence-2");
        when(row.getInt("ordinal")).thenReturn(0);
        when(row.getString("source_uri")).thenReturn("https://source.example/advisory");
        when(row.getString("source_version")).thenReturn("2026-09-06");
        when(row.getString("content")).thenReturn("canonical evidence");
        when(row.getInt("evidence_length")).thenReturn("canonical evidence".length());
        when(row.getLong("governance_state_version")).thenReturn(7L);
        when(jdbc.query(contains("from article_governance"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(row, 0)));
        when(jdbc.update(contains("delete from vector_store"), any(Object[].class))).thenReturn(1);
        when(embeddingModel.embed(org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of(new float[]{1f, 0f}));

        SpringAiEmbeddingIndexService index = new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        int indexed = index.replaceForArticleRevision("article-1", "revision-2", 7L);

        assertThat(indexed).isEqualTo(1);
        ArgumentCaptor<BatchPreparedStatementSetter> batch = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(contains("insert into vector_store"), batch.capture());
        PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
        batch.getValue().setValues(statement, 0);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(org.mockito.ArgumentMatchers.eq(3), metadata.capture());
        assertThat(metadata.getValue()).contains(
                "\"article_id\":\"article-1\"",
                "\"article_revision_id\":\"revision-2\"",
                "\"evidence_snapshot_id\":\"evidence-2\"",
                "\"source_span_start\":0",
                "\"source_span_end\":18",
                "\"source_uri\":\"https://source.example/advisory\"");
        // Article replacement owns exactly article:<id>; an unrelated upload
        // must never be deleted merely because it carries article_id metadata.
        verify(jdbc).update(contains("metadata->>'source_key' = ?"),
                org.mockito.ArgumentMatchers.eq("article:article-1"));
    }
}
