// Independent UI verification for UI-06, UI-12, UI-22, UI-23, UI-24, UI-25.
const rt = require('../lib.js');
const S = require('./state.json');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '');
const text = async (p) => (await rt.semantics(p)).map(lbl).join(' | ');
const nodes = async (p, re, role) => (await rt.semantics(p)).filter((n) => re.test(lbl(n)) && (!role || n.role === role));
const part = process.argv[2] || 'a';
const run = process.argv[3] || '1';

(async () => {
  const admin = await rt.adminToken();
  if (part === 'a') {
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    // UI-06: record API requests on a page change
    await rt.go(page, '#/invoices?size=10', 5000);
    const reqs = [];
    page.on('request', (r) => { if (r.url().startsWith(rt.API)) reqs.push(r.method() + ' ' + decodeURIComponent(r.url().slice(rt.API.length))); });
    await rt.tap(page, 'Next page', { wait: 4000 });
    console.log(`UI-06 run${run}: after Next page -> ${reqs.length} requests: ${reqs.join(' ; ')}`);
    await rt.shot(page, DIR, `ui06-after-next-${run}`);

    // UI-12: page out of range
    await rt.go(page, `#/invoices?page=99&size=10&f=customerId:eq:${S.cust.id}`, 5000);
    const t12 = await text(page);
    const s12 = await rt.shot(page, DIR, `ui12-out-of-range-${run}`);
    console.log(`UI-12 run${run}: ${t12.slice(0, 700)} shot=${s12}`);

    // UI-25: chip label for a customer filter
    await rt.go(page, `#/invoices?f=customerId:eq:${S.cust.id}`, 5000);
    const chips = (await nodes(page, /Customer/)).map(lbl);
    const s25 = await rt.shot(page, DIR, `ui25-chip-${run}`);
    console.log(`UI-25 run${run}: nodes mentioning Customer: ${JSON.stringify(chips)} customer name=${S.cust.name} shot=${s25}`);
    await app.close();

    // UI-22: cashier selection on products / promises vs invoices
    const cash = await rt.cashierToken();
    const capp = await rt.openApp({ token: cash });
    for (const [hash, name] of [['#/products', 'products'], ['#/promises', 'promises'], ['#/invoices', 'invoices']]) {
      await rt.go(capp.page, hash, 5000);
      const boxes = await nodes(capp.page, /./, 'checkbox');
      const s = await rt.shot(capp.page, DIR, `ui22-cashier-${name}-${run}`);
      let afterTick = '';
      if (boxes.length > 1) {
        const first = boxes.sort((x, y) => x.y - y.y)[1];
        await rt.clickAt(capp.page, first.x, first.y, 1200);
        const t = await text(capp.page);
        afterTick = `selected=${(t.match(/\d+ selected/) || ['none'])[0]} export=${/Export/.test(t)}`;
        await rt.shot(capp.page, DIR, `ui22-cashier-${name}-ticked-${run}`);
      }
      console.log(`UI-22 run${run}: cashier ${name}: checkboxes=${boxes.length} ${afterTick} shot=${s}`);
    }
    await capp.close();
  }

  if (part === 'b') {
    // UI-23 + UI-24: admin -> logout -> customer login in the same app
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, '#/invoices', 5000);
    await rt.tap(page, 'Add filter');
    await rt.tap(page, /^Column/, { role: 'button', wait: 1000 });
    const adminItems = (await nodes(page, /./, 'menuitem')).map(lbl);
    console.log(`admin column menu: ${adminItems.join(', ')}`);
    await page.keyboard.press('Escape'); await page.waitForTimeout(500);
    await page.keyboard.press('Escape'); await page.waitForTimeout(800);
    await rt.go(page, '#/invoices', 4000);
    await rt.tap(page, /^20$/, { role: 'button', wait: 1000 });
    await rt.tap(page, /^50$/, { role: 'menuitem', wait: 3000 });
    console.log('admin invoices url after Rows 50:', decodeURIComponent(page.url()));
    await rt.tap(page, /^Account/, { wait: 1200 });
    await rt.tap(page, /Sign out|Log out|Logout/i, { wait: 4000 });
    await rt.enableSemantics(page);
    await rt.shot(page, DIR, `ui23-after-logout-${run}`);
    await rt.clickAt(page, 683, 465, 500); await rt.typeText(page, S.cust.username);
    await rt.clickAt(page, 683, 517, 500); await rt.typeText(page, S.cust.password);
    await rt.tap(page, 'Sign in', { wait: 6000 }); await rt.enableSemantics(page);
    console.log('after customer login:', (await text(page)).slice(0, 200));
    await rt.go(page, '#/invoices', 5000);
    const rowsBtn = (await nodes(page, /^(10|20|50)$/, 'button')).map(lbl);
    const pager = (await nodes(page, /of \d+/)).map(lbl);
    await rt.shot(page, DIR, `ui24-customer-invoices-${run}`);
    await rt.tap(page, 'Add filter');
    await rt.tap(page, /^Column/, { role: 'button', wait: 1000 });
    const items = (await nodes(page, /./, 'menuitem')).map(lbl);
    const shot = await rt.shot(page, DIR, `ui23-customer-filter-columns-${run}`);
    const ct = await rt.login(S.cust.username, S.cust.password);
    const schema = await rt.api('GET', '/api/table-schemas/invoices', { token: ct });
    console.log(`UI-23/24 run${run}: customer url=${decodeURIComponent(page.url())} rows=${rowsBtn} pager=${pager} column menu=[${items.join(', ')}] server schema=[${schema.json.columns.map((c) => c.label).join(', ')}] shot=${shot}`);
    await app.close();

    // Control: customer in a fresh browser (no admin session before)
    const capp = await rt.openApp({ token: ct });
    await rt.go(capp.page, '#/invoices', 5000);
    const rows2 = (await nodes(capp.page, /^(10|20|50)$/, 'button')).map(lbl);
    await rt.tap(capp.page, 'Add filter');
    await rt.tap(capp.page, /^Column/, { role: 'button', wait: 1000 });
    const items2 = (await nodes(capp.page, /./, 'menuitem')).map(lbl);
    await rt.shot(capp.page, DIR, `ui23-control-fresh-customer-${run}`);
    console.log(`control fresh customer: rows=${rows2} column menu=[${items2.join(', ')}]`);
    await capp.close();
  }
})().catch((e) => { console.error('UI FAILED', e.message.slice(0, 1500)); process.exit(1); });
