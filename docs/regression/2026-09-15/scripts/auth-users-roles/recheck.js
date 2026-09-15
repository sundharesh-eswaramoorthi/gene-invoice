// Re-runs each failing API case once, independently of api.js.
const rt = require('../lib.js');
const P = rt.PASSWORD;
const s = (r) => `${r.status} ${(r.text || '').slice(0, 200).replace(/\n/g, ' ')}`;
(async () => {
  const admin = await rt.adminToken();
  const viewerRole = await rt.roleId(admin, 'VIEWER');
  // U01 ordering
  const l = await rt.api('GET', '/api/users?size=50', { token: admin });
  console.log('U01 order:', l.json.content.map((x) => x.username).slice(0, 50).join(' | '));
  // A15 deactivated JWT
  const u = await rt.createStaff(admin, 'CASHIER', 'aurjwt2');
  const tok = await rt.login(u.username, P);
  await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
  const me = await rt.api('GET', '/api/auth/me', { token: tok });
  const inv = await rt.api('GET', '/api/invoices', { token: tok });
  const cust = await rt.api('POST', '/api/customers', { token: tok, body: { name: rt.uniq('aurjwtc'), phone: '555', email: `${rt.uniq('aurjwtc')}@rt.local`, address: '1 Way', username: rt.uniq('aurjwtcl'), password: P } });
  console.log('A15 deactivated token: /me', me.status, 'invoices', inv.status, 'POST customer (write)', s(cust));
  // U08/U09/U10
  const a = await rt.createStaff(admin, 'VIEWER', 'aurrc');
  const d1 = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurrc'), email: a.email, password: P, roleId: viewerRole } });
  const b = await rt.createStaff(admin, 'VIEWER', 'aurrc');
  const d2 = await rt.api('PUT', `/api/users/${b.id}`, { token: admin, body: { email: a.email } });
  const d3 = await rt.api('POST', '/api/users', { token: admin, body: { username: 'y'.repeat(81), password: P, roleId: viewerRole } });
  console.log('U08', s(d1)); console.log('U09', s(d2)); console.log('U10', s(d3));
  // U14
  const pw = await rt.api('PUT', `/api/users/${b.id}`, { token: admin, body: { password: 'a' } });
  const lg = await rt.api('POST', '/api/auth/login', { body: { username: b.username, password: 'a' } });
  console.log('U14 PUT password=a', pw.status, 'then login with "a"', lg.status);
  // U21 (admin as actor; ids are only my own users)
  const r21 = await rt.api('POST', '/api/users/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [b.id, 987654321] } });
  console.log('U21', s(r21));
  // U25 / R15
  const ct = await rt.cashierToken();
  const e1 = await rt.api('POST', '/api/users/export', { token: ct, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
  const e2 = await rt.api('POST', '/api/roles/export', { token: ct, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
  const sp = await rt.createStaff(admin, 'SALES_POC', 'aurrc');
  const st = await rt.login(sp.username, P);
  const e3 = await rt.api('POST', '/api/users/export', { token: st, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
  console.log('U25 cashier users export', e1.status, 'rows', e1.text.trim().split('\n').length - 1, '| R15 cashier roles export', e2.status, e2.text.trim().split('\n').length - 1, '| SALES_POC users export', e3.status, e3.text.trim().split('\n').length - 1);
  // export sort order
  const ex = await rt.api('POST', '/api/users/export', { token: admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: ['username:contains:aurrc'], sort: 'username,desc' } });
  const lst = await rt.api('GET', '/api/users?size=50&filter=username:contains:aurrc&sort=username,desc', { token: admin });
  console.log('export order  :', ex.text.trim().split('\n').slice(1).map((x) => x.split(',')[1]).join(' '));
  console.log('list desc     :', lst.json.content.map((x) => x.username).join(' '));
  // R07/R09/R11/R13
  const rn = rt.uniq('AUR_RC');
  const rr = await rt.api('POST', '/api/roles', { token: admin, body: { name: rn, privileges: [] } });
  const r7 = await rt.api('POST', '/api/roles', { token: admin, body: { name: rn, privileges: [] } });
  const r9 = await rt.api('PUT', `/api/roles/${rr.json.id}`, { token: admin, body: { name: 'VIEWER', privileges: [] } });
  const uu = await rt.createStaff(admin, 'VIEWER', 'aurrc');
  await rt.api('PUT', `/api/users/${uu.id}`, { token: admin, body: { roleId: rr.json.id } });
  const r11 = await rt.api('DELETE', `/api/roles/${rr.json.id}`, { token: admin });
  const r13 = await rt.api('DELETE', `/api/roles/88888888`, { token: admin });
  console.log('R07', s(r7)); console.log('R09', s(r9)); console.log('R11', s(r11)); console.log('R13', s(r13));
  await rt.api('PUT', `/api/users/${uu.id}`, { token: admin, body: { roleId: viewerRole } });
  console.log('cleanup', (await rt.api('DELETE', `/api/roles/${rr.json.id}`, { token: admin })).status);
})().catch((e) => { console.error(e); process.exit(1); });
