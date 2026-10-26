// lib/theme.js — dark / light / system theme handling for the Red Noir shell.
// The theme is stored in localStorage and applied as data-theme on <html>.

export const THEMES = ['dark', 'light', 'system'];
const KEY = 'sentinel-theme';

export function getStoredTheme() {
  try {
    const t = localStorage.getItem(KEY);
    return THEMES.includes(t) ? t : 'dark';
  } catch {
    return 'dark';
  }
}

export function storeTheme(theme) {
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    /* storage unavailable — session-only theme */
  }
}

/** Resolves 'system' to the OS preference at call time. */
export function resolveTheme(theme) {
  const t = theme || getStoredTheme();
  if (t !== 'system') return t;
  return typeof window !== 'undefined' && window.matchMedia
    && window.matchMedia('(prefers-color-scheme: light)').matches
    ? 'light'
    : 'dark';
}

/** Applies data-theme to <html> and returns the resolved theme. */
export function applyTheme(theme) {
  const resolved = resolveTheme(theme);
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.setAttribute('data-theme', resolved);
  }
  return resolved;
}

export function cycleTheme(theme) {
  const next = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
  storeTheme(next);
  applyTheme(next);
  return next;
}

/** Subscribes to OS scheme changes; returns a cleanup fn (for 'system'). */
export function watchSystemPreference(theme, onChange) {
  if (typeof window === 'undefined' || !window.matchMedia || theme !== 'system') {
    return () => {};
  }
  const mq = window.matchMedia('(prefers-color-scheme: light)');
  const handler = () => onChange(applyTheme('system'));
  mq.addEventListener('change', handler);
  return () => mq.removeEventListener('change', handler);
}
