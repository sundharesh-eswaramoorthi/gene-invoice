#!/usr/bin/env node
// Migration suite: upgrade of EXISTING data.
//
//   node run.js <round>          (after env/up.sh migration 7; see run.sh, which also brings everything down)
//
// Phases
//   P0  read-only pg_dump of the user's dev DB (gene-invoice-db) -> restored into e2e_migration (upgraded by the
//       new jar) and e2e_migration_base (booted by the BASELINE jar, the "before" reference).
//   P1  the new jar on the h2 profile (in-memory) still starts.
//   P2  dataset A (dev DB) with the default SCHEMA_HALT_ON_ERROR=true: baseline and new jar compared.
//   P3  dataset A on the baseline jar: what every existing user sees (the "before" snapshot) + write controls.
//   P4  dataset B: a representative dataset seeded through the BASELINE jar's API on a fresh DB, snapshotted,
//       then cloned (e2e_migration_seedup) for the upgrade.
//   P5  dataset B upgraded by the new jar (clean path: halt_on_error=true), checks + comparisons + post-upgrade
//       automation + a multi-region UI case.
//   P6  dataset A upgraded by the new jar: checks, comparisons, scoping/as-of on migrated data, UI logins,
//       writes after the upgrade, and a second boot (idempotency).
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const I = require('./infra');
const C = require('./compare');
const S = require('./seed-baseline');

const ROUND = Number(process.argv[2] || process.env.E2E_ROUND || 1);
const R = rt.results({ area: 'migration', round: ROUND });
const SHOTS = path.join(I.E2E, 'runs', `r${ROUND}`, 'migration', 'shots');
fs.mkdirSync(SHOTS, { recursive: true });
const PW = 'Passw0rd!';
// bcrypt('Passw0rd!'), set on the SAME chosen existing users in both copies before any boot, so their views can be
// compared before and after the upgrade (their real passwords are unknown). Every other hash is left untouched.
const PW_HASH = '$2a$10$1wnsxfnM327NCgL44NTFruDV8Wezil2orWNHsqZVT6EwMDiOlyfRS';
const TODAY = rt.day(0);
const LOG = {
  h2: path.join(I.RT, 'h2.log'),
  baseHaltA: path.join(I.RT, 'baseline-halt.log'),
  newHaltA: path.join(I.RT, 'new-halt.log'),
  baseA: path.join(I.RT, 'baseline.log'),
  newA: path.join(I.RT, 'backend-upgrade.log'),
  newA2: path.join(I.RT, 'backend-upgrade-restart.log'),
  baseB: path.join(I.RT, 'baseline-seed.log'),
  newB: path.join(I.RT, 'backend-seedup.log'),
  newC: path.join(I.RT, 'backend-legacy.log'),
  newC2: path.join(I.RT, 'backend-legacy-restart.log'),
  newCsoft: path.join(I.RT, 'backend-legacy-nohalt.log'),
  newChalf: path.join(I.RT, 'backend-legacy-halfmigrated.log'),
  newCsoft3: path.join(I.RT, 'backend-legacy-nohalt-r3.log'),
};
const LEGACY_SQL = path.join(__dirname, 'data', 'legacy-automation.sql');
// Dataset C creates (POST /api/invoices, POST /api/automation/rules): triage r2 expects 201; the rebuilt jar answers 200
// (InvoiceController -> Outcomes.respond ResponseEntity.ok; RuleController.create has no @ResponseStatus), so both count.
const CREATED_OK = [201, 200];
const DB_SEED = 'e2e_migration_seed';
const DB_SEEDUP = 'e2e_migration_seedup';
const DB_LEGACY2 = 'e2e_migration_legacy2'; // round 3: a third clone of the legacy fixture for the halt_on_error=false boot
const state = {};

// ---- case helpers -----------------------------------------------------------------------------------------

function short(v, n = 1500) {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
}
/** Runs one case; an exception becomes BLOCKED with the error. fn returns {ok|status, actual, evidence, ...}. */
async function kase(meta, fn) {
  try {
    const r = await fn();
    return R.rec({ kind: 'API', ...meta, ...r, actual: short(r.actual), evidence: short(r.evidence || '', 2500) });
  } catch (e) {
    return R.rec({ kind: 'API', ...meta, status: 'BLOCKED', actual: `exception: ${short(e && e.stack ? e.stack : e, 1200)}` });
  }
}
function notTested(meta, why) { return R.rec({ kind: 'API', ...meta, status: 'NOT_TESTED', actual: why }); }

// ---- log helpers ------------------------------------------------------------------------------------------

function read(file) { return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''; }
function levelLines(file, level) { return read(file).split('\n').filter((l) => new RegExp(`^\\S+\\s+${level} `).test(l)); }
/** The DDL statements `ddl-auto: update` could not apply, normalised. */
function ddlFailures(file) {
  const lines = read(file).split('\n');
  const out = new Set();
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes('GenerationTarget encountered exception accepting command')) continue;
    let j = i; let buf = '';
    while (j < lines.length && j < i + 8) { buf += ' ' + lines[j]; if (lines[j].includes('via JDBC [')) break; j++; }
    const m = /Error executing DDL "([\s\S]*?)" via JDBC \[([^\]]*)\]/.exec(buf);
    if (m) out.add(`${m[1].replace(/\s+/g, ' ').trim()} => ${m[2].trim()}`);
  }
  return [...out].sort();
}
/** Warnings from the application's own classes (the upgrade beans, history, seeder...). */
function appWarns(file) {
  return levelLines(file, 'WARN').filter((l) => /\sc\.g\.|com\.geneinvoice/.test(l));
}
function hasLine(file, re) { return read(file).split('\n').some((l) => re.test(l)); }

// ---- SQL helpers ------------------------------------------------------------------------------------------

const REGIONAL = ['customers', 'invoices', 'payments', 'payment_promises', 'disputes'];
const VERSIONED = [
  ['customers', 'customer_versions'], ['invoices', 'invoice_versions'], ['payments', 'payment_versions'],
  ['payment_allocations', 'payment_allocation_versions'], ['payment_promises', 'promise_versions'],
  ['disputes', 'dispute_versions'], ['customer_pocs', 'customer_poc_versions'], ['products', 'product_versions'],
];
const q1 = (db, sql) => I.one(db, sql);
const n1 = (db, sql) => Number(I.one(db, sql));
function tableExists(db, t) { return n1(db, `select count(*) from information_schema.tables where table_schema='public' and table_name='${t}'`) > 0; }
function counts(db, tables) { return Object.fromEntries(tables.map((t) => [t, tableExists(db, t) ? n1(db, `select count(*) from ${t}`) : null])); }

function regionFacts(db) {
  const f = { nulls: {}, nullable: {}, mismatch: {}, perRegion: {} };
  for (const t of REGIONAL) {
    f.nulls[t] = n1(db, `select count(*) from ${t} where region_id is null`);
    f.nullable[t] = q1(db, `select is_nullable from information_schema.columns where table_schema='public' and table_name='${t}' and column_name='region_id'`);
    if (t !== 'customers') {
      f.mismatch[t] = n1(db, `select count(*) from ${t} x join customers c on c.id = x.customer_id where x.region_id <> c.region_id`);
    }
    f.perRegion[t] = I.psql(db, `select r.code, count(*) from ${t} x join regions r on r.id = x.region_id group by r.code order by 1`).map((r) => r.join('=')).join(',');
  }
  return f;
}
/** Every column named region_id in the schema, and rows pointing at a region that does not exist. */
function orphanRegions(db) {
  const cols = I.psql(db, `select table_name from information_schema.columns where table_schema='public' and column_name='region_id' order by 1`).map((r) => r[0]);
  const out = {};
  for (const t of cols) out[t] = n1(db, `select count(*) from ${t} x where x.region_id is not null and not exists (select 1 from regions r where r.id = x.region_id)`);
  const fks = I.psql(db, `select conrelid::regclass::text from pg_constraint where contype='f' and confrelid='regions'::regclass order by 1`).map((r) => r[0]);
  return { cols, out, fks };
}
function grantFacts(db) {
  return {
    staff: n1(db, 'select count(*) from users where customer_id is null'),
    customerLogins: n1(db, 'select count(*) from users where customer_id is not null'),
    staffWithOneDefaultApprove: n1(db, `select count(*) from users u where u.customer_id is null and (select count(*) from user_region_grants g where g.user_id=u.id)=1
      and exists (select 1 from user_region_grants g join regions r on r.id=g.region_id where g.user_id=u.id and r.is_default and g.access_level='APPROVE' and g.record_scope='ALL')`),
    customerGrants: n1(db, 'select count(*) from user_region_grants g join users u on u.id=g.user_id where u.customer_id is not null'),
    pendingFlags: n1(db, 'select count(*) from users where region_backfill_pending'),
    grantsTotal: n1(db, 'select count(*) from user_region_grants'),
  };
}
function historyFacts(db) {
  const f = { meta: I.psql(db, 'select started_at, baseline_completed_at from history_meta'), types: {} };
  for (const [live, ver] of VERSIONED) {
    f.types[live] = {
      live: n1(db, `select count(*) from ${live}`),
      open: n1(db, `select count(*) from ${ver} where valid_to > now()`),
      missing: n1(db, `select count(*) from ${live} x where not exists (select 1 from ${ver} v where v.entity_id = x.id and v.valid_to > now())`),
      dup: n1(db, `select count(*) from (select entity_id from ${ver} where valid_to > now() group by entity_id having count(*) > 1) s`),
    };
  }
  const links = n1(db, 'select (select count(*) from payment_promise_invoices) + (select count(*) from payment_promise_payments)');
  f.types.promise_links = { live: links, open: n1(db, 'select count(*) from entity_link_versions where valid_to > now()'), missing: null, dup: null };
  return f;
}
function passwordHashes(db) { return Object.fromEntries(I.psql(db, 'select username, password from users order by id')); }

/** Picks existing users to compare: one active user per staff role, two customer logins, one inactive staff user. */
function pickUsers(db) {
  const rows = I.psql(db, `select u.username, r.name, u.active, (u.customer_id is not null) from users u join roles r on r.id = u.role_id order by u.id`);
  const picked = []; const seenRole = new Set();
  for (const [u, role, active, isCust] of rows) {
    if (isCust === 't' || active !== 't' || seenRole.has(role)) continue;
    seenRole.add(role); picked.push({ username: u, role, customer: false });
  }
  rows.filter((r) => r[3] === 't' && r[2] === 't').slice(0, 2).forEach(([u, role]) => picked.push({ username: u, role, customer: true }));
  const inactive = rows.find((r) => r[3] === 'f' && r[2] !== 't');
  return { picked, inactive: inactive ? inactive[0] : null };
}

// ---- boot helpers -----------------------------------------------------------------------------------------

async function boot({ kind, jar, port, db, log, profile, halt = true, docs }) {
  fs.writeFileSync(log, '');
  const pid = I.startBackend({ kind, jar, port, db, profile, log, docs: docs || path.join(I.RT, `documents-${kind}-${db || 'mem'}`),
    extraEnv: halt ? {} : { SCHEMA_HALT_ON_ERROR: 'false' } });
  const w = await I.waitLogin(port, { pid, timeoutMs: 180000 });
  return { pid, up: w.ok, wait: w };
}
async function waitExit(pid, ms = 90000) {
  const end = Date.now() + ms;
  while (Date.now() < end && I.alive(pid)) await rt.sleep(1000);
  return !I.alive(pid);
}
async function tokenFor(c, username, password) {
  const r = await c.login(username, password);
  return r.status === 200 ? r.json.token : null;
}

/**
 * Compares two snapshots; returns {diffs, summary, sc2}. The customer audit timeline (includeRelated) is compared as a
 * multiset (rows written by each app's own startup promise sweep carry each app's clock, so their relative order can
 * differ) and under hardening fix SC-2 (harden/FIXLOG-backend.md:29): staff now take in only related records they can
 * open, so a baseline row may be missing when it is about an invoice/payment/promise/dispute outside the user's
 * lists (or is a PAYMENT_APPLIED allocation row for a user who does not see every payment). No row may be added.
 */
function compareSnapshots(sb, sn) {
  const diffs = []; const sc2 = [];
  const visible = (type) => {
    const l = sn.lists[{ INVOICE: 'invoices', PAYMENT: 'payments', PROMISE: 'promises', DISPUTE: 'disputes' }[type]];
    return l && l.status === 200 ? new Set(l.ids) : new Set();
  };
  const seesAllPayments = sn.lists.payments.status === 200 && !(sn.lists.payments.lockedFilters || []).length;
  for (const sec of Object.keys(sb)) {
    for (const k of Object.keys(sb[sec])) {
      if (sec === 'details' && k.startsWith('/api/audit?')) {
        const b = sb[sec][k]; const n = sn[sec][k] || {};
        if (b.status !== n.status) { diffs.push(`${sec}.${k}.status: ${b.status} vs ${n.status}`); continue; }
        const left = [...(n.actions || [])];
        const removed = [];
        for (const a of b.actions || []) { const i = left.indexOf(a); if (i >= 0) left.splice(i, 1); else removed.push(a); }
        if (left.length) diffs.push(`${sec}.${k}: rows added after the upgrade ${JSON.stringify(left.slice(0, 5))}`);
        const unexplained = removed.filter((a) => {
          const [type, id, action] = a.split(':');
          if (['INVOICE', 'PAYMENT', 'PROMISE', 'DISPUTE'].includes(type) && !visible(type).has(Number(id))) return false;
          if (action === 'PAYMENT_APPLIED' && !seesAllPayments) return false;
          return true;
        });
        if (unexplained.length) diffs.push(`${sec}.${k}: rows lost ${JSON.stringify(unexplained.slice(0, 5))}`);
        if (removed.length) sc2.push(`${k}: ${removed.length} row(s) about records outside the user's scope dropped (SC-2)`);
        continue;
      }
      C.diff(sb[sec][k], sn[sec] ? sn[sec][k] : undefined, `${sec}.${k}`, diffs, 80);
    }
  }
  const summary = Object.fromEntries(Object.entries(sn.lists).map(([k, v]) => [k, `${sb.lists[k].status}/${sb.lists[k].totalElements} -> ${v.status}/${v.totalElements}`]));
  return { diffs, summary, sc2 };
}

// =============================================================================================================

