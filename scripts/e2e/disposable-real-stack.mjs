#!/usr/bin/env node
// Disposable infrastructure only. Never loads dotenv or ambient credentials.
import { spawn } from 'node:child_process';
import { createServer as httpServer, request as httpRequest } from 'node:http';
import { createServer as tcpServer } from 'node:net';
import { generateKeyPairSync, randomBytes, randomUUID, createHash } from 'node:crypto';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import safety from './runner-safety.cjs';

safety.validateOptIn(process.env);
if (process.version !== 'v24.20.0') throw new Error('Use exactly Node v24.20.0');
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const baseEnv = safety.childEnvironment(process.env);
baseEnv.PATH = `${path.dirname(process.execPath)}:${baseEnv.PATH}`;
baseEnv.JAVA_HOME ||= '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home';
const java = path.join(baseEnv.JAVA_HOME, 'bin/java');
const runId = `sentinel-real-stack-${randomUUID()}`;
const secrets = [];
const secret = () => { const value = randomBytes(32).toString('hex'); secrets.push(value); return value; };
const scrub = text => safety.redactOutput(text, secrets);
let root, logPath;
let logQueue = Promise.resolve();
const children = new Set(), containers = [], servers = [];
let networkCreated = false, cleaning = false;
async function log(text) {
  const clean = scrub(text);
  process.stdout.write(clean + '\n');
  if (logPath) {
    logQueue = logQueue.then(() => fs.appendFile(logPath, clean + '\n', {mode: 0o600}));
    await logQueue;
  }
}
function launch(command, args, {cwd = repo, env = {}, input, quiet = false} = {}) {
  const child = spawn(command, args, {cwd, env: {...baseEnv, ...env}, detached: true, stdio: ['pipe', 'pipe', 'pipe']});
  children.add(child);
  let output = '', pending = '';
  function consume(chunk) {
    output += chunk;
    if (quiet) return;
    pending += chunk;
    let end;
    while ((end = pending.indexOf('\n')) >= 0) {
      const line = pending.slice(0, end); pending = pending.slice(end + 1);
      void log(line);
    }
  }
  child.stdout.on('data', consume); child.stderr.on('data', consume);
  child.stdin.on('error', () => {});
  child.stdin.end(input);
  const done = new Promise((resolve, reject) => {
    child.on('error', () => reject(new Error(`${path.basename(command)} could not start`)));
    child.on('close', code => {
      children.delete(child);
      if (!quiet && pending) void log(pending);
      if (code === 0) resolve(output);
      else reject(new Error(`${path.basename(command)} exited ${code}${quiet ? ': ' + scrub(output).slice(-2500) : ''}`));
    });
  });
  done.catch(() => {});
  return {child, done, output: () => output};
}
const run = (cmd, args, options) => launch(cmd, args, options).done;
const docker = (args, options = {}) => run('docker', args, {quiet: true, ...options});
async function waitFor(probe, label, timeout = 120000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await probe()) return;
    await new Promise(r => setTimeout(r, 350));
  }
  throw new Error(`Readiness timeout: ${label}`);
}
async function freePort() {
  const server = tcpServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function copy(relative) {
  const from = path.join(repo, relative), to = path.join(root, relative);
  const stat = await fs.lstat(from);
  if (!safety.includeSnapshotEntry(relative, stat.isSymbolicLink())) return;
  if (stat.isDirectory()) {
    await fs.mkdir(to, {recursive: true});
    for (const name of await fs.readdir(from)) await copy(path.join(relative, name));
  } else if (stat.isFile()) {
    await fs.mkdir(path.dirname(to), {recursive: true});
    await fs.copyFile(from, to);
  }
}
async function api(url, body) {
  const response = await fetch(url, {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body), signal: AbortSignal.timeout(15000)});
  if (!response.ok) throw new Error(`Disposable auth returned HTTP ${response.status}`);
  return response.json();
}
async function cleanup() {
  if (cleaning) return; cleaning = true;
  for (const child of children) { try { process.kill(-child.pid, 'SIGTERM'); } catch {} }
  await new Promise(resolve => setTimeout(resolve, 1000));
  for (const child of children) { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }
  for (const server of servers) { server.closeAllConnections?.(); await new Promise(resolve => server.close(resolve)); }
  for (const name of [...containers].reverse()) {
    try {
      const owner = (await docker(['inspect', '--format', '{{index .Config.Labels "sentinel.e2e.owner"}}', name])).trim();
      if (owner !== runId) throw new Error('Container ownership mismatch');
      await docker(['rm', '-f', '-v', name]);
    } catch (error) { await log(`Cleanup: ${error.message}`); }
  }
  if (networkCreated) {
    const owner = (await docker(['network', 'inspect', '--format', '{{index .Labels "sentinel.e2e.owner"}}', runId])).trim();
    if (owner !== runId) throw new Error('Network ownership mismatch');
    await docker(['network', 'rm', runId]);
  }
  const remaining = (await docker(['ps', '-aq', '--filter', `label=sentinel.e2e.owner=${runId}`])).trim();
  if (remaining) { process.exitCode = 1; throw new Error('Owned containers remain after cleanup'); }
  if (root) {
    for (const folder of ['frontend/test-results', 'frontend/playwright-report']) await fs.rm(path.join(root, folder), {recursive: true, force: true});
  }
  await log('Owned stack cleanup finished.');
}
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void cleanup().then(() => process.exit(130)); });

