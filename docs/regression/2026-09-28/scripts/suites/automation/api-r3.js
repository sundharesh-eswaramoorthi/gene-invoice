// Round 3 additions. Round 2 reported no automation defects; the fixes made after round 2 were APP-R2-1 (readable
// changes table on a pending change) and MIG-R2-1 (event counters with a column default). The nearby gaps on the
// automation side: the pending change an automation proposes (its Changes rows, its origin and rule), the promise
// co-assignees that must survive the hold (DESIGN §5.11 "Promise co-assignees survive a hold"), the dedupe of a held
// outcome on a re-run, and the event counters written by the consumer.
const rt = require('../../lib.js');
const H = require('./helpers.js');

async function runsOf(t, ruleId) {
  return (await rt.automation.runs(t, ruleId)).json?.content || [];
}

async function r3Cases(ctx) {
  const { R, t, w } = ctx;
  const S = w.regions.SOUTH.id;
  const U = w.users; const T = w.tokens;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Automation and approvals', ...meta }, fn, o);

  await K({ id: 'AUT-API-150', prd: 'B2', title: 'The pending change an automation proposes reads as the rule\'s: origin AUTOMATION, the rule id, the owner as maker, and readable Changes rows ("Invoices" = the invoice number)',
    steps: 'South customer (Collection POC sam) with invoice 6200; admin MANUAL INVOICE rule (id:eq:I) CREATE_PROMISE amount FIELD Invoice.Balance, linkRecord; run; sam GET /api/approvals/{pc}',
    expected: 'PRD B2 + DESIGN §6.11 / TRIAGE r2 APP-R2-1: status PENDING, origin AUTOMATION, ruleId = rule, requestedBy = admin, amount 6200; changes has a row field invoiceIds labelled "Invoices" whose proposedText is the invoice number; no row labelled "Invoice ids"/"Promise ids"; no promiseIds row (a create with none); canApprove true for sam' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [6200] });
    const inv = cust.invoices[0];
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' }, notes: 'Auto {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const { run, effects } = await H.runAndWait(t, rule.id);
    const pcId = effects[0]?.result?.type === 'PENDING_CHANGE' ? effects[0].result.id : null;
    T.sam = await rt.login('sam');
    const d = pcId ? await rt.approvals.get(T.sam, pcId) : { status: 0 };
    const ch = d.json?.changes || [];
    const invRow = ch.find((c) => c.field === 'invoiceIds');
    const badLabels = ch.filter((c) => /Invoice ids|Promise ids/.test(c.label || ''));
    const promRow = ch.find((c) => c.field === 'promiseIds');
    await rt.automation.disable(t, rule.id);
    return { ok: run?.status === 'SUCCEEDED' && !!pcId && d.status === 200 && d.json.status === 'PENDING' && d.json.origin === 'AUTOMATION' && d.json.ruleId === rule.id
        && d.json.requestedBy?.id === ctx.adminId && Number(d.json.amount) === 6200 && invRow?.label === 'Invoices' && invRow?.proposedText === inv.invoiceNumber
        && badLabels.length === 0 && !promRow && d.json.canApprove === true,
      actual: { run: run && [run.id, run.status], effect: effects[0] && [effects[0].status, effects[0].result], get: d.status,
        detail: d.json && { status: d.json.status, origin: d.json.origin, ruleId: d.json.ruleId, requestedBy: d.json.requestedBy, amount: d.json.amount, canApprove: d.json.canApprove },
        changes: ch.map((c) => ({ field: c.field, label: c.label, proposed: c.proposed, proposedText: c.proposedText })), invoiceNumber: inv.invoiceNumber },
      evidence: `GET /api/approvals/${pcId} as sam`,
      codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:200' };
  });

  await K({ id: 'AUT-API-151', prd: 'A3', title: 'A held automation promise keeps its co-assignees: once another approver applies it, the promise has them',
    steps: 'South customer (Collection POC sam) with invoice 7300; a South staff user "coas" (MANAGE/ALL South); admin MANUAL INVOICE rule CREATE_PROMISE FIELD Invoice.Balance, assignees [USER coas, COLLECTION_POC@CUSTOMER]; run -> held; sam approves; GET /api/promises/{id}',
    expected: 'DESIGN §5.11 "Promise co-assignees survive a hold" + BE-19 AutomationHoldTest: one promise of 7300 with origin via approval; its assignees include coas and sam (the primary Collection POC)' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [7300] });
    const inv = cust.invoices[0];
    const coas = await rt.createStaff(t, 'E2E_REGIONAL', 'coas', { grants: [{ regionId: S, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' }, assignees: [rt.tok.user(coas.id), rt.tok.role('COLLECTION_POC', 'CUSTOMER')] })] });
    ctx.cleanup.push(rule.id);
    const { run, effects } = await H.runAndWait(t, rule.id);
    const pcId = effects[0]?.result?.type === 'PENDING_CHANGE' ? effects[0].result.id : null;
    T.sam = await rt.login('sam');
    const ap = pcId ? await rt.approvals.approve(T.sam, pcId) : { status: 0 };
    const proms = await H.promisesOf(t, cust.c.id);
    const p = proms[0] ? (await rt.api('GET', `/api/promises/${proms[0].id}`, { token: t })).json : null;
    const people = (p?.assignees || []).flatMap((a) => (a.people || []).map((x) => x.userId));
    await rt.automation.disable(t, rule.id);
    return { ok: !!pcId && ap.status === 200 && proms.length === 1 && Number(p?.amount) === 7300 && people.includes(coas.id) && people.includes(U.sam.id),
      actual: { run: run && [run.id, run.status], effect: effects[0] && [effects[0].status, effects[0].result, effects[0].warnings], approve: [ap.status, ap.json?.status, ap.json?.message],
        promises: proms.map((x) => [x.id, x.amount, x.status]), assignees: p?.assignees, coas: coas.id, sam: U.sam.id },
      evidence: `GET /api/promises/${proms[0]?.id}` };
  });

  await K({ id: 'AUT-API-152', prd: 'A5', title: 'A held outcome is done once: running the ONCE_PER_RECORD rule again while (and after) its change is rejected makes no second pending change',
    steps: 'South customer with invoice 6400; admin MANUAL rule CREATE_PROMISE FIXED 6000 ONCE_PER_RECORD; run 1 -> held; run 2; sam rejects; run 3; count pending changes for the customer',
    expected: 'DESIGN §5.10 ("A Held outcome is recorded as effect DONE, result_type PENDING_CHANGE, with its dedupe key"): run 1 done 1 (PENDING_CHANGE); runs 2 and 3 done 0 / skipped 1; exactly one pending-change row for the customer (REJECTED after the reject); no promise' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [6400] });
    const inv = cust.invoices[0];
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIXED', value: 6000 }, repeat: 'ONCE_PER_RECORD', onlyIfNoOpenPromise: false })] });
    ctx.cleanup.push(rule.id);
    const r1 = await H.runAndWait(t, rule.id);
    const r2 = await H.runAndWait(t, rule.id);
    const pcId = r1.effects[0]?.result?.type === 'PENDING_CHANGE' ? r1.effects[0].result.id : null;
    T.sam = await rt.login('sam');
    const rej = pcId ? await rt.approvals.reject(T.sam, pcId) : { status: 0 };
    const r3 = await H.runAndWait(t, rule.id);
    const pcs = rt.sql(`select id, status from pending_changes where customer_id=${cust.c.id} order by id`).map((r) => r.join('/'));
    const proms = await H.promisesOf(t, cust.c.id);
    await rt.automation.disable(t, rule.id);
    const c = (x) => x.run && `${x.run.status}:d${x.run.done}/s${x.run.skipped}/f${x.run.failed}`;
    return { ok: !!pcId && r1.run?.done === 1 && r2.run?.done === 0 && r2.run?.skipped === 1 && rej.status === 200 && r3.run?.done === 0 && r3.run?.skipped === 1
        && pcs.length === 1 && /REJECTED/.test(pcs[0]) && proms.length === 0,
      actual: { runs: [c(r1), c(r2), c(r3)], pc: pcId, reject: [rej.status, rej.json?.status], pendingRows: pcs, promises: proms.length,
        effects2: r2.effects.map((e) => [e.status, e.message]), effects3: r3.effects.map((e) => [e.status, e.message]) } };
  });

  await K({ id: 'AUT-API-153', prd: 'A5', title: 'Every automation event the consumer wrote in this run has its counters set (MIG-R2-1 column defaults) and none is left unprocessed',
    steps: 'SQL on e2e_automation: column defaults of automation_events.depth/claims; count rows with null counters; count rows not DONE/SKIPPED/CANCELLED older than 2 minutes',
    expected: 'TRIAGE r2 B1 (MIG-R2-1): depth and claims default 0; no null counters; nothing stuck',
    kind: 'API', feature: 'Durable execution' },
  async () => {
    const defs = rt.sql("select column_name, coalesce(column_default,'') from information_schema.columns where table_name='automation_events' and column_name in ('depth','claims','attempts') order by 1").map((r) => r.join('='));
    const nulls = H.countRows('automation_events', 'depth is null or claims is null or attempts is null');
    const statuses = rt.sql('select status, count(*) from automation_events group by 1 order by 1').map((r) => r.join('='));
    const stuck = H.countRows('automation_events', "status <> 'DONE' and coalesce(scheduled_for, occurred_at) < now() - interval '2 minutes'");
    const dflt = (c) => (defs.find((d) => d.startsWith(`${c}=`)) || '').split('=')[1] || '';
    return { ok: /^0/.test(dflt('depth')) && /^0/.test(dflt('claims')) && nulls === 0 && stuck === 0,
      actual: { defaults: defs, nullCounters: nulls, statuses, stuckOver2Min: stuck } };
  });
}

