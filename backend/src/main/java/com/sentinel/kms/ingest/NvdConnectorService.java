package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls recent CVEs from the NVD API, materializes them as KB articles, and
 * embeds them so they're searchable + citable by the RAG assistant.
 */
@Service
public class NvdConnectorService {

    private static final Logger log = LoggerFactory.getLogger(NvdConnectorService.class);

    private final ArticleRepository articleRepository;
    private final ConnectorRepository connectorRepository;
    private final EmbeddingIndexService embeddingIndexService;
    private final NvdClient nvdClient;

    public NvdConnectorService(ArticleRepository articleRepository,
                               ConnectorRepository connectorRepository,
                               EmbeddingIndexService embeddingIndexService,
                               NvdClient nvdClient) {
        this.articleRepository = articleRepository;
        this.connectorRepository = connectorRepository;
        this.embeddingIndexService = embeddingIndexService;
        this.nvdClient = nvdClient;
    }

    /**
     * Result of a sync run.
     */
    public record SyncResult(int fetched, int created, int embedded) {
    }

    public SyncResult sync(int days, int max) {
        List<NvdClient.Cve> cves = nvdClient.fetchRecent(days, max);

        List<Article> toSave = new ArrayList<>();
        for (NvdClient.Cve cve : cves) {
            if (articleRepository.existsById(cve.id())) {
                continue;
            }
            toSave.add(toArticle(cve));
        }

        if (!toSave.isEmpty()) {
            articleRepository.saveAll(toSave);
        }
        int created = toSave.size();

        int embedded = 0;
        for (Article article : toSave) {
            try {
                List<String> chunks = List.of(
                        article.getTitle() + " — " + article.getSnippet(),
                        "Description\n" + descriptionOf(article));
                embeddingIndexService.index(chunks, Map.of(
                        "article_id", article.getId(),
                        "title", article.getTitle(),
                        "source", "NVD API",
                        "category", "advisories"));
                embedded++;
            } catch (Exception e) {
                log.warn("Embedding failed for {}: {}", article.getId(), e.getMessage());
            }
        }

        updateConnector(created);

        return new SyncResult(cves.size(), created, embedded);
    }

    private Article toArticle(NvdClient.Cve cve) {
        String description = cve.description() == null ? "" : cve.description();
        Double baseScore = cve.baseScore();

        Article article = new Article();
        article.setId(cve.id());
        article.setCategoryId("advisories");
        article.setSeverity(severityFromScore(baseScore));
        article.setTitle(cve.id() + " — " + firstWords(description, 10));
        article.setSnippet(truncate(description, 180));
        article.setCvss(baseScore == null ? null : String.valueOf(baseScore));
        article.setUpdatedOn(parsePublished(cve.published()));
        article.setAuthor("advisory-bot");
        article.setConfidence("Confirmed");
        article.setSource("NVD API");
        article.setReads(0);
        article.setTags(List.of("CVE", "NVD"));
        article.setRelated(List.of());
        article.setIocs(List.of());
        article.setBody(List.of(
                new ArticleSection("Description", description),
                new ArticleSection("Source", "Imported from the NVD CVE 2.0 API.")));
        return article;
    }

    private void updateConnector(int created) {
        Optional<Connector> existing = connectorRepository.findById("nvd");
        if (existing.isEmpty()) {
            return;
        }
        Connector connector = existing.get();
        connector.setDocs(connector.getDocs() + created);
        connector.setLastLabel("just now");
        connector.setStatus("connected");
        connectorRepository.save(connector);
    }

    /** Recover the original description from the body for embedding. */
    private String descriptionOf(Article article) {
        if (article.getBody() != null && !article.getBody().isEmpty()) {
            String p = article.getBody().get(0).p();
            return p == null ? "" : p;
        }
        return article.getSnippet() == null ? "" : article.getSnippet();
    }

    private String severityFromScore(Double score) {
        if (score == null) {
            return "info";
        }
        if (score >= 9.0) {
            return "critical";
        }
        if (score >= 7.0) {
            return "high";
        }
        if (score >= 4.0) {
            return "medium";
        }
        if (score > 0.0) {
            return "low";
        }
        return "info";
    }

    private LocalDate parsePublished(String published) {
        try {
            return LocalDate.parse(published.substring(0, 10));
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private String firstWords(String text, int count) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = text.trim().split("\\s+");
        if (words.length <= count) {
            return String.join(" ", words);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
