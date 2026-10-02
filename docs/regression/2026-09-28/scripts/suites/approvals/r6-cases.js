// Round 6: proves the r5 fixes and looks one step around them.
//   RGN-R5-1 (PendingChangeScope.isVisible/forList): a change about a moved customer's record is visible to whoever can
//     see that record now, in its own region (detail and list), and a held create follows the customer.
//   ASO-R5-1 (approval_detail_screen.dart): when the server says "You no longer have access to this record", the Record
//     and Customer cells are plain text and "Open …" is hidden.
// Around them: no over-exposure (BOOK scope, other regions, unrelated South changes), the regionId column still meaning
// the change's own region, list/summary/count/export agreement, the new region's reader opening the change from the
// record's History tab, and the as-of view.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, invoiceBy, promiseBy, brief, pick, openAs, waitText, allText } = H;
const SCOPE = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java';
const DETAIL = 'frontend/lib/features/approvals/approval_detail_screen.dart';
const NO_ACCESS = 'You no longer have access to this record';
const RO = 'Read-only in the as-of view';
const flags = (j) => pick(j, ['status', 'statusAt', 'canApprove', 'canReject', 'canWithdraw', 'blockedReason']);
const hasNow = (j) => j && j.current != null;

async function staff(w, username, grants) {
  const admin = w.tokens.admin;
  let u = await rt.findUser(admin, username);
  if (!u) u = await rt.createStaff(admin, 'E2E_REGIONAL', username, { username, fullName: `R6 ${username}`, grants });
  else rt.need(await rt.setGrants(admin, u.id, grants), `grants ${username}`);
  w.users[username] = { ...u, grants };
  w.tokens[username] = await rt.login(username);
  return u;
}

async function cast(w) {
  if (w.r6cast) return;
  const S = w.regions.SOUTH.id; const N = w.regions.NORTH.id;
  await staff(w, 'sal', [{ regionId: S, accessLevel: 'APPROVE', recordScope: 'ALL' }]);
  await staff(w, 'vic', [{ regionId: S, accessLevel: 'READ', recordScope: 'ALL' }]);
  await staff(w, 'nico', [{ regionId: N, accessLevel: 'READ', recordScope: 'ALL' }]);
  await staff(w, 'nbk', [{ regionId: N, accessLevel: 'MANAGE', recordScope: 'BOOK' }]); // North, own book only
  await staff(w, 'duo', [{ regionId: N, accessLevel: 'READ', recordScope: 'ALL' }, { regionId: S, accessLevel: 'READ', recordScope: 'ALL' }]);
  w.r6cast = true;
}

/** A fresh South customer (POC sam) with held T (INVOICE_TERMS), P (PROMISE_UPDATE), C (PAYMENT_RECORD create), D (DISPUTE_APPROVE) by mia. */
async function heldSet(w, prefix) {
  const T = w.tokens; const U = w.users;
  const cu = await customerIn(w, 'SOUTH', prefix);
  const inv = await invoiceBy(T.admin, cu, 3000);
  const inv2 = await invoiceBy(T.admin, cu, 2000);
  const pr = await promiseBy(T.admin, cu, 3000);
  const hT = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
  const hP = rt.heldAware(await rt.api('PUT', `/api/promises/${pr.id}`, { token: T.mia, body: { amount: 4500, promisedDate: pr.promisedDate, collectionPocUserId: U.sam.id, notes: null, invoiceIds: [] } }));
  const hC = await rt.createPayment(T.mia, { customerId: cu.id, amount: 7000, collectionPocUserId: U.sam.id });
  const custTok = await rt.login(cu.username, cu.password);
  const dsp = await rt.createDispute(custTok, { targetType: 'INVOICE', targetId: inv2.id, reason: 'r6 dispute', proposedChangeJson: JSON.stringify({ action: 'cancel' }) });
  const hD = dsp.ok ? rt.heldAware(await rt.api('POST', `/api/disputes/${dsp.json.id}/approve`, { token: T.mia, body: {} })) : { held: false, status: dsp.status, text: dsp.text };
  const ids = { T: hT.pendingChange?.id, P: hP.pendingChange?.id, C: hC.pendingChange?.id, D: hD.pendingChange?.id };
  const fixture = `customer ${cu.id} inv ${inv.id} inv2 ${inv2.id} promise ${pr.id} dispute ${dsp.json?.id}(${dsp.status}); held T:${hT.status}/${ids.T} P:${hP.status}/${ids.P} C:${hC.status}/${ids.C} D:${hD.status}/${ids.D}`;
  return { cu, inv, inv2, pr, dispute: dsp.json, h: { T: hT, P: hP, C: hC, D: hD }, ids, fixture };
}

const listIds = async (token, query = {}, scope = {}) => {
  const l = await rt.list(token, '/api/approvals', { view: 'ALL', ...query }, scope);
  return { status: l.status, ids: (l.json?.content || []).map((x) => x.id), rows: l.json?.content || [], total: l.json?.totalElements, text: l.text };
};