async function main() {
  // ---------------------------------------------------------------------------------------------------------
  // P0 — dump and restore
  // ---------------------------------------------------------------------------------------------------------
  let haveA = false;
  await kase({ id: 'MIG-00', feature: 'Dump and restore', prd: 'B1',
    title: 'The dev database is dumped read-only and restored into two identical e2e copies',
    steps: 'docker exec gene-invoice-db pg_dump -U geneinvoice geneinvoice > data/dev.sql; restore (owner lines stripped) into e2e_migration and e2e_migration_base; compare row counts with the dump',
    expected: 'dump non-empty; both copies hold exactly the dump\'s rows (customers, invoices, payments, ...)' },
  async () => {
    I.dumpDev({ force: true });
    state.dumpCounts = I.dumpCounts();
    if (!state.dumpCounts.customers) return { status: 'BLOCKED', actual: `dev DB empty or unavailable: ${JSON.stringify(state.dumpCounts)}` };
    I.restore(I.DB_NEW); I.restore(I.DB_BASE);
    const keys = ['customers', 'invoices', 'payments', 'payment_promises', 'disputes', 'users', 'customer_pocs', 'payment_allocations', 'notifications', 'audit_logs'];
    const a = counts(I.DB_NEW, keys); const b = counts(I.DB_BASE, keys);
    const exp = Object.fromEntries(keys.map((k) => [k, state.dumpCounts[k] ?? null]));
    haveA = JSON.stringify(a) === JSON.stringify(exp) && JSON.stringify(b) === JSON.stringify(exp);
    state.hashesBefore = passwordHashes(I.DB_NEW);
    return { ok: haveA, actual: { dump: exp, e2e_migration: a, e2e_migration_base: b, tablesInDump: Object.keys(state.dumpCounts).length },
      evidence: `dump ${fs.statSync(I.DUMP).size} bytes, ${Object.keys(state.dumpCounts).length} tables; regions table in dump: ${'regions' in state.dumpCounts}` };
  });

  // Users to compare in dataset A (chosen from the data itself).
  let usersA = null;
  function prepareUsersA() {
    const pick = pickUsers(I.DB_NEW);
    usersA = pick.picked.map((u) => ({ ...u, password: u.username === 'admin' ? 'admin123' : PW }));
    usersAHolder.list = usersA;
    state.inactiveA = pick.inactive;
    const reset = usersA.filter((u) => u.username !== 'admin').map((u) => u.username);
    if (state.inactiveA) reset.push(state.inactiveA);
    for (const db of [I.DB_NEW, I.DB_BASE]) {
      I.psql(db, `update users set password='${PW_HASH}' where username in (${reset.map((u) => `'${u}'`).join(',')})`);
    }
    state.resetUsers = reset;
  }
  if (haveA) prepareUsersA();

  // ---------------------------------------------------------------------------------------------------------
  // P1 — h2 profile
  // ---------------------------------------------------------------------------------------------------------
  await kase({ id: 'MIG-H2-01', feature: 'h2 profile', prd: 'B1',
    title: 'The new jar still starts on the h2 profile (in-memory) with the default region and admin grant',
    steps: `SPRING_PROFILES_ACTIVE=h2 java -jar dist/backend.jar on :${I.PORT_H2}; POST /api/auth/login admin/admin123; GET /api/regions; GET /api/history/range; grep log`,
    expected: 'Started; no ERROR log line and no DDL failure; regions = [DEFAULT (default, active)]; admin /me regions = DEFAULT APPROVE/ALL; history range 200',
    codeRef: 'backend/src/main/resources/application.yml (h2 profile)' },
  async () => {
    const b = await boot({ kind: 'h2', jar: I.NEW_JAR, port: I.PORT_H2, db: null, profile: 'h2', log: LOG.h2 });
    state.h2 = b;
    if (!b.up) return { ok: false, severity: 'high', actual: `did not start: ${short(b.wait)}`, evidence: `${LOG.h2}: ${levelLines(LOG.h2, 'ERROR').slice(0, 3).join(' | ')}` };
    const c = C.client(I.PORT_H2);
    const t = b.wait.json.token;
    const regs = await c.api('GET', '/api/regions', { token: t });
    const range = await c.api('GET', '/api/history/range', { token: t });
    const me = b.wait.json.user;
    const errs = levelLines(LOG.h2, 'ERROR'); const ddl = ddlFailures(LOG.h2); const warns = appWarns(LOG.h2);
    const rs = regs.json && regs.json.content || [];
    const ok = errs.length === 0 && ddl.length === 0 && rs.length === 1 && rs[0].code === 'DEFAULT' && rs[0].defaultRegion && rs[0].active
      && (me.regions || []).length === 1 && me.regions[0].level === 'APPROVE' && me.regions[0].scope === 'ALL' && range.status === 200;
    return { ok, actual: { errors: errs.slice(0, 3), ddl, appWarns: warns.slice(0, 5), regions: rs, meRegions: me.regions && me.regions.map((r) => `${r.code}/${r.level}/${r.scope}`), range: [range.status, range.json] },
      evidence: `${LOG.h2}: ${hasLine(LOG.h2, /Started GeneInvoiceApplication/) ? 'Started GeneInvoiceApplication' : 'no Started line'}` };
  });

  await kase({ id: 'MIG-H2-02', feature: 'h2 profile', prd: 'A5',
    title: 'On h2 a save and a rule work (new tables created cleanly, outbox accepts events)',
    steps: 'on the h2 backend: POST /api/customers {name, username, password} (no regionId: single region); POST /api/automation/rules (MANUAL CUSTOMER rule, CREATE_TASK); count automation_events via GET /api/automation/health',
    expected: 'customer 200 with regionCode DEFAULT; rule 200' },
  async () => {
    if (!state.h2 || !state.h2.up) return { status: 'BLOCKED', actual: 'h2 backend not up' };
    const c = C.client(I.PORT_H2); const t = state.h2.wait.json.token;
    const cu = await c.api('POST', '/api/customers', { token: t, body: { name: 'H2 Probe', email: 'h2probe@e2e.local', username: rt.uniq('h2c'), password: PW } });
    const ru = await c.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger: { kind: 'MANUAL', entityType: 'CUSTOMER' }, actions: [rt.action.task({ title: 'h2 {{Customer.Name}}' })] }) });
    const h = await c.api('GET', '/api/automation/health', { token: t });
    return { ok: cu.status === 200 && cu.json.regionCode === 'DEFAULT' && ru.status === 200,
      actual: { customer: [cu.status, cu.json && { id: cu.json.id, regionCode: cu.json.regionCode }], rule: [ru.status, ru.status === 200 ? ru.json.id : ru.text.slice(0, 300)], health: [h.status, h.json] } };
  });
  await I.stopExtras(['h2']);

  // ---------------------------------------------------------------------------------------------------------
  // P2 — dataset A with the default SCHEMA_HALT_ON_ERROR=true
  // ---------------------------------------------------------------------------------------------------------
  await I.stopNewBackend(); // up.sh's backend (empty e2e_migration); every later new-jar boot is recorded for down.sh
  if (haveA) {
    await kase({ id: 'MIG-A-01', feature: 'Startup on existing data', prd: 'B1',
      title: 'With the default halt_on_error the new jar fails on the dev DB exactly where the baseline jar fails (no new schema error)',
      steps: 'boot BASELINE jar on e2e_migration_base and the NEW jar on e2e_migration, both with SCHEMA_HALT_ON_ERROR unset (true); collect the halting DDL',
      expected: 'Scope: "startup succeeds with no schema errors". The new jar must not introduce a schema error of its own; any failure must be one the baseline has too',
      codeRef: 'backend/src/main/resources/application.yml (hbm2ddl.halt_on_error)' },
    async () => {
      const b = await boot({ kind: 'baseline', jar: I.BASE_JAR, port: I.PORT_BASE, db: I.DB_BASE, log: LOG.baseHaltA });
      if (b.up) await I.stopExtras(['baseline']); else await waitExit(b.pid, 5000);
      const n = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: I.DB_NEW, log: LOG.newHaltA });
      if (n.up) await I.stopNewBackend(); else await waitExit(n.pid, 5000);
      const halting = (f) => { const m = /Halting on error : Error executing DDL "([\s\S]*?)" via JDBC \[([^\]]*)\]/.exec(read(f)); return m ? `${m[1].replace(/\s+/g, ' ').trim()} => ${m[2]}` : null; };
      const hb = halting(LOG.baseHaltA); const hn = halting(LOG.newHaltA);
      state.haltA = { baseUp: b.up, newUp: n.up, hb, hn };
      const ok = (n.up) || (!b.up && hb && hb === hn);
      return { ok, actual: { baselineStarted: b.up, newStarted: n.up, baselineHalt: hb, newHalt: hn },
        evidence: `${LOG.baseHaltA} / ${LOG.newHaltA}: "Halting on error"; pre-existing drift of the dev DB (email tables from another branch) also stops the baseline` };
    });
    // Partial DDL was applied by those attempts: start again from clean copies.
    I.restore(I.DB_NEW); I.restore(I.DB_BASE);
    prepareUsersA();
  }

  // ---------------------------------------------------------------------------------------------------------
  // P3 — dataset A on the baseline jar: the "before" picture
  // ---------------------------------------------------------------------------------------------------------
  const cb = C.client(I.PORT_BASE);
  const cn = C.client(I.PORT_NEW);
  if (haveA) {
    const b = await boot({ kind: 'baseline', jar: I.BASE_JAR, port: I.PORT_BASE, db: I.DB_BASE, log: LOG.baseA, halt: false });
    state.baseAUp = b.up;
    if (b.up) {
      state.snapA = {};
      for (const u of usersA) {
        const t = await tokenFor(cb, u.username, u.password);
        u.baseLogin = !!t;
        if (t) state.snapA[u.username] = await C.snapshot(cb, t);
      }
      state.baseInactiveLogin = state.inactiveA ? (await cb.login(state.inactiveA, PW)).status : null;
      // Write controls on the baseline (after the snapshot): the same payloads are replayed on the upgraded copy.
      const t = await tokenFor(cb, 'admin', 'admin123');
      state.writeA = await buildWritePayloads(cb, t);
      state.writeBase = await doWrites(cb, t, state.writeA);
      state.ddlBaseA = ddlFailures(LOG.baseA);
    }
    await I.stopExtras(['baseline']);
  }

  // ---------------------------------------------------------------------------------------------------------
  // P4 — dataset B seeded through the baseline jar
  // ---------------------------------------------------------------------------------------------------------
  let haveB = false;
  await kase({ id: 'MIG-B-00', feature: 'Baseline-seeded dataset', prd: 'B1',
    title: 'A representative dataset is created through the BASELINE jar on a fresh database (clean pre-upgrade install)',
    steps: `create ${DB_SEED}; boot baseline jar (halt_on_error default) on :${I.PORT_BASE}; seed through its API: 7 staff (every role, 1 inactive), 3 products, 10 customers with logins/POCs, 40 back-dated invoices (2 cancelled), 15 payments, 5 promises, 3 disputes (1 denied); snapshot what each user sees; stop; clone to ${DB_SEEDUP}`,
    expected: 'baseline starts with no DDL failure; every seed call succeeds' },
  async () => {
    I.psql('postgres', `DROP DATABASE IF EXISTS ${DB_SEEDUP} WITH (FORCE)`);
    I.psql('postgres', `DROP DATABASE IF EXISTS ${DB_SEED} WITH (FORCE)`);
    I.psql('postgres', `CREATE DATABASE ${DB_SEED}`);
    const b = await boot({ kind: 'baseline', jar: I.BASE_JAR, port: I.PORT_BASE, db: DB_SEED, log: LOG.baseB });
    if (!b.up) return { status: 'BLOCKED', actual: `baseline did not start on a fresh DB: ${short(b.wait)}` };
    state.worldB = await S.seedBaseline(cb);
    state.usersB = [
      { username: 'admin', password: 'admin123', role: 'ADMIN' }, { username: 'cashier', password: 'cashier123', role: 'CASHIER' },
      ...[['b.sales1', 'SALES_POC'], ['b.coll', 'COLLECTION_POC'], ['b.succ', 'CUSTOMER_SUCCESS_POC'], ['b.cash', 'CASHIER'], ['b.view', 'VIEWER']]
        .map(([u, role]) => ({ username: u, password: PW, role })),
      { username: 'seedcust1', password: PW, customer: true }, { username: 'seedcust4', password: PW, customer: true },
    ];
    state.snapB = {};
    for (const u of state.usersB) {
      const t = await tokenFor(cb, u.username, u.password);
      if (t) state.snapB[u.username] = await C.snapshot(cb, t);
    }
    state.baseInactiveLoginB = (await cb.login('b.gone', PW)).status;
    await I.stopExtras(['baseline']);
    await rt.sleep(1000);
    I.psql('postgres', `CREATE DATABASE ${DB_SEEDUP} TEMPLATE ${DB_SEED}`);
    state.countsB = counts(DB_SEEDUP, ['customers', 'invoices', 'payments', 'payment_promises', 'disputes', 'users', 'payment_allocations', 'customer_pocs', 'products']);
    state.hashesB = passwordHashes(DB_SEEDUP);
    haveB = Object.keys(state.snapB).length === state.usersB.length;
    return { ok: haveB && ddlFailures(LOG.baseB).length === 0, actual: { counts: state.countsB, snapshots: Object.keys(state.snapB), ddl: ddlFailures(LOG.baseB) } };
  });

  // ---------------------------------------------------------------------------------------------------------
  // P5 — dataset B upgraded by the new jar
  // ---------------------------------------------------------------------------------------------------------
  if (haveB) {
    const nb = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: DB_SEEDUP, log: LOG.newB });
    state.newBUp = nb.up;
    await phaseB(nb);
    await I.stopNewBackend();
    // P5b — dataset C: dataset B's pre-upgrade copy plus the dev DB's legacy automation tables WITH rows.
    await phaseC();
    await I.stopNewBackend();
  } else {
    notTested({ id: 'MIG-B-01', feature: 'Startup on existing data', prd: 'B1', title: 'Dataset B upgrade' }, 'dataset B could not be seeded');
  }

  // ---------------------------------------------------------------------------------------------------------
  // P6 — dataset A upgraded by the new jar
  // ---------------------------------------------------------------------------------------------------------
  if (haveA && state.baseAUp) {
    const na = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: I.DB_NEW, log: LOG.newA, halt: false });
    state.newAUp = na.up;
    await phaseA(na);
  } else {
    notTested({ id: 'MIG-A-02', feature: 'Startup on existing data', prd: 'B1', title: 'Dataset A upgrade' }, haveA ? 'baseline jar did not start on the dev DB copy' : 'dev DB dump unavailable');
    // Leave a backend on :18107 for down.sh symmetry (empty DB is fine).
  }
}

// =============================================================================================================
// Writes used as controls (built from the data, identical on both copies)
// =============================================================================================================

async function buildWritePayloads(c, t) {
  const custs = (await c.listAll(t, '/api/customers', { sort: 'id,asc' })).json.content;
  // A customer with a Collection POC and an open invoice with a Sales POC.
  for (const cu of custs) {
    const d = (await c.api('GET', `/api/customers/${cu.id}`, { token: t })).json;
    const coll = (d.collectionPocs || [])[0];
    const invs = (await c.api('GET', '/api/invoices', { token: t, query: { size: 10, filter: [`customerId:eq:${cu.id}`, 'status:in:UNPAID,PARTIALLY_PAID'] } })).json;
    const inv = invs && invs.content && invs.content.find((x) => x.salesPoc && x.salesPoc.active);
    if (!coll || !inv) continue;
    const products = (await c.api('GET', '/api/products', { token: t, query: { size: 10 } })).json.content;
    const prod = products.find((p) => p.active) || products[0];
    return {
      customerId: cu.id,
      customerPut: { name: d.name, phone: '555-0199', email: d.email, address: d.address, paymentTerm: d.paymentTerm },
      invoicePost: { customerId: cu.id, paymentTerm: 'NET_30', salesPocUserId: inv.salesPoc.id, notes: 'e2e migration probe',
        items: [{ productId: prod.id, quantity: 1, unitPrice: 10 }] },
      paymentPost: { customerId: cu.id, amount: 1, method: 'BANK_TRANSFER', notes: 'e2e migration probe', invoiceIds: [inv.id], collectionPocUserId: coll.user.id },
    };
  }
  return null;
}
async function doWrites(c, t, w) {
  if (!w) return null;
  const put = await c.api('PUT', `/api/customers/${w.customerId}`, { token: t, body: w.customerPut });
  const inv = await c.api('POST', '/api/invoices', { token: t, body: w.invoicePost });
  const pay = await c.api('POST', '/api/payments', { token: t, body: w.paymentPost });
  const brief = (r) => [r.status, r.ok ? (r.json && (r.json.id || r.json.status)) : (r.json && r.json.message) || r.text.slice(0, 200)];
  return { customerPut: brief(put), invoicePost: brief(inv), paymentPost: brief(pay) };
}

// =============================================================================================================
// Shared upgrade checks (both datasets)
// =============================================================================================================

