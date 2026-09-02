# Research plan

## 1. Exact decision to support

Decide which body of up to 100 thesis-worthy references should support the design and evaluation of an AI-powered cybersecurity knowledge management system in which SLMs/LLMs use retrieval-augmented generation to recover knowledge. For now, candidates must be discoverable through IEEE Xplore or Mendeley and must include a stable reference link.

The decision is not whether to build the system; it is which sources are credible, relevant, sufficiently diverse, and useful enough to cite as the thesis evidence base.

## 2. Three independent subquestions

1. **Cedar — technical systems evidence:** Which peer-reviewed works describe or evaluate LLM-, SLM-, or RAG-based retrieval, question answering, threat intelligence, incident response, or other cybersecurity knowledge-recovery systems, and what architectural or empirical contribution makes each source useful?
2. **Morrow — validation and counterevidence:** Which peer-reviewed works identify, measure, or mitigate failure modes relevant to an AI-powered cybersecurity KMS—such as hallucination, retrieval error, prompt injection, data poisoning, privacy leakage, stale knowledge, weak evaluation, or unsafe automation—and what constraints do they impose on the design?
3. **Piper — KMS foundations and integration:** Which peer-reviewed works on cybersecurity knowledge management, ontologies, knowledge graphs, taxonomies, organizational knowledge sharing, or retrieval evaluation establish the information model and evaluation criteria into which SLM/LLM/RAG components must fit?

These subquestions are independent by evidence class: implemented AI/RAG systems; risk and validation evidence; and KMS/domain-model foundations.

## 3. Agent tasks and designated evidence notes

- **Cedar** (canvas agent currently named `Cadar`) researches subquestion 1 and writes findings only to `10-primary-evidence-2`.
- **Morrow** researches subquestion 2 and writes findings only to `11-counterevidence-2`.
- **Piper** researches subquestion 3 and writes findings only to `12-kms-evidence-2`.

Each note must contain only evidence records, one record per candidate: title; authors; year; venue; direct IEEE Xplore or Mendeley URL; DOI or publisher URL when exposed by the allowed site; scope tags from AI, Cybersecurity, RAG, and KMS; evidence type/method; concrete thesis relevance; and any limitation. Agents must not edit this plan, another agent's evidence note, the claim matrix, or the recommendation.

Aim for 35 unique candidates per agent so deduplication can still leave up to 100 strong references. Quality and traceability take priority over filling a quota.

## 4. Authoritative-source requirements

- Use only IEEE Xplore and Mendeley for discovery in this research round.
- Prefer peer-reviewed journal articles, conference papers, systematic reviews, standards, and well-described empirical studies. Exclude blogs, marketing pages, unsourced summaries, slide decks, and anonymous uploads.
- Treat Mendeley as a discovery/metadata index, not as proof of quality. Record the underlying DOI or publisher venue when Mendeley exposes it, and do not infer findings from metadata alone.
- For every candidate, verify that the title, authors, year, venue, and URL refer to the same work. Use a stable IEEE document page, Mendeley catalog page, DOI, or publisher link—not a search-results URL.
- Distinguish directly supported findings from an agent's inference. Do not claim a result from title/abstract metadata when the accessible record does not report it.
- Prefer sources with an explicit cybersecurity or knowledge-retrieval contribution. General AI/RAG sources are admissible only when their method or evaluation directly informs the proposed system.
- Flag retractions, duplicates, inaccessible records, non-peer-reviewed items, weak abstracts, and claims that conflict across sources. Deduplicate conference/journal versions and DOI aliases.

## 5. Stop condition

Stop the research handoffs when all three agents have completed their designated notes and either (a) the combined set contains at least 100 unique, metadata-verified, thesis-relevant candidates with adequate coverage of AI, Cybersecurity, RAG, and KMS, or (b) further searching of IEEE Xplore and Mendeley produces no new eligible source in two materially different query passes per subquestion. If the set stops below 100, preserve the quality threshold, state the shortfall, and have Vela report the largest remaining uncertainty rather than padding the list.

Vela may begin only after all three handoffs are complete. Vela must build `20-claim-matrix-2`, explicitly label unsupported claims and source conflicts, write `30-recommendation-2`, and report the single largest remaining uncertainty to Aster. The final recommendation should end with a references-links-only section containing the accepted deduplicated URLs.
