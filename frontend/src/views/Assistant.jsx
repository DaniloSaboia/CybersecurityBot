// views/Assistant.jsx — the RAG assistant / chatbot surface.
//
// Layout: a conversations rail on the left (thread list, new chat, delete)
// and the chat column on the right. Answers stream over SSE; when a stream
// finishes, its done event carries conversationId + messageId so the turn
// becomes part of the persisted thread and can be rated (👍/👎 feedback).

import { useState, useRef, useEffect, useCallback } from 'react';
import { Icon } from '../icons.jsx';
import { SourceChip, CopyBtn } from '../ui.jsx';
import { useSuggestions, useArticleIndex, useConversations, useVectorStoreStatus, queryClient } from '../lib/queries.js';
import { chatStream, deleteConversation, sendFeedback, getConversationMessages } from '../lib/api.js';
import { renderInline, renderRichText } from '../lib/richText.jsx';

const INTRO_MSG = {
  role: 'assistant',
  intro: true,
  blocks: [{ type: 'text', text: "I'm your security knowledge assistant. I search across threat intel, playbooks, advisories, policies and postmortems — and I cite every source. Ask me to triage an alert, pull a playbook, look up an IOC, or draft a report." }],
  citations: [],
};

export default function Assistant({ onOpenArticle, pendingAsk }) {
  const [messages, setMessages] = useState([INTRO_MSG]);
  const [input, setInput] = useState('');
  const [conversationId, setConversationId] = useState(null);
  const scrollRef = useRef(null);
  const endRef = useRef(null);

  const [busy, setBusy] = useState(false);
  const [thinking, setThinking] = useState(false);
  const { index } = useArticleIndex();
  const { data: suggestions } = useSuggestions();
  const { data: conversations } = useConversations();
  const { data: vectorStatus } = useVectorStoreStatus();

  useEffect(() => {
    if (endRef.current) endRef.current.parentNode.scrollTop = endRef.current.parentNode.scrollHeight;
  }, [messages, thinking]);

  const refreshConversations = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['conversations'] });
  }, []);

  // Start a fresh thread (optionally after deleting / switching away from one).
  function newChat() {
    setConversationId(null);
    setMessages([INTRO_MSG]);
    setBusy(false);
    setThinking(false);
  }

  // Load a persisted thread from the API and render it as turns.
  async function openConversation(id) {
    if (busy || id === conversationId) return;
    setConversationId(id);
    setMessages([{ role: 'assistant', loading: true, blocks: [], citations: [] }]);
    try {
      const history = await getConversationMessages(id);
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
    } catch {
      setMessages([INTRO_MSG]);
    }
  }

  async function removeConversation(id) {
    await deleteConversation(id);
    if (id === conversationId) newChat();
    refreshConversations();
  }

  // Stream the answer over SSE: show the thinking dots until the first token,
  // then grow a live text block; finalize citations on the done event.
  function send(text) {
    const q = (text != null ? text : input).trim();
    if (!q || busy) return;
    setMessages((m) => [...m, { role: 'user', text: q }]);
    setInput('');
    setBusy(true);
    setThinking(true);

    chatStream(
      { message: q, conversationId },
      {
        onDelta: (t) => {
          setThinking(false);
          // Pure updater: append to the existing streaming turn if there is one,
          // otherwise start a new one. (No external flag — StrictMode double-invokes
          // updaters, so they must be pure and idempotent.)
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
              copy.push({ role: 'assistant', id: evt && evt.messageId, blocks: [{ type: 'text', text: 'No grounded answer was returned.' }], citations: (evt && evt.citations) || [] });
            }
            return copy;
          });
        },
        onError: (err) => {
          setThinking(false);
          setBusy(false);
          setMessages((m) => [...m, {
            role: 'assistant',
            blocks: [{ type: 'callout', tone: 'critical', title: 'Request failed', text: String((err && err.message) || err) }],
            citations: [],
          }]);
        },
      }
    );
  }

  // pendingAsk triggers a cross-view send (KB "Ask assistant")
  useEffect(() => {
    if (pendingAsk && pendingAsk.text) send(pendingAsk.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAsk && pendingAsk.n]);

  const showSuggestions = messages.length <= 1 && !busy;
  const docCount = index.size || 0;
  const chunkCount = vectorStatus && vectorStatus.chunks != null ? vectorStatus.chunks : '—';

  return (
    <div className="assistant">
      <div className="view-head">
        <div>
          <h1 className="view-title">Assistant</h1>
          <p className="view-sub">Grounded Q&amp;A over the security knowledge base · answers are cited</p>
        </div>
        <div className="assistant-scope">
          <Icon name="database" size={14} />
          <span>{docCount} docs · {chunkCount} chunks indexed</span>
        </div>
      </div>

      <div className="assistant-body">
        <aside className="conv-side">
          <button className="conv-new" onClick={newChat}>
            <Icon name="spark" size={14} /> New chat
          </button>
          <div className="conv-label">THREADS</div>
          <div className="conv-list">
            {(conversations || []).map((c) => (
              <div key={c.id} className={'conv-item' + (c.id === conversationId ? ' active' : '')}>
                <button className="conv-open" onClick={() => openConversation(c.id)} title={c.title}>
                  <Icon name="chat" size={13} />
                  <span>{c.title}</span>
                </button>
                <button className="conv-del" title="Delete thread" onClick={() => removeConversation(c.id)}>
                  <Icon name="trash" size={12} />
                </button>
              </div>
            ))}
            {conversations && conversations.length === 0 && (
              <div className="conv-empty">No saved threads yet</div>
            )}
          </div>
        </aside>

        <div className="chat-col">
          <div className="chat-stream" ref={scrollRef}>
            <div className="chat-stream-inner">
              {messages.map((m, i) =>
                m.role === 'user' ? (
                  <UserBubble key={i} text={m.text} />
                ) : (
                  <AssistantTurn key={i} msg={m} onOpenArticle={onOpenArticle} index={index} fresh={false} />
                )
              )}
              {thinking && <Thinking />}
              {showSuggestions && <Suggestions items={suggestions} onPick={(s) => send(s.label)} />}
              <div ref={endRef} />
            </div>
          </div>

          <div className="composer">
            <div className="composer-inner">
              <button className="composer-attach" title="Attach context"><Icon name="upload" size={17} /></button>
              <textarea
                className="composer-input"
                placeholder="Ask about an alert, IOC, CVE, policy, or playbook…"
                value={input}
                rows={1}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              />
              <button className="composer-send" disabled={!input.trim()} onClick={() => send()}>
                <Icon name="send" size={16} />
              </button>
            </div>
            <div className="composer-foot">
              <span><Icon name="shield" size={12} /> Answers grounded in your KB — verify before acting on critical actions</span>
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
      <div className="avatar avatar-user"><Icon name="user" size={15} /></div>
    </div>
  );
}

