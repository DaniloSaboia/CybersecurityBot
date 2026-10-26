package com.sentinel.kms.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin client over the public NVD CVE 2.0 API. Fetches recently published CVEs
 * and maps them to the lightweight {@link Cve} record consumed by
 * {@link NvdConnectorService}.
 *
 * <p>NVD heavily throttles anonymous clients (~5 requests / 30 s); set
 * {@code NVD_API_KEY} to use the authenticated allowance. Any failure is
 * reported clearly in the logs and degrades to an empty result.
 */
@Component
public class NvdClient {

    private static final Logger log = LoggerFactory.getLogger(NvdClient.class);

    private static final String NVD_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    private static final DateTimeFormatter NVD_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final RestClient restClient;
    private final String apiKey;

    public NvdClient(RestClient.Builder builder,
                     @Value("${app.nvd.api-key:}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /**
     * A single CVE reduced to the fields the KB cares about.
     */
    public record Cve(String id, String description, Double baseScore, String published) {
    }

    /**
     * Fetch CVEs published in the last {@code days} days, capped to {@code max}.
     * Returns an empty list on any error (with the reason logged).
     */
    public List<Cve> fetchRecent(int days, int max) {
        List<Cve> out = new ArrayList<>();
        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            // Naive UTC timestamps (no offset suffix): NVD treats them as UTC, and a
            // literal "+" in the value would be read as a space server-side.
            URI uri = UriComponentsBuilder.fromHttpUrl(NVD_URL)
                    .queryParam("pubStartDate", now.minusDays(days).format(NVD_FMT))
                    .queryParam("pubEndDate", now.format(NVD_FMT))
                    .queryParam("resultsPerPage", Math.min(max, 100))
                    .build(true)
                    .toUri();

            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(uri);
            if (!apiKey.isEmpty()) {
                spec = spec.header("apiKey", apiKey);
            }
            JsonNode root = spec.retrieve().body(JsonNode.class);

            if (root == null) {
                log.warn("NVD fetch returned null body for {}", NVD_URL);
                return out;
            }

            JsonNode vulnerabilities = root.path("vulnerabilities");
            if (!vulnerabilities.isArray()) {
                log.warn("NVD fetch returned unexpected shape (no vulnerabilities array)");
                return out;
            }

            for (JsonNode wrapper : vulnerabilities) {
                if (out.size() >= max) {
                    break;
                }
                JsonNode cve = wrapper.path("cve");
                if (cve.isMissingNode()) {
                    continue;
                }
                String id = cve.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                String published = cve.path("published").asText(null);
                String description = pickDescription(cve.path("descriptions"));
                Double baseScore = pickBaseScore(cve.path("metrics"));
                out.add(new Cve(id, description, baseScore, published));
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 429) {
                log.warn("NVD fetch throttled (HTTP {}){} — set NVD_API_KEY for the "
                                + "authenticated rate limit, or retry later",
                        e.getStatusCode().value(), apiKey.isEmpty() ? " without an API key" : "");
            } else {
                log.warn("NVD fetch failed: HTTP {} {}", e.getStatusCode().value(), e.getStatusText());
            }
            return new ArrayList<>();
        } catch (Exception e) {
            log.warn("NVD fetch failed: {}", e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }

    /** Prefer the English description, else the first available value. */
    private String pickDescription(JsonNode descriptions) {
        if (descriptions == null || !descriptions.isArray() || descriptions.isEmpty()) {
            return "";
        }
        String first = null;
        for (JsonNode d : descriptions) {
            String value = d.path("value").asText("");
            if (first == null && !value.isEmpty()) {
                first = value;
            }
            if ("en".equalsIgnoreCase(d.path("lang").asText(""))) {
                return value;
            }
        }
        return first == null ? "" : first;
    }

    /** baseScore from cvssMetricV31, else V30, else V2; null if absent. */
    private Double pickBaseScore(JsonNode metrics) {
        if (metrics == null || metrics.isMissingNode()) {
            return null;
        }
        for (String key : new String[]{"cvssMetricV31", "cvssMetricV30", "cvssMetricV2"}) {
            JsonNode arr = metrics.path(key);
            if (arr.isArray() && !arr.isEmpty()) {
                JsonNode score = arr.get(0).path("cvssData").path("baseScore");
                if (score.isNumber()) {
                    return score.asDouble();
                }
            }
        }
        return null;
    }
}
