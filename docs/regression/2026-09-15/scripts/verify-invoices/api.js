// Independent verification of reported invoice failures (API side).
const rt = require('../lib.js');
const fs = require('fs');
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 700)); };

(async () => {
  const admin = await rt.adminToken();
  const A = (m, p, body, token = admin) => rt.api(m, p, { token, body });

  // ---- setup ----
  const prod = (await A('POST', '/api/products', { name: rt.uniq('vinvP'), description: 'rt', price: 100, active: true })).json;
  const cust = await rt.createCustomer(admin, 'vinv');
  const s1 = await rt.createStaff(admin, 'SALES_POC', 'vinvs1');
  const s2 = await rt.createStaff(admin, 'SALES_POC', 'vinvs2');
  const sDel = await rt.createStaff(admin, 'SALES_POC', 'vinvdel');
  const coll = await rt.createStaff(admin, 'COLLECTION_POC', 'vinvcol');
  const mkInv = (poc, notes, token = admin) => A('POST', '/api/invoices', {
    customerId: cust.id, salesPocUserId: poc, notes, items: [{ productId: prod.id, quantity: 1 }] }, token);
  log('setup', { prod: prod.id, cust: cust.id, s1: s1.id, s2: s2.id, sDel: sDel.id, coll: coll.id });

  // ---- INV-023 concurrent creation ----
  for (const run of [1, 2]) {
    const rs = await Promise.all([...Array(6)].map((_, i) => mkInv(s1.id, `conc-${run}-${i}`)));
    log(`INV-023 run${run}`, rs.map((r) => ({ s: r.status, n: r.json?.invoiceNumber, m: r.status >= 300 ? (r.json?.message || r.text).slice(0, 160) : undefined })));
  }
  // sequential control
  const seq = [];
  for (let i = 0; i < 3; i++) seq.push((await mkInv(s1.id, 'seq')).status);
  log('INV-023 sequential control', seq);

  // ---- INV-024 notes save with deactivated POC ----
  const invDel = (await mkInv(sDel.id, 'orig')).json;
  const del = await A('DELETE', `/api/users/${sDel.id}`);
  log('INV-024 delete user', { s: del.status, b: del.json });
  for (const t of [1, 2]) {
    const p = await A('PATCH', `/api/invoices/${invDel.id}`, { notes: `new-notes-${t}`, salesPocUserId: sDel.id });
    const g = await A('GET', `/api/invoices/${invDel.id}`);
    log(`INV-024 patch with unchanged inactive POC try${t}`, { s: p.status, m: p.json?.message, notesAfter: g.json.notes, poc: g.json.salesPoc });
  }
  const p24b = await A('PATCH', `/api/invoices/${invDel.id}`, { notes: 'notes-only' });
  log('INV-024 control notes only', { s: p24b.status, notes: p24b.json?.notes });

  // ---- INV-025 INVOICE_MANAGE without POC_ASSIGN ----
  const roleName = rt.uniq('VINVMGR').toUpperCase();
  const role = await A('POST', '/api/roles', { name: roleName, description: 'rt vinv', privileges: ['INVOICE_VIEW', 'INVOICE_MANAGE', 'CUSTOMER_VIEW', 'PRODUCT_VIEW', 'POC_VIEW'] });
  const mgrName = rt.uniq('vinvmgr');
  const mgrU = await A('POST', '/api/users', { username: mgrName, email: `${mgrName}@rt.local`, fullName: mgrName, password: rt.PASSWORD, roleId: role.json.id, active: true });
  const mgr = await rt.login(mgrName, rt.PASSWORD);
  const inv25 = (await mkInv(s1.id, 'orig25')).json;
  for (const t of [1, 2]) {
    const p = await A('PATCH', `/api/invoices/${inv25.id}`, { notes: `mgr-${t}`, salesPocUserId: s1.id }, mgr);
    const g = await A('GET', `/api/invoices/${inv25.id}`);
    log(`INV-025 mgr patch unchanged POC try${t}`, { s: p.status, m: p.json?.message, notesAfter: g.json.notes });
  }
  const p25b = await A('PATCH', `/api/invoices/${inv25.id}`, { notes: 'mgr-only' }, mgr);
  log('INV-025 control notes only', { s: p25b.status, notes: p25b.json?.notes, role: role.status, user: mgrU.status });

  // ---- INV-026 SALES_POC by-id access to another rep's invoice ----
  const s1tok = await rt.login(s1.username, rt.PASSWORD);
  const list26 = await A('GET', `/api/invoices?size=50&filter=customerId:eq:${cust.id}`, undefined, s1tok);
  const invOther = (await mkInv(s2.id, 'other-rep')).json;
  const listHas = (await A('GET', `/api/invoices?size=50&filter=customerId:eq:${cust.id}`, undefined, s1tok)).json.content.some((r) => r.id === invOther.id);
  log('INV-026 s1 list lockedFilters / sees other', { locked: list26.json.lockedFilters, sees: listHas });
  for (const t of [1, 2]) {
    const inv = t === 1 ? invOther : (await mkInv(s2.id, 'other-rep-2')).json;
    const g = await A('GET', `/api/invoices/${inv.id}`, undefined, s1tok);
    const p = await A('PATCH', `/api/invoices/${inv.id}`, { notes: 'touched-by-s1' }, s1tok);
    const c = await A('POST', `/api/invoices/${inv.id}/cancel`, undefined, s1tok);
    const after = await A('GET', `/api/invoices/${inv.id}`);
    log(`INV-026 try${t}`, { get: g.status, patch: p.status, cancel: c.status, statusAfter: after.json.status, notesAfter: after.json.notes, pocAfter: after.json.salesPoc?.username });
  }

  // ---- INV-027 bulk drops unknown / out-of-scope ids ----
  const b1 = await A('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [987654320, 987654321] });
  log('INV-027 admin unknown ids', b1.json || b1.text);
  const own = (await mkInv(s1.id, 'own-bulk')).json;
  const theirs = (await mkInv(s2.id, 'theirs-bulk')).json;
  const b2 = await A('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [own.id, theirs.id] }, s1tok);
  const theirsAfter = await A('GET', `/api/invoices/${theirs.id}`);
  log('INV-027 s1 bulk [own, theirs]', { own: own.id, theirs: theirs.id, res: b2.json, theirsStatus: theirsAfter.json.status });

  // ---- INV-028 ineligible reported as failed ----
  const unpaid = (await mkInv(s1.id, 'b-unpaid')).json;
  const paid = (await mkInv(s1.id, 'b-paid')).json;
  const pay = await A('POST', '/api/payments', { customerId: cust.id, amount: 100, method: 'CASH', notes: 'rt', invoiceIds: [paid.id], collectionPocUserId: coll.id });
  const canc = (await mkInv(s1.id, 'b-canc')).json;
  await A('POST', `/api/invoices/${canc.id}/cancel`);
  const paidNow = (await A('GET', `/api/invoices/${paid.id}`)).json.status;
  const b3 = await A('POST', '/api/invoices/bulk', { action: 'CANCEL', ids: [unpaid.id, paid.id, canc.id] });
  log('INV-028 bulk [unpaid, paid, cancelled]', { pay: pay.status, paidStatus: paidNow, ids: [unpaid.id, paid.id, canc.id], res: b3.json });

  // ---- INV-029 customer filters on POC columns ----
  const ctok = await rt.login(cust.username, rt.PASSWORD);
  const q = async (qs) => { const r = await A('GET', `/api/invoices?size=50&${qs}`, undefined, ctok); return { s: r.status, total: r.json?.totalElements, m: r.status >= 300 ? r.json?.message : undefined, pocInRow: r.json?.content?.[0]?.salesPoc }; };
  log('INV-029 customer baseline', await q(''));
  log('INV-029 filter salesPocUserId s1', await q(`filter=salesPocUserId:eq:${s1.id}`));
  log('INV-029 filter salesPocUserId s2', await q(`filter=salesPocUserId:eq:${s2.id}`));
  log('INV-029 filter salesPocUserId sDel', await q(`filter=salesPocUserId:eq:${sDel.id}`));
  log('INV-029 filter salesPocName contains s1 name', await q(`filter=${encodeURIComponent('salesPocName:contains:' + s1.username.toUpperCase().slice(0, 12))}`));
  log('INV-029 sort salesPocName', await q('sort=salesPocName,asc'));
  const sch = await A('GET', '/api/table-schemas/invoices', undefined, ctok);
  log('INV-029 schema cols for customer', sch.json.columns.map((c) => c.name));

  // ---- INV-030 notes > 500 ----
  const long = 'x'.repeat(501);
  const p30 = await A('PATCH', `/api/invoices/${inv25.id}`, { notes: long });
  const c30 = await mkInv(s1.id, long);
  const p30ok = await A('PATCH', `/api/invoices/${inv25.id}`, { notes: 'y'.repeat(500) });
  log('INV-030', { patch501: p30.status, patchMsg: (p30.json?.message || '').slice(0, 120), create501: c30.status, createMsg: (c30.json?.message || '').slice(0, 120), patch500: p30ok.status });

  // ---- INV-031 non-numeric id ----
  const g31 = await A('GET', '/api/invoices/abc');
  const c31 = await A('POST', '/api/invoices/abc/cancel');
  log('INV-031', { get: g31.status, getMsg: g31.json?.message, cancel: c31.status, cancelMsg: c31.json?.message });

  // ---- INV-032 paging overflow / sort direction ----
  const p32 = await A('GET', '/api/invoices?page=50000000&size=50');
  const p32b = await A('GET', '/api/invoices?page=1000&size=50');
  const s32 = await A('GET', '/api/invoices?sort=total,sideways&size=10');
  log('INV-032', { hugePage: p32.status, msg: p32.json?.message, bigButSafe: p32b.status, bigContent: p32b.json?.content?.length, sideways: s32.status, sortEcho: s32.json?.sort });

  // ---- INV-047 promise against cancelled invoice (backend part) ----
  const pr = await A('POST', '/api/promises', { customerId: cust.id, invoiceIds: [canc.id], amount: 10, promisedDate: '2026-12-01', collectionPocUserId: coll.id });
  const cancBal = (await A('GET', `/api/invoices/${canc.id}`)).json;
  log('INV-047 promise on cancelled', { s: pr.status, m: pr.json?.message, cancelledBalance: cancBal.balance, status: cancBal.status });

  fs.writeFileSync(__dirname + '/api-results.json', JSON.stringify(out, null, 2));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
