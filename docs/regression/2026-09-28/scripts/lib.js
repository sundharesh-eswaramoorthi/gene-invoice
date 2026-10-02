// GeneInvoice E2E helper library (automation, regions, maker-checker, as-of).
//
//   const rt = require('../../lib.js');   // from a suites/<area>/ file
//
// Which environment it talks to comes from the environment variables up.sh writes to
// runtime/<area>/env.sh:  E2E_API, E2E_WEB, E2E_DB (and E2E_AREA). If only E2E_AREA is set, the
// values are read from runtime/<area>/env.json. Nothing here ever talks to any other port.
//
// Conventions
//  - Every API helper takes the caller's token first.
//  - api() never throws on an HTTP status. Setup helpers (createRegion, createCustomer, seedWorld, ...)
//    DO throw when the server refuses, because a broken fixture is not a test result.
//  - Writes that maker-checker may hold (createInvoice, createPayment, createPromise, cancelInvoice,
//    patchInvoice, ...) never throw: they return the raw response plus
//      .held           true on 202 PENDING_APPROVAL
//      .pendingChange  the pendingChange brief when held
//      .data           the saved DTO when applied (200), else null
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const os = require('os');
const { execFileSync } = require('child_process');

const HOME = __dirname;

// ---- environment ------------------------------------------------------------------------

function loadEnv() {
  let api = process.env.E2E_API;
  let web = process.env.E2E_WEB;
  let db = process.env.E2E_DB;
  const area = process.env.E2E_AREA;
  if ((!api || !web || !db) && area) {
    const f = path.join(HOME, 'runtime', area, 'env.json');
    if (fs.existsSync(f)) {
      const j = JSON.parse(fs.readFileSync(f, 'utf8'));
      api = api || j.api; web = web || j.web; db = db || j.db;
    }
  }
  return { API: api ? api.replace(/\/$/, '') : undefined, WEB: web ? (web.endsWith('/') ? web : web + '/') : undefined, DB: db, AREA: area };
}
const ENV = loadEnv();
const API = ENV.API;
const WEB = ENV.WEB;

function requireApi() {
  if (!API) throw new Error('E2E_API is not set. Run: source <e2e>/runtime/<area>/env.sh  (written by env/up.sh)');
  const port = Number(new URL(API).port);
  if (!(port >= 18100 && port <= 18199)) throw new Error(`E2E_API ${API} is outside the harness port range 18100-18199`);
}

const PASSWORD = 'Passw0rd!';

// ---- API core ---------------------------------------------------------------------------

