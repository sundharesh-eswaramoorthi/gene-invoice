// Area F / payments — allocation, credit and voiding, after the due-date work.
const { rt, recorder, money, eq, admin, createProduct, createInvoice, recordPayment } = require('./_h.js');

(async () => {
  const r = recorder('02-payments');
  const { token, id: adminId } = await admin();
  const cust = await rt.createCustomer(token, 'f');
  const prod = await createProduct(token, 'f-pay', '100.00');
  const mk = (n, qty, date) => createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
    items: [{ productId: prod.id, quantity: qty, unitPrice: '100.00' }],
    invoiceDate: date, notes: n });

  // Three invoices, oldest first: 100, 200, 300.
  const day = (d) => new Date(Date.now() - d * 86400000).toISOString();
  const i1 = await mk('oldest', 1, day(30));
  const i2 = await mk('middle', 2, day(20));
  const i3 = await mk('newest', 3, day(10));
  const read = async (id) => (await rt.api('GET', `/api/invoices/${id}`, { token })).json;

  // ---- F-15 oldest-first allocation -------------------------------------------
  let pay1;
  await r.check({
    id: 'F-15', feature: 'Payment allocation', kind: 'API',
    title: 'A payment with no chosen invoices is allocated oldest invoice first',
    steps: 'three invoices dated -30/-20/-10 days for 100/200/300; POST /api/payments amount 250',
    expected: 'oldest fully paid (100), next partly paid (150 of 200), newest untouched, creditApplied 0',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:193',
  }, async () => {
    pay1 = await recordPayment(token, { customerId: cust.id, amount: '250.00', method: 'CASH',
      collectionPocUserId: adminId });
    const [a, b, c] = await Promise.all([read(i1.id), read(i2.id), read(i3.id)]);
    const ok = eq(a.paidAmount, 100) && a.status === 'FULLY_PAID'
      && eq(b.paidAmount, 150) && b.status === 'PARTIALLY_PAID'
      && eq(c.paidAmount, 0) && c.status === 'UNPAID'
      && eq(pay1.creditApplied, 0);
    return { ok, severity: 'high',
      actual: `i1 paid=${a.paidAmount}/${a.status} i2 paid=${b.paidAmount}/${b.status} i3 paid=${c.paidAmount}/${c.status} creditApplied=${pay1.creditApplied}` };
  });

  await r.check({
    id: 'F-16', feature: 'Payment allocation', kind: 'API',
    title: 'The payment records its allocations and they add up to the amount',
    steps: 'GET /api/payments/{id}',
    expected: 'sum(allocations) + creditApplied = amount; each allocation names the invoice it landed on',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/CreditLedger.java:17',
  }, async () => {
    const p = (await rt.api('GET', `/api/payments/${pay1.id}`, { token })).json;
    const list = p.invoices || p.allocations || [];
    const sum = list.reduce((a, x) => a + Number(x.allocatedAmount ?? x.amount ?? 0), 0);
    return { ok: eq(sum + Number(p.creditApplied), p.amount) && list.length === 2,
      actual: `amount=${p.amount} allocations=${list.length} sum=${money(sum)} creditApplied=${p.creditApplied}` };
  });

  // ---- F-17 overpayment -> customer credit ------------------------------------
  let pay2;
  await r.check({
    id: 'F-17', feature: 'Customer credit', kind: 'API',
    title: 'An overpayment settles the rest and the excess becomes customer credit',
    steps: 'remaining balance is 50 + 300 = 350; POST /api/payments amount 500',
    expected: 'all three invoices FULLY_PAID; payment.creditApplied = 150; customer.creditBalance rises by 150',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:220',
  }, async () => {
    const before = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.creditBalance;
    pay2 = await recordPayment(token, { customerId: cust.id, amount: '500.00', method: 'BANK',
      collectionPocUserId: adminId });
    const after = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const [a, b, c] = await Promise.all([read(i1.id), read(i2.id), read(i3.id)]);
    const ok = eq(pay2.creditApplied, 150) && eq(after.creditBalance, Number(before) + 150)
      && [a, b, c].every((x) => x.status === 'FULLY_PAID');
    return { ok, severity: 'high',
      actual: `creditApplied=${pay2.creditApplied} creditBalance ${before} -> ${after.creditBalance} statuses=${[a, b, c].map((x) => x.status).join('/')}` };
  });

  // ---- F-18 credit consumed by the next invoice --------------------------------
  let i4;
  await r.check({
    id: 'F-18', feature: 'Customer credit', kind: 'API',
    title: 'The next invoice raised for the customer consumes the credit balance',
    steps: 'with 150 credit on the account, create a 100 invoice, then a 300 invoice',
    expected: 'the 100 invoice is FULLY_PAID from credit; credit falls to 50; the 300 invoice takes the last 50 and is PARTIALLY_PAID; credit ends at 0',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/CreditLedger.java:44',
  }, async () => {
    const small = await mk('credit-eater', 1);
    const mid = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.creditBalance;
    i4 = await mk('credit-remainder', 3);
    const end = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.creditBalance;
    const big = await read(i4.id);
    const ok = eq(small.paidAmount, 100) && small.status === 'FULLY_PAID' && eq(mid, 50)
      && eq(big.paidAmount, 50) && big.status === 'PARTIALLY_PAID' && eq(end, 0);
    return { ok, severity: 'high',
      actual: `small paid=${small.paidAmount}/${small.status}, credit after small=${mid}, big paid=${big.paidAmount}/${big.status}, credit after big=${end}` };
  });

  // ---- F-19 void (through the dispute flow, the app's only void path) -----------
  const custToken = await rt.login(cust.username, cust.password);
  await r.check({
    id: 'F-19', feature: 'Payment void', kind: 'API',
    title: 'Voiding a payment takes back exactly the money it put in',
    steps: 'customer opens a dispute on payment 1 with {"action":"void"}; admin approves it',
    expected: 'payment VOIDED; the 250 it applied comes back off the invoices and/or the credit balance, totalling 250',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:236',
  }, async () => {
    const before = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const beforeInv = await Promise.all([read(i1.id), read(i2.id), read(i3.id), read(i4.id)]);
    const d = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'PAYMENT', targetId: pay1.id, reason: 'area-f void test',
        proposedChangeJson: '{"action":"void"}' } });
    if (d.status >= 300) return { ok: false, severity: 'high', actual: `dispute open -> ${d.status} ${d.text.slice(0, 200)}` };
    const a = await rt.api('POST', `/api/disputes/${d.json.id}/approve`, { token, body: {} });
    const p = (await rt.api('GET', `/api/payments/${pay1.id}`, { token })).json;
    const afterInv = await Promise.all([read(i1.id), read(i2.id), read(i3.id), read(i4.id)]);
    const after = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const paidDrop = beforeInv.reduce((x, y) => x + Number(y.paidAmount), 0)
      - afterInv.reduce((x, y) => x + Number(y.paidAmount), 0);
    const creditDrop = Number(before.creditBalance) - Number(after.creditBalance);
    const ok = a.status === 200 && p.status === 'VOIDED' && eq(paidDrop + creditDrop, 250);
    return { ok, severity: 'high',
      actual: `approve=${a.status} payment status=${p.status}; invoice paid dropped ${money(paidDrop)}, credit dropped ${money(creditDrop)}, together ${money(paidDrop + creditDrop)} (expected 250.00)` };
  });

  await r.check({
    id: 'F-20', feature: 'Payment void', kind: 'API',
    title: 'A voided payment keeps amount = sum(allocations) + creditApplied at zero allocations',
    steps: 'GET /api/payments/{id} after the void',
    expected: 'status VOIDED, allocations empty, creditApplied 0; the amount is unchanged as a record of what was voided',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:255',
  }, async () => {
    const p = (await rt.api('GET', `/api/payments/${pay1.id}`, { token })).json;
    return { ok: p.status === 'VOIDED' && (p.invoices || []).length === 0 && eq(p.creditApplied, 0)
        && eq(p.amount, 250),
      actual: `status=${p.status} allocations=${(p.invoices || []).length} creditApplied=${p.creditApplied} amount=${p.amount}` };
  });

  // ---- F-21 targeted allocation -------------------------------------------------
  await r.check({
    id: 'F-21', feature: 'Payment allocation', kind: 'API',
    title: 'A payment aimed at chosen invoiceIds goes to those invoices only',
    steps: 'two new invoices A (older) and B (newer); pay 100 with invoiceIds:[B]',
    expected: 'B is paid, A is untouched',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:68',
  }, async () => {
    const a = await mk('targeted-old', 1, day(9));
    const b = await mk('targeted-new', 1, day(1));
    await recordPayment(token, { customerId: cust.id, amount: '100.00', method: 'CASH',
      collectionPocUserId: adminId, invoiceIds: [b.id] });
    const [ra, rb] = await Promise.all([read(a.id), read(b.id)]);
    return { ok: eq(ra.paidAmount, 0) && eq(rb.paidAmount, 100) && rb.status === 'FULLY_PAID',
      severity: 'high',
      actual: `A paid=${ra.paidAmount}/${ra.status} B paid=${rb.paidAmount}/${rb.status}` };
  });

  await r.check({
    id: 'F-22', feature: 'Payment validation', kind: 'API',
    title: "A payment cannot be aimed at another customer's invoice",
    steps: 'create a second customer with an invoice; POST /api/payments for customer 1 with that invoiceId',
    expected: '400 "does not belong to this customer"; nothing is written',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:85',
  }, async () => {
    const other = await rt.createCustomer(token, 'f');
    const oi = await createInvoice(token, { customerId: other.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 1, unitPrice: '100.00' }] });
    const p = await rt.api('POST', '/api/payments', { token,
      body: { customerId: cust.id, amount: '10.00', collectionPocUserId: adminId, invoiceIds: [oi.id] } });
    const after = (await rt.api('GET', `/api/invoices/${oi.id}`, { token })).json;
    return { ok: p.status === 400 && eq(after.paidAmount, 0), severity: 'high',
      actual: `${p.status} ${p.text.slice(0, 140)}; foreign invoice paid=${after.paidAmount}` };
  });

  // ---- F-23/F-24 payments list & tiles -------------------------------------------
  await r.check({
    id: 'F-23', feature: 'Payment list', kind: 'API',
    title: 'Payments list still paginates, sorts and filters server-side',
    steps: 'GET /api/payments?customerId=..&size=10&sort=amount,desc and filter=status:eq:VOIDED',
    expected: 'ordered by amount descending; the VOIDED filter returns only the voided payment',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:113',
  }, async () => {
    const s = await rt.api('GET', `/api/payments?customerId=${cust.id}&size=10&sort=amount,desc`, { token });
    const amounts = s.json.content.map((p) => Number(p.amount));
    const voided = await rt.api('GET', `/api/payments?customerId=${cust.id}&filter=status:eq:VOIDED&size=50`, { token });
    const ordered = amounts.every((v, i) => i === 0 || amounts[i - 1] >= v);
    const ok = s.status === 200 && ordered && voided.json.content.length === 1
      && voided.json.content[0].id === pay1.id;
    return { ok, actual: `sorted=${ordered} amounts=${amounts.join(',')} voidedRows=${voided.json.content.length}` };
  });

  await r.check({
    id: 'F-24', feature: 'Payment list tiles', kind: 'API',
    title: 'Payment tiles are server-side over the filtered set and exclude voided money from "collected"',
    steps: 'GET /api/payments/summary?customerId=..; compare with the full list',
    expected: 'count = number of payments; totalCollected = sum of ACTIVE amounts; voidedCount = 1',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:375',
  }, async () => {
    const list = (await rt.api('GET', `/api/payments?customerId=${cust.id}&size=50`, { token })).json;
    const t = (await rt.api('GET', `/api/payments/summary?customerId=${cust.id}`, { token })).json;
    const active = list.content.filter((p) => p.status === 'ACTIVE');
    const collected = active.reduce((a, p) => a + Number(p.amount), 0);
    const k = Object.keys(t);
    const collectedKey = k.find((x) => /collect/i.test(x));
    const ok = t.count === list.totalElements && eq(t[collectedKey], collected)
      && t.voidedCount === list.content.filter((p) => p.status === 'VOIDED').length;
    return { ok, actual: `tiles=${JSON.stringify(t)} list total=${list.totalElements} activeSum=${money(collected)}` };
  });

  r.save();
})();
