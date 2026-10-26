package com.sentinel.kms.kb;

import com.sentinel.kms.assistant.IocExtractor;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks IOCs up in {@code articles.iocs} (jsonb array of {type, value}) and
 * returns the articles that mention them, so the assistant can answer
 * "have we seen 1.2.3.4?" from the KB's indicator index instead of relying
 * on embedding similarity alone.
 */
@Service
public class IocLookupService {

    /** One article × IOC match. */
    public record IocHit(String articleId, String title, String severity, String cvss,
                         String iocType, String iocValue, EvidenceReference evidence) {
        /** Source-compatible constructor for callers that have no immutable evidence. */
        public IocHit(String articleId, String title, String severity, String cvss,
                      String iocType, String iocValue) {
            this(articleId, title, severity, cvss, iocType, iocValue, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final KnowledgeAccessService knowledgeAccessService;
    private final KnowledgeAuditService audit;

    @Autowired
    public IocLookupService(JdbcTemplate jdbc, KnowledgeAccessService knowledgeAccessService,
                            KnowledgeAuditService audit) {
        this.jdbc = jdbc;
        this.knowledgeAccessService = knowledgeAccessService;
        this.audit = audit;
    }

    /** Compatibility constructor for existing callers that do not inject audit. */
    public IocLookupService(JdbcTemplate jdbc, KnowledgeAccessService knowledgeAccessService) {
        this(jdbc, knowledgeAccessService, null);
    }

    /** Compatibility constructor for isolated legacy tests; it cannot grant protected access. */
    public IocLookupService(JdbcTemplate jdbc) {
        this(jdbc, null, null);
    }

    /**
     * @param iocs extracted indicators (type is advisory; matching is by value)
     * @return one hit per (article, matching IOC value)
     */
    public List<IocHit> lookup(List<IocExtractor.Ioc> iocs) {
        if (knowledgeAccessService == null) {
            return List.of();
        }
        return lookup(iocs, knowledgeAccessService.publicFixtureContext());
    }

    /**
     * Subject-aware IOC lookup. The policy predicate is evaluated in SQL before
     * an IOC value/title can leave the database, not as a post-query filter.
     */
    public List<IocHit> lookup(List<IocExtractor.Ioc> iocs, String subject) {
        if (knowledgeAccessService == null) {
            return List.of();
        }
        return lookup(iocs, knowledgeAccessService.contextFor(subject));
    }

    public List<IocHit> lookup(List<IocExtractor.Ioc> iocs, KnowledgeAccessContext context) {
        List<IocHit> hits = new ArrayList<>();
        if (iocs == null || iocs.isEmpty() || context == null || !context.authenticated()) {
            return hits;
        }
        List<String> values = iocs.stream().map(i -> i.value().toLowerCase()).distinct().toList();
        String placeholders = String.join(",", values.stream().map(v -> "?").toList());
        String sql = """
                select a.id, a.title, a.severity, a.cvss,
                       ioc->>'type' as ioc_type, ioc->>'value' as ioc_value,
                       ar.id as revision_id, ar.revision_number, ws.approval_status as revision_status,
                       evidence.id as evidence_snapshot_id, evidence.source_uri,
                       evidence.source_version, evidence.source_span_start,
                       char_length(ioc->>'value') as source_span_length
                from articles a
                join article_governance ag on ag.article_id = a.id
                join article_revisions ar on ar.id = ag.current_revision_id
                                           and ar.article_id = ag.article_id
                                           and ar.workspace_id = ag.workspace_id
                                           and ar.classification = ag.classification
                join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                                                       and ws.article_id = ar.article_id
                                                       and ws.workspace_id = ar.workspace_id
                                                       and ws.classification = ar.classification
                cross join lateral jsonb_array_elements(coalesce(a.iocs, '[]'::jsonb)) ioc
                join lateral (
                    select e.*, position(lower(ioc->>'value') in lower(e.content)) - 1 as source_span_start
                    from article_evidence_snapshots e
                    where e.article_revision_id = ar.id
                      and position(lower(ioc->>'value') in lower(e.content)) > 0
                    order by e.ordinal asc
                    limit 1
                ) evidence on true
                where lower(ioc->>'value') in (%s)
                  and ag.approval_status = 'approved'
                  and ag.lifecycle_status = 'active'
                  and ws.approval_status = 'approved'
                  and ws.lifecycle_status = 'active'
                  and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                  and (ag.expires_at is null or ag.expires_at > now())
                  and (
                      (ag.classification = 'public' and ag.read_policy = 'public' and ? <> '')
                      or (ag.classification = 'internal' and ag.read_policy = 'workspace_member'
                          and exists (select 1 from workspace_memberships wm
                                      where wm.workspace_id = ag.workspace_id and wm.subject = ?
                                        and wm.active = true))
                      or (ag.classification = 'confidential' and ag.read_policy = 'workspace_editor'
                          and exists (select 1 from workspace_memberships wm
                                      where wm.workspace_id = ag.workspace_id and wm.subject = ?
                                        and wm.active = true and wm.role in ('owner', 'admin', 'editor')))
                      or (ag.classification = 'restricted' and ag.read_policy = 'workspace_admin'
                          and exists (select 1 from workspace_memberships wm
                                      where wm.workspace_id = ag.workspace_id and wm.subject = ?
                                        and wm.active = true and wm.role in ('owner', 'admin')))
                  )
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(values);
        String subject = context.subject();
        args.add(subject);
        args.add(subject);
        args.add(subject);
        args.add(subject);
        try {
            jdbc.query(sql, (rs, n) -> {
                EvidenceReference evidence = new EvidenceReference(
                        rs.getString("id"), rs.getString("revision_id"), rs.getInt("revision_number"),
                        rs.getString("evidence_snapshot_id"), rs.getInt("source_span_start"),
                        rs.getInt("source_span_start") + rs.getInt("source_span_length"),
                        rs.getString("source_uri"), rs.getString("source_version"),
                        rs.getString("revision_status"));
                hits.add(new IocHit(
                        rs.getString("id"), rs.getString("title"), rs.getString("severity"),
                        rs.getString("cvss"), rs.getString("ioc_type"), rs.getString("ioc_value"), evidence));
                return null;
            }, args.toArray());
        } catch (Exception ignored) {
            // A policy/schema failure must not return IOC data by accident.
            return List.of();
        }
        if (audit != null) {
            audit.record(new KnowledgeAuditEvent(context.subject(), "IOC_LOOKUP", "ioc-set",
                    "count:" + values.size(), null, null, null, "allowed",
                    java.util.Map.of("matches", hits.size())));
        }
        return hits;
    }
}
