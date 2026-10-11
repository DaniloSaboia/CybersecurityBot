package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.kb.Category;
import com.sentinel.kms.kb.CategoryRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs server-bundled, source-attributed response guidance on fresh and
 * already populated databases. Each reserved identity is insert-only: editing
 * a bundled article never silently overwrites a user's revision or policy.
 * Publication approval means permitted reference material, not expert review.
 */
@Component
@Order(200)
public class CuratedKnowledgePackLoader implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CuratedKnowledgePackLoader.class);
    private static final String PACK_ID = "sentinel-response-starter";
    private static final Set<String> SEVERITIES = Set.of("critical", "high", "medium", "low", "info");
    private static final String CONFIDENCE = "Source-informed; human review required";

    private final boolean enabled;
    private final ArticleRepository articles;
    private final CategoryRepository categories;
    private final ObjectMapper mapper;
    private final TrustedArticleRegistrationService registration;
    private final JdbcTemplate jdbc;

    public CuratedKnowledgePackLoader(
            @Value("${app.knowledge-pack.enabled:true}") boolean enabled,
            ArticleRepository articles, CategoryRepository categories, ObjectMapper mapper,
            TrustedArticleRegistrationService registration, JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.articles = articles;
        this.categories = categories;
        this.mapper = mapper;
        this.registration = registration;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (!enabled) {
            log.info("Curated response guidance disabled (app.knowledge-pack.enabled=false).");
            return;
        }
        try (InputStream input = new ClassPathResource("knowledge/security-playbooks.json").getInputStream()) {
            LoadResult result = load(mapper.readTree(input));
            log.info("Knowledge pack {} {}: {} added, {} unchanged, {} require review; {} categories added. "
                            + "Canonical indexing is queued through the governed publication pipeline.",
                    PACK_ID, result.version(), result.added(), result.unchanged(),
                    result.conflicts().size(), result.categoriesAdded());
            if (!result.conflicts().isEmpty()) {
                log.warn("Bundled content differs for {}. Existing content and governance were preserved; "
                        + "publish changes through the knowledge revision review workflow.", result.conflicts());
            }
        }
    }

    // run() supplies the transaction, including categories and all canonical
    // registrations. Validation completes before any database mutation.
    LoadResult load(JsonNode root) {
        Pack pack = validate(root);
        // Startup may run on several replicas. Serialize the missing-record
        // check and publication so two first boots cannot overwrite each other.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, PACK_ID);
        List<Article> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int unchanged = 0;
        for (Article candidate : pack.articles()) {
            var existing = articles.findById(candidate.getId());
            if (existing.isEmpty()) {
                missing.add(candidate);
            } else if (content(existing.get()).equals(content(candidate))) {
                unchanged++;
            } else {
                conflicts.add(candidate.getId());
            }
        }
        int categoriesAdded = 0;
        for (Category category : pack.categories()) {
            if (!categories.existsById(category.getId())) {
                categories.saveAndFlush(category);
                categoriesAdded++;
            }
        }
        if (!missing.isEmpty()) {
            registration.persistAndRegister(articles, missing, TrustedArticleSource.CURATED);
        }
        return new LoadResult(pack.version(), missing.size(), unchanged, List.copyOf(conflicts), categoriesAdded);
    }

    private JsonNode content(Article article) {
        // Counters/timestamps do not change source content. Compare the same
        // entity representation on both sides, preserving all authored fields.
        ObjectNode content = mapper.valueToTree(article);
        content.remove(List.of("reads", "createdAt", "updatedAt"));
        return content;
    }

    private Pack validate(JsonNode root) {
        if (root == null || !root.isObject() || !PACK_ID.equals(text(root, "packId"))) {
            throw invalid("unexpected packId");
        }
        String version = text(root, "version");
        date(root, "sourceCheckedOn");
        Set<String> categoryIds = new HashSet<>();
        List<Category> packCategories = new ArrayList<>();
        for (JsonNode node : array(root, "categories")) {
            Category category = new Category();
            category.setId(text(node, "id"));
            if (!categoryIds.add(category.getId())) throw invalid("duplicate category " + category.getId());
            category.setLabel(text(node, "label"));
            category.setAbbr(text(node, "abbr"));
            category.setSortOrder(node.path("sortOrder").asInt());
            category.setDocCount(0);
            packCategories.add(category);
        }
        if (packCategories.isEmpty()) throw invalid("categories must not be empty");

        Set<String> ids = new HashSet<>();
        List<Article> packArticles = new ArrayList<>();
        for (JsonNode node : array(root, "articles")) {
            Article article = new Article();
            article.setId(text(node, "id"));
            if (!ids.add(article.getId())) throw invalid("duplicate article " + article.getId());
            article.setTitle(text(node, "title"));
            article.setCategoryId(text(node, "category"));
            if (!categoryIds.contains(article.getCategoryId())) throw invalid("undeclared article category");
            article.setSeverity(text(node, "severity"));
            if (!SEVERITIES.contains(article.getSeverity())) throw invalid("unknown severity");
            article.setTags(strings(node, "tags"));
            if (!article.getTags().contains("response-playbook")) throw invalid("missing response-playbook tag");
            article.setSource(text(node, "source"));
            article.setUpdatedOn(date(node, "updated"));
            article.setAuthor(text(node, "author"));
            article.setConfidence(text(node, "confidence"));
            if (!CONFIDENCE.equals(article.getConfidence())) throw invalid("guidance must disclose human review required");
            article.setSnippet(text(node, "snippet"));
            article.setRelated(strings(node, "related"));
            if (!array(node, "iocs").isEmpty()) throw invalid("general guidance cannot seed unverified indicators");
            List<ArticleSection> body = new ArrayList<>();
            for (JsonNode section : array(node, "body")) {
                body.add(new ArticleSection(text(section, "h"), text(section, "p")));
            }
            if (body.isEmpty() || body.stream().noneMatch(section ->
                    "Authoritative sources".equals(section.h()) && section.p().contains("https://"))) {
                throw invalid("article requires attributed authoritative sources");
            }
            article.setBody(body);
            TrustedArticleSource.CURATED.verify(article);
            packArticles.add(article);
        }
        if (packArticles.isEmpty()) throw invalid("articles must not be empty");
        for (Article article : packArticles) {
            if (!ids.containsAll(article.getRelated())) throw invalid("related article is outside this pack");
        }
        return new Pack(version, packCategories, packArticles);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("missing text field " + field);
        }
        return value.asText();
    }

    private static LocalDate date(JsonNode node, String field) {
        try {
            return LocalDate.parse(text(node, field));
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid("invalid date " + field);
        }
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid("missing array " + field);
        return value;
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isTextual() || value.asText().isBlank()) throw invalid("invalid " + field);
            values.add(value.asText());
        }
        return values;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid curated knowledge pack: " + reason);
    }

    private record Pack(String version, List<Category> categories, List<Article> articles) {}
    record LoadResult(String version, int added, int unchanged, List<String> conflicts, int categoriesAdded) {}
}