async function upgradeChecks(P, { db, log, pre, users, snaps, inactive, baseInactive, hashesBefore, resetUsers, ddlBase, halt }) {
  const cn = C.client(I.PORT_NEW);
  // Startup and schema errors.
  await kase({ id: `${P}-02`, feature: 'Startup on existing data', prd: 'B1',
    title: `New jar starts on the existing data with no schema error of its own${halt ? ' (halt_on_error=true)' : ' (vs baseline, halt_on_error=false)'}`,
    steps: `boot dist/backend.jar (profile dev) on ${db}; grep ${path.basename(log)} for ERROR lines, DDL failures, app WARNs`,
    expected: 'Started; no log line at ERROR level; DDL failures = none' + (halt ? '' : ' beyond the baseline jar\'s on the same data') + '; no WARN from the upgrade beans / history baseline',
    codeRef: 'backend/src/main/java/com/geneinvoice/region/RegionSchemaUpgrade.java' },
  async () => {
    const errs = levelLines(log, 'ERROR');
    const ddl = ddlFailures(log);
    const extra = ddlBase ? ddl.filter((x) => !ddlBase.includes(x)) : ddl;
    const warns = appWarns(log);
    const started = hasLine(log, /Started GeneInvoiceApplication/);
    const ok = started && errs.length === 0 && extra.length === 0 && warns.length === 0;
    return { ok, severity: 'high', actual: { started, errorLines: errs.slice(0, 4), ddlFailures: ddl.length, ddlNotInBaseline: extra, appWarns: warns.slice(0, 6) },
      evidence: `${log}; baseline DDL failures: ${ddlBase ? ddlBase.length : 'n/a'} (${ddlBase ? ddlBase.slice(0, 2).join(' | ') : ''}…)` };
  });

  await kase({ id: `${P}-03`, feature: 'Default region', prd: 'B1',
    title: 'Exactly one region exists after the upgrade: DEFAULT, default and active, guarded by the partial unique index',
    steps: `SQL on ${db}: select * from regions; pg_indexes uk_regions_default; GET /api/regions as admin`,
    expected: 'DESIGN §3.11 step 1: one row code DEFAULT, is_default, active; uk_regions_default exists; API lists it' },
  async () => {
    const rows = I.psql(db, 'select id, code, name, is_default, active from regions order by id');
    const idx = n1(db, `select count(*) from pg_indexes where indexname='uk_regions_default'`);
    const t = await tokenFor(cn, 'admin', 'admin123');
    const api = await cn.api('GET', '/api/regions', { token: t });
    const ok = rows.length === 1 && rows[0][1] === 'DEFAULT' && rows[0][3] === 't' && rows[0][4] === 't' && idx === 1
      && api.status === 200 && api.json.totalElements === 1 && api.json.content[0].defaultRegion === true;
    state[`${P}-regionId`] = rows[0] && Number(rows[0][0]);
    return { ok, actual: { rows, uk_regions_default: idx, api: [api.status, api.json && api.json.content] } };
  });

  await kase({ id: `${P}-04`, feature: 'Region backfill', prd: 'B1',
    title: 'Every existing customer, invoice, payment, promise and dispute got a region (the default), region_id is NOT NULL, children match their customer',
    steps: `SQL on ${db}: null region_id counts, information_schema is_nullable, child.region_id <> customer.region_id, per-region counts`,
    expected: 'B1 "every record belongs to exactly one": 0 nulls; is_nullable NO on all five; 0 mismatches; all rows in DEFAULT; row counts unchanged by the upgrade',
    codeRef: 'backend/src/main/java/com/geneinvoice/region/RegionSchemaUpgrade.java:90' },
  async () => {
    const f = regionFacts(db);
    const now = counts(db, REGIONAL);
    const unchanged = REGIONAL.every((t) => now[t] === pre[t]);
    const ok = REGIONAL.every((t) => f.nulls[t] === 0 && f.nullable[t] === 'NO' && (t === 'customers' || f.mismatch[t] === 0)
      && (now[t] === 0 || f.perRegion[t] === `DEFAULT=${now[t]}`)) && unchanged;
    return { ok, severity: 'high', actual: { ...f, countsBefore: Object.fromEntries(REGIONAL.map((t) => [t, pre[t]])), countsAfter: now } };
  });

  await kase({ id: `${P}-05`, feature: 'Region references', prd: 'B1',
    title: 'Nothing references a missing region: every region_id column in the schema points at an existing region',
    steps: `SQL on ${db}: every public column named region_id -> rows whose region does not exist; FK constraints to regions`,
    expected: '0 orphan rows in every table with a region_id (live, version, tasks, pending changes, thresholds, notifications, audit...); FKs on the five regional tables' },
  async () => {
    const o = orphanRegions(db);
    const bad = Object.entries(o.out).filter(([, v]) => v > 0);
    const fkOk = REGIONAL.every((t) => o.fks.includes(t));
    return { ok: bad.length === 0 && fkOk, severity: 'high', actual: { columns: o.cols.length, orphans: bad, fkTables: o.fks, missingFk: REGIONAL.filter((t) => !o.fks.includes(t)) } };
  });

  await kase({ id: `${P}-06`, feature: 'Existing users keep their rights', prd: 'B1',
    title: 'Every existing staff user (active or not) got exactly one grant: DEFAULT/APPROVE/ALL; customer logins got none; backfill flags cleared; password hashes untouched',
    steps: `SQL on ${db}: user_region_grants per user; users.region_backfill_pending; users.password compared with the pre-upgrade copy`,
    expected: 'DESIGN §4.6 / §3.11 step 5: staff -> one DEFAULT APPROVE/ALL grant ("the role alone decides"); customer logins 0 grants; flags false; hashes unchanged (existing logins keep working)',
    codeRef: 'backend/src/main/java/com/geneinvoice/region/RegionSchemaUpgrade.java:227' },
  async () => {
    const g = grantFacts(db);
    const after = passwordHashes(db);
    const changed = Object.keys(hashesBefore).filter((u) => after[u] !== hashesBefore[u] && !(resetUsers || []).includes(u));
    const ok = g.staffWithOneDefaultApprove === g.staff && g.customerGrants === 0 && g.pendingFlags === 0 && g.grantsTotal === g.staff && changed.length === 0;
    return { ok, severity: 'high', actual: { ...g, passwordHashesChanged: changed } };
  });

  // Per-user rights (me) and views.
  const tokens = {};
  await kase({ id: `${P}-07`, feature: 'Existing users keep their rights', prd: 'B1',
    title: 'Existing logins work after the upgrade; each user keeps every privilege they had; staff hold DEFAULT/APPROVE/ALL, customers no region',
    steps: `for each existing user (${users.map((u) => u.username).join(', ')}): POST /api/auth/login on baseline and upgraded copies; compare user.privileges and regions`,
    expected: 'DESIGN §4.7: login 200 on both; baseline privileges ⊆ new privileges; staff regions = [DEFAULT APPROVE/ALL]; customer logins regions = []' },
  async () => {
    const rows = [];
    let ok = true;
    for (const u of users) {
      const r = await cn.login(u.username, u.password);
      const before = snaps[u.username] && snaps[u.username].misc['/api/auth/me'].body;
      if (r.status !== 200) { ok = false; rows.push({ u: u.username, login: r.status }); continue; }
      tokens[u.username] = r.json.token;
      const user = r.json.user;
      const lost = before ? (before.privileges || []).filter((p) => !(user.privileges || []).includes(p)) : ['<no baseline>'];
      const regs = (user.regions || []).map((x) => `${x.code}/${x.level}/${x.scope}`);
      const regOk = u.customer ? regs.length === 0 : (regs.length === 1 && regs[0] === 'DEFAULT/APPROVE/ALL');
      if (lost.length || !regOk) ok = false;
      rows.push({ u: u.username, role: user.role, login: 200, lostPrivileges: lost, regions: regs,
        gained: (user.privileges || []).filter((p) => !(before && before.privileges || []).includes(p)).length });
    }
    return { ok, severity: 'high', actual: rows };
  });

  await kase({ id: `${P}-08`, feature: 'Existing users keep their rights', prd: 'B1',
    title: 'An existing inactive user is still refused at login (as on the baseline) but keeps the legacy grant for reactivation',
    steps: `inactive user ${inactive}: POST /api/auth/login on baseline (before) and upgraded copy; SQL grants of that user`,
    expected: 'same status as the baseline (401); one DEFAULT/APPROVE/ALL grant exists' },
  async () => {
    if (!inactive) return { status: 'NOT_TESTED', actual: 'no inactive staff user in this dataset' };
    const r = await cn.login(inactive, PW);
    const g = I.psql(db, `select r.code, g.access_level, g.record_scope from user_region_grants g join regions r on r.id=g.region_id join users u on u.id=g.user_id where u.username='${inactive}'`);
    return { ok: r.status === baseInactive && r.status === 401 && g.length === 1 && g[0].join('/') === 'DEFAULT/APPROVE/ALL',
      actual: { baseline: baseInactive, upgraded: r.status, grants: g } };
  });

  // Comparisons, one case per user.
  let n = 0;
  for (const u of users) {
    n++;
    await kase({ id: `${P}-U${n}`, feature: 'Lists, counts and dashboard unchanged', prd: 'B1',
      title: `${u.username} (${u.customer ? 'customer login' : u.role || 'staff'}) sees exactly the same lists, counts, filters, summaries, dashboard and details as before the upgrade`,
      steps: `baseline jar on the pre-upgrade copy vs new jar on the upgraded copy, as ${u.username}: every page of /api/{customers,invoices,payments,promises,disputes,products,users,roles,notifications}; /summary x4; ${C.FILTERS.length} filtered/sorted lists; ${C.DASHBOARD.length} dashboard calls; /me, notifications & inbox counts, my-scope, privileges; first 4 details per kind; POCs and audit timeline of 2 customers`,
      expected: 'Scope: "existing lists/counts/dashboard totals are the same as before the upgrade" — every field the baseline returns is identical (status, totalElements, default order, rows, lockedFilters, figures); new fields are ignored',
      codeRef: '' },
    async () => {
      const sb = snaps[u.username];
      if (!sb) return { status: 'BLOCKED', actual: `no baseline snapshot for ${u.username}` };
      const t = tokens[u.username] || await tokenFor(cn, u.username, u.password);
      if (!t) return { ok: false, severity: 'high', actual: `login failed on the upgraded copy for ${u.username}` };
      const sn = await C.snapshot(cn, t);
      const { diffs, summary, sc2 } = compareSnapshots(sb, sn);
      state[`${P}-snap-${u.username}`] = sn;
      return { ok: diffs.length === 0, severity: 'high', actual: diffs.length ? { diffs: diffs.slice(0, 25), lists: summary, sc2 } : { lists: summary,
        dashboardCalls: Object.keys(sn.dashboard).length, filtered: Object.values(sn.filtered).map((x) => `${x.status}/${x.totalElements}`).join(' '), sc2 } };
    });
  }

  // History baseline.
  await kase({ id: `${P}-30`, feature: 'History baseline', prd: 'B3',
    title: 'The history baseline copied every existing record once: one open version per live row, started today',
    steps: `SQL on ${db}: history_meta; per type count live vs open versions, live rows with no open version, duplicate open versions; promise links vs entity_link_versions; GET /api/history/range`,
    expected: 'DESIGN §5.7.9: started_at and baseline_completed_at set; missing 0, duplicates 0, open = live for 8 types + links; historyStartsOn = today (UTC), latestAsOf = today-1',
    codeRef: 'backend/src/main/java/com/geneinvoice/history/HistoryBaseline.java:69' },
  async () => {
    const f = historyFacts(db);
    const t = await tokenFor(cn, 'admin', 'admin123');
    const range = await cn.api('GET', '/api/history/range', { token: t });
    const typesOk = Object.values(f.types).every((x) => x.open === x.live && (x.missing === null || x.missing === 0) && (x.dup === null || x.dup === 0));
    const metaOk = f.meta.length === 1 && f.meta[0][0] && f.meta[0][1];
    const rangeOk = range.status === 200 && range.json.historyStartsOn === TODAY && range.json.latestAsOf === rt.day(-1);
    return { ok: typesOk && metaOk && rangeOk, actual: { meta: f.meta, types: f.types, range: [range.status, range.json] },
      evidence: `${path.basename(log)}: ${read(log).split('\n').filter((l) => /history\.baseline/.test(l)).map((l) => l.replace(/^.*: /, '')).join(' | ')}` };
  });

  await kase({ id: `${P}-31`, feature: 'History baseline', prd: 'B3',
    title: 'As-of on migrated data: today answers live and identically; before the upgrade day is refused; a future day is refused',
    steps: 'as admin: GET /api/invoices?asOf=<today>; ?asOf=<yesterday>; ?asOf=<tomorrow>; GET /api/dashboard/outstanding-by-age?asOf=<today>',
    expected: 'DESIGN §D9: today -> live (X-View-As-Of: live), same totalElements and figures as without asOf; yesterday -> 400 AS_OF_BEFORE_HISTORY; tomorrow -> 400 AS_OF_FUTURE' },
  async () => {
    const t = await tokenFor(cn, 'admin', 'admin123');
    const live = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10 } });
    const today = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, asOf: TODAY } });
    const y = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, asOf: rt.day(-1) } });
    const fut = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, asOf: rt.day(1) } });
    const dl = await cn.api('GET', '/api/dashboard/outstanding-by-age', { token: t });
    const dt = await cn.api('GET', '/api/dashboard/outstanding-by-age', { token: t, query: { asOf: TODAY } });
    const ok = today.status === 200 && today.headers['x-view-as-of'] === 'live' && today.json.totalElements === live.json.totalElements
      && y.status === 400 && y.json.error === 'AS_OF_BEFORE_HISTORY' && fut.status === 400 && fut.json.error === 'AS_OF_FUTURE'
      && JSON.stringify(dl.json.buckets) === JSON.stringify(dt.json.buckets);
    return { ok, actual: { live: live.json.totalElements, today: [today.status, today.headers['x-view-as-of'], today.json && today.json.totalElements],
      yesterday: [y.status, y.json && y.json.error, y.json && y.json.message], tomorrow: [fut.status, fut.json && fut.json.error], dashboardSame: JSON.stringify(dl.json.buckets) === JSON.stringify(dt.json.buckets) } };
  });

  await kase({ id: `${P}-32`, feature: 'Region scope on migrated data', prd: 'B1',
    title: 'Asking "which regions": regionIds=<DEFAULT> answers the same as unscoped; an unknown region answers empty; a bad id is refused',
    steps: 'as admin: GET /api/invoices, /api/customers/summary, /api/dashboard/billed-by-month with and without regionIds=<default id>; regionIds=987654; regionIds=abc',
    expected: 'DESIGN §6.0: same totals/figures with regionIds=[default], envelope regionIds=[default]; unknown id -> 200 totalElements 0; non-numeric -> 400 REGION_IDS_INVALID' },
  async () => {
    const t = await tokenFor(cn, 'admin', 'admin123');
    const rid = state[`${P}-regionId`];
    const a = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10 } });
    const b = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, regionIds: [rid] } });
    const sa = await cn.api('GET', '/api/customers/summary', { token: t });
    const sb = await cn.api('GET', '/api/customers/summary', { token: t, query: { regionIds: [rid] } });
    const da = await cn.api('GET', '/api/dashboard/billed-by-month', { token: t });
    const dbb = await cn.api('GET', '/api/dashboard/billed-by-month', { token: t, query: { regionIds: [rid] } });
    const u = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, regionIds: [987654] } });
    const bad = await cn.api('GET', '/api/invoices', { token: t, query: { size: 10, regionIds: 'abc' } });
    const strip = (o) => { const x = { ...o }; delete x.scope; return JSON.stringify(x); };
    const ok = a.json.totalElements === b.json.totalElements && JSON.stringify(b.json.regionIds) === JSON.stringify([rid])
      && strip(sa.json) === strip(sb.json) && strip(da.json) === strip(dbb.json)
      && u.status === 200 && u.json.totalElements === 0 && bad.status === 400 && bad.json.error === 'REGION_IDS_INVALID';
    return { ok, actual: { unscoped: a.json.totalElements, scoped: [b.json.totalElements, b.json.regionIds], summarySame: strip(sa.json) === strip(sb.json),
      dashboardSame: strip(da.json) === strip(dbb.json), unknown: [u.status, u.json && u.json.totalElements], bad: [bad.status, bad.json && bad.json.error] } };
  });

  await kase({ id: `${P}-33`, feature: 'Upgrade audit', prd: 'B1',
    title: 'The backfill is audited once (REGIONS_BACKFILLED, origin SCHEMA) with counts that match the data',
    steps: `SQL on ${db}: audit_logs where action='REGIONS_BACKFILLED'`,
    expected: 'DESIGN §3.11 step 7: exactly one row; its reason names the customer, invoice, payment, promise, dispute and user counts' },
  async () => {
    const rows = I.psql(db, `select entity_type, entity_id, origin, reason from audit_logs where action='REGIONS_BACKFILLED'`);
    const g = grantFacts(db);
    const expReason = `Put ${pre.customers} customer(s), ${pre.invoices} invoice(s), ${pre.payments} payment(s), ${pre.payment_promises} promise(s) and ${pre.disputes} dispute(s) in the default region, and gave ${g.staff} existing user(s) access to it`;
    return { ok: rows.length === 1 && rows[0][2] === 'SCHEMA' && rows[0][3] === expReason, actual: { rows, expectedReason: expReason } };
  });

  await kase({ id: `${P}-34`, feature: 'Enum widening', prd: 'A3',
    title: 'Enum columns were widened on the existing tables: disputes.target_type accepts CUSTOMER; grant enums have one CHECK each',
    steps: `SQL on ${db}: pg_get_constraintdef of CHECKs on disputes, user_region_grants, tasks, automation_rules`,
    expected: 'DESIGN §3.9/D12: disputes target_type CHECK lists INVOICE, PAYMENT, CUSTOMER (and only one target_type CHECK remains); access_level READ/MANAGE/APPROVE; record_scope ALL/BOOK' },
  async () => {
    const checks = I.psql(db, `select conrelid::regclass::text, conname, pg_get_constraintdef(oid) from pg_constraint where contype='c'
      and conrelid::regclass::text in ('disputes','user_region_grants','tasks','automation_rules','automation_runs','automation_effects') order by 1,2`);
    const tt = checks.filter((c) => c[0] === 'disputes' && c[2].includes('target_type'));
    const ok = tt.length === 1 && ['INVOICE', 'PAYMENT', 'CUSTOMER'].every((v) => tt[0][2].includes(`'${v}'`))
      && checks.some((c) => c[0] === 'user_region_grants' && /APPROVE/.test(c[2])) && checks.some((c) => c[0] === 'user_region_grants' && /BOOK/.test(c[2]));
    return { ok, actual: checks.map((c) => `${c[0]}.${c[1]}: ${c[2].replace(/::character varying|::text/g, '')}`) };
  });

  await kase({ id: `${P}-35`, feature: 'New features empty on migrated data', prd: 'B2',
    title: 'Nothing is pending, no thresholds gate the default region, no tasks and no rules after the upgrade',
    steps: 'as admin: GET /api/approvals/summary, /api/approvals, /api/regions/{default}/approval-thresholds, /api/tasks, /api/tasks/mine/count, /api/automation/rules',
    expected: 'B2 thresholds are opt-in per region: none on DEFAULT (existing saves are never held by the upgrade); approvals 0; tasks 0 (A6); rules 0 (A1)' },
  async () => {
    const t = await tokenFor(cn, 'admin', 'admin123');
    const rid = state[`${P}-regionId`];
    const sum = await cn.api('GET', '/api/approvals/summary', { token: t });
    const list = await cn.api('GET', '/api/approvals', { token: t, query: { size: 10 } });
    const th = await cn.api('GET', `/api/regions/${rid}/approval-thresholds`, { token: t });
    const tasks = await cn.api('GET', '/api/tasks', { token: t, query: { size: 10 } });
    const mine = await cn.api('GET', '/api/tasks/mine/count', { token: t });
    const rules = await cn.api('GET', '/api/automation/rules', { token: t, query: { size: 10 } });
    const thRows = th.json && (th.json.thresholds || th.json);
    const gated = Array.isArray(thRows) ? thRows.filter((x) => x.amount !== null && x.amount !== undefined) : null;
    const ok = sum.status === 200 && sum.json.pendingCount === 0 && list.status === 200 && list.json.totalElements === 0
      && th.status === 200 && gated && gated.length === 0 && tasks.status === 200 && tasks.json.totalElements === 0
      && mine.status === 200 && rules.status === 200 && rules.json.totalElements === 0;
    return { ok, actual: { summary: [sum.status, sum.json], approvals: [list.status, list.json && list.json.totalElements], thresholds: [th.status, gated],
      tasks: [tasks.status, tasks.json && tasks.json.totalElements], mine: [mine.status, mine.json], rules: [rules.status, rules.json && rules.json.totalElements] } };
  });

  await kase({ id: `${P}-36`, feature: 'Region references', prd: 'B1',
    title: 'The default region cannot be deleted or deactivated, so migrated records can never be left without a region',
    steps: 'as admin: DELETE /api/regions/{default}; PUT /api/regions/{default} {name, active:false, version}',
    expected: 'DESIGN §6.3: 400 "Region is the default"; 400 "The default region cannot be deactivated"; region still there' },
  async () => {
    const t = await tokenFor(cn, 'admin', 'admin123');
    const rid = state[`${P}-regionId`];
    const g = await cn.api('GET', `/api/regions/${rid}`, { token: t });
    const del = await cn.api('DELETE', `/api/regions/${rid}`, { token: t });
    const deact = await cn.api('PUT', `/api/regions/${rid}`, { token: t, body: { name: g.json.name, active: false, version: g.json.version } });
    const after = I.psql(db, `select code, active, is_default from regions where id=${rid}`);
    return { ok: del.status === 400 && deact.status === 400 && after.length === 1 && after[0][1] === 't',
      actual: { delete: [del.status, del.json && del.json.message], deactivate: [deact.status, deact.json && deact.json.message], after } };
  });
}

