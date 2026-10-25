#!/usr/bin/env node
// Disposable local Keycloak + Mailpit + ordinary server. No external email delivery or production configuration.
import { spawn } from 'node:child_process';
import { createServer, request as httpRequest } from 'node:http';
import { mkdtemp, mkdir, writeFile, readFile } from 'node:fs/promises';
import { openSync, closeSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const KEYCLOAK = 'quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b';
const MAILPIT = 'axllent/mailpit:v1.31.5@sha256:94f6b70c1436defae6799d9ac7ff0eeff9540465b842026d5cdb018f1c0234ea';
const POSTGRES = 'postgres:16.15-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea';
const delay = ms => new Promise(resolveDelay => setTimeout(resolveDelay, ms));
const secret = () => randomBytes(32).toString('base64url');
export async function startEmailPreview({ port = 8090, idpPort = 8091, mailPort = 8092, listen = true, invitationOnly = false } = {}) {
  for (const value of [port, idpPort, mailPort]) if (!Number.isInteger(value) || value < 1024 || value > 65535) throw new Error('Invalid preview port');
  if (new Set([port, idpPort, mailPort]).size !== 3) throw new Error('Preview ports must differ');
  for (const value of [port,idpPort,mailPort]) for (const host of ['127.0.0.1','::1']) {
    const probe = createServer();
    await new Promise((done,reject) => {
      probe.once('error', error => error.code === 'EADDRNOTAVAIL' ? done() : reject(new Error(`Local port ${value} is already in use (${host})`)));
      probe.listen({ port:value, host, ipv6Only:true }, () => probe.close(done));
    });
  }
  await mkdir(join(ROOT, 'server/build'), { recursive: true });
  const output = await mkdtemp(join(ROOT, 'server/build/email-preview-'));
  const logPath = join(output, 'stack.log'), log = openSync(logPath, 'wx', 0o600);
  const fixture = join(output, 'fixture');
  await mkdir(fixture, { mode: 0o700 });
  const run = (args, env = process.env, capture = false) => new Promise((resolveRun, reject) => {
    const child = spawn(args[0], args.slice(1), { cwd: ROOT, env, stdio: ['ignore', capture ? 'pipe' : log, log] });
    let result = '';
    child.stdout?.on('data', chunk => { result += chunk; });
    child.once('error', () => reject(new Error(`Local command unavailable. See ${logPath}`)));
    child.once('exit', code => code === 0 ? resolveRun(result.trim()) : reject(new Error(`Local stack command failed. See ${logPath}`)));
  });
  const origin = `http://localhost:${port}`, issuer = `http://id.usuals.localhost:${idpPort}/realms/usuals`;
  const idp = `http://127.0.0.1:${idpPort}`, mailbox = `http://localhost:${mailPort}`;
  const key = randomUUID().slice(0, 8), network = `usuals-email-${key}`, containers = [];
  let edge, networkCreated = false, closed = false;
  const stop = async () => {
    if (closed) return;
    closed = true;
    if (edge) { edge.closeAllConnections(); await new Promise(done => edge.close(done)); }
    for (const container of containers.reverse()) await run(['docker', 'rm', '-f', container]).catch(() => {});
    if (networkCreated) await run(['docker', 'network', 'rm', network]).catch(() => {});
    closeSync(log);
  };
  const container = async (name, args, env) => {
    await run(['docker', 'run', '--rm', '-d', '--name', name, '--network', network, ...args], env);
    containers.push(name);
  };
  try {
    process.stdout.write(`Building the standard server through scripts/dgradle. Log: ${logPath}\n`);
    const gradleHome = process.env.USUALS_GRADLE_USER_HOME;
    await run(['scripts/dgradle', ...(gradleHome ? ['-g', gradleHome] : []), '-p', 'server', 'testClasses', 'installDist', '--no-watch-fs']);
    const image = `usuals-email-preview:${key}`;
    await run(['docker', 'build', '--platform', 'linux/amd64', '-f', 'server/Dockerfile', '-t', image, 'server/build/install/usuals-relay']);
    await run(['docker', 'network', 'create', network]); networkCreated = true;
    const dbPassword = secret(), clientSecret = secret(), adminPassword = secret(), operatorPassword = secret(), operatorId = randomUUID();
    await writeFile(join(fixture, 'client-secret'), clientSecret, { mode: 0o600 });
    await writeFile(join(output, 'credentials.json'), JSON.stringify({
      synthetic: true, operator: { email: 'operator@example.test', password: operatorPassword },
      keycloak: { username: 'preview-admin', password: adminPassword },
    }, null, 2) + '\n', { mode: 0o600 });
    const realm = {
      realm: 'usuals', enabled: true, displayName: 'Usuals', sslRequired: 'none', registrationAllowed: true,
      registrationEmailAsUsername: true, loginWithEmailAllowed: true, duplicateEmailsAllowed: false,
      verifyEmail: true, resetPasswordAllowed: true, editUsernameAllowed: false, bruteForceProtected: true,
      defaultSignatureAlgorithm: 'RS256', internationalizationEnabled: true, supportedLocales: ['en','es','it','fr','de'], defaultLocale: 'en',
      smtpServer: { host: 'mailpit', port: '1025', from: 'usuals@example.test', fromDisplayName: 'Usuals', ssl: 'false', starttls: 'false', auth: 'false' },
      clients: [{ clientId: 'usuals-web', enabled: true, protocol: 'openid-connect', publicClient: false, secret: clientSecret,
        standardFlowEnabled: true, directAccessGrantsEnabled: false, serviceAccountsEnabled: false,
        redirectUris: [`${origin}/auth/callback`], webOrigins: [origin],
        defaultClientScopes: ['basic','profile','email'], attributes: { 'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': `${origin}/auth/login?fresh=true` } }],
      users: [{ id: operatorId, username: 'operator@example.test', email: 'operator@example.test', emailVerified: true,
        firstName: 'Local', lastName: 'Operator', enabled: true, credentials: [{ type: 'password', value: operatorPassword, temporary: false }] }],
    };
    await writeFile(join(output, 'realm.json'), JSON.stringify(realm), { mode: 0o600 });
    await container(`usuals-email-mail-${key}`, ['--network-alias','mailpit','-p',`127.0.0.1:${mailPort}:8025`,MAILPIT]);
    await container(`usuals-email-db-${key}`, ['--network-alias','database','--tmpfs','/var/lib/postgresql/data:rw,size=256m',
      '-e','POSTGRES_DB=usuals_test','-e','POSTGRES_USER=usuals_test','-e','POSTGRES_PASSWORD',POSTGRES], { ...process.env, POSTGRES_PASSWORD: dbPassword });
    // Only a throwaway realm import is mounted; no host repository or real identity configuration is exposed.
    await container(`usuals-email-idp-${key}`, ['--user','0:0','--network-alias','id.usuals.localhost','-p',`127.0.0.1:${idpPort}:${idpPort}`,
      '-e','KC_BOOTSTRAP_ADMIN_USERNAME=preview-admin','-e','KC_BOOTSTRAP_ADMIN_PASSWORD','-e','JAVA_OPTS_KC_HEAP=-Xms128m -Xmx512m',
      '-v',`${join(output,'realm.json')}:/opt/keycloak/data/import/usuals.json:ro`,KEYCLOAK,
      'start-dev','--import-realm','--http-port',String(idpPort),'--hostname',`http://id.usuals.localhost:${idpPort}`], { ...process.env, KC_BOOTSTRAP_ADMIN_PASSWORD: adminPassword });
    let ready = false;
    for (let i = 0; i < 180; i++) {
      try { if ((await fetch(`${idp}/realms/usuals/.well-known/openid-configuration`, { signal: AbortSignal.timeout(1000) })).ok) { ready = true; break; } } catch {}
      await delay(500);
    }
    if (!ready) throw new Error(`Local Keycloak startup timed out. See ${logPath}`);
    const response = await fetch(`${idp}/realms/master/protocol/openid-connect/token`, {
      method: 'POST', body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: 'preview-admin', password: adminPassword }),
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error('Local identity configuration failed');
    const { access_token: adminToken } = await response.json();
    const currentProfile = await fetch(`${idp}/admin/realms/usuals/users/profile`, { headers: { Authorization: `Bearer ${adminToken}` }, signal: AbortSignal.timeout(10000) });
    if (!currentProfile.ok) throw new Error('Local identity profile could not be read');
    const profileConfiguration = await currentProfile.json();
    profileConfiguration.attributes = profileConfiguration.attributes.filter(attribute => ['username','email'].includes(attribute.name));
    // This release represents disabled unmanaged attributes by an absent policy, not a DISABLED enum value.
    delete profileConfiguration.unmanagedAttributePolicy;
    const profile = await fetch(`${idp}/admin/realms/usuals/users/profile`, {
      method: 'PUT', headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(profileConfiguration), signal: AbortSignal.timeout(10000),
    });
    if (!profile.ok) {
      await writeFile(join(output, 'profile-error.json'), await profile.text(), { mode: 0o600 });
      throw new Error(`Local registration profile configuration failed (${profile.status}). See ${join(output,'profile-error.json')}`);
    }
    const serverName = `usuals-email-server-${key}`;
    const previewEnvironment = { USUALS_TEST_JDBC_URL: 'jdbc:postgresql://database:5432/usuals_test', USUALS_TEST_DB_USER: 'usuals_test', USUALS_TEST_DB_PASSWORD: dbPassword,
      USUALS_OIDC_ENABLED: 'true', USUALS_OIDC_ISSUER: issuer, USUALS_OIDC_CLIENT_ID: 'usuals-web', USUALS_OIDC_CLIENT_SECRET_FILE: '/preview/client-secret',
      USUALS_OIDC_ADMIN_SUBJECTS: JSON.stringify([operatorId]), USUALS_OIDC_LOCAL_HTTP: 'true', USUALS_OIDC_REGISTRATION_ENABLED: 'true',
      USUALS_DIRECTORY: 'collaborators', USUALS_INVITATION_ONLY: invitationOnly ? 'true' : 'false',
      USUALS_EMAIL_INVITATIONS_ENABLED: 'true', USUALS_SMTP_HOST: 'mailpit', USUALS_SMTP_PORT: '1025', USUALS_SMTP_FROM: 'usuals@example.test', USUALS_SMTP_STARTTLS: 'false' };
    const env = { ...process.env, ...previewEnvironment };
    const envArgs = Object.keys(previewEnvironment).flatMap(name => ['-e', name]);
    await container(serverName, ['--platform','linux/amd64','--user',`${process.getuid()}:${process.getgid()}`,
      '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--tmpfs','/tmp:rw,size=32m',
      '-p',`127.0.0.1::${port}`,...envArgs,
      '-v',`${join(ROOT,'server/build/classes/kotlin/test')}:/preview-classes:ro`,'-v',`${fixture}:/preview:rw`,
      '--entrypoint','java',image,'-Xmx512m','-cp','/preview-classes:/opt/usuals/lib/*','io.theficos.usuals.relay.EmailPreviewFixture',String(port),'/preview'], env);
    const upstreamPort = Number((await run(['docker','port',serverName,`${port}/tcp`], process.env, true)).split(':').at(-1));
    ready = false;
    for (let i = 0; i < 120; i++) {
      try { await readFile(join(fixture,'ready.json')); ready = true; break; } catch {}
      await delay(500);
    }
    if (!ready) throw new Error(`Local server startup timed out. Inspect docker logs for ${serverName}; do not share credentials.`);
    if (listen) {
      edge = createServer((req,res) => {
        if (req.headers.host !== `localhost:${port}`) { res.writeHead(400); res.end(); return; }
        const headers = { ...req.headers };
        for (const key of Object.keys(headers)) if (key === 'forwarded' || key.startsWith('x-forwarded-')) delete headers[key];
        const upstream = httpRequest({ hostname: '127.0.0.1', port: upstreamPort, path: req.url, method: req.method, headers }, response => { res.writeHead(response.statusCode, response.headers); response.pipe(res); });
        upstream.on('error', () => { if (!res.headersSent) { res.writeHead(502); res.end(); } else res.destroy(); });
        res.on('close', () => upstream.destroy()); req.pipe(upstream);
      });
      await new Promise((done,reject) => { edge.once('error',reject); edge.listen(port,'127.0.0.1',done); });
    }
    const result = { origin, issuer, mailbox, upstreamPort, output, credentialsFile: join(output,'credentials.json'), stop };
    await writeFile(join(output,'stack.json'), JSON.stringify({ origin, issuer, mailbox, upstreamPort, invitationOnly, pid:process.pid },null,2)+'\n', { mode: 0o600 });
    process.stdout.write(`Local registration ready: ${origin}/\nCaptured email: ${mailbox}/\nSynthetic operator credentials: ${result.credentialsFile}\n`);
    return result;
  } catch (error) {
    for (const container of containers) await run(['docker','logs',container]).catch(() => {});
    await stop(); throw error;
  }
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const stack = await startEmailPreview({ invitationOnly: process.argv.includes('--invitation-only') });
  for (const signal of ['SIGINT','SIGTERM']) process.once(signal, async () => { await stack.stop(); process.exit(0); });
}
