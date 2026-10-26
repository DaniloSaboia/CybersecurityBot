package com.sentinel.kms.vectorstore;

import java.util.Map;

/**
 * One chunk returned from similarity search: the chunk {@code content}, its
 * similarity {@code score} (0..1, higher = closer), and the {@code metadata}
 * stored at index time (article_id, source, heading, chunk_index, ...).
 */
public record RetrievedChunk(String content, double score, Map<String, Object> metadata) {

    /** The KB article this chunk came from, or null for non-article docs. */
    public String articleId() {
        Object v = metadata == null ? null : metadata.get("article_id");
        return v == null ? null : v.toString();
    }

    public String heading() {
        Object v = metadata == null ? null : metadata.get("heading");
        return v == null ? null : v.toString();
    }

    public String source() {
        Object v = metadata == null ? null : metadata.get("source");
        return v == null ? null : v.toString();
    }
}
