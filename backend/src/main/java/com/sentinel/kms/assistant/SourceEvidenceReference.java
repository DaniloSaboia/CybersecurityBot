package com.sentinel.kms.assistant;

import java.util.Map;
import java.util.Optional;

/** Internal durable identity for a non-article upload used to ground an assistant turn. */
record SourceEvidenceReference(String sourceId, String sourceRevisionId, String sourceKey) {

    static Optional<SourceEvidenceReference> fromMetadata(Map<String, ?> metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        SourceEvidenceReference reference = new SourceEvidenceReference(
                value(metadata, "source_id"), value(metadata, "source_revision_id"), value(metadata, "source_key"));
        return reference.isCanonical() ? Optional.of(reference) : Optional.empty();
    }

    boolean isCanonical() {
        return !blank(sourceId) && !blank(sourceRevisionId) && !blank(sourceKey)
                && sourceKey.equals(sourceId + ":" + sourceRevisionId);
    }

    private static String value(Map<String, ?> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
