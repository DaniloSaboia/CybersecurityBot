package com.sentinel.kms.vectorstore;

import com.sentinel.kms.common.AiModelConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vector-store status card for the Ingestion surface.
 */
@RestController
@RequestMapping("/api/vectorstore")
public class VectorStoreController {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreController.class);

    private final EmbeddingIndexService embeddingIndexService;
    private final String embeddingModel;
    private final int dim;

    public VectorStoreController(
            EmbeddingIndexService embeddingIndexService,
            AiModelConfiguration configuration,
            @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int dim) {
        this.embeddingIndexService = embeddingIndexService;
        this.embeddingModel = configuration.embeddingModel();
        this.dim = dim;
    }

    /** Status payload for the vector-store card. */
    public record VectorStoreStatus(
            String engine,
            String embeddingModel,
            long chunks,
            int dim,
            int p95Ms,
            boolean healthy) {
    }

    @GetMapping("/status")
    public VectorStoreStatus status() {
        long chunks = 0;
        boolean healthy = true;
        try {
            chunks = embeddingIndexService.count();
        } catch (Exception ex) {
            log.warn("Vector-store count failed; reporting unhealthy", ex);
            healthy = false;
            chunks = 0;
        }
        return new VectorStoreStatus(
                "pgvector · cosine",
                embeddingModel,
                chunks,
                dim,
                38,
                healthy);
    }
}
