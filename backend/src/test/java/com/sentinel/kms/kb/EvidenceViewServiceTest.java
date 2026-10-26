package com.sentinel.kms.kb;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.audit.AuditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvidenceViewServiceTest {

    @Mock EvidenceReferenceResolver resolver;
    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;

    private EvidenceViewService service;

    @BeforeEach
    void setUp() {
        service = new EvidenceViewService(resolver, jdbc, audit);
    }

    @Test
    void returnsOnlyTheExactImmutablePostgresCharacterSpanAfterCanonicalResolution() {
        EvidenceViewRequest request = request();
        EvidenceReference canonical = reference("revision-7", 7, "snapshot-9", 4, 7);
        when(resolver.resolveCurrentApproved(eq("analyst-1"), any(EvidenceReference.class)))
                .thenReturn(Optional.of(canonical));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(snapshotRow("vid")));

        Optional<EvidenceViewResponse> result = service.view("analyst-1", request);

        assertThat(result).contains(new EvidenceViewResponse(
                "article-1", "revision-7", 7, "snapshot-9", 4, 7,
                "Threat report", "https://example.test/report", "2026-09-01", "approved", "vid"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains("substring(es.content from ? for ?)");
        assertThat(sql.getValue()).contains("ar.id = ag.current_revision_id",
                        "article_revision_workflow_states ws", "ws.article_revision_id = ar.id",
                        "ws.approval_status = 'approved'", "ws.lifecycle_status = 'active'",
                        "ag.expires_at is null or ag.expires_at > clock_timestamp()",
                        "ws.expires_at is null or ws.expires_at > clock_timestamp()")
                .doesNotContain("ar.status = 'approved'");
        assertThat(arguments.getValue()).containsExactly(
                5, 3, "snapshot-9", "revision-7", "article-1", 7, 4, 7, 4, 7);

        ArgumentCaptor<KnowledgeAuditEvent> event = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("EVIDENCE_VIEW");
        assertThat(event.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS.wireValue());
        assertThat(event.getValue().metadata()).isEmpty();
    }

    @Test
    void rejectsAResolverResultWhoseRevisionNumberDiffersFromTheRequestBeforeLoadingContent() {
        EvidenceViewRequest request = request();
        when(resolver.resolveCurrentApproved(eq("analyst-1"), any(EvidenceReference.class)))
                .thenReturn(Optional.of(reference("revision-7", 8, "snapshot-9", 4, 7)));

        assertThat(service.view("analyst-1", request)).isEmpty();

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void returnsAbsentWithoutQueryingWhenEvidenceIsRevokedOrForged() {
        when(resolver.resolveCurrentApproved(eq("analyst-1"), any(EvidenceReference.class)))
                .thenReturn(Optional.empty());

        assertThat(service.view("analyst-1", request())).isEmpty();

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
        ArgumentCaptor<KnowledgeAuditEvent> event = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue())
                .extracting(KnowledgeAuditEvent::resourceId,
                        KnowledgeAuditEvent::workspaceId,
                        KnowledgeAuditEvent::articleId,
                        KnowledgeAuditEvent::articleRevisionId,
                        KnowledgeAuditEvent::outcome)
                .containsExactly("snapshot-9", null, null, null, AuditOutcome.DENIED.wireValue());
        assertThat(event.getValue().metadata()).isEmpty();
    }

    @Test
    void failsClosedWhenResolverThrows() {
        when(resolver.resolveCurrentApproved(eq("analyst-1"), any(EvidenceReference.class)))
                .thenThrow(new IllegalStateException("resolver unavailable"));

        assertThat(service.view("analyst-1", request())).isEmpty();

        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void rejectsIncompleteArticleIdentityRatherThanTreatingUploadOrBareArticleDataAsCitationEvidence() {
        EvidenceViewRequest incomplete = new EvidenceViewRequest(
                "article-1", null, 0, null, 0, 0);

        assertThat(service.view("analyst-1", incomplete)).isEmpty();

        verify(resolver, never()).resolveCurrentApproved(anyString(), any(EvidenceReference.class));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void rejectsAnEmptySpanRatherThanReturningAnEmptyHistoricalQuote() {
        EvidenceViewRequest emptySpan = new EvidenceViewRequest(
                "article-1", "revision-7", 7, "snapshot-9", 4, 4);

        assertThat(service.view("analyst-1", emptySpan)).isEmpty();

        verify(resolver, never()).resolveCurrentApproved(anyString(), any(EvidenceReference.class));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    private static EvidenceViewRequest request() {
        return new EvidenceViewRequest("article-1", "revision-7", 7, "snapshot-9", 4, 7);
    }

    private static EvidenceReference reference(String revisionId, int revisionNumber,
                                               String snapshotId, int start, int end) {
        return new EvidenceReference("article-1", revisionId, revisionNumber, snapshotId,
                start, end, "https://example.test/report", "2026-09-01", "approved");
    }

    private static Map<String, Object> snapshotRow(String passage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("passage", passage);
        row.put("source_label", "Threat report");
        row.put("source_uri", "https://example.test/report");
        row.put("source_version", "2026-09-01");
        return row;
    }
}
