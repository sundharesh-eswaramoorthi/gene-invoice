// Area F / dashboard — every card except the ageing chart (area B owns that one).
// Read through a Sales POC's own book, so the shared database cannot move the numbers under us.
const { rt, recorder, money, eq, admin, createProduct, createInvoice, recordPayment } = require('./_h.js');

(async () => {
  const r = recorder('05-dashboard');
  const { token } = await admin();
  const spoc = await rt.createStaff(token, 'SALES_POC', 'f');
  const cust = await rt.createCustomer(token, 'f');
  const other = await rt.createCustomer(token, 'f');
  const prod = await createProduct(token, 'f-dash', '100.00');
  const mk = (customerId, qty) => createInvoice(token, { customerId, salesPocUserId: spoc.id,
    items: [{ productId: prod.id, quantity: qty, unitPrice: '100.00' }] });

  // cust: 300 billed, 120 collected.  other: 200 billed, nothing collected.
  const i1 = await mk(cust.id, 1);   // 100
  const i2 = await mk(cust.id, 2);   // 200
  const i3 = await mk(other.id, 2);  // 200
  const cancelled = await mk(cust.id, 5); // 500, cancelled below
  await rt.api('POST', `/api/invoices/${cancelled.id}/cancel`, { token });
  await recordPayment(token, { customerId: cust.id, amount: '120.00', method: 'CASH',
    collectionPocUserId: (await admin()).id, invoiceIds: [i1.id, i2.id] });

  const spocToken = await rt.login(spoc.username, spoc.password);
  const thisMonth = new Date().toISOString().slice(0, 7);
  const get = (p) => rt.api('GET', p, { token: spocToken });

  // ---- F-44 billed-by-month -----------------------------------------------------
  await r.check({
    id: 'F-44', feature: 'Dashboard billed-by-month', kind: 'API',
    title: 'billed-by-month returns the book total for the current month, cancelled invoices excluded',
    steps: 'GET /api/dashboard/billed-by-month?months=6 as the Sales POC who owns exactly these 4 invoices',
    expected: 'coverage BOOK; 6 months oldest-first; the current month shows 500.00 over 3 invoices (the 500 cancelled one left out)',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:78',
  }, async () => {
    const res = await get('/api/dashboard/billed-by-month?months=6');
    const m = res.json.months;
    const now = m[m.length - 1];
    const ok = res.status === 200 && res.json.coverage === 'BOOK' && m.length === 6
      && now.month === thisMonth && eq(now.amount, 500) && now.count === 3
      && m.every((x, i) => i === 0 || x.month > m[i - 1].month);
    return { ok, severity: 'high',
      actual: `coverage=${res.json.coverage} months=${m.length} current=${now.month} amount=${now.amount} count=${now.count}` };
  });

  await r.check({
    id: 'F-45', feature: 'Dashboard billed-by-month', kind: 'API',
    title: 'Empty months are present as zero and the window length follows ?months',
    steps: 'GET /api/dashboard/billed-by-month?months=3 and ?months=12',
    expected: '3 and 12 points; every month before this one is 0.00/0 for this brand-new book',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:268',
  }, async () => {
    const a = await get('/api/dashboard/billed-by-month?months=3');
    const b = await get('/api/dashboard/billed-by-month?months=12');
    const past = b.json.months.slice(0, -1);
    return { ok: a.json.months.length === 3 && b.json.months.length === 12
        && past.every((p) => eq(p.amount, 0) && p.count === 0),
      actual: `3 -> ${a.json.months.length} points, 12 -> ${b.json.months.length} points, past months all zero=${past.every((p) => eq(p.amount, 0) && p.count === 0)}` };
  });

  // ---- F-46 collected-by-month ---------------------------------------------------
  await r.check({
    id: 'F-46', feature: 'Dashboard collected-by-month', kind: 'API',
    title: 'collected-by-month reports the money that landed on the book this month',
    steps: 'GET /api/dashboard/collected-by-month?months=6',
    expected: 'coverage BOOK; the current month shows 120.00 over 1 payment',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:158',
  }, async () => {
    const res = await get('/api/dashboard/collected-by-month?months=6');
    const now = res.json.months[res.json.months.length - 1];
    return { ok: res.status === 200 && res.json.coverage === 'BOOK'
        && now.month === thisMonth && eq(now.amount, 120) && now.count === 1,
      severity: 'high',
      actual: `coverage=${res.json.coverage} current=${now.month} amount=${now.amount} count=${now.count}` };
  });

  // ---- F-47 top-outstanding-customers ---------------------------------------------
  await r.check({
    id: 'F-47', feature: 'Dashboard top-outstanding-customers', kind: 'API',
    title: 'top-outstanding-customers ranks by outstanding balance and excludes cancelled invoices',
    steps: 'GET /api/dashboard/top-outstanding-customers?limit=5',
    expected: 'two rows: my first customer 180.00 over 1 still-open invoice (the 100 one was settled, the 500 one cancelled), the second 200.00 over 1; ordered by outstanding descending',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:348',
  }, async () => {
    const res = await get('/api/dashboard/top-outstanding-customers?limit=5');
    const rows = res.json.customers;
    const mine = rows.find((x) => x.customerId === cust.id);
    const theirs = rows.find((x) => x.customerId === other.id);
    const amounts = rows.map((x) => Number(x.outstanding));
    const ordered = amounts.every((v, i) => i === 0 || amounts[i - 1] >= v);
    const ok = res.status === 200 && res.json.coverage === 'BOOK' && rows.length === 2
      && mine && eq(mine.outstanding, 180) && mine.openInvoices === 1
      && theirs && eq(theirs.outstanding, 200) && theirs.openInvoices === 1 && ordered;
    return { ok, severity: 'high',
      actual: `rows=${rows.length} mine=${mine?.outstanding}/${mine?.openInvoices} other=${theirs?.outstanding}/${theirs?.openInvoices} ordered=${ordered}` };
  });

  // ---- F-48 top-paying-customers ---------------------------------------------------
  await r.check({
    id: 'F-48', feature: 'Dashboard top-paying-customers', kind: 'API',
    title: 'top-paying-customers reports what each customer paid into the book',
    steps: 'GET /api/dashboard/top-paying-customers?months=6&limit=5',
    expected: 'one row: my first customer, collected 120.00 over 1 payment, lastPaidAt set',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:170',
  }, async () => {
    const res = await get('/api/dashboard/top-paying-customers?months=6&limit=5');
    const rows = res.json.customers;
    const mine = rows.find((x) => x.customerId === cust.id);
    return { ok: res.status === 200 && rows.length === 1 && mine && eq(mine.collected, 120)
        && mine.payments === 1 && !!mine.lastPaidAt,
      severity: 'high',
      actual: `rows=${rows.length} collected=${mine?.collected} payments=${mine?.payments} lastPaidAt=${mine?.lastPaidAt}` };
  });

  // ---- F-49 self-consistency --------------------------------------------------------
  await r.check({
    id: 'F-49', feature: 'Dashboard consistency', kind: 'API',
    title: 'The dashboard cards agree with the invoice and payment lists for the same scope',
    steps: 'compare top-outstanding totals with GET /api/invoices/summary, and collected-by-month with the allocations on the book',
    expected: 'sum(top-outstanding) = invoice tiles outstanding; billed this month = tiles totalBilled minus the cancelled invoice',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:96',
  }, async () => {
    const top = (await get('/api/dashboard/top-outstanding-customers?limit=20')).json;
    const tiles = (await rt.api('GET', '/api/invoices/summary', { token: spocToken })).json;
    const billed = (await get('/api/dashboard/billed-by-month?months=1')).json.months[0];
    const sumTop = top.customers.reduce((a, x) => a + Number(x.outstanding), 0);
    const ok = eq(sumTop, tiles.outstanding)
      && eq(Number(tiles.totalBilled) - Number(cancelled.total), billed.amount);
    return { ok, severity: 'high',
      actual: `sum(top-outstanding)=${money(sumTop)} vs tiles.outstanding=${tiles.outstanding}; tiles.totalBilled=${tiles.totalBilled} - cancelled ${cancelled.total} = ${money(Number(tiles.totalBilled) - Number(cancelled.total))} vs billed-by-month ${billed.amount}` };
  });

  // ---- F-50 parameter validation ------------------------------------------------------
  await r.check({
    id: 'F-50', feature: 'Dashboard validation', kind: 'API',
    title: 'The dashboard still rejects out-of-range months and limits',
    steps: 'GET billed-by-month?months=0, ?months=25, top-outstanding-customers?limit=0 and ?limit=21',
    expected: '400 on each',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:268',
  }, async () => {
    const a = await get('/api/dashboard/billed-by-month?months=0');
    const b = await get('/api/dashboard/billed-by-month?months=25');
    const c = await get('/api/dashboard/top-outstanding-customers?limit=0');
    const d = await get('/api/dashboard/top-outstanding-customers?limit=21');
    return { ok: [a, b, c, d].every((x) => x.status === 400), severity: 'low',
      actual: `months=0 -> ${a.status}, months=25 -> ${b.status}, limit=0 -> ${c.status}, limit=21 -> ${d.status}` };
  });

  // ---- F-51 scope --------------------------------------------------------------------
  await r.check({
    id: 'F-51', feature: 'Dashboard scope', kind: 'API',
    title: 'A self-service customer sees only their own figures and no customer rankings',
    steps: 'as the customer login: GET billed-by-month, collected-by-month, top-outstanding-customers, top-paying-customers',
    expected: 'the two series answer with coverage OWN and only that customer\'s money; the two rankings are refused',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:253',
  }, async () => {
    const ct = await rt.login(cust.username, cust.password);
    const billed = await rt.api('GET', '/api/dashboard/billed-by-month?months=1', { token: ct });
    const coll = await rt.api('GET', '/api/dashboard/collected-by-month?months=1', { token: ct });
    const topO = await rt.api('GET', '/api/dashboard/top-outstanding-customers', { token: ct });
    const topP = await rt.api('GET', '/api/dashboard/top-paying-customers', { token: ct });
    const ok = billed.json.coverage === 'OWN' && eq(billed.json.months[0].amount, 300)
      && coll.json.coverage === 'OWN' && eq(coll.json.months[0].amount, 120)
      && topO.status === 403 && topP.status === 403;
    return { ok, severity: 'high',
      actual: `billed coverage=${billed.json?.coverage} amount=${billed.json?.months?.[0]?.amount}; collected coverage=${coll.json?.coverage} amount=${coll.json?.months?.[0]?.amount}; rankings=${topO.status}/${topP.status}` };
  });

  // ---- F-97 the collected-scope change did not widen what a POC can see -------------
  await r.check({
    id: 'F-97', feature: 'Dashboard scope', kind: 'API',
    title: 'The payment-scope change on collected-by-month shows a POC only money they can already reach',
    steps: 'a role assignable as both Sales and Collection POC, no SCOPE_OVERRIDE; customer X has their invoice, customer Y their payment, customer Z neither; read collected-by-month and top-paying-customers as them',
    expected: 'X and Y are counted, Z is not; every customer in the ranking also appears in that caller\'s own invoice or payment list',
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:209',
  }, async () => {
    const { roleWith } = require('./_h.js');
    const adminId = (await admin()).id;
    const dual = await roleWith(token, 'f-dual', ['INVOICE_VIEW', 'PAYMENT_VIEW', 'POC_VIEW',
      'NOTIFICATION_VIEW', 'POC_ASSIGNABLE_SALES', 'POC_ASSIGNABLE_COLLECTION']);
    const X = await rt.createCustomer(token, 'f');
    const Y = await rt.createCustomer(token, 'f');
    const Z = await rt.createCustomer(token, 'f');
    const mkI = (c, poc) => createInvoice(token, { customerId: c.id, salesPocUserId: poc,
      items: [{ productId: prod.id, quantity: 1, unitPrice: '100.00' }] });
    const payTo = (c, poc) => recordPayment(token, { customerId: c.id, amount: '100.00',
      method: 'CASH', collectionPocUserId: poc });
    await mkI(X, dual.user.id); await payTo(X, adminId);
    await mkI(Y, adminId);      await payTo(Y, dual.user.id);
    await mkI(Z, adminId);      await payTo(Z, adminId);

    const dt = await rt.login(dual.username, dual.password);
    const coll = await rt.api('GET', '/api/dashboard/collected-by-month?months=1', { token: dt });
    const top = await rt.api('GET', '/api/dashboard/top-paying-customers?months=1&limit=20', { token: dt });
    const invs = await rt.api('GET', '/api/invoices?size=50', { token: dt });
    const pays = await rt.api('GET', '/api/payments?size=50', { token: dt });
    const reachable = new Set([...invs.json.content.map((i) => i.customerId),
      ...pays.json.content.map((p) => p.customerId)]);
    const ranked = top.json.customers.map((c) => c.customerId);
    const ok = eq(coll.json.months[0].amount, 200) && coll.json.months[0].count === 2
      && ranked.includes(X.id) && ranked.includes(Y.id) && !ranked.includes(Z.id)
      && ranked.every((id) => reachable.has(id));
    return { ok, severity: 'high',
      actual: `collected=${coll.json.months[0].amount}/${coll.json.months[0].count}; ranked=${ranked.join(',')} (X=${X.id} Y=${Y.id} Z=${Z.id}); reachable=${[...reachable].join(',')}` };
  });

  r.save();
})();
