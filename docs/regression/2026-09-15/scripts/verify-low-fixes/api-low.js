// Re-verifies the API side of the low-severity fixes: D-40 to D-48 and D-53. The UI-only low
// defects are checked in the browser. Targets 8083, own data.
// Run: node api-low.js → prints a summary and writes api-low-results.json next to this file.
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
    results.push({ id, defect, name, status: 'ERROR', detail: String((e && e.stack) || e) });
  }
}

(async () => {
  const admin = await rt.adminToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'lo-sales');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'lo-coll');
  const roles = (await rt.api('GET', '/api/roles?size=50', { token: admin })).json.content;
  const viewerRoleId = roles.find((r) => r.name === 'VIEWER').id;
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];
  const invoice = async (customerId, productId = prod.id) => (await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId, salesPocUserId: sales.id, items: [{ productId, quantity: 1, unitPrice: 100 }] },
  }));

  // ---- D-40 -------------------------------------------------------------------------------
  await step('L-01', 'D-40', 'paging and sort validation', async () => {
    const badDir = await rt.api('GET', '/api/invoices?size=10&sort=invoiceDate,sideways', { token: admin });
    const okDir = await rt.api('GET', '/api/invoices?size=10&sort=invoiceDate,DESC', { token: admin });
    const huge = await rt.api('GET', '/api/invoices?size=10&page=2147483647', { token: admin });
    check('L-01', 'D-40', 'an invalid sort direction → 400; a page past int arithmetic → empty page, not 500',
      badDir.status === 400 && /asc or desc/.test(badDir.json && badDir.json.message)
        && okDir.status === 200 && huge.status === 200 && huge.json.content.length === 0,
      { badDirection: [badDir.status, badDir.json && badDir.json.message],
        upperCaseDesc: okDir.status, hugePage: [huge.status, huge.json && huge.json.content.length] });
  });

  // ---- D-41 -------------------------------------------------------------------------------
  await step('L-02', 'D-41', 'an export follows the requested sort', async () => {
    for (const name of ['lo-zeta', 'lo-alpha', 'lo-mu']) {
      await rt.api('POST', '/api/products', { token: admin, body: { name: `${name}-${Date.now()}`, price: 10 } });
    }
    const res = await fetch(rt.API + '/api/products/export', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + admin, 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'EXPORT', selectAllMatchingFilter: true, sort: 'name,asc' }),
    });
    const csv = await res.text();
    const at = (s) => csv.indexOf(s);
    check('L-02', 'D-41', 'products export sorted by name comes back in that order',
      res.status === 200 && at('lo-alpha') < at('lo-mu') && at('lo-mu') < at('lo-zeta'),
      { status: res.status, alpha: at('lo-alpha'), mu: at('lo-mu'), zeta: at('lo-zeta') });
  });

  // ---- D-42 -------------------------------------------------------------------------------
  await step('L-03', 'D-42', 'the password rule is the same everywhere', async () => {
    const short = await rt.api('POST', '/api/users', {
      token: admin, body: { username: rt.uniq('lo-short'), password: 'abc12', roleId: viewerRoleId },
    });
    const ok = await rt.api('POST', '/api/users', {
      token: admin, body: { username: rt.uniq('lo-ok'), password: 'abc123', roleId: viewerRoleId },
    });
    const shortCustomer = await rt.api('POST', '/api/customers', {
      token: admin, body: { name: rt.uniq('lo-short-cust'), username: rt.uniq('lo-short-login'), password: 'abc12' },
    });
    check('L-03', 'D-42', 'a 5-character password is refused on users and customer logins; 6 is accepted',
      short.status === 400 && /at least 6 characters/.test(short.json && short.json.message)
        && ok.status === 200 && shortCustomer.status === 400,
      { user: [short.status, short.json && short.json.message], accepted: ok.status, customer: shortCustomer.status });
  });

  // ---- D-43 -------------------------------------------------------------------------------
  await step('L-04', 'D-43', 'deleting a role that is not there', async () => {
    const gone = await rt.api('DELETE', '/api/roles/99999999', { token: admin });
    check('L-04', 'D-43', 'DELETE /api/roles/{unknown} → 404', gone.status === 404, { status: gone.status });
  });

  // ---- D-44 -------------------------------------------------------------------------------
  await step('L-05', 'D-44', 'an automatic primary-POC change is audited', async () => {
    const cust = await rt.createCustomer(admin, 'lo-poc');
    const second = await rt.createStaff(admin, 'COLLECTION_POC', 'lo-coll2');
    await rt.api('POST', `/api/customers/${cust.id}/pocs`, {
      token: admin, body: { pocType: 'COLLECTION', userId: coll.id, primary: true } });
    await rt.api('POST', `/api/customers/${cust.id}/pocs`, {
      token: admin, body: { pocType: 'COLLECTION', userId: second.id, primary: true } });
    const audit = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${cust.id}`, { token: admin });
    const rows = Array.isArray(audit.json) ? audit.json : (audit.json.content || []);
    check('L-05', 'D-44', 'taking over as primary writes POC_PRIMARY_CHANGED',
      rows.some((r) => r.action === 'POC_PRIMARY_CHANGED'),
      { actions: rows.map((r) => r.action).slice(0, 8) });
  });

  // ---- D-45, D-46 -------------------------------------------------------------------------
  await step('L-06', 'D-45/D-46', 'an ADD_POC request that cannot work', async () => {
    const cust = await rt.createCustomer(admin, 'lo-bulk');
    const wrongType = await rt.api('POST', '/api/customers/bulk', {
      token: admin,
      body: { action: 'ADD_POC', ids: [cust.id], params: { userId: coll.id, pocType: 'SALES' } },
    });

    const roleName = rt.uniq('LO_CM_ONLY').toUpperCase().replace(/-/g, '_');
    await rt.api('POST', '/api/roles', {
      token: admin, body: { name: roleName, privileges: ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE'] } });
    const roleId = (await rt.api('GET', '/api/roles?size=50', { token: admin }))
      .json.content.find((r) => r.name === roleName).id;
    const username = rt.uniq('lo-manager');
    await rt.api('POST', '/api/users', { token: admin, body: { username, password: rt.PASSWORD, roleId } });
    const managerToken = await rt.login(username, rt.PASSWORD);
    const denied = await rt.api('POST', '/api/customers/bulk', {
      token: managerToken,
      body: { action: 'ADD_POC', ids: [cust.id], params: { userId: coll.id, pocType: 'COLLECTION' } },
    });

    check('L-06', 'D-45/D-46', 'wrong POC type → one 400; no POC_ASSIGN → 403',
      wrongType.status === 400 && denied.status === 403,
      { wrongType: [wrongType.status, wrongType.json && wrongType.json.message], withoutPocAssign: denied.status });
  });

  // ---- D-47 -------------------------------------------------------------------------------
  await step('L-07', 'D-47', 'an inactive product cannot go on a new invoice', async () => {
    const cust = await rt.createCustomer(admin, 'lo-inactive');
    const retired = (await rt.api('POST', '/api/products', {
      token: admin, body: { name: rt.uniq('lo-retired'), price: 50 } })).json;
    await rt.api('POST', '/api/products/bulk', {
      token: admin, body: { action: 'DEACTIVATE', ids: [retired.id] } });
    const attempt = await invoice(cust.id, retired.id);
    check('L-07', 'D-47', 'a deactivated product on a new invoice line → 400',
      attempt.status === 400 && /no longer an active product/.test(attempt.json && attempt.json.message),
      { status: attempt.status, message: attempt.json && attempt.json.message });
  });

  // ---- D-48 -------------------------------------------------------------------------------
  await step('L-08', 'D-48', 'an unknown invoiceId on a payment', async () => {
    const cust = await rt.createCustomer(admin, 'lo-unknown-inv');
    const inv = (await invoice(cust.id)).json;
    const attempt = await rt.api('POST', '/api/payments', {
      token: admin,
      body: { customerId: cust.id, amount: 25, method: 'Cash', collectionPocUserId: coll.id,
        invoiceIds: [inv.id, 99999999] },
    });
    const payments = (await rt.api('GET', `/api/payments?size=10&filter=customerId:eq:${cust.id}`, { token: admin })).json;
    const after = (await rt.api('GET', `/api/customers/${cust.id}`, { token: admin })).json;
    check('L-08', 'D-48', 'an id that matches no invoice → 404; no payment and no credit created',
      attempt.status === 404 && payments.totalElements === 0 && Number(after.creditBalance) === 0,
      { status: attempt.status, message: attempt.json && attempt.json.message,
        payments: payments.totalElements, credit: after.creditBalance });
  });

  // ---- D-53 -------------------------------------------------------------------------------
  await step('L-09', 'D-53', 'the dispute notification links to a real route', async () => {
    const cust = await rt.createCustomer(admin, 'lo-dispute');
    const inv = (await invoice(cust.id)).json;
    const custToken = await rt.login(cust.username, cust.password);
    const dispute = await rt.api('POST', '/api/disputes', {
      token: custToken, body: { targetType: 'INVOICE', targetId: inv.id, reason: 'lo-dispute link check' },
    });
    const notes = (await rt.api('GET', '/api/notifications?size=50', { token: admin })).json;
    const note = (notes.content || notes).find((n) => n.link && n.link.endsWith('/' + dispute.json.id));
    check('L-09', 'D-53', 'DISPUTE_OPENED links to /disputes/{id}, not /admin/disputes/{id}',
      note != null && note.link === `/disputes/${dispute.json.id}`,
      { dispute: dispute.status, link: note && note.link });
  });

  fs.writeFileSync(path.join(__dirname, 'api-low-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
