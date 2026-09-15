// Independent verification of the permissions-scoping failures (API part). Fresh data, own names.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const P = 'vps';
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 900)); };
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');
const rows = (r) => (r.status === 200 ? r.text.trim().split('\n').length - 1 : null);
const future = (d) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 10);

async function must(r, what) {
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
  return r.json;
}

(async () => {
  const admin = await rt.adminToken();
  // ---- setup --------------------------------------------------------------------------
  const s1 = await rt.createStaff(admin, 'SALES_POC', P + 's1');
  const s2 = await rt.createStaff(admin, 'SALES_POC', P + 's2');
  const c1 = await rt.createStaff(admin, 'COLLECTION_POC', P + 'c1');
  const c2 = await rt.createStaff(admin, 'COLLECTION_POC', P + 'c2');
  const cs1 = await rt.createStaff(admin, 'CUSTOMER_SUCCESS_POC', P + 'cs1');
  const cash = await rt.createStaff(admin, 'CASHIER', P + 'cash');
  const viewer = await rt.createStaff(admin, 'VIEWER', P + 'view');
  const A = await rt.createCustomer(admin, P + 'A');
  const B = await rt.createCustomer(admin, P + 'B');
  const Y = await rt.createCustomer(admin, P + 'Y');
  const prod = await must(await rt.api('POST', '/api/products', { token: admin, body: { name: rt.uniq(P + 'prod'), description: 'x', price: 100, active: true } }), 'product');
  const inv = async (cust, poc, notes) => must(await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: cust.id, salesPocUserId: poc.id, notes, items: [{ productId: prod.id, quantity: 1, unitPrice: 100 }] } }), 'invoice');
  const invA1 = await inv(A, s1, 'A1');
  const invA2 = await inv(A, s1, 'A2');
  const invA3 = await inv(A, s2, 'A3');
  const invY1 = await inv(Y, s2, 'Y1');
  const invY2 = await inv(Y, s2, 'Y2');
  await must(await rt.api('POST', `/api/customers/${A.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: c1.id } }), 'seat A c1');
  await must(await rt.api('POST', `/api/customers/${A.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: cs1.id } }), 'seat A cs1');
  await must(await rt.api('POST', `/api/customers/${B.id}/pocs`, { token: admin, body: { pocType: 'COLLECTION', userId: c2.id } }), 'seat B c2');
  const payA = await must(await rt.api('POST', '/api/payments', { token: admin, body: { customerId: A.id, amount: 10, method: 'CASH', invoiceIds: [invA1.id], collectionPocUserId: c1.id } }), 'payment A');
  const promA = await must(await rt.api('POST', '/api/promises', { token: admin, body: { customerId: A.id, amount: 50, promisedDate: future(10), collectionPocUserId: c1.id, notes: 'promA' } }), 'promA');
  const promB = await must(await rt.api('POST', '/api/promises', { token: admin, body: { customerId: B.id, amount: 50, promisedDate: future(10), collectionPocUserId: c2.id, notes: 'promB' } }), 'promB');

  const T = {
    s1: await rt.login(s1.username, rt.PASSWORD), s2: await rt.login(s2.username, rt.PASSWORD),
    c1: await rt.login(c1.username, rt.PASSWORD), cs1: await rt.login(cs1.username, rt.PASSWORD),
    cash: await rt.login(cash.username, rt.PASSWORD), viewer: await rt.login(viewer.username, rt.PASSWORD),
    A: await rt.login(A.username, rt.PASSWORD), B: await rt.login(B.username, rt.PASSWORD),
  };
  const dispA = await must(await rt.api('POST', '/api/disputes', { token: T.A, body: { targetType: 'INVOICE', targetId: invA2.id, reason: 'vps dispute' } }), 'dispute');
  await must(await rt.api('POST', `/api/disputes/${dispA.id}/deny`, { token: admin, body: { adminNotes: 'no' } }), 'deny');
  const state = { s1, s2, c1, c2, cs1, cash, viewer, A, B, Y, prod, invA1, invA2, invA3, invY1, invY2, payA, promA, promB, dispA };
  fs.writeFileSync(path.join(__dirname, 'state.json'), JSON.stringify(state, null, 1));
  console.log('setup ok', { s1: s1.id, s2: s2.id, c1: c1.id, c2: c2.id, cs1: cs1.id, A: A.id, B: B.id, Y: Y.id });

  // ---- PS-010 export vs view ---------------------------------------------------------
  const seeded = await rt.cashierToken();
  const r010 = {};
  for (const [who, tok] of [['cashier(own)', T.cash], ['cashier(seeded)', seeded], ['SALES_POC', T.s1], ['CS_POC', T.cs1], ['COLLECTION_POC', T.c1]]) {
    for (const e of ['users', 'roles', 'disputes', 'products']) {
      const g = await rt.api('GET', `/api/${e}?size=10`, { token: tok });
      const x = await rt.api('POST', `/api/${e}/export`, { token: tok, body: { selectAllMatchingFilter: true } });
      r010[`${who} ${e}`] = { GET: g.status, EXPORT: x.status, rows: rows(x), sample: x.status === 200 ? (x.text.split('\n')[1] || '').slice(0, 80) : x.text.slice(0, 80) };
    }
  }
  log('PS010', r010);

  // ---- PS-018 bulk drops out-of-scope ids --------------------------------------------
  const nS2 = await rt.api('GET', '/api/notifications?size=10', { token: T.s2 });
  const s2Notif = nS2.json?.content?.[0];
  const nb = await rt.api('POST', '/api/notifications/bulk', { token: T.s1, body: { action: 'MARK_READ', ids: [s2Notif.id] } });
  const nS2after = await rt.api('GET', `/api/notifications?size=10&${qs([`id:eq:${s2Notif.id}`])}`, { token: T.s2 });
  const ib = await rt.api('POST', '/api/invoices/bulk', { token: T.s1, body: { action: 'REASSIGN_SALES_POC', ids: [invY1.id, invA1.id], params: { userId: s1.id } } });
  const y1After = await rt.api('GET', `/api/invoices/${invY1.id}`, { token: admin });
  log('PS018', {
    notif: { s2NotifId: s2Notif.id, s2NotifReadBefore: s2Notif.read, status: nb.status, result: nb.json, s2NotifReadAfter: nS2after.json?.content?.[0]?.read },
    invoices: { requestedIds: [invY1.id, invA1.id], status: ib.status, result: ib.json, y1SalesPocAfter: y1After.json?.salesPoc?.id, s2: s2.id },
  });

  // ---- PS-022 customer POC filter / sort oracle --------------------------------------
  const cnt = async (tok, ent, f, extra = '') => { const r = await rt.api('GET', `/api/${ent}?${qs(f)}${extra}`, { token: tok }); return r.status === 200 ? r.json.totalElements : `${r.status} ${r.text.slice(0, 80)}`; };
  const schemaInv = await rt.api('GET', '/api/table-schemas/invoices', { token: T.A });
  log('PS022', {
    schemaHasSalesPoc: schemaInv.json.columns.some((c) => c.name.startsWith('salesPoc')),
    invAll: await cnt(T.A, 'invoices', []),
    invS1: await cnt(T.A, 'invoices', [`salesPocUserId:eq:${s1.id}`]),
    invS2: await cnt(T.A, 'invoices', [`salesPocUserId:eq:${s2.id}`]),
    invC1: await cnt(T.A, 'invoices', [`salesPocUserId:eq:${c1.id}`]),
    invNameS2: await cnt(T.A, 'invoices', [`salesPocName:contains:${s2.username.slice(0, 8)}`]),
    sortSalesPocName: (await rt.api('GET', '/api/invoices?sort=salesPocName,asc', { token: T.A })).status,
    payC1: await cnt(T.A, 'payments', [`collectionPocUserId:eq:${c1.id}`]),
    payC2: await cnt(T.A, 'payments', [`collectionPocUserId:eq:${c2.id}`]),
    promC1: await cnt(T.A, 'promises', [`collectionPocUserId:eq:${c1.id}`]),
    promC2: await cnt(T.A, 'promises', [`collectionPocUserId:eq:${c2.id}`]),
    custSeatC1: await cnt(T.A, 'customers', [`collectionPocUserId:eq:${c1.id}`]),
    custSeatC2: await cnt(T.A, 'customers', [`collectionPocUserId:eq:${c2.id}`]),
    custSeatCs1: await cnt(T.A, 'customers', [`successPocUserId:eq:${cs1.id}`]),
    unknownColumnControl: await cnt(T.A, 'invoices', ['bogusCol:eq:1']),
    invDtoHasSalesPoc: JSON.stringify((await rt.api('GET', `/api/invoices/${invA1.id}`, { token: T.A })).json).includes(s1.username),
  });

  // ---- PS-023 staff ids in customer DTOs ---------------------------------------------
  const pr = await rt.api('GET', `/api/promises/${promA.id}`, { token: T.A });
  const di = await rt.api('GET', `/api/disputes/${dispA.id}`, { token: T.A });
  const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${promA.id}`, { token: T.A });
  log('PS023', {
    promiseStatus: pr.status, createdByUserId: pr.json?.createdByUserId, overriddenByUserIdPresent: pr.json && 'overriddenByUserId' in pr.json, collectionPoc: pr.json?.collectionPoc,
    disputeStatus: di.status, resolvedByUserId: di.json?.resolvedByUserId, openedByUserId: di.json?.openedByUserId,
    promiseAudit: au.status === 200 ? au.json.map((e) => ({ action: e.action, changedByUserId: e.changedByUserId, changedByUsername: e.changedByUsername, actorHidden: e.actorHidden, afterHasCreatedBy: (e.afterJson || '').includes('createdBy') })) : au.status,
  });

  // ---- PS-029 sales POC by id outside the book ---------------------------------------
  const listY1 = await rt.api('GET', `/api/invoices?${qs([`id:eq:${invY1.id}`])}`, { token: T.s1 });
  const g = await rt.api('GET', `/api/invoices/${invY1.id}`, { token: T.s1 });
  const pa = await rt.api('PATCH', `/api/invoices/${invY1.id}`, { token: T.s1, body: { notes: 'vps edited by s1 outside book' } });
  const ca = await rt.api('POST', `/api/invoices/${invY2.id}/cancel`, { token: T.s1 });
  const y1 = await rt.api('GET', `/api/invoices/${invY1.id}`, { token: admin });
  const y2 = await rt.api('GET', `/api/invoices/${invY2.id}`, { token: admin });
  const custList = await rt.api('GET', `/api/customers?${qs([`id:eq:${Y.id}`])}`, { token: T.s1 });
  log('PS029', {
    listLocked: listY1.json?.lockedFilters, listCount: listY1.json?.totalElements,
    get: g.status, getSalesPoc: g.json?.salesPoc?.username,
    patch: pa.status, notesAfter: y1.json?.notes,
    cancel: ca.status, y2StatusAfter: y2.json?.status,
    custListCount: custList.json?.totalElements, custLocked: custList.json?.lockedFilters,
    getCustomerY: (await rt.api('GET', `/api/customers/${Y.id}`, { token: T.s1 })).status,
    getCustomerYPocs: (await rt.api('GET', `/api/customers/${Y.id}/pocs`, { token: T.s1 })).status,
  });

  // ---- PS-033 SALES_POC sees every payment/promise/dispute ---------------------------
  const tot = async (tok, e) => { const r = await rt.api('GET', `/api/${e}?size=10`, { token: tok }); return { status: r.status, total: r.json?.totalElements, locked: r.json?.lockedFilters }; };
  log('PS033', {
    s1Payments: await tot(T.s1, 'payments'), adminPayments: await tot(admin, 'payments'),
    s1Promises: await tot(T.s1, 'promises'), adminPromises: await tot(admin, 'promises'),
    s1Disputes: await tot(T.s1, 'disputes'), adminDisputes: await tot(admin, 'disputes'),
    s1SeesPromB: (await rt.api('GET', `/api/promises?${qs([`id:eq:${promB.id}`])}`, { token: T.s1 })).json?.totalElements,
    s1Invoices: await tot(T.s1, 'invoices'),
  });

  // ---- PS-034 SALES_POC adds a seat on a customer outside the book -------------------
  const seat = await rt.api('POST', `/api/customers/${Y.id}/pocs`, { token: T.s1, body: { pocType: 'SUCCESS', userId: cs1.id } });
  const seatsY = await rt.api('GET', `/api/customers/${Y.id}/pocs`, { token: admin });
  const cashSeat = await rt.api('POST', `/api/customers/${B.id}/pocs`, { token: T.c1, body: { pocType: 'COLLECTION', userId: c1.id } });
  log('PS034', { s1AddSeatOnY: seat.status, body: seat.json, seatsOnY: seatsY.json?.map((x) => ({ id: x.id, type: x.pocType, user: x.user?.username, primary: x.primary })), c1AddSeatOnB: cashSeat.status });
  if (seat.status === 200) await rt.api('DELETE', `/api/customers/${Y.id}/pocs/${seat.json.id}`, { token: admin });
  if (cashSeat.status === 200) await rt.api('DELETE', `/api/customers/${B.id}/pocs/${cashSeat.json.id}`, { token: admin });

  // ---- PS-035 USER audit readable without USER_VIEW ----------------------------------
  await rt.api('PUT', `/api/users/${viewer.id}`, { token: admin, body: { fullName: 'VPS VIEWER RENAMED' } });
  const ua = {};
  for (const [who, tok] of [['s1', T.s1], ['c1', T.c1], ['cs1', T.cs1], ['cashier', T.cash], ['customerA', T.A]]) {
    const r = await rt.api('GET', `/api/audit?entityType=USER&entityId=${viewer.id}`, { token: tok });
    ua[who] = { status: r.status, rows: Array.isArray(r.json) ? r.json.length : null, hasEmail: r.text.includes(viewer.email), hasPrivileges: r.text.includes('PRODUCT_VIEW') };
  }
  ua.s1GetUser = (await rt.api('GET', `/api/users/${viewer.id}`, { token: T.s1 })).status;
  ua.s1AuditAdmin = (await rt.api('GET', '/api/audit?entityType=USER&entityId=1', { token: T.s1 })).status;
  log('PS035', ua);

  // ---- PS-036 PROMISE_MANAGE without POC_ASSIGN reassigns promise POC ----------------
  const role = await must(await rt.api('POST', '/api/roles', { token: admin, body: { name: rt.uniq('VPS_PM').toUpperCase(), description: 'vps', privileges: ['PROMISE_VIEW', 'PROMISE_MANAGE', 'PAYMENT_VIEW', 'PAYMENT_MANAGE', 'CUSTOMER_VIEW'] } }), 'role');
  const pmUser = await must(await rt.api('POST', '/api/users', { token: admin, body: { username: rt.uniq(P + 'pm'), email: rt.uniq(P) + '@rt.local', fullName: 'VPS PM', password: rt.PASSWORD, roleId: role.id, active: true } }), 'pm user');
  const TP = await rt.login(pmUser.username, rt.PASSWORD);
  const put = await rt.api('PUT', `/api/promises/${promA.id}`, { token: TP, body: { amount: 50, promisedDate: future(10), collectionPocUserId: c2.id, notes: 'promA' } });
  const pAfterPut = await rt.api('GET', `/api/promises/${promA.id}`, { token: admin });
  await rt.api('PUT', `/api/promises/${promA.id}`, { token: admin, body: { amount: 50, promisedDate: future(10), collectionPocUserId: c1.id, notes: 'promA' } });
  const pbulk = await rt.api('POST', '/api/promises/bulk', { token: TP, body: { action: 'REASSIGN_COLLECTION_POC', ids: [promA.id], params: { userId: c2.id } } });
  const pAfterBulk = await rt.api('GET', `/api/promises/${promA.id}`, { token: admin });
  await rt.api('PUT', `/api/promises/${promA.id}`, { token: admin, body: { amount: 50, promisedDate: future(10), collectionPocUserId: c1.id, notes: 'promA' } });
  const paypatch = await rt.api('PATCH', `/api/payments/${payA.id}`, { token: TP, body: { collectionPocUserId: c2.id } });
  const paybulk = await rt.api('POST', '/api/payments/bulk', { token: TP, body: { action: 'REASSIGN_COLLECTION_POC', ids: [payA.id], params: { userId: c2.id } } });
  log('PS036', { rolePrivs: role.privileges, put: put.status, pocAfterPut: pAfterPut.json?.collectionPoc?.id, bulk: pbulk.status, bulkBody: pbulk.json, pocAfterBulk: pAfterBulk.json?.collectionPoc?.id, c2: c2.id, paymentPatch: [paypatch.status, paypatch.json?.message], paymentBulk: [paybulk.status, paybulk.json?.message] });

  // ---- PS-039 deactivated user keeps access via old JWT ------------------------------
  const d1 = await rt.createStaff(admin, 'VIEWER', P + 'deact');
  const td1 = await rt.login(d1.username, rt.PASSWORD);
  const put1 = await rt.api('PUT', `/api/users/${d1.id}`, { token: admin, body: { active: false } });
  const relog = await rt.api('POST', '/api/auth/login', { body: { username: d1.username, password: rt.PASSWORD } });
  const r039a = { deactivate: put1.status, activeAfter: put1.json?.active, relogin: [relog.status, relog.text.slice(0, 80)], oldTokenInvoices: (await rt.api('GET', '/api/invoices?size=10', { token: td1 })).status, oldTokenMe: (await rt.api('GET', '/api/auth/me', { token: td1 })).status };
  const d2 = await rt.createStaff(admin, 'SALES_POC', P + 'deact2');
  const invD = await inv(A, d2, 'owned by d2');
  const td2 = await rt.login(d2.username, rt.PASSWORD);
  const del = await rt.api('DELETE', `/api/users/${d2.id}`, { token: admin });
  const patchD = await rt.api('PATCH', `/api/invoices/${invD.id}`, { token: td2, body: { notes: 'vps written by deactivated user' } });
  const invDafter = await rt.api('GET', `/api/invoices/${invD.id}`, { token: admin });
  const expD = await rt.api('POST', '/api/users/export', { token: td2, body: { selectAllMatchingFilter: true } });
  const r039b = { delete: [del.status, del.json], d2ActiveAfter: (await rt.api('GET', `/api/users/${d2.id}`, { token: admin })).json?.active, patch: patchD.status, notesAfter: invDafter.json?.notes, usersExport: expD.status };
  const expired = rt.mintToken(s1.username, -60);
  log('PS039', { put: r039a, del: r039b, expiredTokenControl: (await rt.api('GET', '/api/invoices', { token: expired })).status });

  fs.writeFileSync(path.join(__dirname, 'api-result.json'), JSON.stringify(out, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
