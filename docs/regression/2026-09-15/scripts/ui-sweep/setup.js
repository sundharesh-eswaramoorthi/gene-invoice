// Creates the data the UI sweep needs and saves it to setup.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}

(async () => {
  const admin = await rt.adminToken();
  const viewer = await rt.createStaff(admin, 'VIEWER', 'uisv');
  const sales = await rt.createStaff(admin, 'SALES_POC', 'uiss');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'uisc');
  const cust = await rt.createCustomer(admin, 'uiscust');

  const prod = must(await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('uisprod'), description: 'UI sweep product', price: 1234.5, active: true },
  }), 'product');
  const bigProd = must(await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('uisbig'), description: 'UI sweep big product', price: 150000, active: true },
  }), 'big product');

  const seat = must(await rt.api('POST', `/api/customers/${cust.id}/pocs`, {
    token: admin, body: { pocType: 'COLLECTION', userId: coll.id, primary: true },
  }), 'seat');

  const inv1 = must(await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: cust.id, notes: 'UI sweep invoice 1', salesPocUserId: sales.id,
      items: [{ productId: prod.id, quantity: 2 }] },
  }), 'invoice1');
  const inv2 = must(await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: cust.id, notes: 'UI sweep invoice 2 big', salesPocUserId: sales.id,
      items: [{ productId: bigProd.id, quantity: 3 }, { productId: prod.id, quantity: 1 }] },
  }), 'invoice2');

  const pay = must(await rt.api('POST', '/api/payments', {
    token: admin,
    body: { customerId: cust.id, amount: 1000, method: 'CASH', notes: 'UI sweep payment',
      invoiceIds: [inv1.id], collectionPocUserId: coll.id },
  }), 'payment');

  const d = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10);
  const prom = must(await rt.api('POST', '/api/promises', {
    token: admin,
    body: { customerId: cust.id, amount: 500, promisedDate: d, collectionPocUserId: coll.id,
      notes: 'UI sweep promise', invoiceIds: [inv1.id] },
  }), 'promise');

  const custToken = await rt.login(cust.username, cust.password);
  const disp = must(await rt.api('POST', '/api/disputes', {
    token: custToken, body: { targetType: 'INVOICE', targetId: inv2.id, reason: 'UI sweep: quantity looks wrong' },
  }), 'dispute');

  const out = {
    viewer: { id: viewer.id, username: viewer.username },
    sales: { id: sales.id, username: sales.username },
    coll: { id: coll.id, username: coll.username },
    cust: { id: cust.id, name: cust.name, username: cust.username },
    prod: { id: prod.id, name: prod.name }, bigProd: { id: bigProd.id, name: bigProd.name },
    seat: seat.id,
    inv1: { id: inv1.id, number: inv1.invoiceNumber, total: inv1.total, balance: inv1.balance },
    inv2: { id: inv2.id, number: inv2.invoiceNumber, total: inv2.total, balance: inv2.balance },
    pay: { id: pay.id, amount: pay.amount },
    prom: { id: prom.id, status: prom.status },
    disp: { id: disp.id, status: disp.status },
    password: rt.PASSWORD,
  };
  fs.writeFileSync(path.join(__dirname, 'setup.json'), JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error('SETUP FAILED', e); process.exit(1); });