async function live(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await cast(w);
  const NORTH = w.regions.NORTH.id; const SOUTH = w.regions.SOUTH.id; const WEST = w.regions.WEST.id;

  await tcase({ id: 'APP-R6-01', feature: 'Moved record: the new region\'s reader sees its changes (RGN-R5-1)', kind: 'API', prd: 'B2',
    title: 'A North-only reader (nico) cannot see a South customer\'s held changes; once the customer moves to North she opens every one of them (CLOSED, with live values, no blocked reason, no buttons) and they are in her list',
    steps: 'nico = READ/ALL North only; fresh South customer: mia holds T (terms), P (promise update), C (payment create 7000), D (dispute approve); GET each + list as nico; admin POST /api/customers/{id}/move-region North; GET invoice, each pc, list (no regionIds / regionIds=North / filter id:eq) as nico',
    expected: 'r5 TRIAGE userDecision + DESIGN §5.11.9: before, nico 404 and not listed; after, invoice 200, each pc 200 status CLOSED, blockedReason null, canApprove/canReject/canWithdraw false, current filled (T, P, D); listed with no regionIds, with regionIds=North, and filter id:eq:{pc} returns exactly [pc]' }, async () => {
    const c = checks();
    const s = await heldSet(w, 'r6mv');
    const pcs = Object.entries(s.ids).filter(([, v]) => v);
    c.that('4 changes held', pcs.length === 4, s.fixture);
    for (const [k, id] of pcs) {
      const g = await rt.approvals.get(T.nico, id);
      c.that(`before: ${k} nico 404`, g.status === 404, `${g.status} ${JSON.stringify(flags(g.json))}`);
    }
    const lb = await listIds(T.nico);
    c.that('before: nico list has none', lb.status === 200 && pcs.every(([, id]) => !lb.ids.includes(id)), `${lb.status} ${JSON.stringify(lb.ids)}`);
    const mv = await rt.moveCustomerRegion(T.admin, s.cu.id, NORTH, 'r6 move');
    c.that('move 200', mv.status === 200, brief(mv).slice(0, 300));
    const ni = await rt.api('GET', `/api/invoices/${s.inv.id}`, { token: T.nico });
    c.that('after: nico GET invoice 200', ni.status === 200, ni.status);
    for (const [k, id] of pcs) {
      const g = await rt.approvals.get(T.nico, id);
      const j = g.json || {};
      c.that(`after: ${k} nico 200 CLOSED, no reason, no buttons`, g.status === 200 && j.status === 'CLOSED' && j.blockedReason == null && !j.canApprove && !j.canReject && !j.canWithdraw, `${g.status} ${JSON.stringify(flags(j))} ${g.status >= 400 ? String(g.text).slice(0, 200) : ''}`);
      if (k !== 'C') {
        c.that(`after: ${k} current + changes[].current filled`, hasNow(j) && (j.changes || []).length > 0 && (j.changes || []).every((x) => x.current != null || x.currentText != null), JSON.stringify((j.changes || []).map((x) => pick(x, ['field', 'current', 'currentText']))).slice(0, 300));
      }
      const f = await listIds(T.nico, { filter: [`id:eq:${id}`] });
      c.that(`after: ${k} nico filter id:eq returns exactly [pc]`, f.status === 200 && f.ids.length === 1 && f.ids[0] === id && f.total === 1, `${f.status} ${JSON.stringify(f.ids)} total ${f.total}`);
    }
    const la = await listIds(T.nico);
    c.that('after: nico list (no regionIds) has all 4', la.status === 200 && pcs.every(([, id]) => la.ids.includes(id)), `${la.status} ${JSON.stringify(la.ids)}`);
    const ln = await listIds(T.nico, {}, { regionIds: [NORTH] });
    c.that('after: nico list regionIds=North has all 4', ln.status === 200 && pcs.every(([, id]) => ln.ids.includes(id)), `${ln.status} ${JSON.stringify(ln.ids)}`);
    w.r6move = s;
    return { ok: c.ok, actual: c.summary(), evidence: `${s.fixture}; move ${mv.status}`, codeRef: `${SCOPE}:115-126 isVisible, forList record branches` };
  }, { retry: false });

  await tcase({ id: 'APP-R6-02', feature: 'Moved record: region scoping of the list (RGN-R5-1 neighbours)', kind: 'API', prd: 'B1',
    title: 'After the move: the old region\'s approver keeps the changes and its plain reader loses them; the admin finds them under both North (record) and South (approver) without duplicates; a reader of both regions finds them only under North; the regionId column still says South',
    steps: 'after APP-R6-01: list as sal, vic, admin (regionIds North / South / both), duo (READ North+South: regionIds South / North); nico list filter regionId:eq:South; row regionId',
    expected: 'r5 TRIAGE userDecisions: sal lists all 4, vic none (and vic GET 404); admin both; both-region list has each id once and totalElements = rows; duo South none, North all; regionId filter/column = the region the change was asked in (South)' }, async () => {
    const c = checks();
    const s = w.r6move;
    if (!s) return { ok: false, actual: 'APP-R6-01 did not run' };
    const pcs = Object.values(s.ids).filter(Boolean);
    const all = (l) => pcs.every((id) => l.ids.includes(id));
    const none = (l) => pcs.every((id) => !l.ids.includes(id));
    const sal = await listIds(T.sal);
    c.that('sal (South approver) lists all 4', sal.status === 200 && all(sal), JSON.stringify(sal.ids));
    const vic = await listIds(T.vic);
    c.that('vic (South reader) lists none', vic.status === 200 && none(vic), JSON.stringify(vic.ids));
    const vg = await rt.approvals.get(T.vic, s.ids.T);
    c.that('vic GET T 404', vg.status === 404, vg.status);
    const an = await listIds(T.admin, {}, { regionIds: [NORTH] });
    c.that('admin regionIds=North lists all 4 (record branch)', an.status === 200 && all(an), JSON.stringify(an.ids.slice(0, 40)));
    const as = await listIds(T.admin, {}, { regionIds: [SOUTH] });
    c.that('admin regionIds=South lists all 4 (approver branch)', as.status === 200 && all(as), JSON.stringify(as.ids.slice(0, 40)));
    const ab = await listIds(T.admin, {}, { regionIds: [NORTH, SOUTH] });
    const uniq = new Set(ab.ids);
    c.that('admin both regions: no duplicate rows, totalElements = rows', ab.status === 200 && uniq.size === ab.ids.length && ab.total === ab.ids.length && all(ab), `rows ${ab.ids.length} unique ${uniq.size} total ${ab.total}`);
    const ds = await listIds(T.duo, {}, { regionIds: [SOUTH] });
    c.that('duo regionIds=South lists none', ds.status === 200 && none(ds), `${ds.status} ${JSON.stringify(ds.ids)}`);
    const dn = await listIds(T.duo, {}, { regionIds: [NORTH] });
    c.that('duo regionIds=North lists all 4', dn.status === 200 && all(dn), `${dn.status} ${JSON.stringify(dn.ids)}`);
    const dg = await rt.approvals.get(T.duo, s.ids.T, { regionIds: [SOUTH] });
    c.that('duo GET T (regionIds=South) 200: a by-id read is not narrowed by the list scope', dg.status === 200, dg.status);
    const fr = await listIds(T.nico, { filter: [`regionId:eq:${SOUTH}`] });
    c.that('nico filter regionId:eq:South lists all 4 (column = the change\'s region)', fr.status === 200 && all(fr), `${fr.status} ${JSON.stringify(fr.ids)} ${fr.status >= 400 ? String(fr.text).slice(0, 200) : ''}`);
    const fn = await listIds(T.nico, { filter: [`regionId:eq:${NORTH}`] });
    c.that('nico filter regionId:eq:North lists none of them', fn.status === 200 && pcs.every((id) => !fn.ids.includes(id)), `${fn.status} ${JSON.stringify(fn.ids)}`);
    const row = (await listIds(T.nico, { filter: [`id:eq:${s.ids.T}`] })).rows[0];
    c.that('nico row regionId = South', row && row.regionId === SOUTH, JSON.stringify(pick(row, ['id', 'regionId', 'regionName', 'status'])));
    return { ok: c.ok, actual: c.summary(), evidence: s.fixture, codeRef: `${SCOPE} forList` };
  }, { retry: false });

  await tcase({ id: 'APP-R6-03', feature: 'Moved record: no over-exposure (RGN-R5-1 neighbours)', kind: 'API', prd: 'B1',
    title: 'Relaxing the change-region gate does not leak: a North BOOK user sees only what her book reaches (the dispute change: disputes have no book), a West user and a North reader of an unrelated South change get 404; adding her as the customer\'s POC opens the held create too',
    steps: 'after APP-R6-01: nbk = MANAGE/BOOK North (no customers); wendy = West; GET/list as each; fresh South customer, mia holds terms change: nico GET/list; admin adds nbk as SUCCESS POC of the moved customer; nbk GET/list',
    expected: 'DESIGN §5.11.9 + ScopeResolver books (INVOICE Sales POC, PROMISE/PAYMENT Collection POC, CUSTOMER any POC, DISPUTE none): nbk T/P/C 404, D 200; wendy all 404/none; nico 404 on the unrelated South change; after the SUCCESS POC add, nbk C and D 200, T/P 404; lists agree with by-id' }, async () => {
    const c = checks();
    const s = w.r6move;
    if (!s) return { ok: false, actual: 'APP-R6-01 did not run' };
    const pcs = Object.values(s.ids).filter(Boolean);
    // Book rules (ScopeResolver.Kind): INVOICE = Sales POC, PROMISE/PAYMENT = Collection POC, CUSTOMER = any POC,
    // DISPUTE = no book (region-wide). So a North BOOK user without the customer still sees the dispute's change.
    const byKey = Object.entries(s.ids).filter(([, v]) => v);
    const nbStat = async () => { const o = {}; for (const [k, id] of byKey) o[k] = (await rt.approvals.get(T.nbk, id)).status; return o; };
    const nb0 = await nbStat();
    c.that('nbk (no book): T/P/C 404, D 200 (disputes have no book)', nb0.T === 404 && nb0.P === 404 && nb0.C === 404 && nb0.D === 200, JSON.stringify(nb0));
    const nl0 = await listIds(T.nbk);
    c.that('nbk list agrees (only D)', nl0.status === 200 && byKey.every(([k, id]) => nl0.ids.includes(id) === (nb0[k] === 200)), `${nl0.status} ${JSON.stringify(nl0.ids)}`);
    const nbd = await rt.api('GET', `/api/disputes/${s.dispute?.id}`, { token: T.nbk });
    c.that('cross-check: nbk can open the dispute itself', nbd.status === 200, nbd.status);
    {
      const gs = [];
      for (const id of pcs) gs.push((await rt.approvals.get(T.wendy, id)).status);
      c.that('wendy: every GET 404', gs.every((x) => x === 404), JSON.stringify(gs));
      const l = await listIds(T.wendy);
      c.that('wendy: list has none', l.status === 200 && pcs.every((id) => !l.ids.includes(id)), `${l.status} ${JSON.stringify(l.ids)}`);
    }
    const cu = await customerIn(w, 'SOUTH', 'r6other');
    const inv = await invoiceBy(T.admin, cu, 3000);
    const h = await rt.patchInvoice(T.mia, inv.id, { paymentTerm: 'NET_90' });
    c.that('unrelated South change held', h.held, brief(h));
    const og = await rt.approvals.get(T.nico, h.pendingChange?.id);
    c.that('nico GET unrelated South change 404', og.status === 404, og.status);
    const ol = await listIds(T.nico);
    c.that('nico list lacks it', !ol.ids.includes(h.pendingChange?.id), JSON.stringify(ol.ids));
    const poc = await rt.api('POST', `/api/customers/${s.cu.id}/pocs`, { token: T.admin, body: { pocType: 'SUCCESS', userId: w.users.nbk.id, primary: true } });
    c.that('admin adds nbk as SUCCESS POC', poc.status === 200 || poc.status === 201, brief(poc).slice(0, 300));
    if (poc.status < 300) {
      const nb1 = await nbStat();
      const nbInv = await rt.api('GET', `/api/invoices/${s.inv.id}`, { token: T.nbk });
      c.that('nbk (customer now in book): C (create, judged by the customer) 200, D 200; T/P stay 404 like the invoice/promise themselves', nb1.C === 200 && nb1.D === 200 && nb1.T === 404 && nb1.P === 404 && nbInv.status === 404, `${JSON.stringify(nb1)} invoice ${nbInv.status}`);
      const l = await listIds(T.nbk);
      c.that('nbk list agrees with by-id', l.status === 200 && byKey.every(([k, id]) => l.ids.includes(id) === (nb1[k] === 200)), `${l.status} ${JSON.stringify(l.ids)}`);
    }
    if (h.pendingChange) await rt.approvals.withdraw(T.mia, h.pendingChange.id);
    return { ok: c.ok, actual: c.summary(), evidence: `${s.fixture}; other pc ${h.pendingChange?.id}`, codeRef: `${SCOPE} isVisible/forList` };
  }, { retry: false });

  await tcase({ id: 'APP-R6-04', feature: 'Moved customer: a held create follows the customer (RGN-R5-1)', kind: 'API', prd: 'B2',
    title: 'A held invoice create (no record yet) on a South customer: before the move vic sees it and nico does not; after the move to North nico sees it (detail and list), vic does not, sal gets the no-access reason, and mia (the maker, still in South) still opens it',
    steps: 'fresh South customer; mia POST invoice 12000 (> South INVOICE_CREATE 10000) -> 202, entityId null; GET/list as vic, nico; admin moves customer North; GET/list as nico, vic, sal, mia',
    expected: 'r5 TRIAGE B1 (create judged by the customer\'s region): before vic 200 nico 404; after nico 200 CLOSED + listed, vic 404 + not listed, sal 200 blockedReason "You no longer have access to this record", mia 200' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'SOUTH', 'r6create');
    const h = await rt.createInvoice(T.mia, { customerId: cu.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: w.users.sam.id });
    c.that('held 202', h.held && h.pendingChange && h.pendingChange.entityId == null, brief(h).slice(0, 300));
    const id = h.pendingChange?.id;
    if (!id) return { ok: false, actual: c.summary() };
    const vb = await rt.approvals.get(T.vic, id);
    c.that('before: vic 200', vb.status === 200, vb.status);
    const nb = await rt.approvals.get(T.nico, id);
    c.that('before: nico 404', nb.status === 404, nb.status);
    const mv = await rt.moveCustomerRegion(T.admin, cu.id, NORTH, 'r6 create move');
    c.that('move 200', mv.status === 200, brief(mv).slice(0, 300));
    const na = await rt.approvals.get(T.nico, id);
    c.that('after: nico 200 CLOSED, reason null', na.status === 200 && na.json?.status === 'CLOSED' && na.json?.blockedReason == null, `${na.status} ${JSON.stringify(flags(na.json))}`);
    const nl = await listIds(T.nico);
    c.that('after: nico lists it', nl.ids.includes(id), JSON.stringify(nl.ids));
    const va = await rt.approvals.get(T.vic, id);
    c.that('after: vic 404', va.status === 404, va.status);
    const vl = await listIds(T.vic);
    c.that('after: vic does not list it', !vl.ids.includes(id), JSON.stringify(vl.ids));
    const sa = await rt.approvals.get(T.sal, id);
    c.that('after: sal 200 no-access', sa.status === 200 && sa.json?.blockedReason === NO_ACCESS, `${sa.status} ${JSON.stringify(flags(sa.json))}`);
    const ma = await rt.approvals.get(T.mia, id);
    c.that('after: mia (maker) 200', ma.status === 200, `${ma.status} ${JSON.stringify(flags(ma.json))}`);
    const ml = await listIds(T.mia, { view: 'MINE' });
    c.that('after: mia MINE lists it', ml.ids.includes(id), JSON.stringify(ml.ids.slice(0, 30)));
    return { ok: c.ok, actual: c.summary(), evidence: `customer ${cu.id}; pc ${id}; move ${mv.status}`, codeRef: `${SCOPE}:122-126` };
  });

  await tcase({ id: 'APP-R6-05', feature: 'Counts, summary and export agree with the list (RGN-R5-1 neighbours)', kind: 'API', prd: 'B1',
    title: 'For nico, sal, duo and admin: summary.count = PENDING rows in the list; awaiting-me count = AWAITING_ME list size; nico\'s CSV export contains the moved changes',
    steps: 'after APP-R6-01..04: GET /api/approvals/summary, /api/approvals/awaiting-me/count and list view=ALL filter status:eq:PENDING / view=AWAITING_ME as each; nico POST /api/approvals/export {action:EXPORT, selectAllMatchingFilter:true}',
    expected: 'DESIGN §6.11: the summary and badge are made of the same forList scope as the list; export rows = list rows' }, async () => {
    const c = checks();
    const s = w.r6move;
    for (const u of ['nico', 'sal', 'duo', 'admin']) {
      const sm = await rt.approvals.summary(T[u]);
      const pl = await listIds(T[u], { filter: ['status:eq:PENDING'] });
      const cnt = sm.json?.pendingCount;
      c.that(`${u}: summary count = PENDING rows`, sm.status === 200 && pl.status === 200 && Number(cnt) === pl.ids.length, `summary ${sm.status} ${String(sm.text).slice(0, 200)}; pending rows ${pl.ids.length}`);
      const ac = await rt.api('GET', '/api/approvals/awaiting-me/count', { token: T[u] });
      const al = await listIds(T[u], { view: 'AWAITING_ME' });
      c.that(`${u}: awaiting-me count = AWAITING_ME rows`, ac.status === 200 && Number(ac.json?.count) === al.ids.length, `count ${ac.status} ${ac.text}; rows ${al.ids.length}`);
    }
    const ex = await rt.api('POST', '/api/approvals/export', { token: T.nico, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const lines = String(ex.text || '').split(/\r?\n/).slice(1).filter(Boolean);
    const exIds = lines.map((l) => Number(l.split(',')[0].replace(/"/g, '')));
    const nl = await listIds(T.nico);
    c.that('nico export 200 and rows = list ids', ex.status === 200 && exIds.length === nl.ids.length && nl.ids.every((id) => exIds.includes(id)), `${ex.status} export ${JSON.stringify(exIds)} list ${JSON.stringify(nl.ids)}`);
    if (s) c.that('nico export contains the moved changes', Object.values(s.ids).filter(Boolean).every((id) => exIds.includes(id)), JSON.stringify(exIds));
    return { ok: c.ok, actual: c.summary(), codeRef: 'backend/src/main/java/com/geneinvoice/approval/ApprovalService.java:141-173' };
  });
}

/** Two changes on one South customer: A (terms, APPROVED by sal) and B (terms on a 2nd invoice, CLOSED by the move to North). */
async function approvedThenMoved(w, prefix) {
  const T = w.tokens;
  const cu = await customerIn(w, 'SOUTH', prefix);
  const invA = await invoiceBy(T.admin, cu, 3000);
  const invB = await invoiceBy(T.admin, cu, 2500);
  const hA = await rt.patchInvoice(T.mia, invA.id, { paymentTerm: 'NET_90' });
  const ap = hA.pendingChange ? await rt.approvals.approve(T.sal, hA.pendingChange.id, 'r6 ok') : null;
  const hB = await rt.patchInvoice(T.mia, invB.id, { paymentTerm: 'NET_60' });
  const mv = await rt.moveCustomerRegion(T.admin, cu.id, w.regions.NORTH.id, 'r6 ui move');
  return { cu, invA, invB, A: hA.pendingChange?.id, B: hB.pendingChange?.id, approve: ap?.status, move: mv.status,
    fixture: `customer ${cu.id} invA ${invA.id} ${invA.invoiceNumber} invB ${invB.id} ${invB.invoiceNumber}; A ${hA.status}/${hA.pendingChange?.id} approve ${ap?.status} ${ap?.json?.status}; B ${hB.status}/${hB.pendingChange?.id}; move ${mv.status}` };
}

const nodeFor = async (page, re) => (await rt.semantics(page)).find((n) => re.test(n.label || '') || re.test(n.text || ''));
const hash = (page) => page.evaluate(() => location.hash);

async function liveUi(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await cast(w);

  await tcase({ id: 'APP-R6-UI-01', feature: 'No links into a record the viewer cannot open (ASO-R5-1)', kind: 'UI', prd: 'B2',
    title: 'A South-only approver on an APPROVED and on a CLOSED change whose customer moved North sees the notice, no "Open …", and Record/Customer as plain text: tapping them stays on the approval',
    steps: 'fresh South customer, invoices A 3000 and B 2500; mia PATCH A NET_90 (held), sal approves; mia PATCH B NET_60 (held); admin moves customer to North (B CLOSED); as sal open /approvals/{A}: check notice, no "Open", Record/Customer node roles, press each; same for /approvals/{B}',
    expected: 'r5 TRIAGE F1 + DESIGN §7.7.1/SC-15: notice "You no longer have access to this record"; no "Open INV-…" button; Record and Customer not buttons; a click leaves the URL at #/approvals/{id}' }, async (attempt) => {
    const c = checks();
    const f = await approvedThenMoved(w, 'r6ui');
    c.that('fixture: A approved, B held, moved', f.A && f.B && f.approve === 200 && f.move === 200, f.fixture);
    w.r6ui = f;
    let shots = [];
    for (const [k, id, inv] of [['A', f.A, f.invA], ['B', f.B, f.invB]]) {
      const app = await openAs('sal', `/approvals/${id}`);
      const page = app.page;
      try {
        c.that(`${k}: notice shown`, !!(await waitText(page, NO_ACCESS, 10000)), (await allText(page)).slice(0, 500));
        c.that(`${k}: no "Open …" button`, !(await rt.exists(page, /^Open /)), (await allText(page)).slice(0, 500));
        const rec = await nodeFor(page, new RegExp(`^${inv.invoiceNumber}$`));
        const cus = await nodeFor(page, new RegExp(f.cu.name));
        c.that(`${k}: Record shown as plain text`, rec && rec.role !== 'button' && rec.role !== 'link', rec && pick(rec, ['role', 'label', 'text']));
        c.that(`${k}: Customer shown as plain text`, cus && cus.role !== 'button' && cus.role !== 'link', cus && pick(cus, ['role', 'label', 'text']));
        shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-01-${attempt}-${k}a`));
        if (rec) { await rt.clickAt(page, rec.x, rec.y, 1500); }
        c.that(`${k}: click on Record stays on the approval`, (await hash(page)) === `#/approvals/${id}`, await hash(page));
        if (cus) { await rt.clickAt(page, cus.x, cus.y, 1500); }
        c.that(`${k}: click on Customer stays on the approval`, (await hash(page)) === `#/approvals/${id}`, await hash(page));
        c.that(`${k}: still on Approval #${id}`, await rt.exists(page, new RegExp(`^Approval #${id}`)), (await allText(page)).slice(0, 300));
      } catch (e) {
        c.that(`${k}: UI steps completed`, false, String(e.message || e).slice(0, 500));
      } finally {
        shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-01-${attempt}-${k}b`));
        c.that(`${k}: no page exception`, app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        await app.close();
      }
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shots[0], evidence: `${f.fixture}; shots ${shots.join(', ')}`, codeRef: `${DETAIL}:75-80,197-212` };
  });

  await tcase({ id: 'APP-R6-UI-02', feature: 'Links stay for a viewer who can open the record (ASO-R5-1 counterpart, RGN-R5-1)', kind: 'UI', prd: 'B2',
    title: 'The North reader on the same moved changes sees no notice, "Open INV-…" on the approved one, and working Record/Customer links (the Record opens the invoice, not "does not exist")',
    steps: 'after APP-R6-UI-01: as nico open /approvals/{A}; check no notice, "Open …" button, Record is a button; tap Record -> #/invoices/{invA}; open /approvals/{B}; tap Customer -> #/customers/{id}',
    expected: 'r5 TRIAGE F1 counterpart: links shown; Record link routes to /invoices/{id} which renders the invoice; Customer link routes to /customers/{id}' }, async (attempt) => {
    const c = checks();
    const f = w.r6ui;
    if (!f) return { ok: false, actual: 'APP-R6-UI-01 did not run' };
    const shots = [];
    const app = await openAs('nico', `/approvals/${f.A}`);
    const page = app.page;
    try {
      await waitText(page, new RegExp(`^Approval #${f.A}`), 10000);
      const t = await allText(page);
      c.that('A: no no-access notice', !t.includes(NO_ACCESS), t.slice(0, 400));
      c.that('A: "Open …" button shown', await rt.exists(page, new RegExp(`^Open ${f.invA.invoiceNumber}`)), t.slice(0, 500));
      const rec = await nodeFor(page, new RegExp(`^${f.invA.invoiceNumber}$`));
      c.that('A: Record is a link (button)', rec && rec.role === 'button', rec && pick(rec, ['role', 'label']));
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-02-${attempt}-a`));
      if (rec) await rt.clickAt(page, rec.x, rec.y, 3000);
      c.that('A: Record link -> #/invoices/{id}', (await hash(page)) === `#/invoices/${f.invA.id}`, await hash(page));
      const t2 = await allText(page);
      c.that('A: invoice page renders (not "does not exist")', t2.includes(f.invA.invoiceNumber) && !/does not exist/i.test(t2), t2.slice(0, 400));
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-02-${attempt}-b`));
      await rt.go(page, `/approvals/${f.B}`, 4000);
      await waitText(page, new RegExp(`^Approval #${f.B}`), 10000);
      const cus = await nodeFor(page, new RegExp(f.cu.name));
      c.that('B: Customer is a link (button)', cus && cus.role === 'button', cus && pick(cus, ['role', 'label']));
      if (cus) await rt.clickAt(page, cus.x, cus.y, 3000);
      c.that('B: Customer link -> #/customers/{id}', (await hash(page)) === `#/customers/${f.cu.id}`, await hash(page));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-02-${attempt}-c`));
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 3));
      await app.close();
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shots[0], evidence: `${f.fixture}; shots ${shots.join(', ')}`, codeRef: DETAIL };
  });

  await tcase({ id: 'APP-R6-UI-03', feature: 'History link to a moved change (RGN-R5-1 / REG-155 replay)', kind: 'UI', prd: 'B2',
    title: 'The North reader opens the moved invoice\'s History tab and follows "Approval #N" to the change, which shows with its values (not "That change does not exist")',
    steps: 'after APP-R6-UI-01: as nico open /invoices/{invB}?tab=history (or tap History); find "Approval #{B}"; click it',
    expected: 'FIXLOG r5 B1 REG-155 replay: lands on #/approvals/{B} showing Approval #{B}, Closed, Net 60; no not-found message' }, async (attempt) => {
    const c = checks();
    const f = w.r6ui;
    if (!f) return { ok: false, actual: 'APP-R6-UI-01 did not run' };
    const shots = [];
    const app = await openAs('nico', `/invoices/${f.invB.id}`);
    const page = app.page;
    try {
      await waitText(page, new RegExp(f.invB.invoiceNumber), 10000);
      await rt.tap(page, /^History/, { wait: 3000 });
      let n = await waitText(page, new RegExp(`Approval #${f.B}\\b`), 8000);
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-03-${attempt}-a`));
      c.that('History shows "Approval #B"', !!n, (await allText(page)).slice(0, 800));
      if (n) {
        // The link may sit inside a merged row node: click the text's own node when it is separate, else the row.
        // The History row is one merged group node (label = title/status/date, text = "Approval #N"); the link sits
        // ~90 px from the row's left edge on its second line (read from the screenshot), so click there.
        const own = (await rt.semantics(page)).find((x) => (x.text || '').trim() === `Approval #${f.B}` || (x.label || '').trim() === `Approval #${f.B}`);
        if (own && own.role === 'group') await rt.clickAt(page, own.x - own.w / 2 + 90, own.y - own.h / 2 + 46, 3000);
        else if (own) await rt.clickAt(page, own.x, own.y, 3000);
        else await rt.tap(page, new RegExp(`Approval #${f.B}\\b`), { wait: 3000 });
        let h = await hash(page);
        c.that('link -> #/approvals/{B}', h.startsWith(`#/approvals/${f.B}`), h);
        const t = await allText(page);
        c.that('change shows (Approval #B, Net 60), not "does not exist"', new RegExp(`Approval #${f.B}`).test(t) && /Net 60/.test(t) && !/does not exist/i.test(t), t.slice(0, 500));
      }
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-03-${attempt}-b`));
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      await app.close();
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shots[shots.length - 1], evidence: `${f.fixture}; shots ${shots.join(', ')}`, codeRef: `${SCOPE} isVisible` };
  });

  await tcase({ id: 'APP-R6-UI-04', feature: 'Approvals list of the new region\'s reader (RGN-R5-1)', kind: 'UI', prd: 'B1',
    title: 'The North reader\'s Approvals page lists the moved customer\'s closed changes; the South-only reader\'s does not',
    steps: 'after APP-R6-UI-01: as nico open /approvals (status filter default) and look for the customer name; as vic the same',
    expected: 'r5 TRIAGE userDecision: nico\'s list has rows for the customer (all statuses view); vic\'s has none' }, async (attempt) => {
    const c = checks();
    const f = w.r6ui;
    if (!f) return { ok: false, actual: 'APP-R6-UI-01 did not run' };
    const shots = [];
    for (const [u, want] of [['nico', true], ['vic', false]]) {
      const app = await openAs(u, '/approvals');
      const page = app.page;
      try {
        await page.waitForTimeout(2500);
        let t = await allText(page);
        // The page may open on "Waiting"; switch to all statuses when there is a tab or chip for it.
        if (want && !t.includes(f.cu.name)) {
          for (const m of [/^All I can see$/, /^All$/, /^All changes/]) {
            if (await rt.exists(page, m)) { await rt.tap(page, m, { wait: 2500 }); break; }
          }
          t = await allText(page);
        }
        const req = app.requests.filter((r) => /\/api\/approvals(\?|$)/.test(r.url || r)).slice(-1)[0];
        c.that(`${u}: customer ${want ? 'listed' : 'not listed'}`, t.includes(f.cu.name) === want, `${t.slice(0, 600)} | last request ${JSON.stringify(req).slice(0, 300)}`);
      } catch (e) {
        c.that(`${u}: UI steps completed`, false, String(e.message || e).slice(0, 500));
      } finally {
        shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-04-${attempt}-${u}`));
        c.that(`${u}: no page exception`, app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        await app.close();
      }
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shots[0], evidence: `${f.fixture}; shots ${shots.join(', ')}`, codeRef: `${SCOPE} forList` };
  });
}

/** After r5.asOf (which moved w.r5asof's customer to North now, after ageWorld(10)). */
async function asOf(w, R) {
  const tcase = H.caseRunner(R);
  const T = w.tokens;
  await tcase({ id: 'APP-R6-10', feature: 'As-of vs live for the new region\'s reader (RGN-R5-1)', kind: 'API', prd: 'B3',
    title: 'For the customer that moved South -> North today: live, nico opens every change and lists them; as of before the move she gets 404 and none in her as-of list; the as-of list for vic has them',
    steps: 'after APP-R5-10: for each pc GET as nico live and ?asOf=day(-2); list as nico live and as-of; list as vic as-of',
    expected: 'r5 TRIAGE userDecision: live nico 200 (CLOSED, blockedReason null) + listed; as of the day before the move nico 404 + not listed (record was in South then); vic as-of lists them' }, async () => {
    const c = checks();
    const s = w.r5asof;
    if (!s) return { ok: false, actual: 'r5.prepAsOf did not run' };
    const AT = rt.day(-2);
    const pcs = Object.entries(s.ids).filter(([, v]) => v);
    for (const [k, id] of pcs) {
      const g = await rt.approvals.get(T.nico, id);
      c.that(`${k}: nico live 200 CLOSED reason null`, g.status === 200 && g.json?.status === 'CLOSED' && g.json?.blockedReason == null, `${g.status} ${JSON.stringify(flags(g.json))}`);
      const a = await rt.approvals.get(T.nico, id, { asOf: AT });
      c.that(`${k}: nico as-of 404`, a.status === 404, a.status);
    }
    const nl = await listIds(T.nico);
    c.that('nico live list has all', pcs.every(([, id]) => nl.ids.includes(id)), JSON.stringify(nl.ids));
    const na = await listIds(T.nico, {}, { asOf: AT });
    c.that('nico as-of list has none', na.status === 200 && pcs.every(([, id]) => !na.ids.includes(id)), `${na.status} ${JSON.stringify(na.ids)}`);
    const va = await listIds(T.vic, {}, { asOf: AT });
    c.that('vic as-of list has all', va.status === 200 && pcs.every(([, id]) => va.ids.includes(id)), `${va.status} ${JSON.stringify(va.ids)}`);
    return { ok: c.ok, actual: c.summary(), evidence: s.fixture, codeRef: `${SCOPE} recordRegion/forList` };
  }, { retry: false });

  await tcase({ id: 'APP-R6-UI-10', feature: 'Historical view keeps its links (ASO-R5-1 counterpart)', kind: 'UI', prd: 'B3',
    title: 'The South-only approver, live, sees the moved terms change with plain-text Record; as of before the move she sees "Read-only in the as-of view" and the Record is a link again',
    steps: 'after APP-R5-10: as sal open /approvals/{T}; check notice + Record role; choose as-of day(-2); check "Read-only in the as-of view", Record role',
    expected: 'r5 FIXLOG F1: live no-access -> plain text; historical read-only -> links behave as before (button)' }, async (attempt) => {
    const c = checks();
    const s = w.r5asof;
    if (!s) return { ok: false, actual: 'r5.prepAsOf did not run' };
    const id = s.ids.T;
    const shots = [];
    const app = await openAs('sal', `/approvals/${id}`);
    const page = app.page;
    try {
      c.that('live: notice', !!(await waitText(page, NO_ACCESS, 10000)), (await allText(page)).slice(0, 400));
      const rl = await nodeFor(page, new RegExp(`^${s.inv.invoiceNumber}$`));
      c.that('live: Record plain text', rl && rl.role !== 'button', rl && pick(rl, ['role', 'label']));
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-10-${attempt}-live`));
      await rt.chooseAsOf(page, rt.day(-2));
      await page.waitForTimeout(2500);
      c.that('as-of: read-only notice', !!(await waitText(page, RO, 10000)), (await allText(page)).slice(0, 400));
      const ra = await nodeFor(page, new RegExp(`^${s.inv.invoiceNumber}$`));
      c.that('as-of: Record is a link', ra && ra.role === 'button', ra && pick(ra, ['role', 'label']));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 500));
    } finally {
      shots.push(await rt.shot(page, R.shotsDir, `APP-R6-UI-10-${attempt}-asof`));
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      await app.close();
    }
    return { ok: c.ok, actual: c.summary(), screenshot: shots[shots.length - 1], evidence: `${s.fixture}; shots ${shots.join(', ')}`, codeRef: DETAIL };
  });
}

module.exports = { live, liveUi, asOf };
