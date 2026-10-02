// Round 4 additions. Round 3 reported no automation defects; the fixes made after round 3 were
//   ASO-R3-1  approval detail as of a date resolves names from history (PendingChangeViews.changes/names),
//   RGN-R3-1  blockedReason "You no longer have access to this record" (PendingChangeViews.blockedReason),
//   RGR-R3-1  Insert placeholder goes in at the caret the field had when the menu opened (placeholder_button.dart).
// One step around each, on the automation side:
//   - the pending change an automation proposes, read by a book-less approver, a seeing approver and the rule owner;
//   - that change read as of a date (history names, statusAt, hidden decision, read-only), in the list, for-record and UI;
//   - the rule editor's placeholder buttons (task title, email subject, email body): mid-text insertion, repeated
//     insertion, and typing on after an insertion.
const rt = require('../../lib.js');
const H = require('./helpers.js');

async function heldPromise(ctx, amount, { collector } = {}) {
  const { t, w } = ctx;
  const S = w.regions.SOUTH.id;
  const cust = await H.mkCustomer(t, { regionId: S, collector: collector || w.users.sam.id, amounts: [amount] });
  const inv = cust.invoices[0];
  const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`),
    actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' } })] });
  ctx.cleanup.push(rule.id);
  const { run, effects } = await H.runAndWait(t, rule.id);
  await rt.automation.disable(t, rule.id);
  const pcId = effects[0]?.result?.type === 'PENDING_CHANGE' ? effects[0].result.id : null;
  return { cust, inv, rule, run, effects, pcId };
}

async function r4Cases(ctx) {
  const { R, t, w } = ctx;
  const S = w.regions.SOUTH.id;
  const U = w.users;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Automation and approvals', ...meta }, fn, o);

  await K({ id: 'AUT-API-160', prd: 'B2', title: 'RGN-R3-1 around automation: on a change a rule proposed, a book-less approver is told "You no longer have access to this record" yet may still decide; a seeing approver gets no reason; the rule owner gets "You requested this change"',
    steps: 'South customer (Collection POC sam) with invoice 6800; admin MANUAL rule CREATE_PROMISE Invoice.Balance -> held pc; new staff "bkap" with South APPROVE/BOOK (not a POC of the customer); GET /api/approvals/{pc} as bkap, sam and admin; bkap POST approve',
    expected: 'TRIAGE r3 RGN-R3-1 userDecision + DESIGN §5.11.9/SC-15: bkap sees the change (200), current null, blockedReason "You no longer have access to this record", canApprove/canReject true, and the approve succeeds (200, APPROVED, a promise of 6800 exists); sam: blockedReason null, canApprove true; admin (maker = rule owner): blockedReason "You requested this change", canApprove false' },
  async () => {
    const h = await heldPromise(ctx, 6800);
    if (!h.pcId) return { status: 'BLOCKED', actual: { run: h.run && [h.run.status], effects: h.effects } };
    const bk = await rt.createStaff(t, 'E2E_REGIONAL', 'bkap', { grants: [{ regionId: S, accessLevel: 'APPROVE', recordScope: 'BOOK' }] });
    const tb = await rt.login(bk.username);
    const ts = await rt.login('sam');
    const b = await rt.approvals.get(tb, h.pcId);
    const s = await rt.approvals.get(ts, h.pcId);
    const a = await rt.approvals.get(t, h.pcId);
    const inList = ((await rt.approvals.list(tb, { view: 'ALL' })).json?.content || []).some((x) => x.id === h.pcId);
    const ap = await rt.approvals.approve(tb, h.pcId, 'book-less approval');
    const proms = await H.promisesOf(t, h.cust.c.id);
    const v = (r) => r.json && { status: r.status, blockedReason: r.json.blockedReason, canApprove: r.json.canApprove, canReject: r.json.canReject, canWithdraw: r.json.canWithdraw, current: r.json.current };
    return { ok: b.status === 200 && b.json.blockedReason === 'You no longer have access to this record' && b.json.canApprove === true && b.json.canReject === true && b.json.current == null && inList
        && s.status === 200 && s.json.blockedReason == null && s.json.canApprove === true
        && a.status === 200 && a.json.blockedReason === 'You requested this change' && a.json.canApprove === false
        && ap.status === 200 && proms.length === 1 && Number(proms[0].amount) === 6800,
      actual: { pc: h.pcId, bookless: v(b), booklessListsIt: inList, sam: v(s), owner: v(a), approve: [ap.status, ap.json?.status, ap.json?.message], promises: proms.map((x) => [x.id, x.amount, x.status]) },
      evidence: `GET /api/approvals/${h.pcId} as ${bk.username} / sam / admin; POST /api/approvals/${h.pcId}/approve as ${bk.username}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:188' };
  });

  await K({ id: 'AUT-API-161', prd: 'B2', title: 'RGN-R3-1 around automation: an approver who has lost sight of the record keeps seeing the decided change with "You no longer have access to this record", and nothing to act on',
    steps: 'held automation promise (South, invoice 6900); sam rejects; GET /api/approvals/{pc} as a South APPROVE/BOOK user outside the book',
    expected: 'TRIAGE r3 RGN-R3-1 ("at any status"): 200, status REJECTED, blockedReason "You no longer have access to this record", canApprove/canReject/canWithdraw false' },
  async () => {
    const h = await heldPromise(ctx, 6900);
    if (!h.pcId) return { status: 'BLOCKED', actual: { effects: h.effects } };
    const rj = await rt.approvals.reject(await rt.login('sam'), h.pcId, 'no');
    const bk = await rt.createStaff(t, 'E2E_REGIONAL', 'bkap2', { grants: [{ regionId: S, accessLevel: 'APPROVE', recordScope: 'BOOK' }] });
    const g = await rt.approvals.get(await rt.login(bk.username), h.pcId);
    return { ok: rj.status === 200 && g.status === 200 && g.json.status === 'REJECTED' && g.json.blockedReason === 'You no longer have access to this record'
        && !g.json.canApprove && !g.json.canReject && !g.json.canWithdraw,
      actual: { reject: rj.status, get: g.status, detail: g.json && { status: g.json.status, blockedReason: g.json.blockedReason, canApprove: g.json.canApprove, canReject: g.json.canReject, canWithdraw: g.json.canWithdraw } } };
  });
}

