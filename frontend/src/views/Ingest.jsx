// views/Ingest.jsx — ingestion pipeline, source connectors, upload + processing.

import { useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Icon } from '../icons.jsx';
import { StatusDot, ConnectorIcon } from '../ui.jsx';
import { useJobs, useConnectors, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { uploadFile, addUrl, retryJob } from '../lib/api.js';

const STAGE_LABEL = {
  extracting: 'Extracting', chunking: 'Chunking', embedding: 'Embedding',
  indexed: 'Indexed', failed: 'Failed', queued: 'Queued',
};

// fixed 5-stage pipeline model: ingest -> extract -> chunk -> embed -> index
const PIPELINE_STAGES = [
  { id: 'ingest', label: 'Ingest', desc: 'Pull from connectors or accept an upload' },
  { id: 'extract', label: 'Extract', desc: 'Parse text, tables and metadata from the source' },
  { id: 'chunk', label: 'Chunk', desc: 'Split into overlapping, retrieval-sized passages' },
  { id: 'embed', label: 'Embed', desc: 'Encode each chunk into a dense vector' },
  { id: 'index', label: 'Index', desc: 'Upsert vectors into the searchable store' },
];

export default function Ingest() {
  const [dragOver, setDragOver] = useState(false);
  const fileInputRef = useRef(null);

  const { data: jobs = [] } = useJobs();
  const { data: connectors = [] } = useConnectors();
  const { data: vector } = useVectorStoreStatus();

  function refreshJobs() { queryClient.invalidateQueries({ queryKey: ['jobs'] }); }

  const uploadMut = useMutation({ mutationFn: uploadFile, onSuccess: refreshJobs });
  const urlMut = useMutation({ mutationFn: addUrl, onSuccess: refreshJobs });
  const retryMut = useMutation({ mutationFn: retryJob, onSuccess: refreshJobs });

  function onPickFiles() { fileInputRef.current && fileInputRef.current.click(); }
  function onFilesSelected(e) {
    const f = e.target.files && e.target.files[0];
    if (f) uploadMut.mutate(f);
    e.target.value = '';
  }
  function onAddUrl() {
    const url = window.prompt('Document URL to ingest');
    if (url) urlMut.mutate(url);
  }

  // stats derive from the job list + vector store
  const active = jobs.filter((j) => !['indexed', 'failed'].includes(j.stage)).length;
  const indexedToday = jobs.filter((j) => j.stage === 'indexed').length;
  const totalDocs = vector && vector.chunks != null ? vector.chunks.toLocaleString() : '764';

  return (
    <div className="ingest">
      <div className="view-head">
        <div>
          <h1 className="view-title">Ingestion Pipeline</h1>
          <p className="view-sub">Connectors, uploads, and the path every document takes into the vector store</p>
        </div>
        <div className="ingest-stats">
          <div className="ingest-stat"><span className="ingest-stat-n">{active}</span><span className="ingest-stat-l">in flight</span></div>
          <div className="ingest-stat"><span className="ingest-stat-n">{indexedToday}</span><span className="ingest-stat-l">indexed today</span></div>
          <div className="ingest-stat"><span className="ingest-stat-n accent">{totalDocs}</span><span className="ingest-stat-l">total docs</span></div>
        </div>
      </div>

      <div className="ingest-pipeline">
        {PIPELINE_STAGES.map((s, i) => (
          <Fragment key={s.id}>
            <div className="pipe-stage">
              <div className="pipe-stage-top"><span className="pipe-idx">{String(i + 1).padStart(2, '0')}</span><span className="pipe-name">{s.label}</span></div>
              <p className="pipe-desc">{s.desc}</p>
            </div>
            {i < PIPELINE_STAGES.length - 1 && <div className="pipe-arrow"><Icon name="chevron" size={16} /></div>}
          </Fragment>
        ))}
      </div>

      <div className="ingest-cols">
        <div className="ingest-col-main">
          <input ref={fileInputRef} type="file" style={{ display: 'none' }} onChange={onFilesSelected} />
          <div
            className={'drop-zone' + (dragOver ? ' over' : '')}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => { e.preventDefault(); setDragOver(false); const f = e.dataTransfer.files[0]; if (f) uploadMut.mutate(f); }}
          >
            <div className="drop-icon"><Icon name="upload" size={26} /></div>
            <div className="drop-title">Drop files to ingest</div>
            <div className="drop-sub">PDF · DOCX · JSON · CSV · HTML · EML — or pull from a connector</div>
            <div className="drop-actions">
              <button className="btn-primary" onClick={onPickFiles}><Icon name="upload" size={14} /> Select files</button>
              <button className="btn-ghost" onClick={onAddUrl}><Icon name="link" size={14} /> Add from URL</button>
            </div>
          </div>

          <div className="jobs-head">
            <span><Icon name="cpu" size={14} /> Processing queue</span>
            <span className="jobs-count">{jobs.length} jobs</span>
          </div>
          <div className="jobs-list">
            {jobs.map((j) => <JobRow key={j.id} j={j} onRetry={() => retryMut.mutate(j.id)} />)}
          </div>
        </div>

        <div className="ingest-col-side">
          <div className="connectors-head">
            <span><Icon name="link" size={14} /> Source connectors</span>
            <button className="connectors-add"><Icon name="bolt" size={13} /> Add</button>
          </div>
          <div className="connectors-list">
            {connectors.map((c) => <ConnectorRow key={c.id} c={c} />)}
          </div>
          <VectorCard vector={vector} />
        </div>
      </div>
    </div>
  );
}

