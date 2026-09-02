package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.watch.WatchlistService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls CISA's Known Exploited Vulnerabilities catalog into the KB: each KEV
 * entry becomes an advisories article (id = the CVE, so NVD and KEV converge
 * on one record), embedded and searchable + citable, and pushed past the
 * watchlist matcher so subscribed users get alerts.
 */
@Service
public class KevConnectorService {

    private static final Logger log = LoggerFactory.getLogger(KevConnectorService.class);

    private final ArticleRepository articleRepository;
    private final ConnectorRepository connectorRepository;
    private final EmbeddingIndexService embeddingIndexService;
    private final KevClient kevClient;
    private final WatchlistService watchlistService;
    private final MeterRegistry meters;

    public KevConnectorService(ArticleRepository articleRepository,
                               ConnectorRepository connectorRepository,
                               EmbeddingIndexService embeddingIndexService,
                               KevClient kevClient,
                               WatchlistService watchlistService,
                               MeterRegistry meters) {
        this.articleRepository = articleRepository;
        this.connectorRepository = connectorRepository;
        this.embeddingIndexService = embeddingIndexService;
        this.kevClient = kevClient;
        this.watchlistService = watchlistService;
        this.meters = meters;
    }

    /** Result of a sync run (same shape as the NVD connector). */
    public record SyncResult(int fetched, int created, int embedded) {
    }

    public SyncResult sync(int max) {
        List<KevClient.KevEntry> entries = kevClient.fetch(max);

        List<Article> toSave = new ArrayList<>();
        for (KevClient.KevEntry entry : entries) {
            // KEV and NVD share the CVE id as the article id: whichever lands
            // first wins, the other skips. Ransomware-linked KEVs are worth
            // re-flagging, but a re-write would fight NVD's copy — keep it simple.
            if (articleRepository.existsById(entry.cveId())) {
                continue;
            }
            toSave.add(toArticle(entry));
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
                        "Known-exploited vulnerability\n" + descriptionOf(article));
                embeddingIndexService.index(chunks, Map.of(
                        "article_id", article.getId(),
                        "title", article.getTitle(),
                        "source", "CISA KEV",
                        "category", "advisories"));
                embedded++;
            } catch (Exception e) {
                log.warn("Embedding failed for {}: {}", article.getId(), e.getMessage());
            }
        }

        updateConnector(created);
        watchlistService.matchAndAlert(toSave);
        meters.counter("sentinel.connector.syncs", "connector", "kev").increment();

        return new SyncResult(entries.size(), created, embedded);
    }

    private Article toArticle(KevClient.KevEntry entry) {
        Article article = new Article();
        article.setId(entry.cveId());
        article.setCategoryId("advisories");
        // Actively exploited + ransomware-linked is the loudest thing the KB knows.
        article.setSeverity(entry.ransomwareUse() ? "critical" : "high");
        article.setTitle("KEV: " + entry.cveId() + " — " + firstWords(entry.vulnerabilityName(), 12));
        article.setSnippet(truncate((entry.vendorProject() + " " + entry.product()
                + " — " + entry.vulnerabilityName()).trim(), 180));
        article.setUpdatedOn(parseDate(entry.dateAdded()));
        article.setAuthor("advisory-bot");
        article.setConfidence("Confirmed");
        article.setSource("CISA KEV");
        article.setReads(0);
        article.setTags(entry.ransomwareUse()
                ? List.of("KEV", "CVE", "ransomware")
                : List.of("KEV", "CVE"));
        article.setRelated(List.of());
        article.setIocs(List.of());
        article.setBody(body(entry));
        return article;
    }

    private List<ArticleSection> body(KevClient.KevEntry entry) {
        List<ArticleSection> sections = new ArrayList<>();
        sections.add(new ArticleSection("Known-exploited vulnerability",
                (entry.vendorProject() + " " + entry.product() + " — "
                        + entry.vulnerabilityName()).trim() + "."));
        if (!entry.description().isBlank()) {
            sections.add(new ArticleSection("Required action", entry.description()));
        }
        if (!entry.dueDate().isBlank()) {
            sections.add(new ArticleSection("Remediation due date",
                    "Federal agencies must remediate by " + entry.dueDate() + "."));
        }
        if (entry.ransomwareUse()) {
            sections.add(new ArticleSection("Ransomware use",
                    "CISA lists this vulnerability as associated with known ransomware campaigns."));
        }
        sections.add(new ArticleSection("Source",
                "Imported from the CISA Known Exploited Vulnerabilities catalog."));
        return sections;
    }

    private void updateConnector(int created) {
        Optional<Connector> existing = connectorRepository.findById("kev");
        if (existing.isEmpty()) {
            return;
        }
        Connector connector = existing.get();
        connector.setDocs(connector.getDocs() + created);
        connector.setLastLabel("just now");
        connector.setStatus("connected");
        connectorRepository.save(connector);
    }

    private String descriptionOf(Article article) {
        if (article.getBody() != null && !article.getBody().isEmpty()) {
            String p = article.getBody().get(0).p();
            return p == null ? "" : p;
        }
        return article.getSnippet() == null ? "" : article.getSnippet();
    }

    private LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date.substring(0, 10));
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
        return text.length() <= max ? text : text.substring(0, max);
    }
}
