package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.EvidenceReferenceResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The assistant's sole persistence path for exact article citations.  JSON is
 * intentionally not used: every candidate is re-resolved through Vellum's
 * current-approved evidence boundary, then written to the immutable normalized
 * table using a deterministic server-generated row id.
 */
@Service
public class MessageCitationReferenceStore {

    private static final String INSERT = """
            insert into message_citation_references (
                id, message_id, ordinal, article_id, article_revision_id, evidence_snapshot_id,
                source_span_start, source_span_end, source_uri, source_version)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final EvidenceReferenceResolver resolver;

    public MessageCitationReferenceStore(JdbcTemplate jdbc, EvidenceReferenceResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    /**
     * Re-resolves all candidate references for the active subject and writes only the canonical
     * provenance returned by the governance service. A zero-row write or unresolved candidate is
     * an error so the surrounding assistant-message transaction rolls back.
     */
    public List<EvidenceReference> bind(String subject, String messageId,
                                        Collection<EvidenceReference> candidates) {
        if (blank(subject) || blank(messageId)) {
            throw new IllegalArgumentException("citation binding requires an authenticated subject and message id");
        }
        List<EvidenceReference> canonical = canonicalize(subject, candidates);
        for (int ordinal = 0; ordinal < canonical.size(); ordinal++) {
            EvidenceReference reference = canonical.get(ordinal);
            int inserted = jdbc.update(INSERT, new Object[]{
                    "citation:" + messageId + ":" + ordinal,
                    messageId,
                    ordinal,
                    reference.articleId(),
                    reference.articleRevisionId(),
                    reference.evidenceSnapshotId(),
                    reference.sourceSpanStart(),
                    reference.sourceSpanEnd(),
                    reference.sourceUri(),
                    reference.sourceVersion()
            });
            if (inserted != 1) {
                throw new IllegalStateException("citation binding did not persist exactly one row");
            }
        }
        return List.copyOf(canonical);
    }

    /**
     * Loads immutable citations for transcript projection. URI/version/status are selected from
     * revision/evidence tables, never from mutable API input or the historical row's duplicate
     * display columns.
     */
    public Map<String, List<EvidenceReference>> loadByMessageIds(Collection<String> messageIds) {
        Set<String> ids = normalizedIds(messageIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String markers = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<LoadedReference> rows = jdbc.query("""
                        select mcr.message_id, mcr.article_id, mcr.article_revision_id,
                               ar.revision_number, mcr.evidence_snapshot_id,
                               mcr.source_span_start, mcr.source_span_end,
                               es.source_uri, es.source_version, ws.approval_status as revision_status
                        from message_citation_references mcr
                        join article_governance ag on ag.article_id = mcr.article_id
                          and ag.current_revision_id = mcr.article_revision_id
                        join article_revisions ar on ar.id = ag.current_revision_id
                          and ar.article_id = ag.article_id
                          and ar.workspace_id = ag.workspace_id
                          and ar.classification = ag.classification
                        join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                          and ws.article_id = ar.article_id
                          and ws.workspace_id = ar.workspace_id
                          and ws.classification = ar.classification
                        join article_evidence_snapshots es on es.id = mcr.evidence_snapshot_id
                          and es.article_revision_id = ar.id
                        where mcr.message_id in (%s)
                          and ag.approval_status = 'approved'
                          and ag.lifecycle_status = 'active'
                          and (ag.expires_at is null or ag.expires_at > clock_timestamp())
                          and ws.approval_status = 'approved'
                          and ws.lifecycle_status = 'active'
                          and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                        order by mcr.message_id, mcr.ordinal
                        """.formatted(markers),
                (rs, row) -> new LoadedReference(
                        rs.getString("message_id"),
                        new EvidenceReference(
                                rs.getString("article_id"),
                                rs.getString("article_revision_id"),
                                rs.getInt("revision_number"),
                                rs.getString("evidence_snapshot_id"),
                                rs.getInt("source_span_start"),
                                rs.getInt("source_span_end"),
                                rs.getString("source_uri"),
                                rs.getString("source_version"),
                                rs.getString("revision_status"))),
                ids.toArray());
        Map<String, List<EvidenceReference>> grouped = new LinkedHashMap<>();
        for (LoadedReference row : rows) {
            grouped.computeIfAbsent(row.messageId(), ignored -> new ArrayList<>()).add(row.reference());
        }
        Map<String, List<EvidenceReference>> immutable = new LinkedHashMap<>();
        grouped.forEach((messageId, references) -> immutable.put(messageId, List.copyOf(references)));
        return Map.copyOf(immutable);
    }

    /**
     * Revalidates historical rows at the transcript boundary. A row that is no longer the
     * exact current-approved evidence for this subject is rejected rather than projected as
     * an article-id-only citation.
     */
    public List<EvidenceReference> resolveCurrentApproved(String subject,
                                                          Collection<EvidenceReference> persisted) {
        if (blank(subject)) {
            throw new IllegalArgumentException("citation replay requires an authenticated subject");
        }
        if (persisted == null || persisted.isEmpty()) {
            return List.of();
        }
        List<EvidenceReference> canonical = new ArrayList<>();
        for (EvidenceReference reference : persisted) {
            EvidenceReference resolved = resolver.resolveCurrentApproved(subject, reference)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "historical citation is no longer current approved evidence"));
            if (!sameEvidenceIdentity(reference, resolved)) {
                throw new IllegalArgumentException("historical citation resolved to a different evidence identity");
            }
            canonical.add(resolved);
        }
        return List.copyOf(canonical);
    }

    private List<EvidenceReference> canonicalize(String subject, Collection<EvidenceReference> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<CitationIdentity, EvidenceReference> unique = new LinkedHashMap<>();
        for (EvidenceReference candidate : candidates) {
            EvidenceReference canonical = resolver.resolveCurrentApproved(subject, candidate)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "citation does not resolve to current approved evidence"));
            unique.putIfAbsent(CitationIdentity.from(canonical), canonical);
        }
        return List.copyOf(unique.values());
    }

    private static Set<String> normalizedIds(Collection<String> ids) {
        Set<String> normalized = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(id -> !blank(id)).forEach(normalized::add);
        }
        return normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean sameEvidenceIdentity(EvidenceReference left, EvidenceReference right) {
        return left != null && right != null
                && java.util.Objects.equals(left.articleId(), right.articleId())
                && java.util.Objects.equals(left.articleRevisionId(), right.articleRevisionId())
                && left.revisionNumber() == right.revisionNumber()
                && java.util.Objects.equals(left.evidenceSnapshotId(), right.evidenceSnapshotId())
                && java.util.Objects.equals(left.sourceSpanStart(), right.sourceSpanStart())
                && java.util.Objects.equals(left.sourceSpanEnd(), right.sourceSpanEnd());
    }

    private record CitationIdentity(String articleId, String articleRevisionId, int revisionNumber,
                                    String snapshotId, Integer spanStart, Integer spanEnd) {
        static CitationIdentity from(EvidenceReference reference) {
            return new CitationIdentity(reference.articleId(), reference.articleRevisionId(),
                    reference.revisionNumber(), reference.evidenceSnapshotId(),
                    reference.sourceSpanStart(), reference.sourceSpanEnd());
        }
    }

    private record LoadedReference(String messageId, EvidenceReference reference) {
    }
}
