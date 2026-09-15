// UI-017: invoice form customer/product dropdowns capped at the first 50 by name.
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
  const tag = rt.uniq('zzzvcp');
  const prod = (await rt.api('POST', '/api/products', { token: A, body: { name: `${tag} late product`, price: 3 } })).json;
  const cust = (await rt.api('POST', '/api/customers', { token: A, body: { name: `${tag} late customer`, username: tag, password: rt.PASSWORD } })).json;
  const tot = async (p) => (await rt.api('GET', p, { token: A })).json.totalElements;
  console.log('totals', JSON.stringify({ products: await tot('/api/products?size=1'), activeProducts: await tot('/api/products?size=1&filter=' + encodeURIComponent('active:eq:true')), customers: await tot('/api/customers?size=1') }));

  const app = await rt.openApp({ token: A });
  const { page } = app;
  const seen = [];
  page.on('response', async (r) => {
    const u = r.url();
    if (r.request().method() === 'GET' && (u.includes('/api/products?') || u.includes('/api/customers?'))) {
      try { const j = await r.json(); seen.push({ url: decodeURIComponent(u.replace(rt.API, '')), rows: j.content.length, total: j.totalElements, inactiveInPage: j.content.filter((x) => x.active === false).length, hasLate: j.content.some((x) => x.name.startsWith(tag)), last: j.content[j.content.length - 1]?.name }); } catch {}
    }
  });
  await rt.go(page, '#/invoices');
  seen.length = 0;
  await rt.go(page, '#/invoices/new', 5000);
  let s = await dump(page, 'c-form');
  console.log('form shot', await rt.shot(page, D, 'c-form'));
  console.log('RESULT requests', JSON.stringify(seen));

  const custField = s.find((n) => /^Customer/.test(n.label || n.text || ''));
  console.log('customer field', JSON.stringify(custField));
  await rt.clickAt(page, custField.x, custField.y, 1500);
  for (let i = 0; i < 25; i++) { await page.mouse.wheel(0, 800); await page.waitForTimeout(120); }
  await page.waitForTimeout(800);
  s = await dump(page, 'c-customer-menu-end');
  console.log('customer menu end shot', await rt.shot(page, D, 'c-customer-menu-end'));
  console.log('RESULT customerMenu', JSON.stringify({ lateOffered: s.some((n) => (n.label || n.text || '').includes(tag)), lastItems: s.slice(-5).map((n) => n.label || n.text) }));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
  s = await rt.semantics(page);
  const prodField = s.find((n) => /^Product/.test(n.label || n.text || ''));
  await rt.clickAt(page, prodField.x, prodField.y, 1500);
  for (let i = 0; i < 25; i++) { await page.mouse.wheel(0, 800); await page.waitForTimeout(120); }
  await page.waitForTimeout(800);
  s = await dump(page, 'c-product-menu-end');
  console.log('product menu end shot', await rt.shot(page, D, 'c-product-menu-end'));
  console.log('RESULT productMenu', JSON.stringify({ lateOffered: s.some((n) => (n.label || n.text || '').includes(tag)), lastItems: s.slice(-5).map((n) => n.label || n.text) }));
  await page.keyboard.press('Escape');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  // cleanup: deactivate the product so it does not linger in other testers' pickers
  await rt.api('PUT', `/api/products/${prod.id}`, { token: A, body: { name: prod.name, price: 3, active: false } });
})().catch((e) => { console.error('UI-C FAILED', e); process.exit(1); });