/** Builds a query string. Arrays become comma lists (regionIds=1,2); undefined/null are dropped. */
function qs(query) {
  if (!query) return '';
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v)) {
      if (k === 'filter') v.forEach((x) => p.append(k, x)); // repeated filter=...
      else p.append(k, v.join(','));
    } else p.append(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

/**
 * Calls the API. Returns { status, ok, json, text, headers, ms }. Never throws on HTTP status;
 * a network failure comes back as status 0 with the error in `text`.
 * `query` is an object (see qs); `path` may already carry a query string.
 */
async function api(method, p, { token, body, query, headers = {}, raw = false } = {}) {
  requireApi();
  const url = API + p + (query ? (p.includes('?') ? '&' + qs(query).slice(1) : qs(query)) : '');
  const t0 = Date.now();
  let res;
  try {
    res = await fetch(url, {
      method,
      headers: {
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(body !== undefined && !raw ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
  } catch (e) {
    return { status: 0, ok: false, json: null, text: String(e && e.cause ? e.cause : e), headers: {}, ms: Date.now() - t0, url };
  }
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON (CSV export etc.) */ }
  return { status: res.status, ok: res.status >= 200 && res.status < 300, json, text, headers: Object.fromEntries(res.headers), ms: Date.now() - t0, url };
}

/** Throws unless the response is 2xx (or one of `allowed`). Returns r.json. For fixtures only. */
function need(r, what, allowed = null) {
  const good = allowed ? allowed.includes(r.status) : r.ok;
  if (!good) throw new Error(`${what} -> ${r.status} ${r.text && r.text.slice(0, 800)}`);
  return r.json;
}

/** Adds the view scope to a query: scoped({size: 50}, {regionIds: [2,3], asOf: '2026-09-20'}). */
function scoped(query = {}, { regionIds, asOf } = {}) {
  const q = { ...query };
  if (regionIds && regionIds.length) q.regionIds = [...regionIds];
  if (asOf) q.asOf = asOf;
  return q;
}

/**
 * Fetches every page of a list (page size 50 — the API accepts only 10, 20 or 50) and returns the first
 * page's response with `json.content` replaced by all rows. The envelope (regionIds, bookRegionIds, asOf,
 * totalElements, lockedFilters...) is the first page's. Stops at `maxPages`.
 */
async function list(token, p, query = {}, scope = {}, { maxPages = 40 } = {}) {
  const first = await api('GET', p, { token, query: scoped({ size: 50, page: 0, ...query }, scope) });
  if (!first.ok || !first.json || !Array.isArray(first.json.content)) return first;
  const rows = [...first.json.content];
  for (let pg = 1; pg < Math.min(first.json.totalPages || 1, maxPages); pg++) {
    const r = await api('GET', p, { token, query: scoped({ size: 50, ...query, page: pg }, scope) });
    if (!r.ok) return r;
    rows.push(...r.json.content);
  }
  first.json = { ...first.json, content: rows };
  return first;
}

// ---- auth -------------------------------------------------------------------------------

async function loginFull(username, password) {
  const r = await api('POST', '/api/auth/login', { body: { username, password } });
  if (r.status !== 200) throw new Error(`login ${username} -> ${r.status} ${r.text}`);
  return r.json; // {token, expiresInMs, user:{..., privileges, regions:[{id,code,level,scope,privileges}]}}
}
async function login(username, password = PASSWORD) {
  return (await loginFull(username, password)).token;
}
const adminToken = () => login('admin', 'admin123');
const cashierToken = () => login('cashier', 'cashier123');
async function me(token) { return need(await api('GET', '/api/auth/me', { token }), 'GET /api/auth/me'); }

/** A unique, prefixed name so repeated runs never collide. */
function uniq(prefix = 'e2e') {
  return `${prefix}-${Date.now().toString(36)}${crypto.randomBytes(2).toString('hex')}`;
}

/** Today (UTC) as yyyy-MM-dd, optionally shifted by `days`. */
function day(days = 0, from = new Date()) {
  const d = new Date(Date.UTC(from.getUTCFullYear(), from.getUTCMonth(), from.getUTCDate()));
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

// ---- roles, users, grants ---------------------------------------------------------------

async function roles(token) {
  return need(await list(token, '/api/roles'), 'GET /api/roles').content;
}
async function roleId(token, name) {
  const role = (await roles(token)).find((x) => x.name === name);
  if (!role) throw new Error(`role ${name} not found`);
  return role.id;
}
/** Creates the role if missing, or sets its privileges to exactly `privileges`. Returns the RoleDto. */
async function ensureRole(token, name, privileges, description = 'E2E role') {
  const existing = (await roles(token)).find((x) => x.name === name);
  if (existing) {
    return need(await api('PUT', `/api/roles/${existing.id}`, { token, body: { name, description, privileges } }), `PUT role ${name}`);
  }
  return need(await api('POST', '/api/roles', { token, body: { name, description, privileges } }), `POST role ${name}`);
}

/** Normalises grant rows: accepts {regionId, accessLevel|level, recordScope|scope}. */
function grantRows(rows = []) {
  return rows.map((g) => ({ regionId: g.regionId, level: g.accessLevel || g.level, scope: g.recordScope || g.scope || 'ALL' }));
}

async function findUser(token, username) {
  const r = await api('GET', '/api/users', { token, query: { size: 50, filter: `username:eq:${username}` } });
  const hit = (r.json?.content || []).find((u) => u.username === username);
  if (hit) return hit;
  // Fallback when the filter is not supported the way we assume: scan.
  const all = await list(token, '/api/users');
  return (all.json?.content || []).find((u) => u.username === username) || null;
}

/**
 * Creates a staff login with the given role. NOTE: a user created without `grants` holds NO region
 * (DESIGN §4.6 / §6.3) and so sees nothing regional; pass grants: [{regionId, accessLevel, recordScope}].
 * `username` may be fixed (e.g. 'nora'); otherwise a unique one is made from `prefix`.
 * Returns the UserDto plus {username, password}.
 */
async function createStaff(token, roleName, prefix = 'e2e', { username, password = PASSWORD, fullName, email, grants } = {}) {
  username = username || uniq(prefix);
  const body = {
    username, email: email || `${username}@e2e.local`, fullName: fullName || username.toUpperCase(),
    password, roleId: await roleId(token, roleName), active: true,
    regionGrants: grants ? grantRows(grants) : [],
  };
  const u = need(await api('POST', '/api/users', { token, body }), `create user ${username} (${roleName})`);
  return { ...u, username, password };
}

/** PUT /api/users/{id}/region-grants — replaces all the user's grants. Returns the raw response. */
async function setGrants(token, userId, rows) {
  return api('PUT', `/api/users/${userId}/region-grants`, { token, body: { grants: grantRows(rows) } });
}
async function getGrants(token, userId) {
  return need(await api('GET', `/api/users/${userId}/region-grants`, { token }), 'GET region-grants');
}

// ---- regions and thresholds -------------------------------------------------------------

async function regions(token) {
  return need(await list(token, '/api/regions'), 'GET /api/regions').content;
}
async function defaultRegion(token) {
  return (await regions(token)).find((r) => r.defaultRegion);
}
/** POST /api/regions (the creator is auto-granted APPROVE/ALL). Throws on refusal. */
async function createRegion(token, { code, name, active = true }) {
  return need(await api('POST', '/api/regions', { token, body: { code, name: name || code, active } }), `create region ${code}`);
}
async function getOrCreateRegion(token, { code, name, active = true }) {
  const hit = (await regions(token)).find((r) => r.code === code);
  return hit || createRegion(token, { code, name, active });
}

/**
 * PUT /api/regions/{id}/approval-thresholds. rows: [{operation, amount, windowHours?}] (amount null = not gated).
 * Operations: INVOICE_CREATE, INVOICE_CANCEL, INVOICE_TERMS, PAYMENT_RECORD, PROMISE_CREATE, PROMISE_UPDATE,
 * PROMISE_OVERRIDE, DISPUTE_APPROVE. Tightening -> 200; loosening -> 202 with a THRESHOLD_CHANGE pending change.
 * Returns the raw response (with .held / .pendingChange like the other held writes).
 */
async function setThresholds(token, regionId, rows) {
  return heldAware(await api('PUT', `/api/regions/${regionId}/approval-thresholds`, { token, body: { thresholds: rows } }));
}
async function getThresholds(token, regionId) {
  return api('GET', `/api/regions/${regionId}/approval-thresholds`, { token });
}

// ---- products, customers ----------------------------------------------------------------

async function ensureProduct(token, name = 'E2E Widget', price = 100) {
  const r = await list(token, '/api/products');
  const hit = (r.json?.content || []).find((p) => p.name === name);
  if (hit) return hit;
  return need(await api('POST', '/api/products', { token, body: { name, description: 'e2e', price, active: true } }), 'create product');
}

/**
 * Creates a customer with its self-service login. Accepts an options object
 * {name, regionId, paymentTerm, username, password, email, phone, address} or (legacy) a prefix string.
 * Returns the CustomerDto plus {username, password}.
 */
async function createCustomer(token, opts = {}) {
  if (typeof opts === 'string') opts = { prefix: opts };
  const username = opts.username || uniq(opts.prefix || 'cust');
  const password = opts.password || PASSWORD;
  const body = {
    name: opts.name || username, phone: opts.phone || '555-0100', email: opts.email || `${username}@e2e.local`,
    address: opts.address || '1 Test Way', paymentTerm: opts.paymentTerm, username, password, regionId: opts.regionId,
  };
  const c = need(await api('POST', '/api/customers', { token, body }), `create customer ${body.name}`);
  return { ...c, username, password };
}

/** POST /api/customers/{id}/pocs — pocType SALES | SUCCESS | COLLECTION. */
async function addCustomerPoc(token, customerId, pocType, userId, primary = true) {
  return need(await api('POST', `/api/customers/${customerId}/pocs`, { token, body: { pocType, userId, primary } }), `add ${pocType} POC`);
}

async function moveCustomerRegion(token, customerId, regionId, reason = 'e2e move') {
  const c = need(await api('GET', `/api/customers/${customerId}`, { token }), 'GET customer');
  return api('POST', `/api/customers/${customerId}/move-region`, { token, body: { regionId, version: c.version, reason } });
}

// ---- held-aware writes (B2) -------------------------------------------------------------

function heldAware(r) {
  r.held = r.status === 202 && r.json && r.json.status === 'PENDING_APPROVAL';
  r.pendingChange = r.held ? r.json.pendingChange : (r.json && r.json.pendingChange) || null;
  r.data = r.status === 200 ? r.json : null;
  return r;
}

/**
 * POST /api/invoices. Either pass `items` ([{productId, quantity, unitPrice}]) or `amount` (one line of
 * quantity 1 at that price, on the E2E product). Returns the held-aware response.
 */
async function createInvoice(token, { customerId, amount, items, productId, dueDate, paymentTerm, salesPocUserId, notes, invoiceDate, query } = {}) {
  if (!items) {
    const pid = productId || (await ensureProduct(token)).id;
    items = [{ productId: pid, quantity: 1, unitPrice: amount ?? 1000 }];
  }
  return heldAware(await api('POST', '/api/invoices', { token, query, body: { customerId, items, dueDate, paymentTerm, salesPocUserId, notes, invoiceDate } }));
}
async function patchInvoice(token, id, body) {
  return heldAware(await api('PATCH', `/api/invoices/${id}`, { token, body }));
}
async function cancelInvoice(token, id, body = {}) {
  return heldAware(await api('POST', `/api/invoices/${id}/cancel`, { token, body }));
}
/** POST /api/payments {customerId, amount, method, notes, invoiceIds, collectionPocUserId, promiseIds}. */
async function createPayment(token, { customerId, amount, method = 'BANK_TRANSFER', notes, invoiceIds, collectionPocUserId, promiseIds } = {}) {
  return heldAware(await api('POST', '/api/payments', { token, body: { customerId, amount, method, notes, invoiceIds, collectionPocUserId, promiseIds } }));
}
/** POST /api/promises {customerId, amount, promisedDate, collectionPocUserId, notes, invoiceIds, assignees}. */
async function createPromise(token, { customerId, amount, promisedDate = day(7), collectionPocUserId, notes, invoiceIds, assignees } = {}) {
  return heldAware(await api('POST', '/api/promises', { token, body: { customerId, amount, promisedDate, collectionPocUserId, notes, invoiceIds, assignees } }));
}
/** POST /api/disputes {targetType: INVOICE|PAYMENT|..., targetId, reason, proposedChangeJson}. */
async function createDispute(token, { targetType, targetId, reason = 'e2e dispute', proposedChangeJson } = {}) {
  return api('POST', '/api/disputes', { token, body: { targetType, targetId, reason, proposedChangeJson } });
}

// ---- approvals --------------------------------------------------------------------------

const approvals = {
  list: (token, query = {}, scope = {}) => api('GET', '/api/approvals', { token, query: scoped({ size: 50, ...query }, scope) }),
  get: (token, id, scope = {}) => api('GET', `/api/approvals/${id}`, { token, query: scoped({}, scope) }),
  forRecord: (token, entityType, entityId, scope = {}) =>
    api('GET', '/api/approvals/for-record', { token, query: scoped({ entityType, entityId }, scope) }),
  summary: (token, scope = {}) => api('GET', '/api/approvals/summary', { token, query: scoped({}, scope) }),
  approve: (token, id, note) => api('POST', `/api/approvals/${id}/approve`, { token, body: note ? { note } : {} }),
  reject: (token, id, note = 'rejected by e2e') => api('POST', `/api/approvals/${id}/reject`, { token, body: { note } }),
  withdraw: (token, id) => api('POST', `/api/approvals/${id}/withdraw`, { token, body: {} }),
};

// ---- tasks, assignees -------------------------------------------------------------------

/** EmailToken builders — the same role-at-a-level model the email To field uses. */
const tok = {
  role: (role, level = 'CUSTOMER') => ({ type: 'ROLE', role, level }), // level CUSTOMER | RECORD
  user: (userId) => ({ type: 'USER', userId }),
  customer: () => ({ type: 'CUSTOMER' }),
};

async function createTask(token, { entityType, entityId, title = uniq('task'), description = null, dueDate = day(2), assignees = [] } = {}) {
  return api('POST', '/api/tasks', { token, body: { entityType, entityId, title, description, dueDate, assignees } });
}
const tasks = {
  list: (token, query = {}, scope = {}) => api('GET', '/api/tasks', { token, query: scoped({ size: 50, ...query }, scope) }),
  mine: (token, query = {}, scope = {}) => api('GET', '/api/tasks/mine', { token, query: scoped({ size: 50, ...query }, scope) }),
  mineCount: (token) => api('GET', '/api/tasks/mine/count', { token }),
  setStatus: (token, id, status, version) => api('POST', `/api/tasks/${id}/status`, { token, body: { status, version } }),
};

// ---- automation -------------------------------------------------------------------------

/**
 * A RuleRequest with sensible defaults (DESIGN §6.14). Example:
 *   rule({ name, trigger: {kind:'ENTITY_CREATED', entityType:'INVOICE'}, regionIds:[north.id],
 *          condition: {op:'AND', children:[{filter:'total:gt:500'}]},
 *          actions: [action.task({title:'Chase {{Invoice.Number}}', assignees:[tok.user(nora.id)]})] })
 */
function rule({ name = uniq('rule'), description = null, enabled = true, trigger, regionIds = [], condition = null, actions = [], maxRecordsPerRun, version, confirmOwnershipChange } = {}) {
  return { name, description, enabled, trigger, regionIds, condition, actions, maxRecordsPerRun, version, confirmOwnershipChange };
}
const action = {
  task: (o = {}) => ({ key: crypto.randomUUID(), type: 'CREATE_TASK', repeat: 'EVERY_TIME', title: 'E2E task', description: null, due: { base: 'TODAY', offsetDays: 2 }, assignees: [], onlyIfNoOpenTask: false, ...o }),
  email: (o = {}) => ({ key: crypto.randomUUID(), type: 'SEND_EMAIL', repeat: 'EVERY_TIME', from: null, fallbackToOwner: true, to: [tok.customer()], subject: 'E2E subject', body: 'E2E body', ...o }),
  promise: (o = {}) => ({ key: crypto.randomUUID(), type: 'CREATE_PROMISE', repeat: 'ONCE_PER_RECORD', amount: { source: 'FIXED', value: 100 }, promisedDate: { base: 'TODAY', offsetDays: 7 }, collectionPoc: null, assignees: [], notes: 'e2e', linkRecord: true, onlyIfNoOpenPromise: true, ...o }),
  dispute: (o = {}) => ({ key: crypto.randomUUID(), type: 'CREATE_DISPUTE', repeat: 'ONCE_PER_RECORD', reason: 'E2E review', proposedChange: 'none', assignees: [], ...o }),
};
const automation = {
  create: (token, body) => api('POST', '/api/automation/rules', { token, body }),
  update: (token, id, body) => api('PUT', `/api/automation/rules/${id}`, { token, body }),
  get: (token, id, scope = {}) => api('GET', `/api/automation/rules/${id}`, { token, query: scoped({}, scope) }),
  list: (token, query = {}, scope = {}) => api('GET', '/api/automation/rules', { token, query: scoped({ size: 50, ...query }, scope) }),
  enable: (token, id, body = {}) => api('POST', `/api/automation/rules/${id}/enable`, { token, body }),
  disable: (token, id) => api('POST', `/api/automation/rules/${id}/disable`, { token, body: {} }),
  remove: (token, id) => api('DELETE', `/api/automation/rules/${id}`, { token }),
  /** POST /rules/{id}/run {recordIds?, runAt?} -> 202 {runId, eventId, status, scheduledFor} */
  run: (token, id, body = {}) => api('POST', `/api/automation/rules/${id}/run`, { token, body }),
  preview: (token, body, scope = {}) => api('POST', '/api/automation/rules/preview', { token, body, query: scoped({}, scope) }),
  runs: (token, ruleId, query = {}) => api('GET', `/api/automation/rules/${ruleId}/runs`, { token, query: { size: 50, ...query } }),
  runById: (token, runId) => api('GET', `/api/automation/runs/${runId}`, { token }),
  effects: (token, runId) => api('GET', `/api/automation/runs/${runId}/effects`, { token, query: { size: 50 } }),
  health: (token) => api('GET', '/api/automation/health', { token }),
  placeholders: (token, entityType) => api('GET', '/api/automation/placeholders', { token, query: { entityType } }),
};
const RUN_DONE = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED', 'CANCELLED', 'DONE']);
/** Polls GET /runs/{id} until its status leaves QUEUED/RUNNING. Returns the RunDto, or null on timeout. */
async function waitForRun(token, runId, { timeoutMs = 60000, intervalMs = 1000 } = {}) {
  return waitFor(async () => {
    const r = await automation.runById(token, runId);
    return r.status === 200 && r.json && (RUN_DONE.has(r.json.status) || !/QUEUED|RUNNING|PENDING/.test(r.json.status)) ? r.json : null;
  }, { timeoutMs, intervalMs });
}

/** GET /api/emails?entityType&entityId — the persisted Email rows for a record (mail transport is none). */
async function emailsFor(token, entityType, entityId) {
  return api('GET', '/api/emails', { token, query: { entityType, entityId, size: 50 } });
}

// ---- polling ----------------------------------------------------------------------------

/**
 * Polls `fn` (sync or async) until it returns a truthy value; returns that value, or null on timeout.
 * Errors thrown by fn are remembered and retried. Use for anything the outbox consumer does
 * asynchronously (poll interval 2 s, schedule tick 30 s).
 */
async function waitFor(fn, { timeoutMs = 30000, intervalMs = 1000 } = {}) {
  const end = Date.now() + timeoutMs;
  let lastError = null;
  for (;;) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) { lastError = e; }
    if (Date.now() >= end) {
      if (lastError) waitFor.lastError = lastError;
      return null;
    }
    await sleep(intervalMs);
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- database (this area's e2e_* database only) -----------------------------------------

/**
 * Runs SQL against this area's database (E2E_DB, must start with e2e_) inside container gi-e2e-db.
 * Returns rows as arrays of strings (tab-separated psql -At output). For reading persisted rows
 * (emails, outbox events, history) and for ageWorld. Never point this at any other database.
 */
function sql(query, { db = ENV.DB } = {}) {
  if (!db || !/^e2e_[a-z0-9_]+$/.test(db)) throw new Error(`sql(): E2E_DB must be an e2e_* database (got ${db})`);
  const out = execFileSync('docker', ['exec', '-i', 'gi-e2e-db', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'e2e', '-d', db, '-At', '-F', '\t', '-c', query], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return out.split('\n').filter((l) => l.length).map((l) => l.split('\t'));
}

/**
 * Moves the whole world back in time by `days`: every timestamp and date column in every table of this
 * area's database (records, history versions, audit, outbox, history_meta) is shifted by -days.
 * After ageWorld(10) everything created so far looks as if it happened 10 days ago, so as-of reads for
 * the last 9 days see it, and new changes made now are "after" it. Invoice due dates move too, so
 * relative facts (days overdue) move with them. Schedules' next-run times move back as well: a
 * scheduled rule may catch up on its next tick. Call it before the change you want to observe as-of.
 */
function ageWorld(days, { db = ENV.DB, skipTables = [] } = {}) {
  if (!Number.isInteger(days) || days <= 0) throw new Error('ageWorld(days): days must be a positive integer');
  const cols = sql(`select table_name, column_name, data_type from information_schema.columns
    where table_schema='public' and data_type in ('timestamp with time zone','timestamp without time zone','date')
    order by table_name`, { db });
  const byTable = {};
  for (const [t, c] of cols) {
    if (skipTables.includes(t)) continue;
    (byTable[t] = byTable[t] || []).push(c);
  }
  const stmts = Object.entries(byTable).map(([t, cs]) =>
    `update "${t}" set ${cs.map((c) => `"${c}" = "${c}" - interval '${days} days'`).join(', ')};`);
  sql(`begin; ${stmts.join(' ')} commit;`, { db });
  return Object.keys(byTable);
}

// ---- UI (Flutter web, CanvasKit) --------------------------------------------------------

/** The Chromium playwright-core expects, from ~/Library/Caches/ms-playwright (override: E2E_CHROMIUM). */
function chromiumPath() {
  if (process.env.E2E_CHROMIUM) return process.env.E2E_CHROMIUM;
  const cache = path.join(os.homedir(), 'Library', 'Caches', 'ms-playwright');
  let rev = null;
  try { rev = require('playwright-core/browsers.json').browsers.find((b) => b.name === 'chromium').revision; } catch { /* ignore */ }
  const dirs = fs.existsSync(cache) ? fs.readdirSync(cache).filter((d) => /^chromium-\d+$/.test(d)) : [];
  dirs.sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
  const ordered = rev ? [`chromium-${rev}`, ...dirs.filter((d) => d !== `chromium-${rev}`)] : dirs;
  for (const d of ordered) {
    for (const rel of [
      'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-mac/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-mac-arm64/Chromium.app/Contents/MacOS/Chromium',
      'chrome-mac/Chromium.app/Contents/MacOS/Chromium',
    ]) {
      const p = path.join(cache, d, rel);
      if (fs.existsSync(p)) return p;
    }
  }
  throw new Error(`No cached Chromium under ${cache}; set E2E_CHROMIUM`);
}

/**
 * Opens the web app headless. Pass `token` (signed in via localStorage), or `username`/`password`
 * (signed in through the API, then the token is planted), or neither (login screen).
 * `path` is an in-app route such as '/invoices' (hash routing). Collects every API response >= 400
 * (apiErrors) and every uncaught page error (pageErrors); `requests` lists every API call made.
 */
async function openApp({ token, username, password, width = 1440, height = 900, path: route = null, bootWaitMs = 6000 } = {}) {
  if (!WEB) throw new Error('E2E_WEB is not set');
  const { chromium } = require('playwright-core');
  if (!token && username) token = await login(username, password || PASSWORD);
  const browser = await chromium.launch({ executablePath: chromiumPath(), headless: true });
  const ctx = await browser.newContext({ viewport: { width, height } });
  if (token) {
    await ctx.addInitScript((t) => localStorage.setItem('flutter.gene_invoice_token', JSON.stringify(t)), token);
  }
  const page = await ctx.newPage();
  const apiErrors = [];
  const pageErrors = [];
  const requests = [];
  page.on('response', (r) => {
    if (!r.url().startsWith(API)) return;
    const entry = { status: r.status(), method: r.request().method(), url: decodeURIComponent(r.url().slice(API.length)) };
    requests.push(entry);
    if (r.status() >= 400) apiErrors.push(entry);
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.goto(WEB + (route ? `#${route}` : ''));
  await waitForFlutter(page, bootWaitMs);
  await enableSemantics(page);
  return { browser, ctx, page, apiErrors, pageErrors, requests, close: () => browser.close() };
}

/** Waits until Flutter has rendered (flt-glass-pane / flutter-view present), then a settle delay. */
async function waitForFlutter(page, settleMs = 6000) {
  try {
    await page.waitForSelector('flutter-view, flt-glass-pane', { timeout: 30000, state: 'attached' });
  } catch { /* fall through to the settle wait */ }
  await page.waitForTimeout(settleMs);
}

/** Turns on Flutter's accessibility tree (idempotent). */
async function enableSemantics(page) {
  await page.evaluate(() => {
    const ph = document.querySelector('flt-semantics-placeholder');
    if (ph) ph.click();
  });
  await page.waitForTimeout(800);
}

/** Signs in through the login screen. */
async function uiLogin(page, username, password) {
  await fillField(page, 'Username', username);
  await fillField(page, 'Password', password);
  await tap(page, 'Sign in', { wait: 4000 });
  await enableSemantics(page);
}

/** In-app navigation by route, e.g. go(page, '/invoices'). */
async function go(page, route, wait = 3500) {
  const hash = route.startsWith('#') ? route.slice(1) : route;
  await page.evaluate((h) => { location.hash = h; }, hash);
  await page.waitForTimeout(wait);
  await enableSemantics(page);
}

/** Every labelled accessibility node with its role, label, text, on-screen centre and size. */
async function semantics(page) {
  return page.$$eval('flt-semantics, flt-semantics input, flt-semantics textarea', (els) => els.map((e, i) => {
    e.setAttribute('data-rt', String(i));
    const r = e.getBoundingClientRect();
    const isField = e.tagName === 'INPUT' || e.tagName === 'TEXTAREA';
    return {
      i,
      tag: e.tagName.toLowerCase(),
      role: e.getAttribute('role') || (isField ? 'textbox' : null),
      label: e.getAttribute('aria-label') || (isField ? (e.getAttribute('aria-label') || e.closest('flt-semantics')?.getAttribute('aria-label')) : null),
      text: isField ? (e.value || '') : (e.querySelector('flt-semantics') ? '' : (e.textContent || '').trim()),
      checked: e.getAttribute('aria-checked'),
      selected: e.getAttribute('aria-selected'),
      expanded: e.getAttribute('aria-expanded'),
      disabled: e.getAttribute('aria-disabled') === 'true',
      x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
      w: Math.round(r.width), h: Math.round(r.height),
    };
  }).filter((n) => (n.label || n.text || n.tag !== 'flt-semantics') && n.w > 0 && n.h > 0));
}

/** All visible label/text strings, split on newlines — handy for "is X on screen" assertions. */
async function screenText(page) {
  const nodes = await semantics(page);
  return [...new Set(nodes.flatMap((n) => `${n.label || ''}\n${n.text || ''}`.split('\n').map((s) => s.trim())).filter(Boolean))];
}

function matches(n, m) {
  const s = `${n.label || ''} ${n.text || ''}`.trim();
  if (m instanceof RegExp) return m.test(n.label || '') || m.test(n.text || '') || m.test(s);
  return n.label === m || n.text === m || s === m;
}

/** Finds a node by label/text (string = exact, RegExp = test). Throws listing what is on screen. */
async function find(page, matcher, { role, nth = 0 } = {}) {
  const nodes = await semantics(page);
  const hits = nodes.filter((n) => matches(n, matcher) && (!role || n.role === role));
  if (!hits[nth]) {
    throw new Error(`No element matching ${matcher}${role ? ` (role ${role})` : ''}. On screen: `
      + nodes.map((n) => `[${n.role || n.tag}] ${n.label || n.text}`).join(' | ').slice(0, 2500));
  }
  return hits[nth];
}
async function exists(page, matcher, opts = {}) {
  try { await find(page, matcher, opts); return true; } catch { return false; }
}

/** Taps a button, tab, chip, menu item or checkbox by its label/text (dispatches a click on the node). */
async function tap(page, matcher, opts = {}) {
  const n = await find(page, matcher, opts);
  await page.locator(`[data-rt="${n.i}"]`).first().dispatchEvent('click');
  await page.waitForTimeout(opts.wait ?? 1500);
  await enableSemantics(page);
  return n;
}

/** A real mouse click at a node's centre (use when dispatchEvent is not enough, e.g. popup menus). */
async function press(page, matcher, opts = {}) {
  const n = await find(page, matcher, opts);
  await clickAt(page, n.x, n.y, opts.wait ?? 1500);
  return n;
}

/** A real mouse click — needed for links inside merged tiles, whose semantics nodes are not separate. */
async function clickAt(page, x, y, wait = 1500) {
  await page.mouse.click(x, y);
  await page.waitForTimeout(wait);
  await enableSemantics(page);
}

/** Types into whatever field has focus. Click the field first (clickAt / fillField). clear = select-all + delete. */
async function typeText(page, text, { clear = false } = {}) {
  if (clear) {
    await page.keyboard.press('Meta+A');
    await page.keyboard.press('Backspace');
  }
  await page.keyboard.type(String(text), { delay: 20 });
}

/** Clicks the text field whose label matches, then types (clearing first). */
async function fillField(page, matcher, text, { clear = true } = {}) {
  const nodes = await semantics(page);
  const hit = nodes.find((n) => (n.role === 'textbox' || n.tag === 'input' || n.tag === 'textarea') && matches(n, matcher))
    || nodes.find((n) => matches(n, matcher));
  if (!hit) throw new Error(`No field matching ${matcher}. On screen: ${nodes.map((n) => `[${n.role || n.tag}] ${n.label || n.text}`).join(' | ').slice(0, 2000)}`);
  await page.mouse.click(hit.x, hit.y);
  await page.waitForTimeout(400);
  await typeText(page, text, { clear });
  await page.waitForTimeout(300);
  return hit;
}

/** Saves a screenshot to <dir>/<name>.png (dir is created) and returns the path. Read it to see the screen. */
async function shot(page, dir, name) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${name}.png`);
  await page.screenshot({ path: file });
  return file;
}

/** The app bar's region selector: returns its node (tooltip "Regions: <label>"), or null when hidden (<=1 region). */
async function regionSelector(page) {
  const nodes = await semantics(page);
  return nodes.find((n) => /^Regions: /.test(n.label || '') || /^Regions: /.test(n.text || '')) || null;
}

/**
 * Opens the region selector and toggles the given region(s) by their menu text ("NORTH · North"), or
 * picks 'All my regions'. Each toggle closes the menu, so it is reopened per item.
 */
async function chooseRegions(page, items) {
  for (const item of [].concat(items)) {
    const open = (await semantics(page)).some((n) => n.role === 'menuitemcheckbox');
    if (!open) {
      const sel = await regionSelector(page);
      if (!sel) throw new Error('Region selector not on screen (user has <= 1 region?)');
      await clickAt(page, sel.x, sel.y, 1200);
    }
    await press(page, item instanceof RegExp ? item : new RegExp(`^${escapeRe(item)}`), { wait: 2500 });
  }
}

/**
 * Closes whatever popup menu / dialog barrier is open by tapping Flutter's "Dismiss" / "Dismiss menu"
 * barrier node (keyboard Escape does not reach the canvas reliably). Returns true when one was closed.
 */
async function dismiss(page) {
  const nodes = await semantics(page);
  const barrier = nodes.find((n) => /^Dismiss( menu)?$/.test(n.label || n.text || ''));
  if (!barrier) return false;
  // The barrier covers the screen; click a corner far from the menu itself.
  await clickAt(page, 5, Math.max(5, (page.viewportSize()?.height || 900) - 5), 1000);
  return true;
}
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The app bar's as-of chip ("Today" / "As of 30 Jun 2026"), or null (customer logins do not get it). */
async function asOfChip(page) {
  const nodes = await semantics(page);
  return nodes.find((n) => /^(Today|As of )/m.test(n.text || '') && /earlier day|another day/.test(n.label || ''))
    || nodes.find((n) => /(^|\n)(Today|As of [^\n]+)$/.test(n.text || '') || /(^|\n)(Today|As of [^\n]+)$/.test(n.label || ''))
    || null;
}

/**
 * Picks an as-of date through the app bar chip: opens the Material date picker, switches it to text
 * input, types the date (MM/DD/YYYY, en-US) and confirms with "View". `date` is 'yyyy-mm-dd'.
 * Returns the chip text afterwards. Pass null to go back to today (the chip's delete button).
 */
async function chooseAsOf(page, date) {
  if (date === null) {
    await tap(page, /Back to today/, { wait: 2500 });
    return (await asOfChip(page))?.text || null;
  }
  const chip = await asOfChip(page);
  if (!chip) throw new Error('As-of chip not on screen');
  await clickAt(page, chip.x, chip.y, 2000);
  await tap(page, /Switch to input/, { wait: 800 });
  const [y, m, d] = date.split('-');
  await fillField(page, /Enter Date|mm\/dd\/yyyy/i, `${m}/${d}/${y}`);
  await tap(page, 'View', { wait: 3000 });
  const after = await asOfChip(page);
  return after ? `${after.label || ''} ${after.text || ''}`.trim() : null;
}

// ---- results ----------------------------------------------------------------------------

/**
 * Collects cases in the README results format and writes runs/r<round>/<area>.json.
 *   const R = rt.results({area: 'b1-regions', round: 1});
 *   R.rec({id:'B1-01', feature:'Region lists', kind:'API', prd:'B1', title, ok: bool | status, steps, expected, actual, evidence, screenshot, codeRef, severity});
 *   R.save();
 */
function results({ area = ENV.AREA, round = Number(process.env.E2E_ROUND || 1) } = {}) {
  const cases = [];
  const file = path.join(HOME, 'runs', `r${round}`, `${area}.json`);
  const shotsDir = path.join(HOME, 'runs', `r${round}`, 'shots', area);
  function rec(o) {
    const status = o.status || (o.ok ? 'PASS' : 'FAIL');
    const c = {
      id: o.id, feature: o.feature || '', kind: o.kind || 'API', prd: o.prd || '', title: o.title || '', status,
      severity: status === 'FAIL' ? (o.severity || 'medium') : '',
      steps: o.steps || '', expected: o.expected || '', actual: typeof o.actual === 'string' ? o.actual : JSON.stringify(o.actual),
      evidence: o.evidence || '', screenshot: o.screenshot || '', codeRef: o.codeRef || '',
    };
    const i = cases.findIndex((x) => x.id === c.id);
    if (i >= 0) cases[i] = c; else cases.push(c);
    console.log(`[${status}] ${c.id} ${c.title}`);
    return c;
  }
  function save() {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ area, round, env: { api: API, web: WEB }, cases }, null, 2));
    const n = (s) => cases.filter((c) => c.status === s).length;
    console.log(`${file}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED`);
    return file;
  }
  return { cases, rec, save, file, shotsDir };
}

// ---- the standard world -----------------------------------------------------------------

/** Every regional privilege: a role holding them all lets the grant alone decide what a user can do. */
const REGIONAL_PRIVILEGES = [
  'CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'INVOICE_VIEW', 'INVOICE_MANAGE', 'PAYMENT_VIEW', 'PAYMENT_MANAGE',
  'DISPUTE_CREATE', 'DISPUTE_VIEW', 'DISPUTE_MANAGE', 'AUDIT_VIEW', 'POC_VIEW', 'POC_ASSIGN',
  'POC_ASSIGNABLE_SALES', 'POC_ASSIGNABLE_SUCCESS', 'POC_ASSIGNABLE_COLLECTION', 'SCOPE_OVERRIDE',
  'PROMISE_VIEW', 'PROMISE_MANAGE', 'PROMISE_OVERRIDE', 'EMAIL_VIEW', 'EMAIL_SEND', 'DOCUMENT_VIEW',
  'DOCUMENT_MANAGE', 'TASK_VIEW', 'TASK_MANAGE', 'CHANGE_APPROVE', 'APPROVAL_THRESHOLD_MANAGE',
];
/** The role the standard world's staff hold: all regional privileges + a few global ones (no user/role admin). */
const REGIONAL_STAFF_PRIVILEGES = [
  ...REGIONAL_PRIVILEGES, 'PRODUCT_VIEW', 'NOTIFICATION_VIEW', 'EXPORT_DATA', 'REGION_VIEW',
  'AUTOMATION_VIEW', 'AUTOMATION_MANAGE',
];

/**
 * Builds the standard world (idempotent for regions, role and users; customers/records are new each call).
 *
 * Regions: DEFAULT (seeded) + NORTH, SOUTH, WEST (admin is auto-granted APPROVE/ALL in each).
 * Role E2E_REGIONAL = every regional privilege + PRODUCT_VIEW, NOTIFICATION_VIEW, EXPORT_DATA, REGION_VIEW,
 *   AUTOMATION_VIEW/MANAGE, so the GRANT decides what each user may do in each region.
 * Users (password Passw0rd!):
 *   nora  E2E_REGIONAL: MANAGE/ALL North, READ/ALL South, nothing in West
 *   sam   E2E_REGIONAL: APPROVE/ALL South, MANAGE/ALL North
 *   bob   E2E_REGIONAL: MANAGE/BOOK North (sales POC on n1's invoices, collection POC on customer n1)
 *   wendy E2E_REGIONAL: MANAGE/ALL West only
 *   cust  customer login of customer s1 (South)
 * Customers (2 per region; names are unique per call): n1,n2 (North), s1,s2 (South), w1,w2 (West), d1 (DEFAULT)
 *   each with a primary COLLECTION POC (n1 bob, n2 nora, s* sam, w* wendy, d1 admin) and 2 invoices (amounts 1000 and 2500; the second one due in the past for n1/s1/w1) and 1 payment
 *   of 500 allocated to the first invoice. Each invoice's Sales POC is the same person as the Collection POC.
 * Thresholds in South: INVOICE_CREATE 10000, PAYMENT_RECORD 5000, INVOICE_CANCEL 5000, PROMISE_CREATE 5000
 *   (window 24h); set AFTER the seed records so none of them is held.
 *
 * Returns { regions:{DEFAULT,NORTH,SOUTH,WEST}, users:{nora,sam,bob,wendy,cust}, tokens:{admin,nora,sam,bob,wendy,cust},
 *           customers:{n1,n2,s1,s2,w1,w2,d1}, invoices:{n1:[..],...}, payments:{n1:[..],...}, product, role, thresholds }
 */
async function seedWorld(adminTok, { thresholds = true, tag } = {}) {
  const t = adminTok || await adminToken();
  const T = tag || Date.now().toString(36).slice(-5);
  const out = { regions: {}, users: {}, tokens: { admin: t }, customers: {}, invoices: {}, payments: {} };

  out.regions.DEFAULT = await defaultRegion(t);
  out.regions.NORTH = await getOrCreateRegion(t, { code: 'NORTH', name: 'North' });
  out.regions.SOUTH = await getOrCreateRegion(t, { code: 'SOUTH', name: 'South' });
  out.regions.WEST = await getOrCreateRegion(t, { code: 'WEST', name: 'West' });
  const R = out.regions;

  out.role = await ensureRole(t, 'E2E_REGIONAL', REGIONAL_STAFF_PRIVILEGES, 'E2E: every regional privilege; grants decide');
  out.product = await ensureProduct(t);

  const staff = [
    ['nora', 'E2E_REGIONAL', 'Nora North', [{ regionId: R.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.SOUTH.id, accessLevel: 'READ', recordScope: 'ALL' }]],
    ['sam', 'E2E_REGIONAL', 'Sam South', [{ regionId: R.SOUTH.id, accessLevel: 'APPROVE', recordScope: 'ALL' }, { regionId: R.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]],
    ['bob', 'E2E_REGIONAL', 'Bob Book', [{ regionId: R.NORTH.id, accessLevel: 'MANAGE', recordScope: 'BOOK' }]],
    ['wendy', 'E2E_REGIONAL', 'Wendy West', [{ regionId: R.WEST.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]],
  ];
  for (const [username, roleName, fullName, grants] of staff) {
    let u = await findUser(t, username);
    if (!u) u = await createStaff(t, roleName, username, { username, fullName, grants });
    else {
      if (u.role !== roleName) {
        need(await api('PUT', `/api/users/${u.id}`, { token: t, body: { roleId: await roleId(t, roleName), password: PASSWORD, active: true } }), `set role ${username}`);
      }
      need(await setGrants(t, u.id, grants), `set grants ${username}`);
      u = { ...u, username, password: PASSWORD };
    }
    out.users[username] = { ...u, password: PASSWORD, grants };
  }

  // Customers, invoices, payments. Created by admin, who holds APPROVE/ALL everywhere.
  const plan = [['n1', 'NORTH'], ['n2', 'NORTH'], ['s1', 'SOUTH'], ['s2', 'SOUTH'], ['w1', 'WEST'], ['w2', 'WEST'], ['d1', 'DEFAULT']];
  for (const [key, code] of plan) {
    const isCust = key === 's1';
    let username = `${key}-${T}`;
    if (isCust && !(await findUser(t, 'cust'))) username = 'cust';
    const c = await createCustomer(t, { name: `${code[0]}${code.slice(1).toLowerCase()} ${key.toUpperCase()} ${T}`, regionId: R[code].id, username, paymentTerm: 'NET_30' });
    out.customers[key] = c;
    // Every customer gets a primary Collection POC, and every invoice a Sales POC (both required): the same
    // person, someone granted in the customer's region.
    const collector = { n1: out.users.bob.id, n2: out.users.nora.id, s1: out.users.sam.id, s2: out.users.sam.id,
      w1: out.users.wendy.id, w2: out.users.wendy.id, d1: (await me(t)).id }[key];
    await addCustomerPoc(t, c.id, 'COLLECTION', collector, true);
    c.collectionPocUserId = collector;
    const invs = [];
    const overdue = key.endsWith('1');
    for (const [amount, due, invoiceDate] of [[1000, day(30), undefined], [2500, overdue ? day(-10) : day(20), overdue ? `${day(-40)}T09:00:00Z` : undefined]]) {
      const r = await createInvoice(t, { customerId: c.id, amount, dueDate: due, invoiceDate, paymentTerm: 'CUSTOM', salesPocUserId: collector });
      need(r, `invoice for ${key}`, [200]);
      invs.push(r.json);
    }
    out.invoices[key] = invs;
    const p = await createPayment(t, { customerId: c.id, amount: 500, invoiceIds: [invs[0].id], collectionPocUserId: collector });
    need(p, `payment for ${key}`, [200]);
    out.payments[key] = [p.json];
  }
  out.users.cust = { username: out.customers.s1.username, password: PASSWORD, customerId: out.customers.s1.id };

  if (thresholds) {
    const th = await setThresholds(t, R.SOUTH.id, [
      { operation: 'INVOICE_CREATE', amount: 10000, windowHours: 24 },
      { operation: 'PAYMENT_RECORD', amount: 5000, windowHours: 24 },
      { operation: 'INVOICE_CANCEL', amount: 5000, windowHours: 24 },
      { operation: 'PROMISE_CREATE', amount: 5000, windowHours: 24 },
    ]);
    out.thresholds = need(th, 'set South thresholds', [200, 202]);
  }

  for (const name of ['nora', 'sam', 'bob', 'wendy']) out.tokens[name] = await login(name, PASSWORD);
  out.tokens.cust = await login(out.users.cust.username, PASSWORD);
  return out;
}

module.exports = {
  HOME, ENV, API, WEB, PASSWORD,
  // api
  api, need, qs, scoped, list, login, loginFull, adminToken, cashierToken, me, uniq, day, sleep, waitFor,
  roles, roleId, ensureRole, findUser, createStaff, setGrants, getGrants, grantRows,
  regions, defaultRegion, createRegion, getOrCreateRegion, setThresholds, getThresholds,
  ensureProduct, createCustomer, addCustomerPoc, moveCustomerRegion,
  heldAware, createInvoice, patchInvoice, cancelInvoice, createPayment, createPromise, createDispute,
  approvals, tok, createTask, tasks, rule, action, automation, waitForRun, emailsFor,
  sql, ageWorld,
  REGIONAL_PRIVILEGES, REGIONAL_STAFF_PRIVILEGES, seedWorld,
  // ui
  chromiumPath, openApp, waitForFlutter, enableSemantics, uiLogin, go, semantics, screenText, find, exists,
  tap, press, clickAt, typeText, fillField, shot, regionSelector, chooseRegions, dismiss, asOfChip, chooseAsOf,
  // results
  results,
};
