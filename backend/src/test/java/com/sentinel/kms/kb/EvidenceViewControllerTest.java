package com.sentinel.kms.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EvidenceViewControllerTest {

    @Mock EvidenceViewService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "analyst-1"));
        mvc = MockMvcBuilders.standaloneSetup(new EvidenceViewController(service))
                .setCustomArgumentResolvers(jwtResolver(jwt))
                .build();
    }

    @Test
    void forwardsOnlyTheJwtSubjectAndReturnsCanonicalImmutablePassage() throws Exception {
        when(service.view(eq("analyst-1"), any(EvidenceViewRequest.class))).thenReturn(Optional.of(response()));

        mvc.perform(validRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleId").value("article-1"))
                .andExpect(jsonPath("$.articleRevisionId").value("revision-7"))
                .andExpect(jsonPath("$.evidenceSnapshotId").value("snapshot-9"))
                .andExpect(jsonPath("$.sourceSpanStart").value(4))
                .andExpect(jsonPath("$.sourceSpanEnd").value(7))
                .andExpect(jsonPath("$.passage").value("vid"));

        ArgumentCaptor<EvidenceViewRequest> request = ArgumentCaptor.forClass(EvidenceViewRequest.class);
        verify(service).view(eq("analyst-1"), request.capture());
        assertThat(request.getValue()).isEqualTo(new EvidenceViewRequest(
                "article-1", "revision-7", 7, "snapshot-9", 4, 7));
    }

    @Test
    void makesRevokedForbiddenForgedAndUnavailableEvidenceIndistinguishableAs404() throws Exception {
        when(service.view(eq("analyst-1"), any(EvidenceViewRequest.class))).thenReturn(Optional.empty());

        mvc.perform(validRequest()).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedIdentityWithoutCallingTheEvidenceService() throws Exception {
        mvc.perform(get("/api/kb/evidence/view")
                        .param("articleId", "article-1")
                        .param("articleRevisionId", "revision-7")
                        .param("revisionNumber", "0")
                        .param("evidenceSnapshotId", "snapshot-9")
                        .param("sourceSpanStart", "9")
                        .param("sourceSpanEnd", "2"))
                .andExpect(status().isBadRequest());

        verify(service, never()).view(any(), any());
    }

    @Test
    void treatsEmptySpansAsMalformedRatherThanReturningABlankEvidenceQuote() throws Exception {
        mvc.perform(get("/api/kb/evidence/view")
                        .param("articleId", "article-1")
                        .param("articleRevisionId", "revision-7")
                        .param("revisionNumber", "7")
                        .param("evidenceSnapshotId", "snapshot-9")
                        .param("sourceSpanStart", "4")
                        .param("sourceSpanEnd", "4"))
                .andExpect(status().isBadRequest());

        verify(service, never()).view(any(), any());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return get("/api/kb/evidence/view")
                .param("articleId", "article-1")
                .param("articleRevisionId", "revision-7")
                .param("revisionNumber", "7")
                .param("evidenceSnapshotId", "snapshot-9")
                .param("sourceSpanStart", "4")
                .param("sourceSpanEnd", "7");
    }

    private static EvidenceViewResponse response() {
        return new EvidenceViewResponse("article-1", "revision-7", 7, "snapshot-9", 4, 7,
                "Threat report", "https://example.test/report", "2026-09-01", "approved", "vid");
    }

    private static HandlerMethodArgumentResolver jwtResolver(Jwt jwt) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && Jwt.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return jwt;
            }
        };
    }
}
