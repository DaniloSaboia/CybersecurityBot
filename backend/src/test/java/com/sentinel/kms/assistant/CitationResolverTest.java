package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationResolverTest {

    @Test
    void streamingResolverDoesNotLeakSplitUnknownOrSpoofedEvidenceTokens() {
        CitationResolver resolver = resolver();
        CitationResolver.StreamingSanitizer stream = resolver.streamingSanitizer();

        String first = stream.accept("Fact [[evidence:TI-1|revision-1|snapshot-1|");
        String second = stream.accept("0-8]] then [[evidence:TI-1|revision-1|snapshot-1|1-8]] and [FA");
        String third = stream.accept("KE-1] done");
        String tail = stream.finish();

        assertThat(first + second + third + tail)
                .isEqualTo("Fact [TI-1] then  and  done")
                .doesNotContain("[[evidence:")
                .doesNotContain("FAKE-1");
        assertThat(stream.citations().legacyIds()).containsExactly("TI-1");
        assertThat(stream.citations().references()).containsExactly(new EvidenceReference(
                "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                "https://example.test/source", "2026-09-05", "APPROVED"));
    }

    @Test
    void noAllowedEvidenceDropsEveryCompleteCitationTokenAcrossStreamBoundaries() {
        CitationResolver.StreamingSanitizer stream = CitationResolver.legacyOnly(Set.of()).streamingSanitizer();

        String output = stream.accept("No [FA") + stream.accept("KE-1] or [TI-1]") + stream.finish();

        assertThat(output).isEqualTo("No  or ");
        assertThat(stream.citations().legacyIds()).isEmpty();
        assertThat(stream.citations().references()).isEmpty();
    }

    @Test
    void sourceUploadWithoutArticleIdentityIsIgnoredByCitationRegistry() {
        RetrievedChunk upload = new RetrievedChunk("private upload", 1.0, Map.of(
                "source_id", "upload-1",
                "source_revision_id", "revision-1",
                "source_key", "private-upload.pdf",
                "owner_sub", "analyst-1"));

        CitationResolver resolver = CitationResolver.fromAuthorizedChunks(List.of(upload), Set.of());

        assertThat(resolver.evidenceTokenFor(upload)).isNull();
        assertThat(resolver.newCitations().references()).isEmpty();
    }

    @Test
    void serverBoundRegistryRejectsAnExistingArticleWithForgedRevisionOrSnapshot() {
        RetrievedChunk forged = new RetrievedChunk("source", 1.0, Map.of(
                "article_id", "TI-1",
                "article_revision_id", "forged-revision",
                "article_revision_number", 1,
                "evidence_snapshot_id", "forged-snapshot",
                "source_span_start", 0,
                "source_span_end", 8));
        EvidenceReference canonical = new EvidenceReference(
                "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                "https://authoritative.example/source", "2026-09-06", "approved");

        CitationResolver resolver = CitationResolver.fromServerBoundEvidence(
                List.of(forged), List.of(canonical), Set.of("TI-1"));

        assertThat(resolver.evidenceTokenFor(forged)).isNull();
        CitationResolver.Citations citations = resolver.newCitations();
        assertThat(resolver.sanitize(
                "Fact [[evidence:TI-1|forged-revision|forged-snapshot|0-8]]", citations))
                .isEqualTo("Fact ");
        assertThat(citations.references()).isEmpty();
    }

    @Test
    void serverBoundRegistryUsesCanonicalSourceFieldsInsteadOfVectorMetadata() {
        RetrievedChunk chunk = new RetrievedChunk("source", 1.0, Map.of(
                "article_id", "TI-1",
                "article_revision_id", "revision-1",
                "article_revision_number", 1,
                "evidence_snapshot_id", "snapshot-1",
                "source_span_start", 0,
                "source_span_end", 8,
                "source_uri", "https://forged.example/source",
                "source_version", "wrong",
                "revision_status", "draft"));
        EvidenceReference canonical = new EvidenceReference(
                "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                "https://authoritative.example/source", "2026-09-06", "approved");

        CitationResolver resolver = CitationResolver.fromServerBoundEvidence(
                List.of(chunk), List.of(canonical), Set.of("TI-1"));
        CitationResolver.Citations citations = resolver.newCitations();
        String safe = resolver.sanitize(
                "Fact [[evidence:TI-1|revision-1|snapshot-1|0-8]]", citations);

        assertThat(safe).isEqualTo("Fact [TI-1]");
        assertThat(citations.references()).containsExactly(canonical);
    }

    @Test
    void serverBoundRegistryRejectsBareArticleIdsUntilAnExactEvidenceTokenIsValidated() {
        RetrievedChunk chunk = new RetrievedChunk("source", 1.0, Map.of(
                "article_id", "TI-1",
                "article_revision_id", "revision-1",
                "article_revision_number", 1,
                "evidence_snapshot_id", "snapshot-1",
                "source_span_start", 0,
                "source_span_end", 8));
        EvidenceReference canonical = new EvidenceReference(
                "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                "https://authoritative.example/source", "2026-09-06", "approved");
        CitationResolver resolver = CitationResolver.fromServerBoundEvidence(
                List.of(chunk), List.of(canonical), Set.of("TI-1"));
        CitationResolver.Citations citations = resolver.newCitations();

        String safe = resolver.sanitize("Fact [TI-1]", citations);

        assertThat(safe).isEqualTo("Fact ");
        assertThat(citations.legacyIds()).isEmpty();
        assertThat(citations.references()).isEmpty();
    }

    @Test
    void incompleteCitationSyntaxAtEndOfStreamIsSuppressed() {
        CitationResolver.StreamingSanitizer stream = CitationResolver.legacyOnly(Set.of()).streamingSanitizer();

        String output = stream.accept("Fact [[evidence:TI-1|forged-revision") + stream.finish();

        assertThat(output).isEqualTo("Fact ");
        assertThat(stream.citations().legacyIds()).isEmpty();
    }

    @Test
    void oversizedUnfinishedEvidenceTokenIsDroppedInsteadOfFlushedAsUnverifiedText() {
        CitationResolver.StreamingSanitizer stream = resolver().streamingSanitizer();

        String output = stream.accept("Fact [[evidence:" + "x".repeat(600)) + stream.finish();

        assertThat(output).doesNotContain("[[evidence:");
        assertThat(stream.citations().legacyIds()).isEmpty();
        assertThat(stream.citations().references()).isEmpty();
    }

    private static CitationResolver resolver() {
        RetrievedChunk chunk = new RetrievedChunk("source", 1.0, Map.of(
                "article_id", "TI-1",
                "article_revision_id", "revision-1",
                "article_revision_number", 1,
                "evidence_snapshot_id", "snapshot-1",
                "source_span_start", 0,
                "source_span_end", 8,
                "source_uri", "https://example.test/source",
                "source_version", "2026-09-05",
                "revision_status", "APPROVED"));
        return CitationResolver.fromAuthorizedChunks(List.of(chunk), Set.of("TI-1"));
    }
}
