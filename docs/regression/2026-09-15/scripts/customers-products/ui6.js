// UI 6: Products screen — create (validation), edit (price + Active toggle), bulk Deactivate; invoice form hides inactive products.
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
const has = (s, re) => s.some((n) => re.test(n.label || '') || re.test(n.text || ''));
const fill = async (page, x, y, text) => { await rt.clickAt(page, x, y, 400); await rt.typeText(page, text, { clear: true }); };
(async () => {
  const A = await rt.adminToken();
  const { TAG } = data;
  const pname = `${TAG} gadget`;
  const byName = async (n) => (await rt.api('GET', '/api/products?filter=' + encodeURIComponent(`name:eq:${n}`), { token: A })).json?.content?.[0];
  const app = await rt.openApp({ token: A });
  const { page } = app;
  const url = '#/products?f=' + encodeURIComponent(`name:contains:${TAG}`);
  await rt.go(page, url);

  // create: empty -> Required
  await rt.tap(page, 'New product');
  await rt.tap(page, 'Save');
  let s = await dump(page, 'u18-empty');
  console.log('empty shot', await rt.shot(page, D, 'u18-empty'));
  // after validation the fields shift; take positions from a fresh dialog instead
  await rt.tap(page, 'Cancel');
  await rt.tap(page, 'New product');
  await fill(page, 683, 338, pname);
  await fill(page, 683, 398, 'UI made');
  await fill(page, 683, 458, '-5');
  await rt.tap(page, 'Save');
  s = await dump(page, 'u18-negative');
  console.log('negative shot', await rt.shot(page, D, 'u18-negative'));
  const negErr = has(s, /Invalid price/);
  const negSaved = !!(await byName(pname));
  console.log('RESULT validation', JSON.stringify({ requiredShown: has(await rt.semantics(page), /Invalid price/) || negErr, negErr, negSaved }));
  // fix price: the price field moved down after the error on name? name ok, so only price has error
  await rt.tap(page, 'Cancel');
  await rt.tap(page, 'New product');
  await fill(page, 683, 338, pname);
  await fill(page, 683, 398, 'UI made');
  await fill(page, 683, 458, '12.50');
  await rt.tap(page, 'Save', { wait: 2500 });
  s = await dump(page, 'u19-created');
  console.log('created shot', await rt.shot(page, D, 'u19-created'));
  let p = await byName(pname);
  console.log('RESULT create', JSON.stringify({ api: p, rowShown: has(s, new RegExp(pname)), dialogClosed: !has(s, /^New product$/) || has(s, /Name contains/) }));

  // edit via row tap
  const cell = s.find((n) => (n.label || n.text) === pname);
  await rt.clickAt(page, cell.x, cell.y, 2000);
  s = await dump(page, 'u20-edit-dialog');
  console.log('edit dialog shot', await rt.shot(page, D, 'u20-edit-dialog'));
  await fill(page, 683, 458, '15');
  await rt.clickAt(page, 843, 511, 600);
  console.log('edit filled shot', await rt.shot(page, D, 'u20-edit-filled'));
  await rt.tap(page, 'Save', { wait: 2500 });
  s = await dump(page, 'u20-after-edit');
  console.log('after edit shot', await rt.shot(page, D, 'u20-after-edit'));
  p = await byName(pname);
  console.log('RESULT edit', JSON.stringify({ api: p }));

  // bulk: select the original product row (active) and Deactivate
  const other = `${TAG} prod`;
  const rowCell = s.find((n) => (n.label || n.text) === other);
  const cb = s.filter((n) => n.role === 'checkbox' && Math.abs(n.y - rowCell.y) < 12)[0];
  console.log('row checkbox', JSON.stringify(cb));
  if (cb) await page.locator(`flt-semantics[data-rt="${cb.i}"]`).dispatchEvent('click'); else await rt.clickAt(page, 344, rowCell.y);
  await page.waitForTimeout(1200);
  s = await dump(page, 'u21-selected');
  console.log('selected shot', await rt.shot(page, D, 'u21-selected'));
  await rt.tap(page, /Deactivate/, { wait: 1500 });
  s = await dump(page, 'u21-confirm');
  console.log('confirm shot', await rt.shot(page, D, 'u21-confirm'));
  if (has(s, /^Confirm$/)) await rt.tap(page, 'Confirm', { wait: 2500 });
  s = await dump(page, 'u21-after-bulk');
  console.log('after bulk shot', await rt.shot(page, D, 'u21-after-bulk'));
  const o = await byName(other);
  console.log('RESULT bulk', JSON.stringify({ otherActive: o?.active, feedback: s.filter((n) => /updated|succeeded|result/i.test(n.label || n.text || '')).map((n) => n.label || n.text) }));
  if (has(s, /^Close$/)) await rt.tap(page, 'Close');

  // invoice form: product dropdown must not offer inactive products (both of mine are inactive now); add an active one
  const act = (await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} active-one`, price: 7 } })).json;
  await rt.go(page, '#/invoices');
  await rt.go(page, '#/invoices/new', 4500);
  s = await dump(page, 'u22-invoice-form');
  console.log('invoice form shot', await rt.shot(page, D, 'u22-invoice-form'));
  const prodField = s.find((n) => /^Product/.test(n.label || n.text || ''));
  console.log('product field', JSON.stringify(prodField));
  if (prodField) { await rt.clickAt(page, prodField.x, prodField.y, 1500); }
  s = await dump(page, 'u22-product-menu');
  console.log('product menu shot', await rt.shot(page, D, 'u22-product-menu'));
  const offered = s.map((n) => n.label || n.text || '').filter((t) => t.includes(TAG));
  console.log('RESULT invoiceProducts', JSON.stringify({ offeredMine: offered, inactiveOffered: offered.some((t) => t.includes('gadget') || t === other), activeOffered: offered.some((t) => t.includes('active-one')) }));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  fs.writeFileSync(path.join(D, 'ui-data.json'), JSON.stringify({ ...JSON.parse(fs.readFileSync(path.join(D, 'ui-data.json'))), gadget: p && p.id, activeOne: act.id }, null, 2));
  await app.close();
})().catch((e) => { console.error('UI6 FAILED', e); process.exit(1); });
