package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderAnswerSourceTest {

    @Test
    void serializesTheOptInWireContractAndParsesProviderReportedUsageWithoutMakingANetworkCall() throws Exception {
        HttpProviderAnswerSource source = new HttpProviderAnswerSource(URI.create("https://provider.invalid/eval"),
                "test-provider", "test-model", "Bearer supplied-by-caller", Duration.ofSeconds(2));
        EvidenceReference evidence = new EvidenceReference("FIX-MFA", "fixture-policy-rev-1", 1,
                "fixture-policy-snapshot-1", 0, 53, "fixture://policy", "fixture-v1", "approved");
        AnswerEvalCase evalCase = new AnswerEvalCase("1.0", "case-1", AnswerCaseType.ANSWERABLE, "Which control?",
                List.of(evidence), new AnswerExpectation(Answerability.ANSWER, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()), pendingReview());

        JsonNode request = new ObjectMapper().readTree(source.requestBody(evalCase, AnswerBaseline.RAG));
        AnswerCandidate parsed = source.parseResponse("""
                {"answer":"Use phishing-resistant MFA.","citationReferences":[
                  {"articleId":"FIX-MFA","articleRevisionId":"fixture-policy-rev-1","revisionNumber":1,
                   "evidenceSnapshotId":"fixture-policy-snapshot-1","sourceSpanStart":0,"sourceSpanEnd":53,
                   "sourceUri":"fixture://policy","sourceVersion":"fixture-v1","revisionStatus":"approved"}],
                 "latencyMs":17,"usage":{"inputTokens":3,"outputTokens":4,"costUsd":"0.0012","currency":"USD","estimated":false}}
                """, Instant.parse("2026-09-05T00:00:00Z"), 99);

        assertThat(request.path("question").asText()).isEqualTo("Which control?");
        assertThat(request.path("baseline").asText()).isEqualTo("RAG");
        assertThat(request.path("evidence")).hasSize(1);
        assertThat(parsed.metadata().executionMode()).isEqualTo(EvaluationExecutionMode.PROVIDER_OPT_IN);
        assertThat(parsed.metadata().latencyMs()).isEqualTo(17);
        assertThat(parsed.metadata().cost().amount()).isEqualTo("0.0012");
        assertThat(parsed.citationReferences()).singleElement().satisfies(reference ->
                assertThat(reference.hasCompleteProvenance()).isTrue());
    }

    private static ExpertReview pendingReview() {
        return new ExpertReview(ExpertReviewStatus.PENDING, null, null, "Pending external review.");
    }
}