// =============================================================================================================
// P5 — dataset B
// =============================================================================================================

async function phaseB(nb) {
  if (!nb.up) {
    R.rec({ id: 'MIG-B-02', feature: 'Startup on existing data', prd: 'B1', kind: 'API', status: 'FAIL', severity: 'high',
      title: 'New jar starts on a baseline-produced database', steps: `boot new jar on ${DB_SEEDUP}`, expected: 'started',
      actual: short(nb.wait), evidence: `${LOG.newB}: ${levelLines(LOG.newB, 'ERROR').slice(0, 3).join(' | ')}` });
    return;
  }
  const users = state.usersB;
  await upgradeChecks('MIG-B', { db: DB_SEEDUP, log: LOG.newB, pre: state.countsB, users, snaps: state.snapB, inactive: 'b.gone',
    baseInactive: state.baseInactiveLoginB, hashesBefore: state.hashesB, resetUsers: [], ddlBase: null, halt: true });

  const cn = C.client(I.PORT_NEW);
  const t = await tokenFor(cn, 'admin', 'admin123');
  const W = state.worldB;

  await kase({ id: 'MIG-B-40', feature: 'Saves after the upgrade', prd: 'A5',
    title: 'Existing customers can be edited, invoiced and paid after the upgrade; the records land in the customer\'s region and each save publishes outbox events',
    steps: 'as admin on the upgraded copy: PUT /api/customers/{c1}; POST /api/invoices (existing customer, existing Sales POC, total 2398.5); POST /api/payments 10 on it; SQL automation_events for those records',
    expected: 'all 200 (DEFAULT has no thresholds, so nothing is held: B2); invoice/payment regionCode DEFAULT (B1); automation_events rows CUSTOMER UPDATED, INVOICE CREATED, PAYMENT CREATED (A5 "the trigger publishes an event")' },
  async () => {
    const cu = W.customers[1];
    const d = (await cn.api('GET', `/api/customers/${cu.id}`, { token: t })).json;
    const put = await cn.api('PUT', `/api/customers/${cu.id}`, { token: t, body: { name: d.name, phone: '555-0999', email: d.email, address: d.address, paymentTerm: d.paymentTerm } });
    const inv = await cn.api('POST', '/api/invoices', { token: t, body: { customerId: cu.id, paymentTerm: 'NET_30', salesPocUserId: W.users['b.sales1'].id, notes: 'post-upgrade',
      items: [{ productId: W.products[0].id, quantity: 1, unitPrice: 1200 }, { productId: W.products[1].id, quantity: 1, unitPrice: 1198.5 }] } });
    const pay = inv.status === 200 ? await cn.api('POST', '/api/payments', { token: t, body: { customerId: cu.id, amount: 10, method: 'UPI', invoiceIds: [inv.json.id], collectionPocUserId: W.users['b.coll'].id } }) : null;
    await rt.sleep(500);
    const ev = I.psql(DB_SEEDUP, `select entity_type, entity_id, kind from automation_events where (entity_type='CUSTOMER' and entity_id=${cu.id})
      ${inv.json && inv.json.id ? `or (entity_type='INVOICE' and entity_id=${inv.json.id})` : ''} ${pay && pay.json && pay.json.id ? `or (entity_type='PAYMENT' and entity_id=${pay.json.id})` : ''} order by id`).map((r) => r.join(':'));
    const ok = put.status === 200 && inv.status === 200 && inv.json.regionCode === 'DEFAULT' && pay && pay.status === 200 && pay.json.regionCode === 'DEFAULT'
      && ev.some((e) => e.startsWith('CUSTOMER:') && e.endsWith(':UPDATED')) && ev.some((e) => e.startsWith('INVOICE:') && e.endsWith(':CREATED')) && ev.some((e) => e.startsWith('PAYMENT:') && e.endsWith(':CREATED'));
    state.B40 = { invoiceId: inv.json && inv.json.id };
    return { ok, severity: 'high', actual: { put: put.status, invoice: [inv.status, inv.json && inv.json.regionCode, inv.json && inv.json.invoiceNumber], payment: pay && [pay.status, pay.json && pay.json.regionCode], events: ev } };
  });

  await kase({ id: 'MIG-B-41', feature: 'Rules over existing records', prd: 'A5',
    title: 'Run now on a rule over migrated invoices evaluates them (history baseline in place) and creates tasks in their region',
    steps: 'as admin: POST /api/automation/rules MANUAL INVOICE, condition customerId:eq:<seed customer 3> AND status:in:UNPAID,PARTIALLY_PAID, CREATE_TASK "Chase {{Invoice.Number}} for {{Customer.Name}}"; POST /rules/{id}/run {}; wait for the run; GET /api/tasks?filter=entityId...',
    expected: 'DESIGN §5.10.9/D10: 202 QUEUED -> run SUCCEEDED, matched = number of that customer\'s open migrated invoices (all pre-upgrade), done = matched; tasks carry the rendered title and region DEFAULT' },
  async () => {
    const cu = W.customers[2];
    const open = (await cn.api('GET', '/api/invoices', { token: t, query: { size: 50, filter: [`customerId:eq:${cu.id}`, 'status:in:UNPAID,PARTIALLY_PAID'] } })).json;
    const rule = await cn.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger: { kind: 'MANUAL', entityType: 'INVOICE' },
      condition: { op: 'AND', children: [{ filter: `customerId:eq:${cu.id}` }, { filter: 'status:in:UNPAID,PARTIALLY_PAID' }] },
      actions: [rt.action.task({ title: 'Chase {{Invoice.Number}} for {{Customer.Name}}' })] }) });
    if (rule.status !== 200) return { ok: false, actual: { rule: [rule.status, rule.text.slice(0, 300)] } };
    const req = await cn.api('POST', `/api/automation/rules/${rule.json.id}/run`, { token: t, body: {} });
    const run = req.status === 202 || req.status === 200 ? await rt.waitForRun(t, req.json.runId, { timeoutMs: 60000 }) : null;
    const tasks = I.psql(DB_SEEDUP, `select t.title, r.code from tasks t join regions r on r.id=t.region_id where t.entity_type='INVOICE' and t.customer_id=${cu.id} order by t.id`);
    const ok = run && run.status === 'SUCCEEDED' && run.matched === open.totalElements && run.done === open.totalElements && open.totalElements > 0
      && tasks.length === open.totalElements && tasks.every(([title, code]) => code === 'DEFAULT' && title.includes(cu.name) && /Chase \S+-/.test(title));
    return { ok, severity: 'high', actual: { openMigratedInvoices: open.totalElements, request: [req.status, req.json], run: run && { status: run.status, matched: run.matched, done: run.done, skipped: run.skipped, failed: run.failed, message: run.message }, tasks } };
  });

  await kase({ id: 'MIG-B-42', feature: 'Enum widening', prd: 'A3',
    title: 'A CUSTOMER-target dispute (the widened enum constant) can be stored on the migrated disputes table',
    steps: 'as admin: MANUAL CUSTOMER rule, condition id:eq:<seed customer 5>, CREATE_DISPUTE reason "Review {{Customer.Name}}"; run now; SQL disputes where target_type=CUSTOMER',
    expected: 'DESIGN §3.9/§5.10.11: run SUCCEEDED done 1; one dispute row target_type CUSTOMER, internal true, region DEFAULT (the pre-upgrade CHECK allowed only INVOICE/PAYMENT)',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeSchemaUpgrade.java:33' },
  async () => {
    const cu = W.customers[4];
    const rule = await cn.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger: { kind: 'MANUAL', entityType: 'CUSTOMER' },
      condition: { op: 'AND', children: [{ filter: `id:eq:${cu.id}` }] }, actions: [rt.action.dispute({ reason: 'Review {{Customer.Name}}' })] }) });
    if (rule.status !== 200) return { ok: false, actual: { rule: [rule.status, rule.text.slice(0, 300)] } };
    const req = await cn.api('POST', `/api/automation/rules/${rule.json.id}/run`, { token: t, body: {} });
    const run = req.json && req.json.runId ? await rt.waitForRun(t, req.json.runId, { timeoutMs: 60000 }) : null;
    const rows = I.psql(DB_SEEDUP, `select d.target_type, d.internal, r.code, d.reason from disputes d join regions r on r.id=d.region_id where d.customer_id=${cu.id} and d.target_type='CUSTOMER'`);
    return { ok: run && run.status === 'SUCCEEDED' && run.done === 1 && rows.length === 1 && rows[0][1] === 't' && rows[0][2] === 'DEFAULT' && rows[0][3] === `Review ${cu.name}`,
      actual: { run: run && { status: run.status, done: run.done, failed: run.failed, message: run.message }, rows } };
  });

  // UI: after adding a second region, migrated records show their region.
  await kase({ id: 'MIG-B-UI-01', kind: 'UI', feature: 'Region shown on migrated records', prd: 'B1',
    title: 'Once a second region exists, the admin sees the region selector and a Region column reading "Default region" on migrated invoices',
    steps: 'API: POST /api/regions {code MIGNORTH} (admin auto-granted APPROVE/ALL); UI: open app as admin, go /invoices, read the header row and cells; screenshot',
    expected: 'DESIGN §7.5 (Region column shown only when user.multiRegion; region selector when > 1 region): selector "Regions: …" visible; column "Region"; every migrated row shows the default region (code DEFAULT / name "Default region")' },
  async () => {
    const reg = await cn.api('POST', '/api/regions', { token: t, body: { code: 'MIGNORTH', name: 'Mig North', active: true } });
    const app = await rt.openApp({ username: 'admin', password: 'admin123', path: '/invoices' });
    try {
      await rt.go(app.page, '/invoices', 4000);
      const text = await rt.screenText(app.page);
      const nodes = await rt.semantics(app.page);
      const sel = await rt.regionSelector(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-B-UI-01-1');
      // Every line of every node (labels of merged row nodes included), not de-duplicated.
      const nodeLines = nodes.map((x) => new Set(`${x.label || ''}\n${x.text || ''}`.split('\n').map((l) => l.trim()).filter(Boolean)));
      const lines = nodeLines.flatMap((set) => [...set]);
      const regionCells = nodeLines.filter((set) => set.has('DEFAULT') || set.has('Default region')).length;
      const otherRegionCells = nodeLines.filter((set) => set.has('MIGNORTH') || set.has('Mig North')).length;
      const rows = new Set(lines.filter((l) => /^INV-\d{8}-\d{4}$/.test(l))).size;
      const ok = reg.status === 200 && !!sel && text.includes('Region') && rows > 0 && regionCells >= rows && otherRegionCells === 0;
      return { ok, screenshot: shot, actual: { region: [reg.status, reg.json && reg.json.code], selector: sel && (sel.label || sel.text), hasRegionHeader: text.includes('Region'),
        invoiceRowsOnScreen: rows, defaultRegionCells: regionCells, otherRegionCells, sample: text.slice(0, 40).join(' | ') } };
    } finally { await app.close(); }
  });
}

