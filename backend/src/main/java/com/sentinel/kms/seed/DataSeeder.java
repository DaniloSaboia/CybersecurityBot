package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.ingest.Connector;
import com.sentinel.kms.ingest.ConnectorRepository;
import com.sentinel.kms.ingest.IngestJob;
import com.sentinel.kms.ingest.IngestJobRepository;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.kb.Category;
import com.sentinel.kms.kb.CategoryRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import com.sentinel.kms.kb.Ioc;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Seeds immutable governed article data from {@code classpath:seed/seed.json} on
 * first boot.  Trusted registration atomically creates durable canonical
 * reindex work; seed data is not directly indexed with legacy metadata.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final boolean seedEnabled;
    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final ConnectorRepository connectorRepository;
    private final IngestJobRepository ingestJobRepository;
    private final ObjectMapper objectMapper;
    private final TrustedArticleRegistrationService trustedArticleRegistrationService;

    public DataSeeder(
            @Value("${app.seed.enabled:true}") boolean seedEnabled,
            ArticleRepository articleRepository,
            CategoryRepository categoryRepository,
            ConnectorRepository connectorRepository,
            IngestJobRepository ingestJobRepository,
            EmbeddingIndexService embeddingIndexService,
            ObjectMapper objectMapper,
            TrustedArticleRegistrationService trustedArticleRegistrationService) {
        this.seedEnabled = seedEnabled;
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.connectorRepository = connectorRepository;
        this.ingestJobRepository = ingestJobRepository;
        // Kept in the constructor for Spring/test compatibility.  The
        // lifecycle reindex worker is the only article vector publisher.
        this.objectMapper = objectMapper;
        this.trustedArticleRegistrationService = trustedArticleRegistrationService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!seedEnabled) {
            log.info("Data seeding disabled (app.seed.enabled=false).");
            return;
        }
        long existingArticles = articleRepository.count();
        if (existingArticles > 0) {
            // Existing rows are either already governed or have a migration
            // outbox item.  Never relabel their legacy text as current; the
            // worker derives canonical chunks from immutable evidence.
            int added = upsertConnectors(root());
            log.info("Seed skipped — {} articles already present; canonical reindex remains durable ({} new connectors).",
                    existingArticles, added);
            return;
        }

        JsonNode root;
        ClassPathResource resource = new ClassPathResource("seed/seed.json");
        try (InputStream in = resource.getInputStream()) {
            root = objectMapper.readTree(in);
        }

        int categories = seedCategories(root.path("categories"));
        int connectors = seedConnectors(root.path("connectors"));
        int jobs = seedJobs(root.path("jobs"));
        List<Article> articles = seedArticles(root.path("articles"));
        trustedArticleRegistrationService.persistAndRegister(
                articleRepository, articles, TrustedArticleSource.SEED);
        int queued = articles.size();

        log.info("Seed complete — {} categories, {} connectors, {} jobs, {} articles ({} canonical reindex jobs queued).",
                categories, connectors, jobs, articles.size(), queued);
    }

    /** Re-reads seed.json (used for connector backfill on already-seeded DBs). */
    private JsonNode root() throws Exception {
        try (InputStream in = new ClassPathResource("seed/seed.json").getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    /** Inserts connectors from seed.json that are not yet in the table. */
    private int upsertConnectors(JsonNode root) {
        int added = 0;
        for (JsonNode n : root.path("connectors")) {
            String id = n.path("id").asText();
            if (id.isBlank() || connectorRepository.existsById(id)) {
                continue;
            }
            Connector c = new Connector();
            c.setId(id);
            c.setName(n.path("name").asText());
            c.setKind(n.path("kind").asText());
            c.setStatus(n.path("status").asText());
            c.setDocs(n.path("docs").asInt());
            c.setLastLabel(n.path("lastLabel").asText());
            c.setAuto(n.path("auto").asBoolean());
            connectorRepository.save(c);
            added++;
        }
        return added;
    }

    private int seedCategories(JsonNode arr) {
        List<Category> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Category c = new Category();
            c.setId(n.path("id").asText());
            c.setLabel(n.path("label").asText());
            c.setAbbr(n.path("abbr").asText());
            c.setDocCount(n.path("docCount").asInt());
            c.setSortOrder(n.path("sortOrder").asInt());
            out.add(c);
        }
        categoryRepository.saveAll(out);
        return out.size();
    }

    private int seedConnectors(JsonNode arr) {
        List<Connector> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Connector c = new Connector();
            c.setId(n.path("id").asText());
            c.setName(n.path("name").asText());
            c.setKind(n.path("kind").asText());
            c.setStatus(n.path("status").asText());
            c.setDocs(n.path("docs").asInt());
            c.setLastLabel(n.path("lastLabel").asText());
            c.setAuto(n.path("auto").asBoolean());
            out.add(c);
        }
        connectorRepository.saveAll(out);
        return out.size();
    }

    private int seedJobs(JsonNode arr) {
        List<IngestJob> out = new ArrayList<>();
        for (JsonNode n : arr) {
            IngestJob j = new IngestJob();
            j.setId(n.path("id").asText());
            j.setName(n.path("name").asText());
            j.setSource(n.path("source").asText());
            j.setSizeLabel(n.path("sizeLabel").asText());
            j.setStage(n.path("stage").asText());
            j.setPct(n.path("pct").asDouble());
            j.setChunks(n.path("chunks").asInt());
            j.setWhenLabel(n.path("whenLabel").asText());
            // Durable ingestion treats only explicit demo rows as simulator-owned.
            j.setDemo(true);
            if (n.hasNonNull("error")) {
                j.setError(n.path("error").asText());
            }
            out.add(j);
        }
        ingestJobRepository.saveAll(out);
        return out.size();
    }

    private List<Article> seedArticles(JsonNode arr) {
        List<Article> out = new ArrayList<>();
        for (JsonNode n : arr) {
            Article a = new Article();
            a.setId(n.path("id").asText());
            a.setTitle(n.path("title").asText());
            // JSON "category" -> entity categoryId
            a.setCategoryId(n.path("category").asText());
            a.setSeverity(n.path("severity").asText());
            a.setTags(stringList(n.path("tags")));
            a.setSource(n.path("source").asText());
            // JSON "updated" -> entity updatedOn (ISO yyyy-MM-dd)
            a.setUpdatedOn(LocalDate.parse(n.path("updated").asText()));
            a.setAuthor(n.path("author").asText());
            a.setConfidence(n.path("confidence").asText());
            a.setReads(n.path("reads").asInt());
            if (n.hasNonNull("cvss")) {
                a.setCvss(n.path("cvss").asText());
            }
            a.setSnippet(n.path("snippet").asText());
            a.setBody(sections(n.path("body")));
            a.setIocs(iocs(n.path("iocs")));
            a.setRelated(stringList(n.path("related")));
            out.add(a);
        }
        return out;
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static List<ArticleSection> sections(JsonNode arr) {
        List<ArticleSection> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(new ArticleSection(n.path("h").asText(), n.path("p").asText()));
            }
        }
        return out;
    }

    private static List<Ioc> iocs(JsonNode arr) {
        List<Ioc> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
                JsonNode n = it.next();
                out.add(new Ioc(n.path("type").asText(), n.path("value").asText()));
            }
        }
        return out;
    }
}
