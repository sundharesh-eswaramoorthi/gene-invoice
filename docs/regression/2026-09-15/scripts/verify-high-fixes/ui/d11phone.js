// D-11 at phone width 400x820: the drawer and the bell ask about unsaved edits. Uses the D-04
// customer and invoice so it never races the desktop D-11 run. Writes d11phone.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const hash = (page) => page.evaluate(() => location.hash);
const dialogCount = async (page) => (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length;

const KINDS = [
  { kind: 'invoice', list: '#/invoices', path: `#/invoices/${S.inv4.id}`, field: 'Notes', api: `/api/invoices/${S.inv4.id}`, prop: 'notes' },
  { kind: 'customer', list: '#/customers', path: `#/customers/${S.c4.id}`, field: 'Phone', api: `/api/customers/${S.c4.id}`, prop: 'phone' },
];

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 400, height: 820 });
  const { page } = app;
  try {
    for (const K of KINDS) {
      const o = (out[K.kind] = {});
      o.serverBefore = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
      await rt.go(page, K.list);
      await rt.go(page, K.path, 4000);
      let n = await rt.semantics(page);
      const l = n.find((x) => lab(x) === K.field && x.role !== 'button');
      o.labelNode = l && `${l.x},${l.y} ${l.w}x${l.h}`;
      await rt.clickAt(page, Math.min(Math.round(l.x + l.w / 2 + 60), 360), l.y + 8, 600);
      await rt.typeText(page, `vu-phone-${Date.now().toString(36)}`, { clear: true });
      await page.waitForTimeout(600);
      o.shotDirty = await rt.shot(page, DIR, `d11p-${K.kind}-1-dirty`);
      // drawer -> Payments, Keep editing
      await rt.clickAt(page, 28, 28, 1500);
      await rt.enableSemantics(page);
      o.shotDrawer = await rt.shot(page, DIR, `d11p-${K.kind}-2-drawer`);
      let item = (await rt.semantics(page)).find((x) => lab(x) === 'Payments' && x.x < 320);
      await rt.clickAt(page, item.x, item.y, 1500);
      await rt.enableSemantics(page);
      o.drawerPayments = { dialogs: await dialogCount(page), hash: await hash(page) };
      o.shotDrawerDialog = await rt.shot(page, DIR, `d11p-${K.kind}-3-drawer-dialog`);
      if (o.drawerPayments.dialogs) {
        await rt.tap(page, 'Keep editing', { wait: 1500 });
        await rt.enableSemantics(page);
        o.drawerPayments.hashAfterKeep = await hash(page);
        o.drawerPayments.dialogsAfterKeep = await dialogCount(page);
        o.shotAfterKeep = await rt.shot(page, DIR, `d11p-${K.kind}-4-after-keep`);
      }
      // bell
      const bell = (await rt.semantics(page)).find((x) => lab(x) === 'Notifications');
      await rt.clickAt(page, bell.x, bell.y, 1500);
      await rt.enableSemantics(page);
      o.bell = { dialogs: await dialogCount(page), hash: await hash(page) };
      if (o.bell.dialogs) await rt.tap(page, 'Keep editing', { wait: 1500 });
      // drawer -> Dashboard, Discard
      await rt.clickAt(page, 28, 28, 1500);
      await rt.enableSemantics(page);
      item = (await rt.semantics(page)).find((x) => lab(x) === 'Dashboard' && x.x < 320);
      await rt.clickAt(page, item.x, item.y, 1500);
      await rt.enableSemantics(page);
      o.drawerDashboard = { dialogs: await dialogCount(page) };
      if (o.drawerDashboard.dialogs) await rt.tap(page, 'Discard', { wait: 2500 });
      await rt.enableSemantics(page);
      o.drawerDashboard.hashAfterDiscard = await hash(page);
      o.drawerDashboard.dialogsAfterDiscard = await dialogCount(page);
      o.shotAfterDiscard = await rt.shot(page, DIR, `d11p-${K.kind}-5-after-discard`);
      o.serverAfter = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
    }
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd11p-error');
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd11phone.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
