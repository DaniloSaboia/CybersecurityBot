# Recommendation

## Decision recommendation

Proceed with the thesis prototype as a **governed, human-in-the-loop cybersecurity knowledge management system in which RAG is a replaceable and testable knowledge-recovery component**. Use a versioned text corpus plus ontology/knowledge graph, provenance- and access-aware retrieval, cited answers with abstention, and read-only advisory operation by default. Do not position the system as an autonomous cyber-defense agent or claim that RAG, GraphRAG, an SLM, or a multi-agent design is inherently superior.

## Evidence coverage and selection

The three completed handoffs supplied 105 candidates: 35 technical-system records, 35 counterevidence/risk records, and 35 KMS-foundation records. DOI-first comparison found no duplicate among 95 DOI-bearing records; normalized title and venue comparison found no duplicate among the DOI-less records or the full set. Five records (C18, C19, C20, C22, C23) were excluded because the handoffs exposed only metadata/search snippets and no abstract, leaving **100 accepted deduplicated references**: 35 technical-system, 30 counterevidence, and 35 KMS-foundation records.

Accepted tag coverage is AI 88, Cybersecurity 85, RAG 50, and KMS 89, with overlap. This meets the plan's stop condition and the 100-reference ceiling. The list was not padded: the five weakest evidence-basis records were removed even though they were unique, and no new literature search was performed after the stop condition was met.

**Prominent limitation:** virtually all accepted evidence in the handoffs is abstract/catalogue-level because IEEE Xplore full-text access failed in the research portals. No accepted study received documented full-text methods, bias, statistics, reproducibility, or study-quality appraisal. Mendeley establishes discovery/metadata traceability, not study quality. Consequently, the recommendation supports a prototype and a defensible evaluation plan, but not production-effectiveness or safety conclusions.

## What the thesis may safely claim

- Peer-reviewed literature contains directly relevant prototypes for cyber QA, incident/timeline analysis, CTI extraction, knowledge-graph construction, vulnerability analysis, and analyst-facing retrieval.
- Ontologies and knowledge graphs are credible mechanisms for normalizing heterogeneous cyber knowledge, representing relations, supporting provenance, and enabling structured retrieval.
- RAG can improve some cyber tasks, but its benefit depends on the task, corpus, retriever, model, and evaluation design.
- Retrieved context does not eliminate hallucination. Evaluation should separate retrieval quality, context quality, faithfulness, answer quality, abstention, temporal correctness, and human outcomes.
- Retrieved documents and tool outputs are untrusted inputs. Prompt injection, corpus/graph poisoning, membership inference, data leakage, unsafe code, and over-reliance are material design threats.
- Local SLM-scale models are feasible comparison candidates, but size or local deployment alone does not establish effectiveness, privacy, or security.
- Human approval and externally enforced authorization are appropriate for high-impact cyber guidance and any side effect.

## What the thesis must not claim

- That RAG or GraphRAG is generally superior, best, or cost-effective without a same-corpus controlled comparison.
- That a knowledge graph is complete, correct, current, or trustworthy merely because it is structured.
- That citations, grounding, local inference, federation, or access control eliminate hallucination, poisoning, injection, leakage, or compliance risk.
- That abstract-reported effect sizes generalize to the thesis corpus, unseen organizations, live incidents, or production SOCs.
- That one model family, embedding model, chunking policy, reranker, or agent topology is universally preferable.
- That the prototype improves live organizational security outcomes or can safely automate remediation, code execution, incident response, or offensive actions.

## Preserved conflicts

The literature does not support collapsing the following conflicts into a single positive narrative:

- Some cyber studies report RAG gains, while others find few-shot or fine-tuned models superior, uneven model benefit, or performance comparable to standalone LLMs.
- Retrieval supplies current evidence and provenance, but also creates prompt-injection and corpus/graph-poisoning channels.
- Structured graphs support explainability and relations, but can contain incomplete, inferred, stale, or malicious edges.
- AI assistance worsened security outcomes or confidence calibration in some user/code studies, while another bounded study found little additional critical-bug rate.
- Updating the external corpus can improve freshness, but may not overcome stale parametric knowledge in the underlying model.

These conflicts should be resolved only for the thesis's defined users, corpus, tasks, and threat model. Otherwise they should remain explicit limitations.

## Supported architecture implications

