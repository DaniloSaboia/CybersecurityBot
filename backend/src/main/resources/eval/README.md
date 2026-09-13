# Answer-level evaluation artifacts

`answer-eval-v1.json` is a synthetic, versioned regression fixture set. Every
case is `PENDING` external expert review; a deterministic pass must never be
reported as factual truth, citation support, faithfulness, correctness, safety,
authorization enforcement, absence of provider calls, or expert validation.

The dataset covers answerable, unanswerable, ambiguous, conflicting, stale,
temporal, multi-document, adversarial, and synthetic authorization-denied
scenarios. It requires immutable provenance
through `com.sentinel.kms.common.EvidenceReference`: article ID, revision ID,
revision number, evidence snapshot ID, source-span offsets, source version, and
revision status. Legacy article-ID citations are preserved as inputs but cannot
pass a fixture requiring that provenance. Upload source references are not
article citations and are never converted into article links by this harness.

Citation support, faithfulness, correctness, abstention, temporal disclosure,
adversarial response, and explicit denied-fixture citation checks are all
labelled deterministic heuristics. Token/phrase overlap is never proof of
truth, support, faithfulness, correctness, safety, or authorization. The
authorization heuristic only detects a fixture-forbidden citation ID; it does
not prove policy enforcement or rule out other disclosure.

## Offline saved-output comparison

Build first, then run the CLI with a dataset, saved answer outputs, and a report
destination:

```bash
cd backend
mvn -B -ntp -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/eval.classpath
java -cp "target/classes:$(cat target/eval.classpath)" \
  com.sentinel.kms.eval.OfflineBaselineEvaluationCli \
  --dataset src/main/resources/eval/answer-eval-v1.json \
  --answers /path/to/saved-answer-outputs.json \
  --report /path/to/answer-eval-report.json
```

The following is a shape illustration, not a runnable complete input. A real
input must contain every one of the dataset's case IDs exactly once in each of
the three baseline arrays; the CLI rejects missing or duplicate cases:

```json
{
  "schemaVersion": "1.0",
  "datasetId": "sentinel-answer-eval-v1",
  "baselines": {
    "RAG": [{"caseId": "answerable-single-evidence", "answer": "…", "citationReferences": [], "legacyCitationIds": [], "metadata": {}}],
    "SEARCH_ONLY": [],
    "NO_RAG": []
  }
}
```

The resulting JSON follows `answer-eval-report-v2.schema.json`, retains
per-answer latency/cost metadata, and adds a candidate SHA-256 binding without
including raw answers in the report. `answer-eval-report-v1.schema.json` is
retained as the legacy shape. The CLI makes no network/provider calls.

Saved outputs must have the exact dataset `schemaVersion` and `datasetId`, and
each baseline must contain every dataset case exactly once. Missing, duplicate,
or unexpected cases fail before scoring.

## External annotation import and scientific gate

Use `expert-annotation-bundle-v1.schema.json` and its empty
`expert-annotation-bundle-v1.template.json` to collect real review records. The
template is intentionally incomplete and cannot be imported until a review team
supplies exact SHA-256 values for the dataset and saved-output artifacts.

```bash
java -cp "target/classes:$(cat target/eval.classpath)" \
  com.sentinel.kms.eval.OfflineBaselineEvaluationCli \
  --dataset /approved/path/dataset.json \
  --answers /approved/path/saved-answer-outputs.json \
  --expert-annotations /approved/path/expert-annotations.json \
  --report /approved/path/answer-eval-report.json \
  --require-scientific-completeness
```

The import binds each review to raw dataset/output artifact hashes, baseline,
case, canonical candidate digest, and full immutable evidence tuple. It rejects
pending records, unknown fields, mismatched evidence/candidate digests,
duplicate reviewers, and reviewer/adjudicator overlap.

The scientific gate fails closed unless the sidecar has two distinct independent
qualified `ACCEPTED` dataset/rubric reviews and two for every reported
baseline/case, all seven labels, and a separate qualified adjudication whenever
reviews disagree or any label is `UNCERTAIN`. A complete review process does
not turn a `FAIL` label into a quality score.

## Optional provider execution

`HttpProviderAnswerSource` is a generic POST adapter, deliberately with no
endpoint, credentials, or automatic invocation. A caller must supply an
approved endpoint and `--enable-real-provider`; this repository does not run a
remote provider command. Provider-reported usage and cost are retained as
metadata, not guessed.

To make a provider result reviewable, explicitly choose a restricted local
capture path. The public report still carries only a digest:

```bash
java -cp "target/classes:$(cat target/eval.classpath)" \
  com.sentinel.kms.eval.ProviderEvaluationCli \
  --enable-real-provider --dataset /approved/path/dataset.json \
  --endpoint https://approved-provider.example/eval \
  --provider approved-provider --model approved-model \
  --answer-capture /restricted/path/provider-answer-capture.json \
  --report /approved/path/provider-eval-report.json
```

`--expert-annotations` requires `--answer-capture`, and
`--require-scientific-completeness` requires expert annotations. Missing
required sidecars are rejected before any provider call.

## External-review requirement

The importer validates declared IDs, timestamps, hashes, and structure only.
Reviewer identity, qualification, independence, consent, authorization-decision
evidence, data-handling approval, and study protocol must be verified outside
this repository. No analyst-study outcome, expert label, participant, provider
output, or production-quality claim is included here.
