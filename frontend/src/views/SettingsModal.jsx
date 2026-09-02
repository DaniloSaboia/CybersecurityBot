// views/SettingsModal.jsx — Red Noir console settings & AI model configuration.

import { useState, useEffect } from 'react';
import { Icon } from '../icons.jsx';
import { DimensionDivider, RedlineNote, StampButton } from '../ui.jsx';
import { useSettings, useVectorStoreStatus, useCategories } from '../lib/queries.js';

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

export function getStoredModel() {
  return localStorage.getItem('sentinel_ai_model') || 'openrouter/free';
}

export function getStoredRagParams() {
  try {
    const raw = localStorage.getItem('sentinel_rag_params');
    return raw ? JSON.parse(raw) : { topK: 6, minScore: 0.3, historyTurns: 4, category: '', minSeverity: '' };
  } catch {
    return { topK: 6, minScore: 0.3, historyTurns: 4, category: '', minSeverity: '' };
  }
}

export default function SettingsModal({ isOpen, onClose, onSave }) {
  const settingsQuery = useSettings();
  const vectorQuery = useVectorStoreStatus();
  const categoriesQuery = useCategories();
  const serverModels = (settingsQuery.data && settingsQuery.data.availableModels) || DEFAULT_MODELS;
  const categories = categoriesQuery.data || [];

  const [activeTab, setActiveTab] = useState('models');
  const [selectedModel, setSelectedModel] = useState(getStoredModel);
  const [ragParams, setRagParams] = useState(getStoredRagParams);
  const [savedNotice, setSavedNotice] = useState(false);

  useEffect(() => {
    if (isOpen) {
      const stored = getStoredModel();
      setSelectedModel(serverModels.some((m) => m.id === stored) ? stored : serverModels[0]?.id || 'openrouter/free');
      setRagParams(getStoredRagParams());
      setSavedNotice(false);
    }
  }, [isOpen, serverModels]);

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
    localStorage.setItem('sentinel_ai_model', modelToSave);
    localStorage.setItem('sentinel_rag_params', JSON.stringify(ragParams));

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
    <div className="settings-backdrop" onClick={onClose}>
      <div
        className="settings-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Modal Header */}
        <div className="settings-head">
          <div className="settings-title-wrap">
            <div className="settings-icon-badge">
              <Icon name="settings" size={18} />
            </div>
            <div>
              <h2 id="settings-title" className="settings-title">System Settings</h2>
              <p className="settings-sub">SecOps Engine Configuration · OpenRouter Free Models · Supabase Cloud</p>
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
            <Icon name="spark" size={14} />
            <span>AI Models &amp; OpenRouter</span>
            {activeTab === 'models' && <span className="tab-indicator" />}
          </button>
          <button
            className={'settings-tab' + (activeTab === 'rag' ? ' active' : '')}
            onClick={() => setActiveTab('rag')}
          >
            <Icon name="sliders" size={14} />
            <span>RAG Tuning</span>
            {activeTab === 'rag' && <span className="tab-indicator" />}
          </button>
          <button
            className={'settings-tab' + (activeTab === 'supabase' ? ' active' : '')}
            onClick={() => setActiveTab('supabase')}
          >
            <Icon name="database" size={14} />
            <span>Supabase Env</span>
            {activeTab === 'supabase' && <span className="tab-indicator" />}
          </button>
        </div>

        {/* Tab 1: AI Models */}
        {activeTab === 'models' && (
          <div className="settings-body">
            <div className="settings-section-title">
              <span>SELECT ACTIVE MODEL (OPENROUTER FREE MODELS)</span>
              <RedlineNote>// zero cost · top-tier inference</RedlineNote>
            </div>

            <div className="models-grid">
              {serverModels.map((m) => {
                const isSelected = selectedModel === m.id;
                return (
                  <button
                    type="button"
                    key={m.id}
                    className={'model-card' + (isSelected ? ' selected' : '')}
                    onClick={() => {
                      setSelectedModel(m.id);
                    }}
                  >
                    <div className="model-card-top">
                      <div className="model-name-wrap">
                        <span className="model-radio">{isSelected ? '◉' : '○'}</span>
                        <span className="model-name">{m.name}</span>
                      </div>
                      {m.tag && <span className={'model-tag ' + m.tag.toLowerCase().replace(/[^a-z]/g, '-')}>{m.tag}</span>}
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

            <DimensionDivider label="OPENROUTER CREDENTIAL STATUS" dim="SERVER ENV" />

            <div className="custom-model-form">
              <div className="form-group">
                <label className="form-label">OPENROUTER API KEY</label>
                <div className="status-v code">
                  {settingsQuery.data?.openRouterApiKeyConfigured ? '● CONFIGURED ON SERVER' : '○ NOT CONFIGURED'}
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
            <div className="settings-section-title">
              <span>RETRIEVAL AUGMENTED GENERATION TUNING</span>
              <RedlineNote>// pgvector + hybrid ranker knobs</RedlineNote>
            </div>

            <div className="rag-tuning-grid">
              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">TOP-K RETRIEVAL CANDIDATES</span>
                  <span className="tuning-value accent">{ragParams.topK} chunks</span>
                </div>
                <input
                  type="range"
                  min="2"
                  max="15"
                  step="1"
                  className="tuning-slider"
                  value={ragParams.topK}
                  onChange={(e) => setRagParams({ ...ragParams, topK: Number(e.target.value) })}
                />
                <p className="tuning-help">Number of closest chunks retrieved from pgvector to ground assistant responses.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">MINIMUM SIMILARITY SCORE</span>
                  <span className="tuning-value accent">{(ragParams.minScore * 100).toFixed(0)}%</span>
                </div>
                <input
                  type="range"
                  min="0.10"
                  max="0.80"
                  step="0.05"
                  className="tuning-slider"
                  value={ragParams.minScore}
                  onChange={(e) => setRagParams({ ...ragParams, minScore: Number(e.target.value) })}
                />
                <p className="tuning-help">Cosine similarity threshold below which candidate chunks are pruned.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">CONVERSATION HISTORY TURNS</span>
                  <span className="tuning-value accent">{ragParams.historyTurns} turns</span>
                </div>
                <input
                  type="range"
                  min="1"
                  max="8"
                  step="1"
                  className="tuning-slider"
                  value={ragParams.historyTurns}
                  onChange={(e) => setRagParams({ ...ragParams, historyTurns: Number(e.target.value) })}
                />
                <p className="tuning-help">Recent dialog turns replayed into model prompt for multi-turn context memory.</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">RETRIEVAL SCOPE — CATEGORY</span>
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
                <p className="tuning-help">Restrict grounding chunks to one KB category (e.g. advisories only).</p>
              </div>

              <div className="tuning-card">
                <div className="tuning-head">
                  <span className="tuning-label">RETRIEVAL SCOPE — MIN SEVERITY</span>
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
            <div className="settings-section-title">
              <span>SUPABASE INFRASTRUCTURE STATUS</span>
              <RedlineNote>// Auth &amp; pgvector backend</RedlineNote>
            </div>

            <div className="supabase-status-card">
              <div className="status-row">
                <span className="status-k">ENVIRONMENT:</span>
                <span className={'status-v ' + (isHostedSupabase ? 'hosted' : 'local')}>
                  {isHostedSupabase ? '● HOSTED CLOUD' : '○ LOCAL INSTANCE'}
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
                  {vectorQuery.isLoading ? 'CHECKING…' : vectorQuery.data?.healthy ? '● CONNECTED' : '○ UNAVAILABLE'}
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
                  OPEN HOSTED SUPABASE SETUP GUIDE &rarr;
                </a>
              </div>
            </div>
          </div>
        )}

        {/* Modal Footer */}
        <div className="settings-foot">
          <div className="settings-foot-left">
            <button className="settings-reset-btn" onClick={handleReset}>
              RESET DEFAULTS
            </button>
            {savedNotice && <span className="settings-saved-badge">✓ SETTINGS SAVED</span>}
          </div>
          <div className="settings-foot-right">
            <button className="settings-cancel-btn" onClick={onClose}>
              CANCEL
            </button>
            <StampButton
              className="btn-primary"
              action="APPLY"
              context="SETTINGS"
              style={{ padding: '9px 20px', fontSize: 11.5, margin: 0 }}
              onClick={handleSave}
            >
              SAVE SETTINGS
            </StampButton>
          </div>
        </div>
      </div>
    </div>
  );
}
