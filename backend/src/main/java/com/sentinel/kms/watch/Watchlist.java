package com.sentinel.kms.watch;

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
import java.util.ArrayList;
import java.util.List;

/**
 * A user's watchlist: a set of case-insensitive terms (CVE ids, product
 * names, threat-actor names) plus an optional minimum severity. When a
 * connector sync materializes articles that match, an {@link KbAlert} is
 * created for the owner.
 */
@Entity
@Table(name = "watchlists")
@Getter
@Setter
@NoArgsConstructor
public class Watchlist {

    @Id
    private String id;

    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    private String label;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> terms = new ArrayList<>();

    /** Minimum article severity that fires an alert: critical..info. */
    @Column(name = "min_severity")
    private String minSeverity;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
