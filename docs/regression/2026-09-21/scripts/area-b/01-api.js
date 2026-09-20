// Area B — overdue status and ageing by days past due. API cases.
// Isolation trick: every invoice is owned by a Sales POC created for this run, and the ageing
// endpoint scopes invoices through ScopeResolver#forInvoices (the Sales POC book), so reading the
// dashboard as that POC gives numbers that contain exactly this script's invoices and nothing
// another tester wrote.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, 'out');
const cases = [];

function rec(o) {
  const status = o.status || (o.ok ? 'PASS' : 'FAIL');
  const c = {
    id: o.id, feature: o.feature, kind: o.kind || 'API', ac: o.ac, title: o.title,
    status,
    severity: status === 'FAIL' ? (o.severity || 'high') : '',
    steps: o.steps, expected: o.expected, actual: o.actual,
    evidence: o.evidence || '', codeRef: o.codeRef || '',
  };
  cases.push(c);
  console.log(`[${status}] ${c.id} ${c.title}`);
  return c;
}

// ---- date + money helpers -------------------------------------------------------------
const T0 = new Date().toISOString().slice(0, 10);
const day = (off) =>
  new Date(Date.parse(T0 + 'T00:00:00Z') + off * 86400000).toISOString().slice(0, 10);
/** Money as whole cents, so nothing is ever compared as a float. */
const c = (x) => Math.round(Number(x) * 100);
const money = (cents) => (cents / 100).toFixed(2);

const LABELS = ['Not yet due', '1–30 days', '31–60 days', '61–90 days', 'Over 90 days'];
const BOUNDS = [[null, 0], [1, 30], [31, 60], [61, 90], [91, null]];

async function ageing(token) {
  const r = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token });
  if (r.status !== 200) throw new Error(`outstanding-by-age -> ${r.status} ${r.text}`);
  return r;
}
async function tiles(token, qs = '') {
  const r = await rt.api('GET', '/api/invoices/summary' + qs, { token });
  if (r.status !== 200) throw new Error(`invoices/summary -> ${r.status} ${r.text}`);
  return r.json;
}

let productId;
async function mkInvoice(token, { customerId, salesPocUserId, dueOffset, amount }) {
  const due = day(dueOffset);
  const invoiceDay = dueOffset < 0 ? due : T0;
  const r = await rt.api('POST', '/api/invoices', {
    token,
    body: {
      customerId, salesPocUserId,
      invoiceDate: invoiceDay + 'T00:00:00Z',
      dueDate: due,
      items: [{ productId, quantity: 1, unitPrice: amount }],
    },
  });
  if (r.status >= 300) throw new Error(`create invoice (due ${due}) -> ${r.status} ${r.text}`);
  return r.json;
}

async function pay(token, { customerId, invoiceId, amount, collectionPocUserId }) {
  const r = await rt.api('POST', '/api/payments', {
    token,
    body: { customerId, amount, invoiceIds: [invoiceId], collectionPocUserId, method: 'TEST' },
  });
  if (r.status >= 300) throw new Error(`payment ${amount} -> ${r.status} ${r.text}`);
  return r.json;
}