// =============================================================================================================
// P5b — dataset C: legacy automation tables that HOLD rows (round 2, after MIG-R1-1 / MIG-R1-2)
// =============================================================================================================

function applySqlFile(db, file) {
  if (!/^e2e_migration_(seed|seedup)$/.test(db)) throw new Error(`refusing database ${db}`);
  require('child_process').execFileSync('bash', ['-c', `docker exec -i gi-e2e-db psql -q -v ON_ERROR_STOP=1 -U e2e -d ${db} < '${file}' > /dev/null`]);
}
function haltingDdl(file) {
  const m = /Halting on error : Error executing DDL "([\s\S]*?)" via JDBC \[([^\]]*)\]/.exec(read(file));
  return m ? `${m[1].replace(/\s+/g, ' ').trim()} => ${m[2]}` : null;
}
function legacyState(db) {
  const has = (t) => tableExists(db, t);
  return {
    rulesLive: n1(db, 'select count(*) from automation_rules'),
    eventsLive: n1(db, 'select count(*) from automation_events'),
    rulesLegacy: has('automation_rules_legacy') ? I.psql(db, 'select name, trigger_kind, run_count from automation_rules_legacy order by name').map((r) => r.join('/')) : null,
    eventsLegacy: has('automation_events_legacy') ? I.psql(db, 'select idempotency_key, status, trigger_kind from automation_events_legacy order by idempotency_key').map((r) => r.join('/')) : null,
  };
}

/** Every value of the fixture's legacy rows (suffix '' = live tables, '_legacy' = the archives), for "nothing lost". */
function legacyRows(db, suffix) {
  const ev = `automation_events${suffix}`; const ru = `automation_rules${suffix}`;
  return {
    events: tableExists(db, ev) ? I.psql(db, `select id, idempotency_key, attempts, entity_id, entity_type, coalesce(last_error,'-'), coalesce(rule_id::text,'-'), status, trigger_kind, created_at::text, coalesce(next_attempt_at::text,'-') from ${ev} where idempotency_key like 'legacy-%' order by idempotency_key`).map((r) => r.join('|')) : null,
    rules: tableExists(db, ru) ? I.psql(db, `select id, name, action, coalesce(action_json,'-'), entity_type, coalesce(filters_json,'-'), run_count, trigger_kind, enabled, coalesce(created_by_user_id::text,'-'), coalesce(description,'-'), created_at::text from ${ru} where action is not null order by name`).map((r) => r.join('|')) : null,
  };
}

async function phaseC() {
  // Dataset B's pre-upgrade database (e2e_migration_seed, never booted by the new jar) + the legacy tables.
  // e2e_migration_seedup (dataset B, done) is replaced by an identical copy for the halt_on_error=false fallback.
  const DBC = DB_SEED; const DBC2 = DB_SEEDUP; const DBC3 = DB_LEGACY2;
  let prepared = false;
  await kase({ id: 'MIG-C-00', feature: 'Legacy automation rows', prd: 'A5',
    title: 'Fixture: a pre-upgrade database whose automation_rules / automation_events tables (the dev DB\'s legacy shape) hold rows',
    steps: `apply data/legacy-automation.sql (the dev DB's CREATE TABLEs for automation_events / automation_rules, verbatim, + 2 legacy rules, 3 legacy events QUEUED/DONE/FAILED) to ${DBC}; clone it to ${DBC2}`,
    expected: 'fixture applied; 2 rules and 3 events' },
  async () => {
    I.psql('postgres', `DROP DATABASE IF EXISTS ${DBC2} WITH (FORCE)`);
    I.psql('postgres', `DROP DATABASE IF EXISTS ${DBC3} WITH (FORCE)`);
    applySqlFile(DBC, LEGACY_SQL);
    I.psql('postgres', `CREATE DATABASE ${DBC2} TEMPLATE ${DBC}`);
    I.psql('postgres', `CREATE DATABASE ${DBC3} TEMPLATE ${DBC}`);
    const st = legacyState(DBC);
    state.C = { pre: st, preCounts: counts(DBC, ['customers', 'invoices', 'payments']), preRows: legacyRows(DBC, '') };
    prepared = st.rulesLive === 2 && st.eventsLive === 3;
    return { ok: prepared, actual: st };
  });
  if (!prepared) return;

  // C-01 — the default (halt_on_error=true) boot.
  let db = DBC; let log = LOG.newC; let up = false;
  await kase({ id: 'MIG-C-01', feature: 'Legacy automation rows', prd: 'A5',
    title: 'The new jar starts (halt_on_error=true, the default) on a database whose legacy automation tables hold rows',
    steps: `boot dist/backend.jar (profile dev, SCHEMA_HALT_ON_ERROR unset) on ${DBC}; admin login + GET /api/automation/health; grep the log for "Halting on error" / DDL failures / ERROR`,
    expected: 'TRIAGE r2 MIG-R2-1 (B1): the default-halt boot starts (login 200, GET /api/automation/health 200) with no halting DDL and no DDL failures. Scope: "startup succeeds with no schema errors" on existing data; unreadable legacy rows are archived to <table>_legacy before Hibernate\'s DDL, "never dropped"',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/AutomationSchemaUpgrade.java; backend/src/main/java/com/geneinvoice/automation/event/AutomationEvent.java (@ColumnDefault on depth/claims)' },
  async () => {
    const b = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: DBC, log: LOG.newC });
    const halt = haltingDdl(LOG.newC);
    const ddl = ddlFailures(LOG.newC);
    up = b.up;
    let health = null;
    if (b.up) {
      const hc = C.client(I.PORT_NEW);
      const ht = await tokenFor(hc, 'admin', 'admin123');
      const h = ht ? await hc.api('GET', '/api/automation/health', { token: ht }) : null;
      health = h ? h.status : 'no admin token';
    }
    if (!b.up) await waitExit(b.pid, 10000);
    state.C.halt = halt;
    return { ok: b.up && health === 200 && !halt && ddl.length === 0, severity: 'medium',
      actual: { started: b.up, health, haltingDdl: halt, ddlFailures: ddl, errorLines: levelLines(LOG.newC, 'ERROR').slice(0, 3).map((l) => l.slice(0, 400)) },
      evidence: `${LOG.newC}: grep "Halting on error"` };
  });
  if (!up) {
    // Fallback: the clean identical copy with halt_on_error=false, so the rest of the legacy path can be observed.
    await I.stopNewBackend();
    const b = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: DBC2, log: LOG.newCsoft, halt: false });
    db = DBC2; log = LOG.newCsoft; up = b.up;
    state.C.softDdl = ddlFailures(LOG.newCsoft);
    if (!up) {
      notTested({ id: 'MIG-C-02', feature: 'Legacy automation rows', prd: 'A5', title: 'Legacy rows preserved' }, `new jar did not start on ${DBC2} even with halt_on_error=false: ${short(b.wait)}`);
      return;
    }
  }
  state.C.db = db; state.C.log = log;
  const cn = C.client(I.PORT_NEW);
  const t = await tokenFor(cn, 'admin', 'admin123');
  const note = db === DBC2 ? ` (on ${DBC2}, booted with SCHEMA_HALT_ON_ERROR=false because the default boot halted: MIG-C-01)` : '';

  await kase({ id: 'MIG-C-02', feature: 'Legacy automation rows', prd: 'A5',
    title: 'The earlier build\'s rules and events are kept: rows the new mapping cannot read are moved to <table>_legacy, none lost, none left in the live tables',
    steps: `after the boot${note}: SQL automation_rules / automation_events / *_legacy; grep the log for "Moved N row(s)"`,
    expected: 'TRIAGE r2 MIG-R2-1: eventsLive 0; automation_events_legacy holds legacy-k1/QUEUED, legacy-k2/DONE and legacy-k3/FAILED (all 3, the DONE row included; order-insensitive); rulesLive 0; automation_rules_legacy holds the 2 rules; the live automation_events / automation_rules tables have every mapped column (incl. events kind, claims, depth, occurred_at; rules actions_json, owner_user_id)',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/AutomationSchemaUpgrade.java' },
  async () => {
    const st = legacyState(db);
    const moved = read(log).split('\n').filter((l) => /Moved \d+ row\(s\) of automation_/.test(l)).map((l) => l.replace(/^.*AutomationSchemaUpgrade\s*:\s*/, ''));
    // Every column the new build maps (AutomationEvent / AutomationRule; identical to a fresh new-jar schema).
    const MAPPED = {
      automation_events: ['actor_user_id', 'attempts', 'caused_by_rule_id', 'changes', 'claimed_by', 'claims', 'claims_base', 'cursor_id', 'depth', 'entity_id',
        'entity_type', 'history_tx_id', 'id', 'kind', 'last_error', 'lease_until', 'next_attempt_at', 'occurred_at', 'origin', 'processed_at', 'record_ids',
        'region_ids', 'rule_id', 'rule_version', 'scheduled_for', 'status', 'updated_at'],
      automation_rules: ['actions_json', 'active_since', 'catch_up', 'condition_json', 'created_at', 'created_by_user_id', 'deleted_at', 'description', 'enabled',
        'entity_type', 'id', 'last_run_at', 'last_run_id', 'last_run_status', 'last_scheduled_for', 'max_records_per_run', 'name', 'next_run_at', 'owner_user_id',
        'schedule_day', 'schedule_time', 'schedule_zone', 'suspended_at', 'suspended_detail', 'suspended_reason', 'trigger_kind', 'updated_at', 'updated_by_user_id', 'version'],
    };
    const missing = {};
    for (const [tbl, want] of Object.entries(MAPPED)) {
      const have = new Set(I.psql(db, `select column_name from information_schema.columns where table_schema='public' and table_name='${tbl}'`).map((r) => r[0]));
      missing[tbl] = want.filter((col) => !have.has(col));
    }
    const eventsKS = (st.eventsLegacy || []).map((s) => s.split('/').slice(0, 2).join('/')).sort();
    const wantEvents = ['legacy-k1/QUEUED', 'legacy-k2/DONE', 'legacy-k3/FAILED'];
    const ok = st.eventsLive === 0 && JSON.stringify(eventsKS) === JSON.stringify(wantEvents)
      && st.rulesLive === 0 && !!st.rulesLegacy && st.rulesLegacy.length === 2
      && missing.automation_events.length === 0 && missing.automation_rules.length === 0;
    return { ok, severity: 'medium', actual: { ...st, eventsLegacyKeyStatus: eventsKS, missingLiveColumns: missing, moveWarnings: moved,
      ddlFailuresOfThisBoot: ddlFailures(log).map((x) => x.slice(0, 160)) }, evidence: `${log}` };
  });

  await kase({ id: 'MIG-C-08', feature: 'Legacy automation rows', prd: 'A5',
    title: 'Nothing is lost in the archive: every column value of the 3 legacy events and 2 legacy rules is kept in <table>_legacy (ids included, so event.rule_id still points at its rule)',
    steps: `before the boot: SQL every legacy column of automation_events / automation_rules on ${DBC}; after the boot${note}: the same columns from automation_events_legacy / automation_rules_legacy`,
    expected: 'FIXLOG E2E r1 B2 step 3 / r2 B1: rows are "copied, then deleted, in one transaction. Nothing is ever dropped" -> the archived rows equal the pre-upgrade rows value for value',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/LegacyAutomationRows.java:94-110' },
  async () => {
    const pre = state.C.preRows; const post = legacyRows(db, '_legacy');
    const ok = JSON.stringify(pre.events) === JSON.stringify(post.events) && JSON.stringify(pre.rules) === JSON.stringify(post.rules)
      && pre.events.length === 3 && pre.rules.length === 2;
    return { ok, severity: 'medium', actual: { pre, archived: post } };
  });

  await kase({ id: 'MIG-C-03', feature: 'Legacy automation rows', prd: 'A5',
    title: 'With legacy rows present, existing customers can still be edited and invoiced; each save publishes an outbox event the consumer processes',
    steps: `as admin${note}: PUT /api/customers/{seed customer 2} {phone 555-0777}; POST /api/invoices (existing Sales POC); poll automation_events for those records`,
    expected: 'PRD A5 "a rule can never slow down or break the user\'s save": PUT customer 200, POST invoice 201 (a create; this build answers 200, also accepted, never 202/4xx/5xx); one CUSTOMER UPDATED and one INVOICE CREATED event, which leave PENDING (consumer up)' },
  async () => {
    const W = state.worldB; const cu = W.customers[1];
    const d = (await cn.api('GET', `/api/customers/${cu.id}`, { token: t })).json;
    const put = await cn.api('PUT', `/api/customers/${cu.id}`, { token: t, body: { name: d.name, phone: '555-0777', email: d.email, address: d.address, paymentTerm: d.paymentTerm } });
    const inv = await cn.api('POST', '/api/invoices', { token: t, body: { customerId: cu.id, paymentTerm: 'NET_30', salesPocUserId: W.users['b.sales1'].id,
      items: [{ productId: W.products[0].id, quantity: 1, unitPrice: 100 }] } });
    const hasKind = n1(db, `select count(*) from information_schema.columns where table_schema='public' and table_name='automation_events' and column_name='kind'`) > 0;
    if (!hasKind) {
      const errs = [...new Set(read(log).split('\n').filter((l) => /ERROR .*SqlExceptionHelper/.test(l)).map((l) => l.replace(/^.*SqlExceptionHelper\s+:\s*/, '')))].slice(-4);
      return { ok: false, severity: 'high', actual: { put: [put.status, put.json && put.json.message], invoice: [inv.status, inv.json && (inv.json.invoiceNumber || inv.json.message)],
        outboxKindColumn: 'missing (ddl-auto could not add it)' }, evidence: `${log}: ${errs.join(' | ')}` };
    }
    const q = () => I.psql(db, `select entity_type, kind, status from automation_events where (entity_type='CUSTOMER' and entity_id=${cu.id}) ${inv.json && inv.json.id ? `or (entity_type='INVOICE' and entity_id=${inv.json.id})` : ''} order by id`).map((r) => r.join(':'));
    const done = await rt.waitFor(() => { const e = q(); return e.length >= 2 && e.every((x) => !x.endsWith(':PENDING')) ? e : null; }, { timeoutMs: 30000 });
    const ev = done || q();
    const ok = put.status === 200 && CREATED_OK.includes(inv.status) && ev.some((e) => e.startsWith('CUSTOMER:UPDATED')) && ev.some((e) => e.startsWith('INVOICE:CREATED')) && !!done;
    return { ok, severity: 'high', actual: { put: [put.status, put.json && put.json.message], invoice: [inv.status, inv.json && (inv.json.invoiceNumber || inv.json.message)], events: ev } };
  });

  await kase({ id: 'MIG-C-04', feature: 'Legacy automation rows', prd: 'A1',
    title: 'With legacy rows present, rules of every trigger kind can be created (incl. the 25-character ENTITY_CREATED_OR_UPDATED on the legacy varchar(20) column)',
    steps: `as admin${note}: POST /api/automation/rules x4: MANUAL CUSTOMER, ENTITY_CREATED_OR_UPDATED INVOICE, SCHEDULE_DAILY INVOICE 09:00, SCHEDULE_WEEKLY PAYMENT MONDAY 09:00 (CREATE_TASK each)`,
    expected: 'DESIGN §6.14: 4 x 201 RuleDto (a create; this build answers 200, also accepted, never 4xx/5xx)' },
  async () => {
    const mk = (trigger) => cn.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger, actions: [rt.action.task({ title: 'Legacy DB rule' })] }) });
    const r = {};
    for (const [k, tr] of Object.entries({
      manual: { kind: 'MANUAL', entityType: 'CUSTOMER' }, createdOrUpdated: { kind: 'ENTITY_CREATED_OR_UPDATED', entityType: 'INVOICE' },
      daily: { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '09:00' }, weekly: { kind: 'SCHEDULE_WEEKLY', entityType: 'PAYMENT', time: '09:00', day: 'MONDAY' } })) {
      const x = await mk(tr); r[k] = [x.status, CREATED_OK.includes(x.status) ? x.json.trigger && x.json.trigger.kind : (x.json && (x.json.message || x.json.fieldErrors)) || x.text.slice(0, 200)];
    }
    const errs = [...new Set(read(log).split('\n').filter((l) => /ERROR .*SqlExceptionHelper.*automation_rules|ERROR .*SqlExceptionHelper/.test(l)).map((l) => l.replace(/^.*SqlExceptionHelper\s+:\s*/, '')))].slice(-4);
    return { ok: Object.values(r).every((x) => CREATED_OK.includes(x[0])), severity: 'high', actual: r, evidence: `${log}: ${errs.join(' | ')}` };
  });

  await kase({ id: 'MIG-C-05', feature: 'Legacy automation rows', prd: 'B1',
    title: 'A second boot on the upgraded legacy database moves nothing more and starts with the default halt_on_error=true',
    steps: `stop; boot the new jar again on ${db} with SCHEMA_HALT_ON_ERROR unset; compare *_legacy counts; grep "Moved"`,
    expected: 'started with the default halt_on_error=true (login 200, GET /api/automation/health 200); legacy tables unchanged (2 rules, 3 events); no "Moved" line; rules created in C-04 still there' },
  async () => {
    const before = { ...legacyState(db), rules: n1(db, 'select count(*) from automation_rules') };
    await I.stopNewBackend();
    const b = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db, log: LOG.newC2 });
    const after = { ...legacyState(db), rules: n1(db, 'select count(*) from automation_rules') };
    const moved = read(LOG.newC2).split('\n').filter((l) => /Moved \d+ row/.test(l));
    let health = null;
    if (b.up) {
      const hc = C.client(I.PORT_NEW);
      const ht = await tokenFor(hc, 'admin', 'admin123');
      const h = ht ? await hc.api('GET', '/api/automation/health', { token: ht }) : null;
      health = h ? h.status : 'no admin token';
    }
    return { ok: b.up && health === 200 && JSON.stringify(before.rulesLegacy) === JSON.stringify(after.rulesLegacy) && JSON.stringify(before.eventsLegacy) === JSON.stringify(after.eventsLegacy)
      && before.rules === after.rules && moved.length === 0, actual: { restarted: b.up, health, halting: haltingDdl(LOG.newC2), before, after, moved } };
  });

  // ---- round 3: nearby gaps of MIG-R2-1 -------------------------------------------------------------------
  /** Boots the new jar on `dbx`, then: admin login, health, PUT a seed customer, POST an invoice, POST 2 rules. */
  async function bootAndUse(dbx, logx, halt) {
    await I.stopNewBackend();
    const b = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: dbx, log: logx, halt });
    const out = { started: b.up, halting: haltingDdl(logx), ddlFailures: ddlFailures(logx).map((x) => x.slice(0, 200)),
      moved: read(logx).split('\n').filter((l) => /Moved \d+ row\(s\)|Could not move/.test(l)).map((l) => l.replace(/^.*(LegacyAutomationRows|AutomationSchemaUpgrade)\s*:\s*/, '')) };
    if (!b.up) { await waitExit(b.pid, 10000); return out; }
    const c = C.client(I.PORT_NEW); const tk = await tokenFor(c, 'admin', 'admin123');
    out.health = (await c.api('GET', '/api/automation/health', { token: tk })).status;
    const W = state.worldB; const cu = W.customers[2];
    const d = (await c.api('GET', `/api/customers/${cu.id}`, { token: tk })).json;
    const put = await c.api('PUT', `/api/customers/${cu.id}`, { token: tk, body: { name: d.name, phone: '555-0778', email: d.email, address: d.address, paymentTerm: d.paymentTerm } });
    const inv = await c.api('POST', '/api/invoices', { token: tk, body: { customerId: cu.id, paymentTerm: 'NET_30', salesPocUserId: W.users['b.sales1'].id,
      items: [{ productId: W.products[0].id, quantity: 1, unitPrice: 100 }] } });
    const r1 = await c.api('POST', '/api/automation/rules', { token: tk, body: rt.rule({ trigger: { kind: 'ENTITY_CREATED_OR_UPDATED', entityType: 'CUSTOMER' }, actions: [rt.action.task({ title: 'r3 {{Customer.Name}}' })] }) });
    const r2 = await c.api('POST', '/api/automation/rules', { token: tk, body: rt.rule({ trigger: { kind: 'SCHEDULE_WEEKLY', entityType: 'INVOICE', time: '09:00', day: 'MONDAY' }, actions: [rt.action.task({ title: 'r3 weekly' })] }) });
    out.put = [put.status, put.json && put.json.message]; out.invoice = [inv.status, inv.json && (inv.json.invoiceNumber || inv.json.message)];
    out.rules = [[r1.status, r1.json && (r1.json.message || r1.json.id)], [r2.status, r2.json && (r2.json.message || r2.json.id)]];
    // the CUSTOMER UPDATED event of that save leaves PENDING (consumer up) and the new ENTITY_CREATED_OR_UPDATED rule acted on it or not, but never failed the save
    const q = () => I.psql(dbx, `select kind, status from automation_events where entity_type='CUSTOMER' and entity_id=${cu.id} order by id`).map((r) => r.join(':'));
    out.events = (await rt.waitFor(() => { const e = q(); return e.length && e.every((x) => !x.endsWith(':PENDING')) ? e : null; }, { timeoutMs: 30000 })) || q();
    out.state = legacyState(dbx);
    out.ok = b.up && out.health === 200 && !out.halting && out.ddlFailures.length === 0 && put.status === 200 && CREATED_OK.includes(inv.status)
      && CREATED_OK.includes(r1.status) && CREATED_OK.includes(r2.status) && out.events.length > 0 && out.events.every((x) => !x.endsWith(':PENDING'));
    return out;
  }

  // C-06: the database the round-2 build left behind (it archived 2 events + 2 rules and kept the DONE row in a live
  // table that lacks kind/claims/..., with a few nullable columns of the new mapping already added). Round 2 said
  // "every later default boot still halts, so the database needs manual repair": the rebuilt jar must repair it.
  const halfOk = db !== DBC2; // DBC2 is still the untouched fixture clone only when the default boot (C-01) worked
  if (!halfOk) {
    notTested({ id: 'MIG-C-06', feature: 'Legacy automation rows', prd: 'A5', title: 'Half-migrated legacy database' }, `${DBC2} was used for the halt_on_error=false fallback of C-01`);
  } else {
    await kase({ id: 'MIG-C-06', feature: 'Legacy automation rows', prd: 'A5',
      title: 'A half-migrated legacy database (the state the round-2 build left: 2 events + 2 rules already in *_legacy, the DONE event still live without kind/claims) is repaired by a default boot',
      steps: `on ${DBC2} (fixture clone): create automation_events_legacy / automation_rules_legacy as the round-2 archive did (CTAS of legacy-k1, legacy-k3 and both rules, deleted from the live tables); add nullable origin varchar(12), processed_at, rule_version to automation_events and condition_json, deleted_at to automation_rules (what that halt=false boot managed to add); boot dist/backend.jar with SCHEMA_HALT_ON_ERROR unset; admin: health, PUT customer, POST invoice, POST 2 rules; SQL live/_legacy`,
      expected: 'MIG-R2-1 expected + FIXLOG E2E r2 B1 ("Half-migrated case: _legacy already holds 2 rows and the live table holds the DONE row without kind. After the run, the live table has 0 rows and _legacy has 3"): the default boot starts with no halting DDL; eventsLive 0 (before the saves) and _legacy holds k1/QUEUED, k2/DONE, k3/FAILED once each; rules _legacy still exactly 2 (no duplicates); saves 200, invoice/rules created',
      codeRef: 'backend/src/main/java/com/geneinvoice/automation/LegacyAutomationRows.java:66-110; AutomationPreSchemaConfig.java:20-28' },
    async () => {
      I.psql(DBC2, `create table automation_events_legacy as select * from automation_events where idempotency_key in ('legacy-k1','legacy-k3');
        delete from automation_events where idempotency_key in ('legacy-k1','legacy-k3');
        create table automation_rules_legacy as select * from automation_rules; delete from automation_rules;
        alter table automation_events add column origin varchar(12), add column processed_at timestamp(6) with time zone, add column rule_version bigint;
        alter table automation_rules add column condition_json text, add column deleted_at timestamp(6) with time zone;`);
      const pre = legacyState(DBC2);
      const r = await bootAndUse(DBC2, LOG.newChalf, true);
      const archived = legacyRows(DBC2, '_legacy');
      const keys = (r.state && r.state.eventsLegacy || []).map((x) => x.split('/').slice(0, 2).join('/')).sort();
      const liveLegacyEvents = n1(DBC2, `select count(*) from automation_events where idempotency_key like 'legacy-%'`);
      const ok = r.ok && JSON.stringify(keys) === JSON.stringify(['legacy-k1/QUEUED', 'legacy-k2/DONE', 'legacy-k3/FAILED'])
        && r.state.rulesLegacy && r.state.rulesLegacy.length === 2 && liveLegacyEvents === 0
        && JSON.stringify(archived) === JSON.stringify(state.C.preRows);
      return { ok, severity: 'medium', actual: { preBoot: pre, ...r, liveLegacyEvents, archivedEqualsFixture: JSON.stringify(archived) === JSON.stringify(state.C.preRows) },
        evidence: `${LOG.newChalf}: grep "Moved" / "Halting on error"` };
    });
  }

  // C-07: the same fixture booted with SCHEMA_HALT_ON_ERROR=false (the round-2 fallback path) now has no DDL failure either.
  await kase({ id: 'MIG-C-07', feature: 'Legacy automation rows', prd: 'A5',
    title: 'With SCHEMA_HALT_ON_ERROR=false the legacy-row fixture upgrades exactly as with the default: no DDL failure, all rows archived, saves and rule creation work',
    steps: `on ${DBC3} (an untouched clone of the fixture made in C-00): boot dist/backend.jar with SCHEMA_HALT_ON_ERROR=false; grep DDL failures; admin: health, PUT customer, POST invoice, POST 2 rules; SQL live/_legacy`,
    expected: 'MIG-R2-1 expected (b): ddl-auto adds every column (no "Error executing DDL"); eventsLive 0 before the saves, 3 events and 2 rules in *_legacy; PUT 200, POST invoice 201/200, 2 rules 201/200 (PRD A5)',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/AutomationPreSchemaConfig.java' },
  async () => {
    const r = await bootAndUse(DBC3, LOG.newCsoft3, false);
    const keys = (r.state && r.state.eventsLegacy || []).map((x) => x.split('/').slice(0, 2).join('/')).sort();
    r.liveLegacyEvents = n1(DBC3, `select count(*) from automation_events where idempotency_key like 'legacy-%'`);
    const ok = r.ok && r.liveLegacyEvents === 0 && JSON.stringify(keys) === JSON.stringify(['legacy-k1/QUEUED', 'legacy-k2/DONE', 'legacy-k3/FAILED'])
      && r.state.rulesLegacy && r.state.rulesLegacy.length === 2 && JSON.stringify(legacyRows(DBC3, '_legacy')) === JSON.stringify(state.C.preRows);
    return { ok, severity: 'medium', actual: r, evidence: `${LOG.newCsoft3}` };
  });
}

