package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Initializes governance only for known server-side seed/connector producers.
 * It uses INSERT ... ON CONFLICT DO NOTHING, so a refresh can never downgrade
 * existing sensitive policy, approval, review, or revision history.
 */
@Service
public class TrustedArticleRegistrationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeAuditService audit;
    private final TransactionTemplate transaction;

    @Autowired
    public TrustedArticleRegistrationService(JdbcTemplate jdbc, ObjectMapper mapper,
                                             KnowledgeAuditService audit,
                                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.transaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /**
     * Compatibility constructor for direct integration tests and small local
     * tools.  A real JdbcTemplate data source receives the same atomic
     * registration boundary as the Spring-managed bean; mock-only unit tests
     * retain their no-database behavior.
     */
    public TrustedArticleRegistrationService(JdbcTemplate jdbc, ObjectMapper mapper,
                                             KnowledgeAuditService audit) {
        this(jdbc, mapper, audit, fallbackTransactionManager(jdbc));
    }

    /**
     * The persistence boundary for server-trusted feeds.  Connector input is
     * materialized as an article and its governed revision/evidence together,
     * so an evidence failure cannot strand a row that a retry would skip.
     *
     * <p>This method deliberately lives on this separate Spring bean instead
     * of a connector or a self-invocation.  That makes {@code @Transactional}
     * effective for the repository write and every registration write while
     * keeping network fetches and vector embedding out of the transaction.</p>
     */
    @Transactional
    public List<Article> persistAndRegister(ArticleRepository articleRepository,
                                            List<Article> articles,
                                            TrustedArticleSource source) {
        if (articleRepository == null) {
            throw new IllegalArgumentException("article repository is required");
        }
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        // Registration uses JdbcTemplate and article_governance has a real FK
        // to articles, so force the JPA inserts to reach PostgreSQL before the
        // first JDBC statement.  This is still one surrounding transaction:
        // any revision/evidence failure rolls the article insert back too.
        List<Article> saved = articleRepository.saveAllAndFlush(articles);
        registerAll(saved, source);
        return saved;
    }

    public boolean register(Article article, TrustedArticleSource source) {
        return inTransaction(() -> registerInTransaction(article, source));
    }

    private boolean registerInTransaction(Article article, TrustedArticleSource source) {
        if (source == null) {
            throw new IllegalArgumentException("trusted source is required");
        }
        source.verify(article);
        String revisionId = "rev:" + article.getId() + ":" + UUID.randomUUID();
        int governanceCreated = jdbc.update("""
                        insert into article_governance
                            (article_id, workspace_id, classification, approval_status, read_policy,
                             lifecycle_status, created_by_sub, approved_by_sub, approved_at)
                        values (?, ?, ?, ?, ?, 'active', ?, ?, now())
                        on conflict (article_id) do nothing
                        """,
                article.getId(), "shared", "public", "approved", "public",
                source.actor(), source.actor());
        if (governanceCreated == 0) {
            audit.record(KnowledgeAuditEvent.of(source.actor(), "TRUSTED_ARTICLE_REGISTER", "article",
                    article.getId(), "shared", article.getId(), null, AuditOutcome.SUCCESS,
                    Map.of("created", false)));
            return false;
        }

        String snapshot = snapshot(article);
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, created_by_sub)
                        values (?, ?, 1, 'shared', 'public', 'approved', cast(? as jsonb), ?, ?)
                        """, revisionId, article.getId(), snapshot, sha256(snapshot), source.actor());

        insertEvidence("evidence:" + article.getId() + ":" + revisionId + ":0", revisionId, 0,
                article.getSource(), version(article.getUpdatedOn()),
                safe(article.getTitle()) + " — " + safe(article.getSnippet()), source.actor());
        List<ArticleSection> body = article.getBody() == null ? List.of() : article.getBody();
        for (int index = 0; index < body.size(); index++) {
            ArticleSection section = body.get(index);
            String content = safe(section.h()) + "\n" + safe(section.p());
            insertEvidence("evidence:" + article.getId() + ":" + revisionId + ":" + (index + 1),
                    revisionId, index + 1, article.getSource(), version(article.getUpdatedOn()), content,
                    source.actor());
        }

        // 40000 makes a pointer readable only when an immutable revision has a
        // matching approved workflow projection.  This explicit trusted-source
        // registration is intentionally not a broad insert trigger: arbitrary
        // connector/client article writes remain fail closed.
        jdbc.update("""
                        insert into article_revision_workflow_states
                            (article_revision_id, article_id, workspace_id, classification,
                             approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values (?, ?, 'shared', 'public', 'approved', 'active', ?, now(), 0)
                        """, revisionId, article.getId(), source.actor());
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                             (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             to_approval_status, to_lifecycle_status, expected_state_version,
                             resulting_state_version, governance_state_version, actor_sub, metadata)
                        values (?, ?, ?, 'shared', 'public', 'approved', 'approved', 'active', -1, 0, 1, ?,
                                '{"trusted_source":true}'::jsonb)
                        """, UUID.randomUUID().toString(), revisionId, article.getId(), source.actor());

        jdbc.update("""
                        update article_governance ag
                        set current_revision_id = ws.article_revision_id,
                            approval_status = ws.approval_status,
                            lifecycle_status = ws.lifecycle_status,
                            review_due_at = ws.review_due_at,
                            expires_at = ws.expires_at,
                            approved_by_sub = ws.approved_by_sub,
                            approved_at = ws.approved_at,
                            state_version = ag.state_version + 1
                        from article_revision_workflow_states ws
                        where ag.article_id = ? and ag.current_revision_id is null
                          and ws.article_revision_id = ? and ws.article_id = ag.article_id
                          and ws.workspace_id = ag.workspace_id and ws.classification = ag.classification
                        """, article.getId(), revisionId);
        jdbc.update("""
                        insert into article_revision_reindex_outbox
                            (id, article_id, article_revision_id, workspace_id, classification, source_key,
                             governance_state_version)
                        values (?, ?, ?, 'shared', 'public', ?, 1)
                        on conflict (article_id, governance_state_version) do nothing
                        """, UUID.randomUUID().toString(), article.getId(), revisionId, "article:" + article.getId());
        audit.record(KnowledgeAuditEvent.of(source.actor(), "TRUSTED_ARTICLE_REGISTER", "article",
                article.getId(), "shared", article.getId(), revisionId, AuditOutcome.SUCCESS,
                Map.of("created", true)));
        return true;
    }

    public void registerAll(List<Article> articles, TrustedArticleSource source) {
        if (articles == null) {
            return;
        }
        inTransaction(() -> {
            for (Article article : articles) {
                registerInTransaction(article, source);
            }
            return null;
        });
    }

    public enum FeedChange { CREATED, UPDATED, UNCHANGED }

    /**
     * Publish one source-owned feed record atomically. Original revisions and
     * evidence remain immutable; the same fenced outbox used by reviewed
     * articles replaces vectors only for the new current revision.
     */
    @Transactional
    public FeedChange publishFeed(ArticleRepository repository, Article article, TrustedArticleSource source) {
        return inTransaction(() -> {
            source.verify(article);
            String connector = source.name().toLowerCase(java.util.Locale.ROOT);
            if (source == TrustedArticleSource.SEED || !article.getId().startsWith("feed:" + connector + ":")) {
                throw new IllegalArgumentException("feed publication requires a source-scoped identity");
            }
            // Serialize first publication too, before a governance row exists.
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    Object.class, article.getId());
            var rows = jdbc.queryForList("""
                    select ag.*, ar.created_by_sub as revision_actor, ar.content_sha256,
                           ws.state_version as workflow_version
                    from article_governance ag
                    join article_revisions ar on ar.id = ag.current_revision_id
                    join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                    where ag.article_id = ? for update of ag, ws
                    """, article.getId());
            if (rows.isEmpty()) {
                if (repository.existsById(article.getId())) {
                    throw new IllegalStateException("Existing feed record requires governance review");
                }
                repository.saveAndFlush(article);
                registerInTransaction(article, source);
                return FeedChange.CREATED;
            }
            var row = rows.get(0);
            if (!source.actor().equals(row.get("created_by_sub"))
                    || !source.actor().equals(row.get("revision_actor"))
                    || !"shared".equals(row.get("workspace_id"))
                    || !"public".equals(row.get("classification"))
                    || !"public".equals(row.get("read_policy"))
                    || !"approved".equals(row.get("approval_status"))
                    || !"active".equals(row.get("lifecycle_status"))
                    || row.get("expires_at") != null || row.get("review_due_at") != null) {
                throw new IllegalStateException("Feed record has a manual policy or revision; review required");
            }
            // Read counters are not source content and must not trigger a revision.
            article.setReads(0);
            String snapshot = snapshot(article);
            if (sha256(snapshot).equals(row.get("content_sha256"))) return FeedChange.UNCHANGED;
            String previous = (String) row.get("current_revision_id");
            long version = ((Number) row.get("state_version")).longValue() + 1;
            long workflowVersion = ((Number) row.get("workflow_version")).longValue();
            int number = jdbc.queryForObject("select coalesce(max(revision_number),0)+1 from article_revisions where article_id=?",
                    Integer.class, article.getId());
            String revision = "rev:" + article.getId() + ":" + UUID.randomUUID();
            jdbc.update("""
                    insert into article_revisions
                    (id,article_id,revision_number,workspace_id,classification,status,snapshot,content_sha256,
                     supersedes_revision_id,created_by_sub)
                    values (?,?,?,'shared','public','approved',?::jsonb,?,?,?)
                    """, revision, article.getId(), number, snapshot, sha256(snapshot), previous, source.actor());
            insertEvidence("evidence:" + revision + ":0", revision, 0, article.getSource(),
                    version(article.getUpdatedOn()), safe(article.getTitle()) + " — " + safe(article.getSnippet()), source.actor());
            var body = article.getBody() == null ? List.<ArticleSection>of() : article.getBody();
            for (int i = 0; i < body.size(); i++) {
                insertEvidence("evidence:" + revision + ":" + (i + 1), revision, i + 1, article.getSource(),
                        version(article.getUpdatedOn()), safe(body.get(i).h()) + "\n" + safe(body.get(i).p()), source.actor());
            }
            jdbc.update("""
                    insert into article_revision_workflow_states
                    (article_revision_id,article_id,workspace_id,classification,approval_status,lifecycle_status,
                     approved_by_sub,approved_at,state_version)
                    values (?,?,'shared','public','approved','active',?,now(),0)
                    """, revision, article.getId(), source.actor());
            jdbc.update("""
                    update article_revision_workflow_states set approval_status='superseded',
                    lifecycle_status='superseded',state_version=state_version+1 where article_revision_id=?
                    """, previous);
            feedEvent(article.getId(), previous, "superseded", "approved", "superseded", "active", "superseded",
                    workflowVersion, workflowVersion + 1, version, source.actor());
            feedEvent(article.getId(), revision, "approved", null, "approved", null, "active",
                    -1, 0, version, source.actor());
            jdbc.update("""
                    update article_governance set current_revision_id=?, state_version=?,
                    approved_by_sub=?,approved_at=now() where article_id=?
                    """, revision, version, source.actor(), article.getId());
            // Preserve UI read counters in the mutable projection.
            article.setReads(jdbc.queryForObject("select reads from articles where id=?", Integer.class, article.getId()));
            repository.saveAndFlush(article);
            jdbc.update("""
                    insert into article_revision_reindex_outbox
                    (id,article_id,article_revision_id,workspace_id,classification,source_key,governance_state_version)
                    values (?,?,?,'shared','public',?,?)
                    """, UUID.randomUUID().toString(), article.getId(), revision, "article:" + article.getId(), version);
            audit.record(KnowledgeAuditEvent.of(source.actor(), "TRUSTED_FEED_UPDATED", "article",
                    article.getId(), "shared", article.getId(), revision, AuditOutcome.SUCCESS,
                    Map.of("revision_number", number)));
            return FeedChange.UPDATED;
        });
    }

    private void feedEvent(String article, String revision, String event, String fromApproval, String toApproval,
                           String fromLifecycle, String toLifecycle, long expected, long resulting,
                           long governanceVersion, String actor) {
        jdbc.update("""
                insert into article_revision_lifecycle_events
                (id,article_revision_id,article_id,workspace_id,classification,event_type,
                 from_approval_status,to_approval_status,from_lifecycle_status,to_lifecycle_status,
                 expected_state_version,resulting_state_version,governance_state_version,actor_sub,metadata)
                values (?,?,?,'shared','public',?,?,?,?,?,?,?,?,?, '{"trusted_source":true}'::jsonb)
                """, UUID.randomUUID().toString(), revision, article, event, fromApproval, toApproval,
                fromLifecycle, toLifecycle, expected, resulting, governanceVersion, actor);
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transaction == null) {
            return work.get();
        }
        return transaction.execute(status -> work.get());
    }

    private static PlatformTransactionManager fallbackTransactionManager(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc == null ? null : jdbc.getDataSource();
        return dataSource == null ? null : new DataSourceTransactionManager(dataSource);
    }

    private void insertEvidence(String id, String revisionId, int ordinal, String sourceLabel,
                                String sourceVersion, String content, String actor) {
        jdbc.update("""
                        insert into article_evidence_snapshots
                            (id, article_revision_id, ordinal, source_label, source_version, content,
                             content_sha256, created_by_sub)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, revisionId, ordinal, safe(sourceLabel), sourceVersion, content,
                sha256(content), actor);
    }

    private String snapshot(Article article) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", article.getId());
        snapshot.put("title", article.getTitle());
        snapshot.put("categoryId", article.getCategoryId());
        snapshot.put("severity", article.getSeverity());
        snapshot.put("tags", article.getTags() == null ? List.of() : article.getTags());
        snapshot.put("source", article.getSource());
        snapshot.put("updatedOn", version(article.getUpdatedOn()));
        snapshot.put("author", article.getAuthor());
        snapshot.put("confidence", article.getConfidence());
        snapshot.put("reads", article.getReads());
        snapshot.put("cvss", article.getCvss());
        snapshot.put("snippet", article.getSnippet());
        snapshot.put("body", article.getBody() == null ? List.of() : article.getBody());
        snapshot.put("iocs", article.getIocs() == null ? List.of() : article.getIocs());
        snapshot.put("related", article.getRelated() == null ? List.of() : article.getRelated());
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("could not snapshot trusted article", e);
        }
    }

    private static String version(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
