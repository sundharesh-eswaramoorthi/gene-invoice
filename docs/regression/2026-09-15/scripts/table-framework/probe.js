const rt = require('../lib.js');
const S = require('./state.json');
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  await rt.go(app.page, `#/invoices?size=10&f=${encodeURIComponent('customerId:eq:' + S.A)}`, 5000);
  console.log('URL', app.page.url());
  const nodes = await rt.semantics(app.page);
  console.log(nodes.map((n) => `[${n.role}] ${(n.label || n.text).slice(0, 60)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  console.log('shot', await rt.shot(app.page, __dirname, 'probe-invoices'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
