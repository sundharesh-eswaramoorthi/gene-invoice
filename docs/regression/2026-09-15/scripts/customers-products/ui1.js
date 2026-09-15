// UI exploration 1: customers list, tiles, New customer dialog + validation, narrow layout.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const dump = async (page, name) => {
  const s = await rt.semantics(page);
  fs.mkdirSync(path.join(D, 'sem'), { recursive: true });
  fs.writeFileSync(path.join(D, 'sem', name + '.txt'), s.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  return s;
};
(async () => {
  const A = await rt.adminToken();
  const TAG = rt.uniq('cpui');
  const sales = await rt.createStaff(A, 'SALES_POC', 'cpui');
  const mk = async (s) => (await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} ${s}`, phone: '555-7', email: `${TAG}-${s}@rt.local`, address: '9 UI St', username: `${TAG}-${s}`, password: rt.PASSWORD } })).json;
  const a = await mk('one'); const b = await mk('two');
  const prod = (await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} prod`, price: 120 } })).json;
  await rt.api('POST', '/api/invoices', { token: A, body: { customerId: a.id, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1 }] } });
  fs.writeFileSync(path.join(D, 'ui-data.json'), JSON.stringify({ TAG, a: a.id, b: b.id, prod: prod.id, sales: sales.id }, null, 2));

  const app = await rt.openApp({ token: A });
  const { page } = app;
  await rt.go(page, '#/customers?f=' + encodeURIComponent(`name:contains:${TAG}`));
  console.log('list shot', await rt.shot(page, D, 'u01-list'));
  await dump(page, 'u01-list');
  await rt.tap(page, 'New customer');
  console.log('dialog shot', await rt.shot(page, D, 'u02-dialog'));
  await dump(page, 'u02-dialog');
  await rt.tap(page, 'Save');
  console.log('validation shot', await rt.shot(page, D, 'u02-validation'));
  await dump(page, 'u02-validation');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();

  const n = await rt.openApp({ token: A, width: 400, height: 800 });
  await rt.go(n.page, '#/customers?f=' + encodeURIComponent(`name:contains:${TAG}`));
  console.log('narrow shot', await rt.shot(n.page, D, 'u01-narrow'));
  await dump(n.page, 'u01-narrow');
  console.log('narrow apiErrors', JSON.stringify(n.apiErrors), 'pageErrors', JSON.stringify(n.pageErrors));
  await n.close();
})().catch((e) => { console.error('UI1 FAILED', e); process.exit(1); });
