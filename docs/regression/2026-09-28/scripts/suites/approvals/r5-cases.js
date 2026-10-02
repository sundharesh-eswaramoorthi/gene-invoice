// Round 5: RGN-R4-1 (PendingChangeScope.recordRegion: an approval's record visibility follows the record's own region,
// live and as of a date) and one step around it: every record kind (INVOICE, PROMISE, DISPUTE, a create judged by the
// customer), a move back, lists vs by-id agreement, and the as-of read of a moved record.
// `live` runs before the UI cases; `prepAsOf` before r4.asOf (which ages the world); `asOf` after it.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, invoiceBy, promiseBy, brief, pick, openAs, waitText, allText } = H;
const SCOPE = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java';
const VIEWS = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java';
const DETAIL = 'frontend/lib/features/approvals/approval_detail_screen.dart';
const NO_ACCESS = 'You no longer have access to this record';
const RO = 'Read-only in the as-of view';
const flags = (j) => pick(j, ['status', 'statusAt', 'canApprove', 'canReject', 'canWithdraw', 'blockedReason']);
const nowVals = (j) => ({ current: j?.current != null, rowsCurrent: (j?.changes || []).filter((x) => x.current != null || x.currentText != null).map((x) => x.field) });
const noNow = (j) => j && j.current == null && (j.changes || []).every((x) => x.current == null && x.currentText == null);

async function staff(w, username, grants) {
  const admin = w.tokens.admin;
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName: `R5 ${username}`, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
  w.users[username] = { ...u, grants };
  w.tokens[username] = await rt.login(username);
  return u;
}

async function cast(w) {
  if (w.r5cast) return;
  const S = w.regions.SOUTH.id; const N = w.regions.NORTH.id;
  await staff(w, 'sal', [{ regionId: S, accessLevel: 'APPROVE', recordScope: 'ALL' }]); // South approver only
  await staff(w, 'vic', [{ regionId: S, accessLevel: 'READ', recordScope: 'ALL' }]); // South viewer only
  await staff(w, 'nico', [{ regionId: N, accessLevel: 'READ', recordScope: 'ALL' }]); // North viewer only
  w.r5cast = true;
}

/**
 * A fresh South customer (POC sam) with four held changes by mia:
 *   T INVOICE_TERMS on a 3000 invoice (NET_90), P PROMISE_UPDATE 3000 -> 4500, C PAYMENT_RECORD create 7000,
 *   D DISPUTE_APPROVE (the customer login raises a cancel dispute on a second 2000 invoice; mia approves it).
 */
async function heldSet(w, prefix) {
  const T = w.tokens; const U = w.users;
  const cu = await customerIn(w, 'SOUTH', prefix);
  const inv = await invoiceBy(T.admin, cu, 3000);
  const inv2 = await invoiceBy(T.admin, cu, 2000);
  const pr = await promiseBy(T.admin, cu, 3000);
  const hT = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
  const hP = rt.heldAware(await rt.api('PUT', `/api/promises/${pr.id}`, { token: T.mia, body: { amount: 4500, promisedDate: pr.promisedDate, collectionPocUserId: U.sam.id, notes: null, invoiceIds: [] } }));
  const hC = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id });
  // Disputes are raised by the customer login (staff get 403): every customer created by the harness has one.
  const custTok = await rt.login(cu.username, cu.password);
  const dsp = await rt.createDispute(custTok, { targetType: 'INVOICE', targetId: inv2.id, reason: 'r5 dispute', proposedChangeJson: JSON.stringify({ action: 'cancel' }) });
  const hD = dsp.ok ? rt.heldAware(await rt.api('POST', `/api/disputes/${dsp.json.id}/approve`, { token: T.mia, body: {} })) : { held: false, status: dsp.status, text: dsp.text };
  const ids = { T: hT.pendingChange?.id, P: hP.pendingChange?.id, C: hC.pendingChange?.id, D: hD.pendingChange?.id };
  const fixture = `customer ${cu.id} inv ${inv.id} inv2 ${inv2.id} promise ${pr.id} dispute ${dsp.json?.id}(${dsp.status}); held T:${hT.status}/${ids.T} P:${hP.status}/${ids.P} C:${hC.status}/${ids.C} D:${hD.status}/${ids.D}`;
  return { cu, inv, inv2, pr, dispute: dsp.json, h: { T: hT, P: hP, C: hC, D: hD }, ids, fixture };
}

