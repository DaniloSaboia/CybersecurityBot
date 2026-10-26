package com.sentinel.kms.eval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-authoritative evaluator permissions. All IDs and grants here are
 * explicitly synthetic disposable test data, never analyst identities.
 */
@Testcontainers
class EvalAuthorizationMigrationIntegrationTest {

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
        seedSyntheticGrants();
    }

    @Test
    void viewAndExecutionRequireExplicitActiveGrantsInTheSameActiveMembershipWorkspace() {
        assertView("synthetic-evaluator", true);
        assertExecute("synthetic-evaluator", true);

        assertView("synthetic-viewer", true);
        assertExecute("synthetic-viewer", false);

        assertView("synthetic-exec-only", false);
        assertExecute("synthetic-exec-only", false);

        assertView("synthetic-admin-without-grant", false);
        assertExecute("synthetic-admin-without-grant", false);

        assertView("synthetic-split-workspace", true);
        assertExecute("synthetic-split-workspace", false);

        assertView("synthetic-membership-other-workspace", false);
        assertExecute("synthetic-membership-other-workspace", false);

        assertView("synthetic-inactive-member", false);
        assertExecute("synthetic-inactive-member", false);

        assertView("", false);
        assertExecute("", false);
        assertView(null, false);
        assertExecute(null, false);
    }

    @Test
    void revokedGrantImmediatelyFailsClosed() {
        jdbc.update("update evaluation_operational_grants set revoked_at = now() where id = ?", "grant-view-revoked");

        assertView("synthetic-revoked", false);
        assertExecute("synthetic-revoked", false);
    }

    @Test
    void grantHistoryIsImmutableExceptForOneWayRevocation() {
        jdbc.update("""
                        insert into evaluation_operational_grants (id, subject, workspace_id, capability)
                        values ('grant-mutable', 'synthetic-mutable', 'eval-a', 'dataset_view')
                        """);
        assertThatThrownBy(() -> jdbc.update("""
                        update evaluation_operational_grants set capability = 'execution' where id = ?
                        """, "grant-mutable"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from evaluation_operational_grants where id = ?", "grant-mutable"))
                .isInstanceOf(DataAccessException.class);

        assertThatCode(() -> jdbc.update("""
                        update evaluation_operational_grants set revoked_at = now() where id = ?
                        """, "grant-mutable"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("""
                        update evaluation_operational_grants set revoked_at = null where id = ?
                        """, "grant-mutable"))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update("""
                        insert into evaluation_operational_grants (id, subject, workspace_id, capability)
                        values ('grant-evaluator-view-duplicate', 'synthetic-evaluator', 'eval-a', 'dataset_view')
                        """))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void grantsAndFunctionsAreNotDataApiCallableAndFunctionsAreSecurityInvoker() {
        assertThat(jdbc.queryForObject("""
                        select relrowsecurity from pg_class
                        where oid = 'public.evaluation_operational_grants'::regclass
                        """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                        select has_table_privilege('anon', 'public.evaluation_operational_grants', 'SELECT')
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select has_function_privilege('anon',
                            'public.can_view_evaluation_dataset(text)'::regprocedure, 'EXECUTE')
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select has_function_privilege('authenticated',
                            'public.can_execute_evaluation(text)'::regprocedure, 'EXECUTE')
                        """, Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                        select p.prosecdef from pg_proc p
                        where p.oid = 'public.can_execute_evaluation(text)'::regprocedure
                        """, Boolean.class)).isFalse();
    }

    private static void seedSyntheticGrants() {
        jdbc.update("insert into workspaces (id, name) values ('eval-a', 'Synthetic eval A'), ('eval-b', 'Synthetic eval B')");
        jdbc.update("""
                        insert into workspace_memberships (id, workspace_id, subject, role, active)
                        values ('membership-evaluator', 'eval-a', 'synthetic-evaluator', 'viewer', true),
                               ('membership-viewer', 'eval-a', 'synthetic-viewer', 'viewer', true),
                               ('membership-exec-only', 'eval-a', 'synthetic-exec-only', 'viewer', true),
                               ('membership-admin', 'eval-a', 'synthetic-admin-without-grant', 'admin', true),
                               ('membership-split-a', 'eval-a', 'synthetic-split-workspace', 'viewer', true),
                               ('membership-split-b', 'eval-b', 'synthetic-split-workspace', 'viewer', true),
                               ('membership-other-workspace', 'eval-b', 'synthetic-membership-other-workspace', 'viewer', true),
                               ('membership-mutable', 'eval-a', 'synthetic-mutable', 'viewer', true),
                               ('membership-inactive', 'eval-a', 'synthetic-inactive-member', 'owner', false),
                               ('membership-revoked', 'eval-a', 'synthetic-revoked', 'viewer', true)
                        """);
        jdbc.update("""
                        insert into evaluation_operational_grants (id, subject, workspace_id, capability)
                        values ('grant-evaluator-view', 'synthetic-evaluator', 'eval-a', 'dataset_view'),
                               ('grant-evaluator-exec', 'synthetic-evaluator', 'eval-a', 'execution'),
                               ('grant-viewer', 'synthetic-viewer', 'eval-a', 'dataset_view'),
                               ('grant-exec-only', 'synthetic-exec-only', 'eval-a', 'execution'),
                               ('grant-split-view', 'synthetic-split-workspace', 'eval-a', 'dataset_view'),
                               ('grant-split-exec', 'synthetic-split-workspace', 'eval-b', 'execution'),
                               ('grant-other-workspace-view', 'synthetic-membership-other-workspace', 'eval-a', 'dataset_view'),
                               ('grant-other-workspace-exec', 'synthetic-membership-other-workspace', 'eval-a', 'execution'),
                               ('grant-inactive-view', 'synthetic-inactive-member', 'eval-a', 'dataset_view'),
                               ('grant-inactive-exec', 'synthetic-inactive-member', 'eval-a', 'execution'),
                               ('grant-view-revoked', 'synthetic-revoked', 'eval-a', 'dataset_view'),
                               ('grant-exec-revoked', 'synthetic-revoked', 'eval-a', 'execution')
                        """);
    }

    private static void assertView(String subject, boolean expected) {
        assertThat(jdbc.queryForObject("select public.can_view_evaluation_dataset(?)", Boolean.class, subject))
                .isEqualTo(expected);
    }

    private static void assertExecute(String subject, boolean expected) {
        assertThat(jdbc.queryForObject("select public.can_execute_evaluation(?)", Boolean.class, subject))
                .isEqualTo(expected);
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
