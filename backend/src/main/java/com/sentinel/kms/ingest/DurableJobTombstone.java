package com.sentinel.kms.ingest;

/** Result of fencing a job before deleting its durable source and vectors. */
public record DurableJobTombstone(String jobId, String sourceKey) {
}
