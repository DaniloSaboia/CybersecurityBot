package com.sentinel.kms.watch;

import com.sentinel.kms.kb.Article;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WatchlistMatcherTest {

    private Watchlist watchlist(String minSeverity, String... terms) {
        Watchlist w = new Watchlist();
        w.setId("wl-1");
        w.setOwnerSub("user-1");
        w.setLabel("watch");
        w.setTerms(List.of(terms));
        w.setMinSeverity(minSeverity);
        return w;
    }

    private Article article(String id, String title, String severity, String snippet, List<String> tags) {
        Article a = new Article();
        a.setId(id);
        a.setTitle(title);
        a.setSeverity(severity);
        a.setSnippet(snippet);
        a.setTags(tags);
        return a;
    }

    @Test
    void matchesTermInArticleId() {
        Article a = article("CVE-2024-21413", "Outlook RCE", "critical", "Moniker link", List.of("CVE"));
        assertThat(WatchlistMatcher.match(watchlist(null, "cve-2024-21413"), a))
                .contains("cve-2024-21413");
    }

    @Test
    void matchesTermInTitleCaseInsensitively() {
        Article a = article("TI-1", "Akira Ransomware Cluster", "high", "", List.of());
        assertThat(WatchlistMatcher.match(watchlist(null, "AKIRA"), a)).isNotNull();
    }

    @Test
    void matchesExactTagOnly() {
        Article a = article("X-1", "Report", "high", "edge device exploitation", List.of("VPN"));
        assertThat(WatchlistMatcher.match(watchlist(null, "sonicwall"), a)).isNull();      // no substring anywhere
        assertThat(WatchlistMatcher.match(watchlist(null, "vpn"), a)).isNotNull();        // exact tag
    }

    @Test
    void severityThresholdGatesTheMatch() {
        Article low = article("CVE-1", "low sev", "low", "", List.of());
        assertThat(WatchlistMatcher.match(watchlist("high", "cve-1"), low)).isNull();
        Article crit = article("CVE-2", "crit sev", "critical", "", List.of());
        assertThat(WatchlistMatcher.match(watchlist("high", "cve-2"), crit)).isNotNull();
        assertThat(WatchlistMatcher.meetsMinSeverity("", "low")).isTrue();
    }

    @Test
    void ignoresShortTermsAndMissingText() {
        Article a = article("T-1", "T1566 Phishing", "info", null, null);
        assertThat(WatchlistMatcher.match(watchlist(null, "ab"), a)).isNull(); // < 3 chars
        assertThat(WatchlistMatcher.match(watchlist(null, "t1566"), a)).isNotNull();
        assertThat(WatchlistMatcher.match(null, a)).isNull();
        assertThat(WatchlistMatcher.match(watchlist(null, "t1566"), null)).isNull();
    }
}
