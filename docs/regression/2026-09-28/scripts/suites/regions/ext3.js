// Round-3 extensions of the regions suite (B1 x B2): a pending change on a record in a region seen at BOOK scope.
// Neighbour of APP-R2-1 (the change table now resolves names of ids): the record's present values must not
// reach someone who can see the change but not the record.
const rt = require('../../lib.js');
const { excerpt } = require('./h.js');

const get = (t, p, query) => rt.api('GET', p, { token: t, query });

async function ext3Cases(F, RUN, { mkCustomer }) {
  const { C } = RUN;
  const { admin, R, T } = F;

  // South INVOICE_TERMS 1000 (a tightening: 200). Keep every limit already set so nothing is loosened.
  const cur = await rt.getThresholds(admin, R.S);
  const rows = (cur.json?.thresholds || cur.json || []).filter((r) => r.amount != null && r.operation !== 'INVOICE_TERMS')
    .map((r) => ({ operation: r.operation, amount: Number(r.amount), windowHours: r.windowHours }));
  rows.push({ operation: 'INVOICE_TERMS', amount: 1000, windowHours: 24 });
  const th = await rt.setThresholds(admin, R.S, rows);
  rt.need(th, 'South INVOICE_TERMS 1000', [200]);

  // abby: APPROVE/BOOK South with an empty book.
  let abby = await rt.findUser(admin, 'abby');
  const grants = [{ regionId: R.S, accessLevel: 'APPROVE', recordScope: 'BOOK' }];
  if (!abby) abby = await rt.createStaff(admin, 'E2E_REGIONAL', 'abby', { username: 'abby', fullName: 'Abby Bookapprover', grants });
  else rt.need(await rt.setGrants(admin, abby.id, grants), 'abby grants');
  const tAbby = await rt.login('abby');

  const held = {};
  async function heldTerms(attempt) {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r3-${attempt}` });
    const inv = c.invoices[0];
    const r = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    return { c, inv, r };
  }

  await C({ id: 'REG-140', feature: 'POC book per region x pending change', prd: 'B1',
    title: 'An APPROVE/BOOK South approver sees a held terms change on an out-of-book invoice, but not the invoice\'s present values',
    steps: 'South INVOICE_TERMS 1000; abby = APPROVE/BOOK South (empty book); new South customer, invoice 2500 (Sales+Collection POC sam); sam PATCH /api/invoices/{id} {dueDate:+60d, paymentTerm:CUSTOM} -> 202; abby GET /api/invoices/{id}; abby GET /api/approvals/{pc}; abby GET /api/approvals?regionIds=S',
    expected: 'invoice 404 for abby (BOOK, not in her book, DESIGN §5.6.4); the change 200 and listed (region_id IN regionsWith(CHANGE_APPROVE), §5.11.9); Detail.current null and every changes[].current / currentText null (§5.11.9 "Detail.current and changes[].current are filled only when the caller can still see the record")',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:126' }, async (attempt) => {
    const h = await heldTerms(attempt);
    Object.assign(held, h);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const inv = await get(tAbby, `/api/invoices/${h.inv.id}`);
    const d = await rt.approvals.get(tAbby, id);
    const l = await rt.approvals.list(tAbby, {}, { regionIds: [R.S] });
    const listed = (l.json?.content || []).some((x) => x.id === id);
    const leaks = (d.json?.changes || []).filter((x) => x.current != null || x.currentText != null);
    const ok = inv.status === 404 && d.status === 200 && d.json.current == null && leaks.length === 0 && listed;
    return { ok, actual: { invoice: inv.status, detail: d.status, current: d.json?.current ?? null, leaks, listed,
      changes: (d.json?.changes || []).map((x) => [x.field, x.currentText, x.proposedText]) },
      evidence: `pc ${id}; GET /api/approvals/${id} as abby -> ${excerpt(d, 500)}` };
  });

  await C({ id: 'REG-141', feature: 'POC book per region x pending change', prd: 'B1',
    title: 'The same change read by nora (READ/ALL South) shows the present values; wendy (West only) gets 404',
    steps: 'held change of REG-140; nora GET /api/approvals/{pc}; wendy GET /api/approvals/{pc}; nora POST approve',
    expected: 'nora 200 with current filled and the dueDate row\'s current = the invoice\'s due date, canApprove false with blockedReason "You don\'t hold the approval right in SOUTH..."; nora approve 403; wendy 404 (PRD B1 404 when invisible, 403 when visible but not allowed)' }, async (attempt) => {
    let h = held;
    if (attempt > 1 || !h.r?.held) h = await heldTerms(`b${attempt}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const n = await rt.approvals.get(T.nora, id); const wd = await rt.approvals.get(T.wendy, id);
    const due = (n.json?.changes || []).find((x) => x.field === 'dueDate');
    const ap = await rt.approvals.approve(T.nora, id, 'no');
    const ok = n.status === 200 && n.json.current != null && due && due.current === h.inv.dueDate && n.json.canApprove === false
      && /approval right/.test(n.json.blockedReason || '') && ap.status === 403 && wd.status === 404;
    return { ok, actual: { nora: n.status, current: !!n.json?.current, dueRow: due, canApprove: n.json?.canApprove, blocked: n.json?.blockedReason,
      approve: excerpt(ap, 150), wendy: wd.status } };
  });

  await C({ id: 'REG-142', feature: 'POC book per region x pending change', prd: 'B1',
    title: 'An approver who cannot open the record is told so on the change (blockedReason), per DESIGN §5.11.9 / SC-15',
    steps: 'held change of REG-140; abby GET /api/approvals/{pc}',
    expected: 'DESIGN §5.11.9: "otherwise they [current] are null and blockedReason reads \'You no longer have access to this record\'"',
    severity: 'low', codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:170' }, async (attempt) => {
    let h = held;
    if (attempt > 1 || !h.r?.held) h = await heldTerms(`c${attempt}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const d = await rt.approvals.get(tAbby, h.r.pendingChange.id);
    const ok = d.status === 200 && d.json.current == null && /no longer have access/i.test(d.json.blockedReason || '');
    return { ok, actual: { status: d.status, current: d.json?.current ?? null, blockedReason: d.json?.blockedReason ?? null,
      canApprove: d.json?.canApprove, canReject: d.json?.canReject }, evidence: excerpt(d, 400) };
  });
}

module.exports = { ext3Cases };
