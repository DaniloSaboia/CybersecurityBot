package com.sentinel.kms.kb;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Additive wire types for the governed article lifecycle.  Workspace,
 * classification, approval identity, revision IDs, evidence IDs, read policy,
 * and audit identity are deliberately absent from mutation inputs: the server
 * derives them from the locked governance row and authenticated subject.
 */
public final class KnowledgeLifecycleDtos {

    private KnowledgeLifecycleDtos() {
    }

    public record ArticleSnapshotInput(
            String title,
            String categoryId,
            String severity,
            List<String> tags,
            String source,
            LocalDate updatedOn,
            String author,
            String confidence,
            Integer reads,
            String cvss,
            String snippet,
            List<ArticleSection> body,
            List<Ioc> iocs,
            List<String> related) {
    }

    /** Immutable evidence input. IDs, hashes, ordinal, and canonical binding are server generated. */
    public record EvidenceSnapshotInput(
            String sourceUri,
            String sourceLabel,
            String sourceVersion,
            String content) {
    }

    public record CreateRevisionRequest(
            long expectedStateVersion,
            ArticleSnapshotInput snapshot,
            List<EvidenceSnapshotInput> evidence,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt) {
    }

    public record TransitionRequest(
            long expectedStateVersion,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt,
            String reasonCode) {
    }

    /** A supersession publishes the nominated pending revision atomically. */
    public record SupersedeRequest(
            long expectedStateVersion,
            String successorRevisionId,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt,
            String reasonCode) {
    }

    /** Rollback creates a new immutable revision copied from the historic target. */
    public record RollbackRequest(
            long expectedStateVersion,
            String targetRevisionId,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt,
            String reasonCode) {
    }

    public record ReviewScheduleRequest(long expectedStateVersion, OffsetDateTime reviewDueAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RevisionResponse(
            String id,
            String articleId,
            int revisionNumber,
            String approvalStatus,
            String lifecycleStatus,
            long stateVersion,
            String supersedesRevisionId,
            String rollbackOfRevisionId,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt,
            List<EvidenceResponse> evidence) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceResponse(
            String id,
            int ordinal,
            String sourceUri,
            String sourceLabel,
            String sourceVersion,
            int characterLength) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LifecycleResponse(
            String articleId,
            String workspaceId,
            String classification,
            String readPolicy,
            String approvalStatus,
            String lifecycleStatus,
            String currentRevisionId,
            long stateVersion,
            OffsetDateTime reviewDueAt,
            OffsetDateTime expiresAt,
            RevisionResponse revision) {
    }
}
