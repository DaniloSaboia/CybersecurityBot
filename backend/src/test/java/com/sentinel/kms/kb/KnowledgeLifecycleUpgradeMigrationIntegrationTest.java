package com.sentinel.kms.kb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrade-only regression.  The rows deliberately satisfy the pre-lifecycle
 * schema while violating lifecycle invariants which were not enforceable
 * before 20260905040000.  The lifecycle migration must normalize them before
 * installing its strict workflow triggers; it must not make a production
 * upgrade fail merely because an old governance row was internally
 * inconsistent.
 */
@Testcontainers
class KnowledgeLifecycleUpgradeMigrationIntegrationTest {

    private static final String PAST_EXPIRY = "clock_timestamp() - interval '1 hour'";
    private static final String FUTURE_EXPIRY = "clock_timestamp() + interval '1 day'";
    private static final String LATE_REVIEW = "clock_timestamp() + interval '3 days'";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void upgradeLegacyDatabase() throws Exception {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
        Path root = projectRoot();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        applyMigrationsThroughChatEvidence(root);
        jdbc.execute("set search_path to public, extensions");

        jdbc.update("insert into categories (id, label, abbr) values ('upgrade-cat', 'Upgrade', 'UP')");
        seedLegacyArticle("upgrade-expired", "approved", "active", "NULL", PAST_EXPIRY);
        seedLegacyArticle("upgrade-rejected", "rejected", "active", "NULL", "NULL");
        seedLegacyArticle("upgrade-superseded", "superseded", "active", "NULL", "NULL");
        seedLegacyArticle("upgrade-late-review", "approved", "active", LATE_REVIEW, FUTURE_EXPIRY);

        // The system under test: this must work against an already-populated
        // pre-40000 database rather than only a clean install.
        jdbc.execute(Files.readString(root.resolve("supabase/migrations/20260905040000_knowledge_lifecycle.sql")));
    }

    @Test
    void upgradeNormalizesLegacyLifecycleContradictionsBeforeBackfill() {
        assertWorkflow("upgrade-expired", "approved", "expired");
        assertWorkflow("upgrade-rejected", "rejected", "archived");
        assertWorkflow("upgrade-superseded", "superseded", "superseded");
        assertWorkflow("upgrade-late-review", "approved", "active");

        assertThat(isCanonicallyReadable("upgrade-expired")).isFalse();
        assertThat(isCanonicallyReadable("upgrade-rejected")).isFalse();
        assertThat(isCanonicallyReadable("upgrade-superseded")).isFalse();
        assertThat(isCanonicallyReadable("upgrade-late-review")).isTrue();

        // A rejected/superseded legacy current pointer is not a publication.
        // Clearing it avoids retaining a pointer that the new binding trigger
        // would reject on every later governance update.  Expired approved
        // history may remain identifiable but is non-readable.
        assertThat(currentRevision("upgrade-expired")).isEqualTo("rev:upgrade-expired:1");
        assertThat(currentRevision("upgrade-rejected")).isNull();
        assertThat(currentRevision("upgrade-superseded")).isNull();
        assertThat(currentRevision("upgrade-late-review")).isEqualTo("rev:upgrade-late-review:1");

        assertThat(validWorkflowDates("upgrade-expired")).isTrue();
        assertThat(validWorkflowDates("upgrade-rejected")).isTrue();
        assertThat(validWorkflowDates("upgrade-superseded")).isTrue();
        assertThat(validWorkflowDates("upgrade-late-review")).isTrue();

        // Every evidence-backed legacy revision gets one immutable baseline
        // event, but only a current approved active non-expired revision gets
        // a durable reindex request.
        for (String articleId : List.of("upgrade-expired", "upgrade-rejected", "upgrade-superseded",
                "upgrade-late-review")) {
            assertThat(baselineEvents(articleId)).isEqualTo(1);
        }
        assertThat(outboxRows("upgrade-expired")).isZero();
        assertThat(outboxRows("upgrade-rejected")).isZero();
        assertThat(outboxRows("upgrade-superseded")).isZero();
        assertThat(outboxRows("upgrade-late-review")).isEqualTo(1);
        assertThat(outboxState("upgrade-late-review")).isEqualTo("pending");
    }

