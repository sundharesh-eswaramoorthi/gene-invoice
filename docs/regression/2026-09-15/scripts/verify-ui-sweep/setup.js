// Verifier's own data for re-checking the ui-sweep failures. Saves setup.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}

(async () => {
  const admin = await rt.adminToken();
  const viewer = await rt.createStaff(admin, 'VIEWER', 'vuiv');
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vuis');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vuic');
  const cust = await rt.createCustomer(admin, 'vuicust');
  const custNoPoc = await rt.createCustomer(admin, 'vuinopoc');

  const prod = must(await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('vuiprod'), description: 'verify product', price: 1234.5, active: true },
  }), 'product');
  const bigProd = must(await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('vuibig'), description: 'verify big product', price: 150000, active: true },
  }), 'big product');

  must(await rt.api('POST', `/api/customers/${cust.id}/pocs`, {
    token: admin, body: { pocType: 'COLLECTION', userId: coll.id, primary: true },
  }), 'seat');

  const inv1 = must(await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: cust.id, notes: 'verify inv 1', salesPocUserId: sales.id,
      items: [{ productId: prod.id, quantity: 2 }] },
  }), 'invoice1');
  const inv2 = must(await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: cust.id, notes: 'verify inv 2 big', salesPocUserId: sales.id,
      items: [{ productId: bigProd.id, quantity: 3 }, { productId: prod.id, quantity: 1 }] },
  }), 'invoice2');

  const pay = must(await rt.api('POST', '/api/payments', {
    token: admin,
    body: { customerId: cust.id, amount: 1000, method: 'CASH', notes: 'verify payment',
      invoiceIds: [inv1.id], collectionPocUserId: coll.id },
  }), 'payment');

  const custToken = await rt.login(cust.username, cust.password);
  const disp = must(await rt.api('POST', '/api/disputes', {
    token: custToken, body: { targetType: 'INVOICE', targetId: inv2.id, reason: 'verify: quantity looks wrong' },
  }), 'dispute');
  // A long unbroken reason (no spaces) — for the ellipsis check (UIS-04).
  const longDisp = must(await rt.api('POST', '/api/disputes', {
    token: custToken, body: { targetType: 'PAYMENT', targetId: pay.id, reason: 'VUILONG' + 'z'.repeat(400) },
  }), 'long dispute');

  const out = {
    viewer: { id: viewer.id, username: viewer.username },
    sales: { id: sales.id, username: sales.username },
    coll: { id: coll.id, username: coll.username },
    cust: { id: cust.id, name: cust.name, username: cust.username },
    custNoPoc: { id: custNoPoc.id, name: custNoPoc.name },
    inv1: { id: inv1.id, number: inv1.invoiceNumber, total: inv1.total, status: inv1.status },
    inv2: { id: inv2.id, number: inv2.invoiceNumber, total: inv2.total },
    pay: { id: pay.id, amount: pay.amount },
    disp: { id: disp.id, targetSummary: disp.targetSummary, targetType: disp.targetType },
    longDisp: { id: longDisp.id, targetSummary: longDisp.targetSummary },
    password: rt.PASSWORD,
  };
  fs.writeFileSync(path.join(__dirname, 'setup.json'), JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error('SETUP FAILED', e); process.exit(1); });
