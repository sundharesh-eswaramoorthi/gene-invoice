// Re-verifies the API side of group 4 of the medium defects (tables and screens): D-23 and D-39.
// The rest of group 4 (D-18, D-19, D-20, D-21, D-24, D-25, D-38) is frontend and checked in the
// browser. Targets 8083 (Postgres, where D-23's rounding was seen) and creates its own data.
// Run: node api-group4.js → prints a summary and writes api-group4-results.json next to this file.
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
const utcDay = (offsetDays) => new Date(Date.now() + offsetDays * 86400000).toISOString().slice(0, 10);

(async () => {
  const admin = await rt.adminToken();
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vm4');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vm4');
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin })).json.content[0];

  // ---- D-23 ------------------------------------------------------------------------------
  await step('M4-01', 'D-23', 'a bare date covers its whole day and nothing of the next', async () => {
    const cust = await rt.createCustomer(admin, 'vm4-dates');
    const today = utcDay(0);
    const yesterday = utcDay(-1);
    for (const at of [`${today}T00:00:00Z`, `${yesterday}T23:59:59.999999Z`]) {
      const r = await rt.api('POST', '/api/invoices', {
        token: admin,
        body: { customerId: cust.id, invoiceDate: at, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: 10 }] },
      });
      if (r.status !== 200) throw new Error(`invoice at ${at} → ${r.status} ${r.text}`);
    }
    const count = async (filter) => {
      const params = new URLSearchParams();
      params.append('filter', `customerId:eq:${cust.id}`);
      params.append('filter', filter);
      const r = await rt.api('GET', `/api/invoices?${params}`, { token: admin });
      return r.status === 200 ? r.json.totalElements : `HTTP ${r.status}`;
    };
    const got = {
      [`lte ${yesterday}`]: await count(`invoiceDate:lte:${yesterday}`),
      [`between ${yesterday}`]: await count(`invoiceDate:between:${yesterday},${yesterday}`),
      'relative yesterday': await count('invoiceDate:relative:yesterday'),
      'relative past': await count('invoiceDate:relative:past'),
      [`gte ${today}`]: await count(`invoiceDate:gte:${today}`),
    };
    check('M4-01', 'D-23', 'midnight belongs to today: every "through yesterday" filter counts 1, gte today counts 1',
      Object.values(got).every((n) => n === 1), got);
  });

  // ---- D-39 ------------------------------------------------------------------------------
  await step('M4-02', 'D-39', 'a dispute carries its target\'s number and amount', async () => {
    const cust = await rt.createCustomer(admin, 'vm4-dispute');
    const custTok = await rt.login(cust.username, cust.password);
    const inv = (await rt.api('POST', '/api/invoices', {
      token: admin, body: { customerId: cust.id, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: 451234.5 }] },
    })).json;
    const pay = (await rt.api('POST', '/api/payments', {
      token: admin, body: { customerId: cust.id, amount: 1000, method: 'Cash', collectionPocUserId: coll.id, invoiceIds: [inv.id] },
    })).json;
    const onInvoice = (await rt.api('POST', '/api/disputes', { token: custTok, body: { targetType: 'INVOICE', targetId: inv.id, reason: 'vm4' } })).json;
    const onPayment = (await rt.api('POST', '/api/disputes', { token: custTok, body: { targetType: 'PAYMENT', targetId: pay.id, reason: 'vm4' } })).json;
    const a = (await rt.api('GET', `/api/disputes/${onInvoice.id}`, { token: admin })).json;
    const b = (await rt.api('GET', `/api/disputes/${onPayment.id}`, { token: admin })).json;
    check('M4-02', 'D-39', 'invoice: number + total; payment: #id + amount; summary kept',
      a.targetNumber === inv.invoiceNumber && Number(a.targetAmount) === 451234.5 && typeof a.targetSummary === 'string'
        && b.targetNumber === `#${pay.id}` && Number(b.targetAmount) === 1000,
      { invoice: [a.targetNumber, a.targetAmount, a.targetSummary], payment: [b.targetNumber, b.targetAmount, b.targetSummary] });
  });

  fs.writeFileSync(path.join(__dirname, 'api-group4-results.json'), JSON.stringify(results, null, 2));
  for (const r of results) console.log(`${r.status.padEnd(5)} ${r.id} ${r.defect} ${r.name}`);
  const bad = results.filter((r) => r.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})();
