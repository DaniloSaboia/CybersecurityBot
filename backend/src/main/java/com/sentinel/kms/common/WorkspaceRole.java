package com.sentinel.kms.common;

/** Trusted, server-stored role of a subject in a workspace. */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    EDITOR,
    VIEWER;

    public boolean atLeast(WorkspaceRole required) {
        return rank(this) >= rank(required);
    }

    private static int rank(WorkspaceRole role) {
        return switch (role) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case EDITOR -> 2;
            case VIEWER -> 1;
        };
    }
}
