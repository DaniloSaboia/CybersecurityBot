package com.sentinel.kms.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses physical PostgreSQL connections rather than a fake transaction manager:
 * REQUIRES_NEW must survive an outer rollback, while success must wait for commit.
 */
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeAuditServiceTransactionPostgresIntegrationTest {

    private static final String SUCCESS_ACTION = "AUDIT_TX_SUCCESS_ROLLBACK";
    private static final String FAILURE_ACTION = "AUDIT_TX_FAILURE_ROLLBACK";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kms")
            .withUsername("kms")
            .withPassword("kms");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate outerTransaction;
    private static KnowledgeAuditService audit;

    @BeforeAll
    static void migrateAndCreateProductionAuditService() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        outerTransaction = new TransactionTemplate(transactions);
        audit = new KnowledgeAuditService(jdbc, new ObjectMapper(), transactions);
    }

    @Test
    void successfulAuditIsNotWrittenWhenItsOuterTransactionRollsBack() {
        outerTransaction.executeWithoutResult(status -> {
            audit.record(event(SUCCESS_ACTION, AuditOutcome.SUCCESS));
            assertThat(count(SUCCESS_ACTION)).isZero();
            status.setRollbackOnly();
        });

        assertThat(count(SUCCESS_ACTION)).isZero();
    }

    @Test
    void failureAuditPersistsInRequiresNewWhenItsOuterTransactionRollsBack() {
        outerTransaction.executeWithoutResult(status -> {
            audit.record(event(FAILURE_ACTION, AuditOutcome.FAILURE));
            status.setRollbackOnly();
        });

        assertThat(count(FAILURE_ACTION)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        select outcome from knowledge_audit_events where action = ?
                        """, String.class, FAILURE_ACTION)).isEqualTo("failure");
    }

    private static KnowledgeAuditEvent event(String action, AuditOutcome outcome) {
        return KnowledgeAuditEvent.of(
                "audit-transaction", action, "audit-transaction", action.toLowerCase(),
                null, null, null, outcome, Map.of("size_bytes", 1));
    }

    private static long count(String action) {
        Long value = jdbc.queryForObject(
                "select count(*) from knowledge_audit_events where action = ?", Long.class, action);
        return value == null ? 0 : value;
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