    private static void seedLegacyArticle(String articleId, String approvalStatus, String lifecycleStatus,
                                          String reviewDueExpression, String expiryExpression) {
        String revisionId = "rev:" + articleId + ":1";
        String evidenceId = "evidence:" + articleId + ":1:0";
        jdbc.update("""
                        insert into articles (id, title, category_id, severity, snippet)
                        values (?, ?, 'upgrade-cat', 'medium', 'legacy evidence')
                        """, articleId, "Legacy " + articleId);
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, created_by_sub)
                        values (?, ?, 1, 'shared', 'public', ?, cast(? as jsonb), repeat('a', 64), 'legacy-upgrade')
                        """, revisionId, articleId, approvalStatus,
                "{\"id\":\"" + articleId + "\",\"title\":\"Legacy " + articleId + "\"}");
        jdbc.update("""
                        insert into article_evidence_snapshots
                            (id, article_revision_id, ordinal, source_label, content, content_sha256, created_by_sub)
                        values (?, ?, 0, 'legacy', 'legacy evidence', repeat('b', 64), 'legacy-upgrade')
                        """, evidenceId, revisionId);
        jdbc.execute("""
                        insert into article_governance
                            (article_id, workspace_id, classification, approval_status, read_policy, lifecycle_status,
                             current_revision_id, review_due_at, expires_at, created_by_sub, approved_by_sub, approved_at)
                        values ('%s', 'shared', 'public', '%s', 'public', '%s', '%s', %s, %s,
                                'legacy-upgrade', 'legacy-approver', clock_timestamp())
                        """.formatted(articleId, approvalStatus, lifecycleStatus, revisionId,
                reviewDueExpression, expiryExpression));
    }

    private static void assertWorkflow(String articleId, String approvalStatus, String lifecycleStatus) {
        String actual = jdbc.queryForObject("""
                        select ws.approval_status, ws.lifecycle_status
                        from article_revision_workflow_states ws
                        where ws.article_id = ?
                        """, (rs, row) -> rs.getString(1) + ":" + rs.getString(2), articleId);
        assertThat(actual).isEqualTo(approvalStatus + ":" + lifecycleStatus);

        String projection = jdbc.queryForObject("""
                        select approval_status, lifecycle_status
                        from article_governance where article_id = ?
                        """, (rs, row) -> rs.getString(1) + ":" + rs.getString(2), articleId);
        assertThat(projection).isEqualTo(approvalStatus + ":" + lifecycleStatus);
    }

    private static boolean isCanonicallyReadable(String articleId) {
        Boolean readable = jdbc.queryForObject("""
                        select exists (
                            select 1
                            from article_governance ag
                            join article_revision_workflow_states ws
                              on ws.article_revision_id = ag.current_revision_id
                             and ws.article_id = ag.article_id
                             and ws.workspace_id = ag.workspace_id
                             and ws.classification = ag.classification
                            where ag.article_id = ?
                              and ag.approval_status = 'approved'
                              and ag.lifecycle_status = 'active'
                              and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                              and ws.approval_status = 'approved'
                              and ws.lifecycle_status = 'active'
                              and (ws.expires_at is null or ws.expires_at > clock_timestamp()))
                        """, Boolean.class, articleId);
        return Boolean.TRUE.equals(readable);
    }

    private static String currentRevision(String articleId) {
        return jdbc.queryForObject("select current_revision_id from article_governance where article_id = ?",
                String.class, articleId);
    }

    private static boolean validWorkflowDates(String articleId) {
        Boolean valid = jdbc.queryForObject("""
                        select review_due_at is null or expires_at is null or review_due_at <= expires_at
                        from article_revision_workflow_states where article_id = ?
                        """, Boolean.class, articleId);
        return Boolean.TRUE.equals(valid);
    }

    private static int baselineEvents(String articleId) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from article_revision_lifecycle_events
                        where article_id = ? and event_type = 'migration_baseline'
                        """, Integer.class, articleId);
        return count == null ? 0 : count;
    }

    private static int outboxRows(String articleId) {
        Integer count = jdbc.queryForObject("select count(*) from article_revision_reindex_outbox where article_id = ?",
                Integer.class, articleId);
        return count == null ? 0 : count;
    }

    private static String outboxState(String articleId) {
        return jdbc.queryForObject("select state from article_revision_reindex_outbox where article_id = ?",
                String.class, articleId);
    }

    private static void applyMigrationsThroughChatEvidence(Path root) throws Exception {
        try (Stream<Path> paths = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> path.getFileName().toString().compareTo("20260905030000_chat_evidence.sql") <= 0)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("supabase/migrations"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not find project root");
        }
        return current;
    }
}
