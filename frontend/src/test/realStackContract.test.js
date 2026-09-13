// The opt-in real-stack guard must be testable without credentials or .env.

import { describe, expect, it } from 'vitest';
import {
  assertRealStackBuildEnvironment,
  assertRealStackFixtures,
  REAL_STACK_BUILD_VARIABLES,
  REAL_STACK_REQUIRED_FIXTURES,
} from '../../e2e/real-stack-contract.js';

function environment(overrides = {}) {
  return {
    SENTINEL_REAL_STACK_E2E: '1',
    VITE_SUPABASE_URL: 'http://disposable-auth.invalid',
    VITE_SUPABASE_ANON_KEY: 'public-test-anon-key',
    VITE_API_PROXY_TARGET: 'http://disposable-backend.invalid',
    E2E_UPLOAD_PATH: '/tmp/disposable-upload.pdf',
    E2E_APPROVED_EVIDENCE_QUERY: 'fixture approved article query',
    E2E_EXPECTED_ANSWER_FRAGMENT: 'fixture answer',
    E2E_EXPECTED_ARTICLE_ID: 'article-fixture',
    E2E_EXPECTED_ARTICLE_TITLE: 'Fixture Article',
    E2E_EXPECTED_ARTICLE_REVISION_ID: 'revision-fixture',
    E2E_EXPECTED_REVISION_NUMBER: '7',
    E2E_EXPECTED_EVIDENCE_SNAPSHOT_ID: 'snapshot-fixture',
    E2E_EXPECTED_SOURCE_SPAN_START: '0',
    E2E_EXPECTED_SOURCE_SPAN_END: '12',
    E2E_EXPECTED_EVIDENCE_FRAGMENT: 'immutable slice',
    E2E_EXPECTED_SOURCE_VERSION: 'fixture-v7',
    E2E_WATCHLIST_LABEL: 'Fixture watchlist',
    ...overrides,
  };
}

describe('opt-in real-stack environment contract', () => {
  it('accepts only a nonblank injected same-origin build seam', () => {
    expect(() => assertRealStackBuildEnvironment(environment())).not.toThrow();
    expect(() => assertRealStackBuildEnvironment(environment({ VITE_API_BASE: '' })))
      .toThrow(/VITE_API_BASE.*unset/i);
    expect(() => assertRealStackBuildEnvironment(environment({ VITE_API_PROXY_TARGET: '  ' })))
      .toThrow(new RegExp(REAL_STACK_BUILD_VARIABLES[2]));
  });

  it('accepts a valid exact evidence tuple and rejects malformed spans without values', () => {
    expect(() => assertRealStackFixtures(environment())).not.toThrow();
    expect(() => assertRealStackFixtures(environment({ E2E_EXPECTED_SOURCE_SPAN_END: '0' })))
      .toThrow(/E2E_EXPECTED_SOURCE_SPAN_END/);
    expect(() => assertRealStackFixtures(environment({ E2E_EXPECTED_ARTICLE_ID: '  ' })))
      .toThrow(new RegExp(REAL_STACK_REQUIRED_FIXTURES[3]));
  });
});
