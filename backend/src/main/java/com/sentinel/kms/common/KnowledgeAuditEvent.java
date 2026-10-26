package com.sentinel.kms.common;

import com.sentinel.kms.audit.AuditOutcome;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A redacted append-only audit event. Never place source content or raw prompts in metadata. */
public record KnowledgeAuditEvent(
        String actorSub,
        String action,
        String resourceType,
        String resourceId,
        String workspaceId,
        String articleId,
        String articleRevisionId,
        String outcome,
    Map<String, Object> metadata) {

    public KnowledgeAuditEvent {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        metadata = Collections.unmodifiableMap(copy);
    }

    /** Typed additive factory for lifecycle/access callers; the record constructor remains compatible. */
    public static KnowledgeAuditEvent of(
            String actor,
            String action,
            String targetKind,
            String targetId,
            String workspaceId,
            String articleId,
            String revisionId,
            AuditOutcome outcome,
            Map<String, ?> metadata) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        return new KnowledgeAuditEvent(actor, action, targetKind, targetId, workspaceId, articleId, revisionId,
                (outcome == null ? AuditOutcome.FAILURE : outcome).wireValue(), copy);
    }
}
