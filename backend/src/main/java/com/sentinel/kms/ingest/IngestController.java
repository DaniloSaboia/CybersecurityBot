package com.sentinel.kms.ingest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST surface for the ingestion pipeline (drop zone, URL import, retry, queue).
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @GetMapping("/jobs")
    public List<IngestJobDto> jobs() {
        return service.listJobs();
    }

    @PostMapping("/upload")
    public IngestJobDto upload(@RequestParam("file") MultipartFile file) {
        return service.createUploadJob(file);
    }

    @PostMapping("/url")
    public IngestJobDto url(@Valid @RequestBody AddUrlRequest request) {
        return service.createUrlJob(request.url());
    }

    @PostMapping("/jobs/{id}/retry")
    public IngestJobDto retry(@PathVariable String id) {
        return service.retry(id);
    }

    /** Delete an ingested job and its embeddings from the vector store. */
    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
}
