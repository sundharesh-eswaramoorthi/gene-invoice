// Regression API cases, part 1: auth, users & roles, customers & POC seats, products, invoices, payments, promises.
// The world has ONE region (DEFAULT). Expected behaviour: the pre-existing contract, unchanged for a single-region
// admin and a POC user (DESIGN §4.6 "With one DEFAULT region and APPROVE/ALL grants, every tier reduces to today's",
// §5.6.4 "Single region, backfilled user ... lockedFilters are exactly today's"), plus the additive §6 fields.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, ids, num, customer, invoice, payment, getJ } = H;

const BE = {
  auth: 'backend/src/main/java/com/geneinvoice/auth/AuthController.java',
  users: 'backend/src/main/java/com/geneinvoice/user/UserController.java',
  roles: 'backend/src/main/java/com/geneinvoice/role/RoleController.java',
  cust: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java',
  custCtl: 'backend/src/main/java/com/geneinvoice/customer/CustomerController.java',
  poc: 'backend/src/main/java/com/geneinvoice/poc/PocService.java',
  prod: 'backend/src/main/java/com/geneinvoice/product/ProductController.java',
  inv: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java',
  invCtl: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java',
  pay: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java',
  credit: 'backend/src/main/java/com/geneinvoice/payment/CreditLedger.java',
  prom: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java',
  scope: 'backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java',
};

