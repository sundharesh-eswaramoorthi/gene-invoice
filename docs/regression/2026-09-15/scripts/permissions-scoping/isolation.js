// Customer isolation (AC-D10): customer A vs customer B.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 600)); };
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');

(async () => {
  const admin = await rt.adminToken();
  const A = await rt.login(st.custA.username, rt.PASSWORD);
  const B = await rt.login(st.custB.username, rt.PASSWORD);
  const a = st.custA.id, b = st.custB.id;

  // I-01 by id
  const byId = {};
  for (const [k, p] of [['invoice', `/api/invoices/${st.invB1.id}`], ['payment', `/api/payments/${st.payB.id}`],
    ['promise', `/api/promises/${st.promB.id}`], ['dispute', `/api/disputes/${st.dispB.id}`],
    ['customer', `/api/customers/${b}`], ['credits', `/api/payments/credits/${b}`],
    ['own-invoice', `/api/invoices/${st.invA1.id}`], ['own-customer', `/api/customers/${a}`]]) {
    const r = await rt.api('GET', p, { token: A });
    byId[k] = { status: r.status, leak: r.status === 200 && !k.startsWith('own') ? r.text.slice(0, 150) : undefined };
  }
  log('I01_byId', byId);

  // I-02 audit by id
  const aud = {};
  for (const [t, id] of [['INVOICE', st.invB1.id], ['PAYMENT', st.payB.id], ['PROMISE', st.promB.id], ['CUSTOMER', b], ['USER', 1], ['PRODUCT', st.product.id], ['INVOICE', st.invA1.id]]) {
    const r = await rt.api('GET', `/api/audit?entityType=${t}&entityId=${id}&includeRelated=true`, { token: A });
    aud[`${t}:${id}`] = { status: r.status, n: Array.isArray(r.json) ? r.json.length : undefined };
  }
  log('I02_audit', aud);

  // I-03 list widening
  const lists = ['invoices', 'payments', 'promises', 'disputes', 'customers'];
  const widen = {};
  for (const e of lists) {
    const custOf = (x) => (e === 'customers' ? x.id : x.customerId);
    const variants = {
      none: '',
      eqB: qs([`${e === 'customers' ? 'id' : 'customerId'}:eq:${b}`]),
      inAB: qs([`${e === 'customers' ? 'id' : 'customerId'}:in:${a},${b}`]),
      neqA: qs([`${e === 'customers' ? 'id' : 'customerId'}:neq:${a}`]),
      paramB: e === 'customers' ? null : `customerId=${b}`,
      sortName: `sort=${e === 'customers' ? 'name' : e === 'disputes' ? 'customerId' : 'customerName'},desc`,
      size50: 'size=50&page=0',
    };
    widen[e] = {};
    for (const [vk, q] of Object.entries(variants)) {
      if (q === null) continue;
      const r = await rt.api('GET', `/api/${e}?size=50&${q}`, { token: A });
      const content = r.json?.content || [];
      const foreign = content.filter((x) => custOf(x) !== a).map((x) => x.id);
      widen[e][vk] = { status: r.status, total: r.json?.totalElements, foreign, err: r.status >= 400 ? r.text.slice(0, 120) : undefined };
    }
    // admin count of A's rows for comparison
    const ra = await rt.api('GET', `/api/${e}?size=50&${qs([`${e === 'customers' ? 'id' : 'customerId'}:eq:${a}`])}`, { token: admin });
    widen[e].adminCountA = ra.status === 200 ? ra.json.totalElements : `ERR ${ra.status} ${ra.text.slice(0, 100)}`;
  }
  log('I03_widen', widen);

  // I-04 summaries = A-only
  const sums = {};
  for (const e of ['invoices', 'payments', 'promises', 'customers']) {
    const ra = await rt.api('GET', `/api/${e}/summary`, { token: A });
    const rb = await rt.api('GET', `/api/${e}/summary?${qs([`${e === 'customers' ? 'id' : 'customerId'}:eq:${b}`])}`, { token: A });
    const rad = await rt.api('GET', `/api/${e}/summary?${qs([`${e === 'customers' ? 'id' : 'customerId'}:eq:${a}`])}`, { token: admin });
    sums[e] = { custA: ra.json, custA_filterB: rb.json, adminFilterA: rad.json, statusA: ra.status, statusB: rb.status };
  }
  log('I04_summary', sums);

  // I-05 notifications of B
  const nb = await rt.api('GET', '/api/notifications?size=50', { token: B });
  const bNotifs = (nb.json?.content || []);
  const unreadB = bNotifs.find((n) => !n.read);
  const bUnreadBefore = (await rt.api('GET', '/api/notifications/unread-count', { token: B })).json;
  let bulkRes = null, readRes = null;
  if (unreadB) {
    bulkRes = await rt.api('POST', '/api/notifications/bulk', { token: A, body: { action: 'MARK_READ', ids: [unreadB.id] } });
    readRes = await rt.api('POST', `/api/notifications/${unreadB.id}/read`, { token: A });
  }
  const selAll = await rt.api('POST', '/api/notifications/bulk', { token: A, body: { action: 'MARK_READ', selectAllMatchingFilter: true, filters: [`id:eq:${unreadB?.id}`] } });
  const bUnreadAfter = (await rt.api('GET', '/api/notifications/unread-count', { token: B })).json;
  const aList = await rt.api('GET', `/api/notifications?size=50&${qs([`id:eq:${unreadB?.id}`])}`, { token: A });
  log('I05_notif', { bNotifCount: bNotifs.length, unreadBId: unreadB?.id, bUnreadBefore, bulk: bulkRes && { s: bulkRes.status, j: bulkRes.json }, read: readRes && readRes.status, selAll: { s: selAll.status, j: selAll.json }, bUnreadAfter, aSeesBNotif: aList.json?.totalElements });

  // I-06 dispute against B's record
  const d1 = await rt.api('POST', '/api/disputes', { token: A, body: { targetType: 'INVOICE', targetId: st.invB1.id, reason: 'cross' } });
  const d2 = await rt.api('POST', '/api/disputes', { token: A, body: { targetType: 'PAYMENT', targetId: st.payB.id, reason: 'cross' } });
  log('I06_crossDispute', { invoice: d1.status, payment: d2.status, body: d1.text.slice(0, 120) });

  // I-07 non-existent ids (404 vs 403 semantics)
  const ne = {};
  for (const p of ['/api/invoices/999999', '/api/payments/999999', '/api/promises/999999', '/api/disputes/999999', '/api/customers/999999']) {
    ne[p] = (await rt.api('GET', p, { token: A })).status;
  }
  log('I07_nonexistent', ne);

  // I-08 bulk/export as customer (no privilege) with B ids / select-all
  const bx = {};
  for (const [e, id] of [['invoices', st.invB1.id], ['payments', st.payB.id], ['promises', st.promB.id], ['customers', b]]) {
    bx[e] = {
      bulk: (await rt.api('POST', `/api/${e}/bulk`, { token: A, body: { action: e === 'customers' ? 'ADD_POC' : 'CANCEL', ids: [id] } })).status,
      exportAll: (await rt.api('POST', `/api/${e}/export`, { token: A, body: { selectAllMatchingFilter: true } })).status,
    };
  }
  log('I08_bulkExport', bx);

  fs.writeFileSync(path.join(__dirname, 'isolation-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('ISOLATION FAILED', e); process.exit(1); });
