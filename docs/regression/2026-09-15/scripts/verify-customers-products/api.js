// Independent verification of reported API failures (customers-products area).
const rt = require('../lib.js');
const A_ = { token: null };
const log = (id, ...a) => console.log(`[${id}]`, ...a.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))));
const short = (r) => ({ status: r.status, body: (r.text || '').slice(0, 260) });

(async () => {
  const A = await rt.adminToken();
  A_.token = A;
  const api = (m, p, body, token = A) => rt.api(m, p, { token, body });

  // ---- POC-008 ----
  log('POC-008 bogus', short(await api('GET', '/api/pocs/assignable?type=bogus')));
  log('POC-008 missing', short(await api('GET', '/api/pocs/assignable')));
  log('POC-008 limit=abc', short(await api('GET', '/api/pocs/assignable?type=SUCCESS&limit=abc')));
  log('POC-008 valid', (await api('GET', '/api/pocs/assignable?type=SUCCESS&limit=2')).status);

  // shared fixtures
  const c1 = await rt.createCustomer(A, 'vcp');
  const c2 = await rt.createCustomer(A, 'vcp');
  const cs1 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'vcpcs');
  const cs2 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'vcpcs');
  const sales = await rt.createStaff(A, 'SALES_POC', 'vcpsales');
  const inact = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'vcpinact');
  log('fixtures', { c1: c1.id, c2: c2.id, cs1: cs1.id, cs2: cs2.id, sales: sales.id, inact: inact.id });
  log('deactivate', (await api('PUT', `/api/users/${inact.id}`, { active: false })).status);

  // ---- POC-010 ----
  const a1 = await api('POST', `/api/customers/${c1.id}/pocs`, { pocType: 'SUCCESS', userId: cs1.id });
  const a2 = await api('POST', `/api/customers/${c1.id}/pocs`, { pocType: 'SUCCESS', userId: cs2.id, primary: true });
  log('POC-010 add', a1.status, a1.json?.primary, a2.status, a2.json?.primary);
  let roster = (await api('GET', `/api/customers/${c1.id}/pocs`)).json;
  log('POC-010 roster after add2', roster.map((p) => `${p.user.username}${p.primary ? '*' : ''}`));
  const del = await api('DELETE', `/api/customers/${c1.id}/pocs/${a2.json.id}`);
  roster = (await api('GET', `/api/customers/${c1.id}/pocs`)).json;
  log('POC-010 delete', del.status, 'roster after', roster.map((p) => `${p.user.username}${p.primary ? '*' : ''}`));
  const audit = await api('GET', `/api/audit?entityType=CUSTOMER&entityId=${c1.id}`);
  log('POC-010 audit', audit.status, (audit.json || []).map((e) => ({ action: e.action, before: e.beforeJson, after: e.afterJson, reason: e.reason })));
  if (audit.json && audit.json[0]) log('POC-010 audit keys', Object.keys(audit.json[0]));

  // ---- BLK-003 ----
  log('BLK-003 pocType=foo', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c2.id], params: { pocType: 'foo', userId: cs1.id } })));
  log('BLK-003 userId=abc', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c2.id], params: { pocType: 'SUCCESS', userId: 'abc' } })));

  // ---- BLK-004 ----
  log('BLK-004 SALES', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c1.id, c2.id], params: { pocType: 'SALES', userId: sales.id } })));
  log('BLK-004 inactive', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c1.id, c2.id], params: { pocType: 'SUCCESS', userId: inact.id } })));
  log('BLK-004 unknown user', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c1.id, c2.id], params: { pocType: 'SUCCESS', userId: 99999999 } })));
  log('BLK-004 already-seated (legit skip)', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c1.id], params: { pocType: 'SUCCESS', userId: cs1.id } })));

  // ---- BLK-006 ----
  const roleName = rt.uniq('VCPROLE').toUpperCase();
  const role = await api('POST', '/api/roles', { name: roleName, description: 'verify', privileges: ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'POC_VIEW'] });
  log('BLK-006 role', role.status, role.json?.privileges);
  const uname = rt.uniq('vcpnoassign');
  const u = await api('POST', '/api/users', { username: uname, email: `${uname}@rt.local`, fullName: uname, password: rt.PASSWORD, roleId: role.json.id, active: true });
  const T = await rt.login(uname, rt.PASSWORD);
  log('BLK-006 bulk', short(await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c2.id], params: { pocType: 'SUCCESS', userId: cs2.id } }, T)));
  log('BLK-006 single', short(await api('POST', `/api/customers/${c2.id}/pocs`, { pocType: 'SUCCESS', userId: cs2.id }, T)));
  log('BLK-006 c2 seats after', (await api('GET', `/api/customers/${c2.id}/pocs`)).json.map((p) => p.user.username));

  // ---- BLK-007 ----
  const b7 = await api('POST', '/api/customers/bulk', { action: 'ADD_POC', ids: [c2.id, 99999999], params: { pocType: 'SUCCESS', userId: cs2.id } });
  log('BLK-007 customers', short(b7));
  const p0 = (await api('POST', '/api/products', { name: rt.uniq('vcpprod'), price: 1 })).json;
  const b7p = await api('POST', '/api/products/bulk', { action: 'DEACTIVATE', ids: [p0.id, 99999999] });
  log('BLK-007 products', short(b7p));

  // ---- VAL-001 ----
  log('VAL-001 pocType FOO', short(await api('POST', `/api/customers/${c2.id}/pocs`, { pocType: 'FOO', userId: cs1.id })));
  log('VAL-001 price abc', short(await api('POST', '/api/products', { name: rt.uniq('vcpp'), price: 'abc' })));
  const bad = await fetch(rt.API + '/api/customers', { method: 'POST', headers: { Authorization: 'Bearer ' + A, 'Content-Type': 'application/json' }, body: '{bad json' });
  log('VAL-001 malformed', { status: bad.status, body: (await bad.text()).slice(0, 260) });

  // ---- PRD-005 ----
  const TAG = rt.uniq('vcpsort');
  const mk = async (n, price) => (await api('POST', '/api/products', { name: `${TAG} ${n}`, price })).json;
  const pa = await mk('a', 5); const pb = await mk('b', 50); const pc = await mk('c', 1);
  const f = [`name:contains:${TAG}`];
  const ex1 = await api('POST', '/api/products/export', { selectAllMatchingFilter: true, filters: f, sort: 'price,desc' });
  log('PRD-005 export price,desc', ex1.status, ex1.text.trim().split('\n'));
  const ex2 = await api('POST', '/api/products/export', { ids: [pc.id, pb.id, pa.id], filters: f, sort: 'name,desc' });
  log('PRD-005 export ids name,desc', ex2.status, ex2.text.trim().split('\n'));
  const lst = await api('GET', `/api/products?sort=price,desc&filter=${encodeURIComponent(f[0])}`);
  log('PRD-005 list price,desc', lst.json.content.map((p) => `${p.name} ${p.price}`));
  // customer export comparison
  const CT = rt.uniq('vcpcsort');
  const cc = [];
  for (const n of ['b', 'c', 'a']) cc.push((await api('POST', '/api/customers', { name: `${CT} ${n}`, username: `${CT}-${n}`, password: rt.PASSWORD })).json);
  const cex = await api('POST', '/api/customers/export', { selectAllMatchingFilter: true, filters: [`name:contains:${CT}`], sort: 'name,desc' });
  log('PRD-005 customer export name,desc', cex.status, cex.text.trim().split('\n').map((l) => l.slice(0, 60)));

  // ---- PRD-007 ----
  const inactProd = (await api('POST', '/api/products', { name: rt.uniq('vcpinactprod'), price: 7, active: false })).json;
  log('PRD-007 product', inactProd.id, inactProd.active);
  const inv = await api('POST', '/api/invoices', { customerId: c2.id, salesPocUserId: sales.id, items: [{ productId: inactProd.id, quantity: 1 }] });
  log('PRD-007 invoice', inv.status, inv.json?.invoiceNumber, inv.json?.status, inv.text.slice(0, 200));
  if (inv.status === 200) {
    const cxl = await api('POST', `/api/invoices/${inv.json.id}/cancel`, { reason: 'verify cleanup' });
    log('PRD-007 cleanup cancel', cxl.status, cxl.text.slice(0, 120));
  }
})().catch((e) => { console.error('FAILED', e); process.exit(1); });
