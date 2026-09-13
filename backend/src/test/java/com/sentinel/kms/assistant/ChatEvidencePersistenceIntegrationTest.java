package com.sentinel.kms.assistant;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.EvidenceReferenceResolver;
import com.sentinel.kms.kb.KnowledgeAccessService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
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
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the actual PostgreSQL binding and immutable-history triggers, never a remote database. */
@Testcontainers
class ChatEvidencePersistenceIntegrationTest {

    private static final String SHA256 = "0".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
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
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> seed());
    }

    @Test
    void normalizedRowsRoundTripWhileJsonTamperingAndDirectMutationCannotChangeProvenance() {
        EvidenceReference candidate = new EvidenceReference(
                "article-1", "revision-1", 1, "evidence-1", 2, 8,
                "https://forged.invalid", "wrong", "draft");
        EvidenceReference canonical = new EvidenceReference(
                "article-1", "revision-1", 1, "evidence-1", 2, 8,
                "https://authoritative.example/report", "2026-09-06", "approved");
        EvidenceReferenceResolver resolver = (subject, reference) -> Optional.of(canonical);
        MessageCitationReferenceStore citationStore = new MessageCitationReferenceStore(jdbc, resolver);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        when(access.canReadSource("reader-1", "upload-1", "upload-revision-1", "upload-1:upload-revision-1"))
                .thenReturn(new AccessDecision(true, "SOURCE_PUBLIC", "shared", "public", "approved"));
        MessageSourceReferenceStore sourceStore = new MessageSourceReferenceStore(jdbc, access);
        SourceEvidenceReference upload = new SourceEvidenceReference(
                "upload-1", "upload-revision-1", "upload-1:upload-revision-1");

        assertThat(citationStore.bind("reader-1", "message-1", List.of(candidate))).containsExactly(canonical);
        assertThat(sourceStore.bind("reader-1", "message-1", List.of(upload))).containsExactly(upload);

        // This legacy compatibility column is intentionally not read by either normalized store.
        jdbc.update("update messages set citation_references = ?::jsonb where id = ?",
                "[{\"articleId\":\"forged-article\",\"articleRevisionId\":\"forged\"}]", "message-1");

        assertThat(citationStore.loadByMessageIds(List.of("message-1")))
                .containsEntry("message-1", List.of(canonical));
        assertThat(sourceStore.loadByMessageIds(List.of("message-1")))
                .containsEntry("message-1", List.of(upload));

        assertThatThrownBy(() -> jdbc.update("""
                        update message_citation_references set source_uri = ? where id = ?
                        """, "https://tampered.invalid", "citation:message-1:0"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                        update message_source_references set source_key = ? where id = ?
                        """, "forged-key", "source-reference:message-1:0"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from message_citation_references where id = ?",
                "citation:message-1:0")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from message_source_references where id = ?",
                "source-reference:message-1:0")).isInstanceOf(DataAccessException.class);

        // A real but non-current revision/evidence pair is rejected by the DB backstop.
        assertThatThrownBy(() -> jdbc.update("""
                        insert into message_citation_references (
                            id, message_id, ordinal, article_id, article_revision_id, evidence_snapshot_id,
                            source_span_start, source_span_end, source_uri, source_version)
                        values ('citation:message-1:1', 'message-1', 1, 'article-1', 'revision-stale',
                                'evidence-stale', 0, 5, 'https://authoritative.example/stale', '2026-08-01')
                        """))
                .isInstanceOf(DataAccessException.class);
    }

    private static void seed() {
        jdbc.update("insert into categories (id, label, abbr) values ('cat-1', 'Category', 'CAT')");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity)
                        values ('article-1', 'Article', 'cat-1', 'high')
                        """);
        insertRevision("revision-1", 1, "approved");
        insertRevision("revision-stale", 2, "approved");
        jdbc.update("""
                        insert into article_evidence_snapshots (
                            id, article_revision_id, ordinal, source_uri, source_label, source_version, content,
                            content_sha256, created_by_sub)
                        values ('evidence-1', 'revision-1', 0, 'https://authoritative.example/report',
                                'test', '2026-09-06', 'evidence text', ?, 'test'),
                               ('evidence-stale', 'revision-stale', 0, 'https://authoritative.example/stale',
                                'test', '2026-08-01', 'stale evidence', ?, 'test')
                        """, SHA256, SHA256);
        jdbc.update("""
                        insert into article_revision_workflow_states (
                            article_revision_id, article_id, workspace_id, classification,
                            approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values ('revision-1', 'article-1', 'shared', 'public',
                                'approved', 'active', 'test', clock_timestamp(), 0)
                        """);
        jdbc.update("""
                        insert into article_revision_lifecycle_events (
                            id, article_revision_id, article_id, workspace_id, classification, event_type,
                            to_approval_status, to_lifecycle_status, expected_state_version,
                            resulting_state_version, governance_state_version, actor_sub)
                        values ('lifecycle:chat-evidence:revision-1', 'revision-1', 'article-1', 'shared', 'public',
                                'migration_baseline', 'approved', 'active', -1, 0, 0, 'test')
                        """);
        jdbc.update("""
                        insert into article_governance (
                            article_id, workspace_id, classification, approval_status, read_policy,
                            lifecycle_status, current_revision_id, created_by_sub, approved_by_sub, approved_at)
                        select 'article-1', 'shared', 'public', ws.approval_status, 'public',
                               ws.lifecycle_status, ws.article_revision_id, 'test', ws.approved_by_sub, ws.approved_at
                        from article_revision_workflow_states ws
                        where ws.article_revision_id = 'revision-1'
                        """);
        jdbc.update("insert into conversations (id, user_sub) values ('conversation-1', 'reader-1')");
        jdbc.update("""
                        insert into messages (id, conversation_id, role, content)
                        values ('message-1', 'conversation-1', 'assistant', 'answer')
                        """);
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, workspace_id, access_scope, classification,
                            source_id, source_revision_id, source_key, is_demo)
                        values ('job-1', 'Upload', 'upload', 'indexed', 'shared', 'public_shared', 'public',
                                'upload-1', 'upload-revision-1', 'upload-1:upload-revision-1', false)
                        """);
    }

    private static void insertRevision(String id, int number, String status) {
        jdbc.update("""
                        insert into article_revisions (
                            id, article_id, revision_number, workspace_id, classification, status,
                            snapshot, content_sha256, created_by_sub)
                        values (?, 'article-1', ?, 'shared', 'public', ?, '{}'::jsonb, ?, 'test')
                        """, id, number, status, SHA256);
    }

    private static Path projectRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("supabase/migrations"))) {
            throw new IllegalStateException("expected Maven execution from backend");
        }
        return root;
    }
}
