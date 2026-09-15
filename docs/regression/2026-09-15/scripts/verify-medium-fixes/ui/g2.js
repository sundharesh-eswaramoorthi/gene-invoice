// Group 2 (who can see what): W-04 filter columns after admin → customer sign-in in the same tab
// (D-22); W-05 "Raise dispute" only for customers. 1366x900. Writes g2.json.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = {};
const snap = C.recorder(out);

async function columnMenu(page, key, name) {
  await rt.tap(page, 'Add filter', { wait: 2000 });
  await rt.enableSemantics(page);
  await rt.tap(page, /^Column/, { wait: 1500 });
  await rt.enableSemantics(page);
  const nodes = await snap(page, key, name);
  const items = nodes.filter((n) => n.role === 'menuitem').map(C.lab);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  await rt.enableSemantics(page);
  const after = await rt.semantics(page);
  if (after.find((n) => C.lab(n) === 'Cancel')) await rt.tap(page, 'Cancel');
  return { items, all: nodes.map(C.lab).filter((t) => /POC|Customer|Invoice|Status|Due|Total|Balance/i.test(t)).slice(0, 40) };
}

(async () => {
  // ---------------- W-04 D-22 (one tab: admin → sign out → customer)
  out.W04 = {};
  {
    const T = await rt.adminToken();
    const app = await rt.openApp({ token: T, width: 1366, height: 900 });
    const { page } = app;
    try {
      await rt.go(page, '#/invoices', 4000);
      const a = await columnMenu(page, 'W04', 'w04-1-admin-columns');
      out.W04.adminColumns = a.items;
      out.W04.adminAllTexts = a.all;
      await C.signOut(page);
      await snap(page, 'W04', 'w04-2-signed-out');
      out.W04.hashAfterSignOut = await page.evaluate(() => location.hash);
      await C.signIn(page, S.c22.username, S.c22.password);
      await snap(page, 'W04', 'w04-3-customer-home');
      out.W04.reloads = await page.evaluate(() => performance.getEntriesByType('navigation').length);
      await rt.go(page, '#/invoices', 4000);
      let n = await snap(page, 'W04', 'w04-4-customer-invoices');
      out.W04.customerListTexts = C.has(n, /error|failed|invalid|not allowed|INV-/i);
      const c = await columnMenu(page, 'W04', 'w04-5-customer-columns');
      out.W04.customerColumns = c.items;
      out.W04.customerAllTexts = c.all;
      n = await snap(page, 'W04', 'w04-6-after-close');
      out.W04.errorsOnScreen = C.has(n, /error|failed|invalid|not allowed|forbidden/i);
      out.W04.salesPocOffered = c.items.some((t) => /Sales POC/i.test(t)) || c.all.some((t) => /Sales POC/i.test(t));
    } catch (e) {
      out.W04.error = String(e.stack || e);
      await rt.shot(page, C.DIR, 'w04-error');
    } finally {
      out.W04.apiErrors = app.apiErrors;
      out.W04.pageErrors = app.pageErrors.slice(0, 5);
      await app.close();
    }
  }

  // ---------------- W-05 Raise dispute
  out.W05 = {};
  {
    const T = await rt.adminToken();
    const app = await rt.openApp({ token: T, width: 1366, height: 900 });
    const { page } = app;
    try {
      await rt.go(page, '#/invoices');
      await rt.go(page, `#/invoices/${S.invDisp.id}`, 4000);
      let n = await snap(page, 'W05', 'w05-1-admin-invoice');
      out.W05.adminInvoiceHeader = n.filter((x) => x.y < 140).map(C.fmt);
      out.W05.adminInvoiceRaise = C.has(n, /Raise dispute/);
      await rt.go(page, '#/payments');
      await rt.go(page, `#/payments/${S.payDisp.id}`, 4000);
      n = await snap(page, 'W05', 'w05-2-admin-payment');
      out.W05.adminPaymentHeader = n.filter((x) => x.y < 140).map(C.fmt);
      out.W05.adminPaymentRaise = C.has(n, /Raise dispute/);
    } catch (e) {
      out.W05.adminError = String(e.stack || e);
    } finally {
      out.W05.adminApiErrors = app.apiErrors;
      await app.close();
    }
  }
  {
    const T = await rt.login(S.cDisp.username, S.cDisp.password);
    const app = await rt.openApp({ token: T, width: 1366, height: 900 });
    const { page } = app;
    try {
      await rt.go(page, '#/invoices');
      await rt.go(page, `#/invoices/${S.invDisp2.id}`, 4000);
      let n = await snap(page, 'W05', 'w05-3-customer-invoice');
      out.W05.customerInvoiceHeader = n.filter((x) => x.y < 140).map(C.fmt);
      out.W05.customerInvoiceRaise = C.has(n, /Raise dispute/);
      await rt.go(page, '#/payments');
      await rt.go(page, `#/payments/${S.payDisp.id}`, 4000);
      n = await snap(page, 'W05', 'w05-4-customer-payment');
      out.W05.customerPaymentRaise = C.has(n, /Raise dispute/);
    } catch (e) {
      out.W05.customerError = String(e.stack || e);
    } finally {
      out.W05.customerApiErrors = app.apiErrors;
      await app.close();
    }
  }
  C.write('g2.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
