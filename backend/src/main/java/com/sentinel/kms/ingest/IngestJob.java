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
    private String id; // "job-7741"

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

    /** Owning user (JWT sub); null = seeded demo job visible to everyone. */
    @Column(name = "owner_sub")
    private String ownerSub;

    /** The fetched URL for URL-import jobs, so retry can re-fetch. */
    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
