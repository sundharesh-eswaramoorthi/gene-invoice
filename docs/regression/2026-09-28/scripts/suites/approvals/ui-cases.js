// UI cases for B2 maker-checker (Flutter web through the semantics tree). Fixtures come from the API.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, invoiceBy, promiseBy, invoicesOf, paymentsOf, promisesOf, notifications, brief, fill, openAs, waitText, allText } = H;

const FE = {
  reply: 'frontend/lib/core/api/pending_reply.dart:59',
  banner: 'frontend/lib/features/approvals/pending_banner.dart',
  detail: 'frontend/lib/features/approvals/approval_detail_screen.dart',
  list: 'frontend/lib/features/approvals/approvals_screen.dart',
  thr: 'frontend/lib/features/approvals/approval_thresholds_screen.dart:399',
  pay: 'frontend/lib/features/payments/record_payment_dialog.dart:135',
  invForm: 'frontend/lib/features/invoices/invoice_form_screen.dart:194',
  invDetail: 'frontend/lib/features/invoices/invoice_detail_screen.dart:150',
  invList: 'frontend/lib/features/invoices/invoices_screen.dart:276',
  bulk: 'frontend/lib/core/table/data_table_scaffold.dart:642',
  promise: 'frontend/lib/features/promises/promise_form_dialog.dart:179',
  override: 'frontend/lib/features/promises/promise_form_dialog.dart:464',
  dispute: 'frontend/lib/features/disputes/dispute_detail_screen.dart:109',
};

