// Shared fixtures, checks and UI helpers for the approvals (B2 maker-checker) suite.
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const E2E = path.resolve(__dirname, '..', '..');

// ---- results --------------------------------------------------------------------------------

function makeResults(round) {
  const R = rt.results({ area: process.env.APP_AREA_OUT || 'approvals', round });
  // Screenshots go to runs/r<round>/approvals/shots (the orchestrator's layout).
  R.shotsDir = path.join(E2E, 'runs', `r${round}`, 'approvals', 'shots');
  fs.mkdirSync(R.shotsDir, { recursive: true });
  return R;
}

/** An accumulating list of named assertions; `ok` when all hold, `summary()` lists them. */
function checks() {
  const items = [];
  const fmt = (d) => {
    if (d === undefined) return '';
    const s = typeof d === 'string' ? d : JSON.stringify(d);
    return ` [${String(s).slice(0, 400)}]`;
  };
  return {
    items,
    that(label, cond, detail) { items.push({ label, ok: !!cond, detail }); return !!cond; },
    get ok() { return items.length > 0 && items.every((i) => i.ok); },
    failed() { return items.filter((i) => !i.ok).map((i) => `${i.label}${fmt(i.detail)}`).join('; '); },
    summary() { return items.map((i) => `${i.ok ? 'ok' : 'NOT OK'}: ${i.label}${fmt(i.detail)}`).join('; '); },
  };
}

/**
 * Runs one case. `fn(attempt)` returns {ok, actual, evidence, screenshot, codeRef, severity, expected?} and must build its
 * own fixtures so a second attempt starts clean. A FAIL is re-run once (README: every FAIL reproduced twice); a FAIL that
 * does not reproduce becomes NOT_TESTED. An exception is BLOCKED (a fixture or harness problem, not a verdict).
 */
function caseRunner(R) {
  const onlyIds = process.env.APP_ONLY ? new Set(process.env.APP_ONLY.split(',')) : null;
  return async function tcase(meta, fn, { retry = true } = {}) {
    if (onlyIds && !onlyIds.has(meta.id)) return null;
    let res;
    try {
      res = await fn(1);
    } catch (e) {
      return R.rec({ ...meta, status: 'BLOCKED', actual: `Blocked: ${String(e && e.stack || e).slice(0, 1500)}` });
    }
    if (!res.ok && retry) {
      let res2;
      try { res2 = await fn(2); } catch (e) { res2 = { ok: false, actual: `retry threw: ${String(e).slice(0, 600)}` }; }
      if (res2.ok) {
        return R.rec({ ...meta, ...res, status: 'NOT_TESTED', severity: '',
          actual: `Attempt 1 FAILED: ${res.actual} || Attempt 2 PASSED — not reproduced, so not counted. ${res2.actual}` });
      }
      res = { ...res, ...res2, actual: `Attempt 1: ${res.actual} || Attempt 2 (reproduced): ${res2.actual}`,
        evidence: [res.evidence, res2.evidence].filter(Boolean).join(' || ') };
    }
    return R.rec({ ...meta, ...res, ok: res.ok });
  };
}

// ---- world ----------------------------------------------------------------------------------

/**
 * The standard world plus the approval cast:
 *   mia   MANAGE/ALL South only            (a maker with no approval right)
 *   nate  APPROVE/ALL North, READ/ALL South (an approver from another region who can see South)
 * South (seeded): INVOICE_CREATE 10000, PAYMENT_RECORD 5000, INVOICE_CANCEL 5000, PROMISE_CREATE 5000 (24 h)
 * South (added here, tightening): INVOICE_TERMS 1000, PROMISE_UPDATE 1000, PROMISE_OVERRIDE 1000, DISPUTE_APPROVE 1000 (24 h)
 * North (added here): INVOICE_CREATE 50000 (24 h)
 */
