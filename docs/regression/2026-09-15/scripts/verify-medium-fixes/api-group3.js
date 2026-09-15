// Re-verifies the API side of group 3 of the medium defects (promises, payments, bulk): D-14,
// D-33, D-36. D-31 and D-37 are UI-only and checked in the browser. Targets 8083, own data.
// Run: node api-group3.js → prints a summary and writes api-group3-results.json next to this file.
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
const inDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

(async () => {
  const admin = await rt.adminToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vm3-sales');
  const otherSales = await rt.createStaff(admin, 'SALES_POC', 'vm3-other');
  const salesTok = await rt.login(sales.username, sales.password);
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];
  const invoice = async (customerId, pocId) => (await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId, salesPocUserId: pocId, items: [{ productId: prod.id, quantity: 1, unitPrice: 100 }] },
  })).json;

  // ---- D-14 ------------------------------------------------------------------------------
  await step('M3-01', 'D-14', 'bulk reports ids it could not act on instead of dropping them', async () => {
    const cust = await rt.createCustomer(admin, 'vm3-bulk');
    const own = await invoice(cust.id, sales.id);
    const theirs = await invoice(cust.id, otherSales.id);
    const unknown = await rt.api('POST', '/api/invoices/bulk', { token: admin, body: { action: 'CANCEL', ids: [own.id, 99999999] } });
    const scoped = await rt.api('POST', '/api/invoices/bulk', { token: salesTok, body: { action: 'CANCEL', ids: [theirs.id] } });
    const u = unknown.json;
    const s = scoped.json;
    const ok = u.requested === 2 && u.succeeded.includes(own.id) && u.skipped.some((x) => x.id === 99999999)
      && s.requested === 1 && s.succeeded.length === 0 && s.skipped.some((x) => x.id === theirs.id)
      && u.skipped[0].reason === s.skipped[0].reason;
    check('M3-01', 'D-14', 'unknown and out-of-book ids → skipped with one neutral reason', ok, {
      unknown: { requested: u.requested, succeeded: u.succeeded, skipped: u.skipped },
      outOfBook: { requested: s.requested, succeeded: s.succeeded, skipped: s.skipped },
    });
  });

  // ---- D-33 ------------------------------------------------------------------------------
  await step('M3-02', 'D-33', 'a cancelled promise cannot be revived by an override', async () => {
    const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vm3-coll');
    const cust = await rt.createCustomer(admin, 'vm3-override');
    const p = (await rt.api('POST', '/api/promises', {
      token: admin, body: { customerId: cust.id, amount: 100, promisedDate: inDays(5), collectionPocUserId: coll.id },
    })).json;
    await rt.api('POST', `/api/promises/${p.id}/cancel`, { token: admin, body: { reason: 'raised in error' } });
    const override = await rt.api('POST', `/api/promises/${p.id}/override`, { token: admin, body: { status: 'OPEN', reason: 'revive' } });
    const clear = await rt.api('DELETE', `/api/promises/${p.id}/override`, { token: admin });
    const after = (await rt.api('GET', `/api/promises/${p.id}`, { token: admin })).json;
    check('M3-02', 'D-33', 'override and clear on a cancelled promise → 400; stays CANCELLED',
      override.status === 400 && clear.status === 400 && after.status === 'CANCELLED' && after.statusOverridden === false,
      { override: [override.status, override.json && override.json.message], clear: [clear.status, clear.json && clear.json.message], after: after.status });
  });

  // ---- D-36 ------------------------------------------------------------------------------
  await step('M3-03', 'D-36', 'the default Collection POC is never a deactivated user', async () => {
    const primary = await rt.createStaff(admin, 'COLLECTION_POC', 'vm3-primary');
    const backup = await rt.createStaff(admin, 'COLLECTION_POC', 'vm3-backup');
    const withBackup = await rt.createCustomer(admin, 'vm3-default');
    const onlyPrimary = await rt.createCustomer(admin, 'vm3-noactive');
    await rt.api('POST', `/api/customers/${withBackup.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: primary.id, primary: true } });
    await rt.api('POST', `/api/customers/${withBackup.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: backup.id, primary: false } });
    await rt.api('POST', `/api/customers/${onlyPrimary.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: primary.id, primary: true } });
    const del = await rt.api('DELETE', `/api/users/${primary.id}`, { token: admin });

    const fallback = await rt.api('POST', '/api/promises', { token: admin, body: { customerId: withBackup.id, amount: 50, promisedDate: inDays(5) } });
    const none = await rt.api('POST', '/api/promises', { token: admin, body: { customerId: onlyPrimary.id, amount: 50, promisedDate: inDays(5) } });
    check('M3-03', 'D-36', 'deactivated primary → defaults to the active backup; no active seat → 400',
      del.json && del.json.deactivated === true
        && fallback.status === 200 && fallback.json.collectionPoc && fallback.json.collectionPoc.id === backup.id
        && none.status === 400 && /no active Collection POC/.test(none.json.message),
      { deletedPrimary: del.json, fallback: [fallback.status, fallback.json && fallback.json.collectionPoc], none: [none.status, none.json && none.json.message] });
  });

  fs.writeFileSync(path.join(__dirname, 'api-group3-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
