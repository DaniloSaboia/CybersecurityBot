// views/Ingest.jsx — document intake, processing, and source refreshes.

import { useEffect, useRef, useState, Fragment } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Icon } from '../icons.jsx';
import './workflows.css';
import { StatusDot, ConnectorIcon, StampButton } from '../ui.jsx';
import { useJobs, useConnectors, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { uploadFile, addUrl, retryJob, deleteJob, syncConnector } from '../lib/api.js';

const STAGE_LABEL = {
  extracting: 'EXTRACTING', chunking: 'CHUNKING', embedding: 'EMBEDDING',
  indexed: 'INDEXED', failed: 'FAILED', queued: 'QUEUED',
};

// fixed 5-stage pipeline model: ingest -> extract -> chunk -> embed -> index
const PIPELINE_STAGES = [
  { id: 'ingest', label: 'Receive', desc: 'Upload a document or refresh a source.' },
  { id: 'extract', label: 'Extract', desc: 'Read text, tables, and indicators.' },
  { id: 'chunk', label: 'Organize', desc: 'Prepare passages for retrieval.' },
  { id: 'embed', label: 'Embed', desc: 'Prepare content for semantic search.' },
  { id: 'index', label: 'Index', desc: 'Make content available to research.' },
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
  const connectorVersion = JSON.stringify(connectors.map((c) => [c.id, c.sync?.runId, c.sync?.updated, c.sync?.created, c.sync?.index?.searchable]));
  useEffect(() => {
    ['vectorstore', 'articles', 'article-index'].forEach((key) => queryClient.invalidateQueries({ queryKey: [key] }));
  }, [connectorVersion]);

  const vector = vectorQuery.data;

  function refreshJobs() { queryClient.invalidateQueries({ queryKey: ['jobs'] }); }
  function operationError(label, err) {
    setOperationNotice({ tone: 'error', text: `${label}: ${String((err && err.message) || err || 'unknown error')}` });
  }

  const uploadMut = useMutation({
    mutationFn: uploadFile,
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Uploading document…' }),
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
    onMutate: () => setOperationNotice({ tone: 'info', text: 'Queuing source refresh…' }),
    onSuccess: (res) => {
      ['connectors', 'vectorstore', 'articles', 'article-index'].forEach((k) =>
        queryClient.invalidateQueries({ queryKey: [k] }));
      setOperationNotice({
        tone: 'success',
        text: res
          ? (res.runId ? 'Refresh queued. Source results and indexing progress will update below.'
            : `${res.created} new records queued for indexing (${res.fetched} fetched).`)
          : 'Refresh queued.',
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
    <div className="ingest workflow-view">
      <div className="view-head">
        <div>
          <h1 className="view-title">Ingestion Pipeline</h1>
          <p className="view-sub">Add source material and follow its progress into your knowledge base</p>
        </div>
        <div className="workflow-head-summary">
          <div className="ingest-stats" aria-label="Ingestion statistics" aria-live="polite">
            <div className="ingest-stat"><span className="ingest-stat-n">{active}</span><span className="ingest-stat-l">In progress</span></div>
            <div className="ingest-stat"><span className="ingest-stat-n">{indexedJobs}</span><span className="ingest-stat-l">Indexed jobs</span></div>
            <div className="ingest-stat"><span className="ingest-stat-n accent">{indexedChunks}</span><span className="ingest-stat-l">Indexed chunks</span></div>
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

      {operationNotice && (
        <div
          className={`ingest-notice tone-${operationNotice.tone}`}
          role={operationNotice.tone === 'error' ? 'alert' : 'status'}
          aria-live="polite"
        >
          {operationNotice.text}
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

            <div className="drop-icon"><Icon name="upload" size={24} /></div>
            <div className="drop-title">Drop a security document</div>
            <div className="drop-sub">PDF, DOCX, JSON, CSV, HTML or EML. Select one file to begin.</div>
            <div className="drop-actions">
              <StampButton
                className="btn-primary"
                aria-label={uploadMut.isPending ? 'UPLOADING…' : 'SELECT FILE'}
                action="SELECT"
                context="LOCAL FILE"
                disabled={uploadMut.isPending}
                onClick={onPickFiles}
              >
                <Icon name="upload" size={13} /> {uploadMut.isPending ? 'Uploading…' : 'Select file'}
              </StampButton>
              <StampButton
                className="btn-ghost"
                aria-label={urlMut.isPending ? 'SUBMITTING…' : 'PULL FROM URL'}
                action="PULL"
                context="URL"
                disabled={urlMut.isPending}
                onClick={onAddUrl}
              >
                <Icon name="link" size={13} /> {urlMut.isPending ? 'Submitting…' : 'Pull from URL'}
              </StampButton>
            </div>
          </div>

          <div className="jobs-head">
            <span><Icon name="cpu" size={14} /> Job queue</span>
            <span className="jobs-count">
              {jobsQuery.isPending ? 'Loading…' : jobsQuery.isError ? 'Unavailable' : `${active} active · ${jobs.length} total`}
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
            {jobsQuery.isSuccess && jobs.length === 0 && <div className="ingest-state">No ingestion jobs yet. Add a document above to get started.</div>}
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
            <span><Icon name="link" size={14} /> Source connectors</span>
          </div>
          <div className="connectors-list" aria-live="polite" aria-busy={connectorsQuery.isPending}>
            {connectorsQuery.isPending && <div className="ingest-state" role="status">Loading connectors…</div>}
            {connectorsQuery.isError && (
              <div className="ingest-state" role="alert">
                Connectors unavailable — {String(connectorsQuery.error?.message || connectorsQuery.error)}
                <StampButton tone="redline" text="RETRY" onClick={() => connectorsQuery.refetch()} />
              </div>
            )}
            {connectorsQuery.isSuccess && connectors.length === 0 && <div className="ingest-state">No connectors configured. You can still upload documents.</div>}
            {connectorsQuery.isSuccess && connectors.map((c, index) => (
              <ConnectorRow
                key={c.id}
                c={c}
                index={index}
                onSync={['nvd', 'kev', 'sans', 'attack'].includes(c.id) ? () => syncMut.mutate(c.id) : null}
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
  const healthStatus = v.healthy === true ? 'Healthy' : v.healthy === false ? 'Degraded' : 'Unknown';
  return (
    <div className="vector-card" aria-busy={loading}>
      <div className="vector-card-head"><Icon name="database" size={14} /> Search index</div>
      {loading && <div className="ingest-state" role="status">Loading search index status…</div>}
      {error && (
        <div className="ingest-state" role="alert">
          Search index status unavailable — {String(error.message || error)}
          <StampButton tone="redline" text="RETRY" onClick={onRetry} />
        </div>
      )}
      {!loading && !error && (
        <>
      <div className="vector-rows">
        <div className="vector-row"><span>Engine</span><code>{engine}</code></div>
        <div className="vector-row"><span>Embedding</span><code>{model}</code></div>
        <div className="vector-row"><span>Indexed chunks</span><code>{chunks}</code></div>
        <div className="vector-row"><span>Dimension</span><code>{dim}</code></div>
      </div>
      <div className="vector-health">
        <StatusDot status={healthStatus === 'Healthy' ? 'connected' : healthStatus === 'Degraded' ? 'error' : 'queued'} />
        {healthStatus} · P95 {p95}
      </div>
        </>
      )}
    </div>
  );
}

function JobRow({ j, index, onRetry, onDelete, retrying, deleting }) {
  const done = j.stage === 'indexed';
  const failed = j.stage === 'failed';
  const barColor = 'var(--accent)';
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
            <span className="job-stage">{(STAGE_LABEL[j.stage] || j.stage || '').toLowerCase()}</span>
            <span className="job-src">· {j.source}</span>
            {j.chunks > 0 && <span className="job-chunks">· {j.chunks} chunks</span>}
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

export function ConnectorRow({ c, index, onSync, syncing }) {
  const run = c.sync || {};
  const recordCount = run.index?.total ?? c.docs ?? 0;
  const busy = syncing || ['queued', 'running'].includes(run.status);
  const at = (value) => value ? new Date(value).toLocaleString() : 'Never';
  return (
    <div className="connector-row">
      <div className="connector-icon"><ConnectorIcon kind={c.kind} /></div>
      <div className="connector-main">
        <div className="connector-name">{c.name}</div>
        <div className="connector-meta">
          <StatusDot status={busy ? 'syncing' : c.status} />
          <span className={'connector-status s-' + (busy ? 'syncing' : c.status)}>
            {busy ? 'SYNCING' : c.status?.toUpperCase() || 'UNKNOWN'}
          </span>
          <span className="connector-docs">· {recordCount.toLocaleString()} {recordCount === 1 ? 'record' : 'records'}</span>
        </div>
      </div>
      {!!Object.keys(run).length && (
          <div className="connector-sync-details">
            <p>Last complete check: {at(run.lastSuccessAt)} · {run.dailyEnabled ? 'Daily refresh on' : 'Manual refresh only'}</p>
            {run.nextRefreshAt && <p>Next daily refresh: {at(run.nextRefreshAt)}</p>}
            {run.runId && <p>{run.created || 0} new · {run.updated || 0} updated · {run.unchanged || 0} unchanged · {run.checked || 0} checked</p>}
            {run.remaining > 0 && <p>{run.remaining} source records still to check. Refresh again to continue within the {run.maxChangesPerRun}-change limit.</p>}
            {run.index && <p>{run.index.searchable} searchable · {run.index.pending} awaiting indexing · {run.index.failed} indexing failures</p>}
            {run.error && <p role="alert">{run.error}</p>}
            {run.sourceUrl && <a href={run.sourceUrl} target="_blank" rel="noreferrer">Open source feed</a>}
            {run.history?.length > 0 && <details>
              <summary>Recent refreshes</summary>
              <ul>{run.history.map((entry) => <li key={entry.runId}>
                {at(entry.startedAt)} — {entry.status}: {entry.created || 0} new, {entry.updated || 0} updated, {entry.unchanged || 0} unchanged
              </li>)}</ul>
            </details>}
          </div>
        )}
      {onSync && (
        <StampButton className="connectors-add" action="REFRESH" context={c.id.toUpperCase()} style={{ margin: 0 }} title={'Refresh ' + c.name} disabled={busy} onClick={onSync}>
          <Icon name="refresh" size={11} /> {busy ? 'Refreshing…' : 'Refresh now'}
        </StampButton>
      )}
      <span className="connector-kind">{c.kind}</span>
    </div>
  );
}
