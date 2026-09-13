package com.sentinel.kms.kb;

import com.sentinel.kms.assistant.IocExtractor;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A current-revision foreign key alone proves only that the revision exists.
 * Every reader must also bind it to the governed article, workspace, and
 * classification before a row can become visible.
 */
@ExtendWith(MockitoExtension.class)
class RevisionBindingAuthorizationTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;
    @Mock KnowledgeAccessService access;
    @Mock VectorStore vectorStore;
    @Mock EmbeddingModel embeddingModel;

    @Test
    void directAndBatchArticleGatesRejectCurrentRevisionsFromAnotherArticleWorkspaceOrClassification() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        KnowledgeAccessService service = new KnowledgeAccessService(jdbc, audit);
        KnowledgeAccessContext context = new KnowledgeAccessContext("reader", Map.of());

        service.canReadArticle(context, "article-a");
        service.readableArticleIds(context, List.of("article-a"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(this::assertRevisionBinding);
    }

    @Test
    void iocLookupRejectsAnIocWhoseCurrentRevisionBelongsToAnotherArticleWorkspaceOrClassification() {
        IocLookupService service = new IocLookupService(jdbc, access, audit);

        service.lookup(List.of(new IocExtractor.Ioc("ip", "203.0.113.7")),
                new KnowledgeAccessContext("reader", Map.of()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(), any(), any(), any(), any());
        assertRevisionBinding(sql.getValue());
    }

    @Test
    void vectorAndFtsLegsRejectAChunkWhenTheCurrentRevisionCrossesArticleWorkspaceOrClassification() {
        when(embeddingModel.embed("binding probe")).thenReturn(new float[]{1f, 0f});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        SpringAiEmbeddingIndexService service = new SpringAiEmbeddingIndexService(vectorStore, jdbc, embeddingModel);

        service.hybridSearch("binding probe", 3, 0.0d, "reader");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(this::assertRevisionBinding);
    }

    private void assertRevisionBinding(String sql) {
        assertThat(sql)
                .containsPattern("ar\\s*\\.\\s*article_id\\s*=\\s*ag\\s*\\.\\s*article_id")
                .containsPattern("ar\\s*\\.\\s*workspace_id\\s*=\\s*ag\\s*\\.\\s*workspace_id")
                .containsPattern("ar\\s*\\.\\s*classification\\s*=\\s*ag\\s*\\.\\s*classification");
    }
}
