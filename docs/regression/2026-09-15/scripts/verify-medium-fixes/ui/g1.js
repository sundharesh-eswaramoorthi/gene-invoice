// Group 1 (validation and errors): W-01 Users dialog email errors (D-29/D-27), W-02 Roles dialog
// duplicate name (D-27), W-03 Record payment 3-decimal amount (D-30). Admin, 1366x900. Writes g1.json.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = {};
const snap = C.recorder(out);

async function pickRole(page, key) {
  await rt.tap(page, /^Role/, { wait: 1200 });
  await rt.enableSemantics(page);
  const nodes = await rt.semantics(page);
  const items = nodes.filter((n) => n.role === 'menuitem' || /^(VIEWER|CASHIER|ADMIN|wm-)/.test(C.lab(n)));
  out[key].roleMenu = items.slice(0, 15).map(C.fmt);
  const pick = items.find((n) => C.lab(n) === 'VIEWER') || items.find((n) => /^wm-exporter/.test(C.lab(n)))
    || items.find((n) => !/ADMIN/.test(C.lab(n)));
  if (!pick) throw new Error('no role item');
  await rt.clickAt(page, pick.x, pick.y, 1000);
  out[key].rolePicked = C.lab(pick);
}

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  try {
    // ---------------- W-01 Users dialog
    out.W01 = {};
    await rt.go(page, '#/users');
    await rt.tap(page, /New user/, { wait: 2000 });
    await rt.enableSemantics(page);
    await snap(page, 'W01', 'w01-1-dialog');
    out.W01.inputs = await C.inputs(page);
    const uname = rt.uniq('wm-ui-user');
    out.W01.username = uname;
    await C.fill(page, /Username/, uname);
    await C.fill(page, /^Email/, 'not-an-email');
    await C.fill(page, /Full name/, 'WM UI USER');
    await C.fill(page, /^Password/, 'Passw0rd!');
    await pickRole(page, 'W01');
    await rt.tap(page, 'Save', { wait: 2500 });
    let n = await snap(page, 'W01', 'w01-2-malformed');
    out.W01.malformedTexts = C.has(n, /email|invalid|exists|must/i);

    const longEmail = 'a'.repeat(118) + '@wm-example.io';
    await C.fill(page, /^Email/, longEmail);
    out.W01.longTyped = longEmail.length;
    out.W01.longFieldValue = (await C.inputs(page)).map((f) => ({ label: f.label, len: (f.value || '').length }));
    await rt.tap(page, 'Save', { wait: 2500 });
    n = await snap(page, 'W01', 'w01-3-overlong');
    out.W01.overlongTexts = C.has(n, /email|invalid|exists|must/i);

    const dup = S.dupUser.email.toUpperCase();
    out.W01.dupEmailTyped = dup;
    await C.fill(page, /^Email/, dup);
    await rt.tap(page, 'Save', { wait: 2500 });
    n = await snap(page, 'W01', 'w01-4-duplicate');
    out.W01.duplicateTexts = C.has(n, /email|invalid|exists|must/i);
    out.W01.dialogStillOpen = C.find(n, /^New user$/).length > 0;
    if (out.W01.dialogStillOpen) await rt.tap(page, 'Cancel');
    const created = await rt.api('GET', `/api/users?filter=username:eq:${uname}`, { token: T });
    out.W01.userCreated = (created.json?.content || []).length;

    // ---------------- W-02 Roles dialog, duplicate name
    out.W02 = {};
    await rt.go(page, '#/roles');
    await rt.tap(page, /New role/, { wait: 2000 });
    await rt.enableSemantics(page);
    await snap(page, 'W02', 'w02-1-dialog');
    await C.fill(page, /^Name/, 'admin');
    await rt.tap(page, 'Save', { wait: 2500 });
    n = await snap(page, 'W02', 'w02-2-after-save');
    const err = C.find(n, /Role name already exists/)[0];
    const save = n.find((x) => C.lab(x) === 'Save');
    const cancel = n.find((x) => C.lab(x) === 'Cancel');
    out.W02.error = C.fmt(err);
    out.W02.save = C.fmt(save);
    out.W02.cancel = C.fmt(cancel);
    out.W02.errorOnScreen = !!err && err.y > 0 && err.y < 900;
    out.W02.errorAboveSaveWithin80px = !!err && !!save && save.y - err.y > 0 && save.y - err.y < 80;
    if (cancel) await rt.tap(page, 'Cancel');

    // ---------------- W-03 Record payment 10.555 / 10.55
    out.W03 = {};
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2000 });
    await rt.enableSemantics(page);
    await snap(page, 'W03', 'w03-1-dialog');
    await rt.tap(page, /^Customer/, { wait: 2000 });
    await rt.typeText(page, S.cPay.name);
    await page.waitForTimeout(2000);
    await rt.enableSemantics(page);
    await snap(page, 'W03', 'w03-2-customer-search');
    await rt.tap(page, new RegExp(S.cPay.name), { wait: 3000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W03', 'w03-3-customer-picked');
    out.W03.pocShown = C.has(n, /Collection POC|WM-COLL/i);
    await C.fill(page, /^Amount/, '10.555');
    await rt.tap(page, 'Record', { wait: 2500 });
    n = await snap(page, 'W03', 'w03-4-10555');
    out.W03.after10555 = C.has(n, /amount|decimal|Enter/i);
    const pays1 = await rt.api('GET', `/api/payments?filter=customerId:eq:${S.cPay.id}`, { token: T });
    out.W03.paymentsAfter10555 = (pays1.json?.content || []).map((p) => p.amount);
    await C.fill(page, /^Amount/, '10.55');
    await rt.tap(page, 'Record', { wait: 3500 });
    n = await snap(page, 'W03', 'w03-5-1055');
    out.W03.dialogOpenAfter1055 = C.find(n, /^Record payment$/).length > 1 || C.find(n, /^Record$/).length > 0;
    out.W03.textsAfter1055 = C.has(n, /amount|decimal|recorded|saved/i);
    const pays2 = await rt.api('GET', `/api/payments?filter=customerId:eq:${S.cPay.id}`, { token: T });
    out.W03.paymentsAfter1055 = (pays2.json?.content || []).map((p) => ({ id: p.id, amount: p.amount, poc: p.collectionPoc?.username }));
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, C.DIR, 'g1-error');
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  C.write('g1.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
