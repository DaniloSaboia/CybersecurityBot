package com.sentinel.kms.audit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Whitelist and scalar validator for the only metadata audit persistence accepts. */
public final class AuditMetadata {

    public static final String EXPECTED_STATE_VERSION = "expected_state_version";
    public static final String ACTUAL_STATE_VERSION = "actual_state_version";
    public static final String FROM_REVISION_ID = "from_revision_id";
    public static final String TO_REVISION_ID = "to_revision_id";
    public static final String REVIEW_DUE_AT = "review_due_at";
    public static final String EXPIRES_AT = "expires_at";
    public static final String REASON_CODE = "reason_code";
    public static final String SOURCE_TYPE = "source_type";
    public static final String SOURCE_ID = "source_id";
    public static final String SOURCE_REVISION_ID = "source_revision_id";
    public static final String SIZE_BYTES = "size_bytes";
    public static final String RETAINED = "retained";
    public static final String IDEMPOTENCY_KEY_PRESENT = "idempotency_key_present";
    public static final String ATTEMPT = "attempt";
    public static final String GENERATION = "generation";
    public static final String CHUNK_COUNT = "chunk_count";
    public static final String VECTOR_ROWS_DELETED = "vector_rows_deleted";
    public static final String FAILURE_CODE = "failure_code";
    public static final String LIMIT = "limit";
    public static final String RETURNED_COUNT = "returned_count";

    private static final Set<String> NUMBER_KEYS = Set.of(
            EXPECTED_STATE_VERSION, ACTUAL_STATE_VERSION, SIZE_BYTES, ATTEMPT, GENERATION,
            CHUNK_COUNT, VECTOR_ROWS_DELETED, LIMIT, RETURNED_COUNT,
            "requested", "allowed", "matches", "vectorCandidates", "lexicalCandidates", "returned");
    private static final Set<String> BOOLEAN_KEYS = Set.of(RETAINED, IDEMPOTENCY_KEY_PRESENT, "created");
    private static final Set<String> IDENTIFIER_KEYS = Set.of(
            FROM_REVISION_ID, TO_REVISION_ID, SOURCE_ID, SOURCE_REVISION_ID);
    private static final Set<String> CODE_KEYS = Set.of(REASON_CODE, FAILURE_CODE);
    private static final Set<String> TIMESTAMP_KEYS = Set.of(REVIEW_DUE_AT, EXPIRES_AT);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private AuditMetadata() {
    }

    /** Drops rather than serializes unknown, nested, binary, or prose metadata. */
    public static Map<String, Object> sanitize(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            Object normalized = normalize(key, value);
            if (normalized != null) {
                safe.put(key, normalized);
            }
        });
        return Map.copyOf(safe);
    }

    private static Object normalize(String key, Object value) {
        if (key == null || value == null) {
            return null;
        }
        if (NUMBER_KEYS.contains(key)) {
            return nonNegativeLong(value);
        }
        if (BOOLEAN_KEYS.contains(key)) {
            return value instanceof Boolean bool ? bool : null;
        }
        if (IDENTIFIER_KEYS.contains(key)) {
            return value instanceof String text && AuditRedaction.isIdentifier(text) ? text : null;
        }
        if (CODE_KEYS.contains(key)) {
            return value instanceof String text && CODE.matcher(text).matches() ? text : null;
        }
        if (TIMESTAMP_KEYS.contains(key)) {
            return normalizedTimestamp(value);
        }
        if (SOURCE_TYPE.equals(key)) {
            return value instanceof String text && ("upload".equals(text) || "url".equals(text)) ? text : null;
        }
        return null;
    }

    private static Long nonNegativeLong(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            return result >= 0 ? result : null;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    private static String normalizedTimestamp(Object value) {
        if (!(value instanceof String text) || text.length() > 64) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
