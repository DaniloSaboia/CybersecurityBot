# Confirmed title, cover details and logos

## Applied identity

The researcher supplied the following title and front-matter details. They supersede the earlier title and the personal/institutional placeholders in prior review snapshots; this update does not change the research findings.

- Exact title: Sistema inteligente de gestão de conhecimento para resposta a incidentes de Cyber segurança com integração de machine learning
- Author: Danilo Palácio Sabóia.
- Programme: Master in Telecommunications and Computer Engineering.
- Institution: Iscte – Instituto Universitário de Lisboa.
- Supervisor: PhD Carlos José Corredoura Serrão, Associate Professor, Iscte – Instituto Universitário de Lisboa.
- Supervisor: PhD Ana Maria Carvalho de Almeida, Associate Professor with Agregation, Iscte – Instituto Universitário de Lisboa.
- Submission date printed as supplied: October 2026.

The wording of the title, including `Cyber segurança`, and the supplied qualification `Agregation` is preserved. The black institutional logo is on the cover; the teal Iscte Tecnologias e Arquitetura logo is on the inner title page. PDF Title and Author metadata also contain the confirmed values. Active README and editorial-brief identity records are updated; historical reviews retain their original scope and hashes.

## Logo provenance

Original attachments are retained byte-for-byte under `figures/supplied-logos/`, so the build does not depend on temporary attachment paths. Display copies only trim empty margins, leaving padding; pixel comparisons confirmed unchanged colours and proportions.

| Logo | Original attachment | Original SHA-256 | Display asset SHA-256 |
|---|---|---|---|
| University | `1F0363DD-552E-46B0-B4D2-847623F037B9.png` | `45fc7a748f5827b758d91d7e6d1f63016a9bb68377ed113309ed653fc8b9cab1` | `aa1b97b34f3dcdd1d41a86b2be35b3d2df91a9373fcdc58c8d9e80b18a0bb31d` |
| Department | `C65AACC0-1E85-49F8-A55A-2F5A32866DFC.png` | `a3a68207a62f901384c11fd57818bda215cf24222a0d695873475b7d247f303f` | `835d400d0ca80e70e5031db510df376176a37eae407638b10822e3afbc710847` |

Visible-content bounds measured over white were `(159, 217, 2651, 612)` and `(662, 554, 3014, 1606)` pixels respectively. Padded crop bounds were `(119, 177, 2691, 652)` and `(557, 449, 3119, 1711)`. Display images are `figures/iscte-university-logo.png` and `figures/iscte-department-logo.png`.

## Verification and delivery

- Tectonic compilation passed with no overflow, underfull-box, missing-character or undefined-reference warnings.
- All six validator regression tests passed. The new unresolved-front-matter-field test was observed failing before implementation and passing afterwards; confirmed metadata is no longer reported through a hard-coded stale warning.
- Structural and citation-link validation passed and recorded the current source/PDF hashes.
- Exact title and author were checked in PDF metadata. Both title pages were checked by text extraction for the supplied title, author, programme and date; the inner page was also checked for both complete supervisor entries and affiliation.
- Physical pages 1 and 3 were rendered from the final PDF and visually inspected. Logo proportions, title wrapping, spacing and legibility passed; no cover fields remain marked unconfirmed.
- A page-by-page text comparison with the preceding reading copy found changes only on physical pages 1 and 3. The remaining text and pagination are unchanged. The document remains 65 pages.
- Both named reading copies are byte-identical to `build/main.pdf`: `KMS_Machine_Learning_Incident_Response_Revised_Draft.pdf` and `Sentinel_KMS_Dissertation_Supervisor_Review.pdf`.
- Initial title/logo update PDF: 620916 bytes; SHA-256 `64bfe2d09fcce4db6812f24523b2b3e5342877138fe2ea52c046bd8801fdd8bc`.
- The preceding PDF is retained as `build/history/pre-cover-update-4ff247bf1c32.pdf`.

Acknowledgements remain the sole printed front-matter placeholder. Actual completed-study records still await integration. Confirmation of cover details does not establish final institutional-format compliance or empirical submission readiness.

## Supervisors on the first page

Both supervisor entries, including qualifications and affiliations, now appear below the degree on physical page 1 as requested, while remaining on physical page 3. Both pages use the same confirmed metadata definition. Compilation, all six regression tests and structural validation passed without layout warnings. Text extraction confirmed both complete entries on both pages, and the final first-page rendering was visually checked for spacing, clipping and overlap.

Only physical page 1 changed relative to the preceding PDF; the remaining text and pagination are unchanged at 65 pages. Both named reading copies have been replaced and verified byte-identical to the build. Current PDF: 621181 bytes; SHA-256 `da143f5e3c7d59e9884b09b26f1a3c6e80cdf966b31efa6571c7dfa4949eadae`. The preceding version is retained as `build/history/before-first-page-supervisors-64bfe2d09fcc.pdf`.