1. Maintain a governed knowledge layer containing source documents plus an ontology/KG. Attach source, authority, timestamps, version, access class, transformation history, and confidence to documents, chunks, entities, and edges. Keep sourced facts separate from inferred/completed graph edges.
2. Place vector, lexical, graph, reranking, and compression strategies behind a common retrieval interface so each can be ablated. Do not begin with a multi-agent topology unless a simpler modular pipeline cannot satisfy an independently measured requirement.
3. Enforce authorization before retrieval. Partition sensitive corpora by role/tenant, minimize verbatim output, log access, and preserve cited source spans for review.
4. Preserve typed boundaries among system instructions, user requests, retrieved evidence, and tool output. Treat external text as hostile; authenticate and quarantine ingestion; sign/version corpus snapshots; support rollback.
5. Default to read-only advisory behavior. Put database authority, tool permissions, sandboxing, rate limits, confirmation, kill switches, and audit controls outside the model.
6. Compare a local SLM and a larger LLM under the same retrieval and evaluation conditions. Select by measured quality, safety, latency, privacy exposure, and cost—not parameter count alone.

## Supported evaluation implications

Use a versioned, expert-reviewed cyber test set covering answerable, unanswerable, ambiguous, conflicting, stale/superseded, multi-document, and adversarial questions. At minimum, report separately:

- retrieval recall/precision and source diversity;
- context relevance/precision and evidence coverage;
- claim-level faithfulness, contradiction, answer correctness, and citation correctness;
- abstention and negative-rejection performance;
- temporal/as-of correctness after corpus, index, embedding, or model updates;
- indirect prompt-injection and corpus/KG-poisoning success and recovery;
- role/tenant authorization, membership inference, and partial-document reconstruction leakage;
- latency, resource use, and cost under the same task budget;
- analyst decision quality, time, confidence calibration, harmful over-reliance, and error severity;
- safe behavior for code, commands, database queries, and tool requests.

Automated RAG metrics may be used for scale only after calibration against domain-expert judgments. Architecture conclusions should come from ablations on the same corpus and task set.

## Completion report to Aster

- Status: complete; both workspace files and connected output notes are written and verified.
- Final accepted-reference count: 100.
- Unsupported-claim count: 5.
- Conflict-family count: 5.
- Largest remaining uncertainty: whether this governed RAG/KG architecture improves representative analysts' decision quality and time on realistic, access-controlled, continuously changing incident/CTI work **without increasing severe unsupported advice or over-reliance**, compared under equal conditions with search-only, no-RAG LLM, and fine-tuned/prompted local-SLM baselines. A next research round should prioritize full-text empirical studies and evaluations that directly measure that comparison.

## References links only