module.exports = async function uiCases(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  const U = w.users;
  const SOUTH = w.regions.SOUTH.id;
  const s1 = { ...w.customers.s1, pocId: U.sam.id };

  /** Opens the app, runs body(app, c), always screenshots and closes; adds page/API errors as a no-crash check. */
  const ui = async (id, attempt, user, route, body, { allowStatuses = [], height = 900 } = {}) => {
    const c = checks();
    const app = await openAs(user, route, { height });
    let shot = '';
    try {
      await body(app, c);
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 700));
    } finally {
      try { shot = await rt.shot(app.page, R.shotsDir, `${id}-${attempt}`); } catch { /* ignore */ }
      const errs = app.apiErrors.filter((x) => !allowStatuses.includes(x.status));
      c.that('no page exception (no crash)', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no unexpected API error', errs.length === 0, errs.slice(0, 5));
      await app.close();
    }
    return { c, shot, writes: app.requests.filter((r) => r.method !== 'GET') };
  };
  const result = (x, codeRef, evidence = '') => ({ ok: x.c.ok, actual: x.c.summary(), screenshot: x.shot, codeRef,
    evidence: `${evidence}${evidence ? '; ' : ''}writes=${JSON.stringify(x.writes).slice(0, 400)}` });
  const heldPayment = async (tok, cu, amount = 7000) => rt.createPayment(tok, { customerId: cu.id, amount, collectionPocUserId: U.sam.id });
  const hashOf = (page) => page.evaluate(() => location.hash);

  // ---------------------------------------------------------------- approvals list, detail, decisions
  let uiPay = null;
  await tcase({ id: 'APP-UI-01', feature: 'Approvals list', kind: 'UI', prd: 'B2',
    title: 'An approver\'s Approvals page lists the held change with status, amount against limit and requester',
    steps: 'mia holds a 7000 payment on a fresh South customer; admin opens /approvals (default "Awaiting my approval")',
    expected: 'DESIGN §7.7.1: row with "Awaiting approval", "Record payment of ₹7,000.00 from <customer>", ₹7,000.00, limit ₹5,000.00, "Mia Maker"; summary tiles' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await heldPayment(T.mia, cu);
    uiPay = { cu, pc: h.pendingChange };
    const x = await ui('APP-UI-01', attempt, 'admin', '/approvals', async ({ page }, c) => {
      const row = await waitText(page, new RegExp(`Record payment of ₹7,000\\.00 from ${cu.name}`), 10000);
      c.that('row with the summary', !!row, (await allText(page)).slice(0, 600));
      const t = await allText(page);
      c.that('Awaiting me tile and Mia Maker in the list', /Awaiting me/.test(t) && /Mia Maker/.test(t) && /₹5,000\.00/.test(t), t.slice(0, 400));
    });
    return result(x, FE.list, `pendingChange ${h.pendingChange?.id}`);
  });

  await tcase({ id: 'APP-UI-02', feature: 'Decision flags in the UI', kind: 'UI', prd: 'B2',
    title: 'The maker sees "You requested this change" with Withdraw, and no Approve/Reject',
    steps: 'mia opens /approvals/{id} of her held payment', expected: 'DESIGN §7.7.1: buttons only per server flags: Withdraw shown; Approve/Reject hidden; blockedReason shown' }, async (attempt) => {
    const x = await ui('APP-UI-02', attempt, 'mia', `/approvals/${uiPay.pc.id}`, async ({ page }, c) => {
      c.that('"You requested this change"', !!(await waitText(page, 'You requested this change')));
      c.that('Withdraw shown', await rt.exists(page, 'Withdraw', { role: 'button' }));
      c.that('no Approve / Reject', !(await rt.exists(page, 'Approve', { role: 'button' })) && !(await rt.exists(page, 'Reject', { role: 'button' })));
    });
    return result(x, FE.detail);
  });

  await tcase({ id: 'APP-UI-03', feature: 'Decision flags in the UI', kind: 'UI', prd: 'B2',
    title: 'A READ viewer (nora) sees the change but no decision buttons, and why',
    steps: 'nora opens /approvals/{id}', expected: 'no Approve/Reject/Withdraw; hint "You don\'t hold the approval right in South"' }, async (attempt) => {
    const x = await ui('APP-UI-03', attempt, 'nora', `/approvals/${uiPay.pc.id}`, async ({ page }, c) => {
      c.that('hint shown', !!(await waitText(page, /approval right in South/)), (await allText(page)).slice(0, 400));
      for (const b of ['Approve', 'Reject', 'Withdraw']) c.that(`no ${b}`, !(await rt.exists(page, b, { role: 'button' })));
    });
    return result(x, FE.detail);
  });

  await tcase({ id: 'APP-UI-03b', feature: 'Self-approval refused', kind: 'UI', prd: 'B2',
    title: 'A maker who holds APPROVE in the region (sam) gets no Approve button on his own change',
    steps: 'sam holds a 7000 payment on a fresh South customer; sam opens /approvals/{id}',
    expected: 'PRD B2 "someone else"; DESIGN §7.7.1: no Approve/Reject; "You requested this change"; Withdraw shown' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await heldPayment(T.sam, cu);
    const x = await ui('APP-UI-03b', attempt, 'sam', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      c.that('"You requested this change"', !!(await waitText(page, 'You requested this change')));
      c.that('no Approve / Reject', !(await rt.exists(page, 'Approve', { role: 'button' })) && !(await rt.exists(page, 'Reject', { role: 'button' })));
      c.that('Withdraw shown', await rt.exists(page, 'Withdraw', { role: 'button' }));
    });
    await rt.approvals.withdraw(T.sam, h.pendingChange?.id);
    return result(x, FE.detail);
  });

  await tcase({ id: 'APP-UI-04', feature: 'Approve in the UI', kind: 'UI', prd: 'B2',
    title: 'Admin approves the held payment from its page: it shows Approved and the payment exists',
    steps: 'admin opens /approvals/{id}; taps Approve; types a note; taps Approve in the dialog',
    expected: 'DESIGN §7.7.1: status chip "Approved", an "Open …" link; API status APPROVED; the 7000 payment exists' }, async (attempt) => {
    let cu = uiPay.cu; let pc = uiPay.pc;
    if (attempt > 1) { cu = await customerIn(w); pc = (await heldPayment(T.mia, cu)).pendingChange; }
    const x = await ui('APP-UI-04', attempt, 'admin', `/approvals/${pc.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Approve', { role: 'button', wait: 1200 });
      await fill(page, 'Note (optional)', 'ok from UI');
      const btns = (await rt.semantics(page)).filter((n) => n.text === 'Approve' || n.label === 'Approve');
      await rt.tap(page, 'Approve', { nth: btns.length - 1, wait: 3000 });
      c.that('status Approved shown', !!(await waitText(page, 'Approved', 8000)), (await allText(page)).slice(0, 400));
      c.that('Open link shown', await rt.exists(page, /^Open /, { role: 'button' }));
    });
    const g = await rt.approvals.get(T.admin, pc.id);
    x.c.that('API APPROVED with note', g.json?.status === 'APPROVED' && g.json?.decisionNote === 'ok from UI', g.json && { status: g.json.status, note: g.json.decisionNote });
    const pays = await paymentsOf(T.admin, cu.id);
    x.c.that('payment exists', pays.length === 1 && Number(pays[0].amount) === 7000, pays.length);
    return result(x, FE.detail);
  });

  await tcase({ id: 'APP-UI-05', feature: 'Reject in the UI', kind: 'UI', prd: 'B2',
    title: 'Reject needs a note in the UI; with one the change shows Rejected and nothing is created',
    steps: 'mia holds a 12000 invoice create; admin opens it, taps Reject, taps Reject with no note, types a note, taps Reject',
    expected: 'DESIGN §7.7.1 Reject (note 1–500 required): dialog stays open without a note; then "Rejected"; API REJECTED; no invoice' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const x = await ui('APP-UI-05', attempt, 'admin', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Reject', { role: 'button', wait: 1200 });
      const btns = async () => (await rt.semantics(page)).filter((n) => n.text === 'Reject' || n.label === 'Reject');
      await rt.tap(page, 'Reject', { nth: (await btns()).length - 1, wait: 1500 });
      c.that('dialog still open without a note', await rt.exists(page, 'Why? (required)', { role: 'textbox' }));
      await fill(page, 'Why? (required)', 'Too big for one invoice');
      await rt.tap(page, 'Reject', { nth: (await btns()).length - 1, wait: 3000 });
      c.that('status Rejected shown', !!(await waitText(page, 'Rejected', 8000)), (await allText(page)).slice(0, 400));
    }, { allowStatuses: [400] });
    const g = await rt.approvals.get(T.admin, h.pendingChange?.id);
    x.c.that('API REJECTED', g.json?.status === 'REJECTED', g.json?.status);
    x.c.that('no invoice', (await invoicesOf(T.admin, cu.id)).length === 0);
    return result(x, FE.detail);
  });

  await tcase({ id: 'APP-UI-06', feature: 'Withdraw in the UI', kind: 'UI', prd: 'B2',
    title: 'The maker withdraws a held change from its page',
    steps: 'mia holds a 7000 payment; mia opens /approvals/{id}, taps Withdraw, confirms Withdraw',
    expected: 'DESIGN §7.7.1: status "Withdrawn"; API WITHDRAWN; no payment' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await heldPayment(T.mia, cu);
    const x = await ui('APP-UI-06', attempt, 'mia', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Withdraw', { role: 'button', wait: 1200 });
      const n = (await rt.semantics(page)).filter((z) => z.text === 'Withdraw' || z.label === 'Withdraw');
      await rt.tap(page, 'Withdraw', { nth: n.length - 1, wait: 3000 });
      c.that('status Withdrawn shown', !!(await waitText(page, 'Withdrawn', 8000)), (await allText(page)).slice(0, 400));
    });
    const g = await rt.approvals.get(T.admin, h.pendingChange?.id);
    x.c.that('API WITHDRAWN', g.json?.status === 'WITHDRAWN', g.json?.status);
    x.c.that('no payment', (await paymentsOf(T.admin, cu.id)).length === 0);
    return result(x, FE.detail);
  });

  // ---------------------------------------------------------------- visible to everyone who can see the record
  await tcase({ id: 'APP-UI-07', feature: 'Pending banner and list chip', kind: 'UI', prd: 'B2',
    title: 'A READ viewer sees the "Awaiting approval" banner on the invoice (still Unpaid), its View link, and the chip on the list row',
    steps: 'mia holds a cancel on an 8000 South invoice; nora opens /invoices/{id}; taps View; opens /invoices?f=customerId:eq:{c}',
    expected: 'PRD B2 visible to everyone who can see the record; DESIGN §7.7.1 banner "Awaiting approval: Cancel invoice … · requested by Mia Maker on …" with View -> /approvals/{id}; TRIAGE default: "Awaiting approval" chip on list rows' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const h = await rt.cancelInvoice(T.mia, inv.id);
    const x = await ui('APP-UI-07', attempt, 'nora', `/invoices/${inv.id}`, async ({ page }, c) => {
      const b = await waitText(page, /^Awaiting approval: Cancel invoice .*requested by Mia Maker/, 10000);
      c.that('banner shown', !!b, (await allText(page)).slice(0, 500));
      c.that('invoice still Unpaid', await rt.exists(page, 'Unpaid'));
      await rt.shot(page, R.shotsDir, `APP-UI-07-${attempt}-banner`);
      await rt.tap(page, 'View', { role: 'button', wait: 3000 });
      const hash = await hashOf(page);
      c.that('View opens the approval', hash === `#/approvals/${h.pendingChange?.id}`, hash);
      await rt.go(page, `/invoices?f=customerId:eq:${cu.id}`, 4500);
      const t = await allText(page);
      c.that('list row has the "Awaiting approval" chip', new RegExp(`${inv.invoiceNumber}`).test(t) && /Awaiting approval/.test(t), t.slice(0, 500));
    });
    return result(x, FE.banner);
  });

  await tcase({ id: 'APP-UI-08', feature: 'Pending banner and list chip', kind: 'UI', prd: 'B2',
    title: 'A held invoice create shows as a banner on the customer\'s page for a READ viewer',
    steps: 'mia holds a 12000 invoice create on a fresh South customer; nora opens /customers/{id}',
    expected: 'DESIGN §7.7.1: "Awaiting approval: Create invoice for <customer> (₹12,000.00) · requested by Mia Maker …"' }, async (attempt) => {
    const cu = await customerIn(w);
    await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const x = await ui('APP-UI-08', attempt, 'nora', `/customers/${cu.id}`, async ({ page }, c) => {
      c.that('banner shown', !!(await waitText(page, /^Awaiting approval: Create invoice for .*₹12,000\.00.*Mia Maker/, 10000)), (await allText(page)).slice(0, 500));
    });
    return result(x, FE.banner);
  });

  await tcase({ id: 'APP-UI-09', feature: 'Pending hidden from non-viewers', kind: 'UI', prd: 'B2',
    title: 'wendy (West only) does not see South\'s held changes on her Approvals page, and cannot open the change',
    steps: 'with mia\'s held South payment from APP-UI-01 pending or decided, and a fresh held one: wendy opens /approvals, taps "All I can see"; opens /approvals/{id}',
    expected: 'DESIGN §5.11.9: no South rows; the detail is not found' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await heldPayment(T.mia, cu);
    const x = await ui('APP-UI-09', attempt, 'wendy', '/approvals', async ({ page }, c) => {
      if (await rt.exists(page, 'All I can see')) await rt.tap(page, 'All I can see', { wait: 3000 });
      const t = await allText(page);
      c.that('no South customer in the list', !t.includes(cu.name) && !/South/.test(t.replace(/Regions: [^|]*/, '')), t.slice(0, 400));
      await rt.go(page, `/approvals/${h.pendingChange?.id}`, 4000);
      const t2 = await allText(page);
      c.that('detail not shown', !t2.includes(cu.name) && !/Approve|Withdraw/.test(t2), t2.slice(0, 300));
    }, { allowStatuses: [404] });
    await rt.approvals.withdraw(T.mia, h.pendingChange?.id);
    return result(x, FE.list);
  });

  // ---------------------------------------------------------------- 202 handling at every write site (§7.6)
  await tcase({ id: 'APP-UI-10', feature: '202 handling: record payment', kind: 'UI', prd: 'B2',
    title: 'Recording a 7000 payment in the dialog shows "Sent for approval" with View, and records nothing',
    steps: 'mia: /payments -> Record payment -> pick a fresh South customer -> Amount 7000 -> Record',
    expected: 'DESIGN §7.6: SnackBar "Sent for approval: Record payment of ₹7,000.00 from <customer>" with View; no crash; no payment row; the change is pending' }, async (attempt) => {
    const cu = await customerIn(w);
    const x = await ui('APP-UI-10', attempt, 'mia', '/payments', async ({ page }, c) => {
      await rt.tap(page, /^Record payment$/, { role: 'button', wait: 2500 });
      await rt.tap(page, /^Customer \*/, { wait: 1500 });
      await fill(page, 'Search by name', cu.name);
      await page.waitForTimeout(1500);
      await rt.tap(page, new RegExp(`^${cu.name}`), { role: 'button', wait: 3500 });
      await fill(page, 'Amount *', '7000');
      await rt.tap(page, 'Record', { role: 'button', wait: 300 });
      const s = await waitText(page, new RegExp(`^Sent for approval: Record payment of ₹7,000\\.00 from ${cu.name}`), 8000);
      c.that('snackbar "Sent for approval: …"', !!s, (await allText(page)).slice(-400));
      c.that('View action', await rt.exists(page, 'View', { role: 'button' }));
    });
    x.c.that('no payment', (await paymentsOf(T.admin, cu.id)).length === 0);
    const mine = await rt.list(T.mia, '/api/approvals', { view: 'MINE' });
    x.c.that('pending change exists', (mine.json?.content || []).some((b) => b.customerId === cu.id && b.status === 'PENDING' && b.operation === 'PAYMENT_RECORD'));
    return result(x, FE.pay);
  });

  await tcase({ id: 'APP-UI-11', feature: '202 handling: new invoice', kind: 'UI', prd: 'B2',
    title: 'Creating a 15000 invoice in the form opens the held change and says so, creating nothing',
    steps: 'mia: /invoices/new -> customer -> product E2E Widget -> Qty 150 -> Create invoice',
    expected: 'DESIGN §7.6: navigates to /approvals/{id} ("Approval #…", "Awaiting approval"); SnackBar "Sent for approval: Create invoice …"; no invoice' }, async (attempt) => {
    const cu = await customerIn(w);
    const x = await ui('APP-UI-11', attempt, 'mia', '/invoices/new', async ({ page }, c) => {
      await rt.tap(page, /^Customer \*/, { wait: 1500 });
      await fill(page, 'Search by name', cu.name);
      await page.waitForTimeout(1500);
      await rt.tap(page, new RegExp(`^${cu.name}`), { role: 'button', wait: 2500 });
      await rt.tap(page, /^Product\n/, { wait: 1500 }); // r2: /^Product/ also matched the rail's 'Products' (rail semantics restored by F3)
      await rt.tap(page, /^E2E Widget/, { wait: 1500 });
      await fill(page, 'Qty', '150');
      await rt.tap(page, 'Create invoice', { role: 'button', wait: 300 });
      const s = await waitText(page, /^Sent for approval: Create invoice for /, 8000);
      c.that('snackbar', !!s, (await allText(page)).slice(-400));
      const hash = await hashOf(page);
      c.that('on the approval page', /^#\/approvals\/\d+$/.test(hash) && await rt.exists(page, /^Approval #\d+/), hash);
    });
    x.c.that('no invoice', (await invoicesOf(T.admin, cu.id)).length === 0);
    return result(x, FE.invForm);
  });

  await tcase({ id: 'APP-UI-12', feature: '202 handling: cancel from the list', kind: 'UI', prd: 'B2',
    title: 'Cancelling an 8000 invoice from the list says "Cancellation sent for approval" and flags the row',
    steps: 'mia: /invoices?f=customerId:eq:{c} -> Cancel invoice -> confirm',
    expected: 'DESIGN §7.6: SnackBar "Cancellation sent for approval" with View; row shows "Awaiting approval"; invoice UNPAID' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const x = await ui('APP-UI-12', attempt, 'mia', `/invoices?f=customerId:eq:${cu.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Cancel invoice', { role: 'button', wait: 1200 });
      c.that('dialog for this invoice', await rt.exists(page, `Cancel ${inv.invoiceNumber}?`));
      const n = (await rt.semantics(page)).filter((z) => z.text === 'Cancel invoice' || z.label === 'Cancel invoice');
      await rt.tap(page, 'Cancel invoice', { nth: n.length - 1, wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^Cancellation sent for approval/, 8000)), (await allText(page)).slice(-300));
      await page.waitForTimeout(2000);
      c.that('row flagged Awaiting approval', /Awaiting approval/.test(await allText(page)));
    });
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    x.c.that('API still UNPAID', g.json?.status === 'UNPAID', g.json?.status);
    return result(x, FE.invList);
  });

  await tcase({ id: 'APP-UI-13', feature: '202 handling: bulk cancel', kind: 'UI', prd: 'B2',
    title: 'Bulk "Cancel unpaid" of an 8000 invoice reports "0 updated, 1 sent for approval"',
    steps: 'mia: /invoices?f=customerId:eq:{c} -> tick the row -> Cancel unpaid -> Confirm',
    expected: 'DESIGN §7.6: quick snack "0 updated, 1 sent for approval"; invoice UNPAID with a pending cancel' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const x = await ui('APP-UI-13', attempt, 'mia', `/invoices?f=customerId:eq:${cu.id}`, async ({ page }, c) => {
      const cell = await rt.find(page, inv.invoiceNumber);
      await rt.clickAt(page, 121, cell.y, 1500);
      await rt.tap(page, 'Cancel unpaid', { role: 'button', wait: 1500 });
      await rt.tap(page, 'Confirm', { wait: 300 });
      c.that('snack "0 updated, 1 sent for approval"', !!(await waitText(page, /0 updated, 1 sent for approval/, 8000)), (await allText(page)).slice(-300));
    });
    const f = await rt.approvals.forRecord(T.admin, 'INVOICE', inv.id);
    x.c.that('pending cancel exists', Array.isArray(f.json) && f.json.some((b) => b.operation === 'INVOICE_CANCEL'), brief(f).slice(0, 200));
    return result(x, FE.bulk);
  });

  await tcase({ id: 'APP-UI-14', feature: '202 handling: invoice terms edit', kind: 'UI', prd: 'B2',
    title: 'Changing an invoice to Net 90 on its page is sent for approval and the fields go back to the live values',
    steps: 'mia: /invoices/{2500 invoice due +20d} -> Payment terms: Net 90 -> Save changes',
    expected: 'DESIGN §7.6 (G18): SnackBar "Sent for approval: …"; the due date shown is the live one again; Save disabled; banner appears; API dueDate unchanged' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    const x = await ui('APP-UI-14', attempt, 'mia', `/invoices/${inv.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Custom', { role: 'button', wait: 1200 });
      await rt.press(page, 'Net 90', { wait: 1500 });
      await rt.tap(page, 'Save changes', { role: 'button', wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^Sent for approval: /, 8000)), (await allText(page)).slice(-300));
      await page.waitForTimeout(2500);
      const t = await allText(page);
      c.that('due date back to the live value', t.includes(inv.dueDate) && !t.includes('Net 90'), t.slice(0, 600));
      c.that('banner shown', /Awaiting approval: /.test(t));
    });
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin });
    x.c.that('API dueDate unchanged', g.json?.dueDate === inv.dueDate, g.json?.dueDate);
    return result(x, FE.invDetail);
  });

  await tcase({ id: 'APP-UI-15', feature: '202 handling: raise promise', kind: 'UI', prd: 'B2',
    title: 'Raising a 6000 promise from an invoice is sent for approval and creates nothing',
    steps: 'mia: /invoices/{id} -> Payment Promise tab -> Raise promise -> Promised amount 6000 -> Raise promise',
    expected: 'DESIGN §7.6: SnackBar "Sent for approval: …"; email step skipped; no promise' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    const x = await ui('APP-UI-15', attempt, 'mia', `/invoices/${inv.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Payment Promise', { wait: 1500 });
      await rt.tap(page, 'Raise promise', { role: 'button', wait: 2500 });
      await fill(page, 'Promised amount *', '6000');
      await rt.tap(page, 'Raise promise', { role: 'button', wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^Sent for approval: /, 8000)), (await allText(page)).slice(-300));
    });
    x.c.that('no promise', (await promisesOf(T.admin, cu.id)).length === 0);
    return result(x, FE.promise);
  });

  await tcase({ id: 'APP-UI-16', feature: '202 handling: edit promise', kind: 'UI', prd: 'B2',
    title: 'Editing a 3000 promise to 4500 is sent for approval; the promise keeps 3000',
    steps: 'admin promise 3000; mia: /promises/{id} -> Edit -> Promised amount 4500 -> Save',
    expected: 'DESIGN §7.6: SnackBar "Sent for approval: …"; API amount 3000' }, async (attempt) => {
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const x = await ui('APP-UI-16', attempt, 'mia', `/promises/${p.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Edit', { role: 'button', wait: 2500 });
      await fill(page, 'Promised amount *', '4500');
      await rt.tap(page, 'Save', { role: 'button', wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^Sent for approval: /, 8000)), (await allText(page)).slice(-300));
      c.that('banner on the promise page', !!(await waitText(page, /^Awaiting approval: /, 6000)), (await allText(page)).slice(0, 400));
      c.that('page still shows ₹3,000.00', /₹3,000\.00/.test(await allText(page)));
    });
    const g = await rt.api('GET', `/api/promises/${p.id}`, { token: T.admin });
    x.c.that('API amount 3000', Number(g.json?.amount) === 3000, g.json?.amount);
    return result(x, FE.promise);
  });

  await tcase({ id: 'APP-UI-17', feature: '202 handling: override promise', kind: 'UI', prd: 'B2',
    title: 'Overriding a 3000 promise to Kept is sent for approval; the status stays Open',
    steps: 'admin promise 3000; mia: /promises/{id} -> Override status -> Status Kept -> Reason -> Override',
    expected: 'DESIGN §7.6: dialog closes, SnackBar "Sent for approval: …"; API status unchanged' }, async (attempt) => {
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const x = await ui('APP-UI-17', attempt, 'mia', `/promises/${p.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Override status', { role: 'button', wait: 1500 });
      await rt.tap(page, /^Status/, { wait: 1000 });
      await rt.press(page, 'Kept', { wait: 1200 });
      await fill(page, 'Reason *', 'customer says paid');
      await rt.tap(page, 'Override', { role: 'button', wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^Sent for approval: /, 8000)), (await allText(page)).slice(-300));
    });
    const g = await rt.api('GET', `/api/promises/${p.id}`, { token: T.admin });
    x.c.that('API status unchanged', g.json?.status === p.status && !g.json?.statusOverridden, g.json && { status: g.json.status, o: g.json.statusOverridden });
    return result(x, FE.override);
  });

  await tcase({ id: 'APP-UI-18', feature: '202 handling: dispute approve', kind: 'UI', prd: 'B2',
    title: 'Approving a dispute above DISPUTE_APPROVE as a non-approver says it needs a second approver and stays on the page',
    steps: 'invoice 1500 on s1; cust disputes {action:cancel}; mia: /disputes/{id} -> Approve',
    expected: 'DESIGN §7.6: SnackBar "This approval needs a second approver"; stays on /disputes/{id}; still Pending; banner shown; API dispute PENDING' }, async (attempt) => {
    const inv = await invoiceBy(w.nextFx(), s1, 1500);
    const d = rt.need(await rt.createDispute(T.cust, { targetType: 'INVOICE', targetId: inv.id, reason: 'ui dispute', proposedChangeJson: '{"action":"cancel"}' }), 'dispute');
    const x = await ui('APP-UI-18', attempt, 'mia', `/disputes/${d.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Approve', { role: 'button', wait: 300 });
      c.that('snackbar', !!(await waitText(page, /^This approval needs a second approver/, 8000)), (await allText(page)).slice(-300));
      await page.waitForTimeout(2000);
      const hash = await hashOf(page);
      c.that('stays on the dispute', hash === `#/disputes/${d.id}`, hash);
      const t = await allText(page);
      c.that('still Pending with banner', /Pending/.test(t) && /Awaiting approval: /.test(t), t.slice(0, 500));
    });
    const g = await rt.api('GET', `/api/disputes/${d.id}`, { token: T.admin });
    x.c.that('API dispute PENDING', g.json?.status === 'PENDING', g.json?.status);
    return result(x, FE.dispute);
  });

  // ---------------------------------------------------------------- thresholds screen
  await tcase({ id: 'APP-UI-19', feature: 'Thresholds screen', kind: 'UI', prd: 'B2',
    title: 'Raising South\'s Record payment limit on the thresholds screen is held: "Raising or removing limits needs a second approver"',
    steps: 'sam: /approval-thresholds (South) -> Record payment Limit 8000 -> Save',
    expected: 'DESIGN §7.6/§7.7.1: 202 notice "Raising or removing limits needs a second approver" with View; banner with the outstanding change; API PAYMENT_RECORD still 5000 with a pending THRESHOLD_CHANGE' }, async (attempt) => {
    const x = await ui('APP-UI-19', attempt, 'sam', '/approval-thresholds', async ({ page }, c) => {
      c.that('South selected', await rt.exists(page, /SOUTH — South/));
      const limits = (await rt.semantics(page)).filter((n) => n.role === 'textbox' && n.label === 'Limit');
      c.that('8 limit fields', limits.length === 8, limits.length);
      await fill(page, 'Limit', '8000', { nth: 3 });
      await rt.tap(page, 'Save', { role: 'button', wait: 300 });
      c.that('notice', !!(await waitText(page, /^Raising or removing limits needs a second approver/, 8000)), (await allText(page)).slice(-300));
    });
    const g = await rt.getThresholds(T.admin, SOUTH);
    x.c.that('API PAYMENT_RECORD still 5000 with pending change', Number(g.json?.thresholds?.find((t) => t.operation === 'PAYMENT_RECORD')?.amount) === 5000 && g.json?.pendingChange?.operation === 'THRESHOLD_CHANGE', g.json && { pc: g.json.pendingChange?.id });
    if (g.json?.pendingChange) await rt.approvals.withdraw(T.sam, g.json.pendingChange.id);
    return result(x, FE.thr);
  });

  await tcase({ id: 'APP-UI-20', feature: 'Thresholds screen', kind: 'UI', prd: 'B2',
    title: 'Lowering South\'s Extend-invoice-due-date limit on the screen saves at once',
    steps: 'sam: /approval-thresholds -> Extend invoice due date Limit 900 -> Save',
    expected: 'DESIGN §7.7.1: "Approval thresholds saved"; API INVOICE_TERMS 900' }, async (attempt) => {
    const x = await ui('APP-UI-20', attempt, 'sam', '/approval-thresholds', async ({ page }, c) => {
      await fill(page, 'Limit', '900', { nth: 2 });
      await rt.tap(page, 'Save', { role: 'button', wait: 300 });
      c.that('saved notice', !!(await waitText(page, /^Approval thresholds saved/, 8000)), (await allText(page)).slice(-300));
    });
    const g = await rt.getThresholds(T.admin, SOUTH);
    x.c.that('API INVOICE_TERMS 900', Number(g.json?.thresholds?.find((t) => t.operation === 'INVOICE_TERMS')?.amount) === 900);
    return result(x, FE.thr);
  });

  // ---------------------------------------------------------------- notifications
  await tcase({ id: 'APP-UI-21', feature: 'Notifications to approvers', kind: 'UI', prd: 'B2',
    title: 'The approver sees "Approval needed: Record payment" on the Notifications page',
    steps: 'mia holds a 7000 payment; admin opens /notifications',
    expected: 'DESIGN §5.11.11: a row "Approval needed: Record payment" with the summary' }, async (attempt) => {
    const cu = await customerIn(w);
    await heldPayment(T.mia, cu);
    const x = await ui('APP-UI-21', attempt, 'admin', '/notifications', async ({ page }, c) => {
      await page.waitForTimeout(1500);
      const t = await allText(page);
      c.that('row shown', /Approval needed: Record payment/.test(t) && t.includes(cu.name), t.slice(0, 600));
    });
    return result(x, 'frontend/lib/features/notifications');
  });

  await tcase({ id: 'APP-UI-22', feature: 'Thresholds screen', kind: 'UI', prd: 'B2',
    title: 'With another person\'s loosening outstanding, the thresholds screen shows it and a competing loosening shows the server\'s reason',
    steps: 'admin PUT South [INVOICE_CREATE 20000] (held); sam opens /approval-thresholds, sets Create invoice limit 30000, Save',
    expected: 'DESIGN §7.7.1: a banner shows the outstanding change with View; Save answers 409 and the screen shows "…awaiting approval…" (no crash); API INVOICE_CREATE still 10000' }, async (attempt) => {
    const held = await rt.setThresholds(T.admin, SOUTH, [{ operation: 'INVOICE_CREATE', amount: 20000 }]);
    const x = await ui('APP-UI-22', attempt, 'sam', '/approval-thresholds', async ({ page }, c) => {
      const t = await allText(page);
      c.that('banner for the outstanding change', /Loosen approval limits in South|awaiting approval|Awaiting approval/i.test(t), t.slice(0, 500));
      await fill(page, 'Limit', '30000', { nth: 0 });
      await rt.tap(page, 'Save', { role: 'button', wait: 2500 });
      const t2 = await allText(page);
      c.that('server reason shown', /already has a change awaiting approval/i.test(t2), t2.slice(-500));
    }, { allowStatuses: [409] });
    const g = await rt.getThresholds(T.admin, SOUTH);
    x.c.that('API INVOICE_CREATE still 10000', Number(g.json?.thresholds?.find((t) => t.operation === 'INVOICE_CREATE')?.amount) === 10000);
    if (held.pendingChange) await rt.approvals.withdraw(T.admin, held.pendingChange.id);
    return result(x, FE.thr);
  });

  await tcase({ id: 'APP-UI-23', feature: 'Thresholds screen', kind: 'UI', prd: 'B2',
    title: 'The link an approved threshold change points to (/approval-thresholds?regionId=West) opens West\'s limits',
    steps: 'admin opens /approval-thresholds?regionId={West}',
    expected: 'TRIAGE B5 CT-5 / DESIGN §7.7.1: the Region dropdown shows "WEST — West" and West\'s limits (Record payment 3,000)' }, async (attempt) => {
    const x = await ui('APP-UI-23', attempt, 'admin', `/approval-thresholds?regionId=${w.regions.WEST.id}`, async ({ page }, c) => {
      c.that('West selected', !!(await waitText(page, /WEST — West/, 6000)), (await allText(page)).slice(0, 300));
    });
    return result(x, FE.thr);
  });

  await tcase({ id: 'APP-UI-24', feature: 'Pending visible to the customer', kind: 'UI', prd: 'B2',
    title: 'The customer sees "Awaiting approval" on its own invoice without being told who asked',
    steps: 'invoice 1500 on s1; cust disputes {action:cancel}; mia approves (held); cust signs in and opens /invoices/{id}',
    expected: 'DESIGN §7.7.1: banner "Awaiting approval: … · requested on <date>" (no name); invoice still Unpaid' }, async (attempt) => {
    const inv = await invoiceBy(w.nextFx(), s1, 1500);
    const d = rt.need(await rt.createDispute(T.cust, { targetType: 'INVOICE', targetId: inv.id, reason: 'ui cust', proposedChangeJson: '{"action":"cancel"}' }), 'dispute');
    const h = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.mia, body: {} }));
    const x = await ui('APP-UI-24', attempt, U.cust.username, `/invoices/${inv.id}`, async ({ page }, c) => {
      const b = await waitText(page, /^Awaiting approval: /, 10000);
      c.that('banner shown', !!b, (await allText(page)).slice(0, 500));
      c.that('no maker name', b && !/Mia Maker|requested by/.test(`${b.label} ${b.text}`), b && (b.label || b.text));
    });
    if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    return result(x, FE.banner);
  });

  await tcase({ id: 'APP-UI-25', feature: 'Pending banner and list chip', kind: 'UI', prd: 'B2',
    title: 'The promises list flags a promise with a held change',
    steps: 'admin promise 3000; mia PUT amount 4500 (held); nora opens /promises?f=customerId:eq:{c}',
    expected: 'TRIAGE F2: "Awaiting approval" chip on the promise row; amount still ₹3,000.00' }, async (attempt) => {
    const cu = await customerIn(w);
    const p = await promiseBy(T.admin, cu, 3000);
    const h = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: { amount: 4500, promisedDate: p.promisedDate, collectionPocUserId: p.collectionPocUserId ?? U.sam.id, notes: null, invoiceIds: null } }));
    const x = await ui('APP-UI-25', attempt, 'nora', `/promises?f=customerId:eq:${cu.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1500);
      const t = await allText(page);
      c.that('chip on the row', /Awaiting approval/.test(t) && /₹3,000\.00/.test(t), t.slice(0, 500));
    });
    if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    return result(x, 'frontend/lib/features/promises/promises_screen.dart');
  });

  // ---------------------------------------------------------------- what the checker is shown
  const clippedCells = async (page) => {
    const nodes = await rt.semantics(page);
    return nodes.filter((n) => n.role === 'cell' && (n.text || '').split('\n').length >= 3)
      .map((n) => ({ lines: (n.text || '').split('\n').length, h: n.h, text: (n.text || '').slice(0, 80) }))
      .filter((n) => n.h < n.lines * 16);
  };

  await tcase({ id: 'APP-UI-26', feature: 'What the checker is shown', kind: 'UI', prd: 'B2',
    title: 'The Changes table shows multi-line values in full (a loosened limit\'s window, an invoice\'s lines)',
    steps: 'sam PUT South [INVOICE_CANCEL 6000 over 12 h] (held); mia holds a 12000 invoice create; admin opens each /approvals/{id} (Changes tab)',
    expected: 'DESIGN §7.7.1 "the changes table": the approver can read each field\'s current and proposed value; cells are tall enough for their text (not clipped at one row height)' }, async (attempt) => {
    const th = await rt.setThresholds(T.sam, SOUTH, [{ operation: 'INVOICE_CANCEL', amount: 6000, windowHours: 12 }]);
    const cu = await customerIn(w);
    const ic = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const found = [];
    const x = await ui('APP-UI-26', attempt, 'admin', `/approvals/${th.json?.pendingChange?.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1000);
      const a = await clippedCells(page);
      found.push(...a);
      c.that('threshold change: no clipped cell', a.length === 0, a);
      await rt.go(page, `/approvals/${ic.pendingChange?.id}`, 4500);
      await rt.shot(page, R.shotsDir, `APP-UI-26-${attempt}-invoice-create`);
      const b = await clippedCells(page);
      found.push(...b);
      c.that('invoice create: no clipped cell', b.length === 0, b);
    }, { height: 1600 }); // r2: a tall viewport, so a cell cut by the window edge is not read as clipped
    if (th.json?.pendingChange) await rt.approvals.withdraw(T.sam, th.json.pendingChange.id);
    if (ic.pendingChange) await rt.approvals.withdraw(T.mia, ic.pendingChange.id);
    return { ...result(x, 'frontend/lib/features/approvals/approval_detail_screen.dart:378 (DataTable with the default one-line row height; multi-line JsonDiffView.pretty text is cut off)'), severity: 'low' };
  });

  await tcase({ id: 'APP-UI-27', feature: 'What the checker is shown', kind: 'UI', prd: 'B2',
    title: 'The Changes table names people and uses readable labels (not "Sales poc user id: 4")',
    steps: 'mia holds a 12000 invoice create with Sales POC Sam South; admin opens /approvals/{id}',
    expected: 'The approver can tell who the Sales POC will be: a "Sales POC" row reading "Sam South"; no raw "... user id" labels with bare ids' }, async (attempt) => {
    const cu = await customerIn(w);
    const ic = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const x = await ui('APP-UI-27', attempt, 'admin', `/approvals/${ic.pendingChange?.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1000);
      const cells = (await rt.semantics(page)).filter((n) => n.role === 'cell').map((n) => n.text || n.label || '');
      c.that('no raw "user id" label', !cells.some((t) => /user id$/i.test(t)), cells.filter((t) => /user id/i.test(t)));
      c.that('Sales POC shown by name', cells.some((t) => t === 'Sam South'), cells.slice(0, 16));
    });
    if (ic.pendingChange) await rt.approvals.withdraw(T.mia, ic.pendingChange.id);
    return { ...result(x, 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:189 (label() only splits the field name; ids are passed through) + frontend/lib/features/approvals/approval_detail_screen.dart:356'), severity: 'low' };
  });

  await tcase({ id: 'APP-UI-28', feature: 'Approval applies only the held change', kind: 'UI', prd: 'B2',
    title: 'End to end from the invoice page: approving a Net 90 terms change reverts a note someone wrote after the request',
    steps: 'invoice 2500 with notes "first"; mia on /invoices/{id} sets Payment terms Net 90 and saves (held); admin PATCH {notes:"second"}; sam approves the held change; GET the invoice',
    expected: 'Approval applies the terms change only: paymentTerm NET_90, notes "second"' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    rt.need(await rt.patchInvoice(T.admin, inv.id, { notes: 'first' }), 'notes first');
    const x = await ui('APP-UI-28', attempt, 'mia', `/invoices/${inv.id}`, async ({ page }, c) => {
      await rt.tap(page, 'Custom', { role: 'button', wait: 1200 });
      await rt.press(page, 'Net 90', { wait: 1500 });
      await rt.tap(page, 'Save changes', { role: 'button', wait: 300 });
      c.that('sent for approval', !!(await waitText(page, /^Sent for approval: /, 8000)));
    });
    const f = await rt.approvals.forRecord(T.admin, 'INVOICE', inv.id);
    const pc = Array.isArray(f.json) ? f.json.find((b) => b.operation === 'INVOICE_TERMS') : null;
    const e = await rt.patchInvoice(T.admin, inv.id, { notes: 'second' });
    const a = await rt.approvals.approve(T.sam, pc?.id);
    const g = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin })).json;
    x.c.that('held from the UI', !!pc, brief(f).slice(0, 200));
    x.c.that('notes edit applied', e.status === 200);
    x.c.that('APPROVED, NET_90', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90', { status: a.json?.status, failure: a.json?.failureReason, term: g?.paymentTerm });
    x.c.that('notes still "second"', g?.notes === 'second', { notes: g?.notes });
    return { ...result(x, 'backend/src/main/java/com/geneinvoice/invoice/InvoiceChangeApplier.java:136 (with frontend/lib/features/invoices/invoice_detail_screen.dart:143 sending the unchanged notes)', `pendingChange ${pc?.id}; GET /api/invoices/${inv.id} -> notes ${JSON.stringify(g?.notes)}`), severity: 'medium' };
  });
  // ---------------------------------------------------------------- round 2: nearby gaps (APP-R1-2 frontend, APP-R1-4 UI)
  await tcase({ id: 'APP-UI-29', feature: 'Approval applies only the held change', kind: 'UI', prd: 'B2',
    title: 'A terms-only change from the invoice page (no notes) sends no notes, and survives a note added before approval',
    steps: 'invoice 2500 with no notes; mia on /invoices/{id} picks Net 90 and taps Save changes (held); inspect the PATCH body; admin PATCH {notes:"call Friday"}; sam approves',
    expected: 'FIXLOG APP-R1-2: PATCH body {paymentTerm:"NET_90"} with no "notes" key; "Sent for approval"; approval APPROVED, NET_90, notes "call Friday"' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    const bodies = [];
    const x = await ui('APP-UI-29', attempt, 'mia', `/invoices/${inv.id}`, async ({ page }, c) => {
      page.on('request', (rq) => { if (rq.method() === 'PATCH') bodies.push(rq.postData()); });
      await rt.tap(page, 'Custom', { role: 'button', wait: 1200 });
      await rt.press(page, 'Net 90', { wait: 1500 });
      await rt.tap(page, 'Save changes', { role: 'button', wait: 300 });
      c.that('sent for approval', !!(await waitText(page, /^Sent for approval: /, 8000)));
    });
    let b = null; try { b = JSON.parse(bodies[0] || 'null'); } catch { /* raw */ }
    x.c.that('one PATCH with paymentTerm NET_90 and no notes key', bodies.length === 1 && b && b.paymentTerm === 'NET_90' && !('notes' in b), bodies);
    const f = await rt.approvals.forRecord(T.admin, 'INVOICE', inv.id);
    const pc = Array.isArray(f.json) ? f.json.find((z) => z.operation === 'INVOICE_TERMS') : null;
    await rt.patchInvoice(T.admin, inv.id, { notes: 'call Friday' });
    const a = await rt.approvals.approve(T.sam, pc?.id);
    const g = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin })).json;
    x.c.that('APPROVED; NET_90; notes kept', a.json?.status === 'APPROVED' && g?.paymentTerm === 'NET_90' && g?.notes === 'call Friday', { status: a.json?.status, failure: a.json?.failureReason, term: g?.paymentTerm, notes: g?.notes });
    return { ...result(x, FE.invDetail, `PATCH bodies ${JSON.stringify(bodies)}; pendingChange ${pc?.id}`), severity: 'low' };
  });

  await tcase({ id: 'APP-UI-30', feature: 'What the checker is shown', kind: 'UI', prd: 'B2',
    title: 'A held payment\'s Changes table names the Collection POC and shows a readable method',
    steps: 'mia holds a 7000 BANK_TRANSFER payment (Collection POC sam); admin opens /approvals/{id}',
    expected: 'FIXLOG APP-R1-4: a "Collection POC" row reading "Sam South", "Bank transfer", the customer named on the page; no "... user id" label' }, async (attempt) => {
    const cu = await customerIn(w);
    const h = await heldPayment(T.mia, cu);
    const x = await ui('APP-UI-30', attempt, 'admin', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1000);
      const cells = (await rt.semantics(page)).filter((n) => n.role === 'cell').map((n) => n.text || n.label || '');
      c.that('Collection POC row', cells.includes('Collection POC'), cells.slice(0, 30));
      c.that('Sam South by name', cells.includes('Sam South'), cells.slice(0, 30));
      c.that('Bank transfer', cells.includes('Bank transfer'), cells.slice(0, 30));
      c.that('customer named on the page', (await allText(page)).includes(cu.name));
      c.that('no raw "user id" label', !cells.some((t) => /user id$/i.test(t)), cells.filter((t) => /user id/i.test(t)));
    });
    if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    return { ...result(x, FE.detail), severity: 'low' };
  });

  await tcase({ id: 'APP-UI-31', feature: '202 handling at write sites', kind: 'UI', prd: 'B2',
    title: 'Editing the notes on the invoice page and putting them back sends nothing ("Nothing has changed")',
    steps: 'invoice 2500 on a South customer; mia on /invoices/{id} types "abc" into notes, clears it, taps Save changes',
    expected: 'FIXLOG APP-R1-2 (frontend): no PATCH is sent; a "Nothing has changed" notice; Save disabled again' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 2500);
    const x = await ui('APP-UI-31', attempt, 'mia', `/invoices/${inv.id}`, async ({ page }, c) => {
      await fill(page, 'Internal notes', 'abc');
      await fill(page, /.*/, '');
      await rt.tap(page, 'Save changes', { role: 'button', wait: 300 });
      c.that('"Nothing has changed"', !!(await waitText(page, 'Nothing has changed', 5000)));
    });
    x.c.that('no write sent', x.writes.length === 0, x.writes);
    return { ...result(x, FE.invDetail), severity: 'low' };
  });
  await tcase({ id: 'APP-UI-32', feature: 'What the checker is shown', kind: 'UI', prd: 'B2',
    title: 'The Changes table names a held payment\'s invoice by number and a held invoice\'s lines by product, not by database id',
    steps: 'admin invoice 8000 on a fresh South customer; mia holds a 7000 payment allocated to it (no promises); admin opens /approvals/{id}. mia holds an invoice create items [{E2E Widget, qty 150}] on another South customer; admin opens /approvals/{id}',
    expected: 'TRIAGE r2 APP-R2-1 contract (DESIGN §7.7.1): payment: an "Invoices" row whose Proposed cell is exactly the invoice number (INV-…); no "Invoice ids" label; no promise row (a create with no promises omits it). Invoice create: the Items Proposed cell contains "150 × E2E Widget"; no cell shows "productId"' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const h = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id, invoiceIds: [inv.id] });
    const cu2 = await customerIn(w);
    const ic = await rt.createInvoice(T.mia, { customerId: cu2.id, items: [{ productId: w.product.id, quantity: 150 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    // The Changes table's cells come in [field label, current, proposed] triples.
    const rowOf = (cells, label) => { const i = cells.indexOf(label); return i >= 0 ? { current: cells[i + 1], proposed: cells[i + 2] } : null; };
    const cellsOf = async (page) => (await rt.semantics(page)).filter((n) => n.role === 'cell').map((n) => n.text || n.label || '');
    let cells = [];
    let cells2 = [];
    const x = await ui('APP-UI-32', attempt, 'admin', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1000);
      cells = await cellsOf(page);
      const r = rowOf(cells, 'Invoices');
      c.that('payment held', h.held, h.status);
      c.that('"Invoices" row present', !!r, cells);
      c.that('Invoices Proposed cell is exactly the invoice number', !!r && !!inv.invoiceNumber && r.proposed === inv.invoiceNumber, { proposed: r?.proposed, invoiceNumber: inv.invoiceNumber });
      c.that('no "Invoice ids" label', !cells.includes('Invoice ids'), cells.filter((t) => /ids$/.test(t)));
      c.that('no promise row on a payment with no promises', !cells.includes('Promises') && !cells.includes('Promise ids'), cells.filter((t) => /^Promise/.test(t)));
      c.that('invoice create held', ic.held, ic.status);
      await rt.go(page, `/approvals/${ic.pendingChange?.id}`, 4000);
      cells2 = await cellsOf(page);
      const it = rowOf(cells2, 'Items');
      c.that(`Items Proposed cell contains "150 × ${w.product.name}"`, !!it && String(it.proposed || '').includes(`150 × ${w.product.name}`), it || cells2);
      c.that('no cell shows "productId"', !cells2.some((t) => t.includes('productId')), cells2.filter((t) => t.includes('productId')));
    });
    for (const pc of [h.pendingChange, ic.pendingChange]) if (pc) await rt.approvals.withdraw(T.mia, pc.id);
    return { ...result(x, 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java + frontend/lib/features/approvals/approval_detail_screen.dart (_ChangesTab._value)',
      `invoice ${inv.id} = ${inv.invoiceNumber}; payment cells ${JSON.stringify(cells).slice(0, 300)}; invoice-create cells ${JSON.stringify(cells2).slice(0, 300)}`), severity: 'low' };
  });
  // r3: nearby gap of APP-R2-1 — the Promises row and a Current cell for an id list.
  await tcase({ id: 'APP-UI-33', feature: 'What the checker is shown', kind: 'UI', prd: 'B2',
    title: 'The Changes table names the promise a held payment settles, and shows "None" -> invoice number for a held promise update',
    steps: 'fresh South customer; admin invoice 8000 + promise 3000; mia holds a 7000 payment with promiseIds [promise]; admin opens /approvals/{id}. mia holds PUT /api/promises/{id} {amount 4500, invoiceIds [invoice]}; admin opens /approvals/{id}',
    expected: 'TRIAGE r2 APP-R2-1 (DESIGN §7.7.1): payment: "Promises" row, Proposed "Promise of ₹3,000.00 by <date>", no "Invoices" row; promise update: "Invoices" row Current "None", Proposed = invoice number; no raw [ id ] JSON' }, async (attempt) => {
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 8000);
    const p = await promiseBy(T.admin, cu, 3000);
    const h = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id, promiseIds: [p.id] });
    const pu = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: T.mia, body: { amount: 4500, promisedDate: p.promisedDate, collectionPocUserId: U.sam.id, notes: p.notes ?? null, invoiceIds: [inv.id] } }));
    const rowOf = (cells, label) => { const i = cells.indexOf(label); return i >= 0 ? { current: cells[i + 1], proposed: cells[i + 2] } : null; };
    const cellsOf = async (page) => (await rt.semantics(page)).filter((n) => n.role === 'cell').map((n) => n.text || n.label || '');
    let cells = []; let cells2 = [];
    const x = await ui('APP-UI-33', attempt, 'admin', `/approvals/${h.pendingChange?.id}`, async ({ page }, c) => {
      await page.waitForTimeout(1000);
      cells = await cellsOf(page);
      const pr = rowOf(cells, 'Promises');
      c.that('payment held', h.held, h.status);
      c.that(`Promises Proposed "Promise of ₹3,000.00 by ${p.promisedDate}"`, pr?.proposed === `Promise of ₹3,000.00 by ${p.promisedDate}`, pr || cells);
      c.that('no Invoices row on a payment with no invoices', !cells.includes('Invoices') && !cells.includes('Invoice ids'), cells.filter((t) => /^Invoice/.test(t)));
      c.that('promise update held', pu.held, pu.status);
      await rt.go(page, `/approvals/${pu.pendingChange?.id}`, 4000);
      await page.waitForTimeout(800);
      cells2 = await cellsOf(page);
      const ir = rowOf(cells2, 'Invoices');
      c.that('Invoices Current "None"', ir?.current === 'None', ir || cells2);
      c.that('Invoices Proposed = invoice number', !!inv.invoiceNumber && ir?.proposed === inv.invoiceNumber, { proposed: ir?.proposed, invoiceNumber: inv.invoiceNumber });
      c.that('no raw id-list JSON', ![...cells, ...cells2].some((t) => /^\[/.test(t)), [...cells, ...cells2].filter((t) => /^\[/.test(t)));
    });
    for (const pc of [h.pendingChange, pu.pendingChange]) if (pc) await rt.approvals.withdraw(T.mia, pc.id);
    return { ...result(x, 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java + frontend/lib/features/approvals/approval_detail_screen.dart (_ChangesTab._value)',
      `payment cells ${JSON.stringify(cells).slice(0, 300)}; promise-update cells ${JSON.stringify(cells2).slice(0, 300)}`), severity: 'low' };
  });
};
