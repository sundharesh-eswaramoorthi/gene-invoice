// Third UI pass: 048 (blind click at the Save node, console) and 052 (sidebar click by position, allocation row, Back control).
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
  const consoleMsgs = [];
  page.on('console', (m) => { if (['error', 'warning'].includes(m.type()) || /exception|overflow|RenderFlex|laid out/i.test(m.text())) consoleMsgs.push(`${m.type()}: ${m.text().slice(0, 300)}`); });
  const notesBox = () => u.clickInput((x) => x.tag === 'TEXTAREA');
  const findSave = async () => {
    for (let i = 0; i < 5; i++) {
      await rt.enableSemantics(page);
      const s = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
      if (s) return s;
      await page.waitForTimeout(800);
    }
    return null;
  };

  await u.step('048 blind click', async () => {
    await rt.go(page, '#/payments', 3000);
    await rt.go(page, `#/payments/${D.pay.id}`, 4000);
    consoleMsgs.length = 0;
    await notesBox();
    await rt.typeText(page, 'blind save 048', { clear: true });
    await page.waitForTimeout(1200);
    const save = await findSave();
    u.note('048 console while dirty', consoleMsgs.slice(0, 10));
    u.note('048 save node', save && { x: save.x, y: save.y, w: save.w, h: save.h });
    if (!save) throw new Error('Save node not found: ' + JSON.stringify((await u.labels()).slice(0, 30)));
    await page.mouse.move(save.x, save.y); await page.waitForTimeout(600);
    await u.shot('v048p3-hover-save-position');
    await rt.clickAt(page, save.x, save.y, 2500);
    await rt.enableSemantics(page);
    u.note('048 after mouse click at the node', { snackbar: await u.has(/Payment saved/), apiNotes: (await api('GET', `/api/payments/${D.pay.id}`)).json.notes });
    await u.shot('v048p3-after-click');
  });

  await u.step('052 sidebar by position', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`, 4000);
    await notesBox();
    await rt.typeText(page, 'lost edit', { clear: true });
    await page.waitForTimeout(700);
    await rt.enableSemantics(page);
    u.note('052 dirty indicator before nav', await u.has(/Unsaved changes/));
    await rt.clickAt(page, 104, 129, 2500); // sidebar "Invoices" (position from the screenshots)
    await rt.enableSemantics(page);
    u.note('052 after sidebar Invoices', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052p3-a-after-sidebar');
    if ((await u.has(/Discard unsaved/)).length) await rt.tap(page, 'Discard', { wait: 2000 });
    u.note('052 API notes after sidebar', (await api('GET', `/api/payments/${D.pay.id}`)).json.notes);
  });

  await u.step('052 allocation row', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`, 4000);
    await notesBox();
    await rt.typeText(page, 'lost edit 2', { clear: true });
    await page.waitForTimeout(700);
    await rt.tap(page, new RegExp(D.I2.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    u.note('052 after allocation row', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052p3-b-after-allocation');
    if ((await u.has(/Discard unsaved/)).length) await rt.tap(page, 'Discard', { wait: 2000 });
    u.note('052 API notes after allocation', (await api('GET', `/api/payments/${D.pay.id}`)).json.notes);
  });

  await u.step('052 Back control', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`, 4000);
    await notesBox();
    await rt.typeText(page, 'lost edit 3', { clear: true });
    await page.waitForTimeout(700);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.enableSemantics(page);
    u.note('052 control: Back button', { hash: await u.hash(), prompt: await u.has(/Discard unsaved/) });
    await u.shot('v052p3-c-back-control');
  });

  u.note('console (all)', consoleMsgs.slice(0, 10));
  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-pass3-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
