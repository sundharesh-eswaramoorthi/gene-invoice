const fs = require('fs'); const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
(async () => {
  const admin = await rt.adminToken();
  const C1 = await rt.login(st.c1.username, rt.PASSWORD); const A = await rt.login(st.A.username, rt.PASSWORD); const S1 = await rt.login(st.s1.username, rt.PASSWORD);
  const d = new Date(Date.now() + 5 * 86400000).toISOString().slice(0, 10);
  const p = await rt.api('POST', '/api/promises', { token: C1, body: { customerId: st.A.id, amount: 5, promisedDate: d, notes: 'vps by c1' } });
  const ov = await rt.api('POST', `/api/promises/${p.json.id}/override`, { token: C1, body: { status: 'KEPT', reason: 'vps' } });
  const seen = await rt.api('GET', `/api/promises/${p.json.id}`, { token: A });
  const list = await rt.api('GET', `/api/promises?filter=${encodeURIComponent('id:eq:' + p.json.id)}`, { token: A });
  const y = (await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.Y.id, salesPocUserId: st.s2.id, notes: 'Y4', items: [{ productId: st.prod.id, quantity: 1, unitPrice: 100 }] } })).json;
  const bulk = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'CANCEL', ids: [y.id] } });
  const yAfterBulk = (await rt.api('GET', `/api/invoices/${y.id}`, { token: admin })).json.status;
  const single = await rt.api('POST', `/api/invoices/${y.id}/cancel`, { token: S1 });
  const out = { c1Id: st.c1.id, create: p.status, override: ov.status, customerView: { collectionPoc: seen.json.collectionPoc, createdByUserId: seen.json.createdByUserId, overriddenByUserId: seen.json.overriddenByUserId }, customerList: list.json.content.map((r) => ({ collectionPoc: r.collectionPoc, createdByUserId: r.createdByUserId, overriddenByUserId: r.overriddenByUserId })), ps029: { bulkCancel: bulk.json, statusAfterBulk: yAfterBulk, singleCancel: single.status } };
  console.log(JSON.stringify(out, null, 1));
  fs.writeFileSync(path.join(__dirname, 'probe-result.json'), JSON.stringify(out, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
