package com.sentinel.kms.common;

/** Published lifecycle state. Only active evidence can be returned to a reader. */
public enum LifecycleStatus {
    ACTIVE,
    SUPERSEDED,
    EXPIRED,
    ARCHIVED
}
