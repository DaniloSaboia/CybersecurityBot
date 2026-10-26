package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deliberately opt-in executable for a caller-supplied HTTP evaluation endpoint.
 * It does not load credentials, discover providers, or make a request unless the
 * command includes {@code --enable-real-provider}. The endpoint is responsible
 * for any authentication mechanism it needs; this CLI never reads environment
 * secrets or credential files.
 */
public final class ProviderEvaluationCli {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ProviderEvaluationCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (!arguments.enableRealProvider()) {
            throw new IllegalStateException("Refusing provider evaluation without --enable-real-provider");
        }
        if (arguments.requireScientificCompleteness() && arguments.expertAnnotations() == null) {
            throw new IllegalStateException("--require-scientific-completeness requires --expert-annotations");
        }
        if (arguments.expertAnnotations() != null && arguments.answerCapture() == null) {
            throw new IllegalStateException("--expert-annotations requires a caller-owned --answer-capture file");
        }
        if (arguments.expertAnnotations() != null && !Files.isRegularFile(arguments.expertAnnotations())) {
            throw new IllegalArgumentException("expert-annotation artifact must be a regular file");
        }
        AnswerEvalDataset dataset = MAPPER.readValue(arguments.dataset().toFile(), AnswerEvalDataset.class);
        HttpProviderAnswerSource source = new HttpProviderAnswerSource(arguments.endpoint(), arguments.provider(),
                arguments.model(), "", arguments.timeout());
        ProviderEvaluationHarness harness = new ProviderEvaluationHarness(
                new BaselineComparisonHarness(new AnswerEvaluationService()), source);
        ProviderEvaluationRun run = harness.runCaptured(dataset,
                        new ProviderEvaluationRequest(true, arguments.baselines()))
                .orElseThrow(() -> new IllegalStateException("explicit provider evaluation did not run"));
        BaselineComparisonReport report = run.report();
        String answerCaptureSha256 = null;
        if (arguments.answerCapture() != null) {
            OfflineBaselineEvaluationCli.writeSavedAnswers(arguments.answerCapture(), dataset, run.candidates());
            answerCaptureSha256 = OfflineBaselineEvaluationCli.sha256Artifact(arguments.answerCapture());
        }
        if (arguments.expertAnnotations() != null) {
            ExpertAnnotationImportResult imported = new ExpertAnnotationImportService().importFile(
                    arguments.expertAnnotations(), dataset, run.candidates(),
                    OfflineBaselineEvaluationCli.sha256Artifact(arguments.dataset()), answerCaptureSha256);
            report = new BaselineComparisonHarness(new AnswerEvaluationService())
                    .withExpertAnnotations(dataset, report, imported);
        }
        if (arguments.requireScientificCompleteness()) {
            ScientificCompletenessGate.requireComplete(report.scientificCompleteness());
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(arguments.report().toFile(), report);
    }

    private record Arguments(boolean enableRealProvider, Path dataset, URI endpoint, String provider, String model,
                             Path report, Path expertAnnotations, Path answerCapture,
                             List<AnswerBaseline> baselines, Duration timeout,
                             boolean requireScientificCompleteness) {
        private static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean enabled = false;
            boolean requireScientificCompleteness = false;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--enable-real-provider".equals(arg)) {
                    enabled = true;
                    continue;
                }
                if ("--require-scientific-completeness".equals(arg)) {
                    requireScientificCompleteness = true;
                    continue;
                }
                if (!"--dataset".equals(arg) && !"--endpoint".equals(arg) && !"--provider".equals(arg)
                        && !"--model".equals(arg) && !"--report".equals(arg) && !"--baselines".equals(arg)
                        && !"--timeout-seconds".equals(arg) && !"--expert-annotations".equals(arg)
                        && !"--answer-capture".equals(arg)) {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
                if (++index >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                if (values.put(arg, args[index]) != null) {
                    throw new IllegalArgumentException("Duplicate argument: " + arg);
                }
            }
            for (String required : List.of("--dataset", "--endpoint", "--provider", "--model", "--report")) {
                if (!values.containsKey(required) || values.get(required).isBlank()) {
                    throw new IllegalArgumentException("Usage: --enable-real-provider --dataset <path> --endpoint <http(s)://...> "
                            + "--provider <name> --model <name> --report <path> [--baselines RAG,SEARCH_ONLY,NO_RAG] "
                            + "[--timeout-seconds n] [--answer-capture <path>] [--expert-annotations <path>] "
                            + "[--require-scientific-completeness]");
                }
            }
            long timeoutSeconds = parseTimeout(values.get("--timeout-seconds"));
            Path annotations = values.containsKey("--expert-annotations")
                    ? Path.of(values.get("--expert-annotations")) : null;
            Path capture = values.containsKey("--answer-capture")
                    ? Path.of(values.get("--answer-capture")) : null;
            return new Arguments(enabled, Path.of(values.get("--dataset")), URI.create(values.get("--endpoint")),
                    values.get("--provider").trim(), values.get("--model").trim(), Path.of(values.get("--report")),
                    annotations, capture, parseBaselines(values.get("--baselines")), Duration.ofSeconds(timeoutSeconds),
                    requireScientificCompleteness);
        }

        private static long parseTimeout(String value) {
            if (value == null || value.isBlank()) {
                return 60;
            }
            try {
                long seconds = Long.parseLong(value);
                if (seconds < 1 || seconds > 600) {
                    throw new IllegalArgumentException("--timeout-seconds must be between 1 and 600");
                }
                return seconds;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--timeout-seconds must be an integer", e);
            }
        }

        private static List<AnswerBaseline> parseBaselines(String value) {
            if (value == null || value.isBlank()) {
                return List.of(); // ProviderEvaluationHarness expands an empty list to all baselines.
            }
            List<AnswerBaseline> baselines = new ArrayList<>();
            for (String token : value.split(",")) {
                try {
                    AnswerBaseline baseline = AnswerBaseline.valueOf(token.trim().toUpperCase(Locale.ROOT));
                    if (!baselines.contains(baseline)) {
                        baselines.add(baseline);
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown baseline: " + token, e);
                }
            }
            if (baselines.isEmpty()) {
                throw new IllegalArgumentException("--baselines must name at least one baseline");
            }
            return List.copyOf(baselines);
        }
    }
}
