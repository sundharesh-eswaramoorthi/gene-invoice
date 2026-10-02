// Round 4: one step around the round-3 fixes in PendingChangeViews (ASO-R3-1 names as of a date; RGN-R3-1
// blockedReason "You no longer have access to this record"). `live` runs before the UI cases; `asOf` runs last,
// because it ages the whole database (rt.ageWorld) and would disturb every later case.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, invoiceBy, promiseBy, brief, pick, openAs, waitText, allText } = H;
const VIEWS = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java';
const SCOPE = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java';
const DETAIL = 'frontend/lib/features/approvals/approval_detail_screen.dart';
const NO_ACCESS = 'You no longer have access to this record';
const RO = 'Read-only in the as-of view';
const flags = (j) => pick(j, ['status', 'statusAt', 'canApprove', 'canReject', 'canWithdraw', 'blockedReason']);

async function staff(w, username, grants) {
  const admin = w.tokens.admin;
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName: `R4 ${username}`, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
  w.users[username] = { ...u, grants };
  w.tokens[username] = await rt.login(username);
  return u;
}

async function live(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  const U = w.users;
  const SOUTH = w.regions.SOUTH.id;
  // bea: APPROVE in South but only her book (she is POC of nothing unless a case makes her one).
  await staff(w, 'bea', [{ regionId: SOUTH, accessLevel: 'APPROVE', recordScope: 'BOOK' }]);
  const termsHeld = async (maker, cu) => {
    const inv = await invoiceBy(T.admin, cu, 3000);
    const r = await rt.patchInvoice(maker, inv.id, { paymentTerm: 'NET_90' });
    return { inv, r };
  };

  await tcase({ id: 'APP-R4-01', feature: 'Book-less approver (RGN-R3-1)', kind: 'API', prd: 'B2',
    title: 'An approver without the record in her book sees "You no longer have access to this record", can still approve, and the approval applies',
    steps: 'bea = APPROVE/BOOK South, POC of nothing; fresh South customer (POC sam); admin invoice 3000; mia PATCH paymentTerm NET_90 (held INVOICE_TERMS); bea GET /api/approvals/{id}; admin GET; bea POST approve; bea GET again',
    expected: 'TRIAGE r3 userDecision RGN-R3-1 / DESIGN §5.11.9 SC-15: bea 200, blockedReason "You no longer have access to this record", canApprove=true canReject=true, current=null; admin blockedReason null; bea approve -> 200 APPROVED; invoice NET_90' }, async () => {
    const c = checks();
    const cu = await customerIn(w);
    const { inv, r } = await termsHeld(T.mia, cu);
    c.that('terms change held', r.held, brief(r));
    const id = r.pendingChange?.id;
    const b = await rt.approvals.get(T.bea, id);
    c.that('bea 200', b.status === 200, brief(b));
    c.that('bea blockedReason no-access', b.json?.blockedReason === NO_ACCESS, flags(b.json));
    c.that('bea canApprove & canReject true', b.json?.canApprove === true && b.json?.canReject === true, flags(b.json));
    c.that('bea current null', b.json?.current == null, b.json?.current);
    c.that('bea changes still listed (paymentTerm Net 90)', (b.json?.changes || []).some((x) => x.field === 'paymentTerm' && /90/.test(x.proposedText || '')), b.json?.changes);
    const a = await rt.approvals.get(T.admin, id);
    c.that('admin blockedReason null, canApprove', a.json?.blockedReason == null && a.json?.canApprove === true, flags(a.json));
    c.that('admin sees current', a.json?.current != null, a.json?.current);
    const bl = await rt.list(T.bea, '/api/approvals', { view: 'AWAITING_ME' });
    c.that('in bea\'s Awaiting-me list', (bl.json?.content || []).some((x) => x.id === id), (bl.json?.content || []).map((x) => x.id));
    const ap = await rt.approvals.approve(T.bea, id);
    c.that('bea approve 200 APPROVED', ap.status === 200 && ap.json?.status === 'APPROVED', brief(ap).slice(0, 300));
    c.that('after approval bea still told no-access (any status)', ap.json?.blockedReason === NO_ACCESS, flags(ap.json));
    const g = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin })).json;
    c.that('invoice NET_90', g?.paymentTerm === 'NET_90', g?.paymentTerm);
    const gb = await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.bea });
    c.that('bea still cannot open the invoice (404/403)', [403, 404].includes(gb.status), gb.status);
    return { ok: c.ok, actual: c.summary(), evidence: `pendingChange ${id}; bea GET ${JSON.stringify(flags(b.json))}`, codeRef: `${VIEWS} blockedReason` };
  });

  await tcase({ id: 'APP-R4-02', feature: 'Book-less approver (RGN-R3-1)', kind: 'API', prd: 'B2',
    title: 'A held create is judged by customer visibility: a BOOK approver who is the customer\'s POC gets no access message; one who is not gets it',
    steps: 'fresh South customer A with bea as an extra (non-primary) Collection POC; fresh South customer B; mia POST /api/payments 7000 on each (held); bea GET both',
    expected: 'TRIAGE r3 RGN-R3-1 "A held create (entityId null) is judged by customer visibility": on A blockedReason null + canApprove true; on B "You no longer have access to this record" + canApprove true' }, async () => {
    const c = checks();
    const A = await customerIn(w);
    await rt.addCustomerPoc(T.admin, A.id, 'COLLECTION', U.bea.id, false);
    const B = await customerIn(w);
    const pa = await rt.createPayment(T.mia, { customerId: A.id, amount: 7000, collectionPocUserId: U.sam.id });
    const pb = await rt.createPayment(T.mia, { customerId: B.id, amount: 7000, collectionPocUserId: U.sam.id });
    c.that('both held', pa.held && pb.held, `${pa.status}/${pb.status}`);
    const da = await rt.approvals.get(T.bea, pa.pendingChange?.id);
    const db = await rt.approvals.get(T.bea, pb.pendingChange?.id);
    c.that('A: 200, blockedReason null, canApprove', da.status === 200 && da.json?.blockedReason == null && da.json?.canApprove === true, `${da.status} ${JSON.stringify(flags(da.json))}`);
    c.that('B: 200, no-access, canApprove', db.status === 200 && db.json?.blockedReason === NO_ACCESS && db.json?.canApprove === true, `${db.status} ${JSON.stringify(flags(db.json))}`);
    for (const p of [pa, pb]) if (p.pendingChange) await rt.approvals.withdraw(T.mia, p.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), codeRef: `${SCOPE} canSeeRecord` };
  });

  await tcase({ id: 'APP-R4-03', feature: 'Maker loses the record (RGN-R3-1)', kind: 'API', prd: 'B2',
    title: 'A maker narrowed to BOOK after requesting still sees the change, is told she no longer has access, and can still withdraw it',
    steps: 'mo = MANAGE/ALL South; fresh South customer; mo POST /api/payments 7000 (held); admin sets mo to MANAGE/BOOK South; mo GET /api/approvals/{id}; mo POST withdraw',
    expected: 'TRIAGE r3 precedence: blockedReason "You no longer have access to this record" ahead of "You requested this change"; canWithdraw unchanged (true); withdraw 200 WITHDRAWN' }, async () => {
    const c = checks();
    const mo = await staff(w, 'mo', [{ regionId: SOUTH, accessLevel: 'MANAGE', recordScope: 'ALL' }]);
    const cu = await customerIn(w);
    const p = await rt.createPayment(T.mo, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id });
    c.that('held', p.held, brief(p));
    const before = await rt.approvals.get(T.mo, p.pendingChange?.id);
    c.that('before: "You requested this change"', before.json?.blockedReason === 'You requested this change', flags(before.json));
    rt.need(await rt.setGrants(T.admin, mo.id, [{ regionId: SOUTH, accessLevel: 'MANAGE', recordScope: 'BOOK' }]), 'narrow mo');
    T.mo = await rt.login('mo');
    const d = await rt.approvals.get(T.mo, p.pendingChange?.id);
    c.that('after: 200', d.status === 200, brief(d));
    c.that('after: no-access reason, canWithdraw true', d.json?.blockedReason === NO_ACCESS && d.json?.canWithdraw === true && d.json?.canApprove === false, flags(d.json));
    const wd = await rt.approvals.withdraw(T.mo, p.pendingChange?.id);
    c.that('withdraw 200 WITHDRAWN', wd.status === 200 && wd.json?.status === 'WITHDRAWN', brief(wd).slice(0, 300));
    rt.need(await rt.setGrants(T.admin, mo.id, [{ regionId: SOUTH, accessLevel: 'MANAGE', recordScope: 'ALL' }]), 'restore mo');
    T.mo = await rt.login('mo');
    return { ok: c.ok, actual: c.summary(), codeRef: `${VIEWS} blockedReason` };
  });

  await tcase({ id: 'APP-R4-04', feature: 'Who is told what (RGN-R3-1 neighbours)', kind: 'API', prd: 'B2',
    title: 'The new no-access reason does not leak onto callers who can see the record: READ viewer, customer login, threshold approver',
    steps: 'held INVOICE_TERMS on s1\'s new invoice (fixture maker); GET as nora (READ South), cust (s1\'s login), mia (MANAGE South, not maker); admin loosens West PAYMENT_RECORD (held THRESHOLD_CHANGE), GET as sam? no: as admin (maker) and bea',
    expected: 'nora: "You don\'t hold the approval right in South"; cust: 200 blockedReason null and no requester; mia: "You don\'t hold the approval right in South"; threshold change: maker "You requested this change"' }, async () => {
    const c = checks();
    const s1 = { ...w.customers.s1, pocId: U.sam.id };
    const { r } = await termsHeld(w.nextFx(), s1);
    c.that('held', r.held, brief(r));
    const id = r.pendingChange?.id;
    const n = await rt.approvals.get(T.nora, id);
    c.that('nora approval-right reason', /approval right in South/.test(n.json?.blockedReason || ''), `${n.status} ${JSON.stringify(flags(n.json))}`);
    const cs = await rt.approvals.get(T.cust, id);
    c.that('cust 200 blockedReason null', cs.status === 200 && cs.json?.blockedReason == null, `${cs.status} ${JSON.stringify(flags(cs.json))}`);
    c.that('cust no requester', cs.json?.requestedBy == null, cs.json?.requestedBy);
    const m = await rt.approvals.get(T.mia, id);
    c.that('mia approval-right reason', /approval right in South/.test(m.json?.blockedReason || ''), `${m.status} ${JSON.stringify(flags(m.json))}`);
    const cur = (await rt.getThresholds(T.admin, w.regions.WEST.id)).json;
    const rows = Array.isArray(cur) ? cur : (cur?.thresholds || cur?.content || []);
    const pr = rows.find((x) => x.operation === 'PAYMENT_RECORD');
    const put = await rt.setThresholds(T.admin, w.regions.WEST.id, [{ operation: 'PAYMENT_RECORD', amount: null, windowHours: 24 }]);
    const tid = put.pendingChange?.id;
    if (tid) {
      const tm = await rt.approvals.get(T.admin, tid);
      c.that('threshold change: maker told "You requested this change"', tm.json?.blockedReason === 'You requested this change', flags(tm.json));
      await rt.approvals.withdraw(T.admin, tid);
    } else {
      c.that('threshold removal held (or no West limit to loosen)', !pr || pr.amount == null, `${put.status} ${String(put.text).slice(0, 200)}`);
    }
    if (id) await rt.approvals.approve(T.admin, id);
    return { ok: c.ok, actual: c.summary(), codeRef: `${VIEWS} blockedReason` };
  });
}

