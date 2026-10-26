// views/SettingsModal.jsx — Workspace settings and AI model configuration.

import { useState, useEffect } from 'react';
import { Icon } from '../icons.jsx';
import { StampButton } from '../ui.jsx';
import { useSettings, useVectorStoreStatus, useCategories, useDemoContent, queryClient } from '../lib/queries.js';
import { retireDemoContent } from '../lib/api.js';
import './access.css';

export const DEFAULT_MODELS = [
  {
    id: 'openrouter/free',
    name: 'Free Models Router',
    provider: 'OpenRouter',
    description: 'Automatically selects a currently available free model for each request.',
    contextWindow: 200000,
    isFree: true,
    tag: 'RECOMMENDED',
  },
];

const DEFAULT_RAG_PARAMS = { topK: 6, minScore: 0.3, historyTurns: 4, category: '', minSeverity: '' };

function settingKey(name, userId) {
  return userId ? `${name}:${encodeURIComponent(userId)}` : name;
}

export function getStoredModel(userId) {
  return localStorage.getItem(settingKey('sentinel_ai_model', userId)) || 'openrouter/free';
}

export function getStoredRagParams(userId) {
  try {
    const raw = localStorage.getItem(settingKey('sentinel_rag_params', userId));
    return raw ? JSON.parse(raw) : DEFAULT_RAG_PARAMS;
  } catch {
    return DEFAULT_RAG_PARAMS;
  }
}

