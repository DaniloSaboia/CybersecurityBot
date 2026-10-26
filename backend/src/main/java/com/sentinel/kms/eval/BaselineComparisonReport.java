package com.sentinel.kms.eval;

import java.time.Instant;
import java.util.List;

/** Versioned report envelope for an apples-to-apples baseline comparison. */
public record BaselineComparisonReport(
        String schemaVersion,
        String datasetId,
        String datasetSchemaVersion,
        Instant generatedAt,
        EvaluationExecutionMode executionMode,
        ScientificCompleteness scientificCompleteness,
        List<BaselineResult> baselines,
        ExpertAnnotationImportResult expertAnnotationImport,
        List<String> limitations) {

    public BaselineComparisonReport {
        baselines = baselines == null ? List.of() : List.copyOf(baselines);
        scientificCompleteness = scientificCompleteness == null
                ? new ScientificCompleteness(false, List.of("Scientific completeness was not assessed."))
                : scientificCompleteness;
        expertAnnotationImport = expertAnnotationImport == null
                ? ExpertAnnotationImportResult.missing() : expertAnnotationImport;
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    /** Backward-compatible report constructor before external sidecar imports existed. */
    public BaselineComparisonReport(String schemaVersion,
                                    String datasetId,
                                    String datasetSchemaVersion,
                                    Instant generatedAt,
                                    EvaluationExecutionMode executionMode,
                                    ScientificCompleteness scientificCompleteness,
                                    List<BaselineResult> baselines,
                                    List<String> limitations) {
        this(schemaVersion, datasetId, datasetSchemaVersion, generatedAt, executionMode, scientificCompleteness,
                baselines, ExpertAnnotationImportResult.missing(), limitations);
    }
}