async function live(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await cast(w);
  const NORTH = w.regions.NORTH.id; const SOUTH = w.regions.SOUTH.id;

  await tcase({ id: 'APP-R5-01', feature: 'Region move: approval follows the record (RGN-R4-1)', kind: 'API', prd: 'B2',
    title: 'After the customer moves South -> North, a South-only approver still opens the (now CLOSED) terms change but sees no live values and "You no longer have access to this record"; a South-only viewer gets 404; admin still sees the values',
    steps: 'sal = APPROVE/ALL South only; vic = READ/ALL South only; nico = READ/ALL North only; fresh South customer, admin invoice 3000; mia PATCH paymentTerm NET_90 (held INVOICE_TERMS); GET /api/approvals/{pc} as sal, vic (before); admin POST /api/customers/{id}/move-region North; GET invoice + approval as sal, vic, admin, nico',
    expected: 'TRIAGE r4 userDecision + FIXLOG r4 B1: before sal 200 current filled, blockedReason null, canApprove; vic 200 current filled. Move 200, pc CLOSED. After: sal GET invoice 404; sal approval 200 CLOSED, current null, every changes[].current/currentText null, blockedReason "You no longer have access to this record"; vic 404; admin 200 with current' }, async () => {
    const c = checks();
    const s = await heldSet(w, 'r5mv');
    c.that('terms change held', s.h.T.held, brief(s.h.T));
    const id = s.ids.T;
    const sb = await rt.approvals.get(T.sal, id);
    c.that('before: sal 200, current filled, blockedReason null, canApprove', sb.status === 200 && sb.json?.current != null && sb.json?.blockedReason == null && sb.json?.canApprove === true, `${sb.status} ${JSON.stringify(flags(sb.json))} ${JSON.stringify(nowVals(sb.json))}`);
    c.that('before: sal changes[].current filled', (sb.json?.changes || []).some((x) => x.field === 'paymentTerm' && (x.current != null || x.currentText != null)), sb.json?.changes);
    const vb = await rt.approvals.get(T.vic, id);
    c.that('before: vic 200 with current', vb.status === 200 && vb.json?.current != null, `${vb.status} ${JSON.stringify(nowVals(vb.json))}`);
    const mv = await rt.moveCustomerRegion(T.admin, s.cu.id, NORTH, 'r5 move');
    c.that('move 200', mv.status === 200, brief(mv).slice(0, 300));
    const si = await rt.api('GET', `/api/invoices/${s.inv.id}`, { token: T.sal });
    c.that('after: sal GET invoice 404', si.status === 404, si.status);
    const sa = await rt.approvals.get(T.sal, id);
    c.that('after: sal approval 200 CLOSED', sa.status === 200 && sa.json?.status === 'CLOSED', `${sa.status} ${JSON.stringify(flags(sa.json))}`);
    c.that('after: sal sees no live values', noNow(sa.json), nowVals(sa.json));
    c.that('after: sal blockedReason no-access', sa.json?.blockedReason === NO_ACCESS, flags(sa.json));
    c.that('after: sal still sees the proposal (Net 90)', (sa.json?.changes || []).some((x) => x.field === 'paymentTerm' && /90/.test(x.proposedText || '')), sa.json?.changes);
    const va = await rt.approvals.get(T.vic, id);
    c.that('after: vic 404', va.status === 404, `${va.status} ${String(va.text).slice(0, 200)}`);
    const aa = await rt.approvals.get(T.admin, id);
    c.that('after: admin 200 with current', aa.status === 200 && aa.json?.current != null, `${aa.status} ${JSON.stringify(nowVals(aa.json))}`);
    const na = await rt.approvals.get(T.nico, id);
    const ni = await rt.api('GET', `/api/invoices/${s.inv.id}`, { token: T.nico });
    c.that('after: nico (North viewer) can open the invoice', ni.status === 200, ni.status);
    w.r5move = { s, nicoApproval: na.status };
    return { ok: c.ok, actual: `${c.summary()}; info: nico GET approval -> ${na.status} (a closed change asked in South; not asserted)`, evidence: `${s.fixture}; move ${mv.status}`, codeRef: `${SCOPE} canSeeRecord/recordRegion` };
  }, { retry: false });

  await tcase({ id: 'APP-R5-02', feature: 'Region move: approval follows the record (RGN-R4-1 neighbours)', kind: 'API', prd: 'B2',
    title: 'The same holds for a promise update, a dispute approval and a held payment create (judged by the customer): sal 200 with no live values and the no-access reason; vic 404; admin 200',
    steps: 'same moved customer as APP-R5-01: held P (PROMISE_UPDATE 3000->4500), D (DISPUTE_APPROVE, cancel dispute on a 2000 invoice), C (PAYMENT_RECORD create 7000); after the move GET each as sal, vic, admin',
    expected: 'FIXLOG r4 B1 (recordRegion for every kind; creates judged by the customer\'s region): sal 200 CLOSED, blockedReason "You no longer have access to this record", no current values; vic 404; admin 200 (P and D with current)' }, async () => {
    const c = checks();
    const m = w.r5move;
    if (!m) return { ok: false, actual: 'APP-R5-01 did not run' };
    for (const k of ['P', 'D', 'C']) {
      const id = m.s.ids[k];
      c.that(`${k} held`, !!id, m.s.fixture);
      if (!id) continue;
      const sa = await rt.approvals.get(T.sal, id);
      c.that(`${k}: sal 200 CLOSED no-access, no live values`, sa.status === 200 && sa.json?.status === 'CLOSED' && sa.json?.blockedReason === NO_ACCESS && noNow(sa.json), `${sa.status} ${JSON.stringify(flags(sa.json))} ${JSON.stringify(nowVals(sa.json))} ${sa.status >= 400 ? String(sa.text).slice(0, 200) : ''}`);
      const va = await rt.approvals.get(T.vic, id);
      c.that(`${k}: vic 404`, va.status === 404, `${va.status} ${JSON.stringify(flags(va.json))}`);
      const aa = await rt.approvals.get(T.admin, id);
      c.that(`${k}: admin 200${k === 'C' ? '' : ' with current'}`, aa.status === 200 && (k === 'C' || aa.json?.current != null), `${aa.status} ${JSON.stringify(nowVals(aa.json))}`);
    }
    return { ok: c.ok, actual: c.summary(), evidence: m.s.fixture, codeRef: `${SCOPE} canSeeRecord/isVisible` };
  }, { retry: false });

  await tcase({ id: 'APP-R5-03', feature: 'Region move: lists agree with by-id (RGN-R4-1 neighbours)', kind: 'API', prd: 'B1',
    title: 'For sal, vic, nico and admin, the moved customer\'s changes appear in GET /api/approvals exactly when GET /api/approvals/{id} answers 200',
    steps: 'after APP-R5-01/02: for each user list /api/approvals?view=ALL (all pages) and GET each of the 4 pc ids',
    expected: 'DESIGN §5.11.9: one visibility rule for list and detail (forList vs isVisible): id in list <=> detail 200' }, async () => {
    const c = checks();
    const m = w.r5move;
    if (!m) return { ok: false, actual: 'APP-R5-01 did not run' };
    const pcs = Object.values(m.s.ids).filter(Boolean);
    for (const u of ['sal', 'vic', 'nico', 'admin']) {
      const l = await rt.list(T[u], '/api/approvals', { view: 'ALL' });
      const listed = new Set((l.json?.content || []).map((x) => x.id));
      const res = [];
      for (const id of pcs) {
        const g = await rt.approvals.get(T[u], id);
        res.push({ id, list: listed.has(id), get: g.status });
      }
      c.that(`${u}: list ${l.status} agrees with GET`, l.status === 200 && res.every((r) => r.list === (r.get === 200)), res);
    }
    return { ok: c.ok, actual: c.summary(), evidence: m.s.fixture, codeRef: `${SCOPE} forList vs isVisible` };
  }, { retry: false });

  await tcase({ id: 'APP-R5-04', feature: 'Region move back (RGN-R4-1 neighbours)', kind: 'API', prd: 'B2',
    title: 'Moving the customer back to South gives the South viewer and approver the record again: vic 200 and sal sees live values on the closed change',
    steps: 'after APP-R5-01..03: admin moves the customer North -> South; GET the terms pc as vic, sal, nico; GET invoice as vic',
    expected: 'TRIAGE r4 userDecision ("the record\'s region decides"): vic invoice 200, approval 200 with current; sal 200 with current, blockedReason not the no-access reason; nico invoice 404' }, async () => {
    const c = checks();
    const m = w.r5move;
    if (!m) return { ok: false, actual: 'APP-R5-01 did not run' };
    const mv = await rt.moveCustomerRegion(T.admin, m.s.cu.id, SOUTH, 'r5 move back');
    c.that('move back 200', mv.status === 200, brief(mv).slice(0, 300));
    const vi = await rt.api('GET', `/api/invoices/${m.s.inv.id}`, { token: T.vic });
    c.that('vic invoice 200', vi.status === 200, vi.status);
    const va = await rt.approvals.get(T.vic, m.s.ids.T);
    c.that('vic approval 200 with current', va.status === 200 && va.json?.current != null, `${va.status} ${JSON.stringify(nowVals(va.json))}`);
    const sa = await rt.approvals.get(T.sal, m.s.ids.T);
    c.that('sal approval current filled, not no-access', sa.status === 200 && sa.json?.current != null && sa.json?.blockedReason !== NO_ACCESS, `${sa.status} ${JSON.stringify(flags(sa.json))} ${JSON.stringify(nowVals(sa.json))}`);
    const ni = await rt.api('GET', `/api/invoices/${m.s.inv.id}`, { token: T.nico });
    c.that('nico invoice 404', ni.status === 404, ni.status);
    return { ok: c.ok, actual: c.summary(), evidence: `${m.s.fixture}; move back ${mv.status}`, codeRef: `${SCOPE} recordRegion` };
  }, { retry: false });

  await tcase({ id: 'APP-R5-05', feature: 'No over-hiding (RGN-R4-1 neighbours)', kind: 'API', prd: 'B2',
    title: 'Changes whose customer never moved are unaffected: a South pending change shows live values to sal and vic; a North held create is approvable by nate and visible to nico, not to vic',
    steps: 'fresh South customer: mia PATCH NET_90 (held); GET as sal/vic; fresh North customer (POC nora): nora POST invoice 60000 (> North INVOICE_CREATE 50000, held); GET as nate (APPROVE North), nico, vic; for-record CUSTOMER as nico; withdraw both',
    expected: 'DESIGN §5.11.9: South change sal 200 current filled blockedReason null canApprove; vic 200 current filled. North create: nate 200 canApprove, blockedReason null; nico 200 (sees the customer); vic 404; for-record lists it for nico' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'SOUTH', 'r5keep');
    const inv = await invoiceBy(T.admin, cu, 3000);
    const h = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    c.that('South change held', h.held, brief(h));
    const s = await rt.approvals.get(T.sal, h.pendingChange?.id);
    c.that('sal 200 current, null reason, canApprove', s.status === 200 && s.json?.current != null && s.json?.blockedReason == null && s.json?.canApprove === true, `${s.status} ${JSON.stringify(flags(s.json))}`);
    const v = await rt.approvals.get(T.vic, h.pendingChange?.id);
    c.that('vic 200 current', v.status === 200 && v.json?.current != null, `${v.status} ${JSON.stringify(nowVals(v.json))}`);
    const nc = await customerIn(w, 'NORTH', 'r5north');
    const ni = await rt.createInvoice(T.nora, { customerId: nc.id, amount: 60000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: w.users.nora.id });
    c.that('North create held', ni.held, brief(ni));
    const pid = ni.pendingChange?.id;
    const na = await rt.approvals.get(T.nate, pid);
    c.that('nate 200 canApprove, null reason', na.status === 200 && na.json?.canApprove === true && na.json?.blockedReason == null, `${na.status} ${JSON.stringify(flags(na.json))}`);
    const nn = await rt.approvals.get(T.nico, pid);
    c.that('nico 200', nn.status === 200, `${nn.status} ${JSON.stringify(flags(nn.json))}`);
    const nv = await rt.approvals.get(T.vic, pid);
    c.that('vic 404', nv.status === 404, nv.status);
    const fr = await rt.approvals.forRecord(T.nico, 'CUSTOMER', nc.id);
    c.that('for-record CUSTOMER as nico lists it', fr.status === 200 && (fr.json || []).some((x) => x.id === pid), `${fr.status} ${String(fr.text).slice(0, 200)}`);
    if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    if (pid) await rt.approvals.withdraw(T.nora, pid);
    return { ok: c.ok, actual: c.summary(), codeRef: `${SCOPE} isVisible` };
  });
}

