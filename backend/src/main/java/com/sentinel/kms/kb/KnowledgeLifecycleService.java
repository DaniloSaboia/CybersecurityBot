package com.sentinel.kms.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.Classification;
import com.sentinel.kms.common.KnowledgeAccessContext;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.common.WorkspaceRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.ArticleSnapshotInput;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.CreateRevisionRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.EvidenceResponse;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.EvidenceSnapshotInput;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.LifecycleResponse;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.RevisionResponse;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.ReviewScheduleRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.RollbackRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.SupersedeRequest;
import static com.sentinel.kms.kb.KnowledgeLifecycleDtos.TransitionRequest;

/**
 * Writes the governed article lifecycle. Immutable revision/evidence rows are
 * never updated: mutable workflow projection rows carry transition state and
 * append-only event/review rows explain every change. All publication paths
 * update the legacy {@code articles} projection and enqueue a fenced reindex
 * item within one database transaction.
 */
@Service
public class KnowledgeLifecycleService {

    // Audit metadata deliberately accepts a compact, enumerable reason code
    // rather than free text.  This mirrors the audit ledger's scalar
    // whitelist and keeps client supplied comments out of the event stream.
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeAccessService access;
    private final KnowledgeAuditService audit;

    public KnowledgeLifecycleService(JdbcTemplate jdbc, ObjectMapper mapper,
                                     KnowledgeAccessService access, KnowledgeAuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
        this.audit = audit;
    }

