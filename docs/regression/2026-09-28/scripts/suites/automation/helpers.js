// Shared helpers for the automation E2E suite (area "automation").
const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const rt = require('../../lib.js');

const AREA = 'automation';
const DEFECT_LOB = 'AUT-R1-1';

function makeResults(round) {
  const R = rt.results({ area: AREA, round });
  // The task asks for runs/r<round>/automation/shots/.
  R.shotsDir = path.join(rt.HOME, 'runs', `r${round}`, AREA, 'shots');
  fs.mkdirSync(R.shotsDir, { recursive: true });
  return R;
}

const short = (x, n = 600) => {
  const s = typeof x === 'string' ? x : JSON.stringify(x);
  return s && s.length > n ? s.slice(0, n) + '…' : s;
};

/**
 * Runs one case. fn(attempt) returns {ok, actual, evidence, ...overrides} or {status, ...}.
 * A failing case is run a second time (from fresh fixtures, as fn builds its own); only a second failure is a FAIL.
 * A failure that does not repeat is NOT_TESTED with a note. An exception is BLOCKED.
 */
async function kase(R, meta, fn, { repeat = true } = {}) {
  if (process.env.CASES && !process.env.CASES.split(',').includes(meta.id)) return null; // debugging filter
  const attempts = [];
  let final = null;
  for (let attempt = 1; attempt <= (repeat ? 2 : 1); attempt++) {
    let res;
    try {
      res = await fn(attempt);
    } catch (e) {
      res = { status: 'BLOCKED', actual: `exception: ${String(e && e.stack || e).slice(0, 1500)}` };
    }
    attempts.push(res);
    final = res;
    if (res.status && res.status !== 'FAIL') break; // explicit PASS / BLOCKED / NOT_TESTED
    if (!res.status && res.ok) break;
  }
  const first = attempts[0];
  const failed = (r) => r && (r.status === 'FAIL' || (!r.status && !r.ok));
  let rec = { ...meta, ...final };
  if (attempts.length === 2 && failed(first) && !failed(final)) {
    rec = { ...meta, ...final, status: 'NOT_TESTED', actual: `Failed on attempt 1, passed on attempt 2 (not reproduced). Attempt 1: ${short(first.actual, 800)} | Attempt 2: ${short(final.actual, 400)}` };
  } else if (attempts.length === 2 && failed(first) && failed(final)) {
    rec = { ...meta, ...final, actual: `Reproduced twice. Attempt 1: ${short(first.actual, 900)} | Attempt 2: ${short(final.actual, 900)}` };
  }
  if (typeof rec.actual !== 'string') rec.actual = short(rec.actual, 2000);
  if (typeof rec.evidence !== 'string') rec.evidence = short(rec.evidence, 2000);
  delete rec.ok_;
  return R.rec(rec);
}

// ---- fixtures -------------------------------------------------------------------------------

