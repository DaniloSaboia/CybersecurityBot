package com.sentinel.kms.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.PreparedStatement;
import java.sql.SQLException;

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

    private static final Set<String> RESERVED_REPLACEMENT_METADATA = Set.of(
            "source_key", "chunk_index", "job_id", "index_generation");
    private static final String ARTICLE_SOURCE_PREFIX = "article:";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate transactionTemplate;
    private final KnowledgeAuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Production constructor: keeps remote embedding outside the final DB transaction. */
    @Autowired
    public SpringAiEmbeddingIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PlatformTransactionManager transactionManager,
            KnowledgeAuditService audit) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Never join a caller transaction that may have begun before embedding;
        // the claim-expiry predicate must observe a fresh transaction clock.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.audit = audit;
    }

    /** Compatibility constructor for transaction-focused integration tests. */
    public SpringAiEmbeddingIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PlatformTransactionManager transactionManager) {
        this(vectorStore, jdbcTemplate, embeddingModel, transactionManager, null);
    }

    /** Compatibility constructor for narrow unit tests; Spring uses the transactional constructor. */
    public SpringAiEmbeddingIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.transactionTemplate = null;
        this.audit = null;
    }

    @Override
    public int index(List<String> chunks, Map<String, Object> metadata) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        // Trusted article writers historically called the generic index seam.
        // Do not let that mutable metadata become provenance authority: rebuild
        // from the current immutable revision instead of indexing caller text.
        String articleId = metadataValue(metadata, "article_id");
        if (articleId != null && metadataValue(metadata, "job_id") == null) {
            String requestedRevision = metadataValue(metadata, "article_revision_id");
            return requestedRevision == null
                    ? replaceCurrentArticleRevision(articleId)
                    : replaceForArticleRevision(articleId, requestedRevision);
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

    /**
     * Replaces one immutable source atomically. Stable UUIDv3 document ids turn a
     * replay of the same source revision into pgvector upserts rather than duplicates.
     */
    @Override
    @Transactional
    public int replaceBySource(String sourceKey, List<String> chunks, Map<String, Object> metadata) {
        String validated = requireSourceKey(sourceKey);
        if (validated.startsWith(ARTICLE_SOURCE_PREFIX)) {
            throw new IllegalArgumentException(
                    "article source replacement must use replaceForArticleRevision");
        }
        acquireSourceLock(validated);
        return replaceLocked(validated, "source", chunks, metadata, null, null);
    }

    /**
     * Reindexes a governed article from database-owned evidence only.  Evidence
     * is read before embedding, then read again under the final governance lock;
     * a concurrent approval, rollback, expiry, or supersession therefore cannot
     * delete/rewrite a different current revision's chunks.
     */
    @Override
    public int replaceForArticleRevision(String articleId, String articleRevisionId) {
        return replaceForArticleRevision(articleId, articleRevisionId, null);
    }

    /**
     * Preferred lifecycle/outbox entry point.  The durable governance state
     * version is part of the final fence, not merely advisory request metadata.
     */
    @Override
    public int replaceForArticleRevision(String articleId, String articleRevisionId,
                                         long governanceStateVersion) {
        if (governanceStateVersion < 0) {
            throw new IllegalArgumentException("governanceStateVersion must not be negative");
        }
        return replaceForArticleRevision(articleId, articleRevisionId, Long.valueOf(governanceStateVersion));
    }

    private int replaceForArticleRevision(String articleId, String articleRevisionId,
                                          Long expectedGovernanceStateVersion) {
        String normalizedArticleId = requireArticleId(articleId);
        String normalizedRevisionId = requireArticleRevisionId(articleRevisionId);
        List<ArticleIndexEvidence> expected = currentArticleEvidence(
                normalizedArticleId, normalizedRevisionId, expectedGovernanceStateVersion, false);
        if (expected.isEmpty()) {
            throw new StaleArticleRevisionException(
                    "article revision is not current and approved: " + normalizedArticleId);
        }
        List<ArticleIndexEvidence> indexable = expected.stream()
                .filter(evidence -> evidence.content() != null && !evidence.content().isBlank())
                .toList();
        List<float[]> embeddings = indexable.isEmpty() ? List.of()
                : embeddingModel.embed(indexable.stream().map(ArticleIndexEvidence::content).toList());
        if (embeddings == null || embeddings.size() != indexable.size()) {
            throw new IllegalStateException("embedding response did not match article evidence passages");
        }
        if (transactionTemplate == null) {
            // Narrow unit tests use this constructor.  Production enters the
            // final section through a new transaction below.
            return replaceForCurrentArticleRevisionLocked(normalizedArticleId, normalizedRevisionId,
                    expected, indexable, embeddings);
        }
        Integer indexed = transactionTemplate.execute(status ->
                replaceForCurrentArticleRevisionLocked(normalizedArticleId, normalizedRevisionId,
                        expected, indexable, embeddings));
        if (indexed == null) {
            throw new StaleArticleRevisionException(
                    "article revision replacement transaction did not complete: " + normalizedArticleId);
        }
        return indexed;
    }

    /**
     * Fenced job replacement. The durable worker owns generation advancement; this
     * method verifies it under a row lock before it can remove/index any passages.
     */
    @Override
    @Transactional
    public int replaceForJob(String jobId, long generation, List<String> chunks, Map<String, Object> metadata) {
        throw new StaleIndexGenerationException(
                "durable job replacement requires the persisted claim token");
    }

    /**
     * Claim-fenced job replacement. Embeddings are calculated before the durable
     * row lock; immediately before the destructive write we lock and validate the
     * current generation, lease token, lease expiry, and deletion/cancellation state.
     */
    @Override
    public int replaceForJob(String jobId, long generation, String claimToken,
                             List<String> chunks, Map<String, Object> metadata) {
        return replaceForLiveClaimInFinalTransaction(jobId, generation, claimToken, chunks, metadata);
    }

    /**
     * Explicit durable-worker entry point.  It is concrete rather than an
     * interface default so the final transaction cannot be bypassed by a
     * self-invocation through {@code this}.
     */
    @Override
    public int replaceForClaim(String jobId, long generation, String claimToken,
                               List<String> chunks, Map<String, Object> metadata) {
        return replaceForLiveClaimInFinalTransaction(jobId, generation, claimToken, chunks, metadata);
    }

    /**
     * Embeds before opening the short write transaction.  PostgreSQL {@code now()}
     * is transaction-start time, so keeping the remote call outside is necessary
     * for an expired lease to be rejected at the actual commit fence.
     */
    private int replaceForLiveClaimInFinalTransaction(String jobId, long generation, String claimToken,
                                                       List<String> chunks, Map<String, Object> metadata) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (claimToken == null || claimToken.isBlank()) {
            throw new StaleIndexGenerationException("claim token is required");
        }
        // This can call a remote embedding provider, so deliberately do it before
        // taking the durable job row lock. A fresh fence is checked below at commit.
        List<String> embeddingTexts = nonBlankChunks(chunks);
        List<float[]> embeddings = embeddingTexts.isEmpty() ? List.of() : embeddingModel.embed(embeddingTexts);
        if (transactionTemplate == null) {
            // The compatibility constructor is only used by isolated unit tests.
            // Production uses the constructor above and always executes this
            // section in a new JDBC transaction.
            return replaceForLiveClaimLocked(jobId, generation, claimToken, chunks, metadata, embeddings);
        }
        Integer indexed = transactionTemplate.execute(status ->
                replaceForLiveClaimLocked(jobId, generation, claimToken, chunks, metadata, embeddings));
        if (indexed == null) {
            throw new StaleIndexGenerationException("claim transaction did not complete for job " + jobId);
        }
        return indexed;
    }

    private int replaceForLiveClaimLocked(String jobId, long generation, String claimToken,
                                          List<String> chunks, Map<String, Object> metadata,
                                          List<float[]> embeddings) {
        List<JobIndexState> states = jdbcTemplate.query("""
                        select source_key, index_generation
                        from ingest_jobs
                        where id = ?
                          and index_generation = ?
                          and claim_token = ?
                          and claim_until > now()
                          and deleted_at is null
                          and cancelled_at is null
                        for update
                        """, (rs, row) -> new JobIndexState(rs.getString("source_key"),
                rs.getLong("index_generation")), jobId, generation, claimToken);
        if (states.isEmpty()) {
            throw new StaleIndexGenerationException("job no longer exists: " + jobId);
        }
        JobIndexState state = states.get(0);
        if (state.generation() != generation) {
            throw new StaleIndexGenerationException("stale, expired, or tombstoned claim for job " + jobId);
        }
        String sourceKey = state.sourceKey();
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new StaleIndexGenerationException("durable job has no immutable source key: " + jobId);
        }
        sourceKey = requireSourceKey(sourceKey);
        acquireSourceLock(sourceKey);
        List<Document> docs = replacementDocuments(sourceKey, Long.toString(generation), chunks,
                metadata, jobId, generation);
        if (docs.size() != embeddings.size()) {
            throw new IllegalStateException("embedding response did not match indexed passages");
        }
        jdbcTemplate.update("""
                        delete from vector_store
                        where metadata->>'source_key' = ?
                        """, sourceKey);
        if (docs.isEmpty()) {
            return 0;
        }
        upsertPrepared(docs, embeddings);
        return docs.size();
    }

    private int replaceForCurrentArticleRevisionLocked(String articleId, String revisionId,
                                                       List<ArticleIndexEvidence> expected,
                                                       List<ArticleIndexEvidence> indexable,
                                                       List<float[]> embeddings) {
        acquireSourceLock(articleSourceKey(articleId));
        long governanceStateVersion = expected.get(0).governanceStateVersion();
        List<ArticleIndexEvidence> current = currentArticleEvidence(articleId, revisionId,
                governanceStateVersion, true);
        if (!expected.equals(current)) {
            throw new StaleArticleRevisionException(
                    "article revision changed before index commit: " + articleId);
        }
        List<Document> docs = articleRevisionDocuments(articleId, revisionId, indexable);
        if (docs.size() != embeddings.size()) {
            throw new IllegalStateException("article evidence documents did not match embeddings");
        }
        // Never delete durable upload passages merely because untrusted metadata
        // happens to carry an article id.  The lifecycle source namespace is
        // exact and immutable: article:<articleId> only.
        jdbcTemplate.update("""
                        delete from vector_store
                        where metadata->>'source_key' = ?
                        """, articleSourceKey(articleId));
        if (docs.isEmpty()) {
            return 0;
        }
        upsertPrepared(docs, embeddings);
        return docs.size();
    }

    private int replaceCurrentArticleRevision(String articleId) {
        String normalizedArticleId = requireArticleId(articleId);
        List<String> revisions = jdbcTemplate.query("""
                        select ag.current_revision_id
                        from article_governance ag
                        join article_revisions ar on ar.id = ag.current_revision_id
                          and ar.article_id = ag.article_id
                          and ar.workspace_id = ag.workspace_id
                          and ar.classification = ag.classification
                        join article_revision_workflow_states ws
                          on ws.article_revision_id = ar.id
                         and ws.article_id = ar.article_id
                         and ws.workspace_id = ar.workspace_id
                         and ws.classification = ar.classification
                         and ws.approval_status = 'approved'
                         and ws.lifecycle_status = 'active'
                         and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                        where ag.article_id = ?
                          and ag.approval_status = 'approved'
                          and ag.lifecycle_status = 'active'
                          and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                        """, (rs, row) -> rs.getString(1), normalizedArticleId);
        if (revisions.isEmpty() || revisions.get(0) == null || revisions.get(0).isBlank()) {
            throw new StaleArticleRevisionException(
                    "article has no current approved revision: " + normalizedArticleId);
        }
        return replaceForArticleRevision(normalizedArticleId, revisions.get(0));
    }

    private List<ArticleIndexEvidence> currentArticleEvidence(String articleId, String revisionId,
                                                               Long expectedGovernanceStateVersion,
                                                               boolean lockForCommit) {
        try {
            String lockClause = lockForCommit ? " for update of ag, ar, ws" : "";
            String stateVersionPredicate = expectedGovernanceStateVersion == null ? ""
                    : " and ag.state_version = ?";
            String sql = ("""
                            select ar.article_id, ar.id as article_revision_id, ar.revision_number,
                                   ws.approval_status as revision_status, es.id as evidence_snapshot_id,
                                   es.ordinal, es.source_uri, es.source_version, es.content,
                                   char_length(es.content) as evidence_length,
                                   ag.state_version as governance_state_version
                            from article_governance ag
                            join article_revisions ar on ar.id = ag.current_revision_id
                              and ar.article_id = ag.article_id
                              and ar.workspace_id = ag.workspace_id
                              and ar.classification = ag.classification
                            join article_revision_workflow_states ws
                              on ws.article_revision_id = ar.id
                             and ws.article_id = ar.article_id
                             and ws.workspace_id = ar.workspace_id
                             and ws.classification = ar.classification
                             and ws.approval_status = 'approved'
                             and ws.lifecycle_status = 'active'
                             and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                            join article_evidence_snapshots es on es.article_revision_id = ar.id
                            where ag.article_id = ?
                              and ag.current_revision_id = ?
                              and ag.approval_status = 'approved'
                              and ag.lifecycle_status = 'active'
                              and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                            order by es.ordinal asc
                            """.replace("order by es.ordinal asc", stateVersionPredicate + " order by es.ordinal asc")
                    + lockClause);
            List<Object> args = new ArrayList<>();
            args.add(articleId);
            args.add(revisionId);
            if (expectedGovernanceStateVersion != null) {
                args.add(expectedGovernanceStateVersion);
            }
            return jdbcTemplate.query(sql,
                    (rs, row) -> new ArticleIndexEvidence(
                            rs.getString("article_id"), rs.getString("article_revision_id"),
                            rs.getInt("revision_number"), rs.getString("revision_status"),
                            rs.getString("evidence_snapshot_id"), rs.getInt("ordinal"),
                            rs.getString("source_uri"), rs.getString("source_version"),
                            rs.getString("content"), rs.getInt("evidence_length"),
                            rs.getLong("governance_state_version")), args.toArray());
        } catch (Exception ignored) {
            // Indexing must not treat a schema/read failure as permission to
            // replace article chunks.
            return List.of();
        }
    }

    private static List<Document> articleRevisionDocuments(String articleId, String revisionId,
                                                           List<ArticleIndexEvidence> evidenceRows) {
        String sourceKey = articleSourceKey(articleId);
        List<Document> docs = new ArrayList<>(evidenceRows.size());
        for (ArticleIndexEvidence evidence : evidenceRows) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("article_id", articleId);
            metadata.put("article_revision_id", revisionId);
            metadata.put("revision_id", revisionId);
            metadata.put("article_revision_number", evidence.revisionNumber());
            metadata.put("revision_status", evidence.revisionStatus());
            metadata.put("evidence_snapshot_id", evidence.evidenceSnapshotId());
            metadata.put("source_span_start", 0);
            metadata.put("source_span_end", evidence.evidenceLength());
            // Spring AI documents reject null metadata values.  An omitted
            // nullable source field remains exact: governed retrieval compares
            // it with PostgreSQL's IS NOT DISTINCT FROM against the immutable
            // evidence value, so null evidence cannot be forged into a URI.
            putIfNotNull(metadata, "source_uri", evidence.sourceUri());
            putIfNotNull(metadata, "source_version", evidence.sourceVersion());
            metadata.put("source_key", sourceKey);
            metadata.put("chunk_index", evidence.ordinal());
            String idInput = sourceKey + '\0' + revisionId + '\0' + evidence.evidenceSnapshotId();
            String id = UUID.nameUUIDFromBytes(idInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            docs.add(Document.builder().id(id).text(evidence.content()).metadata(metadata).build());
        }
        return docs;
    }

    @Override
    @Transactional
    public int deleteBySource(String sourceKey) {
        String validated = requireSourceKey(sourceKey);
        acquireSourceLock(validated);
        return jdbcTemplate.update("delete from vector_store where metadata->>'source_key' = ?", validated);
    }

    @Override
    public List<RetrievedChunk> search(
            String query, int topK, double minScore, String userSub) {
        return hybridSearch(query, topK, minScore, userSub);
    }

    @Override
    public List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub) {
        return hybridSearch(query, topK, minScore, userSub, null);
    }

    @Override
    public List<RetrievedChunk> hybridSearch(
            KnowledgeAccessContext context, String query, int topK, double minScore,
            RetrievalFilters filters) {
        // Only the subject crosses this boundary.  The SQL below resolves every
        // workspace role from workspace_memberships rather than trusting a
        // caller-provided role map.
        return hybridSearch(query, topK, minScore, context == null ? null : context.subject(), filters);
    }

    @Override
    public List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub, RetrievalFilters filters) {
        if (query == null || query.isBlank() || topK < 1) {
            return List.of();
        }
        String subject = normalizeSubject(userSub);
        // Public is a classification, not an anonymous authorization grant.
        // Avoid invoking either embedding provider or SQL for an unauthenticated
        // caller; explicitly authenticated fixture contexts use a nonblank
        // server-owned subject and continue through the governed path.
        if (subject.isEmpty()) {
            return List.of();
        }
        String category = filters == null || filters.category() == null || filters.category().isBlank()
                ? null : filters.category().trim();
        Integer minSeverityRank = filters == null || filters.minSeverity() == null
                || filters.minSeverity().isBlank() ? null : RetrievalFilters.severityRank(filters.minSeverity());
        int candidateLimit = topK > 100 ? 200 : Math.max(1, topK * 2);

        List<HybridRanker.LegRow> vectorLeg;
        try {
            float[] vector = embeddingModel.embed(query);
            String literal = toVectorLiteral(vector);
            vectorLeg = jdbcTemplate.query("""
                            with request_context as (select cast(? as text) as subject)
                            select vs.id::text, vs.content, %s as metadata,
                                   1 - (vs.embedding OPERATOR(extensions.<=>)
                                        ?::extensions.vector) as score
                            %s
                            where 1 - (vs.embedding OPERATOR(extensions.<=>)
                                       ?::extensions.vector) >= ?
                              and (%s or %s)
                              and (cast(? as text) is null or a.category_id = cast(? as text))
                              and (cast(? as int) is null
                                   or (case a.severity when 'critical' then 4 when 'high' then 3
                                        when 'medium' then 2 when 'low' then 1 else 0 end)
                                       >= cast(? as int))
                            order by vs.embedding OPERATOR(extensions.<=>)
                                     ?::extensions.vector
                            limit ?""".formatted(governedMetadataJson(), governedFromClause(),
                    visibleArticlePredicate(), visibleJobPredicate()),
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), false),
                    subject, literal, literal, minScore, category, category,
                    minSeverityRank, minSeverityRank, literal, candidateLimit);
        } catch (Exception e) {
            // A vector-leg outage must not weaken authorization; lexical retrieval
            // below repeats the exact same predicate and may still answer.
            vectorLeg = List.of();
        }

        List<HybridRanker.LegRow> ftsLeg = List.of();
        try {
            ftsLeg = jdbcTemplate.query("""
                            with request_context as (select cast(? as text) as subject),
                            query_terms as (select websearch_to_tsquery('simple', ?) as q)
                            select vs.id::text, vs.content, %s as metadata,
                                   ts_rank(vs.content_tsv, query_terms.q) as score
                            %s
                            cross join query_terms
                            where vs.content_tsv @@ query_terms.q
                              and (%s or %s)
                              and (cast(? as text) is null or a.category_id = cast(? as text))
                              and (cast(? as int) is null
                                   or (case a.severity when 'critical' then 4 when 'high' then 3
                                        when 'medium' then 2 when 'low' then 1 else 0 end)
                                       >= cast(? as int))
                            order by ts_rank(vs.content_tsv, query_terms.q) desc
                            limit ?""".formatted(governedMetadataJson(), governedFromClause(),
                    visibleArticlePredicate(), visibleJobPredicate()),
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), true),
                    subject, query, category, category, minSeverityRank, minSeverityRank, candidateLimit);
        } catch (Exception ignored) {
            // A query/schema failure returns no lexical rows rather than exposing
            // a partly authorized fallback.
        }

        List<RetrievedChunk> out = new ArrayList<>();
        for (HybridRanker.LegRow row : HybridRanker.merge(vectorLeg, ftsLeg, topK)) {
            out.add(new RetrievedChunk(row.content(), row.score(), parseMetadata(row.metadataJson())));
        }
        recordRetrievalAudit(subject, vectorLeg.size(), ftsLeg.size(), out.size());
        return out;
    }

    /**
     * The joins intentionally turn a visible article into a non-null revision
     * row.  A chunk with an article id but no current approved revision cannot
     * pass the outer predicate and its forged evidence metadata is stripped.
     */
    private static String governedFromClause() {
        return """
                from vector_store vs
                cross join request_context ctx
                left join article_governance ag
                  on jsonb_exists(coalesce(vs.metadata, '{}'::jsonb), 'article_id')
                 and not jsonb_exists(coalesce(vs.metadata, '{}'::jsonb), 'job_id')
                 and ag.article_id = vs.metadata->>'article_id'
                 and vs.metadata->>'source_key' = 'article:' || ag.article_id
                 and vs.metadata->>'article_revision_id' = ag.current_revision_id
                 and ag.approval_status = 'approved'
                 and ag.lifecycle_status = 'active'
                 and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                 and (
                       (ag.read_policy = 'public' and ag.classification = 'public' and ctx.subject <> '')
                    or (ag.read_policy = 'workspace_member' and ag.classification = 'internal' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true))
                    or (ag.read_policy = 'workspace_editor' and ag.classification = 'confidential' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true and wm.role in ('owner', 'admin', 'editor')))
                    or (ag.read_policy = 'workspace_admin' and ag.classification = 'restricted' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true and wm.role in ('owner', 'admin')))
                 )
                left join article_revisions ar
                  on ar.id = ag.current_revision_id
                 and ar.article_id = ag.article_id
                 and ar.workspace_id = ag.workspace_id
                 and ar.classification = ag.classification
                 and vs.metadata->>'revision_id' = ar.id
                 and (vs.metadata->>'article_revision_number') ~ '^[1-9][0-9]*$'
                 and (vs.metadata->>'article_revision_number')::integer = ar.revision_number
                left join article_revision_workflow_states ws
                  on ws.article_revision_id = ar.id
                 and ws.article_id = ar.article_id
                 and ws.workspace_id = ar.workspace_id
                 and ws.classification = ar.classification
                 and ws.approval_status = 'approved'
                 and ws.lifecycle_status = 'active'
                 and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                 and vs.metadata->>'revision_status' = ws.approval_status
                left join articles a on a.id = ag.article_id
                left join article_evidence_snapshots evidence
                  on evidence.id = vs.metadata->>'evidence_snapshot_id'
                 and evidence.article_revision_id = ar.id
                 and (vs.metadata->>'source_span_start') ~ '^[0-9]+$'
                 and (vs.metadata->>'source_span_end') ~ '^[0-9]+$'
                 and (vs.metadata->>'source_span_start')::integer <
                     (vs.metadata->>'source_span_end')::integer
                 and (vs.metadata->>'source_span_end')::integer <= char_length(evidence.content)
                 and vs.content = substring(evidence.content
                     from (vs.metadata->>'source_span_start')::integer + 1
                     for (vs.metadata->>'source_span_end')::integer
                         - (vs.metadata->>'source_span_start')::integer)
                 and vs.metadata->>'source_uri' is not distinct from evidence.source_uri
                 and vs.metadata->>'source_version' is not distinct from evidence.source_version
                """;
    }

    /** Approved current article revision, resolved through the governed outer joins. */
    private static String visibleArticlePredicate() {
        return "ar.id is not null and ws.article_revision_id is not null and evidence.id is not null";
    }

    /**
     * Upload visibility uses only durable job facts.  In particular, JSON
     * metadata never grants sharing: owner, scope, classification, membership,
     * active indexed state, and tombstone state all come from ingest_jobs.
     */
    private static String visibleJobPredicate() {
        return """
                exists (
                    select 1
                    from ingest_jobs ij
                    where jsonb_exists(coalesce(vs.metadata, '{}'::jsonb), 'job_id')
                      and ij.id = vs.metadata->>'job_id'
                      and ij.deleted_at is null
                      and ij.cancelled_at is null
                      and ij.stage = 'indexed'
                      and (ij.source_key is null or vs.metadata->>'source_key' = ij.source_key)
                      and (
                           (ij.access_scope = 'owner_private'
                            and ctx.subject <> '' and ij.owner_sub = ctx.subject)
                        or (ij.access_scope = 'public_shared'
                            and ij.classification = 'public' and ctx.subject <> '')
                        or (ij.access_scope = 'workspace' and exists (
                              select 1 from workspace_memberships wm
                              where wm.workspace_id = ij.workspace_id and wm.subject = ctx.subject
                                and wm.active = true
                                and (
                                     ij.classification in ('public', 'internal')
                                  or (ij.classification = 'confidential'
                                      and wm.role in ('owner', 'admin', 'editor'))
                                  or (ij.classification = 'restricted'
                                      and wm.role in ('owner', 'admin'))
                                )
                            ))
                      )
                )
                """;
    }

    /** Replaces potentially stale/forged article provenance with DB-backed current evidence. */
    private static String governedMetadataJson() {
        return """
                jsonb_strip_nulls(
                    coalesce(vs.metadata, '{}'::jsonb) || jsonb_build_object(
                        'article_revision_id', ar.id,
                        'revision_id', ar.id,
                        'article_revision_number', ar.revision_number,
                        'evidence_snapshot_id', evidence.id,
                        'source_span_start', case when evidence.id is null then null
                            else (vs.metadata->>'source_span_start')::integer end,
                        'source_span_end', case when evidence.id is null then null
                            else (vs.metadata->>'source_span_end')::integer end,
                        'source_uri', evidence.source_uri,
                        'source_version', evidence.source_version,
                        'revision_status', ws.approval_status
                    )
                )::text
                """;
    }

    private void recordRetrievalAudit(String subject, int vectorRows, int lexicalRows, int resultRows) {
        if (audit == null) {
            return;
        }
        audit.record(new KnowledgeAuditEvent(subject, "VECTOR_RETRIEVAL", "retrieval", "hybrid",
                null, null, null, "allowed", Map.of(
                "vectorCandidates", vectorRows,
                "lexicalCandidates", lexicalRows,
                "returned", resultRows)));
    }

    private static String normalizeSubject(String subject) {
        return subject == null ? "" : subject.trim();
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
        // Include legacy metadata.job_id rows and new source-keyed rows during rollout.
        return jdbcTemplate.update(
                "delete from vector_store where metadata->>'job_id' = ? or metadata->>'source_key' = ?",
                jobId, "job:" + jobId);
    }

    private int replaceLocked(String sourceKey, String generation, List<String> chunks,
                              Map<String, Object> metadata, String jobId, Long jobGeneration) {
        List<Document> docs = replacementDocuments(sourceKey, generation, chunks, metadata, jobId, jobGeneration);
        int removed = jdbcTemplate.update("delete from vector_store where metadata->>'source_key' = ?", sourceKey);
        if (docs.isEmpty()) {
            return 0;
        }
        vectorStore.add(docs);
        return docs.size();
    }

    private List<Document> replacementDocuments(String sourceKey, String generation, List<String> chunks,
                                                Map<String, Object> metadata, String jobId, Long jobGeneration) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> md = trustedReplacementMetadata(metadata);
            md.put("source_key", sourceKey);
            md.put("chunk_index", i);
            if (jobId != null) {
                md.put("job_id", jobId);
            }
            if (jobGeneration != null) {
                md.put("index_generation", jobGeneration);
            }
            String idInput = sourceKey + '\0' + generation + '\0' + i;
            String id = UUID.nameUUIDFromBytes(idInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            docs.add(Document.builder().id(id).text(text).metadata(md).build());
        }
        return docs;
    }

    private Map<String, Object> trustedReplacementMetadata(Map<String, Object> metadata) {
        Map<String, Object> trusted = new HashMap<>();
        if (metadata == null) {
            return trusted;
        }
        metadata.forEach((key, value) -> {
            if (key != null && !RESERVED_REPLACEMENT_METADATA.contains(key)) {
                trusted.put(key, value);
            }
        });
        return trusted;
    }

    private void acquireSourceLock(String sourceKey) {
        // Transaction-scoped advisory locks coordinate workers across JVM instances.
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null, sourceKey);
    }

    private void upsertPrepared(List<Document> docs, List<float[]> embeddings) {
        jdbcTemplate.batchUpdate("""
                        insert into vector_store (id, content, metadata, embedding)
                        values (?, ?, cast(? as jsonb), ?)
                        on conflict (id) do update
                        set content = excluded.content,
                            metadata = excluded.metadata,
                            embedding = excluded.embedding
                        """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Document document = docs.get(index);
                statement.setObject(1, UUID.fromString(document.getId()));
                statement.setString(2, document.getText());
                statement.setString(3, metadataJson(document.getMetadata()));
                statement.setObject(4, new PGvector(embeddings.get(index)));
            }

            @Override
            public int getBatchSize() {
                return docs.size();
            }
        });
    }

    private String metadataJson(Map<String, Object> metadata) {
        try {
            return mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not serialize vector metadata", e);
        }
    }

    private static List<String> nonBlankChunks(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream().filter(text -> text != null && !text.isBlank()).toList();
    }

    private static String requireSourceKey(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey is required");
        }
        return sourceKey.trim();
    }

    private static String requireArticleId(String articleId) {
        if (articleId == null || articleId.isBlank()) {
            throw new IllegalArgumentException("articleId is required");
        }
        return articleId.trim();
    }

    private static String requireArticleRevisionId(String articleRevisionId) {
        if (articleRevisionId == null || articleRevisionId.isBlank()) {
            throw new IllegalArgumentException("articleRevisionId is required");
        }
        return articleRevisionId.trim();
    }

    private static String articleSourceKey(String articleId) {
        return ARTICLE_SOURCE_PREFIX + articleId;
    }

    /**
     * Compatibility helper for call sites that already carry the immutable
     * revision id.  Article replacement deliberately has one stable source
     * key across revisions, so the revision is a fence input, never part of
     * the deletion key.
     */
    private static String articleSourceKey(String articleId, String articleRevisionId) {
        requireArticleRevisionId(articleRevisionId);
        return articleSourceKey(articleId);
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private record JobIndexState(String sourceKey, long generation) {
    }

    private record ArticleIndexEvidence(String articleId, String articleRevisionId,
                                        int revisionNumber, String revisionStatus,
                                        String evidenceSnapshotId, int ordinal,
                                        String sourceUri, String sourceVersion,
                                        String content, int evidenceLength,
                                        long governanceStateVersion) {
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
