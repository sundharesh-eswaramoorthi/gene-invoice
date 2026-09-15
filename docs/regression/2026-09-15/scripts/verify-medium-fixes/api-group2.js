// Re-verifies group 2 of the medium defects (who can see what): D-15, D-16, D-17. D-22 is a
// frontend change covered by frontend/test/table_providers_test.dart. Targets 8083, own data.
// Run: node api-group2.js → prints a summary and writes api-group2-results.json next to this file.
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
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vm2-sales');
  const otherSales = await rt.createStaff(admin, 'SALES_POC', 'vm2-other');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vm2-coll');
  const salesTok = await rt.login(sales.username, sales.password);
  const otherTok = await rt.login(otherSales.username, otherSales.password);
  const collTok = await rt.login(coll.username, coll.password);
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];
  const mineCust = await rt.createCustomer(admin, 'vm2-mine');
  const theirCust = await rt.createCustomer(admin, 'vm2-theirs');
  const invoice = async (customerId, pocId) => (await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId, salesPocUserId: pocId, items: [{ productId: prod.id, quantity: 1, unitPrice: 100 }] },
  })).json;
  const mine = await invoice(mineCust.id, sales.id);
  const theirs = await invoice(theirCust.id, otherSales.id);

  // ---- D-15 ------------------------------------------------------------------------------
  await step('M2-01', 'D-15', 'a Sales POC cannot reach another rep\'s records by id', async () => {
    const got = {
      getTheirs: await rt.api('GET', `/api/invoices/${theirs.id}`, { token: salesTok }),
      patchTheirs: await rt.api('PATCH', `/api/invoices/${theirs.id}`, { token: salesTok, body: { notes: 'mine now' } }),
      cancelTheirs: await rt.api('POST', `/api/invoices/${theirs.id}/cancel`, { token: salesTok }),
      getTheirCustomer: await rt.api('GET', `/api/customers/${theirCust.id}`, { token: salesTok }),
      seatTheirCustomer: await rt.api('POST', `/api/customers/${theirCust.id}/pocs`, {
        token: salesTok, body: { pocType: 'SUCCESS', userId: sales.id, primary: true },
      }),
      getMine: await rt.api('GET', `/api/invoices/${mine.id}`, { token: salesTok }),
      getMyCustomer: await rt.api('GET', `/api/customers/${mineCust.id}`, { token: salesTok }),
      adminGetTheirs: await rt.api('GET', `/api/invoices/${theirs.id}`, { token: admin }),
    };
    const after = (await rt.api('GET', `/api/invoices/${theirs.id}`, { token: admin })).json;
    const status = Object.fromEntries(Object.entries(got).map(([k, r]) => [k, r.status]));
    const ok = ['getTheirs', 'patchTheirs', 'cancelTheirs', 'getTheirCustomer', 'seatTheirCustomer'].every((k) => status[k] === 404)
      && status.getMine === 200 && status.getMyCustomer === 200 && status.adminGetTheirs === 200
      && after.status === 'UNPAID' && !after.notes;
    check('M2-01', 'D-15', 'outside the book → 404 and nothing changed; own book and admin → 200', ok,
      { ...status, theirsAfter: { status: after.status, notes: after.notes } });
  });

  // ---- D-16 ------------------------------------------------------------------------------
  await step('M2-02', 'D-16', 'customers see no staff ids on promises or disputes', async () => {
    const custTok = await rt.login(mineCust.username, mineCust.password);
    const promise = (await rt.api('POST', '/api/promises', {
      token: admin,
      body: { customerId: mineCust.id, amount: 50, promisedDate: new Date(Date.now() + 5 * 86400000).toISOString().slice(0, 10), collectionPocUserId: coll.id },
    })).json;
    await rt.api('POST', `/api/promises/${promise.id}/override`, { token: admin, body: { status: 'KEPT', reason: 'paid in cash' } });
    const d = (await rt.api('POST', '/api/disputes', { token: custTok, body: { targetType: 'INVOICE', targetId: mine.id, reason: 'vm2' } })).json;
    await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: admin, body: { adminNotes: 'no' } });

    const custPromise = (await rt.api('GET', `/api/promises/${promise.id}`, { token: custTok })).json;
    const custList = (await rt.api('GET', '/api/promises', { token: custTok })).json.content;
    const custDispute = (await rt.api('GET', `/api/disputes/${d.id}`, { token: custTok })).json;
    const adminPromise = (await rt.api('GET', `/api/promises/${promise.id}`, { token: admin })).json;
    const adminDispute = (await rt.api('GET', `/api/disputes/${d.id}`, { token: admin })).json;
    const ok = custPromise.createdByUserId === null && custPromise.overriddenByUserId === null
      && custList.every((p) => p.createdByUserId === null && p.overriddenByUserId === null)
      && custDispute.resolvedByUserId === null
      && adminPromise.createdByUserId != null && adminPromise.overriddenByUserId != null && adminDispute.resolvedByUserId != null;
    check('M2-02', 'D-16', 'customer: createdBy/overriddenBy/resolvedBy null; admin: present', ok, {
      customerPromise: [custPromise.createdByUserId, custPromise.overriddenByUserId],
      customerDispute: custDispute.resolvedByUserId,
      adminPromise: [adminPromise.createdByUserId, adminPromise.overriddenByUserId],
      adminDispute: adminDispute.resolvedByUserId,
    });
  });

  // ---- D-17 ------------------------------------------------------------------------------
  await step('M2-03', 'D-17', 'history needs the record\'s view privilege and respects the book', async () => {
    const adminUser = (await rt.api('GET', '/api/auth/me', { token: admin })).json;
    const got = {
      salesUserHistory: await rt.api('GET', `/api/audit?entityType=USER&entityId=${adminUser.id}`, { token: salesTok }),
      collProductHistory: await rt.api('GET', `/api/audit?entityType=PRODUCT&entityId=${prod.id}`, { token: collTok }),
      salesTheirInvoiceHistory: await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${theirs.id}`, { token: salesTok }),
      otherSalesOwnInvoiceHistory: await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${theirs.id}`, { token: otherTok }),
      adminUserHistory: await rt.api('GET', `/api/audit?entityType=USER&entityId=${adminUser.id}`, { token: admin }),
    };
    const status = Object.fromEntries(Object.entries(got).map(([k, r]) => [k, r.status]));
    const ok = status.salesUserHistory === 403 && status.collProductHistory === 403
      && status.salesTheirInvoiceHistory === 404 && status.otherSalesOwnInvoiceHistory === 200
      && status.adminUserHistory === 200;
    check('M2-03', 'D-17', 'USER/PRODUCT without view → 403; other book → 404; own/admin → 200', ok, status);
  });

  fs.writeFileSync(path.join(__dirname, 'api-group2-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
