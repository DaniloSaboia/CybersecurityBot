// Shared, non-secret contract for the opt-in disposable real-stack suite.
// It deliberately reads process.env only. In particular, it never loads .env
// files or prints supplied values.

export const REAL_STACK_BUILD_VARIABLES = [
  'VITE_SUPABASE_URL',
  'VITE_SUPABASE_ANON_KEY',
  'VITE_API_PROXY_TARGET',
];

// Shell must reset the disposable stack between runs (or provide unique upload
// basenames and watchlist labels). The upload is a private-source assertion;
// E2E_APPROVED_EVIDENCE_QUERY targets a separately seeded current-approved
// article snapshot and must not be expected to cite the private upload.
export const REAL_STACK_REQUIRED_FIXTURES = [
  'E2E_UPLOAD_PATH',
  'E2E_APPROVED_EVIDENCE_QUERY',
  'E2E_EXPECTED_ANSWER_FRAGMENT',
  'E2E_EXPECTED_ARTICLE_ID',
  'E2E_EXPECTED_ARTICLE_TITLE',
  'E2E_EXPECTED_ARTICLE_REVISION_ID',
  'E2E_EXPECTED_REVISION_NUMBER',
  'E2E_EXPECTED_EVIDENCE_SNAPSHOT_ID',
  'E2E_EXPECTED_SOURCE_SPAN_START',
  'E2E_EXPECTED_SOURCE_SPAN_END',
  'E2E_EXPECTED_EVIDENCE_FRAGMENT',
  'E2E_EXPECTED_SOURCE_VERSION',
  'E2E_WATCHLIST_LABEL',
];

export function isNonBlankEnvironmentValue(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function missingNonBlank(names, environment) {
  return names.filter((name) => !isNonBlankEnvironmentValue(environment[name]));
}

export function isRealStackEnabled(environment = process.env) {
  return environment.SENTINEL_REAL_STACK_E2E === '1';
}

export function assertRealStackBuildEnvironment(environment = process.env) {
  if (!isRealStackEnabled(environment)) {
    throw new Error('The real-stack Playwright config requires SENTINEL_REAL_STACK_E2E=1.');
  }
  const missing = missingNonBlank(REAL_STACK_BUILD_VARIABLES, environment);
  if (missing.length) {
    // Names only — build values must never enter an error or browser artifact.
    throw new Error(`Real-stack E2E is missing required build variables: ${missing.join(', ')}`);
  }
  // Same-origin /api is a mandatory seam. Reject even an empty assignment so
  // an inherited command environment cannot silently choose another base.
  if (environment.VITE_API_BASE !== undefined) {
    throw new Error('Real-stack E2E requires VITE_API_BASE to be unset so browser requests use same-origin /api.');
  }
}

export function assertRealStackFixtures(environment = process.env) {
  const missing = missingNonBlank(REAL_STACK_REQUIRED_FIXTURES, environment);
  if (missing.length) {
    // Fixture names only; query strings and fixture passages may be sensitive.
    throw new Error(`Real-stack E2E is missing required fixture variables: ${missing.join(', ')}`);
  }

  const revision = Number(environment.E2E_EXPECTED_REVISION_NUMBER);
  const start = Number(environment.E2E_EXPECTED_SOURCE_SPAN_START);
  const end = Number(environment.E2E_EXPECTED_SOURCE_SPAN_END);
  const invalidNumericFixtures = [
    !Number.isSafeInteger(revision) || revision <= 0 ? 'E2E_EXPECTED_REVISION_NUMBER' : null,
    !Number.isSafeInteger(start) || start < 0 ? 'E2E_EXPECTED_SOURCE_SPAN_START' : null,
    !Number.isSafeInteger(end) || end <= start ? 'E2E_EXPECTED_SOURCE_SPAN_END' : null,
  ].filter(Boolean);
  if (invalidNumericFixtures.length) {
    throw new Error(`Real-stack E2E has invalid exact-evidence numeric fixtures: ${invalidNumericFixtures.join(', ')}`);
  }
}
