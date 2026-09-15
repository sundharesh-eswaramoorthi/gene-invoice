// Creates all data for the permissions-scoping area and writes state.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const P = 'permsc';

function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}

(async () => {
  const admin = await rt.adminToken();
  const st = { created: new Date().toISOString() };

  // staff
  st.viewer = await rt.createStaff(admin, 'VIEWER', P + 'v');
  st.s1 = await rt.createStaff(admin, 'SALES_POC', P + 's1');
  st.s2 = await rt.createStaff(admin, 'SALES_POC', P + 's2');
  st.cs1 = await rt.createStaff(admin, 'CUSTOMER_SUCCESS_POC', P + 'cs');
  st.c1 = await rt.createStaff(admin, 'COLLECTION_POC', P + 'c1');
  st.c2 = await rt.createStaff(admin, 'COLLECTION_POC', P + 'c2');

  // product
  st.product = must(await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq(P + 'prod'), description: 'rt', price: 100, active: true } }), 'product');

  // customers A, B, Y (Y: only S2 invoices), X (sandbox for write probes)
  for (const k of ['A', 'B', 'Y', 'X']) st['cust' + k] = await rt.createCustomer(admin, P + k.toLowerCase());

  const inv = async (cust, sales, qty, notes) => must(await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId: cust.id, salesPocUserId: sales.id, notes, items: [{ productId: st.product.id, quantity: qty }] },
  }), 'invoice');
  st.invA1 = await inv(st.custA, st.s1, 1, 'A1 by s1');
  st.invA2 = await inv(st.custA, st.s2, 2, 'A2 by s2');
  st.invB1 = await inv(st.custB, st.s1, 3, 'B1 by s1');
  st.invB2 = await inv(st.custB, st.s2, 4, 'B2 by s2');
  st.invY1 = await inv(st.custY, st.s2, 1, 'Y1 by s2');
  st.invX1 = await inv(st.custX, st.s1, 1, 'X1 by s1');
  st.invX2 = await inv(st.custX, st.s2, 1, 'X2 by s2');

  // customer seats: A has C1 collection + CS1 success; B has C2 collection
  st.seatA_c1 = must(await rt.api('POST', `/api/customers/${st.custA.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: st.c1.id, primary: true } }), 'seatA c1');
  st.seatA_cs = must(await rt.api('POST', `/api/customers/${st.custA.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: st.cs1.id, primary: true } }), 'seatA cs');
  st.seatB_c2 = must(await rt.api('POST', `/api/customers/${st.custB.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: st.c2.id, primary: true } }), 'seatB c2');

  // payments
  const pay = async (cust, coll, amount, invoiceIds) => must(await rt.api('POST', '/api/payments', {
    token: admin, body: { customerId: cust.id, amount, method: 'CASH', notes: 'rt', collectionPocUserId: coll.id, invoiceIds },
  }), 'payment');
  st.payA = await pay(st.custA, st.c1, 50, [st.invA1.id]);
  st.payB = await pay(st.custB, st.c2, 70, [st.invB1.id]);
  st.payX = await pay(st.custX, st.c1, 10, [st.invX1.id]);

  // promises
  const future = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);
  const prom = async (cust, coll, amount, invoiceIds) => must(await rt.api('POST', '/api/promises', {
    token: admin, body: { customerId: cust.id, amount, promisedDate: future, collectionPocUserId: coll.id, notes: 'rt', invoiceIds },
  }), 'promise');
  st.promA = await prom(st.custA, st.c1, 120, [st.invA2.id]);
  st.promB = await prom(st.custB, st.c2, 300, [st.invB2.id]);
  st.promX = await prom(st.custX, st.c1, 20, [st.invX2.id]);

  // customer logins
  st.tokA = await rt.login(st.custA.username, rt.PASSWORD);
  st.tokB = await rt.login(st.custB.username, rt.PASSWORD);
  st.tokX = await rt.login(st.custX.username, rt.PASSWORD);

  // disputes: each customer opens one pending + one that admin denies (gives a customer notification)
  const disp = async (tok, invoice, reason) => must(await rt.api('POST', '/api/disputes', {
    token: tok, body: { targetType: 'INVOICE', targetId: invoice.id, reason },
  }), 'dispute');
  st.dispA = await disp(st.tokA, st.invA1, 'A pending');
  st.dispA2 = await disp(st.tokA, st.invA2, 'A to deny');
  st.dispB = await disp(st.tokB, st.invB1, 'B pending');
  st.dispB2 = await disp(st.tokB, st.invB2, 'B to deny');
  st.dispX = await disp(st.tokX, st.invX1, 'X pending');
  must(await rt.api('POST', `/api/disputes/${st.dispA2.id}/deny`, { token: admin, body: { adminNotes: 'no' } }), 'deny A2');
  must(await rt.api('POST', `/api/disputes/${st.dispB2.id}/deny`, { token: admin, body: { adminNotes: 'no' } }), 'deny B2');

  // an audited POC change on A2 so customer A's audit has POC-bearing before/after
  must(await rt.api('PATCH', `/api/invoices/${st.invA2.id}`, { token: admin, body: { notes: 'A2 reassigned', salesPocUserId: st.s1.id } }), 'reassign A2');
  must(await rt.api('PATCH', `/api/invoices/${st.invA2.id}`, { token: admin, body: { notes: 'A2 back', salesPocUserId: st.s2.id } }), 'reassign A2 back');

  fs.writeFileSync(path.join(__dirname, 'state.json'), JSON.stringify(st, null, 2));
  console.log('OK', Object.fromEntries(Object.entries(st).filter(([, v]) => v && v.id).map(([k, v]) => [k, v.id])));
})().catch((e) => { console.error('SETUP FAILED', e); process.exit(1); });
