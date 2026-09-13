import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);

test('child environment is allowlisted, Docker is pinned, injected secrets never inherited', () => {
  assert.equal(typeof safety?.childEnvironment, 'function');
  const parent = new Proxy({ PATH: '/bin', HOME: '/tmp/test-home' }, {
    get(target, name) { if (!['PATH', 'HOME', 'TMPDIR', 'JAVA_HOME'].includes(name)) throw new Error('unauthorized env read'); return target[name]; },
    ownKeys() { throw new Error('must not enumerate ambient environment'); },
  });
  const env = safety.childEnvironment(parent);
  assert.equal(env.DOCKER_HOST, 'unix:///Users/danilopalaciosaboia/.colima/sentinel-kms-test/docker.sock');
  assert.equal(env.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE, '/var/run/docker.sock');
  assert.equal(env.HOME, '/tmp/test-home');
  assert.equal(env.E2E_PASSWORD, undefined);
});
test('snapshot excludes dotenv files, credentials, generated state, and symlinks', () => {
  assert.equal(typeof safety?.includeSnapshotEntry, 'function');
  for (const path of ['.env', '.env.local', 'frontend/.env.production', '.git/config', 'backend/target/x', 'frontend/node_modules/x', 'frontend/playwright-report/x', 'frontend/test-results/x', 'credentials.json', 'storageState.json']) {
    assert.equal(safety.includeSnapshotEntry(path, false), false, path);
  }
  assert.equal(safety.includeSnapshotEntry('frontend/src/lib/api.js', true), false);
  assert.equal(safety.includeSnapshotEntry('frontend/src/lib/api.js', false), true);
  assert.equal(safety.includeSnapshotEntry('supabase/migrations/70000.sql', false), true);
});
test('CLI missing opt-in fails before stack creation without echoing injected credentials', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./disposable-real-stack.mjs', import.meta.url))], {
    env: {PATH: '/nonexistent', E2E_PASSWORD: 'must-not-appear', DOCKER_HOST: 'unix:///Users/danilopalaciosaboia/.colima/sentinel-kms-test/docker.sock', TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: '/var/run/docker.sock'}, encoding: 'utf8',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SENTINEL_REAL_STACK_E2E=1 is required/);
  assert.doesNotMatch(result.stderr + result.stdout, /must-not-appear|Snapshot:/);
});
test('exact-evidence fixture mapping fails closed on missing identity or spans', () => {
  assert.equal(typeof safety?.evidenceFixtures, 'function');
  assert.throws(() => safety.evidenceFixtures({}), /evidence metadata/);
  const row = {article_revision_id: 'rev:exact', article_revision_number: 1, evidence_snapshot_id: 'evidence:exact', source_span_start: 0, source_span_end: 80};
  const env = safety.evidenceFixtures(row);
  assert.equal(env.E2E_EXPECTED_REVISION_NUMBER, '1');
  assert.equal(env.E2E_EXPECTED_SOURCE_SPAN_START, '0');
  assert.equal(env.E2E_EXPECTED_ARTICLE_REVISION_ID, 'rev:exact');
  assert.throws(() => safety.evidenceFixtures({...row, source_span_end: -1}), /evidence metadata/);
});
test('output boundary redacts generated secrets and JWTs before artifact writing', () => {
  assert.equal(typeof safety?.redactOutput, 'function');
  const text = 'password=throwaway-value bearer=eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature';
  const clean = safety.redactOutput(text, ['throwaway-value']);
  assert.doesNotMatch(clean, /throwaway-value|eyJ/);
  assert.match(clean, /REDACTED/);
});
let safety;
try { safety = require('./runner-safety.cjs'); } catch (e) { if (e.code !== 'MODULE_NOT_FOUND') throw e; }
test('requires explicit disposable-stack opt-in before any side effects', () => {
  assert.equal(typeof safety?.validateOptIn, 'function', 'opt-in boundary must exist');
  assert.throws(() => safety.validateOptIn({}), /SENTINEL_REAL_STACK_E2E/);
  assert.throws(() => safety.validateOptIn({ SENTINEL_REAL_STACK_E2E: 'true' }), /SENTINEL_REAL_STACK_E2E/);
  assert.doesNotThrow(() => safety.validateOptIn({ SENTINEL_REAL_STACK_E2E: '1' }));
});
