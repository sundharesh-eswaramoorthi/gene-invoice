// Second, independent run of every API failure (README: re-run once before recording).
// AUR-009 is corrected: valid list params and a complete customer body.
const rt = require('../lib.js');
const snip = (r, n = 200) => `${r.status} ${(r.text || '').slice(0, n)}`;
const log = (...a) => console.log(...a);

(async () => {
  const admin = await rt.adminToken();
  const cashierRole = await rt.roleId(admin, 'CASHIER');

  // AUR-009 via PUT active:false
  for (const via of ['PUT', 'DELETE-POC']) {
    const u = await rt.createStaff(admin, via === 'PUT' ? 'CASHIER' : 'SALES_POC', 'vaur');
    const tok = await rt.login(u.username, u.password);
    if (via === 'PUT') {
      const p = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
      log('AUR-009 [PUT] deactivate', p.status, 'active=', p.json?.active);
    } else {
      // Make the SALES_POC a POC on a customer so DELETE deactivates instead of deleting.
      const c = await rt.createCustomer(admin, 'vaur');
      const add = await rt.api('POST', `/api/customers/${c.id}/pocs`, { token: admin, body: { userId: u.id, pocType: 'SALES', primary: true } });
      log('AUR-009 [DELETE] attach POC', snip(add, 120));
      const d = await rt.api('DELETE', `/api/users/${u.id}`, { token: admin });
      log('AUR-009 [DELETE] delete ->', snip(d, 160));
    }
    const me = await rt.api('GET', '/api/auth/me', { token: tok });
    log(`AUR-009 [${via}] old token /me`, me.status, me.json?.username);
    const inv = await rt.api('GET', '/api/invoices', { token: tok });
    log(`AUR-009 [${via}] old token GET /api/invoices`, inv.status, 'total=', inv.json?.totalElements ?? inv.json?.total);
    const cu = await rt.api('GET', '/api/customers', { token: tok });
    log(`AUR-009 [${via}] old token GET /api/customers`, cu.status);
    if (via === 'PUT') {
      const n = rt.uniq('vaurc');
      const w = await rt.api('POST', '/api/customers', { token: tok, body: { name: n, phone: '555-0101', email: `${n}@rt.local`, address: '1 V Way', username: n, password: rt.PASSWORD } });
      log('AUR-009 [PUT] old token POST /api/customers (write)', snip(w, 140));
      const cp = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: u.password, newPassword: 'NewPassw0rd!' } });
      log('AUR-009 [PUT] old token change-password', snip(cp, 80));
    }
    const lg = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: u.password } });
    log(`AUR-009 [${via}] fresh login`, snip(lg, 120));
  }

  // AUR-016
  const a = await rt.createStaff(admin, 'CASHIER', 'vaur');
  const b = await rt.createStaff(admin, 'CASHIER', 'vaur');
  log('AUR-016 POST dup email', snip(await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vaur'), email: a.email, password: rt.PASSWORD, roleId: cashierRole } }), 150));
  log('AUR-016 PUT dup email', snip(await rt.api('PUT', `/api/users/${b.id}`, { token: admin, body: { email: a.email } }), 150));

  // AUR-017
  log('AUR-017 POST email=""', snip(await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vaur'), email: '', password: rt.PASSWORD, roleId: cashierRole } }), 190));
  const ne = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('vaur'), password: rt.PASSWORD, roleId: cashierRole } });
  log('AUR-017 PUT {email:""} on no-email user', snip(await rt.api('PUT', `/api/users/${ne.json.id}`, { token: admin, body: { email: '', active: false } }), 160));
  log('AUR-017 no-email user after', JSON.stringify((await rt.api('GET', `/api/users/${ne.json.id}`, { token: admin })).json));

  // AUR-018
  log('AUR-018 username 90 chars', snip(await rt.api('POST', '/api/users', { token: admin, body: { username: 'vaur' + 'y'.repeat(80) + Math.random().toString(36).slice(2, 8), password: rt.PASSWORD, roleId: cashierRole } }), 150));

  // AUR-021
  const p = await rt.createStaff(admin, 'CASHIER', 'vaur');
  const pp = await rt.api('PUT', `/api/users/${p.id}`, { token: admin, body: { password: 'z' } });
  const pl = await rt.api('POST', '/api/auth/login', { body: { username: p.username, password: 'z' } });
  log('AUR-021 PUT password=z', pp.status, 'login with z', pl.status);

  // AUR-028
  const k = await rt.createStaff(admin, 'CASHIER', 'vaur');
  log('AUR-028 bulk ACTIVATE->DEACTIVATE [own, 55555555, 987654321]', snip(await rt.api('POST', '/api/users/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [k.id, 55555555, 987654321] } }), 200));
  const pr = await rt.api('POST', '/api/products/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [987654321] } });
  log('AUR-028 contrast products bulk unknown id', snip(pr, 200));

  // AUR-031
  const tag = rt.uniq('vaurs').replace(/-/g, '');
  for (const s of ['m', 'z', 'a']) await rt.api('POST', '/api/users', { token: admin, body: { username: tag + s, email: `${tag}${s}@rt.local`, password: rt.PASSWORD, roleId: cashierRole } });
  const lst = await rt.api('GET', `/api/users?sort=username,asc&filter=${encodeURIComponent('username:contains:' + tag)}`, { token: admin });
  const exp = await rt.api('POST', '/api/users/export', { token: admin, body: { selectAllMatchingFilter: true, filters: [`username:contains:${tag}`], sort: 'username,asc' } });
  const expSel = await rt.api('POST', '/api/users/export', { token: admin, body: { ids: (lst.json?.content || []).map((x) => x.id), sort: 'username,asc', filters: [`username:contains:${tag}`] } });
  log('AUR-031 list asc', (lst.json?.content || []).map((x) => x.username.slice(-1)).join(''),
    '| export all asc', exp.text.trim().split(/\r?\n/).slice(1).map((l) => l.split(',')[1].slice(-1)).join(''),
    '| export ids asc', expSel.text.trim().split(/\r?\n/).slice(1).map((l) => l.split(',')[1].slice(-1)).join(''));

  // AUR-032
  const cashier = await rt.cashierToken();
  const ue = await rt.api('POST', '/api/users/export', { token: cashier, body: { selectAllMatchingFilter: true } });
  const re = await rt.api('POST', '/api/roles/export', { token: cashier, body: { selectAllMatchingFilter: true } });
  log('AUR-032 cashier users export', ue.status, 'rows', ue.text.trim().split(/\r?\n/).length - 1, '| roles export', re.status, 'rows', re.text.trim().split(/\r?\n/).length - 1);
  const fil = await rt.api('POST', '/api/users/export', { token: cashier, body: { selectAllMatchingFilter: true, filters: ['username:eq:admin'] } });
  log('AUR-032 cashier export filtered to admin', fil.status, JSON.stringify(fil.text.trim().split(/\r?\n/)[1]));

  // AUR-038
  const rn = rt.uniq('VAUR-R2');
  await rt.api('POST', '/api/roles', { token: admin, body: { name: rn, privileges: [] } });
  log('AUR-038 dup custom', snip(await rt.api('POST', '/api/roles', { token: admin, body: { name: rn, privileges: [] } }), 170));
  log('AUR-038 name CASHIER', snip(await rt.api('POST', '/api/roles', { token: admin, body: { name: 'CASHIER', privileges: [] } }), 170));

  // AUR-039
  const role = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('VAUR-U2'), privileges: ['INVOICE_VIEW'] } });
  const un = rt.uniq('vaur');
  await rt.api('POST', '/api/users', { token: admin, body: { username: un, email: `${un}@rt.local`, password: rt.PASSWORD, roleId: role.json.id } });
  const del = await rt.api('DELETE', `/api/roles/${role.json.id}`, { token: admin });
  log('AUR-039 delete in-use role', snip(del, 170), '| GET after', (await rt.api('GET', `/api/roles/${role.json.id}`, { token: admin })).status);

  // AUR-041
  const d41 = await rt.api('DELETE', '/api/roles/77777777', { token: admin });
  log('AUR-041 DELETE /api/roles/77777777', d41.status, JSON.stringify(d41.text));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
