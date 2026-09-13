package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustedArticleRegistrationServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock KnowledgeAuditService audit;

    @Test
    void existingGovernanceIsNeverDowngradedByTrustedSourceRefresh() {
        TrustedArticleRegistrationService service = new TrustedArticleRegistrationService(
                jdbc, new ObjectMapper(), audit);
        doReturn(0).when(jdbc).update(contains("article_governance"),
                any(), any(), any(), any(), any(), any(), any());

        boolean registered = service.register(article("CVE-1", "NVD API"), TrustedArticleSource.NVD);

        assertThat(registered).isFalse();
        verify(jdbc, never()).update(contains("article_revisions"),
                any(), any(), any(), any(), any());
    }

    @Test
    void newConnectorArticleGetsServerDerivedPublicPolicyAndImmutableEvidence() {
        TrustedArticleRegistrationService service = new TrustedArticleRegistrationService(
                jdbc, new ObjectMapper(), audit);
        doReturn(1).when(jdbc).update(contains("article_governance"),
                any(), any(), any(), any(), any(), any(), any());

        boolean registered = service.register(article("CVE-2", "NVD API"), TrustedArticleSource.NVD);

        assertThat(registered).isTrue();
        verify(jdbc).update(contains("article_governance"),
                org.mockito.ArgumentMatchers.eq("CVE-2"), org.mockito.ArgumentMatchers.eq("shared"),
                org.mockito.ArgumentMatchers.eq("public"), org.mockito.ArgumentMatchers.eq("approved"),
                org.mockito.ArgumentMatchers.eq("public"), any(), any());
        verify(jdbc, times(2)).update(contains("article_evidence_snapshots"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static Article article(String id, String source) {
        Article article = new Article();
        article.setId(id);
        article.setTitle("Example title");
        article.setCategoryId("advisories");
        article.setSeverity("high");
        article.setTags(List.of("CVE"));
        article.setSource(source);
        article.setUpdatedOn(LocalDate.of(2026, 9, 5));
        article.setAuthor("connector");
        article.setConfidence("Confirmed");
        article.setSnippet("Example snippet");
        article.setBody(List.of(new ArticleSection("Description", "Exact source text")));
        article.setIocs(List.of());
        article.setRelated(List.of());
        return article;
    }
}