/**
 * As of a date (ASO-R3-1 around automation). Needs history, so it ages the database (§3.3): run last.
 * Before ageing: pcOpen (held, left pending), pcRejected (held, rejected). After ageing 10 days: sam approves pcOpen.
 */
async function r4AsOfCases(ctx) {
  const { R, t } = ctx;
  for (const id of [...new Set(ctx.cleanup)]) await rt.automation.disable(t, id).catch(() => {});
  const a = await heldPromise(ctx, 7100);
  const b = await heldPromise(ctx, 7200);
  const ts0 = await rt.login('sam');
  const rej = b.pcId ? await rt.approvals.reject(ts0, b.pcId, 'no thanks') : { status: 0 };
  rt.ageWorld(10);
  const ts = await rt.login('sam');
  const ap = a.pcId ? await rt.approvals.approve(ts, a.pcId, 'ok now') : { status: 0 };
  const past = rt.day(-2);
  ctx.r4asof = { a, b, past };
  const K = (meta, fn) => H.kase(R, { kind: 'API', feature: 'As of a date', prd: 'B3', ...meta }, fn, { repeat: false });

  await K({ id: 'AUT-API-162', title: 'ASO-R3-1 around automation: a change a rule proposed, read as of a date before its approval, answers 200 with where it stood then and readable names',
    steps: `held automation promise pcOpen (invoice 7100) 10 days ago (ageWorld 10); sam approves it now; sam GET /api/approvals/{pcOpen}?asOf=${past} and live`,
    expected: 'TRIAGE r3 ASO-R3-1 userDecision + DESIGN §5.11 (as-of): as of the date 200, statusAt PENDING, decidedBy/decidedAt/result null (decision after the date hidden), canApprove/canReject/canWithdraw false, blockedReason "Read-only in the as-of view", current null, origin AUTOMATION and ruleId, "Invoices" row = the invoice number, "Collection POC" row = "Sam South"; live: APPROVED, decidedBy sam, result links the promise' },
  async () => {
    if (!a.pcId) return { status: 'BLOCKED', actual: { effects: a.effects } };
    const h = await rt.approvals.get(ts, a.pcId, { asOf: past });
    const l = await rt.approvals.get(ts, a.pcId);
    const ch = h.json?.changes || [];
    const invRow = ch.find((c) => c.field === 'invoiceIds');
    const pocRow = ch.find((c) => c.field === 'collectionPocUserId');
    const d = h.json || {};
    return { ok: ap.status === 200 && h.status === 200 && d.statusAt === 'PENDING' && d.decidedBy == null && d.decidedAt == null && d.result == null
        && d.canApprove === false && d.canReject === false && d.canWithdraw === false && d.blockedReason === 'Read-only in the as-of view' && d.current == null
        && d.origin === 'AUTOMATION' && d.ruleId === a.rule.id && invRow?.label === 'Invoices' && invRow?.proposedText === a.inv.invoiceNumber && pocRow?.proposedText === 'Sam South'
        && l.status === 200 && l.json.status === 'APPROVED' && l.json.decidedBy?.id === ctx.w.users.sam.id && !!l.json.result,
      actual: { approveNow: ap.status, asOf: { http: h.status, error: h.json?.error, message: H.short(h.json?.message, 200), status: d.status, statusAt: d.statusAt, decidedBy: d.decidedBy, decidedAt: d.decidedAt, result: d.result,
        can: [d.canApprove, d.canReject, d.canWithdraw], blockedReason: d.blockedReason, current: d.current, origin: d.origin, ruleId: d.ruleId,
        changes: ch.map((c) => [c.field, c.label, c.proposedText]) }, live: { http: l.status, status: l.json?.status, decidedBy: l.json?.decidedBy, result: l.json?.result }, invoiceNumber: a.inv.invoiceNumber },
      evidence: `GET /api/approvals/${a.pcId}?asOf=${past} as sam`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:286' };
  });

  await K({ id: 'AUT-API-163', title: 'As of a date, the approvals list, the customer\'s outstanding creates and the promises list show the rule\'s change as still outstanding and not yet in effect; today they show it applied',
    steps: `GET /api/approvals?asOf=${past}&view=ALL (sam); GET /api/approvals/for-record?entityType=CUSTOMER&entityId=<c>&asOf=${past}; GET /api/promises?filter=customerId:eq:<c> with and without asOf; same for the rejected pcRejected`,
    expected: 'PRD B3 ("which approvals were outstanding") + DESIGN §6.11: as of the date pcOpen is listed with statusAt PENDING and is in for-record; the customer has no promise then; pcRejected (decided before the date) is neither in the as-of list (it lists the changes outstanding then, PendingChangeScope.forList §5.11.9) nor in its customer\'s for-record; live: pcOpen APPROVED, for-record empty, one promise of 7100' },
  async () => {
    if (!a.pcId || !b.pcId) return { status: 'BLOCKED', actual: 'no held changes' };
    const list = await rt.list(ts, '/api/approvals', { view: 'ALL' }, { asOf: past });
    const rowA = (list.json?.content || []).find((x) => x.id === a.pcId);
    const rowB = (list.json?.content || []).find((x) => x.id === b.pcId);
    const frA = await rt.approvals.forRecord(ts, 'CUSTOMER', a.cust.c.id, { asOf: past });
    const frB = await rt.approvals.forRecord(ts, 'CUSTOMER', b.cust.c.id, { asOf: past });
    const frLive = await rt.approvals.forRecord(ts, 'CUSTOMER', a.cust.c.id);
    const pPast = await rt.api('GET', '/api/promises', { token: ts, query: { asOf: past, size: 50, filter: `customerId:eq:${a.cust.c.id}` } });
    const pLive = await H.promisesOf(ts, a.cust.c.id);
    const ids = (r) => (Array.isArray(r.json) ? r.json : r.json?.content || []).map((x) => x.id);
    return { ok: list.status === 200 && rowA?.statusAt === 'PENDING' && rowA?.origin === 'AUTOMATION' && !rowB
        && frA.status === 200 && ids(frA).includes(a.pcId) && frB.status === 200 && !ids(frB).includes(b.pcId)
        && frLive.status === 200 && !ids(frLive).includes(a.pcId)
        && pPast.status === 200 && (pPast.json?.content || []).length === 0 && pLive.length === 1 && Number(pLive[0].amount) === 7100,
      actual: { rejectBefore: rej.status, list: [list.status, H.short(list.json?.message, 150)], rowA: rowA && [rowA.status, rowA.statusAt, rowA.origin, rowA.decidedAt], rowB: rowB && [rowB.status, rowB.statusAt],
        forRecordAsOf: [frA.status, ids(frA)], forRecordRejectedAsOf: [frB.status, ids(frB)], forRecordLive: [frLive.status, ids(frLive)],
        promisesAsOf: [pPast.status, (pPast.json?.content || []).map((x) => x.id), H.short(pPast.json?.message, 150)], promisesLive: pLive.map((x) => [x.id, x.amount]) } };
  });

  await K({ id: 'AUT-API-164', title: 'As of a date, the rule that proposed the change still previews the invoice it acted on, and its run is listed',
    steps: `GET /api/automation/rules/{ruleA}/preview?asOf=${past}; GET /api/automation/rules/{ruleA}/runs`,
    expected: 'DESIGN §5.10.9: preview 200 matchCount 1 (the invoice was unpaid then); the run (SUCCEEDED, done 1) is listed' },
  async () => {
    const p = await rt.api('GET', `/api/automation/rules/${a.rule.id}/preview`, { token: t, query: { asOf: past } });
    const runs = (await rt.automation.runs(t, a.rule.id)).json?.content || [];
    return { ok: p.status === 200 && p.json.matchCount === 1 && runs.some((r) => r.id === a.run.id && r.status === 'SUCCEEDED'),
      actual: { preview: [p.status, p.json?.matchCount, p.json?.asOf, H.short(p.json?.message, 200)], runs: runs.map((r) => [r.id, r.status, r.done]) } };
  });
}

/** UI around RGR-R3-1 (placeholder buttons in the rule editor) and ASO-R3-1 (approval detail as of a date). */
async function r4UiCases(ctx) {
  const { R, t } = ctx;
  let n = 0;
  const K = (meta, fn) => H.kase(R, { kind: 'UI', feature: 'Automation UI', ...meta }, async (attempt) => {
    let lastShot = '';
    try {
      const res = await fn(attempt, (s) => { lastShot = s; });
      return { screenshot: lastShot, ...res };
    } catch (e) {
      return { status: 'FAIL', actual: `UI step failed: ${String(e && e.message || e).slice(0, 1200)}`, screenshot: lastShot };
    }
  });
  async function settle(page) {
    const v = page.viewportSize();
    await page.setViewportSize({ width: v.width, height: v.height + 1 }); await page.waitForTimeout(500);
    await page.setViewportSize(v); await page.waitForTimeout(700); await rt.enableSemantics(page);
  }
  const active = (page) => page.evaluate(() => { const a = document.activeElement; return a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') ? { v: a.value, s: a.selectionStart, e: a.selectionEnd } : null; });
  async function typeInto(page, label, value, { inDialog = false } = {}) {
    for (let i = 0; i < 4; i++) {
      if (!inDialog) await settle(page);
      const f = await rt.find(page, label, { role: 'textbox' });
      await page.mouse.click(f.x, f.y); await page.waitForTimeout(600);
      const a0 = await active(page);
      if (!a0) { await page.waitForTimeout(800); await rt.enableSemantics(page); continue; }
      if (a0.v) { await page.keyboard.press('Meta+A'); await page.keyboard.press('Backspace'); }
      await page.keyboard.type(value, { delay: 25 }); await page.waitForTimeout(400);
      const a1 = await active(page);
      if (a1 && a1.v === value) return f;
    }
    throw new Error(`could not type into ${label}`);
  }
  /** Caret: End then `left` ArrowLefts (field must have focus); then a real click on the field's placeholder button and on `item`. */
  async function insertAt(page, label, left, item, { moveCaret = true } = {}) {
    const f = await rt.find(page, label, { role: 'textbox' });
    if (moveCaret) {
      await page.keyboard.press('End');
      for (let i = 0; i < left; i++) await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(300);
    }
    const before = await active(page);
    const b = (await rt.semantics(page)).filter((x) => x.text === 'Insert placeholder' || x.label === 'Insert placeholder')
      .sort((p, q) => Math.abs(p.y - f.y) - Math.abs(q.y - f.y))[0];
    await rt.clickAt(page, b.x, b.y, 1500);
    await rt.press(page, item, { wait: 1500 });
    return { before, field: f };
  }

  await K({ id: 'AUT-UI-23', prd: 'A4', title: 'RGR-R3-1 around the rule editor: a placeholder goes in at the caret of the task title, email subject and email body (mid-text), a second pick goes right after the first, and typing on after clicking back does not replace the text',
    steps: 'admin /automation/new; Name; Add action "Create a task": Title "Dear , thanks", caret after "Dear " -> Insert placeholder > Name; Add action "Send an email" > Edit email…: Customer emails; Subject "Invoice  is due", caret after "Invoice " -> Number, then (without touching the field) Insert placeholder > Name again; click at the end of Subject and type "!"; Message "Hello ,\\nBalance: now", caret before " now" -> Balance; Done; Create rule',
    expected: 'TRIAGE r3 RGR-R3-1 userDecision (placeholder at the caret the field had when the button was pressed; select-all from refocus ignored): saved rule has title "Dear {{Customer.Name}}, thanks", subject "Invoice {{Invoice.Number}}{{Customer.Name}} is due!", body "Hello ,\\nBalance:{{Invoice.Balance}} now"' },
  async (attempt, setShot) => {
    const app = await rt.openApp({ token: t, height: 1400 });
    const page = app.page;
    try {
      const name = rt.uniq('UI-ph');
      await rt.go(page, '/automation/new', 4000);
      await typeInto(page, 'Name *', name);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a task');
      await typeInto(page, 'Title *', 'Dear , thanks');
      const r1 = await insertAt(page, 'Title *', 8, 'Name');
      const hdr = (await rt.screenText(page)).find((s) => /^Create a task/.test(s));
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-23-${++n}`));
      await settle(page);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Send an email');
      await settle(page);
      await rt.tap(page, /Edit email/, { wait: 2500 });
      await rt.tap(page, 'Customer emails', { wait: 1000 });
      await typeInto(page, 'Subject *', 'Invoice  is due', { inDialog: true });
      const r2 = await insertAt(page, 'Subject *', 7, 'Number');
      await insertAt(page, 'Subject *', 0, 'Name', { moveCaret: false });
      // click back at the far right end of the subject and type on
      const sf = await rt.find(page, 'Subject *', { role: 'textbox' });
      await page.mouse.click(sf.x + sf.w / 2 - 12, sf.y); await page.waitForTimeout(600);
      const beforeType = await active(page);
      await page.keyboard.press('End');
      await page.keyboard.type('!', { delay: 25 }); await page.waitForTimeout(500);
      const afterType = await active(page);
      await typeInto(page, 'Message', 'Hello ,', { inDialog: true });
      await page.keyboard.press('Enter'); await page.keyboard.type('Balance: now', { delay: 25 });
      const r3 = await insertAt(page, 'Message', 4, 'Balance');
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-23-${++n}`));
      await rt.tap(page, 'Done', { wait: 1500 });
      await settle(page);
      await rt.tap(page, 'Create rule', { wait: 4000 });
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-23-${++n}`));
      const l = await rt.list(t, '/api/automation/rules', { filter: [`name:eq:${name}`] });
      const saved = (l.json?.content || [])[0];
      const full = saved ? (await rt.automation.get(t, saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); await rt.automation.disable(t, full.id); }
      const task = full?.actions?.find((x) => x.type === 'CREATE_TASK');
      const mail = full?.actions?.find((x) => x.type === 'SEND_EMAIL');
      return { ok: task?.title === 'Dear {{Customer.Name}}, thanks' && mail?.subject === 'Invoice {{Invoice.Number}}{{Customer.Name}} is due!'
          && mail?.body === 'Hello ,\nBalance:{{Invoice.Balance}} now',
        actual: { saved: !!full, title: task?.title, subject: mail?.subject, body: mail?.body, cardHeader: hdr,
          caretBefore: { title: r1.before, subject: r2.before, body: r3.before }, subjectBeforeType: beforeType, subjectAfterType: afterType },
        evidence: `GET /api/automation/rules/${full?.id}` };
    } finally { await app.close(); }
  });

  const A = ctx.r4asof;
  await K({ id: 'AUT-UI-24', prd: 'B3', title: 'ASO-R3-1 around automation in the UI: the approver opens a rule\'s change as of a date before it was approved and sees it pending, read only, with the invoice by number',
    steps: 'after AUT-API-162 (pcOpen approved today, held 10 days ago): sam opens /approvals/{pcOpen}, chooses As of <today-2>',
    expected: 'PRD B3 + DESIGN §7 approval detail: no error; banner "Viewing … — read only"; "Read-only in the as-of view"; "Awaiting approval" (not Approved); "Rule #<id>"; the Changes tab names the invoice by number; no Approve/Reject buttons' },
  async (attempt, setShot) => {
    if (!A?.a?.pcId) return { status: 'BLOCKED', actual: 'no pcOpen from the as-of step' };
    const app = await rt.openApp({ token: await rt.login('sam'), height: 1400 });
    try {
      await rt.go(app.page, `/approvals/${A.a.pcId}`, 5000);
      const chip = await rt.chooseAsOf(app.page, A.past);
      await app.page.waitForTimeout(2500); await rt.enableSemantics(app.page);
      const nodes = await rt.semantics(app.page);
      const lines = await rt.screenText(app.page);
      const s = lines.join(' | ');
      setShot(await rt.shot(app.page, R.shotsDir, `AUT-UI-24-${++n}`));
      const btn = (l) => nodes.some((x) => x.role === 'button' && !x.disabled && (x.text === l || x.label === l));
      const asOfReqs = app.requests.filter((r) => r.url.startsWith(`/api/approvals/${A.a.pcId}`) && /asOf=/.test(r.url));
      return { ok: /read only/i.test(s) && s.includes('Read-only in the as-of view') && lines.includes('Awaiting approval') && !lines.includes('Approved') && s.includes(`Rule #${A.a.rule.id}`) && s.includes(A.a.inv.invoiceNumber)
          && !btn('Approve') && !btn('Reject') && asOfReqs.length > 0 && asOfReqs.every((r) => r.status === 200),
        actual: { chip, approveBtn: btn('Approve'), rejectBtn: btn('Reject'), asOfRequests: asOfReqs.map((r) => [r.status, r.url]), apiErrors: app.apiErrors, screen: H.short(s, 1200) } };
    } finally { await app.close(); }
  });
}

module.exports = { r4Cases, r4AsOfCases, r4UiCases };