function AssistantTurn({ msg, onOpenArticle, index, fresh }) {
  const blocks = Array.isArray(msg.blocks) ? msg.blocks : [];
  const cites = Array.isArray(msg.citations) ? msg.citations : [];
  const hasCites = cites.length > 0;
  const [revealing, setRevealing] = useState(fresh);
  const [rating, setRating] = useState(msg.rating == null ? null : msg.rating);
  useEffect(() => {
    if (!fresh) return;
    const n = blocks.length + (hasCites ? 1 : 0);
    const t = setTimeout(() => setRevealing(false), n * 140 + 750);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function rate(value) {
    const next = rating === value ? 0 : value; // toggle off
    setRating(next === 0 ? null : next);
    try { await sendFeedback(msg.id, next); } catch { /* rating is best-effort */ }
  }

  return (
    <div className={'turn turn-assistant' + (revealing ? ' fresh' : '')}>
      <div className="avatar avatar-bot"><Icon name="shield" size={15} /></div>
      <div className="turn-body">
        {msg.loading && <div className="thinking"><span /><span /><span /> <em>loading thread…</em></div>}
        {blocks.map((b, i) => (
          <div className="block-anim" key={i} style={{ animationDelay: (i * 0.13 + 0.05) + 's' }}>
            <Block b={b} onOpen={onOpenArticle} index={index} />
          </div>
        ))}
        {hasCites && (
          <div className="block-anim" style={{ animationDelay: (blocks.length * 0.13 + 0.1) + 's' }}>
            <div className="citations">
              <div className="citations-label"><Icon name="link" size={12} /> Grounded in {cites.length} source{cites.length > 1 ? 's' : ''}</div>
              <div className="citations-list">
                {cites.map((id) => <SourceChip key={id} id={id} onOpen={onOpenArticle} index={index} />)}
              </div>
            </div>
          </div>
        )}
        {msg.id && !msg.streaming && !msg.intro && (
          <div className="msg-feedback">
            <button
              className={'fb-btn' + (rating === 1 ? ' on' : '')}
              title="Helpful"
              disabled={!msg.id}
              onClick={() => rate(1)}
            >
              <Icon name="check" size={13} /> Helpful
            </button>
            <button
              className={'fb-btn' + (rating === -1 ? ' on' : '')}
              title="Not helpful"
              disabled={!msg.id}
              onClick={() => rate(-1)}
            >
              <Icon name="x" size={13} /> Not helpful
            </button>
          </div>
        )}
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
          <li key={i}><span className="step-n">{String(i + 1).padStart(2, '0')}</span><span>{renderInline(it, onOpen)}</span></li>
        ))}
      </ol>
    );
  }
  if (b.type === 'callout')
    return (
      <div className={`msg-callout tone-${b.tone}`}>
        <Icon name="alert" size={15} />
        <div><div className="callout-title">{b.title}</div><div className="callout-text">{b.text}</div></div>
      </div>
    );
  if (b.type === 'report') {
    const rows = Array.isArray(b.rows) ? b.rows : [];
    return (
      <div className="msg-report">
        <div className="report-head"><Icon name="doc" size={14} /><span>{b.title}</span><CopyBtn className="report-copy" label="Copy" text={(b.title || '') + '\n\n' + rows.map((r) => r[0] + ': ' + r[1]).join('\n')} /></div>
        <table className="report-table"><tbody>
          {rows.map((r, i) => (<tr key={i}><th>{r[0]}</th><td>{r[1]}</td></tr>))}
        </tbody></table>
      </div>
    );
  }
  return null;
}

function Thinking() {
  return (
    <div className="turn turn-assistant">
      <div className="avatar avatar-bot"><Icon name="shield" size={15} /></div>
      <div className="turn-body">
        <div className="thinking"><span /><span /><span /> <em>searching knowledge base…</em></div>
      </div>
    </div>
  );
}

function Suggestions({ items, onPick }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="suggestions">
      <div className="suggestions-label">SUGGESTED</div>
      <div className="suggestions-grid">
        {items.map((s) => (
          <button key={s.id} className="suggestion" onClick={() => onPick(s)}>
            <span className="suggestion-icon"><Icon name={s.icon} size={16} /></span>
            <span>{s.label}</span>
            <Icon name="chevron" size={14} className="suggestion-arrow" />
          </button>
        ))}
      </div>
    </div>
  );
}
