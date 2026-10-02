// A2: conditions are AND/OR trees over the entity's list-page filters.
const rt = require('../../lib.js');
const H = require('./helpers.js');

module.exports = async function conditionCases(ctx) {
  const { R, t, w } = ctx;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Conditions', prd: 'A2', ...meta }, fn, o);

  // Fixture: a North customer with invoices 1000, 6000, 2000 (partly paid 500), 3000.
  async function fixture() {
    const cust = await H.mkCustomer(t, { regionId: w.regions.NORTH.id, collector: w.users.nora.id, amounts: [1000, 6000, 2000, 3000] });
    await H.payInvoice(t, cust, cust.invoices[2], 500);
    const [i1000, i6000, i2000, i3000] = cust.invoices;
    return { cust, i1000, i6000, i2000, i3000 };
  }
  /** Runs a MANUAL task rule with `condition` now; returns the invoice ids it acted on and the preview count. */
  async function actedOn(condition, entityType = 'INVOICE') {
    const body = { trigger: H.manual(entityType), condition, actions: [rt.action.task({ title: 'Cond {{Rule.Name}}' })] };
    const rule = await H.mustRule(t, body);
    ctx.cleanup.push(rule.id);
    const pv = await rt.automation.preview(t, { rule: rt.rule(body), sampleSize: 20 });
    const { run, effects } = await H.runAndWait(t, rule.id);
    const ids = effects.filter((e) => e.status === 'DONE').map((e) => e.target.id).sort((a, b) => a - b);
    return { rule, run, ids, preview: pv.json, previewStatus: pv.status };
  }
  const sameSet = (a, b) => JSON.stringify([...a].sort((x, y) => x - y)) === JSON.stringify([...b].sort((x, y) => x - y));

  await K({ id: 'AUT-API-11', title: 'AND condition acts on exactly the records matching every filter (and matches the list page)',
    steps: 'fixture North customer C with invoices 1000/6000/2000(part paid)/3000; MANUAL INVOICE rule AND(customerId:eq:C,total:gt:1500) + CREATE_TASK; preview; run now; GET /api/invoices with the same filters',
    expected: 'PRD A2: acts on 6000, 2000, 3000 only; preview matchCount 3; same ids as the list page filter' },
  async () => {
    const f = await fixture();
    const want = [f.i6000.id, f.i2000.id, f.i3000.id];
    const r = await actedOn(H.and(`customerId:eq:${f.cust.c.id}`, 'total:gt:1500'));
    const list = await rt.list(t, '/api/invoices', { filter: [`customerId:eq:${f.cust.c.id}`, 'total:gt:1500'] });
    const listIds = (list.json?.content || []).map((x) => x.id);
    return { ok: r.run?.status === 'SUCCEEDED' && sameSet(r.ids, want) && r.preview?.matchCount === 3 && sameSet(listIds, want),
      actual: { run: r.run && [r.run.id, r.run.status, r.run.matched], actedOn: r.ids, want, preview: r.preview?.matchCount, list: listIds } };
  });

  await K({ id: 'AUT-API-12', title: 'OR group inside AND: acts on records matching any branch; non-matching records are left alone',
    steps: 'fixture; rule AND(customerId:eq:C, OR(total:gt:5000, status:eq:PARTIALLY_PAID)); run now',
    expected: 'PRD A2 (AND and OR): acts on the 6000 and the partly paid 2000 only; 1000 and 3000 untouched' },
  async () => {
    const f = await fixture();
    const r = await actedOn(H.and(`customerId:eq:${f.cust.c.id}`, H.or('total:gt:5000', 'status:eq:PARTIALLY_PAID')));
    const want = [f.i6000.id, f.i2000.id];
    return { ok: sameSet(r.ids, want) && r.preview?.matchCount === 2, actual: { actedOn: r.ids, want, preview: r.preview?.matchCount, run: r.run?.status } };
  });

  await K({ id: 'AUT-API-13', title: 'Top-level OR of two AND groups (depth 3)',
    steps: 'fixture; rule OR(AND(customerId:eq:C,total:lt:1500), AND(customerId:eq:C,total:eq:3000)); run now',
    expected: 'PRD A2: acts on the 1000 and the 3000 only' },
  async () => {
    const f = await fixture();
    const c = f.cust.c.id;
    const r = await actedOn(H.or(H.and(`customerId:eq:${c}`, 'total:lt:1500'), H.and(`customerId:eq:${c}`, 'total:eq:3000')));
    const want = [f.i1000.id, f.i3000.id];
    return { ok: sameSet(r.ids, want), actual: { actedOn: r.ids, want, preview: r.preview?.matchCount } };
  });

  await K({ id: 'AUT-API-14', title: 'Conditions work over Customer fields (outstanding) and Payment fields (amount)',
    steps: 'customers A (outstanding 7000) and B (outstanding 1000); CUSTOMER rule AND(OR(id:eq:A,id:eq:B), outstanding:gt:5000); payments 500 and 300 on A; PAYMENT rule AND(customerId:eq:A, amount:gt:400); run both',
    expected: 'PRD A2 "filters over that entity\'s fields": customer rule acts on A only; payment rule acts on the 500 payment only' },
  async () => {
    const A = await H.mkCustomer(t, { regionId: w.regions.NORTH.id, collector: w.users.nora.id, amounts: [4000, 3800] });
    const B = await H.mkCustomer(t, { regionId: w.regions.NORTH.id, collector: w.users.nora.id, amounts: [1000] });
    const p500 = await H.payInvoice(t, A, A.invoices[0], 500);
    const p300 = await H.payInvoice(t, A, A.invoices[1], 300);
    const rc = await actedOn(H.and(H.or(`id:eq:${A.c.id}`, `id:eq:${B.c.id}`), 'outstanding:gt:5000'), 'CUSTOMER');
    const rp = await actedOn(H.and(`customerId:eq:${A.c.id}`, 'amount:gt:400'), 'PAYMENT');
    return { ok: sameSet(rc.ids, [A.c.id]) && sameSet(rp.ids, [p500.id]),
      actual: { customerRule: rc.ids, wantCustomer: [A.c.id], paymentRule: rp.ids, wantPayment: [p500.id], notPayment: p300.id } };
  });

  await K({ id: 'AUT-API-15', title: 'Invalid condition trees are refused at save with the path of the bad node',
    steps: 'POST rules with: unknown field; bad operator; bad enum value; empty group; depth 5; 31 leaves',
    expected: 'DESIGN §5.10.2: 400 with fieldErrors like condition.children[1].filter; limits depth<=4, leaves<=30, empty group rejected' },
  async () => {
    const nest = (d) => (d === 0 ? { filter: 'total:gt:1' } : { op: 'AND', children: [nest(d - 1)] });
    const cases = {
      unknownField: H.and('total:gt:1', 'nosuchfield:eq:1'),
      badOperator: H.and('total:like:1'),
      badEnum: H.and('status:eq:NOT_A_STATUS'),
      emptyGroup: H.and('total:gt:1', { op: 'OR', children: [] }),
      depth5: nest(5),
      leaves31: { op: 'AND', children: Array.from({ length: 31 }, (_, i) => ({ filter: `total:gt:${i}` })) },
    };
    const out = {};
    for (const [k, condition] of Object.entries(cases)) {
      const r = await H.createRule(t, { enabled: false, trigger: H.manual(), condition, actions: [rt.action.task()] });
      if (r.ok) ctx.cleanup.push(r.json.id);
      out[k] = [r.status, r.json?.fieldErrors ? Object.keys(r.json.fieldErrors).join(',') : H.short(r.json?.message, 80)];
    }
    const ok = Object.values(out).every(([s, fe]) => s === 400 && /condition/.test(fe || ''))
      && /condition\.children\[1\]/.test(out.unknownField[1]);
    return { ok, actual: out };
  });

  await K({ id: 'AUT-API-16', title: 'An UPDATED rule on a clock-driven column saves with the "not an update" warning',
    steps: 'POST ENTITY_UPDATED INVOICE rule with condition overdue:eq:true',
    expected: 'DESIGN §5.8.3/§6.14: 200 with warnings containing "Becoming overdue is not an update"' },
  async () => {
    const r = await H.createRule(t, { enabled: false, trigger: { kind: 'ENTITY_UPDATED', entityType: 'INVOICE' }, condition: H.and('overdue:eq:true'), actions: [rt.action.task()] });
    if (r.ok) ctx.cleanup.push(r.json.id);
    return { ok: r.status === 200 && (r.json.warnings || []).some((x) => /not an update/i.test(x)), actual: [r.status, r.json?.warnings] };
  });

  await K({ id: 'AUT-API-17', title: 'A rule with no condition matches every record the owner may see',
    steps: 'nora (MANAGE North only for tasks) previews a MANUAL INVOICE rule with condition null restricted to NORTH; compare with GET /api/invoices?regionIds=NORTH as nora',
    expected: 'DESIGN §3.6 "Null means every record": matchCount = number of North invoices nora sees' },
  async () => {
    const body = rt.rule({ trigger: H.manual(), regionIds: [w.regions.NORTH.id], condition: null, actions: [rt.action.task()] });
    const pv = await rt.automation.preview(w.tokens.nora, { rule: body, sampleSize: 5 });
    const l = await rt.api('GET', '/api/invoices', { token: w.tokens.nora, query: { size: 10, regionIds: w.regions.NORTH.id } });
    return { ok: pv.status === 200 && pv.json.matchCount === l.json?.totalElements && pv.json.matchCount > 0, actual: { preview: [pv.status, pv.json?.matchCount], list: l.json?.totalElements } };
  });
};
