// UI walk per role: sidebar, action buttons, selection checkboxes, editable inputs, POC visibility.
// Usage: node ui.js ROLE [ROLE...]
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const DIR = __dirname;
const NAV = ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes', 'Customers', 'Products', 'Users', 'Roles'];
const WATCH = ['New invoice', 'Record payment', 'New customer', 'New product', 'Raise promise', 'Save changes', 'Sales POC', 'Collection POC',
  'Customer Success POC', 'Customer Success POCs', 'Collection POCs', 'Add filter', 'Export selected', 'Raise dispute', 'Cancel invoice', 'POC missing'];

async function inputs(page) {
  return page.$$eval('flt-semantics input, flt-semantics textarea, [role="textbox"], input, textarea', (els) => els.length);
}

async function snap(page, role, name) {
  const nodes = await rt.semantics(page);
  const labels = nodes.map((n) => `${n.label || ''}|${n.text || ''}`);
  const has = (w) => nodes.some((n) => (n.label || '').includes(w) || (n.text || '').includes(w));
  const res = {
    shot: await rt.shot(page, DIR, `${role}-${name}`),
    checkboxes: nodes.filter((n) => n.role === 'checkbox').length,
    inputs: await inputs(page),
    present: WATCH.filter(has),
    locked: nodes.filter((n) => /me\b|my book|locked|🔒/i.test(n.label || n.text || '')).map((n) => n.label || n.text).slice(0, 3),
  };
  res.all = labels;
  return res;
}

async function login(role) {
  switch (role) {
    case 'ADMIN': return rt.adminToken();
    case 'CASHIER': return rt.login(st.cashierRt.username, rt.PASSWORD);
    case 'VIEWER': return rt.login(st.viewer.username, rt.PASSWORD);
    case 'CUSTOMER': return rt.login(st.custA.username, rt.PASSWORD);
    case 'SALES_POC': return rt.login(st.s1.username, rt.PASSWORD);
    case 'CUSTOMER_SUCCESS_POC': return rt.login(st.cs1.username, rt.PASSWORD);
    case 'COLLECTION_POC': return rt.login(st.c1.username, rt.PASSWORD);
    default: throw new Error(role);
  }
}

(async () => {
  for (const role of process.argv.slice(2)) {
    const token = await login(role);
    const app = await rt.openApp({ token });
    const { page } = app;
    const r = { role };
    const dash = await snap(page, role, 'dashboard');
    r.sidebar = NAV.filter((n) => dash.all.some((l) => l.split('|').some((x) => x === n)));
    r.dashboard = { ...dash, all: undefined };
    const pages = [['invoices', '#/invoices'], ['payments', '#/payments'], ['promises', '#/promises'],
      ['invoice-detail', `#/invoices/${st.invA1.id}`], ['payment-detail', `#/payments/${st.payA.id}`]];
    if (role !== 'CUSTOMER') pages.push(['customers', '#/customers'], ['customer-detail', `#/customers/${st.custA.id}`]);
    else pages.push(['customers-typed', '#/customers'], ['customer-detail', `#/customers/${st.custA.id}`]);
    for (const [name, hash] of pages) {
      await rt.go(page, hash, 4500);
      const s = await snap(page, role, name);
      r[name] = { ...s, all: s.all.slice(0, 80) };
    }
    // Filter picker as seen by this role (invoices, payments, promises)
    r.filterColumns = {};
    for (const ent of ['invoices', 'payments', 'promises']) {
      await rt.go(page, `#/${ent}`, 4000);
      try {
        await rt.tap(page, 'Add filter', { wait: 1500 });
        const nodes = await rt.semantics(page);
        r.filterColumns[ent] = nodes.map((n) => n.label || n.text).filter(Boolean).slice(0, 60);
        r.filterColumns[ent + '_shot'] = await rt.shot(page, DIR, `${role}-${ent}-addfilter`);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(800);
      } catch (e) { r.filterColumns[ent] = 'ERR ' + e.message.slice(0, 200); }
    }
    r.apiErrors = app.apiErrors.slice(0, 20);
    r.pageErrors = app.pageErrors.slice(0, 10);
    await app.close();
    fs.writeFileSync(path.join(DIR, `ui-${role}.json`), JSON.stringify(r, null, 2));
    const brief = (x) => x && `cb=${x.checkboxes} in=${x.inputs} [${x.present.join(', ')}] ${x.locked?.length ? 'locked=' + x.locked.join('/') : ''}`;
    console.log(`\n== ${role} sidebar: ${r.sidebar.join(', ')}`);
    for (const k of ['invoices', 'payments', 'promises', 'customers', 'customers-typed', 'invoice-detail', 'payment-detail', 'customer-detail']) if (r[k]) console.log(`  ${k}: ${brief(r[k])}`);
    console.log('  apiErrors:', JSON.stringify(r.apiErrors).slice(0, 300));
    console.log('  pageErrors:', JSON.stringify(r.pageErrors).slice(0, 300));
  }
})().catch((e) => { console.error('UI FAILED', e); process.exit(1); });
