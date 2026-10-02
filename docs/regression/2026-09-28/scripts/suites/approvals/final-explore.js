// FINAL exploratory pass for B2 maker-checker (area "approvals"), written independently of the existing suite,
// straight from the PRD (B2, with B1/B3/A5 crossings) and DESIGN §5.11 / §6.6 / §6.11 / §6.17 / §7.6 / §7.7.1.
//
//   source <e2e>/runtime/approvals/env.sh && node final-explore.js
// Needs a FRESH environment. Results: runs/final/approvals-explore.json; shots: runs/final/approvals/shots/explore/.
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const E2E = path.resolve(__dirname, '..', '..');
const OUT = path.join(E2E, 'runs', 'final', 'approvals-explore.json');
const SHOTS = path.join(E2E, 'runs', 'final', 'approvals', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });

const cases = [];
function rec(c) {
  const row = {
    id: c.id, feature: c.feature || '', kind: c.kind || 'API', prd: c.prd || 'B2', title: c.title || '',
    status: c.status, severity: c.status === 'FAIL' ? (c.severity || 'medium') : '',
    steps: c.steps || '', expected: c.expected || '', actual: typeof c.actual === 'string' ? c.actual : JSON.stringify(c.actual),
    evidence: c.evidence || '', screenshot: c.screenshot || '', codeRef: c.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === row.id);
  if (i >= 0) cases[i] = row; else cases.push(row);
  console.log(`[${row.status}] ${row.id} ${row.title}`);
  save();
}
function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'approvals-explore', round: 'final', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
}

function checks() {
  const items = [];
  const fmt = (d) => (d === undefined ? '' : ` [${String(typeof d === 'string' ? d : JSON.stringify(d)).slice(0, 350)}]`);
  return {
    that(label, cond, detail) { items.push({ label, ok: !!cond, detail }); return !!cond; },
    get ok() { return items.length > 0 && items.every((i) => i.ok); },
    summary() { return items.map((i) => `${i.ok ? 'ok' : 'NOT OK'}: ${i.label}${fmt(i.detail)}`).join('; '); },
  };
}

// Runs a case; a FAIL is re-run once from fresh fixtures; a FAIL that does not reproduce is NOT_TESTED; a throw is BLOCKED.
async function tcase(meta, fn) {
  let r1;
  try { r1 = await fn(1); } catch (e) {
    return rec({ ...meta, status: 'BLOCKED', actual: `Blocked (fixture/harness): ${String(e && e.stack || e).slice(0, 1500)}` });
  }
  if (r1.ok) return rec({ ...meta, ...r1, status: 'PASS' });
  let r2;
  try { r2 = await fn(2); } catch (e) { r2 = { ok: false, actual: `retry threw: ${String(e).slice(0, 600)}` }; }
  if (r2.ok) {
    return rec({ ...meta, ...r1, status: 'NOT_TESTED', actual: `Attempt 1 FAILED: ${r1.actual} || Attempt 2 PASSED (not reproduced): ${r2.actual}` });
  }
  return rec({ ...meta, ...r1, ...r2, status: 'FAIL', actual: `Attempt 1: ${r1.actual} || Attempt 2 (reproduced): ${r2.actual}`,
    evidence: [r1.evidence, r2.evidence].filter(Boolean).join(' || '), screenshot: r2.screenshot || r1.screenshot || '' });
}

const brief = (r) => (r ? `${r.status} ${String(r.text || '').slice(0, 400)}` : 'no response');

// ---- world ----------------------------------------------------------------------------------------------------

