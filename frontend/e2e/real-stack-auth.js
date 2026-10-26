// Credential boundary for the opt-in disposable real-stack suite.
// This module is never imported by application code. It reads only the four
// child-process variables Shell injects for a local GoTrue UI login; it never
// reads .env, credential files, storageState, or VITE-prefixed credentials.

import { isNonBlankEnvironmentValue } from './real-stack-contract.js';

const credentialNames = [
  'E2E_EMAIL',
  'E2E_PASSWORD',
  'E2E_OTHER_EMAIL',
  'E2E_OTHER_PASSWORD',
];

export function assertDisposableCredentials() {
  const missing = credentialNames.filter((name) => !isNonBlankEnvironmentValue(process.env[name]));
  if (missing.length) {
    // Names only — supplied values must never enter error output.
    throw new Error(`Real-stack E2E is missing required disposable credential variables: ${missing.join(', ')}`);
  }
}

function credentialsFor(subject) {
  if (subject === 'primary') {
    return { email: process.env.E2E_EMAIL, password: process.env.E2E_PASSWORD };
  }
  if (subject === 'other') {
    return { email: process.env.E2E_OTHER_EMAIL, password: process.env.E2E_OTHER_PASSWORD };
  }
  throw new Error(`Unknown disposable E2E subject: ${subject}`);
}

// A normal page-level GoTrue login. Do not replace this with storage-state
// injection: the real suite is intended to cover the browser auth boundary.
export async function loginAs(page, subject) {
  const { email, password } = credentialsFor(subject);
  await page.goto('/');
  await page.getByRole('heading', { name: 'Enter the Console' }).waitFor();
  await page.getByLabel('Operator email').fill(email);
  await page.getByLabel('Passcode').fill(password);
  await page.getByRole('button', { name: 'Authenticate' }).click();
  await page.getByRole('heading', { name: 'AI Assistant' }).waitFor({ timeout: 30_000 });
}
