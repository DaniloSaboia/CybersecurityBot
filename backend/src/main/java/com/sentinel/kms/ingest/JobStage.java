package com.sentinel.kms.ingest;

import java.util.List;

/**
 * Lifecycle of an ingestion job. A job is "in flight" until it reaches
 * {@link #INDEXED} or {@link #FAILED}. The wire value is the lowercase name.
 */
public enum JobStage {
    QUEUED,
    EXTRACTING,
    CHUNKING,
    EMBEDDING,
    INDEXED,
    FAILED,
    CANCELLED;

    /** Lowercase wire id, e.g. "embedding". */
    public String id() {
        return name().toLowerCase();
    }

    public static JobStage fromId(String id) {
        return valueOf(id.toUpperCase());
    }

    public boolean isTerminal() {
        return this == INDEXED || this == FAILED || this == CANCELLED;
    }

    /** Ordered processing stages (excludes QUEUED/FAILED) used to advance a job. */
    public static final List<JobStage> PIPELINE =
            List.of(EXTRACTING, CHUNKING, EMBEDDING, INDEXED);

    /** Next processing stage after this one, or INDEXED at the end. */
    public JobStage next() {
        int i = PIPELINE.indexOf(this);
        if (i < 0 || i + 1 >= PIPELINE.size()) {
            return INDEXED;
        }
        return PIPELINE.get(i + 1);
    }
}
