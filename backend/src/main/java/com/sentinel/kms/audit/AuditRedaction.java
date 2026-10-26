package com.sentinel.kms.audit;

import java.util.regex.Pattern;

/** Central redaction boundary for fields that could otherwise carry user content. */
public final class AuditRedaction {

    public static final String REDACTED = "redacted";
    private static final String UNKNOWN_ACTOR = "system:unknown";
    private static final String UNKNOWN_ACTION = "AUDIT_REDACTED";
    private static final String UNKNOWN_RESOURCE_TYPE = "redacted";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern RESOURCE_TYPE = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private AuditRedaction() {
    }

    public static String actor(String value) {
        return identifier(value, UNKNOWN_ACTOR);
    }

    public static String action(String value) {
        return value != null && ACTION.matcher(value).matches() ? value : UNKNOWN_ACTION;
    }

    public static String resourceType(String value) {
        return value != null && RESOURCE_TYPE.matcher(value).matches() ? value : UNKNOWN_RESOURCE_TYPE;
    }

    public static String identifier(String value) {
        return identifier(value, REDACTED);
    }

    public static String nullableIdentifier(String value) {
        return isIdentifier(value) ? value : null;
    }

    public static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String identifier(String value, String fallback) {
        return isIdentifier(value) ? value : fallback;
    }
}
