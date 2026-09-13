package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.ArticleSection;
import com.sentinel.kms.kb.Category;
import com.sentinel.kms.kb.CategoryRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.watch.WatchlistService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Materializes MITRE ATT&CK techniques as KB articles under a dedicated
 * {@code techniques} category, so the assistant can ground "how does T1566
 * work?" answers and the KB links techniques into threat-intel articles.
 */
@Service
public class AttackConnectorService {

    private static final Logger log = LoggerFactory.getLogger(AttackConnectorService.class);

    private static final String CATEGORY_ID = "techniques";

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final ConnectorRepository connectorRepository;
    private final AttackClient attackClient;
    private final WatchlistService watchlistService;
    private final MeterRegistry meters;
    private final TrustedArticleRegistrationService trustedArticleRegistrationService;

    public AttackConnectorService(ArticleRepository articleRepository,
                                  CategoryRepository categoryRepository,
                                  ConnectorRepository connectorRepository,
                                  EmbeddingIndexService embeddingIndexService,
                                  AttackClient attackClient,
                                  WatchlistService watchlistService,
                                  MeterRegistry meters,
                                  TrustedArticleRegistrationService trustedArticleRegistrationService) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.connectorRepository = connectorRepository;
        // Retained only for constructor compatibility.  Canonical article
        // evidence is queued durably by trusted registration, never indexed
        // here with unbound legacy metadata.
        this.attackClient = attackClient;
        this.watchlistService = watchlistService;
        this.meters = meters;
        this.trustedArticleRegistrationService = trustedArticleRegistrationService;
    }

    /** Result of a sync run (same shape as the other connectors). */
    public record SyncResult(int fetched, int created, int embedded) {
    }

    public SyncResult sync(int max) {
        List<AttackClient.AttackTechnique> techniques = attackClient.fetch(max);
        ensureCategory();

        List<Article> toSave = new ArrayList<>();
        for (AttackClient.AttackTechnique technique : techniques) {
            if (articleRepository.existsById(technique.techniqueId())) {
                continue;
            }
            toSave.add(toArticle(technique));
        }

        if (!toSave.isEmpty()) {
            trustedArticleRegistrationService.persistAndRegister(
                    articleRepository, toSave, TrustedArticleSource.ATTACK);
        }
        int created = toSave.size();

        // Registration has atomically enqueued revision-fenced canonical work.
        int embedded = 0;

        updateConnector(created);
        watchlistService.matchAndAlert(toSave);
        meters.counter("sentinel.connector.syncs", "connector", "attack").increment();

        return new SyncResult(techniques.size(), created, embedded);
    }

    /** The techniques category ships with this connector, not the seed. */
    private void ensureCategory() {
        if (categoryRepository.existsById(CATEGORY_ID)) {
            return;
        }
        Category category = new Category();
        category.setId(CATEGORY_ID);
        category.setLabel("Techniques");
        category.setAbbr("ATT");
        category.setSortOrder(70);
        try {
            categoryRepository.save(category);
        } catch (Exception e) {
            log.warn("Could not create techniques category: {}", e.getMessage());
        }
    }

    private Article toArticle(AttackClient.AttackTechnique technique) {
        Article article = new Article();
        article.setId(technique.techniqueId());
        article.setCategoryId(CATEGORY_ID);
        article.setSeverity("info"); // reference data, not a live severity call
        article.setTitle("ATT&CK " + technique.techniqueId() + " — " + technique.name());
        article.setSnippet(truncate(technique.description(), 180));
        article.setUpdatedOn(java.time.LocalDate.now());
        article.setAuthor("advisory-bot");
        article.setConfidence("Confirmed");
        article.setSource("MITRE ATT&CK");
        article.setReads(0);
        List<String> tags = new ArrayList<>(List.of("MITRE", "ATT&CK"));
        if (technique.subtechnique()) {
            tags.add("subtechnique");
        }
        tags.addAll(technique.platforms());
        article.setTags(tags);
        article.setRelated(List.of());
        article.setIocs(List.of());
        article.setBody(body(technique));
        return article;
    }

    private List<ArticleSection> body(AttackClient.AttackTechnique technique) {
        List<ArticleSection> sections = new ArrayList<>();
        sections.add(new ArticleSection("Technique",
                technique.name() + (technique.subtechnique() ? " (sub-technique)." : ".")));
        if (!technique.description().isBlank()) {
            sections.add(new ArticleSection("Description", technique.description()));
        }
        if (!technique.platforms().isEmpty()) {
            sections.add(new ArticleSection("Platforms", String.join(", ", technique.platforms())));
        }
        sections.add(new ArticleSection("Source",
                "Imported from the MITRE ATT&CK Enterprise STIX bundle."));
        return sections;
    }

    private void updateConnector(int created) {
        Optional<Connector> existing = connectorRepository.findById("attack");
        if (existing.isEmpty()) {
            return;
        }
        Connector connector = existing.get();
        connector.setDocs(connector.getDocs() + created);
        connector.setLastLabel("just now");
        connector.setStatus("connected");
        connectorRepository.save(connector);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
