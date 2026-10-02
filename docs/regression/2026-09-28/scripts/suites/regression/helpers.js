// Shared fixtures, checks and UI helpers for the regression suite (area "regression").
//
// The world is deliberately the pre-existing app's: ONE region (the seeded DEFAULT), an admin who holds
// APPROVE/ALL there, and POC users (SALES_POC, COLLECTION_POC) plus a VIEWER, each granted in DEFAULT the way the
// frontend's user form sends it. No approval thresholds are set, so nothing may be held (B2 defaults).
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const E2E = path.resolve(__dirname, '..', '..');

// ---- results --------------------------------------------------------------------------------

function makeResults(round) {
  const R = rt.results({ area: 'regression', round });
  // Screenshots: runs/r<round>/regression/shots (the orchestrator's layout).
  R.shotsDir = path.join(E2E, 'runs', `r${round}`, 'regression', 'shots');
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
    summary() {
      const bad = items.filter((i) => !i.ok);
      const good = items.filter((i) => i.ok);
      return [...bad.map((i) => `NOT OK: ${i.label}${fmt(i.detail)}`), ...good.map((i) => `ok: ${i.label}${fmt(i.detail)}`)].join('; ');
    },
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

const brief = (r) => (r ? `${r.status} ${String(r.text || '').slice(0, 300)}` : 'no response');
const ids = (r) => (r && r.json && Array.isArray(r.json.content) ? r.json.content.map((x) => x.id) : []);
const num = (v) => (v === null || v === undefined ? v : Number(v));

// ---- world ----------------------------------------------------------------------------------

/**
 * The single-region world:
 *   admin      seeded, APPROVE/ALL in DEFAULT (the only region)
 *   cashier    seeded (CASHIER), APPROVE/ALL in DEFAULT
 *   sales      SALES_POC,      MANAGE/ALL in DEFAULT  (book: invoices where he is Sales POC)
 *   sales2     SALES_POC,      MANAGE/ALL in DEFAULT
 *   coll       COLLECTION_POC, MANAGE/ALL in DEFAULT  (the seeded role holds SCOPE_OVERRIDE: sees everything)
 *   collbook   REG_COLL_BOOK (COLLECTION_POC minus SCOPE_OVERRIDE), MANAGE/ALL (book: payments/promises where Collection POC; customers with his seat)
 *   viewer     VIEWER,         READ/ALL in DEFAULT
 *   product    "Reg Widget" 100.00, product2 "Reg Gadget" 250.00
 */
async function buildWorld() {
  const admin = await rt.adminToken();
  const D = await rt.defaultRegion(admin);
  const tag = Date.now().toString(36).slice(-5);
  const w = { tag, region: D, D: D.id, tokens: { admin }, users: {} };
  w.users.admin = await rt.me(admin);
  const grant = (level) => [{ regionId: D.id, accessLevel: level, recordScope: 'ALL' }];
  // A Collection POC WITHOUT SCOPE_OVERRIDE (the seeded COLLECTION_POC role holds SCOPE_OVERRIDE, so it sees every
  // record): the book-only collector of the pre-existing POC model.
  const collRole = (await rt.roles(admin)).find((r) => r.name === 'COLLECTION_POC');
  await rt.ensureRole(admin, 'REG_COLL_BOOK', collRole.privileges.filter((p) => p !== 'SCOPE_OVERRIDE'), 'Regression: Collection POC, own book only');
  for (const [key, role, level] of [['sales', 'SALES_POC', 'MANAGE'], ['sales2', 'SALES_POC', 'MANAGE'], ['coll', 'COLLECTION_POC', 'MANAGE'],
    ['collbook', 'REG_COLL_BOOK', 'MANAGE'], ['viewer', 'VIEWER', 'READ'], ['success', 'CUSTOMER_SUCCESS_POC', 'MANAGE']]) {
    const u = await rt.createStaff(admin, role, `r${key}`, { fullName: `Reg ${key[0].toUpperCase()}${key.slice(1)} ${tag}`, grants: grant(level) });
    w.users[key] = u;
    w.tokens[key] = await rt.login(u.username, u.password);
  }
  w.tokens.cashier = await rt.cashierToken();
  w.users.cashier = await rt.me(w.tokens.cashier);
  w.product = await rt.ensureProduct(admin, 'Reg Widget', 100);
  w.product2 = await rt.ensureProduct(admin, 'Reg Gadget', 250);
  return w;
}

// ---- fixtures -------------------------------------------------------------------------------

/**
 * A fresh customer in DEFAULT with a primary Collection POC (coll by default) and optional invoices.
 * invoices: [{amount, sales, dueDate, invoiceDate, paymentTerm}] — sales = user id of the Sales POC (default sales).
 */
async function customer(w, { prefix = 'Cust', collection, paymentTerm = 'NET_30', invoices = [], regionId } = {}) {
  const t = w.tokens.admin;
  const c = await rt.createCustomer(t, { name: `${prefix} ${rt.uniq(w.tag)}`, regionId: regionId || w.D, paymentTerm });
  const coll = collection === undefined ? w.users.coll.id : collection;
  if (coll) await rt.addCustomerPoc(t, c.id, 'COLLECTION', coll, true);
  c.collectionPocUserId = coll;
  c.invoices = [];
  for (const spec of invoices) c.invoices.push(await invoice(w, c, spec));
  return c;
}

async function invoice(w, c, { amount = 1000, sales, dueDate, invoiceDate, paymentTerm, items, notes } = {}) {
  const r = await rt.createInvoice(w.tokens.admin, {
    customerId: c.id, amount, items, dueDate, invoiceDate, paymentTerm, notes,
    productId: w.product.id, salesPocUserId: sales || w.users.sales.id,
  });
  rt.need(r, `fixture invoice for ${c.name}`, [200]);
  return r.json;
}

async function payment(w, c, { amount, invoiceIds, token, coll } = {}) {
  const r = await rt.createPayment(token || w.tokens.admin, { customerId: c.id, amount, invoiceIds, collectionPocUserId: coll || c.collectionPocUserId || w.users.coll.id });
  rt.need(r, `fixture payment for ${c.name}`, [200]);
  return r.json;
}

const getJ = async (token, p, query) => rt.api('GET', p, { token, query });

/** A minimal valid PDF and PNG, sniffed by content (ContentSniffer). */
const PDF = Buffer.from('%PDF-1.4\n1 0 obj<< /Type /Catalog >>endobj\ntrailer<< /Root 1 0 R >>\n%%EOF\n');
const PNG = Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a4b50000000049454e44ae426082', 'hex');

async function upload(token, { entityType, entityId, visibility, description, buf = PDF, filename = 'doc.pdf', type = 'application/pdf', noFile = false }) {
  const fd = new FormData();
  if (!noFile) fd.append('file', new Blob([buf], { type }), filename);
  fd.append('entityType', entityType);
  fd.append('entityId', String(entityId));
  if (visibility) fd.append('visibility', visibility);
  if (description) fd.append('description', description);
  return rt.api('POST', '/api/documents', { token, body: fd, raw: true });
}

/** Downloads a document's bytes (raw fetch: lib.api decodes as text). */
async function download(token, id) {
  const res = await fetch(`${rt.API}/api/documents/${id}/download`, { headers: { Authorization: `Bearer ${token}` } });
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, buf, headers: Object.fromEntries(res.headers) };
}