    /** Creates an immutable proposal without changing the currently readable revision. */
    @Transactional
    public LifecycleResponse createDraft(String subject, String articleId, CreateRevisionRequest request) {
        CreateRevisionRequest checked = requireDraftRequest(request);
        Governance governance = lockGovernance(articleId);
        requireEditor(subject, governance);
        assertExpected(subject, governance, checked.expectedStateVersion(), "LIFECYCLE_DRAFT_CREATED", null);
        validateSnapshot(checked.snapshot());
        validateEvidence(checked.evidence());
        validateDates(checked.reviewDueAt(), checked.expiresAt(), false);
        validateCategory(checked.snapshot().categoryId());

        int revisionNumber = nextRevisionNumber(governance.articleId());
        String revisionId = UUID.randomUUID().toString();
        String snapshot = snapshot(governance.articleId(), checked.snapshot());
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, supersedes_revision_id, created_by_sub)
                        values (?, ?, ?, ?, ?, 'draft', cast(? as jsonb), ?, ?, ?)
                        """, revisionId, governance.articleId(), revisionNumber, governance.workspaceId(),
                governance.classification(), snapshot, sha256(snapshot), governance.currentRevisionId(), subject);
        insertEvidence(revisionId, checked.evidence(), subject);
        insertWorkflowState(revisionId, governance, "draft", "active", checked.reviewDueAt(), checked.expiresAt(),
                null, null);
        insertLifecycleEvent(revisionId, governance, "draft_created", null, "draft", null, "active",
                -1, 0, subject, null);
        long newGovernanceVersion = incrementGovernanceVersion(governance, subject, editorGrant(governance));
        RevisionState state = workflowState(revisionId, governance.articleId(), false);
        audit(subject, "LIFECYCLE_DRAFT_CREATED", "article_revision", revisionId, governance, revisionId,
                AuditOutcome.SUCCESS, versionMetadata(governance.stateVersion(), newGovernanceVersion, null, null,
                        checked.reviewDueAt(), checked.expiresAt(), null));
        return response(governance.withStateVersion(newGovernanceVersion), state);
    }

    /** Draft -> pending review. The proposal remains invisible to all read paths. */
    @Transactional
    public LifecycleResponse submit(String subject, String articleId, String revisionId, TransitionRequest request) {
        TransitionRequest checked = requireTransitionRequest(request);
        Governance governance = lockGovernance(articleId);
        requireEditor(subject, governance);
        assertExpected(subject, governance, checked.expectedStateVersion(), "LIFECYCLE_REVIEW_SUBMITTED", revisionId);
        RevisionState state = lockWorkflow(revisionId, governance);
        requireState(state, "draft", "active");
        OffsetDateTime reviewDue = choose(checked.reviewDueAt(), state.reviewDueAt());
        OffsetDateTime expires = choose(checked.expiresAt(), state.expiresAt());
        validateDates(reviewDue, expires, false);
        RevisionState pending = updateWorkflow(revisionId, state, "pending_review", "active", reviewDue, expires,
                null, null);
        String reason = reasonCode(checked.reasonCode());
        insertReview(revisionId, "submitted", subject, reason);
        insertLifecycleEvent(revisionId, governance, "submitted", state.approvalStatus(), pending.approvalStatus(),
                state.lifecycleStatus(), pending.lifecycleStatus(), state.stateVersion(), pending.stateVersion(),
                subject, reason);
        long version = incrementGovernanceVersion(governance, subject, editorGrant(governance));
        audit(subject, "LIFECYCLE_REVIEW_SUBMITTED", "article_revision", revisionId, governance, revisionId,
                AuditOutcome.SUCCESS, versionMetadata(governance.stateVersion(), version, null, null,
                        reviewDue, expires, reason));
        return response(governance.withStateVersion(version), pending);
    }

    /** Admin-only approval atomically publishes a pending immutable revision. */
    @Transactional
    public LifecycleResponse approve(String subject, String articleId, String revisionId, TransitionRequest request) {
        TransitionRequest checked = requireTransitionRequest(request);
        Governance governance = lockGovernance(articleId);
        requireAdmin(subject, governance);
        assertExpected(subject, governance, checked.expectedStateVersion(), "LIFECYCLE_REVISION_APPROVED", revisionId);
        RevisionState candidate = lockWorkflow(revisionId, governance);
        requireState(candidate, "pending_review", "active");
        assertNotSelfApproval(subject, candidate);
        return publish(subject, governance, candidate, checked.reviewDueAt(), checked.expiresAt(),
                reasonCode(checked.reasonCode()), "LIFECYCLE_REVISION_APPROVED", "approved", "approved");
    }

    /** Admin-only rejection leaves a previously published revision readable. */
    @Transactional
    public LifecycleResponse reject(String subject, String articleId, String revisionId, TransitionRequest request) {
        TransitionRequest checked = requireTransitionRequest(request);
        Governance governance = lockGovernance(articleId);
        requireAdmin(subject, governance);
        assertExpected(subject, governance, checked.expectedStateVersion(), "LIFECYCLE_REVISION_REJECTED", revisionId);
        RevisionState state = lockWorkflow(revisionId, governance);
        if (!("draft".equals(state.approvalStatus()) || "pending_review".equals(state.approvalStatus()))) {
            conflict("revision is not reviewable", subject, governance, revisionId);
        }
        String reason = reasonCode(checked.reasonCode());
        RevisionState rejected = updateWorkflow(revisionId, state, "rejected", "archived", state.reviewDueAt(),
                state.expiresAt(), null, null);
        insertReview(revisionId, "rejected", subject, reason);
        insertLifecycleEvent(revisionId, governance, "rejected", state.approvalStatus(), rejected.approvalStatus(),
                state.lifecycleStatus(), rejected.lifecycleStatus(), state.stateVersion(), rejected.stateVersion(),
                subject, reason);
        long version = incrementGovernanceVersion(governance, subject, MutationGrant.ADMIN);
        audit(subject, "LIFECYCLE_REVISION_REJECTED", "article_revision", revisionId, governance, revisionId,
                AuditOutcome.SUCCESS, versionMetadata(governance.stateVersion(), version, null, null,
                        rejected.reviewDueAt(), rejected.expiresAt(), reason));
        return response(governance.withStateVersion(version), rejected);
    }

    /**
     * Explicit supersession publishes a pending successor and marks the given
     * current revision superseded in the same transaction.
     */
    @Transactional
    public LifecycleResponse supersede(String subject, String articleId, String revisionId, SupersedeRequest request) {
        if (request == null || blank(request.successorRevisionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "successorRevisionId is required");
        }
        Governance governance = lockGovernance(articleId);
        requireAdmin(subject, governance);
        assertExpected(subject, governance, request.expectedStateVersion(), "LIFECYCLE_REVISION_SUPERSEDED", revisionId);
        if (!revisionId.equals(governance.currentRevisionId())) {
            // At this point the caller-supplied old revision has not been
            // resolved through the immutable workflow binding. Keep it only
            // as an audit resource target, not in the FK-backed revision
            // column (which would otherwise make the terminal audit vanish).
            conflictForUnprovenRevision("only the current revision can be superseded", subject, governance, revisionId);
        }
        RevisionState successor = lockWorkflow(request.successorRevisionId(), governance);
        requireState(successor, "pending_review", "active");
        assertNotSelfApproval(subject, successor);
        return publish(subject, governance, successor, request.reviewDueAt(), request.expiresAt(),
                reasonCode(request.reasonCode()), "LIFECYCLE_REVISION_SUPERSEDED", "approved", "approved");
    }

    /**
     * A rollback creates a fresh monotonic revision and fresh evidence IDs from
     * a historic immutable source; it never repoints a historic revision.
     */
    @Transactional
    public LifecycleResponse rollback(String subject, String articleId, String targetRevisionId, RollbackRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rollback request is required");
        }
        if (!blank(request.targetRevisionId()) && !request.targetRevisionId().equals(targetRevisionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rollback target mismatch");
        }
        Governance governance = lockGovernance(articleId);
        requireAdmin(subject, governance);
        assertExpected(subject, governance, request.expectedStateVersion(), "LIFECYCLE_REVISION_ROLLED_BACK", targetRevisionId);
        HistoricRevision target = historicRevision(targetRevisionId, governance);
        if (!target.wasPublished()) {
            conflict("rollback target was never approved", subject, governance, targetRevisionId);
        }
        OffsetDateTime reviewDue = choose(request.reviewDueAt(), target.reviewDueAt());
        OffsetDateTime expires = choose(request.expiresAt(), target.expiresAt());
        validateDates(reviewDue, expires, true);

        int number = nextRevisionNumber(governance.articleId());
        String revisionId = UUID.randomUUID().toString();
        jdbc.update("""
                        insert into article_revisions
                            (id, article_id, revision_number, workspace_id, classification, status,
                             snapshot, content_sha256, supersedes_revision_id, rollback_of_revision_id, created_by_sub)
                        values (?, ?, ?, ?, ?, 'rolled_back', cast(? as jsonb), ?, ?, ?, ?)
                        """, revisionId, governance.articleId(), number, governance.workspaceId(),
                governance.classification(), target.snapshot(), target.contentSha256(), governance.currentRevisionId(),
                targetRevisionId, subject);
        copyEvidence(targetRevisionId, revisionId, subject);
        insertWorkflowState(revisionId, governance, "approved", "active", reviewDue, expires, subject,
                OffsetDateTime.now());
        insertLifecycleEvent(revisionId, governance, "rollback_created", null, "approved", null, "active",
                -1, 0, subject, reasonCode(request.reasonCode()));
        RevisionState candidate = workflowState(revisionId, governance.articleId(), true);
        return publish(subject, governance, candidate, reviewDue, expires,
                reasonCode(request.reasonCode()), "LIFECYCLE_REVISION_ROLLED_BACK", "rolled_back", "rolled_back");
    }

    /** Expiry keeps history intact but makes direct, IOC, vector, and evidence reads fail closed. */
    @Transactional
    public LifecycleResponse expire(String subject, String articleId, TransitionRequest request) {
        TransitionRequest checked = requireTransitionRequest(request);
        Governance governance = lockGovernance(articleId);
        requireAdmin(subject, governance);
        assertExpected(subject, governance, checked.expectedStateVersion(), "LIFECYCLE_ARTICLE_EXPIRED",
                governance.currentRevisionId());
        if (blank(governance.currentRevisionId())) {
            conflict("article has no published revision", subject, governance, null);
        }
        RevisionState current = lockWorkflow(governance.currentRevisionId(), governance);
        requireState(current, "approved", "active");
        OffsetDateTime expires = choose(checked.expiresAt(), OffsetDateTime.now());
        if (expires.isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiry must be immediate or in the past");
        }
        RevisionState expired = updateWorkflow(current.revisionId(), current, "approved", "expired",
                current.reviewDueAt(), expires, current.approvedBySub(), current.approvedAt());
        insertLifecycleEvent(current.revisionId(), governance, "expired", current.approvalStatus(),
                expired.approvalStatus(), current.lifecycleStatus(), expired.lifecycleStatus(), current.stateVersion(),
                expired.stateVersion(), subject, reasonCode(checked.reasonCode()));
        // Retain the immutable current pointer for history and rollback.  All
        // read paths join the workflow projection, so its expired state still
        // fails closed without erasing the authoritative revision identity.
        long version = mirrorGovernance(governance, expired, subject, MutationGrant.ADMIN);
        audit(subject, "LIFECYCLE_ARTICLE_EXPIRED", "article", governance.articleId(), governance,
                current.revisionId(), AuditOutcome.SUCCESS,
                versionMetadata(governance.stateVersion(), version, current.revisionId(), current.revisionId(),
                        expired.reviewDueAt(), expired.expiresAt(), reasonCode(checked.reasonCode())));
        return response(governance.withStateVersion(version)
                .withPolicy("approved", "expired", expired.reviewDueAt(), expired.expiresAt(),
                        expired.approvedBySub(), expired.approvedAt()), expired);
    }

    /** Review scheduling is an auditable state transition, not an authorization grant. */
    @Transactional
    public LifecycleResponse updateReviewSchedule(String subject, String articleId, ReviewScheduleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review schedule request is required");
        }
        Governance governance = lockGovernance(articleId);
        requireEditor(subject, governance);
        assertExpected(subject, governance, request.expectedStateVersion(), "LIFECYCLE_REVIEW_SUBMITTED",
                governance.currentRevisionId());
        if (blank(governance.currentRevisionId())) {
            conflict("article has no published revision", subject, governance, null);
        }
        RevisionState current = lockWorkflow(governance.currentRevisionId(), governance);
        validateDates(request.reviewDueAt(), current.expiresAt(), false);
        RevisionState scheduled = updateWorkflow(current.revisionId(), current, current.approvalStatus(),
                current.lifecycleStatus(), request.reviewDueAt(), current.expiresAt(), current.approvedBySub(),
                current.approvedAt());
        insertLifecycleEvent(current.revisionId(), governance, "review_due_changed", current.approvalStatus(),
                scheduled.approvalStatus(), current.lifecycleStatus(), scheduled.lifecycleStatus(),
                current.stateVersion(), scheduled.stateVersion(), subject, null);
        long version = mirrorGovernance(governance, scheduled, subject, editorGrant(governance));
        audit(subject, "LIFECYCLE_REVIEW_SUBMITTED", "article", governance.articleId(), governance,
                current.revisionId(), AuditOutcome.SUCCESS,
                versionMetadata(governance.stateVersion(), version, null, null,
                        scheduled.reviewDueAt(), scheduled.expiresAt(), null));
        return response(governance.withStateVersion(version).withPolicy(scheduled.approvalStatus(),
                scheduled.lifecycleStatus(), scheduled.reviewDueAt(), scheduled.expiresAt(),
                scheduled.approvedBySub(), scheduled.approvedAt()), scheduled);
    }

    /** Editor-only lifecycle metadata; immutable evidence content belongs to the dedicated evidence resolver/viewer. */
    public LifecycleResponse lifecycle(String subject, String articleId) {
        Governance governance = governance(articleId);
        requireEditor(subject, governance);
        RevisionState current = blank(governance.currentRevisionId()) ? null
                : workflowState(governance.currentRevisionId(), governance.articleId(), false);
        return response(governance, current);
    }

    private LifecycleResponse publish(String subject, Governance governance, RevisionState candidate,
                                      OffsetDateTime requestedReviewDue, OffsetDateTime requestedExpires,
                                      String reason, String auditAction, String reviewDecision, String eventType) {
        // A proposal is immutable evidence of the publication it was based on.
        // A fresh article-level CAS token alone is insufficient: approving an
        // old proposal after another revision became current would make its
        // immutable supersedes pointer lie about the lineage it displaced.
        if (!Objects.equals(candidate.supersedesRevisionId(), governance.currentRevisionId())) {
            conflict("revision was based on a stale current revision", subject, governance, candidate.revisionId());
        }
        OffsetDateTime reviewDue = choose(requestedReviewDue, candidate.reviewDueAt());
        OffsetDateTime expires = choose(requestedExpires, candidate.expiresAt());
        validateDates(reviewDue, expires, true);
        RevisionState approved = updateWorkflow(candidate.revisionId(), candidate, "approved", "active", reviewDue,
                expires, subject, OffsetDateTime.now());
        insertLifecycleEvent(candidate.revisionId(), governance, eventType, candidate.approvalStatus(),
                approved.approvalStatus(), candidate.lifecycleStatus(), approved.lifecycleStatus(),
                candidate.stateVersion(), approved.stateVersion(), subject, reason);
        insertReview(candidate.revisionId(), reviewDecision, subject, reason);

        String previousId = governance.currentRevisionId();
        if (!blank(previousId) && !previousId.equals(candidate.revisionId())) {
            RevisionState previous = lockWorkflow(previousId, governance);
            if ("approved".equals(previous.approvalStatus()) && "active".equals(previous.lifecycleStatus())) {
                RevisionState superseded = updateWorkflow(previous.revisionId(), previous, "superseded", "superseded",
                        previous.reviewDueAt(), previous.expiresAt(), previous.approvedBySub(), previous.approvedAt());
                insertLifecycleEvent(previous.revisionId(), governance, "superseded", previous.approvalStatus(),
                        superseded.approvalStatus(), previous.lifecycleStatus(), superseded.lifecycleStatus(),
                        previous.stateVersion(), superseded.stateVersion(), subject, reason);
                insertReview(previous.revisionId(), "superseded", subject, reason);
            }
        }
        long version = publishGovernance(governance, approved, subject, MutationGrant.ADMIN);
        materializeArticleProjection(governance.articleId(), candidate.revisionId());
        enqueueReindex(governance, candidate.revisionId(), version);
        Governance published = governance.withStateVersion(version).withPolicy("approved", "active", reviewDue,
                expires, approved.approvedBySub(), approved.approvedAt()).withCurrentRevision(candidate.revisionId());
        audit(subject, auditAction, "article_revision", candidate.revisionId(), governance, candidate.revisionId(),
                AuditOutcome.SUCCESS, versionMetadata(governance.stateVersion(), version, previousId,
                        candidate.revisionId(), reviewDue, expires, reason));
        return response(published, approved);
    }

    private Governance lockGovernance(String articleId) {
        if (blank(articleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "article id is required");
        }
        List<Governance> rows = jdbc.query("""
                        select article_id, workspace_id, classification, read_policy, approval_status,
                               lifecycle_status, current_revision_id, state_version, review_due_at, expires_at,
                               approved_by_sub, approved_at
                        from article_governance
                        where article_id = ?
                        for update
                        """, (rs, row) -> Governance.from(rs), articleId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "article not found");
        }
        return rows.get(0);
    }

    private Governance governance(String articleId) {
        if (blank(articleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "article id is required");
        }
        List<Governance> rows = jdbc.query("""
                        select article_id, workspace_id, classification, read_policy, approval_status,
                               lifecycle_status, current_revision_id, state_version, review_due_at, expires_at,
                               approved_by_sub, approved_at
                        from article_governance where article_id = ?
                        """, (rs, row) -> Governance.from(rs), articleId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "article not found");
        }
        return rows.get(0);
    }

    private RevisionState lockWorkflow(String revisionId, Governance governance) {
        RevisionState state = workflowState(revisionId, governance.articleId(), true);
        if (state == null || !governance.workspaceId().equals(state.workspaceId())
                || !governance.classification().equals(state.classification())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "revision not found");
        }
        return state;
    }

    private RevisionState workflowState(String revisionId, String articleId, boolean forUpdate) {
        if (blank(revisionId)) {
            return null;
        }
        String lock = forUpdate ? " for update" : "";
        List<RevisionState> rows = jdbc.query(("""
                        select ws.article_revision_id, ws.article_id, ws.workspace_id, ws.classification,
                               ws.approval_status, ws.lifecycle_status, ws.review_due_at, ws.expires_at,
                               ws.approved_by_sub, ws.approved_at, ws.state_version, ar.created_by_sub,
                               ar.revision_number, ar.supersedes_revision_id, ar.rollback_of_revision_id
                        from article_revision_workflow_states ws
                        join article_revisions ar on ar.id = ws.article_revision_id
                        where ws.article_revision_id = ? and ws.article_id = ?
                        """ + lock), (rs, row) -> RevisionState.from(rs), revisionId, articleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private HistoricRevision historicRevision(String revisionId, Governance governance) {
        List<HistoricRevision> rows = jdbc.query("""
                        select ar.id, ar.snapshot::text as snapshot, ar.content_sha256,
                               ws.approval_status, ws.lifecycle_status, ws.review_due_at, ws.expires_at,
                               exists (select 1 from article_revision_lifecycle_events e
                                       where e.article_revision_id = ar.id
                                         and e.to_approval_status = 'approved'
                                         and e.event_type in ('migration_baseline', 'approved',
                                                              'rollback_created', 'rolled_back')) as was_published
                        from article_revisions ar
                        join article_revision_workflow_states ws on ws.article_revision_id = ar.id
                          and ws.article_id = ar.article_id
                          and ws.workspace_id = ar.workspace_id
                          and ws.classification = ar.classification
                        where ar.id = ? and ar.article_id = ? and ar.workspace_id = ? and ar.classification = ?
                        for update
                        """, (rs, row) -> HistoricRevision.from(rs), revisionId, governance.articleId(),
                governance.workspaceId(), governance.classification());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "rollback target not found");
        }
        return rows.get(0);
    }

    private int nextRevisionNumber(String articleId) {
        Integer number = jdbc.queryForObject("""
                        select coalesce(max(revision_number), 0) + 1
                        from article_revisions where article_id = ?
                        """, Integer.class, articleId);
        return number == null ? 1 : number;
    }

    private void insertEvidence(String revisionId, List<EvidenceSnapshotInput> evidence, String subject) {
        int ordinal = 0;
        for (EvidenceSnapshotInput item : evidence) {
            String content = item.content().trim();
            jdbc.update("""
                            insert into article_evidence_snapshots
                                (id, article_revision_id, ordinal, source_uri, source_label, source_version,
                                 content, content_sha256, created_by_sub)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID().toString(), revisionId, ordinal++, item.sourceUri(),
                    item.sourceLabel().trim(), item.sourceVersion(), content, sha256(content), subject);
        }
    }

    private void copyEvidence(String sourceRevisionId, String targetRevisionId, String subject) {
        List<EvidenceCopy> source = jdbc.query("""
                        select ordinal, source_uri, source_label, source_version, content, content_sha256
                        from article_evidence_snapshots
                        where article_revision_id = ? order by ordinal asc
                        """, (rs, row) -> EvidenceCopy.from(rs), sourceRevisionId);
        if (source.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rollback target has no evidence");
        }
        for (EvidenceCopy evidence : source) {
            jdbc.update("""
                            insert into article_evidence_snapshots
                                (id, article_revision_id, ordinal, source_uri, source_label, source_version,
                                 content, content_sha256, created_by_sub)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID().toString(), targetRevisionId, evidence.ordinal(),
                    evidence.sourceUri(), evidence.sourceLabel(), evidence.sourceVersion(), evidence.content(),
                    evidence.contentSha256(), subject);
        }
    }

    private void insertWorkflowState(String revisionId, Governance governance, String approval, String lifecycle,
                                     OffsetDateTime reviewDue, OffsetDateTime expires, String approver,
                                     OffsetDateTime approvedAt) {
        jdbc.update("""
                        insert into article_revision_workflow_states
                            (article_revision_id, article_id, workspace_id, classification, approval_status,
                             lifecycle_status, review_due_at, expires_at, approved_by_sub, approved_at, state_version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """, revisionId, governance.articleId(), governance.workspaceId(), governance.classification(),
                approval, lifecycle, reviewDue, expires, approver, approvedAt);
    }

    private RevisionState updateWorkflow(String revisionId, RevisionState current, String approval, String lifecycle,
                                         OffsetDateTime reviewDue, OffsetDateTime expires, String approver,
                                         OffsetDateTime approvedAt) {
        int updated = jdbc.update("""
                        update article_revision_workflow_states
                        set approval_status = ?, lifecycle_status = ?, review_due_at = ?, expires_at = ?,
                            approved_by_sub = ?, approved_at = ?, state_version = state_version + 1
                        where article_revision_id = ? and state_version = ?
                        """, approval, lifecycle, reviewDue, expires, approver, approvedAt, revisionId,
                current.stateVersion());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision state changed concurrently");
        }
        RevisionState state = workflowState(revisionId, current.articleId(), true);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision state changed concurrently");
        }
        return state;
    }

    private void insertLifecycleEvent(String revisionId, Governance governance, String type,
                                      String fromApproval, String toApproval, String fromLifecycle, String toLifecycle,
                                      long expectedStateVersion, long resultingStateVersion, String actor, String reason) {
        Map<String, Object> metadata = reason == null ? Map.of() : Map.of("reason_code", reason);
        jdbc.update("""
                        insert into article_revision_lifecycle_events
                            (id, article_revision_id, article_id, workspace_id, classification, event_type,
                             from_approval_status, to_approval_status, from_lifecycle_status, to_lifecycle_status,
                             expected_state_version, resulting_state_version, governance_state_version,
                             actor_sub, comment, metadata)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                        """, UUID.randomUUID().toString(), revisionId, governance.articleId(), governance.workspaceId(),
                governance.classification(), type, fromApproval, toApproval, fromLifecycle, toLifecycle,
                expectedStateVersion, resultingStateVersion, governance.stateVersion() + 1,
                actor, reason, json(metadata));
    }

    private void insertReview(String revisionId, String decision, String reviewer, String reason) {
        jdbc.update("""
                        insert into article_reviews (id, article_revision_id, decision, reviewer_sub, comment)
                        values (?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), revisionId, decision, reviewer, reason);
    }

    private long incrementGovernanceVersion(Governance governance, String subject, MutationGrant grant) {
        int updated = jdbc.update("""
                        update article_governance set state_version = state_version + 1
                        where article_id = ? and state_version = ?
                          and exists (
                              select 1 from workspace_memberships wm
                              where wm.workspace_id = article_governance.workspace_id
                                and wm.subject = ? and wm.active = true
                                and %s)
                        """.formatted(grant.sqlRolePredicate()), governance.articleId(), governance.stateVersion(),
                normalizeSubject(subject));
        if (updated != 1) {
            finalMutationRejectedOrConflicted(subject, governance, grant);
        }
        return governance.stateVersion() + 1;
    }

    private long publishGovernance(Governance governance, RevisionState state, String subject, MutationGrant grant) {
        int updated = jdbc.update("""
                        update article_governance ag
                        set current_revision_id = ws.article_revision_id,
                            approval_status = ws.approval_status,
                            lifecycle_status = ws.lifecycle_status,
                            review_due_at = ws.review_due_at,
                            expires_at = ws.expires_at,
                            approved_by_sub = ws.approved_by_sub,
                            approved_at = ws.approved_at,
                            state_version = ag.state_version + 1
                        from article_revision_workflow_states ws
                        where ag.article_id = ? and ag.state_version = ?
                          and ws.article_revision_id = ? and ws.article_id = ag.article_id
                          and ws.workspace_id = ag.workspace_id and ws.classification = ag.classification
                          and exists (
                              select 1 from workspace_memberships wm
                              where wm.workspace_id = ag.workspace_id
                                and wm.subject = ? and wm.active = true
                                and %s)
                        """.formatted(grant.sqlRolePredicate()), governance.articleId(), governance.stateVersion(),
                state.revisionId(), normalizeSubject(subject));
        if (updated != 1) {
            finalMutationRejectedOrConflicted(subject, governance, grant);
        }
        return governance.stateVersion() + 1;
    }

    private long mirrorGovernance(Governance governance, RevisionState state, String subject, MutationGrant grant) {
        int updated = jdbc.update("""
                        update article_governance ag
                        set approval_status = ws.approval_status,
                            lifecycle_status = ws.lifecycle_status,
                            review_due_at = ws.review_due_at,
                            expires_at = ws.expires_at,
                            approved_by_sub = ws.approved_by_sub,
                            approved_at = ws.approved_at,
                            state_version = ag.state_version + 1
                        from article_revision_workflow_states ws
                        where ag.article_id = ? and ag.current_revision_id = ws.article_revision_id
                          and ag.state_version = ? and ws.article_revision_id = ?
                          and ws.article_id = ag.article_id and ws.workspace_id = ag.workspace_id
                          and ws.classification = ag.classification
                          and exists (
                              select 1 from workspace_memberships wm
                              where wm.workspace_id = ag.workspace_id
                                and wm.subject = ? and wm.active = true
                                and %s)
                        """.formatted(grant.sqlRolePredicate()), governance.articleId(), governance.stateVersion(),
                state.revisionId(), normalizeSubject(subject));
        if (updated != 1) {
            finalMutationRejectedOrConflicted(subject, governance, grant);
        }
        return governance.stateVersion() + 1;
    }

    private void materializeArticleProjection(String articleId, String revisionId) {
        int updated = jdbc.update("""
                        update articles a
                        set title = ar.snapshot->>'title',
                            category_id = nullif(ar.snapshot->>'categoryId', ''),
                            severity = ar.snapshot->>'severity',
                            tags = array(select jsonb_array_elements_text(
                                coalesce(ar.snapshot->'tags', '[]'::jsonb))),
                            source = ar.snapshot->>'source',
                            updated_on = nullif(ar.snapshot->>'updatedOn', '')::date,
                            author = ar.snapshot->>'author',
                            confidence = ar.snapshot->>'confidence',
                            reads = coalesce(nullif(ar.snapshot->>'reads', '')::integer, 0),
                            cvss = ar.snapshot->>'cvss',
                            snippet = ar.snapshot->>'snippet',
                            body = coalesce(ar.snapshot->'body', '[]'::jsonb),
                            iocs = coalesce(ar.snapshot->'iocs', '[]'::jsonb),
                            related = array(select jsonb_array_elements_text(
                                coalesce(ar.snapshot->'related', '[]'::jsonb))),
                            updated_at = now()
                        from article_revisions ar
                        where a.id = ? and ar.id = ? and ar.article_id = a.id
                        """, articleId, revisionId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "approved article projection could not be published");
        }
    }

    private void enqueueReindex(Governance governance, String revisionId, long governanceVersion) {
        jdbc.update("""
                        insert into article_revision_reindex_outbox
                            (id, article_id, article_revision_id, workspace_id, classification, source_key,
                             governance_state_version)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (article_id, governance_state_version) do nothing
                        """, UUID.randomUUID().toString(), governance.articleId(), revisionId, governance.workspaceId(),
                governance.classification(), "article:" + governance.articleId(), governanceVersion);
    }

    private RevisionResponse revisionResponse(RevisionState state) {
        if (state == null) {
            return null;
        }
        List<EvidenceResponse> evidence = jdbc.query("""
                        select id, ordinal, source_uri, source_label, source_version, char_length(content) as length
                        from article_evidence_snapshots where article_revision_id = ? order by ordinal asc
                        """, (rs, row) -> new EvidenceResponse(rs.getString("id"), rs.getInt("ordinal"),
                rs.getString("source_uri"), rs.getString("source_label"), rs.getString("source_version"),
                rs.getInt("length")), state.revisionId());
        return new RevisionResponse(state.revisionId(), state.articleId(), state.revisionNumber(),
                state.approvalStatus(), state.lifecycleStatus(), state.stateVersion(), state.supersedesRevisionId(),
                state.rollbackOfRevisionId(), state.reviewDueAt(), state.expiresAt(), List.copyOf(evidence));
    }

    private LifecycleResponse response(Governance governance, RevisionState state) {
        return new LifecycleResponse(governance.articleId(), governance.workspaceId(), governance.classification(),
                governance.readPolicy(), governance.approvalStatus(), governance.lifecycleStatus(),
                governance.currentRevisionId(), governance.stateVersion(), governance.reviewDueAt(), governance.expiresAt(),
                revisionResponse(state));
    }

    private void requireEditor(String subject, Governance governance) {
        KnowledgeAccessContext context = access.contextFor(subject);
        WorkspaceRole role = context.roleIn(governance.workspaceId());
        boolean allowed = role != null && role.atLeast(WorkspaceRole.EDITOR)
                && (!"restricted".equals(governance.classification()) || role.atLeast(WorkspaceRole.ADMIN));
        if (!allowed) {
            audit(subject, "LIFECYCLE_MUTATION_DENIED", "article", governance.articleId(), governance, null,
                    AuditOutcome.DENIED, Map.of());
            throw new AccessDeniedException("lifecycle draft requires a trusted workspace editor");
        }
    }

    private void requireAdmin(String subject, Governance governance) {
        KnowledgeAccessContext context = access.contextFor(subject);
        WorkspaceRole role = context.roleIn(governance.workspaceId());
        if (role == null || !role.atLeast(WorkspaceRole.ADMIN)) {
            audit(subject, "LIFECYCLE_MUTATION_DENIED", "article", governance.articleId(), governance, null,
                    AuditOutcome.DENIED, Map.of());
            throw new AccessDeniedException("lifecycle approval requires a trusted workspace administrator");
        }
    }

    /**
     * The preflight role lookup gives callers a useful 403 before any work,
     * but a membership can be revoked while immutable draft/state rows are
     * being assembled.  The final governance write repeats this exact
     * database-owned role predicate in its WHERE clause.
     */
    private static MutationGrant editorGrant(Governance governance) {
        return "restricted".equals(governance.classification())
                ? MutationGrant.ADMIN : MutationGrant.EDITOR;
    }

    private void finalMutationRejectedOrConflicted(String subject, Governance governance, MutationGrant grant) {
        if (!hasFinalMutationGrant(subject, governance, grant)) {
            audit(subject, "LIFECYCLE_MUTATION_DENIED", "article", governance.articleId(), governance, null,
                    AuditOutcome.DENIED, Map.of());
            throw new AccessDeniedException("lifecycle mutation authorization changed before commit");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "article lifecycle changed concurrently");
    }

    private boolean hasFinalMutationGrant(String subject, Governance governance, MutationGrant grant) {
        String normalized = normalizeSubject(subject);
        if (normalized.isEmpty()) {
            return false;
        }
        try {
            Boolean allowed = jdbc.queryForObject("""
                            select exists (
                                select 1 from workspace_memberships wm
                                where wm.workspace_id = ? and wm.subject = ? and wm.active = true
                                  and %s)
                            """.formatted(grant.sqlRolePredicate()), Boolean.class,
                    governance.workspaceId(), normalized);
            return Boolean.TRUE.equals(allowed);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void assertExpected(String subject, Governance governance, long expected, String action, String revisionId) {
        if (expected < 0 || expected != governance.stateVersion()) {
            // This fence deliberately runs before a transition's requested
            // revision is locked and composite-bound to the governed article.
            // A forged request identifier is therefore not a proven foreign
            // key fact. Preserve it as a redacted resource ID only.
            auditUnprovenRevisionConflict(subject, governance, revisionId,
                    versionMetadata(expected, governance.stateVersion(), null, null, null, null, null));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "article lifecycle state changed");
        }
    }

    private void requireState(RevisionState state, String approval, String lifecycle) {
        if (state == null || !approval.equals(state.approvalStatus()) || !lifecycle.equals(state.lifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "revision is not in the required lifecycle state");
        }
    }

    private void assertNotSelfApproval(String subject, RevisionState state) {
        if (subject != null && subject.equals(state.createdBySub())) {
            throw new AccessDeniedException("a revision author cannot approve their own revision");
        }
    }

    private void conflict(String message, String subject, Governance governance, String revisionId) {
        audit(subject, "LIFECYCLE_CONCURRENCY_CONFLICT", "article", governance.articleId(), governance, revisionId,
                AuditOutcome.CONFLICT, Map.of());
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void conflictForUnprovenRevision(String message, String subject, Governance governance, String revisionId) {
        auditUnprovenRevisionConflict(subject, governance, revisionId, Map.of());
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void auditUnprovenRevisionConflict(String subject, Governance governance, String requestedRevisionId,
                                                Map<String, ?> metadata) {
        if (blank(requestedRevisionId)) {
            audit(subject, "LIFECYCLE_CONCURRENCY_CONFLICT", "article", governance.articleId(), governance, null,
                    AuditOutcome.CONFLICT, metadata);
            return;
        }
        audit(subject, "LIFECYCLE_CONCURRENCY_CONFLICT", "article_revision", requestedRevisionId, governance, null,
                AuditOutcome.CONFLICT, metadata);
    }

    private CreateRevisionRequest requireDraftRequest(CreateRevisionRequest request) {
        if (request == null || request.snapshot() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snapshot is required");
        }
        return request;
    }

    private TransitionRequest requireTransitionRequest(TransitionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transition request is required");
        }
        return request;
    }

    private void validateSnapshot(ArticleSnapshotInput snapshot) {
        if (snapshot == null || blank(snapshot.title()) || blank(snapshot.severity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snapshot title and severity are required");
        }
    }

    private void validateEvidence(List<EvidenceSnapshotInput> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one evidence snapshot is required");
        }
        for (EvidenceSnapshotInput item : evidence) {
            if (item == null || blank(item.sourceLabel()) || blank(item.content())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "evidence sourceLabel and content are required");
            }
            if (item.sourceUri() != null && item.sourceUri().contains("@")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidence URI credentials are not allowed");
            }
        }
    }

    private void validateCategory(String categoryId) {
        if (blank(categoryId)) {
            return;
        }
        Boolean exists = jdbc.queryForObject("select exists (select 1 from categories where id = ?)", Boolean.class,
                categoryId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown category");
        }
    }

    private static void validateDates(OffsetDateTime reviewDue, OffsetDateTime expires, boolean publishing) {
        if (reviewDue != null && expires != null && reviewDue.isAfter(expires)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review due date must not follow expiry");
        }
        if (publishing && expires != null && !expires.isAfter(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "published revision expiry must be in the future");
        }
    }

    private String snapshot(String articleId, ArticleSnapshotInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", articleId);
        result.put("title", input.title().trim());
        result.put("categoryId", emptyToNull(input.categoryId()));
        result.put("severity", input.severity().trim().toLowerCase(java.util.Locale.ROOT));
        result.put("tags", input.tags() == null ? List.of() : List.copyOf(input.tags()));
        result.put("source", input.source());
        result.put("updatedOn", input.updatedOn() == null ? null : input.updatedOn().toString());
        result.put("author", input.author());
        result.put("confidence", input.confidence());
        result.put("reads", input.reads() == null ? 0 : input.reads());
        result.put("cvss", input.cvss());
        result.put("snippet", input.snippet());
        result.put("body", input.body() == null ? List.of() : List.copyOf(input.body()));
        result.put("iocs", input.iocs() == null ? List.of() : List.copyOf(input.iocs()));
        result.put("related", input.related() == null ? List.of() : List.copyOf(input.related()));
        return json(result);
    }

    private String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "snapshot could not be encoded");
        }
    }

    private void audit(String actor, String action, String targetKind, String targetId, Governance governance,
                       String revisionId, AuditOutcome outcome, Map<String, ?> metadata) {
        if (audit == null) {
            return;
        }
        audit.record(KnowledgeAuditEvent.of(actor, action, targetKind, targetId, governance.workspaceId(),
                governance.articleId(), revisionId, outcome, metadata));
    }

    private static Map<String, Object> versionMetadata(long expected, long actual, String from, String to,
                                                        OffsetDateTime reviewDue, OffsetDateTime expires,
                                                        String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("expected_state_version", expected);
        metadata.put("actual_state_version", actual);
        if (!blank(from)) metadata.put("from_revision_id", from);
        if (!blank(to)) metadata.put("to_revision_id", to);
        if (reviewDue != null) metadata.put("review_due_at", reviewDue.toString());
        if (expires != null) metadata.put("expires_at", expires.toString());
        if (!blank(reason)) metadata.put("reason_code", reason);
        return metadata;
    }

    private static String reasonCode(String value) {
        if (blank(value)) {
            return null;
        }
        String code = value.trim();
        if (!REASON_CODE.matcher(code).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reasonCode must be a short code");
        }
        return code;
    }

    private static OffsetDateTime choose(OffsetDateTime proposed, OffsetDateTime existing) {
        return proposed == null ? existing : proposed;
    }

    private static String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String normalizeSubject(String subject) {
        return subject == null ? "" : subject.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum MutationGrant {
        EDITOR("wm.role in ('owner', 'admin', 'editor')"),
        ADMIN("wm.role in ('owner', 'admin')");

        private final String sqlRolePredicate;

        MutationGrant(String sqlRolePredicate) {
            this.sqlRolePredicate = sqlRolePredicate;
        }

        String sqlRolePredicate() {
            return sqlRolePredicate;
        }
    }

    private record Governance(String articleId, String workspaceId, String classification, String readPolicy,
                              String approvalStatus, String lifecycleStatus, String currentRevisionId,
                              long stateVersion, OffsetDateTime reviewDueAt, OffsetDateTime expiresAt,
                              String approvedBySub, OffsetDateTime approvedAt) {
        static Governance from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Governance(rs.getString("article_id"), rs.getString("workspace_id"),
                    rs.getString("classification"), rs.getString("read_policy"),
                    rs.getString("approval_status"), rs.getString("lifecycle_status"),
                    rs.getString("current_revision_id"), rs.getLong("state_version"),
                    rs.getObject("review_due_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class), rs.getString("approved_by_sub"),
                    rs.getObject("approved_at", OffsetDateTime.class));
        }

        Governance withStateVersion(long value) {
            return new Governance(articleId, workspaceId, classification, readPolicy, approvalStatus, lifecycleStatus,
                    currentRevisionId, value, reviewDueAt, expiresAt, approvedBySub, approvedAt);
        }

        Governance withCurrentRevision(String revisionId) {
            return new Governance(articleId, workspaceId, classification, readPolicy, approvalStatus, lifecycleStatus,
                    revisionId, stateVersion, reviewDueAt, expiresAt, approvedBySub, approvedAt);
        }

        Governance withPolicy(String approval, String lifecycle, OffsetDateTime reviewDue, OffsetDateTime expires,
                              String approver, OffsetDateTime approved) {
            return new Governance(articleId, workspaceId, classification, readPolicy, approval, lifecycle,
                    currentRevisionId, stateVersion, reviewDue, expires, approver, approved);
        }
    }

    private record RevisionState(String revisionId, String articleId, String workspaceId, String classification,
                                 String approvalStatus, String lifecycleStatus, OffsetDateTime reviewDueAt,
                                 OffsetDateTime expiresAt, String approvedBySub, OffsetDateTime approvedAt,
                                 long stateVersion, String createdBySub, int revisionNumber,
                                 String supersedesRevisionId, String rollbackOfRevisionId) {
        static RevisionState from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new RevisionState(rs.getString("article_revision_id"), rs.getString("article_id"),
                    rs.getString("workspace_id"), rs.getString("classification"),
                    rs.getString("approval_status"), rs.getString("lifecycle_status"),
                    rs.getObject("review_due_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                    rs.getString("approved_by_sub"), rs.getObject("approved_at", OffsetDateTime.class),
                    rs.getLong("state_version"), rs.getString("created_by_sub"), rs.getInt("revision_number"),
                    rs.getString("supersedes_revision_id"), rs.getString("rollback_of_revision_id"));
        }
    }

    private record HistoricRevision(String snapshot, String contentSha256, OffsetDateTime reviewDueAt,
                                    OffsetDateTime expiresAt, boolean wasPublished) {
        static HistoricRevision from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new HistoricRevision(rs.getString("snapshot"), rs.getString("content_sha256"),
                    rs.getObject("review_due_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class), rs.getBoolean("was_published"));
        }
    }

    private record EvidenceCopy(int ordinal, String sourceUri, String sourceLabel, String sourceVersion,
                                String content, String contentSha256) {
        static EvidenceCopy from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new EvidenceCopy(rs.getInt("ordinal"), rs.getString("source_uri"),
                    rs.getString("source_label"), rs.getString("source_version"), rs.getString("content"),
                    rs.getString("content_sha256"));
        }
    }
}
