package com.sentinel.kms.ingest;

import com.sentinel.kms.audit.AuditActions;
import com.sentinel.kms.audit.AuditMetadata;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.audit.AuditRedaction;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Requests only persist an immutable server-generated source/revision and
 * enqueue a {@code queued} row. A leased worker later reads bytes from
 * Postgres, so retry and restart recovery never need request-memory bytes or
 * synthesized content.
 */
@Service
public class IngestService {

    static final String SOURCE_UPLOAD = "Manual upload";
    static final String SOURCE_URL = "URL import";
    private static final String IDEMPOTENCY_UPLOAD = "upload";
    private static final String IDEMPOTENCY_URL = "url";

    private static final String UPLOAD_EMPTY = "UPLOAD_EMPTY";
    private static final String UPLOAD_SIZE_LIMIT = "UPLOAD_SIZE_LIMIT";
    private static final String UPLOAD_FILE_TYPE_UNSUPPORTED = "UPLOAD_FILE_TYPE_UNSUPPORTED";
    private static final String UPLOAD_READ_FAILED = "UPLOAD_READ_FAILED";
    private static final String URL_INVALID = "URL_INVALID";
    private static final String IDEMPOTENCY_KEY_INVALID = "IDEMPOTENCY_KEY_INVALID";
    private static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    private static final String IDEMPOTENCY_ORIGINAL_UNAVAILABLE = "IDEMPOTENCY_ORIGINAL_UNAVAILABLE";
    private static final String IDEMPOTENCY_OWNER_MISMATCH = "IDEMPOTENCY_OWNER_MISMATCH";
    private static final String RETRY_NOT_OWNER_OR_NOT_FOUND = "RETRY_NOT_OWNER_OR_NOT_FOUND";
    private static final String RETRY_STAGE_CONFLICT = "RETRY_STAGE_CONFLICT";
    private static final String RETRY_SOURCE_UNAVAILABLE = "RETRY_SOURCE_UNAVAILABLE";
    private static final String RETRY_RACE_LOST = "RETRY_RACE_LOST";
    private static final String RETRY_DELETED_RACE = "RETRY_DELETED_RACE";
    private static final String DELETE_NOT_OWNER_OR_NOT_FOUND = "DELETE_NOT_OWNER_OR_NOT_FOUND";
    private static final String VECTOR_DELETE_FAILED = "VECTOR_DELETE_FAILED";
    private static final String SOURCE_DELETE_FAILED = "SOURCE_DELETE_FAILED";

    private final IngestJobRepository jobs;
    private final ConnectorRepository connectors;
    private final IngestSourceRepository sources;
    private final DurableIngestQueue queue;
    private final IngestIdempotencyStore idempotency;
    private final EmbeddingIndexService embeddings;
    private final UrlGuard urlGuard;
    private final Set<String> allowedExtensions;
    private final long sourceMaxBytes;
    private final KnowledgeAuditService audit;

