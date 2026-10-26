package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic, opt-in HTTP provider adapter. It ships with no endpoint, credential,
 * or automatic invocation. Use only through {@link ProviderEvaluationHarness}
 * with an explicit opt-in request authorized by the caller.
 *
 * <p>Request JSON: {@code {question, baseline, evidence}}. Response JSON must
 * contain {@code answer}; it may contain {@code citationReferences}, legacy
 * {@code citations}, {@code latencyMs}, and {@code usage} ({@code inputTokens},
 * {@code outputTokens}, {@code costUsd}, {@code currency}, {@code estimated}).
 */
public final class HttpProviderAnswerSource implements ProviderAnswerSource {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String provider;
    private final String model;
    private final String authorizationHeader;
    private final Duration timeout;

    public HttpProviderAnswerSource(URI endpoint, String provider, String model,
                                    String authorizationHeader, Duration timeout) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), endpoint, provider, model, authorizationHeader, timeout);
    }

    HttpProviderAnswerSource(HttpClient client, ObjectMapper mapper, URI endpoint, String provider, String model,
                             String authorizationHeader, Duration timeout) {
        if (endpoint == null || endpoint.getScheme() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("provider endpoint must use http or https");
        }
        this.client = client == null ? HttpClient.newHttpClient() : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.endpoint = endpoint;
        this.provider = blankOr(provider, "unknown-http-provider");
        this.model = blankOr(model, "unknown");
        this.authorizationHeader = authorizationHeader == null ? "" : authorizationHeader.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    @Override
    public AnswerCandidate answer(AnswerEvalCase evalCase, AnswerBaseline baseline) {
        if (evalCase == null || baseline == null) {
            throw new IllegalArgumentException("evalCase and baseline are required");
        }
        long startedNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(evalCase, baseline)));
            if (!authorizationHeader.isBlank()) {
                request.header("Authorization", authorizationHeader);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("provider returned HTTP " + response.statusCode());
            }
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            return parseResponse(response.body(), startedAt, elapsedMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider evaluation interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("provider evaluation request failed", e);
        }
    }

    String requestBody(AnswerEvalCase evalCase, AnswerBaseline baseline) {
        ObjectNode root = mapper.createObjectNode();
        root.put("question", evalCase.question());
        root.put("baseline", baseline.name());
        root.set("evidence", mapper.valueToTree(evalCase.evidence()));
        return root.toString();
    }

    AnswerCandidate parseResponse(String body, Instant startedAt, long measuredLatencyMs) throws IOException {
        JsonNode root = mapper.readTree(body);
        String answer = root.path("answer").asText(root.path("content").asText(""));
        if (answer.isBlank()) {
            throw new IllegalArgumentException("provider response must contain a non-blank answer or content field");
        }
        List<CitationReference> references = new ArrayList<>();
        for (JsonNode citation : root.path("citationReferences")) {
            references.add(mapper.treeToValue(citation, CitationReference.class));
        }
        List<String> legacyIds = new ArrayList<>();
        JsonNode legacyNode = root.has("legacyCitationIds") ? root.path("legacyCitationIds") : root.path("citations");
        for (JsonNode citation : legacyNode) {
            if (citation.isTextual()) {
                legacyIds.add(citation.asText());
            }
        }
        JsonNode usage = root.path("usage");
        String amount = usage.has("costUsd") ? usage.path("costUsd").asText() : usage.path("amount").asText(null);
        CostMetadata cost = new CostMetadata(usage.path("currency").asText("USD"), amount,
                usage.path("estimated").asBoolean(true), "provider-response",
                nullableLong(usage.get("inputTokens")), nullableLong(usage.get("outputTokens")));
        long latency = root.path("latencyMs").canConvertToLong() ? root.path("latencyMs").asLong() : measuredLatencyMs;
        return new AnswerCandidate(answer, references, legacyIds,
                new EvaluationMetadata(EvaluationExecutionMode.PROVIDER_OPT_IN, provider, model, startedAt, latency, cost));
    }

    private static Long nullableLong(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong() : null;
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
