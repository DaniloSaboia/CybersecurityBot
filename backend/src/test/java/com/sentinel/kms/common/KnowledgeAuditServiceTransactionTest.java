package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeAuditServiceTransactionTest {

    @Test
    void defersSuccessfulAuditUntilTheOuterTransactionCommits() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper(), transactions);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            audit.record(KnowledgeAuditEvent.of(
                    "actor-1", "INGEST_CREATE", "ingest_job", "job-1", null, null, null,
                    AuditOutcome.SUCCESS, Map.of()));

            assertThat(jdbc.writes).isZero();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

            assertThat(jdbc.writes).isEqualTo(1);
            assertThat(transactions.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void persistsTerminalFailureInAnIndependentBestEffortTransaction() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        KnowledgeAuditService audit = new KnowledgeAuditService(jdbc, new ObjectMapper(), transactions);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            audit.record(KnowledgeAuditEvent.of(
                    "actor-1", "INGEST_DELETE", "ingest_job", "job-1", null, null, null,
                    AuditOutcome.FAILURE, Map.of("failure_code", "VECTOR_DELETE_FAILED")));

            assertThat(jdbc.writes).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            assertThat(transactions.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private int writes;

        @Override
        public int update(String sql, Object... args) {
            writes++;
            return 1;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<Integer> propagations = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // The test only observes whether the isolated write was requested.
        }

        @Override
        public void rollback(TransactionStatus status) {
            // The audit service remains best-effort if a transaction fails.
        }
    }
}
