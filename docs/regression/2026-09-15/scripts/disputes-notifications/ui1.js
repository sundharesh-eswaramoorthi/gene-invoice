// UI exploration, part 1: customer opens an invoice and raises a dispute.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const DIR = __dirname;
const dump = async (page, tag) => {
  const nodes = await rt.semantics(page);
  console.log(`--- ${tag}: ` + nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\s+/g, ' ').slice(0, 70)} @${n.x},${n.y}`).join(' | '));
  return nodes;
};

(async () => {
  const seed = await rt.adminToken();
  const myAdmin = await rt.createStaff(seed, 'ADMIN', 'dnuiadm');
  const adm = await rt.login(myAdmin.username, myAdmin.password);
  const admMe = (await rt.api('GET', '/api/auth/me', { token: adm })).json;
  const prod = (await rt.api('POST', '/api/products', { token: adm, body: { name: rt.uniq('dnuiP'), price: 100, active: true } })).json;
  const cust = await rt.createCustomer(adm, 'dnuiC');
  const other = await rt.createCustomer(adm, 'dnuiO');
  const mkInv = async (cid, qty) => (await rt.api('POST', '/api/invoices', { token: adm, body: {
    customerId: cid, salesPocUserId: admMe.id, notes: 'ui notes', items: [{ productId: prod.id, quantity: qty }] } })).json;
  const inv1 = await mkInv(cust.id, 2);
  const inv2 = await mkInv(cust.id, 1);
  const invO = await mkInv(other.id, 1);
  const pay1 = (await rt.api('POST', '/api/payments', { token: adm, body: { customerId: cust.id, amount: 50, method: 'CASH', invoiceIds: [inv2.id], collectionPocUserId: admMe.id } })).json;
  const ctok = await rt.login(cust.username, cust.password);
  const otok = await rt.login(other.username, other.password);
  // the other customer's dispute, to check the customer's list is scoped
  const dO = (await rt.api('POST', '/api/disputes', { token: otok, body: { targetType: 'INVOICE', targetId: invO.id, reason: 'OTHER CUSTOMER SECRET' } })).json;
  const ctx = { myAdmin: myAdmin.username, admId: admMe.id, cust: cust.username, custId: cust.id, other: other.username,
    inv1: inv1.id, inv1No: inv1.invoiceNumber, inv2: inv2.id, inv2No: inv2.invoiceNumber, pay1: pay1.id, dOther: dO.id, prod: prod.id };
  fs.writeFileSync(path.join(DIR, 'ui-ctx.json'), JSON.stringify(ctx, null, 2));
  console.log('ctx', JSON.stringify(ctx));

  const app = await rt.openApp({ token: ctok });
  const { page } = app;
  await rt.go(page, `#/invoices/${inv1.id}`, 4500);
  await dump(page, 'invoice detail');
  console.log(await rt.shot(page, DIR, 'u01-cust-invoice-detail'));

  await rt.tap(page, /Raise dispute/);
  await dump(page, 'dialog');
  console.log(await rt.shot(page, DIR, 'u02-dialog-open'));

  await rt.tap(page, 'Submit');
  await dump(page, 'after empty submit');
  console.log(await rt.shot(page, DIR, 'u03-dialog-empty-submit'));

  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI1 CRASHED', e); process.exit(1); });
