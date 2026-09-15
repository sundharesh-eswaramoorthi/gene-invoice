const rt = require('../lib.js');
const S = require('./state.json');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '');
const text = async (p) => (await rt.semantics(p)).map(lbl).join(' | ');
const nodes = async (p, re, role) => (await rt.semantics(p)).filter((n) => re.test(lbl(n)) && (!role || n.role === role));
(async () => {
  if (process.argv[2] !== "b") {
  const cash = await rt.cashierToken();
  const me = await rt.api('GET', '/api/auth/me', { token: cash });
  console.log('cashier me', me.status, JSON.stringify(me.json?.privileges || me.json?.role || me.text).slice(0, 400));
  let app = await rt.openApp({ token: cash });
  for (const [hash, name] of [['#/products', 'products'], ['#/promises', 'promises'], ['#/invoices', 'invoices'], ['#/payments', 'payments'], ['#/customers', 'customers']]) {
    await rt.go(app.page, hash, 4500);
    const firstCell = (await nodes(app.page, /./, 'cell')).sort((a, b) => a.y - b.y)[0];
    if (firstCell) await rt.clickAt(app.page, 290, firstCell.y, 1000);
    const t = await text(app.page);
    const shot = await rt.shot(app.page, DIR, `ui2-cashier-${name}`);
    console.log(`CASHIER ${name}: cell=${firstCell ? lbl(firstCell) : 'none'} selected=${(t.match(/\d+ selected/) || ['none'])[0]} export=${t.includes('Export selected')} url=${decodeURIComponent(app.page.url())} shot=${shot}`);
    await app.page.keyboard.press('Escape');
  }
  await app.close();
  }
  let app;
  // (b)+(c) admin -> logout -> customer login in the same app instance
  const admin = await rt.adminToken();
  app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/invoices', 4500);
  await rt.tap(page, /^20$/, { role: 'button', wait: 1000 });
  await rt.tap(page, /^50$/, { role: 'menuitem', wait: 3000 });
  console.log('admin invoices url', decodeURIComponent(page.url()));
  await rt.tap(page, /^Account/, { wait: 1200 });
  console.log('account menu:', (await text(page)).slice(0, 400));
  await rt.tap(page, /Sign out|Log out|Logout/i, { wait: 4000 });
  await rt.enableSemantics(page);
  await rt.shot(page, DIR, 'ui2-after-logout');
  await rt.clickAt(page, 683, 465, 500); await rt.typeText(page, S.customers[0].username);
  await rt.clickAt(page, 683, 517, 500); await rt.typeText(page, S.customers[0].password);
  await rt.tap(page, 'Sign in', { wait: 6000 }); await rt.enableSemantics(page);
  console.log('after customer login:', (await text(page)).slice(0, 300));
  await rt.go(page, '#/invoices', 4500);
  const u = decodeURIComponent(page.url());
  const rowsBtn = (await nodes(page, /^(10|20|50)$/, 'button')).map(lbl);
  await rt.shot(page, DIR, 'ui2-customer-invoices');
  await rt.tap(page, 'Add filter');
  await rt.tap(page, /^Column\n/, { role: 'button', wait: 1000 });
  const items = (await nodes(page, /./, 'menuitem')).map(lbl);
  const shot = await rt.shot(page, DIR, 'ui2-customer-filter-columns');
  const schema = await rt.api('GET', '/api/table-schemas/invoices', { token: await rt.login(S.customers[0].username, S.customers[0].password) });
  console.log(`CUSTOMER invoices url=${u} rows=${rowsBtn} column menu=[${items.join(', ')}] server schema=[${schema.json.columns.map((c) => c.label).join(', ')}] shot=${shot}`);
  console.log('apiErrors', JSON.stringify(app.apiErrors).slice(0, 500), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 300));
  await app.close();
})().catch((e) => { console.error('UI2 FAILED', e.message.slice(0, 1500)); process.exit(1); });
