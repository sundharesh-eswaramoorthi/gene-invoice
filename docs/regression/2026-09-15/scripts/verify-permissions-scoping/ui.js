// UI verification for PS-045 (viewer POC chips) and PS-046 (export unreachable without *_MANAGE).
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = {};

async function visit(who, user, hash, name, { selectFirst = false } = {}) {
  const token = user === 'admin' ? await rt.adminToken() : await rt.login(user.username, rt.PASSWORD);
  const app = await rt.openApp({ token });
  try {
    await rt.go(app.page, hash, 5000);
    const nodes = await rt.semantics(app.page);
    const checkboxes = nodes.filter((n) => n.role === 'checkbox');
    const res = {
      who, hash,
      checkboxes: checkboxes.length,
      exportNode: nodes.some((n) => /export/i.test(`${n.label} ${n.text}`)),
      apiErrors: app.apiErrors.slice(0, 5),
      labels: nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\s+/g, ' ').slice(0, 60)}`).slice(0, 60),
    };
    res.shot = await rt.shot(app.page, __dirname, name);
    if (selectFirst && checkboxes.length > 1) {
      await app.page.locator(`flt-semantics[data-rt="${checkboxes[1].i}"]`).dispatchEvent('click');
      await app.page.waitForTimeout(1500);
      const after = await rt.semantics(app.page);
      res.afterSelectExport = after.filter((n) => /export|selected/i.test(`${n.label} ${n.text}`)).map((n) => `[${n.role}] ${n.label || n.text}`);
      res.shotSelected = await rt.shot(app.page, __dirname, name + '-selected');
    }
    out[name] = res;
    console.log(name, JSON.stringify({ ...res, labels: undefined }));
  } finally {
    await app.close();
  }
}

(async () => {
  await visit('VIEWER', st.viewer, `#/customers/${st.A.id}`, 'PS045-viewer-customer');
  await visit('ADMIN', 'admin', `#/customers/${st.A.id}`, 'PS045-admin-customer-control');
  await visit('COLLECTION_POC', st.c1, '#/invoices', 'PS046-collection-invoices');
  await visit('SALES_POC', st.s1, '#/payments', 'PS046-sales-payments');
  await visit('COLLECTION_POC', st.c1, '#/payments', 'PS046-collection-payments-control', { selectFirst: true });
  await visit('CASHIER', st.cash, '#/products', 'PS046-cashier-products');
  fs.writeFileSync(path.join(__dirname, 'ui-result.json'), JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
