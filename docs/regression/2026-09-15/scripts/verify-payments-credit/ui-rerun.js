// Second UI pass: 052 (sidebar node located from the semantics dump) and 045 (dump every dialog label).
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const { make } = require('../payments-credit/uilib.js');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const log = [];
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const u = make(page, __dirname, log);
  const notesBox = () => u.clickInput((x) => x.tag === 'TEXTAREA');
  const consoleMsgs = [];
  page.on('console', (m) => { if (['error', 'warning'].includes(m.type()) || /exception|overflow|RenderFlex|laid out/i.test(m.text())) consoleMsgs.push(`${m.type()}: ${m.text().slice(0, 300)}`); });

  await u.step('048 console + blind click', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`);
    consoleMsgs.length = 0;
    await notesBox();
    await rt.typeText(page, 'blind save 048', { clear: true });
    await page.waitForTimeout(1000);
    await rt.enableSemantics(page);
    const save = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
    u.note('048 console while dirty', consoleMsgs.slice(0, 10));
    u.note('048 save node', save && { x: save.x, y: save.y, w: save.w, h: save.h });
    await page.mouse.move(save.x, save.y); await page.waitForTimeout(600);
    await u.shot('v048r-hover-save-position');
    await rt.clickAt(page, save.x, save.y, 2500);
    await rt.enableSemantics(page);
    u.note('048 after mouse click at the node', { snackbar: await u.has(/Payment saved/), apiNotes: (await api('GET', `/api/payments/${D.pay.id}`)).json.notes });
    await u.shot('v048r-after-click');
  });

  const left = async () => (await rt.semantics(page)).filter((n) => n.x < 320).map((n) => ({ l: (n.label || n.text).replace(/\n/g, ' | ').slice(0, 40), role: n.role, x: n.x, y: n.y }));

  await u.step('045 picker, full dump', async () => {
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await rt.tap(page, /^Collection POC \*/, { wait: 2500 });
    await rt.enableSemantics(page);
    const dlg = async () => (await rt.semantics(page)).filter((n) => n.x > 400 && n.x < 970 && n.y > 150 && n.y < 750)
      .map((n) => (n.label || n.text).replace(/\n/g, ' | ').slice(0, 60));
    u.note('045 dialog labels before typing', (await dlg()).slice(0, 12));
    await page.keyboard.type(D.pocB.username, { delay: 30 });
    await page.waitForTimeout(3000);
    await rt.enableSemantics(page);
    u.note('045 dialog labels 3 s after typing full username', (await dlg()).slice(0, 12));
    await u.shot('v045r-picker-3s');
    await page.keyboard.press('End');
    await page.keyboard.type('x'); await page.keyboard.press('Backspace');
    await page.waitForTimeout(2000); await rt.enableSemantics(page);
    u.note('045 dialog labels after x+Backspace (same text)', (await dlg()).slice(0, 12));
    await u.shot('v045r-picker-after-extra-keys');
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
  });

  await u.step('052 nav away while dirty', async () => {
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, `#/payments/${D.pay.id}`);
    u.note('052 left-side nodes', await left());
    await notesBox();
    await rt.typeText(page, 'lost edit', { clear: true });
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    u.note('052 dirty indicator', await u.has(/Unsaved changes/));
    const nav = (await rt.semantics(page)).find((n) => n.x < 320 && /Invoices/.test(n.label || n.text || ''));
    u.note('052 sidebar Invoices node', nav && { x: nav.x, y: nav.y, role: nav.role, l: nav.label || nav.text });
    await rt.clickAt(page, nav.x, nav.y, 2500);
    await rt.enableSemantics(page);
    u.note('052 after sidebar Invoices', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052-a-after-sidebar');
    if ((await u.has(/Discard unsaved/)).length) await rt.tap(page, 'Discard', { wait: 2000 });
    u.note('052 API notes after sidebar', (await api('GET', `/api/payments/${D.pay.id}`)).json.notes);

    await rt.go(page, `#/payments/${D.pay.id}`);
    await notesBox();
    await rt.typeText(page, 'lost edit 2', { clear: true });
    await page.waitForTimeout(600);
    await rt.tap(page, new RegExp(D.I2.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('052 after allocation row', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052-b-after-allocation');
    if ((await u.has(/Discard unsaved/)).length) await rt.tap(page, 'Discard', { wait: 2000 });
    u.note('052 API notes after allocation', (await api('GET', `/api/payments/${D.pay.id}`)).json.notes);

    await rt.go(page, `#/payments/${D.pay.id}`);
    await notesBox();
    await rt.typeText(page, 'lost edit 3', { clear: true });
    await page.waitForTimeout(600);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.enableSemantics(page);
    u.note('052 control: Back button', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052-c-back-control');
  });

  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-rerun-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
