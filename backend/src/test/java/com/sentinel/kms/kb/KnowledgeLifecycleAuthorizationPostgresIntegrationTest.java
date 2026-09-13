package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
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
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL regression for lifecycle write authorization and the
 * article-level optimistic state fence.  It deliberately uses persisted
 * memberships rather than JWT/user metadata stand-ins.
 */
@Testcontainers
class KnowledgeLifecycleAuthorizationPostgresIntegrationTest {

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
        jdbc.update("insert into categories (id, label, abbr) values ('lifecycle-auth-cat', 'Lifecycle Auth', 'LA')");

        ObjectMapper mapper = new ObjectMapper();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, mapper);
        KnowledgeAccessService access = new KnowledgeAccessService(jdbc, audit);
        lifecycle = new KnowledgeLifecycleService(jdbc, mapper, access, audit);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void outsiderWorkspaceAdminAndSharedViewerAreDeniedWithoutRevisionOrStateWrites() {
        Fixture fixture = fixture();
        LifecycleCounts before = counts(fixture.articleId());

        assertThatThrownBy(() -> inTransaction(() -> lifecycle.createDraft(fixture.outsiderSubject(),
                fixture.articleId(), draftRequest(0))))
                .isInstanceOf(AccessDeniedException.class);
        assertUnchanged(fixture.articleId(), before);

        assertThatThrownBy(() -> inTransaction(() -> lifecycle.createDraft(fixture.viewerSubject(),
                fixture.articleId(), draftRequest(0))))
                .isInstanceOf(AccessDeniedException.class);
        assertUnchanged(fixture.articleId(), before);
    }

    @Test
    void staleExpectedStateVersionReturnsConflictAndDoesNotCreateAnotherDraft() {
        Fixture fixture = fixture();

        inTransaction(() -> lifecycle.createDraft(fixture.editorSubject(), fixture.articleId(), draftRequest(0)));
        LifecycleCounts afterAcceptedDraft = counts(fixture.articleId());
        assertThat(afterAcceptedDraft.governanceVersion()).isEqualTo(1);
        assertThat(afterAcceptedDraft.revisions()).isEqualTo(2);
        assertThat(afterAcceptedDraft.workflowStates()).isEqualTo(2);
        assertThat(afterAcceptedDraft.events()).isEqualTo(2);

        assertThatThrownBy(() -> inTransaction(() -> lifecycle.createDraft(fixture.editorSubject(),
                fixture.articleId(), draftRequest(0))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertUnchanged(fixture.articleId(), afterAcceptedDraft);
        assertThat(jdbc.queryForObject("select current_revision_id from article_governance where article_id = ?",
                String.class, fixture.articleId())).isEqualTo(fixture.initialRevisionId());
    }

    @Test
    void draftBasedOnAnOldCurrentRevisionCannotPublishAfterAnotherRevisionWins() {
        Fixture fixture = fixture();

        String stale = inTransaction(() -> lifecycle.createDraft(fixture.editorSubject(), fixture.articleId(),
                draftRequest(0)).revision().id());
        inTransaction(() -> lifecycle.submit(fixture.editorSubject(), fixture.articleId(), stale,
                new TransitionRequest(1, null, null, "READY_FOR_REVIEW")));

        String successor = inTransaction(() -> lifecycle.createDraft(fixture.editorSubject(), fixture.articleId(),
                draftRequest(2)).revision().id());
        inTransaction(() -> lifecycle.submit(fixture.editorSubject(), fixture.articleId(), successor,
                new TransitionRequest(3, null, null, "READY_FOR_REVIEW")));
        inTransaction(() -> lifecycle.approve(fixture.adminSubject(), fixture.articleId(), successor,
                new TransitionRequest(4, null, null, "APPROVED")));

        assertThatThrownBy(() -> inTransaction(() -> lifecycle.approve(fixture.adminSubject(), fixture.articleId(),
                stale, new TransitionRequest(5, null, null, "STALE_DRAFT"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("select current_revision_id from article_governance where article_id = ?",
                String.class, fixture.articleId())).isEqualTo(successor);
        assertThat(jdbc.queryForObject("select approval_status from article_revision_workflow_states "
                + "where article_revision_id = ?", String.class, stale)).isEqualTo("pending_review");
    }

    @Test
    void membershipRevokedAfterPreflightCannotCommitTheFinalGovernanceMutation() {
        Fixture fixture = fixture();
        LifecycleCounts before = counts(fixture.articleId());
        installInTransactionMembershipRevocationTrigger();
        try {
            assertThatThrownBy(() -> transaction.execute(status -> {
                jdbc.queryForObject("select set_config(?, ?, true)", String.class,
                        "test.lifecycle.article_id", fixture.articleId());
                jdbc.queryForObject("select set_config(?, ?, true)", String.class,
                        "test.lifecycle.subject", fixture.editorSubject());
                return lifecycle.createDraft(fixture.editorSubject(), fixture.articleId(), draftRequest(0));
            })).isInstanceOf(AccessDeniedException.class);
            // The trigger revokes the membership only after the preflight role
            // lookup. The final guarded governance UPDATE must reject it and
            // roll back revision/evidence/workflow/event writes together.
            assertUnchanged(fixture.articleId(), before);
            assertThat(jdbc.queryForObject("""
                            select active from workspace_memberships
                            where workspace_id = 'shared' and subject = ?
                            """, Boolean.class, fixture.editorSubject())).isTrue();
        } finally {
            jdbc.execute("drop trigger if exists lifecycle_fixture_revoke_member on article_revision_workflow_states");
            jdbc.execute("drop function if exists public.lifecycle_fixture_revoke_member()");
        }
    }

    private static void installInTransactionMembershipRevocationTrigger() {
        jdbc.execute("""
                        create function public.lifecycle_fixture_revoke_member()
                        returns trigger
                        language plpgsql
                        as $$
                        begin
                            if new.article_id = current_setting('test.lifecycle.article_id', true) then
                                update workspace_memberships
                                set active = false
                                where workspace_id = new.workspace_id
                                  and subject = current_setting('test.lifecycle.subject', true);
                            end if;
                            return new;
                        end;
                        $$
                        """);
        jdbc.execute("""
                        create trigger lifecycle_fixture_revoke_member
                        after insert on article_revision_workflow_states
                        for each row execute function public.lifecycle_fixture_revoke_member()
                        """);
    }

    private static Fixture fixture() {
        return inTransaction(() -> {
            String suffix = UUID.randomUUID().toString();
            String articleId = "lifecycle-auth-" + suffix;
            String initialRevisionId = "lifecycle-auth-revision-" + suffix;
            String initialEvidenceId = "lifecycle-auth-evidence-" + suffix;
            String editor = "lifecycle-editor-" + suffix;
            String viewer = "lifecycle-viewer-" + suffix;
            String admin = "lifecycle-admin-" + suffix;
            String outsider = "lifecycle-outsider-" + suffix;
            String otherWorkspace = "lifecycle-other-" + suffix;

        jdbc.update("insert into articles (id, title, category_id, severity, snippet) values (?, ?, ?, ?, ?)",
                articleId, "Initial governed article", "lifecycle-auth-cat", "high", "initial evidence");
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, created_by_sub)
                        values (?, ?, 1, 'shared', 'public', 'approved', cast(? as jsonb),
                                repeat('0', 64), 'seed')
                        """, initialRevisionId, articleId, initialSnapshot());
        jdbc.update("""
                        insert into article_evidence_snapshots
                            (id, article_revision_id, ordinal, source_uri, source_label, source_version,
                             content, content_sha256, created_by_sub)
                        values (?, ?, 0, 'https://source.invalid/initial', 'fixture', 'r1',
                                'initial evidence', repeat('1', 64), 'seed')
                        """, initialEvidenceId, initialRevisionId);
        jdbc.update("""
                        insert into article_revision_workflow_states
                            (article_revision_id, article_id, workspace_id, classification,
                             approval_status, lifecycle_status, approved_by_sub, approved_at, state_version)
                        values (?, ?, 'shared', 'public', 'approved', 'active', 'seed', clock_timestamp(), 0)
                        """, initialRevisionId, articleId);
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                            (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             to_approval_status, to_lifecycle_status, expected_state_version,
                             resulting_state_version, governance_state_version, actor_sub)
                        values (?, ?, ?, 'shared', 'public', 'migration_baseline', 'approved', 'active',
                                -1, 0, 0, 'seed')
                        """, "lifecycle-auth-baseline-" + suffix, initialRevisionId, articleId);
        jdbc.update("""
                        insert into article_governance
                            (article_id, workspace_id, classification, approval_status, read_policy, lifecycle_status,
                             current_revision_id, created_by_sub, approved_by_sub, approved_at)
                        select ?, 'shared', 'public', ws.approval_status, 'public', ws.lifecycle_status,
                               ws.article_revision_id, 'seed', ws.approved_by_sub, ws.approved_at
                        from article_revision_workflow_states ws where ws.article_revision_id = ?
                        """, articleId, initialRevisionId);
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, 'shared', ?, 'editor')",
                "lifecycle-editor-membership-" + suffix, editor);
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, 'shared', ?, 'viewer')",
                "lifecycle-viewer-membership-" + suffix, viewer);
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, 'shared', ?, 'admin')",
                "lifecycle-admin-membership-" + suffix, admin);
        jdbc.update("insert into workspaces (id, name) values (?, ?)", otherWorkspace, "Other workspace");
        jdbc.update("insert into workspace_memberships (id, workspace_id, subject, role) values (?, ?, ?, 'admin')",
                "lifecycle-outsider-membership-" + suffix, otherWorkspace, outsider);
            return new Fixture(articleId, initialRevisionId, editor, viewer, admin, outsider);
        });
    }

    private static CreateRevisionRequest draftRequest(long expectedStateVersion) {
        return new CreateRevisionRequest(expectedStateVersion,
                new ArticleSnapshotInput("Proposed governed article", "lifecycle-auth-cat", "high",
                        List.of("lifecycle"), "trusted-source", null, "author", "high", 0,
                        null, "proposed evidence", List.of(), List.of(), List.of()),
                List.of(new EvidenceSnapshotInput("https://source.invalid/proposed", "trusted", "r2",
                        "proposed evidence")), null, null);
    }

    private static LifecycleCounts counts(String articleId) {
        return new LifecycleCounts(
                jdbc.queryForObject("select state_version from article_governance where article_id = ?", Long.class,
                        articleId),
                jdbc.queryForObject("select count(*) from article_revisions where article_id = ?", Integer.class,
                        articleId),
                jdbc.queryForObject("select count(*) from article_revision_workflow_states where article_id = ?",
                        Integer.class, articleId),
                jdbc.queryForObject("select count(*) from article_revision_lifecycle_events where article_id = ?",
                        Integer.class, articleId),
                jdbc.queryForObject("select count(*) from article_revision_reindex_outbox where article_id = ?",
                        Integer.class, articleId));
    }

    private static void assertUnchanged(String articleId, LifecycleCounts expected) {
        assertThat(counts(articleId)).isEqualTo(expected);
    }

    private static <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static String initialSnapshot() {
        return "{\"id\":\"placeholder\",\"title\":\"Initial governed article\","
                + "\"categoryId\":\"lifecycle-auth-cat\",\"severity\":\"high\",\"tags\":[],"
                + "\"reads\":0,\"body\":[],\"iocs\":[],\"related\":[]}";
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

    private record Fixture(String articleId, String initialRevisionId, String editorSubject,
                           String viewerSubject, String adminSubject, String outsiderSubject) {
    }

    private record LifecycleCounts(long governanceVersion, int revisions, int workflowStates, int events,
                                   int reindexOutbox) {
    }
}
