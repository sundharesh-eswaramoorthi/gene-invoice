// Probe (read-only): where History-tab record links and Disputes-tab rows sit on Customer,
// Invoice and Payment details, so d11r.js can click them. Writes d11probe.json + shots.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 140)} @${n.x},${n.y} ${n.w}x${n.h}`;
const KINDS = {
  customer: { list: '#/customers', path: `#/customers/${S.c11.id}` },
  invoice: { list: '#/invoices', path: `#/invoices/${S.inv11.id}` },
  payment: { list: '#/payments', path: `#/payments/${S.pay11.id}` },
};

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  const out = {};
  try {
    for (const [kind, K] of Object.entries(KINDS)) {
      out[kind] = {};
      await rt.go(page, K.list);
      await rt.go(page, K.path, 4000);
      for (const tab of ['History', 'Disputes']) {
        const t = (await rt.semantics(page)).find((x) => x.role === 'tab' && lab(x).includes(tab));
        await rt.clickAt(page, t.x, t.y, 3000);
        await rt.enableSemantics(page);
        const n = await rt.semantics(page);
        out[kind][tab] = n.filter((x) => x.y > 500).map(fmt);
        out[kind][tab + 'Shot'] = await rt.shot(page, DIR, `d11probe-${kind}-${tab}`);
      }
    }
  } catch (e) {
    out.error = String(e.stack || e);
  } finally {
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd11probe.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
