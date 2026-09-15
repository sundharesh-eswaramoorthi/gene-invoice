// D-06: searchable customer/product pickers on the invoice form and the Record payment dialog.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 100)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  out['sem_' + name] = nodes.map(fmt);
  out['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}
async function tapNode(page, n, wait = 1500) {
  await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
  await page.waitForTimeout(wait);
}
// Result rows in an open picker dialog (list tiles), or the "Nothing matches" text.
const results = async (page) => (await rt.semantics(page)).map(lab)
  .filter((t) => /zz-vu|Nothing matches|Could not search/.test(t)).map((t) => t.replace(/\n/g, ' / '));

(async () => {
  const T = await rt.adminToken();
  const cust = S.zzCust.name;
  const prod = S.zzProd.name;
  const inactive = S.zzInactive.name;
  out.names = { cust, prod, inactive };
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  const searches = [];
  page.on('request', (r) => {
    const u = decodeURIComponent(r.url());
    if (r.method() === 'GET' && /\/api\/(customers|products)\?/.test(u)) searches.push(u.replace(rt.API, ''));
  });
  try {
    await rt.go(page, '#/invoices');
    await rt.go(page, '#/invoices/new', 4000);
    let n = await snap(page, 'd06-01-invoice-form');
    // ---- Customer picker
    const custField = n.find((x) => /^Customer/.test(lab(x)));
    out.customerField = fmt(custField);
    await rt.clickAt(page, custField.x, custField.y, 2500);
    await rt.enableSemantics(page);
    n = await snap(page, 'd06-02-choose-customer');
    out.customerDialog = n.map(lab).filter((t) => /Choose|Search/.test(t));
    const part = cust.slice(0, cust.length - 4); // e.g. zz-vu-mu2yllvo59
    await rt.typeText(page, part);
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    out.custAfterPartial = { typed: part, results: await results(page) };
    await snap(page, 'd06-03-customer-partial');
    // Lag probe: finish the name, then add a char, then remove it.
    await rt.typeText(page, cust.slice(-4));
    await page.waitForTimeout(1200);
    out.custAfterFull = { typed: cust, results: await results(page) };
    await rt.typeText(page, 'Q');
    await page.waitForTimeout(1200);
    out.custAfterExtraChar = { typed: cust + 'Q', results: await results(page) };
    await snap(page, 'd06-04-customer-extra-char');
    await page.keyboard.press('Backspace');
    await page.waitForTimeout(1200);
    out.custAfterBackspace = { typed: cust, results: await results(page) };
    n = await snap(page, 'd06-05-customer-back');
    const custRow = n.find((x) => lab(x).startsWith(cust));
    await tapNode(page, custRow, 2000);
    n = await snap(page, 'd06-06-customer-picked');
    out.customerFieldAfter = fmt(n.find((x) => /^Customer/.test(lab(x))));

    // ---- Sales POC picker
    const pocField = n.find((x) => /^Sales POC/.test(lab(x)));
    out.pocField = fmt(pocField);
    if (pocField && /Select/.test(lab(pocField))) {
      await rt.clickAt(page, pocField.x, pocField.y, 2500);
      await rt.typeText(page, S.sales.username);
      await page.waitForTimeout(2000);
      await rt.enableSemantics(page);
      n = await snap(page, 'd06-07-choose-poc');
      let row = n.find((x) => lab(x).includes(S.sales.username));
      out.pocFoundAfterTyping = !!row;
      out.pocListAfterTyping = n.map(lab).filter((t) => /@/.test(t)).slice(0, 4);
      if (!row) {
        // PocPicker (not part of D-06) may lag a keystroke (D-18): add and remove a space.
        await rt.typeText(page, ' ');
        await page.keyboard.press('Backspace');
        await page.waitForTimeout(2000);
        await rt.enableSemantics(page);
        n = await snap(page, 'd06-07b-choose-poc-retry');
        row = n.find((x) => lab(x).includes(S.sales.username));
        out.pocFoundAfterExtraKeystroke = !!row;
        out.pocListAfterExtraKeystroke = n.map(lab).filter((t) => /@/.test(t)).slice(0, 4);
      }
      await tapNode(page, row, 2000);
    }

    // ---- Product picker
    n = await rt.semantics(page);
    const prodField = n.find((x) => /^Product/.test(lab(x)));
    out.productField = fmt(prodField);
    await rt.clickAt(page, prodField.x, prodField.y, 2500);
    await rt.enableSemantics(page);
    n = await snap(page, 'd06-08-choose-product');
    out.productDialog = n.map(lab).filter((t) => /Choose|Search/.test(t));
    await rt.typeText(page, 'zz-vu-');
    await page.waitForTimeout(1500);
    const zzResults = await results(page);
    out.productZz = { typed: 'zz-vu-', activeListed: zzResults.some((t) => t.includes(prod)), inactiveListed: zzResults.some((t) => t.includes(inactive)), results: zzResults.slice(0, 25) };
    await snap(page, 'd06-09-product-zz');
    await rt.typeText(page, 'inactive', {});
    await page.waitForTimeout(1200);
    out.productInactiveSearch = { typed: 'zz-vu-inactive', results: await results(page) };
    await snap(page, 'd06-10-product-inactive-search');
    await rt.typeText(page, prod, { clear: true });
    await page.waitForTimeout(1200);
    out.productExact = { typed: prod, results: await results(page) };
    n = await snap(page, 'd06-11-product-exact');
    const prodRow = n.find((x) => lab(x).startsWith(prod));
    await tapNode(page, prodRow, 2000);
    n = await snap(page, 'd06-12-form-filled');
    out.formFilled = n.map(fmt).filter((t) => /Customer|Sales POC|Product|₹|Total/.test(t));

    // ---- Create
    const create = n.find((x) => x.role === 'button' && lab(x) === 'Create invoice');
    await tapNode(page, create, 4000);
    n = await snap(page, 'd06-13-after-create');
    out.hashAfterCreate = await page.evaluate(() => location.hash);
    out.textsAfterCreate = n.map(lab).filter((t) => /created|error|Fill in/i.test(t));
    const inv = await rt.api('GET', `/api/invoices?size=10&customerId=${S.zzCust.id}`, { token: T });
    out.invoicesForZz = (inv.json?.content || []).map((i) => ({ id: i.id, number: i.invoiceNumber, total: i.total, customerName: i.customerName }));

    // ---- Record payment dialog
    await rt.go(page, '#/payments', 4000);
    await rt.tap(page, 'Record payment', { wait: 2500 });
    n = await rt.semantics(page);
    const rpCust = n.find((x) => /^Customer/.test(lab(x)));
    await rt.clickAt(page, rpCust.x, rpCust.y, 2500);
    await rt.typeText(page, cust.slice(0, 12));
    await page.waitForTimeout(1500);
    out.recordPaymentSearch = { typed: cust.slice(0, 12), results: await results(page) };
    n = await snap(page, 'd06-14-record-payment-search');
    const rpRow = n.find((x) => lab(x).startsWith(cust));
    await tapNode(page, rpRow, 3000);
    n = await snap(page, 'd06-15-record-payment-picked');
    out.recordPaymentAfterPick = n.map(fmt).filter((t) => /Customer|INV-|Outstanding|credit/i.test(t));
    const cancel = n.filter((x) => x.role === 'button' && lab(x) === 'Cancel').slice(-1)[0];
    if (cancel) await tapNode(page, cancel, 1500);
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd06-error');
  } finally {
    out.searchRequests = searches;
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd06.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(Object.fromEntries(Object.entries(out).filter(([k]) => !k.startsWith('sem_'))), null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
