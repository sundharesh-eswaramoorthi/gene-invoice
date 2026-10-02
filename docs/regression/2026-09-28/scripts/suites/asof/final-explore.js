#!/usr/bin/env node
// FINAL exploratory pass for area "asof" (PRD B3, with B1/B2/A cross-feature interactions).
// Written independently of the existing asof suite, straight from the PRD and DESIGN §6/§7.
//
//   source <e2e>/runtime/asof/env.sh
//   node final-explore.js                 # builds its own scenario on the area's (fresh) database, runs every case
//   EXPLORE_REUSE=1 node final-explore.js # reuse runtime/asof/explore-scenario.json (no re-seed)
//   EXPLORE_ONLY=api|ui node final-explore.js
//
// Results: runs/final/asof-explore.json; screenshots: runs/final/asof/shots/explore/.
// Every FAIL is re-checked once more (a second failure = reproduced; a pass on the second attempt = NOT_TESTED).
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');

const OUT = path.join(rt.HOME, 'runs', 'final', 'asof-explore.json');
const SHOTS = path.join(rt.HOME, 'runs', 'final', 'asof', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });
const STATE = path.join(process.env.E2E_RUNTIME || path.join(rt.HOME, 'runtime', 'asof'), 'explore-scenario.json');

// ---- results ------------------------------------------------------------------------------
const cases = [];
if (process.env.EXPLORE_REUSE && fs.existsSync(OUT)) cases.push(...JSON.parse(fs.readFileSync(OUT, 'utf8')).cases);
function rec(c) {
  const row = {
    id: c.id, feature: c.feature || '', kind: c.kind || 'API', prd: c.prd || '', title: c.title || '', status: c.status,
    severity: c.status === 'FAIL' ? (c.severity || 'medium') : '', steps: c.steps || '', expected: c.expected || '',
    actual: typeof c.actual === 'string' ? c.actual : JSON.stringify(c.actual), evidence: c.evidence || '',
    screenshot: c.screenshot || '', codeRef: c.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === row.id);
  if (i >= 0) cases[i] = row; else cases.push(row);
  console.log(`[${row.status}] ${row.id} ${row.title}`);
}
function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'asof', round: 'final', part: 'explore', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
  const n = (s) => cases.filter((c) => c.status === s).length;
  console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED`);
}
async function check(def, fn) {
  const base = { kind: 'API', ...def };
  try {
    const o1 = await fn(1);
    if (o1.ok) return rec({ ...base, status: 'PASS', actual: o1.actual, evidence: o1.evidence || o1.actual, screenshot: o1.screenshot || '' });
    let o2;
    try { o2 = await fn(2); } catch (e) { o2 = { ok: false, actual: `second attempt threw: ${e.message}` }; }
    if (o2.ok) {
      return rec({ ...base, status: 'NOT_TESTED', actual: `attempt 1 failed (${o1.actual}); attempt 2 passed (${o2.actual}) — not reproducible`, evidence: o1.evidence || '', screenshot: o1.screenshot || '' });
    }
    return rec({ ...base, status: 'FAIL', severity: o2.severity || def.severity || 'medium', actual: `${o2.actual} [reproduced 2/2]`, evidence: o2.evidence || o1.evidence || '', screenshot: o2.screenshot || o1.screenshot || '', codeRef: o2.codeRef || def.codeRef || '' });
  } catch (e) {
    return rec({ ...base, status: 'BLOCKED', actual: `harness/setup error: ${e.message}`.slice(0, 1500), evidence: (e.stack || '').split('\n').slice(0, 4).join(' | ') });
  }
}

// ---- helpers ------------------------------------------------------------------------------
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const label = (iso) => { const [y, m, d] = iso.split('-').map(Number); return `${d} ${MON[m - 1]} ${y}`; };
const ex = (r, n = 300) => `${r.status} ${(r.text || '').slice(0, n)}`;
const ids = (r) => (r.json?.content || []).map((x) => x.id);
const G = (tok, p, query) => rt.api('GET', p, { token: tok, query });
const num = (v) => (v === null || v === undefined ? null : Number(v));

// ---- scenario -----------------------------------------------------------------------------
async function build() {
  const admin = await rt.adminToken();
  const W = await rt.seedWorld(admin);
  const tag = W.customers.n1.name.split(' ').pop();
  const R = W.regions; const U = W.users;
  const S = { tag, regions: {}, users: {}, customers: {}, invoices: {}, ids: {} };
  for (const k of Object.keys(R)) S.regions[k] = { id: R[k].id, code: R[k].code };
  for (const k of ['nora', 'sam', 'bob', 'wendy']) S.users[k] = { id: U[k].id, username: U[k].username };
  S.users.cust = { username: U.cust.username, customerId: U.cust.customerId };
  for (const k of Object.keys(W.customers)) S.customers[k] = { id: W.customers[k].id, name: W.customers[k].name };
  for (const k of Object.keys(W.invoices)) S.invoices[k] = W.invoices[k].map((i) => i.id);
  S.adminId = (await rt.me(admin)).id;

  // vera: READ/ALL North only.
  const vera = await rt.createStaff(admin, 'E2E_REGIONAL', 'vera', { grants: [{ regionId: R.NORTH.id, accessLevel: 'READ', recordScope: 'ALL' }] });
  S.users.vera = { id: vera.id, username: vera.username };

  // ---- phase A (becomes ~10 days ago after ageWorld) ----
  const tA = rt.need(await rt.createTask(admin, { entityType: 'INVOICE', entityId: S.invoices.n1[1], title: `Old task ${tag}`, assignees: [rt.tok.user(U.nora.id)] }), 'task A');
  S.ids.taskA = tA.id;
  const r1 = rt.need(await rt.automation.create(admin, rt.rule({
    name: `ROld ${tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [R.NORTH.id],
    condition: { op: 'AND', children: [{ filter: 'balance:gt:0' }] },
    actions: [rt.action.task({ title: `R1 {{Invoice.Number}} ${tag}`, assignees: [rt.tok.user(S.adminId)] })],
  })), 'rule R1');
  S.ids.rule1 = r1.id;
  const runA = rt.need(await rt.automation.run(admin, r1.id, {}), 'run R1 (A)', [200, 202]);
  S.ids.runA = runA.runId;
  const runADone = await rt.waitForRun(admin, runA.runId, { timeoutMs: 90000 });
  S.runAStatus = runADone && runADone.status;

  // A 6000 invoice on s2 (applies: below INVOICE_CREATE 10000), then a held cancel of it (above INVOICE_CANCEL 5000).
  const i6 = await rt.createInvoice(admin, { customerId: S.customers.s2.id, amount: 6000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  rt.need(i6, 'invoice 6000 on s2', [200]);
  S.ids.inv6000 = i6.json.id;
  const cc = await rt.cancelInvoice(admin, i6.json.id, { reason: 'e2e explore cancel' });
  if (!cc.held) throw new Error(`expected the 6000 cancel to be held: ${ex(cc)}`);
  S.ids.pcCancel = cc.pendingChange.id;
  // pcA: sam asks for a 12000 invoice on s2 (held; stays pending).
  const a = await rt.createInvoice(W.tokens.sam, { customerId: S.customers.s2.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  if (!a.held) throw new Error(`expected pcA held: ${ex(a)}`);
  S.ids.pcA = a.pendingChange.id;
  // pcB: admin asks for a 15000 invoice on s1 (held; sam approves it after T).
  const b = await rt.createInvoice(admin, { customerId: S.customers.s1.id, amount: 15000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  if (!b.held) throw new Error(`expected pcB held: ${ex(b)}`);
  S.ids.pcB = b.pendingChange.id;
  // An invoice on w1 due in 5 days (after ageing: due 5 days ago, i.e. after T and before today).
  const d = await rt.createInvoice(admin, { customerId: S.customers.w1.id, amount: 700, dueDate: rt.day(5), paymentTerm: 'CUSTOM', salesPocUserId: U.wendy.id });
  rt.need(d, 'invoice due +5d', [200]);
  S.ids.invDue = d.json.id;

  // ---- age the world ----
  rt.ageWorld(10);
  S.T = rt.day(-7);
  S.yesterday = rt.day(-1);
  S.today = rt.day(0);

  // ---- phase B (now) ----
  const admin2 = await rt.adminToken();
  const sam = await rt.login('sam');
  const bob = await rt.login('bob');
  const apB = await rt.approvals.approve(sam, S.ids.pcB, 'ok by explore');
  if (apB.status !== 200) throw new Error(`approve pcB: ${ex(apB)}`);
  const pcBd = rt.need(await rt.approvals.get(admin2, S.ids.pcB), 'pcB detail');
  S.ids.invB = pcBd.result && (pcBd.result.id || pcBd.result.entityId);
  const rj = await rt.approvals.reject(sam, S.ids.pcCancel, 'no by explore');
  if (rj.status !== 200) throw new Error(`reject pcCancel: ${ex(rj)}`);
  const c = await rt.createInvoice(admin2, { customerId: S.customers.s1.id, amount: 20000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  if (!c.held) throw new Error(`expected pcC held: ${ex(c)}`);
  S.ids.pcC = c.pendingChange.id;

  const r1now = rt.need(await rt.automation.get(admin2, S.ids.rule1), 'R1 now');
  rt.need(await rt.automation.update(admin2, S.ids.rule1, rt.rule({
    name: `RNew ${tag}`, trigger: r1now.trigger, regionIds: r1now.regionIds, condition: r1now.condition, actions: r1now.actions,
    enabled: r1now.enabled, version: r1now.version,
  })), 'rename R1');
  const r2 = rt.need(await rt.automation.create(admin2, rt.rule({
    name: `RAfter ${tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [R.NORTH.id],
    condition: { op: 'AND', children: [{ filter: 'balance:gt:0' }] },
    actions: [rt.action.task({ title: `R2 {{Invoice.Number}} ${tag}`, assignees: [rt.tok.user(S.adminId)] })],
  })), 'rule R2');
  S.ids.rule2 = r2.id;

  const mv = await rt.moveCustomerRegion(admin2, S.customers.n2.id, R.WEST.id, 'explore move');
  if (mv.status !== 200) throw new Error(`move n2: ${ex(mv)}`);

  const tAnow = rt.need(await G(admin2, `/api/tasks/${S.ids.taskA}`), 'task A now');
  rt.need(await rt.tasks.setStatus(admin2, S.ids.taskA, 'DONE', tAnow.version), 'task A done');
  const tB = rt.need(await rt.createTask(admin2, { entityType: 'INVOICE', entityId: S.invoices.n1[1], title: `New task ${tag}`, assignees: [rt.tok.user(U.nora.id)] }), 'task B');
  S.ids.taskB = tB.id;

  const pay = await rt.createPayment(bob, { customerId: S.customers.n1.id, amount: 500, invoiceIds: [S.invoices.n1[0]], collectionPocUserId: U.bob.id });
  if (pay.status !== 200) throw new Error(`bob payment: ${ex(pay)}`);
  S.ids.payBob = pay.json.id;

  const runB = rt.need(await rt.automation.run(admin2, S.ids.rule1, {}), 'run R1 (B)', [200, 202]);
  S.ids.runB = runB.runId;
  await rt.waitForRun(admin2, runB.runId, { timeoutMs: 90000 });
  return S;
}

// ---- API cases ----------------------------------------------------------------------------
async function apiCases(S) {
  const admin = await rt.adminToken();
  const tk = {};
  for (const u of ['nora', 'sam', 'bob', 'wendy']) tk[u] = await rt.login(u);
  tk.vera = await rt.login(S.users.vera.username);
  tk.cust = await rt.login(S.users.cust.username);
  const RG = S.regions; const T = S.T;

  await check({ id: 'ASO-X-01', feature: 'asOf parsing', prd: 'B3', title: 'Non-ISO or impossible asOf values are refused with 400 AS_OF_INVALID and the documented message',
    steps: 'GET /api/invoices?asOf=<v> for v in 2026-9-1, <yesterday>T00:00:00Z, yyyyMMdd, 2025-02-29, 2026-13-01, yesterday',
    expected: 'DESIGN §5.2.2/§6.17: each 400 {error:AS_OF_INVALID, message:"asOf must be a date (yyyy-MM-dd)"}' }, async () => {
    const bad = ['2026-9-1', `${S.yesterday}T00:00:00Z`, S.yesterday.replace(/-/g, ''), '2025-02-29', '2026-13-01', 'yesterday'];
    const out = [];
    for (const v of bad) {
      const r = await G(admin, '/api/invoices', { asOf: v, size: 10 });
      out.push({ v, s: r.status, e: r.json?.error, m: r.json?.message });
    }
    const ok = out.every((o) => o.s === 400 && o.e === 'AS_OF_INVALID' && o.m === 'asOf must be a date (yyyy-MM-dd)');
    return { ok, actual: JSON.stringify(out) };
  });

  await check({ id: 'ASO-X-02', feature: 'regionIds with asOf', prd: 'B1', title: 'regionIds edge values under a past date: empty = all mine, 0/negative/text = 400 REGION_IDS_INVALID, blanks between commas skipped, an ungranted region = empty 200',
    steps: 'admin GET /api/invoices?asOf=T&regionIds= ; &regionIds=0 ; &regionIds=-3 ; &regionIds=abc ; &regionIds=N,,S ; nora &regionIds=WEST',
    expected: 'DESIGN §5.2.2/§6.0: empty -> null (all mine, envelope regionIds = the 4 granted); non-positive or non-numeric -> 400 REGION_IDS_INVALID; blanks skipped; ungranted -> 200, totalElements 0, regionIds []' }, async () => {
    const q = (s) => rt.api('GET', `/api/invoices?size=10&asOf=${T}&${s}`, { token: admin });
    const empty = await q('regionIds=');
    const zero = await q('regionIds=0');
    const neg = await q('regionIds=-3');
    const txt = await q('regionIds=abc');
    const blanks = await q(`regionIds=${RG.NORTH.id},,${RG.SOUTH.id}`);
    const nw = await rt.api('GET', `/api/invoices?size=10&asOf=${T}&regionIds=${RG.WEST.id}`, { token: tk.nora });
    const a = {
      empty: [empty.status, (empty.json?.regionIds || []).length, empty.json?.asOf],
      zero: [zero.status, zero.json?.error], neg: [neg.status, neg.json?.error], txt: [txt.status, txt.json?.error, txt.json?.message],
      blanks: [blanks.status, blanks.json?.regionIds], noraWest: [nw.status, nw.json?.totalElements, nw.json?.regionIds],
    };
    const ok = a.empty[0] === 200 && a.empty[1] === 4 && a.empty[2] === T
      && a.zero[0] === 400 && a.zero[1] === 'REGION_IDS_INVALID' && a.neg[0] === 400 && a.neg[1] === 'REGION_IDS_INVALID'
      && a.txt[0] === 400 && a.txt[1] === 'REGION_IDS_INVALID'
      && a.blanks[0] === 200 && JSON.stringify([...(a.blanks[1] || [])].sort()) === JSON.stringify([RG.NORTH.id, RG.SOUTH.id].sort())
      && a.noraWest[0] === 200 && a.noraWest[1] === 0 && JSON.stringify(a.noraWest[2]) === '[]';
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-03', feature: 'History range', prd: 'B3', title: 'History range: with no lower bound any old day answers (empty); once history has a start, the range reports it, the day before is refused with AS_OF_BEFORE_HISTORY naming that day, and the first day itself answers',
    steps: 'GET /api/history/range (null start) + asOf=2000-01-01; set history_meta.started_at = <day-9> 13:45Z (e2e db); GET range; GET /api/customers?asOf=<day-10>; ?asOf=<day-9>; restore',
    expected: 'DESIGN §6.10/§6.17: range {historyStartsOn:<start>, latestAsOf:<yesterday>, timezone:UTC}; start-1 -> 400 AS_OF_BEFORE_HISTORY "History is available from <d MMM yyyy>"; start -> 200' }, async () => {
    // A database whose records all postdate the first boot has no lower bound (historyStartsOn null, §6.10). To reach
    // the bounded case, history_meta.started_at of this e2e database is set to 9 days ago for this case, then restored.
    const r0 = await G(admin, '/api/history/range');
    const old = rt.sql('select coalesce(started_at::text, \'NULL\') from history_meta where id = 1')[0][0];
    const oldOk = r0.json?.historyStartsOn === null && (await G(admin, '/api/customers', { asOf: '2000-01-01', size: 10 })).json?.totalElements === 0;
    rt.sql(`update history_meta set started_at = '${rt.day(-9)} 13:45:00+00' where id = 1`);
    let r, start, b, f;
    try {
      r = await G(admin, '/api/history/range');
      start = r.json?.historyStartsOn;
      const prev = rt.day(-10);
      b = await G(admin, '/api/customers', { asOf: prev, size: 10 });
      f = await G(admin, '/api/customers', { asOf: rt.day(-9), size: 10 });
    } finally {
      rt.sql(old === 'NULL' ? 'update history_meta set started_at = null where id = 1' : `update history_meta set started_at = '${old}' where id = 1`);
    }
    const okUnbounded = oldOk;
    const ok = okUnbounded && start === rt.day(-9) && r.json.latestAsOf === S.yesterday && r.json.timezone === 'UTC' && b.status === 400 && b.json?.error === 'AS_OF_BEFORE_HISTORY'
      && b.json?.message === `History is available from ${label(start || rt.day(-9))}` && f.status === 200;
    return { ok, actual: `unbounded: range ${JSON.stringify(r0.json)}, asOf=2000-01-01 empty=${oldOk}; bounded (started_at=${rt.day(-9)} 13:45Z): range ${JSON.stringify(r.json)}; start-1 ${ex(b, 200)}; start ${f.status} total=${f.json?.totalElements}` };
  });

  await check({ id: 'ASO-X-04', feature: 'Today vs yesterday', prd: 'B3', title: 'asOf=today is live (header "live", envelope asOf null, today\'s approved invoice listed); asOf=yesterday is historical and differs from live by exactly that invoice',
    steps: 'admin GET /api/invoices?asOf=<today>; ?asOf=<yesterday>; no asOf. invB = the invoice created today by approving pcB',
    expected: 'D9: asOf==today -> live; yesterday -> header <yesterday>, envelope asOf <yesterday>, invB absent; totalElements(live) - totalElements(yesterday) = 1' }, async () => {
    const live = await rt.list(admin, '/api/invoices');
    const tod = await rt.list(admin, '/api/invoices', {}, { asOf: S.today });
    const yes = await rt.list(admin, '/api/invoices', {}, { asOf: S.yesterday });
    const a = {
      today: [tod.status, tod.headers['x-view-as-of'], tod.json?.asOf, tod.json?.totalElements, ids(tod).includes(S.ids.invB)],
      yesterday: [yes.status, yes.headers['x-view-as-of'], yes.json?.asOf, yes.json?.totalElements, ids(yes).includes(S.ids.invB)],
      live: [live.json?.totalElements, ids(live).includes(S.ids.invB)], invB: S.ids.invB,
    };
    const ok = a.today[0] === 200 && a.today[1] === 'live' && a.today[2] === null && a.today[3] === a.live[0] && a.today[4] === true
      && a.yesterday[1] === S.yesterday && a.yesterday[2] === S.yesterday && a.yesterday[4] === false && a.live[0] - a.yesterday[3] === 1;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-05', feature: 'Rights on the region at T', prd: 'B3', title: 'Current grants apply to the region a record belonged to at T: after n2 moved North->West, nora (North manage, no West) opens n2 as of T (region North, read only) but not live; wendy (West only) the reverse; vera (North read) lists its invoices only as of T',
    steps: 'GET /api/customers/{n2} as nora and wendy, with asOf=T and live; vera GET /api/invoices?filter=customerId:eq:n2 with asOf=T and live',
    expected: 'D9 "caller\'s current grants applied to the record\'s region at T"; B1 "nothing in a third": nora T 200 regionCode NORTH canManage false / live 404; wendy T 404 / live 200 WEST; vera T 2 invoices / live 0' }, async () => {
    const n2 = S.customers.n2.id;
    const nT = await G(tk.nora, `/api/customers/${n2}`, { asOf: T });
    const nL = await G(tk.nora, `/api/customers/${n2}`);
    const wT = await G(tk.wendy, `/api/customers/${n2}`, { asOf: T });
    const wL = await G(tk.wendy, `/api/customers/${n2}`);
    const vT = await G(tk.vera, '/api/invoices', { filter: `customerId:eq:${n2}`, asOf: T, size: 10 });
    const vL = await G(tk.vera, '/api/invoices', { filter: `customerId:eq:${n2}`, size: 10 });
    const a = { noraT: [nT.status, nT.json?.regionCode, nT.json?.canManage], noraLive: nL.status, wendyT: wT.status, wendyLive: [wL.status, wL.json?.regionCode],
      veraT: [vT.status, vT.json?.totalElements], veraLive: [vL.status, vL.json?.totalElements] };
    const ok = a.noraT[0] === 200 && a.noraT[1] === 'NORTH' && a.noraT[2] === false && a.noraLive === 404 && a.wendyT === 404
      && a.wendyLive[0] === 200 && a.wendyLive[1] === 'WEST' && a.veraT[1] === 2 && a.veraLive[1] === 0;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-06', feature: 'Dashboard by region at T', prd: 'B3', title: 'Top-outstanding customers as of T narrowed to North include n2 (then North) and narrowed to West exclude it; live the reverse; scope echoes asOf and regions',
    steps: 'admin GET /api/dashboard/top-outstanding-customers?limit=20&regionIds=N|W with asOf=T and live',
    expected: 'B1 "every dashboard figure answers which regions" + B3: T/North has n2, T/West lacks n2; live/West has n2, live/North lacks n2; scope.asOf=T, scope.regionIds=[asked]' }, async () => {
    const top = (reg, asOf) => G(admin, '/api/dashboard/top-outstanding-customers', { limit: 20, regionIds: [reg], asOf });
    const has = (r) => (r.json?.customers || []).some((c) => c.customerId === S.customers.n2.id);
    const tN = await top(RG.NORTH.id, T); const tW = await top(RG.WEST.id, T);
    const lN = await top(RG.NORTH.id); const lW = await top(RG.WEST.id);
    const a = { TN: [tN.status, has(tN), tN.json?.scope], TW: [tW.status, has(tW)], LN: has(lN), LW: has(lW) };
    const ok = a.TN[0] === 200 && a.TN[1] === true && a.TW[1] === false && a.LN === false && a.LW === true
      && a.TN[2]?.asOf === T && JSON.stringify(a.TN[2]?.regionIds) === JSON.stringify([RG.NORTH.id]);
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-07', feature: 'Dashboard = list at T', prd: 'B3', title: 'As of T, North\'s outstanding-by-age total equals the sum of North invoice balances as of T (cancelled excluded)',
    steps: 'admin GET /api/dashboard/outstanding-by-age?regionIds=N&asOf=T; GET /api/invoices?regionIds=N&asOf=T (all pages) sum balance where status != CANCELLED',
    expected: 'B1/B3 "every list, count and dashboard figure" agree for the same (regions, date)' }, async () => {
    const d = await G(admin, '/api/dashboard/outstanding-by-age', { regionIds: [RG.NORTH.id], asOf: T });
    const l = await rt.list(admin, '/api/invoices', {}, { regionIds: [RG.NORTH.id], asOf: T });
    const buckets = d.json?.buckets || [];
    const key = buckets[0] ? Object.keys(buckets[0]).find((k) => /amount|outstanding|total/i.test(k)) : null;
    const dash = buckets.reduce((s, b) => s + Number(b[key] || 0), 0);
    const list = (l.json?.content || []).filter((i) => i.status !== 'CANCELLED').reduce((s, i) => s + Number(i.balance || 0), 0);
    const ok = d.status === 200 && key && Math.abs(dash - list) < 0.005;
    return { ok, actual: `dashboard sum(${key})=${dash}, list sum balance=${list}, list rows=${l.json?.totalElements}; buckets=${JSON.stringify(buckets).slice(0, 400)}` };
  });

  await check({ id: 'ASO-X-08', feature: 'Held cancel at T', prd: 'B2', title: 'A cancel held before T and rejected after T: as of T the invoice is not cancelled, its list row carries pendingApproval, matches filter pendingApproval:eq:true and for-record lists it PENDING; live none of that',
    steps: 'admin GET /api/invoices/{inv6000}, /api/invoices?filter=pendingApproval:eq:true&filter=id:eq:{inv6000}, /api/approvals/for-record?entityType=INVOICE&entityId={inv6000} — asOf=T and live',
    expected: 'B2 "pending change visible ... without having taken effect" + B3 "which approvals were outstanding" (§5.11.10); pendingApproval is a list-row field (§6.5 "list only")' }, async () => {
    const id = S.ids.inv6000;
    const dT = await G(admin, `/api/invoices/${id}`, { asOf: T }); const dL = await G(admin, `/api/invoices/${id}`);
    const fT = await G(admin, '/api/invoices', { filter: ['pendingApproval:eq:true', `id:eq:${id}`], asOf: T, size: 10 });
    const fL = await G(admin, '/api/invoices', { filter: ['pendingApproval:eq:true', `id:eq:${id}`], size: 10 });
    const rT = await rt.approvals.forRecord(admin, 'INVOICE', id, { asOf: T }); const rL = await rt.approvals.forRecord(admin, 'INVOICE', id);
    const pT = (rT.json || []).filter((b) => b.id === S.ids.pcCancel).map((b) => [b.status, b.statusAt]);
    const a = { detailT: [dT.status, dT.json?.status, dT.json?.pendingApproval], detailLive: [dL.json?.status, dL.json?.pendingApproval],
      filterT: fT.json?.totalElements, filterLive: fL.json?.totalElements, forRecordT: [rT.status, pT], forRecordLive: [rL.status, (rL.json || []).filter((b) => (b.statusAt || b.status) === 'PENDING').length] };
    const rowT = (await G(admin, '/api/invoices', { filter: `id:eq:${id}`, asOf: T, size: 10 })).json?.content?.[0];
    const rowL = (await G(admin, '/api/invoices', { filter: `id:eq:${id}`, size: 10 })).json?.content?.[0];
    a.rowT = [rowT?.status, rowT?.pendingApproval]; a.rowLive = [rowL?.status, rowL?.pendingApproval];
    const ok = a.detailT[0] === 200 && a.detailT[1] !== 'CANCELLED' && a.detailLive[0] !== 'CANCELLED' && a.rowT[0] !== 'CANCELLED' && a.rowT[1] === true && a.rowLive[1] !== true
      && a.filterT === 1 && a.filterLive === 0 && pT.length === 1 && pT[0][1] === 'PENDING' && a.forRecordLive[1] === 0;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-09', feature: 'Approved-after-T create', prd: 'B2', title: 'A create held before T and approved after T: as of T the approval reads PENDING with the decision hidden, is read only, and the invoice it made does not exist yet',
    steps: 'sam GET /api/approvals/{pcB}?asOf=T; GET /api/invoices/{invB}?asOf=T; live counterparts',
    expected: 'r1 userDecision ASO-R1-2: statusAt PENDING; decidedBy/decidedAt/decisionNote/result null; status stays live APPROVED; blockedReason "Read-only in the as-of view"; canApprove false. invB 404 at T, 200 live' }, async () => {
    const dT = await rt.approvals.get(tk.sam, S.ids.pcB, { asOf: T }); const dL = await rt.approvals.get(tk.sam, S.ids.pcB);
    const iT = await G(tk.sam, `/api/invoices/${S.ids.invB}`, { asOf: T }); const iL = await G(tk.sam, `/api/invoices/${S.ids.invB}`);
    const j = dT.json || {};
    const a = { T: [dT.status, j.status, j.statusAt, j.decidedBy, j.decidedAt, j.decisionNote, j.result, j.blockedReason, j.canApprove, j.canReject],
      live: [dL.json?.status, !!dL.json?.decidedAt, dL.json?.decisionNote], invB: [iT.status, iL.status] };
    const ok = dT.status === 200 && j.status === 'APPROVED' && j.statusAt === 'PENDING' && j.decidedBy == null && j.decidedAt == null
      && j.decisionNote == null && j.result == null && j.blockedReason === 'Read-only in the as-of view' && j.canApprove === false && j.canReject === false
      && a.live[0] === 'APPROVED' && a.live[1] && iT.status === 404 && iL.status === 200;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-10', feature: 'Outstanding approvals at T', prd: 'B3', title: 'The approvals summary and list as of T count exactly the changes outstanding then (pcA, pcB, pcCancel) and not the one requested after T (pcC, 404 as of T)',
    steps: 'admin GET /api/approvals?view=ALL&asOf=T, /api/approvals/summary?asOf=T, /api/approvals/{pcC}?asOf=T; live counterparts',
    expected: 'D8 interval predicate: T -> {pcA,pcB,pcCancel} all statusAt PENDING, pendingCount 3; live -> pending {pcA,pcC}, pendingCount 2; pcC at T 404' }, async () => {
    const mine = [S.ids.pcA, S.ids.pcB, S.ids.pcCancel, S.ids.pcC];
    const lT = await rt.approvals.list(admin, { view: 'ALL' }, { asOf: T }); const lL = await rt.approvals.list(admin, { view: 'ALL' });
    const sT = await rt.approvals.summary(admin, { asOf: T }); const sL = await rt.approvals.summary(admin);
    const cT = await rt.approvals.get(admin, S.ids.pcC, { asOf: T });
    const inT = (lT.json?.content || []).filter((b) => mine.includes(b.id)).map((b) => `${b.id}:${b.statusAt}`);
    const pendL = (lL.json?.content || []).filter((b) => mine.includes(b.id) && b.status === 'PENDING').map((b) => b.id);
    const a = { listT: inT, pendingLive: pendL, summaryT: sT.json, summaryLive: sL.json, pcC_T: cT.status };
    const ok = inT.length === 3 && inT.every((s) => s.endsWith(':PENDING')) && !inT.some((s) => s.startsWith(`${S.ids.pcC}:`))
      && JSON.stringify(pendL.sort()) === JSON.stringify([S.ids.pcA, S.ids.pcC].sort())
      && sT.json?.pendingCount === 3 && sL.json?.pendingCount === 2 && cT.status === 404;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-11', feature: 'Decisions refused at T', prd: 'B2', title: 'Approve, reject and withdraw sent with a past asOf are refused with 400 AS_OF_READ_ONLY ("You are viewing <d MMM yyyy>. Changes can only be made in the current view.") and pcA stays PENDING',
    steps: 'admin POST /api/approvals/{pcA}/approve?asOf=T; admin POST .../reject?asOf=T; sam POST .../withdraw?asOf=T; GET /api/approvals/{pcA}',
    expected: '§5.11.10 "Approve, reject and withdraw are refused (400)"; §6.17 AS_OF_READ_ONLY message' }, async () => {
    const msg = `You are viewing ${label(T)}. Changes can only be made in the current view.`;
    const ap = await rt.api('POST', `/api/approvals/${S.ids.pcA}/approve`, { token: admin, query: { asOf: T }, body: { note: 'x' } });
    const rj = await rt.api('POST', `/api/approvals/${S.ids.pcA}/reject`, { token: admin, query: { asOf: T }, body: { note: 'x' } });
    const wd = await rt.api('POST', `/api/approvals/${S.ids.pcA}/withdraw`, { token: tk.sam, query: { asOf: T }, body: {} });
    const now = await rt.approvals.get(admin, S.ids.pcA);
    const a = [ap, rj, wd].map((r) => [r.status, r.json?.error, r.json?.message]);
    const ok = a.every((x) => x[0] === 400 && x[1] === 'AS_OF_READ_ONLY' && x[2] === msg) && now.json?.status === 'PENDING';
    return { ok, actual: `${JSON.stringify(a)}; pcA now ${now.json?.status}` };
  });

  await check({ id: 'ASO-X-12', feature: 'Audit at T', prd: 'B3', title: 'The invoice audit trail as of T shows the cancel request still pending and no rejection; live shows the rejection',
    steps: 'admin GET /api/audit?entityType=INVOICE&entityId={inv6000} with asOf=T and live',
    expected: 'D8 audit_logs created_at <= p; B3 "which approvals were outstanding": T has CHANGE_REQUESTED (pendingStatus PENDING) and no CHANGE_REJECTED; live has CHANGE_REJECTED' }, async () => {
    const aT = await G(admin, '/api/audit', { entityType: 'INVOICE', entityId: S.ids.inv6000, asOf: T });
    const aL = await G(admin, '/api/audit', { entityType: 'INVOICE', entityId: S.ids.inv6000 });
    const acts = (r) => (Array.isArray(r.json) ? r.json : []).map((e) => `${e.action}${e.pendingStatus ? '/' + e.pendingStatus : ''}`);
    const t = acts(aT); const l = acts(aL);
    const reqT = t.find((x) => x.startsWith('CHANGE_REQUESTED'));
    const ok = aT.status === 200 && reqT === 'CHANGE_REQUESTED/PENDING' && !t.some((x) => x.startsWith('CHANGE_REJECTED')) && l.some((x) => x.startsWith('CHANGE_REJECTED'));
    return { ok, actual: `T[${aT.status}] ${t.join(',')} | live ${l.join(',')}` };
  });

  await check({ id: 'ASO-X-13', feature: 'Tasks at T', prd: 'A6', title: 'Tasks as of T: the task done after T reads OPEN, the task created after T is absent; nora\'s "my tasks" as of T lists the old task only',
    steps: 'admin GET /api/tasks?filter=id:eq:{A} and ?filter=id:eq:{B} with asOf=T and live; nora GET /api/tasks/mine?asOf=T',
    expected: 'D8 Task versioned; A6 status as of T: A OPEN at T / DONE live; B absent at T / present live; nora mine at T contains A (OPEN), not B' }, async () => {
    const both = async (q) => {
      const x = await G(admin, '/api/tasks', { filter: `id:eq:${S.ids.taskA}`, size: 10, ...q });
      const y = await G(admin, '/api/tasks', { filter: `id:eq:${S.ids.taskB}`, size: 10, ...q });
      return { status: `${x.status}/${y.status}`, json: { content: [...(x.json?.content || []), ...(y.json?.content || [])] } };
    };
    const lT = await both({ asOf: T }); const lL = await both({});
    const mT = await G(tk.nora, '/api/tasks/mine', { asOf: T, size: 50 });
    const m = (r) => (r.json?.content || []).map((x) => `${x.id}:${x.status}`);
    const a = { T: [lT.status, m(lT)], live: m(lL), noraMineT: [mT.status, m(mT).filter((s) => [S.ids.taskA, S.ids.taskB].includes(Number(s.split(':')[0])))] };
    const ok = JSON.stringify(a.T[1]) === JSON.stringify([`${S.ids.taskA}:OPEN`])
      && a.live.includes(`${S.ids.taskA}:DONE`) && a.live.some((s) => s.startsWith(`${S.ids.taskB}:`))
      && JSON.stringify(a.noraMineT[1]) === JSON.stringify([`${S.ids.taskA}:OPEN`]);
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-14', feature: 'Rule revisions at T', prd: 'A1', title: 'As of T a rule reads as its revision then (old name, flags false); a rule created after T is 404; the rules list as of T shows only the old revision; lastRunAt sort refused for past dates',
    steps: 'admin GET /api/automation/rules/{R1}?asOf=T, /rules/{R2}?asOf=T, /rules?filter=name:contains:<tag>&asOf=T, /rules?sort=lastRunAt,desc&asOf=T',
    expected: 'D9/§5.10.10: R1 name "ROld <tag>", canEdit/canRun false; live "RNew <tag>" canEdit true; R2 404 at T; list T = [ROld]; sort lastRunAt -> 400 "This sort isn\'t available for past dates"' }, async () => {
    const r1T = await rt.automation.get(admin, S.ids.rule1, { asOf: T }); const r1L = await rt.automation.get(admin, S.ids.rule1);
    const r2T = await rt.automation.get(admin, S.ids.rule2, { asOf: T });
    const lT = await rt.automation.list(admin, { filter: `name:contains:${S.tag}` }, { asOf: T });
    const sT = await rt.automation.list(admin, { sort: 'lastRunAt,desc' }, { asOf: T });
    const a = { r1T: [r1T.status, r1T.json?.name, r1T.json?.canEdit, r1T.json?.canRun], r1L: [r1L.json?.name, r1L.json?.canEdit], r2T: r2T.status,
      listT: [lT.status, (lT.json?.content || []).map((x) => x.name)], sortT: [sT.status, sT.json?.message] };
    const ok = a.r1T[0] === 200 && a.r1T[1] === `ROld ${S.tag}` && a.r1T[2] === false && a.r1T[3] === false && a.r1L[0] === `RNew ${S.tag}` && a.r1L[1] === true
      && a.r2T === 404 && JSON.stringify(a.listT[1]) === JSON.stringify([`ROld ${S.tag}`]) && a.sortT[0] === 400;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-15', feature: 'Runs at T', prd: 'A5', title: 'The rule\'s runs as of T list only the run made before T, with statusAt; live lists both',
    steps: 'admin GET /api/automation/rules/{R1}/runs?asOf=T and live',
    expected: '§5.10.10: runs filtered createdAt <= p; statusAt set in historical mode; live has runA and runB' }, async () => {
    const rT = await rt.api('GET', `/api/automation/rules/${S.ids.rule1}/runs`, { token: admin, query: { size: 50, asOf: T } });
    const rL = await rt.automation.runs(admin, S.ids.rule1);
    const t = (rT.json?.content || []).map((x) => `${x.id}:${x.status}/${x.statusAt}`);
    const l = (rL.json?.content || []).map((x) => x.id);
    const ok = rT.status === 200 && t.length === 1 && t[0].startsWith(`${S.ids.runA}:`) && !/\/(null|undefined)$/.test(t[0])
      && l.includes(S.ids.runA) && l.includes(S.ids.runB);
    return { ok, actual: `T ${rT.status} ${JSON.stringify(t)}; live ${JSON.stringify(l)}; runA=${S.ids.runA} runB=${S.ids.runB}` };
  });

  await check({ id: 'ASO-X-16', feature: 'Rule preview at T', prd: 'A2', title: 'Previewing a rule as of T evaluates the condition on the data of T (balances, region at T) and omits action rendering with the note; live evaluates today',
    steps: 'admin POST /api/automation/rules/preview {rule: NORTH, INVOICE, balance:gt:0, recordId: n1 inv#1, sampleSize 50} with asOf=T and live; GET /rules/{R2}/preview?asOf=T',
    expected: 'D10: T matchCount 4 (n1 x2, n2 x2 in North then), record.matches true, record.actions null, asOf T, asOfNote "Actions are shown only for today\'s view"; live matchCount 1 (n1 inv#2 only: n1 inv#1 paid, n2 moved), record.matches false; R2 preview at T -> 404' }, async () => {
    const body = {
      rule: rt.rule({ name: `Preview ${S.tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [RG.NORTH.id],
        condition: { op: 'AND', children: [{ filter: 'balance:gt:0' }] }, actions: [rt.action.task({ title: 'P {{Invoice.Number}}', assignees: [rt.tok.user(S.adminId)] })] }),
      recordId: S.invoices.n1[0], sampleSize: 50, evaluateAs: 'CALLER',
    };
    const pT = await rt.automation.preview(admin, body, { asOf: T }); const pL = await rt.automation.preview(admin, body);
    const r2 = await rt.api('GET', `/api/automation/rules/${S.ids.rule2}/preview`, { token: admin, query: { asOf: T } });
    const j = pT.json || {};
    const a = { T: [pT.status, j.matchCount, j.asOf, j.asOfNote, j.record?.matches, j.record?.actions], live: [pL.status, pL.json?.matchCount, pL.json?.record?.matches, Array.isArray(pL.json?.record?.actions)], r2T: r2.status };
    const ok = pT.status === 200 && j.matchCount === 4 && j.asOf === T && j.asOfNote === "Actions are shown only for today's view" && j.record?.matches === true
      && j.record?.actions == null && pL.json?.matchCount === 1 && pL.json?.record?.matches === false && r2.status === 404;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-17', feature: 'Overdue at T', prd: 'B3', title: 'An invoice due between T and today is not overdue as of T and is overdue live (field and filter)',
    steps: 'admin GET /api/invoices/{invDue} and /api/invoices?filter=overdue:eq:true&filter=id:eq:{invDue}, asOf=T and live',
    expected: 'D9 "today in as-of mode is the as-of date; drives overdue": T overdue false, filter 0; live overdue true, filter 1' }, async () => {
    const id = S.ids.invDue;
    const dT = await G(admin, `/api/invoices/${id}`, { asOf: T }); const dL = await G(admin, `/api/invoices/${id}`);
    const fT = await G(admin, '/api/invoices', { filter: ['overdue:eq:true', `id:eq:${id}`], asOf: T, size: 10 });
    const fL = await G(admin, '/api/invoices', { filter: ['overdue:eq:true', `id:eq:${id}`], size: 10 });
    const a = { dueDate: dL.json?.dueDate, T: [dT.status, dT.json?.overdue, fT.json?.totalElements], live: [dL.json?.overdue, fL.json?.totalElements] };
    const ok = a.T[1] === false && a.T[2] === 0 && a.live[0] === true && a.live[1] === 1;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-18', feature: 'Writes refused at T', prd: 'B3', title: 'Past-date writes across features (invoice PATCH, task create, rule Run now, customer region move) are all 400 AS_OF_READ_ONLY and change nothing',
    steps: 'admin with asOf=T: PATCH /api/invoices/{n1 inv#2} {notes}; POST /api/tasks; POST /api/automation/rules/{R1}/run; POST /api/customers/{n1}/move-region; then check invoice notes, task count, run count, n1 region',
    expected: 'D9/§6.1: every new non-GET is WRITE -> 400 AS_OF_READ_ONLY; nothing changes' }, async () => {
    const inv = S.invoices.n1[1];
    const before = { inv: (await G(admin, `/api/invoices/${inv}`)).json, tasks: (await G(admin, '/api/tasks', { size: 10 })).json?.totalElements,
      runs: (await rt.automation.runs(admin, S.ids.rule1)).json?.totalElements, n1: (await G(admin, `/api/customers/${S.customers.n1.id}`)).json };
    const r = [
      await rt.api('PATCH', `/api/invoices/${inv}`, { token: admin, query: { asOf: T }, body: { notes: 'as-of write', version: before.inv.version } }),
      await rt.api('POST', '/api/tasks', { token: admin, query: { asOf: T }, body: { entityType: 'INVOICE', entityId: inv, title: 'as-of task', dueDate: rt.day(3), assignees: [] } }),
      await rt.api('POST', `/api/automation/rules/${S.ids.rule1}/run`, { token: admin, query: { asOf: T }, body: {} }),
      await rt.api('POST', `/api/customers/${S.customers.n1.id}/move-region`, { token: admin, query: { asOf: T }, body: { regionId: RG.SOUTH.id, version: before.n1.version, reason: 'x' } }),
    ].map((x) => [x.status, x.json?.error]);
    await rt.sleep(2500);
    const after = { inv: (await G(admin, `/api/invoices/${inv}`)).json, tasks: (await G(admin, '/api/tasks', { size: 10 })).json?.totalElements,
      runs: (await rt.automation.runs(admin, S.ids.rule1)).json?.totalElements, n1: (await G(admin, `/api/customers/${S.customers.n1.id}`)).json };
    const ok = r.every((x) => x[0] === 400 && x[1] === 'AS_OF_READ_ONLY') && after.inv.notes === before.inv.notes && after.tasks === before.tasks
      && after.runs === before.runs && after.n1.regionId === before.n1.regionId;
    return { ok, actual: `${JSON.stringify(r)}; notes ${before.inv.notes}->${after.inv.notes}; tasks ${before.tasks}->${after.tasks}; runs ${before.runs}->${after.runs}; n1 region ${before.n1.regionId}->${after.n1.regionId}` };
  });

  await check({ id: 'ASO-X-19', feature: 'Customer login at T', prd: 'B3', title: 'A customer login as of T sees only its own invoices of then (not the one approved after T), ignores regionIds, and gets 404 for another customer\'s invoice',
    steps: 'cust (s1) GET /api/invoices?asOf=T, ?asOf=T&regionIds=NORTH, live; GET /api/invoices/{n1 inv#1}?asOf=T',
    expected: 'D9/§5.2.2: customer logins honour asOf, ignore regionIds; T -> s1\'s 2 seed invoices; live -> 3 (incl. invB); other customer 404' }, async () => {
    const t = await G(tk.cust, '/api/invoices', { asOf: T, size: 50 });
    const tn = await G(tk.cust, '/api/invoices', { asOf: T, regionIds: [RG.NORTH.id], size: 50 });
    const l = await G(tk.cust, '/api/invoices', { size: 50 });
    const o = await G(tk.cust, `/api/invoices/${S.invoices.n1[0]}`, { asOf: T });
    const cids = (r) => [...new Set((r.json?.content || []).map((x) => x.customerId))];
    const a = { T: [t.status, t.json?.totalElements, cids(t), ids(t).includes(S.ids.invB)], TNorth: [tn.status, tn.json?.totalElements], live: [l.json?.totalElements, ids(l).includes(S.ids.invB)], other: o.status };
    const ok = a.T[1] === 2 && JSON.stringify(a.T[2]) === JSON.stringify([S.customers.s1.id]) && a.T[3] === false && a.TNorth[1] === 2 && a.live[0] === 3 && a.live[1] === true && a.other === 404;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-20', feature: 'Export at T', prd: 'B3', title: 'The invoice CSV export as of T carries the then balance and status of an invoice paid after T',
    steps: 'admin POST /api/invoices/export?asOf=T {action:"export", ids:[n1 inv#1]} and live',
    expected: 'B3 "every list, report ... as of a date": T row Paid 500.00 / Balance 500.00 / PARTIALLY_PAID; live Paid 1000.00 / Balance 0.00 / FULLY_PAID' }, async () => {
    const body = { action: 'export', ids: [S.invoices.n1[0]] };
    const eT = await rt.api('POST', '/api/invoices/export', { token: admin, query: { asOf: T }, body });
    const eL = await rt.api('POST', '/api/invoices/export', { token: admin, body });
    const row = (r) => (r.text || '').split('\n').filter(Boolean)[1] || '';
    const ok = eT.status === 200 && /,1000\.00,500\.00,500\.00,PARTIALLY_PAID,/.test(row(eT)) && /,1000\.00,1000\.00,0\.00,FULLY_PAID,/.test(row(eL));
    return { ok, actual: `T[${eT.status} ${eT.headers['x-view-as-of']}] ${row(eT)} | live ${row(eL)}` };
  });

  await check({ id: 'ASO-X-21', feature: 'Payments at T', prd: 'B3', title: 'Payments as of T exclude the payment recorded after T and the invoice\'s then balance; the payments summary agrees with the list',
    steps: 'admin GET /api/payments?filter=customerId:eq:n1 with asOf=T and live; GET /api/payments/summary?filter=customerId:eq:n1&asOf=T',
    expected: 'T: 1 payment (seed), bob\'s payment absent; live: 2 payments' }, async () => {
    const f = `customerId:eq:${S.customers.n1.id}`;
    const t = await G(admin, '/api/payments', { filter: f, asOf: T, size: 10 }); const l = await G(admin, '/api/payments', { filter: f, size: 10 });
    const s = await G(admin, '/api/payments/summary', { filter: f, asOf: T });
    const a = { T: [t.status, t.json?.totalElements, ids(t).includes(S.ids.payBob)], live: [l.json?.totalElements, ids(l).includes(S.ids.payBob)], summaryT: [s.status, JSON.stringify(s.json).slice(0, 200)] };
    const ok = a.T[1] === 1 && a.T[2] === false && a.live[0] === 2 && a.live[1] === true && s.status === 200;
    return { ok, actual: JSON.stringify(a) };
  });

  await check({ id: 'ASO-X-22', feature: 'Flags at T', prd: 'B3', title: 'Capability flags are false as of T even for the admin (who can manage live)',
    steps: 'admin GET /api/invoices/{n1 inv#2}, /api/customers/{n1} with asOf=T and live',
    expected: '§6.0/D14: can* false in historical mode; live canManage true' }, async () => {
    const iT = await G(admin, `/api/invoices/${S.invoices.n1[1]}`, { asOf: T }); const iL = await G(admin, `/api/invoices/${S.invoices.n1[1]}`);
    const cT = await G(admin, `/api/customers/${S.customers.n1.id}`, { asOf: T }); const cL = await G(admin, `/api/customers/${S.customers.n1.id}`);
    const flags = (o) => Object.fromEntries(Object.entries(o || {}).filter(([k]) => /^can[A-Z]/.test(k)));
    const trues = (o) => Object.entries(flags(o)).filter(([, v]) => v === true).map(([k]) => k);
    const a = { invT: trues(iT.json), custT: trues(cT.json), invLive: flags(iL.json), custLive: flags(cL.json) };
    const ok = iT.status === 200 && cT.status === 200 && a.invT.length === 0 && a.custT.length === 0 && iL.json?.canManage === true && cL.json?.canManage === true;
    return { ok, actual: JSON.stringify(a) };
  });
}

// ---- UI cases -----------------------------------------------------------------------------
async function uiCases(S) {
  const T = S.T;
  const shot = (page, name) => rt.shot(page, SHOTS, name);
  const withApp = async (opts, fn) => {
    const app = await rt.openApp(opts);
    // The app lands on its home page after boot; navigate to the route explicitly.
    if (opts.path) await rt.go(app.page, opts.path, 4500);
    try { return await fn(app); } finally { await app.close(); }
  };

  await check({ id: 'ASO-XU-01', kind: 'UI', feature: 'Picker bounds', prd: 'B3', title: 'The as-of date picker refuses today (typed in input mode): it says the date is out of range, the chip stays "Today" and no request carries asOf=today',
    steps: 'admin opens /invoices; taps the "Today" chip; Switch to input; types today MM/DD/YYYY; taps View',
    expected: '§7.3.1: lastDate = latestAsOf (yesterday); "Today is never sent"' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: '/invoices' }, async ({ page, requests }) => {
    const chip = await rt.asOfChip(page);
    await rt.clickAt(page, chip.x, chip.y, 2000);
    await rt.tap(page, /Switch to input/, { wait: 800 });
    const [y, m, d] = S.today.split('-');
    await rt.fillField(page, /Enter Date|mm\/dd\/yyyy/i, `${m}/${d}/${y}`);
    await rt.tap(page, 'View', { wait: 2000 });
    const txt = await rt.screenText(page);
    const sh = await shot(page, `XU-01-${n}`);
    const outOfRange = txt.some((t) => /out of range/i.test(t));
    await rt.dismiss(page);
    await page.waitForTimeout(1000);
    const after = await rt.asOfChip(page);
    const sentToday = requests.some((r) => r.url.includes(`asOf=${S.today}`));
    const ok = outOfRange && /Today/.test(`${after?.label} ${after?.text}`) && !sentToday;
    return { ok, actual: `outOfRange=${outOfRange}; chip after="${after?.label || ''} ${after?.text || ''}"; request with asOf=today: ${sentToday}; text: ${txt.filter((t) => /range|Invalid|View|Cancel/i.test(t)).join(' | ')}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-02', kind: 'UI', feature: 'Banner and back to today', prd: 'B3', title: 'Picking T shows "Viewing <d MMM yyyy> (end of day, UTC) — read only" and sends asOf=T; "Back to today" removes the banner and the next requests carry no asOf',
    steps: 'admin /customers; chooseAsOf(T); read banner + requests; tap "Back to today"; read chip + requests',
    expected: '§7.3.1 body banner text and action; §7.1 asOf sent only when set' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: '/customers' }, async ({ page, requests }) => {
    const chipTxt = await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const banner = `Viewing ${label(T)} (end of day, UTC) — read only`;
    const hasBanner = await rt.exists(page, banner);
    const sentAsOf = requests.some((r) => r.url.startsWith('/api/customers') && r.url.includes(`asOf=${T}`));
    const sh1 = await shot(page, `XU-02a-${n}`);
    const mark = requests.length;
    await rt.tap(page, 'Back to today', { wait: 3000 });
    const gone = !(await rt.exists(page, banner));
    const chipAfter = await rt.asOfChip(page);
    const later = requests.slice(mark).filter((r) => r.url.startsWith('/api/customers'));
    const noAsOf = later.length > 0 && later.every((r) => !r.url.includes('asOf='));
    await shot(page, `XU-02b-${n}`);
    const ok = /As of/.test(chipTxt || '') && hasBanner && sentAsOf && gone && /Today/.test(`${chipAfter?.label} ${chipAfter?.text}`) && noAsOf;
    return { ok, actual: `chip="${chipTxt}"; banner=${hasBanner}; asOf sent=${sentAsOf}; after Back: banner gone=${gone}, chip="${chipAfter?.text || chipAfter?.label}", customer requests after=${later.length}, none with asOf=${noAsOf}`, screenshot: sh1 };
  }));

  await check({ id: 'ASO-XU-03', kind: 'UI', feature: 'Record created after T', prd: 'B3', title: 'Opening the invoice that was created after T (by approving pcB) while viewing T says "This invoice did not exist on <d MMM yyyy>."',
    steps: 'admin; chooseAsOf(T); go /invoices/{invB}',
    expected: '§7.4: a detail 404 while historical renders RecordUnavailable("This record did not exist on ...")' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: '/invoices' }, async ({ page }) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/invoices/${S.ids.invB}`, 4500);
    const txt = await rt.screenText(page);
    const sh = await shot(page, `XU-03-${n}`);
    const want = `This invoice did not exist on ${label(T)}.`;
    return { ok: txt.includes(want), actual: `looked for "${want}"; on screen: ${txt.slice(0, 25).join(' | ')}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-04', kind: 'UI', feature: 'Read-only invoice detail', prd: 'B3', title: 'An invoice that exists at T opens read-only as of T: banner shown, no Edit/Cancel/Record payment/Send email actions; live the admin has them',
    steps: 'admin; go /invoices/{n1 inv#2} live and collect actions; chooseAsOf(T); collect again',
    expected: '§7.4 every mutating control ANDs !isHistorical' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: `/invoices/${S.invoices.n1[1]}` }, async ({ page }) => {
    const acts = /^(Edit|Cancel invoice|Cancel|Record payment|Send email|Save|New task|Add task|Upload|Delete)$/;
    const liveNodes = (await rt.semantics(page)).filter((x) => x.role === 'button' && acts.test((x.label || x.text || '').trim())).map((x) => x.label || x.text);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const nodes = (await rt.semantics(page)).filter((x) => x.role === 'button' && acts.test((x.label || x.text || '').trim()) && !x.disabled).map((x) => x.label || x.text);
    const banner = await rt.exists(page, /read only/);
    const sh = await shot(page, `XU-04-${n}`);
    return { ok: liveNodes.length > 0 && nodes.length === 0 && banner, actual: `live actions: ${JSON.stringify(liveNodes)}; as-of enabled actions: ${JSON.stringify(nodes)}; banner=${banner}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-05', kind: 'UI', feature: 'Regions x as-of in the UI', prd: 'B1', title: 'nora narrows to North and views T: the customer list shows n2 (North then) and the request carries both regionIds and asOf; back to today, n2 is gone from North',
    steps: 'nora /customers; chooseRegions([NORTH]) (toggle off South); chooseAsOf(T); read list; Back to today; read list',
    expected: 'B1 "which regions am I asking about" + B3 region at T' }, async (n) => withApp({ username: 'nora', path: '/customers' }, async ({ page, requests }) => {
    // nora holds North + South; toggling South off leaves North only.
    await rt.chooseRegions(page, [/^SOUTH/]);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(2000);
    const tTxt = (await rt.screenText(page)).join('\n');
    const req = requests.filter((r) => r.url.startsWith('/api/customers?') || r.url.startsWith('/api/customers/summary')).pop();
    const sh = await shot(page, `XU-05a-${n}`);
    await rt.tap(page, 'Back to today', { wait: 3000 });
    const lTxt = (await rt.screenText(page)).join('\n');
    await shot(page, `XU-05b-${n}`);
    const n2 = S.customers.n2.name;
    const both = req && req.url.includes(`regionIds=${S.regions.NORTH.id}`) && req.url.includes(`asOf=${T}`);
    const ok = tTxt.includes(n2) && both && !lTxt.includes(n2);
    return { ok, actual: `T list has n2=${tTxt.includes(n2)}; last customers request=${req && req.url}; live(North) has n2=${lTxt.includes(n2)}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-06', kind: 'UI', feature: 'Approval as of T', prd: 'B2', title: 'sam opens pcB (approved after T) while viewing T: it reads Awaiting approval with "Read-only in the as-of view", no Approve/Reject, no decider',
    steps: 'sam; chooseAsOf(T); go /approvals/{pcB}',
    expected: 'r1 userDecision ASO-R1-2 + r3 RGN-R3-1 precedence; §7.4' }, async (n) => withApp({ username: 'sam', path: '/approvals' }, async ({ page }) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/approvals/${S.ids.pcB}`, 4500);
    const txt = await rt.screenText(page);
    const btn = (await rt.semantics(page)).filter((x) => x.role === 'button' && /^(Approve|Reject)$/.test(x.label || x.text || '') && !x.disabled);
    const sh = await shot(page, `XU-06-${n}`);
    const ok = txt.some((t) => /Awaiting approval/.test(t)) && txt.some((t) => /Read-only in the as-of view/.test(t)) && btn.length === 0 && !txt.some((t) => /^Decided/.test(t));
    return { ok, actual: `awaiting=${txt.some((t) => /Awaiting approval/.test(t))}; readOnly=${txt.some((t) => /Read-only in the as-of view/.test(t))}; approve/reject buttons=${btn.length}; decided shown=${txt.some((t) => /^Decided/.test(t))}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-07', kind: 'UI', feature: 'Task as of T', prd: 'A6', title: 'The task marked done after T reads Open as of T on its page and offers no status actions; live it reads Done',
    steps: 'admin go /tasks/{A} live; chooseAsOf(T); read status and buttons',
    expected: 'A6 status + B3; §7.4 canManageTasksProvider false when historical' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: `/tasks/${S.ids.taskA}` }, async ({ page }) => {
    const liveTxt = await rt.screenText(page);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(2000);
    const txt = await rt.screenText(page);
    const btn = (await rt.semantics(page)).filter((x) => x.role === 'button' && /^(Start|Mark done|Reopen|Cancel task|Edit|Save)$/.test(x.label || x.text || '') && !x.disabled).map((x) => x.label || x.text);
    const sh = await shot(page, `XU-07-${n}`);
    const ok = liveTxt.some((t) => /^Done$/.test(t)) && txt.some((t) => /^Open$/.test(t)) && !txt.some((t) => /^Done$/.test(t)) && btn.length === 0;
    return { ok, actual: `live Done=${liveTxt.some((t) => /^Done$/.test(t))}; T Open=${txt.some((t) => /^Open$/.test(t))}; T Done=${txt.some((t) => /^Done$/.test(t))}; T buttons=${JSON.stringify(btn)}; T text: ${txt.slice(0, 30).join(' | ')}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-08', kind: 'UI', feature: 'Rule editor as of T', prd: 'A1', title: 'The rule editor as of T shows the revision then ("ROld <tag>"), read only (no Save); live it shows "RNew <tag>"',
    steps: 'admin go /automation/{R1} live; chooseAsOf(T); read name and buttons',
    expected: 'D9 rule detail AWARE (revision at T); §7.4 rule editor read only' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: `/automation/${S.ids.rule1}` }, async ({ page }) => {
    const liveTxt = (await rt.semantics(page)).map((x) => `${x.label || ''} ${x.text || ''}`).join('\n');
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(2500);
    const nodes = await rt.semantics(page);
    const tTxt = nodes.map((x) => `${x.label || ''} ${x.text || ''}`).join('\n');
    const save = nodes.filter((x) => x.role === 'button' && /^(Save|Save changes|Run now|Disable|Enable|Delete)$/.test((x.label || x.text || '').trim()) && !x.disabled).map((x) => x.label || x.text);
    const sh = await shot(page, `XU-08-${n}`);
    const ok = liveTxt.includes(`RNew ${S.tag}`) && tTxt.includes(`ROld ${S.tag}`) && !tTxt.includes(`RNew ${S.tag}`) && save.length === 0;
    return { ok, actual: `live RNew=${liveTxt.includes(`RNew ${S.tag}`)}; T ROld=${tTxt.includes(`ROld ${S.tag}`)}; T RNew=${tTxt.includes(`RNew ${S.tag}`)}; enabled write buttons at T=${JSON.stringify(save)}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-09', kind: 'UI', feature: 'Logout clears the view date', prd: 'B3', title: 'After viewing T, logging out and signing in again comes back on "Today" with no banner and no asOf on requests',
    steps: 'login screen; uiLogin admin; chooseAsOf(T); Account menu -> Logout; uiLogin admin; read chip, banner, requests',
    expected: '§7.1 AuthController.logout calls clear(); scope lives in session memory only' }, async (n) => withApp({}, async ({ page, requests }) => {
    await rt.uiLogin(page, 'admin', 'admin123');
    await rt.chooseAsOf(page, T);
    const acct = await rt.find(page, /Account/);
    await rt.clickAt(page, acct.x, acct.y, 1500);
    await rt.press(page, /Logout/, { wait: 3000 });
    await rt.uiLogin(page, 'admin', 'admin123');
    await page.waitForTimeout(2000);
    const mark = requests.length;
    await rt.go(page, '/invoices', 4000);
    const chip = await rt.asOfChip(page);
    const banner = await rt.exists(page, /read only/);
    const later = requests.slice(mark);
    const sh = await shot(page, `XU-09-${n}`);
    const ok = /Today/.test(`${chip?.label} ${chip?.text}`) && !banner && later.length > 0 && later.every((r) => !r.url.includes('asOf='));
    return { ok, actual: `chip="${chip?.text || chip?.label}"; banner=${banner}; requests after re-login=${later.length}, with asOf=${later.filter((r) => r.url.includes('asOf=')).length}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-10', kind: 'UI', feature: 'Customer login', prd: 'B3', title: 'A customer login gets neither the as-of chip nor the region selector',
    steps: 'cust login; home page; look for chip and selector',
    expected: '§7.3.1 chip staff only; selector hidden for customer logins' }, async (n) => withApp({ username: S.users.cust.username }, async ({ page }) => {
    const chip = await rt.asOfChip(page);
    const sel = await rt.regionSelector(page);
    const sh = await shot(page, `XU-10-${n}`);
    return { ok: !chip && !sel, actual: `chip=${chip ? (chip.text || chip.label) : null}; selector=${sel ? sel.label : null}`, screenshot: sh };
  }));

  await check({ id: 'ASO-XU-11', kind: 'UI', feature: 'Invoice list at T', prd: 'B3', title: 'The invoice list as of T hides "New invoice" and does not list the invoice created after T; live it has both',
    steps: 'admin /invoices live: look for New invoice + invB number; chooseAsOf(T): look again',
    expected: '§7.4 list canManage ANDs !historical; B3 list membership at T' }, async (n) => withApp({ username: 'admin', password: 'admin123', path: '/invoices' }, async ({ page }) => {
    const invB = (await rt.api('GET', `/api/invoices/${S.ids.invB}`, { token: await rt.adminToken() })).json;
    const filterSearch = async () => {
      const f = (await rt.semantics(page)).find((x) => x.role === 'textbox' && /Search/i.test(x.label || ''));
      if (f) { await rt.clickAt(page, f.x, f.y, 300); await rt.typeText(page, invB.invoiceNumber, { clear: true }); await page.waitForTimeout(2500); }
      return !!f;
    };
    const searched = await filterSearch();
    const liveTxt = (await rt.screenText(page)).join('\n');
    const liveNew = await rt.exists(page, /^New invoice$/);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(2500);
    const tTxt = (await rt.screenText(page)).join('\n');
    const tNew = await rt.exists(page, /^New invoice$/);
    const sh = await shot(page, `XU-11-${n}`);
    const ok = liveNew && !tNew && liveTxt.includes(invB.invoiceNumber) && !tTxt.includes(invB.invoiceNumber);
    return { ok, actual: `search box=${searched}; live New invoice=${liveNew}, invB ${invB.invoiceNumber} listed=${liveTxt.includes(invB.invoiceNumber)}; T New invoice=${tNew}, invB listed=${tTxt.includes(invB.invoiceNumber)}`, screenshot: sh };
  }));
}

(async () => {
  let S;
  if (process.env.EXPLORE_REUSE && fs.existsSync(STATE)) {
    S = JSON.parse(fs.readFileSync(STATE, 'utf8'));
    console.log(`[explore] reusing ${STATE} (tag ${S.tag})`);
  } else {
    S = await build();
    fs.writeFileSync(STATE, JSON.stringify(S, null, 2));
    console.log(`[explore] scenario built (tag ${S.tag}, T ${S.T}) -> ${STATE}`);
  }
  const only = process.env.EXPLORE_ONLY;
  try {
    if (!only || only === 'api') await apiCases(S);
    if (!only || only === 'ui') await uiCases(S);
  } finally { save(); }
})().catch((e) => { console.error(e); try { save(); } catch { /* ignore */ } process.exit(1); });
