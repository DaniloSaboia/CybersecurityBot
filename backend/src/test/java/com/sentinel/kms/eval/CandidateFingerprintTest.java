package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateFingerprintTest {

    private final ObjectMapper canonicalJson = new ObjectMapper().findAndRegisterModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void bindsTheRequiredBaselineCaseAndCandidateProjectionAsCanonicalSortedJson() throws Exception {
        AnswerCandidate candidate = new AnswerCandidate("Synthetic answer", List.of(new CitationReference(
                "FIX-1", "revision-1", 1, "snapshot-1", 0, 4,
                "https://fixture.example/1", "v1", "approved")), List.of("FIX-LEGACY"),
                new EvaluationMetadata(EvaluationExecutionMode.OFFLINE_DETERMINISTIC, "fixture", "model-a",
                        Instant.parse("2026-09-06T00:00:00Z"), 12,
                        new CostMetadata("USD", "0.001", true, "fixture", 4L, 5L)));

        Map<String, Object> expected = new TreeMap<>();
        expected.put("baseline", "RAG");
        expected.put("caseId", "case-1");
        expected.put("candidate", Map.of(
                "answer", candidate.answer(),
                "citationReferences", List.of(Map.of(
                        "articleId", "FIX-1", "articleRevisionId", "revision-1", "revisionNumber", 1,
                        "evidenceSnapshotId", "snapshot-1", "sourceSpanStart", 0, "sourceSpanEnd", 4,
                        "sourceUri", "https://fixture.example/1", "sourceVersion", "v1", "revisionStatus", "approved")),
                "legacyCitationIds", List.of("FIX-LEGACY"),
                "metadata", Map.of(
                        "executionMode", "OFFLINE_DETERMINISTIC", "provider", "fixture", "model", "model-a",
                        "startedAt", "2026-09-06T00:00:00Z", "latencyMs", 12,
                        "cost", Map.of("currency", "USD", "amount", "0.001", "estimated", true,
                                "costSource", "fixture", "inputTokens", 4, "outputTokens", 5))));

        assertThat(CandidateFingerprint.sha256(AnswerBaseline.RAG, "case-1", candidate))
                .isEqualTo(sha256(canonicalJson.writeValueAsBytes(expected)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }
}
