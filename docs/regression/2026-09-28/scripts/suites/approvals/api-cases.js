// API cases for B2 maker-checker. Every case builds its own customers so a re-run (for a FAIL) starts clean.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, invoiceBy, paymentBy, promiseBy, invoicesOf, paymentsOf, promisesOf, notifications, brief, pick } = H;

const CODE = {
  gate: 'backend/src/main/java/com/geneinvoice/approval/MakerChecker.java:49',
  hold: 'backend/src/main/java/com/geneinvoice/approval/MakerChecker.java:97',
  svc: 'backend/src/main/java/com/geneinvoice/approval/ApprovalService.java:242',
  reject: 'backend/src/main/java/com/geneinvoice/approval/ApprovalService.java:315',
  withdraw: 'backend/src/main/java/com/geneinvoice/approval/ApprovalService.java:338',
  scope: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java',
  views: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java',
  thr: 'backend/src/main/java/com/geneinvoice/approval/ApprovalThresholdService.java:121',
  notif: 'backend/src/main/java/com/geneinvoice/approval/ApprovalNotifier.java',
  dispute: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:279',
};

module.exports = async function apiCases(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  const U = w.users;
  const SOUTH = w.regions.SOUTH.id;
  const NORTH = w.regions.NORTH.id;
  const WEST = w.regions.WEST.id;
  const s1 = { ...w.customers.s1, pocId: U.sam.id };
  // Fixture maker per attempt, so a retry never trips the cumulative window on shared customers (s1).
  const fx = () => w.nextFx();
  const heldOk = (c, r, op) => {
    c.that(`202 PENDING_APPROVAL (${op})`, r.status === 202 && r.json?.status === 'PENDING_APPROVAL', brief(r));
    c.that('no top-level id in a held reply', r.json && r.json.id === undefined, r.json && Object.keys(r.json));
    c.that('Location /api/approvals/{pendingChange.id}', r.pendingChange && r.headers.location === `/api/approvals/${r.pendingChange.id}`, r.headers.location);
    c.that(`pendingChange.operation ${op}`, r.pendingChange?.operation === op, r.pendingChange?.operation);
  };
  const cancelIfPending = async (id, maker) => { if (id) await rt.approvals.withdraw(maker, id); };

  // ============================== Setup sanity ==============================
  await tcase({ id: 'APP-SET-01', feature: 'Per-region thresholds', kind: 'API', prd: 'B2',
    title: 'Tightening thresholds (new rows) applies at once with 200 in South and North',
    steps: 'admin PUT /api/regions/{South}/approval-thresholds [INVOICE_TERMS, PROMISE_UPDATE, PROMISE_OVERRIDE, DISPUTE_APPROVE = 1000]; PUT North [INVOICE_CREATE 50000]',
    expected: 'DESIGN §6.11: tightening rows apply at once -> 200, no pendingChange; GET shows the new amounts' }, async () => {
    const c = checks();
    c.that('South PUT 200', w.southSetup.status === 200, brief(w.southSetup));
    c.that('North PUT 200', w.northSetup.status === 200, brief(w.northSetup));
    const g = await rt.getThresholds(T.admin, SOUTH);
    const row = (op) => g.json?.thresholds?.find((t) => t.operation === op);
    for (const op of ['INVOICE_TERMS', 'PROMISE_UPDATE', 'PROMISE_OVERRIDE', 'DISPUTE_APPROVE']) c.that(`South ${op} = 1000`, Number(row(op)?.amount) === 1000, row(op));
    c.that('no pendingChange', g.json?.pendingChange == null, g.json?.pendingChange);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  // ============================== Gated operations ==============================
  await tcase({ id: 'APP-G-01', feature: 'Held invoice create', kind: 'API', prd: 'B2',
    title: 'An invoice above South\'s INVOICE_CREATE limit is held (202) and not created',
    steps: 'fresh South customer; mia (MANAGE South) POST /api/invoices amount 12000 (limit 10000)',
    expected: 'DESIGN §6.6: 202 {status:PENDING_APPROVAL, message, pendingChange{operation INVOICE_CREATE, entityId null, amount 12000, threshold 10000}}, Location /api/approvals/{id}, no top-level id; the customer has no invoice' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    heldOk(c, r, 'INVOICE_CREATE');
    c.that('entityId null for a create', r.pendingChange && r.pendingChange.entityId === null, r.pendingChange?.entityId);
    c.that('amount 12000 / threshold 10000', Number(r.pendingChange?.amount) === 12000 && Number(r.pendingChange?.threshold) === 10000, pick(r.pendingChange, ['amount', 'threshold']));
    c.that('message names South and the limit', /South/.test(r.json?.message || '') && /10,000/.test(r.json?.message || ''), r.json?.message);
    c.that('approverCount 2 (admin, sam; the maker excluded)', r.pendingChange?.approverCount === 2, r.pendingChange?.approverCount);
    c.that('canWithdraw true for the maker', r.pendingChange?.canWithdraw === true, r.pendingChange?.canWithdraw);
    const invs = await invoicesOf(T.admin, cu.id);
    c.that('no invoice created', invs.length === 0, invs.map((i) => i.invoiceNumber));
    return { ok: c.ok, actual: c.summary(), evidence: `POST /api/invoices -> ${r.status} ${JSON.stringify(r.pendingChange)?.slice(0, 300)}`, codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-02', feature: 'At/below threshold applies', kind: 'API', prd: 'B2',
    title: 'An invoice exactly at the limit (10000) and one below it apply immediately (200)',
    steps: 'fresh South customers A, B; mia POST /api/invoices 10000.00 on A, 9999.99 on B',
    expected: 'DESIGN §5.11.1 "Above means amount > threshold": both 200 with the InvoiceDto (id, invoiceNumber)' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const r1 = await rt.createInvoice(T.mia, { customerId: a.id, amount: 10000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const r2 = await rt.createInvoice(T.mia, { customerId: b.id, amount: 9999.99, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('10000 -> 200 with id', r1.status === 200 && r1.json?.id > 0, brief(r1));
    c.that('9999.99 -> 200 with id', r2.status === 200 && r2.json?.id > 0, brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-03', feature: 'Held invoice cancel', kind: 'API', prd: 'B2',
    title: 'Cancelling an invoice whose total is above INVOICE_CANCEL (5000) is held; the invoice stays UNPAID',
    steps: 'admin creates an 8000 invoice on a fresh South customer; mia POST /api/invoices/{id}/cancel',
    expected: 'DESIGN §6.6: 202 pendingChange{operation INVOICE_CANCEL, entityId = invoice id}; GET invoice status still UNPAID' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const r = await rt.cancelInvoice(T.mia, inv.id);
    heldOk(c, r, 'INVOICE_CANCEL');
    c.that('entityId = invoice id', r.pendingChange?.entityId === inv.id, r.pendingChange?.entityId);
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('invoice still UNPAID', g.json?.status === 'UNPAID', g.json?.status);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-04', feature: 'At/below threshold applies', kind: 'API', prd: 'B2',
    title: 'Cancelling an invoice of exactly 5000 (= INVOICE_CANCEL) applies at once',
    steps: 'admin creates a 5000 invoice; mia POST /api/invoices/{id}/cancel', expected: '200, status CANCELLED' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 5000);
    const r = await rt.cancelInvoice(T.mia, inv.id);
    c.that('200 CANCELLED', r.status === 200 && r.json?.status === 'CANCELLED', brief(r));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-05', feature: 'Held invoice terms change', kind: 'API', prd: 'B2',
    title: 'Moving a due date later on a balance above INVOICE_TERMS is held; moving it earlier or editing notes applies',
    steps: 'invoices X, Y of 2500 due +20d; mia PATCH X {dueDate:+60d, paymentTerm:CUSTOM}; mia PATCH Y {dueDate:+10d}; mia PATCH Y {notes}',
    expected: 'DESIGN §5.11.1 INVOICE_TERMS: X -> 202 (amount 2500) and dueDate unchanged; Y earlier -> 200 applied (measure 0); notes-only -> 200 (not gated)' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const x = await invoiceBy(T.admin, cu, 2500);
    const y = await invoiceBy(T.admin, cu, 2500);
    const r = await rt.patchInvoice(T.mia, x.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    heldOk(c, r, 'INVOICE_TERMS');
    c.that('amount = balance 2500', Number(r.pendingChange?.amount) === 2500, r.pendingChange?.amount);
    const gx = await rt.api('GET', `/api/invoices/${x.id}`, { token: T.admin });
    c.that('X dueDate unchanged', gx.json?.dueDate === x.dueDate, `${gx.json?.dueDate} vs ${x.dueDate}`);
    const r2 = await rt.patchInvoice(T.mia, y.id, { dueDate: rt.day(10), paymentTerm: 'CUSTOM' });
    c.that('Y earlier due -> 200 applied', r2.status === 200 && r2.json?.dueDate === rt.day(10), brief(r2));
    const r3 = await rt.patchInvoice(T.mia, y.id, { notes: 'e2e note only' });
    c.that('notes-only -> 200', r3.status === 200 && r3.json?.notes === 'e2e note only', brief(r3));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-06', feature: 'Held payment', kind: 'API', prd: 'B2',
    title: 'A payment above PAYMENT_RECORD (5000) is held and allocates nothing; one below applies',
    steps: 'invoice 8000 on fresh South customer A; mia POST /api/payments {amount 6000, invoiceIds:[inv]}; fresh customer B: mia POST 4000',
    expected: 'DESIGN §6.6: A -> 202 PAYMENT_RECORD, no payment row, invoice paidAmount 0; B -> 200 PaymentDto' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const inv = await invoiceBy(T.admin, a, 8000);
    const r = await rt.createPayment(T.mia, { customerId: a.id, amount: 6000, invoiceIds: [inv.id], collectionPocUserId: U.sam.id });
    heldOk(c, r, 'PAYMENT_RECORD');
    const pays = await paymentsOf(T.admin, a.id);
    c.that('no payment row for A', pays.length === 0, pays.map((p) => p.id));
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('invoice paidAmount still 0', Number(g.json?.paidAmount) === 0, g.json?.paidAmount);
    const r2 = await rt.createPayment(T.mia, { customerId: b.id, amount: 4000, collectionPocUserId: U.sam.id });
    c.that('4000 -> 200', r2.status === 200 && r2.json?.id > 0, brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-07', feature: 'Held promise create', kind: 'API', prd: 'B2',
    title: 'A promise above PROMISE_CREATE (5000) is held; one below applies',
    steps: 'fresh South customers A, B; mia POST /api/promises 6000 on A, 3000 on B',
    expected: 'A -> 202 PROMISE_CREATE and no promise row; B -> 200 PromiseDto' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const r = await rt.createPromise(T.mia, { customerId: a.id, amount: 6000, collectionPocUserId: U.sam.id });
    heldOk(c, r, 'PROMISE_CREATE');
    const ps = await promisesOf(T.admin, a.id);
    c.that('no promise for A', ps.length === 0, ps.map((p) => p.id));
    const r2 = await rt.createPromise(T.mia, { customerId: b.id, amount: 3000, collectionPocUserId: U.sam.id });
    c.that('3000 -> 200', r2.status === 200 && r2.json?.id > 0, brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-08', feature: 'Held promise update', kind: 'API', prd: 'B2',
    title: 'Changing a promise by more than PROMISE_UPDATE (1000) is held; a smaller change applies',
    steps: 'admin promise 3000 on A and on B; mia PUT /api/promises/{A} amount 4500 (delta 1500); mia PUT /api/promises/{B} amount 3500 (delta 500)',
    expected: 'DESIGN §5.11.1 PROMISE_UPDATE measure |new-old|: A -> 202, amount stays 3000; B -> 200, amount 3500' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const pa = await promiseBy(T.admin, a, 3000); const pb = await promiseBy(T.admin, b, 3000);
    const body = (p, amount) => ({ amount, promisedDate: p.promisedDate, collectionPocUserId: p.collectionPocUserId ?? U.sam.id, notes: p.notes ?? null, invoiceIds: null });
    const r = rt.heldAware(await rt.api('PUT', `/api/promises/${pa.id}`, { token: T.mia, body: body(pa, 4500) }));
    heldOk(c, r, 'PROMISE_UPDATE');
    c.that('measure 1500', Number(r.pendingChange?.amount) === 1500, r.pendingChange?.amount);
    const g = await rt.api('GET', `/api/promises/${pa.id}`, { token: T.admin });
    c.that('A amount still 3000', Number(g.json?.amount) === 3000, g.json?.amount);
    const r2 = await rt.api('PUT', `/api/promises/${pb.id}`, { token: T.mia, body: body(pb, 3500) });
    c.that('B -> 200 amount 3500', r2.status === 200 && Number(r2.json?.amount) === 3500, brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-09', feature: 'Held promise override', kind: 'API', prd: 'B2',
    title: 'Overriding a 3000 promise to KEPT is held (unfulfilled > 1000); overriding to BROKEN applies (measure 0)',
    steps: 'admin promise 3000 on A and on B; mia POST /api/promises/{A}/override {status:KEPT}; mia POST /api/promises/{B}/override {status:BROKEN}',
    expected: 'DESIGN §5.11.1 PROMISE_OVERRIDE: A -> 202, status unchanged (OPEN, not overridden); B -> 200 status BROKEN' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const pa = await promiseBy(T.admin, a, 3000); const pb = await promiseBy(T.admin, b, 3000);
    const r = rt.heldAware(await rt.api('POST', `/api/promises/${pa.id}/override`, { token: T.mia, body: { status: 'KEPT', reason: 'e2e override' } }));
    heldOk(c, r, 'PROMISE_OVERRIDE');
    const g = await rt.api('GET', `/api/promises/${pa.id}`, { token: T.admin });
    c.that('A status unchanged', g.json?.status === pa.status && !g.json?.statusOverridden, pick(g.json, ['status', 'statusOverridden']));
    const r2 = await rt.api('POST', `/api/promises/${pb.id}/override`, { token: T.mia, body: { status: 'BROKEN', reason: 'e2e broken' } });
    c.that('B -> 200 BROKEN', r2.status === 200 && r2.json?.status === 'BROKEN', brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  // Disputes: customer login 'cust' (s1, South) opens them.
  const openDispute = async (targetType, targetId, change) => {
    const r = await rt.createDispute(T.cust, { targetType, targetId, reason: 'e2e dispute', proposedChangeJson: JSON.stringify(change) });
    return rt.need(r, `open dispute on ${targetType} ${targetId}`);
  };

  await tcase({ id: 'APP-G-10', feature: 'Held dispute approval', kind: 'API', prd: 'B2',
    title: 'Approving a dispute that cancels a 1500 invoice (> DISPUTE_APPROVE 1000) by a non-approver is held; a second person\'s approval applies it',
    steps: 'invoice 1500 on s1; cust opens dispute {action:cancel}; mia POST /api/disputes/{id}/approve; sam POST /api/approvals/{pc}/approve',
    expected: 'DESIGN §5.11.8: 202 DISPUTE_APPROVE, dispute stays PENDING, invoice UNPAID; after sam approves: APPROVED, invoice CANCELLED, dispute APPROVED' }, async (attempt) => {
    const c = checks();
    const inv = await invoiceBy(fx(attempt), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    heldOk(c, r, 'DISPUTE_APPROVE');
    const gd = await rt.api('GET', `/api/disputes/${d.id}`, { token: T.admin });
    const gi = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('dispute still PENDING', gd.json?.status === 'PENDING', gd.json?.status);
    c.that('invoice still UNPAID', gi.json?.status === 'UNPAID', gi.json?.status);
    if (r.pendingChange) {
      const a = await rt.approvals.approve(T.sam, r.pendingChange.id, 'ok');
      c.that('sam approve -> 200 APPROVED', a.status === 200 && a.json?.status === 'APPROVED', brief(a));
      const gi2 = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
      const gd2 = await rt.api('GET', `/api/disputes/${d.id}`, { token: T.admin });
      c.that('invoice CANCELLED after approval', gi2.json?.status === 'CANCELLED', gi2.json?.status);
      c.that('dispute APPROVED after approval', gd2.json?.status === 'APPROVED', gd2.json?.status);
    }
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-11', feature: 'Dispute approval is the checker step', kind: 'API', prd: 'B2',
    title: 'A South approver approving the customer\'s own proposal as proposed applies at once (the customer was the maker)',
    steps: 'invoice 1500 on s1; cust opens dispute {action:cancel}; sam (APPROVE South, did not set cust\'s password) POST /api/disputes/{id}/approve {}',
    expected: 'DESIGN §5.11.8: 200, dispute APPROVED, invoice CANCELLED (no pending change)' }, async (attempt) => {
    const c = checks();
    const inv = await invoiceBy(fx(attempt), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const r = await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.sam, body: {} });
    c.that('200 applied', r.status === 200 && r.json?.status === 'APPROVED', brief(r));
    const gi = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('invoice CANCELLED', gi.json?.status === 'CANCELLED', gi.json?.status);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-12', feature: 'Dispute approval is the checker step', kind: 'API', prd: 'B2',
    title: 'The staff member who set the customer login\'s password cannot be the checker step: admin\'s approval is held',
    steps: 'invoice 1500 on s1 (admin created cust with its password); cust opens dispute {action:cancel}; admin POST /api/disputes/{id}/approve {}',
    expected: 'DESIGN §5.11.8 (SC-10): 202 held with maker = admin; invoice UNPAID' }, async (attempt) => {
    const c = checks();
    const inv = await invoiceBy(fx(attempt), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.admin, body: {} }));
    heldOk(c, r, 'DISPUTE_APPROVE');
    c.that('maker = admin', r.pendingChange?.requestedBy?.id === U.admin.id, r.pendingChange?.requestedBy);
    const gi = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('invoice UNPAID', gi.json?.status === 'UNPAID', gi.json?.status);
    if (r.pendingChange) await rt.approvals.withdraw(T.admin, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-13', feature: 'Held payment void', kind: 'API', prd: 'B2',
    title: 'Voiding a 2000 payment through a dispute is held for a non-approver; approval voids it',
    steps: 'payment 2000 on s1; cust opens dispute on the PAYMENT {action:void}; mia POST /api/disputes/{id}/approve; admin approves the pending change',
    expected: 'DESIGN §5.11.1 DISPUTE_APPROVE void measure = amount: 202; payment ACTIVE until approved; then VOIDED' }, async (attempt) => {
    const c = checks();
    const p = await paymentBy(fx(attempt), s1, 2000);
    const d = await openDispute('PAYMENT', p.id, { action: 'void' });
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    heldOk(c, r, 'DISPUTE_APPROVE');
    c.that('amount 2000', Number(r.pendingChange?.amount) === 2000, r.pendingChange?.amount);
    const g = await rt.api('GET', `/api/payments/${p.id}`, { token: T.admin });
    c.that('payment still ACTIVE', g.json?.status === 'ACTIVE', g.json?.status);
    if (r.pendingChange) {
      const a = await rt.approvals.approve(T.admin, r.pendingChange.id);
      c.that('admin approve -> APPROVED', a.json?.status === 'APPROVED', brief(a));
      const g2 = await rt.api('GET', `/api/payments/${p.id}`, { token: T.admin });
      c.that('payment VOIDED after approval', g2.json?.status === 'VOIDED', g2.json?.status);
    }
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-14', feature: 'Payment amount change below threshold', kind: 'API', prd: 'B2',
    title: 'Changing a payment amount by 500 through a dispute (<= 1000) applies at once',
    steps: 'payment 2000 on s1; cust dispute {action:update_amount, amount:1500}; mia approve',
    expected: 'DESIGN §5.11.1 update_amount measure |delta| = 500 -> 200; payment amount 1500' }, async (attempt) => {
    const c = checks();
    const p = await paymentBy(fx(attempt), s1, 2000);
    const d = await openDispute('PAYMENT', p.id, { action: 'update_amount', amount: 1500 });
    const r = await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} });
    c.that('200 APPROVED', r.status === 200 && r.json?.status === 'APPROVED', brief(r));
    const g = await rt.api('GET', `/api/payments/${p.id}`, { token: T.admin });
    c.that('payment amount 1500', Number(g.json?.amount) === 1500, g.json?.amount);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-15', feature: 'Held bulk cancel', kind: 'API', prd: 'B2',
    title: 'Bulk CANCEL of two 6000 invoices reports both under pending and cancels neither',
    steps: 'invoices of 6000 on two fresh South customers; mia POST /api/invoices/bulk {action:CANCEL, ids:[a,b]}',
    expected: 'DESIGN §6.7: 200 BulkResult with pending:[{id, pendingChangeId, summary}] x2; both invoices UNPAID' }, async () => {
    const c = checks();
    const a = await customerIn(w); const b = await customerIn(w);
    const ia = await invoiceBy(T.admin, a, 6000); const ib = await invoiceBy(T.admin, b, 6000);
    const r = await rt.api('POST', '/api/invoices/bulk', { token: T.mia, body: { action: 'CANCEL', ids: [ia.id, ib.id] } });
    const pend = r.json?.pending || [];
    c.that('200', r.status === 200, brief(r));
    c.that('pending has both ids with pendingChangeId', pend.length === 2 && pend.every((p) => p.pendingChangeId > 0) && pend.map((p) => p.id).sort().join() === [ia.id, ib.id].sort().join(), pend);
    const ga = await rt.api('GET', `/api/invoices/${ia.id}`, { token: T.admin });
    const gb = await rt.api('GET', `/api/invoices/${ib.id}`, { token: T.admin });
    c.that('both still UNPAID', ga.json?.status === 'UNPAID' && gb.json?.status === 'UNPAID', [ga.json?.status, gb.json?.status]);
    for (const p of pend) await rt.approvals.withdraw(T.mia, p.pendingChangeId);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:119' };
  });

  await tcase({ id: 'APP-G-16', feature: 'Held promise update', kind: 'API', prd: 'B2',
    title: 'Pushing a 3000 promise\'s date later without changing the amount is held (measure = new amount)',
    steps: 'admin promise 3000 due +10d; mia PUT same amount, promisedDate +40d',
    expected: 'DESIGN §5.11.1 PROMISE_UPDATE measure max(|new-old|, newDate > oldDate ? newAmount : 0) = 3000 > 1000 -> 202; promisedDate unchanged' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const r = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: { amount: 3000, promisedDate: rt.day(40), collectionPocUserId: p.collectionPocUserId ?? U.sam.id, notes: null, invoiceIds: null } }));
    heldOk(c, r, 'PROMISE_UPDATE');
    c.that('measure 3000', Number(r.pendingChange?.amount) === 3000, r.pendingChange?.amount);
    const g = await rt.api('GET', `/api/promises/${p.id}`, { token: T.admin });
    c.that('promisedDate unchanged', g.json?.promisedDate === p.promisedDate, `${g.json?.promisedDate} vs ${p.promisedDate}`);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-17', feature: 'Held invoice terms change', kind: 'API', prd: 'B2',
    title: 'Changing the payment term to NET_90 (resolved due date later) on a 2500 balance is held',
    steps: 'admin invoice 2500 due +20d (CUSTOM); mia PATCH {paymentTerm: NET_90}',
    expected: 'DESIGN §5.11.1 INVOICE_TERMS gates when the resolved due date or term differs and moves later: 202; paymentTerm/dueDate unchanged' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    const r = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    heldOk(c, r, 'INVOICE_TERMS');
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('term and due date unchanged', g.json?.paymentTerm === inv.paymentTerm && g.json?.dueDate === inv.dueDate, pick(g.json, ['paymentTerm', 'dueDate']));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-G-18', feature: 'Held dispute approval', kind: 'API', prd: 'B2',
    title: 'An approver who rewrites the customer\'s proposal is not the checker step: sam\'s replace_items (moves 1400) is held with maker sam, and admin\'s approval applies it',
    steps: 'invoice 1500 on s1; cust disputes {action:cancel}; sam POST /api/disputes/{id}/approve {appliedChangeJson:{action:replace_items, items:[{productId, quantity:1, unitPrice:100}]}}; admin approves the held change',
    expected: 'DESIGN §5.11.8: 202 held with requestedBy sam, amount 1400; invoice total stays 1500; after admin approves, total 100 and the dispute APPROVED' }, async (attempt) => {
    const c = checks();
    const inv = await invoiceBy(fx(attempt), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const applied = JSON.stringify({ action: 'replace_items', items: [{ productId: w.product.id, quantity: 1, unitPrice: 100 }] });
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.sam, body: { appliedChangeJson: applied } }));
    heldOk(c, r, 'DISPUTE_APPROVE');
    c.that('maker sam, amount 1400', r.pendingChange?.requestedBy?.id === U.sam.id && Number(r.pendingChange?.amount) === 1400, pick(r.pendingChange, ['requestedBy', 'amount']));
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('total still 1500', Number(g.json?.total) === 1500, g.json?.total);
    if (r.pendingChange) {
      const a = await rt.approvals.approve(T.admin, r.pendingChange.id);
      c.that('admin approve APPROVED', a.json?.status === 'APPROVED', brief(a).slice(0, 300));
      const g2 = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
      const d2 = await rt.api('GET', `/api/disputes/${d.id}`, { token: T.admin });
      c.that('total 100, dispute APPROVED', Number(g2.json?.total) === 100 && d2.json?.status === 'APPROVED', { total: g2.json?.total, dispute: d2.json?.status });
    }
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.dispute };
  });

  await tcase({ id: 'APP-G-19', feature: 'Held promise create', kind: 'API', prd: 'B2',
    title: 'A held promise with co-assignees gets them when approved, and they are told then',
    steps: 'mia POST /api/promises 6000 with assignees [USER nora] (held); sam approves; GET the promise; nora\'s notifications',
    expected: 'DESIGN §6.6 / §5.10.12: approved promise has nora among its assignees; nora notified of the assignment after approval' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await rt.createPromise(T.mia, { customerId: cu.id, amount: 6000, collectionPocUserId: U.sam.id, assignees: [rt.tok.user(U.nora.id)] });
    c.that('held', r.held, brief(r));
    const before = (await notifications(T.nora)).length;
    const a = await rt.approvals.approve(T.sam, r.pendingChange?.id);
    c.that('APPROVED', a.json?.status === 'APPROVED', pick(a.json, ['status', 'failureReason']) || brief(a));
    const g = await rt.api('GET', `/api/promises/${a.json?.result?.entityId}`, { token: T.admin });
    const people = (g.json?.assignees || []).flatMap((x) => (x.people || []).map((p) => p.userId));
    c.that('nora assigned', people.includes(U.nora.id), g.json?.assignees);
    const after = await notifications(T.nora);
    c.that('nora notified', after.length > before && after.some((n) => (n.link || '').includes(`/promises/${a.json?.result?.entityId}`)), after.slice(0, 3).map((n) => pick(n, ['type', 'title', 'link'])));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-G-20', feature: 'Closing pending changes', kind: 'API', prd: 'B2',
    title: 'Denying a dispute closes its held approval, and deleting a customer closes its held creates; the maker is told',
    steps: 'held dispute approval (mia) on an s1 invoice; sam POST /api/disputes/{id}/deny; mia holds a 6000 payment on a fresh customer; admin DELETE /api/customers/{id}',
    expected: 'DESIGN §5.11.7: both changes CLOSED with notes "Dispute denied" / "Customer deleted"; mia gets APPROVAL_CLOSED for each' }, async () => {
    const c = checks();
    const inv = await invoiceBy(fx(), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const h = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    const dn = await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: T.sam, body: { adminNotes: 'no' } });
    c.that('deny 200', dn.status === 200, brief(dn).slice(0, 200));
    const g1 = await rt.approvals.get(T.admin, h.pendingChange?.id);
    c.that('dispute change CLOSED "Dispute denied"', g1.json?.status === 'CLOSED' && /Dispute denied/.test(g1.json?.decisionNote || ''), pick(g1.json, ['status', 'decisionNote']));
    const cu = await customerIn(w);
    const p = await rt.createPayment(T.mia, { customerId: cu.id, amount: 6000, collectionPocUserId: U.sam.id });
    const del = await rt.api('DELETE', `/api/customers/${cu.id}`, { token: T.admin });
    c.that('delete 2xx', del.ok, brief(del).slice(0, 200));
    const g2 = await rt.approvals.get(T.admin, p.pendingChange?.id);
    c.that('create CLOSED "Customer deleted"', g2.json?.status === 'CLOSED' && /Customer deleted/.test(g2.json?.decisionNote || ''), pick(g2.json, ['status', 'decisionNote']) || brief(g2));
    const nm = await notifications(T.mia);
    const closed = nm.filter((n) => n.type === 'APPROVAL_CLOSED').map((n) => n.link);
    c.that('mia told of both', closed.includes(`/approvals/${h.pendingChange?.id}`) && closed.includes(`/approvals/${p.pendingChange?.id}`), closed.slice(0, 5));
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeCloser.java' };
  });

  // ============================== Visibility ==============================
  // A held cancel shared by the visibility cases.
  const visCust = await customerIn(w);
  const visInv = await invoiceBy(T.admin, visCust, 8000);
  const visHeld = await rt.cancelInvoice(T.mia, visInv.id);
  const visPc = visHeld.pendingChange;

  await tcase({ id: 'APP-V-01', feature: 'Pending visible to record viewers', kind: 'API', prd: 'B2',
    title: 'Someone with READ in South (nora) sees the held cancel everywhere, without it having taken effect',
    steps: 'mia holds a cancel on an 8000 South invoice; as nora: GET /api/approvals, GET /api/approvals/{id}, GET /api/approvals/for-record?entityType=INVOICE&entityId, GET /api/invoices?filter=customerId, filter=pendingApproval:eq:true, GET /api/invoices/{id}',
    expected: 'PRD B2 "a pending change is visible to everyone who can see the record, without having taken effect": listed, detail 200, for-record lists it, list row pendingApproval:true, the filter finds it, invoice still UNPAID' }, async () => {
    const c = checks();
    c.that('fixture held', !!visPc, brief(visHeld));
    const l = await rt.approvals.list(T.nora, { view: 'ALL' });
    c.that('in nora\'s approvals list', (l.json?.content || []).some((b) => b.id === visPc.id), brief(l).slice(0, 200));
    const d = await rt.approvals.get(T.nora, visPc.id);
    c.that('detail 200', d.status === 200, brief(d));
    const f = await rt.approvals.forRecord(T.nora, 'INVOICE', visInv.id);
    c.that('for-record lists it', f.status === 200 && Array.isArray(f.json) && f.json.some((b) => b.id === visPc.id), brief(f));
    const li = await rt.api('GET', '/api/invoices', { token: T.nora, query: { size: 50, filter: `customerId:eq:${visCust.id}` } });
    const row = (li.json?.content || []).find((x) => x.id === visInv.id);
    c.that('list row pendingApproval:true', row?.pendingApproval === true, row && pick(row, ['id', 'status', 'pendingApproval']));
    const lf = await rt.api('GET', '/api/invoices', { token: T.nora, query: { size: 50, filter: ['pendingApproval:eq:true', `customerId:eq:${visCust.id}`] } });
    c.that('filter pendingApproval:eq:true finds it', (lf.json?.content || []).some((x) => x.id === visInv.id), brief(lf).slice(0, 200));
    const g = await rt.api('GET', `/api/invoices/${visInv.id}`, { token: T.nora });
    c.that('invoice still UNPAID', g.json?.status === 'UNPAID', g.json?.status);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-02', feature: 'Pending hidden from non-viewers', kind: 'API', prd: 'B2',
    title: 'Users who cannot see South (wendy: West only; bob: North book) do not see the held change',
    steps: 'as wendy and bob: GET /api/approvals (ALL), GET /api/approvals/{id}, GET /api/approvals/for-record?entityType=INVOICE&entityId',
    expected: 'DESIGN §5.11.9: not listed; detail 404; for-record 404 (the record\'s scoped get)' }, async () => {
    const c = checks();
    for (const u of ['wendy', 'bob']) {
      const l = await rt.approvals.list(T[u], { view: 'ALL' });
      c.that(`${u}: not in list`, l.status === 200 && !(l.json?.content || []).some((b) => b.id === visPc.id), brief(l).slice(0, 150));
      const d = await rt.approvals.get(T[u], visPc.id);
      c.that(`${u}: detail 404`, d.status === 404, brief(d));
      const f = await rt.approvals.forRecord(T[u], 'INVOICE', visInv.id);
      c.that(`${u}: for-record 404`, f.status === 404, brief(f));
    }
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-03', feature: 'Pending create visible to region readers', kind: 'API', prd: 'B2',
    title: 'A held invoice create shows in nora\'s approvals list and on the customer\'s for-record, but not for wendy',
    steps: 'mia holds a 12000 invoice create on a fresh South customer; nora GET /api/approvals, GET for-record?entityType=CUSTOMER&entityId; wendy GET /api/approvals',
    expected: 'DESIGN §5.11.9 creates: visible where the region has INVOICE_VIEW and the customer is visible; for-record CUSTOMER lists the pending create' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const id = r.pendingChange?.id;
    c.that('held', r.held, brief(r));
    const l = await rt.approvals.list(T.nora, { view: 'ALL' });
    c.that('nora lists it', (l.json?.content || []).some((b) => b.id === id));
    const f = await rt.approvals.forRecord(T.nora, 'CUSTOMER', cu.id);
    c.that('for-record CUSTOMER lists it', Array.isArray(f.json) && f.json.some((b) => b.id === id), brief(f));
    const lw = await rt.approvals.list(T.wendy, { view: 'ALL' });
    c.that('wendy does not', !(lw.json?.content || []).some((b) => b.id === id));
    await cancelIfPending(id, T.mia);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-04', feature: 'Pending visible to the customer', kind: 'API', prd: 'B2',
    title: 'The customer login sees a pending change on its own invoice via for-record, without who asked; the approvals list is refused',
    steps: 'invoice 1500 on s1; cust disputes {action:cancel}; mia approves the dispute (held); as cust: GET /api/approvals, GET for-record INVOICE and DISPUTE',
    expected: 'DESIGN §5.11.9: GET /api/approvals 403; for-record lists the PENDING brief with requestedBy null' }, async (attempt) => {
    const c = checks();
    const inv = await invoiceBy(fx(attempt), s1, 1500);
    const d = await openDispute('INVOICE', inv.id, { action: 'cancel' });
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    const id = r.pendingChange?.id;
    c.that('held', r.held, brief(r));
    const l = await rt.approvals.list(T.cust, {});
    c.that('GET /api/approvals 403 for a customer', l.status === 403, brief(l));
    const f = await rt.approvals.forRecord(T.cust, 'INVOICE', inv.id);
    const b = Array.isArray(f.json) ? f.json.find((x) => x.id === id) : null;
    c.that('for-record INVOICE lists it', !!b, brief(f));
    c.that('requestedBy hidden', b && b.requestedBy == null, b && b.requestedBy);
    const fc = await rt.approvals.forRecord(T.cust, 'CUSTOMER', s1.id);
    c.that('for-record CUSTOMER refused for a customer login (staff-only)', fc.status === 403 || fc.status === 404, brief(fc));
    const fd = await rt.approvals.forRecord(T.cust, 'DISPUTE', d.id);
    c.that('for-record DISPUTE lists it', Array.isArray(fd.json) && fd.json.some((x) => x.id === id), brief(fd));
    await cancelIfPending(id, T.mia);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-05', feature: 'Approvals list regions', kind: 'API', prd: 'B2',
    title: 'The approvals list answers "which regions": regionIds=North excludes South changes; regionIds=South includes them',
    steps: 'admin GET /api/approvals?view=ALL&regionIds={North}; then regionIds={South}',
    expected: 'DESIGN §6.0/§5.11.9: every row in the North answer has regionId North; envelope regionIds=[North]; the South answer contains the held cancel' }, async () => {
    const c = checks();
    const n = await rt.list(T.admin, '/api/approvals', { view: 'ALL' }, { regionIds: [NORTH] });
    c.that('North answer has only North rows', (n.json?.content || []).every((b) => b.regionId === NORTH), (n.json?.content || []).map((b) => b.regionId));
    c.that('envelope regionIds [North]', JSON.stringify(n.json?.regionIds) === JSON.stringify([NORTH]), n.json?.regionIds);
    const s = await rt.list(T.admin, '/api/approvals', { view: 'ALL' }, { regionIds: [SOUTH] });
    c.that('South answer contains the held cancel', (s.json?.content || []).some((b) => b.id === visPc.id));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-06', feature: 'Approvals views and counts', kind: 'API', prd: 'B2',
    title: 'AWAITING_ME / MINE views and the awaiting-me count follow who can decide',
    steps: 'sam GET /api/approvals?view=AWAITING_ME, /awaiting-me/count, /summary; mia GET view=AWAITING_ME and view=MINE',
    expected: 'DESIGN §5.11.9: sam\'s AWAITING_ME contains mia\'s held cancel and none of sam\'s own; count equals the list total; mia has nothing awaiting her (no approve right) and her MINE contains it' }, async () => {
    const c = checks();
    const a = await rt.list(T.sam, '/api/approvals', { view: 'AWAITING_ME' });
    const rows = a.json?.content || [];
    c.that('sam AWAITING_ME contains mia\'s change', rows.some((b) => b.id === visPc.id));
    c.that('sam AWAITING_ME has none of his own', rows.every((b) => b.requestedBy?.id !== U.sam.id), rows.filter((b) => b.requestedBy?.id === U.sam.id).map((b) => b.id));
    c.that('all AWAITING_ME rows are PENDING', rows.every((b) => b.status === 'PENDING'));
    const cnt = await rt.api('GET', '/api/approvals/awaiting-me/count', { token: T.sam });
    c.that('awaiting-me count = list total', cnt.json?.count === a.json?.totalElements, `${cnt.json?.count} vs ${a.json?.totalElements}`);
    const sum = await rt.approvals.summary(T.sam);
    c.that('summary.awaitingMeCount = list total', sum.json?.awaitingMeCount === a.json?.totalElements, sum.json);
    const ma = await rt.approvals.list(T.mia, { view: 'AWAITING_ME' });
    c.that('mia AWAITING_ME empty', ma.json?.totalElements === 0, ma.json?.totalElements);
    const mm = await rt.list(T.mia, '/api/approvals', { view: 'MINE' });
    c.that('mia MINE contains it', (mm.json?.content || []).some((b) => b.id === visPc.id));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  await tcase({ id: 'APP-V-07', feature: 'Approvals views and counts', kind: 'API', prd: 'B2',
    title: 'The approvals summary counts and sums exactly the PENDING rows the caller can see in the asked regions',
    steps: 'admin GET /api/approvals?view=ALL&filter=status:eq:PENDING&regionIds={South} (all pages); GET /api/approvals/summary?regionIds={South}',
    expected: 'DESIGN §6.11: pendingCount = number of PENDING rows; pendingAmount = their sum' }, async () => {
    const c = checks();
    const l = await rt.list(T.admin, '/api/approvals', { view: 'ALL', filter: ['status:eq:PENDING'] }, { regionIds: [SOUTH] });
    const rows = l.json?.content || [];
    const sum = rows.reduce((a, b) => a + Number(b.amount || 0), 0);
    const s2 = await rt.approvals.summary(T.admin, { regionIds: [SOUTH] });
    c.that('pendingCount', s2.json?.pendingCount === rows.length, `${s2.json?.pendingCount} vs ${rows.length}`);
    c.that('pendingAmount', Math.abs(Number(s2.json?.pendingAmount) - sum) < 0.005, `${s2.json?.pendingAmount} vs ${sum}`);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalService.java:144' };
  }, { retry: false });

  await tcase({ id: 'APP-V-08', feature: 'Approvals export', kind: 'API', prd: 'B2',
    title: 'Exporting "My requests" gives a CSV of the caller\'s changes',
    steps: 'mia POST /api/approvals/export?view=MINE {action:EXPORT, selectAllMatchingFilter:true}',
    expected: 'DESIGN §6.11: 200 text/csv with a header and mia\'s held cancel (its summary)' }, async () => {
    const c = checks();
    const r = await rt.api('POST', '/api/approvals/export', { token: T.mia, query: { view: 'MINE' }, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    c.that('200 CSV', r.status === 200 && /^Id,Status,Change/.test(r.text || ''), (r.text || '').slice(0, 200));
    c.that('contains the held cancel', (r.text || '').includes(visPc.summary), visPc.summary);
    c.that('only mia\'s', !(r.text || '').split('\n').slice(1).filter(Boolean).some((l) => /Sam South|System Administrator/.test(l)), (r.text || '').split('\n').length);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalController.java:120' };
  }, { retry: false });

  await tcase({ id: 'APP-V-09', feature: 'Pending visible to record viewers', kind: 'API', prd: 'B2',
    title: 'Payments, promises and disputes lists flag rows with a change awaiting approval',
    steps: 'hold a void on an s1 payment (cust dispute, mia approves), a PROMISE_UPDATE on a promise (mia); nora GET /api/payments, /api/promises, /api/disputes filtered to them',
    expected: 'DESIGN §5.11.9 / TRIAGE CF-11: pendingApproval:true on the payment, promise and dispute rows; filter pendingApproval:eq:true finds them' }, async (attempt) => {
    const c = checks();
    const p = await paymentBy(fx(attempt), s1, 1200);
    const d = await openDispute('PAYMENT', p.id, { action: 'void' });
    const h1 = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    const cu = await customerIn(w);
    const pr = await promiseBy(T.admin, cu, 3000);
    const h2 = rt.heldAware(await rt.api('PUT', `/api/promises/${pr.id}`, { token: T.mia, body: { amount: 4500, promisedDate: pr.promisedDate, collectionPocUserId: pr.collectionPocUserId ?? U.sam.id, notes: null, invoiceIds: null } }));
    c.that('both held', h1.held && h2.held, `${h1.status}/${h2.status}`);
    const row = async (path, id, filter) => {
      const r = await rt.list(T.nora, path, { filter: [filter] });
      return (r.json?.content || []).find((x) => x.id === id);
    };
    const rp = await row('/api/payments', p.id, `customerId:eq:${s1.id}`);
    const rpr = await row('/api/promises', pr.id, `customerId:eq:${cu.id}`);
    const rd = await row('/api/disputes', d.id, `customerId:eq:${s1.id}`);
    c.that('payment row pendingApproval', rp?.pendingApproval === true, rp && pick(rp, ['id', 'pendingApproval']));
    c.that('promise row pendingApproval', rpr?.pendingApproval === true, rpr && pick(rpr, ['id', 'pendingApproval']));
    c.that('dispute row pendingApproval', rd?.pendingApproval === true, rd && pick(rd, ['id', 'pendingApproval']));
    const fp = await rt.list(T.nora, '/api/payments', { filter: ['pendingApproval:eq:true', `customerId:eq:${s1.id}`] });
    c.that('payments filter finds it', (fp.json?.content || []).some((x) => x.id === p.id), brief(fp).slice(0, 200));
    for (const h of [h1, h2]) if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.scope };
  });

  // ============================== Decisions ==============================
  const heldPaymentBy = async (tok, cu, amount = 6000) => rt.createPayment(tok, { customerId: cu.id, amount, collectionPocUserId: U.sam.id });

  let d04 = null; // shared with D-04/D-05/N-02/D-13
  await tcase({ id: 'APP-D-01', feature: 'Self-approval refused', kind: 'API', prd: 'B2',
    title: 'The maker cannot approve their own change, even holding APPROVE in the region',
    steps: 'sam (APPROVE South) POST /api/payments 6000 on a fresh South customer (held); sam POST /api/approvals/{id}/approve',
    expected: 'PRD B2 "needs approval from someone else"; DESIGN §5.11.6 check 5: 400 "You cannot approve a change you requested"; still PENDING' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.sam, cu);
    c.that('held', r.held, brief(r));
    const a = await rt.approvals.approve(T.sam, r.pendingChange?.id);
    c.that('400 self-approval', a.status === 400 && /cannot approve a change you requested/i.test(a.json?.message || ''), brief(a));
    const g = await rt.approvals.get(T.admin, r.pendingChange?.id);
    c.that('still PENDING', g.json?.status === 'PENDING', g.json?.status);
    d04 = { cu, pc: r.pendingChange };
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-D-02', feature: 'Approver needs APPROVE in the record\'s region', kind: 'API', prd: 'B2',
    title: 'Approvers from elsewhere are refused: nora (READ South), nate (APPROVE North, READ South), wade (APPROVE West only), mia/wendy (no approve right anywhere)',
    steps: 'on sam\'s held South payment: nora, nate POST /approve; wade (APPROVE/ALL West) POST /approve; mia, wendy POST /approve',
    expected: 'DESIGN §5.11.6/§6.11: nora, nate 403 (check 6: no CHANGE_APPROVE in South); wade 404 (cannot see the record, check 1); mia, wendy 403 at the "CHANGE_APPROVE somewhere" guard; change still PENDING' }, async () => {
    const c = checks();
    const id = d04?.pc?.id;
    let wade = await rt.findUser(T.admin, 'wade');
    const grants = [{ regionId: WEST, accessLevel: 'APPROVE', recordScope: 'ALL' }];
    if (!wade) wade = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'wade', { username: 'wade', fullName: 'Wade West', grants });
    else await rt.setGrants(T.admin, wade.id, grants);
    const wadeT = await rt.login('wade');
    for (const u of ['nora', 'nate', 'mia', 'wendy']) {
      const a = await rt.approvals.approve(T[u], id);
      c.that(`${u} -> 403`, a.status === 403, brief(a));
    }
    const ww = await rt.approvals.approve(wadeT, id);
    c.that('wade (APPROVE West only) -> 404', ww.status === 404, brief(ww));
    const cc = await rt.approvals.approve(T.cust, id);
    c.that('customer login -> 403', cc.status === 403, brief(cc));
    const g = await rt.approvals.get(T.admin, id);
    c.that('still PENDING', g.json?.status === 'PENDING', g.json?.status);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  }, { retry: false });

  await tcase({ id: 'APP-D-03', feature: 'Approval applies the change', kind: 'API', prd: 'B2',
    title: 'Approval by another South approver (admin) applies the held payment as the maker',
    steps: 'admin POST /api/approvals/{sam\'s held payment}/approve {note}; GET payments of the customer',
    expected: 'DESIGN §6.11: 200 Detail status APPROVED, result {entityType PAYMENT, entityId, link /payments/{id}}; the payment of 6000 now exists' }, async () => {
    const c = checks();
    const id = d04?.pc?.id;
    const a = await rt.approvals.approve(T.admin, id, 'looks right');
    c.that('200 APPROVED', a.status === 200 && a.json?.status === 'APPROVED', brief(a));
    c.that('result link /payments/{id}', a.json?.result?.link === `/payments/${a.json?.result?.entityId}`, a.json?.result);
    c.that('decidedBy admin, note kept', a.json?.decidedBy?.id === U.admin.id && a.json?.decisionNote === 'looks right', pick(a.json, ['decidedBy', 'decisionNote']));
    const pays = await paymentsOf(T.admin, d04.cu.id);
    c.that('payment 6000 exists', pays.length === 1 && Number(pays[0].amount) === 6000 && pays[0].id === a.json?.result?.entityId, pays.map((p) => pick(p, ['id', 'amount'])));
    const pay = await rt.api('GET', `/api/payments/${a.json?.result?.entityId}`, { token: T.admin });
    const dt = Math.abs(new Date(pay.json?.paidAt) - new Date(d04.pc.requestedAt));
    c.that('paidAt = the request instant (DESIGN §5.11.1 refactor 4)', dt < 1000, { paidAt: pay.json?.paidAt, requestedAt: d04.pc.requestedAt });
    d04.paymentId = a.json?.result?.entityId;
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  }, { retry: false });

  await tcase({ id: 'APP-D-04', feature: 'Decided change is final', kind: 'API', prd: 'B2',
    title: 'Approving, rejecting or withdrawing an already-approved change is a 409',
    steps: 'on the approved change: sam? no — nate is not allowed; admin approve again, admin reject, sam withdraw',
    expected: 'DESIGN §5.11.6 check 3: 409 "Already approved by …"' }, async () => {
    const c = checks();
    const id = d04?.pc?.id;
    const a = await rt.approvals.approve(T.admin, id);
    c.that('approve again 409', a.status === 409 && /Already approved/i.test(a.json?.message || ''), brief(a));
    const rj = await rt.approvals.reject(T.admin, id, 'late');
    c.that('reject 409', rj.status === 409, brief(rj));
    const wd = await rt.approvals.withdraw(T.sam, id);
    c.that('withdraw 409', wd.status === 409, brief(wd));
    const pays = await paymentsOf(T.admin, d04.cu.id);
    c.that('still exactly one payment', pays.length === 1, pays.length);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  }, { retry: false });

  await tcase({ id: 'APP-D-05', feature: 'Audit trail of a decision', kind: 'API', prd: 'B2',
    title: 'The customer\'s audit shows CHANGE_REQUESTED and CHANGE_APPROVED for the approved payment',
    steps: 'GET /api/audit?entityType=CUSTOMER&entityId={customer}',
    expected: 'DESIGN §5.11.11: lifecycle rows on CUSTOMER for creates, with pending_change_id' }, async () => {
    const c = checks();
    const a = await rt.api('GET', '/api/audit', { token: T.admin, query: { entityType: 'CUSTOMER', entityId: d04.cu.id } });
    const acts = (a.json || []).map((e) => e.action);
    c.that('CHANGE_REQUESTED', acts.includes('CHANGE_REQUESTED'), acts);
    c.that('CHANGE_APPROVED', acts.includes('CHANGE_APPROVED'), acts);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/audit/AuditService.java' };
  }, { retry: false });

  await tcase({ id: 'APP-D-06', feature: 'Reject', kind: 'API', prd: 'B2',
    title: 'Reject: the maker is told to withdraw, a note is required, a checker\'s rejection leaves nothing applied',
    steps: 'sam (APPROVE South) holds a 12000 invoice create; sam POST /reject; admin POST /reject {note:""}; admin POST /reject {note:"Too large"}; admin POST /approve',
    expected: 'DESIGN §5.11.6: maker 400 "Use Withdraw for your own request"; empty note 400 fieldErrors.note; 200 REJECTED with decisionNote; no invoice; later approve 409' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await rt.createInvoice(T.sam, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const id = r.pendingChange?.id;
    const m = await rt.approvals.reject(T.sam, id, 'mine');
    c.that('maker reject 400 "Use Withdraw"', m.status === 400 && /Use Withdraw/i.test(m.json?.message || ''), brief(m));
    const e = await rt.api('POST', `/api/approvals/${id}/reject`, { token: T.admin, body: { note: '' } });
    c.that('empty note 400 fieldErrors.note', e.status === 400 && !!e.json?.fieldErrors?.note, brief(e));
    const ok = await rt.approvals.reject(T.admin, id, 'Too large');
    c.that('200 REJECTED with note', ok.status === 200 && ok.json?.status === 'REJECTED' && ok.json?.decisionNote === 'Too large', brief(ok));
    const invs = await invoicesOf(T.admin, cu.id);
    c.that('no invoice', invs.length === 0, invs.length);
    const ap = await rt.approvals.approve(T.admin, id);
    c.that('approve after reject 409', ap.status === 409, brief(ap));
    w._rejected = { id, cu };
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.reject };
  });

  await tcase({ id: 'APP-D-07', feature: 'Withdraw', kind: 'API', prd: 'B2',
    title: 'Only the maker can withdraw; a withdrawn change can no longer be approved and nothing is applied',
    steps: 'mia holds a 6000 payment; admin POST /withdraw; sam POST /withdraw; mia POST /withdraw; admin POST /approve',
    expected: 'DESIGN §6.11: others 403; maker 200 WITHDRAWN (decidedBy maker); approve afterwards 409; no payment' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.mia, cu);
    const id = r.pendingChange?.id;
    const a = await rt.approvals.withdraw(T.admin, id);
    c.that('admin withdraw 403', a.status === 403, brief(a));
    const s = await rt.approvals.withdraw(T.sam, id);
    c.that('sam withdraw 403', s.status === 403, brief(s));
    const m = await rt.approvals.withdraw(T.mia, id);
    c.that('mia withdraw 200 WITHDRAWN', m.status === 200 && m.json?.status === 'WITHDRAWN', brief(m));
    const ap = await rt.approvals.approve(T.admin, id);
    c.that('approve after withdraw 409', ap.status === 409, brief(ap));
    const pays = await paymentsOf(T.admin, cu.id);
    c.that('no payment', pays.length === 0, pays.length);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.withdraw };
  });

  await tcase({ id: 'APP-D-08', feature: 'Decision flags per viewer', kind: 'API', prd: 'B2',
    title: 'Detail flags: maker can withdraw not approve; approver can approve/reject; READ viewer is told why not',
    steps: 'GET /api/approvals/{mia\'s held cancel} as mia, admin, nora',
    expected: 'DESIGN §6.11 Detail: mia canApprove=false canWithdraw=true blockedReason "You requested this change"; admin canApprove=true canReject=true canWithdraw=false blockedReason null; nora blockedReason "You don\'t hold the approval right in South"' }, async () => {
    const c = checks();
    const m = await rt.approvals.get(T.mia, visPc.id);
    c.that('mia flags', m.json?.canApprove === false && m.json?.canWithdraw === true && m.json?.blockedReason === 'You requested this change', pick(m.json, ['canApprove', 'canReject', 'canWithdraw', 'blockedReason']));
    const a = await rt.approvals.get(T.admin, visPc.id);
    c.that('admin flags', a.json?.canApprove === true && a.json?.canReject === true && a.json?.canWithdraw === false && a.json?.blockedReason == null, pick(a.json, ['canApprove', 'canReject', 'canWithdraw', 'blockedReason']));
    const n = await rt.approvals.get(T.nora, visPc.id);
    c.that('nora flags', n.json?.canApprove === false && /approval right in South/.test(n.json?.blockedReason || ''), pick(n.json, ['canApprove', 'canReject', 'canWithdraw', 'blockedReason']));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.views };
  });

  await tcase({ id: 'APP-D-09', feature: 'Approver needs APPROVE in the record\'s region', kind: 'API', prd: 'B2',
    title: 'A North change above North\'s own limit (50000) needs a North approver: sam (MANAGE North, APPROVE South) is refused, nate (APPROVE North) applies it',
    steps: 'fresh North customer (POC nora); nora POST /api/invoices 60000; sam POST /approve; nate POST /approve',
    expected: 'PRD B2 per-region rights + thresholds: 202 (North limit 50000); sam 403; nate 200 APPROVED and the invoice exists' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH');
    const r = await rt.createInvoice(T.nora, { customerId: cu.id, amount: 60000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id });
    c.that('held with threshold 50000', r.held && Number(r.pendingChange?.threshold) === 50000, brief(r));
    const s = await rt.approvals.approve(T.sam, r.pendingChange?.id);
    c.that('sam 403', s.status === 403, brief(s));
    const n = await rt.approvals.approve(T.nate, r.pendingChange?.id);
    c.that('nate 200 APPROVED', n.status === 200 && n.json?.status === 'APPROVED', brief(n));
    const invs = await invoicesOf(T.admin, cu.id);
    c.that('invoice 60000 exists', invs.length === 1 && Number(invs[0].total) === 60000, invs.map((i) => pick(i, ['id', 'total'])));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-D-10', feature: 'One outstanding change per record', kind: 'API', prd: 'B2',
    title: 'A second person\'s gated change on a record with a pending change is 409; the maker\'s repeat supersedes their own',
    steps: 'mia holds a cancel on an 8000 invoice; sam PATCH the invoice dueDate +60d (INVOICE_TERMS); mia POST cancel again',
    expected: 'DESIGN §5.11.7: sam 409 "… already has a change awaiting approval (#id, Cancel invoice by Mia Maker)…"; mia 202 with supersededId = first id; first becomes SUPERSEDED' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const r1 = await rt.cancelInvoice(T.mia, inv.id);
    const p = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    c.that('sam 409 awaiting approval', p.status === 409 && /already has a change awaiting approval/.test(p.json?.message || ''), brief(p));
    const r2 = await rt.cancelInvoice(T.mia, inv.id);
    c.that('mia repeat 202 supersededId', r2.held && r2.pendingChange?.supersededId === r1.pendingChange?.id, pick(r2.pendingChange, ['id', 'supersededId']));
    const g = await rt.approvals.get(T.admin, r1.pendingChange?.id);
    c.that('first SUPERSEDED', g.json?.status === 'SUPERSEDED', g.json?.status);
    w._d10 = r2.pendingChange;
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.hold };
  });

  await tcase({ id: 'APP-D-11', feature: 'Apply conflict', kind: 'API', prd: 'B2',
    title: 'If the record changed so the held cancel can no longer apply, approval ends FAILED with a reason and nothing changes',
    steps: 'mia holds a cancel on an 8000 invoice; admin records a 1000 payment against it; sam approves the cancel',
    expected: 'DESIGN §5.11.6: 200 Detail status FAILED with failureReason; invoice not cancelled; maker notified APPROVAL_FAILED' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const r = await rt.cancelInvoice(T.mia, inv.id);
    await paymentBy(T.admin, cu, 1000, { invoiceIds: [inv.id] });
    const a = await rt.approvals.approve(T.sam, r.pendingChange?.id);
    c.that('200 FAILED with reason', a.status === 200 && a.json?.status === 'FAILED' && !!a.json?.failureReason, pick(a.json, ['status', 'failureReason']) || brief(a));
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    c.that('invoice not cancelled', g.json?.status !== 'CANCELLED', g.json?.status);
    const ns = await notifications(T.mia);
    c.that('mia notified APPROVAL_FAILED', ns.some((n) => n.type === 'APPROVAL_FAILED' && n.link === `/approvals/${r.pendingChange?.id}`), ns.slice(0, 3).map((n) => n.type));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-D-12', feature: 'No decisions in the as-of view', kind: 'API', prd: 'B2',
    title: 'Approving, rejecting or withdrawing while viewing a past date is refused',
    steps: 'admin POST /api/approvals/{pending}/approve?asOf=<yesterday>',
    expected: 'DESIGN §5.11.10/§6.17: 400 AS_OF_READ_ONLY; change still PENDING' }, async () => {
    const c = checks();
    const id = w._d10?.id;
    const a = await rt.api('POST', `/api/approvals/${id}/approve`, { token: T.admin, body: {}, query: { asOf: rt.day(-1) } });
    c.that('approve 400 AS_OF_READ_ONLY', a.status === 400 && a.json?.error === 'AS_OF_READ_ONLY', brief(a));
    const rj = await rt.api('POST', `/api/approvals/${id}/reject`, { token: T.admin, body: { note: 'x' }, query: { asOf: rt.day(-1) } });
    c.that('reject 400 AS_OF_READ_ONLY', rj.status === 400 && rj.json?.error === 'AS_OF_READ_ONLY', brief(rj));
    const wd = await rt.api('POST', `/api/approvals/${id}/withdraw`, { token: T.mia, body: {}, query: { asOf: rt.day(-1) } });
    c.that('withdraw 400 AS_OF_READ_ONLY', wd.status === 400 && wd.json?.error === 'AS_OF_READ_ONLY', brief(wd));
    const g = await rt.approvals.get(T.admin, id);
    c.that('still PENDING', g.json?.status === 'PENDING', g.json?.status);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/common/scope/ViewScopeInterceptor.java' };
  }, { retry: false });

  await tcase({ id: 'APP-D-13', feature: 'Approval applies once', kind: 'API', prd: 'B2',
    title: 'Two approvers approving the same change at the same moment apply it exactly once',
    steps: 'mia holds a 6000 payment; admin and sam POST /approve concurrently',
    expected: 'DESIGN §5.11.5/§5.11.6: one 200 APPROVED, the other 409 (already approved / locked); exactly one payment' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.mia, cu);
    const [a, b] = await Promise.all([rt.approvals.approve(T.admin, r.pendingChange?.id), rt.approvals.approve(T.sam, r.pendingChange?.id)]);
    const st = [a.status, b.status].sort().join(',');
    c.that('one 200 and one 409', st === '200,409', `${brief(a).slice(0, 160)} | ${brief(b).slice(0, 160)}`);
    const pays = await paymentsOf(T.admin, cu.id);
    c.that('exactly one payment', pays.length === 1, pays.length);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-D-14', feature: 'Apply conflict', kind: 'API', prd: 'B2',
    title: 'If the maker lost the region before approval, the approval ends FAILED and nothing is applied',
    steps: 'mia holds a 6000 payment; admin removes mia\'s South grant; admin approves; mia\'s grant restored',
    expected: 'DESIGN §5.11.6 isApplyFailure: 200 status FAILED with a reason (the requester can no longer reach / has no right); no payment' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.mia, cu);
    rt.need(await rt.setGrants(T.admin, U.mia.id, []), 'drop mia grants');
    let a;
    try {
      a = await rt.approvals.approve(T.admin, r.pendingChange?.id);
    } finally {
      rt.need(await rt.setGrants(T.admin, U.mia.id, U.mia.grants), 'restore mia grants');
    }
    c.that('200 FAILED with reason', a.status === 200 && a.json?.status === 'FAILED' && !!a.json?.failureReason, pick(a.json, ['status', 'failureReason']) || brief(a));
    const pays = await paymentsOf(T.admin, cu.id);
    c.that('no payment', pays.length === 0, pays.length);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.svc };
  });

  await tcase({ id: 'APP-D-15', feature: 'Decision flags per viewer', kind: 'API', prd: 'B2',
    title: 'The approver sees what changed since the request: a payment recorded after a held cancel shows as drift',
    steps: 'mia holds a cancel on an 8000 invoice; admin records 1000 against it; admin GET /api/approvals/{id}',
    expected: 'DESIGN §6.11 Detail.changes / TRIAGE CF-8: a paidAmount row with current 1000 and changedSinceRequest=true' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const r = await rt.cancelInvoice(T.mia, inv.id);
    await paymentBy(T.admin, cu, 1000, { invoiceIds: [inv.id] });
    const d = await rt.approvals.get(T.admin, r.pendingChange?.id);
    const row = (d.json?.changes || []).find((x) => x.field === 'paidAmount');
    c.that('paidAmount drift flagged', row && row.changedSinceRequest === true && Number(row.current) === 1000, d.json?.changes);
    if (r.pendingChange) await rt.approvals.withdraw(T.mia, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.views };
  });

  await tcase({ id: 'APP-D-16', feature: 'Approval applies the change', kind: 'API', prd: 'B2',
    title: 'A held invoice create keeps the price it was asked at, even if the product price changes before approval',
    steps: 'product P price 100; mia POST /api/invoices items [{P, quantity 150}] (no unitPrice) -> held 15000; admin PUT product price 300; sam approves',
    expected: 'DESIGN §5.11.1 INVOICE_CREATE payload "each line\'s unitPrice pinned": APPROVED; invoice total 15000 (not 45000)' }, async () => {
    const c = checks();
    const name = rt.uniq('APV Gadget');
    const prod = rt.need(await rt.api('POST', '/api/products', { token: T.admin, body: { name, description: 'e2e', price: 100, active: true } }), 'product');
    const cu = await customerIn(w);
    const r = await rt.createInvoice(T.mia, { customerId: cu.id, items: [{ productId: prod.id, quantity: 150 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('held at 15000', r.held && Number(r.pendingChange?.amount) === 15000, brief(r));
    rt.need(await rt.api('PUT', `/api/products/${prod.id}`, { token: T.admin, body: { name, description: 'e2e', price: 300, active: true } }), 'price change');
    const a = await rt.approvals.approve(T.sam, r.pendingChange?.id);
    c.that('APPROVED', a.json?.status === 'APPROVED', pick(a.json, ['status', 'failureReason']));
    const invs = await invoicesOf(T.admin, cu.id);
    c.that('invoice total 15000', invs.length === 1 && Number(invs[0].total) === 15000, invs.map((i) => i.total));
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java' };
  });

  const termsFixture = async (notes) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    if (notes !== undefined) rt.need(await rt.patchInvoice(T.admin, inv.id, { notes }), 'set notes');
    return inv;
  };
  const invNow = async (id) => (await rt.api('GET', `/api/invoices/${id}`, { token: T.admin })).json;

  await tcase({ id: 'APP-D-17', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'Approving a held terms change must not undo a note edited after the request (lost update)',
    steps: 'invoice 2500 with notes "first"; mia PATCH {notes:"first", paymentTerm:NET_90} (held — the invoice page always sends the notes); admin PATCH {notes:"second"} (not gated, 200); sam approves the held change; GET the invoice',
    expected: 'PRD B2 (the approved change takes effect, nothing else); DESIGN §5.11.6 three-way / InvoiceChangeApplier javadoc "the rest of the invoice is whatever it is now": APPROVED, paymentTerm NET_90, notes still "second"' }, async () => {
    const c = checks();
    const inv = await termsFixture('first');
    const h = await rt.patchInvoice(T.mia, inv.id, { notes: 'first', paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const e = await rt.patchInvoice(T.admin, inv.id, { notes: 'second' });
    c.that('later notes edit applied', e.status === 200 && e.json?.notes === 'second', brief(e).slice(0, 120));
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('APPROVED, term NET_90', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90', { status: a.json?.status, term: g?.paymentTerm });
    c.that('notes still "second" (not reverted)', g?.notes === 'second', { notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), severity: 'medium',
      evidence: `pendingChange ${h.pendingChange?.id}; GET /api/invoices/${inv.id} -> notes ${JSON.stringify(g?.notes)}, paymentTerm ${g?.paymentTerm}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:136 (applyTerms re-runs the whole stored request: fields it did not change still overwrite newer values)' };
  });

  await tcase({ id: 'APP-D-18', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'Approving a held terms change must not undo a Sales POC reassignment made after the request (lost update)',
    steps: 'invoice 2500 (Sales POC sam); mia PATCH {paymentTerm:NET_90, salesPocUserId:sam (unchanged)} (held); admin PATCH {salesPocUserId: mia} (200); sam approves; GET the invoice',
    expected: 'DESIGN §5.11.6 three-way: APPROVED; paymentTerm NET_90; Sales POC stays Mia Maker' }, async () => {
    const c = checks();
    const inv = await termsFixture();
    const h = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90', salesPocUserId: U.sam.id });
    c.that('held', h.held, brief(h));
    const e = await rt.patchInvoice(T.admin, inv.id, { salesPocUserId: U.mia.id });
    c.that('reassignment applied', e.status === 200, brief(e).slice(0, 120));
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    const poc = g?.salesPoc?.id ?? g?.salesPocUserId;
    c.that('APPROVED, term NET_90', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90', { status: a.json?.status, term: g?.paymentTerm });
    c.that('Sales POC still mia (not reverted to sam)', poc === U.mia.id, { salesPoc: g?.salesPoc?.fullName, id: poc });
    return { ok: c.ok, actual: c.summary(), severity: 'medium',
      evidence: `pendingChange ${h.pendingChange?.id}; GET /api/invoices/${inv.id} -> salesPoc ${g?.salesPoc?.fullName}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:136' };
  });

  await tcase({ id: 'APP-D-19', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'A terms change sent the way the invoice page sends it (blank notes) must still apply after someone adds a note',
    steps: 'invoice 2500 with no notes; mia PATCH {notes:"", paymentTerm:NET_90} (held; the page sends notes:"" when the field is empty); admin PATCH {notes:"call Friday"}; sam approves',
    expected: 'The maker did not touch notes, so no conflict: APPROVED, NET_90, notes "call Friday"' }, async () => {
    const c = checks();
    const inv = await termsFixture();
    const h = await rt.patchInvoice(T.mia, inv.id, { notes: '', paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    await rt.patchInvoice(T.admin, inv.id, { notes: 'call Friday' });
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('APPROVED (not FAILED)', a.json?.status === 'APPROVED', { status: a.json?.status, failureReason: a.json?.failureReason });
    c.that('NET_90 and notes kept', g?.paymentTerm === 'NET_90' && g?.notes === 'call Friday', { term: g?.paymentTerm, notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), severity: 'low',
      evidence: `pendingChange ${h.pendingChange?.id}: status ${a.json?.status}, failureReason ${JSON.stringify(a.json?.failureReason)}`,
      codeRef: 'frontend/lib/features/invoices/invoice_detail_screen.dart:143 (always sends notes) + backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:130 (null vs "" counted as a notes change)' };
  });

  await tcase({ id: 'APP-D-20', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'A held promise amount change is re-based: a note edited after the request survives the approval',
    steps: 'promise 3000 notes "first"; mia PUT amount 4500 notes "first" (held); admin PUT amount 3000 notes "second"; sam approves',
    expected: 'DESIGN §5.11.1 PROMISE_UPDATE "re-based on current values": APPROVED, amount 4500, notes "second"' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000, { notes: 'first' });
    const body = (amount, notes) => ({ amount, promisedDate: p.promisedDate, collectionPocUserId: U.sam.id, notes, invoiceIds: null });
    const h = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: body(4500, 'first') }));
    await rt.api('PUT', `/api/promises/${p.id}`, { token: T.admin, body: body(3000, 'second') });
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await rt.api('GET', `/api/promises/${p.id}`, { token: T.admin });
    c.that('APPROVED amount 4500 notes second', a.json?.status === 'APPROVED' && Number(g.json?.amount) === 4500 && g.json?.notes === 'second', { status: a.json?.status, amount: g.json?.amount, notes: g.json?.notes });
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/promise/PromiseChangeApplier.java' };
  });

  await tcase({ id: 'APP-D-21', feature: 'Apply conflict', kind: 'API', prd: 'B2',
    title: 'A held loosening fails when the same limit was tightened after the request',
    steps: 'sam PUT South [PROMISE_OVERRIDE 5000] (held; current 1000); admin PUT [PROMISE_OVERRIDE 500] (tightening, 200); admin approves sam\'s loosening',
    expected: 'Three-way conflict: FAILED "… was changed by someone else"; the limit stays 500' }, async () => {
    const c = checks();
    const h = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'PROMISE_OVERRIDE', amount: 5000 }]);
    c.that('held', h.status === 202, brief(h).slice(0, 120));
    const t2 = await rt.setThresholds(T.admin, SOUTH, [{ operation: 'PROMISE_OVERRIDE', amount: 500 }]);
    c.that('tightening 200', t2.status === 200, brief(t2).slice(0, 120));
    const a = await rt.approvals.approve(T.admin, h.json?.pendingChange?.id);
    c.that('FAILED changed by someone else', a.json?.status === 'FAILED' && /changed by someone else/.test(a.json?.failureReason || ''), pick(a.json, ['status', 'failureReason']));
    const g = await rt.getThresholds(T.admin, SOUTH);
    c.that('limit 500', Number(g.json?.thresholds?.find((x) => x.operation === 'PROMISE_OVERRIDE')?.amount) === 500);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ThresholdChangeApplier.java' };
  }, { retry: false });

  // ---- round 2: nearby gaps around APP-R1-1 / APP-R1-2 / APP-R1-4 ----
  await tcase({ id: 'APP-D-22', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'Conflict detection still holds for fields the maker did change: maker edits notes+terms, someone else edits notes, approval FAILS',
    steps: 'invoice 2500 notes "first"; mia PATCH {notes:"maker note", paymentTerm:NET_90} (held); admin PATCH {notes:"other note"} (200); sam approves',
    expected: 'DESIGN §5.11.6 three-way conflict: status FAILED, failureReason "Notes was changed by someone else"; invoice keeps CUSTOM and "other note"' }, async () => {
    const c = checks();
    const inv = await termsFixture('first');
    const h = await rt.patchInvoice(T.mia, inv.id, { notes: 'maker note', paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const e = await rt.patchInvoice(T.admin, inv.id, { notes: 'other note' });
    c.that('other edit 200', e.status === 200, brief(e).slice(0, 120));
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('FAILED "Notes was changed by someone else"', a.json?.status === 'FAILED' && /Notes was changed by someone else/.test(a.json?.failureReason || ''), pick(a.json, ['status', 'failureReason']));
    c.that('invoice untouched (CUSTOM, "other note")', g?.paymentTerm === 'CUSTOM' && g?.notes === 'other note', { term: g?.paymentTerm, notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), evidence: `pendingChange ${h.pendingChange?.id} -> ${JSON.stringify(pick(a.json, ['status', 'failureReason']))}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:127' };
  });

  await tcase({ id: 'APP-D-23', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'A held change of notes and terms together applies both on approval when nobody interfered',
    steps: 'invoice 2500 notes "first"; mia PATCH {notes:"maker note", paymentTerm:NET_90} (held); sam approves',
    expected: 'APPROVED; paymentTerm NET_90 and notes "maker note" (the rebase still sends notes the maker changed)' }, async () => {
    const c = checks();
    const inv = await termsFixture('first');
    const h = await rt.patchInvoice(T.mia, inv.id, { notes: 'maker note', paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const g0 = await invNow(inv.id);
    c.that('not applied while pending', g0?.notes === 'first' && g0?.paymentTerm === 'CUSTOM', { term: g0?.paymentTerm, notes: g0?.notes });
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('APPROVED; NET_90 + "maker note"', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90' && g?.notes === 'maker note', { status: a.json?.status, failure: a.json?.failureReason, term: g?.paymentTerm, notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:139' };
  });

  await tcase({ id: 'APP-D-24', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'Whitespace-only notes on an invoice with no notes are no notes: no Notes row, and a later note survives approval',
    steps: 'invoice 2500 with no notes; mia PATCH {notes:"   ", paymentTerm:NET_90} (held); GET /api/approvals/{id}; admin PATCH {notes:"call Friday"}; sam approves',
    expected: 'FIXLOG APP-R1-2 "blank notes are no notes": detail has no Notes row; APPROVED; NET_90; notes "call Friday"' }, async () => {
    const c = checks();
    const inv = await termsFixture();
    const h = await rt.patchInvoice(T.mia, inv.id, { notes: '   ', paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const d = await rt.approvals.get(T.admin, h.pendingChange?.id);
    const fields = (d.json?.changes || []).map((x) => x.field);
    c.that('no notes row in the detail', !fields.includes('notes'), fields);
    await rt.patchInvoice(T.admin, inv.id, { notes: 'call Friday' });
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('APPROVED; NET_90; notes kept', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90' && g?.notes === 'call Friday', { status: a.json?.status, failure: a.json?.failureReason, term: g?.paymentTerm, notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), severity: 'low', codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:146' };
  });

  await tcase({ id: 'APP-D-25', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'A held change that reassigns the Sales POC and the terms applies both, and keeps a note added meanwhile',
    steps: 'invoice 2500 (Sales POC sam, no notes); mia PATCH {salesPocUserId: mia, paymentTerm:NET_90} (held); admin PATCH {notes:"meanwhile"}; sam approves',
    expected: 'APPROVED; Sales POC Mia Maker; NET_90; notes "meanwhile"' }, async () => {
    const c = checks();
    const inv = await termsFixture();
    const h = await rt.patchInvoice(T.mia, inv.id, { salesPocUserId: U.mia.id, paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const g0 = await invNow(inv.id);
    c.that('POC not changed while pending', (g0?.salesPoc?.id ?? g0?.salesPocUserId) === U.sam.id, g0?.salesPoc);
    await rt.patchInvoice(T.admin, inv.id, { notes: 'meanwhile' });
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    const poc = g?.salesPoc?.id ?? g?.salesPocUserId;
    c.that('APPROVED; POC mia; NET_90; notes kept', a.json?.status === 'APPROVED' && poc === U.mia.id && g?.paymentTerm === 'NET_90' && g?.notes === 'meanwhile',
      { status: a.json?.status, failure: a.json?.failureReason, poc: g?.salesPoc?.fullName, term: g?.paymentTerm, notes: g?.notes });
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:147' };
  });

  await tcase({ id: 'APP-D-26', feature: 'What the checker is shown', kind: 'API', prd: 'B2',
    title: 'The change detail gives readable labels and names: Sales POC / Collection POC by name, humanised term and method',
    steps: 'mia holds a 12000 invoice create (Sales POC sam, CUSTOM) and a 7000 payment (Collection POC sam, BANK_TRANSFER); admin GET /api/approvals/{id} for each',
    expected: 'FIXLOG APP-R1-4 / DESIGN §7.7.1: rows labelled "Sales POC"/"Collection POC" with proposedText "Sam South"; the summary names the customer; "Payment term" proposedText "Custom"; "Method" proposedText "Bank transfer"; no label ending in "user id"' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const ic = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const cu2 = await customerIn(w);
    const pp = await rt.createPayment(T.mia, { customerId: cu2.id, amount: 7000, method: 'BANK_TRANSFER', collectionPocUserId: U.sam.id });
    c.that('both held', ic.held && pp.held, [ic.status, pp.status]);
    const d1 = (await rt.approvals.get(T.admin, ic.pendingChange?.id)).json;
    const d2 = (await rt.approvals.get(T.admin, pp.pendingChange?.id)).json;
    const row = (d, label) => (d?.changes || []).find((x) => x.label === label);
    const rows = (d) => (d?.changes || []).map((x) => `${x.label}=${x.proposedText ?? JSON.stringify(x.proposed)}`);
    c.that('invoice: Sales POC -> Sam South', row(d1, 'Sales POC')?.proposedText === 'Sam South', rows(d1));
    // The customer is the change's context (header/summary), not a row: the summary must name it.
    c.that('invoice: summary names the customer', (d1?.summary || '').includes(cu.name), d1?.summary);
    c.that('invoice: Payment term -> Custom', row(d1, 'Payment term')?.proposedText === 'Custom', row(d1, 'Payment term'));
    c.that('payment: Collection POC -> Sam South', row(d2, 'Collection POC')?.proposedText === 'Sam South', rows(d2));
    c.that('payment: Method -> Bank transfer', row(d2, 'Method')?.proposedText === 'Bank transfer', row(d2, 'Method'));
    const all = [...(d1?.changes || []), ...(d2?.changes || [])].map((x) => x.label);
    c.that('no "user id" labels', !all.some((l) => /user id$/i.test(l || '')), all);
    for (const pc of [ic.pendingChange, pp.pendingChange]) if (pc) await rt.approvals.withdraw(T.mia, pc.id);
    return { ok: c.ok, actual: c.summary(), severity: 'low', evidence: `invoice rows ${JSON.stringify(rows(d1)).slice(0, 300)}; payment rows ${JSON.stringify(rows(d2)).slice(0, 300)}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:274' };
  });

  await tcase({ id: 'APP-D-27', feature: 'Approval applies only the held change', kind: 'API', prd: 'B2',
    title: 'A held terms change fails cleanly when someone else moved the due date in the meantime',
    steps: 'invoice 2500 CUSTOM due +20d; mia PATCH {paymentTerm:NET_90} (held); admin PATCH {dueDate:+10d} (earlier: not gated, 200); sam approves',
    expected: 'DESIGN §5.11.6 three-way: FAILED "Due date was changed by someone else"; due date stays +10d, term CUSTOM' }, async () => {
    const c = checks();
    const inv = await termsFixture();
    const h = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const e = await rt.patchInvoice(T.admin, inv.id, { dueDate: rt.day(10) });
    c.that('earlier due date applies at once', e.status === 200, brief(e).slice(0, 160));
    const a = await rt.approvals.approve(T.sam, h.pendingChange?.id);
    const g = await invNow(inv.id);
    c.that('FAILED, due date conflict', a.json?.status === 'FAILED' && /Due date was changed by someone else/.test(a.json?.failureReason || ''), pick(a.json, ['status', 'failureReason']));
    c.that('invoice keeps +10d and CUSTOM', g?.dueDate === rt.day(10) && g?.paymentTerm === 'CUSTOM', { due: g?.dueDate, term: g?.paymentTerm });
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:125' };
  });

  await tcase({ id: 'APP-D-28', feature: 'What the checker is shown', kind: 'API', prd: 'B2',
    title: 'The change detail names the invoices a held payment is allocated to and the products on a held invoice (not raw ids)',
    steps: 'admin invoice 8000 on a fresh South customer; mia POST /api/payments 7000 invoiceIds [that invoice] (held); mia POST /api/invoices items [{productId, quantity 150}] (held); admin GET /api/approvals/{id} for each',
    expected: 'TRIAGE r2 APP-R2-1 contract (DESIGN §7.7.1 the changes table): the payment\'s `invoiceIds` row is labelled "Invoices" and its proposedText is exactly the invoice number (e.g. "INV-20260927-0045"); a create with no promises has no `promiseIds` row; the invoice create\'s `items` row reads "150 × E2E Widget …" (one line per item: "<qty> × <product name> at <unit price>")' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const pp = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id, invoiceIds: [inv.id] });
    const prod = w.product;
    const cu2 = await customerIn(w);
    const ic = await rt.createInvoice(T.mia, { customerId: cu2.id, items: [{ productId: prod.id, quantity: 150 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('both held', pp.held && ic.held, [pp.status, ic.status]);
    const d1 = (await rt.approvals.get(T.admin, pp.pendingChange?.id)).json;
    const d2 = (await rt.approvals.get(T.admin, ic.pendingChange?.id)).json;
    const alloc = (d1?.changes || []).find((x) => x.field === 'invoiceIds');
    const promRow = (d1?.changes || []).find((x) => x.field === 'promiseIds');
    const items = (d2?.changes || []).find((x) => x.field === 'items');
    // Compare with the invoice's own number (from the create reply), never an id/number-suffix coincidence.
    c.that('allocation row label is "Invoices"', !!alloc && alloc.label === 'Invoices', alloc?.label);
    c.that('allocation row proposedText is exactly the invoice number', !!alloc && !!inv.invoiceNumber && alloc.proposedText === inv.invoiceNumber, { proposedText: alloc?.proposedText, invoiceNumber: inv.invoiceNumber });
    c.that('payment create with no promises has no promiseIds row', !promRow, promRow);
    c.that(`Items row reads "150 × ${prod.name}"`, !!items && String(items.proposedText || '').includes(`150 × ${prod.name}`), items && { proposed: items.proposed, proposedText: items.proposedText });
    for (const [tok, pc] of [[T.mia, pp.pendingChange], [T.mia, ic.pendingChange]]) if (pc) await rt.approvals.withdraw(tok, pc.id);
    return { ok: c.ok, actual: c.summary(), severity: 'low',
      evidence: `GET /api/approvals/${pp.pendingChange?.id} invoiceIds row ${JSON.stringify(alloc)}; promiseIds row ${JSON.stringify(promRow) || 'none'}; invoice ${inv.id} is ${inv.invoiceNumber}; GET /api/approvals/${ic.pendingChange?.id} items row ${JSON.stringify(items).slice(0, 200)}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java (text() / LABELS for invoiceIds, promiseIds, items)' };
  });

  // ---- r3: nearby gaps of APP-R2-1 (TRIAGE r2 contract: Promises label + "Promise of <amt> by <date>", currentText "None", item lines)
  await tcase({ id: 'APP-D-29', feature: 'What the checker is shown', kind: 'API', prd: 'B2',
    title: 'A held payment settling a promise names the promise ("Promise of ₹3,000.00 by <date>") under "Promises", with no empty Invoices row',
    steps: 'fresh South customer; admin promise 3000 by +10d; mia POST /api/payments 7000 promiseIds [that promise] (no invoiceIds) -> held; admin GET /api/approvals/{id}',
    expected: 'TRIAGE r2 APP-R2-1 (DESIGN §7.7.1): promiseIds row label "Promises", proposedText "Promise of ₹3,000.00 by <yyyy-MM-dd>"; a create whose invoiceIds is null/[] has no invoiceIds row' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const pp = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id, promiseIds: [p.id] });
    c.that('payment held', pp.held, brief(pp));
    const d = (await rt.approvals.get(T.admin, pp.pendingChange?.id)).json;
    const prow = (d?.changes || []).find((x) => x.field === 'promiseIds');
    const irow = (d?.changes || []).find((x) => x.field === 'invoiceIds');
    const want = `Promise of ₹3,000.00 by ${p.promisedDate}`;
    c.that('promiseIds label "Promises"', prow?.label === 'Promises', prow);
    c.that(`proposedText "${want}"`, prow?.proposedText === want, prow?.proposedText);
    c.that('no invoiceIds row', !irow, irow);
    if (pp.pendingChange) await rt.approvals.withdraw(T.mia, pp.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), severity: 'low', evidence: `GET /api/approvals/${pp.pendingChange?.id} changes ${JSON.stringify(d?.changes).slice(0, 500)}`, codeRef: CODE.views };
  });

  await tcase({ id: 'APP-D-30', feature: 'What the checker is shown', kind: 'API', prd: 'B2',
    title: 'A held promise update that links an invoice shows Invoices: current "None" -> proposed the invoice number',
    steps: 'fresh South customer; admin invoice 8000; admin promise 3000 (no invoices); mia PUT /api/promises/{id} {amount 4500, invoiceIds [invoice]} (delta 1500 > PROMISE_UPDATE 1000) -> held; admin GET /api/approvals/{id}',
    expected: 'TRIAGE r2 APP-R2-1: invoiceIds row label "Invoices", currentText "None", proposedText = invoice number; promise unchanged (3000, no invoices) while pending' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const p = await promiseBy(T.admin, cu, 3000);
    const r = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: { amount: 4500, promisedDate: p.promisedDate, collectionPocUserId: U.sam.id, notes: p.notes ?? null, invoiceIds: [inv.id] } }));
    c.that('held PROMISE_UPDATE', r.held && r.pendingChange?.operation === 'PROMISE_UPDATE', brief(r));
    const d = (await rt.approvals.get(T.admin, r.pendingChange?.id)).json;
    const row = (d?.changes || []).find((x) => x.field === 'invoiceIds');
    c.that('label "Invoices"', row?.label === 'Invoices', row);
    c.that('currentText "None"', row?.currentText === 'None', row?.currentText);
    c.that('proposedText = invoice number', !!inv.invoiceNumber && row?.proposedText === inv.invoiceNumber, { proposedText: row?.proposedText, invoiceNumber: inv.invoiceNumber });
    const g = (await rt.api('GET', `/api/promises/${p.id}`, { token: T.admin })).json;
    c.that('promise still 3000 while pending', Number(g?.amount) === 3000, g?.amount);
    if (r.pendingChange) await rt.approvals.withdraw(T.mia, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), severity: 'low', evidence: `GET /api/approvals/${r.pendingChange?.id} changes ${JSON.stringify(d?.changes).slice(0, 500)}`, codeRef: CODE.views };
  });

  await tcase({ id: 'APP-D-31', feature: 'What the checker is shown', kind: 'API', prd: 'B2',
    title: 'A held two-line invoice create lists one line per item with the product name and the unit price',
    steps: 'fresh South customer; mia POST /api/invoices items [{E2E Widget, qty 150, unitPrice 100}, {E2E Widget, qty 2, unitPrice 250.5}] (15501 > 10000) -> held; admin GET /api/approvals/{id}',
    expected: 'TRIAGE r2 APP-R2-1: items proposedText "150 × E2E Widget at ₹100.00\\n2 × E2E Widget at ₹250.50"; no raw productId in any text' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const prod = w.product;
    const ic = await rt.createInvoice(T.mia, { customerId: cu.id, items: [{ productId: prod.id, quantity: 150, unitPrice: 100 }, { productId: prod.id, quantity: 2, unitPrice: 250.5 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('held INVOICE_CREATE', ic.held && ic.pendingChange?.operation === 'INVOICE_CREATE', brief(ic));
    c.that('measure 15501', Number(ic.pendingChange?.amount) === 15501, ic.pendingChange?.amount);
    const d = (await rt.approvals.get(T.admin, ic.pendingChange?.id)).json;
    const it = (d?.changes || []).find((x) => x.field === 'items');
    const want = `150 × ${prod.name} at ₹100.00\n2 × ${prod.name} at ₹250.50`;
    c.that('items proposedText exact', it?.proposedText === want, it?.proposedText);
    c.that('label "Items"', it?.label === 'Items', it?.label);
    if (ic.pendingChange) await rt.approvals.withdraw(T.mia, ic.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), severity: 'low', evidence: `items row ${JSON.stringify(it).slice(0, 400)}`, codeRef: CODE.views };
  });

  // ============================== Cumulative window ==============================
  await tcase({ id: 'APP-C-01', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'Two 3000 payments by the same maker to the same customer within 24 h: the second is held with a cumulative summary',
    steps: 'fresh South customer; mia POST /api/payments 3000 twice',
    expected: 'DESIGN §5.11.3 cumulative window / §6.6: first 200; second 202 whose summary says "with your other changes to <customer> in the last 24 hours this comes to ₹6,000.00"' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r1 = await heldPaymentBy(T.mia, cu, 3000);
    c.that('first 200', r1.status === 200, brief(r1));
    const r2 = await heldPaymentBy(T.mia, cu, 3000);
    c.that('second 202', r2.held, brief(r2));
    c.that('cumulative summary', /with your other changes to .* in the last 24 hours this comes to ₹6,000\.00/.test(r2.pendingChange?.summary || ''), r2.pendingChange?.summary);
    const pays = await paymentsOf(T.admin, cu.id);
    c.that('only the first payment exists', pays.length === 1, pays.length);
    w._c01 = { cu, pc: r2.pendingChange };
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-C-02', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'The window is per maker: another person\'s 3000 payment to the same customer applies',
    steps: 'after APP-C-01, sam POST /api/payments 3000 on the same customer', expected: '200 (sam has no recent changes to this customer)' }, async () => {
    const c = checks();
    const r = await heldPaymentBy(T.sam, w._c01.cu, 3000);
    c.that('200', r.status === 200, brief(r));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  }, { retry: false });

  await tcase({ id: 'APP-C-03', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'The maker\'s pending changes count too: a further 1000 payment by mia is held',
    steps: 'after APP-C-01 (mia: 3000 applied + 3000 pending), mia POST /api/payments 1000',
    expected: 'DESIGN §5.11.3: 1000 + 3000 recent + 3000 pending = 7000 > 5000 -> 202' }, async () => {
    const c = checks();
    const r = await heldPaymentBy(T.mia, w._c01.cu, 1000);
    c.that('202', r.held, brief(r));
    if (r.pendingChange) await rt.approvals.withdraw(T.mia, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  }, { retry: false });

  await tcase({ id: 'APP-C-07', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'Approved changes do not count towards the window: after the held 3000 is approved, a 1000 payment applies',
    steps: 'fresh South customer; mia pays 3000 (200) and 3000 (held); admin approves the held one; mia pays 1000',
    expected: 'DESIGN §5.11.3 "Approved changes are not counted (a checker has seen them)": 1000 + 3000 unapproved = 4000 <= 5000 -> 200' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r1 = await heldPaymentBy(T.mia, cu, 3000);
    const r2 = await heldPaymentBy(T.mia, cu, 3000);
    c.that('3000 applied then 3000 held', r1.status === 200 && r2.held, `${r1.status}/${r2.status}`);
    const a = await rt.approvals.approve(T.admin, r2.pendingChange?.id);
    c.that('approved', a.json?.status === 'APPROVED', brief(a).slice(0, 200));
    const r3 = await heldPaymentBy(T.mia, cu, 1000);
    c.that('1000 -> 200', r3.status === 200, brief(r3));
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalUsageLedger.java' };
  });

  await tcase({ id: 'APP-C-04', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'Two 6000 invoices by the same maker to one customer: the second is held (12000 > 10000)',
    steps: 'fresh South customer; mia POST /api/invoices 6000 twice', expected: 'first 200; second 202 with "this comes to ₹12,000.00"' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r1 = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 6000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const r2 = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 6000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('first 200', r1.status === 200, brief(r1));
    c.that('second 202 cumulative', r2.held && /₹12,000\.00/.test(r2.pendingChange?.summary || ''), r2.pendingChange?.summary || brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-C-05', feature: 'Splitting is caught', kind: 'API', prd: 'B2',
    title: 'A promise walked down in small PROMISE_UPDATE steps is caught once the steps add up past 1000',
    steps: 'admin promise 3000; mia PUT amount 2400 (delta 600); mia PUT amount 1800 (delta 600)',
    expected: 'DESIGN §5.11.3: first 200; second 202 (600 + 600 = 1200 > 1000)' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const body = (amount) => ({ amount, promisedDate: p.promisedDate, collectionPocUserId: p.collectionPocUserId ?? U.sam.id, notes: null, invoiceIds: null });
    const r1 = await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: body(2400) });
    c.that('first 200', r1.status === 200, brief(r1));
    const r2 = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: body(1800) }));
    c.that('second 202', r2.held, brief(r2));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  await tcase({ id: 'APP-C-06', feature: 'Per-region thresholds', kind: 'API', prd: 'B2',
    title: 'windowHours 0 means each change alone: West INVOICE_CREATE 5000/0h lets two 4000 invoices through, holds a 6000 one',
    steps: 'admin PUT West [INVOICE_CREATE 5000 windowHours 0]; wendy POST /api/invoices 4000, 4000, 6000 on one fresh West customer',
    expected: 'DESIGN §6.11 windowHours 0: 200, 200, 202' }, async () => {
    const c = checks();
    const put = await rt.setThresholds(T.admin, WEST, [{ operation: 'INVOICE_CREATE', amount: 5000, windowHours: 0 }]);
    c.that('PUT 200', put.status === 200, brief(put));
    const cu = await customerIn(w, 'WEST');
    const mk = (amount) => rt.createInvoice(T.wendy, { customerId: cu.id, amount, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.wendy.id });
    const a = await mk(4000); const b = await mk(4000); const d = await mk(6000);
    c.that('4000 -> 200', a.status === 200, brief(a));
    c.that('4000 again -> 200', b.status === 200, brief(b));
    c.that('6000 -> 202', d.held, brief(d));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.gate };
  });

  // ============================== Notifications ==============================
  await tcase({ id: 'APP-N-01', feature: 'Notifications to approvers', kind: 'API', prd: 'B2',
    title: 'A held change notifies the region\'s other approvers (APPROVAL_REQUESTED) and nobody else',
    steps: 'sam holds a 6000 payment on a fresh South customer; GET /api/notifications as admin, sam, nate, nora, mia',
    expected: 'DESIGN §5.11.11: admin gets APPROVAL_REQUESTED "Approval needed: Record payment" linking /approvals/{id}; sam (maker), nate (APPROVE North only), nora, mia get nothing for it' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.sam, cu);
    const link = `/approvals/${r.pendingChange?.id}`;
    const has = async (u) => (await notifications(T[u])).some((n) => n.type === 'APPROVAL_REQUESTED' && n.link === link);
    const adminN = (await notifications(T.admin)).find((n) => n.type === 'APPROVAL_REQUESTED' && n.link === link);
    c.that('admin notified', !!adminN, adminN && pick(adminN, ['type', 'title', 'link']));
    c.that('title "Approval needed: Record payment"', /Approval needed: Record payment/.test(adminN?.title || ''), adminN?.title);
    for (const u of ['sam', 'nate', 'nora', 'mia']) c.that(`${u} not notified`, !(await has(u)));
    w._n01 = { cu, pc: r.pendingChange };
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.notif };
  });

  await tcase({ id: 'APP-N-02', feature: 'Notifications to the maker', kind: 'API', prd: 'B2',
    title: 'The maker is told when the change is approved (link to the record) and when rejected (with the note)',
    steps: 'admin approves sam\'s held payment (APP-N-01); GET sam\'s notifications; sam\'s rejected invoice create (APP-D-06): GET sam\'s notifications',
    expected: 'DESIGN §5.11.11: sam APPROVAL_APPROVED linking /payments/{id}; sam APPROVAL_REJECTED with "Too large" in the message' }, async () => {
    const c = checks();
    const a = await rt.approvals.approve(T.admin, w._n01.pc.id);
    const link = `/payments/${a.json?.result?.entityId}`;
    const ns = await notifications(T.sam);
    c.that('sam APPROVAL_APPROVED to the payment', ns.some((n) => n.type === 'APPROVAL_APPROVED' && n.link === link), ns.slice(0, 4).map((n) => pick(n, ['type', 'link'])));
    const nm = await notifications(T.sam);
    const rej = nm.find((n) => n.type === 'APPROVAL_REJECTED' && n.link === `/approvals/${w._rejected?.id}`);
    c.that('sam APPROVAL_REJECTED with the note', !!rej && /Too large/.test(rej.message || ''), rej && pick(rej, ['type', 'message', 'link']));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.notif };
  }, { retry: false });

  // ============================== Region move with pending changes ==============================
  await tcase({ id: 'APP-M-01', feature: 'Region move cannot escape the gate', kind: 'API', prd: 'B2',
    title: 'Moving a customer with a pending change: refused without APPROVE in the source; with it the change is CLOSED and the maker told',
    steps: 'mia holds a 6000 payment on a fresh South customer; max (MANAGE South+North, no approve) POST /move-region to North; sam (APPROVE South, MANAGE North) POST /move-region to North',
    expected: 'DESIGN §6.4/§5.11.7: max 400 "… changes awaiting approval; they must be decided first"; sam 200 with moved.other.PendingChangeMoveParticipant 1; change CLOSED; mia gets APPROVAL_CLOSED' }, async () => {
    const c = checks();
    let max = await rt.findUser(T.admin, 'max');
    const grants = [{ regionId: SOUTH, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: NORTH, accessLevel: 'MANAGE', recordScope: 'ALL' }];
    if (!max) max = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'max', { username: 'max', fullName: 'Max Mover', grants });
    else await rt.setGrants(T.admin, max.id, grants);
    const maxT = await rt.login('max');
    const cu = await customerIn(w);
    const r = await heldPaymentBy(T.mia, cu);
    const m1 = await rt.moveCustomerRegion(maxT, cu.id, NORTH);
    c.that('max refused 400', m1.status === 400 && /awaiting approval/.test(m1.json?.message || ''), brief(m1));
    const m2 = await rt.moveCustomerRegion(T.sam, cu.id, NORTH);
    c.that('sam 200, 1 pending closed', m2.status === 200 && m2.json?.moved?.other?.PendingChangeMoveParticipant === 1, brief(m2).slice(0, 400));
    const g = await rt.approvals.get(T.admin, r.pendingChange?.id);
    c.that('change CLOSED', g.json?.status === 'CLOSED', g.json?.status);
    const nm = await notifications(T.mia);
    c.that('mia APPROVAL_CLOSED', nm.some((n) => n.type === 'APPROVAL_CLOSED' && n.link === `/approvals/${r.pendingChange?.id}`), nm.slice(0, 3).map((n) => n.type));
    w._m01 = cu;
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeMoveParticipant.java' };
  });

  await tcase({ id: 'APP-M-02', feature: 'Region move cannot escape the gate', kind: 'API', prd: 'B2',
    title: 'After the move to North (limit 50000), a 12000 invoice is still held at South\'s 10000 during the cool-down',
    steps: 'on the customer moved in APP-M-01: nora (MANAGE North) POST /api/invoices 12000',
    expected: 'DESIGN §5.11.3 effectiveThreshold: the stricter of current and previous region -> 202 with threshold 10000, regionId North' }, async () => {
    const c = checks();
    const cu = w._m01;
    const r = await rt.createInvoice(T.nora, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    c.that('202 at 10000', r.held && Number(r.pendingChange?.threshold) === 10000, brief(r));
    c.that('regionId North', r.pendingChange?.regionId === NORTH, r.pendingChange?.regionId);
    if (r.pendingChange) await rt.approvals.withdraw(T.nora, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/MakerChecker.java:139' };
  }, { retry: false });

  // ============================== Thresholds ==============================
  await tcase({ id: 'APP-TH-01', feature: 'Per-region thresholds', kind: 'API', prd: 'B2',
    title: 'GET thresholds lists all 8 amount-gated operations with South\'s values; North and West answer their own',
    steps: 'admin GET /api/regions/{South|North|West}/approval-thresholds',
    expected: 'DESIGN §6.11: 8 rows; South INVOICE_CREATE 10000/24h, PAYMENT_RECORD 5000; North INVOICE_CREATE 50000, PAYMENT_RECORD null; activeApprovers South = 2 (admin, sam); canEdit true' }, async () => {
    const c = checks();
    const s = await rt.getThresholds(T.admin, SOUTH);
    const n = await rt.getThresholds(T.admin, NORTH);
    const row = (g, op) => g.json?.thresholds?.find((t) => t.operation === op);
    c.that('8 rows', s.json?.thresholds?.length === 8, s.json?.thresholds?.map((t) => t.operation));
    c.that('South INVOICE_CREATE 10000/24', Number(row(s, 'INVOICE_CREATE')?.amount) === 10000 && row(s, 'INVOICE_CREATE')?.windowHours === 24, row(s, 'INVOICE_CREATE'));
    c.that('South PAYMENT_RECORD 5000', Number(row(s, 'PAYMENT_RECORD')?.amount) === 5000, row(s, 'PAYMENT_RECORD'));
    c.that('North INVOICE_CREATE 50000', Number(row(n, 'INVOICE_CREATE')?.amount) === 50000, row(n, 'INVOICE_CREATE'));
    c.that('North PAYMENT_RECORD not gated', row(n, 'PAYMENT_RECORD')?.amount == null, row(n, 'PAYMENT_RECORD'));
    c.that('South activeApprovers 2, canEdit', s.json?.activeApprovers === 2 && s.json?.canEdit === true, pick(s.json, ['activeApprovers', 'canEdit']));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-02', feature: 'Threshold permissions', kind: 'API', prd: 'B2',
    title: 'Only approvers / threshold managers of the region may read or change its limits',
    steps: 'GET South thresholds as nora (READ), mia (MANAGE), wendy (none); PUT South as nora, mia, wendy; GET region 999999 as admin',
    expected: 'DESIGN §6.11: GET 403 for nora, mia, wendy; PUT 403; unknown region 404' }, async () => {
    const c = checks();
    for (const u of ['nora', 'mia', 'wendy']) {
      const g = await rt.getThresholds(T[u], SOUTH);
      c.that(`${u} GET 403`, g.status === 403, brief(g));
      const p = await rt.setThresholds(T[u], SOUTH, [{ operation: 'INVOICE_CREATE', amount: 1 }]);
      c.that(`${u} PUT 403`, p.status === 403, brief(p));
    }
    const x = await rt.getThresholds(T.admin, 999999);
    c.that('unknown region 404', x.status === 404, brief(x));
    const s = await rt.getThresholds(T.admin, SOUTH);
    c.that('South INVOICE_CREATE unchanged 10000', Number(s.json?.thresholds?.find((t) => t.operation === 'INVOICE_CREATE')?.amount) === 10000);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalThresholdController.java:36' };
  }, { retry: false });

  await tcase({ id: 'APP-TH-03', feature: 'Threshold validation', kind: 'API', prd: 'B2',
    title: 'Bad threshold rows are refused with field errors and change nothing',
    steps: 'admin PUT South with: amount -1; windowHours 721; operation FOO; INVOICE_CREATE listed twice; amount 1.234',
    expected: 'DESIGN §6.11: 400 fieldErrors["thresholds[i].amount" | ".windowHours" | ".operation"]' }, async () => {
    const c = checks();
    const bad = [
      [[{ operation: 'INVOICE_CREATE', amount: -1 }], 'thresholds[0].amount'],
      [[{ operation: 'INVOICE_CREATE', amount: 100, windowHours: 721 }], 'thresholds[0].windowHours'],
      [[{ operation: 'FOO', amount: 100 }], 'thresholds[0].operation'],
      [[{ operation: 'INVOICE_CREATE', amount: 100 }, { operation: 'INVOICE_CREATE', amount: 200 }], 'thresholds[1].operation'],
      [[{ operation: 'INVOICE_CREATE', amount: 1.234 }], 'thresholds[0].amount'],
      [[{ operation: 'THRESHOLD_CHANGE', amount: 100 }], 'thresholds[0].operation'],
    ];
    for (const [rows, key] of bad) {
      const r = await rt.setThresholds(T.admin, SOUTH, rows);
      c.that(`${JSON.stringify(rows)} -> 400 ${key}`, r.status === 400 && r.json?.fieldErrors && key in r.json.fieldErrors, brief(r));
    }
    const s = await rt.getThresholds(T.admin, SOUTH);
    c.that('INVOICE_CREATE still 10000', Number(s.json?.thresholds?.find((t) => t.operation === 'INVOICE_CREATE')?.amount) === 10000);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-04', feature: 'Per-region thresholds', kind: 'API', prd: 'B2',
    title: 'Tightening a region\'s PAYMENT_RECORD applies at once and gates that region; with fewer than two approvers the reply warns',
    steps: 'admin PUT West [PAYMENT_RECORD 3000] (West approvers: admin, wade); wendy POST /api/payments 4000 on a fresh West customer; admin creates region Q<tag> (only admin approves there) and PUT [PAYMENT_RECORD 3000]',
    expected: 'DESIGN §5.11.11: West 200, no pendingChange, no warning (2 approvers); wendy 4000 -> 202 at West\'s 3000; new region 200 with warnings ["Only 1 person can approve changes in <name>, and nobody can approve their own changes"]' }, async () => {
    const c = checks();
    const r = await rt.setThresholds(T.admin, WEST, [{ operation: 'PAYMENT_RECORD', amount: 3000 }]);
    c.that('West 200 no pendingChange', r.status === 200 && r.json?.pendingChange == null, brief(r).slice(0, 300));
    c.that(`West warnings match ${r.json?.activeApprovers} approvers`, r.json?.activeApprovers >= 2 ? !(r.json?.warnings || []).length : (r.json?.warnings || []).length === 1, { approvers: r.json?.activeApprovers, warnings: r.json?.warnings });
    const cu = await customerIn(w, 'WEST');
    const p = await rt.createPayment(T.wendy, { customerId: cu.id, amount: 4000, collectionPocUserId: U.wendy.id });
    c.that('wendy 4000 -> 202 at 3000', p.held && p.pendingChange?.regionId === WEST && Number(p.pendingChange?.threshold) === 3000, brief(p));
    const code = ('Q' + Date.now().toString(36)).toUpperCase().slice(0, 10);
    const reg = await rt.createRegion(T.admin, { code, name: `Quiet ${code}` });
    const q = await rt.setThresholds(T.admin, reg.id, [{ operation: 'PAYMENT_RECORD', amount: 3000 }]);
    c.that('new region: 200, 1 approver, warning', q.status === 200 && q.json?.activeApprovers === 1 && (q.json?.warnings || []).some((x) => new RegExp(`Only 1 person can approve changes in Quiet ${code}`).test(x)), { status: q.status, approvers: q.json?.activeApprovers, warnings: q.json?.warnings });
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  let loosen1 = null;
  await tcase({ id: 'APP-TH-05', feature: 'Threshold loosening held', kind: 'API', prd: 'B2',
    title: 'Raising South\'s PAYMENT_RECORD 5000 -> 8000 is held for a second approver and does not take effect',
    steps: 'sam PUT South [PAYMENT_RECORD 8000]; GET thresholds; mia POST /api/payments 6000 on a fresh South customer',
    expected: 'DESIGN §5.11.11/§6.11: 202, Location /api/approvals/{id}, body.pendingChange{operation THRESHOLD_CHANGE, entityType REGION}; PAYMENT_RECORD still 5000; 6000 payment still held' }, async () => {
    const c = checks();
    const r = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'PAYMENT_RECORD', amount: 8000 }]);
    c.that('202', r.status === 202, brief(r).slice(0, 300));
    c.that('THRESHOLD_CHANGE on REGION', r.json?.pendingChange?.operation === 'THRESHOLD_CHANGE' && r.json?.pendingChange?.entityType === 'REGION', r.json?.pendingChange && pick(r.json.pendingChange, ['id', 'operation', 'entityType', 'summary']));
    c.that('Location', r.headers.location === `/api/approvals/${r.json?.pendingChange?.id}`, r.headers.location);
    const row = r.json?.thresholds?.find((t) => t.operation === 'PAYMENT_RECORD');
    c.that('PAYMENT_RECORD still 5000 in the reply', Number(row?.amount) === 5000, row);
    const cu = await customerIn(w);
    const p = await heldPaymentBy(T.mia, cu);
    c.that('6000 payment still held', p.held, brief(p));
    if (p.pendingChange) await rt.approvals.withdraw(T.mia, p.pendingChange.id);
    loosen1 = r.json?.pendingChange;
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-06', feature: 'Threshold loosening held', kind: 'API', prd: 'B2',
    title: 'The person who loosened the limit cannot approve it; another person\'s competing loosening is 409',
    steps: 'sam POST /api/approvals/{threshold change}/approve; admin PUT South [INVOICE_CREATE 20000]',
    expected: 'DESIGN §5.11.11: self-approval 400; admin 409 (another person\'s THRESHOLD_CHANGE outstanding); INVOICE_CREATE still 10000' }, async () => {
    const c = checks();
    const a = await rt.approvals.approve(T.sam, loosen1?.id);
    c.that('self-approval 400', a.status === 400, brief(a));
    const p = await rt.setThresholds(T.admin, SOUTH, [{ operation: 'INVOICE_CREATE', amount: 20000 }]);
    c.that('competing loosening 409', p.status === 409, brief(p));
    const s = await rt.getThresholds(T.admin, SOUTH);
    c.that('INVOICE_CREATE still 10000', Number(s.json?.thresholds?.find((t) => t.operation === 'INVOICE_CREATE')?.amount) === 10000);
    c.that('GET shows the pending loosening', s.json?.pendingChange?.id === loosen1?.id, s.json?.pendingChange?.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-07', feature: 'Notifications to approvers', kind: 'API', prd: 'B2',
    title: 'A held loosening is announced to the region\'s other approvers',
    steps: 'GET /api/notifications as admin and nora after sam\'s loosening',
    expected: 'DESIGN §5.11.11: admin has APPROVAL_REQUESTED for /approvals/{id} and APPROVAL_THRESHOLDS_CHANGED; nora has neither' }, async () => {
    const c = checks();
    const na = await notifications(T.admin);
    c.that('admin APPROVAL_REQUESTED', na.some((n) => n.type === 'APPROVAL_REQUESTED' && n.link === `/approvals/${loosen1?.id}`), na.slice(0, 5).map((n) => pick(n, ['type', 'link'])));
    c.that('admin APPROVAL_THRESHOLDS_CHANGED', na.some((n) => n.type === 'APPROVAL_THRESHOLDS_CHANGED'), na.slice(0, 5).map((n) => n.type));
    const nn = await notifications(T.nora);
    c.that('nora none', !nn.some((n) => n.link === `/approvals/${loosen1?.id}` || n.type === 'APPROVAL_THRESHOLDS_CHANGED'));
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  let loosen2 = null;
  await tcase({ id: 'APP-TH-08', feature: 'Threshold loosening held', kind: 'API', prd: 'B2',
    title: 'The same maker\'s second loosening supersedes the first',
    steps: 'sam PUT South [PAYMENT_RECORD 9000]; GET the first change', expected: 'DESIGN §5.11.11: 202 with pendingChange.supersededId = first id; the first is SUPERSEDED' }, async () => {
    const c = checks();
    const r = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'PAYMENT_RECORD', amount: 9000 }]);
    c.that('202', r.status === 202, brief(r).slice(0, 200));
    const pc = r.json?.pendingChange;
    const g = await rt.approvals.get(T.admin, loosen1?.id);
    c.that('first SUPERSEDED', g.json?.status === 'SUPERSEDED' && g.json?.supersededById === pc?.id, pick(g.json, ['status', 'supersededById']));
    const d = await rt.approvals.get(T.admin, pc?.id);
    c.that('new detail links the old (supersededId in the held brief or detail)', pc?.supersededId === loosen1?.id || g.json?.supersededById === pc?.id, pick(pc, ['id', 'supersededId']));
    loosen2 = pc;
    return { ok: c.ok, actual: c.summary(), evidence: `detail ${brief(d).slice(0, 300)}`, codeRef: CODE.hold };
  }, { retry: false });

  await tcase({ id: 'APP-TH-09', feature: 'Threshold loosening approved', kind: 'API', prd: 'B2',
    title: 'Another approver approves the loosening: 9000 takes effect and a 6000 payment now applies',
    steps: 'admin POST /api/approvals/{loosening}/approve; GET thresholds; mia POST /api/payments 6000 on a fresh South customer; sam PUT PAYMENT_RECORD 5000 (restore)',
    expected: 'DESIGN §5.11.11: APPROVED with result.link "/approval-thresholds?regionId={South}"; PAYMENT_RECORD 9000; payment 200; restoring 5000 is a tightening -> 200' }, async () => {
    const c = checks();
    const a = await rt.approvals.approve(T.admin, loosen2?.id);
    c.that('APPROVED', a.status === 200 && a.json?.status === 'APPROVED', brief(a).slice(0, 300));
    c.that('result link to thresholds', a.json?.result?.link === `/approval-thresholds?regionId=${SOUTH}`, a.json?.result);
    const s = await rt.getThresholds(T.admin, SOUTH);
    c.that('PAYMENT_RECORD 9000', Number(s.json?.thresholds?.find((t) => t.operation === 'PAYMENT_RECORD')?.amount) === 9000);
    const cu = await customerIn(w);
    const p = await heldPaymentBy(T.mia, cu);
    c.that('6000 payment now 200', p.status === 200, brief(p));
    const back = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'PAYMENT_RECORD', amount: 5000 }]);
    c.that('restore 5000 -> 200', back.status === 200, brief(back).slice(0, 200));
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ThresholdChangeApplier.java' };
  }, { retry: false });

  await tcase({ id: 'APP-TH-10', feature: 'Threshold loosening held', kind: 'API', prd: 'B2',
    title: 'A mixed request applies its tightening part and holds its loosening part',
    steps: 'sam PUT South [PROMISE_CREATE 4000 (tighten), INVOICE_CREATE 15000 (loosen)]; GET; sam withdraws the held part',
    expected: 'DESIGN §6.11: 202; PROMISE_CREATE 4000 applied; INVOICE_CREATE still 10000; the pending change covers Create invoice only' }, async () => {
    const c = checks();
    const r = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'PROMISE_CREATE', amount: 4000 }, { operation: 'INVOICE_CREATE', amount: 15000 }]);
    c.that('202', r.status === 202, brief(r).slice(0, 200));
    const s = await rt.getThresholds(T.admin, SOUTH);
    const row = (op) => s.json?.thresholds?.find((t) => t.operation === op);
    c.that('PROMISE_CREATE 4000 applied', Number(row('PROMISE_CREATE')?.amount) === 4000, row('PROMISE_CREATE'));
    c.that('INVOICE_CREATE still 10000', Number(row('INVOICE_CREATE')?.amount) === 10000, row('INVOICE_CREATE'));
    const sum = r.json?.pendingChange?.summary || '';
    c.that('pending covers Create invoice only', /Create invoice/.test(sum) && !/Create promise/.test(sum), sum);
    if (r.json?.pendingChange) await rt.approvals.withdraw(T.sam, r.json.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-11', feature: 'Threshold loosening held', kind: 'API', prd: 'B2',
    title: 'Removing a limit (amount null) and shortening a window are loosening too; a rejected removal leaves the limit',
    steps: 'sam PUT South [INVOICE_CANCEL null] -> admin rejects; sam PUT South [INVOICE_CREATE 10000 windowHours 1] -> sam withdraws',
    expected: 'DESIGN §5.11.11: both 202; INVOICE_CANCEL still 5000 after rejection; INVOICE_CREATE window still 24' }, async () => {
    const c = checks();
    const r = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'INVOICE_CANCEL', amount: null }]);
    c.that('removal 202', r.status === 202, brief(r).slice(0, 200));
    const rj = await rt.approvals.reject(T.admin, r.json?.pendingChange?.id, 'keep the limit');
    c.that('rejected', rj.json?.status === 'REJECTED', brief(rj).slice(0, 200));
    const w1 = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'INVOICE_CREATE', amount: 10000, windowHours: 1 }]);
    c.that('shorter window 202', w1.status === 202, brief(w1).slice(0, 200));
    const s = await rt.getThresholds(T.admin, SOUTH);
    const row = (op) => s.json?.thresholds?.find((t) => t.operation === op);
    c.that('INVOICE_CANCEL still 5000', Number(row('INVOICE_CANCEL')?.amount) === 5000, row('INVOICE_CANCEL'));
    c.that('INVOICE_CREATE window still 24', row('INVOICE_CREATE')?.windowHours === 24, row('INVOICE_CREATE'));
    if (w1.json?.pendingChange) await rt.approvals.withdraw(T.sam, w1.json.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: CODE.thr };
  }, { retry: false });

  await tcase({ id: 'APP-TH-12', feature: 'Per-region thresholds', kind: 'API', prd: 'B2',
    title: 'A new region starts with a copy of the default region\'s limits',
    steps: 'admin PUT DEFAULT [INVOICE_CREATE 70000]; admin POST /api/regions {code:"E<tag>"}; GET its thresholds',
    expected: 'DESIGN §5.11.11 / §6.3: the new region has INVOICE_CREATE 70000' }, async () => {
    const c = checks();
    const d = await rt.setThresholds(T.admin, w.regions.DEFAULT.id, [{ operation: 'INVOICE_CREATE', amount: 70000 }]);
    c.that('DEFAULT PUT 200', d.status === 200, brief(d).slice(0, 200));
    const code = ('E' + Date.now().toString(36)).toUpperCase().slice(0, 10);
    const reg = await rt.createRegion(T.admin, { code, name: `East ${code}` });
    const g = await rt.getThresholds(T.admin, reg.id);
    const row = g.json?.thresholds?.find((t) => t.operation === 'INVOICE_CREATE');
    c.that('copied INVOICE_CREATE 70000', Number(row?.amount) === 70000, row);
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalThresholdService.java:213' };
  }, { retry: false });
};
