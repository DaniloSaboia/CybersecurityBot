package com.sentinel.kms.kb;

import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Denied unknown targets must retain a resource audit without violating the audit article FK. */
@ExtendWith(MockitoExtension.class)
class KnowledgeAccessServiceAuditTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;

    @Test
    void unknownArticleUsesResourceIdButNeverForeignKeyArticleIdInDeniedAudit() {
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);

        assertThat(service.canReadArticle("reader-1", "unproven-article").allowed()).isFalse();

        ArgumentCaptor<KnowledgeAuditEvent> event = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().resourceId()).isEqualTo("unproven-article");
        assertThat(event.getValue().articleId()).isNull();
        assertThat(event.getValue().outcome()).isEqualTo("denied");
    }
}