async function liveUi(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  const U = w.users;
  await tcase({ id: 'APP-R4-UI-01', feature: 'Book-less approver (RGN-R3-1)', kind: 'UI', prd: 'B2',
    title: 'The book-less approver\'s approval page shows "You no longer have access to this record" and still offers Approve and Reject',
    steps: 'fresh South customer; admin invoice 3000; mia PATCH NET_90 (held); bea opens /approvals/{id}',
    expected: 'DESIGN §7.7.1 (buttons per server flags; blockedReason shown verbatim, approval_detail_screen.dart:173): notice text shown; Approve and Reject buttons present; no crash' }, async (attempt) => {
    const c = checks();
    const cu = await customerIn(w);
    const inv = await invoiceBy(T.admin, cu, 3000);
    const r = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    c.that('held', r.held, brief(r));
    const app = await openAs('bea', `/approvals/${r.pendingChange?.id}`);
    let shot = '';
    try {
      c.that('notice shown', !!(await waitText(app.page, NO_ACCESS, 10000)), (await allText(app.page)).slice(0, 500));
      c.that('Approve button', await rt.exists(app.page, 'Approve', { role: 'button' }));
      c.that('Reject button', await rt.exists(app.page, 'Reject', { role: 'button' }));
      c.that('no Withdraw', !(await rt.exists(app.page, 'Withdraw', { role: 'button' })));
      c.that('Net 90 in the Changes table', /Net 90/.test(await allText(app.page)));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shot = await rt.shot(app.page, R.shotsDir, `APP-R4-UI-01-${attempt}`);
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 3));
      await app.close();
    }
    if (r.pendingChange) await rt.approvals.withdraw(T.mia, r.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), screenshot: shot, codeRef: DETAIL };
  });
}