// lightweight Fragment alias to keep markup 1:1 with the prototype
function Fragment({ children }) { return <>{children}</>; }

function VectorCard({ vector }) {
  const v = vector || {};
  const engine = v.engine || 'pgvector · cosine';
  const model = v.embeddingModel || 'text-embed-3-lg';
  const chunks = v.chunks != null ? v.chunks.toLocaleString() : '41,208';
  const dim = v.dim != null ? v.dim : 3072;
  const healthy = v.healthy !== false;
  const p95 = v.p95Ms != null ? v.p95Ms : 38;
  return (
    <div className="vector-card">
      <div className="vector-card-head"><Icon name="database" size={14} /> Vector store</div>
      <div className="vector-rows">
        <div className="vector-row"><span>Engine</span><code>{engine}</code></div>
        <div className="vector-row"><span>Embedding</span><code>{model}</code></div>
        <div className="vector-row"><span>Chunks</span><code>{chunks}</code></div>
        <div className="vector-row"><span>Dim</span><code>{dim}</code></div>
      </div>
      <div className="vector-health">
        <span className="status-dot" style={{ background: healthy ? 'var(--accent)' : 'var(--sev-critical)' }} />
        {healthy ? 'Healthy' : 'Degraded'} · p95 query {p95}ms
      </div>
    </div>
  );
}

function JobRow({ j, onRetry }) {
  const done = j.stage === 'indexed';
  const failed = j.stage === 'failed';
  const barColor = failed ? 'var(--sev-critical)' : done ? 'var(--accent)' : 'var(--sev-medium)';
  return (
    <div className={'job-row' + (failed ? ' failed' : '')}>
      <div className="job-icon"><Icon name={failed ? 'x' : done ? 'check' : 'cpu'} size={15} /></div>
      <div className="job-main">
        <div className="job-line1">
          <span className="job-name">{j.name}</span>
          <span className="job-size">{j.size}</span>
        </div>
        <div className="job-line2">
          <StatusDot status={j.stage} />
          <span className="job-stage">{STAGE_LABEL[j.stage] || j.stage}</span>
          <span className="job-src">· {j.source}</span>
          {j.chunks > 0 && <span className="job-chunks">· {j.chunks} chunks</span>}
          {failed && <span className="job-error">· {j.error}</span>}
        </div>
        {!done && !failed && (
          <div className="job-bar"><div className="job-bar-fill" style={{ width: j.pct + '%', background: barColor }} /></div>
        )}
      </div>
      <div className="job-when">{done ? <span className="job-ok">indexed</span> : failed ? <button className="job-retry" onClick={onRetry}><Icon name="refresh" size={12} /> retry</button> : Math.round(j.pct) + '%'}</div>
    </div>
  );
}

function ConnectorRow({ c }) {
  return (
    <div className="connector-row">
      <div className="connector-icon"><ConnectorIcon kind={c.kind} /></div>
      <div className="connector-main">
        <div className="connector-name">{c.name}</div>
        <div className="connector-meta">
          <StatusDot status={c.status} />
          <span className={'connector-status s-' + c.status}>{c.status}</span>
          <span className="connector-docs">· {(c.docs || 0).toLocaleString()} docs · {c.last}</span>
        </div>
      </div>
      <span className="connector-kind">{c.kind}</span>
    </div>
  );
}
