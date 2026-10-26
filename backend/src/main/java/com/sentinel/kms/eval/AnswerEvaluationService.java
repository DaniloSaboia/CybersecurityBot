package com.sentinel.kms.eval;

import com.sentinel.kms.common.EvidenceReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline deterministic answer-evaluation checks. These checks are useful
 * regression signals over explicitly versioned fixtures, but are intentionally
 * labelled as heuristics: no string, citation-id, or span match proves truth,
 * semantic support, faithfulness, correctness, safety, or expert agreement.
 */
public final class AnswerEvaluationService {

    public static final String HEURISTIC_CAVEAT = "Deterministic fixture-match heuristic only; it does not prove factual truth, "
            + "semantic citation support, faithfulness, correctness, safety, or expert validation.";

    public static final String AUTHORIZATION_HEURISTIC_CAVEAT = "Deterministic denied-fixture check only; it does not prove backend "
            + "access control, authorization decisions, absence of provider calls, or absence of unreferenced disclosure.";

    public AnswerEvaluationResult evaluate(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        if (evalCase == null) {
            throw new IllegalArgumentException("evalCase is required");
        }
        AnswerCandidate effectiveCandidate = candidate == null
                ? new AnswerCandidate("", List.of(), List.of(), null)
                : candidate;

        MetricAssessment citations = citationSupport(evalCase, effectiveCandidate);
        MetricAssessment correctness = requiredPhrases("correctness_heuristic", effectiveCandidate.answer(),
                evalCase.expectation().requiredPhrases());
        MetricAssessment faithfulness = faithfulness(evalCase, effectiveCandidate, citations);
        MetricAssessment abstention = abstention(evalCase, effectiveCandidate);
        MetricAssessment temporal = temporal(evalCase, effectiveCandidate);
        MetricAssessment adversarial = adversarial(evalCase, effectiveCandidate);
        MetricAssessment authorization = authorization(evalCase, effectiveCandidate);

        List<MetricAssessment> checks = List.of(citations, faithfulness, correctness, abstention, temporal, adversarial,
                authorization);
        boolean deterministicPass = checks.stream().noneMatch(check -> check.status() == MetricStatus.FAIL);
        List<String> limitations = new ArrayList<>();
        limitations.add(HEURISTIC_CAVEAT);
        limitations.add("Expert review status is " + evalCase.expertReview().status()
                + "; deterministic outcomes do not change it.");

        return new AnswerEvaluationResult(evalCase.id(), evalCase.caseType(), effectiveCandidate.metadata(),
                citations, faithfulness, correctness, abstention, temporal, adversarial,
                authorization,
                evalCase.expertReview(), deterministicPass, limitations);
    }

    private MetricAssessment citationSupport(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        List<String> expectedIds = expectedCitationIds(evalCase);
        if (expectedIds.isEmpty()) {
            return notApplicable("citation_support_heuristic", "Fixture has no expected citations.");
        }

        int matched = 0;
        List<String> observations = new ArrayList<>();
        for (String expectedId : expectedIds) {
            List<EvidenceReference> expectedEvidence = expectedEvidence(evalCase, expectedId);
            List<CitationReference> matchingIds = candidate.citationReferences().stream()
                    .filter(reference -> expectedId.equals(reference.articleId()))
                    .toList();

            if (expectedEvidence.isEmpty()) {
                boolean idFound = !matchingIds.isEmpty() || candidate.legacyCitationIds().contains(expectedId);
                if (idFound) {
                    matched++;
                    observations.add("fixture citation id matched without a fixture provenance requirement: " + expectedId);
                } else {
                    observations.add("missing expected citation id: " + expectedId);
                }
                continue;
            }

            boolean provenanceCovered = expectedEvidence.stream().allMatch(evidence -> matchingIds.stream()
                    .anyMatch(reference -> reference.covers(evidence)));
            if (provenanceCovered) {
                matched++;
                observations.add("citation reference structurally covers fixture provenance: " + expectedId);
            } else if (candidate.legacyCitationIds().contains(expectedId)) {
                observations.add("legacy article-id citation cannot satisfy required source version/span provenance: " + expectedId);
            } else if (!matchingIds.isEmpty()) {
                observations.add("citation id lacks required source version/span provenance: " + expectedId);
            } else {
                observations.add("missing expected citation reference: " + expectedId);
            }
        }
        return heuristic("citation_support_heuristic", matched, expectedIds.size(), observations);
    }

