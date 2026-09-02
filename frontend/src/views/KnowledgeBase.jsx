// views/KnowledgeBase.jsx — Red Noir knowledge archive & document dossiers.

import { useEffect, useRef, useState } from 'react';
import { Icon } from '../icons.jsx';
import { SeverityTag, CatTag, Tag, SourceChip, IocTypeIcon, CopyBtn, fmtDate, SEVERITY, BlueprintCorners, TitleBlock, DimensionDivider, RedlineNote, StampButton } from '../ui.jsx';
import { useArticles, useArticle, useCategories, useArticleIndex } from '../lib/queries.js';

export default function KnowledgeBase({ openId, setOpenId, onAsk }) {
  const [query, setQuery] = useState('');
  const [cat, setCat] = useState('all');
  const [sev, setSev] = useState('all');
  const [sort, setSort] = useState('updated');

  // server-driven list + filters
  const articleListQuery = useArticles({ q: query, category: cat, severity: sev, sort });
  const categoryQuery = useCategories();
  const { data: list } = articleListQuery;
  const { data: categories } = categoryQuery;
  const { index: catIndex } = useCategoryIndex(categories);
  const { index: articleIndex } = useArticleIndex();

  const items = (list && list.items) || [];
  const total = (list && list.total) != null ? list.total : items.length;

  // detail panel
  const articleDetailQuery = useArticle(openId);
  const { data: open } = articleDetailQuery;
  const isSplit = !!openId;
  const surfaced = articleListQuery.isPending ? '—' : articleListQuery.isError ? 'UNAVAILABLE' : total.toLocaleString();

  return (
    <div className={'kb' + (isSplit ? ' kb-split' : '')}>
      <div className="kb-main">
        <div className="view-head">
          <div>
            <h1 className="view-title">Knowledge Base</h1>
            <p className="view-sub">{surfaced} documents · live archive query</p>
          </div>
          <div className="kb-search">
            <Icon name="search" size={15} />
            <input
              aria-label="Search knowledge base"
              placeholder="Search title, tag, IOC, CVE id, spec number…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            {query && <button type="button" aria-label="Clear knowledge-base search" onClick={() => setQuery('')}><Icon name="x" size={13} /></button>}
          </div>
        </div>

        <div className="kb-cats" role="group" aria-label="Knowledge-base collection filter">
          <button type="button" aria-pressed={cat === 'all'} className={'cat-chip' + (cat === 'all' ? ' active' : '')} onClick={() => setCat('all')}>
            <Icon name="grid" size={13} /> ALL
          </button>
          {(categories || []).map((c) => (
            <button type="button" aria-pressed={cat === c.id} key={c.id} className={'cat-chip' + (cat === c.id ? ' active' : '')} onClick={() => setCat(c.id)}>
              {c.label} <span className="cat-chip-n">({c.count})</span>
            </button>
          ))}
          {categoryQuery.isPending && <span className="cat-chip" role="status">LOADING…</span>}
          {categoryQuery.isError && (
            <span className="cat-chip" role="alert">
              CATEGORIES UNAVAILABLE
              <StampButton tone="redline" text="RETRY" onClick={() => categoryQuery.refetch()} />
            </span>
          )}
        </div>

        <div className="kb-toolbar">
          <div className="kb-filters">
            <span className="kb-filter-label"><Icon name="filter" size={13} /> SEVERITY</span>
            <div className="sev-group" role="group" aria-label="Severity filter">
              {['all', 'critical', 'high', 'medium', 'low', 'info'].map((s) => (
                <button type="button" aria-pressed={sev === s} key={s} className={'sev-chip' + (sev === s ? ' active' : '')} onClick={() => setSev(s)}>
                  {s === 'all' ? 'ALL' : SEVERITY[s].label}
                </button>
              ))}
            </div>
          </div>
          <div className="kb-sort">
            <label htmlFor="kb-sort">SORT BY</label>
            <select id="kb-sort" value={sort} onChange={(e) => setSort(e.target.value)}>
              <option value="updated">Recently Updated</option>
              <option value="severity">Severity Rank</option>
              <option value="reads">Read Frequency</option>
            </select>
          </div>
        </div>

        <div className={'kb-grid' + (isSplit ? ' compact' : '')} aria-busy={articleListQuery.isPending}>
          {articleListQuery.isPending && (
            <div className="kb-empty" role="status">
              <Icon name="refresh" size={24} />
              <p>Retrieving archive…</p>
            </div>
          )}
          {articleListQuery.isError && (
            <div className="kb-empty" role="alert">
              <Icon name="alert" size={24} />
              <p>Archive query failed — {String(articleListQuery.error?.message || articleListQuery.error)}</p>
              <StampButton tone="redline" text="RETRY" onClick={() => articleListQuery.refetch()} />
            </div>
          )}
          {articleListQuery.isSuccess && items.map((a, index) =>
            isSplit ? (
              <ArticleRow key={a.id} a={a} index={index} active={a.id === openId} onClick={() => setOpenId(a.id)} />
            ) : (
              <ArticleCard key={a.id} a={a} index={index} active={a.id === openId} onClick={() => setOpenId(a.id)} catIndex={catIndex} />
            )
          )}
          {articleListQuery.isSuccess && items.length === 0 && (
            <div className="kb-empty">
              <Icon name="search" size={24} />
              <p>No documents match your current filters</p>
            </div>
          )}
        </div>
      </div>

      {openId && articleDetailQuery.isPending && (
        <ArticleDetailState status="loading" onClose={() => setOpenId(null)} />
      )}
      {openId && articleDetailQuery.isError && (
        <ArticleDetailState
          status="error"
          error={articleDetailQuery.error}
          onClose={() => setOpenId(null)}
          onRetry={() => articleDetailQuery.refetch()}
        />
      )}
      {openId && articleDetailQuery.isSuccess && open && (
        <ArticleDetail a={open} onClose={() => setOpenId(null)} onOpen={setOpenId} onAsk={onAsk} articleIndex={articleIndex} />
      )}
      {openId && articleDetailQuery.isSuccess && !open && (
        <ArticleDetailState status="error" error="Dossier response was empty." onClose={() => setOpenId(null)} onRetry={() => articleDetailQuery.refetch()} />
      )}
    </div>
  );
}