// ---- UI -------------------------------------------------------------------------------------

/** Opens the app as `user` ({username,password} or 'admin') at `route`. */
async function openAs(user, route, { width = 1440, height = 900 } = {}) {
  const creds = user === 'admin' ? { username: 'admin', password: 'admin123' } : user;
  const app = await rt.openApp({ username: creds.username, password: creds.password || rt.PASSWORD, width, height });
  if (route) await rt.go(app.page, route, 5000);
  return app;
}

/** Waits until a node matching `m` shows (polling the semantics tree); returns it or null. */
async function waitText(page, m, timeoutMs = 8000, { role } = {}) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const nodes = await rt.semantics(page);
    const hit = nodes.find((n) => (!role || n.role === role) && (m instanceof RegExp ? m.test(n.label || '') || m.test(n.text || '') : n.label === m || n.text === m));
    if (hit) return hit;
    if (Date.now() > end) return null;
    await page.waitForTimeout(400);
  }
}

async function allText(page) { return (await rt.screenText(page)).join(' | '); }

/** Types into a text field by focusing its DOM input. */
async function fill(page, matcher, text, { nth = 0 } = {}) {
  const n = await rt.find(page, matcher, { role: 'textbox', nth });
  await page.locator(`[data-rt="${n.i}"]`).first().focus();
  await page.waitForTimeout(300);
  await rt.typeText(page, text, { clear: true });
  await page.waitForTimeout(500);
  return n;
}

