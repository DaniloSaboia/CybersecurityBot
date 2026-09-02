// views/Ingest.jsx — Red Noir ingestion pipeline & vector indexing.

import { useRef, useState, Fragment } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Icon } from '../icons.jsx';
import { StatusDot, ConnectorIcon, BlueprintCorners, DimensionDivider, RedlineNote, StampButton } from '../ui.jsx';
import { useJobs, useConnectors, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { uploadFile, addUrl, retryJob, deleteJob, syncConnector } from '../lib/api.js';

const STAGE_LABEL = {
  extracting: 'EXTRACTING', chunking: 'CHUNKING', embedding: 'EMBEDDING',
  indexed: 'INDEXED', failed: 'FAILED', queued: 'QUEUED',
};

// fixed 5-stage pipeline model: ingest -> extract -> chunk -> embed -> index
const PIPELINE_STAGES = [
  { id: 'ingest', label: '01 // INGEST', desc: 'Pull from connectors or accept an upload stream' },
  { id: 'extract', label: '02 // EXTRACT', desc: 'Parse text, tables, IOCs, and metadata schemas' },
  { id: 'chunk', label: '03 // CHUNK', desc: 'Split into overlapping, retrieval-sized tokens' },
  { id: 'embed', label: '04 // EMBED', desc: 'Encode chunks to 1024-dim vectors via mistral-embed' },
  { id: 'index', label: '05 // INDEX', desc: 'Upsert vectors into pgvector cosine space' },
];

export default function Ingest() {
  const [dragOver, setDragOver] = useState(false);
  const [operationNotice, setOperationNotice] = useState(null);
  const fileInputRef = useRef(null);

  const jobsQuery = useJobs();
  const connectorsQuery = useConnectors();
  const vectorQuery = useVectorStoreStatus();
  const jobs = jobsQuery.data || [];
  const connectors = connectorsQuery.data || [];
  const vector = vectorQuery.data;

  function refreshJobs() { queryClient.invalidateQueries({ queryKey: ['jobs'] }); }
  function operationError(label, err) {
    setOperationNotice({ tone: 'error', text: `${label}: ${String((err && err.message) || err || 'unknown error')}` });
  }

  const uploadMut = useMutation({
    mutationFn: uploadFile,
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Uploading specification document…' }),
    onSuccess: () => {
      refreshJobs();
      setOperationNotice({ tone: 'success', text: 'Upload accepted and queued for ingestion.' });
    },
    onError: (err) => operationError('Upload failed', err),
  });
  const urlMut = useMutation({
    mutationFn: addUrl,
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Submitting document URL…' }),
    onSuccess: () => {
      refreshJobs();
      setOperationNotice({ tone: 'success', text: 'URL accepted and queued for ingestion.' });
    },
    onError: (err) => operationError('URL ingestion failed', err),
  });
  const retryMut = useMutation({
    mutationFn: retryJob,
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Retrying failed ingestion job…' }),
    onSuccess: () => {
      refreshJobs();
      setOperationNotice({ tone: 'success', text: 'Ingestion job returned to the execution queue.' });
    },
    onError: (err) => operationError('Job retry failed', err),
  });
  const deleteMut = useMutation({
    mutationFn: deleteJob,
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Removing ingested document and embeddings…' }),
    onSuccess: () => {
      refreshJobs();
      queryClient.invalidateQueries({ queryKey: ['vectorstore'] });
      setOperationNotice({ tone: 'success', text: 'Document and embeddings removed.' });
    },
    onError: (err) => operationError('Delete failed', err),
  });

  const syncMut = useMutation({
    mutationFn: (id) => syncConnector(id),
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Synchronizing source conduit…' }),
    onSuccess: (res) => {
      ['connectors', 'vectorstore', 'articles', 'article-index'].forEach((k) =>
        queryClient.invalidateQueries({ queryKey: [k] }));
      setOperationNotice({
        tone: 'success',
        text: res
          ? `+${res.created} CVEs · ${res.embedded} embedded (${res.fetched} fetched)`
          : 'Source conduit synchronized.',
      });
    },
    onError: (err) => operationError('Connector sync failed', err),
  });

  function onPickFiles() { fileInputRef.current && fileInputRef.current.click(); }
  function onFilesSelected(e) {
    const f = e.target.files && e.target.files[0];
    if (f) uploadMut.mutate(f);
    e.target.value = '';
  }
  function onAddUrl() {
    const url = window.prompt('Document URL to ingest:');
    if (url) urlMut.mutate(url);
  }

  // stats derive from the job list + vector store
  const active = jobsQuery.isSuccess
    ? jobs.filter((j) => !['indexed', 'failed'].includes(j.stage)).length
    : '—';
  const indexedJobs = jobsQuery.isSuccess
    ? jobs.filter((j) => j.stage === 'indexed').length
    : '—';
  const indexedChunks = vectorQuery.isSuccess && vector && vector.chunks != null
    ? vector.chunks.toLocaleString()
    : '—';

  return (
    <div className="ingest">
      <div className="view-head">
        <div>
          <h1 className="view-title">Ingestion Pipeline</h1>
          <p className="view-sub">Connectors, uploads, and data flow into the vector index</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <RedlineNote>// OCR &amp; parser telemetry below</RedlineNote>
          <div className="ingest-stats" aria-label="Ingestion statistics" aria-live="polite">
            <div className="ingest-stat"><span className="ingest-stat-n">{active}</span><span className="ingest-stat-l">IN FLIGHT</span></div>
            <div className="ingest-stat"><span className="ingest-stat-n">{indexedJobs}</span><span className="ingest-stat-l">INDEXED JOBS</span></div>
            <div className="ingest-stat"><span className="ingest-stat-n accent">{indexedChunks}</span><span className="ingest-stat-l">INDEXED CHUNKS</span></div>
          </div>
        </div>
      </div>

      <div className="ingest-pipeline" role="list" aria-label="Five-stage ingestion pipeline">
        {PIPELINE_STAGES.map((s, i) => (
          <Fragment key={s.id}>
            <div className="pipe-stage" role="listitem">
              <div className="pipe-stage-top">
                <span className="pipe-idx">0{i + 1}</span>
                <span className="pipe-name">{s.label}</span>
              </div>
              <p className="pipe-desc">{s.desc}</p>
            </div>
            {i < PIPELINE_STAGES.length - 1 && <div className="pipe-arrow"><Icon name="chevron" size={15} /></div>}
          </Fragment>
        ))}
      </div>

      <DimensionDivider label="INGESTION FEED" dim={`CHUNKS ${indexedChunks}`} />

      {operationNotice && (
        <div
          className={`ingest-notice tone-${operationNotice.tone}`}
          role={operationNotice.tone === 'error' ? 'alert' : 'status'}
          aria-live="polite"
          style={{
            margin: '0 var(--pad) 12px',
            padding: '8px 10px',
            fontFamily: 'var(--mono)',
            fontSize: 10.5,
            color: operationNotice.tone === 'error' ? 'var(--redline-bright)' : 'var(--accent)',
            border: `1px solid ${operationNotice.tone === 'error' ? 'var(--redline-bright)' : 'var(--accent)'}`,
            background: operationNotice.tone === 'error' ? 'var(--redline-bg)' : 'var(--accent-soft)',
          }}
          >
            [ {operationNotice.tone === 'error' ? 'FAULT' : 'STATUS'} ] {operationNotice.text}
          </div>
        )}

      <div className="ingest-cols">
        <div className="ingest-col-main">
          <input
            ref={fileInputRef}
            type="file"
            aria-label="Select one specification document"
            style={{ display: 'none' }}
            onChange={onFilesSelected}
          />
          <div
            className={'drop-zone' + (dragOver ? ' over' : '')}
            aria-busy={uploadMut.isPending || urlMut.isPending}
            onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => { e.preventDefault(); setDragOver(false); const f = e.dataTransfer.files[0]; if (f) uploadMut.mutate(f); }}
          >
            <BlueprintCorners coords="INTAKE // 01" />
            <div className="drop-icon"><Icon name="upload" size={24} /></div>
            <div className="drop-title">Drop a security document</div>
            <div className="drop-sub">PDF · DOCX · JSON · CSV · HTML · EML — or sync via automated connector</div>
            <div className="drop-actions">
              <StampButton
                className="btn-primary"
                action="SELECT"
                context="LOCAL FILE"
                disabled={uploadMut.isPending}
                onClick={onPickFiles}
              >
                <Icon name="upload" size={13} /> {uploadMut.isPending ? 'UPLOADING…' : 'SELECT FILE'}
              </StampButton>
              <StampButton
                className="btn-ghost"
                action="PULL"
                context="URL"
                disabled={urlMut.isPending}
                onClick={onAddUrl}
              >
                <Icon name="link" size={13} /> {urlMut.isPending ? 'SUBMITTING…' : 'PULL FROM URL'}
              </StampButton>
            </div>
          </div>

          <div className="jobs-head">
            <span><Icon name="cpu" size={14} /> JOB QUEUE</span>
            <span className="jobs-count">
              {jobsQuery.isPending ? 'LOADING…' : jobsQuery.isError ? 'UNAVAILABLE' : `${active} ACTIVE · ${jobs.length} TOTAL`}
            </span>
          </div>
          <div className="jobs-list" aria-live="polite" aria-busy={jobsQuery.isPending}>
            {jobsQuery.isPending && <div className="ingest-state" role="status">Loading job queue…</div>}
            {jobsQuery.isError && (
              <div className="ingest-state" role="alert">
                Job queue unavailable — {String(jobsQuery.error?.message || jobsQuery.error)}
                <StampButton tone="redline" text="RETRY" onClick={() => jobsQuery.refetch()} />
              </div>
            )}
            {jobsQuery.isSuccess && jobs.length === 0 && <div className="ingest-state">No ingestion jobs</div>}
            {jobsQuery.isSuccess && jobs.map((j, index) => (
              <JobRow
                key={j.id}
                j={j}
                index={index}
                onRetry={() => retryMut.mutate(j.id)}
                onDelete={() => { if (window.confirm(`Delete "${j.name}" and remove its embeddings?`)) deleteMut.mutate(j.id); }}
                retrying={retryMut.isPending && retryMut.variables === j.id}
                deleting={deleteMut.isPending && deleteMut.variables === j.id}
              />
            ))}
          </div>
        </div>

        <div className="ingest-col-side">
          <div className="connectors-head">
            <span><Icon name="link" size={14} /> SOURCE CONNECTORS</span>
          </div>
          <div className="connectors-list" aria-live="polite" aria-busy={connectorsQuery.isPending}>
            {connectorsQuery.isPending && <div className="ingest-state" role="status">Loading connectors…</div>}
            {connectorsQuery.isError && (
              <div className="ingest-state" role="alert">
                Connectors unavailable — {String(connectorsQuery.error?.message || connectorsQuery.error)}
                <StampButton tone="redline" text="RETRY" onClick={() => connectorsQuery.refetch()} />
              </div>
            )}
            {connectorsQuery.isSuccess && connectors.length === 0 && <div className="ingest-state">No connectors configured</div>}
            {connectorsQuery.isSuccess && connectors.map((c, index) => (
              <ConnectorRow
                key={c.id}
                c={c}
                index={index}
                onSync={c.id === 'nvd' ? () => syncMut.mutate(c.id) : null}
                syncing={syncMut.isPending && syncMut.variables === c.id}
              />
            ))}
          </div>
          <VectorCard
            vector={vector}
            loading={vectorQuery.isPending}
            error={vectorQuery.error}
            onRetry={() => vectorQuery.refetch()}
          />
        </div>
      </div>
    </div>
  );
}

