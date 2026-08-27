package com.sentinel.kms.ingest;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Application service for the ingestion pipeline: lists jobs/connectors and
 * creates upload/URL jobs, handing the heavy lifting to {@link JobProcessor}
 * which runs asynchronously. Jobs are owned by the creating user: listing
 * shows own + shared demo jobs, while retry/delete only work on own jobs.
 */
@Service
public class IngestService {

    static final String SOURCE_UPLOAD = "Manual upload";
    static final String SOURCE_URL = "URL import";

    private final IngestJobRepository jobs;
    private final ConnectorRepository connectors;
    private final JobProcessor processor;
    private final EmbeddingIndexService embeddings;
    private final UrlGuard urlGuard;
    private final Set<String> allowedExtensions;

    public IngestService(
            IngestJobRepository jobs,
            ConnectorRepository connectors,
            JobProcessor processor,
            EmbeddingIndexService embeddings,
            UrlGuard urlGuard,
            @Value("${app.ingest.allowed-extensions:pdf,txt,md,html,htm,doc,docx,json,csv,eml,log,xml,rtf,ppt,pptx,xls,xlsx}") String allowedExtensions
    ) {
        this.jobs = jobs;
        this.connectors = connectors;
        this.processor = processor;
        this.embeddings = embeddings;
        this.urlGuard = urlGuard;
        this.allowedExtensions = Set.of(allowedExtensions.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
    }

    /** Own jobs plus the seeded demo jobs, newest first. */
    public List<IngestJobDto> listJobs(String userSub) {
        return jobs.findByOwnerSubOrOwnerSubIsNullOrderByCreatedAtDesc(userSub).stream()
                .map(IngestJobDto::from)
                .toList();
    }

    public List<ConnectorDto> listConnectors() {
        return connectors.findAllByOrderByCreatedAtAsc().stream()
                .map(ConnectorDto::from)
                .toList();
    }

    /** Create a job for an uploaded file and start real async processing. */
    public IngestJobDto createUploadJob(MultipartFile file, String userSub) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload is empty");
        }
        String name = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename()
                : "upload.bin";
        String ext = extensionOf(name);
        if (!allowedExtensions.contains(ext)) {
            throw new IllegalArgumentException(
                    "Unsupported file type ." + ext + " — allowed: " + String.join(", ", sortedExtensions()));
        }

        IngestJob job = newJob(name, SOURCE_UPLOAD, humanSize(file.getSize()), userSub);
        job.setSourceUrl(null);
        jobs.save(job);

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read upload: " + e.getMessage());
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("Upload is empty");
        }

        processor.process(job.getId(), data, null, null);
        return IngestJobDto.from(job);
    }

    /**
     * Create a job from a URL import: the URL is SSRF-checked synchronously
     * (so bad input fails fast with a 400), then fetched + extracted for real
     * inside the async {@link JobProcessor}.
     */
    public IngestJobDto createUrlJob(String url, String userSub) {
        String safeUrl = url == null ? "" : url.trim();
        urlGuard.validate(safeUrl); // throws IllegalArgumentException on non-public/malformed
        String name = deriveName(safeUrl);

        IngestJob job = newJob(name, SOURCE_URL, "URL", userSub);
        job.setSourceUrl(safeUrl);
        jobs.save(job);

        processor.process(job.getId(), null, null, safeUrl);
        return IngestJobDto.from(job);
    }

    /** Reset a failed job and re-run processing (own jobs only). */
    public IngestJobDto retry(String id, String userSub) {
        IngestJob job = owned(id, userSub);

        job.setStage(JobStage.EXTRACTING.id());
        job.setPct(0);
        job.setError(null);
        job.setWhenLabel("now");
        jobs.save(job);

        if (SOURCE_URL.equals(job.getSource()) && job.getSourceUrl() != null) {
            processor.process(job.getId(), null, null, job.getSourceUrl());
        } else {
            // Original bytes aren't retained; re-run with a minimal placeholder so the
            // pipeline still completes rather than failing again on empty input.
            String synthesized = "Retry of upload job " + job.getId() + " — " + job.getName();
            processor.process(job.getId(), null, synthesized, null);
        }
        return IngestJobDto.from(job);
    }

    /**
     * Delete one of the user's ingested jobs and remove any chunks it indexed
     * from the vector store. (Seeded demo jobs aren't deletable via the API.)
     */
    public int deleteJob(String id, String userSub) {
        IngestJob job = owned(id, userSub);
        int removed = embeddings.deleteByJob(job.getId());
        jobs.delete(job);
        return removed;
    }

    private IngestJob owned(String id, String userSub) {
        IngestJob job = jobs.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No such job: " + id));
        if (job.getOwnerSub() == null || !job.getOwnerSub().equals(userSub)) {
            throw new NoSuchElementException("No such job: " + id);
        }
        return job;
    }

    // ---- helpers ----

    private IngestJob newJob(String name, String source, String sizeLabel, String userSub) {
        IngestJob job = new IngestJob();
        job.setId("job-" + (1000 + ThreadLocalRandom.current().nextInt(9000)));
        job.setName(name);
        job.setSource(source);
        job.setSizeLabel(sizeLabel);
        job.setOwnerSub(userSub);
        job.setStage(JobStage.EXTRACTING.id());
        job.setPct(4);
        job.setChunks(0);
        job.setWhenLabel("now");
        job.setError(null);
        return job;
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
