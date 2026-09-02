package com.sentinel.kms.ingest;

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

    public ConnectorController(IngestService service,
                               NvdConnectorService nvdConnectorService,
                               KevConnectorService kevConnectorService,
                               AttackConnectorService attackConnectorService) {
        this.service = service;
        this.nvdConnectorService = nvdConnectorService;
        this.kevConnectorService = kevConnectorService;
        this.attackConnectorService = attackConnectorService;
    }

    @GetMapping
    public List<ConnectorDto> connectors() {
        return service.listConnectors();
    }

    @PostMapping("/{id}/sync")
    public Object sync(@PathVariable String id,
                       @RequestParam(defaultValue = "7") int days,
                       @RequestParam(defaultValue = "15") int max) {
        return switch (id) {
            case "nvd" -> nvdConnectorService.sync(days, max);
            case "kev" -> kevConnectorService.sync(max);
            case "attack" -> attackConnectorService.sync(max);
            default -> throw new IllegalArgumentException("Sync not supported for connector: " + id);
        };
    }
}
