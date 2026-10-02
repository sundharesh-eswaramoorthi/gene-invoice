// A small API client bound to one backend (baseline on 18108 or new on 18107), a "view snapshot" of what one
// user sees (lists, counts, summaries, filters, dashboard, details), and a subset diff: every field the
// baseline returns must come back identical from the upgraded build (fields the new build adds are ignored).
const { qs } = require('../../lib.js');

function client(port) {
  if (!(port >= 18100 && port <= 18199)) throw new Error(`port ${port} outside harness range`);
  const base = `http://localhost:${port}`;
  async function api(method, p, { token, body, query } = {}) {
    const url = base + p + (query ? qs(query) : '');
    let res;
    try {
      res = await fetch(url, {
        method,
        headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch (e) {
      return { status: 0, ok: false, json: null, text: String(e), headers: {} };
    }
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: res.status, ok: res.ok, json, text, headers: Object.fromEntries(res.headers) };
  }
  async function login(username, password) {
    const r = await api('POST', '/api/auth/login', { body: { username, password } });
    return r;
  }
  async function listAll(token, p, query = {}) {
    const first = await api('GET', p, { token, query: { size: 50, page: 0, ...query } });
    if (!first.ok || !first.json || !Array.isArray(first.json.content)) return first;
    const rows = [...first.json.content];
    for (let pg = 1; pg < Math.min(first.json.totalPages || 1, 60); pg++) {
      const r = await api('GET', p, { token, query: { size: 50, ...query, page: pg } });
      if (!r.ok) return r;
      rows.push(...r.json.content);
    }
    first.json = { ...first.json, content: rows };
    return first;
  }
  return { base, port, api, login, listAll };
}

const LISTS = ['customers', 'invoices', 'payments', 'promises', 'disputes', 'products', 'users', 'roles', 'notifications'];
const SUMMARIES = ['customers', 'invoices', 'payments', 'promises'];
const FILTERS = [
  ['invoices', { filter: ['status:in:UNPAID,PARTIALLY_PAID'] }],
  ['invoices', { filter: ['overdue:eq:true'] }],
  ['invoices', { filter: ['total:gt:1000000'], sort: 'total,desc' }],
  ['invoices', { filter: ['customerName:contains:an'] }],
  ['invoices', { filter: ['invoiceDate:gte:2026-06-01T00:00:00Z'] }],
  ['invoices', { filter: ['status:eq:CANCELLED'] }],
  ['payments', { filter: ['status:eq:VOIDED'] }],
  ['payments', { filter: ['amount:gte:100000'], sort: 'amount,desc' }],
  ['customers', { filter: ['name:contains:a'] }],
  ['customers', { filter: ['outstanding:gt:0'], sort: 'outstanding,desc' }],
  ['customers', { filter: ['phone:contains:555'] }],
  ['promises', { filter: ['status:in:OPEN,BROKEN'] }],
  ['disputes', { filter: ['status:eq:PENDING'] }],
  ['disputes', { filter: ['targetType:eq:PAYMENT'] }],
];
const DASHBOARD = [
  '/api/dashboard/billed-by-month', '/api/dashboard/billed-by-month?months=3', '/api/dashboard/outstanding-by-age',
  '/api/dashboard/top-outstanding-customers', '/api/dashboard/top-outstanding-customers?limit=10',
  '/api/dashboard/collected-by-month', '/api/dashboard/collected-by-month?months=6',
  '/api/dashboard/top-paying-customers', '/api/dashboard/top-paying-customers?months=3&limit=10',
];

/** What one user sees. `c` is a client; `token` the user's token. */
async function snapshot(c, token) {
  const s = { lists: {}, summaries: {}, filtered: {}, dashboard: {}, misc: {}, details: {} };
  for (const e of LISTS) {
    const r = await c.listAll(token, `/api/${e}`);
    const content = r.json && Array.isArray(r.json.content) ? r.json.content : null;
    s.lists[e] = {
      status: r.status,
      totalElements: r.json ? r.json.totalElements : null,
      sort: r.json ? r.json.sort : null,
      lockedFilters: r.json ? r.json.lockedFilters : null,
      ids: content ? content.map((x) => x.id) : null,
      rows: content ? Object.fromEntries(content.map((x) => [x.id, x])) : null,
    };
  }
  for (const e of SUMMARIES) {
    const r = await c.api('GET', `/api/${e}/summary`, { token });
    s.summaries[e] = { status: r.status, body: r.json };
  }
  for (const [e, q] of FILTERS) {
    const r = await c.listAll(token, `/api/${e}`, q);
    const key = `${e}?${JSON.stringify(q)}`;
    s.filtered[key] = { status: r.status, totalElements: r.json ? r.json.totalElements : null,
      ids: r.json && Array.isArray(r.json.content) ? r.json.content.map((x) => x.id) : null };
  }
  for (const p of DASHBOARD) {
    const r = await c.api('GET', p, { token });
    s.dashboard[p] = { status: r.status, body: r.json };
  }
  for (const p of ['/api/auth/me', '/api/notifications/unread-count', '/api/inbox/unread-count', '/api/pocs/my-scope', '/api/privileges']) {
    const r = await c.api('GET', p, { token });
    s.misc[p] = { status: r.status, body: r.json };
  }
  // Details of the first few records of each kind the user can see.
  for (const e of ['customers', 'invoices', 'payments', 'promises', 'disputes']) {
    const ids = (s.lists[e].ids || []).slice(0, 4);
    for (const id of ids) {
      const r = await c.api('GET', `/api/${e}/${id}`, { token });
      s.details[`/api/${e}/${id}`] = { status: r.status, body: r.json };
    }
  }
  const cust = (s.lists.customers.ids || []).slice(0, 2);
  for (const id of cust) {
    const r = await c.api('GET', `/api/customers/${id}/pocs`, { token });
    s.details[`/api/customers/${id}/pocs`] = { status: r.status, body: r.json };
    const a = await c.api('GET', '/api/audit', { token, query: { entityType: 'CUSTOMER', entityId: id, includeRelated: true } });
    s.details[`/api/audit?CUSTOMER:${id}`] = { status: a.status, count: Array.isArray(a.json) ? a.json.length : null,
      actions: Array.isArray(a.json) ? a.json.map((x) => `${x.entityType}:${x.entityId}:${x.action}`) : null };
  }
  return s;
}

const SUPERSET_KEYS = new Set(['privileges']);

/**
 * Subset diff: every value present in `b` (baseline) must equal the one in `n` (new). Keys only in `n` are
 * additions and ignored. Arrays must have the same length and order, except arrays under SUPERSET_KEYS, where
 * every baseline element must be present in the new array (a role gaining the new privileges is expected).
 */
// Values that differ between any two boots by construction: error timestamps, and promise updatedAt, which each
// app's startup promise sweep stamps with its own clock.
const IGNORE = (p) => /\.timestamp$/.test(p) || (/promises/.test(p) && /\.updatedAt$/.test(p));
const SUPERSET_PATHS = [/\/api\/privileges\.body$/];

function diff(b, n, p = '', out = [], limit = 60) {
  if (out.length >= limit) return out;
  if (IGNORE(p)) return out;
  const key = p.split('.').pop().replace(/\[\d+\]$/, '');
  if (b === null || b === undefined) {
    if (!(n === null || n === undefined)) out.push(`${p}: baseline ${JSON.stringify(b)} vs new ${JSON.stringify(n).slice(0, 120)}`);
    return out;
  }
  if (Array.isArray(b)) {
    if (!Array.isArray(n)) { out.push(`${p}: baseline array(${b.length}) vs new ${JSON.stringify(n).slice(0, 120)}`); return out; }
    if (SUPERSET_KEYS.has(key) || SUPERSET_PATHS.some((re) => re.test(p))) {
      const have = new Set(n.map((x) => JSON.stringify(typeof x === 'object' && x ? (x.name || x) : x)));
      const missing = b.filter((x) => !have.has(JSON.stringify(typeof x === 'object' && x ? (x.name || x) : x)));
      if (missing.length) out.push(`${p}: lost ${JSON.stringify(missing).slice(0, 300)}`);
      return out;
    }
    if (b.length !== n.length) { out.push(`${p}: length ${b.length} vs ${n.length}`); }
    for (let i = 0; i < Math.min(b.length, n.length); i++) diff(b[i], n[i], `${p}[${i}]`, out, limit);
    return out;
  }
  if (typeof b === 'object') {
    if (typeof n !== 'object' || n === null) { out.push(`${p}: baseline object vs new ${JSON.stringify(n).slice(0, 120)}`); return out; }
    for (const k of Object.keys(b)) diff(b[k], n[k], p ? `${p}.${k}` : k, out, limit);
    return out;
  }
  if (typeof b === 'number' && typeof n === 'number') {
    if (Math.abs(b - n) > 1e-6) out.push(`${p}: ${b} vs ${n}`);
    return out;
  }
  if (b !== n) out.push(`${p}: ${JSON.stringify(b).slice(0, 120)} vs ${JSON.stringify(n).slice(0, 120)}`);
  return out;
}

module.exports = { client, snapshot, diff, LISTS, SUMMARIES, FILTERS, DASHBOARD };
