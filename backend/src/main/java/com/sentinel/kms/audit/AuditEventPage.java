package com.sentinel.kms.audit;

import java.util.List;

/** A bounded descending page of audit events and an opaque keyset continuation cursor. */
public record AuditEventPage(List<AuditEventDto> events, String nextCursor) {

    public AuditEventPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
