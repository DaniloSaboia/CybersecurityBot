package com.sentinel.kms.kb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * RED-first database contract for lifecycle state.  Revision and evidence rows
 * stay immutable; workflow state and the durable reindex handoff live in the
 * additive 20260905040000 migration.
 */
@Testcontainers
class KnowledgeLifecycleMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
        Path root = projectRoot();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        try (Stream<Path> paths = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    @Test
    void lifecycleStateAndDurableReindexContractsAreInstalled() {
        assertThat(regclass("article_revision_workflow_states")).isTrue();
        assertThat(regclass("article_revision_lifecycle_events")).isTrue();
        assertThat(regclass("article_revision_reindex_outbox")).isTrue();
        assertThat(columnExists("article_governance", "state_version")).isTrue();
    }

    @Test
    void workflowStateRequiresAnExactLifecycleEventAtCommit() {
        String orphanArticle = "workflow-event-orphan-" + UUID.randomUUID();
        String orphanRevision = "workflow-event-orphan-rev-" + UUID.randomUUID();
        seedRevisionWithEvidence(orphanArticle, orphanRevision);

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        Throwable rejected = catchThrowable(() -> transaction.executeWithoutResult(status ->
                insertWorkflow(orphanArticle, orphanRevision)));
        assertThat(rejected).isNotNull();
        assertThat(rootCause(rejected).getMessage())
                .contains("workflow state version requires an exact lifecycle event");

        String boundArticle = "workflow-event-bound-" + UUID.randomUUID();
        String boundRevision = "workflow-event-bound-rev-" + UUID.randomUUID();
        seedRevisionWithEvidence(boundArticle, boundRevision);
        transaction.executeWithoutResult(status -> {
            insertWorkflow(boundArticle, boundRevision);
            jdbc.update("""
                            insert into article_revision_lifecycle_events
                                (id, article_revision_id, article_id, workspace_id, classification, event_type,
                                 to_approval_status, to_lifecycle_status, expected_state_version,
                                 resulting_state_version, governance_state_version, actor_sub)
                            values (?, ?, ?, 'shared', 'public', 'approved', 'approved', 'active', -1, 0, 0,
                                    'workflow-fixture')
                            """, "workflow-event:" + boundRevision, boundRevision, boundArticle);
        });
        Integer count = jdbc.queryForObject("""
                        select count(*) from article_revision_workflow_states
                        where article_revision_id = ?
                        """, Integer.class, boundRevision);
        assertThat(count).isEqualTo(1);
    }

    private static void seedRevisionWithEvidence(String articleId, String revisionId) {
        String categoryId = "workflow-event-category-" + UUID.randomUUID();
        jdbc.update("insert into categories (id, label, abbr) values (?, ?, ?)", categoryId,
                "Workflow event", "WE");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity)
                        values (?, 'Workflow event fixture', ?, 'medium')
                        """, articleId, categoryId);
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, created_by_sub)
                        values (?, ?, 1, 'shared', 'public', 'draft', '{}'::jsonb, repeat('0', 64),
                                'workflow-fixture')
                        """, revisionId, articleId);
        jdbc.update("""
                        insert into article_evidence_snapshots
                            (id, article_revision_id, ordinal, source_label, content, content_sha256, created_by_sub)
                        values (?, ?, 0, 'workflow-fixture', 'immutable evidence', repeat('1', 64),
                                'workflow-fixture')
                        """, "workflow-evidence:" + revisionId, revisionId);
    }

    private static void insertWorkflow(String articleId, String revisionId) {
        jdbc.update("""
                        insert into article_revision_workflow_states
                            (article_revision_id, article_id, workspace_id, classification,
                             approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values (?, ?, 'shared', 'public', 'approved', 'active', 'workflow-fixture',
                                clock_timestamp(), 0)
                        """, revisionId, articleId);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean regclass(String table) {
        Boolean present = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
        return Boolean.TRUE.equals(present);
    }

    private static boolean columnExists(String table, String column) {
        Boolean present = jdbc.queryForObject("""
                        select exists (
                            select 1 from information_schema.columns
                            where table_schema = 'public' and table_name = ? and column_name = ?)
                        """, Boolean.class, table, column);
        return Boolean.TRUE.equals(present);
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
