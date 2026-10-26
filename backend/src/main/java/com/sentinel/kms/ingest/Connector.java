package com.sentinel.kms.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * A source connector feeding the ingestion pipeline.
 * kind ∈ {S3, API, URL}; status ∈ {connected, syncing, error}.
 */
@Entity
@Table(name = "connectors")
@Getter
@Setter
@NoArgsConstructor
public class Connector {

    @Id
    private String id;

    private String name; // "AWS S3 — sec-intel-bucket"

    private String kind;

    private String status;

    private int docs;

    @Column(name = "last_label")
    private String lastLabel; // "4m ago" | "syncing…" | "auth failed"

    private boolean auto;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
