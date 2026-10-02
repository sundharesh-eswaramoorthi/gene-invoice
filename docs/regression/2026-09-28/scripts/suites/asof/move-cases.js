// Round 5 add-on (one step around RGN-R4-1, the r4 backend fix to PendingChangeScope.recordRegion):
// approvals outstanding on a South customer that is moved to North AFTER T, read live and as of T.
// MUST run last: it calls ageWorld() again, which moves the main scenario's "then" out of day(-2).
//
// Sub-scenario (built on the area database after every other case has run):
//   users  sofia APPROVE/ALL South only; vic READ/ALL South only; nate READ/ALL North only (role E2E_REGIONAL)
//   s3     a South customer (Collection POC sam; invoice Sales POC sam); invoice M 6000 (below the 10000 create limit)
//   pcM    admin's held cancel of M (6000 > INVOICE_CANCEL 5000)            -> record-bound change (entityId set)
//   pcP    admin's held payment of 5600 on s3 (> PAYMENT_RECORD 5000)     -> create change (entityId null; judged by the customer)
//   ageWorld(3); then admin moves s3 South -> North (closes pcM, pcP).
const rt = require('../../lib.js');

const short = (o, n = 400) => String((typeof o === 'string' ? o : JSON.stringify(o)) ?? o).slice(0, n);
const ids = (rows) => (rows || []).map((r) => r.id).sort((a, b) => a - b);
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const label = (iso) => { const [y, m, d] = iso.split('-').map(Number); return `${d} ${MONTHS[m - 1]} ${y}`; };
const LOST = 'You no longer have access to this record';
const RO = 'Read-only in the as-of view';

async function build(S) {
  const A = S.tokens.admin;
  const R = S.regions;
  const tag = `${S.tag}m${Date.now().toString(36).slice(-4)}`;
  const mk = async (prefix, fullName, grants) => {
    const u = await rt.createStaff(A, 'E2E_REGIONAL', prefix, { fullName, grants });
    return { id: u.id, username: u.username, token: await rt.login(u.username) };
  };
  const M = { tag };
  M.sofia = await mk('sofia', 'Sofia SouthApprover', [{ regionId: R.SOUTH.id, accessLevel: 'APPROVE', recordScope: 'ALL' }]);
  M.vic = await mk('vic', 'Vic SouthViewer', [{ regionId: R.SOUTH.id, accessLevel: 'READ', recordScope: 'ALL' }]);
  M.nate = await mk('nate', 'Nate NorthViewer', [{ regionId: R.NORTH.id, accessLevel: 'READ', recordScope: 'ALL' }]);
  const s3 = await rt.createCustomer(A, { name: `South S3 ${tag}`, regionId: R.SOUTH.id, paymentTerm: 'NET_30' });
  M.s3 = { id: s3.id, name: s3.name };
  // (Sales POC is per invoice: salesPocUserId on the invoice; a customer SALES seat is refused with 400.)
  await rt.addCustomerPoc(A, s3.id, 'COLLECTION', S.users.sam.id);
  const inv = await rt.createInvoice(A, { customerId: s3.id, amount: 6000, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: S.users.sam.id });
  rt.need(inv, 'invoice M', [200]);
  M.invoiceM = { id: inv.json.id, number: inv.json.invoiceNumber };
  const cx = await rt.cancelInvoice(A, M.invoiceM.id);
  if (!cx.held) throw new Error(`cancel of M not held: ${cx.status} ${cx.text.slice(0, 300)}`);
  M.pcM = cx.pendingChange.id;
  // r6: pcA = admin's held payment 5200 on s3, approved by sofia before the move -> an APPROVED change whose
  // result record (the payment) moves to North with the customer.
  const pa = await rt.createPayment(A, { customerId: s3.id, amount: 5200, collectionPocUserId: S.users.sam.id });
  if (!pa.held) throw new Error(`payment 5200 on s3 not held: ${pa.status} ${pa.text.slice(0, 300)}`);
  M.pcA = pa.pendingChange.id;
  const ap = await rt.approvals.approve(M.sofia.token, M.pcA, 'r6 approve before move');
  rt.need(ap, 'sofia approves pcA', [200]);
  const apd = await rt.approvals.get(A, M.pcA);
  M.pcAStatus = apd.json && apd.json.status;
  M.pcARecord = apd.json && { entityLabel: apd.json.entityLabel, result: apd.json.result, status: apd.json.status };
  const py = await rt.createPayment(A, { customerId: s3.id, amount: 5600, collectionPocUserId: S.users.sam.id });
  if (!py.held) throw new Error(`payment 5600 on s3 not held: ${py.status} ${py.text.slice(0, 300)}`);
  M.pcP = py.pendingChange.id;
  // Before the move (and before ageing): what each caller sees.
  M.before = {};
  for (const w of ['sofia', 'vic']) {
    M.before[w] = {};
    for (const k of ['pcM', 'pcP']) {
      const r = await rt.approvals.get(M[w].token, M[k]);
      const j = r.json || {};
      M.before[w][k] = { status: r.status, hasCurrent: j.current != null, blockedReason: j.blockedReason, canApprove: j.canApprove };
    }
  }
  rt.ageWorld(3);
  const mv = await rt.moveCustomerRegion(A, s3.id, R.NORTH.id, 'r5 move with pending changes');
  M.move = { status: mv.status, body: short(mv.json || mv.text, 500) };
  rt.need(mv, 'move s3 to NORTH');
  return M;
}

