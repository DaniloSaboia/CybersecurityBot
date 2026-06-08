package com.sentinel.kms.vectorstore;

import org.springframework.ai.document.Document;
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
 * in the {@code vector_store} pgvector table; search runs cosine similarity.
 */
@Service
public class SpringAiEmbeddingIndexService implements EmbeddingIndexService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public SpringAiEmbeddingIndexService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
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
    public long count() {
        Long n = jdbcTemplate.queryForObject("select count(*) from vector_store", Long.class);
        return n == null ? 0L : n;
    }
}
