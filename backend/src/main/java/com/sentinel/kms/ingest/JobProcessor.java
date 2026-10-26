package com.sentinel.kms.ingest;

import com.sentinel.kms.audit.AuditActions;
import com.sentinel.kms.audit.AuditMetadata;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real ingestion pipeline for a durable leased claim:
 * <pre>
 *   EXTRACTING -> CHUNKING -> EMBEDDING -> INDEXED
 * </pre>
 * Stage, pct and whenLabel are persisted between stages so a polling UI sees
 * progress. Any failure flips the job to {@code failed} with an error message;
 * exceptions never escape the scheduled worker boundary.
 *
 * <p>No request-memory bytes, in-memory lock, placeholder retry text, or demo
 * simulator state is used here.  Every stage write is token/generation fenced
 * by {@link DurableIngestQueue}; a process restart can safely recover an
 * expired lease from persisted source material.</p>
 */
@Component
public class JobProcessor {

    private final IngestJobRepository jobs;
    private final IngestSourceRepository sources;
    private final DurableIngestQueue queue;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingIndexService index;
    private final UrlFetcher urlFetcher;
    private final MeterRegistry meters;
    private final KnowledgeAuditService audit;

    private final int chunkSize;
    private final int chunkOverlap;
    private final long sourceMaxBytes;

    @Autowired
    public JobProcessor(
            IngestJobRepository jobs,
            IngestSourceRepository sources,
            DurableIngestQueue queue,
            TextExtractor extractor,
            Chunker chunker,
            EmbeddingIndexService index,
            UrlFetcher urlFetcher,
            MeterRegistry meters,
            @Value("${app.ingest.chunk-size:1200}") int chunkSize,
            @Value("${app.ingest.chunk-overlap:150}") int chunkOverlap,
            @Value("${app.ingest.source-max-bytes:20971520}") long sourceMaxBytes,
            KnowledgeAuditService audit
    ) {
        this.jobs = jobs;
        this.sources = sources;
        this.queue = queue;
        this.extractor = extractor;
        this.chunker = chunker;
        this.index = index;
        this.urlFetcher = urlFetcher;
        this.meters = meters;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.sourceMaxBytes = Math.min(sourceMaxBytes, IngestSource.MAX_PERSISTED_BYTES);
        this.audit = audit;
    }

    /** Compatibility constructor retained for focused worker tests. */
    public JobProcessor(
            IngestJobRepository jobs,
            IngestSourceRepository sources,
            DurableIngestQueue queue,
            TextExtractor extractor,
            Chunker chunker,
            EmbeddingIndexService index,
            UrlFetcher urlFetcher,
            MeterRegistry meters,
            int chunkSize,
            int chunkOverlap,
            long sourceMaxBytes
    ) {
        this(jobs, sources, queue, extractor, chunker, index, urlFetcher, meters,
                chunkSize, chunkOverlap, sourceMaxBytes, null);
    }

