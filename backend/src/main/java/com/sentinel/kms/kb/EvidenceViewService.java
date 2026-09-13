package com.sentinel.kms.kb;

import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Returns a passage only after the requested immutable identity has been
 * re-resolved for the current subject. It deliberately has no source/upload
 * fallback: upload provenance belongs to a different governed surface.
 */
@Service
public class EvidenceViewService {

    private static final String SNAPSHOT_QUERY = """
            select substring(es.content from ? for ?) as passage,
                   es.source_label, es.source_uri, es.source_version
            from article_governance ag
            join article_revisions ar on ar.id = ag.current_revision_id
              and ar.article_id = ag.article_id
              and ar.workspace_id = ag.workspace_id
              and ar.classification = ag.classification
            join article_revision_workflow_states ws on ws.article_revision_id = ar.id
              and ws.article_id = ar.article_id
              and ws.workspace_id = ar.workspace_id
              and ws.classification = ar.classification
            join article_evidence_snapshots es on es.article_revision_id = ar.id
            where es.id = ?
              and ar.id = ?
              and ar.article_id = ?
              and ar.revision_number = ?
              and ag.approval_status = 'approved'
              and ag.lifecycle_status = 'active'
              and (ag.expires_at is null or ag.expires_at > clock_timestamp())
              and ws.approval_status = 'approved'
              and ws.lifecycle_status = 'active'
              and (ws.expires_at is null or ws.expires_at > clock_timestamp())
              and ? >= 0
              and ? >= ?
              and ? <= char_length(es.content)
            limit 1
            """;

    private final EvidenceReferenceResolver resolver;
    private final JdbcTemplate jdbc;
    private final KnowledgeAuditService audit;

    public EvidenceViewService(EvidenceReferenceResolver resolver, JdbcTemplate jdbc,
                               KnowledgeAuditService audit) {
        this.resolver = resolver;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * A missing result intentionally conflates an invalid, stale, revoked,
     * unauthorized, or unavailable reference so callers cannot learn which
     * condition occurred.
     */
    public Optional<EvidenceViewResponse> view(String subject, EvidenceViewRequest request) {
        if (blank(subject) || request == null || !request.isWellFormed()) {
            return Optional.empty();
        }

        EvidenceReference candidate = request.candidate();
        Optional<EvidenceReference> resolved;
        try {
            resolved = resolver.resolveCurrentApproved(subject, candidate);
        } catch (RuntimeException ignored) {
            recordUnproven(subject, candidate, AuditOutcome.FAILURE);
            return Optional.empty();
        }
        if (resolved == null || resolved.isEmpty()) {
            recordUnproven(subject, candidate, AuditOutcome.DENIED);
            return Optional.empty();
        }

        EvidenceReference canonical = resolved.get();
        if (!sameCoreIdentity(candidate, canonical)) {
            recordUnproven(subject, candidate, AuditOutcome.DENIED);
            return Optional.empty();
        }

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(SNAPSHOT_QUERY,
                    canonical.sourceSpanStart() + 1,
                    canonical.sourceSpanEnd() - canonical.sourceSpanStart(),
                    canonical.evidenceSnapshotId(), canonical.articleRevisionId(), canonical.articleId(),
                    canonical.revisionNumber(), canonical.sourceSpanStart(), canonical.sourceSpanEnd(),
                    canonical.sourceSpanStart(), canonical.sourceSpanEnd());
            if (rows == null || rows.isEmpty()) {
                recordProven(subject, canonical, AuditOutcome.DENIED);
                return Optional.empty();
            }

            Map<String, Object> row = rows.get(0);
            String passage = text(row, "passage");
            String sourceUri = text(row, "source_uri");
            String sourceVersion = text(row, "source_version");
            if (passage == null
                    || !Objects.equals(sourceUri, canonical.sourceUri())
                    || !Objects.equals(sourceVersion, canonical.sourceVersion())) {
                recordProven(subject, canonical, AuditOutcome.DENIED);
                return Optional.empty();
            }

            EvidenceViewResponse response = new EvidenceViewResponse(
                    canonical.articleId(), canonical.articleRevisionId(), canonical.revisionNumber(),
                    canonical.evidenceSnapshotId(), canonical.sourceSpanStart(), canonical.sourceSpanEnd(),
                    text(row, "source_label"), canonical.sourceUri(), canonical.sourceVersion(),
                    canonical.revisionStatus(), passage);
            recordProven(subject, canonical, AuditOutcome.SUCCESS);
            return Optional.of(response);
        } catch (RuntimeException ignored) {
            recordProven(subject, canonical, AuditOutcome.FAILURE);
            return Optional.empty();
        }
    }

    /**
     * Resolver absence, failure, or a tuple mismatch proves none of the
     * request-supplied article identifiers.  The audit ledger deliberately
     * keeps its article/revision foreign keys null in that case so a denied
     * event cannot be lost to an FK violation.
     */
    private void recordUnproven(String subject, EvidenceReference candidate, AuditOutcome outcome) {
        audit.record(KnowledgeAuditEvent.of(subject, "EVIDENCE_VIEW", "article-evidence",
                candidate == null ? null : candidate.evidenceSnapshotId(), null, null, null,
                outcome, Map.of()));
    }

    /** The resolver returned this immutable tuple, so FK-backed audit facts are proven. */
    private void recordProven(String subject, EvidenceReference reference, AuditOutcome outcome) {
        audit.record(KnowledgeAuditEvent.of(subject, "EVIDENCE_VIEW", "article-evidence",
                reference.evidenceSnapshotId(), null, reference.articleId(), reference.articleRevisionId(),
                outcome, Map.of()));
    }

    private static boolean sameCoreIdentity(EvidenceReference requested, EvidenceReference canonical) {
        return canonical != null
                && Objects.equals(requested.articleId(), canonical.articleId())
                && Objects.equals(requested.articleRevisionId(), canonical.articleRevisionId())
                && requested.revisionNumber() == canonical.revisionNumber()
                && Objects.equals(requested.evidenceSnapshotId(), canonical.evidenceSnapshotId())
                && Objects.equals(requested.sourceSpanStart(), canonical.sourceSpanStart())
                && Objects.equals(requested.sourceSpanEnd(), canonical.sourceSpanEnd());
    }

    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
