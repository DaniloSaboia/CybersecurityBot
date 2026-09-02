package com.sentinel.kms.vectorstore;

import java.util.List;
import java.util.Map;

/**
 * The seam between ingestion (which writes embeddings) and the RAG assistant
 * (which reads them). Backed by Spring AI's {@code VectorStore} over pgvector;
 * the active embedding model (Mistral or Ollama) is chosen by Spring profile.
 */
public interface EmbeddingIndexService {

    /**
     * Embed and store a batch of chunks for one document. The embedding model is
     * invoked by the vector store; each chunk inherits {@code metadata} plus a
     * 0-based {@code chunk_index}.
     *
     * @return number of chunks indexed
     */
    int index(List<String> chunks, Map<String, Object> metadata);

    /** Similarity search over shared chunks plus chunks owned by {@code userSub}. */
    List<RetrievedChunk> search(String query, int topK, double minScore, String userSub);

    /** Internal/shared-only search retained for callers that have no user context. */
    default List<RetrievedChunk> search(String query, int topK, double minScore) {
        return search(query, topK, minScore, null);
    }

    /**
     * Hybrid retrieval: cosine-similarity vectors fused with Postgres full-text
     * ranking (reciprocal-rank fusion). Exact tokens like CVE ids and hashes
     * match reliably via the lexical leg even when embeddings score them low.
     * Defaults to plain similarity search.
     */
    default List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub) {
        return search(query, topK, minScore, userSub);
    }

    /** Internal/shared-only hybrid search retained for callers without a user context. */
    default List<RetrievedChunk> hybridSearch(String query, int topK, double minScore) {
        return hybridSearch(query, topK, minScore, null);
    }

    /**
     * Hybrid retrieval with an article scope: chunks are kept only when their
     * backing article matches {@code filters} (category / minimum severity).
     */
    default List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub, RetrievalFilters filters) {
        return hybridSearch(query, topK, minScore, userSub);
    }

    /** Total number of indexed chunks (for the vector-store status card). */
    long count();

    /**
     * Remove every chunk indexed for an ingestion job (metadata {@code job_id}).
     *
     * @return number of chunks deleted
     */
    int deleteByJob(String jobId);
}
