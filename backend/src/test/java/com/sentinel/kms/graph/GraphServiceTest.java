package com.sentinel.kms.graph;

import com.sentinel.kms.common.*;
import com.sentinel.kms.kb.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.Optional;
import static com.sentinel.kms.graph.GraphDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphServiceTest {
    final EvidenceReferenceResolver resolver = mock(EvidenceReferenceResolver.class);
    final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final KnowledgeAuditService audit = mock(KnowledgeAuditService.class);
    final GraphService service = new GraphService(jdbc, resolver, access, audit, new DataSourceTransactionManager());
    final EvidenceReference evidence = new EvidenceReference("a", "r", 1, "e", 0, 4, "uri", "v1", "approved");
    EntityInput input(EvidenceReference e) {
        return new EntityInput("malware", "name", new ProvenanceInput(e, StatementKind.sourced, null, null, Instant.now()));
    }
    @Test void deniesAbsentResolverBeforeStorage() {
        when(resolver.resolveCurrentApproved("reader", evidence)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createEntity("reader", input(evidence))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsResolverRepairOfRevisionNumber() {
        when(resolver.resolveCurrentApproved("reader", evidence)).thenReturn(Optional.of(
            new EvidenceReference("a", "r", 2, "e", 0, 4, "uri", "v1", "approved")));
        assertThatThrownBy(() -> service.createEntity("reader", input(evidence))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsForgedUriAndVersion() {
        when(resolver.resolveCurrentApproved("reader", evidence)).thenReturn(Optional.of(
            new EvidenceReference("a", "r", 1, "e", 0, 4, "other", "v2", "approved")));
        assertThatThrownBy(() -> service.createEntity("reader", input(evidence))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void resolverFailureCannotBecomeAnAllowOrLeakExceptionMessage() {
        when(resolver.resolveCurrentApproved("reader", evidence)).thenThrow(new RuntimeException("SECRET"));
        assertThatThrownBy(() -> service.createEntity("reader", input(evidence)))
            .isInstanceOf(AccessDeniedException.class).hasMessage("Graph evidence unavailable");
        verifyNoInteractions(jdbc);
    }
    @Test void inferredAssertionsRequireMethodAndVersion() {
        var in = new EntityInput("malware", "name", new ProvenanceInput(evidence, StatementKind.inferred, null, null, Instant.now()));
        assertThatThrownBy(() -> service.createEntity("reader", in)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, resolver);
    }
}
