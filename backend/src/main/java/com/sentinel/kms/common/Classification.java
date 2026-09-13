package com.sentinel.kms.common;

/** Data classification is resolved from the governed database record, never vector metadata. */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED
}
