// Re-runs each failing API case once with fresh data, to rule out interference from other testers.
const rt = require('../lib.js');
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 200)}`;
(async () => {
  const A = await rt.adminToken();
  const c1 = await rt.createCustomer(A, 'cprr');
  const c2 = await rt.createCustomer(A, 'cprr');
  const cs1 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cprr');
  const cs2 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cprr');
  const sales = await rt.createStaff(A, 'SALES_POC', 'cprr');
  const off = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cprr');
  await rt.api('PUT', `/api/users/${off.id}`, { token: A, body: { active: false } });

  // POC-010 bad pocType on single add
  let r = await rt.api('POST', `/api/customers/${c1.id}/pocs`, { token: A, body: { pocType: 'FOO', userId: cs1.id } });
  console.log('POC-010 badType', snip(r));
  // malformed JSON body on customers create
  r = await rt.api('POST', '/api/customers', { token: A, body: undefined, headers: { 'Content-Type': 'application/json' } });
  const raw = await fetch(rt.API + '/api/customers', { method: 'POST', headers: { Authorization: 'Bearer ' + A, 'Content-Type': 'application/json' }, body: '{bad json' });
  console.log('malformed JSON body ->', raw.status, (await raw.text()).slice(0, 160));
  // POC-014
  r = await rt.api('GET', '/api/pocs/assignable?type=bogus', { token: A }); console.log('POC-014 bogus', snip(r));
  r = await rt.api('GET', '/api/pocs/assignable', { token: A }); console.log('POC-014 missing', snip(r));
  // POC-017
  await rt.api('POST', `/api/customers/${c1.id}/pocs`, { token: A, body: { pocType: 'SUCCESS', userId: cs1.id } });
  const s2 = await rt.api('POST', `/api/customers/${c1.id}/pocs`, { token: A, body: { pocType: 'SUCCESS', userId: cs2.id, primary: true } });
  await rt.api('DELETE', `/api/customers/${c1.id}/pocs/${s2.json.id}`, { token: A });
  const seats = (await rt.api('GET', `/api/customers/${c1.id}/pocs`, { token: A })).json;
  const au = (await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${c1.id}`, { token: A })).json;
  console.log('POC-017 seats after', seats.map((p) => `${p.user.username}${p.primary ? '*' : ''}`));
  console.log('POC-017 audit', au.filter((e) => e.action.startsWith('POC_')).map((e) => `${e.action} before=${e.beforeJson} after=${e.afterJson}`));
  // BLK-003
  r = await rt.api('POST', '/api/customers/bulk', { token: A, body: { action: 'ADD_POC', ids: [c2.id], params: { userId: cs1.id, pocType: 'foo' } } }); console.log('BLK-003 foo', snip(r));
  r = await rt.api('POST', '/api/customers/bulk', { token: A, body: { action: 'ADD_POC', ids: [c2.id], params: { userId: 'abc', pocType: 'SUCCESS' } } }); console.log('BLK-003 abc', snip(r));
  // BLK-004
  r = await rt.api('POST', '/api/customers/bulk', { token: A, body: { action: 'ADD_POC', ids: [c1.id, c2.id], params: { userId: sales.id, pocType: 'SALES' } } }); console.log('BLK-004 sales', snip(r));
  r = await rt.api('POST', '/api/customers/bulk', { token: A, body: { action: 'ADD_POC', ids: [c1.id, c2.id], params: { userId: off.id, pocType: 'SUCCESS' } } }); console.log('BLK-004 inactive', snip(r));
  // BLK-006: role with CUSTOMER_MANAGE and POC_VIEW but no POC_ASSIGN
  const role = await rt.api('POST', '/api/roles', { token: A, body: { name: rt.uniq('CPRRNA').toUpperCase(), privileges: ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'POC_VIEW'] } });
  const u = await rt.api('POST', '/api/users', { token: A, body: { username: rt.uniq('cprrna'), password: rt.PASSWORD, roleId: role.json.id, active: true } });
  const t = await rt.login(u.json.username, rt.PASSWORD);
  r = await rt.api('POST', '/api/customers/bulk', { token: t, body: { action: 'ADD_POC', ids: [c2.id], params: { userId: cs1.id, pocType: 'SUCCESS' } } }); console.log('BLK-006 no POC_ASSIGN bulk', snip(r));
  r = await rt.api('POST', `/api/customers/${c2.id}/pocs`, { token: t, body: { pocType: 'SUCCESS', userId: cs1.id } }); console.log('BLK-006 same user single add', snip(r));
  // BLK-007
  r = await rt.api('POST', '/api/customers/bulk', { token: A, body: { action: 'ADD_POC', ids: [c2.id, 99999999], params: { userId: cs2.id, pocType: 'SUCCESS' } } }); console.log('BLK-007', snip(r));
  // PRD-002
  r = await rt.api('POST', '/api/products', { token: A, body: { name: rt.uniq('cprr P'), price: 'abc' } }); console.log('PRD-002 string price', snip(r));
  // PRD-007
  const tag = rt.uniq('cprrP');
  const pa = await rt.api('POST', '/api/products', { token: A, body: { name: `${tag} a`, price: 5 } });
  const pb = await rt.api('POST', '/api/products', { token: A, body: { name: `${tag} b`, price: 50 } });
  const pc = await rt.api('POST', '/api/products', { token: A, body: { name: `${tag} c`, price: 1 } });
  r = await rt.api('POST', '/api/products/export', { token: A, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`name:contains:${tag}`], sort: 'price,desc' } });
  console.log('PRD-007 export sort=price,desc ->', JSON.stringify(r.text));
  r = await rt.api('POST', '/api/products/export', { token: A, body: { action: 'EXPORT', ids: [pc.json.id, pa.json.id, pb.json.id], sort: 'name,asc' } });
  console.log('PRD-007 export ids sort=name,asc ->', JSON.stringify(r.text));
  // PRD-009
  await rt.api('PUT', `/api/products/${pa.json.id}`, { token: A, body: { name: `${tag} a`, price: 5, active: false } });
  r = await rt.api('POST', '/api/invoices', { token: A, body: { customerId: c2.id, salesPocUserId: sales.id, items: [{ productId: pa.json.id, quantity: 1 }] } });
  console.log('PRD-009 invoice with inactive product', r.status, r.json && r.json.invoiceNumber);
  if (r.status === 200) { const c = await rt.api('POST', `/api/invoices/${r.json.id}/cancel`, { token: A, body: { reason: 'rt cleanup' } }); console.log('  cleanup cancel', c.status); }
})().catch((e) => { console.error(e); process.exit(1); });
