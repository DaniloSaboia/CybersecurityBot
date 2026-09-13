package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import com.sentinel.kms.common.EvidenceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderEvaluationCliTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void refusesToContactAProviderWithoutTheExplicitEnableFlag(@TempDir Path temp) throws Exception {
        Path dataset = writeDataset(temp);

        assertThatThrownBy(() -> ProviderEvaluationCli.main(new String[]{
                "--dataset", dataset.toString(), "--endpoint", "http://127.0.0.1:1/eval",
                "--provider", "local", "--model", "fixture", "--report", temp.resolve("report.json").toString(),
                "--baselines", "RAG"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--enable-real-provider");
    }

    @Test
    void explicitlyEnabledCliExchangesTheDocumentedHttpContractWithALocalProvider(@TempDir Path temp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eval", exchange -> {
            calls.incrementAndGet();
            byte[] response = ("""
                    {"answer":"Privileged accounts require phishing-resistant MFA.",
                     "citationReferences":[{"articleId":"FIX-MFA","articleRevisionId":"fixture-policy-rev-1",
                     "revisionNumber":1,"evidenceSnapshotId":"fixture-policy-snapshot-1","sourceSpanStart":0,
                     "sourceSpanEnd":53,"sourceUri":"fixture://policy","sourceVersion":"fixture-v1",
                     "revisionStatus":"approved"}],"latencyMs":4,
                     "usage":{"costUsd":"0.0001","currency":"USD","estimated":true}}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Path dataset = writeDataset(temp);
            Path report = temp.resolve("provider-report.json");
            Path capture = temp.resolve("provider-answer-capture.json");

            ProviderEvaluationCli.main(new String[]{
                    "--enable-real-provider", "--dataset", dataset.toString(),
                    "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/eval",
                    "--provider", "local-test", "--model", "fixture", "--report", report.toString(),
                    "--baselines", "RAG", "--answer-capture", capture.toString()});

            BaselineComparisonReport result = mapper.readValue(report.toFile(), BaselineComparisonReport.class);
            assertThat(calls).hasValue(1);
            assertThat(Files.readString(capture)).contains("\"schemaVersion\"", "phishing-resistant MFA");
            assertThat(Files.readString(report)).doesNotContain("Privileged accounts require phishing-resistant MFA.");
            assertThat(result.executionMode()).isEqualTo(EvaluationExecutionMode.PROVIDER_OPT_IN);
            assertThat(result.baselines()).singleElement().satisfies(baseline -> {
                assertThat(baseline.baseline()).isEqualTo(AnswerBaseline.RAG);
                assertThat(baseline.results()).singleElement().satisfies(caseResult -> {
                    assertThat(caseResult.candidateMetadata().latencyMs()).isEqualTo(4);
                    assertThat(caseResult.candidateMetadata().cost().amount()).isEqualTo("0.0001");
                });
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesACompletenessOrAnnotationRunBeforeCallingAProviderWithoutTheRequiredLocalSidecars(@TempDir Path temp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eval", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            Path dataset = writeDataset(temp);
            String[] base = {"--enable-real-provider", "--dataset", dataset.toString(),
                    "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/eval",
                    "--provider", "local-test", "--model", "fixture", "--report", temp.resolve("report.json").toString(),
                    "--baselines", "RAG"};

            assertThatThrownBy(() -> ProviderEvaluationCli.main(concat(base, "--require-scientific-completeness")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expert-annotations");
            assertThatThrownBy(() -> ProviderEvaluationCli.main(concat(base,
                    "--expert-annotations", temp.resolve("synthetic-annotations.json").toString())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("answer-capture");
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private Path writeDataset(Path temp) throws Exception {
        EvidenceReference evidence = new EvidenceReference("FIX-MFA", "fixture-policy-rev-1", 1,
                "fixture-policy-snapshot-1", 0, 53, "fixture://policy", "fixture-v1", "approved");
        AnswerEvalDataset dataset = new AnswerEvalDataset("1.0", "provider-cli-v1", List.of(new AnswerEvalCase(
                "1.0", "case-1", AnswerCaseType.ANSWERABLE, "Which control?", List.of(evidence),
                new AnswerExpectation(Answerability.ANSWER, List.of("phishing-resistant MFA"), List.of("FIX-MFA"),
                        List.of(evidence), List.of(), List.of(), List.of(), List.of()), pendingReview())), pendingReview());
        Path file = temp.resolve("dataset.json");
        mapper.writeValue(file.toFile(), dataset);
        return file;
    }

    private static ExpertReview pendingReview() {
        return new ExpertReview(ExpertReviewStatus.PENDING, null, null, "Pending external review.");
    }

    private static String[] concat(String[] base, String... tail) {
        String[] combined = java.util.Arrays.copyOf(base, base.length + tail.length);
        System.arraycopy(tail, 0, combined, base.length, tail.length);
        return combined;
    }
}
