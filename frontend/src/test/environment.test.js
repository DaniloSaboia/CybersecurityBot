// environment.test.js — every frontend unit test needs a real same-origin DOM.

import { expect, it } from 'vitest';

it('provides a same-origin localStorage implementation to all frontend tests', () => {
  expect(window.location.origin).toBe('http://localhost');
  expect(typeof window.localStorage.getItem).toBe('function');

  const key = 'sentinel-test-environment';
  window.localStorage.setItem(key, 'ready');
  expect(window.localStorage.getItem(key)).toBe('ready');
  window.localStorage.removeItem(key);
});
