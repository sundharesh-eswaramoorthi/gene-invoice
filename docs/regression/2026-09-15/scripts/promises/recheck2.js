const fx = require('./fx'); const { rt, addDays } = fx;
const q = (filters, extra = '') => '/api/promises?size=50' + filters.map((f) => '&filter=' + encodeURIComponent(f)).join('') + extra;
(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const collT = await rt.login(coll.username, coll.password);
  const mk = async () => { const c = await rt.createCustomer(adm, 'promises'); await fx.addPoc(c.id, coll.id, true); return c; };
  // R04
  { const c = await mk(); const a = await fx.invoice(c.id, 100); const b = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 200, promisedDate: addDays(6), invoiceIds: [a.id, b.id] })).json;
    await rt.api('POST', `/api/invoices/${b.id}/cancel`, { token: adm });
    const u = await rt.api('PUT', `/api/promises/${p.id}`, { token: adm, body: { amount: 200, promisedDate: addDays(9), invoiceIds: [a.id, b.id], collectionPocUserId: coll.id } });
    console.log('R04 PUT same invoiceIds after invoice cancel ->', u.status, u.json?.message); }
  // M03
  { const c = await mk(); const inv = await fx.invoice(c.id, 100);
    await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] });
    await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6), invoiceIds: [inv.id] });
    await fx.pay(c.id, 100, { invoiceIds: [inv.id] });
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${c.id}`, { token: adm })).json;
    console.log('M03 same-invoice overlap summary', JSON.stringify(s)); }
  // M04
  { const c = await mk(); await fx.invoice(c.id, 500);
    await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) });
    await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(6) });
    await fx.pay(c.id, 100);
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${c.id}`, { token: adm })).json;
    console.log('M04 general overlap summary', JSON.stringify(s)); }
  // CU03
  { const c = await mk(); const ct = await rt.login(c.username, c.password);
    await fx.promise({ customerId: c.id, amount: 10, promisedDate: addDays(6) });
    const a = await rt.api('GET', q([`collectionPocUserId:eq:${coll.id}`]), { token: ct });
    const b = await rt.api('GET', q([`collectionPocUserId:eq:1`]), { token: ct });
    const s = await rt.api('GET', '/api/promises?size=10&sort=collectionPocName,desc', { token: ct });
    const nm = await rt.api('GET', q([`collectionPocName:contains:${coll.username.slice(0, 12).toUpperCase()}`]), { token: ct });
    console.log('CU03 customer filter own POC', a.status, a.json?.totalElements, '| other', b.status, b.json?.totalElements, '| sort', s.status, '| name contains', nm.status, nm.json?.totalElements); }
  // L01
  { const c = await mk(); const p = (await fx.promise({ customerId: c.id, amount: 500, promisedDate: addDays(-2) })).json;
    await fx.invoice(c.id, 300); const g = await fx.getPromise(p.id);
    const n = (await fx.notificationsFor(collT)).filter((x) => x.type === 'PROMISE_BROKEN' && x.link === `/promises/${p.id}`);
    console.log('L01 general kept past date ->', p.status, 'after new invoice ->', g.json.status, 'notifs', n.length); }
  // L02
  { const c = await mk(); const older = await fx.invoice(c.id, 100); const newer = await fx.invoice(c.id, 100);
    const p = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(5), invoiceIds: [newer.id] })).json;
    const pay = await fx.pay(c.id, 100, { promiseIds: [p.id] }); const g = await fx.getPromise(p.id);
    console.log('L02 alloc', JSON.stringify(pay.json.invoices.map(i => i.invoiceNumber + ':' + i.allocatedAmount)), 'promise covers', newer.invoiceNumber, '->', g.json.status, 'links', JSON.stringify(g.json.payments.map(x => x.id)), 'fulfilled', g.json.fulfilledAmount); }
  // L03
  { const x = await rt.createStaff(adm, 'COLLECTION_POC', 'promises'); const c = await rt.createCustomer(adm, 'promises'); await fx.addPoc(c.id, x.id, true);
    const d = await rt.api('DELETE', `/api/users/${x.id}`, { token: adm });
    const p = await fx.promise({ customerId: c.id, amount: 10, promisedDate: addDays(3) });
    console.log('L03 delete', d.text, '-> promise', p.status, 'poc', p.json?.collectionPoc?.username, 'active', p.json?.collectionPoc?.active); }
})();
