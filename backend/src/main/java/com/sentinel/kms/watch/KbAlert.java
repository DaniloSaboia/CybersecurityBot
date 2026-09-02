package com.sentinel.kms.watch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One watchlist hit: a synced article matched one of the user's watchlists.
 */
@Entity
@Table(name = "kb_alerts")
@Getter
@Setter
@NoArgsConstructor
public class KbAlert {

    @Id
    private String id;

    @Column(name = "owner_sub", nullable = false)
    private String ownerSub;

    @Column(name = "watchlist_id", nullable = false)
    private String watchlistId;

    @Column(name = "article_id", nullable = false)
    private String articleId;

    @Column(name = "article_title")
    private String articleTitle;

    private String severity;

    /** Human-readable why-it-matched line, e.g. "term CVE-2024-21413". */
    private String reason;

    private boolean read;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