function useCategoryIndex(categories) {
  const index = new Map();
  for (const c of categories || []) index.set(c.id, c);
  return { index };
}

function ArticleCard({ a, index, active, onClick, catIndex }) {
  return (
    <button type="button" aria-pressed={active} className={'article-card' + (active ? ' active' : '')} onClick={onClick}>
      <BlueprintCorners coords={a.id} />
      <div className="article-card-top">
        <span className="article-id">{a.id}</span>
        <SeverityTag level={a.severity} small />
      </div>
      <h3 className="article-card-title">{a.title}</h3>
      <p className="article-card-snippet">{a.snippet}</p>
      <div className="article-card-foot">
        <CatTag id={a.category} index={catIndex} />
        <span className="article-meta-dot">·</span>
        <span className="article-card-date"><Icon name="clock" size={11} /> {fmtDate(a.updated)}</span>
        {a.iocs && a.iocs.length > 0 && (
          <>
            <span className="article-meta-dot">·</span>
            <span className="article-card-ioc"><Icon name="hash" size={11} /> {a.iocs.length} IOC</span>
          </>
        )}
      </div>
    </button>
  );
}

function ArticleRow({ a, index, active, onClick }) {
  return (
    <button type="button" aria-pressed={active} className={'article-row' + (active ? ' active' : '')} onClick={onClick}>
      <span className="article-row-id">{a.id}</span>
      <span className="article-row-title">{a.title}</span>
      <SeverityTag level={a.severity} small />
    </button>
  );
}

