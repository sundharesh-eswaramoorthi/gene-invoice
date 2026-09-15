// D-12 follow-up: the account menu's first row shows username and role (phone 400x820 and desktop).
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 100)} @${n.x},${n.y} ${n.w}x${n.h}`;

const users = [
  { key: 'longColl', u: S.longColl.username, p: S.longColl.password },
  { key: 'admin', u: 'admin', p: 'admin123' },
  { key: 'viewer', u: S.viewer.username, p: S.viewer.password },
  { key: 'customer', u: S.c12.username, p: S.c12.password },
];

(async () => {
  const runs = [...users.map((u) => ({ ...u, w: 400, h: 820 })), { ...users[0], w: 1366, h: 900, key: 'longColl_desktop' }];
  for (const usr of runs) {
    const o = (out[usr.key] = {});
    const token = await rt.login(usr.u, usr.p);
    const app = await rt.openApp({ token, width: usr.w, height: usr.h });
    const { page } = app;
    try {
      await rt.go(page, '#/', 4000);
      let n = await rt.semantics(page);
      const acct = n.find((x) => x.role === 'button' && /^Account/.test(lab(x)));
      o.accountButton = fmt(acct);
      await rt.clickAt(page, acct.x, acct.y, 1500);
      await rt.enableSemantics(page);
      n = await rt.semantics(page);
      o.menuNodes = n.map(fmt).filter((t) => !/^\[null\] 99\+/.test(t)).slice(0, 12);
      o.shot = await rt.shot(page, DIR, `d12m-${usr.key}-menu`);
      await page.keyboard.press('Escape');
    } catch (e) {
      o.error = String(e.stack || e);
      await rt.shot(page, DIR, `d12m-${usr.key}-error`);
    } finally {
      await app.close();
    }
  }
  fs.writeFileSync(path.join(DIR, 'd12menu.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
