package com.sentinel.kms.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** The review-schedule endpoint is PATCH, so its browser preflight must pass. */
class SecurityConfigCorsTest {

    @Test
    void patchPreflightIsAllowedForLifecycleReviewSchedule() {
        SecurityConfig security = new SecurityConfig("https://console.example", false);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS",
                "/api/kb/articles/article-1/review-schedule");
        request.addHeader("Origin", "https://console.example");
        request.addHeader("Access-Control-Request-Method", "PATCH");

        CorsConfiguration cors = security.corsConfigurationSource().getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedMethods()).contains("PATCH");
    }
}
