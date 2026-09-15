// UI verification of PAYCR-022 (UI), 044, 045, 046, 048, 052, 054.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const { make } = require('../payments-credit/uilib.js');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const only = process.argv[2]; // optional: run one section
const log = [];
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const u = make(page, __dirname, log);
  const notesBox = () => u.clickInput((x) => x.tag === 'TEXTAREA');
  const want = (k) => !only || only === k;
  const entries = async () => (await rt.semantics(page)).filter((n) => /\n@/.test(n.label || '')).map((n) => n.label.replace(/\n/g, ' | '));

  if (want('046')) await u.step('046 customer dropdown', async () => {
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await rt.tap(page, 'Customer *', { wait: 2000 });
    const labels0 = await u.labels();
    u.note('046 A-customer offered at open', labels0.some((l) => (l || '').includes(D.custA.name)));
    for (let i = 0; i < 25; i++) { await page.mouse.move(683, 450); await page.mouse.wheel(0, 600); await page.waitForTimeout(150); }
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    const labels = (await u.labels()).filter(Boolean);
    u.note('046 last options visible', labels.slice(-6));
    u.note('046 zz customer offered', labels.some((l) => l.includes(D.custZ.name)));
    await u.shot('v046-dropdown-bottom');
    await page.keyboard.press('Escape'); await page.waitForTimeout(600);
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
  });

  if (want('045')) await u.step('045 picker filter', async () => {
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await rt.tap(page, /^Collection POC \*/, { wait: 2000 });
    await page.keyboard.type(D.pocB.username, { delay: 30 });
    await page.waitForTimeout(3000);
    await rt.enableSemantics(page);
    const e1 = await entries();
    u.note('045 entries 3 s after typing the full username', { count: e1.length, first: e1.slice(0, 4) });
    await u.shot('v045-picker-3s');
    await page.keyboard.press('Shift'); // a key that changes nothing
    await page.waitForTimeout(1500); await rt.enableSemantics(page);
    u.note('045 entries after pressing Shift', (await entries()).slice(0, 4));
    await page.keyboard.press('Backspace');
    await page.waitForTimeout(1500); await rt.enableSemantics(page);
    const e2 = await entries();
    u.note('045 entries after Backspace (search now one char shorter)', { count: e2.length, first: e2.slice(0, 4) });
    await u.shot('v045-picker-after-backspace');
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
  });

  if (want('044')) await u.step('044 POC then customer', async () => {
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await rt.tap(page, /^Collection POC \*/, { wait: 2000 });
    await page.keyboard.type(D.pocB.username, { delay: 30 });
    await page.waitForTimeout(800);
    await page.keyboard.type(' '); await page.keyboard.press('Backspace'); // nudge the list
    await page.waitForTimeout(1500); await rt.enableSemantics(page);
    await rt.tap(page, new RegExp(D.pocB.fullName), { role: 'button', wait: 1500 });
    await rt.enableSemantics(page);
    u.note('044 POC after manual pick', await u.has(/^Collection POC \*/));
    await u.shot('v044-a-poc-picked');
    await rt.tap(page, 'Customer *', { wait: 2000 });
    await rt.tap(page, D.custA.name, { wait: 3500 });
    await rt.enableSemantics(page);
    u.note('044 POC after choosing the customer', await u.has(/^Collection POC \*/));
    u.note('044 expected pick / primary', { picked: D.pocB.fullName, primary: D.pocA.fullName });
    await u.shot('v044-b-after-customer');
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
  });

  if (want('048')) await u.step('048 save button drawn', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`);
    await u.shot('v048-a-clean');
    await notesBox();
    await rt.typeText(page, 'visibility check', { clear: true });
    await page.waitForTimeout(800);
    await rt.enableSemantics(page);
    const sem = await rt.semantics(page);
    u.note('048 Save/Unsaved nodes', sem.filter((n) => /Save changes|Unsaved/.test(`${n.label} ${n.text}`)).map((n) => ({ l: n.label || n.text, x: n.x, y: n.y, w: n.w, h: n.h })));
    u.note('048 allocations nodes', sem.filter((n) => /INV-|allocations/.test(`${n.label} ${n.text}`)).map((n) => ({ l: (n.label || n.text).slice(0, 40), y: n.y, h: n.h })));
    await u.shot('v048-b-dirty-1366x900');
    await page.setViewportSize({ width: 1920, height: 1300 });
    await page.waitForTimeout(1500); await rt.enableSemantics(page);
    u.note('048 Save node at 1920x1300', (await rt.semantics(page)).filter((n) => /Save changes|Unsaved/.test(`${n.label} ${n.text}`)).map((n) => ({ x: n.x, y: n.y, h: n.h })));
    await u.shot('v048-c-dirty-1920x1300');
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.waitForTimeout(1000);
  });

  if (want('052')) await u.step('052 nav away while dirty', async () => {
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, `#/payments/${D.pay.id}`);
    await notesBox();
    await rt.typeText(page, 'lost edit', { clear: true });
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    const nav = (await rt.semantics(page)).find((n) => (n.label || n.text) === 'Invoices' && n.x < 300);
    u.note('052 sidebar node', nav && { x: nav.x, y: nav.y, role: nav.role });
    await rt.clickAt(page, nav.x, nav.y, 2500);
    await rt.enableSemantics(page);
    u.note('052 after sidebar Invoices', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052-a-after-sidebar');
    if ((await u.has(/Discard unsaved/)).length) { await rt.tap(page, 'Discard', { wait: 2000 }); }
    u.note('052 API notes after sidebar', (await api('GET', `/api/payments/${D.pay.id}`)).json.notes);

    await rt.go(page, `#/payments/${D.pay.id}`);
    await notesBox();
    await rt.typeText(page, 'lost edit 2', { clear: true });
    await page.waitForTimeout(600);
    await rt.tap(page, new RegExp(D.I2.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('052 after allocation row', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052-b-after-allocation');
    if ((await u.has(/Discard unsaved/)).length) { await rt.tap(page, 'Discard', { wait: 2000 }); }

    // control: Back button does prompt
    await rt.go(page, `#/payments/${D.pay.id}`);
    await notesBox();
    await rt.typeText(page, 'lost edit 3', { clear: true });
    await page.waitForTimeout(600);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.enableSemantics(page);
    u.note('052 control: Back button', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    if ((await u.has(/Discard unsaved/)).length) { await rt.tap(page, 'Discard', { wait: 2000 }); }
  });

  if (want('054')) await u.step('054 promise tab', async () => {
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, `#/payments/${D.pay.id}`);
    await rt.tap(page, 'Payment Promise', { role: 'tab', wait: 3500 });
    await rt.enableSemantics(page);
    const promises = (await api('GET', `/api/promises?customerId=${D.custA.id}&size=50`)).json.content
      .map((p) => ({ id: p.id, amount: p.amount, status: p.status, linked: (p.payments || []).map((x) => x.paymentId ?? x.id) }));
    u.note('054 customer promises (API)', promises);
    u.note('054 tab rows', (await u.labels()).filter((l) => /romise|₹|\$|by /.test(l || '')).slice(0, 12));
    await page.mouse.move(800, 750); await page.mouse.wheel(0, 300); await page.waitForTimeout(700);
    await u.shot('v054-promise-tab');
  });

  if (want('022')) await u.step('022 inactive POC notes save', async () => {
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, `#/payments/${D.payInactive.id}`);
    u.note('022 POC field', await u.has(/^Collection POC|inactive/));
    await notesBox();
    await rt.typeText(page, 'note after poc left', { clear: true });
    await page.waitForTimeout(600);
    const before = app.apiErrors.length;
    await rt.tap(page, 'Save changes', { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('022 error / snackbar', await u.has(/inactive|cannot be assigned|Payment saved/));
    u.note('022 new apiErrors', app.apiErrors.slice(before));
    await u.shot('v022-inactive-save');
    u.note('022 API notes', (await api('GET', `/api/payments/${D.payInactive.id}`)).json.notes);
  });

  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, `ui-log${only ? '-' + only : ''}.json`), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
