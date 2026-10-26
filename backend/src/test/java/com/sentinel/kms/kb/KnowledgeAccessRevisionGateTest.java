package com.sentinel.kms.kb;

import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Direct title/article gates must require an approved current immutable revision too. */
@ExtendWith(MockitoExtension.class)
class KnowledgeAccessRevisionGateTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;

    @Test
    void batchGateFailsClosedUnlessCurrentApprovedRevisionExistsAndIsUnexpired() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);

        service.readableArticleIds(new KnowledgeAccessContext("reader", Map.of()), List.of("article-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("join article_revisions", "article_revision_workflow_states",
                "ws.approval_status = 'approved'", "ag.expires_at");
    }
}