let W;
async function world() {
  const admin = await rt.adminToken();
  const w = await rt.seedWorld(admin);
  const R = w.regions;
  await rt.ensureRole(admin, 'X_NOAPPROVE', rt.REGIONAL_STAFF_PRIVILEGES.filter((p) => p !== 'CHANGE_APPROVE'), 'explore: no CHANGE_APPROVE');
  const cast = [
    ['xmia', 'E2E_REGIONAL', 'Xmia Maker', [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE' }]],
    ['xkim', 'E2E_REGIONAL', 'Xkim Maker', [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE' }]],
    ['xmo', 'E2E_REGIONAL', 'Xmo Mover', [{ regionId: R.SOUTH.id, accessLevel: 'MANAGE' }, { regionId: R.NORTH.id, accessLevel: 'MANAGE' }]],
    ['xpat', 'X_NOAPPROVE', 'Xpat Grantonly', [{ regionId: R.SOUTH.id, accessLevel: 'APPROVE' }]],
    ['xnate', 'E2E_REGIONAL', 'Xnate North', [{ regionId: R.NORTH.id, accessLevel: 'APPROVE' }, { regionId: R.SOUTH.id, accessLevel: 'READ' }]],
    ['xvic', 'E2E_REGIONAL', 'Xvic Approver', [{ regionId: R.SOUTH.id, accessLevel: 'APPROVE' }]],
  ];
  for (const [username, role, fullName, grants] of cast) {
    let u = await rt.findUser(admin, username);
    if (!u) u = await rt.createStaff(admin, role, username, { username, fullName, grants });
    w.users[username] = u;
    w.tokens[username] = await rt.login(username);
  }
  w.users.admin = await rt.me(admin);
  return w;
}
const POC = (w, region) => ({ SOUTH: w.users.sam.id, NORTH: w.users.nora.id, WEST: w.users.wendy.id, DEFAULT: w.users.admin.id }[region]);
async function cust(region = 'SOUTH', tag = 'x') {
  const c = await rt.createCustomer(W.tokens.admin, { name: `${region} ${rt.uniq(tag)}`, regionId: W.regions[region].id, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(W.tokens.admin, c.id, 'COLLECTION', POC(W, region), true);
  c.poc = POC(W, region);
  return c;
}
async function inv(token, c, amount, extra = {}) {
  return rt.createInvoice(token, { customerId: c.id, amount, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: c.poc, ...extra });
}
async function invApplied(token, c, amount, extra) {
  const r = await inv(token, c, amount, extra);
  rt.need(r, `fixture invoice ${amount}`, [200]);
  return r.json;
}
async function pay(token, c, amount, extra = {}) {
  return rt.createPayment(token, { customerId: c.id, amount, collectionPocUserId: c.poc, ...extra });
}
/** A held South payment of 6000 made by `maker` on a fresh customer. */
async function heldPayment(maker = 'xmia', amount = 6000) {
  const c = await cust('SOUTH', 'hp');
  const r = await pay(W.tokens[maker], c, amount);
  if (!r.held) throw new Error(`fixture: expected a held payment, got ${brief(r)}`);
  return { c, r, pc: r.pendingChange };
}
const pcGet = (tok, id, scope) => rt.approvals.get(tok, id, scope);
const status = async (id) => (await pcGet(W.tokens.admin, id)).json?.status;
const invNo = (n) => Number(String(n || '').split('-').pop());

// ---- UI helpers ------------------------------------------------------------------------------------------------

async function openAs(user, route) {
  const password = user === 'admin' ? 'admin123' : rt.PASSWORD;
  const app = await rt.openApp({ username: user, password });
  if (route) await rt.go(app.page, route, 5000);
  return app;
}
async function waitText(page, m, timeoutMs = 10000) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    const t = await rt.screenText(page);
    const hit = t.find((s) => (m instanceof RegExp ? m.test(s) : s === m));
    if (hit) return hit;
    if (Date.now() > end) return null;
    await page.waitForTimeout(500);
  }
}
const allText = async (page) => (await rt.screenText(page)).join(' | ');
async function buttons(page, re) {
  return (await rt.semantics(page)).filter((n) => n.role === 'button' && re.test(`${n.label || ''} ${n.text || ''}`));
}
async function focusFill(page, matcher, text) {
  const n = await rt.find(page, matcher, { role: 'textbox' });
  await page.locator(`[data-rt="${n.i}"]`).first().focus();
  await page.waitForTimeout(400);
  await rt.typeText(page, text, { clear: true });
  await page.waitForTimeout(500);
}

// ---- cases -----------------------------------------------------------------------------------------------------

async function apiCases() {
  const T = W.tokens;
  const S = W.regions.SOUTH;

  await tcase({ id: 'APP-X-01', feature: 'Threshold boundary', kind: 'API', prd: 'B2',
    title: 'A payment exactly equal to the South PAYMENT_RECORD limit (5000.00) applies; 5000.01 is held',
    steps: 'fresh South customer c; xmia (MANAGE South) POST /api/payments amount 5000.00; fresh customer c2; xmia POST amount 5000.01',
    expected: 'DESIGN §5.11.1 "Above" means amount > threshold: 5000.00 -> 200 applied; 5000.01 -> 202 PENDING_APPROVAL (threshold 5000)' },
  async () => {
    const k = checks();
    const c = await cust(); const c2 = await cust();
    const a = await pay(T.xmia, c, 5000);
    k.that('5000.00 -> 200 applied', a.status === 200 && !a.held, brief(a));
    const b = await pay(T.xmia, c2, 5000.01);
    k.that('5000.01 -> 202 held', b.status === 202 && b.held, brief(b));
    k.that('held threshold is 5000', b.pendingChange && Number(b.pendingChange.threshold) === 5000, b.pendingChange);
    return { ok: k.ok, actual: k.summary(), evidence: `payment ${a.json?.id}; pending #${b.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-02', feature: 'Cumulative window boundary', kind: 'API', prd: 'B2',
    title: 'Split payments that sum to exactly the limit apply; one more cent to the same customer is held (other customer not)',
    steps: 'fresh South c; xmia pays 4000 then 1000 (sum = 5000); then 0.01 to c; then 0.01 to a fresh customer c2',
    expected: '§5.11.3 cumulative window: 4000 and 1000 -> 200 (5000 is not above 5000); 0.01 -> 202 with summary "…with your other changes to <c> in the last 24 hours this comes to …5,000.01"; 0.01 on c2 -> 200' },
  async () => {
    const k = checks();
    const c = await cust(); const c2 = await cust();
    const a = await pay(T.xmia, c, 4000); const b = await pay(T.xmia, c, 1000);
    k.that('4000 applied', a.status === 200, brief(a));
    k.that('1000 applied (sum exactly 5000)', b.status === 200, brief(b));
    const d = await pay(T.xmia, c, 0.01);
    k.that('0.01 on same customer held', d.held, brief(d));
    k.that('summary names the window total', d.pendingChange && /last 24 hours this comes to/.test(d.pendingChange.summary || '') && /5,000\.01/.test(d.pendingChange.summary || ''), d.pendingChange?.summary);
    const e = await pay(T.xmia, c2, 0.01);
    k.that('0.01 on another customer applied', e.status === 200, brief(e));
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${d.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-03', feature: 'Approval right = role AND grant', kind: 'API', prd: 'B2',
    title: 'A user with an APPROVE grant in South but whose role lacks CHANGE_APPROVE cannot approve and is not counted as an approver',
    steps: 'xmia holds a 6000 South payment; read pendingChange.approverCount; xpat (role X_NOAPPROVE, grant APPROVE/South) GET /api/approvals/{id}, POST approve',
    expected: '§4.2 effective = role ∋ p ∧ grant: approverCount = 3 (admin, sam, xvic; the maker and xpat excluded); xpat detail canApprove=false; approve -> 403; change stays PENDING' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    k.that('approverCount = 3', pc.approverCount === 3, { approverCount: pc.approverCount });
    const d = await pcGet(T.xpat, pc.id);
    k.that('xpat can read it (200)', d.status === 200, brief(d));
    k.that('xpat canApprove false', d.json && d.json.canApprove === false, d.json && { canApprove: d.json.canApprove, blockedReason: d.json.blockedReason });
    const ap = await rt.approvals.approve(T.xpat, pc.id);
    k.that('xpat approve -> 403', ap.status === 403, brief(ap));
    k.that('still PENDING', (await status(pc.id)) === 'PENDING');
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pc.id}` };
  });

  await tcase({ id: 'APP-X-04', feature: 'Approver from another region', kind: 'API', prd: 'B2',
    title: 'An approver of North who can read South sees a South change but cannot approve or reject it',
    steps: 'xmia holds a 6000 South payment; xnate (APPROVE North, READ South) GET detail, POST approve, POST reject {note}',
    expected: 'B2 "approval from someone else holding the approval right in that record\'s region"; §6.11: detail 200 with canApprove=false, blockedReason "You don\'t hold the approval right in South"; approve 403; reject 403; still PENDING' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const d = await pcGet(T.xnate, pc.id);
    k.that('detail 200', d.status === 200, brief(d));
    k.that('canApprove/canReject false', d.json && d.json.canApprove === false && d.json.canReject === false, d.json && { a: d.json.canApprove, r: d.json.canReject });
    k.that('blockedReason names South', d.json && d.json.blockedReason === "You don't hold the approval right in South", d.json?.blockedReason);
    const ap = await rt.approvals.approve(T.xnate, pc.id);
    k.that('approve 403', ap.status === 403, brief(ap));
    const rj = await rt.approvals.reject(T.xnate, pc.id, 'no');
    k.that('reject 403', rj.status === 403, brief(rj));
    k.that('still PENDING', (await status(pc.id)) === 'PENDING');
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pc.id}` };
  });

  await tcase({ id: 'APP-X-05', feature: 'Invisible to a region outsider', kind: 'API', prd: 'B2',
    title: 'Someone with no grant in South cannot see, list, approve or withdraw a South change',
    steps: 'xmia holds a 6000 South payment; wendy (MANAGE West only) GET /api/approvals/{id}, GET /api/approvals?view=ALL, GET summary, POST approve, POST withdraw',
    expected: 'B2 visible to everyone who can see the record (only them); D15: GET 404; not in her list; summary pendingCount excludes it; approve/withdraw refused (403/404), change still PENDING' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const d = await pcGet(T.wendy, pc.id);
    k.that('GET 404', d.status === 404, brief(d));
    const l = await rt.list(T.wendy, '/api/approvals', { view: 'ALL' });
    k.that('not in list', l.ok && !(l.json.content || []).some((x) => x.id === pc.id), brief(l).slice(0, 200));
    const ap = await rt.approvals.approve(T.wendy, pc.id);
    k.that('approve refused 403/404', [403, 404].includes(ap.status), brief(ap));
    const wd = await rt.approvals.withdraw(T.wendy, pc.id);
    k.that('withdraw refused 403/404', [403, 404].includes(wd.status), brief(wd));
    k.that('still PENDING', (await status(pc.id)) === 'PENDING');
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pc.id}` };
  });

  await tcase({ id: 'APP-X-06', feature: 'Decision rules and note boundaries', kind: 'API', prd: 'B2',
    title: 'Self-approve/self-reject refused with the documented messages; others cannot withdraw; reject note 1..500 chars enforced',
    steps: 'xmia (no approval right) holds a payment and approves it; xvic (APPROVE South) holds a 6000 South payment; xvic approve; xvic reject; sam withdraw; sam reject with note "   "; sam reject with 501 chars; sam reject with exactly 500 chars; sam approve afterwards',
    expected: '§5.11.6/§6.11: maker without CHANGE_APPROVE anywhere 403 (guard); approver self-approve 400 "You cannot approve a change you requested"; self-reject 400 "Use Withdraw for your own request"; sam withdraw 403; blank note 400 fieldErrors.note; 501 chars 400 fieldErrors.note; 500 chars 200 REJECTED with that note; approve after 409 "Already rejected by …"' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xvic');
    const x = await heldPayment('xmia');
    const a0 = await rt.approvals.approve(T.xmia, x.pc.id);
    k.that('maker with no approval right anywhere: approve 403 (controller guard)', a0.status === 403, brief(a0));
    await rt.approvals.withdraw(T.xmia, x.pc.id);
    const a = await rt.approvals.approve(T.xvic, pc.id);
    k.that('approver self approve 400 + message', a.status === 400 && /You cannot approve a change you requested/.test(a.text), brief(a));
    const b = await rt.approvals.reject(T.xvic, pc.id, 'mine');
    k.that('approver self reject 400 Use Withdraw', b.status === 400 && /Use Withdraw for your own request/.test(b.text), brief(b));
    const c = await rt.approvals.withdraw(T.sam, pc.id);
    k.that('other withdraw 403', c.status === 403, brief(c));
    const d = await rt.approvals.reject(T.sam, pc.id, '   ');
    k.that('blank note 400 fieldErrors.note', d.status === 400 && d.json && d.json.fieldErrors && d.json.fieldErrors.note, brief(d));
    const e = await rt.approvals.reject(T.sam, pc.id, 'x'.repeat(501));
    k.that('501 chars 400 fieldErrors.note', e.status === 400 && e.json && e.json.fieldErrors && e.json.fieldErrors.note, brief(e));
    const note = 'n'.repeat(500);
    const f = await rt.approvals.reject(T.sam, pc.id, note);
    k.that('500 chars 200 REJECTED', f.status === 200 && f.json.status === 'REJECTED' && f.json.decisionNote === note, brief(f).slice(0, 150));
    const g = await rt.approvals.approve(T.sam, pc.id);
    k.that('approve after reject 409 Already rejected', g.status === 409 && /Already rejected/.test(g.text), brief(g));
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pc.id}` };
  });

  await tcase({ id: 'APP-X-07', feature: 'Held create: no effect, visible, then applied', kind: 'API', prd: 'B2',
    title: 'A held invoice create has not taken effect, is visible to a South reader via for-record CUSTOMER, and applies on approval',
    steps: 'fresh South c; xmia POST /api/invoices total 12000; check 202 body/Location; list invoices of c; nora (READ South) GET for-record CUSTOMER c; wendy same; sam approve; GET invoice',
    expected: '§6.6: 202, Location /api/approvals/{id}, no top-level id, entityId null; c has 0 invoices; nora sees the pending create; wendy 404; approve -> APPROVED result.link /invoices/{id}; invoice total 12000 exists' },
  async () => {
    const k = checks();
    const c = await cust();
    const r = await inv(T.xmia, c, 12000);
    k.that('202 held', r.held, brief(r));
    k.that('Location header', /\/api\/approvals\/\d+$/.test(r.headers.location || ''), r.headers.location);
    k.that('no top-level id; entityId null', r.json && r.json.id === undefined && r.pendingChange && r.pendingChange.entityId == null, r.json && Object.keys(r.json));
    const l = await rt.list(T.admin, '/api/invoices', { filter: [`customerId:eq:${c.id}`] });
    k.that('no invoice created yet', l.ok && l.json.totalElements === 0, l.json?.totalElements);
    const fr = await rt.approvals.forRecord(T.nora, 'CUSTOMER', c.id);
    k.that('nora for-record CUSTOMER lists it', fr.status === 200 && (fr.json || []).some((x) => x.id === r.pendingChange.id), brief(fr).slice(0, 200));
    const fw = await rt.approvals.forRecord(T.wendy, 'CUSTOMER', c.id);
    k.that('wendy for-record 404', fw.status === 404, brief(fw));
    const ap = await rt.approvals.approve(T.sam, r.pendingChange.id);
    k.that('approved', ap.status === 200 && ap.json.status === 'APPROVED', brief(ap).slice(0, 300));
    const link = ap.json?.result?.link;
    k.that('result link /invoices/{id}', /^\/invoices\/\d+$/.test(link || ''), ap.json?.result);
    const invId = link ? Number(link.split('/').pop()) : null;
    const g = invId ? await rt.api('GET', `/api/invoices/${invId}`, { token: T.admin }) : null;
    k.that('invoice total 12000', g && g.status === 200 && Number(g.json.total) === 12000, g && brief(g).slice(0, 200));
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${r.pendingChange?.id}, invoice ${invId}` };
  });

  await tcase({ id: 'APP-X-08', feature: 'Invoice numbers', kind: 'API', prd: 'B2',
    title: 'A held invoice create consumes no invoice number (no gap); the approved one takes the next number at approval',
    steps: 'admin creates applied invoice A (North, 100); xmia creates held 12000 in South; admin creates applied B (North); sam approves the held one -> C',
    expected: '§5.11.1 refactor 1 / §5.11.6 "Rejected requests leave no gaps": seq(B) = seq(A)+1; seq(C) = seq(B)+1' },
  async () => {
    const k = checks();
    const n1 = await cust('NORTH'); const s1 = await cust('SOUTH');
    const A = await invApplied(T.admin, n1, 100);
    const H = await inv(T.xmia, s1, 12000);
    k.that('held', H.held, brief(H));
    const B = await invApplied(T.admin, n1, 100);
    k.that('B = A+1', invNo(B.invoiceNumber) === invNo(A.invoiceNumber) + 1, { A: A.invoiceNumber, B: B.invoiceNumber });
    const ap = await rt.approvals.approve(T.sam, H.pendingChange.id);
    const cid = Number((ap.json?.result?.link || '').split('/').pop());
    const C = cid ? (await rt.api('GET', `/api/invoices/${cid}`, { token: T.admin })).json : null;
    k.that('C = B+1', C && invNo(C.invoiceNumber) === invNo(B.invoiceNumber) + 1, { B: B.invoiceNumber, C: C && C.invoiceNumber });
    return { ok: k.ok, actual: k.summary(), evidence: `A ${A.invoiceNumber}, B ${B.invoiceNumber}, C ${C && C.invoiceNumber}` };
  });

  await tcase({ id: 'APP-X-09', feature: 'Which regions am I asking about', kind: 'API', prd: 'B1',
    title: 'Approvals list/summary honour regionIds: North only hides South changes; invalid ids 400; unknown ids empty; summary agrees with list',
    steps: 'xmia holds a South payment; sam GET /api/approvals?view=ALL with regionIds=NORTH, =SOUTH, =abc, =999999, regionIds= (empty); GET /summary regionIds=SOUTH vs list filter status:eq:PENDING regionIds=SOUTH',
    expected: 'B1/§6.0: NORTH -> not listed, envelope regionIds [north]; SOUTH -> listed; abc -> 400 REGION_IDS_INVALID; 999999 -> 200 empty; empty value -> not a 5xx; summary.pendingCount = PENDING totalElements' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const N = W.regions.NORTH.id;
    const ln = await rt.approvals.list(T.sam, { view: 'ALL', size: 50 }, { regionIds: [N] });
    k.that('NORTH: 200 and envelope regionIds=[N]', ln.status === 200 && JSON.stringify(ln.json.regionIds) === JSON.stringify([N]), ln.json && ln.json.regionIds);
    k.that('NORTH: South change absent', ln.ok && !(ln.json.content || []).some((x) => x.id === pc.id));
    const ls = await rt.list(T.sam, '/api/approvals', { view: 'ALL', filter: [`status:eq:PENDING`] }, { regionIds: [S.id] });
    k.that('SOUTH: present', ls.ok && (ls.json.content || []).some((x) => x.id === pc.id));
    const bad = await rt.api('GET', '/api/approvals', { token: T.sam, query: { regionIds: 'abc' } });
    k.that('abc -> 400 REGION_IDS_INVALID', bad.status === 400 && bad.json && bad.json.error === 'REGION_IDS_INVALID', brief(bad));
    const unk = await rt.api('GET', '/api/approvals', { token: T.sam, query: { regionIds: '999999', view: 'ALL' } });
    k.that('999999 -> 200 empty', unk.status === 200 && unk.json.totalElements === 0, brief(unk).slice(0, 200));
    const emp = await rt.api('GET', '/api/approvals?regionIds=&view=ALL', { token: T.sam });
    k.that('empty regionIds -> not 5xx', emp.status > 0 && emp.status < 500, brief(emp).slice(0, 200));
    const sm = await rt.approvals.summary(T.sam, { regionIds: [S.id] });
    k.that('summary.pendingCount = PENDING list total (South)', sm.ok && sm.json.pendingCount === ls.json.totalElements, { summary: sm.json, listTotal: ls.json && ls.json.totalElements });
    return { ok: k.ok, actual: k.summary(), evidence: `empty regionIds -> ${emp.status} ${String(emp.text).slice(0, 120)}` };
  });

  await tcase({ id: 'APP-X-10', feature: 'As-of guards on decisions', kind: 'API', prd: 'B3',
    title: 'Decisions are refused as of a date; asOf future/invalid/today behave per the error catalogue',
    steps: 'xmia holds a South payment; sam POST approve?asOf=<yesterday>; xmia POST withdraw?asOf=<yesterday>; sam GET /api/approvals?asOf=<tomorrow>; ?asOf=2026-13-01; ?asOf=<today>',
    expected: '§5.11.6 check 2 / §6.17: approve and withdraw 400 AS_OF_READ_ONLY and still PENDING; tomorrow 400 AS_OF_FUTURE; 2026-13-01 400 AS_OF_INVALID; today 200 with X-View-As-Of: live' },
  async () => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const ap = await rt.api('POST', `/api/approvals/${pc.id}/approve`, { token: T.sam, body: {}, query: { asOf: rt.day(-1) } });
    k.that('approve asOf -> 400 AS_OF_READ_ONLY', ap.status === 400 && ap.json && ap.json.error === 'AS_OF_READ_ONLY', brief(ap));
    const wd = await rt.api('POST', `/api/approvals/${pc.id}/withdraw`, { token: T.xmia, body: {}, query: { asOf: rt.day(-1) } });
    k.that('withdraw asOf -> 400 AS_OF_READ_ONLY', wd.status === 400 && wd.json && wd.json.error === 'AS_OF_READ_ONLY', brief(wd));
    k.that('still PENDING', (await status(pc.id)) === 'PENDING');
    const fu = await rt.api('GET', '/api/approvals', { token: T.sam, query: { asOf: rt.day(1) } });
    k.that('tomorrow -> 400 AS_OF_FUTURE', fu.status === 400 && fu.json && fu.json.error === 'AS_OF_FUTURE', brief(fu));
    const iv = await rt.api('GET', '/api/approvals', { token: T.sam, query: { asOf: '2026-13-01' } });
    k.that('2026-13-01 -> 400 AS_OF_INVALID', iv.status === 400 && iv.json && iv.json.error === 'AS_OF_INVALID', brief(iv));
    const td = await rt.api('GET', '/api/approvals', { token: T.sam, query: { asOf: rt.day(0) } });
    k.that('today -> 200, X-View-As-Of live', td.status === 200 && td.headers['x-view-as-of'] === 'live', { status: td.status, h: td.headers['x-view-as-of'] });
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pc.id}` };
  });

  await tcase({ id: 'APP-X-11', feature: 'Threshold validation', kind: 'API', prd: 'B2',
    title: 'Threshold PUT rejects bad rows with per-row fieldErrors, accepts the 720 h edge and 0 as an amount',
    steps: 'admin PUT /api/regions/{WEST}/approval-thresholds with: windowHours 721; amount -1; amount 1.001; duplicate operation; operation "BOGUS"; operation THRESHOLD_CHANGE; then {PROMISE_UPDATE, amount 700, windowHours 720}',
    expected: '§6.11: 400 fieldErrors["thresholds[i].windowHours" | ".amount" | ".operation"] for each bad row; valid 720 h row -> 200 (tightening: new row)' },
  async () => {
    const k = checks();
    const Wr = W.regions.WEST.id;
    const put = (rows) => rt.setThresholds(T.admin, Wr, rows);
    const fe = (r, key) => r.status === 400 && r.json && r.json.fieldErrors && Object.keys(r.json.fieldErrors).some((x) => x === key);
    const a = await put([{ operation: 'PROMISE_UPDATE', amount: 10, windowHours: 721 }]);
    k.that('721 h -> 400 thresholds[0].windowHours', fe(a, 'thresholds[0].windowHours'), brief(a));
    const b = await put([{ operation: 'PROMISE_UPDATE', amount: -1 }]);
    k.that('-1 -> 400 thresholds[0].amount', fe(b, 'thresholds[0].amount'), brief(b));
    const c = await put([{ operation: 'PROMISE_UPDATE', amount: 1.001 }]);
    k.that('1.001 -> 400 thresholds[0].amount', fe(c, 'thresholds[0].amount'), brief(c));
    const d = await put([{ operation: 'PROMISE_UPDATE', amount: 10 }, { operation: 'PROMISE_UPDATE', amount: 20 }]);
    k.that('duplicate -> 400 thresholds[1].operation', fe(d, 'thresholds[1].operation'), brief(d));
    const e = await put([{ operation: 'BOGUS', amount: 10 }]);
    k.that('BOGUS -> 400 thresholds[0].operation', fe(e, 'thresholds[0].operation'), brief(e));
    const f = await put([{ operation: 'THRESHOLD_CHANGE', amount: 10 }]);
    k.that('THRESHOLD_CHANGE -> 400 thresholds[0].operation', fe(f, 'thresholds[0].operation'), brief(f));
    const g = await put([{ operation: 'PROMISE_UPDATE', amount: 700, windowHours: 720 }]);
    k.that('720 h accepted (200 or already tighter)', g.status === 200, brief(g).slice(0, 200));
    const row = (g.json?.thresholds || []).find((x) => x.operation === 'PROMISE_UPDATE');
    k.that('row stored 700 / 720 h', row && Number(row.amount) === 700 && row.windowHours === 720, row);
    return { ok: k.ok, actual: k.summary() };
  });

  await tcase({ id: 'APP-X-12', feature: 'Per-region thresholds and a lone approver', kind: 'API', prd: 'B2',
    title: 'West gets its own PAYMENT_RECORD limit; the same amount is held in West but not North; loosening West with one approver warns and cannot be self-approved',
    steps: 'admin PUT WEST PAYMENT_RECORD 100 (tighten); wendy pays 150 on a West customer; sam pays 150 on a North customer; admin PUT WEST PAYMENT_RECORD 1000 (loosen); admin approve own THRESHOLD_CHANGE; GET thresholds; admin withdraw',
    expected: 'B2 "thresholds are per region": West 150 -> 202 (approverCount 1: admin); North 150 -> 200; loosening -> 202 with pendingChange (operation THRESHOLD_CHANGE) and warnings "Only 1 person can approve changes in West…"; GET still shows 100 and the pendingChange; self-approve 400; withdraw 200 WITHDRAWN' },
  async () => {
    const k = checks();
    const Wr = W.regions.WEST.id;
    const t = await rt.setThresholds(T.admin, Wr, [{ operation: 'PAYMENT_RECORD', amount: 100, windowHours: 0 }]);
    k.that('tighten 200', t.status === 200, brief(t).slice(0, 200));
    const wc = await cust('WEST'); const nc = await cust('NORTH');
    const wp = await pay(T.wendy, wc, 150);
    k.that('West 150 held', wp.held, brief(wp));
    k.that('approverCount 1', wp.pendingChange && wp.pendingChange.approverCount === 1, wp.pendingChange && wp.pendingChange.approverCount);
    const np = await pay(T.sam, nc, 150);
    k.that('North 150 applied', np.status === 200, brief(np));
    const lo = await rt.setThresholds(T.admin, Wr, [{ operation: 'PAYMENT_RECORD', amount: 1000, windowHours: 0 }]);
    k.that('loosen -> 202 THRESHOLD_CHANGE', lo.status === 202 && lo.pendingChange && lo.pendingChange.operation === 'THRESHOLD_CHANGE', brief(lo).slice(0, 300));
    k.that('warning about one approver', (lo.json?.warnings || []).some((x) => /Only 1 person can approve changes in West/.test(x)), lo.json && lo.json.warnings);
    const g = await rt.getThresholds(T.admin, Wr);
    const row = (g.json?.thresholds || []).find((x) => x.operation === 'PAYMENT_RECORD');
    k.that('limit still 100 while pending', row && Number(row.amount) === 100, row);
    k.that('GET shows pendingChange', g.json && g.json.pendingChange && g.json.pendingChange.id === lo.pendingChange?.id, g.json && g.json.pendingChange);
    const sa = lo.pendingChange ? await rt.approvals.approve(T.admin, lo.pendingChange.id) : null;
    k.that('self-approve 400', sa && sa.status === 400, brief(sa));
    const wd = lo.pendingChange ? await rt.approvals.withdraw(T.admin, lo.pendingChange.id) : null;
    k.that('withdraw 200', wd && wd.status === 200 && wd.json.status === 'WITHDRAWN', brief(wd).slice(0, 200));
    // clean up the held West payment so it does not linger
    if (wp.pendingChange) await rt.approvals.withdraw(T.wendy, wp.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), evidence: `threshold change #${lo.pendingChange?.id}; west payment #${wp.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-13', feature: 'Zero threshold', kind: 'API', prd: 'B2',
    title: 'A limit of 0 holds any positive change (cancel of a 1.00 invoice); GET shows 0.00, not "not gated"',
    steps: 'admin PUT WEST INVOICE_CANCEL amount 0; admin creates a West invoice of 1.00; wendy POST /api/invoices/{id}/cancel',
    expected: '§5.11.1 "above" = amount > threshold: 1.00 > 0 -> 202 held; invoice stays UNPAID; GET thresholds amount 0 (not null)' },
  async () => {
    const k = checks();
    const Wr = W.regions.WEST.id;
    const t = await rt.setThresholds(T.admin, Wr, [{ operation: 'INVOICE_CANCEL', amount: 0 }]);
    k.that('set 0 -> 200', t.status === 200, brief(t).slice(0, 200));
    const row = (t.json?.thresholds || []).find((x) => x.operation === 'INVOICE_CANCEL');
    k.that('row amount is 0 (not null)', row && row.amount !== null && Number(row.amount) === 0, row);
    const c = await cust('WEST');
    const i = await invApplied(T.admin, c, 1);
    const r = await rt.cancelInvoice(T.wendy, i.id);
    k.that('cancel 1.00 held', r.held, brief(r));
    const g = await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin });
    k.that('invoice still UNPAID', g.json && g.json.status === 'UNPAID', g.json && g.json.status);
    if (r.pendingChange) await rt.approvals.withdraw(T.wendy, r.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), evidence: `invoice ${i.id}; pending #${r.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-14', feature: 'One outstanding change per record', kind: 'API', prd: 'B2',
    title: 'Same maker re-asks -> supersedes; another maker on the same record -> 409 naming the open change',
    steps: 'admin creates South invoice 6000 on fresh c; xmia cancel (held #1); xmia cancel again (held #2); xkim cancel',
    expected: '§5.11.7: #2 202 with supersededId=#1, #1 SUPERSEDED with supersededById=#2; xkim 409 "… already has a change awaiting approval (#2, Cancel invoice by …)"' },
  async () => {
    const k = checks();
    const c = await cust();
    const i = await invApplied(T.admin, c, 6000);
    const a = await rt.cancelInvoice(T.xmia, i.id);
    const b = await rt.cancelInvoice(T.xmia, i.id);
    k.that('both held', a.held && b.held, `${brief(a)} | ${brief(b)}`);
    k.that('supersededId = first', b.pendingChange && a.pendingChange && b.pendingChange.supersededId === a.pendingChange.id, b.pendingChange && b.pendingChange.supersededId);
    const d1 = a.pendingChange ? await pcGet(T.admin, a.pendingChange.id) : null;
    k.that('first SUPERSEDED -> second', d1 && d1.json.status === 'SUPERSEDED' && d1.json.supersededById === b.pendingChange?.id, d1 && { s: d1.json.status, by: d1.json.supersededById });
    const x = await rt.cancelInvoice(T.xkim, i.id);
    k.that('xkim 409 names #2', x.status === 409 && new RegExp(`already has a change awaiting approval \\(#${b.pendingChange?.id}`).test(x.text), brief(x));
    if (b.pendingChange) await rt.approvals.withdraw(T.xmia, b.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), evidence: `invoice ${i.id}` };
  });

  await tcase({ id: 'APP-X-15', feature: 'Automation × maker-checker', kind: 'API', prd: 'A5',
    title: 'A rule\'s CREATE_PROMISE above South\'s PROMISE_CREATE limit is held (origin AUTOMATION); the save is not slowed; the owner cannot approve; another approver applies it',
    steps: 'sam (APPROVE South) creates rule ENTITY_CREATED INVOICE, region South, condition customerId:eq:c, action CREATE_PROMISE FIXED 6000; admin POST invoice 8000 for c; wait for the run; inspect effects and the pending change; xmia approve; sam approve',
    expected: 'A5 "a rule can never slow down or break the user\'s save": invoice 200; D11: effect DONE with result.type PENDING_CHANGE; pending origin AUTOMATION, ruleId = rule, maker = owner sam; sam approve 400 (maker = owner); xvic approve -> APPROVED, promise of 6000 exists for c' },
  async () => {
    const k = checks();
    const c = await cust();
    const body = rt.rule({ trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, regionIds: [S.id],
      condition: { op: 'AND', children: [{ filter: `customerId:eq:${c.id}` }] },
      actions: [rt.action.promise({ amount: { source: 'FIXED', value: 6000 } })] });
    const cr = await rt.automation.create(T.sam, body);
    k.that('rule created', cr.ok, brief(cr).slice(0, 300));
    if (!cr.ok) return { ok: false, actual: k.summary() };
    const ruleId = cr.json.id;
    try {
      const i = await inv(T.admin, c, 8000);
      k.that('invoice save 200', i.status === 200, brief(i));
      k.that(`invoice save fast (${i.ms} ms < 3000)`, i.ms < 3000, i.ms);
      const run = await rt.waitFor(async () => {
        const rs = await rt.automation.runs(T.sam, ruleId);
        const x = (rs.json?.content || [])[0];
        return x && !/QUEUED|RUNNING|PENDING/.test(x.status) ? x : null;
      }, { timeoutMs: 45000 });
      k.that('run finished', !!run, run);
      const eff = run ? await rt.automation.effects(T.sam, run.id) : null;
      const e0 = eff && (eff.json?.content || [])[0];
      k.that('effect result PENDING_CHANGE', e0 && e0.result && e0.result.type === 'PENDING_CHANGE', e0);
      const fr = await rt.approvals.forRecord(T.admin, 'CUSTOMER', c.id);
      const pc = (fr.json || []).find((x) => x.operation === 'PROMISE_CREATE');
      k.that('pending PROMISE_CREATE origin AUTOMATION', pc && pc.origin === 'AUTOMATION', fr.json);
      const d = pc ? (await pcGet(T.admin, pc.id)).json : null;
      k.that('detail ruleId and maker = rule owner sam', d && d.ruleId === ruleId && d.requestedBy && d.requestedBy.id === W.users.sam.id, d && { ruleId: d.ruleId, requestedBy: d.requestedBy });
      const own = pc ? await rt.approvals.approve(T.sam, pc.id) : null;
      k.that('owner approve 400', own && own.status === 400 && /You cannot approve a change you requested/.test(own.text), brief(own));
      const ok = pc ? await rt.approvals.approve(T.xvic, pc.id) : null;
      k.that('xvic approve APPROVED', ok && ok.status === 200 && ok.json.status === 'APPROVED', brief(ok).slice(0, 300));
      const ps = await rt.list(T.admin, '/api/promises', { filter: [`customerId:eq:${c.id}`] });
      k.that('promise 6000 exists', (ps.json?.content || []).some((p) => Number(p.amount) === 6000), (ps.json?.content || []).map((p) => p.amount));
      return { ok: k.ok, actual: k.summary(), evidence: `rule ${ruleId}; run ${run && run.id}; pending #${pc && pc.id}` };
    } finally {
      await rt.automation.disable(T.sam, ruleId);
    }
  });

  await tcase({ id: 'APP-X-16', feature: 'Maker deactivated before approval', kind: 'API', prd: 'B2',
    title: 'Approving a change whose maker was deactivated records FAILED with the documented reason and applies nothing',
    steps: 'fresh maker xk<n> (MANAGE South) holds a 6000 payment on fresh c; admin PUT /api/users/{id} {active:false}; sam approve; list payments of c',
    expected: '§5.11.6 isApplyFailure: 200 with status FAILED, failureReason "The requester\'s account is no longer active"; c has no payments' },
  async (attempt) => {
    const k = checks();
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'xk', { grants: [{ regionId: S.id, accessLevel: 'MANAGE' }] });
    const tokU = await rt.login(u.username);
    const c = await cust();
    const r = await pay(tokU, c, 6000);
    k.that('held', r.held, brief(r));
    const de = await rt.api('PUT', `/api/users/${u.id}`, { token: T.admin, body: { active: false } });
    k.that('deactivated', de.ok, brief(de).slice(0, 200));
    const ap = r.pendingChange ? await rt.approvals.approve(T.sam, r.pendingChange.id) : null;
    k.that('FAILED with reason', ap && ap.status === 200 && ap.json.status === 'FAILED' && ap.json.failureReason === "The requester's account is no longer active", brief(ap).slice(0, 400));
    const ps = await rt.list(T.admin, '/api/payments', { filter: [`customerId:eq:${c.id}`] });
    k.that('no payment applied', ps.ok && ps.json.totalElements === 0, ps.json && ps.json.totalElements);
    return { ok: k.ok, actual: k.summary(), evidence: `user ${u.username}; pending #${r.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-17', feature: 'Region move × pending', kind: 'API', prd: 'B2',
    title: 'A customer with a pending change cannot be moved by someone without the approval right in the source region; an approver\'s move closes it',
    steps: 'admin South invoice 6000 on fresh c; xmia cancel (held); xmo (MANAGE South+North) move c to North; sam (APPROVE South, MANAGE North) move c to North; GET the change',
    expected: '§5.11.1 "A move cannot be used to escape the gate" / §5.11.7: xmo -> 400 and c stays South, change PENDING; sam -> 200, change CLOSED with note "Customer moved from South to North"; invoice still UNPAID' },
  async () => {
    const k = checks();
    const c = await cust();
    const i = await invApplied(T.admin, c, 6000);
    const h = await rt.cancelInvoice(T.xmia, i.id);
    k.that('held', h.held, brief(h));
    const m1 = await rt.moveCustomerRegion(T.xmo, c.id, W.regions.NORTH.id);
    k.that('xmo move 400', m1.status === 400, brief(m1));
    const c1 = (await rt.api('GET', `/api/customers/${c.id}`, { token: T.admin })).json;
    k.that('still South', c1 && c1.regionId === S.id, c1 && c1.regionId);
    k.that('still PENDING', h.pendingChange && (await status(h.pendingChange.id)) === 'PENDING');
    const m2 = await rt.moveCustomerRegion(T.sam, c.id, W.regions.NORTH.id);
    k.that('sam move 200', m2.status === 200, brief(m2).slice(0, 300));
    const d = h.pendingChange ? (await pcGet(T.admin, h.pendingChange.id)).json : null;
    k.that('CLOSED with move note', d && d.status === 'CLOSED' && /moved from South to North/.test(d.decisionNote || ''), d && { s: d.status, note: d.decisionNote });
    const g = await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin });
    k.that('invoice UNPAID', g.json && g.json.status === 'UNPAID', g.json && g.json.status);
    return { ok: k.ok, actual: k.summary(), evidence: `customer ${c.id}; pending #${h.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-18', feature: 'Customer login and pending changes', kind: 'API', prd: 'B2',
    title: 'A customer login sees a pending change on its own invoice anonymously but has no approvals list',
    steps: 'staff (admin, then sam on retry) creates invoice 6000 for s1 (the "cust" login\'s customer); xmia cancels it (held); cust GET /api/approvals, GET for-record INVOICE, GET for-record CUSTOMER, GET /api/approvals/{id}, GET /api/invoices?filter=pendingApproval:eq:true',
    expected: '§5.11.9: list 403; for-record INVOICE 200 with the change, requestedBy/origin null; for-record CUSTOMER 403; the invoice list flags pendingApproval true on it' },
  async (attempt) => {
    const k = checks();
    const s1 = W.customers.s1; s1.poc = W.users.sam.id;
    const maker = attempt === 1 ? T.admin : T.sam;
    const i = await invApplied(maker, s1, 6000);
    const h = await rt.cancelInvoice(T.xmia, i.id);
    k.that('held', h.held, brief(h));
    const l = await rt.api('GET', '/api/approvals', { token: T.cust });
    k.that('list 403', l.status === 403, brief(l));
    const fr = await rt.approvals.forRecord(T.cust, 'INVOICE', i.id);
    const b = (fr.json || []).find((x) => x.id === h.pendingChange?.id);
    k.that('for-record INVOICE lists it', fr.status === 200 && !!b, brief(fr).slice(0, 300));
    k.that('requestedBy and origin null', b && b.requestedBy == null && b.origin == null, b && { requestedBy: b.requestedBy, origin: b.origin });
    const fc = await rt.approvals.forRecord(T.cust, 'CUSTOMER', s1.id);
    k.that('for-record CUSTOMER 403', fc.status === 403, brief(fc));
    const il = await rt.api('GET', '/api/invoices', { token: T.cust, query: { size: 50, filter: 'pendingApproval:eq:true' } });
    k.that('invoice list flags it', il.ok && (il.json.content || []).some((x) => x.id === i.id && x.pendingApproval === true), brief(il).slice(0, 300));
    if (h.pendingChange) await rt.approvals.withdraw(T.xmia, h.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), evidence: `invoice ${i.id}; pending #${h.pendingChange?.id}` };
  });

  await tcase({ id: 'APP-X-19', feature: 'Invoice terms measure', kind: 'API', prd: 'B2',
    title: 'INVOICE_TERMS: an earlier due date or a notes-only edit applies; a later due date on a balance above the limit is held and not in effect',
    steps: 'admin PUT SOUTH INVOICE_TERMS 1000 (tighten); admin South invoice 3000 due +20d on fresh c; xmia PATCH dueDate +10d; PATCH notes only; PATCH dueDate +40d; GET invoice',
    expected: '§5.11.1 measure = balance only when the due date moves later: earlier -> 200; notes -> 200; later -> 202; invoice dueDate still +10d' },
  async () => {
    const k = checks();
    const t = await rt.setThresholds(T.admin, S.id, [{ operation: 'INVOICE_TERMS', amount: 1000, windowHours: 24 }]);
    k.that('threshold set (200)', t.status === 200, brief(t).slice(0, 200));
    const c = await cust();
    const i = await invApplied(T.admin, c, 3000);
    let cur = (await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin })).json;
    const e = await rt.patchInvoice(T.xmia, i.id, { dueDate: rt.day(10), paymentTerm: 'CUSTOM', version: cur.version });
    k.that('earlier due date 200', e.status === 200, brief(e));
    cur = (await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin })).json;
    const n = await rt.patchInvoice(T.xmia, i.id, { notes: 'just a note', version: cur.version });
    k.that('notes only 200', n.status === 200, brief(n));
    cur = (await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin })).json;
    const l = await rt.patchInvoice(T.xmia, i.id, { dueDate: rt.day(40), paymentTerm: 'CUSTOM', version: cur.version });
    k.that('later due date 202', l.held, brief(l));
    const g = (await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin })).json;
    k.that('due date still +10d', g && g.dueDate === rt.day(10), g && g.dueDate);
    if (l.pendingChange) await rt.approvals.withdraw(T.xmia, l.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), evidence: `invoice ${i.id}` };
  });
}

