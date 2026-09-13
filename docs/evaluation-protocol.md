# Evaluation and analyst-study protocol

## What this implementation can and cannot establish

Automated tests verify bounded software behavior. They do not establish that an answer is true,
that a citation entails a claim, that prompt injection is impossible, or that analysts work
more safely with the assistant. Synthetic fixtures and mock provider outputs are test inputs,
not measured model performance. The versioned answer dataset must retain pending review status
until qualified reviewers actually annotate it.

## Corpus and case preparation

1. Freeze a corpus snapshot, source revisions, extraction/chunking settings, embedding model,
   retrieval settings, and model identifiers. Preserve the original documents and passage IDs.
2. Use authorized, de-identified organizational cases or explicitly synthetic scenarios. Do not
   send confidential source material to a hosted provider without approval for that data class.
3. Include answerable, unanswerable, ambiguous, conflicting, superseded/stale, multi-document,
   and indirect-injection cases. Include cross-role/workspace cases with expected access denial.
4. Have two domain reviewers independently annotate reference claims, supporting passages,
   relevant dates, acceptable abstentions, harmful recommendations, and error severity.
5. Record reviewer identifiers, review dates, disagreements, adjudications, and annotation version.
   Do not silently replace pending labels with model-generated ratings.
6. Keep development and held-out test cases separate. Tune thresholds only on the development
   set, record that decision, and report held-out results without retroactive threshold changes.

## Controlled baseline comparison

Run search-only, no-RAG, and RAG against identical case IDs and corpus versions. Compare a local
model and a larger hosted model only where the data's access policy permits each provider.
Keep response budget, retrieval budget, temperature, prompt versions, and stopping rules fixed.
Store raw outputs before scoring. Missing calls and timeouts are recorded failures/missing data,
not fabricated answers or silently dropped cases. Store measured wall-clock latency and actual
usage/cost when supplied by the provider; unknown cost remains unknown, not zero.

Report retrieval recall/precision and source diversity separately from answer correctness,
claim support, citation accuracy, abstention, temporal correctness, and unsafe-action rate.
Automated string/structure checks are diagnostics, not substitutes for expert entailment review.
Run comparisons with and without optional reranking and graph retrieval if those paths are being
claimed as improvements. A graph that exists but was not used is not a graph-retrieval baseline.

## Analyst study (requires participants and approval)

- Obtain organizational/ethics approval and informed consent appropriate to the institution.
- Recruit representative analysts; pre-register inclusion criteria and experience strata.
- Choose sample size using a prospective power analysis with a defensible effect estimate.
  This repository does not invent a participant count or a significance result.
- Use a counterbalanced within-participant design with different matched case sets to limit
  learning effects. Randomize assignment and record the random seed and allocation.
- Compare ordinary search with assistant-supported work; optionally include a no-RAG condition.
- Keep tasks advisory and sandboxed. No production incident actions or offensive operations.
- Record task correctness, elapsed time, confidence before/after, evidence opened, overrides,
  unsupported advice accepted, harmful-action attempts, and reviewer-scored error severity.
- Predefine stopping rules for severe unsafe recommendations or data disclosure. Preserve the
  failed cases for analysis rather than removing them from reported results.
- Blind outcome reviewers to condition where feasible. Report uncertainty intervals, missing
  data, learning effects, and limits of generalization. Retain severe errors as a separate metric.

## Suggested study-record fields

`study_id, participant_pseudonym, experience_band, case_id, corpus_version, condition,
model_id, configuration_id, started_at, elapsed_ms, decision, confidence_before,
confidence_after, evidence_opened, unsupported_advice_accepted, error_severity,
reviewer_id, review_status, review_notes`

Keep participant identities and consent records outside the application repository. Store the
study records in an access-controlled location with a defined retention/deletion policy.

## CI and live evaluation operations

- `python3 -m unittest discover -s scripts/ci -p 'test_*.py'` verifies the report gates.
- `python3 scripts/ci/quality_gate.py retrieval report.json` rejects missing/empty/malformed
  reports, inconsistent totals, failed cases, and low aggregate metrics. Default retrieval
  budgets are recall 0.8, MRR 0.6 and nDCG 0.6; they are starting engineering thresholds, not
  expert-calibrated clinical/security standards. Override explicitly when justified.
- `python3 scripts/ci/quality_gate.py junit backend/target/surefire-reports/TEST-*IntegrationTest.xml`
  rejects skipped, missing, empty, or failing integration reports.
- The nightly live retrieval job is opt-in through repository variable `ENABLE_LIVE_EVAL=true`.
  Configure `EVAL_BASE_URL` to a dedicated HTTPS evaluation deployment and the secret
  `EVAL_AUTH_TOKEN` to an authorized evaluation account. The job does not boot/seed an application
  against a production database. Missing configuration in an enabled job is a failure.
- Reports are retained as workflow artifacts. They may include evaluation questions and source
  IDs, so keep the evaluation environment/corpus appropriate for the repository's visibility.

## Acceptance boundary

A completed implementation can supply reproducible runners, real security tests, and review
forms. Closing expert-review or analyst-effectiveness tasks requires actual annotations and
study results. Until then, those findings remain open in the Maestri notepad.
