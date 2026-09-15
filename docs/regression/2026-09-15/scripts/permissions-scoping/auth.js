// 401 vs 403 semantics, deactivated users with still-valid tokens, live privilege changes.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 700)); };
const P = 'permsc';

(async () => {
  const admin = await rt.adminToken();

  // A01 401 family
  const A01 = {
    noToken: (await rt.api('GET', '/api/invoices')).status,
    garbage: (await rt.api('GET', '/api/invoices', { token: 'not.a.jwt' })).status,
    expired: (await rt.api('GET', '/api/invoices', { token: rt.mintToken(st.viewer.username, -60) })).status,
    unknownUser: (await rt.api('GET', '/api/invoices', { token: rt.mintToken('no-such-user-' + Date.now(), 600) })).status,
    tamperedSig: (await rt.api('GET', '/api/invoices', { token: rt.mintToken(st.viewer.username, 600).slice(0, -3) + 'abc' })).status,
    badPassword: (await rt.api('POST', '/api/auth/login', { body: { username: st.viewer.username, password: 'wrong' } })).status,
    meNoToken: (await rt.api('GET', '/api/auth/me')).status,
  };
  log('A01_401', A01);

  // A02 403 for valid token lacking privilege (body shape)
  const V = await rt.login(st.viewer.username, rt.PASSWORD);
  const r403 = await rt.api('POST', '/api/products', { token: V, body: { name: rt.uniq(P), price: 1 } });
  const r403b = await rt.api('GET', '/api/users', { token: V });
  log('A02_403', { postProduct: r403.status, body: r403.json?.message, getUsers: r403b.status });

  // A03 deactivated via PUT active:false
  const d1 = await rt.createStaff(admin, 'VIEWER', P + 'deact');
  const t1 = await rt.login(d1.username, rt.PASSWORD);
  const before = (await rt.api('GET', '/api/invoices', { token: t1 })).status;
  await rt.api('PUT', `/api/users/${d1.id}`, { token: admin, body: { active: false } });
  const u1 = await rt.api('GET', `/api/users/${d1.id}`, { token: admin });
  const loginAfter = await rt.api('POST', '/api/auth/login', { body: { username: d1.username, password: rt.PASSWORD } });
  const listAfter = await rt.api('GET', '/api/invoices?size=10', { token: t1 });
  const meAfter = await rt.api('GET', '/api/auth/me', { token: t1 });
  const custAfter = await rt.api('GET', `/api/customers/${st.custA.id}`, { token: t1 });
  log('A03_deactivatedPut', { before, activeFlag: u1.json.active, loginAfter: loginAfter.status, loginMsg: loginAfter.json?.message, listWithOldToken: listAfter.status, listRows: listAfter.json?.totalElements, meWithOldToken: meAfter.status, meBody: meAfter.text.slice(0, 120), customerWithOldToken: custAfter.status });

  // A04 deactivated via DELETE of a POC-referenced SALES_POC, then writes with old token
  const d2 = await rt.createStaff(admin, 'SALES_POC', P + 'deldeact');
  const inv = (await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.custX.id, salesPocUserId: d2.id, items: [{ productId: st.product.id, quantity: 1 }] } })).json;
  const t2 = await rt.login(d2.username, rt.PASSWORD);
  const del = await rt.api('DELETE', `/api/users/${d2.id}`, { token: admin });
  const patch = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: t2, body: { notes: 'written by a deactivated user' } });
  const invAfter = await rt.api('GET', `/api/invoices/${inv.id}`, { token: admin });
  const exp = await rt.api('POST', '/api/users/export', { token: t2, body: { selectAllMatchingFilter: true } });
  log('A04_deactivatedDelete', { del: del.json, patchWithOldToken: patch.status, notesAfter: invAfter.json.notes, exportUsersWithOldToken: exp.status, exportRows: exp.status === 200 ? exp.text.trim().split('\n').length - 1 : null });

  // A05 bulk DEACTIVATE
  const d3 = await rt.createStaff(admin, 'VIEWER', P + 'bulkdeact');
  const t3 = await rt.login(d3.username, rt.PASSWORD);
  const bulk = await rt.api('POST', '/api/users/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [d3.id] } });
  const after3 = await rt.api('GET', '/api/payments', { token: t3 });
  log('A05_bulkDeactivate', { bulk: bulk.json?.succeeded, oldToken: after3.status });

  // A06 privilege revoked on the role takes effect for an existing token
  const role = (await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('PERMSC_LIVE').toUpperCase(), privileges: ['PRODUCT_VIEW'] } })).json;
  const uR = (await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq(P + 'live'), password: rt.PASSWORD, roleId: role.id, active: true } })).json;
  const tR = await rt.login(uR.username, rt.PASSWORD);
  const g1 = (await rt.api('GET', '/api/products', { token: tR })).status;
  await rt.api('PUT', `/api/roles/${role.id}`, { token: admin, body: { name: role.name, privileges: ['INVOICE_VIEW'] } });
  const g2 = (await rt.api('GET', '/api/products', { token: tR })).status;
  const g3 = (await rt.api('GET', '/api/invoices', { token: tR })).status;
  log('A06_liveRevoke', { productsBefore: g1, productsAfterRevoke: g2, invoicesAfterGrant: g3 });

  // A07 custom role PROMISE_MANAGE without POC_ASSIGN reassigning promise POC (parity with invoices/payments)
  const role2 = (await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('PERMSC_PM').toUpperCase(), privileges: ['PROMISE_VIEW', 'PROMISE_MANAGE', 'PAYMENT_VIEW', 'PAYMENT_MANAGE', 'CUSTOMER_VIEW'] } })).json;
  const u2 = (await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq(P + 'pm'), password: rt.PASSWORD, roleId: role2.id, active: true } })).json;
  const tP = await rt.login(u2.username, rt.PASSWORD);
  const pb = await rt.api('POST', '/api/promises/bulk', { token: tP, body: { action: 'REASSIGN_COLLECTION_POC', ids: [st.promX.id], params: { userId: st.c2.id } } });
  const promAfter = await rt.api('GET', `/api/promises/${st.promX.id}`, { token: admin });
  const payB = await rt.api('POST', '/api/payments/bulk', { token: tP, body: { action: 'REASSIGN_COLLECTION_POC', ids: [st.payX.id], params: { userId: st.c2.id } } });
  const payPatch = await rt.api('PATCH', `/api/payments/${st.payX.id}`, { token: tP, body: { collectionPocUserId: st.c2.id } });
  log('A07_promiseReassignNoPocAssign', { promiseBulk: pb.status, promiseBulkRes: pb.json?.succeeded, promXPocAfter: promAfter.json.collectionPoc?.id, c2: st.c2.id, paymentBulk: payB.status, paymentBulkMsg: payB.json?.message, paymentPatch: payPatch.status });
  // restore promX
  await rt.api('POST', '/api/promises/bulk', { token: admin, body: { action: 'REASSIGN_COLLECTION_POC', ids: [st.promX.id], params: { userId: st.c1.id } } });

  fs.writeFileSync(path.join(__dirname, 'auth-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('AUTH FAILED', e); process.exit(1); });
