// UI: New invoice (admin preselect + save, 50-customer cap, cashier mandatory POC), detail edit/save, tabs, error paths.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const log = (...a) => console.log(...a);
const labels = (nodes) => nodes.map((x) => `[${x.role}] ${JSON.stringify(x.label || x.text).slice(0, 110)} @${x.x},${x.y}`).join('\n');
const has = (nodes, re) => nodes.some((n) => re.test(`${n.label || ''} ${n.text || ''}`));
const txt = (n) => n.label || n.text || '';
const step = async (name, fn) => { try { await fn(); } catch (e) { log(`STEP ${name} ERROR`, e.message.slice(0, 900)); } };
/** Scrolls an open dropdown menu until the item is in the accessibility tree, then taps it. */
const pickFromMenu = async (page, name) => {
  for (let i = 0; i < 60; i++) {
    const nodes = await rt.semantics(page);
    const hit = nodes.find((n) => txt(n) === name);
    if (hit) { await page.locator(`flt-semantics[data-rt="${hit.i}"]`).dispatchEvent('click'); await page.waitForTimeout(1200); return i; }
    const items = nodes.filter((n) => n.role === 'menuitem');
    const at = items.length ? items[Math.floor(items.length / 2)] : { x: 683, y: 450 };
    await page.mouse.move(at.x, at.y); await page.mouse.wheel(0, 250); await page.waitForTimeout(300);
  }
  throw new Error('menu item not found after scrolling: ' + name);
};

