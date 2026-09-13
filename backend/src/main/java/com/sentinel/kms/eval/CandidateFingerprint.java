package com.sentinel.kms.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable SHA-256 binding for a canonical sorted-JSON projection of one saved
 * answer candidate: {@code {baseline, caseId, candidate}}. The digest is a
 * binding mechanism, not an assessment of answer truth.
 */
public final class CandidateFingerprint {

    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper().findAndRegisterModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CandidateFingerprint() {
    }

    public static String sha256(AnswerBaseline baseline, String caseId, AnswerCandidate candidate) {
        if (baseline == null) {
            throw new IllegalArgumentException("baseline is required");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }

        Map<String, Object> projection = new TreeMap<>();
        projection.put("baseline", baseline.name());
        projection.put("caseId", caseId.trim());
        projection.put("candidate", candidateProjection(candidate));
        try {
            return sha256Bytes(CANONICAL_JSON.writeValueAsBytes(projection));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Candidate fingerprint serialization failed", serializationFailure);
        }
    }

    static String sha256Bytes(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static Map<String, Object> candidateProjection(AnswerCandidate candidate) {
        Map<String, Object> projection = new TreeMap<>();
        projection.put("answer", candidate.answer());
        List<Map<String, Object>> citations = new ArrayList<>();
        for (CitationReference citation : candidate.citationReferences()) {
            Map<String, Object> reference = new TreeMap<>();
            reference.put("articleId", citation.articleId());
            reference.put("articleRevisionId", citation.articleRevisionId());
            reference.put("revisionNumber", citation.revisionNumber());
            reference.put("evidenceSnapshotId", citation.evidenceSnapshotId());
            reference.put("sourceSpanStart", citation.sourceSpanStart());
            reference.put("sourceSpanEnd", citation.sourceSpanEnd());
            reference.put("sourceUri", citation.sourceUri());
            reference.put("sourceVersion", citation.sourceVersion());
            reference.put("revisionStatus", citation.revisionStatus());
            citations.add(reference);
        }
        projection.put("citationReferences", citations);
        projection.put("legacyCitationIds", candidate.legacyCitationIds());
        projection.put("metadata", metadataProjection(candidate.metadata()));
        return projection;
    }

    private static Map<String, Object> metadataProjection(EvaluationMetadata metadata) {
        Map<String, Object> projection = new TreeMap<>();
        projection.put("executionMode", metadata.executionMode().name());
        projection.put("provider", metadata.provider());
        projection.put("model", metadata.model());
        projection.put("startedAt", metadata.startedAt().toString());
        projection.put("latencyMs", metadata.latencyMs());
        CostMetadata cost = metadata.cost();
        Map<String, Object> costProjection = new TreeMap<>();
        costProjection.put("currency", cost.currency());
        costProjection.put("amount", cost.amount());
        costProjection.put("estimated", cost.estimated());
        costProjection.put("costSource", cost.costSource());
        costProjection.put("inputTokens", cost.inputTokens());
        costProjection.put("outputTokens", cost.outputTokens());
        projection.put("cost", costProjection);
        return projection;
    }
}
