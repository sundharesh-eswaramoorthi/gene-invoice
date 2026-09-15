// API regression tests: payments, allocation, customer credit.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const out = [];
function rec(id, title, ok, detail) {
  out.push({ id, title, status: ok ? 'PASS' : 'FAIL', detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${title}\n     ${JSON.stringify(detail).slice(0, 900)}`);
}
const n = (v) => (v === null || v === undefined ? null : Number(v));
const snip = (r) => ({ status: r.status, body: (r.text || '').slice(0, 300) });

(async () => {
  const admin = await rt.adminToken();
  const cashier = await rt.cashierToken();
  const api = (m, p, body, token = admin) => rt.api(m, p, { token, body });

  // ---- setup -------------------------------------------------------------------------
  const poc1 = await rt.createStaff(admin, 'COLLECTION_POC', 'paycr');
  const poc2 = await rt.createStaff(admin, 'COLLECTION_POC', 'paycr');
  const poc3 = await rt.createStaff(admin, 'COLLECTION_POC', 'paycr');
  const sales = await rt.createStaff(admin, 'SALES_POC', 'paycr');
  const viewer = await rt.createStaff(admin, 'VIEWER', 'paycr');
  const prod = (await api('POST', '/api/products', { name: rt.uniq('paycr-prod'), price: 100 })).json;
  const c1 = await rt.createCustomer(admin, 'paycr');
  const c2 = await rt.createCustomer(admin, 'paycr');
  const c3 = await rt.createCustomer(admin, 'paycr');
  const c4 = await rt.createCustomer(admin, 'paycr');
  const c5 = await rt.createCustomer(admin, 'paycr');
  const seat = await api('POST', `/api/customers/${c1.id}/pocs`, { pocType: 'COLLECTION', userId: poc1.id, primary: true });
  console.log('setup', { poc1: poc1.id, poc2: poc2.id, poc3: poc3.id, sales: sales.id, prod: prod.id,
    c1: c1.id, c2: c2.id, c3: c3.id, c4: c4.id, c5: c5.id, seat: seat.status });

  const mkInv = async (cid, amount, date) => {
    const r = await api('POST', '/api/invoices', {
      customerId: cid, invoiceDate: date, salesPocUserId: sales.id,
      items: [{ productId: prod.id, quantity: 1, unitPrice: amount }],
    });
    if (r.status !== 200) throw new Error('invoice ' + r.status + r.text);
    return r.json;
  };
  const inv = async (id) => (await api('GET', `/api/invoices/${id}`)).json;
  const credit = async (cid) => n((await api('GET', `/api/payments/credits/${cid}`)).json?.creditBalance);
  const payCount = async (cid) => (await api('GET', `/api/payments?filter=customerId:eq:${cid}&size=50`)).json.totalElements;
  const pay = (body, token = admin) => api('POST', '/api/payments', body, token);
  const invState = (i) => ({ no: i.invoiceNumber, paid: n(i.paidAmount), bal: n(i.balance), status: i.status });

  // Created out of date order so id order != date order.
  const C = await mkInv(c1.id, 300, '2026-03-01T10:00:00Z');
  const A = await mkInv(c1.id, 100, '2026-01-01T10:00:00Z');
  const B = await mkInv(c1.id, 200, '2026-02-01T10:00:00Z');
  const X2 = await mkInv(c2.id, 70, '2025-12-01T10:00:00Z'); // other customer's invoice

  // ---- create validation -------------------------------------------------------------
  let r = await pay({ customerId: c1.id, amount: 50, method: 'Cash' });
  let a = await inv(A.id);
  rec('PAY-01', 'missing collectionPocUserId -> 400, nothing applied',
    r.status === 400 && /Collection POC is required/i.test(r.text) && n(a.paidAmount) === 0 && (await payCount(c1.id)) === 0,
    { ...snip(r), invA: invState(a) });

  const r2a = await pay({ customerId: c1.id, amount: 50, collectionPocUserId: sales.id });
  const c2user = (await api('GET', `/api/users?filter=username:eq:${c2.username}`)).json?.content?.[0];
  const r2b = await pay({ customerId: c1.id, amount: 50, collectionPocUserId: c2user?.id });
  const r2c = await pay({ customerId: c1.id, amount: 50, collectionPocUserId: 99999999 });
  rec('PAY-02', 'non-assignable POC (sales user / customer login / unknown id) rejected',
    r2a.status === 400 && /not assignable as Collection POC/i.test(r2a.text) && r2b.status === 400 && [400, 404].includes(r2c.status)
      && (await payCount(c1.id)) === 0,
    { sales: snip(r2a), customerLogin: snip(r2b), unknown: snip(r2c) });

  const r3 = await Promise.all([
    pay({ customerId: c1.id, amount: 0, collectionPocUserId: poc1.id }),
    pay({ customerId: c1.id, amount: -5, collectionPocUserId: poc1.id }),
    pay({ customerId: c1.id, collectionPocUserId: poc1.id }),
    pay({ amount: 5, collectionPocUserId: poc1.id }),
    pay({ customerId: 99999999, amount: 5, collectionPocUserId: poc1.id }),
  ]);
  rec('PAY-03', 'amount 0 / negative / missing, missing customer -> 400; unknown customer -> 404',
    r3[0].status === 400 && r3[1].status === 400 && r3[2].status === 400 && r3[3].status === 400 && r3[4].status === 404
      && (await payCount(c1.id)) === 0,
    r3.map(snip));

  // ---- oldest-first allocation -------------------------------------------------------
  r = await pay({ customerId: c1.id, amount: 150, method: 'Cash', notes: 'first', collectionPocUserId: poc1.id });
  const p150 = r.json;
  let [ia, ib, ic] = [await inv(A.id), await inv(B.id), await inv(C.id)];
  rec('PAY-04', 'no invoiceIds: 150 applied oldest-first (A 100 full, B 50 partial, C untouched)',
    r.status === 200 && ia.status === 'FULLY_PAID' && n(ia.paidAmount) === 100 && n(ia.balance) === 0
      && ib.status === 'PARTIALLY_PAID' && n(ib.paidAmount) === 50 && n(ib.balance) === 150
      && ic.status === 'UNPAID' && n(ic.paidAmount) === 0 && n(p150.creditApplied) === 0 && n(p150.customerCreditBalance) === 0
      && p150.collectionPoc?.id === poc1.id && p150.status === 'ACTIVE',
    { status: r.status, allocations: p150?.invoices?.map((x) => [x.invoiceNumber, n(x.allocatedAmount)]),
      creditApplied: p150?.creditApplied, A: invState(ia), B: invState(ib), C: invState(ic) });

  r = await api('GET', `/api/payments/${p150.id}`);
  const g = r.json;
  rec('PAY-05', 'GET /api/payments/{id}: allocations carry invoice numbers/amounts, creditApplied, POC',
    r.status === 200 && g.invoices.length === 2 && g.invoices[0].invoiceNumber === A.invoiceNumber
      && n(g.invoices[0].allocatedAmount) === 100 && g.invoices[1].invoiceNumber === B.invoiceNumber
      && n(g.invoices[1].allocatedAmount) === 50 && n(g.creditApplied) === 0 && n(g.amount) === 150
      && g.method === 'Cash' && g.notes === 'first' && !!g.paidAt && g.collectionPoc?.username === poc1.username && g.pocMissing === false,
    { status: r.status, body: r.text.slice(0, 700) });

  // ---- explicit invoiceIds -----------------------------------------------------------
  r = await pay({ customerId: c1.id, amount: 100, invoiceIds: [C.id], collectionPocUserId: poc1.id });
  const p100c = r.json;
  [ib, ic] = [await inv(B.id), await inv(C.id)];
  rec('PAY-06', 'explicit invoiceIds [C]: only C paid even though older B is outstanding',
    r.status === 200 && n(ic.paidAmount) === 100 && ic.status === 'PARTIALLY_PAID' && n(ib.paidAmount) === 50
      && p100c.invoices.length === 1 && p100c.invoices[0].id === C.id && n(p100c.creditApplied) === 0,
    { status: r.status, B: invState(ib), C: invState(ic), alloc: p100c?.invoices?.map((x) => x.invoiceNumber) });

  const before06 = await payCount(c1.id);
  const r7a = await pay({ customerId: c1.id, amount: 20, invoiceIds: [X2.id], collectionPocUserId: poc1.id });
  const r7b = await pay({ customerId: c1.id, amount: 20, invoiceIds: [B.id, X2.id], collectionPocUserId: poc1.id });
  const [ib7, ix7] = [await inv(B.id), await inv(X2.id)];
  rec('PAY-07', "invoiceIds including another customer's invoice -> 400, nothing applied",
    r7a.status === 400 && /does not belong to this customer/.test(r7a.text) && r7b.status === 400
      && n(ib7.paidAmount) === 50 && n(ix7.paidAmount) === 0 && (await payCount(c1.id)) === before06 && (await credit(c1.id)) === 0,
    { only: snip(r7a), mixed: snip(r7b), B: invState(ib7), X2: invState(ix7) });

  // ---- overpayment -> credit; next invoice consumes it ------------------------------
  r = await pay({ customerId: c1.id, amount: 1000, method: 'Transfer', collectionPocUserId: poc1.id });
  const p1000 = r.json;
  [ib, ic] = [await inv(B.id), await inv(C.id)];
  const cr8 = await credit(c1.id);
  const cust8 = (await api('GET', `/api/customers/${c1.id}`)).json;
  rec('PAY-08', 'overpayment 1000: B +150, C +200, 650 becomes creditBalance (payments/credits + customer)',
    r.status === 200 && ib.status === 'FULLY_PAID' && ic.status === 'FULLY_PAID' && n(p1000.creditApplied) === 650
      && n(p1000.customerCreditBalance) === 650 && cr8 === 650 && n(cust8.creditBalance) === 650
      && p1000.invoices.map((x) => n(x.allocatedAmount)).join() === '150,200',
    { status: r.status, creditApplied: p1000?.creditApplied, creditsEndpoint: cr8, customerCredit: cust8?.creditBalance,
      alloc: p1000?.invoices?.map((x) => [x.invoiceNumber, n(x.allocatedAmount)]), B: invState(ib), C: invState(ic) });

  const D = await mkInv(c1.id, 400, '2026-04-01T10:00:00Z');
  const cr9a = await credit(c1.id);
  const E = await mkInv(c1.id, 500, '2026-05-01T10:00:00Z');
  const cr9b = await credit(c1.id);
  const F = await mkInv(c1.id, 50, '2026-06-01T10:00:00Z');
  const cr9c = await credit(c1.id);
  rec('PAY-09', 'next invoices consume credit: D 400 fully paid (credit 250), E 500 paid 250 (credit 0), F 50 unpaid',
    n(D.paidAmount) === 400 && D.status === 'FULLY_PAID' && cr9a === 250 && n(E.paidAmount) === 250
      && E.status === 'PARTIALLY_PAID' && n(E.balance) === 250 && cr9b === 0 && n(F.paidAmount) === 0 && F.status === 'UNPAID' && cr9c === 0,
    { D: invState(D), creditAfterD: cr9a, E: invState(E), creditAfterE: cr9b, F: invState(F), creditAfterF: cr9c });

  const auD = (await api('GET', `/api/audit?entityType=INVOICE&entityId=${D.id}`)).json || [];
  const created = auD.find((e) => e.action === 'INVOICE_CREATED');
  const auA = (await api('GET', `/api/audit?entityType=INVOICE&entityId=${A.id}`)).json || [];
  const applied = auA.find((e) => e.action === 'PAYMENT_APPLIED');
  const auP = (await api('GET', `/api/audit?entityType=PAYMENT&entityId=${p150.id}`)).json || [];
  rec('PAY-10', 'audit: INVOICE_CREATED notes credit used; invoice gets PAYMENT_APPLIED; payment gets PAYMENT_RECORDED',
    /Customer credit applied: 400(\.00)?/.test(created?.reason || '') && !!applied
      && JSON.parse(applied.afterJson).paidAmount == 100 && auP.some((e) => e.action === 'PAYMENT_RECORDED'),
    { createdReason: created?.reason, applied: applied && { before: applied.beforeJson, after: applied.afterJson }, paymentActions: auP.map((e) => e.action) });

  // explicit ids + overpay of the chosen invoice -> leftover to credit, other outstanding untouched
  r = await pay({ customerId: c1.id, amount: 300, invoiceIds: [E.id], collectionPocUserId: poc1.id });
  const p300 = r.json;
  const [ie11, if11] = [await inv(E.id), await inv(F.id)];
  const cr11 = await credit(c1.id);
  rec('PAY-11', 'explicit invoiceIds [E] overpaid by 50: E fully paid, 50 to credit, F untouched',
    r.status === 200 && ie11.status === 'FULLY_PAID' && n(p300.creditApplied) === 50 && cr11 === 50 && n(if11.paidAmount) === 0,
    { status: r.status, E: invState(ie11), F: invState(if11), creditApplied: p300?.creditApplied, credit: cr11 });

  const G = await mkInv(c1.id, 30, '2026-07-01T10:00:00Z');
  const cr12 = await credit(c1.id);
  rec('PAY-12', 'partial credit consumption: invoice G 30 fully paid from 50 credit, 20 credit left',
    n(G.paidAmount) === 30 && G.status === 'FULLY_PAID' && cr12 === 20, { G: invState(G), credit: cr12 });

  // ---- odd explicit targets (customer c3) -------------------------------------------
  const H = await mkInv(c3.id, 100, '2026-01-01T10:00:00Z');
  const I = await mkInv(c3.id, 80, '2026-01-02T10:00:00Z');
  const canc = await api('POST', `/api/invoices/${I.id}/cancel`, {});
  r = await pay({ customerId: c3.id, amount: 10, invoiceIds: [99999999], collectionPocUserId: poc1.id });
  const ih13 = await inv(H.id);
  rec('PAY-13', 'invoiceIds with a non-existent invoice id is rejected (not silently turned into credit)',
    [400, 404].includes(r.status),
    { ...snip(r), creditApplied: r.json?.creditApplied, allocations: r.json?.invoices?.length, H: invState(ih13), c3Credit: await credit(c3.id) });
  const unknownIdPayment = r.status === 200 ? r.json.id : null;

  r = await pay({ customerId: c3.id, amount: 80, invoiceIds: [I.id], collectionPocUserId: poc1.id });
  const ii14 = await inv(I.id);
  rec('PAY-14', 'invoiceIds [cancelled invoice]: invoice stays CANCELLED/unpaid, money is not lost (credit or 400)',
    canc.status === 200 && ii14.status === 'CANCELLED' && n(ii14.paidAmount) === 0 && (r.status === 400 || n(r.json?.creditApplied) === 80),
    { cancel: canc.status, pay: snip(r), creditApplied: r.json?.creditApplied, I: invState(ii14) });

  r = await pay({ customerId: c4.id, amount: 75.5, method: 'Card', collectionPocUserId: poc2.id });
  const cr15 = await credit(c4.id);
  rec('PAY-15', 'customer with no invoices: whole amount to credit, no allocations',
    r.status === 200 && r.json.invoices.length === 0 && n(r.json.creditApplied) === 75.5 && cr15 === 75.5,
    { status: r.status, creditApplied: r.json?.creditApplied, invoices: r.json?.invoices, credit: cr15 });

  r = await pay({ customerId: c4.id, amount: 10.555, collectionPocUserId: poc2.id });
  const g16 = (await api('GET', `/api/payments/${r.json?.id}`)).json;
  const cr16 = await credit(c4.id);
  rec('PAY-16', 'amount with 3 decimals: response amount matches what is stored (or 400)',
    r.status === 400 || (n(r.json.amount) === n(g16.amount) && n(r.json.creditApplied) === n(g16.creditApplied)),
    { status: r.status, respAmount: r.json?.amount, storedAmount: g16?.amount, respCredit: r.json?.creditApplied,
      storedCredit: g16?.creditApplied, customerCredit: cr16 });

  // ---- promiseIds ---------------------------------------------------------------------
  const future = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10);
  const pr = (await api('POST', '/api/promises', { customerId: c1.id, amount: 20, promisedDate: future,
    collectionPocUserId: poc1.id, invoiceIds: [F.id] })).json;
  // F is 50, credit 20 on c1 was consumed by G, so F is still unpaid.
  r = await pay({ customerId: c1.id, amount: 20, invoiceIds: [F.id], promiseIds: [pr.id], collectionPocUserId: poc1.id });
  const p20 = r.json;
  const prAfter = (await api('GET', `/api/promises/${pr.id}`)).json;
  rec('PAY-17', 'promiseIds links the payment to the promise (promise shows it, status moves off OPEN)',
    r.status === 200 && JSON.stringify(prAfter).includes(`${p20.id}`) && ['PARTIALLY_KEPT', 'KEPT'].includes(prAfter.status)
      && n(prAfter.fulfilledAmount) === 20,
    { status: r.status, promise: JSON.stringify(prAfter).slice(0, 600) });

  const prOther = (await api('POST', '/api/promises', { customerId: c2.id, amount: 20, promisedDate: future,
    collectionPocUserId: poc1.id })).json;
  const bc = await payCount(c1.id); const bf = n((await inv(F.id)).paidAmount); const bcr = await credit(c1.id);
  const r18a = await pay({ customerId: c1.id, amount: 5, promiseIds: [prOther.id], collectionPocUserId: poc1.id });
  const r18b = await pay({ customerId: c1.id, amount: 5, promiseIds: [99999999], collectionPocUserId: poc1.id });
  const prC = (await api('POST', '/api/promises', { customerId: c1.id, amount: 5, promisedDate: future, collectionPocUserId: poc1.id })).json;
  const cancP = await api('POST', `/api/promises/${prC.id}/cancel`, { reason: 'rt' });
  const r18c = await pay({ customerId: c1.id, amount: 5, promiseIds: [prC.id], collectionPocUserId: poc1.id });
  const ac = await payCount(c1.id); const af = n((await inv(F.id)).paidAmount); const acr = await credit(c1.id);
  rec('PAY-18', "promiseIds of another customer / unknown / cancelled promise rejected and whole payment rolled back",
    r18a.status === 400 && /different customer/.test(r18a.text) && r18b.status === 404 && r18c.status === 400 && cancP.status === 200
      && ac === bc && af === bf && acr === bcr,
    { other: snip(r18a), unknown: snip(r18b), cancelled: snip(r18c), countBefore: bc, countAfter: ac, Fpaid: [bf, af], credit: [bcr, acr] });

  // ---- PATCH notes / Collection POC -------------------------------------------------
  r = await api('PATCH', `/api/payments/${p150.id}`, { notes: 'edited note' });
  const g19 = (await api('GET', `/api/payments/${p150.id}`)).json;
  const au19 = ((await api('GET', `/api/audit?entityType=PAYMENT&entityId=${p150.id}`)).json || []).find((e) => e.action === 'PAYMENT_UPDATED');
  rec('PAY-19', 'PATCH notes -> 200, persisted, PAYMENT_UPDATED audit with before/after and actor',
    r.status === 200 && g19.notes === 'edited note' && g19.collectionPoc?.id === poc1.id && !!au19
      && JSON.parse(au19.beforeJson).notes === 'first' && JSON.parse(au19.afterJson).notes === 'edited note' && au19.changedByUsername === 'admin',
    { status: r.status, notes: g19.notes, audit: au19 && { by: au19.changedByUsername, before: JSON.parse(au19.beforeJson).notes, after: JSON.parse(au19.afterJson).notes } });

  const poc2tok = await rt.login(poc2.username, poc2.password);
  r = await api('PATCH', `/api/payments/${p150.id}`, { notes: 'edited note', collectionPocUserId: poc2.id }, cashier);
  const g20 = (await api('GET', `/api/payments/${p150.id}`)).json;
  const au20 = ((await api('GET', `/api/audit?entityType=PAYMENT&entityId=${p150.id}`)).json || []).filter((e) => e.action === 'PAYMENT_UPDATED');
  const notif20 = (await api('GET', '/api/notifications?size=50', undefined, poc2tok)).json?.content || [];
  const auditPoc = au20.find((e) => JSON.parse(e.afterJson).collectionPoc?.id === poc2.id);
  rec('PAY-20', 'PATCH Collection POC (cashier) -> 200, persisted, audited, new POC notified with a deep link',
    r.status === 200 && g20.collectionPoc?.id === poc2.id && !!auditPoc && JSON.parse(auditPoc.beforeJson).collectionPoc?.id === poc1.id
      && auditPoc.changedByUsername === 'cashier' && notif20.some((x) => (x.link || '').includes(`/payments/${p150.id}`)),
    { status: r.status, poc: g20.collectionPoc?.username, auditRows: au20.length, notif: notif20.map((x) => [x.title, x.link]) });

  const r21a = await api('PATCH', `/api/payments/${p150.id}`, { collectionPocUserId: sales.id });
  const r21b = await api('PATCH', `/api/payments/99999999`, { notes: 'x' });
  const g21 = (await api('GET', `/api/payments/${p150.id}`)).json;
  rec('PAY-21', 'PATCH to non-assignable POC -> 400 (unchanged); PATCH unknown payment -> 404',
    r21a.status === 400 && g21.collectionPoc?.id === poc2.id && r21b.status === 404, { nonAssignable: snip(r21a), unknown: snip(r21b) });

  r = await api('PATCH', `/api/payments/${p150.id}`, { notes: 'x'.repeat(301) });
  const r22ok = await api('PATCH', `/api/payments/${p150.id}`, { notes: 'y'.repeat(300) });
  rec('PAY-22', 'PATCH 301-character notes -> clean 400 validation error (column is 300)',
    r.status === 400, { over: snip(r), exactly300: r22ok.status });

  // notes-only save when the payment's POC has since been deactivated (the UI resends the unchanged POC id)
  r = await pay({ customerId: c3.id, amount: 5, collectionPocUserId: poc3.id });
  const p3 = r.json;
  const del = await api('DELETE', `/api/users/${poc3.id}`);
  const u3 = (await api('GET', `/api/users/${poc3.id}`)).json;
  const r23a = await api('PATCH', `/api/payments/${p3.id}`, { notes: 'note after POC left', collectionPocUserId: poc3.id });
  const r23b = await api('PATCH', `/api/payments/${p3.id}`, { notes: 'note only' });
  rec('PAY-23', 'notes-only save (unchanged POC id resent, as the UI does) succeeds after the POC was deactivated',
    r23a.status === 200, { deleteUser: snip(del), userActive: u3?.active, withSamePoc: snip(r23a), notesOnly: snip(r23b) });

  // custom role with PAYMENT_MANAGE but not POC_ASSIGN
  const roleR = await api('POST', '/api/roles', { name: rt.uniq('PAYCR_NOASSIGN'), description: 'rt', privileges: ['PAYMENT_VIEW', 'PAYMENT_MANAGE', 'POC_VIEW', 'CUSTOMER_VIEW', 'INVOICE_VIEW'] });
  const uname = rt.uniq('paycr');
  const uR = await api('POST', '/api/users', { username: uname, email: `${uname}@rt.local`, fullName: uname, password: rt.PASSWORD, roleId: roleR.json?.id, active: true });
  const naTok = await rt.login(uname, rt.PASSWORD);
  const r24a = await api('PATCH', `/api/payments/${p100c.id}`, { notes: 'by no-assign', collectionPocUserId: poc1.id }, naTok);
  const r24b = await api('PATCH', `/api/payments/${p100c.id}`, { notes: 'by no-assign' }, naTok);
  const r24c = await api('PATCH', `/api/payments/${p100c.id}`, { collectionPocUserId: poc2.id }, naTok);
  rec('PAY-24', 'role with PAYMENT_MANAGE but no POC_ASSIGN: notes-only save works when the unchanged POC id is resent; changing POC refused',
    r24a.status === 200 && r24b.status === 200 && r24c.status >= 400,
    { role: roleR.status, user: uR.status, sameIdResent: snip(r24a), notesOnly: snip(r24b), changePoc: snip(r24c) });

  // ---- permissions -------------------------------------------------------------------
  const vTok = await rt.login(viewer.username, viewer.password);
  const sTok = await rt.login(sales.username, sales.password);
  const p1Tok = await rt.login(poc1.username, poc1.password);
  const c1Tok = await rt.login(c1.username, c1.password);
  const perm = {
    viewerPost: (await pay({ customerId: c1.id, amount: 1, collectionPocUserId: poc1.id }, vTok)).status,
    viewerPatch: (await api('PATCH', `/api/payments/${p150.id}`, { notes: 'v' }, vTok)).status,
    viewerGet: (await api('GET', `/api/payments/${p150.id}`, undefined, vTok)).status,
    salesPost: (await pay({ customerId: c1.id, amount: 1, collectionPocUserId: poc1.id }, sTok)).status,
    customerPost: (await pay({ customerId: c1.id, amount: 1, collectionPocUserId: poc1.id }, c1Tok)).status,
    anonList: (await rt.api('GET', '/api/payments')).status,
  };
  const cashPay = await pay({ customerId: c5.id, amount: 11, collectionPocUserId: poc1.id }, cashier);
  const pocPay = await pay({ customerId: c5.id, amount: 12, collectionPocUserId: poc1.id }, p1Tok);
  rec('PAY-25', 'permissions: VIEWER/SALES_POC/CUSTOMER cannot record or edit (403), anonymous 401; CASHIER and COLLECTION_POC can record',
    perm.viewerPost === 403 && perm.viewerPatch === 403 && perm.viewerGet === 200 && perm.salesPost === 403 && perm.customerPost === 403
      && perm.anonList === 401 && cashPay.status === 200 && pocPay.status === 200,
    { ...perm, cashierPost: cashPay.status, collectionPocPost: pocPay.status });

  const own = await api('GET', `/api/payments/${p150.id}`, undefined, c1Tok);
  const other = await api('GET', `/api/payments/${cashPay.json.id}`, undefined, c1Tok);
  const ownCr = await api('GET', `/api/payments/credits/${c1.id}`, undefined, c1Tok);
  const otherCr = await api('GET', `/api/payments/credits/${c5.id}`, undefined, c1Tok);
  const custList = (await api('GET', '/api/payments?size=50', undefined, c1Tok)).json;
  rec('PAY-26', 'customer login: own payment 200 without POC identity, other customer 403, credits own 200 / other 403, list only own',
    own.status === 200 && own.json.collectionPoc === null && other.status === 403 && ownCr.status === 200 && otherCr.status === 403
      && custList.content.every((p) => p.customerId === c1.id) && custList.totalElements === (await payCount(c1.id))
      && !JSON.stringify(custList).includes(poc1.username),
    { own: own.status, ownPoc: own.json?.collectionPoc, ownPocMissing: own.json?.pocMissing, other: other.status, ownCr: ownCr.json, otherCr: otherCr.status,
      listTotal: custList.totalElements, lockedFilters: custList.lockedFilters });

  const nf = [await api('GET', '/api/payments/99999999'), await api('GET', '/api/payments/credits/99999999')];
  rec('PAY-27', 'GET unknown payment / credits of unknown customer -> 404', nf[0].status === 404 && nf[1].status === 404, nf.map(snip));

  // ---- list paging / sort / filter --------------------------------------------------
  const c1count = await payCount(c1.id);
  const l1 = (await api('GET', `/api/payments?filter=customerId:eq:${c1.id}&size=10`)).json;
  const l2 = (await api('GET', `/api/payments?customerId=${c1.id}&size=10&sort=amount,asc`)).json;
  const pgA = (await api('GET', `/api/payments?filter=customerId:eq:${c1.id}&size=10&page=0&sort=amount,desc`)).json;
  const bad = await Promise.all([
    api('GET', '/api/payments?size=7'),
    api('GET', '/api/payments?sort=collectionPocUserId,asc'),
    api('GET', '/api/payments?filter=bogus:eq:1'),
    api('GET', '/api/payments?filter=amount:contains:1'),
  ]);
  const amts = l2.content.map((p) => n(p.amount));
  const paid = l1.content.map((p) => p.paidAt);
  rec('PAY-28', 'list: customer filter (filter= and ?customerId=), default paidAt desc, amount asc sort, envelope fields',
    l1.totalElements === c1count && l1.content.every((p) => p.customerId === c1.id) && l1.size === 10 && l1.sort.startsWith('paidAt,desc')
      && paid.every((x, i) => i === 0 || x <= paid[i - 1]) && amts.every((x, i) => i === 0 || x >= amts[i - 1]) && l2.totalElements === c1count
      && pgA.content.length === Math.min(10, c1count),
    { total: l1.totalElements, sort: l1.sort, applied: l1.appliedFilters, amountsAsc: amts, totalPages: l1.totalPages });
  rec('PAY-29', 'list: bad size / non-sortable column / unknown column / wrong operator -> 400',
    bad.every((x) => x.status === 400), bad.map(snip));

  const l3 = (await api('GET', `/api/payments?filter=customerId:eq:${c1.id}&filter=collectionPocUserId:eq:${poc2.id}`)).json;
  const l4 = (await api('GET', `/api/payments?filter=customerId:eq:${c1.id}&filter=amount:between:100,300`)).json;
  rec('PAY-30', 'list: combined filters (customer AND Collection POC; amount between 100,300)',
    l3.totalElements === 1 && l3.content[0].id === p150.id && l4.content.every((p) => n(p.amount) >= 100 && n(p.amount) <= 300)
      && l4.totalElements === 3,
    { pocFilter: l3.content.map((p) => p.id), between: l4.content.map((p) => n(p.amount)) });

  // ---- summary + void via dispute ----------------------------------------------------
  const sumOf = async (cid) => (await api('GET', `/api/payments/summary?filter=customerId:eq:${cid}`)).json;
  const s1 = await sumOf(c1.id);
  const all1 = (await api('GET', `/api/payments?filter=customerId:eq:${c1.id}&size=50`)).json.content;
  const expTotal = all1.filter((p) => p.status === 'ACTIVE').reduce((s, p) => s + n(p.amount), 0);
  const expCredit = all1.filter((p) => p.status === 'ACTIVE').reduce((s, p) => s + n(p.creditApplied), 0);
  rec('PAY-31', 'summary over customer filter: count, totalCollected, creditApplied, activeCount, voidedCount, pocMissingCount',
    s1.count === all1.length && n(s1.totalCollected) === expTotal && n(s1.creditApplied) === expCredit && s1.activeCount === all1.length
      && s1.voidedCount === 0 && s1.pocMissingCount === 0,
    { summary: s1, expTotal, expCredit, rows: all1.length });

  // void p100c (the explicit-C payment) through the dispute flow
  const disp = await api('POST', '/api/disputes', { targetType: 'PAYMENT', targetId: p100c.id, reason: 'rt void', proposedChangeJson: '{"action":"void"}' }, c1Tok);
  const appr = await api('POST', `/api/disputes/${disp.json?.id}/approve`, { adminNotes: 'ok' });
  const gv = (await api('GET', `/api/payments/${p100c.id}`)).json;
  const icv = await inv(C.id);
  const s2 = await sumOf(c1.id);
  rec('PAY-32', 'after voiding a payment: status VOIDED, allocation reversed on the invoice, summary totalCollected drops by its amount, voidedCount 1',
    appr.status === 200 && gv.status === 'VOIDED' && n(icv.paidAmount) === 200 && icv.status === 'PARTIALLY_PAID'
      && n(s2.totalCollected) === n(s1.totalCollected) - 100 && s2.voidedCount === 1 && s2.activeCount === s1.activeCount - 1 && s2.count === s1.count,
    { dispute: snip(disp), approve: appr.status, payment: gv.status, allocationsAfter: gv.invoices?.length, C: invState(icv), before: s1, after: s2 });

  // void a payment whose overpayment credit was already consumed by a later invoice (c5)
  const K = await mkInv(c5.id, 100, '2026-01-01T10:00:00Z');
  const c5Tok = await rt.login(c5.username, c5.password);
  const cr33a = await credit(c5.id); // 23 from the cashier/poc payments above
  const pOver = (await pay({ customerId: c5.id, amount: 200, collectionPocUserId: poc1.id })).json; // K 100 - 23 = 77 applied? no: K was paid 23 by credit on create
  const cr33b = await credit(c5.id);
  const L = await mkInv(c5.id, 150, '2026-02-01T10:00:00Z');
  const cr33c = await credit(c5.id);
  const d33 = await api('POST', '/api/disputes', { targetType: 'PAYMENT', targetId: pOver.id, reason: 'rt void consumed credit', proposedChangeJson: '{"action":"void"}' }, c5Tok);
  const a33 = await api('POST', `/api/disputes/${d33.json?.id}/approve`, { adminNotes: 'ok' });
  const [iK, iL] = [await inv(K.id), await inv(L.id)];
  const cr33d = await credit(c5.id);
  const activeSum = (await sumOf(c5.id));
  const paidOnInvoices = n(iK.paidAmount) + n(iL.paidAmount);
  rec('PAY-33', 'voiding a payment whose credit was already consumed keeps money consistent (invoice paid total <= active collections)',
    a33.status === 200 && paidOnInvoices <= n(activeSum.totalCollected),
    { creditBefore: cr33a, K: invState(iK), pOver: { creditApplied: pOver.creditApplied, alloc: pOver.invoices?.map((x) => n(x.allocatedAmount)) },
      creditAfterPay: cr33b, L_on_create: invState(L), creditAfterL: cr33c, approve: a33.status, creditAfterVoid: cr33d,
      paidOnInvoicesAfterVoid: paidOnInvoices, activeCollected: activeSum.totalCollected });

  // ---- bulk REASSIGN_COLLECTION_POC -------------------------------------------------
  const pA = p1000.id, pB = p300.id;
  r = await api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [pA, pB], params: { userId: poc2.id } }, cashier);
  const [gA, gB] = [(await api('GET', `/api/payments/${pA}`)).json, (await api('GET', `/api/payments/${pB}`)).json];
  const auA34 = ((await api('GET', `/api/audit?entityType=PAYMENT&entityId=${pA}`)).json || []).filter((e) => e.action === 'PAYMENT_UPDATED');
  rec('PAY-34', 'bulk REASSIGN_COLLECTION_POC (cashier) on 2 ids: both succeeded, persisted, audited per record',
    r.status === 200 && r.json.requested === 2 && r.json.succeeded.length === 2 && gA.collectionPoc?.id === poc2.id && gB.collectionPoc?.id === poc2.id
      && auA34.length >= 1,
    { status: r.status, result: r.json, auditRowsA: auA34.length });

  const b35 = await Promise.all([
    api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [pA] }),
    api('POST', '/api/payments/bulk', { action: 'VOID', ids: [pA], params: { userId: poc2.id } }),
    api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [pA], params: { userId: poc2.id } }, vTok),
    api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', params: { userId: poc2.id } }),
  ]);
  rec('PAY-35', 'bulk: missing userId 400, VOID not offered 400, VIEWER 403, no ids 400',
    b35[0].status === 400 && b35[1].status === 400 && b35[2].status === 403 && b35[3].status === 400, b35.map(snip));

  r = await api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [pA, pB], params: { userId: sales.id } });
  const g36 = (await api('GET', `/api/payments/${pA}`)).json;
  rec('PAY-36', 'bulk to a non-assignable user: every id reported in failed with a reason, nothing changed',
    r.status === 200 && r.json.failed.length === 2 && r.json.succeeded.length === 0 && g36.collectionPoc?.id === poc2.id,
    { status: r.status, result: r.json });

  r = await api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [pA, 99999999], params: { userId: poc1.id } });
  rec('PAY-37', 'bulk with one unknown id: every requested id accounted for (succeeded/failed/skipped) (AC-D5)',
    r.status === 200 && r.json.requested === 2 && (r.json.succeeded.length + r.json.failed.length + r.json.skipped.length) === 2,
    { status: r.status, result: r.json });

  const filt = [`customerId:eq:${c1.id}`, 'status:eq:ACTIVE'];
  const cnt38 = (await api('GET', `/api/payments?filter=${filt[0]}&filter=${filt[1]}`)).json.totalElements;
  r = await api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', selectAllMatchingFilter: true, filters: filt, params: { userId: poc1.id } });
  const l38 = (await api('GET', `/api/payments?filter=${filt[0]}&filter=${filt[1]}&filter=collectionPocUserId:eq:${poc1.id}`)).json.totalElements;
  rec('PAY-38', 'bulk select-all-matching (customer + ACTIVE) reassigns exactly the filtered set',
    r.status === 200 && r.json.requested === cnt38 && r.json.succeeded.length === cnt38 && l38 === cnt38 && r.json.truncated === false,
    { matching: cnt38, result: { requested: r.json?.requested, succeeded: r.json?.succeeded?.length, truncated: r.json?.truncated }, nowOnPoc1: l38 });

  // ---- export ------------------------------------------------------------------------
  r = await api('POST', '/api/payments/export', { ids: [pA, pB] });
  const lines = r.text.trim().split(/\r?\n/);

  const ex2 = await api('POST', '/api/payments/export', { selectAllMatchingFilter: true, filters: [`customerId:eq:${c1.id}`] });
  const ex3 = await api('POST', '/api/payments/export', { selectAllMatchingFilter: true, filters: [`customerId:eq:${c1.id}`] }, c1Tok);
  const ex4 = await api('POST', '/api/payments/export', { ids: [pA] }, vTok);
  rec('PAY-39', 'export: ids -> CSV header + 2 rows incl. Collection POC; select-all filter -> all rows; CUSTOMER/VIEWER 403',
    r.status === 200 && /text\/csv/.test(r.headers['content-type']) && lines[0].includes('Payment #') && lines[0].includes('Collection POC')
      && lines.length === 3 && ex2.text.trim().split(/\r?\n/).length === c1count + 1 && ex3.status === 403 && ex4.status === 403,
    { status: r.status, ct: r.headers['content-type'], csv: lines, allRows: ex2.text.trim().split(/\r?\n/).length - 1, c1count, customer: ex3.status, viewer: ex4.status });

  // ---- notification on create -------------------------------------------------------
  const n40 = (await api('GET', '/api/notifications?size=50', undefined, p1Tok)).json?.content || [];
  rec('PAY-40', 'Collection POC receives an in-app notification with /payments/{id} link when a payment is recorded for them',
    n40.some((x) => (x.link || '') === `/payments/${p20.id}`), { notifs: n40.slice(0, 6).map((x) => [x.title, x.link]) });

  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify({ out, ids: {
    poc1: poc1.id, poc2: poc2.id, poc1user: poc1.username, poc2user: poc2.username, sales: sales.id, c1: c1.id, c1name: c1.name,
    c1user: c1.username, c3: c3.id, c4: c4.id, c5: c5.id, p150: p150.id, p1000: p1000.id, p300: p300.id, p100c: p100c.id, p20: p20.id,
    promise: pr.id, invA: A.id, invF: F.id, invFno: F.invoiceNumber, invAno: A.invoiceNumber, unknownIdPayment, prod: prod.id,
  } }, null, 2));
  console.log('DONE', out.filter((x) => x.status === 'FAIL').map((x) => x.id));
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
