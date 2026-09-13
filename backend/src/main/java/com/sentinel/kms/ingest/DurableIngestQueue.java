package com.sentinel.kms.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed queue coordination for ingestion jobs.
 *
 * <p>Every state change is fenced by both a random lease token and the durable
 * generation.  An expired worker gets a new generation before it can resume,
 * which prevents a stale process from finishing or indexing after recovery,
 * retry, or deletion.</p>
 */
@Component
public class DurableIngestQueue {

    private final JdbcTemplate jdbc;
    private final long leaseMillis;
    private final String workerId;

    @Autowired
    public DurableIngestQueue(
            JdbcTemplate jdbc,
            @Value("${app.ingest.claim-lease-ms:900000}") long leaseMillis,
            @Value("${HOSTNAME:sentinel-ingest}") String nodeName) {
        this(jdbc, leaseMillis, nodeName + ":" + UUID.randomUUID(), true);
    }

    DurableIngestQueue(JdbcTemplate jdbc, long leaseMillis, String workerId, boolean exactWorkerId) {
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("Ingestion claim lease must be positive");
        }
        this.jdbc = jdbc;
        this.leaseMillis = leaseMillis;
        this.workerId = workerId;
    }

    /**
     * Atomically claim one queued job, or recover exactly one job whose lease
     * expired.  {@code SKIP LOCKED} lets multiple application instances poll
     * without blocking or processing the same source twice.
     */
    public Optional<DurableJobClaim> claimNext() {
        String token = UUID.randomUUID().toString();
        List<DurableJobClaim> claims = jdbc.query("""
                        with candidate as (
                            select id
                            from ingest_jobs
                            where deleted_at is null
                              and coalesce(is_demo, false) = false
                              and source_id is not null
                              and (
                                   stage = 'queued'
                                   or (stage in ('extracting', 'chunking', 'embedding')
                                       and (claim_until is null or claim_until < now()))
                              )
                            order by case when stage = 'queued' then 0 else 1 end, created_at
                            for update skip locked
                            limit 1
                        )
                        update ingest_jobs job
                        set claim_token = ?,
                            claimed_by = ?,
                            claimed_at = now(),
                            claim_until = now() + (? * interval '1 millisecond'),
                            attempt = coalesce(job.attempt, 0) + 1,
                            index_generation = case
                                when job.stage = 'queued' then greatest(job.index_generation, 1)
                                else job.index_generation + 1
                            end,
                            stage = 'extracting',
                            pct = 0,
                            error = null,
                            when_label = 'now'
                        from candidate
                        where job.id = candidate.id
                        returning job.id, job.claim_token, job.index_generation
                        """,
                (rs, row) -> new DurableJobClaim(
                        rs.getString("id"), rs.getString("claim_token"), rs.getLong("index_generation")),
                token, workerId, leaseMillis);
        return claims.stream().findFirst();
    }

    /** Extends an active lease; a lost/expired claim cannot be resurrected. */
    public boolean heartbeat(DurableJobClaim claim) {
        return jdbc.update("""
                        update ingest_jobs
                        set claim_until = now() + (? * interval '1 millisecond')
                        where id = ? and claim_token = ? and index_generation = ?
                          and deleted_at is null and claim_until > now()
                        """,
                leaseMillis, claim.jobId(), claim.token(), claim.generation()) == 1;
    }

    public boolean progress(DurableJobClaim claim, JobStage stage, double pct, int chunks) {
        return jdbc.update("""
                        update ingest_jobs
                        set stage = ?, pct = ?, chunks = ?, when_label = 'now'
                        where id = ? and claim_token = ? and index_generation = ?
                          and deleted_at is null and claim_until > now()
                        """,
                stage.id(), pct, chunks, claim.jobId(), claim.token(), claim.generation()) == 1;
    }

    /**
     * Retains first-fetch URL bytes only while this exact claim is live.  This
     * conditional update matters because a delete may remove the source while a
     * slow HTTP fetch is still in flight.
     */
    public boolean retainFetchedSource(
            DurableJobClaim claim, String sourceId, byte[] bytes, String sha256) {
        return jdbc.update("""
                        update ingest_job_sources source
                        set source_bytes = ?, source_size_bytes = ?, source_sha256 = ?
                        from ingest_jobs job
                        where source.id = ? and source.job_id = job.id
                          and job.id = ? and job.claim_token = ?
                          and job.index_generation = ? and job.deleted_at is null
                          and source.source_bytes is null
                          and job.claim_until > now()
                        """,
                bytes, bytes.length, sha256, sourceId,
                claim.jobId(), claim.token(), claim.generation()) == 1;
    }

    public boolean complete(DurableJobClaim claim, int chunks) {
        return jdbc.update("""
                        update ingest_jobs
                        set stage = 'indexed', pct = 100, chunks = ?, error = null,
                            when_label = 'now', claim_token = null, claim_until = null
                        where id = ? and claim_token = ? and index_generation = ?
                          and deleted_at is null and claim_until > now()
                        """,
                chunks, claim.jobId(), claim.token(), claim.generation()) == 1;
    }

    /** A stale worker cannot turn a retried/deleted job back into failed. */
    public boolean fail(DurableJobClaim claim, String error) {
        return jdbc.update("""
                        update ingest_jobs
                        set stage = 'failed', error = ?, when_label = 'now',
                            claim_token = null, claim_until = null
                        where id = ? and claim_token = ? and index_generation = ?
                          and deleted_at is null and claim_until > now()
                        """,
                error, claim.jobId(), claim.token(), claim.generation()) == 1;
    }

    /**
     * Only a failed, live job can be manually retried.  Incrementing the
     * generation invalidates any late work from the previous attempt.
     */
    public boolean requeueFailed(String jobId, String ownerSub) {
        return jdbc.update("""
                        update ingest_jobs
                        set stage = 'queued', pct = 0, chunks = 0, error = null,
                            when_label = 'now', claim_token = null, claim_until = null,
                            index_generation = index_generation + 1
                        where id = ? and owner_sub = ? and stage = 'failed'
                          and deleted_at is null
                        """, jobId, ownerSub) == 1;
    }

    /**
     * Tombstone first, then let the caller remove vectors/source in the same
     * transaction.  A vector replacement that begins after this update sees a
     * missing/mismatched generation and is rejected by the vectorstore seam.
     */
    public Optional<DurableJobTombstone> tombstone(String jobId, String ownerSub) {
        List<DurableJobTombstone> tombstones = jdbc.query("""
                        update ingest_jobs
                        set deleted_at = now(), cancelled_at = now(), stage = 'cancelled',
                            pct = 0, error = null, claim_token = null, claimed_by = null,
                            claimed_at = null, claim_until = null,
                            index_generation = index_generation + 1
                        where id = ? and owner_sub = ? and deleted_at is null
                        returning id, source_key
                        """,
                (rs, row) -> new DurableJobTombstone(rs.getString("id"), rs.getString("source_key")),
                jobId, ownerSub);
        return tombstones.stream().findFirst();
    }
}
