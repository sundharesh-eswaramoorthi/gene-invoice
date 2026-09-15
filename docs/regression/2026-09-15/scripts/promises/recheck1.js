const fx = require('./fx'); const { rt, addDays } = fx;
(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const c = await rt.createCustomer(adm, 'promises'); await fx.addPoc(c.id, coll.id, true);
  const inv = await fx.invoice(c.id, 100);
  const p = (await fx.promise({ customerId: c.id, amount: 100, promisedDate: addDays(4), invoiceIds: [inv.id] })).json;
  const foo = await rt.api('POST', `/api/promises/${p.id}/override`, { token: adm, body: { status: 'FOO', reason: 'x' } });
  console.log('override status FOO ->', foo.status, foo.text.slice(0, 300));
  const pay = await fx.pay(c.id, 100, { invoiceIds: [inv.id] });
  const can = await rt.api('POST', `/api/promises/${p.id}/cancel`, { token: adm, body: { reason: 'error' } });
  console.log('cancel ->', can.status, can.json.status, 'links', can.json.payments.length);
  const o = await rt.api('POST', `/api/promises/${p.id}/override`, { token: adm, body: { status: 'OPEN', reason: 'revive' } });
  console.log('override OPEN on cancelled ->', o.status, o.json?.status);
  const d = await rt.api('DELETE', `/api/promises/${p.id}/override`, { token: adm });
  console.log('clear override ->', d.status, d.json?.status, 'links', JSON.stringify(d.json?.payments?.map(x => x.id)), 'fulfilled', d.json?.fulfilledAmount, 'payment', pay.json.id);
  const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${p.id}`, { token: adm });
  console.log('audit', JSON.stringify((au.json || []).map(x => x.action)));
})();
