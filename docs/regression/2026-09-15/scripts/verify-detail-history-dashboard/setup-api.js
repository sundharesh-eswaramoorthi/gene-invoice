// Verifier: creates own data, then reproduces HIST-006, API-500-001 and DET-006 (API part).
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const out = {};
function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}
const days = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

(async () => {
  const A = await rt.adminToken();
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });

  const prod = must(await api('POST', '/api/products', { name: rt.uniq('vdhd-prod'), description: 'rt', price: 100, active: true }), 'product');
  const sales = await rt.createStaff(A, 'SALES_POC', 'vdhd-sales');
  const coll = await rt.createStaff(A, 'COLLECTION_POC', 'vdhd-coll');
  const cust = await rt.createCustomer(A, 'vdhd-cust');
  must(await api('POST', `/api/customers/${cust.id}/pocs`, { pocType: 'COLLECTION', userId: coll.id, primary: true }), 'seat coll');

  const inv = (qty, notes) => api('POST', '/api/invoices', {
    customerId: cust.id, notes, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: qty }],
  });
  const V1 = must(await inv(2, 'V1 notes original'), 'invoice V1');
  const V2 = must(await inv(1, 'V2 notes original'), 'invoice V2');
  const pay = (amount, invoiceIds, notes) => api('POST', '/api/payments', {
    customerId: cust.id, amount, method: 'CASH', notes, invoiceIds, collectionPocUserId: coll.id,
  });
  const P1 = must(await pay(50, [V1.id], 'P1 notes original'), 'payment P1');
  const PR1 = must(await api('POST', '/api/promises', { customerId: cust.id, amount: 100, promisedDate: days(10), invoiceIds: [V1.id], notes: 'PR1 on V1' }), 'PR1');
  const PR2 = must(await api('POST', '/api/promises', { customerId: cust.id, amount: 60, promisedDate: days(12), invoiceIds: [V2.id], notes: 'PR2 on V2' }), 'PR2');

  const cT = await rt.login(cust.username, cust.password);
  const D1 = must(await api('POST', '/api/disputes', { targetType: 'INVOICE', targetId: V1.id, reason: 'VD1 reason' }, cT), 'dispute D1');
  must(await api('POST', `/api/disputes/${D1.id}/deny`, { adminNotes: 'VD1 denied by admin' }), 'deny D1');
  const me = (await api('GET', '/api/auth/me')).json;

  Object.assign(out, { prod: prod.id, sales: sales.id, coll: coll.id, cust: { id: cust.id, username: cust.username, password: cust.password },
    V1: V1.id, V2: V2.id, P1: P1.id, PR1: PR1.id, PR2: PR2.id, D1: D1.id, adminId: me?.id });

  // ---- HIST-006 ----
  const h = await api('GET', `/api/audit?entityType=CUSTOMER&entityId=${cust.id}&includeRelated=true`, undefined, cT);
  const denied = (h.json || []).find((e) => e.action === 'DISPUTE_DENIED' && e.entityId === D1.id);
  const opened = (h.json || []).find((e) => e.action === 'DISPUTE_OPENED' && e.entityId === D1.id);
  out.HIST006 = {
    status: h.status,
    denied: denied && { actorHidden: denied.actorHidden, changedByUserId: denied.changedByUserId, changedByUsername: denied.changedByUsername, afterJson: denied.afterJson },
    openedAfter: opened && opened.afterJson,
  };
  // Same customer reading the dispute itself: does it already expose resolvedByUserId?
  const dget = await api('GET', `/api/disputes/${D1.id}`, undefined, cT);
  out.HIST006.customerDisputeGet = { status: dget.status, resolvedByUserId: dget.json?.resolvedByUserId, openedByUserId: dget.json?.openedByUserId };
  const dlist = await api('GET', `/api/disputes?size=10`, undefined, cT);
  out.HIST006.customerDisputeList = { status: dlist.status, row: (dlist.json?.content || []).find((d) => d.id === D1.id) };
  // Can a customer resolve a user id to a name?
  const u1 = await api('GET', `/api/users/${me?.id}`, undefined, cT);
  out.HIST006.customerUsersGet = { status: u1.status };
  // Invoice timeline too
  const hi = await api('GET', `/api/audit?entityType=INVOICE&entityId=${V1.id}&includeRelated=true`, undefined, cT);
  const deniedI = (hi.json || []).find((e) => e.action === 'DISPUTE_DENIED');
  out.HIST006.invoiceTimelineDenied = deniedI && { actorHidden: deniedI.actorHidden, changedByUserId: deniedI.changedByUserId, afterJson: deniedI.afterJson };

  // ---- API-500-001 ----
  out.API500 = {};
  for (const p of ['/api/invoices/abc', '/api/customers/abc', '/api/payments/abc', '/api/disputes/abc', '/api/promises/abc',
    '/api/audit?entityType=CUSTOMER&entityId=abc', '/api/invoices/99999999', '/api/invoices?size=abc']) {
    const r = await api('GET', p);
    out.API500[p] = { status: r.status, body: r.text.slice(0, 260) };
  }

  // ---- DET-006 (API side) ----
  const links = {};
  for (const id of [PR1.id, PR2.id]) {
    const r = await api('GET', `/api/promises/${id}`);
    links[id] = { status: r.json?.status, payments: (r.json?.payments || []).map((x) => x.paymentId ?? x.id), invoiceIds: r.json?.invoiceIds ?? (r.json?.invoices || []).map((i) => i.invoiceId ?? i.id) };
  }
  out.DET006 = { promiseLinks: links, rawPR1: (await api('GET', `/api/promises/${PR1.id}`)).json };
  const byCust = await api('GET', `/api/promises?customerId=${cust.id}&size=50`);
  out.DET006.customerPromises = (byCust.json?.content || []).map((p) => p.id);
  const byPay = await api('GET', `/api/promises?paymentId=${P1.id}&size=50`);
  out.DET006.paymentIdFilter = { status: byPay.status, body: byPay.text.slice(0, 200) };

  // ---- DASH-006 (API side) ----
  const sum = await api('GET', '/api/promises/summary');
  out.DASH006 = { status: sum.status, keys: Object.keys(sum.json || {}), sample: sum.json };

  fs.writeFileSync(path.join(DIR, 'state.json'), JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });
