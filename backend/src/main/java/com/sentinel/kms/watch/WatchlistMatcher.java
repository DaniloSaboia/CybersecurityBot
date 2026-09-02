package com.sentinel.kms.watch;

import com.sentinel.kms.kb.Article;

import java.util.List;
import java.util.Locale;

/**
 * Pure matching logic: does an article fire a watchlist? Kept static so the
 * rules are unit-testable without a database.
 *
 * <ul>
 *   <li>A term matches when it appears (case-insensitively) as a substring of
 *       the article id, title, snippet, or exactly equals one of its tags.</li>
 *   <li>Terms shorter than 3 characters are ignored (noise control).</li>
 *   <li>Even with a term match, the article must meet the watchlist's minimum
 *       severity (critical &gt; high &gt; medium &gt; low &gt; info).</li>
 * </ul>
 */
public final class WatchlistMatcher {

    private WatchlistMatcher() {
    }

    /** Non-null when the article fires the watchlist: the human reason line. */
    public static String match(Watchlist watchlist, Article article) {
        if (watchlist == null || article == null) {
            return null;
        }
        String term = matchTerm(watchlist, article);
        if (term == null) {
            return null;
        }
        if (!meetsMinSeverity(watchlist.getMinSeverity(), article.getSeverity())) {
            return null;
        }
        return "term " + term + " · severity " + (article.getSeverity() == null ? "info" : article.getSeverity());
    }

    private static String matchTerm(Watchlist watchlist, Article article) {
        List<String> terms = watchlist.getTerms();
        if (terms == null || terms.isEmpty()) {
            return null;
        }
        String id = lower(article.getId());
        String title = lower(article.getTitle());
        String snippet = lower(article.getSnippet());
        for (String raw : terms) {
            if (raw == null) {
                continue;
            }
            String term = raw.trim().toLowerCase(Locale.ROOT);
            if (term.length() < 3) {
                continue;
            }
            if (id.contains(term) || title.contains(term) || snippet.contains(term)) {
                return term;
            }
            if (article.getTags() != null) {
                for (String tag : article.getTags()) {
                    if (tag != null && tag.toLowerCase(Locale.ROOT).equals(term)) {
                        return term;
                    }
                }
            }
        }
        return null;
    }

    /** severity(article) &gt;= minSeverity(watchlist), blank min passes everything. */
    public static boolean meetsMinSeverity(String minSeverity, String articleSeverity) {
        if (minSeverity == null || minSeverity.isBlank()) {
            return true;
        }
        return rank(articleSeverity) >= rank(minSeverity);
    }

    private static int rank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
