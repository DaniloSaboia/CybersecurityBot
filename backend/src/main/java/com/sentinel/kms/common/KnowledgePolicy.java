package com.sentinel.kms.common;

/** Pure, fail-closed policy evaluator shared by direct, IOC, and retrieval paths. */
public final class KnowledgePolicy {

    private KnowledgePolicy() {
    }

    public static AccessDecision decide(KnowledgeAccessContext context, ArticlePolicy policy) {
        if (policy == null) {
            return AccessDecision.deny("GOVERNANCE_MISSING", null);
        }
        if (context == null || !context.authenticated()) {
            return AccessDecision.deny("AUTHENTICATION_REQUIRED", policy);
        }
        if (policy.classification() == null || policy.approvalStatus() == null
                || policy.readPolicy() == null || policy.lifecycleStatus() == null
                || policy.workspaceId() == null || policy.workspaceId().isBlank()) {
            return AccessDecision.deny("GOVERNANCE_INVALID", policy);
        }
        if (policy.approvalStatus() != ApprovalStatus.APPROVED) {
            return AccessDecision.deny("NOT_APPROVED", policy);
        }
        if (policy.lifecycleStatus() != LifecycleStatus.ACTIVE) {
            return AccessDecision.deny("NOT_ACTIVE", policy);
        }

        WorkspaceRole role = context.roleIn(policy.workspaceId());
        // Classification supplies a non-negotiable floor.  A malformed row such
        // as RESTRICTED + WORKSPACE_MEMBER must never silently lower clearance.
        return switch (policy.classification()) {
            case PUBLIC -> policy.readPolicy() == ReadPolicy.PUBLIC
                    ? AccessDecision.allow("PUBLIC_APPROVED", policy)
                    : AccessDecision.deny("POLICY_CLASSIFICATION_MISMATCH", policy);
            case INTERNAL -> policy.readPolicy() == ReadPolicy.WORKSPACE_MEMBER
                    ? role == null ? AccessDecision.deny("MEMBERSHIP_REQUIRED", policy)
                    : AccessDecision.allow("WORKSPACE_MEMBER", policy)
                    : AccessDecision.deny("POLICY_CLASSIFICATION_MISMATCH", policy);
            case CONFIDENTIAL -> policy.readPolicy() == ReadPolicy.WORKSPACE_EDITOR
                    ? role != null && role.atLeast(WorkspaceRole.EDITOR)
                    ? AccessDecision.allow("WORKSPACE_EDITOR", policy)
                    : AccessDecision.deny("ROLE_REQUIRED", policy)
                    : AccessDecision.deny("POLICY_CLASSIFICATION_MISMATCH", policy);
            case RESTRICTED -> policy.readPolicy() == ReadPolicy.WORKSPACE_ADMIN
                    ? role != null && role.atLeast(WorkspaceRole.ADMIN)
                    ? AccessDecision.allow("WORKSPACE_ADMIN", policy)
                    : AccessDecision.deny("ROLE_REQUIRED", policy)
                    : AccessDecision.deny("POLICY_CLASSIFICATION_MISMATCH", policy);
        };
    }

    public static boolean canMutateConnector(KnowledgeAccessContext context) {
        if (context == null || !context.authenticated()) {
            return false;
        }
        return context.workspaceRoles().values().stream()
                .anyMatch(role -> role.atLeast(WorkspaceRole.EDITOR));
    }
}
