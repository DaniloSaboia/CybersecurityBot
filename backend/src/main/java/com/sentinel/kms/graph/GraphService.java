package com.sentinel.kms.graph;

import com.sentinel.kms.audit.AuditActions;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.*;
import com.sentinel.kms.kb.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import static com.sentinel.kms.graph.GraphDtos.*;

/** No authorization cache or upload fallback. All returned references are re-resolved. */
@Service
public class GraphService {
    private final JdbcTemplate jdbc;
    private final EvidenceReferenceResolver resolver;
    private final KnowledgeAccessService access;
    private final KnowledgeAuditService audit;
    private final TransactionTemplate transaction;
    public GraphService(JdbcTemplate jdbc, EvidenceReferenceResolver resolver, KnowledgeAccessService access,
                        KnowledgeAuditService audit, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.resolver=resolver; this.access=access; this.audit=audit;
        this.transaction=new TransactionTemplate(manager);
    }

    public Entity createEntity(String subject, EntityInput input) {
        if (input == null || !type(input.entityType()) || blank(input.label()) || input.label().length()>500)
            throw new IllegalArgumentException("Invalid graph entity");
        validate(input.provenance());
        String id=UUID.randomUUID().toString();
        try {
            canonical(subject,input.provenance().evidence());
            Entity result=transaction.execute(status -> {
                lockArticles(List.of(input.provenance().evidence().articleId()));
                EvidenceReference evidence=canonical(subject,input.provenance().evidence());
                Binding binding=binding(evidence);
                access.requireWorkspaceEditor(subject,binding.workspace());
                insert("knowledge_graph_entities",id,subject,input.provenance(),evidence,binding,
                    "entity_type, label", new Object[]{input.entityType(),input.label()});
                return readEntity(subject,id).orElseThrow(GraphService::denied);
            });
            record(subject,AuditActions.GRAPH_ENTITY_CREATE,"graph-entity",id,result.provenance(),AuditOutcome.SUCCESS);
            return result;
        } catch (RuntimeException error) {
            record(subject,AuditActions.GRAPH_ENTITY_CREATE,"graph-entity",id,null,
                error instanceof AccessDeniedException ? AuditOutcome.DENIED : AuditOutcome.FAILURE);
            if (error instanceof AccessDeniedException) throw denied();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Graph unavailable");
        }
    }

    public Edge createEdge(String subject, EdgeInput input) {
        if(input==null || blank(input.fromEntityId()) || blank(input.toEntityId()) || !type(input.relationType()))
            throw new IllegalArgumentException("Invalid graph edge");
        validate(input.provenance());
        String id=UUID.randomUUID().toString();
        try {
            canonical(subject,input.provenance().evidence());
            Edge result=transaction.execute(status -> {
                Entity from=readEntity(subject,input.fromEntityId()).orElseThrow(GraphService::denied);
                Entity to=readEntity(subject,input.toEntityId()).orElseThrow(GraphService::denied);
                lockArticles(List.of(input.provenance().evidence().articleId(),
                    from.provenance().evidence().articleId(),to.provenance().evidence().articleId()));
                EvidenceReference evidence=canonical(subject,input.provenance().evidence());
                canonical(subject,from.provenance().evidence());
                canonical(subject,to.provenance().evidence());
                Binding binding=binding(evidence);
                access.requireWorkspaceEditor(subject,binding.workspace());
                for(Entity endpoint:List.of(from,to)) {
                    if(!binding.workspace().equals(endpoint.provenance().workspaceId())
                        || !binding.classification().equals(endpoint.provenance().classification())) throw denied();
                }
                insert("knowledge_graph_edges",id,subject,input.provenance(),evidence,binding,
                    "from_entity_id, to_entity_id, relation_type",new Object[]{from.id(),to.id(),input.relationType()});
                return readEdge(subject,id).orElseThrow(GraphService::denied);
            });
            record(subject,AuditActions.GRAPH_EDGE_CREATE,"graph-edge",id,result.provenance(),AuditOutcome.SUCCESS);
            return result;
        } catch(RuntimeException error) {
            record(subject,AuditActions.GRAPH_EDGE_CREATE,"graph-edge",id,null,
                error instanceof AccessDeniedException ? AuditOutcome.DENIED : AuditOutcome.FAILURE);
            if(error instanceof AccessDeniedException) throw denied();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Graph unavailable");
        }
    }

