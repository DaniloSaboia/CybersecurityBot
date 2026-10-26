package com.sentinel.kms.ingest;

/** A fenced, leased claim issued by the database to exactly one worker. */
public record DurableJobClaim(String jobId, String token, long generation) {
}
