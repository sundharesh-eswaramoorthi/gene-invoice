// Area F / table framework, CSV exports and email — the shared machinery the new work touched.
const { rt, recorder, eq, admin, createProduct, createInvoice, recordPayment, parseCsv, roleWith,
  isoDay } = require('./_h.js');

(async () => {
  const r = recorder('06-tables-exports-email');
  const { token, id: adminId } = await admin();
  const cust = await rt.createCustomer(token, 'f');
  const custToken = await rt.login(cust.username, cust.password);
  const prod = await createProduct(token, 'f-exp', '40.00');
  const inv = await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
    items: [{ productId: prod.id, quantity: 2, unitPrice: '40.00' }] });
  const pay = await recordPayment(token, { customerId: cust.id, amount: '30.00', method: 'CASH',
    collectionPocUserId: adminId });
  const promise = (await rt.api('POST', '/api/promises', { token,
    body: { customerId: cust.id, amount: '50.00', promisedDate: isoDay(5),
      collectionPocUserId: adminId, invoiceIds: [inv.id] } })).json;
  const dispute = (await rt.api('POST', '/api/disputes', { token: custToken,
    body: { targetType: 'INVOICE', targetId: inv.id, reason: 'area-f export fixture' } })).json;

  // ---- F-52..F-54 table framework ------------------------------------------------
  await r.check({
    id: 'F-52', feature: 'Table framework', kind: 'API',
    title: 'The query framework still rejects a bad page size, an unknown sort column and a malformed filter',
    steps: 'GET /api/invoices?size=7; ?sort=nosuchcolumn,asc; ?sort=total,sideways; ?filter=total:eq (no value)',
    expected: '400 on each, with a message naming what was wrong',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableQuery.java:26',
  }, async () => {
    const bad = await Promise.all([
      rt.api('GET', '/api/invoices?size=7', { token }),
      rt.api('GET', '/api/invoices?sort=nosuchcolumn,asc', { token }),
      rt.api('GET', '/api/invoices?sort=total,sideways', { token }),
      rt.api('GET', '/api/invoices?filter=total:eq', { token }),
    ]);
    return { ok: bad.every((x) => x.status === 400),
      actual: bad.map((x) => `${x.status}:${(x.json?.message || x.text).slice(0, 60)}`).join(' | ') };
  });

  await r.check({
    id: 'F-53', feature: 'Table framework', kind: 'API',
    title: 'A column that is declared not-sortable is still refused as a sort key',
    steps: 'GET /api/invoices?sort=notes,asc and ?sort=customerId,asc',
    expected: '400 for both (notes and the customer reference are notSortable in the schema)',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:72',
  }, async () => {
    const a = await rt.api('GET', '/api/invoices?sort=notes,asc', { token });
    const b = await rt.api('GET', '/api/invoices?sort=customerId,asc', { token });
    return { ok: a.status === 400 && b.status === 400, severity: 'low',
      actual: `notes -> ${a.status}, customerId -> ${b.status}` };
  });

  await r.check({
    id: 'F-54', feature: 'Table framework', kind: 'API',
    title: 'The table-schema endpoint still describes every table the frontend builds filters from',
    steps: 'GET /api/table-schemas/all and /api/table-schemas/invoices',
    expected: 'invoices, payments, customers, promises, products, users, roles, disputes, notifications and inbox are all described; the invoice schema still carries the pre-existing columns',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:392',
  }, async () => {
    const all = await rt.api('GET', '/api/table-schemas/all', { token });
    const one = await rt.api('GET', '/api/table-schemas/invoices', { token });
    const entities = Array.isArray(all.json) ? all.json.map((s) => s.entity) : Object.keys(all.json);
    const want = ['invoices', 'payments', 'customers', 'promises', 'products', 'users', 'roles',
      'disputes', 'notifications', 'inbox'];
    const cols = (one.json.columns || []).map((c) => c.name);
    const pre = ['invoiceNumber', 'customerId', 'invoiceDate', 'total', 'paidAmount', 'balance', 'status'];
    return { ok: all.status === 200 && want.every((e) => entities.includes(e))
        && pre.every((c) => cols.includes(c)),
      actual: `entities=${entities.join(',')}; invoice columns=${cols.join(',')}` };
  });

  // ---- F-55..F-61 CSV exports -----------------------------------------------------
  const exports = [
    ['F-55', 'customers', '/api/customers/export', [`name:contains:${cust.name}`], 'Name', cust.name],
    ['F-56', 'payments', '/api/payments/export', [`customerName:contains:${cust.name}`], 'Amount', '30.00'],
    ['F-57', 'products', '/api/products/export', [`name:contains:${prod.name}`], 'Name', prod.name],
    ['F-58', 'users', '/api/users/export', [`username:contains:${cust.username}`], 'Username', cust.username],
    ['F-59', 'roles', '/api/roles/export', ['name:eq:CASHIER'], 'Name', 'CASHIER'],
    ['F-60', 'disputes', '/api/disputes/export', [`id:eq:${dispute.id}`], null, String(dispute.id)],
    ['F-61', 'promises', '/api/promises/export', [`id:eq:${promise.id}`], null, '50.00'],
  ];
  for (const [id, name, path, filters, headerWanted, cellWanted] of exports) {
    await r.check({
      id, feature: `CSV export — ${name}`, kind: 'API',
      title: `The ${name} CSV export still downloads the filtered rows`,
      steps: `POST ${path} {selectAllMatchingFilter:true, filters:${JSON.stringify(filters)}}`,
      expected: '200 text/csv with Content-Disposition attachment, a header row, and my row present',
      codeRef: `backend/src/main/java/com/geneinvoice/${name === 'promises' ? 'promise/PaymentPromiseController' : name.slice(0, -1) + '/' + name[0].toUpperCase() + name.slice(1, -1) + 'Controller'}.java`,
    }, async () => {
      const res = await rt.api('POST', path, { token,
        body: { action: 'EXPORT', selectAllMatchingFilter: true, filters } });
      const rows = parseCsv(res.text || '');
      const header = rows[0] || [];
      const body = rows.slice(1);
      const ok = res.status === 200
        && /text\/csv/.test(res.headers['content-type'] || '')
        && /attachment/.test(res.headers['content-disposition'] || '')
        && body.length >= 1
        && body.some((row) => row.some((c) => c === cellWanted || c.includes(cellWanted)))
        && (!headerWanted || header.includes(headerWanted));
      return { ok, actual: `${res.status} type=${res.headers['content-type']} disposition=${res.headers['content-disposition']} header=[${header.join('|')}] rows=${body.length}` };
    });
  }

  // ---- F-62/F-63 export privileges (D-02) -------------------------------------------
  const exportOnly = await roleWith(token, 'f-exponly', ['EXPORT_DATA', 'NOTIFICATION_VIEW']);
  const viewOnly = await roleWith(token, 'f-viewonly', ['CUSTOMER_VIEW', 'PAYMENT_VIEW',
    'PRODUCT_VIEW', 'USER_VIEW', 'ROLE_VIEW', 'DISPUTE_VIEW', 'PROMISE_VIEW', 'INVOICE_VIEW']);
  const paths = ['/api/customers/export', '/api/payments/export', '/api/products/export',
    '/api/users/export', '/api/roles/export', '/api/disputes/export', '/api/promises/export',
    '/api/invoices/export'];

  await r.check({
    id: 'F-62', feature: 'Export privileges', kind: 'API',
    title: 'EXPORT_DATA alone does not open any CSV export (regression of D-02)',
    steps: 'a role with EXPORT_DATA and no entity view privilege POSTs every export endpoint',
    expected: '403 on all eight',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerController.java:165',
  }, async () => {
    const t = await rt.login(exportOnly.username, exportOnly.password);
    const res = await Promise.all(paths.map((p) => rt.api('POST', p, { token: t,
      body: { action: 'EXPORT', selectAllMatchingFilter: true } })));
    const bad = res.map((x, i) => `${paths[i]}=${x.status}`).filter((_, i) => res[i].status !== 403);
    return { ok: bad.length === 0, severity: 'high',
      actual: bad.length ? `not 403: ${bad.join(', ')}` : 'all eight returned 403' };
  });

  await r.check({
    id: 'F-63', feature: 'Export privileges', kind: 'API',
    title: 'The entity view privilege alone does not open a CSV export either',
    steps: 'a role with every *_VIEW privilege but no EXPORT_DATA POSTs every export endpoint',
    expected: '403 on all eight',
    codeRef: 'backend/src/main/java/com/geneinvoice/privilege/Privileges.java:74',
  }, async () => {
    const t = await rt.login(viewOnly.username, viewOnly.password);
    const res = await Promise.all(paths.map((p) => rt.api('POST', p, { token: t,
      body: { action: 'EXPORT', selectAllMatchingFilter: true } })));
    const bad = res.map((x, i) => `${paths[i]}=${x.status}`).filter((_, i) => res[i].status !== 403);
    return { ok: bad.length === 0, severity: 'high',
      actual: bad.length ? `not 403: ${bad.join(', ')}` : 'all eight returned 403' };
  });

  await r.check({
    id: 'F-64', feature: 'Export scope', kind: 'API',
    title: 'A self-service customer exporting invoices gets only their own rows',
    steps: 'POST /api/invoices/export as the customer login with selectAllMatchingFilter',
    expected: 'either a 403 (no EXPORT_DATA on the CUSTOMER role) or a CSV holding only that customer\'s invoices',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:134',
  }, async () => {
    const res = await rt.api('POST', '/api/invoices/export', { token: custToken,
      body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    if (res.status === 403) return { ok: true, actual: '403 — the seeded CUSTOMER role has no EXPORT_DATA' };
    const rows = parseCsv(res.text || '').slice(1);
    const foreign = rows.filter((row) => row[1] && row[1] !== cust.name);
    return { ok: res.status === 200 && foreign.length === 0, severity: 'high',
      actual: `${res.status} rows=${rows.length} foreignRows=${foreign.length}` };
  });

  // ---- F-65..F-68 email on 8083 ------------------------------------------------------
  await r.check({
    id: 'F-65', feature: 'Email', kind: 'API',
    title: 'GET /api/emails for a record answers on the fresh 8083 database',
    steps: 'GET /api/emails?entityType=INVOICE&entityId=<mine>&page=0&size=20',
    expected: '200 with a paged envelope (empty content is fine) — not a 500',
    codeRef: 'backend/src/main/java/com/geneinvoice/email/EmailController.java:53',
  }, async () => {
    const res = await rt.api('GET', `/api/emails?entityType=INVOICE&entityId=${inv.id}&page=0&size=20`, { token });
    return { ok: res.status === 200 && Array.isArray(res.json?.content), severity: 'high',
      actual: `${res.status} ${JSON.stringify(res.json).slice(0, 220)}` };
  });

  await r.check({
    id: 'F-66', feature: 'Email inbox', kind: 'API',
    title: 'GET /api/inbox and /api/inbox/unread-count answer on 8083',
    steps: 'GET /api/inbox?page=0&size=20 and GET /api/inbox/unread-count',
    expected: '200 on both — not a 500',
    codeRef: 'backend/src/main/java/com/geneinvoice/email/InboxController.java:41',
  }, async () => {
    const list = await rt.api('GET', '/api/inbox?page=0&size=20', { token });
    const count = await rt.api('GET', '/api/inbox/unread-count', { token });
    return { ok: list.status === 200 && Array.isArray(list.json?.content) && count.status === 200,
      severity: 'high',
      actual: `inbox=${list.status} rows=${list.json?.content?.length} unread-count=${count.status} ${JSON.stringify(count.json)}` };
  });

  await r.check({
    id: 'F-67', feature: 'Email compose', kind: 'API',
    title: 'The compose context and preview still answer for an invoice',
    steps: 'GET /api/emails/context?entityType=INVOICE&entityId=..; POST /api/emails/preview',
    expected: '200 on both; the context names the record and the delivery state',
    codeRef: 'backend/src/main/java/com/geneinvoice/email/EmailController.java:20',
  }, async () => {
    const ctx = await rt.api('GET', `/api/emails/context?entityType=INVOICE&entityId=${inv.id}`, { token });
    const prev = await rt.api('POST', '/api/emails/preview', { token,
      body: { entityType: 'INVOICE', entityId: inv.id, subject: 'area-f', body: 'hello',
        to: [{ type: 'CUSTOMER' }] } });
    return { ok: ctx.status === 200 && ctx.json.entityId === inv.id && prev.status === 200,
      severity: 'high',
      actual: `context=${ctx.status} label=${ctx.json?.entityLabel} delivery=${JSON.stringify(ctx.json?.delivery)}; preview=${prev.status} ${JSON.stringify(prev.json).slice(0, 160)}` };
  });

  await r.check({
    id: 'F-68', feature: 'Email delivery', kind: 'API',
    title: 'GET /api/emails/delivery reports the transport state instead of failing',
    steps: 'GET /api/emails/delivery',
    expected: '200 with configured:false (MAIL_TRANSPORT defaults to none on this deployment)',
    codeRef: 'backend/src/main/java/com/geneinvoice/email/EmailController.java:74',
  }, async () => {
    const res = await rt.api('GET', '/api/emails/delivery', { token });
    return { ok: res.status === 200 && res.json && 'configured' in res.json, severity: 'medium',
      actual: `${res.status} ${JSON.stringify(res.json).slice(0, 200)}` };
  });

  await r.check({
    id: 'F-91', feature: 'Email send', kind: 'API',
    title: 'Sending an email about an invoice writes to the emails table on 8083 and reads back',
    steps: 'POST /api/emails {entityType:INVOICE, entityId, subject, body, to:[{type:CUSTOMER}]}; GET /api/emails for the same record',
    expected: '200 on the send; the email is returned by the record\'s email list — no 500 from a stale emails table (the 8082 condition of D-01)',
    codeRef: 'backend/src/main/java/com/geneinvoice/email/EmailController.java:41',
  }, async () => {
    const send = await rt.api('POST', '/api/emails', { token,
      body: { entityType: 'INVOICE', entityId: inv.id, subject: 'area-f probe', body: 'hello',
        to: [{ type: 'CUSTOMER' }] } });
    const list = await rt.api('GET', `/api/emails?entityType=INVOICE&entityId=${inv.id}`, { token });
    const found = (list.json?.content || []).some((e) => e.id === send.json?.id);
    return { ok: send.status === 200 && list.status === 200 && found, severity: 'high',
      actual: `send=${send.status} id=${send.json?.id} status=${send.json?.status}; list=${list.status} rows=${list.json?.content?.length} found=${found}` };
  });

  // ---- F-92/F-93 scope did not leak --------------------------------------------------
  await r.check({
    id: 'F-92', feature: 'Scope', kind: 'API',
    title: 'A self-service customer still sees only their own invoices and payments',
    steps: 'as the customer login: GET /api/invoices and /api/payments; GET another customer\'s invoice by id',
    expected: 'every row belongs to that customer; the foreign invoice is a 404',
    codeRef: 'backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java',
  }, async () => {
    const other = await rt.createCustomer(token, 'f');
    const foreign = await createInvoice(token, { customerId: other.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 1, unitPrice: '40.00' }] });
    const invs = await rt.api('GET', '/api/invoices?size=50', { token: custToken });
    const pays = await rt.api('GET', '/api/payments?size=50', { token: custToken });
    const direct = await rt.api('GET', `/api/invoices/${foreign.id}`, { token: custToken });
    const ok = invs.json.content.every((i) => i.customerId === cust.id)
      && pays.json.content.every((p) => p.customerId === cust.id)
      && (direct.status === 404 || direct.status === 403);
    return { ok, severity: 'high',
      actual: `invoices=${invs.json.content.length} all mine=${invs.json.content.every((i) => i.customerId === cust.id)}; payments=${pays.json.content.length} all mine=${pays.json.content.every((p) => p.customerId === cust.id)}; foreign invoice=${direct.status}` };
  });

  await r.check({
    id: 'F-93', feature: 'Scope', kind: 'API',
    title: 'A self-service customer never sees POC identity on their own records',
    steps: 'as the customer login: GET /api/invoices/{mine} and /api/payments/{mine}',
    expected: 'salesPoc / collectionPoc are null and the POC columns are not filterable for them',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:43',
  }, async () => {
    const one = await rt.api('GET', `/api/invoices/${inv.id}`, { token: custToken });
    const p = await rt.api('GET', `/api/payments/${pay.id}`, { token: custToken });
    const filtered = await rt.api('GET', `/api/invoices?filter=salesPocUserId:eq:${adminId}`, { token: custToken });
    return { ok: one.json.salesPoc === null && p.json.collectionPoc === null && filtered.status === 400,
      severity: 'high',
      actual: `invoice salesPoc=${JSON.stringify(one.json?.salesPoc)} payment collectionPoc=${JSON.stringify(p.json?.collectionPoc)} pocFilter=${filtered.status}` };
  });

  r.save();
})();
