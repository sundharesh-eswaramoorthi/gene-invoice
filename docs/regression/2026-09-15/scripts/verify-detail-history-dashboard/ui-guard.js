// Verifier: DET-012 / DET-013 / DET-014 (unsaved-edit guard), with a control check on the Back arrow.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const res = {};
const allText = async (page) => (await rt.semantics(page)).map((n) => `${n.label || ''} ${n.text || ''}`).join(' | ');
const hash = (page) => page.evaluate(() => location.hash);
const hasDialog = async (page) => (await allText(page)).includes('Discard unsaved changes?');

async function focusField(page, re, fallback) {
  const nodes = await rt.semantics(page);
  const n = nodes.find((x) => re.test(x.label || '') || re.test(x.text || ''));
  const [x, y] = n ? [n.x, n.y] : fallback;
  await rt.clickAt(page, x, y, 600);
  return { via: n ? 'semantics' : 'fallback', x, y, node: n && `${n.role}:${n.label || n.text}` };
}

(async () => {
  const token = await rt.adminToken();
  const app = await rt.openApp({ token });
  const { page } = app;
  try {
    // ---------- customer: sidebar ----------
    await rt.go(page, '#/customers');
    await rt.go(page, `#/customers/${S.cust.id}`);
    fs.writeFileSync(path.join(DIR, 'sem-customer.json'), JSON.stringify(await rt.semantics(page), null, 1));
    await rt.shot(page, DIR, 'g0-customer');
    let f = await focusField(page, /^Phone/, [886, 289]);
    await rt.typeText(page, '9');
    await page.waitForTimeout(500);
    await rt.shot(page, DIR, 'g1-customer-dirty');
    // Control: the in-app Back arrow prompts (proves the screen is dirty).
    await rt.tap(page, 'Back', { wait: 1500 });
    const ctrlDlg = await hasDialog(page);
    await rt.shot(page, DIR, 'g2-back-arrow-dialog');
    if (ctrlDlg) await rt.tap(page, 'Keep editing', { wait: 1200 });
    const afterKeep = await hash(page);
    // Now the sidebar
    const nav = await rt.find(page, 'Invoices');
    await rt.clickAt(page, nav.x, nav.y, 2500);
    const sbDlg = await hasDialog(page);
    const sbHash = await hash(page);
    await rt.shot(page, DIR, 'g3-customer-sidebar');
    res.customerSidebar = { field: f, controlBackArrowDialog: ctrlDlg, hashAfterKeep: afterKeep, sidebarNode: `${nav.role}:${nav.label || nav.text}@${nav.x},${nav.y}`, dialog: sbDlg, hashAfter: sbHash };
    if (sbDlg) await rt.tap(page, 'Keep editing', { wait: 1000 });
    const srv1 = await rt.api('GET', `/api/customers/${S.cust.id}`, { token });
    res.customerSidebar.serverPhone = srv1.json?.phone;

    // ---------- customer: browser back ----------
    await rt.go(page, '#/invoices');
    await rt.go(page, `#/customers/${S.cust.id}`);
    f = await focusField(page, /^Phone/, [886, 289]);
    await rt.typeText(page, '8');
    await page.waitForTimeout(500);
    await page.goBack();
    await page.waitForTimeout(2500);
    await rt.enableSemantics(page);
    res.customerBrowserBack = { field: f, dialog: await hasDialog(page), hashAfter: await hash(page) };
    await rt.shot(page, DIR, 'g4-customer-browser-back');

    // ---------- customer: notifications bell ----------
    await rt.go(page, `#/customers/${S.cust.id}`);
    f = await focusField(page, /^Phone/, [886, 289]);
    await rt.typeText(page, '7');
    await page.waitForTimeout(500);
    const bell = await rt.find(page, /Notifications/);
    await rt.clickAt(page, bell.x, bell.y, 2500);
    res.customerBell = { field: f, bell: `${bell.role}:${bell.label || bell.text}@${bell.x},${bell.y}`, dialog: await hasDialog(page), hashAfter: await hash(page) };
    await rt.shot(page, DIR, 'g5-customer-bell');

    // ---------- payment: allocation row ----------
    await rt.go(page, '#/payments');
    await rt.go(page, `#/payments/${S.P1}`);
    fs.writeFileSync(path.join(DIR, 'sem-payment.json'), JSON.stringify(await rt.semantics(page), null, 1));
    await rt.shot(page, DIR, 'g6-payment');
    f = await focusField(page, /^Notes/, [886, 289]);
    await rt.typeText(page, ' edited');
    await page.waitForTimeout(500);
    await rt.shot(page, DIR, 'g7-payment-dirty');
    const nodes = await rt.semantics(page);
    const row = nodes.find((n) => /INV-\d{8}-\d+/.test(`${n.label || ''} ${n.text || ''}`) && /allocated/.test(`${n.label || ''} ${n.text || ''}`));
    let rowInfo = null;
    if (row) {
      rowInfo = `${row.role}:${(row.label || row.text).slice(0, 80)}@${row.x},${row.y}`;
      await rt.clickAt(page, row.x, row.y, 2500);
    }
    res.paymentAllocation = { field: f, row: rowInfo, dialog: await hasDialog(page), hashAfter: await hash(page) };
    await rt.shot(page, DIR, 'g8-payment-alloc-tap');
    const srv2 = await rt.api('GET', `/api/payments/${S.P1}`, { token });
    res.paymentAllocation.serverNotes = srv2.json?.notes;

    // ---------- payment: sidebar ----------
    await rt.go(page, `#/payments/${S.P1}`);
    f = await focusField(page, /^Notes/, [886, 289]);
    await rt.typeText(page, ' again');
    await page.waitForTimeout(500);
    const nav2 = await rt.find(page, 'Dashboard');
    await rt.clickAt(page, nav2.x, nav2.y, 2500);
    res.paymentSidebar = { field: f, dialog: await hasDialog(page), hashAfter: await hash(page) };
    await rt.shot(page, DIR, 'g9-payment-sidebar');
  } catch (e) {
    res.error = String(e.stack || e);
  } finally {
    res.pageErrors = app.pageErrors.slice(0, 5);
    fs.writeFileSync(path.join(DIR, 'ui-guard.json'), JSON.stringify(res, null, 2));
    console.log(JSON.stringify(res, null, 2));
    await app.close();
  }
})();
