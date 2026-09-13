package com.sentinel.kms.audit;

import com.sentinel.kms.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP binding regressions for the authenticated, workspace-scoped audit stream. */
@ExtendWith(MockitoExtension.class)
class AuditReadControllerTest {

    @Mock AuditReadService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "alice"));
        mvc = MockMvcBuilders.standaloneSetup(new AuditController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtResolver(jwt))
                .build();
    }

    @Test
    void forwardsTheAuthenticatedSubjectAndBoundedKeysetParameters() throws Exception {
        when(service.list("alice", "workspace-1", 2, "cursor-1"))
                .thenReturn(new AuditEventPage(List.of(event()), "cursor-2"));

        mvc.perform(get("/api/audit/events")
                        .param("workspaceId", "workspace-1")
                        .param("limit", "2")
                        .param("before", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value("event-1"))
                .andExpect(jsonPath("$.events[0].action").value("AUDIT_READ"))
                .andExpect(jsonPath("$.events[0].metadata.limit").value(2))
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"));

        verify(service).list("alice", "workspace-1", 2, "cursor-1");
    }

    @Test
    void deniesUnauthorizedWorkspaceWithoutExposingMembershipDetails() throws Exception {
        doThrow(new AccessDeniedException("membership role ADMIN required"))
                .when(service).list("alice", "workspace-2", null, null);

        mvc.perform(get("/api/audit/events").param("workspaceId", "workspace-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"));
    }

    @Test
    void requiresWorkspaceId() throws Exception {
        mvc.perform(get("/api/audit/events"))
                .andExpect(status().isBadRequest());
    }

    private static AuditEventDto event() {
        return new AuditEventDto("event-1", OffsetDateTime.parse("2026-09-06T12:00:00Z"), "alice",
                "AUDIT_READ", "audit_workspace", "workspace-1", "workspace-1", null, null,
                "success", Map.of("limit", 2));
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
