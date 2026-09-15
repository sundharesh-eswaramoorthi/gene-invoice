// Group 3 (promises and payments): W-06 Record payment keeps a picked POC (D-31); W-07 promise
// default POC skips a deactivated primary (D-36); W-08 override from a Promises row refreshes the
// row and tiles (D-37); W-09 promise card shows a cancelled invoice as cancelled. Admin, 1366x900.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = {};
const snap = C.recorder(out);

async function pickPoc(page, key, fieldRe, user) {
  await rt.tap(page, fieldRe, { wait: 2000 });
  await rt.enableSemantics(page);
  await rt.typeText(page, user.username);
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  const n = await snap(page, key, `${key.toLowerCase()}-poc-search`);
  out[key].pickerList = C.has(n, /@wm-|@/).slice(0, 8);
  await rt.tap(page, new RegExp('@' + user.username), { wait: 1500 });
  await rt.enableSemantics(page);
}

async function pickCustomer(page, name) {
  await rt.tap(page, /^Customer/, { wait: 2000 });
  await rt.typeText(page, name);
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  await rt.tap(page, new RegExp(name), { wait: 3500 });
  await rt.enableSemantics(page);
}

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  try {
    // ---------------- W-06 D-31: pick B, then a customer whose primary is A
    out.W06 = { A: S.collA.fullName, B: S.collB.fullName };
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2000 });
    await rt.enableSemantics(page);
    await pickPoc(page, 'W06', /^Collection POC/, S.collB);
    let n = await snap(page, 'W06', 'w06-1-b-picked');
    out.W06.pocBeforeCustomer = C.has(n, /Collection POC/);
    await pickCustomer(page, S.cPay.name);
    await page.waitForTimeout(2000);
    n = await snap(page, 'W06', 'w06-2-after-customer');
    out.W06.pocAfterCustomer = C.has(n, /Collection POC/);
    out.W06.customerField = C.has(n, /Customer/);
    await rt.tap(page, 'Cancel');

    // Same dialog, D-36 customer: the default must be the active backup.
    out.W07 = { primaryDeactivated: S.p36.fullName, backup: S.b36.fullName };
    await rt.tap(page, 'Record payment', { wait: 2000 });
    await rt.enableSemantics(page);
    await pickCustomer(page, S.c36.name);
    await page.waitForTimeout(2000);
    n = await snap(page, 'W07', 'w07-1-record-payment-default');
    out.W07.recordPaymentPoc = C.has(n, /Collection POC/);
    await rt.tap(page, 'Cancel');

    // ---------------- W-07 D-36: Raise promise on the customer's Payment Promise tab
    await rt.go(page, '#/customers');
    await rt.go(page, `#/customers/${S.c36.id}`, 4000);
    await rt.tap(page, 'Payment Promise', { wait: 2500 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W07', 'w07-2-promise-tab');
    await rt.tap(page, 'Raise promise', { wait: 3000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W07', 'w07-3-raise-promise-dialog');
    out.W07.promisePoc = C.has(n, /Collection POC/);
    out.W07.mentionsPrimary = C.has(n, new RegExp(S.p36.username, 'i'));
    await rt.tap(page, 'Cancel');

    // ---------------- W-08 D-37: override from a Promises row
    out.W08 = {};
    await rt.go(page, '#/promises');
    await rt.go(page, `#/promises?f=customerId:eq:${S.c37.id}`, 4500);
    n = await snap(page, 'W08', 'w08-1-before');
    out.W08.before = C.has(n, /Open|Kept|Broken|Partially|Promised|promise/i).slice(0, 30);
    await rt.tap(page, 'Override status', { wait: 2000 });
    await rt.enableSemantics(page);
    await snap(page, 'W08', 'w08-2-dialog');
    await rt.tap(page, /^Status/, { wait: 1200 });
    await rt.enableSemantics(page);
    n = await rt.semantics(page);
    const kept = n.find((x) => x.role === 'menuitem' && /^Kept$/.test(C.lab(x))) || n.find((x) => /^Kept$/.test(C.lab(x)));
    out.W08.menu = n.filter((x) => x.role === 'menuitem').map(C.lab);
    await rt.clickAt(page, kept.x, kept.y, 1000);
    await C.fill(page, /Reason/, 'wm D-37 override to kept');
    await rt.tap(page, 'Override', { wait: 3000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W08', 'w08-3-after');
    out.W08.after = C.has(n, /Open|Kept|Broken|Partially|Promised|promise/i).slice(0, 30);
    out.W08.hash = await page.evaluate(() => location.hash);
    await page.waitForTimeout(3000);
    await rt.enableSemantics(page);
    n = await snap(page, 'W08', 'w08-4-after-wait');
    out.W08.afterWait = C.has(n, /Open|Kept|Broken|Partially|Promised|promise/i).slice(0, 30);
    out.W08.api = (await rt.api('GET', `/api/promises/${S.prom37.id}`, { token: T })).json?.status;

    // ---------------- W-09 promise card with a cancelled invoice
    out.W09 = { cancelled: S.inv9x.invoiceNumber, live: S.inv9y.invoiceNumber };
    await rt.go(page, '#/customers');
    await rt.go(page, `#/customers/${S.c9.id}`, 4000);
    await rt.tap(page, 'Payment Promise', { wait: 3000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W09', 'w09-1-customer-promise-tab');
    out.W09.chips = C.has(n, /INV-/);
    await rt.go(page, '#/invoices');
    await rt.go(page, `#/invoices/${S.inv9y.id}`, 4000);
    await rt.tap(page, 'Payment Promise', { wait: 3000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W09', 'w09-2-invoice-promise-tab');
    out.W09.invoiceTabChips = C.has(n, /INV-/);
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, C.DIR, 'g3-error');
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  C.write('g3.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
