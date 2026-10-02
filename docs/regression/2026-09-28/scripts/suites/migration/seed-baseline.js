// Seeds a representative "existing" dataset through the BASELINE jar's own API (dataset B), so that it is exactly
// what a pre-upgrade installation would hold: staff of every seeded role (one inactive), customers with logins
// and POCs, back-dated invoices (some cancelled), payments (allocated, on account), promises, disputes (one
// denied). Every call must succeed on the baseline; a failure throws (it is a fixture, not a test result).
const PW = 'Passw0rd!';

function need(r, what, ok = [200, 201]) {
  if (!ok.includes(r.status)) throw new Error(`${what} -> ${r.status} ${String(r.text).slice(0, 400)}`);
  return r.json;
}
const day = (n) => { const d = new Date(); d.setUTCHours(0, 0, 0, 0); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

async function seedBaseline(c) {
  const admin = need(await c.login('admin', 'admin123'), 'admin login').token;
  const roles = need(await c.api('GET', '/api/roles', { token: admin, query: { size: 50 } }), 'roles').content;
  const role = (n) => roles.find((r) => r.name === n).id;
  const staff = [
    ['b.sales1', 'SALES_POC', true], ['b.sales2', 'SALES_POC', true], ['b.coll', 'COLLECTION_POC', true],
    ['b.succ', 'CUSTOMER_SUCCESS_POC', true], ['b.cash', 'CASHIER', true], ['b.view', 'VIEWER', true],
    ['b.gone', 'CASHIER', false],
  ];
  const users = {};
  for (const [username, r, active] of staff) {
    const u = need(await c.api('POST', '/api/users', { token: admin, body: {
      username, email: `${username}@seed.local`, fullName: username.toUpperCase(), password: PW, roleId: role(r), active } }), `user ${username}`);
    users[username] = u;
  }
  const products = [];
  for (const [name, price] of [['Seed Widget', 1200], ['Seed Service', 5000], ['Seed Licence', 250]]) {
    products.push(need(await c.api('POST', '/api/products', { token: admin, body: { name, description: 'seed', price, active: true } }), `product ${name}`));
  }
  const customers = [];
  const terms = ['NET_30', 'NET_15', 'NET_60', 'DUE_ON_RECEIPT', null];
  for (let i = 1; i <= 10; i++) {
    const cu = need(await c.api('POST', '/api/customers', { token: admin, body: {
      name: `Seed Customer ${String(i).padStart(2, '0')}`, phone: `555-02${String(i).padStart(2, '0')}`,
      email: `seedcust${i}@seed.local`, address: `${i} Seed Street`, paymentTerm: terms[i % terms.length],
      username: `seedcust${i}`, password: PW } }), `customer ${i}`);
    customers.push(cu);
    if (i !== 10) { // customer 10 keeps no POC at all
      need(await c.api('POST', `/api/customers/${cu.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: users['b.coll'].id, primary: true } }), 'coll poc');
      if (i % 2) need(await c.api('POST', `/api/customers/${cu.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: users['b.succ'].id, primary: true } }), 'succ poc');
    }
  }
  const invoices = [];
  let k = 0;
  for (const cu of customers) {
    for (let j = 0; j < 4; j++) {
      k++;
      const back = [200, 95, 40, 3][j] + (k % 7);
      const d = new Date(); d.setUTCDate(d.getUTCDate() - back); d.setUTCHours(9, 0, 0, 0);
      const items = [{ productId: products[k % 3].id, quantity: 1 + (k % 4), unitPrice: products[k % 3].price },
        { productId: products[(k + 1) % 3].id, quantity: 1, unitPrice: 999.5 }];
      const inv = need(await c.api('POST', '/api/invoices', { token: admin, body: {
        customerId: cu.id, invoiceDate: d.toISOString(), paymentTerm: 'CUSTOM', dueDate: day(-back + 30),
        salesPocUserId: (k % 2 ? users['b.sales1'] : users['b.sales2']).id, notes: `seed ${k}`, items } }), `invoice ${k}`);
      invoices.push(inv);
    }
  }
  // Cancel two invoices.
  for (const inv of [invoices[3], invoices[17]]) need(await c.api('POST', `/api/invoices/${inv.id}/cancel`, { token: admin }), 'cancel');
  // Payments: full, partial, on account.
  const payments = [];
  for (let i = 0; i < customers.length - 1; i++) {
    const cu = customers[i];
    const own = invoices.filter((x) => x.customerId === cu.id);
    const first = own[0];
    const full = need(await c.api('POST', '/api/payments', { token: admin, body: {
      customerId: cu.id, amount: first.total, method: 'BANK_TRANSFER', notes: 'seed full', invoiceIds: [first.id],
      collectionPocUserId: users['b.coll'].id } }), `payment full ${i}`);
    payments.push(full);
    if (i % 2 === 0) {
      payments.push(need(await c.api('POST', '/api/payments', { token: admin, body: {
        customerId: cu.id, amount: Math.round(own[1].total / 3), method: 'UPI', notes: 'seed partial', invoiceIds: [own[1].id],
        collectionPocUserId: users['b.coll'].id } }), `payment partial ${i}`));
    }
    if (i === 4) {
      payments.push(need(await c.api('POST', '/api/payments', { token: admin, body: {
        customerId: cu.id, amount: 777, method: 'CASH', notes: 'seed on account', invoiceIds: [],
        collectionPocUserId: users['b.coll'].id } }), 'payment on account'));
    }
  }
  // Promises.
  const promises = [];
  for (let i = 0; i < 5; i++) {
    const cu = customers[i];
    const own = invoices.filter((x) => x.customerId === cu.id);
    promises.push(need(await c.api('POST', '/api/promises', { token: admin, body: {
      customerId: cu.id, amount: 1500 + i * 100, promisedDate: day(i === 0 ? -1 : 5 + i), collectionPocUserId: users['b.coll'].id,
      notes: `seed promise ${i}`, invoiceIds: [own[2].id] } }), `promise ${i}`));
  }
  // Disputes raised by customer logins; one denied by admin.
  const disputes = [];
  for (let i = 0; i < 3; i++) {
    const cu = customers[i];
    const tok = need(await c.login(`seedcust${i + 1}`, PW), 'cust login').token;
    const own = invoices.filter((x) => x.customerId === cu.id);
    disputes.push(need(await c.api('POST', '/api/disputes', { token: tok, body: {
      targetType: 'INVOICE', targetId: own[3].id, reason: `seed dispute ${i}`, proposedChangeJson: null } }), `dispute ${i}`));
  }
  need(await c.api('POST', `/api/disputes/${disputes[2].id}/deny`, { token: admin, body: { adminNotes: 'seed deny' } }), 'deny');
  return { users, products, customers, invoices, payments, promises, disputes };
}

module.exports = { seedBaseline, PW };
