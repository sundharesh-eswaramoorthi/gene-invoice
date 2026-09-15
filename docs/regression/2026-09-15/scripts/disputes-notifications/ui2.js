// UI part 2: customer raises disputes (validation, submit, duplicate), sees own list and detail.
const rt = require('../lib.js');
const DIR = __dirname;
const ctx = require('./ui-ctx.json');
const out = {};
const dump = async (page, tag) => {
  const nodes = await rt.semantics(page);
  console.log(`--- ${tag}: ` + nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\s+/g, ' ').slice(0, 80)} @${n.x},${n.y}`).join(' | '));
  return nodes;
};
const onScreen = async (page, re) => (await rt.semantics(page)).some((n) => re.test(n.label || '') || re.test(n.text || ''));
const step = async (name, fn) => { try { await fn(); } catch (e) { console.log(`STEP ${name} ERROR: ${e.message.slice(0, 600)}`); } };

(async () => {
  const ctok = await rt.login(ctx.cust, rt.PASSWORD);
  const app = await rt.openApp({ token: ctok });
  const { page } = app;

  // 1. notes-only dispute from the invoice header
  await step('notes dispute', async () => {
    await rt.go(page, `#/invoices/${ctx.inv1}`, 4500);
    await rt.tap(page, /Raise dispute/);
    const title = await rt.find(page, /^Raise dispute •/);
    await rt.clickAt(page, 683, title.y + 75, 600);
    await rt.typeText(page, 'UI: notes are wrong on this invoice');
    await rt.tap(page, /What should change\?/);
    await dump(page, 'menu open');
    console.log(await rt.shot(page, DIR, 'u04-action-menu'));
    await rt.tap(page, 'Correct the notes only');
    await rt.tap(page, 'Submit');
    out.notesRequired = await onScreen(page, /Enter the corrected notes/);
    await dump(page, 'after submit w/o notes');
    console.log(await rt.shot(page, DIR, 'u05-notes-required'));
    const dd = await rt.find(page, /What should change\?/);
    await rt.clickAt(page, 683, dd.y + 64, 600);
    await rt.typeText(page, 'UI corrected notes');
    console.log(await rt.shot(page, DIR, 'u06-notes-filled'));
    await rt.tap(page, 'Submit', { wait: 3000 });
    out.dialogClosed = !(await onScreen(page, /^Raise dispute •/));
    await dump(page, 'after submit');
    console.log(await rt.shot(page, DIR, 'u07-after-submit-tab'));
    out.tabShowsDispute = await onScreen(page, /UI: notes are wrong/);
    const r = await rt.api('GET', `/api/disputes?targetType=INVOICE&targetId=${ctx.inv1}`, { token: ctok });
    out.apiDispute = r.json.content.map((d) => ({ id: d.id, status: d.status, reason: d.reason, proposed: d.proposedChangeJson }));
    out.disputeId = r.json.content[0]?.id;
  });

  // 2. duplicate dispute from the tab button
  await step('duplicate', async () => {
    const nodes = await rt.semantics(page);
    const btns = nodes.filter((n) => /Raise dispute/.test(n.label || n.text || ''));
    const tabBtn = btns.sort((a, b) => b.y - a.y)[0];
    await rt.clickAt(page, tabBtn.x, tabBtn.y, 1500);
    const title = await rt.find(page, /^Raise dispute •/);
    await rt.clickAt(page, 683, title.y + 75, 600);
    await rt.typeText(page, 'duplicate attempt');
    await rt.tap(page, 'Submit', { wait: 2500 });
    out.duplicateError = (await rt.semantics(page)).filter((n) => /already exists|open dispute/i.test(n.label || n.text || '')).map((n) => n.label || n.text);
    await dump(page, 'duplicate');
    console.log(await rt.shot(page, DIR, 'u08-duplicate-error'));
    await rt.tap(page, 'Cancel');
  });

  // 3. customer Disputes list is scoped
  await step('list', async () => {
    await rt.go(page, '#/disputes', 4500);
    await dump(page, 'customer disputes list');
    console.log(await rt.shot(page, DIR, 'u09-customer-disputes-list'));
    out.listHasOwn = await onScreen(page, /UI: notes are wrong/);
    out.listHasOther = await onScreen(page, /OTHER CUSTOMER SECRET/);
  });

  // 4. customer opens dispute detail: no Approve/Deny
  await step('detail', async () => {
    await rt.go(page, `#/disputes/${out.disputeId}`, 4000);
    await dump(page, 'customer detail');
    console.log(await rt.shot(page, DIR, 'u10-customer-dispute-detail'));
    out.detailHasApprove = await onScreen(page, /^Approve$/);
    out.detailHasReason = await onScreen(page, /UI: notes are wrong/);
    // other customer's dispute by URL
    await rt.go(page, `#/disputes/${ctx.dOther}`, 4000);
    await dump(page, 'other customer detail');
    console.log(await rt.shot(page, DIR, 'u11-other-dispute-by-url'));
    out.otherSecretVisible = await onScreen(page, /OTHER CUSTOMER SECRET/);
  });

  // 5. payment dispute: amount validation
  await step('payment amount', async () => {
    await rt.go(page, `#/payments/${ctx.pay1}`, 4500);
    await dump(page, 'payment detail');
    console.log(await rt.shot(page, DIR, 'u12-customer-payment-detail'));
    await rt.tap(page, /Raise dispute/);
    const title = await rt.find(page, /^Raise dispute •/);
    await rt.clickAt(page, 683, title.y + 75, 600);
    await rt.typeText(page, 'UI: amount wrong');
    await rt.tap(page, /What should change\?/);
    await rt.tap(page, 'Correct the amount');
    const dd = await rt.find(page, /What should change\?/);
    await rt.clickAt(page, 683, dd.y + 58, 600);
    await rt.typeText(page, '-5');
    await rt.tap(page, 'Submit', { wait: 2000 });
    out.amountError = await onScreen(page, /Enter a positive amount/);
    await dump(page, 'amount error');
    console.log(await rt.shot(page, DIR, 'u13-amount-error'));
    await rt.tap(page, 'Cancel');
    const r = await rt.api('GET', `/api/disputes?targetType=PAYMENT&targetId=${ctx.pay1}`, { token: ctok });
    out.paymentDisputes = r.json.totalElements;
  });

  console.log('OUT', JSON.stringify(out, null, 1));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  require('fs').writeFileSync(require('path').join(DIR, 'ui2-out.json'), JSON.stringify({ out, apiErrors: app.apiErrors, pageErrors: app.pageErrors }, null, 2));
  await app.close();
})().catch((e) => { console.error('UI2 CRASHED', e); process.exit(1); });
