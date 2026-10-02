// API cases for B3 (as-of reads). Each case cites its PRD clause and the DESIGN section it checks.
const rt = require('../../lib.js');

const sum = (xs) => xs.reduce((a, b) => a + Number(b || 0), 0);
const ids = (rows) => (rows || []).map((r) => r.id).sort((a, b) => a - b);
const eqSet = (a, b) => JSON.stringify([...a].sort((x, y) => x - y)) === JSON.stringify([...b].sort((x, y) => x - y));
const short = (o, n = 400) => String((typeof o === 'string' ? o : JSON.stringify(o)) ?? o).slice(0, n);
function parseCsv(text) {
  const rows = []; let row = []; let f = ''; let q = false;
  const t = (text || '').replace(/^\uFEFF/, '');
  for (let i = 0; i < t.length; i++) {
    const ch = t[i];
    if (q) { if (ch === '"') { if (t[i + 1] === '"') { f += '"'; i++; } else q = false; } else f += ch; continue; }
    if (ch === '"') q = true; else if (ch === ',') { row.push(f); f = ''; } else if (ch === '\n') { row.push(f); rows.push(row); row = []; f = ''; } else if (ch !== '\r') f += ch;
  }
  if (f !== '' || row.length) { row.push(f); rows.push(row); }
  return rows;
}
const err = (r) => `${r.status} ${r.json ? `${r.json.error}: ${r.json.message}` : short(r.text, 200)}`;

