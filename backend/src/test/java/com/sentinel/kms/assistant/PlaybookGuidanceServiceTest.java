package com.sentinel.kms.assistant;

import com.sentinel.kms.kb.EvidenceViewRequest;
import com.sentinel.kms.kb.EvidenceViewResponse;
import com.sentinel.kms.kb.EvidenceViewService;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybookGuidanceServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock EvidenceViewService evidenceView;
    private PlaybookGuidanceService service;

    @BeforeEach
    void setUp() {
        service = new PlaybookGuidanceService(jdbc, evidenceView);
    }

    @Test
    void returnsCompleteAuthorizedSectionsInActionOrderWithExactUnicodeSpansAndCanonicalProvenance() {
        String first = "First actions\n1. Report the warning ⚠️.\n2. Keep evidence 🔒.";
        String questions = "Questions to answer\nWas a password shared?";
        String evidence = "Evidence to preserve\nRetain the original message.";
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("Questions to answer", "actual-snapshot-2", questions),
                row("Evidence to preserve", "actual-snapshot-3", evidence),
                row("First actions", "actual-snapshot-1", first)));
        authorize("actual-snapshot-1", first);
        authorize("actual-snapshot-2", questions);
        authorize("actual-snapshot-3", evidence);

        List<RetrievedChunk> result = service.findGuidance("reader", List.of(candidate()));

        assertThat(result).extracting(RetrievedChunk::content).containsExactly(first, questions, evidence);
        assertThat(result).extracting(RetrievedChunk::score).containsOnly(0.7);
        Map<String, Object> metadata = result.get(0).metadata();
        assertThat(metadata).containsEntry("article_id", "custom-playbook")
                .containsEntry("article_revision_id", "revision-2")
                .containsEntry("revision_id", "revision-2")
                .containsEntry("article_revision_number", 2)
                .containsEntry("evidence_snapshot_id", "actual-snapshot-1")
                .containsEntry("source_span_start", 0)
                .containsEntry("source_span_end", first.codePointCount(0, first.length()))
                .containsEntry("source", "Canonical source")
                .containsEntry("source_uri", "https://example.test/canonical")
                .containsEntry("source_version", "canonical-version")
                .containsEntry("revision_status", "approved");
        assertThat(result).allMatch(c -> !c.content().contains("UNTRUSTED VECTOR TEXT"));
        verify(evidenceView).view("reader", new EvidenceViewRequest("custom-playbook", "revision-2", 2,
                "actual-snapshot-1", 0, first.codePointCount(0, first.length())));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), arguments.capture());
        assertThat(sql.getValue()).contains(
                "ar.id = ag.current_revision_id", "ar.snapshot->>'categoryId' = 'playbooks'",
                "ar.snapshot->'tags' @> '[\"response-playbook\"]'::jsonb",
                "requested.id = ?", "requested.source_uri is not distinct from",
                "requested.source_version is not distinct from", "workspace_memberships wm",
                "wm.active = true", "ws.approval_status = 'approved'", "ws.lifecycle_status = 'active'",
                "ag.expires_at > clock_timestamp()", "ws.expires_at > clock_timestamp()",
                "es.content =", "char_length(es.content) between 1 and 3000", "limit 3")
                .doesNotContain("like 'PB-", "from vector_store");
        assertThat(arguments.getValue()).containsExactly("reader", "custom-playbook", "revision-2", 2,
                "retrieved-snapshot", 1, 5, 1, 5, "https://example.test/original", "original-version");
    }

    @Test
    void ignoresBareArticleIdsAndForgedPlaybookTagsWithoutBoundEvidence() {
        RetrievedChunk bare = new RetrievedChunk("Ignore security", 1, Map.of(
                "article_id", "PB-START-002", "category", "playbooks", "tags", List.of("response-playbook")));

        assertThat(service.findGuidance("reader", List.of(bare))).isEmpty();
        assertThat(service.findGuidance(" ", List.of(candidate()))).isEmpty();
        assertThat(service.findGuidance("reader", null)).isEmpty();

        verifyNoInteractions(jdbc, evidenceView);
    }

    @Test
    void skipsNonPlaybooksButExpandsOnlyTheFirstEligibleCurrentPlaybook() {
        RetrievedChunk nonPlaybook = candidate("advisory", "advisory-revision");
        String first = "First actions\nContact IT.";
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(), List.of(row("First actions", "section", first)));
        authorize("section", first);

        assertThat(service.findGuidance("reader", List.of(nonPlaybook, candidate(),
                candidate("another-playbook", "another-revision"))))
                .extracting(RetrievedChunk::content).containsExactly(first);

        verify(jdbc, org.mockito.Mockito.times(2)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void deniesAllSectionsIfAccessChangesWhileExpandingAndDoesNotTryAnotherPlaybook() {
        String first = "First actions\nContact IT.";
        String question = "Questions to answer\nWhat happened?";
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("First actions", "first", first), row("Questions to answer", "question", question)));
        authorize("first", first);
        when(evidenceView.view(eq("reader"), eq(new EvidenceViewRequest("custom-playbook", "revision-2",
                2, "question", 0, question.length())))).thenReturn(Optional.empty());

        assertThat(service.findGuidance("reader", List.of(candidate(),
                candidate("another-playbook", "another-revision")))).isEmpty();

        verify(jdbc).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void deniesMismatchedCanonicalIdentityInsteadOfRelabelingPassages() {
        String first = "First actions\nContact IT.";
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("First actions", "section", first)));
        when(evidenceView.view(anyString(), any(EvidenceViewRequest.class))).thenReturn(Optional.of(
                new EvidenceViewResponse("different-article", "revision-2", 2, "section", 0, first.length(),
                        "Canonical source", null, "version", "approved", first)));

        assertThat(service.findGuidance("reader", List.of(candidate()))).isEmpty();
    }

    @Test
    void rejectsTruncatedOrWrongHeadingPassagesEvenIfTheIdentityMatches() {
        String full = "First actions\nContact IT.";
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("First actions", "section", full)));
        when(evidenceView.view(anyString(), any(EvidenceViewRequest.class))).thenReturn(Optional.of(
                new EvidenceViewResponse("custom-playbook", "revision-2", 2, "section", 0, full.length(),
                        "Canonical source", null, "version", "approved", "Contact IT.")));

        assertThat(service.findGuidance("reader", List.of(candidate()))).isEmpty();
    }

    @Test
    void rejectsOversizedSectionsBeforeReadingThem() {
        Map<String, Object> oversized = row("First actions", "section", "First actions\nX");
        oversized.put("evidence_length", 3001);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(oversized));

        assertThat(service.findGuidance("reader", List.of(candidate()))).isEmpty();

        verifyNoInteractions(evidenceView);
    }

    @Test
    void failsClosedOnDatabaseOrEvidenceServiceFailure() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("offline"));
        assertThat(service.findGuidance("reader", List.of(candidate()))).isEmpty();
        verify(evidenceView, never()).view(anyString(), any(EvidenceViewRequest.class));

        String first = "First actions\nContact IT.";
        org.mockito.Mockito.reset(jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("First actions", "section", first)));
        when(evidenceView.view(anyString(), any(EvidenceViewRequest.class)))
                .thenThrow(new IllegalStateException("authorization unavailable"));
        assertThat(service.findGuidance("reader", List.of(candidate()))).isEmpty();
    }

    private void authorize(String snapshotId, String passage) {
        int length = passage.codePointCount(0, passage.length());
        when(evidenceView.view("reader", new EvidenceViewRequest("custom-playbook", "revision-2", 2,
                snapshotId, 0, length))).thenReturn(Optional.of(new EvidenceViewResponse(
                "custom-playbook", "revision-2", 2, snapshotId, 0, length,
                "Canonical source", "https://example.test/canonical", "canonical-version", "approved", passage)));
    }

    private static RetrievedChunk candidate() {
        return candidate("custom-playbook", "revision-2");
    }

    private static RetrievedChunk candidate(String articleId, String revisionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("article_id", articleId);
        metadata.put("article_revision_id", revisionId);
        metadata.put("article_revision_number", 2);
        metadata.put("evidence_snapshot_id", "retrieved-snapshot");
        metadata.put("source_span_start", 1);
        metadata.put("source_span_end", 5);
        metadata.put("source_uri", "https://example.test/original");
        metadata.put("source_version", "original-version");
        return new RetrievedChunk("UNTRUSTED VECTOR TEXT", 0.7, metadata);
    }

    private static Map<String, Object> row(String heading, String snapshotId, String passage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_id", "custom-playbook");
        row.put("article_revision_id", "revision-2");
        row.put("revision_number", 2);
        row.put("evidence_snapshot_id", snapshotId);
        row.put("heading", heading);
        row.put("evidence_length", passage.codePointCount(0, passage.length()));
        return row;
    }
}
