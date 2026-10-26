package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.watch.WatchlistService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pulls recent CVEs from the NVD API, materializes them as KB articles, and
 * queues governed canonical reindex work through trusted registration.  New
 * articles are also pushed past the watchlist matcher to fire user alerts.
 */
@Service
public class NvdConnectorService {

    private final ArticleRepository articleRepository;
    private final ConnectorRepository connectorRepository;
    private final NvdClient nvdClient;
    private final WatchlistService watchlistService;
    private final MeterRegistry meters;
    private final TrustedArticleRegistrationService trustedArticleRegistrationService;

    public NvdConnectorService(ArticleRepository articleRepository,
                               ConnectorRepository connectorRepository,
                               EmbeddingIndexService embeddingIndexService,
                               NvdClient nvdClient,
                               WatchlistService watchlistService,
                               MeterRegistry meters,
                               TrustedArticleRegistrationService trustedArticleRegistrationService) {
        this.articleRepository = articleRepository;
        this.connectorRepository = connectorRepository;
        // Retained as a constructor argument for Spring/test compatibility.
        // Article publication now creates durable canonical reindex work; this
        // connector must never stamp a legacy article-only vector row.
        this.nvdClient = nvdClient;
        this.watchlistService = watchlistService;
        this.meters = meters;
        this.trustedArticleRegistrationService = trustedArticleRegistrationService;
    }

    /**
     * Result of a sync run.
     */
    /** embedded remains a compatibility field; canonical work is queued, not synchronously embedded. */
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
            trustedArticleRegistrationService.persistAndRegister(
                    articleRepository, toSave, TrustedArticleSource.NVD);
        }
        int created = toSave.size();

        // trustedArticleRegistrationService atomically writes one fenced
        // article_revision_reindex_outbox row per publication.  The worker
        // derives chunk text and all evidence metadata from immutable rows.
        int embedded = 0;

        updateConnector(created);
        watchlistService.matchAndAlert(toSave);
        meters.counter("sentinel.connector.syncs", "connector", "nvd").increment();

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