module.exports = async function apiCases({ S, check }) {
  const A = S.tokens.admin;
  const T = rt.day(-2); // "then": after the (aged) phase A, before phase B
  const Y1 = rt.day(-1); // latest as-of day
  const BEFORE = rt.day(-(S.age + 5)); // before any record existed
  const get = (p, query = {}, token = A) => rt.api('GET', p, { token, query });
  const at = (q = {}) => ({ ...q, asOf: T });
  const C = S.customers;
  const I = S.invoices;
  const R = S.regions;

  // ---------------------------------------------------------------- range and validation
  await check({ id: 'ASO-API-01', feature: 'History range', prd: 'B3', title: 'GET /api/history/range reports the readable days (no lower bound on a fresh history, latest = yesterday, UTC)',
    steps: 'GET /api/history/range as admin', expected: 'DESIGN §6.10: 200 {historyStartsOn:null, latestAsOf: today-1, timezone:"UTC"}' }, async () => {
    const r = await get('/api/history/range');
    return { ok: r.status === 200 && r.json.historyStartsOn === null && r.json.latestAsOf === Y1 && r.json.timezone === 'UTC', actual: `${r.status} ${short(r.json)}` };
  });

  await check({ id: 'ASO-API-02', feature: 'asOf = today', prd: 'B3', title: 'asOf equal to today answers live (header "live", envelope asOf null, same rows as no asOf)',
    steps: `GET /api/invoices?asOf=${rt.day(0)} vs GET /api/invoices`, expected: 'DESIGN D9/§5.2.2: asOf == today -> live; X-View-As-Of: live' }, async () => {
    const a = await rt.list(A, '/api/invoices', { asOf: rt.day(0) });
    const b = await rt.list(A, '/api/invoices');
    const ok = a.status === 200 && a.headers['x-view-as-of'] === 'live' && a.json.asOf === null && eqSet(ids(a.json.content), ids(b.json.content));
    return { ok, actual: `${a.status} header=${a.headers['x-view-as-of']} asOf=${a.json && a.json.asOf} rows=${a.json && a.json.totalElements} live rows=${b.json.totalElements}` };
  });

  await check({ id: 'ASO-API-03', feature: 'Future date', prd: 'B3', title: 'A future asOf is refused with 400 AS_OF_FUTURE on lists, detail, dashboard and live-only endpoints',
    steps: `GET ?asOf=${rt.day(1)} on /api/invoices, /api/customers/{n1}, /api/dashboard/billed-by-month, /api/approvals, /api/regions`, expected: 'DESIGN §6.17: 400 AS_OF_FUTURE "asOf cannot be after today"' }, async () => {
    const paths = ['/api/invoices', `/api/customers/${C.n1.id}`, '/api/dashboard/billed-by-month', '/api/approvals', '/api/regions'];
    const out = [];
    for (const p of paths) {
      const r = await get(p, { asOf: rt.day(1) });
      out.push({ p, s: r.status, e: r.json && r.json.error, m: r.json && r.json.message });
    }
    return { ok: out.every((o) => o.s === 400 && o.e === 'AS_OF_FUTURE' && o.m === 'asOf cannot be after today'), actual: short(out, 800) };
  });

  await check({ id: 'ASO-API-04', feature: 'Invalid date', prd: 'B3', title: 'Malformed or impossible asOf values are refused with 400 AS_OF_INVALID (strict ISO date)',
    steps: 'GET /api/invoices?asOf=<2026-13-01 | 2026-02-30 | 27-09-2026 | 2026-9-5 | yesterday | 2026-09-25T00:00:00Z | 20260925>', expected: 'DESIGN §5.2.2/§6.17: 400 AS_OF_INVALID "asOf must be a date (yyyy-MM-dd)"' }, async () => {
    const vals = ['2026-13-01', '2026-02-30', '27-09-2026', '2026-9-5', 'yesterday', `${T}T00:00:00Z`, T.replace(/-/g, '')];
    const out = [];
    for (const v of vals) {
      const r = await get('/api/invoices', { asOf: v });
      out.push({ v, s: r.status, e: r.json && r.json.error });
    }
    return { ok: out.every((o) => o.s === 400 && o.e === 'AS_OF_INVALID'), actual: short(out, 800) };
  });

  await check({ id: 'ASO-API-05', feature: 'Blank asOf', prd: 'B3', title: 'A blank asOf parameter is the live view',
    steps: 'GET /api/invoices?asOf=', expected: 'DESIGN §5.2.2: blank -> no point bound; 200 live, envelope asOf null' }, async () => {
    const r = await rt.api('GET', '/api/invoices?asOf=&size=10', { token: A });
    return { ok: r.status === 200 && r.json.asOf === null, actual: `${r.status} asOf=${r.json && r.json.asOf}` };
  });

  await check({ id: 'ASO-API-06', feature: 'Before history', prd: 'B3', title: 'A date before history began is refused with AS_OF_BEFORE_HISTORY; the first history day is accepted; the range reports it',
    steps: `SQL: history_meta.started_at = ${rt.day(-12)}T06:00Z (simulates a deployment whose history began then); GET /api/history/range; GET /api/invoices?asOf=${rt.day(-13)}; ?asOf=${rt.day(-12)}; then restore started_at`,
    expected: 'DESIGN D9/§6.17: 400 AS_OF_BEFORE_HISTORY "History is available from <d MMM yyyy>"; the start day itself 200; range.historyStartsOn = that day' }, async () => {
    const prevRow = rt.sql("select coalesce(started_at::text, 'NULL') from history_meta where id = 1");
    const prev = prevRow.length && prevRow[0][0] !== 'NULL' ? prevRow[0][0] : null;
    try {
      rt.sql(`update history_meta set started_at = '${rt.day(-12)}T06:00:00Z' where id = 1`);
      const range = await get('/api/history/range');
      const before = await get('/api/invoices', { asOf: rt.day(-13), size: 10 });
      const onDay = await get('/api/invoices', { asOf: rt.day(-12), size: 10 });
      const detail = await get(`/api/customers/${C.n1.id}`, { asOf: rt.day(-13) });
      const d = new Date(`${rt.day(-12)}T00:00:00Z`);
      const label = `${d.getUTCDate()} ${d.toLocaleString('en-US', { month: 'short', timeZone: 'UTC' })} ${d.getUTCFullYear()}`;
      const ok = range.json.historyStartsOn === rt.day(-12) && before.status === 400 && before.json.error === 'AS_OF_BEFORE_HISTORY'
        && before.json.message === `History is available from ${label}` && onDay.status === 200 && detail.status === 400 && detail.json.error === 'AS_OF_BEFORE_HISTORY';
      return { ok, actual: `range=${short(range.json)}; before=${err(before)}; onDay=${onDay.status}; detail=${err(detail)}` };
    } finally {
      rt.sql(prev ? `update history_meta set started_at = '${prev}' where id = 1` : 'update history_meta set started_at = null where id = 1');
    }
  });

  await check({ id: 'ASO-API-07', feature: 'Configuration answers live', prd: 'B3', title: 'Configuration endpoints answer live under a past date (header "live", no error)',
    steps: `GET with asOf=${T}: /api/regions, /api/users, /api/roles, /api/table-schemas/invoices, /api/notifications, /api/regions/{SOUTH}/approval-thresholds, /api/approvals/operations, /api/automation/placeholders, /api/pocs/my-scope, /api/auth/me, /api/history/range`,
    expected: 'DESIGN D9 + §6.1: IGNORED endpoints 200 with X-View-As-Of: live; list envelopes asOf null' }, async () => {
    const paths = [['/api/regions', { size: 50 }], ['/api/users', { size: 50 }], ['/api/roles', { size: 50 }], ['/api/table-schemas/invoices', {}], ['/api/notifications', { size: 20 }],
      [`/api/regions/${R.SOUTH.id}/approval-thresholds`, {}], ['/api/approvals/operations', {}], ['/api/automation/placeholders', { entityType: 'INVOICE' }], ['/api/pocs/my-scope', {}], ['/api/auth/me', {}], ['/api/history/range', {}]];
    const out = [];
    for (const [p, q] of paths) {
      const r = await get(p, { ...q, asOf: T });
      out.push({ p, s: r.status, h: r.headers['x-view-as-of'], asOf: r.json && !Array.isArray(r.json) ? r.json.asOf : undefined });
    }
    const ok = out.every((o) => o.s === 200 && o.h === 'live' && (o.asOf === undefined || o.asOf === null));
    return { ok, actual: short(out.filter((o) => !(o.s === 200 && o.h === 'live' && !o.asOf)), 800) || 'all live' };
  });

  await check({ id: 'ASO-API-08', feature: 'CORS header', prd: 'B3', title: 'X-View-As-Of is exposed to the browser through CORS',
    steps: `GET /api/invoices?asOf=${T} with Origin: ${rt.WEB.replace(/\/$/, '')}`, expected: 'DESIGN §5.2.2/§6.0: Access-Control-Expose-Headers includes X-View-As-Of' }, async () => {
    const r = await rt.api('GET', '/api/invoices', { token: A, query: { asOf: T, size: 10 }, headers: { Origin: rt.WEB.replace(/\/$/, '') } });
    const exp = r.headers['access-control-expose-headers'] || '';
    return { ok: r.status === 200 && /x-view-as-of/i.test(exp), actual: `${r.status} expose=${exp} acao=${r.headers['access-control-allow-origin']}` };
  });

  await check({ id: 'ASO-API-09', feature: 'Empty past', prd: 'B3', title: 'A day before any record existed shows an empty world: lists empty, dashboard zero, a record by id 404',
    steps: `GET /api/invoices, /api/customers, /api/dashboard/billed-by-month, /api/invoices/{n1 inv}, /api/customers/{n1} with asOf=${BEFORE}`, expected: 'DESIGN D9: a record exists at T iff it has a version valid at T; D15 404 when no version at T' }, async () => {
    const inv = await get('/api/invoices', { asOf: BEFORE, size: 10 });
    const cus = await get('/api/customers', { asOf: BEFORE, size: 10 });
    const bil = await get('/api/dashboard/billed-by-month', { asOf: BEFORE });
    const d1 = await get(`/api/invoices/${I.n1[0].id}`, { asOf: BEFORE });
    const d2 = await get(`/api/customers/${C.n1.id}`, { asOf: BEFORE });
    const ok = inv.status === 200 && inv.json.totalElements === 0 && cus.json.totalElements === 0 && bil.status === 200 && sum(bil.json.months.map((m) => m.amount)) === 0 && d1.status === 404 && d2.status === 404;
    return { ok, actual: `invoices=${inv.json && inv.json.totalElements} customers=${cus.json && cus.json.totalElements} billed=${bil.json && sum(bil.json.months.map((m) => m.amount))} invoiceById=${d1.status} customerById=${d2.status}` };
  });

  // ---------------------------------------------------------------- lists, figures and detail at T
  const invT = await rt.list(A, '/api/invoices', { asOf: T });
  const invLive = await rt.list(A, '/api/invoices');
  const rowT = (id) => invT.json.content.find((r) => r.id === id);
  const rowLive = (id) => invLive.json.content.find((r) => r.id === id);

  await check({ id: 'ASO-API-10', feature: 'Invoice list as of', prd: 'B3', title: 'The invoice list as of T holds exactly the invoices that existed then; later ones appear only live',
    steps: `setup (seed, X, held changes) -> ageWorld(${S.age}) -> create N, approve Y, create s1-300; GET /api/invoices?asOf=${T} (all pages) vs live`,
    expected: 'PRD B3 "every list ... can be asked as of a date"; DESIGN D9: rows = the then-snapshot ids; envelope asOf=T; header X-View-As-Of=T; N, Y, s1-300 only live' }, async () => {
    const thenIds = Object.keys(S.then.invoices).map(Number);
    const tIds = ids(invT.json.content);
    const liveIds = ids(invLive.json.content);
    const later = [S.invoiceN.id, S.invoiceY.id, S.invoiceS1Small.id];
    const ok = invT.status === 200 && invT.headers['x-view-as-of'] === T && invT.json.asOf === T && eqSet(tIds, thenIds) && invT.json.totalElements === S.then.invoiceCount
      && later.every((i) => !tIds.includes(i) && liveIds.includes(i));
    return { ok, actual: `asOf rows=${tIds.length} (then ${thenIds.length}) header=${invT.headers['x-view-as-of']} envelope.asOf=${invT.json.asOf}; later ids ${later} in asOf: ${later.filter((i) => tIds.includes(i))}, in live: ${later.filter((i) => liveIds.includes(i))}` };
  });

  await check({ id: 'ASO-API-11', feature: 'Invoice values as of', prd: 'B3', title: 'Each invoice row as of T carries its then status, total, balance, customer name (at T) and region (at T)',
    steps: `compare every row of GET /api/invoices?asOf=${T} with the snapshot taken before ageing; X cancelled (approved) after T; n1 renamed, n2 moved after T; d1 paid after T`,
    expected: 'DESIGN §5.7.13: customerName is the name at T; D9: region at T; X UNPAID at T and CANCELLED live' }, async () => {
    const diffs = [];
    for (const [id, th] of Object.entries(S.then.invoices)) {
      const r = rowT(Number(id));
      if (!r) { diffs.push(`${id} missing`); continue; }
      for (const k of ['status', 'total', 'balance', 'customerName', 'regionId']) if (String(r[k]) !== String(th[k])) diffs.push(`${id}.${k}: then=${th[k]} asOf=${r[k]}`);
    }
    const x = rowLive(S.invoiceX.id);
    const ok = diffs.length === 0 && x && x.status === 'CANCELLED' && rowT(S.invoiceX.id).status === 'UNPAID';
    return { ok, actual: diffs.length ? diffs.slice(0, 12).join('; ') : `all ${Object.keys(S.then.invoices).length} rows match; X live=${x && x.status}` };
  });

  await check({ id: 'ASO-API-12', feature: 'Invoice tiles as of', prd: 'B3', title: 'The invoice summary tiles as of T equal what they showed then',
    steps: `GET /api/invoices/summary?asOf=${T} vs the snapshot before ageing, and vs live`, expected: 'PRD B3 "every ... count ... as of a date"; DESIGN §5.1 tiles use Today.date()=T' }, async () => {
    const r = await get('/api/invoices/summary', { asOf: T });
    const live = await get('/api/invoices/summary');
    const strip = (o) => { const c = { ...o }; delete c.scope; return c; };
    const ok = r.status === 200 && JSON.stringify(strip(r.json)) === JSON.stringify(strip(S.then.invoiceSummary)) && JSON.stringify(strip(live.json)) !== JSON.stringify(strip(S.then.invoiceSummary));
    return { ok, actual: `asOf=${short(strip(r.json), 300)} then=${short(strip(S.then.invoiceSummary), 300)} live=${short(strip(live.json), 300)}` };
  });

  const cusT = await rt.list(A, '/api/customers', { asOf: T });
  const cusLive = await rt.list(A, '/api/customers');
  await check({ id: 'ASO-API-13', feature: 'Customer list as of', prd: 'B3', title: 'The customer list as of T shows names, regions and outstanding as they were then',
    steps: `GET /api/customers?asOf=${T} vs snapshot; n1 renamed and n2 moved to WEST and d1 paid after T`, expected: 'PRD B3 + B1 "which region a record belonged to then"; values equal the then-snapshot; live shows the new name/region' }, async () => {
    const diffs = [];
    for (const [id, th] of Object.entries(S.then.customers)) {
      const r = cusT.json.content.find((c) => c.id === Number(id));
      if (!r) { diffs.push(`${id} missing`); continue; }
      for (const k of ['name', 'regionId', 'outstanding']) if (String(r[k]) !== String(th[k])) diffs.push(`${id}.${k}: then=${th[k]} asOf=${r[k]}`);
    }
    const n1l = cusLive.json.content.find((c) => c.id === C.n1.id);
    const n2l = cusLive.json.content.find((c) => c.id === C.n2.id);
    const ok = cusT.json.asOf === T && diffs.length === 0 && cusT.json.totalElements === S.then.customerCount && n1l.name === C.n1.newName && n2l.regionId === R.WEST.id;
    return { ok, actual: diffs.length ? diffs.join('; ') : `all ${cusT.json.totalElements} match; live n1=${n1l.name} n2 region=${n2l.regionId}` };
  });

  await check({ id: 'ASO-API-14', feature: 'Customer tiles as of', prd: 'B3', title: 'The customer summary tiles as of T equal what they showed then',
    steps: `GET /api/customers/summary?asOf=${T}`, expected: 'PRD B3 "every ... count"; equals the then-snapshot' }, async () => {
    const r = await get('/api/customers/summary', { asOf: T });
    const strip = (o) => { const c = { ...o }; delete c.scope; return c; };
    return { ok: r.status === 200 && JSON.stringify(strip(r.json)) === JSON.stringify(strip(S.then.customerSummary)), actual: `asOf=${short(r.json, 300)} then=${short(S.then.customerSummary, 300)}` };
  });

  await check({ id: 'ASO-API-15', feature: 'Payment list as of', prd: 'B3', title: 'The payment list as of T excludes the payment recorded after T; the paid invoice has its then balance',
    steps: `GET /api/payments?asOf=${T}; GET /api/invoices/{d1 inv2500}?asOf=${T} vs live`, expected: 'rows = then ids; invoice balance 2500 at T, 1500 live' }, async () => {
    const p = await rt.list(A, '/api/payments', { asOf: T });
    const pl = await rt.list(A, '/api/payments');
    const di = await get(`/api/invoices/${S.paymentNow.invoiceId}`, { asOf: T });
    const dl = await get(`/api/invoices/${S.paymentNow.invoiceId}`);
    const ok = eqSet(ids(p.json.content), S.then.paymentIds) && ids(pl.json.content).includes(S.paymentNow.id) && Number(di.json.balance) === 2500 && Number(dl.json.balance) === 1500;
    return { ok, actual: `asOf ids=${ids(p.json.content)} then=${S.then.paymentIds} liveHasNew=${ids(pl.json.content).includes(S.paymentNow.id)}; balance asOf=${di.json && di.json.balance} live=${dl.json && dl.json.balance}` };
  });

  await check({ id: 'ASO-API-16', feature: 'Filters as of', prd: 'B3', title: 'Filters answer against the values at T (status, customer name at T, pendingApproval)',
    steps: `GET /api/invoices?asOf=${T}&filter=status:eq:CANCELLED; &filter=customerName:contains:<old n1 name>; live the same filters`,
    expected: 'PRD B3 "every ... filter"; X is not CANCELLED at T; the old name finds n1\'s 2 invoices at T and none live' }, async () => {
    const canT = await rt.list(A, '/api/invoices', { asOf: T, filter: 'status:eq:CANCELLED' });
    const canL = await rt.list(A, '/api/invoices', { filter: 'status:eq:CANCELLED' });
    const oldT = await rt.list(A, '/api/invoices', { asOf: T, filter: `customerName:contains:${C.n1.name}` });
    const oldL = await rt.list(A, '/api/invoices', { filter: `customerName:contains:${C.n1.name}` });
    const newL = await rt.list(A, '/api/invoices', { filter: `customerName:contains:${C.n1.newName}` });
    const ok = !ids(canT.json.content).includes(S.invoiceX.id) && ids(canL.json.content).includes(S.invoiceX.id)
      && eqSet(ids(oldT.json.content), I.n1.map((i) => i.id)) && oldL.json.totalElements === 0 && newL.json.totalElements === 3;
    return { ok, actual: `cancelled asOf=${ids(canT.json.content)} live=${ids(canL.json.content)}; oldName asOf=${ids(oldT.json.content)} live=${oldL.json.totalElements}; newName live=${newL.json.totalElements}` };
  });

  await check({ id: 'ASO-API-17', feature: 'Regions as of', prd: 'B3', title: 'regionIds narrows by the region each record belonged to at T (n2 moved NORTH->WEST after T)',
    steps: `GET /api/invoices?regionIds=${R.NORTH.id}&asOf=${T}; live regionIds=${R.NORTH.id} and ${R.WEST.id}`,
    expected: 'PRD B3 "including which region a record belonged to then"; n2 invoices in NORTH at T, in WEST live; envelope regionIds=[NORTH]' }, async () => {
    const n2 = I.n2.map((i) => i.id);
    const nT = await rt.list(A, '/api/invoices', {}, { regionIds: [R.NORTH.id], asOf: T });
    const nL = await rt.list(A, '/api/invoices', {}, { regionIds: [R.NORTH.id] });
    const wT = await rt.list(A, '/api/invoices', {}, { regionIds: [R.WEST.id], asOf: T });
    const wL = await rt.list(A, '/api/invoices', {}, { regionIds: [R.WEST.id] });
    const has = (r) => n2.every((i) => ids(r.json.content).includes(i));
    const ok = has(nT) && !has(nL) && !has(wT) && has(wL) && JSON.stringify(nT.json.regionIds) === JSON.stringify([R.NORTH.id]);
    return { ok, actual: `NORTH asOf has n2=${has(nT)} live=${has(nL)}; WEST asOf has n2=${has(wT)} live=${has(wL)}; envelope regionIds=${JSON.stringify(nT.json.regionIds)}` };
  });

  await check({ id: 'ASO-API-18', feature: 'Customer detail as of', prd: 'B3', title: 'A customer by id as of T shows its region then and no write rights (flags false, version null)',
    steps: `GET /api/customers/{n2}?asOf=${T} vs live`, expected: 'DESIGN D14: every flag false in historical mode; §5.7.15 rehydrated DTOs carry version null; region NORTH at T, WEST live' }, async () => {
    const t = await get(`/api/customers/${C.n2.id}`, { asOf: T });
    const l = await get(`/api/customers/${C.n2.id}`);
    const ok = t.status === 200 && t.json.regionId === R.NORTH.id && t.json.regionCode === 'NORTH' && t.json.canManage === false && t.json.canMoveRegion === false && t.json.version == null
      && l.json.regionCode === 'WEST' && l.json.canMoveRegion === true;
    return { ok, actual: `asOf ${t.status} region=${t.json && t.json.regionCode} canManage=${t.json && t.json.canManage} canMove=${t.json && t.json.canMoveRegion} version=${t.json && t.json.version}; live region=${l.json.regionCode} canMove=${l.json.canMoveRegion}` };
  });

  await check({ id: 'ASO-API-19', feature: 'Invoice detail as of', prd: 'B3', title: 'An invoice by id as of T shows its then status and due date, with canManage false',
    steps: `GET /api/invoices/{X}?asOf=${T}; GET /api/invoices/{n1 inv1}?asOf=${T} (due date moved after T)`, expected: 'X UNPAID at T, CANCELLED live; n1 inv1 dueDate = then value; canManage false; version null' }, async () => {
    const x = await get(`/api/invoices/${S.invoiceX.id}`, { asOf: T });
    const xl = await get(`/api/invoices/${S.invoiceX.id}`);
    const p = await get(`/api/invoices/${S.patchedInvoice.id}`, { asOf: T });
    const pl = await get(`/api/invoices/${S.patchedInvoice.id}`);
    const ok = x.status === 200 && x.json.status === 'UNPAID' && x.json.canManage === false && x.json.version == null && xl.json.status === 'CANCELLED'
      && p.json.dueDate === S.patchedInvoice.dueDateThen && pl.json.dueDate === S.patchedInvoice.dueDateNow;
    return { ok, actual: `X asOf=${x.json && x.json.status} canManage=${x.json && x.json.canManage} version=${x.json && x.json.version} live=${xl.json.status}; due asOf=${p.json && p.json.dueDate} live=${pl.json.dueDate}` };
  });

  await check({ id: 'ASO-API-20', feature: 'Later records 404', prd: 'B3', title: 'Records created after T are 404 by id as of T (invoice, approved invoice, payment, task, promise, dispute)',
    steps: `GET by id with asOf=${T}: /api/invoices/{N}, /api/invoices/{Y}, /api/payments/{new}, /api/tasks/{T2}, /api/promises/{new}, /api/disputes/{new}`, expected: 'DESIGN D15: 404 when there is no version at T' }, async () => {
    const paths = [`/api/invoices/${S.invoiceN.id}`, `/api/invoices/${S.invoiceY.id}`, `/api/payments/${S.paymentNow.id}`, `/api/tasks/${S.tasks.T2.id}`, `/api/promises/${S.promiseNow.id}`, `/api/disputes/${S.disputeNow.id}`];
    const out = [];
    for (const p of paths) { const r = await get(p, { asOf: T }); const l = await get(p); out.push({ p, asOf: r.status, live: l.status }); }
    return { ok: out.every((o) => o.asOf === 404 && o.live === 200), actual: short(out, 800) };
  });

  await check({ id: 'ASO-API-21', feature: 'Latest day', prd: 'B3', title: 'Yesterday (the latest as-of day) answers the same pre-change world',
    steps: `GET /api/invoices?asOf=${Y1}`, expected: 'DESIGN §6.10 latestAsOf=yesterday; rows = then ids' }, async () => {
    const r = await rt.list(A, '/api/invoices', { asOf: Y1 });
    return { ok: r.status === 200 && r.json.asOf === Y1 && eqSet(ids(r.json.content), Object.keys(S.then.invoices).map(Number)), actual: `${r.status} asOf=${r.json && r.json.asOf} rows=${r.json && r.json.totalElements}` };
  });

  // ---------------------------------------------------------------- rights applied to the region at T
  await check({ id: 'ASO-API-22', feature: 'Region at T: rights', prd: 'B3', title: 'nora (MANAGE North, nothing in West) can open n2 as of T (North then) but not live (West now)',
    steps: `nora GET /api/customers/{n2}?asOf=${T}; nora GET /api/customers/{n2}`, expected: 'DESIGN D9 Rights: current grants applied to the record\'s region at T -> 200 at T, 404 live' }, async () => {
    const t = await get(`/api/customers/${C.n2.id}`, { asOf: T }, S.tokens.nora);
    const l = await get(`/api/customers/${C.n2.id}`, {}, S.tokens.nora);
    return { ok: t.status === 200 && l.status === 404, actual: `asOf=${t.status} live=${l.status}` };
  });
  await check({ id: 'ASO-API-23', feature: 'Region at T: rights', prd: 'B3', title: 'wendy (West only) cannot open n2 as of T (it was North) but can live',
    steps: `wendy GET /api/customers/{n2}?asOf=${T}; live`, expected: 'DESIGN D9: 404 at T, 200 live' }, async () => {
    const t = await get(`/api/customers/${C.n2.id}`, { asOf: T }, S.tokens.wendy);
    const l = await get(`/api/customers/${C.n2.id}`, {}, S.tokens.wendy);
    return { ok: t.status === 404 && l.status === 200, actual: `asOf=${t.status} live=${l.status}` };
  });
  await check({ id: 'ASO-API-24', feature: 'Region at T: lists', prd: 'B3', title: 'nora\'s and wendy\'s invoice lists as of T equal what each saw then; live they follow the move',
    steps: `nora/wendy GET /api/invoices?asOf=${T} (all pages) vs their snapshots and live`, expected: 'PRD B1+B3; nora at T = then ids (incl. n2), live without n2; wendy at T = then ids (no n2), live with n2' }, async () => {
    const n2 = I.n2.map((i) => i.id);
    const nT = await rt.list(S.tokens.nora, '/api/invoices', { asOf: T });
    const nL = await rt.list(S.tokens.nora, '/api/invoices');
    const wT = await rt.list(S.tokens.wendy, '/api/invoices', { asOf: T });
    const wL = await rt.list(S.tokens.wendy, '/api/invoices');
    const ok = eqSet(ids(nT.json.content), S.then.noraInvoiceIds) && eqSet(ids(wT.json.content), S.then.wendyInvoiceIds)
      && n2.every((i) => !ids(nL.json.content).includes(i)) && n2.every((i) => ids(wL.json.content).includes(i));
    return { ok, actual: `nora asOf=${ids(nT.json.content)} then=${S.then.noraInvoiceIds} live=${ids(nL.json.content)}; wendy asOf=${ids(wT.json.content)} then=${S.then.wendyInvoiceIds} live=${ids(wL.json.content)}` };
  });
  await check({ id: 'ASO-API-25', feature: 'Region at T: history tab', prd: 'B3', title: 'The customer\'s History (audit) as of T has no region-change entry; live it does',
    steps: `GET /api/audit?entityType=CUSTOMER&entityId={n2}&asOf=${T}; live`, expected: 'DESIGN §6.15: historical -> entries with createdAt <= T; CUSTOMER_REGION_CHANGED only live' }, async () => {
    const t = await get('/api/audit', { entityType: 'CUSTOMER', entityId: C.n2.id, asOf: T });
    const l = await get('/api/audit', { entityType: 'CUSTOMER', entityId: C.n2.id });
    const act = (r) => (r.json || []).map((e) => e.action);
    const ok = t.status === 200 && !act(t).includes('CUSTOMER_REGION_CHANGED') && act(l).includes('CUSTOMER_REGION_CHANGED');
    return { ok, actual: `asOf=${t.status} ${act(t).join(',')} | live ${act(l).join(',')}` };
  });
  await check({ id: 'ASO-API-26', feature: 'Region at T: dashboard', prd: 'B3', title: 'Top outstanding customers for NORTH as of T include n2 (North then); live NORTH does not',
    steps: `GET /api/dashboard/top-outstanding-customers?limit=10&regionIds=${R.NORTH.id}&asOf=${T}; live`, expected: 'PRD B1 "every dashboard figure answers which regions" + B3' }, async () => {
    const t = await get('/api/dashboard/top-outstanding-customers', { limit: 10, regionIds: R.NORTH.id, asOf: T });
    const l = await get('/api/dashboard/top-outstanding-customers', { limit: 10, regionIds: R.NORTH.id });
    const has = (r) => (r.json.customers || []).some((c) => c.customerId === C.n2.id);
    return { ok: t.status === 200 && has(t) && !has(l) && t.json.scope && t.json.scope.asOf === T, actual: `asOf has n2=${has(t)} scope=${short(t.json.scope)}; live has n2=${has(l)}` };
  });

  // ---------------------------------------------------------------- approvals outstanding then
  const P = S.pcs;
  // Outstanding at T: A (approved after T), B (still pending), D (approved after T), E (rejected after T, round 2).
  // Round 4: + G (payment 5500 naming promise P1) and H (promise 6000 naming s1's 2500 invoice), both withdrawn after T.
  const THEN_PENDING = [P.A.id, P.B.id, P.D.id].concat(P.E ? [P.E.id] : []).concat(P.G ? [P.G.id, P.H.id] : []);
  const THEN_AMOUNT = 20000 + 6000 + 6000 + (P.E ? P.E.amount : 0) + (P.G ? P.G.amount + P.H.amount : 0);
  await check({ id: 'ASO-API-30', feature: 'Approvals as of', prd: 'B3', title: 'The approvals list as of T shows exactly the changes outstanding then, each with statusAt PENDING',
    steps: `pcA (create 20000 s2), pcB (payment 6000 s1), pcD (cancel X), pcE (payment 7000 s1), pcF (create 12000 s1, approved before ageing) requested before ageing; pcA, pcD approved and pcE rejected after T; pcC requested after T. GET /api/approvals?asOf=${T}`,
    expected: 'PRD B3 "which approvals were outstanding"; DESIGN §5.11.10: rows A,B,D,E with statusAt PENDING; C (later) and F (decided before T) absent; live PENDING = B,C' }, async () => {
    const t = await rt.approvals.list(A, {}, { asOf: T });
    const l = await rt.approvals.list(A, { filter: 'status:eq:PENDING' });
    const tIds = ids(t.json.content);
    const ok = t.status === 200 && eqSet(tIds, THEN_PENDING) && eqSet(tIds, S.then.pendingIds) && t.json.content.every((r) => r.statusAt === 'PENDING')
      && eqSet(ids(l.json.content), [P.B.id, P.C.id]);
    return { ok, actual: `asOf ${t.status} ids=${tIds} statusAt=${(t.json.content || []).map((r) => `${r.id}:${r.status}/${r.statusAt}`)}; live pending=${ids(l.json.content)}` };
  });
  await check({ id: 'ASO-API-31', feature: 'Approvals as of', prd: 'B3', title: 'The approvals summary as of T counts the changes outstanding then',
    steps: `GET /api/approvals/summary?asOf=${T} vs live`, expected: `pendingCount ${THEN_PENDING.length}, pendingAmount ${THEN_AMOUNT} at T (= snapshot); live 2 / 21000` }, async () => {
    const t = await rt.approvals.summary(A, { asOf: T });
    const l = await rt.approvals.summary(A);
    const ok = t.status === 200 && t.json.pendingCount === THEN_PENDING.length && Number(t.json.pendingAmount) === THEN_AMOUNT && t.json.pendingCount === S.then.approvalsSummary.pendingCount
      && l.json.pendingCount === 2 && Number(l.json.pendingAmount) === 21000;
    return { ok, actual: `asOf=${short(t.json)} live=${short(l.json)} then=${short(S.then.approvalsSummary)}` };
  });
  await check({ id: 'ASO-API-32', feature: 'Approvals as of', prd: 'B3', title: 'for-record as of T lists the change outstanding on the record then (a pending create on s2, the cancel on X)',
    steps: `GET /api/approvals/for-record?entityType=CUSTOMER&entityId={s2}&asOf=${T}; ?entityType=INVOICE&entityId={X}&asOf=${T}; live`, expected: 'DESIGN §6.11: PENDING rows outstanding at T; live [] for both' }, async () => {
    const cT = await rt.approvals.forRecord(A, 'CUSTOMER', C.s2.id, { asOf: T });
    const cL = await rt.approvals.forRecord(A, 'CUSTOMER', C.s2.id);
    const xT = await rt.approvals.forRecord(A, 'INVOICE', S.invoiceX.id, { asOf: T });
    const xL = await rt.approvals.forRecord(A, 'INVOICE', S.invoiceX.id);
    const ok = eqSet(ids(cT.json), [P.A.id]) && ids(cL.json).length === 0 && eqSet(ids(xT.json), [P.D.id]) && ids(xL.json).length === 0;
    return { ok, actual: `s2 asOf=${cT.status}:${ids(cT.json)} live=${ids(cL.json)}; X asOf=${xT.status}:${ids(xT.json)} live=${ids(xL.json)}` };
  });
  await check({ id: 'ASO-API-33', feature: 'Approvals as of', prd: 'B3', title: 'Invoice X is flagged pendingApproval as of T (and filterable); not live',
    steps: `GET /api/invoices?asOf=${T} row X; &filter=pendingApproval:eq:true; live`, expected: 'DESIGN §5.11.10: the pendingApproval flag uses the interval' }, async () => {
    const f = await rt.list(A, '/api/invoices', { asOf: T, filter: 'pendingApproval:eq:true' });
    const ok = rowT(S.invoiceX.id).pendingApproval === true && ids(f.json.content).includes(S.invoiceX.id) && !rowLive(S.invoiceX.id).pendingApproval;
    return { ok, actual: `row asOf pendingApproval=${rowT(S.invoiceX.id).pendingApproval}; filter asOf=${f.status}:${ids(f.json && f.json.content)}; live=${rowLive(S.invoiceX.id).pendingApproval}` };
  });
  await check({ id: 'ASO-API-34', feature: 'Approvals as of', prd: 'B3', title: 'An approval by id as of T reads PENDING then, cannot be acted on, and says why',
    steps: `GET /api/approvals/{pcA}?asOf=${T} vs live`, expected: 'DESIGN §6.11: statusAt PENDING; canApprove/canReject/canWithdraw false; blockedReason "Read-only in the as-of view"; live status APPROVED' }, async () => {
    const t = await rt.approvals.get(A, P.A.id, { asOf: T });
    const l = await rt.approvals.get(A, P.A.id);
    const j = t.json || {};
    const ok = t.status === 200 && j.statusAt === 'PENDING' && j.canApprove === false && j.canReject === false && j.canWithdraw === false && j.blockedReason === 'Read-only in the as-of view' && l.json.status === 'APPROVED';
    return { ok, actual: `asOf ${t.status} status=${j.status} statusAt=${j.statusAt} canApprove=${j.canApprove} canReject=${j.canReject} canWithdraw=${j.canWithdraw} blocked=${j.blockedReason}; live=${l.json.status}` };
  });
  await check({ id: 'ASO-API-35', feature: 'Approvals as of', prd: 'B3', title: 'A change requested after T is 404 by id as of T',
    steps: `GET /api/approvals/{pcC}?asOf=${T}`, expected: 'DESIGN D8/D15: requested_at > T -> not outstanding/visible then -> 404' }, async () => {
    const t = await rt.approvals.get(A, P.C.id, { asOf: T });
    const l = await rt.approvals.get(A, P.C.id);
    return { ok: t.status === 404 && l.status === 200, actual: `asOf=${t.status} live=${l.status}` };
  });
  await check({ id: 'ASO-API-36', feature: 'Approvals read only', prd: 'B3', title: 'Approve, reject and withdraw are refused as of a date (400 AS_OF_READ_ONLY) and the change stays PENDING',
    steps: `admin POST /api/approvals/{pcB}/approve?asOf=${T}; /reject; sam /withdraw; GET /api/approvals/{pcB}`, expected: 'DESIGN §5.11.10: refused 400; status unchanged' }, async () => {
    const a = await rt.api('POST', `/api/approvals/${P.B.id}/approve`, { token: A, query: { asOf: T }, body: {} });
    const r = await rt.api('POST', `/api/approvals/${P.B.id}/reject`, { token: A, query: { asOf: T }, body: { note: 'no' } });
    const w = await rt.api('POST', `/api/approvals/${P.B.id}/withdraw`, { token: S.tokens.sam, query: { asOf: T }, body: {} });
    const g = await rt.approvals.get(A, P.B.id);
    const ok = [a, r, w].every((x) => x.status === 400 && x.json.error === 'AS_OF_READ_ONLY') && g.json.status === 'PENDING';
    return { ok, actual: `approve=${err(a)}; reject=${err(r)}; withdraw=${err(w)}; now=${g.json.status}` };
  });
  await check({ id: 'ASO-API-37', feature: 'Approvals as of', prd: 'B3', title: 'The approvals list as of T honours regionIds (SOUTH: A,B,D,E,G,H; NORTH: none)',
    steps: `GET /api/approvals?asOf=${T}&regionIds=${R.SOUTH.id}; &regionIds=${R.NORTH.id}`, expected: 'PRD B1 + B3' }, async () => {
    const s = await rt.approvals.list(A, {}, { asOf: T, regionIds: [R.SOUTH.id] });
    const n = await rt.approvals.list(A, {}, { asOf: T, regionIds: [R.NORTH.id] });
    return { ok: eqSet(ids(s.json.content), THEN_PENDING) && n.json.content.length === 0, actual: `SOUTH=${ids(s.json.content)} NORTH=${ids(n.json.content)}` };
  });
  await check({ id: 'ASO-API-38', feature: 'Approvals export as of', prd: 'B3', title: 'The approvals export as of T holds the changes outstanding then',
    steps: `POST /api/approvals/export?asOf=${T} {action:"EXPORT", selectAllMatchingFilter:true}`, expected: `DESIGN §6.1: approvals export AWARE; ${THEN_PENDING.length} data rows (A,B,D,E), header X-View-As-Of=T` }, async () => {
    const r = await rt.api('POST', '/api/approvals/export', { token: A, query: { asOf: T }, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const lines = (r.text || '').trim().split('\n');
    return { ok: r.status === 200 && r.headers['x-view-as-of'] === T && lines.length === THEN_PENDING.length + 1, actual: `${r.status} header=${r.headers['x-view-as-of']} lines=${lines.length}: ${short(lines.join(' | '), 500)}` };
  });

  // ---------------------------------------------------------------- round 2: decisions taken after T are not shown as of T (ASO-R1-2)
  const decisionOf = (j) => ({ status: j.status, statusAt: j.statusAt, decidedBy: j.decidedBy, decidedAt: j.decidedAt, decisionNote: j.decisionNote, failureReason: j.failureReason, supersededById: j.supersededById, result: j.result });
  await check({ id: 'ASO-API-39', feature: 'Approvals as of: decision hidden', prd: 'B3', severity: 'low',
    title: 'ASO-R1-2 retest: an approval by id as of T that was decided after T carries no decider, decision date, note or result (approved A and D, rejected E); live they are there',
    steps: `GET /api/approvals/{pcA|pcD|pcE}?asOf=${T} and live (A, D approved by sam today; E rejected by sam today with note "${P.E && P.E.note}")`,
    expected: 'PRD B3 "which approvals were outstanding" + FIXLOG ASO-R1-2: statusAt PENDING; decidedBy, decidedAt, decisionNote, failureReason, supersededById, result null as of T; live: decidedBy Sam South, decidedAt set, note, and (approved) result link',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:109-137 (hiddenDecision)' }, async () => {
    const out = []; let ok = true;
    for (const k of ['A', 'D', 'E']) {
      if (!P[k]) continue;
      const t = await rt.approvals.get(A, P[k].id, { asOf: T });
      const l = await rt.approvals.get(A, P[k].id);
      const tj = t.json || {}; const lj = l.json || {};
      const hidden = t.status === 200 && tj.statusAt === 'PENDING' && tj.decidedBy == null && tj.decidedAt == null && tj.decisionNote == null && tj.failureReason == null && tj.supersededById == null && tj.result == null;
      const liveShown = l.status === 200 && lj.decidedBy && lj.decidedBy.id === S.users.sam.id && !!lj.decidedAt && (k === 'E' ? lj.status === 'REJECTED' && lj.decisionNote === P.E.note && lj.result == null : lj.status === 'APPROVED' && lj.decisionNote === 'ok' && lj.result && lj.result.link);
      ok = ok && hidden && !!liveShown;
      out.push(`${k}#${P[k].id}: asOf ${t.status} ${short(decisionOf(tj), 260)} | live ${short(decisionOf(lj), 260)}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });
  await check({ id: 'ASO-API-39b', feature: 'Approvals as of: decision hidden', prd: 'B3', severity: 'low',
    title: 'The approvals list, for-record rows and CSV export as of T carry no decision date for changes decided after T',
    steps: `GET /api/approvals?asOf=${T}; GET /api/approvals/for-record?entityType=INVOICE&entityId={X}&asOf=${T}; POST /api/approvals/export?asOf=${T} (column "Decided"); live list`,
    expected: 'Brief.decidedAt null at T for A, D, E (FIXLOG ASO-R1-2: "Brief nulls decidedAt"); the CSV "Decided" column empty and Status PENDING for them; live list has decidedAt for A, D, E' }, async () => {
    const t = await rt.approvals.list(A, {}, { asOf: T });
    const l = await rt.approvals.list(A, {});
    const fr = await rt.approvals.forRecord(A, 'INVOICE', S.invoiceX.id, { asOf: T });
    const x = await rt.api('POST', '/api/approvals/export', { token: A, query: { asOf: T }, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const decided = ['A', 'D', 'E'].filter((k) => P[k]).map((k) => P[k].id);
    const rowsT = (t.json && t.json.content) || [];
    const listLeak = rowsT.filter((r) => decided.includes(r.id) && r.decidedAt != null).map((r) => `${r.id}:${r.decidedAt}`);
    const frLeak = (Array.isArray(fr.json) ? fr.json : []).filter((r) => r.decidedAt != null).map((r) => `${r.id}:${r.decidedAt}`);
    const rows = parseCsv(x.text);
    const head = rows[0] || [];
    const iDec = head.findIndex((h) => /Decided/.test(h)); const iSt = head.findIndex((h) => h === 'Status');
    const csvLeak = rows.slice(1).filter((c) => decided.includes(Number(c[0])) && ((c[iDec] || '') !== '' || c[iSt] !== 'PENDING')).map((c) => c.join('|'));
    const liveHas = ((l.json && l.json.content) || []).filter((r) => decided.includes(r.id) && r.decidedAt != null).length === decided.length;
    const ok = t.status === 200 && listLeak.length === 0 && frLeak.length === 0 && x.status === 200 && iDec >= 0 && csvLeak.length === 0 && liveHas;
    return { ok, actual: `list asOf leaks=${short(listLeak)}; for-record X asOf ${fr.status} leaks=${short(frLeak)}; export ${x.status} header=${short(head)} leaking rows=${short(csvLeak, 400)}; live list decidedAt present for A,D,E=${liveHas}` };
  });
  await check({ id: 'ASO-API-39c', feature: 'Approvals as of: earlier decision kept', prd: 'B3',
    title: 'A change decided BEFORE T keeps its decision as of T (statusAt APPROVED, decider, date, note, result link); it is not listed among the outstanding',
    steps: `pcF: held 12000 invoice on s1, approved by sam before ageing. GET /api/approvals/{pcF}?asOf=${T}; GET /api/approvals?asOf=${T}`,
    expected: 'DESIGN D8/§5.11.10: by id visible (requested before T), statusAt APPROVED, decidedBy Sam South, decidedAt set (before T), decisionNote, result link to the invoice (which existed at T); not in the outstanding list' }, async () => {
    if (!P.F) return { ok: false, actual: 'scenario has no pcF' };
    const t = await rt.approvals.get(A, P.F.id, { asOf: T });
    const lst = await rt.approvals.list(A, {}, { asOf: T });
    const j = t.json || {};
    const inv = j.result ? await get(`/api/invoices/${j.result.entityId}`, { asOf: T }) : { status: 0 };
    const ok = t.status === 200 && j.statusAt === 'APPROVED' && j.decidedBy && j.decidedBy.id === S.users.sam.id && j.decidedAt && j.decidedAt.slice(0, 10) <= T
      && j.decisionNote === P.F.note && j.result && j.result.link && inv.status === 200 && !ids(lst.json && lst.json.content).includes(P.F.id);
    return { ok, actual: `asOf ${t.status} ${short(decisionOf(j), 400)}; result invoice asOf=${inv.status}; in outstanding list=${ids(lst.json && lst.json.content).includes(P.F.id)}` };
  });

  // ---------------------------------------------------------------- round 3: the Changes table as of T (near APP-R2-1)
  await check({ id: 'ASO-API-39d', feature: 'Approvals as of: readable changes', prd: 'B3', severity: 'low',
    title: 'An approval viewed as of T reads its Changes in words (invoice numbers, invoice lines) and shows no "record now" (current) values',
    steps: `GET /api/approvals/{pcE}?asOf=${T} (held payment 7000 naming s1's invoice ${P.E && P.E.invoiceNumber}); GET /api/approvals/{pcA}?asOf=${T} (held invoice create, items); GET /api/approvals/{pcD}?asOf=${T} (cancel of X, which is CANCELLED today); live pcD`,
    expected: 'FIXLOG r2 B2 / TRIAGE r2 APP-R2-1: invoiceIds row labelled "Invoices", proposedText = the invoice number; no empty promiseIds row on a create; items read "<qty> × <product> at <price>"; FIXLOG: "as-of (current is null)" — top-level current and every row\'s current null as of T (the record today is not history, PRD B3)',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:120-127,194-257' }, async () => {
    const out = []; let ok = true;
    const e = await rt.approvals.get(A, P.E.id, { asOf: T });
    const ej = e.json || {}; const ech = ej.changes || [];
    const inv = ech.find((c) => c.field === 'invoiceIds');
    const pro = ech.find((c) => c.field === 'promiseIds');
    const eOk = e.status === 200 && inv && inv.label === 'Invoices' && inv.proposedText === P.E.invoiceNumber && !pro && ej.current == null && ech.every((c) => c.current == null);
    ok = ok && !!eOk;
    out.push(`E asOf ${e.status}: invoiceIds=${short(inv, 250)} promiseIds=${short(pro || null, 120)} current=${short(ej.current, 80)}`);
    const a = await rt.approvals.get(A, P.A.id, { asOf: T });
    const aj = a.json || {}; const ach = aj.changes || [];
    const items = ach.find((c) => c.field === 'items');
    const aOk = a.status === 200 && items && /^\d+(\.\d+)? × .+ at /.test(items.proposedText || '') && !/productId/.test(items.proposedText || '') && aj.current == null && ach.every((c) => c.current == null);
    ok = ok && !!aOk;
    out.push(`A asOf ${a.status}: items=${short(items, 250)} current=${short(aj.current, 80)}`);
    const d = await rt.approvals.get(A, P.D.id, { asOf: T });
    const dl = await rt.approvals.get(A, P.D.id);
    const dj = d.json || {};
    const dOk = d.status === 200 && dj.current == null && (dj.changes || []).every((c) => c.current == null && c.currentText == null);
    ok = ok && dOk;
    out.push(`D asOf ${d.status}: current=${short(dj.current, 120)} rows=${short((dj.changes || []).map((c) => ({ f: c.field, cur: c.current, curT: c.currentText, prop: c.proposedText })), 250)}; live current=${short(dl.json && dl.json.current, 160)}`);
    return { ok, actual: out.join(' ;; ') };
  });

  // ---------------------------------------------------------------- round 4: ASO-R3-1 retest and one step around it
  const roOk = (j) => j.canApprove === false && j.canReject === false && j.canWithdraw === false && j.blockedReason === 'Read-only in the as-of view' && j.current == null && (j.changes || []).every((c) => c.current == null && c.currentText == null);
  await check({ id: 'ASO-API-R3-1', feature: 'ASO-R3-1 retest', prd: 'B3', severity: 'high',
    title: 'ASO-R3-1 retest: every change requested before T opens by id as of T (200, no AS_OF_UNSUPPORTED), including held invoice creates and payments/promises naming invoices or promises',
    steps: `GET /api/approvals/{id}?asOf=${T} for pcA (invoice create 20000), pcB (payment), pcD (cancel), pcE (payment naming an invoice), pcF (create, approved before T), pcG (payment naming a promise), pcH (promise naming an invoice)`,
    expected: 'TRIAGE r3 B1 / userDecision ASO-R3-1: never refuses the page; 200 with statusAt PENDING (APPROVED for F), read only, current null',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java (names helper)' }, async () => {
    const out = []; let ok = true;
    for (const k of ['A', 'B', 'D', 'E', 'F', 'G', 'H']) {
      if (!P[k]) continue;
      const t = await rt.approvals.get(A, P[k].id, { asOf: T });
      const j = t.json || {};
      const good = t.status === 200 && j.statusAt === (k === 'F' ? 'APPROVED' : 'PENDING') && roOk(j);
      ok = ok && good;
      out.push(`${k}#${P[k].id}:${t.status}${t.status !== 200 ? ` ${err(t)}` : ` statusAt=${j.statusAt} blocked=${j.blockedReason} current=${short(j.current, 40)}`}${good ? '' : ' <-- BAD'}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });
  await check({ id: 'ASO-API-39e', feature: 'Approvals as of: readable changes', prd: 'B3',
    title: 'Round 4 (near ASO-R3-1): as of T a held payment names its promise ("Promise of ₹1,000.00 by …") and a held promise names its invoice; the withdrawal after T is hidden; live both read WITHDRAWN with the same names',
    steps: `pcG: admin's held payment 5500 on s1 with promiseIds [P1 (1000)]; pcH: admin's held promise 6000 on s1 with invoiceIds [${P.H && P.H.invoiceNumber}]; both withdrawn by admin after T. GET /api/approvals/{pcG|pcH}?asOf=${T} and live`,
    expected: 'TRIAGE r2 APP-R2-1 + r3 B1: row "Promises" proposedText "Promise of ₹1,000.00 by <date>" (no invoiceIds row); row "Invoices" proposedText = the invoice number; as of T statusAt PENDING, decidedAt/decidedBy null; live status WITHDRAWN' }, async () => {
    if (!P.G) return { ok: false, actual: 'scenario has no pcG/pcH' };
    const g = await rt.approvals.get(A, P.G.id, { asOf: T }); const gl = await rt.approvals.get(A, P.G.id);
    const h = await rt.approvals.get(A, P.H.id, { asOf: T }); const hl = await rt.approvals.get(A, P.H.id);
    const row = (r, f) => ((r.json && r.json.changes) || []).find((c) => c.field === f);
    const gp = row(g, 'promiseIds'); const gpl = row(gl, 'promiseIds'); const hi = row(h, 'invoiceIds'); const hil = row(hl, 'invoiceIds');
    const proRe = /^Promise of ₹1,000\.00 by /;
    const gOk = g.status === 200 && gp && gp.label === 'Promises' && proRe.test(gp.proposedText || '') && !row(g, 'invoiceIds') && g.json.statusAt === 'PENDING' && g.json.decidedAt == null && g.json.decidedBy == null
      && gl.status === 200 && gl.json.status === 'WITHDRAWN' && gpl && gpl.proposedText === gp.proposedText;
    const hOk = h.status === 200 && hi && hi.label === 'Invoices' && hi.proposedText === P.H.invoiceNumber && h.json.statusAt === 'PENDING' && h.json.decidedAt == null
      && hl.status === 200 && hl.json.status === 'WITHDRAWN' && hil && hil.proposedText === P.H.invoiceNumber;
    return { ok: !!(gOk && hOk), actual: `G asOf ${g.status}${g.status !== 200 ? ` ${err(g)}` : ''} promiseIds=${short(gp, 200)} invoiceIds=${short(row(g, 'invoiceIds') || null, 80)} statusAt=${g.json && g.json.statusAt} decidedAt=${g.json && g.json.decidedAt}; live ${gl.json && gl.json.status} ${short(gpl && gpl.proposedText, 80)} ;; H asOf ${h.status}${h.status !== 200 ? ` ${err(h)}` : ''} invoiceIds=${short(hi, 200)} statusAt=${h.json && h.json.statusAt}; live ${hl.json && hl.json.status} ${short(hil && hil.proposedText, 80)}` };
  });
  await check({ id: 'ASO-API-39f', feature: 'Approvals as of: readable changes', prd: 'B3',
    title: 'Round 4 (near ASO-R3-1): an invoice line in a held create names the product as it was called at T; live it uses today\'s name',
    steps: `pcA items on product "${S.product && S.product.nameThen}"; after T the product is renamed "${S.product && S.product.nameNow}". GET /api/approvals/{pcA}?asOf=${T}; live`,
    expected: 'TRIAGE r3 userDecision ASO-R3-1: "as of a date, the names in the Changes table (… product …) are read as those records stood on that date"; items "1 × <old name> at ₹20,000.00" as of T; "1 × <new name> at ₹20,000.00" live' }, async () => {
    if (!S.product || !S.product.nameNow) return { ok: false, actual: 'scenario has no product rename' };
    const t = await rt.approvals.get(A, P.A.id, { asOf: T }); const l = await rt.approvals.get(A, P.A.id);
    const it = ((t.json && t.json.changes) || []).find((c) => c.field === 'items'); const il = ((l.json && l.json.changes) || []).find((c) => c.field === 'items');
    const ok = t.status === 200 && it && it.proposedText === `1 × ${S.product.nameThen} at ₹20,000.00` && il && il.proposedText === `1 × ${S.product.nameNow} at ₹20,000.00`;
    return { ok, actual: `asOf ${t.status}${t.status !== 200 ? ` ${err(t)}` : ''} items=${short(it && it.proposedText, 120)}; live items=${short(il && il.proposedText, 120)}` };
  });
  await check({ id: 'ASO-API-39g', feature: 'Approvals as of: blocked reason', prd: 'B3',
    title: 'Round 4 (near RGN-R3-1 blockedReason change): as of T every caller gets "Read-only in the as-of view"; live the maker, a READ-only viewer and an approver get their own reasons',
    steps: `pcB (sam's held payment, pending). GET /api/approvals/{pcB} as sam, nora (READ South), admin — as of ${T} and live`,
    expected: 'TRIAGE r3 userDecision RGN-R3-1 precedence: as of → "Read-only in the as-of view" (all false); live sam → "You requested this change"; nora → "You don\'t hold the approval right in <South>"; admin → null, canApprove/canReject true' }, async () => {
    const out = []; let ok = true;
    for (const [who, tk] of [['sam', S.tokens.sam], ['nora', S.tokens.nora], ['admin', A]]) {
      const t = await rt.approvals.get(tk, P.B.id, { asOf: T }); const l = await rt.approvals.get(tk, P.B.id);
      const tj = t.json || {}; const lj = l.json || {};
      const tOk = t.status === 200 && tj.blockedReason === 'Read-only in the as-of view' && !tj.canApprove && !tj.canReject && !tj.canWithdraw;
      const lOk = l.status === 200 && (who === 'sam' ? lj.blockedReason === 'You requested this change' && lj.canWithdraw === true && !lj.canApprove
        : who === 'nora' ? /^You don't hold the approval right in /.test(lj.blockedReason || '') && !lj.canApprove && !lj.canReject
          : lj.blockedReason == null && lj.canApprove === true && lj.canReject === true);
      ok = ok && tOk && lOk;
      out.push(`${who}: asOf ${t.status} ${short({ b: tj.blockedReason, a: tj.canApprove, r: tj.canReject, w: tj.canWithdraw })} | live ${l.status} ${short({ b: lj.blockedReason, a: lj.canApprove, r: lj.canReject, w: lj.canWithdraw })}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });

  // ---------------------------------------------------------------- tasks
  await check({ id: 'ASO-API-40', feature: 'Tasks as of', prd: 'B3', title: 'The task list as of T shows T1 open and T3; T2 (created after) only live; T1 DONE live',
    steps: `GET /api/tasks?asOf=${T} vs live`, expected: 'PRD A6+B3; DESIGN D8: tasks versioned' }, async () => {
    const t = await rt.list(A, '/api/tasks', { asOf: T });
    const l = await rt.list(A, '/api/tasks');
    const f = (r, id) => (r.json.content || []).find((x) => x.id === id);
    const ok = t.status === 200 && t.json.asOf === T && f(t, S.tasks.T1.id) && f(t, S.tasks.T1.id).status === 'OPEN' && f(t, S.tasks.T3.id) && !f(t, S.tasks.T2.id)
      && f(l, S.tasks.T1.id).status === 'DONE' && f(l, S.tasks.T2.id);
    return { ok, actual: `asOf ${t.status} T1=${f(t, S.tasks.T1.id) && f(t, S.tasks.T1.id).status} T3=${!!f(t, S.tasks.T3.id)} T2=${!!f(t, S.tasks.T2.id)}; live T1=${f(l, S.tasks.T1.id) && f(l, S.tasks.T1.id).status} T2=${!!f(l, S.tasks.T2.id)}` };
  });
  await check({ id: 'ASO-API-41', feature: 'Tasks as of', prd: 'B3', title: 'A task by id as of T is OPEN with no write flags and no version',
    steps: `GET /api/tasks/{T1}?asOf=${T}`, expected: 'DESIGN §6.12: historical -> version null, canEdit=false, canChangeStatus=false' }, async () => {
    const t = await get(`/api/tasks/${S.tasks.T1.id}`, { asOf: T });
    const j = t.json || {};
    return { ok: t.status === 200 && j.status === 'OPEN' && j.version == null && j.canEdit === false && j.canChangeStatus === false, actual: `${t.status} status=${j.status} version=${j.version} canEdit=${j.canEdit} canChangeStatus=${j.canChangeStatus}` };
  });
  await check({ id: 'ASO-API-42', feature: 'My tasks as of', prd: 'B3', title: 'nora\'s My tasks as of T lists T1 and T3 (both open, n2 was North); live T3 is gone (n2 in West) and T2 appears',
    steps: `nora GET /api/tasks/mine?asOf=${T}, /api/tasks/mine/count?asOf=${T}; live`, expected: 'PRD A6 "My tasks" + B3; count at T = 2' }, async () => {
    const t = await rt.list(S.tokens.nora, '/api/tasks/mine', { asOf: T });
    const l = await rt.list(S.tokens.nora, '/api/tasks/mine');
    const ct = await get('/api/tasks/mine/count', { asOf: T }, S.tokens.nora);
    const cl = await get('/api/tasks/mine/count', {}, S.tokens.nora);
    const ti = ids(t.json.content); const li = ids(l.json.content);
    const ok = ti.includes(S.tasks.T1.id) && ti.includes(S.tasks.T3.id) && !ti.includes(S.tasks.T2.id) && !li.includes(S.tasks.T3.id) && li.includes(S.tasks.T2.id) && ct.json.count === 2 && ct.headers['x-view-as-of'] === T;
    return { ok, actual: `asOf ids=${ti} count=${ct.status}:${short(ct.json)} header=${ct.headers['x-view-as-of']}; live ids=${li} count=${short(cl.json)}` };
  });
  await check({ id: 'ASO-API-43', feature: 'Tasks as of', prd: 'B3', title: 'Task summary tiles as of T count the tasks of then, with "overdue" measured from T',
    steps: `GET /api/tasks/summary?asOf=${T}; GET /api/tasks?asOf=${T} (all rows)`, expected: 'count/open/inProgress = then snapshot; overdue = open tasks whose due date (at T) is before T (DESIGN D9: "today" in as-of mode is T). Note: ageWorld moved due dates back 10 days, so tasks due +2 then are due -8 now = 6 days before T' }, async () => {
    const t = await get('/api/tasks/summary', { asOf: T });
    const rows = (await rt.list(A, '/api/tasks', { asOf: T })).json.content;
    const expOverdue = rows.filter((x) => ['OPEN', 'IN_PROGRESS'].includes(x.status) && x.dueDate && x.dueDate < T).length;
    const flagsOk = rows.every((x) => x.overdue === (['OPEN', 'IN_PROGRESS'].includes(x.status) && !!x.dueDate && x.dueDate < T));
    const th = S.then.tasksSummary;
    const ok = t.status === 200 && t.json.count === th.count && t.json.open === th.open && t.json.inProgress === th.inProgress && t.json.overdue === expOverdue && flagsOk;
    return { ok, actual: `asOf=${short(t.json)} then=${short(th)} expectedOverdue=${expOverdue} rowOverdueFlagsConsistent=${flagsOk}` };
  });
  await check({ id: 'ASO-API-44', feature: 'Tasks tab as of', prd: 'B3', title: 'The Tasks tab of an invoice as of T lists only the tasks that existed then',
    steps: `GET /api/tasks?entityType=INVOICE&entityId={n1 inv1}&asOf=${T}; GET /api/tasks/count?...&asOf=${T}; live`, expected: 'T1 only at T (count 1, open 1); T1+T2 live' }, async () => {
    const t = await rt.list(A, '/api/tasks', { entityType: 'INVOICE', entityId: I.n1[0].id, asOf: T });
    const l = await rt.list(A, '/api/tasks', { entityType: 'INVOICE', entityId: I.n1[0].id });
    const c = await get('/api/tasks/count', { entityType: 'INVOICE', entityId: I.n1[0].id, asOf: T });
    const ok = eqSet(ids(t.json.content), [S.tasks.T1.id]) && eqSet(ids(l.json.content), [S.tasks.T1.id, S.tasks.T2.id]) && c.json.count === 1 && c.json.open === 1;
    return { ok, actual: `asOf=${ids(t.json.content)} count=${short(c.json)}; live=${ids(l.json.content)}` };
  });

  // ---------------------------------------------------------------- record tabs
  await check({ id: 'ASO-API-50', feature: 'History tab as of', prd: 'B3', title: 'An invoice\'s History (audit) as of T has the entries of then only; the later due-date change is live only',
    steps: `GET /api/audit?entityType=INVOICE&entityId={n1 inv1}&asOf=${T} vs live`, expected: `DESIGN §6.15: createdAt <= T; count = snapshot (${S.then.auditN1Inv})` }, async () => {
    const t = await get('/api/audit', { entityType: 'INVOICE', entityId: I.n1[0].id, asOf: T });
    const l = await get('/api/audit', { entityType: 'INVOICE', entityId: I.n1[0].id });
    const ok = t.status === 200 && t.json.length === S.then.auditN1Inv && l.json.length > t.json.length;
    return { ok, actual: `asOf ${t.status} n=${t.json && t.json.length} [${(t.json || []).map((e) => e.action)}]; live n=${l.json.length} [${l.json.map((e) => e.action)}]` };
  });
  await check({ id: 'ASO-API-51', feature: 'Documents tab as of', prd: 'B3', title: 'The Documents tab as of T shows the document then present (deleted later) and not the one uploaded later',
    steps: `GET /api/documents?entityType=CUSTOMER&entityId={n1}&asOf=${T}; /api/documents/count?...; live`, expected: 'DESIGN §5.7.17: uploadedAt <= T and not deleted at T; D1 at T, D2 live' }, async () => {
    const t = await get('/api/documents', { entityType: 'CUSTOMER', entityId: C.n1.id, asOf: T });
    const l = await get('/api/documents', { entityType: 'CUSTOMER', entityId: C.n1.id });
    const c = await get('/api/documents/count', { entityType: 'CUSTOMER', entityId: C.n1.id, asOf: T });
    const ti = ids(t.json && t.json.content); const li = ids(l.json && l.json.content);
    const flagsOff = (t.json && t.json.content || []).every((d) => d.canEdit === false && d.canDelete === false);
    const ok = t.status === 200 && eqSet(ti, [S.docs.D1]) && eqSet(li, [S.docs.D2]) && flagsOff && c.status === 200;
    return { ok, actual: `asOf=${ti} flagsOff=${flagsOff} count=${c.status}:${short(c.json)}; live=${li}` };
  });
  await check({ id: 'ASO-API-52', feature: 'POC seats as of', prd: 'B3', title: 'The customer\'s POC seats as of T exclude the seat added after T',
    steps: `GET /api/customers/{n1}/pocs?asOf=${T}; live`, expected: 'DESIGN §5.7.17 PocService.listFor as-of; the POC book is per region and is versioned (D8)' }, async () => {
    const t = await get(`/api/customers/${C.n1.id}/pocs`, { asOf: T });
    const l = await get(`/api/customers/${C.n1.id}/pocs`);
    const types = (r) => (Array.isArray(r.json) ? r.json : (r.json && r.json.content) || []).map((p) => `${p.pocType}:${p.userId}`);
    const ok = t.status === 200 && !types(t).some((x) => x.startsWith('SUCCESS')) && types(l).some((x) => x.startsWith('SUCCESS'));
    return { ok, actual: `asOf ${t.status} ${types(t)}; live ${types(l)} (seat add status ${S.seatNow.status})` };
  });
  await check({ id: 'ASO-API-53', feature: 'Promises/Disputes tabs as of', prd: 'B3', title: 'A customer\'s promises and disputes as of T exclude those raised later',
    steps: `GET /api/promises?filter=customerId:eq:{d1}&asOf=${T}; /api/disputes same; live`, expected: '0 at T; 1 live each' }, async () => {
    const pt = await rt.list(A, '/api/promises', { filter: `customerId:eq:${C.d1.id}`, asOf: T });
    const pl = await rt.list(A, '/api/promises', { filter: `customerId:eq:${C.d1.id}` });
    const dt = await rt.list(A, '/api/disputes', { filter: `customerId:eq:${C.d1.id}`, asOf: T });
    const dl = await rt.list(A, '/api/disputes', { filter: `customerId:eq:${C.d1.id}` });
    const ok = pt.json.totalElements === 0 && pl.json.totalElements === 1 && dt.json.totalElements === 0 && dl.json.totalElements === 1;
    return { ok, actual: `promises asOf=${pt.status}:${pt.json && pt.json.totalElements} live=${pl.json.totalElements}; disputes asOf=${dt.status}:${dt.json && dt.json.totalElements} live=${dl.json.totalElements}` };
  });
  await check({ id: 'ASO-API-54', feature: 'Emails tab as of', prd: 'B3', title: 'An invoice\'s Emails tab as of T shows the email sent before T, not the one sent after',
    steps: `GET /api/emails?entityType=INVOICE&entityId={n1 inv1}&asOf=${T}; live`, expected: 'DESIGN D8: emails occurred_at <= T' }, async () => {
    const t = await get('/api/emails', { entityType: 'INVOICE', entityId: I.n1[0].id, size: 50, asOf: T });
    const l = await get('/api/emails', { entityType: 'INVOICE', entityId: I.n1[0].id, size: 50 });
    const subj = (r) => ((r.json && r.json.content) || []).map((e) => e.subject);
    const ok = t.status === 200 && subj(t).includes(S.emailBefore.subject) && !subj(t).includes(S.emailAfter.subject) && subj(l).includes(S.emailAfter.subject);
    return { ok, actual: `asOf ${t.status} ${subj(t)}; live ${subj(l)} (send statuses ${S.emailBefore.status}/${S.emailAfter.status})` };
  });

  // ---------------------------------------------------------------- exports
  const exp = (p, q = {}) => rt.api('POST', p, { token: A, query: q, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
  await check({ id: 'ASO-API-60', feature: 'Invoice export as of', prd: 'B3', title: 'The invoice CSV export as of T holds the then rows and values (X UNPAID, old customer name, no later invoices)',
    steps: `POST /api/invoices/export?asOf=${T} {selectAllMatchingFilter:true}; live`, expected: 'PRD B3 "every ... report"; DESIGN §5.2.3 export AWARE' }, async () => {
    const t = await exp('/api/invoices/export', { asOf: T });
    const l = await exp('/api/invoices/export');
    const line = (txt, num) => (txt || '').split('\n').find((x) => x.includes(num)) || '';
    const later = [S.invoiceN.number, S.invoiceS1Small.number];
    const ok = t.status === 200 && t.headers['x-view-as-of'] === T && /UNPAID/.test(line(t.text, S.invoiceX.number)) && /CANCELLED/.test(line(l.text, S.invoiceX.number))
      && later.every((n) => !t.text.includes(n) && l.text.includes(n)) && t.text.includes(C.n1.name) && !t.text.includes(C.n1.newName)
      && t.text.trim().split('\n').length === S.then.invoiceCount + 1;
    return { ok, actual: `${t.status} header=${t.headers['x-view-as-of']} lines=${(t.text || '').trim().split('\n').length} X=${short(line(t.text, S.invoiceX.number), 160)} | live X=${short(line(l.text, S.invoiceX.number), 160)}` };
  });
  await check({ id: 'ASO-API-61', feature: 'Customer export as of', prd: 'B3', title: 'The customer CSV export as of T uses the names of then',
    steps: `POST /api/customers/export?asOf=${T}`, expected: 'old n1 name present, new name absent; live the reverse' }, async () => {
    const t = await exp('/api/customers/export', { asOf: T });
    const l = await exp('/api/customers/export');
    const ok = t.status === 200 && t.text.includes(C.n1.name) && !t.text.includes(C.n1.newName) && l.text.includes(C.n1.newName);
    return { ok, actual: `${t.status} hasOld=${t.text.includes(C.n1.name)} hasNew=${t.text.includes(C.n1.newName)}; live hasNew=${l.text.includes(C.n1.newName)}` };
  });
  await check({ id: 'ASO-API-62', feature: 'Payment/task export as of', prd: 'B3', title: 'Payment and task CSV exports as of T hold only rows that existed then',
    steps: `POST /api/payments/export?asOf=${T}; POST /api/tasks/export?asOf=${T}`, expected: `payments: ${S.then.paymentIds.length} rows; tasks: no T2 title` }, async () => {
    const p = await exp('/api/payments/export', { asOf: T });
    const pl = await exp('/api/payments/export');
    const k = await exp('/api/tasks/export', { asOf: T });
    const kl = await exp('/api/tasks/export');
    const ok = p.status === 200 && p.text.trim().split('\n').length === S.then.paymentIds.length + 1 && pl.text.trim().split('\n').length === S.then.paymentIds.length + 2
      && k.status === 200 && k.text.includes(S.tasks.T1.title) && !k.text.includes(S.tasks.T2.title) && kl.text.includes(S.tasks.T2.title);
    return { ok, actual: `payments asOf ${p.status} lines=${(p.text || '').trim().split('\n').length} live=${(pl.text || '').trim().split('\n').length}; tasks asOf ${k.status} hasT2=${(k.text || '').includes(S.tasks.T2.title)} liveHasT2=${(kl.text || '').includes(S.tasks.T2.title)}` };
  });

  // ---------------------------------------------------------------- dashboard
  const dsum = (r, k = 'amount') => sum((r.json.months || r.json.buckets || []).map((m) => m[k]));
  await check({ id: 'ASO-API-70', feature: 'Dashboard as of', prd: 'B3', title: 'Billed-by-month as of T totals what was billed then; live totals differ',
    steps: `GET /api/dashboard/billed-by-month?asOf=${T}; live`, expected: `sum = then sum (${dsum({ json: S.then.dash.billed })}); scope.asOf = T` }, async () => {
    const t = await get('/api/dashboard/billed-by-month', { asOf: T });
    const l = await get('/api/dashboard/billed-by-month');
    const ok = t.status === 200 && dsum(t) === dsum({ json: S.then.dash.billed }) && dsum(t, 'count') === dsum({ json: S.then.dash.billed }, 'count') && t.json.scope.asOf === T && dsum(l) !== dsum(t);
    return { ok, actual: `asOf sum=${dsum(t)} count=${dsum(t, 'count')} scope.asOf=${t.json.scope && t.json.scope.asOf}; then=${dsum({ json: S.then.dash.billed })}; live=${dsum(l)}` };
  });
  await check({ id: 'ASO-API-71', feature: 'Dashboard as of', prd: 'B3', title: 'Outstanding-by-age as of T totals the outstanding of then, bucketed from T',
    steps: `GET /api/dashboard/outstanding-by-age?asOf=${T}`, expected: 'sum and count equal the then snapshot; the "Not yet due" bucket starts at T (dueDateFrom = T)' }, async () => {
    const t = await get('/api/dashboard/outstanding-by-age', { asOf: T });
    const l = await get('/api/dashboard/outstanding-by-age');
    const first = t.json.buckets && t.json.buckets[0];
    const ok = t.status === 200 && dsum(t) === dsum({ json: S.then.dash.age }) && dsum(t, 'count') === dsum({ json: S.then.dash.age }, 'count') && first.dueDateFrom === T && dsum(l) !== dsum(t);
    return { ok, actual: `asOf sum=${dsum(t)} count=${dsum(t, 'count')} firstBucket.dueDateFrom=${first && first.dueDateFrom}; then=${dsum({ json: S.then.dash.age })}; live=${dsum(l)}` };
  });
  await check({ id: 'ASO-API-72', feature: 'Dashboard as of', prd: 'B3', title: 'Top outstanding customers as of T have the balances and names of then',
    steps: `GET /api/dashboard/top-outstanding-customers?limit=10&asOf=${T}`, expected: 'same customers/outstanding/names as the then snapshot (n1 under its old name)' }, async () => {
    const t = await get('/api/dashboard/top-outstanding-customers', { limit: 10, asOf: T });
    const m = (r) => (r.customers || []).map((c) => `${c.customerId}:${c.customerName}:${Number(c.outstanding)}`).sort();
    const ok = t.status === 200 && JSON.stringify(m(t.json)) === JSON.stringify(m(S.then.dash.top));
    return { ok, actual: `asOf=${m(t.json)} | then=${m(S.then.dash.top)}` };
  });
  await check({ id: 'ASO-API-73', feature: 'Dashboard as of', prd: 'B3', title: 'Collected-by-month as of T excludes the payment recorded after T',
    steps: `GET /api/dashboard/collected-by-month?asOf=${T}; live`, expected: 'asOf sum = then sum; live = then + 1000' }, async () => {
    const t = await get('/api/dashboard/collected-by-month', { asOf: T });
    const l = await get('/api/dashboard/collected-by-month');
    const th = dsum({ json: S.then.dash.collected });
    return { ok: t.status === 200 && dsum(t) === th && dsum(l) === th + 1000, actual: `asOf=${dsum(t)} then=${th} live=${dsum(l)}` };
  });

  // ---------------------------------------------------------------- rules
  const RL = S.rules;
  await check({ id: 'ASO-API-80', feature: 'Rule as of', prd: 'B3', title: 'A rule by id as of T is the revision in force then (v1 name and condition), read only',
    steps: `GET /api/automation/rules/{R1}?asOf=${T}; live`, expected: 'DESIGN §5.10.10: the revision snapshot; flags false; live = v2' }, async () => {
    const t = await rt.automation.get(A, RL.R1.id, { asOf: T });
    const l = await rt.automation.get(A, RL.R1.id);
    const cond = (r) => JSON.stringify(r.json && r.json.condition);
    const ok = t.status === 200 && t.json.name === RL.R1.v1 && /6000/.test(cond(t)) && t.json.canEdit === false && t.json.canRun === false && l.json.name === RL.R1.v2 && /7000/.test(cond(l));
    return { ok, actual: `asOf ${t.status} name=${t.json && t.json.name} cond=${cond(t)} canEdit=${t.json && t.json.canEdit} canRun=${t.json && t.json.canRun}; live name=${l.json.name}` };
  });
  await check({ id: 'ASO-API-81', feature: 'Rules list as of', prd: 'B3', title: 'The rules list as of T shows R1 under its v1 name and not R2 (created later)',
    steps: `GET /api/automation/rules?asOf=${T}; live`, expected: 'DESIGN D9 rules list AWARE (revisions)' }, async () => {
    const t = await rt.automation.list(A, {}, { asOf: T });
    const l = await rt.automation.list(A);
    const f = (r, id) => (r.json.content || []).find((x) => x.id === id);
    const ok = t.status === 200 && t.json.asOf === T && f(t, RL.R1.id) && f(t, RL.R1.id).name === RL.R1.v1 && !f(t, RL.R2.id) && f(l, RL.R2.id) && f(l, RL.R1.id).name === RL.R1.v2;
    return { ok, actual: `asOf ${t.status} R1=${f(t, RL.R1.id) && f(t, RL.R1.id).name} R2=${!!f(t, RL.R2.id)}; live R1=${f(l, RL.R1.id) && f(l, RL.R1.id).name} R2=${!!f(l, RL.R2.id)}` };
  });
  const body = { rule: rt.rule({ name: 'preview', trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, condition: { op: 'AND', children: [{ filter: 'total:eq:6000' }, { filter: 'status:eq:UNPAID' }] }, actions: [rt.action.task({ title: 'P {{Invoice.Number}}', assignees: [rt.tok.user(S.adminId)] })] }), sampleSize: 10 };
  await check({ id: 'ASO-API-82', feature: 'Rule preview as of', prd: 'B3', title: 'Previewing a rule as of T evaluates the condition on the data of then (X matches, N does not) and omits action rendering',
    steps: `POST /api/automation/rules/preview?asOf=${T} {condition total=6000 AND UNPAID}; live`, expected: 'DESIGN D10/§6.14: asOf=T, sample includes X not N, record.actions null, asOfNote "Actions are shown only for today\'s view"; live sample N not X with actions' }, async () => {
    const t = await rt.automation.preview(A, body, { asOf: T });
    const l = await rt.automation.preview(A, body);
    const s = (r) => ((r.json && r.json.sample) || []).map((x) => x.id);
    const j = t.json || {};
    const ok = t.status === 200 && j.asOf === T && s(t).includes(S.invoiceX.id) && !s(t).includes(S.invoiceN.id) && j.matchCount === 1 && j.record && j.record.actions == null
      && j.asOfNote === "Actions are shown only for today's view" && s(l).includes(S.invoiceN.id) && !s(l).includes(S.invoiceX.id) && l.json.record && Array.isArray(l.json.record.actions);
    return { ok, actual: `asOf ${t.status} matchCount=${j.matchCount} sample=${s(t)} asOf=${j.asOf} note=${j.asOfNote} actions=${j.record && JSON.stringify(j.record.actions)}; live matchCount=${l.json && l.json.matchCount} sample=${s(l)}` };
  });
  await check({ id: 'ASO-API-83', feature: 'Rule preview as of', prd: 'B3', title: 'A saved rule\'s preview as of T evaluates the revision in force then (v1: 6000) on the data of then',
    steps: `GET /api/automation/rules/{R1}/preview?asOf=${T}; GET /api/automation/rules/{R1}/matches?asOf=${T}; live preview (v2: 7000)`, expected: 'DESIGN §5.10.9: revision at T -> X matches; live v2 -> 0 matches' }, async () => {
    const t = await rt.api('GET', `/api/automation/rules/${RL.R1.id}/preview`, { token: A, query: { asOf: T, sampleSize: 10 } });
    const m = await rt.api('GET', `/api/automation/rules/${RL.R1.id}/matches`, { token: A, query: { asOf: T, size: 10 } });
    const l = await rt.api('GET', `/api/automation/rules/${RL.R1.id}/preview`, { token: A, query: { sampleSize: 10 } });
    const s = (r) => ((r.json && r.json.sample) || []).map((x) => x.id);
    const ok = t.status === 200 && s(t).includes(S.invoiceX.id) && t.json.matchCount === 1 && m.status === 200 && ids(m.json.content).includes(S.invoiceX.id) && l.status === 200 && l.json.matchCount === 0;
    return { ok, actual: `asOf ${t.status} matchCount=${t.json && t.json.matchCount} sample=${s(t)}; matches ${m.status} ${ids(m.json && m.json.content)}; live matchCount=${l.json && l.json.matchCount}` };
  });
  await check({ id: 'ASO-API-84', feature: 'Rule as of', prd: 'B3', title: 'A rule created after T is 404 as of T (detail and preview)',
    steps: `GET /api/automation/rules/{R2}?asOf=${T}; GET /api/automation/rules/{R2}/preview?asOf=${T}`, expected: 'DESIGN §5.10.9: no revision at T -> 404' }, async () => {
    const d = await rt.automation.get(A, RL.R2.id, { asOf: T });
    const p = await rt.api('GET', `/api/automation/rules/${RL.R2.id}/preview`, { token: A, query: { asOf: T } });
    return { ok: d.status === 404 && p.status === 404, actual: `detail=${d.status} preview=${p.status}` };
  });
  await check({ id: 'ASO-API-85', feature: 'Rule runs as of', prd: 'B3', title: 'A rule\'s runs as of T list only the run made before T, with its statusAt',
    steps: `GET /api/automation/rules/{R1}/runs?asOf=${T}; live; GET /api/automation/runs/{after}?asOf=${T}`, expected: 'DESIGN §5.10.10: createdAt <= T, statusAt set; the later run is not listed and is 404 by id' }, async () => {
    const t = await rt.automation.runs(A, RL.R1.id, { asOf: T });
    const l = await rt.automation.runs(A, RL.R1.id);
    const one = await rt.api('GET', `/api/automation/runs/${S.runs.after}`, { token: A, query: { asOf: T } });
    const ti = ids(t.json && t.json.content);
    const ok = t.status === 200 && eqSet(ti, [S.runs.before]) && t.json.content[0].statusAt && eqSet(ids(l.json.content), [S.runs.before, S.runs.after]) && one.status === 404;
    return { ok, actual: `asOf ${t.status} ids=${ti} statusAt=${t.json && t.json.content && t.json.content[0] && t.json.content[0].statusAt}; live=${ids(l.json && l.json.content)}; later run by id asOf=${one.status}` };
  });

  // ---------------------------------------------------------------- read only
  const tag = S.tag;
  const writes = [
    ['POST', '/api/customers', { name: `RO ${tag}`, username: `ro-${tag}`, password: 'Passw0rd!', regionId: R.NORTH.id }],
    ['PUT', `/api/customers/${C.w2.id}`, { name: 'RO rename' }],
    ['DELETE', `/api/customers/${C.w2.id}`],
    ['POST', `/api/customers/${C.w2.id}/move-region`, { regionId: R.NORTH.id, version: 0, reason: 'ro' }],
    ['POST', `/api/customers/${C.w2.id}/pocs`, { pocType: 'SALES', userId: S.users.wendy.id, primary: false }],
    ['POST', '/api/customers/bulk', { action: 'DELETE', ids: [C.w2.id] }],
    ['POST', '/api/invoices', { customerId: C.d1.id, items: [{ productId: 1, quantity: 1, unitPrice: 10 }] }],
    ['PATCH', `/api/invoices/${I.w2[0].id}`, { notes: 'ro' }],
    ['POST', `/api/invoices/${I.w2[0].id}/cancel`, {}],
    ['POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [I.w2[0].id] }],
    ['POST', '/api/payments', { customerId: C.d1.id, amount: 5, method: 'BANK_TRANSFER' }],
    ['PATCH', `/api/payments/${S.paymentNow.id}`, { notes: 'ro' }],
    ['POST', '/api/promises', { customerId: C.d1.id, amount: 5, promisedDate: rt.day(5) }],
    ['PUT', `/api/promises/${S.promiseNow.id}`, { amount: 6, promisedDate: rt.day(6) }],
    ['POST', `/api/promises/${S.promiseNow.id}/cancel`, {}],
    ['POST', '/api/promises/recompute', {}],
    ['POST', '/api/disputes', { targetType: 'INVOICE', targetId: I.d1[0].id, reason: 'ro' }],
    ['POST', `/api/disputes/${S.disputeNow.id}/approve`, {}],
    ['POST', `/api/disputes/${S.disputeNow.id}/deny`, {}],
    ['PUT', `/api/disputes/${S.disputeNow.id}/assignees`, { assignees: [] }],
    ['POST', '/api/tasks', { entityType: 'INVOICE', entityId: I.d1[0].id, title: 'ro', dueDate: rt.day(3), assignees: [] }],
    ['PATCH', `/api/tasks/${S.tasks.T2.id}`, { title: 'ro', version: 0 }],
    ['POST', `/api/tasks/${S.tasks.T2.id}/status`, { status: 'DONE', version: 0 }],
    ['POST', '/api/tasks/bulk', { action: 'MARK_DONE', ids: [S.tasks.T2.id] }],
    ['POST', '/api/automation/rules', rt.rule({ name: `RO rule ${tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, actions: [rt.action.task({ assignees: [rt.tok.user(S.adminId)] })] })],
    ['PUT', `/api/automation/rules/${RL.R2.id}`, { name: 'ro' }],
    ['POST', `/api/automation/rules/${RL.R2.id}/disable`, {}],
    ['POST', `/api/automation/rules/${RL.R2.id}/enable`, {}],
    ['DELETE', `/api/automation/rules/${RL.R2.id}`],
    ['POST', `/api/automation/rules/${RL.R2.id}/run`, {}],
    ['POST', `/api/automation/runs/${S.runs.after}/retry`, {}],
    ['POST', `/api/approvals/${P.B.id}/approve`, {}],
    ['PUT', `/api/regions/${R.SOUTH.id}/approval-thresholds`, { thresholds: [{ operation: 'INVOICE_CREATE', amount: 1 }] }],
    ['POST', '/api/regions', { code: `RO${Date.now() % 100000}`, name: 'ro' }],
    ['PUT', `/api/regions/${R.WEST.id}`, { name: 'ro', active: true, version: 0 }],
    ['POST', `/api/regions/${R.WEST.id}/make-default`, {}],
    ['PUT', `/api/users/${S.users.wendy.id}/region-grants`, { grants: [] }],
    ['POST', '/api/users', { username: `ro-${tag}`, email: `ro-${tag}@e2e.local`, fullName: 'RO', password: 'Passw0rd!', roleId: 1, active: true, regionGrants: [] }],
    ['PUT', `/api/users/${S.users.wendy.id}`, { fullName: 'RO' }],
    ['POST', '/api/roles', { name: `RO_${tag}`, description: 'ro', privileges: [] }],
    ['POST', '/api/products', { name: `RO ${tag}`, price: 1, active: true }],
    ['POST', '/api/emails', { entityType: 'INVOICE', entityId: I.d1[0].id, to: [rt.tok.customer()], subject: 'ro', body: 'ro' }],
    ['PATCH', `/api/documents/${S.docs.D2}`, { description: 'ro' }],
    ['DELETE', `/api/documents/${S.docs.D2}`],
  ];
  const before = {
    customers: (await get('/api/customers', { size: 10 })).json.totalElements,
    invoices: (await get('/api/invoices', { size: 10 })).json.totalElements,
    tasks: (await get('/api/tasks', { size: 10 })).json.totalElements,
    rules: (await rt.automation.list(A)).json.totalElements,
    regions: (await get('/api/regions', { size: 50 })).json.totalElements,
    t2: (await get(`/api/tasks/${S.tasks.T2.id}`)).json.status,
    r2: (await rt.automation.get(A, RL.R2.id)).json.status,
    w2: (await get(`/api/customers/${C.w2.id}`)).json.name,
    wendyGrants: (await get(`/api/users/${S.users.wendy.id}/region-grants`)).json.length,
    d2: (await get('/api/documents', { entityType: 'CUSTOMER', entityId: C.n1.id })).json.totalElements,
  };
  const results = [];
  for (const [m, p, b] of writes) {
    const r = await rt.api(m, p, { token: A, query: { asOf: T }, body: b });
    results.push({ m, p, s: r.status, e: r.json && r.json.error, msg: r.json && r.json.message });
  }
  // Multipart upload
  {
    const fd = new FormData();
    fd.append('file', new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')], { type: 'image/png' }), 'ro.png');
    fd.append('entityType', 'CUSTOMER'); fd.append('entityId', String(C.n1.id));
    const r = await rt.api('POST', '/api/documents', { token: A, query: { asOf: T }, body: fd, raw: true });
    results.push({ m: 'POST', p: '/api/documents (multipart)', s: r.status, e: r.json && r.json.error, msg: r.json && r.json.message });
  }
  const bad = results.filter((x) => !(x.s === 400 && x.e === 'AS_OF_READ_ONLY'));
  const d = new Date(`${T}T00:00:00Z`);
  const label = `${d.getUTCDate()} ${d.toLocaleString('en-US', { month: 'short', timeZone: 'UTC' })} ${d.getUTCFullYear()}`;
  const msgOk = results.filter((x) => x.e === 'AS_OF_READ_ONLY').every((x) => x.msg === `You are viewing ${label}. Changes can only be made in the current view.`);
  await check({ id: 'ASO-API-90', feature: 'Read-only mode', prd: 'B3', title: `Every write sent with a past asOf is refused with 400 AS_OF_READ_ONLY (${results.length} endpoints)`,
    steps: `admin sends each write with ?asOf=${T}: ${results.map((x) => `${x.m} ${x.p}`).join('; ')}`,
    expected: 'DESIGN D2/§5.2.2/§6.17: 400 AS_OF_READ_ONLY "You are viewing <d MMM yyyy>. Changes can only be made in the current view." for every WRITE handler' }, async () => ({
    ok: bad.length === 0 && msgOk, actual: bad.length ? `not refused as read-only: ${short(bad, 1500)}` : `all ${results.length} refused; message ok=${msgOk} (${results[0].msg})`,
  }));
  const after = {
    customers: (await get('/api/customers', { size: 10 })).json.totalElements,
    invoices: (await get('/api/invoices', { size: 10 })).json.totalElements,
    tasks: (await get('/api/tasks', { size: 10 })).json.totalElements,
    rules: (await rt.automation.list(A)).json.totalElements,
    regions: (await get('/api/regions', { size: 50 })).json.totalElements,
    t2: (await get(`/api/tasks/${S.tasks.T2.id}`)).json.status,
    r2: (await rt.automation.get(A, RL.R2.id)).json.status,
    w2: (await get(`/api/customers/${C.w2.id}`)).json.name,
    wendyGrants: (await get(`/api/users/${S.users.wendy.id}/region-grants`)).json.length,
    d2: (await get('/api/documents', { entityType: 'CUSTOMER', entityId: C.n1.id })).json.totalElements,
  };
  await check({ id: 'ASO-API-91', feature: 'Read-only mode', prd: 'B3', title: 'The refused past-date writes changed nothing',
    steps: 'compare live counts/states before and after the ASO-API-90 matrix', expected: 'identical' }, async () => ({
    ok: JSON.stringify(before) === JSON.stringify(after), actual: `before=${short(before)} after=${short(after)}`,
  }));
  await check({ id: 'ASO-API-92', feature: 'Read-only mode', prd: 'B3', title: 'Housekeeping writes stay allowed under a past date (login, mark notifications read, users export) and answer live',
    steps: `POST /api/auth/login?asOf=${T}; POST /api/notifications/mark-all-read?asOf=${T}; POST /api/users/export?asOf=${T}`, expected: 'DESIGN §5.2.4: method-level @AsOfIgnored -> 200, X-View-As-Of: live' }, async () => {
    const a = await rt.api('POST', '/api/auth/login', { query: { asOf: T }, body: { username: 'admin', password: 'admin123' } });
    const b = await rt.api('POST', '/api/notifications/mark-all-read', { token: A, query: { asOf: T }, body: {} });
    const c = await rt.api('POST', '/api/users/export', { token: A, query: { asOf: T }, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const ok = a.status === 200 && b.status === 200 && c.status === 200 && [a, b, c].every((x) => x.headers['x-view-as-of'] === 'live');
    return { ok, actual: `login=${a.status}/${a.headers['x-view-as-of']} markAll=${b.status}/${b.headers['x-view-as-of']} usersExport=${c.status}/${c.headers['x-view-as-of']}` };
  });
  await check({ id: 'ASO-API-93', feature: 'asOf = today write', prd: 'B3', title: 'A write sent with asOf equal to today is live and goes through',
    steps: `POST /api/tasks?asOf=${rt.day(0)} (task on d1 inv); then cancel it via bulk`, expected: 'DESIGN §5.2.2: d == today -> live, header live' }, async () => {
    const r = await rt.api('POST', '/api/tasks', { token: A, query: { asOf: rt.day(0) }, body: { entityType: 'INVOICE', entityId: I.d1[0].id, title: `today ${tag}`, dueDate: rt.day(2), assignees: [] } });
    if (r.ok) await rt.api('POST', '/api/tasks/bulk', { token: A, body: { action: 'CANCEL', ids: [r.json.id] } });
    return { ok: r.status === 200 && r.headers['x-view-as-of'] === 'live', actual: `${r.status} header=${r.headers['x-view-as-of']}` };
  });

  // ---------------------------------------------------------------- customer login
  await check({ id: 'ASO-API-94', feature: 'Customer login as of', prd: 'B3', title: 'A customer login asking as of T sees its own records of then; writes are refused',
    steps: `cust GET /api/invoices?asOf=${T}; live; GET /api/invoices/{s1-300}?asOf=${T}; POST /api/disputes?asOf=${T}`, expected: 'DESIGN D17: the API honours asOf for customer logins; ids = then snapshot; the later invoice 404 at T; write 400 AS_OF_READ_ONLY' }, async () => {
    const t = await rt.list(S.tokens.cust, '/api/invoices', { asOf: T });
    const l = await rt.list(S.tokens.cust, '/api/invoices');
    const d = await get(`/api/invoices/${S.invoiceS1Small.id}`, { asOf: T }, S.tokens.cust);
    const w = await rt.api('POST', '/api/disputes', { token: S.tokens.cust, query: { asOf: T }, body: { targetType: 'INVOICE', targetId: I.s1[0].id, reason: 'ro' } });
    const ok = eqSet(ids(t.json.content), S.then.custInvoiceIds) && ids(l.json.content).includes(S.invoiceS1Small.id) && d.status === 404 && w.status === 400 && w.json.error === 'AS_OF_READ_ONLY';
    return { ok, actual: `asOf ids=${ids(t.json.content)} then=${S.then.custInvoiceIds} live=${ids(l.json.content)}; later by id=${d.status}; write=${err(w)}` };
  });
};
