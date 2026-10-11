"""Resolve exact supplied list; cache publisher metadata, never infer identities."""
import concurrent.futures, datetime, html, json, re, time, urllib.request, urllib.parse
from pathlib import Path
BASE=Path(__file__).resolve().parent
CACHE=BASE/'metadata-cache'; CACHE.mkdir(exist_ok=True)
ABS=BASE/'abstracts'; ABS.mkdir(exist_ok=True)
records=json.loads((BASE/'reference-input.json').read_text())
kms={}
for b in re.split(r'(?m)^## \d+\. ',(BASE/'kms-note.txt').read_text())[1:]:
    title=b.splitlines()[0]
    fields=dict(re.findall(r'^- ([^:]+): (.*)$',b,re.M))
    for u in re.findall(r'https://\S+',b): kms[u.rstrip('/')]=dict(title=title,fields=fields,original_block=b)
def fetch(url,path,accept='application/json'):
    if path.exists(): return json.loads(path.read_text())
    d={'request_url':url,'retrieved_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    for attempt in range(3):
        try:
            req=urllib.request.Request(url,headers={'Accept':accept,'User-Agent':'ThesisReferenceMetadata/1.0 (bibliographic verification)'})
            with urllib.request.urlopen(req,timeout=35) as r:
                body=r.read().decode('utf-8'); d.update(status=r.status,response_url=r.url,body=body)
            break
        except Exception as e:
            d['error']=str(e)
            if attempt<2: time.sleep(1+attempt)
    path.write_text(json.dumps(d,ensure_ascii=False,indent=2))
    return d
def clean(s): return html.unescape(re.sub('<[^>]+>','',s)).strip()
def work(r):
    r.update(evidence_level='metadata',full_text_appraised=False,handoff_evidence_level='abstract/catalogue only; not independently full-text appraised',metadata={},status='unresolved',evidence=[])
    supplied=kms.get(r['url'].rstrip('/'))
    if supplied: r['supplied_note_metadata']=supplied; r['evidence'].append({'source':'kms-note.txt','kind':'abstract-level handoff, not verbatim abstract'})
    if 'doi.org/' in r['url']:
        doi=r['url'].split('doi.org/',1)[1];r['doi']=doi
        assert re.fullmatch(r'10\.\d{4,9}/\S+',doi)
        path=CACHE/(r['key']+'-crossref.json')
        raw=fetch('https://api.crossref.org/works/'+urllib.parse.quote(doi,safe=''),path)
        r['evidence'].append({'source':str(path.relative_to(BASE)),'kind':'Crossref response'})
        try: m=json.loads(raw.get('body','{}')).get('message',{})
        except Exception: m={}
        if not isinstance(m,dict): m={}
        if m.get('title'):
            r['metadata']=m;r['status']='resolved-crossref'
        else:
            path=CACHE/(r['key']+'-doi-csl.json'); raw=fetch(r['url'],path,'application/vnd.citationstyles.csl+json')
            r['evidence'].append({'source':str(path.relative_to(BASE)),'kind':'DOI content negotiation'})
            try: m=json.loads(raw.get('body','{}'))
            except Exception: m={}
            if isinstance(m,dict) and m.get('title'):r['metadata']=m;r['status']='resolved-doi'
    if not r['metadata'] and supplied:
        f=supplied['fields'];r['metadata']={'title':[supplied['title']],'author_literal':f.get('Authors'),'issued':{'date-parts':[[int(f['Year'])]]},'container-title':[f.get('Venue','')]};r['status']='resolved-supplied-note'
    m=r['metadata']; t=m.get('title','Unresolved bibliographic record');r['title']=clean(t[0] if isinstance(t,list) else t)
    if m.get('abstract'):
        text=clean(m['abstract']);p=ABS/(r['key']+'.txt');p.write_text('Source: '+r['url']+'\nEvidence: publisher-deposited abstract retrieved via Crossref/DOI metadata; no full-text appraisal.\n\n'+text+'\n')
        r['abstract_file']=str(p.relative_to(BASE));r['evidence_level']='abstract'
    if r['status']=='unresolved': r['resolution_note']='No exact bibliographic metadata supplied for this URL or successfully retrieved. Title/authors must not be inferred.'
    return r
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex: records=list(ex.map(work,records))
(BASE/'reference-catalogue.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
print(json.dumps({'count':len(records),'statuses':{s:sum(r['status']==s for r in records) for s in set(r['status'] for r in records)},'abstracts':sum(r['evidence_level']=='abstract' for r in records)},indent=2))
for r in records:print(r['key'],r['evidence_level'],r['title'])
