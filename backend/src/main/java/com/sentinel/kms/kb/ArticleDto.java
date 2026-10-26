package com.sentinel.kms.kb;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wire DTO for a knowledge-base article. The React app depends on these exact
 * keys: {@code category} (= {@link Article#getCategoryId()}) and {@code updated}
 * (= {@link Article#getUpdatedOn()} as ISO {@code yyyy-MM-dd}). {@code cvss} is
 * omitted from the JSON when null (CVE-only field).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArticleDto(
        String id,
        String title,
        String category,
        String severity,
        List<String> tags,
        String source,
        String updated,
        String author,
        String confidence,
        int reads,
        String cvss,
        String snippet,
        List<Ioc> iocs,
        List<ArticleSection> body,
        List<String> related
) {
    public static ArticleDto from(Article a) {
        return new ArticleDto(
                a.getId(),
                a.getTitle(),
                a.getCategoryId(),
                a.getSeverity(),
                a.getTags(),
                a.getSource(),
                a.getUpdatedOn() == null ? null : a.getUpdatedOn().toString(),
                a.getAuthor(),
                a.getConfidence(),
                a.getReads(),
                a.getCvss(),
                a.getSnippet(),
                a.getIocs(),
                a.getBody(),
                a.getRelated()
        );
    }
}
