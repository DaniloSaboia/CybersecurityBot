package com.sentinel.kms.eval;

import com.sentinel.kms.config.RateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRateLimitTest {

    @Test
    void onlyPostRetrievalEvaluationUsesItsOwnBoundedBucket() throws Exception {
        RateLimitConfig.RateLimitFilter filter = new RateLimitConfig.RateLimitFilter(9, 9, 1);

        assertThat(run(filter, "POST", "/api/eval/retrieval").getStatus()).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/eval/retrieval").getStatus()).isEqualTo(429);
        assertThat(run(filter, "GET", "/api/eval/cases").getStatus()).isEqualTo(200);
        assertThat(run(filter, "POST", "/api/ingest/upload").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse run(RateLimitConfig.RateLimitFilter filter,
                                               String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setUserPrincipal(() -> "synthetic-evaluator");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> chainCalls.incrementAndGet());
        if (response.getStatus() == 200) {
            assertThat(chainCalls).hasValue(1);
        }
        return response;
    }
}
