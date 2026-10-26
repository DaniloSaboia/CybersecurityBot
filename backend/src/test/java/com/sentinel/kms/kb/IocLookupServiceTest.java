package com.sentinel.kms.kb;

import com.sentinel.kms.assistant.IocExtractor;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IocLookupServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAccessService access;
    @Mock KnowledgeAuditService audit;
    @Mock ResultSet row;

    @Test
    void returnsOnlyAnExactCurrentEvidenceSpanForAnAuthorizedIocHit() throws Exception {
        when(row.getString("id")).thenReturn("CVE-42");
        when(row.getString("title")).thenReturn("Observed indicator");
        when(row.getString("severity")).thenReturn("high");
        when(row.getString("cvss")).thenReturn(null);
        when(row.getString("ioc_type")).thenReturn("ip");
        when(row.getString("ioc_value")).thenReturn("203.0.113.7");
        when(row.getString("revision_id")).thenReturn("rev:CVE-42:1");
        when(row.getInt("revision_number")).thenReturn(1);
        when(row.getString("revision_status")).thenReturn("approved");
        when(row.getString("evidence_snapshot_id")).thenReturn("evidence:CVE-42:1:1");
        when(row.getInt("source_span_start")).thenReturn(8);
        when(row.getInt("source_span_length")).thenReturn(11);
        when(row.getString("source_version")).thenReturn("2026-09-06");
        when(row.getString("source_uri")).thenReturn("https://example.test/CVE-42");
        AtomicReference<Throwable> mapperFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            try {
                mapper.mapRow(row, 0);
            } catch (Throwable failure) {
                mapperFailure.set(failure);
                throw failure;
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any());

        IocLookupService service = new IocLookupService(jdbc, access, audit);
        List<IocLookupService.IocHit> hits = service.lookup(
                List.of(new IocExtractor.Ioc("ip", "203.0.113.7")),
                new KnowledgeAccessContext("reader", Map.of()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(), any(), any(), any(), any());
        assertThat(mapperFailure.get()).isNull();
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.evidence()).isNotNull();
            assertThat(hit.evidence().evidenceSnapshotId()).isEqualTo("evidence:CVE-42:1:1");
            assertThat(hit.evidence().sourceSpanStart()).isEqualTo(8);
            assertThat(hit.evidence().sourceSpanEnd()).isEqualTo(19);
            assertThat(hit.evidence().sourceUri()).isEqualTo("https://example.test/CVE-42");
            assertThat(hit.evidence().sourceVersion()).isEqualTo("2026-09-06");
        });
        assertThat(sql.getValue()).contains("position(lower(ioc->>'value') in lower(e.content))");
    }
}
