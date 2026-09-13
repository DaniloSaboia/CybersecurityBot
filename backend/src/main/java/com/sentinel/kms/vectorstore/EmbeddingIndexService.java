package com.sentinel.kms.vectorstore;

import com.sentinel.kms.common.KnowledgeAccessContext;

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

    /**
     * Idempotently replace all passages belonging to one immutable server-generated
     * source key. Implementations must stamp {@code source_key} themselves rather
     * than accepting a caller supplied value as authority.
     */
    int replaceBySource(String sourceKey, List<String> chunks, Map<String, Object> metadata);

    /**
     * Rebuilds one currently-approved article revision from its immutable
     * database evidence.  The caller supplies no content or provenance metadata:
     * implementations must fence the supplied revision against the live
     * governance pointer immediately before replacing its passages.
     *
     * @throws StaleArticleRevisionException when the revision is no longer the
     *                                       current approved revision
     */
    default int replaceForArticleRevision(String articleId, String articleRevisionId) {
        throw new UnsupportedOperationException("article revision replacement is not supported by this index");
    }

    /**
     * Preferred lifecycle/outbox replacement fence.  The supplied governance
     * state version must still name this current approved revision during the
     * final locked write; stale workers must not remove newer passages.
     */
    default int replaceForArticleRevision(String articleId, String articleRevisionId,
                                          long governanceStateVersion) {
        return replaceForArticleRevision(articleId, articleRevisionId);
    }

    /**
     * Fenced replacement for a durable ingestion job. {@code generation} must be
     * the persisted job generation claimed by the worker; stale workers are rejected.
     */
    int replaceForJob(String jobId, long generation, List<String> chunks, Map<String, Object> metadata);

    /**
     * Durable claim-fenced job replacement. The worker must present the current
     * persisted lease token; expired, deleted, cancelled, or stale claims fail.
     */
    int replaceForJob(String jobId, long generation, String claimToken,
                      List<String> chunks, Map<String, Object> metadata);

    /**
     * Compatibility spelling for durable workers introduced while the claim
     * protocol was being wired.  New callers should use {@link #replaceForJob}
     * so the job identity remains explicit in the public contract.
     */
    default int replaceForClaim(String jobId, long generation, String claimToken,
                                List<String> chunks, Map<String, Object> metadata) {
        return replaceForJob(jobId, generation, claimToken, chunks, metadata);
    }

    /** Delete every passage belonging to one immutable server-generated source key. */
    int deleteBySource(String sourceKey);

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

    /**
     * Additive governed retrieval seam.  Consumers pass only a server-resolved
     * context; implementations must still resolve memberships from the database
     * rather than trusting the context's role map as a client assertion.
     */
    default List<RetrievedChunk> hybridSearch(
            KnowledgeAccessContext context, String query, int topK, double minScore,
            RetrievalFilters filters) {
        return hybridSearch(query, topK, minScore,
                context == null ? null : context.subject(), filters);
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
