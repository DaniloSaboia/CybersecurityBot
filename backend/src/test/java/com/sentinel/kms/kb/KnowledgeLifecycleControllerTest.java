package com.sentinel.kms.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keeps the lifecycle API additive: every route is bound to its specific
 * workflow operation and receives only the authenticated subject.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeLifecycleControllerTest {

    private static final String ARTICLE_ID = "article-1";
    private static final String REVISION_ID = "revision-2";
    private static final String SUBJECT = "analyst-1";

    @Mock
    KnowledgeLifecycleService lifecycle;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", SUBJECT));
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeLifecycleController(lifecycle))
                .setCustomArgumentResolvers(jwtResolver(jwt))
                .build();
    }

    @Test
    void lifecycleRouteForwardsTheAuthenticatedSubject() throws Exception {
        when(lifecycle.lifecycle(SUBJECT, ARTICLE_ID)).thenReturn(response(7));

        mvc.perform(get("/api/kb/articles/{articleId}/lifecycle", ARTICLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleId").value(ARTICLE_ID))
                .andExpect(jsonPath("$.currentRevisionId").value(REVISION_ID))
                .andExpect(jsonPath("$.stateVersion").value(7));

        verify(lifecycle).lifecycle(SUBJECT, ARTICLE_ID);
    }

    @Test
    void createDraftReturnsCreatedAndForwardsOnlyTheJwtSubject() throws Exception {
        when(lifecycle.createDraft(eq(SUBJECT), eq(ARTICLE_ID), any())).thenReturn(response(8));

        mvc.perform(post("/api/kb/articles/{articleId}/revisions", ARTICLE_ID)
                        .contentType("application/json")
                        .content(createRevisionBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stateVersion").value(8));

        ArgumentCaptor<KnowledgeLifecycleDtos.CreateRevisionRequest> request =
                ArgumentCaptor.forClass(KnowledgeLifecycleDtos.CreateRevisionRequest.class);
        verify(lifecycle).createDraft(eq(SUBJECT), eq(ARTICLE_ID), request.capture());
        assertThat(request.getValue().expectedStateVersion()).isEqualTo(7);
        assertThat(request.getValue().snapshot().title()).isEqualTo("Draft intelligence");
        assertThat(request.getValue().evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.content()).isEqualTo("immutable evidence"));
    }

    @Test
    void transitionRoutesBindToTheirSpecificLifecycleOperations() throws Exception {
        when(lifecycle.submit(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any())).thenReturn(response(8));
        when(lifecycle.approve(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any())).thenReturn(response(9));
        when(lifecycle.reject(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any())).thenReturn(response(10));
        when(lifecycle.expire(eq(SUBJECT), eq(ARTICLE_ID), any())).thenReturn(response(11));

        mvc.perform(post("/api/kb/articles/{articleId}/revisions/{revisionId}/submit", ARTICLE_ID, REVISION_ID)
                        .contentType("application/json").content(transitionBody()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/kb/articles/{articleId}/revisions/{revisionId}/approve", ARTICLE_ID, REVISION_ID)
                        .contentType("application/json").content(transitionBody()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/kb/articles/{articleId}/revisions/{revisionId}/reject", ARTICLE_ID, REVISION_ID)
                        .contentType("application/json").content(transitionBody()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/kb/articles/{articleId}/expire", ARTICLE_ID)
                        .contentType("application/json").content(transitionBody()))
                .andExpect(status().isOk());

        verify(lifecycle).submit(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any());
        verify(lifecycle).approve(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any());
        verify(lifecycle).reject(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any());
        verify(lifecycle).expire(eq(SUBJECT), eq(ARTICLE_ID), any());
    }

    @Test
    void supersedeRollbackAndReviewScheduleRoutesRemainAdditive() throws Exception {
        when(lifecycle.supersede(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any())).thenReturn(response(8));
        when(lifecycle.rollback(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any())).thenReturn(response(9));
        when(lifecycle.updateReviewSchedule(eq(SUBJECT), eq(ARTICLE_ID), any())).thenReturn(response(10));

        mvc.perform(post("/api/kb/articles/{articleId}/revisions/{revisionId}/supersede", ARTICLE_ID, REVISION_ID)
                        .contentType("application/json").content(supersedeBody()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/kb/articles/{articleId}/revisions/{revisionId}/rollback", ARTICLE_ID, REVISION_ID)
                        .contentType("application/json").content(rollbackBody()))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/kb/articles/{articleId}/review-schedule", ARTICLE_ID)
                        .contentType("application/json").content(reviewScheduleBody()))
                .andExpect(status().isOk());

        verify(lifecycle).supersede(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any());
        verify(lifecycle).rollback(eq(SUBJECT), eq(ARTICLE_ID), eq(REVISION_ID), any());
        verify(lifecycle).updateReviewSchedule(eq(SUBJECT), eq(ARTICLE_ID), any());
    }

    private static KnowledgeLifecycleDtos.LifecycleResponse response(long stateVersion) {
        return new KnowledgeLifecycleDtos.LifecycleResponse(
                ARTICLE_ID, "workspace-1", "internal", "workspace", "approved", "active", REVISION_ID,
                stateVersion, OffsetDateTime.parse("2026-10-01T00:00:00Z"), null, null);
    }

    private static String createRevisionBody() {
        return """
                {"expectedStateVersion":7,
                 "snapshot":{"title":"Draft intelligence","categoryId":"category-1","body":[],"iocs":[],"related":[]},
                 "evidence":[{"sourceUri":"https://example.test/source","sourceLabel":"report","sourceVersion":"v1","content":"immutable evidence"}],
                 "reviewDueAt":"2026-10-01T00:00:00Z"}
                """;
    }

    private static String transitionBody() {
        return """
                {"expectedStateVersion":7,"reviewDueAt":"2026-10-01T00:00:00Z",
                 "expiresAt":"2026-12-01T00:00:00Z","reasonCode":"REVIEW_COMPLETE"}
                """;
    }

    private static String supersedeBody() {
        return """
                {"expectedStateVersion":7,"successorRevisionId":"revision-3",
                 "reviewDueAt":"2026-10-01T00:00:00Z","reasonCode":"SUPERSEDED"}
                """;
    }

    private static String rollbackBody() {
        return """
                {"expectedStateVersion":7,"targetRevisionId":"revision-1",
                 "reviewDueAt":"2026-10-01T00:00:00Z","reasonCode":"ROLLBACK_APPROVED"}
                """;
    }

    private static String reviewScheduleBody() {
        return """
                {"expectedStateVersion":7,"reviewDueAt":"2026-10-02T00:00:00Z"}
                """;
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
