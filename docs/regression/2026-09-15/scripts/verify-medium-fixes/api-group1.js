// Re-verifies group 1 of the medium defects (error handling and input validation): D-13, D-27,
// D-28, D-29, D-30, D-32. Targets the regression environment (8083) and creates its own data.
// Run: node api-group1.js → prints a summary and writes api-group1-results.json next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const API = 'http://localhost:8083';
const results = [];
function check(id, defect, name, ok, detail) {
  results.push({ id, defect, name, status: ok ? 'PASS' : 'FAIL', detail });
}
async function step(id, defect, name, fn) {
  try {
    await fn();
  } catch (e) {
    results.push({ id, defect, name, status: 'ERROR', detail: String((e && e.stack) || e) });
  }
}
const leaks = (r) => /java\.|com\.geneinvoice|Exception/.test(r.text || '');
const summary = (r) => [r.status, (r.json && (r.json.message || JSON.stringify(r.json.fieldErrors))) || r.text.slice(0, 80)];
const raw = async (method, p, token, body, contentType = 'application/json') => {
  const res = await fetch(API + p, {
    method, headers: { Authorization: 'Bearer ' + token, 'Content-Type': contentType }, body,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, text, json };
};

(async () => {
  const admin = await rt.adminToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vm1');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vm1');
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];
  const cust = await rt.createCustomer(admin, 'vm1');
  const custToken = await rt.login(cust.username, cust.password);
  const invoice = (await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId: cust.id, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: 100 }] },
  })).json;
  const payment = (await rt.api('POST', '/api/payments', {
    token: admin, body: { customerId: cust.id, amount: 40, method: 'Cash', collectionPocUserId: coll.id },
  })).json;

  // ---- D-13 ------------------------------------------------------------------------------
  await step('M1-01', 'D-13', 'malformed input is a 4xx without internal detail', async () => {
    const got = {
      'GET /invoices/abc': await rt.api('GET', '/api/invoices/abc', { token: admin }),
      'GET /invoices?size=abc': await rt.api('GET', '/api/invoices?size=abc', { token: admin }),
      'GET /pocs/assignable?type=bogus': await rt.api('GET', '/api/pocs/assignable?type=bogus', { token: admin }),
      'GET /pocs/assignable (no type)': await rt.api('GET', '/api/pocs/assignable', { token: admin }),
      'POST /products price abc': await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq('vm1'), price: 'abc' } }),
      'POST /products {bad json': await raw('POST', '/api/products', admin, '{bad json'),
      'POST /override status FOO': await rt.api('POST', '/api/promises/999999/override', { token: admin, body: { status: 'FOO', reason: 'r' } }),
      'bulk ADD_POC pocType foo': await rt.api('POST', '/api/customers/bulk', { token: admin, body: { action: 'ADD_POC', ids: [cust.id], params: { pocType: 'foo', userId: coll.id } } }),
      'bulk ADD_POC userId abc': await rt.api('POST', '/api/customers/bulk', { token: admin, body: { action: 'ADD_POC', ids: [cust.id], params: { pocType: 'SUCCESS', userId: 'abc' } } }),
      'DELETE /invoices (405)': await rt.api('DELETE', '/api/invoices', { token: admin }),
      'POST /products text/plain (415)': await raw('POST', '/api/products', admin, 'x', 'text/plain'),
      'GET /api/no-such-thing (404)': await rt.api('GET', '/api/no-such-thing', { token: admin }),
    };
    const d = (await rt.api('POST', '/api/disputes', { token: custToken, body: { targetType: 'PAYMENT', targetId: payment.id, reason: 'vm1' } })).json;
    got['dispute update_amount abc'] = await rt.api('POST', `/api/disputes/${d.id}/approve`, {
      token: admin, body: { appliedChangeJson: JSON.stringify({ action: 'update_amount', amount: 'abc' }) },
    });
    const expected = { 'DELETE /invoices (405)': 405, 'POST /products text/plain (415)': 415, 'GET /api/no-such-thing (404)': 404 };
    const ok = Object.entries(got).every(([k, r]) => r.status === (expected[k] || 400) && !leaks(r));
    check('M1-01', 'D-13', 'each malformed request → expected 4xx, no class names', ok,
      Object.fromEntries(Object.entries(got).map(([k, r]) => [k, summary(r)])));
  });

  // ---- D-27 ------------------------------------------------------------------------------
  await step('M1-02', 'D-27', 'duplicates and a role in use get a clear 400', async () => {
    const a = await rt.createStaff(admin, 'VIEWER', 'vm1-a');
    const b = await rt.createStaff(admin, 'VIEWER', 'vm1-b');
    const roleName = rt.uniq('vm1-role');
    const role = (await rt.api('POST', '/api/roles', { token: admin, body: { name: roleName, privileges: ['INVOICE_VIEW'] } })).json;
    const holderName = rt.uniq('vm1-holder');
    await rt.api('POST', '/api/users', { token: admin, body: { username: holderName, email: `${holderName}@rt.local`, password: 'Passw0rd!', roleId: role.id } });
    const got = {
      dupEmailCreate: await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vm1-c'), email: a.email.toUpperCase(), password: 'Passw0rd!', roleId: role.id } }),
      dupEmailUpdate: await rt.api('PUT', `/api/users/${b.id}`, { token: admin, body: { email: a.email } }),
      dupRoleName: await rt.api('POST', '/api/roles', { token: admin, body: { name: 'viewer', privileges: [] } }),
      deleteRoleInUse: await rt.api('DELETE', `/api/roles/${role.id}`, { token: admin }),
      deleteUnknownRole: await rt.api('DELETE', '/api/roles/99999999', { token: admin }),
    };
    const ok = got.dupEmailCreate.status === 400 && got.dupEmailCreate.json.message === 'Email already exists'
      && got.dupEmailUpdate.status === 400 && got.dupRoleName.status === 400 && got.dupRoleName.json.message === 'Role name already exists'
      && got.deleteRoleInUse.status === 400 && /assigned to 1 user/.test(got.deleteRoleInUse.json.message)
      && got.deleteUnknownRole.status === 404;
    check('M1-02', 'D-27', 'email/role-name clash and role in use → 400 with message; unknown role → 404', ok,
      Object.fromEntries(Object.entries(got).map(([k, r]) => [k, summary(r)])));
  });

  // ---- D-28 ------------------------------------------------------------------------------
  await step('M1-03', 'D-28', 'blank emails are stored as none and never collide', async () => {
    const viewerRoleId = (await rt.api('GET', '/api/roles?size=50&filter=name:eq:VIEWER', { token: admin })).json.content[0].id;
    const one = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vm1-blank'), email: '', password: 'Passw0rd!', roleId: viewerRoleId } });
    const two = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vm1-blank'), email: '', password: 'Passw0rd!', roleId: viewerRoleId } });
    const deact = await rt.api('PUT', `/api/users/${two.json.id}`, { token: admin, body: { email: '', active: false } });
    const react = await rt.api('PUT', `/api/users/${two.json.id}`, { token: admin, body: { email: '', active: true } });
    check('M1-03', 'D-28', 'two blank-email users 200 with null email; edit/reactivate 200',
      one.status === 200 && one.json.email === null && two.status === 200 && deact.status === 200 && react.status === 200 && react.json.active === true,
      { one: summary(one), two: summary(two), deactivate: deact.status, reactivate: react.status });
  });

  // ---- D-29 ------------------------------------------------------------------------------
  await step('M1-04', 'D-29', 'overlong text is a 400 field error', async () => {
    const viewerRoleId = (await rt.api('GET', '/api/roles?size=50&filter=name:eq:VIEWER', { token: admin })).json.content[0].id;
    const got = {
      username81: await rt.api('POST', '/api/users', { token: admin, body: { username: 'u'.repeat(81), password: 'Passw0rd!', roleId: viewerRoleId } }),
      invoiceNotes501: await rt.api('PATCH', `/api/invoices/${invoice.id}`, { token: admin, body: { notes: 'x'.repeat(501) } }),
      invoiceNotes500: await rt.api('PATCH', `/api/invoices/${invoice.id}`, { token: admin, body: { notes: 'x'.repeat(500) } }),
      paymentNotes301: await rt.api('PATCH', `/api/payments/${payment.id}`, { token: admin, body: { notes: 'x'.repeat(301) } }),
    };
    const ok = got.username81.status === 400 && got.username81.json.fieldErrors.username
      && got.invoiceNotes501.status === 400 && got.invoiceNotes501.json.fieldErrors.notes
      && got.invoiceNotes500.status === 200
      && got.paymentNotes301.status === 400 && got.paymentNotes301.json.fieldErrors.notes;
    check('M1-04', 'D-29', 'username 81, invoice notes 501, payment notes 301 → 400 field errors; 500 → 200', ok,
      Object.fromEntries(Object.entries(got).map(([k, r]) => [k, summary(r)])));
  });

  // ---- D-30 ------------------------------------------------------------------------------
  await step('M1-05', 'D-30', 'payment amounts must be whole cents', async () => {
    const pay = (amount) => rt.api('POST', '/api/payments', { token: admin, body: { customerId: cust.id, amount, method: 'Cash', collectionPocUserId: coll.id } });
    const tiny = await pay(0.001);
    const fine = await pay(10.555);
    const ok2 = await pay(10.55);
    const stored = ok2.status === 200 ? (await rt.api('GET', `/api/payments/${ok2.json.id}`, { token: admin })).json.amount : null;
    check('M1-05', 'D-30', '0.001 and 10.555 → 400; 10.55 → 200 and stored as 10.55',
      tiny.status === 400 && fine.status === 400 && ok2.status === 200 && Number(stored) === 10.55,
      { tiny: summary(tiny), fine: summary(fine), ok: ok2.status, stored });
  });

  // ---- D-32 ------------------------------------------------------------------------------
  await step('M1-06', 'D-32', 'a dispute cannot set quantity 0 or negative', async () => {
    const d = (await rt.api('POST', '/api/disputes', { token: custToken, body: { targetType: 'INVOICE', targetId: invoice.id, reason: 'vm1' } })).json;
    const attempt = (quantity) => rt.api('POST', `/api/disputes/${d.id}/approve`, {
      token: admin, body: { appliedChangeJson: JSON.stringify({ action: 'replace_items', items: [{ productId: prod.id, quantity }] }) },
    });
    const zero = await attempt(0);
    const neg = await attempt(-2);
    const inv = (await rt.api('GET', `/api/invoices/${invoice.id}`, { token: admin })).json;
    const dispute = (await rt.api('GET', `/api/disputes/${d.id}`, { token: admin })).json;
    check('M1-06', 'D-32', 'qty 0 / -2 → 400; invoice total unchanged; dispute still PENDING',
      zero.status === 400 && neg.status === 400 && Number(inv.total) === 100 && dispute.status === 'PENDING',
      { zero: summary(zero), negative: summary(neg), total: inv.total, dispute: dispute.status });
  });

  fs.writeFileSync(path.join(__dirname, 'api-group1-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
