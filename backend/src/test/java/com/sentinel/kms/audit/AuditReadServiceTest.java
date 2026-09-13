package com.sentinel.kms.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Regressions for database-authorized, redacted, keyset-paged audit reads. */
@ExtendWith(MockitoExtension.class)
class AuditReadServiceTest {

    @Mock KnowledgeAuditService audit;

    private RecordingJdbcTemplate jdbc;
    private AuditReadService service;

    @BeforeEach
    void setUp() {
        jdbc = new RecordingJdbcTemplate();
        service = new AuditReadService(jdbc, new ObjectMapper(), audit);
    }

    @Test
    void authorizedWorkspaceReadIsBoundedKeysetPagedRedactedAndAuditedOnlyAfterQuery() {
        jdbc.allowed = true;
        jdbc.rows = List.of(
                row("event-2", "2026-09-06T12:00:01Z", "{\"reason_code\":\"SAFE_REASON\","
                        + "\"body\":\"AUDIT_DOCUMENT_CANARY\",\"nested\":{\"token\":\"secret\"}}"),
                row("event-1", "2026-09-06T12:00:00Z", "{\"reason_code\":\"OLDER_REASON\"}"));
        doAnswer(invocation -> {
            assertThat(jdbc.queryInvoked).isTrue();
            return null;
        }).when(audit).record(any(KnowledgeAuditEvent.class));

        AuditEventPage page = service.list("alice", "workspace-1", 1, null);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).id()).isEqualTo("event-2");
        assertThat(page.events().get(0).metadata()).containsOnly(Map.entry("reason_code", "SAFE_REASON"));
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(jdbc.authorizationSql).contains("public.can_read_workspace_audit");
        assertThat(jdbc.eventSql).contains("public.can_read_workspace_audit");
        assertThat(jdbc.eventSql).contains("order by occurred_at desc, id desc");
        assertThat(jdbc.eventArgs).containsExactly("workspace-1", "alice", 2);

        ArgumentCaptor<KnowledgeAuditEvent> event = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().actorSub()).isEqualTo("alice");
        assertThat(event.getValue().action()).isEqualTo(AuditActions.AUDIT_READ);
        assertThat(event.getValue().workspaceId()).isEqualTo("workspace-1");
        assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS.wireValue());
        assertThat(event.getValue().metadata()).containsOnly(
                Map.entry(AuditMetadata.LIMIT, 1L),
                Map.entry(AuditMetadata.RETURNED_COUNT, 1L));
    }

    @Test
    void deniedWorkspaceDoesNotRunTheEventQueryOrRecordAnAuditRead() {
        jdbc.allowed = false;

        assertThatThrownBy(() -> service.list("alice", "workspace-1", null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("workspace audit access denied");

        assertThat(jdbc.queryInvoked).isFalse();
        verify(audit, never()).record(any());
    }

    @Test
    void malformedCursorAndUnboundedLimitAreRejectedBeforeAuthorization() {
        assertThatThrownBy(() -> service.list("alice", "workspace-1", 101, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Audit limit must be between 1 and 100");
        assertThatThrownBy(() -> service.list("alice", "workspace-1", 1, "AUDIT_CURSOR_CANARY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid audit cursor");

        assertThat(jdbc.authorizationSql).isNull();
        assertThat(jdbc.queryInvoked).isFalse();
        verify(audit, never()).record(any());
    }

    @Test
    void queryFailureDoesNotCreateAReadAuditEvent() {
        jdbc.allowed = true;
        jdbc.queryFailure = new IllegalStateException("AUDIT_QUERY_SECRET_CANARY");

        assertThatThrownBy(() -> service.list("alice", "workspace-1", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUDIT_QUERY_SECRET_CANARY");

        verify(audit, never()).record(any());
    }

    @Test
    void cursorAddsAStableOccurredAtAndIdFence() {
        jdbc.allowed = true;
        String cursor = AuditCursor.encode(Instant.parse("2026-09-06T12:00:01Z"), "event-2");

        service.list("alice", "workspace-1", 50, cursor);

        assertThat(jdbc.eventSql).contains("occurred_at < ? or (occurred_at = ? and id < ?)");
        assertThat(jdbc.eventArgs).containsExactly(
                "workspace-1", "alice",
                Timestamp.from(Instant.parse("2026-09-06T12:00:01Z")),
                Timestamp.from(Instant.parse("2026-09-06T12:00:01Z")), "event-2", 51);
    }

    private static AuditRow row(String id, String occurredAt, String metadata) {
        return new AuditRow(id, Instant.parse(occurredAt), "alice", "LIFECYCLE_DRAFT_CREATED", "article",
                "article-1", "workspace-1", "article-1", "revision-1", "success", metadata);
    }

    private record AuditRow(String id, Instant occurredAt, String actor, String action, String resourceType,
                            String resourceId, String workspaceId, String articleId, String revisionId,
                            String outcome, String metadata) {
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private boolean allowed;
        private boolean queryInvoked;
        private RuntimeException queryFailure;
        private List<AuditRow> rows = List.of();
        private String authorizationSql;
        private String eventSql;
        private Object[] eventArgs;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            authorizationSql = sql;
            return requiredType.cast(allowed);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryInvoked = true;
            eventSql = sql;
            eventArgs = args.clone();
            if (queryFailure != null) {
                throw queryFailure;
            }
            List<T> mapped = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                AuditRow row = rows.get(index);
                ResultSet resultSet = mock(ResultSet.class);
                try {
                    org.mockito.Mockito.when(resultSet.getString("id")).thenReturn(row.id());
                    org.mockito.Mockito.when(resultSet.getTimestamp("occurred_at"))
                            .thenReturn(Timestamp.from(row.occurredAt()));
                    org.mockito.Mockito.when(resultSet.getString("actor_sub")).thenReturn(row.actor());
                    org.mockito.Mockito.when(resultSet.getString("action")).thenReturn(row.action());
                    org.mockito.Mockito.when(resultSet.getString("resource_type")).thenReturn(row.resourceType());
                    org.mockito.Mockito.when(resultSet.getString("resource_id")).thenReturn(row.resourceId());
                    org.mockito.Mockito.when(resultSet.getString("workspace_id")).thenReturn(row.workspaceId());
                    org.mockito.Mockito.when(resultSet.getString("article_id")).thenReturn(row.articleId());
                    org.mockito.Mockito.when(resultSet.getString("article_revision_id")).thenReturn(row.revisionId());
                    org.mockito.Mockito.when(resultSet.getString("outcome")).thenReturn(row.outcome());
                    org.mockito.Mockito.when(resultSet.getString("metadata")).thenReturn(row.metadata());
                    mapped.add(rowMapper.mapRow(resultSet, index));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            return mapped;
        }
    }
}
