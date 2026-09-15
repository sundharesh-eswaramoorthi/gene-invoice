// Quick UI probe: admin opens customer A detail; dump semantics and screenshot.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;
  console.log('dashboard', (await rt.semantics(page)).map((n) => `[${n.role}] ${n.label || n.text} @${n.x},${n.y}`).join(' | '));
  await rt.shot(page, __dirname, 'p0-dashboard');
  await rt.go(page, `#/customers/${S.custA.id}`);
  console.log('URL', page.url());
  console.log('custA', (await rt.semantics(page)).map((n) => `[${n.role}] ${n.label || n.text} @${n.x},${n.y}`).join(' | '));
  await rt.shot(page, __dirname, 'p1-custA');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
