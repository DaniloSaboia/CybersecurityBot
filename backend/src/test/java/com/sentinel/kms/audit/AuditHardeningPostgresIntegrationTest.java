package com.sentinel.kms.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL contract for audit hardening.  The RED reproduction starts
 * from the governance schema with its old generic audit trigger deliberately
 * removed: the additive hardening migration must independently restore every
 * protection asserted below.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditHardeningPostgresIntegrationTest {

    private static final String WORKSPACE = "audit-workspace";
    private static final String SECOND_WORKSPACE = "other-audit-workspace";
    private static final String PRIMARY_EVENT_ID = "audit-event-primary";
    private static final String SECONDARY_EVENT_ID = "audit-event-secondary";
    private static final String RUNTIME_WRITER = "audit_runtime_writer";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void bootstrapGovernanceSchemaThenApplyAuditHardening() throws Exception {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
        Path root = projectRoot();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        applyMigrationsThroughGovernance(root);

        // The old migration's generic trigger is intentionally absent.  This
        // proves the new migration cannot merely rely on historical state.
        jdbc.execute("drop trigger if exists immutable_knowledge_audit_events on public.knowledge_audit_events");
        jdbc.execute(Files.readString(root.resolve("supabase/migrations/20260905050000_audit_hardening.sql")));
        seedMemberships();
        provisionNonOwnerRuntimeWriter();
    }

    @Test
    @Order(1)
    void auditRowsRemainAppendOnlyEvenAfterTheLegacyTriggerIsRemoved() {
        String id = UUID.randomUUID().toString();
        insertEvent(id, "success", "{}");

        assertThatThrownBy(() -> jdbc.update(
                "update knowledge_audit_events set action = ? where id = ?", "AUDIT_MUTATED", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from knowledge_audit_events where id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("truncate table knowledge_audit_events"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_audit_events where id = ?", Long.class, id))
                .isEqualTo(1L);
    }

    @Test
    @Order(2)
    void metadataIsACompactAllowlistedScalarObjectAndConflictIsAnOutcome() {
        assertThatCode(() -> insertEvent(UUID.randomUUID().toString(), "conflict", """
                {"source_type":"upload","size_bytes":42,"retained":true,
                 "failure_code":"NETWORK_TIMEOUT","from_revision_id":"rev:1",
                 "review_due_at":"2026-09-06T12:30:00Z","created":false}
                """))
                .doesNotThrowAnyException();

        // Instant.toString() retains fractional precision whenever non-zero.
        assertThatCode(() -> insertEvent(UUID.randomUUID().toString(), "success", """
                {"review_due_at":"2026-09-06T12:30:00.123456789Z",
                 "expires_at":"2026-10-06T12:30:00.5Z"}
                """))
                .doesNotThrowAnyException();

        assertMetadataRejected("{\"content\":\"AUDIT_DOCUMENT_CANARY\"}");
        assertMetadataRejected("{\"source_type\":{\"nested\":\"upload\"}}");
        assertMetadataRejected("{\"size_bytes\":1.5}");
        assertMetadataRejected("{\"source_type\":\"https://secret.example/token\"}");
        assertMetadataRejected("{\"size_bytes\":" + "1" + "0".repeat(4096) + "}");
    }

    @Test
    @Order(3)
    void directClientRolesHaveNoAuditTableOrAuthorizationFunctionAccess() {
        assertThat(functionExists()).isTrue();
        for (String role : new String[]{"anon", "authenticated"}) {
            assertThat(hasTablePrivilege(role, "SELECT")).isFalse();
            assertThat(hasTablePrivilege(role, "INSERT")).isFalse();
            assertThat(hasTablePrivilege(role, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege(role, "DELETE")).isFalse();
            assertThat(hasTablePrivilege(role, "TRUNCATE")).isFalse();
            assertThat(hasFunctionPrivilege(role)).isFalse();
            assertThat(hasFunctionPrivilege(role, "public.audit_metadata_is_safe(jsonb)")).isFalse();
            assertThat(hasFunctionPrivilege(role, "public.validate_knowledge_audit_event_insert()")).isFalse();
            assertClientRoleCannotReadTableOrCallAuthorityFunction(role);
        }
        assertThat(jdbc.queryForObject("""
                        select not prosecdef
                        from pg_proc
                        where oid = 'public.can_read_workspace_audit(text,text)'::regprocedure
                        """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select to_regclass('public.idx_knowledge_audit_workspace_cursor') is not null", Boolean.class))
                .isTrue();
    }

    @Test
    @Order(4)
    void workspaceAuditAuthorityComesFromPersistedActiveOwnerOrAdminMembership() {
        assertThat(functionExists()).isTrue();
        assertThat(canRead(WORKSPACE, "owner-sub")).isTrue();
        assertThat(canRead(WORKSPACE, "admin-sub")).isTrue();
        assertThat(canRead(WORKSPACE, "editor-sub")).isFalse();
        assertThat(canRead(WORKSPACE, "viewer-sub")).isFalse();
        assertThat(canRead(WORKSPACE, "inactive-owner-sub")).isFalse();
        assertThat(canRead(WORKSPACE, "unknown-sub")).isFalse();
        assertThat(canRead(null, "owner-sub")).isFalse();
        assertThat(canRead(WORKSPACE, null)).isFalse();
    }

    @Test
    @Order(5)
    void boundedAuditQueryRechecksAuthorityAndCannotCrossWorkspaceOrRepeatACursorRow() {
        List<AuditRow> ownerWorkspace = readPage(WORKSPACE, "owner-sub", null, 20);
        assertThat(ids(ownerWorkspace)).contains(PRIMARY_EVENT_ID).doesNotContain(SECONDARY_EVENT_ID);

        List<AuditRow> adminWorkspace = readPage(WORKSPACE, "admin-sub", null, 20);
        assertThat(ids(adminWorkspace)).contains(PRIMARY_EVENT_ID).doesNotContain(SECONDARY_EVENT_ID);

        List<AuditRow> ownerOtherWorkspace = readPage(SECOND_WORKSPACE, "owner-sub", null, 20);
        assertThat(ids(ownerOtherWorkspace)).contains(SECONDARY_EVENT_ID).doesNotContain(PRIMARY_EVENT_ID);

        assertThat(readPage(WORKSPACE, "editor-sub", null, 20)).isEmpty();
        assertThat(readPage(WORKSPACE, "inactive-owner-sub", null, 20)).isEmpty();

        List<AuditRow> firstPage = readPage(WORKSPACE, "owner-sub", null, 1);
        assertThat(firstPage).hasSize(1);
        List<AuditRow> afterCursor = readPage(WORKSPACE, "owner-sub", firstPage.get(0), 20);
        assertThat(afterCursor).doesNotContain(firstPage.get(0));
    }

    @Test
    @Order(6)
    void nonOwnerRuntimeWriterCanInsertSafeRowsWithoutDirectValidatorExecution() {
        assertThat(hasTablePrivilege(RUNTIME_WRITER, "INSERT")).isTrue();
        assertThat(hasFunctionPrivilege(RUNTIME_WRITER, "public.audit_metadata_is_safe(jsonb)")).isFalse();
        assertThat(isSecurityDefiner("public.validate_knowledge_audit_event_insert()")).isTrue();

        jdbc.execute("set role " + RUNTIME_WRITER);
        try {
            assertThatCode(() -> insertEvent(UUID.randomUUID().toString(), "success",
                    "{\"source_type\":\"upload\",\"size_bytes\":12}"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> insertEvent(UUID.randomUUID().toString(), "success",
                    "{\"content\":\"AUDIT_DOCUMENT_CANARY\"}"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("reset role");
        }
    }

    private static void applyMigrationsThroughGovernance(Path root) throws Exception {
        try (Stream<Path> paths = Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> path.getFileName().toString()
                            .compareTo("20260905020000_knowledge_governance.sql") <= 0)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jdbc.execute(Files.readString(migration));
            }
        }
    }

    private static void seedMemberships() {
        jdbc.update("insert into workspaces (id, name) values (?, ?)", WORKSPACE, "Audit workspace");
        jdbc.update("insert into workspaces (id, name) values (?, ?)", SECOND_WORKSPACE, "Other audit workspace");
        insertMembership("owner-sub", "owner", true);
        insertMembership("admin-sub", "admin", true);
        insertMembership("editor-sub", "editor", true);
        insertMembership("viewer-sub", "viewer", true);
        insertMembership("inactive-owner-sub", "owner", false);
        insertMembership(SECOND_WORKSPACE, "owner-sub", "owner", true);
        insertMembership(SECOND_WORKSPACE, "admin-sub", "admin", true);
        insertEvent(PRIMARY_EVENT_ID, WORKSPACE, "success", "{}");
        insertEvent(SECONDARY_EVENT_ID, SECOND_WORKSPACE, "success", "{}");
    }

    private static void provisionNonOwnerRuntimeWriter() {
        jdbc.execute("create role " + RUNTIME_WRITER + " nologin bypassrls");
        jdbc.execute("grant usage on schema public to " + RUNTIME_WRITER);
        jdbc.execute("grant insert on table public.knowledge_audit_events to " + RUNTIME_WRITER);
    }

    private static void insertMembership(String subject, String role, boolean active) {
        insertMembership(WORKSPACE, subject, role, active);
    }

    private static void insertMembership(String workspace, String subject, String role, boolean active) {
        jdbc.update("""
                        insert into workspace_memberships (id, workspace_id, subject, role, active)
                        values (?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), workspace, subject, role, active);
    }

    private static void insertEvent(String id, String outcome, String metadata) {
        insertEvent(id, WORKSPACE, outcome, metadata);
    }

    private static void insertEvent(String id, String workspace, String outcome, String metadata) {
        jdbc.update("""
                        insert into knowledge_audit_events
                            (id, actor_sub, action, resource_type, resource_id, workspace_id, outcome, metadata)
                        values (?, 'audit-test', 'AUDIT_TEST', 'audit-test', ?, ?, ?, cast(? as jsonb))
                        """, id, "event:" + id, workspace, outcome, metadata);
    }

    private static void assertMetadataRejected(String metadata) {
        assertThatThrownBy(() -> insertEvent(UUID.randomUUID().toString(), "success", metadata))
                .isInstanceOf(DataAccessException.class);
    }

    private static boolean functionExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regprocedure('public.can_read_workspace_audit(text,text)') is not null", Boolean.class));
    }

    private static boolean hasTablePrivilege(String role, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege(?, 'public.knowledge_audit_events', ?)", Boolean.class, role, privilege));
    }

    private static boolean hasFunctionPrivilege(String role) {
        return hasFunctionPrivilege(role, "public.can_read_workspace_audit(text,text)");
    }

    private static boolean hasFunctionPrivilege(String role, String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select has_function_privilege(
                            ?, cast(? as regprocedure), 'EXECUTE')
                        """, Boolean.class, role, function));
    }

    private static boolean isSecurityDefiner(String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select coalesce((
                            select prosecdef from pg_proc where oid = cast(? as regprocedure)
                        ), false)
                        """, Boolean.class, function));
    }

    private static void assertClientRoleCannotReadTableOrCallAuthorityFunction(String role) {
        // Roles are constants owned by the fixture, never request input.
        jdbc.execute("set role " + role);
        try {
            assertThatThrownBy(() -> jdbc.queryForObject(
                    "select count(*) from knowledge_audit_events", Long.class))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.queryForObject(
                    "select public.can_read_workspace_audit(?, ?)", Boolean.class, WORKSPACE, "owner-sub"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertEvent(UUID.randomUUID().toString(), "success", "{}"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("reset role");
        }
    }

    private static boolean canRead(String workspace, String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select public.can_read_workspace_audit(?, ?)", Boolean.class, workspace, subject));
    }

    private static List<AuditRow> readPage(String workspace, String subject, AuditRow before, int limit) {
        return jdbc.query("""
                        select id, occurred_at
                        from knowledge_audit_events
                        where workspace_id = ?
                          and public.can_read_workspace_audit(workspace_id, ?)
                          and (?::timestamptz is null
                               or (occurred_at, id) < (?::timestamptz, ?))
                        order by occurred_at desc, id desc
                        limit ?
                        """, (rs, row) -> new AuditRow(
                        rs.getString("id"), rs.getObject("occurred_at", OffsetDateTime.class)),
                workspace, subject,
                before == null ? null : before.occurredAt(),
                before == null ? null : before.occurredAt(),
                before == null ? null : before.id(),
                limit);
    }

    private static List<String> ids(List<AuditRow> rows) {
        return rows.stream().map(AuditRow::id).toList();
    }

    private record AuditRow(String id, OffsetDateTime occurredAt) {
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