/** Waits for a request matching `pred` in app.requests (after `from`). */
async function waitReq(app, pred, timeoutMs = 8000, from = 0) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const hit = app.requests.slice(from).find(pred);
    if (hit) return hit;
    if (Date.now() > end) return null;
    await app.page.waitForTimeout(300);
  }
}

module.exports = {
  E2E, makeResults, checks, caseRunner, done, brief, ids, num, buildWorld, customer, invoice, payment, getJ,
  PDF, PNG, upload, download, openAs, waitText, allText, fill, waitReq,
};

/**
 * Picks an item in a SearchPickerField: taps the field (fieldMatcher), in the "Choose …" dialog optionally types
 * `query`, waits for the list to settle, clicks the item with the real mouse, and verifies the field now shows it.
 * Returns true when the field shows the picked item. Retries the whole pick once.
 */
async function pick(page, fieldMatcher, itemMatcher, query) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (!(await waitText(page, /^Choose /, 300))) await rt.tap(page, fieldMatcher, { wait: 2000 });
    // Always narrow by the query: an item listed below the dialog's 300px list is clipped, and a click there
    // lands on the barrier and closes the picker.
    if (query && attempt === 0) {
      await rt.typeText(page, query);
      await page.waitForTimeout(2000);
      await rt.enableSemantics(page);
    }
    // role button: the search box itself (a textbox) holds the typed query and would match too.
    const hit = await waitText(page, itemMatcher, 5000, { role: 'button' });
    if (!hit) throw new Error(`picker item ${itemMatcher} not listed`);
    await page.waitForTimeout(500);
    const fresh = await rt.find(page, itemMatcher, { role: 'button' });
    await rt.clickAt(page, fresh.x, fresh.y, 1500);
    if (!(await waitText(page, /^Choose /, 500))) {
      const field = (await rt.semantics(page)).find((n) => (fieldMatcher instanceof RegExp ? fieldMatcher.test(n.label || '') || fieldMatcher.test(n.text || '') : n.text === fieldMatcher));
      const label = `${field && field.label || ''}\n${field && field.text || ''}`;
      if (field && !/Select…/.test(label)) return true;
    }
  }
  return false;
}
module.exports.pick = pick;

/** Row-selection checkboxes of a data table (unlabelled checkbox nodes at the left edge), top to bottom. [0] = header "select page". */
async function rowBoxes(page) {
  return page.$$eval('flt-semantics[role=checkbox]', (els) => els.map((e, i) => {
    e.setAttribute('data-rb', String(i));
    const r = e.getBoundingClientRect();
    return { i, label: e.getAttribute('aria-label'), checked: e.getAttribute('aria-checked'), x: r.x + r.width / 2, y: r.y + r.height / 2, h: r.height };
  }).filter((b) => !b.label && b.x < 160 && b.h > 0).sort((a, b) => a.y - b.y));
}
/** Toggles the n-th row box (0 = header). */
async function toggleRow(page, n) {
  const boxes = await rowBoxes(page);
  if (!boxes[n]) throw new Error(`row box ${n} not found (${boxes.length})`);
  await page.locator(`[data-rb="${boxes[n].i}"]`).first().dispatchEvent('click');
  await page.waitForTimeout(900);
  await rt.enableSemantics(page);
}
module.exports.rowBoxes = rowBoxes;
module.exports.toggleRow = toggleRow;