/** UI: the approver opens the pending change an automation proposed. */
async function r3UiCases(ctx) {
  const { R, t, w } = ctx;
  const S = w.regions.SOUTH.id; const U = w.users;
  let n = 0;
  await H.kase(R, { id: 'AUT-UI-22', kind: 'UI', feature: 'Automation UI', prd: 'B2',
    title: 'The approver opens a change an automation proposed: "Raised by" links the rule, and the Changes tab names the invoice by number',
    steps: 'South customer (Collection POC sam) with invoice 6600; admin MANUAL rule CREATE_PROMISE FIELD Invoice.Balance; run -> held; sam opens /approvals/{pc}',
    expected: 'DESIGN §7 approval detail + TRIAGE r2 APP-R2-1: "Raised by" shows "Rule #<id>"; the Changes tab shows a row "Invoices" with the invoice number, not "#<id>" or JSON; Approve is offered to sam' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [6600] });
    const inv = cust.invoices[0];
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' } })] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id);
    const pcId = effects[0]?.result?.type === 'PENDING_CHANGE' ? effects[0].result.id : null;
    await rt.automation.disable(t, rule.id);
    if (!pcId) return { status: 'BLOCKED', actual: { effects } };
    const app = await rt.openApp({ token: await rt.login('sam'), height: 1400 });
    let shot = '';
    try {
      await rt.go(app.page, `/approvals/${pcId}`, 5000);
      const lines = await rt.screenText(app.page);
      shot = await rt.shot(app.page, R.shotsDir, `AUT-UI-22-${++n}`);
      const s = lines.join(' | ');
      return { ok: s.includes(`Rule #${rule.id}`) && lines.includes('Invoices') && s.includes(inv.invoiceNumber) && !lines.includes(`#${inv.id}`) && /Approve/.test(s),
        actual: { pc: pcId, ruleLink: s.includes(`Rule #${rule.id}`), invoicesRow: lines.includes('Invoices'), invoiceNumberShown: s.includes(inv.invoiceNumber), rawIdShown: lines.includes(`#${inv.id}`), screen: H.short(s, 900) },
        screenshot: shot, evidence: `apiErrors=${JSON.stringify(app.apiErrors).slice(0, 300)}` };
    } finally { await app.close(); }
  });
}

module.exports = { r3Cases, r3UiCases };
