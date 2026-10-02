// A5 durability (nothing is lost if the consumer is down: kill -9 the backend right after saves, restart it on the
// same database, assert each rule acted exactly once) and B3-style rule evaluation as of a date.
const rt = require('../../lib.js');
const H = require('./helpers.js');

async function asOfCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id;
  const cust = await H.mkCustomer(t, { regionId: N, collector: w.users.nora.id, amounts: [5000] });
  const inv = cust.invoices[0];
  const body = { trigger: H.manual(), condition: H.and(`customerId:eq:${cust.c.id}`, 'status:eq:UNPAID'), actions: [rt.action.task({ title: 'AsOf {{Invoice.Number}}' })] };
  const old = await H.mustRule(t, body);
  ctx.cleanup.push(old.id);
  rt.ageWorld(3); // everything so far happened 3 days ago
  await H.payInvoice(t, cust, inv, 5000); // now PAID: no longer matches
  const newer = await H.mustRule(t, { ...body, name: rt.uniq('after-ageing') });
  ctx.cleanup.push(newer.id);
  const past = rt.day(-1);

  await H.kase(R, { id: 'AUT-API-130', feature: 'As of a date', kind: 'API', prd: 'B3', title: 'Rule preview can be asked as of a date: it counts what matched then and shows no actions',
    steps: `invoice UNPAID 3 days ago (ageWorld 3), paid in full now; GET /rules/{id}/preview?asOf=${past} and without asOf; POST /rules/preview?asOf=${past}`,
    expected: 'PRD B3 + DESIGN §5.10.9: as of the past date matchCount 1, record.actions null, asOfNote "Actions are shown only for today\'s view", X-View-As-Of header = date; live matchCount 0' },
  async () => {
    const a = await rt.api('GET', `/api/automation/rules/${old.id}/preview`, { token: t, query: { asOf: past } });
    const b = await rt.api('GET', `/api/automation/rules/${old.id}/preview`, { token: t });
    const c = await rt.automation.preview(t, { rule: rt.rule(body), recordId: inv.id }, { asOf: past });
    return { ok: a.status === 200 && a.json.matchCount === 1 && a.json.asOf === past && a.headers['x-view-as-of'] === past && b.json?.matchCount === 0
      && c.status === 200 && c.json.matchCount === 1 && c.json.record?.actions == null && /today/i.test(c.json.asOfNote || ''),
      actual: { pastSaved: [a.status, a.json?.matchCount, a.json?.asOf, a.headers['x-view-as-of']], live: b.json?.matchCount, pastBody: [c.status, c.json?.matchCount, c.json?.record?.actions, c.json?.asOfNote] } };
  }, { repeat: false });

  await H.kase(R, { id: 'AUT-API-131', feature: 'As of a date', kind: 'API', prd: 'B3', title: 'The rules list and a rule can be read as of a date',
    steps: `GET /api/automation/rules?asOf=${past}&filter=name:contains:<old rule>, and the same for the rule created after ageing; GET /rules/<new>?asOf=${past}`,
    expected: 'DESIGN §5.10.10: the list as of the date contains the older rule only; the newer rule -> 404 as of that date' },
  async () => {
    const l = await rt.api('GET', '/api/automation/rules', { token: t, query: { asOf: past, size: 50, sort: 'name,asc', filter: `name:contains:${old.name}` } });
    const l2 = await rt.api('GET', '/api/automation/rules', { token: t, query: { asOf: past, size: 50, filter: `name:contains:${newer.name}` } });
    const ids = [...(l.json?.content || []), ...(l2.json?.content || [])].map((x) => x.id);
    const g = await rt.automation.get(t, newer.id, { asOf: past });
    return { ok: l.status === 200 && ids.includes(old.id) && !ids.includes(newer.id) && g.status === 404, actual: { list: [l.status, `old in: ${ids.includes(old.id)}`, `new in: ${ids.includes(newer.id)}`, H.short(l.json?.message, 120)], newerAsOf: g.status } };
  }, { repeat: false });

  await H.kase(R, { id: 'AUT-API-132', feature: 'As of a date', kind: 'API', prd: 'B3', title: 'Changing data is refused while viewing a past date (run now included)',
    steps: `POST /rules/{id}/run?asOf=${past}; POST /rules?asOf=${past}`,
    expected: 'DESIGN §6.0/§6.17: 400 AS_OF_READ_ONLY' },
  async () => {
    const a = await rt.api('POST', `/api/automation/rules/${old.id}/run`, { token: t, body: {}, query: { asOf: past } });
    const b = await rt.api('POST', '/api/automation/rules', { token: t, body: rt.rule(body), query: { asOf: past } });
    return { ok: a.status === 400 && a.json?.error === 'AS_OF_READ_ONLY' && b.status === 400 && b.json?.error === 'AS_OF_READ_ONLY', actual: { run: [a.status, a.json?.error], create: [b.status, b.json?.error] } };
  }, { repeat: false });
}

