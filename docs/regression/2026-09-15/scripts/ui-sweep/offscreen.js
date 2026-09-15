// Which table columns / row actions sit beyond the right edge of the window on desktop lists?
const rt = require('../lib.js');
const DIR = __dirname + '/offscreen';
(async () => {
  for (const [who, width] of [['admin', 1366], ['admin', 1920], ['cust', 1366]]) {
    const S = require('./setup.json');
    const token = who === 'admin' ? await rt.adminToken() : await rt.login(S.cust.username, S.password);
    const app = await rt.openApp({ token, width, height: 900 });
    const pages = who === 'admin' ? ['#/invoices', '#/payments', '#/customers', '#/products', '#/users'] : ['#/invoices', '#/promises'];
    for (const h of pages) {
      await rt.go(app.page, h, 4500);
      const nodes = await rt.semantics(app.page);
      const off = nodes.filter((n) => n.x > width).map((n) => `${n.label || n.text}@${n.x}`);
      const hdr = nodes.filter((n) => n.y > 190 && n.y < 260).map((n) => `${n.label || n.text}@${n.x}`);
      const file = await rt.shot(app.page, DIR, `${who}-${width}-${h.slice(2)}`);
      console.log(`${who} ${width} ${h}\n  header: ${hdr.join(' | ')}\n  offscreen(${off.length}): ${[...new Set(off.map((o) => o.split('@')[0]))].slice(0, 12).join(' | ')}\n  ${file}`);
    }
    console.log('api', JSON.stringify(app.apiErrors), 'errs', JSON.stringify(app.pageErrors));
    await app.close();
  }
})().catch((e) => { console.error(e); process.exit(1); });
