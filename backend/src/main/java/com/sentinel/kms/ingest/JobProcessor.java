package com.sentinel.kms.ingest;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real ingestion pipeline for a single job off the request thread:
 * <pre>
 *   EXTRACTING -> CHUNKING -> EMBEDDING -> INDEXED
 * </pre>
 * Stage, pct and whenLabel are persisted between stages so a polling UI sees
 * progress. Any failure flips the job to {@code failed} with an error message;
 * exceptions never escape the {@code @Async} method.
 *
 * <p>The set of job ids currently being processed for real is exposed so the
 * {@link JobSimulator} never fights this component over the same row.
 */
@Component
public class JobProcessor {

    private final IngestJobRepository jobs;
    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingIndexService index;
    private final UrlFetcher urlFetcher;
    private final MeterRegistry meters;

    private final int chunkSize;
    private final int chunkOverlap;
    private final long simulateLatencyMs;

    /** Ids of jobs this processor is actively driving (claimed by real upload/url/retry). */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public JobProcessor(
            IngestJobRepository jobs,
            TextExtractor extractor,
            Chunker chunker,
            EmbeddingIndexService index,
            UrlFetcher urlFetcher,
            MeterRegistry meters,
            @Value("${app.ingest.chunk-size:1200}") int chunkSize,
            @Value("${app.ingest.chunk-overlap:150}") int chunkOverlap,
            @Value("${app.ingest.simulate-latency-ms:1200}") long simulateLatencyMs
    ) {
        this.jobs = jobs;
        this.extractor = extractor;
        this.chunker = chunker;
        this.index = index;
        this.urlFetcher = urlFetcher;
        this.meters = meters;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.simulateLatencyMs = simulateLatencyMs;
    }

    /** True while a real upload/url/retry is processing this job id. */
    public boolean isActive(String jobId) {
        return active.contains(jobId);
    }

    /**
     * Process a job end-to-end. Exactly one of the sources is set:
     * {@code rawBytes} for uploads, {@code url} for URL imports (fetched + re-
     * SSRF-validated here), {@code fallbackText} as a last-resort placeholder.
     */
    @Async
    public void process(String jobId, byte[] rawBytes, String fallbackText, String url) {
        active.add(jobId);
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        try {
            IngestJob job = jobs.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }

            // ---- EXTRACTING ----
            advance(job, JobStage.EXTRACTING, 25, "now");
            pause();
            String text;
            if (rawBytes != null) {
                text = extractor.extract(rawBytes, job.getName());
            } else if (url != null) {
                try {
                    byte[] fetched = urlFetcher.fetch(url);
                    text = extractor.extract(fetched, job.getName());
                } catch (IllegalArgumentException e) {
                    fail(job, "URL fetch failed: " + e.getMessage());
                    return;
                }
            } else {
                text = fallbackText != null ? fallbackText : "";
            }
            if (text == null || text.isBlank()) {
                fail(job, "No extractable text in source");
                return;
            }
            // Uploaded/imported documents are attacker-controllable: neutralize
            // obvious prompt-injection payloads before chunking + embedding so
            // they never reach a RAG prompt from the stored copy.
            text = com.sentinel.kms.common.ContentSanitizer.sanitize(text);
            advance(job, JobStage.EXTRACTING, 100, "now");

            // ---- CHUNKING ----
            advance(job, JobStage.CHUNKING, 30, "now");
            pause();
            List<String> chunks = chunker.chunk(text, chunkSize, chunkOverlap);
            if (chunks.isEmpty()) {
                fail(job, "Chunking produced no content");
                return;
            }
            job.setChunks(chunks.size());
            advance(job, JobStage.CHUNKING, 100, "now");

            // ---- EMBEDDING ----
            advance(job, JobStage.EMBEDDING, 40, "now");
            pause();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", job.getSource());
            metadata.put("job_id", job.getId());
            metadata.put("name", job.getName());
            if (job.getOwnerSub() != null && !job.getOwnerSub().isBlank()) {
                metadata.put("owner_sub", job.getOwnerSub());
            }
            try {
                index.index(chunks, metadata);
            } catch (Exception e) {
                fail(job, "Embedding/index failed: " + safeMessage(e));
                return;
            }
            advance(job, JobStage.EMBEDDING, 100, "now");

            // ---- INDEXED ----
            advance(job, JobStage.INDEXED, 100, "now");
            succeeded = true;
        } catch (Exception e) {
            // Last-resort guard: never let an exception escape @Async.
            jobs.findById(jobId).ifPresent(j -> fail(j, "Processing failed: " + safeMessage(e)));
        } finally {
            meters.counter("sentinel.ingest.jobs", "outcome", succeeded ? "indexed" : "failed")
                    .increment();
            meters.timer("sentinel.ingest.job.latency").record(
                    System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            active.remove(jobId);
        }
    }

    private void advance(IngestJob job, JobStage stage, double pct, String when) {
        job.setStage(stage.id());
        job.setPct(pct);
        job.setWhenLabel(when);
        if (stage == JobStage.INDEXED || stage == JobStage.FAILED) {
            job.setError(stage == JobStage.INDEXED ? null : job.getError());
        }
        jobs.save(job);
    }

    private void fail(IngestJob job, String message) {
        job.setStage(JobStage.FAILED.id());
        job.setError(message);
        job.setWhenLabel("now");
        jobs.save(job);
    }

    private void pause() {
        if (simulateLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulateLatencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
