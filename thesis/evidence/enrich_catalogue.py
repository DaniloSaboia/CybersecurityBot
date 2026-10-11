"""Retrieve selected abstracts without converting handoff claims to direct appraisal."""
import concurrent.futures,datetime,html,json,re,urllib.request,urllib.parse
from pathlib import Path
B=Path(__file__).resolve().parent
rows=json.loads((B/'reference-catalogue.json').read_text())
selected={1,2,11,12,23,30,32,33,38,39,40,41,43,45,48,52,66,67,68,70,72,74,75,84,90,93,94,95,97}
def run(r):
 if int(r['key'][3:]) not in selected or r.get('abstract_file') or not r.get('doi'):return r
 url='https://api.openalex.org/works/https://doi.org/'+urllib.parse.quote(r['doi'],safe='/')
 p=B/'metadata-cache'/(r['key']+'-openalex.json')
 d={'request_url':url,'retrieved_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'ThesisBibliography/1.0'}),timeout=30) as x:d.update(status=x.status,body=x.read().decode())
  m=json.loads(d['body']);inv=m.get('abstract_inverted_index')
  if inv:
   assert m.get('doi','').lower()=='https://doi.org/'+r['doi'].lower(), 'OpenAlex DOI identity mismatch'
   words={i:w for w,poss in inv.items() for i in poss};text=' '.join(words[i] for i in sorted(words))
   ap=B/'abstracts'/(r['key']+'.txt');ap.write_text('Source: '+url+'\nEvidence: OpenAlex indexed abstract, not full-text appraisal.\n\n'+text+'\n')
   r.update(abstract_file=str(ap.relative_to(B)),evidence_level='abstract')
 except Exception as e:d['error']=str(e)
 p.write_text(json.dumps(d,ensure_ascii=False,indent=2));r['evidence'].append({'source':str(p.relative_to(B)),'kind':'OpenAlex abstract retrieval attempt'})
 return r
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:rows=list(ex.map(run,rows))
(B/'reference-catalogue.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
for r in rows:
 if int(r['key'][3:]) in selected:print(r['key'],r['evidence_level'],r['title'])
