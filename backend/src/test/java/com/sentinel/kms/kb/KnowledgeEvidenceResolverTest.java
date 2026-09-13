package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Server-side canonicalization required before a chat citation is persisted. */
@ExtendWith(MockitoExtension.class)
class KnowledgeEvidenceResolverTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAccessService access;

    @Test
    void legacyOrIncompleteCitationCannotBeResolved() {
        EvidenceReferenceResolver resolver = new KnowledgeEvidenceResolver(jdbc, access);

        assertThat(resolver.resolveCurrentApproved("reader", new EvidenceReference(
                "article-1", null, 0, null, null, null, null, null, null))).isEmpty();

        verify(access, never()).canReadArticle(anyString(), anyString());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void deniedSubjectCannotResolveEvenAWellFormedEvidenceIdentity() {
        EvidenceReference candidate = candidate();
        when(access.canReadArticle("reader", "article-1"))
                .thenReturn(AccessDecision.deny("SOURCE_MEMBERSHIP_REQUIRED", null));
        EvidenceReferenceResolver resolver = new KnowledgeEvidenceResolver(jdbc, access);

        assertThat(resolver.resolveCurrentApproved("reader", candidate)).isEmpty();

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void returnsOnlyCanonicalCurrentApprovedEvidenceAndRejectsOutOfRangeSpan() throws Exception {
        when(access.canReadArticle("reader", "article-1"))
                .thenReturn(new AccessDecision(true, "ARTICLE_PUBLIC", "shared", "PUBLIC", "APPROVED"));
        ResultSet row = mock(ResultSet.class);
        when(row.getString("article_id")).thenReturn("article-1");
        when(row.getString("article_revision_id")).thenReturn("revision-1");
        when(row.getInt("revision_number")).thenReturn(7);
        when(row.getString("evidence_snapshot_id")).thenReturn("evidence-1");
        when(row.getString("source_uri")).thenReturn("https://authoritative.example/report");
        when(row.getString("source_version")).thenReturn("2026-09-06");
        when(row.getString("revision_status")).thenReturn("approved");
        when(row.getInt("evidence_length")).thenReturn(12);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });
        EvidenceReferenceResolver resolver = new KnowledgeEvidenceResolver(jdbc, access);

        EvidenceReference resolved = resolver.resolveCurrentApproved("reader", candidate()).orElseThrow();

        assertThat(resolved).isEqualTo(new EvidenceReference("article-1", "revision-1", 7, "evidence-1",
                2, 5, "https://authoritative.example/report", "2026-09-06", "approved"));

        assertThat(resolver.resolveCurrentApproved("reader", new EvidenceReference(
                "article-1", "revision-1", 7, "evidence-1", 2, 13, null, null, null))).isEmpty();

        // A resolver must reject rather than normalize a forged core identity.
        // The mock deliberately returns a canonical row despite the input so
        // this proves the service owns the exact-tuple check itself.
        assertThat(resolver.resolveCurrentApproved("reader", new EvidenceReference(
                "article-1", "revision-1", 8, "evidence-1", 2, 5, null, null, null))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("article_governance", "article_revisions", "article_evidence_snapshots",
                "article_revision_workflow_states", "ag.current_revision_id",
                "ag.approval_status = 'approved'", "ws.approval_status = 'approved'",
                "ar.revision_number = ?");
    }

    private static EvidenceReference candidate() {
        return new EvidenceReference("article-1", "revision-1", 7, "evidence-1", 2, 5,
                "https://forged.example", "wrong", "draft");
    }
}