/** A fresh customer in `regionId`, a primary Collection POC, and one invoice per amount (sales POC = collector). */
async function mkCustomer(t, { regionId, collector, amounts = [1000], name, dueDays = 20, extraPocs = [] }) {
  const c = await rt.createCustomer(t, { name: name || rt.uniq('AutoCust'), regionId, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(t, c.id, 'COLLECTION', collector, true);
  for (const [type, uid, primary] of extraPocs) await rt.addCustomerPoc(t, c.id, type, uid, primary);
  const invoices = [];
  for (const a of amounts) {
    const r = await rt.createInvoice(t, { customerId: c.id, amount: a, dueDate: rt.day(dueDays), paymentTerm: 'CUSTOM', salesPocUserId: collector });
    rt.need(r, `invoice ${a} for ${c.name}`, [200]);
    invoices.push(r.json);
  }
  return { c, invoices, collector };
}

async function payInvoice(t, cust, inv, amount) {
  const p = await rt.createPayment(t, { customerId: cust.c.id, amount, invoiceIds: [inv.id], collectionPocUserId: cust.collector });
  rt.need(p, 'payment', [200]);
  return p.json;
}

// ---- rules and runs ---------------------------------------------------------------------------

async function createRule(t, spec) {
  const r = await rt.automation.create(t, rt.rule(spec));
  return r;
}
async function mustRule(t, spec) {
  const r = await createRule(t, spec);
  rt.need(r, `create rule ${spec.name || ''}`);
  return r.json;
}
const manual = (entityType = 'INVOICE') => ({ kind: 'MANUAL', entityType });
const and = (...filters) => ({ op: 'AND', children: filters.map((f) => (typeof f === 'string' ? { filter: f } : f)) });
const or = (...filters) => ({ op: 'OR', children: filters.map((f) => (typeof f === 'string' ? { filter: f } : f)) });

/** POST /run and wait for the run to finish. Returns {req, run, effects}. */
async function runAndWait(t, ruleId, body = {}, { timeoutMs = 60000, query } = {}) {
  const req = await rt.api('POST', `/api/automation/rules/${ruleId}/run`, { token: t, body, query });
  if (![200, 202].includes(req.status)) return { req, run: null, effects: [] };
  const run = await rt.waitForRun(t, req.json.runId, { timeoutMs });
  const eff = run ? await rt.automation.effects(t, req.json.runId) : null;
  return { req, run, effects: eff?.json?.content || [] };
}

async function tasksOfRule(t, ruleId) {
  const r = await rt.list(t, '/api/tasks', { filter: [`ruleId:eq:${ruleId}`] });
  return r.json?.content || [];
}
async function taskDetail(t, id) {
  return (await rt.api('GET', `/api/tasks/${id}`, { token: t })).json;
}
async function emailsOn(t, type, id) {
  return (await rt.emailsFor(t, type, id)).json?.content || [];
}
async function promisesOf(t, customerId) {
  const r = await rt.list(t, '/api/promises', { filter: [`customerId:eq:${customerId}`] });
  return r.json?.content || [];
}

/** Disables and deletes rules (clean-up; ignores errors). */
async function retire(t, ...ids) {
  for (const id of ids.flat()) {
    if (!id) continue;
    await rt.automation.disable(t, id);
  }
}

function countRows(table, where = 'true') {
  return Number(rt.sql(`select count(*) from ${table} where ${where}`)[0][0]);
}

/** UTC HH:mm `minutes` from now (rounded up to the next whole minute first). */
function hhmmAhead(minutes, zoneOffsetMin = 0) {
  const d = new Date(Date.now() + zoneOffsetMin * 60000);
  d.setUTCSeconds(0, 0);
  d.setUTCMinutes(d.getUTCMinutes() + minutes);
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
}
const WEEKDAYS = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
function weekdayAhead(minutes) {
  const d = new Date(Date.now());
  d.setUTCSeconds(0, 0);
  d.setUTCMinutes(d.getUTCMinutes() + minutes);
  return WEEKDAYS[d.getUTCDay()];
}

// ---- backend process control (this area's own backend only) --------------------------------

const RESTART = path.join(__dirname, 'restart-backend.sh');
function backend(action) {
  return execFileSync(RESTART, [AREA, action], { encoding: 'utf8', timeout: 240000 });
}

function logGrep(pattern, max = 5) {
  const f = path.join(rt.HOME, 'runtime', AREA, 'backend.log');
  const lines = fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.includes(pattern));
  return lines.slice(-max).map((l) => l.slice(0, 300));
}

module.exports = {
  AREA, DEFECT_LOB, makeResults, kase, short, mkCustomer, payInvoice, createRule, mustRule, manual, and, or,
  runAndWait, tasksOfRule, taskDetail, emailsOn, promisesOf, retire, countRows, hhmmAhead, weekdayAhead, WEEKDAYS,
  backend, logGrep,
};
