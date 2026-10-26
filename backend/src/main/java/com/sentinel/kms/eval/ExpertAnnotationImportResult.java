package com.sentinel.kms.eval;

import java.util.List;

/** Result of fail-closed structural validation of an annotation sidecar. */
public record ExpertAnnotationImportResult(
        ExpertAnnotationImportStatus status,
        String sourceSha256,
        ExpertAnnotationBundle bundle,
        List<String> issues,
        List<String> limitations) {

    private static final String IDENTITY_LIMITATION =
            "The importer validates declared reviewer identifiers and qualifications structurally; "
                    + "real-world identity, qualification, consent, and independence require external verification.";
    private static final String SOURCE_HASH_LIMITATION =
            "sourceSha256 is the raw sidecar-byte digest for importFile; programmatic validate exposes a canonical "
                    + "bundle digest, not a source-file-byte digest.";

    public ExpertAnnotationImportResult {
        status = status == null ? ExpertAnnotationImportStatus.INVALID : status;
        sourceSha256 = normalize(sourceSha256);
        issues = issues == null ? List.of() : List.copyOf(issues);
        limitations = limitations == null || limitations.isEmpty()
                ? List.of(IDENTITY_LIMITATION, SOURCE_HASH_LIMITATION) : List.copyOf(limitations);
    }

    public static ExpertAnnotationImportResult missing() {
        return new ExpertAnnotationImportResult(ExpertAnnotationImportStatus.MISSING, null, null,
                List.of("No external expert-annotation artifact was supplied."),
                List.of(IDENTITY_LIMITATION, SOURCE_HASH_LIMITATION));
    }

    public static ExpertAnnotationImportResult invalid(String sourceSha256, ExpertAnnotationBundle bundle,
                                                        List<String> issues) {
        return new ExpertAnnotationImportResult(ExpertAnnotationImportStatus.INVALID, sourceSha256, bundle,
                issues, List.of(IDENTITY_LIMITATION, SOURCE_HASH_LIMITATION));
    }

    public static ExpertAnnotationImportResult validated(String sourceSha256, ExpertAnnotationBundle bundle) {
        return new ExpertAnnotationImportResult(ExpertAnnotationImportStatus.VALIDATED, sourceSha256, bundle,
                List.of(), List.of(IDENTITY_LIMITATION, SOURCE_HASH_LIMITATION));
    }

    public boolean validated() {
        return status == ExpertAnnotationImportStatus.VALIDATED;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
