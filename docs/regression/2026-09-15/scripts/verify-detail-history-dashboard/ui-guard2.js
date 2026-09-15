// Verifier re-run: DET-012/013/014 with the real input coordinates (the first run clicked the field label),
// plus re-runs of DET-006, DET-016 and HUI-007 with a mouse-click control.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const res = {};
const lbl = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const allText = async (page) => (await rt.semantics(page)).map(lbl).join(' | ');
const hash = (page) => page.evaluate(() => location.hash);
const hasDialog = async (page) => (await allText(page)).includes('Discard unsaved changes?');
const PHONE = [886, 289];
const PNOTES = [886, 301];
const SIDEBAR = { Dashboard: [111, 85], Invoices: [104, 129], Payments: [108, 173], Disputes: [105, 261] };
const BELL = [1166, 28];

async function dirtyCustomer(page, ch, shotName) {
  await rt.go(page, `#/customers/${S.cust.id}`, 4000);
  await rt.clickAt(page, PHONE[0], PHONE[1], 600);
  await page.keyboard.press('End');
  await rt.typeText(page, ch);
  await page.waitForTimeout(600);
  if (shotName) await rt.shot(page, DIR, shotName);
}
async function dirtyPayment(page, txt, shotName) {
  await rt.go(page, `#/payments/${S.P1}`, 4000);
  await rt.clickAt(page, PNOTES[0], PNOTES[1], 600);
  await page.keyboard.press('End');
  await rt.typeText(page, txt);
  await page.waitForTimeout(600);
  if (shotName) await rt.shot(page, DIR, shotName);
}

(async () => {
  const token = await rt.adminToken();
  const app = await rt.openApp({ token });
  const { page } = app;
  try {
    // --- control: dirty customer + in-app Back arrow prompts ---
    await rt.go(page, '#/customers');
    await dirtyCustomer(page, '9', 'h0-customer-dirty');
    await rt.tap(page, 'Back', { wait: 1500 });
    res.control = { backArrowDialog: await hasDialog(page), hash: await hash(page) };
    await rt.shot(page, DIR, 'h1-back-arrow-dialog');
    if (res.control.backArrowDialog) await rt.tap(page, 'Keep editing', { wait: 1000 });
    res.control.hashAfterKeep = await hash(page);

    // --- DET-012: sidebar, run twice (Invoices, then Disputes) ---
    res.customerSidebar = [];
    for (const [i, dest] of ['Invoices', 'Disputes'].entries()) {
      await dirtyCustomer(page, String(i), i === 0 ? null : 'h2b-customer-dirty');
      await rt.clickAt(page, SIDEBAR[dest][0], SIDEBAR[dest][1], 2500);
      res.customerSidebar.push({ dest, dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `h2-customer-sidebar-${dest}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }
    res.serverPhoneAfter = (await rt.api('GET', `/api/customers/${S.cust.id}`, { token })).json?.phone;

    res.paymentSidebar = [];
    for (const dest of ['Invoices', 'Dashboard']) {
      await dirtyPayment(page, ' x', dest === 'Invoices' ? 'h3-payment-dirty' : null);
      await rt.clickAt(page, SIDEBAR[dest][0], SIDEBAR[dest][1], 2500);
      res.paymentSidebar.push({ dest, dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `h4-payment-sidebar-${dest}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }

    // --- DET-013: browser back, twice ---
    res.browserBack = [];
    for (const i of [0, 1]) {
      await rt.go(page, '#/invoices', 3000);
      await dirtyCustomer(page, String(5 + i), null);
      await page.goBack();
      await page.waitForTimeout(2500);
      await rt.enableSemantics(page);
      res.browserBack.push({ dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `h5-browser-back-${i}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }

    // --- DET-014: allocation row, twice; bell, twice ---
    res.allocation = [];
    for (const i of [0, 1]) {
      await dirtyPayment(page, ' y', i === 0 ? 'h6-payment-dirty-alloc' : null);
      const row = (await rt.semantics(page)).find((n) => /INV-\d{8}-\d+/.test(lbl(n)) && /allocated/.test(lbl(n)));
      const [x, y] = row ? [row.x, row.y] : [335, 395];
      await rt.clickAt(page, x, y, 2500);
      res.allocation.push({ at: [x, y], dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `h7-alloc-tap-${i}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }
    res.bell = [];
    for (const i of [0, 1]) {
      await dirtyCustomer(page, String(3 + i), null);
      await rt.clickAt(page, BELL[0], BELL[1], 2500);
      res.bell.push({ dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `h8-bell-${i}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }
    res.serverPaymentNotesAfter = (await rt.api('GET', `/api/payments/${S.P1}`, { token })).json?.notes;
    res.serverPhoneEnd = (await rt.api('GET', `/api/customers/${S.cust.id}`, { token })).json?.phone;

    // --- DET-006 re-run ---
    await rt.go(page, `#/payments/${S.P1}?tab=promises`, 5000);
    res.det006 = (await rt.semantics(page)).map(lbl).filter((s) => /PR[12] on V/.test(s)).map((s) => s.replace(/\n/g, ' / ').slice(0, 90));

    // --- HUI-007 re-run + controls ---
    await rt.go(page, `#/invoices/${S.V1}?tab=history`, 5000);
    let nodes = await rt.semantics(page);
    let row = nodes.find((n) => /Dispute denied/i.test(lbl(n)));
    res.hui007 = { rowNode: row && `${row.role}@${row.x},${row.y} ${row.w}x${row.h}: ${lbl(row).replace(/\n/g, ' / ')}` };
    // control A: real mouse click on the chevron at the right edge of the row -> should expand, stay put
    await rt.clickAt(page, 1318, 683, 1800);
    res.hui007.chevronClickHash = await hash(page);
    await rt.shot(page, DIR, 'h9-history-chevron-click');
    res.hui007.expandedAfterChevron = (await allText(page)).includes('VD1 denied by admin');
    // collapse again and re-activate via the semantics node
    await rt.go(page, `#/invoices/${S.V1}?tab=history`, 5000);
    nodes = await rt.semantics(page);
    row = nodes.find((n) => /Dispute denied/i.test(lbl(n)));
    res.hui007.actions = await page.locator(`flt-semantics[data-rt="${row.i}"]`).evaluate((e) => ({ role: e.getAttribute('role'), children: e.querySelectorAll('flt-semantics').length, ariaExpanded: e.getAttribute('aria-expanded') }));
    await page.locator(`flt-semantics[data-rt="${row.i}"]`).dispatchEvent('click');
    await page.waitForTimeout(2500);
    res.hui007.semanticActivateHash = await hash(page);
    await rt.shot(page, DIR, 'h10-history-semantic-activate');

    // --- DET-016 re-run ---
    await rt.go(page, '#/payments/abc', 3500);
    res.det016 = { hash: await hash(page), texts: (await rt.semantics(page)).map(lbl) };
    await rt.shot(page, DIR, 'h11-payments-abc');
  } catch (e) {
    res.error = String(e.stack || e);
  } finally {
    res.pageErrors = app.pageErrors.slice(0, 8);
    fs.writeFileSync(path.join(DIR, 'ui-guard2.json'), JSON.stringify(res, null, 2));
    console.log(JSON.stringify(res, null, 2));
    await app.close();
  }
})();
