// Creates all data for the UI re-verification of D-04, D-06, D-07, D-10, D-11, D-12 via the API.
// Writes state.json next to this file. Names are prefixed "vu-" (or "zz-vu-").
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const utcDay = (d) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 10);

(async () => {
  const admin = await rt.adminToken();
  const S = { log: [] };
  const must = (r, what) => {
    S.log.push({ what, status: r.status, body: (r.text || '').slice(0, 300) });
    if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
    return r.json;
  };
  S.sales = await rt.createStaff(admin, 'SALES_POC', 'vu-sales');
  S.coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vu-coll');
  S.prod = (await rt.api('GET', '/api/products?size=10&sort=id,asc&filter=active:eq:true', { token: admin })).json.content[0];

  const invoice = (customerId, unitPrice, salesPocUserId = S.sales.id) => rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId, salesPocUserId, notes: 'vu original', items: [{ productId: S.prod.id, quantity: 1, unitPrice }] },
  });
  const pay = (customerId, amount, invoiceIds, collectionPocUserId = S.coll.id) => rt.api('POST', '/api/payments', {
    token: admin, body: { customerId, amount, method: 'Cash', notes: 'vu original', collectionPocUserId, invoiceIds },
  });

  // ---- D-04: invoice + payment + two pending disputes
  S.c4 = await rt.createCustomer(admin, 'vu-d04');
  S.inv4 = must(await invoice(S.c4.id, 500), 'inv4');
  S.pay4 = must(await pay(S.c4.id, 100, [S.inv4.id]), 'pay4');
  S.inv4b = must(await invoice(S.c4.id, 200), 'inv4b'); // one open dispute per invoice
  const dispute = async (tok, id) => rt.api('POST', '/api/disputes', {
    token: tok, body: { targetType: 'INVOICE', targetId: id, reason: 'vu D-04 check', proposedChangeJson: '{"action":"update_notes","notes":"x"}' },
  });
  let d = await dispute(admin, S.inv4.id);
  if (d.status >= 300) {
    S.log.push({ what: 'dispute as admin', status: d.status, body: d.text.slice(0, 300) });
    const ct = await rt.login(S.c4.username, S.c4.password);
    d = await dispute(ct, S.inv4.id);
    S.disputeCreatedBy = 'customer';
    S.d4a = must(d, 'd4a');
    S.d4b = must(await dispute(ct, S.inv4b.id), "d4b");
  } else {
    S.disputeCreatedBy = 'admin';
    S.d4a = must(d, 'd4a');
    S.d4b = must(await dispute(admin, S.inv4b.id), "d4b");
  }

  // ---- D-06: zz customer, active + inactive zz products
  S.zzCust = await rt.createCustomer(admin, 'zz-vu');
  const pname = rt.uniq('zz-vu-prod');
  S.zzProd = must(await rt.api('POST', '/api/products', { token: admin, body: { name: pname, description: 'vu active', price: 123.45, active: true } }), 'zzProd');
  const iname = rt.uniq('zz-vu-inactive');
  S.zzInactive = must(await rt.api('POST', '/api/products', { token: admin, body: { name: iname, description: 'vu inactive', price: 99, active: false } }), 'zzInactive');

  // ---- D-07: POCs that get deactivated
  S.sx = await rt.createStaff(admin, 'SALES_POC', 'vu-d07s');
  S.cx = await rt.createStaff(admin, 'COLLECTION_POC', 'vu-d07c');
  S.c7 = await rt.createCustomer(admin, 'vu-d07');
  S.inv7 = must(await invoice(S.c7.id, 300, S.sx.id), 'inv7');
  S.pay7 = must(await pay(S.c7.id, 50, [S.inv7.id], S.cx.id), 'pay7');
  S.delSx = must(await rt.api('DELETE', `/api/users/${S.sx.id}`, { token: admin }), 'delete sx') ;
  S.delCx = must(await rt.api('DELETE', `/api/users/${S.cx.id}`, { token: admin }), 'delete cx');
  S.sxAfter = (await rt.api('GET', `/api/users/${S.sx.id}`, { token: admin })).json;
  S.cxAfter = (await rt.api('GET', `/api/users/${S.cx.id}`, { token: admin })).json;

  // ---- D-10: promise on [A,B], B cancelled
  S.c10 = await rt.createCustomer(admin, 'vu-d10');
  S.A = must(await invoice(S.c10.id, 100), 'A');
  S.B = must(await invoice(S.c10.id, 100), 'B');
  S.promise = must(await rt.api('POST', '/api/promises', {
    token: admin, body: { customerId: S.c10.id, amount: 200, promisedDate: utcDay(7), collectionPocUserId: S.coll.id, notes: 'vu promise', invoiceIds: [S.A.id, S.B.id] },
  }), 'promise');
  S.cancelB = must(await rt.api('POST', `/api/invoices/${S.B.id}/cancel`, { token: admin, body: { reason: 'vu cancel B' } }), 'cancel B');

  // ---- D-11: customer, invoice, payment allocated to it
  S.c11 = await rt.createCustomer(admin, 'vu-d11');
  S.inv11 = must(await invoice(S.c11.id, 400), 'inv11');
  S.pay11 = must(await pay(S.c11.id, 150, [S.inv11.id]), 'pay11');

  // ---- D-12: long-named collection POC, viewer, customer login
  S.longColl = await rt.createStaff(admin, 'COLLECTION_POC', 'vu-collections-representative-north');
  S.viewer = await rt.createStaff(admin, 'VIEWER', 'vu-viewer');
  S.c12 = await rt.createCustomer(admin, 'vu-d12cust');

  fs.writeFileSync(path.join(DIR, 'state.json'), JSON.stringify(S, null, 2));
  console.log(JSON.stringify({
    disputeCreatedBy: S.disputeCreatedBy, d4a: S.d4a.id, d4b: S.d4b.id, inv4: S.inv4.id, pay4: S.pay4.id,
    zzCust: S.zzCust.name, zzProd: S.zzProd, zzInactive: S.zzInactive,
    inv7: S.inv7.id, pay7: S.pay7.id, sxActive: S.sxAfter?.active, cxActive: S.cxAfter?.active, delSx: S.delSx,
    A: S.A.id, B: S.B.id, promise: S.promise.id, promiseInvoices: S.promise.invoices,
    c11: S.c11.id, inv11: S.inv11.id, pay11: S.pay11.id, longColl: S.longColl.username,
  }, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
