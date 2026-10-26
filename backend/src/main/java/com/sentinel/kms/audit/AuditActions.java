package com.sentinel.kms.audit;

/** Stable action names; audit writers must use identifiers rather than prose. */
public final class AuditActions {

    public static final String AUDIT_READ = "AUDIT_READ";

    public static final String INGEST_CREATE = "INGEST_CREATE";
    public static final String INGEST_REQUEST_REPLAY = "INGEST_REQUEST_REPLAY";
    public static final String INGEST_RETRY = "INGEST_RETRY";
    public static final String INGEST_DELETE = "INGEST_DELETE";
    public static final String INGEST_SOURCE_CREATED = "INGEST_SOURCE_CREATED";
    public static final String INGEST_SOURCE_RETAINED = "INGEST_SOURCE_RETAINED";
    public static final String INGEST_SOURCE_REMOVED = "INGEST_SOURCE_REMOVED";
    public static final String INGEST_INDEXED = "INGEST_INDEXED";
    public static final String INGEST_PROCESS_FAILED = "INGEST_PROCESS_FAILED";

    public static final String GRAPH_ENTITY_CREATE = "GRAPH_ENTITY_CREATE";
    public static final String GRAPH_EDGE_CREATE = "GRAPH_EDGE_CREATE";
    public static final String GRAPH_ENTITY_READ = "GRAPH_ENTITY_READ";
    public static final String GRAPH_EDGE_READ = "GRAPH_EDGE_READ";

    public static final String LIFECYCLE_DRAFT_CREATED = "LIFECYCLE_DRAFT_CREATED";
    public static final String LIFECYCLE_REVIEW_SUBMITTED = "LIFECYCLE_REVIEW_SUBMITTED";
    public static final String LIFECYCLE_REVISION_APPROVED = "LIFECYCLE_REVISION_APPROVED";
    public static final String LIFECYCLE_REVISION_REJECTED = "LIFECYCLE_REVISION_REJECTED";
    public static final String LIFECYCLE_REVISION_SUPERSEDED = "LIFECYCLE_REVISION_SUPERSEDED";
    public static final String LIFECYCLE_ARTICLE_EXPIRED = "LIFECYCLE_ARTICLE_EXPIRED";
    public static final String LIFECYCLE_REVISION_ROLLED_BACK = "LIFECYCLE_REVISION_ROLLED_BACK";
    public static final String LIFECYCLE_MUTATION_DENIED = "LIFECYCLE_MUTATION_DENIED";
    public static final String LIFECYCLE_CONCURRENCY_CONFLICT = "LIFECYCLE_CONCURRENCY_CONFLICT";

    private AuditActions() {
    }
}
