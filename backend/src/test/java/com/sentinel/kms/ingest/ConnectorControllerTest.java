package com.sentinel.kms.ingest;

import com.sentinel.kms.kb.KnowledgeAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConnectorControllerTest {

    @Mock IngestService ingestService;
    @Mock NvdConnectorService nvd;
    @Mock KevConnectorService kev;
    @Mock AttackConnectorService attack;
    @Mock KnowledgeAccessService access;
    @Mock FeedSyncService feeds;

    private ConnectorController controller;

    @BeforeEach
    void setUp() {
        controller = new ConnectorController(ingestService, nvd, kev, attack, access, feeds);
    }

    @Test
    void viewerCannotStartConnectorSyncEvenWhenRequestMetadataClaimsAdmin() {
        Jwt viewer = jwt("viewer-1", Map.of("role", "admin", "user_metadata", Map.of("role", "admin")));
        doThrow(new AccessDeniedException("connector mutation requires trusted editor role"))
                .when(access).requireConnectorMutation("viewer-1", "nvd");

        assertThatThrownBy(() -> controller.sync("nvd", 7, 15, viewer))
                .isInstanceOf(AccessDeniedException.class);

        verify(nvd, never()).sync(7, 15);
    }

    @Test
    void trustedEditorAuthorizationIsCheckedBeforeSyncDispatch() {
        Jwt editor = jwt("editor-1", Map.of("role", "viewer"));

        controller.sync("kev", 7, 3, editor);

        verify(access).requireConnectorMutation("editor-1", "kev");
        verify(feeds).enqueue("kev", "manual");
    }

    private static Jwt jwt(String subject, Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), merge(Map.of("sub", subject), claims));
    }

    private static Map<String, Object> merge(Map<String, Object> required, Map<String, Object> supplied) {
        java.util.Map<String, Object> merged = new java.util.HashMap<>(required);
        merged.putAll(supplied);
        return merged;
    }
}
