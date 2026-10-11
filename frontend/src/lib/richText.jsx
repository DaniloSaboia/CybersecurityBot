// Safe Markdown for assistant answers and KB passages. Source text never grants citation authority.
import { createElement } from 'react';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STRUCTURED_REFERENCE = /^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+(?:[:|][^\s]+)?$/;

function citationOrder(options = {}) {
  const ids = new Map();
  const add = (id) => {
    if (typeof id === 'string' && id.trim() && !ids.has(id)) ids.set(id, ids.size + 1);
  };
  (Array.isArray(options.citations) ? options.citations : []).forEach(add);
  (Array.isArray(options.citationReferences) ? options.citationReferences : []).forEach((reference) => {
    // These must come from the server response, never from parsed answer text.
    if (reference && ['articleId', 'articleRevisionId', 'evidenceSnapshotId'].every((field) =>
      typeof reference[field] === 'string' && reference[field].trim() && reference[field] === reference[field].trim())
      && Number.isSafeInteger(reference.revisionNumber) && reference.revisionNumber > 0
      && Number.isSafeInteger(reference.sourceSpanStart) && reference.sourceSpanStart >= 0
      && Number.isSafeInteger(reference.sourceSpanEnd) && reference.sourceSpanEnd > reference.sourceSpanStart) add(reference.articleId);
  });
  return ids;
}

function referenceNode(token, ids, onOpen, key) {
  const content = token.slice(1, -1);
  let id = content;
  const lastColon = content.lastIndexOf(':');
  const echoedIdentity = lastColon > 0 && UUID.test(content.slice(lastColon + 1));
  if (!ids.has(id) && echoedIdentity) id = content.slice(0, lastColon);
  const ordinal = ids.get(id);
  if (ordinal) {
    return <button type="button" key={key} className="inline-cite" data-article-id={id}
      onClick={() => onOpen?.(id)} aria-label={`Open source ${ordinal}: ${id}`}
      title={`Source ${ordinal}: ${id}${echoedIdentity ? ' — article link; the echoed reference is not exact evidence' : ''}`}>
      {ordinal}
    </button>;
  }
  if (echoedIdentity || STRUCTURED_REFERENCE.test(content) || token.startsWith('[[evidence:')) {
    return <span key={key} className="inline-cite-unverified" role="note"
      aria-label={`Unverified source reference: ${token}`} title={`Unverified source reference: ${token}`}>Unverified source</span>;
  }
  return <span key={key}>{token}</span>;
}

