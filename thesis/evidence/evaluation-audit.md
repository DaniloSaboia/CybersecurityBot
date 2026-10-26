# Chapter 4 factual audit

## Scope and provenance

- Repository: `/Users/danilopalaciosaboia/Documents/GitHub/.maestri/floors/CybersecurityBot--escrita`.
- Audited Git HEAD: `a66c615369db1f7c4eb19ccab0d51613ed6a3b75`; inspected working-tree contents govern the claims. No assertion that concurrent thesis work is part of that commit.
- Exact execution timestamp, interpreter, argv, input SHA-256 values, merged stdout/stderr, and exit codes are retained in `evaluation-checks.txt`.
- This audit writes only its assigned chapter and two evidence files. Application sources, datasets, review statuses, workflows, configuration, and production systems were not changed.
- No external citations were introduced. In particular, `ref039` and `ref040` were not assumed to map to any source.

## Sources inspected and supported claims

| Source | Finding and evidential boundary |
|---|---|
| `README.md` | General application and retrieval architecture, eval endpoint, provider options, and test instructions. Its statement that a nightly workflow reruns evaluation must be qualified by the actual opt-in condition in the workflow. Documentation is not run evidence. |
| `docs/evaluation-protocol.md` | Explicit separation of fixtures and measured outcomes; frozen corpus and configuration; independent reviews; controlled baselines; approved, sandboxed analyst study; configurable engineering thresholds; opt-in live evaluation. |
| `backend/src/main/resources/eval/README.md` | Synthetic fixtures, heuristic caveats, saved-output CLI, immutable evidence, v2 report shape, external annotation bindings, and explicit provider opt-in. The README says reviewer credentials and independence need external verification. |
| `backend/src/main/resources/eval/answer-eval-v1.json` | Parsed directly: dataset ID `sentinel-answer-eval-v1`, schema `1.0`, nine cases. Overall review and all nine case reviews are `PENDING`; reviewer IDs and timestamps are null. No measured answer output or expert annotation is inferred from fixture expectations. |
| `backend/src/main/resources/eval/goldenset.json` | Parsed directly: ten retrieval questions, expected article IDs, optional category and severity restrictions. These are cases, not measured successful retrievals. |
| `backend/src/main/java/com/sentinel/kms/eval/RetrievalMetrics.java` | Recall, reciprocal rank, and binary nDCG over ranked IDs. Recall and nDCG return zero for empty relevant sets. Static metric helpers do not deduplicate; the caller does. |
| `backend/src/main/java/com/sentinel/kms/eval/EvalService.java` | Requires an authenticated subject, applies a maximum case limit, invokes hybrid search with filters, deduplicates article IDs, aggregates metrics, and defines per-case pass as recall greater than zero. Chunk retrieval precedes deduplication; this is not a generated-answer evaluation nor a full conversational/reranking evaluation. |
| `backend/src/main/java/com/sentinel/kms/eval/AnswerEvaluationService.java` | Seven checks are explicitly deterministic heuristics. Phrase occurrence, required evidence tuple coverage, and absence of forbidden citation IDs do not establish truth, entailment, access control, or no provider disclosure. |
| `backend/src/main/java/com/sentinel/kms/eval/BaselineComparisonHarness.java` | Offline candidate scoring for declared baselines, candidate digests, heuristic pass rates, and default missing-annotation status. Input provenance and controlled generation cannot be established merely by assigning a baseline name. |
| `backend/src/main/java/com/sentinel/kms/eval/ProviderEvaluationHarness.java` | No execution without explicit opt-in and a supplied source. Calls supplied source over baseline/case cells; rejects null candidates. Not invoked. |
| `backend/src/main/java/com/sentinel/kms/eval/ScientificCompletenessGate.java` | Requires a validated annotation sidecar, two independent accepted qualified dataset/rubric reviews, candidate reviews bound to digests/evidence, and separate adjudication for disagreement or uncertainty. Checks declared structure, not actual reviewer credentials. Review completeness does not imply positive quality labels. |
| `scripts/ci/quality_gate.py` | Rejects empty/inconsistent retrieval reports, duplicate IDs, false/inconsistent pass totals, and non-finite/out-of-range/below-threshold aggregates. Defaults: recall 0.8, MRR 0.6, nDCG 0.6. Does not recalculate aggregate scores from rankings or authenticate corpus identity. JUnit gate checks actual testcase children as well as summary counts and required suite names. |
| `scripts/ci/test_quality_gate.py` | Standard-library test file, temporary JSON/XML fixtures, subprocess invocation of the gate. Safe to execute without services. Synthetic report acceptance is not live retrieval success or actual integration-test execution. |
| `scripts/ci/smoke_db_bootstrap.py` | Starts an isolated Docker pgvector container and checks migration/bootstrap tables. Read but not executed because this task's execution scope was offline gate tests, not service startup. |
| `.github/workflows/eval.yml` | Offline gate/unit jobs plus a live job conditional on `ENABLE_LIVE_EVAL == 'true'`; requires configured evaluation URL/token and HTTPS; stores report artifacts. No workflow run or repository variable/secret was queried. |
| `.github/workflows/ci.yml` | Declares Docker bootstrap, Maven verification, required executed integration-suite gate, frontend tests/build, and browser journeys. Declared steps are not evidence of a successful current run. |