    private MetricAssessment faithfulness(AnswerEvalCase evalCase, AnswerCandidate candidate, MetricAssessment citations) {
        List<String> phrases = evalCase.expectation().requiredPhrases();
        boolean expectsCitations = !expectedCitationIds(evalCase).isEmpty();
        if (phrases.isEmpty() && !expectsCitations) {
            return notApplicable("faithfulness_heuristic", "Fixture has no claim/citation alignment assertions.");
        }

        int matchedPhrases = countContained(candidate.answer(), phrases);
        int total = phrases.size() + (expectsCitations ? 1 : 0);
        int matched = matchedPhrases + (expectsCitations && citations.status() == MetricStatus.PASS ? 1 : 0);
        List<String> observations = new ArrayList<>();
        observations.add("claim/evidence alignment is checked only against fixture phrases and citation provenance.");
        observations.addAll(missingPhraseObservations(candidate.answer(), phrases));
        if (expectsCitations && citations.status() != MetricStatus.PASS) {
            observations.add("citation-reference integrity check did not pass.");
        }
        return heuristic("faithfulness_heuristic", matched, total, observations);
    }

    private MetricAssessment requiredPhrases(String metric, String answer, List<String> requiredPhrases) {
        if (requiredPhrases.isEmpty()) {
            return notApplicable(metric, "Fixture has no required answer phrases.");
        }
        int matched = countContained(answer, requiredPhrases);
        return heuristic(metric, matched, requiredPhrases.size(), missingPhraseObservations(answer, requiredPhrases));
    }

    private MetricAssessment abstention(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        AnswerExpectation expectation = evalCase.expectation();
        if (expectation.answerability() != Answerability.ABSTAIN) {
            return notApplicable("abstention_heuristic", "Fixture expects an answer rather than abstention.");
        }
        boolean phraseFound = expectation.allowedAbstentionPhrases().stream()
                .anyMatch(phrase -> contains(candidate.answer(), phrase));
        boolean hasCitation = candidate.hasAnyCitation();
        List<String> observations = new ArrayList<>();
        if (!phraseFound) {
            observations.add("answer did not contain an allowed fixture abstention phrase.");
        }
        if (hasCitation) {
            observations.add("abstention candidate emitted citations despite no-evidence fixture.");
        }
        return heuristic("abstention_heuristic", phraseFound && !hasCitation ? 1 : 0, 1, observations);
    }

    private MetricAssessment temporal(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        List<String> markers = evalCase.expectation().requiredTemporalMarkers();
        if (markers.isEmpty()) {
            return notApplicable("temporal_heuristic", "Fixture has no required temporal disclosure markers.");
        }
        int phraseMatches = countContained(candidate.answer(), markers);
        List<EvidenceReference> expectedEvidence = evalCase.expectation().requiredEvidence().isEmpty()
                ? evalCase.evidence() : evalCase.expectation().requiredEvidence();
        Set<String> versions = expectedEvidence.stream().map(EvidenceReference::sourceVersion)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean versionsMatch = versions.isEmpty() || versions.stream().allMatch(version -> candidate.citationReferences().stream()
                .anyMatch(citation -> version.equals(citation.sourceVersion())));
        int total = markers.size() + (versions.isEmpty() ? 0 : 1);
        int matched = phraseMatches + (versionsMatch && !versions.isEmpty() ? 1 : 0);
        List<String> observations = new ArrayList<>(missingPhraseObservations(candidate.answer(), markers));
        if (!versions.isEmpty() && !versionsMatch) {
            observations.add("citation references did not carry every fixture source version: " + versions);
        }
        return heuristic("temporal_heuristic", matched, total, observations);
    }

