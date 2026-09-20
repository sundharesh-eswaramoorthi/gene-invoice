// Area D seed: one customer, one invoice, one payment, documents on each, plus the two logins
// the privilege cases need (a DOCUMENT_VIEW-only staff role, and the customer's own login).
const h = require('./helpers');
const rt = h.rt;

(async () => {
  const admin = await rt.adminToken();

  // --- a product to invoice ---------------------------------------------------------------
  const product = await rt.api('POST', '/api/products', {
    token: admin,
    body: { name: rt.uniq('d-prod'), description: 'area d', price: 100.0, active: true },
  });
  if (product.status >= 300) throw new Error(`product -> ${product.status} ${product.text}`);

  // --- POCs (invoices need a sales POC, payments a collection POC) --------------------------
  const salesPoc = await rt.createStaff(admin, 'SALES_POC', 'd-sales');
  const collectionPoc = await rt.createStaff(admin, 'COLLECTION_POC', 'd-coll');

  // --- the customer, with its self-service login -------------------------------------------
  const customer = await rt.createCustomer(admin, 'd-cust');

  const invoice = await rt.api('POST', '/api/invoices', {
    token: admin,
    body: {
      customerId: customer.id,
      salesPocUserId: salesPoc.id,
      notes: 'area d invoice',
      items: [{ productId: product.json.id, quantity: 2, unitPrice: 100.0 }],
    },
  });
  if (invoice.status >= 300) throw new Error(`invoice -> ${invoice.status} ${invoice.text}`);

  const payment = await rt.api('POST', '/api/payments', {
    token: admin,
    body: {
      customerId: customer.id,
      amount: 50.0,
      method: 'CASH',
      notes: 'area d payment',
      invoiceIds: [invoice.json.id],
      collectionPocUserId: collectionPoc.id,
    },
  });
  if (payment.status >= 300) throw new Error(`payment -> ${payment.status} ${payment.text}`);

  // A second, empty invoice for the empty-state case.
  const emptyInvoice = await rt.api('POST', '/api/invoices', {
    token: admin,
    body: {
      customerId: customer.id,
      salesPocUserId: salesPoc.id,
      notes: 'area d empty',
      items: [{ productId: product.json.id, quantity: 1, unitPrice: 25.0 }],
    },
  });

  // --- documents ----------------------------------------------------------------------------
  const docs = {};
  docs.invoiceInternal = await h.apiUpload(admin, {
    entityType: 'INVOICE', entityId: invoice.json.id,
    name: 'd-purchase-order.pdf', bytes: h.pdfBytes('po'), type: 'application/pdf',
    description: 'Customer PO', visibility: 'INTERNAL',
  });
  docs.invoiceShared = await h.apiUpload(admin, {
    entityType: 'INVOICE', entityId: invoice.json.id,
    name: 'd-signed-delivery-note.png', bytes: h.pngBytes(), type: 'image/png',
    description: 'Signed delivery note', visibility: 'SHARED',
  });
  docs.customerDoc = await h.apiUpload(admin, {
    entityType: 'CUSTOMER', entityId: customer.id,
    name: 'd-trade-licence.pdf', bytes: h.pdfBytes('licence'), type: 'application/pdf',
    description: 'Trade licence', visibility: 'SHARED',
  });
  docs.paymentDoc = await h.apiUpload(admin, {
    entityType: 'PAYMENT', entityId: payment.json.id,
    name: 'd-cheque-image.png', bytes: h.pngBytes(), type: 'image/png',
    description: 'Cheque image', visibility: 'INTERNAL',
  });
  for (const [k, v] of Object.entries(docs)) {
    if (v.status >= 300) throw new Error(`upload ${k} -> ${v.status} ${v.text}`);
  }

  // --- a staff role with DOCUMENT_VIEW but no DOCUMENT_MANAGE -------------------------------
  const want = ['CUSTOMER_VIEW', 'INVOICE_VIEW', 'PAYMENT_VIEW', 'PRODUCT_VIEW', 'DOCUMENT_VIEW',
    'NOTIFICATION_VIEW', 'SCOPE_OVERRIDE', 'INVOICE_MANAGE', 'PAYMENT_MANAGE', 'CUSTOMER_MANAGE'];
  const roleName = rt.uniq('D-NODOCMANAGE').toUpperCase();
  const role = await rt.api('POST', '/api/roles', {
    token: admin,
    body: {
      name: roleName,
      description: 'Area D: can see documents, cannot manage them',
      privileges: want,
    },
  });
  if (role.status >= 300) throw new Error(`role -> ${role.status} ${role.text}`);
  const viewerUser = rt.uniq('d-viewonly');
  const viewer = await rt.api('POST', '/api/users', {
    token: admin,
    body: {
      username: viewerUser, email: `${viewerUser}@rt.local`, fullName: viewerUser.toUpperCase(),
      password: rt.PASSWORD, roleId: role.json.id, active: true,
    },
  });
  if (viewer.status >= 300) throw new Error(`viewer user -> ${viewer.status} ${viewer.text}`);

  const seed = {
    admin,
    customer: { id: customer.id, name: customer.name, username: customer.username, password: customer.password },
    invoice: { id: invoice.json.id, number: invoice.json.invoiceNumber },
    emptyInvoice: { id: emptyInvoice.json.id, number: emptyInvoice.json.invoiceNumber },
    payment: { id: payment.json.id },
    product: product.json.id,
    salesPoc: { id: salesPoc.id, username: salesPoc.username },
    collectionPoc: { id: collectionPoc.id, username: collectionPoc.username },
    noManage: { username: viewerUser, password: rt.PASSWORD, roleId: role.json.id, roleName },
    docs: Object.fromEntries(Object.entries(docs).map(([k, v]) => [k, v.json])),
  };
  h.writeSeed(seed);
  console.log(JSON.stringify({
    customer: seed.customer.id, invoice: seed.invoice.id, emptyInvoice: seed.emptyInvoice.id,
    payment: seed.payment.id, docs: Object.fromEntries(Object.entries(seed.docs).map(([k, v]) => [k, v.id])),
    noManage: seed.noManage.username,
  }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });
