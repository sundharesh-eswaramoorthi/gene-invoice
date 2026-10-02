// Round 5 additions. Round 4 reported no automation defects; the fixes made after round 4 were
//   RGN-R4-1  an approval's record visibility follows the record's own region, live and as of a date
//             (PendingChangeScope.canSeeRecord / isVisible / recordRegion),
//   TSK-R4-1  Insert placeholder gives the text field its focus back with the caret after the token
//             (placeholder_button.dart + one FocusNode per field in action_cards / email_action_dialog / send_email_dialog).
// One step around each, on the automation side:
//   - a change a rule proposed (a held promise create, entityId null -> judged by the customer) whose customer then
//     moves region: old-region approver / old-region viewer / new-region viewer, live and as of a date;
//     and the rule's own evaluation (preview) follows the record's region live and as of a date;
//   - the rule editor's placeholder fields after a pick by mouse: focus back and typing on without clicking back,
//     in task title and description, promise notes, dispute reason, email subject and body; closing the menu
//     without a pick keeps typing where the user was.
const rt = require('../../lib.js');
const H = require('./helpers.js');

async function heldPromiseSouth(ctx, amount, { regionIds = [] } = {}) {
  const { t, w } = ctx;
  const S = w.regions.SOUTH.id;
  const cust = await H.mkCustomer(t, { regionId: S, collector: w.users.sam.id, amounts: [amount] });
  const inv = cust.invoices[0];
  const rule = await H.mustRule(t, { trigger: H.manual(), regionIds, condition: H.and(`id:eq:${inv.id}`),
    actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' } })] });
  ctx.cleanup.push(rule.id);
  const { run, effects } = await H.runAndWait(t, rule.id);
  const pcId = effects[0]?.result?.type === 'PENDING_CHANGE' ? effects[0].result.id : null;
  return { cust, inv, rule, run, effects, pcId };
}

async function people(ctx) {
  if (ctx.r5people) return ctx.r5people;
  const { t, w } = ctx;
  const S = w.regions.SOUTH.id; const N = w.regions.NORTH.id;
  const sal = await rt.createStaff(t, 'E2E_REGIONAL', 'sal5', { grants: [{ regionId: S, accessLevel: 'APPROVE', recordScope: 'ALL' }] });
  const vic = await rt.createStaff(t, 'E2E_REGIONAL', 'vic5', { grants: [{ regionId: S, accessLevel: 'READ', recordScope: 'ALL' }] });
  const nor = await rt.createStaff(t, 'E2E_REGIONAL', 'nor5', { grants: [{ regionId: N, accessLevel: 'APPROVE', recordScope: 'ALL' }] });
  ctx.r5people = { sal, vic, nor };
  return ctx.r5people;
}
const view = (r) => ({ http: r.status, status: r.json?.status, statusAt: r.json?.statusAt, blockedReason: r.json?.blockedReason,
  current: r.json?.current == null ? null : 'filled', changesCurrent: (r.json?.changes || []).map((c) => c.current == null ? null : 'x'),
  canApprove: r.json?.canApprove, error: r.json?.error });

async function r5Cases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const S = w.regions.SOUTH.id;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Automation and approvals', ...meta }, fn, o);

  await K({ id: 'AUT-API-165', prd: 'B2', title: 'RGN-R4-1 around automation: a promise a rule proposed for a South customer that then moves to North shows no live values to a South-only approver, is gone for a South-only viewer, and stays readable for North',
    steps: 'South customer (Collection POC sam) invoice 6600; admin MANUAL rule CREATE_PROMISE Invoice.Balance -> held pc (South PROMISE_CREATE 5000); sal5 APPROVE/ALL South only, vic5 READ/ALL South only, nora (MANAGE North, READ South); GET /api/approvals/{pc} as sal5 / vic5 / nora; admin POST /api/customers/{c}/move-region North; GET again',
    expected: 'TRIAGE r4 RGN-R4-1 userDecision (a create has no now-values: PromiseChangeApplier.currentView is null without an entityId, so the record check is judged on blockedReason): before the move sal5 200, blockedReason null, canApprove true; vic5 200 (not "no longer have access"). After the move pc CLOSED; sal5 200 CLOSED, current null, blockedReason "You no longer have access to this record"; vic5 404; sal5 GET invoice 404; nora (sees North now, READ South for the change) 200 and not "no longer have access"' },
  async () => {
    const P = await people(ctx);
    const h = await heldPromiseSouth(ctx, 6600);
    await rt.automation.disable(t, h.rule.id);
    if (!h.pcId) return { status: 'BLOCKED', actual: { run: h.run && h.run.status, effects: h.effects } };
    const ts = await rt.login(P.sal.username); const tv = await rt.login(P.vic.username); const tn = await rt.login('nora');
    const b = { sal: await rt.approvals.get(ts, h.pcId), vic: await rt.approvals.get(tv, h.pcId), nora: await rt.approvals.get(tn, h.pcId) };
    const mv = await rt.moveCustomerRegion(t, h.cust.c.id, N, 'r5 move');
    const a = { sal: await rt.approvals.get(ts, h.pcId), vic: await rt.approvals.get(tv, h.pcId), nora: await rt.approvals.get(tn, h.pcId) };
    const salInv = await rt.api('GET', `/api/invoices/${h.inv.id}`, { token: ts });
    ctx.r5move = { h, moved: mv.status };
    const NO = 'You no longer have access to this record';
    const ok = b.sal.status === 200 && b.sal.json.blockedReason == null && b.sal.json.canApprove === true
      && b.vic.status === 200 && b.vic.json.blockedReason !== NO && b.nora.status === 200
      && [200, 202].includes(mv.status)
      && a.sal.status === 200 && a.sal.json.status === 'CLOSED' && a.sal.json.current == null && a.sal.json.blockedReason === 'You no longer have access to this record'
      && (a.sal.json.changes || []).every((c) => c.current == null)
      && a.vic.status === 404 && a.nora.status === 200 && a.nora.json.blockedReason !== NO && salInv.status === 404;
    return { ok, actual: { pc: h.pcId, move: [mv.status, H.short(mv.json?.message, 150)], before: { sal: view(b.sal), vic: view(b.vic), nora: view(b.nora) },
        after: { sal: view(a.sal), vic: view(a.vic), nora: view(a.nora) }, salInvoice: salInv.status },
      evidence: `GET /api/approvals/${h.pcId} as ${P.sal.username}/${P.vic.username}/nora before and after POST move-region`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:121' };
  });

  await K({ id: 'AUT-API-166', prd: 'B2', title: 'RGN-R4-1 around automation: the approvals list and count of a South-only approver and a South-only viewer follow the customer out of South; the rule\'s run and effects stay readable to the owner',
    steps: 'after AUT-API-165: GET /api/approvals?view=ALL as sal5 and vic5; GET /api/approvals/summary; GET /api/automation/runs/{run}/effects as admin',
    expected: 'TRIAGE r4 userDecision: sal5 (approver of the region the change was asked in) still lists the closed change; vic5 (plain viewer) does not; the run effect still names the pending change' },
  async () => {
    const m = ctx.r5move; if (!m?.h?.pcId) return { status: 'BLOCKED', actual: 'no pc from AUT-API-165' };
    const P = await people(ctx);
    const ts = await rt.login(P.sal.username); const tv = await rt.login(P.vic.username);
    const ls = await rt.list(ts, '/api/approvals', { view: 'ALL' });
    const lv = await rt.list(tv, '/api/approvals', { view: 'ALL' });
    const inS = (ls.json?.content || []).some((x) => x.id === m.h.pcId);
    const inV = (lv.json?.content || []).some((x) => x.id === m.h.pcId);
    const eff = await rt.automation.effects(t, m.h.run.id);
    const e0 = (eff.json?.content || [])[0];
    return { ok: ls.status === 200 && inS && lv.status === 200 && !inV && eff.status === 200 && e0?.result?.id === m.h.pcId,
      actual: { salList: [ls.status, inS, ls.json?.totalElements], vicList: [lv.status, inV, lv.json?.totalElements], effects: [eff.status, e0 && e0.result] } };
  });

  await K({ id: 'AUT-API-167', prd: 'B1', title: 'A rule scoped to South stops matching the invoice once its customer moves to North (live preview and Run now)',
    steps: 'after AUT-API-165: POST /api/automation/rules/preview with the same rule but regionIds [South] and [North]',
    expected: 'PRD B1 (every record belongs to exactly one region) + DESIGN §5.10: the South-scoped preview matches 0, the North-scoped one 1' },
  async () => {
    const m = ctx.r5move; if (!m?.h?.inv) return { status: 'BLOCKED', actual: 'no fixture' };
    const body = (regionIds) => ({ rule: rt.rule({ trigger: H.manual(), regionIds, condition: H.and(`id:eq:${m.h.inv.id}`), actions: [rt.action.task()] }), sampleSize: 5 });
    const pS = await rt.automation.preview(t, body([S]));
    const pN = await rt.automation.preview(t, body([N]));
    return { ok: pS.status === 200 && pS.json.matchCount === 0 && pN.status === 200 && pN.json.matchCount === 1,
      actual: { south: [pS.status, pS.json?.matchCount, H.short(pS.json?.message, 150)], north: [pN.status, pN.json?.matchCount] } };
  });
}

/** As of a date around RGN-R4-1: needs history, so ages the database (another 5 days) — run after r4asof. */
async function r5AsOfCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const S = w.regions.SOUTH.id;
  for (const id of [...new Set(ctx.cleanup)]) await rt.automation.disable(t, id).catch(() => {});
  const P = await people(ctx);
  const h = await heldPromiseSouth(ctx, 6700, { regionIds: [S] });
  await rt.automation.disable(t, h.rule.id);
  rt.ageWorld(5);
  const mv = await rt.moveCustomerRegion(t, h.cust.c.id, N, 'r5 asof move');
  const past = rt.day(-2);
  const K = (meta, fn) => H.kase(R, { kind: 'API', feature: 'As of a date', prd: 'B3', ...meta }, fn, { repeat: false });

  await K({ id: 'AUT-API-168', title: 'RGN-R4-1 as of a date around automation: before the customer\'s move the rule\'s change is readable to the South-only viewer and read-only for the South approver; today it is 404 / "no longer have access"',
    steps: `South held automation promise (6700) made 5 days ago (ageWorld 5); admin moves the customer to North now; GET /api/approvals/{pc}?asOf=${past} and live as vic5 (READ South) and sal5 (APPROVE South)`,
    expected: 'TRIAGE r4 userDecision ("As of a date, the record\'s region at that date decides"): as of the date vic5 200 statusAt PENDING; sal5 200 blockedReason "Read-only in the as-of view", current null; live vic5 404, sal5 200 CLOSED "You no longer have access to this record"' },
  async () => {
    if (!h.pcId) return { status: 'BLOCKED', actual: { effects: h.effects } };
    const tv = await rt.login(P.vic.username); const ts = await rt.login(P.sal.username);
    const vp = await rt.approvals.get(tv, h.pcId, { asOf: past });
    const sp = await rt.approvals.get(ts, h.pcId, { asOf: past });
    const vl = await rt.approvals.get(tv, h.pcId);
    const sl = await rt.approvals.get(ts, h.pcId);
    return { ok: [200, 202].includes(mv.status) && vp.status === 200 && vp.json.statusAt === 'PENDING' && sp.status === 200 && sp.json.blockedReason === 'Read-only in the as-of view' && sp.json.current == null
        && vl.status === 404 && sl.status === 200 && sl.json.status === 'CLOSED' && sl.json.blockedReason === 'You no longer have access to this record',
      actual: { move: mv.status, vicAsOf: view(vp), salAsOf: view(sp), vicLive: view(vl), salLive: view(sl) },
      evidence: `GET /api/approvals/${h.pcId}?asOf=${past}`, codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:147' };
  });

  await K({ id: 'AUT-API-169', title: 'Rule evaluation as of a date uses the region the record was in then: the South-scoped rule previews the moved invoice as of before the move, not today',
    steps: `GET /api/automation/rules/{rule}/preview?asOf=${past} and without asOf (rule regionIds [South], condition id:eq:<invoice>)`,
    expected: 'PRD B3 ("every … rule evaluation can be asked as of a date, including which region a record belonged to then"): as of the date matchCount 1; live 0' },
  async () => {
    const pp = await rt.api('GET', `/api/automation/rules/${h.rule.id}/preview`, { token: t, query: { asOf: past } });
    const pl = await rt.api('GET', `/api/automation/rules/${h.rule.id}/preview`, { token: t });
    return { ok: pp.status === 200 && pp.json.matchCount === 1 && pl.status === 200 && pl.json.matchCount === 0,
      actual: { asOf: [pp.status, pp.json?.matchCount, H.short(pp.json?.message, 200)], live: [pl.status, pl.json?.matchCount, H.short(pl.json?.message, 200)] } };
  });
}

/** UI around TSK-R4-1: focus comes back to the field after a mouse pick, so typing goes on without clicking back. */
async function r5UiCases(ctx) {
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
  const active = (page) => page.evaluate(() => { const a = document.activeElement; return a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') ? { tag: a.tagName, v: a.value, s: a.selectionStart, e: a.selectionEnd } : { tag: a && a.tagName }; });
  async function typeInto(page, label, value, { inDialog = false } = {}) {
    for (let i = 0; i < 4; i++) {
      if (!inDialog) await settle(page);
      const f = await lastBox(page, label);
      await page.mouse.click(f.x, f.y); await page.waitForTimeout(600);
      const a0 = await active(page);
      if (a0.v == null) { await page.waitForTimeout(800); await rt.enableSemantics(page); continue; }
      if (a0.v) { await page.keyboard.press('Meta+A'); await page.keyboard.press('Backspace'); }
      await page.keyboard.type(value, { delay: 25 }); await page.waitForTimeout(400);
      const a1 = await active(page);
      if (a1 && a1.v === value) return f;
    }
    throw new Error(`could not type into ${label}; textboxes: ${(await rt.semantics(page)).filter((x) => x.role === 'textbox').map((x) => `${x.label}@${Math.round(x.y)}`).join(', ')}`);
  }
  /** Field has focus with the caret at its end. Mouse click on its {} button, mouse click on `item`, then type `suffix` without touching the field. */
  async function pickAndType(page, label, item, suffix) {
    const f = await lastBox(page, label);
    const b = (await rt.semantics(page)).filter((x) => x.text === 'Insert placeholder' || x.label === 'Insert placeholder')
      .sort((p, q) => Math.abs(p.y - f.y) - Math.abs(q.y - f.y))[0];
    await rt.clickAt(page, b.x, b.y, 1500);
    const it = await rt.find(page, item);
    await page.mouse.click(it.x, it.y); await page.waitForTimeout(1300);
    const afterPick = await active(page);
    await page.keyboard.type(suffix, { delay: 30 }); await page.waitForTimeout(500);
    const afterType = await active(page);
    return { afterPick, afterType };
  }
  const val = (x) => x && x.v;
  /** The last textbox with this label (the rule's own Description comes before the task card's). */
  async function lastBox(page, label) {
    const hits = (await rt.semantics(page)).filter((x) => x.role === 'textbox' && (x.label === label || x.text === label));
    if (!hits.length) return rt.find(page, label, { role: 'textbox' });
    return hits[hits.length - 1];
  }

  await K({ id: 'AUT-UI-25', prd: 'A4', title: 'TSK-R4-1 around the rule editor: after a mouse pick from Insert placeholder the field has its focus back, so typing goes on after the token in task Title/Description, promise Notes and dispute Reason',
    steps: 'admin /automation/new; Name; Add action Create a task: Title "Chase " -> {} (mouse) > Name -> type " now" (no click back); Description "Owes " -> {} > Balance -> type "!"; Add action Create a promise: Notes "Pay " -> {} > Number -> type " soon"; Add action Open a dispute: Reason "Check " -> {} > Name -> type " terms"; Create rule',
    expected: 'TRIAGE r4 TSK-R4-1 (FIXLOG-frontend "E2E r4 F1"): after each pick document.activeElement is the field (INPUT/TEXTAREA) with the caret after the token; saved rule: title "Chase {{Customer.Name}} now", description "Owes {{Invoice.Balance}}!", notes "Pay {{Invoice.Number}} soon", reason "Check {{Customer.Name}} terms"' },
  async (attempt, setShot) => {
    const app = await rt.openApp({ token: t, height: 2400 });
    const page = app.page;
    try {
      const name = rt.uniq('UI-ph5');
      await rt.go(page, '/automation/new', 4000);
      await typeInto(page, 'Name *', name);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a task');
      await typeInto(page, 'Title *', 'Chase ');
      const s1 = await pickAndType(page, 'Title *', 'Name', ' now');
      await typeInto(page, 'Description', 'Owes ');
      const s2 = await pickAndType(page, 'Description', 'Balance', '!');
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-25-${++n}`));
      await settle(page);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a promise');
      await typeInto(page, 'Notes', 'Pay ');
      const s3 = await pickAndType(page, 'Notes', 'Number', ' soon');
      await settle(page);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Open a dispute');
      await typeInto(page, 'Reason *', 'Check ');
      const s4 = await pickAndType(page, 'Reason *', 'Name', ' terms');
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-25-${++n}`));
      await settle(page);
      await rt.tap(page, 'Create rule', { wait: 4000 });
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-25-${++n}`));
      const l = await rt.list(t, '/api/automation/rules', { filter: [`name:eq:${name}`] });
      const saved = (l.json?.content || [])[0];
      const full = saved ? (await rt.automation.get(t, saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); await rt.automation.disable(t, full.id); }
      const A = (type) => full?.actions?.find((x) => x.type === type);
      const exp = { t: 'Chase {{Customer.Name}} now', d: 'Owes {{Invoice.Balance}}!', n: 'Pay {{Invoice.Number}} soon', r: 'Check {{Customer.Name}} terms' };
      const focusOk = [s1, s2, s3, s4].every((s) => s.afterPick && /INPUT|TEXTAREA/.test(s.afterPick.tag));
      const typedOk = val(s1.afterType) === exp.t && val(s2.afterType) === exp.d && val(s3.afterType) === exp.n && val(s4.afterType) === exp.r;
      const savedOk = A('CREATE_TASK')?.title === exp.t && A('CREATE_TASK')?.description === exp.d && A('CREATE_PROMISE')?.notes === exp.n && A('CREATE_DISPUTE')?.reason === exp.r;
      return { ok: focusOk && typedOk && savedOk,
        actual: { focusOk, typedOk, savedOk, saved: !!full, fields: { title: s1, description: s2, notes: s3, reason: s4 },
          savedValues: full && { title: A('CREATE_TASK')?.title, description: A('CREATE_TASK')?.description, notes: A('CREATE_PROMISE')?.notes, reason: A('CREATE_DISPUTE')?.reason } },
        evidence: `GET /api/automation/rules/${full?.id}` };
    } finally { await app.close(); }
  });

  await K({ id: 'AUT-UI-26', prd: 'A4', title: 'TSK-R4-1 around the rule\'s email dialog: after a mouse pick the Subject and Message keep focus, typing goes on after the token; closing the menu without a pick keeps typing where the user was',
    steps: 'admin /automation/new; Name; Add action Send an email > Edit email…; Customer emails; Subject "Invoice " -> {} > Number -> type " due"; Message "Hi " -> {} > Name -> type ","; then Subject: End, open {} and close it with a click outside (no pick), type "!"; Done; Create rule',
    expected: 'FIXLOG-frontend "E2E r4 F1" (onCanceled gives the field its focus back with its selection): activeElement is the field after each pick; saved subject "Invoice {{Invoice.Number}} due!", body "Hi {{Customer.Name}},"' },
  async (attempt, setShot) => {
    const app = await rt.openApp({ token: t, height: 1400 });
    const page = app.page;
    try {
      const name = rt.uniq('UI-ph5m');
      await rt.go(page, '/automation/new', 4000);
      await typeInto(page, 'Name *', name);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Send an email');
      await settle(page);
      await rt.tap(page, /Edit email/, { wait: 2500 });
      await rt.tap(page, 'Customer emails', { wait: 1000 });
      await typeInto(page, 'Subject *', 'Invoice ', { inDialog: true });
      const s1 = await pickAndType(page, 'Subject *', 'Number', ' due');
      await typeInto(page, 'Message', 'Hi ', { inDialog: true });
      const s2 = await pickAndType(page, 'Message', 'Name', ',');
      // back to the subject, caret at the end; open the menu and close it without a pick
      const sf = await rt.find(page, 'Subject *', { role: 'textbox' });
      await page.mouse.click(sf.x + sf.w / 2 - 12, sf.y); await page.waitForTimeout(600);
      await page.keyboard.press('End'); await page.waitForTimeout(300);
      const b = (await rt.semantics(page)).filter((x) => x.text === 'Insert placeholder' || x.label === 'Insert placeholder')
        .sort((p, q) => Math.abs(p.y - sf.y) - Math.abs(q.y - sf.y))[0];
      await rt.clickAt(page, b.x, b.y, 1500);
      const dismissed = await rt.dismiss(page);
      await page.waitForTimeout(800);
      const afterCancel = await active(page);
      await page.keyboard.type('!', { delay: 30 }); await page.waitForTimeout(500);
      const afterCancelType = await active(page);
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-26-${++n}`));
      await rt.tap(page, 'Done', { wait: 1500 });
      await settle(page);
      await rt.tap(page, 'Create rule', { wait: 4000 });
      setShot(await rt.shot(page, R.shotsDir, `AUT-UI-26-${++n}`));
      const l = await rt.list(t, '/api/automation/rules', { filter: [`name:eq:${name}`] });
      const saved = (l.json?.content || [])[0];
      const full = saved ? (await rt.automation.get(t, saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); await rt.automation.disable(t, full.id); }
      const mail = full?.actions?.find((x) => x.type === 'SEND_EMAIL');
      const focusOk = [s1.afterPick, s2.afterPick, afterCancel].every((a) => a && /INPUT|TEXTAREA/.test(a.tag));
      return { ok: focusOk && val(s1.afterType) === 'Invoice {{Invoice.Number}} due' && val(s2.afterType) === 'Hi {{Customer.Name}},'
          && mail?.subject === 'Invoice {{Invoice.Number}} due!' && mail?.body === 'Hi {{Customer.Name}},',
        actual: { focusOk, subject: s1, message: s2, dismissed, afterCancel, afterCancelType, saved: mail && { subject: mail.subject, body: mail.body } },
        evidence: `GET /api/automation/rules/${full?.id}` };
    } finally { await app.close(); }
  });
}

module.exports = { r5Cases, r5AsOfCases, r5UiCases };
