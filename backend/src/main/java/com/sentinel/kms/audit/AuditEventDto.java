package com.sentinel.kms.audit;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe representation of an audit event returned to an authorized workspace administrator. */
public record AuditEventDto(
        String id,
        OffsetDateTime occurredAt,
        String actorSub,
        String action,
        String resourceType,
        String resourceId,
        String workspaceId,
        String articleId,
        String articleRevisionId,
        String outcome,
        Map<String, Object> metadata) {

    public AuditEventDto {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        metadata = Collections.unmodifiableMap(copy);
    }
}
