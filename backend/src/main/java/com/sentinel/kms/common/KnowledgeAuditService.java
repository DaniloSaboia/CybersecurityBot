package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditMetadata;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.audit.AuditRedaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** Writes redacted governance/access events without turning an audit outage into a data disclosure. */
@Service
public class KnowledgeAuditService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate independentTransaction;

    /** Compatibility constructor for direct callers and focused tests. */
    public KnowledgeAuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, (TransactionTemplate) null);
    }

    /**
     * Production constructor. Terminal events use an independent transaction;
     * successful events wait for the caller's commit so a rolled-back write is
     * never recorded as a successful mutation.
     */
    @Autowired
    public KnowledgeAuditService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, mapper, requiresNew(transactionManager));
    }

    private KnowledgeAuditService(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate independentTransaction) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.independentTransaction = independentTransaction;
    }

    public void record(KnowledgeAuditEvent event) {
        if (event == null) {
            return;
        }
        String actor = AuditRedaction.actor(event.actorSub());
        String action = AuditRedaction.action(event.action());
        String resourceType = AuditRedaction.resourceType(event.resourceType());
        String resourceId = AuditRedaction.identifier(event.resourceId());
        String workspaceId = AuditRedaction.nullableIdentifier(event.workspaceId());
        String articleId = AuditRedaction.nullableIdentifier(event.articleId());
        String revisionId = AuditRedaction.nullableIdentifier(event.articleRevisionId());
        String outcome = AuditOutcome.fromWire(event.outcome()).wireValue();
        Map<String, Object> metadata = AuditMetadata.sanitize(event.metadata());
        PersistableAuditEvent safeEvent = new PersistableAuditEvent(
                actor, action, resourceType, resourceId, workspaceId, articleId, revisionId, outcome, metadata);

        if (deferSuccessfulEventUntilCommit(safeEvent)) {
            return;
        }
        persistIndependently(safeEvent);
    }

    private boolean deferSuccessfulEventUntilCommit(PersistableAuditEvent event) {
        if (independentTransaction == null
                || !isSuccessful(event.outcome())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                persistIndependently(event);
            }
        });
        return true;
    }

    private void persistIndependently(PersistableAuditEvent event) {
        if (independentTransaction == null) {
            persistSafely(event);
            return;
        }
        try {
            independentTransaction.executeWithoutResult(status -> persistSafely(event));
        } catch (Exception ignored) {
            // A transaction-manager outage must remain best-effort and reveal
            // neither event fields nor the underlying exception text.
            log.warn("Knowledge audit persistence failed: action={}, outcome={}", event.action(), event.outcome());
        }
    }

    private void persistSafely(PersistableAuditEvent event) {
        try {
            jdbc.update("""
                            insert into knowledge_audit_events
                                (id, actor_sub, action, resource_type, resource_id, workspace_id,
                                 article_id, article_revision_id, outcome, metadata)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                            """,
                    UUID.randomUUID().toString(), event.actor(), event.action(), event.resourceType(),
                    event.resourceId(), event.workspaceId(), event.articleId(), event.revisionId(), event.outcome(),
                    json(event.metadata()));
        } catch (Exception e) {
            // Audit outages cannot disclose request data through exception logs or
            // mask the original authorization/operation result.
            log.warn("Knowledge audit persistence failed: action={}, outcome={}", event.action(), event.outcome());
        }
    }

    private static boolean isSuccessful(String outcome) {
        return AuditOutcome.SUCCESS.wireValue().equals(outcome) || AuditOutcome.ALLOWED.wireValue().equals(outcome);
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private String json(Map<String, Object> metadata) {
        try {
            return mapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record PersistableAuditEvent(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String workspaceId,
            String articleId,
            String revisionId,
            String outcome,
            Map<String, Object> metadata) {
    }

}
