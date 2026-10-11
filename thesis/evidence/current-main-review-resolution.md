# Independent review resolution — current main-worktree dissertation

The independent read-only review of the 78-page reading copy identified one unresolved author-input dependency and four presentation/provenance issues. This record resolves findings against a later build; it does not alter the review's original observations or claim research outcomes.

| Finding | Resolution and post-change verification |
|---|---|
| Unfinished front matter (PDF pp. 1, 3, 5) | Deliberately remains a supervisor-review draft. Author, programme, institution, department, supervisors, submission date, acknowledgements, and institutional formatting/disclosure requirements must be supplied or confirmed by the author before final submission. No names or affiliations have been guessed. |
| Appendix A provenance wording | Now distinguishes governance and evidence mechanisms inspected in committed local `main` at `8d259eb` from subsequent uncommitted interface/prompt changes and engineering checks run on the modified working tree. |
| Screenshot figures drifting beyond section 3.8; stranded prose | Shortened the introduction and placed a float barrier before section 3.9. In the rebuilt PDF the complete screenshot explanation ends on PDF p. 46, Figures 3.3–3.6 appear together on pp. 47–48, and section 3.9 starts on p. 49. The figures are scripted mocked interfaces, not live ingestion, model runs, or participant observations. |
| Historical floor report ambiguity | Added an explicit superseded-record warning to `verification-run.md`; current 12/408/91/17 counts and the screenshot provenance remain in `current-main-verification.md`. |
| Numeric citation ordering and caption punctuation | Enabled natbib's `sort&compress` package option. The formerly `[20, 11]` group now prints `[11, 20]` on PDF p. 22. Removed the redundant Peffers citation attached directly after Table 1.1's caption; the preceding methodology paragraph retains that reference. |

Post-resolution checks: Tectonic build succeeded; `python3 -B -m unittest discover -s . -p 'test_validate.py' -v` passed 2/2; `python3 validate.py` passed with zero errors, 26 cited references matching the bibliography, and 78 A4 pages. Text extraction of the rebuilt PDF found no application source-tree paths, old commit ID, or Java/SQL/JSX filenames. Selected page images were visually checked after the float and citation edits. These checks do not verify semantic entailment or institutional approval.
