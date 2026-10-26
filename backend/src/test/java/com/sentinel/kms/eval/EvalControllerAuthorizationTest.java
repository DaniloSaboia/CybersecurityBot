package com.sentinel.kms.eval;

import com.sentinel.kms.common.GlobalExceptionHandler;
import com.sentinel.kms.kb.KnowledgeAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EvalControllerAuthorizationTest {

    @Mock GoldenSet goldenSet;
    @Mock EvalService evalService;
    @Mock KnowledgeAccessService access;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "synthetic-viewer"));
        mvc = MockMvcBuilders.standaloneSetup(new EvalController(goldenSet, evalService, access))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(jwtResolver(jwt))
                .build();
    }

    @Test
    void datasetReadIsDeniedBeforeGoldenSetAccessForAnyAuthenticatedUserWithoutExplicitGrant() throws Exception {
        when(access.canViewEvaluationDataset("synthetic-viewer")).thenReturn(false);

        mvc.perform(get("/api/eval/cases"))
                .andExpect(status().isForbidden());

        verify(access).canViewEvaluationDataset("synthetic-viewer");
        verifyNoInteractions(goldenSet, evalService);
    }

    @Test
    void explicitlyAuthorizedDatasetViewerReceivesCases() throws Exception {
        when(access.canViewEvaluationDataset("synthetic-viewer")).thenReturn(true);
        when(goldenSet.cases()).thenReturn(List.of(new EvalCase(
                "synthetic-case", "synthetic question", List.of("article-1"), null, null)));

        mvc.perform(get("/api/eval/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("synthetic-case"));

        verify(access).canViewEvaluationDataset("synthetic-viewer");
        verify(goldenSet).cases();
    }

    @Test
    void executionIsDeniedBeforeEvalServiceOrProviderWorkForAViewerWithoutExplicitExecutionGrant() throws Exception {
        doThrow(new AccessDeniedException("explicit execution grant required"))
                .when(access).requireEvaluationExecution("synthetic-viewer");

        mvc.perform(post("/api/eval/retrieval"))
                .andExpect(status().isForbidden());

        verify(access).requireEvaluationExecution("synthetic-viewer");
        verifyNoInteractions(evalService, goldenSet);
    }

    @Test
    void authorizedExecutionForwardsOnlyTheJwtSubjectAfterTheGrantCheck() throws Exception {
        EvalService.EvalReport report = new EvalService.EvalReport(
                Instant.EPOCH, 1, 3, 1.0, 1.0, 1.0, 1, List.of());
        when(evalService.run("synthetic-viewer")).thenReturn(report);

        mvc.perform(post("/api/eval/retrieval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").value(1));

        InOrder order = inOrder(access, evalService);
        order.verify(access).requireEvaluationExecution("synthetic-viewer");
        order.verify(evalService).run("synthetic-viewer");
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
