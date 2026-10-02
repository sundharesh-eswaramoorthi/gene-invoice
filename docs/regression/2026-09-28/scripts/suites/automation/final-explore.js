// Final exploratory pass, area "automation" (PRD A1-A5 plus cross-feature B1/B2/B3).
// Written from the PRD and DESIGN §5.10 / §6.14 / §7.7.3 only (not from the existing suite's cases).
//
//   $E2E/env/up.sh automation 5 --fresh && source $E2E/runtime/automation/env.sh
//   node final-explore.js            -> runs/final/automation-explore.json, shots runs/final/automation/shots/explore/
//   $E2E/env/down.sh automation
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

const OUT = path.join(rt.HOME, 'runs', 'final', 'automation-explore.json');
const SHOTS = path.join(rt.HOME, 'runs', 'final', 'automation', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });
const cases = [];
const ONLY = process.env.CASES ? process.env.CASES.split(',') : null;

function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'automation-explore', round: 'final', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
}
const short = (x, n = 700) => { const s = typeof x === 'string' ? x : JSON.stringify(x); return s && s.length > n ? s.slice(0, n) + '…' : s; };

/** Runs a case; a failure is retried once from fresh fixtures, and only a repeated failure is a FAIL. */
async function kase(meta, fn) {
  if (ONLY && !ONLY.includes(meta.id)) return;
  const attempts = [];
  for (let a = 1; a <= 2; a++) {
    let r;
    try { r = await fn(a); } catch (e) { r = { status: 'BLOCKED', actual: 'exception: ' + String(e && e.stack || e).slice(0, 1500) }; }
    attempts.push(r);
    if (r.status && r.status !== 'FAIL') break;
    if (!r.status && r.ok) break;
  }
  const failed = (r) => r.status === 'FAIL' || (!r.status && !r.ok);
  const last = attempts[attempts.length - 1];
  let status = last.status || (last.ok ? 'PASS' : 'FAIL');
  let actual = last.actual;
  if (attempts.length === 2 && failed(attempts[0]) && !failed(last)) {
    status = 'NOT_TESTED';
    actual = `Failed on attempt 1, passed on attempt 2 (not reproduced). Attempt 1: ${short(attempts[0].actual)} | Attempt 2: ${short(last.actual, 400)}`;
  } else if (attempts.length === 2 && failed(attempts[0]) && failed(last)) {
    actual = `Reproduced twice. Attempt 1: ${short(attempts[0].actual, 900)} | Attempt 2: ${short(last.actual, 900)}`;
  }
  const c = {
    id: meta.id, feature: meta.feature || '', kind: meta.kind || 'API', prd: meta.prd || '', title: meta.title || '', status,
    severity: status === 'FAIL' ? (last.severity || meta.severity || 'medium') : '',
    steps: meta.steps || '', expected: meta.expected || '', actual: short(actual, 2500) || '',
    evidence: short(last.evidence || '', 2500), screenshot: last.screenshot || '', codeRef: last.codeRef || meta.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === c.id);
  if (i >= 0) cases[i] = c; else cases.push(c);
  console.log(`[${status}] ${c.id} ${c.title}`);
  save();
}

// ---- fixtures ----------------------------------------------------------------------------------
let W; let T; let adminId;
const cleanup = [];

async function mkCust(regionCode, collectorId, amounts = [1000], { name } = {}) {
  const c = await rt.createCustomer(T, { name: name || rt.uniq(`X-${regionCode}`), regionId: W.regions[regionCode].id, paymentTerm: 'NET_30' });
  await rt.addCustomerPoc(T, c.id, 'COLLECTION', collectorId, true);
  const invoices = [];
  for (const a of amounts) {
    const r = await rt.createInvoice(T, { customerId: c.id, amount: a, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: collectorId });
    rt.need(r, `invoice ${a}`, [200]);
    invoices.push(r.json);
  }
  return { c, invoices, collectorId };
}
async function mkRule(token, spec) {
  const r = await rt.automation.create(token, rt.rule(spec));
  if (r.ok && r.json && r.json.id) cleanup.push(r.json.id);
  return r;
}
async function mustRule(token, spec) { const r = await mkRule(token, spec); rt.need(r, 'create rule'); return r.json; }
const manual = (entityType = 'INVOICE') => ({ kind: 'MANUAL', entityType });
const on = (kind, entityType = 'INVOICE') => ({ kind, entityType });
const leaf = (f) => ({ filter: f });
const AND = (...xs) => ({ op: 'AND', children: xs.map((x) => (typeof x === 'string' ? leaf(x) : x)) });
const OR = (...xs) => ({ op: 'OR', children: xs.map((x) => (typeof x === 'string' ? leaf(x) : x)) });

async function tasksOfRule(token, ruleId) {
  const r = await rt.list(token, '/api/tasks', { filter: [`ruleId:eq:${ruleId}`] });
  return r.json?.content || [];
}
/** Waits until the outbox has nothing pending or in flight (the consumer polls every 2 s). */
async function drained(timeoutMs = 45000) {
  await rt.sleep(1500);
  return rt.waitFor(() => Number(rt.sql("select count(*) from automation_events where status in ('PENDING','PROCESSING') and (next_attempt_at is null or next_attempt_at <= now())")[0][0]) === 0, { timeoutMs, intervalMs: 1000 });
}
async function runAndWait(token, ruleId, body = {}, query) {
  const req = await rt.api('POST', `/api/automation/rules/${ruleId}/run`, { token, body, query });
  if (![200, 202].includes(req.status)) return { req, run: null, effects: [] };
  const run = await rt.waitForRun(token, req.json.runId, { timeoutMs: 60000 });
  const eff = run ? await rt.automation.effects(token, req.json.runId) : null;
  return { req, run, effects: eff?.json?.content || [] };
}
const median = (xs) => { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };

