// Independent verification of reported invoice failures (UI side).
// Usage: node ui.js <case>   where case in: poc-inactive | mgr | customers | picker | promise | abc | chip
const rt = require('../lib.js');
const DIR = __dirname;
const which = process.argv[2];
const dump = (nodes) => nodes.map((n) => `[${n.role}] ${(n.label || n.text).slice(0, 60)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n');

(async () => {
  const admin = await rt.adminToken();
  const A = (m, p, body, token = admin) => rt.api(m, p, { token, body });
  const prod = (await A('POST', '/api/products', { name: rt.uniq('vinvUP'), description: 'rt', price: 100, active: true })).json;
  const cust = await rt.createCustomer(admin, 'vinvui');
  const s1 = await rt.createStaff(admin, 'SALES_POC', 'vinvuis');
  const mkInv = (poc, notes) => A('POST', '/api/invoices', { customerId: cust.id, salesPocUserId: poc, notes, items: [{ productId: prod.id, quantity: 1 }] });

  if (which === 'poc-inactive' || which === 'mgr') {
    let token = admin; let inv; let pocId;
    if (which === 'poc-inactive') {
      const sDel = await rt.createStaff(admin, 'SALES_POC', 'vinvuidel');
      inv = (await mkInv(sDel.id, 'ui-orig')).json;
      console.log('delete', (await A('DELETE', `/api/users/${sDel.id}`)).json);
      pocId = sDel.id;
    } else {
      const roleName = rt.uniq('VINVUIMGR').toUpperCase();
      const role = await A('POST', '/api/roles', { name: roleName, description: 'rt', privileges: ['INVOICE_VIEW', 'INVOICE_MANAGE', 'CUSTOMER_VIEW', 'PRODUCT_VIEW', 'POC_VIEW'] });
      const u = rt.uniq('vinvuimgr');
      await A('POST', '/api/users', { username: u, email: `${u}@rt.local`, fullName: u, password: rt.PASSWORD, roleId: role.json.id, active: true });
      token = await rt.login(u, rt.PASSWORD);
      inv = (await mkInv(s1.id, 'ui-orig')).json;
      pocId = s1.id;
    }
    const app = await rt.openApp({ token });
    const { page } = app;
    await rt.go(page, `#/invoices/${inv.id}`, 5000);
    let nodes = await rt.semantics(page);
    console.log(dump(nodes));
    const notes = nodes.find((n) => /Notes/.test(n.label || n.text) && n.role !== 'button');
    console.log('notes node', notes);
    await rt.shot(page, DIR, `${which}-1-open`);
    if (notes) {
      await rt.clickAt(page, notes.x, notes.y, 600);
      await rt.typeText(page, ' edited-in-ui');
    }
    await page.waitForTimeout(500);
    await rt.shot(page, DIR, `${which}-2-typed`);
    await rt.tap(page, 'Save changes', { wait: 2500 });
    await rt.shot(page, DIR, `${which}-3-saved`);
    nodes = await rt.semantics(page);
    console.log('AFTER SAVE:\n' + dump(nodes).split('\n').filter((l) => /POC|Sales|inactive|error|may not|Unsaved|saved|Notes/i.test(l)).join('\n'));
    console.log('apiErrors', JSON.stringify(app.apiErrors));
    const g = await A('GET', `/api/invoices/${inv.id}`);
    console.log('notes after (api):', JSON.stringify(g.json.notes), 'poc', g.json.salesPoc?.username, g.json.salesPoc?.active);
    await app.close();
  }

  if (which === 'customers') {
    const z = await rt.createCustomer(admin, 'zzvinv');
    const tot = await A('GET', '/api/customers?size=50&sort=name,asc');
    console.log('customers total', tot.json.totalElements, 'last on page 1:', tot.json.content.at(-1).name, 'zz:', z.name);
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, '#/invoices/new', 5000);
    await rt.shot(page, DIR, 'customers-1-form');
    await rt.tap(page, /Customer/, { wait: 1500 });
    await rt.shot(page, DIR, 'customers-2-menu');
    let nodes = await rt.semantics(page);
    const firstNames = nodes.map((n) => n.label || n.text);
    console.log('menu has zz?', firstNames.some((s) => s.includes(z.name)));
    // scroll to the end of the menu
    for (let i = 0; i < 25; i++) { await page.mouse.wheel(0, 800); await page.waitForTimeout(120); }
    await page.waitForTimeout(800);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    const names = nodes.map((n) => n.label || n.text).filter(Boolean);
    console.log('menu tail:', names.slice(-8).join(' | '));
    console.log('menu has zz after scroll?', names.some((s) => s.includes(z.name)));
    await rt.shot(page, DIR, 'customers-3-menu-end');
    await app.close();
  }

  if (which === 'picker') {
    const target = await rt.createStaff(admin, 'SALES_POC', 'vinvpick');
    const inv = (await mkInv(s1.id, 'picker')).json;
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, `#/invoices/${inv.id}`, 5000);
    let nodes = await rt.semantics(page);
    const poc = nodes.find((n) => /Sales POC/.test(n.label || n.text));
    console.log('poc field', poc);
    await rt.clickAt(page, poc.x, poc.y, 2000);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    const search = nodes.find((n) => /Search by name/.test(n.label || n.text));
    console.log('search field', search);
    if (search) await rt.clickAt(page, search.x, search.y, 400);
    await rt.typeText(page, target.username);
    await page.waitForTimeout(2000);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    const listed = () => nodes.filter((n) => /@/.test(n.label || n.text)).map((n) => (n.label || n.text).replace(/\n/g, ' ').slice(0, 60));
    console.log('after typing full username + 2s, rows:', listed().length, listed().slice(0, 6));
    console.log('target listed?', nodes.some((n) => (n.label || n.text).includes(target.username)));
    await rt.shot(page, DIR, 'picker-1-after-typing');
    await page.keyboard.press('End');
    await rt.typeText(page, ' ');
    await page.waitForTimeout(1500);
    await page.keyboard.press('Backspace');
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    console.log('after 2 extra keystrokes rows:', listed().length, listed().slice(0, 6));
    console.log('target listed?', nodes.some((n) => (n.label || n.text).includes(target.username)));
    await rt.shot(page, DIR, 'picker-2-after-extra');
    await app.close();
  }

  if (which === 'promise') {
    const inv = (await mkInv(s1.id, 'to-cancel')).json;
    await A('POST', `/api/invoices/${inv.id}/cancel`);
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, `#/invoices?f=customerId:eq:${cust.id}`, 5000);
    const nodes = await rt.semantics(page);
    console.log(dump(nodes).split('\n').filter((l) => /INV-|Raise promise|Cancel invoice|Open|Cancelled|Customer is/.test(l)).join('\n'));
    await rt.shot(page, DIR, 'promise-1-list');
    console.log('invoice', inv.invoiceNumber);
    await app.close();
  }

  if (which === 'abc') {
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, '#/invoices/abc', 4000);
    const nodes = await rt.semantics(page);
    console.log(dump(nodes));
    console.log('pageErrors', app.pageErrors.slice(0, 3));
    await rt.shot(page, DIR, 'abc-1');
    await rt.go(page, '#/invoices/987654321', 4000);
    console.log('nonexistent:', dump(await rt.semantics(page)).split('\n').filter((l) => /not|found|Back|unavailable/i.test(l)).join('\n'));
    await rt.shot(page, DIR, 'abc-2-nonexistent');
    await app.close();
  }

  if (which === 'chip') {
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, `#/invoices?f=customerId:eq:${cust.id}`, 5000);
    const nodes = await rt.semantics(page);
    console.log('customer', cust.id, cust.name);
    console.log(dump(nodes).split('\n').filter((l) => /Customer/.test(l)).join('\n'));
    await rt.shot(page, DIR, 'chip-1-url');
    await app.close();
  }
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
