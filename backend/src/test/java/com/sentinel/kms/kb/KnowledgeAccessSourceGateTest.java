package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.common.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Upload chunks are governed by immutable persisted source identity, not metadata. */
@ExtendWith(MockitoExtension.class)
class KnowledgeAccessSourceGateTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;

    @Test
    void rejectsForgedOrIncompleteSourceIdentityBeforeAnyLookup() {
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);

        AccessDecision decision = service.canReadSource(
                new KnowledgeAccessContext("reader", Map.of()), "source-1", "revision-1", "forged-key");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("SOURCE_IDENTITY_MISMATCH");
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void permitsOnlyTheDatabaseScopedPublicTupleAndRequiresACompletedIndex() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("workspace_id")).thenReturn("shared");
        when(resultSet.getString("access_scope")).thenReturn("public_shared");
        when(resultSet.getString("classification")).thenReturn("public");
        when(resultSet.getString("owner_sub")).thenReturn("someone-else");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);

        AccessDecision decision = service.canReadSource(
                new KnowledgeAccessContext("reader", Map.of()), "source-1", "revision-1", "source-1:revision-1");

        assertThat(decision.allowed()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("source_id = ?", "source_revision_id = ?", "source_key = ?",
                "source_key = source_id || ':' || source_revision_id", "stage = 'indexed'",
                "deleted_at is null", "cancelled_at is null", "public.can_read_ingest_source");
    }

    @Test
    void confidentialWorkspaceTupleDeniesAViewerEvenWhenAnotherOwnerIsPresent() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("workspace_id")).thenReturn("workspace-1");
        when(resultSet.getString("access_scope")).thenReturn("workspace");
        when(resultSet.getString("classification")).thenReturn("confidential");
        when(resultSet.getString("owner_sub")).thenReturn("someone-else");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);

        AccessDecision decision = service.canReadSource(
                new KnowledgeAccessContext("viewer", Map.of("workspace-1", WorkspaceRole.VIEWER)),
                "source-1", "revision-1", "source-1:revision-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("SOURCE_ROLE_REQUIRED");
    }
}
