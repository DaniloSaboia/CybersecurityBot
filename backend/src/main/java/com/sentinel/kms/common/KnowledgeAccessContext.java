package com.sentinel.kms.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request identity plus memberships resolved by the server from the database.
 * It deliberately contains no JWT role or user-metadata claims.
 */
public record KnowledgeAccessContext(String subject, Map<String, WorkspaceRole> workspaceRoles) {

    public KnowledgeAccessContext {
        subject = subject == null ? "" : subject.trim();
        workspaceRoles = Map.copyOf(workspaceRoles == null ? Map.of() : new LinkedHashMap<>(workspaceRoles));
    }

    /** Package-visible test factory; production contexts come from KnowledgeAccessService. */
    static KnowledgeAccessContext forTest(String subject, Map<String, WorkspaceRole> workspaceRoles) {
        return new KnowledgeAccessContext(subject, workspaceRoles);
    }

    public boolean authenticated() {
        return !subject.isBlank();
    }

    public WorkspaceRole roleIn(String workspaceId) {
        return workspaceId == null ? null : workspaceRoles.get(workspaceId);
    }
}
