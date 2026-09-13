package com.sentinel.kms.audit;

import java.util.Locale;

/** Stable persisted outcomes for redacted, append-only audit events. */
public enum AuditOutcome {
    ALLOWED("allowed"),
    DENIED("denied"),
    SUCCESS("success"),
    FAILURE("failure"),
    CONFLICT("conflict");

    private final String wireValue;

    AuditOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AuditOutcome fromWire(String value) {
        if (value == null || value.isBlank()) {
            return FAILURE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AuditOutcome outcome : values()) {
            if (outcome.wireValue.equals(normalized)) {
                return outcome;
            }
        }
        return FAILURE;
    }
}