async function liveUi(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await tcase({ id: 'APP-R5-UI-01', feature: 'Region move: approval follows the record (RGN-R4-1)', kind: 'UI', prd: 'B2',
    title: 'A South-only approver opening a change whose customer moved North sees "You no longer have access to this record", no Approve/Reject, and no crash; the South-only viewer gets a not-found page',
    steps: 'fresh South customer, invoice 3000, mia PATCH NET_90 (held); admin moves customer to North; sal opens /approvals/{id}; vic opens /approvals/{id}',
    expected: 'DESIGN §7.7.1 + TRIAGE r4: sal sees the notice verbatim, the proposed Net 90, no Approve/Reject (CLOSED); vic sees a not-found message, no page exception' }, async (attempt) => {
    const c = checks();
    await cast(w);
    const cu = await customerIn(w, 'SOUTH', 'r5ui');
    const inv = await invoiceBy(T.admin, cu, 3000);
    const h = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    c.that('held', h.held, brief(h));
    const mv = await rt.moveCustomerRegion(T.admin, cu.id, w.regions.NORTH.id, 'r5 ui move');
    c.that('move 200', mv.status === 200, brief(mv).slice(0, 200));
    const id = h.pendingChange?.id;
    let shot = ''; let shot2 = '';
    const app = await openAs('sal', `/approvals/${id}`);
    try {
      c.that('notice shown', !!(await waitText(app.page, NO_ACCESS, 10000)), (await allText(app.page)).slice(0, 600));
      const t = await allText(app.page);
      c.that('Net 90 proposal shown', /Net 90/.test(t), t.slice(0, 600));
      c.that('no Approve/Reject', !(await rt.exists(app.page, 'Approve', { role: 'button' })) && !(await rt.exists(app.page, 'Reject', { role: 'button' })));
      c.that('closed status shown', /closed/i.test(t), t.slice(0, 400));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shot = await rt.shot(app.page, R.shotsDir, `APP-R5-UI-01-${attempt}-sal`);
      c.that('sal: no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('sal: no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 3));
      await app.close();
    }
    const app2 = await openAs('vic', `/approvals/${id}`);
    try {
      await app2.page.waitForTimeout(3000);
      const t = await allText(app2.page);
      c.that('vic: not-found message', /not found|no longer|could not|doesn.t exist|does not exist/i.test(t), t.slice(0, 500));
      c.that('vic: no proposal leaked', !/Net 90/.test(t), t.slice(0, 500));
    } catch (e) {
      c.that('vic UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shot2 = await rt.shot(app2.page, R.shotsDir, `APP-R5-UI-01-${attempt}-vic`);
      c.that('vic: no page exception', app2.pageErrors.length === 0, app2.pageErrors.slice(0, 3));
      await app2.close();
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shot, evidence: `vic shot ${shot2}; pc ${id}; move ${mv.status}`, codeRef: DETAIL };
  });
}

