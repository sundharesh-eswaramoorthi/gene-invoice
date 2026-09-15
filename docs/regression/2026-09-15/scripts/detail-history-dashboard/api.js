// API checks: History timeline + detail endpoints (not-found / forbidden).
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const results = [];
function rec(id, title, pass, detail) { results.push({ id, title, pass, detail }); console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title} :: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`); }
const hist = (token, type, id, rel = true) => rt.api('GET', `/api/audit?entityType=${type}&entityId=${id}${rel ? '&includeRelated=true' : ''}`, { token });
const key = (e) => `${e.entityType}:${e.entityId}:${e.action}`;
const isNewestFirst = (l) => l.every((e, i) => i === 0 || (e.createdAt || '') <= (l[i - 1].createdAt || ''));

(async () => {
  const A = await rt.adminToken();
  const cA = await rt.login(S.custA.username, S.custA.password);
  const cB = await rt.login(S.custB.username, S.custB.password);

  // H1 customer A full history
  const h = await hist(A, 'CUSTOMER', S.custA.id);
  const L = h.json || [];
  const keys = new Set(L.map(key));
  const need = [
    `CUSTOMER:${S.custA.id}:CUSTOMER_CREATED`, `CUSTOMER:${S.custA.id}:CUSTOMER_UPDATED`,
    `CUSTOMER:${S.custA.id}:POC_ASSIGNED`, `CUSTOMER:${S.custA.id}:POC_REMOVED`, `CUSTOMER:${S.custA.id}:POC_PRIMARY_CHANGED`,
    `INVOICE:${S.A1.id}:INVOICE_CREATED`, `INVOICE:${S.A2.id}:INVOICE_CREATED`,
    `INVOICE:${S.A1.id}:PAYMENT_APPLIED`, `INVOICE:${S.A1.id}:PAYMENT_REVERSED`,
    `PAYMENT:${S.PA1.id}:PAYMENT_RECORDED`, `PAYMENT:${S.PA2.id}:PAYMENT_RECORDED`,
    `PROMISE:${S.PR1.id}:PROMISE_CREATED`, `PROMISE:${S.PR2.id}:PROMISE_CREATED`,
    `DISPUTE:${S.D1.id}:DISPUTE_OPENED`, `DISPUTE:${S.D1.id}:DISPUTE_DENIED`, `DISPUTE:${S.D2.id}:DISPUTE_OPENED`,
    `PAYMENT:${S.PA2.id}:DISPUTE_APPROVED`, `INVOICE:${S.A2.id}:DISPUTE_APPROVED`,
  ];
  const missing = need.filter((k) => !keys.has(k));
  const foreign = L.filter((e) => (e.entityType === 'INVOICE' && [S.B1.id, S.C1.id].includes(e.entityId)) || (e.entityType === 'PAYMENT' && e.entityId === S.PB1.id));
  rec('H1', 'customer A history contains every event, newest first, none of B/C', h.status === 200 && !missing.length && !foreign.length && isNewestFirst(L),
    { status: h.status, rows: L.length, missing, foreign: foreign.length, newestFirst: isNewestFirst(L), actions: [...new Set(L.map((e) => e.action))] });
  fs.writeFileSync(path.join(__dirname, 'hist-custA-admin.json'), JSON.stringify(L, null, 1));

  // H2 labels and amounts present
  const lab = (t, id) => L.find((e) => e.entityType === t && e.entityId === id)?.entityLabel;
  const invCreated = L.find((e) => key(e) === `INVOICE:${S.A1.id}:INVOICE_CREATED`);
  const payRec = L.find((e) => key(e) === `PAYMENT:${S.PA1.id}:PAYMENT_RECORDED`);
  const prCre = L.find((e) => key(e) === `PROMISE:${S.PR1.id}:PROMISE_CREATED`);
  const applied = L.filter((e) => key(e) === `INVOICE:${S.A1.id}:PAYMENT_APPLIED`);
  const ok2 = lab('INVOICE', S.A1.id) === S.A1.invoiceNumber && lab('PAYMENT', S.PA1.id) === `Payment #${S.PA1.id}` && lab('PROMISE', S.PR1.id) === `Promise #${S.PR1.id}`
    && lab('DISPUTE', S.D1.id) === `Dispute #${S.D1.id}`
    && invCreated && /"total":200/.test(invCreated.afterJson || '') && payRec && /"amount":50/.test(payRec.afterJson || '')
    && prCre && /"amount":100/.test(prCre.afterJson || '') && applied.some((e) => /"paymentId":\s*109|"paymentId":/.test(e.afterJson || ''));
  rec('H2', 'rows carry entityLabel and amounts in snapshots', !!ok2, {
    invLabel: lab('INVOICE', S.A1.id), payLabel: lab('PAYMENT', S.PA1.id), prLabel: lab('PROMISE', S.PR1.id), dLabel: lab('DISPUTE', S.D1.id),
    invCreatedAfter: invCreated?.afterJson?.slice(0, 160), payRecAfter: payRec?.afterJson?.slice(0, 160), prAfter: prCre?.afterJson?.slice(0, 160), appliedAfter: applied.map((a) => a.afterJson?.slice(0, 120)),
  });

  // H3 invoice A1 history: own events + payments applied/reversed + promises covering it + disputes on it; not A2's
  const hi = await hist(A, 'INVOICE', S.A1.id);
  const LI = hi.json || [];
  const ki = new Set(LI.map(key));
  const needI = [`INVOICE:${S.A1.id}:INVOICE_CREATED`, `INVOICE:${S.A1.id}:PAYMENT_APPLIED`, `INVOICE:${S.A1.id}:PAYMENT_REVERSED`,
    `PROMISE:${S.PR1.id}:PROMISE_CREATED`, `DISPUTE:${S.D1.id}:DISPUTE_OPENED`, `DISPUTE:${S.D1.id}:DISPUTE_DENIED`, `DISPUTE:${S.D2.id}:DISPUTE_OPENED`];
  const missI = needI.filter((k) => !ki.has(k));
  const leakI = LI.filter((e) => (e.entityType === 'INVOICE' && e.entityId !== S.A1.id) || (e.entityType === 'PROMISE' && [S.PR2.id, S.PR3.id].includes(e.entityId))
    || (e.entityType === 'DISPUTE' && [S.D3.id, S.D4.id].includes(e.entityId)) || (e.entityType === 'CUSTOMER'));
  rec('H3', 'invoice A1 history: own + applied/reversed + promise PR1 + disputes D1/D2, nothing of A2/PR2/PR3/D3/D4', hi.status === 200 && !missI.length && !leakI.length && isNewestFirst(LI),
    { status: hi.status, rows: LI.length, missing: missI, leaks: leakI.map(key), keys: [...ki] });
  fs.writeFileSync(path.join(__dirname, 'hist-A1-admin.json'), JSON.stringify(LI, null, 1));

  // H3b: does invoice A1 history include PAYMENT_RECORDED of payments applied to it? (design: "covers the payments applied to it")
  const payRowsOnInv = LI.filter((e) => e.entityType === 'PAYMENT');
  rec('H3b', 'invoice A1 history: payments applied shown (as INVOICE PAYMENT_APPLIED rows with paymentId)', applied.length >= 2 && LI.filter((e) => e.action === 'PAYMENT_APPLIED').length >= 2,
    { paymentAppliedRows: LI.filter((e) => e.action === 'PAYMENT_APPLIED').map((e) => e.afterJson?.slice(0, 100)), paymentEntityRows: payRowsOnInv.map(key) });

  // H4 payment PA2 history: payment events + dispute D3 (+ approval) ; promise PR2 (general) may be linked
  const hp = await hist(A, 'PAYMENT', S.PA2.id);
  const LP = hp.json || [];
  const kp = new Set(LP.map(key));
  const needP = [`PAYMENT:${S.PA2.id}:PAYMENT_RECORDED`, `DISPUTE:${S.D3.id}:DISPUTE_OPENED`, `PAYMENT:${S.PA2.id}:DISPUTE_APPROVED`];
  const missP = needP.filter((k) => !kp.has(k));
  const leakP = LP.filter((e) => (e.entityType === 'PAYMENT' && e.entityId !== S.PA2.id) || (e.entityType === 'DISPUTE' && e.entityId !== S.D3.id));
  rec('H4', 'payment PA2 history: recorded + dispute D3 opened + approval; no other payment/dispute', hp.status === 200 && !missP.length && !leakP.length,
    { status: hp.status, rows: LP.length, missing: missP, leaks: leakP.map(key), keys: [...kp] });

  // H5 customer login's history of own customer: no POC events / keys, staff hidden
  const hc = await hist(cA, 'CUSTOMER', S.custA.id);
  const LC = hc.json || [];
  const pocRows = LC.filter((e) => e.action.includes('POC'));
  const pocKeys = LC.filter((e) => /poc/i.test(e.beforeJson || '') || /poc/i.test(e.afterJson || ''));
  const staffIds = [S.sales.id, S.coll.id, S.coll2.id, S.succ.id, 1];
  const staffNames = LC.filter((e) => e.changedByUsername && e.changedByUsername !== S.custA.username);
  const staffIdShown = LC.filter((e) => e.changedByUserId && staffIds.includes(e.changedByUserId));
  const staffInJson = LC.filter((e) => [S.sales.username, S.coll.username, S.coll2.username, 'admin'].some((u) => (e.afterJson || '').includes(`"${u}"`) || (e.beforeJson || '').includes(`"${u}"`)));
  const hiddenCount = LC.filter((e) => e.actorHidden).length;
  const ownNamed = LC.filter((e) => e.changedByUsername === S.custA.username).length;
  rec('H5', 'customer login history: no POC events/keys, no staff usernames, actorHidden set', hc.status === 200 && !pocRows.length && !pocKeys.length && !staffNames.length && !staffIdShown.length && !staffInJson.length && hiddenCount > 0,
    { status: hc.status, rows: LC.length, pocRows: pocRows.length, pocKeyRows: pocKeys.map((e) => key(e) + ' ' + (e.afterJson || '').slice(0, 80)), staffNames: staffNames.map((e) => e.changedByUsername), staffIdShown: staffIdShown.length, staffInJson: staffInJson.map((e) => key(e) + ':' + (e.afterJson || '').match(/"(openedBy|resolvedBy|changedBy|createdBy)[^,]*/g)), actorHidden: hiddenCount, ownNamed });
  fs.writeFileSync(path.join(__dirname, 'hist-custA-customer.json'), JSON.stringify(LC, null, 1));

  // H5b customer login's invoice history
  const hci = await hist(cA, 'INVOICE', S.A1.id);
  const LCI = hci.json || [];
  rec('H5b', 'customer login invoice A1 history: 200, no POC keys, no staff names', hci.status === 200 && !LCI.some((e) => /poc/i.test((e.beforeJson || '') + (e.afterJson || '')) || e.action.includes('POC')) && !LCI.some((e) => e.changedByUsername && e.changedByUsername !== S.custA.username),
    { status: hci.status, rows: LCI.length, sample: LCI.slice(0, 3).map((e) => ({ k: key(e), who: e.changedByUsername, hidden: e.actorHidden, after: (e.afterJson || '').slice(0, 120) })) });

  // H5c: staff identity fields in snapshots other than *poc* keys (e.g. openedByUserId, resolvedByUserId, createdByUserId, salesPoc...)
  const idLike = LC.flatMap((e) => ((e.afterJson || '') + (e.beforeJson || '')).match(/"(\w*(UserId|ByUserId|Username|ByUsername))":\s*("[^"]*"|\d+)/g) || []);
  rec('H5c', 'customer login history snapshots carry no staff user ids/usernames (openedBy/resolvedBy/createdBy)', idLike.filter((s) => !s.includes(String(S.custA.id)) && !s.includes('null')).length === 0,
    { found: [...new Set(idLike)].slice(0, 20) });

  // H6 customer B cannot read customer A history (403) ; invoice/payment of A -> 403
  const x1 = await hist(cB, 'CUSTOMER', S.custA.id);
  const x2 = await hist(cB, 'INVOICE', S.A1.id);
  const x3 = await hist(cB, 'PAYMENT', S.PA1.id);
  const x4 = await hist(cB, 'PROMISE', S.PR1.id, false);
  rec('H6', 'customer B reading customer A history/invoice/payment/promise -> 403', [x1, x2, x3, x4].every((r) => r.status === 403),
    [x1, x2, x3, x4].map((r) => `${r.status} ${r.text.slice(0, 80)}`));

  // H7 bad entity type / unsupported for customer / not found
  const b1 = await rt.api('GET', `/api/audit?entityType=BOGUS&entityId=1`, { token: A });
  const b2 = await hist(A, 'INVOICE', 999999);
  const b3 = await hist(A, 'CUSTOMER', 999999);
  const b4 = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=abc`, { token: A });
  const b5 = await rt.api('GET', `/api/audit?entityType=USER&entityId=1`, { token: cA });
  const b6 = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${S.custA.id}`);
  rec('H7', 'audit validation: BOGUS 400, missing invoice/customer 404, id=abc 400, customer USER 403, no token 401', b1.status === 400 && b2.status === 404 && b3.status === 404 && b4.status === 400 && b5.status === 403 && b6.status === 401,
    { bogus: `${b1.status} ${b1.json?.message}`, inv404: `${b2.status} ${b2.json?.message}`, cust404: `${b3.status}`, abc: `${b4.status} ${b4.text.slice(0, 100)}`, custUser: b5.status, noToken: b6.status });

  // H8 >500 char dispute reason still opens and is in history (approval of D4 with long reason)
  const d2 = await rt.api('GET', `/api/disputes/${S.D2.id}`, { token: A });
  const d2row = L.find((e) => key(e) === `DISPUTE:${S.D2.id}:DISPUTE_OPENED`);
  const d4row = L.find((e) => key(e) === `INVOICE:${S.A2.id}:DISPUTE_APPROVED`);
  const d2full = (d2row?.afterJson || '').includes(' END');
  rec('H8', 'dispute with >500 char reason: created 200, GET full reason, audit reason truncated to 500, full text in snapshot', d2.status === 200 && d2.json.reason === S.longReason && d2row && d2row.reason.length <= 500 && d2full && d4row && d4row.reason.length <= 500,
    { get: d2.status, reasonLen: d2.json?.reason?.length, auditReasonLen: d2row?.reason?.length, auditReasonTail: d2row?.reason?.slice(-5), fullInSnapshot: d2full, d4ApproveReasonLen: d4row?.reason?.length });

  // H9 > 100 rows on C1
  const h9 = await hist(A, 'INVOICE', S.C1.id);
  const h9c = await hist(A, 'CUSTOMER', S.custC.id);
  rec('H9', 'invoice C1 history > 100 rows (for Show more), newest first', h9.status === 200 && h9.json.length > 100 && isNewestFirst(h9.json),
    { rows: h9.json?.length, custRows: h9c.json?.length, first: h9.json?.[0]?.action, firstAfter: h9.json?.[0]?.afterJson?.slice(0, 80) });

  // H10 non-related mode (includeRelated=false) returns only own entity rows
  const h10 = await hist(A, 'CUSTOMER', S.custA.id, false);
  rec('H10', 'includeRelated=false: only the customer\'s own rows', h10.status === 200 && h10.json.every((e) => e.entityType === 'CUSTOMER' && e.entityId === S.custA.id),
    { rows: h10.json?.length, types: [...new Set((h10.json || []).map((e) => e.entityType))] });

  // D1..D4 detail endpoints: not-found & forbidden
  const g = async (p, t) => (await rt.api('GET', p, { token: t })).status;
  const det = {
    inv999: await g('/api/invoices/999999', A), invAbc: await g('/api/invoices/abc', A), cust999: await g('/api/customers/999999', A), pay999: await g('/api/payments/999999', A),
    cBinvA: await g(`/api/invoices/${S.A1.id}`, cB), cBcustA: await g(`/api/customers/${S.custA.id}`, cB), cBpayA: await g(`/api/payments/${S.PA1.id}`, cB),
    cAown: await g(`/api/invoices/${S.A1.id}`, cA), cAcust: await g(`/api/customers/${S.custA.id}`, cA), cApay: await g(`/api/payments/${S.PA1.id}`, cA),
  };
  rec('DET-API', 'detail endpoints: 404 unknown, 400 malformed, 403 other customer, 200 own', det.inv999 === 404 && det.cust999 === 404 && det.pay999 === 404 && det.invAbc === 400
    && det.cBinvA === 403 && det.cBcustA === 403 && det.cBpayA === 403 && det.cAown === 200 && det.cAcust === 200 && det.cApay === 200, det);

  // Promises tab API for invoice: invoiceId filter works (regression of 400)
  const pt = await rt.api('GET', `/api/promises?size=50&customerId=${S.custA.id}&invoiceId=${S.A1.id}`, { token: A });
  rec('TAB-PROM-API', 'invoice promises tab query (customerId+invoiceId) -> 200 with only PR1', pt.status === 200 && pt.json.content.map((p) => p.id).join() === String(S.PR1.id),
    { status: pt.status, ids: pt.json?.content?.map((p) => p.id), msg: pt.json?.message });
  const dt = await rt.api('GET', `/api/disputes?size=50&targetType=INVOICE&targetId=${S.A1.id}`, { token: A });
  const dtc = await rt.api('GET', `/api/disputes?size=50&customerId=${S.custA.id}`, { token: A });
  rec('TAB-DISP-API', 'disputes tab queries: invoice A1 -> D1,D2; customer A -> D1..D4', dt.status === 200 && dt.json.content.map((d) => d.id).sort().join() === [S.D1.id, S.D2.id].sort().join()
    && dtc.status === 200 && dtc.json.content.length === 4,
    { inv: dt.json?.content?.map((d) => d.id), cust: dtc.json?.content?.map((d) => d.id), st: [dt.status, dtc.status] });

  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify(results, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
