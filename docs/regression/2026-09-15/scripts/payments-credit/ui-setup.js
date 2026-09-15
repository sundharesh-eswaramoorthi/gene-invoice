// Creates the UI test data and writes ui-data.json.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const pocU1 = await rt.createStaff(admin, 'COLLECTION_POC', 'uipc');
  const pocU2 = await rt.createStaff(admin, 'COLLECTION_POC', 'uipc');
  const pocU3 = await rt.createStaff(admin, 'COLLECTION_POC', 'uipc'); // deactivated later
  const sales = await rt.createStaff(admin, 'SALES_POC', 'uipc');
  const prod = (await api('POST', '/api/products', { name: rt.uniq('uipc-prod'), price: 100 })).json;
  const cu = await rt.createCustomer(admin, 'apaycr');   // sorts near the top of the dropdown
  const czz = await rt.createCustomer(admin, 'zzpaycr'); // sorts after the 50th customer
  await api('POST', `/api/customers/${cu.id}/pocs`, { pocType: 'COLLECTION', userId: pocU1.id, primary: true });
  await api('POST', `/api/customers/${czz.id}/pocs`, { pocType: 'COLLECTION', userId: pocU2.id, primary: true });
  const mkInv = async (cid, amount, date) => (await api('POST', '/api/invoices', {
    customerId: cid, invoiceDate: date, salesPocUserId: sales.id,
    items: [{ productId: prod.id, quantity: 1, unitPrice: amount }],
  })).json;
  const I1 = await mkInv(cu.id, 100, '2026-01-01T10:00:00Z');
  const I2 = await mkInv(cu.id, 200, '2026-02-01T10:00:00Z');
  const I3 = await mkInv(cu.id, 300, '2026-03-01T10:00:00Z');
  const future = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10);
  const pr = (await api('POST', '/api/promises', { customerId: cu.id, amount: 55, promisedDate: future, collectionPocUserId: pocU1.id })).json;
  const pay = (await api('POST', '/api/payments', { customerId: cu.id, amount: 120, method: 'Cash', notes: 'ui seed', collectionPocUserId: pocU1.id })).json;
  const payInactive = (await api('POST', '/api/payments', { customerId: cu.id, amount: 5, method: 'Cash', notes: 'inactive poc seed', collectionPocUserId: pocU3.id })).json;
  const del = await api('DELETE', `/api/users/${pocU3.id}`);
  const data = { pocU1, pocU2, pocU3, sales, prod, cu, czz, I1, I2, I3, promise: pr, pay, payInactive, del: del.json };
  fs.writeFileSync(path.join(__dirname, 'ui-data.json'), JSON.stringify(data, null, 2));
  console.log({ cu: cu.id, cuName: cu.name, czz: czz.id, czzName: czz.name, pocU1: pocU1.username, pocU2: pocU2.username,
    I1: I1.invoiceNumber, I2: I2.invoiceNumber, promise: pr.id, promiseStatus: pr.status, pay: pay.id,
    payAlloc: pay.invoices.map((x) => [x.invoiceNumber, x.allocatedAmount]), payInactive: payInactive.id, del: del.json });
})().catch((e) => { console.error(e); process.exit(1); });
