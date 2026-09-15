// Second pass of UI checks with corrected click targets.  node ui2.js <poc-inactive|mgr|picker|customers>
const rt = require('../lib.js');
const DIR = __dirname;
const which = process.argv[2];
const dump = (nodes) => nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\n/g, ' / ').slice(0, 70)} @${n.x},${n.y}`).join('\n');

(async () => {
  const admin = await rt.adminToken();
  const A = (m, p, body, token = admin) => rt.api(m, p, { token, body });
  const prod = (await A('POST', '/api/products', { name: rt.uniq('vinvU2P'), description: 'rt', price: 100, active: true })).json;
  const cust = await rt.createCustomer(admin, 'vinvui2');
  const s1 = await rt.createStaff(admin, 'SALES_POC', 'vinvui2s');
  const mkInv = (poc, notes) => A('POST', '/api/invoices', { customerId: cust.id, salesPocUserId: poc, notes, items: [{ productId: prod.id, quantity: 1 }] });

  if (which === 'poc-inactive' || which === 'mgr') {
    let token = admin; let inv;
    if (which === 'poc-inactive') {
      const sDel = await rt.createStaff(admin, 'SALES_POC', 'vinvui2del');
      inv = (await mkInv(sDel.id, 'ui-orig')).json;
      console.log('delete', JSON.stringify((await A('DELETE', `/api/users/${sDel.id}`)).json));
    } else {
      const role = await A('POST', '/api/roles', { name: rt.uniq('VINVUI2MGR').toUpperCase(), description: 'rt', privileges: ['INVOICE_VIEW', 'INVOICE_MANAGE', 'CUSTOMER_VIEW', 'PRODUCT_VIEW', 'POC_VIEW'] });
      const u = rt.uniq('vinvui2mgr');
      await A('POST', '/api/users', { username: u, email: `${u}@rt.local`, fullName: u, password: rt.PASSWORD, roleId: role.json.id, active: true });
      token = await rt.login(u, rt.PASSWORD);
      inv = (await mkInv(s1.id, 'ui-orig')).json;
    }
    const app = await rt.openApp({ token });
    const { page } = app;
    const patches = [];
    page.on('request', (r) => { if (r.method() === 'PATCH') patches.push(r.postData()); });
    await rt.go(page, `#/invoices/${inv.id}`, 5000);
    const nodes = await rt.semantics(page);
    const notesLabel = nodes.find((n) => n.text === 'Notes');
    // The text box sits to the right of the label (x ~ 887), slightly below the label centre.
    await rt.clickAt(page, 887, notesLabel.y + 8, 600);
    await page.keyboard.press('End');
    await rt.typeText(page, ' edited-in-ui');
    await page.waitForTimeout(600);
    await rt.shot(page, DIR, `${which}-v2-typed`);
    await rt.tap(page, 'Save changes', { wait: 3000 });
    await rt.shot(page, DIR, `${which}-v2-saved`);
    const after = await rt.semantics(page);
    console.log('AFTER SAVE:\n' + dump(after).split('\n').filter((l) => /POC|inactive|may not|Unsaved|saved|Notes|edited|cannot/i.test(l)).join('\n'));
    console.log('PATCH bodies sent:', JSON.stringify(patches));
    console.log('apiErrors', JSON.stringify(app.apiErrors));
    const g = await A('GET', `/api/invoices/${inv.id}`);
    console.log('notes after (api):', JSON.stringify(g.json.notes), '| poc', g.json.salesPoc?.username, 'active', g.json.salesPoc?.active);
    await app.close();
  }

  if (which === 'picker') {
    const target = await rt.createStaff(admin, 'SALES_POC', 'vinvpick2');
    const inv = (await mkInv(s1.id, 'picker')).json;
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, `#/invoices/${inv.id}`, 5000);
    await rt.clickAt(page, 887, 241, 2500); // the Sales POC field box
    await rt.enableSemantics(page);
    let nodes = await rt.semantics(page);
    console.log('dialog nodes:\n' + dump(nodes).split('\n').filter((l) => /Choose|Search|@|Cancel/.test(l)).slice(0, 12).join('\n'));
    const rows = () => nodes.filter((n) => /@/.test(`${n.label} ${n.text}`)).map((n) => (n.label || n.text).replace(/\n/g, ' ').slice(0, 50));
    const before = rows();
    // The search field has autofocus; type the full username.
    await rt.typeText(page, target.username);
    await page.waitForTimeout(2000);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    const r1 = rows();
    console.log(`typed "${target.username}" + 2s -> rows ${r1.length}:`, r1.slice(0, 5), '| target listed?', r1.some((s) => s.includes(target.username)), '| unchanged from initial list?', JSON.stringify(r1) === JSON.stringify(before));
    await rt.shot(page, DIR, 'picker-v2-after-typing');
    await page.keyboard.press('Shift'); // no-op key (no text change)
    await page.waitForTimeout(1200);
    await rt.typeText(page, 'x');       // one more real keystroke
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    nodes = await rt.semantics(page);
    const r2 = rows();
    console.log('after one extra keystroke ("x") rows', r2.length, r2.slice(0, 5), '| target listed?', r2.some((s) => s.includes(target.username)));
    await rt.shot(page, DIR, 'picker-v2-after-extra');
    await app.close();
  }

  if (which === 'customers') {
    const z = await rt.createCustomer(admin, 'zzvinv2');
    const tot = await A('GET', '/api/customers?size=50&sort=name,asc');
    console.log('customers total', tot.json.totalElements, '| 50th by name:', tot.json.content.at(-1).name, '| new customer:', z.name);
    const app = await rt.openApp({ token: admin });
    const { page } = app;
    await rt.go(page, '#/invoices/new', 5000);
    await rt.tap(page, /Customer/, { wait: 1500 });
    await page.mouse.move(676, 500);
    for (let i = 0; i < 40; i++) { await page.mouse.wheel(0, 600); await page.waitForTimeout(100); }
    await page.waitForTimeout(1000);
    await rt.enableSemantics(page);
    const names = (await rt.semantics(page)).map((n) => n.label || n.text).filter(Boolean);
    console.log('menu tail:', names.slice(-6).join(' | '));
    console.log('new customer in menu?', names.some((s) => s.includes(z.name)));
    await rt.shot(page, DIR, 'customers-v2-menu-end');
    await app.close();
  }
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
