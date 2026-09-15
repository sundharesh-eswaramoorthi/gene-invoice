// UI probe: list, new form, detail (+tabs) as admin. Dumps semantics + screenshots.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const dump = async (page, tag) => {
  const n = await rt.semantics(page);
  console.log(`\n== ${tag} (${n.length})\n` + n.map((x) => `[${x.role}] ${JSON.stringify(x.label || x.text).slice(0, 90)} @${x.x},${x.y} ${x.w}x${x.h}`).join('\n'));
};
(async () => {
  const A = await rt.adminToken();
  const app = await rt.openApp({ token: A });
  const { page } = app;
  const E = S.custEmpty.id;
  await rt.go(page, `#/invoices?size=10&f=${encodeURIComponent(`customerId:eq:${E}`)}`, 5000);
  await dump(page, 'list filtered');
  console.log(await rt.shot(page, __dirname, 'p1-list-filtered'));
  await rt.go(page, '#/invoices', 5000);
  console.log(await rt.shot(page, __dirname, 'p2-list-all'));
  await dump(page, 'list all');
  await rt.go(page, '#/invoices/new', 5000);
  await dump(page, 'new form');
  console.log(await rt.shot(page, __dirname, 'p3-new-form'));
  await rt.go(page, `#/invoices/${S.eInv[1].id}`, 5000);
  await dump(page, 'detail');
  console.log(await rt.shot(page, __dirname, 'p4-detail'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('ERR', e); process.exit(1); });