export default function SettingsModal({ isOpen, onClose, onSave, userId }) {
  const settingsQuery = useSettings();
  const vectorQuery = useVectorStoreStatus();
  const categoriesQuery = useCategories();
  const demoContentQuery = useDemoContent(userId, isOpen);
  const serverModels = (settingsQuery.data && settingsQuery.data.availableModels) || DEFAULT_MODELS;
  const embeddingModels = settingsQuery.data?.availableEmbeddingModels || [];
  const categories = categoriesQuery.data || [];

  const [activeTab, setActiveTab] = useState('models');
  const [selectedModel, setSelectedModel] = useState(() => getStoredModel(userId));
  const [ragParams, setRagParams] = useState(() => getStoredRagParams(userId));
  const [savedNotice, setSavedNotice] = useState(false);

  useEffect(() => {
    if (isOpen) {
      const stored = getStoredModel(userId);
      setSelectedModel(serverModels.some((m) => m.id === stored) ? stored : serverModels[0]?.id || 'openrouter/free');
      setRagParams(getStoredRagParams(userId));
      setSavedNotice(false);
    }
  }, [isOpen, serverModels, userId]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const closeOnEscape = (event) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  function handleSave() {
    const modelToSave = selectedModel;
    localStorage.setItem(settingKey('sentinel_ai_model', userId), modelToSave);
    localStorage.setItem(settingKey('sentinel_rag_params', userId), JSON.stringify(ragParams));

    setSavedNotice(true);
    if (onSave) {
      onSave({ model: modelToSave, ragParams });
    }
    setTimeout(() => {
      setSavedNotice(false);
      onClose();
    }, 600);
  }

  function handleReset() {
    setSelectedModel('openrouter/free');
    setRagParams({ topK: 6, minScore: 0.3, historyTurns: 4, category: '', minSeverity: '' });
  }

  const supabaseUrl = import.meta.env.VITE_SUPABASE_URL || 'http://localhost:54321';
  const isHostedSupabase = supabaseUrl.includes('.supabase.co');

  return (
    <div className="settings-backdrop access-settings-backdrop" onClick={onClose}>
      <div
        className="settings-modal access-settings"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Modal Header */}
        <div className="settings-head">
          <div className="settings-title-wrap">
            <div>
              <h2 id="settings-title" className="settings-title">System Settings</h2>
              <p className="settings-sub">Models, retrieval preferences, and connection details.</p>
            </div>
          </div>
          <button type="button" className="settings-close-btn" onClick={onClose} aria-label="Close settings">
            <Icon name="x" size={16} />
          </button>
        </div>

        {/* Navigation Tabs */}
        <div className="settings-tabs">
          <button
            className={'settings-tab' + (activeTab === 'models' ? ' active' : '')}
            onClick={() => setActiveTab('models')}
          >
            <span>Models</span>
            {activeTab === 'models' && <span className="tab-indicator" />}
          </button>
          <button
            className={'settings-tab' + (activeTab === 'rag' ? ' active' : '')}
            onClick={() => setActiveTab('rag')}
          >
            <span>Retrieval</span>
            {activeTab === 'rag' && <span className="tab-indicator" />}
          </button>
          <button
            className={'settings-tab' + (activeTab === 'supabase' ? ' active' : '')}
            onClick={() => setActiveTab('supabase')}
          >
            <span>Connection</span>
            {activeTab === 'supabase' && <span className="tab-indicator" />}
          </button>
        </div>

        {/* Tab 1: AI Models */}
        {activeTab === 'models' && (
          <div className="settings-body">
            <h3 className="settings-section-title">Answer model</h3>

            <div className="models-grid">
              {serverModels.map((m) => {
                const isSelected = selectedModel === m.id;
                return (
                  <button
                    type="button"
                    key={m.id}
                    className={'model-card' + (isSelected ? ' selected' : '')}
                    aria-pressed={isSelected}
                    onClick={() => {
                      setSelectedModel(m.id);
                    }}
                  >
                    <div className="model-card-top">
                      <div className="model-name-wrap">
                        <span className="model-radio" aria-hidden="true"><Icon name={isSelected ? 'check' : 'chat'} size={16} /></span>
                        <span className="model-name">{m.name}</span>
                      </div>

                    </div>
                    <p className="model-desc">{m.description}</p>
                    <div className="model-meta">
                      <span className="model-provider">{m.provider}</span>
                      <span className="model-context">{(m.contextWindow / 1024).toFixed(0)}k context</span>
                    </div>
                  </button>
                );
              })}
            </div>

            <h3 className="settings-section-title">Embedding index</h3>
            <p className="form-hint">
              Active embedding model: <strong>{settingsQuery.data?.defaultEmbedModel || (settingsQuery.isPending ? 'Loading…' : 'Not reported')}</strong>.
              {' '}Ingestion and search use the same model. Switching requires reindexing existing content.
            </p>
            <div className="models-grid" aria-label="Free embedding models">
              {embeddingModels.map((m) => (
                <div key={m.id} className={'model-card' + (m.id === settingsQuery.data?.defaultEmbedModel ? ' selected' : '')}>
                  <div className="model-card-top">
                    <span className="model-name">{m.name}</span>
                    <span className="model-tag">{m.id === settingsQuery.data?.defaultEmbedModel ? 'ACTIVE' : 'FREE'}</span>
                  </div>
                  <p className="model-desc">{m.description}</p>
                  <div className="model-meta">
                    <span className="model-provider">{m.provider}</span>
                    <span className="model-context">{m.contextWindow.toLocaleString()} token context</span>
                  </div>
                </div>
              ))}
            </div>
            {!embeddingModels.length && (
              <p className="form-hint">{settingsQuery.isLoading ? 'Loading embedding catalog…' : 'No free embedding models are available in the current catalog.'}</p>
            )}
            <p className="form-hint">
              Listed models may have different vector dimensions and input limits. The active model is configured for this index on the server.
            </p>

            <h3 className="settings-section-title">API connection</h3>

            <div className="custom-model-form">
              <div className="form-group">
                <span className="form-label">OpenRouter API key</span>
                <div className="status-v code">
                  {settingsQuery.isPending ? 'Checking…' : settingsQuery.isError ? 'Status unavailable' : settingsQuery.data?.openRouterApiKeyConfigured ? 'Configured on server' : 'Not configured'}
                </div>
                <span className="form-hint">
                  Set <code>OPENROUTER_API_KEY</code> in the backend environment. Secrets are never stored in browser storage.
                </span>
              </div>
            </div>
          </div>
        )}

        {/* Tab 2: RAG Tuning */}
        {activeTab === 'rag' && (
          <div className="settings-body">
            {demoContentQuery.isSuccess && (
              <DemoContentMaintenance key={userId} preview={demoContentQuery.data} />
            )}
            {demoContentQuery.isError && demoContentQuery.error?.status !== 403 && demoContentQuery.error?.status !== 401 && (
              <div className="demo-maintenance" role="alert">
                <p>Knowledge maintenance is unavailable — {String(demoContentQuery.error?.message || 'Unable to load the preview.')}</p>
                <button type="button" className="settings-cancel-btn" onClick={() => demoContentQuery.refetch()}>Retry maintenance preview</button>
              </div>
            )}
            <h3 className="settings-section-title">Evidence used in answers</h3>

            <div className="rag-tuning-grid">
              <div className="tuning-card">
                <div className="tuning-head">
                  <label htmlFor="retrieval-count" className="tuning-label">Retrieved passages</label>
                  <span className="tuning-value accent">{ragParams.topK} chunks</span>
                </div>
                <input
                  id="retrieval-count"
                  type="range"
                  min="2"
                  max="15"
                  step="1"
                  className="tuning-slider"
                  value={ragParams.topK}
                  onChange={(e) => setRagParams({ ...ragParams, topK: Number(e.target.value) })}
                />
                <p className="tuning-help">How many relevant passages the assistant can use as evidence for an answer.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <label htmlFor="retrieval-score" className="tuning-label">Minimum similarity</label>
                  <span className="tuning-value accent">{(ragParams.minScore * 100).toFixed(0)}%</span>
                </div>
                <input
                  id="retrieval-score"
                  type="range"
                  min="0.10"
                  max="0.80"
                  step="0.05"
                  className="tuning-slider"
                  value={ragParams.minScore}
                  onChange={(e) => setRagParams({ ...ragParams, minScore: Number(e.target.value) })}
                />
                <p className="tuning-help">Exclude passages below this relevance threshold. A higher value narrows the evidence available.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <label htmlFor="retrieval-history" className="tuning-label">Conversation history</label>
                  <span className="tuning-value accent">{ragParams.historyTurns} turns</span>
                </div>
                <input
                  id="retrieval-history"
                  type="range"
                  min="1"
                  max="8"
                  step="1"
                  className="tuning-slider"
                  value={ragParams.historyTurns}
                  onChange={(e) => setRagParams({ ...ragParams, historyTurns: Number(e.target.value) })}
                />
                <p className="tuning-help">How many recent conversation turns the assistant uses to follow your investigation.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">Category</span>
                  <span className="tuning-value accent">{ragParams.category || 'ALL'}</span>
                </div>
                <select
                  className="tuning-select"
                  value={ragParams.category || ''}
                  onChange={(e) => setRagParams({ ...ragParams, category: e.target.value || '' })}
                  aria-label="Retrieval category scope"
                >
                  <option value="">All categories</option>
                  {categories.map((c) => (
                    <option key={c.id} value={c.id}>{c.label}</option>
                  ))}
                </select>
                <p className="tuning-help">Use evidence from one knowledge base category, or include all categories.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">Minimum severity</span>
                  <span className="tuning-value accent">{ragParams.minSeverity || 'ANY'}</span>
                </div>
                <select
                  className="tuning-select"
                  value={ragParams.minSeverity || ''}
                  onChange={(e) => setRagParams({ ...ragParams, minSeverity: e.target.value || '' })}
                  aria-label="Minimum severity scope"
                >
                  <option value="">Any severity</option>
                  <option value="critical">≥ critical</option>
                  <option value="high">≥ high</option>
                  <option value="medium">≥ medium</option>
                  <option value="low">≥ low</option>
                </select>
                <p className="tuning-help">Only ground in articles at or above this severity (critical &gt; high &gt; medium &gt; low &gt; info).</p>
              </div>
            </div>
          </div>
        )}

        {/* Tab 3: Supabase Environment */}
        {activeTab === 'supabase' && (
          <div className="settings-body">
            <h3 className="settings-section-title">Supabase connection</h3>

            <div className="supabase-status-card">
              <div className="status-row">
                <span className="status-k">ENVIRONMENT:</span>
                <span className={'status-v ' + (isHostedSupabase ? 'hosted' : 'local')}>
                  <Icon name="dot" size={9} className="status-dot" /> {isHostedSupabase ? 'HOSTED CLOUD' : 'LOCAL INSTANCE'}
                </span>
              </div>
              <div className="status-row">
                <span className="status-k">SUPABASE URL:</span>
                <span className="status-v code">{supabaseUrl}</span>
              </div>
              <div className="status-row">
                <span className="status-k">AUTH PROTOCOL:</span>
                <span className="status-v">GoTrue JWKS validation &middot; aud:authenticated</span>
              </div>
              <div className="status-row">
                <span className="status-k">VECTOR ENGINE:</span>
                <span className="status-v">
                  {vectorQuery.data?.engine || 'pgvector · cosine'} ({vectorQuery.data?.dim || 1024}-dim)
                </span>
              </div>
              <div className="status-row">
                <span className="status-k">DATABASE CHECK:</span>
                <span className={'status-v ' + (vectorQuery.data?.healthy ? 'hosted' : '')}>
                  {vectorQuery.isLoading ? 'CHECKING…' : <><Icon name="dot" size={9} className="status-dot" /> {vectorQuery.data?.healthy ? 'CONNECTED' : 'UNAVAILABLE'}</>}
                </span>
              </div>
            </div>

            <div className="supabase-guide-box">
              <div className="guide-box-head">
                <Icon name="link" size={14} />
                <span>Need to connect a new hosted Supabase Cloud project?</span>
              </div>
              <p className="guide-box-text">
                Run the migration in <code>supabase/migrations/20260829000000_init_schema.sql</code> in your Supabase SQL Editor,
                then configure <code>.env</code> with your project URL, database URL, and anon key.
              </p>
              <div style={{ marginTop: 10 }}>
                <a
                  href="https://github.com/DaniloSaboia/CybersecurityBot/blob/main/docs/supabase-hosted-setup.md"
                  target="_blank"
                  rel="noreferrer"
                  className="guide-link"
                >
                  OPEN HOSTED SUPABASE SETUP GUIDE <Icon name="chevron" size={11} />
                </a>
              </div>
            </div>
          </div>
        )}

        {/* Modal Footer */}
        <div className="settings-foot">
          <div className="settings-foot-left">
            <button className="settings-reset-btn" onClick={handleReset}>
              Reset defaults
            </button>
            {savedNotice && <span className="settings-saved-badge"><Icon name="check" size={12} /> SETTINGS SAVED</span>}
          </div>
          <div className="settings-foot-right">
            <button className="settings-cancel-btn" onClick={onClose}>
              Cancel
            </button>
            <StampButton
              className="btn-primary"
              action="APPLY"
              context="SETTINGS"
              onClick={handleSave}
            >
              Save changes
            </StampButton>
          </div>
        </div>
      </div>
    </div>
  );
}

