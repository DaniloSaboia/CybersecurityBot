package com.sentinel.kms.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire representation of an {@link IngestJob}. Note the renamed keys the React
 * app depends on: {@code size} (= sizeLabel) and {@code when} (= whenLabel).
 * {@code error} is omitted unless the job failed.
 */
public record IngestJobDto(
        String id,
        String name,
        String source,
        String size,
        String stage,
        double pct,
        int chunks,
        String when,
        @JsonInclude(JsonInclude.Include.NON_NULL) String error
) {
    public static IngestJobDto from(IngestJob j) {
        return new IngestJobDto(
                j.getId(),
                j.getName(),
                j.getSource(),
                j.getSizeLabel(),
                j.getStage(),
                j.getPct(),
                j.getChunks(),
                j.getWhenLabel(),
                j.getError()
        );
    }
}
