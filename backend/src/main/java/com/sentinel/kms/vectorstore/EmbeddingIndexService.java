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

    /** Similarity search over all indexed chunks. */
    List<RetrievedChunk> search(String query, int topK, double minScore);

    /** Total number of indexed chunks (for the vector-store status card). */
    long count();

    /**
     * Remove every chunk indexed for an ingestion job (metadata {@code job_id}).
     *
     * @return number of chunks deleted
     */
    int deleteByJob(String jobId);
}
