#!/usr/bin/env node
/**
 * Configures a fresh local WSO2 API Manager for the IT Access Portal. Safe to re-run: every step
 * checks whether its result already exists.
 *
 *   node infra/wso2/setup.mjs        (WSO2 must be running: docker compose --profile gateway up -d)
 *
 * Steps (the same things you would click through in the Publisher / Developer Portal UIs):
 *   1. Roles portal_employee / portal_admin, and demo users ted.l (employee) / alice.admin (admin)
 *   2. API "AccessPortalAPI": context /accessportal, version 1.0.0, backend http://host.docker.internal:8080/api,
 *      one resource per backend endpoint, OAuth2 scopes bound to the roles
 *   3. Revision + deploy to the "Default" gateway, then lifecycle -> PUBLISHED
 *   4. Developer Portal application "AccessPortalWeb", subscribed to the API, with OAuth2 keys
 *   5. Exports WSO2's signing certificate so the backend can verify the gateway's X-JWT-Assertion
 *
 * Reads the repo-root .env. Writes (all git-ignored) frontend/public/gateway-config.json (client id
 * for the Angular gateway mode) and infra/wso2/generated/: app.env (client id/secret)
 * and wso2-signing-cert.pem. Local development only: TLS verification is disabled for localhost
 * because WSO2 uses a self-signed certificate.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'; // self-signed localhost certificate (dev only)

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const generatedDir = join(here, 'generated');

const env = loadDotEnv(join(repoRoot, '.env'));
const ADMIN = 'admin';
const ADMIN_PASSWORD = required('WSO2_ADMIN_PASSWORD');
const USERS = [
  { name: 'ted.l', password: required('PORTAL_EMPLOYEE_PASSWORD'), roles: ['portal_employee'] },
  { name: 'alice.admin', password: required('PORTAL_ADMIN_PASSWORD'), roles: ['portal_employee', 'portal_admin'] },
];
const MGMT = 'https://localhost:9443';
const API = {
  name: 'AccessPortalAPI',
  context: '/accessportal',
  version: '1.0.0',
  backend: env.WSO2_BACKEND_URL || 'http://host.docker.internal:8080/api',
};
const APP_NAME = 'AccessPortalWeb';
// WSO2 accepts a regular expression here. Both URLs must be allowed: the login callback, and the
// page WSO2 returns to after sign-out (post_logout_redirect_uri).
const CALLBACK_URL = 'regexp=(http://localhost:4200/auth/callback|http://localhost:4200/)';

// Scope -> roles allowed to obtain it. The gateway rejects a call (403) if the token lacks the scope.
const SCOPES = [
  { name: 'access_request:read', description: 'Read access requests', roles: ['portal_employee', 'portal_admin'] },
  { name: 'access_request:submit', description: 'Submit access requests', roles: ['portal_employee', 'portal_admin'] },
  { name: 'access_request:review', description: 'Approve or reject requests', roles: ['portal_admin'] },
];
// One gateway resource per backend endpoint (paths are relative to the API context).
const OPERATIONS = [
  { verb: 'GET', target: '/requests', scope: 'access_request:read' },
  { verb: 'POST', target: '/requests', scope: 'access_request:submit' },
  { verb: 'GET', target: '/requests/{id}', scope: 'access_request:read' },
  { verb: 'PATCH', target: '/requests/{id}/status', scope: 'access_request:review' },
  { verb: 'GET', target: '/requests/{id}/history', scope: 'access_request:read' },
  { verb: 'GET', target: '/requests/{id}/notifications', scope: 'access_request:read' },
  { verb: 'GET', target: '/health', scope: null }, // public: no token needed
];

await main();

async function main() {
  await waitForServer();

  step('1. Roles and users (RemoteUserStoreManagerService admin service)');
  for (const role of ['portal_employee', 'portal_admin']) {
    if (await soap('isExistingRole', `<ser:roleName>${role}</ser:roleName>`) === 'true') {
      log(`role ${role} exists`);
    } else {
      await soap('addRole', `<ser:roleName>${role}</ser:roleName>`);
      log(`role ${role} created`);
    }
  }
  for (const user of USERS) {
    if (await soap('isExistingUser', `<ser:userName>${user.name}</ser:userName>`) === 'true') {
      log(`user ${user.name} exists`);
      continue;
    }
    await soap('addUser', [
      `<ser:userName>${user.name}</ser:userName>`,
      `<ser:credential>${xml(user.password)}</ser:credential>`,
      ...user.roles.map((r) => `<ser:roleList>${r}</ser:roleList>`),
      '<ser:profileName>default</ser:profileName>',
      '<ser:requirePasswordChange>false</ser:requirePasswordChange>',
    ].join(''));
    log(`user ${user.name} created with roles ${user.roles.join(', ')}`);
  }

  step('2. Admin token for the Publisher / Developer Portal REST APIs');
  const setupClient = await registerClient('portal_setup_client');
  const token = await passwordToken(setupClient, ADMIN, ADMIN_PASSWORD, [
    'apim:api_view', 'apim:api_create', 'apim:api_publish', 'apim:api_manage',
    'apim:subscribe', 'apim:app_manage', 'apim:sub_manage', 'apim:api_key',
  ].join(' '));
  const publisher = rest(`${MGMT}/api/am/publisher/v4`, token);
  const devportal = rest(`${MGMT}/api/am/devportal/v3`, token);

  step(`3. API ${API.name} ${API.version} -> ${API.backend}`);
  const found = await publisher('GET', `/apis?query=${encodeURIComponent(`name:${API.name}`)}`);
  let api = found.list.find((a) => a.name === API.name && a.version === API.version);
  if (api) {
    log(`API exists (${api.id}), status ${api.lifeCycleStatus}`);
  } else {
    api = await publisher('POST', '/apis', {
      name: API.name,
      context: API.context,
      version: API.version,
      description: 'IT Access Request Portal REST API (Spring Boot backend)',
      transport: ['https'],
      policies: ['Unlimited'],
      securityScheme: ['oauth2'],
      endpointConfig: {
        endpoint_type: 'http',
        production_endpoints: { url: API.backend },
        sandbox_endpoints: { url: API.backend },
      },
      scopes: SCOPES.map((s) => ({
        shared: false,
        scope: { name: s.name, displayName: s.name, description: s.description, bindings: s.roles },
      })),
      operations: OPERATIONS.map((op) => ({
        target: op.target,
        verb: op.verb,
        authType: op.scope ? 'Application & Application User' : 'None',
        throttlingPolicy: 'Unlimited',
        scopes: op.scope ? [op.scope] : [],
      })),
    });
    log(`API created (${api.id})`);
  }

  if (api.lifeCycleStatus !== 'PUBLISHED') {
    const revision = await publisher('POST', `/apis/${api.id}/revisions`, { description: 'Initial revision (setup.mjs)' });
    await publisher('POST', `/apis/${api.id}/deploy-revision?revisionId=${revision.id}`, [
      { name: 'Default', vhost: 'localhost', displayOnDevportal: true },
    ]);
    log(`revision ${revision.displayName} deployed to gateway "Default"`);
    await publisher('POST', `/apis/change-lifecycle?apiId=${api.id}&action=Publish`);
    log('lifecycle -> PUBLISHED');
  }

  step(`4. Application ${APP_NAME}, subscription and OAuth2 keys`);
  const apps = await devportal('GET', `/applications?query=${encodeURIComponent(APP_NAME)}`);
  let app = apps.list.find((a) => a.name === APP_NAME);
  if (!app) {
    app = await devportal('POST', '/applications', {
      name: APP_NAME,
      throttlingPolicy: 'Unlimited',
      description: 'Angular UI and curl examples for the IT Access Portal',
      tokenType: 'JWT',
    });
    log(`application created (${app.applicationId})`);
  } else {
    log(`application exists (${app.applicationId})`);
  }

  const subs = await devportal('GET', `/subscriptions?applicationId=${app.applicationId}`);
  if (!subs.list.some((s) => s.apiId === api.id)) {
    await waitForDevportalApi(devportal, api.id);
    await devportal('POST', '/subscriptions', { applicationId: app.applicationId, apiId: api.id, throttlingPolicy: 'Unlimited' });
    log('subscribed to the API');
  } else {
    log('already subscribed');
  }

  // The browser (Angular) signs in with authorization code + PKCE as a *public* client: it has no
  // secret, so PKCE is what stops a stolen authorization code from being exchanged by someone else.
  // The client secret still exists for the curl / password-grant examples in docs/wso2-setup.md.
  const keyConfig = {
    keyType: 'PRODUCTION',
    keyManager: 'Resident Key Manager',
    grantTypesToBeSupported: ['authorization_code', 'refresh_token', 'password', 'client_credentials'],
    callbackUrl: CALLBACK_URL,
    additionalProperties: {
      pkceMandatory: 'true',
      pkceSupportPlain: 'false',
      bypassClientCredentials: 'true',
    },
  };
  const keys = await devportal('GET', `/applications/${app.applicationId}/oauth-keys`);
  let prod = keys.list.find((k) => k.keyType === 'PRODUCTION');
  if (!prod) {
    prod = await devportal('POST', `/applications/${app.applicationId}/generate-keys`, { ...keyConfig, validityTime: 3600 });
    log('production keys generated (public client, PKCE required)');
  } else {
    prod = await devportal('PUT', `/applications/${app.applicationId}/oauth-keys/${prod.keyMappingId}`, {
      ...prod,
      supportedGrantTypes: keyConfig.grantTypesToBeSupported,
      callbackUrl: keyConfig.callbackUrl,
      additionalProperties: { ...prod.additionalProperties, ...keyConfig.additionalProperties },
    });
    log('production keys updated (public client, PKCE required)');
  }

  step('5. Generated files (git-ignored)');
  mkdirSync(generatedDir, { recursive: true });
  writeFileSync(join(generatedDir, 'app.env'), [
    '# Written by infra/wso2/setup.mjs. Do not commit.',
    `WSO2_CLIENT_ID=${prod.consumerKey}`,
    `WSO2_CLIENT_SECRET=${prod.consumerSecret}`,
    `WSO2_TOKEN_URL=${MGMT}/oauth2/token`,
    `WSO2_GATEWAY_URL=https://localhost:8243${API.context}/${API.version}`,
    '',
  ].join('\n'));
  log(`${join('infra', 'wso2', 'generated', 'app.env')}`);

  // The Angular app (gateway mode) reads its OAuth client id at runtime. The id is not a secret
  // (public client + PKCE), but it differs per WSO2 install, so it is generated, not committed.
  writeFileSync(join(repoRoot, 'frontend', 'public', 'gateway-config.json'),
    `${JSON.stringify({ clientId: prod.consumerKey }, null, 2)}\n`);
  log(`${join('frontend', 'public', 'gateway-config.json')}`);

  const pem = execFileSync('docker', [
    'exec', 'portal-wso2am', 'sh', '-c',
    'keytool -exportcert -rfc -alias wso2carbon -storepass wso2carbon -keystore "$CARBON_HOME/repository/resources/security/wso2carbon.jks" 2>/dev/null || ' +
    '"$JAVA_HOME/bin/keytool" -exportcert -rfc -alias wso2carbon -storepass wso2carbon -keystore "/home/wso2carbon/wso2am-4.7.0/repository/resources/security/wso2carbon.jks"',
  ], { encoding: 'utf8' });
  if (!pem.includes('BEGIN CERTIFICATE')) throw new Error('Could not export the WSO2 signing certificate');
  writeFileSync(join(generatedDir, 'wso2-signing-cert.pem'), pem);
  log(`${join('infra', 'wso2', 'generated', 'wso2-signing-cert.pem')}`);

  step('Done');
  console.log(`Gateway:   https://localhost:8243${API.context}/${API.version}/requests`);
  console.log('Get a token: see docs/wso2-setup.md');
}

// ---------------------------------------------------------------------------------------------
// helpers

async function waitForServer() {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`${MGMT}/api/am/publisher/v4/settings`);
      if (res.ok) return;
    } catch {
      // not up yet
    }
    if (i === 0) console.log('Waiting for WSO2 on https://localhost:9443 ...');
    await sleep(5000);
  }
  throw new Error('WSO2 API Manager is not reachable on https://localhost:9443');
}

async function waitForDevportalApi(devportal, apiId) {
  // Publishing is asynchronous; the Developer Portal may need a moment to list the API.
  for (let i = 0; i < 20; i++) {
    try {
      await devportal('GET', `/apis/${apiId}`);
      return;
    } catch {
      await sleep(2000);
    }
  }
  throw new Error('API did not appear in the Developer Portal');
}

async function soap(operation, bodyXml) {
  const envelope =
    '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ser="http://service.ws.um.carbon.wso2.org">' +
    `<soapenv:Body><ser:${operation}>${bodyXml}</ser:${operation}></soapenv:Body></soapenv:Envelope>`;
  const res = await fetch(`${MGMT}/services/RemoteUserStoreManagerService`, {
    method: 'POST',
    headers: {
      Authorization: basic(ADMIN, ADMIN_PASSWORD),
      'Content-Type': 'text/xml;charset=UTF-8',
      SOAPAction: `urn:${operation}`,
    },
    body: envelope,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${operation} failed (HTTP ${res.status}): ${text.slice(0, 400)}`);
  return /<ns:return>([^<]*)<\/ns:return>/.exec(text)?.[1] ?? null;
}

async function registerClient(clientName) {
  return json(await fetch(`${MGMT}/client-registration/v0.17/register`, {
    method: 'POST',
    headers: { Authorization: basic(ADMIN, ADMIN_PASSWORD), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      callbackUrl: 'https://localhost',
      clientName,
      owner: ADMIN,
      grantType: 'password refresh_token client_credentials',
      saasApp: true,
    }),
  }), 'client registration');
}

async function passwordToken(client, username, password, scope) {
  const res = await json(await fetch(`${MGMT}/oauth2/token`, {
    method: 'POST',
    headers: { Authorization: basic(client.clientId, client.clientSecret), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', username, password, scope }),
  }), 'token');
  return res.access_token;
}

function rest(base, token) {
  return async (method, path, body) => {
    const res = await fetch(base + path, {
      method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return json(res, `${method} ${path}`);
  };
}

async function json(res, what) {
  const text = await res.text();
  if (!res.ok) throw new Error(`${what} failed (HTTP ${res.status}): ${text.slice(0, 600)}`);
  return text ? JSON.parse(text) : {};
}

function loadDotEnv(file) {
  if (!existsSync(file)) throw new Error(`${file} not found: copy .env.example to .env first`);
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m) values[m[1]] = m[2];
  }
  return { ...values, ...process.env };
}

function required(name) {
  if (!env[name]) throw new Error(`${name} is missing from .env (see .env.example)`);
  return env[name];
}

function basic(user, password) {
  return `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`;
}

function xml(value) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function step(title) {
  console.log(`\n== ${title}`);
}

function log(message) {
  console.log(`   ${message}`);
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
