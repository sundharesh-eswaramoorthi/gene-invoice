// Re-runs every failing case once more (shared server) before it is recorded.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 500)); };
const P = 'permsc';

(async () => {
  const admin = await rt.adminToken();
  const CA = await rt.login(st.cashierRt.username, rt.PASSWORD);
  const SC = await rt.cashierToken(); // seeded cashier, read-only usage (export is a read)
  const S1 = await rt.login(st.s1.username, rt.PASSWORD);
  const C1 = await rt.login(st.c1.username, rt.PASSWORD);
  const A = await rt.login(st.custA.username, rt.PASSWORD);

  // R1 exports beyond VIEW privilege
  const r1 = {};
  for (const [who, tok] of [['cashierRt', CA], ['seededCashier', SC], ['s1', S1], ['c1', C1]]) {
    for (const e of ['users', 'roles', 'disputes', 'products']) {
      const r = await rt.api('POST', `/api/${e}/export`, { token: tok, body: { selectAllMatchingFilter: true } });
      r1[`${who}.${e}`] = r.status === 200 ? `200 rows=${r.text.trim().split('\n').length - 1} first=${(r.text.split('\n')[1] || '').slice(0, 70)}` : r.status;
    }
    r1[`${who}.GET users`] = (await rt.api('GET', '/api/users', { token: tok })).status;
    r1[`${who}.GET roles`] = (await rt.api('GET', '/api/roles', { token: tok })).status;
    r1[`${who}.GET disputes`] = (await rt.api('GET', '/api/disputes', { token: tok })).status;
    r1[`${who}.GET products`] = (await rt.api('GET', '/api/products', { token: tok })).status;
  }
  log('R1_exports', r1);

  // R2 deactivated token
  const d = await rt.createStaff(admin, 'VIEWER', P + 'rdeact');
  const td = await rt.login(d.username, rt.PASSWORD);
  await rt.api('PUT', `/api/users/${d.id}`, { token: admin, body: { active: false } });
  log('R2_deactivated', { login: (await rt.api('POST', '/api/auth/login', { body: { username: d.username, password: rt.PASSWORD } })).status, oldTokenInvoices: (await rt.api('GET', '/api/invoices', { token: td })).status, oldTokenMe: (await rt.api('GET', '/api/auth/me', { token: td })).status });

  // R3 sales POC outside book by id
  log('R3_salesOutsideBook', {
    get: (await rt.api('GET', `/api/invoices/${st.invY1.id}`, { token: S1 })).status,
    patch: (await rt.api('PATCH', `/api/invoices/${st.invY1.id}`, { token: S1, body: { notes: 'recheck edit by s1' } })).status,
    customerY: (await rt.api('GET', `/api/customers/${st.custY.id}`, { token: S1 })).status,
    customerYPocs: (await rt.api('GET', `/api/customers/${st.custY.id}/pocs`, { token: S1 })).status,
    listHasY1: (await rt.api('GET', `/api/invoices?${qs([`id:eq:${st.invY1.id}`])}`, { token: S1 })).json.totalElements,
  });

  // R4 customer POC filter oracle
  log('R4_customerPocOracle', {
    s1: (await rt.api('GET', `/api/invoices?${qs([`salesPocUserId:eq:${st.s1.id}`])}`, { token: A })).json?.totalElements,
    s2: (await rt.api('GET', `/api/invoices?${qs([`salesPocUserId:eq:${st.s2.id}`])}`, { token: A })).json?.totalElements,
    c1: (await rt.api('GET', `/api/invoices?${qs([`salesPocUserId:eq:${st.c1.id}`])}`, { token: A })).json?.totalElements,
    sortStatus: (await rt.api('GET', '/api/invoices?sort=salesPocName,asc', { token: A })).status,
    payC1: (await rt.api('GET', `/api/payments?${qs([`collectionPocUserId:eq:${st.c1.id}`])}`, { token: A })).json?.totalElements,
    payC2: (await rt.api('GET', `/api/payments?${qs([`collectionPocUserId:eq:${st.c2.id}`])}`, { token: A })).json?.totalElements,
    custSeatC1: (await rt.api('GET', `/api/customers?${qs([`collectionPocUserId:eq:${st.c1.id}`])}`, { token: A })).json?.totalElements,
    nameContains: (await rt.api('GET', `/api/invoices?${qs([`salesPocName:contains:${st.s2.username.toUpperCase()}`])}`, { token: A })).json?.totalElements,
  });

  // R5 USER audit for non-USER_VIEW roles
  const au = await rt.api('GET', `/api/audit?entityType=USER&entityId=${st.viewer.id}`, { token: S1 });
  const auC1 = await rt.api('GET', `/api/audit?entityType=USER&entityId=${st.viewer.id}`, { token: C1 });
  log('R5_userAudit', { s1: au.status, c1: auC1.status, rows: au.json?.length, hasEmail: au.text.includes('@rt.local'), hasPrivileges: au.text.includes('privileges') });

  // R6 custom role PROMISE_MANAGE without POC_ASSIGN
  const role = (await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('PERMSC_PM2').toUpperCase(), privileges: ['PROMISE_VIEW', 'PROMISE_MANAGE', 'CUSTOMER_VIEW'] } })).json;
  const u = (await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq(P + 'pm2'), password: rt.PASSWORD, roleId: role.id, active: true } })).json;
  const tp = await rt.login(u.username, rt.PASSWORD);
  const put = await rt.api('PUT', `/api/promises/${st.promX.id}`, { token: tp, body: { amount: 20, promisedDate: new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10), collectionPocUserId: st.c2.id, notes: 'rt', invoiceIds: [st.invX2.id] } });
  const after = await rt.api('GET', `/api/promises/${st.promX.id}`, { token: admin });
  await rt.api('POST', '/api/promises/bulk', { token: admin, body: { action: 'REASSIGN_COLLECTION_POC', ids: [st.promX.id], params: { userId: st.c1.id } } });
  log('R6_promisePocNoAssign', { put: put.status, pocAfter: after.json.collectionPoc?.id, c2: st.c2.id });

  // R7 bulk silently drops out-of-scope explicit ids
  const b = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'REASSIGN_SALES_POC', ids: [st.invX2.id, st.invX1.id], params: { userId: st.s1.id } } });
  log('R7_bulkDrop', b.json);

  // R8 customer sees staff user ids
  const pr = await rt.api('GET', `/api/promises/${st.promA.id}`, { token: A });
  const di = await rt.api('GET', `/api/disputes/${st.dispA2.id}`, { token: A });
  log('R8_actorIds', { promiseCreatedBy: pr.json.createdByUserId, disputeResolvedBy: di.json.resolvedByUserId });
  fs.writeFileSync(path.join(__dirname, 'recheck-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('RECHECK FAILED', e); process.exit(1); });
