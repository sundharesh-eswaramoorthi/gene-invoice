// Area F / the rest of the blast radius — cashier capability, bulk actions, audit, notifications,
// products CRUD and concurrent money.
const { rt, recorder, money, eq, admin, createProduct, createInvoice, recordPayment } = require('./_h.js');

(async () => {
  const r = recorder('08-misc-blast-radius');
  const { token, id: adminId } = await admin();
  const cashier = await rt.cashierToken();
  const prod = await createProduct(token, 'f-misc', '100.00');

  // ---- F-83 CASHIER keeps everything it could do before -------------------------
  await r.check({
    id: 'F-83', feature: 'Role capability', kind: 'API',
    title: 'CASHIER can still create a customer, raise an invoice, record a payment and export',
    steps: 'as cashier: POST /api/customers, POST /api/invoices, POST /api/payments, POST /api/invoices/export, GET /api/products',
    expected: 'all succeed (§8 "CASHIER must retain everything it can do today")',
    codeRef: 'backend/src/main/java/com/geneinvoice/config/DataSeeder.java:69',
  }, async () => {
    const name = rt.uniq('f-cash');
    const c = await rt.api('POST', '/api/customers', { token: cashier,
      body: { name, phone: '555-0102', email: `${name}@rt.local`, address: '4 Cash Lane',
        username: name, password: rt.PASSWORD } });
    const i = await rt.api('POST', '/api/invoices', { token: cashier,
      body: { customerId: c.json?.id, salesPocUserId: adminId,
        items: [{ productId: prod.id, quantity: 1, unitPrice: '100.00' }] } });
    const p = await rt.api('POST', '/api/payments', { token: cashier,
      body: { customerId: c.json?.id, amount: '40.00', method: 'CASH', collectionPocUserId: adminId } });
    const x = await rt.api('POST', '/api/invoices/export', { token: cashier,
      body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`customerName:contains:${name}`] } });
    const prods = await rt.api('GET', '/api/products?size=10', { token: cashier });
    const ok = c.status === 200 && i.status === 200 && p.status === 200 && x.status === 200
      && prods.status === 200;
    return { ok, severity: 'high',
      actual: `customer=${c.status} invoice=${i.status} payment=${p.status} export=${x.status} products=${prods.status}` };
  });

  // ---- F-84/F-85 bulk actions ----------------------------------------------------
  const cust = await rt.createCustomer(token, 'f');
  const mk = () => createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
    items: [{ productId: prod.id, quantity: 1, unitPrice: '100.00' }] });
  await r.check({
    id: 'F-84', feature: 'Bulk actions', kind: 'API',
    title: 'Bulk CANCEL on invoices still cancels each named invoice',
    steps: 'create 3 invoices; POST /api/invoices/bulk {action:CANCEL, ids:[...]}',
    expected: 'all 3 in succeeded; all 3 read back CANCELLED',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:112',
  }, async () => {
    const invs = [await mk(), await mk(), await mk()];
    const res = await rt.api('POST', '/api/invoices/bulk', { token,
      body: { action: 'CANCEL', ids: invs.map((i) => i.id) } });
    const after = await Promise.all(invs.map((i) =>
      rt.api('GET', `/api/invoices/${i.id}`, { token }).then((x) => x.json.status)));
    return { ok: res.status === 200 && (res.json.succeeded || []).length === 3
        && after.every((s) => s === 'CANCELLED'),
      actual: `${res.status} succeeded=${(res.json?.succeeded || []).length} failed=${JSON.stringify(res.json?.failed)} statuses=${after.join('/')}` };
  });

  await r.check({
    id: 'F-85', feature: 'Bulk actions', kind: 'API',
    title: 'Bulk REASSIGN_SALES_POC on invoices and ADD_POC on customers still work',
    steps: 'POST /api/invoices/bulk {REASSIGN_SALES_POC, params.userId}; POST /api/customers/bulk {ADD_POC}',
    expected: 'both report the records as succeeded and the assignment shows on the record',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerController.java:128',
  }, async () => {
    const sales = await rt.createStaff(token, 'SALES_POC', 'f');
    const success = await rt.createStaff(token, 'CUSTOMER_SUCCESS_POC', 'f');
    const inv = await mk();
    const a = await rt.api('POST', '/api/invoices/bulk', { token,
      body: { action: 'REASSIGN_SALES_POC', ids: [inv.id], params: { userId: sales.id } } });
    const b = await rt.api('POST', '/api/customers/bulk', { token,
      body: { action: 'ADD_POC', ids: [cust.id], params: { pocType: 'SUCCESS', userId: success.id } } });
    const readInv = (await rt.api('GET', `/api/invoices/${inv.id}`, { token })).json;
    const readCust = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const ok = a.status === 200 && (a.json.succeeded || []).length === 1
      && readInv.salesPoc?.id === sales.id
      && b.status === 200 && (b.json.succeeded || []).length === 1
      && (readCust.successPocs || []).some((s) => s.user.id === success.id);
    return { ok, actual: `reassign=${a.status}/${JSON.stringify(a.json?.succeeded)} invoicePoc=${readInv.salesPoc?.id} expected=${sales.id}; addPoc=${b.status}/${JSON.stringify(b.json?.succeeded)} seats=${(readCust.successPocs || []).length}` };
  });

  // ---- F-86 audit history ----------------------------------------------------------
  await r.check({
    id: 'F-86', feature: 'Audit history', kind: 'API',
    title: "An invoice's History still records creation and the payments that land on it",
    steps: 'raise an invoice, pay it, GET /api/audit?entityType=INVOICE&entityId=..',
    expected: 'rows for INVOICE_CREATED and PAYMENT_APPLIED, newest first, each naming the actor',
    codeRef: 'backend/src/main/java/com/geneinvoice/audit/AuditTimelineService.java',
  }, async () => {
    const inv = await mk();
    await recordPayment(token, { customerId: cust.id, amount: '100.00', method: 'CASH',
      collectionPocUserId: adminId, invoiceIds: [inv.id] });
    const res = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${inv.id}`, { token });
    const rows = res.json.content || res.json;
    const actions = rows.map((x) => x.action);
    const ok = res.status === 200 && actions.includes('INVOICE_CREATED')
      && actions.includes('PAYMENT_APPLIED') && rows.every((x) => x.changedByUsername || x.actorHidden);
    return { ok, actual: `${res.status} actions=${actions.join(',')}` };
  });

  // ---- F-87 notifications -----------------------------------------------------------
  await r.check({
    id: 'F-87', feature: 'Notifications', kind: 'API',
    title: 'Notifications still list, count unread and mark read',
    steps: 'GET /api/notifications, GET /unread-count, POST /{id}/read, GET /unread-count again',
    expected: 'the list answers, and marking one read lowers the unread count by one',
    codeRef: 'backend/src/main/java/com/geneinvoice/notification/NotificationController.java',
  }, async () => {
    const list = await rt.api('GET', '/api/notifications?size=10', { token });
    const before = (await rt.api('GET', '/api/notifications/unread-count', { token })).json.count;
    const unread = (list.json.content || []).find((n) => !n.read);
    if (!unread) return { ok: list.status === 200 && before === 0, status: 'PASS',
      actual: `list=${list.status}, nothing unread to mark (count=${before})` };
    const mark = await rt.api('POST', `/api/notifications/${unread.id}/read`, { token });
    const after = (await rt.api('GET', '/api/notifications/unread-count', { token })).json.count;
    return { ok: list.status === 200 && mark.status < 300 && after === before - 1,
      actual: `list=${list.status} unread ${before} -> ${after} after marking #${unread.id} (mark=${mark.status})` };
  });

  // ---- F-88 products CRUD -------------------------------------------------------------
  await r.check({
    id: 'F-88', feature: 'Products', kind: 'API',
    title: 'Products still create, edit, deactivate in bulk and delete',
    steps: 'POST /api/products, PUT /api/products/{id}, POST /api/products/bulk {DEACTIVATE}, DELETE',
    expected: 'each step succeeds and the change is visible on the next read',
    codeRef: 'backend/src/main/java/com/geneinvoice/product/ProductController.java:86',
  }, async () => {
    const p = await createProduct(token, 'f-crud', '12.34');
    const upd = await rt.api('PUT', `/api/products/${p.id}`, { token,
      body: { name: p.name, description: 'edited by area-f', price: '56.78', active: true } });
    const bulk = await rt.api('POST', '/api/products/bulk', { token,
      body: { action: 'DEACTIVATE', ids: [p.id] } });
    const read = (await rt.api('GET', `/api/products/${p.id}`, { token })).json;
    const del = await rt.api('DELETE', `/api/products/${p.id}`, { token });
    const gone = await rt.api('GET', `/api/products/${p.id}`, { token });
    const ok = upd.status === 200 && eq(upd.json.price, 56.78) && bulk.status === 200
      && read.active === false && del.status < 300 && gone.status === 404;
    return { ok, actual: `update=${upd.status} price=${upd.json?.price} bulk=${bulk.status} active=${read.active} delete=${del.status} get=${gone.status}` };
  });

  // ---- F-89 concurrent money -----------------------------------------------------------
  await r.check({
    id: 'F-89', feature: 'Payment concurrency', kind: 'API',
    title: 'Two payments recorded at the same instant on one customer never lose money',
    steps: 'one 400.00 invoice; POST /api/payments x4 of 100.00 in parallel',
    expected: 'the invoice ends FULLY_PAID at 400.00 paid, the customer credit is unchanged and the four payments together account for 400.00',
    codeRef: 'backend/src/main/java/com/geneinvoice/payment/PaymentService.java:54',
  }, async () => {
    const solo = await rt.createCustomer(token, 'f');
    const inv = await createInvoice(token, { customerId: solo.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 4, unitPrice: '100.00' }] });
    const res = await Promise.all(Array.from({ length: 4 }, () => rt.api('POST', '/api/payments', {
      token, body: { customerId: solo.id, amount: '100.00', method: 'CASH', collectionPocUserId: adminId },
    })));
    const after = (await rt.api('GET', `/api/invoices/${inv.id}`, { token })).json;
    const c = (await rt.api('GET', `/api/customers/${solo.id}`, { token })).json;
    const created = res.filter((x) => x.status === 200);
    const total = created.reduce((a, x) => a + Number(x.json.amount), 0);
    const ok = created.length === 4 && eq(after.paidAmount, 400) && after.status === 'FULLY_PAID'
      && eq(c.creditBalance, 0) && eq(total, 400);
    return { ok, severity: 'high',
      actual: `created=${created.length}/4 invoice paid=${after.paidAmount}/${after.status} credit=${c.creditBalance} paymentsTotal=${money(total)}` };
  });

  // ---- F-90 invoice CSV keeps its pre-existing columns -----------------------------------
  await r.check({
    id: 'F-90', feature: 'CSV export — invoices', kind: 'API', ac: 'AC-A3',
    title: 'The invoice CSV keeps every pre-existing column and gains due date and overdue',
    steps: 'POST /api/invoices/export filtered to my customer',
    expected: 'header is Invoice #, Customer, Date, Due date, Total, Paid, Balance, Status, Overdue (+ Sales POC for a POC-aware caller); the row matches the invoice',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:141',
  }, async () => {
    const { parseCsv } = require('./_h.js');
    const res = await rt.api('POST', '/api/invoices/export', { token,
      body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`customerName:contains:${cust.name}`] } });
    const rows = parseCsv(res.text || '');
    const header = rows[0] || [];
    const want = ['Invoice #', 'Customer', 'Date', 'Due date', 'Total', 'Paid', 'Balance', 'Status', 'Overdue'];
    const ok = res.status === 200 && want.every((h, i) => header[i] === h) && rows.length > 1
      && rows.slice(1).every((row) => row[1] === cust.name);
    return { ok, actual: `${res.status} header=[${header.join('|')}] rows=${rows.length - 1}` };
  });

  // ---- F-94..F-96 pre-existing invoice flows the due-date work could have moved -----------
  const custToken = await rt.login(cust.username, cust.password);
  await r.check({
    id: 'F-94', feature: 'Invoice update', kind: 'API', ac: 'AC-A2',
    title: 'Patching only the Sales POC leaves the due date, terms, total and notes alone',
    steps: 'PATCH /api/invoices/{id} {salesPocUserId} with no dueDate or paymentTerm in the body',
    expected: 'dueDate, paymentTerm, total and notes unchanged; only the POC moves',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:138',
  }, async () => {
    const sales = await rt.createStaff(token, 'SALES_POC', 'f');
    const inv = await mk();
    const res = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token,
      body: { salesPocUserId: sales.id } });
    const ok = res.status === 200 && res.json.dueDate === inv.dueDate
      && res.json.paymentTerm === inv.paymentTerm && eq(res.json.total, inv.total)
      && res.json.salesPoc?.id === sales.id;
    return { ok, severity: 'high',
      actual: `${res.status} dueDate ${inv.dueDate} -> ${res.json?.dueDate}, term ${inv.paymentTerm} -> ${res.json?.paymentTerm}, total ${inv.total} -> ${res.json?.total}, poc=${res.json?.salesPoc?.id}` };
  });

  await r.check({
    id: 'F-95', feature: 'Dispute replace_items', kind: 'API',
    title: 'Approving a replace_items dispute recomputes the total and leaves the due date where it was',
    steps: 'customer disputes a 2-line invoice with replace_items down to 1 unit; admin approves',
    expected: 'total falls to the new line total, one item remains, dueDate unchanged',
    codeRef: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java:259',
  }, async () => {
    const inv = await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 2, unitPrice: '100.00' }] });
    const d = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'INVOICE', targetId: inv.id, reason: 'area-f wrong quantity',
        proposedChangeJson: JSON.stringify({ action: 'replace_items',
          items: [{ productId: prod.id, quantity: 1, unitPrice: '100.00' }], notes: 'corrected' }) } });
    const a = await rt.api('POST', `/api/disputes/${d.json.id}/approve`, { token, body: {} });
    const after = (await rt.api('GET', `/api/invoices/${inv.id}`, { token })).json;
    return { ok: a.status === 200 && eq(after.total, 100) && after.items.length === 1
        && after.dueDate === inv.dueDate,
      severity: 'high',
      actual: `approve=${a.status} total ${inv.total} -> ${after.total}, items=${after.items.length}, dueDate ${inv.dueDate} -> ${after.dueDate}` };
  });

  await r.check({
    id: 'F-96', feature: 'Dispute cancel with refund', kind: 'API',
    title: 'Cancelling a part-paid invoice through a dispute refunds the money to customer credit',
    steps: 'invoice 200, pay 80 against it, customer disputes with {"action":"cancel"}, admin approves',
    expected: 'invoice CANCELLED with paidAmount 0; the customer credit balance rises by exactly 80',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:388',
  }, async () => {
    const inv = await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 2, unitPrice: '100.00' }] });
    await recordPayment(token, { customerId: cust.id, amount: '80.00', method: 'CASH',
      collectionPocUserId: adminId, invoiceIds: [inv.id] });
    const before = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.creditBalance;
    const d = await rt.api('POST', '/api/disputes', { token: custToken,
      body: { targetType: 'INVOICE', targetId: inv.id, reason: 'area-f refund path',
        proposedChangeJson: '{"action":"cancel"}' } });
    const a = await rt.api('POST', `/api/disputes/${d.json.id}/approve`, { token, body: {} });
    const after = (await rt.api('GET', `/api/invoices/${inv.id}`, { token })).json;
    const credit = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.creditBalance;
    return { ok: a.status === 200 && after.status === 'CANCELLED' && eq(after.paidAmount, 0)
        && eq(credit, Number(before) + 80),
      severity: 'high',
      actual: `approve=${a.status} invoice=${after.status} paid=${after.paidAmount} credit ${before} -> ${credit}` };
  });

  r.save();
})();
