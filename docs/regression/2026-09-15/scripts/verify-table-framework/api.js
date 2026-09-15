// Independent verification of the table-framework API failures. Uses only data it creates.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const out = {};
const log = (k, v) => { (out[k] = out[k] || []).push(v); console.log(k, JSON.stringify(v)); };
const q = (s) => encodeURIComponent(s);

async function raw(method, p, token, bodyText, ct = 'application/json') {
  const res = await fetch(rt.API + p, {
    method, headers: { Authorization: 'Bearer ' + token, ...(bodyText !== undefined || ct ? { 'Content-Type': ct } : {}) },
    body: bodyText,
  });
  return { status: res.status, text: (await res.text()).slice(0, 200) };
}

(async () => {
  const a = await rt.adminToken();
  const c = await rt.cashierToken();
  const statePath = path.join(__dirname, 'state.json');
  let S;
  if (fs.existsSync(statePath)) S = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  else {
    const rep = await rt.createStaff(a, 'SALES_POC', 'vtf');
    const coll = await rt.createStaff(a, 'COLLECTION_POC', 'vtf');
    const cust = await rt.createCustomer(a, 'vtf');
    const pname = rt.uniq('vtf-prod');
    const p1 = await rt.api('POST', '/api/products', { token: a, body: { name: pname + '-01', price: 10 } });
    const p2 = await rt.api('POST', '/api/products', { token: a, body: { name: pname + '-02', price: 10 } });
    const today = new Date().toISOString().slice(0, 10);
    const yest = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
    const two = new Date(Date.now() - 2 * 86400000).toISOString().slice(0, 10);
    const mk = async (date) => {
      const r = await rt.api('POST', '/api/invoices', { token: a, body: { customerId: cust.id, invoiceDate: date, salesPocUserId: rep.id, items: [{ productId: p1.json.id, quantity: 1 }] } });
      if (r.status >= 300) throw new Error('invoice ' + r.status + r.text);
      return r.json.id;
    };
    const invToday = await mk(`${today}T00:00:00Z`);
    const invYest = await mk(`${yest}T12:00:00Z`);
    const invPaid = await mk(`${today}T00:00:00Z`);
    const invCanc = await mk(`${two}T08:00:00Z`);
    const pay = await rt.api('POST', '/api/payments', { token: a, body: { customerId: cust.id, amount: 10, method: 'CASH', invoiceIds: [invPaid], collectionPocUserId: coll.id } });
    const canc = await rt.api('POST', `/api/invoices/${invCanc}/cancel`, { token: a });
    const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
    const prom = await rt.api('POST', '/api/promises', { token: a, body: { customerId: cust.id, amount: 5, promisedDate: tomorrow, collectionPocUserId: coll.id } });
    S = { rep, coll, cust, p1: p1.json, p2: p2.json, pname, invToday, invYest, invPaid, invCanc, pay: pay.status, canc: canc.status, prom: prom.status, promId: prom.json?.id };
    fs.writeFileSync(statePath, JSON.stringify(S, null, 2));
  }
  console.log('state', JSON.stringify({ ...S, rep: S.rep.id, coll: S.coll.id, cust: S.cust.id }));
  const today = new Date().toISOString().slice(0, 10);
  const yest = new Date(Date.now() - 86400000).toISOString().slice(0, 10);

  // ---- TF-012 paging validation
  for (const qs of ['size=abc', 'page=abc', 'page=-1', 'page=50000000&size=50', 'page=2147483647&size=10', 'page=1000&size=10', 'size=100']) {
    const r = await rt.api('GET', `/api/invoices?${qs}&filter=${q('customerId:eq:' + S.cust.id)}`, { token: a });
    log('TF-012', `${qs} -> ${r.status} ${r.text.slice(0, 160)}`);
  }

  // ---- TF-032 sort direction
  for (const s of ['total', 'total,DESC', 'total,sideways', 'total,']) {
    const r = await rt.api('GET', `/api/invoices?sort=${q(s)}&filter=${q('customerId:eq:' + S.cust.id)}`, { token: a });
    log('TF-032', `sort=${s} -> ${r.status} sort=${r.json?.sort} ${r.status >= 400 ? r.text.slice(0, 120) : ''}`);
  }

  // ---- TF-040 date upper bounds
  const base = `filter=${q('customerId:eq:' + S.cust.id)}`;
  for (const f of [`invoiceDate:lte:${yest}`, 'invoiceDate:relative:yesterday', 'invoiceDate:relative:past', `invoiceDate:between:${yest},${yest}`,
    `invoiceDate:lte:${yest}T23:59:59.999999Z`, `invoiceDate:lte:${yest}T23:59:59.999999999Z`, 'invoiceDate:relative:today', `invoiceDate:gte:${today}`]) {
    const r = await rt.api('GET', `/api/invoices?size=50&${base}&filter=${q(f)}`, { token: a });
    const rows = (r.json?.content || []).map((x) => `${x.id}@${x.invoiceDate}`);
    log('TF-040', `${f} -> ${r.status} total=${r.json?.totalElements} rows=${rows.join(',')}`);
  }

  // ---- TF-081 customer filtering / sorting on POC columns
  const ct = await rt.login(S.cust.username, S.cust.password);
  const sch = await rt.api('GET', '/api/table-schemas/invoices', { token: ct });
  log('TF-081', `schema cols: ${(sch.json?.columns || []).map((x) => x.name).join(',')}`);
  const inv0 = await rt.api('GET', '/api/invoices', { token: ct });
  log('TF-081', `customer list total=${inv0.json?.totalElements} first row keys=${Object.keys(inv0.json?.content?.[0] || {}).join(',')} salesPoc=${JSON.stringify(inv0.json?.content?.[0]?.salesPoc)}`);
  const pre = S.rep.fullName.slice(0, 12);
  for (const f of [`salesPocName:contains:${pre}`, 'salesPocName:contains:zzzz-nobody', `salesPocUserId:eq:${S.rep.id}`, 'salesPocUserId:eq:999999', 'salesPocUserId:isEmpty:']) {
    const r = await rt.api('GET', `/api/invoices?filter=${q(f)}`, { token: ct });
    log('TF-081', `customer invoices ${f} -> ${r.status} total=${r.json?.totalElements} ${r.status >= 400 ? r.text.slice(0, 120) : ''}`);
  }
  const srt = await rt.api('GET', '/api/invoices?sort=salesPocName,asc', { token: ct });
  log('TF-081', `customer sort=salesPocName,asc -> ${srt.status} sort=${srt.json?.sort}`);
  for (const f of [`collectionPocUserId:eq:${S.coll.id}`, 'collectionPocUserId:eq:999999', `collectionPocName:contains:${S.coll.fullName.slice(0, 12)}`]) {
    const r = await rt.api('GET', `/api/promises?filter=${q(f)}`, { token: ct });
    log('TF-081', `customer promises ${f} -> ${r.status} total=${r.json?.totalElements} ${r.status >= 400 ? r.text.slice(0, 120) : ''}`);
  }
  const pays = await rt.api('GET', `/api/payments?filter=${q('collectionPocUserId:eq:' + S.coll.id)}`, { token: ct });
  log('TF-081', `customer payments collectionPocUserId:eq:coll -> ${pays.status} total=${pays.json?.totalElements}`);

  // ---- TF-083 cashier user export
  const nt = await rt.api('GET', '/api/invoices', {});
  const cu = await rt.api('GET', '/api/users', { token: c });
  const cx = await rt.api('POST', '/api/users/export', { token: c, body: { selectAllMatchingFilter: true, filters: ['username:contains:vtf'] } });
  log('TF-083', `no token GET invoices -> ${nt.status}; cashier GET users -> ${cu.status}; cashier POST users/export -> ${cx.status} ${cx.headers['content-type']} lines=${cx.text.split('\r\n').length} sample=${cx.text.split('\r\n').slice(0, 3).join(' | ')}`);
  const cr = await rt.api('GET', '/api/roles', { token: c });
  const crx = await rt.api('POST', '/api/roles/export', { token: c, body: { selectAllMatchingFilter: true } });
  log('TF-083', `cashier GET roles -> ${cr.status}; cashier POST roles/export -> ${crx.status} ${crx.text.split('\r\n').slice(0, 3).join(' | ')}`);
  const cd = await rt.api('GET', '/api/disputes', { token: c });
  const cdx = await rt.api('POST', '/api/disputes/export', { token: c, body: { selectAllMatchingFilter: true } });
  log('TF-083', `cashier GET disputes -> ${cd.status}; cashier POST disputes/export -> ${cdx.status} lines=${cdx.text.split('\r\n').length} ${cdx.text.slice(0, 120)}`);

  // ---- TF-091 bulk drops unknown / out-of-filter ids
  const b1 = await rt.api('POST', '/api/products/bulk', { token: a, body: { action: 'DEACTIVATE', ids: [S.p1.id, 999999999] } });
  log('TF-091', `DEACTIVATE [p1, 999999999] -> ${b1.status} ${b1.text}`);
  const b2 = await rt.api('POST', '/api/products/bulk', { token: a, body: { action: 'ACTIVATE', ids: [S.p2.id, 999999999], filters: [`name:contains:${S.pname}-01`] } });
  log('TF-091', `ACTIVATE [p2, 999999999] filter name contains -01 -> ${b2.status} ${b2.text}`);
  const b3 = await rt.api('POST', '/api/products/bulk', { token: a, body: { action: 'ACTIVATE', ids: [S.p1.id] } });
  log('TF-091', `restore ACTIVATE [p1] -> ${b3.status} ${b3.text}`);
  // cashier notification: MARK_READ with an admin notification id
  const an = await rt.api('GET', '/api/notifications?size=10', { token: a });
  const foreign = an.json?.content?.[0]?.id;
  if (foreign) {
    const nb = await rt.api('POST', '/api/notifications/bulk', { token: c, body: { action: 'MARK_READ', ids: [foreign] } });
    log('TF-091', `cashier notifications MARK_READ [admin notification ${foreign}] -> ${nb.status} ${nb.text}`);
  } else log('TF-091', 'no admin notification to test');
  // invoices: out-of-scope id as customer? customers lack INVOICE_MANAGE; use admin with nonexistent id
  const ib = await rt.api('POST', '/api/invoices/bulk', { token: a, body: { action: 'CANCEL', ids: [999999999] } });
  log('TF-091', `invoices CANCEL [999999999] -> ${ib.status} ${ib.text}`);

  // ---- TF-093 malformed bodies
  log('TF-093', `products/bulk no body -> ${JSON.stringify(await raw('POST', '/api/products/bulk', a, undefined))}`);
  log('TF-093', `products/bulk '{not json' -> ${JSON.stringify(await raw('POST', '/api/products/bulk', a, '{not json'))}`);
  log('TF-093', `invoices/export ids:"abc" -> ${JSON.stringify(await raw('POST', '/api/invoices/export', a, '{"ids":"abc"}'))}`);
  log('TF-093', `products/bulk {} (no action) -> ${JSON.stringify(await raw('POST', '/api/products/bulk', a, '{}'))}`);
  log('TF-093', `products/bulk ids [] -> ${JSON.stringify(await raw('POST', '/api/products/bulk', a, '{"action":"ACTIVATE","ids":[]}'))}`);
  log('TF-093', `products/bulk text/plain -> ${JSON.stringify(await raw('POST', '/api/products/bulk', a, 'x', 'text/plain'))}`);

  // ---- TF-095 ineligible invoices counted as failed
  const paid = await rt.api('GET', `/api/invoices/${S.invPaid}`, { token: a });
  const canc = await rt.api('GET', `/api/invoices/${S.invCanc}`, { token: a });
  log('TF-095', `pre: paid inv ${S.invPaid} status=${paid.json?.status} paid=${paid.json?.paidAmount}; inv ${S.invCanc} status=${canc.json?.status}`);
  const cb = await rt.api('POST', '/api/invoices/bulk', { token: a, body: { action: 'CANCEL', ids: [S.invPaid, S.invCanc] } });
  log('TF-095', `CANCEL [paid, cancelled] -> ${cb.status} ${cb.text}`);
  const pb = await rt.api('POST', '/api/products/bulk', { token: a, body: { action: 'ACTIVATE', ids: [S.p1.id] } });
  log('TF-095', `compare products ACTIVATE already-active -> ${pb.status} ${pb.text}`);

  fs.writeFileSync(path.join(__dirname, `api-out-${Date.now()}.json`), JSON.stringify(out, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });
