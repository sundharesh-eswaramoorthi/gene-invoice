// Fresh data for the UI verifications (own users/customers only).
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const pocA = await rt.createStaff(admin, 'COLLECTION_POC', 'vpcA');
  const pocB = await rt.createStaff(admin, 'COLLECTION_POC', 'vpcB');
  const pocC = await rt.createStaff(admin, 'COLLECTION_POC', 'vpcC');
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vpcS');
  const prod = (await api('POST', '/api/products', { name: rt.uniq('vpc-prod'), price: 100 })).json;
  const custA = await rt.createCustomer(admin, 'aaavpc'); // sorts into the first 50 names
  const custZ = await rt.createCustomer(admin, 'zzzvpc'); // sorts after the first 50 names
  for (const c of [custA, custZ]) {
    const s = await api('POST', `/api/customers/${c.id}/pocs`, { pocType: 'COLLECTION', userId: pocA.id, primary: true });
    if (s.status >= 300) throw new Error('seat ' + s.status + s.text);
  }
  const mkInv = async (cid, amount, date) => (await api('POST', '/api/invoices', { customerId: cid, invoiceDate: date,
    salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: amount }] })).json;
  const I1 = await mkInv(custA.id, 100, '2026-01-01T10:00:00Z');
  const I2 = await mkInv(custA.id, 200, '2026-02-01T10:00:00Z');
  const I3 = await mkInv(custA.id, 300, '2026-03-01T10:00:00Z');
  const future = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);
  const P1 = (await api('POST', '/api/promises', { customerId: custA.id, amount: 100, promisedDate: future, collectionPocUserId: pocA.id, invoiceIds: [I1.id] })).json;
  const P2 = (await api('POST', '/api/promises', { customerId: custA.id, amount: 300, promisedDate: future, collectionPocUserId: pocA.id, invoiceIds: [I3.id] })).json;
  const pay = (await api('POST', '/api/payments', { customerId: custA.id, amount: 150, method: 'Cash', notes: 'orig note',
    collectionPocUserId: pocA.id, promiseIds: [P1.id], invoiceIds: [I1.id, I2.id] })).json;
  const payInactive = (await api('POST', '/api/payments', { customerId: custA.id, amount: 5, notes: 'inactive poc seed',
    collectionPocUserId: pocC.id, invoiceIds: [I2.id] })).json;
  const del = await api('DELETE', `/api/users/${pocC.id}`);
  const promises = (await api('GET', `/api/promises?customerId=${custA.id}&size=50`)).json;
  const data = { pocA, pocB, pocC, sales, custA, custZ, I1, I2, I3, P1: P1.id, P2: P2.id, pay: { id: pay.id, alloc: pay.invoices.map((i) => i.invoiceNumber) },
    payInactive: { id: payInactive.id }, del: del.text, promiseTotal: promises.totalElements,
    promiseSample: JSON.stringify(promises.content.map((p) => ({ id: p.id, keys: Object.keys(p), payments: p.payments || p.linkedPayments || p.paymentIds }))) };
  fs.writeFileSync(path.join(__dirname, 'ui-data.json'), JSON.stringify(data, null, 2));
  console.log(JSON.stringify({ custA: custA.name, custZ: custZ.name, pocA: pocA.username, pocB: pocB.username, pay: data.pay, payInactive: payInactive.id,
    del: del.text, promises: data.promiseSample }, null, 1));
})().catch((e) => { console.error('ERR', e); process.exit(1); });
