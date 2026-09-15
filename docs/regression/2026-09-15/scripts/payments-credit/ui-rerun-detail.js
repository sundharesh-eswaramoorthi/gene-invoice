// Rerun of the Payment Details steps that were blocked by the picker failure.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const { make } = require('./uilib.js');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const log = [];
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const u = make(page, __dirname, log);
  const pid = D.pay.id;
  const notesBox = () => u.clickInput((x) => x.tag === 'TEXTAREA');

  await u.step('Save button visibility while dirty', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'visibility check', { clear: true });
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    const save = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
    u.note('Save changes node without scrolling (1366x900)', save ? { y: save.y, h: save.h } : 'not on screen');
    await u.shot('s01-dirty-no-scroll');
    for (let i = 0; i < 4; i++) { await page.mouse.move(800, 400); await page.mouse.wheel(0, 400); await page.waitForTimeout(400); }
    await rt.enableSemantics(page);
    const save2 = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
    u.note('Save changes node after scrolling the top pane 4x400px', save2 ? { y: save2.y, h: save2.h } : 'not on screen');
    u.note('unsaved text after scroll', (await rt.semantics(page)).filter((n) => /Unsaved/.test(n.label || n.text || '')).map((n) => ({ y: n.y, h: n.h })));
    await u.shot('s01b-dirty-scrolled');
    await page.setViewportSize({ width: 1366, height: 1200 });
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    const save3 = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
    u.note('Save changes node at 1366x1200', save3 ? { y: save3.y, h: save3.h } : 'not on screen');
    await u.shot('s01c-dirty-1366x1200');
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.waitForTimeout(1000);
  });

  await u.step('change POC + save + history', async () => {
    await rt.go(page, `#/payments/${pid}`);
    // the dirty edit above is discarded by navigating in-app; reload the record
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, `#/payments/${pid}`);
    await u.pickPoc(D.pocU2, 's2');
    u.note('POC field now', await u.has(/^Collection POC \*/));
    u.note('unsaved indicator', await u.has(/Unsaved changes/));
    await rt.tap(page, 'Save changes', { wait: 2500 });
    await rt.enableSemantics(page);
    u.note('snackbar', await u.has(/Payment saved/));
    const g = (await api('GET', `/api/payments/${pid}`)).json;
    u.note('API POC after save', { poc: g.collectionPoc?.username, expected: D.pocU2.username, notes: g.notes });
    await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
    await rt.enableSemantics(page);
    u.note('history rows', (await u.labels()).filter((l) => /updat|POC|record|Payment #|notes|appl/i.test(l || '')).slice(0, 12));
    await u.shot('s03-history');
  });

  await u.step('discard dialog', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'discard me', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.enableSemantics(page);
    u.note('dialog', await u.has(/Discard unsaved changes|Keep editing|^Discard$|not been saved/));
    await u.shot('s04-discard-dialog');
    await rt.tap(page, 'Keep editing', { wait: 1200 });
    u.note('after Keep editing', { hash: await u.hash(), notes: (await u.inputs()).find((x) => x.tag === 'TEXTAREA')?.value });
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.tap(page, 'Discard', { wait: 2500 });
    u.note('after Discard: hash', await u.hash());
    u.note('API notes after discard', (await api('GET', `/api/payments/${pid}`)).json.notes);
    await u.shot('s05-after-discard');
  });

  await u.step('candidate 15: sidebar nav while dirty', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'lost edit', { clear: true });
    await page.waitForTimeout(500);
    await rt.clickAt(page, 104, 129, 2500); // sidebar "Invoices"
    await rt.enableSemantics(page);
    u.note('after clicking the Invoices nav item', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('s06-nav-away-dirty');
    u.note('API notes (edit lost?)', (await api('GET', `/api/payments/${pid}`)).json.notes);
  });

  await u.step('candidate 15b: allocation row click while dirty', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'lost edit 2', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, new RegExp(D.I2.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('after clicking an allocation row while dirty', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
  });

  await u.step('allocation row opens its invoice', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await rt.tap(page, new RegExp(D.I1.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('after allocation click', { hash: await u.hash(), expected: `#/invoices/${D.I1.id}`, title: (await u.has(new RegExp(D.I1.invoiceNumber))).slice(0, 2) });
    await u.shot('s07-allocation-to-invoice');
  });

  await u.step('inactive POC: notes-only save', async () => {
    await rt.go(page, `#/payments/${D.payInactive.id}`);
    u.note('POC field', await u.has(/^Collection POC/));
    await notesBox();
    await rt.typeText(page, 'note after poc left', { clear: true });
    await page.waitForTimeout(500);
    await u.shot('s08-inactive-poc-dirty');
    await rt.tap(page, 'Save changes', { wait: 2500 });
    await rt.enableSemantics(page);
    u.note('error / snackbar', await u.has(/inactive|cannot be assigned|Payment saved/));
    await page.mouse.move(800, 300); await page.mouse.wheel(0, 400); await page.waitForTimeout(700);
    await u.shot('s09-inactive-poc-save');
    u.note('API notes', (await api('GET', `/api/payments/${D.payInactive.id}`)).json.notes);
  });

  await u.step('Payment Promise tab (candidate 37)', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await rt.tap(page, 'Payment Promise', { role: 'tab', wait: 3500 });
    await rt.enableSemantics(page);
    const promises = (await api('GET', `/api/promises?customerId=${D.cu.id}&size=50`)).json.content
      .map((p) => ({ id: p.id, status: p.status, amount: p.amount, linkedToThisPayment: (p.payments || []).some((x) => x.id === pid) }));
    u.note('customer promises (API)', promises);
    u.note('rows on the tab', (await u.labels()).filter((l) => /₹|romise/.test(l || '')).slice(0, 12));
    await page.mouse.move(800, 750); await page.mouse.wheel(0, 300); await page.waitForTimeout(700);
    await u.shot('s10-promise-tab');
  });

  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-rerun-detail-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
