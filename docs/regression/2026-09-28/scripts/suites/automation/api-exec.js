// A5: run now (now / at a time / chosen records), schedules (daily, weekly), preview, run history, health,
// failures that never break anything.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const SCHED_REF = 'backend/src/main/java/com/geneinvoice/automation/schedule/ScheduleTicker.java:69 (ruleRepository.findDueSchedules loads whole AutomationRule entities, whose @Lob actionsJson/conditionJson cannot be read outside a transaction on PostgreSQL) + backend/src/main/java/com/geneinvoice/automation/rule/AutomationRuleRepository.java:94-102';

/** Creates one set of schedule rules whose slot is `minutes` ahead (whole minutes, UTC). */
async function makeScheduleSet(ctx, minutes) {
  const { t, w } = ctx;
  const cust = await H.mkCustomer(t, { regionId: w.regions.NORTH.id, collector: w.users.nora.id, amounts: [1000] });
  const inv = cust.invoices[0];
  const slot = H.hhmmAhead(minutes);
  const slotIst = H.hhmmAhead(minutes, 330);
  const today = H.weekdayAhead(minutes);
  const other = H.WEEKDAYS[(H.WEEKDAYS.indexOf(today) + 1) % 7];
  const mk = async (label, trigger, enabled = true) => {
    const r = await H.mustRule(t, { name: rt.uniq(`Sched-${label}`), enabled, trigger: { entityType: 'INVOICE', catchUp: 'ONE', ...trigger },
      condition: H.and(`id:eq:${inv.id}`), actions: [rt.action.task({ repeat: 'EVERY_TIME', onlyIfNoOpenTask: false, title: `Sched ${label} {{Invoice.Number}} {{Today}}` })] });
    ctx.cleanup.push(r.id);
    return r;
  };
  const [hh, mm] = slot.split(':').map(Number);
  const slotAt = new Date(); slotAt.setUTCHours(hh, mm, 0, 0);
  if (slotAt.getTime() < Date.now() - 60000) slotAt.setUTCDate(slotAt.getUTCDate() + 1);
  const set = {
    slot, slotIst, today, other, inv, slotAt: slotAt.getTime(),
    daily: await mk('daily', { kind: 'SCHEDULE_DAILY', time: slot, zone: 'UTC' }),
    weekly: await mk('weekly', { kind: 'SCHEDULE_WEEKLY', time: slot, day: today, zone: 'UTC' }),
    weeklyOther: await mk('weekly-other', { kind: 'SCHEDULE_WEEKLY', time: slot, day: other, zone: 'UTC' }),
    disabled: await mk('disabled', { kind: 'SCHEDULE_DAILY', time: slot, zone: 'UTC' }, false),
    ist: await mk('ist', { kind: 'SCHEDULE_DAILY', time: slotIst, zone: '+05:30' }),
  };
  console.log(`[sched] slot ${slot} UTC (${slotIst} +05:30), today ${today}, other ${other}`);
  return set;
}

async function startSchedules(ctx) {
  ctx.sched = await makeScheduleSet(ctx, 2);
}

/** Waits for a set's slot (+ a tick and a drain) and describes each rule. */
async function observe(ctx, s) {
  const t = ctx.t;
  const runsOf = async (id) => (await rt.automation.runs(t, id)).json?.content || [];
  const waitUntil = s.slotAt + 75000;
  await rt.waitFor(async () => {
    const all = [await runsOf(s.daily.id), await runsOf(s.weekly.id), await runsOf(s.ist.id)];
    return all.every((x) => x.length && x.every((r) => !/QUEUED|RUNNING/.test(r.status))) ? true : null;
  }, { timeoutMs: Math.max(5000, waitUntil - Date.now()), intervalMs: 3000 });
  if (Date.now() < waitUntil) await rt.sleep(waitUntil - Date.now());
  const d = async (rule) => {
    const runs = await runsOf(rule.id);
    const g = (await rt.automation.get(t, rule.id)).json;
    const tasks = await H.tasksOfRule(t, rule.id);
    return { rule: rule.id, runs: runs.map((r) => ({ id: r.id, trigger: r.triggerType, status: r.status, scheduledFor: r.scheduledFor, matched: r.matched, done: r.done })), nextRunAt: g?.nextRunAt, tasks: tasks.length };
  };
  const out = { slot: s.slot, slotIst: s.slotIst, today: s.today, other: s.other, observedAt: new Date().toISOString(),
    daily: await d(s.daily), weekly: await d(s.weekly), weeklyOther: await d(s.weeklyOther), disabled: await d(s.disabled), ist: await d(s.ist) };
  for (const r of [s.daily, s.weekly, s.weeklyOther, s.ist]) await rt.automation.disable(t, r.id);
  return out;
}

