package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fails closed for claims of scientific completeness. Deterministic fixture
 * checks and fixture review metadata cannot substitute for a separately
 * imported, bound, independently reviewed external annotation sidecar.
 */
public final class ScientificCompletenessGate {

    private ScientificCompletenessGate() {
    }

    /** Legacy entry point: without an imported sidecar, completeness is false. */
    public static ScientificCompleteness assess(AnswerEvalDataset dataset) {
        return assess(dataset, List.of(), ExpertAnnotationImportResult.missing());
    }

    /**
     * Assesses review-process completeness, not answer quality. A recorded
     * FAIL verdict can be a complete review; this class never manufactures an
     * aggregate expert score or treats a heuristic as a human finding.
     */
    public static ScientificCompleteness assess(AnswerEvalDataset dataset,
                                                List<BaselineResult> baselines,
                                                ExpertAnnotationImportResult imported) {
        if (dataset == null) {
            return new ScientificCompleteness(false, List.of("No dataset was supplied."));
        }
        ExpertAnnotationImportResult annotationImport = imported == null
                ? ExpertAnnotationImportResult.missing() : imported;
        if (!annotationImport.validated()) {
            List<String> blockers = new ArrayList<>(annotationImport.issues());
            if (blockers.isEmpty()) {
                blockers.add("External expert-annotation artifact is not structurally validated.");
            }
            return new ScientificCompleteness(false, blockers);
        }
        if (annotationImport.bundle() == null) {
            return new ScientificCompleteness(false,
                    List.of("Validated annotation import omitted its sidecar bundle."));
        }

        List<String> blockers = new ArrayList<>();
        ExpertAnnotationBundle bundle = annotationImport.bundle();
        validateDatasetReviews(bundle.datasetReviews(), blockers);
        Map<Target, BaselineCaseResult> cells = reportCells(dataset, baselines, blockers);
        Map<Target, List<ExpertAnnotationBundle.Annotation>> annotations = groupAnnotations(bundle.annotations(), blockers);
        Map<Target, List<ExpertAnnotationBundle.Adjudication>> adjudications = groupAdjudications(bundle.adjudications(), blockers);
        annotations.keySet().stream().filter(target -> !cells.containsKey(target)).forEach(target ->
                blockers.add("Annotation targets a baseline candidate absent from this report: " + target.display()));
        adjudications.keySet().stream().filter(target -> !cells.containsKey(target)).forEach(target ->
                blockers.add("Adjudication targets a baseline candidate absent from this report: " + target.display()));
        cells.forEach((target, cell) -> validateCandidateReviews(target, cell, caseById(dataset, target.caseId()),
                annotations.getOrDefault(target, List.of()), adjudications.getOrDefault(target, List.of()), blockers));
        return new ScientificCompleteness(blockers.isEmpty(), blockers);
    }

    private static void validateDatasetReviews(List<ExpertAnnotationBundle.DatasetReview> records,
                                               List<String> blockers) {
        Set<String> reviewers = new HashSet<>();
        for (ExpertAnnotationBundle.DatasetReview review : safe(records)) {
            if (review != null && review.status() == ExpertReviewStatus.ACCEPTED && review.independent()
                    && !blank(review.reviewerId()) && !blank(review.reviewerQualification())
                    && review.reviewedAt() != null && !blank(review.rationale())) {
                reviewers.add(review.reviewerId());
            }
        }
        if (reviewers.size() < 2) {
            blockers.add("Dataset/rubric sidecar requires two independent qualified ACCEPTED reviews.");
        }
    }

