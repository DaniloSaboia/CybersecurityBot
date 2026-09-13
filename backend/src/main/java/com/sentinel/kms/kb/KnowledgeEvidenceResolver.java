package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.common.EvidenceReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Re-resolves a candidate citation against immutable, currently-approved
 * evidence immediately before it is persisted or replayed.  Callers must use
 * the returned value rather than a model/vector supplied reference.
 */
@Service
public class KnowledgeEvidenceResolver implements EvidenceReferenceResolver {

    private final JdbcTemplate jdbc;
    private final KnowledgeAccessService access;

    public KnowledgeEvidenceResolver(JdbcTemplate jdbc, KnowledgeAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /**
     * Returns canonical DB provenance only when {@code subject} can read the
     * article and the candidate names the article's current approved evidence
     * with an in-range span.  Any lookup failure is an abstention.
     */
    @Override
    public Optional<EvidenceReference> resolveCurrentApproved(String subject, EvidenceReference candidate) {
        if (blank(subject) || !complete(candidate)) {
            return Optional.empty();
        }
        try {
            AccessDecision decision = access.canReadArticle(subject, candidate.articleId());
            if (decision == null || !decision.allowed()) {
                return Optional.empty();
            }
            List<CanonicalEvidence> matches = jdbc.query("""
                            select ar.article_id, ar.id as article_revision_id, ar.revision_number,
                                   es.id as evidence_snapshot_id, es.source_uri, es.source_version,
                                   ws.approval_status as revision_status, char_length(es.content) as evidence_length
                            from article_governance ag
                            join article_revisions ar on ar.id = ag.current_revision_id
                              and ar.article_id = ag.article_id
                              and ar.workspace_id = ag.workspace_id
                              and ar.classification = ag.classification
                            join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                              and ws.article_id = ar.article_id
                              and ws.workspace_id = ar.workspace_id
                              and ws.classification = ar.classification
                              and ws.approval_status = 'approved'
                              and ws.lifecycle_status = 'active'
                              and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                            join article_evidence_snapshots es on es.article_revision_id = ar.id
                            where ag.article_id = ?
                              and ar.id = ?
                              and ar.revision_number = ?
                              and es.id = ?
                              and ag.approval_status = 'approved'
                              and ag.lifecycle_status = 'active'
                              and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                            """, (rs, row) -> new CanonicalEvidence(
                    rs.getString("article_id"), rs.getString("article_revision_id"),
                    rs.getInt("revision_number"), rs.getString("evidence_snapshot_id"),
                    rs.getString("source_uri"), rs.getString("source_version"),
                    rs.getString("revision_status"), rs.getInt("evidence_length")),
                    candidate.articleId(), candidate.articleRevisionId(), candidate.revisionNumber(),
                    candidate.evidenceSnapshotId());
            if (matches.isEmpty()) {
                return Optional.empty();
            }
            CanonicalEvidence evidence = matches.get(0);
            // Resolver output is never a repair/canonicalization of caller
            // identity.  Every immutable core field has to match exactly.
            if (!candidate.articleId().equals(evidence.articleId())
                    || !candidate.articleRevisionId().equals(evidence.revisionId())
                    || candidate.revisionNumber() != evidence.revisionNumber()
                    || !candidate.evidenceSnapshotId().equals(evidence.snapshotId())
                    || candidate.sourceSpanStart() < 0
                    || candidate.sourceSpanEnd() < candidate.sourceSpanStart()
                    || candidate.sourceSpanEnd() > evidence.length()) {
                return Optional.empty();
            }
            return Optional.of(new EvidenceReference(evidence.articleId(), evidence.revisionId(),
                    evidence.revisionNumber(), evidence.snapshotId(), candidate.sourceSpanStart(),
                    candidate.sourceSpanEnd(), evidence.sourceUri(), evidence.sourceVersion(), evidence.status()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static boolean complete(EvidenceReference reference) {
        return reference != null
                && !blank(reference.articleId())
                && !blank(reference.articleRevisionId())
                && reference.revisionNumber() > 0
                && !blank(reference.evidenceSnapshotId())
                && reference.sourceSpanStart() != null
                && reference.sourceSpanEnd() != null
                && reference.sourceSpanStart() >= 0
                && reference.sourceSpanEnd() >= reference.sourceSpanStart();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record CanonicalEvidence(String articleId, String revisionId, int revisionNumber,
                                     String snapshotId, String sourceUri, String sourceVersion,
                                     String status, int length) {
    }
}