async function asOf(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  const U = w.users;
  const tag = rt.uniq('r4');
  const prodName = `R4 Gadget ${tag}`;
  // ---- fixtures, before ageing: all held by mia on one fresh South customer
  const cu = await customerIn(w, 'SOUTH', 'r4asof');
  const oldCustName = cu.name;
  const P = await rt.ensureProduct(T.admin, prodName, 300);
  const X = await invoiceBy(T.admin, cu, 2000);
  const pr = await promiseBy(T.admin, cu, 600);
  const pr2 = await promiseBy(T.admin, cu, 3000);
  const A = await rt.createInvoice(T.mia, { customerId: cu.id, items: [{ productId: P.id, quantity: 50, unitPrice: 300 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  const B = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id, invoiceIds: [X.id], promiseIds: [pr.id] });
  const C = rt.heldAware(await rt.api('PUT', `/api/promises/${pr2.id}`, { token: T.mia, body: { amount: 4500, promisedDate: pr2.promisedDate, collectionPocUserId: U.sam.id, notes: null, invoiceIds: [X.id] } }));
  const D = await rt.createInvoice(T.mia, { customerId: cu.id, items: [{ productId: P.id, quantity: 40, unitPrice: 300 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  const E = await rt.createInvoice(T.mia, { customerId: cu.id, items: [{ productId: P.id, quantity: 35 }], dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
  const heldAll = [A, B, C, D, E].every((h) => h.held);
  const dApproved = D.held ? await rt.approvals.approve(T.admin, D.pendingChange.id) : null;
  const id = (h) => h.pendingChange?.id;
  // ---- age everything 10 days, then rename the product and the customer, then decide E
  rt.ageWorld(10);
  const newProd = `${prodName} Max`;
  const newCust = `Omega ${tag}`;
  const pp = await rt.api('PUT', `/api/products/${P.id}`, { token: T.admin, body: { name: newProd, description: 'e2e', price: 300, active: true } });
  const cp = await rt.api('PUT', `/api/customers/${cu.id}`, { token: T.admin, body: { name: newCust, paymentTerm: 'NET_30' } });
  const eApproved = E.held ? await rt.approvals.approve(T.admin, id(E)) : null;
  const AT = rt.day(-2);
  const Xnow = (await rt.api('GET', `/api/invoices/${X.id}`, { token: T.admin })).json;
  const prNow = (await rt.api('GET', `/api/promises/${pr.id}`, { token: T.admin })).json;
  const fixture = `held ${[A, B, C, D, E].map((h) => `${h.status}:${id(h)}`).join(',')}; D approve ${dApproved?.status}; product PUT ${pp.status}; customer PUT ${cp.status}; E approve ${eApproved?.status}`;
  const setupOk = (c) => {
    c.that('fixtures held', heldAll, fixture);
    c.that('renames applied', pp.status === 200 && cp.status === 200, fixture);
  };
  const row = (d, f) => (d?.changes || []).find((x) => x.field === f);

  await tcase({ id: 'APP-R4-10', feature: 'Approval detail as of a date (ASO-R3-1)', kind: 'API', prd: 'B3',
    title: 'As of before a product rename, a held invoice create names its lines by the OLD product name; live by the new one',
    steps: `mia holds invoice create 50 × "${prodName}" at 300; ageWorld(10); admin renames product to "${newProd}"; admin GET /api/approvals/{id}?asOf=${AT}; then live`,
    expected: 'TRIAGE r3 userDecision ASO-R3-1: as-of 200, items proposedText "50 × <old name> at ₹300.00", blockedReason "Read-only in the as-of view", canApprove/canReject/canWithdraw false, current null, statusAt PENDING; live 200 with "<new name>"' }, async () => {
    const c = checks(); setupOk(c);
    const h = await rt.approvals.get(T.admin, id(A), { asOf: AT });
    c.that('as-of 200', h.status === 200, brief(h));
    c.that('as-of items old name', row(h.json, 'items')?.proposedText === `50 × ${prodName} at ₹300.00`, row(h.json, 'items'));
    c.that('read-only flags', h.json?.blockedReason === RO && !h.json?.canApprove && !h.json?.canReject && !h.json?.canWithdraw, flags(h.json));
    c.that('statusAt PENDING, current null', h.json?.statusAt === 'PENDING' && h.json?.current == null, flags(h.json));
    const l = await rt.approvals.get(T.admin, id(A));
    c.that('live 200 new name', l.status === 200 && row(l.json, 'items')?.proposedText === `50 × ${newProd} at ₹300.00`, row(l.json, 'items') || brief(l));
    c.that('live admin can approve', l.json?.canApprove === true && l.json?.blockedReason == null, flags(l.json));
    return { ok: c.ok, actual: c.summary(), evidence: fixture, codeRef: `${VIEWS} names()` };
  }, { retry: false });

  await tcase({ id: 'APP-R4-11', feature: 'Approval detail as of a date (ASO-R3-1)', kind: 'API', prd: 'B3',
    title: 'As of a date, a held payment names its invoice and its promise; a held promise update names None -> invoice',
    steps: `mia holds payment 7000 invoiceIds [X] promiseIds [600 promise] and a promise update invoiceIds [X]; ageWorld(10); GET both ?asOf=${AT} as admin, nora (READ South) and bea (APPROVE/BOOK)`,
    expected: 'ASO-R3-1: 200; Invoices = X\'s number; Promises = "Promise of ₹600.00 by <promisedDate>"; promise update Invoices current "None"? (currentText from base) -> X\'s number; nora and bea also 200 with "Read-only in the as-of view"' }, async () => {
    const c = checks(); setupOk(c);
    const hb = await rt.approvals.get(T.admin, id(B), { asOf: AT });
    c.that('payment as-of 200', hb.status === 200, brief(hb));
    c.that('Invoices = X number', row(hb.json, 'invoiceIds')?.proposedText === Xnow?.invoiceNumber, { row: row(hb.json, 'invoiceIds'), X: Xnow?.invoiceNumber });
    c.that('Promises = promise text', row(hb.json, 'promiseIds')?.proposedText === `Promise of ₹600.00 by ${prNow?.promisedDate}`, { row: row(hb.json, 'promiseIds'), date: prNow?.promisedDate });
    const hc = await rt.approvals.get(T.admin, id(C), { asOf: AT });
    c.that('promise update as-of 200', hc.status === 200, brief(hc));
    c.that('promise update Invoices -> X number', row(hc.json, 'invoiceIds')?.proposedText === Xnow?.invoiceNumber, row(hc.json, 'invoiceIds'));
    for (const u of ['nora', 'bea']) {
      for (const [k, h] of [['payment', B], ['promise update', C], ['invoice create', A]]) {
        const g = await rt.approvals.get(T[u], id(h), { asOf: AT });
        c.that(`${u} ${k} as-of 200 read-only`, g.status === 200 && g.json?.blockedReason === RO && !g.json?.canApprove, `${g.status} ${JSON.stringify(flags(g.json))} ${g.status !== 200 ? String(g.text).slice(0, 200) : ''}`);
      }
    }
    return { ok: c.ok, actual: c.summary(), evidence: fixture, codeRef: `${VIEWS} names()` };
  }, { retry: false });

  await tcase({ id: 'APP-R4-12', feature: 'Approval detail as of a date (ASO-R3-1)', kind: 'API', prd: 'B3',
    title: 'As of a date, a change decided before it shows APPROVED; one decided after it shows PENDING with no decision; lists agree',
    steps: `D approved before ageing; E approved after ageing; GET D and E ?asOf=${AT}; GET /api/approvals?asOf=${AT}; GET /api/approvals/for-record?entityType=CUSTOMER&entityId=<cu>&asOf=${AT}`,
    expected: 'DESIGN §6.11 / D8: D statusAt APPROVED (names old product); E statusAt PENDING, decidedBy/decidedAt null; list as-of contains A,B,C,E but not D; for-record CUSTOMER contains A,B,E (creates outstanding then)' }, async () => {
    const c = checks(); setupOk(c);
    c.that('D and E approved', dApproved?.json?.status === 'APPROVED' && eApproved?.json?.status === 'APPROVED', fixture);
    const d = await rt.approvals.get(T.admin, id(D), { asOf: AT });
    c.that('D 200 statusAt APPROVED', d.status === 200 && d.json?.statusAt === 'APPROVED', `${d.status} ${JSON.stringify(flags(d.json))}`);
    c.that('D items old name', row(d.json, 'items')?.proposedText === `40 × ${prodName} at ₹300.00`, row(d.json, 'items'));
    const e = await rt.approvals.get(T.admin, id(E), { asOf: AT });
    c.that('E 200 statusAt PENDING, decision hidden', e.status === 200 && e.json?.statusAt === 'PENDING' && e.json?.decidedBy == null && e.json?.decidedAt == null && e.json?.result == null, `${e.status} ${JSON.stringify(pick(e.json, ['statusAt', 'decidedBy', 'decidedAt', 'result']))}`);
    // The server stamps the list price (300) into a held create's line when none is given (probe r4), so either text is right.
    c.that('E items "35 × <old> at ₹300.00|list price"', [`35 × ${prodName} at ₹300.00`, `35 × ${prodName} at list price`].includes(row(e.json, 'items')?.proposedText), row(e.json, 'items'));
    const l = await rt.list(T.admin, '/api/approvals', { view: 'ALL' }, { asOf: AT });
    const ids = (l.json?.content || []).map((x) => x.id);
    c.that('list as-of 200', l.status === 200, brief(l));
    c.that('list has A,B,C,E', [A, B, C, E].every((h) => ids.includes(id(h))), ids);
    c.that('list lacks D', !ids.includes(id(D)), ids);
    const fr = await rt.approvals.forRecord(T.admin, 'CUSTOMER', cu.id, { asOf: AT });
    const fids = (fr.json || []).map((x) => x.id);
    c.that('for-record CUSTOMER as-of has A,B,E', fr.status === 200 && [A, B, E].every((h) => fids.includes(id(h))) && !fids.includes(id(D)), `${fr.status} ${JSON.stringify(fids)}`);
    return { ok: c.ok, actual: c.summary(), evidence: fixture, codeRef: `${VIEWS} / ${SCOPE}` };
  }, { retry: false });

  await tcase({ id: 'APP-R4-UI-10', feature: 'Approval detail as of a date (ASO-R3-1)', kind: 'UI', prd: 'B3',
    title: 'The approval page viewed as of a date shows the old product name, "Read-only in the as-of view" and no buttons; back to today shows the new name and Approve',
    steps: `admin opens /approvals/{A}; chooses as-of ${AT}; reads the page; Back to today`,
    expected: 'DESIGN §7 as-of mode: banner "Viewing … read only"; Items cell "50 × <old name> at ₹300.00"; "Read-only in the as-of view"; no Approve/Reject; live: "<new name>" and Approve' }, async (attempt) => {
    const c = checks(); setupOk(c);
    const app = await openAs('admin', `/approvals/${id(A)}`);
    let shot = ''; let shot2 = '';
    try {
      c.that('live shows new name', !!(await waitText(app.page, new RegExp(newProd.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), 10000)), (await allText(app.page)).slice(0, 500));
      const chip = await rt.chooseAsOf(app.page, AT);
      await app.page.waitForTimeout(1500);
      const t = await allText(app.page);
      shot = await rt.shot(app.page, R.shotsDir, `APP-R4-UI-10-${attempt}-asof`);
      c.that('chip set', /As of/.test(chip || ''), chip);
      c.that('old product name shown', t.includes(`50 × ${prodName} at ₹300.00`) && !t.includes(newProd), t.slice(0, 700));
      c.that('"Read-only in the as-of view"', t.includes(RO), t.slice(0, 400));
      c.that('no Approve/Reject', !(await rt.exists(app.page, 'Approve', { role: 'button' })) && !(await rt.exists(app.page, 'Reject', { role: 'button' })));
      c.that('no "could not load" / error', !/not found|could not|went wrong|AS_OF_UNSUPPORTED/i.test(t), t.slice(0, 300));
      await rt.chooseAsOf(app.page, null);
      await app.page.waitForTimeout(1500);
      const t2 = await allText(app.page);
      shot2 = await rt.shot(app.page, R.shotsDir, `APP-R4-UI-10-${attempt}-live`);
      c.that('back to today: new name + Approve', t2.includes(newProd) && await rt.exists(app.page, 'Approve', { role: 'button' }), t2.slice(0, 500));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
      try { shot = await rt.shot(app.page, R.shotsDir, `APP-R4-UI-10-${attempt}-err`); } catch { /* ignore */ }
    } finally {
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 3));
      c.that('as-of detail request carried asOf', app.requests.some((q) => /\/api\/approvals\/\d+/.test(q.url) && /asOf=/.test(q.url)), app.requests.filter((q) => /approvals/.test(q.url)).map((q) => q.url).slice(0, 6));
      await app.close();
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shot, evidence: `${fixture}; live shot ${shot2}`, codeRef: DETAIL };
  });
}

module.exports = { live, liveUi, asOf };
