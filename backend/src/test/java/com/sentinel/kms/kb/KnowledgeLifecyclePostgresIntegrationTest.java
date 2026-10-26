package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.SpringAiEmbeddingIndexService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.ArticleSnapshotInput;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.CreateRevisionRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.EvidenceSnapshotInput;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.LifecycleResponse;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.RollbackRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real PostgreSQL lifecycle regression: publication changes the authorized
 * article projection, stale vectors abstain until a fenced outbox reindex, and
 * rollback/expiry never resurrect prior text or provenance.
 */
@Testcontainers
class KnowledgeLifecyclePostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static KnowledgeLifecycleService lifecycle;
    private static KnowledgeAccessService access;
    private static KnowledgeEvidenceResolver evidenceResolver;
    private static KnowledgeArticleReindexWorker reindexWorker;
    private static SpringAiEmbeddingIndexService index;

    @BeforeAll
    static void migrate() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(Files.readString(projectRoot().resolve("docker/db-init/01-init.sql")));
        try (Stream<Path> paths = Files.list(projectRoot().resolve("supabase/migrations"))) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jdbc.execute(Files.readString(migration));
            }
        }
        jdbc.execute("set search_path to public, extensions");
        jdbc.update("insert into categories (id, label, abbr) values ('lifecycle-cat', 'Lifecycle', 'LC')");

        ObjectMapper mapper = new ObjectMapper();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, mapper);
        access = new KnowledgeAccessService(jdbc, audit);
        lifecycle = new KnowledgeLifecycleService(jdbc, mapper, access, audit);
        evidenceResolver = new KnowledgeEvidenceResolver(jdbc, access);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);

        EmbeddingModel embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(vector());
        when(embeddings.embed(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> inputs = invocation.getArgument(0, List.class);
            return inputs.stream().map(ignored -> vector()).toList();
        });
        index = new SpringAiEmbeddingIndexService(mock(VectorStore.class), jdbc, embeddings, manager, audit);
        reindexWorker = new KnowledgeArticleReindexWorker(jdbc, index, audit, manager);
    }

    @Test
    void publishRollbackAndExpiryAreWorkflowFencedAcrossDirectEvidenceAndVectorReads() {
        String suffix = UUID.randomUUID().toString();
        Fixture fixture = fixture(suffix);
        insertCanonicalOldChunk(fixture);

        assertThat(index.hybridSearch("old evidence", 5, 0.0, "public-reader"))
                .extracting(chunk -> chunk.metadata().get("article_revision_id"))
                .contains(fixture.initialRevisionId());

        LifecycleResponse draft = inTransaction(() -> lifecycle.createDraft("editor-" + suffix,
                fixture.articleId(), new CreateRevisionRequest(0, snapshot("Published R2", "new evidence beta"),
                List.of(evidence("new evidence beta")), null, null)));
        String r2 = draft.revision().id();
        assertThat(draft.stateVersion()).isEqualTo(1);
        assertThat(draft.revision().approvalStatus()).isEqualTo("draft");

        LifecycleResponse pending = inTransaction(() -> lifecycle.submit("editor-" + suffix, fixture.articleId(),
                r2, new TransitionRequest(1, null, null, "READY_FOR_REVIEW")));
        assertThat(pending.stateVersion()).isEqualTo(2);

        LifecycleResponse approved = inTransaction(() -> lifecycle.approve("admin-" + suffix, fixture.articleId(),
                r2, new TransitionRequest(2, null, null, "APPROVED")));
        assertThat(approved.currentRevisionId()).isEqualTo(r2);
        assertThat(jdbc.queryForObject("select title from articles where id = ?", String.class, fixture.articleId()))
                .isEqualTo("Published R2");
        assertThat(evidenceResolver.resolveCurrentApproved("public-reader", new EvidenceReference(
                fixture.articleId(), r2, approved.revision().revisionNumber(), approved.revision().evidence().get(0).id(),
                0, "new evidence beta".length(), null, null, null))).isPresent();

        // Old exact R1 metadata is no longer current; retrieval must abstain
        // until the R2 outbox has indexed canonical R2 evidence.
        assertThat(index.hybridSearch("old evidence", 5, 0.0, "public-reader"))
                .extracting(chunk -> chunk.metadata().get("article_revision_id"))
                .doesNotContain(fixture.initialRevisionId());
        assertThat(jdbc.queryForObject("select state from article_revision_reindex_outbox where article_id = ? "
                        + "and article_revision_id = ?", String.class, fixture.articleId(), r2))
                .isEqualTo("pending");

        // Publish a new immutable rollback revision before the R2 worker runs.
        // The R2 work item must be cancelled rather than deleting/indexing text
        // for an obsolete revision; the fresh rollback work item then wins.
        LifecycleResponse rolledBack = inTransaction(() -> lifecycle.rollback("admin-" + suffix,
                fixture.articleId(), fixture.initialRevisionId(), new RollbackRequest(3, fixture.initialRevisionId(),
                null, null, "ROLLBACK")));
        String rollbackRevision = rolledBack.currentRevisionId();
        assertThat(rollbackRevision).isNotEqualTo(fixture.initialRevisionId()).isNotEqualTo(r2);
        assertThat(jdbc.queryForObject("select title from articles where id = ?", String.class, fixture.articleId()))
                .isEqualTo("Initial R1");
        assertThat(jdbc.queryForObject("select rollback_of_revision_id from article_revisions where id = ?",
                String.class, rollbackRevision)).isEqualTo(fixture.initialRevisionId());
        assertThat(jdbc.queryForObject("select count(*) from article_evidence_snapshots where article_revision_id = ?",
                Integer.class, rollbackRevision)).isEqualTo(1);

        assertThat(reindexWorker.drainOnce()).isTrue();
        assertThat(jdbc.queryForObject("select state from article_revision_reindex_outbox where article_id = ? "
                        + "and article_revision_id = ?", String.class, fixture.articleId(), r2))
                .isEqualTo("cancelled");
        assertThat(reindexWorker.drainOnce()).isTrue();
        assertThat(jdbc.queryForObject("select state from article_revision_reindex_outbox where article_id = ? "
                        + "and article_revision_id = ?", String.class, fixture.articleId(), rollbackRevision))
                .isEqualTo("completed");
        assertThat(jdbc.query("select metadata->>'article_revision_id', metadata->>'evidence_snapshot_id', "
                        + "metadata->>'source_span_start', metadata->>'source_span_end' from vector_store "
                        + "where metadata->>'source_key' = ?", (rs, row) -> List.of(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), "article:" + fixture.articleId()))
                .allSatisfy(metadata -> assertThat(metadata.get(0)).isEqualTo(rollbackRevision));

        LifecycleResponse expired = inTransaction(() -> lifecycle.expire("admin-" + suffix, fixture.articleId(),
                new TransitionRequest(4, null, null, "EXPIRED")));
        assertThat(expired.currentRevisionId()).isEqualTo(rollbackRevision);
        assertThat(access.canReadArticle("public-reader", fixture.articleId()).allowed()).isFalse();
        assertThat(evidenceResolver.resolveCurrentApproved("public-reader", new EvidenceReference(
                fixture.articleId(), rollbackRevision, rolledBack.revision().revisionNumber(),
                rolledBack.revision().evidence().get(0).id(), 0, "old evidence alpha".length(), null, null, null)))
                .isEmpty();
        assertThat(index.hybridSearch("old evidence", 5, 0.0, "public-reader"))
                .extracting(chunk -> chunk.metadata().get("article_id"))
                .doesNotContain(fixture.articleId());
    }

    private static Fixture fixture(String suffix) {
        return inTransaction(() -> {
            String articleId = "lifecycle-" + suffix;
            String revision = "initial-" + suffix;
            String evidence = "evidence-initial-" + suffix;
        jdbc.update("insert into articles (id, title, category_id, severity, snippet) values (?, ?, ?, ?, ?)",
                articleId, "Initial R1", "lifecycle-cat", "high", "old evidence alpha");
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, created_by_sub)
                        values (?, ?, 1, 'shared', 'public', 'approved', cast(? as jsonb),
                                repeat('0',64), 'seed')
                        """, revision, articleId,
                "{\"id\":\"placeholder\",\"title\":\"Initial R1\",\"categoryId\":\"lifecycle-cat\","
                        + "\"severity\":\"high\",\"tags\":[],\"reads\":0,\"body\":[],\"iocs\":[],\"related\":[]}");
        jdbc.update("""
                        insert into article_evidence_snapshots
                            (id, article_revision_id, ordinal, source_uri, source_label, source_version,
                             content, content_sha256, created_by_sub)
                        values (?, ?, 0, 'https://source.invalid/r1', 'seed', 'r1', ?, repeat('1',64), 'seed')
                        """, evidence, revision, "old evidence alpha");
        jdbc.update("""
                        insert into article_revision_workflow_states
                            (article_revision_id, article_id, workspace_id, classification,
                             approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values (?, ?, 'shared', 'public', 'approved', 'active', 'seed', clock_timestamp(), 0)
                        """, revision, articleId);
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                            (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             to_approval_status, to_lifecycle_status, expected_state_version,
                             resulting_state_version, governance_state_version, actor_sub)
                        values (?, ?, ?, 'shared', 'public', 'migration_baseline', 'approved', 'active',
                                -1, 0, 0, 'seed')
                        """, "baseline-" + suffix, revision, articleId);
        jdbc.update("""
                        insert into article_governance
                            (article_id, workspace_id, classification, approval_status, read_policy, lifecycle_status,
                             current_revision_id, created_by_sub, approved_by_sub, approved_at)
                        select ?, 'shared', 'public', ws.approval_status, 'public', ws.lifecycle_status,
                               ws.article_revision_id, 'seed', ws.approved_by_sub, ws.approved_at
                        from article_revision_workflow_states ws where ws.article_revision_id = ?
                        """, articleId, revision);
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, 'shared', ?, 'editor')",
                "editor-membership-" + suffix, "editor-" + suffix);
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, 'shared', ?, 'admin')",
                "admin-membership-" + suffix, "admin-" + suffix);
            return new Fixture(articleId, revision, evidence);
        });
    }

    private static ArticleSnapshotInput snapshot(String title, String evidence) {
        return new ArticleSnapshotInput(title, "lifecycle-cat", "high", List.of("lifecycle"), "trusted-source",
                null, "author", "high", 0, null, evidence, List.of(), List.of(), List.of());
    }

    private static EvidenceSnapshotInput evidence(String content) {
        return new EvidenceSnapshotInput("https://source.invalid/r2", "trusted", "r2", content);
    }

    private static void insertCanonicalOldChunk(Fixture fixture) {
        String metadata = "{" +
                "\"article_id\":\"" + fixture.articleId() + "\"," +
                "\"article_revision_id\":\"" + fixture.initialRevisionId() + "\"," +
                "\"revision_id\":\"" + fixture.initialRevisionId() + "\"," +
                "\"article_revision_number\":1," +
                "\"revision_status\":\"approved\"," +
                "\"evidence_snapshot_id\":\"" + fixture.initialEvidenceId() + "\"," +
                "\"source_span_start\":0," +
                "\"source_span_end\":18," +
                "\"source_uri\":\"https://source.invalid/r1\"," +
                "\"source_version\":\"r1\"," +
                "\"source_key\":\"article:" + fixture.articleId() + "\"}";
        jdbc.update("insert into vector_store (content, metadata, embedding) values (?, cast(? as jsonb), ?::extensions.vector)",
                "old evidence alpha", metadata, vectorLiteral());
    }

    private static <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }

    private static String vectorLiteral() {
        return "[" + String.join(",", java.util.Collections.nCopies(1024, "0.1")) + "]";
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

    private record Fixture(String articleId, String initialRevisionId, String initialEvidenceId) {
    }
}
