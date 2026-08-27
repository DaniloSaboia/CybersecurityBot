// lib/richText.test.jsx — unit tests for the markdown-lite answer renderer.

import { describe, it, expect, vi } from 'vitest';
import { renderInline, renderRichText } from './richText.jsx';

describe('renderInline', () => {
  it('splits bold and citation tokens', () => {
    const out = renderInline('**Rotate** keys per [TI-2291] now');
    expect(out).toHaveLength(5); // ['', '**Rotate**', ' keys per ', '[TI-2291]', ' now']
    expect(out[0].props.children).toBe('');
    expect(out[1].type).toBe('strong'); // **Rotate** minus markers
    expect(out[1].props.children).toBe('Rotate');
    expect(out[3].type).toBe('button'); // [TI-2291] chip
    expect(out[3].props.children).toBe('TI-2291');
    expect(out[3].props.className).toBe('inline-cite');
  });

  it('invokes onOpen with the article id when the chip is clicked', () => {
    const onOpen = vi.fn();
    const chip = renderInline('see [CVE-2024-21413]', onOpen)[1];
    chip.props.onClick();
    expect(onOpen).toHaveBeenCalledWith('CVE-2024-21413');
  });

  it('leaves plain text untouched', () => {
    const out = renderInline('plain text, no markup');
    expect(out).toHaveLength(1);
    expect(out[0].props.children).toBe('plain text, no markup');
  });
});

describe('renderRichText', () => {
  it('renders paragraphs and ordered steps separately', () => {
    const out = renderRichText('Intro line.\n\n1. **Revoke** sessions\n2. Rotate keys');
    expect(out[0].type).toBe('p');
    const list = out[1];
    expect(list.type).toBe('ol');
    expect(list.props.children).toHaveLength(2);
    const firstItem = list.props.children[0];
    expect(firstItem.props.children[0].props.children).toBe('01'); // zero-padded step number
    // bold marker honored inside the step: [step-n span, content span] -> [text, strong, text]
    const inlineParts = firstItem.props.children[1].props.children;
    expect(inlineParts[1].type).toBe('strong');
    expect(inlineParts[1].props.children).toBe('Revoke');
  });

  it('groups bulleted lines into an unordered list', () => {
    const out = renderRichText('- one\n- two');
    const list = out[0];
    expect(list.type).toBe('ol');
    expect(list.props.children[0].props.children[0].props.children).toBe('•');
  });

  it('drops blank lines', () => {
    const out = renderRichText('a\n\n\n\nb');
    expect(out).toHaveLength(2);
  });

  it('is safe on null/undefined input', () => {
    expect(renderRichText(null)).toHaveLength(0);
    expect(renderRichText(undefined)).toHaveLength(0);
  });
});
