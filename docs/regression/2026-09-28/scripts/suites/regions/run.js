// E2E suite, area "regions" (PRD B1). Usage:
//   source <e2e>/runtime/regions/env.sh && node run.js <round>     (or E2E_ROUND=<n>)
//   ONLY=<regex of case ids> to run a subset;  SKIP_UI=1 / SKIP_API=1;  SKIP_ASOF=1 skips the ageWorld phase.
// Results: <e2e>/runs/r<round>/regions.json, screenshots: <e2e>/runs/r<round>/regions/shots/.
const rt = require('../../lib.js');
const { makeRunner, same, ids, excerpt, sortN } = require('./h.js');
const ui = require('./ui.js');

const round = Number(process.argv[2] || process.env.E2E_ROUND || 1);
const RUN = makeRunner({ round });
const { C } = RUN;

const PW = rt.PASSWORD;
const NOT_REACHABLE = 'Not found, or outside your scope or the current filter';

// ------------------------------------------------------------------------------------------
// Fixtures
// ------------------------------------------------------------------------------------------
async function ensureStaff(admin, username, roleName, grants, fullName) {
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, roleName, username, { username, fullName: fullName || username, grants });
  else {
    rt.need(await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { roleId: await rt.roleId(admin, roleName), password: PW, active: true } }), `role ${username}`);
    rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
  }
  return { ...u, username, password: PW };
}

async function setup() {
  const admin = await rt.adminToken();
  const w = await rt.seedWorld(admin);
  const R = { N: w.regions.NORTH.id, S: w.regions.SOUTH.id, W: w.regions.WEST.id, D: w.regions.DEFAULT.id };
  const me = await rt.me(admin);
  const U = { nora: w.users.nora, sam: w.users.sam, bob: w.users.bob, wendy: w.users.wendy, admin: me };
  const T = { ...w.tokens };

  // mia: MANAGE/ALL North + MANAGE/BOOK South (a mixed view).
  U.mia = await ensureStaff(admin, 'mia', 'E2E_REGIONAL', [
    { regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.S, accessLevel: 'MANAGE', recordScope: 'BOOK' }], 'Mia Mixed');
  // ghost: a staff login holding no region at all.
  U.ghost = await ensureStaff(admin, 'ghost', 'E2E_REGIONAL', [], 'Ghost Nogrant');
  // uma: a user administrator whose own reach is MANAGE/ALL North only (granter bound, §4.6).
  await rt.ensureRole(admin, 'E2E_USERADMIN', [...rt.REGIONAL_STAFF_PRIVILEGES, 'USER_VIEW', 'USER_MANAGE', 'ROLE_VIEW'], 'E2E: user admin');
  U.uma = await ensureStaff(admin, 'uma', 'E2E_USERADMIN', [{ regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }], 'Uma Useradmin');
  U.tina = await ensureStaff(admin, 'tina', 'E2E_REGIONAL', [{ regionId: R.S, accessLevel: 'READ', recordScope: 'ALL' }], 'Tina Target');
  for (const n of ['mia', 'ghost', 'uma', 'tina']) T[n] = await rt.login(n);

  // mia is the Sales POC of one South invoice (her South book).
  const miaInv = rt.need(await rt.createInvoice(admin, { customerId: w.customers.s2.id, amount: 100, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: U.mia.id }), 'mia invoice', [200]);

  // Promises, disputes and tasks in North, South and West.
  const collector = { n1: U.bob.id, n2: U.nora.id, s1: U.sam.id, s2: U.sam.id, w1: U.wendy.id, w2: U.wendy.id, d1: me.id };
  const promises = {};
  for (const k of ['n1', 's2', 'w1']) {
    promises[k] = rt.need(await rt.createPromise(admin, { customerId: w.customers[k].id, amount: 100, collectionPocUserId: collector[k] }), `promise ${k}`, [200]);
  }
  const disputes = {};
  for (const k of ['n1', 's1', 'w1']) {
    const ct = await rt.login(w.customers[k].username, PW);
    disputes[k] = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: w.invoices[k][0].id, reason: `e2e ${k}` }), `dispute ${k}`);
  }
  const tasksBy = {};
  for (const k of ['n1', 'n2', 's1', 'w1']) {
    tasksBy[k] = rt.need(await rt.createTask(admin, { entityType: 'INVOICE', entityId: w.invoices[k][0].id, title: `e2e task ${k}` }), `task ${k}`);
  }
  return { admin, w, R, U, T, collector, miaInv, promises, disputes, tasks: tasksBy };
}

