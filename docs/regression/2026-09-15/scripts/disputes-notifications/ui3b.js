// UI part 3b: Approve/Deny button visibility probe, invalid JSON, Deny with notes, customer notification
// clicks, bulk mark read vs bell badge, mark all read, amount validation redo.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const DIR = __dirname;
const ctx = require('./ui3-ctx.json');
const out = {};
const dump = async (page, tag) => {
  const nodes = await rt.semantics(page);
  console.log(`--- ${tag}: ` + nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\s+/g, ' ').slice(0, 80)} @${n.x},${n.y} ${n.w}x${n.h}`).join(' | '));
  return nodes;
};
const onScreen = async (page, re) => (await rt.semantics(page)).some((n) => re.test(n.label || '') || re.test(n.text || ''));
const step = async (name, fn) => { try { await fn(); } catch (e) { console.log(`STEP ${name} ERROR: ${e.message.slice(0, 700)}`); } };
const hash = (page) => page.evaluate(() => location.hash);
// the bell badge is the short numeric text node in the top bar
const badge = async (page) => {
  const n = (await rt.semantics(page)).find((x) => x.y < 50 && /^\d+\+?$/.test((x.label || x.text || '').trim()));
  return n ? (n.label || n.text).trim() : null;
};

(async () => {
  const adm = await rt.login(ctx.myAdmin, rt.PASSWORD);
  const xt = await rt.login(ctx.X, rt.PASSWORD);
  const dStatus = async (id) => (await rt.api('GET', `/api/disputes/${id}`, { token: adm })).json;

  let app = await rt.openApp({ token: adm });
  let page = app.page;

  // 1. visibility probe of the Resolve buttons on a pending dispute
  await step('probe', async () => {
    await rt.go(page, `#/disputes/${ctx.dX3}`, 4500);
    const nodes = await dump(page, 'dX3 detail');
    const ap = nodes.find((n) => n.label === 'Approve' || n.text === 'Approve');
    const dn = nodes.find((n) => n.label === 'Deny' || n.text === 'Deny');
    out.approveRect = ap && { x: ap.x, y: ap.y, w: ap.w, h: ap.h };
    out.denyRect = dn && { x: dn.x, y: dn.y, w: dn.w, h: dn.h };
    console.log(await rt.shot(page, DIR, 'b01-dX3-detail'));
    // colour sample where the Approve button should be painted (left side of its row)
    out.pixelsAtApproveRow = await page.evaluate(({ y }) => {
      const c = document.querySelector('canvas');
      return c ? 'canvas-present' : 'no-canvas';
    }, { y: ap ? ap.y : 0 });
    await page.mouse.move(683, 600);
    await page.mouse.wheel(0, 400);
    await page.waitForTimeout(1200);
    await dump(page, 'dX3 scrolled');
    console.log(await rt.shot(page, DIR, 'b02-dX3-scrolled'));
  });

  // 2. invalid JSON in the Applied change field -> error, still pending
  await step('invalid json', async () => {
    await rt.go(page, `#/disputes/${ctx.dX3}`, 4500);
    const resolve = await rt.find(page, 'Resolve');
    await rt.clickAt(page, 810, resolve.y + 150, 600);
    await page.keyboard.press('Meta+A');
    await page.keyboard.press('Backspace');
    await rt.typeText(page, '{bad json');
    await rt.tap(page, 'Approve', { wait: 2500 });
    out.invalidJsonHash = await hash(page);
    const nodes = await dump(page, 'after invalid json approve');
    out.invalidJsonError = nodes.filter((n) => /format|json|unexpected|invalid|error/i.test(n.label || n.text || '')).map((n) => n.label || n.text);
    console.log(await rt.shot(page, DIR, 'b03-invalid-json'));
    out.dX3StatusAfterInvalid = (await dStatus(ctx.dX3)).status;
  });

  // 3. Deny dX2 with admin notes
  await step('deny', async () => {
    await rt.go(page, `#/disputes/${ctx.dX2}`, 4500);
    const resolve = await rt.find(page, 'Resolve');
    await rt.clickAt(page, 810, resolve.y + 295, 600);
    await rt.typeText(page, 'UI3 deny note');
    console.log(await rt.shot(page, DIR, 'b04-deny-notes-typed'));
    await rt.tap(page, 'Deny', { wait: 4000 });
    out.afterDenyHash = await hash(page);
    console.log(await rt.shot(page, DIR, 'b05-after-deny'));
    const d = await dStatus(ctx.dX2);
    const p = (await rt.api('GET', `/api/payments/${ctx.payX}`, { token: adm })).json;
    out.denyEffect = { status: d.status, adminNotes: d.adminNotes, payNotes: p.notes, payMethod: p.method };
    const cn = (await rt.api('GET', `/api/notifications?size=10&filter=${encodeURIComponent('type:eq:DISPUTE_DENIED')}`, { token: xt })).json.content;
    out.customerDeniedNotif = cn.find((n) => n.link === `/disputes/${ctx.dX2}`);
    await rt.go(page, `#/disputes/${ctx.dX2}`, 4000);
    await dump(page, 'denied detail');
    console.log(await rt.shot(page, DIR, 'b06-denied-detail'));
  });

  // 4. bulk mark read from the Notifications screen vs the bell badge
  await step('bulk badge', async () => {
    await rt.api('POST', '/api/notifications/bulk', { token: adm, body: { action: 'MARK_UNREAD', selectAllMatchingFilter: true, filters: [] } });
    await app.close();
    app = await rt.openApp({ token: adm });
    page = app.page;
    await rt.go(page, '#/notifications', 5000);
    out.badgeBeforeBulk = await badge(page);
    out.apiBeforeBulk = (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
    const rows = (await rt.semantics(page)).filter((n) => n.role === 'cell' && /^New dispute from|Dispute|POC|assigned/i.test(n.label || n.text || ''));
    const ys = [...new Set(rows.map((r) => r.y))].slice(0, 2);
    for (const y of ys) await rt.clickAt(page, 318, y, 700);
    await dump(page, 'after selecting 2 rows');
    console.log(await rt.shot(page, DIR, 'b07-two-selected'));
    await rt.tap(page, /^Mark read$/, { wait: 3000 });
    out.badgeAfterBulk = await badge(page);
    out.apiAfterBulk = (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
    console.log(await rt.shot(page, DIR, 'b08-after-bulk-mark-read'));
    await page.waitForTimeout(6000);
    out.badgeAfterBulk6s = await badge(page);
  });

  // 5. Mark all read
  await step('mark all', async () => {
    await rt.tap(page, 'Mark all read', { wait: 3000 });
    out.badgeAfterMarkAll = await badge(page);
    out.apiAfterMarkAll = (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
    console.log(await rt.shot(page, DIR, 'b09-after-mark-all-read'));
  });
  console.log('admin apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  const admErrors = { apiErrors: app.apiErrors, pageErrors: app.pageErrors };
  await app.close();

  // 6. customer: notifications -> click approved / denied -> dispute detail
  app = await rt.openApp({ token: xt });
  page = app.page;
  await step('customer notifications', async () => {
    await rt.go(page, '#/notifications', 5000);
    out.custBadge = await badge(page);
    await dump(page, 'customer notifications');
    console.log(await rt.shot(page, DIR, 'b10-customer-notifications'));
    const ap = await rt.find(page, 'Dispute approved');
    await rt.clickAt(page, ap.x, ap.y, 3500);
    out.custApprovedClickHash = await hash(page);
    await dump(page, 'customer after approved click');
    console.log(await rt.shot(page, DIR, 'b11-customer-approved-detail'));
    await rt.go(page, '#/notifications', 4000);
    const dn = await rt.find(page, 'Dispute denied');
    await rt.clickAt(page, dn.x, dn.y, 3500);
    out.custDeniedClickHash = await hash(page);
    out.custDeniedShowsNote = await onScreen(page, /UI3 deny note/);
    console.log(await rt.shot(page, DIR, 'b12-customer-denied-detail'));
  });

  // 7. amount validation redo: -5 in the amount field, then a valid amount
  await step('amount redo', async () => {
    await rt.go(page, `#/payments/${ctx.payX}`, 4500);
    await rt.tap(page, /Raise dispute/);
    const title = await rt.find(page, /^Raise dispute •/);
    await rt.clickAt(page, 683, title.y + 75, 600);
    await rt.typeText(page, 'UI3: amount should be 120');
    await rt.tap(page, /What should change\?/);
    await rt.tap(page, 'Correct the amount');
    const dd = await rt.find(page, /What should change\?/);
    await rt.clickAt(page, 683, dd.y + 47, 600);
    await rt.typeText(page, '-5');
    await rt.tap(page, 'Submit', { wait: 2000 });
    out.negAmountError = await onScreen(page, /Enter a positive amount/);
    console.log(await rt.shot(page, DIR, 'b13-negative-amount'));
    await rt.clickAt(page, 683, dd.y + 47, 600);
    await rt.typeText(page, '120', { clear: true });
    await rt.tap(page, 'Submit', { wait: 3000 });
    out.validAmountDialogClosed = !(await onScreen(page, /^Raise dispute •/));
    console.log(await rt.shot(page, DIR, 'b14-valid-amount-submitted'));
    const r = await rt.api('GET', `/api/disputes?targetType=PAYMENT&targetId=${ctx.payX}&filter=${encodeURIComponent('status:eq:PENDING')}`, { token: xt });
    out.amountDispute = r.json.content.map((d) => ({ id: d.id, reason: d.reason, proposed: d.proposedChangeJson }));
  });
  console.log('OUT', JSON.stringify(out, null, 1));
  console.log('customer apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  fs.writeFileSync(path.join(DIR, 'ui3b-out.json'), JSON.stringify({ out, admErrors, custErrors: { apiErrors: app.apiErrors, pageErrors: app.pageErrors } }, null, 2));
  await app.close();
})().catch((e) => { console.error('UI3B CRASHED', e); process.exit(1); });