/** Before r4.asOf ages the world: a South customer with held T and P (and D, C) still PENDING. */
async function prepAsOf(w) {
  await cast(w);
  w.r5asof = await heldSet(w, 'r5asof');
}

async function asOf(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await tcase({ id: 'APP-R5-10', feature: 'As-of region of a moved record (RGN-R4-1)', kind: 'API', prd: 'B3',
    title: 'As of before the move, the South viewer and approver see the change (read-only) and the North viewer does not; live, the reverse; lists agree',
    steps: 'held T/P/D/C on a South customer; ageWorld(10) (r4.asOf); admin moves the customer to North now; GET each pc ?asOf=day(-2) and live as sal, vic, nico; list /api/approvals?asOf=day(-2) as vic',
    expected: 'TRIAGE r4 userDecision "As of a date, the record\'s region at that date decides": as-of sal 200 "Read-only in the as-of view" current null statusAt PENDING; vic as-of 200; nico as-of 404; live sal 200 no-access, vic 404; vic as-of list contains the pcs' }, async () => {
    const c = checks();
    const s = w.r5asof;
    if (!s) return { ok: false, actual: 'prepAsOf did not run' };
    const mv = await rt.moveCustomerRegion(T.admin, s.cu.id, w.regions.NORTH.id, 'r5 asof move');
    c.that('move 200', mv.status === 200, brief(mv).slice(0, 300));
    const AT = rt.day(-2);
    const pcs = Object.entries(s.ids).filter(([, v]) => v);
    c.that('fixtures held (4)', pcs.length === 4, s.fixture);
    for (const [k, id] of pcs) {
      const sa = await rt.approvals.get(T.sal, id, { asOf: AT });
      c.that(`${k}: sal as-of 200 read-only, PENDING, no current`, sa.status === 200 && sa.json?.blockedReason === RO && sa.json?.statusAt === 'PENDING' && sa.json?.current == null && !sa.json?.canApprove, `${sa.status} ${JSON.stringify(flags(sa.json))} ${sa.status >= 400 ? String(sa.text).slice(0, 200) : ''}`);
      const va = await rt.approvals.get(T.vic, id, { asOf: AT });
      c.that(`${k}: vic as-of 200`, va.status === 200, `${va.status} ${String(va.text).slice(0, 200)}`);
      const na = await rt.approvals.get(T.nico, id, { asOf: AT });
      c.that(`${k}: nico as-of 404`, na.status === 404, `${na.status} ${JSON.stringify(flags(na.json))}`);
      const sl = await rt.approvals.get(T.sal, id);
      c.that(`${k}: sal live 200 no-access`, sl.status === 200 && sl.json?.blockedReason === NO_ACCESS && noNow(sl.json), `${sl.status} ${JSON.stringify(flags(sl.json))} ${JSON.stringify(nowVals(sl.json))}`);
      const vl = await rt.approvals.get(T.vic, id);
      c.that(`${k}: vic live 404`, vl.status === 404, vl.status);
    }
    const l = await rt.list(T.vic, '/api/approvals', { view: 'ALL' }, { asOf: AT });
    const ids = (l.json?.content || []).map((x) => x.id);
    c.that('vic as-of list 200 contains all 4', l.status === 200 && pcs.every(([, id]) => ids.includes(id)), `${l.status} ${JSON.stringify(ids)}`);
    const ll = await rt.list(T.vic, '/api/approvals', { view: 'ALL' });
    const lids = (ll.json?.content || []).map((x) => x.id);
    c.that('vic live list contains none', ll.status === 200 && pcs.every(([, id]) => !lids.includes(id)), `${ll.status} ${JSON.stringify(lids)}`);
    const nl = await rt.list(T.nico, '/api/approvals', { view: 'ALL' }, { asOf: AT });
    const nids = (nl.json?.content || []).map((x) => x.id);
    c.that('nico as-of list contains none', nl.status === 200 && pcs.every(([, id]) => !nids.includes(id)), `${nl.status} ${JSON.stringify(nids)}`);
    const vi = await rt.api('GET', `/api/invoices/${s.inv.id}`, { token: T.vic, query: { asOf: AT } });
    c.that('vic invoice as-of 200 (cross-check)', vi.status === 200, vi.status);
    return { ok: c.ok, actual: c.summary(), evidence: `${s.fixture}; move ${mv.status}`, codeRef: `${SCOPE} recordRegion (Temporal.fromValid)` };
  }, { retry: false });
}

module.exports = { live, liveUi, prepAsOf, asOf };