    /**
     * Process one database-issued claim end-to-end.  The source is always read
     * from Postgres; URL bytes are stored before extraction on their first
     * successful fetch so future retries are byte-for-byte stable.
     */
    public void process(DurableJobClaim claim) {
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        IngestJob job = null;
        IngestSource source = null;
        try {
            job = jobs.findById(claim.jobId()).orElse(null);
            if (job == null) {
                return;
            }
            source = sources.findByJobId(job.getId()).orElse(null);
            if (source == null || !matchesDurableSource(job, source)) {
                fail(claim, job, source, "DURABLE_SOURCE_UNAVAILABLE",
                        "Durable source is unavailable; upload or import again");
                return;
            }

            // ---- EXTRACTING ----
            if (!advance(claim, job, JobStage.EXTRACTING, 25)) {
                return;
            }
            byte[] rawBytes = source.getSourceBytes();
            if (!source.hasRetainedBytes() && "url".equals(source.getSourceType())) {
                try {
                    byte[] fetched = urlFetcher.fetch(source.getSourceUrl());
                    if (fetched.length > sourceMaxBytes) {
                        fail(claim, job, source, "SOURCE_SIZE_LIMIT",
                                "Fetched source exceeds durable storage limit");
                        return;
                    }
                    if (!queue.retainFetchedSource(claim, source.getId(), fetched, IngestSource.sha256(fetched))) {
                        return; // deleted, retried, or recovered while the fetch was in flight
                    }
                    source.retain(fetched);
                    rawBytes = fetched;
                    recordSourceRetained(job, source, claim);
                } catch (RuntimeException e) {
                    fail(claim, job, source, "URL_FETCH_FAILED", "URL fetch failed");
                    return;
                }
            }
            if (rawBytes == null || rawBytes.length == 0) {
                fail(claim, job, source, "DURABLE_SOURCE_UNAVAILABLE",
                        "Durable source is unavailable; upload or import again");
                return;
            }
            if (!queue.heartbeat(claim)) {
                return;
            }
            String text = extractor.extract(rawBytes, job.getName());
            if (text == null || text.isBlank()) {
                fail(claim, job, source, "NO_EXTRACTABLE_TEXT", "No extractable text in source");
                return;
            }
            // Uploaded/imported documents are attacker-controllable: neutralize
            // obvious prompt-injection payloads before chunking + embedding so
            // they never reach a RAG prompt from the stored copy.
            text = com.sentinel.kms.common.ContentSanitizer.sanitize(text);
            if (!advance(claim, job, JobStage.EXTRACTING, 100)) {
                return;
            }

            // ---- CHUNKING ----
            if (!advance(claim, job, JobStage.CHUNKING, 30)) {
                return;
            }
            List<String> chunks = chunker.chunk(text, chunkSize, chunkOverlap);
            if (chunks.isEmpty()) {
                fail(claim, job, source, "NO_CHUNKS", "Chunking produced no content");
                return;
            }
            job.setChunks(chunks.size());
            if (!advance(claim, job, JobStage.CHUNKING, 100)) {
                return;
            }

            // ---- EMBEDDING ----
            if (!advance(claim, job, JobStage.EMBEDDING, 40)) {
                return;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", job.getSource());
            metadata.put("name", job.getName());
            metadata.put("source_id", source.getId());
            metadata.put("source_revision_id", source.getSourceRevisionId());
            metadata.put("source_key", source.getSourceKey());
            if (job.getOwnerSub() != null && !job.getOwnerSub().isBlank()) {
                metadata.put("owner_sub", job.getOwnerSub());
            }
            try {
                if (!queue.heartbeat(claim)) {
                    return;
                }
                // Vellum's seam takes the final database row lock and verifies
                // generation, exact claim token, unexpired lease, and absence
                // of a delete/cancel tombstone before replacing this source.
                index.replaceForClaim(job.getId(), claim.generation(), claim.token(), chunks, metadata);
            } catch (Exception e) {
                fail(claim, job, source, "INDEX_REPLACEMENT_FAILED", "Embedding/index failed");
                return;
            }
            if (!advance(claim, job, JobStage.EMBEDDING, 100)) {
                return;
            }

            // ---- INDEXED ----
            succeeded = queue.complete(claim, chunks.size());
            if (succeeded) {
                recordIndexed(job, source, claim, chunks.size());
            }
        } catch (Exception e) {
            // Last-resort guard: a stale claim cannot mutate a newer generation.
            fail(claim, job, source, "PROCESSING_FAILED", "Processing failed");
        } finally {
            meters.counter("sentinel.ingest.jobs", "outcome", succeeded ? "indexed" : "not_indexed")
                    .increment();
            meters.timer("sentinel.ingest.job.latency").record(
                    System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private boolean advance(DurableJobClaim claim, IngestJob job, JobStage stage, double pct) {
        job.setStage(stage.id());
        job.setPct(pct);
        job.setWhenLabel("now");
        return queue.progress(claim, stage, pct, job.getChunks());
    }

    private void fail(
            DurableJobClaim claim,
            IngestJob job,
            IngestSource source,
            String failureCode,
            String message) {
        if (queue.fail(claim, message)) {
            record(job, AuditActions.INGEST_PROCESS_FAILED, "ingest_job", claim.jobId(), AuditOutcome.FAILURE,
                    failureMetadata(job, source, claim, failureCode));
        }
    }

    private static boolean matchesDurableSource(IngestJob job, IngestSource source) {
        return job.getSourceId() != null
                && job.getSourceId().equals(source.getId())
                && job.getSourceRevisionId() != null
                && job.getSourceRevisionId().equals(source.getSourceRevisionId())
                && job.getSourceKey() != null
                && job.getSourceKey().equals(source.getSourceKey());
    }

    private void recordSourceRetained(IngestJob job, IngestSource source, DurableJobClaim claim) {
        Map<String, Object> metadata = provenance(job, source, claim);
        metadata.put(AuditMetadata.SIZE_BYTES, Math.max(0L, source.getSourceSizeBytes()));
        metadata.put(AuditMetadata.RETAINED, true);
        record(job, AuditActions.INGEST_SOURCE_RETAINED, "ingest_source", source.getId(), AuditOutcome.SUCCESS,
                metadata);
    }

    private void recordIndexed(IngestJob job, IngestSource source, DurableJobClaim claim, int chunks) {
        Map<String, Object> metadata = provenance(job, source, claim);
        metadata.put(AuditMetadata.CHUNK_COUNT, (long) Math.max(0, chunks));
        record(job, AuditActions.INGEST_INDEXED, "ingest_job", job.getId(), AuditOutcome.SUCCESS, metadata);
    }

    private static Map<String, Object> failureMetadata(
            IngestJob job,
            IngestSource source,
            DurableJobClaim claim,
            String failureCode) {
        Map<String, Object> metadata = provenance(job, source, claim);
        metadata.put(AuditMetadata.FAILURE_CODE, failureCode);
        return metadata;
    }

    private static Map<String, Object> provenance(IngestJob job, IngestSource source, DurableJobClaim claim) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AuditMetadata.SOURCE_TYPE, sourceType(source, job));
        metadata.put(AuditMetadata.GENERATION, Math.max(0L, claim.generation()));
        if (job != null) {
            metadata.put(AuditMetadata.ATTEMPT, (long) Math.max(0, job.getAttempt()));
        }
        if (source != null) {
            if (source.getId() != null) {
                metadata.put(AuditMetadata.SOURCE_ID, source.getId());
            }
            if (source.getSourceRevisionId() != null) {
                metadata.put(AuditMetadata.SOURCE_REVISION_ID, source.getSourceRevisionId());
            }
        }
        return metadata;
    }

    private void record(
            IngestJob job,
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
                    job == null ? null : job.getOwnerSub(),
                    action,
                    targetKind,
                    targetId,
                    null,
                    null,
                    null,
                    outcome,
                    metadata));
        } catch (RuntimeException ignored) {
            // Audit persistence failures cannot disclose source material or alter claim handling.
        }
    }

    private static String sourceType(IngestSource source, IngestJob job) {
        if (source != null && "url".equals(source.getSourceType())) {
            return "url";
        }
        return job != null && IngestService.SOURCE_URL.equals(job.getSource()) ? "url" : "upload";
    }
}
