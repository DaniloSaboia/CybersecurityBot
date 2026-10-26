package com.sentinel.kms.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Reads redacted audit records for a persisted owner/admin workspace membership.
 * The authorization function intentionally appears both before and inside the
 * event query so a revoked membership cannot win a check/query race.
 */
@Service
public class AuditReadService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeAuditService audit;

    public AuditReadService(JdbcTemplate jdbc, ObjectMapper mapper, KnowledgeAuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    public AuditEventPage list(String subject, String workspaceId, Integer requestedLimit, String before) {
        String actor = requireActor(subject);
        String workspace = requireWorkspace(workspaceId);
        int limit = boundedLimit(requestedLimit);
        AuditCursor.Value cursor = AuditCursor.decode(before);

        if (!canReadWorkspace(workspace, actor)) {
            throw new AccessDeniedException("workspace audit access denied");
        }

        List<AuditEventDto> rows = cursor == null
                ? readFirstPage(workspace, actor, limit + 1)
                : readAfterCursor(workspace, actor, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AuditEventDto> events = hasMore
                ? List.copyOf(rows.subList(0, limit))
                : List.copyOf(rows);
        String nextCursor = hasMore
                ? cursorFor(events.get(events.size() - 1))
                : null;

        // This follows a fully successful, database-authorized read. Its metadata
        // is intentionally scalar and whitelist-compatible with AuditMetadata.
        audit.record(KnowledgeAuditEvent.of(actor, AuditActions.AUDIT_READ, "audit_workspace", workspace,
                workspace, null, null, AuditOutcome.SUCCESS,
                Map.of(AuditMetadata.LIMIT, (long) limit,
                        AuditMetadata.RETURNED_COUNT, (long) events.size())));
        return new AuditEventPage(events, nextCursor);
    }

    private boolean canReadWorkspace(String workspaceId, String subject) {
        Boolean allowed = jdbc.queryForObject(
                "select public.can_read_workspace_audit(?, ?)", Boolean.class, workspaceId, subject);
        return Boolean.TRUE.equals(allowed);
    }

    private List<AuditEventDto> readFirstPage(String workspaceId, String subject, int fetchLimit) {
        return jdbc.query("""
                        select id, occurred_at, actor_sub, action, resource_type, resource_id,
                               workspace_id, article_id, article_revision_id, outcome,
                               metadata::text as metadata
                        from knowledge_audit_events
                        where workspace_id = ?
                          and public.can_read_workspace_audit(workspace_id, ?)
                        order by occurred_at desc, id desc
                        limit ?
                        """, this::mapRow, workspaceId, subject, fetchLimit);
    }

    private List<AuditEventDto> readAfterCursor(
            String workspaceId, String subject, AuditCursor.Value cursor, int fetchLimit) {
        Timestamp occurredAt = Timestamp.from(cursor.occurredAt());
        return jdbc.query("""
                        select id, occurred_at, actor_sub, action, resource_type, resource_id,
                               workspace_id, article_id, article_revision_id, outcome,
                               metadata::text as metadata
                        from knowledge_audit_events
                        where workspace_id = ?
                          and public.can_read_workspace_audit(workspace_id, ?)
                          and (occurred_at < ? or (occurred_at = ? and id < ?))
                        order by occurred_at desc, id desc
                        limit ?
                        """, this::mapRow, workspaceId, subject, occurredAt, occurredAt, cursor.id(), fetchLimit);
    }

    private AuditEventDto mapRow(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        Timestamp occurredAt = resultSet.getTimestamp("occurred_at");
        Instant instant = occurredAt == null ? Instant.EPOCH : occurredAt.toInstant();
        return new AuditEventDto(
                AuditRedaction.identifier(resultSet.getString("id")),
                OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
                AuditRedaction.actor(resultSet.getString("actor_sub")),
                AuditRedaction.action(resultSet.getString("action")),
                AuditRedaction.resourceType(resultSet.getString("resource_type")),
                AuditRedaction.identifier(resultSet.getString("resource_id")),
                AuditRedaction.nullableIdentifier(resultSet.getString("workspace_id")),
                AuditRedaction.nullableIdentifier(resultSet.getString("article_id")),
                AuditRedaction.nullableIdentifier(resultSet.getString("article_revision_id")),
                AuditOutcome.fromWire(resultSet.getString("outcome")).wireValue(),
                parseMetadata(resultSet.getString("metadata")));
    }

    private String cursorFor(AuditEventDto event) {
        return AuditCursor.encode(event.occurredAt().toInstant(), event.id());
    }

    private Map<String, Object> parseMetadata(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(serialized, new TypeReference<>() {
            });
            return AuditMetadata.sanitize(parsed);
        } catch (Exception ignored) {
            // Corrupt or legacy event metadata must not become a reason to reveal
            // a raw database payload, and may never be copied into logs.
            return Map.of();
        }
    }

    private static String requireActor(String subject) {
        if (!AuditRedaction.isIdentifier(subject)) {
            throw new AccessDeniedException("audit subject unavailable");
        }
        return subject;
    }

    private static String requireWorkspace(String workspaceId) {
        if (!AuditRedaction.isIdentifier(workspaceId)) {
            throw new IllegalArgumentException("Invalid workspace id");
        }
        return workspaceId;
    }

    private static int boundedLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Audit limit must be between 1 and 100");
        }
        return limit;
    }
}
