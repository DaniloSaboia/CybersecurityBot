# Current main worktree: inspection and verification — 29 September 2026

## Snapshot boundary

The user-designated final application repository is `/Users/danilopalaciosaboia/Documents/GitHub/CybersecurityBot`. Its local `main` HEAD at inspection was `8d259eb8c46735681666bff6042fd99129651d97` (committed 13 September 2026), with additional modified and untracked local application files. The thesis is now housed in this repository; the former Maestri floor at `a66c615` and its `verification-run.md` are historical, not the source of the current screenshots or current test counts. No fetch or production deployment is asserted.

Committed `8d259eb` already contains leased and fenced durable ingestion, retained sources, document governance and lifecycle operations, policy-aware hybrid retrieval, immutable evidence references, evidence-view authorization, a graph assertion store (not graph-based retrieval), and synthetic evaluation fixtures. The local worktree additionally contains curated response playbooks, incident-oriented frontend surfaces, and an evidence-dependent generation fallback. The latter are not attributed to the unmodified commit.

Selected implementation definitions and UI assets inspected (literal locations retained here for reproducibility, not rendered in the thesis): `backend/src/main/java/com/sentinel/kms/ingest/DurableIngestQueue.java`, `backend/src/main/java/com/sentinel/kms/kb/KnowledgeLifecycleController.java`, `backend/src/main/java/com/sentinel/kms/kb/EvidenceViewService.java`, `backend/src/main/java/com/sentinel/kms/assistant/CitationResolver.java`, `backend/src/main/java/com/sentinel/kms/graph/GraphService.java`, `backend/src/main/java/com/sentinel/kms/ingest/FeedSyncService.java`, `backend/src/main/java/com/sentinel/kms/assistant/PlaybookGuidanceService.java`, `backend/src/main/java/com/sentinel/kms/seed/CuratedKnowledgePackLoader.java`, `backend/src/main/resources/knowledge/security-playbooks.json`, `frontend/src/App.jsx`, `frontend/src/navigation.css`, `frontend/src/views/KnowledgeBase.jsx`, `frontend/src/views/Ingest.jsx`, `frontend/src/views/Assistant.jsx`, `frontend/src/views/EvidenceDialog.jsx`, `frontend/e2e/fixtures/mocked-contract.js`, and `frontend/e2e/thesis-current-main.spec.js`. The generated source manifest records SHA-256 for readable source files; it does not inspect credentials or certify deployment.

## Executed checks on this local main worktree

| Check | Command and context | Observed result | Limit |
|---|---|---|---|
| Report-validation gate | `python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v` at repository root | 12 passed, exit 0 | Synthetic reports; no measured retrieval or model output. |
| Java backend | `mvn -q -DskipITs test` in `backend` | Exit 0; parsed 88 Surefire XML reports: 408 tests, 0 failures, 0 errors, 0 skipped. | Selected container-backed checks use isolated PostgreSQL; no hosted production or expert adjudication. |
| Frontend units | Node 24 via `npm exec --yes --package=node@24 -- ./node_modules/.bin/vitest run --reporter=dot` in `frontend` | Exit 0; 13 test files, 91 tests passed. | Component behavior in test DOM is not live backend authorization. |
| Production frontend bundle | `SENTINEL_REAL_STACK_E2E=1 npm exec --yes --package=node@24 -- npm run build` in `frontend` | Exit 0; Vite 8.2.2 transformed 133 modules; nonfatal bundle-size warning. | `SENTINEL_REAL_STACK_E2E=1` excludes repository-level Vite env files; no backend started. |
| Chromium suite | `CI=1 SENTINEL_REAL_STACK_E2E=1 THESIS_FIGURE_DIR=<absolute thesis figures dir> npm exec --yes --package=node@24 -- ./node_modules/.bin/playwright test -c playwright.config.js --project=chromium --workers=4 --reporter=list` in `frontend` | Exit 0; 17 passed, including one new figure-capture spec. | Smoke/navigation and intercepted HTTP-contract journeys; not a live model, real GoTrue, or a human study. |
| Targeted figure run | Same environment, `playwright test e2e/thesis-current-main.spec.js -c playwright.config.js --project=chromium --workers=1 --reporter=list` | Exit 0; one asserted screenshot journey passed. | HTTP and authentication are deterministic Playwright route fixtures. |

## Screenshot provenance

The current blue/slate interface was rendered from the current local main working tree (not the older red floor interface). The scripted browser signed in with a test-only intercepted GoTrue response, visited the Assistant, Knowledge Base and Ingestion views, sent a fixed question, opened an exact-evidence dialog, and asserted visible text and request counts. The single CVE, indexed-job count, P95 latency, cited answer, and passage were mock fixtures; the uploaded file did not produce the cited answer. The screenshots are not observations of human participants. Print captions must call this out.

- `figures/main-assistant-start-mocked.png` — sha256 `e4cd5b8ed0586c4547561c787e5feaceeafed90bf5b2fd784c290fd83c463ee6` (empty assistant and response-plan intake).
- `figures/main-knowledge-base-mocked.png` — sha256 `0e97f7bf37a7a96e63793bd6d0bf42d71ca0972fbbc694648b08fe3078dfec44` (library and playbook navigation).
- `figures/main-ingestion-mocked.png` — sha256 `35acac4d3483d1518b960b0a3ede93d20fe22a9d6f1a96cbead373c070892eae` (upload accepted in mock; fake indexed status and metrics).
- `figures/main-cited-answer-mocked.png` — sha256 `77cc1c5a3976ce8308af3d954efe8ea544d40846765d39db382466415e7b8c8a` (mocked cited answer and passage action).
- `figures/main-evidence-detail-mocked.png` — sha256 `6144e9d2b66322ceb69e1fd267f1c279c93291c27eb63f694218a84c2c38d112` (mocked evidence dialog crop).
- `figures/main-evidence-dialog-mocked.png` — sha256 `23dd8af566347e26a5355d2571a28f631f70e5336b89ffdc2adeaf66f28a39b3` (whole-screen dialog state).

The supplied 80-page Godinho dissertation was inspected for structure and presentation only. No case results, participant responses, identity fields, institution, acknowledgements, or bibliography entries from that example were copied into the Sentinel thesis. The two supplied SLR PDFs are byte-identical; the thesis literature chapter does not claim to have performed an SLR.

## Outstanding evidence

The researcher subsequently confirmed personally completing model comparison and human evaluation. Their actual methods and outcome records await transfer into the revised manuscript; the search-only/no-RAG/RAG conditions and participant arrangements must not be inferred from a proposed protocol. The earlier assertion of an unperformed participant study was incorrect and is superseded by this clarification. Software checks remain distinct from those empirical findings. Institutional title-page fields, personal acknowledgements, university formatting requirements, and any required disclosure of AI-assisted drafting remain to be confirmed before submission.
