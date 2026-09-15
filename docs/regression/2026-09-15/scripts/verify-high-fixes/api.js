// Re-verifies the API side of the 12 high-severity defects after the fixes (D-01, D-02, D-03,
// D-05, D-07, D-08, D-09, D-10). Targets the regression environment (8083). Creates its own data.
// Run: node api.js   → prints a summary and writes api-results.json next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const results = [];
function check(id, defect, name, ok, detail) {
  results.push({ id, defect, name, status: ok ? 'PASS' : 'FAIL', detail });
}
async function step(id, defect, name, fn) {
  try {
    await fn();
  } catch (e) {
    results.push({ id, defect, name, status: 'ERROR', detail: String(e && e.stack || e) });
  }
}
const q = (params) => new URLSearchParams(params).toString();
const utcDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);

(async () => {
  const admin = await rt.adminToken();
  const cashier = await rt.cashierToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vh');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vh');
  const prod = (await rt.api('GET', '/api/products?' + q({ size: 10, filter: 'active:eq:true' }), { token: admin })).json.content[0];

  const invoice = (customerId, unitPrice, quantity = 1, salesPocUserId = sales.id) =>
    rt.api('POST', '/api/invoices', {
      token: admin,
      body: { customerId, salesPocUserId, items: [{ productId: prod.id, quantity, unitPrice }] },
    });
  const pay = (customerId, amount, invoiceIds, collectionPocUserId = coll.id) =>
    rt.api('POST', '/api/payments', {
      token: admin,
      body: { customerId, amount, method: 'Cash', collectionPocUserId, invoiceIds },
    });
  // Disputes are opened by the customer's own login and approved by staff.
  const approveDispute = async (customer, targetType, targetId, change) => {
    const d = await rt.api('POST', '/api/disputes', {
      token: await rt.login(customer.username, customer.password),
      body: { targetType, targetId, reason: 'high-fix verification', proposedChangeJson: JSON.stringify(change) },
    });
    if (d.status !== 200) throw new Error(`dispute create ${d.status} ${d.text}`);
    const a = await rt.api('POST', `/api/disputes/${d.json.id}/approve`, { token: admin, body: { adminNotes: 'ok' } });
    if (a.status !== 200) throw new Error(`dispute approve ${a.status} ${a.text}`);
  };
  const credit = async (cid) => Number((await rt.api('GET', `/api/payments/credits/${cid}`, { token: admin })).json.creditBalance);
  const paidOf = async (id) => Number((await rt.api('GET', `/api/invoices/${id}`, { token: admin })).json.paidAmount);
  const books = async (cid) => {
    const invs = (await rt.api('GET', '/api/invoices?' + q({ size: 50, filter: `customerId:eq:${cid}` }), { token: admin })).json.content;
    const pays = (await rt.api('GET', '/api/payments?' + q({ size: 50, filter: `customerId:eq:${cid}` }), { token: admin })).json.content;
    const onInvoices = invs.filter((i) => i.status !== 'CANCELLED').reduce((s, i) => s + Number(i.paidAmount), 0);
    const collected = pays.filter((p) => p.status === 'ACTIVE').reduce((s, p) => s + Number(p.amount), 0);
    const c = await credit(cid);
    return { onInvoices, credit: c, collected, balanced: Math.abs(onInvoices + c - collected) < 0.005 };
  };

  // ---- D-01 ------------------------------------------------------------------------------
  await step('V-01', 'D-01', 'a deactivated user\'s existing token is refused everywhere', async () => {
    const u = await rt.createStaff(admin, 'CASHIER', 'vh-d01');
    const tok = await rt.login(u.username, u.password);
    const before = await rt.api('GET', '/api/auth/me', { token: tok });
    const deact = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
    const me = await rt.api('GET', '/api/auth/me', { token: tok });
    const list = await rt.api('GET', '/api/invoices', { token: tok });
    const write = await rt.api('POST', '/api/customers', { token: tok, body: { name: rt.uniq('vh-ghost') } });
    const exp = await rt.api('POST', '/api/invoices/export', { token: tok, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    check('V-01', 'D-01', 'deactivated token → 401 on /me, list, write, export',
      before.status === 200 && deact.status < 300 && [me, list, write, exp].every((r) => r.status === 401),
      { before: before.status, deactivate: deact.status, me: me.status, list: list.status, write: write.status, export: exp.status });
  });
  await step('V-02', 'D-01', 'a user deactivated by DELETE as a named POC loses access', async () => {
    const u = await rt.createStaff(admin, 'SALES_POC', 'vh-d01b');
    const tok = await rt.login(u.username, u.password);
    const c = await rt.createCustomer(admin, 'vh-d01b');
    await invoice(c.id, 10, 1, u.id);
    const del = await rt.api('DELETE', `/api/users/${u.id}`, { token: admin });
    const list = await rt.api('GET', '/api/invoices', { token: tok });
    check('V-02', 'D-01', 'POC deleted → deactivated → old token 401',
      del.json && del.json.deactivated === true && list.status === 401, { delete: del.json, list: list.status });
  });

  // ---- D-02 ------------------------------------------------------------------------------
  await step('V-03', 'D-02', 'exports need the table\'s view privilege', async () => {
    const collTok = await rt.login(coll.username, coll.password);
    const all = { action: 'EXPORT', selectAllMatchingFilter: true };
    const got = {};
    for (const t of ['users', 'roles', 'disputes']) got['cashier ' + t] = (await rt.api('POST', `/api/${t}/export`, { token: cashier, body: all })).status;
    got['collectionPoc products'] = (await rt.api('POST', '/api/products/export', { token: collTok, body: all })).status;
    got['cashier invoices'] = (await rt.api('POST', '/api/invoices/export', { token: cashier, body: all })).status;
    for (const t of ['users', 'roles', 'disputes', 'products', 'invoices', 'payments', 'customers', 'promises']) {
      got['admin ' + t] = (await rt.api('POST', `/api/${t}/export`, { token: admin, body: all })).status;
    }
    const denied = ['cashier users', 'cashier roles', 'cashier disputes', 'collectionPoc products'];
    const ok = denied.every((k) => got[k] === 403)
      && got['cashier invoices'] === 200
      && Object.keys(got).filter((k) => k.startsWith('admin')).every((k) => got[k] === 200);
    check('V-03', 'D-02', '403 without view privilege; 200 with it', ok, got);
  });

  // ---- D-03 ------------------------------------------------------------------------------
  await step('V-04', 'D-03', '(a) void after a dispute cancel refunded the invoice to credit', async () => {
    const c = await rt.createCustomer(admin, 'vh-d03a');
    const inv = (await invoice(c.id, 100)).json;
    const p = (await pay(c.id, 100, [inv.id])).json;
    await approveDispute(c, 'INVOICE', inv.id, { action: 'cancel' });
    const creditAfterCancel = await credit(c.id);
    await approveDispute(c, 'PAYMENT', p.id, { action: 'void' });
    const b = await books(c.id);
    check('V-04', 'D-03', 'credit 100 → 0 after void; books balance',
      creditAfterCancel === 100 && b.credit === 0 && b.balanced, { creditAfterCancel, ...b });
  });
  await step('V-05', 'D-03', '(b) void after replace_items refunded to credit a later invoice spent', async () => {
    const c = await rt.createCustomer(admin, 'vh-d03b');
    const inv = (await invoice(c.id, 100, 3)).json;
    const p = (await pay(c.id, 300, [inv.id])).json;
    await approveDispute(c, 'INVOICE', inv.id, { action: 'replace_items', items: [{ productId: prod.id, quantity: 1, unitPrice: 50 }] });
    const creditAfterEdit = await credit(c.id);
    const later = (await invoice(c.id, 100, 2)).json;
    const laterPaidBefore = await paidOf(later.id);
    await approveDispute(c, 'PAYMENT', p.id, { action: 'void' });
    const b = await books(c.id);
    const firstPaid = await paidOf(inv.id);
    const laterPaid = await paidOf(later.id);
    check('V-05', 'D-03', 'credit 250 → later invoice paid 200 → void un-pays both, credit 0',
      creditAfterEdit === 250 && laterPaidBefore === 200 && firstPaid === 0 && laterPaid === 0 && b.credit === 0 && b.balanced,
      { creditAfterEdit, laterPaidBefore, firstPaid, laterPaid, ...b });
  });
  await step('V-06', 'D-03', '(c) void an overpayment whose credit paid a later invoice', async () => {
    const c = await rt.createCustomer(admin, 'vh-d03c');
    const first = (await invoice(c.id, 23)).json;
    const p = (await pay(c.id, 146)).json;
    const creditAfterPay = await credit(c.id);
    const later = (await invoice(c.id, 123)).json;
    const laterPaidBefore = await paidOf(later.id);
    const payment = (await rt.api('GET', `/api/payments/${p.id}`, { token: admin })).json;
    await approveDispute(c, 'PAYMENT', p.id, { action: 'void' });
    const b = await books(c.id);
    check('V-06', 'D-03', 'credit 123 pays L; void → L unpaid, first unpaid, credit 0',
      creditAfterPay === 123 && laterPaidBefore === 123 && (await paidOf(first.id)) === 0 && (await paidOf(later.id)) === 0
        && b.credit === 0 && b.balanced,
      { creditAfterPay, laterPaidBefore, paymentInvoicesBeforeVoid: payment.invoices, ...b });
  });

  // ---- D-05 ------------------------------------------------------------------------------
  await step('V-07', 'D-05', 'six invoices created at once get distinct numbers', async () => {
    const c = await rt.createCustomer(admin, 'vh-d05');
    const runs = [];
    for (let round = 0; round < 2; round++) {
      const rs = await Promise.all(Array.from({ length: 6 }, () => invoice(c.id, 10)));
      runs.push({ statuses: rs.map((r) => r.status), numbers: rs.map((r) => r.json && r.json.invoiceNumber) });
    }
    const ok = runs.every((r) => r.statuses.every((s) => s === 200) && new Set(r.numbers).size === 6);
    check('V-07', 'D-05', '2 rounds × 6 concurrent creates → all 200, unique', ok, runs);
  });

  // ---- D-07 ------------------------------------------------------------------------------
  await step('V-08', 'D-07', 'notes-only save with a deactivated POC', async () => {
    const s2 = await rt.createStaff(admin, 'SALES_POC', 'vh-d07');
    const c2 = await rt.createStaff(admin, 'COLLECTION_POC', 'vh-d07');
    const c = await rt.createCustomer(admin, 'vh-d07');
    const inv = (await invoice(c.id, 50, 1, s2.id)).json;
    const p = (await pay(c.id, 10, null, c2.id)).json;
    await rt.api('DELETE', `/api/users/${s2.id}`, { token: admin });
    await rt.api('DELETE', `/api/users/${c2.id}`, { token: admin });
    const ri = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: admin, body: { notes: 'inv note', salesPocUserId: s2.id } });
    const rp = await rt.api('PATCH', `/api/payments/${p.id}`, { token: admin, body: { notes: 'pay note', collectionPocUserId: c2.id } });
    check('V-08', 'D-07', 'PATCH with unchanged inactive POC → 200 and notes saved',
      ri.status === 200 && ri.json.notes === 'inv note' && rp.status === 200 && rp.json.notes === 'pay note',
      { invoice: [ri.status, ri.json && ri.json.message], payment: [rp.status, rp.json && rp.json.message] });
  });
  await step('V-09', 'D-07', 'notes-only save for a role without POC_ASSIGN; a real change still refused', async () => {
    const roleName = rt.uniq('vh-clerk');
    const role = await rt.api('POST', '/api/roles', {
      token: admin,
      body: { name: roleName, description: 'no POC_ASSIGN', privileges: ['INVOICE_VIEW', 'INVOICE_MANAGE', 'PAYMENT_VIEW', 'PAYMENT_MANAGE'] },
    });
    if (role.status !== 200) throw new Error(`role create ${role.status} ${role.text}`);
    const username = rt.uniq('vh-clerk');
    const u = await rt.api('POST', '/api/users', {
      token: admin,
      body: { username, email: `${username}@rt.local`, fullName: username, password: 'Passw0rd!', roleId: role.json.id, active: true },
    });
    if (u.status >= 300) throw new Error(`user create ${u.status} ${u.text}`);
    const tok = await rt.login(username, 'Passw0rd!');
    const c = await rt.createCustomer(admin, 'vh-d07b');
    const inv = (await invoice(c.id, 50)).json;
    const p = (await pay(c.id, 10)).json;
    const other = await rt.createStaff(admin, 'SALES_POC', 'vh-d07b');
    const same = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: tok, body: { notes: 'clerk', salesPocUserId: sales.id } });
    const samePay = await rt.api('PATCH', `/api/payments/${p.id}`, { token: tok, body: { notes: 'clerk', collectionPocUserId: coll.id } });
    const change = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: tok, body: { notes: 'clerk', salesPocUserId: other.id } });
    check('V-09', 'D-07', 'same POC → 200; changed POC → 400',
      same.status === 200 && samePay.status === 200 && change.status === 400,
      { same: same.status, samePayment: samePay.status, change: [change.status, change.json && change.json.message] });
  });

  // ---- D-08 ------------------------------------------------------------------------------
  await step('V-10', 'D-08', 'customer logins cannot filter or sort on POC columns', async () => {
    const c = await rt.createCustomer(admin, 'vh-d08');
    await invoice(c.id, 10);
    const tok = await rt.login(c.username, c.password);
    const probes = [
      '/api/invoices?' + q({ filter: `salesPocUserId:eq:${sales.id}` }),
      '/api/invoices?' + q({ filter: 'salesPocName:contains:V' }),
      '/api/invoices?' + q({ sort: 'salesPocName,asc' }),
      '/api/invoices/summary?' + q({ filter: `salesPocUserId:eq:${sales.id}` }),
      '/api/payments?' + q({ filter: `collectionPocUserId:eq:${coll.id}` }),
      '/api/promises?' + q({ filter: `collectionPocUserId:eq:${coll.id}` }),
      '/api/customers?' + q({ filter: `successPocUserId:eq:${coll.id}` }),
    ];
    const got = {};
    for (const pth of probes) {
      const r = await rt.api('GET', pth, { token: tok });
      got[pth] = [r.status, r.json && r.json.message];
    }
    const plain = await rt.api('GET', '/api/invoices', { token: tok });
    const staff = await rt.api('GET', '/api/invoices?' + q({ filter: `salesPocUserId:eq:${sales.id}` }), { token: admin });
    check('V-10', 'D-08', 'every probe 400 Unknown column; plain list 200; staff filter 200',
      Object.values(got).every(([s, m]) => s === 400 && /Unknown column/.test(m || '')) && plain.status === 200 && staff.status === 200,
      { probes: got, plainList: plain.status, staffFilter: staff.status });
  });

  // ---- D-09 ------------------------------------------------------------------------------
  await step('V-11', 'D-09', 'a kept general promise stays kept when a later invoice is raised', async () => {
    const c = await rt.createCustomer(admin, 'vh-d09');
    const pr = await rt.api('POST', '/api/promises', {
      token: admin,
      body: { customerId: c.id, amount: 500, promisedDate: utcDay(-2), collectionPocUserId: coll.id, notes: 'v', invoiceIds: [] },
    });
    const before = pr.json && pr.json.status;
    await invoice(c.id, 300);
    const after = (await rt.api('GET', `/api/promises/${pr.json.id}`, { token: admin })).json.status;
    const collTok = await rt.login(coll.username, coll.password);
    const notes = (await rt.api('GET', '/api/notifications?' + q({ size: 50 }), { token: collTok })).json.content;
    const broken = notes.filter((n) => n.link === `/promises/${pr.json.id}`);
    check('V-11', 'D-09', 'KEPT → still KEPT, no broken notification',
      before === 'KEPT' && after === 'KEPT' && broken.length === 0, { before, after, brokenNotifications: broken.length });
  });

  // ---- D-10 ------------------------------------------------------------------------------
  await step('V-12', 'D-10', 'a promise stays editable after one of its invoices is cancelled', async () => {
    const c = await rt.createCustomer(admin, 'vh-d10');
    const a = (await invoice(c.id, 100)).json;
    const b = (await invoice(c.id, 100)).json;
    const x = (await invoice(c.id, 100)).json;
    const body = (notes, invoiceIds, amount = 200) =>
      ({ customerId: c.id, amount, promisedDate: utcDay(7), collectionPocUserId: coll.id, notes, invoiceIds });
    const pr = (await rt.api('POST', '/api/promises', { token: admin, body: body('v', [a.id, b.id]) })).json;
    await rt.api('POST', `/api/invoices/${b.id}/cancel`, { token: admin });
    await rt.api('POST', `/api/invoices/${x.id}/cancel`, { token: admin });
    const edit = await rt.api('PUT', `/api/promises/${pr.id}`, { token: admin, body: body('edited', [a.id, b.id]) });
    const untick = await rt.api('PUT', `/api/promises/${pr.id}`, { token: admin, body: body('edited', [a.id], 100) });
    const addCancelled = await rt.api('PUT', `/api/promises/${pr.id}`, { token: admin, body: body('edited', [a.id, x.id], 100) });
    check('V-12', 'D-10', 'edit with [A, cancelled B] 200; untick 200; newly add cancelled 400',
      edit.status === 200 && edit.json.notes === 'edited' && untick.status === 200 && addCancelled.status === 400,
      { edit: [edit.status, edit.json && edit.json.message], untick: untick.status, addCancelled: [addCancelled.status, addCancelled.json && addCancelled.json.message] });
  });

  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
