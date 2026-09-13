package com.sentinel.kms.common;

/** Database-backed policy facts for one published article/revision. */
public record ArticlePolicy(
        String workspaceId,
        Classification classification,
        ApprovalStatus approvalStatus,
        ReadPolicy readPolicy,
        LifecycleStatus lifecycleStatus) {
}
