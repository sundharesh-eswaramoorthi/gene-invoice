// FINAL exploratory pass, area "regions" (focus PRD B1, with B1 x A/B2/B3 interactions).
// Written from the PRD and DESIGN §5.6 / §6 / §7 only (not from the existing suite's case list).
//   source <e2e>/runtime/regions/env.sh && node final-explore.js
// Results: <e2e>/runs/final/regions-explore.json; screenshots <e2e>/runs/final/regions/shots/explore/.
// A FAIL is re-run once; not reproduced -> NOT_TESTED. An exception -> BLOCKED.
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const OUT = path.join(rt.HOME, 'runs', 'final', 'regions-explore.json');
const SHOTS = path.join(rt.HOME, 'runs', 'final', 'regions', 'shots', 'explore');
const PW = rt.PASSWORD;
const cases = [];
const ONLY = process.env.ONLY ? new RegExp(process.env.ONLY) : null;
const str = (v) => (typeof v === 'string' ? v : JSON.stringify(v));

function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'regions', round: 'final-explore', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
}
function put(meta, r) {
  const c = {
    id: meta.id, feature: meta.feature || '', kind: meta.kind || 'API', prd: meta.prd || 'B1', title: meta.title,
    status: r.status, severity: r.status === 'FAIL' ? (r.severity || meta.severity || 'medium') : '',
    steps: meta.steps || '', expected: meta.expected || '', actual: str(r.actual ?? ''),
    evidence: str(r.evidence ?? ''), screenshot: r.screenshot || '', codeRef: r.status === 'FAIL' ? (r.codeRef || meta.codeRef || '') : '',
  };
  const i = cases.findIndex((x) => x.id === c.id);
  if (i >= 0) cases[i] = c; else cases.push(c);
  console.log(`[${c.status}${c.severity ? '(' + c.severity + ')' : ''}] ${c.id} ${c.title}${c.status !== 'PASS' ? '\n    actual: ' + c.actual.slice(0, 900) : ''}`);
  save();
}
async function C(meta, fn) {
  if (ONLY && !ONLY.test(meta.id)) return;
  let r1;
  try { r1 = await fn(1); } catch (e) {
    put(meta, { status: 'BLOCKED', actual: `exception: ${e && e.stack ? e.stack.split('\n').slice(0, 3).join(' ') : e}` });
    return;
  }
  if (r1.ok) { put(meta, { ...r1, status: 'PASS' }); return; }
  let r2;
  try { r2 = await fn(2); } catch (e) { r2 = { ok: false, actual: `exception on re-run: ${e}` }; }
  if (r2.ok) { put(meta, { ...r1, status: 'NOT_TESTED', actual: `Not reproduced on the second attempt. First attempt: ${str(r1.actual)}` }); return; }
  put(meta, { ...r1, status: 'FAIL', actual: `${str(r1.actual)}  [reproduced on 2nd attempt: ${str(r2.actual).slice(0, 500)}]` });
}

const get = (t, p, query) => rt.api('GET', p, { token: t, query });
const ex = (r, n = 240) => `${r.status} ${(r.text || '').slice(0, n)}`;

