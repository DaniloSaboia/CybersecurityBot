# Independent implementation-coherence review

Snapshot checked: 2026-09-07T17:59:13.809243+00:00. Read-only review; no application startup, compilation, tests, database operations, or provider execution. Only this report was written. Hashes identify the inspected bytes, not a deployed system or complete security audit.

## Outcome

The implementation-led narrative is coherent across all five chapters, frontmatter, appendices, metadata, and bibliography. No substantive RQ mismatch or unsupported effectiveness/security guarantee was found in the sampled runtime claims. One evidence-packaging issue remains at this snapshot.

### Actionable finding — medium: current execution claim lacks its cited record

`thesis/chapters/04-evaluation.tex:8,26` attributes the current check to twelve passing tests, discovery selecting `test_quality_gate.py`, and duration **1.231 seconds**, citing `thesis/evidence/implementation-revision.md`. That companion file did not exist when checked; Chapter 3 also cites it at line 155. The existing `thesis/evidence/evaluation-checks.txt:7,33,37` instead records discovery with `test_*.py`, **1.182 seconds**, and exit zero. Appendix A (`appendices.tex:33–36`) points readers to this older record. The chapter explicitly distinguishes earlier checks, so this is not evidence of fabricated results, but the current run cannot yet be independently corroborated from its declared artefact.

**Acceptance:** include the actual new execution record with exact command/output/input hashes, keep its identity distinct from the earlier record, and verify the final appendix/chapter pointers. Do not replace missing execution evidence with a reconstructed log. Another writer may still be preparing this file; this finding applies only to the hashed snapshot.

## Important claims checked against definitions/call paths

- **Retrieval is not graph retrieval — supported.** `RagService.java:312–354` calls literal/rewritten hybrid search and subject-aware IOC lookup, then optional reranking; `SpringAiEmbeddingIndexService.java:485–570` performs vector/lexical retrieval; `HybridRanker.java:19–51` implements rank fusion and returns retained branch rows rather than exposing the fusion score. `GraphController.java:16–30` only creates/reads individual entities/edges. Backend search for `GraphService` and graph-table names found graph-package usage, not assistant integration. Chapters 1–5 and both abstracts consistently preserve this distinction.
- **Upload grounding is not article evidence — supported.** `RagService.java:437–481,604–630` separates article references from source tuples; `KnowledgeAccessService.java:99–130` resolves current durable source policy. Source-only chunks can remain grounding while lacking article citation tokens/viewer identity. Chapter 3 lines 66/137 and Chapter 5 line 10 state this limitation explicitly. `EvidenceViewRequest.java:14–20` requires a nonempty span; `EvidenceViewService.java:23–49,68–125` only resolves the current approved revision, not unconditional historical replay.
- **Buffered SSE and final disclosure gate — supported, not immediate token streaming.** `RagService.java:271–276` collects provider deltas; lines 709–743 sanitize, reauthorize, and persist before yielding generated events. Local persistence-failure fallback can have a null message ID (`747–756`), but the thesis does not assert that every fallback is durably stored. No complete revocation-race or deployment-security guarantee is inferred.
- **No-grounding abstention is not zero provider exposure — correctly qualified.** `RagService.java:315–332` delays rewriting/reranking until grounding exists, but `SpringAiEmbeddingIndexService.java:506` can embed the question first. Chapter 3 line 131 correctly follows the actual call path rather than the overbroad code comment at `RagService.java:307–310`.
- **Grounding identity is not entailment or complete prompt inclusion — correctly qualified.** IOC content is constructed at `RagService.java:646–656`; `PromptBuilder.java:142–175` sanitizes/truncates the source section. Chapter 3 lines 129/133 explicitly avoid treating the stored span as support for every synthetic summary field or proof of full prompt inclusion.
- **Evaluation is narrower than the assistant — supported.** `EvalService.java:115–141` calls hybrid search, omits source-only hits from article scoring, deduplicates article IDs after chunk selection, and passes a case on positive recall. It does not run rewriting, reranking, IOC integration, answer generation, or the assistant's later evidence resolution. Chapter 4 line 68 accurately disclaims the stronger assertions in the service's own comments.
- **Heuristics and provider labels are not scientific outcomes — supported.** `AnswerEvaluationService.java:19–53,182–197` labels phrase/provenance/forbidden-ID checks as heuristics; `HttpProviderAnswerSource.java:86–91` sends fixture evidence for every baseline, including no-RAG. Chapter 4 line 42 explicitly requires an independently verified endpoint rather than accepting a label as controlled context removal. `ScientificCompletenessGate.java:32–79,147–168` requires imported sidecars and distinct accepted reviewer identities; it cannot authenticate actual reviewer independence. The text preserves that limit.
- **Fixtures and existing validator evidence are correctly bounded.** Parsed fixtures contain ten retrieval cases and nine answer cases; the latter include two answerable cases and seven other case types, with all case reviews and the overall review pending. The historical-query/stale-abstention rubric problem is explicitly disclosed in Chapters 4/5. `test_quality_gate.py:20–25,62–65` fabricates metrics/XML; `quality_gate.py:19–42` validates declared aggregates rather than reconstructing retrieval. The old retained log establishes twelve validator passes, not live integration/model results; current-log packaging remains the finding above.

