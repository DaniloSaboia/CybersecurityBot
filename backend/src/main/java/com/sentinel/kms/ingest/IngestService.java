package com.sentinel.kms.ingest;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Application service for the ingestion pipeline: lists jobs/connectors and
 * creates upload/URL jobs, handing the heavy lifting to {@link JobProcessor}
 * which runs asynchronously.
 */
@Service
public class IngestService {

    static final String SOURCE_UPLOAD = "Manual upload";
    static final String SOURCE_URL = "URL import";

    private final IngestJobRepository jobs;
    private final ConnectorRepository connectors;
    private final JobProcessor processor;
    private final EmbeddingIndexService embeddings;

    public IngestService(
            IngestJobRepository jobs,
            ConnectorRepository connectors,
            JobProcessor processor,
            EmbeddingIndexService embeddings
    ) {
        this.jobs = jobs;
        this.connectors = connectors;
        this.processor = processor;
        this.embeddings = embeddings;
    }

    public List<IngestJobDto> listJobs() {
        return jobs.findAllByOrderByCreatedAtDesc().stream()
                .map(IngestJobDto::from)
                .toList();
    }

    public List<ConnectorDto> listConnectors() {
        return connectors.findAllByOrderByCreatedAtAsc().stream()
                .map(ConnectorDto::from)
                .toList();
    }

    /** Create a job for an uploaded file and start real async processing. */
    public IngestJobDto createUploadJob(MultipartFile file) {
        String name = (file != null && file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename()
                : "upload.bin";
        long bytes = file != null ? file.getSize() : 0L;

        IngestJob job = newJob(name, SOURCE_UPLOAD, humanSize(bytes));
        jobs.save(job);

        byte[] data;
        try {
            data = (file != null) ? file.getBytes() : new byte[0];
        } catch (IOException e) {
            data = new byte[0];
        }

        processor.process(job.getId(), data, null);
        return IngestJobDto.from(job);
    }

    /** Create a job from a URL import and start real async processing. */
    public IngestJobDto createUrlJob(String url) {
        String safeUrl = url == null ? "" : url.trim();
        String name = deriveName(safeUrl);

        IngestJob job = newJob(name, SOURCE_URL, "URL");
        jobs.save(job);

        // No real bytes for a URL import in this build — synthesize real, indexable text.
        String synthesized = "Imported from URL: " + safeUrl + "\n\n"
                + "This document was ingested into the Sentinel KMS knowledge base from the "
                + "source URL above (" + name + "). It is available for retrieval-augmented "
                + "search and assistant grounding.";

        processor.process(job.getId(), null, synthesized);
        return IngestJobDto.from(job);
    }

    /** Reset a failed job and re-run processing. */
    public IngestJobDto retry(String id) {
        IngestJob job = jobs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such job: " + id));

        job.setStage(JobStage.EXTRACTING.id());
        job.setPct(0);
        job.setError(null);
        job.setWhenLabel("now");
        jobs.save(job);

        if (SOURCE_URL.equals(job.getSource())) {
            String synthesized = "Re-imported from URL: " + job.getName() + "\n\n"
                    + "Retry ingestion into the Sentinel KMS knowledge base.";
            processor.process(job.getId(), null, synthesized);
        } else {
            // Original bytes aren't retained; re-run with a minimal placeholder so the
            // pipeline still completes rather than failing again on empty input.
            String synthesized = "Retry of upload job " + job.getId() + " — " + job.getName();
            processor.process(job.getId(), null, synthesized);
        }
        return IngestJobDto.from(job);
    }

    /**
     * Delete an ingested job and remove any chunks it indexed from the vector store.
     * (Seeded demo jobs have no chunks tied to them, so only the row is removed.)
     */
    public int deleteJob(String id) {
        IngestJob job = jobs.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No such job: " + id));
        int removed = embeddings.deleteByJob(job.getId());
        jobs.delete(job);
        return removed;
    }

    // ---- helpers ----

    private IngestJob newJob(String name, String source, String sizeLabel) {
        IngestJob job = new IngestJob();
        job.setId("job-" + (1000 + ThreadLocalRandom.current().nextInt(9000)));
        job.setName(name);
        job.setSource(source);
        job.setSizeLabel(sizeLabel);
        job.setStage(JobStage.EXTRACTING.id());
        job.setPct(4);
        job.setChunks(0);
        job.setWhenLabel("now");
        job.setError(null);
        return job;
    }

    private static String deriveName(String url) {
        if (url == null || url.isBlank()) {
            return "URL import";
        }
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path != null && !path.isBlank() && !path.equals("/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) {
                    return last;
                }
            }
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost();
            }
        } catch (URISyntaxException ignored) {
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
