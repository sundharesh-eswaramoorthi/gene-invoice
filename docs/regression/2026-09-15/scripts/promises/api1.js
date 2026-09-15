// API part 1: create validation, POC default, auto-link KEPT / PARTIALLY_KEPT, general promise,
// BROKEN + notify-once, override / clear override, cancel, permissions.
const fx = require('./fx');
const { rt, addDays } = fx;
const { makeHarness, snip } = require('./harness');
const { tc, save } = makeHarness('api1');

(async () => {
  const adm = await fx.admin();
  const admId = await fx.adminId();
  const collA = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const collB = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const sales = await rt.createStaff(adm, 'SALES_POC', 'promises');
  const collAT = await rt.login(collA.username, collA.password);
  const collBT = await rt.login(collB.username, collB.password);
  const cashT = await rt.cashierToken();

  const c1 = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(c1.id, collA.id, true);
  const c2 = await rt.createCustomer(adm, 'promises');
  const inv1 = await fx.invoice(c1.id, 100);
  const inv2 = await fx.invoice(c1.id, 200);
  const invC2 = await fx.invoice(c2.id, 50);
  const invCancel = await fx.invoice(c1.id, 30);
  const cx = await rt.api('POST', `/api/invoices/${invCancel.id}/cancel`, { token: adm });
  console.log('setup: c1', c1.id, 'c2', c2.id, 'inv1', inv1.id, inv1.total, 'inv2', inv2.id, 'invCancel', invCancel.id, cx.status, 'collA', collA.id, 'collB', collB.id);
  const base = { customerId: c1.id, amount: 100, promisedDate: addDays(7) };

  // ---- validation (AC-B1) ----
  await tc('PRM-V01', 'Create rejects amount 0 and negative amount', async () => {
    const a = await fx.promise({ ...base, amount: 0 });
    const b = await fx.promise({ ...base, amount: -5 });
    return { ok: a.status === 400 && b.status === 400, actual: `amount 0 -> ${snip(a)} ; amount -5 -> ${snip(b)}` };
  });
  await tc('PRM-V02', 'Create rejects a missing promisedDate', async () => {
    const a = await fx.promise({ customerId: c1.id, amount: 100 });
    return { ok: a.status === 400, actual: snip(a) };
  });
  await tc('PRM-V03', 'Create rejects missing / unknown customer', async () => {
    const a = await fx.promise({ amount: 100, promisedDate: addDays(3) });
    const b = await fx.promise({ customerId: 987654321, amount: 100, promisedDate: addDays(3) });
    return { ok: a.status === 400 && b.status === 404, actual: `missing -> ${snip(a)} ; unknown -> ${snip(b)}` };
  });
  await tc('PRM-V04', "Create rejects an invoice belonging to another customer", async () => {
    const a = await fx.promise({ ...base, invoiceIds: [inv1.id, invC2.id] });
    return { ok: a.status === 400 && /different customer/i.test(a.text), actual: snip(a) };
  });
  await tc('PRM-V05', 'Create rejects a CANCELLED invoice', async () => {
    const a = await fx.promise({ ...base, invoiceIds: [invCancel.id] });
    return { ok: cx.status === 200 && a.status === 400 && /cancelled/i.test(a.text), actual: `invoice cancel -> ${cx.status}; promise -> ${snip(a)}` };
  });
  await tc('PRM-V06', 'Create rejects an unknown invoice id', async () => {
    const a = await fx.promise({ ...base, invoiceIds: [987654321] });
    return { ok: a.status === 404, actual: snip(a) };
  });

  // ---- Collection POC default (AC-B8) ----
  let pDefault;
  await tc('PRM-D01', "Collection POC defaults to the customer's primary when omitted", async () => {
    const a = await fx.promise({ ...base, notes: 'default poc' });
    pDefault = a.json;
    return { ok: a.status === 200 && a.json.collectionPoc?.id === collA.id && a.json.status === 'OPEN', actual: `${a.status} collectionPoc=${a.json?.collectionPoc?.username} (expected ${collA.username}) status=${a.json?.status}` };
  });
  await tc('PRM-D02', 'Collection POC required when the customer has no primary', async () => {
    const a = await fx.promise({ customerId: c2.id, amount: 20, promisedDate: addDays(3) });
    const b = await fx.promise({ customerId: c2.id, amount: 20, promisedDate: addDays(3), collectionPocUserId: collB.id });
    return { ok: a.status === 400 && /Collection POC is required/i.test(a.text) && b.status === 200 && b.json.collectionPoc?.id === collB.id, actual: `no poc -> ${snip(a)} ; explicit collB -> ${b.status} poc=${b.json?.collectionPoc?.username}` };
  });
  await tc('PRM-D03', 'An explicit POC not assignable as Collection (a SALES_POC user) is rejected', async () => {
    const a = await fx.promise({ ...base, collectionPocUserId: sales.id });
    return { ok: a.status === 400 && /not assignable/i.test(a.text), actual: snip(a) };
  });

  // ---- auto-link KEPT (AC-B3), PARTIALLY_KEPT (AC-B4) ----
  let p1, pay1;
  await tc('PRM-K01', 'Payment fully settling the promised invoice auto-links and marks KEPT (Collection POC creates it)', async () => {
    const a = await fx.promise({ ...base, invoiceIds: [inv1.id], notes: 'k01' }, collAT);
    p1 = a.json;
    pay1 = await fx.pay(c1.id, 100, { invoiceIds: [inv1.id] });
    const g = await fx.getPromise(p1.id);
    const ok = a.status === 200 && a.json.status === 'OPEN' && pay1.status === 200 && g.json.status === 'KEPT'
      && Number(g.json.fulfilledAmount) === 100 && Number(g.json.remainingAmount) === 0
      && g.json.payments.some((x) => x.id === pay1.json.id);
    return { ok, actual: `create by COLLECTION_POC -> ${a.status} ${a.json?.status}; payment ${pay1.status} #${pay1.json?.id}; promise now ${g.json.status} fulfilled=${g.json.fulfilledAmount} remaining=${g.json.remainingAmount} payments=${JSON.stringify(g.json.payments.map((x) => x.id))}` };
  });
  let p2;
  await tc('PRM-K02', 'Partial payment marks PARTIALLY_KEPT and records remainingAmount', async () => {
    const a = await fx.promise({ ...base, amount: 200, invoiceIds: [inv2.id] });
    p2 = a.json;
    const pay = await fx.pay(c1.id, 50, { invoiceIds: [inv2.id] });
    const g = await fx.getPromise(p2.id);
    return { ok: g.json.status === 'PARTIALLY_KEPT' && Number(g.json.fulfilledAmount) === 50 && Number(g.json.remainingAmount) === 150,
      actual: `payment ${pay.status}; promise ${g.json.status} fulfilled=${g.json.fulfilledAmount} remaining=${g.json.remainingAmount}` };
  });
  await tc('PRM-K03', 'Promised amount may exceed / fall short of the invoice balance (AC-B2, not blocked)', async () => {
    const a = await fx.promise({ ...base, amount: 999, invoiceIds: [inv2.id] });
    const b = await fx.promise({ ...base, amount: 1, invoiceIds: [inv2.id] });
    return { ok: a.status === 200 && b.status === 200, actual: `excess 999 vs balance 150 -> ${a.status} ${a.json?.status}; shortfall 1 -> ${b.status} ${b.json?.status}` };
  });

  // ---- general promise (AC-B7) ----
  const c3 = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(c3.id, collA.id, true);
  await fx.invoice(c3.id, 300);
  let pg;
  await tc('PRM-G01', 'General promise (no invoices) tracks payments: 40 -> PARTIALLY_KEPT, +60 -> KEPT', async () => {
    const a = await fx.promise({ customerId: c3.id, amount: 100, promisedDate: addDays(5) });
    pg = a.json;
    await fx.pay(c3.id, 40);
    const g1 = await fx.getPromise(pg.id);
    await fx.pay(c3.id, 60);
    const g2 = await fx.getPromise(pg.id);
    return { ok: a.status === 200 && a.json.invoices.length === 0 && a.json.status === 'OPEN' && g1.json.status === 'PARTIALLY_KEPT' && Number(g1.json.remainingAmount) === 60 && g2.json.status === 'KEPT' && Number(g2.json.fulfilledAmount) === 100,
      actual: `create ${a.status} ${a.json?.status} invoices=${a.json?.invoices?.length}; after 40: ${g1.json.status} rem=${g1.json.remainingAmount}; after +60: ${g2.json.status} fulfilled=${g2.json.fulfilledAmount} payments=${g2.json.payments.length}` };
  });

  // ---- BROKEN + notify once (AC-B5, AC-B10) ----
  const c4 = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(c4.id, collB.id, true);
  const inv4 = await fx.invoice(c4.id, 100);
  let pb;
  const brokenNotifs = async (id) => (await fx.notificationsFor(collBT)).filter((n) => n.type === 'PROMISE_BROKEN' && n.link === `/promises/${id}`);
  await tc('PRM-B01', 'Promise with a past date and no payment is BROKEN; Collection POC gets ONE notification deep-linking to it', async () => {
    const a = await fx.promise({ customerId: c4.id, amount: 100, promisedDate: addDays(-2), invoiceIds: [inv4.id] });
    pb = a.json;
    const n = await brokenNotifs(pb.id);
    return { ok: a.status === 200 && a.json.status === 'BROKEN' && a.json.collectionPoc?.id === collB.id && n.length === 1,
      actual: `create ${a.status} status=${a.json?.status} poc=${a.json?.collectionPoc?.username}; PROMISE_BROKEN notifications for collB = ${n.length}`, evidence: n[0] ? `${n[0].title} | ${n[0].message} | link ${n[0].link}` : '' };
  });
  await tc('PRM-B02', 'Re-evaluation (new invoice, promise edit) does not send a second broken notification', async () => {
    await fx.invoice(c4.id, 10);
    const u = await rt.api('PUT', `/api/promises/${pb.id}`, { token: adm, body: { amount: 100, promisedDate: pb.promisedDate, notes: 'edited', invoiceIds: [inv4.id] } });
    const n = await brokenNotifs(pb.id);
    const g = await fx.getPromise(pb.id);
    return { ok: u.status === 200 && g.json.status === 'BROKEN' && n.length === 1, actual: `PUT ${u.status}; status ${g.json.status}; notifications ${n.length}` };
  });
  await tc('PRM-B03', 'Paying after the date does not un-break the promise but is recorded in fulfilledAmount', async () => {
    await fx.pay(c4.id, 100, { invoiceIds: [inv4.id] });
    const g = await fx.getPromise(pb.id);
    const n = await brokenNotifs(pb.id);
    return { ok: g.json.status === 'BROKEN' && Number(g.json.fulfilledAmount) === 100 && n.length === 1, actual: `status ${g.json.status} fulfilled=${g.json.fulfilledAmount} remaining=${g.json.remainingAmount} notifications=${n.length}` };
  });
  await tc('PRM-B04', 'General promise past its date with an unpaid balance is BROKEN', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collB.id, true);
    await fx.invoice(c.id, 80);
    const a = await fx.promise({ customerId: c.id, amount: 80, promisedDate: addDays(-1) });
    const n = await brokenNotifs(a.json.id);
    return { ok: a.json.status === 'BROKEN' && n.length === 1, actual: `status ${a.json.status}; notifications ${n.length}` };
  });
  await tc('PRM-A01', 'Every status change writes an audit entry (AC-B9)', async () => {
    const r = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${pb.id}`, { token: adm });
    const acts = (r.json || []).map((x) => x.action);
    return { ok: r.status === 200 && acts.includes('PROMISE_CREATED') && acts.includes('PROMISE_STATUS_CHANGED') && acts.includes('PROMISE_UPDATED'), actual: `${r.status} actions=${JSON.stringify(acts)}` };
  });

  // ---- override (US-B6, AC-B9) ----
  const c5 = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(c5.id, collB.id, true);
  const inv5 = await fx.invoice(c5.id, 100);
  const po = (await fx.promise({ customerId: c5.id, amount: 100, promisedDate: addDays(10), invoiceIds: [inv5.id] })).json;
  await tc('PRM-O01', 'Override requires a reason and refuses CANCELLED / unknown status', async () => {
    const a = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'KEPT' } });
    const b = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'KEPT', reason: '   ' } });
    const c = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'CANCELLED', reason: 'x' } });
    const d = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'FOO', reason: 'x' } });
    const g = await fx.getPromise(po.id);
    return { ok: [a, b, c, d].every((x) => x.status === 400) && g.json.statusOverridden === false, actual: `no reason ${snip(a)} | blank ${snip(b)} | CANCELLED ${snip(c)} | FOO ${a.status === 400 ? d.status : d.status}; still overridden=${g.json.statusOverridden}` };
  });
  await tc('PRM-O02', 'Override OPEN -> KEPT with a reason records who/when/why and an audit row', async () => {
    const a = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'KEPT', reason: 'Customer showed wire receipt' } });
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${po.id}`, { token: adm });
    const acts = (au.json || []).map((x) => x.action);
    return { ok: a.status === 200 && a.json.status === 'KEPT' && a.json.statusOverridden && a.json.overrideReason === 'Customer showed wire receipt' && a.json.overriddenByUserId === admId && !!a.json.overriddenAt && acts.includes('PROMISE_STATUS_OVERRIDDEN'),
      actual: `${a.status} status=${a.json?.status} overridden=${a.json?.statusOverridden} reason=${a.json?.overrideReason} by=${a.json?.overriddenByUserId} at=${a.json?.overriddenAt}; audit=${JSON.stringify(acts)}` };
  });
  await tc('PRM-O03', 'An override pins the status while fulfilment keeps being tracked', async () => {
    await fx.pay(c5.id, 30, { invoiceIds: [inv5.id] });
    const g = await fx.getPromise(po.id);
    return { ok: g.json.status === 'KEPT' && g.json.statusOverridden && Number(g.json.fulfilledAmount) === 30, actual: `status ${g.json.status} overridden=${g.json.statusOverridden} fulfilled=${g.json.fulfilledAmount}` };
  });
  await tc('PRM-O04', 'DELETE /override hands back to automatic tracking (-> PARTIALLY_KEPT) and audits it', async () => {
    const d = await rt.api('DELETE', `/api/promises/${po.id}/override`, { token: adm });
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${po.id}`, { token: adm });
    const acts = (au.json || []).map((x) => x.action);
    const again = await rt.api('DELETE', `/api/promises/${po.id}/override`, { token: adm });
    return { ok: d.status === 200 && d.json.status === 'PARTIALLY_KEPT' && !d.json.statusOverridden && d.json.overrideReason == null && acts.includes('PROMISE_OVERRIDE_CLEARED') && again.status === 200,
      actual: `${d.status} status=${d.json?.status} overridden=${d.json?.statusOverridden} reason=${d.json?.overrideReason}; audit has CLEARED=${acts.includes('PROMISE_OVERRIDE_CLEARED')}; second DELETE (no override) -> ${again.status}` };
  });
  await tc('PRM-O05', 'Override to BROKEN notifies the Collection POC once', async () => {
    const a = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'BROKEN', reason: 'Customer refused' } });
    const a2 = await rt.api('POST', `/api/promises/${po.id}/override`, { token: adm, body: { status: 'BROKEN', reason: 'Customer refused again' } });
    const n = await brokenNotifs(po.id);
    await rt.api('DELETE', `/api/promises/${po.id}/override`, { token: adm });
    return { ok: a.status === 200 && a2.status === 200 && n.length === 1, actual: `override ${a.status}/${a2.status} status=${a.json?.status}; notifications to collB=${n.length}` };
  });
  await tc('PRM-O06', 'CASHIER (no PROMISE_MANAGE/OVERRIDE) can view but cannot create, override or clear', async () => {
    const v = await rt.api('GET', `/api/promises/${po.id}`, { token: cashT });
    const c = await fx.promise({ ...base }, cashT);
    const o = await rt.api('POST', `/api/promises/${po.id}/override`, { token: cashT, body: { status: 'KEPT', reason: 'x' } });
    const d = await rt.api('DELETE', `/api/promises/${po.id}/override`, { token: cashT });
    return { ok: v.status === 200 && c.status === 403 && o.status === 403 && d.status === 403, actual: `view ${v.status} create ${c.status} override ${o.status} clear ${d.status}` };
  });

  // ---- cancel (AC-B11) ----
  const c6 = await rt.createCustomer(adm, 'promises');
  await fx.addPoc(c6.id, collA.id, true);
  const inv6 = await fx.invoice(c6.id, 100);
  const pc = (await fx.promise({ customerId: c6.id, amount: 100, promisedDate: addDays(4), invoiceIds: [inv6.id] })).json;
  const pay6 = await fx.pay(c6.id, 100, { invoiceIds: [inv6.id] });
  await tc('PRM-C01', 'Cancel unlinks payments without altering the payment or its allocations', async () => {
    const before = await fx.getPromise(pc.id);
    const payBefore = await rt.api('GET', `/api/payments/${pay6.json.id}`, { token: adm });
    const invBefore = await rt.api('GET', `/api/invoices/${inv6.id}`, { token: adm });
    const c = await rt.api('POST', `/api/promises/${pc.id}/cancel`, { token: adm, body: { reason: 'raised in error' } });
    const payAfter = await rt.api('GET', `/api/payments/${pay6.json.id}`, { token: adm });
    const invAfter = await rt.api('GET', `/api/invoices/${inv6.id}`, { token: adm });
    const pick = (p) => JSON.stringify({ a: p.amount, s: p.status, c: p.creditApplied, inv: p.invoices.map((i) => [i.id, i.allocatedAmount]) });
    const pickI = (i) => JSON.stringify({ paid: i.paidAmount, bal: i.balance, s: i.status });
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${pc.id}`, { token: adm });
    const ok = before.json.status === 'KEPT' && before.json.payments.length === 1 && c.status === 200 && c.json.status === 'CANCELLED'
      && c.json.payments.length === 0 && Number(c.json.fulfilledAmount) === 0 && pick(payBefore.json) === pick(payAfter.json) && pickI(invBefore.json) === pickI(invAfter.json)
      && (au.json || []).some((x) => x.action === 'PROMISE_CANCELLED');
    return { ok, actual: `before ${before.json.status} links=${before.json.payments.length}; cancel ${c.status} -> ${c.json?.status} links=${c.json?.payments?.length} fulfilled=${c.json?.fulfilledAmount}; payment before=${pick(payBefore.json)} after=${pick(payAfter.json)}; invoice before=${pickI(invBefore.json)} after=${pickI(invAfter.json)}` };
  });
  await tc('PRM-C02', 'A cancelled promise cannot be cancelled again or edited; re-evaluation never resurrects it', async () => {
    const a = await rt.api('POST', `/api/promises/${pc.id}/cancel`, { token: adm, body: {} });
    const b = await rt.api('PUT', `/api/promises/${pc.id}`, { token: adm, body: { amount: 100, promisedDate: addDays(4) } });
    await fx.pay(c6.id, 5); // re-evaluate the customer's book
    const g = await fx.getPromise(pc.id);
    return { ok: a.status === 400 && b.status === 400 && g.json.status === 'CANCELLED' && g.json.payments.length === 0, actual: `cancel again ${snip(a)} | PUT ${snip(b)} | after new payment status=${g.json.status} links=${g.json.payments.length}` };
  });
  await tc('PRM-C03', 'Override on a CANCELLED promise is refused (lead #20)', async () => {
    const o = await rt.api('POST', `/api/promises/${pc.id}/override`, { token: adm, body: { status: 'OPEN', reason: 'revive?' } });
    let extra = '';
    if (o.status === 200) {
      const d = await rt.api('DELETE', `/api/promises/${pc.id}/override`, { token: adm });
      extra = ` ; then DELETE override -> ${d.status} status=${d.json?.status} links=${JSON.stringify(d.json?.payments?.map((x) => x.id))} fulfilled=${d.json?.fulfilledAmount}`;
    }
    return { ok: o.status === 400, actual: `override on CANCELLED -> ${o.status} status=${o.json?.status}${extra}` };
  });
  await tc('PRM-C04', 'Cancel with no body is accepted (reason optional)', async () => {
    const p = (await fx.promise({ customerId: c6.id, amount: 10, promisedDate: addDays(4) })).json;
    const c = await rt.api('POST', `/api/promises/${p.id}/cancel`, { token: adm });
    return { ok: c.status === 200 && c.json.status === 'CANCELLED', actual: snip(c) };
  });

  // ---- update ----
  await tc('PRM-U01', 'PUT validation: amount 0 -> 400, missing promisedDate -> 400', async () => {
    const a = await rt.api('PUT', `/api/promises/${pDefault.id}`, { token: adm, body: { amount: 0, promisedDate: addDays(3) } });
    const b = await rt.api('PUT', `/api/promises/${pDefault.id}`, { token: adm, body: { amount: 10 } });
    return { ok: a.status === 400 && b.status === 400, actual: `amount 0 ${snip(a)} | no date ${snip(b)}` };
  });
  await tc('PRM-U02', 'PUT moving the date into the past with nothing paid re-evaluates to BROKEN', async () => {
    const c = await rt.createCustomer(adm, 'promises');
    await fx.addPoc(c.id, collB.id, true);
    const inv = await fx.invoice(c.id, 60);
    const p = (await fx.promise({ customerId: c.id, amount: 60, promisedDate: addDays(3), invoiceIds: [inv.id] })).json;
    const u = await rt.api('PUT', `/api/promises/${p.id}`, { token: adm, body: { amount: 60, promisedDate: addDays(-1), invoiceIds: [inv.id], collectionPocUserId: collB.id } });
    const n = await brokenNotifs(p.id);
    return { ok: p.status === 'OPEN' && u.status === 200 && u.json.status === 'BROKEN' && n.length === 1, actual: `before ${p.status}; PUT ${u.status} -> ${u.json?.status}; notifications=${n.length}` };
  });
  await tc('PRM-U03', 'PUT reassigning the Collection POC changes it and notifies the new POC', async () => {
    const u = await rt.api('PUT', `/api/promises/${pDefault.id}`, { token: adm, body: { amount: 100, promisedDate: pDefault.promisedDate, collectionPocUserId: collB.id } });
    const n = (await fx.notificationsFor(collBT)).filter((x) => x.link === `/promises/${pDefault.id}`);
    return { ok: u.status === 200 && u.json.collectionPoc?.id === collB.id && n.length >= 1, actual: `${u.status} poc=${u.json?.collectionPoc?.username}; notifications to collB for this promise=${n.length} ${n[0] ? n[0].title : ''}` };
  });
  await tc('PRM-P01', 'GET unknown promise -> 404', async () => {
    const g = await fx.getPromise(987654321);
    return { ok: g.status === 404, actual: snip(g) };
  });

  require('fs').writeFileSync(__dirname + '/ids-api1.json', JSON.stringify({ c1: c1.id, c3: c3.id, c4: c4.id, c5: c5.id, c6: c6.id, collA: collA.id, collB: collB.id, collAUser: collA.username, collBUser: collB.username, p1: p1?.id, p2: p2?.id, pg: pg?.id, pb: pb?.id, po: po.id }, null, 1));
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
