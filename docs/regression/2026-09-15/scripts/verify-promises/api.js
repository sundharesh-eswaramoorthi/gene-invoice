// Independent reproduction of the promises failures (API side).
const { rt, addDays, adm, invoice, addPoc, pay, promise, get, brief } = require('./fx');
const out = {};
const log = (k, ...a) => { console.log(k, ...a.map((x) => (typeof x === 'string' ? x : JSON.stringify(x)))); (out[k] = out[k] || []).push(a); };
const q = (filters, extra = '') => '/api/promises?size=50' + filters.map((f) => '&filter=' + encodeURIComponent(f)).join('') + extra;

(async () => {
  const T = await adm();
  const coll = await rt.createStaff(T, 'COLLECTION_POC', 'vprm');
  const collT = await rt.login(coll.username, coll.password);
  const cust = async () => { const c = await rt.createCustomer(T, 'vprm'); await addPoc(c.id, coll.id, true); return c; };

  // ---- PRM-O07: unknown override status
  {
    const c = await cust(); const inv = await invoice(c.id, 100);
    const p = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(4), invoiceIds: [inv.id] })).json;
    const r = await rt.api('POST', `/api/promises/${p.id}/override`, { token: T, body: { status: 'FOO', reason: 'x' } });
    log('O07 override FOO', r.status, r.text.slice(0, 260));
    const r2 = await rt.api('POST', `/api/promises/${p.id}/override`, { token: T, body: { reason: 'x' } });
    log('O07 override missing status', r2.status, r2.text.slice(0, 200));
    const r3 = await rt.api('POST', '/api/promises', { token: T, body: { customerId: c.id, amount: 10, promisedDate: 'not-a-date' } });
    log('O07 create bad date (same handler path)', r3.status, r3.text.slice(0, 200));
  }

  // ---- PRM-C03: override revives a cancelled promise
  {
    const c = await cust(); const inv = await invoice(c.id, 100);
    const p = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(4), invoiceIds: [inv.id] })).json;
    const py = await pay(c.id, 100, { invoiceIds: [inv.id] });
    log('C03 after pay', brief((await get(p.id)).json), 'payment', py.json.id);
    const cn = await rt.api('POST', `/api/promises/${p.id}/cancel`, { token: T, body: { reason: 'raised in error' } });
    log('C03 cancel', cn.status, brief(cn.json));
    const put = await rt.api('PUT', `/api/promises/${p.id}`, { token: T, body: { amount: 100, promisedDate: addDays(5) } });
    log('C03 PUT on cancelled', put.status, put.json?.message);
    const ov = await rt.api('POST', `/api/promises/${p.id}/override`, { token: T, body: { status: 'OPEN', reason: 'revive' } });
    log('C03 override OPEN on cancelled', ov.status, brief(ov.json));
    const cl = await rt.api('DELETE', `/api/promises/${p.id}/override`, { token: T });
    log('C03 clear override', cl.status, brief(cl.json));
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${p.id}`, { token: T });
    log('C03 audit', au.status, (au.json || []).map((x) => x.action));
  }

  // ---- PRM-R04: edit after one invoice is cancelled
  {
    const c = await cust(); const a = await invoice(c.id, 100); const b = await invoice(c.id, 100);
    const p = (await promise({ customerId: c.id, amount: 200, promisedDate: addDays(6), invoiceIds: [a.id, b.id] })).json;
    const cb = await rt.api('POST', `/api/invoices/${b.id}/cancel`, { token: T });
    log('R04 cancel B', cb.status, cb.json?.status, 'promise now', brief((await get(p.id)).json));
    const body = { customerId: c.id, amount: 200, promisedDate: addDays(9), collectionPocUserId: coll.id, notes: 'date moved', invoiceIds: [a.id, b.id] };
    const u1 = await rt.api('PUT', `/api/promises/${p.id}`, { token: T, body });
    log('R04 PUT (dialog body, both ids)', u1.status, u1.json?.message);
    const u2 = await rt.api('PUT', `/api/promises/${p.id}`, { token: T, body: { ...body, invoiceIds: [a.id] } });
    log('R04 PUT (only live id)', u2.status, u2.json?.message || brief(u2.json));
    const u3 = await rt.api('PUT', `/api/promises/${p.id}`, { token: T, body: { amount: 200, promisedDate: addDays(10), notes: 'no ids' } });
    log('R04 PUT (no invoiceIds)', u3.status, u3.json?.message || brief(u3.json));
    // also: all invoices cancelled -> dialog would send [] ? check the invoice list the dialog loads
    const il = await rt.api('GET', `/api/invoices?size=50&customerId=${c.id}&filter=${encodeURIComponent('status:in:UNPAID,PARTIALLY_PAID')}`, { token: T });
    log('R04 dialog checklist source', il.status, (il.json?.content || []).map((i) => i.invoiceNumber + '/' + i.status));
    out.R04 = { customerId: c.id, promiseId: p.id, a: a.invoiceNumber, b: b.invoiceNumber };
  }

  // ---- PRM-M03: two promises on the same invoice, one payment
  {
    const c = await cust(); const inv = await invoice(c.id, 100);
    const p1 = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    const p2 = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] })).json;
    await pay(c.id, 100, { invoiceIds: [inv.id] });
    log('M03 p1', brief((await get(p1.id)).json), 'p2', brief((await get(p2.id)).json));
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${c.id}`, { token: T })).json;
    const ps = (await rt.api('GET', `/api/payments/summary?filter=${encodeURIComponent('customerId:eq:' + c.id)}`, { token: T })).json;
    log('M03 promise summary', s, 'payments summary', ps);
  }

  // ---- PRM-M04: two general promises, one payment
  {
    const c = await cust(); await invoice(c.id, 500);
    const g1 = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) })).json;
    const g2 = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) })).json;
    await pay(c.id, 100);
    log('M04 g1', brief((await get(g1.id)).json), 'g2', brief((await get(g2.id)).json));
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${c.id}`, { token: T })).json;
    const ps = (await rt.api('GET', `/api/payments/summary?filter=${encodeURIComponent('customerId:eq:' + c.id)}`, { token: T })).json;
    log('M04 promise summary', s, 'payments summary', ps);
    // mixed: invoice-scoped promise on B + general promise, one payment aimed at B
    const c2 = await cust(); await invoice(c2.id, 300); const b2 = await invoice(c2.id, 250);
    const sc = (await promise({ customerId: c2.id, amount: 250, promisedDate: addDays(6), invoiceIds: [b2.id] })).json;
    const gg = (await promise({ customerId: c2.id, amount: 75, promisedDate: addDays(6) })).json;
    const py = await pay(c2.id, 250, { invoiceIds: [b2.id] });
    log('M04 mixed scoped', brief((await get(sc.id)).json), 'general', brief((await get(gg.id)).json), 'payment', py.json.id);
    const s2 = (await rt.api('GET', `/api/promises/summary?customerId=${c2.id}`, { token: T })).json;
    log('M04 mixed summary fulfilled', s2.fulfilledAmount, 'kept', s2.keptCount, s2.keptAmount);
  }

  // ---- PRM-CU03: customer filters/sorts on POC-restricted columns
  {
    const c = await cust(); const ct = await rt.login(c.username, c.password);
    await promise({ customerId: c.id, amount: 10, promisedDate: addDays(6) });
    const other = await rt.createStaff(T, 'COLLECTION_POC', 'vprm');
    const sch = await rt.api('GET', '/api/table-schemas/promises', { token: ct });
    log('CU03 schema cols', sch.status, (sch.json?.columns || []).map((x) => x.name));
    const a = await rt.api('GET', q([`collectionPocUserId:eq:${coll.id}`]), { token: ct });
    const b = await rt.api('GET', q([`collectionPocUserId:eq:${other.id}`]), { token: ct });
    const s = await rt.api('GET', '/api/promises?size=10&sort=collectionPocName,asc', { token: ct });
    const n1 = await rt.api('GET', q([`collectionPocName:contains:${coll.username.toUpperCase()}`]), { token: ct });
    const n2 = await rt.api('GET', q([`collectionPocName:contains:ZZZNOPE`]), { token: ct });
    const e = await rt.api('GET', q([`collectionPocUserId:isEmpty:`]), { token: ct });
    log('CU03 customer: own POC id', a.status, a.json?.totalElements, '| other id', b.status, b.json?.totalElements,
      '| sort', s.status, '| name contains own', n1.status, n1.json?.totalElements, '| name nope', n2.status, n2.json?.totalElements, '| isEmpty', e.status, e.json?.totalElements);
    log('CU03 customer row POC field', a.json?.content?.[0]?.collectionPoc, 'createdBy', a.json?.content?.[0]?.createdByUserId);
    const inv = await rt.api('GET', `/api/invoices?size=10&filter=${encodeURIComponent('salesPocName:contains:a')}`, { token: ct });
    log('CU03 customer invoices salesPocName filter', inv.status);
  }

  // ---- PRM-L01: kept general promise flips to BROKEN after a new invoice
  {
    const c = await cust();
    const p = (await promise({ customerId: c.id, amount: 500, promisedDate: addDays(-2) })).json;
    log('L01 created', brief(p));
    const before = (await rt.api('GET', '/api/notifications?size=50', { token: collT })).json.content.filter((x) => x.link === `/promises/${p.id}`).length;
    await invoice(c.id, 300);
    const g = (await get(p.id)).json;
    const after = (await rt.api('GET', '/api/notifications?size=50', { token: collT })).json.content.filter((x) => x.link === `/promises/${p.id}`);
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${p.id}`, { token: T });
    log('L01 after new invoice', brief(g), 'notifs before', before, 'after', after.map((x) => x.type + ':' + x.title), 'audit', (au.json || []).map((x) => x.action + (x.newValue ? ' ' + JSON.stringify(x.newValue).slice(0, 40) : '')));
    // control: general promise with future date on debt-free account: creating invoice before date -> stays OPEN (expected)
    const c2 = await cust();
    const p2 = (await promise({ customerId: c2.id, amount: 500, promisedDate: addDays(3) })).json;
    await invoice(c2.id, 300);
    log('L01 control (future-dated)', brief(p2), '->', brief((await get(p2.id)).json));
  }

  // ---- PRM-L02: payment ticked to a promise but allocated elsewhere
  {
    const c = await cust(); const older = await invoice(c.id, 100); const newer = await invoice(c.id, 100);
    const p = (await promise({ customerId: c.id, amount: 100, promisedDate: addDays(5), invoiceIds: [newer.id] })).json;
    const py = await pay(c.id, 100, { promiseIds: [p.id] });
    log('L02 payment', py.status, (py.json?.invoices || []).map((i) => i.invoiceNumber + ':' + i.allocatedAmount), 'older', older.invoiceNumber, 'newer(promised)', newer.invoiceNumber);
    log('L02 promise', brief((await get(p.id)).json));
  }

  // ---- PRM-L03: default POC is a deactivated user
  {
    const x = await rt.createStaff(T, 'COLLECTION_POC', 'vprm');
    const c = await rt.createCustomer(T, 'vprm'); await addPoc(c.id, x.id, true);
    const d = await rt.api('DELETE', `/api/users/${x.id}`, { token: T });
    log('L03 delete user', d.status, d.text);
    const pocs = await rt.api('GET', `/api/customers/${c.id}/pocs`, { token: T });
    log('L03 customer pocs', pocs.status, (pocs.json || []).map?.((s) => s.pocType + ':' + (s.user?.username) + ':primary=' + s.primary + ':active=' + s.user?.active));
    const p = await promise({ customerId: c.id, amount: 10, promisedDate: addDays(3) });
    log('L03 promise default POC', p.status, p.json?.message || brief(p.json));
    const p2 = await promise({ customerId: c.id, amount: 10, promisedDate: addDays(3), collectionPocUserId: x.id });
    log('L03 explicit same inactive POC', p2.status, p2.json?.message);
  }
  require('fs').writeFileSync(__dirname + '/api-out-' + (process.argv[2] || '1') + '.json', JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
