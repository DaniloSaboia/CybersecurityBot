package com.sentinel.kms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationAvailabilityTest {
    private static final Instant NOW = Instant.parse("2026-09-29T09:00:00Z");
    private static final Instant RESET = Instant.parse("2026-09-30T00:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final GenerationAvailability availability = new GenerationAvailability(new ObjectMapper(), clock);

    @Test
    void detectsWrappedStreamingQuotaAndProducesOnlySanitizedExplanation() {
        String body = quotaBody("\"X-RateLimit-Reset\":\"1790726400000\",\"Authorization\":\"Bearer SECRET\"");
        availability.recordFailure(new IllegalStateException("stream failed", response(429, body, new HttpHeaders())));
        assertThat(availability.isBlocked()).isTrue();
        assertThat(availability.current().orElseThrow().retryAt()).isEqualTo(RESET);
        assertThat(availability.userMessage()).contains("daily free-model allowance", "00:00 UTC on 30 Sep 2026")
                .doesNotContain("SECRET", "Authorization", "metadata", "remedy_hint");
    }

    @Test
    void detectsSpringAiSynchronousFailureWithEmbeddedEnvelope() {
        availability.recordFailure(new NonTransientAiException("HTTP 429 - " + quotaBody(
                "\"x-ratelimit-reset\":1790726400000")));
        assertThat(availability.current().orElseThrow().retryAt()).isEqualTo(RESET);
    }

    @Test
    void usesHttpResetHeaderWhenMetadataDoesNotContainOne() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Reset", "1790726400000");
        availability.recordFailure(response(429, quotaBody(""), headers));
        assertThat(availability.current().orElseThrow().retryAt()).isEqualTo(RESET);
        assertThat(availability.current().orElseThrow().providerResetKnown()).isTrue();
    }

    @Test
    void resumesAtResetWithoutAnotherProviderFailureOrManualReset() {
        availability.recordFailure(response(429, quotaBody("\"X-RateLimit-Reset\":1790726400000"), new HttpHeaders()));
        clock.now = RESET.minusMillis(1);
        assertThat(availability.isBlocked()).isTrue();
        clock.now = RESET;
        assertThat(availability.isBlocked()).isFalse();
        assertThat(availability.userMessage()).isEqualTo("AI generation is temporarily unavailable.");
    }

    @Test
    void unknownOrInvalidResetUsesBriefCooldownWithoutClaimingProviderReset() {
        for (String reset : new String[] {"invalid", "1", "1790726400", "9999999999999"}) {
            GenerationAvailability candidate = new GenerationAvailability(new ObjectMapper(), clock);
            candidate.recordFailure(response(429, quotaBody("\"X-RateLimit-Reset\":\"" + reset + "\""), new HttpHeaders()));
            assertThat(candidate.current().orElseThrow().retryAt()).isEqualTo(NOW.plusSeconds(300));
            assertThat(candidate.current().orElseThrow().providerResetKnown()).isFalse();
            assertThat(candidate.userMessage()).contains("did not supply a usable reset time").doesNotContain(reset);
        }
    }

    @Test
    void doesNotConfuseUpstreamOrMinuteRateLimitsWithDailyAccountQuota() {
        for (String source : new String[] {"upstream_provider", "openrouter_free_tier_minute", ""}) {
            availability.recordFailure(response(429, """
                    {"error":{"code":429,"message":"Rate limit exceeded","metadata":{"limit_source":"%s"}}}
                    """.formatted(source), new HttpHeaders()));
            assertThat(availability.isBlocked()).isFalse();
        }
        availability.recordFailure(response(503, quotaBody(""), new HttpHeaders()));
        assertThat(availability.isBlocked()).isFalse();
    }

    @Test
    void acceptsLegacyDailyQuotaMessageOnlyWithoutConflictingLimitMetadata() {
        availability.recordFailure(response(429, """
                {"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day",
                "metadata":{"limit_source":"upstream_provider"}}}
                """, new HttpHeaders()));
        assertThat(availability.isBlocked()).isFalse();
        availability.recordFailure(response(429, """
                {"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day"}}
                """, new HttpHeaders()));
        assertThat(availability.isBlocked()).isTrue();
        clock.now = NOW.plusSeconds(300);
        assertThat(availability.isBlocked()).isFalse();
    }

    @Test
    void malformedUnknownAndOversizedErrorsNeverLeakOrDisableGeneration() {
        for (String body : new String[] {"SECRET", "{bad json SECRET}", "{\"error\":{\"code\":401}}",
                "{\"error\":null}", "SECRET".repeat(12_000)}) {
            availability.recordFailure(response(429, body, new HttpHeaders()));
            assertThat(availability.isBlocked()).isFalse();
            assertThat(availability.userMessage()).doesNotContain("SECRET");
        }
        availability.recordFailure(null);
        assertThat(availability.isBlocked()).isFalse();
    }

    @Test
    void unrelatedOrShorterFailuresDoNotClearKnownDailyCooldown() {
        availability.recordFailure(response(429, quotaBody("\"X-RateLimit-Reset\":1790726400000"), new HttpHeaders()));
        availability.recordFailure(new IllegalStateException("timeout"));
        availability.recordFailure(response(429, quotaBody(""), new HttpHeaders()));
        assertThat(availability.current().orElseThrow().retryAt()).isEqualTo(RESET);
        assertThat(availability.current().orElseThrow().providerResetKnown()).isTrue();
    }

    private static String quotaBody(String headers) {
        return """
                {"error":{"code":429,"message":"Provider text must not be surfaced SECRET",
                "metadata":{"limit_source":"openrouter_free_tier_daily","headers":{%s},
                "remedy_hint":"Provider advertising or other arbitrary text"}}}
                """.formatted(headers);
    }

    private static WebClientResponseException response(int status, String body, HttpHeaders headers) {
        return WebClientResponseException.create(status, "Provider failure", headers,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
