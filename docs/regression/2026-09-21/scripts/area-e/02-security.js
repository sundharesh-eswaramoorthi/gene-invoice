// Area E — privilege escalation, cross-scope access, auth failure modes, CASHIER retention,
// and the idempotency of the DOCUMENT_* seeding as it stands in the database.
const e = require('./lib-e.js');
const rt = e.rt;

(async () => {
  const w = await e.world();
  const tokens = await e.tokens(w);
  const admin = await rt.adminToken();
  const out = { generatedAt: new Date().toISOString(), escalation: {}, scope: {}, auth: {}, cashier: {}, seeding: {} };

  // ---- 1. escalation: a role without DOCUMENT_MANAGE calling upload directly ------------
  out.escalation.viewerUploadCustomer = await e.upload(tokens.VIEWER, { entityType: 'CUSTOMER', entityId: w.custA.id, filename: 'e-esc-viewer.pdf' }).then((r) => ({ status: r.status, message: r.json?.message, id: r.json?.id ?? null }));
  out.escalation.viewerUploadInvoice = await e.upload(tokens.VIEWER, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-esc-viewer2.pdf' }).then((r) => ({ status: r.status, message: r.json?.message, id: r.json?.id ?? null }));
  out.escalation.viewerDeleteAdminDoc = await rt.api('DELETE', `/api/documents/${w.docs.custA_internal.id}`, { token: tokens.VIEWER }).then((r) => ({ status: r.status, message: r.json?.message }));
  // A role with neither DOCUMENT_VIEW nor DOCUMENT_MANAGE at all.
  out.escalation.noDocPrivsUpload = await e.upload(tokens.EXPORT_ONLY, { entityType: 'CUSTOMER', entityId: w.custA.id, filename: 'e-esc-eo.pdf' }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.escalation.noDocPrivsList = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${w.custA.id}`, { token: tokens.EXPORT_ONLY }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.escalation.noDocPrivsDownload = await e.download(tokens.EXPORT_ONLY, w.docs.custA_internal.id).then((r) => ({ status: r.status }));

  // ---- 2. a customer login on staff-only document endpoints -----------------------------
  const cust = tokens.CUSTOMER;
  // Upload one of its own so the PATCH/DELETE probe targets a document it actually owns.
  const ownUpload = await e.upload(cust, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-cust-own.pdf' });
  out.escalation.customerUploadOwn = { status: ownUpload.status, id: ownUpload.json?.id ?? null, visibility: ownUpload.json?.visibility ?? null };
  const ownId = ownUpload.json?.id;
  out.escalation.customerPatchOwn = ownId ? await rt.api('PATCH', `/api/documents/${ownId}`, { token: cust, body: { visibility: 'INTERNAL', description: 'x' } }).then((r) => ({ status: r.status, message: r.json?.message })) : null;
  out.escalation.customerDeleteOwn = ownId ? await rt.api('DELETE', `/api/documents/${ownId}`, { token: cust }).then((r) => ({ status: r.status, message: r.json?.message })) : null;
  out.escalation.customerPatchAdminDoc = await rt.api('PATCH', `/api/documents/${w.docs.custA_shared.id}`, { token: cust, body: { visibility: 'INTERNAL' } }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.escalation.customerDownloadOwnInternal = await e.download(cust, w.docs.custA_internal.id).then((r) => ({ status: r.status }));
  out.escalation.customerDownloadOwnShared = await e.download(cust, w.docs.custA_shared.id).then((r) => ({ status: r.status }));

  // ---- 3. cross-customer: a customer login reaching another customer's records ----------
  out.scope.customerListForeignCustomerDocs = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${w.custB.id}`, { token: cust }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.scope.customerListForeignInvoiceDocs = await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invB.id}`, { token: cust }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.scope.customerDownloadForeignDoc = await e.download(cust, w.docs.custB_internal.id).then((r) => ({ status: r.status }));
  out.scope.customerDownloadForeignInvoiceDoc = await e.download(cust, w.docs.invB_internal.id).then((r) => ({ status: r.status }));
  out.scope.customerUploadForeignInvoice = await e.upload(cust, { entityType: 'INVOICE', entityId: w.invB.id, filename: 'e-esc-cross.pdf' }).then((r) => ({ status: r.status, id: r.json?.id ?? null }));
  out.scope.customerGetForeignInvoice = await rt.api('GET', `/api/invoices/${w.invB.id}`, { token: cust }).then((r) => ({ status: r.status }));

  // ---- 4. a POC reaching a record outside their book, by direct id ----------------------
  const sales = tokens.SALES_POC;
  out.scope.pocGetForeignInvoice = await rt.api('GET', `/api/invoices/${w.invB.id}`, { token: sales }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.scope.pocListForeignInvoiceDocs = await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invB.id}`, { token: sales }).then((r) => ({ status: r.status, message: r.json?.message }));
  out.scope.pocCountForeignInvoiceDocs = await rt.api('GET', `/api/documents/count?entityType=INVOICE&entityId=${w.invB.id}`, { token: sales }).then((r) => ({ status: r.status }));
  out.scope.pocDownloadForeignInvoiceDoc = await e.download(sales, w.docs.invB_internal.id).then((r) => ({ status: r.status }));
  out.scope.pocUploadForeignInvoice = await e.upload(sales, { entityType: 'INVOICE', entityId: w.invB.id, filename: 'e-esc-poc.pdf' }).then((r) => ({ status: r.status, id: r.json?.id ?? null }));
  out.scope.pocDeleteForeignInvoiceDoc = await rt.api('DELETE', `/api/documents/${w.docs.invB_internal.id}`, { token: sales }).then((r) => ({ status: r.status }));
  out.scope.pocListForeignCustomerDocs = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${w.custB.id}`, { token: sales }).then((r) => ({ status: r.status }));
  out.scope.pocDownloadPaymentDocOutsideBook = await e.download(sales, w.docs.payA_internal.id).then((r) => ({ status: r.status }));
  out.scope.pocOverdueFilterScoped = await rt.api(`GET`, `/api/invoices?filter=overdue:eq:true&size=50`, { token: sales })
    .then((r) => ({ status: r.status, totalElements: r.json?.totalElements, ids: (r.json?.content || []).map((i) => i.id), lockedFilters: r.json?.lockedFilters }));
  out.scope.adminOverdueFilterAll = await rt.api(`GET`, `/api/invoices?filter=overdue:eq:true&size=50`, { token: admin })
    .then((r) => ({ status: r.status, totalElements: r.json?.totalElements }));
  out.scope.pocExportForeignInvoice = await rt.api('POST', '/api/invoices/export', { token: sales, body: { action: 'EXPORT', ids: [w.invB.id] } })
    .then((r) => ({ status: r.status, body: (r.text || '').trim().split('\n').length - 1 }));

  // ---- 5. auth failure modes ------------------------------------------------------------
  const expired = rt.mintToken(w.users.ADMIN.username, -60);
  const live = rt.mintToken(w.users.ADMIN.username, 3600);
  const paths = ['/api/documents?entityType=CUSTOMER&entityId=' + w.custA.id, '/api/invoices', '/api/dashboard/outstanding-by-age', `/api/documents/${w.docs.custA_internal.id}/download`];
  out.auth.mintedLive = {};
  out.auth.expired = {};
  out.auth.malformed = {};
  out.auth.wrongSignature = {};
  out.auth.unknownUser = {};
  out.auth.noToken = {};
  const badSig = live.slice(0, live.lastIndexOf('.') + 1) + 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
  const unknown = rt.mintToken('e-no-such-user-' + Date.now(), 3600);
  for (const p of paths) {
    out.auth.mintedLive[p] = (await rt.api('GET', p, { token: live })).status;
    out.auth.expired[p] = (await rt.api('GET', p, { token: expired })).status;
    out.auth.malformed[p] = (await rt.api('GET', p, { token: 'not-a-jwt' })).status;
    out.auth.wrongSignature[p] = (await rt.api('GET', p, { token: badSig })).status;
    out.auth.unknownUser[p] = (await rt.api('GET', p, { token: unknown })).status;
    out.auth.noToken[p] = (await rt.api('GET', p)).status;
  }
  // And on a write path, where a 500 would be easiest to provoke.
  out.auth.expiredUpload = (await e.upload(expired, { entityType: 'CUSTOMER', entityId: w.custA.id, filename: 'e-expired.pdf' })).status;
  out.auth.malformedUpload = (await e.upload('...', { entityType: 'CUSTOMER', entityId: w.custA.id, filename: 'e-malformed.pdf' })).status;
  out.auth.malformedExport = (await rt.api('POST', '/api/invoices/export', { token: 'a.b.c', body: { action: 'EXPORT', ids: [w.invA.id] } })).status;

  // ---- 6. missing / unknown document ids are 404, not 500 -------------------------------
  out.escalation.unknownDocDownload = (await e.download(admin, 99999999)).status;
  const throwaway = await e.upload(admin, { entityType: 'CUSTOMER', entityId: w.custA.id, filename: 'e-softdelete.pdf' });
  await rt.api('DELETE', `/api/documents/${throwaway.json.id}`, { token: admin });
  out.escalation.deletedDocDownload = (await e.download(admin, throwaway.json.id)).status;
  out.escalation.deletedDocPatch = (await rt.api('PATCH', `/api/documents/${throwaway.json.id}`, { token: admin, body: { description: 'x' } })).status;

  // ---- 7. CASHIER retention: everything it could do before this feature ------------------
  const cashier = tokens.CASHIER;
  const line = { productId: w.productId, quantity: 1, unitPrice: '100.00' };
  const newCust = await rt.api('POST', '/api/customers', { token: cashier, body: { name: rt.uniq('e-cashier'), phone: '555-0101', email: `${rt.uniq('e')}@rt.local`, address: '2 Test Way', username: rt.uniq('e'), password: rt.PASSWORD } });
  const newInv = await rt.api('POST', '/api/invoices', { token: cashier, body: { customerId: w.custA.id, salesPocUserId: w.users.SALES_POC.id, items: [line], notes: 'cashier retention' } });
  out.cashier = {
    createCustomer: newCust.status,
    updateCustomer: newCust.status < 300 ? (await rt.api('PUT', `/api/customers/${newCust.json.id}`, { token: cashier, body: { name: newCust.json.name + ' x', phone: '555-0102', email: newCust.json.email, address: '3 Test Way' } })).status : null,
    listProducts: (await rt.api('GET', '/api/products', { token: cashier })).status,
    createInvoice: newInv.status,
    cancelInvoice: newInv.status < 300 ? (await rt.api('POST', `/api/invoices/${newInv.json.id}/cancel`, { token: cashier })).status : null,
    createPayment: (await rt.api('POST', '/api/payments', { token: cashier, body: { customerId: w.custA.id, amount: '5.00', method: 'CASH', collectionPocUserId: w.users.COLLECTION_POC.id, invoiceIds: [] } })).status,
    listPayments: (await rt.api('GET', '/api/payments', { token: cashier })).status,
    notifications: (await rt.api('GET', '/api/notifications', { token: cashier })).status,
    dashboardBilled: (await rt.api('GET', '/api/dashboard/billed-by-month', { token: cashier })).status,
    dashboardAgeing: (await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: cashier })).status,
    promisesList: (await rt.api('GET', '/api/promises', { token: cashier })).status,
    pocAssign: (await rt.api('POST', `/api/customers/${w.custA.id}/pocs`, { token: cashier, body: { pocType: 'SUCCESS', userId: w.users.CUSTOMER_SUCCESS_POC.id, primary: false } })).status,
    exportInvoices: (await rt.api('POST', '/api/invoices/export', { token: cashier, body: { action: 'EXPORT', ids: [w.invA.id] } })).status,
    exportCustomers: (await rt.api('POST', '/api/customers/export', { token: cashier, body: { action: 'EXPORT', ids: [w.custA.id] } })).status,
    scopeOverrideSeesForeignInvoice: (await rt.api('GET', `/api/invoices/${w.invB.id}`, { token: cashier })).status,
  };

  // ---- 8. the seeded state of DOCUMENT_VIEW / DOCUMENT_MANAGE ----------------------------
  const privs = await rt.api('GET', '/api/privileges?size=50', { token: admin });
  const privNames = (privs.json?.content || privs.json || []).map((p) => p.name || p);
  out.seeding.privilegeRows = privNames;
  out.seeding.documentViewRows = privNames.filter((n) => n === 'DOCUMENT_VIEW').length;
  out.seeding.documentManageRows = privNames.filter((n) => n === 'DOCUMENT_MANAGE').length;
  const roles = await rt.api('GET', '/api/roles?size=50', { token: admin });
  out.seeding.roles = {};
  for (const r of roles.json.content || []) {
    out.seeding.roles[r.name] = { privileges: r.privileges, duplicates: r.privileges.length !== new Set(r.privileges).size };
  }

  e.writeOut('security.json', out);
  console.log('wrote out/security.json');
})().catch((err) => { console.error(err); process.exit(1); });
