// API regression tests: auth, session, users, roles, privileges.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const results = [];
function rec(id, title, pass, detail) {
  results.push({ id, title, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title}\n     ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
}
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 260)}`;
const P = rt.PASSWORD;

(async () => {
  const admin = await rt.adminToken();
  const roles = (await rt.api('GET', '/api/roles?size=50', { token: admin })).json.content;
  const roleByName = Object.fromEntries(roles.map((r) => [r.name, r]));

  // ---------------------------------------------------------------- AUTH
  const me1 = await rt.createStaff(admin, 'VIEWER', 'aur');
  {
    const r = await rt.api('POST', '/api/auth/login', { body: { username: me1.username, password: P } });
    const u = r.json?.user || {};
    rec('API-A01', 'login with valid credentials', r.status === 200 && typeof r.json.token === 'string'
      && r.json.expiresInMs === 86400000 && u.username === me1.username && u.role === 'VIEWER'
      && Array.isArray(u.privileges) && u.privileges.includes('INVOICE_VIEW') && u.customerId === null,
      `${r.status} expiresInMs=${r.json?.expiresInMs} user=${JSON.stringify(u).slice(0, 200)}`);
  }
  {
    const r = await rt.api('POST', '/api/auth/login', { body: { username: me1.username, password: 'wrong-pass' } });
    rec('API-A02', 'login wrong password -> 401', r.status === 401 && r.json?.message === 'Invalid username or password', snip(r));
    const r2 = await rt.api('POST', '/api/auth/login', { body: { username: rt.uniq('nobody'), password: 'whatever1' } });
    rec('API-A03', 'login unknown user -> 401 same message', r2.status === 401 && r2.json?.message === r.json?.message, snip(r2));
    const r3 = await rt.api('POST', '/api/auth/login', { body: { username: '', password: '' } });
    const r4 = await rt.api('POST', '/api/auth/login', { body: {} });
    rec('API-A04', 'login blank/missing fields -> 400 with fieldErrors', r3.status === 400 && r4.status === 400
      && r3.json?.fieldErrors?.username && r3.json?.fieldErrors?.password, `${snip(r3)} || ${snip(r4)}`);
  }
  {
    // deactivated user cannot log in
    const u = await rt.createStaff(admin, 'VIEWER', 'aur');
    const d = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
    const r = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: P } });
    rec('API-A05', 'deactivated user cannot log in -> 401', d.status === 200 && d.json.active === false && r.status === 401, `PUT ${d.status} active=${d.json?.active}; login ${snip(r)}`);
    const a = await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: true } });
    const r2 = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: P } });
    rec('API-A06', 'reactivated user can log in again', a.status === 200 && r2.status === 200, `PUT ${a.status}; login ${r2.status}`);
  }
  {
    const tok = await rt.login(me1.username, P);
    const r = await rt.api('GET', '/api/auth/me', { token: tok });
    rec('API-A07', 'GET /api/auth/me returns caller', r.status === 200 && r.json.id === me1.id && r.json.username === me1.username
      && r.json.role === 'VIEWER' && r.json.privileges.length === roleByName.VIEWER.privileges.length, snip(r));
  }
  {
    const cases = {
      none: undefined,
      garbage: 'not.a.jwt',
      expired: rt.mintToken(me1.username, -60),
      wrongSig: rt.mintToken(me1.username, 3600).slice(0, -4) + 'AAAA',
      unknownUser: rt.mintToken(rt.uniq('ghost'), 3600),
    };
    const out = {};
    let ok = true;
    for (const [k, t] of Object.entries(cases)) {
      for (const p of ['/api/auth/me', '/api/invoices', '/api/users']) {
        const r = await rt.api('GET', p, { token: t });
        out[`${k} ${p}`] = r.status;
        if (r.status !== 401) ok = false;
      }
    }
    // Non-Bearer scheme
    const b = await rt.api('GET', '/api/invoices', { headers: { Authorization: 'Basic YWRtaW46YWRtaW4xMjM=' } });
    out['basic /api/invoices'] = b.status; if (b.status !== 401) ok = false;
    const valid = await rt.api('GET', '/api/invoices', { token: rt.mintToken(me1.username, 3600) });
    out['valid minted'] = valid.status;
    rec('API-A08', 'missing/garbage/expired/wrong-signature/unknown-user token -> 401 on protected endpoints', ok && valid.status === 200, out);
  }
  {
    const vt = await rt.login(me1.username, P);
    const cashier = await rt.cashierToken();
    const checks = [
      ['viewer GET /api/users', await rt.api('GET', '/api/users', { token: vt })],
      ['viewer POST /api/users', await rt.api('POST', '/api/users', { token: vt, body: { username: 'x', password: 'y', roleId: 1 } })],
      ['viewer GET /api/roles', await rt.api('GET', '/api/roles', { token: vt })],
      ['viewer GET /api/privileges', await rt.api('GET', '/api/privileges', { token: vt })],
      ['viewer POST /api/users/export', await rt.api('POST', '/api/users/export', { token: vt, body: { action: 'EXPORT', selectAllMatchingFilter: true } })],
      ['cashier GET /api/users', await rt.api('GET', '/api/users', { token: cashier })],
      ['cashier POST /api/roles', await rt.api('POST', '/api/roles', { token: cashier, body: { name: 'x' } })],
      ['cashier DELETE /api/users/1', await rt.api('DELETE', '/api/users/999999', { token: cashier })],
      ['cashier POST /api/users/bulk', await rt.api('POST', '/api/users/bulk', { token: cashier, body: { action: 'DEACTIVATE', ids: [999999] } })],
    ];
    const bad = checks.filter(([, r]) => r.status !== 403 || r.json?.message !== 'You do not have permission for this action');
    rec('API-A09', 'signed-in user lacking the privilege gets 403 with permission message', bad.length === 0,
      checks.map(([n, r]) => `${n}=${r.status}`).join(', ') + (bad.length ? ' | bad: ' + bad.map(([n, r]) => `${n} ${snip(r)}`).join(';') : ''));
  }
  // change password
  {
    const u = await rt.createStaff(admin, 'VIEWER', 'aurpw');
    const tok = await rt.login(u.username, P);
    const wrong = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: 'nope-nope', newPassword: 'NewPass123' } });
    rec('API-A10', 'change-password wrong current -> 400 "Current password is incorrect"', wrong.status === 400 && wrong.json?.message === 'Current password is incorrect', snip(wrong));
    const short = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: P, newPassword: 'abc' } });
    rec('API-A11', 'change-password too short -> 400', short.status === 400 && /at least 6/.test(short.json?.message || ''), snip(short));
    const same = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: P, newPassword: P } });
    rec('API-A12', 'change-password same as current -> 400', same.status === 400 && /differ/.test(same.json?.message || ''), snip(same));
    const blank = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: '', newPassword: '' } });
    const noauth = await rt.api('POST', '/api/auth/change-password', { body: { currentPassword: P, newPassword: 'NewPass123' } });
    rec('API-A13', 'change-password blank fields -> 400, no token -> 401', blank.status === 400 && noauth.status === 401, `blank ${snip(blank)} | noauth ${snip(noauth)}`);
    const ok = await rt.api('POST', '/api/auth/change-password', { token: tok, body: { currentPassword: P, newPassword: 'NewPass123' } });
    const oldL = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: P } });
    const newL = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: 'NewPass123' } });
    const stillTok = await rt.api('GET', '/api/auth/me', { token: tok });
    rec('API-A14', 'change-password success; old password rejected, new accepted', ok.status === 200 && ok.json?.status === 'ok'
      && oldL.status === 401 && newL.status === 200, `change ${snip(ok)}; old login ${oldL.status}; new login ${newL.status}; pre-change token /me ${stillTok.status}`);
  }
  // deactivated / deleted user keeps JWT access?
  {
    const u = await rt.createStaff(admin, 'CASHIER', 'aurjwt');
    const tok = await rt.login(u.username, P);
    const before = await rt.api('GET', '/api/invoices', { token: tok });
    await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { active: false } });
    const me = await rt.api('GET', '/api/auth/me', { token: tok });
    const inv = await rt.api('GET', '/api/invoices', { token: tok });
    const cust = await rt.api('POST', '/api/customers', { token: tok, body: { name: rt.uniq('aurjwtc'), phone: '1', email: `${rt.uniq('x')}@rt.local`, address: 'a' } });
    rec('API-A15', 'deactivated user existing JWT is rejected (401)', me.status === 401 && inv.status === 401,
      `before deactivation GET /api/invoices ${before.status}; after deactivation: /me ${me.status} ${JSON.stringify(me.json)?.slice(0, 120)}; GET /api/invoices ${inv.status}; POST /api/customers (write) ${cust.status}`);
    const u2 = await rt.createStaff(admin, 'VIEWER', 'aurjwt');
    const tok2 = await rt.login(u2.username, P);
    await rt.api('DELETE', `/api/users/${u2.id}`, { token: admin });
    const me2 = await rt.api('GET', '/api/auth/me', { token: tok2 });
    rec('API-A16', 'hard-deleted user existing JWT -> 401', me2.status === 401, `/me ${me2.status}`);
  }

  // ---------------------------------------------------------------- USERS
  const pfx = rt.uniq('aurl');
  const mk = async (suffix, role = 'VIEWER', extra = {}) => {
    const r = await rt.api('POST', '/api/users', { token: admin, body: {
      username: `${pfx}-${suffix}`, email: `${pfx}-${suffix}@rt.local`, fullName: `Full ${suffix}`, password: P,
      roleId: roleByName[role].id, active: true, ...extra } });
    if (r.status !== 200) throw new Error('mk ' + snip(r));
    return r.json;
  };
  const ua = await mk('a'); const ub = await mk('b', 'CASHIER'); const uc = await mk('c'); const ud = await mk('d');
  const ue = await mk('e', 'SALES_POC'); const uf = await mk('f'); const ug = await mk('g'); const uh = await mk('h'); const ui = await mk('i'); const uj = await mk('j'); const uk = await mk('k');
  {
    const r = await rt.api('GET', '/api/users', { token: admin });
    rec('API-U01', 'list users default envelope (size 20, sort username,asc, id tiebreak)', r.status === 200 && r.json.size === 20 && r.json.page === 0
      && r.json.sort === 'username,asc' && Array.isArray(r.json.content) && r.json.totalElements >= 13
      && r.json.content.every((x, i, a) => i === 0 || a[i - 1].username.localeCompare(x.username, 'en', { sensitivity: 'base' }) <= 0 || a[i - 1].username <= x.username)
      && 'privileges' in r.json.content[0] && !('password' in r.json.content[0]),
      `status ${r.status} size=${r.json?.size} sort=${r.json?.sort} total=${r.json?.totalElements} keys=${Object.keys(r.json?.content?.[0] || {})}`);
  }
  {
    const f = `filter=username:contains:${pfx}`;
    const p0 = await rt.api('GET', `/api/users?size=10&page=0&${f}`, { token: admin });
    const p1 = await rt.api('GET', `/api/users?size=10&page=1&${f}`, { token: admin });
    const names0 = p0.json.content.map((x) => x.username); const names1 = p1.json.content.map((x) => x.username);
    const ok = p0.json.totalElements === 11 && p0.json.totalPages === 2 && names0.length === 10 && names1.length === 1
      && names0[0] === `${pfx}-a` && names1[0] === `${pfx}-k` && !names0.some((n) => names1.includes(n));
    rec('API-U02', 'paging size=10 over own 11 users (page 0 = a..j, page 1 = k)', ok, `total=${p0.json.totalElements} pages=${p0.json.totalPages} p0=${names0.length} p1=${names1}`);
    const bs = await rt.api('GET', '/api/users?size=15', { token: admin });
    const bp = await rt.api('GET', '/api/users?page=-1', { token: admin });
    rec('API-U03', 'invalid size (15) and negative page -> 400', bs.status === 400 && bp.status === 400, `${snip(bs)} | ${snip(bp)}`);
    const sd = await rt.api('GET', `/api/users?size=20&sort=fullName,desc&${f}`, { token: admin });
    const sr = await rt.api('GET', `/api/users?size=20&sort=roleName,asc&${f}`, { token: admin });
    const bad = await rt.api('GET', `/api/users?sort=password,asc`, { token: admin });
    rec('API-U04', 'sort fullName desc / roleName asc work; unknown sort column -> 400',
      sd.status === 200 && sd.json.content[0].fullName === 'Full k' && sd.json.content.at(-1).fullName === 'Full a'
      && sr.status === 200 && sr.json.content[0].role === 'CASHIER' && sr.json.content.at(-1).role === 'VIEWER' && bad.status === 400,
      `fullName desc first=${sd.json?.content?.[0]?.fullName}; roleName asc first=${sr.json?.content?.[0]?.role} last=${sr.json?.content?.at(-1)?.role}; bad ${snip(bad)}`);
    const fr = await rt.api('GET', `/api/users?${f}&filter=roleName:eq:CASHIER`, { token: admin });
    const bo = await rt.api('GET', `/api/users?filter=username:gt:a`, { token: admin });
    const bc = await rt.api('GET', `/api/users?filter=password:eq:a`, { token: admin });
    rec('API-U05', 'filters combine (username contains + roleName eq); bad operator / unknown column -> 400',
      fr.status === 200 && fr.json.totalElements === 1 && fr.json.content[0].id === ub.id
      && fr.json.appliedFilters.length === 2 && bo.status === 400 && bc.status === 400,
      `combined total=${fr.json?.totalElements} applied=${JSON.stringify(fr.json?.appliedFilters)}; bad op ${snip(bo)}; bad col ${snip(bc)}`);
  }
  {
    const noUser = await rt.api('POST', '/api/users', { token: admin, body: { password: P, roleId: roleByName.VIEWER.id } });
    const noPw = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurv'), roleId: roleByName.VIEWER.id } });
    const noRole = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurv'), password: P } });
    const badRole = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurv'), password: P, roleId: 99999999 } });
    const custRole = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurv'), password: P, roleId: roleByName.CUSTOMER.id } });
    rec('API-U06', 'create validation: missing username/password/roleId -> 400 fieldErrors; unknown role -> 404; CUSTOMER role -> 400',
      noUser.status === 400 && noUser.json?.fieldErrors?.username && noPw.status === 400 && noPw.json?.fieldErrors?.password
      && noRole.status === 400 && noRole.json?.fieldErrors?.roleId && badRole.status === 404 && custRole.status === 400,
      `noUser ${snip(noUser)} | noPw ${noPw.status} | noRole ${noRole.status} | badRole ${snip(badRole)} | custRole ${snip(custRole)}`);
    const dup = await rt.api('POST', '/api/users', { token: admin, body: { username: ua.username, password: P, roleId: roleByName.VIEWER.id } });
    rec('API-U07', 'duplicate username -> 400 "Username already exists"', dup.status === 400 && dup.json?.message === 'Username already exists', snip(dup));
    const dupEmail = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurde'), email: ua.email, password: P, roleId: roleByName.VIEWER.id } });
    rec('API-U08', 'duplicate email on create -> 4xx validation error (not 500)', dupEmail.status >= 400 && dupEmail.status < 500, snip(dupEmail));
    const dupEmailUpd = await rt.api('PUT', `/api/users/${uc.id}`, { token: admin, body: { email: ua.email } });
    rec('API-U09', 'duplicate email on update -> 4xx (not 500)', dupEmailUpd.status >= 400 && dupEmailUpd.status < 500, snip(dupEmailUpd));
    const longName = await rt.api('POST', '/api/users', { token: admin, body: { username: 'x'.repeat(90), password: P, roleId: roleByName.VIEWER.id } });
    rec('API-U10', 'username longer than column (90 > 80 chars) -> 400 (not 500)', longName.status === 400, snip(longName));
    const defActive = await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq('aurdef'), password: P, roleId: roleByName.SALES_POC.id } });
    const g = await rt.api('GET', `/api/users/${defActive.json?.id}`, { token: admin });
    const nf = await rt.api('GET', `/api/users/99999999`, { token: admin });
    rec('API-U11', 'create happy path: active defaults true, role+privileges returned; GET by id; unknown id -> 404',
      defActive.status === 200 && defActive.json.active === true && defActive.json.role === 'SALES_POC'
      && JSON.stringify(defActive.json.privileges) === JSON.stringify(roleByName.SALES_POC.privileges)
      && g.status === 200 && g.json.username === defActive.json.username && nf.status === 404,
      `create ${defActive.status} active=${defActive.json?.active} role=${defActive.json?.role}; get ${g.status}; unknown ${snip(nf)}`);
  }
  {
    // update
    const up = await rt.api('PUT', `/api/users/${ud.id}`, { token: admin, body: { email: `${pfx}-d2@rt.local`, fullName: 'Renamed D', password: 'Changed99', roleId: roleByName.CASHIER.id } });
    const g = await rt.api('GET', `/api/users/${ud.id}`, { token: admin });
    const lOld = await rt.api('POST', '/api/auth/login', { body: { username: ud.username, password: P } });
    const lNew = await rt.api('POST', '/api/auth/login', { body: { username: ud.username, password: 'Changed99' } });
    const aud = await rt.api('GET', `/api/audit?entityType=USER&entityId=${ud.id}`, { token: admin });
    const auditRow = (aud.json || []).find((x) => x.action === 'USER_UPDATED');
    rec('API-U12', 'update email/fullName/password/role; new password works; USER_UPDATED audit row',
      up.status === 200 && g.json.fullName === 'Renamed D' && g.json.email === `${pfx}-d2@rt.local` && g.json.role === 'CASHIER'
      && lOld.status === 401 && lNew.status === 200 && lNew.json.user.role === 'CASHIER' && !!auditRow && auditRow.changedByUsername === 'admin',
      `PUT ${up.status}; get fullName=${g.json?.fullName} role=${g.json?.role}; old pw ${lOld.status}; new pw ${lNew.status}; audit ${aud.status} rows=${(aud.json || []).map((x) => x.action)}`);
    const keep = await rt.api('PUT', `/api/users/${ud.id}`, { token: admin, body: { fullName: 'Renamed D2', password: '' } });
    const l2 = await rt.api('POST', '/api/auth/login', { body: { username: ud.username, password: 'Changed99' } });
    const nf = await rt.api('PUT', `/api/users/99999999`, { token: admin, body: { fullName: 'x' } });
    const badRole = await rt.api('PUT', `/api/users/${ud.id}`, { token: admin, body: { roleId: 99999999 } });
    rec('API-U13', 'update with blank password keeps password; unknown user/role -> 404', keep.status === 200 && keep.json.fullName === 'Renamed D2'
      && l2.status === 200 && nf.status === 404 && badRole.status === 404, `keep ${keep.status}; login ${l2.status}; unknown user ${nf.status}; unknown role ${badRole.status}`);
    const pw = await rt.api('PUT', `/api/users/${ud.id}`, { token: admin, body: { password: 'a' } });
    rec('API-U14', 'admin setting a 1-char password is rejected like change-password (min 6)', pw.status === 400, `PUT password 'a' -> ${snip(pw)}`);
    await rt.api('PUT', `/api/users/${ud.id}`, { token: admin, body: { password: 'Changed99' } });
  }
  {
    // delete plain user
    const del = await rt.api('DELETE', `/api/users/${uf.id}`, { token: admin });
    const g = await rt.api('GET', `/api/users/${uf.id}`, { token: admin });
    const l = await rt.api('POST', '/api/auth/login', { body: { username: uf.username, password: P } });
    const nf = await rt.api('DELETE', `/api/users/99999999`, { token: admin });
    rec('API-U15', 'delete a plain user -> deleted:true, then 404 and cannot log in; unknown -> 404',
      del.status === 200 && del.json.deleted === true && del.json.deactivated === false && g.status === 404 && l.status === 401 && nf.status === 404,
      `DELETE ${snip(del)}; GET ${g.status}; login ${l.status}; unknown ${nf.status}`);
  }
  {
    // delete user named as POC on invoice/customer seat -> deactivated instead
    const cust = await rt.createCustomer(admin, 'aurc');
    const prod = await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq('aurp'), description: 'd', price: 10, active: true } });
    const inv = await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: cust.id, salesPocUserId: ue.id, items: [{ productId: prod.json.id, quantity: 1 }] } });
    const del = await rt.api('DELETE', `/api/users/${ue.id}`, { token: admin });
    const g = await rt.api('GET', `/api/users/${ue.id}`, { token: admin });
    const l = await rt.api('POST', '/api/auth/login', { body: { username: ue.username, password: P } });
    const invAfter = await rt.api('GET', `/api/invoices/${inv.json?.id}`, { token: admin });
    const aud = await rt.api('GET', `/api/audit?entityType=USER&entityId=${ue.id}`, { token: admin });
    rec('API-U16', 'delete user named as invoice Sales POC -> deactivated (not deleted); invoice keeps POC; login refused; audit',
      inv.status === 200 && del.status === 200 && del.json.deleted === false && del.json.deactivated === true && del.json.pocReferences === 1
      && g.status === 200 && g.json.active === false && l.status === 401 && invAfter.json?.salesPoc?.id === ue.id
      && (aud.json || []).some((x) => x.action === 'USER_DEACTIVATED'),
      `invoice ${inv.status} id=${inv.json?.id}; DELETE ${snip(del)}; GET active=${g.json?.active}; login ${l.status}; invoice salesPoc=${JSON.stringify(invAfter.json?.salesPoc)}; audit=${(aud.json || []).map((x) => x.action)}`);
    // customer seat
    const cs = await mk('cs', 'CUSTOMER_SUCCESS_POC');
    const seat = await rt.api('POST', `/api/customers/${cust.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: cs.id, primary: true } });
    const del2 = await rt.api('DELETE', `/api/users/${cs.id}`, { token: admin });
    const g2 = await rt.api('GET', `/api/users/${cs.id}`, { token: admin });
    const assignable = await rt.api('GET', `/api/pocs/assignable?type=SUCCESS&q=${encodeURIComponent(cs.username)}`, { token: admin });
    const inAssignable = JSON.stringify(assignable.json || '').includes(cs.username);
    rec('API-U17', 'delete user holding a customer POC seat -> deactivated; drops out of assignable dropdown',
      seat.status < 300 && del2.json?.deactivated === true && del2.json?.pocReferences >= 1 && g2.json?.active === false && !inAssignable,
      `seat ${seat.status}; DELETE ${snip(del2)}; active=${g2.json?.active}; assignable ${assignable.status} contains=${inAssignable}`);
  }
  {
    // bulk: use my own user-manager as actor to test self-skip (never touches seeded admin)
    const mgrRole = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('AUR_UMGR'), description: 'user mgr', privileges: ['USER_VIEW', 'USER_MANAGE', 'EXPORT_DATA'] } });
    const mgr = await mk('mgr', 'VIEWER');
    await rt.api('PUT', `/api/users/${mgr.id}`, { token: admin, body: { roleId: mgrRole.json.id } });
    const mt = await rt.login(mgr.username, P);
    await rt.api('PUT', `/api/users/${uh.id}`, { token: admin, body: { active: false } });
    const r = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'DEACTIVATE', ids: [ug.id, uh.id, mgr.id, ug.id] } });
    const g = await rt.api('GET', `/api/users/${ug.id}`, { token: admin });
    const sk = r.json?.skipped || [];
    rec('API-U18', 'bulk DEACTIVATE per-record results: succeeded / skipped already-inactive / skipped self; duplicate id counted once',
      r.status === 200 && r.json.requested === 3 && JSON.stringify(r.json.succeeded) === JSON.stringify([ug.id])
      && sk.some((s) => s.id === uh.id && /Already inactive/.test(s.reason)) && sk.some((s) => s.id === mgr.id && /own account/.test(s.reason))
      && r.json.failed.length === 0 && g.json.active === false,
      `${snip(r)}; ${ug.username} active=${g.json?.active}`);
    const lg = await rt.api('POST', '/api/auth/login', { body: { username: ug.username, password: P } });
    const ra = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'ACTIVATE', ids: [ug.id, uh.id, ui.id] } });
    const lg2 = await rt.api('POST', '/api/auth/login', { body: { username: ug.username, password: P } });
    rec('API-U19', 'bulk ACTIVATE: inactive users re-activated, already-active skipped; login refused while inactive and allowed after',
      lg.status === 401 && ra.status === 200 && ra.json.succeeded.length === 2 && ra.json.succeeded.includes(ug.id) && ra.json.succeeded.includes(uh.id)
      && ra.json.skipped.length === 1 && ra.json.skipped[0].id === ui.id && lg2.status === 200,
      `login while inactive ${lg.status}; ${snip(ra)}; login after ${lg2.status}`);
    const unk = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'DELETE', ids: [ui.id] } });
    const noids = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'DEACTIVATE' } });
    const noact = await rt.api('POST', '/api/users/bulk', { token: mt, body: { ids: [ui.id] } });
    rec('API-U20', 'bulk validation: unknown action / no ids / missing action -> 400', unk.status === 400 && noids.status === 400 && noact.status === 400,
      `${snip(unk)} | ${snip(noids)} | ${noact.status}`);
    const missing = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'DEACTIVATE', ids: [ui.id, 987654321] } });
    const accounted = [...(missing.json?.succeeded || []), ...(missing.json?.failed || []).map((x) => x.id), ...(missing.json?.skipped || []).map((x) => x.id)];
    rec('API-U21', 'bulk with a non-existent id: every requested id accounted for in succeeded/failed/skipped (design §8, AC-D5)',
      missing.status === 200 && accounted.includes(987654321), snip(missing));
    await rt.api('PUT', `/api/users/${ui.id}`, { token: admin, body: { active: true } });
    // select all matching filter
    const sel = await rt.api('POST', '/api/users/bulk', { token: mt, body: { action: 'DEACTIVATE', selectAllMatchingFilter: true, filters: [`username:contains:${pfx}-`, 'roleName:eq:VIEWER', `username:neq:${mgr.username}`] } });
    const after = await rt.api('GET', `/api/users?size=50&filter=username:contains:${pfx}-&filter=active:eq:false`, { token: admin });
    const cashierRow = await rt.api('GET', `/api/users/${ub.id}`, { token: admin });
    rec('API-U22', 'bulk selectAllMatchingFilter only touches rows matching the filter',
      sel.status === 200 && sel.json.succeeded.length >= 5 && cashierRow.json.active === true
      && after.json.content.every((x) => x.role === 'VIEWER' || x.role === 'SALES_POC' || x.role === 'CUSTOMER_SUCCESS_POC'),
      `${snip(sel)}; cashier-role user still active=${cashierRow.json?.active}; inactive now=${after.json?.content?.map((x) => x.username.slice(-3) + ':' + x.role)}`);
    // export
    const ex = await rt.api('POST', '/api/users/export', { token: admin, body: { action: 'EXPORT', ids: [ua.id, ub.id] } });
    const lines = (ex.text || '').trim().split(/\r?\n/);
    rec('API-U23', 'export users CSV (selected ids) -> text/csv with header and 2 rows',
      ex.status === 200 && /text\/csv/.test(ex.headers['content-type']) && /users\.csv/.test(ex.headers['content-disposition'] || '')
      && lines[0] === 'Id,Username,Full name,Email,Role,Active' && lines.length === 3 && lines.some((l) => l.includes(ub.username) && l.includes('CASHIER')),
      `${ex.status} ${ex.headers['content-type']} lines=${JSON.stringify(lines)}`);
    const ex2 = await rt.api('POST', '/api/users/export', { token: admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`username:contains:${pfx}-`], sort: 'username,desc' } });
    const l2 = (ex2.text || '').trim().split(/\r?\n/);
    const ex3 = await rt.api('POST', '/api/users/export', { token: admin, body: { action: 'EXPORT' } });
    rec('API-U24', 'export selectAllMatchingFilter returns only filtered rows; no ids -> 400',
      ex2.status === 200 && l2.length - 1 === (await rt.api('GET', `/api/users?filter=username:contains:${pfx}-`, { token: admin })).json.totalElements && ex3.status === 400,
      `rows=${l2.length - 1}; first data row=${l2[1]}; no-ids ${snip(ex3)}`);
    // export by a caller without USER_VIEW
    const ct = await rt.cashierToken();
    const exc = await rt.api('POST', '/api/users/export', { token: ct, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const usersVisible = await rt.api('GET', '/api/users', { token: ct });
    rec('API-U25', 'CASHIER (EXPORT_DATA but no USER_VIEW) cannot export the user table',
      exc.status === 403, `GET /api/users as cashier ${usersVisible.status}; POST /api/users/export as cashier ${exc.status} rows=${(exc.text || '').split('\n').length - 1} sample=${(exc.text || '').split('\n').slice(0, 3).join(' / ')}`);
  }

  // ---------------------------------------------------------------- ROLES & PRIVILEGES
  {
    const r = await rt.api('GET', '/api/privileges', { token: admin });
    const names = (r.json || []).map((p) => p.name).sort();
    const expected = ['USER_VIEW', 'USER_MANAGE', 'ROLE_VIEW', 'ROLE_MANAGE', 'CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'PRODUCT_VIEW', 'PRODUCT_MANAGE', 'INVOICE_VIEW', 'INVOICE_MANAGE', 'PAYMENT_VIEW', 'PAYMENT_MANAGE', 'DISPUTE_CREATE', 'DISPUTE_VIEW', 'DISPUTE_MANAGE', 'NOTIFICATION_VIEW', 'AUDIT_VIEW', 'POC_VIEW', 'POC_ASSIGN', 'POC_ASSIGNABLE_SALES', 'POC_ASSIGNABLE_SUCCESS', 'POC_ASSIGNABLE_COLLECTION', 'SCOPE_OVERRIDE', 'PROMISE_VIEW', 'PROMISE_MANAGE', 'PROMISE_OVERRIDE', 'EXPORT_DATA'].sort();
    rec('API-R01', 'GET /api/privileges returns the 27 canonical privileges', r.status === 200 && JSON.stringify(names) === JSON.stringify(expected),
      `${r.status} count=${names.length} missing=${expected.filter((x) => !names.includes(x))} extra=${names.filter((x) => !expected.includes(x))}`);
  }
  {
    const r = await rt.api('GET', '/api/roles', { token: admin });
    const names = r.json.content.map((x) => x.name);
    rec('API-R02', 'list roles: envelope, default sort name,asc, contains 7 seeded roles', r.status === 200 && r.json.sort === 'name,asc'
      && ['ADMIN', 'CASHIER', 'VIEWER', 'CUSTOMER', 'SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC'].every((n) => roles.some((x) => x.name === n))
      && names.every((n, i) => i === 0 || names[i - 1].localeCompare(n) <= 0 || names[i - 1] <= n),
      `sort=${r.json.sort} total=${r.json.totalElements} first=${names.slice(0, 5)}`);
    const f = await rt.api('GET', '/api/roles?filter=name:contains:POC&size=50', { token: admin });
    const sd = await rt.api('GET', '/api/roles?sort=description,asc', { token: admin });
    rec('API-R03', 'roles filter name contains POC; sort by non-sortable description -> 400',
      f.status === 200 && f.json.content.every((x) => x.name.includes('POC')) && f.json.content.some((x) => x.name === 'SALES_POC') && sd.status === 400,
      `filter total=${f.json?.totalElements}; sort description ${snip(sd)}`);
  }
  {
    // seeded matrix per design notes §2
    const Y = true, N = false;
    const cols = ['ADMIN', 'CASHIER', 'VIEWER', 'CUSTOMER', 'SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC'];
    const m = {
      CUSTOMER_VIEW: [Y, Y, Y, Y, Y, Y, Y], CUSTOMER_MANAGE: [Y, Y, N, N, N, Y, N],
      PRODUCT_VIEW: [Y, Y, Y, N, Y, Y, N], PRODUCT_MANAGE: [Y, N, N, N, N, N, N],
      INVOICE_VIEW: [Y, Y, Y, Y, Y, Y, Y], INVOICE_MANAGE: [Y, Y, N, N, Y, N, N],
      PAYMENT_VIEW: [Y, Y, Y, Y, Y, Y, Y], PAYMENT_MANAGE: [Y, Y, N, N, N, N, Y],
      DISPUTE_CREATE: [Y, N, N, Y, N, N, N], DISPUTE_VIEW: [Y, N, N, Y, Y, Y, Y], DISPUTE_MANAGE: [Y, N, N, N, N, N, N],
      NOTIFICATION_VIEW: [Y, Y, Y, Y, Y, Y, Y], AUDIT_VIEW: [Y, N, N, Y, Y, Y, Y],
      USER_VIEW: [Y, N, N, N, N, N, N], USER_MANAGE: [Y, N, N, N, N, N, N],
      ROLE_VIEW: [Y, N, N, N, N, N, N], ROLE_MANAGE: [Y, N, N, N, N, N, N],
      POC_VIEW: [Y, Y, Y, N, Y, Y, Y], POC_ASSIGN: [Y, Y, N, N, Y, Y, Y],
      POC_ASSIGNABLE_SALES: [Y, N, N, N, Y, N, N], POC_ASSIGNABLE_SUCCESS: [Y, N, N, N, N, Y, N], POC_ASSIGNABLE_COLLECTION: [Y, N, N, N, N, N, Y],
      SCOPE_OVERRIDE: [Y, Y, Y, N, N, Y, Y],
      PROMISE_VIEW: [Y, Y, Y, Y, Y, Y, Y], PROMISE_MANAGE: [Y, N, N, N, N, N, Y], PROMISE_OVERRIDE: [Y, N, N, N, N, N, Y],
      EXPORT_DATA: [Y, Y, N, N, Y, Y, Y],
    };
    const diffs = [];
    cols.forEach((c, i) => {
      const role = roleByName[c];
      if (!role) { diffs.push(`${c}: missing`); return; }
      const expected = Object.keys(m).filter((p) => m[p][i]).sort();
      const actual = [...role.privileges].sort();
      const missing = expected.filter((p) => !actual.includes(p));
      const extra = actual.filter((p) => !expected.includes(p));
      if (missing.length || extra.length) diffs.push(`${c}: missing=${missing} extra=${extra}`);
    });
    rec('API-R04', 'seeded role x privilege matrix equals design notes §2 exactly (27 privileges x 7 roles)', diffs.length === 0,
      diffs.length ? diffs.join(' ; ') : `all 7 roles match; counts ${cols.map((c) => `${c}=${roleByName[c].privileges.length}`).join(' ')}`);
  }
  {
    const name = rt.uniq('AUR_ROLE');
    const c = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'custom', privileges: ['NOTIFICATION_VIEW', 'INVOICE_VIEW', 'EXPORT_DATA'] } });
    const g = await rt.api('GET', `/api/roles/${c.json?.id}`, { token: admin });
    rec('API-R05', 'create custom role with chosen privileges; GET by id returns them sorted',
      c.status === 200 && JSON.stringify(c.json.privileges) === JSON.stringify(['EXPORT_DATA', 'INVOICE_VIEW', 'NOTIFICATION_VIEW']) && g.status === 200 && g.json.name === name,
      `${snip(c)}; GET ${g.status}`);
    const blank = await rt.api('POST', '/api/roles', { token: admin, body: { name: '  ', privileges: [] } });
    const unkP = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('AUR_BAD'), privileges: ['FLY_TO_MOON'] } });
    const dup = await rt.api('POST', '/api/roles', { token: admin, body: { name, privileges: [] } });
    const dupSeed = await rt.api('POST', '/api/roles', { token: admin, body: { name: 'ADMIN', privileges: [] } });
    rec('API-R06', 'role create validation: blank name -> 400; unknown privilege -> 404', blank.status === 400 && unkP.status === 404 && /FLY_TO_MOON/.test(unkP.json?.message || ''),
      `blank ${snip(blank)} | unknown priv ${snip(unkP)}`);
    rec('API-R07', 'duplicate role name (custom and seeded ADMIN) -> 400/409 clear message (not 500)', dup.status >= 400 && dup.status < 500 && dupSeed.status >= 400 && dupSeed.status < 500,
      `dup custom ${snip(dup)} | dup ADMIN ${snip(dupSeed)}`);
    // user with that role sees privilege changes immediately
    const u = await mk('role', 'VIEWER');
    await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { roleId: c.json.id } });
    const tok = await rt.login(u.username, P);
    const before = await rt.api('GET', '/api/customers', { token: tok });
    const up = await rt.api('PUT', `/api/roles/${c.json.id}`, { token: admin, body: { name: name + '_X', description: 'edited', privileges: ['NOTIFICATION_VIEW', 'CUSTOMER_VIEW'] } });
    const after = await rt.api('GET', '/api/customers', { token: tok });
    const me = await rt.api('GET', '/api/auth/me', { token: tok });
    const invNow = await rt.api('GET', '/api/invoices', { token: tok });
    rec('API-R08', 'update role name/description/privileges; existing session picks up the new privileges on next request',
      up.status === 200 && up.json.name === name + '_X' && up.json.description === 'edited' && JSON.stringify(up.json.privileges) === JSON.stringify(['CUSTOMER_VIEW', 'NOTIFICATION_VIEW'])
      && before.status === 403 && after.status === 200 && me.json.role === name + '_X' && invNow.status === 403,
      `PUT ${up.status} ${JSON.stringify(up.json?.privileges)}; GET /api/customers before ${before.status} after ${after.status}; /me role=${me.json?.role}; invoices now ${invNow.status}`);
    const upRen = await rt.api('PUT', `/api/roles/${c.json.id}`, { token: admin, body: { name: 'CASHIER', privileges: [] } });
    rec('API-R09', 'renaming a custom role to an existing role name (CASHIER) -> 4xx (not 500)', upRen.status >= 400 && upRen.status < 500, snip(upRen));
    const upNf = await rt.api('PUT', `/api/roles/99999999`, { token: admin, body: { name: 'x', privileges: [] } });
    rec('API-R10', 'update unknown role -> 404', upNf.status === 404, snip(upNf));
    // delete in use
    const delInUse = await rt.api('DELETE', `/api/roles/${c.json.id}`, { token: admin });
    const stillThere = await rt.api('GET', `/api/roles/${c.json.id}`, { token: admin });
    const userOk = await rt.api('GET', '/api/auth/me', { token: tok });
    rec('API-R11', 'delete a role still assigned to a user -> refused with a clear 4xx; role and user intact',
      delInUse.status >= 400 && delInUse.status < 500 && stillThere.status === 200 && userOk.status === 200,
      `DELETE ${snip(delInUse)}; role GET ${stillThere.status}; user /me ${userOk.status}`);
    // delete unused
    const r2 = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('AUR_TMP'), privileges: ['NOTIFICATION_VIEW'] } });
    const d2 = await rt.api('DELETE', `/api/roles/${r2.json.id}`, { token: admin });
    const g2 = await rt.api('GET', `/api/roles/${r2.json.id}`, { token: admin });
    rec('API-R12', 'delete an unused custom role -> 2xx, then GET 404', d2.status >= 200 && d2.status < 300 && g2.status === 404, `DELETE ${d2.status}; GET ${g2.status}`);
    const dNf = await rt.api('DELETE', `/api/roles/99999999`, { token: admin });
    rec('API-R13', 'delete unknown role -> 404', dNf.status === 404, snip(dNf));
    const ex = await rt.api('POST', '/api/roles/export', { token: admin, body: { action: 'EXPORT', ids: [roleByName.VIEWER.id] } });
    const lines = (ex.text || '').trim().split(/\r?\n/);
    const ct = await rt.cashierToken();
    const exc = await rt.api('POST', '/api/roles/export', { token: ct, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    rec('API-R14', 'roles export CSV (admin) lists VIEWER with its privileges', ex.status === 200 && lines[0] === 'Id,Name,Description,Privileges'
      && lines.length === 2 && lines[1].includes('VIEWER') && lines[1].includes('SCOPE_OVERRIDE'), `${ex.status} ${JSON.stringify(lines)}`);
    rec('API-R15', 'CASHIER (EXPORT_DATA but no ROLE_VIEW) cannot export roles', exc.status === 403, `cashier POST /api/roles/export -> ${exc.status} rows=${(exc.text || '').split('\n').length - 1}`);
    // cleanup: move user off role and delete role
    await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { roleId: roleByName.VIEWER.id } });
    const d3 = await rt.api('DELETE', `/api/roles/${c.json.id}`, { token: admin });
    console.log('cleanup role delete', d3.status);
  }
  {
    // role with no privileges
    const r = await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('AUR_EMPTY'), description: '', privileges: [] } });
    const u = await mk('empty', 'VIEWER');
    await rt.api('PUT', `/api/users/${u.id}`, { token: admin, body: { roleId: r.json.id } });
    const lg = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: P } });
    const inv = await rt.api('GET', '/api/invoices', { token: lg.json?.token });
    rec('API-R16', 'user on a role with zero privileges can log in; every data endpoint is 403', lg.status === 200 && lg.json.user.privileges.length === 0 && inv.status === 403,
      `login ${lg.status} privs=${JSON.stringify(lg.json?.user?.privileges)}; invoices ${inv.status}`);
  }

  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify(results, null, 1));
  console.log(`\n${results.filter((r) => r.pass).length}/${results.length} passed`);
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
