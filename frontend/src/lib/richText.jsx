// lib/richText.jsx — markdown-lite renderer for assistant answers.
// Extracted from Assistant.jsx so it can be unit-tested and reused.

// supports **bold** and [ARTICLE-ID] inline citation chips (always clickable)
export function renderInline(text, onOpen) {
  const s = text == null ? '' : String(text);
  const parts = s.split(/(\*\*[^*]+\*\*|\[[A-Z0-9]+(?:-[A-Z0-9]+)*\])/g);
  return parts.map((p, i) => {
    if (p.startsWith('**') && p.endsWith('**')) return <strong key={i}>{p.slice(2, -2)}</strong>;
    if (p.startsWith('[') && p.endsWith(']')) {
      const id = p.slice(1, -1);
      return (
        <button key={i} className="inline-cite" onClick={() => onOpen && onOpen(id)} title={id}>
          {id}
        </button>
      );
    }
    return <span key={i}>{p}</span>;
  });
}

// Renders answer text as scannable Markdown-lite: numbered / bulleted lines become
// styled step lists, other non-empty lines become paragraphs, and **bold** + [ID]
// citation chips are honored inline.
export function renderRichText(text, onOpen) {
  const lines = String(text == null ? '' : text).split('\n');
  const out = [];
  let list = null; // { ordered: boolean, items: string[] }
  let k = 0;
  const flush = () => {
    if (!list) return;
    const ordered = list.ordered;
    out.push(
      <ol className="msg-steps" key={'l' + k++}>
        {list.items.map((it, idx) => (
          <li key={idx}>
            <span className="step-n">{ordered ? String(idx + 1).padStart(2, '0') : '•'}</span>
            <span>{renderInline(it, onOpen)}</span>
          </li>
        ))}
      </ol>
    );
    list = null;
  };
  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    const numM = line.match(/^\s*\d+[.)]\s+(.*)$/);
    const bulM = line.match(/^\s*[-*]\s+(.*)$/);
    if (numM) {
      if (!list || !list.ordered) { flush(); list = { ordered: true, items: [] }; }
      list.items.push(numM[1]);
    } else if (bulM) {
      if (!list || list.ordered) { flush(); list = { ordered: false, items: [] }; }
      list.items.push(bulM[1]);
    } else {
      flush();
      if (line.trim()) out.push(<p className="msg-text" key={'p' + k++}>{renderInline(line, onOpen)}</p>);
    }
  }
  flush();
  return out;
}
