# Sentinel response starter pack

The bundled pack supplies 16 practical defensive playbooks for small organizations and the people reporting security problems. It turns a symptom such as “I clicked a link” or “files are encrypted” into questions, prioritized actions, evidence, recovery checks and an escalation path.

It is original AI-assisted editorial synthesis, **not expert operational approval**. The source-check date is **2026-09-29**, pack version **2026-09-29.1**. Checking a source today does not mean that the publisher updated it today or that the guidance was tested against this organization's systems.

## Content and scope

The canonical content is [security-playbooks.json](../backend/src/main/resources/knowledge/security-playbooks.json). Each article belongs to `playbooks`, has the `response-playbook` tag and uses the existing `ArticleSection` shape (`h` heading and `p` text). Source links and review limitations are stored in article body sections so they travel with retrieved content.

| Article | Problem addressed | Potential impact |
| --- | --- | --- |
| `PB-START-001` | Something is wrong: triage a suspected security incident | high |
| `PB-START-002` | I clicked a suspicious link: phishing and malicious messages | high |
| `PB-START-003` | A payment request looks wrong: business email compromise | critical |
| `PB-START-004` | Files are encrypted: ransomware and extortion response | critical |
| `PB-START-005` | A computer may be infected: endpoint malware | high |
| `PB-START-006` | My account was taken over: passwords, MFA and session theft | high |
| `PB-START-007` | An API key was exposed: leaked cloud or application secrets | critical |
| `PB-START-008` | A critical vulnerability affects us: prioritize and remediate | critical |
| `PB-START-009` | Our website may be breached: web application and API compromise | critical |
| `PB-START-010` | Sensitive data may have left: exfiltration and data breach | critical |
| `PB-START-011` | Our service is overwhelmed: suspected DDoS | high |
| `PB-START-012` | A work device is missing: lost or stolen laptop or phone | high |
| `PB-START-013` | An insider may be misusing access: investigate fairly | high |
| `PB-START-014` | A supplier or software update is compromised | critical |
| `PB-START-015` | Restore safely: backups and recovery after an incident | high |
| `PB-START-016` | Cloud files are public: exposed storage and sharing | high |

Severity is a navigation cue for possible impact, not a verdict about an incident. The pack contains no invented incident reports, fake indicators, fabricated CVSS values or seeded readership. It does not cover every threat, and it is not an operational technology, industrial safety, malware reverse engineering or jurisdiction-specific legal manual.

Each playbook contains:

1. Symptoms and scope.
2. Questions that distinguish different response branches.
3. Ordered first actions, with sensitive changes assigned to authorized responders.
4. Evidence to preserve.
5. Containment and recovery.
6. Observable checks before declaring recovery.
7. Prevention and escalation.
8. A decision checkpoint explaining common mistakes and responsible ownership.
9. Direct authoritative-source links.
10. A source-check date and human-review boundary.

The decision checkpoints are Sentinel's implementation suggestions, not quotations or claims that a publisher approved this product. Adapt them with the relevant system owner. The pack does not run commands, contact third parties, change infrastructure or perform remediation automatically.

## Installation behavior

The application configuration `app.knowledge-pack.enabled` defaults to true and can be controlled with `KNOWLEDGE_PACK_ENABLED`. The dedicated `CuratedKnowledgePackLoader` runs independently of the optional demonstration seed, so an already-populated knowledge base can receive these articles.

The loader validates the complete resource before database changes, adds missing identities, and sends content through the existing trusted registration and canonical indexing pipeline. Existing article identities are preserved. If bundled content differs from an existing article, startup reports a review conflict instead of silently replacing that article or its governance.

This document describes installation behavior; it does not by itself prove any hosted database has been updated or that embedding jobs have finished. Confirm startup output, stored articles and indexing status in the running environment.

To disable future loading, set `KNOWLEDGE_PACK_ENABLED=false` and restart the backend. This does not delete already-published knowledge.

## Editorial and operational review

Before adopting a playbook as an organization's runbook:

- Name an accountable incident lead, technical owner and backup contact.
- Replace generic provider actions with the organization's supported product procedures.
- Agree authority, service priorities, reversal steps and evidence storage.
- Validate each recovery check in a tabletop or controlled test.
- Have the privacy/legal owner supply relevant reporting obligations and local contacts.
- Record the reviewer, tested environment, date and next review trigger through the knowledge revision workflow.

Review again when a cited vendor procedure changes, a source becomes obsolete, a service architecture changes or a real incident exposes a gap. Do not move `sourceCheckedOn` forward without rechecking sources. Changing the pack version does not automatically replace existing articles; use reviewed revisions for substantive updates.

For an active incident, a user should give Sentinel non-sensitive facts: observed symptom, affected service, approximate time, action already taken and current impact. Do not submit passwords, live tokens, complete personal datasets or confidential malware samples in a chat. The product should ask for missing context, label uncertainty, cite the applicable playbook and identify the next responsible human.

## Source checks and attribution

The sources below were consulted on 2026-09-29. “Publisher content read through search” means the publisher's indexed text was available; it is not a claim that every linked page could be fetched directly. CISA's KEV page and two CISA PDFs could not be retrieved directly in this environment; indexed publisher content was available and that limitation is retained in the JSON registry. No quoted passages or wholesale third-party documents are included.

