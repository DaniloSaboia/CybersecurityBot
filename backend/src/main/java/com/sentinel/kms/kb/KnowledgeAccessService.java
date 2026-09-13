package com.sentinel.kms.kb;

import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.ApprovalStatus;
import com.sentinel.kms.common.ArticlePolicy;
import com.sentinel.kms.common.Classification;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.common.KnowledgePolicy;
import com.sentinel.kms.common.LifecycleStatus;
import com.sentinel.kms.common.ReadPolicy;
import com.sentinel.kms.common.WorkspaceRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves workspace grants from the database and applies the exact same policy
 * facts for KB, IOC, and retrieval callers. JWT/user metadata never supplies a role.
 */
@Service
public class KnowledgeAccessService {

    private final JdbcTemplate jdbc;
    private final KnowledgeAuditService audit;

    public KnowledgeAccessService(JdbcTemplate jdbc, KnowledgeAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Builds a request context from active database memberships only. */
    public KnowledgeAccessContext contextFor(String subject) {
        String normalized = normalizeSubject(subject);
        if (normalized.isEmpty()) {
            return new KnowledgeAccessContext("", Map.of());
        }
        try {
            Map<String, WorkspaceRole> roles = new LinkedHashMap<>();
            jdbc.query("""
                            select workspace_id, role
                            from workspace_memberships
                            where subject = ? and active = true
                            """,
                    (rs, row) -> {
                        WorkspaceRole role = enumValue(WorkspaceRole.class, rs.getString("role"));
                        String workspaceId = rs.getString("workspace_id");
                        if (role != null && workspaceId != null && !workspaceId.isBlank()) {
                            roles.put(workspaceId, role);
                        }
                        return null;
                    }, normalized);
            return new KnowledgeAccessContext(normalized, roles);
        } catch (Exception e) {
            // A membership lookup failure can never become an implicit grant.
            audit.record(new KnowledgeAuditEvent(normalized, "MEMBERSHIP_RESOLUTION", "subject", normalized,
                    null, null, null, "failure", Map.of()));
            return new KnowledgeAccessContext(normalized, Map.of());
        }
    }

    /** Internal callers without a user identity may see public approved fixture data only. */
    public KnowledgeAccessContext publicFixtureContext() {
        return new KnowledgeAccessContext("system:public-fixture", Map.of());
    }

    public AccessDecision canReadArticle(String subject, String articleId) {
        return canReadArticle(contextFor(subject), articleId);
    }

    public AccessDecision canReadArticle(KnowledgeAccessContext context, String articleId) {
        ArticlePolicy policy = policyForArticle(articleId);
        AccessDecision decision = KnowledgePolicy.decide(context, policy);
        // article_id is a foreign key in the audit ledger.  An unknown or
        // policy-unresolvable requested ID is still useful as resourceId, but
        // must not make the best-effort denied audit row fail its FK insert.
        String provenArticleId = policy == null ? null : safe(articleId);
        audit.record(KnowledgeAuditEvent.of(subjectOf(context), "ARTICLE_READ", "article", safe(articleId),
                decision.workspaceId(), provenArticleId, null,
                decision.allowed() ? AuditOutcome.ALLOWED : AuditOutcome.DENIED, Map.of()));
        return decision;
    }

    /**
     * Authoritative upload/source gate for assistant retrieval and citations.
     * The triple is immutable server-derived provenance; metadata alone is never
     * accepted as an authorization assertion.  Missing, stale, deleted, or
     * mismatched rows deny access without revealing why to a caller.
     */
    public AccessDecision canReadSource(String subject, String sourceId,
                                        String sourceRevisionId, String sourceKey) {
        return canReadSource(contextFor(subject), sourceId, sourceRevisionId, sourceKey);
    }

    /** Context overload for server-resolved callers; use the subject overload at API boundaries. */
    public AccessDecision canReadSource(KnowledgeAccessContext context, String sourceId,
                                        String sourceRevisionId, String sourceKey) {
        String canonicalKey = canonicalSourceKey(sourceId, sourceRevisionId);
        if (canonicalKey == null || !canonicalKey.equals(sourceKey)) {
            return recordSourceDecision(context, sourceKey,
                    AccessDecision.deny("SOURCE_IDENTITY_MISMATCH", null));
        }
        try {
            List<SourcePolicyRow> rows = jdbc.query("""
                            select workspace_id, access_scope, classification, owner_sub
                            from ingest_jobs
                            where source_id = ? and source_revision_id = ? and source_key = ?
                              and source_key = source_id || ':' || source_revision_id
                              and deleted_at is null and cancelled_at is null and stage = 'indexed'
                              and public.can_read_ingest_source(?, ?, ?, ?)
                            """, (rs, row) -> new SourcePolicyRow(
                    rs.getString("workspace_id"), rs.getString("access_scope"),
                    enumValue(Classification.class, rs.getString("classification")), rs.getString("owner_sub")),
                    sourceId, sourceRevisionId, sourceKey,
                    sourceId, sourceRevisionId, sourceKey, subjectOf(context));
            AccessDecision decision = rows.isEmpty()
                    ? AccessDecision.deny("SOURCE_NOT_READABLE", null)
                    : decideSource(context, rows.get(0));
            return recordSourceDecision(context, sourceKey, decision);
        } catch (Exception e) {
            return recordSourceDecision(context, sourceKey, AccessDecision.deny("SOURCE_LOOKUP_FAILED", null));
        }
    }

    /**
     * Batch post-retrieval/title gate. Failure deliberately returns no IDs so callers
     * cannot use an unavailable policy lookup as an authorization bypass.
     */
    public Set<String> readableArticleIds(String subject, Collection<String> candidateIds) {
        return readableArticleIds(contextFor(subject), candidateIds);
    }

    public Set<String> readableArticleIds(KnowledgeAccessContext context, Collection<String> candidateIds) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (candidateIds != null) {
            candidateIds.stream().filter(id -> id != null && !id.isBlank()).forEach(requested::add);
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        try {
            String marks = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
            List<ArticlePolicyRow> rows = jdbc.query("""
                            select ag.article_id, ag.workspace_id, ag.classification, ag.approval_status,
                                   ag.read_policy, ag.lifecycle_status
                            from article_governance ag
                            join article_revisions ar on ar.id = ag.current_revision_id
                              and ar.article_id = ag.article_id
                              and ar.workspace_id = ag.workspace_id
                              and ar.classification = ag.classification
                            join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                              and ws.article_id = ar.article_id
                              and ws.workspace_id = ar.workspace_id
                              and ws.classification = ar.classification
                              and ws.approval_status = 'approved'
                              and ws.lifecycle_status = 'active'
                              and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                            where ag.article_id in (%s)
                              and (ag.expires_at is null or ag.expires_at > now())
                            """.formatted(marks),
                    (rs, row) -> new ArticlePolicyRow(rs.getString("article_id"), policyFrom(rs)),
                    requested.toArray());
            Map<String, ArticlePolicy> policies = new LinkedHashMap<>();
            for (ArticlePolicyRow row : rows) {
                policies.put(row.articleId(), row.policy());
            }
            LinkedHashSet<String> allowed = new LinkedHashSet<>();
            for (String articleId : requested) {
                AccessDecision decision = KnowledgePolicy.decide(context, policies.get(articleId));
                if (decision.allowed()) {
                    allowed.add(articleId);
                }
            }
            audit.record(new KnowledgeAuditEvent(subjectOf(context), "ARTICLE_BATCH_READ", "article-set",
                    "count:" + requested.size(), null, null, null, "allowed",
                    Map.of("requested", requested.size(), "allowed", allowed.size())));
            return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
        } catch (Exception e) {
            audit.record(new KnowledgeAuditEvent(subjectOf(context), "ARTICLE_BATCH_READ", "article-set",
                    "count:" + requested.size(), null, null, null, "failure", Map.of()));
            return Set.of();
        }
    }

    /** Enforces an editor/admin grant in the connector's persisted workspace. */
    public void requireConnectorMutation(String subject, String connectorId) {
        KnowledgeAccessContext context = contextFor(subject);
        String workspaceId = connectorWorkspace(connectorId);
        WorkspaceRole role = context.roleIn(workspaceId);
        boolean allowed = workspaceId != null && role != null && role.atLeast(WorkspaceRole.EDITOR);
        audit.record(new KnowledgeAuditEvent(subjectOf(context), "CONNECTOR_MUTATION", "connector",
                safe(connectorId), workspaceId, null, null, allowed ? "allowed" : "denied", Map.of()));
        if (!allowed) {
            throw new AccessDeniedException("connector mutation requires a trusted workspace editor or admin role");
        }
    }

    /** Enforces the same server-stored role for governed lifecycle/graph mutations. */
    public void requireWorkspaceEditor(String subject, String workspaceId) {
        KnowledgeAccessContext context = contextFor(subject);
        WorkspaceRole role = context.roleIn(workspaceId);
        if (role == null || !role.atLeast(WorkspaceRole.EDITOR)) {
            audit.record(new KnowledgeAuditEvent(subjectOf(context), "WORKSPACE_MUTATION", "workspace",
                    safe(workspaceId), workspaceId, null, null, "denied", Map.of()));
            throw new AccessDeniedException("workspace mutation requires a trusted editor or admin role");
        }
    }

    /**
     * Operational evaluation data is never a consequence of a JWT role or a
     * generic shared-workspace membership.  The 20260905070000 database
     * function resolves an explicit, live dataset_view grant plus membership.
     */
    public boolean canViewEvaluationDataset(String subject) {
        String normalized = normalizeSubject(subject);
        boolean allowed = false;
        if (!normalized.isEmpty()) {
            try {
                Boolean result = jdbc.queryForObject("select public.can_view_evaluation_dataset(?)",
                        Boolean.class, normalized);
                allowed = Boolean.TRUE.equals(result);
            } catch (Exception ignored) {
                // Function/schema lookup failure is deliberately a denial.
            }
        }
        audit.record(KnowledgeAuditEvent.of(normalized, "EVALUATION_DATASET_VIEW", "evaluation-dataset",
                "golden-set", null, null, null, allowed ? AuditOutcome.SUCCESS : AuditOutcome.DENIED, Map.of()));
        return allowed;
    }

    /** Requires explicit execution *and* dataset-view grants resolved by PostgreSQL. */
    public void requireEvaluationExecution(String subject) {
        String normalized = normalizeSubject(subject);
        boolean allowed = false;
        if (!normalized.isEmpty()) {
            try {
                Boolean result = jdbc.queryForObject("select public.can_execute_evaluation(?)", Boolean.class,
                        normalized);
                allowed = Boolean.TRUE.equals(result);
            } catch (Exception ignored) {
                // Fail closed: provider work must never start on a policy outage.
            }
        }
        audit.record(KnowledgeAuditEvent.of(normalized, "EVALUATION_EXECUTION", "evaluation", "retrieval",
                null, null, null, allowed ? AuditOutcome.SUCCESS : AuditOutcome.DENIED, Map.of()));
        if (!allowed) {
            throw new AccessDeniedException("evaluation execution requires explicit operational grants");
        }
    }

    private ArticlePolicy policyForArticle(String articleId) {
        if (articleId == null || articleId.isBlank()) {
            return null;
        }
        try {
            List<ArticlePolicy> policies = jdbc.query("""
                            select ag.workspace_id, ag.classification, ag.approval_status,
                                   ag.read_policy, ag.lifecycle_status
                            from article_governance ag
                            join article_revisions ar on ar.id = ag.current_revision_id
                              and ar.article_id = ag.article_id
                              and ar.workspace_id = ag.workspace_id
                              and ar.classification = ag.classification
                            join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                              and ws.article_id = ar.article_id
                              and ws.workspace_id = ar.workspace_id
                              and ws.classification = ar.classification
                              and ws.approval_status = 'approved'
                              and ws.lifecycle_status = 'active'
                              and (ws.expires_at is null or ws.expires_at > clock_timestamp())
                            where ag.article_id = ?
                              and (ag.expires_at is null or ag.expires_at > now())
                            """, (rs, row) -> policyFrom(rs), articleId);
            return policies.isEmpty() ? null : policies.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String connectorWorkspace(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            return null;
        }
        try {
            List<String> workspaces = jdbc.query("select workspace_id from connectors where id = ?",
                    (rs, row) -> rs.getString(1), connectorId);
            return workspaces.isEmpty() ? null : workspaces.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static ArticlePolicy policyFrom(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ArticlePolicy(rs.getString("workspace_id"),
                enumValue(Classification.class, rs.getString("classification")),
                enumValue(ApprovalStatus.class, rs.getString("approval_status")),
                enumValue(ReadPolicy.class, rs.getString("read_policy")),
                enumValue(LifecycleStatus.class, rs.getString("lifecycle_status")));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeSubject(String subject) {
        return subject == null ? "" : subject.trim();
    }

    private AccessDecision decideSource(KnowledgeAccessContext context, SourcePolicyRow source) {
        if (context == null || !context.authenticated() || source == null || source.classification() == null) {
            return AccessDecision.deny("SOURCE_POLICY_INVALID", null);
        }
        String scope = source.accessScope() == null ? "" : source.accessScope().trim().toLowerCase(java.util.Locale.ROOT);
        String subject = context.subject();
        return switch (scope) {
            case "owner_private" -> subject.equals(source.ownerSub())
                    ? sourceDecision(true, "SOURCE_OWNER", source)
                    : sourceDecision(false, "SOURCE_OWNER_REQUIRED", source);
            case "public_shared" -> source.classification() == Classification.PUBLIC
                    ? sourceDecision(true, "SOURCE_PUBLIC", source)
                    : sourceDecision(false, "SOURCE_POLICY_INVALID", source);
            case "workspace" -> workspaceSourceDecision(context, source);
            default -> sourceDecision(false, "SOURCE_POLICY_INVALID", source);
        };
    }

    private AccessDecision workspaceSourceDecision(KnowledgeAccessContext context, SourcePolicyRow source) {
        WorkspaceRole role = context.roleIn(source.workspaceId());
        if (role == null) {
            return sourceDecision(false, "SOURCE_MEMBERSHIP_REQUIRED", source);
        }
        boolean allowed = switch (source.classification()) {
            case PUBLIC, INTERNAL -> true;
            case CONFIDENTIAL -> role.atLeast(WorkspaceRole.EDITOR);
            case RESTRICTED -> role.atLeast(WorkspaceRole.ADMIN);
        };
        return sourceDecision(allowed, allowed ? "SOURCE_WORKSPACE" : "SOURCE_ROLE_REQUIRED", source);
    }

    private static AccessDecision sourceDecision(boolean allowed, String reason, SourcePolicyRow source) {
        return new AccessDecision(allowed, reason, source.workspaceId(), source.classification().name(), "INDEXED");
    }

    private AccessDecision recordSourceDecision(KnowledgeAccessContext context, String sourceKey,
                                                AccessDecision decision) {
        audit.record(new KnowledgeAuditEvent(subjectOf(context), "SOURCE_READ", "source", safe(sourceKey),
                decision.workspaceId(), null, null, decision.allowed() ? "allowed" : "denied", Map.of()));
        return decision;
    }

    private static String canonicalSourceKey(String sourceId, String sourceRevisionId) {
        if (sourceId == null || sourceId.isBlank() || sourceRevisionId == null || sourceRevisionId.isBlank()) {
            return null;
        }
        return sourceId + ':' + sourceRevisionId;
    }

    private static String subjectOf(KnowledgeAccessContext context) {
        return context == null ? "" : context.subject();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record ArticlePolicyRow(String articleId, ArticlePolicy policy) {
    }

    private record SourcePolicyRow(String workspaceId, String accessScope,
                                   Classification classification, String ownerSub) {
    }
}
