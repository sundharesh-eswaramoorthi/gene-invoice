// Round 2 additions: regression cases for AUT-R1-1 / AUT-R1-2 (large-object reads on PostgreSQL), and the gaps next
// to them: every action type and both placeholder levels on the EVENT path (not only on Run now), approvals and
// disputes reached from an event (PendingChangeRepository / DisputeRepository reads, also @Lob entities), rights on
// the event path, a burst of saves, and a failing action on an event-triggered rule.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const H = require('./helpers.js');

const money = (n) => `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function events(type, id) {
  return rt.sql(`select id, kind, status, attempts, coalesce(last_error,'') from automation_events where entity_type='${type}' and entity_id=${Number(id)} order by id`).map((r) => r.join('/'));
}
async function runsOf(t, ruleId) {
  return (await rt.automation.runs(t, ruleId)).json?.content || [];
}
/** Polls until the rule has `n` finished runs (or times out), then waits a little more for duplicates. */
async function waitRuns(t, ruleId, n, timeoutMs = 30000) {
  await rt.waitFor(async () => { const r = await runsOf(t, ruleId); return r.length >= n && r.every((x) => !/QUEUED|RUNNING/.test(x.status)) ? r : null; }, { timeoutMs, intervalMs: 1500 });
  await rt.sleep(4000);
  return runsOf(t, ruleId);
}
const brief = (runs) => runs.map((r) => `${r.id}:${r.triggerType}:${r.status}:m${r.matched}/d${r.done}/s${r.skipped}/f${r.failed}`);

async function eventCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const S = w.regions.SOUTH.id; const Wr = w.regions.WEST.id;
  const U = w.users; const T = w.tokens;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Event-triggered actions', ...meta }, fn, o);

  await K({ id: 'AUT-API-142', prd: 'A3', title: 'An invoice-created rule sends an email with Customer- and Invoice-level placeholders, from the primary of a shared role',
    steps: 'North customer with Collection POCs sam (primary) and nora; ENTITY_CREATED INVOICE rule (condition customerId:eq:C) SEND_EMAIL from COLLECTION_POC@CUSTOMER to CUSTOMER, subject "{{Customer.Name}} owes {{Invoice.Balance}}", body "Invoice {{Invoice.Number}} due {{Invoice.DueDate}}; total outstanding {{Customer.Outstanding}}; POC {{Customer.CollectionPoc}}"; POST /api/invoices 3210 for C; wait for the CREATED run; GET /api/emails?entityType=INVOICE&entityId=I',
    expected: 'PRD A1/A3/A4: one CREATED run SUCCEEDED; one OUTBOUND email row (NOT_SENT/NOT_CONFIGURED) with subject "<name> owes ₹3,210.00", body rendered, from sam (primary), to the customer' },
  async () => {
    const c = await rt.createCustomer(t, { name: rt.uniq('EvMail'), regionId: N, paymentTerm: 'NET_30' });
    await rt.addCustomerPoc(t, c.id, 'COLLECTION', U.nora.id, false);
    await rt.addCustomerPoc(t, c.id, 'COLLECTION', U.sam.id, true);
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:eq:${c.id}`),
      actions: [rt.action.email({ from: rt.tok.role('COLLECTION_POC', 'CUSTOMER'), to: [rt.tok.customer()], subject: '{{Customer.Name}} owes {{Invoice.Balance}}',
        body: 'Invoice {{Invoice.Number}} due {{Invoice.DueDate}}; total outstanding {{Customer.Outstanding}}; POC {{Customer.CollectionPoc}}' })] });
    ctx.cleanup.push(rule.id);
    const inv = await rt.createInvoice(t, { customerId: c.id, amount: 3210, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id });
    const runs = await waitRuns(t, rule.id, 1);
    await rt.sleep(800);
    const ems = await H.emailsOn(t, 'INVOICE', inv.json?.id);
    const e = ems[0];
    const wantSubj = `${c.name} owes ${money(3210)}`;
    const wantBody = `Invoice ${inv.json?.invoiceNumber} due ${inv.json?.dueDate}; total outstanding ${money(3210)}; POC Sam South`;
    await rt.automation.disable(t, rule.id);
    return { ok: inv.status === 200 && runs.length === 1 && runs[0].triggerType === 'CREATED' && runs[0].status === 'SUCCEEDED' && ems.length === 1
      && e.subject === wantSubj && e.body === wantBody && e.from?.userId === U.sam.id && (e.to || []).length === 1 && e.to[0].address === c.email && /NOT_SENT|NOT_CONFIGURED/.test(e.status),
      actual: { save: [inv.status, inv.ms], runs: brief(runs), emails: ems.map((x) => ({ id: x.id, status: x.status, subject: x.subject, body: x.body, from: x.from?.name, to: (x.to || []).map((y) => y.address) })), wantSubj, wantBody, events: events('INVOICE', inv.json?.id) } };
  });

  await K({ id: 'AUT-API-143', prd: 'A2', title: 'A payment-created rule with a condition on the amount acts on a matching payment and ignores a non-matching one; Payment placeholders render',
    steps: 'North customer (Collection POC nora) with two invoices; ENTITY_CREATED PAYMENT rule, condition customerId:eq:C AND amount:gt:300, CREATE_TASK "Got {{Payment.Amount}} from {{Customer.Name}} for {{Payment.Invoices}}" assignees [COLLECTION_POC@RECORD]; POST payment 450 on invoice 1, then payment 120 on invoice 2',
    expected: 'PRD A1/A2: exactly one CREATED run that acted (the 450 payment) -> one task on that payment titled "Got ₹450.00 from <name> for <inv1>", assignee nora; the 120 payment gets no task' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1000, 1000] });
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'PAYMENT' }, condition: H.and(`customerId:eq:${cust.c.id}`, 'amount:gt:300'),
      actions: [rt.action.task({ title: 'Got {{Payment.Amount}} from {{Customer.Name}} for {{Payment.Invoices}}', assignees: [rt.tok.role('COLLECTION_POC', 'RECORD')] })] });
    ctx.cleanup.push(rule.id);
    const p1 = await rt.createPayment(t, { customerId: cust.c.id, amount: 450, invoiceIds: [cust.invoices[0].id], collectionPocUserId: U.nora.id });
    const p2 = await rt.createPayment(t, { customerId: cust.c.id, amount: 120, invoiceIds: [cust.invoices[1].id], collectionPocUserId: U.nora.id });
    await rt.waitFor(async () => ((await H.tasksOfRule(t, rule.id)).length ? true : null), { timeoutMs: 30000 });
    await rt.sleep(8000);
    const tasks = await H.tasksOfRule(t, rule.id);
    const d = tasks[0] ? await H.taskDetail(t, tasks[0].id) : null;
    const people = (d?.assignees || []).flatMap((a) => (a.people || []).map((p) => p.userId));
    const want = `Got ${money(450)} from ${cust.c.name} for ${cust.invoices[0].invoiceNumber}`;
    const runs = await runsOf(t, rule.id);
    await rt.automation.disable(t, rule.id);
    return { ok: p1.status === 200 && p2.status === 200 && tasks.length === 1 && d?.entityType === 'PAYMENT' && d?.entityId === p1.json.id && d?.title === want && people.includes(U.nora.id)
      && runs.filter((r) => r.done > 0).length === 1,
      actual: { payments: [[p1.status, p1.json?.id], [p2.status, p2.json?.id]], tasks: tasks.map((x) => [x.id, x.entityType, x.entityId, x.title]), want, people, runs: brief(runs),
        events: [...events('PAYMENT', p1.json?.id), ...events('PAYMENT', p2.json?.id)] } };
  });

  await K({ id: 'AUT-API-144', prd: 'B2', feature: 'Automation and approvals', title: 'A promise that an invoice-created rule makes above South\'s threshold is held; others who can see it see the pending change; another approver applies it once',
    steps: 'South customer (Collection POC sam); admin ENTITY_CREATED INVOICE rule (customerId:eq:C) CREATE_PROMISE amount FIELD Invoice.Balance; admin POST /api/invoices 7000 (under INVOICE_CREATE 10000, over PROMISE_CREATE 5000); wait for the run; GET approvals for the customer as nora (READ South) and wendy (West only); admin approve; sam approve',
    expected: 'PRD B2: the run\'s effect is DONE with a PENDING_CHANGE result; no promise yet; nora sees the pending change (200), wendy does not (404); admin (the rule owner) cannot approve (400); sam approves -> exactly one promise of 7000' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [] });
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:eq:${cust.c.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' }, notes: 'Auto {{Invoice.Number}}', onlyIfNoOpenPromise: false })] });
    ctx.cleanup.push(rule.id);
    const inv = await rt.createInvoice(t, { customerId: cust.c.id, amount: 7000, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const runs = await waitRuns(t, rule.id, 1);
    const eff = runs[0] ? (await rt.automation.effects(t, runs[0].id)).json?.content || [] : [];
    const e = eff[0];
    const pcId = e?.result?.type === 'PENDING_CHANGE' ? e.result.id : null;
    const before = await H.promisesOf(t, cust.c.id);
    T.nora = await rt.login('nora'); T.wendy = await rt.login('wendy'); T.sam = await rt.login('sam');
    const nv = pcId ? await rt.approvals.get(T.nora, pcId) : { status: 0 };
    const wv = pcId ? await rt.approvals.get(T.wendy, pcId) : { status: 0 };
    const own = pcId ? await rt.approvals.approve(t, pcId) : { status: 0 };
    const sa = pcId ? await rt.approvals.approve(T.sam, pcId) : { status: 0 };
    const after = await H.promisesOf(t, cust.c.id);
    await rt.automation.disable(t, rule.id);
    return { ok: inv.status === 200 && !inv.held && runs.length === 1 && runs[0].triggerType === 'CREATED' && e?.status === 'DONE' && !!pcId && before.length === 0
      && nv.status === 200 && nv.json?.status === 'PENDING' && nv.json?.origin === 'AUTOMATION' && wv.status === 404 && own.status === 400 && sa.status === 200 && after.length === 1 && Number(after[0].amount) === 7000,
      actual: { invoice: [inv.status, inv.json?.id, inv.held], runs: brief(runs), effect: e && [e.status, e.result, e.message], promisesBefore: before.length, noraGet: [nv.status, nv.json?.status, nv.json?.origin],
        wendyGet: wv.status, ownerApprove: [own.status, own.json?.message], samApprove: [sa.status, sa.json?.status], promisesAfter: after.map((p) => [p.id, p.amount, p.status]), events: events('INVOICE', inv.json?.id) } };
  });

  await K({ id: 'AUT-API-145', prd: 'A3', feature: 'Event-triggered actions', title: 'An invoice-updated rule opens one internal dispute (ONCE_PER_RECORD) however often the invoice is updated',
    steps: 'North customer (Collection POC nora), invoice 1500 (Sales POC sam); ENTITY_UPDATED INVOICE rule (id:eq:I) CREATE_DISPUTE ONCE_PER_RECORD reason "Check {{Invoice.Number}} of {{Customer.Name}}", assignees [SALES_POC@RECORD]; PATCH notes twice (5 s apart); wait',
    expected: 'PRD A1/A3 + DESIGN §5.10.11: two UPDATED runs; the first opens one dispute (internal, origin AUTOMATION, rendered reason, assignee sam); the second is done 0 / skipped 1; exactly one dispute on the customer' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const inv = (await rt.createInvoice(t, { customerId: cust.c.id, amount: 1500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id })).json;
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_UPDATED', entityType: 'INVOICE' }, condition: H.and(`id:eq:${inv.id}`),
      actions: [rt.action.dispute({ reason: 'Check {{Invoice.Number}} of {{Customer.Name}}', assignees: [rt.tok.role('SALES_POC', 'RECORD')] })] });
    ctx.cleanup.push(rule.id);
    const u1 = await rt.patchInvoice(t, inv.id, { notes: 'first update' });
    await waitRuns(t, rule.id, 1);
    const u2 = await rt.patchInvoice(t, inv.id, { notes: 'second update' });
    const runs = await waitRuns(t, rule.id, 2);
    const all = (await rt.list(t, '/api/disputes', { filter: [`customerId:eq:${cust.c.id}`] })).json?.content || [];
    const d = all[0] ? (await rt.api('GET', `/api/disputes/${all[0].id}`, { token: t })).json : null;
    const people = (d?.assignees || []).flatMap((a) => (a.people || []).map((p) => p.userId));
    const sorted = [...runs].sort((a, b) => a.id - b.id);
    await rt.automation.disable(t, rule.id);
    return { ok: u1.status === 200 && u2.status === 200 && runs.length === 2 && runs.every((r) => r.triggerType === 'UPDATED') && sorted[0].done === 1 && sorted[1].done === 0 && sorted[1].skipped === 1
      && all.length === 1 && d?.internal === true && d?.origin === 'AUTOMATION' && d?.ruleId === rule.id && d?.reason === `Check ${inv.invoiceNumber} of ${cust.c.name}` && people.includes(U.sam.id),
      actual: { saves: [u1.status, u2.status], runs: brief(sorted), disputes: all.length, dispute: d && { id: d.id, internal: d.internal, origin: d.origin, ruleId: d.ruleId, reason: d.reason, people }, events: events('INVOICE', inv.id) } };
  });

  await K({ id: 'AUT-API-146', prd: 'B1', feature: 'Rules and rights', title: 'An event-triggered rule acts only where its owner may act: nora\'s invoice-created rule acts in North, not in South (READ) or West (none)',
    steps: 'nora (MANAGE North, READ South, nothing West) creates an unrestricted ENTITY_CREATED INVOICE rule, condition customerId:in:<new North, South, West customers>, CREATE_TASK; admin creates one invoice for each customer; wait 25 s',
    expected: 'PRD B1 + DESIGN §5.10.7: exactly one task, on the North invoice; nothing is done on the South or West invoices' },
  async () => {
    T.nora = await rt.login('nora');
    const cn = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const cs = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [] });
    const cw = await H.mkCustomer(t, { regionId: Wr, collector: U.wendy.id, amounts: [] });
    const rule = await H.mustRule(T.nora, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:in:${[cn.c.id, cs.c.id, cw.c.id].join(',')}`),
      actions: [rt.action.task({ title: 'Nora event {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const mk = async (cust, poc) => (await rt.createInvoice(t, { customerId: cust.c.id, amount: 800, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: poc })).json;
    const iN = await mk(cn, U.nora.id); const iS = await mk(cs, U.sam.id); const iW = await mk(cw, U.wendy.id);
    await rt.waitFor(async () => ((await H.tasksOfRule(t, rule.id)).length ? true : null), { timeoutMs: 30000 });
    await rt.sleep(10000);
    const tasks = await H.tasksOfRule(t, rule.id);
    const runs = await runsOf(t, rule.id);
    await rt.automation.disable(T.nora, rule.id);
    return { ok: tasks.length === 1 && tasks[0].entityId === iN.id && tasks[0].regionId === N,
      actual: { invoices: { north: iN.id, south: iS.id, west: iW.id }, tasks: tasks.map((x) => [x.id, x.entityId, x.regionId]), runs: brief(runs) } };
  });

  await K({ id: 'AUT-API-147', prd: 'A5', feature: 'Durable execution', title: 'A burst of saves is acted on exactly once each',
    steps: 'ENTITY_CREATED INVOICE rule (customerId:eq:C) CREATE_TASK EVERY_TIME; POST 8 invoices for C back to back (no waits); wait until 8 runs finish',
    expected: 'PRD A5 "nothing is lost": 8 CREATED runs, 8 tasks, one per invoice, no duplicates; every save 200' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:eq:${cust.c.id}`),
      actions: [rt.action.task({ title: 'Burst {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const saves = await Promise.all(Array.from({ length: 8 }, (_, i) => rt.createInvoice(t, { customerId: cust.c.id, amount: 100 + i, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id })));
    const runs = await waitRuns(t, rule.id, 8, 60000);
    const tasks = await H.tasksOfRule(t, rule.id);
    const ids = saves.map((s) => s.json?.id).sort((a, b) => a - b);
    const taskIds = tasks.map((x) => x.entityId).sort((a, b) => a - b);
    await rt.automation.disable(t, rule.id);
    return { ok: saves.every((s) => s.status === 200) && runs.length === 8 && runs.every((r) => r.status === 'SUCCEEDED') && JSON.stringify(ids) === JSON.stringify(taskIds),
      actual: { saves: saves.map((s) => [s.status, s.ms]), runs: brief(runs), invoices: ids, taskEntities: taskIds } };
  });

  await K({ id: 'AUT-API-148', prd: 'A5', feature: 'Durable execution', title: 'An event-triggered rule whose action cannot be done neither slows nor breaks the save; the other action still happens and the event is not retried',
    steps: 'customer with NO Collection POC; ENTITY_CREATED INVOICE rule (customerId:eq:C) actions CREATE_PROMISE (needs a Collection POC) + CREATE_TASK; POST /api/invoices; wait for the run; read automation_events',
    expected: 'PRD A5 + DESIGN §5.10.5/6: save 200 fast (<1500 ms); run PARTIAL (promise FAILED "no Collection POC", task DONE); the event DONE, not retried' },
  async () => {
    const c = await rt.createCustomer(t, { name: rt.uniq('EvNoPoc'), regionId: N, paymentTerm: 'NET_30' });
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:eq:${c.id}`),
      actions: [rt.action.promise({ amount: { source: 'FIXED', value: 100 } }), rt.action.task({ title: 'Still made {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const inv = await rt.createInvoice(t, { customerId: c.id, amount: 900, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id });
    const runs = await waitRuns(t, rule.id, 1);
    const eff = runs[0] ? (await rt.automation.effects(t, runs[0].id)).json?.content || [] : [];
    const st = Object.fromEntries(eff.map((e) => [e.actionType, [e.status, e.message]]));
    const ev = rt.sql(`select status, attempts from automation_events where entity_type='INVOICE' and entity_id=${Number(inv.json?.id)} and kind='CREATED'`);
    await rt.automation.disable(t, rule.id);
    return { ok: inv.status === 200 && inv.ms < 1500 && runs.length === 1 && runs[0].status === 'PARTIAL' && st.CREATE_PROMISE?.[0] === 'FAILED' && st.CREATE_TASK?.[0] === 'DONE' && ev.length === 1 && ev[0][0] === 'DONE',
      actual: { save: [inv.status, inv.ms], runs: brief(runs), effects: st, event: ev } };
  });

  await K({ id: 'AUT-API-149', prd: 'A1', feature: 'Entity triggers', title: 'A customer rule on "created or updated" with ONCE_PER_RECORD acts on the create and not again on the update',
    steps: 'ENTITY_CREATED_OR_UPDATED CUSTOMER rule, condition name:contains:<tag>, CREATE_TASK ONCE_PER_RECORD "Welcome {{Customer.Name}}"; POST the customer; wait; PUT it (phone change); wait',
    expected: 'PRD A1 + DESIGN §5.10.3 repeat: two runs (CREATED, UPDATED); one task "Welcome <name>"; the UPDATED run skipped it' },
  async () => {
    const tag = rt.uniq('Welcome');
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED_OR_UPDATED', entityType: 'CUSTOMER' }, condition: H.and(`name:contains:${tag}`),
      actions: [rt.action.task({ title: 'Welcome {{Customer.Name}}', repeat: 'ONCE_PER_RECORD' })] });
    ctx.cleanup.push(rule.id);
    const c = await rt.createCustomer(t, { name: tag, regionId: N, paymentTerm: 'NET_30' });
    await waitRuns(t, rule.id, 1);
    const put = await rt.api('PUT', `/api/customers/${c.id}`, { token: t, body: { name: c.name, phone: '555-0177', email: c.email, address: c.address, paymentTerm: 'NET_30' } });
    const runs = (await waitRuns(t, rule.id, 2)).sort((a, b) => a.id - b.id);
    const tasks = await H.tasksOfRule(t, rule.id);
    await rt.automation.disable(t, rule.id);
    return { ok: put.status === 200 && runs.length === 2 && runs[0].triggerType === 'CREATED' && runs[1].triggerType === 'UPDATED' && runs[1].done === 0 && tasks.length === 1 && tasks[0].title === `Welcome ${tag}`,
      actual: { put: put.status, runs: brief(runs), tasks: tasks.map((x) => [x.id, x.title]), events: events('CUSTOMER', c.id) } };
  });
}

/** A5: a burst of saves with the backend killed right after it; events claimed at the kill come back after the lease. */
async function durableBurstCases(ctx) {
  const { R, w } = ctx;
  const N = w.regions.NORTH.id; const U = w.users;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Durable execution', prd: 'A5', ...meta }, fn, o);
  await K({ id: 'AUT-API-124', title: 'A burst of saves cut off by kill -9 is acted on exactly once each after restart, including events that were being processed at the crash',
    steps: 'ENTITY_CREATED INVOICE rule (customerId:eq:C) CREATE_TASK EVERY_TIME; POST 20 invoices for C concurrently; kill -9 the backend as soon as they answer; read automation_events; start the backend on the same DB; wait until every CREATED event of those invoices is DONE (lease 300 s, application.yml automation lease-seconds)',
    expected: 'PRD A5 "nothing is lost if the consumer is down" + DESIGN §3.6 lease: all 20 saves 200; after restart (and the lease of any claimed event) 20 CREATED runs, exactly 20 tasks, one per invoice, no duplicates' },
  async () => {
    let t = ctx.t;
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const rule = await H.mustRule(t, { trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, condition: H.and(`customerId:eq:${cust.c.id}`),
      actions: [rt.action.task({ title: 'Crash burst {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    await rt.sleep(2500); // let the rule's own bookkeeping settle
    const saves = await Promise.all(Array.from({ length: 20 }, (_, i) => rt.createInvoice(t, { customerId: cust.c.id, amount: 200 + i, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id })));
    H.backend('kill');
    const ids = saves.map((s) => s.json?.id).filter(Boolean).sort((a, b) => a - b);
    const q = `select status, count(*) from automation_events where entity_type='INVOICE' and kind='CREATED' and entity_id in (${ids.join(',') || 0}) group by status order by status`;
    const atKill = rt.sql(q).map((r) => r.join('='));
    const tasksAtKill = Number(rt.sql(`select count(*) from tasks where rule_id=${rule.id}`)[0]?.[0] ?? -1);
    H.backend('start');
    // The first request after a restart can hit a pooled socket of the killed process (ECONNRESET): retry.
    for (let i = 0; i < 5; i++) { try { t = await rt.adminToken(); break; } catch (e) { if (i === 4) throw e; await rt.sleep(1000); } }
    ctx.t = t; w.tokens.admin = t;
    const t0 = Date.now();
    await rt.waitFor(async () => (rt.sql(q).every((r) => r[0] === 'DONE') ? true : null), { timeoutMs: 400000, intervalMs: 5000 });
    const settledS = Math.round((Date.now() - t0) / 1000);
    await rt.sleep(5000);
    const after = rt.sql(q).map((r) => r.join('='));
    const tasks = await H.tasksOfRule(t, rule.id);
    const taskEnt = tasks.map((x) => x.entityId).sort((a, b) => a - b);
    const runs = (await rt.automation.runs(t, rule.id)).json?.content || [];
    await rt.automation.disable(t, rule.id);
    return { ok: saves.every((s) => s.status === 200) && ids.length === 20 && JSON.stringify(taskEnt) === JSON.stringify(ids) && runs.length === 20 && after.join() === 'DONE=20',
      actual: { saves: saves.map((s) => s.status).join(','), eventsAtKill: atKill, tasksAtKill, settledAfterRestartS: settledS, eventsAfter: after, tasks: tasks.length, distinctTaskInvoices: new Set(taskEnt).size, runs: runs.length, runStatuses: [...new Set(runs.map((r) => r.status))] } };
  }, { repeat: true });
}

/** Whole-run sweep for AUT-R1-1 / AUT-R1-2: no event or tick failed on a large-object read, nothing is stuck or dead. */
async function sweepCases(ctx) {
  const { R } = ctx;
  const t = await rt.adminToken();
  // Let anything still in flight settle.
  // An event claimed by a worker that was killed comes back only after its lease (300 s): wait for live leases too.
  const leaseLeft = Number(rt.sql("select coalesce(extract(epoch from max(lease_until) - now()), 0) from automation_events where status='PROCESSING'")[0][0]);
  await rt.waitFor(async () => { const h = (await rt.automation.health(t)).json; return h && h.pending === 0 && h.processing === 0 ? h : null; }, { timeoutMs: Math.max(60000, (leaseLeft + 60) * 1000), intervalMs: 3000 });
  const log = fs.readFileSync(path.join(rt.HOME, 'runtime', H.AREA, 'backend.log'), 'utf8').split('\n');
  const count = (re) => log.filter((l) => re.test(l)).length;
  const first = (re) => (log.find((l) => re.test(l)) || '').slice(0, 300);
  const h = (await rt.automation.health(t)).json || {};
  const byStatus = rt.sql("select status, count(*) from automation_events group by status order by status").map((r) => r.join('='));
  const lobRows = rt.sql("select id, kind, status, attempts, last_error from automation_events where last_error ilike '%lob%' order by id limit 5").map((r) => r.join('/'));
  const errRows = rt.sql("select id, kind, status, attempts, last_error from automation_events where last_error is not null order by id limit 5").map((r) => r.join('/'));
  const kinds = rt.sql("select kind, status, count(*) from automation_events group by kind, status order by kind, status").map((r) => r.join('/'));
  const runKinds = rt.sql("select trigger_type, status, count(*) from automation_runs group by trigger_type, status order by 1, 2").map((r) => r.join('/'));
  R.rec({ id: 'AUT-API-140', feature: 'Regression', kind: 'API', prd: 'A5', title: 'AUT-R1-1 fixed: over the whole run no automation event failed with "Unable to access lob stream", and none is left pending, retrying or dead',
    steps: 'after all API, UI and durability cases: GET /api/automation/health; automation_events grouped by status; last_error of every event; grep backend.log for "lob stream" and "automation.event.failed"',
    expected: 'DESIGN §5.10.6: every CREATED/UPDATED/CHILDREN event DONE; no last_error containing "lob"; no "Unable to access lob stream" in the log; health pending 0, retrying 0, dead 0; entity runs (CREATED/UPDATED) exist',
    ok: lobRows.length === 0 && count(/lob stream/i) === 0 && count(/automation\.event\.failed/) === 0 && h.dead === 0 && h.retrying === 0 && h.pending === 0 && h.processing === 0 && runKinds.some((k) => /^CREATED\//.test(k)) && runKinds.some((k) => /^UPDATED\//.test(k)),
    actual: H.short({ health: { pending: h.pending, processing: h.processing, retrying: h.retrying, dead: h.dead }, eventsByStatus: byStatus, eventsByKind: kinds, runsByTrigger: runKinds, lobErrorRows: lobRows, rowsWithAnyError: errRows,
      logLobStream: count(/lob stream/i), logEventFailed: count(/automation\.event\.failed/), firstEventFailed: first(/automation\.event\.failed/) }, 2000),
    evidence: 'runtime/automation/backend.log grep "lob stream" / "automation.event.failed"; SQL on automation_events / automation_runs' });
  R.rec({ id: 'AUT-API-141', feature: 'Regression', kind: 'API', prd: 'A1', title: 'AUT-R1-2 fixed: over the whole run the schedule tick never failed, and scheduled runs were made',
    steps: 'after all cases: grep backend.log for "automation.tick.failed"; automation_runs where trigger_type=SCHEDULED',
    expected: 'DESIGN §5.10.8: no tick failure in the log; at least one SCHEDULED run SUCCEEDED',
    ok: count(/automation\.tick\.failed/) === 0 && runKinds.some((k) => /^SCHEDULED\/SUCCEEDED/.test(k)),
    actual: H.short({ logTickFailed: count(/automation\.tick\.failed/), firstTickFailed: first(/automation\.tick\.failed/), runsByTrigger: runKinds }, 1500),
    evidence: 'runtime/automation/backend.log grep "automation.tick.failed"' });
}

module.exports = { eventCases, sweepCases, durableBurstCases };
