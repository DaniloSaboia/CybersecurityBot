package com.sentinel.kms.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A knowledge-base entry. Column names mirror the Supabase migration
 * (20260608010000_init.sql). Note the API/UI exposes {@code categoryId} as
 * "category" and {@code updatedOn} as "updated" — see the KB DTO/mapper.
 */
@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
public class Article {

    @Id
    private String id; // e.g. "TI-2291", "CVE-2024-21413"

    private String title;

    @Column(name = "category_id")
    private String categoryId;

    /** Lowercase severity id: critical/high/medium/low/info. */
    private String severity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    private String source;

    @Column(name = "updated_on")
    private LocalDate updatedOn;

    private String author;

    private String confidence;

    private int reads;

    private String cvss; // only for CVE advisories

    private String snippet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ArticleSection> body = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Ioc> iocs = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related", columnDefinition = "text[]")
    private List<String> related = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
