// A3 actions (task, promise, dispute, email), A4 placeholders and role-at-a-level people, A6 automation tasks.
const rt = require('../../lib.js');
const H = require('./helpers.js');

module.exports = async function actionCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id;
  const U = w.users;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Actions', ...meta }, fn, o);
  const money = (n) => `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  async function oneInvoiceRule(cust, inv, actions, extra = {}) {
    const rule = await H.mustRule(t, { trigger: H.manual('INVOICE'), condition: H.and(`id:eq:${inv.id}`), actions, ...extra });
    ctx.cleanup.push(rule.id);
    return rule;
  }

  await K({ id: 'AUT-API-40', prd: 'A3', feature: 'Task action', title: 'CREATE_TASK makes a task on the record with rendered title/description, due date and assignees',
    steps: 'North customer (Collection POC nora) invoice 1234 due +20d; MANUAL rule id:eq:I, CREATE_TASK title "Chase {{Invoice.Number}} ({{Invoice.Balance}})", description "{{Customer.Name}} owes {{Customer.Outstanding}} in {{Customer.Region}}", due INVOICE_DUE_DATE-3, assignees [COLLECTION_POC@CUSTOMER, USER sam]; run now; GET /api/tasks/{id}',
    expected: 'PRD A3/A4/A6: task on INVOICE I, title "Chase INV-… (₹1,234.00)", description with customer name/₹1,234.00/North, dueDate = due-3, origin AUTOMATION, ruleId, region North, assignees resolve to nora and sam' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1234] });
    const inv = cust.invoices[0];
    const rule = await oneInvoiceRule(cust, inv, [rt.action.task({ title: 'Chase {{Invoice.Number}} ({{Invoice.Balance}})', description: '{{Customer.Name}} owes {{Customer.Outstanding}} in {{Customer.Region}}',
      due: { base: 'INVOICE_DUE_DATE', offsetDays: -3 }, assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER'), rt.tok.user(U.sam.id)] })]);
    const { run, effects } = await H.runAndWait(t, rule.id);
    const eff = effects[0];
    const task = eff?.result?.id ? await H.taskDetail(t, eff.result.id) : null;
    const wantTitle = `Chase ${inv.invoiceNumber} (${money(1234)})`;
    const wantDesc = `${cust.c.name} owes ${money(1234)} in North`;
    const people = (task?.assignees || []).flatMap((a) => a.people.map((p) => p.userId)).sort();
    const wantDue = rt.day(17);
    const ok = run?.status === 'SUCCEEDED' && eff?.status === 'DONE' && eff?.result?.type === 'TASK' && task && task.title === wantTitle && task.description === wantDesc
      && task.dueDate === wantDue && task.entityType === 'INVOICE' && task.entityId === inv.id && task.origin === 'AUTOMATION' && task.rule?.id === rule.id
      && task.regionId === N && JSON.stringify(people) === JSON.stringify([U.nora.id, U.sam.id].sort());
    return { ok, actual: { run: run && [run.id, run.status], effect: eff && [eff.status, eff.result], task: task && { title: task.title, description: task.description, dueDate: task.dueDate, entity: [task.entityType, task.entityId], origin: task.origin, rule: task.rule, regionId: task.regionId, people } },
      expected: `title "${wantTitle}", description "${wantDesc}", due ${wantDue}, people [${U.nora.id},${U.sam.id}]` };
  });

  await K({ id: 'AUT-API-41', prd: 'A3', feature: 'Task action', title: 'ONCE_PER_RECORD acts once however often the rule runs; preview shows it as already done',
    steps: 'rule with CREATE_TASK repeat ONCE_PER_RECORD on one invoice; run now twice; preview with ruleId',
    expected: 'DESIGN §5.10.5: one task; second run acts on nothing (skipped); preview sample alreadyDone contains the action key' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [900] });
    const a = rt.action.task({ repeat: 'ONCE_PER_RECORD', title: 'Once {{Invoice.Number}}', onlyIfNoOpenTask: false });
    const rule = await oneInvoiceRule(cust, cust.invoices[0], [a]);
    const r1 = await H.runAndWait(t, rule.id);
    const r2 = await H.runAndWait(t, rule.id);
    const tasks = await H.tasksOfRule(t, rule.id);
    const pv = await rt.automation.preview(t, { rule: rt.rule({ name: rule.name, trigger: rule.trigger, condition: rule.condition, actions: rule.actions }), ruleId: rule.id, sampleSize: 5 });
    const already = pv.json?.sample?.[0]?.alreadyDone || [];
    return { ok: tasks.length === 1 && r2.run?.done === 0 && already.includes(rule.actions[0].key),
      actual: { tasks: tasks.length, run1: r1.run && [r1.run.status, r1.run.done, r1.run.skipped], run2: r2.run && [r2.run.status, r2.run.done, r2.run.skipped], run2Effects: r2.effects.map((e) => e.status), alreadyDone: already } };
  });

  await K({ id: 'AUT-API-42', prd: 'A3', feature: 'Task action', title: 'EVERY_TIME acts on every run; the "no open task" guard holds it back while one is open',
    steps: 'rule A: CREATE_TASK EVERY_TIME, no guard -> run twice; rule B: EVERY_TIME + onlyIfNoOpenTask -> run, run, mark task DONE, run',
    expected: 'DESIGN §5.10.5: A makes 2 tasks; B makes 1, then skips (open task), then makes a 2nd after the first is DONE' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [900] });
    const inv = cust.invoices[0];
    const A = await oneInvoiceRule(cust, inv, [rt.action.task({ repeat: 'EVERY_TIME', title: 'Every {{Invoice.Number}}', onlyIfNoOpenTask: false })]);
    await H.runAndWait(t, A.id); await H.runAndWait(t, A.id);
    const tA = await H.tasksOfRule(t, A.id);
    const B = await oneInvoiceRule(cust, inv, [rt.action.task({ repeat: 'EVERY_TIME', title: 'Guarded {{Invoice.Number}}', onlyIfNoOpenTask: true })]);
    await H.runAndWait(t, B.id);
    const b2 = await H.runAndWait(t, B.id);
    const tB1 = await H.tasksOfRule(t, B.id);
    let st = null;
    if (tB1[0]) st = await rt.tasks.setStatus(t, tB1[0].id, 'DONE', tB1[0].version);
    await H.runAndWait(t, B.id);
    const tB2 = await H.tasksOfRule(t, B.id);
    return { ok: tA.length === 2 && tB1.length === 1 && b2.effects.some((e) => e.status === 'SKIPPED') && tB2.length === 2,
      actual: { everyTime: tA.length, guardedAfter2Runs: tB1.length, guardedRun2Effects: b2.effects.map((e) => [e.status, e.message]), markDone: st?.status, guardedAfterDone: tB2.length } };
  });

  await K({ id: 'AUT-API-43', prd: 'A3', feature: 'Promise action', title: 'CREATE_PROMISE takes the amount from the record, dates it, links the invoice and uses the Collection POC',
    steps: 'invoice 3000 with 1000 paid; CREATE_PROMISE amount FIELD Invoice.Balance, promisedDate TODAY+7, collectionPoc null, notes "Auto {{Invoice.Number}}", linkRecord; run now',
    expected: 'DESIGN §5.10.5: promise 2000.00 dated today+7, Collection POC = customer\'s (nora), linked to the invoice, notes rendered' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [3000] });
    const inv = cust.invoices[0];
    await H.payInvoice(t, cust, inv, 1000);
    const rule = await oneInvoiceRule(cust, inv, [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' }, promisedDate: { base: 'TODAY', offsetDays: 7 }, notes: 'Auto {{Invoice.Number}}' })]);
    const { run, effects } = await H.runAndWait(t, rule.id);
    const pid = effects[0]?.result?.id;
    const p = pid ? (await rt.api('GET', `/api/promises/${pid}`, { token: t })).json : null;
    const linked = JSON.stringify(p?.invoices || p?.invoiceIds || p?.links || '');
    const poc = p?.collectionPocUserId ?? p?.collectionPoc?.id;
    return { ok: effects[0]?.result?.type === 'PROMISE' && Number(p?.amount) === 2000 && p?.promisedDate === rt.day(7) && poc === U.nora.id && linked.includes(String(inv.id)) && p?.notes === `Auto ${inv.invoiceNumber}`,
      actual: { run: run?.status, effect: effects[0] && [effects[0].status, effects[0].result, effects[0].message], promise: p && { amount: p.amount, promisedDate: p.promisedDate, poc, notes: p.notes, linked: H.short(linked, 200), status: p.status } } };
  });

  await K({ id: 'AUT-API-44', prd: 'A3', feature: 'Promise action', title: 'CREATE_PROMISE on a fully paid invoice is skipped ("Nothing owed"); a second run does not duplicate an open promise',
    steps: 'invoice 500 fully paid -> rule CREATE_PROMISE FIELD Invoice.Balance; run. Invoice 800 unpaid -> CREATE_PROMISE EVERY_TIME with onlyIfNoOpenPromise; run twice',
    expected: 'DESIGN §5.10.5: first effect SKIPPED (Nothing owed), no promise; second rule makes 1 promise, then SKIPPED' },
  async () => {
    const paid = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [500] });
    await H.payInvoice(t, paid, paid.invoices[0], 500);
    const r1 = await oneInvoiceRule(paid, paid.invoices[0], [rt.action.promise({ amount: { source: 'FIELD', field: 'Invoice.Balance' } })]);
    const x1 = await H.runAndWait(t, r1.id);
    const open = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [800] });
    const r2 = await oneInvoiceRule(open, open.invoices[0], [rt.action.promise({ repeat: 'EVERY_TIME', onlyIfNoOpenPromise: true, amount: { source: 'FIXED', value: 300 } })]);
    await H.runAndWait(t, r2.id);
    const x3 = await H.runAndWait(t, r2.id);
    const ps1 = await H.promisesOf(t, paid.c.id); const ps2 = await H.promisesOf(t, open.c.id);
    return { ok: x1.effects[0]?.status === 'SKIPPED' && /nothing owed/i.test(x1.effects[0]?.message || '') && ps1.length === 0 && ps2.length === 1 && x3.effects[0]?.status === 'SKIPPED',
      actual: { paidEffect: x1.effects.map((e) => [e.status, e.message]), promisesOnPaid: ps1.length, promisesOnOpen: ps2.length, secondRun: x3.effects.map((e) => [e.status, e.message]) } };
  });

  await K({ id: 'AUT-API-45', prd: 'A3', feature: 'Dispute action', title: 'CREATE_DISPUTE opens one internal dispute with a rendered reason and record-level assignees; the customer cannot see it',
    steps: 'North invoice (sales POC sam, customer Collection POC nora); CREATE_DISPUTE reason "Review {{Invoice.Number}} for {{Customer.Name}}", proposedChange none, assignees [SALES_POC@RECORD]; run twice; GET dispute as admin and as the customer login',
    expected: 'DESIGN §5.10.11: one PENDING dispute, internal=true, origin AUTOMATION, ruleId, reason rendered, assignee resolves to sam; second run no duplicate; customer login 404' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const invR = await rt.createInvoice(t, { customerId: cust.c.id, amount: 1500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    const inv = invR.json;
    const rule = await oneInvoiceRule(cust, inv, [rt.action.dispute({ reason: 'Review {{Invoice.Number}} for {{Customer.Name}}', assignees: [rt.tok.role('SALES_POC', 'RECORD')] })]);
    const x1 = await H.runAndWait(t, rule.id);
    const x2 = await H.runAndWait(t, rule.id);
    const did = x1.effects[0]?.result?.id;
    const d = did ? (await rt.api('GET', `/api/disputes/${did}`, { token: t })).json : null;
    const custTok = await rt.login(cust.c.username);
    const asCust = did ? await rt.api('GET', `/api/disputes/${did}`, { token: custTok }) : { status: 0 };
    const people = (d?.assignees || []).flatMap((a) => a.people.map((p) => p.userId));
    const all = await rt.list(t, '/api/disputes', { filter: [`customerId:eq:${cust.c.id}`] });
    return { ok: d && d.internal === true && d.origin === 'AUTOMATION' && d.ruleId === rule.id && d.reason === `Review ${inv.invoiceNumber} for ${cust.c.name}` && people.includes(U.sam.id)
      && (all.json?.content || []).length === 1 && x2.run?.done === 0 && asCust.status === 404,
      actual: { dispute: d && { id: d.id, status: d.status, internal: d.internal, origin: d.origin, ruleId: d.ruleId, reason: d.reason, people }, disputesOnCustomer: (all.json?.content || []).length, run2: x2.run && [x2.run.done, x2.run.skipped], customerGet: asCust.status } };
  });

  await K({ id: 'AUT-API-46', prd: 'A3', feature: 'Email action', title: 'SEND_EMAIL saves an outbound email on the record with rendered subject/body, from the role holder, to the customer',
    steps: 'North customer (Collection POC nora) invoice 2222; SEND_EMAIL from COLLECTION_POC@CUSTOMER, to [CUSTOMER], subject "Invoice {{Invoice.Number}} for {{Customer.Name}}", body "Balance {{Invoice.Balance}} due {{Invoice.DueDate}}"; run; GET /api/emails?entityType=INVOICE&entityId=I',
    expected: 'PRD A3/A4; mail transport none -> one OUTBOUND email, status NOT_SENT/NOT_CONFIGURED, subject/body rendered, from nora, to the customer address, sentBy admin (owner); effect DONE with the "not configured" warning' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [2222] });
    const inv = cust.invoices[0];
    const rule = await oneInvoiceRule(cust, inv, [rt.action.email({ from: rt.tok.role('COLLECTION_POC', 'CUSTOMER'), to: [rt.tok.customer()],
      subject: 'Invoice {{Invoice.Number}} for {{Customer.Name}}', body: 'Balance {{Invoice.Balance}} due {{Invoice.DueDate}}' })]);
    const { run, effects } = await H.runAndWait(t, rule.id);
    await rt.sleep(1500);
    const em = await H.emailsOn(t, 'INVOICE', inv.id);
    const e = em[0];
    const ok = em.length === 1 && e.direction === 'OUTBOUND' && /NOT_SENT|NOT_CONFIGURED/.test(e.status) && e.subject === `Invoice ${inv.invoiceNumber} for ${cust.c.name}`
      && e.body === `Balance ${money(2222)} due ${inv.dueDate}` && e.from?.userId === U.nora.id && e.to?.length === 1 && e.to[0].address === cust.c.email && e.sentBy?.userId === ctx.adminId
      && effects[0]?.status === 'DONE' && (effects[0]?.warnings || []).some((x) => /not configured/i.test(x));
    return { ok, actual: { run: run?.status, effect: effects[0] && [effects[0].status, effects[0].warnings], emails: em.map((x) => ({ id: x.id, status: x.status, subject: x.subject, body: x.body, from: x.from?.name, to: x.to?.map((y) => y.address), sentBy: x.sentBy?.name })) } };
  });

  await K({ id: 'AUT-API-47', prd: 'A3', feature: 'Email action', title: 'An automation email never goes to a colleague who cannot see the record',
    steps: 'North invoice; SEND_EMAIL to [USER wendy (West only), USER sam (North MANAGE)]; run',
    expected: 'DESIGN §5.10.5 step 5: wendy dropped with warning "... cannot see this record, so was not emailed"; sam is the only recipient' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1500] });
    const inv = cust.invoices[0];
    const rule = await oneInvoiceRule(cust, inv, [rt.action.email({ from: null, to: [rt.tok.user(U.wendy.id), rt.tok.user(U.sam.id)], subject: 'Heads up {{Invoice.Number}}', body: 'x' })]);
    const { effects } = await H.runAndWait(t, rule.id);
    await rt.sleep(1000);
    const em = await H.emailsOn(t, 'INVOICE', inv.id);
    const to = (em[0]?.to || []).map((x) => x.userId);
    return { ok: em.length === 1 && to.length === 1 && to[0] === U.sam.id && (effects[0]?.warnings || []).some((x) => /cannot see/i.test(x)),
      actual: { effect: effects[0] && [effects[0].status, effects[0].warnings], recipients: to } };
  });

  await K({ id: 'AUT-API-48', prd: 'A3', feature: 'Task action', title: 'A task assignee who cannot see the record is dropped with a warning; the task is still made',
    steps: 'North invoice; CREATE_TASK assignees [USER wendy (West only), USER nora]; run',
    expected: 'DESIGN §5.9.1: effect DONE with warning "Wendy West cannot see this record, so was not assigned"; task assignees = nora only' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1500] });
    const rule = await oneInvoiceRule(cust, cust.invoices[0], [rt.action.task({ title: 'Probe {{Invoice.Number}}', assignees: [rt.tok.user(U.wendy.id), rt.tok.user(U.nora.id)] })]);
    const { effects } = await H.runAndWait(t, rule.id);
    const task = effects[0]?.result?.id ? await H.taskDetail(t, effects[0].result.id) : null;
    const who = (task?.assignees || []).map((a) => a.token.userId);
    return { ok: effects[0]?.status === 'DONE' && (effects[0]?.warnings || []).some((x) => /cannot see/i.test(x)) && JSON.stringify(who) === JSON.stringify([U.nora.id]),
      actual: { effect: effects[0] && [effects[0].status, effects[0].warnings], assignees: who } };
  });

  await K({ id: 'AUT-API-49', prd: 'A4', feature: 'Placeholders', title: 'Placeholders are offered per level: Customer.* on every rule, plus the record level of the rule\'s entity',
    steps: 'GET /api/automation/placeholders?entityType=CUSTOMER|INVOICE|PAYMENT',
    expected: 'DESIGN §5.10.4: CUSTOMER -> Customer + General; INVOICE -> Customer + Invoice (Number, Balance…) + General; PAYMENT -> Customer + Payment + General' },
  async () => {
    const lv = {};
    for (const e of ['CUSTOMER', 'INVOICE', 'PAYMENT']) {
      const r = await rt.automation.placeholders(t, e);
      lv[e] = (r.json?.groups || []).map((g) => `${g.level}:${g.items.length}`);
      lv[e + '_tokens'] = (r.json?.groups || []).flatMap((g) => g.items.map((i) => i.token));
    }
    const has = (e, tok) => lv[e + '_tokens'].includes(tok);
    const ok = has('CUSTOMER', '{{Customer.Name}}') && !has('CUSTOMER', '{{Invoice.Balance}}') && has('INVOICE', '{{Invoice.Balance}}') && has('INVOICE', '{{Customer.Name}}')
      && has('PAYMENT', '{{Payment.Amount}}') && !has('PAYMENT', '{{Invoice.Number}}') && has('INVOICE', '{{Today}}');
    return { ok, actual: { CUSTOMER: lv.CUSTOMER, INVOICE: lv.INVOICE, PAYMENT: lv.PAYMENT } };
  });

  await K({ id: 'AUT-API-50', prd: 'A4', feature: 'Placeholders', title: 'Payment and Customer rules render their own placeholders; a fallback is used when a value is empty',
    steps: 'payment 600 on a North customer: PAYMENT rule CREATE_TASK "Paid {{Payment.Amount}} ({{Payment.Number}}) by {{Customer.Name}} for {{Payment.Invoices}}"; CUSTOMER rule CREATE_TASK "{{Customer.Name}} owes {{Customer.Outstanding}} [{{Customer.Region}}] CP={{Customer.CollectionPoc}} SP={{Customer.SuccessPoc|Our team}}"',
    expected: 'PRD A4: payment title "Paid ₹600.00 (#<id>) by <name> for INV-…"; customer title "<name> owes ₹1,400.00 [North] CP=Nora North SP=Our team"' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [2000] });
    const pay = await H.payInvoice(t, cust, cust.invoices[0], 600);
    const pr = await H.mustRule(t, { trigger: H.manual('PAYMENT'), condition: H.and(`id:eq:${pay.id}`), actions: [rt.action.task({ title: 'Paid {{Payment.Amount}} ({{Payment.Number}}) by {{Customer.Name}} for {{Payment.Invoices}}' })] });
    const cr = await H.mustRule(t, { trigger: H.manual('CUSTOMER'), condition: H.and(`id:eq:${cust.c.id}`), actions: [rt.action.task({ title: '{{Customer.Name}} owes {{Customer.Outstanding}} [{{Customer.Region}}] CP={{Customer.CollectionPoc}} SP={{Customer.SuccessPoc|Our team}}' })] });
    ctx.cleanup.push(pr.id, cr.id);
    const a = await H.runAndWait(t, pr.id); const b = await H.runAndWait(t, cr.id);
    const ta = a.effects[0]?.result?.id ? await H.taskDetail(t, a.effects[0].result.id) : null;
    const tb = b.effects[0]?.result?.id ? await H.taskDetail(t, b.effects[0].result.id) : null;
    const wantA = `Paid ${money(600)} (#${pay.id}) by ${cust.c.name} for ${cust.invoices[0].invoiceNumber}`;
    const wantB = `${cust.c.name} owes ${money(1400)} [North] CP=Nora North SP=Our team`;
    return { ok: ta?.title === wantA && tb?.title === wantB, actual: { payment: ta?.title, customer: tb?.title }, expected: `"${wantA}" and "${wantB}"` };
  });

  await K({ id: 'AUT-API-51', prd: 'A4', feature: 'Placeholders', title: 'Unknown placeholders, or ones not offered at the rule\'s level, are refused at save',
    steps: 'INVOICE rule task title "{{Foo.Bar}}"; INVOICE rule title "{{Payment.Amount}}"; CUSTOMER rule email subject "{{Invoice.Number}}"; PAYMENT rule email body "{{Invoice.Balance}}"',
    expected: 'DESIGN §5.10.4: 400 with fieldErrors on actions[0].title / actions[0].subject / actions[0].body' },
  async () => {
    const tries = [
      ['INVOICE', rt.action.task({ title: '{{Foo.Bar}}' }), 'title'],
      ['INVOICE', rt.action.task({ title: '{{Payment.Amount}}' }), 'title'],
      ['CUSTOMER', rt.action.email({ subject: 'x {{Invoice.Number}}' }), 'subject'],
      ['PAYMENT', rt.action.email({ body: 'x {{Invoice.Balance}}' }), 'body'],
    ];
    const out = [];
    for (const [e, a, field] of tries) {
      const r = await H.createRule(t, { enabled: false, trigger: H.manual(e), actions: [a] });
      if (r.ok) ctx.cleanup.push(r.json.id);
      out.push([e, field, r.status, r.json?.fieldErrors?.[`actions[0].${field}`] || H.short(r.json?.fieldErrors, 120)]);
    }
    return { ok: out.every((o) => o[2] === 400 && typeof o[3] === 'string' && /\{\{/.test(o[3])), actual: out };
  });

  // Customer with two Collection POCs: primary sam, secondary nora.
  async function twoPocCustomer() {
    const c = await rt.createCustomer(t, { name: rt.uniq('TwoPoc'), regionId: N, paymentTerm: 'NET_30' });
    await rt.addCustomerPoc(t, c.id, 'COLLECTION', U.nora.id, false);
    await rt.addCustomerPoc(t, c.id, 'COLLECTION', U.sam.id, true);
    const cust = { c, invoices: [], collector: U.sam.id };
    const invR = await rt.createInvoice(t, { customerId: cust.c.id, amount: 1800, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id });
    const cj = (await rt.api('GET', `/api/customers/${cust.c.id}`, { token: t })).json || {};
    return { cust, inv: invR.json, pocs: H.short(cj.collectionPocs, 300) };
  }

  await K({ id: 'AUT-API-52', prd: 'A4', feature: 'Role resolution', title: 'A role held by several people resolves to the primary for placeholders, the email sender and the promise Collection POC',
    steps: 'customer with Collection POCs sam (primary) and nora; INVOICE rule: task title "{{Customer.CollectionPoc}}", SEND_EMAIL from COLLECTION_POC@CUSTOMER to CUSTOMER, CREATE_PROMISE collectionPoc COLLECTION_POC@CUSTOMER; run',
    expected: 'PRD A4 "a role held by several people resolves to the primary": title "Sam South", email from sam, promise Collection POC sam' },
  async () => {
    const { cust, inv, pocs } = await twoPocCustomer();
    const rule = await oneInvoiceRule(cust, inv, [
      rt.action.task({ title: '{{Customer.CollectionPoc}}' }),
      rt.action.email({ from: rt.tok.role('COLLECTION_POC', 'CUSTOMER'), to: [rt.tok.customer()], subject: 'From {{Customer.CollectionPoc}}', body: 'b' }),
      rt.action.promise({ amount: { source: 'FIXED', value: 100 }, collectionPoc: rt.tok.role('COLLECTION_POC', 'CUSTOMER') })]);
    const { effects } = await H.runAndWait(t, rule.id);
    const byType = Object.fromEntries(effects.map((e) => [e.actionType, e]));
    const task = byType.CREATE_TASK?.result?.id ? await H.taskDetail(t, byType.CREATE_TASK.result.id) : null;
    await rt.sleep(800);
    const em = (await H.emailsOn(t, 'INVOICE', inv.id))[0];
    const p = byType.CREATE_PROMISE?.result?.id ? (await rt.api('GET', `/api/promises/${byType.CREATE_PROMISE.result.id}`, { token: t })).json : null;
    const poc = p?.collectionPocUserId ?? p?.collectionPoc?.id;
    return { ok: task?.title === 'Sam South' && em?.from?.userId === U.sam.id && poc === U.sam.id,
      actual: { pocs, taskTitle: task?.title, emailFrom: em?.from?.name, promisePoc: poc, effects: effects.map((e) => [e.actionType, e.status, e.message]) } };
  });

  // Triage r1: a task or email To role means every active holder who can see the record (DESIGN §5.9.1, the email
  // To field); "resolves to the primary" is only for placeholders, the From role and the promise Collection POC (AUT-API-52).
  await K({ id: 'AUT-API-53', prd: 'A4', feature: 'Role resolution', title: 'A task assigned to a role held by several people resolves to every active holder who can see the record, primary flagged',
    steps: 'customer with Collection POCs sam (primary) and nora; CREATE_TASK assignees [COLLECTION_POC@CUSTOMER]; run; GET /api/tasks/{id}; GET /api/tasks/mine as nora and sam',
    expected: 'DESIGN §5.9.1 (triage r1): one assignee whose people are [Sam South (primary:true), Nora North (primary:false)]; the task is in both sam\'s and nora\'s My tasks' },
  async () => {
    const { cust, inv, pocs } = await twoPocCustomer();
    const rule = await oneInvoiceRule(cust, inv, [rt.action.task({ title: 'Role {{Invoice.Number}}', assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] })]);
    const { effects } = await H.runAndWait(t, rule.id);
    const tid = effects[0]?.result?.id;
    const task = tid ? await H.taskDetail(t, tid) : null;
    const assignees = task?.assignees || [];
    const ppl = assignees[0]?.people || [];
    const people = ppl.map((p) => `${p.name}(primary:${!!p.primary})`);
    const sam = ppl.find((p) => p.userId === U.sam.id);
    const nora = ppl.find((p) => p.userId === U.nora.id);
    const mineN = (await rt.tasks.mine(w.tokens.nora)).json?.content?.some((x) => x.id === tid);
    const mineS = (await rt.tasks.mine(w.tokens.sam)).json?.content?.some((x) => x.id === tid);
    const ok = assignees.length === 1 && ppl.length === 2 && sam?.name === 'Sam South' && sam.primary === true
      && nora?.name === 'Nora North' && !nora.primary && ppl.filter((p) => p.primary).length === 1 && mineS === true && mineN === true;
    return { ok, actual: { pocs, taskId: tid, assignees: assignees.length, resolvedPeople: people, inSamMyTasks: mineS, inNoraMyTasks: mineN },
      evidence: 'GET /api/tasks/{id} assignees[0].people; GET /api/tasks/mine as sam and nora' };
  });

  await K({ id: 'AUT-API-54', prd: 'A4', feature: 'Role resolution', title: 'An email addressed To a role held by several people goes To every holder',
    steps: 'customer with Collection POCs sam (primary) and nora; SEND_EMAIL to [COLLECTION_POC@CUSTOMER]; run; read the email row',
    expected: 'DESIGN §5.9.1 / email To field (triage r1): to = [Sam South, Nora North], both TO (neither in cc)' },
  async () => {
    const { cust, inv, pocs } = await twoPocCustomer();
    const rule = await oneInvoiceRule(cust, inv, [rt.action.email({ from: null, to: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')], subject: 'To role {{Invoice.Number}}', body: 'b' })]);
    await H.runAndWait(t, rule.id);
    await rt.sleep(800);
    const em = (await H.emailsOn(t, 'INVOICE', inv.id))[0];
    const to = (em?.to || []).map((x) => x.name);
    const cc = (em?.cc || []).map((x) => x.name);
    const want = ['Nora North', 'Sam South'];
    const ok = JSON.stringify([...to].sort()) === JSON.stringify(want) && !cc.some((n) => want.includes(n));
    return { ok, actual: { pocs, emailId: em?.id, to, cc }, expected: 'to [Sam South, Nora North] (any order), neither in cc' };
  });

  await K({ id: 'AUT-API-55', prd: 'A3', feature: 'Role resolution', title: 'A record-level role (Sales POC of this invoice) resolves to the invoice\'s own POC, not the customer\'s',
    steps: 'customer Collection POC nora; invoice with Sales POC sam; CREATE_TASK assignees [SALES_POC@RECORD]; run',
    expected: 'PRD A3 role-at-a-level: the task resolves to sam' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [] });
    const inv = (await rt.createInvoice(t, { customerId: cust.c.id, amount: 1000, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id })).json;
    const rule = await oneInvoiceRule(cust, inv, [rt.action.task({ title: 'Rec {{Invoice.SalesPoc}}', assignees: [rt.tok.role('SALES_POC', 'RECORD')] })]);
    const { effects } = await H.runAndWait(t, rule.id);
    const task = effects[0]?.result?.id ? await H.taskDetail(t, effects[0].result.id) : null;
    const people = (task?.assignees || []).flatMap((a) => a.people.map((p) => p.userId));
    return { ok: JSON.stringify(people) === JSON.stringify([U.sam.id]) && task?.title === 'Rec Sam South', actual: { title: task?.title, people } };
  });
};
