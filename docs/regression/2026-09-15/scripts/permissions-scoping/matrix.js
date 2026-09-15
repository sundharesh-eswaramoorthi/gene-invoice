// Privilege matrix: for each role call a representative set of endpoints and compare allow/deny
// against design notes §2 (and the controllers' @PreAuthorize). Writes matrix-result.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const P = 'permsc';

// Design notes §2 matrix, verbatim.
const M = {
  ADMIN: 'CUSTOMER_VIEW CUSTOMER_MANAGE PRODUCT_VIEW PRODUCT_MANAGE INVOICE_VIEW INVOICE_MANAGE PAYMENT_VIEW PAYMENT_MANAGE DISPUTE_CREATE DISPUTE_VIEW DISPUTE_MANAGE NOTIFICATION_VIEW AUDIT_VIEW USER_VIEW USER_MANAGE ROLE_VIEW ROLE_MANAGE POC_VIEW POC_ASSIGN POC_ASSIGNABLE_SALES POC_ASSIGNABLE_SUCCESS POC_ASSIGNABLE_COLLECTION SCOPE_OVERRIDE PROMISE_VIEW PROMISE_MANAGE PROMISE_OVERRIDE EXPORT_DATA',
  CASHIER: 'CUSTOMER_VIEW CUSTOMER_MANAGE PRODUCT_VIEW INVOICE_VIEW INVOICE_MANAGE PAYMENT_VIEW PAYMENT_MANAGE NOTIFICATION_VIEW POC_VIEW POC_ASSIGN SCOPE_OVERRIDE PROMISE_VIEW EXPORT_DATA',
  VIEWER: 'CUSTOMER_VIEW PRODUCT_VIEW INVOICE_VIEW PAYMENT_VIEW NOTIFICATION_VIEW POC_VIEW SCOPE_OVERRIDE PROMISE_VIEW',
  CUSTOMER: 'CUSTOMER_VIEW INVOICE_VIEW PAYMENT_VIEW DISPUTE_CREATE DISPUTE_VIEW NOTIFICATION_VIEW AUDIT_VIEW PROMISE_VIEW',
  SALES_POC: 'CUSTOMER_VIEW PRODUCT_VIEW INVOICE_VIEW INVOICE_MANAGE PAYMENT_VIEW DISPUTE_VIEW NOTIFICATION_VIEW AUDIT_VIEW POC_VIEW POC_ASSIGN POC_ASSIGNABLE_SALES PROMISE_VIEW EXPORT_DATA',
  CUSTOMER_SUCCESS_POC: 'CUSTOMER_VIEW CUSTOMER_MANAGE PRODUCT_VIEW INVOICE_VIEW PAYMENT_VIEW DISPUTE_VIEW NOTIFICATION_VIEW AUDIT_VIEW POC_VIEW POC_ASSIGN POC_ASSIGNABLE_SUCCESS SCOPE_OVERRIDE PROMISE_VIEW EXPORT_DATA',
  COLLECTION_POC: 'CUSTOMER_VIEW INVOICE_VIEW PAYMENT_VIEW PAYMENT_MANAGE DISPUTE_VIEW NOTIFICATION_VIEW AUDIT_VIEW POC_VIEW POC_ASSIGN POC_ASSIGNABLE_COLLECTION SCOPE_OVERRIDE PROMISE_VIEW PROMISE_MANAGE PROMISE_OVERRIDE EXPORT_DATA',
};
for (const k of Object.keys(M)) M[k] = new Set(M[k].split(' '));

const future = new Date(Date.now() + 15 * 86400000).toISOString().slice(0, 10);

async function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}

