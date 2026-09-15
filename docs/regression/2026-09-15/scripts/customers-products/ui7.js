// UI 7: invoice form pickers load only the first 50 customers/products by name (candidate 13).
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
  const tag = rt.uniq('zzzcp');
  const prod = (await rt.api('POST', '/api/products', { token: A, body: { name: `${tag} late product`, price: 3 } })).json;
  const cust = await rt.createCustomer(A, tag);
  const activeProducts = (await rt.api('GET', '/api/products?size=10&filter=' + encodeURIComponent('active:eq:true'), { token: A })).json.totalElements;
  const allProducts = (await rt.api('GET', '/api/products?size=10', { token: A })).json.totalElements;
  const allCustomers = (await rt.api('GET', '/api/customers?size=10', { token: A })).json.totalElements;
  console.log('totals', JSON.stringify({ activeProducts, allProducts, allCustomers }));

  const app = await rt.openApp({ token: A });
  const { page } = app;
  const seen = {};
  page.on('response', async (r) => {
    const u = r.url();
    if (r.request().method() === 'GET' && (u.includes('/api/products?') || u.includes('/api/customers?'))) {
      try { const j = await r.json(); seen[decodeURIComponent(u.replace(rt.API, ''))] = { rows: j.content.length, total: j.totalElements, names: j.content.map((x) => x.name) }; } catch {}
    }
  });
  await rt.go(page, '#/invoices');
  await rt.go(page, '#/invoices/new', 5000);
  let s = await dump(page, 'u23-form');
  const reqs = Object.entries(seen).map(([u, v]) => ({ url: u, rows: v.rows, total: v.total, hasLateProduct: v.names.some((n) => n.includes(tag + ' late')), hasLateCustomer: v.names.some((n) => n.includes(cust.name)) }));
  console.log('RESULT requests', JSON.stringify(reqs));

  // open product menu and scroll to its end to show the last entry offered
  const prodField = s.find((n) => /^Product/.test(n.label || n.text || ''));
  await rt.clickAt(page, prodField.x, prodField.y, 1500);
  for (let i = 0; i < 15; i++) { await page.mouse.wheel(0, 800); await page.waitForTimeout(150); }
  await page.waitForTimeout(800);
  s = await dump(page, 'u23-product-menu-end');
  console.log('menu end shot', await rt.shot(page, D, 'u23-product-menu-end'));
  console.log('RESULT menuEnd', JSON.stringify({ lateOffered: s.some((n) => (n.label || n.text || '').includes(tag)), lastItems: s.slice(-6).map((n) => n.label || n.text) }));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  fs.writeFileSync(path.join(D, 'ui7-data.json'), JSON.stringify({ tag, prod: prod.id, cust: cust.id }));
})().catch((e) => { console.error('UI7 FAILED', e); process.exit(1); });
