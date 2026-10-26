package com.sentinel.kms.kb;

import java.util.List;

/**
 * Response body for {@code GET /api/kb/articles}: the filtered/sorted article
 * list plus a total count.
 */
public record ArticleListResponse(
        List<ArticleDto> items,
        long total
) {
}