async function buildWorld() {
  const admin = await rt.adminToken();
  const w = await rt.seedWorld(admin);
  const R = w.regions;
  const extra = [
    ['mia', 'Mia Maker', [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]],
    ['nate', 'Nate North', [{ regionId: R.NORTH.id, accessLevel: 'APPROVE', recordScope: 'ALL' }, { regionId: R.SOUTH.id, accessLevel: 'READ', recordScope: 'ALL' }]],
  ];
  for (const [username, fullName, grants] of extra) {
    let u = await rt.findUser(admin, username);
    if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName, grants });
    else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
    w.users[username] = { ...u, password: rt.PASSWORD, grants };
    w.tokens[username] = await rt.login(username);
  }
  // Fixture makers for the shared customer s1 (the customer login's): MANAGE South only, so they add no approver,
  // rotated so no one of them builds up the cumulative window on s1.
  w.fxTokens = [];
  for (const k of ['fxa', 'fxb', 'fxc', 'fxd', 'fxe', 'fxf']) {
    const grants = [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }];
    let u = await rt.findUser(admin, k);
    if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', k, { username: k, fullName: `Fixture ${k}`, grants });
    else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${k}`);
    w.fxTokens.push(await rt.login(k));
  }
  let fxi = 0;
  w.nextFx = () => w.fxTokens[(fxi++) % w.fxTokens.length];
  w.users.admin = await rt.me(admin);
  // On a re-used environment the seed may loosen a limit an earlier run tightened; that is held for approval.
  // Withdraw it so the suite starts from the seeded limits without an outstanding threshold change.
  if (w.thresholds && w.thresholds.pendingChange) await rt.approvals.withdraw(admin, w.thresholds.pendingChange.id);
  const south = await rt.setThresholds(admin, R.SOUTH.id, [
    { operation: 'INVOICE_TERMS', amount: 1000, windowHours: 24 },
    { operation: 'PROMISE_UPDATE', amount: 1000, windowHours: 24 },
    { operation: 'PROMISE_OVERRIDE', amount: 1000, windowHours: 24 },
    { operation: 'DISPUTE_APPROVE', amount: 1000, windowHours: 24 },
  ]);
  w.southSetup = south;
  const north = await rt.setThresholds(admin, R.NORTH.id, [{ operation: 'INVOICE_CREATE', amount: 50000, windowHours: 24 }]);
  w.northSetup = north;
  return w;
}

// ---- fixtures -------------------------------------------------------------------------------

/** A fresh customer in `region` (default South) with a primary Collection POC; created by admin. */
async function customerIn(w, region = 'SOUTH', prefix = 'apv') {
  const poc = { SOUTH: w.users.sam.id, NORTH: w.users.nora.id, WEST: w.users.wendy.id, DEFAULT: w.users.admin.id }[region];
  const c = await rt.createCustomer(w.tokens.admin, { name: `${region} ${rt.uniq(prefix)}`, regionId: w.regions[region].id, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(w.tokens.admin, c.id, 'COLLECTION', poc, true);
  c.pocId = poc;
  c.region = region;
  return c;
}

/** An invoice made by admin (applied; throws if held). */
async function invoiceBy(token, c, amount, extra = {}) {
  const r = await rt.createInvoice(token, { customerId: c.id, amount, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: c.pocId, ...extra });
  rt.need(r, `fixture invoice ${amount} for ${c.name}`, [200]);
  return r.json;
}
async function paymentBy(token, c, amount, extra = {}) {
  const r = await rt.createPayment(token, { customerId: c.id, amount, collectionPocUserId: c.pocId, ...extra });
  rt.need(r, `fixture payment ${amount} for ${c.name}`, [200]);
  return r.json;
}
async function promiseBy(token, c, amount, extra = {}) {
  const r = await rt.createPromise(token, { customerId: c.id, amount, promisedDate: rt.day(10), collectionPocUserId: c.pocId, ...extra });
  rt.need(r, `fixture promise ${amount} for ${c.name}`, [200]);
  return r.json;
}

async function invoicesOf(token, customerId) {
  const r = await rt.list(token, '/api/invoices', { filter: [`customerId:eq:${customerId}`] });
  return r.json?.content || [];
}
async function paymentsOf(token, customerId) {
  const r = await rt.list(token, '/api/payments', { filter: [`customerId:eq:${customerId}`] });
  return r.json?.content || [];
}
async function promisesOf(token, customerId) {
  const r = await rt.list(token, '/api/promises', { filter: [`customerId:eq:${customerId}`] });
  return r.json?.content || [];
}
async function notifications(token) {
  const r = await rt.list(token, '/api/notifications');
  return r.json?.content || [];
}
const brief = (r) => r ? `${r.status} ${String(r.text || '').slice(0, 500)}` : 'no response';
const pick = (o, keys) => Object.fromEntries(keys.map((k) => [k, o ? o[k] : undefined]));

// ---- UI -------------------------------------------------------------------------------------

/** Types into a text field by focusing its DOM input (coordinates of fresh dialogs can be stale). */
async function fill(page, matcher, text, { nth = 0 } = {}) {
  const n = await rt.find(page, matcher, { role: 'textbox', nth });
  await page.locator(`[data-rt="${n.i}"]`).first().focus();
  await page.waitForTimeout(400);
  await rt.typeText(page, text, { clear: true });
  await page.waitForTimeout(600);
  return n;
}

/** Opens the app as `user` (admin uses admin123) at `route`. */
async function openAs(user, route, { width = 1440, height = 900 } = {}) {
  const password = user === 'admin' ? 'admin123' : rt.PASSWORD;
  const app = await rt.openApp({ username: user, password, width, height });
  if (route) await rt.go(app.page, route, 5000);
  return app;
}

/** Waits until a node matching `m` shows (polling the semantics tree); returns it or null. */
async function waitText(page, m, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const nodes = await rt.semantics(page);
    const hit = nodes.find((n) => (m instanceof RegExp ? m.test(n.label || '') || m.test(n.text || '') : n.label === m || n.text === m));
    if (hit) return hit;
    if (Date.now() > end) return null;
    await page.waitForTimeout(400);
  }
}

async function allText(page) { return (await rt.screenText(page)).join(' | '); }

module.exports = {
  E2E, makeResults, checks, caseRunner, buildWorld, customerIn, invoiceBy, paymentBy, promiseBy,
  invoicesOf, paymentsOf, promisesOf, notifications, brief, pick, fill, openAs, waitText, allText,
};
