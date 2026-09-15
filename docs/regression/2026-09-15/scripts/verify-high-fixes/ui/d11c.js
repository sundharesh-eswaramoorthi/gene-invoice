// D-11 follow-up 2: (1) does the bell's unread badge swallow taps on a clean page? (2) how many
// browser Back presses are dead after a blocked sidebar/drawer navigation + Keep editing?
// Usage: node d11c.js desktop|phone   Writes d11c-<mode>.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const MODES = {
  desktop: { w: 1366, h: 900, list: '#/invoices', path: `#/invoices/${S.inv11.id}`, api: `/api/invoices/${S.inv11.id}` },
  phone: { w: 400, h: 820, list: '#/invoices', path: `#/invoices/${S.inv4.id}`, api: `/api/invoices/${S.inv4.id}` },
};

(async () => {
  const mode = process.argv[2];
  const M = MODES[mode];
  const out = { mode };
  const T = await rt.adminToken();
  out.serverBefore = (await rt.api('GET', M.api, { token: T })).json?.notes;
  const app = await rt.openApp({ token: T, width: M.w, height: M.h });
  const { page } = app;
  const settle = async (ms = 1500) => { await page.waitForTimeout(ms); await rt.enableSemantics(page); };
  const st = async () => ({
    hash: await page.evaluate(() => location.hash),
    historyLength: await page.evaluate(() => history.length),
    dialogs: (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length,
  });
  const open = async () => { await rt.go(page, M.list); await rt.go(page, M.path, 4000); };
  const badge = async () => (await rt.semantics(page)).find((x) => /^(99\+|\d+)$/.test(lab(x)) && x.y < 50);
  const bell = async () => (await rt.semantics(page)).find((x) => lab(x) === 'Notifications');
  try {
    // (1) clean page, click the badge
    await open();
    const b = await badge();
    out.badge = b && `${lab(b)} @${b.x},${b.y} ${b.w}x${b.h}`;
    if (b) {
      await rt.clickAt(page, b.x, b.y, 800);
      await settle();
      out.cleanBadgeClick = await st();
      out.cleanBadgeShot = await rt.shot(page, DIR, `d11c-${mode}-1-clean-badge-click`);
      if (out.cleanBadgeClick.hash !== M.path) await open();
    }
    const bl = await bell();
    await rt.clickAt(page, bl.x - 10, bl.y + 12, 800);
    await settle();
    out.cleanBellOffBadge = await st();
    // (2) dead Back presses after a blocked navigation
    await open();
    for (let attempt = 0; attempt < 3; attempt++) {
      const n = await rt.semantics(page);
      const l = n.find((x) => lab(x) === 'Notes' && x.role !== 'button');
      await rt.clickAt(page, Math.min(Math.round(l.x + l.w / 2 + 60), M.w - 40), l.y + 8, 800);
      await rt.typeText(page, `vu-d11c-${Date.now().toString(36)}`, { clear: true });
      await settle(800);
      if ((await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes')) break;
    }
    out.dirty = await st();
    out.dirtyConfirmed = (await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes');
    out.dirtyShot = await rt.shot(page, DIR, `d11c-${mode}-2a-dirty`);
    if (M.w < 900) {
      await rt.clickAt(page, 28, 28, 1500);
      await rt.enableSemantics(page);
      const item = (await rt.semantics(page)).find((x) => lab(x) === 'Payments' && x.x < 320);
      await rt.clickAt(page, item.x, item.y, 800);
    } else {
      await rt.clickAt(page, 110, 173, 800);
    }
    await settle(1200);
    out.blocked = await st();
    out.blockedShot = await rt.shot(page, DIR, `d11c-${mode}-2b-blocked`);
    if (out.blocked.dialogs) await rt.tap(page, 'Keep editing', { wait: 1500 });
    await settle(800);
    out.afterKeep = await st();
    out.afterKeepShot = await rt.shot(page, DIR, `d11c-${mode}-2c-after-keep`);
    out.backs = [];
    for (let i = 0; i < 5; i++) {
      await page.goBack();
      await settle();
      const s = await st();
      out.backs.push(s);
      if (s.dialogs) {
        s.shot = await rt.shot(page, DIR, `d11c-${mode}-2-back-${i + 1}-dialog`);
        await rt.tap(page, 'Discard', { wait: 2500 });
        s.afterDiscard = await st();
        break;
      }
    }
    out.serverAfter = (await rt.api('GET', M.api, { token: T })).json?.notes;
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, `d11c-${mode}-error`);
  } finally {
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, `d11c-${mode}.json`), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
