# Current-implementation revision audit

## Scope and outcome

Only `thesis/chapters/03-system.tex` and `thesis/chapters/04-evaluation.tex` were edited, using V4A patches. This audit is a new evidence file. No application code, dataset, prior evidence file, or other existing document was edited. The repository already contained extensive modified/untracked implementation work; claims refer to inspected working-tree definitions, not a released revision or deployed database.

The chapters already excluded measured effectiveness claims. Revisions correct narrower implementation mismatches and make remaining empirical work explicit. No Godinho, ASTRA, or template-dependence marker was present in these chapters; post-edit checks also found none. No example PDF was used as implementation or methodological evidence.

## Source-grounded corrections

Paths below are relative to the repository; Java paths beginning `backend/.../` expand to `backend/src/main/java/com/sentinel/kms/`.

- **Publication semantics:** `backend/.../kb/KnowledgeLifecycleService.java:128-139,167-233` shows ordinary approval and supersession require a pending-review candidate, administrator authority, and no self-approval. Rollback instead creates fresh revision/evidence identities from a previously published target and publishes without a new pending-review cycle. Chapter 3 no longer implies supersession selects an already approved successor or that rollback necessarily follows ordinary review.
- **Scope and scores:** `backend/.../vectorstore/SpringAiEmbeddingIndexService.java:485-570,578-681` applies category/severity predicates in both SQL legs, using article fields; minimum similarity applies only to the vector leg. `backend/.../vectorstore/HybridRanker.java:19-51` sorts by RRF with constant 60 but returns the retained branch score. These are not probability estimates or measured improvements.
- **Assistant versus hybrid-search scope:** `backend/.../assistant/RagService.java:312-353,361-481,634-674` supplies literal-question IOC matches separately, without the hybrid branch's category/severity arguments. `backend/.../kb/IocLookupService.java:71-83,102-150` binds an indicator occurrence in approved evidence; the assistant constructs a summary with article fields rather than quoting that occurrence alone. The citation span therefore does not by itself support every field in the summary.
- **Generation and exposure boundary:** `backend/.../assistant/RagService.java:168-228,243-276,312-332,504-525` implements literal-grounding abstention, later rewriting/reranking, final reauthorization, and buffered SSE disclosure. `backend/.../vectorstore/SpringAiEmbeddingIndexService.java:504-530` can already embed the question before empty grounding is known, despite stronger wording in a RagService comment. Chapter 3 retains that caveat. `backend/.../assistant/PromptBuilder.java:37-81,115-181` caps the source section, not the whole prompt; `RagService.java:65-99,258-263` uses a separate natural-language SSE system prompt, unlike JSON chat.
- **Evaluation is a component check:** `backend/.../eval/EvalService.java:85-111,115-141` passes the evaluator subject to hybrid search, removes blank article IDs, deduplicates articles, and passes a case for recall greater than zero. It does not run the assistant's IOC/rewrite/rerank/post-retrieval resolution pipeline. `backend/.../eval/RetrievalMetrics.java:19-65` implements recall, reciprocal rank, and binary nDCG; precision remains proposed.
- **Operational grants:** `backend/.../eval/EvalController.java:36-49` calls `backend/.../kb/KnowledgeAccessService.java:219-257` before evaluation. `supabase/migrations/20260905070000_eval_authorization.sql:19-72` requires active membership and dataset-view/execution grants in the same workspace. Such grants do not alter document-read predicates, and the evaluator does not filter expected relevance IDs to that subject.
- **Comparison adapter limitation:** `backend/.../eval/OfflineBaselineEvaluationCli.java:63-120` requires exact case coverage for every baseline. `backend/.../eval/ProviderEvaluationCli.java:31-52` explicitly gates provider execution. `backend/.../eval/HttpProviderAnswerSource.java:58-90` sends fixture evidence with every baseline name, including NO_RAG, to a caller-specified endpoint. It neither directly invokes RagService nor enforces no-context baseline isolation. Chapter 4 now requires independent endpoint verification before a controlled comparison can be claimed.
- **Research-question coherence:** `thesis/chapters/01-introduction.tex:29-45` was read after the concurrent introduction revision. Chapter 4 now maps RQ1 to identity/control traces, RQ2 to runtime boundaries, RQ3 to threat-control limitations, and RQ4 to evaluation evidence. Retrieval/answer/analyst effectiveness questions and the analyst study remain proposed extensions.

## Other inspected boundaries retained

Additional literal source paths for the machine-readable manifest (the corresponding observations above use the declared Java-package abbreviation):

- `backend/src/main/java/com/sentinel/kms/eval/HttpProviderAnswerSource.java`
- `backend/src/main/java/com/sentinel/kms/eval/OfflineBaselineEvaluationCli.java`
- `backend/src/main/java/com/sentinel/kms/eval/ProviderEvaluationCli.java`
- `backend/src/main/java/com/sentinel/kms/eval/EvalController.java`
- `backend/src/main/java/com/sentinel/kms/assistant/PromptBuilder.java`
- `backend/src/main/java/com/sentinel/kms/kb/IocLookupService.java`
- `backend/src/main/java/com/sentinel/kms/vectorstore/HybridRanker.java`
- `backend/src/main/java/com/sentinel/kms/ingest/TextExtractor.java`
- `backend/src/main/java/com/sentinel/kms/ingest/Chunker.java`

