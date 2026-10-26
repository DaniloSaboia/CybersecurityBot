package com.sentinel.kms.kb;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-level regression guards for provenance bindings that cannot be
 * safely enforced by application read predicates alone.
 */
@Testcontainers
class KnowledgeGovernanceBindingMigrationIntegrationTest {

    private static final String SHA256 = "0".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
        seedBoundRevisionFixture();
    }

    @Test
    void currentRevisionMustBelongToTheGovernedArticleWorkspaceAndClassification() {
        assertThatThrownBy(() -> jdbc.update("""
                        update article_governance set current_revision_id = ? where article_id = ?
                        """, "rev-b-shared-public", "article-a"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("""
                        update article_governance set current_revision_id = ? where article_id = ?
                        """, "rev-a-other-public", "article-a"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("""
                        update article_governance set current_revision_id = ? where article_id = ?
                        """, "rev-a-shared-internal", "article-a"))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> jdbc.update("""
                        update article_governance set current_revision_id = ? where article_id = ?
                        """, "rev-a-shared-public", "article-a"))
                .doesNotThrowAnyException();
    }

    @Test
    void directReadPolicyChangeWithoutCasAndLifecycleEventIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                        update article_governance
                        set read_policy = 'workspace_admin'
                        where article_id = 'article-a'
                        """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lifecycleEventForOneRevisionCannotAuthorizePointerMoveToAnotherRevisionAtSameGovernanceVersion() {
        String suffix = UUID.randomUUID().toString();
        String articleId = "pointer-event-binding-" + suffix;
        String revisionB = "pointer-event-binding-b-" + suffix;
        String revisionC = "pointer-event-binding-c-" + suffix;

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("""
                            insert into articles (id, title, category_id, severity)
                            values (?, 'Pointer-event binding', 'cat', 'high')
                            """, articleId);
            insertRevision(revisionB, articleId, 1, "shared", "public");
            insertRevision(revisionC, articleId, 2, "shared", "public");
            insertEvidence("pointer-event-binding-evidence-b-" + suffix, revisionB);
            insertEvidence("pointer-event-binding-evidence-c-" + suffix, revisionC);
            insertApprovedWorkflow(revisionB, articleId);
            insertApprovedWorkflow(revisionC, articleId);

            // Revision B consumes governance version 1. Revision C has its
            // own valid baseline event, but not one for version 1. A bare
            // article-level event must not authorize moving the pointer to C.
            insertLifecycleEvent("pointer-event-binding-event-b-" + suffix, revisionB, articleId, 1);
            insertLifecycleEvent("pointer-event-binding-event-c-" + suffix, revisionC, articleId, 0);
            jdbc.update("""
                            insert into article_governance (
                                article_id, workspace_id, classification, approval_status, read_policy,
                                lifecycle_status, current_revision_id, created_by_sub, approved_by_sub, approved_at)
                            values (?, 'shared', 'public', 'approved', 'public', 'active', ?,
                                    'fixture', 'fixture', timestamptz '2026-01-01 00:00:00+00')
                            """, articleId, revisionB);

            jdbc.update("""
                            update article_governance
                            set current_revision_id = ?, state_version = state_version + 1
                            where article_id = ?
                            """, revisionC, articleId);
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void messageCitationReferencesAreAppendOnly() {
        assertThatThrownBy(() -> jdbc.update("""
                        update message_citation_references set source_uri = ? where id = ?
                        """, "https://changed.invalid", "citation-1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from message_citation_references where id = ?", "citation-1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void messageCitationMustResolveToTheCurrentApprovedArticleRevisionExactEvidenceAndSpan() {
        assertThatThrownBy(() -> insertCitation("citation-wrong-article", "article-a",
                "rev-b-shared-public", "evidence-a-1", 0, 5,
                "https://source.invalid", "1"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertCitation("citation-wrong-evidence", "article-a",
                "rev-a-shared-public", "evidence-b-1", 0, 5,
                "https://source.invalid", "1"))
                .isInstanceOf(DataAccessException.class);

        insertRevision("rev-a-noncurrent", "article-a", 4, "shared", "public");
        jdbc.update("""
                        insert into article_evidence_snapshots (
                            id, article_revision_id, ordinal, source_uri, source_label, source_version, content,
                            content_sha256, created_by_sub)
                        values ('evidence-a-noncurrent', 'rev-a-noncurrent', 0, 'https://source.invalid',
                                'test', '1', 'source text', ?, 'test')
                        """, SHA256);
        assertThatThrownBy(() -> insertCitation("citation-stale-revision", "article-a",
                "rev-a-noncurrent", "evidence-a-noncurrent", 0, 5,
                "https://source.invalid", "1"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertCitation("citation-bad-span", "article-a",
                "rev-a-shared-public", "evidence-a-1", 0, 999,
                "https://source.invalid", "1"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertCitation("citation-tampered-uri", "article-a",
                "rev-a-shared-public", "evidence-a-1", 0, 5,
                "https://tampered.invalid", "1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void directCitationDeletionIsRejectedButMessageDeletionCascadesItsImmutableReferences() {
        jdbc.update("""
                        insert into messages (id, conversation_id, role, content)
                        values ('message-cascade', 'conversation-1', 'assistant', 'answer')
                        """);
        insertCitationForMessage("citation-cascade", "message-cascade", 0, "article-a",
                "rev-a-shared-public", "evidence-a-1", 0, 5,
                "https://source.invalid", "1");

        jdbc.update("delete from messages where id = 'message-cascade'");

        assertThat(jdbc.queryForObject("select count(*) from message_citation_references where id = ?",
                Integer.class, "citation-cascade")).isZero();
    }

    @Test
    void ingestJobReadFunctionUsesOnlyLivePersistedScopeOwnerAndMembershipFacts() {
        assertCanRead("job-owner", "owner-sub", true);
        assertCanRead("job-owner", "other-sub", false);
        assertCanRead("job-public", "anonymous-reader", true);
        assertCanRead("job-internal", "viewer-sub", true);
        assertCanRead("job-confidential", "viewer-sub", false);
        assertCanRead("job-confidential", "editor-sub", true);
        assertCanRead("job-restricted", "editor-sub", false);
        assertCanRead("job-restricted", "admin-sub", true);
        assertCanRead("job-tombstoned-public", "anonymous-reader", false);
        assertCanRead("job-cancelled-public", "anonymous-reader", false);
        assertCanRead("job-public", "", false);
        assertCanRead("missing-job", "owner-sub", false);
    }

    @Test
    void ingestJobReadFunctionIsInvokerScopedAndNotDataApiCallable() {
        assertThat(jdbc.queryForObject("""
                        select p.prosecdef
                        from pg_proc p
                        where p.oid = 'public.can_read_ingest_job(text,text)'::regprocedure
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select has_function_privilege('anon',
                            'public.can_read_ingest_job(text,text)'::regprocedure, 'EXECUTE')
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select has_function_privilege('authenticated',
                            'public.can_read_ingest_job(text,text)'::regprocedure, 'EXECUTE')
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select p.prosecdef
                        from pg_proc p
                        where p.oid = 'public.can_read_ingest_source(text,text,text,text)'::regprocedure
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select has_function_privilege('anon',
                            'public.can_read_ingest_source(text,text,text,text)'::regprocedure, 'EXECUTE')
                        """, Boolean.class)).isFalse();
    }

    @Test
    void messageSourceReferenceMustBindAReadableLiveCanonicalUploadTuple() {
        insertMessageSourceReference("source-reference-public", "message-1", 0,
                "source-public", "revision-public", "source-public:revision-public");

        assertThatThrownBy(() -> insertMessageSourceReference("source-reference-private", "message-1", 1,
                "source-private", "revision-private", "source-private:revision-private"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertMessageSourceReference("source-reference-forged", "message-1", 2,
                "source-public", "revision-public", "source-private:revision-private"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ingestSourceReadFunctionUsesTheExactLiveCanonicalTuple() {
        assertCanReadSource("source-public", "revision-public", "source-public:revision-public", "user-1", true);
        assertCanReadSource("source-owner", "revision-owner", "source-owner:revision-owner", "user-1", true);
        assertCanReadSource("source-internal", "revision-internal", "source-internal:revision-internal", "user-1", true);
        assertCanReadSource("source-confidential", "revision-confidential", "source-confidential:revision-confidential",
                "user-1", false);
        assertCanReadSource("source-private", "revision-private", "source-private:revision-private", "user-1", false);
        assertCanReadSource("source-tombstone", "revision-tombstone", "source-tombstone:revision-tombstone",
                "user-1", false);
        assertCanReadSource("source-public", "revision-public", "forged", "user-1", false);
    }

    @Test
    void directSourceReferenceDeletionIsRejectedButMessageDeletionCascadesIt() {
        jdbc.update("""
                        insert into messages (id, conversation_id, role, content)
                        values ('message-source-cascade', 'conversation-1', 'assistant', 'answer')
                        """);
        insertMessageSourceReference("source-reference-cascade", "message-source-cascade", 0,
                "source-public", "revision-public", "source-public:revision-public");

        assertThatThrownBy(() -> jdbc.update(
                "delete from message_source_references where id = ?", "source-reference-cascade"))
                .isInstanceOf(DataAccessException.class);

        jdbc.update("delete from messages where id = 'message-source-cascade'");
        assertThat(jdbc.queryForObject("select count(*) from message_source_references where id = ?",
                Integer.class, "source-reference-cascade")).isZero();
    }

    private static void seedBoundRevisionFixture() {
        transaction.executeWithoutResult(status -> seedBoundRevisionFixtureInTransaction());
    }

    /**
     * The workflow projection and its baseline ledger entry must commit as one
     * unit.  The production invariant is deferred specifically to permit the
     * normal state-then-event write order, not to permit a committed state
     * without evidence of its transition.
     */
    private static void seedBoundRevisionFixtureInTransaction() {
        jdbc.update("insert into categories (id, label, abbr) values ('cat', 'Category', 'CAT')");
        jdbc.update("insert into workspaces (id, name) values ('other', 'Other workspace')");
        jdbc.update("""
                        insert into articles (id, title, category_id, severity)
                        values ('article-a', 'Article A', 'cat', 'high'),
                               ('article-b', 'Article B', 'cat', 'high')
                        """);
        insertRevision("rev-a-shared-public", "article-a", 1, "shared", "public");
        insertRevision("rev-b-shared-public", "article-b", 1, "shared", "public");
        insertRevision("rev-a-other-public", "article-a", 2, "other", "public");
        insertRevision("rev-a-shared-internal", "article-a", 3, "shared", "internal");
        jdbc.update("""
                        insert into article_evidence_snapshots (
                            id, article_revision_id, ordinal, source_uri, source_label, source_version, content,
                            content_sha256, created_by_sub)
                        values ('evidence-a-1', 'rev-a-shared-public', 0, 'https://source.invalid',
                                'test', '1', 'source text', ?, 'test')
                        """, SHA256);
        jdbc.update("""
                        insert into article_revision_workflow_states (
                            article_revision_id, article_id, workspace_id, classification,
                            approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values ('rev-a-shared-public', 'article-a', 'shared', 'public',
                                'approved', 'active', 'test', clock_timestamp(), 0)
                        """);
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                            (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             to_approval_status, to_lifecycle_status, expected_state_version,
                             resulting_state_version, governance_state_version, actor_sub)
                        values ('lifecycle:binding-fixture:rev-a-shared-public',
                                'rev-a-shared-public', 'article-a', 'shared', 'public', 'migration_baseline',
                                'approved', 'active', -1, 0, 0, 'test')
                        """);
        jdbc.update("""
                        insert into article_governance (
                            article_id, workspace_id, classification, approval_status, read_policy,
                            lifecycle_status, current_revision_id, created_by_sub, approved_by_sub, approved_at)
                        select 'article-a', 'shared', 'public', ws.approval_status, 'public',
                               ws.lifecycle_status, ws.article_revision_id, 'test', ws.approved_by_sub, ws.approved_at
                        from article_revision_workflow_states ws
                        where ws.article_revision_id = 'rev-a-shared-public'
                        """);
        jdbc.update("""
                        insert into article_evidence_snapshots (
                            id, article_revision_id, ordinal, source_uri, source_label, source_version, content,
                            content_sha256, created_by_sub)
                        values ('evidence-b-1', 'rev-b-shared-public', 0, 'https://other.invalid',
                                'test', '1', 'source text', ?, 'test')
                        """, SHA256);
        jdbc.update("insert into conversations (id, user_sub) values ('conversation-1', 'user-1')");
        jdbc.update("""
                        insert into messages (id, conversation_id, role, content)
                        values ('message-1', 'conversation-1', 'assistant', 'answer')
                        """);
        jdbc.update("""
                        insert into message_citation_references (
                            id, message_id, ordinal, article_id, article_revision_id, evidence_snapshot_id,
                            source_span_start, source_span_end, source_uri, source_version)
                        values ('citation-1', 'message-1', 0, 'article-a', 'rev-a-shared-public', 'evidence-a-1',
                                0, 5, 'https://source.invalid', '1')
                        """);

        jdbc.update("""
                        insert into workspace_memberships (id, workspace_id, subject, role)
                        values ('member-viewer', 'shared', 'viewer-sub', 'viewer'),
                               ('member-editor', 'shared', 'editor-sub', 'editor'),
                               ('member-admin', 'shared', 'admin-sub', 'admin'),
                               ('member-message-owner', 'shared', 'user-1', 'viewer')
                        """);
        insertJob("job-owner", "owner-sub", "owner_private", "restricted", "shared", false);
        insertJob("job-public", "connector-sub", "public_shared", "public", "shared", false);
        insertJob("job-internal", null, "workspace", "internal", "shared", false);
        insertJob("job-confidential", null, "workspace", "confidential", "shared", false);
        insertJob("job-restricted", null, "workspace", "restricted", "shared", false);
        insertJob("job-tombstoned-public", null, "public_shared", "public", "shared", true);
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, workspace_id, access_scope, classification,
                            is_demo, cancelled_at)
                        values ('job-cancelled-public', 'job-cancelled-public', 'test', 'complete', NULL,
                                'shared', 'public_shared', 'public', false, now())
                        """);
        insertSourceJob("job-source-public", "source-public", "revision-public", "source-public:revision-public",
                "other-user", "public_shared", "public", "shared", false);
        insertSourceJob("job-source-private", "source-private", "revision-private", "source-private:revision-private",
                "other-user", "owner_private", "restricted", "shared", false);
        insertSourceJob("job-source-owner", "source-owner", "revision-owner", "source-owner:revision-owner",
                "user-1", "owner_private", "restricted", "shared", false);
        insertSourceJob("job-source-internal", "source-internal", "revision-internal", "source-internal:revision-internal",
                null, "workspace", "internal", "shared", false);
        insertSourceJob("job-source-confidential", "source-confidential", "revision-confidential",
                "source-confidential:revision-confidential", null, "workspace", "confidential", "shared", false);
        insertSourceJob("job-source-tombstone", "source-tombstone", "revision-tombstone",
                "source-tombstone:revision-tombstone", "other-user", "public_shared", "public", "shared", true);
    }

    private static void insertRevision(String id, String articleId, int number, String workspaceId,
                                       String classification) {
        jdbc.update("""
                        insert into article_revisions (
                            id, article_id, revision_number, workspace_id, classification, status,
                            snapshot, content_sha256, created_by_sub)
                        values (?, ?, ?, ?, ?, 'approved', '{}'::jsonb, ?, 'test')
                        """, id, articleId, number, workspaceId, classification, SHA256);
    }

    private static void insertEvidence(String id, String revisionId) {
        jdbc.update("""
                        insert into article_evidence_snapshots (
                            id, article_revision_id, ordinal, source_uri, source_label, source_version, content,
                            content_sha256, created_by_sub)
                        values (?, ?, 0, 'https://source.invalid', 'test', '1', 'source text', ?, 'test')
                        """, id, revisionId, SHA256);
    }

    private static void insertApprovedWorkflow(String revisionId, String articleId) {
        jdbc.update("""
                        insert into article_revision_workflow_states (
                            article_revision_id, article_id, workspace_id, classification,
                            approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values (?, ?, 'shared', 'public', 'approved', 'active', 'fixture',
                                timestamptz '2026-01-01 00:00:00+00', 0)
                        """, revisionId, articleId);
    }

    private static void insertLifecycleEvent(String id, String revisionId, String articleId,
                                             long governanceStateVersion) {
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                            (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             to_approval_status, to_lifecycle_status, expected_state_version,
                             resulting_state_version, governance_state_version, actor_sub)
                        values (?, ?, ?, 'shared', 'public', 'migration_baseline', 'approved', 'active',
                                -1, 0, ?, 'fixture')
                        """, id, revisionId, articleId, governanceStateVersion);
    }

    private static void insertJob(String id, String ownerSub, String accessScope, String classification,
                                  String workspaceId, boolean tombstoned) {
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, workspace_id, access_scope, classification,
                            is_demo, deleted_at)
                        values (?, ?, 'test', 'complete', ?, ?, ?, ?, false, ?)
                        """, id, id, ownerSub, workspaceId, accessScope, classification,
                tombstoned ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private static void insertSourceJob(String id, String sourceId, String revisionId, String sourceKey,
                                        String ownerSub, String accessScope, String classification,
                                        String workspaceId, boolean tombstoned) {
        jdbc.update("""
                        insert into ingest_jobs (
                            id, name, source, stage, owner_sub, workspace_id, access_scope, classification,
                            source_id, source_revision_id, source_key, is_demo, deleted_at)
                        values (?, ?, 'test', 'indexed', ?, ?, ?, ?, ?, ?, ?, false, ?)
                        """, id, id, ownerSub, workspaceId, accessScope, classification,
                sourceId, revisionId, sourceKey,
                tombstoned ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private static void assertCanRead(String jobId, String subject, boolean expected) {
        Boolean actual = jdbc.queryForObject("select public.can_read_ingest_job(?, ?)", Boolean.class,
                jobId, subject);
        assertThat(actual).isEqualTo(expected);
    }

    private static void assertCanReadSource(String sourceId, String revisionId, String sourceKey,
                                            String subject, boolean expected) {
        Boolean actual = jdbc.queryForObject(
                "select public.can_read_ingest_source(?, ?, ?, ?)", Boolean.class,
                sourceId, revisionId, sourceKey, subject);
        assertThat(actual).isEqualTo(expected);
    }

    private static void insertCitation(String id, String articleId, String revisionId, String evidenceId,
                                       int spanStart, int spanEnd, String sourceUri, String sourceVersion) {
        insertCitationForMessage(id, "message-1", 1, articleId, revisionId, evidenceId,
                spanStart, spanEnd, sourceUri, sourceVersion);
    }

    private static void insertCitationForMessage(String id, String messageId, int ordinal, String articleId,
                                                 String revisionId, String evidenceId, int spanStart, int spanEnd,
                                                 String sourceUri, String sourceVersion) {
        jdbc.update("""
                        insert into message_citation_references (
                            id, message_id, ordinal, article_id, article_revision_id, evidence_snapshot_id,
                            source_span_start, source_span_end, source_uri, source_version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, messageId, ordinal, articleId, revisionId, evidenceId,
                spanStart, spanEnd, sourceUri, sourceVersion);
    }

    private static void insertMessageSourceReference(String id, String messageId, int ordinal,
                                                      String sourceId, String sourceRevisionId, String sourceKey) {
        jdbc.update("""
                        insert into message_source_references (
                            id, message_id, ordinal, source_id, source_revision_id, source_key)
                        values (?, ?, ?, ?, ?, ?)
                        """, id, messageId, ordinal, sourceId, sourceRevisionId, sourceKey);
    }

    private static Path projectRoot() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve("supabase/migrations"))) {
            throw new IllegalStateException("expected Maven execution from backend");
        }
        return root;
    }
}
