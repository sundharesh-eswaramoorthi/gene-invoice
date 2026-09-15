// AC-A8: a customer login never receives POC identity. Crawls every JSON response customer A can
// read, searches keys containing 'poc' and staff identity strings; checks table schemas and POC
// column filters/sorts as a customer.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');

const staff = ['s1', 's2', 'c1', 'c2', 'cs1', 'viewer', 'cashierRt'].filter((k) => st[k]).map((k) => st[k]);
const needles = [];
for (const u of staff) needles.push(u.username, u.username.toUpperCase(), `${u.username}@rt.local`);
needles.push('System Administrator', 'Default Cashier', 'admin@geneinvoice.local', 'cashier@geneinvoice.local');
const staffIds = new Set(staff.map((u) => u.id).concat([1, 2]));

function walk(v, p, hits) {
  if (v === null || v === undefined) return;
  if (typeof v === 'string') {
    for (const n of needles) if (v.includes(n)) hits.strings.push(`${p} contains "${n}"`);
    if (/^[\[{]/.test(v.trim())) { try { walk(JSON.parse(v), p + '<json>', hits); } catch { /* not json */ } }
    return;
  }
  if (Array.isArray(v)) { v.forEach((x, i) => walk(x, `${p}[${i}]`, hits)); return; }
  if (typeof v === 'object') {
    for (const [k, x] of Object.entries(v)) {
      const kp = `${p}.${k}`;
      if (/poc/i.test(k)) hits.pocKeys.push(`${kp}=${JSON.stringify(x)?.slice(0, 80)}`);
      if (/(createdBy|overriddenBy|resolvedBy|openedBy|changedBy)UserId$/i.test(k) && x != null) hits.actorIds.push(`${kp}=${x}${staffIds.has(x) ? ' (STAFF)' : ''}`);
      if (/changedByUsername$/i.test(k) && x != null) hits.actorIds.push(`${kp}=${x}`);
      walk(x, kp, hits);
    }
  }
}

(async () => {
  const A = await rt.login(st.custA.username, rt.PASSWORD);
  const out = { crawl: {}, schemas: {}, filters: {} };
  const gets = [
    '/api/auth/me', '/api/customers', '/api/customers/summary', `/api/customers/${st.custA.id}`,
    '/api/invoices', '/api/invoices/summary', `/api/invoices/${st.invA1.id}`, `/api/invoices/${st.invA2.id}`, '/api/invoices/assignable-check',
    '/api/payments', '/api/payments/summary', `/api/payments/${st.payA.id}`, `/api/payments/credits/${st.custA.id}`,
    '/api/promises', '/api/promises/summary', `/api/promises/${st.promA.id}`, `/api/promises?invoiceId=${st.invA2.id}`,
    '/api/disputes', `/api/disputes/${st.dispA.id}`, `/api/disputes/${st.dispA2.id}`,
    '/api/notifications', '/api/notifications/unread-count',
    `/api/audit?entityType=INVOICE&entityId=${st.invA1.id}&includeRelated=true`,
    `/api/audit?entityType=INVOICE&entityId=${st.invA2.id}&includeRelated=true`,
    `/api/audit?entityType=PAYMENT&entityId=${st.payA.id}&includeRelated=true`,
    `/api/audit?entityType=PROMISE&entityId=${st.promA.id}&includeRelated=true`,
    `/api/audit?entityType=CUSTOMER&entityId=${st.custA.id}&includeRelated=true`,
    '/api/table-schemas', '/api/table-schemas/all',
  ];
  for (const g of gets) {
    const r = await rt.api('GET', g, { token: A });
    const hits = { pocKeys: [], strings: [], actorIds: [] };
    walk(r.json, '$', hits);
    out.crawl[g] = { status: r.status, ...hits };
    const flag = hits.pocKeys.length + hits.strings.length + hits.actorIds.length;
    console.log(`${r.status} ${g} pocKeys=${hits.pocKeys.length} staffStrings=${hits.strings.length} actorIds=${hits.actorIds.length}`);
    if (flag) {
      console.log('   pocKeys:', hits.pocKeys.slice(0, 6).join(' | '));
      console.log('   strings:', hits.strings.slice(0, 6).join(' | '));
      console.log('   actors :', hits.actorIds.slice(0, 6).join(' | '));
    }
  }

  // schemas: customer vs viewer
  const V = await rt.login(st.viewer.username, rt.PASSWORD);
  for (const e of ['invoices', 'payments', 'customers', 'promises', 'disputes', 'products', 'notifications']) {
    const rc = await rt.api('GET', `/api/table-schemas/${e}`, { token: A });
    const rv = await rt.api('GET', `/api/table-schemas/${e}`, { token: V });
    const cc = (rc.json?.columns || []).map((c) => c.name);
    const vc = (rv.json?.columns || []).map((c) => c.name);
    out.schemas[e] = { customerPocCols: cc.filter((n) => /poc/i.test(n)), viewerPocCols: vc.filter((n) => /poc/i.test(n)), status: rc.status };
  }
  console.log('SCHEMAS', JSON.stringify(out.schemas));

  // filtering / sorting on POC columns as a customer
  const s1Name = st.s1.username.toUpperCase().slice(0, 9);
  const probes = [
    ['invoices', qs([`salesPocUserId:eq:${st.s1.id}`])], ['invoices', qs([`salesPocUserId:eq:${st.s2.id}`])],
    ['invoices', qs([`salesPocUserId:eq:${st.c1.id}`])], ['invoices', qs(['salesPocUserId:isEmpty:'])],
    ['invoices', qs([`salesPocName:contains:${s1Name}`])], ['invoices', 'sort=salesPocName,asc'],
    ['payments', qs([`collectionPocUserId:eq:${st.c1.id}`])], ['payments', qs([`collectionPocUserId:eq:${st.c2.id}`])],
    ['payments', 'sort=collectionPocName,desc'],
    ['promises', qs([`collectionPocUserId:eq:${st.c1.id}`])], ['promises', qs([`collectionPocUserId:eq:${st.c2.id}`])],
    ['promises', 'sort=collectionPocName,asc'],
    ['customers', qs([`collectionPocUserId:eq:${st.c1.id}`])], ['customers', qs([`collectionPocUserId:eq:${st.c2.id}`])],
    ['customers', qs([`successPocUserId:eq:${st.cs1.id}`])], ['customers', qs(['successPocUserId:isEmpty:'])],
    ['invoices/summary', qs([`salesPocUserId:eq:${st.s1.id}`])],
  ];
  for (const [e, q] of probes) {
    const r = await rt.api('GET', `/api/${e}?${q}`, { token: A });
    const key = `${e}?${decodeURIComponent(q)}`;
    out.filters[key] = { status: r.status, total: r.json?.totalElements ?? r.json?.count, msg: r.status >= 400 ? r.json?.message : undefined };
    console.log('FILTER', r.status, key, 'total=', out.filters[key].total, out.filters[key].msg || '');
  }
  fs.writeFileSync(path.join(__dirname, 'aca8-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('ACA8 FAILED', e); process.exit(1); });
