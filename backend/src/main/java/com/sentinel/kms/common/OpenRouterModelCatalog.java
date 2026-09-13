package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small cached view of OpenRouter's public model catalog. Free endpoints change
 * frequently, so the Settings UI must not rely on a release-time hard-coded list.
 */
@Service
public class OpenRouterModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterModelCatalog.class);
    private static final Duration SUCCESS_TTL = Duration.ofMinutes(10);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private static final List<SettingsController.ModelOption> FALLBACK = List.of(
            new SettingsController.ModelOption(
                    "openrouter/free",
                    "Free Models Router",
                    "OpenRouter",
                    "Automatically selects a currently available free model for each request.",
                    200_000,
                    true,
                    "RECOMMENDED"));

    private final RestClient restClient;
    private final String modelsUrl;
    private volatile Snapshot snapshot = new Snapshot(FALLBACK, Instant.EPOCH);
    private volatile Snapshot embeddingSnapshot = new Snapshot(List.of(), Instant.EPOCH);

    public OpenRouterModelCatalog(
            @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.modelsUrl = stripTrailingSlash(baseUrl) + "/models";
    }

    public List<SettingsController.ModelOption> availableModels() {
        Snapshot current = snapshot;
        if (current.validUntil().isAfter(Instant.now())) {
            return current.models();
        }
        synchronized (this) {
            current = snapshot;
            if (current.validUntil().isAfter(Instant.now())) {
                return current.models();
            }
            try {
                JsonNode body = restClient.get().uri(modelsUrl).retrieve().body(JsonNode.class);
                List<SettingsController.ModelOption> models = parse(body);
                if (models.isEmpty()) {
                    throw new IllegalStateException("OpenRouter returned no free text models");
                }
                snapshot = new Snapshot(List.copyOf(models), Instant.now().plus(SUCCESS_TTL));
            } catch (Exception ex) {
                log.warn("Could not refresh OpenRouter model catalog: {}", ex.toString());
                List<SettingsController.ModelOption> lastKnown = current.models().isEmpty()
                        ? FALLBACK
                        : current.models();
                snapshot = new Snapshot(lastKnown, Instant.now().plus(FAILURE_TTL));
            }
            return snapshot.models();
        }
    }

    public boolean isSelectable(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return true;
        }
        String requested = modelId.trim();
        return availableModels().stream().anyMatch(model -> model.id().equals(requested));
    }

    public synchronized List<SettingsController.ModelOption> availableEmbeddingModels() {
        if (embeddingSnapshot.validUntil().isAfter(Instant.now())) {
            return embeddingSnapshot.models();
        }
        try {
            String endpoint = modelsUrl.substring(0, modelsUrl.length() - "/models".length())
                    + "/embeddings/models";
            JsonNode body = restClient.get().uri(endpoint).retrieve().body(JsonNode.class);
            if (body == null || !body.path("data").isArray()) {
                throw new IllegalStateException("Invalid embedding catalog response");
            }
            embeddingSnapshot = new Snapshot(parseEmbeddings(body), Instant.now().plus(SUCCESS_TTL));
        } catch (Exception ex) {
            log.warn("Could not refresh OpenRouter embedding catalog: {}", ex.toString());
            embeddingSnapshot = new Snapshot(embeddingSnapshot.models(), Instant.now().plus(FAILURE_TTL));
        }
        return embeddingSnapshot.models();
    }

    static List<SettingsController.ModelOption> parse(JsonNode body) {
        return parse(body, "text");
    }

    static List<SettingsController.ModelOption> parseEmbeddings(JsonNode body) {
        return parse(body, "embeddings");
    }

    private static List<SettingsController.ModelOption> parse(JsonNode body, String output) {
        JsonNode data = body == null ? null : body.path("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode model : data) {
            String id = model.path("id").asText("");
            JsonNode outputs = model.path("architecture").path("output_modalities");
            if (id.isBlank() || !hasZeroPricing(model.path("pricing"))
                    || !contains(model.path("architecture").path("input_modalities"), "text")
                    || outputs.size() != 1 || !contains(outputs, output)) {
                continue;
            }

            String description = model.path("description").asText("Free OpenRouter model.").strip();
            if (description.length() > 220) {
                description = description.substring(0, 217).stripTrailing() + "...";
            }
            SettingsController.ModelOption option = new SettingsController.ModelOption(
                    id,
                    model.path("name").asText(id),
                    "OpenRouter",
                    description,
                    model.path("context_length").asInt(0),
                    true,
                    id.equals("openrouter/free") ? "RECOMMENDED" : "FREE");
            candidates.add(new Candidate(option, model.path("created").asLong(0)));
        }

        candidates.sort(Comparator
                .comparing((Candidate candidate) -> !candidate.option().id().equals("openrouter/free"))
                .thenComparing(Candidate::created, Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.option().name()));
        return candidates.stream().map(Candidate::option).toList();
    }

    private static boolean hasZeroPricing(JsonNode pricing) {
        if (!isZero(pricing.path("prompt")) || !isZero(pricing.path("completion"))) return false;
        // A zero token price is insufficient if the endpoint charges per request.
        for (JsonNode price : pricing) {
            if (!isZero(price)) return false;
        }
        return true;
    }

    private static boolean contains(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZero(JsonNode value) {
        try {
            return value.isValueNode() && new BigDecimal(value.asText()).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record Candidate(SettingsController.ModelOption option, Long created) {
    }

    private record Snapshot(List<SettingsController.ModelOption> models, Instant validUntil) {
    }
}
