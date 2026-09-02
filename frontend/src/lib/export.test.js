// @vitest-environment jsdom
// lib/export.test.js — Markdown export builders.

import { describe, expect, it, vi } from 'vitest';
import { conversationToMarkdown, conversationFilename, articleToMarkdown, downloadFile } from './export.js';

describe('conversationToMarkdown', () => {
  const messages = [
    { role: 'user', text: 'What is CVE-2024-21413?' },
    {
      role: 'assistant',
      blocks: [
        { type: 'text', text: 'An Outlook RCE [CVE-2024-21413].' },
        { type: 'steps', items: ['**Patch** Outlook', '**Block** moniker links'] },
        { type: 'callout', tone: 'critical', title: 'Act now', text: 'Exploited in the wild' },
        { type: 'report', title: 'Summary', rows: [['Severity', 'critical'], ['CVSS', '9.8']] },
      ],
      citations: ['CVE-2024-21413'],
    },
  ];

  it('renders user turns as headings', () => {
    const md = conversationToMarkdown(messages, 'CVE thread');
    expect(md).toContain('# Sentinel KMS — CVE thread');
    expect(md).toContain('## ❯ What is CVE-2024-21413?');
  });

  it('renders every block type and the source list', () => {
    const md = conversationToMarkdown(messages);
    expect(md).toContain('An Outlook RCE [CVE-2024-21413].');
    expect(md).toContain('1. **Patch** Outlook');
    expect(md).toContain('> **⚠ CRITICAL — Act now**');
    expect(md).toContain('| Severity | critical |');
    expect(md).toContain('**Sources:** [CVE-2024-21413]');
  });

  it('slugs a safe filename with a date', () => {
    const name = conversationFilename('Akira Ransomware / SonicWall!');
    expect(name).toMatch(/^sentinel-akira-ransomware-sonicwall-\d{4}-\d{2}-\d{2}\.md$/);
  });
});

describe('articleToMarkdown', () => {
  it('renders metadata, tags and body sections', () => {
    const md = articleToMarkdown({
      id: 'TI-2291',
      title: 'APT29 phishing',
      severity: 'critical',
      cvss: '9.1',
      updated: '2026-08-01',
      tags: ['APT', 'phishing'],
      snippet: 'Device-code flow lures.',
      body: [{ h: 'TTPs', p: 'Spearphishing with OAuth prompts.' }],
    });
    expect(md).toContain('# APT29 phishing');
    expect(md).toContain('- **ID:** TI-2291');
    expect(md).toContain('- **CVSS:** 9.1');
    expect(md).toContain('#APT #phishing');
    expect(md).toContain('## TTPs');
    expect(md).toContain('Spearphishing with OAuth prompts.');
  });
});

describe('downloadFile', () => {
  it('creates and clicks an anchor with a blob URL', () => {
    const url = 'blob:mock-url';
    URL.createObjectURL = vi.fn(() => url);
    URL.revokeObjectURL = vi.fn();
    const anchor = { click: vi.fn(), remove: vi.fn(), href: '', download: '' };
    vi.spyOn(document, 'createElement').mockReturnValue(anchor);
    vi.spyOn(document.body, 'appendChild').mockImplementation(() => anchor);

    downloadFile('t.md', '# hi');

    expect(anchor.click).toHaveBeenCalled();
    expect(anchor.download).toBe('t.md');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(url);
  });
});