- Database-authoritative classification: `backend/.../common/KnowledgePolicy.java:9-49`; current-only passage viewing: `backend/.../kb/EvidenceViewService.java:23-49,68-125`.
- Durable ingest definitions/usages: `backend/.../ingest/IngestService.java:139-237,242-265`; `JobProcessor.java:103-199`; `DurableIngestQueue.java:45-153`; `TextExtractor.java:18-49`; `Chunker.java:21-41`. Retention and claim fencing are not exactly-once provider execution.
- Trusted publication/outbox: `backend/.../kb/TrustedArticleRegistrationService.java:69-173`; `KnowledgeArticleReindexWorker.java:56-68`; `backend/.../ingest/NvdConnectorService.java:60-84`. Immediate sync is not completed embedding or continuous upstream revision tracking.
- Graph boundaries: `backend/.../graph/GraphController.java:16-30` exposes creation and individual reads, while `GraphService.java:32-90,137-187` binds governed evidence and distinguishes inferred provenance. The inspected assistant path contains no graph retrieval integration.
- Hosting/security: `docker-compose.yml:14-50,69-82`; `backend/src/main/resources/application.yml:12-14,64-95,104-106`; `backend/.../config/SecurityConfig.java:51-76,105-126`. Configuration is not deployed-state verification.
- Evidence UI identity and focus handling: `frontend/src/views/EvidenceDialog.jsx:23-53,80-151`. No browser execution was performed.
- Heuristic limits and review completeness: `backend/.../eval/AnswerEvaluationService.java:11-23,25-53,182-197`; `ScientificCompletenessGate.java:22-78,147-183`. Structural reviews do not authenticate real reviewer independence or establish quality. Programmatic JSON inspection confirmed 10 retrieval fixtures, 9 answer fixtures (2 answerable and one each of the other seven types), and PENDING dataset/all-case review records with null reviewer and timestamp.

## Executed offline validator check

Working directory: `/Users/danilopalaciosaboia/Documents/GitHub/.maestri/floors/CybersecurityBot--escrita`.

A Python subprocess launched the same interpreter as its parent with arguments:

```text
-B -m unittest discover -s scripts/ci -p test_quality_gate.py -v
```

The child environment was explicitly replaced with `PATH=/usr/bin:/bin` and `PYTHONDONTWRITEBYTECODE=1`. The inspected test file imports only standard-library modules and invokes the local quality gate using temporary JSON/XML reports (`scripts/ci/test_quality_gate.py:1-25,27-95`). No application, database bootstrap, provider CLI, network client, browser, or Maven suite was launched. This is credential isolation by explicit environment replacement, not a network sandbox claim.

Actual combined output:

```text
test_accepts_executed_integration_report (test_quality_gate.QualityGateTest) ... ok
test_accepts_healthy_retrieval (test_quality_gate.QualityGateTest) ... ok
test_rejects_duplicate_case_ids (test_quality_gate.QualityGateTest) ... ok
test_rejects_empty_junit_report (test_quality_gate.QualityGateTest) ... ok
test_rejects_empty_report (test_quality_gate.QualityGateTest) ... ok
test_rejects_false_case_even_with_claimed_pass_total (test_quality_gate.QualityGateTest) ... ok
test_rejects_inconsistent_totals (test_quality_gate.QualityGateTest) ... ok
test_rejects_junit_failure_even_if_summary_is_wrong (test_quality_gate.QualityGateTest) ... ok
test_rejects_nan (test_quality_gate.QualityGateTest) ... ok
test_rejects_regression (test_quality_gate.QualityGateTest) ... ok
test_rejects_skipped_integration_report (test_quality_gate.QualityGateTest) ... ok
test_requires_every_named_integration_suite (test_quality_gate.QualityGateTest) ... ok

----------------------------------------------------------------------
Ran 12 tests in 1.231s

OK
EXIT_CODE=0
```

The elapsed time is validator-suite duration, not model latency. The XML “executed integration” case is synthetic; the retrieval numbers are test inputs. These tests exercise declared-result validation only (`scripts/ci/quality_gate.py:19-66,79-83`), not observed retrieval effectiveness, real database authorization, or answer safety.

Validator input SHA-256 values computed from local bytes:

```text
923e21900f9c1a14bf957f0465a9cb121a2575081c3bd795404efae6410ea7f9 scripts/ci/test_quality_gate.py
fb71c27d2586bdeb3cf9bc4780230a9436dff1d2a5f78461bb1d9cfdb1576265 scripts/ci/quality_gate.py
```

## Document verification and remaining limits

A read-only Python static check verified the original chapter labels, balanced begin/end environments, resolution of chapter references and citation keys against the current thesis sources, and absence of prohibited dependence markers. Actual output:

```text
thesis/chapters/03-system.tex labels=['ch:system', 'tab:system-requirements', 'fig:sentinel-architecture'] refs_resolve=True citations_resolve=True
thesis/chapters/04-evaluation.tex labels=['ch:evaluation', 'tab:evaluation-status'] refs_resolve=True citations_resolve=True
STATIC_CHECK_ERRORS=[]
```

The static checker exited zero. It is not a TeX compiler or visual layout review. The parent agent owns the combined thesis build and frontmatter verification. No Java/database/browser regression pass, deployed migration state, expert annotation, provider performance, or analyst outcome is claimed by this revision. Existing results and prior build artifacts were not counted as newly executed evidence.