// ---- UI cases (live) -------------------------------------------------------------------------------------------

async function uiCase(meta, fn) {
  return tcase({ kind: 'UI', ...meta }, async (attempt) => {
    let app = null;
    const ctx = { open: async (u, r) => { if (app) await app.close(); app = await openAs(u, r); return app; }, attempt };
    try {
      return await fn(ctx);
    } finally {
      if (app) await app.close().catch(() => {});
    }
  });
}

async function uiCases() {
  const T = W.tokens;

  await uiCase({ id: 'APP-X-UI-01', feature: 'Approvals list for an approver', prd: 'B2',
    title: 'An approver lands on "Awaiting my approval" and sees the new South change; the Awaiting me tile matches the API',
    steps: 'xmia holds a 6000 South payment; sam opens /approvals',
    expected: '§7.7.1: default segment AWAITING_ME for a changeApprove holder; the row\'s summary is on screen; tile "Awaiting me" = GET /api/approvals/summary awaitingMeCount' },
  async ({ open, attempt }) => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const sm = (await rt.approvals.summary(T.sam)).json;
    const app = await open('sam', '/approvals');
    const p = app.page;
    await waitText(p, /Awaiting my approval/);
    const segs = (await rt.semantics(p)).filter((n) => /Awaiting my approval/.test(`${n.label || ''} ${n.text || ''}`));
    const segSel = segs.some((n) => n.selected === 'true' || n.checked === 'true')
      || app.requests.some((r) => /^\/api\/approvals\?/.test(r.url) && /view=AWAITING_ME/.test(r.url));
    k.that('segment Awaiting my approval selected (flag or list fetched with view=AWAITING_ME)', segSel, app.requests.filter((r) => /^\/api\/approvals\?/.test(r.url)).map((r) => r.url));
    const row = await waitText(p, new RegExp(pc.customerName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    k.that('row for the change shown', !!row, pc.customerName);
    // Semantics nodes in order (screenText de-duplicates, which drops a count equal to a neighbouring tile's).
    const nodes = (await rt.semantics(p)).map((n) => `${n.label || ''}\n${n.text || ''}`.trim());
    const at = nodes.findIndex((x) => x.split('\n').includes('Awaiting me'));
    const own = at >= 0 ? nodes[at].split('\n').find((x) => /^\d+$/.test(x)) : undefined;
    const tileNum = at < 0 ? NaN : Number(own !== undefined ? own : nodes.slice(at + 1).find((x) => /^\d+$/.test(x)));
    k.that('tile Awaiting me = API awaitingMeCount', tileNum === sm.awaitingMeCount, { tile: tileNum, api: sm.awaitingMeCount });
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-01-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${pc.id}` };
  });

  await uiCase({ id: 'APP-X-UI-02', feature: 'Maker view and withdraw', prd: 'B2',
    title: 'The maker sees no Approve/Reject, is told "You requested this change", and can withdraw through the confirm dialog',
    steps: 'xmia holds a 6000 South payment; xmia opens /approvals/{id}; tap Withdraw; confirm Withdraw in the dialog',
    expected: '§7.7.1 buttons only per server flags; blockedReason shown; Withdraw -> dialog "Withdraw this request?" -> status WITHDRAWN (API) and the page shows it' },
  async ({ open, attempt }) => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const app = await open('xmia', `/approvals/${pc.id}`);
    const p = app.page;
    await waitText(p, /You requested this change/);
    k.that('blocked reason shown', await rt.exists(p, /You requested this change/));
    k.that('no Approve button', (await buttons(p, /^\s*Approve\s*$/)).length === 0);
    k.that('no Reject button', (await buttons(p, /^\s*Reject\s*$/)).length === 0);
    const wbs = await buttons(p, /Withdraw/);
    k.that('Withdraw button', wbs.length > 0);
    if (wbs.length) {
      await rt.tap(p, /Withdraw/, { role: 'button' });
      k.that('dialog shown', !!(await waitText(p, /Withdraw this request\?/, 4000)));
      const all = await buttons(p, /Withdraw/);
      if (all.length) await rt.clickAt(p, all[all.length - 1].x, all[all.length - 1].y, 3000);
    }
    const st = await status(pc.id);
    k.that('API WITHDRAWN', st === 'WITHDRAWN', st);
    k.that('page shows Withdrawn', !!(await waitText(p, /Withdrawn/i, 6000)));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-02-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${pc.id}` };
  });

  await uiCase({ id: 'APP-X-UI-03', feature: 'Reject dialog validation (keyboard)', prd: 'B2',
    title: 'Reject with an empty note shows the inline error and keeps the change pending; a typed note rejects it',
    steps: 'xmia holds a 6000 South payment; sam opens /approvals/{id}; Reject; press the dialog\'s Reject with no note; type a note with the keyboard; Reject',
    expected: '§7.7.1 Reject (note 1–500 required): "Say why, so the person who asked knows" and still PENDING; then REJECTED with that note' },
  async ({ open, attempt }) => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const app = await open('sam', `/approvals/${pc.id}`);
    const p = app.page;
    await waitText(p, /Reject/);
    await rt.tap(p, /^Reject$/, { role: 'button' });
    k.that('dialog', !!(await waitText(p, /Reject this change\?/, 4000)));
    let rbs = await buttons(p, /^\s*Reject\s*$/);
    await rt.clickAt(p, rbs[rbs.length - 1].x, rbs[rbs.length - 1].y, 1500);
    k.that('inline error', !!(await waitText(p, /Say why, so the person who asked knows/, 4000)));
    k.that('still PENDING', (await status(pc.id)) === 'PENDING');
    const note = `ui reject ${attempt}`;
    await focusFill(p, /Why\? \(required\)/, note);
    rbs = await buttons(p, /^\s*Reject\s*$/);
    await rt.clickAt(p, rbs[rbs.length - 1].x, rbs[rbs.length - 1].y, 3000);
    const d = (await pcGet(T.admin, pc.id)).json;
    k.that('REJECTED with note', d.status === 'REJECTED' && d.decisionNote === note, { s: d.status, note: d.decisionNote });
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-03-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${pc.id}` };
  });

  await uiCase({ id: 'APP-X-UI-04', feature: 'Approve through the UI', prd: 'B2',
    title: 'An approver approves a held invoice create through the confirm dialog; the page offers "Open <invoice>"',
    steps: 'xmia holds a 12000 South invoice create; sam opens /approvals/{id}; Approve; confirm Approve',
    expected: '§7.7.1: dialog "Approve this change?" -> APPROVED (API), invoice created, page shows "Open {entityLabel}" which navigates to result.link' },
  async ({ open, attempt }) => {
    const k = checks();
    const c = await cust();
    const h = await inv(T.xmia, c, 12000);
    if (!h.held) throw new Error(`fixture not held: ${brief(h)}`);
    const app = await open('sam', `/approvals/${h.pendingChange.id}`);
    const p = app.page;
    await waitText(p, /^Approve$/);
    await rt.tap(p, /^Approve$/, { role: 'button' });
    k.that('dialog', !!(await waitText(p, /Approve this change\?/, 4000)));
    const abs = await buttons(p, /^\s*Approve\s*$/);
    await rt.clickAt(p, abs[abs.length - 1].x, abs[abs.length - 1].y, 3500);
    const d = (await pcGet(T.admin, h.pendingChange.id)).json;
    k.that('API APPROVED', d.status === 'APPROVED', d.status);
    const openHit = await waitText(p, /^Open (New invoice for|INV-)/, 6000);
    k.that('"Open {entityLabel}" shown', !!openHit, openHit || (await allText(p)).slice(-400));
    if (openHit) {
      await rt.tap(p, openHit, { wait: 3500 });
      const hash = await p.evaluate(() => location.hash);
      k.that('Open goes to result.link', d.result && hash.includes(d.result.link), { hash, link: d.result && d.result.link });
    }
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-04-${attempt}`);  // after following the link
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${h.pendingChange.id} result ${JSON.stringify(d.result)}` };
  });

  await uiCase({ id: 'APP-X-UI-05', feature: 'Cross-region reader', prd: 'B2',
    title: 'A North approver who only reads South sees the South change read-only with the reason',
    steps: 'xmia holds a 6000 South payment; xnate opens /approvals/{id}',
    expected: '§6.11 blockedReason "You don\'t hold the approval right in South" shown; no Approve/Reject/Withdraw buttons' },
  async ({ open, attempt }) => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const app = await open('xnate', `/approvals/${pc.id}`);
    const p = app.page;
    k.that('reason shown', !!(await waitText(p, /You don't hold the approval right in South/)));
    k.that('no decision buttons', (await buttons(p, /^\s*(Approve|Reject|Withdraw)\s*$/)).length === 0);
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-05-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${pc.id}` };
  });

  await uiCase({ id: 'APP-X-UI-06', feature: 'Pending banner for a reader', prd: 'B2',
    title: 'A South reader opening the invoice sees "Awaiting approval: …" and View opens the change',
    steps: 'admin South invoice 6000 on fresh c; xmia cancel (held); nora (READ South) opens /invoices/{id}; tap View on the banner',
    expected: 'B2 "a pending change is visible to everyone who can see the record, without having taken effect"; §7.7.1 banner; invoice still shown UNPAID; View -> #/approvals/{id}' },
  async ({ open, attempt }) => {
    const k = checks();
    const c = await cust();
    const i = await invApplied(T.admin, c, 6000);
    const h = await rt.cancelInvoice(T.xmia, i.id);
    if (!h.held) throw new Error(`fixture not held: ${brief(h)}`);
    const app = await open('nora', `/invoices/${i.id}`);
    const p = app.page;
    k.that('banner', !!(await waitText(p, /^Awaiting approval: /)), await allText(p));
    const txt = await allText(p);
    k.that('still unpaid on screen', /Unpaid|UNPAID/.test(txt));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-06-${attempt}`);
    if (await rt.exists(p, 'View')) {
      await rt.tap(p, 'View', { wait: 3500 });
      const hash = await p.evaluate(() => location.hash);
      k.that('View -> /approvals/{id}', hash.includes(`/approvals/${h.pendingChange.id}`), hash);
    } else k.that('View button present', false);
    await rt.approvals.withdraw(T.xmia, h.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `invoice ${i.id}; pending #${h.pendingChange.id}` };
  });

  await uiCase({ id: 'APP-X-UI-07', feature: 'Cancel from the invoice list is held', prd: 'B2',
    title: 'Cancelling a 6000 South invoice from the list shows "Cancellation sent for approval" and leaves it unpaid',
    steps: 'admin South invoice 6000 on fresh c; xmia opens /invoices?f=customerId:eq:<c>; tap "Cancel invoice"; confirm "Cancel invoice"',
    expected: '§7.6 invoices_screen cancel: snack "Cancellation sent for approval" (with View); API invoice UNPAID and one PENDING INVOICE_CANCEL for it' },
  async ({ open, attempt }) => {
    const k = checks();
    const c = await cust();
    const i = await invApplied(T.admin, c, 6000);
    const app = await open('xmia', `/invoices?f=${encodeURIComponent(`customerId:eq:${c.id}`)}`);
    const p = app.page;
    await waitText(p, new RegExp(i.invoiceNumber));
    const cbs = (await rt.semantics(p)).filter((n) => /Cancel invoice/.test(`${n.label || ''} ${n.text || ''}`));
    k.that('Cancel invoice control', cbs.length > 0, await allText(p));
    if (cbs.length) {
      await rt.clickAt(p, cbs[0].x, cbs[0].y, 1500);
      k.that('confirm dialog', !!(await waitText(p, new RegExp(`Cancel ${i.invoiceNumber}\\?`), 4000)));
      const conf = (await rt.semantics(p)).filter((n) => n.role === 'button' && /Cancel invoice/.test(`${n.label || ''} ${n.text || ''}`));
      if (conf.length) await rt.clickAt(p, conf[conf.length - 1].x, conf[conf.length - 1].y, 800);
      k.that('snack', !!(await waitText(p, /Cancellation sent for approval/, 5000)));
    }
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-07-${attempt}`);
    const g = (await rt.api('GET', `/api/invoices/${i.id}`, { token: T.admin })).json;
    k.that('invoice UNPAID', g.status === 'UNPAID', g.status);
    const fr = (await rt.approvals.forRecord(T.admin, 'INVOICE', i.id)).json || [];
    k.that('one pending cancel', fr.length === 1 && fr[0].operation === 'INVOICE_CANCEL', fr.map((x) => x.operation));
    for (const x of fr) await rt.approvals.withdraw(T.xmia, x.id);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `invoice ${i.id}` };
  });

  await uiCase({ id: 'APP-X-UI-08', feature: 'Thresholds screen: no rights', prd: 'B2',
    title: 'A user who neither approves nor sets limits anywhere gets the explanatory empty state on the thresholds screen',
    steps: 'wendy (MANAGE West only) opens /approval-thresholds',
    expected: '§7.7.1 region dropdown only over regions with approvalThresholdManage/changeApprove: none -> "You do not approve changes or manage approval limits in any region."; no Save; no 4xx from thresholds GETs' },
  async ({ open, attempt }) => {
    const k = checks();
    const app = await open('wendy', '/approval-thresholds');
    const p = app.page;
    const txt = await allText(p);
    const hit = await waitText(p, /You do not approve changes or manage approval limits in any region/, 6000);
    // The route may instead be guarded (redirect); record what was shown.
    const hash = await p.evaluate(() => location.hash);
    k.that('empty state or guarded redirect', !!hit || !/approval-thresholds/.test(hash), { hash, txt: txt.slice(0, 300) });
    k.that('no Save button', (await buttons(p, /^\s*Save\s*$/)).length === 0);
    k.that('no threshold API errors', !app.apiErrors.some((e) => /approval-thresholds/.test(e.url)), app.apiErrors);
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-08-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot };
  });

  await uiCase({ id: 'APP-X-UI-09', feature: 'Thresholds screen: region choice', prd: 'B2',
    title: 'A North approver who only reads South is offered only North on the thresholds screen and only North is fetched',
    steps: 'xnate opens /approval-thresholds; open the Region dropdown',
    expected: '§7.7.1: options = regions where he holds approvalThresholdManage or changeApprove = NORTH only; no request for SOUTH thresholds' },
  async ({ open, attempt }) => {
    const k = checks();
    const app = await open('xnate', '/approval-thresholds');
    const p = app.page;
    await waitText(p, /NORTH — North/, 6000);
    const before = await allText(p);
    k.that('NORTH selected', /NORTH — North/.test(before), before.slice(0, 300));
    const dd = (await rt.semantics(p)).find((n) => /NORTH — North/.test(`${n.label || ''} ${n.text || ''}`));
    if (dd) await rt.clickAt(p, dd.x, dd.y, 1500);
    const opened = await allText(p);
    k.that('no SOUTH option', !/SOUTH — South/.test(opened), opened.slice(0, 400));
    k.that('no request for South thresholds', !app.requests.some((r) => r.url.includes(`/api/regions/${W.regions.SOUTH.id}/approval-thresholds`)), app.requests.filter((r) => /approval-thresholds/.test(r.url)));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-09-${attempt}`);
    await rt.dismiss(p);
    return { ok: k.ok, actual: k.summary(), screenshot: shot };
  });

  await uiCase({ id: 'APP-X-UI-10', feature: 'Thresholds screen: input validation', prd: 'B2',
    title: 'Typing a non-number into a limit shows the field error and sends no PUT',
    steps: 'admin opens /approval-thresholds, picks SOUTH if needed; types "abc" into the first Limit field; Save',
    expected: '§7.7.1 money fields: "Enter an amount such as 100000 or 2500.50" and no PUT /approval-thresholds' },
  async ({ open, attempt }) => {
    const k = checks();
    const app = await open('admin', '/approval-thresholds');
    const p = app.page;
    await waitText(p, /Approval thresholds/, 6000);
    const limits = (await rt.semantics(p)).filter((n) => n.role === 'textbox' && /Limit/.test(n.label || ''));
    k.that('limit fields present', limits.length >= 8, limits.length);
    if (limits.length) {
      await p.locator(`[data-rt="${limits[0].i}"]`).first().focus();
      await rt.typeText(p, 'abc', { clear: true });
      await p.waitForTimeout(400);
      await rt.tap(p, /^Save$/, { role: 'button', wait: 2000 });
    }
    k.that('field error', !!(await waitText(p, /Enter an amount such as 100000 or 2500\.50/, 4000)));
    k.that('no PUT sent', !app.requests.some((r) => r.method === 'PUT' && /approval-thresholds/.test(r.url)), app.requests.filter((r) => r.method === 'PUT'));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-10-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot };
  });

  await uiCase({ id: 'APP-X-UI-11', feature: 'Region selector on approvals', prd: 'B1',
    title: 'Narrowing the region selector to North removes a South change from the approvals list and sends regionIds',
    steps: 'xmia holds a 6000 South payment; sam opens /approvals; region selector: untick SOUTH (leaves NORTH only); then All my regions',
    expected: 'B1 "every list … answers which regions am I asking about": the approvals request carries regionIds=<north>; the South row is gone; switching back to all regions brings it back' },
  async ({ open, attempt }) => {
    const k = checks();
    const { pc } = await heldPayment('xmia');
    const app = await open('sam', '/approvals');
    const p = app.page;
    const custRe = new RegExp(pc.customerName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    k.that('row before', !!(await waitText(p, custRe)));
    await rt.chooseRegions(p, [/^SOUTH/]); // from all regions, unticking South leaves North only
    await p.waitForTimeout(2500);
    const sent = app.requests.filter((r) => /^\/api\/approvals(\?|$)/.test(r.url) && r.url.includes(`regionIds=${W.regions.NORTH.id}`));
    k.that('request with regionIds=NORTH', sent.length > 0, app.requests.filter((r) => /^\/api\/approvals/.test(r.url)).slice(-3));
    k.that('South row gone', !(await waitText(p, custRe, 1500)));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-11-${attempt}`);
    await rt.chooseRegions(p, ['All my regions']);
    k.that('row back', !!(await waitText(p, custRe, 6000)));
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${pc.id}` };
  });

  await uiCase({ id: 'APP-X-UI-13', feature: 'Customer login cannot reach approvals', prd: 'B2',
    title: 'The customer login gets no approvals list at /approvals (guarded) and sees its own invoice\'s pending cancel anonymously',
    steps: 'staff invoice 6000 on s1; xmia cancels (held); cust opens /approvals, then /invoices/{id}',
    expected: '§5.11.9: no approvals list for customer logins (route guarded / no GET /api/approvals); on the invoice the banner reads "Awaiting approval: <summary>" without "requested by"' },
  async ({ open, attempt }) => {
    const k = checks();
    const s1 = W.customers.s1; s1.poc = W.users.sam.id;
    const i = await invApplied(attempt === 1 ? T.xvic : T.xmo, s1, 6000);
    const h = await rt.cancelInvoice(T.xmia, i.id);
    if (!h.held) throw new Error(`fixture not held: ${brief(h)}`);
    const app = await open(W.users.cust.username, '/approvals');
    const p = app.page;
    await p.waitForTimeout(1500);
    const hash = await p.evaluate(() => location.hash);
    const txt = await allText(p);
    k.that('no approvals list shown', !/Awaiting my approval|My requests|All I can see/.test(txt), { hash, txt: txt.slice(0, 200) });
    k.that('no 2xx GET /api/approvals list', !app.requests.some((r) => /^\/api\/approvals(\?|$)/.test(r.url) && r.status < 300), app.requests.filter((r) => /approvals/.test(r.url)));
    const shot1 = await rt.shot(p, SHOTS, `APP-X-UI-13-${attempt}-a`);
    await rt.go(p, `/invoices/${i.id}`, 5000);
    const b = await waitText(p, /^Awaiting approval: /, 8000);
    k.that('anonymous banner', !!b && !/requested by/i.test(b), b);
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-13-${attempt}-b`);
    await rt.approvals.withdraw(T.xmia, h.pendingChange.id);
    return { ok: k.ok, actual: k.summary(), screenshot: `${shot1} ; ${shot}`, evidence: `invoice ${i.id}; pending #${h.pendingChange.id}` };
  });
}

