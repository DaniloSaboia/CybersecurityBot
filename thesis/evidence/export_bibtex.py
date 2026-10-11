"""Export verified catalogue to classic BibTeX without adding inferred facts."""
import collections,html,json,re
from pathlib import Path
B=Path(__file__).resolve().parent
rs=json.loads((B/'reference-catalogue.json').read_text())
for record in rs:
 if record['key'] in ('ref039','ref040'):
  filename='direct-source-1.json' if record['key']=='ref039' else 'direct-source-2.json'
  correction={'source':filename,'kind':'Direct paper abstract and selected methods inspected; OpenAlex abstract field was bibliographic metadata only'}
  if correction not in record['evidence']:record['evidence'].append(correction)
  record['selected_full_text_methods_inspected']=True
 if record['key']=='ref041':
  correction={'source':'https://aclanthology.org/2024.acl-long.585/','kind':'Direct ACL abstract; OpenAlex indexed abstract field was bibliographic metadata only'}
  if correction not in record['evidence']:record['evidence'].append(correction)
def esc(s):
 s=html.unescape(re.sub('<[^>]+>','',str(s)))
 table={'\\':r'\textbackslash{}','&':r'\&','%':r'\%','$':r'\$','#':r'\#','_':r'\_','{':r'\{','}':r'\}','~':r'\textasciitilde{}','^':r'\textasciicircum{}'}
 # Preserve the deposited e-with-cedilla identity with a TeX accent;
 # Latin Modern lacks the precomposed U+0229 glyph. Do not silently
 # correct the author's name to a different Unicode character.
 table['ȩ']=r'{\fontencoding{T1}\fontfamily{lmr}\selectfont\c{e}}'
 return ''.join(table.get(c,c) for c in s)
def first(v): return v[0] if isinstance(v,list) and v else v
bib=['% Exact recommendation References links only order. No full-text study appraisal.\n% Unresolved placeholders are deliberately preserved; do not cite them as substantive evidence.\n']
url_groups=collections.defaultdict(list);doi_groups=collections.defaultdict(list);title_groups=collections.defaultdict(list)
for r in rs:
 url_groups[r['url']].append(r['key'])
 if r.get('doi'):doi_groups[r['doi'].lower()].append(r['key'])
 if r['status']!='unresolved':title_groups[re.sub(r'\W+','',r['title'].lower())].append(r['key'])
for r in rs:
 m=r['metadata'];r['duplicate_keys']=sorted(set(url_groups[r['url']]+doi_groups.get(r.get('doi','').lower(),[])+title_groups.get(re.sub(r'\W+','',r['title'].lower()),[]))-{r['key']})
 if m.get('DOI'):assert m['DOI'].lower()==r['doi'].lower(),r['key']
 typ={'journal-article':'article','article-journal':'article','proceedings-article':'inproceedings','paper-conference':'inproceedings','book-chapter':'incollection','book':'book'}.get(m.get('type'),'misc')
 if r['status']=='unresolved':typ='misc'
 f={'title':'{'+esc(r['title'])+'}'}
 authors=[]
 for a in m.get('author',[]):
  if a.get('family'):authors.append(esc(a['family'])+(', '+esc(a['given']) if a.get('given') else ''))
  elif a.get('name') or a.get('literal'):authors.append('{'+esc(a.get('name') or a['literal'])+'}')
 if m.get('author_literal'):authors=[esc(x.strip()) for x in m['author_literal'].split(';')]
 if authors:f['author']=' and '.join(authors)
 for dkey in ['published-print','published','issued','published-online']:
  dp=m.get(dkey,{}).get('date-parts',[])
  if dp and dp[0]:f['year']=str(dp[0][0]);r['bibtex_year_source']=dkey;break
 ct=first(m.get('container-title',[]))
 if ct:
  f['journal' if typ=='article' else 'booktitle' if typ in ('inproceedings','incollection') else 'howpublished']=esc(ct)
 for source,target in [('volume','volume'),('issue','number'),('page','pages'),('publisher','publisher')]:
  if m.get(source):f[target]=esc(m[source]).replace('–','--') if target=='pages' else esc(m[source])
 if r.get('doi'):f['doi']=r['doi']
 f['url']=r['url']
 if r['status']=='unresolved':f['note']=esc('Unresolved bibliographic record. Exact supplied URL retained; title, authors, year and venue could not be verified. Not evidence for substantive claims.')
 elif r['status']=='resolved-supplied-note':f['note']=esc('Bibliographic metadata transcribed exactly from the supplied KMS note; not independently publisher-verified. Abstract-level handoff only.')
 bib.append('@'+typ+'{'+r['key']+',\n'+',\n'.join('  '+k+' = {'+v+'}' for k,v in f.items())+'\n}\n')
 r['bibtex_type']=typ
