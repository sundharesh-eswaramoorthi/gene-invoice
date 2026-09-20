// Area F / payment promises and disputes — the pre-existing flows, end to end.
const { rt, recorder, eq, admin, createProduct, createInvoice, recordPayment, isoDay } = require('./_h.js');

(async () => {
  const r = recorder('04-promises-disputes');
  const { token, id: adminId } = await admin();
  const cust = await rt.createCustomer(token, 'f');
  const custToken = await rt.login(cust.username, cust.password);
  const prod = await createProduct(token, 'f-pd', '50.00');
  const mkInv = (qty) => createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
    items: [{ productId: prod.id, quantity: qty, unitPrice: '50.00' }] });

  // ---- F-35..F-38 promises ------------------------------------------------------
  const invA = await mkInv(2); // 100
  let promise;
  await r.check({
    id: 'F-35', feature: 'Payment promise', kind: 'API', ac: 'AC-A10',
    title: 'A promise against an unpaid invoice is still created OPEN',
    steps: 'POST /api/promises {customerId, amount 100, promisedDate +7d, invoiceIds:[A]}',
    expected: '200, status OPEN, fulfilledAmount 0, remainingAmount 100, the invoice linked',
    codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:76',
  }, async () => {
    const res = await rt.api('POST', '/api/promises', { token,
      body: { customerId: cust.id, amount: '100.00', promisedDate: isoDay(7),
        collectionPocUserId: adminId, notes: 'area-f', invoiceIds: [invA.id] } });
    promise = res.json;
    const ok = res.status === 200 && promise.status === 'OPEN' && eq(promise.fulfilledAmount, 0)
      && eq(promise.remainingAmount, 100) && (promise.invoices || []).some((i) => i.id === invA.id);
    return { ok, actual: `${res.status} status=${promise?.status} fulfilled=${promise?.fulfilledAmount} remaining=${promise?.remainingAmount} invoices=${(promise?.invoices || []).length}` };
  });

  await r.check({
    id: 'F-36', feature: 'Payment promise', kind: 'API', ac: 'AC-A10',
    title: 'Paying the promised money flips the promise OPEN -> KEPT',
    steps: 'POST /api/payments amount 100 against invoice A; GET /api/promises/{id}',
    expected: 'status KEPT, fulfilledAmount 100, the payment linked to the promise',
    codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:292',
  }, async () => {
    await recordPayment(token, { customerId: cust.id, amount: '100.00', method: 'CASH',
      collectionPocUserId: adminId, invoiceIds: [invA.id] });
    const p = (await rt.api('GET', `/api/promises/${promise.id}`, { token })).json;
    return { ok: p.status === 'KEPT' && eq(p.fulfilledAmount, 100),
      severity: 'high',
      actual: `status=${p.status} fulfilled=${p.fulfilledAmount} payments=${(p.payments || []).length}` };
  });

  await r.check({
    id: 'F-37', feature: 'Payment promise', kind: 'API', ac: 'AC-A10',
    title: 'A promise whose date has gone with nothing paid reads BROKEN',
    steps: 'raise a second unpaid invoice; POST /api/promises with promisedDate = yesterday and nothing paid',
    expected: 'status BROKEN as soon as it is evaluated',
    codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java:331',
  }, async () => {
    const invB = await mkInv(3); // 150
    const res = await rt.api('POST', '/api/promises', { token,
      body: { customerId: cust.id, amount: '150.00', promisedDate: isoDay(-1),
        collectionPocUserId: adminId, invoiceIds: [invB.id] } });
    return { ok: res.status === 200 && res.json.status === 'BROKEN',
      actual: `${res.status} status=${res.json?.status} fulfilled=${res.json?.fulfilledAmount}` };
  });

  await r.check({
    id: 'F-38', feature: 'Payment promise', kind: 'API',
    title: 'Promises list, filter and summary tiles still work',
    steps: 'GET /api/promises?customerId=..&filter=status:eq:BROKEN and GET /api/promises/summary?customerId=..',
    expected: 'the filter returns only BROKEN promises; the tiles count every promise of the customer',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:215',
  }, async () => {
    const all = await rt.api('GET', `/api/promises?customerId=${cust.id}&size=50`, { token });
    const broken = await rt.api('GET', `/api/promises?customerId=${cust.id}&filter=status:eq:BROKEN&size=50`, { token });
    const t = await rt.api('GET', `/api/promises/summary?customerId=${cust.id}`, { token });
    const tileCount = t.json.count ?? t.json.total ?? Object.values(t.json).find((v) => typeof v === 'number');
    const ok = all.status === 200 && broken.json.content.every((p) => p.status === 'BROKEN')
      && broken.json.content.length === 1 && t.status === 200
      && tileCount === all.json.totalElements;
    return { ok, actual: `all=${all.json.totalElements} broken=${broken.json.content.length} tiles=${JSON.stringify(t.json)}` };
  });

  // ---- F-39..F-43 disputes -------------------------------------------------------
  const invC = await mkInv(4); // 200
  let dispute;
  await r.check({
    id: 'F-39', feature: 'Dispute', kind: 'API',
    title: 'A customer can still raise a dispute on their own invoice',
    steps: 'POST /api/disputes as the customer login {targetType:INVOICE, targetId, reason, proposedChangeJson}',
    expected: '200, status PENDING, the target invoice summarised on the dispute',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:63',
  }, async () => {
    const res = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'INVOICE', targetId: invC.id, reason: 'area-f: goods not delivered',
        proposedChangeJson: '{"action":"cancel"}' } });
    dispute = res.json;
    const ok = res.status === 200 && dispute.status === 'PENDING'
      && dispute.targetType === 'INVOICE' && dispute.targetId === invC.id
      && dispute.targetNumber === invC.invoiceNumber;
    return { ok, severity: 'high',
      actual: `${res.status} status=${dispute?.status} target=${dispute?.targetType}#${dispute?.targetId} number=${dispute?.targetNumber}` };
  });

  await r.check({
    id: 'F-40', feature: 'Dispute', kind: 'API',
    title: 'A second open dispute on the same record is still refused',
    steps: 'POST /api/disputes again for the same invoice',
    expected: '400 "An open dispute already exists"',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:71',
  }, async () => {
    const res = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'INVOICE', targetId: invC.id, reason: 'again' } });
    return { ok: res.status === 400 && /already exists/i.test(res.text), severity: 'low',
      actual: `${res.status} ${res.text.slice(0, 140)}` };
  });

  await r.check({
    id: 'F-41', feature: 'Dispute', kind: 'API',
    title: 'Approving a dispute applies the proposed change and resolves it',
    steps: 'POST /api/disputes/{id}/approve as admin',
    expected: 'dispute APPROVED with resolvedAt/resolvedByUserId; the invoice is CANCELLED',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:150',
  }, async () => {
    const res = await rt.api('POST', `/api/disputes/${dispute.id}/approve`, { token,
      body: { adminNotes: 'area-f approved' } });
    const inv = (await rt.api('GET', `/api/invoices/${invC.id}`, { token })).json;
    const ok = res.status === 200 && res.json.status === 'APPROVED' && res.json.resolvedAt
      && res.json.resolvedByUserId === adminId && inv.status === 'CANCELLED';
    return { ok, severity: 'high',
      actual: `${res.status} dispute=${res.json?.status} resolvedBy=${res.json?.resolvedByUserId} invoice=${inv.status}` };
  });

  await r.check({
    id: 'F-42', feature: 'Dispute', kind: 'API',
    title: 'Denying a dispute resolves it and leaves the target untouched',
    steps: 'customer raises a dispute on a fresh invoice; admin POSTs /deny',
    expected: 'dispute DENIED; the invoice keeps its status and total',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:180',
  }, async () => {
    const invD = await mkInv(1);
    const d = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'INVOICE', targetId: invD.id, reason: 'area-f deny path',
        proposedChangeJson: '{"action":"cancel"}' } });
    const res = await rt.api('POST', `/api/disputes/${d.json.id}/deny`, { token,
      body: { adminNotes: 'area-f denied' } });
    const inv = (await rt.api('GET', `/api/invoices/${invD.id}`, { token })).json;
    const ok = res.status === 200 && res.json.status === 'DENIED'
      && inv.status === invD.status && eq(inv.total, invD.total);
    return { ok, severity: 'high',
      actual: `${res.status} dispute=${res.json?.status} invoice status=${inv.status} (was ${invD.status}) total=${inv.total}` };
  });

  await r.check({
    id: 'F-43', feature: 'Dispute list', kind: 'API',
    title: 'Disputes list still filters and paginates server-side, and a resolved dispute cannot be re-resolved',
    steps: 'GET /api/disputes?customerId=..&filter=status:eq:APPROVED; POST /approve on the already-denied one',
    expected: 'the filter returns only APPROVED rows; re-resolving is a 400',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:322',
  }, async () => {
    const list = await rt.api('GET', `/api/disputes?customerId=${cust.id}&size=50`, { token });
    const approved = await rt.api('GET', `/api/disputes?customerId=${cust.id}&filter=status:eq:APPROVED&size=50`, { token });
    const again = await rt.api('POST', `/api/disputes/${dispute.id}/approve`, { token, body: {} });
    const ok = list.status === 200 && list.json.totalElements >= 2
      && approved.json.content.every((d) => d.status === 'APPROVED')
      && approved.json.content.length === 1 && again.status === 400;
    return { ok, actual: `all=${list.json.totalElements} approved=${approved.json.content.length} reApprove=${again.status} ${again.text.slice(0, 100)}` };
  });

  r.save();
})();
