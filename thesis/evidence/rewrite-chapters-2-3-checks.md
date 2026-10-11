# Integration checks for rewritten Chapters 2 and 3

The delegated drafts were returned in the older Maestri floor despite the canonical repository having been supplied. Before integration, the canonical chapters were compared against the pre-rewrite backup and found unchanged. The reviewed drafts were then applied through a V4A patch to the canonical thesis at `/Users/danilopalaciosaboia/Documents/GitHub/CybersecurityBot/thesis`.

## Changes and checks

- Chapter 2 now follows incident response, knowledge lifecycle, ML representations, retrieval/generation, related cybersecurity studies, confidentiality and evaluation. It contains no Sentinel implementation discussion. Numeric source facts are attributed to the corresponding studies, not transferred to Sentinel.
- Chapter 3 now explains the actual current system, including maintained playbooks, incident prompts, fallback guidance and evidence inspection. Git/source-history narration is removed. The requirement rationale now incorporates the requirements interviews documented in the author's earlier thesis.
- The Sentence-BERT citation was checked against the ACL Anthology BibTeX record and added to the supplementary bibliography. The primary PDF was retrieved. NIST incident-response guidance, the original RAG paper and the PyPI comparison preprint were also retrieved for selected method review.
- The PyPI primary preprint explicitly discusses fine-tuning in its comparison; the chapter's bounded description was checked against that text. The parent could not retrieve the ForensicLLM PDF directly (TLS/403), so its account was narrowed to the adaptation, comparison and professional-response assessments supported by the retained abstract. No absence of retrieval in ForensicLLM is asserted.
- The architecture diagram now foregrounds incident-response knowledge use rather than an implementation audit. Existing current-frontend screenshots remain explicitly mocked, with concise captions. The ingestion crop omits the uploaded filenames.
- Static checks on both canonical chapters found no code-file extension/path or Git-history wording and no missing citation key. No product/stack term was found in Chapter 2.
- A separate preview build succeeded without overfull boxes, missing glyphs, undefined references or multiply defined labels. The cover, literature opening, requirements table and screenshot pages were rendered and visually checked after integration.

The preview is only an integration check. It still includes older sections awaiting replacement and must not be delivered as the completed revision. The previous named reading copy remains superseded by the ongoing rewrite. Chapter 1 integration, Chapters 4–5 and appendices, the researcher's measured data, final cross-chapter validation, and a new complete reading copy remain outstanding.
