// views/Assistant.jsx — Red Noir RAG Assistant console.

import { useState, useRef, useEffect, useCallback } from 'react';
import { Icon } from '../icons.jsx';
import { SourceChip, CopyBtn, DimensionDivider, RedlineNote, StampButton } from '../ui.jsx';
import { useSuggestions, useArticleIndex, useConversations, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { chatStream, deleteConversation, sendFeedback, getConversationMessages } from '../lib/api.js';
import { renderInline, renderRichText } from '../lib/richText.jsx';
import { conversationToMarkdown, conversationFilename, downloadFile } from '../lib/export.js';
import { getStoredModel, getStoredRagParams, DEFAULT_MODELS } from './SettingsModal.jsx';

const INTRO_MSG = {
  role: 'assistant',
  intro: true,
  blocks: [{
    type: 'text',
    text: "I am your SecOps Knowledge Assistant. I answer security questions grounded strictly in your knowledge base: threat intelligence, playbooks, CVE advisories, policies, and postmortems — with full source provenance.",
  }],
  citations: [],
};

export default function Assistant({ onOpenArticle, pendingAsk, activeModel, onOpenSettings }) {
  const [messages, setMessages] = useState([INTRO_MSG]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState(null);
  const [threadsOpen, setThreadsOpen] = useState(false);
  const [deletingConversationId, setDeletingConversationId] = useState(null);
  const [conversationActionError, setConversationActionError] = useState('');
  const scrollRef = useRef(null);
  const endRef = useRef(null);
  const streamRequestRef = useRef(0);
  const historyRequestRef = useRef(0);

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
    if (stream) stream.scrollTop = stream.scrollHeight;
  }, [messages, thinking]);

  useEffect(() => () => {
    streamRequestRef.current += 1;
    historyRequestRef.current += 1;
  }, []);

  const refreshConversations = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['conversations'] });
  }, []);

  // Start a fresh thread
  function newChat() {
    streamRequestRef.current += 1;
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
    streamRequestRef.current += 1;
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
    if (!q || busy) return;
    historyRequestRef.current += 1;
    const requestId = ++streamRequestRef.current;
    setMessages((m) => [...m, { role: 'user', text: q }]);
    setInput('');
    setBusy(true);
    setThinking(true);

    const currentModel = activeModel || getStoredModel();
    const currentRag = getStoredRagParams();

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
              };
            } else {
              copy.push({
                role: 'assistant',
                id: evt && evt.messageId,
                blocks: [{ type: 'text', text: 'No grounded answer was returned.' }],
                citations: (evt && evt.citations) || [],
              });
            }
            return copy;
          });
        },
        onError: (err) => {
          if (streamRequestRef.current !== requestId) return;
          setThinking(false);
          setBusy(false);
          setMessages((m) => [...m, {
            role: 'assistant',
            blocks: [{ type: 'callout', tone: 'critical', title: 'Execution Fault', text: String((err && err.message) || err) }],
            citations: [],
          }]);
        },
      }
    );
  }

  useEffect(() => {
    if (pendingAsk && pendingAsk.text) send(pendingAsk.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAsk && pendingAsk.n]);

  // Export the current transcript as a Markdown file.
  function exportThread() {
    const title = (conversations || []).find((c) => c.id === conversationId)?.title
      || (messages.find((m) => m.role === 'user')?.text || 'transcript');
    downloadFile(conversationFilename(title), conversationToMarkdown(messages, title));
  }

  const showSuggestions = messages.length <= 1 && !busy;
  const docCount = articleIndexQuery.isPending ? '—' : articleIndexQuery.isError ? 'ERR' : index.size;
  const chunkCount = vectorStatusQuery.isPending
    ? '—'
    : vectorStatusQuery.isError
      ? 'ERR'
      : vectorStatus && vectorStatus.chunks != null
        ? vectorStatus.chunks.toLocaleString()
        : '—';

  return (
    <div className="assistant">
      <div className="view-head">
        <div>
          <h1 className="view-title">AI Assistant</h1>
          <p className="view-sub">Grounded Q&amp;A over the security knowledge base · every answer cited</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <RedlineNote>// ground-truth citations active</RedlineNote>
          <StampButton
            action="EXPORT"
            context=".MD"
            tone="approved"
            className="fb-btn"
            title="Export this transcript as Markdown"
            disabled={messages.length <= 1}
            onClick={exportThread}
          >
            <Icon name="download" size={12} /> EXPORT .MD
          </StampButton>
          <div className="assistant-scope" role="status" aria-live="polite">
            <Icon name="database" size={14} />
            <span>{docCount} DOCS · {chunkCount} CHUNKS INDEXED</span>
          </div>
        </div>
      </div>

      <div className="assistant-body">
        <StampButton
          className="conv-mobile-toggle"
          action="THREADS"
          context="SHT-01"
          aria-controls="saved-thread-panel"
          aria-expanded={threadsOpen}
          onClick={() => setThreadsOpen(true)}
        >
          <Icon name="chat" size={13} /> SAVED THREADS
        </StampButton>
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
          <button type="button" className="conv-mobile-close" onClick={() => setThreadsOpen(false)}>
            <Icon name="x" size={13} /> CLOSE
          </button>
          <StampButton className="conv-new" action="NEW" context="CHAT" onClick={newChat}>
            <Icon name="spark" size={14} /> + NEW CHAT
          </StampButton>
          <div className="conv-label">Recent Threads</div>
          <div className="conv-list">
            {conversationsQuery.isPending && (
              <div className="conv-empty" role="status">Loading threads…</div>
            )}
            {conversationsQuery.isError && (
              <div className="conv-empty" role="alert">
                Threads unavailable
                <StampButton tone="redline" text="RETRY" onClick={() => conversationsQuery.refetch()} />
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
              <div className="conv-empty">No saved threads yet</div>
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
              <DimensionDivider label="SESSION STREAM" dim="860PX" />
              {messages.map((m, i) =>
                m.role === 'user' ? (
                  <UserBubble key={i} text={m.text} />
                ) : (
                  <AssistantTurn key={i} msg={m} onOpenArticle={onOpenArticle} index={index} fresh={false} />
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
                placeholder="Ask anything: alert triage, IOC lookup, CVE details, playbook guidance…"
                value={input}
                rows={1}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              />
              <button
                type="button"
                className="composer-send"
                aria-label={busy ? 'Assistant is responding' : 'Send technical query'}
                disabled={busy || !input.trim()}
                onClick={() => send()}
              >
                <Icon name="send" size={15} />
              </button>
            </div>
            <div className="composer-foot">
              <span><Icon name="shield" size={12} /> ANSWERS GROUNDED STRICTLY IN YOUR INDEXED CORPUS</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function UserBubble({ text }) {
  return (
    <div className="turn turn-user">
      <div className="bubble-user">{text}</div>
      <div className="avatar avatar-user"><Icon name="user" size={14} /></div>
    </div>
  );
}

function AssistantTurn({ msg, onOpenArticle, index, fresh }) {
  const blocks = Array.isArray(msg.blocks) ? msg.blocks : [];
  const cites = Array.isArray(msg.citations) ? msg.citations : [];
  const hasCites = cites.length > 0;
  const [revealing, setRevealing] = useState(fresh);
  const [rating, setRating] = useState(msg.rating == null ? null : msg.rating);
  const [ratingError, setRatingError] = useState('');

  useEffect(() => {
    if (!fresh) return;
    const n = blocks.length + (hasCites ? 1 : 0);
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
        {msg.loading && <div className="thinking" role="status"><span /><span /><span /> <em>Retrieving conversation…</em></div>}
        {blocks.map((b, i) => (
          <div className="block-anim" key={i} style={{ animationDelay: (i * 0.12 + 0.04) + 's' }}>
            <Block b={b} onOpen={onOpenArticle} index={index} />
          </div>
        ))}
        {hasCites && (
          <div className="block-anim" style={{ animationDelay: (blocks.length * 0.12 + 0.08) + 's' }}>
            <div className="citations">
              <div className="citations-label">
                <Icon name="link" size={12} /> SOURCES // {cites.length} GROUNDED PASSAGES
              </div>
              <div className="citations-list">
                {cites.map((id) => <SourceChip key={id} id={id} onOpen={onOpenArticle} index={index} />)}
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
            <StampButton
              className={'fb-btn' + (rating === 1 ? ' on' : '')}
              action="HELPFUL"
              context="RATE"
              tone="approved"
              active={rating === 1}
              title="Helpful answer"
              aria-pressed={rating === 1}
              disabled={!msg.id}
              onClick={() => rate(1)}
            >
              <Icon name="check" size={12} /> HELPFUL
            </StampButton>
            <StampButton
              className={'fb-btn' + (rating === -1 ? ' on' : '')}
              action="NOT HELPFUL"
              context="RATE"
              tone="redline"
              active={rating === -1}
              title="Not helpful"
              aria-pressed={rating === -1}
              disabled={!msg.id}
              onClick={() => rate(-1)}
            >
              <Icon name="x" size={12} /> NOT HELPFUL
            </StampButton>
          </div>
        )}
        {ratingError && <div className="conv-empty" role="alert" style={{ color: 'var(--redline-bright)' }}>Feedback failed — {ratingError}</div>}
      </div>
    </div>
  );
}

function Block({ b, onOpen }) {
  if (!b) return null;
  if (b.type === 'text') return <div className="msg-rich">{renderRichText(b.text, onOpen)}</div>;
  if (b.type === 'steps') {
    const items = Array.isArray(b.items) ? b.items : [];
    return (
      <ol className="msg-steps">
        {items.map((it, i) => (
          <li key={i}>
            <span className="step-n">[{String(i + 1).padStart(2, '0')}.0]</span>
            <span>{renderInline(it, onOpen)}</span>
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
          <div className="callout-title">{b.title}</div>
          <div className="callout-text">{b.text}</div>
        </div>
      </div>
    );
  if (b.type === 'report') {
    const rows = Array.isArray(b.rows) ? b.rows : [];
    return (
      <div className="msg-report">
        <div className="report-head">
          <Icon name="doc" size={14} />
          <span>{b.title}</span>
          <CopyBtn className="report-copy" label="Copy" text={(b.title || '') + '\n\n' + rows.map((r) => r[0] + ': ' + r[1]).join('\n')} />
        </div>
        <table className="report-table">
          <tbody>
            {rows.map((r, i) => (<tr key={i}><th>{r[0]}</th><td>{r[1]}</td></tr>))}
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
          <em>SCANNING VECTOR SPACE</em>
        </div>
      </div>
    </div>
  );
}

function Suggestions({ items, onPick, loading, error, onRetry }) {
  if (loading) {
    return <div className="thinking" role="status"><span /><span /><span /> <em>LOADING SUGGESTIONS</em></div>;
  }
  if (error) {
    return (
      <div className="msg-callout tone-critical" role="alert">
        <Icon name="alert" size={15} />
        <div>
          <div className="callout-title">Suggestions unavailable</div>
          <div className="callout-text">{String(error.message || error)}</div>
          <StampButton className="fb-btn" tone="redline" text="RETRY" onClick={onRetry} />
        </div>
      </div>
    );
  }
  if (!items || items.length === 0) {
    return <div className="conv-empty">No suggested queries</div>;
  }
  return (
    <div className="suggestions">
      <div className="suggestions-label">Recommended Queries</div>
      <div className="suggestions-grid">
        {items.map((s) => (
          <button key={s.id} type="button" className="suggestion" onClick={() => onPick(s)}>
            <span className="suggestion-icon"><Icon name={s.icon} size={15} /></span>
            <span>{s.label}</span>
            <Icon name="chevron" size={13} className="suggestion-arrow" />
          </button>
        ))}
      </div>
    </div>
  );
}
