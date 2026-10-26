package com.sentinel.kms.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Database-backed request-key reservation for durable ingest creation.
 *
 * <p>The unique owner/key index is the concurrency boundary.  A winner writes
 * its mapping and source/job in one surrounding service transaction; a loser
 * observes the committed immutable mapping and either replays its job or gets a
 * conflict for a different request fingerprint.</p>
 */
@Component
public class IngestIdempotencyStore {

    static final int MAX_KEY_LENGTH = 200;

    private final JdbcTemplate jdbc;

    public IngestIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve {@code ownerSub/key}, or resolve its existing immutable mapping.
     * The caller must be in the transaction that later persists {@code jobId}.
     */
    public IngestIdempotencyReservation reserve(
            String ownerSub,
            String requestKind,
            String key,
            String payloadSha256,
            String jobId) {
        validate(ownerSub, requestKind, key, payloadSha256, jobId);

        List<String> inserted = jdbc.query("""
                        insert into ingest_request_idempotency (
                            id, owner_sub, idempotency_key, request_kind, payload_sha256, job_id
                        ) values (?, ?, ?, ?, ?, ?)
                        on conflict (owner_sub, idempotency_key) do nothing
                        returning job_id
                        """,
                (rs, row) -> rs.getString("job_id"),
                UUID.randomUUID().toString(), ownerSub, key, requestKind, payloadSha256, jobId);
        if (!inserted.isEmpty()) {
            return IngestIdempotencyReservation.claimed(jobId);
        }

        List<ExistingMapping> existing = jdbc.query("""
                        select request_kind, payload_sha256, job_id
                        from ingest_request_idempotency
                        where owner_sub = ? and idempotency_key = ?
                        """,
                (rs, row) -> new ExistingMapping(
                        rs.getString("request_kind"),
                        rs.getString("payload_sha256"),
                        rs.getString("job_id")),
                ownerSub, key);
        if (existing.isEmpty()) {
            // A unique-conflict insert waits for the competing transaction, so
            // this is only possible after an unexpected out-of-band deletion.
            throw new IdempotencyConflictException("Idempotency-Key reservation is unavailable; retry the request");
        }
        ExistingMapping mapping = existing.get(0);
        if (!requestKind.equals(mapping.requestKind()) || !payloadSha256.equals(mapping.payloadSha256())) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different request");
        }
        return IngestIdempotencyReservation.replay(mapping.jobId());
    }

    private static void validate(
            String ownerSub, String requestKind, String key, String payloadSha256, String jobId) {
        if (ownerSub == null || ownerSub.isBlank()) {
            throw new IllegalArgumentException("Authenticated subject is required");
        }
        if (!"upload".equals(requestKind) && !"url".equals(requestKind)) {
            throw new IllegalArgumentException("Unsupported idempotency request kind");
        }
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to " + MAX_KEY_LENGTH + " characters");
        }
        if (payloadSha256 == null || !payloadSha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Idempotency payload fingerprint is invalid");
        }
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Idempotency job id is required");
        }
    }

    private record ExistingMapping(String requestKind, String payloadSha256, String jobId) {
    }
}