https://doi.org/10.1145/3672608.3707898
https://doi.org/10.26342/2025-75-15
https://doi.org/10.1007/s11280-025-01393-5
https://doi.org/10.1145/3746252.3761478
https://doi.org/10.1109/BigData59044.2023.10386611
https://doi.org/10.1109/EuroSPW61312.2024.00018
https://doi.org/10.1145/3701716.3715469
https://doi.org/10.1109/EuroSP63326.2025.00057
https://doi.org/10.1109/ISDFS65363.2025.11012055
https://www.mendeley.com/catalogue/e79a950e-f69e-3fe8-b9fb-4c21e80e36e4
https://doi.org/10.3390/computers14020067
https://doi.org/10.1609/aaai.v38i21.30362
https://doi.org/10.1007/978-3-031-56583-0_20
https://doi.org/10.1109/JIOT.2025.3586658
https://doi.org/10.1109/ACCESS.2025.3627277
https://doi.org/10.1016/j.csbj.2025.07.012
https://doi.org/10.1109/MNET.2025.3619162
https://doi.org/10.1109/ACCESS.2025.3603580
https://doi.org/10.1016/j.comnet.2025.111162
https://doi.org/10.1109/ACSACW65225.2024.00036
https://doi.org/10.1109/CICN63059.2024.10847404
https://doi.org/10.1109/ICDSCA63855.2024.10859408
https://doi.org/10.1145/3756681.3757042
https://doi.org/10.1109/CSCWD64889.2025.11033478
https://doi.org/10.1145/3719027.3744855
https://doi.org/10.1109/AIxDKE63520.2024.00033
https://doi.org/10.1109/ICMLA61862.2024.00133
https://doi.org/10.1109/ACCESS.2025.3586203
https://doi.org/10.1007/978-3-031-87496-3_5
https://doi.org/10.1016/j.fsidi.2025.301872
https://doi.org/10.1016/j.fsidi.2025.301981
https://doi.org/10.1016/j.procs.2025.08.299
https://doi.org/10.7717/peerj-cs.3371
https://doi.org/10.1145/3780095
https://doi.org/10.1109/SCSE65633.2025.11031059
https://doi.org/10.3390/math13050856
https://doi.org/10.1145/3703155
https://doi.org/10.1609/aaai.v38i16.29728
https://doi.org/10.18653/v1/2024.eacl-demo.16
https://doi.org/10.18653/v1/2024.naacl-long.20
https://doi.org/10.18653/v1/2024.acl-long.585
https://doi.org/10.1145/3701228
https://doi.org/10.52202/079017-0692
https://doi.org/10.1371/journal.pdig.0000877
https://doi.org/10.1145/3605764.3623985
https://doi.org/10.1109/ICSE55347.2025.00007
https://doi.org/10.34190/iccws.20.1.3292
https://doi.org/10.52202/079017-2636
https://www.mendeley.com/catalogue/81ac6092-600c-3c3d-8c29-329b2c67fdbf
https://www.mendeley.com/catalogue/eb1c701d-3b4a-3414-a79c-4ca6d69f7e41
https://www.mendeley.com/catalogue/aa2446f0-62ac-3d01-bbcd-c6094cc7fb6d
https://doi.org/10.1016/j.inffus.2025.103900
https://doi.org/10.1145/3719027.3765095
https://doi.org/10.5220/0013108300003899
https://doi.org/10.1145/3696410.3714771
https://doi.org/10.1145/3719027.3744840
https://www.mendeley.com/catalogue/447e8b29-dbd9-3346-b4de-8e890eb798f5
https://doi.org/10.1109/SP46215.2023.10179300
https://www.mendeley.com/catalogue/13a00f07-d300-3d37-a43e-f8b68e3c1260
https://doi.org/10.1162/tacl_a_00459
https://doi.org/10.1109/SP46214.2022.9833571
https://doi.org/10.1145/3576915.3623157
https://www.mendeley.com/catalogue/704c30ea-a5be-30ad-a5bd-ccd0db0803de
https://www.mendeley.com/catalogue/8305ced0-04be-3620-ae63-73af06c0ea7e
https://www.mendeley.com/catalogue/1bc65c1a-0c34-3449-b2e7-331be5a26832
https://doi.org/10.1016/j.procs.2021.08.104
https://doi.org/10.1007/s10270-022-01013-0
https://doi.org/10.1016/j.cose.2023.103524
https://doi.org/10.1016/j.procs.2022.09.031
https://doi.org/10.3390/fi16030069
https://doi.org/10.1109/MNET.2025.3562901
https://www.mendeley.com/catalogue/169f44f0-7df5-38c8-beaf-d1fa4ca0b47b
https://doi.org/10.1016/j.procs.2025.09.485
https://doi.org/10.1093/comjnl/bxu101
https://doi.org/10.1007/s10115-023-01860-3
https://doi.org/10.1007/s40747-023-01205-1
https://doi.org/10.3390/app122412947
https://doi.org/10.1109/TKDE.2022.3175719
https://doi.org/10.3390/electronics11152287
https://doi.org/10.3390/electronics14142878
https://doi.org/10.7717/peerj-cs.2768
https://doi.org/10.1016/j.cose.2024.103848
https://doi.org/10.1155/2020/8883696
https://doi.org/10.1109/EISIC.2017.20
https://doi.org/10.3390/jcp4030025
https://doi.org/10.1109/COMST.2023.3273282
https://doi.org/10.1016/j.jisa.2024.103786
https://doi.org/10.1007/s10115-025-02382-w
https://doi.org/10.1016/j.cose.2023.103170
https://doi.org/10.1109/CSR61664.2024.10679346
https://doi.org/10.1007/s10586-024-04899-1
https://doi.org/10.1007/978-3-319-69341-5_13
https://doi.org/10.1080/23311975.2023.2195027
https://doi.org/10.1109/ACCESS.2022.3163758
https://doi.org/10.1177/1534484303257985
https://doi.org/10.3390/s23167273
https://doi.org/10.1016/j.cose.2019.101589
https://doi.org/10.1093/cybsec/tyaf016
https://doi.org/10.1109/TDSC.2022.3151148
https://doi.org/10.1145/3637211
