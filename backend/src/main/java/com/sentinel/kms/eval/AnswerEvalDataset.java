package com.sentinel.kms.eval;

import java.util.List;

/** Dataset envelope retained in reports for reproducibility. */
public record AnswerEvalDataset(
        String schemaVersion,
        String datasetId,
        List<AnswerEvalCase> cases,
        ExpertReview expertReview) {

    public AnswerEvalDataset {
        if (schemaVersion == null || schemaVersion.isBlank() || datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("schemaVersion and datasetId are required");
        }
        schemaVersion = schemaVersion.trim();
        datasetId = datasetId.trim();
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("answer evaluation dataset must contain at least one case");
        }
        expertReview = expertReview == null
                ? new ExpertReview(ExpertReviewStatus.PENDING, null, null, "No dataset-level expert review recorded.")
                : expertReview;
    }
}
