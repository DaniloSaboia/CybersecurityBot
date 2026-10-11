package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.EvidenceViewRequest;
import com.sentinel.kms.kb.EvidenceViewResponse;
import com.sentinel.kms.kb.EvidenceViewService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Expands a retrieved, current playbook into bounded, independently authorized source passages. */
@Service
public class PlaybookGuidanceService {
    private static final int MAX_SECTION_LENGTH = 3000;
    private static final List<String> HEADINGS = List.of(
            "First actions", "Questions to answer", "Evidence to preserve");

    private static final String GUIDANCE_QUERY = """
            with request_context as (select cast(? as text) as subject),
            selected_playbook as (
                select ar.article_id, ar.id as article_revision_id, ar.revision_number, ar.snapshot
                from article_governance ag
                join article_revisions ar on ar.id = ag.current_revision_id
                  and ar.article_id = ag.article_id
                  and ar.workspace_id = ag.workspace_id
                  and ar.classification = ag.classification
                join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                  and ws.article_id = ar.article_id
                  and ws.workspace_id = ar.workspace_id
                  and ws.classification = ar.classification
                join article_evidence_snapshots requested on requested.article_revision_id = ar.id
                cross join request_context ctx
                where ar.article_id = ? and ar.id = ? and ar.revision_number = ?
                  and requested.id = ?
                  and ? >= 0 and ? > ? and ? <= char_length(requested.content)
                  and requested.source_uri is not distinct from cast(? as text)
                  and requested.source_version is not distinct from cast(? as text)
                  and ar.snapshot->>'categoryId' = 'playbooks'
                  and ar.snapshot->'tags' @> '["response-playbook"]'::jsonb
                  and ag.approval_status = 'approved' and ag.lifecycle_status = 'active'
                  and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                  and ws.approval_status = 'approved' and ws.lifecycle_status = 'active'
                  and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                  and (
                        (ag.read_policy = 'public' and ag.classification = 'public' and ctx.subject <> '')
                     or (ag.read_policy = 'workspace_member' and ag.classification = 'internal' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true))
                     or (ag.read_policy = 'workspace_editor' and ag.classification = 'confidential' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true and wm.role in ('owner', 'admin', 'editor')))
                     or (ag.read_policy = 'workspace_admin' and ag.classification = 'restricted' and exists (
                           select 1 from workspace_memberships wm
                           where wm.workspace_id = ag.workspace_id and wm.subject = ctx.subject
                             and wm.active = true and wm.role in ('owner', 'admin')))
                  )
            )
            select p.article_id, p.article_revision_id, p.revision_number,
                   section.evidence_snapshot_id, section.heading, section.evidence_length
            from selected_playbook p
            left join lateral (
                select es.id as evidence_snapshot_id,
                       p.snapshot->'body'->(es.ordinal - 1)->>'h' as heading,
                       char_length(es.content) as evidence_length
                from article_evidence_snapshots es
                where es.article_revision_id = p.article_revision_id and es.ordinal > 0
                  and p.snapshot->'body'->(es.ordinal - 1)->>'h' in
                      ('First actions', 'Questions to answer', 'Evidence to preserve')
                  and es.content = (p.snapshot->'body'->(es.ordinal - 1)->>'h') || chr(10)
                      || (p.snapshot->'body'->(es.ordinal - 1)->>'p')
                  and char_length(es.content) between 1 and 3000
                order by case p.snapshot->'body'->(es.ordinal - 1)->>'h'
                    when 'First actions' then 0 when 'Questions to answer' then 1 else 2 end,
                    es.ordinal
                limit 3
            ) section on true
            """;

    private final JdbcTemplate jdbc;
    private final EvidenceViewService evidenceView;

    public PlaybookGuidanceService(JdbcTemplate jdbc, EvidenceViewService evidenceView) {
        this.jdbc = jdbc;
        this.evidenceView = evidenceView;
    }

