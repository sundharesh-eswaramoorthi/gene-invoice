// Round-4 extensions of the regions suite: one step around the RGN-R3-1 fix (blockedReason "You no longer have
// access to this record" when the caller cannot open the record a change is about).
const rt = require('../../lib.js');
const { excerpt } = require('./h.js');

const get = (t, p, query) => rt.api('GET', p, { token: t, query });
const NO_ACCESS = 'You no longer have access to this record';

async function staff(admin, username, grants, fullName) {
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `${username} grants`);
  return { user: u, token: await rt.login(username) };
}

const brief = (d) => ({ status: d.status, pcStatus: d.json?.status, current: d.json?.current == null ? null : 'filled',
  blockedReason: d.json?.blockedReason ?? null, canApprove: d.json?.canApprove, canReject: d.json?.canReject,
  canWithdraw: d.json?.canWithdraw });

async function ext4Cases(F, RUN, { mkCustomer }) {
  const { C, shotsDir } = RUN;
  const { admin, R, T, w } = F;
  const shot = (page, name) => rt.shot(page, shotsDir, name);

  // ext3 set South INVOICE_TERMS 1000 (and abby APPROVE/BOOK South, empty book). Make sure of both.
  const cur = await rt.getThresholds(admin, R.S);
  const terms = (cur.json?.thresholds || cur.json || []).find((r) => r.operation === 'INVOICE_TERMS');
  if (!terms || terms.amount == null || Number(terms.amount) > 1000) {
    const rows = (cur.json?.thresholds || cur.json || []).filter((r) => r.amount != null && r.operation !== 'INVOICE_TERMS')
      .map((r) => ({ operation: r.operation, amount: Number(r.amount), windowHours: r.windowHours }));
    rows.push({ operation: 'INVOICE_TERMS', amount: 1000, windowHours: 24 });
    rt.need(await rt.setThresholds(admin, R.S, rows), 'South INVOICE_TERMS 1000', [200]);
  }
  const abby = await staff(admin, 'abby', [{ regionId: R.S, accessLevel: 'APPROVE', recordScope: 'BOOK' }], 'Abby Bookapprover');
  // sally: APPROVE/ALL South and nothing else.
  const sally = await staff(admin, 'sally', [{ regionId: R.S, accessLevel: 'APPROVE', recordScope: 'ALL' }], 'Sally Southonly');

  async function heldTerms(tag) {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r4-${tag}` });
    const inv = c.invoices[0];
    const r = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    return { c, inv, r };
  }
  F.r4 = {};

  await C({ id: 'REG-142b', feature: 'POC book per region x pending change', prd: 'B1',
    title: 'RGN-R3-1 retest: the book-less approver is told "You no longer have access to this record" and may still decide',
    steps: 'new South customer, invoice 2500 (POC sam); sam PATCH {dueDate:+60d, paymentTerm:CUSTOM} -> 202; abby (APPROVE/BOOK South, empty book) GET /api/invoices/{id} and GET /api/approvals/{pc}',
    expected: 'invoice 404; change 200, current null, blockedReason "You no longer have access to this record", canApprove/canReject true (DESIGN §5.11.9 SC-15: approval is a region right)',
    severity: 'low', codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:191' }, async (n) => {
    const h = await heldTerms(`a${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    F.r4.a = h;
    const inv = await get(abby.token, `/api/invoices/${h.inv.id}`);
    const d = await rt.approvals.get(abby.token, h.r.pendingChange.id);
    const ok = inv.status === 404 && d.status === 200 && d.json.current == null && d.json.blockedReason === NO_ACCESS
      && d.json.canApprove === true && d.json.canReject === true;
    return { ok, actual: { invoice: inv.status, ...brief(d) }, evidence: `pc ${h.r.pendingChange.id}: ${excerpt(d, 300)}` };
  });

  await C({ id: 'REG-143', feature: 'POC book per region x pending change', prd: 'B2',
    title: 'The other callers on the same change keep their own reasons: maker "You requested this change", a seeing approver none, a reader "no approval right"',
    steps: 'change of REG-142b; sam (maker, APPROVE/ALL South) GET; admin GET; tina (READ/ALL South) GET',
    expected: 'sam: current filled, blockedReason "You requested this change", canWithdraw true; admin: current filled, blockedReason null, canApprove true; tina: current filled, blockedReason "You don\'t hold the approval right in South"' }, async (n) => {
    let h = F.r4.a;
    if (n > 1 || !h?.r?.held) h = await heldTerms(`b${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const s = await rt.approvals.get(T.sam, id); const a = await rt.approvals.get(admin, id); const t = await rt.approvals.get(F.T.tina, id);
    const ok = s.status === 200 && s.json.current != null && s.json.blockedReason === 'You requested this change' && s.json.canWithdraw === true
      && a.status === 200 && a.json.current != null && a.json.blockedReason == null && a.json.canApprove === true
      && t.status === 200 && t.json.current != null && /approval right in South/.test(t.json.blockedReason || '');
    return { ok, actual: { sam: brief(s), admin: brief(a), tina: brief(t) } };
  });

  await C({ id: 'REG-144', feature: 'POC book per region x pending change', prd: 'B2',
    title: 'The book-less approver approves the out-of-book change; it takes effect, and the decided change still says she has no access',
    steps: 'change of REG-142b; abby POST /api/approvals/{pc}/approve; admin GET /api/invoices/{id}; abby GET /api/approvals/{pc}',
    expected: 'approve 200 (SC-15); invoice dueDate = +60d; decided change: status APPROVED, current null, blockedReason "You no longer have access to this record" (any status), canApprove false' }, async (n) => {
    let h = F.r4.a;
    if (n > 1 || !h?.r?.held) h = await heldTerms(`c${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const ap = await rt.approvals.approve(abby.token, id, 'ok from abby');
    const inv = await get(admin, `/api/invoices/${h.inv.id}`);
    const d = await rt.approvals.get(abby.token, id);
    const ok = ap.status === 200 && inv.json?.dueDate === rt.day(60) && d.status === 200 && d.json.status === 'APPROVED'
      && d.json.current == null && d.json.blockedReason === NO_ACCESS && d.json.canApprove === false;
    return { ok, actual: { approve: excerpt(ap, 150), dueDate: inv.json?.dueDate, after: brief(d) } };
  });

  await C({ id: 'REG-145', feature: 'POC book per region x pending change', prd: 'B2',
    title: 'A held invoice CREATE on an out-of-book customer: the book-less approver is told she has no access; the maker is not',
    steps: 'new South customer (POC sam); sam POST /api/invoices amount 12000 (> South INVOICE_CREATE 10000) -> 202; abby GET /api/customers/{id}, GET /api/approvals/{pc}; sam GET /api/approvals/{pc}',
    expected: 'customer 404 for abby; abby: 200, current null, blockedReason "You no longer have access to this record" (held create judged by customer visibility, TRIAGE r3 B1); sam: "You requested this change"' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [], prefix: `r4-d${n}` });
    const r = await rt.createInvoice(T.sam, { customerId: c.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: F.U.sam.id });
    if (!r.held) return { ok: false, actual: `setup: create not held: ${excerpt(r)}` };
    F.r4.create = { c, r };
    const id = r.pendingChange.id;
    const cu = await get(abby.token, `/api/customers/${c.id}`);
    const d = await rt.approvals.get(abby.token, id); const s = await rt.approvals.get(T.sam, id);
    const ok = cu.status === 404 && d.status === 200 && d.json.current == null && d.json.blockedReason === NO_ACCESS
      && s.status === 200 && s.json.blockedReason === 'You requested this change';
    return { ok, actual: { customer: cu.status, abby: brief(d), sam: brief(s) } };
  });

  await C({ id: 'REG-146', feature: 'Region move x pending change', prd: 'B1',
    title: 'After the customer is moved out of South, a South-only approver reading the closed change sees no present values and is told she has no access',
    steps: 'sally = APPROVE/ALL South only; new South customer, invoice 2500 (POC sam); sam PATCH {dueDate:+60d} -> 202; sally GET /api/approvals/{pc} (pending, sanity); admin moves the customer South -> North (closes the change); sally GET /api/invoices/{id}; sally GET /api/approvals/{pc}',
    expected: 'before: current filled, blockedReason null. After the move: invoice 404 for sally (it is in North); the change is still visible (asked in South, she approves there) but, per DESIGN §5.11.9, Detail.current and changes[].current are null because she can no longer see the record, and blockedReason reads "You no longer have access to this record"',
    severity: 'medium', codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:129 (canSeeRecord passes pc.getRegionId(), the region the change was asked in, to ScopeResolver.requireVisible, so tier() is judged on South while the record now lives in North)' }, async (n) => {
    const h = await heldTerms(`e${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const before = await rt.approvals.get(sally.token, id);
    const mv = await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r4 move with a change waiting');
    const inv = await get(sally.token, `/api/invoices/${h.inv.id}`);
    const cu = await get(sally.token, `/api/customers/${h.c.id}`);
    const d = await rt.approvals.get(sally.token, id);
    const leaks = (d.json?.changes || []).filter((x) => x.current != null || x.currentText != null).map((x) => [x.field, x.currentText]);
    const ok = before.status === 200 && before.json.current != null && mv.status === 200 && inv.status === 404 && cu.status === 404
      && d.status === 200 && d.json.current == null && leaks.length === 0 && d.json.blockedReason === NO_ACCESS;
    return { ok, actual: { before: brief(before), move: mv.status, invoice: inv.status, customer: cu.status, after: brief(d), afterRegion: d.json?.regionName, leaks },
      evidence: `pc ${id}, invoice ${h.inv.id}, customer ${h.c.id}; sally GET /api/approvals/${id} after the move -> ${excerpt(d, 700)}` };
  });

  await C({ id: 'REG-147', feature: 'Customer login x pending change', prd: 'B2',
    title: 'A customer login reading a held change on its own invoice is not told it has no access',
    steps: 'sam PATCH s1\'s 2500 invoice {dueDate:+60d, paymentTerm:CUSTOM} -> 202; cust (s1 login) GET /api/approvals/{pc}',
    expected: 'either 404 (customers do not see the change) or 200 with blockedReason null and canApprove false; never "You no longer have access to this record" for its own invoice' }, async (n) => {
    const inv = w.invoices.s1[n === 1 ? 1 : 0];
    const r = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(61 + n), paymentTerm: 'CUSTOM' });
    if (!r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(r)}` };
    const ct = await rt.login(w.customers.s1.username);
    const d = await rt.approvals.get(ct, r.pendingChange.id);
    const ok = d.status === 404 || (d.status === 200 && d.json.blockedReason == null && d.json.canApprove === false);
    return { ok, actual: brief(d), evidence: excerpt(d, 300) };
  });

  await C({ id: 'REG-U21', kind: 'UI', feature: 'POC book per region x pending change', prd: 'B1',
    title: 'Approval detail screen shows the book-less approver "You no longer have access to this record" and still offers Approve',
    steps: 'held invoice create of REG-145; abby opens /approvals/{pc}',
    expected: 'the text "You no longer have access to this record" is on screen; the Approve button is present (DESIGN §5.11.9, §7 approval detail)',
    severity: 'low', codeRef: 'frontend/lib/features/approvals/approval_detail_screen.dart:173' }, async (n) => {
    let id = F.r4.create?.r?.pendingChange?.id;
    if (n > 1 || !id) {
      const c = await mkCustomer(F, 'S', { amounts: [], prefix: `r4-u${n}` });
      const r = await rt.createInvoice(T.sam, { customerId: c.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: F.U.sam.id });
      id = r.pendingChange?.id;
    }
    const app = await rt.openApp({ token: abby.token });
    try {
      await rt.go(app.page, `/approvals/${id}`, 5000);
      const txt = await rt.screenText(app.page);
      const s = await shot(app.page, `REG-U21-${n}`);
      const msg = txt.some((t) => t.includes(NO_ACCESS));
      const approve = await rt.exists(app.page, /^Approve$/);
      return { ok: msg && approve, actual: { message: msg, approveButton: approve, text: txt.slice(0, 40) }, screenshot: s };
    } finally { await app.close(); }
  });
}