// A throw-away customer in a region, with a primary Collection POC and (optionally) invoices of `amounts`.
async function mkCustomer(F, key, { amounts = [100], login = false, prefix = 'x' } = {}) {
  const regionId = F.R[key];
  const col = { N: F.U.nora.id, S: F.U.sam.id, W: F.U.wendy.id, D: F.U.admin.id }[key];
  const c = await rt.createCustomer(F.admin, { name: `${prefix}-${key}-${rt.uniq('c')}`, regionId, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(F.admin, c.id, 'COLLECTION', col, true);
  const invoices = [];
  for (const a of amounts) {
    const r = await rt.createInvoice(F.admin, { customerId: c.id, amount: a, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: col });
    invoices.push(rt.need(r, 'scratch invoice', [200]));
  }
  return { ...c, col, invoices, token: login ? await rt.login(c.username, c.password) : null };
}

const get = (t, p, query) => rt.api('GET', p, { token: t, query });

// ------------------------------------------------------------------------------------------
// API cases
// ------------------------------------------------------------------------------------------
async function apiCases(F) {
  const { admin, w, R, U, T } = F;
  const NS = sortN([R.N, R.S]);
  const regionsOf = (m) => Object.fromEntries((m.regions || []).map((r) => [r.code, r]));

  // ---------------- /me and login: rights differ per region ----------------
  await C({ id: 'REG-01', feature: '/me regions', prd: 'B1', title: 'GET /api/auth/me lists each granted region with its own level and effective privileges',
    steps: 'nora (MANAGE/ALL North, READ/ALL South, nothing West) GET /api/auth/me',
    expected: 'regions = NORTH(MANAGE, ALL, privileges incl. INVOICE_MANAGE) + SOUTH(READ, ALL, INVOICE_VIEW but no INVOICE_MANAGE); no WEST/DEFAULT; union has no CHANGE_APPROVE (DESIGN §4.7, §6.2)' }, async () => {
    const m = await rt.me(T.nora);
    const g = regionsOf(m);
    const ok = Object.keys(g).sort().join() === 'NORTH,SOUTH' && g.NORTH.level === 'MANAGE' && g.NORTH.scope === 'ALL' && g.SOUTH.level === 'READ'
      && g.NORTH.privileges.includes('INVOICE_MANAGE') && !g.SOUTH.privileges.includes('INVOICE_MANAGE') && g.SOUTH.privileges.includes('INVOICE_VIEW')
      && m.privileges.includes('INVOICE_MANAGE') && !m.privileges.includes('CHANGE_APPROVE');
    return { ok, actual: { regions: (m.regions || []).map((r) => ({ code: r.code, level: r.level, scope: r.scope, manage: r.privileges.includes('INVOICE_MANAGE') })), changeApproveInUnion: m.privileges.includes('CHANGE_APPROVE') } };
  });

  await C({ id: 'REG-02', feature: '/me regions', prd: 'B1', title: 'The login response carries the same per-region rights as /me',
    steps: 'POST /api/auth/login nora; compare user.regions with GET /api/auth/me', expected: 'identical regions arrays (DESIGN §4.7)' }, async () => {
    const l = await rt.loginFull('nora', PW);
    const m = await rt.me(l.token);
    const a = JSON.stringify((l.user.regions || []).map((r) => [r.id, r.level, r.scope]));
    const b = JSON.stringify((m.regions || []).map((r) => [r.id, r.level, r.scope]));
    return { ok: a === b && (l.user.regions || []).length === 2, actual: { login: a, me: b } };
  });

  await C({ id: 'REG-03', feature: '/me regions', prd: 'B1', title: 'A customer login has regions [] and a West-only user has exactly [WEST]; sam holds CHANGE_APPROVE only in South',
    steps: 'GET /api/auth/me as cust, wendy, sam', expected: 'cust regions []; wendy [WEST MANAGE]; sam SOUTH privileges incl. CHANGE_APPROVE, NORTH without (§4.2)' }, async () => {
    const c = await rt.me(T.cust); const wd = await rt.me(T.wendy); const s = regionsOf(await rt.me(T.sam));
    const ok = Array.isArray(c.regions) && c.regions.length === 0 && (wd.regions || []).map((r) => r.code).join() === 'WEST'
      && s.SOUTH?.privileges.includes('CHANGE_APPROVE') && !s.NORTH?.privileges.includes('CHANGE_APPROVE');
    return { ok, actual: { cust: c.regions, wendy: (wd.regions || []).map((r) => `${r.code}:${r.level}`), samSouthApprove: s.SOUTH?.privileges.includes('CHANGE_APPROVE'), samNorthApprove: s.NORTH?.privileges.includes('CHANGE_APPROVE') } };
  });

  // ---------------- Lists: exactly the granted regions ----------------
  const LISTS = [['REG-10', 'customers', '/api/customers'], ['REG-11', 'invoices', '/api/invoices'], ['REG-12', 'payments', '/api/payments'],
    ['REG-13', 'promises', '/api/promises'], ['REG-14', 'disputes', '/api/disputes'], ['REG-15', 'tasks', '/api/tasks']];
  for (const [id, name, p] of LISTS) {
    await C({ id, feature: 'Region-scoped lists', prd: 'B1', title: `${name} list for a North+South user holds exactly the North and South rows`,
      steps: `admin GET ${p}?regionIds=N,S (reference); nora GET ${p} (no regionIds)`,
      expected: `same id set as admin's North+South; envelope regionIds=[${NS}], bookRegionIds=[]; no West/DEFAULT row (PRD B1 "every list answers which regions"; DESIGN §6.0)` }, async () => {
      const ref = await rt.list(admin, p, {}, { regionIds: NS });
      const got = await rt.list(T.nora, p);
      const wref = await rt.list(admin, p, {}, { regionIds: [R.W] });
      const leak = (ids(got) || []).filter((x) => (ids(wref) || []).includes(x));
      const ok = got.status === 200 && same(ids(got), ids(ref)) && same(got.json.regionIds, NS) && got.json.bookRegionIds.length === 0 && leak.length === 0 && ids(ref).length > 0;
      return { ok, actual: { status: got.status, nora: sortN(ids(got) || []), expected: sortN(ids(ref) || []), regionIds: got.json?.regionIds, westLeak: leak }, evidence: excerpt(got, 200) };
    });
  }

  await C({ id: 'REG-16', feature: 'Region-scoped lists', prd: 'B1', title: 'A West-only user sees only West invoices, customers and payments',
    steps: 'wendy GET /api/invoices, /api/customers, /api/payments; admin same with regionIds=W', expected: 'id sets equal admin West; regionIds [W]' }, async () => {
    const out = {}; let ok = true;
    for (const p of ['/api/invoices', '/api/customers', '/api/payments']) {
      const a = await rt.list(T.wendy, p); const ref = await rt.list(admin, p, {}, { regionIds: [R.W] });
      const good = same(ids(a), ids(ref)) && same(a.json?.regionIds, [R.W]);
      out[p] = { n: ids(a)?.length, ref: ids(ref)?.length, regionIds: a.json?.regionIds, good }; ok = ok && good;
    }
    return { ok, actual: out };
  });

  await C({ id: 'REG-17', feature: 'Region selector (API)', prd: 'B1', title: 'regionIds=North narrows lists to North only and echoes regionIds [N]',
    steps: 'nora GET /api/{invoices,customers,payments,promises}?regionIds=N', expected: 'rows = admin North rows; PageResponse.regionIds == [N] (DESIGN §6.0)' }, async () => {
    const out = {}; let ok = true;
    for (const p of ['/api/invoices', '/api/customers', '/api/payments', '/api/promises']) {
      const a = await rt.list(T.nora, p, {}, { regionIds: [R.N] }); const ref = await rt.list(admin, p, {}, { regionIds: [R.N] });
      const good = a.status === 200 && same(ids(a), ids(ref)) && same(a.json.regionIds, [R.N]);
      out[p] = { got: ids(a)?.length, ref: ids(ref)?.length, regionIds: a.json?.regionIds }; ok = ok && good;
    }
    return { ok, actual: out };
  });

  await C({ id: 'REG-18', feature: 'Region selector (API)', prd: 'B1', title: 'Asking for an ungranted region answers 200 with nothing and regionIds []',
    steps: 'nora GET /api/invoices?regionIds=<West>; ?regionIds=<N>,<W>,999999', expected: '200, content [], totalElements 0, regionIds []; the mixed ask answers [N] with the North rows (DESIGN §6.0 "Unknown or ungranted ids contribute nothing")' }, async () => {
    const r = await get(T.nora, '/api/invoices', { regionIds: R.W, size: 50 });
    const mix = await get(T.nora, '/api/invoices', { regionIds: `${R.N},${R.W},999999`, size: 50 });
    const ref = await get(T.nora, '/api/invoices', { regionIds: R.N, size: 50 });
    const ok = r.status === 200 && r.json.totalElements === 0 && r.json.regionIds.length === 0 && mix.status === 200 && same(mix.json.regionIds, [R.N]) && mix.json.totalElements === ref.json.totalElements;
    return { ok, actual: { west: excerpt(r, 250), mixed: mix.json?.regionIds || excerpt(mix, 150), mixedTotal: mix.json?.totalElements, northTotal: ref.json?.totalElements } };
  });

  await C({ id: 'REG-19', feature: 'Region selector (API)', prd: 'B1', title: 'regionIds accepts the comma and repeated forms and refuses garbage with 400 REGION_IDS_INVALID',
    steps: 'nora GET /api/invoices?regionIds=N&regionIds=S; ?regionIds=abc; ?regionIds=0; ?regionIds=N,,S', expected: 'repeated form -> regionIds [N,S]; abc and 0 -> 400 error=REGION_IDS_INVALID; blank tokens skipped (DESIGN §5.2.2)' }, async () => {
    const rep = await rt.api('GET', `/api/invoices?size=10&regionIds=${R.N}&regionIds=${R.S}`, { token: T.nora });
    const bad = await rt.api('GET', '/api/invoices?regionIds=abc', { token: T.nora });
    const zero = await rt.api('GET', '/api/invoices?regionIds=0', { token: T.nora });
    const blanks = await rt.api('GET', `/api/invoices?size=10&regionIds=${R.N},,${R.S}`, { token: T.nora });
    const ok = rep.status === 200 && same(rep.json.regionIds, NS) && bad.status === 400 && bad.json?.error === 'REGION_IDS_INVALID'
      && zero.status === 400 && zero.json?.error === 'REGION_IDS_INVALID' && blanks.status === 200 && same(blanks.json.regionIds, NS);
    return { ok, actual: { repeated: rep.json?.regionIds, abc: excerpt(bad, 160), zero: excerpt(zero, 160), blanks: blanks.json?.regionIds || excerpt(blanks, 160) } };
  });

  await C({ id: 'REG-20', feature: 'No grants', prd: 'B1', title: 'A staff login with no grant sees nothing regional (no regional privilege, no rows, no record)',
    steps: 'ghost (E2E_REGIONAL, no grants) GET /api/auth/me, /api/invoices, /api/customers, /api/invoices/{n1 invoice}',
    expected: '/me regions [] and no regional privilege in the union (D6: authorities = effective anywhere); lists answer 403 (no right anywhere) or an empty 200; the record 403/404 — never data (DESIGN §4.6, D15)' }, async () => {
    const m = await rt.me(T.ghost);
    const a = await get(T.ghost, '/api/invoices', { size: 10 }); const b = await get(T.ghost, '/api/customers', { size: 10 });
    const c = await get(T.ghost, `/api/invoices/${w.invoices.n1[0].id}`);
    const none = (r) => r.status === 403 || (r.status === 200 && r.json.totalElements === 0);
    const regional = m.privileges.filter((p) => rt.REGIONAL_PRIVILEGES.includes(p));
    const ok = m.regions.length === 0 && regional.length === 0 && none(a) && none(b) && [403, 404].includes(c.status);
    return { ok, actual: { meRegions: m.regions, regionalInUnion: regional, invoices: a.status, customers: b.status, detail: c.status } };
  });

  await C({ id: 'REG-21', feature: 'Region-scoped lists', prd: 'B1', title: 'The DEFAULT region is its own region: admin sees d1, nora does not',
    steps: 'admin and nora GET /api/customers?filter=id:eq:<d1>', expected: 'admin 1 row (regionCode DEFAULT), nora 0 rows' }, async () => {
    const a = await get(admin, '/api/customers', { filter: `id:eq:${w.customers.d1.id}`, size: 10 });
    const n = await get(T.nora, '/api/customers', { filter: `id:eq:${w.customers.d1.id}`, size: 10 });
    const ok = a.json?.totalElements === 1 && a.json.content[0].regionCode === 'DEFAULT' && n.json?.totalElements === 0;
    return { ok, actual: { admin: a.json?.content?.map((c) => c.regionCode), nora: n.json?.totalElements, nstatus: n.status, astatus: a.status, err: a.status !== 200 ? excerpt(a) : '' } };
  });

  await C({ id: 'REG-22', feature: 'Region column', prd: 'B1', title: 'List schemas expose regionId/regionName; filter regionId:in and sort by regionName work',
    steps: 'GET /api/table-schemas/invoices; nora GET /api/invoices?filter=regionId:in:S&sort=regionName,desc', expected: 'schema has regionId and regionName columns; filtered rows all SOUTH (DESIGN §6.5)' }, async () => {
    const s = await get(T.nora, '/api/table-schemas/invoices');
    const cols = (s.json?.columns || []).map((c) => c.key || c.field || c.name);
    const f = await get(T.nora, '/api/invoices', { filter: `regionId:in:${R.S}`, sort: 'regionName,desc', size: 50 });
    const srt = await get(T.nora, '/api/invoices', { sort: 'regionName,asc', size: 50 });
    const names = (srt.json?.content || []).map((i) => i.regionName);
    const sortedOk = names.join() === [...names].sort().join();
    const ok = cols.includes('regionId') && cols.includes('regionName') && f.status === 200 && f.json.content.length > 0 && f.json.content.every((i) => i.regionId === R.S) && sortedOk;
    return { ok, actual: { cols: cols.filter((c) => /region/i.test(c || '')), filtered: f.status === 200 ? f.json.content.map((i) => i.regionCode) : excerpt(f), sorted: names } };
  });

  // ---------------- Summaries / counts ----------------
  await C({ id: 'REG-25', feature: 'Counts and tiles', prd: 'B1', title: 'Invoice summary tiles answer for the asked regions only',
    steps: 'nora GET /api/invoices/summary (all, regionIds=S, regionIds=W); admin same with regionIds=N,S / S',
    expected: 'nora all == admin N+S; nora S == admin S; nora W -> count 0 (PRD B1 "every count answers which regions")' }, async () => {
    const nAll = await get(T.nora, '/api/invoices/summary'); const aNS = await get(admin, '/api/invoices/summary', { regionIds: NS.join(',') });
    const nS = await get(T.nora, '/api/invoices/summary', { regionIds: R.S }); const aS = await get(admin, '/api/invoices/summary', { regionIds: R.S });
    const nW = await get(T.nora, '/api/invoices/summary', { regionIds: R.W });
    const ok = JSON.stringify(nAll.json) === JSON.stringify(aNS.json) && JSON.stringify(nS.json) === JSON.stringify(aS.json) && nW.json?.count === 0;
    return { ok, actual: { noraAll: nAll.json, adminNS: aNS.json, noraS: nS.json?.count, adminS: aS.json?.count, noraW: nW.json?.count } };
  });

  await C({ id: 'REG-26', feature: 'Counts and tiles', prd: 'B1', title: 'Customer, payment and promise summaries answer for the asked regions only',
    steps: 'nora GET /api/{customers,payments,promises}/summary?regionIds=N vs admin same', expected: 'equal to admin North; nora without regionIds equals admin N+S' }, async () => {
    const out = {}; let ok = true;
    for (const p of ['/api/customers/summary', '/api/payments/summary', '/api/promises/summary', '/api/tasks/summary']) {
      const a = await get(T.nora, p, { regionIds: R.N }); const b = await get(admin, p, { regionIds: R.N });
      const c = await get(T.nora, p); const d = await get(admin, p, { regionIds: NS.join(',') });
      if (a.status === 404 && b.status === 404) { out[p] = 'no endpoint'; continue; }
      const good = a.status === 200 && JSON.stringify(a.json) === JSON.stringify(b.json) && JSON.stringify(c.json) === JSON.stringify(d.json);
      out[p] = good ? 'ok' : { noraN: a.json, adminN: b.json, noraAll: c.json, adminNS: d.json }; ok = ok && good;
    }
    return { ok, actual: out };
  });

  await C({ id: 'REG-27', feature: 'Counts and tiles', prd: 'B1', title: 'Notifications and inbox lists echo the asked regions (regionIds) and ignore ungranted ones',
    steps: 'nora GET /api/notifications?regionIds=N ; ?regionIds=W; /api/inbox?regionIds=S ; ?regionIds=W; admin GET /api/notifications?regionIds=N / S', expected: '200; PageResponse.regionIds [N] / [] / [S] / []; admin\'s DISPUTE_OPENED notice for the North dispute appears only under regionIds=N and the South one only under S (DESIGN §5.6.7)' }, async () => {
    const a = await get(T.nora, '/api/notifications', { regionIds: R.N }); const b = await get(T.nora, '/api/notifications', { regionIds: R.W });
    const c = await get(T.nora, '/api/notifications/unread-count', { regionIds: R.W });
    const d = await get(T.nora, '/api/inbox', { regionIds: R.S, size: 10 }); const e = await get(T.nora, '/api/inbox', { regionIds: R.W, size: 10 });
    const links = async (rid) => ((await get(admin, '/api/notifications', { regionIds: rid, size: 50 })).json?.content || []).map((x) => x.link);
    const ln = await links(R.N); const ls = await links(R.S);
    const dn = `/disputes/${F.disputes.n1.id}`; const ds = `/disputes/${F.disputes.s1.id}`;
    const contentOk = ln.includes(dn) && !ln.includes(ds) && ls.includes(ds) && !ls.includes(dn);
    const ok = a.status === 200 && same(a.json.regionIds, [R.N]) && b.status === 200 && b.json.regionIds.length === 0 && c.status === 200
      && d.status === 200 && same(d.json.regionIds, [R.S]) && e.status === 200 && e.json.regionIds.length === 0 && contentOk;
    return { ok, actual: { n: a.json?.regionIds, w: b.json?.regionIds, unreadW: c.text?.slice(0, 80), inboxS: d.json?.regionIds || excerpt(d, 150), inboxW: e.json?.regionIds || excerpt(e, 150), adminNotifLinksN: ln, adminNotifLinksS: ls } };
  });


  await C({ id: 'REG-28', feature: 'Pickers', prd: 'B1', title: 'People pickers answer for the asked regions: /api/assignees/people and /api/pocs/assignable without a customer',
    steps: 'nora GET /api/assignees/people?regionIds=S&q=; GET /api/pocs/assignable?type=COLLECTION&regionIds=S', expected: 'only staff granted in South (sam, mia, tina, nora...) — never bob (North only) or wendy (West only) (DESIGN §6.8, §6.13)' }, async () => {
    const a = await get(T.nora, '/api/assignees/people', { regionIds: R.S, q: '', limit: 50 });
    const b = await get(T.nora, '/api/pocs/assignable', { type: 'COLLECTION', regionIds: R.S, limit: 50 });
    const names = (r) => (Array.isArray(r.json) ? r.json : r.json?.content || []).map((u) => u.username || u.name);
    const na = names(a); const nb = names(b);
    const ok = a.status === 200 && b.status === 200 && na.includes('sam') && !na.includes('bob') && !na.includes('wendy') && nb.includes('sam') && !nb.includes('bob') && !nb.includes('wendy');
    return { ok, actual: { people: na, assignable: nb, statuses: [a.status, b.status] } };
  });


  await C({ id: 'REG-29', feature: 'Counts and tiles', prd: 'B1', title: '"My tasks" list and summary answer for the asked regions',
    steps: 'admin creates tasks assigned to nora on a North and a South invoice; nora GET /api/tasks/mine?regionIds=N, ?regionIds=S, /api/tasks/mine/summary?regionIds=N',
    expected: 'N: the North task only, regionIds [N]; S: the South task only; summaries differ accordingly (DESIGN §6.1 "/api/tasks/** GETs honour regionIds")' }, async () => {
    const tn = rt.need(await rt.createTask(admin, { entityType: 'INVOICE', entityId: w.invoices.n1[1].id, title: rt.uniq('mine-n'), assignees: [rt.tok.user(U.nora.id)] }), 'task n');
    const ts = rt.need(await rt.createTask(admin, { entityType: 'INVOICE', entityId: w.invoices.s1[1].id, title: rt.uniq('mine-s'), assignees: [rt.tok.user(U.nora.id)] }), 'task s');
    const n = await rt.list(T.nora, '/api/tasks/mine', {}, { regionIds: [R.N] }); const s = await rt.list(T.nora, '/api/tasks/mine', {}, { regionIds: [R.S] });
    const sn = await get(T.nora, '/api/tasks/mine/summary', { regionIds: R.N }); const sa = await get(T.nora, '/api/tasks/mine/summary');
    const ok = ids(n).includes(tn.id) && !ids(n).includes(ts.id) && same(n.json.regionIds, [R.N]) && ids(s).includes(ts.id) && !ids(s).includes(tn.id)
      && sn.status === 200 && sa.status === 200 && sn.json.count < sa.json.count;
    return { ok, actual: { north: ids(n), south: ids(s), tn: tn.id, ts: ts.id, summaryN: sn.json, summaryAll: sa.json } };
  });

  // ---------------- Dashboard ----------------
  const DASH = ['/api/dashboard/billed-by-month?months=3', '/api/dashboard/outstanding-by-age', '/api/dashboard/top-outstanding-customers?limit=20',
    '/api/dashboard/collected-by-month?months=3', '/api/dashboard/top-paying-customers?months=3&limit=20'];
  const strip = (j) => { if (!j) return j; const { scope, ...rest } = j; return JSON.stringify(rest); };
  await C({ id: 'REG-30', feature: 'Dashboard', prd: 'B1', title: 'Every dashboard figure for a North+South user equals the North+South figures, with scope.regionIds [N,S]',
    steps: `nora GET ${DASH.join(', ')}; admin same with regionIds=N,S`, expected: 'figures identical; scope.regionIds == [N,S] (PRD B1 "every dashboard figure"; DESIGN §6.9)' }, async () => {
    const out = {}; let ok = true;
    for (const p of DASH) {
      const a = await rt.api('GET', p, { token: T.nora }); const b = await rt.api('GET', p, { token: admin, query: { regionIds: NS.join(',') } });
      const good = a.status === 200 && strip(a.json) === strip(b.json) && same(a.json.scope?.regionIds, NS);
      out[p] = good ? 'ok' : { nora: strip(a.json)?.slice(0, 300), admin: strip(b.json)?.slice(0, 300), scope: a.json?.scope }; ok = ok && good;
    }
    return { ok, actual: out };
  });

  await C({ id: 'REG-31', feature: 'Dashboard', prd: 'B1', title: 'Narrowing the dashboard to North changes every figure to the North figures',
    steps: 'nora GET each dashboard endpoint with regionIds=N; admin same', expected: 'identical to admin North; scope.regionIds [N]; top-outstanding lists only North customers' }, async () => {
    const out = {}; let ok = true;
    for (const p of DASH) {
      const a = await rt.api('GET', p, { token: T.nora, query: { regionIds: R.N } }); const b = await rt.api('GET', p, { token: admin, query: { regionIds: R.N } });
      const good = a.status === 200 && strip(a.json) === strip(b.json) && same(a.json.scope?.regionIds, [R.N]);
      out[p] = good ? 'ok' : { nora: strip(a.json)?.slice(0, 300), admin: strip(b.json)?.slice(0, 300) }; ok = ok && good;
    }
    const top = await rt.api('GET', '/api/dashboard/top-outstanding-customers?limit=20', { token: T.nora, query: { regionIds: R.N } });
    const southish = (top.json?.customers || []).filter((c) => !/^North|^x-N|^mv|^North/.test(c.customerName) && ![w.customers.n1.id, w.customers.n2.id].includes(c.customerId));
    const nIds = ids(await rt.list(admin, '/api/customers', {}, { regionIds: [R.N] }));
    const foreign = (top.json?.customers || []).filter((c) => !nIds.includes(c.customerId));
    ok = ok && foreign.length === 0;
    out.foreignInTop = foreign.map((c) => c.customerName); void southish;
    return { ok, actual: out };
  });

  await C({ id: 'REG-32', feature: 'Dashboard', prd: 'B1', title: 'A West-only user\'s dashboard shows only West customers and West totals',
    steps: 'wendy GET /api/dashboard/top-outstanding-customers, /billed-by-month vs admin regionIds=W', expected: 'equal to admin West; scope.regionIds [W]' }, async () => {
    const a = await rt.api('GET', DASH[2], { token: T.wendy }); const b = await rt.api('GET', DASH[2], { token: admin, query: { regionIds: R.W } });
    const c = await rt.api('GET', DASH[0], { token: T.wendy }); const d = await rt.api('GET', DASH[0], { token: admin, query: { regionIds: R.W } });
    const ok = strip(a.json) === strip(b.json) && strip(c.json) === strip(d.json) && same(a.json.scope.regionIds, [R.W]);
    return { ok, actual: { top: (a.json?.customers || []).map((x) => x.customerName), billed: strip(c.json)?.slice(0, 200), scope: a.json?.scope } };
  });

  // ---------------- Detail / by-id: 200 with flags, 404 when invisible ----------------
  const DETAIL = [
    ['REG-35', 'customer', (k) => `/api/customers/${w.customers[k].id}`, 'canManage'],
    ['REG-36', 'invoice', (k) => `/api/invoices/${w.invoices[k][0].id}`, 'canManage'],
    ['REG-37', 'payment', (k) => `/api/payments/${w.payments[k][0].id}`, 'canManage'],
    ['REG-38', 'promise', (k) => `/api/promises/${F.promises[{ n1: 'n1', s1: 's2', w1: 'w1' }[k]].id}`, 'canManage'],
    ['REG-39', 'dispute', (k) => `/api/disputes/${F.disputes[k].id}`, 'canResolve'],
  ];
  for (const [id, name, pathFor, flag] of DETAIL) {
    await C({ id, feature: 'Detail read matrix', prd: 'B1', title: `${name} detail: North 200 ${flag}=true, South 200 ${flag}=false, West 404`,
      steps: `nora GET ${pathFor('n1')}, ${pathFor('s1')}, ${pathFor('w1')}`,
      expected: `N 200 ${flag} true (MANAGE); S 200 ${flag} false (READ only); W 404 (no grant) (PRD B1 read/manage/nothing; DESIGN D14/D15)` }, async () => {
      const n = await get(T.nora, pathFor('n1')); const s = await get(T.nora, pathFor('s1')); const x = await get(T.nora, pathFor('w1'));
      const ok = n.status === 200 && n.json[flag] === true && s.status === 200 && s.json[flag] === false && x.status === 404;
      return { ok, actual: { north: [n.status, n.json?.[flag], n.json?.regionCode], south: [s.status, s.json?.[flag], s.json?.regionCode], west: excerpt(x, 150) } };
    });
  }

  await C({ id: 'REG-40', feature: 'Detail read matrix', prd: 'B1', title: 'By-id reads ignore regionIds (deep links keep working when the selector excludes the region)',
    steps: 'nora GET /api/invoices/{s1 invoice}?regionIds=N', expected: '200 (DESIGN §4.2 invariant I3)' }, async () => {
    const r = await get(T.nora, `/api/invoices/${w.invoices.s1[0].id}`, { regionIds: R.N });
    return { ok: r.status === 200, actual: excerpt(r, 120) };
  });

  await C({ id: 'REG-41', feature: 'Record tabs', prd: 'B1', title: 'Record tabs of an invisible (West) record answer 404; of a readable (South) record 200',
    steps: 'nora GET /api/audit, /api/documents, /api/documents/count, /api/emails (entityType=INVOICE&entityId=W/S), /api/customers/{W}/pocs, /api/payments/credits/{W}',
    expected: 'every West call 404; every South call 200 (DESIGN D15, §5.6.5)' }, async () => {
    const wi = w.invoices.w1[0].id; const si = w.invoices.s1[0].id; const wc = w.customers.w1.id; const sc = w.customers.s1.id;
    const paths = (inv, cust) => [`/api/audit?entityType=INVOICE&entityId=${inv}`, `/api/documents?entityType=INVOICE&entityId=${inv}`,
      `/api/documents/count?entityType=INVOICE&entityId=${inv}`, `/api/emails?entityType=INVOICE&entityId=${inv}`,
      `/api/customers/${cust}/pocs`, `/api/payments/credits/${cust}`, `/api/audit?entityType=CUSTOMER&entityId=${cust}&includeRelated=true`];
    const out = {}; let ok = true;
    for (const p of paths(wi, wc)) { const r = await get(T.nora, p); out['W ' + p] = r.status; ok = ok && r.status === 404; }
    for (const p of paths(si, sc)) { const r = await get(T.nora, p); out['S ' + p] = r.status; ok = ok && r.status === 200; }
    return { ok, actual: out };
  });

  await C({ id: 'REG-42', feature: 'Record tabs', prd: 'B1', title: 'Per-record list tabs (tasks, promises, disputes) for a West record come back empty for nora',
    steps: 'nora GET /api/tasks?entityType=INVOICE&entityId=<w1 inv>, /api/promises?customerId=<w1>, /api/disputes?customerId=<w1>; admin same',
    expected: 'nora 0 rows each (admin > 0 for tasks/promises/disputes)' }, async () => {
    const q = [['/api/tasks', { entityType: 'INVOICE', entityId: w.invoices.w1[0].id }], ['/api/promises', { customerId: w.customers.w1.id }], ['/api/disputes', { customerId: w.customers.w1.id }]];
    const out = {}; let ok = true;
    for (const [p, qq] of q) {
      const n = await get(T.nora, p, { ...qq, size: 10 }); const a = await get(admin, p, { ...qq, size: 10 });
      out[p] = { nora: n.status === 200 ? n.json.totalElements : n.status, admin: a.json?.totalElements };
      ok = ok && (n.status === 404 || (n.status === 200 && n.json.totalElements === 0)) && a.json?.totalElements > 0;
    }
    return { ok, actual: out };
  });


  await C({ id: 'REG-43', feature: 'Record tabs', prd: 'B1', title: 'Document flags are region-aware: editable on a North record, read-only on a South one',
    steps: 'admin uploads a PDF to a North and a South invoice; nora GET /api/documents?entityType=INVOICE&entityId=…', expected: 'North canEdit/canDelete true; South false/false (DESIGN §5.6.6 DocumentService.toDto, §6.5)' }, async () => {
    const up = async (inv) => { const fd = new FormData(); fd.append('file', new Blob(['%PDF-1.4 e2e'], { type: 'application/pdf' }), 'flags.pdf'); fd.append('entityType', 'INVOICE'); fd.append('entityId', String(inv)); fd.append('visibility', 'INTERNAL'); return rt.api('POST', '/api/documents', { token: admin, body: fd, raw: true }); };
    const un = await up(w.invoices.n1[1].id); const us = await up(w.invoices.s1[1].id);
    const ln = await get(T.nora, '/api/documents', { entityType: 'INVOICE', entityId: w.invoices.n1[1].id }); const ls = await get(T.nora, '/api/documents', { entityType: 'INVOICE', entityId: w.invoices.s1[1].id });
    const dn = (ln.json?.content || []).find((d) => d.id === un.json?.id); const ds = (ls.json?.content || []).find((d) => d.id === us.json?.id);
    const ok = dn?.canEdit === true && dn?.canDelete === true && ds?.canEdit === false && ds?.canDelete === false;
    return { ok, actual: { uploads: [un.status, us.status], north: dn && { canEdit: dn.canEdit, canDelete: dn.canDelete }, south: ds && { canEdit: ds.canEdit, canDelete: ds.canDelete } } };
  });

  await C({ id: 'REG-44', feature: 'Write matrix', prd: 'B1', title: 'Single-record writes ignore regionIds (a North write works while the selector shows only South)',
    steps: 'nora PATCH /api/invoices/{n2 inv}?regionIds=S {notes}', expected: '200 (DESIGN §4.2 invariant I3)' }, async () => {
    const r = await rt.api('PATCH', `/api/invoices/${w.invoices.n2[0].id}`, { token: T.nora, body: { notes: rt.uniq('i3') }, query: { regionIds: R.S } });
    return { ok: r.status === 200, actual: excerpt(r, 150) };
  });

  // ---------------- Exports ----------------
  await C({ id: 'REG-45', feature: 'Exports', prd: 'B1', title: 'Invoice export (select all) holds North+South invoices only; with regionIds=W nothing',
    steps: 'nora POST /api/invoices/export {selectAllMatchingFilter:true}; again with ?regionIds=W; {ids:[w1 inv, n1 inv]}', expected: 'CSV has N and S invoice numbers, no West ones; W export has only the header; naming a West id exports only the North row (DESIGN §6.7, §5.6.5)' }, async () => {
    const r = await rt.api('POST', '/api/invoices/export', { token: T.nora, body: { selectAllMatchingFilter: true } });
    const rw = await rt.api('POST', '/api/invoices/export', { token: T.nora, body: { selectAllMatchingFilter: true }, query: { regionIds: R.W } });
    const has = (t, k) => (t || '').includes(w.invoices[k][0].invoiceNumber);
    const lines = (rw.text || '').trim().split('\n').filter(Boolean);
    const byIds = await rt.api('POST', '/api/invoices/export', { token: T.nora, body: { ids: [w.invoices.w1[0].id, w.invoices.n1[0].id] } });
    const ok = r.status === 200 && has(r.text, 'n1') && has(r.text, 's1') && !has(r.text, 'w1') && !has(r.text, 'd1') && rw.status === 200 && lines.length === 1
      && byIds.status === 200 && has(byIds.text, 'n1') && !has(byIds.text, 'w1');
    return { ok, actual: { status: r.status, n1: has(r.text, 'n1'), s1: has(r.text, 's1'), w1: has(r.text, 'w1'), westExportLines: lines.length, byIds: [byIds.status, has(byIds.text, 'n1'), has(byIds.text, 'w1')] } };
  });

  await C({ id: 'REG-46', feature: 'Exports', prd: 'B1', title: 'Customer, payment, promise and dispute exports honour regionIds',
    steps: 'nora POST /api/{customers,payments,promises,disputes}/export?regionIds=S {selectAllMatchingFilter:true}', expected: 'only South rows: South customer names present, North/West absent' }, async () => {
    const out = {}; let ok = true;
    for (const p of ['customers', 'payments', 'promises', 'disputes']) {
      const r = await rt.api('POST', `/api/${p}/export`, { token: T.nora, body: { selectAllMatchingFilter: true }, query: { regionIds: R.S } });
      const t = r.text || '';
      const hasS = t.includes(w.customers.s1.name) || t.includes(w.customers.s2.name);
      const hasN = t.includes(w.customers.n1.name) || t.includes(w.customers.n2.name); const hasW = t.includes(w.customers.w1.name);
      const good = r.status === 200 && hasS && !hasN && !hasW;
      out[p] = { status: r.status, hasS, hasN, hasW }; ok = ok && good;
    }
    return { ok, actual: out };
  });

  // ---------------- The POC book, per region ----------------
  await C({ id: 'REG-50', feature: 'POC book per region', prd: 'B1', title: 'A BOOK-scope user sees only his own book in North, with the locked "my book" filter',
    steps: 'bob (MANAGE/BOOK North; Sales+Collection POC of n1) GET /api/invoices', expected: 'exactly n1\'s invoices; lockedFilters ["salesPocUserId:eq:<bob>"]; bookRegionIds [N] (DESIGN §5.6.4)' }, async () => {
    const r = await rt.list(T.bob, '/api/invoices');
    const ref = await rt.list(admin, '/api/invoices', { filter: `salesPocUserId:eq:${U.bob.id}` });
    const ok = r.status === 200 && same(ids(r), ids(ref)) && JSON.stringify(r.json.lockedFilters) === JSON.stringify([`salesPocUserId:eq:${U.bob.id}`]) && same(r.json.bookRegionIds, [R.N]);
    return { ok, actual: { ids: sortN(ids(r) || []), expected: sortN(ids(ref) || []), locked: r.json?.lockedFilters, book: r.json?.bookRegionIds } };
  });

  await C({ id: 'REG-51', feature: 'POC book per region', prd: 'B1', title: 'BOOK scope also limits customers and payments to his book',
    steps: 'bob GET /api/customers, /api/payments', expected: 'customers = [n1]; payments = n1\'s payment (collection POC bob)' }, async () => {
    const c = await rt.list(T.bob, '/api/customers'); const p = await rt.list(T.bob, '/api/payments');
    // Reference: North customers where bob holds a seat or owns an invoice; payments whose Collection POC is bob.
    const allN = (await rt.list(admin, '/api/customers', {}, { regionIds: [R.N] })).json.content;
    const seat = allN.filter((x) => [...(x.collectionPocs || []), ...(x.successPocs || [])].some((s) => s.user?.id === U.bob.id)).map((x) => x.id);
    const byInv = (await rt.list(admin, '/api/invoices', { filter: `salesPocUserId:eq:${U.bob.id}` }, { regionIds: [R.N] })).json.content.map((i) => i.customerId);
    const refC = [...new Set([...seat, ...byInv])];
    const refP = ids(await rt.list(admin, '/api/payments', { filter: `collectionPocUserId:eq:${U.bob.id}` }));
    const ok = same(ids(c), refC) && same(ids(p), refP) && ids(c).includes(w.customers.n1.id) && !ids(c).includes(w.customers.n2.id) && ids(p).includes(w.payments.n1[0].id) && !ids(p).includes(w.payments.n2[0].id);
    return { ok, actual: { customers: ids(c), expectedCustomers: refC, payments: ids(p), expectedPayments: refP, lockedC: c.json?.lockedFilters, lockedP: p.json?.lockedFilters } };
  });

  await C({ id: 'REG-52', feature: 'POC book per region', prd: 'B1', title: 'Outside his book a BOOK user gets 404; inside it 200',
    steps: 'bob GET /api/invoices/{n2 inv}, /api/customers/{n2}, /api/invoices/{n1 inv}', expected: 'n2 404, 404; n1 200 (DESIGN §5.6.4 requireVisible BOOK)' }, async () => {
    const a = await get(T.bob, `/api/invoices/${w.invoices.n2[0].id}`); const b = await get(T.bob, `/api/customers/${w.customers.n2.id}`);
    const c = await get(T.bob, `/api/invoices/${w.invoices.n1[0].id}`);
    return { ok: a.status === 404 && b.status === 404 && c.status === 200, actual: { n2Invoice: a.status, n2Customer: b.status, n1Invoice: c.status } };
  });

  await C({ id: 'REG-53', feature: 'POC book per region', prd: 'B1', title: 'A BOOK user cannot create an invoice for a customer outside his book (404, nothing written)',
    steps: 'bob POST /api/invoices {customerId:n2, salesPocUserId:bob, amount 100}; then for n1', expected: 'n2 -> 404 and n2 invoice count unchanged; n1 -> 200 (DESIGN §5.6.6, SC-12)' }, async () => {
    const before = (await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${w.customers.n2.id}` })).json.totalElements;
    const r = await rt.createInvoice(T.bob, { customerId: w.customers.n2.id, amount: 100, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.bob.id });
    const after = (await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${w.customers.n2.id}` })).json.totalElements;
    const ok1 = await rt.createInvoice(T.bob, { customerId: w.customers.n1.id, amount: 100, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.bob.id });
    return { ok: r.status === 404 && before === after && ok1.status === 200, actual: { n2: excerpt(r, 150), before, after, n1: ok1.status } };
  });

  await C({ id: 'REG-54', feature: 'POC book per region', prd: 'B1', title: 'Mixed view: all of North plus only her own South book; no chip, bookRegionIds [S]; narrowed to South the chip appears',
    steps: 'mia (MANAGE/ALL N, MANAGE/BOOK S; Sales POC of one s2 invoice) GET /api/invoices; then ?regionIds=S',
    expected: 'all N invoices + her S invoice; lockedFilters [] and bookRegionIds [S]; with regionIds=S only her invoice and lockedFilters [salesPocUserId:eq:mia] (DESIGN §5.6.4 step 6, §6.0)' }, async () => {
    const all = await rt.list(T.mia, '/api/invoices'); const nRef = await rt.list(admin, '/api/invoices', {}, { regionIds: [R.N] });
    const s = await rt.list(T.mia, '/api/invoices', {}, { regionIds: [R.S] });
    const ok = same(ids(all), [...ids(nRef), F.miaInv.id]) && all.json.lockedFilters.length === 0 && same(all.json.bookRegionIds, [R.S])
      && same(ids(s), [F.miaInv.id]) && JSON.stringify(s.json.lockedFilters) === JSON.stringify([`salesPocUserId:eq:${U.mia.id}`]);
    return { ok, actual: { all: { n: ids(all)?.length, expected: ids(nRef).length + 1, locked: all.json?.lockedFilters, book: all.json?.bookRegionIds }, south: { ids: ids(s), locked: s.json?.lockedFilters, book: s.json?.bookRegionIds } } };
  });

  await C({ id: 'REG-55', feature: 'POC book per region', prd: 'B1', title: 'Dashboard coverage: BOOK for a pure-book user, MIXED for the mixed user',
    steps: 'bob and mia GET /api/dashboard/billed-by-month', expected: 'bob coverage BOOK, scope.bookRegionIds [N]; mia coverage MIXED, scope.bookRegionIds [S] (DESIGN §5.12)' }, async () => {
    const b = await get(T.bob, '/api/dashboard/billed-by-month'); const m = await get(T.mia, '/api/dashboard/billed-by-month');
    const ok = b.json?.coverage === 'BOOK' && same(b.json.scope.bookRegionIds, [R.N]) && m.json?.coverage === 'MIXED' && same(m.json.scope.bookRegionIds, [R.S]);
    return { ok, actual: { bob: [b.json?.coverage, b.json?.scope], mia: [m.json?.coverage, m.json?.scope] } };
  });


  await C({ id: 'REG-55b', feature: 'POC book per region', prd: 'B1', title: 'Mixed-view "collected" = North payment sums + only the South allocations to her own book',
    steps: 'admin records a South payment of 40 on s2 allocated to mia\'s South invoice (Collection POC sam); mia and admin GET /api/dashboard/collected-by-month?months=3 and /top-paying-customers?regionIds=S',
    expected: 'mia collected total = admin North total + what was paid against her South-book invoices (40 in a fresh world); narrowed to South, mia\'s top-paying list is s2 with 40 only (not s2\'s other payments); coverage MIXED (DESIGN §5.12 region split)' }, async () => {
    if (!F.miaPay) F.miaPay = rt.need(await rt.createPayment(admin, { customerId: w.customers.s2.id, amount: 40, invoiceIds: [F.miaInv.id], collectionPocUserId: U.sam.id }), 'mia payment', [200]);
    const sum = (j) => (j?.months || []).reduce((x, m) => x + Number(m.amount), 0);
    const m = await get(T.mia, '/api/dashboard/collected-by-month', { months: 3 }); const a = await get(admin, '/api/dashboard/collected-by-month', { months: 3, regionIds: R.N });
    const tp = await get(T.mia, '/api/dashboard/top-paying-customers', { months: 3, limit: 20, regionIds: R.S });
    const s2 = (tp.json?.customers || []).find((c) => c.customerId === w.customers.s2.id);
    // Reference for her South book: what has been paid against invoices whose Sales POC is mia (allocations), per customer.
    const book = (await rt.list(admin, '/api/invoices', { filter: `salesPocUserId:eq:${U.mia.id}` }, { regionIds: [R.S] })).json.content;
    const bookPaid = book.reduce((x, i) => x + Number(i.paidAmount), 0);
    const perCust = {}; for (const i of book) perCust[i.customerId] = (perCust[i.customerId] || 0) + Number(i.paidAmount);
    const rowsOk = (tp.json?.customers || []).every((c) => Math.abs(Number(c.collected) - (perCust[c.customerId] || 0)) < 0.01);
    const ok = m.json?.coverage === 'MIXED' && Math.abs(sum(m.json) - (sum(a.json) + bookPaid)) < 0.01 && s2 && Math.abs(Number(s2.collected) - 40) < 0.01 && rowsOk;
    return { ok, actual: { coverage: m.json?.coverage, mia: sum(m.json), adminNorth: sum(a.json), southBookPaid: bookPaid, topPayingSouth: tp.json?.customers || excerpt(tp, 200), expectedPerCustomer: perCust } };
  });

  await C({ id: 'REG-55c', feature: 'POC book per region', prd: 'B1', title: 'A BOOK user\'s summary tiles and export cover only his book',
    steps: 'bob GET /api/invoices/summary; POST /api/invoices/export {selectAllMatchingFilter:true}', expected: 'summary count = his list total; export rows = his invoices only (no n2 invoice)' }, async () => {
    const l = await rt.list(T.bob, '/api/invoices'); const sm = await get(T.bob, '/api/invoices/summary');
    const ex = await rt.api('POST', '/api/invoices/export', { token: T.bob, body: { selectAllMatchingFilter: true } });
    const rows = (ex.text || '').trim().split('\n').length - 1;
    const ok = sm.json?.count === l.json.totalElements && rows === l.json.totalElements && !(ex.text || '').includes(w.invoices.n2[0].invoiceNumber);
    return { ok, actual: { listTotal: l.json.totalElements, summaryCount: sm.json?.count, exportRows: rows, n2InExport: (ex.text || '').includes(w.invoices.n2[0].invoiceNumber) } };
  });

  await C({ id: 'REG-56', feature: 'POC book per region', prd: 'B1', title: '/api/pocs/my-scope reports the book per region',
    steps: 'mia GET /api/pocs/my-scope', expected: 'regions[] has NORTH clearable true and SOUTH clearable false (DESIGN §6.8)' }, async () => {
    const r = await get(T.mia, '/api/pocs/my-scope');
    const g = Object.fromEntries((r.json?.regions || []).map((x) => [x.code, x]));
    const ok = r.status === 200 && g.NORTH?.clearable === true && g.SOUTH?.clearable === false;
    return { ok, actual: excerpt(r, 500) };
  });

  await C({ id: 'REG-57', feature: 'POC book per region', prd: 'B1', title: 'Assignable POC picker is per region: only people granted in the customer\'s region',
    steps: 'nora GET /api/pocs/assignable?type=COLLECTION&customerId=<n2>; &customerId=<w1>; &regionId=<W>', expected: 'n2: nora, sam, bob, not wendy; w1: 404; regionId W: [] (DESIGN §6.8)' }, async () => {
    const a = await get(T.nora, '/api/pocs/assignable', { type: 'COLLECTION', customerId: w.customers.n2.id, limit: 50 });
    const b = await get(T.nora, '/api/pocs/assignable', { type: 'COLLECTION', customerId: w.customers.w1.id, limit: 50 });
    const c = await get(T.nora, '/api/pocs/assignable', { type: 'COLLECTION', regionId: R.W, limit: 50 });
    const names = (a.json || []).map((u) => u.username);
    const ok = a.status === 200 && ['nora', 'sam', 'bob'].every((n) => names.includes(n)) && !names.includes('wendy') && b.status === 404 && c.status === 200 && (c.json || []).length === 0;
    return { ok, actual: { n2: names, w1: b.status, regionW: c.json } };
  });

  await C({ id: 'REG-58', feature: 'POC book per region', prd: 'B1', title: 'A person without a grant in the record\'s region cannot be made its Sales POC',
    steps: 'admin POST /api/invoices {customerId:n2 (North), salesPocUserId: wendy (West only)}', expected: '400 "... not assignable as Sales POC in region NORTH" (DESIGN §5.6.7)' }, async () => {
    const r = await rt.createInvoice(admin, { customerId: w.customers.n2.id, amount: 100, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.wendy.id });
    return { ok: r.status === 400 && /NORTH|North/.test(r.text) && /assignable/i.test(r.text), actual: excerpt(r, 300) };
  });


  await C({ id: 'REG-59', feature: 'POC book per region', prd: 'B1', title: 'Tasks follow the parent\'s visibility: a BOOK user sees tasks on his book\'s records only',
    steps: 'tasks on n1 (bob\'s book) and n2 invoices; bob GET /api/tasks; GET /api/tasks/{n2 task}', expected: 'n1 task listed, n2 task not; n2 task by id 404 (DESIGN §5.6.4 Book.PARENT)' }, async () => {
    const l = ids(await rt.list(T.bob, '/api/tasks'));
    const d = await get(T.bob, `/api/tasks/${F.tasks.n2.id}`);
    return { ok: l.includes(F.tasks.n1.id) && !l.includes(F.tasks.n2.id) && d.status === 404, actual: { listed: l, n1: F.tasks.n1.id, n2: F.tasks.n2.id, n2ById: d.status } };
  });

  // ---------------- Writes: MANAGE in the record's region ----------------
  const three = async (fnN, fnS, fnW) => ({ N: await fnN(), S: await fnS(), W: await fnW() });

  await C({ id: 'REG-60', feature: 'Write matrix', prd: 'B1', title: 'Invoice PATCH: North 200, South 403, West 404, and a refusal changes nothing',
    steps: 'nora PATCH /api/invoices/{id} {notes} on a North, a South and a West invoice; admin re-reads', expected: '200 / 403 / 404; South and West notes unchanged (DESIGN §5.6.6, D15)' }, async () => {
    const note = rt.uniq('note');
    const r = await three(() => rt.patchInvoice(T.nora, w.invoices.n2[1].id, { notes: note }), () => rt.patchInvoice(T.nora, w.invoices.s2[1].id, { notes: note }), () => rt.patchInvoice(T.nora, w.invoices.w2[1].id, { notes: note }));
    const s = await get(admin, `/api/invoices/${w.invoices.s2[1].id}`); const x = await get(admin, `/api/invoices/${w.invoices.w2[1].id}`);
    const ok = r.N.status === 200 && r.S.status === 403 && r.W.status === 404 && s.json.notes !== note && x.json.notes !== note;
    return { ok, actual: { N: r.N.status, S: excerpt(r.S, 120), W: excerpt(r.W, 120), southNotes: s.json.notes, westNotes: x.json.notes } };
  });

  await C({ id: 'REG-61', feature: 'Write matrix', prd: 'B1', title: 'Invoice cancel: North 200, South 403, West 404 (South/West stay unpaid)',
    steps: 'scratch customers in N/S/W with one 100 invoice each; nora POST /api/invoices/{id}/cancel', expected: '200 CANCELLED / 403 / 404; S and W status unchanged' }, async () => {
    const [n, s, x] = [await mkCustomer(F, 'N'), await mkCustomer(F, 'S'), await mkCustomer(F, 'W')];
    const r = await three(() => rt.cancelInvoice(T.nora, n.invoices[0].id), () => rt.cancelInvoice(T.nora, s.invoices[0].id), () => rt.cancelInvoice(T.nora, x.invoices[0].id));
    const ss = (await get(admin, `/api/invoices/${s.invoices[0].id}`)).json.status; const ws = (await get(admin, `/api/invoices/${x.invoices[0].id}`)).json.status;
    const ok = r.N.status === 200 && r.N.json.status === 'CANCELLED' && r.S.status === 403 && r.W.status === 404 && ss !== 'CANCELLED' && ws !== 'CANCELLED';
    return { ok, actual: { N: [r.N.status, r.N.json?.status], S: r.S.status, W: r.W.status, southStatus: ss, westStatus: ws } };
  });

  await C({ id: 'REG-62', feature: 'Write matrix', prd: 'B1', title: 'Invoice create per customer region: North 200 (region North), South 403, West 404',
    steps: 'nora POST /api/invoices for n2 (N), s2 (S), w2 (W), 100 each', expected: '200 with regionId N / 403 / 404; S and W invoice counts unchanged (DESIGN §8.3 RegionAccessMatrixTest)' }, async () => {
    const cnt = async (c) => (await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${c}` })).json.totalElements;
    const bS = await cnt(w.customers.s2.id); const bW = await cnt(w.customers.w2.id);
    const r = await three(() => rt.createInvoice(T.nora, { customerId: w.customers.n2.id, amount: 100, dueDate: rt.day(10), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }),
      () => rt.createInvoice(T.nora, { customerId: w.customers.s2.id, amount: 100, dueDate: rt.day(10), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id }),
      () => rt.createInvoice(T.nora, { customerId: w.customers.w2.id, amount: 100, dueDate: rt.day(10), paymentTerm: 'CUSTOM', salesPocUserId: U.wendy.id }));
    const aS = await cnt(w.customers.s2.id); const aW = await cnt(w.customers.w2.id);
    const ok = r.N.status === 200 && r.N.json.regionId === R.N && r.S.status === 403 && r.W.status === 404 && aS === bS && aW === bW;
    return { ok, actual: { N: [r.N.status, r.N.json?.regionId], S: excerpt(r.S, 120), W: excerpt(r.W, 120), south: [bS, aS], west: [bW, aW] } };
  });

  await C({ id: 'REG-63', feature: 'Write matrix', prd: 'B1', title: 'Payment record per customer region: North 200 (region North), South 403, West 404',
    steps: 'nora POST /api/payments {amount 10} for n2, s2, w2 with their Collection POC', expected: '200 regionId N / 403 / 404' }, async () => {
    const r = await three(() => rt.createPayment(T.nora, { customerId: w.customers.n2.id, amount: 10, collectionPocUserId: U.nora.id }),
      () => rt.createPayment(T.nora, { customerId: w.customers.s2.id, amount: 10, collectionPocUserId: U.sam.id }),
      () => rt.createPayment(T.nora, { customerId: w.customers.w2.id, amount: 10, collectionPocUserId: U.wendy.id }));
    const ok = r.N.status === 200 && r.N.json.regionId === R.N && r.S.status === 403 && r.W.status === 404;
    return { ok, actual: { N: [r.N.status, r.N.json?.regionId], S: excerpt(r.S, 120), W: excerpt(r.W, 120) } };
  });

  await C({ id: 'REG-64', feature: 'Write matrix', prd: 'B1', title: 'Promise create per customer region: North 200, South 403, West 404',
    steps: 'nora POST /api/promises {amount 10} for n2, s2, w2', expected: '200 regionId N / 403 / 404' }, async () => {
    const r = await three(() => rt.createPromise(T.nora, { customerId: w.customers.n2.id, amount: 10, collectionPocUserId: U.nora.id }),
      () => rt.createPromise(T.nora, { customerId: w.customers.s2.id, amount: 10, collectionPocUserId: U.sam.id }),
      () => rt.createPromise(T.nora, { customerId: w.customers.w2.id, amount: 10, collectionPocUserId: U.wendy.id }));
    const ok = r.N.status === 200 && r.N.json.regionId === R.N && r.S.status === 403 && r.W.status === 404;
    return { ok, actual: { N: [r.N.status, r.N.json?.regionId], S: excerpt(r.S, 120), W: excerpt(r.W, 120) } };
  });

  await C({ id: 'REG-65', feature: 'Write matrix', prd: 'B1', title: 'Customer update: North 200, South 403, West 404; refused rows unchanged',
    steps: 'nora PUT /api/customers/{n2,s2,w2} {name,phone:"555-9999",...}', expected: '200 / 403 / 404; s2/w2 phone unchanged' }, async () => {
    const body = (c) => ({ name: c.name, phone: '555-9999', email: c.email, address: c.address, paymentTerm: 'NET_30' });
    const r = await three(() => rt.api('PUT', `/api/customers/${w.customers.n2.id}`, { token: T.nora, body: body(w.customers.n2) }),
      () => rt.api('PUT', `/api/customers/${w.customers.s2.id}`, { token: T.nora, body: body(w.customers.s2) }),
      () => rt.api('PUT', `/api/customers/${w.customers.w2.id}`, { token: T.nora, body: body(w.customers.w2) }));
    const s = (await get(admin, `/api/customers/${w.customers.s2.id}`)).json.phone; const x = (await get(admin, `/api/customers/${w.customers.w2.id}`)).json.phone;
    const ok = r.N.status === 200 && r.S.status === 403 && r.W.status === 404 && s !== '555-9999' && x !== '555-9999';
    return { ok, actual: { N: excerpt(r.N, 100), S: r.S.status, W: r.W.status, southPhone: s, westPhone: x } };
  });

  await C({ id: 'REG-66', feature: 'Write matrix', prd: 'B1', title: 'Customer delete: North 200, South 403, West 404',
    steps: 'scratch customers without invoices in N/S/W; nora DELETE /api/customers/{id}', expected: 'N 200/204 and gone; S 403 and W 404, both still present' }, async () => {
    const [n, s, x] = [await mkCustomer(F, 'N', { amounts: [] }), await mkCustomer(F, 'S', { amounts: [] }), await mkCustomer(F, 'W', { amounts: [] })];
    const r = await three(() => rt.api('DELETE', `/api/customers/${n.id}`, { token: T.nora }), () => rt.api('DELETE', `/api/customers/${s.id}`, { token: T.nora }), () => rt.api('DELETE', `/api/customers/${x.id}`, { token: T.nora }));
    const nGone = (await get(admin, `/api/customers/${n.id}`)).status; const sLeft = (await get(admin, `/api/customers/${s.id}`)).status; const wLeft = (await get(admin, `/api/customers/${x.id}`)).status;
    const ok = [200, 204].includes(r.N.status) && nGone === 404 && r.S.status === 403 && r.W.status === 404 && sLeft === 200 && wLeft === 200;
    return { ok, actual: { N: [r.N.status, nGone], S: [r.S.status, sLeft], W: [r.W.status, wLeft] } };
  });

  await C({ id: 'REG-67', feature: 'Write matrix', prd: 'B1', title: 'POC seat add needs POC_ASSIGN in the customer\'s region: North 200, South 403, West 404',
    steps: 'nora POST /api/customers/{id}/pocs {pocType:SUCCESS, userId:nora|sam|wendy}', expected: '200 / 403 / 404 (DESIGN §5.6.6 PocService.add)' }, async () => {
    const [n, s, x] = [await mkCustomer(F, 'N', { amounts: [] }), await mkCustomer(F, 'S', { amounts: [] }), await mkCustomer(F, 'W', { amounts: [] })];
    const r = await three(() => rt.api('POST', `/api/customers/${n.id}/pocs`, { token: T.nora, body: { pocType: 'SUCCESS', userId: U.nora.id, primary: true } }),
      () => rt.api('POST', `/api/customers/${s.id}/pocs`, { token: T.nora, body: { pocType: 'SUCCESS', userId: U.sam.id, primary: true } }),
      () => rt.api('POST', `/api/customers/${x.id}/pocs`, { token: T.nora, body: { pocType: 'SUCCESS', userId: U.wendy.id, primary: true } }));
    return { ok: r.N.status === 200 && r.S.status === 403 && r.W.status === 404, actual: { N: excerpt(r.N, 100), S: r.S.status, W: r.W.status } };
  });

  await C({ id: 'REG-68', feature: 'Write matrix', prd: 'B1', title: 'Task create on a record: North 200 (region North), South 403, West 404',
    steps: 'nora POST /api/tasks {entityType:INVOICE, entityId: n2/s2/w2 invoice}', expected: '200 regionId N / 403 / 404 (TASK_MANAGE is MANAGE-level)' }, async () => {
    const r = await three(() => rt.createTask(T.nora, { entityType: 'INVOICE', entityId: w.invoices.n2[0].id }), () => rt.createTask(T.nora, { entityType: 'INVOICE', entityId: w.invoices.s2[0].id }), () => rt.createTask(T.nora, { entityType: 'INVOICE', entityId: w.invoices.w2[0].id }));
    return { ok: r.N.status === 200 && r.N.json.regionId === R.N && r.S.status === 403 && r.W.status === 404, actual: { N: [r.N.status, r.N.json?.regionId], S: r.S.status, W: r.W.status } };
  });

  await C({ id: 'REG-69', feature: 'Write matrix', prd: 'B1', title: 'Email send needs EMAIL_SEND in the record\'s region; a named recipient needs access to it',
    steps: 'nora POST /api/emails on n2/s2/w2 invoices to CUSTOMER; then on n2 to USER wendy', expected: 'N 200, S 403, W 404; USER wendy on a North record -> 400 fieldErrors.to[0] (DESIGN §5.6.6)' }, async () => {
    const send = (inv, to) => rt.api('POST', '/api/emails', { token: T.nora, body: { entityType: 'INVOICE', entityId: inv, to, subject: 'e2e', body: 'e2e body' } });
    const r = await three(() => send(w.invoices.n2[0].id, [rt.tok.customer()]), () => send(w.invoices.s2[0].id, [rt.tok.customer()]), () => send(w.invoices.w2[0].id, [rt.tok.customer()]));
    const u = await send(w.invoices.n2[0].id, [rt.tok.user(U.wendy.id)]);
    const ok = [200, 201, 202].includes(r.N.status) && r.S.status === 403 && r.W.status === 404 && u.status === 400 && u.json?.fieldErrors && u.json.fieldErrors['to[0]'];
    return { ok, actual: { N: excerpt(r.N, 100), S: r.S.status, W: r.W.status, userWendy: excerpt(u, 200) } };
  });

  await C({ id: 'REG-70', feature: 'Write matrix', prd: 'B1', title: 'Document upload needs DOCUMENT_MANAGE in the record\'s region: North 201, South 403, West 404',
    steps: 'nora POST /api/documents (multipart file, entityType=INVOICE, entityId n2/s2/w2)', expected: '201 / 403 / 404' }, async () => {
    const up = async (inv) => {
      const fd = new FormData();
      fd.append('file', new Blob(['%PDF-1.4 e2e'], { type: 'application/pdf' }), 'e2e.pdf');
      fd.append('entityType', 'INVOICE'); fd.append('entityId', String(inv));
      return rt.api('POST', '/api/documents', { token: T.nora, body: fd, raw: true });
    };
    const r = await three(() => up(w.invoices.n2[0].id), () => up(w.invoices.s2[0].id), () => up(w.invoices.w2[0].id));
    return { ok: [200, 201].includes(r.N.status) && r.S.status === 403 && r.W.status === 404, actual: { N: excerpt(r.N, 150), S: excerpt(r.S, 100), W: r.W.status } };
  });

  await C({ id: 'REG-71', feature: 'Write matrix', prd: 'B1', title: 'Dispute approve: South (READ) 403 and still PENDING, West 404',
    steps: 'nora POST /api/disputes/{s1 dispute}/approve {}; /{w1 dispute}/approve', expected: '403 (dispute still PENDING); 404' }, async () => {
    const s = await rt.api('POST', `/api/disputes/${F.disputes.s1.id}/approve`, { token: T.nora, body: {} });
    const x = await rt.api('POST', `/api/disputes/${F.disputes.w1.id}/approve`, { token: T.nora, body: {} });
    const st = (await get(admin, `/api/disputes/${F.disputes.s1.id}`)).json.status;
    return { ok: s.status === 403 && x.status === 404 && st === 'PENDING', actual: { S: excerpt(s, 120), W: x.status, southStatus: st } };
  });

  await C({ id: 'REG-72', feature: 'Write matrix', prd: 'B1', title: 'Promise override: South (READ) 403, West 404',
    steps: 'nora POST /api/promises/{s2 promise}/override {status:KEPT, reason}; West promise', expected: '403; 404' }, async () => {
    const b = { status: 'KEPT', reason: 'e2e' };
    const s = await rt.api('POST', `/api/promises/${F.promises.s2.id}/override`, { token: T.nora, body: b });
    const x = await rt.api('POST', `/api/promises/${F.promises.w1.id}/override`, { token: T.nora, body: b });
    return { ok: s.status === 403 && x.status === 404, actual: { S: excerpt(s, 120), W: excerpt(x, 120) } };
  });


  await C({ id: 'REG-72b', feature: 'Write matrix', prd: 'B1', title: 'Payment PATCH and promise update/cancel: South 403, West 404, North 200',
    steps: 'nora PATCH /api/payments/{n2,s2,w2 payment} {notes}; POST /api/promises/{s2,w1 promise}/cancel', expected: 'payments 200/403/404; promise cancel S 403, W 404 (DESIGN §5.6.6)' }, async () => {
    const pb = { notes: rt.uniq('pn') };
    const r = await three(() => rt.api('PATCH', `/api/payments/${w.payments.n2[0].id}`, { token: T.nora, body: pb }), () => rt.api('PATCH', `/api/payments/${w.payments.s2[0].id}`, { token: T.nora, body: pb }), () => rt.api('PATCH', `/api/payments/${w.payments.w2[0].id}`, { token: T.nora, body: pb }));
    const cs = await rt.api('POST', `/api/promises/${F.promises.s2.id}/cancel`, { token: T.nora, body: { reason: 'e2e' } });
    const cw = await rt.api('POST', `/api/promises/${F.promises.w1.id}/cancel`, { token: T.nora, body: { reason: 'e2e' } });
    const st = (await get(admin, `/api/promises/${F.promises.s2.id}`)).json.status;
    const ok = r.N.status === 200 && r.S.status === 403 && r.W.status === 404 && cs.status === 403 && cw.status === 404 && st !== 'CANCELLED';
    return { ok, actual: { payN: excerpt(r.N, 100), payS: r.S.status, payW: r.W.status, cancelS: cs.status, cancelW: cw.status, southPromiseStatus: st } };
  });

  await C({ id: 'REG-73', feature: 'Customer create', prd: 'B1', title: 'Customer create picks/validates the region: 403 READ region, 404 ungranted, auto single region, 400 when ambiguous, narrowing picks',
    steps: 'nora POST /api/customers {regionId:S}; {regionId:W}; {no regionId}; sam {no regionId}; sam ?regionIds=S {no regionId}',
    expected: '403; 404; 200 regionId N; 400 fieldErrors.regionId "Choose the region this customer belongs to"; 200 regionId S (DESIGN §6.4)' }, async () => {
    const body = (extra = {}) => { const u = rt.uniq('cc'); return { name: u, phone: '555', email: `${u}@e2e.local`, address: 'x', paymentTerm: 'NET_30', username: u, password: PW, ...extra }; };
    const a = await rt.api('POST', '/api/customers', { token: T.nora, body: body({ regionId: R.S }) });
    const b = await rt.api('POST', '/api/customers', { token: T.nora, body: body({ regionId: R.W }) });
    const c = await rt.api('POST', '/api/customers', { token: T.nora, body: body() });
    const d = await rt.api('POST', '/api/customers', { token: T.sam, body: body() });
    const e = await rt.api('POST', '/api/customers', { token: T.sam, body: body(), query: { regionIds: R.S } });
    const ok = a.status === 403 && b.status === 404 && c.status === 200 && c.json.regionId === R.N && d.status === 400 && d.json?.fieldErrors?.regionId && e.status === 200 && e.json.regionId === R.S;
    return { ok, actual: { readRegion: a.status, ungranted: b.status, auto: [c.status, c.json?.regionId], ambiguous: excerpt(d, 250), narrowed: [e.status, e.json?.regionId, e.status !== 200 ? excerpt(e, 200) : ''] } };
  });

  await C({ id: 'REG-74', feature: 'Customer create', prd: 'B1', title: 'An inactive region takes no new customers',
    steps: 'admin POST /api/regions {code, active:false}; admin POST /api/customers {regionId: that region}', expected: '400 "Region X is inactive" (DESIGN §6.4)' }, async () => {
    const code = `IN${Date.now().toString(36).toUpperCase().slice(-6)}`;
    const rg = rt.need(await rt.api('POST', '/api/regions', { token: admin, body: { code, name: `Inactive ${code}`, active: false } }), 'create inactive region');
    F.inactiveRegion = rg;
    const u = rt.uniq('ic');
    const r = await rt.api('POST', '/api/customers', { token: admin, body: { name: u, phone: '5', email: `${u}@e2e.local`, address: 'x', paymentTerm: 'NET_30', username: u, password: PW, regionId: rg.id } });
    return { ok: r.status === 400 && /inactive/i.test(r.text), actual: excerpt(r, 250) };
  });

  // ---------------- Bulk ----------------
  await C({ id: 'REG-75', feature: 'Bulk', prd: 'B1', title: 'Bulk cancel by ids: the North row succeeds, South and West rows are skipped as not reachable and stay open',
    steps: 'scratch N/S/W invoices (100); nora POST /api/invoices/bulk {action:CANCEL, ids:[n,s,w]}', expected: `succeeded [n]; skipped s and w with "${NOT_REACHABLE}"; S and W not cancelled (DESIGN §5.6.5 Bulk writes)` }, async () => {
    const [n, s, x] = [await mkCustomer(F, 'N'), await mkCustomer(F, 'S'), await mkCustomer(F, 'W')];
    const ni = n.invoices[0].id; const si = s.invoices[0].id; const wi = x.invoices[0].id;
    const r = await rt.api('POST', '/api/invoices/bulk', { token: T.nora, body: { action: 'CANCEL', ids: [ni, si, wi] } });
    const sk = Object.fromEntries((r.json?.skipped || []).map((o) => [o.id, o.reason]));
    const ss = (await get(admin, `/api/invoices/${si}`)).json.status; const ws = (await get(admin, `/api/invoices/${wi}`)).json.status;
    const ok = r.status === 200 && same(r.json.succeeded, [ni]) && sk[si] === NOT_REACHABLE && sk[wi] === NOT_REACHABLE && ss !== 'CANCELLED' && ws !== 'CANCELLED';
    return { ok, actual: { status: r.status, succeeded: r.json?.succeeded, skipped: r.json?.skipped, failed: r.json?.failed, southStatus: ss, westStatus: ws } };
  });

  await C({ id: 'REG-76', feature: 'Bulk', prd: 'B1', title: 'Bulk cancel "select all matching" only touches rows the caller may manage',
    steps: 'scratch N/S/W invoices; nora POST /api/invoices/bulk {action:CANCEL, selectAllMatchingFilter:true, filters:["customerId:in:n,s,w"]}', expected: 'only the North invoice is cancelled; South and West untouched' }, async () => {
    const [n, s, x] = [await mkCustomer(F, 'N'), await mkCustomer(F, 'S'), await mkCustomer(F, 'W')];
    const r = await rt.api('POST', '/api/invoices/bulk', { token: T.nora, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: [`customerId:in:${n.id},${s.id},${x.id}`] } });
    const st = async (i) => (await get(admin, `/api/invoices/${i}`)).json.status;
    const [a, b, c] = [await st(n.invoices[0].id), await st(s.invoices[0].id), await st(x.invoices[0].id)];
    return { ok: r.status === 200 && a === 'CANCELLED' && b !== 'CANCELLED' && c !== 'CANCELLED', actual: { result: excerpt(r, 250), north: a, south: b, west: c } };
  });

  await C({ id: 'REG-77', feature: 'Bulk', prd: 'B1', title: 'Bulk "select all" honours regionIds in the query string',
    steps: 'scratch N and S invoices; admin POST /api/invoices/bulk?regionIds=S {CANCEL, selectAllMatchingFilter, filters:[customerId:in:n,s]}', expected: 'only the South invoice cancelled (DESIGN §6.7 "All bulk and export POSTs honour regionIds")' }, async () => {
    const [n, s] = [await mkCustomer(F, 'N'), await mkCustomer(F, 'S')];
    const r = await rt.api('POST', '/api/invoices/bulk', { token: admin, query: { regionIds: R.S }, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: [`customerId:in:${n.id},${s.id}`] } });
    const a = (await get(admin, `/api/invoices/${n.invoices[0].id}`)).json.status; const b = (await get(admin, `/api/invoices/${s.invoices[0].id}`)).json.status;
    return { ok: r.status === 200 && a !== 'CANCELLED' && b === 'CANCELLED', actual: { result: excerpt(r, 250), north: a, south: b } };
  });

  await C({ id: 'REG-78', feature: 'Bulk', prd: 'B1', title: 'Customer bulk ADD_POC skips customers outside the caller\'s MANAGE regions',
    steps: 'scratch N and S customers; nora POST /api/customers/bulk {action:ADD_POC, ids:[n,s], params:{pocType:SUCCESS,userId:nora}}', expected: 'N succeeded; S skipped (not reachable)' }, async () => {
    const [n, s] = [await mkCustomer(F, 'N', { amounts: [] }), await mkCustomer(F, 'S', { amounts: [] })];
    const r = await rt.api('POST', '/api/customers/bulk', { token: T.nora, body: { action: 'ADD_POC', ids: [n.id, s.id], params: { pocType: 'SUCCESS', userId: U.nora.id } } });
    const skipped = (r.json?.skipped || []).map((o) => o.id);
    return { ok: r.status === 200 && same(r.json.succeeded, [n.id]) && skipped.includes(s.id), actual: excerpt(r, 400) };
  });

  // ---------------- asOf interplay on region reads/writes ----------------
  await C({ id: 'REG-79', feature: 'View scope echo', prd: 'B1', title: 'asOf=today answers live (X-View-As-Of: live, PageResponse.asOf null); a future date is refused; a write with a past asOf is refused',
    steps: 'nora GET /api/invoices?asOf=<today>&regionIds=N; ?asOf=<tomorrow>; PATCH /api/invoices/{n2 inv}?asOf=<yesterday>',
    expected: 'header live, asOf null, regionIds [N]; 400 AS_OF_FUTURE; 400 AS_OF_READ_ONLY (DESIGN §6.0, D2)' }, async () => {
    const a = await get(T.nora, '/api/invoices', { asOf: rt.day(0), regionIds: R.N, size: 10 });
    const b = await get(T.nora, '/api/invoices', { asOf: rt.day(1), size: 10 });
    const c = await rt.api('PATCH', `/api/invoices/${w.invoices.n2[0].id}`, { token: T.nora, body: { notes: 'x' }, query: { asOf: rt.day(-1) } });
    const ok = a.status === 200 && a.headers['x-view-as-of'] === 'live' && a.json.asOf === null && same(a.json.regionIds, [R.N])
      && b.status === 400 && b.json?.error === 'AS_OF_FUTURE' && c.status === 400 && c.json?.error === 'AS_OF_READ_ONLY';
    return { ok, actual: { today: [a.status, a.headers['x-view-as-of'], a.json?.asOf, a.json?.regionIds], future: excerpt(b, 150), write: excerpt(c, 150) } };
  });

  // ---------------- Region administration ----------------
  await C({ id: 'REG-80', feature: 'Region admin', prd: 'B1', title: 'Regions list: REGION_VIEW may read it; a customer login may not',
    steps: 'admin, nora GET /api/regions; cust GET /api/regions', expected: 'admin/nora 200 with RegionDto {id,code,name,active,defaultRegion,version,createdAt}, exactly one default; cust 403 (DESIGN §6.3)' }, async () => {
    const a = await rt.list(admin, '/api/regions'); const n = await get(T.nora, '/api/regions', { size: 50 }); const c = await get(T.cust, '/api/regions', { size: 50 });
    const r0 = a.json?.content?.[0] || {};
    const shape = ['id', 'code', 'name', 'active', 'defaultRegion', 'createdAt'].every((k) => k in r0);
    const defaults = (a.json?.content || []).filter((r) => r.defaultRegion).length;
    return { ok: a.status === 200 && shape && defaults === 1 && n.status === 200 && c.status === 403, actual: { admin: a.status, codes: (a.json?.content || []).map((r) => r.code), defaults, sample: r0, nora: n.status, cust: c.status } };
  });

  await C({ id: 'REG-81', feature: 'Region admin', prd: 'B1', title: 'Only REGION_MANAGE may create, edit or delete regions',
    steps: 'nora POST /api/regions; PUT /api/regions/{N}; DELETE /api/regions/{N}', expected: '403 each' }, async () => {
    const a = await rt.api('POST', '/api/regions', { token: T.nora, body: { code: 'NOPE1', name: 'Nope', active: true } });
    const b = await rt.api('PUT', `/api/regions/${R.N}`, { token: T.nora, body: { name: 'North!', active: true, version: 0 } });
    const c = await rt.api('DELETE', `/api/regions/${R.N}`, { token: T.nora });
    return { ok: a.status === 403 && b.status === 403 && c.status === 403, actual: [a.status, b.status, c.status] };
  });

  await C({ id: 'REG-82', feature: 'Region admin', prd: 'B1', title: 'Region create validates the code and the name (bad, duplicate code, duplicate name ignoring case)',
    steps: 'admin POST /api/regions {code:"x!"}; {code:"NORTH"}; {code:<new>, name:"north"}', expected: '400 fieldErrors.code; 400 fieldErrors.code; 400 fieldErrors.name (DESIGN §3.2, §6.3)' }, async () => {
    const a = await rt.api('POST', '/api/regions', { token: admin, body: { code: 'x!', name: 'Bad code', active: true } });
    const b = await rt.api('POST', '/api/regions', { token: admin, body: { code: 'NORTH', name: `Dup ${Date.now()}`, active: true } });
    const c = await rt.api('POST', '/api/regions', { token: admin, body: { code: `DN${Date.now().toString(36).toUpperCase().slice(-5)}`, name: 'north', active: true } });
    const ok = a.status === 400 && a.json?.fieldErrors?.code && b.status === 400 && b.json?.fieldErrors?.code && c.status === 400 && c.json?.fieldErrors?.name;
    return { ok, actual: { bad: excerpt(a, 200), dupCode: excerpt(b, 200), dupName: excerpt(c, 200) } };
  });

  await C({ id: 'REG-83', feature: 'Region admin', prd: 'B1', title: 'Creating a region auto-grants the creator APPROVE/ALL there',
    steps: 'admin POST /api/regions {code:EXnnn}; admin GET /api/auth/me; GET /api/regions/{id}/grants', expected: '200 RegionDto; /me lists it at APPROVE/ALL; grants list admin APPROVE ALL (DESIGN §6.3)' }, async () => {
    const code = `EX${Date.now().toString(36).toUpperCase().slice(-6)}`;
    const r = await rt.api('POST', '/api/regions', { token: admin, body: { code, name: `East ${code}`, active: true } });
    if (r.status === 200) F.east = r.json;
    const m = await rt.me(await rt.adminToken());
    const mine = (m.regions || []).find((x) => x.code === code);
    const g = r.status === 200 ? await get(admin, `/api/regions/${r.json.id}/grants`) : null;
    const adminRow = (g?.json || []).find((x) => x.username === 'admin');
    return { ok: r.status === 200 && mine?.level === 'APPROVE' && mine?.scope === 'ALL' && adminRow?.level === 'APPROVE', actual: { create: excerpt(r, 200), me: mine, grants: g?.json } };
  });

  await C({ id: 'REG-84', feature: 'Region admin', prd: 'B1', title: 'Region edit: rename with the version, stale version 409, code immutable, duplicate name 400, unknown 404',
    steps: 'admin PUT /api/regions/{east} {name, active, version}; again with the old version; PUT {code:"ZZZ"...}', expected: '200 new name; 409; code unchanged' }, async () => {
    if (!F.east) return { status: 'BLOCKED', actual: 'REG-83 did not create the region' };
    const cur = (await get(admin, `/api/regions/${F.east.id}`)).json;
    const a = await rt.api('PUT', `/api/regions/${F.east.id}`, { token: admin, body: { name: `${cur.name} v2`, active: true, version: cur.version } });
    const b = await rt.api('PUT', `/api/regions/${F.east.id}`, { token: admin, body: { name: `${cur.name} v3`, active: true, version: cur.version } });
    const c = await rt.api('PUT', `/api/regions/${F.east.id}`, { token: admin, body: { code: 'ZZZZ', name: `${cur.name} v2`, active: true, version: a.json?.version } });
    const after = (await get(admin, `/api/regions/${F.east.id}`)).json;
    const dup = await rt.api('PUT', `/api/regions/${F.east.id}`, { token: admin, body: { name: 'SOUTH', active: true, version: after.version } });
    const unknown = await get(admin, '/api/regions/999999');
    return { ok: a.status === 200 && a.json.name === `${cur.name} v2` && b.status === 409 && after.code === cur.code && dup.status === 400 && unknown.status === 404,
      actual: { rename: excerpt(a, 150), stale: excerpt(b, 150), codeChange: c.status, codeAfter: after.code, dupName: excerpt(dup, 160), unknown: unknown.status } };
  });

  await C({ id: 'REG-85', feature: 'Region admin', prd: 'B1', title: 'Default region rules: cannot deactivate it; make-default flips exactly one; inactive cannot become default',
    steps: 'admin PUT DEFAULT {active:false}; POST /api/regions/{east}/make-default then back; POST /api/regions/{inactive}/make-default',
    expected: '400 "The default region cannot be deactivated"; exactly one default after each flip; 400 for the inactive region (DESIGN §6.3)' }, async () => {
    const d = (await get(admin, `/api/regions/${R.D}`)).json;
    const a = await rt.api('PUT', `/api/regions/${R.D}`, { token: admin, body: { name: d.name, active: false, version: d.version } });
    let flip = null; let defaults1 = null; let back = null;
    if (F.east) {
      flip = await rt.api('POST', `/api/regions/${F.east.id}/make-default`, { token: admin, body: {} });
      defaults1 = (await rt.regions(admin)).filter((r) => r.defaultRegion).map((r) => r.code);
      back = await rt.api('POST', `/api/regions/${R.D}/make-default`, { token: admin, body: {} });
    }
    const defaults2 = (await rt.regions(admin)).filter((r) => r.defaultRegion).map((r) => r.code);
    const c = F.inactiveRegion ? await rt.api('POST', `/api/regions/${F.inactiveRegion.id}/make-default`, { token: admin, body: {} }) : { status: 'n/a' };
    const ok = a.status === 400 && flip?.status === 200 && defaults1?.length === 1 && defaults1[0] === F.east.code && back?.status === 200 && defaults2.length === 1 && defaults2[0] === 'DEFAULT' && c.status === 400;
    return { ok, actual: { deactivateDefault: excerpt(a, 200), flip: flip?.status, defaultsAfterFlip: defaults1, back: back?.status, defaultsAfterBack: defaults2, inactiveMakeDefault: c.status } };
  });

  await C({ id: 'REG-86', feature: 'Region admin', prd: 'B1', title: 'Region delete refuses the default region and a region still in use',
    steps: 'admin DELETE /api/regions/{DEFAULT}; DELETE /api/regions/{NORTH}', expected: '400 "Region is the default"; 400 "Region still has customers, disputes or grants; deactivate it instead"' }, async () => {
    const a = await rt.api('DELETE', `/api/regions/${R.D}`, { token: admin }); const b = await rt.api('DELETE', `/api/regions/${R.N}`, { token: admin });
    const still = (await get(admin, `/api/regions/${R.N}`)).status;
    return { ok: a.status === 400 && b.status === 400 && still === 200, actual: { deleteDefault: excerpt(a, 200), deleteNorth: excerpt(b, 200) } };
  });

  await C({ id: 'REG-87', feature: 'Region admin', prd: 'B1', title: 'Region grants tab lists who holds what in the region',
    steps: 'admin GET /api/regions/{N}/grants', expected: 'nora MANAGE/ALL, bob MANAGE/BOOK, sam MANAGE/ALL, mia MANAGE/ALL; no wendy; nora (REGION_VIEW without USER_VIEW) 403 (DESIGN §6.3 gate REGION_VIEW and USER_VIEW)' }, async () => {
    const r = await get(admin, `/api/regions/${R.N}/grants`);
    const g = Object.fromEntries((r.json || []).map((x) => [x.username, `${x.level}/${x.scope}`]));
    const n = await get(T.nora, `/api/regions/${R.N}/grants`);
    const ok = r.status === 200 && g.nora === 'MANAGE/ALL' && g.bob === 'MANAGE/BOOK' && g.sam === 'MANAGE/ALL' && g.mia === 'MANAGE/ALL' && !g.wendy && n.status === 403;
    return { ok, actual: { grants: g, noraWithoutUserView: n.status } };
  });


  await C({ id: 'REG-88', feature: 'Region admin', prd: 'B1', title: 'Privileges carry their region level; the regions table schema is gated',
    steps: 'admin GET /api/privileges; cust GET /api/table-schemas/regions; nora GET /api/table-schemas/regions', expected: 'INVOICE_VIEW READ, INVOICE_MANAGE MANAGE, CHANGE_APPROVE APPROVE, USER_MANAGE null; cust 403; nora 200 (DESIGN §6.2, §8.3)' }, async () => {
    const p = await get(admin, '/api/privileges');
    const list = Array.isArray(p.json) ? p.json : p.json?.content || [];
    const lv = Object.fromEntries(list.map((x) => [x.name, x.regionLevel ?? null]));
    const c = await get(T.cust, '/api/table-schemas/regions'); const n = await get(T.nora, '/api/table-schemas/regions');
    const ok = lv.INVOICE_VIEW === 'READ' && lv.INVOICE_MANAGE === 'MANAGE' && lv.CHANGE_APPROVE === 'APPROVE' && lv.USER_MANAGE === null && c.status === 403 && n.status === 200;
    return { ok, actual: { levels: { INVOICE_VIEW: lv.INVOICE_VIEW, INVOICE_MANAGE: lv.INVOICE_MANAGE, CHANGE_APPROVE: lv.CHANGE_APPROVE, USER_MANAGE: lv.USER_MANAGE }, custSchema: c.status, noraSchema: n.status } };
  });

  // ---------------- Grants editor (API) ----------------
  await C({ id: 'REG-90', feature: 'Grants', prd: 'B1', title: 'GET /api/users/{id}/region-grants returns the user\'s grants with region code, name, level, scope',
    steps: 'admin GET /api/users/{nora}/region-grants', expected: '[{regionId N, regionCode NORTH, level MANAGE, scope ALL, grantedAt}, {SOUTH READ ALL}] (DESIGN §6.3)' }, async () => {
    const g = await rt.getGrants(admin, U.nora.id);
    const m = Object.fromEntries(g.map((x) => [x.regionCode, `${x.level}/${x.scope}`]));
    return { ok: m.NORTH === 'MANAGE/ALL' && m.SOUTH === 'READ/ALL' && g.length === 2 && g.every((x) => x.regionName && x.grantedAt), actual: g };
  });

  await C({ id: 'REG-91', feature: 'Grants', prd: 'B1', title: 'A grant change applies to the user\'s next request, and is audited',
    steps: 'admin PUT wendy grants [W MANAGE, N READ]; wendy GET /api/invoices (same token); restore [W MANAGE]; wendy GET again; audit_logs USER_REGION_GRANTS_CHANGED',
    expected: 'with N READ wendy sees North invoices (regionIds [N,W]); after restore only West; an audit row per change (DESIGN §5.5, §5.6.1)' }, async () => {
    const auditBefore = Number(rt.sql(`select count(*) from audit_logs where action='USER_REGION_GRANTS_CHANGED' and entity_id=${U.wendy.id}`)[0][0]);
    const p1 = await rt.setGrants(admin, U.wendy.id, [{ regionId: R.W, accessLevel: 'MANAGE' }, { regionId: R.N, accessLevel: 'READ' }]);
    const a = await get(T.wendy, '/api/invoices', { size: 10 });
    const p2 = await rt.setGrants(admin, U.wendy.id, [{ regionId: R.W, accessLevel: 'MANAGE' }]);
    const b = await get(T.wendy, '/api/invoices', { size: 10 });
    const auditAfter = Number(rt.sql(`select count(*) from audit_logs where action='USER_REGION_GRANTS_CHANGED' and entity_id=${U.wendy.id}`)[0][0]);
    const ok = p1.status === 200 && same(a.json?.regionIds, [R.N, R.W]) && p2.status === 200 && same(b.json?.regionIds, [R.W]) && auditAfter - auditBefore >= 2;
    return { ok, actual: { put1: p1.status, withNorth: a.json?.regionIds, put2: p2.status, afterRestore: b.json?.regionIds, auditRows: [auditBefore, auditAfter] } };
  });

  await C({ id: 'REG-92', feature: 'Grants', prd: 'B1', title: 'Grant replace refuses self, customer logins, duplicate and unknown regions',
    steps: 'admin PUT own grants; PUT grants of cust\'s user; PUT nora [N READ, N MANAGE]; PUT nora [region 999999]', expected: '400 each ("You cannot change your own region access", "Customer logins take their region from their customer", fieldErrors.grants x2); nora unchanged' }, async () => {
    const custUser = await rt.findUser(admin, F.w.users.cust.username);
    const a = await rt.setGrants(admin, U.admin.id, [{ regionId: R.N, accessLevel: 'READ' }]);
    const b = await rt.setGrants(admin, custUser.id, [{ regionId: R.S, accessLevel: 'READ' }]);
    const c = await rt.setGrants(admin, U.nora.id, [{ regionId: R.N, accessLevel: 'READ' }, { regionId: R.N, accessLevel: 'MANAGE' }]);
    const d = await rt.setGrants(admin, U.nora.id, [{ regionId: 999999, accessLevel: 'READ' }]);
    const after = await rt.getGrants(admin, U.nora.id);
    const ok = a.status === 400 && b.status === 400 && c.status === 400 && c.json?.fieldErrors?.grants && d.status === 400 && after.length === 2;
    return { ok, actual: { self: excerpt(a, 150), customer: excerpt(b, 150), duplicate: excerpt(c, 200), unknown: excerpt(d, 200), noraAfter: after.map((x) => x.regionCode + ':' + x.level) } };
  });

  await C({ id: 'REG-93', feature: 'Grants', prd: 'B1', title: 'POST /api/users without regionGrants (or with []) creates a user holding no region',
    steps: 'admin POST /api/users without regionGrants; another with regionGrants []; GET their region-grants; log in; GET /api/auth/me', expected: '[] both; /me regions [] (DESIGN §4.6, SC-5)' }, async () => {
    const mk = async (grants) => {
      const u = rt.uniq('ng');
      const body = { username: u, email: `${u}@e2e.local`, fullName: u, password: PW, roleId: await rt.roleId(admin, 'E2E_REGIONAL'), active: true };
      if (grants) body.regionGrants = grants;
      const r = rt.need(await rt.api('POST', '/api/users', { token: admin, body }), 'create user');
      return { g: await rt.getGrants(admin, r.id), me: await rt.me(await rt.login(u)) };
    };
    const a = await mk(null); const b = await mk([]);
    return { ok: a.g.length === 0 && b.g.length === 0 && a.me.regions.length === 0 && b.me.regions.length === 0, actual: { absent: [a.g, a.me.regions], empty: [b.g, b.me.regions] } };
  });

  await C({ id: 'REG-94', feature: 'Grants', prd: 'B1', title: 'Nobody grants more than they hold: a North-MANAGE user admin is bounded per region and level',
    steps: 'uma (USER_MANAGE; MANAGE/ALL North only) PUT tina grants: [S READ kept, N READ]; [S READ, N APPROVE]; [S MANAGE, N READ]; [N READ] (drops S); [S READ, N READ, W READ]',
    expected: '200; 403; 403; 403; 403 (DESIGN §4.6 granter bound, §6.3)' }, async () => {
    rt.need(await rt.setGrants(admin, U.tina.id, [{ regionId: R.S, accessLevel: 'READ' }]), 'reset tina');
    const put = (rows) => rt.setGrants(T.uma, U.tina.id, rows);
    const a = await put([{ regionId: R.S, accessLevel: 'READ' }, { regionId: R.N, accessLevel: 'READ' }]);
    const b = await put([{ regionId: R.S, accessLevel: 'READ' }, { regionId: R.N, accessLevel: 'APPROVE' }]);
    const c = await put([{ regionId: R.S, accessLevel: 'MANAGE' }, { regionId: R.N, accessLevel: 'READ' }]);
    const d = await put([{ regionId: R.N, accessLevel: 'READ' }]);
    const e = await put([{ regionId: R.S, accessLevel: 'READ' }, { regionId: R.N, accessLevel: 'READ' }, { regionId: R.W, accessLevel: 'READ' }]);
    const after = await rt.getGrants(admin, U.tina.id);
    const m = Object.fromEntries(after.map((x) => [x.regionCode, x.level]));
    const ok = a.status === 200 && b.status === 403 && c.status === 403 && d.status === 403 && e.status === 403 && m.SOUTH === 'READ' && m.NORTH === 'READ' && !m.WEST;
    return { ok, actual: { keepAndAdd: a.status, approve: b.status, changeSouth: c.status, dropSouth: d.status, addWest: e.status, tinaAfter: m } };
  });

  await C({ id: 'REG-95', feature: 'Grants', prd: 'B1', title: 'Without USER_MANAGE nobody can edit grants; user creation with grants is bounded too',
    steps: 'nora PUT /api/users/{tina}/region-grants; uma POST /api/users with regionGrants [S READ]', expected: '403; 403 (DESIGN §6.3)' }, async () => {
    const a = await rt.setGrants(T.nora, U.tina.id, [{ regionId: R.N, accessLevel: 'READ' }]);
    const u = rt.uniq('um');
    const b = await rt.api('POST', '/api/users', { token: T.uma, body: { username: u, email: `${u}@e2e.local`, fullName: u, password: PW, roleId: await rt.roleId(admin, 'E2E_REGIONAL'), active: true, regionGrants: [{ regionId: R.S, level: 'READ', scope: 'ALL' }] } });
    return { ok: a.status === 403 && b.status === 403, actual: { noraPut: excerpt(a, 120), umaCreateSouth: excerpt(b, 160) } };
  });

  // ---------------- Customer region move ----------------
  // A North customer with an invoice, a payment, a promise, a dispute (opened by its login) and a task.
  async function mkMover(key = 'N') {
    const c = await mkCustomer(F, key, { amounts: [300, 400], login: true, prefix: 'mv' });
    const pay = rt.need(await rt.createPayment(admin, { customerId: c.id, amount: 50, invoiceIds: [c.invoices[0].id], collectionPocUserId: c.col }), 'mv pay', [200]);
    const prom = rt.need(await rt.createPromise(admin, { customerId: c.id, amount: 60, collectionPocUserId: c.col }), 'mv promise', [200]);
    const disp = rt.need(await rt.createDispute(c.token, { targetType: 'INVOICE', targetId: c.invoices[1].id, reason: 'mv' }), 'mv dispute');
    const task = rt.need(await rt.createTask(admin, { entityType: 'INVOICE', entityId: c.invoices[0].id, title: 'mv task' }), 'mv task');
    return { ...c, pay, prom, disp, task };
  }

  await C({ id: 'REG-100', feature: 'Region move', prd: 'B1', title: 'Moving a customer moves every child with it (invoices, payments, promises, disputes, tasks) and reports the counts',
    steps: 'mover in North (2 invoices, payment, promise, dispute, task); admin POST /api/customers/{id}/move-region {regionId:W, version, reason}; admin re-reads each child',
    expected: '200 {customer.regionId W, moved:{invoices 2, payments 1, promises 1, disputes 1, other.TaskMoveParticipant 1}}; every child regionId W; customer version bumped (PRD B1 "exactly one region"; DESIGN §5.6.8, §6.4)' }, async () => {
    const m = await mkMover('N');
    const v0 = (await get(admin, `/api/customers/${m.id}`)).json.version;
    const r = await rt.moveCustomerRegion(admin, m.id, R.W, 'e2e move to West');
    F.moved = m;
    const kids = {
      inv0: (await get(admin, `/api/invoices/${m.invoices[0].id}`)).json?.regionId, inv1: (await get(admin, `/api/invoices/${m.invoices[1].id}`)).json?.regionId,
      pay: (await get(admin, `/api/payments/${m.pay.id}`)).json?.regionId, prom: (await get(admin, `/api/promises/${m.prom.id}`)).json?.regionId,
      disp: (await get(admin, `/api/disputes/${m.disp.id}`)).json?.regionId, task: (await get(admin, `/api/tasks/${m.task.id}`)).json?.regionId,
    };
    const c = (await get(admin, `/api/customers/${m.id}`)).json;
    const mv = r.json?.moved || {};
    const ok = r.status === 200 && r.json.customer.regionId === R.W && mv.invoices === 2 && mv.payments === 1 && mv.promises === 1 && mv.disputes === 1
      && mv.other?.TaskMoveParticipant === 1 && Object.values(kids).every((x) => x === R.W) && c.version > v0;
    return { ok, actual: { status: r.status, moved: mv, warnings: r.json?.warnings, kids, version: [v0, c.version], err: r.status !== 200 ? excerpt(r) : '' } };
  });

  await C({ id: 'REG-101', feature: 'Region move', prd: 'B1', title: 'After the move, visibility follows the new region (North-only users lose it, West users gain it)',
    steps: 'nora GET moved customer + invoice + /api/customers list; wendy same', expected: 'nora 404/404 and not listed; wendy 200/200 and listed' }, async () => {
    const m = F.moved; if (!m) return { status: 'BLOCKED', actual: 'REG-100 did not run' };
    const nc = await get(T.nora, `/api/customers/${m.id}`); const ni = await get(T.nora, `/api/invoices/${m.invoices[0].id}`);
    const wc = await get(T.wendy, `/api/customers/${m.id}`); const wi = await get(T.wendy, `/api/invoices/${m.invoices[0].id}`);
    const nl = ids(await rt.list(T.nora, '/api/customers')); const wl = ids(await rt.list(T.wendy, '/api/customers'));
    const ok = nc.status === 404 && ni.status === 404 && wc.status === 200 && wi.status === 200 && !nl.includes(m.id) && wl.includes(m.id);
    return { ok, actual: { nora: [nc.status, ni.status, nl.includes(m.id)], wendy: [wc.status, wi.status, wl.includes(m.id)] } };
  });

  await C({ id: 'REG-102', feature: 'Region move', prd: 'B1', title: 'History is kept: the audit shows CUSTOMER_REGION_CHANGED (from/to) and every earlier entry; a move row is written',
    steps: 'admin GET /api/audit?entityType=CUSTOMER&entityId=<moved>; SQL customer_region_moves', expected: 'CUSTOMER_REGION_CHANGED with before NORTH / after WEST and region_id W; CUSTOMER_CREATED still there; 1 customer_region_moves row N->W (DESIGN §5.6.8 steps 13, 16)' }, async () => {
    const m = F.moved; if (!m) return { status: 'BLOCKED', actual: 'REG-100 did not run' };
    const a = await get(admin, '/api/audit', { entityType: 'CUSTOMER', entityId: m.id });
    const rows = a.json || [];
    const ch = rows.find((x) => x.action === 'CUSTOMER_REGION_CHANGED');
    const created = rows.some((x) => x.action === 'CUSTOMER_CREATED');
    const moves = rt.sql(`select from_region_id, to_region_id from customer_region_moves where customer_id=${m.id}`);
    const aud = rt.sql(`select region_id from audit_logs where entity_type='CUSTOMER' and entity_id=${m.id} and action='CUSTOMER_REGION_CHANGED'`);
    const ok = !!ch && /NORTH/.test(ch.beforeJson || '') && /WEST/.test(ch.afterJson || '') && created && moves.length === 1 && Number(moves[0][0]) === R.N && Number(moves[0][1]) === R.W && Number(aud[0]?.[0]) === R.W;
    return { ok, actual: { actions: rows.map((x) => x.action), before: ch?.beforeJson, after: ch?.afterJson?.slice(0, 200), moves, auditRegion: aud } };
  });

  await C({ id: 'REG-103', feature: 'Region move', prd: 'B1', title: 'Move refusals: 404 unreachable target, 403 no MANAGE in source or target, 400 same/inactive, 409 stale, 400 as-of',
    steps: 'nora moves an N customer to W, to S; nora moves an S customer to N; admin moves to the same region, to an inactive region, with a stale version, with ?asOf=yesterday',
    expected: '404; 403; 403; 400; 400; 409; 400 AS_OF_READ_ONLY — and the customer stays in North (DESIGN §5.6.8 steps 3-6, §6.4)' }, async () => {
    const c = await mkCustomer(F, 'N', { amounts: [100] }); const s = await mkCustomer(F, 'S', { amounts: [] });
    const cv = (await get(admin, `/api/customers/${c.id}`)).json.version;
    const mv = (t, id, regionId, version = cv, query) => rt.api('POST', `/api/customers/${id}/move-region`, { token: t, body: { regionId, version, reason: 'e2e' }, query });
    const a = await mv(T.nora, c.id, R.W); const b = await mv(T.nora, c.id, R.S);
    const sv = (await get(admin, `/api/customers/${s.id}`)).json.version; const d = await mv(T.nora, s.id, R.N, sv);
    const e = await mv(admin, c.id, R.N); const f = F.inactiveRegion ? await mv(admin, c.id, F.inactiveRegion.id) : { status: 'n/a' };
    const g = await mv(admin, c.id, R.W, cv + 5);
    const h = await mv(admin, c.id, R.W, cv, { asOf: rt.day(-1) });
    const still = (await get(admin, `/api/customers/${c.id}`)).json.regionId;
    const ok = a.status === 404 && b.status === 403 && d.status === 403 && e.status === 400 && f.status === 400 && g.status === 409 && h.status === 400 && h.json?.error === 'AS_OF_READ_ONLY' && still === R.N;
    return { ok, actual: { toWest: a.status, toSouthReadOnly: b.status, fromSouth: d.status, same: excerpt(e, 120), inactive: excerpt(f, 120), stale: g.status, asOf: excerpt(h, 120), regionAfter: still } };
  });

  await C({ id: 'REG-104', feature: 'Region move', prd: 'B1', title: 'canMoveRegion is true only with CUSTOMER_MANAGE in the region and another managed active region',
    steps: 'nora GET /api/customers/{n2} (manages only North); sam GET same (manages North and South); nora GET s2 (READ)', expected: 'nora false; sam true; nora on s2 false (DESIGN §5.6.9)' }, async () => {
    const a = (await get(T.nora, `/api/customers/${w.customers.n2.id}`)).json; const b = (await get(T.sam, `/api/customers/${w.customers.n2.id}`)).json;
    const c = (await get(T.nora, `/api/customers/${w.customers.s2.id}`)).json;
    return { ok: a.canMoveRegion === false && b.canMoveRegion === true && c.canMoveRegion === false, actual: { nora: a.canMoveRegion, sam: b.canMoveRegion, noraSouth: c.canMoveRegion } };
  });

  await C({ id: 'REG-105', feature: 'Region move', prd: 'B1', title: 'Move warns about POCs with no access to the new region, and keeps the seats',
    steps: 'N customer with Collection POC nora (no West grant); admin moves it to West', expected: '200; warnings mention Nora and West; the Collection seat is still there (DESIGN §5.6.8 step 10)' }, async () => {
    const c = await mkCustomer(F, 'N', { amounts: [100] });
    const r = await rt.moveCustomerRegion(admin, c.id, R.W, 'e2e warn');
    const seats = (await get(admin, `/api/customers/${c.id}/pocs`)).json;
    const ws = (r.json?.warnings || []).join(' | ');
    const seatKept = JSON.stringify(seats || '').includes(`"id":${U.nora.id}`) || JSON.stringify(seats || '').includes('nora');
    return { ok: r.status === 200 && /nora/i.test(ws) && /west/i.test(ws) && seatKept, actual: { status: r.status, warnings: r.json?.warnings, seats: JSON.stringify(seats).slice(0, 200) } };
  });

  await C({ id: 'REG-106', feature: 'Region move', prd: 'B1', title: 'A move by someone managing both regions (not admin) works and lists follow it',
    steps: 'sam (MANAGE N, APPROVE S) moves an N customer to S; nora (READ S) then sees it read-only', expected: '200; nora GET -> 200 canManage false; regionIds=S list includes it' }, async () => {
    const c = await mkCustomer(F, 'N', { amounts: [100] });
    const r = await rt.moveCustomerRegion(T.sam, c.id, R.S, 'e2e sam');
    const n = await get(T.nora, `/api/customers/${c.id}`);
    const l = ids(await rt.list(T.nora, '/api/invoices', {}, { regionIds: [R.S] }));
    return { ok: r.status === 200 && n.status === 200 && n.json.canManage === false && l.includes(c.invoices[0].id), actual: { move: excerpt(r, 150), nora: [n.status, n.json?.canManage, n.json?.regionCode], listed: l.includes(c.invoices[0].id) } };
  });



  await C({ id: 'REG-108', feature: 'Region move', prd: 'B1', title: 'A move is refused while changes await approval unless the mover may approve them in the source region',
    steps: 'South customer; sam records a 6000 payment (held above South PAYMENT_RECORD 5000); max (MANAGE South+West, no APPROVE) moves it to West; admin (APPROVE South) moves it',
    expected: 'max: 400 "… has 1 change awaiting approval; they must be decided first", nothing moved; admin: 200, moved.other.PendingChangeMoveParticipant 1, the pending change CLOSED (DESIGN D7, §6.4)' }, async () => {
    const max = await ensureStaff(admin, 'max', 'E2E_REGIONAL', [{ regionId: R.S, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: R.W, accessLevel: 'MANAGE', recordScope: 'ALL' }], 'Max Mover');
    const tm = await rt.login(max.username);
    const c = await mkCustomer(F, 'S', { amounts: [9000] });
    const held = await rt.createPayment(T.sam, { customerId: c.id, amount: 6000, invoiceIds: [c.invoices[0].id], collectionPocUserId: U.sam.id });
    if (!held.held) return { status: 'BLOCKED', actual: `fixture: the 6000 payment was not held: ${excerpt(held, 200)}` };
    const a = await rt.moveCustomerRegion(tm, c.id, R.W, 'max');
    const still = (await get(admin, `/api/customers/${c.id}`)).json.regionId;
    const b = await rt.moveCustomerRegion(admin, c.id, R.W, 'admin');
    const pc = await rt.approvals.get(admin, held.pendingChange.id);
    const ok = a.status === 400 && /awaiting approval/i.test(a.text) && still === R.S && b.status === 200 && b.json.moved?.other?.PendingChangeMoveParticipant === 1 && pc.json?.status === 'CLOSED';
    return { ok, actual: { maxMove: excerpt(a, 220), regionAfterRefusal: still, adminMove: [b.status, b.json?.moved?.other], pendingStatus: pc.json?.status || excerpt(pc, 150) } };
  });

  await C({ id: 'REG-107', feature: 'Region move', prd: 'B1', title: 'A deleted customer\'s history stays behind its last region',
    steps: 'nora creates+deletes a North customer (no invoices); nora and wendy GET /api/audit?entityType=CUSTOMER&entityId=<id>', expected: 'nora 200 with CUSTOMER_DELETED; wendy (no North grant) 404 (DESIGN §5.6.5 readOrGone, §8.3 RegionAdminTest)' }, async () => {
    const c = await mkCustomer(F, 'N', { amounts: [] });
    const del = await rt.api('DELETE', `/api/customers/${c.id}`, { token: T.nora });
    const a = await get(T.nora, '/api/audit', { entityType: 'CUSTOMER', entityId: c.id }); const b = await get(T.wendy, '/api/audit', { entityType: 'CUSTOMER', entityId: c.id });
    const acts = (a.json || []).map((x) => x.action);
    return { ok: [200, 204].includes(del.status) && a.status === 200 && acts.includes('CUSTOMER_DELETED') && b.status === 404, actual: { del: del.status, nora: [a.status, acts], wendy: b.status } };
  });

  // ---------------- Customer logins ----------------
  await C({ id: 'REG-110', feature: 'Customer login', prd: 'B1', title: 'A customer login sees only its own records and ignores regionIds',
    steps: 'cust (customer s1) GET /api/invoices; ?regionIds=N; ?regionIds=W', expected: 'only s1 invoices each time (DESIGN D17)' }, async () => {
    const own = ids(await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${w.customers.s1.id}` }));
    const a = await rt.list(T.cust, '/api/invoices'); const b = await rt.list(T.cust, '/api/invoices', {}, { regionIds: [R.N] }); const c = await rt.list(T.cust, '/api/invoices', {}, { regionIds: [R.W] });
    const ok = same(ids(a), own) && same(ids(b), own) && same(ids(c), own);
    return { ok, actual: { own, plain: ids(a), north: ids(b), west: ids(c) } };
  });

  await C({ id: 'REG-111', feature: 'Customer login', prd: 'B1', title: 'A customer login cannot open another customer\'s records',
    steps: 'cust GET /api/invoices/{s2 inv}; /api/customers/{s2}; /api/customers/{s1}; /api/disputes/{n1 dispute}; /api/payments/{s2 pay}', expected: '404; 403 (X1); 200; 403 (X1); 404 (DESIGN D17)' }, async () => {
    const a = await get(T.cust, `/api/invoices/${w.invoices.s2[0].id}`); const b = await get(T.cust, `/api/customers/${w.customers.s2.id}`);
    const c = await get(T.cust, `/api/customers/${w.customers.s1.id}`); const d = await get(T.cust, `/api/disputes/${F.disputes.n1.id}`);
    const e = await get(T.cust, `/api/payments/${w.payments.s2[0].id}`);
    const ok = a.status === 404 && b.status === 403 && c.status === 200 && d.status === 403 && e.status === 404;
    return { ok, actual: { otherInvoice: a.status, otherCustomer: b.status, ownCustomer: c.status, otherDispute: d.status, otherPayment: e.status } };
  });

  await C({ id: 'REG-112', feature: 'Customer login', prd: 'B1', title: 'A customer login\'s dashboard and tasks are its own only',
    steps: 'cust GET /api/dashboard/billed-by-month?months=3, /outstanding-by-age; /api/tasks', expected: 'coverage OWN and the totals equal customer s1\'s own invoices (billed sum, outstanding sum); tasks refused or empty (D17). Top-customer rankings stay refused for customers (pre-existing, DashboardService)' }, async () => {
    const own = (await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${w.customers.s1.id}` })).json.content;
    const a = await get(T.cust, '/api/dashboard/billed-by-month', { months: 3 }); const b = await get(T.cust, '/api/dashboard/outstanding-by-age');
    const t = await get(T.cust, '/api/tasks', { size: 10 });
    const billed = (a.json?.months || []).reduce((x, m) => x + Number(m.amount), 0); const ownBilled = own.filter((i) => i.status !== 'CANCELLED').reduce((x, i) => x + Number(i.total), 0);
    const outst = (b.json?.buckets || []).reduce((x, m) => x + Number(m.amount), 0); const ownOut = own.filter((i) => i.status !== 'CANCELLED').reduce((x, i) => x + Number(i.balance), 0);
    const ok = a.status === 200 && a.json.coverage === 'OWN' && Math.abs(billed - ownBilled) < 0.01 && b.status === 200 && Math.abs(outst - ownOut) < 0.01 && (t.status === 403 || (t.status === 200 && t.json.totalElements === 0));
    return { ok, actual: { coverage: a.json?.coverage, billed, ownBilled, outstanding: outst, ownOutstanding: ownOut, tasks: t.status === 200 ? t.json.totalElements : t.status } };
  });

  await C({ id: 'REG-113', feature: 'Customer login', prd: 'B1', title: 'A customer login follows its customer\'s region move',
    steps: 'S customer with a login; admin moves it to West; the login GET /api/invoices, /api/customers/{own}, /api/auth/me', expected: 'still its own invoices; own customer 200 with regionCode WEST; /me regions [] (DESIGN D17, §8.3 CustomerLoginRegionTest)' }, async () => {
    const c = await mkCustomer(F, 'S', { amounts: [100, 200], login: true });
    const before = ids(await rt.list(c.token, '/api/invoices'));
    const r = await rt.moveCustomerRegion(admin, c.id, R.W, 'login follows');
    const after = ids(await rt.list(c.token, '/api/invoices'));
    const own = await get(c.token, `/api/customers/${c.id}`); const m = await rt.me(c.token);
    const ok = r.status === 200 && same(before, after) && after.length === 2 && own.status === 200 && own.json.regionCode === 'WEST' && m.regions.length === 0;
    return { ok, actual: { move: r.status, before, after, own: [own.status, own.json?.regionCode], meRegions: m.regions } };
  });
}

// ------------------------------------------------------------------------------------------
// As-of move history (ages the whole database, so it runs last)
// ------------------------------------------------------------------------------------------
async function asOfCases(F) {
  const { admin, R, T } = F;
  const c = await mkCustomer(F, 'N', { amounts: [100, 200] });
  rt.ageWorld(4);
  const moveR = await rt.moveCustomerRegion(admin, c.id, R.S, 'as-of probe');
  const past = rt.day(-2);
  await C({ id: 'REG-120', feature: 'Region move history', prd: 'B1', title: 'As of a date before the move the customer and its invoices are in North; live they are in South',
    steps: 'N customer + 2 invoices; ageWorld(4); admin moves it to South; admin GET /api/customers/{id}?asOf=<2 days ago>; live GET',
    expected: 'asOf: 200 regionCode NORTH, header X-View-As-Of=<date>; live: SOUTH (PRD B3 "which region a record belonged to then"; DESIGN §8.3 CustomerRegionMoveTest)' }, async () => {
    const a = await get(admin, `/api/customers/${c.id}`, { asOf: past }); const b = await get(admin, `/api/customers/${c.id}`);
    const ok = moveR.status === 200 && a.status === 200 && a.json.regionCode === 'NORTH' && a.headers['x-view-as-of'] === past && b.json.regionCode === 'SOUTH';
    return { ok, actual: { move: moveR.status, asOf: [a.status, a.json?.regionCode, a.headers['x-view-as-of']], live: b.json?.regionCode } };
  });

  await C({ id: 'REG-121', feature: 'Region move history', prd: 'B1', title: 'Region-filtered lists as of before the move put the invoices in North, and live in South; PageResponse echoes asOf and regionIds',
    steps: 'admin GET /api/invoices?regionIds=N&asOf=<past>&filter=customerId:eq:<id>; same live; same with regionIds=S',
    expected: 'past N: 2 rows, asOf=<past>, regionIds [N]; live N: 0; live S: 2; past S: 0' }, async () => {
    const q = { filter: `customerId:eq:${c.id}`, size: 10 };
    const pN = await get(admin, '/api/invoices', { ...q, regionIds: R.N, asOf: past }); const lN = await get(admin, '/api/invoices', { ...q, regionIds: R.N });
    const pS = await get(admin, '/api/invoices', { ...q, regionIds: R.S, asOf: past }); const lS = await get(admin, '/api/invoices', { ...q, regionIds: R.S });
    const ok = pN.json?.totalElements === 2 && pN.json.asOf === past && same(pN.json.regionIds, [R.N]) && pN.headers['x-view-as-of'] === past
      && lN.json?.totalElements === 0 && lS.json?.totalElements === 2 && pS.json?.totalElements === 0;
    return { ok, actual: { pastN: [pN.status, pN.json?.totalElements, pN.json?.asOf, pN.json?.regionIds], liveN: lN.json?.totalElements, pastS: pS.json?.totalElements, liveS: lS.json?.totalElements } };
  });

  await C({ id: 'REG-122', feature: 'Region move history', prd: 'B1', title: 'A North-only reader can still see, as of before the move, a customer that has since left North',
    steps: 'bob-like check with nora? nora holds N+S; use wendy-free: create user nell MANAGE North only; GET /api/customers/{id}?asOf=<past>; live', expected: 'asOf: 200 (the record was in North then; current grants applied to region at T, DESIGN D9); live: 404' }, async () => {
    const nell = await ensureStaff(admin, 'nell', 'E2E_REGIONAL', [{ regionId: R.N, accessLevel: 'MANAGE', recordScope: 'ALL' }], 'Nell North');
    const t = await rt.login(nell.username);
    const a = await get(t, `/api/customers/${c.id}`, { asOf: past }); const b = await get(t, `/api/customers/${c.id}`);
    return { ok: a.status === 200 && a.json.regionCode === 'NORTH' && b.status === 404, actual: { asOf: [a.status, a.json?.regionCode], live: b.status } };
  });
  void T;
}

// ------------------------------------------------------------------------------------------
(async () => {
  const t0 = Date.now();
  const F = await setup();
  console.log(`fixtures ready in ${Date.now() - t0} ms: regions ${JSON.stringify(F.R)}`);
  if (!process.env.SKIP_API) await apiCases(F);
  if (!process.env.SKIP_UI) await ui.uiCases(F, RUN, { mkCustomer });
  if (!process.env.SKIP_API && !process.env.SKIP_EXT) await require('./ext.js').extCases(F, RUN, { mkCustomer }); // round-2 additions
  if (!process.env.SKIP_API && !process.env.SKIP_EXT) await require('./ext3.js').ext3Cases(F, RUN, { mkCustomer }); // round-3 additions
  if (!process.env.SKIP_EXT) await require('./ext4.js').ext4Cases(F, RUN, { mkCustomer }); // round-4 additions
  if (!process.env.SKIP_EXT) await require('./ext5.js').ext5Cases(F, RUN, { mkCustomer }); // round-5 additions
  if (!process.env.SKIP_EXT) await require('./ext6.js').ext6Cases(F, RUN, { mkCustomer }); // round-6 additions
  if (!process.env.SKIP_ASOF && !process.env.SKIP_API) await asOfCases(F);
  if (!process.env.SKIP_ASOF && !process.env.SKIP_EXT) await require('./ext4.js').ext4AfterAge(F, RUN); // round-4
  if (!process.env.SKIP_ASOF && !process.env.SKIP_EXT) await require('./ext5.js').ext5AfterAge(F, RUN); // round-5
  if (!process.env.SKIP_ASOF && !process.env.SKIP_EXT) await require('./ext6.js').ext6AfterAge(F, RUN); // round-6
  RUN.save();
  const n = (s) => RUN.cases.filter((c) => c.status === s).length;
  console.log(`${RUN.file}: ${RUN.cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED in ${Math.round((Date.now() - t0) / 1000)} s`);
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); RUN.save(); process.exit(2); });
