package com.sentinel.kms.common;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePolicyTest {

    @Test
    void publicApprovedEvidenceIsReadableToAnAuthenticatedSubject() {
        KnowledgeAccessContext context = KnowledgeAccessContext.forTest("analyst-1", Map.of());

        AccessDecision decision = KnowledgePolicy.decide(context,
                new ArticlePolicy("shared", Classification.PUBLIC, ApprovalStatus.APPROVED,
                        ReadPolicy.PUBLIC, LifecycleStatus.ACTIVE));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo("PUBLIC_APPROVED");
    }

    @Test
    void restrictedEvidenceFailsClosedForViewerAndMissingGovernance() {
        KnowledgeAccessContext viewer = KnowledgeAccessContext.forTest("analyst-1",
                Map.of("workspace-a", WorkspaceRole.VIEWER));

        AccessDecision restricted = KnowledgePolicy.decide(viewer,
                new ArticlePolicy("workspace-a", Classification.RESTRICTED, ApprovalStatus.APPROVED,
                        ReadPolicy.WORKSPACE_ADMIN, LifecycleStatus.ACTIVE));
        AccessDecision absent = KnowledgePolicy.decide(viewer, null);

        assertThat(restricted.allowed()).isFalse();
        assertThat(restricted.reasonCode()).isEqualTo("ROLE_REQUIRED");
        assertThat(absent.allowed()).isFalse();
        assertThat(absent.reasonCode()).isEqualTo("GOVERNANCE_MISSING");
    }

    @Test
    void malformedRestrictedMemberPolicyCannotLowerClassificationClearance() {
        KnowledgeAccessContext viewer = KnowledgeAccessContext.forTest("analyst-1",
                Map.of("workspace-a", WorkspaceRole.VIEWER));

        AccessDecision decision = KnowledgePolicy.decide(viewer,
                new ArticlePolicy("workspace-a", Classification.RESTRICTED, ApprovalStatus.APPROVED,
                        ReadPolicy.WORKSPACE_MEMBER, LifecycleStatus.ACTIVE));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("POLICY_CLASSIFICATION_MISMATCH");
    }

    @Test
    void unapprovedOrSupersededEvidenceIsNeverReadableEvenToAnAdmin() {
        KnowledgeAccessContext admin = KnowledgeAccessContext.forTest("admin-1",
                Map.of("workspace-a", WorkspaceRole.ADMIN));

        AccessDecision pending = KnowledgePolicy.decide(admin,
                new ArticlePolicy("workspace-a", Classification.CONFIDENTIAL, ApprovalStatus.PENDING_REVIEW,
                        ReadPolicy.WORKSPACE_EDITOR, LifecycleStatus.ACTIVE));
        AccessDecision superseded = KnowledgePolicy.decide(admin,
                new ArticlePolicy("workspace-a", Classification.CONFIDENTIAL, ApprovalStatus.APPROVED,
                        ReadPolicy.WORKSPACE_EDITOR, LifecycleStatus.SUPERSEDED));

        assertThat(pending.allowed()).isFalse();
        assertThat(pending.reasonCode()).isEqualTo("NOT_APPROVED");
        assertThat(superseded.allowed()).isFalse();
        assertThat(superseded.reasonCode()).isEqualTo("NOT_ACTIVE");
    }

    @Test
    void evidenceReferenceRequiresAnImmutableRevisionAndSnapshot() {
        Map<String, Object> valid = Map.of(
                "article_id", "CVE-2026-0001",
                "article_revision_id", "revision-1",
                "article_revision_number", 2,
                "evidence_snapshot_id", "snapshot-1",
                "source_span_start", 4,
                "source_span_end", 22,
                "source_uri", "https://example.test/advisory",
                "source_version", "2026-09-05",
                "revision_status", "APPROVED");

        Optional<EvidenceReference> evidence = EvidenceReference.fromMetadata(valid);

        assertThat(evidence).contains(new EvidenceReference(
                "CVE-2026-0001", "revision-1", 2, "snapshot-1", 4, 22,
                "https://example.test/advisory", "2026-09-05", "APPROVED"));
        assertThat(EvidenceReference.fromMetadata(Map.of("article_id", "CVE-2026-0001"))).isEmpty();
        assertThat(EvidenceReference.fromMetadata(Map.of(
                "article_id", "CVE-2026-0001",
                "article_revision_id", "revision-1",
                "article_revision_number", 2,
                "evidence_snapshot_id", "snapshot-1"))).isEmpty();
    }

    @Test
    void connectorMutationNeedsAnEditorOrAdminMembership() {
        KnowledgeAccessContext viewer = KnowledgeAccessContext.forTest("viewer",
                Map.of("shared", WorkspaceRole.VIEWER));
        KnowledgeAccessContext editor = KnowledgeAccessContext.forTest("editor",
                Map.of("shared", WorkspaceRole.EDITOR));

        assertThat(KnowledgePolicy.canMutateConnector(viewer)).isFalse();
        assertThat(KnowledgePolicy.canMutateConnector(editor)).isTrue();
        assertThat(KnowledgePolicy.canMutateConnector(
                KnowledgeAccessContext.forTest("none", Map.of()))).isFalse();
    }
}