    public Optional<Entity> readEntity(String subject,String id) {
        return read(subject,id,false).map(row -> new Entity(id,(String)row.get("entity_type"),(String)row.get("label"),
            (Provenance)row.get("provenance")));
    }
    public Optional<Edge> readEdge(String subject,String id) {
        return read(subject,id,true).map(row -> new Edge(id,(String)row.get("from_entity_id"),
            (String)row.get("to_entity_id"),(String)row.get("relation_type"),(Provenance)row.get("provenance")));
    }
    private Optional<Map<String,Object>> read(String subject,String id,boolean edge) {
        String eventId=UUID.randomUUID().toString(); // unproven caller IDs never enter audit
        String action=edge?AuditActions.GRAPH_EDGE_READ:AuditActions.GRAPH_ENTITY_READ;
        String kind=edge?"graph-edge":"graph-entity";
        if(blank(subject)||blank(id)) return Optional.empty();
        try {
            String table=edge?"knowledge_graph_edges":"knowledge_graph_entities";
            // Resolve only a non-content authorization key before the shared subject gate.
            List<String> articles=jdbc.query("select article_id from "+table+" where id=?",(rs,n)->rs.getString(1),id);
            if(articles.isEmpty() || !access.canReadArticle(subject,articles.get(0)).allowed()) throw denied();
            List<Map<String,Object>> rows=jdbc.query("select * from "+table+" where id=?",(rs,n)-> {
                Map<String,Object> row=new HashMap<>();
                row.put("provenance",provenance(rs));
                if(edge) { row.put("from_entity_id",rs.getString("from_entity_id")); row.put("to_entity_id",rs.getString("to_entity_id")); row.put("relation_type",rs.getString("relation_type")); }
                else { row.put("entity_type",rs.getString("entity_type")); row.put("label",rs.getString("label")); }
                return row;
            },id);
            if(rows.isEmpty()) throw denied();
            Map<String,Object> row=rows.get(0);
            Provenance stored=(Provenance)row.get("provenance");
            if(edge) {
                if(readEntity(subject,(String)row.get("from_entity_id")).isEmpty()
                    ||readEntity(subject,(String)row.get("to_entity_id")).isEmpty()) throw denied();
            }
            EvidenceReference resolved=canonical(subject,stored.evidence());
            row.put("provenance",new Provenance(resolved,stored.workspaceId(),stored.classification(),stored.evidenceSha256(),
                stored.statementKind(),stored.inferenceMethod(),stored.inferenceVersion(),stored.observedAt(),
                stored.recordedAt(),stored.recordVersion(),stored.createdBySub()));
            record(subject,action,kind,id,stored,AuditOutcome.SUCCESS);
            return Optional.of(row);
        } catch(RuntimeException error) {
            record(subject,action,kind,eventId,null,error instanceof AccessDeniedException?AuditOutcome.DENIED:AuditOutcome.FAILURE);
            return Optional.empty();
        }
    }

