package com.sentinel.kms.eval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGoldenSetTest {

    @Test
    void loadsVersionedCasesForEveryRequestedAnswerLevelCategoryWithoutClaimingExpertReview() {
        AnswerGoldenSet goldenSet = new AnswerGoldenSet(new ClassPathResource("eval/answer-eval-v1.json"));

        AnswerEvalDataset dataset = goldenSet.dataset();

        assertThat(dataset.schemaVersion()).isEqualTo("1.0");
        assertThat(dataset.cases()).extracting(AnswerEvalCase::caseType)
                .contains(AnswerCaseType.ANSWERABLE, AnswerCaseType.UNANSWERABLE,
                        AnswerCaseType.AMBIGUOUS, AnswerCaseType.CONFLICTING,
                        AnswerCaseType.STALE, AnswerCaseType.MULTI_DOCUMENT,
                        AnswerCaseType.ADVERSARIAL, AnswerCaseType.AUTHORIZATION);
        assertThat(dataset.cases()).allSatisfy(evalCase ->
                assertThat(evalCase.expertReview().status()).isEqualTo(ExpertReviewStatus.PENDING));
    }

    @Test
    void treatsSupersededFixtureEvidenceAsAnAbstentionCaseRatherThanAResolvableCitation() {
        AnswerGoldenSet goldenSet = new AnswerGoldenSet(new ClassPathResource("eval/answer-eval-v1.json"));

        AnswerEvalCase stale = goldenSet.dataset().cases().stream()
                .filter(evalCase -> evalCase.caseType() == AnswerCaseType.STALE)
                .findFirst().orElseThrow();

        assertThat(stale.expectation().answerability()).isEqualTo(Answerability.ABSTAIN);
        assertThat(stale.expectation().requiredCitationIds()).isEmpty();
        assertThat(stale.expectation().requiredEvidence()).isEmpty();
        assertThat(stale.expertReview().status()).isEqualTo(ExpertReviewStatus.PENDING);
    }

    @Test
    void includesAnApprovedVersionedTemporalDisclosureCaseSeparateFromTheStaleAbstention() {
        AnswerGoldenSet goldenSet = new AnswerGoldenSet(new ClassPathResource("eval/answer-eval-v1.json"));

        AnswerEvalCase temporal = goldenSet.dataset().cases().stream()
                .filter(evalCase -> "temporal-current-version-disclosure".equals(evalCase.id()))
                .findFirst().orElseThrow();

        assertThat(temporal.expectation().answerability()).isEqualTo(Answerability.ANSWER);
        assertThat(temporal.expectation().requiredTemporalMarkers()).containsExactly("2026-09-06");
        assertThat(temporal.evidence()).allSatisfy(reference ->
                assertThat(reference.revisionStatus()).isEqualTo("approved"));
        assertThat(temporal.expertReview().status()).isEqualTo(ExpertReviewStatus.PENDING);
    }

    @Test
    void keepsAuthorizationFixtureSyntheticAndPendingRatherThanClaimingAnAccessDecision() {
        AnswerEvalCase authorization = new AnswerGoldenSet(new ClassPathResource("eval/answer-eval-v1.json"))
                .dataset().cases().stream()
                .filter(evalCase -> evalCase.caseType() == AnswerCaseType.AUTHORIZATION)
                .findFirst().orElseThrow();

        assertThat(authorization.expectation().forbiddenCitationIds()).containsExactly("FIX-DENIED");
        assertThat(authorization.expertReview().status()).isEqualTo(ExpertReviewStatus.PENDING);
    }
}
