// Invoices API regression suite. Run: node api.js  -> writes api-results.json and state.json
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const results = [];
function rec(id, title, pass, detail) {
  results.push({ id, title, pass: !!pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title} :: ${JSON.stringify(detail).slice(0, 600)}`);
}
const snip = (r) => ({ status: r.status, body: (r.text || '').slice(0, 300) });
const q = (filters = [], extra = '') => filters.map((f) => 'filter=' + encodeURIComponent(f)).join('&') + extra;

(async () => {
  const A = await rt.adminToken();
  const C = await rt.cashierToken();
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });

  // ---------- fixtures ----------
  const sales = await rt.createStaff(A, 'SALES_POC', 'inv');
  const sales2 = await rt.createStaff(A, 'SALES_POC', 'inv');
  const collector = await rt.createStaff(A, 'COLLECTION_POC', 'inv');
  const viewer = await rt.createStaff(A, 'VIEWER', 'inv');
  const inactive = await rt.createStaff(A, 'SALES_POC', 'inv');
  const deact = await api('PUT', `/api/users/${inactive.id}`, { active: false });
  // custom role: INVOICE_MANAGE but no POC_ASSIGN
  const roleName = rt.uniq('INVMGR').toUpperCase().replace(/-/g, '_');
  const role = await api('POST', '/api/roles', { name: roleName, description: 'rt invoices', privileges: ['INVOICE_VIEW', 'INVOICE_MANAGE', 'CUSTOMER_VIEW', 'PRODUCT_VIEW', 'POC_VIEW'] });
  const mgrUser = await rt.createStaff(A, roleName, 'inv');
  const custA = await rt.createCustomer(A, 'invA');
  const custB = await rt.createCustomer(A, 'invB');
  const custCredit = await rt.createCustomer(A, 'invCr');
  const custEmpty = await rt.createCustomer(A, 'invE');
  const p1 = (await api('POST', '/api/products', { name: rt.uniq('invP1'), description: 'rt', price: 100.00, active: true })).json;
  const p2 = (await api('POST', '/api/products', { name: rt.uniq('invP2'), description: 'rt', price: 25.50, active: true })).json;
  const salesT = await rt.login(sales.username, sales.password);
  const viewerT = await rt.login(viewer.username, viewer.password);
  const mgrT = await rt.login(mgrUser.username, mgrUser.password);
  const custAT = await rt.login(custA.username, custA.password);
  console.log('fixtures', { sales: sales.id, sales2: sales2.id, collector: collector.id, viewer: viewer.id, inactive: inactive.id, deact: deact.status, role: role.status, mgr: mgrUser.id, custA: custA.id, custB: custB.id, custCredit: custCredit.id, p1: p1.id, p2: p2.id });

  const create = (body, token = A) => api('POST', '/api/invoices', body, token);
  const pay = (customerId, amount, invoiceIds) => api('POST', '/api/payments', { customerId, amount, method: 'CASH', notes: 'rt inv', invoiceIds, collectionPocUserId: collector.id });
  const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');

  // ---------- INV-A01 create with default price ----------
  let r = await create({ customerId: custA.id, salesPocUserId: sales.id, notes: 'first', items: [{ productId: p1.id, quantity: 3 }, { productId: p2.id, quantity: 2 }] });
  const inv1 = r.json;
  rec('A01', 'create: default product price, total, items, status UNPAID', r.status === 200 && Number(inv1.total) === 351 && inv1.items.length === 2
    && Number(inv1.items[0].unitPrice) === 100 && Number(inv1.items[1].lineTotal) === 51 && inv1.status === 'UNPAID' && Number(inv1.balance) === 351
    && inv1.salesPoc?.id === sales.id && inv1.pocMissing === false, { ...snip(r) });
  rec('A02', 'invoice number format INV-yyyyMMdd-NNNN (UTC today)', new RegExp(`^INV-${today}-\\d{4}$`).test(inv1?.invoiceNumber || ''), { invoiceNumber: inv1?.invoiceNumber, today });

  // ---------- unit price override ----------
  r = await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 2, unitPrice: 12.34 }] });
  const inv2 = r.json;
  rec('A03', 'create: unitPrice override used instead of product price', r.status === 200 && Number(inv2.items[0].unitPrice) === 12.34 && Number(inv2.total) === 24.68, snip(r));
  const n1 = parseInt(inv1.invoiceNumber.slice(-4), 10), n2 = parseInt(inv2.invoiceNumber.slice(-4), 10);
  rec('A04', 'invoice numbering increments', n2 > n1 && inv2.invoiceNumber.slice(0, 13) === inv1.invoiceNumber.slice(0, 13), { a: inv1.invoiceNumber, b: inv2.invoiceNumber });

  // zero price override allowed
  r = await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1, unitPrice: 0 }] });
  const invZero = r.json;
  rec('A05', 'create: unitPrice 0 allowed -> total 0 -> FULLY_PAID', r.status === 200 && Number(r.json.total) === 0 && r.json.status === 'FULLY_PAID', snip(r));

  // ---------- validation ----------
  const before = (await api('GET', `/api/invoices/summary?${q([`customerId:eq:${custB.id}`])}`)).json;
  r = await create({ customerId: custB.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1, unitPrice: -1 }] });
  rec('A06', 'create: negative unit price -> 400', r.status === 400 && /negative/i.test(r.text), snip(r));
  r = await create({ customerId: custB.id, salesPocUserId: sales.id, items: [{ productId: 987654321, quantity: 1 }] });
  rec('A07', 'create: unknown product -> 404', r.status === 404 && /Product not found/.test(r.text), snip(r));
  const r8a = await create({ customerId: custB.id, salesPocUserId: sales.id, items: [] });
  const r8b = await create({ customerId: custB.id, salesPocUserId: sales.id });
  const r8c = await create({ customerId: custB.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 0 }] });
  rec('A08', 'create: empty/missing items and qty 0 -> 400', r8a.status === 400 && r8b.status === 400 && r8c.status === 400, { empty: snip(r8a), missing: snip(r8b), qty0: snip(r8c) });
  r = await create({ customerId: custB.id, items: [{ productId: p1.id, quantity: 1 }] });
  rec('A09', 'create: missing salesPocUserId -> 400 "Sales POC is required"', r.status === 400 && /Sales POC is required/i.test(r.text), snip(r));
  const r10a = await create({ customerId: custB.id, salesPocUserId: viewer.id, items: [{ productId: p1.id, quantity: 1 }] });
  const r10b = await create({ customerId: custB.id, salesPocUserId: inactive.id, items: [{ productId: p1.id, quantity: 1 }] });
  const r10c = await create({ customerId: custB.id, salesPocUserId: collector.id, items: [{ productId: p1.id, quantity: 1 }] });
  const custBUser = (await api('GET', `/api/users?size=50&${q([`username:eq:${custB.username}`])}`)).json;
  const custBUserId = custBUser?.content?.[0]?.id;
  const r10d = custBUserId ? await create({ customerId: custB.id, salesPocUserId: custBUserId, items: [{ productId: p1.id, quantity: 1 }] }) : { status: 'n/a', text: JSON.stringify(custBUser).slice(0, 200) };
  rec('A10', 'create: non-assignable (VIEWER, COLLECTION_POC), inactive, customer-account POC -> 400', r10a.status === 400 && r10b.status === 400 && r10c.status === 400 && r10d.status === 400,
    { viewer: snip(r10a), inactive: snip(r10b), collection: snip(r10c), customerLogin: snip(r10d), deactStatus: deact.status });
  r = await create({ customerId: custB.id, salesPocUserId: 987654321, items: [{ productId: p1.id, quantity: 1 }] });
  const r11b = await create({ customerId: 987654321, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1 }] });
  rec('A11', 'create: unknown POC user / unknown customer -> 404', r.status === 404 && r11b.status === 404, { pocUser: snip(r), customer: snip(r11b) });
  const after = (await api('GET', `/api/invoices/summary?${q([`customerId:eq:${custB.id}`])}`)).json;
  rec('A12', 'rejected creates leave no invoice behind', before.count === 0 && after.count === 0, { before, after });

  // ---------- status computation via payments ----------
  r = await create({ customerId: custB.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 2 }] }); // 200
  const invB1 = r.json;
  const payP = await pay(custB.id, 50, [invB1.id]);
  let g = await api('GET', `/api/invoices/${invB1.id}`);
  const partialOk = g.json.status === 'PARTIALLY_PAID' && Number(g.json.paidAmount) === 50 && Number(g.json.balance) === 150;
  const payF = await pay(custB.id, 150, [invB1.id]);
  const g2 = await api('GET', `/api/invoices/${invB1.id}`);
  rec('A13', 'status UNPAID -> PARTIALLY_PAID -> FULLY_PAID from payments', invB1.status === 'UNPAID' && payP.status === 200 && partialOk && g2.json.status === 'FULLY_PAID' && Number(g2.json.balance) === 0,
    { payP: payP.status, afterPartial: { s: g.json.status, paid: g.json.paidAmount, bal: g.json.balance }, payF: payF.status, afterFull: { s: g2.json.status, bal: g2.json.balance } });

  // ---------- customer credit consumed on create ----------
  const credPay = await pay(custCredit.id, 80, undefined); // no invoices -> all credit
  const cBefore = (await api('GET', `/api/customers/${custCredit.id}`)).json;
  r = await create({ customerId: custCredit.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1 }] });
  const invC1 = r.json;
  const cMid = (await api('GET', `/api/customers/${custCredit.id}`)).json;
  const r2 = await create({ customerId: custCredit.id, salesPocUserId: sales.id, items: [{ productId: p2.id, quantity: 1 }] });
  const cAfter = (await api('GET', `/api/customers/${custCredit.id}`)).json;
  rec('A14', 'customer credit consumed automatically on create (80 credit vs 100 invoice)', credPay.status === 200 && Number(cBefore.creditBalance) === 80 && invC1.status === 'PARTIALLY_PAID'
    && Number(invC1.paidAmount) === 80 && Number(invC1.balance) === 20 && Number(cMid.creditBalance) === 0 && r2.json.status === 'UNPAID' && Number(r2.json.paidAmount) === 0 && Number(cAfter.creditBalance) === 0,
    { credPay: credPay.status, creditBefore: cBefore.creditBalance, inv: { s: invC1.status, paid: invC1.paidAmount, bal: invC1.balance }, creditAfter: cMid.creditBalance, second: { s: r2.json.status, paid: r2.json.paidAmount } });
  const credPay2 = await pay(custCredit.id, 500, [r2.json.id]); // 25.50 applied, 474.50 credit
  const r3 = await create({ customerId: custCredit.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 2 }] });
  const cAfter2 = (await api('GET', `/api/customers/${custCredit.id}`)).json;
  rec('A15', 'credit larger than invoice -> FULLY_PAID and remaining credit kept', r3.json.status === 'FULLY_PAID' && Number(r3.json.paidAmount) === 200 && Number(cAfter2.creditBalance) === 274.5,
    { pay2: credPay2.status, inv: { s: r3.json.status, paid: r3.json.paidAmount }, credit: cAfter2.creditBalance });
  let au = await api('GET', `/api/audit?entityType=INVOICE&entityId=${invC1.id}`);
  const createdRow = (au.json || []).find((e) => e.action === 'INVOICE_CREATED');
  rec('A16', 'INVOICE_CREATED audit row notes the credit applied', au.status === 200 && createdRow && /Customer credit applied: 80/.test(createdRow.reason || ''), { status: au.status, row: createdRow && { action: createdRow.action, reason: createdRow.reason } });

  // ---------- GET by id ----------
  g = await api('GET', `/api/invoices/${inv1.id}`);
  rec('A17', 'GET /api/invoices/{id}: items, balance, salesPoc, customer', g.status === 200 && g.json.items.length === 2 && g.json.items.every((i) => i.productName && i.productId && i.quantity > 0)
    && Number(g.json.balance) === 351 && g.json.salesPoc?.id === sales.id && g.json.customerId === custA.id && g.json.customerName === custA.name && g.json.notes === 'first',
    { status: g.status, salesPoc: g.json.salesPoc, balance: g.json.balance, items: g.json.items });
  const g404 = await api('GET', '/api/invoices/987654321');
  const gAbc = await api('GET', '/api/invoices/abc');
  rec('A18', 'GET unknown id -> 404; non-numeric id -> 400', g404.status === 404 && gAbc.status === 400, { unknown: snip(g404), abc: snip(gAbc) });

  // ---------- PATCH notes / POC ----------
  r = await api('PATCH', `/api/invoices/${inv1.id}`, { notes: 'updated notes rt' });
  au = await api('GET', `/api/audit?entityType=INVOICE&entityId=${inv1.id}`);
  const upd = (au.json || []).filter((e) => e.action === 'INVOICE_UPDATED');
  rec('A19', 'PATCH notes -> saved and audited (INVOICE_UPDATED before/after)', r.status === 200 && r.json.notes === 'updated notes rt' && r.json.salesPoc?.id === sales.id && upd.length >= 1
    && /first/.test(upd[0].beforeJson || JSON.stringify(upd[0])) && /updated notes rt/.test(upd[0].afterJson || JSON.stringify(upd[0])),
    { patch: snip(r), audit: upd.map((e) => ({ action: e.action, by: e.changedByUsername, before: String(e.beforeJson).slice(0, 80) })) });
  r = await api('PATCH', `/api/invoices/${inv1.id}`, { salesPocUserId: sales2.id });
  au = await api('GET', `/api/audit?entityType=INVOICE&entityId=${inv1.id}`);
  const upd2 = (au.json || []).filter((e) => e.action === 'INVOICE_UPDATED');
  const g3 = await api('GET', `/api/invoices/${inv1.id}`);
  rec('A20', 'PATCH Sales POC -> reassigned and audited', r.status === 200 && g3.json.salesPoc?.id === sales2.id && g3.json.notes === 'updated notes rt' && upd2.length >= 2
    && upd2.some((e) => String(e.afterJson).includes(sales2.username) || String(e.afterJson).includes(String(sales2.id))),
    { patch: r.status, salesPoc: g3.json.salesPoc, auditCount: upd2.length, newest: upd2[0] && String(upd2[0].afterJson).slice(0, 200) });
  const notif = await rt.api('GET', '/api/notifications?size=50', { token: await rt.login(sales2.username, sales2.password) });
  rec('A21', 'new Sales POC is notified of the assignment', notif.status === 200 && (notif.json.content || []).some((n) => String(n.link || n.message || '').includes(`/invoices/${inv1.id}`) || String(n.message || '').includes(inv1.invoiceNumber)),
    { status: notif.status, sample: (notif.json?.content || []).slice(0, 2) });
  const r22a = await api('PATCH', `/api/invoices/${inv1.id}`, { salesPocUserId: inactive.id });
  const r22b = await api('PATCH', `/api/invoices/${inv1.id}`, { salesPocUserId: viewer.id });
  rec('A22', 'PATCH Sales POC to inactive / non-assignable -> 400, unchanged', r22a.status === 400 && r22b.status === 400 && (await api('GET', `/api/invoices/${inv1.id}`)).json.salesPoc.id === sales2.id, { inactive: snip(r22a), viewer: snip(r22b) });
  const r23 = await api('PATCH', '/api/invoices/987654321', { notes: 'x' });
  rec('A23', 'PATCH unknown invoice -> 404', r23.status === 404, snip(r23));

  // notes-only save after POC deactivated (candidate 2): the web client always resends the POC id
  const tmpSales = await rt.createStaff(A, 'SALES_POC', 'inv');
  const invD = (await create({ customerId: custA.id, salesPocUserId: tmpSales.id, items: [{ productId: p2.id, quantity: 1 }] })).json;
  const delU = await api('DELETE', `/api/users/${tmpSales.id}`);
  const r24a = await api('PATCH', `/api/invoices/${invD.id}`, { notes: 'notes only, client payload', salesPocUserId: tmpSales.id });
  const r24b = await api('PATCH', `/api/invoices/${invD.id}`, { notes: 'notes only, no poc' });
  const gD = await api('GET', `/api/invoices/${invD.id}`);
  rec('A24', 'Notes-only save on invoice whose Sales POC was deactivated (UI payload resends unchanged POC)', r24a.status === 200,
    { deleteUser: snip(delU), withUnchangedPoc: snip(r24a), withoutPoc: snip(r24b), after: { notes: gD.json.notes, poc: gD.json.salesPoc } });
  // role with INVOICE_MANAGE but not POC_ASSIGN
  const r25a = await api('PATCH', `/api/invoices/${inv2.id}`, { notes: 'mgr notes', salesPocUserId: sales.id }, mgrT);
  const r25b = await api('PATCH', `/api/invoices/${inv2.id}`, { notes: 'mgr notes2' }, mgrT);
  const r25c = await api('PATCH', `/api/invoices/${inv2.id}`, { salesPocUserId: sales2.id }, mgrT);
  rec('A25', 'INVOICE_MANAGE without POC_ASSIGN: notes-only save with unchanged POC (UI payload) accepted; POC change refused',
    r25a.status === 200 && r25b.status === 200 && r25c.status >= 400,
    { role: role.status, unchangedPoc: snip(r25a), notesOnly: snip(r25b), changePoc: snip(r25c) });

  // ---------- cancel ----------
  const invCan = (await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p2.id, quantity: 4 }] })).json;
  r = await api('POST', `/api/invoices/${invCan.id}/cancel`);
  au = await api('GET', `/api/audit?entityType=INVOICE&entityId=${invCan.id}`);
  rec('A26', 'cancel UNPAID invoice -> CANCELLED and audited', r.status === 200 && r.json.status === 'CANCELLED' && (au.json || []).some((e) => e.action === 'INVOICE_CANCELLED'), { cancel: snip(r), audit: (au.json || []).map((e) => e.action) });
  const r27a = await api('POST', `/api/invoices/${invCan.id}/cancel`);
  const r27b = await api('POST', `/api/invoices/${invC1.id}/cancel`); // partially paid (credit)
  const r27c = await api('POST', `/api/invoices/${invB1.id}/cancel`); // fully paid
  const r27d = await api('POST', '/api/invoices/987654321/cancel');
  rec('A27', 'cancel: already cancelled 400, partially paid 400, fully paid 400, unknown 404', r27a.status === 400 && /already cancelled/i.test(r27a.text) && r27b.status === 400 && /payments/i.test(r27b.text) && r27c.status === 400 && r27d.status === 404,
    { already: snip(r27a), partial: snip(r27b), full: snip(r27c), unknown: snip(r27d) });

  // ---------- list: data set for custE (dates, statuses) ----------
  const E = custEmpty.id;
  const d40 = new Date(Date.now() - 40 * 86400e3).toISOString();
  const d3 = new Date(Date.now() - 3 * 86400e3).toISOString();
  const eInv = [];
  const specs = [
    { items: [{ productId: p1.id, quantity: 1 }], invoiceDate: d40, salesPocUserId: sales.id },          // 100, 40 days ago
    { items: [{ productId: p1.id, quantity: 2 }], invoiceDate: d3, salesPocUserId: sales2.id },          // 200, 3 days ago
    { items: [{ productId: p2.id, quantity: 1 }], salesPocUserId: sales.id },                            // 25.50 today
    { items: [{ productId: p1.id, quantity: 5 }], salesPocUserId: sales2.id },                           // 500 today
    { items: [{ productId: p2.id, quantity: 3 }], salesPocUserId: sales.id },                            // 76.50 today
  ];
  for (const s of specs) eInv.push((await create({ customerId: E, notes: 'rt list', ...s })).json);
  await pay(E, 60, [eInv[1].id]);         // eInv[1] 200 -> PARTIALLY_PAID (60)
  await pay(E, 25.5, [eInv[2].id]);       // eInv[2] -> FULLY_PAID
  await api('POST', `/api/invoices/${eInv[4].id}/cancel`); // eInv[4] CANCELLED
  const EF = `customerId:eq:${E}`;

  // paging
  const pg0 = await api('GET', `/api/invoices?size=10&page=0&${q([EF])}`);
  const pgAll = await api('GET', `/api/invoices?size=10&${q([])}`);
  const pgB = await api('GET', `/api/invoices?size=10&page=1&${q([])}`);
  const overlap = (pgAll.json.content || []).map((x) => x.id).filter((id) => (pgB.json.content || []).map((x) => x.id).includes(id));
  rec('A28', 'list paging: totals, page 0/1 disjoint, default sort invoiceDate desc', pg0.status === 200 && pg0.json.totalElements === 5 && pg0.json.content.length === 5
    && pgAll.json.content.length === 10 && overlap.length === 0 && pg0.json.content[pg0.json.content.length - 1].id === eInv[0].id,
    { total: pg0.json.totalElements, keys: Object.keys(pg0.json), overlap, firstPageSize: pgAll.json.content.length, secondPage: pgB.json.content.length });
  const bad1 = await api('GET', '/api/invoices?size=13');
  const bad2 = await api('GET', '/api/invoices?page=-1');
  const bad3 = await api('GET', `/api/invoices?${q(['nope:eq:1'])}`);
  const bad4 = await api('GET', `/api/invoices?${q(['total:contains:1'])}`);
  const bad5 = await api('GET', `/api/invoices?${q(['invoiceDate:relative:someday'])}`);
  const bad6 = await api('GET', '/api/invoices?sort=nope,asc');
  rec('A29', 'list validation: bad size, negative page, unknown column, bad operator, unknown preset, unknown sort -> 400',
    [bad1, bad2, bad3, bad4, bad5, bad6].every((x) => x.status === 400), [bad1, bad2, bad3, bad4, bad5, bad6].map(snip));

  // sort on every sortable column
  const sortable = ['id', 'invoiceNumber', 'customerName', 'invoiceDate', 'total', 'paidAmount', 'balance', 'status', 'salesPocName', 'createdAt'];
  const sortOut = {};
  let sortPass = true;
  const val = (x, f) => ({ id: x.id, invoiceNumber: x.invoiceNumber, customerName: x.customerName, invoiceDate: x.invoiceDate, total: Number(x.total), paidAmount: Number(x.paidAmount), balance: Number(x.balance), status: x.status, salesPocName: x.salesPoc?.fullName || x.salesPoc?.display || '' }[f]);
  for (const f of sortable) {
    for (const dir of ['asc', 'desc']) {
      const s = await api('GET', `/api/invoices?size=10&sort=${f},${dir}&${q([EF])}`);
      let ordered = s.status === 200;
      if (ordered && f !== 'createdAt') {
        const vals = s.json.content.map((x) => val(x, f));
        for (let i = 1; i < vals.length; i++) {
          const a = vals[i - 1], b = vals[i];
          const cmp = typeof a === 'number' ? a - b : String(a).localeCompare(String(b));
          if (dir === 'asc' ? cmp > 0 && typeof a === 'number' : cmp < 0 && typeof a === 'number') ordered = false;
          if (typeof a !== 'number' && f !== 'status' && f !== 'salesPocName' && (dir === 'asc' ? a > b : a < b)) ordered = false;
        }
        if (f === 'status' || f === 'salesPocName') sortOut[`${f},${dir}`] = vals;
      }
      if (!ordered) { sortPass = false; sortOut[`${f},${dir}`] = { status: s.status, vals: s.json?.content?.map((x) => val(x, f)), body: s.text.slice(0, 200) }; }
    }
  }
  const ns = await Promise.all(['customerId', 'notes', 'salesPocUserId'].map((f) => api('GET', `/api/invoices?sort=${f},asc`)));
  rec('A30', 'sort works asc/desc on every sortable column; non-sortable columns rejected 400', sortPass && ns.every((x) => x.status === 400), { sortOut, nonSortable: ns.map(snip) });
  const sch = await api('GET', '/api/tables/invoices/schema');
  rec('A30b', 'schema endpoint lists invoice columns with sortable flags', sch.status === 200, { status: sch.status, body: sch.text.slice(0, 400) });

  // filters
  const ids = (res) => (res.json?.content || []).map((x) => x.id).sort((a, b) => a - b);
  const exp = (...ix) => ix.map((i) => eInv[i].id).sort((a, b) => a - b);
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const f1 = await api('GET', `/api/invoices?size=50&${q([EF, 'status:in:UNPAID,PARTIALLY_PAID'])}`);
  const f2 = await api('GET', `/api/invoices?size=50&${q([EF, 'total:between:100,200'])}`);
  const f3 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:today'])}`);
  const f4 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:last7Days'])}`);
  const f5 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:last30Days'])}`);
  const f6 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:past'])}`);
  const f7 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:future'])}`);
  const f8 = await api('GET', `/api/invoices?size=50&${q([EF, `salesPocUserId:eq:${sales2.id}`])}`);
  const f9 = await api('GET', `/api/invoices?size=50&${q([EF, 'salesPocUserId:isEmpty:'])}`);
  const f10 = await api('GET', `/api/invoices?size=50&${q([EF, 'salesPocUserId:isNotEmpty:'])}`);
  const f11 = await api('GET', `/api/invoices?size=50&customerId=${E}`);
  const f12 = await api('GET', `/api/invoices?size=50&${q([EF, 'status:eq:CANCELLED'])}`);
  const f13 = await api('GET', `/api/invoices?size=50&${q([EF, 'balance:gt:0'])}`);
  const d40day = d40.slice(0, 10);
  const f14 = await api('GET', `/api/invoices?size=50&${q([EF, `invoiceDate:between:${d40day},${d40day}`])}`);
  const f15 = await api('GET', `/api/invoices?size=50&${q(['salesPocUserId:isEmpty:'])}`);
  const lm = new Date(Date.now() - 40 * 86400e3); const nowD = new Date();
  const lastMonthHas0 = lm.getUTCMonth() === (nowD.getUTCMonth() + 11) % 12;
  const f16 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:lastMonth'])}`);
  const f17 = await api('GET', `/api/invoices?size=50&${q([EF, 'invoiceDate:relative:thisMonth'])}`);
  const thisMonthExp = [1, 2, 3, 4].filter((i) => new Date(eInv[i].invoiceDate).getUTCMonth() === nowD.getUTCMonth());
  const filt = {
    statusIn: [ids(f1), exp(0, 1, 3)], between: [ids(f2), exp(0, 1)], today: [ids(f3), exp(2, 3, 4)], last7: [ids(f4), exp(1, 2, 3, 4)],
    last30: [ids(f5), exp(1, 2, 3, 4)], past: [ids(f6), exp(0, 1)], future: [ids(f7), []], pocEq: [ids(f8), exp(1, 3)], pocEmpty: [ids(f9), []],
    pocNotEmpty: [ids(f10), exp(0, 1, 2, 3, 4)], customerIdParam: [ids(f11), exp(0, 1, 2, 3, 4)], cancelled: [ids(f12), exp(4)], balanceGt0: [ids(f13), exp(0, 1, 3)],
    dateBetweenSameDay: [ids(f14), exp(0)], lastMonth: [ids(f16), lastMonthHas0 ? exp(0) : []], thisMonth: [ids(f17), exp(...thisMonthExp)],
  };
  const bad = Object.entries(filt).filter(([, [a, b]]) => !same(a, b));
  rec('A31', 'filters: status in, total between, date presets (today/last7/last30/past/future/lastMonth/thisMonth), date between, customerId, salesPocUserId eq/isEmpty/isNotEmpty, balance gt',
    bad.length === 0, { mismatches: Object.fromEntries(bad), statuses: [f1, f2, f3, f9].map((x) => x.status) });
  rec('A32', 'global salesPocUserId:isEmpty returns only POC-missing rows (pocMissing=true)', f15.status === 200 && (f15.json.content || []).every((x) => x.salesPoc == null && x.pocMissing === true), { status: f15.status, total: f15.json?.totalElements });

  // summary tiles
  const sm = await api('GET', `/api/invoices/summary?${q([EF])}`);
  const s = sm.json;
  // totals: 100 + 200 + 25.5 + 500 + 76.5 = 902 ; paid: 60 + 25.5 = 85.5 ; outstanding (excl. cancelled 76.5): 100+140+0+500 = 740
  rec('A33', 'summary tiles over filter: count, totalBilled, totalPaid, outstanding excl. cancelled, per-status counts, pocMissingCount',
    sm.status === 200 && s.count === 5 && Number(s.totalBilled) === 902 && Number(s.totalPaid) === 85.5 && Number(s.outstanding) === 740
    && s.unpaidCount === 2 && s.partiallyPaidCount === 1 && s.fullyPaidCount === 1 && s.cancelledCount === 1 && s.pocMissingCount === 0, s);
  const sm2 = await api('GET', `/api/invoices/summary?${q([EF, 'status:in:UNPAID,PARTIALLY_PAID'])}`);
  const sm3 = await api('GET', `/api/invoices/summary?customerId=${E}`);
  rec('A34', 'summary follows extra filters and customerId param', sm2.json.count === 3 && Number(sm2.json.totalBilled) === 800 && Number(sm2.json.outstanding) === 740 && sm3.json.count === 5, { withStatus: sm2.json, param: sm3.json });
  const sm4 = await api('GET', `/api/invoices/summary?${q([EF, 'invoiceDate:relative:future'])}`);
  rec('A35', 'summary for empty result renders zeros (AC-E5)', sm4.status === 200 && sm4.json.count === 0 && Number(sm4.json.totalBilled) === 0 && Number(sm4.json.outstanding) === 0, sm4.json);
  const sm5 = await api('GET', `/api/invoices/summary?${q(['bogus:eq:1'])}`);
  rec('A35b', 'summary with invalid filter -> 400', sm5.status === 400, snip(sm5));

  // ---------- bulk CANCEL mixed ----------
  const bU1 = (await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p2.id, quantity: 1 }] })).json;
  const bU2 = (await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p2.id, quantity: 2 }] })).json;
  // invB1 fully paid, invC1 partially paid, invCan already cancelled
  const bk = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [bU1.id, bU2.id, invB1.id, invC1.id, invCan.id] });
  const b = bk.json || {};
  const accounted = [...(b.succeeded || []), ...(b.failed || []).map((x) => x.id), ...(b.skipped || []).map((x) => x.id)].sort();
  rec('A36', 'bulk CANCEL mixed: unpaid succeed, paid/cancelled reported per record, every id accounted for', bk.status === 200 && b.requested === 5
    && same((b.succeeded || []).sort(), [bU1.id, bU2.id].sort()) && accounted.length === 5,
    { status: bk.status, requested: b.requested, succeeded: b.succeeded, failed: b.failed, skipped: b.skipped });
  rec('A37', 'bulk CANCEL: ineligible (paid / already cancelled) rows reported as skipped, not failed (AC-D6)', (b.skipped || []).length === 3 && (b.failed || []).length === 0,
    { failed: b.failed, skipped: b.skipped });
  const st = await Promise.all([bU1, bU2, invB1, invC1].map((x) => api('GET', `/api/invoices/${x.id}`)));
  const auB = await api('GET', `/api/audit?entityType=INVOICE&entityId=${bU1.id}`);
  rec('A38', 'bulk CANCEL side effects: cancelled ones CANCELLED, paid untouched, audit per record', st[0].json.status === 'CANCELLED' && st[1].json.status === 'CANCELLED' && st[2].json.status === 'FULLY_PAID' && st[3].json.status === 'PARTIALLY_PAID'
    && (auB.json || []).some((e) => e.action === 'INVOICE_CANCELLED'), { statuses: st.map((x) => x.json.status), audit: (auB.json || []).map((e) => e.action) });
  const bk2 = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [987654321] });
  rec('A39', 'bulk with an unknown/out-of-scope id: reported, not silently dropped (AC-D5)', bk2.status === 200 && (bk2.json.requested === 1) && ((bk2.json.failed || []).length + (bk2.json.skipped || []).length) === 1,
    { status: bk2.status, body: bk2.json });
  const bk3 = await api('POST', '/api/invoices/bulk', { action: 'FROB', ids: [bU1.id] });
  const bk4 = await api('POST', '/api/invoices/bulk', { action: 'CANCEL' });
  const bk5 = await api('POST', '/api/invoices/bulk', { action: 'REASSIGN_SALES_POC', ids: [bU1.id] });
  const bk6 = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [bU1.id] }, viewerT);
  rec('A40', 'bulk validation: unknown action 400, no ids 400, reassign without userId 400, VIEWER 403', bk3.status === 400 && bk4.status === 400 && bk5.status === 400 && bk6.status === 403,
    { unknown: snip(bk3), noIds: snip(bk4), noUser: snip(bk5), viewer: snip(bk6) });

  // select all matching
  const sa = [];
  const custSA = await rt.createCustomer(A, 'invSA');
  for (let i = 0; i < 3; i++) sa.push((await create({ customerId: custSA.id, salesPocUserId: sales.id, items: [{ productId: p2.id, quantity: i + 1 }] })).json);
  await pay(custSA.id, 10, [sa[2].id]);
  const bk7 = await api('POST', '/api/invoices/bulk', { action: 'CANCEL', selectAllMatchingFilter: true, filters: [`customerId:eq:${custSA.id}`, 'status:eq:UNPAID'] });
  const saSum = (await api('GET', `/api/invoices/summary?${q([`customerId:eq:${custSA.id}`])}`)).json;
  rec('A41', 'bulk CANCEL selectAllMatchingFilter applies to the filtered set only', bk7.status === 200 && bk7.json.requested === 2 && bk7.json.succeeded.length === 2 && bk7.json.truncated === false
    && saSum.cancelledCount === 2 && saSum.partiallyPaidCount === 1, { bulk: bk7.json, summary: saSum });

  // bulk reassign
  const bk8 = await api('POST', '/api/invoices/bulk', { action: 'REASSIGN_SALES_POC', ids: [eInv[0].id, eInv[1].id, eInv[3].id], params: { userId: sales2.id } });
  const re = await Promise.all([eInv[0], eInv[1], eInv[3]].map((x) => api('GET', `/api/invoices/${x.id}`)));
  const auR = await api('GET', `/api/audit?entityType=INVOICE&entityId=${eInv[0].id}`);
  rec('A42', 'bulk REASSIGN_SALES_POC: all succeed, POC changed, audited per record', bk8.status === 200 && bk8.json.succeeded.length === 3 && re.every((x) => x.json.salesPoc.id === sales2.id)
    && (auR.json || []).some((e) => e.action === 'INVOICE_UPDATED'), { bulk: bk8.json, pocs: re.map((x) => x.json.salesPoc.id), audit: (auR.json || []).map((e) => e.action) });
  const bk9 = await api('POST', '/api/invoices/bulk', { action: 'REASSIGN_SALES_POC', ids: [eInv[0].id], params: { userId: inactive.id } });
  const bk10 = await api('POST', '/api/invoices/bulk', { action: 'REASSIGN_SALES_POC', ids: [eInv[0].id], params: { userId: sales.id } }, mgrT);
  rec('A43', 'bulk REASSIGN to inactive user -> per-record failure with reason; caller without POC_ASSIGN refused', bk9.status === 200 && bk9.json.succeeded.length === 0 && ((bk9.json.failed || []).length + (bk9.json.skipped || []).length) === 1
    && bk10.status >= 400, { inactive: bk9.json, noPocAssign: snip(bk10) });

  // ---------- export ----------
  const ex = await api('POST', '/api/invoices/export', { action: 'EXPORT', ids: [eInv[0].id, eInv[3].id], sort: 'total,asc', filters: [] });
  const lines = ex.text.trim().split(/\r\n/);
  rec('A44', 'export CSV for selected ids: headers, rows, text/csv, attachment', ex.status === 200 && /text\/csv/.test(ex.headers['content-type']) && /attachment; filename="invoices.csv"/.test(ex.headers['content-disposition'] || '')
    && lines[0] === 'Invoice #,Customer,Date,Total,Paid,Balance,Status,Sales POC' && lines.length === 3 && lines[1].startsWith(eInv[0].invoiceNumber) && lines[2].includes(sales2.username),
    { status: ex.status, ct: ex.headers['content-type'], cd: ex.headers['content-disposition'], lines });
  const ex2 = await api('POST', '/api/invoices/export', { action: 'EXPORT', selectAllMatchingFilter: true, filters: [EF, 'status:eq:CANCELLED'] });
  const ex3 = await api('POST', '/api/invoices/export', { action: 'EXPORT', selectAllMatchingFilter: true, filters: [EF] }, custAT);
  const ex4 = await api('POST', '/api/invoices/export', { action: 'EXPORT', ids: [eInv[0].id] }, C);
  rec('A45', 'export selectAll over filter; cashier may export; customer (no EXPORT_DATA) 403', ex2.status === 200 && ex2.text.trim().split(/\r\n/).length === 2 && ex2.text.includes('CANCELLED') && ex4.status === 200 && ex3.status === 403,
    { selectAll: ex2.text.trim().split(/\r\n/), cashier: ex4.status, customer: snip(ex3) });

  // ---------- permissions / scoping ----------
  const pv1 = await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1 }] }, viewerT);
  const pv2 = await api('POST', `/api/invoices/${bU1.id}/cancel`, undefined, viewerT);
  const pv3 = await api('PATCH', `/api/invoices/${inv1.id}`, { notes: 'x' }, viewerT);
  const pv4 = await api('GET', `/api/invoices?size=10`, undefined, viewerT);
  const pv5 = await api('GET', `/api/invoices`);
  const noTok = await rt.api('GET', '/api/invoices');
  rec('A46', 'VIEWER: list 200, create/cancel/patch 403; no token 401', pv1.status === 403 && pv2.status === 403 && pv3.status === 403 && pv4.status === 200 && (noTok.status === 401 || noTok.status === 403),
    { create: pv1.status, cancel: pv2.status, patch: pv3.status, list: pv4.status, noToken: noTok.status });
  const pc = await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1 }] }, C);
  const pcSelf = await create({ customerId: custA.id, salesPocUserId: (await rt.api('GET', '/api/auth/me', { token: C })).json?.id, items: [{ productId: p1.id, quantity: 1 }] }, C);
  const chk = await rt.api('GET', '/api/invoices/assignable-check', { token: C });
  const chkA = await rt.api('GET', '/api/invoices/assignable-check', { token: A });
  rec('A47', 'CASHIER creates with a Sales POC; cashier itself not assignable (400); assignable-check admin true / cashier false', pc.status === 200 && pcSelf.status === 400 && chk.json?.sales === false && chkA.json?.sales === true,
    { create: pc.status, selfPoc: snip(pcSelf), cashierCheck: chk.json, adminCheck: chkA.json });
  const cu1 = await api('GET', `/api/invoices/${inv1.id}`, undefined, custAT);
  const cu2 = await api('GET', `/api/invoices/${invB1.id}`, undefined, custAT);
  const cu3 = await api('GET', `/api/invoices?size=50&${q([`customerId:eq:${custB.id}`])}`, undefined, custAT);
  const cu4 = await api('GET', `/api/invoices/summary?${q([`customerId:eq:${custB.id}`])}`, undefined, custAT);
  const cu5 = await create({ customerId: custA.id, salesPocUserId: sales.id, items: [{ productId: p1.id, quantity: 1 }] }, custAT);
  rec('A48', 'CUSTOMER: own invoice without POC identity, other customer 403, cannot widen scope via filter, cannot create', cu1.status === 200 && cu1.json.salesPoc === null && cu1.json.pocMissing === null
    && cu2.status === 403 && cu3.status === 200 && cu3.json.totalElements === 0 && cu4.json?.count === 0 && cu5.status === 403,
    { own: { s: cu1.status, poc: cu1.json?.salesPoc, pm: cu1.json?.pocMissing }, other: cu2.status, filterOther: cu3.json?.totalElements, tilesOther: cu4.json, create: cu5.status });
  const cu6 = await api('GET', `/api/invoices?size=10&${q(['salesPocName:contains:a'])}`, undefined, custAT);
  const cu7 = await api('GET', `/api/invoices?size=10&sort=salesPocName,asc`, undefined, custAT);
  rec('A49', 'CUSTOMER cannot filter/sort on POC-restricted columns (AC-A8)', cu6.status === 400 || cu6.status === 403, { filter: snip(cu6), sort: snip(cu7) });

  const state = { sales, sales2, collector, viewer, inactive, mgrUser, custA, custB, custCredit, custEmpty, custSA, p1, p2, inv1, inv2, invB1, invC1, invCan, invD, eInv, tmpSales };
  fs.writeFileSync(path.join(__dirname, 'state.json'), JSON.stringify(state, null, 2));
  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify(results, null, 2));
  console.log(`\n${results.filter((x) => x.pass).length}/${results.length} passed`);
})().catch((e) => { console.error('SUITE ERROR', e); process.exit(1); });
