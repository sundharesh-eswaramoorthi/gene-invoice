// UI 3 (exploration): Customer Details layout and Products screen / New product dialog.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const data = JSON.parse(fs.readFileSync(path.join(D, 'ui-data.json')));
const dump = async (page, name) => {
  const s = await rt.semantics(page);
  fs.mkdirSync(path.join(D, 'sem'), { recursive: true });
  fs.writeFileSync(path.join(D, 'sem', name + '.txt'), s.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  return s;
};
(async () => {
  const A = await rt.adminToken();
  const app = await rt.openApp({ token: A });
  const { page } = app;
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${data.b}`);
  console.log('detail shot', await rt.shot(page, D, 'u05-detail'));
  await dump(page, 'u05-detail');
  await rt.go(page, '#/products?f=' + encodeURIComponent(`name:contains:${data.TAG}`));
  console.log('products shot', await rt.shot(page, D, 'u10-products'));
  await dump(page, 'u10-products');
  await rt.tap(page, 'New product');
  console.log('product dialog shot', await rt.shot(page, D, 'u10-product-dialog'));
  await dump(page, 'u10-product-dialog');
  await rt.tap(page, 'Cancel');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI3 FAILED', e); process.exit(1); });