module.exports = async function moveCases({ S, check, shotsDir }) {
  const A = S.tokens.admin;
  const T = rt.day(-2); // after the 3-day ageing: s3, M, pcM, pcP existed; the move is today
  const R = S.regions;
  let M;
  try {
    M = await build(S);
    S.move5 = { ...M, sofia: { id: M.sofia.id, username: M.sofia.username }, vic: { id: M.vic.id, username: M.vic.username }, nate: { id: M.nate.id, username: M.nate.username } };
  } catch (e) {
    await check({ id: 'ASO-API-M0', feature: 'Approvals across a region move', prd: 'B3', title: 'Round 5 sub-scenario setup (South customer with two held changes, aged, moved to North)',
      steps: 'see move-cases.js header', expected: 'setup succeeds' }, async () => { throw e; });
    return;
  }
  const tok = { admin: A, sofia: M.sofia.token, vic: M.vic.token, nate: M.nate.token };
  const pre = `Round 5 sub-scenario: South customer s3 (${M.s3.id}); invoice M ${M.invoiceM.number} (${M.invoiceM.id}); pcM = admin's held cancel of M (id ${M.pcM}); pcP = admin's held payment 5600 on s3 (id ${M.pcP}); sofia APPROVE South only, vic READ South only, nate READ North only; ageWorld(3); admin moves s3 South->North today (${M.move.status})`;

  await check({ id: 'ASO-API-M1', feature: 'Approvals across a region move (RGN-R4-1 retest)', prd: 'B2',
    title: 'RGN-R4-1 retest (live): after the customer moves South->North, a South-only approver sees the closed changes without now-values; a South-only viewer no longer sees them; the admin (North) does',
    steps: `${pre}. GET /api/approvals/{pcM|pcP} as sofia, vic, admin; GET /api/invoices/{M} as sofia`,
    expected: 'r4 TRIAGE B1 + userDecision: before the move sofia sees current (pcM; a held create has no current by design) (blockedReason null, canApprove); after: sofia invoice 404; approval 200 CLOSED, current null, every changes[].current/currentText null, blockedReason "You no longer have access to this record"; vic 404; admin 200 with current',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:122-155' }, async () => {
    const out = []; let ok = true;
    const b = M.before;
    // A held create (pcP, a payment not made yet) has no "record now": PaymentChangeApplier.currentView returns null by
    // design, so `current` is only asserted for the record-bound pcM (harness fix, r5).
    const needCur = (k) => k === 'pcM';
    const beforeOk = ['pcM', 'pcP'].every((k) => b.sofia[k].status === 200 && (!needCur(k) || b.sofia[k].hasCurrent) && b.sofia[k].blockedReason == null && b.sofia[k].canApprove === true
      && b.vic[k].status === 200 && (!needCur(k) || b.vic[k].hasCurrent));
    ok = ok && beforeOk;
    out.push(`before move: ${short(b, 500)}`);
    const inv = await rt.api('GET', `/api/invoices/${M.invoiceM.id}`, { token: tok.sofia });
    ok = ok && inv.status === 404;
    out.push(`sofia invoice M -> ${inv.status}`);
    for (const k of ['pcM', 'pcP']) {
      const s = await rt.approvals.get(tok.sofia, M[k]); const sj = s.json || {};
      const chg = sj.changes || [];
      const sOk = s.status === 200 && sj.status === 'CLOSED' && sj.current == null && chg.every((c) => c.current == null && c.currentText == null) && sj.blockedReason === LOST
        && !sj.canApprove && !sj.canReject;
      const v = await rt.approvals.get(tok.vic, M[k]);
      const a = await rt.approvals.get(tok.admin, M[k]); const aj = a.json || {};
      const aOk = a.status === 200 && aj.status === 'CLOSED' && (!needCur(k) || aj.current != null) && aj.blockedReason == null;
      ok = ok && sOk && v.status === 404 && aOk;
      out.push(`${k}: sofia ${s.status} ${short({ st: sj.status, cur: sj.current != null, chgCur: chg.filter((c) => c.current != null || c.currentText != null).map((c) => c.field), b: sj.blockedReason, a: sj.canApprove })}; vic ${v.status}; admin ${a.status} ${short({ st: aj.status, cur: aj.current != null, b: aj.blockedReason })}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });

  await check({ id: 'ASO-API-M2', feature: 'Approvals across a region move, as of a date', prd: 'B3',
    title: 'As of T (before the move) the changes read PENDING in South: the South-only approver and the South-only viewer both open them (read only); the North-only viewer does not',
    steps: `${pre}. GET /api/approvals/{pcM|pcP}?asOf=${T} as sofia, vic, nate, admin`,
    expected: 'PRD B3 "which region a record belonged to then and which approvals were outstanding"; r4 TRIAGE B1: as of the moment before the move sofia 200 "Read-only in the as-of view", current null; vic 200 (record in South then); statusAt PENDING; nate 404 (nothing in South)',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:145-155' }, async () => {
    const out = []; let ok = true;
    for (const k of ['pcM', 'pcP']) {
      for (const w of ['sofia', 'vic', 'admin']) {
        const r = await rt.approvals.get(tok[w], M[k], { asOf: T }); const j = r.json || {};
        const good = r.status === 200 && j.statusAt === 'PENDING' && j.blockedReason === RO && j.current == null && !j.canApprove && !j.canReject && !j.canWithdraw && j.decidedAt == null;
        ok = ok && good;
        out.push(`${k} ${w}: ${r.status} ${short({ st: j.status, at: j.statusAt, b: j.blockedReason, cur: j.current != null, dec: j.decidedAt })}`);
      }
      const n = await rt.approvals.get(tok.nate, M[k], { asOf: T });
      ok = ok && n.status === 404;
      out.push(`${k} nate: ${n.status}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });

  await check({ id: 'ASO-API-M3', feature: 'Approvals across a region move, as of a date', prd: 'B3',
    title: 'Approvals list, summary and for-record as of T show the two changes outstanding in South for the South-only viewer and approver; live the viewer sees neither',
    steps: `${pre}. vic/sofia GET /api/approvals?asOf=${T}&filter=customerId:eq:s3 (and regionIds=SOUTH), /api/approvals/summary?asOf=${T}&regionIds=SOUTH, /api/approvals/for-record?entityType=INVOICE&entityId=M&asOf=${T}; live the same`,
    expected: 'PRD B3 "which approvals were outstanding"; DESIGN §5.11.9 forList record subqueries on each record\'s region at T: as of T both rows (statusAt PENDING) for vic and sofia, also under regionIds=SOUTH and not under NORTH; for-record lists pcM; live vic sees neither',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:174-' }, async () => {
    const out = []; let ok = true;
    const want = [M.pcM, M.pcP].sort((a, b) => a - b);
    // r6: only pcM/pcP (the approved pcA added in r6 is covered by M5-M7).
    const mine = (rows) => ids((rows || []).filter((r) => r.customerId === M.s3.id && (r.id === M.pcM || r.id === M.pcP)));
    for (const w of ['vic', 'sofia']) {
      const t = await rt.approvals.list(tok[w], {}, { asOf: T }); const tj = t.json || {};
      const ts = await rt.approvals.list(tok[w], {}, { asOf: T, regionIds: [R.SOUTH.id] });
      const tn = await rt.approvals.list(tok[w], {}, { asOf: T, regionIds: [R.NORTH.id] });
      const tRows = (tj.content || []).filter((r) => r.customerId === M.s3.id);
      const fr = await rt.approvals.forRecord(tok[w], 'INVOICE', M.invoiceM.id, { asOf: T });
      const frRows = Array.isArray(fr.json) ? fr.json : (fr.json && (fr.json.content || fr.json.items)) || [];
      const good = t.status === 200 && JSON.stringify(mine(tj.content)) === JSON.stringify(want) && tRows.every((r) => r.statusAt === 'PENDING')
        && JSON.stringify(mine(ts.json && ts.json.content)) === JSON.stringify(want) && mine(tn.json && tn.json.content).length === 0
        && fr.status === 200 && frRows.some((r) => r.id === M.pcM);
      ok = ok && good;
      out.push(`${w} asOf: list ${t.status} s3 rows=${JSON.stringify(mine(tj.content))} statusAt=${tRows.map((r) => r.statusAt)} | SOUTH ${ts.status} ${JSON.stringify(mine(ts.json && ts.json.content))} | NORTH ${tn.status} ${JSON.stringify(mine(tn.json && tn.json.content))} (${tn.status === 200 ? '' : short(tn.json)}) | for-record ${fr.status} ${short(frRows.map((r) => r.id))}`);
    }
    const sv = await rt.approvals.summary(tok.vic, { asOf: T, regionIds: [R.SOUTH.id] });
    out.push(`vic summary asOf SOUTH ${sv.status} ${short(sv.json, 200)}`);
    ok = ok && sv.status === 200 && Number(sv.json.pendingCount) >= 2;
    const l = await rt.approvals.list(tok.vic, { filter: 'status:in:PENDING,CLOSED' });
    const l2 = await rt.approvals.list(tok.vic, {});
    const frl = await rt.approvals.forRecord(tok.vic, 'INVOICE', M.invoiceM.id);
    const lRows = mine((l.json && l.json.content) || []).concat(mine((l2.json && l2.json.content) || []));
    ok = ok && lRows.length === 0 && (frl.status === 404 || frl.status === 403 || (frl.status === 200 && !(Array.isArray(frl.json) ? frl.json : (frl.json.content || [])).some((r) => r.id === M.pcM)));
    out.push(`vic live: list ${l.status}/${l2.status} s3 rows=${JSON.stringify(lRows)}; for-record ${frl.status}`);
    return { ok, actual: out.join(' ;; ') };
  });

  await check({ id: 'ASO-API-M4', feature: 'Records across a region move, as of a date', prd: 'B3',
    title: 'Invoice M and customer s3: as of T the South viewer opens them (region South, read only) and the North viewer does not; live the reverse',
    steps: `${pre}. GET /api/invoices/{M} and /api/customers/{s3} as vic and nate, with asOf=${T} and live; GET /api/invoices?asOf=${T} as vic (filter customerId)`,
    expected: 'PRD B3 "including which region a record belonged to then": as of T vic 200 regionId=SOUTH, nate 404; live vic 404, nate 200 regionId=NORTH; invoice M is flagged pendingApproval as of T (and reads PARTIALLY_PAID: the r6 pcA payment was allocated to it)',
    codeRef: '' }, async () => {
    const out = []; let ok = true;
    for (const [p, what] of [[`/api/invoices/${M.invoiceM.id}`, 'invoice'], [`/api/customers/${M.s3.id}`, 'customer']]) {
      const vt = await rt.api('GET', p, { token: tok.vic, query: { asOf: T } });
      const nt = await rt.api('GET', p, { token: tok.nate, query: { asOf: T } });
      const vl = await rt.api('GET', p, { token: tok.vic });
      const nl = await rt.api('GET', p, { token: tok.nate });
      const good = vt.status === 200 && vt.json.regionId === R.SOUTH.id && nt.status === 404 && vl.status === 404 && nl.status === 200 && nl.json.regionId === R.NORTH.id;
      ok = ok && good;
      out.push(`${what}: vic asOf ${vt.status} region=${vt.json && vt.json.regionId}; nate asOf ${nt.status}; vic live ${vl.status}; nate live ${nl.status} region=${nl.json && nl.json.regionId}`);
    }
    const lt = await rt.list(tok.vic, '/api/invoices', { filter: `customerId:eq:${M.s3.id}` }, { asOf: T });
    const row = (lt.json.content || []).find((r) => r.id === M.invoiceM.id);
    // r6 harness fix: the approved pcA payment (5200, no invoiceIds) is auto-allocated to M (6000), so M reads
    // PARTIALLY_PAID (balance 800) from then on; before r6 it was UNPAID.
    ok = ok && lt.status === 200 && row && row.status === 'PARTIALLY_PAID' && row.pendingApproval === true && row.regionId === R.SOUTH.id;
    out.push(`vic invoice list asOf ${lt.status} M=${short(row && { status: row.status, pendingApproval: row.pendingApproval, regionId: row.regionId })}`);
    return { ok, actual: out.join(' ;; ') };
  });

  // UI: the approval screen as the South viewer (as of T and live) and the South approver (live).
  const T_LBL = label(T);
  let n = 0;
  const shot = async (page, id) => rt.shot(page, shotsDir, `${id}-${++n}`);
  await check({ kind: 'UI', id: 'ASO-UI-M1', feature: 'Approvals across a region move', prd: 'B3',
    title: 'The approval screen: vic (South viewer) opens pcM as of T ("Awaiting approval", read only) but not live; sofia (South approver) live sees "You no longer have access to this record" and no Approve',
    steps: `${pre}. vic logs in, chooseAsOf(${T}), /approvals/{pcM}; Back to today; /approvals/{pcM}. sofia logs in, /approvals/{pcM}`,
    expected: 'as of T: "Awaiting approval", "Read-only in the as-of view", no Approve/Reject; live vic: not found / no access message; sofia live: "You no longer have access to this record", no Approve button' }, async () => {
    const sps = []; const out = []; let ok = true;
    let app = await rt.openApp({ username: M.vic.username, password: 'Passw0rd!' });
    try {
      const page = app.page;
      await rt.chooseAsOf(page, T);
      await rt.go(page, `/approvals/${M.pcM}`);
      await rt.sleep(1500);
      const tt = await rt.screenText(page); sps.push(await shot(page, 'ASO-UI-M1'));
      const tOk = tt.some((x) => /Awaiting approval/.test(x)) && tt.some((x) => x.includes(RO)) && !tt.some((x) => /^Approve$/.test(x));
      await rt.chooseAsOf(page, null);
      await rt.go(page, `/approvals/${M.pcM}`);
      await rt.sleep(1500);
      const lt = await rt.screenText(page); sps.push(await shot(page, 'ASO-UI-M1'));
      const lOk = !lt.some((x) => /Awaiting approval|Cancel invoice/.test(x)) && lt.some((x) => /not found|no longer|access|could not|doesn't exist|does not exist/i.test(x));
      ok = ok && tOk && lOk;
      out.push(`vic asOf ${T_LBL}: ok=${tOk} text=${short(tt.filter((x) => /Awaiting|Read-only|Approve|Reject|Closed|South|North/.test(x)), 300)} ;; vic live: ok=${lOk} text=${short(lt.slice(0, 40), 400)}`);
    } finally { await app.close(); }
    app = await rt.openApp({ username: M.sofia.username, password: 'Passw0rd!' });
    try {
      const page = app.page;
      await rt.go(page, `/approvals/${M.pcM}`);
      await rt.sleep(1500);
      const st = await rt.screenText(page); sps.push(await shot(page, 'ASO-UI-M1'));
      const sem = await rt.semantics(page);
      const approveBtn = sem.some((x) => x.role === 'button' && /^Approve$/.test(x.label || x.text || ''));
      const sOk = st.some((x) => x.includes(LOST)) && !approveBtn;
      ok = ok && sOk;
      out.push(`sofia live: ok=${sOk} lost=${st.some((x) => x.includes(LOST))} approveBtn=${approveBtn} text=${short(st.filter((x) => /Closed|access|Approve|Reject|Current/.test(x)), 300)}`);
    } finally { await app.close(); }
    return { ok, actual: out.join(' ;; '), screenshot: sps.join(', '), evidence: `screenshots ${sps.join(', ')}` };
  });
  // ---------------------------------------------------------------- round 6 additions
  // ASO-R5-1 retest (frontend r5 F1) and one step around it and around the r5 backend B1 (record-region visibility).
  const pcARes = (M.pcARecord && M.pcARecord.result) || {};
  const pre6 = `${pre}; r6: pcA = admin's held payment 5200 on s3 approved by sofia before the ageing (id ${M.pcA}, status ${M.pcAStatus}, result ${short(pcARes)})`;

  await check({ id: 'ASO-API-M5', feature: 'Approvals across a region move (RGN-R5-1 retest, one step around)', prd: 'B2',
    title: 'After the move, a North-only viewer who can open the moved records now sees the closed/approved changes on them, with now-values; as of T (records in South) she does not',
    steps: `${pre6}. nate GET /api/approvals/{pcM|pcP|pcA} live and ?asOf=${T}; nate GET /api/approvals (no regionIds, regionIds=NORTH, filter=id:eq:pcM) live and asOf=${T}`,
    expected: 'r5 TRIAGE B1 / DESIGN §5.11.9 VISIBLE record branch (record judged in its own region now): nate live 200 for pcM (CLOSED, current filled, blockedReason null, canApprove false), pcP (CLOSED), pcA (APPROVED, blockedReason null); list contains all three with and without regionIds=NORTH; filter id:eq:pcM -> exactly [pcM]; Brief.regionId stays SOUTH (the region the change was asked in); as of T 404 and absent from the list',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java (isVisible/forList record branches)' }, async () => {
    const out = []; let ok = true;
    const want = [M.pcM, M.pcP, M.pcA].sort((a, b) => a - b);
    const exp = { pcM: 'CLOSED', pcP: 'CLOSED', pcA: 'APPROVED' };
    for (const k of ['pcM', 'pcP', 'pcA']) {
      const r = await rt.approvals.get(tok.nate, M[k]); const j = r.json || {};
      const good = r.status === 200 && j.status === exp[k] && j.blockedReason == null && !j.canApprove && !j.canReject && (k !== 'pcM' || j.current != null);
      ok = ok && good;
      out.push(`nate ${k} live ${r.status} ${short({ st: j.status, cur: j.current != null, b: j.blockedReason, a: j.canApprove, region: j.regionName })}`);
      const t = await rt.approvals.get(tok.nate, M[k], { asOf: T });
      ok = ok && t.status === 404;
      out.push(`nate ${k} asOf ${t.status}`);
    }
    const mineRows = (j) => ((j && j.content) || []).filter((r) => r.customerId === M.s3.id);
    const l = await rt.approvals.list(tok.nate, {});
    const ln = await rt.approvals.list(tok.nate, {}, { regionIds: [R.NORTH.id] });
    const lf = await rt.approvals.list(tok.nate, { filter: `id:eq:${M.pcM}` });
    const lt = await rt.approvals.list(tok.nate, {}, { asOf: T });
    const lRows = mineRows(l.json);
    ok = ok && l.status === 200 && JSON.stringify(ids(lRows)) === JSON.stringify(want) && lRows.every((r) => r.regionId === R.SOUTH.id)
      && ln.status === 200 && JSON.stringify(ids(mineRows(ln.json))) === JSON.stringify(want)
      && lf.status === 200 && JSON.stringify(ids(lf.json.content)) === JSON.stringify([M.pcM])
      && lt.status === 200 && mineRows(lt.json).length === 0;
    out.push(`nate list live ${l.status} s3 rows=${JSON.stringify(ids(lRows))} regionIds=${JSON.stringify(lRows.map((r) => r.regionId))} (SOUTH=${R.SOUTH.id}); regionIds=NORTH ${ln.status} ${JSON.stringify(ids(mineRows(ln.json)))}; id:eq ${lf.status} ${JSON.stringify(ids(lf.json && lf.json.content))}; asOf ${lt.status} ${JSON.stringify(ids(mineRows(lt.json)))}`);
    return { ok, actual: out.join(' ;; ') };
  });

  await check({ id: 'ASO-API-M6', feature: 'Approvals across a region move: region narrowing live and as of T (one step around RGN-R5-1)', prd: 'B3',
    title: 'The regions asked about narrow the approvals list by approver region or record region live, and by the record\'s region THEN as of T; the South viewer vic stays out live',
    steps: `${pre6}. admin/sam/vic/sofia GET /api/approvals with regionIds=SOUTH or NORTH, live and asOf=${T}`,
    expected: 'r5 FIXLOG B1: live admin regionIds=SOUTH has pcM,pcP,pcA (approver branch) and regionIds=NORTH has them (record branch); as of T (list = outstanding then, D8) admin regionIds=SOUTH has pcM,pcP and regionIds=NORTH has none (the records were in South then); sam (APPROVE South, MANAGE North) sees them under each; sofia live regionIds=SOUTH has them (approver keeps them); vic live has none',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java forList' }, async () => {
    const out = []; let ok = true;
    const want = JSON.stringify([M.pcM, M.pcP, M.pcA].sort((a, b) => a - b));
    const s3ids = (r) => JSON.stringify(ids(((r.json && r.json.content) || []).filter((x) => x.customerId === M.s3.id)));
    const cases = [
      ['admin', { regionIds: [R.SOUTH.id] }, want], ['admin', { regionIds: [R.NORTH.id] }, want],
      // r6 harness fix: as of a date the list holds only the changes OUTSTANDING then (D8, PendingChangeScope:51/273),
      // so the approved-before-T pcA is not in it.
      ['admin', { regionIds: [R.SOUTH.id], asOf: T }, JSON.stringify([M.pcM, M.pcP].sort((a, b) => a - b))], ['admin', { regionIds: [R.NORTH.id], asOf: T }, '[]'],
      ['sam', { regionIds: [R.SOUTH.id] }, want], ['sam', { regionIds: [R.NORTH.id] }, want],
      ['sofia', { regionIds: [R.SOUTH.id] }, want], ['vic', {}, '[]'],
    ];
    const tk = { ...tok, sam: S.tokens.sam };
    for (const [w, scope, exp] of cases) {
      const r = await rt.approvals.list(tk[w], {}, scope);
      const got = s3ids(r);
      const good = r.status === 200 && got === exp;
      ok = ok && good;
      out.push(`${w} ${short(scope)} -> ${r.status} ${got}${good ? '' : ` (want ${exp})`}`);
    }
    return { ok, actual: out.join(' ;; ') };
  });

  await check({ id: 'ASO-API-M7', feature: 'Approved change whose result moved away', prd: 'B2',
    title: 'An APPROVED change whose payment moved to North: the South-only approver still opens it (APPROVED, no now-values, "You no longer have access to this record") but not the payment; nate opens both',
    steps: `${pre6}. sofia GET /api/approvals/{pcA}, GET /api/payments/{result}; nate the same`,
    expected: 'r4 TRIAGE userDecision (approver keeps the change, now-values hidden, blockedReason) and r5 B1 (the North viewer sees it)',
    codeRef: '' }, async () => {
    const out = []; let ok = true;
    const s = await rt.approvals.get(tok.sofia, M.pcA); const sj = s.json || {};
    const n = await rt.approvals.get(tok.nate, M.pcA); const nj = n.json || {};
    const rid = (sj.result && sj.result.entityId) || pcARes.entityId;
    const sp = rid ? await rt.api('GET', `/api/payments/${rid}`, { token: tok.sofia }) : { status: 'n/a' };
    const np = rid ? await rt.api('GET', `/api/payments/${rid}`, { token: tok.nate }) : { status: 'n/a' };
    ok = M.pcAStatus === 'APPROVED' && s.status === 200 && sj.status === 'APPROVED' && sj.blockedReason === LOST && sj.current == null
      && (sj.changes || []).every((c) => c.current == null && c.currentText == null)
      && n.status === 200 && nj.blockedReason == null && sp.status === 404 && np.status === 200 && (np.json || {}).regionId === R.NORTH.id;
    out.push(`sofia pcA ${s.status} ${short({ st: sj.status, b: sj.blockedReason, cur: sj.current != null, result: sj.result })}; nate pcA ${n.status} ${short({ st: nj.status, b: nj.blockedReason, result: nj.result })}; payment ${rid}: sofia ${sp.status}, nate ${np.status} region=${np.json && np.json.regionId}`);
    return { ok, actual: out.join(' ;; ') };
  });

  // UI helpers for links on the approval page.
  const isLink = (n) => n && (n.role === 'button' || n.role === 'link');
  const nodeFor = async (page, text) => {
    const sem = await rt.semantics(page);
    return sem.find((n) => (n.label || n.text || '').trim() === text) || sem.find((n) => (n.label || n.text || '').includes(text)) || null;
  };
  const hashOf = (page) => { const u = page.url(); return u.slice(u.indexOf('#')); };

  await check({ kind: 'UI', id: 'ASO-UI-M2', feature: 'Approvals across a region move (ASO-R5-1 retest)', prd: 'B2', severity: 'low',
    title: 'ASO-R5-1 retest: when the approval says "You no longer have access to this record", Record and Customer are plain text (tapping stays on the approval) and an approved change offers no "Open …"',
    steps: `${pre6}. sofia logs in; /approvals/{pcM}: find the Record (invoice number) and Customer nodes, tap and click each; /approvals/{pcA}: look for "Open …"`,
    expected: 'DESIGN §5.11.9 (SC-15) + r4 TRIAGE userDecision + r5 F1: notice shown; Record/Customer text shown but not links; tapping leaves the URL at #/approvals/{pcM}; never "That invoice does not exist."; pcA page shows no "Open …" button',
    codeRef: 'frontend/lib/features/approvals/approval_detail_screen.dart:147-152, 195-212, 76' }, async (attempt) => {
    const sps = []; const out = []; let ok = true;
    const app = await rt.openApp({ username: M.sofia.username, password: 'Passw0rd!' });
    try {
      const page = app.page;
      await rt.go(page, `/approvals/${M.pcM}`); await rt.sleep(1500);
      const st = await rt.screenText(page);
      sps.push(await rt.shot(page, shotsDir, `ASO-UI-M2-${attempt}a`));
      const recNode = await nodeFor(page, M.invoiceM.number);
      const custNode = await nodeFor(page, M.s3.name);
      const lost = st.some((x) => x.includes(LOST));
      out.push(`pcM: lost=${lost} record=${short(recNode && { role: recNode.role, label: recNode.label, text: recNode.text })} customer=${short(custNode && { role: custNode.role, label: (custNode.label || '').slice(0, 60) })}`);
      ok = ok && lost && recNode && custNode && !isLink(recNode) && !isLink(custNode);
      for (const [nm, nd] of [['record', recNode], ['customer', custNode]]) {
        if (!nd) continue;
        await rt.clickAt(page, nd.x, nd.y); await rt.sleep(800);
        const h = hashOf(page); const tx = await rt.screenText(page);
        const stay = h === `#/approvals/${M.pcM}` && !tx.some((x) => /does not exist/.test(x));
        ok = ok && stay;
        out.push(`click ${nm} -> ${h} stay=${stay}${stay ? '' : ` text=${short(tx.slice(0, 12), 200)}`}`);
        if (!stay) { sps.push(await rt.shot(page, shotsDir, `ASO-UI-M2-${attempt}-${nm}`)); await rt.go(page, `/approvals/${M.pcM}`); await rt.sleep(1200); }
      }
      await rt.go(page, `/approvals/${M.pcA}`); await rt.sleep(1500);
      const at = await rt.screenText(page);
      sps.push(await rt.shot(page, shotsDir, `ASO-UI-M2-${attempt}b`));
      const open = at.filter((x) => /^Open /.test(x));
      const aLost = at.some((x) => x.includes(LOST));
      const aCust = await nodeFor(page, M.s3.name);
      ok = ok && open.length === 0 && aLost && at.some((x) => /Approved/.test(x)) && !isLink(aCust);
      out.push(`pcA: approved=${at.some((x) => /Approved/.test(x))} lost=${aLost} open=${JSON.stringify(open)} customerLink=${isLink(aCust)}`);
    } finally { await app.close(); }
    return { ok, actual: out.join(' ;; '), screenshot: sps.join(', '), evidence: `screenshots ${sps.join(', ')}` };
  });

  await check({ kind: 'UI', id: 'ASO-UI-M3', feature: 'Approval links for viewers who can open the record (one step around ASO-R5-1)', prd: 'B2',
    title: 'Viewers who CAN open the record keep working links: nate (North, live) taps Record -> the invoice opens; nate sees "Open …" on the approved pcA and it opens the payment; vic as of T (Read-only in the as-of view) still gets a Record link that opens the invoice as of T',
    steps: `${pre6}. nate: /approvals/{pcM}, tap invoice number; /approvals/{pcA}, tap "Open …". vic: chooseAsOf(${T}), /approvals/{pcM}, tap invoice number`,
    expected: 'r5 F1 (unchanged: links when the record is visible; the as-of read-only case still links, r1 F1 pin) and r5 B1 (nate sees the change): each tap lands on the record page showing it, never "does not exist"',
    codeRef: 'frontend/lib/features/approvals/approval_detail_screen.dart' }, async (attempt) => {
    const sps = []; const out = []; let ok = true;
    let app = await rt.openApp({ username: M.nate.username, password: 'Passw0rd!' });
    try {
      const page = app.page;
      await rt.go(page, `/approvals/${M.pcM}`); await rt.sleep(1500);
      const st = await rt.screenText(page);
      sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}a`));
      const rec = await nodeFor(page, M.invoiceM.number);
      const noNotice = !st.some((x) => x.includes(LOST));
      let h = ''; let tx = [];
      if (rec) { await rt.clickAt(page, rec.x, rec.y); await rt.sleep(1500); h = hashOf(page); tx = await rt.screenText(page); }
      const recOk = noNotice && isLink(rec) && h === `#/invoices/${M.invoiceM.id}` && tx.some((x) => x.includes(M.invoiceM.number)) && !tx.some((x) => /does not exist/.test(x));
      if (!recOk) sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}a2`));
      ok = ok && recOk;
      out.push(`nate pcM: notice=${!noNotice} record=${short(rec && { role: rec.role })} -> ${h} ok=${recOk}`);
      await rt.go(page, `/approvals/${M.pcA}`); await rt.sleep(1500);
      const at = await rt.screenText(page);
      sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}b`));
      const open = at.find((x) => /^Open /.test(x));
      let h2 = ''; let tx2 = [];
      if (open) { await rt.tap(page, open); await rt.sleep(1200); h2 = hashOf(page); tx2 = await rt.screenText(page); }
      const openOk = !!open && /^#\/payments\/\d+/.test(h2) && !tx2.some((x) => /does not exist/.test(x));
      if (!openOk) sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}b2`));
      ok = ok && openOk;
      out.push(`nate pcA: open=${JSON.stringify(open)} -> ${h2} ok=${openOk}`);
    } finally { await app.close(); }
    app = await rt.openApp({ username: M.vic.username, password: 'Passw0rd!' });
    try {
      const page = app.page;
      await rt.chooseAsOf(page, T);
      await rt.go(page, `/approvals/${M.pcM}`); await rt.sleep(1500);
      const st = await rt.screenText(page);
      sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}c`));
      const rec = await nodeFor(page, M.invoiceM.number);
      let h = ''; let tx = [];
      if (rec) { await rt.clickAt(page, rec.x, rec.y); await rt.sleep(1500); h = hashOf(page); tx = await rt.screenText(page); }
      const vOk = st.some((x) => x.includes(RO)) && isLink(rec) && h === `#/invoices/${M.invoiceM.id}` && tx.some((x) => x.includes(M.invoiceM.number)) && !tx.some((x) => /does not exist/.test(x));
      if (!vOk) sps.push(await rt.shot(page, shotsDir, `ASO-UI-M3-${attempt}c2`));
      ok = ok && vOk;
      out.push(`vic asOf ${T}: ro=${st.some((x) => x.includes(RO))} record=${short(rec && { role: rec.role })} -> ${h} ok=${vOk}${vOk ? '' : ` text=${short(tx.slice(0, 15), 300)}`}`);
    } finally { await app.close(); }
    return { ok, actual: out.join(' ;; '), screenshot: sps.join(', '), evidence: `screenshots ${sps.join(', ')}` };
  });
};