function ArticleDetailState({ status, error, onClose, onRetry }) {
  return (
    <section className="article-detail" aria-label="Article detail" aria-busy={status === 'loading'}>
      <div className="article-detail-bar">
        <button type="button" className="ad-back" onClick={onClose}><Icon name="arrowleft" size={14} /> BACK</button>
      </div>
      <div className="article-detail-scroll">
        <div className="kb-empty" role={status === 'error' ? 'alert' : 'status'}>
          <Icon name={status === 'error' ? 'alert' : 'refresh'} size={24} />
          <p>
            {status === 'error'
              ? `Document retrieval failed — ${String(error?.message || error || '')}`
              : 'Retrieving document…'}
          </p>
          {status === 'error' && <StampButton tone="redline" text="RETRY" onClick={onRetry} />}
        </div>
      </div>
    </section>
  );
}

function ArticleDetail({ a, onClose, onOpen, onAsk, articleIndex }) {
  const titleRef = useRef(null);

  useEffect(() => {
    titleRef.current?.focus();
  }, [a.id]);

  return (
    <section className="article-detail" aria-labelledby="article-detail-title">
      <BlueprintCorners coords={a.id} />
      <div className="article-detail-bar">
        <div className="adbar-left">
          <button type="button" className="ad-back" onClick={onClose}><Icon name="arrowleft" size={14} /> BACK</button>
          <span className="article-id big">{a.id}</span>
        </div>
        <div className="article-detail-actions">
          <StampButton className="ad-btn" action="CONSULT" context="AI" onClick={() => onAsk(`Tell me more about ${a.id}: ${a.title}`)}>
            <Icon name="chat" size={13} /> ASK AI
          </StampButton>
          <button type="button" className="ad-btn icon" onClick={onClose} title="Close document" aria-label="Close document">
            <Icon name="x" size={15} />
          </button>
        </div>
      </div>

      <div className="article-detail-scroll">
        <TitleBlock sheetNo={a.id} title={a.title?.substring(0, 24).toUpperCase()} scale="1:1" rev="REV 2.1" />

        <div className="article-detail-head" style={{ marginTop: 16 }}>
          <SeverityTag level={a.severity} />
          {a.cvss != null && <span className="cvss-badge">CVSS {a.cvss}</span>}
          <span className="confidence">CONFIDENCE {a.confidence}</span>
          {a.cvss != null && a.cvss >= 7.0 && <RedlineNote circle>// high-risk vector</RedlineNote>}
        </div>

        <h2 id="article-detail-title" ref={titleRef} tabIndex={-1} className="article-detail-title">{a.title}</h2>

        <div className="article-detail-meta">
          <span><Icon name="database" size={12} /> {a.source}</span>
          <span><Icon name="user" size={12} /> {a.author}</span>
          <span><Icon name="clock" size={12} /> {fmtDate(a.updated)}</span>
          <span><Icon name="book" size={12} /> {(a.reads || 0).toLocaleString()} READS</span>
        </div>

        <div className="article-tags">
          {(a.tags || []).map((t) => <Tag key={t}>{t}</Tag>)}
        </div>

        {a.iocs && a.iocs.length > 0 && (
          <div className="ioc-block">
            <BlueprintCorners coords="IOC" />
            <div className="ioc-block-head"><Icon name="hash" size={13} /> Indicators of Compromise</div>
            <div className="ioc-list">
              {a.iocs.map((ioc, i) => (
                <div className="ioc-row" key={i}>
                  <span className="ioc-type"><IocTypeIcon type={ioc.type} /> {ioc.type}</span>
                  <code className="ioc-value">{ioc.value}</code>
                  <CopyBtn className="ioc-copy" text={ioc.value} />
                </div>
              ))}
            </div>
          </div>
        )}

        <DimensionDivider label="DOCUMENT BODY" dim="SECTION 01" />

        <div className="article-body">
          {(a.body || []).map((sec, i) => (
            <div className="article-section" key={i}>
              <h4>{sec.h}</h4>
              <p>{sec.p}</p>
            </div>
          ))}
        </div>

        {a.related && a.related.length > 0 && (
          <div className="related-block">
            <div className="related-head"><Icon name="link" size={13} /> RELATED DOCUMENTS</div>
            <div className="related-list">
              {a.related.map((id) => <SourceChip key={id} id={id} onOpen={onOpen} index={articleIndex} />)}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
