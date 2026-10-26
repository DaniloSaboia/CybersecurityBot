package com.sentinel.kms.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP binding regressions for the optional owner-scoped Idempotency-Key API. */
@ExtendWith(MockitoExtension.class)
class IngestControllerTest {

    @Mock IngestService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), Map.of("sub", "alice"));
        mvc = MockMvcBuilders.standaloneSetup(new IngestController(service))
                .setCustomArgumentResolvers(jwtResolver(jwt))
                .build();
    }

    @Test
    void uploadBindsOptionalIdempotencyKeyAndPreservesTheExistingDtoResponse() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evidence.txt", "text/plain", "original bytes".getBytes());
        when(service.createUploadJob(any(), eq("alice"), eq("lost-response-key")))
                .thenReturn(dto("job-1"));

        mvc.perform(multipart("/api/ingest/upload")
                        .file(file)
                        .header("Idempotency-Key", "lost-response-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-1"));

        verify(service).createUploadJob(any(), eq("alice"), eq("lost-response-key"));
    }

    @Test
    void urlBindsOptionalIdempotencyKey() throws Exception {
        when(service.createUrlJob("https://example.com/advisory", "alice", "url-key"))
                .thenReturn(dto("job-url"));

        mvc.perform(post("/api/ingest/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "url-key")
                        .content("{\"url\":\"https://example.com/advisory\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("job-url"));

        verify(service).createUrlJob("https://example.com/advisory", "alice", "url-key");
    }

    @Test
    void payloadMismatchIsExposedAsConflictInsteadOfCreatingAnotherJob() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evidence.txt", "text/plain", "different bytes".getBytes());
        doThrow(new ResponseStatusException(CONFLICT, "Idempotency-Key was already used for a different request"))
                .when(service).createUploadJob(any(), eq("alice"), eq("lost-response-key"));

        mvc.perform(multipart("/api/ingest/upload")
                        .file(file)
                        .header("Idempotency-Key", "lost-response-key"))
                .andExpect(status().isConflict());
    }

    private static HandlerMethodArgumentResolver jwtResolver(Jwt jwt) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && Jwt.class.isAssignableFrom(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer container,
                    NativeWebRequest request,
                    WebDataBinderFactory binderFactory) {
                return jwt;
            }
        };
    }

    private static IngestJobDto dto(String id) {
        return new IngestJobDto(id, "evidence.txt", "Manual upload", "12 B", "queued", 0, 0, "now", null);
    }
}