(async () => {
  const A = await rt.adminToken();
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });
  const me = (await api('GET', '/api/auth/me')).json;
  const custF = await rt.createCustomer(A, 'invF');
  log('fixtures', { admin: me.id, custF: custF.id });
  let created = [];

  // ================= admin: new invoice =================
  let app = await rt.openApp({ token: A });
  let { page } = app;
  await step('admin-new', async () => {
    await rt.go(page, '#/invoices/new', 5000);
    let nodes = await rt.semantics(page);
    log('POC FIELD', JSON.stringify(nodes.filter((n) => /Sales POC/.test(txt(n))).map(txt)));
    await rt.tap(page, 'Customer *', { wait: 1500 });
    log('customer scrolls needed', await pickFromMenu(page, custF.name));
    await rt.tap(page, 'Product', { wait: 1500 });
    await pickFromMenu(page, S.p1.name);
    nodes = await rt.semantics(page);
    log('after product', JSON.stringify(nodes.filter((n) => /Qty|₹|Product|invP/.test(txt(n))).map((n) => [txt(n), n.x, n.y])));
    await rt.clickAt(page, 745, 280, 500);
    await rt.typeText(page, '3', { clear: true });
    await page.waitForTimeout(600);
    await rt.tap(page, 'Add line', { wait: 1200 });
    nodes = await rt.semantics(page);
    const prods = nodes.filter((n) => txt(n) === 'Product');
    log('product fields', JSON.stringify(prods.map((n) => [n.x, n.y])));
    await page.locator(`flt-semantics[data-rt="${prods[prods.length - 1].i}"]`).dispatchEvent('click'); await page.waitForTimeout(1500);
    await pickFromMenu(page, S.p2.name);
    nodes = await rt.semantics(page);
    const p2node = nodes.filter((n) => txt(n) === S.p2.name).pop();
    await rt.clickAt(page, 745, p2node ? p2node.y : 328, 500);
    await rt.typeText(page, '2', { clear: true });
    await page.waitForTimeout(800);
    nodes = await rt.semantics(page);
    log('FORM TOTALS', JSON.stringify(nodes.filter((n) => /₹/.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f2-new-filled'));
    await rt.tap(page, 'Create invoice', { wait: 3500 });
    nodes = await rt.semantics(page);
    log('AFTER CREATE hash', await page.evaluate(() => location.hash), 'snackbar', has(nodes, /Invoice created/), 'errors', JSON.stringify(nodes.filter((n) => /required|Fill in|failed/i.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f3-after-create'));
    created = (await api('GET', `/api/invoices?size=10&filter=${encodeURIComponent(`customerId:eq:${custF.id}`)}`)).json.content;
    log('CREATED VIA UI', JSON.stringify(created.map((x) => ({ id: x.id, total: x.total, poc: x.salesPoc?.id, status: x.status }))));
  });

  // ================= admin: detail edit =================
  const target = created[0] ? created[0].id : S.eInv[3].id;
  log('detail target', target);
  await step('detail-edit', async () => {
    await rt.go(page, `#/invoices/${target}`, 5000);
    let nodes = await rt.semantics(page);
    log('== detail\n' + labels(nodes));
    log(await rt.shot(page, __dirname, 'f3b-detail-before'));
    await rt.clickAt(page, 886, 300, 600);
    await rt.typeText(page, 'UI edited notes', { clear: true });
    await page.waitForTimeout(600);
    nodes = await rt.semantics(page);
    log('dirty marker', has(nodes, /Unsaved changes/));
    await rt.tap(page, 'Save changes', { wait: 3000 });
    nodes = await rt.semantics(page);
    const g = (await api('GET', `/api/invoices/${target}`)).json;
    log('AFTER NOTES SAVE snackbar', has(nodes, /Invoice saved/), 'api notes', JSON.stringify(g.notes), 'errorText', JSON.stringify(nodes.filter((n) => /inactive|may not|required|error/i.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f4-detail-notes-saved'));
    await rt.tap(page, /^Sales POC \*/, { wait: 1500 });
    await rt.typeText(page, S.sales2.username);
    await page.waitForTimeout(1800);
    nodes = await rt.semantics(page);
    const hit1 = has(nodes, new RegExp(S.sales2.username));
    log('PICKER after typing+1.8s: sales2 listed?', hit1, 'items', JSON.stringify(nodes.filter((n) => /@/.test(txt(n))).map((n) => txt(n).slice(0, 60)).slice(0, 6)));
    log(await rt.shot(page, __dirname, 'f5-poc-picker-search'));
    if (!hit1) { await page.keyboard.type(' '); await page.keyboard.press('Backspace'); await page.waitForTimeout(1800); nodes = await rt.semantics(page); log('after extra keystroke sales2 listed?', has(nodes, new RegExp(S.sales2.username))); }
    const tile = nodes.find((n) => new RegExp(S.sales2.username).test(txt(n)));
    await page.locator(`flt-semantics[data-rt="${tile.i}"]`).dispatchEvent('click'); await page.waitForTimeout(1200);
    await rt.tap(page, 'Save changes', { wait: 3000 });
    const g2 = (await api('GET', `/api/invoices/${target}`)).json;
    nodes = await rt.semantics(page);
    log('AFTER POC SAVE api poc', g2.salesPoc?.id, 'expected', S.sales2.id, 'field', JSON.stringify(nodes.filter((n) => /Sales POC \*/.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f6-detail-poc-saved'));
    await rt.tap(page, 'History', { wait: 3000 });
    nodes = await rt.semantics(page);
    log('== history\n' + labels(nodes.filter((n) => n.y > 500)));
    log(await rt.shot(page, __dirname, 'f7-history'));
    const before = app.apiErrors.length;
    await rt.tap(page, 'Payment Promise', { wait: 3500 });
    nodes = await rt.semantics(page);
    log('== promise tab\n' + labels(nodes.filter((n) => n.y > 500)), '\nnew apiErrors', JSON.stringify(app.apiErrors.slice(before)));
    log(await rt.shot(page, __dirname, 'f8-promise-tab'));
    log('hash', await page.evaluate(() => location.hash));
  });

  // ================= inactive-POC invoice: notes-only save =================
  await step('inactive-poc-notes', async () => {
    await rt.go(page, `#/invoices/${S.invD.id}`, 5000);
    let nodes = await rt.semantics(page);
    log('invD poc field', JSON.stringify(nodes.filter((n) => /Sales POC/.test(txt(n))).map(txt)));
    await rt.clickAt(page, 886, 300, 600);
    await rt.typeText(page, 'notes only edit', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, 'Save changes', { wait: 3000 });
    nodes = await rt.semantics(page);
    const g = (await api('GET', `/api/invoices/${S.invD.id}`)).json;
    log('INACTIVE-POC SAVE api notes', JSON.stringify(g.notes), 'errors', JSON.stringify(nodes.filter((n) => /inactive|cannot|saved/i.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f9-inactive-poc-notes'));
  });

  // ================= not found / malformed =================
  await step('not-found', async () => {
    await rt.go(page, '#/invoices/987654321', 4000);
    let nodes = await rt.semantics(page);
    log('NOTFOUND', JSON.stringify(nodes.map(txt).filter((t) => !/^(Dashboard|Invoices|Payments|Promises|Disputes|Customers|Products|Users|Roles|Notifications)$|Account|^\d+$/.test(t))));
    log(await rt.shot(page, __dirname, 'f10-notfound'));
    await rt.go(page, '#/invoices/abc', 4000);
    nodes = await rt.semantics(page);
    log('ABC', JSON.stringify(nodes.map(txt).slice(0, 20)));
    log(await rt.shot(page, __dirname, 'f11-abc'));
  });
  log('admin apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 600));
  await app.close();

  // ================= cashier: mandatory POC =================
  const C = await rt.cashierToken();
  app = await rt.openApp({ token: C });
  page = app.page;
  await step('cashier-new', async () => {
    await rt.go(page, '#/invoices/new', 5000);
    let nodes = await rt.semantics(page);
    log('CASHIER POC FIELD', JSON.stringify(nodes.filter((n) => /Sales POC/.test(txt(n))).map(txt)));
    await rt.tap(page, 'Customer *', { wait: 1500 });
    await pickFromMenu(page, custF.name);
    await rt.tap(page, 'Product', { wait: 1500 });
    await pickFromMenu(page, S.p2.name);
    await rt.tap(page, 'Create invoice', { wait: 2000 });
    nodes = await rt.semantics(page);
    log('CASHIER SUBMIT W/O POC', JSON.stringify(nodes.filter((n) => /required|Fill in/.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f12-cashier-poc-required'));
    await rt.tap(page, /^Sales POC \*/, { wait: 1500 });
    await rt.typeText(page, S.sales.username);
    await page.waitForTimeout(1800);
    nodes = await rt.semantics(page);
    let tile = nodes.find((n) => new RegExp(S.sales.username).test(txt(n)));
    log('cashier picker listed after typing?', !!tile);
    if (!tile) { await page.keyboard.type(' '); await page.keyboard.press('Backspace'); await page.waitForTimeout(1800); nodes = await rt.semantics(page); tile = nodes.find((n) => new RegExp(S.sales.username).test(txt(n))); log('cashier picker needed extra keystroke; now listed?', !!tile); }
    await page.locator(`flt-semantics[data-rt="${tile.i}"]`).dispatchEvent('click'); await page.waitForTimeout(1200);
    await rt.tap(page, 'Create invoice', { wait: 3500 });
    nodes = await rt.semantics(page);
    const list = (await api('GET', `/api/invoices?size=10&filter=${encodeURIComponent(`customerId:eq:${custF.id}`)}`)).json.content;
    log('CASHIER CREATE hash', await page.evaluate(() => location.hash), 'invoices for custF', JSON.stringify(list.map((x) => ({ total: x.total, poc: x.salesPoc?.id }))));
    log(await rt.shot(page, __dirname, 'f13-cashier-created'));
  });
  log('cashier apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 400));
  await app.close();

  // ================= INVOICE_MANAGE without POC_ASSIGN: notes save =================
  const mgrT = await rt.login(S.mgrUser.username, S.mgrUser.password);
  app = await rt.openApp({ token: mgrT });
  page = app.page;
  await step('mgr-notes', async () => {
    await rt.go(page, '#/invoices', 4000);
    await rt.go(page, `#/invoices/${S.inv2.id}`, 5000);
    let nodes = await rt.semantics(page);
    log('== mgr detail\n' + labels(nodes));
    await rt.clickAt(page, 886, 300, 600);
    await rt.typeText(page, 'mgr ui notes', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, 'Save changes', { wait: 3000 });
    nodes = await rt.semantics(page);
    const g = (await api('GET', `/api/invoices/${S.inv2.id}`)).json;
    log('MGR SAVE api notes', JSON.stringify(g.notes), 'errors', JSON.stringify(nodes.filter((n) => /may not|saved|Sales POC/i.test(txt(n))).map(txt)));
    log(await rt.shot(page, __dirname, 'f14-mgr-notes'));
  });
  log('mgr apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 400));
  await app.close();
  fs.writeFileSync(path.join(__dirname, 'state-forms.json'), JSON.stringify({ custF, created }, null, 2));
})().catch((e) => { console.error('ERR', e); process.exit(1); });
