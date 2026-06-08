package com.sentinel.kms.kb;

/**
 * Article severity. {@code id()} is the lowercase wire value stored in the DB
 * and used by the API/UI; {@code rank()} (4..0) drives the "severity" sort.
 */
public enum Severity {
    CRITICAL("CRITICAL", 4),
    HIGH("HIGH", 3),
    MEDIUM("MEDIUM", 2),
    LOW("LOW", 1),
    INFO("INFO", 0);

    private final String label;
    private final int rank;

    Severity(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public int rank() {
        return rank;
    }

    /** Lowercase wire id, e.g. "critical". */
    public String id() {
        return name().toLowerCase();
    }

    public static Severity fromId(String id) {
        return valueOf(id.toUpperCase());
    }

    public static int rankOf(String id) {
        try {
            return fromId(id).rank();
        } catch (Exception e) {
            return 0;
        }
    }
}
