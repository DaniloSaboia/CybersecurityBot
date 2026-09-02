package com.sentinel.kms.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-user rate limiting for the expensive surfaces: LLM-backed chat and the
 * ingestion pipeline. Buckets are in-memory (single-instance deployments) and
 * keyed by the authenticated principal (Supabase user sub), falling back to
 * the client IP for anonymous callers that slipped past the security chain.
 *
 * <p>Limits (per minute, refill is continuous):
 * <ul>
 *   <li>{@code /api/assistant/**} — chat requests (LLM cost driver)</li>
 *   <li>{@code {@code /api/ingest/**}} — uploads / URL imports (embedding cost driver)</li>
 * </ul>
 * Over the limit the filter short-circuits with {@code 429} and a JSON body
 * carrying the seconds until capacity returns.
 */
@Configuration
public class RateLimitConfig {

    /** Filter that applies Bucket4j limits to chat + ingest paths. */
    public static class RateLimitFilter extends OncePerRequestFilter {

        private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        private final Function<String, Bucket> chatBucketFactory;
        private final Function<String, Bucket> ingestBucketFactory;
        private final int chatPerMinute;
        private final int ingestPerMinute;

        public RateLimitFilter(int chatPerMinute, int ingestPerMinute) {
            this.chatPerMinute = chatPerMinute;
            this.ingestPerMinute = ingestPerMinute;
            this.chatBucketFactory = user -> newBucket(chatPerMinute);
            this.ingestBucketFactory = user -> newBucket(ingestPerMinute);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            RateClass rateClass = classify(path);
            if (rateClass == RateClass.NONE) {
                chain.doFilter(request, response);
                return;
            }

            String principal = request.getUserPrincipal() != null
                    ? request.getUserPrincipal().getName()
                    : request.getRemoteAddr();
            String key = rateClass.prefix + principal;
            Bucket bucket = buckets.computeIfAbsent(key,
                    rateClass == RateClass.CHAT ? chatBucketFactory : ingestBucketFactory);

            if (bucket.tryConsume(1)) {
                chain.doFilter(request, response);
                return;
            }

            long waitSeconds = Math.max(1,
                    bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() / 1_000_000_000 + 1);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"error\":\"Rate limit exceeded\","
                    + "\"message\":\"Too many " + rateClass.label + " requests. Retry in "
                    + waitSeconds + "s.\",\"retryAfterSeconds\":" + waitSeconds + "}");
        }

        private static Bucket newBucket(int permitsPerMinute) {
            Refill refill = Refill.greedy(permitsPerMinute, Duration.ofMinutes(1));
            return Bucket.builder().addLimit(Bandwidth.classic(permitsPerMinute, refill)).build();
        }

        private enum RateClass {
            NONE("", ""),
            CHAT("chat:", "chat"),
            INGEST("ingest:", "ingest");

            final String prefix;
            final String label;

            RateClass(String prefix, String label) {
                this.prefix = prefix;
                this.label = label;
            }
        }

        private static RateClass classify(String path) {
            if (path == null) {
                return RateClass.NONE;
            }
            if (path.startsWith("/api/assistant/")) {
                return RateClass.CHAT;
            }
            if (path.startsWith("/api/ingest/")) {
                return RateClass.INGEST;
            }
            return RateClass.NONE;
        }

        /** Test seam: drops all cached buckets. */
        void resetForTest() {
            buckets.clear();
        }
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${app.ratelimit.chat-per-minute:12}") int chatPerMinute,
            @Value("${app.ratelimit.ingest-per-minute:6}") int ingestPerMinute) {
        RateLimitFilter filter = new RateLimitFilter(chatPerMinute, ingestPerMinute);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        // After the Spring Security chain (-100) so getUserPrincipal() is populated.
        registration.setOrder(50);
        registration.addUrlPatterns("/api/*");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