    @Autowired
    public IngestService(
            IngestJobRepository jobs,
            ConnectorRepository connectors,
            IngestSourceRepository sources,
            DurableIngestQueue queue,
            IngestIdempotencyStore idempotency,
            EmbeddingIndexService embeddings,
            UrlGuard urlGuard,
            @Value("${app.ingest.allowed-extensions:pdf,txt,md,html,htm,doc,docx,json,csv,eml,log,xml,rtf,ppt,pptx,xls,xlsx}") String allowedExtensions,
            @Value("${app.ingest.source-max-bytes:20971520}") long sourceMaxBytes,
            KnowledgeAuditService audit
    ) {
        if (sourceMaxBytes <= 0) {
            throw new IllegalArgumentException("Durable source size cap must be positive");
        }
        this.jobs = jobs;
        this.connectors = connectors;
        this.sources = sources;
        this.queue = queue;
        this.idempotency = idempotency;
        this.embeddings = embeddings;
        this.urlGuard = urlGuard;
        this.allowedExtensions = Set.of(allowedExtensions.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        // The migration's BYTEA check is a hard safety boundary.  A deployment
        // may lower the application cap, but it must not accept bytes Postgres
        // will reject later in the transaction.
        this.sourceMaxBytes = Math.min(sourceMaxBytes, IngestSource.MAX_PERSISTED_BYTES);
        this.audit = audit;
    }

    /**
     * Compatibility constructor retained for focused unit tests and callers
     * that pre-date append-only auditing. Spring uses the injected overload.
     */
    public IngestService(
            IngestJobRepository jobs,
            ConnectorRepository connectors,
            IngestSourceRepository sources,
            DurableIngestQueue queue,
            IngestIdempotencyStore idempotency,
            EmbeddingIndexService embeddings,
            UrlGuard urlGuard,
            String allowedExtensions,
            long sourceMaxBytes
    ) {
        this(jobs, connectors, sources, queue, idempotency, embeddings, urlGuard,
                allowedExtensions, sourceMaxBytes, null);
    }

    /** Persisted-governance-visible jobs for this authenticated subject, newest first. */
    public List<IngestJobDto> listJobs(String userSub) {
        return jobs.findVisibleJobs(userSub).stream()
                .map(IngestJobDto::from)
                .toList();
    }

    public List<ConnectorDto> listConnectors() {
        return connectors.findAllByOrderByCreatedAtAsc().stream()
                .map(ConnectorDto::from)
                .toList();
    }

    /** Persist an upload before exposing its queued job to a durable worker. */
    @Transactional
    public IngestJobDto createUploadJob(MultipartFile file, String userSub) {
        return createUploadJob(file, userSub, null);
    }

    /**
     * Optional owner-scoped request idempotency.  With no key, this preserves
     * the existing behavior of accepting each upload as a new document.
     */
    @Transactional
    public IngestJobDto createUploadJob(MultipartFile file, String userSub, String idempotencyKey) {
        if (file == null || file.isEmpty()) {
            recordCreateDenied(userSub, IDEMPOTENCY_UPLOAD, idempotencyKey, UPLOAD_EMPTY);
            throw new IllegalArgumentException("Upload is empty");
        }
        String normalizedKey = normalizeIdempotencyKey(userSub, IDEMPOTENCY_UPLOAD, idempotencyKey);
        if (file.getSize() > sourceMaxBytes) {
            recordCreateDenied(userSub, IDEMPOTENCY_UPLOAD, normalizedKey, UPLOAD_SIZE_LIMIT);
            throw new IllegalArgumentException("Upload exceeds durable storage limit of " + sourceMaxBytes + " bytes");
        }
        String name = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename()
                : "upload.bin";
        String ext = extensionOf(name);
        if (!allowedExtensions.contains(ext)) {
            recordCreateDenied(userSub, IDEMPOTENCY_UPLOAD, normalizedKey, UPLOAD_FILE_TYPE_UNSUPPORTED);
            throw new IllegalArgumentException(
                    "Unsupported file type ." + ext + " — allowed: " + String.join(", ", sortedExtensions()));
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            recordCreateFailure(userSub, IDEMPOTENCY_UPLOAD, normalizedKey, UPLOAD_READ_FAILED);
            throw new IllegalArgumentException("Could not read upload: " + e.getMessage());
        }
        if (data.length == 0) {
            recordCreateDenied(userSub, IDEMPOTENCY_UPLOAD, normalizedKey, UPLOAD_EMPTY);
            throw new IllegalArgumentException("Upload is empty");
        }
        if (data.length > sourceMaxBytes) {
            recordCreateDenied(userSub, IDEMPOTENCY_UPLOAD, normalizedKey, UPLOAD_SIZE_LIMIT);
            throw new IllegalArgumentException("Upload exceeds durable storage limit of " + sourceMaxBytes + " bytes");
        }

        String jobId = UUID.randomUUID().toString();
        IngestIdempotencyReservation reservation = reserve(
                userSub, IDEMPOTENCY_UPLOAD, normalizedKey,
                payloadFingerprint(IDEMPOTENCY_UPLOAD, utf8(name), utf8(file.getContentType()), data), jobId);
        if (reservation != null && !reservation.newlyClaimed()) {
            return replay(reservation, userSub);
        }
        String sourceId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        IngestJob job = newJob(jobId, name, SOURCE_UPLOAD, humanSize(data.length), userSub, sourceId, revisionId);
        jobs.save(job);
        IngestSource source = sources.save(IngestSource.upload(
                sourceId, revisionId, job.getId(), name, file.getContentType(), data));
        recordCreate(userSub, job, source, normalizedKey != null);
        return IngestJobDto.from(job);
    }

    /**
     * Create a job from a URL import: the URL is SSRF-checked synchronously
     * (so bad input fails fast with a 400). The worker retains a successful
     * response before extraction so later retry/recovery is deterministic.
     */
    @Transactional
    public IngestJobDto createUrlJob(String url, String userSub) {
        return createUrlJob(url, userSub, null);
    }

    /** Optional owner-scoped request idempotency for a URL import. */
    @Transactional
    public IngestJobDto createUrlJob(String url, String userSub, String idempotencyKey) {
        String safeUrl = url == null ? "" : url.trim();
        try {
            urlGuard.validate(safeUrl); // throws IllegalArgumentException on non-public/malformed
        } catch (IllegalArgumentException e) {
            recordCreateDenied(userSub, IDEMPOTENCY_URL, idempotencyKey, URL_INVALID);
            throw e;
        }
        String normalizedKey = normalizeIdempotencyKey(userSub, IDEMPOTENCY_URL, idempotencyKey);
        String name = deriveName(safeUrl);
        String jobId = UUID.randomUUID().toString();
        IngestIdempotencyReservation reservation = reserve(
                userSub, IDEMPOTENCY_URL, normalizedKey,
                payloadFingerprint(IDEMPOTENCY_URL, utf8(safeUrl)), jobId);
        if (reservation != null && !reservation.newlyClaimed()) {
            return replay(reservation, userSub);
        }
        String sourceId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();

        IngestJob job = newJob(jobId, name, SOURCE_URL, "URL", userSub, sourceId, revisionId);
        job.setSourceUrl(safeUrl); // display/backward compatibility; source row is authoritative
        jobs.save(job);
        IngestSource source = sources.save(IngestSource.url(sourceId, revisionId, job.getId(), name, safeUrl));
        recordCreate(userSub, job, source, normalizedKey != null);
        return IngestJobDto.from(job);
    }

    /**
     * Atomically requeue a failed durable source. Retry never fabricates text:
     * a legacy or corrupt source record gets an explicit conflict instead.
     */
    public IngestJobDto retry(String id, String userSub) {
        IngestJob job = ownedForRetry(id, userSub);
        if (!JobStage.FAILED.id().equals(job.getStage())) {
            throw retryConflict(userSub, job.getId(), RETRY_STAGE_CONFLICT, "Only failed jobs can be retried");
        }
        IngestSource source = sources.findByJobId(job.getId())
                .orElseThrow(() -> retryConflict(
                        userSub, job.getId(), RETRY_SOURCE_UNAVAILABLE,
                        "Original source is unavailable; upload or import again"));
        if (!matchesDurableSource(job, source)) {
            throw retryConflict(userSub, job.getId(), RETRY_SOURCE_UNAVAILABLE,
                    "Original source is unavailable; upload or import again");
        }
        if (SOURCE_UPLOAD.equals(job.getSource()) && !source.hasRetainedBytes()) {
            throw retryConflict(userSub, job.getId(), RETRY_SOURCE_UNAVAILABLE,
                    "Original source is unavailable; upload again");
        }
        if (SOURCE_URL.equals(job.getSource()) && !source.hasRetainedBytes()
                && (source.getSourceUrl() == null || source.getSourceUrl().isBlank())) {
            throw retryConflict(userSub, job.getId(), RETRY_SOURCE_UNAVAILABLE,
                    "Original URL source is unavailable; import again");
        }
        if (!queue.requeueFailed(job.getId(), userSub)) {
            throw retryConflict(userSub, job.getId(), RETRY_RACE_LOST,
                    "Job changed while retry was requested");
        }
        IngestJob retried = jobs.findByIdAndOwnerSubAndDeletedAtIsNull(job.getId(), userSub)
                .orElseThrow(() -> retryConflict(
                        userSub, job.getId(), RETRY_DELETED_RACE,
                        "Job was deleted while retry was requested"));
        record(userSub, AuditActions.INGEST_RETRY, "ingest_job", retried.getId(), AuditOutcome.SUCCESS,
                Map.of(
                        AuditMetadata.ATTEMPT, (long) Math.max(0, retried.getAttempt()),
                        AuditMetadata.GENERATION, Math.max(0L, retried.getIndexGeneration())));
        return IngestJobDto.from(retried);
    }

    /**
     * Fence the job before removing vectors/source. The vectorstore replacement
     * helper locks the same job generation, so an old worker cannot recreate
     * chunks after this public DELETE returns.
     */
    @Transactional
    public int deleteJob(String id, String userSub) {
        DurableJobTombstone tombstone = queue.tombstone(id, userSub)
                .orElseThrow(() -> deleteDenied(id, userSub));
        // New durable jobs delete through the immutable source key.  Old rows
        // have no source_key metadata, so retain the legacy job-id cleanup only
        // for that compatibility path; tombstoning still fences stale workers.
        int removed;
        try {
            removed = tombstone.sourceKey() == null || tombstone.sourceKey().isBlank()
                    ? embeddings.deleteByJob(tombstone.jobId())
                    : embeddings.deleteBySource(tombstone.sourceKey());
        } catch (RuntimeException e) {
            recordDeleteFailure(userSub, tombstone.jobId(), VECTOR_DELETE_FAILED);
            throw e;
        }
        try {
            sources.deleteByJobId(tombstone.jobId());
        } catch (RuntimeException e) {
            recordDeleteFailure(userSub, tombstone.jobId(), SOURCE_DELETE_FAILED);
            throw e;
        }
        Map<String, Object> metadata = Map.of(AuditMetadata.VECTOR_ROWS_DELETED, (long) Math.max(0, removed));
        record(userSub, AuditActions.INGEST_DELETE, "ingest_job", tombstone.jobId(), AuditOutcome.SUCCESS, metadata);
        record(userSub, AuditActions.INGEST_SOURCE_REMOVED, "ingest_source", tombstone.sourceKey(),
                AuditOutcome.SUCCESS, metadata);
        return removed;
    }

    private IngestJob ownedForRetry(String id, String userSub) {
        return jobs.findByIdAndOwnerSubAndDeletedAtIsNull(id, userSub)
                .orElseThrow(() -> {
                    record(userSub, AuditActions.INGEST_RETRY, "ingest_job", id, AuditOutcome.DENIED,
                            Map.of(AuditMetadata.REASON_CODE, RETRY_NOT_OWNER_OR_NOT_FOUND));
                    return new NoSuchElementException("No such job: " + id);
                });
    }

    // ---- helpers ----

    private IngestJob newJob(
            String jobId,
            String name,
            String source,
            String sizeLabel,
            String userSub,
            String sourceId,
            String revisionId) {
        IngestJob job = new IngestJob();
        job.setId(jobId);
        job.setName(name);
        job.setSource(source);
        job.setSizeLabel(sizeLabel);
        job.setOwnerSub(userSub);
        job.setSourceId(sourceId);
        job.setSourceRevisionId(revisionId);
        job.setSourceKey(IngestSource.sourceKey(sourceId, revisionId));
        job.setDemo(false);
        job.setIndexGeneration(1);
        job.setAttempt(0);
        job.setStage(JobStage.QUEUED.id());
        job.setPct(0);
        job.setChunks(0);
        job.setWhenLabel("now");
        job.setError(null);
        return job;
    }

    private IngestIdempotencyReservation reserve(
            String userSub, String requestKind, String key, String payloadSha256, String jobId) {
        if (key == null) {
            return null;
        }
        try {
            return idempotency.reserve(userSub, requestKind, key, payloadSha256, jobId);
        } catch (IdempotencyConflictException e) {
            record(userSub, AuditActions.INGEST_CREATE, "ingest_request", null, AuditOutcome.CONFLICT,
                    Map.of(
                            AuditMetadata.SOURCE_TYPE, requestKind,
                            AuditMetadata.IDEMPOTENCY_KEY_PRESENT, true,
                            AuditMetadata.REASON_CODE, IDEMPOTENCY_CONFLICT));
            throw conflict(e.getMessage());
        }
    }

    private IngestJobDto replay(IngestIdempotencyReservation reservation, String userSub) {
        IngestJob job = jobs.findById(reservation.jobId())
                .orElseThrow(() -> replayConflict(
                        userSub, reservation.jobId(), IDEMPOTENCY_ORIGINAL_UNAVAILABLE));
        // The database reservation is owner-scoped; recheck the persisted job
        // owner before returning it so a corrupt mapping cannot disclose a job.
        if (!userSub.equals(job.getOwnerSub())) {
            throw replayConflict(userSub, job.getId(), IDEMPOTENCY_OWNER_MISMATCH);
        }
        record(userSub, AuditActions.INGEST_REQUEST_REPLAY, "ingest_job", job.getId(), AuditOutcome.SUCCESS,
                Map.of(
                        AuditMetadata.SOURCE_TYPE, sourceType(job),
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, true));
        return IngestJobDto.from(job);
    }

    private void recordCreate(String userSub, IngestJob job, IngestSource source, boolean idempotencyKeyPresent) {
        String sourceType = sourceType(source);
        record(userSub, AuditActions.INGEST_CREATE, "ingest_job", job.getId(), AuditOutcome.SUCCESS,
                Map.of(
                        AuditMetadata.SOURCE_TYPE, sourceType,
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, idempotencyKeyPresent));
        record(userSub, AuditActions.INGEST_SOURCE_CREATED, "ingest_source", source.getId(), AuditOutcome.SUCCESS,
                Map.of(
                        AuditMetadata.SOURCE_TYPE, sourceType,
                        AuditMetadata.SOURCE_ID, source.getId(),
                        AuditMetadata.SOURCE_REVISION_ID, source.getSourceRevisionId(),
                        AuditMetadata.SIZE_BYTES, Math.max(0L, source.getSourceSizeBytes()),
                        AuditMetadata.RETAINED, source.hasRetainedBytes(),
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, idempotencyKeyPresent));
    }

    private String normalizeIdempotencyKey(String actor, String sourceType, String rawKey) {
        try {
            return normalizeIdempotencyKey(rawKey);
        } catch (IllegalArgumentException e) {
            recordCreateDenied(actor, sourceType, rawKey, IDEMPOTENCY_KEY_INVALID);
            throw e;
        }
    }

    private void recordCreateDenied(String actor, String sourceType, String key, String reasonCode) {
        record(actor, AuditActions.INGEST_CREATE, "ingest_request", null, AuditOutcome.DENIED,
                Map.of(
                        AuditMetadata.SOURCE_TYPE, sourceType,
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, hasIdempotencyKey(key),
                        AuditMetadata.REASON_CODE, reasonCode));
    }

    private void recordCreateFailure(String actor, String sourceType, String key, String failureCode) {
        record(actor, AuditActions.INGEST_CREATE, "ingest_request", null, AuditOutcome.FAILURE,
                Map.of(
                        AuditMetadata.SOURCE_TYPE, sourceType,
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, hasIdempotencyKey(key),
                        AuditMetadata.FAILURE_CODE, failureCode));
    }

    private ResponseStatusException retryConflict(
            String actor,
            String jobId,
            String reasonCode,
            String publicReason) {
        record(actor, AuditActions.INGEST_RETRY, "ingest_job", jobId, AuditOutcome.CONFLICT,
                Map.of(AuditMetadata.REASON_CODE, reasonCode));
        return conflict(publicReason);
    }

    private NoSuchElementException deleteDenied(String id, String actor) {
        record(actor, AuditActions.INGEST_DELETE, "ingest_job", id, AuditOutcome.DENIED,
                Map.of(AuditMetadata.REASON_CODE, DELETE_NOT_OWNER_OR_NOT_FOUND));
        return new NoSuchElementException("No such job: " + id);
    }

    private void recordDeleteFailure(String actor, String jobId, String failureCode) {
        record(actor, AuditActions.INGEST_DELETE, "ingest_job", jobId, AuditOutcome.FAILURE,
                Map.of(AuditMetadata.FAILURE_CODE, failureCode));
    }

    private ResponseStatusException replayConflict(String actor, String jobId, String reasonCode) {
        record(actor, AuditActions.INGEST_REQUEST_REPLAY, "ingest_job", jobId, AuditOutcome.CONFLICT,
                Map.of(
                        AuditMetadata.IDEMPOTENCY_KEY_PRESENT, true,
                        AuditMetadata.REASON_CODE, reasonCode));
        return conflict("Original idempotent job is unavailable");
    }

    private void record(
            String actor,
            String action,
            String targetKind,
            String targetId,
            AuditOutcome outcome,
            Map<String, ?> metadata) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(KnowledgeAuditEvent.of(
                    AuditRedaction.actor(actor),
                    AuditRedaction.action(action),
                    AuditRedaction.resourceType(targetKind),
                    AuditRedaction.identifier(targetId),
                    null,
                    null,
                    null,
                    outcome,
                    metadata));
        } catch (RuntimeException ignored) {
            // Auditing must never expose request material or change an ingestion result.
        }
    }

    private static String sourceType(IngestSource source) {
        return "url".equals(source.getSourceType()) ? "url" : "upload";
    }

    private static String sourceType(IngestJob job) {
        return SOURCE_URL.equals(job.getSource()) ? "url" : "upload";
    }

    private static boolean hasIdempotencyKey(String key) {
        return key != null && !key.isBlank();
    }

    private static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String normalized = rawKey.trim();
        if (normalized.isEmpty() || normalized.length() > IngestIdempotencyStore.MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 1 to " + IngestIdempotencyStore.MAX_KEY_LENGTH + " characters");
        }
        return normalized;
    }

    /** Hashes a length-delimited server-normalized request representation. */
    static String payloadFingerprint(String kind, byte[]... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addFingerprintField(digest, utf8(kind));
            for (byte[] field : fields) {
                addFingerprintField(digest, field);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void addFingerprintField(MessageDigest digest, byte[] field) {
        byte[] value = field == null ? new byte[0] : field;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean matchesDurableSource(IngestJob job, IngestSource source) {
        return job.getSourceId() != null
                && job.getSourceId().equals(source.getId())
                && job.getSourceRevisionId() != null
                && job.getSourceRevisionId().equals(source.getSourceRevisionId())
                && job.getSourceKey() != null
                && job.getSourceKey().equals(source.getSourceKey());
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private List<String> sortedExtensions() {
        return allowedExtensions.stream().sorted().toList();
    }

    static String deriveName(String url) {
        if (url == null || url.isBlank()) {
            return "URL import";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !path.equals("/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) {
                    return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return url.length() > 64 ? url.substring(0, 64) : url;
    }

    static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = (int) (Math.log10(bytes) / Math.log10(1024));
        i = Math.min(i, units.length - 1);
        double value = bytes / Math.pow(1024, i);
        if (i == 0) {
            return bytes + " B";
        }
        return String.format("%.1f %s", value, units[i]);
    }
}