// =============================================================================================================
// P6 — dataset A
// =============================================================================================================

async function phaseA(na) {
  if (!na.up) {
    R.rec({ id: 'MIG-A-02', feature: 'Startup on existing data', prd: 'B1', kind: 'API', status: 'FAIL', severity: 'high',
      title: 'New jar starts on the dev DB copy (halt_on_error=false, as the baseline)', steps: `boot new jar on ${I.DB_NEW}`, expected: 'started',
      actual: short(na.wait), evidence: `${LOG.newA}: ${levelLines(LOG.newA, 'ERROR').slice(0, 3).join(' | ')}` });
    return;
  }
  const pre = Object.fromEntries(['customers', 'invoices', 'payments', 'payment_promises', 'disputes'].map((t) => [t, state.dumpCounts[t]]));
  await upgradeChecks('MIG-A', { db: I.DB_NEW, log: LOG.newA, pre, users: usersAList(), snaps: state.snapA, inactive: state.inactiveA,
    baseInactive: state.baseInactiveLogin, hashesBefore: state.hashesBefore, resetUsers: state.resetUsers, ddlBase: state.ddlBaseA, halt: false });

  const cn = C.client(I.PORT_NEW);
  // ---- UI -----------------------------------------------------------------------------------------------
  await uiCasesA();

  // ---- writes after the upgrade -------------------------------------------------------------------------
  const t = await tokenFor(cn, 'admin', 'admin123');
  await kase({ id: 'MIG-A-40', feature: 'Saves after the upgrade', prd: 'A5',
    title: 'On the upgraded dev DB, editing an existing customer, creating an invoice and recording a payment still work as they do on the baseline',
    steps: `as admin, identical payloads on both copies (customer ${state.writeA && state.writeA.customerId}): PUT /api/customers/{id} {phone 555-0199}; POST /api/invoices (1 line x 10); POST /api/payments {amount 1}. Repeated twice on the upgraded copy`,
    expected: 'PRD A5 "a rule can never slow down or break the user\'s save": same outcome as the baseline (200, 200, 200) on both attempts; each save wrote its outbox row (CUSTOMER UPDATED, INVOICE CREATED, PAYMENT CREATED) and the consumer took it off PENDING ("the trigger publishes an event, a consumer performs the action")',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/event/AutomationEvents.java:124 (outbox insert into a pre-existing automation_events table); backend/src/main/java/com/geneinvoice/automation/AutomationSchemaUpgrade.java:31' },
  async () => {
    if (!state.writeA) return { status: 'BLOCKED', actual: 'no customer with a Collection POC and an open invoice found in the dev data' };
    const a1 = await doWrites(cn, t, state.writeA);
    const a2 = await doWrites(cn, t, state.writeA);
    const errs = read(LOG.newA).split('\n').filter((l) => /ERROR .*SqlExceptionHelper/.test(l)).map((l) => l.replace(/^.*SqlExceptionHelper\s+:\s*/, ''));
    const legacy = I.psql(I.DB_NEW, `select column_name, is_nullable, data_type from information_schema.columns where table_name='automation_events'
      and column_name in ('idempotency_key','trigger_kind','entity_type','status','kind') order by 1`).map((r) => r.join(' '));
    const same = (x) => x && ['customerPut', 'invoicePost', 'paymentPost'].every((k) => x[k][0] === state.writeBase[k][0]);
    // Round 2: the saves' outbox rows exist in the (legacy) automation_events table and the consumer takes them.
    const w = state.writeA;
    const ids = (x, k) => (x && x[k] && x[k][0] === 200 && typeof x[k][1] === 'number') ? x[k][1] : null;
    const invIds = [ids(a1, 'invoicePost'), ids(a2, 'invoicePost')].filter(Boolean); const payIds = [ids(a1, 'paymentPost'), ids(a2, 'paymentPost')].filter(Boolean);
    const evq = () => I.psql(I.DB_NEW, `select entity_type, kind, status from automation_events where (entity_type='CUSTOMER' and entity_id=${w.customerId})
      ${invIds.length ? `or (entity_type='INVOICE' and entity_id in (${invIds.join(',')}))` : ''} ${payIds.length ? `or (entity_type='PAYMENT' and entity_id in (${payIds.join(',')}))` : ''} order by id`).map((r) => r.join(':'));
    const consumed = await rt.waitFor(() => { const e = evq(); return e.length && e.every((x) => !x.endsWith(':PENDING')) ? e : null; }, { timeoutMs: 30000 });
    const ev = consumed || evq();
    const evOk = ev.filter((e) => e.startsWith('CUSTOMER:UPDATED')).length >= 2 && ev.filter((e) => e.startsWith('INVOICE:CREATED')).length >= 2 && ev.filter((e) => e.startsWith('PAYMENT:CREATED')).length >= 2 && !!consumed;
    return { ok: same(a1) && same(a2) && evOk, severity: 'high', actual: { baseline: state.writeBase, upgradedAttempt1: a1, upgradedAttempt2: a2, outboxEvents: ev, consumed: !!consumed },
      evidence: `backend log: ${[...new Set(errs)].slice(0, 4).join(' | ')}; automation_events columns (from the dev DB, pre-dating this build): ${legacy.join('; ')}` };
  });

  await kase({ id: 'MIG-A-41', feature: 'Rules over existing records', prd: 'A1',
    title: 'On the upgraded dev DB automation rules of every trigger kind can be created, and Run now acts on a migrated customer',
    steps: 'as admin: POST /api/automation/rules (MANUAL CUSTOMER rule, CREATE_TASK "Migrated {{Customer.Name}}") twice; then ENTITY_CREATED_OR_UPDATED INVOICE, ENTITY_CREATED PAYMENT, SCHEDULE_DAILY INVOICE 09:00, SCHEDULE_WEEKLY CUSTOMER MONDAY 09:00 (disabled); POST /rules/{first}/run {recordIds:[migrated customer]}; wait; SQL tasks',
    expected: 'DESIGN §6.14: 200 RuleDto for each; run SUCCEEDED done 1; one task "Migrated <name>" in region DEFAULT (A1/A5 "run from the UI whenever wanted", A6)',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/AutomationSchemaUpgrade.java:31 (legacy automation_rules columns action/run_count NOT NULL and trigger_kind CHECK are left in place)' },
  async () => {
    const body = () => rt.rule({ trigger: { kind: 'MANUAL', entityType: 'CUSTOMER' }, actions: [rt.action.task({ title: 'Migrated {{Customer.Name}}' })] });
    const r1 = await cn.api('POST', '/api/automation/rules', { token: t, body: body() });
    const r2 = await cn.api('POST', '/api/automation/rules', { token: t, body: body() });
    // Round 2: every trigger kind (the 25-char ENTITY_CREATED_OR_UPDATED on the legacy varchar(20) trigger_kind; the schedules).
    const more = {};
    for (const [k, tr] of Object.entries({ createdOrUpdated: { kind: 'ENTITY_CREATED_OR_UPDATED', entityType: 'INVOICE' }, created: { kind: 'ENTITY_CREATED', entityType: 'PAYMENT' },
      daily: { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '09:00' }, weekly: { kind: 'SCHEDULE_WEEKLY', entityType: 'CUSTOMER', time: '09:00', day: 'MONDAY' } })) {
      const x = await cn.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger: tr, enabled: false, actions: [rt.action.task({ title: 'Mig {{Customer.Name}}' })] }) });
      more[k] = [x.status, x.status === 200 ? x.json.trigger && x.json.trigger.kind : (x.json && (x.json.message || JSON.stringify(x.json.fieldErrors || x.json.errors))) || x.text.slice(0, 200)];
    }
    // Run now on the first rule over one migrated customer: the task lands on it.
    let runNow = null;
    if (r1.status === 200 && state.writeA) {
      const req = await cn.api('POST', `/api/automation/rules/${r1.json.id}/run`, { token: t, body: { recordIds: [state.writeA.customerId] } });
      const run = req.json && req.json.runId ? await rt.waitForRun(t, req.json.runId, { timeoutMs: 60000 }) : null;
      const tasks = I.psql(I.DB_NEW, `select t.title, r.code from tasks t join regions r on r.id=t.region_id where t.title like 'Migrated %' and t.customer_id=${state.writeA.customerId}`).map((x) => x.join('/'));
      runNow = { request: req.status, run: run && { status: run.status, matched: run.matched, done: run.done, failed: run.failed, message: run.message }, tasks };
    }
    const errs = read(LOG.newA).split('\n').filter((l) => /ERROR .*SqlExceptionHelper.*automation_rules/.test(l)).map((l) => l.replace(/^.*SqlExceptionHelper\s+:\s*/, ''));
    const legacy = I.psql(I.DB_NEW, `select conname from pg_constraint where conrelid='automation_rules'::regclass and contype='c' order by 1`).map((r) => r[0]);
    const runOk = runNow && runNow.run && runNow.run.status === 'SUCCEEDED' && runNow.run.done === 1 && runNow.tasks.length === 1 && runNow.tasks[0].endsWith('/DEFAULT');
    return { ok: r1.status === 200 && r2.status === 200 && Object.values(more).every((x) => x[0] === 200) && runOk, severity: 'high',
      actual: { attempt1: [r1.status, r1.status === 200 ? r1.json.id : r1.json && r1.json.message], attempt2: [r2.status, r2.status === 200 ? r2.json.id : r2.json && r2.json.message], otherTriggerKinds: more, runNow },
      evidence: `backend log: ${[...new Set(errs)].slice(0, 3).join(' | ')}; automation_rules CHECKs: ${legacy.join(', ')}` };
  });

  await kase({ id: 'MIG-A-44', feature: 'Legacy automation tables', prd: 'A5',
    title: 'The dev DB\'s legacy automation_events / automation_rules tables are reconciled: the outbox has no CHECK and only the mapped NOT NULLs; legacy rule CHECKs are gone or widened; trigger_kind is long enough',
    steps: 'SQL on e2e_migration after the upgrade: pg_constraint CHECKs on automation_events / automation_rules; information_schema NOT NULL columns and character_maximum_length of trigger_kind; *_legacy tables',
    expected: 'DESIGN §3.6 "No FK, no unique constraint, no enum or CHECK types" on the outbox: 0 CHECKs; NOT NULL only on attempts, claims, depth, id, kind, occurred_at, status (FIXLOG E2E r1 B2 step 1); automation_rules: no CHECK on the unmapped action column, the trigger_kind CHECK (if any) lists the 6 current constants, action/run_count nullable, trigger_kind length >= 25; no *_legacy table (the legacy tables were empty)',
    codeRef: 'backend/src/main/java/com/geneinvoice/automation/AutomationSchemaUpgrade.java:67-81' },
  async () => {
    const db = I.DB_NEW;
    const checks = (t) => I.psql(db, `select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid='${t}'::regclass and contype='c' order by 1`).map((r) => `${r[0]}: ${r[1].replace(/::character varying|::text/g, '')}`);
    const notNull = (t) => I.psql(db, `select column_name from information_schema.columns where table_schema='public' and table_name='${t}' and is_nullable='NO' order by 1`).map((r) => r[0]);
    const len = (t, c) => q1(db, `select character_maximum_length from information_schema.columns where table_schema='public' and table_name='${t}' and column_name='${c}'`);
    const ev = { checks: checks('automation_events'), notNull: notNull('automation_events') };
    const ru = { checks: checks('automation_rules'), notNull: notNull('automation_rules'), triggerKindLen: len('automation_rules', 'trigger_kind') };
    const legacyTables = I.psql(db, `select table_name from information_schema.tables where table_schema='public' and table_name like '%\\_legacy'`).map((r) => r[0]);
    const tk = ru.checks.filter((c) => /trigger_kind/.test(c));
    const ok = ev.checks.length === 0 && JSON.stringify(ev.notNull) === JSON.stringify(['attempts', 'claims', 'depth', 'id', 'kind', 'occurred_at', 'status'])
      && !ru.checks.some((c) => /\baction\b/.test(c.split(':')[0]) || /\(\(action\)/.test(c)) && !ru.notNull.includes('action') && !ru.notNull.includes('run_count')
      && tk.every((c) => ['ENTITY_CREATED', 'ENTITY_UPDATED', 'ENTITY_CREATED_OR_UPDATED', 'SCHEDULE_DAILY', 'SCHEDULE_WEEKLY', 'MANUAL'].every((v) => c.includes(`'${v}'`)))
      && Number(ru.triggerKindLen || 999) >= 25 && legacyTables.length === 0;
    return { ok, severity: 'high', actual: { automation_events: ev, automation_rules: ru, legacyTables } };
  });

  await kase({ id: 'MIG-A-45', feature: 'Rules over existing records', prd: 'A1',
    title: 'On the upgraded dev DB an ENTITY_UPDATED rule fires when an existing customer is saved (trigger -> outbox -> consumer -> task)',
    steps: 'as admin: POST /api/automation/rules {trigger ENTITY_UPDATED CUSTOMER, condition id:eq:<migrated customer>, CREATE_TASK "Changed {{Customer.Name}}"}; PUT /api/customers/{id} {phone 555-0145}; poll tasks for that title (<= 45 s)',
    expected: 'PRD A1/A5: rule 200; save 200; one task "Changed <customer name>" on that customer in region DEFAULT; the rule\'s run SUCCEEDED' },
  async () => {
    if (!state.writeA) return { status: 'BLOCKED', actual: 'no suitable migrated customer' };
    const id = state.writeA.customerId;
    const d = (await cn.api('GET', `/api/customers/${id}`, { token: t })).json;
    const rule = await cn.api('POST', '/api/automation/rules', { token: t, body: rt.rule({ trigger: { kind: 'ENTITY_UPDATED', entityType: 'CUSTOMER' },
      condition: { op: 'AND', children: [{ filter: `id:eq:${id}` }] }, actions: [rt.action.task({ title: 'Changed {{Customer.Name}}' })] }) });
    if (rule.status !== 200) return { ok: false, severity: 'high', actual: { rule: [rule.status, rule.text.slice(0, 300)] } };
    const put = await cn.api('PUT', `/api/customers/${id}`, { token: t, body: { name: d.name, phone: '555-0145', email: d.email, address: d.address, paymentTerm: d.paymentTerm } });
    const tasks = await rt.waitFor(() => { const r = I.psql(I.DB_NEW, `select t.title, r.code from tasks t join regions r on r.id=t.region_id where t.customer_id=${id} and t.title like 'Changed %'`).map((x) => x.join('/')); return r.length ? r : null; }, { timeoutMs: 45000, intervalMs: 1500 });
    const runs = await cn.api('GET', `/api/automation/rules/${rule.json.id}/runs`, { token: t, query: { size: 10 } });
    const run = runs.json && runs.json.content && runs.json.content[0];
    const ok = put.status === 200 && tasks && tasks.length === 1 && tasks[0] === `Changed ${d.name}/DEFAULT` && run && run.status === 'SUCCEEDED';
    return { ok, severity: 'high', actual: { rule: rule.json.id, put: put.status, tasks, run: run && { id: run.id, status: run.status, matched: run.matched, done: run.done, message: run.message } } };
  });

  await kase({ id: 'MIG-A-42', feature: 'History baseline', prd: 'B3',
    title: 'The history reconciler finds nothing to repair on the migrated data',
    steps: 'as admin: POST /api/admin/history/reconcile',
    expected: 'DESIGN §6.10: 200 with missing 0, drifted 0, orphaned 0, duplicates 0, linkDrift 0, repaired 0' },
  async () => {
    const r = await cn.api('POST', '/api/admin/history/reconcile', { token: t, body: {} });
    const j = r.json || {};
    return { ok: r.status === 200 && ['missing', 'drifted', 'orphaned', 'duplicates', 'linkDrift', 'repaired'].every((k) => j[k] === 0), actual: [r.status, j] };
  });

  // ---- second boot --------------------------------------------------------------------------------------
  await kase({ id: 'MIG-A-43', feature: 'Idempotent upgrade', prd: 'B1',
    title: 'A second boot of the new jar on the upgraded dev DB changes nothing: no new region, grant, backfill audit or baseline versions',
    steps: `record regions/grants/versions/REGIONS_BACKFILLED/history_meta; stop and restart the new jar on ${I.DB_NEW}; compare; grep the second log`,
    expected: 'DESIGN §3.11/§5.7.9: identical counts; history_meta unchanged; no "history.baseline.gaps" warning; no "Put … in a region" / "Gave … access" lines on the second boot' },
  async () => {
    const snap = () => ({ regions: n1(I.DB_NEW, 'select count(*) from regions'), grants: n1(I.DB_NEW, 'select count(*) from user_region_grants'),
      backfillAudits: n1(I.DB_NEW, `select count(*) from audit_logs where action='REGIONS_BACKFILLED'`), meta: I.psql(I.DB_NEW, 'select started_at, baseline_completed_at from history_meta'),
      versions: Object.fromEntries(VERSIONED.map(([, v]) => [v, n1(I.DB_NEW, `select count(*) from ${v}`)])) });
    const before = snap();
    await I.stopNewBackend();
    const n2 = await boot({ kind: 'backend', jar: I.NEW_JAR, port: I.PORT_NEW, db: I.DB_NEW, log: LOG.newA2, halt: false });
    const after = snap();
    const noisy = read(LOG.newA2).split('\n').filter((l) => /history\.baseline\.gaps|Put \d+ row\(s\)|Gave \d+ existing|Created the default region/.test(l));
    return { ok: n2.up && JSON.stringify(before) === JSON.stringify(after) && noisy.length === 0, actual: { restarted: n2.up, before, after, upgradeLinesOnSecondBoot: noisy } };
  });

  // ---- round 4: one step around the r3 fixes (ASO-R3-1 / RGN-R3-1: approval detail as of a date names records from
  // history). On migrated data the only history of the products and customers is the upgrade's baseline versions.
  await kase({ id: 'MIG-A-46', feature: 'Approvals on migrated data', prd: 'B3',
    title: 'A held invoice on a migrated customer (migrated product) reads live and as of a date: names come from the upgrade\'s history baseline, not from today',
    steps: 'on the upgraded dev DB (after the second boot), as admin: PUT /api/regions/<DEFAULT>/approval-thresholds [INVOICE_CREATE 5]; POST /api/invoices (migrated customer, 1 x migrated product at 10) -> held; GET /api/approvals/{id}; ageWorld(2) on e2e_migration; PUT /api/products/{pid} renamed "<name> R4"; GET /api/approvals/{id}?asOf=<yesterday>; GET /api/approvals?asOf=<yesterday>; GET /api/approvals/{id}?asOf=<5 days ago>; GET /api/approvals/{id} live; withdraw; rename back',
    expected: 'B2/B3 + FIXLOG E2E r3 B1: threshold 200; invoice 202 PENDING_APPROVAL; live detail 200, Items "1 × <old name> at ₹10.00", blockedReason "You requested this change", canWithdraw; as of yesterday 200, statusAt PENDING, current null, canApprove/canWithdraw false, blockedReason "Read-only in the as-of view", Items with the OLD product name (from the baseline product version) and customerName the migrated name; list as of yesterday contains it; before the (aged) history start 400 AS_OF_BEFORE_HISTORY; live Items with the new name',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:132-190 (detail/blockedReason), :385-400 (item lines)' },
  async () => {
    if (!state.writeA) return { status: 'BLOCKED', actual: 'no suitable migrated customer' };
    const t2 = await tokenFor(cn, 'admin', 'admin123');
    const regs = await cn.api('GET', '/api/regions', { token: t2, query: { size: 50 } });
    const def = (regs.json && (regs.json.content || regs.json) || []).find((r) => r.defaultRegion || r.code === 'DEFAULT');
    const th = await cn.api('PUT', `/api/regions/${def.id}/approval-thresholds`, { token: t2, body: { thresholds: [{ operation: 'INVOICE_CREATE', amount: 5, windowHours: 24 }] } });
    const w = state.writeA;
    const pid = w.invoicePost.items[0].productId;
    const prod = (await cn.api('GET', `/api/products/${pid}`, { token: t2 })).json;
    const inv = await cn.api('POST', '/api/invoices', { token: t2, body: { ...w.invoicePost, notes: 'e2e r4 held' } });
    const pcId = inv.json && inv.json.pendingChange && inv.json.pendingChange.id;
    if (inv.status !== 202 || !pcId) return { ok: false, severity: 'high', actual: { threshold: [th.status, th.text.slice(0, 200)], invoice: [inv.status, inv.text.slice(0, 300)] } };
    const items = (d) => { const c = d && (d.changes || []).find((x) => x.field === 'items'); return c ? (c.proposedText || JSON.stringify(c.proposed)) : null; };
    const brief = (r) => r.json ? { status: r.json.status, statusAt: r.json.statusAt, customerName: r.json.customerName, items: items(r.json), current: r.json.current === null ? null : 'set',
      canApprove: r.json.canApprove, canReject: r.json.canReject, canWithdraw: r.json.canWithdraw, blockedReason: r.json.blockedReason } : r.text.slice(0, 300);
    const live1 = await cn.api('GET', `/api/approvals/${pcId}`, { token: t2 });
    rt.ageWorld(2, { db: I.DB_NEW });
    const renamed = `${prod.name} R4`.slice(0, 100);
    const put = await cn.api('PUT', `/api/products/${pid}`, { token: t2, body: { name: renamed, description: prod.description, price: prod.price, active: prod.active } });
    const yday = rt.day(-1);
    const asOf = await cn.api('GET', `/api/approvals/${pcId}`, { token: t2, query: { asOf: yday } });
    const listAsOf = await cn.api('GET', '/api/approvals', { token: t2, query: { size: 50, asOf: yday } });
    const before = await cn.api('GET', `/api/approvals/${pcId}`, { token: t2, query: { asOf: rt.day(-5) } });
    const live2 = await cn.api('GET', `/api/approvals/${pcId}`, { token: t2 });
    const wd = await cn.api('POST', `/api/approvals/${pcId}/withdraw`, { token: t2, body: {} });
    const back = await cn.api('PUT', `/api/products/${pid}`, { token: t2, body: { name: prod.name, description: prod.description, price: prod.price, active: prod.active } });
    const a = asOf.json || {}; const l1 = live1.json || {}; const l2 = live2.json || {};
    const inList = listAsOf.json && (listAsOf.json.content || []).some((x) => x.id === pcId);
    const oldLine = `1 × ${prod.name} at`; const newLine = `1 × ${renamed} at`;
    const checks = {
      threshold200: th.status === 200,
      live200: live1.status === 200 && (items(l1) || '').includes(oldLine) && l1.blockedReason === 'You requested this change' && l1.canWithdraw === true,
      asOf200: asOf.status === 200,
      asOfPending: a.statusAt === 'PENDING' && a.current === null && a.canApprove === false && a.canWithdraw === false && a.blockedReason === 'Read-only in the as-of view',
      asOfOldName: (items(a) || '').includes(oldLine) && !(items(a) || '').includes(renamed),
      asOfCustomer: !!a.customerName,
      listAsOf: listAsOf.status === 200 && !!inList,
      beforeHistory400: before.status === 400 && /AS_OF_BEFORE_HISTORY/.test(before.text),
      liveNewName: live2.status === 200 && (items(l2) || '').includes(newLine),
      renamed: put.status === 200, withdrawn: wd.status === 200, renamedBack: back.status === 200,
    };
    const ok = Object.values(checks).every(Boolean);
    const errs = levelLines(LOG.newA2, 'ERROR').slice(-3);
    return { ok, severity: 'high', actual: { pendingChange: pcId, product: [pid, prod.name], checks, live1: brief(live1), asOf: [asOf.status, brief(asOf)],
      listAsOf: [listAsOf.status, inList], beforeHistory: [before.status, before.text.slice(0, 200)], live2: brief(live2), withdraw: wd.status },
      evidence: `backend log ${LOG.newA2} last ERRORs: ${errs.join(' | ')}` };
  });

  // ---- round 5: one step around the r4 fix (RGN-R4-1, recordRegion over version rows) on migrated data.
  await require('./r5-case.js')({ kase, cn, I, state, usersAList, tokenFor, levelLines, LOG, short });
}

function usersAList() { return usersAHolder.list; }
const usersAHolder = { list: [] };

// ---- UI cases on dataset A ------------------------------------------------------------------------------------

async function uiCasesA() {
  const admin = usersAHolder.list.find((u) => u.username === 'admin');
  const sbAdmin = state.snapA.admin;
  const baseErr = (sb) => new Set(Object.entries(sb.misc).filter(([, v]) => v.status >= 400).map(([k]) => k));
  const unexpectedErrors = (app, sb) => app.apiErrors.filter((e) => ![...baseErr(sb)].some((p) => e.url.startsWith(p)));

  await kase({ id: 'MIG-UI-01', kind: 'UI', feature: 'Existing logins in the UI', prd: 'B1',
    title: 'The existing admin signs in through the login screen with the unchanged credentials and the dashboard shows the pre-upgrade figures',
    steps: 'open the web app (no token), type admin/admin123, Sign in; read the dashboard; screenshot',
    expected: `"Welcome, <fullName>"; "Invoices by status" total and per-status counts equal the baseline invoice summary; no API error the baseline did not also have; no region selector (single region)` },
  async () => {
    const app = await rt.openApp({});
    try {
      await rt.uiLogin(app.page, 'admin', 'admin123');
      await app.page.waitForTimeout(3000); await rt.enableSemantics(app.page);
      const text = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-01-1');
      const s = sbAdmin.summaries.invoices.body;
      const me = sbAdmin.misc['/api/auth/me'].body;
      const idx = text.indexOf('Invoices by status');
      const seg = text.slice(idx, idx + 16);
      const want = [String(s.count), String(s.fullyPaidCount), String(s.unpaidCount), String(s.partiallyPaidCount), String(s.cancelledCount)];
      const sel = await rt.regionSelector(app.page);
      const errs = unexpectedErrors(app, sbAdmin);
      const ok = text.includes(`Welcome, ${me.fullName}`) && idx >= 0 && want.every((w) => seg.includes(w)) && !sel && errs.length === 0;
      return { ok, screenshot: shot, actual: { welcome: text.find((x) => x.startsWith('Welcome')), statusSegment: seg, expectedCounts: want, regionSelector: sel && sel.label, unexpectedApiErrors: errs, knownBaselineErrors: [...baseErr(sbAdmin)] } };
    } finally { await app.close(); }
  });

  await kase({ id: 'MIG-UI-02', kind: 'UI', feature: 'Lists unchanged in the UI', prd: 'B1',
    title: 'Customers and invoices lists in the UI show the same totals as before the upgrade, with no Region column for a single-region user',
    steps: 'as admin (token): /customers and /invoices; read the "1–20 of N" pager and the column headers; screenshots',
    expected: 'pager totals = baseline totalElements (customers, invoices); no "Region" header (DESIGN §7: column only when multiRegion)' },
  async () => {
    const app = await rt.openApp({ username: 'admin', password: 'admin123' });
    try {
      await rt.go(app.page, '/customers', 4000);
      const ct = await rt.screenText(app.page);
      const s1 = await rt.shot(app.page, SHOTS, 'MIG-UI-02-1');
      await rt.go(app.page, '/invoices', 4000);
      const it = await rt.screenText(app.page);
      await rt.shot(app.page, SHOTS, 'MIG-UI-02-2');
      const cn = sbAdmin.lists.customers.totalElements; const inN = sbAdmin.lists.invoices.totalElements;
      const cPager = ct.find((x) => /^\d+–\d+ of \d+$/.test(x)); const iPager = it.find((x) => /^\d+–\d+ of \d+$/.test(x));
      const ok = cPager === `1–20 of ${cn}` && iPager === `1–20 of ${inN}` && !ct.includes('Region') && !it.includes('Region');
      return { ok, screenshot: s1, actual: { customersPager: cPager, expectedCustomers: cn, invoicesPager: iPager, expectedInvoices: inN, regionHeader: ct.includes('Region') || it.includes('Region') } };
    } finally { await app.close(); }
  });

  const staffBook = usersAHolder.list.find((u) => !u.customer && state.snapA[u.username] && (state.snapA[u.username].lists.invoices.lockedFilters || []).length > 0)
    || usersAHolder.list.find((u) => !u.customer && u.username !== 'admin');
  await kase({ id: 'MIG-UI-03', kind: 'UI', feature: 'Existing logins in the UI', prd: 'B1',
    title: `An existing ${staffBook ? staffBook.role : 'staff'} user (${staffBook ? staffBook.username : '-'}) signs in through the UI and sees the same invoice list as before`,
    steps: 'login screen: username/password (reset to Passw0rd! in both copies before any boot); go /invoices; read pager; screenshot',
    expected: 'sign-in succeeds; invoices pager total = the baseline totalElements for that user (their book, if the role has no SCOPE_OVERRIDE)' },
  async () => {
    if (!staffBook) return { status: 'NOT_TESTED', actual: 'no staff user' };
    const app = await rt.openApp({});
    try {
      await rt.uiLogin(app.page, staffBook.username, staffBook.password);
      await app.page.waitForTimeout(2500);
      await rt.go(app.page, '/invoices', 4000);
      const text = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-03-1');
      const n = state.snapA[staffBook.username].lists.invoices.totalElements;
      const pager = text.find((x) => /^\d+–\d+ of \d+$/.test(x)) || null;
      const exp = n === 0 ? null : `1–${Math.min(20, n)} of ${n}`;
      const ok = !text.includes('Sign in') && (exp ? pager === exp : true);
      return { ok, screenshot: shot, actual: { user: staffBook.username, pager, expected: exp, stillOnLogin: text.includes('Sign in') } };
    } finally { await app.close(); }
  });

  const cust = usersAHolder.list.find((u) => u.customer);
  await kase({ id: 'MIG-UI-04', kind: 'UI', feature: 'Existing logins in the UI', prd: 'B1',
    title: `An existing customer login (${cust ? cust.username : '-'}) signs in through the UI and sees its own invoices, with no region selector and no as-of chip`,
    steps: 'login screen as the customer; go /invoices; read pager, look for "Regions:" and the "Today" chip; screenshot',
    expected: 'invoices pager = baseline total for that customer; region selector hidden and as-of chip absent for customer logins (README §4, DESIGN §7)' },
  async () => {
    if (!cust) return { status: 'NOT_TESTED', actual: 'no customer login' };
    const app = await rt.openApp({});
    try {
      await rt.uiLogin(app.page, cust.username, cust.password);
      await app.page.waitForTimeout(2500);
      await rt.go(app.page, '/invoices', 4000);
      const text = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-04-1');
      const n = state.snapA[cust.username].lists.invoices.totalElements;
      const pager = text.find((x) => /^\d+–\d+ of \d+$/.test(x)) || null;
      const exp = n === 0 ? null : `1–${Math.min(20, n)} of ${n}`;
      const sel = await rt.regionSelector(app.page);
      const chip = await rt.asOfChip(app.page);
      const ok = !text.includes('Sign in') && (exp ? pager === exp : true) && !sel && !chip;
      return { ok, screenshot: shot, actual: { pager, expected: exp, regionSelector: sel && sel.label, asOfChip: chip && (chip.label || chip.text) } };
    } finally { await app.close(); }
  });

  await kase({ id: 'MIG-UI-05', kind: 'UI', feature: 'Existing logins in the UI', prd: 'B1',
    title: 'A wrong password for an existing user is refused in the UI (negative)',
    steps: 'login screen: admin / wrong-password; Sign in; screenshot',
    expected: 'stays on the login screen with an error; no token stored' },
  async () => {
    const app = await rt.openApp({});
    try {
      await rt.uiLogin(app.page, 'admin', 'not-the-password');
      const text = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-05-1');
      const tokenSet = await app.page.evaluate(() => localStorage.getItem('flutter.gene_invoice_token'));
      const ok = text.includes('Sign in') && !tokenSet && text.some((x) => /invalid|incorrect|wrong/i.test(x));
      return { ok, screenshot: shot, actual: { onLogin: text.includes('Sign in'), token: !!tokenSet, messages: text.filter((x) => /invalid|incorrect|wrong|error/i.test(x)) } };
    } finally { await app.close(); }
  });

  await kase({ id: 'MIG-UI-06', kind: 'UI', feature: 'Existing logins in the UI', prd: 'B1',
    title: `The existing inactive user (${state.inactiveA || '-'}) is refused in the UI, as before the upgrade (negative)`,
    steps: 'login screen as the inactive user (password reset in both copies); Sign in; screenshot',
    expected: 'stays on the login screen with an error (baseline login status 401)' },
  async () => {
    if (!state.inactiveA) return { status: 'NOT_TESTED', actual: 'no inactive staff user' };
    const app = await rt.openApp({});
    try {
      await rt.uiLogin(app.page, state.inactiveA, PW);
      const text = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-06-1');
      return { ok: text.includes('Sign in') && state.baseInactiveLogin === 401, screenshot: shot,
        actual: { onLogin: text.includes('Sign in'), baselineApi: state.baseInactiveLogin, messages: text.filter((x) => /invalid|inactive|disabled|error/i.test(x)) } };
    } finally { await app.close(); }
  });

  const firstCust = sbAdmin.lists.customers.ids[0];
  await kase({ id: 'MIG-UI-07', kind: 'UI', feature: 'Migrated record detail', prd: 'B3',
    title: 'A migrated customer\'s detail page opens with its POCs and History tab; the history shows the pre-upgrade audit entries',
    steps: `as admin: go /customers/${firstCust}; tap History; screenshot`,
    expected: 'customer name shown; History lists entries (the baseline audit timeline had entries for this customer); no unexpected API error' },
  async () => {
    const app = await rt.openApp({ username: 'admin', password: 'admin123' });
    try {
      await rt.go(app.page, `/customers/${firstCust}`, 4500);
      const name = sbAdmin.lists.customers.rows[firstCust].name;
      const t1 = await rt.screenText(app.page);
      let tapped = true;
      try { await rt.tap(app.page, 'History', { wait: 3000 }); } catch { tapped = false; }
      const t2 = await rt.screenText(app.page);
      const shot = await rt.shot(app.page, SHOTS, 'MIG-UI-07-1');
      const auditCount = sbAdmin.details[`/api/audit?CUSTOMER:${firstCust}`] && sbAdmin.details[`/api/audit?CUSTOMER:${firstCust}`].count;
      const errs = unexpectedErrors(app, sbAdmin);
      const historyish = t2.filter((x) => /created|updated|payment|invoice|added|changed/i.test(x)).length;
      const ok = t1.includes(name) && tapped && (auditCount ? historyish > 0 : true) && errs.length === 0;
      return { ok, screenshot: shot, actual: { name, tappedHistory: tapped, baselineAuditEntries: auditCount, historyLines: historyish, sample: t2.slice(0, 40).join(' | '), unexpectedApiErrors: errs } };
    } finally { await app.close(); }
  });
}

// =============================================================================================================

(async () => {
  const t0 = Date.now();
  try {
    await main();
  } catch (e) {
    R.rec({ id: 'MIG-HARNESS', feature: 'harness', prd: 'B1', kind: 'API', status: 'BLOCKED', title: 'Suite aborted', actual: String(e && e.stack || e) });
  } finally {
    await I.stopExtras();
    for (const db of [I.DB_BASE, DB_SEED, DB_SEEDUP]) I.dropDb(db);
    const file = R.save();
    console.log(`done in ${Math.round((Date.now() - t0) / 1000)}s -> ${file}`);
  }
})();
