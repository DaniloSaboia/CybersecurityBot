package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.KnowledgeAccessService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for source connectors feeding the ingestion pipeline.
 * Sync-capable connectors: {@code nvd} (recent CVEs), {@code kev} (CISA known
 * exploited vulnerabilities), {@code attack} (MITRE ATT&CK techniques).
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final IngestService service;
    private final NvdConnectorService nvdConnectorService;
    private final KevConnectorService kevConnectorService;
    private final AttackConnectorService attackConnectorService;
    private final KnowledgeAccessService knowledgeAccessService;
    private final FeedSyncService feeds;

    public ConnectorController(IngestService service,
                               NvdConnectorService nvdConnectorService,
                               KevConnectorService kevConnectorService,
                               AttackConnectorService attackConnectorService,
                               KnowledgeAccessService knowledgeAccessService,
                               FeedSyncService feeds) {
        this.service = service;
        this.nvdConnectorService = nvdConnectorService;
        this.kevConnectorService = kevConnectorService;
        this.attackConnectorService = attackConnectorService;
        this.knowledgeAccessService = knowledgeAccessService;
        this.feeds = feeds;
    }

    @GetMapping
    public List<ConnectorDto> connectors() {
        return service.listConnectors().stream().map(c -> {
            if (!FeedSyncService.SOURCES.contains(c.id())) return c;
            var sync = feeds.status(c.id());
            return new ConnectorDto(c.id(),c.name(),c.kind(),c.status(),c.docs(),c.last(),
                    Boolean.TRUE.equals(sync.get("dailyEnabled")),sync);
        }).toList();
    }

    @PostMapping("/{id}/sync")
    public Object sync(@PathVariable String id,
                       @RequestParam(defaultValue = "7") int days,
                       @RequestParam(defaultValue = "15") int max,
                       @AuthenticationPrincipal Jwt jwt) {
        // The persisted workspace_memberships table is the sole role source.
        // In particular, connector config and JWT user metadata are never authority inputs.
        knowledgeAccessService.requireConnectorMutation(jwt.getSubject(), id);
        return switch (id) {
            case "nvd", "kev", "sans" -> feeds.enqueue(id, "manual");
            case "attack" -> attackConnectorService.sync(max);
            default -> throw new IllegalArgumentException("Sync not supported for connector: " + id);
        };
    }
}