async function checkSchedules(ctx) {
  const { R } = ctx;
  const o1 = await observe(ctx, ctx.sched);
  const firedOk = (x, slot) => x.runs.length === 1 && x.runs[0].trigger === 'SCHEDULED' && x.runs[0].status === 'SUCCEEDED' && x.runs[0].matched === 1 && x.tasks === 1 && new RegExp(`T${slot}:00`).test(x.runs[0].scheduledFor);
  const tests = {
    'AUT-API-80': (o) => firedOk(o.daily, o.slot) && o.daily.nextRunAt > o.observedAt,
    'AUT-API-81': (o) => { const days = o.weekly.nextRunAt ? (Date.parse(o.weekly.nextRunAt) - Date.parse(o.observedAt)) / 86400000 : 0; return firedOk(o.weekly, o.slot) && days > 6 && days < 7.1; },
    'AUT-API-84': (o) => firedOk(o.ist, o.slot),
  };
  const anyFail = Object.values(tests).some((f) => !f(o1));
  let o2 = null;
  if (anyFail) {
    const minutes = 60 - new Date().getUTCSeconds() >= 45 ? 1 : 2;
    o2 = await observe(ctx, await makeScheduleSet(ctx, minutes));
  }
  const evidence = `backend.log: ${H.logGrep('automation.tick.failed', 2).join(' || ')}`;
  const meta = {
    'AUT-API-80': { title: 'A daily schedule fires at its time and acts on the matching records', steps: 'SCHEDULE_DAILY at a UTC time 1-2 min ahead, zone UTC, condition id:eq:<invoice>, CREATE_TASK EVERY_TIME; wait past the slot + a 30 s tick + a drain',
      expected: 'PRD A1 daily schedule / DESIGN §5.10.8: one SCHEDULED run (scheduledFor = the slot) SUCCEEDED, matched 1, one task; nextRunAt moves to tomorrow' },
    'AUT-API-81': { title: 'A weekly schedule fires on its weekday at its time', steps: 'SCHEDULE_WEEKLY day = today (UTC), time 1-2 min ahead; wait past the slot',
      expected: 'PRD A1 weekly schedule: one SCHEDULED run, one task; nextRunAt one week later' },
    'AUT-API-84': { title: 'A schedule in another time zone fires at that zone\'s wall-clock time', steps: 'SCHEDULE_DAILY zone +05:30 at the IST wall-clock time of the same slot; wait past the slot',
      expected: 'DESIGN §6.14 zone: one SCHEDULED run at the same instant as the UTC rule' },
  };
  const brief = (o, k) => H.short({ slot: o.slot, observedAt: o.observedAt, ...(k === 'AUT-API-80' ? o.daily : k === 'AUT-API-81' ? o.weekly : o.ist) }, 700);
  const passed = {};
  for (const [id, f] of Object.entries(tests)) {
    const a = f(o1); const b = o2 ? f(o2) : null;
    const status = a && b !== false ? 'PASS' : (!a && b) || (a && b === false) ? 'NOT_TESTED' : 'FAIL';
    passed[id] = status === 'PASS';
    R.rec({ id, feature: 'Schedules', kind: 'API', prd: 'A1', ...meta[id], status, severity: status === 'FAIL' ? 'high' : '',
      actual: o2 ? `Attempt 1: ${brief(o1, id)} | Attempt 2: ${brief(o2, id)}` : brief(o1, id),
      evidence: status === 'FAIL' ? evidence : '', codeRef: status === 'FAIL' ? SCHED_REF : '' });
  }
  // Negative controls: meaningful only if the positive daily schedule fires.
  const neg = (id, title, steps, expected, test) => {
    if (!passed['AUT-API-80']) {
      R.rec({ id, feature: 'Schedules', kind: 'API', prd: 'A1', title, steps, expected, status: 'BLOCKED', actual: `No run, but the positive control AUT-API-80 never fires either. ${H.short(test.detail(o1), 400)}`, evidence: 'blocked by AUT-R1-2 (schedules never fire)' });
      return;
    }
    R.rec({ id, feature: 'Schedules', kind: 'API', prd: 'A1', title, steps, expected, ok: test.ok(o1) && (!o2 || test.ok(o2)), actual: H.short(test.detail(o1), 800) });
  };
  neg('AUT-API-82', 'A weekly schedule does not fire on other days, and its next run is on its weekday', 'SCHEDULE_WEEKLY day = tomorrow (UTC), same time; wait past today\'s slot',
    'DESIGN §5.10.8 "A weekly rule ticks only on its configured weekday": no run; nextRunAt on that weekday at the slot',
    { ok: (o) => o.weeklyOther.runs.length === 0 && o.weeklyOther.tasks === 0 && H.WEEKDAYS[new Date(o.weeklyOther.nextRunAt).getUTCDay()] === o.other, detail: (o) => ({ other: o.other, ...o.weeklyOther }) });
  neg('AUT-API-83', 'A disabled schedule never fires', 'SCHEDULE_DAILY at the same slot created with enabled=false; wait past the slot',
    'DESIGN §5.10.8: no run, no nextRunAt', { ok: (o) => o.disabled.runs.length === 0 && o.disabled.tasks === 0 && !o.disabled.nextRunAt, detail: (o) => o.disabled });
  // The weekly next-run weekday is computed at save time and can be checked without a tick.
  R.rec({ id: 'AUT-API-85', feature: 'Schedules', kind: 'API', prd: 'A1', title: 'A schedule\'s next run is computed on save: today\'s slot for daily, the chosen weekday for weekly',
    steps: 'the rules created for AUT-API-80..84, GET /rules/{id} right after save', expected: 'DESIGN §5.10.8: nextRunAt = the next slot in the rule\'s zone; weekly on its weekday; disabled -> null',
    ok: [ctx.sched.daily, ctx.sched.weekly, ctx.sched.ist].every((r) => new RegExp(`T${ctx.sched.slot}:00`).test(r.nextRunAt || '')) && new RegExp(`T${ctx.sched.slot}:00`).test(ctx.sched.weeklyOther.nextRunAt || '')
      && H.WEEKDAYS[new Date(ctx.sched.weeklyOther.nextRunAt).getUTCDay()] === ctx.sched.other && !ctx.sched.disabled.nextRunAt,
    actual: H.short({ daily: ctx.sched.daily.nextRunAt, weekly: ctx.sched.weekly.nextRunAt, ist: ctx.sched.ist.nextRunAt, weeklyOther: ctx.sched.weeklyOther.nextRunAt, disabled: ctx.sched.disabled.nextRunAt }) });
}

