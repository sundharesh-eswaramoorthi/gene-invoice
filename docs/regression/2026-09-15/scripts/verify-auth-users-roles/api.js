// Independent verification of the auth-users-roles API failures. Own data only (prefix "vaur").
const rt = require('../lib.js');

const out = {};
const log = (id, ...a) => { console.log(id, ...a); (out[id] ||= []).push(a.map(String).join(' ')); };
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 260)}`;

async function mkUser(admin, body) {
  return rt.api('POST', '/api/users', { token: admin, body });
}

(async () => {
  const admin = await rt.adminToken();
  const cashierRole = await rt.roleId(admin, 'CASHIER');
  const viewerRole = await rt.roleId(admin, 'VIEWER');

  // ---- AUR-009 deactivated user keeps access via existing JWT ----
  {
    const u = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const tok = await rt.login(u.username, u.password);
    const pre = await rt.api('GET', '/api/auth/me', { token: tok });
    log('AUR-009', 'before deactivation /me', pre.status);
    const put = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
    log('AUR-009', 'admin PUT active:false', put.status, 'active=', put.json?.active);
    const me = await rt.api('GET', '/api/auth/me', { token: tok });
    log('AUR-009', 'old token /me', snip(me));
    const inv = await rt.api('GET', '/api/invoices?size=1', { token: tok });
    log('AUR-009', 'old token GET /api/invoices', inv.status);
    const cname = rt.uniq('vaur-cust');
    const cust = await rt.api('POST', '/api/customers', { token: tok, body: { name: cname, phone: '555-0101', email: `${cname}@rt.local`, address: '1 V Way' } });
    log('AUR-009', 'old token POST /api/customers', snip(cust));
    const relog = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: u.password } });
    log('AUR-009', 'fresh login after deactivation', snip(relog));
    // DELETE path on a hard-deletable user: old token after hard delete
    const u2 = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const tok2 = await rt.login(u2.username, u2.password);
    const del = await rt.api('DELETE', `/api/users/${u2.id}`, { token: admin });
    log('AUR-009', 'hard delete other user', snip(del));
    const me2 = await rt.api('GET', '/api/auth/me', { token: tok2 });
    log('AUR-009', 'hard-deleted user old token /me', me2.status);
  }

  // ---- AUR-016 duplicate email ----
  {
    const a = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const b = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const n = rt.uniq('vaur');
    const dupCreate = await mkUser(admin, { username: n, email: a.email, fullName: 'x', password: rt.PASSWORD, roleId: cashierRole });
    log('AUR-016', 'POST dup email', snip(dupCreate));
    const dupPut = await rt.api('PUT', `/api/users/${b.id}`, { token: admin, body: { email: a.email } });
    log('AUR-016', 'PUT dup email', snip(dupPut));
    const bAfter = await rt.api('GET', `/api/users/${b.id}`, { token: admin });
    log('AUR-016', 'b email after failed PUT', bAfter.json?.email);
    const dupUser = await mkUser(admin, { username: a.username, email: rt.uniq('x') + '@rt.local', password: rt.PASSWORD, roleId: cashierRole });
    log('AUR-016', 'contrast: dup username', snip(dupUser));
  }

  // ---- AUR-017 blank email ----
  {
    const n1 = rt.uniq('vaur'), n2 = rt.uniq('vaur');
    const nullA = await mkUser(admin, { username: n1, password: rt.PASSWORD, roleId: cashierRole });
    const nullB = await mkUser(admin, { username: n2, password: rt.PASSWORD, roleId: cashierRole });
    log('AUR-017', 'create no-email #1', nullA.status, 'email=', JSON.stringify(nullA.json?.email), '#2', nullB.status, 'email=', JSON.stringify(nullB.json?.email));
    const blank1 = await mkUser(admin, { username: rt.uniq('vaur'), email: '', password: rt.PASSWORD, roleId: cashierRole });
    log('AUR-017', 'create email="" #1', snip(blank1));
    const blank2 = await mkUser(admin, { username: rt.uniq('vaur'), email: '', password: rt.PASSWORD, roleId: cashierRole });
    log('AUR-017', 'create email="" #2', snip(blank2));
    // Simulate the UI edit form: it always sends email: _email.text.trim() == '' for a no-email user
    const putBlank = await rt.api('PUT', `/api/users/${nullA.json.id}`, { token: admin, body: { email: '', fullName: 'Edited', roleId: cashierRole, active: false } });
    log('AUR-017', 'PUT no-email user with UI body {email:"",active:false}', snip(putBlank));
    const after = await rt.api('GET', `/api/users/${nullA.json.id}`, { token: admin });
    log('AUR-017', 'after: active=', after.json?.active, 'fullName=', after.json?.fullName, 'email=', JSON.stringify(after.json?.email));
    const putNoEmail = await rt.api('PUT', `/api/users/${nullA.json.id}`, { token: admin, body: { fullName: 'Edited2' } });
    log('AUR-017', 'contrast: PUT without email field', putNoEmail.status);
    out.noEmailUser = { id: nullA.json.id, username: n1 };
  }

  // ---- AUR-018 username > 80 ----
  {
    for (const len of [80, 81]) {
      const name = ('vaur' + 'x'.repeat(200)).slice(0, len - 6) + Math.random().toString(36).slice(2, 8);
      const r = await mkUser(admin, { username: name, password: rt.PASSWORD, roleId: cashierRole, email: rt.uniq('v') + '@rt.local' });
      log('AUR-018', `username length ${name.length}`, snip(r));
    }
  }

  // ---- AUR-021 1-char password via PUT ----
  {
    const u = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const put = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { password: 'a' } });
    log('AUR-021', 'PUT password=a', put.status);
    const l = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: 'a' } });
    log('AUR-021', 'login with "a"', l.status);
    const c = await mkUser(admin, { username: rt.uniq('vaur'), password: 'b', roleId: cashierRole, email: rt.uniq('v') + '@rt.local' });
    log('AUR-021', 'POST create with password "b"', c.status);
    const t = await rt.login(u.username, 'a');
    const cp = await rt.api('POST', '/api/auth/change-password', { token: t, body: { currentPassword: 'a', newPassword: 'c' } });
    log('AUR-021', 'contrast change-password to "c"', snip(cp));
  }

  // ---- AUR-028 unknown id dropped from bulk ----
  {
    const u = await rt.createStaff(admin, 'CASHIER', 'vaur');
    const r = await rt.api('POST', '/api/users/bulk', { token: admin, body: { action: 'DEACTIVATE', ids: [u.id, 987654321] } });
    log('AUR-028', 'bulk DEACTIVATE [own, 987654321]', snip(r));
  }

  // ---- AUR-031 export ignores sort ----
  {
    const tag = rt.uniq('vaursort').replace(/-/g, '');
    const made = [];
    for (const s of ['c', 'a', 'b']) {
      const r = await mkUser(admin, { username: `${tag}${s}`, password: rt.PASSWORD, roleId: cashierRole, email: `${tag}${s}@rt.local` });
      made.push(r.json?.username);
    }
    log('AUR-031', 'created in id order', made.join(','));
    const list = await rt.api('GET', `/api/users?size=10&sort=username,desc&filter=${encodeURIComponent('username:contains:' + tag)}`, { token: admin });
    log('AUR-031', 'list sort=username,desc', (list.json?.content || []).map((x) => x.username).join(','));
    const ex = await rt.api('POST', '/api/users/export', { token: admin, body: { selectAllMatchingFilter: true, filters: [`username:contains:${tag}`], sort: 'username,desc' } });
    const rows = (ex.text || '').trim().split(/\r?\n/).slice(1).map((l) => l.split(',')[1]);
    log('AUR-031', 'export sort=username,desc', ex.status, rows.join(','));
    const ex2 = await rt.api('POST', '/api/users/export', { token: admin, body: { selectAllMatchingFilter: true, filters: [`username:contains:${tag}`], sort: 'username,asc' } });
    log('AUR-031', 'export sort=username,asc', ex2.status, (ex2.text || '').trim().split(/\r?\n/).slice(1).map((l) => l.split(',')[1]).join(','));
  }

  // ---- AUR-032 export without USER_VIEW / ROLE_VIEW ----
  {
    const cashier = await rt.cashierToken();
    const cMe = await rt.api('GET', '/api/auth/me', { token: cashier });
    log('AUR-032', 'cashier privileges has USER_VIEW?', cMe.json?.privileges?.includes('USER_VIEW'), 'ROLE_VIEW?', cMe.json?.privileges?.includes('ROLE_VIEW'), 'EXPORT_DATA?', cMe.json?.privileges?.includes('EXPORT_DATA'));
    const gl = await rt.api('GET', '/api/users', { token: cashier });
    log('AUR-032', 'cashier GET /api/users', gl.status);
    const gr = await rt.api('GET', '/api/roles', { token: cashier });
    log('AUR-032', 'cashier GET /api/roles', gr.status);
    const ue = await rt.api('POST', '/api/users/export', { token: cashier, body: { selectAllMatchingFilter: true } });
    const ulines = (ue.text || '').trim().split(/\r?\n/);
    log('AUR-032', 'cashier POST /api/users/export', ue.status, ue.headers['content-type'], 'rows', ulines.length - 1, 'hasAdminEmail', /admin@geneinvoice\.local/.test(ue.text), 'header', ulines[0]);
    const re = await rt.api('POST', '/api/roles/export', { token: cashier, body: { selectAllMatchingFilter: true } });
    const rlines = (re.text || '').trim().split(/\r?\n/);
    log('AUR-032', 'cashier POST /api/roles/export', re.status, 'rows', rlines.length - 1, 'sample', rlines.find((l) => l.includes('ADMIN'))?.slice(0, 120));
    const sp = await rt.createStaff(admin, 'SALES_POC', 'vaur');
    const spTok = await rt.login(sp.username, sp.password);
    const spl = await rt.api('GET', '/api/users', { token: spTok });
    const spe = await rt.api('POST', '/api/users/export', { token: spTok, body: { selectAllMatchingFilter: true } });
    log('AUR-032', 'SALES_POC GET /api/users', spl.status, 'POST export', spe.status, 'rows', (spe.text || '').trim().split(/\r?\n/).length - 1);
    const vw = await mkUser(admin, { username: rt.uniq('vaur'), email: rt.uniq('v') + '@rt.local', password: rt.PASSWORD, roleId: viewerRole });
    const vwTok = await rt.login(vw.json.username, rt.PASSWORD);
    const vwe = await rt.api('POST', '/api/users/export', { token: vwTok, body: { selectAllMatchingFilter: true } });
    log('AUR-032', 'contrast VIEWER (no EXPORT_DATA) export', vwe.status);
  }

  // ---- AUR-038 duplicate role name ----
  {
    const name = rt.uniq('VAUR-ROLE');
    const r1 = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'v', privileges: ['INVOICE_VIEW'] } });
    log('AUR-038', 'create custom role', r1.status);
    const r2 = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'dup', privileges: [] } });
    log('AUR-038', 'POST dup custom name', snip(r2));
    const r3 = await rt.api('POST', '/api/roles', { token: admin, body: { name: 'ADMIN', description: 'dup', privileges: [] } });
    log('AUR-038', 'POST name ADMIN', snip(r3));
    const r4 = await rt.api('PUT', `/api/roles/${r1.json.id}`, { token: admin, body: { name: 'VIEWER', description: 'v', privileges: ['INVOICE_VIEW'] } });
    log('AUR-038', 'PUT own role -> VIEWER', snip(r4));
    const own = await rt.api('GET', `/api/roles/${r1.json.id}`, { token: admin });
    log('AUR-038', 'own role name after', own.json?.name);
    out.ownRole = r1.json;
  }

  // ---- AUR-039 delete role in use ----
  {
    const name = rt.uniq('VAUR-INUSE');
    const role = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'v', privileges: ['INVOICE_VIEW', 'CUSTOMER_VIEW'] } });
    const un = rt.uniq('vaur');
    const u = await mkUser(admin, { username: un, email: `${un}@rt.local`, password: rt.PASSWORD, roleId: role.json.id });
    log('AUR-039', 'user on custom role', u.status, u.json?.role);
    const d = await rt.api('DELETE', `/api/roles/${role.json.id}`, { token: admin });
    log('AUR-039', 'DELETE role in use', snip(d));
    const g = await rt.api('GET', `/api/roles/${role.json.id}`, { token: admin });
    const t = await rt.login(un, rt.PASSWORD);
    const me = await rt.api('GET', '/api/auth/me', { token: t });
    log('AUR-039', 'role after', g.status, g.json?.name, 'user /me', me.status, me.json?.role);
    // contrast: unused role deletes fine
    const free = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('VAUR-FREE'), privileges: [] } });
    const df = await rt.api('DELETE', `/api/roles/${free.json.id}`, { token: admin });
    const gf = await rt.api('GET', `/api/roles/${free.json.id}`, { token: admin });
    log('AUR-039', 'contrast unused role delete', df.status, 'then GET', gf.status);
  }

  // ---- AUR-041 delete non-existent role ----
  {
    for (const id of [99999999, 88888888]) {
      const d = await rt.api('DELETE', `/api/roles/${id}`, { token: admin });
      log('AUR-041', `DELETE /api/roles/${id}`, `${d.status} body=${JSON.stringify(d.text)}`);
    }
    const g = await rt.api('GET', '/api/roles/99999999', { token: admin });
    const du = await rt.api('DELETE', '/api/users/99999999', { token: admin });
    log('AUR-041', 'contrast GET role 99999999', g.status, 'DELETE user 99999999', du.status);
  }

  require('fs').writeFileSync(__dirname + '/api-out.json', JSON.stringify(out, null, 2));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
