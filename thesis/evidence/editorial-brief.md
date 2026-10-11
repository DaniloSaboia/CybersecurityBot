# Thesis rewrite brief

## Fixed identity and narrative

Exact title (preserve spelling and case): Sistema inteligente de gestão de conhecimento para resposta a incidentes de Cyber segurança com integração de machine learning

Confirmed cover details: Danilo Palácio Sabóia; Master in Telecommunications and Computer Engineering; Iscte – Instituto Universitário de Lisboa; October 2026. Supervisors: PhD Carlos José Corredoura Serrão, Associate Professor; PhD Ana Maria Carvalho de Almeida, Associate Professor with Agregation; both affiliated with Iscte – Instituto Universitário de Lisboa. Use the supplied black institutional logo on the cover and the teal Iscte Tecnologias e Arquitetura logo on the inner title page. Preserve the supplied title and qualification wording rather than silently normalising them.

Retain English main text, like the supplied structural example, with Portuguese Resumo and the exact Portuguese title. Write as the researcher: restrained first-person plural for research choices (we define, we adopt, we evaluate), clear active descriptions for technical behaviour. No third-person instructions to 'the author', 'the user', or 'the student'. Avoid audit-report language, repeated disclaimers, self-assessment, and formulaic contrast paragraphs.

The argument concerns knowledge capture, organisation, maintenance and reuse during incident response, with machine learning supporting contextual retrieval and source-based explanations. Governance and evidence are enabling requirements, not a replacement subject for the title. Optimisation means reducing effort/time to locate applicable knowledge and helping people make correct, well-supported decisions; it does not mean arbitrary mathematical optimality or automatically executing remediation.

## Chapter boundaries

1. Introduction: motivation, knowledge-management problem in incident response, objectives, research questions, methodology, scope, chapter organisation. No product name, implementation mechanisms, technology stack, storage details, test inventories, commits or results-denial prose.
2. State of the art: focused synthesis of incident response and knowledge management; ML representations and retrieval; RAG and comparable cybersecurity studies; security and evaluation. No Sentinel implementation details or source-inspection commentary. Discuss literature strengths/limits specifically, once where useful. Cite actual relevant studies rather than cataloguing everything read.
3. Design and development: introduce Sentinel KMS; requirements and use cases; rationale; architecture; knowledge lifecycle and ingestion; ML-supported retrieval and incident guidance; interface; confidentiality, evidence and operations. Explain why each design choice serves incident-response knowledge reuse. No Git/history narrative, source filenames, code paths, commit identifiers or code inventories. Use the actual current application capabilities, not only a historical commit.
4. Evaluation and results: research questions -> experiment/method -> observed results -> interpretation and limitations. Model and participant records must be verified before numerical reporting. The researcher states measurements were taken: do not assert universally that they were not measured. Missing records in this writing workspace are not proof that a study never happened. Automated checks, deterministic screenshots and genuine experiments must stay distinct.
5. Conclusion: answer the research questions using the results actually documented, contributions, bounded limitations, future work. Do not tell an author what to do or fill the chapter with repeated evidence disclaimers.
Appendices: supporting research instruments and scenario/method detail only; no development audit or bibliographic procurement log.

## Common questions and IDs

Principal question: How can a knowledge-management system use machine learning to support faster, better-informed incident-response decisions while preserving the reliability and confidentiality of the knowledge used?

RQ1: What knowledge must be captured, organised and maintained to support incident-response decisions?
RQ2: How can machine-learning-based retrieval and language generation turn that knowledge into relevant, traceable guidance?
RQ3: How does the resulting system perform in retrieval, response quality, task efficiency and usability, subject to access and provenance controls?

F1 Governed knowledge; F2 Recoverable ingestion; F3 Contextual retrieval; F4 Traceable incident guidance.
N1 Access and disclosure safety; N2 Reviewable operations; N3 Deployment flexibility.
Keep existing cross-reference labels unless coordinating changes.

## Evidence and presentation

User's example supplies chapter progression, requirements/evaluation connection, concise technical explanations, and demonstrations; do not copy its system, identity, measurements or participant outcomes. The writing slides favour a single research argument, active voice, examples before formal details and tables that substantiate claims. The thesis guide requires defined terms, purposeful paragraphs, relevant literature and warranted conclusions. SLR slides supply review discipline (question, selection, quality, synthesis), not this project's screening counts or an automatically completed SLR.

Separate internal reproducibility files from the reader-facing text. Cite sources in the bibliography; never print code filenames or branch/commit details in prose, tables, captions or diagrams. Configuration values essential for explaining a method are acceptable without filenames.

Current screenshot captures come from the requested main repository's actual frontend with deterministic mocked authentication/API responses. Disclose that once in the illustration introduction and succinctly in captions; do not present fixture counts/answers as experiments. Existing engineering run counts are 12 report-validator, 408 Java, 91 frontend and 17 browser tests; these remain separate layers, not participant/sample counts.

New source keys available from parent: nist2025incident (NIST SP 800-61r3, primary full text) and lewis2020rag (original RAG paper). Existing keys ref039/ref040 are RAGAs/ARES. Parent owns supplementary bibliography. No invented citations, sample sizes, scores or deployment claims.