async function durabilityScenario(ctx, attempt) {
  const { t, w } = ctx;
  const N = w.regions.NORTH.id;
  const cust = await H.mkCustomer(t, { regionId: N, collector: w.users.nora.id, amounts: [1000, 2000, 3000] });
  const [iManual, iEntity, iSched] = cust.invoices;
  const task = (title) => [rt.action.task({ repeat: 'EVERY_TIME', onlyIfNoOpenTask: false, title })];
  const manualRule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${iManual.id}`), actions: task('Durable manual {{Invoice.Number}}') });
  const entityRule = await H.mustRule(t, { trigger: { kind: 'ENTITY_UPDATED', entityType: 'INVOICE' }, condition: H.and(`id:eq:${iEntity.id}`), actions: task('Durable entity {{Invoice.Number}}') });
  // A daily slot that passes while the backend is down (at least 45 s ahead).
  const now = new Date();
  const ahead = 60 - now.getUTCSeconds() >= 45 ? 1 : 2;
  const slot = H.hhmmAhead(ahead);
  const schedRule = await H.mustRule(t, { trigger: { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: slot, zone: 'UTC', catchUp: 'ONE' }, condition: H.and(`id:eq:${iSched.id}`), actions: task('Durable sched {{Invoice.Number}}') });
  ctx.cleanup.push(manualRule.id, entityRule.id, schedRule.id);
  const [sh, sm] = slot.split(':').map(Number);
  const slotAt = new Date(); slotAt.setUTCHours(sh, sm, 0, 0);

  const runAt = new Date(Date.now() + 15000).toISOString();
  const queued = await rt.automation.run(t, manualRule.id, { runAt });
  const patch = await rt.patchInvoice(t, iEntity.id, { notes: `before crash ${attempt}` });
  const killOut = H.backend('kill'); // kill -9 right after the save
  const down = { at: new Date().toISOString() };
  down.entityEvents = rt.sql(`select id, kind, status from automation_events where entity_type='INVOICE' and entity_id=${iEntity.id} and kind='UPDATED' order by id`).map((r) => r.join('/'));
  down.manualEvent = rt.sql(`select id, kind, status from automation_events where id=${Number(queued.json?.eventId)}`).map((r) => r.join('/'));
  down.healthWhileDown = (await rt.automation.health(t)).status; // 0 = connection refused
  // Stay down until the run time and the schedule slot have both passed.
  const until = Math.max(Date.parse(runAt), slotAt.getTime()) + 5000;
  if (Date.now() < until) await rt.sleep(until - Date.now());
  const startOut = H.backend('start');
  const t2 = await rt.adminToken();
  ctx.t = t2; w.tokens.admin = t2;
  const run = await rt.waitForRun(t2, queued.json?.runId, { timeoutMs: 60000 });
  const schedRuns = await rt.waitFor(async () => { const r = (await rt.automation.runs(t2, schedRule.id)).json?.content || []; return r.length && r.every((x) => !/QUEUED|RUNNING/.test(x.status)) ? r : null; }, { timeoutMs: 90000, intervalMs: 3000 }) || [];
  const entityTasks0 = await rt.waitFor(async () => { const x = await H.tasksOfRule(t2, entityRule.id); return x.length ? x : null; }, { timeoutMs: 25000 }) || [];
  await rt.sleep(6000); // anything duplicated would show up by now
  const res = {
    queued: [queued.status, queued.json?.runId], patch: [patch.status, patch.ms], down, killOut: killOut.trim(), startOut: startOut.trim().split('\n').slice(-1)[0],
    manualRun: run && [run.status, run.matched, run.done, run.startedAt], manualTasks: (await H.tasksOfRule(t2, manualRule.id)).length,
    schedRuns: schedRuns.map((r) => [r.triggerType, r.status, r.scheduledFor]), schedTasks: (await H.tasksOfRule(t2, schedRule.id)).length,
    entityTasks: (await H.tasksOfRule(t2, entityRule.id)).length, entityTasksFirstSeen: entityTasks0.length,
    entityEventsAfter: rt.sql(`select id, kind, status, attempts, coalesce(last_error,'') from automation_events where entity_type='INVOICE' and entity_id=${iEntity.id} and kind='UPDATED' order by id`).map((r) => r.join('/')),
    notesPersisted: (await rt.api('GET', `/api/invoices/${iEntity.id}`, { token: t2 })).json?.notes === `before crash ${attempt}`,
    slot, runAt,
  };
  for (const id of [manualRule.id, entityRule.id, schedRule.id]) await rt.automation.disable(t2, id);
  return res;
}

async function durabilityCases(ctx) {
  const { R } = ctx;
  const a1 = await durabilityScenario(ctx, 1);
  const okManual = (r) => r.queued[0] === 202 && r.manualRun?.[0] === 'SUCCEEDED' && r.manualTasks === 1;
  const okSched = (r) => r.schedRuns.length === 1 && r.schedRuns[0][1] === 'SUCCEEDED' && r.schedTasks === 1;
  const okEntity = (r) => r.entityTasks === 1;
  const okSave = (r) => r.patch[0] === 200 && r.notesPersisted && r.down.entityEvents.some((e) => /PENDING|PROCESSING|DONE/.test(e));
  const needSecond = !okManual(a1) || !okSched(a1) || !okEntity(a1) || !okSave(a1);
  const a2 = needSecond ? await durabilityScenario(ctx, 2) : null;
  const rec = (meta, test, extra = {}, pick = (r) => r) => {
    const ok1 = test(a1); const ok2 = a2 ? test(a2) : null;
    const describe = (r) => H.short(pick(r), 900);
    let status = ok1 && ok2 !== false ? 'PASS' : !ok1 && ok2 ? 'NOT_TESTED' : 'FAIL';
    if (ok1 && ok2 === false) status = 'NOT_TESTED';
    R.rec({ feature: 'Durable execution', kind: 'API', prd: 'A5', ...meta, status, ...(status === 'FAIL' ? extra : {}),
      actual: a2 ? `Attempt 1: ${describe(a1)} | Attempt 2: ${describe(a2)}` : describe(a1) });
  };
  rec({ id: 'AUT-API-120', title: 'A run requested for a time while the backend is down happens once after it restarts',
    steps: 'POST /rules/{id}/run {runAt: now+15 s}; kill -9 the backend; stay down past runAt; start it on the same database; poll the run',
    expected: 'PRD A5 "nothing is lost if the consumer is down": the MANUAL run SUCCEEDED once, exactly one task' }, okManual, {},
  (r) => ({ queued: r.queued, runAt: r.runAt, manualEventWhileDown: r.down.manualEvent, kill: r.killOut, start: r.startOut, run: r.manualRun, tasks: r.manualTasks }));
  rec({ id: 'AUT-API-121', title: 'A schedule slot missed while the backend was down is caught up once',
    steps: 'SCHEDULE_DAILY slot 1-2 min ahead (catchUp ONE); kill -9 before the slot; restart after it',
    expected: 'PRD A5 + DESIGN §5.10.8: one SCHEDULED run for the missed slot, SUCCEEDED, exactly one task' }, okSched,
  { severity: 'high', evidence: `backend.log: ${H.logGrep('automation.tick.failed', 1).join('')} (see AUT-R1-2)`, codeRef: 'backend/src/main/java/com/geneinvoice/automation/schedule/ScheduleTicker.java:69' },
  (r) => ({ slot: r.slot, down: r.down.at, kill: r.killOut, start: r.startOut, schedRuns: r.schedRuns, schedTasks: r.schedTasks }));
  rec({ id: 'AUT-API-122', title: 'The save made just before the crash is kept and its outbox event survives',
    steps: 'PATCH /api/invoices/{id} notes, then kill -9 immediately; read automation_events while down; GET the invoice after restart',
    expected: 'PRD A5: 200, the notes persisted; the invoice UPDATED event row exists (PENDING) while the backend is down' }, okSave, {},
  (r) => ({ patch: r.patch, eventsWhileDown: r.down.entityEvents, apiWhileDown: r.down.healthWhileDown, notesPersisted: r.notesPersisted }));
  rec({ id: 'AUT-API-123', title: 'An update event written just before a crash is acted on exactly once after restart',
    steps: 'ENTITY_UPDATED INVOICE rule on the invoice; PATCH it; kill -9 at once; restart; wait 25 s',
    expected: 'PRD A5: exactly one task from the entity rule' }, okEntity,
  { severity: 'high', evidence: `automation_events.last_error "Unable to access lob stream" (see ${H.DEFECT_LOB})`, codeRef: 'backend/src/main/java/com/geneinvoice/automation/rule/AutomationRuleRepository.java:44' },
  (r) => ({ eventsWhileDown: r.down.entityEvents, eventsAfter: r.entityEventsAfter, entityTasks: r.entityTasks }));
}

module.exports = { asOfCases, durabilityCases };
