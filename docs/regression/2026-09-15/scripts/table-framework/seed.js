// Seeds this tester's own data for the table-framework regression tests. Writes state.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const must = (r, what) => {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text.slice(0, 300)}`);
  return r.json;
};
const day = (offset, hh = 12) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offset);
  d.setUTCHours(hh, 0, 0, 0);
  return d;
};
const ymd = (d) => d.toISOString().slice(0, 10);

(async () => {
  const admin = await rt.adminToken();
  const P = rt.uniq('tfw'); // e.g. tfw-mf2k3abc12
  const S = {
    P, customers: [], special: {}, products: [], invoices: [], payments: [], promises: [],
    disputes: [], users: [], roles: [], warnings: [],
  };

  // staff
  S.sales = await rt.createStaff(admin, 'SALES_POC', `${P}s`);
  S.coll = await rt.createStaff(admin, 'COLLECTION_POC', `${P}k`);
  S.success = await rt.createStaff(admin, 'CUSTOMER_SUCCESS_POC', `${P}x`);
  S.myAdmin = await rt.createStaff(admin, 'ADMIN', `${P}a`);
  for (let i = 0; i < 10; i++) S.users.push(await rt.createStaff(admin, 'VIEWER', `${P}u`));
  // one inactive viewer
  must(await rt.api('PUT', `/api/users/${S.users[9].id}`, { token: admin, body: { active: false } }), 'deactivate viewer');

  // products
  const prices = [10, 25.5, 99.99, 5, 250, 10, 42, 7.25, 1000, 60, 60, 3];
  for (let i = 0; i < prices.length; i++) {
    const p = must(await rt.api('POST', '/api/products', {
      token: admin,
      body: {
        name: `${P}-prod-${String(i + 1).padStart(2, '0')}`,
        description: i === 0 ? 'Widget, "deluxe" edition' : (i % 3 === 0 ? null : `desc ${i}`),
        price: prices[i], active: true,
      },
    }), 'product');
    S.products.push(p);
  }
  for (const i of [3, 7]) {
    must(await rt.api('PUT', `/api/products/${S.products[i].id}`, {
      token: admin, body: { name: S.products[i].name, description: S.products[i].description, price: prices[i], active: false },
    }), 'deactivate product');
  }

  // customers
  for (let i = 0; i < 23; i++) {
    const nn = String(i + 1).padStart(2, '0');
    const c = must(await rt.api('POST', '/api/customers', {
      token: admin,
      body: {
        name: `${P}-cust-${nn}`,
        phone: i % 4 === 0 ? null : `555-01${nn}`,
        email: i % 5 === 0 ? null : `${P}c${nn}@rt.local`,
        address: i % 2 ? `Unit ${nn}: Main St, Springfield` : `${nn} Elm Rd`,
        username: `${P}c${nn}`, password: rt.PASSWORD,
      },
    }), 'customer');
    S.customers.push({ ...c, username: `${P}c${nn}`, password: rt.PASSWORD });
  }
  const mkSpecial = async (key, name) => {
    const c = must(await rt.api('POST', '/api/customers', {
      token: admin,
      body: { name, phone: '555-9999', email: `${P}${key}@rt.local`, address: 'x', username: `${P}${key}`, password: rt.PASSWORD },
    }), `special customer ${key}`);
    S.special[key] = c;
  };
  await mkSpecial('quote', `${P} O'Brien, "Q" 50%_off: x`);
  await mkSpecial('sqli', `${P} ' OR 1=1 --`);
  await mkSpecial('formula', `=${P}-formula`);
  await mkSpecial('pct', `${P} 100% done`);
  await mkSpecial('und', `${P} a_b`);

  const A = S.customers[0];
  const B = S.customers[1];
  S.A = A.id; S.B = B.id;

  // invoices for A: totals with duplicates, dates spread over presets
  const pid = S.products[0].id;
  const invSpec = [
    // [unitPrice, qty, dayOffset, notes]
    [100, 1, 0, 'Wire: ref, 50% off_now'], [100, 1, 0, null], [250, 1, -1, 'rush'], [50, 1, -1, null],
    [75.5, 1, -3, '   '], [100, 1, -3, 'rush'], [300, 1, -6, null], [50, 1, -8, 'late'],
    [100, 2, -10, null], [20, 3, -20, null], [1000, 1, -29, 'big'], [10, 1, -35, null],
    [100, 1, -45, null], [250, 1, -60, null], [5, 7, -100, null], [100, 1, -200, null],
    [42, 1, -400, null], [100, 1, -500, null], [60, 2, 5, 'future'], [60, 1, 30, null],
    [100, 1, -2, null], [50, 1, -4, null], [300, 1, -15, null], [99.99, 1, -25, null],
  ];
  for (const [price, qty, off, notes] of invSpec) {
    const r = await rt.api('POST', '/api/invoices', {
      token: admin,
      body: {
        customerId: A.id, invoiceDate: day(off, off === 0 ? 0 : 12).toISOString(), notes,
        salesPocUserId: S.sales.id, items: [{ productId: pid, quantity: qty, unitPrice: price }],
      },
    });
    if (r.status >= 300) { S.warnings.push(`invoice off=${off}: ${r.status} ${r.text.slice(0, 200)}`); continue; }
    S.invoices.push(r.json);
  }
  // B invoices (and an invoice with no-one else's POC)
  for (const t of [70, 80, 90]) {
    S.invoices.push(must(await rt.api('POST', '/api/invoices', {
      token: admin,
      body: { customerId: B.id, notes: 'b', salesPocUserId: S.sales.id, items: [{ productId: pid, quantity: 1, unitPrice: t }] },
    }), 'B invoice'));
  }
  for (const k of ['quote', 'formula', 'sqli']) {
    S.invoices.push(must(await rt.api('POST', '/api/invoices', {
      token: admin,
      body: { customerId: S.special[k].id, notes: `n,"${k}"`, salesPocUserId: S.sales.id, items: [{ productId: pid, quantity: 1, unitPrice: 11 }] },
    }), 'special invoice'));
  }

  const Ainv = S.invoices.filter((i) => i.customerId === A.id);
  // payments against A (partial and full)
  const pay = async (amount, invIdx, method, notes) => {
    const r = await rt.api('POST', '/api/payments', {
      token: admin,
      body: { customerId: A.id, amount, method, notes, invoiceIds: invIdx.map((i) => Ainv[i].id), collectionPocUserId: S.coll.id },
    });
    if (r.status >= 300) { S.warnings.push(`payment: ${r.status} ${r.text.slice(0, 200)}`); return; }
    S.payments.push(r.json);
  };
  await pay(100, [0], 'CASH', null);            // inv0 fully paid
  await pay(40, [2], 'Wire: ref,1', 'a "q"');  // inv2 partial
  await pay(25, [3], 'CARD', '50%_off');       // inv3 partial
  await pay(300, [6], 'CASH', null);           // inv6 fully
  await pay(10, [11], 'CHEQUE', null);         // inv11 fully
  await pay(33.3, [13], 'UPI', null);          // inv13 partial
  // cancel two unpaid invoices
  for (const i of [7, 15]) {
    must(await rt.api('POST', `/api/invoices/${Ainv[i].id}/cancel`, { token: admin }), 'cancel');
  }

  // promises for A
  const prom = async (amount, off, invIdx, notes) => {
    const r = await rt.api('POST', '/api/promises', {
      token: admin,
      body: { customerId: A.id, amount, promisedDate: ymd(day(off)), collectionPocUserId: S.coll.id, notes, invoiceIds: invIdx.map((i) => Ainv[i].id) },
    });
    if (r.status >= 300) { S.warnings.push(`promise off=${off}: ${r.status} ${r.text.slice(0, 200)}`); return; }
    S.promises.push(r.json);
  };
  await prom(50, -5, [1], 'call: back, "soon"');
  await prom(120, 0, [4], null);
  await prom(200, 3, [9], '50%');
  await prom(75, 30, [], 'general');
  await prom(500, -40, [12], null);
  await prom(10, 10, [1, 4], null);
  await prom(99, -1, [], null);

  // disputes (target INVOICE)
  for (const [i, reason] of [[1, 'Wrong total: see, "PO 12"'], [4, 'dup'], [9, "' OR 1=1 --"]]) {
    const r = await rt.api('POST', '/api/disputes', { token: admin, body: { targetType: 'INVOICE', targetId: Ainv[i].id, reason } });
    if (r.status >= 300) { S.warnings.push(`dispute: ${r.status} ${r.text.slice(0, 200)}`); continue; }
    S.disputes.push(r.json);
  }

  // roles
  for (let i = 1; i <= 3; i++) {
    S.roles.push(must(await rt.api('POST', '/api/roles', {
      token: admin,
      body: { name: `${P}-role-${i}`, description: i === 1 ? 'desc, with "quotes"' : null, privileges: ['INVOICE_VIEW'] },
    }), 'role'));
  }

  // POC seats -> also generates POC_ASSIGNED notifications for my coll / success users
  for (let i = 0; i < 15; i++) {
    const r = await rt.api('POST', `/api/customers/${S.customers[i].id}/pocs`, {
      token: admin, body: { pocType: 'COLLECTION', userId: S.coll.id, primary: true },
    });
    if (r.status >= 300) S.warnings.push(`seat coll ${i}: ${r.status} ${r.text.slice(0, 200)}`);
  }
  for (let i = 0; i < 8; i++) {
    const r = await rt.api('POST', `/api/customers/${S.customers[i].id}/pocs`, {
      token: admin, body: { pocType: 'SUCCESS', userId: S.success.id, primary: true },
    });
    if (r.status >= 300) S.warnings.push(`seat success ${i}: ${r.status} ${r.text.slice(0, 200)}`);
  }

  fs.writeFileSync(path.join(__dirname, 'state.json'), JSON.stringify(S, null, 2));
  console.log('prefix', P, 'A', A.id, 'B', B.id, 'invoices', S.invoices.length, 'payments', S.payments.length,
    'promises', S.promises.length, 'disputes', S.disputes.length, 'warnings', S.warnings);
})().catch((e) => { console.error('SEED FAILED', e); process.exit(1); });
