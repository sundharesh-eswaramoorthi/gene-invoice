// D-04: FilledButton sizing — dispute Approve/Deny, Save changes on detail screens, dashboard,
// Record payment dialog, login. Writes d04.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  out['sem_' + name] = nodes.map(fmt);
  out['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}
const byLab = (nodes, m) => nodes.find((n) => (m instanceof RegExp ? m.test(lab(n)) : lab(n) === m));
async function focusBeside(page, nodes, label) {
  const l = nodes.find((n) => lab(n) === label && n.role !== 'button');
  if (!l) throw new Error('no label ' + label);
  const x = Math.round(l.x + l.w / 2 + 250);
  const y = l.y + 8;
  await rt.clickAt(page, x, y, 600);
  return { x, y };
}
const disputeStatus = async (T, id) => (await rt.api('GET', `/api/disputes/${id}`, { token: T })).json?.status;

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  try {
    // ---- dispute A: blank clicks, then Deny
    await rt.go(page, '#/disputes');
    await rt.go(page, `#/disputes/${S.d4a.id}`, 4000);
    let n = await snap(page, 'd04-01-dispute-a');
    const appr = byLab(n, 'Approve');
    const deny = byLab(n, 'Deny');
    out.approveNode = fmt(appr);
    out.denyNode = fmt(deny);
    const y = (appr || deny).y;
    out.statusBefore = await disputeStatus(T, S.d4a.id);
    await rt.clickAt(page, 1300, y, 2500);
    out.statusAfterBlank1300 = await disputeStatus(T, S.d4a.id);
    await rt.clickAt(page, deny.x + Math.round(deny.w / 2) + 200, y, 2500);
    out.statusAfterBlankNearDeny = await disputeStatus(T, S.d4a.id);
    await snap(page, 'd04-02-after-blank-clicks');
    await rt.clickAt(page, deny.x, deny.y, 3000);
    n = await snap(page, 'd04-03-after-deny-click');
    out.statusAfterDeny = await disputeStatus(T, S.d4a.id);

    // ---- dispute B: Approve by a real click on the button
    await rt.go(page, `#/disputes/${S.d4b.id}`, 4000);
    n = await snap(page, 'd04-04-dispute-b');
    const apprB = byLab(n, 'Approve');
    out.approveNodeB = fmt(apprB);
    await rt.clickAt(page, apprB.x, apprB.y, 3000);
    await snap(page, 'd04-05-after-approve-click');
    out.statusAfterApproveB = await disputeStatus(T, S.d4b.id);

    // ---- invoice notes
    await rt.go(page, `#/invoices/${S.inv4.id}`, 4000);
    n = await snap(page, 'd04-06-invoice');
    out.invoiceSaveBeforeEdit = fmt(byLab(n, 'Save changes'));
    out.invoiceNotesClick = await focusBeside(page, n, 'Notes');
    await rt.typeText(page, 'vu-d04 invoice notes', { clear: true });
    await page.waitForTimeout(600);
    n = await snap(page, 'd04-07-invoice-dirty');
    const invSave = byLab(n, 'Save changes');
    out.invoiceSaveDirty = fmt(invSave);
    out.invoiceUnsavedHint = fmt(byLab(n, 'Unsaved changes'));
    await rt.clickAt(page, invSave.x, invSave.y, 3000);
    n = await snap(page, 'd04-08-invoice-after-save');
    out.invoiceAfterSaveTexts = n.map(lab).filter((t) => /saved|Unsaved|error|inactive/i.test(t));
    out.invoiceNotesServer = (await rt.api('GET', `/api/invoices/${S.inv4.id}`, { token: T })).json?.notes;

    // ---- payment notes
    await rt.go(page, `#/payments/${S.pay4.id}`, 4000);
    n = await snap(page, 'd04-09-payment');
    out.paymentNotesClick = await focusBeside(page, n, 'Notes');
    await rt.typeText(page, 'vu-d04 payment notes', { clear: true });
    await page.waitForTimeout(600);
    n = await snap(page, 'd04-10-payment-dirty');
    const paySave = byLab(n, 'Save changes');
    out.paymentSaveDirty = fmt(paySave);
    out.paymentUnsavedHint = fmt(byLab(n, 'Unsaved changes'));
    await rt.clickAt(page, paySave.x, paySave.y, 3000);
    n = await snap(page, 'd04-11-payment-after-save');
    out.paymentAfterSaveTexts = n.map(lab).filter((t) => /saved|Unsaved|error|inactive/i.test(t));
    out.paymentNotesServer = (await rt.api('GET', `/api/payments/${S.pay4.id}`, { token: T })).json?.notes;

    // ---- customer details Save changes
    await rt.go(page, `#/customers/${S.c4.id}`, 4000);
    n = await snap(page, 'd04-12-customer');
    out.customerPhoneClick = await focusBeside(page, n, 'Phone');
    await rt.typeText(page, '555-0199', { clear: true });
    await page.waitForTimeout(600);
    n = await snap(page, 'd04-13-customer-dirty');
    const cSave = byLab(n, 'Save changes');
    out.customerSaveDirty = fmt(cSave);
    await rt.clickAt(page, cSave.x, cSave.y, 3000);
    await snap(page, 'd04-14-customer-after-save');
    out.customerPhoneServer = (await rt.api('GET', `/api/customers/${S.c4.id}`, { token: T })).json?.phone;

    // ---- dashboard, lists, Record payment dialog
    await rt.go(page, '#/', 4000);
    n = await snap(page, 'd04-15-dashboard');
    out.dashboardButtons = n.filter((x) => x.role === 'button').map(fmt);
    await rt.go(page, '#/invoices', 4000);
    n = await snap(page, 'd04-16-invoices-list');
    out.invoicesListButtons = n.filter((x) => x.role === 'button' && x.y < 250).map(fmt);
    await rt.go(page, '#/payments', 4000);
    n = await snap(page, 'd04-17-payments-list');
    await rt.tap(page, 'Record payment', { wait: 2500 });
    n = await snap(page, 'd04-18-record-payment-dialog');
    out.recordDialogButtons = n.filter((x) => x.role === 'button').map(fmt);
    await rt.tap(page, 'Cancel', { wait: 1500 });
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd04-error');
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }

  // ---- login screen
  const app2 = await rt.openApp({ width: 1366, height: 900 });
  try {
    const n = await snap(app2.page, 'd04-19-login');
    out.loginButtons = n.filter((x) => x.role === 'button').map(fmt);
  } catch (e) {
    out.loginError = String(e.stack || e);
  } finally {
    await app2.close();
  }
  fs.writeFileSync(path.join(DIR, 'd04.json'), JSON.stringify(out, null, 1));
  const brief = Object.fromEntries(Object.entries(out).filter(([k]) => !k.startsWith('sem_')));
  console.log(JSON.stringify(brief, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
