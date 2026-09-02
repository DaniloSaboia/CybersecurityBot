package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin client over the public CISA Known Exploited Vulnerabilities catalog
 * (a single JSON feed, no auth). Only the fields the KB cares about are kept.
 * Any failure is logged and degrades to an empty result.
 */
@Component
public class KevClient {

    private static final Logger log = LoggerFactory.getLogger(KevClient.class);

    private static final String KEV_URL =
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    private final RestClient restClient;

    public KevClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** One KEV entry reduced to the fields the KB cares about. */
    public record KevEntry(
            String cveId,
            String vendorProject,
            String product,
            String vulnerabilityName,
            String dateAdded,
            String dueDate,
            boolean ransomwareUse,
            String requiredAction,
            String description) {
    }

    /** Fetches up to {@code max} KEV entries (newest first by feed order). */
    public List<KevEntry> fetch(int max) {
        List<KevEntry> out = new ArrayList<>();
        try {
            JsonNode root = restClient.get().uri(KEV_URL).retrieve().body(JsonNode.class);
            if (root == null) {
                log.warn("KEV fetch returned null body");
                return out;
            }
            JsonNode vulnerabilities = root.path("vulnerabilities");
            if (!vulnerabilities.isArray()) {
                log.warn("KEV fetch returned unexpected shape (no vulnerabilities array)");
                return out;
            }
            for (JsonNode v : vulnerabilities) {
                if (out.size() >= max) {
                    break;
                }
                String cveId = v.path("cveID").asText(null);
                if (cveId == null || cveId.isBlank()) {
                    continue;
                }
                out.add(new KevEntry(
                        cveId,
                        text(v, "vendorProject"),
                        text(v, "product"),
                        text(v, "vulnerabilityName"),
                        text(v, "dateAdded"),
                        text(v, "dueDate"),
                        "Known".equalsIgnoreCase(text(v, "knownRansomwareCampaignUse")),
                        text(v, "requiredAction"),
                        text(v, "notes")));
            }
        } catch (RestClientResponseException e) {
            log.warn("KEV fetch failed: HTTP {} {}", e.getStatusCode().value(), e.getStatusText());
        } catch (Exception e) {
            log.warn("KEV fetch failed: {}", e.getMessage());
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value == null ? "" : value.trim();
    }
}
