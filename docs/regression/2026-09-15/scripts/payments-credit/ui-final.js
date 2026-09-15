// Final checks: Save button reachability with a blurred notes field; edits kept after "Keep editing".
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
  const saveNode = async () => (await rt.semantics(page)).find((n) => /Save changes/.test(n.label || n.text || ''));

  await u.step('Save reachability (notes blurred)', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await u.clickInput((x) => x.tag === 'TEXTAREA');
    await rt.typeText(page, 'reach check', { clear: true });
    await rt.clickAt(page, 292, 290, 800); // click the "Notes" label to blur the field
    await rt.enableSemantics(page);
    const s = await saveNode();
    u.note('Save node after blur (1366x900)', s ? { x: s.x, y: s.y, h: s.h } : 'none');
    u.note('Unsaved text', (await rt.semantics(page)).filter((n) => /Unsaved/.test(n.label || n.text || '')).map((n) => ({ y: n.y, h: n.h })));
    await u.shot('k01-dirty-blurred');
    // a real mouse click where the node says the button is
    if (s) {
      await rt.clickAt(page, s.x, s.y, 2500);
      await rt.enableSemantics(page);
      u.note('after a real mouse click on the Save node position: snackbar', await u.has(/Payment saved/));
      u.note('API notes', (await api('GET', `/api/payments/${pid}`)).json.notes);
      await u.shot('k02-after-mouse-save');
    }
  });

  await u.step('Keep editing keeps the edit', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await u.clickInput((x) => x.tag === 'TEXTAREA');
    await rt.typeText(page, 'kept after dialog', { clear: true });
    await page.waitForTimeout(400);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.tap(page, 'Keep editing', { wait: 1500 });
    await rt.enableSemantics(page);
    u.note('after Keep editing', { hash: await u.hash(), textOnScreen: await u.has(/kept after dialog/), unsaved: await u.has(/Unsaved changes/) });
    await u.shot('k03-after-keep-editing');
    // leave cleanly without saving
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.tap(page, 'Discard', { wait: 2000 });
    u.note('API notes unchanged', (await api('GET', `/api/payments/${pid}`)).json.notes);
  });

  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-final-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
