// Final exploratory pass, area "regression" (env index 6). Written from the PRD and DESIGN §6/§7 only.
//   source <e2e>/runtime/regression/env.sh && node final-explore.js [--case <regex>] [--only api|ui]
// Results: runs/final/regression-explore.json; screenshots: runs/final/regression/shots/explore/.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');

const OUT = path.join(rt.HOME, 'runs', 'final', 'regression-explore.json');
const SHOTS = path.join(rt.HOME, 'runs', 'final', 'regression', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });
const args = process.argv.slice(2);
const argv = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : null; };
const caseRe = argv('--case') ? new RegExp(argv('--case')) : null;
const only = argv('--only');

const cases = [];
function rec(o) {
  const status = o.status || (o.ok ? 'PASS' : 'FAIL');
  const c = {
    id: o.id, feature: o.feature || '', kind: o.kind || 'API', prd: o.prd || '', title: o.title || '', status,
    severity: status === 'FAIL' ? (o.severity || 'medium') : '',
    steps: o.steps || '', expected: o.expected || '', actual: typeof o.actual === 'string' ? o.actual : JSON.stringify(o.actual),
    evidence: o.evidence || '', screenshot: o.screenshot || '', codeRef: o.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === c.id);
  if (i >= 0) cases[i] = c; else cases.push(c);
  console.log(`[${status}] ${c.id} ${c.title}${status !== 'PASS' ? '\n    ' + c.actual.slice(0, 1500) : ''}`);
}
function save() {
  fs.writeFileSync(OUT, JSON.stringify({ area: 'regression-explore', round: 'final', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
  const n = (s) => cases.filter((c) => c.status === s).length;
  console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED`);
}
function checks() {
  const items = [];
  const f = (d) => (d === undefined ? '' : ` [${(typeof d === 'string' ? d : JSON.stringify(d)).slice(0, 300)}]`);
  return {
    that(label, cond, detail) { items.push({ label, ok: !!cond, detail }); return !!cond; },
    get ok() { return items.length > 0 && items.every((i) => i.ok); },
    summary() {
      return [...items.filter((i) => !i.ok).map((i) => `NOT OK: ${i.label}${f(i.detail)}`),
        ...items.filter((i) => i.ok).map((i) => `ok: ${i.label}${f(i.detail)}`)].join('; ');
    },
  };
}
async function tc(meta, fn) {
  if (caseRe && !caseRe.test(meta.id)) return;
  if (only && only.toUpperCase() !== (meta.kind || 'API')) return;
  try {
    const r = (await fn()) || {};
    rec({ ...meta, ...r, ok: r.ok, status: r.status });
  } catch (e) {
    rec({ ...meta, status: 'BLOCKED', actual: `harness/fixture error: ${String(e && e.stack || e).slice(0, 1500)}` });
  }
  save();
}
const ids = (rows) => (rows || []).map((x) => x.id);
const num = (x) => (x == null ? null : Number(x));
const brief = (r) => `${r.status} ${String(r.text || '').slice(0, 300)}`;

(async () => {
  const admin = await rt.adminToken();
  const W = await rt.seedWorld(admin);
  const { NORTH: N, SOUTH: S, WEST: WE, DEFAULT: D } = W.regions;
  const T = W.tokens;
  const C = W.customers;
  const I = W.invoices;
  const U = W.users;
  console.log('seeded', JSON.stringify({ N: N.id, S: S.id, WE: WE.id, D: D.id, cust: Object.fromEntries(Object.entries(C).map(([k, v]) => [k, v.id])) }));

  // ------------------------------------------------------------------------------------------ API
  await tc({ id: 'RGR-X-01', feature: 'Customers list/summary by region', kind: 'API', prd: 'B1',
    title: 'Customers list and summary tiles answer "which regions": asked South -> only South; asked an ungranted region -> 200 empty; none -> all granted' }, async () => {
    const k = checks();
    const all = await rt.list(T.nora, '/api/customers');
    const south = await rt.list(T.nora, '/api/customers', {}, { regionIds: [S.id] });
    const west = await rt.list(T.nora, '/api/customers', {}, { regionIds: [WE.id] });
    const sumS = await rt.api('GET', '/api/customers/summary', { token: T.nora, query: { regionIds: S.id } });
    const sumW = await rt.api('GET', '/api/customers/summary', { token: T.nora, query: { regionIds: WE.id } });
    const sumAll = await rt.api('GET', '/api/customers/summary', { token: T.nora });
    k.that('no regionIds: exactly n1,n2,s1,s2', JSON.stringify(ids(all.json.content).sort()) === JSON.stringify([C.n1.id, C.n2.id, C.s1.id, C.s2.id].sort()), ids(all.json.content));
    k.that('envelope regionIds = N,S', JSON.stringify([...(all.json.regionIds || [])].sort()) === JSON.stringify([N.id, S.id].sort()), all.json.regionIds);
    k.that('South: exactly s1,s2', JSON.stringify(ids(south.json.content).sort()) === JSON.stringify([C.s1.id, C.s2.id].sort()), ids(south.json.content));
    k.that('South envelope regionIds=[S]', JSON.stringify(south.json.regionIds) === JSON.stringify([S.id]), south.json.regionIds);
    k.that('West (ungranted): 200 and empty', west.status === 200 && west.json.content.length === 0 && west.json.totalElements === 0, brief(west));
    k.that('West envelope regionIds=[]', Array.isArray(west.json.regionIds) && west.json.regionIds.length === 0, west.json.regionIds);
    k.that('summary South count 2, outstanding 6000', sumS.status === 200 && sumS.json.count === 2 && num(sumS.json.totalOutstanding) === 6000, sumS.json);
    k.that('summary West count 0', sumW.status === 200 && sumW.json.count === 0 && num(sumW.json.totalOutstanding) === 0, sumW.json);
    k.that('summary all count 4, outstanding 12000', sumAll.status === 200 && sumAll.json.count === 4 && num(sumAll.json.totalOutstanding) === 12000, sumAll.json);
    return { ok: k.ok, actual: k.summary(), steps: 'seedWorld; nora GET /api/customers and /api/customers/summary with regionIds none | South | West',
      expected: 'DESIGN §6.0: lists and summaries narrowed to asked∩granted; ungranted ids contribute nothing (200, empty)',
      evidence: `GET /api/customers?regionIds=${WE.id} -> ${west.status} total=${west.json.totalElements}` };
  });

  await tc({ id: 'RGR-X-02', feature: 'regionIds validation', kind: 'API', prd: 'B1',
    title: 'regionIds boundaries on pre-existing lists: non-numeric -> 400 REGION_IDS_INVALID; empty value and repeated form handled; page size 15 refused' }, async () => {
    const k = checks();
    const bad = await rt.api('GET', '/api/payments?regionIds=abc', { token: T.nora });
    const bad2 = await rt.api('GET', `/api/invoices?regionIds=${N.id},x`, { token: T.nora });
    const empty = await rt.api('GET', '/api/payments?regionIds=', { token: T.nora });
    const rep = await rt.api('GET', `/api/invoices?regionIds=${N.id}&regionIds=${S.id}&size=50`, { token: T.nora });
    const size = await rt.api('GET', '/api/invoices?size=15', { token: T.nora });
    k.that('abc -> 400 REGION_IDS_INVALID', bad.status === 400 && bad.json && bad.json.error === 'REGION_IDS_INVALID', brief(bad));
    k.that('"N,x" -> 400 REGION_IDS_INVALID', bad2.status === 400 && bad2.json && bad2.json.error === 'REGION_IDS_INVALID', brief(bad2));
    k.that('empty regionIds is not a 500', empty.status !== 500 && empty.status !== 0, brief(empty));
    k.that('repeated regionIds = N,S -> 8 invoices', rep.status === 200 && rep.json.totalElements === 8, `${rep.status} total=${rep.json && rep.json.totalElements} regionIds=${JSON.stringify(rep.json && rep.json.regionIds)}`);
    k.that('size=15 -> 400', size.status === 400, brief(size));
    return { ok: k.ok, actual: k.summary() + ` | empty regionIds -> ${empty.status} total=${empty.json && empty.json.totalElements} regionIds=${JSON.stringify(empty.json && empty.json.regionIds)}`,
      steps: 'nora GET /api/payments?regionIds=abc; /api/invoices?regionIds=N,x; /api/payments?regionIds=; /api/invoices?regionIds=N&regionIds=S; /api/invoices?size=15',
      expected: 'DESIGN §6.0: non-numeric -> 400 REGION_IDS_INVALID; repeated form works; §6 README page size 10/20/50',
      evidence: `abc: ${brief(bad)}` };
  });

  await tc({ id: 'RGR-X-03', feature: 'Dashboard by region', kind: 'API', prd: 'B1',
    title: 'Dashboard figures answer "which regions": outstanding-by-age and top-outstanding for nora/North, wendy, admin all' }, async () => {
    const k = checks();
    const age = (tok, q = {}) => rt.api('GET', '/api/dashboard/outstanding-by-age', { token: tok, query: q });
    const top = (tok, q = {}) => rt.api('GET', '/api/dashboard/top-outstanding-customers', { token: tok, query: { limit: 20, ...q } });
    const sum = (r) => (r.json && r.json.buckets || []).reduce((a, b) => a + num(b.amount), 0);
    const aN = await age(T.nora, { regionIds: N.id });
    const aW = await age(T.wendy);
    const aAll = await age(T.admin);
    const aNoraAll = await age(T.nora);
    const tN = await top(T.nora, { regionIds: N.id });
    const tWest = await top(T.nora, { regionIds: WE.id });
    k.that('nora North outstanding 6000', sum(aN) === 6000, { sum: sum(aN), scope: aN.json && aN.json.scope });
    k.that('scope.regionIds=[N]', JSON.stringify(aN.json && aN.json.scope && aN.json.scope.regionIds) === JSON.stringify([N.id]), aN.json && aN.json.scope);
    k.that('nora N+S outstanding 12000', sum(aNoraAll) === 12000, sum(aNoraAll));
    k.that('wendy outstanding 6000 (West only)', sum(aW) === 6000, sum(aW));
    k.that('admin outstanding 21000 (4 regions)', sum(aAll) === 21000, sum(aAll));
    const topIds = (tN.json && tN.json.customers || []).map((c) => c.customerId).sort();
    k.that('top-outstanding North = n1,n2', JSON.stringify(topIds) === JSON.stringify([C.n1.id, C.n2.id].sort()), topIds);
    k.that('top-outstanding West for nora: 200, none', tWest.status === 200 && (tWest.json.customers || []).length === 0, brief(tWest));
    return { ok: k.ok, actual: k.summary(), steps: 'nora/wendy/admin GET /api/dashboard/outstanding-by-age and /top-outstanding-customers with and without regionIds',
      expected: 'PRD B1 "every … dashboard figure answers which regions"; DESIGN §6.9 scope object', evidence: `nora N buckets=${JSON.stringify(aN.json && aN.json.buckets).slice(0, 300)}` };
  });

  await tc({ id: 'RGR-X-04', feature: 'Export by region', kind: 'API', prd: 'B1',
    title: 'Invoice CSV export with "select all matching" honours regionIds from the query string' }, async () => {
    const k = checks();
    const r = await rt.api('POST', `/api/invoices/export?regionIds=${S.id}`, { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [] } });
    const lines = (r.text || '').trim().split(/\r?\n/);
    const dataLines = lines.slice(1);
    const southNums = I.s1.concat(I.s2).map((x) => x.invoiceNumber);
    const otherNums = [...I.n1, ...I.n2, ...I.w1, ...I.w2, ...I.d1].map((x) => x.invoiceNumber);
    k.that('200 CSV', r.status === 200, brief(r));
    k.that('4 data rows', dataLines.length === 4, dataLines.length);
    k.that('all South invoice numbers present', southNums.every((n) => r.text.includes(n)), southNums);
    k.that('no other region invoice number present', otherNums.every((n) => !r.text.includes(n)), otherNums.filter((n) => r.text.includes(n)));
    return { ok: k.ok, actual: k.summary(), steps: `admin POST /api/invoices/export?regionIds=${S.id} {selectAllMatchingFilter:true}`,
      expected: 'DESIGN §6.7: all bulk and export POSTs honour regionIds from the query string', evidence: lines.slice(0, 3).join(' / ').slice(0, 400) };
  });

  await tc({ id: 'RGR-X-05', feature: 'Rights differ per region', kind: 'API', prd: 'B1',
    title: 'nora: READ in South (reads, flags false, writes 403), MANAGE in North (writes ok), nothing in West (404)' }, async () => {
    const k = checks();
    const sInv = I.s2[1];
    const g1 = await rt.api('GET', `/api/invoices/${sInv.id}`, { token: T.nora });
    const p1 = await rt.patchInvoice(T.nora, sInv.id, { notes: 'nora edit', version: g1.json && g1.json.version });
    const pay = await rt.createPayment(T.nora, { customerId: C.s2.id, amount: 10, collectionPocUserId: U.sam.id });
    const prom = await rt.createPromise(T.nora, { customerId: C.s2.id, amount: 10, collectionPocUserId: U.sam.id });
    const cu = await rt.api('GET', `/api/customers/${C.s2.id}`, { token: T.nora });
    const putC = await rt.api('PUT', `/api/customers/${C.s2.id}`, { token: T.nora, body: { ...cu.json, name: cu.json.name + ' x' } });
    const wInv = await rt.api('GET', `/api/invoices/${I.w1[0].id}`, { token: T.nora });
    const wCust = await rt.api('GET', `/api/customers/${C.w1.id}`, { token: T.nora });
    const nInv = await rt.api('GET', `/api/invoices/${I.n2[1].id}`, { token: T.nora });
    const p2 = await rt.patchInvoice(T.nora, I.n2[1].id, { notes: 'nora north edit', version: nInv.json && nInv.json.version });
    k.that('GET South invoice 200, canManage=false', g1.status === 200 && g1.json.canManage === false, brief(g1).slice(0, 120));
    k.that('PATCH South invoice 403', p1.status === 403, brief(p1));
    k.that('POST payment South 403', pay.status === 403, brief(pay));
    k.that('POST promise South 403', prom.status === 403, brief(prom));
    k.that('GET South customer canManage=false canMoveRegion=false', cu.status === 200 && cu.json.canManage === false && !cu.json.canMoveRegion, { cm: cu.json.canManage, mv: cu.json.canMoveRegion });
    k.that('PUT South customer 403', putC.status === 403, brief(putC));
    k.that('GET West invoice 404', wInv.status === 404, brief(wInv));
    k.that('GET West customer 404', wCust.status === 404, brief(wCust));
    k.that('GET North invoice canManage=true', nInv.status === 200 && nInv.json.canManage === true, nInv.json && nInv.json.canManage);
    k.that('PATCH North invoice notes 200', p2.status === 200, brief(p2));
    return { ok: k.ok, actual: k.summary(), steps: 'nora (MANAGE N, READ S, none W) reads and writes records in each region',
      expected: 'PRD B1: read in one, manage in another, nothing in a third; DESIGN §6.0 403 no right / 404 invisible', evidence: `PATCH S -> ${brief(p1)}` };
  });

  await tc({ id: 'RGR-X-06', feature: 'Customer create region', kind: 'API', prd: 'B1',
    title: 'Customer create: region defaults when exactly one manageable region (after regionIds narrowing); otherwise 400 regionId; READ region 403; ungranted 404' }, async () => {
    const k = checks();
    const body = (u) => ({ name: rt.uniq('XC'), phone: '555', email: `${u}@e2e.local`, address: 'a', username: u, password: rt.PASSWORD });
    const u1 = rt.uniq('xw'); const r1 = await rt.api('POST', '/api/customers', { token: T.wendy, body: body(u1) });
    const u2 = rt.uniq('xs'); const r2 = await rt.api('POST', '/api/customers', { token: T.sam, body: body(u2) });
    const u3 = rt.uniq('xs'); const r3 = await rt.api('POST', '/api/customers', { token: T.sam, body: body(u3), query: { regionIds: N.id } });
    const u4 = rt.uniq('xn'); const r4 = await rt.api('POST', '/api/customers', { token: T.nora, body: { ...body(u4), regionId: S.id } });
    const u5 = rt.uniq('xn'); const r5 = await rt.api('POST', '/api/customers', { token: T.nora, body: { ...body(u5), regionId: WE.id } });
    const u6 = rt.uniq('xn'); const r6 = await rt.api('POST', '/api/customers', { token: T.nora, body: body(u6) });
    k.that('wendy (only West) no regionId -> 200 West', r1.status === 200 && r1.json.regionId === WE.id, brief(r1).slice(0, 200));
    k.that('sam (manages N and S) no regionId -> 400 fieldErrors.regionId', r2.status === 400 && r2.json && r2.json.fieldErrors && /region/i.test(r2.json.fieldErrors.regionId || ''), brief(r2));
    k.that('sam narrowed to North -> 200 North', r3.status === 200 && r3.json.regionId === N.id, brief(r3).slice(0, 200));
    k.that('nora regionId=South (READ) -> 403', r4.status === 403, brief(r4));
    k.that('nora regionId=West (ungranted) -> 404', r5.status === 404, brief(r5));
    k.that('nora no regionId (manages only North) -> 200 North', r6.status === 200 && r6.json.regionId === N.id, brief(r6).slice(0, 200));
    return { ok: k.ok, actual: k.summary(), steps: 'POST /api/customers as wendy, sam, sam?regionIds=N, nora {regionId:S}, nora {regionId:W}, nora {}',
      expected: 'DESIGN §6.4: null regionId + exactly one manageable region -> that region; else 400 fieldErrors.regionId; 403 without CUSTOMER_MANAGE; 404 ungranted', evidence: `sam: ${brief(r2)}` };
  });

  await tc({ id: 'RGR-X-07', feature: 'POC book per region', kind: 'API', prd: 'B1',
    title: 'bob (MANAGE/BOOK North) sees only his book: customers, invoices, dashboard; lockedFilters and bookRegionIds set; another North customer is 404' }, async () => {
    const k = checks();
    const cl = await rt.list(T.bob, '/api/customers');
    const il = await rt.list(T.bob, '/api/invoices');
    const n2 = await rt.api('GET', `/api/customers/${C.n2.id}`, { token: T.bob });
    const n2inv = await rt.api('GET', `/api/invoices/${I.n2[0].id}`, { token: T.bob });
    const age = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: T.bob });
    const sum = (age.json && age.json.buckets || []).reduce((a, b) => a + num(b.amount), 0);
    k.that('customers = [n1]', JSON.stringify(ids(cl.json.content)) === JSON.stringify([C.n1.id]), ids(cl.json.content));
    k.that('customers bookRegionIds=[N]', JSON.stringify(cl.json.bookRegionIds) === JSON.stringify([N.id]), cl.json.bookRegionIds);
    k.that('customers lockedFilters non-empty', Array.isArray(cl.json.lockedFilters) && cl.json.lockedFilters.length > 0, cl.json.lockedFilters);
    k.that('invoices = n1 invoices', JSON.stringify(ids(il.json.content).sort()) === JSON.stringify(ids(I.n1).sort()), ids(il.json.content));
    k.that('GET n2 customer 404', n2.status === 404, brief(n2));
    k.that('GET n2 invoice 404', n2inv.status === 404, brief(n2inv));
    k.that('dashboard outstanding = 3000 (n1 only)', sum === 3000, { sum, scope: age.json && age.json.scope });
    k.that('dashboard scope.bookRegionIds=[N]', JSON.stringify(age.json && age.json.scope && age.json.scope.bookRegionIds) === JSON.stringify([N.id]), age.json && age.json.scope);
    return { ok: k.ok, actual: k.summary(), steps: 'bob GET customers, invoices, n2 by id, dashboard outstanding-by-age',
      expected: 'PRD B1 "the POC book is per region"; DESIGN §6.0 bookRegionIds/lockedFilters', evidence: `lockedFilters=${JSON.stringify(cl.json.lockedFilters)}` };
  });

  await tc({ id: 'RGR-X-08', feature: 'Payment threshold boundary', kind: 'API', prd: 'B2',
    title: 'South PAYMENT_RECORD 5000: exactly 5000 applies (200); 5000.01 is held (202, Location, no id), allocates nothing, and is visible to a READ viewer of the customer' }, async () => {
    const k = checks();
    const eq = await rt.createPayment(T.sam, { customerId: C.s1.id, amount: 5000, collectionPocUserId: U.sam.id });
    const s2Before = await rt.api('GET', `/api/customers/${C.s2.id}`, { token: T.admin });
    const over = await rt.createPayment(T.sam, { customerId: C.s2.id, amount: 5000.01, invoiceIds: [I.s2[1].id], collectionPocUserId: U.sam.id });
    const s2After = await rt.api('GET', `/api/customers/${C.s2.id}`, { token: T.admin });
    const inv = await rt.api('GET', `/api/invoices/${I.s2[1].id}`, { token: T.admin });
    const pays = await rt.list(T.admin, '/api/payments', { filter: `customerId:eq:${C.s2.id}` });
    const pcId = over.pendingChange && over.pendingChange.id;
    const fr = await rt.approvals.forRecord(T.nora, 'CUSTOMER', C.s2.id);
    const det = pcId ? await rt.approvals.get(T.nora, pcId) : { status: 0 };
    k.that('exactly 5000 -> 200 applied', eq.status === 200 && eq.data && eq.data.id, brief(eq).slice(0, 160));
    k.that('5000.01 -> 202 PENDING_APPROVAL', over.status === 202 && over.held, brief(over).slice(0, 300));
    k.that('Location /api/approvals/{id}', over.headers && String(over.headers.location || '').endsWith(`/api/approvals/${pcId}`), over.headers && over.headers.location);
    k.that('no top-level id', over.json && over.json.id === undefined, Object.keys(over.json || {}));
    k.that('customer outstanding unchanged', num(s2Before.json.outstanding ?? s2Before.json.totalOutstanding) === num(s2After.json.outstanding ?? s2After.json.totalOutstanding), { before: s2Before.json.outstanding, after: s2After.json.outstanding });
    k.that('invoice balance unchanged (2500)', num(inv.json.balance ?? (num(inv.json.total) - num(inv.json.paidAmount))) === 2500, { total: inv.json.total, paid: inv.json.paidAmount, balance: inv.json.balance });
    k.that('payments list still only the seed payment', pays.json.content.length === 1, ids(pays.json.content));
    k.that('nora (READ South) sees the held create for the customer', fr.status === 200 && (fr.json || []).some((b) => b.id === pcId), brief(fr).slice(0, 300));
    k.that('nora can open the approval detail, canApprove=false', det.status === 200 && det.json.canApprove === false, det.json && { canApprove: det.json.canApprove, blockedReason: det.json.blockedReason });
    return { ok: k.ok, actual: k.summary(), steps: 'sam POST /api/payments s1 5000; sam POST /api/payments s2 5000.01 against the 2500 invoice; read back as admin and nora',
      expected: 'PRD B2 "above a threshold" (exactly equal is not above); pending visible to everyone who can see the record without having taken effect; DESIGN §6.6', evidence: `held pc=${pcId} summary=${over.pendingChange && over.pendingChange.summary}` };
  });

  let heldCancelInvoice = null;
  await tc({ id: 'RGR-X-09', feature: 'Held cancel lifecycle', kind: 'API', prd: 'B2',
    title: 'Cancel above INVOICE_CANCEL is held: invoice stays UNPAID, list row pendingApproval=true for a READ viewer; READ viewer 403 to approve; maker 400; approver applies it' }, async () => {
    const k = checks();
    const cr = await rt.createInvoice(T.admin, { customerId: C.s2.id, amount: 6000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const inv = cr.data;
    const cancel = await rt.cancelInvoice(T.sam, inv.id, { reason: 'e2e' });
    const pcId = cancel.pendingChange && cancel.pendingChange.id;
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.nora });
    const lst = await rt.list(T.nora, '/api/invoices', { filter: 'pendingApproval:eq:true' }, { regionIds: [S.id] });
    const aNora = await rt.approvals.approve(T.nora, pcId);
    const aSam = await rt.approvals.approve(T.sam, pcId);
    const aWendy = await rt.approvals.get(T.wendy, pcId);
    const aAdmin = await rt.approvals.approve(T.admin, pcId, 'ok');
    const g2 = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    const lst2 = await rt.list(T.nora, '/api/invoices', { filter: 'pendingApproval:eq:true' }, { regionIds: [S.id] });
    k.that('invoice 6000 created 200', cr.status === 200, brief(cr).slice(0, 150));
    k.that('cancel -> 202 held', cancel.status === 202 && cancel.held, brief(cancel).slice(0, 200));
    k.that('invoice still UNPAID for nora', g.status === 200 && g.json.status === 'UNPAID', g.json && g.json.status);
    k.that('nora list pendingApproval:eq:true contains it', ids(lst.json.content).includes(inv.id), ids(lst.json.content));
    k.that('nora (READ South) approve -> 403', aNora.status === 403, brief(aNora));
    k.that('sam (maker) approve -> 400', aSam.status === 400, brief(aSam));
    k.that('wendy (no South) detail -> 404', aWendy.status === 404, brief(aWendy));
    k.that('admin approve -> 200 APPROVED', aAdmin.status === 200 && aAdmin.json.status === 'APPROVED', brief(aAdmin).slice(0, 200));
    k.that('invoice now CANCELLED', g2.json && g2.json.status === 'CANCELLED', g2.json && g2.json.status);
    k.that('no longer pendingApproval in list', !ids(lst2.json.content).includes(inv.id), ids(lst2.json.content));
    return { ok: k.ok, actual: k.summary(), steps: 'admin creates 6000 invoice for s2 (South); sam cancels; nora reads/list; nora, sam, admin approve; wendy opens',
      expected: 'PRD B2: pending, needs approval from someone else holding the approval right in that region; visible without having taken effect', evidence: `pc=${pcId} nora approve: ${brief(aNora)}` };
  });

  await tc({ id: 'RGR-X-10', feature: 'Bulk cancel mixed', kind: 'API', prd: 'B2',
    title: 'Invoice bulk CANCEL mixing a small and a large South invoice: the small one is cancelled, the large one lands in pending[] and stays UNPAID' }, async () => {
    const k = checks();
    // A new South customer: INVOICE_CREATE counts the same maker's changes to one customer over 24 h (§6.6), and s1 holds credit.
    const s3 = await rt.createCustomer(T.admin, { name: rt.uniq('South S3'), regionId: S.id, paymentTerm: 'NET_30' });
    await rt.addCustomerPoc(T.admin, s3.id, 'COLLECTION', U.sam.id, true);
    const mk = async (amount) => { const r = await rt.createInvoice(T.admin, { customerId: s3.id, amount, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id }); if (r.status !== 200) throw new Error(`fixture invoice ${amount} -> ${brief(r)}`); return r.data; };
    const small = await mk(800);
    const big = await mk(7000);
    const r = await rt.api('POST', '/api/invoices/bulk', { token: T.sam, body: { action: 'CANCEL', ids: [small.id, big.id], params: { reason: 'bulk e2e' } } });
    const gs = await rt.api('GET', `/api/invoices/${small.id}`, { token: T.admin });
    const gb = await rt.api('GET', `/api/invoices/${big.id}`, { token: T.admin });
    heldCancelInvoice = { id: big.id, number: big.invoiceNumber, pcId: (r.json && r.json.pending || []).find((p) => p.id === big.id)?.pendingChangeId };
    k.that('bulk 200', r.status === 200, brief(r).slice(0, 300));
    k.that('succeeded = [small]', JSON.stringify(r.json && r.json.succeeded) === JSON.stringify([small.id]), r.json && r.json.succeeded);
    k.that('pending has big with pendingChangeId', heldCancelInvoice.pcId != null, r.json && r.json.pending);
    k.that('small CANCELLED', gs.json.status === 'CANCELLED', gs.json.status);
    k.that('big still UNPAID', gb.json.status === 'UNPAID', gb.json.status);
    return { ok: k.ok, actual: k.summary(), steps: 'admin creates a new South customer s3 and 800 + 7000 invoices for it; sam POST /api/invoices/bulk {action:CANCEL, ids:[both]}',
      expected: 'DESIGN §6.7 BulkResult.pending for invoice bulk CANCEL; PRD B2', evidence: brief(r).slice(0, 400) };
  });

  await tc({ id: 'RGR-X-11', feature: 'As-of parameter contract', kind: 'API', prd: 'B3',
    title: 'asOf on pre-existing endpoints: future 400 AS_OF_FUTURE, malformed 400 AS_OF_INVALID, today -> live header, yesterday -> dated header; writes with asOf 400 AS_OF_READ_ONLY' }, async () => {
    const k = checks();
    const fut = await rt.api('GET', '/api/invoices', { token: T.admin, query: { asOf: rt.day(1) } });
    const bad = await rt.api('GET', '/api/invoices', { token: T.admin, query: { asOf: '2026-02-30' } });
    const bad2 = await rt.api('GET', '/api/customers', { token: T.admin, query: { asOf: '28/09/2026' } });
    const today = await rt.api('GET', '/api/customers', { token: T.admin, query: { asOf: rt.day(0) } });
    const yest = await rt.api('GET', '/api/customers', { token: T.admin, query: { asOf: rt.day(-1) } });
    const dash = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: T.admin, query: { asOf: rt.day(-1) } });
    const w1 = await rt.api('POST', '/api/customers', { token: T.admin, query: { asOf: rt.day(-1) }, body: { name: 'x', username: rt.uniq('ro'), password: rt.PASSWORD, regionId: D.id } });
    const w3 = await rt.api('POST', '/api/payments', { token: T.admin, query: { asOf: rt.day(-1) }, body: { customerId: C.d1.id, amount: 1, method: 'CASH' } });
    k.that('future -> 400 AS_OF_FUTURE', fut.status === 400 && fut.json && fut.json.error === 'AS_OF_FUTURE', brief(fut));
    k.that('2026-02-30 -> 400 AS_OF_INVALID', bad.status === 400 && bad.json && bad.json.error === 'AS_OF_INVALID', brief(bad));
    k.that('28/09/2026 -> 400 AS_OF_INVALID', bad2.status === 400 && bad2.json && bad2.json.error === 'AS_OF_INVALID', brief(bad2));
    k.that('today -> 200, X-View-As-Of: live', today.status === 200 && today.headers['x-view-as-of'] === 'live', `${today.status} ${today.headers['x-view-as-of']}`);
    k.that('today -> content equals live', today.json && today.json.totalElements >= 8, today.json && today.json.totalElements);
    k.that('yesterday -> 200, header = date, asOf in envelope', yest.status === 200 && yest.headers['x-view-as-of'] === rt.day(-1) && yest.json.asOf === rt.day(-1), `${yest.status} ${yest.headers['x-view-as-of']} ${yest.json && yest.json.asOf}`);
    k.that('yesterday (fresh db) -> empty world', yest.json && yest.json.totalElements === 0, yest.json && yest.json.totalElements);
    k.that('dashboard yesterday -> scope.asOf', dash.status === 200 && dash.json.scope && dash.json.scope.asOf === rt.day(-1), dash.json && dash.json.scope);
    k.that('POST customer with asOf -> 400 AS_OF_READ_ONLY', w1.status === 400 && w1.json && w1.json.error === 'AS_OF_READ_ONLY', brief(w1));
    k.that('POST payment with asOf -> 400 AS_OF_READ_ONLY', w3.status === 400 && w3.json && w3.json.error === 'AS_OF_READ_ONLY', brief(w3));
    return { ok: k.ok, actual: k.summary(), steps: 'admin GET /api/invoices|customers|dashboard with asOf tomorrow, 2026-02-30, 28/09/2026, today, yesterday; POST customers/payments with asOf=yesterday',
      expected: 'DESIGN §6.0, §6.17 error catalogue; B3 as-of reads', evidence: `future: ${brief(fut)}` };
  });

  await tc({ id: 'RGR-X-12', feature: 'Automation x maker-checker', kind: 'API', prd: 'A1',
    title: 'An INVOICE ENTITY_CREATED rule does not fire for a held create; it fires once the create is approved, and the task title carries the new invoice number' }, async () => {
    const k = checks();
    const title = 'Welcome {{Invoice.Number}} for {{Customer.Name}}';
    const cr = await rt.automation.create(T.admin, rt.rule({ name: rt.uniq('xcreated'), trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, regionIds: [S.id],
      condition: null, actions: [rt.action.task({ title, assignees: [rt.tok.user(U.sam.id)] })] }));
    if (cr.status !== 200) return { status: 'BLOCKED', actual: `rule create -> ${brief(cr)}` };
    const ruleId = cr.json.id;
    const t0 = Date.now();
    const held = await rt.createInvoice(T.sam, { customerId: C.s2.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const saveMs = Date.now() - t0;
    await rt.sleep(8000);
    const runsBefore = await rt.automation.runs(T.admin, ruleId);
    const pcId = held.pendingChange && held.pendingChange.id;
    const ap = await rt.approvals.approve(T.admin, pcId, 'go');
    const newId = ap.json && ap.json.result && ap.json.result.entityId;
    const task = newId ? await rt.waitFor(async () => {
      const r = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: newId });
      return r.status === 200 && r.json.content.length ? r.json.content : null;
    }, { timeoutMs: 45000, intervalMs: 2000 }) : null;
    const inv = newId ? await rt.api('GET', `/api/invoices/${newId}`, { token: T.admin }) : { json: {} };
    const runsAfter = await rt.automation.runs(T.admin, ruleId);
    await rt.automation.disable(T.admin, ruleId);
    k.that('held create -> 202', held.status === 202 && held.held, brief(held).slice(0, 200));
    k.that('held save returned quickly (< 3000 ms)', saveMs < 3000, saveMs);
    k.that('no run while held', runsBefore.status === 200 && runsBefore.json.totalElements === 0, runsBefore.json && runsBefore.json.content);
    k.that('approve -> APPROVED with result entityId', ap.status === 200 && ap.json.status === 'APPROVED' && newId, brief(ap).slice(0, 300));
    k.that('rule task created on the new invoice', task && task.length === 1, task && task.map((t) => t.title));
    k.that('task title has invoice number and customer name', task && task[0].title === `Welcome ${inv.json.invoiceNumber} for ${C.s2.name}`, task && task[0].title);
    k.that('task origin AUTOMATION, rule id', task && task[0].origin === 'AUTOMATION' && task[0].rule && task[0].rule.id === ruleId, task && { origin: task[0].origin, rule: task[0].rule });
    k.that('exactly one run after approval', runsAfter.json && runsAfter.json.totalElements === 1, runsAfter.json && runsAfter.json.content.map((r) => ({ id: r.id, s: r.status, t: r.triggerType })));
    return { ok: k.ok, actual: k.summary(), steps: 'admin creates ENTITY_CREATED INVOICE rule (South) with a task action; sam creates 12000 invoice (held); wait 8s; admin approves; wait for the task',
      expected: 'PRD A1 (fires when created), A5, B2 (a held change has not taken effect); A4 placeholders', evidence: `rule=${ruleId} pc=${pcId} newInvoice=${newId} runs=${JSON.stringify(runsAfter.json && runsAfter.json.content.map((r) => [r.id, r.status, r.matched, r.done]))}` };
  });

  await tc({ id: 'RGR-X-13', feature: 'Email action placeholders', kind: 'API', prd: 'A4',
    title: 'Run now on a chosen North invoice: SEND_EMAIL renders {{Invoice.Number}}, {{Customer.Name}}, {{Invoice.Balance}} into the persisted email (transport none)' }, async () => {
    const k = checks();
    const target = I.n2[1];
    const cr = await rt.automation.create(T.nora, rt.rule({ name: rt.uniq('xmail'), trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [N.id],
      actions: [rt.action.email({ subject: 'Invoice {{Invoice.Number}} for {{Customer.Name}}', body: 'Balance {{Invoice.Balance}} due {{Invoice.DueDate}}' })] }));
    if (cr.status !== 200) return { status: 'BLOCKED', actual: `rule create -> ${brief(cr)}` };
    const run = await rt.automation.run(T.nora, cr.json.id, { recordIds: [target.id] });
    const done = run.json && run.json.runId ? await rt.waitForRun(T.nora, run.json.runId, { timeoutMs: 45000 }) : null;
    const ef = done ? await rt.automation.effects(T.nora, done.id) : { json: {} };
    const em = await rt.emailsFor(T.nora, 'INVOICE', target.id);
    const mail = (em.json && (em.json.content || em.json) || []).find((e) => /Invoice /.test(e.subject || ''));
    const full = mail ? await rt.api('GET', `/api/emails/${mail.id}`, { token: T.nora }) : { json: {} };
    const inv = await rt.api('GET', `/api/invoices/${target.id}`, { token: T.nora });
    k.that('run 202 QUEUED', run.status === 202 && run.json.status === 'QUEUED', brief(run));
    k.that('run finished SUCCEEDED, matched 1 done 1', done && done.status === 'SUCCEEDED' && done.matched === 1 && done.done === 1, done && { s: done.status, m: done.matched, d: done.done, f: done.failed, msg: done.message });
    k.that('effect DONE', ef.json && (ef.json.content || []).some((e) => e.status === 'DONE'), ef.json && ef.json.content);
    k.that('email persisted', !!mail, (em.json && (em.json.content || em.json) || []).map((e) => e.subject));
    k.that('subject rendered', mail && mail.subject === `Invoice ${inv.json.invoiceNumber} for ${C.n2.name}`, mail && mail.subject);
    k.that('body has balance 2500 and no raw token', full.json && /2,?500/.test(full.json.body || full.json.bodyText || '') && !/\{\{/.test(full.json.body || ''), full.json && (full.json.body || '').slice(0, 200));
    k.that('status NOT_SENT/NOT_CONFIGURED', mail && /NOT_SENT|NOT_CONFIGURED/.test(mail.status || ''), mail && mail.status);
    return { ok: k.ok, actual: k.summary(), steps: 'nora creates MANUAL INVOICE rule (North) with SEND_EMAIL to customer; POST /rules/{id}/run {recordIds:[n2 invoice]}; read /api/emails',
      expected: 'PRD A3 send an email, A4 placeholders, A5 run from the UI whenever wanted', evidence: `run=${done && done.id} email=${mail && mail.id} subject=${mail && mail.subject}` };
  });

  await tc({ id: 'RGR-X-14', feature: 'Tasks by region', kind: 'API', prd: 'A6',
    title: 'A task on a West invoice is invisible to nora (404, not in lists even when she asks for West) and visible to wendy in My tasks' }, async () => {
    const k = checks();
    const t = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.w1[0].id, title: rt.uniq('west-task'), assignees: [rt.tok.user(U.wendy.id)] });
    const tid = t.json && t.json.id;
    const gN = await rt.api('GET', `/api/tasks/${tid}`, { token: T.nora });
    const lN = await rt.list(T.nora, '/api/tasks');
    const lNW = await rt.api('GET', '/api/tasks', { token: T.nora, query: { regionIds: WE.id, size: 50 } });
    const mW = await rt.tasks.mine(T.wendy);
    const cW = await rt.tasks.mineCount(T.wendy);
    const badAssign = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.w1[0].id, title: 'bad', assignees: [rt.tok.user(U.nora.id)] });
    k.that('task created', t.status === 200 && tid, brief(t).slice(0, 200));
    k.that('nora GET -> 404', gN.status === 404, brief(gN));
    k.that('nora list excludes it', !ids(lN.json.content).includes(tid), ids(lN.json.content));
    k.that('nora regionIds=West -> 200 empty', lNW.status === 200 && lNW.json.totalElements === 0, brief(lNW).slice(0, 200));
    k.that('wendy mine includes it', ids(mW.json && mW.json.content).includes(tid), ids(mW.json && mW.json.content));
    k.that('wendy mine/count >= 1', cW.status === 200 && cW.json.count >= 1, cW.json);
    k.that('assigning nora (cannot see West) -> 400 fieldErrors.assignees', badAssign.status === 400 && badAssign.json && badAssign.json.fieldErrors && badAssign.json.fieldErrors.assignees, brief(badAssign));
    return { ok: k.ok, actual: k.summary(), steps: 'admin POST /api/tasks on w1 invoice assigned to wendy; nora GET/list; wendy mine; admin assigns nora on a West invoice',
      expected: 'PRD A6 + B1; DESIGN §6.12 400 fieldErrors.assignees when a user cannot see the record', evidence: `task=${tid}; nora GET ${gN.status}` };
  });

  await tc({ id: 'RGR-X-15', feature: 'Region administration', kind: 'API', prd: 'B1',
    title: 'Region admin edges: inactive region refuses new customers; default cannot be deactivated; region with customers cannot be deleted; duplicate code 400; code immutable' }, async () => {
    const k = checks();
    const code = ('E' + Date.now().toString(36)).toUpperCase().slice(0, 10);
    const reg = await rt.api('POST', '/api/regions', { token: T.admin, body: { code, name: 'East', active: true } });
    const dup = await rt.api('POST', '/api/regions', { token: T.admin, body: { code, name: 'East 2', active: true } });
    const c = await rt.createCustomer(T.admin, { name: rt.uniq('East cust'), regionId: reg.json.id });
    const g = await rt.api('GET', `/api/regions/${reg.json.id}`, { token: T.admin });
    const deact = await rt.api('PUT', `/api/regions/${reg.json.id}`, { token: T.admin, body: { name: 'East', active: false, version: g.json.version } });
    const newC = await rt.api('POST', '/api/customers', { token: T.admin, body: { name: 'x', username: rt.uniq('ea'), password: rt.PASSWORD, regionId: reg.json.id } });
    const del = await rt.api('DELETE', `/api/regions/${reg.json.id}`, { token: T.admin });
    const gd = await rt.api('GET', `/api/regions/${D.id}`, { token: T.admin });
    const deactD = await rt.api('PUT', `/api/regions/${D.id}`, { token: T.admin, body: { name: gd.json.name, active: false, version: gd.json.version } });
    const nora = await rt.api('POST', '/api/regions', { token: T.nora, body: { code: 'NOPE', name: 'x' } });
    const g2 = await rt.api('GET', `/api/regions/${reg.json.id}`, { token: T.admin });
    const recode = await rt.api('PUT', `/api/regions/${reg.json.id}`, { token: T.admin, body: { code: 'CHANGED', name: 'East', active: false, version: g2.json.version } });
    const g3 = await rt.api('GET', `/api/regions/${reg.json.id}`, { token: T.admin });
    k.that('create region 200', reg.status === 200, brief(reg));
    k.that('duplicate code -> 400 fieldErrors.code', dup.status === 400 && dup.json.fieldErrors && dup.json.fieldErrors.code, brief(dup));
    k.that('deactivate -> 200 active=false', deact.status === 200 && deact.json.active === false, brief(deact));
    k.that('customer in inactive region -> 400 mentions inactive', newC.status === 400 && /inactive/i.test(newC.text), brief(newC));
    k.that('delete region with customers -> 400', del.status === 400 && /customers|deactivate/i.test(del.text), brief(del));
    k.that('deactivate DEFAULT -> 400', deactD.status === 400 && /default/i.test(deactD.text), brief(deactD));
    k.that('nora (no REGION_MANAGE) create region -> 403', nora.status === 403, brief(nora));
    k.that('code stays immutable', g3.json && g3.json.code === code, { put: recode.status, code: g3.json && g3.json.code });
    return { ok: k.ok, actual: k.summary(), steps: 'admin creates region E…, duplicate, customer, deactivate, new customer, delete, deactivate DEFAULT; nora create; PUT with changed code',
      expected: 'DESIGN §6.3/§6.4 region contract', evidence: `delete: ${brief(del)}` };
  });

  await tc({ id: 'RGR-X-16', feature: 'Grants administration', kind: 'API', prd: 'B1',
    title: 'Grants: nobody edits their own; a new user with no grants sees nothing regional; granting READ in North gives read without write; customer logins take region from customer' }, async () => {
    const k = checks();
    const meA = await rt.me(T.admin);
    const self = await rt.setGrants(T.admin, meA.id, [{ regionId: D.id, level: 'APPROVE', scope: 'ALL' }]);
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'xnog');
    const tk = await rt.login(u.username);
    const c0 = await rt.api('GET', '/api/customers', { token: tk, query: { size: 50 } });
    const d0 = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: tk });
    const set = await rt.setGrants(T.admin, u.id, [{ regionId: N.id, level: 'READ', scope: 'ALL' }]);
    const tk2 = await rt.login(u.username);
    const c1 = await rt.list(tk2, '/api/customers');
    const w1 = await rt.createPayment(tk2, { customerId: C.n1.id, amount: 5, collectionPocUserId: U.bob.id });
    const custU = await rt.findUser(T.admin, C.s2.username);
    const cg = custU ? await rt.setGrants(T.admin, custU.id, [{ regionId: N.id, level: 'READ', scope: 'ALL' }]) : { status: 0, text: 'customer user not found' };
    const dupG = await rt.setGrants(T.admin, u.id, [{ regionId: N.id, level: 'READ', scope: 'ALL' }, { regionId: N.id, level: 'MANAGE', scope: 'ALL' }]);
    k.that('own grants -> 400', self.status === 400 && /own/i.test(self.text), brief(self));
    // §4.2/§4.7: flat authorities are effectiveAnywhere(), so a user holding no region has no regional privilege: 403 or an empty 200 both mean "sees nothing".
    k.that('no-grant user: customers 403 or 200 empty', c0.status === 403 || (c0.status === 200 && c0.json.totalElements === 0), brief(c0).slice(0, 200));
    k.that('no-grant user: dashboard 403 or 0', d0.status === 403 || (d0.status === 200 && (d0.json.buckets || []).reduce((a, b) => a + num(b.amount), 0) === 0), brief(d0).slice(0, 200));
    k.that('grant READ North -> 200', set.status === 200, brief(set));
    const got = ids(c1.json.content);
    k.that('READ North sees n1,n2 (and other North customers only)', got.includes(C.n1.id) && got.includes(C.n2.id) && !got.includes(C.s1.id) && !got.includes(C.w1.id), got);
    k.that('READ North payment -> 403', w1.status === 403, brief(w1));
    k.that('customer login grants -> 400', cg.status === 400 && /customer/i.test(cg.text || ''), brief(cg));
    k.that('duplicate region rows -> 400 fieldErrors.grants', dupG.status === 400 && dupG.json && dupG.json.fieldErrors && dupG.json.fieldErrors.grants, brief(dupG));
    return { ok: k.ok, actual: k.summary(), steps: 'admin PUT own grants; create user without grants; list; grant READ North; list; payment; PUT grants on a customer login; duplicate rows',
      expected: 'DESIGN §6.3 grants contract; §4.6 no grants → nothing', evidence: `self: ${brief(self)}` };
  });

  await tc({ id: 'RGR-X-17', feature: 'Customer login scope', kind: 'API', prd: 'B1',
    title: 'A customer login ignores regionIds (asking North still shows its own South invoices) and cannot see another customer\'s invoice' }, async () => {
    const k = checks();
    const own = await rt.list(T.cust, '/api/invoices');
    const north = await rt.list(T.cust, '/api/invoices', {}, { regionIds: [N.id] });
    const other = await rt.api('GET', `/api/invoices/${I.s2[0].id}`, { token: T.cust });
    const meC = await rt.me(T.cust);
    const ownIds = ids(own.json.content);
    k.that('own list contains s1 invoices only', ownIds.length >= 2 && ids(I.s1).every((x) => ownIds.includes(x)) && !ownIds.includes(I.s2[0].id), ownIds);
    k.that('regionIds=North ignored: same rows', JSON.stringify(ids(north.json.content).sort()) === JSON.stringify([...ownIds].sort()), ids(north.json.content));
    k.that('other customer invoice -> 404/403', other.status === 404 || other.status === 403, brief(other));
    k.that('/me regions = []', Array.isArray(meC.regions) && meC.regions.length === 0, meC.regions);
    return { ok: k.ok, actual: k.summary(), steps: 'cust (s1 login) GET /api/invoices with and without regionIds=North; GET s2 invoice; /me',
      expected: 'DESIGN §6.0 "Customer logins ignore it"; §6.2 regions [] for customer logins', evidence: `own=${ownIds}` };
  });

  // ------------------------------------------------------------------------------------------ UI
  const ui = async (meta, who, route, fn) => tc({ kind: 'UI', ...meta }, async () => {
    // A planted token boots on '/' (the router's initialLocation, unchanged from main), so navigate in-app.
    const app = await rt.openApp({ token: who });
    try { await rt.go(app.page, route, 4500); return await fn(app); } finally { await app.close(); }
  });
  const hasText = async (page, re) => (await rt.screenText(page)).some((s) => re.test(s));
  const waitText = async (page, re, ms = 8000) => { const end = Date.now() + ms; for (;;) { if (await hasText(page, re)) return true; if (Date.now() > end) return false; await page.waitForTimeout(500); await rt.enableSemantics(page); } };

  await ui({ id: 'RGR-X-U01', feature: 'Region selector', prd: 'B1',
    title: 'nora\'s region selector offers only NORTH and SOUTH; unticking NORTH on Invoices re-asks with regionIds=South and shows only South rows' }, T.nora, '/invoices', async ({ page, requests }) => {
    const k = checks();
    await waitText(page, /North N1/);
    const sel = await rt.regionSelector(page);
    k.that('selector shown "Regions: All regions"', sel && /All regions/.test(sel.label || sel.text), sel && sel.label);
    await rt.clickAt(page, sel.x, sel.y, 1200);
    const items = (await rt.semantics(page)).filter((n) => n.role === 'menuitemcheckbox').map((n) => n.label || n.text).filter((x) => !/All my regions/.test(x));
    const s1 = await rt.shot(page, SHOTS, 'RGR-X-U01-1');
    k.that('menu items = NORTH, SOUTH (no WEST/DEFAULT)', items.length === 2 && items.some((x) => /^NORTH/.test(x)) && items.some((x) => /^SOUTH/.test(x)), items);
    const before = requests.length;
    await rt.press(page, /^NORTH/, { wait: 3500 });
    await waitText(page, /South S1/);
    const reqs = requests.slice(before).filter((r) => /^\/api\/invoices\?/.test(r.url));
    const txt = await rt.screenText(page);
    const s2 = await rt.shot(page, SHOTS, 'RGR-X-U01-2');
    const sel2 = await rt.regionSelector(page);
    k.that('selector now "SOUTH"', sel2 && /SOUTH/.test(sel2.label || sel2.text) && !/NORTH/.test(sel2.label || ''), sel2 && sel2.label);
    k.that(`invoices re-asked with regionIds=${S.id}`, reqs.some((r) => r.url.includes(`regionIds=${S.id}`) && !r.url.includes(`${N.id},`)), reqs.map((r) => r.url));
    k.that('South rows shown', txt.some((s) => /South S1/.test(s)), txt.filter((s) => /S1|S2/.test(s)).slice(0, 4));
    k.that('no North rows', !txt.some((s) => /North N[12]/.test(s)), txt.filter((s) => /North N/.test(s)).slice(0, 4));
    return { ok: k.ok, actual: k.summary(), screenshot: s2, evidence: `${s1}; requests ${JSON.stringify(reqs.map((r) => r.url)).slice(0, 300)}`,
      steps: 'open /invoices as nora; open region selector; untick NORTH', expected: 'PRD B1 every list answers which regions; DESIGN §7.3.1 selector over user.regions' };
  });

  await ui({ id: 'RGR-X-U02', feature: 'Single-region user', prd: 'B1',
    title: 'wendy (one region) has no region selector; Customers shows only West customers and no Region column' }, T.wendy, '/customers', async ({ page }) => {
    const k = checks();
    await waitText(page, /West W1/);
    const sel = await rt.regionSelector(page);
    const txt = await rt.screenText(page);
    const s = await rt.shot(page, SHOTS, 'RGR-X-U02-1');
    k.that('no region selector', !sel, sel && sel.label);
    k.that('West W1 and W2 shown', txt.some((x) => /West W1/.test(x)) && txt.some((x) => /West W2/.test(x)), txt.filter((x) => /West/.test(x)).slice(0, 5));
    k.that('no North/South/Default customers', !txt.some((x) => /(North|South|Default) [NSD][12]/.test(x)), txt.filter((x) => /(North|South|Default) /.test(x)).slice(0, 5));
    k.that('no "Region" column header', !txt.includes('Region'), txt.filter((x) => /Region/.test(x)));
    return { ok: k.ok, actual: k.summary(), screenshot: s, steps: 'open /customers as wendy', expected: 'DESIGN §7.3.1 selector hidden when regions ≤ 1; §7.5 Region column only when multiRegion' };
  });

  await ui({ id: 'RGR-X-U03', feature: 'As-of read-only mode', prd: 'B3',
    title: 'admin picks yesterday on Customers: banner "Viewing … — read only", New customer hidden, list requests carry asOf and show the empty past; Back to today restores' }, T.admin, '/customers', async ({ page, requests }) => {
    const k = checks();
    await waitText(page, /New customer/);
    k.that('New customer shown live', await hasText(page, /^New customer$/));
    const before = requests.length;
    const chip = await rt.chooseAsOf(page, rt.day(-1));
    await page.waitForTimeout(2000); await rt.enableSemantics(page);
    const txt = await rt.screenText(page);
    const s1 = await rt.shot(page, SHOTS, 'RGR-X-U03-1');
    const reqs = requests.slice(before).filter((r) => /^\/api\/customers\?/.test(r.url));
    k.that('chip reads "As of …"', /As of/.test(chip || ''), chip);
    k.that('banner "Viewing … read only"', txt.some((x) => /Viewing .* read only/.test(x)), txt.filter((x) => /Viewing/.test(x)));
    k.that('New customer hidden', !txt.includes('New customer'), txt.filter((x) => /New customer/.test(x)));
    k.that(`customers asked with asOf=${rt.day(-1)}`, reqs.some((r) => r.url.includes(`asOf=${rt.day(-1)}`)), reqs.map((r) => r.url));
    k.that('no customer rows (world did not exist yesterday)', !txt.some((x) => /North N1|South S1|West W1/.test(x)), txt.filter((x) => /N1|S1|W1/.test(x)).slice(0, 3));
    await rt.chooseAsOf(page, null);
    await waitText(page, /New customer/);
    const txt2 = await rt.screenText(page);
    const s2 = await rt.shot(page, SHOTS, 'RGR-X-U03-2');
    k.that('back to today: banner gone, New customer back, rows back', !txt2.some((x) => /Viewing .* read only/.test(x)) && txt2.includes('New customer') && txt2.some((x) => /North N1/.test(x)), txt2.filter((x) => /Viewing|New customer|North N1/.test(x)));
    return { ok: k.ok, actual: k.summary(), screenshot: s1, evidence: `${s2}`, steps: 'admin /customers; as-of chip -> yesterday; inspect; Back to today',
      expected: 'PRD B3 as-of lists; DESIGN §7.3.1 banner, §7.4 mutating controls off' };
  });

  await ui({ id: 'RGR-X-U04', feature: 'Read-only record by region', prd: 'B1',
    title: 'nora opens a South invoice (READ): no Save changes / Send email controls; a North invoice (MANAGE) offers them' }, T.nora, `/invoices/${I.s2[1].id}`, async ({ page }) => {
    const k = checks();
    await waitText(page, new RegExp(I.s2[1].invoiceNumber));
    const t1 = await rt.screenText(page);
    const s1 = await rt.shot(page, SHOTS, 'RGR-X-U04-1');
    await rt.go(page, `/invoices/${I.n2[1].id}`);
    await waitText(page, new RegExp(I.n2[1].invoiceNumber));
    const t2 = await rt.screenText(page);
    const s2 = await rt.shot(page, SHOTS, 'RGR-X-U04-2');
    k.that('South invoice shown', t1.some((x) => x.includes(I.s2[1].invoiceNumber)));
    k.that('South: no "Save changes"', !t1.some((x) => /Save changes/.test(x)), t1.filter((x) => /Save/.test(x)));
    k.that('South: no "Send email"', !t1.some((x) => /Send email/.test(x)), t1.filter((x) => /email/i.test(x)).slice(0, 5));
    k.that('South: Region shown (multi-region user)', t1.some((x) => /SOUTH|South/.test(x)));
    k.that('North: "Save changes" present', t2.some((x) => /Save changes/.test(x)), t2.filter((x) => /Save/.test(x)));
    k.that('North: "Send email" present', t2.some((x) => /Send email/.test(x)), t2.filter((x) => /email/i.test(x)).slice(0, 5));
    return { ok: k.ok, actual: k.summary(), screenshot: s1, evidence: s2, steps: `nora opens /invoices/${I.s2[1].id} then /invoices/${I.n2[1].id}`,
      expected: 'PRD B1 read in one region, manage in another; DESIGN §7.5 canManage / hasIn(regionId)' };
  });

  await ui({ id: 'RGR-X-U05', feature: 'Pending banner for a reader', prd: 'B2',
    title: 'A READ viewer (nora) of a South invoice whose cancel is held sees "Awaiting approval: …" and the invoice is still Unpaid; the list row shows the Awaiting approval chip' }, T.nora, heldCancelInvoice ? `/invoices/${heldCancelInvoice.id}` : '/invoices', async ({ page }) => {
    if (!heldCancelInvoice || !heldCancelInvoice.pcId) return { status: 'BLOCKED', actual: 'RGR-X-10 did not leave a held cancel' };
    const k = checks();
    await waitText(page, /Awaiting approval/, 10000);
    const t = await rt.screenText(page);
    const s1 = await rt.shot(page, SHOTS, 'RGR-X-U05-1');
    k.that('banner "Awaiting approval: … Cancel …"', t.some((x) => /^Awaiting approval: .*Cancel/i.test(x) || /Awaiting approval: .*/.test(x)), t.filter((x) => /Awaiting/.test(x)));
    k.that('status Unpaid', t.some((x) => /^Unpaid$/i.test(x)), t.filter((x) => /paid|cancel/i.test(x)).slice(0, 6));
    k.that('View button', t.includes('View'), t.filter((x) => /View/.test(x)));
    await rt.go(page, '/invoices');
    await waitText(page, new RegExp(heldCancelInvoice.number));
    const t2 = await rt.screenText(page);
    const s2 = await rt.shot(page, SHOTS, 'RGR-X-U05-2');
    const row = t2.filter((x) => x.includes(heldCancelInvoice.number));
    k.that('list shows an "Awaiting approval" indicator', t2.some((x) => /Awaiting approval/i.test(x)), row.slice(0, 3));
    return { ok: k.ok, actual: k.summary(), screenshot: s1, evidence: s2, steps: `RGR-X-10 left invoice ${heldCancelInvoice.number} with a held cancel; nora opens it and the list`,
      expected: 'PRD B2 "a pending change is visible to everyone who can see the record, without having taken effect"; harden TRIAGE CF-11/PC-1 chip' };
  });

  await ui({ id: 'RGR-X-U06', feature: 'Customer create dialog region', prd: 'B1',
    title: 'sam (manages North and South) gets a required Region dropdown in New customer; saving without it shows "Choose the region this customer belongs to"' }, T.sam, '/customers', async ({ page }) => {
    const k = checks();
    await waitText(page, /New customer/);
    await rt.tap(page, 'New customer', { wait: 2000 });
    const t = await rt.screenText(page);
    const hasRegion = (await rt.semantics(page)).some((n) => /Region/.test(n.label || '') || /Region/.test(n.text || ''));
    const cname = rt.uniq('UIcust');
    await rt.fillField(page, /^Name/, cname);
    const fields = (await rt.semantics(page)).filter((n) => n.role === 'textbox').map((n) => n.label);
    if (fields.some((l) => /Username/.test(l || ''))) await rt.fillField(page, /Username/, rt.uniq('uic'));
    if (fields.some((l) => /^Password/.test(l || ''))) await rt.fillField(page, /^Password/, rt.PASSWORD);
    const saveBtn = (await rt.semantics(page)).find((n) => /^(Save|Create)$/.test(n.label || n.text || ''));
    if (saveBtn) await rt.clickAt(page, saveBtn.x, saveBtn.y, 2000);
    const t2 = await rt.screenText(page);
    const s = await rt.shot(page, SHOTS, 'RGR-X-U06-1');
    const created = await rt.list(T.admin, '/api/customers', { filter: `name:eq:${cname}` });
    k.that('dialog open', t.some((x) => /New customer/.test(x)));
    k.that('Region field present', hasRegion, t.filter((x) => /Region/.test(x)));
    k.that('Save pressed', !!saveBtn, fields);
    k.that('validation "Choose the region this customer belongs to"', t2.some((x) => /Choose the region this customer belongs to/.test(x)), t2.filter((x) => /region|Required/i.test(x)));
    k.that('nothing created', created.json && created.json.content.length === 0, created.json && created.json.totalElements);
    return { ok: k.ok, actual: k.summary(), screenshot: s, steps: 'sam /customers -> New customer; fill name (and login fields); Save without region',
      expected: 'DESIGN §7.5 "customer dialog shows a required Region dropdown when there is more than one choice"; §6.4 message' };
  });

  await tc({ id: 'RGR-X-U07', kind: 'UI', feature: 'Login keyboard flow', prd: 'B1',
    title: 'Login by keyboard: wrong password shows "Invalid username or password"; Tab to password and Enter signs nora in, and her selector lists her two regions' }, async () => {
    const app = await rt.openApp({});
    const { page } = app;
    try {
      const k = checks();
      await rt.fillField(page, 'Username', 'nora');
      await page.keyboard.press('Tab');
      await rt.typeText(page, 'wrong-pass');
      await page.keyboard.press('Enter');
      await page.waitForTimeout(3000); await rt.enableSemantics(page);
      const t1 = await rt.screenText(page);
      const s1 = await rt.shot(page, SHOTS, 'RGR-X-U07-1');
      k.that('error "Invalid username or password"', t1.some((x) => /Invalid username or password/.test(x)), t1.slice(0, 10));
      await rt.fillField(page, /^Password/, rt.PASSWORD);
      await page.keyboard.press('Enter');
      await page.waitForTimeout(5000); await rt.enableSemantics(page);
      const sel = await rt.regionSelector(page);
      const t2 = await rt.screenText(page);
      const s2 = await rt.shot(page, SHOTS, 'RGR-X-U07-2');
      k.that('signed in (no Sign in button)', !t2.includes('Sign in'), t2.slice(0, 10));
      k.that('region selector present', !!sel, t2.slice(0, 15));
      return { ok: k.ok, actual: k.summary(), screenshot: s2, evidence: s1, steps: 'login screen: type nora, Tab, wrong password, Enter; then correct password, Enter',
        expected: 'Pre-existing login unchanged; B1 selector for a multi-region user' };
    } finally { await app.close(); }
  });

  await ui({ id: 'RGR-X-U08', feature: 'Customer login shell', prd: 'B3',
    title: 'A customer login has neither the region selector nor the as-of chip, and its invoices list shows its own invoices' }, T.cust, '/invoices', async ({ page, requests }) => {
    const k = checks();
    await waitText(page, new RegExp(I.s1[0].invoiceNumber));
    const sel = await rt.regionSelector(page);
    const chip = await rt.asOfChip(page);
    const t = await rt.screenText(page);
    const s = await rt.shot(page, SHOTS, 'RGR-X-U08-1');
    k.that('no region selector', !sel);
    k.that('no as-of chip', !chip, chip && (chip.label || chip.text));
    k.that('own invoices listed', I.s1.every((x) => t.some((y) => y.includes(x.invoiceNumber))), t.filter((x) => /INV/.test(x)).slice(0, 5));
    k.that('no regionIds/asOf sent', !requests.some((r) => /regionIds=|asOf=/.test(r.url)), requests.filter((r) => /regionIds=|asOf=/.test(r.url)).map((r) => r.url));
    return { ok: k.ok, actual: k.summary(), screenshot: s, steps: 'open /invoices as the s1 customer login', expected: 'DESIGN §7.3.1 selector hidden and as-of chip staff only' };
  });

  await ui({ id: 'RGR-X-U09', feature: 'Dashboard by region', prd: 'B1',
    title: 'nora\'s dashboard re-asks every figure with regionIds when she narrows to SOUTH, and the top-outstanding table lists only South customers' }, T.nora, '/', async ({ page, requests }) => {
    const k = checks();
    await page.waitForTimeout(2000); await rt.enableSemantics(page);
    const before = requests.length;
    await rt.chooseRegions(page, [/^NORTH/]);
    await page.waitForTimeout(3000); await rt.enableSemantics(page);
    const dash = requests.slice(before).filter((r) => /^\/api\/dashboard\//.test(r.url));
    const t = await rt.screenText(page);
    const s = await rt.shot(page, SHOTS, 'RGR-X-U09-1');
    const kinds = [...new Set(dash.map((r) => r.url.split('?')[0]))];
    k.that('dashboard endpoints re-asked', kinds.length >= 3, kinds);
    k.that(`every re-ask carries regionIds=${S.id}`, dash.length > 0 && dash.every((r) => r.url.includes(`regionIds=${S.id}`)), dash.map((r) => r.url));
    k.that('South customer in figures', t.some((x) => /South S[12]/.test(x)), t.filter((x) => /South S/.test(x)).slice(0, 3));
    k.that('no North customer in figures', !t.some((x) => /North N[12]/.test(x)), t.filter((x) => /North N/.test(x)).slice(0, 3));
    return { ok: k.ok, actual: k.summary(), screenshot: s, steps: 'nora opens / ; untick NORTH in the region selector', expected: 'PRD B1 every dashboard figure answers which regions; DESIGN §6.9' };
  });

  // --------------------------------------------------------------------------------- aged (last)
  await tc({ id: 'RGR-X-18', feature: 'As-of region membership', kind: 'API', prd: 'B3',
    title: 'After a North -> South move, lists, summaries and dashboard asked for North as of before the move include the customer; live they do not; nora\'s rights follow the region at that date' }, async () => {
    const k = checks();
    rt.ageWorld(10);
    const mv = await rt.moveCustomerRegion(T.admin, C.n2.id, S.id, 'final explore');
    const asOf = rt.day(-2);
    const liveN = await rt.list(T.nora, '/api/customers', {}, { regionIds: [N.id] });
    const pastN = await rt.list(T.nora, '/api/customers', {}, { regionIds: [N.id], asOf });
    const pastS = await rt.list(T.nora, '/api/customers', {}, { regionIds: [S.id], asOf });
    const liveInvN = await rt.list(T.nora, '/api/invoices', {}, { regionIds: [N.id] });
    const pastInvN = await rt.list(T.nora, '/api/invoices', {}, { regionIds: [N.id], asOf });
    const liveTop = await rt.api('GET', '/api/dashboard/top-outstanding-customers', { token: T.nora, query: { regionIds: N.id, limit: 20 } });
    const pastTop = await rt.api('GET', '/api/dashboard/top-outstanding-customers', { token: T.nora, query: { regionIds: N.id, limit: 20, asOf } });
    const liveC = await rt.api('GET', `/api/customers/${C.n2.id}`, { token: T.nora });
    const pastC = await rt.api('GET', `/api/customers/${C.n2.id}`, { token: T.nora, query: { asOf } });
    const wPast = await rt.api('GET', `/api/customers/${C.n2.id}`, { token: T.wendy, query: { asOf } });
    const bobLive = await rt.list(T.bob, '/api/invoices');
    const topIds = (r) => (r.json && r.json.customers || []).map((c) => c.customerId);
    k.that('move 200', mv.status === 200, brief(mv).slice(0, 300));
    k.that('live North excludes n2', !ids(liveN.json.content).includes(C.n2.id), ids(liveN.json.content));
    k.that('as-of North includes n2', ids(pastN.json.content).includes(C.n2.id), ids(pastN.json.content));
    k.that('as-of South excludes n2', !ids(pastS.json.content).includes(C.n2.id), ids(pastS.json.content));
    k.that('as-of invoices North include n2 invoices', ids(I.n2).every((x) => ids(pastInvN.json.content).includes(x)), ids(pastInvN.json.content));
    k.that('live invoices North exclude n2 invoices', ids(I.n2).every((x) => !ids(liveInvN.json.content).includes(x)), ids(liveInvN.json.content));
    k.that('dashboard as-of North includes n2; live does not', topIds(pastTop).includes(C.n2.id) && !topIds(liveTop).includes(C.n2.id), { past: topIds(pastTop), live: topIds(liveTop) });
    k.that('live n2: regionId South, canManage false for nora', liveC.status === 200 && liveC.json.regionId === S.id && liveC.json.canManage === false, liveC.json && { r: liveC.json.regionId, cm: liveC.json.canManage });
    k.that('as-of n2: regionId North, canManage false (historical)', pastC.status === 200 && pastC.json.regionId === N.id && !pastC.json.canManage, pastC.json && { r: pastC.json.regionId, cm: pastC.json.canManage });
    k.that('wendy as-of n2 -> 404', wPast.status === 404, brief(wPast));
    k.that('bob book unaffected (still his n1 invoices, none of n2)', ids(I.n1).every((x) => ids(bobLive.json.content).includes(x)) && ids(I.n2).every((x) => !ids(bobLive.json.content).includes(x)), ids(bobLive.json.content));
    return { ok: k.ok, actual: k.summary(), steps: 'ageWorld(10); admin moves n2 North->South; nora reads lists/summary/dashboard/by-id live and asOf=today-2 with regionIds North/South',
      expected: 'PRD B3 "including which region a record belonged to then"; B1', evidence: `move moved=${JSON.stringify(mv.json && mv.json.moved)}` };
  });

  save();
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); rec({ id: 'RGR-X-HARNESS', title: 'explore aborted', status: 'BLOCKED', actual: String(e && e.stack || e) }); save(); process.exit(2); });
