package com.sentinel.kms.ingest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Streams the MITRE ATT&CK Enterprise STIX bundle and keeps only the
 * {@code attack-pattern} objects (techniques), up to {@code max}. Streaming
 * matters here: the bundle is tens of MB, and we only need a slice of it.
 */
@Component
public class AttackClient {

    private static final Logger log = LoggerFactory.getLogger(AttackClient.class);

    private static final String ATTACK_URL =
            "https://raw.githubusercontent.com/mitre-attack/attack-stix-data"
                    + "/master/enterprise-attack/enterprise-attack.json";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public AttackClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** One ATT&CK technique reduced to the fields the KB cares about. */
    public record AttackTechnique(
            String techniqueId,
            String name,
            String description,
            List<String> platforms,
            boolean subtechnique) {
    }

    /**
     * Fetches up to {@code max} techniques. The bundle streams object by
     * object; non-attack-pattern objects are skipped without being buffered.
     */
    public List<AttackTechnique> fetch(int max) {
        List<AttackTechnique> out = new ArrayList<>();
        try {
            try (java.io.InputStream in = restClient.get().uri(ATTACK_URL).exchange(
                    (req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException(
                                    "ATT&CK fetch failed: HTTP " + res.getStatusCode().value());
                        }
                        return res.getBody();
                    })) {
                JsonFactory factory = mapper.getFactory();
                try (JsonParser parser = factory.createParser(in)) {
                    if (parser.nextToken() != JsonToken.START_OBJECT) {
                        throw new IllegalStateException("ATT&CK bundle is not a JSON object");
                    }
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String field = parser.currentName();
                        if (!"objects".equals(field)) {
                            parser.skipChildren();
                            continue;
                        }
                        if (parser.nextToken() != JsonToken.START_ARRAY) {
                            continue;
                        }
                        while (parser.nextToken() == JsonToken.START_OBJECT) {
                            JsonNode object = mapper.readTree(parser);
                            if (!"attack-pattern".equals(object.path("type").asText(""))) {
                                continue;
                            }
                            AttackTechnique technique = toTechnique(object);
                            if (technique != null) {
                                out.add(technique);
                                if (out.size() >= max) {
                                    break;
                                }
                            }
                        }
                        parser.skipChildren();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ATT&CK fetch failed: {}", e.getMessage());
        }
        return out;
    }

    private AttackTechnique toTechnique(JsonNode object) {
        String name = object.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        String externalId = null;
        for (JsonNode ref : object.path("external_references")) {
            String source = ref.path("source_name").asText("");
            if ("mitre-attack".equals(source) || "capec".equals(source)) {
                String id = ref.path("external_id").asText("");
                if (id.matches("T\\d{4}(\\.\\d{3})?")) {
                    externalId = id;
                    break;
                }
            }
        }
        String id = externalId != null ? externalId : object.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return null;
        }
        List<String> platforms = new ArrayList<>();
        for (JsonNode p : object.path("x_mitre_platforms")) {
            platforms.add(p.asText());
        }
        return new AttackTechnique(
                id,
                name,
                object.path("description").asText(""),
                platforms,
                object.path("x_mitre_is_subtechnique").asBoolean(false));
    }
}