function safeHref(href) {
  if (!href || /[\u0000-\u0020\u007f]/.test(href)) return null;
  try {
    const url = new URL(href);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

/** options contains only citations/references delivered with this server-resolved message. */
export function renderInline(text, onOpen, options = {}, depth = 0) {
  const value = text == null ? '' : String(text);
  if (depth > 4) return [value];
  const ids = citationOrder(options);
  const pattern = /\[\[evidence:[^\]\n]+\]\]|`{1,2}[^`\n]+`{1,2}|\*\*[^*\n]+\*\*|__[^_\n]+__|~~[^~\n]+~~|\*[^*\n]+\*|(?<!\w)_[^_\n]+_(?!\w)|!?\[[^\]\n]+\]\([^\)\n]*\)|<https?:\/\/[^>\s]+>|\[[^\]\n]+\]/g;
  const out = [];
  let end = 0;
  let key = 0;
  for (const match of value.matchAll(pattern)) {
    if (match.index > end) out.push(<span key={key++}>{value.slice(end, match.index)}</span>);
    const token = match[0];
    const nested = (inner) => renderInline(inner, onOpen, options, depth + 1);
    if (token.startsWith('`')) {
      out.push(<code className="msg-inline-code" key={key++}>{token.replace(/^`{1,2}|`{1,2}$/g, '')}</code>);
    } else if (token.startsWith('**') || token.startsWith('__')) {
      out.push(<strong key={key++}>{nested(token.slice(2, -2))}</strong>);
    } else if (token.startsWith('~~')) {
      out.push(<del key={key++}>{nested(token.slice(2, -2))}</del>);
    } else if (token.startsWith('*') || token.startsWith('_')) {
      out.push(<em key={key++}>{nested(token.slice(1, -1))}</em>);
    } else {
      const link = token.match(/^(!?)\[([^\]]+)\]\((.*)\)$/);
      if (link) {
        const label = link[2];
        const href = safeHref(link[3].trim());
        if (ids.has(label) || STRUCTURED_REFERENCE.test(label)) out.push(referenceNode(`[${label}]`, ids, onOpen, key++));
        else if (href && !link[1]) out.push(<a className="msg-link" key={key++} href={href} target="_blank" rel="noopener noreferrer">{nested(label)}</a>);
        // Do not load remote images or activate unsafe/relative URL schemes.
        else out.push(<span key={key++}>{label}</span>);
      } else if (token.startsWith('<')) {
        const href = safeHref(token.slice(1, -1));
        out.push(href
          ? <a className="msg-link" key={key++} href={href} target="_blank" rel="noopener noreferrer">{token.slice(1, -1)}</a>
          : <span key={key++}>{token}</span>);
      } else out.push(referenceNode(token, ids, onOpen, key++));
    }
    end = match.index + token.length;
  }
  if (end < value.length || out.length === 0) out.push(<span key={key++}>{value.slice(end)}</span>);
  return out;
}

