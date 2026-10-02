// Rules act only within their owner's rights and regions (A3/A5 x B1), who sees rules and runs, and
// automation-made changes above a threshold are held for approval (A3 x B2).
const rt = require('../../lib.js');
const H = require('./helpers.js');

module.exports = async function rightsCases(ctx) {
  const { R, t, w } = ctx;
  const N = w.regions.NORTH.id; const S = w.regions.SOUTH.id; const Wr = w.regions.WEST.id;
  const U = w.users; const T = w.tokens;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Rights and regions', prd: 'A5', ...meta }, fn, o);
  const invIds = (...keys) => keys.flatMap((k) => w.invoices[k].map((i) => i.id));
  const custIds = (...keys) => keys.map((k) => w.customers[k].id);

  await K({ id: 'AUT-API-100', title: 'A rule acts only where its owner may act: nora (MANAGE North, READ South, nothing in West)',
    steps: 'nora creates an unrestricted MANUAL INVOICE rule, condition customerId:in:<n2,s2,w2>, CREATE_TASK; run now',
    expected: 'PRD A5/B1 + DESIGN §5.10.7: tasks only on n2\'s two invoices (North); nothing on s2 (READ) or w2 (no grant)' },
  async () => {
    const rule = await H.mustRule(T.nora, { trigger: H.manual(), condition: H.and(`customerId:in:${custIds('n2', 's2', 'w2').join(',')}`), actions: [rt.action.task({ title: 'Nora {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    const { run, effects } = await H.runAndWait(T.nora, rule.id);
    const done = effects.filter((e) => e.status === 'DONE').map((e) => e.target.id).sort((a, b) => a - b);
    const tasks = await H.tasksOfRule(t, rule.id);
    const want = invIds('n2').sort((a, b) => a - b);
    return { ok: JSON.stringify(done) === JSON.stringify(want) && tasks.length === 2 && tasks.every((x) => x.regionId === N),
      actual: { run: run && [run.status, run.matched, run.done, run.failed], effects: effects.map((e) => [e.target.id, e.status, e.message]), tasksRegions: tasks.map((x) => x.regionId), want } };
  });

  await K({ id: 'AUT-API-101', title: 'A rule cannot be restricted to a region where its owner cannot do what it does',
    steps: 'nora POST rule (CREATE_TASK) with regionIds [SOUTH] (READ), [WEST] (none), [NORTH] (MANAGE)',
    expected: 'DESIGN §6.14: SOUTH and WEST -> 400 fieldErrors.regionIds; NORTH -> 200' },
  async () => {
    const mk = (regionIds) => H.createRule(T.nora, { enabled: false, trigger: H.manual(), regionIds, actions: [rt.action.task()] });
    const s = await mk([S]); const x = await mk([Wr]); const n = await mk([N]);
    if (n.ok) ctx.cleanup.push(n.json.id);
    return { ok: s.status === 400 && !!s.json?.fieldErrors?.regionIds && x.status === 400 && n.status === 200,
      actual: { south: [s.status, s.json?.fieldErrors], west: [x.status, x.json?.fieldErrors], north: n.status } };
  });

  await K({ id: 'AUT-API-102', title: 'A rule restricted to NORTH never acts in SOUTH, although its owner (admin) could',
    steps: 'admin rule regionIds [NORTH], condition customerId:in:<n2,s2>; run now',
    expected: 'DESIGN §5.10.7 "a restricted rule never matches outside its regions": tasks on n2\'s invoices only' },
  async () => {
    const rule = await H.mustRule(t, { trigger: H.manual(), regionIds: [N], condition: H.and(`customerId:in:${custIds('n2', 's2').join(',')}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id);
    const ids = effects.map((e) => e.target.id).sort((a, b) => a - b);
    return { ok: JSON.stringify(ids) === JSON.stringify(invIds('n2').sort((a, b) => a - b)), actual: { actedOn: ids, north: invIds('n2'), south: invIds('s2') } };
  });

  await K({ id: 'AUT-API-103', title: 'A rule of a book-scoped owner acts only on the owner\'s book',
    steps: 'bob (MANAGE/BOOK North; POC of n1) creates a MANUAL rule condition customerId:in:<n1,n2>, CREATE_TASK; run now',
    expected: 'DESIGN §5.10.7/§8.5 RunAsOwnerTest: tasks on n1\'s invoices only (n2 is not in bob\'s book)' },
  async () => {
    const rule = await H.mustRule(T.bob, { trigger: H.manual(), condition: H.and(`customerId:in:${custIds('n1', 'n2').join(',')}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { run, effects } = await H.runAndWait(T.bob, rule.id);
    const done = effects.filter((e) => e.status === 'DONE').map((e) => e.target.id).sort((a, b) => a - b);
    return { ok: JSON.stringify(done) === JSON.stringify(invIds('n1').sort((a, b) => a - b)), actual: { run: run && [run.status, run.matched, run.done], effects: effects.map((e) => [e.target.id, e.status]), want: invIds('n1') } };
  });

  await K({ id: 'AUT-API-104', title: 'Who sees a rule follows regions: a West-only user does not see a North/South owner\'s rule', prd: 'B1',
    steps: 'nora\'s unrestricted rule; GET /rules and /rules/{id} and /rules/{id}/runs as wendy (West), sam (North+South); admin GET /rules?regionIds=WEST',
    expected: 'DESIGN §5.10.10: wendy not listed + 404 + 404; sam sees it; admin narrowed to WEST does not list it' },
  async () => {
    const rule = await H.mustRule(T.nora, { enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const inList = async (tok, q = {}) => ((await rt.list(tok, '/api/automation/rules', { filter: [`id:eq:${rule.id}`], ...q })).json?.content || []).length;
    const a = { wendyList: await inList(T.wendy), wendyGet: (await rt.automation.get(T.wendy, rule.id)).status, wendyRuns: (await rt.automation.runs(T.wendy, rule.id)).status,
      samList: await inList(T.sam), samGet: (await rt.automation.get(T.sam, rule.id)).status, adminWest: await inList(t, { regionIds: Wr }), adminNorth: await inList(t, { regionIds: N }) };
    return { ok: a.wendyList === 0 && a.wendyGet === 404 && a.wendyRuns === 404 && a.samList === 1 && a.samGet === 200 && a.adminWest === 0 && a.adminNorth === 1, actual: a };
  });

  await K({ id: 'AUT-API-105', title: 'A viewer who sees a rule but not all its records gets redacted runs and effects', prd: 'B1',
    steps: 'sam\'s unrestricted rule acts on n2 (North, not in bob\'s book) and n1 (bob\'s book); bob GET /runs/{id} and /runs/{id}/effects',
    expected: 'DESIGN §5.10.10: bob sees partial:true counters (only n1\'s) and n2\'s effects as {target:{hidden:true}} with null result/message' },
  async () => {
    const rule = await H.mustRule(T.sam, { trigger: H.manual(), condition: H.and(`customerId:in:${custIds('n1', 'n2').join(',')}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const { req, run } = await H.runAndWait(T.sam, rule.id);
    const asBob = (await rt.automation.runById(T.bob, req.json?.runId)).json;
    const effB = (await rt.automation.effects(T.bob, req.json?.runId)).json?.content || [];
    const hidden = effB.filter((e) => e.target?.hidden); const shown = effB.filter((e) => !e.target?.hidden);
    return { ok: run?.done === 4 && asBob?.partial === true && asBob?.done === 2 && hidden.length === 2 && hidden.every((e) => e.result == null && e.target.id == null) && shown.every((e) => invIds('n1').includes(e.target.id)),
      actual: { owner: run && [run.done, run.partial], bob: asBob && [asBob.done, asBob.partial], effects: effB.map((e) => [e.target?.id ?? 'hidden', e.status, e.result?.type ?? null]) } };
  });

  await K({ id: 'AUT-API-106', title: 'When the owner loses the right to act, the rule is suspended and stops acting', prd: 'A5',
    steps: 'nora\'s North MANUAL rule (CREATE_TASK); admin sets nora\'s grants to READ North + READ South; GET rule; POST /run; restore nora\'s grants; POST /resume',
    expected: 'DESIGN §5.10.7: rule SUSPENDED (OWNER_LACKS_RIGHTS) after the grant change; run now 400 "suspended"; after restoring, resume -> ACTIVE' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [100] });
    const rule = await H.mustRule(T.nora, { trigger: H.manual(), regionIds: [N], condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const orig = U.nora.grants;
    let g1, run, g2, resume;
    try {
      g1 = await rt.setGrants(t, U.nora.id, [{ regionId: N, accessLevel: 'READ' }, { regionId: S, accessLevel: 'READ' }]);
      await rt.sleep(500);
      const after = (await rt.automation.get(t, rule.id)).json;
      g2 = after;
      run = await rt.automation.run(await rt.login('nora'), rule.id, {});
    } finally {
      await rt.setGrants(t, U.nora.id, orig);
    }
    T.nora = await rt.login('nora');
    resume = await rt.api('POST', `/api/automation/rules/${rule.id}/resume`, { token: T.nora, body: {} });
    const tasks = await H.tasksOfRule(t, rule.id);
    return { ok: g1.status === 200 && g2?.status === 'SUSPENDED' && g2?.suspendedReason === 'OWNER_LACKS_RIGHTS' && [400, 403].includes(run.status) && tasks.length === 0 && resume.status === 200 && resume.json?.status === 'ACTIVE',
      actual: { grants: g1.status, rule: g2 && [g2.status, g2.suspendedReason, g2.suspendedDetail], runNow: [run.status, run.json?.message], resume: [resume.status, resume.json?.status], tasks: tasks.length } };
  });

  // ---- maker-checker ----
  async function makeHeld() {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [2500] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.promise({ amount: { source: 'FIXED', value: 6000 }, notes: 'Held {{Invoice.Number}}', onlyIfNoOpenPromise: false })] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id);
    const e = effects[0];
    return { cust, rule, e, pcId: e?.result?.type === 'PENDING_CHANGE' ? e.result.id : null };
  }
  const KB = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Automation and approvals', prd: 'B2', ...meta }, fn, o);

  await KB({ id: 'AUT-API-110', title: 'A promise an automation makes above the region\'s threshold becomes a pending change, not a promise',
    steps: 'South (PROMISE_CREATE threshold 5000); admin MANUAL rule on a South invoice: CREATE_PROMISE FIXED 6000, notes "Held {{Invoice.Number}}"; run; GET /api/approvals/{id}; GET promises of the customer',
    expected: 'PRD B2 + DESIGN D11: effect DONE with result PENDING_CHANGE (link /approvals/{id}); approval PENDING, origin AUTOMATION, ruleId, requestedBy admin (owner), amount 6000; no promise yet' },
  async () => {
    const { cust, rule, e } = await makeHeld();
    const a = e?.result?.type === 'PENDING_CHANGE' ? (await rt.approvals.get(t, e.result.id)).json : null;
    const ps = await H.promisesOf(t, cust.c.id);
    return { ok: e?.status === 'DONE' && e?.result?.type === 'PENDING_CHANGE' && e.result.link === `/approvals/${e.result.id}` && a?.status === 'PENDING' && a?.origin === 'AUTOMATION'
      && a?.ruleId === rule.id && a?.requestedBy?.id === ctx.adminId && Number(a?.amount) === 6000 && ps.length === 0,
      actual: { effect: e && [e.status, e.result], approval: a && { status: a.status, origin: a.origin, ruleId: a.ruleId, requestedBy: a.requestedBy, amount: a.amount, op: a.operation }, promises: ps.length } };
  });

  await KB({ id: 'AUT-API-111', title: 'The rule\'s owner cannot approve what their rule requested; another approver in the region can, and exactly one promise results',
    steps: 'a pending change made as in AUT-API-110: admin (owner) POST /approve; nora (READ South) GET /api/approvals/{id}; wendy GET; sam (APPROVE South) POST /approve; run the rule again (ONCE_PER_RECORD)',
    expected: 'PRD B2 "approval from someone else": admin 400; nora can see it (200); wendy 404; sam 200 APPROVED; one promise of 6000; the second run makes no second pending change' },
  async () => {
    const h = await makeHeld();
    if (!h.pcId) return { status: 'BLOCKED', actual: `no pending change was made: ${H.short(h.e)}` };
    const own = await rt.approvals.approve(t, h.pcId);
    const nv = await rt.approvals.get(T.nora, h.pcId);
    const wv = await rt.approvals.get(T.wendy, h.pcId);
    const ok2 = await rt.approvals.approve(T.sam, h.pcId);
    const ps = await H.promisesOf(t, h.cust.c.id);
    const again = await H.runAndWait(t, h.rule.id);
    const pend = await rt.approvals.list(t, { filter: `customerId:eq:${h.cust.c.id}` });
    return { ok: own.status === 400 && nv.status === 200 && wv.status === 404 && ok2.status === 200 && ok2.json?.status === 'APPROVED' && ps.length === 1 && Number(ps[0].amount) === 6000 && again.run?.done === 0,
      actual: { ownerApprove: [own.status, own.json?.message], noraSees: nv.status, wendySees: wv.status, samApprove: [ok2.status, ok2.json?.status], promises: ps.map((p) => [p.id, p.amount, p.status]), rerun: again.run && [again.run.done, again.run.skipped], approvalsOnCustomer: pend.json?.totalElements } };
  });

  await KB({ id: 'AUT-API-112', title: 'A promise an automation makes under the threshold applies at once',
    steps: 'same South setup, CREATE_PROMISE FIXED 4000; run',
    expected: 'PRD B2: below threshold -> effect result PROMISE, promise exists, no pending change' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: S, collector: U.sam.id, amounts: [4500] });
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.promise({ amount: { source: 'FIXED', value: 4000 } })] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id);
    const ps = await H.promisesOf(t, cust.c.id);
    return { ok: effects[0]?.result?.type === 'PROMISE' && ps.length === 1, actual: { effect: effects[0] && [effects[0].status, effects[0].result], promises: ps.length } };
  });

  await KB({ id: 'AUT-API-113', title: 'A dispute an automation opens proposing a cancel needs someone other than the rule\'s owner to approve it',
    steps: 'North invoice 1000; admin rule CREATE_DISPUTE proposedChange "cancel"; run; admin POST /api/disputes/{id}/approve; sam POST approve; GET invoice',
    expected: 'DESIGN §5.10.11: admin 400 "A change your own rule proposed must be approved by someone else"; sam 200; the invoice is CANCELLED' },
  async () => {
    const cust = await H.mkCustomer(t, { regionId: N, collector: U.nora.id, amounts: [1000] });
    const inv = cust.invoices[0];
    const rule = await H.mustRule(t, { trigger: H.manual(), condition: H.and(`id:eq:${inv.id}`), actions: [rt.action.dispute({ proposedChange: 'cancel', reason: 'Cancel {{Invoice.Number}}?' })] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t, rule.id);
    const did = effects[0]?.result?.id;
    const own = await rt.api('POST', `/api/disputes/${did}/approve`, { token: t, body: { adminNotes: 'ok' } });
    const other = await rt.api('POST', `/api/disputes/${did}/approve`, { token: T.sam, body: { adminNotes: 'ok' } });
    const after = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: t })).json;
    return { ok: own.status === 400 && [200, 202].includes(other.status) && after?.status === 'CANCELLED',
      actual: { effect: effects[0] && [effects[0].status, effects[0].result], ownerApprove: [own.status, own.json?.message], samApprove: [other.status, other.json?.status], invoiceStatus: after?.status } };
  });
};