The delegated implementation context establishes that the graph is assertion-only, not retrieval. A targeted search found no graph integration reference in the assistant package (the only textual matches were the substring in “paragraph”); `EvalService` directly calls hybrid search rather than a graph service. This is sufficient for the chapter's explicit refusal to claim a graph-retrieval baseline, not a complete graph subsystem audit.

## Programmatically verified fixture inventory

Answer case types: `ANSWERABLE` 2; `UNANSWERABLE`, `AMBIGUOUS`, `CONFLICTING`, `STALE`, `MULTI_DOCUMENT`, `ADVERSARIAL`, and `AUTHORIZATION` each 1. Temporal disclosure is an answerable case rather than a separate case type.

Exact answer case IDs:

1. `answerable-single-evidence`
2. `unanswerable-empty-evidence`
3. `ambiguous-missing-scope`
4. `conflicting-sources`
5. `stale-version-must-abstain`
6. `temporal-current-version-disclosure`
7. `multi-document-response`
8. `adversarial-untrusted-source`
9. `authorization-denied-reference`

The stale fixture asks which patch version was recorded in a superseded snapshot but expects abstention. The chapter flags this as a rubric issue needing domain review, not an application bug established by testing. The dataset contains synthetic references and expectations rather than a validated source corpus and independently annotated semantic judgments.

## Actual execution

Executed with Python 3.9.6:

`python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v`

The evidence file records the exact resolved interpreter path. The subprocess environment was restricted to `PATH` and `PYTHONDONTWRITEBYTECODE`; no `.env` was read, no secrets were inspected, and no application/provider/database was started. The inspected test and gate modules use local standard-library operations only.

**Result: 12 tests ran in 1.182 seconds, OK, exit code 0.** No skips or failures were reported. This is bounded report-validator fixture evidence. The run does not execute the Java metrics, answer heuristic implementation, importer, provider harness, database integration tests, or analyst protocol. Test duration is not model latency. Fixture values 0.9/0.8/0.8 are explicitly not benchmark results.

## Scientific conclusions intentionally withheld

- No measured live retrieval recall, MRR, nDCG, precision, or source-diversity result.
- No actual comparison of search-only, no-RAG, and RAG outputs.
- No local-versus-hosted model ranking, latency, token-use, or cost result.
- No expert-reviewed claim support, citation accuracy, answer correctness, temporal correctness, or unsafe-action rate.
- No participant recruitment, consent, demographics, sample size, task completion improvement, or significance result.
- No claim that adversarial robustness or authorization enforcement follows from a phrase/identifier fixture.
- No graph-retrieval benefit or active graph condition.
- No adoption of existing build/test artifacts as newly verified execution.

## Chapter verification and limitation

- Required chapter title and `ch:evaluation` label are present; implementation appendix uses `\ref{app:implementation}`; repository paths use `\path`; no citation keys were inserted.
- Tool-counted chapter length: 2,363 whitespace tokens; approximately 2,373 prose words under a simple count excluding equation blocks and stripping command names. Both meet the 2,000–3,000 word requirement. Word-count conventions can vary around identifiers and mathematical notation.
- Static brace balance is zero; begin/end environments are balanced for equations, cases, table, and tabularx.
- An isolated compile was attempted with Tectonic `--only-cached --untrusted`, a report-class wrapper, the supplied thesis packages, chapter counter 3, and an explicit dummy appendix label. It failed before chapter processing because cached `tectonic-format-latex.tex` was unavailable. Exit code 1; no PDF was produced. Exact output is appended to `evaluation-checks.txt`.
- No network resource fetch was enabled to bypass that offline compile limitation. The parent must compile the integrated thesis with a provisioned TeX installation and verify pagination, table fit, references, and typography. No claim of successful LaTeX compilation is made.
