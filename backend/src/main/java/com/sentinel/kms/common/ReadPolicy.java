package com.sentinel.kms.common;

/** Minimum trust boundary required to read approved, active knowledge. */
public enum ReadPolicy {
    PUBLIC,
    WORKSPACE_MEMBER,
    WORKSPACE_EDITOR,
    WORKSPACE_ADMIN
}