function VectorCard({ vector, loading, error, onRetry }) {
  const v = vector || {};
  const engine = v.engine || '—';
  const model = v.embeddingModel || '—';
  const chunks = v.chunks != null ? v.chunks.toLocaleString() : '—';
  const dim = v.dim != null ? v.dim : '—';
  const p95 = v.p95Ms != null ? `${v.p95Ms}ms` : '—';
  const healthStatus = v.healthy === true ? 'NOMINAL' : v.healthy === false ? 'DEGRADED' : 'UNKNOWN';
  return (
    <div className="vector-card" aria-busy={loading}>
      <div className="vector-card-head"><Icon name="database" size={14} /> Vector Store</div>
      {loading && <div className="ingest-state" role="status">Loading vector telemetry…</div>}
      {error && (
        <div className="ingest-state" role="alert">
          Vector telemetry unavailable — {String(error.message || error)}
          <StampButton tone="redline" text="RETRY" onClick={onRetry} />
        </div>
      )}
      {!loading && !error && (
        <>
      <div className="vector-rows">
        <div className="vector-row"><span>Engine</span><code>{engine}</code></div>
        <div className="vector-row"><span>Embedding</span><code>{model}</code></div>
        <div className="vector-row"><span>Indexed Chunks</span><code>{chunks}</code></div>
        <div className="vector-row"><span>Dimension</span><code>{dim}</code></div>
      </div>
      <div className="vector-health">
        <StatusDot status={healthStatus === 'NOMINAL' ? 'connected' : healthStatus === 'DEGRADED' ? 'error' : 'queued'} />
        STATUS {healthStatus} · P95 {p95}
      </div>
        </>
      )}
    </div>
  );
}