module.exports = async function apiCore(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users; const D = w.D;

  // ================================================================== AUTH
  await tcase({ id: 'REG-AUTH-01', feature: 'Login', kind: 'API', prd: 'B1',
    title: 'Admin logs in; the response keeps token/user/privileges and adds regions = [DEFAULT APPROVE/ALL]',
    steps: 'POST /api/auth/login admin/admin123',
    expected: 'DESIGN §6.2: 200 {token, expiresInMs, user:{id, username, role ADMIN, privileges (all 40), customerId null, regions:[{code DEFAULT, level APPROVE, scope ALL, privileges}]}}' }, async () => {
    const c = checks();
    const r = await rt.api('POST', '/api/auth/login', { body: { username: 'admin', password: 'admin123' } });
    const u = r.json && r.json.user;
    c.that('200 with token', r.status === 200 && typeof r.json.token === 'string' && r.json.token.length > 20, r.status);
    c.that('expiresInMs > 0', r.json && r.json.expiresInMs > 0, r.json && r.json.expiresInMs);
    c.that('role ADMIN, customerId null', u && u.role === 'ADMIN' && u.customerId === null, u && { role: u.role, customerId: u.customerId });
    c.that('40 privileges', u && u.privileges.length === 40, u && u.privileges.length);
    c.that('regions = [DEFAULT APPROVE ALL]', u && u.regions.length === 1 && u.regions[0].code === 'DEFAULT' && u.regions[0].level === 'APPROVE' && u.regions[0].scope === 'ALL' && u.regions[0].defaultRegion === true, u && u.regions);
    return done(c, { codeRef: BE.auth });
  });

  await tcase({ id: 'REG-AUTH-02', feature: 'Login', kind: 'API', prd: 'B1',
    title: 'Wrong password, unknown user and blank fields are refused without a token',
    steps: 'POST /api/auth/login admin/wrong; nobody/x; {username:"",password:""}',
    expected: '401 "Invalid username or password" for bad credentials; 400 for blank fields (validation); no token' }, async () => {
    const c = checks();
    const a = await rt.api('POST', '/api/auth/login', { body: { username: 'admin', password: 'wrong' } });
    const b = await rt.api('POST', '/api/auth/login', { body: { username: rt.uniq('nobody'), password: 'x12345' } });
    const e = await rt.api('POST', '/api/auth/login', { body: { username: '', password: '' } });
    c.that('wrong password 401', a.status === 401 && !(a.json && a.json.token), brief(a));
    c.that('message "Invalid username or password"', a.json && /Invalid username or password/.test(a.json.message || ''), a.json && a.json.message);
    c.that('unknown user 401', b.status === 401, brief(b));
    c.that('blank fields 400/401, no token', (e.status === 400 || e.status === 401) && !(e.json && e.json.token), brief(e));
    return done(c, { codeRef: BE.auth });
  });

  await tcase({ id: 'REG-AUTH-03', feature: 'Session', kind: 'API', prd: 'B1',
    title: '/me needs a valid token; /me for a staff user and a customer login carry regions (customer: [])',
    steps: 'GET /api/auth/me with no token, a garbage token, sales POC token, a customer login token',
    expected: 'DESIGN §6.2/§4.7: 401 without/garbage token; sales POC /me regions [DEFAULT MANAGE ALL]; customer login regions []' }, async () => {
    const c = checks();
    const none = await rt.api('GET', '/api/auth/me');
    const junk = await rt.api('GET', '/api/auth/me', { token: 'not.a.jwt' });
    const s = await getJ(T.sales, '/api/auth/me');
    const cu = await customer(w, { prefix: 'MeCust' });
    const ct = await rt.login(cu.username, cu.password);
    const m = await getJ(ct, '/api/auth/me');
    c.that('no token 401', none.status === 401, brief(none));
    c.that('garbage token 401', junk.status === 401, brief(junk));
    c.that('sales /me 200 SALES_POC', s.status === 200 && s.json.role === 'SALES_POC', brief(s));
    c.that('sales regions DEFAULT MANAGE ALL', s.json && s.json.regions.length === 1 && s.json.regions[0].level === 'MANAGE' && s.json.regions[0].scope === 'ALL', s.json && s.json.regions);
    c.that('customer /me role CUSTOMER, customerId set, regions []', m.status === 200 && m.json.role === 'CUSTOMER' && m.json.customerId === cu.id && Array.isArray(m.json.regions) && m.json.regions.length === 0, m.json);
    return done(c, { codeRef: BE.auth });
  });

  await tcase({ id: 'REG-AUTH-04', feature: 'Change password', kind: 'API', prd: 'B1',
    title: 'Change password: wrong current / same / too short refused; success returns a new token, ends old sessions, new password logs in',
    steps: 'fresh CASHIER-role user (DEFAULT MANAGE/ALL); POST /api/auth/change-password {wrong current}; {same}; {"abc"}; {valid}; GET /me with old token; login with old and new password',
    expected: '400 "Current password is incorrect"; 400 "New password must differ from current"; 400 "Password must be at least 6 characters"; 200 {status ok, token}; old token 401 (AUTH-04); old password 401; new password 200' }, async () => {
    const c = checks();
    const u = await rt.createStaff(T.admin, 'CASHIER', 'rpw', { grants: [{ regionId: D, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const t0 = await rt.login(u.username);
    const cp = (body, token = t0) => rt.api('POST', '/api/auth/change-password', { token, body });
    const a = await cp({ currentPassword: 'nope123', newPassword: 'Other123!' });
    const b = await cp({ currentPassword: rt.PASSWORD, newPassword: rt.PASSWORD });
    const s = await cp({ currentPassword: rt.PASSWORD, newPassword: 'abc' });
    c.that('wrong current 400', a.status === 400 && /Current password is incorrect/.test(a.json && a.json.message), brief(a));
    c.that('same password 400', b.status === 400 && /must differ/.test(b.json && b.json.message), brief(b));
    c.that('short password 400', s.status === 400 && /at least 6/.test(s.json && s.json.message), brief(s));
    await rt.sleep(1100);
    const ok = await cp({ currentPassword: rt.PASSWORD, newPassword: 'NewPass9!' });
    c.that('valid change 200 with token', ok.status === 200 && ok.json.status === 'ok' && typeof ok.json.token === 'string', brief(ok));
    const old = await getJ(t0, '/api/auth/me');
    c.that('old token no longer works (401)', old.status === 401, brief(old));
    const fresh = ok.json && ok.json.token ? await getJ(ok.json.token, '/api/auth/me') : { status: 0 };
    c.that('returned token works', fresh.status === 200, brief(fresh));
    const oldPw = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: rt.PASSWORD } });
    const newPw = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: 'NewPass9!' } });
    c.that('old password 401', oldPw.status === 401, brief(oldPw));
    c.that('new password 200', newPw.status === 200, brief(newPw));
    return done(c, { codeRef: BE.auth });
  });

  await tcase({ id: 'REG-AUTH-05', feature: 'Login', kind: 'API', prd: 'B1',
    title: 'A deactivated user cannot log in; reactivating restores access (users bulk DEACTIVATE/ACTIVATE)',
    steps: 'fresh VIEWER user; POST /api/users/bulk {action:DEACTIVATE, ids:[u]}; login; bulk ACTIVATE; login',
    expected: 'bulk 200 succeeded [u]; login refused (401) while inactive; 200 after ACTIVATE' }, async () => {
    const c = checks();
    const u = await rt.createStaff(T.admin, 'VIEWER', 'rdeact', { grants: [{ regionId: D, accessLevel: 'READ', recordScope: 'ALL' }] });
    const d = await rt.api('POST', '/api/users/bulk', { token: T.admin, body: { action: 'DEACTIVATE', ids: [u.id] } });
    c.that('DEACTIVATE succeeded', d.status === 200 && d.json.succeeded.includes(u.id), brief(d));
    c.that('BulkResult has pending []', d.json && Array.isArray(d.json.pending) && d.json.pending.length === 0, d.json && d.json.pending);
    const l1 = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: rt.PASSWORD } });
    c.that('inactive login refused', l1.status === 401 || l1.status === 403, brief(l1));
    const a = await rt.api('POST', '/api/users/bulk', { token: T.admin, body: { action: 'ACTIVATE', ids: [u.id] } });
    c.that('ACTIVATE succeeded', a.status === 200 && a.json.succeeded.includes(u.id), brief(a));
    const l2 = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: rt.PASSWORD } });
    c.that('login works again', l2.status === 200, brief(l2));
    return done(c, { codeRef: BE.users });
  });

  // ================================================================== USERS & ROLES
  await tcase({ id: 'REG-USR-01', feature: 'Users admin', kind: 'API', prd: 'B1',
    title: 'Create a staff user with a DEFAULT grant; read it back with regionGrants; find it by filter; update name and role',
    steps: 'POST /api/users {username, email, fullName, password, roleId SALES_POC, active, regionGrants:[{DEFAULT, MANAGE, ALL}]}; GET /api/users/{id}; GET /api/users?filter=username:eq:<u>; PUT /api/users/{id} {fullName, roleId CASHIER}',
    expected: 'DESIGN §6.3: 200 UserDto; GET has regionGrants [{regionCode DEFAULT, level MANAGE, scope ALL}]; list filter returns exactly that user (regionIds [] for the unscoped users list); PUT 200 with new name/role' }, async () => {
    const c = checks();
    const username = rt.uniq('rusr');
    const r = await rt.api('POST', '/api/users', { token: T.admin, body: { username, email: `${username}@e2e.local`, fullName: 'Reg User', password: rt.PASSWORD, roleId: await rt.roleId(T.admin, 'SALES_POC'), active: true, regionGrants: [{ regionId: D, level: 'MANAGE', scope: 'ALL' }] } });
    c.that('create 200', r.status === 200 && r.json.username === username && r.json.role === 'SALES_POC', brief(r));
    const id = r.json && r.json.id;
    const g = await getJ(T.admin, `/api/users/${id}`);
    c.that('GET has regionGrants DEFAULT MANAGE ALL', g.status === 200 && g.json.regionGrants && g.json.regionGrants.length === 1 && g.json.regionGrants[0].regionCode === 'DEFAULT' && g.json.regionGrants[0].level === 'MANAGE' && g.json.regionGrants[0].scope === 'ALL', g.json && g.json.regionGrants);
    const l = await getJ(T.admin, '/api/users', { size: 10, filter: `username:eq:${username}` });
    c.that('filter finds exactly the user', l.status === 200 && l.json.totalElements === 1 && l.json.content[0].id === id, brief(l));
    c.that('users list regionIds [] (unscoped)', l.json && Array.isArray(l.json.regionIds) && l.json.regionIds.length === 0, l.json && l.json.regionIds);
    const p = await rt.api('PUT', `/api/users/${id}`, { token: T.admin, body: { fullName: 'Reg User Renamed', roleId: await rt.roleId(T.admin, 'CASHIER') } });
    c.that('PUT 200 name+role', p.status === 200 && p.json.fullName === 'Reg User Renamed' && p.json.role === 'CASHIER', brief(p));
    return done(c, { codeRef: BE.users });
  });

  await tcase({ id: 'REG-USR-02', feature: 'Users admin', kind: 'API', prd: 'B1',
    title: 'User validation: duplicate username / email, missing role, customer role refused; non-admins get 403',
    steps: 'POST /api/users duplicate admin username; duplicate email; no roleId; CUSTOMER role; GET /api/users as sales POC; POST /api/users as cashier',
    expected: '400 "Username already exists"; 400 "Email already exists"; 400 (roleId required); 400 "Use POST /api/customers..."; 403 for sales POC and cashier (no USER_VIEW/USER_MANAGE)' }, async () => {
    const c = checks();
    const base = { email: `${rt.uniq('x')}@e2e.local`, fullName: 'X', password: rt.PASSWORD, roleId: await rt.roleId(T.admin, 'VIEWER'), active: true, regionGrants: [] };
    const dup = await rt.api('POST', '/api/users', { token: T.admin, body: { ...base, username: 'admin' } });
    const dupE = await rt.api('POST', '/api/users', { token: T.admin, body: { ...base, username: rt.uniq('u'), email: 'admin@geneinvoice.local' } });
    const noRole = await rt.api('POST', '/api/users', { token: T.admin, body: { ...base, username: rt.uniq('u'), roleId: null } });
    const custRole = await rt.api('POST', '/api/users', { token: T.admin, body: { ...base, username: rt.uniq('u'), email: `${rt.uniq('y')}@e2e.local`, roleId: await rt.roleId(T.admin, 'CUSTOMER') } });
    const s = await getJ(T.sales, '/api/users');
    const cash = await rt.api('POST', '/api/users', { token: T.cashier, body: { ...base, username: rt.uniq('u'), email: `${rt.uniq('z')}@e2e.local` } });
    c.that('duplicate username 400', dup.status === 400 && /Username already exists/.test(dup.json && dup.json.message), brief(dup));
    c.that('duplicate email 400', dupE.status === 400 && /Email already exists/.test(dupE.json && dupE.json.message), brief(dupE));
    c.that('no role 400', noRole.status === 400, brief(noRole));
    c.that('customer role 400', custRole.status === 400 && /customers/.test(custRole.json && custRole.json.message), brief(custRole));
    c.that('sales GET /api/users 403', s.status === 403, brief(s));
    c.that('cashier POST /api/users 403', cash.status === 403, brief(cash));
    return done(c, { codeRef: BE.users });
  });

  await tcase({ id: 'REG-USR-03', feature: 'Users admin', kind: 'API', prd: 'B1',
    title: 'Delete: a plain user is deleted; a user named as a POC is deactivated instead; deleting yourself is refused',
    steps: 'fresh VIEWER user -> DELETE; fresh COLLECTION_POC user seated on a customer -> DELETE; admin DELETE own id',
    expected: 'plain: 200/204 and GET 404; POC user: 200/204, still exists with active=false (audit USER_DEACTIVATED); self: 400 "You cannot delete your own account"' }, async () => {
    const c = checks();
    const g = [{ regionId: D, accessLevel: 'MANAGE', recordScope: 'ALL' }];
    const plain = await rt.createStaff(T.admin, 'VIEWER', 'rdel', { grants: g });
    const d1 = await rt.api('DELETE', `/api/users/${plain.id}`, { token: T.admin });
    const g1 = await getJ(T.admin, `/api/users/${plain.id}`);
    c.that('plain delete 2xx', d1.status === 200 || d1.status === 204, brief(d1));
    c.that('plain user gone (404)', g1.status === 404, brief(g1));
    const poc = await rt.createStaff(T.admin, 'COLLECTION_POC', 'rdelpoc', { grants: g });
    await customer(w, { prefix: 'DelPoc', collection: poc.id });
    const d2 = await rt.api('DELETE', `/api/users/${poc.id}`, { token: T.admin });
    const g2 = await getJ(T.admin, `/api/users/${poc.id}`);
    c.that('POC delete 2xx', d2.status === 200 || d2.status === 204, brief(d2));
    c.that('POC user kept, inactive', g2.status === 200 && g2.json.active === false, brief(g2));
    const self = await rt.api('DELETE', `/api/users/${U.admin.id}`, { token: T.admin });
    c.that('self delete 400', self.status === 400 && /own account/.test(self.json && self.json.message), brief(self));
    return done(c, { codeRef: BE.users });
  });

  await tcase({ id: 'REG-USR-04', feature: 'Users admin', kind: 'API', prd: 'B1',
    title: 'Users list sorts, pages and exports CSV',
    steps: 'GET /api/users?sort=username,asc&size=10&page=0 and page=1; POST /api/users/export {selectAllMatchingFilter:true}',
    expected: 'sorted ascending by username; page 1 continues page 0 without overlap; export 200 text/csv with a header row and one line per user' }, async () => {
    const c = checks();
    const p0 = await getJ(T.admin, '/api/users', { sort: 'username,asc', size: 10, page: 0 });
    const names = (p0.json && p0.json.content || []).map((u) => u.username);
    // Postgres sorts with the database collation (en_US: punctuation ignored, so "rcollbook-x" < "rcoll-y"):
    // compare on letters and digits only.
    const key = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
    const sorted = [...names].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
    c.that('page 0 sorted asc', p0.status === 200 && JSON.stringify(names.map(key)) === JSON.stringify(sorted.map(key)), names);
    if (p0.json && p0.json.totalElements > 10) {
      const p1 = await getJ(T.admin, '/api/users', { sort: 'username,asc', size: 10, page: 1 });
      const overlap = (p1.json.content || []).filter((u) => names.includes(u.username));
      c.that('page 1 no overlap', p1.status === 200 && overlap.length === 0 && p1.json.page === 1, { page: p1.json.page, overlap: overlap.length });
    }
    const e = await rt.api('POST', '/api/users/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    const lines = (e.text || '').trim().split('\n');
    c.that('export 200 csv', e.status === 200 && /text\/csv/.test(e.headers['content-type'] || ''), brief(e));
    c.that('one line per user + header', lines.length === (p0.json.totalElements + 1), { lines: lines.length, total: p0.json.totalElements });
    return done(c, { codeRef: BE.users });
  });

  await tcase({ id: 'REG-ROLE-01', feature: 'Roles admin', kind: 'API', prd: 'B1',
    title: 'Create, update and delete a role; duplicates and roles in use are refused; privileges carry regionLevel',
    steps: 'POST /api/roles {name, description, privileges:[CUSTOMER_VIEW, INVOICE_VIEW]}; duplicate POST; PUT adding PRODUCT_VIEW; DELETE; DELETE SALES_POC (in use); GET /api/privileges',
    expected: '200 RoleDto; duplicate 400 "Role name already exists"; PUT 200 with 3 privileges; DELETE unused 2xx then 404; in-use 400 "Role is assigned to..."; /api/privileges 40 items each with regionLevel (null for global PRODUCT_VIEW, READ for INVOICE_VIEW, MANAGE for INVOICE_MANAGE, APPROVE for CHANGE_APPROVE) (§6.2)' }, async () => {
    const c = checks();
    const name = rt.uniq('RROLE').toUpperCase().replace(/-/g, '_');
    const r = await rt.api('POST', '/api/roles', { token: T.admin, body: { name, description: 'reg', privileges: ['CUSTOMER_VIEW', 'INVOICE_VIEW'] } });
    c.that('create 200', r.status === 200 && r.json.name === name && r.json.privileges.length === 2, brief(r));
    const dup = await rt.api('POST', '/api/roles', { token: T.admin, body: { name, description: 'reg', privileges: [] } });
    c.that('duplicate 400', dup.status === 400 && /already exists/.test(dup.json && dup.json.message), brief(dup));
    const up = await rt.api('PUT', `/api/roles/${r.json.id}`, { token: T.admin, body: { name, description: 'reg2', privileges: ['CUSTOMER_VIEW', 'INVOICE_VIEW', 'PRODUCT_VIEW'] } });
    c.that('update 200 3 privileges', up.status === 200 && up.json.privileges.length === 3 && up.json.description === 'reg2', brief(up));
    const del = await rt.api('DELETE', `/api/roles/${r.json.id}`, { token: T.admin });
    const gone = await getJ(T.admin, `/api/roles/${r.json.id}`);
    c.that('delete 2xx then 404', (del.status === 200 || del.status === 204) && gone.status === 404, `${brief(del)} / ${brief(gone)}`);
    const inUse = await rt.api('DELETE', `/api/roles/${await rt.roleId(T.admin, 'SALES_POC')}`, { token: T.admin });
    c.that('in-use role 400', inUse.status === 400 && /assigned/.test(inUse.json && inUse.json.message), brief(inUse));
    const pv = await getJ(T.admin, '/api/privileges');
    const arr = Array.isArray(pv.json) ? pv.json : (pv.json && pv.json.content) || [];
    const lv = (n) => (arr.find((p) => p.name === n) || {}).regionLevel;
    c.that('40 privileges', arr.length === 40, arr.length);
    c.that('regionLevel per privilege', lv('PRODUCT_VIEW') === null && lv('INVOICE_VIEW') === 'READ' && lv('INVOICE_MANAGE') === 'MANAGE' && lv('CHANGE_APPROVE') === 'APPROVE', { PRODUCT_VIEW: lv('PRODUCT_VIEW'), INVOICE_VIEW: lv('INVOICE_VIEW'), INVOICE_MANAGE: lv('INVOICE_MANAGE'), CHANGE_APPROVE: lv('CHANGE_APPROVE') });
    return done(c, { codeRef: BE.roles });
  });

  // ================================================================== CUSTOMERS
  await tcase({ id: 'REG-CUS-01', feature: 'Customers create', kind: 'API', prd: 'B1',
    title: 'Single-region admin creates a customer WITHOUT regionId: it lands in DEFAULT (the one region he manages)',
    steps: 'POST /api/customers {name, phone, email, address, paymentTerm NET_45, username, password} (no regionId)',
    expected: 'DESIGN §6.4: "If it is null and the caller can manage exactly one active region, that region is used" -> 200, regionId DEFAULT, regionCode DEFAULT, canManage true, paymentTerm NET_45, outstanding 0, creditBalance 0, pocMissing true' }, async () => {
    const c = checks();
    const username = rt.uniq('rcnr');
    const r = await rt.api('POST', '/api/customers', { token: T.admin, body: { name: `NoRegion ${username}`, phone: '555-0101', email: `${username}@e2e.local`, address: '2 Test Way', paymentTerm: 'NET_45', username, password: rt.PASSWORD } });
    c.that('200', r.status === 200, brief(r));
    const j = r.json || {};
    c.that('region DEFAULT', j.regionId === D && j.regionCode === 'DEFAULT', { regionId: j.regionId, regionCode: j.regionCode });
    c.that('canManage true', j.canManage === true, j.canManage);
    c.that('term NET_45 (Net 45)', j.paymentTerm === 'NET_45' && j.paymentTermLabel === 'Net 45', { t: j.paymentTerm, l: j.paymentTermLabel });
    c.that('zero balances, pocMissing', num(j.outstanding) === 0 && num(j.creditBalance) === 0 && j.pocMissing === true, { o: j.outstanding, cb: j.creditBalance, pm: j.pocMissing });
    const login = await rt.api('POST', '/api/auth/login', { body: { username, password: rt.PASSWORD } });
    c.that('its self-service login works', login.status === 200 && login.json.user.customerId === j.id, brief(login));
    return done(c, { codeRef: BE.cust });
  });

  await tcase({ id: 'REG-CUS-02', feature: 'Customers create', kind: 'API', prd: 'B1',
    title: 'Customer validation: blank name, bad email, duplicate username, CUSTOM term refused on a customer',
    steps: 'POST /api/customers with name ""; email "not-an-email"; username "admin"; paymentTerm CUSTOM',
    expected: '400 fieldErrors.name; 400 fieldErrors.email; 400 "Username already taken"; 400 fieldErrors.paymentTerm' }, async () => {
    const c = checks();
    const b = (o) => ({ name: 'X', phone: '1', email: `${rt.uniq('v')}@e2e.local`, address: 'a', username: rt.uniq('v'), password: rt.PASSWORD, regionId: D, ...o });
    const n = await rt.api('POST', '/api/customers', { token: T.admin, body: b({ name: '' }) });
    const e = await rt.api('POST', '/api/customers', { token: T.admin, body: b({ email: 'not-an-email' }) });
    const u = await rt.api('POST', '/api/customers', { token: T.admin, body: b({ username: 'admin' }) });
    const t = await rt.api('POST', '/api/customers', { token: T.admin, body: b({ paymentTerm: 'CUSTOM' }) });
    c.that('blank name 400 fieldErrors.name', n.status === 400 && n.json.fieldErrors && n.json.fieldErrors.name, brief(n));
    c.that('bad email 400 fieldErrors.email', e.status === 400 && e.json.fieldErrors && e.json.fieldErrors.email, brief(e));
    c.that('duplicate username 400', u.status === 400 && /taken|exists/i.test(u.json && u.json.message), brief(u));
    c.that('CUSTOM term 400', t.status === 400 && (t.json.fieldErrors && t.json.fieldErrors.paymentTerm), brief(t));
    return done(c, { codeRef: BE.cust });
  });

  await tcase({ id: 'REG-CUS-03', feature: 'Customers edit', kind: 'API', prd: 'B1',
    title: 'Edit a customer (name, phone, address, payment term); the change is audited and the customer login\'s name follows',
    steps: 'fresh customer NET_30; PUT /api/customers/{id} {name, phone, email, address, paymentTerm NET_60}; GET; GET /api/audit?entityType=CUSTOMER&entityId',
    expected: '200 with the new values; audit has CUSTOMER_UPDATED and CUSTOMER_PAYMENT_TERM_CHANGED; the customer login /me fullName = new name' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Edit' });
    const name = `Edited ${rt.uniq(w.tag)}`;
    const r = await rt.api('PUT', `/api/customers/${cu.id}`, { token: T.admin, body: { name, phone: '555-9999', email: cu.email, address: '9 New Rd', paymentTerm: 'NET_60' } });
    c.that('PUT 200', r.status === 200 && r.json.name === name && r.json.phone === '555-9999' && r.json.address === '9 New Rd' && r.json.paymentTerm === 'NET_60', brief(r));
    const a = await getJ(T.admin, '/api/audit', { entityType: 'CUSTOMER', entityId: cu.id });
    const acts = (a.json || []).map((x) => x.action);
    c.that('audited', acts.includes('CUSTOMER_UPDATED') && acts.includes('CUSTOMER_PAYMENT_TERM_CHANGED'), acts);
    const m = await rt.me(await rt.login(cu.username, cu.password));
    c.that('login fullName follows', m.fullName === name, m.fullName);
    return done(c, { codeRef: BE.cust });
  });

  await tcase({ id: 'REG-CUS-04', feature: 'POC seats', kind: 'API', prd: 'B1',
    title: 'POC seats: add Success POC, add a second Collection POC, make it primary, remove the old one; Sales seat and wrong-role user refused',
    steps: 'fresh customer (Collection seat coll); POST /pocs {SUCCESS, success user}; POST /pocs {COLLECTION, admin, primary false}; POST /pocs/{adminSeat}/primary; DELETE /pocs/{collSeat}; POST /pocs {SALES, sales}; POST /pocs {COLLECTION, viewer}',
    expected: 'each 200; GET /api/customers/{id} then shows successPocs [success], collectionPocs [admin primary]; pocMissing false; SALES seat 400 "Sales POC is assigned per invoice, not per customer"; viewer 400 "not assignable as Collection POC"; POC_ASSIGNED notification for the success user (link /customers/{id})' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Seats' });
    const pocs0 = await getJ(T.admin, `/api/customers/${cu.id}/pocs`);
    const collSeat = (Array.isArray(pocs0.json) ? pocs0.json : []).find((p) => p.userId === U.coll.id || (p.user && p.user.id === U.coll.id));
    const s = await rt.api('POST', `/api/customers/${cu.id}/pocs`, { token: T.admin, body: { pocType: 'SUCCESS', userId: U.success.id, primary: true } });
    const a = await rt.api('POST', `/api/customers/${cu.id}/pocs`, { token: T.admin, body: { pocType: 'COLLECTION', userId: U.admin.id, primary: false } });
    c.that('add success 200', s.status === 200, brief(s));
    c.that('add 2nd collection 200', a.status === 200, brief(a));
    const pr = a.json ? await rt.api('POST', `/api/customers/${cu.id}/pocs/${a.json.id}/primary`, { token: T.admin, body: {} }) : { status: 0 };
    c.that('make primary 200', pr.status === 200, brief(pr));
    const del = collSeat ? await rt.api('DELETE', `/api/customers/${cu.id}/pocs/${collSeat.id}`, { token: T.admin }) : { status: 0, text: `coll seat not found in ${brief(pocs0)}` };
    c.that('remove old seat 2xx', del.status === 200 || del.status === 204, brief(del));
    const g = await getJ(T.admin, `/api/customers/${cu.id}`);
    const cp = (g.json && g.json.collectionPocs) || [];
    const sp = (g.json && g.json.successPocs) || [];
    const uid = (p) => (p.user ? p.user.id : p.userId);
    c.that('success seat shown', sp.length === 1 && uid(sp[0]) === U.success.id, sp);
    c.that('collection = admin primary only', cp.length === 1 && uid(cp[0]) === U.admin.id && cp[0].primary === true, cp);
    c.that('pocMissing false', g.json && g.json.pocMissing === false, g.json && g.json.pocMissing);
    const sales = await rt.api('POST', `/api/customers/${cu.id}/pocs`, { token: T.admin, body: { pocType: 'SALES', userId: U.sales.id, primary: true } });
    c.that('SALES seat 400', sales.status === 400 && /per invoice/.test(sales.json && sales.json.message), brief(sales));
    const bad = await rt.api('POST', `/api/customers/${cu.id}/pocs`, { token: T.admin, body: { pocType: 'COLLECTION', userId: U.viewer.id, primary: false } });
    c.that('viewer not assignable 400', bad.status === 400 && /not assignable/.test(bad.json && bad.json.message), brief(bad));
    const n = await rt.list(T.success, '/api/notifications');
    c.that('success POC notified', (n.json && n.json.content || []).some((x) => x.type === 'POC_ASSIGNED' && x.link === `/customers/${cu.id}`), (n.json && n.json.content || []).slice(0, 3));
    return done(c, { codeRef: BE.poc });
  });

  await tcase({ id: 'REG-CUS-05', feature: 'Customers delete', kind: 'API', prd: 'B1',
    title: 'Delete a customer without invoices: it, its login and its seats go; history stays readable; one with invoices is refused cleanly',
    steps: 'fresh customer (no invoices) -> DELETE /api/customers/{id}; GET; login as its user; GET /api/audit?entityType=CUSTOMER&entityId; fresh customer with an invoice -> DELETE',
    expected: 'delete 2xx; GET 404; login 401; audit has CUSTOMER_DELETED (§5.6.5 deleted-customer history); customer with invoices -> 4xx (not 5xx), still present' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Del' });
    const d = await rt.api('DELETE', `/api/customers/${cu.id}`, { token: T.admin });
    c.that('delete 2xx', d.status === 200 || d.status === 204, brief(d));
    const g = await getJ(T.admin, `/api/customers/${cu.id}`);
    c.that('GET 404', g.status === 404, brief(g));
    const l = await rt.api('POST', '/api/auth/login', { body: { username: cu.username, password: cu.password } });
    c.that('login gone (401)', l.status === 401, brief(l));
    const a = await getJ(T.admin, '/api/audit', { entityType: 'CUSTOMER', entityId: cu.id });
    c.that('history readable with CUSTOMER_DELETED', a.status === 200 && (a.json || []).some((x) => x.action === 'CUSTOMER_DELETED'), brief(a));
    const cu2 = await customer(w, { prefix: 'DelInv', invoices: [{ amount: 100 }] });
    const d2 = await rt.api('DELETE', `/api/customers/${cu2.id}`, { token: T.admin });
    const g2 = await getJ(T.admin, `/api/customers/${cu2.id}`);
    c.that('with invoices: 4xx not 5xx', d2.status >= 400 && d2.status < 500, brief(d2));
    c.that('with invoices: still there', g2.status === 200, brief(g2));
    return done(c, { codeRef: BE.cust });
  });

  await tcase({ id: 'REG-CUS-06', feature: 'Customers list', kind: 'API', prd: 'B1',
    title: 'Customers list: filter, sort, paging, envelope regionIds [DEFAULT]; summary tiles; export CSV',
    steps: '3 fresh customers named "Srt<tag> A/B/C"; GET /api/customers?filter=name:contains:Srt<tag>&sort=name,desc&size=10; GET /api/customers/summary; POST /api/customers/export {selectAllMatchingFilter, filters:[name:contains:...]}',
    expected: 'totalElements 3 in C,B,A order; envelope regionIds [DEFAULT], bookRegionIds [], lockedFilters [] for admin; summary.count = list total (unfiltered); export 200 CSV with 3 data rows' }, async () => {
    const c = checks();
    const k = `Srt${rt.uniq('').replace(/-/g, '')}`;
    for (const x of ['A', 'B', 'C']) await rt.createCustomer(T.admin, { name: `${k} ${x}`, regionId: D });
    const f = `name:contains:${k}`;
    const l = await getJ(T.admin, '/api/customers', { filter: f, sort: 'name,desc', size: 10 });
    c.that('3 rows C,B,A', l.status === 200 && l.json.totalElements === 3 && l.json.content.map((x) => x.name.slice(-1)).join('') === 'CBA', brief(l));
    c.that('envelope regionIds [D], book [], locked []', l.json && JSON.stringify(l.json.regionIds) === JSON.stringify([D]) && l.json.bookRegionIds.length === 0 && l.json.lockedFilters.length === 0 && l.json.asOf === null, l.json && { r: l.json.regionIds, b: l.json.bookRegionIds, lf: l.json.lockedFilters, asOf: l.json.asOf });
    const all = await getJ(T.admin, '/api/customers', { size: 10 });
    const s = await getJ(T.admin, '/api/customers/summary');
    c.that('summary count = total', s.status === 200 && s.json.count === all.json.totalElements, { summary: s.json, total: all.json.totalElements });
    const e = await rt.api('POST', '/api/customers/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [f] } });
    const lines = (e.text || '').trim().split('\n');
    c.that('export 3 data rows', e.status === 200 && lines.length === 4, { status: e.status, lines: lines.length, head: lines[0] });
    return done(c, { codeRef: BE.custCtl });
  });

  await tcase({ id: 'REG-CUS-07', feature: 'Customers bulk', kind: 'API', prd: 'B1',
    title: 'Customers bulk ADD_POC seats a Success POC on the chosen customers',
    steps: '2 fresh customers; POST /api/customers/bulk {action ADD_POC, ids, params:{userId: success, pocType: SUCCESS}}',
    expected: '200 BulkResult succeeded both ids, failed [], pending []; each customer now lists the success POC' }, async () => {
    const c = checks();
    const a = await customer(w, { prefix: 'Bulk' }); const b = await customer(w, { prefix: 'Bulk' });
    const r = await rt.api('POST', '/api/customers/bulk', { token: T.admin, body: { action: 'ADD_POC', ids: [a.id, b.id], params: { userId: U.success.id, pocType: 'SUCCESS' } } });
    c.that('200 both succeeded', r.status === 200 && r.json.succeeded.length === 2 && r.json.failed.length === 0, brief(r));
    c.that('pending []', r.json && Array.isArray(r.json.pending) && r.json.pending.length === 0, r.json && r.json.pending);
    for (const x of [a, b]) {
      const g = await getJ(T.admin, `/api/customers/${x.id}`);
      c.that(`customer ${x.id} has the success seat`, (g.json.successPocs || []).some((p) => (p.user ? p.user.id : p.userId) === U.success.id), g.json.successPocs);
    }
    return done(c, { codeRef: BE.custCtl });
  });

  await tcase({ id: 'REG-CUS-08', feature: 'POC book (customers)', kind: 'API', prd: 'B1',
    title: 'A book-only Collection POC sees only customers where he holds a seat; others are 404 by id; the list carries today\'s locked chip',
    steps: 'collbook = COLLECTION_POC privileges without SCOPE_OVERRIDE, MANAGE/ALL DEFAULT; customer X (Collection seat collbook) and Y (seat admin); collbook GET /api/customers; GET /api/customers/{Y}',
    expected: 'DESIGN §5.6.4 ("Single region, backfilled user: BOOK when it has only the POC privilege ... lockedFilters exactly today\'s"): list has X not Y, lockedFilters ["myBook:eq:<collbook>"], bookRegionIds [DEFAULT]; GET Y 404' }, async () => {
    const c = checks();
    const X = await customer(w, { prefix: 'Book', collection: U.collbook.id });
    const Y = await customer(w, { prefix: 'NotBook', collection: U.admin.id });
    const l = await rt.list(T.collbook, '/api/customers');
    const got = ids(l);
    c.that('X listed', got.includes(X.id), got.length);
    c.that('Y not listed', !got.includes(Y.id));
    c.that('lockedFilters myBook', l.json && JSON.stringify(l.json.lockedFilters) === JSON.stringify([`myBook:eq:${U.collbook.id}`]), l.json && l.json.lockedFilters);
    c.that('bookRegionIds [DEFAULT]', l.json && JSON.stringify(l.json.bookRegionIds) === JSON.stringify([D]), l.json && l.json.bookRegionIds);
    const gy = await getJ(T.collbook, `/api/customers/${Y.id}`);
    c.that('Y by id 404', gy.status === 404, brief(gy));
    return done(c, { codeRef: BE.scope });
  });

  await tcase({ id: 'REG-CUS-09', feature: 'POC book (customers)', kind: 'API', prd: 'B1',
    title: 'The seeded COLLECTION_POC role (it holds SCOPE_OVERRIDE) keeps seeing every customer, with no locked chip',
    steps: 'coll (seeded COLLECTION_POC, MANAGE/ALL DEFAULT) GET /api/customers; GET a customer where he holds no seat',
    expected: 'DESIGN §5.6.4 tier FULL with SCOPE_OVERRIDE and scope ALL: lockedFilters [], bookRegionIds [], every customer listed (= admin total); by-id 200' }, async () => {
    const c = checks();
    const Y = await customer(w, { prefix: 'Other', collection: U.admin.id });
    const l = await getJ(T.coll, '/api/customers', { size: 10 });
    const a = await getJ(T.admin, '/api/customers', { size: 10 });
    c.that('same total as admin', l.status === 200 && l.json.totalElements === a.json.totalElements, { coll: l.json && l.json.totalElements, admin: a.json.totalElements });
    c.that('no locked chip, no book region', l.json && l.json.lockedFilters.length === 0 && l.json.bookRegionIds.length === 0, l.json && { lf: l.json.lockedFilters, b: l.json.bookRegionIds });
    const g = await getJ(T.coll, `/api/customers/${Y.id}`);
    c.that('by id 200', g.status === 200, brief(g));
    return done(c, { codeRef: BE.scope });
  });

  // ================================================================== PRODUCTS
  await tcase({ id: 'REG-PRD-01', feature: 'Products', kind: 'API', prd: 'B1',
    title: 'Products: create, update price, deactivate (bulk) and reactivate; an inactive product cannot be invoiced',
    steps: 'POST /api/products {name, description, price 42.50, active}; PUT price 45; POST /api/products/bulk DEACTIVATE; POST /api/invoices with that product; bulk ACTIVATE; invoice again',
    expected: '200 ProductDto; PUT 200 price 45; bulk 200 succeeded; invoice 400 "<name> is no longer an active product"; after ACTIVATE invoice 200. Products stay global (D18): list regionIds []' }, async () => {
    const c = checks();
    const name = rt.uniq('RegProd');
    const p = await rt.api('POST', '/api/products', { token: T.admin, body: { name, description: 'reg', price: 42.5, active: true } });
    c.that('create 200', p.status === 200 && p.json.name === name && num(p.json.price) === 42.5, brief(p));
    const u = await rt.api('PUT', `/api/products/${p.json.id}`, { token: T.admin, body: { name, description: 'reg', price: 45, active: true } });
    c.that('update 200', u.status === 200 && num(u.json.price) === 45, brief(u));
    const d = await rt.api('POST', '/api/products/bulk', { token: T.admin, body: { action: 'DEACTIVATE', ids: [p.json.id] } });
    c.that('deactivate 200', d.status === 200 && d.json.succeeded.includes(p.json.id), brief(d));
    const cu = await customer(w, { prefix: 'Prod' });
    const i = await rt.createInvoice(T.admin, { customerId: cu.id, items: [{ productId: p.json.id, quantity: 1, unitPrice: 45 }], salesPocUserId: U.sales.id });
    c.that('inactive product invoice 400', i.status === 400 && /no longer an active product/.test(i.json && i.json.message), brief(i));
    await rt.api('POST', '/api/products/bulk', { token: T.admin, body: { action: 'ACTIVATE', ids: [p.json.id] } });
    const i2 = await rt.createInvoice(T.admin, { customerId: cu.id, items: [{ productId: p.json.id, quantity: 2, unitPrice: 45 }], salesPocUserId: U.sales.id });
    c.that('active again: invoice 200 total 90', i2.status === 200 && num(i2.json.total) === 90, brief(i2));
    const l = await getJ(T.admin, '/api/products', { size: 10 });
    c.that('products list regionIds []', l.json && Array.isArray(l.json.regionIds) && l.json.regionIds.length === 0, l.json && l.json.regionIds);
    return done(c, { codeRef: BE.prod });
  });

  await tcase({ id: 'REG-PRD-02', feature: 'Products', kind: 'API', prd: 'B1',
    title: 'Product rights: POC users can list products but not create; validation on price and name',
    steps: 'sales GET /api/products; sales POST /api/products; admin POST {price -1}; admin POST {name ""}; admin POST /api/products/export',
    expected: 'sales list 200; sales create 403; negative price 400; blank name 400; export 200 CSV' }, async () => {
    const c = checks();
    const l = await getJ(T.sales, '/api/products', { size: 10 });
    const cr = await rt.api('POST', '/api/products', { token: T.sales, body: { name: rt.uniq('x'), price: 1, active: true } });
    const neg = await rt.api('POST', '/api/products', { token: T.admin, body: { name: rt.uniq('x'), price: -1, active: true } });
    const blank = await rt.api('POST', '/api/products', { token: T.admin, body: { name: '', price: 1, active: true } });
    const e = await rt.api('POST', '/api/products/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    c.that('sales list 200', l.status === 200 && l.json.totalElements >= 2, brief(l));
    c.that('sales create 403', cr.status === 403, brief(cr));
    c.that('negative price 400', neg.status === 400, brief(neg));
    c.that('blank name 400', blank.status === 400, brief(blank));
    c.that('export csv', e.status === 200 && /text\/csv/.test(e.headers['content-type'] || ''), brief(e));
    return done(c, { codeRef: BE.prod });
  });

  // ================================================================== INVOICES
  await tcase({ id: 'REG-INV-01', feature: 'Invoice due dates', kind: 'API', prd: 'B2',
    title: 'A new invoice takes its due date from the customer\'s payment term (NET_15 -> +15 days); lines, total and number are right; nothing is held',
    steps: 'fresh customer NET_15; POST /api/invoices {customerId, items:[{Reg Widget x3 @100}, {Reg Gadget x2 @250}], salesPocUserId sales} (no dueDate/paymentTerm)',
    expected: '200 (no DEFAULT threshold, so not 202 - B2); invoiceNumber INV-yyyyMMdd-nnnn; total 800, paid 0, balance 800, UNPAID; paymentTerm NET_15 "Net 15"; dueDate = today+15; overdue false; salesPoc = sales; regionCode DEFAULT; canManage true' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Due', paymentTerm: 'NET_15' });
    const r = await rt.createInvoice(T.admin, { customerId: cu.id, items: [{ productId: w.product.id, quantity: 3, unitPrice: 100 }, { productId: w.product2.id, quantity: 2, unitPrice: 250 }], salesPocUserId: U.sales.id });
    const j = r.json || {};
    c.that('200 not held', r.status === 200 && !r.held, brief(r));
    c.that('number format', /^INV-\d{8}-\d{4}$/.test(j.invoiceNumber || ''), j.invoiceNumber);
    c.that('total 800 / balance 800 / UNPAID', num(j.total) === 800 && num(j.balance) === 800 && num(j.paidAmount) === 0 && j.status === 'UNPAID', { t: j.total, b: j.balance, s: j.status });
    c.that('2 lines with lineTotals 300/500', (j.items || []).length === 2 && (j.items || []).map((i) => num(i.lineTotal)).sort().join() === '300,500', j.items);
    c.that('NET_15, due today+15', j.paymentTerm === 'NET_15' && j.paymentTermLabel === 'Net 15' && j.dueDate === rt.day(15), { term: j.paymentTerm, due: j.dueDate, want: rt.day(15) });
    c.that('not overdue', j.overdue === false && j.daysOverdue === 0, { o: j.overdue, d: j.daysOverdue });
    c.that('sales POC + region + canManage', j.salesPoc && j.salesPoc.id === U.sales.id && j.regionCode === 'DEFAULT' && j.canManage === true, { poc: j.salesPoc && j.salesPoc.id, region: j.regionCode, cm: j.canManage });
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-02', feature: 'Invoice due dates', kind: 'API', prd: 'B1',
    title: 'Payment terms on create: explicit term, Custom with a date, a date alone = Custom, a back-dated invoice; bad combinations refused',
    steps: 'fresh customer NET_30; POST invoices with paymentTerm NET_45; paymentTerm CUSTOM+dueDate +10; dueDate +12 only; invoiceDate -20d with DUE_ON_RECEIPT; CUSTOM without date; NET_30 with a date; dueDate before invoiceDate; GET /api/invoices/due-date-preview?customerId',
    expected: 'NET_45 -> +45; CUSTOM -> +10; date only -> CUSTOM +12; back-dated DUE_ON_RECEIPT -> due = invoice day (overdue 20 days); 400 fieldErrors.dueDate "Pick a due date for custom terms" / "Pick Custom terms to set the due date yourself" / "The due date cannot be before the invoice date"; preview {dueDate today+30, paymentTerm NET_30, source CUSTOMER}' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Terms', paymentTerm: 'NET_30' });
    const mk = (o) => rt.createInvoice(T.admin, { customerId: cu.id, amount: 100, salesPocUserId: U.sales.id, productId: w.product.id, ...o });
    const a = await mk({ paymentTerm: 'NET_45' });
    c.that('NET_45 +45', a.status === 200 && a.json.paymentTerm === 'NET_45' && a.json.dueDate === rt.day(45), brief(a));
    const b = await mk({ paymentTerm: 'CUSTOM', dueDate: rt.day(10) });
    c.that('CUSTOM +10', b.status === 200 && b.json.paymentTerm === 'CUSTOM' && b.json.dueDate === rt.day(10), brief(b));
    const d = await mk({ dueDate: rt.day(12) });
    c.that('date only -> CUSTOM +12', d.status === 200 && d.json.paymentTerm === 'CUSTOM' && d.json.dueDate === rt.day(12), brief(d));
    const e = await mk({ paymentTerm: 'DUE_ON_RECEIPT', invoiceDate: `${rt.day(-20)}T10:00:00Z` });
    c.that('back-dated due on receipt: overdue 20 days', e.status === 200 && e.json.dueDate === rt.day(-20) && e.json.overdue === true && e.json.daysOverdue === 20, brief(e));
    const f = await mk({ paymentTerm: 'CUSTOM' });
    c.that('CUSTOM without date 400', f.status === 400 && f.json.fieldErrors && /custom terms/.test(f.json.fieldErrors.dueDate || ''), brief(f));
    const g = await mk({ paymentTerm: 'NET_30', dueDate: rt.day(3) });
    c.that('NET_30 with date 400', g.status === 400 && g.json.fieldErrors && /Custom terms/.test(g.json.fieldErrors.dueDate || ''), brief(g));
    const h = await mk({ dueDate: rt.day(-5) });
    c.that('due before invoice date 400', h.status === 400 && h.json.fieldErrors && /before the invoice date/.test(h.json.fieldErrors.dueDate || ''), brief(h));
    const p = await getJ(T.admin, '/api/invoices/due-date-preview', { customerId: cu.id });
    c.that('preview NET_30 from CUSTOMER', p.status === 200 && p.json.dueDate === rt.day(30) && p.json.paymentTerm === 'NET_30' && p.json.source === 'CUSTOMER', brief(p));
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-03', feature: 'Invoice create validation', kind: 'API', prd: 'B1',
    title: 'Invoice create refuses empty lines, zero quantity, negative price, unknown product/customer, a missing or unassignable Sales POC',
    steps: 'POST /api/invoices with items []; quantity 0; unitPrice -1; productId 999999; customerId 999999; salesPocUserId null; salesPocUserId = viewer',
    expected: '400; 400; 400; 404 "Product not found"; 404 "Customer not found"; 400 "Sales POC is required"; 400 "not assignable as Sales POC"' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'InvVal' });
    const post = (body) => rt.api('POST', '/api/invoices', { token: T.admin, body: { customerId: cu.id, salesPocUserId: U.sales.id, items: [{ productId: w.product.id, quantity: 1, unitPrice: 10 }], ...body } });
    const r1 = await post({ items: [] });
    const r2 = await post({ items: [{ productId: w.product.id, quantity: 0, unitPrice: 10 }] });
    const r3 = await post({ items: [{ productId: w.product.id, quantity: 1, unitPrice: -1 }] });
    const r4 = await post({ items: [{ productId: 999999, quantity: 1, unitPrice: 1 }] });
    const r5 = await post({ customerId: 999999 });
    const r6 = await post({ salesPocUserId: null });
    const r7 = await post({ salesPocUserId: U.viewer.id });
    c.that('empty items 400', r1.status === 400, brief(r1));
    c.that('qty 0 400', r2.status === 400, brief(r2));
    c.that('negative price 400', r3.status === 400, brief(r3));
    c.that('unknown product 404', r4.status === 404, brief(r4));
    c.that('unknown customer 404', r5.status === 404, brief(r5));
    c.that('no Sales POC 400', r6.status === 400 && /required/.test(r6.json && r6.json.message), brief(r6));
    c.that('viewer as Sales POC 400', r7.status === 400 && /not assignable/.test(r7.json && r7.json.message), brief(r7));
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-04', feature: 'Invoice edit', kind: 'API', prd: 'B2',
    title: 'Edit an invoice: notes, due date moved later, Sales POC reassigned; a stale version is refused; the history records each',
    steps: 'fresh invoice (NET_30); PATCH {notes, dueDate +60, paymentTerm CUSTOM, salesPocUserId sales2, version}; PATCH again with the old version; GET /api/audit?entityType=INVOICE',
    expected: '200 applied at once (DEFAULT has no INVOICE_TERMS threshold -> not 202); notes/dueDate/CUSTOM/salesPoc sales2; stale version 409; audit INVOICE_UPDATED and INVOICE_DUE_DATE_CHANGED; sales2 gets POC_ASSIGNED (link /invoices/{id})' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'InvEdit', invoices: [{ amount: 400 }] });
    const inv = cu.invoices[0];
    const r = await rt.patchInvoice(T.admin, inv.id, { notes: 'reg note', dueDate: rt.day(60), paymentTerm: 'CUSTOM', salesPocUserId: U.sales2.id, version: inv.version });
    c.that('200 not held', r.status === 200 && !r.held, brief(r));
    const j = r.json || {};
    c.that('fields applied', j.notes === 'reg note' && j.dueDate === rt.day(60) && j.paymentTerm === 'CUSTOM' && j.salesPoc && j.salesPoc.id === U.sales2.id, { n: j.notes, d: j.dueDate, t: j.paymentTerm, p: j.salesPoc && j.salesPoc.id });
    const stale = await rt.patchInvoice(T.admin, inv.id, { notes: 'again', version: inv.version });
    c.that('stale version 409', stale.status === 409, brief(stale));
    const a = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: inv.id });
    const acts = (a.json || []).map((x) => x.action);
    c.that('audit rows', acts.includes('INVOICE_UPDATED') && acts.includes('INVOICE_DUE_DATE_CHANGED'), acts);
    const s = await rt.list(T.sales2, '/api/notifications');
    c.that('new Sales POC (sales2) notified', (s.json.content || []).some((x) => x.type === 'POC_ASSIGNED' && x.link === `/invoices/${inv.id}`), (s.json.content || []).slice(0, 2).map((x) => x.title));
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-05', feature: 'Invoice cancel', kind: 'API', prd: 'B2',
    title: 'Cancel an unpaid invoice; cancelling again, cancelling one with payments and editing a cancelled one are refused',
    steps: 'fresh customer with invoices A (500) and B (700); POST /api/invoices/{A}/cancel; again; payment 100 on B; cancel B; PATCH A notes',
    expected: 'A 200 CANCELLED (not held: DEFAULT has no INVOICE_CANCEL threshold); again 400 "Invoice already cancelled"; B 400 "Cannot cancel an invoice with payments; refund first"; PATCH A 400 "Cannot edit a cancelled invoice"; customer outstanding drops by 500' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Cancel', invoices: [{ amount: 500 }, { amount: 700 }] });
    const [A, B] = cu.invoices;
    const r = await rt.cancelInvoice(T.admin, A.id);
    c.that('cancel 200 CANCELLED', r.status === 200 && !r.held && r.json.status === 'CANCELLED', brief(r));
    const again = await rt.cancelInvoice(T.admin, A.id);
    c.that('again 400', again.status === 400 && /already cancelled/.test(again.json && again.json.message), brief(again));
    await payment(w, cu, { amount: 100, invoiceIds: [B.id] });
    const b = await rt.cancelInvoice(T.admin, B.id);
    c.that('with payments 400', b.status === 400 && /with payments/.test(b.json && b.json.message), brief(b));
    const e = await rt.patchInvoice(T.admin, A.id, { notes: 'x' });
    c.that('edit cancelled 400', e.status === 400 && /cancelled invoice/.test(e.json && e.json.message), brief(e));
    const g = await getJ(T.admin, `/api/customers/${cu.id}`);
    c.that('outstanding 600 (700-100)', num(g.json.outstanding) === 600, g.json.outstanding);
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-06', feature: 'Invoice ageing', kind: 'API', prd: 'B1',
    title: 'Ageing: overdue flags, days overdue and the customer\'s overdue amount follow the due date',
    steps: 'fresh customer; invoices due +5 (300), -3 (200), -45 (100, invoiceDate -60); GET list filter customerId + overdue:eq:true; GET customer',
    expected: 'overdue flags false/true/true with daysOverdue 0/3/45; overdue filter returns the two; customer overdueAmount 300 and outstanding 600' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Age' });
    const i1 = await invoice(w, cu, { amount: 300, dueDate: rt.day(5), paymentTerm: 'CUSTOM' });
    const i2 = await invoice(w, cu, { amount: 200, dueDate: rt.day(-3), invoiceDate: `${rt.day(-10)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    const i3 = await invoice(w, cu, { amount: 100, dueDate: rt.day(-45), invoiceDate: `${rt.day(-60)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    c.that('flags', i1.overdue === false && i2.overdue === true && i3.overdue === true, [i1.overdue, i2.overdue, i3.overdue]);
    c.that('days 0/3/45', i1.daysOverdue === 0 && i2.daysOverdue === 3 && i3.daysOverdue === 45, [i1.daysOverdue, i2.daysOverdue, i3.daysOverdue]);
    const l = await getJ(T.admin, '/api/invoices', { size: 10, customerId: cu.id, filter: 'overdue:eq:true' });
    c.that('overdue filter -> 2', l.status === 200 && l.json.totalElements === 2 && ids(l).sort().join() === [i2.id, i3.id].sort().join(), brief(l));
    const g = await getJ(T.admin, `/api/customers/${cu.id}`);
    c.that('customer overdue 300 / outstanding 600', num(g.json.overdueAmount) === 300 && num(g.json.outstanding) === 600, { o: g.json.overdueAmount, out: g.json.outstanding });
    return done(c, { codeRef: BE.inv });
  });

  await tcase({ id: 'REG-INV-07', feature: 'Invoice list', kind: 'API', prd: 'B1',
    title: 'Invoices list: filters (status in, total between, customerId), sort by total, paging, summary tiles, export CSV',
    steps: 'fresh customer with invoices 100,200,300,400 (cancel the 400); GET /api/invoices?customerId&filter=status:in:UNPAID&sort=total,desc; filter total:between:150,350; size 10 page 0; GET /api/invoices/summary?customerId; POST /api/invoices/export {filters:[customerId:eq]}',
    expected: 'UNPAID rows 300,200,100 in that order; between -> 200,300; summary count 4, cancelledCount 1, unpaidCount 3, totalBilled 600 (cancelled excluded) or 1000 (documented behaviour of tiles), outstanding 600; export header "Invoice #,Customer,..." + 4 rows' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'List', invoices: [{ amount: 100 }, { amount: 200 }, { amount: 300 }, { amount: 400 }] });
    await rt.cancelInvoice(T.admin, cu.invoices[3].id);
    const l = await getJ(T.admin, '/api/invoices', { size: 10, customerId: cu.id, filter: 'status:in:UNPAID', sort: 'total,desc' });
    c.that('UNPAID 300,200,100', l.status === 200 && (l.json.content || []).map((x) => num(x.total)).join() === '300,200,100', brief(l));
    const b = await getJ(T.admin, '/api/invoices', { size: 10, customerId: cu.id, filter: 'total:between:150,350', sort: 'total,asc' });
    c.that('between 200,300', b.status === 200 && (b.json.content || []).map((x) => num(x.total)).join() === '200,300', brief(b));
    const s = await getJ(T.admin, '/api/invoices/summary', { customerId: cu.id });
    c.that('summary counts', s.status === 200 && s.json.count === 4 && s.json.cancelledCount === 1 && s.json.unpaidCount === 3 && num(s.json.outstanding) === 600, s.json);
    const e = await rt.api('POST', '/api/invoices/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`customerId:eq:${cu.id}`] } });
    const lines = (e.text || '').trim().split('\n');
    c.that('export header + 4 rows', e.status === 200 && /^Invoice #,Customer,Date,Due date,Total,Paid,Balance,Status,Overdue/.test(lines[0]) && lines.length === 5, { status: e.status, lines: lines.length, head: lines[0] });
    return done(c, { codeRef: BE.invCtl });
  });

  await tcase({ id: 'REG-INV-08', feature: 'Invoice bulk', kind: 'API', prd: 'B2',
    title: 'Bulk: CANCEL skips a paid invoice and cancels the rest (pending [] with no threshold); REASSIGN_SALES_POC moves the owner',
    steps: 'fresh customer, invoices X(100), Y(200 with a payment), Z(300); POST /api/invoices/bulk {CANCEL, ids [X,Y]}; POST /bulk {REASSIGN_SALES_POC, ids [Z], params.userId admin}; bulk FOO',
    expected: 'CANCEL: succeeded [X], skipped [Y] with a reason, pending []; REASSIGN: succeeded [Z] and Z.salesPoc admin; unknown action 400' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'BulkInv', invoices: [{ amount: 100 }, { amount: 200 }, { amount: 300 }] });
    const [X, Y, Z] = cu.invoices;
    await payment(w, cu, { amount: 50, invoiceIds: [Y.id] });
    const r = await rt.api('POST', '/api/invoices/bulk', { token: T.admin, body: { action: 'CANCEL', ids: [X.id, Y.id] } });
    c.that('X succeeded', r.status === 200 && r.json.succeeded.includes(X.id), brief(r));
    c.that('Y skipped with reason', r.json && (r.json.skipped || []).some((s) => s.id === Y.id && s.reason), r.json && r.json.skipped);
    c.that('pending []', r.json && Array.isArray(r.json.pending) && r.json.pending.length === 0, r.json && r.json.pending);
    const g = await getJ(T.admin, `/api/invoices/${X.id}`);
    c.that('X cancelled', g.json.status === 'CANCELLED', g.json.status);
    const ra = await rt.api('POST', '/api/invoices/bulk', { token: T.admin, body: { action: 'REASSIGN_SALES_POC', ids: [Z.id], params: { userId: U.admin.id } } });
    const gz = await getJ(T.admin, `/api/invoices/${Z.id}`);
    c.that('reassign ok', ra.status === 200 && ra.json.succeeded.includes(Z.id) && gz.json.salesPoc.id === U.admin.id, brief(ra));
    const bad = await rt.api('POST', '/api/invoices/bulk', { token: T.admin, body: { action: 'FOO', ids: [Z.id] } });
    c.that('unknown action 400', bad.status === 400, brief(bad));
    return done(c, { codeRef: BE.invCtl });
  });

  await tcase({ id: 'REG-INV-09', feature: 'POC book (invoices)', kind: 'API', prd: 'B1',
    title: 'A Sales POC sees only his own invoices (today\'s locked chip), is 404 on others, can create for a customer in his book but not outside it',
    steps: 'customer K with invoice I1 (Sales POC sales) and I2 (Sales POC admin); customer L with only admin-owned invoice; sales GET /api/invoices?customerId=K; GET I2; sales POST /api/invoices for K (Sales POC himself); for L',
    expected: 'DESIGN §5.6.4/§5.6.6: list shows I1 not I2, lockedFilters ["salesPocUserId:eq:<sales>"]; GET I2 404; create for K 200; create for L 404 "Customer not found" (book-aware, SC-12)' }, async () => {
    const c = checks();
    const K = await customer(w, { prefix: 'SBook', invoices: [{ amount: 110 }, { amount: 120, sales: U.admin.id }] });
    const L = await customer(w, { prefix: 'SNot', invoices: [{ amount: 130, sales: U.admin.id }] });
    const l = await getJ(T.sales, '/api/invoices', { size: 50, customerId: K.id });
    c.that('only I1', l.status === 200 && ids(l).join() === String(K.invoices[0].id), brief(l));
    c.that('locked chip', l.json && JSON.stringify(l.json.lockedFilters) === JSON.stringify([`salesPocUserId:eq:${U.sales.id}`]), l.json && l.json.lockedFilters);
    const g = await getJ(T.sales, `/api/invoices/${K.invoices[1].id}`);
    c.that('I2 404', g.status === 404, brief(g));
    const k = await rt.createInvoice(T.sales, { customerId: K.id, amount: 50, productId: w.product.id, salesPocUserId: U.sales.id });
    c.that('create in book 200', k.status === 200 && k.json.salesPoc.id === U.sales.id, brief(k));
    const lo = await rt.createInvoice(T.sales, { customerId: L.id, amount: 50, productId: w.product.id, salesPocUserId: U.sales.id });
    c.that('create outside book 404', lo.status === 404, brief(lo));
    return done(c, { codeRef: BE.scope });
  });

  // ================================================================== PAYMENTS
  await tcase({ id: 'REG-PAY-01', feature: 'Payments & allocation', kind: 'API', prd: 'B2',
    title: 'Record a payment against a chosen invoice: it is applied, the invoice becomes Partially paid, the rest untouched',
    steps: 'fresh customer, invoices A 1000 and B 500; POST /api/payments {customerId, amount 400, method CASH, invoiceIds [B], collectionPocUserId coll}',
    expected: '200 (no PAYMENT_RECORD threshold in DEFAULT); payment ACTIVE, invoices [{B, allocatedAmount 400}], creditApplied 0; B paid 400 balance 100 PARTIALLY_PAID; A untouched; POC notified' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Pay', invoices: [{ amount: 1000 }, { amount: 500 }] });
    const [A, B] = cu.invoices;
    const r = await rt.createPayment(T.admin, { customerId: cu.id, amount: 400, method: 'CASH', invoiceIds: [B.id], collectionPocUserId: U.coll.id });
    const j = r.json || {};
    c.that('200 not held', r.status === 200 && !r.held, brief(r));
    c.that('allocated 400 to B', j.status === 'ACTIVE' && (j.invoices || []).length === 1 && j.invoices[0].id === B.id && num(j.invoices[0].allocatedAmount) === 400 && num(j.creditApplied) === 0, j.invoices);
    const gb = await getJ(T.admin, `/api/invoices/${B.id}`);
    const ga = await getJ(T.admin, `/api/invoices/${A.id}`);
    c.that('B partially paid', gb.json.status === 'PARTIALLY_PAID' && num(gb.json.paidAmount) === 400 && num(gb.json.balance) === 100, { s: gb.json.status, p: gb.json.paidAmount });
    c.that('A untouched', ga.json.status === 'UNPAID' && num(ga.json.paidAmount) === 0, ga.json.status);
    const n = await rt.list(T.coll, '/api/notifications');
    c.that('coll notified of the payment', (n.json.content || []).some((x) => x.link === `/payments/${j.id}`), (n.json.content || []).slice(0, 2).map((x) => x.link));
    return done(c, { codeRef: BE.pay });
  });

  await tcase({ id: 'REG-PAY-02', feature: 'Payments & allocation', kind: 'API', prd: 'B1',
    title: 'Without chosen invoices a payment pays the oldest first; overpayment becomes customer credit; the next invoice is paid from credit',
    steps: 'fresh customer, invoices Old (300, dated -20d) and New (200); payment 700 without invoiceIds; GET /api/payments/credits/{customer}; new invoice 150; GET customer',
    expected: 'Old then New fully paid; creditApplied 200 and customer credit 200; credits endpoint 200; new invoice created FULLY_PAID from credit (paidAmount 150) and credit becomes 50; audit on the new invoice has PAYMENT_APPLIED "Paid from customer credit"' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Credit' });
    const Old = await invoice(w, cu, { amount: 300, invoiceDate: `${rt.day(-20)}T08:00:00Z` });
    const New = await invoice(w, cu, { amount: 200 });
    const p = await payment(w, cu, { amount: 700 });
    const alloc = (p.invoices || []).map((i) => `${i.id}:${num(i.allocatedAmount)}`);
    c.that('both paid, oldest first', alloc.join() === `${Old.id}:300,${New.id}:200`, alloc);
    c.that('creditApplied 200, customer credit 200', num(p.creditApplied) === 200 && num(p.customerCreditBalance) === 200, { ca: p.creditApplied, cb: p.customerCreditBalance });
    const cr = await getJ(T.admin, `/api/payments/credits/${cu.id}`);
    c.that('credits endpoint 200', cr.status === 200 && num(cr.json.creditBalance) === 200, brief(cr));
    const n = await invoice(w, cu, { amount: 150 });
    c.that('new invoice paid from credit', n.status === 'FULLY_PAID' && num(n.paidAmount) === 150, { s: n.status, p: n.paidAmount });
    const g = await getJ(T.admin, `/api/customers/${cu.id}`);
    c.that('credit now 50', num(g.json.creditBalance) === 50, g.json.creditBalance);
    const a = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: n.id });
    c.that('audit PAYMENT_APPLIED from credit', (a.json || []).some((x) => x.action === 'PAYMENT_APPLIED' && /credit/i.test(x.reason || '')), (a.json || []).map((x) => `${x.action}:${x.reason}`));
    return done(c, { codeRef: BE.credit });
  });

  await tcase({ id: 'REG-PAY-03', feature: 'Payments validation', kind: 'API', prd: 'B1',
    title: 'Payment validation: zero amount, 3 decimals, another customer\'s invoice, unknown invoice, missing Collection POC',
    steps: 'POST /api/payments amount 0; amount 1.234; invoiceIds [other customer invoice]; invoiceIds [999999]; collectionPocUserId null',
    expected: '400; 400 (cents); 400 "does not belong to this customer"; 404 "Invoice not found"; 400 "Collection POC is required"; nothing recorded' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'PayVal', invoices: [{ amount: 100 }] });
    const other = await customer(w, { prefix: 'PayOther', invoices: [{ amount: 100 }] });
    const post = (o) => rt.createPayment(T.admin, { customerId: cu.id, amount: 10, collectionPocUserId: U.coll.id, ...o });
    const a = await post({ amount: 0 });
    const b = await post({ amount: 1.234 });
    const d = await post({ invoiceIds: [other.invoices[0].id] });
    const e = await post({ invoiceIds: [999999] });
    const f = await post({ collectionPocUserId: null });
    c.that('amount 0 400', a.status === 400, brief(a));
    c.that('3 decimals 400', b.status === 400, brief(b));
    c.that('foreign invoice 400', d.status === 400 && /does not belong/.test(d.json && d.json.message), brief(d));
    c.that('unknown invoice 404', e.status === 404, brief(e));
    c.that('no Collection POC 400', f.status === 400 && /required/.test(f.json && f.json.message), brief(f));
    const l = await getJ(T.admin, '/api/payments', { size: 10, filter: `customerId:eq:${cu.id}` });
    c.that('no payment recorded', l.json && l.json.totalElements === 0, l.json && l.json.totalElements);
    return done(c, { codeRef: BE.pay });
  });

  await tcase({ id: 'REG-PAY-04', feature: 'Payments edit & list', kind: 'API', prd: 'B1',
    title: 'Payment notes edit, list filter/sort, summary tiles, export CSV and bulk REASSIGN_COLLECTION_POC',
    steps: 'fresh customer, invoice 1000, payments 100 and 250; PATCH /api/payments/{p1} {notes}; GET /api/payments?filter=customerId:eq&sort=amount,desc; GET /api/payments/summary; POST /api/payments/export; POST /api/payments/bulk {REASSIGN_COLLECTION_POC, ids [p2], params.userId admin}',
    expected: 'PATCH 200 notes; list 250,100; summary totalCollected includes 350 (count >= 2); export 200 CSV with 2 rows for the filter; bulk succeeded [p2] and p2.collectionPoc admin' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'PayList', invoices: [{ amount: 1000 }] });
    const p1 = await payment(w, cu, { amount: 100 });
    const p2 = await payment(w, cu, { amount: 250 });
    const e1 = await rt.api('PATCH', `/api/payments/${p1.id}`, { token: T.admin, body: { notes: 'reg notes' } });
    c.that('PATCH notes 200', e1.status === 200 && e1.json.notes === 'reg notes', brief(e1));
    const f = `customerId:eq:${cu.id}`;
    const l = await getJ(T.admin, '/api/payments', { size: 10, filter: f, sort: 'amount,desc' });
    c.that('list 250,100', l.status === 200 && (l.json.content || []).map((x) => num(x.amount)).join() === '250,100', brief(l));
    const s = await getJ(T.admin, '/api/payments/summary', { filter: f });
    c.that('summary 350 / 2', s.status === 200 && num(s.json.totalCollected) === 350 && s.json.count === 2, s.json);
    const e = await rt.api('POST', '/api/payments/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [f] } });
    c.that('export 2 rows', e.status === 200 && (e.text || '').trim().split('\n').length === 3, brief(e));
    const b = await rt.api('POST', '/api/payments/bulk', { token: T.admin, body: { action: 'REASSIGN_COLLECTION_POC', ids: [p2.id], params: { userId: U.admin.id } } });
    const g = await getJ(T.admin, `/api/payments/${p2.id}`);
    c.that('reassign ok', b.status === 200 && b.json.succeeded.includes(p2.id) && g.json.collectionPoc.id === U.admin.id, brief(b));
    return done(c, { codeRef: BE.pay });
  });

  await tcase({ id: 'REG-PAY-05', feature: 'POC book (payments)', kind: 'API', prd: 'B1',
    title: 'A book-only Collection POC records a payment for a customer in his book, sees his own payments only, and cannot pay a customer outside his book',
    steps: 'customer K (Collection seat collbook) with invoice 300; customer L (seat admin) with invoice 300 and an admin payment; collbook POST /api/payments for K (collectionPocUserId collbook); collbook GET /api/payments; collbook POST for L',
    expected: 'DESIGN §5.6.4/§5.6.6: K 200 and K\'s invoice paid; list lockedFilters ["collectionPocUserId:eq:<collbook>"] and excludes L\'s payment; L 404 "Customer not found" (book-aware create, SC-12)' }, async () => {
    const c = checks();
    const K = await customer(w, { prefix: 'CBook', collection: U.collbook.id, invoices: [{ amount: 300 }] });
    const L = await customer(w, { prefix: 'CNot', collection: U.admin.id, invoices: [{ amount: 300 }] });
    const lp = await payment(w, L, { amount: 50, coll: U.admin.id });
    const k = await rt.createPayment(T.collbook, { customerId: K.id, amount: 300, collectionPocUserId: U.collbook.id });
    c.that('K payment 200', k.status === 200 && (k.json.invoices || [])[0] && k.json.invoices[0].status === 'FULLY_PAID', brief(k));
    const l = await rt.list(T.collbook, '/api/payments');
    c.that('locked chip', JSON.stringify(l.json.lockedFilters) === JSON.stringify([`collectionPocUserId:eq:${U.collbook.id}`]), l.json.lockedFilters);
    c.that('L payment not listed, K payment listed', !ids(l).includes(lp.id) && ids(l).includes(k.json && k.json.id));
    const x = await rt.createPayment(T.collbook, { customerId: L.id, amount: 10, collectionPocUserId: U.collbook.id });
    c.that('L 404', x.status === 404, brief(x));
    return done(c, { codeRef: BE.scope });
  });

  // ================================================================== PROMISES
  await tcase({ id: 'REG-PRM-01', feature: 'Promises', kind: 'API', prd: 'B2',
    title: 'Create a promise, edit it, and see a payment keep it; the list and summary count it',
    steps: 'fresh customer, invoice 500; POST /api/promises {customerId, amount 300, promisedDate +7, collectionPocUserId coll, invoiceIds [inv]}; PUT {amount 250, promisedDate +9}; payment 250 with promiseIds [p]; GET promise; GET /api/promises/summary',
    expected: 'create 200 OPEN (not held); PUT 200 amount 250 date +9; after the payment status KEPT, fulfilledAmount 250, remaining 0; summary counts it among kept' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Prom', invoices: [{ amount: 500 }] });
    const r = await rt.createPromise(T.admin, { customerId: cu.id, amount: 300, promisedDate: rt.day(7), collectionPocUserId: U.coll.id, invoiceIds: [cu.invoices[0].id] });
    c.that('create 200 OPEN', r.status === 200 && !r.held && r.json.status === 'OPEN' && num(r.json.remainingAmount) === 300, brief(r));
    const id = r.json && r.json.id;
    const u = await rt.api('PUT', `/api/promises/${id}`, { token: T.admin, body: { amount: 250, promisedDate: rt.day(9), collectionPocUserId: U.coll.id, invoiceIds: [cu.invoices[0].id] } });
    c.that('PUT 200', u.status === 200 && num(u.json.amount) === 250 && u.json.promisedDate === rt.day(9), brief(u));
    const p = await rt.createPayment(T.admin, { customerId: cu.id, amount: 250, invoiceIds: [cu.invoices[0].id], collectionPocUserId: U.coll.id, promiseIds: [id] });
    c.that('payment 200', p.status === 200, brief(p));
    const g = await getJ(T.admin, `/api/promises/${id}`);
    c.that('KEPT', g.json.status === 'KEPT' && num(g.json.fulfilledAmount) === 250 && num(g.json.remainingAmount) === 0, { s: g.json.status, f: g.json.fulfilledAmount });
    const s = await getJ(T.admin, '/api/promises/summary');
    c.that('summary 200', s.status === 200 && s.json && typeof s.json === 'object', brief(s));
    return done(c, { codeRef: BE.prom });
  });

  await tcase({ id: 'REG-PRM-02', feature: 'Promises', kind: 'API', prd: 'B1',
    title: 'Promise override and cancel: override to BROKEN with a reason, clear it, then cancel; validation of amount and date',
    steps: 'fresh promise 100; POST /override {status BROKEN, reason}; DELETE /override; POST /cancel {reason}; POST /api/promises amount -5; no promisedDate',
    expected: 'override 200 status BROKEN statusOverridden true; clear 200 statusOverridden false; cancel 200 CANCELLED; invalid 400s' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'PromOv', invoices: [{ amount: 500 }] });
    const r = await rt.createPromise(T.admin, { customerId: cu.id, amount: 100, collectionPocUserId: U.coll.id });
    const id = r.json.id;
    const o = await rt.api('POST', `/api/promises/${id}/override`, { token: T.admin, body: { status: 'BROKEN', reason: 'reg' } });
    c.that('override 200 BROKEN', o.status === 200 && o.json.status === 'BROKEN' && o.json.statusOverridden === true, brief(o));
    const cl = await rt.api('DELETE', `/api/promises/${id}/override`, { token: T.admin });
    c.that('clear 200', cl.status === 200 && cl.json.statusOverridden === false, brief(cl));
    const x = await rt.api('POST', `/api/promises/${id}/cancel`, { token: T.admin, body: { reason: 'reg' } });
    c.that('cancel 200 CANCELLED', x.status === 200 && x.json.status === 'CANCELLED', brief(x));
    const bad = await rt.api('POST', '/api/promises', { token: T.admin, body: { customerId: cu.id, amount: -5, promisedDate: rt.day(3), collectionPocUserId: U.coll.id } });
    const nod = await rt.api('POST', '/api/promises', { token: T.admin, body: { customerId: cu.id, amount: 5, collectionPocUserId: U.coll.id } });
    c.that('negative 400', bad.status === 400, brief(bad));
    c.that('no date 400', nod.status === 400, brief(nod));
    return done(c, { codeRef: BE.prom });
  });
};
