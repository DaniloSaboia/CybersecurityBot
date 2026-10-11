package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared, in-memory cooldown for the server's account-wide free-model quota.
 * Upstream throttling and unknown errors must not disable other free models.
 * Retains only a safe reason and time; never retains or returns provider payloads.
 */
@Component
public class GenerationAvailability {
    private static final Duration UNKNOWN_RESET_COOLDOWN = Duration.ofMinutes(5);
    private static final Duration MAX_RESET_DISTANCE = Duration.ofHours(48);
    private static final int MAX_ERROR_LENGTH = 65_536;
    private static final String DAILY_QUOTA = "openrouter_free_tier_daily";
    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter
            .ofPattern("HH:mm 'UTC on' dd MMM uuuu", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final Clock clock;
    private final AtomicReference<Blockage> blockage = new AtomicReference<>();

    @Autowired
    public GenerationAvailability(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    GenerationAvailability(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Optional<Blockage> current() {
        Blockage value = blockage.get();
        if (value != null && !value.retryAt().isAfter(clock.instant())) {
            blockage.compareAndSet(value, null);
            value = blockage.get();
        }
        return Optional.ofNullable(value);
    }

    public boolean isBlocked() {
        return current().isPresent();
    }

    public String userMessage() {
        return current().map(Blockage::userMessage)
                .orElse("AI generation is temporarily unavailable.");
    }

    public void recordFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 12 && seen.add(cause);
             cause = cause.getCause()) {
            Blockage detected = classify(cause);
            if (detected != null) {
                blockage.updateAndGet(previous -> previous != null
                        && previous.retryAt().isAfter(detected.retryAt()) ? previous : detected);
                return;
            }
        }
    }

    private Blockage classify(Throwable failure) {
        Integer httpStatus = null;
        HttpHeaders responseHeaders = null;
        String body;
        if (failure instanceof WebClientResponseException response) {
            httpStatus = response.getStatusCode().value();
            responseHeaders = response.getHeaders();
            body = response.getResponseBodyAsString();
        } else if (failure instanceof RestClientResponseException response) {
            httpStatus = response.getStatusCode().value();
            responseHeaders = response.getResponseHeaders();
            body = response.getResponseBodyAsString();
        } else {
            // Spring AI's NonTransientAiException embeds the JSON after "HTTP 429 -".
            body = failure.getMessage();
        }
        if (body == null || body.length() > MAX_ERROR_LENGTH || (httpStatus != null && httpStatus != 429)) {
            return null;
        }
        int jsonStart = body.indexOf('{');
        if (jsonStart < 0) return null;
        try {
            JsonNode error = mapper.readTree(body.substring(jsonStart)).path("error");
            if ((httpStatus == null && error.path("code").asInt(-1) != 429) || !error.isObject()) {
                return null;
            }
            JsonNode metadata = error.path("metadata");
            String source = metadata.path("limit_source").asText("");
            boolean legacyDailyQuota = source.isBlank()
                    && error.path("message").asText("").contains("free-models-per-day");
            if (!DAILY_QUOTA.equals(source) && !legacyDailyQuota) return null;

            Instant now = clock.instant();
            Instant reset = resetTime(header(metadata.path("headers"), "X-RateLimit-Reset"), now);
            if (reset == null && responseHeaders != null) {
                reset = resetTime(responseHeaders.getFirst("X-RateLimit-Reset"), now);
            }
            return new Blockage(reset == null ? now.plus(UNKNOWN_RESET_COOLDOWN) : reset, reset != null);
        } catch (Exception ignored) {
            // A malformed or unfamiliar response is not proof of an account-wide outage.
            return null;
        }
    }

    private static String header(JsonNode headers, String expected) {
        var fields = headers.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (expected.equalsIgnoreCase(field.getKey())) return field.getValue().asText();
        }
        return null;
    }

    private static Instant resetTime(String value, Instant now) {
        if (value == null) return null;
        try {
            // OpenRouter's X-RateLimit-Reset is a Unix timestamp in milliseconds.
            Instant reset = Instant.ofEpochMilli(Long.parseLong(value));
            return reset.isAfter(now) && !reset.isAfter(now.plus(MAX_RESET_DISTANCE)) ? reset : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record Blockage(Instant retryAt, boolean providerResetKnown) {
        public String userMessage() {
            String reason = "AI generation is unavailable because the configured OpenRouter account "
                    + "has used its daily free-model allowance.";
            return providerResetKnown
                    ? reason + " The provider reports a reset at " + RESET_FORMAT.format(retryAt) + "."
                    : reason + " The provider did not supply a usable reset time; requests are paused briefly.";
        }
    }
}
