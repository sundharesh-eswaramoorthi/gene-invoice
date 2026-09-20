// AC-C10, AC-C11, AC-C12 — who may reach a document. Every cross-scope case is a DIRECT
// request for a known-foreign document id, not a walk through the UI.
const rt = require('../lib.js');
const h = require('./helpers.js');

const TARGETS = 'backend/src/main/java/com/geneinvoice/document/DocumentTargets.java';
const SVC = 'backend/src/main/java/com/geneinvoice/document/DocumentService.java';

const list = (token, type, id) =>
  rt.api('GET', `/api/documents?entityType=${type}&entityId=${id}&size=50`, { token });

module.exports = async function authz(ctx, rec) {
  const { admin, A, B, salesPoc, viewer, docOnly, noDoc, noInvoice, invoiceViewOnly } = ctx;

  // A shared and an internal document on customer A's own record, and one on each of A's
  // invoice and payment; plus a document on customer B, for the cross-customer probe.
  const mk = async (type, id, visibility, name) => {
    const r = await h.upload(admin, {
      entityType: type, entityId: id, bytes: h.pdf(900), filename: name,
      contentType: 'application/pdf', visibility,
    });
    if (r.json?.id) ctx.uploaded.push(r.json);
    if (r.status !== 201) throw new Error(`authz fixture ${name} -> ${r.status} ${r.text}`);
    return r.json;
  };
  const aSharedCustomer = await mk('CUSTOMER', A.customer.id, 'SHARED', 'a-shared.pdf');
  const aInternalCustomer = await mk('CUSTOMER', A.customer.id, 'INTERNAL', 'a-internal.pdf');
  const aInvoiceDoc = await mk('INVOICE', A.invoice.id, 'SHARED', 'a-invoice.pdf');
  const aPaymentDoc = await mk('PAYMENT', A.payment.id, 'SHARED', 'a-payment.pdf');
  const bInvoiceDoc = await mk('INVOICE', B.invoice.id, 'SHARED', 'b-invoice.pdf');
  const bCustomerDoc = await mk('CUSTOMER', B.customer.id, 'SHARED', 'b-customer.pdf');

  // ---- authentication ------------------------------------------------------------------
  const anon = await h.download(undefined, aInvoiceDoc.id);
  const anonList = await list(undefined, 'INVOICE', A.invoice.id);
  const anonUp = await h.upload('not-a-token', { entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(300) });
  const anonOk = anon.status === 401 && anonList.status === 401 && anonUp.status === 401;
  rec({
    ac: 'AC-C10',
    title: 'Download, list and upload all require authentication',
    status: anonOk ? 'PASS' : 'FAIL',
    severity: anonOk ? '' : 'high',
    steps: 'GET /api/documents/{id}/download and GET /api/documents with no token; POST /api/documents with a garbage bearer token',
    expected: '401 on all three',
    actual: `download=${anon.status} list=${anonList.status} upload=${anonUp.status}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/config/SecurityConfig.java',
  });

  // ---- AC-C10: DOCUMENT_VIEW / DOCUMENT_MANAGE ------------------------------------------
  const nd = await h.download(noDoc.token, aInvoiceDoc.id);
  const ndList = await list(noDoc.token, 'INVOICE', A.invoice.id);
  const ndUp = await h.upload(noDoc.token, { entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(300), filename: 'x.pdf', contentType: 'application/pdf' });
  const ndOk = nd.status === 403 && ndList.status === 403 && ndUp.status === 403;
  rec({
    ac: 'AC-C10',
    title: 'A role with every record privilege but no DOCUMENT_VIEW/MANAGE is refused',
    status: ndOk ? 'PASS' : 'FAIL',
    severity: ndOk ? '' : 'high',
    steps: 'Custom role with CUSTOMER/INVOICE/PAYMENT view+manage and SCOPE_OVERRIDE, but neither document privilege; download, list and upload on invoice A',
    expected: '403 on all three',
    actual: `download=${nd.status} list=${ndList.status} upload=${ndUp.status}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentController.java:46',
  });

  const doDl = await h.download(docOnly.token, aInvoiceDoc.id);
  const doUp = await h.upload(docOnly.token, { entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(300), filename: 'x.pdf', contentType: 'application/pdf' });
  const doOk = doDl.status === 403 && doUp.status === 403;
  rec({
    ac: 'AC-C10',
    title: 'DOCUMENT_VIEW/MANAGE alone is not enough — the parent record\'s privilege is also required',
    status: doOk ? 'PASS' : 'FAIL',
    severity: doOk ? '' : 'high',
    steps: 'Custom role with only DOCUMENT_VIEW + DOCUMENT_MANAGE; direct download of, and upload to, invoice A',
    expected: '403 on both (no INVOICE_VIEW, no INVOICE_MANAGE)',
    actual: `download=${doDl.status} upload=${doUp.status}`,
    codeRef: `${TARGETS}:64`,
  });

  const niInvoice = await h.download(noInvoice.token, aInvoiceDoc.id);
  const niCustomer = await h.download(noInvoice.token, aSharedCustomer.id);
  const niOk = niInvoice.status === 403 && niCustomer.status === 200;
  rec({
    ac: 'AC-C10',
    title: 'The view privilege is checked per record type: no INVOICE_VIEW blocks an invoice document only',
    status: niOk ? 'PASS' : 'FAIL',
    severity: niOk ? '' : 'high',
    steps: 'Custom role with CUSTOMER_VIEW/MANAGE + both document privileges but no INVOICE_VIEW; download an invoice document and a customer document',
    expected: 'invoice document 403, customer document 200',
    actual: `invoiceDoc=${niInvoice.status} customerDoc=${niCustomer.status}`,
    codeRef: `${TARGETS}:38`,
  });

  const ivDl = await h.download(invoiceViewOnly.token, aInvoiceDoc.id);
  const ivUp = await h.upload(invoiceViewOnly.token, { entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(300), filename: 'x.pdf', contentType: 'application/pdf' });
  const ivOk = ivDl.status === 200 && ivUp.status === 403;
  rec({
    ac: 'AC-C10',
    title: 'INVOICE_VIEW without INVOICE_MANAGE can download but not upload',
    status: ivOk ? 'PASS' : 'FAIL',
    severity: ivOk ? '' : 'high',
    steps: 'Custom role with INVOICE_VIEW + DOCUMENT_VIEW/MANAGE but no INVOICE_MANAGE; download invoice A\'s document, then upload to invoice A',
    expected: 'download 200, upload 403',
    actual: `download=${ivDl.status} upload=${ivUp.status}`,
    codeRef: `${TARGETS}:76`,
  });

  const vDl = await h.download(viewer.token, aInvoiceDoc.id);
  const vUp = await h.upload(viewer.token, { entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(300), filename: 'x.pdf', contentType: 'application/pdf' });
  const vDel = await rt.api('DELETE', `/api/documents/${aInvoiceDoc.id}`, { token: viewer.token });
  const vOk = vDl.status === 200 && vUp.status === 403 && vDel.status === 403;
  rec({
    ac: 'AC-C10',
    title: 'The seeded VIEWER role can download but cannot upload or delete',
    status: vOk ? 'PASS' : 'FAIL',
    severity: vOk ? '' : 'high',
    steps: 'VIEWER login: download invoice A\'s document, upload to invoice A, delete that document',
    expected: 'download 200, upload 403, delete 403',
    actual: `download=${vDl.status} upload=${vUp.status} delete=${vDel.status}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentController.java:88',
  });

  // ---- AC-C11: POC book -----------------------------------------------------------------
  const pocOwn = await h.download(salesPoc.token, bInvoiceDoc.id);
  const pocForeign = await h.download(salesPoc.token, aInvoiceDoc.id);
  const pocForeignList = await list(salesPoc.token, 'INVOICE', A.invoice.id);
  const pocOk = pocOwn.status === 200 && pocForeign.status === 404 && pocForeignList.status === 404;
  rec({
    ac: 'AC-C11',
    title: 'A SALES_POC cannot reach a document on an invoice outside their book (direct id request)',
    status: pocOk ? 'PASS' : 'FAIL',
    severity: pocOk ? '' : 'high',
    steps: `SALES_POC seated on invoice ${B.invoice.id} only. GET /api/documents/${bInvoiceDoc.id}/download (their book) and GET /api/documents/${aInvoiceDoc.id}/download (another rep's invoice), plus a list on invoice ${A.invoice.id}`,
    expected: 'own book 200; foreign document 404 (not 200, not the bytes); foreign list 404',
    actual: `ownBook=${pocOwn.status} foreignDoc=${pocForeign.status} foreignList=${pocForeignList.status}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:288',
  });

  const pocCustomer = await h.download(salesPoc.token, aSharedCustomer.id);
  const pocPayment = await h.download(salesPoc.token, aPaymentDoc.id);
  const pocOk2 = pocCustomer.status === 404 && (pocPayment.status === 404 || pocPayment.status === 403);
  rec({
    ac: 'AC-C11',
    title: 'The book also narrows a POC\'s reach to customer and payment documents',
    status: pocOk2 ? 'PASS' : 'FAIL',
    severity: pocOk2 ? '' : 'high',
    steps: `SALES_POC downloads document ${aSharedCustomer.id} (customer A, not in their book) and ${aPaymentDoc.id} (a payment; a sales rep holds no collection seat, so their payments book is empty)`,
    expected: '404 on the customer document; 404 or 403 on the payment document',
    actual: `customerDoc=${pocCustomer.status} paymentDoc=${pocPayment.status}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java:86',
  });

  // ---- AC-C11: one customer login reaching at another customer's document ---------------
  const crossDl = await h.download(B.token, aSharedCustomer.id);
  const crossInv = await h.download(B.token, aInvoiceDoc.id);
  const crossList = await list(B.token, 'CUSTOMER', A.customer.id);
  const crossOk = [crossDl.status, crossInv.status, crossList.status].every((s) => s === 403 || s === 404);
  rec({
    ac: 'AC-C11',
    title: 'A self-service customer cannot reach another customer\'s document by id',
    status: crossOk ? 'PASS' : 'FAIL',
    severity: crossOk ? '' : 'high',
    steps: `Customer B's own login: GET /api/documents/${aSharedCustomer.id}/download and /api/documents/${aInvoiceDoc.id}/download (both customer A's, both SHARED), and a list on customer ${A.customer.id}`,
    expected: '403 or 404 on all three — never the bytes',
    actual: `customerDoc=${crossDl.status} invoiceDoc=${crossInv.status} list=${crossList.status}`,
    evidence: crossDl.status === 200 ? `LEAK: ${crossDl.bytes.length} bytes returned` : '',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:130',
  });

  // ---- AC-C12: a customer sees only what is SHARED on their own records -----------------
  const ownShared = await h.download(A.token, aSharedCustomer.id);
  const ownInternal = await h.download(A.token, aInternalCustomer.id);
  const visOk = ownShared.status === 200 && ownInternal.status === 404;
  rec({
    ac: 'AC-C12',
    title: 'A self-service customer downloads a SHARED document on their own record and 404s on an INTERNAL one',
    status: visOk ? 'PASS' : 'FAIL',
    severity: visOk ? '' : 'high',
    steps: `Customer A's login: download ${aSharedCustomer.id} (SHARED) and ${aInternalCustomer.id} (INTERNAL), both on their own customer record`,
    expected: 'SHARED 200, INTERNAL 404',
    actual: `shared=${ownShared.status} internal=${ownInternal.status}`,
    evidence: ownInternal.status === 200 ? `LEAK: internal document returned ${ownInternal.bytes.length} bytes` : '',
    codeRef: `${SVC}:218`,
  });

  const ownList = await list(A.token, 'CUSTOMER', A.customer.id);
  const rows = ownList.json?.content || [];
  const onlyShared = ownList.status === 200 && rows.length > 0
    && rows.every((d) => d.visibility === 'SHARED');
  const adminList = await list(admin, 'CUSTOMER', A.customer.id);
  const adminRows = adminList.json?.content || [];
  const hidesInternal = adminRows.some((d) => d.visibility === 'INTERNAL')
    && !rows.some((d) => d.visibility === 'INTERNAL');
  const listOk = onlyShared && hidesInternal;
  rec({
    ac: 'AC-C12',
    title: 'A customer\'s document list on their own record holds only SHARED rows',
    status: listOk ? 'PASS' : 'FAIL',
    severity: listOk ? '' : 'high',
    steps: `Customer A's login lists CUSTOMER ${A.customer.id}; admin lists the same record for comparison`,
    expected: 'every row the customer sees is SHARED, and the INTERNAL rows admin sees are absent',
    actual: `customer sees ${rows.length} row(s) [${[...new Set(rows.map((d) => d.visibility))].join(',') || 'none'}]; admin sees ${adminRows.length} row(s) [${[...new Set(adminRows.map((d) => d.visibility))].join(',')}]`,
    codeRef: `${SVC}:237`,
  });

  const ownCount = await rt.api('GET', `/api/documents/count?entityType=CUSTOMER&entityId=${A.customer.id}`, { token: A.token });
  const adminCount = await rt.api('GET', `/api/documents/count?entityType=CUSTOMER&entityId=${A.customer.id}`, { token: admin });
  const countOk = ownCount.status === 200 && ownCount.json.count === rows.length
    && ownCount.json.count < adminCount.json.count;
  rec({
    ac: 'AC-C12',
    title: 'The badge count a customer sees excludes INTERNAL documents',
    status: countOk ? 'PASS' : 'FAIL',
    severity: countOk ? '' : 'medium',
    steps: 'GET /api/documents/count on the customer\'s own record as the customer and as admin',
    expected: 'the customer\'s count equals the number of SHARED rows and is lower than admin\'s',
    actual: `customerCount=${ownCount.json?.count} sharedRows=${rows.length} adminCount=${adminCount.json?.count}`,
    codeRef: `${SVC}:151`,
  });

  const custPatch = await rt.api('PATCH', `/api/documents/${aSharedCustomer.id}`, {
    token: A.token, body: { visibility: 'INTERNAL', description: 'customer edit' },
  });
  const custDelete = await rt.api('DELETE', `/api/documents/${aSharedCustomer.id}`, { token: A.token });
  const cdOk = custPatch.status === 403 && custDelete.status === 403;
  rec({
    ac: 'AC-C12',
    title: 'A self-service customer cannot edit visibility or delete a document',
    status: cdOk ? 'PASS' : 'FAIL',
    severity: cdOk ? '' : 'high',
    steps: `Customer A's login: PATCH /api/documents/${aSharedCustomer.id} {visibility:INTERNAL} and DELETE the same id`,
    expected: '403 on both',
    actual: `patch=${custPatch.status} delete=${custDelete.status}`,
    codeRef: `${SVC}:173`,
  });

  const custUp = await h.upload(A.token, {
    entityType: 'CUSTOMER', entityId: A.customer.id, bytes: h.pdf(700),
    filename: 'customer-upload.pdf', contentType: 'application/pdf', visibility: 'INTERNAL',
  });
  if (custUp.json?.id) ctx.uploaded.push(custUp.json);
  // The PRD's AC-C12 says a customer cannot upload; the implementation doc records the opposite
  // decision (§1 answer 4) and forces the upload SHARED so it cannot be hidden from its uploader.
  const custUpOk = custUp.status === 201 && custUp.json.visibility === 'SHARED';
  rec({
    ac: 'AC-C12',
    title: 'Customer self-service upload — documented deviation from AC-C12; the upload is forced SHARED',
    status: custUpOk ? 'PASS' : 'FAIL',
    severity: custUpOk ? '' : 'medium',
    steps: `Customer A's login uploads to their own customer record asking for visibility=INTERNAL`,
    expected: 'docs/implementation/invoice-due-dates-and-documents.md §1 answer 4 records the decision to allow customer uploads; the result must be SHARED whatever was asked for, so an upload is never invisible to its uploader',
    actual: `${custUp.status} visibility=${custUp.json?.visibility}`,
    evidence: 'PRD AC-C12 says "cannot upload... unless the Open Questions resolve otherwise"; the implementation doc resolves it to yes. Flagged for the reviewer, not scored as a defect.',
    codeRef: `${SVC}:85`,
  });

  const custCross = await h.upload(A.token, {
    entityType: 'CUSTOMER', entityId: B.customer.id, bytes: h.pdf(700),
    filename: 'cross-upload.pdf', contentType: 'application/pdf',
  });
  if (custCross.json?.id) ctx.uploaded.push(custCross.json);
  const crossUpOk = custCross.status === 403 || custCross.status === 404;
  rec({
    ac: 'AC-C11',
    title: 'A customer login cannot upload onto another customer\'s record',
    status: crossUpOk ? 'PASS' : 'FAIL',
    severity: crossUpOk ? '' : 'high',
    steps: `Customer A's login POSTs a document to CUSTOMER ${B.customer.id}`,
    expected: '403 or 404',
    actual: `${custCross.status} ${custCross.text.slice(0, 200)}`,
    codeRef: `${TARGETS}:76`,
  });

  // ---- delete: the record's manage privilege, or being the uploader ----------------------
  const uploaderDoc = await h.upload(invoiceViewOnly.token, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(400),
    filename: 'not-allowed.pdf', contentType: 'application/pdf',
  });
  const cashier = await rt.cashierToken();
  const cashierDoc = await h.upload(cashier, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(400),
    filename: 'cashier.pdf', contentType: 'application/pdf',
  });
  if (cashierDoc.json?.id) ctx.uploaded.push(cashierDoc.json);
  const foreignDelete = cashierDoc.json?.id
    ? await rt.api('DELETE', `/api/documents/${cashierDoc.json.id}`, { token: invoiceViewOnly.token })
    : { status: 'no fixture' };
  const delOk = foreignDelete.status === 403;
  rec({
    ac: 'AC-C10',
    title: 'Deleting someone else\'s document needs the record\'s manage privilege',
    status: delOk ? 'PASS' : 'FAIL',
    severity: delOk ? '' : 'high',
    steps: `Cashier uploads a document to invoice ${A.invoice.id}; the INVOICE_VIEW-only login (not the uploader, no INVOICE_MANAGE) tries to delete it`,
    expected: '403 — neither the record\'s manage privilege nor the uploader',
    actual: `upload by view-only role=${uploaderDoc.status}; delete of the cashier's document=${foreignDelete.status}`,
    codeRef: `${SVC}:198`,
  });

  ctx.authz = { aSharedCustomer, aInternalCustomer, aInvoiceDoc, aPaymentDoc, bInvoiceDoc, bCustomerDoc };
};
