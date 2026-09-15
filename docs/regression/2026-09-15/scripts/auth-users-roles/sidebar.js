// UI: the sidebar offers only the screens each role may use.
const rt = require('../lib.js');
const fs = require('fs');
const D = __dirname;
const ALL = ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes', 'Customers', 'Products', 'Users', 'Roles'];
const EXPECT = {
  ADMIN: ALL,
  CASHIER: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Customers', 'Products'],
  VIEWER: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Customers', 'Products'],
  SALES_POC: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes', 'Customers', 'Products'],
  CUSTOMER_SUCCESS_POC: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes', 'Customers', 'Products'],
  COLLECTION_POC: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes', 'Customers'],
  CUSTOMER: ['Dashboard', 'Invoices', 'Payments', 'Promises', 'Disputes'],
};
(async () => {
  const admin = await rt.adminToken();
  const tokens = { ADMIN: admin, CASHIER: await rt.cashierToken() };
  for (const r of ['VIEWER', 'SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC']) {
    const u = await rt.createStaff(admin, r, 'aursb');
    tokens[r] = await rt.login(u.username, u.password);
  }
  const c = await rt.createCustomer(admin, 'aursb');
  tokens.CUSTOMER = await rt.login(c.username, c.password);
  const out = {};
  for (const [role, tok] of Object.entries(tokens)) {
    const app = await rt.openApp({ token: tok });
    const nodes = await rt.semantics(app.page);
    // Nav rail entries sit in the left column (x < 260).
    const nav = ALL.filter((l) => nodes.some((n) => n.x < 260 && ((n.label || '') + ' ' + (n.text || '')).trim().split('\n')[0].trim() === l));
    const account = nodes.find((n) => /•/.test(`${n.label} ${n.text}`));
    const shot = await rt.shot(app.page, D, `sidebar-${role}`);
    const ok = JSON.stringify(nav) === JSON.stringify(EXPECT[role]);
    out[role] = { ok, nav, expected: EXPECT[role], account: account && (account.label || account.text), shot, apiErrors: app.apiErrors, pageErrors: app.pageErrors };
    console.log(`${ok ? 'PASS' : 'FAIL'} ${role}: nav=${nav.join(',')} | account=${account && (account.label || account.text)} | ${shot} | apiErrors=${JSON.stringify(app.apiErrors)}`);
    if (!ok) console.log('   left nodes:', nodes.filter((n) => n.x < 260).map((n) => `[${n.role}] ${n.label || n.text}`).join(' | '));
    await app.close();
  }
  fs.writeFileSync(`${D}/sidebar-results.json`, JSON.stringify(out, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
