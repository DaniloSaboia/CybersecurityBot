package com.sentinel.kms.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI implementation of {@link EmbeddingIndexService}. Indexing calls the
 * active {@code EmbeddingModel} (via {@code VectorStore.add}) and stores vectors
 * in the {@code vector_store} pgvector table; {@link #search} runs cosine
 * similarity, while {@link #hybridSearch} fuses that vector leg with a Postgres
 * full-text leg over the generated {@code content_tsv} column (see the upgrade
 * migration) via reciprocal-rank fusion.
 */
@Service
public class SpringAiEmbeddingIndexService implements EmbeddingIndexService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpringAiEmbeddingIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public int index(List<String> chunks, Map<String, Object> metadata) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> md = new HashMap<>();
            if (metadata != null) {
                md.putAll(metadata);
            }
            md.put("chunk_index", i);
            docs.add(Document.builder().text(text).metadata(md).build());
        }
        if (docs.isEmpty()) {
            return 0;
        }
        vectorStore.add(docs);
        return docs.size();
    }

    @Override
    public List<RetrievedChunk> search(String query, int topK, double minScore) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(minScore)
                .build();
        List<Document> results = vectorStore.similaritySearch(request);
        if (results == null) {
            return List.of();
        }
        List<RetrievedChunk> chunks = new ArrayList<>(results.size());
        for (Document d : results) {
            double score = d.getScore() == null ? 0.0 : d.getScore();
            chunks.add(new RetrievedChunk(d.getText(), score, d.getMetadata()));
        }
        return chunks;
    }

    @Override
    public List<RetrievedChunk> hybridSearch(String query, int topK, double minScore) {
        List<HybridRanker.LegRow> vectorLeg;
        try {
            float[] vector = embeddingModel.embed(query);
            String literal = toVectorLiteral(vector);
            vectorLeg = jdbcTemplate.query("""
                            select id::text, content, metadata::text as metadata,
                                   1 - (embedding <=> ?::vector) as score
                            from vector_store
                            where 1 - (embedding <=> ?::vector) >= ?
                            order by embedding <=> ?::vector
                            limit ?""",
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), false),
                    literal, literal, minScore, topK * 2);
        } catch (Exception e) {
            // Embedding or DB hiccup: degrade to the lexical leg only.
            vectorLeg = List.of();
        }

        List<HybridRanker.LegRow> ftsLeg = List.of();
        try {
            ftsLeg = jdbcTemplate.query("""
                            select id::text, content, metadata::text as metadata,
                                   ts_rank(content_tsv, q) as score
                            from vector_store, websearch_to_tsquery('simple', ?) q
                            where content_tsv @@ q
                            order by ts_rank(content_tsv, q) desc
                            limit ?""",
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), true),
                    query, topK * 2);
        } catch (Exception ignored) {
            // No FTS index / empty table — vector leg still answers.
        }

        List<RetrievedChunk> out = new ArrayList<>();
        for (HybridRanker.LegRow row : HybridRanker.merge(vectorLeg, ftsLeg, topK)) {
            out.add(new RetrievedChunk(row.content(), row.score(), parseMetadata(row.metadataJson())));
        }
        return out;
    }

    @Override
    public long count() {
        Long n = jdbcTemplate.queryForObject("select count(*) from vector_store", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public int deleteByJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return 0;
        }
        // metadata is jsonb; chunks indexed for an upload/URL job carry job_id.
        return jdbcTemplate.update(
                "delete from vector_store where metadata->>'job_id' = ?", jobId);
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