// ---- as-of cases (after ageWorld; nothing may run after) -------------------------------------------------------

async function asOfCases() {
  const T = W.tokens;
  // Held before the ageing so it looks requested 3 days ago; decided today after the ageing.
  const pending = await heldPayment('xmia');
  const stays = await heldPayment('xkim');
  rt.ageWorld(3);
  const dec = await rt.approvals.approve(T.sam, pending.pc.id);

  await tcase({ id: 'APP-X-20', feature: 'As-of: outstanding then', kind: 'API', prd: 'B3',
    title: 'A change requested 3 days ago and approved today reads PENDING as of yesterday and APPROVED live; the payment did not exist yesterday',
    steps: 'xmia held a payment; ageWorld(3); sam approves today; GET /api/approvals/{id}?asOf=<yesterday>; list filter status/as-of; summary as of yesterday; payments of the customer as of yesterday and live',
    expected: 'B3 "which approvals were outstanding": as of yesterday statusAt PENDING (status stays live APPROVED per ASO-R1-2), decidedBy null; listed in the as-of list; as-of summary pendingCount counts it; payments of c: 0 as of yesterday, 1 live' },
  async () => {
    const k = checks();
    k.that('approved today', dec.status === 200 && dec.json.status === 'APPROVED', brief(dec).slice(0, 200));
    const y = rt.day(-1);
    const d = await pcGet(T.sam, pending.pc.id, { asOf: y });
    k.that('as-of detail 200 statusAt PENDING', d.status === 200 && d.json.statusAt === 'PENDING', d.json && { status: d.json.status, statusAt: d.json.statusAt });
    k.that('as-of hides decider', d.json && d.json.decidedBy == null && d.json.decidedAt == null, d.json && { decidedBy: d.json.decidedBy, decidedAt: d.json.decidedAt });
    k.that('as-of flags false with read-only reason', d.json && d.json.canApprove === false && d.json.blockedReason === 'Read-only in the as-of view', d.json && { a: d.json.canApprove, r: d.json.blockedReason });
    const l = await rt.list(T.sam, '/api/approvals', { view: 'ALL' }, { asOf: y });
    const row = (l.json?.content || []).find((x) => x.id === pending.pc.id);
    k.that('in as-of list with statusAt PENDING', row && row.statusAt === 'PENDING', row && { status: row.status, statusAt: row.statusAt });
    const sm = await rt.approvals.summary(T.sam, { asOf: y });
    const pendNow = (await rt.list(T.sam, '/api/approvals', { view: 'ALL' }, { asOf: y })).json?.content?.filter((x) => x.statusAt === 'PENDING').length;
    k.that('as-of summary pendingCount = rows PENDING then', sm.ok && sm.json.pendingCount === pendNow, { summary: sm.json, rows: pendNow });
    const py = await rt.list(T.admin, '/api/payments', { filter: [`customerId:eq:${pending.c.id}`] }, { asOf: y });
    const pl = await rt.list(T.admin, '/api/payments', { filter: [`customerId:eq:${pending.c.id}`] });
    k.that('payments: 0 as of yesterday, 1 live', py.ok && py.json.totalElements === 0 && pl.ok && pl.json.totalElements === 1, { asOf: py.json && py.json.totalElements, live: pl.json && pl.json.totalElements });
    return { ok: k.ok, actual: k.summary(), evidence: `pending #${pending.pc.id}` };
  }, );

  await uiCase({ id: 'APP-X-UI-12', feature: 'As-of read-only approval page', prd: 'B3',
    title: 'Viewing yesterday, an approver opening a still-pending change gets no decision buttons and "Read-only in the as-of view"',
    steps: 'xkim held a payment 3 days ago (aged); sam opens /approvals/{id}; picks as-of yesterday via the app-bar chip',
    expected: '§7.4 read-only mode / §6.11 blockedReason "Read-only in the as-of view"; banner "Viewing … — read only"; no Approve/Reject; requests carry asOf' },
  async ({ open, attempt }) => {
    const k = checks();
    const app = await open('sam', `/approvals/${stays.pc.id}`);
    const p = app.page;
    k.that('live: Approve visible', !!(await waitText(p, /^Approve$/, 6000)));
    const chip = await rt.chooseAsOf(p, rt.day(-1));
    await p.waitForTimeout(2500);
    k.that('chip shows As of', /As of/.test(chip || ''), chip);
    k.that('read-only reason', !!(await waitText(p, /Read-only in the as-of view/, 6000)), await allText(p));
    k.that('banner', !!(await waitText(p, /Viewing .*read only/, 3000)));
    k.that('no Approve/Reject', (await buttons(p, /^\s*(Approve|Reject)\s*$/)).length === 0);
    k.that('asOf sent', app.requests.some((r) => r.url.includes(`/api/approvals/${stays.pc.id}`) && r.url.includes(`asOf=${rt.day(-1)}`)), app.requests.filter((r) => r.url.includes(`/api/approvals/${stays.pc.id}`)).slice(-3));
    const shot = await rt.shot(p, SHOTS, `APP-X-UI-12-${attempt}`);
    return { ok: k.ok, actual: k.summary(), screenshot: shot, evidence: `pending #${stays.pc.id}` };
  });
}

(async () => {
  const t0 = Date.now();
  try {
    W = await world();
    const only = process.env.X_ONLY || '';
    if (!only || only.includes('api')) await apiCases();
    if (!only || only.includes('ui')) await uiCases();
    if (!only || only.includes('asof')) await asOfCases();
  } catch (e) {
    rec({ id: 'APP-X-HARNESS', feature: 'Harness', kind: 'API', prd: 'B2', title: 'Explore run aborted', status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
  } finally {
    save();
    const n = (s) => cases.filter((c) => c.status === s).length;
    console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED in ${Math.round((Date.now() - t0) / 1000)} s`);
  }
  process.exit(0);
})();
