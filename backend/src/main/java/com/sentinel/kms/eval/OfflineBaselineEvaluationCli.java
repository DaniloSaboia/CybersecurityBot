package com.sentinel.kms.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Offline executable for comparing saved answer outputs. It reads no credentials
 * and makes no network/provider call: callers must capture answers separately.
 */
public final class OfflineBaselineEvaluationCli {

    private static final long MAX_ARTIFACT_BYTES = 16L * 1024L * 1024L;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OfflineBaselineEvaluationCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        BaselineComparisonReport report = run(arguments.dataset(), arguments.answers(), arguments.expertAnnotations());
        if (arguments.requireScientificCompleteness()) {
            ScientificCompletenessGate.requireComplete(report.scientificCompleteness());
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(arguments.report().toFile(), report);
    }

    /** Runs the deterministic comparison from versioned on-disk inputs. */
    public static BaselineComparisonReport run(Path datasetPath, Path savedAnswersPath) throws IOException {
        return run(datasetPath, savedAnswersPath, null);
    }

    /**
     * Runs a saved-output comparison and optionally attaches a separately
     * supplied expert sidecar. The sidecar is bound to the exact dataset and
     * answer artifact bytes; no network or provider call is made.
     */
    static BaselineComparisonReport run(Path datasetPath, Path savedAnswersPath,
                                        Path expertAnnotationsPath) throws IOException {
        AnswerEvalDataset dataset = MAPPER.readValue(datasetPath.toFile(), AnswerEvalDataset.class);
        SavedAnswers saved = readSavedAnswers(savedAnswersPath, dataset);
        BaselineComparisonHarness harness = new BaselineComparisonHarness(new AnswerEvaluationService());
        BaselineComparisonReport report = harness.runOffline(dataset, saved.candidates());
        if (expertAnnotationsPath == null) {
            return report;
        }
        ExpertAnnotationImportResult imported = new ExpertAnnotationImportService().importFile(expertAnnotationsPath,
                dataset, saved.candidates(), sha256Artifact(datasetPath), sha256Artifact(savedAnswersPath));
        return harness.withExpertAnnotations(dataset, report, imported);
    }

    private static SavedAnswers readSavedAnswers(Path savedAnswersPath, AnswerEvalDataset dataset) throws IOException {
        JsonNode root = MAPPER.readTree(savedAnswersPath.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("saved answers must be a JSON object");
        }
        rejectUnknownFields(root, Set.of("schemaVersion", "datasetId", "baselines"), "saved answers");
        String inputSchemaVersion = requiredText(root, "schemaVersion", "saved answers");
        if (!dataset.schemaVersion().equals(inputSchemaVersion)) {
            throw new IllegalArgumentException("saved answers schemaVersion does not match dataset: " + inputSchemaVersion);
        }
        String inputDatasetId = requiredText(root, "datasetId", "saved answers");
        if (!dataset.datasetId().equals(inputDatasetId)) {
            throw new IllegalArgumentException("saved answers datasetId does not match dataset: " + inputDatasetId);
        }
        JsonNode baselinesNode = root.path("baselines");
        if (!baselinesNode.isObject()) {
            throw new IllegalArgumentException("saved answers must contain a baselines object");
        }
        Set<String> allowedBaselines = new HashSet<>();
        for (AnswerBaseline baseline : AnswerBaseline.values()) {
            allowedBaselines.add(baseline.name());
        }
        rejectUnknownFields(baselinesNode, allowedBaselines, "saved answer baselines");
        Set<String> expectedCaseIds = dataset.cases().stream().map(AnswerEvalCase::id).collect(java.util.stream.Collectors.toSet());
        Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates = new EnumMap<>(AnswerBaseline.class);
        for (AnswerBaseline baseline : AnswerBaseline.values()) {
            JsonNode answersNode = baselinesNode.path(baseline.name());
            if (answersNode.isObject()) {
                rejectUnknownFields(answersNode, Set.of("answers"), "baseline " + baseline);
                answersNode = answersNode.path("answers");
            }
            if (!answersNode.isArray()) {
                throw new IllegalArgumentException("missing answers array for baseline " + baseline);
            }
            Map<String, AnswerCandidate> byCase = new LinkedHashMap<>();
            for (JsonNode answerNode : answersNode) {
                if (!answerNode.isObject()) {
                    throw new IllegalArgumentException("saved answer must be an object for baseline " + baseline);
                }
                String caseId = requiredText(answerNode, "caseId", "saved answer for baseline " + baseline);
                if (!expectedCaseIds.contains(caseId)) {
                    throw new IllegalArgumentException("unexpected case for baseline " + baseline + ": " + caseId);
                }
                ObjectNode candidateNode = answerNode.deepCopy();
                candidateNode.remove("caseId");
                AnswerCandidate candidate = MAPPER.treeToValue(candidateNode, AnswerCandidate.class);
                if (byCase.put(caseId, candidate) != null) {
                    throw new IllegalArgumentException("duplicate saved answer for baseline " + baseline + " case " + caseId);
                }
            }
            if (!byCase.keySet().equals(expectedCaseIds)) {
                Set<String> missing = new HashSet<>(expectedCaseIds);
                missing.removeAll(byCase.keySet());
                throw new IllegalArgumentException("saved answers do not contain exactly the dataset cases for baseline "
                        + baseline + "; missing=" + missing);
            }
            candidates.put(baseline, byCase);
        }
        return new SavedAnswers(candidates);
    }