/** Per-role throwaway records, created by admin, so an allowed call can really succeed. */
async function fixtures(admin, role) {
  const f = {};
  f.custPut = await rt.createCustomer(admin, P + 'put');
  f.custDel = await rt.createCustomer(admin, P + 'del');
  f.seat = await must(await rt.api('POST', `/api/customers/${f.custPut.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: st.cs1.id, primary: true } }), 'seat');
  f.prod = await must(await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq(P + 'p'), price: 5, active: true } }), 'prod');
  f.prodDel = await must(await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq(P + 'pd'), price: 5, active: true } }), 'prodDel');
  f.user = await rt.createStaff(admin, 'VIEWER', P + 'u');
  f.userDel = await rt.createStaff(admin, 'VIEWER', P + 'ud');
  const priv = ['PRODUCT_VIEW'];
  f.role = await must(await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('PERMSC_R').toUpperCase(), description: 'rt', privileges: priv } }), 'role');
  f.roleDel = await must(await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('PERMSC_RD').toUpperCase(), description: 'rt', privileges: priv } }), 'roleDel');
  f.invCancel = await must(await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.custX.id, salesPocUserId: st.s1.id, items: [{ productId: st.product.id, quantity: 1 }] } }), 'invCancel');
  f.prom = await must(await rt.api('POST', '/api/promises', { token: admin, body: { customerId: st.custX.id, amount: 5, promisedDate: future, collectionPocUserId: st.c1.id, notes: 'matrix ' + role } }), 'prom');
  // one open dispute per invoice: give each role a fresh invoice on X to dispute, and a fresh one on A
  f.invDispX = await must(await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.custX.id, salesPocUserId: st.s1.id, items: [{ productId: st.product.id, quantity: 1 }] } }), 'invDispX');
  f.invDispA = await must(await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.custA.id, salesPocUserId: st.s1.id, notes: 'matrix dispute target', items: [{ productId: st.product.id, quantity: 1 }] } }), 'invDispA');
  f.disp = await must(await rt.api('POST', '/api/disputes', { token: st.tokX, body: { targetType: 'INVOICE', targetId: f.invDispX.id, reason: 'matrix ' + role } }), 'disp');
  return f;
}

// Each probe: [name, requiredPrivs[], method, pathFn, bodyFn, opts]
// opts.customerOnly: needs a customer-scoped caller; opts.staffOnly: customer callers get 403 regardless.
const probes = [
  ['GET customers', ['CUSTOMER_VIEW'], 'GET', () => '/api/customers'],
  ['GET customers/summary', ['CUSTOMER_VIEW'], 'GET', () => '/api/customers/summary'],
  ['GET customers/{A}', ['CUSTOMER_VIEW'], 'GET', () => `/api/customers/${st.custA.id}`],
  ['GET customers/{A}/pocs', ['POC_VIEW'], 'GET', () => `/api/customers/${st.custA.id}/pocs`, null, { staffOnly: true }],
  ['POST customers', ['CUSTOMER_MANAGE'], 'POST', () => '/api/customers', () => { const u = rt.uniq(P + 'mc'); return { name: u, username: u, password: rt.PASSWORD }; }],
  ['PUT customers/{id}', ['CUSTOMER_MANAGE'], 'PUT', (f) => `/api/customers/${f.custPut.id}`, (f) => ({ name: f.custPut.name, phone: '555-0199' })],
  ['POST customers/{id}/pocs', ['POC_ASSIGN'], 'POST', (f) => `/api/customers/${f.custPut.id}/pocs`, () => ({ pocType: 'COLLECTION', userId: st.c2.id, primary: false }), { staffOnly: true }],
  ['DELETE customers/{id}/pocs/{seat}', ['POC_ASSIGN'], 'DELETE', (f) => `/api/customers/${f.custPut.id}/pocs/${f.seat.id}`, null, { staffOnly: true }],
  ['POST customers/bulk ADD_POC', ['CUSTOMER_MANAGE', 'POC_ASSIGN'], 'POST', () => '/api/customers/bulk', (f) => ({ action: 'ADD_POC', ids: [f.custPut.id], params: { userId: st.cs1.id, pocType: 'SUCCESS' } })],
  ['DELETE customers/{id}', ['CUSTOMER_MANAGE'], 'DELETE', (f) => `/api/customers/${f.custDel.id}`],

  ['GET products', ['PRODUCT_VIEW'], 'GET', () => '/api/products'],
  ['GET products/{id}', ['PRODUCT_VIEW'], 'GET', () => `/api/products/${st.product.id}`],
  ['POST products', ['PRODUCT_MANAGE'], 'POST', () => '/api/products', () => ({ name: rt.uniq(P + 'mp'), price: 1, active: true })],
  ['PUT products/{id}', ['PRODUCT_MANAGE'], 'PUT', (f) => `/api/products/${f.prod.id}`, (f) => ({ name: f.prod.name, price: 6, active: true })],
  ['POST products/bulk DEACTIVATE', ['PRODUCT_MANAGE'], 'POST', () => '/api/products/bulk', (f) => ({ action: 'DEACTIVATE', ids: [f.prod.id] })],
  ['DELETE products/{id}', ['PRODUCT_MANAGE'], 'DELETE', (f) => `/api/products/${f.prodDel.id}`],

  ['GET invoices', ['INVOICE_VIEW'], 'GET', () => '/api/invoices'],
  ['GET invoices/summary', ['INVOICE_VIEW'], 'GET', () => '/api/invoices/summary'],
  ['GET invoices/{A1}', ['INVOICE_VIEW'], 'GET', () => `/api/invoices/${st.invA1.id}`],
  ['GET invoices/assignable-check', ['INVOICE_VIEW'], 'GET', () => '/api/invoices/assignable-check'],
  ['POST invoices', ['INVOICE_MANAGE'], 'POST', () => '/api/invoices', () => ({ customerId: st.custX.id, salesPocUserId: st.s1.id, items: [{ productId: st.product.id, quantity: 1 }] })],
  ['PATCH invoices/{X1} notes', ['INVOICE_MANAGE'], 'PATCH', () => `/api/invoices/${st.invX1.id}`, () => ({ notes: 'matrix patch' })],
  ['POST invoices/{id}/cancel', ['INVOICE_MANAGE'], 'POST', (f) => `/api/invoices/${f.invCancel.id}/cancel`],
  ['POST invoices/bulk REASSIGN_SALES_POC', ['INVOICE_MANAGE'], 'POST', () => '/api/invoices/bulk', () => ({ action: 'REASSIGN_SALES_POC', ids: [st.invX1.id], params: { userId: st.s1.id } })],

  ['GET payments', ['PAYMENT_VIEW'], 'GET', () => '/api/payments'],
  ['GET payments/summary', ['PAYMENT_VIEW'], 'GET', () => '/api/payments/summary'],
  ['GET payments/{A}', ['PAYMENT_VIEW'], 'GET', () => `/api/payments/${st.payA.id}`],
  ['GET payments/credits/{A}', ['PAYMENT_VIEW'], 'GET', () => `/api/payments/credits/${st.custA.id}`],
  ['POST payments', ['PAYMENT_MANAGE'], 'POST', () => '/api/payments', () => ({ customerId: st.custX.id, amount: 1, method: 'CASH', collectionPocUserId: st.c1.id })],
  ['PATCH payments/{X} notes', ['PAYMENT_MANAGE'], 'PATCH', () => `/api/payments/${st.payX.id}`, () => ({ notes: 'matrix patch' })],
  ['POST payments/bulk REASSIGN_COLLECTION_POC', ['PAYMENT_MANAGE'], 'POST', () => '/api/payments/bulk', () => ({ action: 'REASSIGN_COLLECTION_POC', ids: [st.payX.id], params: { userId: st.c1.id } })],

  ['GET promises', ['PROMISE_VIEW'], 'GET', () => '/api/promises'],
  ['GET promises/summary', ['PROMISE_VIEW'], 'GET', () => '/api/promises/summary'],
  ['GET promises/{A}', ['PROMISE_VIEW'], 'GET', () => `/api/promises/${st.promA.id}`],
  ['POST promises', ['PROMISE_MANAGE'], 'POST', () => '/api/promises', () => ({ customerId: st.custX.id, amount: 3, promisedDate: future, collectionPocUserId: st.c1.id })],
  ['PUT promises/{id}', ['PROMISE_MANAGE'], 'PUT', (f) => `/api/promises/${f.prom.id}`, () => ({ amount: 6, promisedDate: future, collectionPocUserId: st.c1.id, notes: 'matrix put', invoiceIds: [] })],
  ['POST promises/{id}/override', ['PROMISE_OVERRIDE'], 'POST', (f) => `/api/promises/${f.prom.id}/override`, () => ({ status: 'KEPT', reason: 'matrix' })],
  ['DELETE promises/{id}/override', ['PROMISE_OVERRIDE'], 'DELETE', (f) => `/api/promises/${f.prom.id}/override`],
  ['POST promises/bulk REASSIGN_COLLECTION_POC', ['PROMISE_MANAGE'], 'POST', () => '/api/promises/bulk', () => ({ action: 'REASSIGN_COLLECTION_POC', ids: [st.promX.id], params: { userId: st.c1.id } })],
  ['POST promises/{id}/cancel', ['PROMISE_MANAGE'], 'POST', (f) => `/api/promises/${f.prom.id}/cancel`, () => ({ reason: 'matrix' })],

  ['GET disputes', ['DISPUTE_VIEW'], 'GET', () => '/api/disputes'],
  ['GET disputes/{A}', ['DISPUTE_VIEW'], 'GET', () => `/api/disputes/${st.dispA.id}`],
  ['POST disputes', ['DISPUTE_CREATE'], 'POST', () => '/api/disputes', (f) => ({ targetType: 'INVOICE', targetId: f.invDispA.id, reason: 'matrix probe' }), { customerOnly: true }],
  ['POST disputes/{id}/deny', ['DISPUTE_MANAGE'], 'POST', (f) => `/api/disputes/${f.disp.id}/deny`, () => ({ adminNotes: 'matrix' })],

  ['GET users', ['USER_VIEW'], 'GET', () => '/api/users'],
  ['GET users/{id}', ['USER_VIEW'], 'GET', () => `/api/users/${st.viewer.id}`],
  ['POST users', ['USER_MANAGE'], 'POST', () => '/api/users', () => { const u = rt.uniq(P + 'mu'); return { username: u, password: rt.PASSWORD, roleId: st._viewerRoleId, active: true }; }],
  ['PUT users/{id}', ['USER_MANAGE'], 'PUT', (f) => `/api/users/${f.user.id}`, () => ({ fullName: 'Matrix Put' })],
  ['POST users/bulk DEACTIVATE', ['USER_MANAGE'], 'POST', () => '/api/users/bulk', (f) => ({ action: 'DEACTIVATE', ids: [f.user.id] })],
  ['DELETE users/{id}', ['USER_MANAGE'], 'DELETE', (f) => `/api/users/${f.userDel.id}`],

  ['GET roles', ['ROLE_VIEW'], 'GET', () => '/api/roles'],
  ['GET roles/{id}', ['ROLE_VIEW'], 'GET', (f) => `/api/roles/${f.role.id}`],
  ['GET privileges', ['ROLE_VIEW'], 'GET', () => '/api/privileges'],
  ['POST roles', ['ROLE_MANAGE'], 'POST', () => '/api/roles', () => ({ name: rt.uniq('PERMSC_MR').toUpperCase(), privileges: ['PRODUCT_VIEW'] })],
  ['PUT roles/{id}', ['ROLE_MANAGE'], 'PUT', (f) => `/api/roles/${f.role.id}`, (f) => ({ name: f.role.name, description: 'put', privileges: ['PRODUCT_VIEW'] })],
  ['DELETE roles/{id}', ['ROLE_MANAGE'], 'DELETE', (f) => `/api/roles/${f.roleDel.id}`],

  ['GET notifications', ['NOTIFICATION_VIEW'], 'GET', () => '/api/notifications'],
  ['GET notifications/unread-count', ['NOTIFICATION_VIEW'], 'GET', () => '/api/notifications/unread-count'],
  ['POST notifications/bulk MARK_READ all', ['NOTIFICATION_VIEW'], 'POST', () => '/api/notifications/bulk', () => ({ action: 'MARK_UNREAD', selectAllMatchingFilter: true, filters: ['id:eq:0'] })],

  ['GET audit INVOICE A1', ['AUDIT_VIEW'], 'GET', () => `/api/audit?entityType=INVOICE&entityId=${st.invA1.id}`],
  ['GET pocs/assignable', ['POC_VIEW'], 'GET', () => '/api/pocs/assignable?type=SALES', null, { staffOnly: true }],
  ['GET pocs/types', ['POC_VIEW'], 'GET', () => '/api/pocs/types', null, { staffOnly: true }],
  ['GET pocs/my-scope', ['POC_VIEW'], 'GET', () => '/api/pocs/my-scope', null, { staffOnly: true }],
  ['GET table-schemas/invoices', [], 'GET', () => '/api/table-schemas/invoices'],
  ['GET auth/me', [], 'GET', () => '/api/auth/me'],
];

// Exports: annotation requires only EXPORT_DATA; design intent = EXPORT_DATA + the entity's VIEW.
const exportsList = [
  ['customers', 'CUSTOMER_VIEW'], ['products', 'PRODUCT_VIEW'], ['invoices', 'INVOICE_VIEW'], ['payments', 'PAYMENT_VIEW'],
  ['promises', 'PROMISE_VIEW'], ['users', 'USER_VIEW'], ['roles', 'ROLE_VIEW'], ['disputes', 'DISPUTE_VIEW'],
];

function outcome(s) { return s >= 200 && s < 300 ? 'ALLOW' : s === 403 ? 'DENY' : `OTHER(${s})`; }

(async () => {
  const admin = await rt.adminToken();
  st._viewerRoleId = await rt.roleId(admin, 'VIEWER');
  if (!st.cashierRt) {
    st.cashierRt = await rt.createStaff(admin, 'CASHIER', P + 'cash');
    fs.writeFileSync(path.join(__dirname, 'state.json'), JSON.stringify(st, null, 2));
  }
  const subjects = {
    ADMIN: { token: admin, customer: false },
    CASHIER: { token: await rt.login(st.cashierRt.username, rt.PASSWORD), customer: false },
    VIEWER: { token: await rt.login(st.viewer.username, rt.PASSWORD), customer: false },
    CUSTOMER: { token: await rt.login(st.custA.username, rt.PASSWORD), customer: true },
    SALES_POC: { token: await rt.login(st.s1.username, rt.PASSWORD), customer: false },
    CUSTOMER_SUCCESS_POC: { token: await rt.login(st.cs1.username, rt.PASSWORD), customer: false },
    COLLECTION_POC: { token: await rt.login(st.c1.username, rt.PASSWORD), customer: false },
  };
  const only = process.argv[2];
  const result = {};
  for (const [role, subj] of Object.entries(subjects)) {
    if (only && only !== role) continue;
    const f = await fixtures(admin, role);
    const rows = [];
    for (const [name, req, method, pf, bf, opts = {}] of probes) {
      let exp = req.every((p) => M[role].has(p)) ? 'ALLOW' : 'DENY';
      if (opts.staffOnly && subj.customer) exp = 'DENY';
      if (opts.customerOnly && !subj.customer) exp = 'DENY';
      const r = await rt.api(method, pf(f), { token: subj.token, body: bf ? bf(f) : undefined });
      const act = outcome(r.status);
      rows.push({ name, exp, act, status: r.status, ok: exp === act, body: exp === act ? undefined : r.text.slice(0, 200) });
    }
    for (const [ent, view] of exportsList) {
      const r = await rt.api('POST', `/api/${ent}/export`, { token: subj.token, body: { selectAllMatchingFilter: true } });
      const expAnno = M[role].has('EXPORT_DATA') ? 'ALLOW' : 'DENY';
      const expDesign = M[role].has('EXPORT_DATA') && M[role].has(view) ? 'ALLOW' : 'DENY';
      const act = outcome(r.status);
      const lines = r.status === 200 ? r.text.split('\n').filter(Boolean).length - 1 : null;
      rows.push({ name: `EXPORT ${ent}`, exp: expDesign, expAnnotation: expAnno, act, status: r.status, ok: expDesign === act, rows: lines, head: r.status === 200 ? r.text.split('\n')[0] : r.text.slice(0, 120) });
    }
    result[role] = rows;
    const bad = rows.filter((x) => !x.ok);
    console.log(`\n== ${role}: ${rows.length} probes, ${bad.length} mismatches`);
    for (const b of bad) console.log(`  MISMATCH ${b.name}: expected ${b.exp} got ${b.act} (${b.status}) ${b.rows != null ? 'rows=' + b.rows : ''} ${(b.body || b.head || '').replace(/\s+/g, ' ').slice(0, 160)}`);
  }

  // Seeded cashier: reads only.
  if (!only || only === 'SEEDED_CASHIER') {
    const ct = await rt.cashierToken();
    const rows = [];
    for (const [name, req, method, pf, bf, opts = {}] of probes) {
      if (method !== 'GET') continue;
      const exp = req.every((p) => M.CASHIER.has(p)) ? 'ALLOW' : 'DENY';
      let pth; try { pth = pf({ role: { id: st.product.id } }); } catch { continue; }
      const r = await rt.api(method, pth, { token: ct });
      rows.push({ name, exp, act: outcome(r.status), status: r.status, ok: exp === outcome(r.status) });
    }
    result.SEEDED_CASHIER = rows;
    const bad = rows.filter((x) => !x.ok);
    console.log(`\n== SEEDED_CASHIER (reads): ${rows.length} probes, ${bad.length} mismatches`);
    for (const b of bad) console.log(`  MISMATCH ${b.name}: expected ${b.exp} got ${b.act} (${b.status})`);
  }
  fs.writeFileSync(path.join(__dirname, only ? `matrix-result-${only}.json` : 'matrix-result.json'), JSON.stringify(result, null, 2));
})().catch((e) => { console.error('MATRIX FAILED', e); process.exit(1); });
