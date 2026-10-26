package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.Category;
import com.sentinel.kms.kb.CategoryRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuratedKnowledgePackLoaderTest {
    @Mock ArticleRepository articles;
    @Mock CategoryRepository categories;
    @Mock TrustedArticleRegistrationService registration;
    @Mock JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CuratedKnowledgePackLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CuratedKnowledgePackLoader(true, articles, categories, mapper, registration, jdbc);
    }

    @Test
    void repeatStartupDoesNotDuplicateOrReplacePublishedContent() throws Exception {
        Map<String, Article> stored = new HashMap<>();
        when(articles.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(categories.existsById("playbooks")).thenReturn(false, true);
        when(registration.persistAndRegister(eq(articles), anyList(), eq(TrustedArticleSource.CURATED)))
                .thenAnswer(call -> {
                    List<Article> incoming = call.getArgument(1);
                    incoming.forEach(article -> stored.put(article.getId(), article));
                    return incoming;
                });

        var first = loader.load(pack());
        stored.get("PB-START-001").setReads(29);
        var second = loader.load(pack());

        assertThat(first.added()).isEqualTo(1);
        assertThat(first.categoriesAdded()).isEqualTo(1);
        assertThat(second.added()).isZero();
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.conflicts()).isEmpty();
        verify(categories, times(1)).saveAndFlush(any(Category.class));
        verify(registration, times(1)).persistAndRegister(eq(articles), anyList(), eq(TrustedArticleSource.CURATED));
        verify(articles, never()).count();
        verify(articles, never()).saveAll(any());
    }

    @Test
    void populatedDatabaseGetsMissingGuidanceWhileExistingRevisionsAndCategoryStayUntouched() throws Exception {
        Article locallyEdited = new Article();
        locallyEdited.setId("PB-START-001");
        locallyEdited.setTitle("Organization-approved response procedure");
        when(articles.findById("PB-START-001")).thenReturn(Optional.of(locallyEdited));
        when(articles.findById("PB-START-002")).thenReturn(Optional.empty());
        when(categories.existsById("playbooks")).thenReturn(true);
        ObjectNode pack = pack();
        ObjectNode second = (ObjectNode) pack.path("articles").get(0).deepCopy();
        second.put("id", "PB-START-002");
        ((ArrayNode) pack.get("articles")).add(second);

        var result = loader.load(pack);

        assertThat(result.conflicts()).containsExactly("PB-START-001");
        assertThat(result.added()).isEqualTo(1);
        assertThat(locallyEdited.getTitle()).isEqualTo("Organization-approved response procedure");
        var registered = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(registration).persistAndRegister(eq(articles), registered.capture(), eq(TrustedArticleSource.CURATED));
        assertThat((List<Article>) registered.getValue()).extracting(Article::getId).containsExactly("PB-START-002");
        verify(categories, never()).saveAndFlush(any());
    }

    @Test
    void duplicateIdentitiesFailBeforeAnyDatabaseInteraction() throws Exception {
        ObjectNode pack = pack();
        ((ArrayNode) pack.get("articles")).add(pack.path("articles").get(0).deepCopy());

        assertThatThrownBy(() -> loader.load(pack)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate article");

        verifyNoInteractions(articles, categories, registration, jdbc);
    }

    @Test
    void invalidLaterArticleDoesNotPartiallyInstallThePack() throws Exception {
        ObjectNode pack = pack();
        ObjectNode second = (ObjectNode) pack.path("articles").get(0).deepCopy();
        second.put("id", "PB-START-002");
        second.put("confidence", "Expert verified");
        ((ArrayNode) pack.get("articles")).add(second);

        assertThatThrownBy(() -> loader.load(pack)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("human review required");

        verifyNoInteractions(articles, categories, registration, jdbc);
    }

    @Test
    void missingAttributionAndForeignIdentityAreRejected() throws Exception {
        ObjectNode pack = pack();
        ObjectNode article = (ObjectNode) pack.path("articles").get(0);
        article.putArray("body").addObject().put("h", "Actions").put("p", "Isolate the affected endpoint.");
        assertThatThrownBy(() -> loader.load(pack)).hasMessageContaining("authoritative sources");

        ObjectNode other = pack();
        ((ObjectNode) other.path("articles").get(0)).put("id", "user-owned-article");
        assertThatThrownBy(() -> loader.load(other)).hasMessageContaining("reserved pack identity");
        verifyNoInteractions(articles, categories, registration, jdbc);
    }

    @Test
    void bundledPackLoadsIndependentlyOfDemoSeed() throws Exception {
        loader.run();

        var registered = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(registration).persistAndRegister(eq(articles), registered.capture(), eq(TrustedArticleSource.CURATED));
        assertThat((List<Article>) registered.getValue()).hasSizeGreaterThanOrEqualTo(12)
                .allSatisfy(article -> {
                    assertThat(article.getId()).startsWith("PB-START-");
                    assertThat(article.getConfidence()).isEqualTo("Source-informed; human review required");
                    assertThat(article.getIocs()).isEmpty();
                });
        verify(articles, never()).count();
    }

    @Test
    void disabledPackDoesNotReadOrWriteDatabase() throws Exception {
        new CuratedKnowledgePackLoader(false, articles, categories, mapper, registration, jdbc).run();
        verifyNoInteractions(articles, categories, registration, jdbc);
    }

    private ObjectNode pack() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {
                  "packId": "sentinel-response-starter",
                  "version": "2026-09-29.1", "sourceCheckedOn": "2026-09-29",
                  "categories": [{"id":"playbooks","label":"Playbooks","abbr":"PB","sortOrder":3}],
                  "articles": [{
                    "id":"PB-START-001", "title":"Respond to suspicious email", "category":"playbooks",
                    "severity":"high", "tags":["response-playbook","phishing"],
                    "source":"Sentinel response guidance", "updated":"2026-09-29",
                    "author":"Sentinel editorial synthesis", "confidence":"Source-informed; human review required",
                    "snippet":"Preserve the email, report it, and contain affected accounts.",
                    "related":[], "iocs":[],
                    "body":[{"h":"First actions","p":"Report the message to your security team."},
                            {"h":"Authoritative sources","p":"CISA: https://www.cisa.gov/secure-our-world/recognize-and-report-phishing"}]
                  }]
                }
                """);
    }
}
