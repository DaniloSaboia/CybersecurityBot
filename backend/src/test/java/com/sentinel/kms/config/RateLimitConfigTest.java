package com.sentinel.kms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

    private RateLimitConfig.RateLimitFilter filter(int permits) {
        return new RateLimitConfig.RateLimitFilter(permits, permits);
    }

    private MockHttpServletResponse run(RateLimitConfig.RateLimitFilter filter, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setUserPrincipal(() -> "user-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> chainCalls.incrementAndGet());
        return response;
    }

    @Test
    void chatRequestsOverTheCapGet429() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(2);
        assertThat(run(filter, "/api/assistant/chat").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/assistant/chat").getStatus()).isEqualTo(200);
        MockHttpServletResponse blocked = run(filter, "/api/assistant/chat");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void bucketsArePerSurface() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(1);
        assertThat(run(filter, "/api/assistant/chat").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/ingest/upload").getStatus()).isEqualTo(200); // separate bucket
        assertThat(run(filter, "/api/assistant/chat").getStatus()).isEqualTo(429);
    }

    @Test
    void unthrottledPathsPassThrough() throws Exception {
        assertThat(run(filter(1), "/api/kb/articles").getStatus()).isEqualTo(200);
    }

    @Test
    void streamingChatCountsAgainstTheSameBucket() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(2);
        assertThat(run(filter, "/api/assistant/chat/stream").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/assistant/chat").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/assistant/chat/stream").getStatus()).isEqualTo(429);
    }

    @Test
    void fallsBackToRemoteIpWhenAnonymous() throws Exception {
        RateLimitConfig.RateLimitFilter filter = filter(1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/assistant/chat");
        request.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void progressPollingDoesNotConsumeUploadBudgetButRefreshDoes() throws Exception {
        var filter = filter(1);
        for (int i = 0; i < 30; i++) {
            var request = new MockHttpServletRequest("GET", "/api/ingest/jobs");
            request.setUserPrincipal(() -> "user-1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(run(filter, "/api/connectors/kev/sync").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/ingest/upload").getStatus()).isEqualTo(429);
    }
}
