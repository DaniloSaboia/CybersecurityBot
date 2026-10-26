package com.sentinel.kms.assistant;

import com.sentinel.kms.kb.KnowledgeAccessService;
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
 * Durable, non-public provenance for upload-grounded assistant turns. The caller's subject is
 * rechecked before write and again by {@link ConversationService} before any transcript replay.
 */
@Service
public class MessageSourceReferenceStore {

    private static final String INSERT = """
            insert into message_source_references (
                id, message_id, ordinal, source_id, source_revision_id, source_key)
            values (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final KnowledgeAccessService access;

    public MessageSourceReferenceStore(JdbcTemplate jdbc, KnowledgeAccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Writes only the canonical source tuple that the current subject may still read. */
    public List<SourceEvidenceReference> bind(String subject, String messageId,
                                              Collection<SourceEvidenceReference> candidates) {
        if (blank(subject) || blank(messageId)) {
            throw new IllegalArgumentException("source binding requires an authenticated subject and message id");
        }
        List<SourceEvidenceReference> approved = approve(subject, candidates);
        for (int ordinal = 0; ordinal < approved.size(); ordinal++) {
            SourceEvidenceReference reference = approved.get(ordinal);
            int inserted = jdbc.update(INSERT, new Object[]{
                    "source-reference:" + messageId + ":" + ordinal,
                    messageId,
                    ordinal,
                    reference.sourceId(),
                    reference.sourceRevisionId(),
                    reference.sourceKey()
            });
            if (inserted != 1) {
                throw new IllegalStateException("source reference did not persist exactly one row");
            }
        }
        return List.copyOf(approved);
    }

    /** Reads immutable source tuples; authorization happens at the transcript boundary. */
    public Map<String, List<SourceEvidenceReference>> loadByMessageIds(Collection<String> messageIds) {
        Set<String> ids = normalizedIds(messageIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String markers = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<LoadedReference> rows = jdbc.query("""
                        select msr.message_id, msr.source_id, msr.source_revision_id, msr.source_key
                        from message_source_references msr
                        where msr.message_id in (%s)
                        order by msr.message_id, msr.ordinal
                        """.formatted(markers),
                (rs, row) -> new LoadedReference(rs.getString("message_id"),
                        new SourceEvidenceReference(rs.getString("source_id"),
                                rs.getString("source_revision_id"), rs.getString("source_key"))),
                ids.toArray());
        Map<String, List<SourceEvidenceReference>> grouped = new LinkedHashMap<>();
        for (LoadedReference row : rows) {
            grouped.computeIfAbsent(row.messageId(), ignored -> new ArrayList<>()).add(row.reference());
        }
        Map<String, List<SourceEvidenceReference>> immutable = new LinkedHashMap<>();
        grouped.forEach((messageId, references) -> immutable.put(messageId, List.copyOf(references)));
        return Map.copyOf(immutable);
    }

    private List<SourceEvidenceReference> approve(String subject,
                                                  Collection<SourceEvidenceReference> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<SourceEvidenceReference> approved = new LinkedHashSet<>();
        for (SourceEvidenceReference reference : candidates) {
            if (reference == null || !reference.isCanonical()) {
                throw new IllegalArgumentException("source reference lacks a canonical source identity");
            }
            try {
                var decision = access.canReadSource(subject, reference.sourceId(),
                        reference.sourceRevisionId(), reference.sourceKey());
                if (decision == null || !decision.allowed()) {
                    throw new IllegalArgumentException("source reference does not resolve to a readable live source");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("source reference does not resolve to a readable live source", e);
            }
            approved.add(reference);
        }
        return List.copyOf(approved);
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

    private record LoadedReference(String messageId, SourceEvidenceReference reference) {
    }
}