    /**
     * The supplied chunks nominate identities only. Category, tags, section identities and text
     * are taken from immutable server records; vector prose is never returned. Failure on the
     * first eligible playbook closes the whole expansion instead of selecting a different plan.
     */
    public List<RetrievedChunk> findGuidance(String subject, List<RetrievedChunk> retrieved) {
        if (subject == null || subject.isBlank() || retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }
        Set<String> visited = new HashSet<>();
        try {
            for (RetrievedChunk chunk : retrieved) {
                if (chunk == null || chunk.metadata() == null || chunk.metadata().containsKey("job_id")) {
                    continue;
                }
                var parsed = EvidenceReference.fromMetadata(chunk.metadata());
                if (parsed.isEmpty()) continue;
                EvidenceReference candidate = parsed.get();
                if (candidate.sourceSpanEnd() <= candidate.sourceSpanStart()
                        || !visited.add(candidate.articleRevisionId())) continue;
                List<Map<String, Object>> rows = jdbc.queryForList(GUIDANCE_QUERY,
                        subject, candidate.articleId(), candidate.articleRevisionId(), candidate.revisionNumber(),
                        candidate.evidenceSnapshotId(), candidate.sourceSpanStart(), candidate.sourceSpanEnd(),
                        candidate.sourceSpanStart(), candidate.sourceSpanEnd(), candidate.sourceUri(),
                        candidate.sourceVersion());
                if (rows == null) return List.of();
                if (rows.isEmpty()) continue;
                return resolveSections(subject, candidate, chunk.score(), rows);
            }
        } catch (RuntimeException ignored) {
            // Database/schema/access failures must not expose partly authorized source material.
        }
        return List.of();
    }

    private List<RetrievedChunk> resolveSections(String subject, EvidenceReference candidate, double score,
                                                List<Map<String, Object>> rows) {
        if (rows.size() > HEADINGS.size()) return List.of();
        List<RetrievedChunk> resolved = new ArrayList<>();
        Set<String> headings = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String heading = text(row, "heading");
            String snapshotId = text(row, "evidence_snapshot_id");
            Integer length = integer(row.get("evidence_length"));
            Integer revisionNumber = integer(row.get("revision_number"));
            if (!Objects.equals(candidate.articleId(), text(row, "article_id"))
                    || !Objects.equals(candidate.articleRevisionId(), text(row, "article_revision_id"))
                    || !Objects.equals(candidate.revisionNumber(), revisionNumber)
                    || !HEADINGS.contains(heading) || !headings.add(heading)
                    || snapshotId == null || snapshotId.isBlank()
                    || length == null || length < 1 || length > MAX_SECTION_LENGTH) return List.of();

            EvidenceViewRequest request = new EvidenceViewRequest(candidate.articleId(),
                    candidate.articleRevisionId(), revisionNumber, snapshotId, 0, length);
            var authorized = evidenceView.view(subject, request);
            if (authorized == null || authorized.isEmpty()) return List.of();
            EvidenceViewResponse evidence = authorized.get();
            if (!sameIdentity(request, evidence) || !"approved".equals(evidence.revisionStatus())
                    || evidence.passage() == null
                    || evidence.passage().codePointCount(0, evidence.passage().length()) != length
                    || !evidence.passage().startsWith(heading + "\n")) return List.of();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("article_id", evidence.articleId());
            metadata.put("article_revision_id", evidence.articleRevisionId());
            metadata.put("revision_id", evidence.articleRevisionId());
            metadata.put("article_revision_number", evidence.revisionNumber());
            metadata.put("evidence_snapshot_id", evidence.evidenceSnapshotId());
            metadata.put("source_span_start", evidence.sourceSpanStart());
            metadata.put("source_span_end", evidence.sourceSpanEnd());
            metadata.put("source", evidence.sourceLabel());
            metadata.put("source_uri", evidence.sourceUri());
            metadata.put("source_version", evidence.sourceVersion());
            metadata.put("revision_status", evidence.revisionStatus());
            metadata.put("source_key", "article:" + evidence.articleId());
            metadata.put("heading", heading);
            resolved.add(new RetrievedChunk(evidence.passage(), score, Collections.unmodifiableMap(metadata)));
        }
        resolved.sort(java.util.Comparator.comparingInt(chunk -> HEADINGS.indexOf(chunk.heading())));
        return List.copyOf(resolved);
    }

    private static boolean sameIdentity(EvidenceViewRequest request, EvidenceViewResponse response) {
        return response != null && Objects.equals(request.articleId(), response.articleId())
                && Objects.equals(request.articleRevisionId(), response.articleRevisionId())
                && request.revisionNumber() == response.revisionNumber()
                && Objects.equals(request.evidenceSnapshotId(), response.evidenceSnapshotId())
                && request.sourceSpanStart() == response.sourceSpanStart()
                && request.sourceSpanEnd() == response.sourceSpanEnd();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
