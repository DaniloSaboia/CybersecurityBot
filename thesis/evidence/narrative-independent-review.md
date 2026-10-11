# Independent read-only review — current 65-page draft

## Scope and disposition

Reviewed the current canonical dissertation at `/Users/danilopalaciosaboia/Documents/GitHub/CybersecurityBot/thesis`, including all five chapters, frontmatter, appendices, diagram/screenshot content and the **post-layout `build/main.pdf`**. The superseded named reading copy was not used.

**Reviewed PDF:** 65 pages; SHA-256 `af3c562dc297640ad5068c97dbc0f2df9c18ac87509f3813307641af5f5652e3`. The copied PDF, source snapshot, manifest, extraction and renders are in this directory. Source/PDF hashes still matched the canonical files at the end of the recheck. All page references below are **physical PDF pages**, not printed numbers. This report supersedes the earlier 71-page review in the parent scratch directory.

**PASS with minor corrections for the requested narrative-revised DRAFT.** No wholesale rewrite blocker was found. The pass covers title/narrative, chapter boundaries, voice, question consistency and the separation of technical checks from empirical effectiveness; it does not certify untransferred empirical methods. One methodological adjective needs qualification (M1).

**FAIL submission readiness / empirical completeness**, as expected and already declared. The completed model comparisons and human evaluation are accepted as performed; the absence of transferred records is not evidence to the contrary.

## Verified passes

- **Exact title** preserved in `metadata.tex:2`, PDF metadata and both covers (PDF 1, 3); English body and bilingual abstracts remain aligned with the knowledge-reuse/incident-response objective.
- **Chapter boundaries:** Chapters 1–2 contain no Sentinel implementation, stack or source-inspection narrative. Chapter 3 connects the actual system to the research problem without code filenames, repository paths, commits or branches.
- **Voice and consistency:** Only RQ1–RQ3 appear; their meanings and F1–F4/N1–N3 stay consistent. No reader-facing third-person instruction to the author/student was found. Non-rendering editorial comments are not counted as prose leakage.
- **Evidence distinction:** `04-evaluation.tex:7,46–79` (PDF 45, 47–48) keeps completed research, engineering checks and dated provider compatibility separate. The 12/408/91/17 checks are not summed or represented as participant/sample counts. Exact provider IDs/dimensions match the retained dated record.
- **No invented study outcome:** No numerical quality/efficiency gain, participant count, model ranking, instrument score, randomisation, recruitment or statistical significance is fabricated. Chapter 5 remains a bounded architectural/technical synthesis (`05-conclusion.tex:6–10,15–28`; PDF 51–53).
- **Illustrations:** `03-system.tex:158–185` (PDF 42–43) labels actual-frontend screenshots with mocked responses and demonstration data. Appendix A is explicitly constructed, and Appendix B explicitly distinguishes optional layouts from administered instruments (`appendices.tex:3,65,90–107`; PDF 55, 59–61).

## Remaining minor edits / support follow-ups

### M1 — “Controlled” exceeds the confirmed methodological detail

**Location:** `chapters/04-evaluation.tex:7,81`; PDF **45, 48** (also the section title in the contents, PDF 12).

“We completed **controlled** model comparison” and “Controlled comparison and human evaluation” assert an experimental qualifier beyond the supplied confirmation of completed model comparisons. The actual comparison conditions have not been transferred. The previous occurrence in the limitations paragraph was already removed by the layout revision.

**Action:** Use **“We completed model comparisons and human evaluation …”** and **“Model comparisons and human evaluation”**, unless the researcher explicitly confirms the controls. Preserve the completed tense; do not recast the research as proposed or unperformed. This is a small wording correction, not a demand for a new study.

### M2 — Figure 3.2 overgeneralises manual administrative approval

**Location:** `chapters/03-system.tex:144–145`, Figure 3.2; PDF **41**. Diagram text source: `figures/generate_diagrams.py:97`.

The first box says “Administrative approval; active lifecycle”, while the caption describes a general “governed article's path”. The prose correctly permits trusted-producer admission without manual review (`03-system.tex:81–83`, PDF 37; `appendices.tex:30`, PDF 56).

**Action:** The smallest change is **“An editor-managed article's path …”** in the caption. Alternatively, distinguish approved/trusted admission in the box. No application change is needed.

### M3 — Attribute reciprocal-rank fusion

