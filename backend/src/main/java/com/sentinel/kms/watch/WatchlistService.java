package com.sentinel.kms.watch;

import com.sentinel.kms.kb.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Watchlist CRUD (owner-scoped) plus the alert pipeline: connector syncs push
 * freshly materialized articles through {@link #matchAndAlert(List)}, which
 * fans an alert out to every user whose watchlist matches.
 */
@Service
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);

    /** How far back an owner's alert feed reaches. */
    private static final int ALERT_FEED_LIMIT = 100;

    private final WatchlistRepository watchlists;
    private final KbAlertRepository alerts;

    public WatchlistService(WatchlistRepository watchlists, KbAlertRepository alerts) {
        this.watchlists = watchlists;
        this.alerts = alerts;
    }

    // ---- watchlist CRUD ----

    public record WatchlistDto(
            String id, String label, List<String> terms, String minSeverity,
            int unreadAlerts, String createdAt) {
    }

    public record CreateWatchlistRequest(String label, List<String> terms, String minSeverity) {
    }

    public List<WatchlistDto> list(String ownerSub) {
        return watchlists.findByOwnerSubOrderByCreatedAtDesc(ownerSub).stream()
                .map(w -> toDto(w, (int) alerts.countByOwnerSubAndReadFalse(ownerSub)))
                .toList();
    }

    public WatchlistDto create(String ownerSub, CreateWatchlistRequest request) {
        if (request.label() == null || request.label().isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        if (request.terms() == null || request.terms().isEmpty()) {
            throw new IllegalArgumentException("at least one watch term is required");
        }
        Watchlist watchlist = new Watchlist();
        watchlist.setId("wl-" + UUID.randomUUID().toString().substring(0, 8));
        watchlist.setOwnerSub(ownerSub);
        watchlist.setLabel(request.label().trim());
        watchlist.setTerms(request.terms().stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .map(t -> t.toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
        watchlist.setMinSeverity(blankToNull(request.minSeverity()));
        return toDto(watchlists.save(watchlist), 0);
    }

    public void delete(String ownerSub, String id) {
        watchlists.findByIdAndOwnerSub(id, ownerSub).ifPresent(watchlists::delete);
    }

    // ---- alert feed ----

    public List<KbAlert> alerts(String ownerSub, boolean unreadOnly) {
        Pageable limit = PageRequest.of(0, ALERT_FEED_LIMIT);
        return unreadOnly
                ? alerts.findByOwnerSubAndReadFalseOrderByCreatedAtDesc(ownerSub, limit)
                : alerts.findByOwnerSubOrderByCreatedAtDesc(ownerSub, limit);
    }

    public void markRead(String ownerSub, String alertId) {
        alerts.findByIdAndOwnerSub(alertId, ownerSub).ifPresent(alert -> {
            alert.setRead(true);
            alerts.save(alert);
        });
    }

    public int markAllRead(String ownerSub) {
        int n = 0;
        for (KbAlert alert : alerts.findByOwnerSubAndReadFalseOrderByCreatedAtDesc(
                ownerSub, PageRequest.of(0, 1000))) {
            alert.setRead(true);
            alerts.save(alert);
            n++;
        }
        return n;
    }

    // ---- matching pipeline (called by connector syncs) ----

    /**
     * Pushes newly created articles past every watchlist and records one
     * alert per (matching watchlist, article). Deduplicated so repeated syncs
     * never spam the same hit.
     */
    public void matchAndAlert(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }
        List<Watchlist> all = watchlists.findAll();
        if (all.isEmpty()) {
            return;
        }
        for (Watchlist watchlist : all) {
            for (Article article : articles) {
                String reason = WatchlistMatcher.match(watchlist, article);
                if (reason == null) {
                    continue;
                }
                if (alerts.findByWatchlistIdAndArticleId(watchlist.getId(), article.getId()).isPresent()) {
                    continue;
                }
                KbAlert alert = new KbAlert();
                alert.setId("al-" + UUID.randomUUID().toString().substring(0, 10));
                alert.setOwnerSub(watchlist.getOwnerSub());
                alert.setWatchlistId(watchlist.getId());
                alert.setArticleId(article.getId());
                alert.setArticleTitle(article.getTitle());
                alert.setSeverity(article.getSeverity());
                alert.setReason(reason);
                alerts.save(alert);
                log.info("Watchlist '{}' fired for article {} ({})",
                        watchlist.getLabel(), article.getId(), reason);
            }
        }
    }

    private static WatchlistDto toDto(Watchlist w, int unread) {
        return new WatchlistDto(w.getId(), w.getLabel(), w.getTerms(), w.getMinSeverity(),
                unread, w.getCreatedAt() == null ? null : w.getCreatedAt().toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