function JobRow({ j, index, onRetry, onDelete, retrying, deleting }) {
  const done = j.stage === 'indexed';
  const failed = j.stage === 'failed';
  const barColor = failed ? 'var(--sev-critical)' : done ? 'var(--accent)' : 'var(--sev-medium)';
  const rawPct = Number(j.pct);
  const pct = Number.isFinite(rawPct) ? Math.min(100, Math.max(0, rawPct)) : 0;
    return (
      <div className={'job-row' + (failed ? ' failed' : '')} aria-label={`${j.name}: ${STAGE_LABEL[j.stage] || j.stage || 'unknown'}`}>
        <div className="job-icon"><Icon name={failed ? 'x' : done ? 'check' : 'cpu'} size={14} /></div>
        <div className="job-main">
          <div className="job-line1">
            <span className="job-name">{j.name}</span>
            <span className="job-size">{j.size}</span>
          </div>
          <div className="job-line2">
            <StatusDot status={j.stage} />
            <span className="job-stage">{STAGE_LABEL[j.stage] || j.stage}</span>
            <span className="job-src">· {j.source}</span>
            {j.chunks > 0 && <span className="job-chunks">· {j.chunks} CHUNKS</span>}
            {failed && <span className="job-error">· {j.error}</span>}
          </div>
        {!done && !failed && (
          <div className="job-bar" role="progressbar" aria-label={`Ingestion progress for ${j.name}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
            <div className="job-bar-fill" style={{ width: pct + '%', background: barColor }} />
          </div>
        )}
      </div>
      <div className="job-when">
        {done ? (
          <span className="job-ok">OK</span>
        ) : failed ? (
          <StampButton className="job-retry" tone="redline" text={retrying ? 'RETRYING…' : 'RETRY'} disabled={retrying} onClick={onRetry} />
        ) : (
          Math.round(pct) + '%'
        )}
      </div>
      {onDelete && (
        <button type="button" className="job-delete" aria-label={`Delete ${j.name} and its embeddings`} title="Delete ingested file & its embeddings" disabled={deleting} onClick={onDelete}>
          <Icon name={deleting ? 'refresh' : 'trash'} size={13} />
        </button>
      )}
    </div>
  );
}

function ConnectorRow({ c, index, onSync, syncing }) {
  return (
    <div className="connector-row">
      <div className="connector-icon"><ConnectorIcon kind={c.kind} /></div>
      <div className="connector-main">
        <div className="connector-name">{c.name}</div>
        <div className="connector-meta">
          <StatusDot status={syncing ? 'syncing' : c.status} />
          <span className={'connector-status s-' + (syncing ? 'syncing' : c.status)}>
            {syncing ? 'SYNCING' : c.status?.toUpperCase() || 'UNKNOWN'}
          </span>
          <span className="connector-docs">· {(c.docs || 0).toLocaleString()} DOCS · {c.last}</span>
        </div>
      </div>
      {onSync && (
        <StampButton className="connectors-add" action="SYNC" context="NVD" style={{ margin: 0 }} title="Pull recent CVEs from NVD" disabled={syncing} onClick={onSync}>
          <Icon name="refresh" size={11} /> {syncing ? 'SYNCING…' : 'SYNC NVD'}
        </StampButton>
      )}
      <span className="connector-kind">{c.kind}</span>
    </div>
  );
}
