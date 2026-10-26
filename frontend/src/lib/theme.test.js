// @vitest-environment jsdom
// lib/theme.test.js — theme storage, resolution and application.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getStoredTheme, storeTheme, resolveTheme, applyTheme, cycleTheme, THEMES } from './theme.js';

// jsdom does not implement matchMedia — provide a controllable stub.
function stubMatchMedia(prefersLight) {
  const mq = {
    matches: prefersLight,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  };
  window.matchMedia = vi.fn(() => mq);
  return mq;
}

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    stubMatchMedia(false);
  });

  it('defaults to dark when nothing is stored', () => {
    expect(getStoredTheme()).toBe('dark');
  });

  it('stores and reads back a theme', () => {
    storeTheme('light');
    expect(getStoredTheme()).toBe('light');
  });

  it('ignores unknown stored values', () => {
    localStorage.setItem('sentinel-theme', 'neon-noir');
    expect(getStoredTheme()).toBe('dark');
  });

  it('resolves system preference via matchMedia', () => {
    stubMatchMedia(true);
    expect(resolveTheme('system')).toBe('light');
    expect(resolveTheme('dark')).toBe('dark'); // explicit theme ignores the OS
  });

  it('applies the resolved theme to <html>', () => {
    expect(applyTheme('light')).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });

  it('cycles dark -> light -> system -> dark and persists', () => {
    expect(cycleTheme('dark')).toBe('light');
    expect(cycleTheme('light')).toBe('system');
    expect(cycleTheme('system')).toBe('dark');
    expect(localStorage.getItem('sentinel-theme')).toBe('dark');
    expect(THEMES).toEqual(['dark', 'light', 'system']);
  });
});