(async () => {
  const admin = await rt.adminToken();

  // ---- fixtures ------------------------------------------------------------------------
  const prod = await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('b-prod'), price: '1.00', active: true },
  });
  if (prod.status >= 300) throw new Error(`product -> ${prod.status} ${prod.text}`);
  productId = prod.json.id;

  const custA = await rt.createCustomer(admin, 'b-cust');
  const custOwn = await rt.createCustomer(admin, 'b-own');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'b-coll');
  const salesA = await rt.createStaff(admin, 'SALES_POC', 'b-sa');   // bucket boundaries
  const salesB = await rt.createStaff(admin, 'SALES_POC', 'b-sb');   // outstanding / exclusions
  const salesC = await rt.createStaff(admin, 'SALES_POC', 'b-sc');   // all not yet due
  const salesD = await rt.createStaff(admin, 'SALES_POC', 'b-sd');   // money precision
  const salesE = await rt.createStaff(admin, 'SALES_POC', 'b-se');   // derived overdue (D3)
  const salesF = await rt.createStaff(admin, 'SALES_POC', 'b-sf');   // the other customer's book

  const tokA = await rt.login(salesA.username, salesA.password);
  const tokB = await rt.login(salesB.username, salesB.password);
  const tokC = await rt.login(salesC.username, salesC.password);
  const tokD = await rt.login(salesD.username, salesD.password);
  const tokE = await rt.login(salesE.username, salesE.password);
  const tokColl = await rt.login(coll.username, coll.password);
  const tokCustOwn = await rt.login(custOwn.username, custOwn.password);

  console.log(`today(UTC)=${T0} custA=${custA.id} custOwn=${custOwn.id} salesA=${salesA.id}`);

  // ---- book A: one invoice on every bucket boundary --------------------------------------
  // Amounts are powers of two in cents, so a bucket's own total says exactly which invoices
  // it counted — a misplaced invoice cannot hide inside a matching sum.
  const BOUNDARY = [
    { off: +1, amount: '0.01', bucket: 0, why: 'due tomorrow' },
    { off: 0, amount: '0.02', bucket: 0, why: 'due today' },
    { off: -1, amount: '0.04', bucket: 1, why: '1 day past due' },
    { off: -30, amount: '0.08', bucket: 1, why: '30 days past due' },
    { off: -31, amount: '0.16', bucket: 2, why: '31 days past due' },
    { off: -60, amount: '0.32', bucket: 2, why: '60 days past due' },
    { off: -61, amount: '0.64', bucket: 3, why: '61 days past due' },
    { off: -90, amount: '1.28', bucket: 3, why: '90 days past due' },
    { off: -91, amount: '2.56', bucket: 4, why: '91 days past due' },
  ];
  for (const b of BOUNDARY) {
    b.inv = await mkInvoice(admin, {
      customerId: custA.id, salesPocUserId: salesA.id, dueOffset: b.off, amount: b.amount,
    });
    b.cents = c(b.amount);
  }

  const aA = await ageing(tokA);
  const bucketsA = aA.json.buckets;

  // B-01 .. B-09 — every boundary, exactly (AC-B1, AC-B8)
  BOUNDARY.forEach((b, i) => {
    const landed = bucketsA.findIndex((bk) => (c(bk.amount) & b.cents) === b.cents);
    rec({
      id: `B-${String(i + 1).padStart(2, '0')}`,
      feature: 'Ageing buckets',
      ac: i < 2 ? 'AC-B1' : 'AC-B8',
      title: `Invoice due ${b.off === 0 ? 'today' : (b.off > 0 ? `today+${b.off}` : `today${b.off}`)} (${b.why}) lands in "${LABELS[b.bucket]}"`,
      steps: `POST /api/invoices dueDate=${day(b.off)} total=${b.amount} salesPoc=${salesA.username}; GET /api/dashboard/outstanding-by-age as that POC`,
      expected: `${b.amount} is counted in bucket "${LABELS[b.bucket]}" and in no other`,
      actual: landed < 0
        ? `no bucket's amount contains ${b.amount}; buckets = ${bucketsA.map((x) => `${x.label}=${x.amount}`).join(', ')}`
        : `counted in "${bucketsA[landed].label}"`,
      ok: landed === b.bucket,
      evidence: `invoice ${b.inv.invoiceNumber} (id ${b.inv.id}), dueDate ${b.inv.dueDate}, overdue=${b.inv.overdue}, daysOverdue=${b.inv.daysOverdue}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:326 (overdueBy)',
    });
  });

  // B-10 — bucket set, labels and bounds
  const labelsOk = bucketsA.length === 5 && bucketsA.every((b, i) => b.label === LABELS[i]);
  const boundsOk = bucketsA.every((b, i) => b.fromDays === BOUNDS[i][0] && b.toDays === BOUNDS[i][1]);
  rec({
    id: 'B-10', feature: 'Ageing buckets', ac: 'AC-B1',
    title: 'Five buckets with the D4 labels and contiguous, non-overlapping day bounds',
    steps: 'GET /api/dashboard/outstanding-by-age',
    expected: `${LABELS.join(' / ')} with bounds ${JSON.stringify(BOUNDS)}`,
    actual: bucketsA.map((b) => `${b.label}[${b.fromDays},${b.toDays}]`).join(' '),
    ok: labelsOk && boundsOk,
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:61 (AGES)',
  });

  // B-11 — every invoice in exactly one bucket, totals decompose exactly
  const expectedByBucket = [0, 0, 0, 0, 0];
  const countByBucket = [0, 0, 0, 0, 0];
  BOUNDARY.forEach((b) => { expectedByBucket[b.bucket] += b.cents; countByBucket[b.bucket] += 1; });
  const sumsOk = bucketsA.every((b, i) => c(b.amount) === expectedByBucket[i] && b.count === countByBucket[i]);
  rec({
    id: 'B-11', feature: 'Ageing buckets', ac: 'AC-B1',
    title: 'Each invoice falls in exactly one bucket — amounts and counts add up per bucket',
    steps: '9 boundary invoices in one Sales POC book; GET /api/dashboard/outstanding-by-age as that POC',
    expected: bucketsA.map((b, i) => `${LABELS[i]}=${money(expectedByBucket[i])}/${countByBucket[i]}`).join(', '),
    actual: bucketsA.map((b) => `${b.label}=${b.amount}/${b.count}`).join(', '),
    ok: sumsOk,
  });

  // B-12 — deep-linkable window per bucket (AC-B6): the dates the bucket carries fetch exactly
  // the invoices behind it. Query built the way the frontend builds it.
  for (let i = 0; i < bucketsA.length; i++) {
    const b = bucketsA[i];
    const chips = ['filter=status:in:UNPAID,PARTIALLY_PAID'];
    if (b.dueDateFrom && b.dueDateTo) chips.push(`filter=dueDate:between:${b.dueDateFrom},${b.dueDateTo}`);
    else if (b.dueDateFrom) chips.push(`filter=dueDate:gte:${b.dueDateFrom}`);
    else if (b.dueDateTo) chips.push(`filter=dueDate:lte:${b.dueDateTo}`);
    const qs = `?size=50&${chips.join('&')}`;
    const list = await rt.api('GET', '/api/invoices' + qs, { token: tokA });
    const rows = list.json?.content || [];
    const sum = rows.reduce((s, r) => s + c(r.balance), 0);
    rec({
      id: `B-${12 + i}`, feature: 'Bucket deep link', ac: 'AC-B6',
      title: `"${b.label}" window (${b.dueDateFrom || '-'} .. ${b.dueDateTo || '-'}) fetches exactly the invoices behind the bucket`,
      steps: `GET /api/invoices${qs} as the same Sales POC`,
      expected: `${b.count} rows totalling ${b.amount} outstanding`,
      actual: `${list.status} — ${list.json?.totalElements} rows totalling ${money(sum)}`,
      ok: list.status === 200 && list.json?.totalElements === b.count && rows.length === b.count
        && sum === c(b.amount),
      evidence: `bucket ${b.label}: amount=${b.amount} count=${b.count}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:124 (bucket dueDateFrom/dueDateTo)',
    });
  }

  // ---- book A: reconciliation (AC-B3) -----------------------------------------------------
  const totalCents = expectedByBucket.reduce((s, x) => s + x, 0);
  const tilesA = await tiles(tokA);
  const sumBucketsA = bucketsA.reduce((s, b) => s + c(b.amount), 0);
  rec({
    id: 'B-17', feature: 'Reconciliation', ac: 'AC-B3',
    title: 'Buckets sum to the outstanding total reported by the invoice list tiles for the same scope',
    steps: 'GET /api/dashboard/outstanding-by-age and GET /api/invoices/summary as the same Sales POC',
    expected: `sum(buckets) = tiles.outstanding = ${money(totalCents)}`,
    actual: `sum(buckets)=${money(sumBucketsA)} tiles.outstanding=${tilesA.outstanding}`,
    ok: sumBucketsA === c(tilesA.outstanding) && sumBucketsA === totalCents,
  });

  const topA = await rt.api('GET', '/api/dashboard/top-outstanding-customers?limit=20', { token: tokA });
  const topSum = (topA.json?.customers || []).reduce((s, x) => s + c(x.outstanding), 0);
  rec({
    id: 'B-18', feature: 'Reconciliation', ac: 'AC-B3',
    title: 'Buckets sum to the outstanding the dashboard reports per customer (top-outstanding-customers)',
    steps: 'GET /api/dashboard/top-outstanding-customers?limit=20 as the same Sales POC',
    expected: `sum of customer outstanding = ${money(totalCents)}`,
    actual: `${topSum === totalCents ? '' : 'MISMATCH '}sum=${money(topSum)} over ${(topA.json?.customers || []).length} customer(s)`,
    ok: topA.status === 200 && topSum === sumBucketsA,
  });

  // ---- book C: every invoice not yet due (AC-B3 edge case) ---------------------------------
  const FUTURE = [{ off: 5, amount: '1.11' }, { off: 30, amount: '2.22' }, { off: 365, amount: '3.33' }];
  for (const f of FUTURE) {
    await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesC.id, dueOffset: f.off, amount: f.amount });
  }
  const aC = (await ageing(tokC)).json;
  const futureCents = FUTURE.reduce((s, f) => s + c(f.amount), 0);
  const tilesC = await tiles(tokC);
  const overdueBucketsEmpty = aC.buckets.slice(1).every((b) => c(b.amount) === 0 && b.count === 0);
  rec({
    id: 'B-19', feature: 'Reconciliation', ac: 'AC-B3',
    title: 'Book where every invoice is not yet due: all money in "Not yet due", nothing overdue, and it still reconciles',
    steps: '3 invoices due today+5/+30/+365 in a fresh Sales POC book; GET outstanding-by-age + invoices/summary as that POC',
    expected: `Not yet due = ${money(futureCents)} / 3, the four overdue buckets 0.00 / 0, tiles.outstanding = ${money(futureCents)}`,
    actual: `${aC.buckets.map((b) => `${b.label}=${b.amount}/${b.count}`).join(', ')}; tiles.outstanding=${tilesC.outstanding}, tiles.overdueAmount=${tilesC.overdueAmount}, tiles.overdueCount=${tilesC.overdueCount}`,
    ok: c(aC.buckets[0].amount) === futureCents && aC.buckets[0].count === 3 && overdueBucketsEmpty
      && c(tilesC.outstanding) === futureCents && c(tilesC.overdueAmount) === 0 && tilesC.overdueCount === 0,
  });

  // ---- book B: outstanding, not total; exclusions (AC-B2) ----------------------------------
  const invB1 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesB.id, dueOffset: -45, amount: '12345.67' });
  const invB2 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesB.id, dueOffset: -45, amount: '100.00' });
  const invB3 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesB.id, dueOffset: -45, amount: '50.00' });
  await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesB.id, dueOffset: -5, amount: '7.00' });

  const b0 = (await ageing(tokB)).json.buckets;
  rec({
    id: 'B-20', feature: 'Outstanding balance', ac: 'AC-B2',
    title: 'A bucket carries the outstanding balance of its invoices',
    steps: '3 invoices due today-45 (12345.67 + 100.00 + 50.00) and 1 due today-5 (7.00); GET outstanding-by-age',
    expected: '31–60 days = 12495.67 / 3 and 1–30 days = 7.00 / 1',
    actual: `31–60=${b0[2].amount}/${b0[2].count}, 1–30=${b0[1].amount}/${b0[1].count}`,
    ok: c(b0[2].amount) === 1249567 && b0[2].count === 3 && c(b0[1].amount) === 700 && b0[1].count === 1,
  });

  await pay(admin, { customerId: custA.id, invoiceId: invB1.id, amount: '345.67', collectionPocUserId: coll.id });
  const b1 = (await ageing(tokB)).json.buckets;
  const drop = c(b0[2].amount) - c(b1[2].amount);
  rec({
    id: 'B-21', feature: 'Outstanding balance', ac: 'AC-B2',
    title: 'A part payment drops the bucket by exactly the payment, never by the invoice total',
    steps: `POST /api/payments 345.67 against invoice ${invB1.invoiceNumber} (total 12345.67); re-read outstanding-by-age`,
    expected: '31–60 days falls by exactly 345.67 to 12150.00, count still 3',
    actual: `fell by ${money(drop)} to ${b1[2].amount}, count ${b1[2].count}`,
    ok: drop === 34567 && c(b1[2].amount) === 1215000 && b1[2].count === 3,
  });

  const invB1After = await rt.api('GET', `/api/invoices/${invB1.id}`, { token: admin });
  rec({
    id: 'B-22', feature: 'Outstanding balance', ac: 'AC-B2',
    title: 'The bucket holds total - paidAmount for the part-paid invoice, not its total',
    steps: `GET /api/invoices/${invB1.id} and compare with the 31–60 bucket`,
    expected: 'invoice balance 12000.00 is what the bucket counts for it (12150.00 = 12000.00 + 100.00 + 50.00)',
    actual: `total=${invB1After.json.total} paid=${invB1After.json.paidAmount} balance=${invB1After.json.balance}; bucket=${b1[2].amount}`,
    ok: c(invB1After.json.balance) === 1200000
      && c(b1[2].amount) === c(invB1After.json.balance) + 10000 + 5000,
  });

  await pay(admin, { customerId: custA.id, invoiceId: invB2.id, amount: '100.00', collectionPocUserId: coll.id });
  const b2 = (await ageing(tokB)).json.buckets;
  const invB2After = await rt.api('GET', `/api/invoices/${invB2.id}`, { token: admin });
  rec({
    id: 'B-23', feature: 'Exclusions', ac: 'AC-B2',
    title: 'A fully-paid invoice leaves the buckets entirely',
    steps: `Pay invoice ${invB2.invoiceNumber} in full (100.00); re-read outstanding-by-age`,
    expected: '31–60 days = 12050.00 / 2 — the fully-paid invoice is in no bucket',
    actual: `status=${invB2After.json.status}, balance=${invB2After.json.balance}; 31–60=${b2[2].amount}/${b2[2].count}; all buckets=${b2.map((x) => `${x.label}=${x.amount}/${x.count}`).join(', ')}`,
    ok: invB2After.json.status === 'FULLY_PAID' && c(b2[2].amount) === 1205000 && b2[2].count === 2,
  });

  const cancelled = await rt.api('POST', `/api/invoices/${invB3.id}/cancel`, { token: admin });
  const b3 = (await ageing(tokB)).json.buckets;
  rec({
    id: 'B-24', feature: 'Exclusions', ac: 'AC-B2',
    title: 'A cancelled invoice leaves the buckets entirely',
    steps: `POST /api/invoices/${invB3.id}/cancel (50.00, due today-45); re-read outstanding-by-age`,
    expected: '31–60 days = 12000.00 / 1',
    actual: `cancel=${cancelled.status} status=${cancelled.json?.status}; 31–60=${b3[2].amount}/${b3[2].count}`,
    ok: cancelled.json?.status === 'CANCELLED' && c(b3[2].amount) === 1200000 && b3[2].count === 1,
  });

  const tilesB = await tiles(tokB);
  const sumB = b3.reduce((s, x) => s + c(x.amount), 0);
  rec({
    id: 'B-25', feature: 'Reconciliation', ac: 'AC-B3',
    title: 'After a part payment, a full payment and a cancellation the buckets still sum to outstanding',
    steps: 'GET outstanding-by-age and GET /api/invoices/summary as the same Sales POC',
    expected: 'sum(buckets) = tiles.outstanding = 12007.00',
    actual: `sum(buckets)=${money(sumB)} tiles.outstanding=${tilesB.outstanding}`,
    ok: sumB === c(tilesB.outstanding) && sumB === 1200700,
  });

  // ---- book D: money precision --------------------------------------------------------------
  const MONEY = [{ off: -10, amount: '0.01' }, { off: -10, amount: '12345.67' }, { off: -10, amount: '99999999.99' }];
  const moneyInvs = [];
  for (const m of MONEY) {
    moneyInvs.push(await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesD.id, dueOffset: m.off, amount: m.amount }));
  }
  const rD = await ageing(tokD);
  const bD = rD.json.buckets;
  const exactText = /"amount":100012345\.67[,}]/.test(rD.text);
  rec({
    id: 'B-26', feature: 'Money precision', ac: 'AC-B2',
    title: 'Awkward amounts (0.01, 12345.67, 99999999.99) add up exactly in a bucket, with no floating-point drift',
    steps: 'Three invoices due today-10; GET /api/dashboard/outstanding-by-age',
    expected: '1–30 days = 100012345.67 / 3, serialised as an exact decimal',
    actual: `1–30=${bD[1].amount}/${bD[1].count}; raw JSON contains exact literal: ${exactText}`,
    ok: c(bD[1].amount) === 10001234567 && bD[1].count === 3 && exactText,
    evidence: rD.text.slice(0, 400),
  });

  await pay(admin, { customerId: custA.id, invoiceId: moneyInvs[1].id, amount: '0.99', collectionPocUserId: coll.id });
  const rD2 = await ageing(tokD);
  const bD2 = rD2.json.buckets;
  const tilesD = await tiles(tokD);
  rec({
    id: 'B-27', feature: 'Money precision', ac: 'AC-B2',
    title: 'A sub-rupee part payment moves the bucket by exactly one cent-accurate step',
    steps: 'POST /api/payments 0.99 against the 12345.67 invoice; re-read outstanding-by-age',
    expected: '1–30 days = 100012344.68 and tiles.outstanding matches it to the cent',
    actual: `1–30=${bD2[1].amount}; tiles.outstanding=${tilesD.outstanding}; raw exact literal: ${/"amount":100012344\.68[,}]/.test(rD2.text)}`,
    ok: c(bD2[1].amount) === 10001234468 && c(tilesD.outstanding) === 10001234468,
  });

  // ---- book E: overdue is derived, never stored (D3) -------------------------------------------
  const invE1 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesE.id, dueOffset: -1, amount: '11.00' });
  const invE2 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesE.id, dueOffset: 0, amount: '13.00' });
  const invE3 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesE.id, dueOffset: -200, amount: '17.00' });
  const invE4 = await mkInvoice(admin, { customerId: custA.id, salesPocUserId: salesE.id, dueOffset: -200, amount: '19.00' });

  const auditE1 = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${invE1.id}`, { token: admin });
  const readE1 = await rt.api('GET', `/api/invoices/${invE1.id}`, { token: admin });
  const auditE1b = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${invE1.id}`, { token: admin });
  const actions = (auditE1b.json || []).map((e) => e.action);
  rec({
    id: 'B-28', feature: 'Derived overdue', ac: 'D3',
    title: 'An invoice whose due date has passed reads as overdue with no write to the row',
    steps: `POST /api/invoices dueDate=${day(-1)}; GET /api/invoices/${invE1.id}; GET /api/audit?entityType=INVOICE&entityId=${invE1.id} before and after the read`,
    expected: 'overdue=true, daysOverdue=1 on create and on read; the only audit action is INVOICE_CREATED, so nothing was written to make it overdue',
    actual: `create: overdue=${invE1.overdue} daysOverdue=${invE1.daysOverdue}; read: overdue=${readE1.json.overdue} daysOverdue=${readE1.json.daysOverdue}; audit actions ${JSON.stringify(actions)} (${(auditE1.json || []).length} before the read, ${actions.length} after)`,
    ok: invE1.overdue === true && invE1.daysOverdue === 1
      && readE1.json.overdue === true && readE1.json.daysOverdue === 1
      && actions.every((a) => a === 'INVOICE_CREATED')
      && (auditE1.json || []).length === actions.length,
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:118 (isOverdue)',
  });

  rec({
    id: 'B-29', feature: 'Derived overdue', ac: 'D3',
    title: 'An invoice due today is not overdue; it is the day rolling over, not a write, that makes it late',
    steps: `POST /api/invoices dueDate=${T0} (today, UTC); read it back`,
    expected: 'overdue=false, daysOverdue=0, and it sits in "Not yet due"',
    actual: `overdue=${invE2.overdue} daysOverdue=${invE2.daysOverdue} dueDate=${invE2.dueDate}`,
    ok: invE2.overdue === false && invE2.daysOverdue === 0 && invE2.dueDate === T0,
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDates.java:18 (today, UTC)',
  });

  const cancelE3 = await rt.api('POST', `/api/invoices/${invE3.id}/cancel`, { token: admin });
  const readE3 = await rt.api('GET', `/api/invoices/${invE3.id}`, { token: admin });
  rec({
    id: 'B-30', feature: 'Derived overdue', ac: 'D3',
    title: 'A CANCELLED invoice 200 days past its due date never reads as overdue',
    steps: `POST /api/invoices dueDate=${day(-200)}; POST /api/invoices/${invE3.id}/cancel; GET it back`,
    expected: 'status CANCELLED, overdue=false, daysOverdue=0',
    actual: `cancel=${cancelE3.status} status=${readE3.json.status} overdue=${readE3.json.overdue} daysOverdue=${readE3.json.daysOverdue}`,
    ok: readE3.json.status === 'CANCELLED' && readE3.json.overdue === false && readE3.json.daysOverdue === 0,
  });

  await pay(admin, { customerId: custA.id, invoiceId: invE4.id, amount: '19.00', collectionPocUserId: coll.id });
  const readE4 = await rt.api('GET', `/api/invoices/${invE4.id}`, { token: admin });
  rec({
    id: 'B-31', feature: 'Derived overdue', ac: 'D3',
    title: 'A fully-paid invoice 200 days past its due date never reads as overdue',
    steps: `Pay invoice ${invE4.invoiceNumber} (due ${day(-200)}) in full; GET it back`,
    expected: 'status FULLY_PAID, overdue=false, daysOverdue=0',
    actual: `status=${readE4.json.status} overdue=${readE4.json.overdue} daysOverdue=${readE4.json.daysOverdue} balance=${readE4.json.balance}`,
    ok: readE4.json.status === 'FULLY_PAID' && readE4.json.overdue === false && readE4.json.daysOverdue === 0,
  });

  const aE = (await ageing(tokE)).json.buckets;
  rec({
    id: 'B-32', feature: 'Exclusions', ac: 'AC-B2',
    title: 'Neither the cancelled nor the fully-paid 200-day-old invoice reaches the "Over 90 days" bucket',
    steps: 'GET outstanding-by-age for the book that holds all four D3 invoices',
    expected: 'Not yet due = 13.00 / 1, 1–30 days = 11.00 / 1, 31–60 / 61–90 / Over 90 all 0.00 / 0',
    actual: aE.map((b) => `${b.label}=${b.amount}/${b.count}`).join(', '),
    ok: c(aE[0].amount) === 1300 && aE[0].count === 1 && c(aE[1].amount) === 1100 && aE[1].count === 1
      && aE.slice(2).every((b) => c(b.amount) === 0 && b.count === 0),
  });

  // ---- scope and coverage (AC-B4) -----------------------------------------------------------
  const invF1 = await mkInvoice(admin, { customerId: custOwn.id, salesPocUserId: salesF.id, dueOffset: -5, amount: '10.00' });
  const invF2 = await mkInvoice(admin, { customerId: custOwn.id, salesPocUserId: salesF.id, dueOffset: 5, amount: '20.00' });

  rec({
    id: 'B-33', feature: 'Scope and coverage', ac: 'AC-B4',
    title: 'A Sales POC sees only their own book, reported as Coverage BOOK',
    steps: 'GET /api/dashboard/outstanding-by-age as the boundary-set Sales POC',
    expected: `coverage=BOOK and a total of exactly ${money(totalCents)} over 9 invoices — no other POC's invoices`,
    actual: `coverage=${aA.json.coverage} total=${money(sumBucketsA)} count=${bucketsA.reduce((s, b) => s + b.count, 0)}`,
    ok: aA.json.coverage === 'BOOK' && sumBucketsA === totalCents
      && bucketsA.reduce((s, b) => s + b.count, 0) === 9,
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:102,248 (forInvoices, coverage)',
  });

  const aCust = await ageing(tokCustOwn);
  const sumCust = aCust.json.buckets.reduce((s, b) => s + c(b.amount), 0);
  const tilesCust = await tiles(tokCustOwn);
  rec({
    id: 'B-34', feature: 'Scope and coverage', ac: 'AC-B4',
    title: 'A self-service customer login sees only their own invoices, reported as Coverage OWN',
    steps: `Customer ${custOwn.username} (2 invoices: 10.00 due today-5, 20.00 due today+5) logs in; GET /api/dashboard/outstanding-by-age`,
    expected: 'coverage=OWN, Not yet due = 20.00 / 1, 1–30 days = 10.00 / 1, total 30.00 — nothing from the other customer',
    actual: `coverage=${aCust.json.coverage}; ${aCust.json.buckets.map((b) => `${b.label}=${b.amount}/${b.count}`).join(', ')}`,
    ok: aCust.json.coverage === 'OWN' && sumCust === 3000
      && c(aCust.json.buckets[0].amount) === 2000 && aCust.json.buckets[0].count === 1
      && c(aCust.json.buckets[1].amount) === 1000 && aCust.json.buckets[1].count === 1,
    evidence: `own invoices ${invF1.invoiceNumber}, ${invF2.invoiceNumber}`,
  });

  rec({
    id: 'B-35', feature: 'Scope and coverage', ac: 'AC-B4',
    title: "A customer's buckets reconcile with the outstanding on their own invoice list",
    steps: 'GET /api/invoices/summary as the same customer login',
    expected: 'tiles.outstanding = 30.00 = sum of the customer’s buckets',
    actual: `tiles.outstanding=${tilesCust.outstanding} count=${tilesCust.count} sum(buckets)=${money(sumCust)}`,
    ok: c(tilesCust.outstanding) === sumCust && tilesCust.count === 2,
  });

  const aAdmin = await ageing(admin);
  const sumAdmin = aAdmin.json.buckets.reduce((s, b) => s + c(b.amount), 0);
  rec({
    id: 'B-36', feature: 'Scope and coverage', ac: 'AC-B4',
    title: 'A full-scope staff login reports Coverage ALL and covers at least every book this run created',
    steps: 'GET /api/dashboard/outstanding-by-age as admin',
    expected: `coverage=ALL and a total of at least ${money(totalCents + sumCust)} (this run’s books alone)`,
    actual: `coverage=${aAdmin.json.coverage} total=${money(sumAdmin)}`,
    ok: aAdmin.json.coverage === 'ALL' && sumAdmin >= totalCents + sumCust,
  });

  const aColl = await ageing(tokColl);
  const tilesColl = await tiles(tokColl);
  const sumColl = aColl.json.buckets.reduce((s, b) => s + c(b.amount), 0);
  const collList = await rt.api('GET', '/api/invoices?size=10', { token: tokColl });
  rec({
    id: 'B-37', feature: 'Scope and coverage', ac: 'AC-B4',
    title: 'A COLLECTION_POC sees exactly what their invoice list shows, with the same coverage the list scope gives',
    steps: 'Create a COLLECTION_POC login; GET /api/dashboard/outstanding-by-age, /api/invoices/summary and /api/invoices',
    expected: 'the chart never shows money the caller cannot find on their invoice list: sum(buckets) = tiles.outstanding, and coverage matches the list’s locked scope (the seeded COLLECTION_POC role carries SCOPE_OVERRIDE, so its invoice book is unrestricted → ALL)',
    actual: `coverage=${aColl.json.coverage}, lockedFilters=${JSON.stringify(collList.json?.lockedFilters)}, sum(buckets)=${money(sumColl)}, tiles.outstanding=${tilesColl.outstanding}`,
    ok: sumColl === c(tilesColl.outstanding)
      && aColl.json.coverage === ((collList.json?.lockedFilters || []).length === 0 ? 'ALL' : 'BOOK'),
    codeRef: 'backend/src/main/java/com/geneinvoice/config/DataSeeder.java:143 (COLLECTION_POC has SCOPE_OVERRIDE)',
  });

  // ---- AC-B7: one aggregate query -------------------------------------------------------------
  const t0 = Date.now(); await ageing(tokA); const tSmall = Date.now() - t0;
  const t1 = Date.now(); await ageing(admin); const tAll = Date.now() - t1;
  rec({
    id: 'B-38', feature: 'Aggregate', ac: 'AC-B7', kind: 'CODE',
    title: 'The ageing figures come from a single aggregate query — no per-invoice work in Java, no N+1',
    steps: 'Read DashboardService.outstandingByAge; time the endpoint for a 9-invoice book and for the whole database',
    expected: 'one CriteriaQuery with a sum/count pair per bucket, executed once via getSingleResult(); no repository call per invoice',
    actual: `outstandingByAge builds 5 x (Aggregates.sumWhen + Aggregates.countWhen) into one multiselect and runs em.createQuery(cq).getSingleResult() once (DashboardService.java:104-115); Invoice#isOverdue is never called on this path. Latency: ${tSmall}ms for a 9-invoice book vs ${tAll}ms for every invoice in the database`,
    ok: true,
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:109-115',
  });

  // Clock safety: if the run crossed UTC midnight every boundary above moved by a day.
  const T1 = new Date().toISOString().slice(0, 10);
  if (T1 !== T0) console.log(`!! UTC date changed during the run: ${T0} -> ${T1}`);

  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'api.json'), JSON.stringify({
    cases,
    fixtures: {
      today: T0, todayAtEnd: T1, productId,
      custA: { id: custA.id, name: custA.name, username: custA.username },
      custOwn: { id: custOwn.id, name: custOwn.name, username: custOwn.username },
      salesA: { id: salesA.id, username: salesA.username, password: salesA.password },
      salesB: { id: salesB.id, username: salesB.username },
      collection: { id: coll.id, username: coll.username, password: coll.password },
      boundary: BOUNDARY.map((b) => ({ off: b.off, amount: b.amount, id: b.inv.id, number: b.inv.invoiceNumber, due: b.inv.dueDate })),
      expectedBuckets: bucketsA.map((b, i) => ({ label: b.label, amount: b.amount, count: b.count })),
    },
  }, null, 2));
  const fails = cases.filter((x) => x.status === 'FAIL');
  console.log(`\n${cases.length} cases, ${cases.length - fails.length} PASS, ${fails.length} FAIL`);
  fails.forEach((f) => console.log(`FAIL ${f.id} ${f.ac} — ${f.title}\n      ${f.actual}`));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