NIST SP 800-61 Rev. 3 is the 2025 publication. NIST SP 800-83 Rev. 1 is older (2013); it supplies general malware-handling concepts, while current endpoint product procedures still need local verification. Vendor material applies to that vendor and is illustrative where a playbook also discusses other platforms. US and UK source material does not set Portuguese or other local legal obligations.

| Source ID | Reference | Publisher | Verification |
| --- | --- | --- | --- |
| `nist-ir` | [NIST SP 800-61 Rev. 3: incident response](https://csrc.nist.gov/pubs/sp/800/61/r3/final) | NIST | Publisher page and full PDF read |
| `ncsc-phishing` | [Phishing: actions to take](https://www.ncsc.gov.uk/section/respond-recover/phishing) | UK NCSC | Publisher content read through search |
| `fbi-bec` | [Business Email Compromise](https://www.fbi.gov/how-we-can-help-you/common-frauds-and-scams/business-email-compromise) | FBI | Publisher content read through search |
| `cisa-ransomware` | [#StopRansomware Guide](https://www.cisa.gov/stopransomware/ransomware-guide) | CISA | Publisher content read through search |
| `nist-malware` | [NIST SP 800-83 Rev. 1: malware prevention and handling](https://csrc.nist.gov/pubs/sp/800/83/r1/final) | NIST | Publisher page and full PDF read; 2013 guidance, use current product procedures |
| `ms-account` | [Respond to a compromised email account in Microsoft 365](https://learn.microsoft.com/en-us/defender-office-365/responding-to-a-compromised-email-account) | Microsoft | Publisher page read |
| `ncsc-account` | [Recovering a hacked account](https://www.ncsc.gov.uk/guidance/recovering-a-hacked-account) | UK NCSC | Publisher content read through search |
| `aws-keys` | [Practical steps to minimize key exposure using AWS Security Services](https://aws.amazon.com/blogs/security/practical-steps-to-minimize-key-exposure-using-aws-security-services/) | AWS | Publisher page read |
| `owasp-secrets` | [Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | OWASP | Publisher page read |
| `cisa-kev` | [Known Exploited Vulnerabilities Catalog](https://www.cisa.gov/known-exploited-vulnerabilities-catalog) | CISA | Publisher search-index text read; direct retrieval unavailable |
| `cisa-response` | [Cybersecurity Incident and Vulnerability Response Playbooks](https://www.cisa.gov/sites/default/files/publications/Cybersecurity_Incident_Vulnerability_Response_Playbooks_508C.pdf) | CISA | Publisher search-index text read |
| `owasp-logging` | [Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) | OWASP | Publisher page read |
| `owasp-sqli` | [SQL Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html) | OWASP | Publisher page read |
| `owasp-authz` | [Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) | OWASP | Publisher page read |
| `ftc-breach` | [Data Breach Response: A Guide for Business](https://www.ftc.gov/business-guidance/resources/data-breach-response-guide-business) | FTC | Publisher content read through search |
| `cloudflare-ddos` | [Under a DDoS attack?](https://developers.cloudflare.com/fundamentals/security/under-ddos-attack/) | Cloudflare | Publisher content read through search |
| `cloudflare-defense` | [Proactive DDoS defense](https://developers.cloudflare.com/ddos-protection/best-practices/proactive-defense/) | Cloudflare | Publisher content read through search |
| `cloudflare-challenge` | [Under Attack mode](https://developers.cloudflare.com/fundamentals/reference/under-attack-mode/) | Cloudflare | Publisher content read through search |
| `apple-lost` | [How to find your lost iPhone or iPad](https://support.apple.com/en-ie/101593) | Apple | Publisher page read |
| `cisa-insider` | [Insider Threat Mitigation Guide](https://www.cisa.gov/sites/default/files/2022-11/Insider%20Threat%20Mitigation%20Guide_Final_508.pdf) | CISA | Publisher search-index text read; direct retrieval unavailable |
| `cisa-supply` | [Defending Against Software Supply Chain Attacks](https://www.cisa.gov/sites/default/files/publications/defending_against_software_supply_chain_attacks_508_1.pdf) | CISA | Publisher search-index text read; direct retrieval unavailable |
| `ftc-small` | [Cybersecurity for Small Business](https://www.ftc.gov/business-guidance/small-businesses/cybersecurity) | FTC | Publisher content read through search |
| `ncsc-backups` | [Ransomware-resistant backups](https://www.ncsc.gov.uk/collection/ransomware-resistant-backups) | UK NCSC | Publisher page read |
| `ncsc-recovery` | [Mitigating malware and ransomware attacks](https://www.ncsc.gov.uk/guidance/mitigating-malware-and-ransomware-attacks) | UK NCSC | Publisher page read |
| `aws-s3` | [Blocking public access to your Amazon S3 storage](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html) | AWS | Publisher content read through search |

## Validation checklist

Content integrity checks should confirm 16 unique stable article IDs, declared categories, internal related-article references, HTTPS source links, valid dates, empty generic IOC lists, zero invented reads and the explicit confidence value `Source-informed; human review required`.

Application checks should confirm that the pack loads on an existing database, repeated startup creates no duplicate revisions, changed local content is preserved for review, the playbooks appear in the knowledge library, source links are usable, and common symptom searches retrieve the relevant guidance. A passing content check does not replace operational review or prove an incident was resolved.