    private MetricAssessment adversarial(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        AnswerExpectation expectation = evalCase.expectation();
        if (expectation.forbiddenPhrases().isEmpty() && expectation.requiredSafeResponsePhrases().isEmpty()) {
            return notApplicable("adversarial_heuristic", "Fixture has no adversarial-response assertions.");
        }
        List<String> observations = new ArrayList<>();
        int safeMatches = countContained(candidate.answer(), expectation.requiredSafeResponsePhrases());
        List<String> presentForbidden = expectation.forbiddenPhrases().stream()
                .filter(phrase -> contains(candidate.answer(), phrase))
                .toList();
        if (!presentForbidden.isEmpty()) {
            observations.add("answer contains forbidden fixture phrase(s): " + presentForbidden);
        }
        observations.addAll(missingPhraseObservations(candidate.answer(), expectation.requiredSafeResponsePhrases()));
        int total = expectation.requiredSafeResponsePhrases().size() + (expectation.forbiddenPhrases().isEmpty() ? 0 : 1);
        int matched = safeMatches + (presentForbidden.isEmpty() && !expectation.forbiddenPhrases().isEmpty() ? 1 : 0);
        return heuristic("adversarial_heuristic", matched, total, observations);
    }

    private MetricAssessment authorization(AnswerEvalCase evalCase, AnswerCandidate candidate) {
        List<String> forbiddenIds = evalCase.expectation().forbiddenCitationIds();
        if (forbiddenIds.isEmpty()) {
            return new MetricAssessment("authorization_leakage_heuristic", MetricStatus.NOT_APPLICABLE, 0.0,
                    EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC, true, AUTHORIZATION_HEURISTIC_CAVEAT,
                    List.of("Fixture has no forbidden evidence identifiers."));
        }
        Set<String> emittedIds = new LinkedHashSet<>(candidate.legacyCitationIds());
        candidate.citationReferences().stream().map(CitationReference::articleId).forEach(emittedIds::add);
        List<String> exposed = forbiddenIds.stream().filter(emittedIds::contains).toList();
        List<String> observations = exposed.isEmpty()
                ? List.of("No fixture-forbidden citation id was emitted.")
                : List.of("candidate emitted fixture-forbidden citation id(s): " + exposed);
        return new MetricAssessment("authorization_leakage_heuristic", exposed.isEmpty() ? MetricStatus.PASS : MetricStatus.FAIL,
                exposed.isEmpty() ? 1.0 : 0.0, EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC, true,
                AUTHORIZATION_HEURISTIC_CAVEAT, observations);
    }

    private static List<String> expectedCitationIds(AnswerEvalCase evalCase) {
        Set<String> ids = new LinkedHashSet<>(evalCase.expectation().requiredCitationIds());
        evalCase.expectation().requiredEvidence().stream().map(EvidenceReference::articleId).forEach(ids::add);
        return List.copyOf(ids);
    }

    private static List<EvidenceReference> expectedEvidence(AnswerEvalCase evalCase, String citationId) {
        List<EvidenceReference> explicit = evalCase.expectation().requiredEvidence().stream()
                .filter(evidence -> citationId.equals(evidence.articleId()))
                .toList();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return evalCase.evidence().stream()
                .filter(evidence -> citationId.equals(evidence.articleId()))
                .toList();
    }

    private static MetricAssessment heuristic(String metric, int matched, int total, List<String> observations) {
        double score = total == 0 ? 0.0 : (double) matched / total;
        MetricStatus status = matched == total ? MetricStatus.PASS : MetricStatus.FAIL;
        return new MetricAssessment(metric, status, score, EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC,
                true, HEURISTIC_CAVEAT, observations);
    }

    private static MetricAssessment notApplicable(String metric, String observation) {
        return new MetricAssessment(metric, MetricStatus.NOT_APPLICABLE, 0.0,
                EvaluationMethod.DETERMINISTIC_FIXTURE_HEURISTIC, true, HEURISTIC_CAVEAT, List.of(observation));
    }

    private static int countContained(String answer, List<String> phrases) {
        return (int) phrases.stream().filter(phrase -> contains(answer, phrase)).count();
    }

    private static List<String> missingPhraseObservations(String answer, List<String> phrases) {
        return phrases.stream()
                .filter(phrase -> !contains(answer, phrase))
                .map(phrase -> "missing fixture phrase: " + phrase)
                .toList();
    }

    private static boolean contains(String text, String phrase) {
        return text != null && phrase != null
                && text.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT));
    }
}
