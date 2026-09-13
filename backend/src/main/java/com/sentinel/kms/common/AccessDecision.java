package com.sentinel.kms.common;

/** Explainable server-side access result. Callers must enforce {@link #allowed()}. */
public record AccessDecision(
        boolean allowed,
        String reasonCode,
        String workspaceId,
        String classification,
        String approvalStatus) {

    public static AccessDecision allow(String reasonCode, ArticlePolicy policy) {
        return new AccessDecision(true, reasonCode, policy.workspaceId(),
                policy.classification().name(), policy.approvalStatus().name());
    }

    public static AccessDecision deny(String reasonCode, ArticlePolicy policy) {
        return new AccessDecision(false, reasonCode,
                policy == null ? null : policy.workspaceId(),
                policy == null || policy.classification() == null ? null : policy.classification().name(),
                policy == null || policy.approvalStatus() == null ? null : policy.approvalStatus().name());
    }
}