## RQs, example exclusion, and bibliography

- Chapter 1 lines 33–45 defines an implementation/evidence principal question and RQ1–RQ4. Chapter 4 lines 8–12 maps its evaluation boundary to those questions; Chapter 5 lines 6–12 answers them at source-inspection/bounded-verification level. Analyst benefit and controlled model comparisons remain future empirical obligations, not silently substituted completed RQs.
- Case-insensitive scanning of every inspected TeX/Bib source found **no Godinho or ASTRA reference** and no template attribution/adaptation narrative. Occurrences of “example” are ordinary illustrative scenarios and claim-to-source examples, not references to the supplied dissertation. This is source validation, not verification of a potentially older compiled PDF.
- Both BibTeX databases were read: **101 unique keys**, **26 cited keys**, no missing cited keys, and no unresolved placeholder cited. External bibliographic truth/full-text literature claims were not independently re-researched in this implementation review.

## Snapshot integrity and scope

All 12 direct thesis TeX/Bib sources were read in full. Runtime definitions were sampled at the cited ranges; hashes below cover their full files even where only selected ranges were inspected. No inspected file changed between its capture and the end-of-review hash comparison: true.

### Thesis source SHA-256

```text
a7e47ce13dd7955e0dfa028faa7405cb35fd6b4957e4a82bd7a9160a58882240  thesis/acronyms.tex
17b604a38e65921e4ab0f5d4dced3cc10182c9a16da4eeb9e502557ce6e5d5b1  thesis/appendices.tex
86cab230d3449aae2f91c6817ee50b1754fd2579992ae3ac7159e445ba8207bb  thesis/chapters/01-introduction.tex
9d4c8e1d20bbe60ddde011da0b2d02c6ccc99ff4ffbe1cca774f3698413901c3  thesis/chapters/02-literature.tex
e1e2a578c20f6317f1482b8640b18ada55c01790844b2d541c38ca1063dcf159  thesis/chapters/03-system.tex
dcbea5ea3102ca718083cd7849d3c61377cfffd1fb7ff07fee3ffd46ab70208b  thesis/chapters/04-evaluation.tex
83f8f13ac228e5c3740e84b1218a58142c4600bcd98ef9c98235692da48aafca  thesis/chapters/05-conclusion.tex
8b37330f4f87ef2973e19677fba6214c351c02ea3fd4865ecbb2bfc5cec1cff9  thesis/frontmatter.tex
0ec597b0c6827e2c69767dfd5703bb4c31e96dd496901ba441127d17f6312f08  thesis/main.tex
6ecbcac726524cc9848d937e07f25851c88d91ac1bc4f8b786e56a3780107efa  thesis/metadata.tex
599f16ace0cac380d47bba23722f47a978004b08a2ade1d23a811f483463c0d7  thesis/references.bib
ab7074f2d6fc0b3dad07e52775351d9d070c7342dc8292d1834076782d13c1a9  thesis/supplementary.bib
```

