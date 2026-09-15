// Second run of each failing API case on this verifier's own data (shared server rule).
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 700)); };

(async () => {
  const admin = await rt.adminToken();
  const L = (u) => rt.login(u.username, rt.PASSWORD);
  const S1 = await L(st.s1); const C1 = await L(st.c1); const CASH = await L(st.cash); const A = await L(st.A);

  log('PS010', {
    cashUsersGET: (await rt.api('GET', '/api/users', { token: CASH })).status,
    cashUsersExport: (await rt.api('POST', '/api/users/export', { token: CASH, body: { selectAllMatchingFilter: true } })).status,
    cashDisputesGET: (await rt.api('GET', '/api/disputes', { token: CASH })).status,
    cashDisputesExport: (await rt.api('POST', '/api/disputes/export', { token: CASH, body: { selectAllMatchingFilter: true } })).status,
    c1ProductsGET: (await rt.api('GET', '/api/products', { token: C1 })).status,
    c1ProductsExport: (await rt.api('POST', '/api/products/export', { token: C1, body: { selectAllMatchingFilter: true } })).status,
    s1RolesExport: (await rt.api('POST', '/api/roles/export', { token: S1, body: { selectAllMatchingFilter: true } })).status,
  });

  const b = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'REASSIGN_SALES_POC', ids: [st.invY1.id, st.invA2.id], params: { userId: st.s1.id } } });
  log('PS018', { requestedIds: [st.invY1.id, st.invA2.id], result: b.json });

  log('PS022', {
    s1: (await rt.api('GET', `/api/invoices?${qs([`salesPocUserId:eq:${st.s1.id}`])}`, { token: A })).json?.totalElements,
    s2: (await rt.api('GET', `/api/invoices?${qs([`salesPocUserId:eq:${st.s2.id}`])}`, { token: A })).json?.totalElements,
    sort: (await rt.api('GET', '/api/invoices?sort=collectionPocName,asc', { token: A })).status,
    paySort: (await rt.api('GET', '/api/payments?sort=collectionPocName,asc', { token: A })).status,
  });

  const pr = await rt.api('GET', `/api/promises/${st.promA.id}`, { token: A });
  const di = await rt.api('GET', `/api/disputes/${st.dispA.id}`, { token: A });
  log('PS023', { createdByUserId: pr.json?.createdByUserId, resolvedByUserId: di.json?.resolvedByUserId });

  const extra = (await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.Y.id, salesPocUserId: st.s2.id, notes: 'Y3', items: [{ productId: st.prod.id, quantity: 1, unitPrice: 100 }] } })).json;
  log('PS029', {
    get: (await rt.api('GET', `/api/invoices/${extra.id}`, { token: S1 })).status,
    patch: (await rt.api('PATCH', `/api/invoices/${extra.id}`, { token: S1, body: { notes: 'vps recheck s1 edit' } })).status,
    cancel: (await rt.api('POST', `/api/invoices/${extra.id}/cancel`, { token: S1 })).status,
    statusAfter: (await rt.api('GET', `/api/invoices/${extra.id}`, { token: admin })).json?.status,
  });

  log('PS033', {
    s1Payments: (await rt.api('GET', '/api/payments', { token: S1 })).json?.totalElements,
    adminPayments: (await rt.api('GET', '/api/payments', { token: admin })).json?.totalElements,
    s1SeesPromB: (await rt.api('GET', `/api/promises?${qs([`id:eq:${st.promB.id}`])}`, { token: S1 })).json?.totalElements,
  });

  const seat = await rt.api('POST', `/api/customers/${st.Y.id}/pocs`, { token: S1, body: { pocType: 'COLLECTION', userId: st.c1.id } });
  log('PS034', { add: seat.status, primary: seat.json?.primary });
  if (seat.status === 200) await rt.api('DELETE', `/api/customers/${st.Y.id}/pocs/${seat.json.id}`, { token: admin });

  log('PS035', { s1: (await rt.api('GET', `/api/audit?entityType=USER&entityId=${st.viewer.id}`, { token: S1 })).status });

  const d = await rt.createStaff(admin, 'SALES_POC', 'vpsdeact3');
  const td = await rt.login(d.username, rt.PASSWORD);
  await rt.api('POST', '/api/users/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [d.id] } });
  log('PS039', {
    relogin: (await rt.api('POST', '/api/auth/login', { body: { username: d.username, password: rt.PASSWORD } })).status,
    oldTokenPayments: (await rt.api('GET', '/api/payments', { token: td })).status,
    oldTokenCreateInvoice: (await rt.api('POST', '/api/invoices', { token: td, body: { customerId: st.Y.id, salesPocUserId: st.s2.id, notes: 'vps by bulk-deactivated user', items: [{ productId: st.prod.id, quantity: 1, unitPrice: 1 }] } })).status,
  });
  fs.writeFileSync(path.join(__dirname, 'recheck-result.json'), JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
