// Round-5 extensions of the regions suite: one step around the RGN-R4-1 fix (PendingChangeScope.canSeeRecord /
// isVisible now judge the record by its OWN region, via recordRegion over the live table or its versions).
// Looks at: plain viewers of the old region, viewers of the new region, the other record kinds (PAYMENT create,
// PROMISE update, DISPUTE approve), lists vs detail, the UI, and as-of reads after a move.
const rt = require('../../lib.js');
const { excerpt } = require('./h.js');

const get = (t, p, query) => rt.api('GET', p, { token: t, query });
const NO_ACCESS = 'You no longer have access to this record';
const AS_OF_RO = 'Read-only in the as-of view';

async function staff(admin, username, grants, fullName) {
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `${username} grants`);
  return { user: u, token: await rt.login(username) };
}

const brief = (d) => ({ status: d.status, pcStatus: d.json?.status, current: d.json?.current == null ? null : 'filled',
  changesCurrent: (d.json?.changes || []).filter((x) => x.current != null || x.currentText != null).length,
  blockedReason: d.json?.blockedReason ?? null, canApprove: d.json?.canApprove, statusAt: d.json?.statusAt ?? undefined });
const noNow = (d) => d.status === 200 && d.json.current == null
  && (d.json.changes || []).every((x) => x.current == null && x.currentText == null);

async function inList(token, pcId, scope = {}) {
  const r = await rt.approvals.list(token, { filter: `id:eq:${pcId}` }, scope);
  return r.status === 200 ? (r.json.content || []).some((x) => x.id === pcId) : `status ${r.status}`;
}