### Sampled implementation and retained evidence SHA-256

```text
93202d8626f0223340160e2b94a8ef2821021223e97f4cff0da78bd4727e089a  backend/src/main/java/com/sentinel/kms/assistant/PromptBuilder.java
e02c8ca50379be91c6b244423124d24529994a3c27e44cc71c80dc51ac75cb47  backend/src/main/java/com/sentinel/kms/assistant/RagService.java
97b091a61183c6747115b0bd93654e2482efa73aec67726043a6838cec42b09f  backend/src/main/java/com/sentinel/kms/eval/AnswerEvaluationService.java
a0db470542cf096fb555c88de49d2cb105516afd418e04e3347b403f3a21e060  backend/src/main/java/com/sentinel/kms/eval/EvalService.java
1db45f845bf42db4eefb3bc63b070490f71238697d1046836d30cc8fd158dd40  backend/src/main/java/com/sentinel/kms/eval/HttpProviderAnswerSource.java
d6a8708efdbffef2ce09c3cbb1af07c4ae6202cd3eb3a93307332abadd1ce452  backend/src/main/java/com/sentinel/kms/eval/ScientificCompletenessGate.java
da694c624311d2d292bfd7fb8f307edd225362dd3de299292a55d2c94e4c407c  backend/src/main/java/com/sentinel/kms/graph/GraphController.java
d21db55755187d7f4f5f47ecafc032e78640d5ccf48342fc6e99c1776922ba23  backend/src/main/java/com/sentinel/kms/graph/GraphService.java
3165302e02f9080448e34d617bcb862a66a653365326785b4be3a7011ccb8375  backend/src/main/java/com/sentinel/kms/kb/EvidenceReferenceResolver.java
41554c2b825db84858ac36bf0241dbb14c278e6f41f81dc1b36ed26da616bb4f  backend/src/main/java/com/sentinel/kms/kb/EvidenceViewRequest.java
c0687030bce72bcfc490c7f3a5149169376bd85aab60b5ace3be63f271fb7500  backend/src/main/java/com/sentinel/kms/kb/EvidenceViewService.java
fcfc2c482302e78cd2bbcfac130e85c35a12ac01fcc550ae0f721f340e4e0887  backend/src/main/java/com/sentinel/kms/kb/KnowledgeAccessService.java
202d7b106c35347786b3ffdf69c4c42c82eef9ca848f1f2830d3803018ccd297  backend/src/main/java/com/sentinel/kms/vectorstore/HybridRanker.java
e4fd877946edddfb8cdb2f6d98caa0b9ac59a3b8fec544fe68da4fd586968240  backend/src/main/java/com/sentinel/kms/vectorstore/SpringAiEmbeddingIndexService.java
b98936580891a70228c312bbf70b7d3c1f98f8839e12f8ab50a76bcb808d71af  backend/src/main/resources/eval/answer-eval-v1.json
86e94414a7c1bc907a02e2f98a7fe11f7503b324c430eac4c4b0e2b5682b46ec  backend/src/main/resources/eval/goldenset.json
fb71c27d2586bdeb3cf9bc4780230a9436dff1d2a5f78461bb1d9cfdb1576265  scripts/ci/quality_gate.py
923e21900f9c1a14bf957f0465a9cb121a2575081c3bd795404efae6410ea7f9  scripts/ci/test_quality_gate.py
8d96aafdb047a9b234fee14054198169535f895439554e6628aff8d8dc231709  thesis/evidence/evaluation-checks.txt
```

The missing `thesis/evidence/implementation-revision.md` has no inspected hash. Resolve findings separately rather than rewriting this independent snapshot. No thesis or application changes were made.
