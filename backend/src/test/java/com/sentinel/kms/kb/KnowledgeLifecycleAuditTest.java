package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.common.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.SupersedeRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stale request reaches the state-version fence before its supplied revision
 * can be bound to an immutable workflow row. That unproven identifier must
 * remain only an audit resource target, never an FK-backed revision column.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeLifecycleAuditTest {

    private static final String SUBJECT = "lifecycle-admin";
    private static final String ARTICLE_ID = "article-1";
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String FORGED_REVISION_ID = "forged-revision-id";

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAccessService access;
    @Mock KnowledgeAuditService audit;
    @Mock ResultSet governanceRow;

    private KnowledgeLifecycleService lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        when(governanceRow.getString("article_id")).thenReturn(ARTICLE_ID);
        when(governanceRow.getString("workspace_id")).thenReturn(WORKSPACE_ID);
        when(governanceRow.getString("classification")).thenReturn("public");
        when(governanceRow.getString("read_policy")).thenReturn("workspace");
        when(governanceRow.getString("approval_status")).thenReturn("approved");
        when(governanceRow.getString("lifecycle_status")).thenReturn("active");
        when(governanceRow.getString("current_revision_id")).thenReturn("current-revision-id");
        when(governanceRow.getLong("state_version")).thenReturn(7L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(governanceRow, 0));
        });
        when(access.contextFor(SUBJECT)).thenReturn(new KnowledgeAccessContext(SUBJECT,
                Map.of(WORKSPACE_ID, WorkspaceRole.ADMIN)));
        lifecycle = new KnowledgeLifecycleService(jdbc, new ObjectMapper(), access, audit);
    }

    @Test
    void staleApprovalWithForgedRevisionKeepsTheUnprovenIdOnlyAsAuditResource() {
        assertConflict(() -> lifecycle.approve(SUBJECT, ARTICLE_ID, FORGED_REVISION_ID,
                new TransitionRequest(6, null, null, null)));

        assertUnprovenRevisionConflictAudit();
    }

    @Test
    void staleSupersessionWithForgedCurrentRevisionKeepsTheUnprovenIdOnlyAsAuditResource() {
        assertConflict(() -> lifecycle.supersede(SUBJECT, ARTICLE_ID, FORGED_REVISION_ID,
                new SupersedeRequest(6, "successor-revision-id", null, null, null)));

        assertUnprovenRevisionConflictAudit();
    }

    private void assertConflict(ThrowingInvocation invocation) {
        assertThatThrownBy(invocation::invoke)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    private void assertUnprovenRevisionConflictAudit() {
        ArgumentCaptor<KnowledgeAuditEvent> event = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit).record(event.capture());

        assertThat(event.getValue())
                .extracting(KnowledgeAuditEvent::action,
                        KnowledgeAuditEvent::resourceType,
                        KnowledgeAuditEvent::resourceId,
                        KnowledgeAuditEvent::workspaceId,
                        KnowledgeAuditEvent::articleId,
                        KnowledgeAuditEvent::articleRevisionId,
                        KnowledgeAuditEvent::outcome)
                .containsExactly("LIFECYCLE_CONCURRENCY_CONFLICT", "article_revision", FORGED_REVISION_ID,
                        WORKSPACE_ID, ARTICLE_ID, null, AuditOutcome.CONFLICT.wireValue());
        assertThat(event.getValue().metadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "expected_state_version", 6L,
                "actual_state_version", 7L));
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void invoke();
    }
}
