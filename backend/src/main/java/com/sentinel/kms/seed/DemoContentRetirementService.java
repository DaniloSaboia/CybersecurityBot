package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.kms.common.WorkspaceRole;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.KnowledgeAccessService;
import com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;
import com.sentinel.kms.kb.KnowledgeLifecycleService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An authenticated, bounded cleanup of unchanged packaged demonstration content. */
@Service
public class DemoContentRetirementService {
    private static final Set<String> ORIGINAL_ACTORS = Set.of("system:seed", "system:migration");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArticleRepository articles;
    private final KnowledgeAccessService access;
    private final KnowledgeLifecycleService lifecycle;

    public DemoContentRetirementService(JdbcTemplate jdbc, ObjectMapper mapper, ArticleRepository articles,
                                        KnowledgeAccessService access, KnowledgeLifecycleService lifecycle) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.articles = articles;
        this.access = access;
        this.lifecycle = lifecycle;
    }

    @Transactional(readOnly = true)
    public Report preview(String subject) {
        return inspect(subject, false);
    }

    @Transactional
    public Report retire(String subject) {
        return inspect(subject, true);
    }

    private Report inspect(String subject, boolean retire) {
        var role = access.contextFor(subject).roleIn("shared");
        if (role == null || !role.atLeast(WorkspaceRole.ADMIN)) {
            throw new AccessDeniedException("demo content maintenance requires a shared workspace administrator");
        }
        List<String> eligible = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        List<String> preserved = new ArrayList<>();
        for (JsonNode seed : seedArticles()) {
            String id = seed.path("id").asText();
            // Lock both policy and the legacy projection until lifecycle.expire
            // repeats the admin/state-version checks and commits its audit event.
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select ag.workspace_id, ag.classification, ag.read_policy, ag.approval_status,
                           ag.lifecycle_status, ag.state_version, ag.review_due_at, ag.expires_at,
                           ag.created_by_sub as governance_actor, ag.approved_by_sub,
                           ar.created_by_sub as revision_actor, ar.revision_number, ar.snapshot::text as snapshot,
                           (select count(*) from article_revisions history where history.article_id = ag.article_id) as revision_count,
                           (select count(*) from article_reviews reviews where reviews.article_revision_id = ar.id) as review_count
                    from article_governance ag
                    join articles a on a.id = ag.article_id
                    join article_revisions ar on ar.id = ag.current_revision_id and ar.article_id = ag.article_id
                    where ag.article_id = ?
                    """ + (retire ? " for update of ag, a" : ""), id);
            if (rows.isEmpty()) continue;
            Map<String, Object> row = rows.get(0);
            if (!"active".equals(row.get("lifecycle_status"))) continue;
            Article projection = articles.findById(id).orElse(null);
            if (!originalPolicy(row) || projection == null || !unchanged(seed, row, projection)) {
                preserved.add(id);
                continue;
            }
            if (retire) {
                lifecycle.expire(subject, id, new TransitionRequest(
                        ((Number) row.get("state_version")).longValue(), null, null, "DEMO_CONTENT_NOT_OPERATIONAL"));
                retired.add(id);
            } else {
                eligible.add(id);
            }
        }
        return new Report(List.copyOf(eligible), List.copyOf(retired), List.copyOf(preserved));
    }

    private boolean originalPolicy(Map<String, Object> row) {
        return "shared".equals(row.get("workspace_id")) && "public".equals(row.get("classification"))
                && "public".equals(row.get("read_policy")) && "approved".equals(row.get("approval_status"))
                && ORIGINAL_ACTORS.contains(String.valueOf(row.get("governance_actor")))
                && ORIGINAL_ACTORS.contains(String.valueOf(row.get("revision_actor")))
                && ORIGINAL_ACTORS.contains(String.valueOf(row.get("approved_by_sub")))
                && ((Number) row.get("revision_number")).intValue() == 1
                && ((Number) row.get("revision_count")).intValue() == 1
                && ((Number) row.get("review_count")).intValue() == 0
                && ((Number) row.get("state_version")).longValue() <= 1
                && row.get("review_due_at") == null && row.get("expires_at") == null;
    }

    private boolean unchanged(JsonNode seed, Map<String, Object> row, Article projection) {
        try {
            ObjectNode expected = seed.deepCopy();
            expected.set("categoryId", expected.remove("category"));
            expected.set("updatedOn", expected.remove("updated"));
            expected.remove("reads");
            if (!expected.has("cvss")) expected.putNull("cvss");
            JsonNode snapshot = mapper.readTree((String) row.get("snapshot"));
            if (!(snapshot instanceof ObjectNode original)) return false;
            original.remove("reads");
            ObjectNode current = mapper.valueToTree(projection);
            current.remove(List.of("reads", "createdAt", "updatedAt"));
            if (projection.getUpdatedOn() != null) current.put("updatedOn", projection.getUpdatedOn().toString());
            // Some application's JSON settings omit nulls. Normalize the one
            // optional authored field before comparing complete content trees.
            if (!current.has("cvss")) current.putNull("cvss");
            return expected.equals(original) && expected.equals(current);
        } catch (Exception invalidSnapshot) {
            return false;
        }
    }

    private JsonNode seedArticles() {
        try (var input = new ClassPathResource("seed/seed.json").getInputStream()) {
            return mapper.readTree(input).path("articles");
        } catch (IOException e) {
            throw new IllegalStateException("packaged demonstration manifest could not be read", e);
        }
    }

    public record Report(List<String> eligibleIds, List<String> retiredIds, List<String> preservedIds) {}
}
