const rt = require('../lib.js');
const S = require('./state.json');
(async () => {
  const admin = await rt.adminToken();
  const coll = await rt.login(S.coll.username, S.coll.password);
  const ma = await rt.login(S.myAdmin.username, S.myAdmin.password);
  const pr = S.products;
  // TF-091 rerun: nonexistent + out-of-filter ids
  const r1 = await rt.api('POST', '/api/products/bulk', { token: admin, body: { action: 'ACTIVATE', ids: [pr[1].id, 999999999], filters: [`name:contains:${S.P}-prod-01`] } });
  console.log('TF-091 rerun', r1.status, r1.text);
  // TF-093 rerun
  const nb = await fetch(`${rt.API}/api/products/bulk`, { method: 'POST', headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' } });
  const mj = await fetch(`${rt.API}/api/products/bulk`, { method: 'POST', headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' }, body: '{not json' });
  const ex = await fetch(`${rt.API}/api/invoices/export`, { method: 'POST', headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' }, body: '{"ids":"abc"}' });
  console.log('TF-093 rerun no-body', nb.status, (await nb.text()).slice(0, 160), '| malformed', mj.status, (await mj.text()).slice(0, 160), '| export ids:"abc"', ex.status, (await ex.text()).slice(0, 120));
  // TF-095 rerun: paid + cancelled only
  const Ainv = S.invoices.filter((i) => i.customerId === S.A);
  const r3 = await rt.api('POST', '/api/invoices/bulk', { token: admin, body: { action: 'CANCEL', ids: [Ainv[0].id, Ainv[7].id], filters: [`customerId:eq:${S.A}`] } });
  console.log('TF-095 rerun', r3.status, r3.text);
  // TF-098 corrected: foreign notification owned by my own ADMIN user
  const mine = await rt.api('GET', '/api/notifications?size=10', { token: ma });
  const foreign = mine.json.content[0];
  const before = (await rt.api('GET', `/api/notifications?size=50&filter=${encodeURIComponent('read:eq:false')}`, { token: coll })).json.totalElements;
  const cl = (await rt.api('GET', '/api/notifications?size=10&filter=read:eq:true', { token: coll })).json.content.map((n) => n.id).slice(0, 2);
  const r7 = await rt.api('POST', '/api/notifications/bulk', { token: coll, body: { action: 'MARK_READ', ids: [...(await rt.api('GET', '/api/notifications?size=50&filter=read:eq:false', { token: coll })).json.content.map((n) => n.id), foreign.id], filters: [] } });
  const after = (await rt.api('GET', '/api/notifications/unread-count', { token: coll })).json.count;
  const f2 = (await rt.api('GET', `/api/notifications?size=50`, { token: ma })).json.content.find((n) => n.id === foreign.id);
  console.log('TF-098 corrected: foreign', foreign.id, 'read before', foreign.read, '-> after', f2.read, '| coll unread before', before, 'after', after, '|', r7.status, r7.text.slice(0, 300));
})().catch((e) => { console.error(e); process.exit(1); });
