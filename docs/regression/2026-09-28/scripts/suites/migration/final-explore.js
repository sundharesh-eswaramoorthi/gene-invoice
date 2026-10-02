#!/usr/bin/env node
// FINAL exploratory pass, area "migration" (upgrade of existing data). Written independently of run.js.
//
//   env/up.sh migration 7 --fresh ; source runtime/migration/env.sh ; node final-explore.js
//
// Scenario: the up.sh backend is stopped; e2e_migration is recreated EMPTY and the BASELINE (pre-feature) jar
// is booted on it (port 18107, the port the web bundle talks to). A small, varied legacy dataset is created
// through the baseline API (custom role, inactive user, customer login, POCs, overdue invoice, payment,
// promise, dispute, notifications) and a before-snapshot is taken. The legacy rows are back-dated 30 days.
// The baseline is stopped and the NEW jar boots on the same database (the upgrade). Then the cases run,
// including a second boot. Results: runs/final/migration-explore.json; shots: runs/final/migration/shots/explore.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const I = require('./infra');

const OUT = path.join(I.E2E, 'runs', 'final', 'migration-explore.json');
const SHOTS = path.join(I.E2E, 'runs', 'final', 'migration', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });
const PW = 'Passw0rd!';
const DB = 'e2e_migration';
const LOG_BASE = path.join(I.RT, 'explore-baseline.log');
const LOG_NEW = path.join(I.RT, 'explore-upgrade.log');
const LOG_NEW2 = path.join(I.RT, 'explore-upgrade-restart.log');
const cases = [];
const S = {}; // shared state

function rec(o) {
  const status = o.status || (o.ok ? 'PASS' : 'FAIL');
  const c = {
    id: o.id, feature: o.feature || '', kind: o.kind || 'API', prd: o.prd || '', title: o.title || '', status,
    severity: status === 'FAIL' ? (o.severity || 'medium') : '',
    steps: o.steps || '', expected: o.expected || '', actual: typeof o.actual === 'string' ? o.actual : JSON.stringify(o.actual),
    evidence: typeof o.evidence === 'string' ? o.evidence : JSON.stringify(o.evidence || ''), screenshot: o.screenshot || '', codeRef: o.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === c.id);
  if (i >= 0) cases[i] = c; else cases.push(c);
  console.log(`[${status}] ${c.id} ${c.title}${status !== 'PASS' ? '  :: ' + c.actual.slice(0, 400) : ''}`);
  save();
}
function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'migration', round: 'final', kind: 'explore', env: { api: process.env.E2E_API, web: process.env.E2E_WEB }, cases }, null, 2));
}
const short = (v, n = 900) => { const s = typeof v === 'string' ? v : JSON.stringify(v); return s && s.length > n ? s.slice(0, n) + '…' : s; };

/** Runs one case; an exception is recorded as FAIL with the error (reviewed by the tester afterwards). */
async function tc(meta, fn) {
  const ctx = { shots: [] };
  try {
    const r = await fn(ctx);
    rec({ ...meta, ...r, screenshot: r.screenshot || ctx.shots.join(',') });
  } catch (e) {
    rec({ ...meta, ok: false, status: meta.errorStatus || 'FAIL', actual: 'exception: ' + (e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : String(e)).slice(0, 1500), screenshot: ctx.shots.join(',') });
  } finally {
    if (ctx.app) { try { await ctx.app.close(); } catch { /* ignore */ } }
  }
}
async function openAt(ctx, opts, route) {
  const app = ctx.app = await rt.openApp(opts);
  if (route) { await rt.go(app.page, route, 4500); }
  return app;
}
function rowLines(txt, name, next) {
  const L = txt.split('\n'); const a = L.indexOf(name); if (a < 0) return [];
  const b = next ? L.indexOf(next, a + 1) : -1; return L.slice(a, b > a ? b : a + 12);
}
async function snap(ctx, page, name) { const p = await rt.shot(page, SHOTS, name); ctx.shots.push(p); return p; }

const api = rt.api;
const need = rt.need;
const sql = (q) => I.psql(DB, q);
const one = (q) => I.one(DB, q);

async function total(token, p, query = {}) {
  const r = await api('GET', p, { token, query: { size: 10, page: 0, ...query } });
  return r.status === 200 ? r.json.totalElements : `HTTP ${r.status}`;
}
const LISTS = ['/api/customers', '/api/invoices', '/api/payments', '/api/promises', '/api/disputes'];
async function totals(token, query = {}) {
  const o = {};
  for (const l of LISTS) o[l.split('/').pop()] = await total(token, l, query);
  return o;
}

// ---- boot helpers --------------------------------------------------------------------------------------------

async function bootBaseline() {
  const pid = I.startBackend({ kind: 'baseline', jar: I.BASE_JAR, port: I.PORT_NEW, db: DB, log: LOG_BASE, docs: path.join(I.RT, 'documents-explore-base') });
  const w = await I.waitLogin(I.PORT_NEW, { pid });
  if (!w.ok) throw new Error('baseline did not come up: ' + JSON.stringify(w));
  return pid;
}
async function bootNew(log) {
  const pid = I.startBackend({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: DB, log, docs: path.join(I.RT, 'documents') });
  const w = await I.waitLogin(I.PORT_NEW, { pid });
  if (!w.ok) throw new Error('new jar did not come up: ' + JSON.stringify(w));
  return pid;
}

// ---- legacy dataset through the BASELINE API ------------------------------------------------------------------

async function seedLegacy() {
  const admin = await rt.adminToken();
  const roles = need(await api('GET', '/api/roles', { token: admin, query: { size: 50 } }), 'roles').content;
  const rid = (n) => roles.find((r) => r.name === n).id;
  const mgrRole = need(await api('POST', '/api/roles', { token: admin, body: { name: 'LEGACY_MGR', description: 'custom pre-upgrade role',
    privileges: ['CUSTOMER_VIEW', 'INVOICE_VIEW', 'INVOICE_MANAGE', 'PAYMENT_VIEW', 'NOTIFICATION_VIEW', 'SCOPE_OVERRIDE'] } }), 'custom role');
  const mk = async (username, roleId, active = true) => need(await api('POST', '/api/users', { token: admin,
    body: { username, email: `${username}@legacy.local`, fullName: username.replace('lg_', 'Legacy ').toUpperCase(), password: PW, roleId, active } }), `user ${username}`);
  const u = {};
  u.sales = await mk('lg_sales', rid('SALES_POC'));
  u.coll = await mk('lg_coll', rid('COLLECTION_POC'));
  u.view = await mk('lg_view', rid('VIEWER'));
  u.view2 = await mk('lg_view2', rid('VIEWER'));
  u.cash = await mk('lg_cash', rid('CASHIER'));
  u.mgr = await mk('lg_mgr', mgrRole.id);
  u.off = await mk('lg_off', rid('SALES_POC'), true);
  const product = need(await api('POST', '/api/products', { token: admin, body: { name: 'Legacy Widget', description: 'legacy', price: 100, active: true } }), 'product');
  const cust = async (name, username) => need(await api('POST', '/api/customers', { token: admin,
    body: { name, phone: '555-0101', email: `${username}@legacy.local`, address: '1 Old Road', paymentTerm: 'NET_30', username, password: PW } }), `customer ${name}`);
  const c = {};
  c.alpha = await cust('Legacy Alpha', 'lgalpha');
  c.beta = await cust('Legacy Beta', 'lgbeta');
  c.gamma = await cust('Legacy Gamma', 'lggamma');
  const poc = async (cid, pocType, userId) => need(await api('POST', `/api/customers/${cid}/pocs`, { token: admin, body: { pocType, userId, primary: true } }), `poc ${pocType}`);
  // Sales POC is per invoice in the baseline; Collection is per customer.
  await poc(c.alpha.id, 'COLLECTION', u.coll.id);
  await poc(c.beta.id, 'COLLECTION', u.coll.id);
  const adminId = (need(await api('GET', '/api/auth/me', { token: admin }), 'me')).id;
  await poc(c.gamma.id, 'COLLECTION', adminId);
  const inv = async (customerId, qty, salesPocUserId, extra = {}) => need(await api('POST', '/api/invoices', { token: admin,
    body: { customerId, salesPocUserId, items: [{ productId: product.id, quantity: qty, unitPrice: 100 }], ...extra } }), 'invoice');
  const i = {};
  i.a1 = await inv(c.alpha.id, 10, u.sales.id);
  i.a2 = await inv(c.alpha.id, 25, u.sales.id);
  const old = new Date(Date.now() - 40 * 86400000).toISOString();
  i.b1 = await inv(c.beta.id, 40, u.sales.id, { invoiceDate: old, dueDate: rt.day(-10), paymentTerm: 'CUSTOM' });
  i.g1 = await inv(c.gamma.id, 7, adminId);
  const p = {};
  p.a = need(await api('POST', '/api/payments', { token: admin, body: { customerId: c.alpha.id, amount: 500, method: 'BANK_TRANSFER', invoiceIds: [i.a1.id], collectionPocUserId: u.coll.id } }), 'payment a');
  p.b = need(await api('POST', '/api/payments', { token: admin, body: { customerId: c.beta.id, amount: 300, method: 'BANK_TRANSFER', invoiceIds: [i.b1.id], collectionPocUserId: u.coll.id } }), 'payment b');
  const promise = need(await api('POST', '/api/promises', { token: admin, body: { customerId: c.beta.id, amount: 1000, promisedDate: rt.day(5), invoiceIds: [i.b1.id], collectionPocUserId: u.coll.id } }), 'promise');
  const alphaTok = await rt.login('lgalpha', PW);
  const dispute = need(await api('POST', '/api/disputes', { token: alphaTok, body: { targetType: 'INVOICE', targetId: i.a2.id, reason: 'Legacy dispute: wrong quantity' } }), 'dispute');
  // lg_off becomes inactive (a real pre-upgrade deactivation)
  need(await api('PUT', `/api/users/${u.off.id}`, { token: admin, body: { active: false } }), 'deactivate lg_off');
  Object.assign(S, { u, c, i, p, promise, dispute, product, adminId });
}

