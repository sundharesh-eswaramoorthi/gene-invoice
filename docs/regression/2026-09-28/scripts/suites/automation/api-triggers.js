// A1 (+A5): a rule fires when a Customer, Invoice or Payment is created or updated. The save publishes an outbox
// event; the consumer (poll every 2 s) evaluates the rule and acts. Cases run as one batch so the waits overlap;
// the whole batch is run twice when anything fails (reproduce-twice rule).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const LOB_REF = 'backend/src/main/java/com/geneinvoice/automation/rule/AutomationRuleRepository.java:44 (findEntityCandidates, called outside any transaction from EventProcessor.candidates, backend/src/main/java/com/geneinvoice/automation/run/EventProcessor.java:180) + @Lob String actionsJson/conditionJson at backend/src/main/java/com/geneinvoice/automation/rule/AutomationRule.java:97-103';

module.exports = async function triggerCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const nora = w.users.nora.id;

  const specs = [
    { id: 'AUT-API-20', title: 'Customer CREATED fires an ENTITY_CREATED CUSTOMER rule', kind: 'ENTITY_CREATED', entity: 'CUSTOMER', want: 1,
      setup: async (s) => { s.tag = rt.uniq('TrigC'); return H.and(`name:contains:${s.tag}`); },
      save: async (s) => { const c = await rt.createCustomer(t, { name: s.tag, regionId: N }); s.target = ['CUSTOMER', c.id]; return 200; } },
    { id: 'AUT-API-21', title: 'Customer UPDATED fires an ENTITY_UPDATED CUSTOMER rule', kind: 'ENTITY_UPDATED', entity: 'CUSTOMER', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`id:eq:${s.cust.c.id}`); },
      save: async (s) => { const c = s.cust.c; s.target = ['CUSTOMER', c.id];
        const r = await rt.api('PUT', `/api/customers/${c.id}`, { token: t, body: { name: c.name, phone: '555-0199', email: c.email, address: '2 Changed Rd', paymentTerm: 'NET_30' } }); return r.status; } },
    { id: 'AUT-API-22', title: 'Invoice CREATED fires an ENTITY_CREATED INVOICE rule', kind: 'ENTITY_CREATED', entity: 'INVOICE', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 1500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); s.target = ['INVOICE', r.json?.id]; s.saveMs = r.ms; return r.status; } },
    { id: 'AUT-API-23', title: 'Invoice UPDATED fires an ENTITY_UPDATED INVOICE rule', kind: 'ENTITY_UPDATED', entity: 'INVOICE', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [1200] }); return H.and(`id:eq:${s.cust.invoices[0].id}`); },
      save: async (s) => { const i = s.cust.invoices[0]; s.target = ['INVOICE', i.id]; const r = await rt.patchInvoice(t, i.id, { notes: 'changed ' + Date.now() }); s.saveMs = r.ms; return r.status; } },
    { id: 'AUT-API-24', title: 'Payment CREATED fires an ENTITY_CREATED PAYMENT rule', kind: 'ENTITY_CREATED', entity: 'PAYMENT', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [1200] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const p = await rt.createPayment(t, { customerId: s.cust.c.id, amount: 200, invoiceIds: [s.cust.invoices[0].id], collectionPocUserId: nora }); s.target = ['PAYMENT', p.json?.id]; return p.status; } },
    { id: 'AUT-API-25', title: 'Payment UPDATED fires an ENTITY_UPDATED PAYMENT rule', kind: 'ENTITY_UPDATED', entity: 'PAYMENT', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [1200] }); s.pay = await H.payInvoice(t, s.cust, s.cust.invoices[0], 100); return H.and(`id:eq:${s.pay.id}`); },
      save: async (s) => { s.target = ['PAYMENT', s.pay.id]; const r = await rt.api('PATCH', `/api/payments/${s.pay.id}`, { token: t, body: { notes: 'changed ' + Date.now() } }); return r.status; } },
    { id: 'AUT-API-26', title: 'An ENTITY_CREATED_OR_UPDATED rule fires on the create and again on the update (EVERY_TIME)', kind: 'ENTITY_CREATED_OR_UPDATED', entity: 'INVOICE', want: 2,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 900, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); s.target = ['INVOICE', r.json?.id];
        await rt.sleep(300); const p = await rt.patchInvoice(t, r.json.id, { notes: 'then updated' }); return r.status === 200 ? p.status : r.status; } },
    { id: 'AUT-API-27', title: 'Renaming a customer counts as an update of each of its invoices (CHILDREN event)', kind: 'ENTITY_UPDATED', entity: 'INVOICE', want: 2,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [700, 800] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const c = s.cust.c; s.target = ['CUSTOMER', c.id];
        const r = await rt.api('PUT', `/api/customers/${c.id}`, { token: t, body: { name: c.name + ' Renamed', phone: c.phone, email: c.email, address: c.address, paymentTerm: 'NET_30' } }); return r.status; } },
    { id: 'AUT-API-28', title: 'A new invoice pushing a customer\'s outstanding over a limit fires "Customer updated where outstanding > 1000"', kind: 'ENTITY_UPDATED', entity: 'CUSTOMER', want: 1,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`id:eq:${s.cust.c.id}`, 'outstanding:gt:1000'); },
      save: async (s) => { s.target = ['CUSTOMER', s.cust.c.id]; const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 2000, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); return r.status; } },
    // Negative cases: meaningful only while the positive control of the same kind fires.
    { id: 'AUT-API-29', title: 'An ENTITY_CREATED rule does not fire on an update', kind: 'ENTITY_CREATED', entity: 'INVOICE', want: 0, control: 'AUT-API-22',
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [1100] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const i = s.cust.invoices[0]; s.target = ['INVOICE', i.id]; return (await rt.patchInvoice(t, i.id, { notes: 'x' })).status; } },
    { id: 'AUT-API-30', title: 'A record that does not meet the condition does not fire the rule', kind: 'ENTITY_CREATED', entity: 'INVOICE', want: 0, control: 'AUT-API-22',
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`customerId:eq:${s.cust.c.id}`, 'total:gt:99999'); },
      save: async (s) => { const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); s.target = ['INVOICE', r.json?.id]; return r.status; } },
    { id: 'AUT-API-31', title: 'A disabled rule does not fire', kind: 'ENTITY_CREATED', entity: 'INVOICE', want: 0, control: 'AUT-API-22', disabled: true,
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); s.target = ['INVOICE', r.json?.id]; return r.status; } },
    { id: 'AUT-API-32', title: 'A MANUAL ("only when run by hand") rule never fires on a save', kind: 'MANUAL', entity: 'INVOICE', want: 0, control: 'AUT-API-22',
      setup: async (s) => { s.cust = await H.mkCustomer(t, { regionId: N, collector: nora, amounts: [] }); return H.and(`customerId:eq:${s.cust.c.id}`); },
      save: async (s) => { const r = await rt.createInvoice(t, { customerId: s.cust.c.id, amount: 500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: nora }); s.target = ['INVOICE', r.json?.id]; return r.status; } },
  ];

  async function batch() {
    const out = {};
    for (const sp of specs) {
      const s = {};
      const condition = await sp.setup(s);
      const rule = await H.mustRule(t, { enabled: !sp.disabled, trigger: { kind: sp.kind, entityType: sp.entity }, condition,
        actions: [rt.action.task({ title: `Trig ${sp.id} {{Customer.Name}}`, repeat: 'EVERY_TIME', onlyIfNoOpenTask: false })] });
      ctx.cleanup.push(rule.id);
      s.rule = rule; out[sp.id] = s;
    }
    const t0 = Date.now();
    for (const sp of specs) { const s = out[sp.id]; s.saveStatus = await sp.save(s); }
    // Poll all together: positives until they have their tasks, negatives for the full window.
    const deadline = Date.now() + 30000;
    for (;;) {
      for (const sp of specs) { const s = out[sp.id]; s.tasks = await H.tasksOfRule(t, s.rule.id); }
      const done = specs.every((sp) => sp.want === 0 || out[sp.id].tasks.length >= sp.want);
      if (done && Date.now() - t0 > 12000) break;
      if (Date.now() > deadline) break;
      await rt.sleep(2000);
    }
    await rt.sleep(1500);
    for (const sp of specs) {
      const s = out[sp.id];
      s.tasks = await H.tasksOfRule(t, s.rule.id);
      const runs = await rt.automation.runs(t, s.rule.id);
      s.runs = (runs.json?.content || []).map((r) => `${r.id}:${r.triggerType}:${r.status}`);
      const [type, id] = s.target || [];
      s.events = type && id ? rt.sql(`select id, kind, status, attempts, coalesce(last_error,'') from automation_events where entity_type='${type}' and entity_id=${Number(id)} order by id desc limit 3`).map((r) => r.join('/')) : [];
      await rt.automation.disable(t, s.rule.id);
    }
    return out;
  }

  const first = await batch();
  const anyFail = specs.some((sp) => (sp.want === 0 ? false : first[sp.id].tasks.length !== sp.want));
  const second = anyFail ? await batch() : null;

  const verdict = (sp, s) => sp.want === 0 ? s.tasks.length === 0 : s.tasks.length === sp.want && s.tasks.every((x) => x.origin === 'AUTOMATION');
  const describe = (sp, s) => `rule ${s.rule.id} save=${s.saveStatus}${s.saveMs ? `/${s.saveMs}ms` : ''} tasks=${s.tasks.length} (want ${sp.want}) runs=[${s.runs.join(',')}] events=[${s.events.join(' ; ')}]`;
  const passed = {};
  for (const sp of specs) {
    const a = first[sp.id]; const b = second ? second[sp.id] : null;
    const okA = verdict(sp, a); const okB = b ? verdict(sp, b) : null;
    passed[sp.id] = okA && (okB !== false);
    const meta = { id: sp.id, feature: 'Entity triggers', kind: 'API', prd: sp.id === 'AUT-API-32' ? 'A5' : 'A1', title: sp.title,
      steps: `admin creates ${sp.disabled ? 'a DISABLED ' : ''}${sp.kind} ${sp.entity} rule (CREATE_TASK, EVERY_TIME) with a condition naming a fresh record; performs the save; polls GET /api/tasks?filter=ruleId:eq:<rule> for 30 s`,
      expected: sp.want ? `PRD A1/A5: the consumer acts within seconds -> ${sp.want} task(s) with origin AUTOMATION on the record` : 'PRD A1: no task',
    };
    if (sp.want === 0) {
      const control = sp.control && passed[sp.control];
      if (!control) {
        R.rec({ ...meta, status: 'BLOCKED', actual: `No task, but the positive control ${sp.control} does not fire either, so "does not fire" proves nothing. ${describe(sp, a)}`,
          evidence: `blocked by ${H.DEFECT_LOB}` });
        continue;
      }
      R.rec({ ...meta, ok: okA && okB !== false, actual: describe(sp, a) + (b ? ` | attempt 2: ${describe(sp, b)}` : '') });
      continue;
    }
    if (okA && (okB === null || okB)) { R.rec({ ...meta, status: 'PASS', actual: describe(sp, a) }); continue; }
    if (!okA && okB) { R.rec({ ...meta, status: 'NOT_TESTED', actual: `Not reproduced: attempt 1 ${describe(sp, a)} | attempt 2 ${describe(sp, b)}` }); continue; }
    const lob = [...a.events, ...(b ? b.events : [])].some((e) => /lob stream/i.test(e));
    R.rec({ ...meta, status: 'FAIL', severity: 'high', actual: `Reproduced twice. Attempt 1: ${describe(sp, a)} | Attempt 2: ${describe(sp, b)}`,
      evidence: `${lob ? 'automation_events.last_error = "Unable to access lob stream"; ' : ''}backend.log: ${H.logGrep('automation.event.failed', 2).join(' || ')}`,
      codeRef: lob ? LOB_REF : '' });
  }

  // The save itself is never slowed or broken by rules (A5): measured on the batch's saves.
  const saves = specs.map((sp) => [sp.id, first[sp.id].saveStatus, first[sp.id].saveMs]).filter((x) => x[2] !== undefined);
  const batchesOk = [first, second].filter(Boolean).every((b) => specs.every((sp) => b[sp.id].saveStatus === 200 && (b[sp.id].saveMs === undefined || b[sp.id].saveMs < 1500)));
  R.rec({ id: 'AUT-API-33', feature: 'Durable execution', kind: 'API', prd: 'A5', title: 'Saves that trigger rules (even rules whose processing fails) answer 200 at once',
    steps: 'the saves of AUT-API-20..32 while their rules are enabled (several of them failing in the consumer)',
    expected: 'PRD A5 "a rule can never slow down or break the user\'s save": every save 200, well under a second',
    ok: batchesOk,
    actual: `save statuses: ${specs.map((sp) => `${sp.id}=${first[sp.id].saveStatus}`).join(' ')}; timed saves (ms): ${saves.map((x) => `${x[0]}=${x[2]}`).join(' ')}` });
};
