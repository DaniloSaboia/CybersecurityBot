// views/Assistant.jsx — Security research conversations and their evidence.

import { useState, useRef, useEffect, useCallback } from 'react';
import { Icon } from '../icons.jsx';
import { SourceChip, CopyBtn } from '../ui.jsx';
import { useSuggestions, useArticleIndex, useConversations, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { chatStream, deleteConversation, sendFeedback, getConversationMessages } from '../lib/api.js';
import { renderInline, renderRichText } from '../lib/richText.jsx';
import { conversationToMarkdown, conversationFilename, downloadFile } from '../lib/export.js';
import { getStoredModel, getStoredRagParams, DEFAULT_MODELS } from './SettingsModal.jsx';
import EvidenceDialog, { evidenceReferenceKey, isExactEvidenceReference } from './EvidenceDialog.jsx';
import './assistant.css';

// Keep these examples aligned with the existing backend SuggestionService.
const STARTER_QUESTIONS = [
  { id: 'triage', icon: 'alert', label: 'I clicked a suspicious email link. What should I do first?' },
  { id: 'summarize', icon: 'intel', label: 'Files are encrypted and there is a ransom note. How do we contain this safely?' },
  { id: 'playbook', icon: 'book', label: 'Someone accessed my work account. How do I regain control and check for damage?' },
  { id: 'report', icon: 'doc', label: 'An API key was exposed publicly. How do I revoke it and investigate misuse?' },
];

const STARTER_TITLES = {
  triage: 'Suspicious email or link',
  summarize: 'Ransomware or encrypted files',
  playbook: 'Compromised account',
  report: 'Exposed password or API key',
};

const INTRO_MSG = {
  role: 'assistant',
  intro: true,
  blocks: [{
    type: 'text',
    text: "I am your SecOps Knowledge Assistant. I answer security questions grounded strictly in your knowledge base: threat intelligence, playbooks, CVE advisories, policies, and postmortems — with full source provenance.",
  }],
  citations: [],
  citationReferences: [],
};

export default function Assistant({ onOpenArticle, pendingAsk, onPendingAskConsumed, activeModel, onOpenSettings, userId }) {
  const [messages, setMessages] = useState([INTRO_MSG]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState(null);
  const [threadsOpen, setThreadsOpen] = useState(false);
  const [deletingConversationId, setDeletingConversationId] = useState(null);
  const [conversationActionError, setConversationActionError] = useState('');
  const [evidenceSelection, setEvidenceSelection] = useState(null);
  const scrollRef = useRef(null);
  const endRef = useRef(null);
  const streamRequestRef = useRef(0);
  const streamAbortRef = useRef(null);
  const historyRequestRef = useRef(0);
  const evidenceOpenerRef = useRef(null);
  const consumedAskRef = useRef(null);

  const [busy, setBusy] = useState(false);
  const [thinking, setThinking] = useState(false);
  const articleIndexQuery = useArticleIndex();
  const suggestionsQuery = useSuggestions();
  const conversationsQuery = useConversations();
  const vectorStatusQuery = useVectorStoreStatus();
  const { index } = articleIndexQuery;
  const suggestions = suggestionsQuery.data;
  const conversations = conversationsQuery.data;
  const vectorStatus = vectorStatusQuery.data;

  useEffect(() => {
    const stream = scrollRef.current;
    if (stream) stream.scrollTop = messages.length === 1 && messages[0].intro ? 0 : stream.scrollHeight;
  }, [messages, thinking]);

  useEffect(() => () => {
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    streamRequestRef.current += 1;
    historyRequestRef.current += 1;
  }, []);

  // The App boundary normally remounts this view on identity change. Keep the
  // local guard as well so a direct rerender cannot briefly retain an A-owned
  // evidence selection while B is becoming active.
  useEffect(() => {
    setEvidenceSelection(null);
  }, [userId]);

  const refreshConversations = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['conversations'] });
  }, []);

  function cancelActiveStream() {
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    streamRequestRef.current += 1;
  }

  const closeEvidence = useCallback(() => setEvidenceSelection(null), []);

  const openEvidence = useCallback((reference, opener) => {
    if (!isExactEvidenceReference(reference)) return;
    evidenceOpenerRef.current = opener || document.activeElement;
    setEvidenceSelection({ ownerId: userId, reference });
  }, [userId]);

  // Start a fresh thread
  function newChat() {
    cancelActiveStream();
    closeEvidence();
    historyRequestRef.current += 1;
    setConversationId(null);
    setMessages([INTRO_MSG]);
    setBusy(false);
    setThinking(false);
    setThreadsOpen(false);
    setConversationActionError('');
  }

  // Load a persisted thread from the API and render it as turns.
  async function openConversation(id) {
    if (id === conversationId) {
      setThreadsOpen(false);
      return;
    }
    cancelActiveStream();
    closeEvidence();
    const requestId = ++historyRequestRef.current;
    setConversationId(id);
    setBusy(false);
    setThinking(false);
    setThreadsOpen(false);
    setConversationActionError('');
    setMessages([{ role: 'assistant', loading: true, blocks: [], citations: [] }]);
    try {
      const history = await getConversationMessages(id);
      if (historyRequestRef.current !== requestId) return;
      const turns = history.map((m) =>
        m.role === 'user'
          ? { role: 'user', text: m.content }
          : {
              role: 'assistant',
              id: m.id,
              blocks: [{ type: 'text', text: m.content }],
              citations: m.citations || [],
              citationReferences: Array.isArray(m.citationReferences) ? m.citationReferences : [],
              rating: m.rating,
            }
      );
      setMessages(turns.length ? turns : [INTRO_MSG]);
    } catch (err) {
      if (historyRequestRef.current !== requestId) return;
      setMessages([{
        role: 'assistant',
        blocks: [{
          type: 'callout',
          tone: 'critical',
          title: 'Archive Retrieval Failed',
          text: String((err && err.message) || err || 'Unable to load this saved thread.'),
        }],
        citations: [],
      }]);
    }
  }

  async function removeConversation(id) {
    if (!window.confirm('Delete this saved thread? This action cannot be undone.')) return;
    setDeletingConversationId(id);
    setConversationActionError('');
    try {
      await deleteConversation(id);
      if (id === conversationId) newChat();
      refreshConversations();
    } catch (err) {
      setConversationActionError(String((err && err.message) || err || 'Unable to delete the thread.'));
    } finally {
      setDeletingConversationId(null);
    }
  }

  // Stream the answer over SSE
  function send(text) {
    const q = (text != null ? text : input).trim();
    if (!q || busy || streamAbortRef.current) return false;
    historyRequestRef.current += 1;
    streamAbortRef.current?.abort();
    const controller = new AbortController();
    streamAbortRef.current = controller;
    const requestId = ++streamRequestRef.current;
    setMessages((m) => [...m, { role: 'user', text: q }]);
    setInput('');
    setBusy(true);
    setThinking(true);

    const currentModel = activeModel || getStoredModel(userId);
    const currentRag = getStoredRagParams(userId);

    chatStream(
      {
        message: q,
          conversationId,
          model: currentModel,
          topK: currentRag?.topK,
          minScore: currentRag?.minScore,
          historyTurns: currentRag?.historyTurns,
          category: currentRag?.category || null,
          minSeverity: currentRag?.minSeverity || null,
          signal: controller.signal,
      },
      {
        onDelta: (t) => {
          if (streamRequestRef.current !== requestId) return;
          setThinking(false);
          setMessages((m) => {
            const last = m[m.length - 1];
            if (last && last.role === 'assistant' && last.streaming) {
              const prev = (last.blocks && last.blocks[0] && last.blocks[0].text) || '';
              const copy = m.slice();
              copy[copy.length - 1] = { ...last, blocks: [{ type: 'text', text: prev + t }] };
              return copy;
            }
            return [...m, { role: 'assistant', streaming: true, blocks: [{ type: 'text', text: t }], citations: [] }];
          });
        },
        onDone: (evt) => {
          if (streamRequestRef.current !== requestId) return;
          if (streamAbortRef.current === controller) streamAbortRef.current = null;
          setThinking(false);
          setBusy(false);
          if (evt && evt.conversationId) setConversationId(evt.conversationId);
          refreshConversations();
          setMessages((m) => {
            const copy = m.slice();
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && last.streaming) {
              copy[copy.length - 1] = {
                ...last,
                streaming: false,
                id: evt && evt.messageId,
                citations: (evt && evt.citations) || [],
                citationReferences: Array.isArray(evt && evt.citationReferences) ? evt.citationReferences : [],
              };
            } else {
              copy.push({
                role: 'assistant',
                id: evt && evt.messageId,
                blocks: [{ type: 'text', text: 'No grounded answer was returned.' }],
                citations: (evt && evt.citations) || [],
                citationReferences: Array.isArray(evt && evt.citationReferences) ? evt.citationReferences : [],
              });
            }
            return copy;
          });
        },
        onError: (err) => {
          if (streamRequestRef.current !== requestId) return;
          if (streamAbortRef.current === controller) streamAbortRef.current = null;
          setThinking(false);
          setBusy(false);
          setMessages((m) => [...m, {
            role: 'assistant',
            blocks: [{ type: 'callout', tone: 'critical', title: 'Execution Fault', text: String((err && err.message) || err) }],
            citations: [],
            citationReferences: [],
          }]);
        },
      }
    );
    return true;
  }

  useEffect(() => {
    if (!pendingAsk?.text || busy || consumedAskRef.current === pendingAsk) return undefined;
    let cancelled = false;
    // StrictMode replays mount effects. Start only after that replay settles,
    // then consume the cross-view request so returning from a citation cannot
    // submit it again in a fresh Assistant mount.
    queueMicrotask(() => {
      if (!cancelled && send(pendingAsk.text)) {
        consumedAskRef.current = pendingAsk;
        onPendingAskConsumed?.(pendingAsk);
      }
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAsk, busy, onPendingAskConsumed]);

  // Export the current transcript as a Markdown file.
  function exportThread() {
    const title = (conversations || []).find((c) => c.id === conversationId)?.title
      || (messages.find((m) => m.role === 'user')?.text || 'transcript');
    downloadFile(conversationFilename(title), conversationToMarkdown(messages, title));
  }

  const showSuggestions = messages.length <= 1 && !busy;
  const selectedModel = activeModel || getStoredModel(userId);
  const modelName = DEFAULT_MODELS.find((model) => model.id === selectedModel)?.name || selectedModel;
  const isEmpty = messages.length === 1 && messages[0].intro;
  const docCount = articleIndexQuery.isPending ? '—' : articleIndexQuery.isError ? 'Unavailable' : index.size;
  const chunkCount = vectorStatusQuery.isPending
    ? '—'
    : vectorStatusQuery.isError
      ? 'Unavailable'
      : vectorStatus && vectorStatus.chunks != null
        ? vectorStatus.chunks.toLocaleString()
        : '—';

  return (
    <div className={'assistant investigation-desk' + (isEmpty ? ' is-empty' : '')}>
      <div className="view-head">
        <div>
          <h1 className="view-title">AI Assistant</h1>
          <p className="view-sub">Find your next step with guidance from your security knowledge base.</p>
        </div>
        <div className="assistant-head-actions">
          <div className="assistant-scope" role="status" aria-live="polite">
            <Icon name="database" size={14} />
            <span>{docCount} {docCount === 1 ? 'document' : 'documents'} <span aria-hidden="true">·</span> {chunkCount} indexed passages</span>
          </div>
          <button
            type="button"
            className="assistant-export"
            aria-label="EXPORT .MD"
            title="Export this transcript as Markdown"
            disabled={messages.length <= 1}
            onClick={exportThread}
          >
            <Icon name="download" size={14} /> Export .md
          </button>
        </div>
      </div>

      <div className="assistant-body">
        <button
          type="button"
          className="conv-mobile-toggle"
          aria-label="SAVED THREADS"
          aria-controls="saved-thread-panel"
          aria-expanded={threadsOpen}
          onClick={() => setThreadsOpen(true)}
        >
          <Icon name="chat" size={14} /> Saved threads
        </button>
        {threadsOpen && (
          <button
            type="button"
            className="conv-mobile-scrim"
            aria-label="Close saved threads"
            onClick={() => setThreadsOpen(false)}
          />
        )}
        <aside
          id="saved-thread-panel"
          className={'conv-side' + (threadsOpen ? ' mobile-open' : '')}
          aria-label="Saved conversation threads"
        >
          <button type="button" aria-label="CLOSE" className="conv-mobile-close" onClick={() => setThreadsOpen(false)}>
            <Icon name="x" size={13} /> Close
          </button>
          <button type="button" className="conv-new" onClick={newChat}>
            <Icon name="chat" size={14} /> New conversation
          </button>
          <div className="conv-label"><Icon name="clock" size={13} /> Recent threads</div>
          <div className="conv-list">
            {conversationsQuery.isPending && (
              <div className="conv-empty" role="status">Loading threads…</div>
            )}
            {conversationsQuery.isError && (
              <div className="conv-empty" role="alert">
                Threads unavailable
                <button type="button" className="assistant-retry" aria-label="RETRY" onClick={() => conversationsQuery.refetch()}>Retry</button>
              </div>
            )}
            {(conversations || []).map((c) => (
              <div key={c.id} className={'conv-item' + (c.id === conversationId ? ' active' : '')}>
                <button
                  type="button"
                  className="conv-open"
                  onClick={() => openConversation(c.id)}
                  title={c.title}
                  aria-current={c.id === conversationId ? 'true' : undefined}
                >
                  <Icon name="chat" size={12} />
                  <span>{c.title}</span>
                </button>
                <button
                  type="button"
                  className="conv-del"
                  title="Delete thread"
                  aria-label={`Delete saved thread: ${c.title}`}
                  disabled={deletingConversationId === c.id}
                  onClick={() => removeConversation(c.id)}
                >
                  <Icon name={deletingConversationId === c.id ? 'refresh' : 'trash'} size={12} />
                </button>
              </div>
            ))}
            {conversationsQuery.isSuccess && (conversations || []).length === 0 && (
              <div className="conv-empty conv-empty-start"><span>No saved threads yet</span><p>Your conversations will appear here after you ask a question.</p></div>
            )}
            {conversationActionError && (
              <div className="conv-empty" role="alert" style={{ color: 'var(--redline-bright)' }}>
                Delete failed — {conversationActionError}
              </div>
            )}
          </div>
        </aside>

        <div className="chat-col">
          <div
            className="chat-stream"
            ref={scrollRef}
            role="log"
            aria-live="polite"
            aria-relevant="additions text"
            aria-busy={busy}
          >
            <div className="chat-stream-inner">

              {messages.length === 1 && messages[0].intro && (
                <section className="assistant-start">
                  <h2>What happened?</h2>
                  <p>Describe a security problem in your own words. Get help with what to check, what to do next, and how to know it worked.</p>
                  <div className="assistant-evidence-guide">
                    <Icon name="link" size={17} />
                    <span>Follow an answer back to its sources.<small>Open cited articles and, when available, the exact revision and passage used.</small></span>
                  </div>
                </section>
              )}
              {isEmpty && <IncidentIntake onSend={send} />}
              {messages.filter((m) => !m.intro).map((m, i) =>
                m.role === 'user' ? (
                  <UserBubble key={i} text={m.text} />
                ) : (
                  <AssistantTurn key={i} msg={m} onOpenArticle={onOpenArticle} onViewEvidence={openEvidence} index={index} fresh={false} />
                )
              )}
              {thinking && <Thinking />}
              {showSuggestions && (
                <Suggestions
                  items={suggestions}
                  loading={suggestionsQuery.isPending}
                  error={suggestionsQuery.error}
                  onRetry={() => suggestionsQuery.refetch()}
                  onPick={(s) => send(s.label)}
                />
              )}
              <div ref={endRef} />
            </div>
          </div>

          <div className="composer">
            <div className="composer-inner">
              <textarea
                className="composer-input"
                aria-label="Technical query"
                placeholder="Ask a security question…"
                value={input}
                rows={2}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              />
              <div className="composer-toolbar">
                <button
                  type="button"
                  className="composer-model"
                  aria-label={`Change AI model: ${modelName}`}
                  title="Choose AI model and retrieval settings"
                  disabled={!onOpenSettings}
                  onClick={onOpenSettings}
                >
                  <Icon name="cpu" size={14} />
                  <span>{modelName}</span>
                  <Icon name="chevron" size={12} />
                </button>
                <button
                  type="button"
                  className="composer-send"
                  aria-label={busy ? 'Assistant is responding' : 'Send technical query'}
                  disabled={busy || !input.trim()}
                  onClick={() => send()}
                >
                  <Icon name="send" size={17} />
                </button>
              </div>
            </div>
            <div className="composer-foot">
              <span>Review cited evidence before acting.</span>
              <span className="composer-shortcut">Enter to send · Shift + Enter for a new line</span>
            </div>
          </div>
        </div>
      </div>
      {evidenceSelection?.ownerId === userId && (
        <EvidenceDialog
          key={`${userId}:${evidenceReferenceKey(evidenceSelection.reference)}`}
          reference={evidenceSelection.reference}
          userId={userId}
          onClose={closeEvidence}
          returnFocusRef={evidenceOpenerRef}
        />
      )}
    </div>
  );
}

function UserBubble({ text }) {
  return (
    <div className="turn turn-user">
      <div className="bubble-user"><span className="turn-label">Your question</span>{text}</div>
      <div className="avatar avatar-user"><Icon name="user" size={14} /></div>
    </div>
  );
}

function IncidentIntake({ onSend }) {
  const [happened, setHappened] = useState('');
  const [affected, setAffected] = useState('');
  const [actions, setActions] = useState('');

  function submit(event) {
    event.preventDefault();
    if (!happened.trim()) return;
    onSend([
      'Help me respond to this cybersecurity problem using the relevant playbook and cite your sources.',
      `What happened: ${happened.trim()}`,
      `Affected devices, accounts, or services: ${affected.trim() || 'Not yet known.'}`,
      `Actions already taken: ${actions.trim() || 'Not specified.'}`,
      'Give me a prioritized plan: what to do now, what evidence to preserve, how to recover, how to verify recovery, and when to contact IT or an incident responder. Explain the purpose and expected result of each action. Ask up to three essential questions if needed; do not assume missing facts.',
    ].join('\n\n'));
  }

  return (
    <details className="incident-intake">
      <summary><Icon name="shield" size={17} /><span>Build a response plan<small>Add the details that make the guidance useful to you.</small></span><Icon name="chevron" size={16} /></summary>
      <form onSubmit={submit}>
        <label htmlFor="incident-happened">What happened? <span>Required</span></label>
        <textarea id="incident-happened" value={happened} onChange={(event) => setHappened(event.target.value)} required maxLength={4000} rows={3} placeholder="For example: I entered my password after following a link in an email about an unpaid invoice." />
        <div className="incident-intake-fields">
          <div><label htmlFor="incident-affected">What is affected?</label><input id="incident-affected" value={affected} onChange={(event) => setAffected(event.target.value)} maxLength={1500} placeholder="Devices, accounts, services, or not sure" /></div>
          <div><label htmlFor="incident-actions">What have you already done?</label><input id="incident-actions" value={actions} onChange={(event) => setActions(event.target.value)} maxLength={1500} placeholder="For example: reported it to IT" /></div>
        </div>
        <div className="incident-intake-footer"><p>Describe the issue without passwords, secret keys, or personal data.</p><button type="submit" disabled={!happened.trim()}>Get response plan <Icon name="arrowright" size={15} /></button></div>
      </form>
    </details>
  );
}

function AssistantTurn({ msg, onOpenArticle, onViewEvidence, index, fresh }) {
  const blocks = Array.isArray(msg.blocks) ? msg.blocks : [];
  const cites = Array.isArray(msg.citations) ? msg.citations : [];
  const citationReferences = Array.isArray(msg.citationReferences) ? msg.citationReferences : [];
  const hasCites = cites.length > 0;
  const hasCitationReferences = citationReferences.length > 0;
  const [revealing, setRevealing] = useState(fresh);
  const [rating, setRating] = useState(msg.rating == null ? null : msg.rating);
  const [ratingError, setRatingError] = useState('');

  useEffect(() => {
    if (!fresh) return;
    const n = blocks.length + (hasCites ? 1 : 0) + (hasCitationReferences ? 1 : 0);
    const t = setTimeout(() => setRevealing(false), n * 140 + 750);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function rate(value) {
    const previous = rating;
    const next = rating === value ? 0 : value;
    setRating(next === 0 ? null : next);
    setRatingError('');
    try {
      await sendFeedback(msg.id, next);
    } catch (err) {
      setRating(previous);
      setRatingError(String((err && err.message) || err || 'Unable to save feedback.'));
    }
  }

  return (
    <div className={'turn turn-assistant' + (revealing ? ' fresh' : '')}>
      <div className="avatar avatar-bot"><Icon name="shield" size={14} /></div>
      <div className="turn-body">
        <div className="turn-label">{msg.streaming ? 'Answering' : 'Assistant'}</div>
        {msg.loading && <div className="thinking" role="status"><span /><span /><span /> <em>Retrieving conversation…</em></div>}
        {blocks.map((b, i) => (
          <div className="block-anim" key={i} style={{ animationDelay: (i * 0.12 + 0.04) + 's' }}>
            <Block b={b} onOpen={onOpenArticle} index={index} citations={cites} citationReferences={citationReferences} />
          </div>
        ))}
        {hasCites && (
          <div className="block-anim" style={{ animationDelay: (blocks.length * 0.12 + 0.08) + 's' }}>
            <div className="citations">
              <div className="citations-label">
                <Icon name="link" size={12} /> Sources <span className="citations-count">{cites.length}</span>
              </div>
              <div className="citations-list">
                {cites.map((id) => <SourceChip key={id} id={id} onOpen={onOpenArticle} index={index} />)}
              </div>
            </div>
          </div>
        )}
        {hasCitationReferences && (
          <div className="block-anim" style={{ animationDelay: ((blocks.length + (hasCites ? 1 : 0)) * 0.12 + 0.08) + 's' }}>
            <div className="citations evidence-references">
              <div className="citations-label">
                <Icon name="link" size={12} /> Exact evidence <span className="citations-count">{citationReferences.length}</span>
              </div>
              <p className="evidence-context">Revisions and passages used for this answer.</p>
              <div className="citations-list">
                {citationReferences.map((reference, i) => {
                  if (!isExactEvidenceReference(reference)) {
                    return <div className="evidence-reference-unavailable" role="status" key={`unavailable-${i}`}>Evidence record unavailable</div>;
                  }
                  return (
                    <button
                      type="button"
                      className="evidence-reference"
                      key={evidenceReferenceKey(reference)}
                      aria-label={`View exact evidence for ${reference.articleId}, revision ${reference.revisionNumber}`}
                      onClick={(event) => onViewEvidence?.(reference, event.currentTarget)}
                    >
                      <Icon name="doc" size={16} />
                      <span className="evidence-reference-id">{reference.articleId}</span>
                      <span className="evidence-reference-revision">Revision {reference.revisionNumber}</span>
                      <span className="evidence-reference-open">View passage <Icon name="chevron" size={13} /></span>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}
        {msg.id && !msg.streaming && !msg.intro && (
          <div className="msg-feedback">
            <CopyBtn
              className="fb-copy-btn"
              label="Copy Answer"
              text={blocks.map((b) => (b.type === 'text' ? b.text : b.type === 'steps' ? (b.items || []).join('\n') : b.title || '')).join('\n\n')}
            />
            <button
              type="button"
              className={'fb-btn' + (rating === 1 ? ' on' : '')}
              aria-label="HELPFUL"
              title="Helpful answer"
              aria-pressed={rating === 1}
              disabled={!msg.id}
              onClick={() => rate(1)}
            >
              <Icon name="thumbsUp" size={13} /> Helpful
            </button>
            <button
              type="button"
              className={'fb-btn' + (rating === -1 ? ' on' : '')}
              aria-label="NOT HELPFUL"
              title="Not helpful"
              aria-pressed={rating === -1}
              disabled={!msg.id}
              onClick={() => rate(-1)}
            >
              <Icon name="thumbsDown" size={13} /> Not helpful
            </button>
          </div>
        )}
        {ratingError && <div className="conv-empty" role="alert" style={{ color: 'var(--redline-bright)' }}>Feedback failed — {ratingError}</div>}
      </div>
    </div>
  );
}

function Block({ b, onOpen, citations, citationReferences }) {
  if (!b) return null;
  const references = { citations, citationReferences };
  if (b.type === 'text') return <div className="msg-rich">{renderRichText(b.text, onOpen, references)}</div>;
  if (b.type === 'steps') {
    const items = Array.isArray(b.items) ? b.items : [];
    return (
      <ol className="msg-steps">
        {items.map((it, i) => (
          <li key={i}>
            <span className="step-n" aria-hidden="true">{i + 1}.</span>
            <span className="step-content">{renderInline(it, onOpen, references)}</span>
          </li>
        ))}
      </ol>
    );
  }
  if (b.type === 'callout')
    return (
      <div className={`msg-callout tone-${b.tone}`}>
        <Icon name="alert" size={15} />
        <div>
          <div className="callout-title">{renderInline(b.title, onOpen, references)}</div>
          <div className="callout-text">{renderRichText(b.text, onOpen, references)}</div>
        </div>
      </div>
    );
  if (b.type === 'report') {
    const rows = Array.isArray(b.rows) ? b.rows : [];
    return (
      <div className="msg-report">
        <div className="report-head">
          <Icon name="doc" size={14} />
          <span>{renderInline(b.title, onOpen, references)}</span>
          <CopyBtn className="report-copy" label="Copy" text={(b.title || '') + '\n\n' + rows.map((r) => r[0] + ': ' + r[1]).join('\n')} />
        </div>
        <table className="report-table">
          <tbody>
            {rows.map((r, i) => (<tr key={i}><th scope="row">{renderInline(r[0], onOpen, references)}</th><td>{renderInline(r[1], onOpen, references)}</td></tr>))}
          </tbody>
        </table>
      </div>
    );
  }
  return null;
}

function Thinking() {
  return (
    <div className="turn turn-assistant">
      <div className="avatar avatar-bot"><Icon name="shield" size={14} /></div>
      <div className="turn-body">
        <div className="thinking" role="status">
          <span /><span /><span />
          <em>Searching your knowledge base…</em>
        </div>
      </div>
    </div>
  );
}

function Suggestions({ items, onPick, loading, error, onRetry }) {
  if (loading) {
    return <div className="thinking" role="status"><span /><span /><span /> <em>Loading suggested questions…</em></div>;
  }
  if (error) {
    return (
      <div className="msg-callout tone-critical" role="alert">
        <Icon name="alert" size={15} />
        <div>
          <div className="callout-title">Suggestions unavailable</div>
          <div className="callout-text">{String(error.message || error)}</div>
          <button type="button" className="assistant-retry" aria-label="RETRY" onClick={onRetry}>Retry</button>
        </div>
      </div>
    );
  }
  const questions = items?.length ? items : STARTER_QUESTIONS;
  return (
    <div className="suggestions">
      <div className="suggestions-label">Start with a question</div>
      <div className="suggestions-grid">
        {questions.map((s) => (
          <button key={s.id} type="button" className="suggestion" aria-label={s.label} onClick={() => onPick(s)}>
            <span className="suggestion-icon"><Icon name={s.icon} size={15} /></span>
            <span className="suggestion-copy">
              {STARTER_TITLES[s.id] && <strong>{STARTER_TITLES[s.id]}</strong>}
              <span>{s.label}</span>
            </span>
            <Icon name="chevron" size={14} className="suggestion-arrow" />
          </button>
        ))}
      </div>
    </div>
  );
}