(B.parent/'references.bib').write_text('\n'.join(bib))
(B/'reference-catalogue.json').write_text(json.dumps(rs,ensure_ascii=False,indent=2))
counts=collections.Counter(r['status'] for r in rs);ev=collections.Counter(r['evidence_level'] for r in rs)
report={'reference_count':len(rs),'unique_urls':len(url_groups),'doi_records':sum(bool(r.get('doi')) for r in rs),'unique_dois':len(doi_groups),'status_counts':dict(counts),'evidence_counts':dict(ev),'duplicate_records':[r['key'] for r in rs if r['duplicate_keys']],'unresolved_keys':[r['key'] for r in rs if r['status']=='unresolved'],'all_full_text_appraised':False,'notes':'Evidence level measures directly retrieved evidence. Both supplied notes are abstract/catalogue handoffs, not primary full-text appraisals. Metadata verification does not validate recommendation claims. RAG benchmark ref038 is named RGB, not RETRIEVAL.'}
assert len(rs)==100 and [r['key'] for r in rs]==[f'ref{i:03d}' for i in range(1,101)]
for r in rs:
 if r.get('abstract_file'):assert (B/r['abstract_file']).is_file()
 if r['status']=='unresolved':assert r['title']=='Unresolved bibliographic record' and not r['metadata']
(B/'verification.json').write_text(json.dumps(report,indent=2))
lines=['# Reference map','',f"Verified exact supplied list: {len(rs)} records; {len(url_groups)} unique URLs; {len(doi_groups)} unique DOIs.",f"Resolution: {dict(counts)}. Direct evidence: {dict(ev)}.",'','**Evidence boundary:** recommendation and KMS claims arrived as abstract/catalogue-level handoffs. Retrieved abstracts are saved separately and do not amount to full-text methodological or quality appraisal. No record is marked full text. Metadata-only records do not independently establish the handoff claims.','', '**Naming caution:** ref038 introduces the Retrieval-Augmented Generation Benchmark (RGB). No resolved title identifies a benchmark called RETRIEVAL; do not silently substitute that name.','', '| Key | Exact verified title (or explicit placeholder) | DOI | Direct evidence |','|---|---|---|---|']
for r in rs:lines.append('| '+r['key']+' | '+r['title'].replace('|','\\|')+' | '+r.get('doi','—')+' | '+r['evidence_level']+(' (unresolved)' if r['status']=='unresolved' else ' (supplied-note metadata)' if r['status']=='resolved-supplied-note' else '')+' |')
lines+=['','## Remaining issues','', '- ref002: supplied DOI failed Crossref and DOI negotiation; exact identifier is preserved, not corrected.','- Nine Mendeley-only entries lack bibliographic metadata in either supplied note; placeholders are preserved. UCO (ref072) has exact metadata in kms-note.txt.','- The recommendation prose mentions 95 DOI-bearing candidates among 105; the actual accepted list contains 90 DOI URLs and ten Mendeley URLs. The list itself, not the earlier candidate count, controls this catalogue.','- Author initials and venue wording for UCO are retained exactly from the supplied note.','- Raw metadata API responses and retrieval errors are under metadata-cache/. The build script regenerates metadata; rerun enrich_catalogue.py before export_bibtex.py to preserve supplementary abstract enrichment.']
(B/'reference-map.md').write_text('\n'.join(lines)+'\n')
print(json.dumps(report,indent=2))
