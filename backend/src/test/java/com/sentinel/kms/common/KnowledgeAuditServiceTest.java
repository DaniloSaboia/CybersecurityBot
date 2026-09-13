package com.sentinel.kms.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnowledgeAuditServiceTest {

    @Test
    void persistsOnlyWhitelistedScalarMetadata() throws Exception {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper());

        audit.record(new KnowledgeAuditEvent(
                "actor-1", "INGEST_CREATE", "ingest_job", "job-1", null, null, null, "success",
                Map.of(
                        "source_type", "upload",
                        "size_bytes", 42,
                        "content", "AUDIT_DOCUMENT_CANARY",
                        "authorization", "Bearer AUDIT_TOKEN_CANARY",
                        "source_url", "https://user:AUDIT_PASSWORD_CANARY@example.invalid/path",
                        "nested", Map.of("body", "AUDIT_NESTED_CANARY"),
                        "unknown", "AUDIT_UNKNOWN_CANARY")));

        JsonNode metadata = new ObjectMapper().readTree(jdbc.persistedJson());
        assertThat(metadata.fieldNames()).toIterable().containsExactlyInAnyOrder("source_type", "size_bytes");
        assertThat(metadata.toString()).doesNotContain(
                "AUDIT_DOCUMENT_CANARY", "AUDIT_TOKEN_CANARY", "AUDIT_PASSWORD_CANARY",
                "AUDIT_NESTED_CANARY", "AUDIT_UNKNOWN_CANARY");
    }

    @Test
    void persistenceFailureDoesNotLogEventOrExceptionCanaries() {
        FailingJdbcTemplate jdbc = new FailingJdbcTemplate();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeAuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> audit.record(new KnowledgeAuditEvent(
                    "actor-1", "INGEST_CREATE", "ingest_job", "AUDIT_RESOURCE_CANARY",
                    null, null, null, "failure",
                    Map.of("content", "AUDIT_METADATA_CANARY"))))
                    .doesNotThrowAnyException();

            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(
                        "AUDIT_RESOURCE_CANARY", "AUDIT_METADATA_CANARY", "AUDIT_EXCEPTION_CANARY");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void typedFactorySupportsConflictAndDropsNullMetadataBeforePersistence() throws Exception {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper());
        Map<String, Object> supplied = new LinkedHashMap<>();
        supplied.put("expected_state_version", 4);
        supplied.put("reason_code", null);

        audit.record(KnowledgeAuditEvent.of(
                "actor-1", "LIFECYCLE_CONCURRENCY_CONFLICT", "article_revision", "revision-1",
                "workspace-1", "article-1", "revision-1", AuditOutcome.CONFLICT, supplied));

        assertThat(jdbc.arguments[8]).isEqualTo("conflict");
        JsonNode metadata = new ObjectMapper().readTree(jdbc.persistedJson());
        assertThat(metadata.fieldNames()).toIterable().containsExactly("expected_state_version");
    }

    @Test
    void dropsFractionalNumericMetadataRatherThanSilentlyTruncatingIt() throws Exception {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper());

        audit.record(new KnowledgeAuditEvent(
                "actor-1", "INGEST_CREATE", "ingest_job", "job-1", null, null, null, "success",
                Map.of(
                        "size_bytes", new BigDecimal("1.5"),
                        "attempt", BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE),
                        "generation", 2L)));

        JsonNode metadata = new ObjectMapper().readTree(jdbc.persistedJson());
        assertThat(metadata.fieldNames()).toIterable().containsExactly("generation");
        assertThat(metadata.toString()).doesNotContain("1.5", "18446744073709551617");
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] arguments;

        @Override
        public int update(String sql, Object... args) {
            this.arguments = args;
            return 1;
        }

        String persistedJson() {
            return (String) arguments[9];
        }
    }

    private static final class FailingJdbcTemplate extends JdbcTemplate {
        @Override
        public int update(String sql, Object... args) {
            throw new IllegalStateException("AUDIT_EXCEPTION_CANARY document=AUDIT_DOCUMENT_CANARY");
        }
    }
}
