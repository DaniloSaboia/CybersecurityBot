# Evaluation evidence reconciliation

## Author correction

The researcher confirms completing model comparisons and human evaluation personally and has requested an Excel template for transferring the results. Missing outcome records in the writing workspace do not establish that these experiments were not conducted. Remove categorical statements that deny their existence; finalise measured outcomes when the primary results arrive.

## Sources verified directly after the independent search

1. `docs/openrouter-free-models.md`, lines 27–45: the dated 6 September 2026 record describes live embedding compatibility checks. At a request dimension of 1024, `nvidia/llama-nemotron-embed-vl-1b-v2:free` and `liquid/lfm-2.5-embedding-350m:free` returned 1024-dimensional vectors with a reported cost of zero; `nvidia/nemotron-3-embed-1b:free` returned HTTP 400 requiring 2048 dimensions. The same record describes a completed chat request, a batch of two embeddings and a synthetic pgvector write/query rolled back afterward. These are documented operational observations, not repeated answer-quality estimates. The number of repetitions and raw response logs are not present in this source. Do not infer that matching dimension makes models' embedding spaces compatible.

2. `docs/api-contract.md`, lines 109–129: a retrieval-response example dated 2 September 2026 contains ten cases, cutoff six, mean recall 0.92, MRR 0.87, nDCG 0.88 and nine passing cases, but only one detailed result is printed. Its position as a contract example and the absent complete run mean these numbers require the researcher's provenance confirmation before entering the results tables. Do not classify them as measured or fabricated solely from the example.

3. The author's original thesis at `/Users/danilopalaciosaboia/Downloads/Tese_DaniloPalácioSabóia_88790.pdf`, PDF page 27, section 1.4, states that literature review was followed by semi-structured interviews with a research specialist to identify use cases, user roles and requirements. This supports a description of qualitative requirements gathering. It does not identify session dates/counts, a coding method, an outcome-study participant sample or questionnaire scores. The work plan on page 28 does not itself document completed outcome evaluation. The author’s original research framing on pages 25–28 concerns knowledge reuse, incident response and teams with limited resources; implementation-dependent claims such as automatic continuous learning must still match the developed system.

The original cover's institutional information and date are historical, not automatically current submission metadata. Its earlier literature screening counts are also not a substitute for the final review's search/selection records.

## Writing treatment

Describe provider compatibility in the technical evaluation, requirements interviews in the methodology, and recorded model and participant outcomes in their own result sections. Keep test-fixture values out of measured result tables. Use the original thesis as project context without copying its unsupported generalisations. Do not print these filenames or Git details in the reader-facing dissertation.

The primary controlled-comparison and participant-outcome data are awaiting transfer through `Thesis_Evaluation_Data_Template.xlsx` or the researcher's existing equivalent records. No study results were invented or rerun during this reconciliation.