const listItem = (line) => line.match(/^(\s*)(?:(\d+)[.)]|([-+*]))\s+(.+)$/);
const heading = (line) => line.match(/^\s{0,3}(#{1,6})\s+(.+)$/);
const fence = (line) => line.match(/^\s{0,3}(`{3,}|~{3,})\s*([\w+-]*)\s*$/);
const rule = (line) => /^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line);
const quote = (line) => /^\s{0,3}>\s?/.test(line);

function tableCells(line) {
  const input = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  const cells = [];
  let value = '';
  let inCode = false;
  for (let i = 0; i < input.length; i++) {
    if (input[i] === '\\' && input[i + 1] === '|') { value += '|'; i++; }
    else if (input[i] === '`') { inCode = !inCode; value += input[i]; }
    else if (input[i] === '|' && !inCode) { cells.push(value.trim()); value = ''; }
    else value += input[i];
  }
  cells.push(value.trim());
  return cells;
}

function tableStart(lines, index) {
  return index + 1 < lines.length && lines[index].includes('|')
    && tableCells(lines[index + 1]).length === tableCells(lines[index]).length
    && tableCells(lines[index + 1]).every((cell) => /^:?-{3,}:?$/.test(cell));
}

/** Semantic Markdown blocks. Blank lines inside a list never reset numbering. */
export function renderRichText(text, onOpen, options = {}, depth = 0) {
  if (depth > 12) return [<p className="msg-text" key="text">{String(text ?? '')}</p>];
  const lines = String(text == null ? '' : text).replace(/\r\n?/g, '\n').split('\n');
  const out = [];
  let i = 0;
  const inline = (value) => renderInline(value, onOpen, options);
  const startsBlock = (index) => heading(lines[index]) || fence(lines[index]) || rule(lines[index])
    || quote(lines[index]) || listItem(lines[index]) || tableStart(lines, index);
  while (i < lines.length) {
    if (!lines[i].trim()) { i++; continue; }
    const codeFence = fence(lines[i]);
    if (codeFence) {
      const content = [];
      const character = codeFence[1][0];
      const minimum = codeFence[1].length;
      i++;
      while (i < lines.length && !(lines[i].trim().length >= minimum
        && [...lines[i].trim()].every((c) => c === character))) content.push(lines[i++]);
      if (i < lines.length) i++;
      out.push(<div className="msg-code-block" key={out.length}>
        <div className="msg-code-label">{codeFence[2] || 'Code'}</div>
        <pre className="msg-code"><code>{content.join('\n')}</code></pre>
      </div>);
      continue;
    }
    const title = heading(lines[i]);
    if (title) {
      const level = title[1].length;
      out.push(createElement(`h${Math.min(level + 1, 6)}`,
        { key: out.length, className: `msg-heading msg-heading-${level}` }, inline(title[2].replace(/\s+#+\s*$/, '').trim())));
      i++; continue;
    }
    if (rule(lines[i])) { out.push(<hr className="msg-rule" key={out.length} />); i++; continue; }
    if (quote(lines[i])) {
      const content = [];
      while (i < lines.length && quote(lines[i])) content.push(lines[i++].replace(/^\s{0,3}>\s?/, ''));
      out.push(<blockquote className="msg-quote" key={out.length}>{renderRichText(content.join('\n'), onOpen, options, depth + 1)}</blockquote>);
      continue;
    }
    if (tableStart(lines, i)) {
      const headers = tableCells(lines[i]);
      const rows = [];
      i += 2;
      while (i < lines.length && lines[i].trim() && lines[i].includes('|') && !heading(lines[i])) rows.push(tableCells(lines[i++]));
      out.push(<div className="msg-table-wrap" key={out.length} role="region" aria-label="Response table" tabIndex={0}>
        <table className="msg-table"><thead><tr>{headers.map((cell, column) => <th key={column} scope="col">{inline(cell)}</th>)}</tr></thead>
          <tbody>{rows.map((row, index) => <tr key={index}>{headers.map((_, column) => <td key={column}>{inline(row[column] || '')}</td>)}</tr>)}</tbody>
        </table>
      </div>);
      continue;
    }
    const firstItem = listItem(lines[i]);
    if (firstItem) {
      const ordered = Boolean(firstItem[2]);
      const indent = firstItem[1].length;
      const items = [];
      let previous = Number(firstItem[2] || 1) - 1;
      while (i < lines.length) {
        const item = listItem(lines[i]);
        if (!item || Boolean(item[2]) !== ordered || item[1].length !== indent) break;
        const number = ordered ? Math.max(Number(item[2]), previous + 1) : null;
        previous = number;
        const content = [item[4]];
        i++;
        while (i < lines.length) {
          if (!lines[i].trim()) {
            let next = i + 1;
            while (next < lines.length && !lines[next].trim()) next++;
            const following = next < lines.length ? listItem(lines[next]) : null;
            if (following && following[1].length === indent && Boolean(following[2]) === ordered) { i = next; break; }
            if (next < lines.length && lines[next].match(/^\s*/)[0].length > indent) { content.push(''); i++; continue; }
            break;
          }
          const nextItem = listItem(lines[i]);
          if (nextItem && nextItem[1].length <= indent) break;
          const leading = lines[i].match(/^\s*/)[0].length;
          if (leading > indent) content.push(lines[i++].slice(Math.min(leading, indent + 2)));
          else if (!startsBlock(i)) content.push(lines[i++]);
          else break;
        }
        items.push({ number, content });
      }
      const children = items.map((item, index) => <li key={index} value={ordered ? item.number : undefined}>
        <span className="step-n" aria-hidden="true">{ordered ? `${item.number}.` : '•'}</span>
        <div className="step-content">{renderRichText(item.content.join('\n'), onOpen, options, depth + 1)}</div>
      </li>);
      out.push(ordered
        ? <ol className="msg-steps" key={out.length} start={items[0].number}>{children}</ol>
        : <ul className="msg-steps msg-bullets" key={out.length}>{children}</ul>);
      continue;
    }
    const paragraph = [lines[i++].trim()];
    while (i < lines.length && lines[i].trim() && !startsBlock(i)) paragraph.push(lines[i++].trim());
    out.push(<p className="msg-text" key={out.length}>{inline(paragraph.join(' '))}</p>);
  }
  return out;
}
