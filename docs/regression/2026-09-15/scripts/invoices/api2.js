// Re-runs of failing API cases + extra edge cases. Uses state.json from api.js.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = [];
const rec = (id, title, pass, detail) => { out.push({ id, title, pass, detail }); console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title} :: ${JSON.stringify(detail).slice(0, 700)}`); };
const snip = (r) => ({ status: r.status, body: (r.text || '').slice(0, 260) });
const q = (fs_) => fs_.map((f) => 'filter=' + encodeURIComponent(f)).join('&');

(async () => {
  const A = await rt.adminToken();
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });
  const create = (body, token = A) => api('POST', '/api/invoices', body, token);
  const salesT = await rt.login(S.sales.username, S.sales.password);
  const mgrT = await rt.login(S.mgrUser.username, S.mgrUser.password);
  const custAT = await rt.login(S.custA.username, S.custA.password);

  // --- re-runs ---
  let r = await api('GET', '/api/invoices/abc');
  const r2 = await api('POST', '/api/invoices/abc/cancel');
  rec('R18', 'non-numeric invoice id -> 400 (re-run)', r.status === 400 && r2.status === 400, { get: snip(r), cancel: snip(r2) });

  r = await api('PATCH', `/api/invoices/${S.invD.id}`, { notes: 'rerun notes', salesPocUserId: S.tmpSales.id });
  rec('R24', 'notes-only save, unchanged inactive POC resent (re-run)', r.status === 200, snip(r));
  r = await api('PATCH', `/api/invoices/${S.inv2.id}`, { notes: 'rerun mgr', salesPocUserId: S.sales.id }, mgrT);
  const cur = (await api('GET', `/api/invoices/${S.inv2.id}`)).json;
  rec('R25', 'INVOICE_MANAGE w/o POC_ASSIGN notes save with unchanged POC (re-run)', r.status === 200, { patch: snip(r), currentPoc: cur.salesPoc?.id });

  const u = (await create({ customerId: S.custA.id, salesPocUserId: S.sales.id, items: [{ productId: S.p2.id, quantity: 1 }] })).json;
  r = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [u.id, S.invB1.id, S.invCan.id] });
  rec('R37', 'bulk CANCEL ineligible rows land in skipped (re-run)', (r.json.skipped || []).length === 2 && (r.json.failed || []).length === 0, r.json);
  r = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [987654320, 987654321] });
  rec('R39', 'bulk unknown ids reported (re-run)', r.json.requested === 2, r.json);

  // AC-A8 leak via POC filter: customer counts differ depending on the POC id
  const c1 = await api('GET', `/api/invoices?size=50&${q([`salesPocUserId:eq:${S.sales.id}`])}`, undefined, custAT);
  const c2 = await api('GET', `/api/invoices?size=50&${q([`salesPocUserId:eq:${S.sales2.id}`])}`, undefined, custAT);
  const c3 = await api('GET', `/api/invoices?size=50&${q([`salesPocName:contains:${S.sales.username.slice(4, 10)}`])}`, undefined, custAT);
  const c4 = await api('GET', `/api/invoices/summary?${q(['salesPocUserId:isEmpty:'])}`, undefined, custAT);
  rec('R49', 'CUSTOMER can filter by salesPocUserId / salesPocName (AC-A8: must be rejected)', ![c1, c2, c3].some((x) => x.status === 200),
    { bySales: [c1.status, c1.json?.totalElements], bySales2: [c2.status, c2.json?.totalElements], byName: [c3.status, c3.json?.totalElements], tilesIsEmpty: [c4.status, c4.json?.count] });

  // --- schema ---
  const sch = await api('GET', '/api/table-schemas/invoices');
  const cols = Object.fromEntries((sch.json?.columns || []).map((c) => [c.name, c.sortable]));
  const schC = await api('GET', '/api/table-schemas/invoices', undefined, custAT);
  rec('A30b', 'schema: invoice columns + sortable flags; POC columns hidden for customers', sch.status === 200 && cols.invoiceNumber === true && cols.total === true && cols.customerId === false && cols.notes === false && cols.salesPocUserId === false && cols.salesPocName === true
    && !(schC.json?.columns || []).some((c) => /salesPoc/.test(c.name)), { cols, customerCols: (schC.json?.columns || []).map((c) => c.name), presets: sch.json?.datePresets || sch.json?.presets });

  // --- Sales POC scope ---
  const mine = (await create({ customerId: S.custA.id, salesPocUserId: S.sales.id, items: [{ productId: S.p2.id, quantity: 1 }] })).json;
  const theirs = (await create({ customerId: S.custA.id, salesPocUserId: S.sales2.id, items: [{ productId: S.p2.id, quantity: 1 }] })).json;
  const sl = await api('GET', `/api/invoices?size=50&${q([`customerId:eq:${S.custA.id}`])}`, undefined, salesT);
  const onlyMine = (sl.json?.content || []).every((x) => x.salesPoc?.id === S.sales.id);
  rec('A50', 'SALES_POC list locked to own book (lockedFilters shown, only own invoices)', sl.status === 200 && onlyMine && (sl.json.lockedFilters || []).length > 0 && (sl.json.content || []).some((x) => x.id === mine.id),
    { locked: sl.json?.lockedFilters, count: sl.json?.totalElements, onlyMine });
  const sb = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [mine.id, theirs.id] }, salesT);
  const theirsAfter = (await api('GET', `/api/invoices/${theirs.id}`)).json;
  rec('A51', 'SALES_POC bulk CANCEL [mine, other rep\'s]: other rep\'s row not touched and reported as skipped (AC-D6)', sb.status === 200 && theirsAfter.status === 'UNPAID' && (sb.json.skipped || []).some((x) => x.id === theirs.id),
    { bulk: sb.json, theirsStatus: theirsAfter.status });
  const theirs2 = (await create({ customerId: S.custA.id, salesPocUserId: S.sales2.id, items: [{ productId: S.p2.id, quantity: 1 }] })).json;
  const sg = await api('GET', `/api/invoices/${theirs2.id}`, undefined, salesT);
  const sp = await api('PATCH', `/api/invoices/${theirs2.id}`, { notes: 'edited by other rep' }, salesT);
  const sc = await api('POST', `/api/invoices/${theirs2.id}/cancel`, undefined, salesT);
  rec('A52', 'SALES_POC without SCOPE_OVERRIDE cannot GET / PATCH / cancel another rep\'s invoice by id', sg.status === 403 && sp.status === 403 && sc.status === 403,
    { get: sg.status, patch: snip(sp), cancel: snip(sc) });

  // --- numbering under concurrency ---
  const par = await Promise.all(Array.from({ length: 6 }, () => create({ customerId: S.custB.id, salesPocUserId: S.sales.id, items: [{ productId: S.p2.id, quantity: 1 }] })));
  const nums = par.filter((x) => x.status === 200).map((x) => x.json.invoiceNumber);
  rec('A53', 'six concurrent creates all succeed with unique sequential numbers', par.every((x) => x.status === 200) && new Set(nums).size === 6,
    { statuses: par.map((x) => x.status), nums, errors: par.filter((x) => x.status !== 200).map(snip) });

  // --- over-long notes ---
  const long = 'x'.repeat(501);
  const ln = await api('PATCH', `/api/invoices/${S.inv1.id}`, { notes: long });
  const lc = await create({ customerId: S.custB.id, salesPocUserId: S.sales.id, notes: long, items: [{ productId: S.p2.id, quantity: 1 }] });
  rec('A54', 'notes longer than 500 chars -> 400 validation error (not 500)', ln.status === 400 && lc.status === 400, { patch: snip(ln), create: snip(lc) });

  // --- pagination / sort input validation ---
  const hp = await api('GET', '/api/invoices?page=50000000&size=50');
  const sd = await api('GET', '/api/invoices?size=10&sort=total,sideways');
  rec('A55', 'huge page number handled (empty page, not 500); invalid sort direction rejected (AC-D9)', hp.status === 200 && sd.status === 400, { hugePage: snip(hp), sortSideways: { status: sd.status, sort: sd.json?.sort } });

  // --- cancelled invoice balance ---
  const cn = (await api('GET', `/api/invoices/${S.invCan.id}`)).json;
  const tilesCan = (await api('GET', `/api/invoices/summary?${q([`customerId:eq:${S.custA.id}`, 'status:eq:CANCELLED'])}`)).json;
  rec('A56', 'cancelled invoice: DTO balance vs outstanding tile', true, { status: cn.status, total: cn.total, paid: cn.paidAmount, balance: cn.balance, tilesOutstandingCancelled: tilesCan.outstanding });

  fs.writeFileSync(path.join(__dirname, 'api2-results.json'), JSON.stringify(out, null, 2));
  fs.writeFileSync(path.join(__dirname, 'state2.json'), JSON.stringify({ mine, theirs, theirs2, u }, null, 2));
})().catch((e) => { console.error('ERR', e); process.exit(1); });
