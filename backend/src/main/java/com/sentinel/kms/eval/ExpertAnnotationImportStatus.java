package com.sentinel.kms.eval;

/** Fail-closed state of the optional external expert-annotation sidecar. */
public enum ExpertAnnotationImportStatus {
    /** No annotation artifact was supplied. This never establishes completeness. */
    MISSING,
    /** The artifact bound structurally to the supplied dataset and saved answers. */
    VALIDATED,
    /** The artifact was malformed, unbound, or internally inconsistent. */
    INVALID
}
