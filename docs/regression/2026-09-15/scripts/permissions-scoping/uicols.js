// Opens Add filter -> Column dropdown and lists the column options, for CUSTOMER and VIEWER.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = {};
(async () => {
  for (const [role, tokFn] of [['CUSTOMER', () => rt.login(st.custA.username, rt.PASSWORD)], ['VIEWER', () => rt.login(st.viewer.username, rt.PASSWORD)]]) {
    const app = await rt.openApp({ token: await tokFn() });
    const { page } = app;
    out[role] = {};
    for (const ent of ['invoices', 'payments', 'promises']) {
      await rt.go(page, `#/${ent}`, 4000);
      try {
        await rt.tap(page, 'Add filter', { wait: 1500 });
        const col = await rt.find(page, /^Column/);
        await rt.clickAt(page, col.x, col.y, 1500);
        await rt.enableSemantics(page);
        const nodes = await rt.semantics(page);
        out[role][ent] = nodes.map((n) => n.label || n.text).filter(Boolean)
          .filter((l) => !/^(Dismiss|Add filter|Cancel|Apply|Notifications|Condition)/.test(l)).slice(0, 40);
        out[role][ent + '_shot'] = await rt.shot(page, __dirname, `${role}-${ent}-columns`);
        await page.keyboard.press('Escape'); await page.waitForTimeout(600);
        await page.keyboard.press('Escape'); await page.waitForTimeout(600);
      } catch (e) { out[role][ent] = 'ERR ' + e.message.slice(0, 300); }
      console.log(role, ent, JSON.stringify(out[role][ent]).slice(0, 600));
    }
    await app.close();
  }
  fs.writeFileSync(path.join(__dirname, 'uicols-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('UICOLS FAILED', e); process.exit(1); });
