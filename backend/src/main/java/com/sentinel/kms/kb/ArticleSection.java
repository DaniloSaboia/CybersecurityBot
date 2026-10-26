package com.sentinel.kms.kb;

/**
 * One ordered section of an article body: heading {@code h} + paragraph {@code p}.
 * Stored inside {@link Article#getBody()} as jsonb (e.g. [{"h":...,"p":...}]).
 */
public record ArticleSection(String h, String p) {
}