    private EvidenceReference canonical(String subject,EvidenceReference candidate) {
        try {
            if(blank(subject)||candidate==null) throw denied();
            Optional<EvidenceReference> result=resolver.resolveCurrentApproved(subject,candidate);
            if(result==null||result.isEmpty()) throw denied();
            EvidenceReference e=result.get();
            if(!Objects.equals(candidate.articleId(),e.articleId()) || !Objects.equals(candidate.articleRevisionId(),e.articleRevisionId())
                || candidate.revisionNumber()!=e.revisionNumber() || !Objects.equals(candidate.evidenceSnapshotId(),e.evidenceSnapshotId())
                || !Objects.equals(candidate.sourceSpanStart(),e.sourceSpanStart()) || !Objects.equals(candidate.sourceSpanEnd(),e.sourceSpanEnd())
                || !Objects.equals(candidate.sourceUri(),e.sourceUri()) || !Objects.equals(candidate.sourceVersion(),e.sourceVersion())
                || !"approved".equals(e.revisionStatus())) throw denied();
            return e;
        } catch(RuntimeException error) { throw denied(); }
    }
    private void lockArticles(List<String> articles) {
        // Match lifecycle order: governance first, then workflow. No membership or immutable-row locks.
        List<String> sorted=articles.stream().distinct().sorted().toList();
        for(String article:sorted) jdbc.query("select article_id from article_governance where article_id=? for update",(rs,n)->rs.getString(1),article);
        for(String article:sorted) jdbc.query("select ws.article_revision_id from article_revision_workflow_states ws join article_governance ag on ag.current_revision_id=ws.article_revision_id where ag.article_id=? for update of ws",(rs,n)->rs.getString(1),article);
    }
    private Binding binding(EvidenceReference e) {
        return jdbc.queryForObject("select ar.workspace_id,ar.classification,es.content_sha256 from article_revisions ar join article_evidence_snapshots es on es.article_revision_id=ar.id where ar.id=? and es.id=?",
            (rs,n)->new Binding(rs.getString(1),rs.getString(2),rs.getString(3)),e.articleRevisionId(),e.evidenceSnapshotId());
    }
    private void insert(String table,String id,String subject,ProvenanceInput p,EvidenceReference e,Binding b,String extra,Object[] values) {
        List<Object> args=new ArrayList<>(Arrays.asList(id,b.workspace(),b.classification(),e.articleId(),e.articleRevisionId(),e.revisionNumber(),
            e.evidenceSnapshotId(),e.sourceSpanStart(),e.sourceSpanEnd(),e.sourceUri(),e.sourceVersion(),b.hash(),p.statementKind().name(),
            p.inferenceMethod(),p.inferenceVersion(),Timestamp.from(p.observedAt()),subject));
        args.addAll(Arrays.asList(values));
        jdbc.update("insert into "+table+" (id,workspace_id,classification,article_id,article_revision_id,revision_number,evidence_snapshot_id,source_span_start,source_span_end,source_uri,source_version,evidence_sha256,statement_kind,inference_method,inference_version,observed_at,created_by_sub,"+extra+") values ("+String.join(",",Collections.nCopies(args.size(),"?"))+")",args.toArray());
    }
    private static Provenance provenance(ResultSet rs) throws SQLException {
        EvidenceReference e=new EvidenceReference(rs.getString("article_id"),rs.getString("article_revision_id"),rs.getInt("revision_number"),
            rs.getString("evidence_snapshot_id"),rs.getInt("source_span_start"),rs.getInt("source_span_end"),rs.getString("source_uri"),rs.getString("source_version"),"approved");
        return new Provenance(e,rs.getString("workspace_id"),rs.getString("classification"),rs.getString("evidence_sha256"),
            StatementKind.valueOf(rs.getString("statement_kind")),rs.getString("inference_method"),rs.getString("inference_version"),
            rs.getTimestamp("observed_at").toInstant(),rs.getTimestamp("recorded_at").toInstant(),rs.getInt("record_version"),rs.getString("created_by_sub"));
    }
    private void record(String actor,String action,String kind,String id,Provenance p,AuditOutcome outcome) {
        audit.record(KnowledgeAuditEvent.of(actor,action,kind,id,p==null?null:p.workspaceId(),
            p==null?null:p.evidence().articleId(),p==null?null:p.evidence().articleRevisionId(),outcome,Map.of()));
    }
    private static void validate(ProvenanceInput p) {
        if(p==null||p.evidence()==null||p.statementKind()==null||p.observedAt()==null) throw new IllegalArgumentException("Invalid graph provenance");
        EvidenceReference e=p.evidence();
        if(blank(e.articleId())||blank(e.articleRevisionId())||blank(e.evidenceSnapshotId())||e.revisionNumber()<1
            ||e.sourceSpanStart()==null||e.sourceSpanEnd()==null||e.sourceSpanStart()<0||e.sourceSpanEnd()<=e.sourceSpanStart())
            throw new IllegalArgumentException("Invalid graph evidence identity");
        if(p.statementKind()==StatementKind.inferred ? blank(p.inferenceMethod())||blank(p.inferenceVersion())
            ||p.inferenceMethod().length()>200||p.inferenceVersion().length()>100 : p.inferenceMethod()!=null||p.inferenceVersion()!=null)
            throw new IllegalArgumentException("Invalid graph inference provenance");
    }
    private static boolean blank(String s){ return s==null||s.isBlank(); }
    private static boolean type(String s){ return s!=null&&s.matches("[a-z][a-z0-9_]{0,63}"); }
    private static AccessDeniedException denied(){ return new AccessDeniedException("Graph evidence unavailable"); }
    private record Binding(String workspace,String classification,String hash) {}
}
