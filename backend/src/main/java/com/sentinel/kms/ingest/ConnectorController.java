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
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final IngestService service;
    private final NvdConnectorService nvdConnectorService;

    public ConnectorController(IngestService service, NvdConnectorService nvdConnectorService) {
        this.service = service;
        this.nvdConnectorService = nvdConnectorService;
    }

    @GetMapping
    public List<ConnectorDto> connectors() {
        return service.listConnectors();
    }

    @PostMapping("/{id}/sync")
    public NvdConnectorService.SyncResult sync(@PathVariable String id,
                                               @RequestParam(defaultValue = "7") int days,
                                               @RequestParam(defaultValue = "15") int max) {
        if ("nvd".equals(id)) {
            return nvdConnectorService.sync(days, max);
        }
        throw new IllegalArgumentException("Sync not supported for connector: " + id);
    }
}
