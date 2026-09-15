// POC book scoping: SALES_POC (no SCOPE_OVERRIDE) locked to its own invoices; COLLECTION/CS POCs unfiltered.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const st = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const qs = (arr) => arr.map((f) => 'filter=' + encodeURIComponent(f)).join('&');
const out = {};
const log = (k, v) => { out[k] = v; console.log(k, JSON.stringify(v).slice(0, 700)); };

(async () => {
  const admin = await rt.adminToken();
  const S1 = await rt.login(st.s1.username, rt.PASSWORD);
  const C1 = await rt.login(st.c1.username, rt.PASSWORD);
  const CS = await rt.login(st.cs1.username, rt.PASSWORD);
  const s1 = st.s1.id, s2 = st.s2.id;

  // B01 list scoped + lockedFilters + count equals admin's filter
  const l = await rt.api('GET', '/api/invoices?size=50', { token: S1 });
  const adm = await rt.api('GET', `/api/invoices?size=50&${qs([`salesPocUserId:eq:${s1}`])}`, { token: admin });
  log('B01_list', { status: l.status, total: l.json.totalElements, adminTotalS1: adm.json.totalElements, locked: l.json.lockedFilters, applied: l.json.appliedFilters,
    foreign: l.json.content.filter((i) => i.salesPoc?.id !== s1).map((i) => i.id) });

  // B02 cannot widen
  const w = {};
  for (const f of [`salesPocUserId:eq:${s2}`, `salesPocUserId:neq:${s1}`, `salesPocUserId:in:${s1},${s2}`, `customerId:eq:${st.custY.id}`, 'salesPocUserId:isEmpty:']) {
    const r = await rt.api('GET', `/api/invoices?size=50&${qs([f])}`, { token: S1 });
    w[f] = { status: r.status, total: r.json?.totalElements, foreign: (r.json?.content || []).filter((i) => i.salesPoc?.id !== s1).map((i) => i.id) };
  }
  w.sortSalesPocName = (await rt.api('GET', '/api/invoices?size=50&sort=salesPocName,desc', { token: S1 })).json?.totalElements;
  log('B02_widen', w);

  // B03 summary
  const sm = await rt.api('GET', '/api/invoices/summary', { token: S1 });
  const smA = await rt.api('GET', `/api/invoices/summary?${qs([`salesPocUserId:eq:${s1}`])}`, { token: admin });
  log('B03_summary', { s1: sm.json, adminS1: smA.json });

  // B04 bulk with ids of S2's invoice / select-all on Y (S2 only)
  const b1 = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'REASSIGN_SALES_POC', ids: [st.invX2.id], params: { userId: s1 } } });
  const b2 = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'REASSIGN_SALES_POC', selectAllMatchingFilter: true, filters: [`customerId:eq:${st.custY.id}`], params: { userId: s1 } } });
  const x2 = await rt.api('GET', `/api/invoices/${st.invX2.id}`, { token: admin });
  const y1 = await rt.api('GET', `/api/invoices/${st.invY1.id}`, { token: admin });
  log('B04_bulk', { explicit: b1.json, selectAllY: b2.json, x2SalesPoc: x2.json.salesPoc?.id, y1SalesPoc: y1.json.salesPoc?.id, s2 });

  // B05 export only own
  const ex = await rt.api('POST', '/api/invoices/export', { token: S1, body: { selectAllMatchingFilter: true } });
  const lines = ex.text.trim().split('\n');
  const pocCol = lines[0].split(',').indexOf('Sales POC');
  const foreignRows = lines.slice(1).filter((ln) => !ln.includes(st.s1.username));
  const exIds = await rt.api('POST', '/api/invoices/export', { token: S1, body: { ids: [st.invX2.id, st.invX1.id] } });
  log('B05_export', { status: ex.status, rows: lines.length - 1, pocCol, foreignRows: foreignRows.slice(0, 3), explicitIdsRows: exIds.text.trim().split('\n').length - 1, explicitBody: exIds.text.slice(0, 300) });

  // B06 by-id outside book
  const g = await rt.api('GET', `/api/invoices/${st.invY1.id}`, { token: S1 });
  log('B06_getOutsideBook', { status: g.status, salesPoc: g.json?.salesPoc?.username, customer: g.json?.customerName });

  // B07 PATCH / cancel outside book
  const freshS2 = (await rt.api('POST', '/api/invoices', { token: admin, body: { customerId: st.custY.id, salesPocUserId: s2, notes: 'book cancel target', items: [{ productId: st.product.id, quantity: 1 }] } })).json;
  const p = await rt.api('PATCH', `/api/invoices/${st.invY1.id}`, { token: S1, body: { notes: 'edited by s1 (outside book)' } });
  const pAfter = await rt.api('GET', `/api/invoices/${st.invY1.id}`, { token: admin });
  const c = await rt.api('POST', `/api/invoices/${freshS2.id}/cancel`, { token: S1 });
  const cAfter = await rt.api('GET', `/api/invoices/${freshS2.id}`, { token: admin });
  const bulkCancel = await rt.api('POST', '/api/invoices/bulk', { token: S1, body: { action: 'CANCEL', ids: [freshS2.id] } });
  log('B07_writeOutsideBook', { patch: p.status, notesAfter: pAfter.json.notes, cancel: c.status, statusAfter: cAfter.json.status, bulkCancelSameIdBefore: bulkCancel.json, freshS2: freshS2.id });

  // B08 customers book
  const cl = await rt.api('GET', '/api/customers?size=50', { token: S1 });
  const ids = cl.json.content.map((x) => x.id);
  const cy = await rt.api('GET', `/api/customers/${st.custY.id}`, { token: S1 });
  const cyl = await rt.api('GET', `/api/customers?${qs([`id:eq:${st.custY.id}`])}`, { token: S1 });
  log('B08_customers', { total: cl.json.totalElements, locked: cl.json.lockedFilters, hasA: ids.includes(st.custA.id), hasB: ids.includes(st.custB.id), hasX: ids.includes(st.custX.id), hasY: ids.includes(st.custY.id), yById: cy.status, yByFilter: cyl.json?.totalElements });

  // B09 my-scope
  log('B09_myScope', { s1: (await rt.api('GET', '/api/pocs/my-scope', { token: S1 })).json, c1: (await rt.api('GET', '/api/pocs/my-scope', { token: C1 })).json, cs: (await rt.api('GET', '/api/pocs/my-scope', { token: CS })).json });

  // B10 S1 payments / promises / disputes scope
  const sp = {};
  for (const e of ['payments', 'promises', 'disputes']) {
    const r = await rt.api('GET', `/api/${e}?size=50`, { token: S1 });
    const ra = await rt.api('GET', `/api/${e}?size=50`, { token: admin });
    sp[e] = { s1Total: r.json.totalElements, adminTotal: ra.json.totalElements, locked: r.json.lockedFilters, seesY: r.json.content.some((x) => x.customerId === st.custY.id), seesBPayOrProm: r.json.content.some((x) => x.id === (e === 'payments' ? st.payB.id : e === 'promises' ? st.promB.id : st.dispB.id)) };
  }
  log('B10_salesOtherLists', sp);

  // B11 C1 / CS unfiltered by default
  const cc = {};
  for (const [who, tok] of [['c1', C1], ['cs', CS]]) {
    for (const e of ['payments', 'promises', 'customers', 'invoices']) {
      const r = await rt.api('GET', `/api/${e}?size=50`, { token: tok });
      const ra = await rt.api('GET', `/api/${e}?size=50`, { token: admin });
      cc[`${who}.${e}`] = { status: r.status, total: r.json?.totalElements, adminTotal: ra.json?.totalElements, locked: r.json?.lockedFilters, applied: r.json?.appliedFilters };
    }
  }
  const c1pb = await rt.api('GET', `/api/payments/${st.payB.id}`, { token: C1 });
  cc.c1SeesPayB = c1pb.status;
  const c1own = await rt.api('GET', `/api/payments?size=50&${qs([`collectionPocUserId:eq:${st.c1.id}`])}`, { token: C1 });
  cc.c1NarrowOwn = { total: c1own.json.totalElements, foreign: c1own.json.content.filter((p) => p.collectionPoc?.id !== st.c1.id).length };
  log('B11_overrideRoles', cc);

  // B12 S1 edits customer seats outside its book (Y)
  const seat = await rt.api('POST', `/api/customers/${st.custY.id}/pocs`, { token: S1, body: { pocType: 'SUCCESS', userId: st.cs1.id, primary: false } });
  if (seat.status === 200) await rt.api('DELETE', `/api/customers/${st.custY.id}/pocs/${seat.json.id}`, { token: admin });
  const seatsY = await rt.api('GET', `/api/customers/${st.custY.id}/pocs`, { token: S1 });
  log('B12_seatOutsideBook', { add: seat.status, body: seat.text.slice(0, 160), listSeatsY: seatsY.status });

  // B13 audit outside book + USER audit
  await rt.api('PUT', `/api/users/${st.viewer.id}`, { token: admin, body: { fullName: st.viewer.fullName } });
  const au1 = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${st.invY1.id}`, { token: S1 });
  const au2 = await rt.api('GET', `/api/audit?entityType=USER&entityId=${st.viewer.id}`, { token: S1 });
  const au3 = await rt.api('GET', `/api/audit?entityType=USER&entityId=1`, { token: S1 });
  log('B13_audit', { invoiceOutsideBook: au1.status, invN: au1.json?.length, userAudit: au2.status, userAuditSample: au2.text.slice(0, 400), adminUserAudit: au3.status, adminSample: au3.text.slice(0, 200) });

  fs.writeFileSync(path.join(__dirname, 'book-result.json'), JSON.stringify(out, null, 2));
})().catch((e) => { console.error('BOOK FAILED', e); process.exit(1); });
