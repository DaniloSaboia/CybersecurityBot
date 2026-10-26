# Knowledge management and machine learning for incident response

Exact title: **Sistema inteligente de gestão de conhecimento para resposta a incidentes de Cyber segurança com integração de machine learning**.

The manuscript has an English body and bilingual abstracts. Its argument concerns knowledge capture, maintenance and reuse during incident response, with machine learning supporting retrieval and source-based guidance. Implementation detail begins in Chapter 3. The supplied Godinho dissertation informs academic structure and narrative, not this project's identity, institutional requirements or findings. The writing lecture and thesis guidance inform the argument and presentation; the two identical SLR teaching PDFs supply methodological guidance, not a search or screening study conducted for this dissertation.

The canonical application and thesis are in `/Users/danilopalaciosaboia/Documents/GitHub/CybersecurityBot`. Source history and verification provenance belong only in internal evidence records. Reader-facing prose, captions and diagrams must not contain code filenames, paths, or Git history. This revision replaces the earlier implementation-audit narrative. Both named reading copies now contain the verified rewrite; the previous PDF is preserved under `build/history/`. A successful document build does not imply submission readiness.

## Open and edit

- Verified reading copy: `KMS_Machine_Learning_Incident_Response_Revised_Draft.pdf` (65 pages), byte-identical to `build/main.pdf`. The earlier `Sentinel_KMS_Dissertation_Supervisor_Review.pdf` name now points to the same revised content. Hashes and remaining manuscript inputs are recorded in `evidence/document-validation.json` and `evidence/manuscript-status.json`.
- Main source: `main.tex`
- Author/institution/programme/supervisors/date: `metadata.tex`
- Chapters: `chapters/01-introduction.tex` through `chapters/05-conclusion.tex`
- Bibliography: `references.bib` (100 supplied records) and `supplementary.bib` (additional primary research and guidance)
- Provenance and outstanding bibliographic issues: `evidence/reference-map.md`
- Current test and screenshot provenance: `evidence/current-main-verification.md`, `figures/`, and `../frontend/e2e/thesis-current-main.spec.js`

The printed bibliography includes only cited works; unresolved catalogue records are retained but never used to support claims. Citations use `\cite{key}`, so numbering and links are generated automatically. The supplied-list keys are stable identifiers, not printed reference numbers.

## Compile locally

Tectonic is installed on this machine. From this directory:

    mkdir -p build
    python3 figures/generate_diagrams.py
    tectonic -k --keep-logs --outdir build main.tex
    python3 validate.py

The first Tectonic run downloads TeX packages and fonts; subsequent runs use its cache. The project uses explicit OpenType Latin Modern font files for portability. Do not compile with pdfLaTeX. An ordinary TeX installation can use XeLaTeX + BibTeX + XeLaTeX twice, or `latexmk -xelatex main.tex`.

For Overleaf, upload `Sentinel_KMS_Overleaf.zip` as a new project, select `main.tex`, and set the compiler to **XeLaTeX**. The ZIP contains the complete manuscript, both bibliography files, and every referenced figure and logo, with `main.tex` at its root. Import instructions and a writing-file guide are in `OVERLEAF_README.txt` (included as `README.txt` in the ZIP). Regenerate the package after editing with `python3 package_overleaf.py` from this directory. A fresh local build of the extracted package and its checks are recorded in `evidence/overleaf-package-validation.json`; those checks apply to the archive hash recorded there.

No proprietary class is required. The supplied writing decks are structural and methodological teaching material, not a verified university dissertation template. In particular, their SLR example is a different study: no search, screening, quality appraisal, participant result, or flow diagram is copied as if obtained for Sentinel.

`validate.py` is intended to run here inside the repository. It checks citation keys against BibTeX and the generated bibliography, cross-references, unresolved citations, missing glyphs, and overflow warnings. It records source hashes without loading `.env` or provider credentials. It does not establish semantic citation entailment or application security.

## Executed checks and their limits

On 29 September 2026, the inspected local main worktree passed 12 Python report-validator tests, 408 Java tests (including selected isolated PostgreSQL container paths), 91 frontend unit tests under Node 24, and 17 Chromium checks. A separate production frontend build passed. The browser suite includes intercepted HTTP-contract journeys and a current-interface screenshot capture; these are not live backend or analyst evaluations. The previous `evidence/verification-run.md` pertains to an older floor snapshot and is retained only as a historical record. See `evidence/current-main-verification.md` for the new commands and limitations.

The application screenshots were captured from this repository's current frontend bundle under deterministic mocked authentication and API responses. The visible upload is not the source of the separately mocked CVE answer, and displayed counts and latency are fixture values. To refresh, run `SENTINEL_REAL_STACK_E2E=1 npm exec --yes --package=node@24 -- npm run build`, then set `THESIS_FIGURE_DIR` to this thesis's absolute `figures/` directory and run `CI=1 SENTINEL_REAL_STACK_E2E=1 npm exec --yes --package=node@24 -- ./node_modules/.bin/playwright test e2e/thesis-current-main.spec.js -c playwright.config.js --project=chromium --workers=1 --reporter=list` from `frontend/`; rebuild the thesis afterwards. No screenshots establish live ingestion, model quality, or human participation.

## Completed studies and remaining manuscript inputs

- The author, programme, supervisors, affiliation, two logos and October 2026 submission date have been supplied and applied. Personal acknowledgements remain to be written.
- Confirm the institution's formatting, citation style, research-ethics rules, and AI-assisted-drafting disclosure requirements. The supplied decks do not set these requirements.
- Appraise the central full-text studies; much of the supplied evidence is abstract-level.
- Resolve the ten explicitly unresolved bibliographic records if they are needed for later chapters.
- Review and adjudicate the synthetic test cases with qualified experts.
- Import the actual model-comparison and human-evaluation records. The researcher explicitly confirms having completed this work; missing local records are a data-transfer dependency, not evidence of an unperformed study.
- Document the procedures actually used, including recruitment and ethics arrangements where applicable, without retroactively imposing a proposed protocol.
- Reconcile the outcome tables, abstracts and effectiveness conclusions with those records. `Thesis_Evaluation_Data_Template.xlsx` is an empty optional transfer format; unrecorded fields remain blank and aggregates must not be expanded into invented individual observations.

The five chapters and appendices have been narratively revised around the agreed research questions. Chapter 4 reports evaluation criteria, verified engineering checks and dated provider compatibility observations; its comparative and human-results synthesis awaits the actual records. Software regression checks, provider compatibility, controlled model comparisons and human outcomes are distinct evidence categories. The manuscript must not claim a winning model or an improvement in task performance without the corresponding study records. `evidence/manuscript-status.json` tracks the remaining inputs separately from structural build validation.

The graph is an evidence-linked assertion store, not a GraphRAG retriever. Local inference, authentication and storage are independent deployment choices. The writing revision does not change application runtime behaviour.

## Review records

The earlier `independent-review`, `review-resolution` and `current-main-review-resolution` evidence records are historical snapshots, not approval of this rewrite. The narrative review is retained in `evidence/narrative-independent-review.md`; all four minor findings are addressed in `evidence/narrative-review-resolution.md`, with primary-method support in `evidence/review-primary-source-resolution.md`. The subsequently supplied title and cover details supersede the earlier title-page placeholders; their verification is recorded in `evidence/cover-update-verification.md`. Current build checks and hashes are in `evidence/document-validation.json`. Empirical-record integration and acknowledgements remain pending.
