// Creates this area's own data and writes state.json.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const log = [];
function must(r, what) {
  log.push(`${what} -> ${r.status}`);
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}
const days = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

(async () => {
  const A = await rt.adminToken();
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });

  const prod = must(await api('POST', '/api/products', { name: rt.uniq('dhd-prod'), description: 'rt', price: 100, active: true }), 'product');
  const sales = await rt.createStaff(A, 'SALES_POC', 'dhd-sales');
  const coll = await rt.createStaff(A, 'COLLECTION_POC', 'dhd-coll');
  const coll2 = await rt.createStaff(A, 'COLLECTION_POC', 'dhd-coll2');
  const succ = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'dhd-succ');
  const custA = await rt.createCustomer(A, 'dhd-custA');
  const custB = await rt.createCustomer(A, 'dhd-custB');
  const custC = await rt.createCustomer(A, 'dhd-custC');

  // POC seats on A: add coll (primary), succ, then coll2 made primary, then remove succ.
  const seatColl = must(await api('POST', `/api/customers/${custA.id}/pocs`, { pocType: 'COLLECTION', userId: coll.id, primary: true }), 'seat coll');
  const seatSucc = must(await api('POST', `/api/customers/${custA.id}/pocs`, { pocType: 'SUCCESS', userId: succ.id, primary: true }), 'seat succ');
  const seatColl2 = must(await api('POST', `/api/customers/${custA.id}/pocs`, { pocType: 'COLLECTION', userId: coll2.id }), 'seat coll2');
  must(await api('POST', `/api/customers/${custA.id}/pocs/${seatColl2.id}/primary`), 'primary coll2');
  const rem = await api('DELETE', `/api/customers/${custA.id}/pocs/${seatSucc.id}`);
  log.push(`remove succ seat -> ${rem.status}`);
  must(await api('POST', `/api/customers/${custB.id}/pocs`, { pocType: 'COLLECTION', userId: coll.id, primary: true }), 'seat B coll');

  const inv = (customerId, qty, notes) => api('POST', '/api/invoices', {
    customerId, notes, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: qty }],
  });
  const A1 = must(await inv(custA.id, 2, 'A1 notes original'), 'invoice A1');
  const A2 = must(await inv(custA.id, 1, 'A2 notes original'), 'invoice A2');
  const B1 = must(await inv(custB.id, 3, 'B1 notes original'), 'invoice B1');
  const C1 = must(await inv(custC.id, 1, 'C1 notes'), 'invoice C1');

  const pay = (customerId, amount, invoiceIds, notes, extra = {}) => api('POST', '/api/payments', {
    customerId, amount, method: 'CASH', notes, invoiceIds, collectionPocUserId: coll.id, ...extra,
  });
  const PA1 = must(await pay(custA.id, 50, [A1.id], 'PA1 notes original'), 'payment PA1');
  const PA2 = must(await pay(custA.id, 20, [A1.id], 'PA2 to be voided'), 'payment PA2');
  const PA3 = must(await pay(custA.id, 10, [A2.id], 'PA3 on A2'), 'payment PA3');
  const PB1 = must(await pay(custB.id, 30, [B1.id], 'PB1 notes original'), 'payment PB1');

  const PR1 = must(await api('POST', '/api/promises', { customerId: custA.id, amount: 100, promisedDate: days(10), invoiceIds: [A1.id], notes: 'PR1 on A1' }), 'promise PR1');
  const PR2 = must(await api('POST', '/api/promises', { customerId: custA.id, amount: 60, promisedDate: days(12), notes: 'PR2 general' }), 'promise PR2');
  const PR3 = must(await api('POST', '/api/promises', { customerId: custA.id, amount: 10, promisedDate: days(12), invoiceIds: [A2.id], notes: 'PR3 on A2' }), 'promise PR3');

  const cAT = await rt.login(custA.username, custA.password);
  // D1: on A1, opened by customer A, denied.
  const D1 = must(await api('POST', '/api/disputes', { targetType: 'INVOICE', targetId: A1.id, reason: 'D1 short reason' }, cAT), 'dispute D1');
  must(await api('POST', `/api/disputes/${D1.id}/deny`, { adminNotes: 'D1 denied by admin' }), 'deny D1');
  // D2: on A1, a >500 character reason, left pending.
  const longReason = 'LONG-REASON ' + 'x'.repeat(600) + ' END';
  const D2 = must(await api('POST', '/api/disputes', { targetType: 'INVOICE', targetId: A1.id, reason: longReason }, cAT), 'dispute D2 long');
  // D3: on PA2 (payment), approved with void -> PAYMENT_REVERSED on A1.
  const D3 = must(await api('POST', '/api/disputes', { targetType: 'PAYMENT', targetId: PA2.id, reason: 'D3 duplicate payment', proposedChangeJson: JSON.stringify({ action: 'void' }) }, cAT), 'dispute D3');
  must(await api('POST', `/api/disputes/${D3.id}/approve`, { adminNotes: 'D3 approved void' }), 'approve D3');
  // D4: on A2 approved with update_notes, reason > 500 chars also (approval audit reason).
  const D4 = must(await api('POST', '/api/disputes', { targetType: 'INVOICE', targetId: A2.id, reason: 'D4 ' + 'y'.repeat(700), proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'A2 notes via dispute' }) }, cAT), 'dispute D4 long');
  must(await api('POST', `/api/disputes/${D4.id}/approve`, {}), 'approve D4 (no admin notes, long reason)');

  // Customer A edit -> CUSTOMER_UPDATED
  must(await api('PUT', `/api/customers/${custA.id}`, { name: custA.name, phone: '555-0199', email: custA.email, address: '2 Test Way' }), 'customer A update');

  // C: 105 invoice notes edits -> > 100 history rows
  for (let i = 0; i < 105; i++) {
    const r = await api('PATCH', `/api/invoices/${C1.id}`, { notes: `C1 note edit ${i}`, salesPocUserId: sales.id });
    if (r.status >= 300) { log.push(`C1 patch ${i} -> ${r.status} ${r.text}`); break; }
  }

  const state = {
    prod, sales, coll, coll2, succ, custA, custB, custC,
    seatColl, seatSucc, seatColl2,
    A1, A2, B1, C1, PA1, PA2, PA3, PB1, PR1, PR2, PR3, D1, D2, D3, D4, longReason,
  };
  fs.writeFileSync(path.join(DIR, 'state.json'), JSON.stringify(state, null, 2));
  console.log(log.join('\n'));
  console.log('ids', { custA: custA.id, custB: custB.id, custC: custC.id, A1: A1.id, A2: A2.id, B1: B1.id, C1: C1.id, PA1: PA1.id, PA2: PA2.id, PA3: PA3.id, PB1: PB1.id, PR1: PR1.id, PR2: PR2.id, PR3: PR3.id, D1: D1.id, D2: D2.id, D3: D3.id, D4: D4.id });
})().catch((e) => { console.error('SETUP FAILED', e.message); console.log(log.join('\n')); process.exit(1); });
