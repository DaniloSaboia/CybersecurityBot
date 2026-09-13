package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbServiceGovernanceTest {

    @Mock ArticleRepository articles;
    @Mock CategoryRepository categories;
    @Mock KnowledgeAccessService access;

    private KbService service;

    @BeforeEach
    void setUp() {
        service = new KbService(articles, categories, access);
    }

    @Test
    void unreadableDirectArticleLooksAbsentBeforeTheEntityIsFetched() {
        when(access.canReadArticle("outsider", "restricted-1"))
                .thenReturn(new AccessDecision(false, "ROLE_REQUIRED", "team-a", "RESTRICTED", "APPROVED"));

        assertThatThrownBy(() -> service.getArticle("outsider", "restricted-1"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Article not found: restricted-1");

        verify(articles, never()).findById(any());
    }

    @Test
    void listFetchesArticleBodiesOnlyAfterAReadonlyPolicyIdGate() {
        Article publicArticle = article("public-1", "public report");
        when(articles.findAllIds()).thenReturn(List.of("public-1", "restricted-1"));
        when(access.readableArticleIds("analyst", List.of("public-1", "restricted-1")))
                .thenReturn(Set.of("public-1"));
        when(articles.findAllById(Set.of("public-1"))).thenReturn(List.of(publicArticle));

        ArticleListResponse result = service.search("analyst", null, null, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).extracting(ArticleDto::id).containsExactly("public-1");
        verify(articles, never()).findAll();
    }

    private static Article article(String id, String title) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setSeverity("info");
        article.setTags(List.of());
        article.setBody(List.of());
        article.setIocs(List.of());
        article.setRelated(List.of());
        return article;
    }
}
