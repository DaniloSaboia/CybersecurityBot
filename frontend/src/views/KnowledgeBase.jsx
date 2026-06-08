// views/KnowledgeBase.jsx — knowledge base browser + article detail panel.

import { useState } from 'react';
import { Icon } from '../icons.jsx';
import { SeverityTag, CatTag, Tag, SourceChip, IocTypeIcon, CopyBtn, fmtDate, SEVERITY } from '../ui.jsx';
import { useArticles, useArticle, useCategories, useArticleIndex } from '../lib/queries.js';

export default function KnowledgeBase({ openId, setOpenId, onAsk }) {
  const [query, setQuery] = useState('');
  const [cat, setCat] = useState('all');
  const [sev, setSev] = useState('all');
  const [sort, setSort] = useState('updated');

  // server-driven list + filters
  const { data: list } = useArticles({ q: query, category: cat, severity: sev, sort });
  const { data: categories } = useCategories();
  const { index: catIndex } = useCategoryIndex(categories);
  const { index: articleIndex } = useArticleIndex();

  const items = (list && list.items) || [];
  const total = (list && list.total) != null ? list.total : items.length;

  // detail panel
  const { data: open } = useArticle(openId);
  const isSplit = !!openId;

  return (
    <div className={'kb' + (isSplit ? ' kb-split' : '')}>
      <div className="kb-main">
        <div className="view-head">
          <div>
            <h1 className="view-title">Knowledge Base</h1>
            <p className="view-sub">{total} surfaced · 764 indexed across 6 collections</p>
          </div>
          <div className="kb-search">
            <Icon name="search" size={15} />
            <input placeholder="Search title, tag, IOC, CVE id…" value={query} onChange={(e) => setQuery(e.target.value)} />
            {query && <button onClick={() => setQuery('')}><Icon name="x" size={13} /></button>}
          </div>
        </div>

        <div className="kb-cats">
          <button className={'cat-chip' + (cat === 'all' ? ' active' : '')} onClick={() => setCat('all')}>
            <Icon name="grid" size={13} /> All
          </button>
          {(categories || []).map((c) => (
            <button key={c.id} className={'cat-chip' + (cat === c.id ? ' active' : '')} onClick={() => setCat(c.id)}>
              {c.label} <span className="cat-chip-n">{c.count}</span>
            </button>
          ))}
        </div>

        <div className="kb-toolbar">
          <div className="kb-filters">
            <span className="kb-filter-label"><Icon name="filter" size={13} /> Severity</span>
            <div className="sev-group">
              {['all', 'critical', 'high', 'medium', 'low', 'info'].map((s) => (
                <button key={s} className={'sev-chip' + (sev === s ? ' active' : '')} onClick={() => setSev(s)}>
                  {s === 'all' ? 'All' : SEVERITY[s].label}
                </button>
              ))}
            </div>
          </div>
          <div className="kb-sort">
            <span>Sort</span>
            <select value={sort} onChange={(e) => setSort(e.target.value)}>
              <option value="updated">Recently updated</option>
              <option value="severity">Severity</option>
              <option value="reads">Most read</option>
            </select>
          </div>
        </div>

        <div className={'kb-grid' + (isSplit ? ' compact' : '')}>
          {items.map((a) =>
            isSplit ? (
              <ArticleRow key={a.id} a={a} active={a.id === openId} onClick={() => setOpenId(a.id)} />
            ) : (
              <ArticleCard key={a.id} a={a} active={a.id === openId} onClick={() => setOpenId(a.id)} catIndex={catIndex} />
            )
          )}
          {items.length === 0 && <div className="kb-empty"><Icon name="search" size={22} /><p>No entries match those filters.</p></div>}
        </div>
      </div>

      {open && <ArticleDetail a={open} onClose={() => setOpenId(null)} onOpen={setOpenId} onAsk={onAsk} articleIndex={articleIndex} />}
    </div>
  );
}

// build a Map id->category for CatTag lookups
function useCategoryIndex(categories) {
  const index = new Map();
  for (const c of categories || []) index.set(c.id, c);
  return { index };
}

function ArticleCard({ a, active, onClick, catIndex }) {
  return (
    <button className={'article-card' + (active ? ' active' : '')} onClick={onClick}>
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
        {a.iocs && a.iocs.length > 0 && (<><span className="article-meta-dot">·</span><span className="article-card-ioc"><Icon name="hash" size={11} /> {a.iocs.length} IOC</span></>)}
      </div>
    </button>
  );
}

function ArticleRow({ a, active, onClick }) {
  return (
    <button className={'article-row' + (active ? ' active' : '')} onClick={onClick}>
      <span className="article-row-id">{a.id}</span>
      <span className="article-row-title">{a.title}</span>
      <SeverityTag level={a.severity} small />
    </button>
  );
}

function ArticleDetail({ a, onClose, onOpen, onAsk, articleIndex }) {
  return (
    <div className="article-detail">
      <div className="article-detail-bar">
        <div className="adbar-left">
          <button className="ad-back" onClick={onClose}><Icon name="arrowleft" size={15} /> Back</button>
          <span className="article-id big">{a.id}</span>
        </div>
        <div className="article-detail-actions">
          <button className="ad-btn" onClick={() => onAsk(`Tell me more about ${a.id}: ${a.title}`)}><Icon name="chat" size={14} /> Ask assistant</button>
          <button className="ad-btn icon" onClick={onClose} title="Close"><Icon name="x" size={16} /></button>
        </div>
      </div>
      <div className="article-detail-scroll">
        <div className="article-detail-head">
          <SeverityTag level={a.severity} />
          {a.cvss && <span className="cvss-badge">CVSS {a.cvss}</span>}
          <span className="confidence">Confidence: {a.confidence}</span>
        </div>
        <h2 className="article-detail-title">{a.title}</h2>
        <div className="article-detail-meta">
          <span><Icon name="database" size={12} /> {a.source}</span>
          <span><Icon name="user" size={12} /> {a.author}</span>
          <span><Icon name="clock" size={12} /> {fmtDate(a.updated)}</span>
          <span><Icon name="book" size={12} /> {(a.reads || 0).toLocaleString()} reads</span>
        </div>

        <div className="article-tags">
          {(a.tags || []).map((t) => <Tag key={t}>{t}</Tag>)}
        </div>

        {a.iocs && a.iocs.length > 0 && (
          <div className="ioc-block">
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
            <div className="related-head"><Icon name="link" size={13} /> Related entries</div>
            <div className="related-list">
              {a.related.map((id) => <SourceChip key={id} id={id} onOpen={onOpen} index={articleIndex} />)}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
