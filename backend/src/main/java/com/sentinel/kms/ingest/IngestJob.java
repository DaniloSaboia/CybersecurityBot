package com.sentinel.kms.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A document moving through the ingestion pipeline. {@code stage} holds the
 * lowercase {@link JobStage} id; {@code pct} is progress within the current stage.
 */
@Entity
@Table(name = "ingest_jobs")
@Getter
@Setter
@NoArgsConstructor
public class IngestJob {

    @Id
    private String id; // UUID string for new jobs; legacy demo IDs remain supported

    private String name; // filename

    private String source; // "Manual upload" | connector name

    @Column(name = "size_label")
    private String sizeLabel; // human-readable, e.g. "8.4 MB"

    /** Lowercase JobStage id: queued/extracting/chunking/embedding/indexed/failed. */
    private String stage;

    private double pct; // 0..100 within current stage

    private int chunks; // produced after chunking

    @Column(name = "when_label")
    private String whenLabel; // "now" | "6m ago"

    private String error; // present when stage == failed

    /** Owning user (JWT sub); a null owner never grants visibility by itself. */
    @Column(name = "owner_sub")
    private String ownerSub;

    /** The fetched URL for URL-import jobs, so retry can re-fetch. */
    @Column(name = "source_url")
    private String sourceUrl;

    /** Server-generated durable source identity; never taken from client metadata. */
    @Column(name = "source_id")
    private String sourceId;

    /** Immutable server-generated content revision used to construct sourceKey. */
    @Column(name = "source_revision_id")
    private String sourceRevisionId;

    @Column(name = "source_key")
    private String sourceKey;

    /** Fencing generation checked by the vectorstore before replacement. */
    @Column(name = "index_generation")
    private long indexGeneration;

    /** Number of durable worker claims, including a recovered expired lease. */
    private int attempt;

    @Column(name = "claim_token")
    private String claimToken;

    @Column(name = "claim_until")
    private OffsetDateTime claimUntil;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    /** Explicitly marked seed/demo records are the only rows a simulator may move. */
    @Column(name = "is_demo")
    private boolean demo;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /** Soft deletion retains a fence but source bytes are removed separately. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
