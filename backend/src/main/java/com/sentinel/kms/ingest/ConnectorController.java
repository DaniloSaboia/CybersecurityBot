package com.sentinel.kms.ingest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for source connectors feeding the ingestion pipeline.
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final IngestService service;

    public ConnectorController(IngestService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConnectorDto> connectors() {
        return service.listConnectors();
    }
}
