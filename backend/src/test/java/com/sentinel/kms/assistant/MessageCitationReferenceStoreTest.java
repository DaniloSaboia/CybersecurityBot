package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.EvidenceReferenceResolver;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageCitationReferenceStoreTest {

    private static final EvidenceReference CANDIDATE = new EvidenceReference(
            "TI-1", "revision-1", 1, "snapshot-1", 2, 8,
            "https://forged.example", "wrong", "draft");
    private static final EvidenceReference CANONICAL = new EvidenceReference(
            "TI-1", "revision-1", 7, "snapshot-1", 2, 8,
            "https://authoritative.example/report", "2026-09-06", "approved");

    @Mock JdbcTemplate jdbc;
    @Mock EvidenceReferenceResolver resolver;

    @Test
    void writesOnlyResolverCanonicalProvenanceWithADeterministicRowId() {
        when(resolver.resolveCurrentApproved("analyst-1", CANDIDATE)).thenReturn(Optional.of(CANONICAL));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        MessageCitationReferenceStore store = new MessageCitationReferenceStore(jdbc, resolver);

        List<EvidenceReference> persisted = store.bind("analyst-1", "message-1", List.of(CANDIDATE));

        assertThat(persisted).containsExactly(CANONICAL);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("insert into message_citation_references");
        assertThat(arguments.getValue()).containsExactly(
                "citation:message-1:0", "message-1", 0,
                "TI-1", "revision-1", "snapshot-1", 2, 8,
                "https://authoritative.example/report", "2026-09-06");
        assertThat(arguments.getValue()).doesNotContain("https://forged.example", "wrong", "draft");
    }

    @Test
    void rejectsAnUnresolvedRevisionOrSnapshotBeforeAnyDatabaseWrite() {
        when(resolver.resolveCurrentApproved("analyst-1", CANDIDATE)).thenReturn(Optional.empty());
        MessageCitationReferenceStore store = new MessageCitationReferenceStore(jdbc, resolver);

        assertThatThrownBy(() -> store.bind("analyst-1", "message-1", List.of(CANDIDATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current approved evidence");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void transcriptReadbackDerivesProvenanceFromImmutableEvidenceRows() throws Exception {
        ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
        when(row.getString("message_id")).thenReturn("message-1");
        when(row.getString("article_id")).thenReturn("TI-1");
        when(row.getString("article_revision_id")).thenReturn("revision-1");
        when(row.getInt("revision_number")).thenReturn(7);
        when(row.getString("evidence_snapshot_id")).thenReturn("snapshot-1");
        when(row.getInt("source_span_start")).thenReturn(2);
        when(row.getInt("source_span_end")).thenReturn(8);
        when(row.getString("source_uri")).thenReturn("https://authoritative.example/report");
        when(row.getString("source_version")).thenReturn("2026-09-06");
        when(row.getString("revision_status")).thenReturn("approved");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        MessageCitationReferenceStore store = new MessageCitationReferenceStore(jdbc, resolver);

        Map<String, List<EvidenceReference>> references = store.loadByMessageIds(List.of("message-1"));

        assertThat(references).containsEntry("message-1", List.of(CANONICAL));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("article_evidence_snapshots es", "es.source_uri", "es.source_version",
                        "article_governance ag", "ag.current_revision_id = mcr.article_revision_id",
                        "article_revision_workflow_states ws", "ws.article_revision_id = ar.id",
                        "ws.approval_status = 'approved'", "ws.lifecycle_status = 'active'",
                        "ag.expires_at is null or ag.expires_at > clock_timestamp()",
                        "ws.expires_at is null or ws.expires_at > clock_timestamp()",
                        "ws.approval_status as revision_status")
                .doesNotContain("mcr.source_uri", "mcr.source_version", "ar.status as revision_status");
    }

    @Test
    void transcriptReplayRequiresTheSameCurrentApprovedEvidenceIdentity() {
        EvidenceReference substituted = new EvidenceReference(
                "TI-1", "revision-2", 8, "snapshot-2", 2, 8,
                "https://authoritative.example/new", "2026-09-07", "approved");
        when(resolver.resolveCurrentApproved("analyst-1", CANONICAL)).thenReturn(Optional.of(CANONICAL));
        MessageCitationReferenceStore store = new MessageCitationReferenceStore(jdbc, resolver);

        assertThat(store.resolveCurrentApproved("analyst-1", List.of(CANONICAL))).containsExactly(CANONICAL);

        when(resolver.resolveCurrentApproved("analyst-1", CANDIDATE)).thenReturn(Optional.of(substituted));
        assertThatThrownBy(() -> store.resolveCurrentApproved("analyst-1", List.of(CANDIDATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different evidence identity");
    }
}
