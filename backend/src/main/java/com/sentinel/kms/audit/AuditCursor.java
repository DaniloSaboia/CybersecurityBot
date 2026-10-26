package com.sentinel.kms.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Opaque, URL-safe cursor for the immutable audit-event ordering. */
public final class AuditCursor {

    private static final int MAX_ENCODED_LENGTH = 512;

    private AuditCursor() {
    }

    public static String encode(Instant occurredAt, String id) {
        if (occurredAt == null || !AuditRedaction.isIdentifier(id)) {
            throw new IllegalArgumentException("Invalid audit cursor");
        }
        String payload = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static Value decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw invalid();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = payload.indexOf('|');
            if (separator <= 0 || separator != payload.lastIndexOf('|')) {
                throw invalid();
            }
            Instant occurredAt = Instant.parse(payload.substring(0, separator));
            String id = payload.substring(separator + 1);
            if (!AuditRedaction.isIdentifier(id)) {
                throw invalid();
            }
            return new Value(occurredAt, id);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid audit cursor");
    }

    record Value(Instant occurredAt, String id) {
    }
}