async function ext5Cases(F, RUN, { mkCustomer }) {
  const { C, shotsDir } = RUN;
  const { admin, R, T } = F;
  const shot = (page, name) => rt.shot(page, shotsDir, name);

  // South: make sure INVOICE_TERMS 1000, and add PROMISE_UPDATE / DISPUTE_APPROVE 1000 (tightening -> 200).
  const cur = await rt.getThresholds(admin, R.S);
  const rows = (cur.json?.thresholds || cur.json || []).filter((r) => r.amount != null)
    .map((r) => ({ operation: r.operation, amount: Number(r.amount), windowHours: r.windowHours }));
  const setRow = (op, amt) => { const i = rows.findIndex((r) => r.operation === op); if (i >= 0) rows[i].amount = Math.min(rows[i].amount, amt); else rows.push({ operation: op, amount: amt, windowHours: 24 }); };
  setRow('INVOICE_TERMS', 1000); setRow('PROMISE_UPDATE', 1000); setRow('DISPUTE_APPROVE', 1000);
  const th = await rt.setThresholds(admin, R.S, rows);
  if (th.status !== 200) console.log(`ext5: South thresholds -> ${excerpt(th, 300)}`);

  const sally = await staff(admin, 'sally', [{ regionId: R.S, accessLevel: 'APPROVE', recordScope: 'ALL' }], 'Sally Southonly');
  const tina = { token: await rt.login('tina') }; // READ/ALL South, no approval right
  const nico = await staff(admin, 'nico', [{ regionId: R.N, accessLevel: 'READ', recordScope: 'ALL' }], 'Nico Northreader');
  const mack = await staff(admin, 'mack', [{ regionId: R.S, accessLevel: 'MANAGE', recordScope: 'ALL' }], 'Mack Southmanager');
  F.r5 = { sally, tina, nico, mack };

  async function heldTerms(tag) {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r5-${tag}` });
    const inv = c.invoices[0];
    const r = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    return { c, inv, r };
  }

  // ---- RGN-R4-1 retest is REG-146 (ext4). Here: the other audiences on the same kind of change. ----
  await C({ id: 'REG-150', feature: 'Region move x pending change', prd: 'B1',
    title: 'After a move out of South, a South READ-only viewer (no approval right) no longer sees the closed change (404); before the move she saw it with present values; admin still sees present values',
    steps: 'new South customer, invoice 2500 (POC sam); sam PATCH {dueDate:+60d, paymentTerm:CUSTOM} -> 202; tina (READ/ALL South) GET /api/approvals/{pc}; admin moves the customer to North; tina GET /api/approvals/{pc} and list ?filter=id:eq:{pc}; admin GET /api/approvals/{pc}',
    expected: 'before: tina 200, current filled (B2 "visible to everyone who can see the record"). After: tina 404 and not listed (TRIAGE r4 user decision: a plain viewer of the old region no longer sees it); admin (holds North) 200 with current filled, blockedReason null',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:102-136' }, async (n) => {
    const h = await heldTerms(`a${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const tb = await rt.approvals.get(tina.token, id);
    const mv = await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r5 move');
    const ta = await rt.approvals.get(tina.token, id);
    const tl = await inList(tina.token, id);
    const aa = await rt.approvals.get(admin, id);
    const ok = tb.status === 200 && tb.json.current != null && mv.status === 200 && ta.status === 404 && tl === false
      && aa.status === 200 && aa.json.status === 'CLOSED' && aa.json.current != null && aa.json.blockedReason == null;
    return { ok, actual: { tinaBefore: brief(tb), move: mv.status, tinaAfter: ta.status, tinaListed: tl, admin: brief(aa) },
      evidence: `pc ${id}, invoice ${h.inv.id}, customer ${h.c.id}; tina after: ${excerpt(ta, 120)}; admin after: ${excerpt(aa, 250)}` };
  });

  await C({ id: 'REG-151', feature: 'Region move x pending change', prd: 'B1',
    title: 'Lists agree with detail after the move: sally (APPROVE South) still lists the closed change; its row carries no present values',
    steps: 'as REG-150; sally GET /api/approvals?filter=id:eq:{pc}; sally GET /api/approvals?regionIds=North&filter=id:eq:{pc}; sally GET /api/approvals/{pc}',
    expected: 'listed in her own regions (it was asked in South, approval is a South right, SC-15); an out-of-grant regionIds filter is narrowed to nothing (no leak); detail 200 CLOSED with current null and "You no longer have access to this record"' }, async (n) => {
    const h = await heldTerms(`b${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    rt.need(await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r5 move b'), 'move');
    const l = await inList(sally.token, id);
    const lN = await rt.approvals.list(sally.token, { filter: `id:eq:${id}` }, { regionIds: [R.N] });
    const d = await rt.approvals.get(sally.token, id);
    const nNorth = lN.status === 200 ? (lN.json.content || []).length : lN.status;
    const ok = l === true && (nNorth === 0 || lN.status === 400 || lN.status === 403) && noNow(d) && d.json.status === 'CLOSED' && d.json.blockedReason === NO_ACCESS;
    return { ok, actual: { listed: l, northFilter: [lN.status, nNorth], detail: brief(d) } };
  });

  await C({ id: 'REG-152', feature: 'Region move x pending change', prd: 'B1',
    title: 'A held PAYMENT create (no record yet) left behind by a move: the South approver gets no present values and the access reason; a South viewer gets 404; admin sees it',
    steps: 'new South customer, invoice 9000; sam POST /api/payments 6000 (> South PAYMENT_RECORD 5000) -> 202; tina GET (before); admin moves customer to North; sally, tina, admin GET /api/approvals/{pc}',
    expected: 'before: tina 200. After: sally 200 CLOSED, current null, blockedReason "You no longer have access to this record"; tina 404; admin 200, blockedReason null (create judged by the customer\'s own region, PendingChangeScope.isVisible last line / canSeeRecord)' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [9000], prefix: `r5-p${n}` });
    const r = await rt.createPayment(T.sam, { customerId: c.id, amount: 6000, invoiceIds: [c.invoices[0].id], collectionPocUserId: F.U.sam.id });
    if (!r.held) return { ok: false, actual: `setup: payment not held: ${excerpt(r)}` };
    const id = r.pendingChange.id;
    const tb = await rt.approvals.get(tina.token, id);
    const mv = await rt.moveCustomerRegion(admin, c.id, R.N, 'r5 move p');
    const s = await rt.approvals.get(sally.token, id); const t = await rt.approvals.get(tina.token, id); const a = await rt.approvals.get(admin, id);
    const ok = tb.status === 200 && mv.status === 200 && noNow(s) && s.json.status === 'CLOSED' && s.json.blockedReason === NO_ACCESS
      && t.status === 404 && a.status === 200 && a.json.blockedReason == null;
    return { ok, actual: { tinaBefore: tb.status, move: mv.status, sally: brief(s), tina: t.status, admin: brief(a) }, evidence: `pc ${id}, customer ${c.id}; ${excerpt(s, 250)}` };
  });

  await C({ id: 'REG-153', feature: 'Region move x pending change', prd: 'B1',
    title: 'A held PROMISE update left behind by a move: record region is read from the promise; sally gets the access reason, tina 404, admin present values',
    steps: 'new South customer; admin promise 3000 (POC sam); mack (MANAGE South) PUT /api/promises/{id} amount 4500 (delta 1500 > PROMISE_UPDATE 1000) -> 202; before: sally current filled; admin moves customer to North; sally, tina, admin GET /api/approvals/{pc}',
    expected: 'before: sally 200 with current, blockedReason null. After: sally 200, current null, "You no longer have access to this record"; tina 404; admin 200 with current filled. No 500 (PendingChangeScope.recordRegion over PaymentPromise)' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [4000], prefix: `r5-pr${n}` });
    const p = rt.need(await rt.createPromise(admin, { customerId: c.id, amount: 3000, collectionPocUserId: F.U.sam.id }), 'promise', [200]);
    const pd = (await get(admin, `/api/promises/${p.id}`)).json || p;
    const r = rt.heldAware(await rt.api('PUT', `/api/promises/${p.id}`, { token: mack.token,
      body: { amount: 4500, promisedDate: pd.promisedDate, collectionPocUserId: pd.collectionPocUserId ?? F.U.sam.id, notes: pd.notes ?? null, invoiceIds: null } }));
    if (!r.held) return { ok: false, actual: `setup: promise update not held: ${excerpt(r)}` };
    const id = r.pendingChange.id;
    const sb = await rt.approvals.get(sally.token, id);
    const mv = await rt.moveCustomerRegion(admin, c.id, R.N, 'r5 move pr');
    const s = await rt.approvals.get(sally.token, id); const t = await rt.approvals.get(tina.token, id); const a = await rt.approvals.get(admin, id);
    const ok = sb.status === 200 && sb.json.current != null && sb.json.blockedReason == null && mv.status === 200
      && noNow(s) && s.json.blockedReason === NO_ACCESS && t.status === 404 && a.status === 200 && a.json.current != null;
    return { ok, actual: { sallyBefore: brief(sb), move: mv.status, sally: brief(s), tina: t.status, admin: brief(a) }, evidence: `pc ${id}, promise ${p.id}, customer ${c.id}` };
  });

  await C({ id: 'REG-154', feature: 'Region move x pending change', prd: 'B1',
    title: 'A held DISPUTE approval left behind by a move: record region is read from the dispute; sally gets the access reason, tina 404, admin present values',
    steps: 'new South customer with login, invoice 2500; the customer opens a dispute {action:cancel}; mack POST /api/disputes/{id}/approve -> 202 DISPUTE_APPROVE; before: sally current filled; admin moves customer to North; sally, tina, admin GET /api/approvals/{pc}',
    expected: 'before: sally 200 with current. After: sally 200, current null, "You no longer have access to this record"; tina 404; admin 200 with current (recordRegion over Dispute)' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r5-d${n}`, login: true });
    const d = rt.need(await rt.createDispute(c.token, { targetType: 'INVOICE', targetId: c.invoices[0].id, reason: 'r5', proposedChangeJson: JSON.stringify({ action: 'cancel' }) }), 'dispute');
    const r = rt.heldAware(await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: mack.token, body: {} }));
    if (!r.held) return { ok: false, actual: `setup: dispute approve not held: ${excerpt(r)}` };
    const id = r.pendingChange.id;
    const sb = await rt.approvals.get(sally.token, id);
    const mv = await rt.moveCustomerRegion(admin, c.id, R.N, 'r5 move d');
    const s = await rt.approvals.get(sally.token, id); const t = await rt.approvals.get(tina.token, id); const a = await rt.approvals.get(admin, id);
    const ok = sb.status === 200 && sb.json.current != null && mv.status === 200
      && noNow(s) && s.json.blockedReason === NO_ACCESS && t.status === 404 && a.status === 200 && a.json.current != null;
    return { ok, actual: { sallyBefore: brief(sb), move: mv.status, sally: brief(s), tina: t.status, admin: brief(a) }, evidence: `pc ${id}, dispute ${d.id}, customer ${c.id}` };
  });

  await C({ id: 'REG-155', feature: 'Region move x pending change', prd: 'B2',
    title: 'A North-only reader who can now open the moved invoice can also open the closed change about it (visible to whoever can see the record)',
    steps: 'as REG-150; nico (READ/ALL North only) GET /api/invoices/{id} and GET /api/approvals/{pc}; list ?filter=id:eq:{pc}',
    expected: 'invoice 200; change 200 with current filled and listed — DESIGN §5.11.9 VISIBLE: "(entity_id IS NOT NULL AND entity_type=\'INVOICE\' AND entity_id IN ids(Invoice, forInvoices()))" has no gate on the change\'s region; TRIAGE r4 B1 test plan: "The admin, who holds North: 200 with current filled. So does a North-only viewer"',
    severity: 'low', codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:113 (isVisible: `if (!rights.has(pc.getOperation().viewPrivilege(), region)) return false;` with region = pc.getRegionId(), the region the change was asked in) and :225 (forList: `region.in(kindRegions.get(i))`)' }, async (n) => {
    const h = await heldTerms(`n${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    rt.need(await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r5 move n'), 'move');
    const inv = await get(nico.token, `/api/invoices/${h.inv.id}`);
    const d = await rt.approvals.get(nico.token, id);
    const l = await inList(nico.token, id);
    const ok = inv.status === 200 && d.status === 200 && d.json.current != null && l === true;
    return { ok, actual: { invoice: inv.status, change: brief(d), listed: l }, evidence: `pc ${id}, invoice ${h.inv.id}, customer ${h.c.id}; nico GET /api/approvals/${id} -> ${excerpt(d, 150)}` };
  });

  await C({ id: 'REG-U22', kind: 'UI', feature: 'Region move x pending change', prd: 'B1',
    title: 'Approval detail screen: sally reading the change left behind by a move sees "You no longer have access to this record" and no Approve button',
    steps: 'as REG-146 (held terms change, customer moved to North); sally opens /approvals/{pc}',
    expected: 'the message is on screen; no Approve/Reject (CLOSED); no present due date shown', severity: 'low' }, async (n) => {
    const h = await heldTerms(`u${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    rt.need(await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r5 move u'), 'move');
    const app = await rt.openApp({ token: sally.token });
    try {
      await rt.go(app.page, `/approvals/${id}`, 5000);
      const txt = await rt.screenText(app.page);
      const s = await shot(app.page, `REG-U22-${n}`);
      const msg = txt.some((t) => t.includes(NO_ACCESS));
      const approve = await rt.exists(app.page, /^Approve$/);
      return { ok: msg && !approve, actual: { message: msg, approveButton: approve, text: txt.slice(0, 50) }, screenshot: s };
    } finally { await app.close(); }
  });

  // For the as-of case after ageWorld: a held terms change that stays pending until the world is aged.
  const pre = await heldTerms('asof');
  F.r5.pre = pre.r.held ? pre : null;
}

// Runs after asOfCases has aged the world by 4 days: the move happens now; as of 2 days ago the record was in South.
async function ext5AfterAge(F, RUN) {
  const { C } = RUN;
  const { admin, R } = F;
  const h = F.r5?.pre;
  await C({ id: 'REG-156', feature: 'As-of x region move x pending change', prd: 'B3',
    title: 'As of before the move, the record\'s region THEN decides: tina (South reader) sees the outstanding change, nico (North reader) does not; live it is the reverse for tina',
    steps: 'held terms change on a South invoice (pending); ageWorld(4); admin moves the customer to North now; GET /api/approvals/{pc}?asOf=<2 days ago> as tina, nico, sally; live as tina and sally',
    expected: 'asOf: tina 200 statusAt PENDING (the record was in South then, TRIAGE r4 user decision "As of a date, the record\'s region at that date decides"); nico 404; sally 200 "Read-only in the as-of view", canApprove false. Live: tina 404; sally 200 CLOSED, current null, "You no longer have access to this record"' }, async () => {
    if (!h) return { status: 'BLOCKED', actual: 'no held change from ext5 setup' };
    const id = h.r.pendingChange.id;
    const mv = await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r5 as-of move');
    const tS = await rt.login('sally'); const tT = await rt.login('tina'); const tN = await rt.login('nico');
    const past = rt.day(-2);
    const q = (t) => rt.api('GET', `/api/approvals/${id}`, { token: t, query: { asOf: past } });
    const aT = await q(tT); const aN = await q(tN); const aS = await q(tS);
    const lT = await rt.approvals.get(tT, id); const lS = await rt.approvals.get(tS, id);
    const ok = mv.status === 200 && aT.status === 200 && aT.json.statusAt === 'PENDING' && aN.status === 404
      && aS.status === 200 && aS.json.blockedReason === AS_OF_RO && aS.json.canApprove === false
      && lT.status === 404 && noNow(lS) && lS.json.status === 'CLOSED' && lS.json.blockedReason === NO_ACCESS;
    return { ok, actual: { move: mv.status, asOf: { tina: brief(aT), nico: aN.status, sally: brief(aS) }, live: { tina: lT.status, sally: brief(lS) } },
      evidence: `pc ${id}, customer ${h.c.id}; tina asOf -> ${excerpt(aT, 200)}` };
  });
}

module.exports = { ext5Cases, ext5AfterAge };
