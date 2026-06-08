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
import java.util.Map;

/**
 * Seeds the database (and vector store) from {@code classpath:seed/seed.json} on
 * first boot. Idempotent: if any articles already exist it skips entirely.
 * Embedding is best-effort per article so a missing API key never crashes startup.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final boolean seedEnabled;
    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final ConnectorRepository connectorRepository;
    private final IngestJobRepository ingestJobRepository;
    private final EmbeddingIndexService embeddingIndexService;
    private final ObjectMapper objectMapper;

    public DataSeeder(
            @Value("${app.seed.enabled:true}") boolean seedEnabled,
            ArticleRepository articleRepository,
            CategoryRepository categoryRepository,
            ConnectorRepository connectorRepository,
            IngestJobRepository ingestJobRepository,
            EmbeddingIndexService embeddingIndexService,
            ObjectMapper objectMapper) {
        this.seedEnabled = seedEnabled;
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.connectorRepository = connectorRepository;
        this.ingestJobRepository = ingestJobRepository;
        this.embeddingIndexService = embeddingIndexService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!seedEnabled) {
            log.info("Data seeding disabled (app.seed.enabled=false).");
            return;
        }
        long existingArticles = articleRepository.count();
        if (existingArticles > 0) {
            // Already seeded. But if the vector store is empty (e.g. the embedding
            // provider was unavailable on first boot — no API key, Ollama models not
            // pulled yet), backfill embeddings now so RAG has grounding.
            long vectors;
            try {
                vectors = embeddingIndexService.count();
            } catch (Exception e) {
                log.warn("Seed skipped — {} articles present; vector count unavailable: {}",
                        existingArticles, e.getMessage());
                return;
            }
            if (vectors > 0) {
                log.info("Seed skipped — {} articles already present, {} chunks indexed.",
                        existingArticles, vectors);
                return;
            }
            log.info("Articles present but vector store empty — re-embedding {} articles.", existingArticles);
            int re = embedArticles(articleRepository.findAll());
            log.info("Re-embedded {} of {} articles.", re, existingArticles);
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
        int embedded = embedArticles(articles);

        log.info("Seed complete — {} categories, {} connectors, {} jobs, {} articles ({} embedded).",
                categories, connectors, jobs, articles.size(), embedded);
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
        articleRepository.saveAll(out);
        return out;
    }

    private int embedArticles(List<Article> articles) {
        int embedded = 0;
        for (Article a : articles) {
            try {
                List<String> chunks = new ArrayList<>();
                chunks.add(a.getTitle() + " — " + a.getSnippet());
                for (ArticleSection s : a.getBody()) {
                    chunks.add(s.h() + "\n" + s.p());
                }
                Map<String, Object> metadata = Map.of(
                        "article_id", a.getId(),
                        "title", a.getTitle(),
                        "source", a.getSource(),
                        "category", a.getCategoryId());
                embeddingIndexService.index(chunks, metadata);
                embedded++;
            } catch (Exception ex) {
                log.warn("Embedding failed for article {} (continuing): {}", a.getId(), ex.getMessage());
            }
        }
        return embedded;
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