// ------------------------------------------------------------------------------------------
let W, R, T, U, admin;
async function mkCust(regionKey, pocUserId, label = 'x') {
  const c = await rt.createCustomer(admin, { name: `Xp ${regionKey} ${label} ${rt.uniq('c')}`, regionId: R[regionKey], paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(admin, c.id, 'COLLECTION', pocUserId, true);
  return c;
}
async function staff(username, roleName, grants, fullName) {
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, roleName, username, { username, fullName: fullName || username, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
  return u;
}

async function setup() {
  admin = await rt.adminToken();
  W = await rt.seedWorld(admin);
  R = { N: W.regions.NORTH.id, S: W.regions.SOUTH.id, W: W.regions.WEST.id, D: W.regions.DEFAULT.id };
  T = { ...W.tokens };
  U = { ...W.users, admin: await rt.me(admin) };
  // ghost: staff login, E2E_REGIONAL role, no grant anywhere.
  U.ghost = await staff('ghost', 'E2E_REGIONAL', []);
  // mo: MANAGE in South and North, no approval right anywhere.
  U.mo = await staff('mo', 'E2E_REGIONAL', [{ regionId: R.S, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }]);
  T.ghost = await rt.login('ghost'); T.mo = await rt.login('mo');
}

// ------------------------------------------------------------------------------------------
async function apiCases() {
  const { n1, n2, s1, s2, w1 } = W.customers;

  await C({ id: 'RGX-01', feature: 'regionIds parameter', prd: 'B1', title: 'regionIds validation boundaries: non-numeric and 0 are 400 REGION_IDS_INVALID; empty means "all my regions"; an unknown id answers 200 empty',
    steps: 'nora GET /api/invoices with regionIds=abc | 0 | (empty) | 999999 | none',
    expected: 'DESIGN §6.0/§6.17: abc -> 400 error REGION_IDS_INVALID "regionIds must be a comma-separated list of region ids"; 0 -> 400 (not a region id); empty == no param; 999999 -> 200, totalElements 0, regionIds []' }, async () => {
    const q = { size: 50 };
    const none = await get(T.nora, '/api/invoices', q);
    const abc = await get(T.nora, '/api/invoices', { ...q, regionIds: 'abc' });
    const zero = await get(T.nora, '/api/invoices', { ...q, regionIds: '0' });
    const empty = await get(T.nora, '/api/invoices', { ...q, regionIds: '' });
    const unk = await get(T.nora, '/api/invoices', { ...q, regionIds: '999999' });
    const ok = none.status === 200 && abc.status === 400 && abc.json?.error === 'REGION_IDS_INVALID'
      && /comma-separated list of region ids/.test(abc.json?.message || '') && zero.status === 400
      && empty.status === 200 && empty.json.totalElements === none.json.totalElements
      && unk.status === 200 && unk.json.totalElements === 0 && Array.isArray(unk.json.regionIds) && unk.json.regionIds.length === 0;
    return { ok, actual: { none: [none.status, none.json?.totalElements, none.json?.regionIds], abc: ex(abc, 200), zero: ex(zero, 160), empty: [empty.status, empty.json?.totalElements], unknown: [unk.status, unk.json?.totalElements, unk.json?.regionIds] } };
  });

  await C({ id: 'RGX-02', feature: 'Nothing in a third region', prd: 'B1', title: 'Asking only for an ungranted region never widens the answer: every list, count, summary and dashboard figure is empty/zero for nora with regionIds=[West]',
    steps: 'nora (MANAGE N, READ S, nothing W) GET 20 set endpoints with regionIds=<West>; and with regionIds=<West,North> compare to <North>',
    expected: 'B1 "every list, filter, count and dashboard figure answers which regions am I asking about"; DESIGN §6.0 "Unknown or ungranted ids contribute nothing (200, empty)"' }, async () => {
    const qW = { regionIds: R.W };
    const lists = ['/api/customers', '/api/invoices', '/api/payments', '/api/promises', '/api/disputes', '/api/tasks', '/api/tasks/mine', '/api/approvals', '/api/automation/rules'];
    const bad = [];
    for (const p of lists) {
      const r = await get(T.nora, p, { ...qW, size: 10 });
      if (r.status !== 200 || r.json?.totalElements !== 0 || (r.json?.regionIds || []).length !== 0) bad.push(`${p}: ${r.status} total=${r.json?.totalElements} regionIds=${JSON.stringify(r.json?.regionIds)}`);
    }
    // Every top-level numeric figure must be zero (numbers or numeric strings); arrays/objects ignored.
    const zeroish = (j) => j && Object.values(j).every((v) => (typeof v === 'number' ? v === 0 : typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) ? Number(v) === 0 : true));
    const sums = Object.fromEntries(['/api/customers/summary', '/api/invoices/summary', '/api/payments/summary', '/api/promises/summary',
      '/api/tasks/summary', '/api/approvals/summary', '/api/automation/rules/summary', '/api/tasks/mine/count', '/api/approvals/awaiting-me/count'].map((p) => [p, zeroish]));
    for (const [p, f] of Object.entries(sums)) {
      const r = await get(T.nora, p, qW);
      if (r.status !== 200 || !f(r.json)) bad.push(`${p}: ${ex(r, 200)}`);
    }
    for (const p of ['/api/dashboard/outstanding-by-age', '/api/dashboard/top-outstanding-customers', '/api/dashboard/billed-by-month', '/api/dashboard/collected-by-month', '/api/dashboard/top-paying-customers']) {
      const r = await get(T.nora, p, qW);
      const nums = JSON.stringify(r.json?.buckets || r.json?.customers || r.json?.months || []);
      const anyAmount = (r.json?.buckets || r.json?.months || []).some((b) => Number(b.amount) !== 0) || (r.json?.customers || []).length > 0;
      if (r.status !== 200 || anyAmount || (r.json?.scope?.regionIds || []).length !== 0) bad.push(`${p}: ${r.status} scope=${JSON.stringify(r.json?.scope)} ${nums.slice(0, 120)}`);
    }
    // West + North == North
    const wn = await get(T.nora, '/api/invoices', { regionIds: `${R.W},${R.N}`, size: 10 });
    const nn = await get(T.nora, '/api/invoices', { regionIds: R.N, size: 10 });
    if (wn.json?.totalElements !== nn.json?.totalElements || JSON.stringify(wn.json?.regionIds) !== JSON.stringify([R.N])) bad.push(`W+N ${wn.json?.totalElements}/${JSON.stringify(wn.json?.regionIds)} vs N ${nn.json?.totalElements}`);
    return { ok: bad.length === 0, actual: bad.length ? bad : 'all empty/zero; W+N == N' };
  });

  await C({ id: 'RGX-03', feature: 'Counts add up across regions', prd: 'B1', title: 'Per-region figures partition the whole: invoice counts, invoice-summary outstanding and dashboard ageing buckets for N,S,W,D sum to the unscoped figure; top-outstanding for North lists only North customers',
    steps: 'admin GET /api/invoices, /api/invoices/summary, /api/dashboard/outstanding-by-age with regionIds each of N,S,W,D and without; /api/dashboard/top-outstanding-customers?regionIds=N&limit=50',
    expected: 'B1: every count/dashboard figure answers "which regions"; records belong to exactly one region so the parts sum to the whole; scope.regionIds echoes the asked set' }, async () => {
    const regs = [R.N, R.S, R.W, R.D];
    const tot = async (q) => (await get(admin, '/api/invoices', { size: 10, ...q })).json.totalElements;
    const out = async (q) => Number((await get(admin, '/api/invoices/summary', q)).json.outstanding);
    const age = async (q) => { const j = (await get(admin, '/api/dashboard/outstanding-by-age', q)).json; return { sum: j.buckets.reduce((a, b) => a + Number(b.amount), 0), scope: j.scope }; };
    const all = { n: await tot({}), o: await out({}), a: (await age({})).sum };
    let n = 0; let o = 0; let a = 0; const per = {}; const scopes = [];
    for (const r of regs) {
      const x = { n: await tot({ regionIds: r }), o: await out({ regionIds: r }), ag: await age({ regionIds: r }) };
      per[r] = { n: x.n, o: x.o, a: x.ag.sum }; n += x.n; o += x.o; a += x.ag.sum; scopes.push(JSON.stringify(x.ag.scope.regionIds));
    }
    const top = (await get(admin, '/api/dashboard/top-outstanding-customers', { regionIds: R.N, limit: 20 }).then((r) => r.json));
    const northNames = new Set((await rt.list(admin, '/api/customers', {}, { regionIds: [R.N] })).json.content.map((c) => c.id));
    const topOk = top.customers.length > 0 && top.customers.every((c) => northNames.has(c.customerId));
    const close = (x, y) => Math.abs(x - y) < 0.005;
    const ok = n === all.n && close(o, all.o) && close(a, all.a) && topOk && scopes.every((s, i) => s === JSON.stringify([regs[i]]));
    return { ok, actual: { all, sumOfParts: { n, o, a }, per, scopes, topCustomers: top.customers.map((c) => c.customerName), topOk } };
  });

  await C({ id: 'RGX-04', feature: 'Read in one region, manage in another', prd: 'B1', title: 'nora (READ South, MANAGE North): every write on a South record is 403 while the same write on a North record succeeds; South reads carry canManage=false',
    steps: 'nora GET s2 invoice; PATCH notes; POST invoice for s2; POST task on s2 invoice; POST promise for s2; POST customer regionId=South; mirror on n2 (North)',
    expected: 'B1 "read in one, manage in another"; DESIGN §5.6.6 requireIn -> 403, §5.6.9 canManage false' }, async () => {
    const sInv = W.invoices.s2[0]; const nInv = W.invoices.n2[0];
    const g = await get(T.nora, `/api/invoices/${sInv.id}`);
    const pS = await rt.api('PATCH', `/api/invoices/${sInv.id}`, { token: T.nora, body: { notes: 'nora south' } });
    const pN = await rt.api('PATCH', `/api/invoices/${nInv.id}`, { token: T.nora, body: { notes: 'nora north' } });
    const iS = await rt.createInvoice(T.nora, { customerId: s2.id, amount: 100, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const iN = await rt.createInvoice(T.nora, { customerId: n2.id, amount: 100, paymentTerm: 'NET_30', salesPocUserId: U.nora.id });
    const tS = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: sInv.id });
    const tN = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: nInv.id });
    const prS = await rt.createPromise(T.nora, { customerId: s2.id, amount: 50 });
    const cS = await rt.api('POST', '/api/customers', { token: T.nora, body: { name: rt.uniq('noraS'), phone: '1', email: `${rt.uniq('n')}@e2e.local`, address: 'a', username: rt.uniq('ns'), password: PW, regionId: R.S } });
    const ok = g.status === 200 && g.json.canManage === false && pS.status === 403 && pN.status === 200
      && iS.status === 403 && iN.status === 200 && tS.status === 403 && tN.status === 200 && prS.status === 403 && cS.status === 403;
    return { ok, actual: { getSouth: [g.status, g.json?.canManage, g.json?.regionCode], patchS: pS.status, patchN: pN.status, invS: ex(iS, 120), invN: iN.status, taskS: ex(tS, 120), taskN: tN.status, promiseS: ex(prS, 120), custS: ex(cS, 120) } };
  });

  await C({ id: 'RGX-05', feature: 'Nothing in a third region', prd: 'B1', title: 'Records in an ungranted region are 404 (not 403) by id, through for-record approvals, audit, task counts and email lists',
    steps: 'nora GET West customer/invoice/payment by id; /api/approvals/for-record INVOICE westInv; /api/audit?entityType=CUSTOMER&entityId=w1; /api/tasks/count?entityType=INVOICE&entityId=westInv; /api/emails?entityType=INVOICE&entityId=westInv',
    expected: 'DESIGN §6.0 404 invisible (no existence leak); §6.11 for-record 404 without the record\'s scoped get' }, async () => {
    const wi = W.invoices.w1[0]; const wp = W.payments.w1[0];
    const r = {
      cust: await get(T.nora, `/api/customers/${w1.id}`), inv: await get(T.nora, `/api/invoices/${wi.id}`), pay: await get(T.nora, `/api/payments/${wp.id}`),
      forRec: await get(T.nora, '/api/approvals/for-record', { entityType: 'INVOICE', entityId: wi.id }),
      audit: await get(T.nora, '/api/audit', { entityType: 'CUSTOMER', entityId: w1.id }),
      taskCount: await get(T.nora, '/api/tasks/count', { entityType: 'INVOICE', entityId: wi.id }),
      emails: await get(T.nora, '/api/emails', { entityType: 'INVOICE', entityId: wi.id, size: 10 }),
    };
    const st = Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v.status]));
    const leak = Object.values(r).some((v) => v.status === 200 && /West/.test(v.text || ''));
    const ok = ['cust', 'inv', 'pay', 'forRec', 'audit'].every((k) => r[k].status === 404)
      && [404, 200].includes(r.taskCount.status) && (r.taskCount.status === 404 || Number(r.taskCount.json?.count) === 0)
      && [404, 200].includes(r.emails.status) && (r.emails.status === 404 || (r.emails.json?.content || []).length === 0) && !leak;
    return { ok, actual: { statuses: st, taskCount: r.taskCount.text.slice(0, 80), emails: r.emails.text.slice(0, 120), custMsg: r.cust.json?.message } };
  });

  await C({ id: 'RGX-06', feature: 'Customer create: region', prd: 'B1', title: 'Customer create without regionId: the single manageable region is used; with two it is 400 "Choose the region…", unless regionIds narrows to one; an ungranted region is 404',
    steps: 'wendy/nora/sam POST /api/customers without regionId; sam again with ?regionIds=<North>; nora with regionId=West',
    expected: 'DESIGN §6.4: wendy -> WEST, nora -> NORTH (only region she manages), sam -> 400 fieldErrors.regionId, sam+regionIds=N -> NORTH, nora regionId=West -> 404' }, async () => {
    const body = () => ({ name: rt.uniq('xpc'), phone: '1', email: `${rt.uniq('e')}@e2e.local`, address: 'a', username: rt.uniq('xpu'), password: PW });
    const w = await rt.api('POST', '/api/customers', { token: T.wendy, body: body() });
    const n = await rt.api('POST', '/api/customers', { token: T.nora, body: body() });
    const s = await rt.api('POST', '/api/customers', { token: T.sam, body: body() });
    const sn = await rt.api('POST', '/api/customers', { token: T.sam, body: body(), query: { regionIds: R.N } });
    const nw = await rt.api('POST', '/api/customers', { token: T.nora, body: { ...body(), regionId: R.W } });
    const ok = w.status === 200 && w.json.regionCode === 'WEST' && n.status === 200 && n.json.regionCode === 'NORTH'
      && s.status === 400 && /Choose the region this customer belongs to/.test(s.json?.fieldErrors?.regionId || '')
      && sn.status === 200 && sn.json.regionCode === 'NORTH' && nw.status === 404;
    return { ok, actual: { wendy: [w.status, w.json?.regionCode], nora: [n.status, n.json?.regionCode], sam: ex(s, 200), samNarrowed: [sn.status, sn.json?.regionCode], noraWest: ex(nw, 120) } };
  });

  await C({ id: 'RGX-07', feature: 'Inactive region', prd: 'B1', title: 'An inactive region takes no new customers, no moves and cannot become the default; /me still lists it as inactive',
    steps: 'admin POST region XI…; PUT active=false; POST customer regionId=XI; move n-customer to XI; POST make-default; GET /api/auth/me',
    expected: 'DESIGN §6.3/§6.4: 400 "Region <name> is inactive" on create and move; make-default 400; /me regions[] item active:false' }, async (attempt) => {
    const code = `XI${Date.now().toString(36).toUpperCase().slice(-5)}${attempt}`;
    const reg = await rt.createRegion(admin, { code, name: `Inactive ${code}` });
    const off = await rt.api('PUT', `/api/regions/${reg.id}`, { token: admin, body: { name: reg.name, active: false, version: reg.version } });
    const c = await rt.api('POST', '/api/customers', { token: admin, body: { name: rt.uniq('xin'), phone: '1', email: `${rt.uniq('e')}@e2e.local`, address: 'a', username: rt.uniq('xiu'), password: PW, regionId: reg.id } });
    const mc = await mkCust('N', U.nora.id, 'inact');
    const mv = await rt.moveCustomerRegion(admin, mc.id, reg.id);
    const md = await rt.api('POST', `/api/regions/${reg.id}/make-default`, { token: admin });
    const me = await rt.me(admin);
    const mine = (me.regions || []).find((x) => x.id === reg.id);
    const ok = off.status === 200 && off.json.active === false && c.status === 400 && /inactive/i.test(c.text) && mv.status === 400 && /inactive/i.test(mv.text)
      && md.status === 400 && mine && mine.active === false;
    return { ok, actual: { deactivate: [off.status, off.json?.active], create: ex(c, 200), move: ex(mv, 200), makeDefault: ex(md, 200), me: mine } };
  });

  await C({ id: 'RGX-08', feature: 'Move a customer: errors', prd: 'B1', title: 'Move-region error contract: same region 400, stale version 409, ungranted target 404, READ-only source 403, historical 400 AS_OF_READ_ONLY; a good move carries the children',
    steps: 'admin move North customer to North; with version-1; nora move it to West; nora move s2 (South, READ) to North; admin move ?asOf=yesterday; admin move to South',
    expected: 'DESIGN §5.6.8 / §6.4 status codes; after the move invoices/payments of the customer report regionCode SOUTH and moved.invoices == 2' }, async () => {
    const c = await mkCust('N', U.nora.id, 'mv');
    for (const amt of [100, 200]) rt.need(await rt.createInvoice(admin, { customerId: c.id, amount: amt, paymentTerm: 'NET_30', salesPocUserId: U.nora.id }), 'inv', [200]);
    const cur = (await get(admin, `/api/customers/${c.id}`)).json;
    const same = await rt.api('POST', `/api/customers/${c.id}/move-region`, { token: admin, body: { regionId: R.N, version: cur.version, reason: 'x' } });
    const stale = await rt.api('POST', `/api/customers/${c.id}/move-region`, { token: admin, body: { regionId: R.S, version: cur.version - 1 >= 0 ? cur.version - 1 : cur.version + 5, reason: 'x' } });
    const toWest = await rt.api('POST', `/api/customers/${c.id}/move-region`, { token: T.nora, body: { regionId: R.W, version: cur.version, reason: 'x' } });
    const s2c = (await get(admin, `/api/customers/${s2.id}`)).json;
    const fromRead = await rt.api('POST', `/api/customers/${s2.id}/move-region`, { token: T.nora, body: { regionId: R.N, version: s2c.version, reason: 'x' } });
    const hist = await rt.api('POST', `/api/customers/${c.id}/move-region`, { token: admin, query: { asOf: rt.day(-1) }, body: { regionId: R.S, version: cur.version, reason: 'x' } });
    const good = await rt.api('POST', `/api/customers/${c.id}/move-region`, { token: admin, body: { regionId: R.S, version: cur.version, reason: 'explore move' } });
    const invs = (await get(admin, '/api/invoices', { filter: `customerId:eq:${c.id}`, size: 10 })).json.content;
    const ok = same.status === 400 && /already in/i.test(same.text) && stale.status === 409 && toWest.status === 404 && fromRead.status === 403
      && hist.status === 400 && hist.json?.error === 'AS_OF_READ_ONLY' && good.status === 200 && good.json.customer.regionCode === 'SOUTH'
      && good.json.moved?.invoices === 2 && invs.length === 2 && invs.every((i) => i.regionCode === 'SOUTH');
    return { ok, actual: { same: ex(same, 150), stale: ex(stale, 150), toWest: toWest.status, fromRead: fromRead.status, hist: ex(hist, 200), good: [good.status, good.json?.customer?.regionCode, good.json?.moved], invRegions: invs.map((i) => i.regionCode) } };
  });

  await C({ id: 'RGX-09', feature: 'Grants are bounded by the granter', prd: 'B1', title: 'A user admin holding MANAGE North + READ South can grant at most that: APPROVE North, anything in West, MANAGE South are 403; dropping a row he cannot touch is 403; his own grants are 400',
    steps: 'role E2E_UADMIN = regional staff + USER_VIEW/USER_MANAGE; gary MANAGE/ALL N, READ/ALL S; gary POST /api/users with various regionGrants; PUT grants of a user holding West READ + North READ sending only North; gary PUT own grants',
    expected: 'DESIGN §4.6 / §6.3: within bounds 200; beyond 403; self 400 "You cannot change your own region access"' }, async (attempt) => {
    await rt.ensureRole(admin, 'E2E_UADMIN', [...rt.REGIONAL_STAFF_PRIVILEGES, 'USER_VIEW', 'USER_MANAGE'], 'e2e user admin');
    const gname = `gary${attempt}`;
    const gary = await staff(gname, 'E2E_UADMIN', [{ regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.S, accessLevel: 'READ', recordScope: 'ALL' }], 'Gary Grants');
    const gt = await rt.login(gname);
    const roleId = await rt.roleId(admin, 'E2E_REGIONAL');
    const mk = (grants) => rt.api('POST', '/api/users', { token: gt, body: { username: rt.uniq('gu'), email: `${rt.uniq('gu')}@e2e.local`, fullName: 'GU', password: PW, roleId, active: true, regionGrants: grants } });
    const okNorthRead = await mk([{ regionId: R.N, level: 'READ', scope: 'ALL' }]);
    const apN = await mk([{ regionId: R.N, level: 'APPROVE', scope: 'ALL' }]);
    const west = await mk([{ regionId: R.W, level: 'READ', scope: 'ALL' }]);
    const manS = await mk([{ regionId: R.S, level: 'MANAGE', scope: 'ALL' }]);
    const other = await rt.createStaff(admin, 'E2E_REGIONAL', 'wo', { grants: [{ regionId: R.W, accessLevel: 'READ' }, { regionId: R.N, accessLevel: 'READ' }] });
    const drop = await rt.setGrants(gt, other.id, [{ regionId: R.N, level: 'MANAGE', scope: 'ALL' }]);
    const keep = await rt.setGrants(gt, other.id, [{ regionId: R.W, level: 'READ', scope: 'ALL' }, { regionId: R.N, level: 'MANAGE', scope: 'ALL' }]);
    const self = await rt.setGrants(gt, gary.id, [{ regionId: R.N, level: 'READ', scope: 'ALL' }]);
    const ok = okNorthRead.status === 200 && apN.status === 403 && west.status === 403 && manS.status === 403 && drop.status === 403
      && keep.status === 200 && self.status === 400 && /cannot change your own region access/i.test(self.text);
    return { ok, actual: { northRead: okNorthRead.status, approveNorth: apN.status, west: west.status, manageSouth: manS.status, dropUntouchable: ex(drop, 120), keepUntouchable: ex(keep, 160), self: ex(self, 160) } };
  });

  await C({ id: 'RGX-10', feature: 'Grant validation', prd: 'B1', title: 'Grant replace validation: duplicate region 400 fieldErrors.grants, unknown region 400 fieldErrors.grants, customer login 400',
    steps: 'admin PUT /api/users/{id}/region-grants with two rows for North; with regionId 999999; for the customer login s1',
    expected: 'DESIGN §6.3: 400 fieldErrors.grants for unknown or duplicate region; 400 "Customer logins take their region from their customer"' }, async () => {
    const u = await rt.createStaff(admin, 'E2E_REGIONAL', 'gv', { grants: [{ regionId: R.N, accessLevel: 'READ' }] });
    const dup = await rt.setGrants(admin, u.id, [{ regionId: R.N, level: 'READ', scope: 'ALL' }, { regionId: R.N, level: 'MANAGE', scope: 'ALL' }]);
    const unk = await rt.setGrants(admin, u.id, [{ regionId: 999999, level: 'READ', scope: 'ALL' }]);
    const custUser = await rt.findUser(admin, W.users.cust.username);
    const cu = await rt.setGrants(admin, custUser.id, [{ regionId: R.S, level: 'READ', scope: 'ALL' }]);
    const after = await rt.getGrants(admin, u.id);
    const ok = dup.status === 400 && dup.json?.fieldErrors?.grants && unk.status === 400 && unk.json?.fieldErrors?.grants
      && cu.status === 400 && /Customer logins take their region/.test(cu.text) && after.length === 1 && after[0].level === 'READ';
    return { ok, actual: { dup: ex(dup, 200), unknown: ex(unk, 200), customerLogin: ex(cu, 160), grantsAfter: after } };
  });

  await C({ id: 'RGX-11', feature: 'Grant change takes effect', prd: 'B1', title: 'Revoking a region takes effect on the user\'s existing token at once: North record 404, South (newly granted READ) visible, /me updated',
    steps: 'rita MANAGE North; login; GET n1 (200); admin sets rita to READ South only; same token GET n1, GET s1, GET /me, POST invoice for n2',
    expected: 'B1 rights per region; DESIGN §4.7 JWT unchanged but rights are read from grants: after revocation n1 -> 404, s1 -> 200, /me regions = [SOUTH READ]; writes in North 404/403' }, async () => {
    const rita = await rt.createStaff(admin, 'E2E_REGIONAL', 'rita', { grants: [{ regionId: R.N, accessLevel: 'MANAGE' }] });
    const t = await rt.login(rita.username);
    const before = await get(t, `/api/customers/${n1.id}`);
    rt.need(await rt.setGrants(admin, rita.id, [{ regionId: R.S, level: 'READ', scope: 'ALL' }]), 'regrant');
    const a = await get(t, `/api/customers/${n1.id}`); const b = await get(t, `/api/customers/${s1.id}`);
    const me = await get(t, '/api/auth/me');
    const w = await rt.createInvoice(t, { customerId: n2.id, amount: 10, paymentTerm: 'NET_30', salesPocUserId: U.nora.id });
    const regs = (me.json?.regions || []).map((r) => `${r.code}:${r.level}`);
    const ok = before.status === 200 && a.status === 404 && b.status === 200 && JSON.stringify(regs) === JSON.stringify(['SOUTH:READ']) && [403, 404].includes(w.status);
    return { ok, actual: { before: before.status, northAfter: a.status, southAfter: b.status, me: regs, write: w.status } };
  });

  await C({ id: 'RGX-12', feature: 'POC book per region', prd: 'B1', title: 'POC assignability is per region: a West-only user cannot be a North customer\'s Collection POC seat or an invoice Sales POC; the assignable picker is region-bound',
    steps: 'admin POST /api/customers/n2/pocs COLLECTION wendy; POST invoice n2 salesPocUserId=wendy; GET /api/pocs/assignable?type=SALES&customerId=n2 (admin); nora ?regionId=West; nora ?customerId=w1',
    expected: 'DESIGN §5.6.7: 400 "User … is not assignable as Sales POC in region NORTH"; picker for n2 has nora/sam/bob not wendy; nora regionId=West -> []; customerId=w1 -> 404' }, async () => {
    const seat = await rt.api('POST', `/api/customers/${n2.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: U.wendy.id, primary: false } });
    const inv = await rt.createInvoice(admin, { customerId: n2.id, amount: 10, paymentTerm: 'NET_30', salesPocUserId: U.wendy.id });
    const pick = await get(admin, '/api/pocs/assignable', { type: 'SALES', customerId: n2.id, limit: 50 });
    const names = (pick.json || []).map((u) => u.username);
    const nw = await get(T.nora, '/api/pocs/assignable', { type: 'SALES', regionId: R.W, limit: 50 });
    const nc = await get(T.nora, '/api/pocs/assignable', { type: 'SALES', customerId: w1.id, limit: 50 });
    const ok = seat.status === 400 && /not assignable as Collection POC/i.test(seat.text) && /NORTH/.test(seat.text) && inv.status === 400
      && names.includes('nora') && names.includes('sam') && !names.includes('wendy') && nw.status === 200 && Array.isArray(nw.json) && nw.json.length === 0 && nc.status === 404;
    return { ok, actual: { seat: ex(seat, 200), invoice: ex(inv, 200), pickerN2: names, noraWest: ex(nw, 100), noraW1: nc.status } };
  });

  await C({ id: 'RGX-13', feature: 'Region administration', prd: 'B1', title: 'Region admin contract: duplicate code 400 fieldErrors.code (case-insensitive), code immutable, default cannot be deactivated or deleted, occupied region cannot be deleted, an empty region can, non-admin 403',
    steps: 'admin POST {code:"north"}; PUT North with a different code; PUT default active=false; DELETE North; DELETE default; create XD… and DELETE; nora POST region',
    expected: 'DESIGN §6.3 messages; delete empty region 204 (creator\'s own grant goes with it)' }, async (attempt) => {
    const dup = await rt.api('POST', '/api/regions', { token: admin, body: { code: 'north', name: rt.uniq('Dup') } });
    const north = (await get(admin, `/api/regions/${R.N}`)).json;
    const recode = await rt.api('PUT', `/api/regions/${R.N}`, { token: admin, body: { code: 'NORTHX', name: north.name, active: true, version: north.version } });
    const northAfter = (await get(admin, `/api/regions/${R.N}`)).json;
    const def = (await get(admin, `/api/regions/${R.D}`)).json;
    const offDef = await rt.api('PUT', `/api/regions/${R.D}`, { token: admin, body: { name: def.name, active: false, version: def.version } });
    const delN = await rt.api('DELETE', `/api/regions/${R.N}`, { token: admin });
    const delD = await rt.api('DELETE', `/api/regions/${R.D}`, { token: admin });
    const code = `XD${Date.now().toString(36).toUpperCase().slice(-5)}${attempt}`;
    const x = await rt.createRegion(admin, { code, name: `Del ${code}` });
    const delX = await rt.api('DELETE', `/api/regions/${x.id}`, { token: admin });
    const nora = await rt.api('POST', '/api/regions', { token: T.nora, body: { code: `NO${attempt}${Date.now().toString(36).toUpperCase().slice(-4)}`, name: rt.uniq('nr') } });
    const ok = dup.status === 400 && dup.json?.fieldErrors?.code && northAfter.code === 'NORTH' && [200, 400].includes(recode.status)
      && offDef.status === 400 && /default region cannot be deactivated/i.test(offDef.text)
      && delN.status === 400 && /still has customers/i.test(delN.text) && delD.status === 400 && /default/i.test(delD.text)
      && delX.status === 204 && nora.status === 403;
    return { ok, actual: { dup: ex(dup, 160), recode: [recode.status, northAfter.code], offDefault: ex(offDef, 160), delNorth: ex(delN, 160), delDefault: ex(delD, 120), delEmpty: ex(delX, 120), noraCreate: nora.status } };
  });

  await C({ id: 'RGX-14', feature: 'Tasks x regions', prd: 'A6', title: 'Task assignees must be able to see the record\'s region: a West-only user is refused on a North invoice; a READ-South user may be assigned on a South invoice and, as assignee, complete it; West user never lists it',
    steps: 'admin POST /api/tasks on n2 invoice assignees [USER wendy]; on s2 invoice assignees [USER nora]; nora POST /tasks/{id}/status DONE; nora PATCH title; wendy GET /api/tasks?entityType=INVOICE&entityId=s2inv',
    expected: 'DESIGN §6.12: 400 fieldErrors.assignees for a user who cannot see the record; status by TASK_MANAGE or effective assignee -> 200; PATCH needs TASK_MANAGE in region -> 403; wendy lists nothing' }, async () => {
    const nInv = W.invoices.n2[1]; const sInv = W.invoices.s2[1];
    const bad = await rt.createTask(admin, { entityType: 'INVOICE', entityId: nInv.id, assignees: [rt.tok.user(U.wendy.id)] });
    const good = await rt.createTask(admin, { entityType: 'INVOICE', entityId: sInv.id, assignees: [rt.tok.user(U.nora.id)] });
    let done = { status: 0 }; let patch = { status: 0 };
    if (good.status === 200) {
      patch = await rt.api('PATCH', `/api/tasks/${good.json.id}`, { token: T.nora, body: { title: 'nora edit', version: good.json.version } });
      done = await rt.tasks.setStatus(T.nora, good.json.id, 'DONE', good.json.version);
    }
    const wl = await get(T.wendy, '/api/tasks', { entityType: 'INVOICE', entityId: sInv.id, size: 10 });
    const ok = bad.status === 400 && bad.json?.fieldErrors?.assignees && good.status === 200 && good.json.assignedToMe === false
      && done.status === 200 && done.json?.status === 'DONE' && patch.status === 403 && [200, 404].includes(wl.status) && (wl.status === 404 || wl.json.totalElements === 0);
    return { ok, actual: { westAssignee: ex(bad, 200), southTask: [good.status, good.json?.regionCode], noraPatch: patch.status, noraDone: [done.status, done.json?.status, (done.text || '').slice(0, 120)], wendyList: [wl.status, wl.json?.totalElements] } };
  });

  await C({ id: 'RGX-15', feature: 'Automation x regions', prd: 'A1', title: 'A rule acts only in its owner\'s effective regions: nora cannot restrict a task rule to West or to South (READ); unrestricted, it fires for a North invoice but not a South one; wendy cannot see it',
    steps: 'nora POST rule regionIds [West] / [South] (CREATE_TASK); regionIds [] ENTITY_CREATED INVOICE total>0 -> task; admin creates invoices on n2 and s2; wait; GET tasks per invoice; wendy/sam GET rule',
    expected: 'DESIGN §5.10.7: 400 fieldErrors.regionIds for a region where the owner lacks a required privilege; empty = every region where the owner can act -> North only; RuleVisibility: wendy 404, sam 200' }, async () => {
    const mkRule = (regionIds, name) => rt.rule({ name, trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, regionIds,
      condition: { op: 'AND', children: [{ filter: 'total:gt:0' }] }, actions: [rt.action.task({ title: 'XP {{Invoice.Number}}', assignees: [rt.tok.user(U.nora.id)] })] });
    const west = await rt.automation.create(T.nora, mkRule([R.W], rt.uniq('xpW')));
    const south = await rt.automation.create(T.nora, mkRule([R.S], rt.uniq('xpS')));
    const any = await rt.automation.create(T.nora, mkRule([], rt.uniq('xpAny')));
    if (any.status !== 200) return { ok: false, actual: { west: ex(west), south: ex(south), any: ex(any) } };
    const iN = rt.need(await rt.createInvoice(admin, { customerId: n2.id, amount: 111, paymentTerm: 'NET_30', salesPocUserId: U.nora.id }), 'iN', [200]);
    const iS = rt.need(await rt.createInvoice(admin, { customerId: s2.id, amount: 111, paymentTerm: 'NET_30', salesPocUserId: U.sam.id }), 'iS', [200]);
    const tn = await rt.waitFor(async () => { const r = await rt.tasks.list(admin, { entityType: 'INVOICE', entityId: iN.id }); return r.json?.totalElements > 0 ? r.json : null; }, { timeoutMs: 40000 });
    await rt.sleep(6000);
    const ts = await rt.tasks.list(admin, { entityType: 'INVOICE', entityId: iS.id });
    const vw = await rt.automation.get(T.wendy, any.json.id); const vs = await rt.automation.get(T.sam, any.json.id);
    await rt.automation.disable(T.nora, any.json.id);
    const ok = west.status === 400 && west.json?.fieldErrors?.regionIds && south.status === 400 && south.json?.fieldErrors?.regionIds
      && tn && tn.totalElements === 1 && ts.json.totalElements === 0 && vw.status === 404 && vs.status === 200;
    return { ok, actual: { west: ex(west, 200), south: ex(south, 200), northTasks: tn?.totalElements ?? 'timeout', southTasks: ts.json?.totalElements, wendyView: vw.status, samView: vs.status } };
  });

  await C({ id: 'RGX-16', feature: 'Thresholds per region: boundary', prd: 'B2', title: 'Threshold boundary and region: exactly South\'s INVOICE_CREATE 10000 applies; 10000.01 is held; 20000 in North (no threshold) applies',
    steps: 'fresh South customers A,B (no other records); sam POST invoice 10000.00 for A, 10000.01 for B; fresh North customer C, sam 20000',
    expected: 'DESIGN Q17 strictly greater: 200 / 202 PENDING_APPROVAL / 200; thresholds are per region (B2)' }, async () => {
    const a = await mkCust('S', U.sam.id, 'eq'); const b = await mkCust('S', U.sam.id, 'gt'); const c = await mkCust('N', U.sam.id, 'n');
    const ia = await rt.createInvoice(T.sam, { customerId: a.id, amount: 10000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const ib = await rt.createInvoice(T.sam, { customerId: b.id, amount: 10000.01, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const ic = await rt.createInvoice(T.sam, { customerId: c.id, amount: 20000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const ok = ia.status === 200 && ib.status === 202 && ib.held && ib.pendingChange?.regionName === 'South' && ic.status === 200;
    return { ok, actual: { equal: [ia.status, ia.json?.total], above: [ib.status, ib.pendingChange?.threshold, ib.pendingChange?.regionName], north: ic.status } };
  });

  await C({ id: 'RGX-17', feature: 'Approval right is per region', prd: 'B2', title: 'A held South change: a South READ user sees it (canApprove false, reason names South) and gets 403 on approve; a West user gets 404; the South approver approves and the invoice appears in South',
    steps: 'fresh South customer; admin POST invoice 20000 (held); nora GET /api/approvals/{id}, POST approve; wendy GET; sam approve; GET invoices for the customer',
    expected: 'B2 "pending change visible to everyone who can see the record"; approval needs the right in that record\'s region; DESIGN §6.11 blockedReason "You don\'t hold the approval right in South", 403, 404' }, async () => {
    const c = await mkCust('S', U.sam.id, 'ap');
    const h = await rt.createInvoice(admin, { customerId: c.id, amount: 20000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    if (!h.held) return { ok: false, actual: `not held: ${ex(h)}` };
    const id = h.pendingChange.id;
    const nv = await rt.approvals.get(T.nora, id); const na = await rt.approvals.approve(T.nora, id);
    const wv = await rt.approvals.get(T.wendy, id);
    const sa = await rt.approvals.approve(T.sam, id);
    const invs = (await get(admin, '/api/invoices', { filter: `customerId:eq:${c.id}`, size: 10 })).json.content;
    const ok = nv.status === 200 && nv.json.canApprove === false && /approval right in South/.test(nv.json.blockedReason || '') && na.status === 403
      && wv.status === 404 && sa.status === 200 && sa.json.status === 'APPROVED' && invs.length === 1 && invs[0].regionCode === 'SOUTH';
    return { ok, actual: { noraView: [nv.status, nv.json?.canApprove, nv.json?.blockedReason], noraApprove: ex(na, 120), wendy: wv.status, samApprove: [sa.status, sa.json?.status], invoices: invs.map((i) => [i.total, i.regionCode]) } };
  });

  await C({ id: 'RGX-18', feature: 'Pending change visible without effect', prd: 'B2', title: 'A held cancel on a South invoice is visible to a South READ user as pendingApproval on the row and in for-record, while the invoice stays un-cancelled; a West user cannot see it',
    steps: 'fresh South customer, admin invoice 6000 (applies), admin cancel (South INVOICE_CANCEL 5000 -> held); nora list invoices regionIds=S customerId filter; nora for-record; wendy for-record',
    expected: 'B2: "a pending change is visible to everyone who can see the record, without having taken effect"' }, async () => {
    const c = await mkCust('S', U.sam.id, 'cx');
    const inv = rt.need(await rt.createInvoice(admin, { customerId: c.id, amount: 6000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id }), 'inv6000', [200]);
    const cx = await rt.cancelInvoice(admin, inv.id);
    const row = (await get(T.nora, '/api/invoices', { regionIds: R.S, filter: `customerId:eq:${c.id}`, size: 10 })).json?.content?.[0];
    const fr = await rt.approvals.forRecord(T.nora, 'INVOICE', inv.id);
    const wf = await rt.approvals.forRecord(T.wendy, 'INVOICE', inv.id);
    const ok = cx.held && row && row.pendingApproval === true && row.status !== 'CANCELLED' && fr.status === 200 && fr.json.length === 1 && fr.json[0].operation === 'INVOICE_CANCEL' && wf.status === 404;
    return { ok, actual: { cancel: [cx.status, cx.pendingChange?.operation], row: row && { status: row.status, pendingApproval: row.pendingApproval }, noraForRecord: [fr.status, (fr.json || []).map?.((x) => x.operation)], wendy: wf.status } };
  });

  await C({ id: 'RGX-19', feature: 'Move while changes are pending', prd: 'B1', title: 'Moving a customer with a pending change: a mover without the approval right in the source is refused 400; an approver\'s move closes the change and counts it',
    steps: 'fresh South customer; admin invoice 20000 (held); mo (MANAGE S+N) move to North; admin move to North; GET the pending change',
    expected: 'DESIGN Q6/§6.4: 400 "… has 1 change awaiting approval; they must be decided first"; with CHANGE_APPROVE 200, moved.other.PendingChangeMoveParticipant >= 1, change no longer PENDING' }, async () => {
    const c = await mkCust('S', U.sam.id, 'pm');
    const h = await rt.createInvoice(admin, { customerId: c.id, amount: 20000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    if (!h.held) return { ok: false, actual: `not held ${ex(h)}` };
    const m1 = await rt.moveCustomerRegion(T.mo, c.id, R.N);
    const m2 = await rt.moveCustomerRegion(admin, c.id, R.N);
    const pc = await rt.approvals.get(admin, h.pendingChange.id);
    const ok = m1.status === 400 && /awaiting approval/.test(m1.text) && m2.status === 200 && (m2.json.moved?.other?.PendingChangeMoveParticipant || 0) >= 1 && pc.json.status !== 'PENDING';
    return { ok, actual: { mo: ex(m1, 200), admin: [m2.status, m2.json?.moved], change: [pc.status, pc.json?.status] } };
  });

  await C({ id: 'RGX-20', feature: 'Move cool-down', prd: 'B2', title: 'After a customer moves South -> North, the stricter South INVOICE_CREATE threshold still applies to it (cool-down); an unmoved North customer is not gated',
    steps: 'fresh South customer (no records) moved to North by admin; sam POST invoice 15000 for it; fresh North customer, sam 15000',
    expected: 'DESIGN Q6 "For 30 days after a move the stricter of the two regions\' thresholds applies" (§6.6): moved -> 202 held; unmoved -> 200' }, async () => {
    const c = await mkCust('S', U.sam.id, 'cd');
    rt.need(await rt.moveCustomerRegion(admin, c.id, R.N), 'move', [200]);
    await rt.addCustomerPoc(admin, c.id, 'SALES', U.sam.id, true).catch(() => null);
    const h = await rt.createInvoice(T.sam, { customerId: c.id, amount: 15000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const n = await mkCust('N', U.sam.id, 'ncd');
    const k = await rt.createInvoice(T.sam, { customerId: n.id, amount: 15000, paymentTerm: 'NET_30', salesPocUserId: U.sam.id });
    const ok = h.status === 202 && h.held && k.status === 200;
    return { ok, actual: { moved: [h.status, h.pendingChange?.regionName, h.pendingChange?.threshold, (h.json?.message || '').slice(0, 160)], unmoved: k.status } };
  });

  await C({ id: 'RGX-21', feature: 'As-of with regions', prd: 'B3', title: 'as-of boundaries combined with regionIds: today is "live", tomorrow AS_OF_FUTURE, a malformed date AS_OF_INVALID, yesterday answers historically for the asked region only',
    steps: 'nora GET /api/invoices?regionIds=N&asOf=<today|tomorrow|2026-9-1|yesterday>',
    expected: 'DESIGN §6.0/§6.17: X-View-As-Of live for today; 400 codes; yesterday -> 200, asOf echoed, regionIds [N], totalElements 0 in a fresh DB (§3.3 of README)' }, async () => {
    const q = (asOf) => get(T.nora, '/api/invoices', { regionIds: R.N, asOf, size: 10 });
    const td = await q(rt.day(0)); const tm = await q(rt.day(1)); const bad = await q('2026-9-1'); const yd = await q(rt.day(-1));
    const ok = td.status === 200 && td.headers['x-view-as-of'] === 'live' && tm.status === 400 && tm.json?.error === 'AS_OF_FUTURE'
      && bad.status === 400 && bad.json?.error === 'AS_OF_INVALID' && yd.status === 200 && yd.json.asOf === rt.day(-1)
      && JSON.stringify(yd.json.regionIds) === JSON.stringify([R.N]) && yd.json.totalElements === 0 && yd.headers['x-view-as-of'] === rt.day(-1);
    return { ok, actual: { today: [td.status, td.headers['x-view-as-of'], td.json?.totalElements], tomorrow: ex(tm, 150), malformed: ex(bad, 150), yesterday: [yd.status, yd.headers['x-view-as-of'], yd.json?.asOf, yd.json?.regionIds, yd.json?.totalElements] } };
  });

  await C({ id: 'RGX-22', feature: 'People pickers are region-bound', prd: 'B1', title: '/api/assignees/people lists only staff holding a grant in the caller\'s regions (wendy never sees North/South-only staff; nora never sees West-only staff); record-bound lookup of a West invoice is 404 for nora',
    steps: 'wendy GET /api/assignees/people?q=; nora GET same; nora GET ?entityType=INVOICE&entityId=westInv',
    expected: 'DESIGN §6.13 region-scoped (SC-18)' }, async () => {
    const w = await get(T.wendy, '/api/assignees/people', { q: '' });
    const n = await get(T.nora, '/api/assignees/people', { q: '' });
    const nr = await get(T.nora, '/api/assignees/people', { q: '', entityType: 'INVOICE', entityId: W.invoices.w1[0].id });
    const wu = (w.json || []).map((x) => x.username); const nu = (n.json || []).map((x) => x.username);
    const ok = w.status === 200 && wu.includes('wendy') && !wu.includes('nora') && !wu.includes('bob') && !wu.includes('ghost')
      && n.status === 200 && nu.includes('bob') && !nu.includes('wendy') && !nu.includes('ghost') && nr.status === 404;
    return { ok, actual: { wendySees: wu, noraSees: nu, noraWestRecord: nr.status } };
  });

  await C({ id: 'RGX-23', feature: 'Export and bulk honour regions', prd: 'B1', title: 'Export "all matching" with regionIds=[North] contains only North invoices; nora\'s bulk CANCEL over all South invoices (READ there) cancels nothing',
    steps: 'admin POST /api/invoices/export?regionIds=N {action:EXPORT, selectAllMatchingFilter:true}; nora POST /api/invoices/bulk?regionIds=S {action:CANCEL, selectAllMatchingFilter:true}',
    expected: 'DESIGN §6.7 "All bulk and export POSTs honour regionIds"; §5.6.5 bulk uses MANAGE access -> South rows unreachable' }, async () => {
    const nList = (await rt.list(admin, '/api/invoices', {}, { regionIds: [R.N] })).json.content;
    const exp = await rt.api('POST', '/api/invoices/export', { token: admin, query: { regionIds: R.N }, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [] } });
    const lines = (exp.text || '').trim().split('\n').slice(1);
    const nums = new Set(nList.map((i) => i.invoiceNumber));
    const allNorth = lines.length === nList.length && lines.every((l) => [...nums].some((x) => l.includes(x)));
    const sBefore = (await rt.list(admin, '/api/invoices', {}, { regionIds: [R.S] })).json.content.filter((i) => i.status === 'CANCELLED').length;
    const bulk = await rt.api('POST', '/api/invoices/bulk', { token: T.nora, query: { regionIds: R.S }, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: [] } });
    const sAfter = (await rt.list(admin, '/api/invoices', {}, { regionIds: [R.S] })).json.content.filter((i) => i.status === 'CANCELLED').length;
    const ok = exp.status === 200 && allNorth && [200, 400, 403].includes(bulk.status) && sAfter === sBefore;
    return { ok, actual: { exportRows: lines.length, northRows: nList.length, allNorth, bulk: ex(bulk, 240), cancelledBefore: sBefore, cancelledAfter: sAfter } };
  });

  await C({ id: 'RGX-24', feature: 'Book scope per region', prd: 'B1', title: 'A mixed grant (MANAGE/ALL North, MANAGE/BOOK South) lists all North invoices but only the South invoices of its own book; envelope reports bookRegionIds=[South] and no locked chip',
    steps: 'maya (E2E_REGIONAL) MANAGE/ALL N + MANAGE/BOOK S; admin creates South invoice with salesPoc maya on s2 (she is added as seat); GET /api/invoices',
    expected: 'B1 "the POC book is per region"; DESIGN §5.6.4 mixed view: lockedFilters [], bookRegionIds [S]' }, async (attempt) => {
    const maya = await staff(`maya${attempt}`, 'E2E_REGIONAL', [{ regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.S, accessLevel: 'MANAGE', recordScope: 'BOOK' }]);
    const c = await mkCust('S', U.sam.id, 'bk');
    const mine = rt.need(await rt.createInvoice(admin, { customerId: c.id, amount: 70, paymentTerm: 'NET_30', salesPocUserId: maya.id }), 'maya inv', [200]);
    const t = await rt.login(`maya${attempt}`);
    const all = await rt.list(t, '/api/invoices');
    const rows = all.json.content;
    const north = (await rt.list(admin, '/api/invoices', {}, { regionIds: [R.N] })).json.content.length;
    const south = rows.filter((r) => r.regionCode === 'SOUTH');
    const ok = all.status === 200 && rows.filter((r) => r.regionCode === 'NORTH').length === north && south.length === 1 && south[0].id === mine.id
      && JSON.stringify(all.json.bookRegionIds) === JSON.stringify([R.S]) && (all.json.lockedFilters || []).length === 0;
    return { ok, actual: { north: [rows.filter((r) => r.regionCode === 'NORTH').length, north], south: south.map((r) => r.id), mine: mine.id, bookRegionIds: all.json.bookRegionIds, lockedFilters: all.json.lockedFilters } };
  });
}

// ------------------------------------------------------------------------------------------
// openApp's path is not kept by the app at boot (it lands on '/'), so navigate after boot.
async function withApp(opts, fn) {
  const { path: route, ...o } = opts;
  const app = await rt.openApp(o);
  if (route) await rt.go(app.page, route);
  try { return await fn(app); } finally { await app.close(); }
}
const hasText = async (page, re) => (await rt.screenText(page)).some((s) => re.test(s));

async function uiCases() {
  const { n2, s2, w1 } = W.customers;

  await C({ id: 'RGX-U1', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'nora\'s region selector offers exactly NORTH and SOUTH (+ All my regions); choosing only SOUTH sends regionIds=<South> and the invoices list shows only South rows',
    steps: 'login nora; /invoices; open "Regions:" selector; read items; untick NORTH (leaving SOUTH); read the selector label, requests and rows',
    expected: 'B1 "which regions am I asking about"; DESIGN §7.3.1 selector over user.regions; §7.1 regionIds sent on every list request' }, async (attempt) => {
    return withApp({ username: 'nora', path: '/invoices' }, async ({ page, requests, pageErrors }) => {
      const sel = await rt.regionSelector(page);
      if (!sel) return { ok: false, actual: 'no region selector', screenshot: await rt.shot(page, SHOTS, `RGX-U1-${attempt}-nosel`) };
      await rt.clickAt(page, sel.x, sel.y, 1200);
      const items = (await rt.semantics(page)).filter((n) => n.role === 'menuitemcheckbox' || /All my regions/.test(n.label || n.text || '')).map((n) => (n.label || n.text));
      const s1 = await rt.shot(page, SHOTS, `RGX-U1-${attempt}-menu`);
      // From "all", each item is a toggle: unticking NORTH leaves SOUTH only.
      await rt.press(page, /^NORTH/, { wait: 3000 });
      const selNode = await rt.regionSelector(page);
      const label = selNode ? `${selNode.label || ''} ${selNode.text || ''}`.trim() : '';
      await page.waitForTimeout(1500);
      const invReq = requests.filter((r) => /^\/api\/invoices\?/.test(r.url)).pop();
      const text = await rt.screenText(page);
      const s2shot = await rt.shot(page, SHOTS, `RGX-U1-${attempt}-south`);
      const northRows = text.filter((t) => /North N[12] /.test(t)).length; const southRows = text.filter((t) => /South S[12] /.test(t)).length;
      const hasNorthItem = items.some((i) => /^NORTH/.test(i)); const hasSouthItem = items.some((i) => /^SOUTH/.test(i)); const hasWest = items.some((i) => /^WEST|^DEFAULT/.test(i));
      const ok = /SOUTH/.test(label) && !/NORTH/.test(label) && hasNorthItem && hasSouthItem && !hasWest && items.some((i) => /All my regions/.test(i)) && invReq && invReq.url.includes(`regionIds=${R.S}`) && !/regionIds=[^&]*,/.test(invReq.url)
        && southRows > 0 && northRows === 0 && pageErrors.length === 0;
      return { ok, actual: { items, label, lastInvoiceRequest: invReq?.url, northRows, southRows, pageErrors }, screenshot: `${s1}, ${s2shot}` };
    });
  });

  await C({ id: 'RGX-U2', kind: 'UI', feature: 'Single-region user', prd: 'B1', title: 'wendy (West only) sees no region selector and no Region column; her invoices list shows West rows only',
    steps: 'login wendy; /invoices',
    expected: 'DESIGN §7.3.1 selector hidden when regions.length <= 1; §7.5 Region column only when multiRegion' }, async (attempt) => {
    return withApp({ username: 'wendy', path: '/invoices' }, async ({ page, pageErrors }) => {
      const sel = await rt.regionSelector(page);
      const text = await rt.screenText(page);
      const shot = await rt.shot(page, SHOTS, `RGX-U2-${attempt}`);
      const regionHeader = text.some((t) => /^Region$/.test(t));
      const west = text.filter((t) => /West W[12] /.test(t)).length; const other = text.filter((t) => /(North|South|Default) [NSD][12] /.test(t)).length;
      const ok = !sel && !regionHeader && west > 0 && other === 0 && pageErrors.length === 0;
      return { ok, actual: { selector: sel && sel.label, regionHeader, westRows: west, otherRows: other, pageErrors }, screenshot: shot };
    });
  });

  await C({ id: 'RGX-U3', kind: 'UI', feature: 'Read-only record in a READ region', prd: 'B1', title: 'nora opens a South invoice: fields are read-only (no notes field, no "Save changes"); a North invoice is editable',
    steps: 'login nora; /invoices/<s2 inv>; then /invoices/<n2 inv>',
    expected: 'B1 read in one region, manage in another; DESIGN §7.5 controls follow canManage' }, async (attempt) => {
    return withApp({ username: 'nora', path: `/invoices/${W.invoices.s2[0].id}` }, async ({ page, pageErrors }) => {
      await page.waitForTimeout(1500);
      const sText = await rt.screenText(page);
      const sShot = await rt.shot(page, SHOTS, `RGX-U3-${attempt}-south`);
      const sEditable = (await rt.exists(page, 'Save changes')) || (await rt.semantics(page)).some((n) => n.role === 'textbox' && /Internal notes/.test(n.label || ''));
      await rt.go(page, `/invoices/${W.invoices.n2[0].id}`);
      const nShot = await rt.shot(page, SHOTS, `RGX-U3-${attempt}-north`);
      const nEditable = await rt.exists(page, 'Save changes');
      const southLoaded = sText.some((t) => t.includes(W.invoices.s2[0].invoiceNumber));
      const ok = southLoaded && !sEditable && nEditable && pageErrors.length === 0;
      return { ok, actual: { southLoaded, southEditable: sEditable, northEditable: nEditable, pageErrors }, screenshot: `${sShot}, ${nShot}` };
    });
  });

  await C({ id: 'RGX-U4', kind: 'UI', feature: 'Deep link into an ungranted region', prd: 'B1', title: 'nora deep-links to a West customer and a West invoice: the app says the record does not exist (404), without leaking its name or crashing',
    steps: 'login nora; /customers/<w1>; /invoices/<w1 inv>',
    expected: 'DESIGN §6.0 404; §7.4 RecordUnavailable "That customer does not exist."' }, async (attempt) => {
    return withApp({ username: 'nora', path: `/customers/${w1.id}` }, async ({ page, pageErrors }) => {
      await page.waitForTimeout(1000);
      const t1 = await rt.screenText(page);
      const s1 = await rt.shot(page, SHOTS, `RGX-U4-${attempt}-customer`);
      await rt.go(page, `/invoices/${W.invoices.w1[0].id}`);
      const t2 = await rt.screenText(page);
      const s2s = await rt.shot(page, SHOTS, `RGX-U4-${attempt}-invoice`);
      const c404 = t1.some((t) => /That customer does not exist/.test(t)); const i404 = t2.some((t) => /That invoice does not exist/.test(t));
      const leak = [...t1, ...t2].some((t) => t.includes(w1.name) || t.includes(W.invoices.w1[0].invoiceNumber));
      const ok = c404 && i404 && !leak && pageErrors.length === 0;
      return { ok, actual: { customerMsg: t1.filter((t) => /exist|permission|Could not/.test(t)), invoiceMsg: t2.filter((t) => /exist|permission|Could not/.test(t)), leak, pageErrors }, screenshot: `${s1}, ${s2s}` };
    });
  });

  await C({ id: 'RGX-U5', kind: 'UI', feature: 'Staff login with no region', prd: 'B1', title: 'A staff user holding no region signs in through the login screen and gets empty screens (dashboard, customers, invoices), no region selector, no server errors and no crash',
    steps: 'open app (login screen); sign in as ghost; visit /, /customers, /invoices',
    expected: 'B1 nothing in any region; DESIGN §4.6 a user with no grants sees nothing regional; empty states, not errors' }, async (attempt) => {
    return withApp({}, async ({ page, apiErrors, pageErrors }) => {
      await rt.uiLogin(page, 'ghost', PW);
      await page.waitForTimeout(2000);
      const dash = await rt.shot(page, SHOTS, `RGX-U5-${attempt}-dashboard`);
      const loggedIn = await hasText(page, /ghost|Dashboard/);
      const sel = await rt.regionSelector(page);
      await rt.go(page, '/customers');
      const cText = await rt.screenText(page);
      const cs = await rt.shot(page, SHOTS, `RGX-U5-${attempt}-customers`);
      await rt.go(page, '/invoices');
      const iText = await rt.screenText(page);
      const is = await rt.shot(page, SHOTS, `RGX-U5-${attempt}-invoices`);
      const anyRows = [...cText, ...iText].some((t) => /(North|South|West|Default) [NSWD][12] /.test(t));
      const serverErr = apiErrors.filter((e) => e.status >= 500);
      const ok = loggedIn && !sel && !anyRows && serverErr.length === 0 && pageErrors.length === 0;
      return { ok, actual: { loggedIn, selector: sel && sel.label, anyRows, apiErrors: apiErrors.slice(0, 8), pageErrors, customersText: cText.filter((t) => /No |empty|nothing|0 /i.test(t)).slice(0, 5) }, screenshot: `${dash}, ${cs}, ${is}` };
    });
  });

  await C({ id: 'RGX-U6', kind: 'UI', feature: 'New customer: region choice', prd: 'B1', title: 'New customer dialog: sam (manages North and South) must choose a Region and Save without one shows "Choose the region this customer belongs to"; wendy (one region) is not asked',
    steps: 'login sam; /customers; tap "New customer"; fill Name/Username/Password; tap Save; read errors; close; login wendy; open the dialog',
    expected: 'DESIGN §7.5 create flows: required Region dropdown when there is more than one choice; §6.4 message' }, async (attempt) => {
    const samRes = await withApp({ username: 'sam', path: '/customers' }, async ({ page }) => {
      await rt.tap(page, /New customer/, { wait: 2000 });
      const hasRegion = (await rt.semantics(page)).some((n) => /^Region/.test(n.label || '') || n.text === 'Region');
      await rt.fillField(page, 'Name', rt.uniq('uiNoRegion'));
      await rt.fillField(page, 'Username', rt.uniq('uinr'));
      await rt.fillField(page, 'Password', 'Passw0rd!');
      await rt.tap(page, 'Save', { wait: 2000 });
      const txt = await rt.screenText(page);
      const shot = await rt.shot(page, SHOTS, `RGX-U6-${attempt}-sam`);
      const msg = txt.some((t) => /Choose the region this customer belongs to/.test(t));
      const stillOpen = txt.some((t) => /^New customer$/.test(t));
      return { hasRegion, msg, stillOpen, shot };
    });
    const wRes = await withApp({ username: 'wendy', path: '/customers' }, async ({ page }) => {
      await rt.tap(page, /New customer/, { wait: 2000 });
      const hasRegion = (await rt.semantics(page)).some((n) => /^Region/.test(n.label || '') || n.text === 'Region');
      const shot = await rt.shot(page, SHOTS, `RGX-U6-${attempt}-wendy`);
      return { hasRegion, shot };
    });
    const ok = samRes.hasRegion && samRes.msg && samRes.stillOpen && !wRes.hasRegion;
    return { ok, actual: { sam: { region: samRes.hasRegion, message: samRes.msg, dialogStillOpen: samRes.stillOpen }, wendy: { region: wRes.hasRegion } }, screenshot: `${samRes.shot}, ${wRes.shot}` };
  });

  await C({ id: 'RGX-U7', kind: 'UI', feature: 'Move region (UI)', prd: 'B1', title: 'Move region: hidden for nora on a South customer; sam moves a North customer to South through the dialog (only SOUTH offered), sees the result, and the server agrees',
    steps: 'login nora /customers/<s2>: no "Move region"; login sam /customers/<fresh North customer>: tap "Move region"; open "Move to"; read options; pick SOUTH; type reason; tap Move; read result; GET customer',
    expected: 'DESIGN §7.5 Move customer when canMoveRegion; options = active regionsWith(customerManage) minus current; result dialog "<name> is now in SOUTH" (code or name)' }, async (attempt) => {
    const noraHas = await withApp({ username: 'nora', path: `/customers/${s2.id}` }, async ({ page }) => {
      await page.waitForTimeout(1000);
      const has = await rt.exists(page, /Move region/);
      const shot = await rt.shot(page, SHOTS, `RGX-U7-${attempt}-nora`);
      return { has, shot };
    });
    const c = await mkCust('N', U.sam.id, `ui${attempt}`);
    const samRes = await withApp({ username: 'sam', path: `/customers/${c.id}` }, async ({ page, pageErrors }) => {
      await page.waitForTimeout(1000);
      const has = await rt.exists(page, /Move region/);
      if (!has) return { has, shot: await rt.shot(page, SHOTS, `RGX-U7-${attempt}-sam-nobutton`) };
      await rt.tap(page, /Move region/, { wait: 2000 });
      const dd = (await rt.semantics(page)).find((n) => /Move to/.test(n.label || n.text || ''));
      if (dd) await rt.clickAt(page, dd.x, dd.y, 1500);
      const opts = (await rt.semantics(page)).map((n) => n.label || n.text).filter((s) => /^(NORTH|SOUTH|WEST|DEFAULT) — /.test(s || ''));
      const shotMenu = await rt.shot(page, SHOTS, `RGX-U7-${attempt}-sam-options`);
      if (opts.some((o) => /^SOUTH/.test(o))) await rt.press(page, /^SOUTH — /, { wait: 1500 });
      await rt.fillField(page, /Reason/, 'ui explore move');
      await rt.tap(page, 'Move', { wait: 3500 });
      const txt = await rt.screenText(page);
      const shotDone = await rt.shot(page, SHOTS, `RGX-U7-${attempt}-sam-done`);
      const result = txt.find((t) => /is now in/.test(t)) || null;
      return { has, opts, result, pageErrors, shot: `${shotMenu}, ${shotDone}` };
    });
    const after = (await get(admin, `/api/customers/${c.id}`)).json;
    const ok = !noraHas.has && samRes.has && samRes.opts && samRes.opts.length === 1 && /^SOUTH/.test(samRes.opts[0]) && samRes.result && /SOUTH|South/.test(samRes.result) && after.regionCode === 'SOUTH';
    return { ok, actual: { noraSeesMove: noraHas.has, samSeesMove: samRes.has, options: samRes.opts, result: samRes.result, serverRegion: after.regionCode, pageErrors: samRes.pageErrors }, screenshot: `${noraHas.shot}, ${samRes.shot}` };
  });

  await C({ id: 'RGX-U8', kind: 'UI', feature: 'Regions x as-of in the app', prd: 'B3', title: 'nora narrows to NORTH and picks yesterday: the list request carries both regionIds and asOf, the read-only banner shows, and "Back to today" drops asOf while keeping the region',
    steps: 'login nora; /invoices; untick SOUTH (NORTH only); chooseAsOf(yesterday); inspect requests/banner; Back to today; inspect next request',
    expected: 'B1 + B3 "every list … can be asked as of a date" for the chosen regions; DESIGN §7.1 apiParams; §7.3.1 banner "Viewing … — read only"' }, async (attempt) => {
    return withApp({ username: 'nora', path: '/invoices' }, async ({ page, requests, pageErrors }) => {
      await rt.chooseRegions(page, [/^SOUTH/]); // untick SOUTH from "all" -> NORTH only
      const sn = await rt.regionSelector(page);
      const label = sn ? `${sn.label || ''} ${sn.text || ''}`.trim() : '';
      const y = rt.day(-1);
      const chip = await rt.chooseAsOf(page, y);
      await page.waitForTimeout(1500);
      const hist = requests.filter((r) => /^\/api\/invoices\?/.test(r.url)).pop();
      const banner = await hasText(page, /Viewing .*read only/);
      const s1 = await rt.shot(page, SHOTS, `RGX-U8-${attempt}-asof`);
      const n0 = requests.length;
      await rt.chooseAsOf(page, null);
      await page.waitForTimeout(1500);
      const live = requests.slice(n0).filter((r) => /^\/api\/invoices\?/.test(r.url)).pop();
      const s2s = await rt.shot(page, SHOTS, `RGX-U8-${attempt}-live`);
      const ok = hist && hist.url.includes(`asOf=${y}`) && hist.url.includes(`regionIds=${R.N}`) && banner && live && !live.url.includes('asOf=') && live.url.includes(`regionIds=${R.N}`) && pageErrors.length === 0;
      return { ok, actual: { label, chip, histReq: hist?.url, banner, liveReq: live?.url, pageErrors }, screenshot: `${s1}, ${s2s}` };
    });
  });
}

// ------------------------------------------------------------------------------------------
(async () => {
  const t0 = Date.now();
  await setup();
  console.log(`setup ${Date.now() - t0} ms regions ${JSON.stringify(R)}`);
  if (!process.env.SKIP_API) await apiCases();
  if (!process.env.SKIP_UI) await uiCases();
  save();
  const n = (s) => cases.filter((c) => c.status === s).length;
  console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED in ${Math.round((Date.now() - t0) / 1000)} s`);
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); save(); process.exit(2); });