async function snapshotBaseline() {
  const who = { admin: await rt.adminToken() };
  for (const n of ['lg_sales', 'lg_coll', 'lg_view', 'lg_cash', 'lg_mgr']) who[n] = await rt.login(n, PW);
  who.lgalpha = await rt.login('lgalpha', PW);
  const before = {};
  for (const [n, t] of Object.entries(who)) {
    const me = need(await api('GET', '/api/auth/me', { token: t }), 'me');
    const nf = await api('GET', '/api/notifications', { token: t, query: { size: 50 } });
    const uc = await api('GET', '/api/notifications/unread-count', { token: t });
    before[n] = { privileges: [...(me.privileges || [])].sort(), totals: await totals(t),
      notifications: nf.status === 200 ? nf.json.totalElements : `HTTP ${nf.status}`, unread: uc.json };
  }
  before.lastInvoiceNumber = one('select max(invoice_number) from invoices');
  S.before = before;
}

// ---- main ------------------------------------------------------------------------------------------------------

async function main() {
  const t0 = Date.now();
  // 0. replace the up.sh backend by the baseline on an EMPTY database
  await I.stopNewBackend();
  I.psql('postgres', `DROP DATABASE IF EXISTS ${DB} WITH (FORCE)`);
  I.psql('postgres', `CREATE DATABASE ${DB}`);
  await bootBaseline();
  await seedLegacy();
  await snapshotBaseline();
  // back-date the legacy rows 30 days (data that existed long before the upgrade)
  for (const t of ['customers', 'invoices', 'payments', 'payment_promises', 'disputes', 'audit_logs', 'notifications', 'users']) {
    const has = one(`select count(*) from information_schema.columns where table_name='${t}' and column_name='created_at'`);
    if (has === '1') sql(`update ${t} set created_at = created_at - interval '30 days'`);
  }
  await I.stopExtras(['baseline']);
  // 1. the upgrade
  await bootNew(LOG_NEW);
  console.log(`legacy seeded + upgraded in ${Math.round((Date.now() - t0) / 1000)}s`);

  const admin = await rt.adminToken();
  S.admin = admin;
  const T = {};
  for (const n of ['lg_sales', 'lg_coll', 'lg_view', 'lg_view2', 'lg_cash', 'lg_mgr']) T[n] = await rt.login(n, PW);
  T.lgalpha = await rt.login('lgalpha', PW);
  S.T = T;
  const def = (await rt.regions(admin)).find((r) => r.defaultRegion);
  S.def = def;

  // ---------------------------------------------------------------------------------------------------------
  await tc({ id: 'MIG-X-01', feature: 'Region backfill', kind: 'API', prd: 'B1',
    title: 'After the upgrade every legacy customer, invoice, payment, promise and dispute belongs to exactly one region (DEFAULT), enforced NOT NULL, and the DTOs say so',
    steps: 'baseline jar: seed 3 customers, 4 invoices, 2 payments, 1 promise, 1 dispute; back-date 30d; boot new jar; SQL region_id + is_nullable; GET each list as admin',
    expected: 'B1 "every record belongs to exactly one": region_id = DEFAULT id on every row, column NOT NULL; each list row regionCode DEFAULT' },
  async () => {
    const tabs = ['customers', 'invoices', 'payments', 'payment_promises', 'disputes'];
    const bad = []; const ev = {};
    for (const t of tabs) {
      const n = one(`select count(*) from ${t}`); const nd = one(`select count(*) from ${t} where region_id = ${def.id}`);
      const nullable = one(`select is_nullable from information_schema.columns where table_name='${t}' and column_name='region_id'`);
      ev[t] = { rows: n, inDefault: nd, nullable };
      if (n !== nd || nullable !== 'NO' || n === '0') bad.push(t);
    }
    const dto = {};
    for (const l of LISTS) {
      const r = await rt.list(admin, l);
      dto[l] = (r.json?.content || []).map((x) => x.regionCode);
      if (!dto[l].length || dto[l].some((c) => c !== 'DEFAULT')) bad.push(l);
    }
    return { ok: bad.length === 0, actual: bad.length ? `mismatch: ${bad.join(',')}` : 'all rows DEFAULT, NOT NULL, DTO regionCode DEFAULT', evidence: short({ ev, dto }) };
  });

  await tc({ id: 'MIG-X-02', feature: 'Grant backfill', kind: 'API', prd: 'B1',
    title: 'Legacy staff (incl. a custom-role user and an inactive user) get exactly DEFAULT/APPROVE/ALL; customer logins get none; no pending flag remains',
    steps: 'SQL user_region_grants joined to users after the upgrade; GET /api/users/{id}/region-grants for lg_mgr and lg_off',
    expected: 'DESIGN §4.6/§3.11 step 5: every pre-B1 staff user -> one DEFAULT APPROVE ALL grant; customer logins none; region_backfill_pending false everywhere' },
  async () => {
    const rows = sql(`select u.username, coalesce(u.customer_id::text,''), count(g.id), string_agg(g.access_level||'/'||g.record_scope||'@'||g.region_id, ',')
      from users u left join user_region_grants g on g.user_id=u.id group by u.username, u.customer_id order by u.username`);
    const bad = rows.filter(([n, cust, cnt, g]) => (cust ? cnt !== '0' : (cnt !== '1' || g !== `APPROVE/ALL@${def.id}`)));
    const pending = one('select count(*) from users where region_backfill_pending');
    const api1 = await api('GET', `/api/users/${S.u.mgr.id}/region-grants`, { token: admin });
    const api2 = await api('GET', `/api/users/${S.u.off.id}/region-grants`, { token: admin });
    const ok = bad.length === 0 && pending === '0' && api1.status === 200 && api1.json.length === 1 && api2.status === 200 && api2.json.length === 1;
    return { ok, actual: ok ? `${rows.length} users checked; staff DEFAULT APPROVE/ALL, customers none; pending=0` : `bad=${short(bad)} pending=${pending} lg_mgr=${api1.status} ${short(api1.json)} lg_off=${api2.status} ${short(api2.json)}`,
      evidence: short(rows, 1500) };
  });

  await tc({ id: 'MIG-X-03', feature: '/me after upgrade', kind: 'API', prd: 'B1',
    title: 'Each legacy user keeps every privilege it had; built-in roles gain the new ones; the custom role gains nothing; regions = [DEFAULT] for staff, [] for the customer login',
    steps: 'baseline: GET /api/auth/me per user (snapshot); new jar: GET /api/auth/me for admin, lg_sales, lg_coll, lg_view, lg_cash, lg_mgr, lgalpha',
    expected: 'DESIGN §4.7: privileges = effectiveAnywhere, identical to today for backfilled users (superset only where the seeder adds new privileges to built-in roles); customer regions []' },
  async () => {
    const out = {}; const bad = [];
    for (const n of ['admin', 'lg_sales', 'lg_coll', 'lg_view', 'lg_cash', 'lg_mgr', 'lgalpha']) {
      const t = n === 'admin' ? admin : T[n];
      const me = await rt.me(t);
      const now = new Set(me.privileges || []);
      const lost = S.before[n].privileges.filter((p) => !now.has(p));
      const gained = [...now].filter((p) => !S.before[n].privileges.includes(p)).sort();
      const regs = (me.regions || []).map((r) => `${r.code}/${r.level}/${r.scope}`);
      out[n] = { lost, gained, regs };
      if (lost.length) bad.push(`${n} lost ${lost}`);
      if (n === 'lg_mgr' && gained.length) bad.push(`custom role gained ${gained}`);
      if (n === 'lgalpha' ? regs.length !== 0 : (regs.length !== 1 || regs[0] !== 'DEFAULT/APPROVE/ALL')) bad.push(`${n} regions ${regs}`);
    }
    return { ok: bad.length === 0, actual: bad.length ? bad.join('; ') : 'no privilege lost; custom role unchanged; regions as expected', evidence: short(out, 2500) };
  });

  await tc({ id: 'MIG-X-04', feature: 'Scoped counts', kind: 'API', prd: 'B1',
    title: '"Which regions am I asking about" on migrated data: unscoped = regionIds=<DEFAULT> = before-upgrade totals; an empty regionIds is not an error that changes the answer; a non-numeric id is 400 REGION_IDS_INVALID',
    steps: 'for lg_sales, lg_coll, lg_view, lg_cash, lgalpha: totals unscoped, with regionIds=DEFAULT, with regionIds= (empty); lg_sales regionIds=abc',
    expected: 'DESIGN §6.0: regionIds optional; totals equal the baseline snapshot; non-numeric -> 400 REGION_IDS_INVALID' },
  async () => {
    const out = {}; const bad = [];
    for (const n of ['lg_sales', 'lg_coll', 'lg_view', 'lg_cash', 'lgalpha']) {
      const a = await totals(T[n]);
      const b = await totals(T[n], { regionIds: String(def.id) });
      const e = await totals(T[n], { regionIds: '' });
      out[n] = { before: S.before[n].totals, unscoped: a, scoped: b, empty: e };
      if (JSON.stringify(a) !== JSON.stringify(S.before[n].totals)) bad.push(`${n} unscoped != before`);
      if (JSON.stringify(b) !== JSON.stringify(a)) bad.push(`${n} regionIds=DEFAULT != unscoped`);
      const emptyOk = JSON.stringify(e) === JSON.stringify(a) || Object.values(e).every((v) => v === 'HTTP 400');
      if (!emptyOk) bad.push(`${n} regionIds= (empty) -> ${JSON.stringify(e)}`);
    }
    const inval = await api('GET', '/api/invoices', { token: T.lg_sales, query: { regionIds: 'abc' } });
    if (inval.status !== 400 || inval.json?.error !== 'REGION_IDS_INVALID') bad.push(`abc -> ${inval.status} ${short(inval.json, 200)}`);
    return { ok: bad.length === 0, actual: bad.length ? bad.join('; ') : 'all equal; abc -> 400 REGION_IDS_INVALID', evidence: short(out, 3000) };
  });

  await tc({ id: 'MIG-X-05', feature: 'Legacy notifications', kind: 'API', prd: 'B1',
    title: 'Pre-upgrade notifications (region_id null) stay listed with the same unread count, also when the user narrows to a region',
    steps: 'baseline: admin gets a dispute-opened notice, lg_sales/lg_coll POC-assigned notices; new jar: GET /api/notifications and /unread-count unscoped and with regionIds=DEFAULT',
    expected: 'DESIGN §5.6.7: filter is region_id IN (asked) OR region_id IS NULL, so legacy notices remain; counts equal baseline' },
  async () => {
    const out = {}; const bad = [];
    for (const n of ['admin', 'lg_sales', 'lg_coll']) {
      const t = n === 'admin' ? admin : T[n];
      const l = await api('GET', '/api/notifications', { token: t, query: { size: 50 } });
      const ls = await api('GET', '/api/notifications', { token: t, query: { size: 50, regionIds: String(def.id) } });
      const uc = await api('GET', '/api/notifications/unread-count', { token: t, query: { regionIds: String(def.id) } });
      out[n] = { before: S.before[n].notifications, beforeUnread: S.before[n].unread, now: l.json?.totalElements, scoped: ls.json?.totalElements, unreadScoped: uc.json };
      if (!S.before[n].notifications) bad.push(`${n}: baseline had no notifications (fixture)`);
      if (l.json?.totalElements !== S.before[n].notifications || ls.json?.totalElements !== S.before[n].notifications) bad.push(`${n} counts differ`);
      if (JSON.stringify(uc.json) !== JSON.stringify(S.before[n].unread)) bad.push(`${n} unread differs`);
    }
    return { ok: bad.length === 0, actual: bad.length ? bad.join('; ') : 'legacy notifications kept in both views', evidence: short(out, 1500) };
  });

  await tc({ id: 'MIG-X-06', feature: 'As-of boundaries on migrated data', kind: 'API', prd: 'B3',
    title: 'History starts on the upgrade day even though the legacy rows are 30 days old: yesterday -> 400 AS_OF_BEFORE_HISTORY, today -> live, tomorrow -> AS_OF_FUTURE, bad date -> AS_OF_INVALID',
    steps: 'GET /api/history/range; GET /api/invoices?asOf=<yesterday|today|tomorrow|2026-13-01> as admin',
    expected: 'DESIGN D9/§5.7.9/§6.17: historyStartsOn = today; codes as listed; asOf=today answers live with X-View-As-Of: live and the same total' },
  async () => {
    const range = await api('GET', '/api/history/range', { token: admin });
    const y = await api('GET', '/api/invoices', { token: admin, query: { asOf: rt.day(-1) } });
    const t = await api('GET', '/api/invoices', { token: admin, query: { asOf: rt.day(0) } });
    const f = await api('GET', '/api/invoices', { token: admin, query: { asOf: rt.day(1) } });
    const b = await api('GET', '/api/invoices', { token: admin, query: { asOf: '2026-13-01' } });
    const live = await total(admin, '/api/invoices');
    const ok = range.json?.historyStartsOn === rt.day(0) && y.status === 400 && y.json?.error === 'AS_OF_BEFORE_HISTORY'
      && t.status === 200 && t.headers['x-view-as-of'] === 'live' && t.json.totalElements === live
      && f.status === 400 && f.json?.error === 'AS_OF_FUTURE' && b.status === 400 && b.json?.error === 'AS_OF_INVALID';
    return { ok, actual: `range=${short(range.json)} yesterday=${y.status} ${y.json?.error} "${y.json?.message}" today=${t.status} hdr=${t.headers['x-view-as-of']} n=${t.json?.totalElements}/${live} tomorrow=${f.status} ${f.json?.error} bad=${b.status} ${b.json?.error}`, evidence: '' };
  });

  await tc({ id: 'MIG-X-07', feature: 'Thresholds after upgrade', kind: 'API', prd: 'B2',
    title: 'The upgrade gates nothing: DEFAULT has no thresholds and a 50,000 invoice on a migrated customer is applied at once',
    steps: 'GET /api/regions/{DEFAULT}/approval-thresholds; admin POST /api/invoices {Legacy Gamma, 1 x 50000}',
    expected: 'B2 threshold is configurable per region; no threshold configured -> 200 applied, no pending change' },
  async () => {
    const th = await rt.getThresholds(admin, def.id);
    const gated = (Array.isArray(th.json) ? th.json : (th.json?.thresholds || [])).filter((x) => x.amount != null);
    const inv = await rt.createInvoice(admin, { customerId: S.c.gamma.id, salesPocUserId: S.adminId, items: [{ productId: S.product.id, quantity: 1, unitPrice: 50000 }] });
    S.bigInv = inv.data;
    const pend = one("select count(*) from pending_changes where status='PENDING'");
    const ok = th.status === 200 && gated.length === 0 && inv.status === 200 && !inv.held && pend === '0';
    return { ok, actual: `thresholds=${th.status} gated=${short(gated)} invoice=${inv.status} held=${inv.held} number=${inv.data?.invoiceNumber} pending=${pend}`, evidence: short(th.json, 600) };
  });

  await tc({ id: 'MIG-X-08', feature: 'Invoice numbering continues', kind: 'API', prd: 'B1',
    title: 'The first invoice created after the upgrade continues the legacy number sequence without a duplicate',
    steps: 'baseline max(invoice_number) vs the invoice created in MIG-X-07; SQL duplicate check',
    expected: 'existing data upgraded without disruption: number > last legacy number, same format, unique' },
  async () => {
    const last = S.before.lastInvoiceNumber; const nw = S.bigInv?.invoiceNumber;
    const dup = one('select count(*) from (select invoice_number from invoices group by invoice_number having count(*)>1) x');
    const ok = !!nw && nw > last && nw.split('-').length === last.split('-').length && dup === '0';
    return { ok, actual: `last legacy ${last}, new ${nw}, duplicates ${dup}` };
  });

  await tc({ id: 'MIG-X-09', feature: 'Threshold boundary on migrated record', kind: 'API', prd: 'B2',
    title: 'DEFAULT PAYMENT_RECORD=500: a migrated COLLECTION_POC records exactly 500.00 (applied) and 500.01 (held); the held one does not touch the balance and is visible to a migrated VIEWER',
    steps: 'admin PUT thresholds DEFAULT PAYMENT_RECORD 500; lg_coll POST /api/payments {Legacy Beta, 500.00, inv b1}; again 500.01; lg_view GET /api/approvals',
    expected: 'DESIGN §5.11.1 "above" = amount > threshold: 500.00 -> 200; 500.01 -> 202 PENDING_APPROVAL, balance unchanged; B2 pending visible to those who can see the record' },
  async () => {
    const th = await rt.setThresholds(admin, def.id, [{ operation: 'PAYMENT_RECORD', amount: 500, windowHours: null }]);
    const bal0 = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv').balance;
    const p1 = await rt.createPayment(T.lg_coll, { customerId: S.c.beta.id, amount: 500.00, invoiceIds: [S.i.b1.id], collectionPocUserId: S.u.coll.id });
    const bal1 = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv').balance;
    const p2 = await rt.createPayment(T.lg_coll, { customerId: S.c.beta.id, amount: 500.01, invoiceIds: [S.i.b1.id], collectionPocUserId: S.u.coll.id });
    const bal2 = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv').balance;
    S.heldPay = p2.pendingChange;
    const seen = await rt.approvals.list(T.lg_view, {});
    const vis = (seen.json?.content || []).some((x) => x.id === p2.pendingChange?.id);
    const ok = th.status === 200 && p1.status === 200 && !p1.held && Number(bal1) === Number(bal0) - 500
      && p2.status === 202 && p2.held && Number(bal2) === Number(bal1) && vis;
    return { ok, actual: `thresholds PUT ${th.status}; 500.00 -> ${p1.status} held=${p1.held}; balance ${bal0}->${bal1}; 500.01 -> ${p2.status} held=${p2.held} pc=${p2.pendingChange?.id} "${p2.json?.message}"; balance ${bal2}; lg_view approvals ${seen.status} sees=${vis}`,
      evidence: short(p2.json, 800), codeRef: '' };
  });

  await tc({ id: 'MIG-X-10', feature: 'Maker-checker rights of migrated users', kind: 'API', prd: 'B2',
    title: 'Migrated APPROVE/ALL grants do not let a role without CHANGE_APPROVE approve: the maker (lg_coll) and a CASHIER are refused; the migrated admin approves and the payment applies',
    steps: 'lg_coll POST /api/approvals/{pc}/approve; lg_cash same; admin approve; GET invoice balance',
    expected: 'DESIGN D11: approving needs CHANGE_APPROVE in the region and checker != maker -> 403 for both; admin -> 200 APPROVED, balance - 500.01' },
  async () => {
    const pc = S.heldPay?.id;
    if (!pc) return { status: 'BLOCKED', actual: 'no held payment from MIG-X-09' };
    const a1 = await rt.approvals.approve(T.lg_coll, pc);
    const a2 = await rt.approvals.approve(T.lg_cash, pc);
    const bal1 = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv').balance;
    const a3 = await rt.approvals.approve(admin, pc, 'ok');
    const g = await rt.approvals.get(admin, pc);
    const bal2 = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv').balance;
    const ok = [403].includes(a1.status) && [403].includes(a2.status) && a3.status === 200 && g.json?.status === 'APPROVED'
      && Math.abs(Number(bal1) - Number(bal2) - 500.01) < 0.001;
    return { ok, actual: `maker ${a1.status} ${short(a1.json?.message, 150)}; cashier ${a2.status} ${short(a2.json?.message, 150)}; admin ${a3.status} status=${g.json?.status}; balance ${bal1}->${bal2}` };
  });

  await tc({ id: 'MIG-X-11', feature: 'Self-approval on migrated admin', kind: 'API', prd: 'B2',
    title: 'The migrated admin (only CHANGE_APPROVE holder) cannot approve its own held change; it stays pending and the invoice is not cancelled',
    steps: 'admin PUT DEFAULT INVOICE_CANCEL=100 (tighten); admin POST /api/invoices/{Legacy Gamma 700}/cancel -> 202; admin approve it',
    expected: 'B2 "needs approval from someone else": approve by maker refused (403/400), change PENDING, invoice status unchanged' },
  async () => {
    const th = await rt.setThresholds(admin, def.id, [{ operation: 'PAYMENT_RECORD', amount: 500, windowHours: null }, { operation: 'INVOICE_CANCEL', amount: 100, windowHours: null }]);
    const c = await rt.cancelInvoice(admin, S.i.g1.id);
    const pc = c.pendingChange?.id;
    const a = pc ? await rt.approvals.approve(admin, pc) : { status: 'n/a' };
    const g = pc ? await rt.approvals.get(admin, pc) : { json: null };
    const inv = need(await api('GET', `/api/invoices/${S.i.g1.id}`, { token: admin }), 'inv');
    S.heldCancel = pc;
    const ok = th.status === 200 && c.status === 202 && [400, 403, 409].includes(a.status) && g.json?.status === 'PENDING' && inv.status !== 'CANCELLED';
    return { ok, actual: `thresholds ${th.status}; cancel ${c.status} pc=${pc}; self-approve ${a.status} ${short(a.json?.message, 200)}; pc status ${g.json?.status}; invoice ${inv.status}` };
  });

  await tc({ id: 'MIG-X-12', feature: 'Pending flag on migrated rows', kind: 'API', prd: 'B2',
    title: 'The held cancel of a migrated invoice is visible (pendingApproval: true) to other migrated users who can see it, without having taken effect',
    steps: 'lg_cash, lg_view GET /api/invoices (list) and /api/approvals/for-record?entityType=INVOICE&entityId=<g1>',
    expected: 'B2 "a pending change is visible to everyone who can see the record, without having taken effect"; DESIGN §6.5 pendingApproval on list rows' },
  async () => {
    if (!S.heldCancel) return { status: 'BLOCKED', actual: 'no held cancel from MIG-X-11' };
    const out = {}; const bad = [];
    for (const n of ['lg_cash', 'lg_view']) {
      const l = await rt.list(T[n], '/api/invoices');
      const row = (l.json?.content || []).find((x) => x.id === S.i.g1.id);
      const fr = await rt.approvals.forRecord(T[n], 'INVOICE', S.i.g1.id);
      const arr = Array.isArray(fr.json) ? fr.json : (fr.json?.content || fr.json?.items || (fr.json ? [fr.json] : []));
      out[n] = { row: row && { status: row.status, pendingApproval: row.pendingApproval }, forRecord: fr.status, n: arr.length };
      if (!row || row.pendingApproval !== true || row.status === 'CANCELLED' || fr.status !== 200) bad.push(n);
    }
    return { ok: bad.length === 0, actual: bad.length ? `not visible/incorrect for ${bad}: ${short(out)}` : short(out), evidence: short(out) };
  });

  // ---- age the world 10 days: the upgrade (and everything so far) now happened 10 days ago ------------------------
  rt.ageWorld(10, { db: DB });
  S.aged = true;

  await tc({ id: 'MIG-X-13', feature: 'As-of window after ageing', kind: 'API', prd: 'B3',
    title: 'Ten days after the upgrade: history starts on the upgrade day; the day before it is refused; as of 2 days ago the migrated lists answer historically with the same totals as today',
    steps: 'ageWorld(10); GET /api/history/range; lg_sales/admin totals asOf=day(-11) and asOf=day(-2) vs live',
    expected: 'DESIGN D9: asOf < historyStartsOn -> 400 AS_OF_BEFORE_HISTORY; asOf in range -> 200 with X-View-As-Of=<date>' },
  async () => {
    const range = await api('GET', '/api/history/range', { token: admin });
    const before = await api('GET', '/api/customers', { token: T.lg_sales, query: { asOf: rt.day(-11) } });
    const liveT = await totals(T.lg_sales);
    const pastT = await totals(T.lg_sales, { asOf: rt.day(-2) });
    const h = await api('GET', '/api/customers', { token: T.lg_sales, query: { asOf: rt.day(-2) } });
    const ok = range.json?.historyStartsOn === rt.day(-10) && before.status === 400 && before.json?.error === 'AS_OF_BEFORE_HISTORY'
      && JSON.stringify(liveT) === JSON.stringify(pastT) && h.headers['x-view-as-of'] === rt.day(-2);
    return { ok, actual: `range ${short(range.json)}; day-11 ${before.status} ${before.json?.error} "${before.json?.message}"; live ${JSON.stringify(liveT)} past ${JSON.stringify(pastT)} hdr ${h.headers['x-view-as-of']}` };
  });

  // region move of a migrated customer
  S.north = await rt.createRegion(admin, { code: 'NORTH', name: 'North' });
  await tc({ id: 'MIG-X-14', feature: 'Moving a migrated customer', kind: 'API', prd: 'B1',
    title: 'Moving migrated Legacy Alpha to NORTH carries all its legacy children; the migrated SALES_POC (DEFAULT only) loses it live but still sees it as of before the move',
    steps: 'admin POST /api/regions NORTH; POST /api/customers/{alpha}/move-region {NORTH}; lg_sales lists live and asOf=day(-2)',
    expected: 'B1 one region per record; DESIGN §6.4 moved counts (invoices 2, payments 1, disputes 1) + warning about lg_sales; D9 current grants applied to region at T' },
  async () => {
    const mv = await rt.moveCustomerRegion(admin, S.c.alpha.id, S.north.id, 'legacy account moved north');
    const liveC = await rt.list(T.lg_sales, '/api/customers');
    const pastC = await rt.list(T.lg_sales, '/api/customers', {}, { asOf: rt.day(-2) });
    const liveI = await rt.list(T.lg_sales, '/api/invoices');
    const pastI = await rt.list(T.lg_sales, '/api/invoices', {}, { asOf: rt.day(-2) });
    const hasLive = (liveC.json?.content || []).some((x) => x.id === S.c.alpha.id);
    const pastRow = (pastC.json?.content || []).find((x) => x.id === S.c.alpha.id);
    const liveInv = (liveI.json?.content || []).filter((x) => x.customerId === S.c.alpha.id).length;
    const pastInv = (pastI.json?.content || []).filter((x) => x.customerId === S.c.alpha.id).length;
    const m = mv.json?.moved || {};
    const warn = (mv.json?.warnings || []).join(' | ');
    const ok = mv.status === 200 && m.invoices === 2 && m.payments === 1 && m.disputes === 1 && !hasLive && pastRow && pastRow.regionCode === 'DEFAULT' && liveInv === 0 && pastInv === 2
      && /lg_sales|LEGACY SALES/i.test(warn);
    return { ok, actual: `move ${mv.status} moved=${short(m)} warnings="${warn}"; lg_sales live sees alpha=${hasLive}, invoices ${liveInv}; asOf -2 row=${pastRow && pastRow.regionCode}, invoices ${pastInv}`, evidence: short(mv.json, 1000) };
  });

  await tc({ id: 'MIG-X-15', feature: 'POC book per region for a migrated user', kind: 'API', prd: 'B1',
    title: 'Granting the migrated SALES_POC MANAGE/BOOK in NORTH gives back exactly its book there (Alpha and its invoices), with NORTH reported as a book region',
    steps: 'admin PUT /api/users/{lg_sales}/region-grants [DEFAULT APPROVE ALL, NORTH MANAGE BOOK]; lg_sales re-login; GET /api/customers?regionIds=NORTH, /api/invoices?regionIds=NORTH',
    expected: 'B1 "the POC book is per region": sees Alpha (its POC) in NORTH; bookRegionIds contains NORTH' },
  async () => {
    const g = await rt.setGrants(admin, S.u.sales.id, [{ regionId: def.id, level: 'APPROVE', scope: 'ALL' }, { regionId: S.north.id, level: 'MANAGE', scope: 'BOOK' }]);
    T.lg_sales = await rt.login('lg_sales', PW);
    const c = await api('GET', '/api/customers', { token: T.lg_sales, query: { size: 50, regionIds: String(S.north.id) } });
    const i = await api('GET', '/api/invoices', { token: T.lg_sales, query: { size: 50, regionIds: String(S.north.id) } });
    const names = (c.json?.content || []).map((x) => x.name);
    const ok = g.status === 200 && names.length === 1 && names[0] === 'Legacy Alpha' && i.json?.totalElements === 2
      && (c.json?.bookRegionIds || []).includes(S.north.id);
    return { ok, actual: `grants ${g.status}; customers ${short(names)} bookRegionIds=${short(c.json?.bookRegionIds)} regionIds=${short(c.json?.regionIds)}; invoices ${i.json?.totalElements}`, evidence: short(g.json, 500) };
  });

  // ---- automation on migrated data --------------------------------------------------------------------------------
  await tc({ id: 'MIG-X-16', feature: 'Scheduled rule on migrated invoices (Run now)', kind: 'API', prd: 'A1',
    title: 'A DAILY INVOICE rule with an OR condition, run from "Run now", creates tasks on the matching migrated invoices, assigned to the migrated Sales POC, with placeholders rendered from migrated values',
    steps: 'admin POST /api/automation/rules {SCHEDULE_DAILY INVOICE, condition OR[overdue:eq:true, total:gt:10000], CREATE_TASK "Chase {{Invoice.Number}} for {{Customer.Name}} ({{Invoice.Balance}})" to ROLE SALES_POC@RECORD (the Sales POC of the invoice)}; POST /run; wait; effects; lg_sales GET /api/tasks/mine',
    expected: 'A1/A2/A3/A4/A5: run SUCCEEDED, matched = Beta overdue invoice + 50,000 Gamma invoice; task on Beta invoice assigned to lg_sales with rendered title; A4 primary resolution' },
  async () => {
    const r = await rt.automation.create(admin, rt.rule({ name: 'Legacy chase', trigger: { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '23:59', zone: 'UTC', catchUp: 'ONE' },
      condition: { op: 'OR', children: [{ filter: 'overdue:eq:true' }, { filter: 'total:gt:10000' }] },
      actions: [rt.action.task({ title: 'Chase {{Invoice.Number}} for {{Customer.Name}} ({{Invoice.Balance}})', assignees: [rt.tok.role('SALES_POC', 'RECORD')] })] }));
    if (![200, 201].includes(r.status)) return { ok: false, actual: `create rule ${r.status} ${short(r.json)}` };
    S.rule1 = r.json;
    const run = await rt.automation.run(admin, r.json.id, {});
    const done = run.json?.runId ? await rt.waitForRun(admin, run.json.runId, { timeoutMs: 60000 }) : null;
    const eff = done ? await rt.automation.effects(admin, done.id) : { json: null };
    const mine = await rt.tasks.mine(T.lg_sales);
    const invB = need(await api('GET', `/api/invoices/${S.i.b1.id}`, { token: admin }), 'inv b1');
    const t = (mine.json?.content || []).find((x) => x.entityType === 'INVOICE' && x.entityId === S.i.b1.id);
    const expectTitle = `Chase ${invB.invoiceNumber} for Legacy Beta (`;
    S.taskB = t;
    const tDetail = t ? (await api('GET', `/api/tasks/${t.id}`, { token: T.lg_sales })).json : null;
    const targets = (eff.json?.content || []).map((e) => `${e.target?.id}:${e.status}`);
    const ok = run.status === 202 && done && done.status === 'SUCCEEDED' && done.matched === 2 && t && t.title.startsWith(expectTitle) && !/\{\{/.test(t.title)
      && (tDetail?.assignees?.[0]?.people || []).some((p) => p.userId === S.u.sales.id && p.primary);
    return { ok, actual: `rule ${r.status} id=${r.json.id}; run ${run.status} -> ${done && done.status} matched=${done && done.matched} done=${done && done.done}; effects ${short(targets)}; lg_sales task="${t && t.title}" detail assignees=${short(tDetail && tDetail.assignees)}`,
      evidence: short({ run: done, warnings: r.json.warnings }, 1200) };
  });

  await tc({ id: 'MIG-X-17', feature: 'Entity-updated rule + email on migrated customer', kind: 'API', prd: 'A4',
    title: 'Saving a migrated customer fires an ENTITY_UPDATED rule whose email renders {{Customer.Name}} and a money placeholder from migrated data; the save itself is unaffected',
    steps: 'GET /api/automation/placeholders?entityType=CUSTOMER; admin rule ENTITY_UPDATED CUSTOMER name:contains:Gamma -> SEND_EMAIL to CUSTOMER; PUT /api/customers/{gamma} phone change; poll /api/emails?entityType=CUSTOMER&entityId=gamma',
    expected: 'A1 update trigger, A4 placeholders offered at customer level, A5 save 200 fast; Email row persisted with rendered subject/body (status NOT_SENT/NOT_CONFIGURED)' },
  async () => {
    const ph = await rt.automation.placeholders(admin, 'CUSTOMER');
    const toks = (ph.json?.groups || []).flatMap((g) => g.items.map((i) => i.token));
    const money = toks.find((x) => /Outstanding/.test(x)) || toks.find((x) => /Balance|Amount/.test(x));
    const r = await rt.automation.create(admin, rt.rule({ name: 'Legacy hello', trigger: { kind: 'ENTITY_UPDATED', entityType: 'CUSTOMER' },
      condition: { op: 'AND', children: [{ filter: 'name:contains:Gamma' }] },
      actions: [rt.action.email({ to: [rt.tok.customer()], subject: 'Hello {{Customer.Name}}', body: `You owe ${money}` })] }));
    if (![200, 201].includes(r.status)) return { ok: false, actual: `create rule ${r.status} ${short(r.json)} tokens=${short(toks)}` };
    const cust = need(await api('GET', `/api/customers/${S.c.gamma.id}`, { token: admin }), 'gamma');
    const t0 = Date.now();
    const put = await api('PUT', `/api/customers/${S.c.gamma.id}`, { token: admin, body: { name: cust.name, phone: '555-0199', email: cust.email, address: cust.address, paymentTerm: cust.paymentTerm, version: cust.version } });
    const ms = Date.now() - t0;
    const em = await rt.waitFor(async () => { const e = await rt.emailsFor(admin, 'CUSTOMER', S.c.gamma.id); return (e.json?.content || []).find((x) => /Hello/.test(x.subject || '')); }, { timeoutMs: 40000 });
    const detail = em ? await api('GET', `/api/emails/${em.id}`, { token: admin }) : null;
    const body = detail?.json?.body || detail?.json?.bodyText || detail?.json?.text || em?.body || '';
    const outstanding = need(await api('GET', `/api/customers/${S.c.gamma.id}`, { token: admin }), 'gamma').outstanding;
    const ok = !!money && put.status === 200 && em && em.subject === 'Hello Legacy Gamma' && !/\{\{/.test(body) && /\d/.test(body);
    return { ok, actual: `placeholders ${ph.status} money token=${money}; PUT ${put.status} in ${ms}ms; email=${em ? `${em.subject} status=${em.status}` : 'none'} body="${short(body, 200)}" outstanding=${outstanding}`,
      evidence: short(detail?.json || em, 800) };
  });

  await tc({ id: 'MIG-X-18', feature: 'Automation x maker-checker on migrated data', kind: 'API', prd: 'A3',
    title: 'A rule creating a promise above DEFAULT PROMISE_CREATE on a migrated invoice is held (effect PENDING_CHANGE), no promise appears, the rule owner cannot approve it',
    steps: 'admin tighten DEFAULT PROMISE_CREATE=100; rule MANUAL INVOICE CREATE_PROMISE amount FIELD Invoice.Balance; run {recordIds:[b1]}; effects; GET /api/promises?customerId; admin approve',
    expected: 'DESIGN D11: held outcome recorded as DONE with result_type PENDING_CHANGE, owner = maker cannot approve; B2 no effect until approved' },
  async () => {
    const th = await rt.setThresholds(admin, def.id, [{ operation: 'PAYMENT_RECORD', amount: 500, windowHours: null }, { operation: 'INVOICE_CANCEL', amount: 100, windowHours: null }, { operation: 'PROMISE_CREATE', amount: 100, windowHours: null }]);
    const promisesBefore = (await rt.list(admin, '/api/promises', { filter: `customerId:eq:${S.c.beta.id}` })).json?.totalElements;
    const r = await rt.automation.create(admin, rt.rule({ name: 'Legacy promise', trigger: { kind: 'MANUAL', entityType: 'INVOICE' },
      actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' }, onlyIfNoOpenPromise: false })] }));
    if (![200, 201].includes(r.status)) return { ok: false, actual: `rule ${r.status} ${short(r.json)}` };
    const run = await rt.automation.run(admin, r.json.id, { recordIds: [S.i.b1.id] });
    const done = run.json?.runId ? await rt.waitForRun(admin, run.json.runId) : null;
    const eff = done ? await rt.automation.effects(admin, done.id) : { json: null };
    const e = (eff.json?.content || [])[0];
    const promisesAfter = (await rt.list(admin, '/api/promises', { filter: `customerId:eq:${S.c.beta.id}` })).json?.totalElements;
    const pcId = e?.result?.type === 'PENDING_CHANGE' ? e.result.id : null;
    const a = pcId ? await rt.approvals.approve(admin, pcId) : { status: 'n/a' };
    const ok = th.status === 200 && done && e && e.status === 'DONE' && e.result?.type === 'PENDING_CHANGE' && promisesAfter === promisesBefore && [400, 403, 409].includes(a.status);
    return { ok, actual: `thresholds ${th.status}; run ${run.status} -> ${done && done.status}; effect ${short(e)}; promises ${promisesBefore}->${promisesAfter}; owner approve ${a.status} ${short(a.json?.message, 150)}` };
  });

  await tc({ id: 'MIG-X-19', feature: 'Task on a migrated payment by a migrated user', kind: 'API', prd: 'A6',
    title: 'A migrated COLLECTION_POC (whose role gained TASK_MANAGE in the upgrade) creates a task on a legacy payment, assigns itself, and completes it',
    steps: 'lg_coll POST /api/tasks {PAYMENT, legacy Beta payment, dueDate today, assignees [USER lg_coll]}; GET /api/tasks?entityType=PAYMENT&entityId; POST /status DONE',
    expected: 'A6 tasks on a payment with title, due date, status, assignees; region = the payment region DEFAULT' },
  async () => {
    const c = await rt.createTask(T.lg_coll, { entityType: 'PAYMENT', entityId: S.p.b.id, title: 'Confirm legacy remittance', dueDate: rt.day(0), assignees: [rt.tok.user(S.u.coll.id)] });
    if (c.status !== 200 && c.status !== 201) return { ok: false, actual: `create ${c.status} ${short(c.json)}` };
    const l = await rt.tasks.list(T.lg_coll, { entityType: 'PAYMENT', entityId: S.p.b.id });
    const s = await rt.tasks.setStatus(T.lg_coll, c.json.id, 'DONE', c.json.version);
    const ok = (l.json?.content || []).some((x) => x.id === c.json.id) && c.json.regionCode === 'DEFAULT' && c.json.assignedToMe === true && s.status === 200 && s.json.status === 'DONE';
    return { ok, actual: `create ${c.status} region=${c.json.regionCode} assignedToMe=${c.json.assignedToMe} label=${c.json.entityLabel}; list has it=${(l.json?.content || []).some((x) => x.id === c.json.id)}; DONE ${s.status} ${s.json?.status}` };
  });

  await tc({ id: 'MIG-X-20', feature: 'Custom legacy role and new features', kind: 'API', prd: 'A6',
    title: 'A user of a custom pre-upgrade role that never received the new privileges is refused tasks, automation and threshold endpoints (403), but keeps its legacy invoice access',
    steps: 'lg_mgr GET /api/tasks, /api/automation/rules, POST /api/tasks, PUT /api/regions/{DEFAULT}/approval-thresholds; GET /api/invoices',
    expected: 'DESIGN §4.6 "the role alone decides": 403 for the new features; 200 invoices with the baseline total' },
  async () => {
    const a = await api('GET', '/api/tasks', { token: T.lg_mgr, query: { size: 10 } });
    const b = await api('GET', '/api/automation/rules', { token: T.lg_mgr, query: { size: 10 } });
    const c = await rt.createTask(T.lg_mgr, { entityType: 'INVOICE', entityId: S.i.g1.id, title: 'x' });
    const d = await rt.setThresholds(T.lg_mgr, def.id, [{ operation: 'PAYMENT_RECORD', amount: 1, windowHours: null }]);
    const inv = await total(T.lg_mgr, '/api/invoices');
    const ok = a.status === 403 && b.status === 403 && c.status === 403 && d.status === 403 && inv === S.before.lg_mgr.totals.invoices + 1 - 2;
    return { ok, actual: `tasks ${a.status}; rules ${b.status}; create task ${c.status}; thresholds ${d.status}; invoices ${inv} (baseline ${S.before.lg_mgr.totals.invoices} + 1 created after upgrade - 2 moved to NORTH)` };
  });

  // ---- UI cases (the web app talks to this backend on 18107) ------------------------------------------------------
  await tc({ id: 'MIG-X-U1', feature: 'Region selector appears for a migrated admin', kind: 'UI', prd: 'B1',
    title: 'Once a second region exists, the migrated admin sees the region selector (DEFAULT and NORTH) and a Region column showing where each legacy customer lives',
    steps: 'openApp(admin) /customers; read app bar + list; open region selector; screenshot',
    expected: 'DESIGN §7.3.1/§7.5: selector visible when regions > 1, items "DEFAULT · …" and "NORTH · North"; Region column; Alpha NORTH, Beta/Gamma DEFAULT' },
  async (ctx) => {
    const app = await openAt(ctx, { token: admin }, '/customers');
    const page = app.page;
    await page.waitForTimeout(2000);
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U1-customers');
    const sel = await rt.regionSelector(page);
    let items = [];
    if (sel) {
      await rt.clickAt(page, sel.x, sel.y, 1500);
      items = (await rt.semantics(page)).filter((n) => n.role === 'menuitemcheckbox').map((n) => n.label || n.text);
      await snap(ctx, page, 'U1-selector');
      await rt.dismiss(page);
    }
    // screenText keeps reading order: the cells of Alpha's row follow its name, up to the next customer
    const alphaNode = rowLines(txt, 'Legacy Alpha', 'Legacy Beta').join(' | ');
    const alphaRow = /NORTH/.test(alphaNode) && !/DEFAULT/.test(alphaNode);
    const ok = !!sel && items.some((x) => /^DEFAULT/.test(x)) && items.some((x) => /^NORTH/.test(x)) && /Region/.test(txt) && alphaRow && /Legacy Alpha/.test(txt);
    return { ok, actual: `selector=${sel && (sel.label || sel.text)} items=${short(items)} regionColumn=${/Region/.test(txt)} alpha=${/Legacy Alpha/.test(txt)} northShown=${alphaRow} alphaNode="${alphaNode.replace(/\n/g, ' | ')}"`, evidence: short(txt, 1200) };
  });

  await tc({ id: 'MIG-X-U2', feature: 'As-of picker on migrated data', kind: 'UI', prd: 'B3',
    title: 'The migrated admin views customers as of 2 days ago: read-only banner, Legacy Alpha shown in DEFAULT (before the move), and the request carries asOf',
    steps: 'openApp(admin) /customers; chooseAsOf(day(-2)); read banner, list, requests; screenshot; Back to today',
    expected: 'B3 as-of lists incl. region a record belonged to then; DESIGN §7.3.1 banner "Viewing … — read only"' },
  async (ctx) => {
    const app = await openAt(ctx, { token: admin }, '/customers');
    const page = app.page;
    const chip = await rt.chooseAsOf(page, rt.day(-2));
    await page.waitForTimeout(2500);
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U2-asof');
    const sent = app.requests.some((r) => /\/api\/customers\?/.test(r.url) && r.url.includes(`asOf=${rt.day(-2)}`));
    const alphaLine = rowLines(txt, 'Legacy Alpha', 'Legacy Beta').join(' | ');
    const ok = /read only/i.test(txt) && sent && /Legacy Alpha/.test(txt) && /DEFAULT|Default/.test(alphaLine) && !/NORTH|North/.test(alphaLine);
    return { ok, actual: `chip="${chip}" banner=${/read only/i.test(txt)} asOf sent=${sent} alphaRow="${alphaLine.replace(/\n/g, ' | ')}"`, evidence: short(txt, 1200) };
  });

  await tc({ id: 'MIG-X-U3', feature: 'As-of before the upgrade in the UI', kind: 'UI', prd: 'B3',
    title: 'Picking a date before history starts (the day before the upgrade) is not accepted; the app stays on today and shows no broken screen',
    steps: 'openApp(admin) /invoices; open as-of chip; switch to input; type day(-11); View; read screen; screenshot',
    expected: 'DESIGN §7.3.1: date picker firstDate = historyStartsOn, so the date is refused by the picker (or an AS_OF_BEFORE_HISTORY message); no apiErrors 5xx, no page errors' },
  async (ctx) => {
    const app = await openAt(ctx, { token: admin }, '/invoices');
    const page = app.page;
    const chip = await rt.asOfChip(page);
    await rt.clickAt(page, chip.x, chip.y, 2000);
    await rt.tap(page, /Switch to input/, { wait: 800 });
    const d = rt.day(-11).split('-');
    await rt.fillField(page, /Enter Date|mm\/dd\/yyyy/i, `${d[1]}/${d[2]}/${d[0]}`);
    await rt.tap(page, 'View', { wait: 2500 });
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U3-before-history');
    const asOfSent = app.requests.some((r) => r.url.includes(`asOf=${rt.day(-11)}`) && r.status === 200);
    const refused = /Out of range|History is available|not available|before/i.test(txt);
    const errs = app.apiErrors.filter((e) => e.status >= 500);
    const ok = !asOfSent && refused && errs.length === 0 && app.pageErrors.length === 0 && !/read only/i.test(txt);
    return { ok, actual: `refusal shown=${refused} asOf-200 sent=${asOfSent} 5xx=${short(errs)} pageErrors=${short(app.pageErrors)} banner=${/read only/i.test(txt)}`, evidence: short(txt, 1000) };
  });

  await tc({ id: 'MIG-X-U4', feature: 'Keyboard login of a migrated user and My tasks', kind: 'UI', prd: 'A6',
    title: 'The migrated SALES_POC signs in with the keyboard only (type, Tab, Enter) using its unchanged password and sees the automation task on the legacy invoice in My tasks',
    steps: 'openApp() login screen; click Username; type lg_sales; Tab; type password; Enter; go /my-tasks; read; screenshot',
    expected: 'existing credentials keep working; A6 task visible to its assignee with title, due date, status' },
  async (ctx) => {
    const app = ctx.app = await rt.openApp({});
    const page = app.page;
    await rt.fillField(page, 'Username', 'lg_sales');
    await page.keyboard.press('Tab');
    await page.waitForTimeout(300);
    await rt.typeText(page, PW);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(5000);
    await rt.enableSemantics(page);
    const afterLogin = (await rt.screenText(page)).join('\n');
    await rt.go(page, '/my-tasks', 4500);
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U4-mytasks');
    const loggedIn = !/Sign in/.test(afterLogin) || /My tasks|Dashboard/.test(afterLogin);
    const title = S.taskB?.title || 'Chase';
    const ok = loggedIn && txt.includes(title) && /Open|OPEN/.test(txt);
    return { ok, actual: `loggedIn=${loggedIn}; task "${title}" shown=${txt.includes(title)}`, evidence: short(txt, 1200) };
  });

  await tc({ id: 'MIG-X-U5', feature: 'Awaiting-approval chip on a migrated invoice', kind: 'UI', prd: 'B2',
    title: 'A migrated VIEWER sees the held cancel on the legacy Gamma invoice as "Awaiting approval" in the invoices list, and the invoice is still not cancelled',
    steps: 'openApp(lg_view) /invoices; find the Gamma 700 invoice row; screenshot; open it',
    expected: 'B2 pending visible to everyone who can see the record without having taken effect; triage decision: "Awaiting approval" chip on list rows' },
  async (ctx) => {
    const inv = need(await api('GET', `/api/invoices/${S.i.g1.id}`, { token: admin }), 'g1');
    const app = await openAt(ctx, { token: T.lg_view }, '/invoices');
    const page = app.page;
    await page.waitForTimeout(1500);
    const nodes = await rt.semantics(page);
    const row = nodes.map((n) => `${n.label || ''}\n${n.text || ''}`).find((s) => s.includes(inv.invoiceNumber)) || '';
    const all = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U5-invoices');
    await rt.go(page, `/invoices/${S.i.g1.id}`, 4000);
    const dtxt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U5-invoice-detail');
    const chip = /Awaiting approval/i.test(row) || (/Awaiting approval/i.test(all) && all.includes(inv.invoiceNumber));
    const ok = chip && !/CANCELLED|Cancelled/.test(row) && /awaiting|pending/i.test(dtxt);
    return { ok, actual: `row="${row.replace(/\n/g, ' | ')}" chip=${chip}; detail mentions pending=${/awaiting|pending/i.test(dtxt)}`, evidence: short(dtxt, 1000) };
  });

  await tc({ id: 'MIG-X-U6', feature: 'Migrated customer login after a region move', kind: 'UI', prd: 'B1',
    title: 'The migrated customer login of Legacy Alpha (moved to NORTH) still signs in and sees its own legacy invoices, with no region selector and no as-of chip',
    steps: 'openApp login screen; uiLogin lgalpha; go /invoices; read; screenshot',
    expected: 'DESIGN §7.3.1 selector/chip hidden for customer logins; D17 customer logins see their own records regardless of region' },
  async (ctx) => {
    const app = ctx.app = await rt.openApp({});
    const page = app.page;
    await rt.uiLogin(page, 'lgalpha', PW);
    await rt.go(page, '/invoices', 4500);
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U6-customer-invoices');
    const nums = [S.i.a1.invoiceNumber, S.i.a2.invoiceNumber];
    const sel = await rt.regionSelector(page);
    const chip = await rt.asOfChip(page);
    const ok = nums.every((n) => txt.includes(n)) && !sel && !chip && !txt.includes(S.i.b1.invoiceNumber);
    return { ok, actual: `own invoices shown=${nums.map((n) => txt.includes(n))} selector=${!!sel} chip=${!!chip} otherCustomerLeak=${txt.includes(S.i.b1.invoiceNumber)}`, evidence: short(txt, 1000) };
  });

  await tc({ id: 'MIG-X-U7', feature: 'Custom legacy role navigation', kind: 'UI', prd: 'A6',
    title: 'The custom-role user sees no My tasks / Tasks / Automation / Regions entries, and opening /tasks directly shows a no-access state instead of a broken page',
    steps: 'openApp(lg_mgr) /invoices; read nav; go /tasks; read; screenshot',
    expected: 'DESIGN §7.3.2 nav requiredAnyOf; §7.3.3 route guard needs(...) (router.dart:57 redirects to /) -> no tasks screen; no page errors' },
  async (ctx) => {
    const app = await openAt(ctx, { token: T.lg_mgr, width: 1440 }, '/invoices');
    const page = app.page;
    const nav = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U7-nav');
    await rt.go(page, '/tasks', 4000);
    const txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U7-tasks-direct');
    const navLeak = ['My tasks', 'Automation', 'Regions'].filter((x) => nav.split('\n').includes(x));
    // router.dart needs(): a route the user may not open redirects to '/' (the dashboard)
    const url = await page.evaluate(() => location.hash);
    const noAccess = /permission|not allowed|access|don't have|do not have|not authori[sz]ed|Forbidden/i.test(txt)
      || ((url === '#/' || url === '' || url === '#') && /Welcome/.test(txt) && !/My tasks|Add task|New task/.test(txt));
    const ok = navLeak.length === 0 && noAccess && app.pageErrors.length === 0;
    return { ok, actual: `nav entries leaked=${short(navLeak)} /tasks handled (redirect to dashboard or message)=${noAccess} pageErrors=${short(app.pageErrors)} apiErrors=${short(app.apiErrors)}`, evidence: short(txt, 1000) };
  });

  await tc({ id: 'MIG-X-U8', feature: 'Rule detail for a rule on migrated data', kind: 'UI', prd: 'A5',
    title: 'The migrated admin opens the scheduled rule and sees its last run (Run now) with the matched count, and a Run now action',
    steps: 'openApp(admin) /automation/{rule1}; read; screenshot',
    expected: 'A5 "a rule can be scheduled or run from the UI whenever wanted": Run now visible; last run SUCCEEDED 2 matched shown' },
  async (ctx) => {
    if (!S.rule1) return { status: 'BLOCKED', actual: 'rule from MIG-X-16 missing' };
    const app = await openAt(ctx, { token: admin }, `/automation/${S.rule1.id}`);
    const page = app.page;
    await page.waitForTimeout(1500);
    let txt = (await rt.screenText(page)).join('\n');
    await snap(ctx, page, 'U8-rule');
    if (await rt.exists(page, /^Runs/)) { await rt.tap(page, /^Runs/, { wait: 2500 }); txt += '\n' + (await rt.screenText(page)).join('\n'); await snap(ctx, page, 'U8-rule-runs'); }
    const ok = /Legacy chase/.test(txt) && /Run now/i.test(txt) && /Succeeded|SUCCEEDED/i.test(txt);
    return { ok, actual: `name=${/Legacy chase/.test(txt)} runNow=${/Run now/i.test(txt)} succeeded=${/Succeeded|SUCCEEDED/i.test(txt)}`, evidence: short(txt, 1500) };
  });

  // ---- second boot (idempotency after real use) ------------------------------------------------------------------
  await tc({ id: 'MIG-X-21', feature: 'Second boot respects post-upgrade choices', kind: 'API', prd: 'B1',
    title: 'After admins used the upgraded system (renamed DEFAULT, emptied a migrated user\'s grants), a restart re-grants nobody, keeps the rename and one default region, and writes no second backfill',
    steps: 'admin PUT /api/regions/{DEFAULT} name "Head office"; PUT /api/users/{lg_view2}/region-grants []; count REGIONS_BACKFILLED audits; stop + boot new jar; re-check',
    expected: 'DESIGN §4.6 F6 "a user later left deliberately with no grants is never re-granted"; §3.11 step 1 only when no default exists' },
  async () => {
    const r0 = need(await api('GET', `/api/regions/${def.id}`, { token: admin }), 'region');
    const ren = await api('PUT', `/api/regions/${def.id}`, { token: admin, body: { name: 'Head office', active: true, version: r0.version } });
    const g = await rt.setGrants(admin, S.u.view2.id, []);
    const v2 = await totals(await rt.login('lg_view2', PW));
    const auditsBefore = one("select count(*) from audit_logs where action='REGIONS_BACKFILLED'");
    const grantsBefore = one('select count(*) from user_region_grants');
    await I.stopNewBackend();
    await bootNew(LOG_NEW2);
    S.admin = await rt.adminToken();
    const regs = await rt.regions(S.admin);
    const defs = regs.filter((x) => x.defaultRegion);
    const auditsAfter = one("select count(*) from audit_logs where action='REGIONS_BACKFILLED'");
    const grantsAfter = one('select count(*) from user_region_grants');
    const v2g = one(`select count(*) from user_region_grants where user_id=${S.u.view2.id}`);
    const errs = I.grepLog(LOG_NEW2, /ERROR/).slice(0, 5);
    const ok = ren.status === 200 && g.status === 200 && Object.values(v2).every((x) => x === 0 || x === 'HTTP 403') && defs.length === 1 && defs[0].id === def.id && defs[0].name === 'Head office'
      && regs.length === 2 && auditsAfter === auditsBefore && grantsAfter === grantsBefore && v2g === '0';
    return { ok, actual: `rename ${ren.status}; empty grants ${g.status}, lg_view2 totals ${JSON.stringify(v2)}; after restart: regions ${regs.map((x) => `${x.code}:${x.name}:${x.defaultRegion}`)}; backfill audits ${auditsBefore}->${auditsAfter}; grants ${grantsBefore}->${grantsAfter}; lg_view2 grants ${v2g}; ERROR lines ${errs.length}`,
      evidence: short(errs, 800) };
  });

  await tc({ id: 'MIG-X-22', feature: 'Held change survives a restart', kind: 'API', prd: 'B2',
    title: 'The held cancel on the migrated invoice is still pending after the restart, then a rejection closes it and the invoice stays open',
    steps: 'GET /api/approvals/{heldCancel} after restart; create a second approver? (none) -> admin rejects (reject by maker = withdraw semantics?) ; use withdraw by maker',
    expected: 'B2 pending changes persist; maker can withdraw; invoice unchanged' },
  async () => {
    if (!S.heldCancel) return { status: 'BLOCKED', actual: 'no held cancel' };
    const g = await rt.approvals.get(S.admin, S.heldCancel);
    const w = await rt.approvals.withdraw(S.admin, S.heldCancel);
    const g2 = await rt.approvals.get(S.admin, S.heldCancel);
    const inv = need(await api('GET', `/api/invoices/${S.i.g1.id}`, { token: S.admin }), 'g1');
    const ok = g.json?.status === 'PENDING' && w.status === 200 && g2.json?.status === 'WITHDRAWN' && inv.status !== 'CANCELLED';
    return { ok, actual: `after restart ${g.status} ${g.json?.status}; withdraw ${w.status}; now ${g2.json?.status}; invoice ${inv.status}` };
  });

  save();
  const n = (s) => cases.filter((c) => c.status === s).length;
  console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED in ${Math.round((Date.now() - t0) / 1000)}s`);
}

main().catch((e) => { console.error('FATAL', e); save(); process.exitCode = 1; })
  .finally(async () => { try { await I.stopExtras(); } catch { /* ignore */ } });
