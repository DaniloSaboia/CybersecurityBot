package com.sentinel.kms.graph;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.util.Comparator;
import java.util.UUID;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.*;
import com.sentinel.kms.kb.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import static com.sentinel.kms.graph.GraphDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeGraphPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("graph_disposable").withUsername("graph_test").withPassword("graph_test");
    static JdbcTemplate jdbc;
    static GraphService service;
    static KnowledgeAuditService audit;
    static org.springframework.transaction.support.TransactionTemplate transaction;
    @BeforeAll static void migrate() throws Exception {
        var ds = new SingleConnectionDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), true);
        jdbc = new JdbcTemplate(ds);
        audit = new KnowledgeAuditService(jdbc,new ObjectMapper());
        var access = new KnowledgeAccessService(jdbc,audit);
        var manager = new DataSourceTransactionManager(ds);
        transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        service = new GraphService(jdbc,new KnowledgeEvidenceResolver(jdbc,access),access,audit,manager);
        Path root = Path.of("").toAbsolutePath().getParent();
        jdbc.execute(Files.readString(root.resolve("docker/db-init/01-init.sql")));
        try (var paths = Files.list(root.resolve("supabase/migrations"))) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".sql")).sorted(Comparator.naturalOrder()).toList())
                jdbc.execute(Files.readString(p));
        }
    }
    @Test void installsImmutableEntityAndEdgeStorage() {
        assertThat(jdbc.queryForObject("select to_regclass('knowledge_graph_entities') is not null", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select to_regclass('knowledge_graph_edges') is not null", Boolean.class)).isTrue();
    }

    @Test void persistsExactCanonicalEntitiesAndInferredEdges() {
        var a=fixture("public");
        var b=fixture("public");
        var x=service.createEntity(a.editor(),input(a));
        var y=service.createEntity(b.editor(),input(b));
        var edge=service.createEdge(a.editor(),new EdgeInput(x.id(),y.id(),"targets",
            new ProvenanceInput(a.evidence(),StatementKind.inferred,"analyst_review","1",Instant.parse("2026-01-01T00:00:00Z"))));
        assertThat(service.readEntity("reader",x.id())).contains(x);
        assertThat(service.readEdge("reader",edge.id())).contains(edge);
        assertThat(edge.provenance().statementKind()).isEqualTo(StatementKind.inferred);
        assertThat(edge.provenance().evidence()).isEqualTo(a.evidence());
        assertThat(edge.provenance().evidenceSha256()).hasSize(64);
        assertThat(edge.provenance().recordVersion()).isEqualTo(1);
        assertThat(edge.provenance().recordedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from knowledge_audit_events where action='GRAPH_EDGE_CREATE' and resource_id=? and outcome='success' and metadata='{}'::jsonb",Integer.class,edge.id())).isEqualTo(1);
    }
    @Test void requiresCurrentMembershipAndClassificationAtReadAndWrite() {
        var f=fixture("internal");
        var entity=service.createEntity(f.editor(),input(f));
        assertThat(service.readEntity("outsider",entity.id())).isEmpty();
        assertThat(service.readEntity(f.editor(),entity.id())).isPresent();
        jdbc.update("update workspace_memberships set active=false where subject=?",f.editor());
        assertThat(service.readEntity(f.editor(),entity.id())).isEmpty();
        assertThatThrownBy(()->service.createEntity(f.editor(),input(f))).isInstanceOf(AccessDeniedException.class);
        var restricted=fixture("restricted"); // editor is below restricted/admin floor
        assertThatThrownBy(()->service.createEntity(restricted.editor(),input(restricted))).isInstanceOf(AccessDeniedException.class);
        var pub=fixture("public");
        assertThatThrownBy(()->service.createEntity("public-reader",input(pub))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void workflowRevocationHidesEntityAndIncidentEdgeAndDeniesNewWrites() {
        var a=fixture("public"); var b=fixture("public");
        var x=service.createEntity(a.editor(),input(a)); var y=service.createEntity(b.editor(),input(b));
        var edge=service.createEdge(a.editor(),new EdgeInput(x.id(),y.id(),"targets",input(a).provenance()));
        archive(b);
        assertThat(service.readEntity("reader",y.id())).isEmpty();
        assertThat(service.readEdge("reader",edge.id())).isEmpty();
        assertThatThrownBy(()->service.createEntity(b.editor(),input(b))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.createEdge(a.editor(),new EdgeInput(x.id(),y.id(),"targets",input(a).provenance()))).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_graph_edges where id=?",Integer.class,edge.id())).isEqualTo(1);
    }
    @Test void rejectsForgedTupleEmptySpanAndCrossClassificationEdge() {
        var a=fixture("public"); var e=a.evidence();
        for(var wrong:java.util.List.of(
            new EvidenceReference(e.articleId(),e.articleRevisionId(),9,e.evidenceSnapshotId(),0,4,e.sourceUri(),e.sourceVersion(),"approved"),
            new EvidenceReference(e.articleId(),e.articleRevisionId(),1,"missing",0,4,e.sourceUri(),e.sourceVersion(),"approved"),
            new EvidenceReference(e.articleId(),e.articleRevisionId(),1,e.evidenceSnapshotId(),0,900,e.sourceUri(),e.sourceVersion(),"approved"))) {
            assertThatThrownBy(()->service.createEntity(a.editor(),new EntityInput("malware","label",new ProvenanceInput(wrong,StatementKind.sourced,null,null,Instant.now())))).isInstanceOf(AccessDeniedException.class);
        }
        var x=service.createEntity(a.editor(),input(a));
        var b=fixture("internal"); var y=service.createEntity(b.editor(),input(b));
        assertThatThrownBy(()->service.createEdge(a.editor(),new EdgeInput(x.id(),y.id(),"targets",input(a).provenance()))).isInstanceOf(AccessDeniedException.class);
        assertThat(service.readEntity(null,x.id())).isEmpty();
        assertThat(service.readEntity("reader","unknown")).isEmpty();
    }
    @Test void databaseRejectsForgedProvenanceAndHistoryMutationAndClientAccess() {
        var f=fixture("public"); var entity=service.createEntity(f.editor(),input(f));
        assertThatThrownBy(()->jdbc.update("update knowledge_graph_entities set label='changed' where id=?",entity.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from knowledge_graph_entities where id=?",entity.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Clone an otherwise valid row with one forged immutable binding.
        String columns="entity_type,label,id,workspace_id,classification,article_id,article_revision_id,revision_number,evidence_snapshot_id,source_span_start,source_span_end,source_uri,source_version,evidence_sha256,statement_kind,observed_at,created_by_sub";
        String select="entity_type,label,?,workspace_id,classification,article_id,article_revision_id,revision_number,evidence_snapshot_id,source_span_start,source_span_end,source_uri,'FORGED',evidence_sha256,statement_kind,observed_at,created_by_sub";
        assertThatThrownBy(()->jdbc.update("insert into knowledge_graph_entities ("+columns+") select "+select+" from knowledge_graph_entities where id=?",UUID.randomUUID().toString(),entity.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String role:java.util.List.of("anon","authenticated")) {
            jdbc.execute("set role "+role);
            try {
                assertThatThrownBy(()->jdbc.queryForList("select * from knowledge_graph_entities")).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->jdbc.queryForList("select * from knowledge_graph_edges")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { jdbc.execute("reset role"); }
        }
    }
    @Test void storageRejectsEdgeWhoseEndpointEvidenceWasRevoked() {
        var a=fixture("public"); var b=fixture("public");
        var x=service.createEntity(a.editor(),input(a)); var y=service.createEntity(b.editor(),input(b));
        var edge=service.createEdge(a.editor(),new EdgeInput(x.id(),y.id(),"targets",input(a).provenance()));
        archive(b);
        String fields="from_entity_id,to_entity_id,relation_type,workspace_id,classification,article_id,article_revision_id,revision_number,evidence_snapshot_id,source_span_start,source_span_end,source_uri,source_version,evidence_sha256,statement_kind,observed_at,created_by_sub";
        assertThatThrownBy(()->jdbc.update("insert into knowledge_graph_edges (id,"+fields+") select ?,"+fields+" from knowledge_graph_edges where id=?",UUID.randomUUID().toString(),edge.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void expiryAndMissingEvidenceStayAbsentWithoutDeletingHistory() throws Exception {
        var f=fixture("public"); var x=service.createEntity(f.editor(),input(f));
        transaction.executeWithoutResult(status -> {
            jdbc.update("update article_revision_workflow_states set expires_at=clock_timestamp()+interval '300 milliseconds',state_version=state_version+1 where article_revision_id=?",f.evidence().articleRevisionId());
            appendEvent(jdbc, f.evidence().articleRevisionId(), "expiry_changed", "approved", "active", 0);
        });
        // Wall-clock expiry must work without a lifecycle job changing status.
        Thread.sleep(400);
        assertThat(service.readEntity("reader",x.id())).isEmpty();
        assertThatThrownBy(()->service.createEntity(f.editor(),input(f))).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from knowledge_graph_entities where id=?",Integer.class,x.id())).isEqualTo(1);
    }
    @Test void httpApiUsesJwtSubjectAndReturnsAbsentAfterRevocation() throws Exception {
        var f=fixture("public");
        var mapper=new ObjectMapper().findAndRegisterModules();
        String body=mapper.writeValueAsString(input(f));
        var mvc=mvc(f.editor());
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/graph/entities")
            .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc("reader").perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/graph/entities/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.provenance.statementKind").value("sourced"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.provenance.evidence.evidenceSnapshotId").value(f.evidence().evidenceSnapshotId()));
        mvc("reader").perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/graph/entities")
            .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc(null).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/graph/entities/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        archive(f);
        mvc("reader").perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/graph/entities/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    private static org.springframework.test.web.servlet.MockMvc mvc(String subject) {
        var jwt=subject==null?null:new org.springframework.security.oauth2.jwt.Jwt("fixture",Instant.now(),Instant.now().plusSeconds(300),java.util.Map.of("alg","none"),java.util.Map.of("sub",subject));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new GraphController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                public boolean supportsParameter(org.springframework.core.MethodParameter p) {
                    return p.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class);
                }
                public Object resolveArgument(org.springframework.core.MethodParameter p,
                    org.springframework.web.method.support.ModelAndViewContainer c,
                    org.springframework.web.context.request.NativeWebRequest r,
                    org.springframework.web.bind.support.WebDataBinderFactory b) { return jwt; }
            }).build();
    }
    @Test void graphWriteRechecksRevocationAfterWaitingForPublicationLock() throws Exception {
        var f=fixture("public");
        int graphBackend=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
        try(var writer=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            writer.setAutoCommit(false);
            try(var ps=writer.prepareStatement("select article_id from article_governance where article_id=? for update")) {
                ps.setString(1,f.evidence().articleId()); ps.executeQuery().close();
            }
            try(var ps=writer.prepareStatement("update article_revision_workflow_states set lifecycle_status='archived',state_version=state_version+1 where article_revision_id=?")) {
                ps.setString(1,f.evidence().articleRevisionId()); ps.executeUpdate();
            }
            var writerJdbc = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
            appendEvent(writerJdbc, f.evidence().articleRevisionId(), "archived", "approved", "active", 0);
            var task=java.util.concurrent.CompletableFuture.supplyAsync(()-> {
                try { service.createEntity(f.editor(),input(f)); return false; }
                catch(AccessDeniedException expected) { return true; }
            });
            boolean waiting=false;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline&&!waiting) {
                try(var ps=writer.prepareStatement("select cardinality(pg_blocking_pids(?)) > 0")) {
                    ps.setInt(1,graphBackend);
                    try(var rs=ps.executeQuery()) {rs.next();waiting=rs.getBoolean(1);}
                }
                if(!waiting) Thread.sleep(20);
            }
            writer.commit();
            assertThat(waiting).as("graph write waited behind governance publication lock").isTrue();
            assertThat(task.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from knowledge_graph_entities where article_id=?",Integer.class,f.evidence().articleId())).isZero();
    }
    @Test void movingCurrentRevisionNeverRetargetsOldGraphRows() {
        var f=fixture("public"); var x=service.createEntity(f.editor(),input(f));
        String revision="next-"+UUID.randomUUID(), evidence="next-e-"+UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
        jdbc.update("insert into article_revisions(id,article_id,revision_number,workspace_id,classification,status,snapshot,content_sha256,created_by_sub) values (?,?,2,'shared','public','draft','{}',repeat('0',64),'fixture')",revision,f.evidence().articleId());
        jdbc.update("insert into article_evidence_snapshots(id,article_revision_id,ordinal,source_label,content,content_sha256,created_by_sub) values (?,?,0,'fixture','replacement evidence',repeat('1',64),'fixture')",evidence,revision);
        jdbc.update("insert into article_revision_workflow_states(article_revision_id,article_id,workspace_id,classification,approval_status,lifecycle_status,approved_by_sub,approved_at) values (?,?,'shared','public','approved','active','fixture',clock_timestamp())",revision,f.evidence().articleId());
        appendEvent(jdbc, revision, "approved", null, null, 1);
        jdbc.update("update article_governance set current_revision_id=?,state_version=state_version+1,approved_at=(select approved_at from article_revision_workflow_states where article_revision_id=?) where article_id=?",revision,revision,f.evidence().articleId());
        });
        assertThat(service.readEntity("reader",x.id())).isEmpty();
        assertThatThrownBy(()->service.createEntity(f.editor(),input(f))).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select article_revision_id from knowledge_graph_entities where id=?",String.class,x.id())).isEqualTo(f.evidence().articleRevisionId());
    }
    @Test void fixturesCommitMatchingLifecycleEvents() {
        var f = fixture("public");
        assertThat(jdbc.queryForObject("""
            select count(*) from article_revision_lifecycle_events e
            join article_revision_workflow_states ws on ws.article_revision_id=e.article_revision_id
              and ws.article_id=e.article_id and ws.workspace_id=e.workspace_id
              and ws.classification=e.classification and ws.state_version=e.resulting_state_version
              and ws.approval_status=e.to_approval_status and ws.lifecycle_status=e.to_lifecycle_status
            join article_governance ag on ag.article_id=ws.article_id
              and ag.state_version=e.governance_state_version
            where ws.article_revision_id=? and e.expected_state_version=-1
            """, Integer.class, f.evidence().articleRevisionId())).isEqualTo(1);
    }
    private static EntityInput input(Fixture f) {
        return new EntityInput("malware","Test entity",new ProvenanceInput(f.evidence(),StatementKind.sourced,null,null,Instant.parse("2026-01-01T00:00:00Z")));
    }
    private static Fixture fixture(String classification) {
        return transaction.execute(status -> fixtureInTransaction(classification));
    }
    private static Fixture fixtureInTransaction(String classification) {
        String id=UUID.randomUUID().toString(), rev="r-"+id, evidence="e-"+id, editor="editor-"+id;
        String workspace="public".equals(classification)?"shared":"w-"+id;
        String policy=switch(classification) {case "public"->"public";case "internal"->"workspace_member";case "confidential"->"workspace_editor";default->"workspace_admin";};
        jdbc.update("insert into workspaces(id,name) values (?,?) on conflict do nothing",workspace,workspace);
        jdbc.update("insert into categories(id,label,abbr) values ('graph','Graph','G') on conflict do nothing");
        jdbc.update("insert into articles(id,title,category_id,severity,snippet) values (?,'Graph article','graph','high','snippet')",id);
        jdbc.update("insert into article_revisions(id,article_id,revision_number,workspace_id,classification,status,snapshot,content_sha256,created_by_sub) values (?,?,1,?,?,'draft','{}',repeat('0',64),'fixture')",rev,id,workspace,classification);
        jdbc.update("insert into article_evidence_snapshots(id,article_revision_id,ordinal,source_uri,source_label,source_version,content,content_sha256,created_by_sub) values (?,?,0,'https://source.invalid/1','fixture','v1','evidence passage',repeat('1',64),'fixture')",evidence,rev);
        jdbc.update("insert into article_revision_workflow_states(article_revision_id,article_id,workspace_id,classification,approval_status,lifecycle_status,approved_by_sub,approved_at) values (?,?,?,?,'approved','active','fixture',clock_timestamp())",rev,id,workspace,classification);
        appendEvent(jdbc, rev, "approved", null, null, 0);
        jdbc.update("insert into article_governance(article_id,workspace_id,classification,approval_status,read_policy,lifecycle_status,current_revision_id,created_by_sub,approved_by_sub,approved_at) select article_id,workspace_id,classification,approval_status,?,lifecycle_status,article_revision_id,'fixture',approved_by_sub,approved_at from article_revision_workflow_states where article_revision_id=?",policy,rev);
        jdbc.update("insert into workspace_memberships(id,workspace_id,subject,role) values (?,?,?,'editor')",UUID.randomUUID().toString(),workspace,editor);
        return new Fixture(editor,new EvidenceReference(id,rev,1,evidence,0,8,"https://source.invalid/1","v1","approved"));
    }
    private static void archive(Fixture fixture) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("update article_revision_workflow_states set lifecycle_status='archived',state_version=state_version+1 where article_revision_id=?",
                    fixture.evidence().articleRevisionId());
            appendEvent(jdbc, fixture.evidence().articleRevisionId(), "archived", "approved", "active", 0);
        });
    }

    /** Test-only lifecycle writer: append the resulting state before this connection commits. */
    private static void appendEvent(JdbcTemplate connection, String revision, String eventType,
                                    String previousApproval, String previousLifecycle, long governanceVersion) {
        connection.update("""
                insert into article_revision_lifecycle_events
                    (id, article_revision_id, article_id, workspace_id, classification,
                     event_type, from_approval_status, to_approval_status,
                     from_lifecycle_status, to_lifecycle_status,
                     expected_state_version, resulting_state_version, governance_state_version, actor_sub)
                select ?, ws.article_revision_id, ws.article_id, ws.workspace_id, ws.classification,
                       ?, ?, ws.approval_status, ?, ws.lifecycle_status,
                       ws.state_version - 1, ws.state_version, ?, 'fixture'
                from article_revision_workflow_states ws where ws.article_revision_id=?
                """, UUID.randomUUID().toString(), eventType, previousApproval, previousLifecycle,
                governanceVersion, revision);
    }
    private record Fixture(String editor,EvidenceReference evidence) {}
}
