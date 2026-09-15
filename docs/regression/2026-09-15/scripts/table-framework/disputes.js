const fs = require('fs');
const rt = require('../lib.js');
const S = require('./state.json');
(async () => {
  const tok = await rt.login(S.customers[0].username, S.customers[0].password);
  const Ainv = S.invoices.filter((i) => i.customerId === S.A);
  S.disputes = [];
  for (const [i, reason] of [[1, 'Wrong total: see, "PO 12"'], [4, 'dup'], [9, "' OR 1=1 --"]]) {
    const r = await rt.api('POST', '/api/disputes', { token: tok, body: { targetType: 'INVOICE', targetId: Ainv[i].id, reason } });
    console.log(r.status, r.text.slice(0, 160));
    if (r.status < 300) S.disputes.push(r.json);
  }
  fs.writeFileSync(__dirname + '/state.json', JSON.stringify(S, null, 2));
  const a = await rt.adminToken();
  const p = await rt.api('GET', '/api/products?size=50&filter=' + encodeURIComponent('name:contains:' + S.P), { token: a });
  console.log('product keys', Object.keys(p.json.content[0]));
  const u = await rt.api('GET', '/api/users?size=10&filter=' + encodeURIComponent('username:contains:' + S.P), { token: a });
  console.log('user keys', Object.keys(u.json.content[0]), u.json.totalElements);
  const n = await rt.api('GET', '/api/notifications?size=50', { token: await rt.login(S.coll.username, S.coll.password) });
  console.log('coll notifications', n.status, n.json && n.json.totalElements);
})();
