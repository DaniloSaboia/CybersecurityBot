package com.sentinel.kms.watch;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Watchlists + alert feed API.
 *
 * <ul>
 *   <li>GET    /api/watchlists           — own watchlists (with unread counts)</li>
 *   <li>POST   /api/watchlists           — create (label, terms[], minSeverity)</li>
 *   <li>DELETE /api/watchlists/{id}      — delete (cascades its alerts)</li>
 *   <li>GET    /api/alerts?unread=true   — own alert feed (newest first)</li>
 *   <li>POST   /api/alerts/{id}/read     — mark one alert read</li>
 *   <li>POST   /api/alerts/read-all      — mark everything read</li>
 * </ul>
 */
@RestController
public class WatchlistController {

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping("/api/watchlists")
    public List<WatchlistService.WatchlistDto> watchlists(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @PostMapping("/api/watchlists")
    public WatchlistService.WatchlistDto create(
            @Valid @RequestBody WatchlistService.CreateWatchlistRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(jwt.getSubject(), request);
    }

    @DeleteMapping("/api/watchlists/{id}")
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject(), id);
    }

    @GetMapping("/api/alerts")
    public List<KbAlert> alerts(
            @RequestParam(name = "unread", defaultValue = "false") boolean unread,
            @AuthenticationPrincipal Jwt jwt) {
        return service.alerts(jwt.getSubject(), unread);
    }

    @PostMapping("/api/alerts/{id}/read")
    public void markRead(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        service.markRead(jwt.getSubject(), id);
    }

    @PostMapping("/api/alerts/read-all")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("marked", service.markAllRead(jwt.getSubject()));
    }
}