try {
  // Never reuse or kill a preview owned by another agent.
  const uiPort = await freePort();
  const uiOrigin = `http://127.0.0.1:${uiPort}`;
  root = await fs.mkdtemp('/tmp/sentinel-real-stack-');
  await fs.chmod(root, 0o700);
  // Empty Docker client config avoids personal registry credentials/contexts.
  baseEnv.DOCKER_CONFIG = path.join(root, 'docker-client');
  await fs.mkdir(baseEnv.DOCKER_CONFIG, {mode: 0o700});
  baseEnv.NPM_CONFIG_USERCONFIG = '/dev/null';
  logPath = path.join(root, 'execution.log');
  await log(`Snapshot: ${root}`);
  await log('AI PROVIDER SUBSTITUTION: deterministic test chat/embeddings, NOT live model quality.');
  for (const relative of ['backend/src/main', 'backend/src/test/java/com/sentinel/kms/e2e/SentinelDisposableBrowserHarness.java', 'backend/src/test/resources/sentinel-disposable-browser.yml', 'backend/pom.xml', 'docker/db-init/01-init.sql', 'supabase/migrations', 'frontend/src', 'frontend/public', 'frontend/e2e', 'scripts/e2e', 'frontend/index.html', 'frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.js', 'frontend/playwright.config.js', 'frontend/playwright.real.config.js']) {
    try { await copy(relative); } catch (error) { if (error.code !== 'ENOENT' || relative !== 'frontend/public') throw error; }
  }
  const migrationNames = (await fs.readdir(path.join(root, 'supabase/migrations'))).filter(n => n.endsWith('.sql')).sort();
  const manifest = [];
  for (const name of migrationNames) manifest.push({name, sha256: createHash('sha256').update(await fs.readFile(path.join(root, 'supabase/migrations', name))).digest('hex')});
  await fs.writeFile(path.join(root, 'migration-manifest.json'), JSON.stringify(manifest, null, 2));
  await run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], {cwd: path.join(root, 'frontend'), env: {NPM_CONFIG_USERCONFIG: '/dev/null'}});
  await run('mvn', ['-s', '../scripts/e2e/empty-maven-settings.xml', '-gs', '../scripts/e2e/empty-maven-settings.xml', '-q', '-DskipTests', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test', '-Dmdep.outputFile=target/e2e-classpath.txt'], {cwd: path.join(root, 'backend')});
  await docker(['image', 'inspect', 'pgvector/pgvector:pg16', '--format', '{{.Id}}']);
  const authImage = 'supabase/gotrue:v2.177.0';
  try { await docker(['image', 'inspect', authImage, '--format', '{{.Id}}']); }
  catch { await log('Pulling pinned GoTrue v2.177.0'); await docker(['pull', authImage]); }
  await docker(['network', 'create', '--label', `sentinel.e2e.owner=${runId}`, runId]); networkCreated = true;
  const dbPassword = secret(), jwtSecret = secret();
  const dbName = `${runId}-db`, authName = `${runId}-auth`;
  const dbPort = await freePort(), authPort = await freePort(), backendPort = await freePort(), gatewayPort = await freePort();
  const dbEnv = {POSTGRES_USER: 'postgres', POSTGRES_PASSWORD: dbPassword, POSTGRES_DB: 'sentinel_e2e'};
  await docker(['run', '-d', '--name', dbName, '--label', `sentinel.e2e.owner=${runId}`, '--network', runId, '--network-alias', 'db', '--log-driver', 'none', '-p', `127.0.0.1:${dbPort}:5432`, ...Object.keys(dbEnv).flatMap(k => ['-e', k]), 'pgvector/pgvector:pg16'], {env: dbEnv}); containers.push(dbName);
  await waitFor(async () => { try { await docker(['exec', '-e', 'PGPASSWORD', dbName, 'psql', '-X', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'sentinel_e2e', '-qAt', '-c', 'select 1'], {env: {PGPASSWORD: dbPassword}}); return true; } catch { return false; } }, 'PostgreSQL');
  const sql = async text => docker(['exec', '-i', dbName, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'sentinel_e2e', '-qAt'], {input: text});
  await sql(await fs.readFile(path.join(root, 'docker/db-init/01-init.sql'), 'utf8'));
  for (const name of migrationNames) { await sql('set search_path to public, extensions;\n' + await fs.readFile(path.join(root, 'supabase/migrations', name), 'utf8')); await log(`Migration applied: ${name}`); }
  // GoTrue has its own schema and migrations; application schema is never substituted.
  await sql('create schema if not exists auth;');
  const {privateKey} = generateKeyPairSync('ec', {namedCurve: 'P-256'});
  const jwk = {...privateKey.export({format: 'jwk'}), kid: 'disposable-es256', alg: 'ES256', use: 'sig', key_ops: ['sign', 'verify']};
  secrets.push(jwk.d);
  const issuer = `http://127.0.0.1:${gatewayPort}/auth/v1`;
  const authEnv = {GOTRUE_API_HOST: '0.0.0.0', PORT: '9999', API_EXTERNAL_URL: issuer,
    GOTRUE_SITE_URL: uiOrigin, GOTRUE_DB_DRIVER: 'postgres',
    GOTRUE_DB_DATABASE_URL: `postgres://postgres:${dbPassword}@db:5432/sentinel_e2e?sslmode=disable&search_path=auth`,
    GOTRUE_JWT_SECRET: jwtSecret, GOTRUE_JWT_KEYS: JSON.stringify([jwk]), GOTRUE_JWT_ISSUER: issuer,
    GOTRUE_JWT_AUD: 'authenticated', GOTRUE_JWT_EXP: '3600', GOTRUE_JWT_DEFAULT_GROUP_NAME: 'authenticated',
    GOTRUE_CORS_ALLOWED_HEADERS: 'apikey', GOTRUE_EXTERNAL_EMAIL_ENABLED: 'true', GOTRUE_MAILER_AUTOCONFIRM: 'true', GOTRUE_LOG_LEVEL: 'error'};
  await docker(['create', '--name', authName, '--label', `sentinel.e2e.owner=${runId}`, '--network', runId, '--log-driver', 'none', '-p', `127.0.0.1:${authPort}:9999`, ...Object.keys(authEnv).flatMap(k => ['-e', k]), authImage], {env: authEnv}); containers.push(authName);
  const auth = launch('docker', ['start', '-a', authName], {quiet: true});
  // Transparent prefix gateway only: no canned responses and no token minting.
  const gateway = httpServer((req, res) => {
    if (!req.url.startsWith('/auth/v1/')) { res.writeHead(404).end(); return; }
    const upstream = httpRequest({hostname: '127.0.0.1', port: authPort, path: req.url.slice('/auth/v1'.length), method: req.method, headers: req.headers}, response => {res.writeHead(response.statusCode, response.headers); response.pipe(res);});
    upstream.on('error', () => res.writeHead(502).end()); req.pipe(upstream);
  });
  await new Promise(resolve => gateway.listen(gatewayPort, '127.0.0.1', resolve)); servers.push(gateway);
  await waitFor(async () => { if (auth.child.exitCode !== null) { await auth.done; throw new Error('GoTrue exited before health readiness'); } try { return (await fetch(`${issuer}/health`, {signal: AbortSignal.timeout(1000)})).ok; } catch { return false; } }, 'GoTrue health');
  const jwks = await (await fetch(`${issuer}/.well-known/jwks.json`)).json();
  if (!jwks.keys?.some(k => k.kid === jwk.kid && k.alg === 'ES256' && !k.d)) throw new Error('GoTrue did not publish asymmetric public JWKS');
  await log('Real GoTrue health and ES256 JWKS verified.');
  const emailA = `${randomUUID()}@sentinel.test`, emailB = `${randomUUID()}@sentinel.test`;
  secrets.push(emailA, emailB);
  const passwordA = secret(), passwordB = secret();
  await api(`${issuer}/signup`, {email: emailA, password: passwordA});
  await api(`${issuer}/signup`, {email: emailB, password: passwordB});
  const sessionA = await api(`${issuer}/token?grant_type=password`, {email: emailA, password: passwordA});
  const sessionB = await api(`${issuer}/token?grant_type=password`, {email: emailB, password: passwordB});
  for (const session of [sessionA, sessionB]) {
    secrets.push(session.access_token, session.refresh_token);
    const header = JSON.parse(Buffer.from(session.access_token.split('.')[0], 'base64url'));
    const claims = JSON.parse(Buffer.from(session.access_token.split('.')[1], 'base64url'));
    if (header.alg !== 'ES256' || claims.iss !== issuer || claims.aud !== 'authenticated') throw new Error('GoTrue token claims mismatch');
  }
  await log('Two ephemeral GoTrue password logins returned ES256 authenticated-audience tokens.');
  const cp = await fs.readFile(path.join(root, 'backend/target/e2e-classpath.txt'), 'utf8');
  const backendEnv = {APP_CORS_ALLOWED_ORIGINS: uiOrigin, SENTINEL_REAL_STACK_E2E: '1', SPRING_CONFIG_LOCATION: 'classpath:sentinel-disposable-browser.yml', SPRING_CONFIG_IMPORT: '',
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/sentinel_e2e`, SPRING_DATASOURCE_USERNAME: 'postgres', SPRING_DATASOURCE_PASSWORD: dbPassword,
    APP_SECURITY_JWKS_URI: `${issuer}/.well-known/jwks.json`, APP_SECURITY_JWT_ISSUER: issuer, APP_SECURITY_JWT_AUDIENCE: 'authenticated', SERVER_PORT: String(backendPort)};
  const backend = launch(java, ['-cp', `target/test-classes:target/classes:${cp.trim()}`, 'com.sentinel.kms.e2e.SentinelDisposableBrowserHarness'], {cwd: path.join(root, 'backend'), env: backendEnv});
  await waitFor(async () => { if (backend.child.exitCode !== null) throw new Error('Backend exited before fixture readiness'); return backend.output().includes('SENTINEL_DISPOSABLE_READY'); }, 'backend seeded/indexed', 120000);
  const baseApi = `http://127.0.0.1:${backendPort}`;
  if ((await fetch(`${baseApi}/api/me`)).status !== 401) throw new Error('Production API accepted anonymous request');
  for (const session of [sessionA, sessionB]) if (!(await fetch(`${baseApi}/api/me`, {headers: {authorization: `Bearer ${session.access_token}`}})).ok) throw new Error('Production JWT validation rejected GoTrue login');
  await log('Production SecurityConfig verified: anonymous 401, both GoTrue users 200.');
  const uploadPath = path.join(root, 'disposable-procedure.txt');
  await fs.writeFile(uploadPath, 'Sentinel upload-only marker: cobalt lantern. Revoke active sessions before rotating credentials.');
  const evidenceMetadata = JSON.parse((await sql("select metadata from vector_store where metadata->>'article_id'='E2E-9001' order by id limit 1")).trim());
  const e2eEnv = {E2E_APPROVED_EVIDENCE_QUERY: 'What is the Sentinel disposable procedure E2E-9001?', E2E_EXPECTED_ARTICLE_ID: 'E2E-9001',
    ...safety.evidenceFixtures(evidenceMetadata),
    CI: '1', SENTINEL_REAL_STACK_E2E: '1', SENTINEL_E2E_UI_PORT: String(uiPort), VITE_API_PROXY_TARGET: baseApi, VITE_SUPABASE_URL: `http://127.0.0.1:${gatewayPort}`, VITE_SUPABASE_ANON_KEY: 'disposable-local-public-client',
    E2E_EMAIL: emailA, E2E_PASSWORD: passwordA, E2E_OTHER_EMAIL: emailB, E2E_OTHER_PASSWORD: passwordB,
    E2E_UPLOAD_PATH: uploadPath, E2E_QUERY: 'What is the Sentinel disposable procedure E2E-9001?', E2E_EXPECTED_ANSWER_FRAGMENT: 'Revoke active sessions before rotating credentials.',
    E2E_EXPECTED_CITATION_ID: 'E2E-9001', E2E_EXPECTED_ARTICLE_TITLE: 'Sentinel disposable procedure',
    E2E_EXPECTED_EVIDENCE_FRAGMENT: 'Sentinel disposable procedure requires revoking active sessions', E2E_EXPECTED_SOURCE_VERSION: '2026-09-06', E2E_WATCHLIST_LABEL: 'Disposable A-only watchlist', NPM_CONFIG_USERCONFIG: '/dev/null'};
  await log('Executing CI=1 SENTINEL_REAL_STACK_E2E=1 npm run test:e2e:real -- --config ../scripts/e2e/disposable.playwright.config.mjs');
  await run('npm', ['run', 'test:e2e:real', '--', '--config', '../scripts/e2e/disposable.playwright.config.mjs'], {cwd: path.join(root, 'frontend'), env: e2eEnv});
  const persisted = await sql("select json_build_object('indexed_uploads',(select count(*) from ingest_jobs where stage='indexed' and not is_demo),'upload_vectors',(select count(*) from vector_store where metadata->>'job_id' is not null),'assistant_messages',(select count(*) from messages where role='assistant'),'watchlists',(select count(*) from watchlists))");
  const counts = JSON.parse(persisted.trim());
  if (Object.values(counts).some(count => !Number.isInteger(count) || count < 1)) throw new Error('Required browser-created persistence is missing');
  await log(`Persistence counts: ${JSON.stringify(counts)}`);
  const ownedIds = JSON.parse((await sql("select json_build_object('conversation',(select id from conversations limit 1),'watchlist',(select id from watchlists limit 1),'upload',(select id from ingest_jobs where not is_demo limit 1))")).trim());
  const asUser = (session, route, method = 'GET') => fetch(baseApi + route, {method, headers: {authorization: `Bearer ${session.access_token}`}, signal: AbortSignal.timeout(10000)});
  const conversationRoute = `/api/assistant/conversations/${encodeURIComponent(ownedIds.conversation)}`;
  if ((await asUser(sessionA, conversationRoute + '/messages')).status !== 200) throw new Error('Owner cannot reopen persisted conversation');
  if ((await asUser(sessionB, conversationRoute + '/messages')).status !== 404) throw new Error('Cross-user conversation read was not denied');
  if ((await asUser(sessionB, conversationRoute, 'DELETE')).status !== 404) throw new Error('Cross-user conversation delete was not denied');
  if ((await asUser(sessionB, `/api/ingest/jobs/${encodeURIComponent(ownedIds.upload)}`, 'DELETE')).status !== 404) throw new Error('Cross-user upload delete was not denied');
  await asUser(sessionB, `/api/watchlists/${encodeURIComponent(ownedIds.watchlist)}`, 'DELETE');
  const remainingWatchlists = await (await asUser(sessionA, '/api/watchlists')).json();
  if (!remainingWatchlists.some(item => item.id === ownedIds.watchlist)) throw new Error('Cross-user watchlist delete mutated owner state');
  if ((await asUser(sessionA, conversationRoute + '/messages')).status !== 200) throw new Error('Cross-user attempt mutated conversation');
  await log('Cross-user direct HTTP verification passed: private conversation read/delete 404, upload delete 404, watchlist delete has no effect; owner readback preserved.');
  await fs.writeFile(path.join(root, 'result.json'), JSON.stringify({status: 'passed', aiProviderSubstitution: true, persistence: counts, crossUserHttp: 'passed', browserSuite: 'real-stack.spec.js', optionalRetryFixture: false}, null, 2));
  await log('REAL STACK BROWSER RUN PASSED (deterministic AI provider substitution).');
} catch (error) {
  await log(`RUN FAILED: ${error.message}`);
  process.exitCode = 1;
} finally {
  await cleanup();
}
