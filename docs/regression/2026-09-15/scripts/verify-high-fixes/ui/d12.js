// D-12: phone app bar — hamburger opens the drawer, bell does not overlap it, title visible,
// account menu heads with username/role; desktop label capped with ellipsis. Writes d12.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, key, name) {
  const nodes = await rt.semantics(page);
  out[key]['sem_' + name] = nodes.map(fmt);
  out[key]['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}

const users = [
  { key: 'longColl', u: S.longColl.username, p: S.longColl.password },
  { key: 'admin', u: 'admin', p: 'admin123' },
  { key: 'viewer', u: S.viewer.username, p: S.viewer.password },
  { key: 'customer', u: S.c12.username, p: S.c12.password },
];

(async () => {
  for (const usr of users) {
    const o = (out[usr.key] = { username: usr.u });
    const token = await rt.login(usr.u, usr.p);
    const app = await rt.openApp({ token, width: 400, height: 820 });
    const { page } = app;
    try {
      await rt.go(page, '#/', 4000);
      let n = await snap(page, usr.key, `d12-${usr.key}-1-phone-bar`);
      o.barNodes = n.filter((x) => x.y < 60).map(fmt);
      o.hashBefore = await page.evaluate(() => location.hash);
      await rt.clickAt(page, 28, 28, 2000);
      await rt.enableSemantics(page);
      n = await snap(page, usr.key, `d12-${usr.key}-2-after-hamburger`);
      o.hashAfterHamburger = await page.evaluate(() => location.hash);
      o.drawerNodes = n.filter((x) => x.x < 320 && x.y > 60).map(fmt).slice(0, 12);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(1200);
      await rt.go(page, '#/', 3000);
      n = await rt.semantics(page);
      const acct = n.find((x) => x.y < 60 && x.x > 300 && x.role === 'button' && !/Notifications/.test(lab(x)));
      o.accountButton = fmt(acct);
      if (acct) {
        await rt.clickAt(page, acct.x, acct.y, 1500);
        await rt.enableSemantics(page);
        n = await snap(page, usr.key, `d12-${usr.key}-3-account-menu`);
        o.menuNodes = n.filter((x) => x.y > 40).map(fmt).slice(0, 10);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(800);
      }
    } catch (e) {
      o.error = String(e.stack || e);
      await rt.shot(page, DIR, `d12-${usr.key}-error`);
    } finally {
      o.pageErrors = app.pageErrors.slice(0, 5);
      await app.close();
    }
  }

  // Desktop: label next to the account icon, capped with an ellipsis.
  for (const usr of [users[0], users[1]]) {
    const key = usr.key + '_desktop';
    const o = (out[key] = {});
    const token = await rt.login(usr.u, usr.p);
    const app = await rt.openApp({ token, width: 1366, height: 900 });
    try {
      await rt.go(app.page, '#/', 4000);
      const n = await snap(app.page, key, `d12-${key}-bar`);
      o.barNodes = n.filter((x) => x.y < 60).map(fmt);
      await app.page.screenshot({ path: path.join(DIR, 'shots', `d12-${key}-bar-crop.png`), clip: { x: 900, y: 0, width: 466, height: 60 } });
    } catch (e) {
      o.error = String(e.stack || e);
    } finally {
      await app.close();
    }
  }
  fs.writeFileSync(path.join(DIR, 'd12.json'), JSON.stringify(out, null, 1));
  const brief = JSON.parse(JSON.stringify(out, (k, v) => (k.startsWith('sem_') ? undefined : v)));
  console.log(JSON.stringify(brief, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
