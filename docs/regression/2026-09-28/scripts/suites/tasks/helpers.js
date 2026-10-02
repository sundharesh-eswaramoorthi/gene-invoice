// Shared fixtures, checks and UI helpers for the tasks (A6) suite.
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const E2E = path.resolve(__dirname, '..', '..');

// ---- results --------------------------------------------------------------------------------

function makeResults(round) {
  const R = rt.results({ area: 'tasks', round });
  // Screenshots go to runs/r<round>/tasks/shots (the orchestrator's layout).
  R.shotsDir = path.join(E2E, 'runs', `r${round}`, 'tasks', 'shots');
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
 * Runs one case. `fn(attempt)` returns {ok, actual, evidence, screenshot, codeRef, severity} and builds its own
 * fixtures so a second attempt starts clean. A FAIL is re-run once (README: every FAIL reproduced twice); one that
 * does not reproduce becomes NOT_TESTED. An exception is BLOCKED (fixture/harness problem, not a verdict).
 */
function caseRunner(R, only) {
  return async function tcase(meta, fn, { retry = true } = {}) {
    if (only && !only.test(meta.id)) return null;
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
          actual: `Attempt 1 FAILED: ${res.actual} || Attempt 2 PASSED - not reproduced, so not counted. ${res2.actual}` });
      }
      res = { ...res, ...res2, actual: `Attempt 1: ${res.actual} || Attempt 2 (reproduced): ${res2.actual}`,
        evidence: [res.evidence, res2.evidence].filter(Boolean).join(' || '),
        screenshot: res2.screenshot || res.screenshot };
    }
    return R.rec({ ...meta, ...res, ok: res.ok });
  };
}

/** Wraps a checks() object as a case result. */
const done = (c, extra = {}) => ({ ok: c.ok, actual: c.summary(), ...extra });

// ---- world ----------------------------------------------------------------------------------

const NO_TASK_PRIVS = rt.REGIONAL_STAFF_PRIVILEGES.filter((p) => p !== 'TASK_VIEW' && p !== 'TASK_MANAGE');
const VIEW_ONLY_TASK_PRIVS = rt.REGIONAL_STAFF_PRIVILEGES.filter((p) => p !== 'TASK_MANAGE');

/**
 * The standard world plus the task cast:
 *   nina  E2E_NOTASK (every regional privilege but TASK_*), MANAGE/ALL North  - sees records, never tasks
 *   tara  E2E_TASKVIEW (every regional privilege but TASK_MANAGE), MANAGE/ALL South - may work tasks, not manage them
 * Seats added: s2 gets nora as a second (non-primary) Collection POC; n2 gets nina as a second Collection POC.
 */
async function buildWorld() {
  const admin = await rt.adminToken();
  const w = await rt.seedWorld(admin);
  const R = w.regions;
  await rt.ensureRole(admin, 'E2E_NOTASK', NO_TASK_PRIVS, 'E2E: no task privileges');
  await rt.ensureRole(admin, 'E2E_TASKVIEW', VIEW_ONLY_TASK_PRIVS, 'E2E: tasks view only');
  const extra = [
    ['nina', 'E2E_NOTASK', 'Nina Notask', [{ regionId: R.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]],
    ['tara', 'E2E_TASKVIEW', 'Tara Taskview', [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]],
  ];
  for (const [username, role, fullName, grants] of extra) {
    let u = await rt.findUser(admin, username);
    if (!u) u = await rt.createStaff(admin, role, username, { username, fullName, grants });
    else {
      rt.need(await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { roleId: await rt.roleId(admin, role), password: rt.PASSWORD, active: true } }), `role ${username}`);
      rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
    }
    w.users[username] = { ...u, password: rt.PASSWORD, grants };
    w.tokens[username] = await rt.login(username);
  }
  w.users.admin = await rt.me(admin);
  // Several people holding one role: s2 (South) Collection POC = sam (primary) + nora; n2 (North) = nora (primary) + nina.
  await rt.addCustomerPoc(admin, w.customers.s2.id, 'COLLECTION', w.users.nora.id, false);
  await rt.addCustomerPoc(admin, w.customers.n2.id, 'COLLECTION', w.users.nina.id, false);
  return w;
}

// ---- fixtures -------------------------------------------------------------------------------

const POC_OF = (w, region) => ({ SOUTH: w.users.sam.id, NORTH: w.users.nora.id, WEST: w.users.wendy.id, DEFAULT: w.users.admin.id }[region]);

/** A fresh customer in `region` with a primary Collection POC, plus (optionally) an invoice and a payment. */
async function customerIn(w, region = 'NORTH', { prefix = 'tsk', invoice = true, payment = true, poc } = {}) {
  const pocId = poc || POC_OF(w, region);
  const c = await rt.createCustomer(w.tokens.admin, { name: `${region[0]}${region.slice(1).toLowerCase()} ${rt.uniq(prefix)}`, regionId: w.regions[region].id, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(w.tokens.admin, c.id, 'COLLECTION', pocId, true);
  c.pocId = pocId;
  c.region = region;
  if (invoice) {
    const r = await rt.createInvoice(w.tokens.admin, { customerId: c.id, amount: 800, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: pocId });
    rt.need(r, 'fixture invoice', [200]);
    c.invoice = r.json;
  }
  if (payment && invoice) {
    const p = await rt.createPayment(w.tokens.admin, { customerId: c.id, amount: 100, invoiceIds: [c.invoice.id], collectionPocUserId: pocId });
    rt.need(p, 'fixture payment', [200]);
    c.payment = p.json;
  }
  return c;
}

/** Creates a task (fixture; throws unless 200). */
async function task(token, o) {
  const r = await rt.createTask(token, o);
  rt.need(r, `fixture task ${o.entityType} ${o.entityId}`);
  return r.json;
}
const getTask = (token, id, query) => rt.api('GET', `/api/tasks/${id}`, { token, query });

async function notifications(token, query = {}) {
  const r = await rt.list(token, '/api/notifications', query);
  return r.json?.content || [];
}
const notesFor = async (token, taskId, type = 'TASK_ASSIGNED') => (await notifications(token)).filter((n) => n.type === type && n.link === `/tasks/${taskId}`);

const brief = (r) => r ? `${r.status} ${String(r.text || '').slice(0, 400)}` : 'no response';
const ids = (r) => (r && r.json && Array.isArray(r.json.content) ? r.json.content.map((x) => x.id) : []);

// ---- UI -------------------------------------------------------------------------------------

/** Types into a text field by focusing its DOM input. */
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
  E2E, makeResults, checks, caseRunner, done, buildWorld, customerIn, task, getTask, notifications, notesFor,
  brief, ids, fill, openAs, waitText, allText, POC_OF,
};
