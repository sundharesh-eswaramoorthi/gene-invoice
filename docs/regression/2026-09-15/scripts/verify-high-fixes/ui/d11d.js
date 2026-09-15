// U-19 re-run extension: several refused in-app navigations in a row must not grow browser history;
// after saving, a single browser Back navigates with no prompt.
// Usage: node d11d.js desktop|phone   (desktop: invoice inv4b, phone: invoice inv7). Writes d11d-<mode>.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const MODES = {
  desktop: { w: 1366, h: 900, id: S.inv4b.id },
  phone: { w: 400, h: 820, id: S.inv7.id },
};
const RAIL = { Dashboard: 85, Payments: 173 };

(async () => {
  const mode = process.argv[2];
  const M = MODES[mode];
  const detail = `#/invoices/${M.id}`;
  const out = { mode, detail, steps: [] };
  const T = await rt.adminToken();
  out.serverBefore = (await rt.api('GET', `/api/invoices/${M.id}`, { token: T })).json?.notes;
  const app = await rt.openApp({ token: T, width: M.w, height: M.h });
  const { page } = app;
  const settle = async (ms = 1500) => { await page.waitForTimeout(ms); await rt.enableSemantics(page); };
  const st = async () => ({
    hash: await page.evaluate(() => location.hash),
    historyLength: await page.evaluate(() => history.length),
    dialogs: (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length,
  });
  async function attempt(name, action) {
    await action();
    await settle(1200);
    const s = { name, ...(await st()) };
    if (s.dialogs) {
      await rt.tap(page, 'Keep editing', { wait: 1500 });
      await settle(600);
      s.afterKeep = await st();
    }
    out.steps.push(s);
  }
  const nav = async (label) => {
    if (M.w < 900) {
      await rt.clickAt(page, 28, 28, 1500);
      await rt.enableSemantics(page);
      const item = (await rt.semantics(page)).find((x) => lab(x) === label && x.x < 320);
      await rt.clickAt(page, item.x, item.y, 600);
    } else {
      await rt.clickAt(page, 110, RAIL[label], 600);
    }
  };
  try {
    await rt.go(page, '#/invoices');
    await rt.go(page, detail, 4000);
    out.opened = await st();
    for (let i = 0; i < 3; i++) {
      const n = await rt.semantics(page);
      const l = n.find((x) => lab(x) === 'Notes' && x.role !== 'button');
      await rt.clickAt(page, Math.min(Math.round(l.x + l.w / 2 + 60), M.w - 40), l.y + 8, 800);
      out.typed = `vu-d11d-${Date.now().toString(36)}`;
      await rt.typeText(page, out.typed, { clear: true });
      await settle(800);
      if ((await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes')) break;
    }
    out.dirty = await st();
    await attempt(mode === 'phone' ? 'drawer Payments' : 'sidebar Payments', () => nav('Payments'));
    await attempt('bell (off badge)', async () => {
      const b = (await rt.semantics(page)).find((x) => lab(x) === 'Notifications');
      await rt.clickAt(page, b.x - 10, b.y + 12, 600);
    });
    await attempt(mode === 'phone' ? 'drawer Dashboard' : 'sidebar Dashboard', () => nav('Dashboard'));
    await attempt('Back arrow', async () => {
      const b = await rt.find(page, 'Back');
      await rt.clickAt(page, b.x, b.y, 600);
    });
    out.shotAfterRefusals = await rt.shot(page, DIR, `d11d-${mode}-1-after-refusals`);
    // Save, then one browser Back.
    const save = (await rt.semantics(page)).find((x) => lab(x) === 'Save changes');
    await rt.clickAt(page, save.x, save.y, 3000);
    await settle(800);
    out.afterSave = await st();
    out.serverAfterSave = (await rt.api('GET', `/api/invoices/${M.id}`, { token: T })).json?.notes;
    await page.goBack();
    await settle(2000);
    out.backAfterSave = await st();
    out.shotBackAfterSave = await rt.shot(page, DIR, `d11d-${mode}-2-back-after-save`);
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, `d11d-${mode}-error`);
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, `d11d-${mode}.json`), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, (k, v) => (/shot/i.test(k) ? undefined : v), 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
