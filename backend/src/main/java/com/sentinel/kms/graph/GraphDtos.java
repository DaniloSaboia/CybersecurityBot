package com.sentinel.kms.graph;

import com.sentinel.kms.common.EvidenceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** Each graph row is an assertion supported by one exact article passage. */
public final class GraphDtos {
    private GraphDtos() {}
    public enum StatementKind { sourced, inferred }
    public record ProvenanceInput(
            @NotNull @Valid EvidenceReference evidence,
            @NotNull StatementKind statementKind,
            @Size(max=200) String inferenceMethod,
            @Size(max=100) String inferenceVersion,
            @NotNull Instant observedAt) {}
    public record EntityInput(
            @NotBlank @Pattern(regexp="[a-z][a-z0-9_]{0,63}") String entityType,
            @NotBlank @Size(max=500) String label,
            @NotNull @Valid ProvenanceInput provenance) {}
    public record EdgeInput(
            @NotBlank @Size(max=200) String fromEntityId,
            @NotBlank @Size(max=200) String toEntityId,
            @NotBlank @Pattern(regexp="[a-z][a-z0-9_]{0,63}") String relationType,
            @NotNull @Valid ProvenanceInput provenance) {}
    public record Provenance(EvidenceReference evidence, String workspaceId, String classification,
            String evidenceSha256, StatementKind statementKind, String inferenceMethod,
            String inferenceVersion, Instant observedAt, Instant recordedAt, int recordVersion,
            String createdBySub) {}
    public record Entity(String id, String entityType, String label, Provenance provenance) {}
    public record Edge(String id, String fromEntityId, String toEntityId, String relationType, Provenance provenance) {}
}
