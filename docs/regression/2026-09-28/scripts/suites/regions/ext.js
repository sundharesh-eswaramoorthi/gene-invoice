// Round-2 extensions of the regions suite: rule evaluation and rule lists answer "which regions" (B1 x A),
// book-scope placeholder masking (neighbour of RR-1), and cross-part top-paying ties (neighbour of RR-3).
const rt = require('../../lib.js');
const { same, sortN, excerpt } = require('./h.js');

const get = (t, p, query) => rt.api('GET', p, { token: t, query });
const HIDDEN = "Customer details are left out: you cannot see this record's customer";

async function extCases(F, RUN, { mkCustomer }) {
  const { C } = RUN;
  const { admin, w, R, U, T } = F;
  const marker = (Math.floor(Math.random() * 9000) + 1000) / 100 + 300; // e.g. 342.17, unique per run
  const amt = Number(marker.toFixed(2));

  // Scratch invoices with a unique amount in North, South and West (one each).
  const sc = {};
  for (const k of ['N', 'S', 'W']) sc[k] = await mkCustomer(F, k, { amounts: [amt], prefix: 'rx' });
  const inv = { N: sc.N.invoices[0].id, S: sc.S.invoices[0].id, W: sc.W.invoices[0].id };
  const cond = { op: 'AND', children: [{ filter: `total:eq:${amt}` }] };
  const manualRule = (o = {}) => rt.rule({ trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, condition: cond,
    actions: [rt.action.task({ title: 'rx {{Invoice.Number}}' })], ...o });
  const sampleIds = (p) => (p.json?.sample || []).filter((s) => !s.hidden).map((s) => s.id);

  await C({ id: 'REG-130', feature: 'Rule evaluation scope', prd: 'B1', title: 'Rule preview matches only records in the caller\'s regions, and regionIds narrows it',
    steps: `three invoices of total ${amt} in North, South, West; POST /api/automation/rules/preview {rule: MANUAL INVOICE total:eq:${amt}} as nora (N MANAGE, S READ), as nora with regionIds=S, as wendy (W only), as admin`,
    expected: 'nora: matchCount 2, sample {N,S}; nora regionIds=S: 1 {S}; wendy: 1 {W}; admin: 3 (PRD B1 "every list, filter, count answers which regions"; DESIGN §5.10.9 CALLER view under the request ViewScope)' }, async () => {
    const body = { rule: manualRule(), sampleSize: 20, evaluateAs: 'CALLER' };
    const n = await rt.automation.preview(T.nora, body); const ns = await rt.automation.preview(T.nora, body, { regionIds: [R.S] });
    const wd = await rt.automation.preview(T.wendy, body); const a = await rt.automation.preview(admin, body);
    const ok = n.status === 200 && n.json.matchCount === 2 && same(sampleIds(n), [inv.N, inv.S])
      && ns.json?.matchCount === 1 && same(sampleIds(ns), [inv.S]) && wd.json?.matchCount === 1 && same(sampleIds(wd), [inv.W]) && a.json?.matchCount === 3;
    return { ok, actual: { nora: [n.status, n.json?.matchCount, sampleIds(n)], noraS: [ns.status, ns.json?.matchCount, sampleIds(ns)], wendy: [wd.status, wd.json?.matchCount, sampleIds(wd)], admin: [a.status, a.json?.matchCount], inv },
      evidence: excerpt(n, 300) };
  });

  await C({ id: 'REG-131', feature: 'Rule evaluation scope', prd: 'B1', title: 'Rule preview for a record in an ungranted region answers as not reachable (no data leak)',
    steps: `nora POST /rules/preview {recordId: <West invoice>}`, expected: 'no West record data: 404, or record null / matches false with no rendered title naming the West invoice (B1 "nothing in a third")' }, async () => {
    const r = await rt.automation.preview(T.nora, { rule: manualRule(), recordId: inv.W, sampleSize: 5, evaluateAs: 'CALLER' });
    const wNum = sc.W.invoices[0].invoiceNumber;
    const leak = (r.text || '').includes(wNum);
    return { ok: !leak && (r.status === 404 || (r.status === 200 && (!r.json.record || r.json.record.id !== inv.W || r.json.record.matches === false)) || r.status === 400), actual: { status: r.status, leak, body: (r.text || '').slice(0, 300) } };
  });

  await C({ id: 'REG-132', feature: 'Rule evaluation scope', prd: 'B1', title: 'Rules list, summary and detail follow the rule\'s regions: a West-only rule is invisible to a North+South user; regionIds narrows the list',
    steps: 'wendy creates MANUAL INVOICE rule restricted to [W]; nora GET /rules/{id}, /rules; admin GET /rules?regionIds=N and ?regionIds=W',
    expected: 'nora detail 404, not in her list; admin N list excludes it, W list includes it; PageResponse.regionIds echoes [N]/[W] (DESIGN §5.10.10 RuleVisibility)' }, async () => {
    const c = await rt.automation.create(T.wendy, manualRule({ regionIds: [R.W], name: rt.uniq('rx-west') }));
    if (c.status !== 200 && c.status !== 201) return { ok: false, actual: `wendy create rule ${excerpt(c, 300)}` };
    const id = c.json.id;
    const d = await rt.automation.get(T.nora, id); const nl = await rt.automation.list(T.nora);
    const aN = await rt.automation.list(admin, {}, { regionIds: [R.N] }); const aW = await rt.automation.list(admin, {}, { regionIds: [R.W] });
    const has = (r) => (r.json?.content || []).some((x) => x.id === id);
    const ok = d.status === 404 && nl.status === 200 && !has(nl) && !has(aN) && has(aW) && same(aN.json.regionIds, [R.N]) && same(aW.json.regionIds, [R.W]);
    return { ok, actual: { noraDetail: d.status, inNoraList: has(nl), adminN: [has(aN), aN.json?.regionIds], adminW: [has(aW), aW.json?.regionIds] } };
  });

  await C({ id: 'REG-133', feature: 'Rule evaluation scope', prd: 'B1', title: 'A rule may be restricted only to regions where its owner can act',
    steps: 'nora POST /api/automation/rules restricted to [W] (no grant) and to [S] (READ only; CREATE_TASK needs TASK_MANAGE)',
    expected: '400 fieldErrors.regionIds for both (DESIGN §5.10.7 "each listed region must be one where the owner holds every RequiredPrivileges privilege")' }, async () => {
    const a = await rt.automation.create(T.nora, manualRule({ regionIds: [R.W] })); const b = await rt.automation.create(T.nora, manualRule({ regionIds: [R.S] }));
    const fe = (r) => r.json?.fieldErrors && Object.keys(r.json.fieldErrors).some((k) => k.startsWith('regionIds'));
    for (const r of [a, b]) if (r.status < 300 && r.json?.id) await rt.automation.remove(T.nora, r.json.id);
    return { ok: a.status === 400 && fe(a) && b.status === 400 && fe(b), actual: { west: excerpt(a, 250), southRead: excerpt(b, 250) } };
  });

  await C({ id: 'REG-134', feature: 'Rule evaluation scope', prd: 'B1', title: 'Run now acts only on the rule\'s regions and the requested regionIds',
    steps: `admin creates MANUAL INVOICE rule (total:eq:${amt}, CREATE_TASK) restricted to [N,S]; POST /rules/{id}/run?regionIds=S; wait; then rule unrestricted, run with no regionIds`,
    expected: 'first run: matched 1, a task only on the South invoice; second: tasks on N, S and W (PRD A5 "run from the UI"; DESIGN §5.10.9 regionIds ANDed into the run)' }, async () => {
    const title = rt.uniq('rx-run');
    const c = await rt.automation.create(admin, manualRule({ regionIds: [R.N, R.S], name: title, actions: [rt.action.task({ title: `${title} {{Invoice.Number}}` })] }));
    if (c.status >= 300) return { ok: false, actual: `create ${excerpt(c, 300)}` };
    const r1 = await rt.api('POST', `/api/automation/rules/${c.json.id}/run`, { token: admin, body: {}, query: { regionIds: R.S } });
    const run1 = r1.json?.runId ? await rt.waitForRun(admin, r1.json.runId) : null;
    const tasksOn = async () => {
      const out = {};
      for (const k of ['N', 'S', 'W']) out[k] = ((await rt.tasks.list(admin, { filter: `entityId:eq:${inv[k]}` })).json?.content || []).filter((t) => t.title.startsWith(title)).length;
      return out;
    };
    const t1 = await tasksOn();
    const g = (await rt.automation.get(admin, c.json.id)).json;
    const u = await rt.automation.update(admin, c.json.id, manualRule({ name: title, regionIds: [], version: g.version, actions: g.actions }));
    const r2 = await rt.automation.run(admin, c.json.id, {});
    const run2 = r2.json?.runId ? await rt.waitForRun(admin, r2.json.runId) : null;
    const t2 = await tasksOn();
    const ok = [200, 202].includes(r1.status) && run1?.matched === 1 && t1.N === 0 && t1.S === 1 && t1.W === 0 && u.status === 200 && run2?.matched === 3 && t2.N === 1 && t2.S === 2 && t2.W === 1;
    return { ok, actual: { run1: [r1.status, run1?.status, run1?.matched, run1?.done], tasksAfter1: t1, update: u.status, run2: [r2.status, run2?.status, run2?.matched, run2?.done], tasksAfter2: t2 } };
  });

  await C({ id: 'REG-135', feature: 'POC book per region', prd: 'B1', title: 'A South-BOOK user previewing a rule on a payment in her book gets Customer.* left out when the customer is outside her book; filled in when inside',
    steps: 'new South customer (Collection POC sam, phone 555-0100); admin records a 50 payment with collectionPocUserId=mia; mia (MANAGE/BOOK South) GET /api/payments/{id}, /api/customers/{id}; POST /rules/preview {PAYMENT MANUAL, recordId, CREATE_TASK "T {{Customer.Name}} {{Customer.Phone}}"}; then the same INVOICE preview on her own s2 invoice (customer in her book through the invoice)',
    expected: 'payment 200, customer 404; title "T <name>" without the phone, warning "Customer details are left out: ..." exactly once (TRIAGE SC-1 default, FIXLOG RR-1); invoice case: phone rendered, no such warning' }, async () => {
    const c = await rt.createCustomer(admin, { name: `rx-book-${rt.uniq('c')}`, regionId: R.S, paymentTerm: 'NET_30', phone: '555-0100' });
    await rt.addCustomerPoc(admin, c.id, 'COLLECTION', U.sam.id, true);
    const pay = await rt.createPayment(admin, { customerId: c.id, amount: 50, collectionPocUserId: U.mia.id });
    if (pay.status !== 200) return { status: 'BLOCKED', actual: `payment for mia: ${excerpt(pay, 300)}` };
    const pid = pay.data?.id ?? pay.json?.id;
    const pv = await get(T.mia, `/api/payments/${pid}`); const cs = await get(T.mia, `/api/customers/${c.id}`);
    const act = [rt.action.task({ title: 'T {{Customer.Name}} {{Customer.Phone}}' })];
    const p = await rt.automation.preview(T.mia, { rule: rt.rule({ trigger: { kind: 'MANUAL', entityType: 'PAYMENT' }, actions: act }), recordId: pid, sampleSize: 5, evaluateAs: 'CALLER' });
    const a = p.json?.record?.actions?.[0]; const title = a?.rendered?.title;
    const warns = (a?.warnings || []).filter((x) => x.includes('Customer details are left out'));
    const q = await rt.automation.preview(T.mia, { rule: rt.rule({ trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, actions: act }), recordId: F.miaInv.id, sampleSize: 5, evaluateAs: 'CALLER' });
    const qa = q.json?.record?.actions?.[0]; const qt = qa?.rendered?.title;
    const s2 = await get(T.mia, `/api/customers/${w.customers.s2.id}`);
    const ok = pv.status === 200 && cs.status === 404 && p.status === 200 && !!title && title.startsWith(`T ${c.name}`) && !title.includes('555-0100') && warns.length === 1
      && s2.status === 200 && q.status === 200 && !!qt && qt.includes(s2.json.phone) && !(qa.warnings || []).some((x) => x.includes('Customer details are left out'));
    return { ok, actual: { payment: pv.status, customer: cs.status, preview: p.status, title, warnings: a?.warnings, problems: a?.problems, ownInvoice: { s2: s2.status, title: qt, warnings: qa?.warnings } }, evidence: excerpt(p, 400) };
  });

  await C({ id: 'REG-136', feature: 'POC book per region', prd: 'B1', title: 'Top-paying across a full region and a book region: a tie is ordered by name',
    steps: `new North customer "Zeta..." pays X on its invoice; new South customer "alpha..." pays X on an invoice whose Sales POC is mia; mia GET /api/dashboard/top-paying-customers?months=3&limit=20`,
    expected: 'both rows with collected X, alpha before Zeta (case-insensitive name tie-break; FIXLOG RR-3); coverage MIXED' }, async () => {
    const X = Number((amt + 3000).toFixed(2)); // under South's PAYMENT_RECORD 5000 threshold
    const tag = rt.uniq('t');
    const zc = await rt.createCustomer(admin, { name: `Zeta tie ${tag}`, regionId: R.N, paymentTerm: 'NET_30' });
    await rt.addCustomerPoc(admin, zc.id, 'COLLECTION', U.nora.id, true);
    const zi = rt.need(await rt.createInvoice(admin, { customerId: zc.id, amount: 9000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id }), 'zeta inv', [200]);
    rt.need(await rt.createPayment(admin, { customerId: zc.id, amount: X, invoiceIds: [zi.id], collectionPocUserId: U.nora.id }), 'zeta pay', [200]);
    const ac = await rt.createCustomer(admin, { name: `alpha tie ${tag}`, regionId: R.S, paymentTerm: 'NET_30' });
    await rt.addCustomerPoc(admin, ac.id, 'COLLECTION', U.sam.id, true);
    const ai = rt.need(await rt.createInvoice(admin, { customerId: ac.id, amount: 9000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.mia.id }), 'alpha inv', [200]);
    const ap = await rt.createPayment(admin, { customerId: ac.id, amount: X, invoiceIds: [ai.id], collectionPocUserId: U.sam.id });
    if (ap.status !== 200) return { status: 'BLOCKED', actual: `alpha payment ${ap.status} held=${!!ap.held}` };
    const tp = await get(T.mia, '/api/dashboard/top-paying-customers', { months: 3, limit: 20 });
    const rows = tp.json?.customers || [];
    const iz = rows.findIndex((r) => r.customerId === zc.id); const ia = rows.findIndex((r) => r.customerId === ac.id);
    const ok = tp.status === 200 && ia >= 0 && iz >= 0 && ia < iz && Math.abs(Number(rows[ia].collected) - X) < 0.01 && Math.abs(Number(rows[iz].collected) - X) < 0.01 && tp.json.coverage === 'MIXED';
    return { ok, actual: { status: tp.status, coverage: tp.json?.coverage, alphaIdx: ia, zetaIdx: iz, rows: rows.map((r) => [r.customerId, r.customerName || r.name, r.collected]) } };
  });
  void sortN;
}

module.exports = { extCases };