// Runs after the world has been aged (asOfCases): the historical reason wins over the access one.
async function ext4AfterAge(F, RUN) {
  const { C } = RUN;
  const h = F.r4?.create;
  await C({ id: 'REG-148', feature: 'As-of x pending change', prd: 'B3',
    title: 'As of a past date the book-less approver gets "Read-only in the as-of view" (precedence over the access reason) and cannot decide',
    steps: 'held invoice create of REG-145 (still pending); ageWorld(4) (asOfCases); abby GET /api/approvals/{pc}?asOf=<2 days ago>; live GET',
    expected: 'asOf: 200, statusAt PENDING, blockedReason "Read-only in the as-of view", canApprove false, current null; live: blockedReason "You no longer have access to this record" (TRIAGE r3 B1 precedence)' }, async () => {
    if (!h?.r?.held) return { status: 'BLOCKED', actual: 'no held create from REG-145' };
    const tAbby = await rt.login('abby');
    const id = h.r.pendingChange.id;
    const a = await rt.api('GET', `/api/approvals/${id}`, { token: tAbby, query: { asOf: rt.day(-2) } });
    const l = await rt.approvals.get(tAbby, id);
    const ok = a.status === 200 && a.json.statusAt === 'PENDING' && a.json.blockedReason === 'Read-only in the as-of view' && a.json.canApprove === false
      && a.json.current == null && l.status === 200 && l.json.blockedReason === NO_ACCESS;
    return { ok, actual: { asOf: { ...brief(a), statusAt: a.json?.statusAt }, live: brief(l) }, evidence: excerpt(a, 300) };
  });
}

module.exports = { ext4Cases, ext4AfterAge };
