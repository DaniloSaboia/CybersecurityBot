package com.sentinel.kms.ingest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
 * All operations are scoped to the authenticated user: listings show own +
 * shared demo jobs; retry/delete only touch the user's own jobs.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @GetMapping("/jobs")
    public List<IngestJobDto> jobs(@AuthenticationPrincipal Jwt jwt) {
        return service.listJobs(jwt.getSubject());
    }

    @PostMapping("/upload")
    public IngestJobDto upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return service.createUploadJob(file, jwt.getSubject());
    }

    @PostMapping("/url")
    public IngestJobDto url(@Valid @RequestBody AddUrlRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.createUrlJob(request.url(), jwt.getSubject());
    }

    @PostMapping("/jobs/{id}/retry")
    public IngestJobDto retry(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.retry(id, jwt.getSubject());
    }

    /** Delete one of the user's ingested jobs and its embeddings from the vector store. */
    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        service.deleteJob(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