    private static Map<Target, BaselineCaseResult> reportCells(AnswerEvalDataset dataset,
                                                                List<BaselineResult> baselines,
                                                                List<String> blockers) {
        Map<Target, BaselineCaseResult> cells = new HashMap<>();
        if (baselines == null || baselines.isEmpty()) {
            blockers.add("No baseline report cells were supplied for completeness assessment.");
            return cells;
        }
        for (BaselineResult baseline : baselines) {
            if (baseline == null || baseline.baseline() == null) {
                blockers.add("Report contains a baseline without an identifier.");
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (BaselineCaseResult result : safe(baseline.results())) {
                if (result == null || blank(result.caseId())) {
                    blockers.add("Report contains a baseline result without a case identifier.");
                    continue;
                }
                Target target = new Target(baseline.baseline(), result.caseId());
                if (!seen.add(result.caseId()) || cells.putIfAbsent(target, result) != null) {
                    blockers.add("Report contains a duplicate baseline/case cell: " + target.display());
                }
                if (caseById(dataset, result.caseId()) == null) {
                    blockers.add("Report contains a case absent from the dataset: " + target.display());
                }
                if (!sha256(result.candidateSha256())) {
                    blockers.add("Report candidate digest is missing or malformed: " + target.display());
                }
            }
            for (AnswerEvalCase evalCase : dataset.cases()) {
                if (!seen.contains(evalCase.id())) {
                    blockers.add("Report omits dataset case " + evalCase.id() + " for baseline " + baseline.baseline() + ".");
                }
            }
        }
        return cells;
    }

    private static Map<Target, List<ExpertAnnotationBundle.Annotation>> groupAnnotations(
            List<ExpertAnnotationBundle.Annotation> records, List<String> blockers) {
        Map<Target, List<ExpertAnnotationBundle.Annotation>> grouped = new HashMap<>();
        for (ExpertAnnotationBundle.Annotation record : safe(records)) {
            if (record == null) {
                blockers.add("Annotation sidecar contains a missing review record.");
            } else {
                grouped.computeIfAbsent(new Target(record.baseline(), record.caseId()), ignored -> new ArrayList<>()).add(record);
            }
        }
        return grouped;
    }

    private static Map<Target, List<ExpertAnnotationBundle.Adjudication>> groupAdjudications(
            List<ExpertAnnotationBundle.Adjudication> records, List<String> blockers) {
        Map<Target, List<ExpertAnnotationBundle.Adjudication>> grouped = new HashMap<>();
        for (ExpertAnnotationBundle.Adjudication record : safe(records)) {
            if (record == null) {
                blockers.add("Annotation sidecar contains a missing adjudication record.");
            } else {
                grouped.computeIfAbsent(new Target(record.baseline(), record.caseId()), ignored -> new ArrayList<>()).add(record);
            }
        }
        return grouped;
    }

    private static void validateCandidateReviews(Target target,
                                                 BaselineCaseResult cell,
                                                 AnswerEvalCase evalCase,
                                                 List<ExpertAnnotationBundle.Annotation> records,
                                                 List<ExpertAnnotationBundle.Adjudication> adjudications,
                                                 List<String> blockers) {
        if (evalCase == null || !sha256(cell.candidateSha256())) {
            return;
        }
        List<ExpertAnnotationBundle.Annotation> qualifying = safe(records).stream()
                .filter(record -> record.status() == ExpertReviewStatus.ACCEPTED && record.independent()
                        && !blank(record.reviewerId()) && !blank(record.reviewerQualification())
                        && record.reviewedAt() != null && !blank(record.rationale())
                        && cell.candidateSha256().equals(record.candidateSha256())
                        && record.labels() != null && record.labels().complete()
                        && evidenceEquals(evalCase.evidence(), record.reviewedEvidence()))
                .toList();
        Set<String> reviewerIds = new HashSet<>();
        qualifying.forEach(record -> reviewerIds.add(record.reviewerId()));
        if (reviewerIds.size() < 2) {
            blockers.add("Baseline candidate requires two independent qualified ACCEPTED reviews: " + target.display());
            return;
        }
        boolean disagreement = qualifying.stream().map(ExpertAnnotationBundle.Annotation::labels).distinct().count() > 1
                || qualifying.stream().anyMatch(record -> record.labels().hasUncertain());
        if (!disagreement) {
            return;
        }
        boolean resolved = safe(adjudications).stream().anyMatch(adjudication ->
                adjudication.status() == ExpertReviewStatus.ACCEPTED && adjudication.independent()
                        && !blank(adjudication.adjudicatorId()) && !reviewerIds.contains(adjudication.adjudicatorId())
                        && !blank(adjudication.adjudicatorQualification()) && adjudication.adjudicatedAt() != null
                        && !blank(adjudication.rationale()) && cell.candidateSha256().equals(adjudication.candidateSha256())
                        && adjudication.labels() != null && adjudication.labels().complete()
                        && evidenceEquals(evalCase.evidence(), adjudication.reviewedEvidence()));
        if (!resolved) {
            blockers.add("Disagreement or UNCERTAIN labels require a separate qualified adjudication: " + target.display());
        }
    }

    private static AnswerEvalCase caseById(AnswerEvalDataset dataset, String caseId) {
        if (dataset == null || blank(caseId)) {
            return null;
        }
        return dataset.cases().stream().filter(evalCase -> caseId.equals(evalCase.id())).findFirst().orElse(null);
    }

    private static boolean evidenceEquals(List<EvidenceReference> expected, List<EvidenceReference> actual) {
        return expected != null && actual != null && expected.equals(actual);
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record Target(AnswerBaseline baseline, String caseId) {
        private String display() {
            return String.valueOf(baseline) + "/" + String.valueOf(caseId);
        }
    }

    public static void requireComplete(ScientificCompleteness completeness) {
        if (completeness == null || !completeness.complete()) {
            List<String> blockers = completeness == null ? List.of("No completeness result was supplied.") : completeness.blockers();
            throw new IllegalStateException("Scientific-completeness gate failed: " + String.join("; ", blockers));
        }
    }
}