**Location:** `chapters/03-system.tex:105–110`; PDF **38–39**.

The equation and constant 60 match the retained implementation-inspection record (`evidence/implementation-audit.md:41`, A25), and the text correctly avoids treating the score as probability/confidence. However, there is no primary scholarly attribution for the named method/equation in the prose or two bibliographies.

**Action:** Add a verified primary attribution when completing the bibliography. Keep 60 identified as a configured constant, not an empirically optimised value. This is an attribution improvement, not a finding that the equation is wrong; no new bibliographic metadata has been invented here.

### M4 — Corroborate or narrow one MoRSE method description

**Location:** `chapters/02-literature.tex:39`; PDF **28**.

The retained abstract (`evidence/abstracts/ref001.txt:4`) supports the 600-question evaluation, heterogeneous retrieval and positive comparison. It does not itself document the general/multi-document/vulnerability subdivision, expert-validated reference answers or model-assisted assessment. No retained MoRSE full text was located in the supplied local evidence/scratch locations.

**Action:** Retain the already-checked primary passage in the internal evidence record if available elsewhere; otherwise narrow those particulars to what the existing abstract supports. This is a **local support gap**, not evidence that the description is false. No network literature synthesis was undertaken.

## Current layout — PASS for obvious defects

All **65 current pages** were rendered and reviewed in overview, with critical pages also inspected at page size.

- The former one-/four-line chapter-ending spillovers are gone: Chapters 1, 3 and 4 now finish on PDF **24, 44 and 50** respectively.
- The acronym list fits PDF **19**, the short List of Figures caption is present, and citation spacing is corrected.
- No new overlap, clipping, off-page table/equation, missing figure or broken chapter transition was found. The current log contains no overfull, underfull, undefined-reference or missing-glyph match. The validator was **not** rerun.
- The earlier appendix-promise inconsistency is **resolved**: `01-introduction.tex:60` now accurately promises supporting scenarios and evaluation-record definitions, not administered research instruments.
- **Optional polish only:** diagram labels remain small (Figure 3.2 body text scales to about 5.9 points); some long identifiers/URLs are letter-spaced by justification (PDF 48, 65); Appendix B ends with a short seven-line tail on PDF **62**. These are readability/pagination refinements, not new layout blockers. Blank versos follow the `openright` book layout.

## Known submission blockers — not failures of this narrative task

1. **Actual empirical methods/outcomes are not integrated.** `04-evaluation.tex:81–124,154–170` (PDF **48–50**) explains interpretation but does not yet present the completed studies' actual conditions, denominators, scoring or outcomes. Transfer original records or clearly labelled aggregates and the available real methodological details; reconcile RQ3 in `05-conclusion.tex:10–12,28–29` (PDF **51–53**) and findings in `frontmatter.tex:44–62` (PDF **7, 9**). Do not infer controls, participant arrangements, consent/approval, instruments or results from the illustrative layouts.
2. **Personal/institutional placeholders remain.** `metadata.tex:3–8` and `frontmatter.tex:39`; PDF **1, 3, 5**. Confirm the author, programme, university/department, supervisors, submission date and acknowledgements against actual submission requirements, not the structural example.
3. **Delivery must remain labelled draft.** The numerical-integration instructions are non-rendering. Keep the distributed artifact/covering message identified as a narrative-revised draft pending empirical integration; repeated evidence-denial prose is unnecessary.

## Selected support checks and limits

- The limited requirements-interview statement is supported by the original research account (`thesis-rewrite-20260929/original-thesis.txt:357–362`); no session count, coding method or outcome-study instrument is inferred.
- Retained NIST primary text supports maintenance, CSF-function and playbook rationale. Retained Sentence-BERT and original RAG primary texts support the representations/training distinction. RAGAs/ARES retained primary extracts support statement-based faithfulness and judges/PPI; the thesis does not pretend these were administered instruments in this study.
- `docs/openrouter-free-models.md:27–45` supports provider identifiers/dimensions; `evidence/current-main-verification.md:15–20` supports the separate engineering counts. These observations remain bounded, not promoted into model-quality or human-benefit findings.
- No thesis/application file was edited. No writing validator, application test suite, production/model call or network lookup was run. Implementation support was sampled from retained records, not re-audited across the application. Scratch review artifacts and a reusable read-only-review procedural note in the loaded skill were the only writes outside the existing thesis.
