// lib/export.js — conversation + article export helpers (Markdown).

export function downloadFile(filename, text, mime = 'text/markdown') {
  const blob = new Blob([text], { type: mime + ';charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function slug(text, max = 40) {
  return String(text || 'thread')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, max) || 'thread';
}

/** Renders one assistant message payload (blocks + citations) to Markdown. */
function blocksToMarkdown(msg) {
  const out = [];
  for (const b of msg.blocks || []) {
    if (b.type === 'text') out.push(b.text || '');
    else if (b.type === 'steps') out.push((b.items || []).map((it, i) => `${i + 1}. ${it}`).join('\n'));
    else if (b.type === 'callout') out.push(`> **${b.tone === 'critical' ? '⚠ CRITICAL' : '⚠ HIGH'} — ${b.title}**\n> ${b.text}`);
    else if (b.type === 'report') {
      out.push(`**${b.title}**\n\n${(b.rows || []).map(([k, v]) => `| ${k} | ${v} |`).join('\n')}`);
    }
  }
  const cites = msg.citations || [];
  if (cites.length) {
    out.push(`\n**Sources:** ${cites.map((c) => `[${c}]`).join(' ')}`);
  }
  return out.join('\n\n');
}

/**
 * Builds a Markdown transcript from the Assistant's message list.
 * User turns become "## ❯ question"; assistant turns become plain answers.
 */
export function conversationToMarkdown(messages, title) {
  const lines = [
    `# Sentinel KMS — ${title || 'Assistant transcript'}`,
    '',
    `> Exported ${new Date().toISOString().replace('T', ' ').slice(0, 16)} UTC`,
    '',
  ];
  for (const m of messages || []) {
    if (m.intro) {
      lines.push(`_${blocksToMarkdown(m)}_`, '');
      continue;
    }
    if (m.role === 'user') {
      lines.push(`## ❯ ${m.text}`, '');
    } else {
      lines.push(blocksToMarkdown(m), '');
    }
  }
  return lines.join('\n');
}

export function conversationFilename(title) {
  return `sentinel-${slug(title)}-${new Date().toISOString().slice(0, 10)}.md`;
}

/** Article detail → Markdown (KB export). */
export function articleToMarkdown(article) {
  const a = article || {};
  const sections = (a.body || []).map((s) => `## ${s.h}\n\n${s.p}`).join('\n\n');
  return [
    `# ${a.title || a.id}`,
    '',
    `- **ID:** ${a.id}`,
    `- **Severity:** ${a.severity}`,
    a.cvss ? `- **CVSS:** ${a.cvss}` : '',
    `- **Updated:** ${a.updated || ''}`,
    (a.tags || []).length ? `- **Tags:** ${a.tags.map((t) => `#${t}`).join(' ')}` : '',
    '',
    a.snippet || '',
    '',
    sections,
  ].filter(Boolean).join('\n');
}