    static void writeSavedAnswers(Path output, AnswerEvalDataset dataset,
                                  Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates) throws IOException {
        if (output == null || dataset == null || candidates == null) {
            throw new IllegalArgumentException("output, dataset, and candidates are required");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", dataset.schemaVersion());
        root.put("datasetId", dataset.datasetId());
        ObjectNode baselines = root.putObject("baselines");
        for (AnswerBaseline baseline : AnswerBaseline.values()) {
            Map<String, AnswerCandidate> answers = candidates.get(baseline);
            if (answers == null) {
                continue;
            }
            var outputAnswers = baselines.putArray(baseline.name());
            for (AnswerEvalCase evalCase : dataset.cases()) {
                AnswerCandidate candidate = answers.get(evalCase.id());
                if (candidate == null) {
                    throw new IllegalArgumentException("missing captured candidate for baseline " + baseline
                            + " case " + evalCase.id());
                }
                ObjectNode answer = MAPPER.valueToTree(candidate);
                answer.put("caseId", evalCase.id());
                outputAnswers.add(answer);
            }
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
    }

    static String sha256Artifact(Path artifact) throws IOException {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("evaluation artifact must be a regular file");
        }
        if (Files.size(artifact) > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("evaluation artifact exceeds " + MAX_ARTIFACT_BYTES + " bytes");
        }
        return CandidateFingerprint.sha256Bytes(Files.readAllBytes(artifact));
    }

    private static String requiredText(JsonNode node, String field, String context) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(context + " is missing " + field);
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String context) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("unknown field in " + context + ": " + field);
            }
        });
    }

    private record SavedAnswers(Map<AnswerBaseline, Map<String, AnswerCandidate>> candidates) {
    }

    private record Arguments(Path dataset, Path answers, Path expertAnnotations, Path report,
                             boolean requireScientificCompleteness) {
        private static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean requireScientificCompleteness = false;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--require-scientific-completeness".equals(arg)) {
                    requireScientificCompleteness = true;
                    continue;
                }
                if (!"--dataset".equals(arg) && !"--answers".equals(arg) && !"--expert-annotations".equals(arg)
                        && !"--report".equals(arg)) {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
                if (++index >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                if (values.put(arg, args[index]) != null) {
                    throw new IllegalArgumentException("Duplicate argument: " + arg);
                }
            }
            if (!values.containsKey("--dataset") || !values.containsKey("--answers") || !values.containsKey("--report")) {
                throw new IllegalArgumentException("Usage: --dataset <path> --answers <path> --report <path> "
                        + "[--expert-annotations <path>] [--require-scientific-completeness]");
            }
            Path annotations = values.containsKey("--expert-annotations")
                    ? Path.of(values.get("--expert-annotations")) : null;
            return new Arguments(Path.of(values.get("--dataset")), Path.of(values.get("--answers")), annotations,
                    Path.of(values.get("--report")), requireScientificCompleteness);
        }
    }
}