async function execCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const S = w.regions.SOUTH.id;
  const U = w.users;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Run now', prd: 'A5', ...meta }, fn, o);

  await K({ id: 'AUT-API-60', title: 'Run now queues a MANUAL run that the consumer executes; it is recorded and audited',
    steps: 'MANUAL INVOICE rule on one invoice with CREATE_TASK; POST /rules/{id}/run {}; poll GET /runs/{runId}; GET /api/audit?entityType=RULE',
    expected: 'DESIGN §6.14: 202 {runId, eventId, status QUEUED, scheduledFor}; run becomes SUCCEEDED with triggerType MANUAL, requestedBy admin, matched 1, done 1; audit RULE_RUN_REQUESTED; rule lastRun set' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1000] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.task({ title: 'Run now {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const { req, run } = await H.runAndWait(t, rule.id);
    const au = await rt.api('GET', '/api/audit', { token: t, query: { entityType: 'RULE', entityId: rule.id } });
    const g = (await rt.automation.get(t, rule.id)).json;
    return { ok: req.status === 202 && req.json.status === 'QUEUED' && req.json.runId && req.json.eventId && run?.status === 'SUCCEEDED' && run.triggerType === 'MANUAL'
      && run.requestedBy?.id === ctx.adminId && run.matched === 1 && run.done === 1 && (au.json || []).some((x) => x.action === 'RULE_RUN_REQUESTED') && g?.lastRun?.id === run.id,
      actual: { request: [req.status, req.json], run: run && { id: run.id, status: run.status, trigger: run.triggerType, by: run.requestedBy, matched: run.matched, done: run.done }, audit: (au.json || []).map((x) => x.action), lastRun: g?.lastRun } };
  });

  await K({ id: 'AUT-API-61', title: 'Run now on chosen records acts only on those; more than 50 ids is refused',
    steps: 'customer with 3 invoices; rule condition customerId:eq:C; POST /run {recordIds:[i1,i3]}; POST /run with 51 ids',
    expected: 'DESIGN §5.10.9: tasks on i1 and i3 only; 51 ids -> 400 fieldErrors.recordIds' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100, 200, 300] });
    const [i1, i2, i3] = cust.invoices;
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`customerId:eq:${cust.c.id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id, { recordIds: [i1.id, i3.id] });
    const ids = effects.map((e) => e.target.id).sort((a, b) => a - b);
    const big = await rt.automation.run(t, rule.id, { recordIds: Array.from({ length: 51 }, (_, k) => 1000000 + k) });
    return { ok: JSON.stringify(ids) === JSON.stringify([i1.id, i3.id].sort((a, b) => a - b)) && big.status === 400 && !!big.json?.fieldErrors?.recordIds,
      actual: { actedOn: ids, notI2: i2.id, fiftyOne: [big.status, big.json?.fieldErrors] } };
  });

  await K({ id: 'AUT-API-62', title: 'Run now at a time waits until then; an identical request while queued returns the same run; bad times are refused',
    steps: 'POST /run {runAt: now+25s}; again with the same runAt; POST /run {runAt: same, recordIds:[i]}; check QUEUED at +8 s; wait; POST /run {runAt: 1 h ago}; POST /run {runAt: +100 days}',
    expected: 'DESIGN §5.10.9: 202 QUEUED scheduledFor=runAt; identical -> 200 same runId; different recordIds -> 202 new run; still QUEUED before runAt, SUCCEEDED after with startedAt >= runAt; past / >90 days -> 400 fieldErrors.runAt' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100] });
    const inv = cust.invoices[0];
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`), actions: [rt.action.task({ repeat: 'EVERY_TIME', onlyIfNoOpenTask: false })] });
    ctx.cleanup.push(rule.id);
    const runAt = new Date(Date.now() + 25000).toISOString();
    const a = await rt.automation.run(t, rule.id, { runAt });
    const b = await rt.automation.run(t, rule.id, { runAt });
    const c = await rt.automation.run(t, rule.id, { runAt, recordIds: [inv.id] });
    await rt.sleep(8000);
    const mid = (await rt.automation.runById(t, a.json?.runId)).json;
    const done = await rt.waitForRun(t, a.json?.runId, { timeoutMs: 60000 });
    const past = await rt.automation.run(t, rule.id, { runAt: new Date(Date.now() - 3600000).toISOString() });
    const far = await rt.automation.run(t, rule.id, { runAt: new Date(Date.now() + 100 * 86400000).toISOString() });
    const ok = a.status === 202 && a.json.status === 'QUEUED' && Date.parse(a.json.scheduledFor) === Date.parse(runAt) && b.status === 200 && b.json.runId === a.json.runId
      && c.status === 202 && c.json.runId !== a.json.runId && mid?.status === 'QUEUED' && done?.status === 'SUCCEEDED' && Date.parse(done.startedAt) >= Date.parse(runAt)
      && past.status === 400 && far.status === 400;
    return { ok, actual: { first: [a.status, a.json], identical: [b.status, b.json?.runId], otherRecords: [c.status, c.json?.runId], at8s: mid?.status, final: done && [done.status, done.startedAt], past: [past.status, past.json?.fieldErrors], far: [far.status, far.json?.fieldErrors] } };
  });

  await K({ id: 'AUT-API-63', title: 'A disabled rule can still be run by hand',
    steps: 'MANUAL rule created enabled=false on one invoice; POST /run',
    expected: 'DESIGN §5.10.9 "It may be disabled": 202 and the run SUCCEEDED with one task' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100] });
    const rule = await H.mustRule(t, { enabled: false, trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { req, run } = await H.runAndWait(t, rule.id);
    const tasks = await H.tasksOfRule(t, rule.id);
    return { ok: req.status === 202 && run?.status === 'SUCCEEDED' && tasks.length === 1, actual: { req: req.status, run: run?.status, tasks: tasks.length } };
  });

  await K({ id: 'AUT-API-64', title: 'Run now honours the regions being viewed (regionIds)', prd: 'A5',
    steps: 'admin unrestricted rule condition customerId:in:<North C>,<South D>; POST /run?regionIds=<NORTH>',
    expected: 'DESIGN §5.10.9: the run acts on C\'s invoice only' },
  async () => {
    const C = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100] });
    const D = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [100] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`customerId:in:${C.c.id},${D.c.id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { effects, req } = await H.runAndWait(t, rule.id, {}, { query: { regionIds: N } });
    const ids = effects.map((e) => e.target.id);
    return { ok: req.status === 202 && JSON.stringify(ids) === JSON.stringify([C.invoices[0].id]), actual: { actedOn: ids, north: C.invoices[0].id, south: D.invoices[0].id } };
  });

  await K({ id: 'AUT-API-65', title: 'A run stops at "most records per run" and says it was truncated',
    steps: 'rule maxRecordsPerRun 2, condition matching 3 invoices; run now',
    expected: 'DESIGN §5.10.6: matched 2, truncated true, 2 tasks' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100, 200, 300] });
    const rule = await H.mustRule(t, { trigger: H.manual(), maxRecordsPerRun: 2, condition: H.and(`customerId:eq:${cust.c.id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { run } = await H.runAndWait(t, rule.id);
    const tasks = await H.tasksOfRule(t, rule.id);
    return { ok: run?.matched === 2 && run?.truncated === true && tasks.length === 2, actual: { run: run && [run.status, run.matched, run.done, run.truncated], tasks: tasks.length } };
  });

  await K({ id: 'AUT-API-66', title: 'Only the owner runs a rule; an administrator must confirm taking it over; others get 403',
    steps: 'nora\'s North MANUAL rule; sam POST /run; admin POST /run {}; admin POST /run {confirmOwnershipChange:true}',
    expected: 'DESIGN §5.10.7: sam 403; admin 400 fieldErrors.confirmOwnershipChange; then 202 and the rule owner becomes admin' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100] });
    const nr = await H.mustRule(w.tokens.nora, { trigger: H.manual(), regionIds: [N], condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(nr.id);
    const bySam = await rt.automation.run(w.tokens.sam, nr.id, {});
    const noConfirm = await rt.automation.run(t, nr.id, {});
    const yes = await rt.automation.run(t, nr.id, { confirmOwnershipChange: true });
    const owner = (await rt.automation.get(t, nr.id)).json?.owner?.id;
    if (yes.json?.runId) await rt.waitForRun(t, yes.json.runId);
    return { ok: bySam.status === 403 && noConfirm.status === 400 && !!noConfirm.json?.fieldErrors?.confirmOwnershipChange && yes.status === 202 && owner === ctx.adminId,
      actual: { sam: bySam.status, adminNoConfirm: [noConfirm.status, noConfirm.json?.fieldErrors], adminConfirm: yes.status, ownerAfter: owner } };
  });

  await K({ id: 'AUT-API-67', title: 'Preview shows the matches and the rendered actions, and writes nothing', feature: 'Preview',
    steps: 'customer with invoices 1000 and 4000; POST /rules/preview {rule: MANUAL INVOICE AND(customerId:eq:C,total:gt:2000) with CREATE_TASK "P {{Invoice.Number}} {{Invoice.Balance}}" + SEND_EMAIL to [SUCCESS_POC@CUSTOMER] (nobody holds it), recordId:<4000>}; count rows of tasks, emails, promises, disputes, automation_runs, automation_effects, automation_events, pending_changes before/after',
    expected: 'DESIGN §5.10.9/§6.14: matchCount 1, sample [4000], record.matches true, task rendered title, email problem "No recipients…"; no row written anywhere' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1000, 4000] });
    const big = cust.invoices[1];
    const tables = ['tasks', 'emails', 'payment_promises', 'disputes', 'automation_runs', 'automation_effects', 'automation_events', 'pending_changes', 'automation_rules'];
    const existing = new Set(rt.sql("select table_name from information_schema.tables where table_schema='public'").map((r) => r[0]));
    const use = tables.filter((x) => existing.has(x));
    const before = Object.fromEntries(use.map((x) => [x, H.countRows(x)]));
    const body = rt.rule({ trigger: H.manual(), condition: H.and(`customerId:eq:${cust.c.id}`, 'total:gt:2000'),
      actions: [rt.action.task({ title: 'P {{Invoice.Number}} {{Invoice.Balance}}' }), rt.action.email({ from: null, to: [rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER')], subject: 'S {{Customer.Name}}', body: 'b' })] });
    const pv = await rt.automation.preview(t, { rule: body, recordId: big.id, sampleSize: 10 });
    const after = Object.fromEntries(use.map((x) => [x, H.countRows(x)]));
    const acts = pv.json?.record?.actions || [];
    const task = acts.find((a) => a.type === 'CREATE_TASK'); const mail = acts.find((a) => a.type === 'SEND_EMAIL');
    const mailProblems = [...(mail?.problems || []), ...(mail?.email?.problems || [])];
    const ok = pv.status === 200 && pv.json.matchCount === 1 && pv.json.sample?.[0]?.id === big.id && pv.json.record?.matches === true
      && task?.rendered?.title === `P ${big.invoiceNumber} ₹4,000.00` && mailProblems.length > 0 && JSON.stringify(before) === JSON.stringify(after);
    return { ok, actual: { status: pv.status, matchCount: pv.json?.matchCount, sample: pv.json?.sample?.map((x) => x.id), matches: pv.json?.record?.matches, taskTitle: task?.rendered?.title, mailProblems, before, after } };
  });

  await K({ id: 'AUT-API-68', title: 'Preview "as the owner" is for the owner or an administrator only', feature: 'Preview',
    steps: 'admin-owned saved rule; GET /rules/{id}/preview?evaluateAs=OWNER as nora (AUTOMATION_VIEW, not owner/admin), as admin; nora with evaluateAs=CALLER',
    expected: 'DESIGN §5.10.9: nora OWNER -> 403; admin OWNER -> 200 evaluatedAs OWNER; nora CALLER -> 200 evaluatedAs CALLER' },
  async () => {
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and('total:gt:0'), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const a = await rt.api('GET', `/api/automation/rules/${rule.id}/preview`, { token: w.tokens.nora, query: { evaluateAs: 'OWNER' } });
    const b = await rt.api('GET', `/api/automation/rules/${rule.id}/preview`, { token: t, query: { evaluateAs: 'OWNER' } });
    const c = await rt.api('GET', `/api/automation/rules/${rule.id}/preview`, { token: w.tokens.nora, query: { evaluateAs: 'CALLER' } });
    return { ok: a.status === 403 && b.status === 200 && b.json.evaluatedAs === 'OWNER' && c.status === 200 && c.json.evaluatedAs === 'CALLER' && c.json.matchCount < b.json.matchCount,
      actual: { noraOwner: a.status, adminOwner: [b.status, b.json?.evaluatedAs, b.json?.matchCount], noraCaller: [c.status, c.json?.evaluatedAs, c.json?.matchCount] } };
  });

  await K({ id: 'AUT-API-69', title: 'Run history: runs list (newest first, filterable), run detail and its effects', feature: 'Run history',
    steps: 'rule on 2 invoices (CREATE_TASK EVERY_TIME); run now twice; GET /rules/{id}/runs; ?filter=status:eq:SUCCEEDED; GET /runs/{id}; GET /runs/{id}/effects',
    expected: 'DESIGN §6.14: 2 runs, createdAt desc; filter works; detail counters matched 2 done 2; effects 2 DONE with target and result links /tasks/{id}' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100, 200] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`customerId:eq:${cust.c.id}`), actions: [rt.action.task({ repeat: 'EVERY_TIME', onlyIfNoOpenTask: false })] });
    ctx.cleanup.push(rule.id);
    const x1 = await H.runAndWait(t, rule.id); const x2 = await H.runAndWait(t, rule.id);
    const runs = (await rt.automation.runs(t, rule.id)).json?.content || [];
    const f = (await rt.automation.runs(t, rule.id, { filter: 'status:eq:SUCCEEDED' })).json?.content || [];
    const detail = (await rt.automation.runById(t, x2.run?.id)).json;
    const eff = x2.effects;
    return { ok: runs.length === 2 && runs[0].id === x2.run?.id && f.length === 2 && detail?.matched === 2 && detail?.done === 2 && eff.length === 2
      && eff.every((e) => e.status === 'DONE' && /^\/tasks\/\d+$/.test(e.result?.link || '') && /^\/invoices\/\d+$/.test(e.target?.link || '')),
      actual: { runs: runs.map((r) => [r.id, r.status, r.createdAt]), filtered: f.length, detail: detail && [detail.matched, detail.done, detail.skipped, detail.failed], effects: eff.map((e) => [e.status, e.target?.link, e.result?.link]) } };
  });

  await K({ id: 'AUT-API-70', title: 'Automation health is for administrators', feature: 'Run history',
    steps: 'GET /api/automation/health as admin and as nora',
    expected: 'DESIGN §6.14: admin 200 {consumerEnabled, pending, processing, retrying, dead, deadEvents}; nora 403' },
  async () => {
    const a = await rt.automation.health(t); const b = await rt.automation.health(w.tokens.nora);
    return { ok: a.status === 200 && a.json.consumerEnabled === true && 'pending' in a.json && Array.isArray(a.json.deadEvents) && b.status === 403, actual: { admin: [a.status, a.json], nora: b.status } };
  });

  await K({ id: 'AUT-API-71', title: 'An action that cannot be done fails for that record only; the other action still happens and nothing is retried', feature: 'Durable execution',
    steps: 'customer WITHOUT a Collection POC, invoice 900 (sales POC nora); rule: CREATE_PROMISE (needs a Collection POC) + CREATE_TASK; run now; check the outbox event',
    expected: 'DESIGN §5.10.5/§5.10.6: promise effect FAILED (permanent, "No Collection POC"), task DONE, run PARTIAL, event DONE (not retried)' },
  async () => {
    const c = await rt.createCustomer(t, { name: rt.uniq('NoPoc'), regionId: N, paymentTerm: 'NET_30' });
    const inv = (await rt.createInvoice(t, { customerId: c.id, amount: 900, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id })).json;
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`), actions: [rt.action.promise({ amount: { source: 'FIXED', value: 100 } }), rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { req, run, effects } = await H.runAndWait(t, rule.id);
    const ev = rt.sql(`select status, attempts from automation_events where id=${Number(req.json?.eventId)}`)[0];
    const st = Object.fromEntries(effects.map((e) => [e.actionType, [e.status, e.message]]));
    return { ok: st.CREATE_PROMISE?.[0] === 'FAILED' && st.CREATE_TASK?.[0] === 'DONE' && run?.status === 'PARTIAL' && ev?.[0] === 'DONE' && ev?.[1] === '0',
      actual: { run: run && [run.status, run.done, run.failed], effects: st, event: ev } };
  });
}

module.exports = { startSchedules, checkSchedules, execCases };