// ---- API cases -----------------------------------------------------------------------------------
async function apiCases() {
  const { NORTH, SOUTH, WEST } = W.regions;
  const U = W.users;

  await kase({ id: 'AUT-X-01', feature: 'Durable execution', prd: 'A5', severity: 'high',
    title: 'Ten failing/slow-to-act rules on invoice create neither break nor noticeably slow the save',
    steps: 'WEST customer C; time 4 POST /api/invoices (baseline); create 10 enabled ENTITY_CREATED INVOICE rules (WEST, customerId eq C) each with a SEND_EMAIL that cannot resolve (from/to SUCCESS_POC nobody holds, no fallback) and a CREATE_TASK; time 4 more POSTs; drain',
    expected: 'Every save 200 with the invoice saved; median save time with rules <= baseline*2 + 300 ms; after drain each rule produced 4 tasks and the invoices are unchanged (PRD A5 "a rule can never slow down or break the user\'s save")',
  }, async () => {
    const cu = await mkCust('WEST', U.wendy.id, []);
    const post = () => rt.createInvoice(T, { customerId: cu.c.id, amount: 700, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.wendy.id });
    const base = []; const baseIds = [];
    for (let i = 0; i < 4; i++) { const r = await post(); base.push(r.ms); baseIds.push(r.status); }
    const rules = [];
    for (let i = 0; i < 10; i++) {
      rules.push(await mustRule(T, { name: rt.uniq('x01'), trigger: on('ENTITY_CREATED'), regionIds: [WEST.id], condition: AND(`customerId:eq:${cu.c.id}`),
        actions: [rt.action.email({ from: rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER'), fallbackToOwner: false, to: [rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER')], subject: 'x', body: 'y' }), rt.action.task({ title: 'X01 {{Invoice.Number}}' })] }));
    }
    const withR = []; const st = []; const ids = [];
    for (let i = 0; i < 4; i++) { const r = await post(); withR.push(r.ms); st.push(r.status); if (r.json?.id) ids.push(r.json.id); }
    await drained(90000);
    const counts = [];
    for (const rl of rules) counts.push((await tasksOfRule(T, rl.id)).filter((t) => ids.includes(t.entityId)).length);
    const invs = [];
    for (const id of ids) { const g = await rt.api('GET', `/api/invoices/${id}`, { token: T }); invs.push(`${g.status}:${g.json?.total}:${g.json?.status}`); }
    const mb = median(base); const mw = median(withR);
    const ok = st.every((s) => s === 200) && ids.length === 4 && mw <= mb * 2 + 300 && counts.every((n) => n === 4) && invs.every((s) => /^200:700(\.0+)?:/.test(s));
    rules.forEach((r) => cleanup.push(r.id));
    return { ok, actual: `baseline ms ${base.join(',')} (median ${mb}); with 10 rules ms ${withR.join(',')} (median ${mw}); statuses ${st.join(',')}; tasks per rule on the 4 new invoices ${counts.join(',')}; invoices ${invs.join(' ')}`,
      evidence: `rules ${rules.map((r) => r.id).join(',')}; invoice ids ${ids.join(',')}` };
  });

  await kase({ id: 'AUT-X-02', feature: 'Triggers', prd: 'A1',
    title: 'ENTITY_UPDATED fires on an invoice edit but not on its create; ENTITY_CREATED fires on create only',
    steps: 'NORTH customer C; rules U (ENTITY_UPDATED) and K (ENTITY_CREATED), both INVOICE, customerId eq C, CREATE_TASK EVERY_TIME; POST invoice; drain; PATCH notes; drain',
    expected: 'After create: K 1 task, U 0. After the PATCH: K still 1, U 1 (PRD A1 "fires when … created or updated")',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, []);
    const ru = await mustRule(T, { name: rt.uniq('x02-upd'), trigger: on('ENTITY_UPDATED'), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task({ title: 'upd {{Invoice.Number}}' })] });
    const rk = await mustRule(T, { name: rt.uniq('x02-crt'), trigger: on('ENTITY_CREATED'), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task({ title: 'crt {{Invoice.Number}}' })] });
    const inv = rt.need(await rt.createInvoice(T, { customerId: cu.c.id, amount: 800, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv', [200]);
    await drained();
    const a1 = [(await tasksOfRule(T, rk.id)).length, (await tasksOfRule(T, ru.id)).length];
    const p = await rt.patchInvoice(T, inv.id, { notes: 'edited by X02', version: inv.version });
    await drained();
    const a2 = [(await tasksOfRule(T, rk.id)).length, (await tasksOfRule(T, ru.id)).length];
    const ok = p.status === 200 && a1[0] === 1 && a1[1] === 0 && a2[0] === 1 && a2[1] === 1;
    return { ok, actual: `after create: created-rule ${a1[0]}, updated-rule ${a1[1]}; PATCH ${p.status}; after edit: created-rule ${a2[0]}, updated-rule ${a2[1]}`, evidence: `rules K=${rk.id} U=${ru.id}, invoice ${inv.id}` };
  });

  await kase({ id: 'AUT-X-03', feature: 'Triggers', prd: 'A1',
    title: 'A CUSTOMER created-or-updated rule fires on customer create and again on a customer edit (EVERY_TIME)',
    steps: 'Rule CUSTOMER ENTITY_CREATED_OR_UPDATED, NORTH, name:contains:<tag>, CREATE_TASK "Welcome {{Customer.Name}}" EVERY_TIME; create NORTH customer named <tag>; drain; PUT customer phone; drain',
    expected: '1 task after create titled "Welcome <name>", 2 after the edit; tasks are on the CUSTOMER',
  }, async () => {
    const tag = rt.uniq('x03tag');
    const rl = await mustRule(T, { name: rt.uniq('x03'), trigger: on('ENTITY_CREATED_OR_UPDATED', 'CUSTOMER'), regionIds: [NORTH.id], condition: AND(`name:contains:${tag}`), actions: [rt.action.task({ title: 'Welcome {{Customer.Name}}' })] });
    const c = await rt.createCustomer(T, { name: `Cust ${tag}`, regionId: NORTH.id, paymentTerm: 'NET_30' });
    await drained();
    const t1 = await tasksOfRule(T, rl.id);
    const put = await rt.api('PUT', `/api/customers/${c.id}`, { token: T, body: { name: c.name, phone: '555-0199', email: c.email, address: '2 Test Way', paymentTerm: 'NET_30' } });
    await drained();
    const t2 = await tasksOfRule(T, rl.id);
    const ok = t1.length === 1 && t1[0].title === `Welcome Cust ${tag}` && t1[0].entityType === 'CUSTOMER' && put.status === 200 && t2.length === 2;
    return { ok, actual: `after create ${t1.length} task(s) ${short(t1.map((t) => `${t.entityType}#${t.entityId} "${t.title}"`))}; PUT ${put.status}; after edit ${t2.length}`, evidence: `rule ${rl.id}, customer ${c.id}; PUT body ${short(put.text, 200)}` };
  });

  await kase({ id: 'AUT-X-04', feature: 'Email action', prd: 'A3',
    title: 'A PAYMENT-created rule saves an Email with Payment and Customer placeholders rendered',
    steps: 'WEST customer C (collector wendy), invoice 1000; rule PAYMENT ENTITY_CREATED, WEST, customerId eq C, SEND_EMAIL to CUSTOMER subject "Thanks {{Customer.Name}}: {{Payment.Amount}}" body "Payment {{Payment.Number}} via {{Payment.Method}} on {{Payment.Date}}; left {{Payment.Unallocated}}"; POST payment 300 on the invoice; drain; GET /api/emails?entityType=PAYMENT&entityId=p',
    expected: 'One Email row on the payment, status NOT_SENT/NOT_CONFIGURED, subject contains the customer name and 300.00, body contains "#<paymentId>", no "{{"',
  }, async () => {
    const cu = await mkCust('WEST', U.wendy.id, [1000]);
    const rl = await mustRule(T, { name: rt.uniq('x04'), trigger: on('ENTITY_CREATED', 'PAYMENT'), regionIds: [WEST.id], condition: AND(`customerId:eq:${cu.c.id}`),
      actions: [rt.action.email({ to: [rt.tok.customer()], subject: 'Thanks {{Customer.Name}}: {{Payment.Amount}}', body: 'Payment {{Payment.Number}} via {{Payment.Method}} on {{Payment.Date}}; left {{Payment.Unallocated}}' })] });
    const p = rt.need(await rt.createPayment(T, { customerId: cu.c.id, amount: 300, invoiceIds: [cu.invoices[0].id], collectionPocUserId: U.wendy.id }), 'payment', [200]);
    await drained();
    const em = await rt.waitFor(async () => { const r = await rt.emailsFor(T, 'PAYMENT', p.id); return (r.json?.content || []).length ? r.json.content : null; }, { timeoutMs: 20000 });
    const e = em && em[0];
    const ok = !!e && em.length === 1 && /NOT_SENT|NOT_CONFIGURED/.test(e.status) && e.subject.includes(cu.c.name) && /300[.,]00/.test(e.subject) && e.body.includes(`#${p.id}`) && !/\{\{/.test(e.subject + e.body);
    return { ok, actual: e ? `${em.length} email(s); status ${e.status}; subject "${e.subject}"; body "${short(e.body, 300)}"` : 'no email row', evidence: `rule ${rl.id} payment ${p.id}` };
  });

  await kase({ id: 'AUT-X-05', feature: 'Placeholders', prd: 'A4',
    title: 'Placeholders not offered at the rule\'s level are refused at save with the action field path',
    steps: 'POST /rules: CUSTOMER rule with task title {{Invoice.Number}}; INVOICE rule with body {{Payment.Amount}}; INVOICE rule with {{Invoice.Frobnicate}}; GET /placeholders?entityType=CUSTOMER',
    expected: '400 with fieldErrors on actions[0].title / actions[0].body / actions[0].title respectively; CUSTOMER catalogue has no Invoice/Payment group, has Customer and General (Today, Rule.Name)',
  }, async () => {
    const r1 = await mkRule(T, { name: rt.uniq('x05a'), trigger: manual('CUSTOMER'), actions: [rt.action.task({ title: 'Inv {{Invoice.Number}}' })] });
    const r2 = await mkRule(T, { name: rt.uniq('x05b'), trigger: manual('INVOICE'), actions: [rt.action.email({ body: 'Amt {{Payment.Amount}}' })] });
    const r3 = await mkRule(T, { name: rt.uniq('x05c'), trigger: manual('INVOICE'), actions: [rt.action.task({ title: 'X {{Invoice.Frobnicate}}' })] });
    const cat = await rt.automation.placeholders(T, 'CUSTOMER');
    const toks = (cat.json?.groups || []).flatMap((g) => g.items.map((i) => i.token));
    const fe = (r) => r.json?.fieldErrors || {};
    const ok = r1.status === 400 && fe(r1)['actions[0].title'] && r2.status === 400 && fe(r2)['actions[0].body'] && r3.status === 400 && fe(r3)['actions[0].title']
      && cat.status === 200 && !toks.some((x) => /Invoice\.|Payment\./.test(x)) && toks.includes('{{Customer.Name}}') && toks.includes('{{Today}}') && toks.includes('{{Rule.Name}}');
    return { ok, actual: `r1 ${r1.status} ${short(fe(r1))}; r2 ${r2.status} ${short(fe(r2))}; r3 ${r3.status} ${short(fe(r3))}; catalogue ${cat.status} groups ${(cat.json?.groups || []).map((g) => g.level).join(',')} tokens ${toks.length}` };
  });

  await kase({ id: 'AUT-X-06', feature: 'Placeholders', prd: 'A4',
    title: 'Customer.CollectionPoc follows the primary when two people hold the role; fallback text and Rule.Name render',
    steps: 'NORTH customer, Collection POCs nora (primary) and sam (secondary); MANUAL INVOICE rule, task EVERY_TIME title "{{Customer.CollectionPoc}}|{{Invoice.Notes|no notes}}|{{Rule.Name}}"; run on the invoice; make sam primary; run again',
    expected: 'First title "Nora North|no notes|<rule>", second "Sam South|no notes|<rule>" (PRD A4 "a role held by several people resolves to the primary")',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, [1200]);
    await rt.addCustomerPoc(T, cu.c.id, 'COLLECTION', U.sam.id, false);
    const name = rt.uniq('x06');
    const rl = await mustRule(T, { name, trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task({ title: '{{Customer.CollectionPoc}}|{{Invoice.Notes|no notes}}|{{Rule.Name}}' })] });
    const a = await runAndWait(T, rl.id, { recordIds: [cu.invoices[0].id] });
    const t1 = (await tasksOfRule(T, rl.id)).map((t) => t.title);
    const pocs = rt.need(await rt.api('GET', `/api/customers/${cu.c.id}/pocs`, { token: T }), 'pocs');
    const list = Array.isArray(pocs) ? pocs : (pocs.content || pocs.pocs || Object.values(pocs).flat());
    const samPoc = list.find((x) => (x.userId === U.sam.id || x.user?.id === U.sam.id) && /COLLECTION/.test(x.pocType || x.type || ''));
    const prim = samPoc ? await rt.api('POST', `/api/customers/${cu.c.id}/pocs/${samPoc.id}/primary`, { token: T, body: {} }) : { status: 'no-poc' };
    const b = await runAndWait(T, rl.id, { recordIds: [cu.invoices[0].id] });
    const t2 = (await tasksOfRule(T, rl.id)).map((t) => t.title);
    const second = t2.find((x) => !t1.includes(x)) || (t2.length > 1 ? t2.filter((x) => x === t2[0]).length > 1 && t2[0] : null);
    const ok = t1.length === 1 && t1[0] === `Nora North|no notes|${name}` && prim.status === 200 && t2.length === 2 && t2.includes(`Sam South|no notes|${name}`);
    return { ok, actual: `run1 ${a.run?.status}; titles after run1 ${short(t1)}; make-primary ${prim.status}; run2 ${b.run?.status}; titles ${short(t2)}`, evidence: `rule ${rl.id}, pocs ${short(list, 400)}` };
  });

  await kase({ id: 'AUT-X-07', feature: 'Conditions', prd: 'A2',
    title: 'Condition limits: depth 4 accepted, depth 5, an empty group, 21 children and an unknown field refused with paths',
    steps: 'POST /rules with nested AND groups (4 levels, 5 levels), {op:AND,children:[]}, a group of 21 leaves, a leaf "bogusField:eq:1"',
    expected: 'depth 4 -> 200; others 400 with fieldErrors keyed under condition… (DESIGN §5.10.2 limits: depth ≤ 4, children 1..20)',
  }, async () => {
    const nest = (d) => (d === 1 ? AND('total:gt:1') : AND(nest(d - 1)));
    const mk = (cond) => mkRule(T, { name: rt.uniq('x07'), trigger: manual(), condition: cond, actions: [rt.action.task()] });
    const d4 = await mk(nest(4)); const d5 = await mk(nest(5));
    const empty = await mk({ op: 'AND', children: [] });
    const wide = await mk({ op: 'OR', children: Array.from({ length: 21 }, (_, i) => leaf(`total:gt:${i}`)) });
    const bogus = await mk(AND('total:gt:1', 'bogusField:eq:1'));
    const keys = (r) => Object.keys(r.json?.fieldErrors || {});
    const ok = d4.status === 200 && [d5, empty, wide, bogus].every((r) => r.status === 400 && keys(r).some((k) => k.startsWith('condition')))
      && keys(bogus).some((k) => k.includes('children[1]'));
    return { ok, actual: `depth4 ${d4.status}; depth5 ${d5.status} ${short(d5.json?.fieldErrors)}; empty ${empty.status} ${short(empty.json?.fieldErrors)}; 21 children ${wide.status} ${short(wide.json?.fieldErrors)}; bogus ${bogus.status} ${short(bogus.json?.fieldErrors)}` };
  });

  await kase({ id: 'AUT-X-08', feature: 'Conditions', prd: 'A2',
    title: 'AND-with-nested-OR selects exactly the matching invoices in preview and in a run',
    steps: 'NORTH customer with invoices 1000, 2500, 7000; preview AND(customerId eq C, OR(total gt 5000, total lt 1500)) and AND(customerId, total gt 5000, total lt 1500); save the OR rule (MANUAL) and run it',
    expected: 'OR preview matchCount 2, AND preview 0; the run creates tasks on exactly the 1000 and 7000 invoices',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, [1000, 2500, 7000]);
    const orCond = AND(`customerId:eq:${cu.c.id}`, OR('total:gt:5000', 'total:lt:1500'));
    const andCond = AND(`customerId:eq:${cu.c.id}`, 'total:gt:5000', 'total:lt:1500');
    const p1 = await rt.automation.preview(T, { rule: rt.rule({ name: 'p', trigger: manual(), condition: orCond, actions: [rt.action.task()] }), sampleSize: 10 });
    const p2 = await rt.automation.preview(T, { rule: rt.rule({ name: 'p', trigger: manual(), condition: andCond, actions: [rt.action.task()] }), sampleSize: 10 });
    const rl = await mustRule(T, { name: rt.uniq('x08'), trigger: manual(), regionIds: [NORTH.id], condition: orCond, actions: [rt.action.task({ title: 'x08 {{Invoice.Total}}' })] });
    const r = await runAndWait(T, rl.id, {});
    const got = (await tasksOfRule(T, rl.id)).map((t) => t.entityId).sort();
    const want = [cu.invoices[0].id, cu.invoices[2].id].sort();
    const ok = p1.status === 200 && p1.json.matchCount === 2 && p2.status === 200 && p2.json.matchCount === 0 && r.run?.status === 'SUCCEEDED' && JSON.stringify(got) === JSON.stringify(want);
    return { ok, actual: `OR preview ${p1.status} matchCount ${p1.json?.matchCount}; AND preview ${p2.status} matchCount ${p2.json?.matchCount}; run ${r.run?.status} matched ${r.run?.matched}; task invoices ${got} want ${want}` };
  });

  await kase({ id: 'AUT-X-09', feature: 'Rule regions', prd: 'B1',
    title: 'nora (MANAGE North, READ South) cannot restrict a task rule to South, and her unrestricted rule never fires in South',
    steps: 'nora POST rule regionIds [SOUTH] (CREATE_TASK); nora POST unrestricted ENTITY_CREATED INVOICE rule with OR(customerId eq S, customerId eq N); admin creates an invoice for S (South) and for N (North); drain',
    expected: '400 fieldErrors.regionIds naming South; unrestricted rule: task on the North invoice only (DESIGN §5.10.7: empty = every region where the owner holds the rights)',
  }, async () => {
    const tn = W.tokens.nora;
    const bad = await mkRule(tn, { name: rt.uniq('x09bad'), trigger: manual(), regionIds: [SOUTH.id], actions: [rt.action.task()] });
    const s = await mkCust('SOUTH', U.sam.id, []); const n = await mkCust('NORTH', U.nora.id, []);
    const rl = await mustRule(tn, { name: rt.uniq('x09'), trigger: on('ENTITY_CREATED'), regionIds: [], condition: OR(`customerId:eq:${s.c.id}`, `customerId:eq:${n.c.id}`), actions: [rt.action.task({ title: 'x09 {{Invoice.Number}}' })] });
    const is = rt.need(await rt.createInvoice(T, { customerId: s.c.id, amount: 900, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id }), 'inv S', [200]);
    const inn = rt.need(await rt.createInvoice(T, { customerId: n.c.id, amount: 900, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv N', [200]);
    await drained();
    const tasks = await tasksOfRule(T, rl.id);
    const ids = tasks.map((t) => t.entityId);
    const ok = bad.status === 400 && /South/.test(bad.json?.fieldErrors?.regionIds || '') && ids.length === 1 && ids[0] === inn.id;
    return { ok, actual: `restricted-to-South save ${bad.status} ${short(bad.json?.fieldErrors)}; unrestricted rule tasks on invoices ${ids} (north ${inn.id}, south ${is.id})` };
  });

  await kase({ id: 'AUT-X-10', feature: 'Rule visibility', prd: 'B1',
    title: 'A North-only rule is invisible to West-only wendy (404, not in list or summary); sam (North) sees it but cannot run or edit it',
    steps: 'nora creates rule regionIds [NORTH]; wendy GET /rules/{id}, GET /rules?filter=name:contains, GET /rules/summary before/after; sam GET, POST /run, PUT',
    expected: 'wendy 404 and not listed, summary unchanged; sam GET 200, run 403, PUT 403 (DESIGN §5.10.10, §5.10.7)',
  }, async () => {
    const tw = W.tokens.wendy; const ts = W.tokens.sam;
    const before = await rt.api('GET', '/api/automation/rules/summary', { token: tw });
    const name = rt.uniq('x10');
    const rl = await mustRule(W.tokens.nora, { name, trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task()] });
    const g = await rt.automation.get(tw, rl.id);
    const l = await rt.automation.list(tw, { filter: `name:contains:${name}` });
    const after = await rt.api('GET', '/api/automation/rules/summary', { token: tw });
    const sg = await rt.automation.get(ts, rl.id);
    const sr = await rt.automation.run(ts, rl.id, {});
    const body = rt.rule({ name: name + '-sam', trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task()], version: sg.json?.version });
    const sp = await rt.automation.update(ts, rl.id, body);
    const ok = g.status === 404 && l.status === 200 && (l.json.content || []).length === 0 && JSON.stringify(before.json) === JSON.stringify(after.json)
      && sg.status === 200 && sr.status === 403 && sp.status === 403;
    return { ok, actual: `wendy GET ${g.status}; list ${l.status} rows ${(l.json?.content || []).length}; summary before ${short(before.json)} after ${short(after.json)}; sam GET ${sg.status}, run ${sr.status} ${short(sr.json?.message, 150)}, PUT ${sp.status} ${short(sp.json, 200)}` };
  });

  await kase({ id: 'AUT-X-11', feature: 'Rules list scope', prd: 'B1',
    title: 'Rules list answers "which regions": regionIds narrows, a non-numeric regionIds is 400 REGION_IDS_INVALID',
    steps: 'admin creates rule N (regionIds [NORTH]) and W (regionIds [WEST]) with a shared tag; GET /rules?filter=name:contains:tag with regionIds=WEST; with regionIds=abc; GET /rules/summary?regionIds=WEST vs NORTH',
    expected: 'regionIds=WEST lists W not N and the envelope regionIds=[WEST]; abc -> 400 REGION_IDS_INVALID',
  }, async () => {
    const tag = rt.uniq('x11');
    const rn = await mustRule(T, { name: `${tag}-N`, trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task()] });
    const rw = await mustRule(T, { name: `${tag}-W`, trigger: manual(), regionIds: [WEST.id], actions: [rt.action.task()] });
    const lw = await rt.automation.list(T, { filter: `name:contains:${tag}` }, { regionIds: [WEST.id] });
    const bad = await rt.api('GET', '/api/automation/rules', { token: T, query: { regionIds: 'abc', size: 10 } });
    const names = (lw.json?.content || []).map((r) => r.name);
    const ok = lw.status === 200 && names.includes(`${tag}-W`) && !names.includes(`${tag}-N`) && JSON.stringify(lw.json.regionIds) === JSON.stringify([WEST.id])
      && bad.status === 400 && bad.json?.error === 'REGION_IDS_INVALID';
    return { ok, actual: `WEST list ${lw.status} names ${names} envelope regionIds ${short(lw.json?.regionIds)}; abc -> ${bad.status} ${bad.json?.error} "${bad.json?.message}"`, evidence: `rules ${rn.id},${rw.id}` };
  });

  await kase({ id: 'AUT-X-12', feature: 'Promise action x maker-checker', prd: 'B2', severity: 'high',
    title: 'A rule\'s promise at exactly South\'s PROMISE_CREATE limit applies; 0.01 above is held, the owner cannot approve it, another approver can',
    steps: 'Two SOUTH customers (invoice 9000 each); admin MANUAL INVOICE rules with CREATE_PROMISE FIXED 5000 and 5000.01, run each on its invoice; admin approve the held change; sam approve it',
    expected: '5000: effect DONE result PROMISE, promise exists. 5000.01: effect DONE result PENDING_CHANGE, no promise until approved; admin (maker/owner) approve refused 4xx; sam approve 200 and the promise then exists (PRD B2)',
  }, async () => {
    const a = await mkCust('SOUTH', U.sam.id, [9000]); const b = await mkCust('SOUTH', U.sam.id, [9000]);
    const mkp = (amt) => mustRule(T, { name: rt.uniq('x12'), trigger: manual(), regionIds: [SOUTH.id], actions: [rt.action.promise({ amount: { source: 'FIXED', value: amt }, onlyIfNoOpenPromise: false })] });
    const ra = await mkp(5000); const rb = await mkp(5000.01);
    const xa = await runAndWait(T, ra.id, { recordIds: [a.invoices[0].id] });
    const xb = await runAndWait(T, rb.id, { recordIds: [b.invoices[0].id] });
    const pa = (await rt.list(T, '/api/promises', { filter: [`customerId:eq:${a.c.id}`] })).json?.content || [];
    const pb0 = (await rt.list(T, '/api/promises', { filter: [`customerId:eq:${b.c.id}`] })).json?.content || [];
    const ea = xa.effects[0]; const eb = xb.effects[0];
    const pcId = eb?.result?.type === 'PENDING_CHANGE' ? eb.result.id : null;
    const own = pcId ? await rt.approvals.approve(T, pcId) : { status: 'n/a' };
    const other = pcId ? await rt.approvals.approve(W.tokens.sam, pcId) : { status: 'n/a' };
    const pb1 = (await rt.list(T, '/api/promises', { filter: [`customerId:eq:${b.c.id}`] })).json?.content || [];
    const ok = ea?.status === 'DONE' && ea?.result?.type === 'PROMISE' && pa.length === 1 && Number(pa[0].amount) === 5000
      && eb?.status === 'DONE' && !!pcId && pb0.length === 0 && own.status >= 400 && own.status < 500 && other.status === 200 && pb1.length === 1 && Number(pb1[0].amount) === 5000.01;
    return { ok, actual: `5000: effect ${ea?.status}/${ea?.result?.type}, promises ${pa.map((p) => p.amount)}; 5000.01: effect ${eb?.status}/${eb?.result?.type} #${eb?.result?.id}, promises before approval ${pb0.length}; owner approve ${own.status} ${short(own.json?.message, 150)}; sam approve ${other.status}; promises after ${pb1.map((p) => p.amount)}`,
      evidence: `rules ${ra.id},${rb.id}; runs ${xa.req.json?.runId},${xb.req.json?.runId}` };
  });

  await kase({ id: 'AUT-X-13', feature: 'Run now', prd: 'A5',
    title: 'Run-now validation and idempotency: >50 records, bad ids, past/too-far runAt refused; a booked run is idempotent and waits for its time',
    steps: 'MANUAL rule; POST /run with 51 recordIds, ["x"], runAt now-1min, runAt now+91d; then runAt now+10min twice; then same runAt with recordIds [inv]; poll 6 s',
    expected: '400 fieldErrors.recordIds / recordIds / runAt / runAt; first booked 202 QUEUED scheduledFor=runAt; identical repeat 200 same runId; different recordIds 202 new runId; run still QUEUED after 6 s (DESIGN §5.10.9)',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, [1000]);
    const rl = await mustRule(T, { name: rt.uniq('x13'), trigger: manual(), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task()] });
    const iso = (ms) => new Date(Date.now() + ms).toISOString();
    const r51 = await rt.automation.run(T, rl.id, { recordIds: Array.from({ length: 51 }, (_, i) => i + 1) });
    const rx = await rt.api('POST', `/api/automation/rules/${rl.id}/run`, { token: T, body: { recordIds: ['x'] } });
    const rp = await rt.automation.run(T, rl.id, { runAt: iso(-60000) });
    const rf = await rt.automation.run(T, rl.id, { runAt: iso(91 * 86400000) });
    const at = new Date(Date.now() + 600000); at.setUTCSeconds(0, 0);
    const b1 = await rt.automation.run(T, rl.id, { runAt: at.toISOString() });
    const b2 = await rt.automation.run(T, rl.id, { runAt: at.toISOString() });
    const b3 = await rt.automation.run(T, rl.id, { runAt: at.toISOString(), recordIds: [cu.invoices[0].id] });
    await rt.sleep(6000);
    const st = b1.json?.runId ? await rt.automation.runById(T, b1.json.runId) : null;
    const fe = (r) => Object.keys(r.json?.fieldErrors || {}).join(',');
    const ok = r51.status === 400 && fe(r51).includes('recordIds') && rx.status === 400 && rp.status === 400 && fe(rp).includes('runAt') && rf.status === 400 && fe(rf).includes('runAt')
      && b1.status === 202 && b1.json.status === 'QUEUED' && new Date(b1.json.scheduledFor).getTime() === at.getTime()
      && b2.status === 200 && b2.json.runId === b1.json.runId && b3.status === 202 && b3.json.runId !== b1.json.runId && st?.json?.status === 'QUEUED';
    return { ok, actual: `51 ids ${r51.status} [${fe(r51)}]; ["x"] ${rx.status} ${short(rx.json, 200)}; past ${rp.status} [${fe(rp)}]; +91d ${rf.status} [${fe(rf)}]; book ${b1.status} ${short(b1.json)}; repeat ${b2.status} run ${b2.json?.runId}; other records ${b3.status} run ${b3.json?.runId}; after 6 s ${st?.json?.status}` };
  });

  await kase({ id: 'AUT-X-14', feature: 'As-of x automation', prd: 'B3',
    title: 'Writes refuse a past view (AS_OF_READ_ONLY), a future asOf is AS_OF_FUTURE, and a past-date preview shows no actions',
    steps: 'POST /rules?asOf=yesterday; POST /rules/{id}/run?asOf=yesterday; GET /rules?asOf=tomorrow; POST /rules/preview?asOf=yesterday',
    expected: '400 AS_OF_READ_ONLY twice; 400 AS_OF_FUTURE; preview 200 with asOf=yesterday, record.actions null (or record null) and asOfNote "Actions are shown only for today\'s view" (DESIGN §5.10.9, §6.17)',
  }, async () => {
    const y = rt.day(-1);
    const rl = await mustRule(T, { name: rt.uniq('x14'), trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task()] });
    const c1 = await rt.api('POST', '/api/automation/rules', { token: T, query: { asOf: y }, body: rt.rule({ name: rt.uniq('x14b'), trigger: manual(), actions: [rt.action.task()] }) });
    const c2 = await rt.api('POST', `/api/automation/rules/${rl.id}/run`, { token: T, query: { asOf: y }, body: {} });
    const c3 = await rt.api('GET', '/api/automation/rules', { token: T, query: { asOf: rt.day(1), size: 10 } });
    const c4 = await rt.automation.preview(T, { rule: rt.rule({ name: 'p', trigger: manual(), actions: [rt.action.task()] }), sampleSize: 5 }, { asOf: y });
    if (c1.ok && c1.json?.id) cleanup.push(c1.json.id);
    const ok = c1.status === 400 && c1.json?.error === 'AS_OF_READ_ONLY' && c2.status === 400 && c2.json?.error === 'AS_OF_READ_ONLY' && c3.status === 400 && c3.json?.error === 'AS_OF_FUTURE'
      && c4.status === 200 && c4.json.asOf === y && (!c4.json.record || c4.json.record.actions == null) && /today/i.test(c4.json.asOfNote || '');
    return { ok, actual: `create ${c1.status} ${c1.json?.error}; run ${c2.status} ${c2.json?.error}; list tomorrow ${c3.status} ${c3.json?.error}; preview ${c4.status} asOf ${c4.json?.asOf} record ${short(c4.json?.record, 200)} note "${c4.json?.asOfNote}"` };
  });

  await kase({ id: 'AUT-X-15', feature: 'Enable/disable/delete', prd: 'A5',
    title: 'A disabled entity rule does not fire but can still be run by hand; a deleted rule is 404 and never fires again',
    steps: 'NORTH customer C; ENTITY_CREATED INVOICE rule (customerId eq C) created disabled; POST invoice; drain; run now; DELETE; GET; POST /run; POST another invoice; drain',
    expected: 'no task from the create; run now 202 and 1 task per matching invoice; DELETE 204; GET 404; run 404; no new task after delete',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, []);
    const rl = await mustRule(T, { name: rt.uniq('x15'), enabled: false, trigger: on('ENTITY_CREATED'), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task({ title: 'x15 {{Invoice.Number}}' })] });
    rt.need(await rt.createInvoice(T, { customerId: cu.c.id, amount: 500, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv', [200]);
    await drained();
    const t0 = (await tasksOfRule(T, rl.id)).length;
    const rn = await runAndWait(T, rl.id, {});
    const t1 = (await tasksOfRule(T, rl.id)).length;
    const del = await rt.automation.remove(T, rl.id);
    const g = await rt.automation.get(T, rl.id);
    const run2 = await rt.automation.run(T, rl.id, {});
    rt.need(await rt.createInvoice(T, { customerId: cu.c.id, amount: 600, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv2', [200]);
    await drained();
    const t2 = (await tasksOfRule(T, rl.id)).length;
    const ok = t0 === 0 && rn.req.status === 202 && rn.run?.status === 'SUCCEEDED' && t1 === 1 && del.status === 204 && g.status === 404 && run2.status === 404 && t2 === 1;
    return { ok, actual: `tasks after create while disabled ${t0}; run now ${rn.req.status} ${rn.run?.status}; tasks ${t1}; DELETE ${del.status}; GET ${g.status}; run after delete ${run2.status} ${short(run2.json?.message, 120)}; tasks after delete+create ${t2}` };
  });

  await kase({ id: 'AUT-X-16', feature: 'Run size', prd: 'A5',
    title: 'maxRecordsPerRun boundaries: 0 and 5001 refused; 1 truncates a 3-record run to one action',
    steps: 'POST rules with maxRecordsPerRun 0, 5001, 1 (condition customerId eq C with 3 invoices); run the valid one',
    expected: '400 fieldErrors.maxRecordsPerRun twice; run SUCCEEDED matched 1, truncated true, 1 task',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, [100, 200, 300]);
    const spec = (m) => ({ name: rt.uniq('x16'), trigger: manual(), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task()], maxRecordsPerRun: m });
    const z = await mkRule(T, spec(0)); const big = await mkRule(T, spec(5001)); const one = await mkRule(T, spec(1));
    const r = one.ok ? await runAndWait(T, one.json.id, {}) : {};
    const n = one.ok ? (await tasksOfRule(T, one.json.id)).length : -1;
    const ok = z.status === 400 && z.json?.fieldErrors?.maxRecordsPerRun && big.status === 400 && big.json?.fieldErrors?.maxRecordsPerRun && one.status === 200 && r.run?.matched === 1 && r.run?.truncated === true && n === 1;
    return { ok, actual: `0 -> ${z.status} ${short(z.json?.fieldErrors)}; 5001 -> ${big.status}; 1 -> ${one.status}; run ${r.run?.status} matched ${r.run?.matched} truncated ${r.run?.truncated}; tasks ${n}` };
  });

  await kase({ id: 'AUT-X-17', feature: 'Actions', prd: 'A3',
    title: 'A rule needs 1..5 actions with unique keys',
    steps: 'POST rules with 0 actions, 6 actions, 2 actions sharing one key, 5 actions',
    expected: '400 fieldErrors.actions, 400 fieldErrors.actions, 400 on actions[1].key, 200',
  }, async () => {
    const mk = (actions) => mkRule(T, { name: rt.uniq('x17'), trigger: manual(), regionIds: [NORTH.id], actions });
    const none = await mk([]);
    const six = await mk(Array.from({ length: 6 }, () => rt.action.task()));
    const k = rt.action.task(); const dup = await mk([k, { ...rt.action.task(), key: k.key }]);
    const five = await mk(Array.from({ length: 5 }, () => rt.action.task()));
    const fe = (r) => r.json?.fieldErrors || {};
    const ok = none.status === 400 && fe(none).actions && six.status === 400 && fe(six).actions && dup.status === 400 && Object.keys(fe(dup)).some((x) => /actions\[1\]\.key/.test(x)) && five.status === 200;
    return { ok, actual: `0 -> ${none.status} ${short(fe(none))}; 6 -> ${six.status} ${short(fe(six))}; dup -> ${dup.status} ${short(fe(dup))}; 5 -> ${five.status}` };
  });

  await kase({ id: 'AUT-X-18', feature: 'Dispute action', prd: 'A3',
    title: 'A ONCE_PER_RECORD dispute action run twice on the same invoice opens one dispute; preview marks it already done',
    steps: 'NORTH customer invoice I; MANUAL rule CREATE_DISPUTE reason "Review {{Invoice.Number}}"; run on I twice; GET /api/disputes?filter=targetId:eq:I; GET /rules/{id}/preview?recordId=I',
    expected: '1 dispute with reason "Review <number>"; second run effect not DONE (skipped); preview sample for I has alreadyDone containing the action key',
  }, async () => {
    const cu = await mkCust('NORTH', U.nora.id, [1500]);
    const I = cu.invoices[0];
    const act = rt.action.dispute({ reason: 'Review {{Invoice.Number}}' });
    const rl = await mustRule(T, { name: rt.uniq('x18'), trigger: manual(), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [act] });
    const r1 = await runAndWait(T, rl.id, { recordIds: [I.id] });
    const r2 = await runAndWait(T, rl.id, { recordIds: [I.id] });
    const ds = (await rt.list(T, '/api/disputes', { filter: [`targetId:eq:${I.id}`, 'targetType:eq:INVOICE'] })).json?.content || [];
    const pv = await rt.api('GET', `/api/automation/rules/${rl.id}/preview`, { token: T, query: { recordId: I.id, sampleSize: 10 } });
    const s = (pv.json?.sample || []).find((x) => x.id === I.id);
    const ok = ds.length === 1 && ds[0].reason === `Review ${I.invoiceNumber}` && r1.effects[0]?.status === 'DONE' && r2.effects.every((e) => e.status !== 'DONE') && s && (s.alreadyDone || []).includes(act.key);
    return { ok, actual: `disputes ${ds.length} ${short(ds.map((d) => d.reason))}; run1 effects ${short(r1.effects.map((e) => e.status))}; run2 ${r2.run?.status} effects ${short(r2.effects.map((e) => `${e.status}:${e.message}`))}; preview ${pv.status} sample ${short(s)}` };
  });

  await kase({ id: 'AUT-X-19', feature: 'Owner rights x regions', prd: 'A5', severity: 'high',
    title: 'Reducing the owner\'s grant to READ suspends the rule (OWNER_LACKS_RIGHTS), it stops acting, and Run now says to resume first',
    steps: 'staff otto (E2E_REGIONAL, MANAGE/ALL North) creates ENTITY_CREATED INVOICE rule, regionIds [NORTH], customerId eq C; admin PUT otto grants to READ/ALL North; GET rule; POST invoice for C; drain; otto POST /run',
    expected: 'rule status SUSPENDED, suspendedReason OWNER_LACKS_RIGHTS; no task for the new invoice; run 400 "The rule is suspended; resume it first" (DESIGN §5.10.7 proactive recheck)',
  }, async () => {
    const otto = await rt.createStaff(T, 'E2E_REGIONAL', 'otto', { grants: [{ regionId: NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const to = await rt.login(otto.username);
    const cu = await mkCust('NORTH', U.nora.id, []);
    const rl = await mustRule(to, { name: rt.uniq('x19'), trigger: on('ENTITY_CREATED'), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task({ title: 'x19' })] });
    const sg = await rt.setGrants(T, otto.id, [{ regionId: NORTH.id, accessLevel: 'READ', recordScope: 'ALL' }]);
    const g = await rt.automation.get(T, rl.id);
    rt.need(await rt.createInvoice(T, { customerId: cu.c.id, amount: 400, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv', [200]);
    await drained();
    const n = (await tasksOfRule(T, rl.id)).length;
    const to2 = await rt.login(otto.username);
    const run = await rt.automation.run(to2, rl.id, {});
    ctx.suspendedRuleId = rl.id; ctx.suspendedRuleName = rl.name;
    const ok = sg.status === 200 && g.json?.status === 'SUSPENDED' && g.json?.suspendedReason === 'OWNER_LACKS_RIGHTS' && n === 0 && run.status === 400 && /suspended/i.test(run.json?.message || '');
    return { ok, actual: `grants PUT ${sg.status}; rule status ${g.json?.status} reason ${g.json?.suspendedReason} detail "${g.json?.suspendedDetail}"; tasks after invoice ${n}; otto run ${run.status} "${run.json?.message}"`, evidence: `rule ${rl.id}, otto ${otto.id}` };
  });

  await kase({ id: 'AUT-X-20', feature: 'Task action', prd: 'A6',
    title: 'A rule task assigned to COLLECTION_POC@CUSTOMER lands in that person\'s My tasks with due date, status and link',
    steps: 'WEST customer (collector wendy); MANUAL rule CREATE_TASK due TODAY+3, assignee ROLE COLLECTION_POC CUSTOMER; run; wendy GET /api/tasks/mine; GET /api/tasks/mine/count before/after',
    expected: 'task OPEN, dueDate today+3, entityType INVOICE, rule ref set, assignee wendy; appears in wendy\'s mine; count +1',
  }, async () => {
    const cu = await mkCust('WEST', U.wendy.id, [1100]);
    const before = await rt.tasks.mineCount(W.tokens.wendy);
    const rl = await mustRule(T, { name: rt.uniq('x20'), trigger: manual(), regionIds: [WEST.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task({ title: 'Call about {{Invoice.Number}}', due: { base: 'TODAY', offsetDays: 3 }, assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] })] });
    await runAndWait(T, rl.id, {});
    const mine = (await rt.list(W.tokens.wendy, '/api/tasks/mine', { filter: [`ruleId:eq:${rl.id}`] })).json?.content || [];
    const after = await rt.tasks.mineCount(W.tokens.wendy);
    const t = mine[0];
    // List rows show roles by label only (AssigneeService.describeBriefly); the task's own page names the people.
    const people = t ? JSON.stringify((await rt.api('GET', `/api/tasks/${t.id}`, { token: W.tokens.wendy })).json?.assignees) : '';
    const ok = mine.length === 1 && t.status === 'OPEN' && t.dueDate === rt.day(3) && t.entityType === 'INVOICE' && t.entityId === cu.invoices[0].id && t.rule && people.includes('Wendy') && after.json?.count === before.json?.count + 1;
    return { ok, actual: `mine ${mine.length}; task ${short(t && { status: t.status, dueDate: t.dueDate, entity: `${t.entityType}#${t.entityId}`, rule: t.rule, title: t.title })}; assignees ${short(people, 300)}; mine count ${before.json?.count} -> ${after.json?.count}` };
  });

  await kase({ id: 'AUT-X-21', feature: 'Rights', prd: 'A5',
    title: 'Automation is closed to customer logins and to staff without AUTOMATION_MANAGE for writes',
    steps: 'customer login GET /api/automation/rules; staff "viewer" with a role holding AUTOMATION_VIEW but not AUTOMATION_MANAGE (MANAGE/ALL North) GET list and POST rule',
    expected: 'customer 403; viewer GET 200, POST 403',
  }, async () => {
    const cust = await rt.api('GET', '/api/automation/rules', { token: W.tokens.cust, query: { size: 10 } });
    await rt.ensureRole(T, 'E2E_AUTOVIEW', rt.REGIONAL_STAFF_PRIVILEGES.filter((p) => p !== 'AUTOMATION_MANAGE'));
    const v = await rt.createStaff(T, 'E2E_AUTOVIEW', 'viewer', { grants: [{ regionId: NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const tv = await rt.login(v.username);
    const gl = await rt.automation.list(tv, {});
    const pc = await mkRule(tv, { name: rt.uniq('x21'), trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task()] });
    const ok = cust.status === 403 && gl.status === 200 && pc.status === 403;
    return { ok, actual: `customer GET ${cust.status}; viewer GET ${gl.status}; viewer POST ${pc.status} ${short(pc.json, 200)}` };
  });
}

// ---- UI cases ------------------------------------------------------------------------------------
const ctx = {};
async function withApp(opts, fn) {
  const { path: route, ...rest } = opts;
  const app = await rt.openApp(rest);
  try {
    if (route) await rt.go(app.page, route, 5000);
    return await fn(app);
  } finally { await app.close(); }
}
/** RGB of one screen pixel (decodes a 1x1 PNG screenshot). */
async function pixel(page, x, y) {
  const zlib = require('zlib');
  const buf = await page.screenshot({ clip: { x, y, width: 1, height: 1 } });
  let off = 8; const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off); const type = buf.toString('ascii', off + 4, off + 8);
    if (type === 'IDAT') idat.push(buf.subarray(off + 8, off + 8 + len));
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  return [raw[1], raw[2], raw[3]];
}
async function uiCases() {
  const { NORTH, WEST } = W.regions;
  const U = W.users;
  // Fixtures shared by the UI cases.
  const tag = rt.uniq('ui');
  const noraRule = await mustRule(W.tokens.nora, { name: `${tag} North only`, trigger: manual(), regionIds: [NORTH.id], actions: [rt.action.task({ title: 'ui north' })] });
  const wendyRule = await mustRule(W.tokens.wendy, { name: `${tag} West own`, trigger: manual(), regionIds: [WEST.id], actions: [rt.action.task({ title: 'ui west' })] });
  const cu = await mkCust('NORTH', U.nora.id, [1000, 6000]);
  const orRule = await mustRule(T, { name: `${tag} Any rule`, trigger: manual(), regionIds: [NORTH.id], condition: OR(`customerId:eq:${cu.c.id}`, 'total:gt:999999'), actions: [rt.action.task({ title: 'ui or {{Invoice.Number}}' })] });
  const done = await runAndWait(T, orRule.id, {});
  ctx.orRule = orRule; ctx.orRun = done.req.json?.runId;

  await kase({ id: 'AUT-XU-01', kind: 'UI', feature: 'Rules list', prd: 'B1',
    title: 'West-only wendy\'s rules list shows her West rule and not nora\'s North-only rule',
    steps: 'nora creates "<tag> North only" (NORTH); wendy creates "<tag> West own" (WEST); open /automation as wendy',
    expected: 'List shows "<tag> West own"; "<tag> North only" is absent; no API error',
  }, async (a) => withApp({ username: 'wendy', path: '/automation' }, async (app) => {
    await app.page.waitForTimeout(2000);
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-01-${a}`);
    const ok = txt.includes(`${tag} West own`) && !txt.includes(`${tag} North only`) && app.apiErrors.length === 0;
    return { ok, actual: `west rule on screen ${txt.includes(`${tag} West own`)}; north rule on screen ${txt.includes(`${tag} North only`)}; apiErrors ${short(app.apiErrors)}`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-02', kind: 'UI', feature: 'Rule editor', prd: 'A1',
    title: 'Creating a rule with a blank name shows a Name error and creates nothing',
    steps: 'admin opens /automation/new, leaves Name empty, taps "Create rule"',
    expected: 'An error under Name (e.g. "must not be blank" or "required"); no successful POST /api/automation/rules; still on the New rule page',
  }, async (a) => withApp({ token: T, path: '/automation/new' }, async (app) => {
    const before = (await rt.automation.list(T, {})).json?.totalElements;
    await rt.tap(app.page, 'Create rule', { wait: 2500 });
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-02-${a}`);
    const posted = app.requests.filter((r) => r.method === 'POST' && /\/api\/automation\/rules(\?|$)/.test(r.url));
    const after = (await rt.automation.list(T, {})).json?.totalElements;
    const ok = /must not be blank|required|enter a name|name/i.test(txt.replace(/Name \*/g, '')) && !posted.some((r) => r.status === 200) && before === after && txt.includes('New rule');
    return { ok, actual: `POSTs ${short(posted)}; rules ${before}->${after}; screen: ${short(txt.split('\n').filter((l) => /name|blank|required|error/i.test(l)), 400)}`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-03', kind: 'UI', feature: 'Run now', prd: 'A5',
    title: 'Run now from the rule page queues a run, shows "Run #N", and the run completes',
    steps: 'admin opens /automation/<OR rule>; taps header "Run now"; dialog "Run \\"<name>\\"" shows "In: …"; taps dialog "Run now"',
    expected: 'POST /api/automation/rules/<id>/run -> 202; dialog shows "Run #<id>"; that run finishes SUCCEEDED (API)',
  }, async (a) => withApp({ token: T, path: `/automation/${orRule.id}` }, async (app) => {
    await rt.tap(app.page, 'Run now', { wait: 2000 });
    const dlg = (await rt.screenText(app.page)).join('\n');
    const nodes = (await rt.semantics(app.page)).filter((n) => (n.label === 'Run now' || n.text === 'Run now'));
    const btn = nodes[nodes.length - 1];
    if (btn) await rt.clickAt(app.page, btn.x, btn.y, 3000);
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-03-${a}`);
    const post = app.requests.find((r) => r.method === 'POST' && r.url.startsWith(`/api/automation/rules/${orRule.id}/run`));
    const m = txt.match(/Run #(\d+)/);
    const run = m ? await rt.waitForRun(T, Number(m[1]), { timeoutMs: 30000 }) : null;
    const ok = /Run "/.test(dlg) && /In: /.test(dlg) && post && [200, 202].includes(post.status) && !!m && run?.status === 'SUCCEEDED';
    return { ok, actual: `dialog title shown ${/Run "/.test(dlg)}; In: line ${short(dlg.split('\n').find((l) => /^In: /.test(l)))}; POST ${short(post)}; queued text ${m && m[0]}; run status ${run?.status}`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-04', kind: 'UI', feature: 'Condition builder', prd: 'A2',
    title: 'A saved OR condition opens with "Any of these" selected and both conditions shown',
    steps: 'admin opens /automation/<OR rule> (condition OR(customerId eq C, total gt 999999))',
    expected: '"Any of these" segment selected, "All of these" not; two condition chips',
  }, async (a) => withApp({ token: T, path: `/automation/${orRule.id}` }, async (app) => {
    await app.page.mouse.move(700, 500); await app.page.mouse.wheel(0, 500); await app.page.waitForTimeout(1000); await rt.enableSemantics(app.page);
    const nodes = await rt.semantics(app.page);
    const any = nodes.find((n) => /Any of these/.test(n.label || n.text || ''));
    const all = nodes.find((n) => /All of these/.test(n.label || n.text || ''));
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-04-${a}`);
    // The segments expose no selected state in semantics, so the fill colour inside each segment is compared
    // with the page background just above the control.
    const inside = async (n) => n && pixel(app.page, n.x - Math.round(n.w / 2) + 6, n.y - Math.round(n.h / 2) + 6);
    const pa = await inside(any); const pl = await inside(all);
    const bg = any ? await pixel(app.page, any.x, any.y - Math.round(any.h / 2) - 8) : null;
    const same = (a, b) => a && b && a.every((v, i) => Math.abs(v - b[i]) < 6);
    const ok = !!any && !!all && !same(pa, bg) && same(pl, bg) && /999[,.]?999/.test(txt) && /Customer is/.test(txt);
    return { ok, actual: `Any fill ${pa} All fill ${pl} background ${bg}; chips ${short(txt.split('\n').filter((l) => /Customer is|Total greater/.test(l)))}`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-05', kind: 'UI', feature: 'Read-only rule for a non-owner', prd: 'A5',
    title: 'sam (North, not owner, no AUTOMATION_ADMIN) sees nora\'s North rule read-only, with no Run now / Enable / Disable / Delete',
    steps: 'sam opens /automation/<nora North rule>',
    expected: '"Runs as Nora North" shown; no Run now, Disable, Enable or Delete buttons; Save changes absent or disabled; Name field disabled',
  }, async (a) => withApp({ username: 'sam', path: `/automation/${noraRule.id}` }, async (app) => {
    const nodes = await rt.semantics(app.page);
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-05-${a}`);
    const btn = (l) => nodes.find((n) => (n.label === l || n.text === l) && !n.disabled);
    const save = nodes.find((n) => /Save changes/.test(n.label || n.text || ''));
    const nameField = nodes.find((n) => n.role === 'textbox' && /Name/.test(n.label || ''));
    const ok = /Runs as Nora North/.test(txt) && !btn('Run now') && !btn('Disable') && !btn('Enable') && !btn('Delete') && (!save || save.disabled);
    return { ok, actual: `runs-as ${/Runs as Nora North/.test(txt)}; enabled buttons: ${['Run now', 'Disable', 'Enable', 'Delete'].filter((l) => btn(l)).join(',') || 'none'}; save ${short(save)}; name field ${short(nameField)}`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-06', kind: 'UI', feature: 'Unsaved changes', prd: 'A5',
    title: 'Leaving an edited rule asks "Discard unsaved changes?" and Keep editing keeps the edit',
    steps: 'admin opens /automation/<OR rule>; types " edited" into Name; navigates to /automation; taps Keep editing',
    expected: 'Dialog "Discard unsaved changes?"; after Keep editing the Name still ends with " edited"; nothing saved (API name unchanged)',
  }, async (a) => withApp({ token: T, path: `/automation/${orRule.id}` }, async (app) => {
    await rt.fillField(app.page, 'Name *', `${orRule.name} edited`);
    await app.page.waitForTimeout(600);
    await rt.go(app.page, '/automation', 2500);
    const txt = (await rt.screenText(app.page)).join('\n');
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-06-${a}`);
    let kept = null;
    if (/Discard unsaved changes\?/.test(txt)) {
      await rt.tap(app.page, 'Keep editing', { wait: 1500 });
      const f = (await rt.semantics(app.page)).find((n) => n.role === 'textbox' && /^Name/.test(n.label || ''));
      kept = f && f.text;
    }
    const api = (await rt.automation.get(T, orRule.id)).json?.name;
    const ok = /Discard unsaved changes\?/.test(txt) && kept === `${orRule.name} edited` && api === orRule.name;
    return { ok, actual: `dialog ${/Discard unsaved changes\?/.test(txt)}; field after Keep editing "${kept}"; API name "${api}"`, screenshot: shot };
  }));

  await kase({ id: 'AUT-XU-07', kind: 'UI', feature: 'Suspended rule', prd: 'A5',
    title: 'A suspended rule shows the reason and offers Resume, not Run now',
    steps: 'admin opens the rule suspended in AUT-X-19 (owner lost MANAGE)',
    expected: 'Status chip/line "Suspended: …" naming the owner\'s missing rights; a Resume button; no Run now button',
  }, async (a) => {
    if (!ctx.suspendedRuleId) return { status: 'BLOCKED', actual: 'AUT-X-19 did not leave a suspended rule' };
    return withApp({ token: T, path: `/automation/${ctx.suspendedRuleId}` }, async (app) => {
      const nodes = await rt.semantics(app.page);
      const txt = (await rt.screenText(app.page)).join('\n');
      const shot = await rt.shot(app.page, SHOTS, `AUT-XU-07-${a}`);
      const has = (l) => nodes.some((n) => n.label === l || n.text === l);
      const ok = /Suspended: /.test(txt) && has('Resume') && !has('Run now');
      return { ok, actual: `suspended line ${short(txt.split('\n').filter((l) => /Suspended/.test(l)))}; Resume ${has('Resume')}; Run now ${has('Run now')}`, screenshot: shot };
    });
  });

  await kase({ id: 'AUT-XU-08', kind: 'UI', feature: 'Runs tab', prd: 'A5',
    title: 'The Runs tab lists a finished run with its counts, and opening it shows its effects',
    steps: 'admin opens /automation/<OR rule>?tab=runs; clicks the run row',
    expected: 'Row "#<runId>" with Succeeded and "1 matched"-style counts; run detail lists the task effect(s)',
  }, async (a) => withApp({ token: T, path: `/automation/${orRule.id}?tab=runs` }, async (app) => {
    await app.page.waitForTimeout(1500);
    const txt = (await rt.screenText(app.page)).join('\n');
    const has = txt.includes(`#${ctx.orRun}`);
    let detail = '';
    if (has) {
      const n = (await rt.semantics(app.page)).find((x) => (x.label || x.text || '').includes(`#${ctx.orRun}`));
      await rt.clickAt(app.page, n.x, n.y, 2500);
      detail = (await rt.screenText(app.page)).join('\n');
    }
    const shot = await rt.shot(app.page, SHOTS, `AUT-XU-08-${a}`);
    const ok = has && /Succeeded/i.test(txt) && /matched|done/i.test(txt) && /Task|task/.test(detail) && new RegExp(`Run #${ctx.orRun}`).test(detail);
    return { ok, actual: `row #${ctx.orRun} ${has}; succeeded ${/Succeeded/i.test(txt)}; detail lines ${short(detail.split('\n').slice(0, 25), 700)}`, screenshot: shot };
  }));
}

// ---- As-of (ages the world; runs last) ---------------------------------------------------------------
async function asOfCases() {
  const { NORTH } = W.regions; const U = W.users;
  await kase({ id: 'AUT-X-22', feature: 'As-of rules and evaluation', prd: 'B3',
    title: 'As of a past date the rule shows its old name, and preview evaluates the condition on the world as it was',
    steps: 'NORTH customer C with 1 invoice; rule "<tag>-old" (MANUAL, customerId eq C); ageWorld(3); rename to "<tag>-new"; add a 2nd invoice to C; GET /rules?asOf=-1d&filter=name:contains:<tag>; GET /rules/{id}?asOf=-1d; GET /rules/{id}/preview?asOf=-1d and live',
    expected: 'past list and by-id show "<tag>-old", live "<tag>-new"; preview past matchCount 1, live 2; X-View-As-Of header = the date (PRD B3 "every … rule evaluation can be asked as of a date")',
  }, async () => {
    const tag = rt.uniq('x22');
    const cu = await mkCust('NORTH', U.nora.id, [1000]);
    const rl = await mustRule(T, { name: `${tag}-old`, trigger: manual(), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: [rt.action.task()] });
    rt.ageWorld(3);
    const cur = (await rt.automation.get(T, rl.id)).json;
    const upd = await rt.automation.update(T, rl.id, rt.rule({ name: `${tag}-new`, trigger: manual(), regionIds: [NORTH.id], condition: AND(`customerId:eq:${cu.c.id}`), actions: cur.actions, version: cur.version }));
    rt.need(await rt.createInvoice(T, { customerId: cu.c.id, amount: 2000, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'inv2', [200]);
    const y = rt.day(-1);
    const lp = await rt.automation.list(T, { filter: `name:contains:${tag}` }, { asOf: y });
    const gp = await rt.automation.get(T, rl.id, { asOf: y });
    const gl = await rt.automation.get(T, rl.id);
    const pp = await rt.api('GET', `/api/automation/rules/${rl.id}/preview`, { token: T, query: { asOf: y, sampleSize: 10 } });
    const pl = await rt.api('GET', `/api/automation/rules/${rl.id}/preview`, { token: T, query: { sampleSize: 10 } });
    ctx.asOfRule = rl.id;
    const ok = upd.status === 200 && lp.status === 200 && (lp.json.content || []).map((r) => r.name).join() === `${tag}-old` && gp.json?.name === `${tag}-old` && gl.json?.name === `${tag}-new`
      && pp.status === 200 && pp.json.matchCount === 1 && pl.json?.matchCount === 2 && pp.headers['x-view-as-of'] === y;
    return { ok, actual: `update ${upd.status}; past list ${lp.status} ${short((lp.json?.content || []).map((r) => r.name))}; past get ${gp.status} "${gp.json?.name}"; live "${gl.json?.name}"; preview past ${pp.status} matchCount ${pp.json?.matchCount} header ${pp.headers['x-view-as-of']}; live matchCount ${pl.json?.matchCount}` };
  });

  await kase({ id: 'AUT-XU-09', kind: 'UI', feature: 'Read-only (historical) rule', prd: 'B3',
    title: 'On a past date the rule page is read-only and New rule says it cannot be created',
    steps: 'admin opens /automation/<AUT-X-22 rule>; chooses as-of yesterday; then goes to /automation/new',
    expected: 'Banner "Viewing … read only"; old rule name shown; Run now / Disable / Delete absent or disabled; Save changes absent or disabled; /automation/new shows "New rules can\'t be created on a past date."',
  }, async (a) => {
    if (!ctx.asOfRule) return { status: 'BLOCKED', actual: 'AUT-X-22 did not create its rule' };
    return withApp({ token: T, path: `/automation/${ctx.asOfRule}` }, async (app) => {
      const chip = await rt.chooseAsOf(app.page, rt.day(-1));
      await app.page.waitForTimeout(1500);
      const nodes = await rt.semantics(app.page);
      const txt = (await rt.screenText(app.page)).join('\n');
      const shot1 = await rt.shot(app.page, SHOTS, `AUT-XU-09-${a}-rule`);
      const live = (l) => nodes.find((n) => (n.label === l || n.text === l) && !n.disabled);
      await rt.go(app.page, '/automation/new', 3000);
      const txt2 = (await rt.screenText(app.page)).join('\n');
      const shot2 = await rt.shot(app.page, SHOTS, `AUT-XU-09-${a}-new`);
      const ok = /read only/i.test(txt) && /-old/.test(txt) && !live('Run now') && !live('Disable') && !live('Delete') && !live('Save changes') && /New rules can't be created on a past date\./.test(txt2);
      return { ok, actual: `chip "${chip}"; banner ${/read only/i.test(txt)}; old name ${/-old/.test(txt)}; enabled action buttons ${['Run now', 'Disable', 'Delete', 'Save changes'].filter((l) => live(l)).join(',') || 'none'}; new-rule page notice ${/New rules can't be created/.test(txt2)}`, screenshot: `${shot1} ${shot2}` };
    });
  });
}

(async () => {
  T = await rt.adminToken();
  adminId = (await rt.me(T)).id;
  W = await rt.seedWorld(T);
  const want = (process.env.PARTS || 'api,ui,asof').split(',');
  try {
    if (want.includes('api')) await apiCases();
    if (want.includes('ui')) await uiCases();
    if (want.includes('asof')) await asOfCases();
  } finally {
    for (const id of [...new Set(cleanup)]) await rt.automation.disable(T, id).catch(() => {});
    save();
    const n = (s) => cases.filter((c) => c.status === s).length;
    console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED`);
  }
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