function DemoContentMaintenance({ preview }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const eligible = preview?.eligibleIds?.length || 0;
  const preserved = preview?.preservedIds?.length || 0;

  async function retire() {
    if (busy || !eligible) return;
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const next = await retireDemoContent();
      setResult(next);
      await Promise.all(['demo-content', 'articles', 'article', 'article-index', 'categories', 'vectorstore'].map((key) =>
        queryClient.invalidateQueries({ queryKey: [key] })));
    } catch (failure) {
      setError(String(failure?.message || 'Unable to retire demo content. Try again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="demo-maintenance" aria-labelledby="demo-maintenance-title" aria-busy={busy}>
      <h3 id="demo-maintenance-title" className="settings-section-title">Retire sample knowledge</h3>
      <p className="form-hint">Remove unchanged demo articles from active knowledge and assistant retrieval. Article history and your edits are preserved.</p>
      <p className="form-hint">{eligible} unchanged sample {eligible === 1 ? 'article' : 'articles'} eligible · {preserved} edited or protected {preserved === 1 ? 'article' : 'articles'} preserved</p>
      <button type="button" className="settings-cancel-btn" disabled={busy || !eligible} onClick={retire}>{busy ? 'Retiring demo content…' : 'Retire demo content'}</button>
      {result && <p className="demo-maintenance-result" role="status">Retired {result.retiredIds.length} sample {result.retiredIds.length === 1 ? 'article' : 'articles'}. Preserved {result.preservedIds.length} edited or protected {result.preservedIds.length === 1 ? 'article' : 'articles'}. History remains available.</p>}
      {error && <p className="demo-maintenance-error" role="alert">{error}</p>}
    </section>
  );
}
