package com.sentinel.kms.kb;

import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.StaleArticleRevisionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the lifecycle outbox.  Embedding stays inside the vectorstore's
 * pre-commit phase; this worker only claims durable work and records a retry
 * outcome. The vector replacement fence rechecks the exact article revision
 * and governance version before it can delete any existing article chunks.
 */
@Service
public class KnowledgeArticleReindexWorker {

    private final JdbcTemplate jdbc;
    private final EmbeddingIndexService index;
    private final KnowledgeAuditService audit;
    private final TransactionTemplate transaction;

    public KnowledgeArticleReindexWorker(JdbcTemplate jdbc, EmbeddingIndexService index,
                                         KnowledgeAuditService audit,
                                         PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.index = index;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${app.kb.lifecycle-reindex-poll-ms:1000}")
    public void drainScheduled() {
        // Bounded work prevents an unavailable embedding provider from
        // monopolizing the scheduler thread; failed items retain a retry state.
        for (int i = 0; i < 4; i++) {
            if (!drainOnce()) {
                return;
            }
        }
    }

    /** Public for deterministic tests and operational triggering; returns whether a row was claimed. */
    public boolean drainOnce() {
        ReindexClaim claim = transaction.execute(status -> claimNext());
        if (claim == null) {
            return false;
        }
        try {
            int indexed = index.replaceForArticleRevision(claim.articleId(), claim.revisionId(),
                    claim.governanceStateVersion());
            transaction.executeWithoutResult(status -> complete(claim));
            audit(claim, "LIFECYCLE_REINDEX_SUCCEEDED", AuditOutcome.SUCCESS,
                    Map.of("attempt", claim.attempt(), "chunk_count", indexed));
        } catch (StaleArticleRevisionException stale) {
            transaction.executeWithoutResult(status -> cancelStale(claim));
            audit(claim, "LIFECYCLE_REINDEX_STALE", AuditOutcome.CONFLICT,
                    Map.of("attempt", claim.attempt(), "failure_code", "STALE_REVISION"));
        } catch (Exception ignored) {
            transaction.executeWithoutResult(status -> failRetryably(claim));
            audit(claim, "LIFECYCLE_REINDEX_FAILED", AuditOutcome.FAILURE,
                    Map.of("attempt", claim.attempt(), "failure_code", "REINDEX_FAILED"));
        }
        return true;
    }

    private ReindexClaim claimNext() {
        List<ReindexClaim> rows = jdbc.query("""
                        select id, article_id, article_revision_id, governance_state_version, attempt
                        from article_revision_reindex_outbox
                        where state = 'pending'
                           or (state in ('claimed', 'failed') and claim_until is not null
                               and claim_until < clock_timestamp())
                        order by created_at asc
                        for update skip locked
                        limit 1
                        """, (rs, row) -> new ReindexClaim(rs.getString("id"), rs.getString("article_id"),
                rs.getString("article_revision_id"), rs.getLong("governance_state_version"),
                rs.getInt("attempt") + 1, UUID.randomUUID().toString()));
        if (rows.isEmpty()) {
            return null;
        }
        ReindexClaim claim = rows.get(0);
        int updated = jdbc.update("""
                        update article_revision_reindex_outbox
                        set state = 'claimed', attempt = ?, claim_token = ?,
                            claim_until = clock_timestamp() + interval '2 minutes', last_error = null
                        where id = ?
                        """, claim.attempt(), claim.token(), claim.id());
        return updated == 1 ? claim : null;
    }

    private void complete(ReindexClaim claim) {
        jdbc.update("""
                        update article_revision_reindex_outbox
                        set state = 'completed', completed_at = clock_timestamp(), claim_until = null
                        where id = ? and state = 'claimed' and claim_token = ?
                        """, claim.id(), claim.token());
    }

    private void cancelStale(ReindexClaim claim) {
        jdbc.update("""
                        update article_revision_reindex_outbox
                        set state = 'cancelled', claim_until = null, last_error = 'stale_revision'
                        where id = ? and state = 'claimed' and claim_token = ?
                        """, claim.id(), claim.token());
    }

    private void failRetryably(ReindexClaim claim) {
        jdbc.update("""
                        update article_revision_reindex_outbox
                        set state = 'failed', claim_until = clock_timestamp() + interval '30 seconds',
                            last_error = 'reindex_failed'
                        where id = ? and state = 'claimed' and claim_token = ?
                        """, claim.id(), claim.token());
    }

    private void audit(ReindexClaim claim, String action, AuditOutcome outcome, Map<String, ?> metadata) {
        if (audit == null) {
            return;
        }
        audit.record(KnowledgeAuditEvent.of("system:lifecycle-reindex", action, "article_revision",
                claim.revisionId(), null, claim.articleId(), claim.revisionId(), outcome, metadata));
    }

    private record ReindexClaim(String id, String articleId, String revisionId, long governanceStateVersion,
                                int attempt, String token) {
    }
}
