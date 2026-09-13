exports.redactOutput = (text, secrets) => {
  for (const value of secrets) if (value) text = text.split(value).join('[REDACTED]');
  return text.replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[JWT REDACTED]');
};
exports.evidenceFixtures = row => {
  if (![row.article_revision_id, row.evidence_snapshot_id].every(v => typeof v === 'string' && v.trim())
      || !Number.isInteger(row.article_revision_number) || row.article_revision_number < 1
      || !Number.isInteger(row.source_span_start) || row.source_span_start < 0
      || !Number.isInteger(row.source_span_end) || row.source_span_end <= row.source_span_start) throw new Error('Invalid exact evidence metadata');
  return {E2E_EXPECTED_ARTICLE_REVISION_ID: row.article_revision_id, E2E_EXPECTED_REVISION_NUMBER: String(row.article_revision_number),
    E2E_EXPECTED_EVIDENCE_SNAPSHOT_ID: row.evidence_snapshot_id, E2E_EXPECTED_SOURCE_SPAN_START: String(row.source_span_start), E2E_EXPECTED_SOURCE_SPAN_END: String(row.source_span_end)};
};
exports.includeSnapshotEntry = (path, symlink) => !symlink && !path.split(/[\\/]/).some(part =>
  part.startsWith('.env') || ['.git', 'target', 'node_modules', 'dist', 'test-results', 'playwright-report'].includes(part)
  || /credential|storage.?state|secret|\.pem$|\.key$/i.test(part));
exports.childEnvironment = parent => {
  const env = {};
  for (const key of ['PATH', 'HOME', 'TMPDIR', 'JAVA_HOME']) if (parent[key]) env[key] = parent[key];
  env.DOCKER_HOST = 'unix:///Users/danilopalaciosaboia/.colima/sentinel-kms-test/docker.sock';
  env.TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE = '/var/run/docker.sock';
  return env;
};
exports.validateOptIn = env => {
  if (env.SENTINEL_REAL_STACK_E2E !== '1') throw new Error('SENTINEL_REAL_STACK_E2E=1 is required');
};
