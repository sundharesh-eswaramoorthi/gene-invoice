// Re-verifies group 5 of the medium defects (how payments count towards promises): D-34, D-35,
// D-26, plus D-68 (a dispute reason over 1,000 characters), found during the medium re-check.
// Targets 8083, own data.
// Run: node api-group5.js → prints a summary and writes api-group5-results.json next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const results = [];
function check(id, defect, name, ok, detail) {
  results.push({ id, defect, name, status: ok ? 'PASS' : 'FAIL', detail });
}
async function step(id, defect, name, fn) {
  try {
    await fn();
  } catch (e) {
    results.push({ id, defect, name, status: 'ERROR', detail: String((e && e.stack) || e) });
  }
}
const inDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
const pick = (p) => p && { id: p.id, amount: p.amount, status: p.status, fulfilledAmount: p.fulfilledAmount,
  payments: (p.payments || []).map((x) => x.id) };

(async () => {
  const admin = await rt.adminToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vm5-sales');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vm5-coll');
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];
  const invoice = async (customerId, price) => (await rt.api('POST', '/api/invoices', {
    token: admin, body: { customerId, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: price }] },
  })).json;
  const promise = async (customerId, amount, days, invoiceIds) => (await rt.api('POST', '/api/promises', {
    token: admin, body: { customerId, amount, promisedDate: inDays(days), collectionPocUserId: coll.id, invoiceIds },
  })).json;
  const pay = (customerId, amount, extra = {}) => rt.api('POST', '/api/payments', {
    token: admin, body: { customerId, amount, method: 'Cash', collectionPocUserId: coll.id, ...extra },
  });
  const getPromise = async (id) => (await rt.api('GET', `/api/promises/${id}`, { token: admin })).json;
  const getInvoice = async (id) => (await rt.api('GET', `/api/invoices/${id}`, { token: admin })).json;

  // ---- D-34 ------------------------------------------------------------------------------
  await step('M5-01', 'D-34', 'one payment counts once across two promises on the same invoice', async () => {
    const cust = await rt.createCustomer(admin, 'vm5-same');
    const inv = await invoice(cust.id, 100);
    const a = await promise(cust.id, 100, 5, [inv.id]);
    const b = await promise(cust.id, 100, 6, [inv.id]);
    const p = await pay(cust.id, 100, { invoiceIds: [inv.id] });
    const pa = await getPromise(a.id);
    const pb = await getPromise(b.id);
    const counted = Number(pa.fulfilledAmount) + Number(pb.fulfilledAmount);
    check('M5-01', 'D-34', 'invoice settled → both KEPT; fulfilled adds up to the 100 paid, not 200',
      p.status === 200 && pa.status === 'KEPT' && pb.status === 'KEPT' && counted === 100,
      { payment: p.status, first: pick(pa), second: pick(pb), counted });
  });

  let general; // reused by the D-26 check
  await step('M5-02', 'D-34', 'two general promises share one payment, earliest date first', async () => {
    const cust = await rt.createCustomer(admin, 'vm5-general');
    await invoice(cust.id, 300);
    const later = await promise(cust.id, 100, 8, null);
    const earlier = await promise(cust.id, 100, 5, null);
    const p = await pay(cust.id, 100);
    const pe = await getPromise(earlier.id);
    const pl = await getPromise(later.id);
    general = { cust, payment: p.json, earlier: pe, later: pl };
    check('M5-02', 'D-34', 'payment of 100 → the earlier promise KEPT (100), the later OPEN (0)',
      p.status === 200 && pe.status === 'KEPT' && Number(pe.fulfilledAmount) === 100
        && pl.status === 'OPEN' && Number(pl.fulfilledAmount) === 0,
      { earlier: pick(pe), later: pick(pl) });
  });

  // ---- D-35 ------------------------------------------------------------------------------
  await step('M5-03', 'D-35', 'a payment ticked to a promise pays that promise\'s invoice', async () => {
    const cust = await rt.createCustomer(admin, 'vm5-ticked');
    const older = await invoice(cust.id, 100);
    const promised = await invoice(cust.id, 100);
    const pr = await promise(cust.id, 100, 5, [promised.id]);
    const p = await pay(cust.id, 100, { promiseIds: [pr.id] });
    const o = await getInvoice(older.id);
    const n = await getInvoice(promised.id);
    const after = await getPromise(pr.id);
    check('M5-03', 'D-35', 'no invoices chosen, promise ticked → its invoice is paid and the promise KEPT',
      p.status === 200 && n.status === 'FULLY_PAID' && o.status !== 'FULLY_PAID' && after.status === 'KEPT'
        && Number(after.fulfilledAmount) === 100,
      { payment: p.status, olderInvoice: o.status, promisedInvoice: n.status, promise: pick(after) });
  });

  await step('M5-04', 'D-35', 'a ticked promise the chosen invoices cannot serve is refused', async () => {
    const cust = await rt.createCustomer(admin, 'vm5-disjoint');
    const other = await invoice(cust.id, 100);
    const promised = await invoice(cust.id, 100);
    const pr = await promise(cust.id, 100, 5, [promised.id]);
    const p = await pay(cust.id, 100, { invoiceIds: [other.id], promiseIds: [pr.id] });
    const payments = (await rt.api('GET', `/api/payments?size=10&filter=customerId:eq:${cust.id}`, { token: admin })).json;
    check('M5-04', 'D-35', 'chosen invoice ≠ the promise\'s invoice → 400 naming the promise; nothing recorded',
      p.status === 400 && /covers none of the chosen invoices/.test(p.json && p.json.message)
        && payments.totalElements === 0,
      { status: p.status, message: p.json && p.json.message, paymentsRecorded: payments.totalElements });
  });

  // ---- D-26 ------------------------------------------------------------------------------
  await step('M5-05', 'D-26', 'a payment\'s Promises tab lists only the promises it is linked to', async () => {
    const { cust, payment, earlier } = general;
    // The exact request the Payment Details "Payment Promise" tab sends.
    const tab = await rt.api('GET', `/api/promises?size=50&customerId=${cust.id}&paymentId=${payment.id}`, { token: admin });
    const all = await rt.api('GET', `/api/promises?size=50&customerId=${cust.id}`, { token: admin });
    const ids = tab.json.content.map((x) => x.id);
    check('M5-05', 'D-26', 'customer has 2 promises; the payment tab lists only the one the payment counts for',
      tab.status === 200 && all.json.totalElements === 2 && ids.length === 1 && ids[0] === earlier.id,
      { tab: ids, customerPromises: all.json.content.map((x) => x.id) });
  });

  // ---- recompute endpoint -------------------------------------------------------------------
  await step('M5-06', 'D-34', 'existing promises can be previewed under the new rule, by admin only', async () => {
    const cashier = await rt.cashierToken();
    const preview = await rt.api('POST', '/api/promises/recompute?apply=false', { token: admin });
    const denied = await rt.api('POST', '/api/promises/recompute?apply=false', { token: cashier });
    check('M5-06', 'D-34', 'admin preview → 200 with a list of changes; cashier → 403',
      preview.status === 200 && Array.isArray(preview.json) && denied.status === 403,
      { preview: [preview.status, Array.isArray(preview.json) ? `${preview.json.length} changes` : preview.text.slice(0, 200)],
        cashier: denied.status });
  });

  // ---- D-68 ------------------------------------------------------------------------------
  await step('M5-07', 'D-68', 'a dispute reason over 1,000 characters is saved', async () => {
    const cust = await rt.createCustomer(admin, 'vm5-dispute');
    const inv = await invoice(cust.id, 100);
    const ct = await rt.login(cust.username, cust.password);
    const reason = 'x'.repeat(1496) + '-END';
    const d = await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv.id, reason } });
    const id = d.json && d.json.id;
    const back = id && (await rt.api('GET', `/api/disputes/${id}`, { token: admin })).json;
    const notes = (await rt.api('GET', '/api/notifications?size=50', { token: admin })).json;
    const note = (notes.content || notes).find((x) => x.link && x.link.endsWith('/' + id));
    check('M5-07', 'D-68', '1,500-character reason → dispute opened with the full reason; admin notification cut to 1,000 with "…"',
      (d.status === 200 || d.status === 201) && back && back.reason === reason
        && note && note.message.length === 1000 && note.message.endsWith('…'),
      { status: d.status, message: d.json && d.json.message, savedReasonLength: back && back.reason.length,
        notification: note && { length: note.message.length, ends: note.message.slice(-5) } });
  });

  fs.writeFileSync(path.join(__dirname, 'api-group5-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
