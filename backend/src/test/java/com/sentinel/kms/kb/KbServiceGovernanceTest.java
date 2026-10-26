package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
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

    @Test
    void categoryCountsReflectReadableDocumentsInsteadOfSeededTotals() {
        Article first = article("playbook-1", "Phishing response");
        first.setCategoryId("playbooks");
        Article second = article("playbook-2", "Ransomware response");
        second.setCategoryId("playbooks");
        Category playbooks = new Category();
        playbooks.setId("playbooks");
        playbooks.setLabel("Playbooks & Runbooks");
        playbooks.setAbbr("PB");
        playbooks.setDocCount(73);
        Category privateCategory = new Category();
        privateCategory.setId("private");
        privateCategory.setDocCount(100);
        List<String> candidates = List.of("playbook-1", "playbook-2", "restricted-1");
        Set<String> readable = Set.of("playbook-1", "playbook-2");
        when(articles.findAllIds()).thenReturn(candidates);
        when(access.readableArticleIds("analyst", candidates)).thenReturn(readable);
        when(articles.findAllById(readable)).thenReturn(List.of(first, second));
        when(categories.findAllByOrderBySortOrderAsc()).thenReturn(List.of(playbooks, privateCategory));

        assertThat(service.listCategories("analyst"))
                .containsExactly(new CategoryDto("playbooks", "Playbooks & Runbooks", "PB", 2));
        verify(articles, never()).findAll();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "all", " ALL "})
    void categoryOnlyFilterNeverBindsNullSeverityAndPreservesPolicyGate(String inactiveSeverity) {
        List<String> candidates = List.of("playbook-1", "restricted-1");
        Set<String> readable = Set.of("playbook-1");
        when(articles.findIdsByCategory("playbooks")).thenReturn(candidates);
        when(access.readableArticleIds("analyst", candidates)).thenReturn(readable);
        when(articles.findAllById(readable)).thenReturn(List.of(article("playbook-1", "Response steps")));

        var result = service.search("analyst", null, " playbooks ", inactiveSeverity, null);

        assertThat(result.items()).extracting(ArticleDto::id).containsExactly("playbook-1");
        var ordered = inOrder(articles, access);
        ordered.verify(articles).findIdsByCategory("playbooks");
        ordered.verify(access).readableArticleIds("analyst", candidates);
        ordered.verify(articles).findAllById(readable);
        verify(articles, never()).findIdsByCategoryAndSeverity(any(), any());
        verify(articles, never()).findIdsBySeverity(any());
        verify(articles, never()).findAllIds();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "all", " ALL "})
    void severityOnlyFilterNormalizesValueWithoutBindingNullCategory(String inactiveCategory) {
        List<String> candidates = List.of("public-1", "restricted-1");
        Set<String> readable = Set.of("public-1");
        when(articles.findIdsBySeverity("high")).thenReturn(candidates);
        when(access.readableArticleIds("analyst", candidates)).thenReturn(readable);
        when(articles.findAllById(readable)).thenReturn(List.of(article("public-1", "Response steps")));

        var result = service.search("analyst", null, inactiveCategory, " HiGh ", null);

        assertThat(result.items()).extracting(ArticleDto::id).containsExactly("public-1");
        var ordered = inOrder(articles, access);
        ordered.verify(articles).findIdsBySeverity("high");
        ordered.verify(access).readableArticleIds("analyst", candidates);
        ordered.verify(articles).findAllById(readable);
        verify(articles, never()).findIdsByCategoryAndSeverity(any(), any());
        verify(articles, never()).findIdsByCategory(any());
        verify(articles, never()).findAllIds();
    }

    @Test
    void combinedFiltersUseNormalizedNonNullValuesAndPreservePolicyGate() {
        List<String> candidates = List.of("playbook-1", "restricted-1");
        Set<String> readable = Set.of("playbook-1");
        when(articles.findIdsByCategoryAndSeverity("playbooks", "high")).thenReturn(candidates);
        when(access.readableArticleIds("analyst", candidates)).thenReturn(readable);
        when(articles.findAllById(readable)).thenReturn(List.of(article("playbook-1", "Response steps")));

        var result = service.search("analyst", null, " playbooks ", " HIGH ", null);

        assertThat(result.items()).extracting(ArticleDto::id).containsExactly("playbook-1");
        var ordered = inOrder(articles, access);
        ordered.verify(articles).findIdsByCategoryAndSeverity("playbooks", "high");
        ordered.verify(access).readableArticleIds("analyst", candidates);
        ordered.verify(articles).findAllById(readable);
        verify(articles, never()).findIdsByCategory(any());
        verify(articles, never()).findIdsBySeverity(any());
        verify(articles, never()).findAllIds();
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
