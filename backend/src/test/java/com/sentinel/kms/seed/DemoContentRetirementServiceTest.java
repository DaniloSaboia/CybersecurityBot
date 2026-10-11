package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.WorkspaceRole;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.KnowledgeAccessService;
import com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;
import com.sentinel.kms.kb.KnowledgeLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DemoContentRetirementServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ArticleRepository articles = mock(ArticleRepository.class);
    final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
    final KnowledgeLifecycleService lifecycle = mock(KnowledgeLifecycleService.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final DemoContentRetirementService service = new DemoContentRetirementService(jdbc, mapper, articles, access, lifecycle);
    Map<String, Object> row;
    Article projection;
    String id;

    @BeforeEach
    void setUp() throws Exception {
        when(access.contextFor("admin")).thenReturn(new KnowledgeAccessContext("admin", Map.of("shared", WorkspaceRole.ADMIN)));
        ObjectNode fixture;
        try (var input = new ClassPathResource("seed/seed.json").getInputStream()) {
            fixture = (ObjectNode) mapper.readTree(input).path("articles").get(0);
        }
        fixture.set("categoryId", fixture.remove("category"));
        fixture.set("updatedOn", fixture.remove("updated"));
        if (!fixture.has("cvss")) fixture.putNull("cvss");
        id = fixture.path("id").asText();
        projection = mapper.treeToValue(fixture, Article.class);
        row = new HashMap<>();
        row.put("workspace_id", "shared"); row.put("classification", "public");
        row.put("read_policy", "public"); row.put("approval_status", "approved");
        row.put("lifecycle_status", "active"); row.put("state_version", 1L);
        row.put("governance_actor", "system:seed"); row.put("revision_actor", "system:seed");
        row.put("approved_by_sub", "system:seed"); row.put("revision_number", 1);
        row.put("revision_count", 1L); row.put("review_count", 0L);
        row.put("snapshot", mapper.writeValueAsString(fixture));
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(row));
        when(articles.findById(id)).thenReturn(Optional.of(projection));
    }

    @Test
    void previewIdentifiesOnlyPackagedUnchangedContentWithoutMutation() {
        projection.setReads(projection.getReads() + 5);
        var report = service.preview("admin");
        assertThat(report.eligibleIds()).containsExactly(id);
        assertThat(report.retiredIds()).isEmpty();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void retirementUsesExistingLifecycleWithActorAndStateFenceAndIsIdempotent() {
        when(lifecycle.expire(eq("admin"), eq(id), any())).thenAnswer(call -> {
            row.put("lifecycle_status", "expired");
            return null;
        });
        assertThat(service.retire("admin").retiredIds()).containsExactly(id);
        assertThat(service.retire("admin").retiredIds()).isEmpty();
        verify(lifecycle).expire("admin", id, new TransitionRequest(1, null, null, "DEMO_CONTENT_NOT_OPERATIONAL"));
        verify(articles, never()).save(any());
        verify(articles, never()).delete(any(Article.class));
    }

    @Test
    void editedCurrentProjectionIsPreservedEvenWhenOriginalImmutableSnapshotMatches() {
        projection.setTitle("Organization-specific replacement");
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void changedImmutableSnapshotIsPreservedEvenWhenProjectionStillMatches() throws Exception {
        ObjectNode original = (ObjectNode) mapper.readTree((String) row.get("snapshot"));
        original.put("snippet", "A reviewed organization-specific change");
        row.put("snapshot", original.toString());
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void sameContentWithHumanRevisionOrAdditionalDraftIsPreserved() {
        row.put("revision_actor", "human-editor");
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        row.put("revision_actor", "system:seed");
        row.put("revision_count", 2L);
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void manualPolicyOrReviewChangesArePreserved() {
        row.put("read_policy", "workspace");
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        row.put("read_policy", "public");
        row.put("review_count", 1L);
        assertThat(service.retire("admin").preservedIds()).containsExactly(id);
        verifyNoInteractions(lifecycle);
    }

    @Test
    void nonAdministratorIsRejectedBeforeReadingDemoContent() {
        when(access.contextFor("viewer")).thenReturn(new KnowledgeAccessContext("viewer", Map.of("shared", WorkspaceRole.VIEWER)));
        assertThatThrownBy(() -> service.retire("viewer")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, articles, lifecycle);
    }
}
