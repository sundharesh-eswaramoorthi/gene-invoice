// API part 2: re-entrancy (void via dispute, invoice cancel), many-to-many + summary, filters,
// bulk, export, customer read-only access, and the promise leads from candidates.md.
const fx = require('./fx');
const { rt, addDays } = fx;
const { makeHarness, snip } = require('./harness');
const { tc, save } = makeHarness('api2');

const q = (filters, extra = '') => '/api/promises?size=50' + filters.map((f) => '&filter=' + encodeURIComponent(f)).join('') + extra;
const ids = (r) => (r.json?.content || []).map((x) => x.id).sort((a, b) => a - b);
const same = (a, b) => JSON.stringify([...a].sort((x, y) => x - y)) === JSON.stringify([...b].sort((x, y) => x - y));

(async () => {
  const adm = await fx.admin();
  const collA = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const collB = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const sales = await rt.createStaff(adm, 'SALES_POC', 'promises');
  const viewer = await rt.createStaff(adm, 'VIEWER', 'promises');
  const collAT = await rt.login(collA.username, collA.password);
  const collBT = await rt.login(collB.username, collB.password);
  const salesT = await rt.login(sales.username, sales.password);
  const viewerT = await rt.login(viewer.username, viewer.password);
  const cashT = await rt.cashierToken();

  // ---- re-entrancy: void a linked payment via an approved dispute (AC-B6) ----
  await tc('PRM-R01', 'Voiding a linked payment (customer dispute approved) re-evaluates KEPT -> OPEN and unlinks it', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const ct = await rt.login(c.username, c.password);
    const inv = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    const pay = await fx.pay(c.id, 100, { invoiceIds: [inv.id] });
    const kept = await fx.getPromise(p.id);
    const { dispute, approve } = await fx.disputeAndApprove(ct, 'PAYMENT', pay.json.id, { action: 'void' });
    const payAfter = await rt.api('GET', `/api/payments/${pay.json.id}`, { token: adm });
    const g = await fx.getPromise(p.id);
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${p.id}`, { token: adm });
    const changes = (au.json || []).filter((x) => x.action === 'PROMISE_STATUS_CHANGED').map((x) => `${x.before}->${x.after}`);
    return { ok: kept.json.status === 'KEPT' && approve.status === 200 && payAfter.json.status === 'VOIDED' && g.json.status === 'OPEN' && Number(g.json.fulfilledAmount) === 0 && g.json.payments.length === 0,
      actual: `before void ${kept.json.status}; dispute ${dispute.status} approve ${approve.status}; payment ${payAfter.json?.status}; promise ${g.json.status} fulfilled=${g.json.fulfilledAmount} links=${g.json.payments.length}; status-change audit=${JSON.stringify(changes)}` };
  });
  await tc('PRM-R02', 'Dispute-driven invoice cancellation re-evaluates PARTIALLY_KEPT -> KEPT (remaining promised invoice settled)', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const ct = await rt.login(c.username, c.password);
    const a = await fx.invoice(c.id, 100);
    const b = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 200, promisedDate: addDays(6), invoiceIds: [a.id, b.id] })).json;
    await fx.pay(c.id, 100, { invoiceIds: [a.id] });
    const mid = await fx.getPromise(p.id);
    const { approve } = await fx.disputeAndApprove(ct, 'INVOICE', b.id, { action: 'cancel' });
    const invB = await rt.api('GET', `/api/invoices/${b.id}`, { token: adm });
    const g = await fx.getPromise(p.id);
    global.__r02 = { p, a, b, c };
    return { ok: mid.json.status === 'PARTIALLY_KEPT' && approve.status === 200 && invB.json.status === 'CANCELLED' && g.json.status === 'KEPT',
      actual: `after paying invoice A: ${mid.json.status}; dispute approve ${approve.status}; invoice B ${invB.json?.status}; promise ${g.json.status} fulfilled=${g.json.fulfilledAmount}` };
  });
  await tc('PRM-R03', 'Cancelling the only promised invoice (direct cancel) re-evaluates OPEN -> KEPT', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const inv = await fx.invoice(c.id, 70);
    const p = (await fx.promise({ customerId: c.id, amount: 70, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    const x = await rt.api('POST', `/api/invoices/${inv.id}/cancel`, { token: adm });
    const g = await fx.getPromise(p.id);
    return { ok: p.status === 'OPEN' && x.status === 200 && g.json.status === 'KEPT', actual: `before ${p.status}; cancel ${x.status}; after ${g.json.status}` };
  });
  await tc('PRM-R04', 'Editing a promise after one of its invoices was cancelled (UI re-sends invoiceIds) (lead #3)', async () => {
    const { p, a, b } = global.__r02;
    const uiLike = await rt.api('PUT', `/api/promises/${p.id}`, { token: adm, body: { amount: 200, promisedDate: addDays(9), notes: 'date moved', invoiceIds: [a.id, b.id], collectionPocUserId: collA.id } });
    const noInv = await rt.api('PUT', `/api/promises/${p.id}`, { token: adm, body: { amount: 200, promisedDate: addDays(9), notes: 'date moved' } });
    return { ok: uiLike.status === 200, actual: `PUT with the same invoiceIds (as the edit dialog sends) -> ${snip(uiLike)} ; PUT omitting invoiceIds -> ${noInv.status}` };
  });
  await tc('PRM-R05', 'Changing a linked payment amount via dispute (update_amount 100 -> 60) re-evaluates KEPT -> PARTIALLY_KEPT', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const ct = await rt.login(c.username, c.password);
    const inv = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    const pay = await fx.pay(c.id, 100, { invoiceIds: [inv.id] });
    const { approve } = await fx.disputeAndApprove(ct, 'PAYMENT', pay.json.id, { action: 'update_amount', amount: 60 });
    const g = await fx.getPromise(p.id);
    return { ok: approve.status === 200 && g.json.status === 'PARTIALLY_KEPT' && Number(g.json.fulfilledAmount) === 60, actual: `approve ${approve.status}; promise ${g.json.status} fulfilled=${g.json.fulfilledAmount}` };
  });

  // ---- many-to-many and the summary (AC-B12) ----
  const sum = async (cid) => (await rt.api('GET', `/api/promises/summary?customerId=${cid}`, { token: adm })).json;
  await tc('PRM-M01', 'One payment settling two invoice-scoped promises (distinct invoices): both KEPT, summary not double-counted', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const a = await fx.invoice(c.id, 100);
    const b = await fx.invoice(c.id, 100);
    const pa = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [a.id] })).json;
    const pb = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [b.id] })).json;
    const pay = await fx.pay(c.id, 200, { invoiceIds: [a.id, b.id] });
    const ga = await fx.getPromise(pa.id); const gb = await fx.getPromise(pb.id);
    const s = await sum(c.id);
    return { ok: ga.json.status === 'KEPT' && gb.json.status === 'KEPT' && ga.json.payments[0]?.id === pay.json.id && gb.json.payments[0]?.id === pay.json.id && Number(s.fulfilledAmount) === 200 && s.keptCount === 2 && Number(s.promisedAmount) === 200,
      actual: `A ${ga.json.status} f=${ga.json.fulfilledAmount}; B ${gb.json.status} f=${gb.json.fulfilledAmount}; same payment #${pay.json.id} linked to both; summary ${JSON.stringify(s)}` };
  });
  await tc('PRM-M02', 'One promise fulfilled by two payments (120 + 80): KEPT, 2 links, summary fulfilled = 200 (not 400)', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const inv = await fx.invoice(c.id, 200);
    const p = (await fx.promise({ customerId: c.id, amount: 200, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    await fx.pay(c.id, 120, { invoiceIds: [inv.id] });
    const mid = await fx.getPromise(p.id);
    await fx.pay(c.id, 80, { invoiceIds: [inv.id] });
    const g = await fx.getPromise(p.id);
    const s = await sum(c.id);
    return { ok: mid.json.status === 'PARTIALLY_KEPT' && g.json.status === 'KEPT' && g.json.payments.length === 2 && Number(g.json.fulfilledAmount) === 200 && Number(s.fulfilledAmount) === 200 && s.keptCount === 1,
      actual: `after 120: ${mid.json.status}; after +80: ${g.json.status} links=${g.json.payments.length} fulfilled=${g.json.fulfilledAmount}; summary fulfilled=${s.fulfilledAmount} kept=${s.keptCount}/${s.keptAmount}` };
  });
  await tc('PRM-M03', 'One payment of 100 against two promises covering the SAME invoice: summary totals vs money collected', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const inv = await fx.invoice(c.id, 100);
    const p1 = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    const p2 = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    await fx.pay(c.id, 100, { invoiceIds: [inv.id] });
    const g1 = await fx.getPromise(p1.id); const g2 = await fx.getPromise(p2.id);
    const s = await sum(c.id);
    const pays = await rt.api('GET', `/api/payments/summary?filter=${encodeURIComponent('customerId:eq:' + c.id)}`, { token: adm });
    return { ok: Number(s.fulfilledAmount) <= 100, actual: `P1 ${g1.json.status} f=${g1.json.fulfilledAmount}; P2 ${g2.json.status} f=${g2.json.fulfilledAmount}; promise summary fulfilledAmount=${s.fulfilledAmount} keptCount=${s.keptCount} keptAmount=${s.keptAmount}; payments summary totalCollected=${pays.json?.totalCollected}` };
  });
  await tc('PRM-M04', 'One payment of 100 against two GENERAL promises of 100: summary totals vs money collected (lead #6)', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    await fx.invoice(c.id, 500);
    const p1 = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) })).json;
    const p2 = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) })).json;
    await fx.pay(c.id, 100);
    const g1 = await fx.getPromise(p1.id); const g2 = await fx.getPromise(p2.id);
    const s = await sum(c.id);
    return { ok: Number(s.fulfilledAmount) <= 100, actual: `P1 ${g1.json.status} f=${g1.json.fulfilledAmount}; P2 ${g2.json.status} f=${g2.json.fulfilledAmount}; summary fulfilledAmount=${s.fulfilledAmount} keptCount=${s.keptCount} keptAmount=${s.keptAmount} promisedAmount=${s.promisedAmount} (only 100 was paid)` };
  });

  // ---- list filters, sort, validation (US-B5) ----
  const cf = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(cf.id, collA.id, true);
  const fi1 = await fx.invoice(cf.id, 100);
  const fi2 = await fx.invoice(cf.id, 100);
  const F = {};
  F.kept = (await fx.promise({ customerId: cf.id, amount: 100, promisedDate: addDays(3), invoiceIds: [fi1.id] })).json;
  await fx.pay(cf.id, 100, { invoiceIds: [fi1.id] });
  F.open = (await fx.promise({ customerId: cf.id, amount: 50, promisedDate: addDays(20), invoiceIds: [fi2.id], collectionPocUserId: collB.id })).json;
  F.general = (await fx.promise({ customerId: cf.id, amount: 30, promisedDate: addDays(40), collectionPocUserId: collB.id })).json;
  F.broken = (await fx.promise({ customerId: cf.id, amount: 20, promisedDate: addDays(-3), invoiceIds: [fi2.id] })).json;
  F.twoInv = (await fx.promise({ customerId: cf.id, amount: 60, promisedDate: addDays(10), invoiceIds: [fi1.id, fi2.id] })).json;
  const all = [F.kept.id, F.open.id, F.general.id, F.broken.id, F.twoInv.id];
  const cfF = `customerId:eq:${cf.id}`;
  await tc('PRM-F01', 'Filter status (eq / in) combined with customerId', async () => {
    const kept = await rt.api('GET', q([cfF, 'status:eq:KEPT']), { token: adm });
    const brk = await rt.api('GET', q([cfF, 'status:in:BROKEN,OPEN']), { token: adm });
    const stat = await Promise.all(all.map(async (id) => [id, (await fx.getPromise(id)).json.status]));
    const expKept = stat.filter((s) => s[1] === 'KEPT').map((s) => s[0]);
    const expBO = stat.filter((s) => ['BROKEN', 'OPEN'].includes(s[1])).map((s) => s[0]);
    return { ok: kept.status === 200 && same(ids(kept), expKept) && same(ids(brk), expBO) && ids(kept).includes(F.kept.id) && ids(brk).includes(F.broken.id),
      actual: `statuses ${JSON.stringify(stat)}; eq:KEPT -> ${JSON.stringify(ids(kept))}; in:BROKEN,OPEN -> ${JSON.stringify(ids(brk))}` };
  });
  await tc('PRM-F02', 'Filter promisedDate between / gte', async () => {
    const r = await rt.api('GET', q([cfF, `promisedDate:between:${addDays(1)},${addDays(15)}`]), { token: adm });
    const r2 = await rt.api('GET', q([cfF, `promisedDate:gte:${addDays(30)}`]), { token: adm });
    return { ok: r.status === 200 && same(ids(r), [F.kept.id, F.twoInv.id]) && same(ids(r2), [F.general.id]), actual: `between -> ${r.status} ${JSON.stringify(ids(r))} (expected ${F.kept.id},${F.twoInv.id}); gte +30d -> ${JSON.stringify(ids(r2))}` };
  });
  await tc('PRM-F03', 'customerId filter chip and ?customerId= param agree; total count exact', async () => {
    const r = await rt.api('GET', q([cfF]), { token: adm });
    const r2 = await rt.api('GET', `/api/promises?size=50&customerId=${cf.id}`, { token: adm });
    return { ok: same(ids(r), all) && same(ids(r2), all) && r.json.totalElements === 5, actual: `chip ${JSON.stringify(ids(r))} total=${r.json.totalElements}; param ${JSON.stringify(ids(r2))}` };
  });
  await tc('PRM-F04', 'collectionPocUserId filter returns only that POC\'s promises', async () => {
    const r = await rt.api('GET', q([`collectionPocUserId:eq:${collB.id}`]), { token: adm });
    return { ok: r.status === 200 && same(ids(r), [F.open.id, F.general.id]), actual: `${r.status} ${JSON.stringify(ids(r))} (expected ${F.open.id},${F.general.id}) lockedFilters=${JSON.stringify(r.json?.lockedFilters)}` };
  });
  await tc('PRM-F05', 'invoiceId filter (eq, and ?invoiceId= param) returns promises covering the invoice, one row each', async () => {
    const r = await rt.api('GET', q([`invoiceId:eq:${fi2.id}`]), { token: adm });
    const r2 = await rt.api('GET', `/api/promises?size=50&customerId=${cf.id}&invoiceId=${fi1.id}`, { token: adm });
    return { ok: r.status === 200 && same(ids(r), [F.open.id, F.broken.id, F.twoInv.id]) && same(ids(r2), [F.kept.id, F.twoInv.id]) && r2.json.totalElements === 2,
      actual: `invoiceId:eq:${fi2.id} -> ${r.status} ${JSON.stringify(ids(r))}; ?invoiceId=${fi1.id} -> ${r2.status} ${JSON.stringify(ids(r2))} total=${r2.json?.totalElements}` };
  });
  await tc('PRM-F06', 'invoiceId isEmpty = general promises, isNotEmpty = invoice-scoped (new)', async () => {
    const e = await rt.api('GET', q([cfF, 'invoiceId:isEmpty:']), { token: adm });
    const ne = await rt.api('GET', q([cfF, 'invoiceId:isNotEmpty:']), { token: adm });
    const s = await rt.api('GET', `/api/promises/summary?filter=${encodeURIComponent(cfF)}&filter=${encodeURIComponent('invoiceId:isEmpty:')}`, { token: adm });
    return { ok: e.status === 200 && same(ids(e), [F.general.id]) && same(ids(ne), [F.kept.id, F.open.id, F.broken.id, F.twoInv.id]) && s.json?.total === 1,
      actual: `isEmpty -> ${e.status} ${JSON.stringify(ids(e))}; isNotEmpty -> ${ne.status} ${JSON.stringify(ids(ne))}; summary(isEmpty) total=${s.json?.total} ${s.status}` };
  });
  await tc('PRM-F07', 'Bad list input rejected with 400: unknown column, bad operator, bad enum, page size 7', async () => {
    const a = await rt.api('GET', q(['nope:eq:1']), { token: adm });
    const b = await rt.api('GET', q(['status:contains:KE']), { token: adm });
    const c = await rt.api('GET', q(['status:eq:NOTASTATUS']), { token: adm });
    const d = await rt.api('GET', '/api/promises?size=7', { token: adm });
    return { ok: [a, b, c, d].every((x) => x.status === 400), actual: `unknown col ${snip(a)} | op ${b.status} | enum ${c.status} | size7 ${d.status}` };
  });
  await tc('PRM-F08', 'Server-side sort on remainingAmount and amount', async () => {
    const r = await rt.api('GET', q([cfF], '&sort=remainingAmount,asc'), { token: adm });
    const rem = (r.json?.content || []).map((x) => Number(x.remainingAmount));
    const r2 = await rt.api('GET', q([cfF], '&sort=amount,desc'), { token: adm });
    const am = (r2.json?.content || []).map((x) => Number(x.amount));
    const asc = rem.every((v, i) => i === 0 || rem[i - 1] <= v);
    const desc = am.every((v, i) => i === 0 || am[i - 1] >= v);
    return { ok: r.status === 200 && asc && desc, actual: `remaining asc ${JSON.stringify(rem)}; amount desc ${JSON.stringify(am)}` };
  });
  await tc('PRM-F09', 'Summary tiles over the filtered set match the list', async () => {
    const s = (await rt.api('GET', `/api/promises/summary?filter=${encodeURIComponent(cfF)}`, { token: adm })).json;
    const list = (await rt.api('GET', q([cfF]), { token: adm })).json.content;
    const cnt = (st) => list.filter((x) => x.status === st).length;
    const amt = (st) => list.filter((x) => x.status === st).reduce((a, x) => a + Number(x.amount), 0);
    const ok = s.total === list.length && s.openCount === cnt('OPEN') && s.keptCount === cnt('KEPT') && s.brokenCount === cnt('BROKEN') && s.partiallyKeptCount === cnt('PARTIALLY_KEPT')
      && Number(s.openAmount) === amt('OPEN') && Number(s.brokenAmount) === amt('BROKEN') && Number(s.promisedAmount) === list.filter((x) => x.status !== 'CANCELLED').reduce((a, x) => a + Number(x.amount), 0);
    return { ok, actual: `summary ${JSON.stringify(s)}; list statuses ${JSON.stringify(list.map((x) => x.status + ':' + x.amount))}` };
  });

  // ---- bulk & export ----
  const cb = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(cb.id, collA.id, true);
  const bp = [];
  for (let i = 0; i < 4; i++) bp.push((await fx.promise({ customerId: cb.id, amount: 10 + i, promisedDate: addDays(5 + i), notes: i === 0 ? 'He said "soon", ok' : null })).json);
  await tc('PRM-BK01', 'Bulk REASSIGN_COLLECTION_POC moves the selected promises to the new POC', async () => {
    const r = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'REASSIGN_COLLECTION_POC', ids: [bp[0].id, bp[1].id], params: { userId: collB.id } } });
    const g0 = await fx.getPromise(bp[0].id); const g2 = await fx.getPromise(bp[2].id);
    return { ok: r.status === 200 && same(r.json.succeeded, [bp[0].id, bp[1].id]) && g0.json.collectionPoc.id === collB.id && g2.json.collectionPoc.id === collA.id,
      actual: `${r.status} ${JSON.stringify(r.json)}; #${bp[0].id} poc=${g0.json.collectionPoc.username}; untouched #${bp[2].id} poc=${g2.json.collectionPoc.username}` };
  });
  await tc('PRM-BK02', 'Bulk REASSIGN: missing userId -> 400; non-assignable user -> per-row failed', async () => {
    const a = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'REASSIGN_COLLECTION_POC', ids: [bp[0].id] } });
    const b = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'REASSIGN_COLLECTION_POC', ids: [bp[0].id], params: { userId: sales.id } } });
    return { ok: a.status === 400 && b.status === 200 && b.json.failed.length === 1 && b.json.succeeded.length === 0, actual: `no userId ${snip(a)} | sales user ${snip(b)}` };
  });
  await tc('PRM-BK03', 'Bulk CANCEL: live ids succeed; an already-cancelled id is reported, not dropped', async () => {
    const r = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'CANCEL', ids: [bp[2].id, bp[3].id], params: { reason: 'bulk rt' } } });
    const r2 = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'CANCEL', ids: [bp[2].id, bp[1].id] } });
    const g = await fx.getPromise(bp[2].id);
    const accounted = r2.json ? r2.json.succeeded.length + r2.json.failed.length + r2.json.skipped.length : 0;
    return { ok: r.status === 200 && same(r.json.succeeded, [bp[2].id, bp[3].id]) && g.json.status === 'CANCELLED' && r2.status === 200 && accounted === 2 && r2.json.succeeded.includes(bp[1].id),
      actual: `first ${r.status} ${JSON.stringify(r.json)}; second (one already cancelled) ${r2.status} ${JSON.stringify(r2.json)}` };
  });
  await tc('PRM-BK04', 'Bulk: unknown action -> 400; no ids and no selectAll -> 400; CASHIER / VIEWER -> 403', async () => {
    const a = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'DELETE', ids: [bp[0].id] } });
    const b = await rt.api('POST', '/api/promises/bulk', { token: adm, body: { action: 'CANCEL' } });
    const c = await rt.api('POST', '/api/promises/bulk', { token: cashT, body: { action: 'CANCEL', ids: [bp[0].id] } });
    const d = await rt.api('POST', '/api/promises/bulk', { token: viewerT, body: { action: 'CANCEL', ids: [bp[0].id] } });
    return { ok: a.status === 400 && b.status === 400 && c.status === 403 && d.status === 403, actual: `unknown ${snip(a)} | empty ${snip(b)} | cashier ${c.status} | viewer ${d.status}` };
  });
  await tc('PRM-BK05', 'Bulk CANCEL selectAllMatchingFilter applies to the whole filtered set (Collection POC caller)', async () => {
    const cz = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(cz.id, collA.id, true);
    for (let i = 0; i < 3; i++) await fx.promise({ customerId: cz.id, amount: 5, promisedDate: addDays(5) });
    const r = await rt.api('POST', '/api/promises/bulk', { token: collAT, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: [`customerId:eq:${cz.id}`] } });
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${cz.id}`, { token: adm })).json;
    return { ok: r.status === 200 && r.json.requested === 3 && r.json.succeeded.length === 3 && s.cancelledCount === 3, actual: `${r.status} requested=${r.json?.requested} succeeded=${r.json?.succeeded?.length}; summary cancelled=${s.cancelledCount}` };
  });
  await tc('PRM-E01', 'Export selected ids -> CSV with header and one row per id (quotes escaped)', async () => {
    const r = await rt.api('POST', '/api/promises/export', { token: adm, body: { action: 'EXPORT', ids: [bp[0].id, bp[1].id] } });
    const lines = r.text.trim().split(/\r?\n/);
    return { ok: r.status === 200 && /text\/csv/.test(r.headers['content-type']) && lines[0].startsWith('Id,Customer,Promised amount') && lines.length === 3 && r.text.includes('""soon""'),
      actual: `${r.status} ${r.headers['content-type']} lines=${lines.length}`, evidence: r.text.slice(0, 400).replace(/\n/g, ' \\n ') };
  });
  await tc('PRM-E02', 'Export selectAllMatchingFilter with customerId filter returns every matching row; CASHIER may export; VIEWER / customer 403', async () => {
    const r = await rt.api('POST', '/api/promises/export', { token: adm, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [cfF] } });
    const rc = await rt.api('POST', '/api/promises/export', { token: cashT, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [cfF] } });
    const rv = await rt.api('POST', '/api/promises/export', { token: viewerT, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [cfF] } });
    const lines = r.text.trim().split(/\r?\n/);
    return { ok: r.status === 200 && lines.length === 6 && rc.status === 200 && rv.status === 403, actual: `admin ${r.status} rows=${lines.length - 1}; cashier ${rc.status}; viewer ${rv.status}` };
  });

  // ---- customer login: read-only, POC stripped (US-B7) ----
  const cc = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(cc.id, collA.id, true);
  const ccT = await rt.login(cc.username, cc.password);
  const ccInv = await fx.invoice(cc.id, 90);
  const ccP = (await fx.promise({ customerId: cc.id, amount: 90, promisedDate: addDays(8), invoiceIds: [ccInv.id], notes: 'cust view' })).json;
  await rt.api('POST', `/api/promises/${ccP.id}/override`, { token: adm, body: { status: 'KEPT', reason: 'rt' } });
  await tc('PRM-CU01', 'Customer lists and reads only its own promises, with collectionPoc stripped', async () => {
    const l = await rt.api('GET', '/api/promises?size=50', { token: ccT });
    const g = await fx.getPromise(ccP.id, ccT);
    const other = await fx.getPromise(F.kept.id, ccT);
    const onlyOwn = (l.json?.content || []).every((x) => x.customerId === cc.id);
    const noPoc = (l.json?.content || []).every((x) => x.collectionPoc == null) && g.json?.collectionPoc == null;
    const s = await rt.api('GET', '/api/promises/summary', { token: ccT });
    return { ok: l.status === 200 && l.json.totalElements === 1 && onlyOwn && noPoc && g.status === 200 && other.status === 403 && s.json?.total === 1,
      actual: `list ${l.status} total=${l.json?.totalElements} onlyOwn=${onlyOwn} pocStripped=${noPoc}; own GET ${g.status}; other customer's promise ${other.status}; summary total=${s.json?.total}`,
      evidence: `own promise payload keys: collectionPoc=${JSON.stringify(g.json?.collectionPoc)} createdByUserId=${g.json?.createdByUserId} overriddenByUserId=${g.json?.overriddenByUserId}` };
  });
  await tc('PRM-CU02', 'Customer cannot create, edit, cancel, override, clear override, bulk or export', async () => {
    const r = [
      await fx.promise({ customerId: cc.id, amount: 5, promisedDate: addDays(3) }, ccT),
      await rt.api('PUT', `/api/promises/${ccP.id}`, { token: ccT, body: { amount: 5, promisedDate: addDays(3) } }),
      await rt.api('POST', `/api/promises/${ccP.id}/cancel`, { token: ccT, body: {} }),
      await rt.api('POST', `/api/promises/${ccP.id}/override`, { token: ccT, body: { status: 'BROKEN', reason: 'x' } }),
      await rt.api('DELETE', `/api/promises/${ccP.id}/override`, { token: ccT }),
      await rt.api('POST', '/api/promises/bulk', { token: ccT, body: { action: 'CANCEL', ids: [ccP.id] } }),
      await rt.api('POST', '/api/promises/export', { token: ccT, body: { action: 'EXPORT', ids: [ccP.id] } }),
    ];
    return { ok: r.every((x) => x.status === 403), actual: `create/PUT/cancel/override/clear/bulk/export -> ${r.map((x) => x.status).join('/')}` };
  });
  await tc('PRM-CU03', 'Customer cannot filter or sort promises by the POC-restricted columns (AC-A8, lead #10)', async () => {
    const a = await rt.api('GET', q([`collectionPocUserId:eq:${collA.id}`]), { token: ccT });
    const b = await rt.api('GET', q([`collectionPocUserId:eq:${collB.id}`]), { token: ccT });
    const c = await rt.api('GET', '/api/promises?size=50&sort=collectionPocName,asc', { token: ccT });
    const schema = await rt.api('GET', '/api/table-schemas/promises', { token: ccT });
    const cols = (schema.json?.columns || []).map((x) => x.key || x.name || x.field);
    return { ok: a.status === 400 && b.status === 400 && c.status === 400,
      actual: `filter collectionPocUserId=<own POC> -> ${a.status} total=${a.json?.totalElements}; =<other POC> -> ${b.status} total=${b.json?.totalElements}; sort collectionPocName -> ${c.status}; schema columns offered=${JSON.stringify(cols)}` };
  });
  await tc('PRM-P02', 'VIEWER and SALES_POC can list promises (PROMISE_VIEW) but not create', async () => {
    const v = await rt.api('GET', '/api/promises?size=10', { token: viewerT });
    const vc = await fx.promise({ customerId: cf.id, amount: 5, promisedDate: addDays(3) }, viewerT);
    const s = await rt.api('GET', '/api/promises?size=10', { token: salesT });
    const sc = await fx.promise({ customerId: cf.id, amount: 5, promisedDate: addDays(3) }, salesT);
    return { ok: v.status === 200 && vc.status === 403 && s.status === 200 && sc.status === 403, actual: `viewer list ${v.status} create ${vc.status}; sales list ${s.status} total=${s.json?.totalElements} locked=${JSON.stringify(s.json?.lockedFilters)} create ${sc.status}` };
  });

  // ---- leads ----
  await tc('PRM-L01', 'General promise KEPT because the account owed nothing by its date must not flip to BROKEN when a later invoice is created (AC-B7, lead #5)', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collB.id, true);
    const p = (await fx.promise({ customerId: c.id, amount: 500, promisedDate: addDays(-2) })).json;
    await fx.invoice(c.id, 300);
    const g = await fx.getPromise(p.id);
    const n = (await fx.notificationsFor(collBT)).filter((x) => x.type === 'PROMISE_BROKEN' && x.link === `/promises/${p.id}`);
    return { ok: p.status === 'KEPT' && g.json.status === 'KEPT', actual: `created (date 2 days ago, account owes nothing) -> ${p.status}; after a new invoice is raised today -> ${g.json.status}; broken notifications=${n.length}` };
  });
  await tc('PRM-L02', 'Payment explicitly linked to a promise (promiseIds) but allocated to a different invoice (lead #30)', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const older = await fx.invoice(c.id, 100);
    const newer = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(5), invoiceIds: [newer.id] })).json;
    const pay = await fx.pay(c.id, 100, { promiseIds: [p.id] });
    const g = await fx.getPromise(p.id);
    const alloc = (pay.json?.invoices || []).map((i) => `${i.invoiceNumber}:${i.allocatedAmount}`);
    const linked = g.json.payments.some((x) => x.id === pay.json.id);
    return { ok: !(linked && Number(g.json.fulfilledAmount) === 0), actual: `payment ${pay.status} allocated ${JSON.stringify(alloc)} (promise covers ${newer.invoiceNumber}); promise ${g.json.status} linked=${linked} fulfilled=${g.json.fulfilledAmount}` };
  });
  await tc('PRM-L03', 'Default Collection POC must not be an inactive (deactivated) primary (lead #23)', async () => {
    const collC = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collC.id, true);
    const del = await rt.api('DELETE', `/api/users/${collC.id}`, { token: adm });
    const p = await fx.promise({ customerId: c.id, amount: 10, promisedDate: addDays(3) });
    return { ok: p.status === 400 || (p.status === 200 && p.json.collectionPoc?.active === true), actual: `delete user -> ${snip(del)}; promise without POC -> ${p.status} poc=${p.json?.collectionPoc?.username} active=${p.json?.collectionPoc?.active} ${p.status !== 200 ? p.text.slice(0, 150) : ''}` };
  });
  await tc('PRM-L04', 'Explicit promiseIds: a promise of another customer / a cancelled promise are refused', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collA.id, true);
    const other = await fx.pay(c.id, 10, { promiseIds: [F.open.id] });
    const cancelled = await fx.pay(cb.id, 10, { promiseIds: [bp[2].id] });
    return { ok: other.status === 400 && cancelled.status === 400, actual: `other customer's promise -> ${snip(other)} | cancelled promise -> ${snip(cancelled)}` };
  });

  require('fs').writeFileSync(__dirname + '/ids-api2.json', JSON.stringify({ cf: cf.id, cb: cb.id, cc: cc.id, ccUser: cc.username, collA: collA.id, collAUser: collA.username, F }, null, 1));
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
