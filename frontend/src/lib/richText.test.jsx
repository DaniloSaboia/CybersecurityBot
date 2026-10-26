import { afterEach, describe, it, expect, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { renderInline, renderRichText } from './richText.jsx';

afterEach(cleanup);
const revision = '3df31b12-04a3-4b33-ad21-b0944b37fdaf';
const exact = (articleId = 'PB-START-007') => ({
  articleId, articleRevisionId: revision, revisionNumber: 1,
  evidenceSnapshotId: 'snapshot-1', sourceSpanStart: 0, sourceSpanEnd: 85,
});

describe('safe response references', () => {
  it('renders compact numbered article citations only from the message allowlist', () => {
    const onOpen = vi.fn();
    const { container } = render(<>{renderInline('**Rotate** keys per [PB-START-007] and [PB-START-016].', onOpen,
      { citations: ['PB-START-016', 'PB-START-007'] })}</>);
    expect(container.querySelector('strong').textContent).toBe('Rotate');
    const citation = screen.getByRole('button', { name: 'Open source 2: PB-START-007' });
    expect(citation.textContent).toBe('2');
    fireEvent.click(citation);
    expect(onOpen).toHaveBeenCalledWith('PB-START-007');
  });

  it('uses validated exact references when legacy citations are empty, without trusting the echoed UUID', () => {
    const onOpen = vi.fn();
    render(<>{renderInline(`See [PB-START-007:${revision}].`, onOpen,
      { citations: [], citationReferences: [exact(), exact()] })}</>);
    const citation = screen.getByRole('button', { name: 'Open source 1: PB-START-007' });
    expect(citation.title).toContain('not exact evidence');
    fireEvent.click(citation);
    expect(onOpen).toHaveBeenCalledWith('PB-START-007');
    expect(screen.queryByText(revision, { exact: false })).toBeNull();
  });

  it('never authorizes another article because it reuses a known revision UUID', () => {
    render(<>{renderInline(`Read [PB-START-999:${revision}] and [generaltext].`, vi.fn(),
      { citationReferences: [exact()] })}</>);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByRole('note').textContent).toBe('Unverified source');
    expect(screen.getByRole('note').getAttribute('aria-label')).toContain('PB-START-999');
    expect(screen.getByText('[generaltext]')).toBeTruthy();
  });

  it.each([
    { articleId: 'PB-START-007' },
    { ...exact(), sourceSpanEnd: 0 },
    { ...exact(), revisionNumber: 0 },
    { ...exact(), articleId: ' PB-START-007' },
  ])('rejects malformed exact reference metadata %#', (reference) => {
    render(<>{renderInline('[PB-START-007]', vi.fn(), { citationReferences: [reference] })}</>);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByRole('note')).toBeTruthy();
  });

  it('never derives citation authority from source-looking text or a Markdown link', () => {
    render(<>{renderInline(`[[evidence:PB-START-007|${revision}|snapshot-1|0-85]] [PB-START-007](https://example.com)`, vi.fn())}</>);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getAllByRole('note')).toHaveLength(2);
  });
});

describe('semantic response Markdown', () => {
  it('renders the saved API-key response with real headings and continuous steps across blank lines', () => {
    const saved = `# Revoking Exposed API Keys\n\n## Summary\nUse the provider console.\n\n## Immediate actions\n\n1. **Identify** the exposed credential. [PB-START-007:${revision}]\n\n2. **Revoke** it using the known provider console.\n\n3. Verify applications use its replacement.\n\n## Verify recovery\nCheck the audit trail.`;
    const { container } = render(<>{renderRichText(saved, vi.fn(), { citationReferences: [exact()] })}</>);
    expect(screen.getByRole('heading', { level: 2, name: 'Revoking Exposed API Keys' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 3, name: 'Summary' })).toBeTruthy();
    expect(screen.getAllByRole('list')).toHaveLength(1);
    const items = [...container.querySelectorAll('ol > li')];
    expect(items.map((item) => item.value)).toEqual([1, 2, 3]);
    expect([...container.querySelectorAll('.step-n')].map((node) => node.textContent)).toEqual(['1.', '2.', '3.']);
    expect(container.textContent).not.toContain('##');
    expect(container.textContent).not.toContain(revision);
    expect(screen.getByRole('button', { name: 'Open source 1: PB-START-007' })).toBeTruthy();
  });

  it('preserves a non-one starting number and standard repeated-one Markdown numbering', () => {
    const { container } = render(<>{renderRichText('4. Four\n\n5. Five\n\n## Next procedure\n1. First\n\n1. Second')}</>);
    const lists = [...container.querySelectorAll('ol')];
    expect(lists.map((list) => list.start)).toEqual([4, 1]);
    expect([...lists[0].children].map((item) => item.value)).toEqual([4, 5]);
    expect([...lists[1].children].map((item) => item.value)).toEqual([1, 2]);
  });

  it('groups soft paragraph lines and uses a real unordered list for bullets', () => {
    const { container } = render(<>{renderRichText('First line\ncontinues here.\n\n- Evidence\n\n- Recovery')}</>);
    expect(container.querySelector('p').textContent).toBe('First line continues here.');
    expect(container.querySelector('ul').children).toHaveLength(2);
    expect(container.querySelector('ol')).toBeNull();
  });

  it('renders tables, quotations, and fenced code without interpreting code as citations or markup', () => {
    const value = '| Check | Result |\n| --- | --- |\n| **Token** | `revoked` |\n\n> Preserve the audit trail.\n\n```sh\n# literal heading\necho "[PB-START-007]"\n<script>alert(1)</script>\n```';
    const { container } = render(<>{renderRichText(value, vi.fn(), { citations: ['PB-START-007'] })}</>);
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getAllByRole('columnheader').map((node) => node.textContent)).toEqual(['Check', 'Result']);
    expect(container.querySelector('blockquote').textContent).toBe('Preserve the audit trail.');
    expect(container.querySelector('pre code').textContent).toContain('# literal heading');
    expect(container.querySelector('pre code').textContent).toContain('<script>alert(1)</script>');
    expect(container.querySelector('script')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('keeps raw HTML inert and permits only safe web links without loading images', () => {
    const value = '<img src=x onerror=alert(1)> <script>alert(1)</script>\n\n[Guide](https://example.com/help) [Bad](javascript:alert) [Data](data:text/html,evil) [Protocol](//example.com) ![Tracking](https://example.com/track.png)';
    const { container } = render(<>{renderRichText(value)}</>);
    expect(container.querySelector('img, script, iframe')).toBeNull();
    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Guide' }).href).toBe('https://example.com/help');
    expect(screen.getByRole('link', { name: 'Guide' }).rel).toBe('noopener noreferrer');
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('handles incomplete fences during streaming and null input without throwing', () => {
    expect(renderRichText(null)).toHaveLength(0);
    expect(renderRichText(undefined)).toHaveLength(0);
    const { container } = render(<>{renderRichText('```text\nStill arriving\n[PB-START-007]')}</>);
    expect(container.querySelector('pre').textContent).toContain('Still arriving');
    expect(screen.queryByRole('button')).toBeNull();
  });
});
