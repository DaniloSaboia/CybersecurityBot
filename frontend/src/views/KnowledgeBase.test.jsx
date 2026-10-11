import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import KnowledgeBase from './KnowledgeBase.jsx';

const hooks = vi.hoisted(() => ({ articles: vi.fn(), article: vi.fn() }));
vi.mock('../lib/queries.js', () => ({
  useArticles: hooks.articles,
  useArticle: hooks.article,
  useCategories: () => ({ data: [{ id: 'playbooks', label: 'Playbooks & Runbooks', count: 16 }], isPending: false, isError: false }),
  useArticleIndex: () => ({ index: new Map() }),
}));

const playbook = {
  id: 'PB-START-001', title: 'Phishing response', category: 'playbooks', severity: 'high',
  source: 'CISA', updated: '2026-09-29', tags: ['phishing'],
  body: [
    { h: 'First actions', p: '1. Report the message.\n2. Preserve the email headers.' },
    { h: 'Evidence to preserve', p: 'Do not open https://suspicious.example/attachment' },
    { h: 'Authoritative sources', p: 'CISA phishing guidance — https://www.cisa.gov/secure-our-world/recognize-and-report-phishing\nUnsafe reference — javascript:alert(1)\nMisleading reference — https://cisa.gov@malicious.example/' },
  ],
};

describe('Knowledge Base response playbooks', () => {
  beforeEach(() => {
    hooks.articles.mockReset().mockReturnValue({ data: { items: [], total: 0 }, isSuccess: true });
    hooks.article.mockReset().mockReturnValue({ data: playbook, isSuccess: true });
  });
  afterEach(cleanup);

  it('finds playbooks by symptom and clears severity filters that could hide the relevant guidance', () => {
    render(<KnowledgeBase openId={null} setOpenId={vi.fn()} onAsk={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'critical', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: 'Encrypted files', exact: true }));
    expect(hooks.articles).toHaveBeenLastCalledWith({ q: 'ransomware', category: 'playbooks', severity: 'all', sort: 'updated' });
    expect(screen.getByRole('button', { name: 'Encrypted files' }).getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Browse all playbooks' }));
    expect(hooks.articles).toHaveBeenLastCalledWith({ q: '', category: 'playbooks', severity: 'all', sort: 'updated' });
  });

  it('renders scannable actions and safe external source links while leaving suspicious URLs as text', () => {
    render(<KnowledgeBase openId={playbook.id} setOpenId={vi.fn()} onAsk={vi.fn()} />);
    const section = screen.getByRole('heading', { name: 'First actions' }).parentElement;
    expect(within(section).getAllByRole('listitem')).toHaveLength(2);
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe('https://www.cisa.gov/secure-our-world/recognize-and-report-phishing');
    expect(links[0].getAttribute('rel')).toBe('noopener noreferrer');
    expect(links[0].textContent).toContain('www.cisa.gov');
    expect(screen.getByText('Do not open https://suspicious.example/attachment')).toBeTruthy();
    expect(screen.getByText('Unsafe reference — javascript:alert(1)')).toBeTruthy();
  });

  it('starts guided help with the specific playbook and requests the missing incident context', () => {
    const onAsk = vi.fn();
    render(<KnowledgeBase openId={playbook.id} setOpenId={vi.fn()} onAsk={onAsk} />);
    fireEvent.click(screen.getByRole('button', { name: 'Help me use this playbook' }));
    expect(onAsk).toHaveBeenCalledWith(expect.stringContaining('PB-START-001: Phishing response'));
    expect(onAsk).toHaveBeenCalledWith(expect.stringContaining('what I have already done'));
    expect(onAsk).toHaveBeenCalledWith(expect.stringContaining('Do not assume that I have administrator access'));
  });
});
