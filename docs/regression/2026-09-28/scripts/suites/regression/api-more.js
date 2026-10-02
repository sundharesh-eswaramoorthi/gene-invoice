// Regression API cases, part 2: disputes, documents, email (incl. placeholders), inbox, notifications, dashboard,
// audit, list conventions (paging/filters/regionIds/asOf) and "nothing is held without a threshold" (B2 default).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, ids, num, customer, invoice, payment, getJ, upload, download, PDF, PNG } = H;

const BE = {
  disp: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java',
  doc: 'backend/src/main/java/com/geneinvoice/document/DocumentService.java',
  docRules: 'backend/src/main/java/com/geneinvoice/document/DocumentRules.java',
  email: 'backend/src/main/java/com/geneinvoice/email/EmailService.java',
  ph: 'backend/src/main/java/com/geneinvoice/automation/placeholder/PlaceholderCatalogue.java',
  inbox: 'backend/src/main/java/com/geneinvoice/email/InboxService.java',
  notif: 'backend/src/main/java/com/geneinvoice/notification/NotificationController.java',
  dash: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java',
  audit: 'backend/src/main/java/com/geneinvoice/audit/AuditController.java',
  timeline: 'backend/src/main/java/com/geneinvoice/audit/AuditTimelineService.java',
  query: 'backend/src/main/java/com/geneinvoice/common/query/TableQuery.java',
  scope: 'backend/src/main/java/com/geneinvoice/common/scope/ViewScopeInterceptor.java',
  mc: 'backend/src/main/java/com/geneinvoice/approval/MakerChecker.java',
};

/** Current-month point of a monthly dashboard series. */
const thisMonth = (series) => {
  const ym = rt.day(0).slice(0, 7);
  const p = (series.months || []).find((m) => String(m.month).startsWith(ym));
  return p ? num(p.amount) : null;
};

module.exports = async function apiMore(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users; const D = w.D;

  // ================================================================== DISPUTES
  await tcase({ id: 'REG-DSP-01', feature: 'Disputes', kind: 'API', prd: 'B1',
    title: 'A customer opens a dispute on its invoice; a second open one is refused; staff cannot open one; admins are notified',
    steps: 'fresh customer + invoice; customer login POST /api/disputes {targetType INVOICE, targetId, reason, proposedChangeJson {"action":"update_notes","notes":"fix"}}; again; admin POST /api/disputes; admin GET /api/notifications',
    expected: '200 PENDING with regionCode DEFAULT, internal false (not shown for customers), targetNumber = invoice number; duplicate 400 "An open dispute already exists for this invoice"; staff 403 "Only customers can open disputes"; admin has DISPUTE_OPENED "New dispute from <customer>" linking /disputes/{id}' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Disp', invoices: [{ amount: 600 }] });
    const ct = await rt.login(cu.username, cu.password);
    const inv = cu.invoices[0];
    const r = await rt.createDispute(ct, { targetType: 'INVOICE', targetId: inv.id, reason: 'wrong notes', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'fixed by dispute' }) });
    c.that('200 PENDING', r.status === 200 && r.json.status === 'PENDING' && r.json.customerId === cu.id, brief(r));
    c.that('target number + region', r.json && r.json.targetNumber === inv.invoiceNumber && r.json.regionCode === 'DEFAULT', r.json && { tn: r.json.targetNumber, rc: r.json.regionCode });
    const again = await rt.createDispute(ct, { targetType: 'INVOICE', targetId: inv.id, reason: 'again' });
    c.that('duplicate 400', again.status === 400 && /already exists/.test(again.json && again.json.message), brief(again));
    const staff = await rt.createDispute(T.admin, { targetType: 'INVOICE', targetId: inv.id, reason: 'x' });
    c.that('staff 403', staff.status === 403, brief(staff));
    const n = await rt.list(T.admin, '/api/notifications');
    c.that('admin notified', (n.json.content || []).some((x) => x.type === 'DISPUTE_OPENED' && x.link === `/disputes/${r.json.id}` && x.title.includes(cu.name)), (n.json.content || []).slice(0, 3).map((x) => `${x.type} ${x.link}`));
    return done(c, { codeRef: BE.disp });
  });

  await tcase({ id: 'REG-DSP-02', feature: 'Disputes', kind: 'API', prd: 'B2',
    title: 'Admin approves an invoice dispute: the proposed change is applied (not held: no DISPUTE_APPROVE threshold), the customer is told',
    steps: 'fresh customer + invoice + customer dispute proposing update_notes; admin POST /api/disputes/{id}/approve {adminNotes}; GET invoice; customer GET /api/notifications; approve again',
    expected: '200 APPROVED with resolvedByUserId admin; invoice notes = proposed; customer has DISPUTE_APPROVED notification; a second approve 400 "Dispute already resolved"; invoice audit has DISPUTE_APPROVED' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DispOk', invoices: [{ amount: 600 }] });
    const ct = await rt.login(cu.username, cu.password);
    const inv = cu.invoices[0];
    const d = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: inv.id, reason: 'notes', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'fixed by dispute' }) }), 'dispute');
    const a = await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.admin, body: { adminNotes: 'ok' } });
    c.that('approve 200 APPROVED', a.status === 200 && a.json.status === 'APPROVED' && a.json.resolvedByUserId === U.admin.id, brief(a));
    const g = await getJ(T.admin, `/api/invoices/${inv.id}`);
    c.that('change applied', g.json.notes === 'fixed by dispute', g.json.notes);
    const n = await rt.list(ct, '/api/notifications');
    c.that('customer told', (n.json.content || []).some((x) => x.type === 'DISPUTE_APPROVED'), (n.json.content || []).map((x) => x.type));
    const again = await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.admin, body: {} });
    c.that('again 400', again.status === 400 && /already resolved/.test(again.json && again.json.message), brief(again));
    const au = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: inv.id });
    c.that('invoice audit DISPUTE_APPROVED', (au.json || []).some((x) => x.action === 'DISPUTE_APPROVED'), (au.json || []).map((x) => x.action));
    return done(c, { codeRef: BE.disp });
  });

  await tcase({ id: 'REG-DSP-03', feature: 'Disputes', kind: 'API', prd: 'B1',
    title: 'Deny a dispute; approve a payment dispute that voids the payment; approving an invoice dispute with no change is refused',
    steps: 'customer disputes invoice A (no proposal) -> admin approve {} ; admin deny {adminNotes}; customer disputes payment P {"action":"void"} -> admin approve; GET payment and invoice',
    expected: 'approve with no change 400 "No change specified for approval"; deny 200 DENIED, adminNotes kept, customer gets DISPUTE_DENIED; void approve 200: payment VOIDED, its invoice back to UNPAID with paid 0' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DispDeny', invoices: [{ amount: 600 }, { amount: 400 }] });
    const ct = await rt.login(cu.username, cu.password);
    const [A, B] = cu.invoices;
    const d = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: A.id, reason: 'no proposal' }), 'dispute');
    const ap = await rt.api('POST', `/api/disputes/${d.id}/approve`, { token: T.admin, body: {} });
    c.that('no change 400', ap.status === 400 && /No change specified/.test(ap.json && ap.json.message), brief(ap));
    const dn = await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: T.admin, body: { adminNotes: 'not agreed' } });
    c.that('deny 200 DENIED', dn.status === 200 && dn.json.status === 'DENIED' && dn.json.adminNotes === 'not agreed', brief(dn));
    const n = await rt.list(ct, '/api/notifications');
    c.that('customer told denied', (n.json.content || []).some((x) => x.type === 'DISPUTE_DENIED'), (n.json.content || []).map((x) => x.type));
    const p = await payment(w, cu, { amount: 400, invoiceIds: [B.id] });
    const pd = rt.need(await rt.createDispute(ct, { targetType: 'PAYMENT', targetId: p.id, reason: 'duplicate', proposedChangeJson: JSON.stringify({ action: 'void' }) }), 'payment dispute');
    const va = await rt.api('POST', `/api/disputes/${pd.id}/approve`, { token: T.admin, body: {} });
    c.that('void approve 200', va.status === 200 && va.json.status === 'APPROVED', brief(va));
    const gp = await getJ(T.admin, `/api/payments/${p.id}`);
    const gb = await getJ(T.admin, `/api/invoices/${B.id}`);
    c.that('payment VOIDED', gp.json.status === 'VOIDED', gp.json.status);
    c.that('invoice back to UNPAID', gb.json.status === 'UNPAID' && num(gb.json.paidAmount) === 0, { s: gb.json.status, p: gb.json.paidAmount });
    return done(c, { codeRef: BE.disp });
  });

  await tcase({ id: 'REG-DSP-04', feature: 'Disputes', kind: 'API', prd: 'B1',
    title: 'Dispute visibility: a customer sees only its own disputes, cannot dispute another customer\'s invoice; staff lists filter by status',
    steps: 'customers C1, C2 each dispute own invoice; C1 GET /api/disputes; C1 GET C2\'s dispute; C1 POST dispute on C2\'s invoice; admin GET /api/disputes?filter=status:eq:PENDING&filter=customerId:eq:C1',
    expected: 'C1 list = only its dispute (regionIds not used for customers); C1 GET other 403/404; C1 on other invoice 403; admin filtered list = C1\'s dispute' }, async () => {
    const c = checks();
    const c1 = await customer(w, { prefix: 'DV1', invoices: [{ amount: 100 }] });
    const c2 = await customer(w, { prefix: 'DV2', invoices: [{ amount: 100 }] });
    const t1 = await rt.login(c1.username, c1.password); const t2 = await rt.login(c2.username, c2.password);
    const d1 = rt.need(await rt.createDispute(t1, { targetType: 'INVOICE', targetId: c1.invoices[0].id }), 'd1');
    const d2 = rt.need(await rt.createDispute(t2, { targetType: 'INVOICE', targetId: c2.invoices[0].id }), 'd2');
    const l = await rt.list(t1, '/api/disputes');
    c.that('own only', l.status === 200 && ids(l).join() === String(d1.id), brief(l));
    const g = await getJ(t1, `/api/disputes/${d2.id}`);
    c.that('other dispute 403/404', g.status === 403 || g.status === 404, brief(g));
    const x = await rt.createDispute(t1, { targetType: 'INVOICE', targetId: c2.invoices[0].id });
    c.that('other invoice 403', x.status === 403, brief(x));
    const a = await getJ(T.admin, '/api/disputes', { size: 10, filter: ['status:eq:PENDING', `customerId:eq:${c1.id}`] });
    c.that('admin filter', a.status === 200 && ids(a).join() === String(d1.id), brief(a));
    return done(c, { codeRef: BE.disp });
  });

  // ================================================================== DOCUMENTS
  await tcase({ id: 'REG-DOC-01', feature: 'Documents', kind: 'API', prd: 'B1',
    title: 'Upload a PDF to an invoice (INTERNAL by default); list, count and download it byte for byte',
    steps: 'fresh invoice; POST /api/documents multipart {file doc.pdf, entityType INVOICE, entityId, description}; GET /api/documents?entityType&entityId; GET /count; GET /{id}/download',
    expected: '2xx DocumentDto visibility INTERNAL, contentType application/pdf, sizeBytes = file size, canDownload/canEdit/canDelete true; list 1 row; count 1; download 200 with identical bytes; invoice audit DOCUMENT_UPLOADED' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Doc', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const u = await upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, description: 'contract' });
    c.that('upload 2xx', u.status === 200 || u.status === 201, brief(u));
    const j = u.json || {};
    c.that('INTERNAL, pdf, size, flags', j.visibility === 'INTERNAL' && j.contentType === 'application/pdf' && j.sizeBytes === PDF.length && j.canDownload && j.canEdit && j.canDelete, { v: j.visibility, t: j.contentType, s: j.sizeBytes });
    const l = await getJ(T.admin, '/api/documents', { entityType: 'INVOICE', entityId: inv.id });
    c.that('list 1', l.status === 200 && l.json.totalElements === 1 && l.json.content[0].description === 'contract', brief(l));
    const n = await getJ(T.admin, '/api/documents/count', { entityType: 'INVOICE', entityId: inv.id });
    c.that('count 1', n.status === 200 && n.json.count === 1, brief(n));
    const dl = await download(T.admin, j.id);
    c.that('download identical', dl.status === 200 && Buffer.compare(dl.buf, PDF) === 0, { status: dl.status, len: dl.buf.length });
    const a = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: inv.id });
    c.that('audit DOCUMENT_UPLOADED', (a.json || []).some((x) => x.action === 'DOCUMENT_UPLOADED'), (a.json || []).map((x) => x.action));
    return done(c, { codeRef: BE.doc });
  });

  await tcase({ id: 'REG-DOC-02', feature: 'Documents visibility', kind: 'API', prd: 'B1',
    title: 'INTERNAL vs SHARED: the customer login sees and downloads only SHARED documents; sharing an internal one makes it visible',
    steps: 'fresh customer + invoice; admin uploads I (INTERNAL) and S (SHARED, png) on the invoice; customer GET list/count; download S; download I; admin PATCH I {visibility SHARED, description}; customer list again',
    expected: 'customer list = [S], count 1; S download 200; I download 404; PATCH 200; then customer sees both' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DocVis', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const ct = await rt.login(cu.username, cu.password);
    const I = rt.need(await upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, visibility: 'INTERNAL' }), 'I');
    const S = rt.need(await upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, visibility: 'SHARED', buf: PNG, filename: 's.png', type: 'image/png' }), 'S');
    const l = await getJ(ct, '/api/documents', { entityType: 'INVOICE', entityId: inv.id });
    c.that('customer list = [S]', l.status === 200 && ids(l).join() === String(S.id), brief(l));
    const n = await getJ(ct, '/api/documents/count', { entityType: 'INVOICE', entityId: inv.id });
    c.that('customer count 1', n.json && n.json.count === 1, brief(n));
    const ds = await download(ct, S.id); const di = await download(ct, I.id);
    c.that('S download 200', ds.status === 200 && Buffer.compare(ds.buf, PNG) === 0, ds.status);
    c.that('I download 404', di.status === 404, di.status);
    const p = await rt.api('PATCH', `/api/documents/${I.id}`, { token: T.admin, body: { visibility: 'SHARED', description: 'now shared' } });
    c.that('PATCH 200', p.status === 200 && p.json.visibility === 'SHARED' && p.json.description === 'now shared', brief(p));
    const l2 = await getJ(ct, '/api/documents', { entityType: 'INVOICE', entityId: inv.id });
    c.that('customer sees both', l2.json && l2.json.totalElements === 2, brief(l2));
    return done(c, { codeRef: BE.doc });
  });

  await tcase({ id: 'REG-DOC-03', feature: 'Documents', kind: 'API', prd: 'B1',
    title: 'Customer uploads are always SHARED; customers cannot edit or delete; wrong file types and a missing file are refused; delete removes it',
    steps: 'customer login uploads a PDF on its invoice asking INTERNAL; customer PATCH / DELETE it; admin uploads a .txt; admin uploads with no file; admin DELETE the doc; list',
    expected: 'customer upload 2xx visibility SHARED; customer PATCH 403, DELETE 403; txt 400 fieldErrors.file "Files of this kind cannot be attached..."; no file 400 fieldErrors.file "Choose a file"; admin delete 2xx and list empty' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DocCust', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const ct = await rt.login(cu.username, cu.password);
    const u = await upload(ct, { entityType: 'INVOICE', entityId: inv.id, visibility: 'INTERNAL' });
    c.that('customer upload SHARED', (u.status === 200 || u.status === 201) && u.json.visibility === 'SHARED', brief(u));
    const id = u.json && u.json.id;
    const p = await rt.api('PATCH', `/api/documents/${id}`, { token: ct, body: { description: 'x' } });
    const d = await rt.api('DELETE', `/api/documents/${id}`, { token: ct });
    c.that('customer PATCH 403', p.status === 403, brief(p));
    c.that('customer DELETE 403', d.status === 403, brief(d));
    const txt = await upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, buf: Buffer.from('hello world'), filename: 'a.txt', type: 'text/plain' });
    c.that('txt 400', txt.status === 400 && txt.json.fieldErrors && /cannot be attached/.test(txt.json.fieldErrors.file || ''), brief(txt));
    const nf = await upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, noFile: true });
    c.that('no file 400', nf.status === 400 && nf.json.fieldErrors && /Choose a file/.test(nf.json.fieldErrors.file || ''), brief(nf));
    const ad = await rt.api('DELETE', `/api/documents/${id}`, { token: T.admin });
    c.that('admin delete 2xx', ad.status === 200 || ad.status === 204, brief(ad));
    const l = await getJ(T.admin, '/api/documents', { entityType: 'INVOICE', entityId: inv.id });
    c.that('list empty', l.json && l.json.totalElements === 0, brief(l));
    return done(c, { codeRef: BE.docRules });
  });

  await tcase({ id: 'REG-DOC-04', feature: 'Documents', kind: 'API', prd: 'B1',
    title: 'Documents on a customer and on a payment; a VIEWER can read but not upload',
    steps: 'fresh customer + invoice + payment; admin uploads to CUSTOMER and PAYMENT; viewer GET list for both; viewer upload',
    expected: 'uploads 2xx with entityLink /customers/{id} and /payments/{id}; viewer lists 200 with canEdit false; viewer upload 403' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DocAll', invoices: [{ amount: 100 }] });
    const p = await payment(w, cu, { amount: 50 });
    const a = await upload(T.admin, { entityType: 'CUSTOMER', entityId: cu.id });
    const b = await upload(T.admin, { entityType: 'PAYMENT', entityId: p.id });
    c.that('customer doc', (a.status === 200 || a.status === 201) && a.json.entityLink === `/customers/${cu.id}`, brief(a));
    c.that('payment doc', (b.status === 200 || b.status === 201) && b.json.entityLink === `/payments/${p.id}`, brief(b));
    const va = await getJ(T.viewer, '/api/documents', { entityType: 'CUSTOMER', entityId: cu.id });
    c.that('viewer reads, canEdit false', va.status === 200 && va.json.totalElements === 1 && va.json.content[0].canEdit === false, brief(va));
    const vu = await upload(T.viewer, { entityType: 'CUSTOMER', entityId: cu.id });
    c.that('viewer upload 403', vu.status === 403, brief(vu));
    return done(c, { codeRef: BE.doc });
  });

  // ================================================================== EMAIL
  await tcase({ id: 'REG-EML-01', feature: 'Email compose', kind: 'API', prd: 'A4',
    title: 'Send an email about an invoice with Customer and Invoice placeholders: saved NOT_SENT (transport none) with the text filled in',
    steps: 'fresh customer + invoice 750 (NET_30); POST /api/emails {entityType INVOICE, entityId, to [CUSTOMER], subject "Invoice {{Invoice.Number}} for {{Customer.Name}}", body "Balance {{Invoice.Balance}} due {{Invoice.DueDate}} ({{Invoice.PaymentTerm}}); today {{Today}}"}; GET /api/emails?entityType&entityId; GET /api/emails/{id}',
    expected: 'A4 + PC-10: 200 EmailDto status NOT_SENT (or NOT_CONFIGURED), subject "Invoice INV-… for <name>", body "Balance ₹750.00 due <due> (Net 30); today <today>"; to = the customer address; listed for the invoice; GET by id same text' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Mail', invoices: [{ amount: 750 }] });
    const inv = cu.invoices[0];
    const r = await rt.api('POST', '/api/emails', { token: T.admin, body: { entityType: 'INVOICE', entityId: inv.id, to: [rt.tok.customer()],
      subject: 'Invoice {{Invoice.Number}} for {{Customer.Name}}', body: 'Balance {{Invoice.Balance}} due {{Invoice.DueDate}} ({{Invoice.PaymentTerm}}); today {{Today}}' } });
    c.that('200 NOT_SENT/NOT_CONFIGURED', r.status === 200 && /NOT_SENT|NOT_CONFIGURED/.test(r.json.status), brief(r));
    c.that('subject rendered', r.json && r.json.subject === `Invoice ${inv.invoiceNumber} for ${cu.name}`, r.json && r.json.subject);
    c.that('body rendered', r.json && r.json.body === `Balance ₹750.00 due ${inv.dueDate} (Net 30); today ${rt.day(0)}`, r.json && r.json.body);
    c.that('to = customer', r.json && r.json.to.length === 1 && r.json.to[0].address === cu.email, r.json && r.json.to);
    const l = await rt.emailsFor(T.admin, 'INVOICE', inv.id);
    c.that('listed on the invoice', l.status === 200 && ids(l).includes(r.json && r.json.id), brief(l));
    const g = await getJ(T.admin, `/api/emails/${r.json && r.json.id}`);
    c.that('GET by id same', g.status === 200 && g.json.subject === r.json.subject && g.json.body === r.json.body, brief(g));
    return done(c, { codeRef: BE.email });
  });

  await tcase({ id: 'REG-EML-02', feature: 'Email compose', kind: 'API', prd: 'A4',
    title: 'Unknown or unavailable placeholders are refused on their field; plain text is untouched; promise emails offer customer fields only',
    steps: 'POST /api/emails on an invoice with subject {{Invoice.Foo}} and body {{Rule.Name}}; on a customer with {{Invoice.Number}}; on a promise with {{Customer.Name}} and {{Payment.Amount}}; on an invoice with plain "Hello {name}"',
    expected: '400 fieldErrors.subject "Unknown placeholder {{Invoice.Foo}}", fieldErrors.body "{{Rule.Name}} is not available on invoice emails"; customer email with Invoice.* 400; promise: {{Payment.Amount}} 400, {{Customer.Name}} alone 200 rendered; plain text 200 kept verbatim' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'MailBad', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const send = (entityType, entityId, subject, body) => rt.api('POST', '/api/emails', { token: T.admin, body: { entityType, entityId, to: [rt.tok.customer()], subject, body } });
    const a = await send('INVOICE', inv.id, 'Hi {{Invoice.Foo}}', '{{Rule.Name}}');
    c.that('invoice unknown/rule 400', a.status === 400 && /Unknown placeholder/.test((a.json.fieldErrors || {}).subject || '') && /not available/.test((a.json.fieldErrors || {}).body || ''), brief(a));
    const b = await send('CUSTOMER', cu.id, 'x', 'No {{Invoice.Number}}');
    c.that('customer with Invoice.* 400', b.status === 400 && (b.json.fieldErrors || {}).body, brief(b));
    const pr = rt.need(await rt.createPromise(T.admin, { customerId: cu.id, amount: 50, collectionPocUserId: U.coll.id }), 'promise');
    const p1 = await send('PROMISE', pr.id, 'x', '{{Payment.Amount}}');
    c.that('promise with Payment.* 400', p1.status === 400, brief(p1));
    const p2 = await send('PROMISE', pr.id, 'Promise from {{Customer.Name}}', 'ok');
    c.that('promise with Customer.Name 200', p2.status === 200 && p2.json.subject === `Promise from ${cu.name}`, brief(p2));
    const pl = await send('INVOICE', inv.id, 'Hello {name}', 'Plain {{ not a token');
    c.that('plain text kept', pl.status === 200 && pl.json.subject === 'Hello {name}' && pl.json.body === 'Plain {{ not a token', brief(pl));
    return done(c, { codeRef: BE.email });
  });

  await tcase({ id: 'REG-EML-03', feature: 'Email compose', kind: 'API', prd: 'A4',
    title: 'Placeholder catalogue offers Customer and record fields for invoice/payment/customer, customer fields for promise/dispute',
    steps: 'GET /api/automation/placeholders?entityType=INVOICE, PAYMENT, CUSTOMER, PROMISE, DISPUTE, PRODUCT',
    expected: 'DESIGN §6.14 / §5.10.4: INVOICE groups Customer (CUSTOMER), Invoice (RECORD, has {{Invoice.Balance}}), General (COMMON, {{Today}}); PAYMENT has a Payment group; CUSTOMER has no record group; PROMISE/DISPUTE Customer + General(Today only); PRODUCT 400' }, async () => {
    const c = checks();
    const g = async (t) => getJ(T.admin, '/api/automation/placeholders', { entityType: t });
    const inv = await g('INVOICE');
    const lv = (r) => (r.json && r.json.groups || []).map((x) => x.level).join();
    const has = (r, token) => (r.json && r.json.groups || []).some((x) => x.items.some((i) => i.token === token));
    c.that('INVOICE groups', inv.status === 200 && lv(inv) === 'CUSTOMER,RECORD,COMMON' && has(inv, '{{Invoice.Balance}}') && has(inv, '{{Customer.Name}}') && has(inv, '{{Today}}'), inv.json);
    const pay = await g('PAYMENT');
    c.that('PAYMENT record group', pay.status === 200 && has(pay, '{{Payment.Amount}}'), lv(pay));
    const cus = await g('CUSTOMER');
    c.that('CUSTOMER: no record group', cus.status === 200 && lv(cus) === 'CUSTOMER,COMMON', lv(cus));
    const pr = await g('PROMISE');
    c.that('PROMISE: customer + Today only', pr.status === 200 && lv(pr) === 'CUSTOMER,COMMON' && !has(pr, '{{Rule.Name}}') && has(pr, '{{Today}}'), pr.json && pr.json.groups);
    const ds = await g('DISPUTE');
    c.that('DISPUTE 200', ds.status === 200 && lv(ds) === 'CUSTOMER,COMMON', lv(ds));
    const bad = await g('PRODUCT');
    c.that('PRODUCT 400', bad.status === 400, brief(bad));
    return done(c, { codeRef: BE.ph });
  });

  await tcase({ id: 'REG-EML-04', feature: 'Email compose', kind: 'API', prd: 'A3',
    title: 'Email context lists roles by level with their holders; preview warns that delivery is not configured; To by role resolves to the POC',
    steps: 'fresh customer (Collection seat coll) + invoice (Sales POC sales); GET /api/emails/context?entityType=INVOICE&entityId; POST /api/emails/preview {to [ROLE COLLECTION_POC CUSTOMER]}; POST /api/emails same',
    expected: 'context delivery.configured false; roles include COLLECTION_POC (Customer level, people [coll]) and SALES_POC (Invoice level, people [sales]); preview 200 with warning "Email delivery is not configured..." and to = coll; send 200 with to = coll' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'MailCtx', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const ctx = await getJ(T.admin, '/api/emails/context', { entityType: 'INVOICE', entityId: inv.id });
    const role = (n) => (ctx.json && ctx.json.roles || []).find((r) => r.role === n) || {};
    c.that('delivery not configured', ctx.status === 200 && ctx.json.delivery.configured === false, brief(ctx));
    c.that('COLLECTION_POC customer level = coll', role('COLLECTION_POC').level === 'CUSTOMER' && (role('COLLECTION_POC').people || []).some((p) => p.userId === U.coll.id), role('COLLECTION_POC'));
    c.that('SALES_POC invoice level = sales', role('SALES_POC').level === 'RECORD' && (role('SALES_POC').people || []).some((p) => p.userId === U.sales.id), role('SALES_POC'));
    const body = { entityType: 'INVOICE', entityId: inv.id, to: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')], subject: 's', body: 'b' };
    const pv = await rt.api('POST', '/api/emails/preview', { token: T.admin, body });
    c.that('preview warns', pv.status === 200 && (pv.json.warnings || []).some((x) => /not configured/.test(x)) && (pv.json.to || []).some((t) => t.userId === U.coll.id), brief(pv));
    const s = await rt.api('POST', '/api/emails', { token: T.admin, body });
    c.that('send to coll', s.status === 200 && (s.json.to || []).some((t) => t.userId === U.coll.id), brief(s));
    return done(c, { codeRef: BE.email });
  });

  // ================================================================== INBOX & NOTIFICATIONS
  await tcase({ id: 'REG-INB-01', feature: 'Inbox', kind: 'API', prd: 'B1',
    title: 'An email to a colleague lands in his inbox; unread count, read/unread, bulk and mark-all-read work',
    steps: 'admin emails sales2 (USER) twice about an invoice; sales2 GET /api/inbox, /unread-count; POST /{id}/read; /{id}/unread; POST /bulk MARK_READ; POST /mark-all-read',
    expected: 'inbox has both (read false), envelope regionIds [DEFAULT]; unread 2; read -> 1; unread -> 2; bulk MARK_READ one -> 1; mark-all-read -> 0' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Inbox', invoices: [{ amount: 100 }] });
    const t2 = T.sales2;
    await rt.api('POST', '/api/inbox/mark-all-read', { token: t2, body: {} });
    const s1 = rt.need(await rt.api('POST', '/api/emails', { token: T.admin, body: { entityType: 'INVOICE', entityId: cu.invoices[0].id, to: [rt.tok.user(U.sales2.id)], subject: `A ${rt.uniq('i')}`, body: 'one' } }), 'mail1');
    const s2 = rt.need(await rt.api('POST', '/api/emails', { token: T.admin, body: { entityType: 'INVOICE', entityId: cu.invoices[0].id, to: [rt.tok.user(U.sales2.id)], subject: `B ${rt.uniq('i')}`, body: 'two' } }), 'mail2');
    const cnt = async () => (await getJ(t2, '/api/inbox/unread-count')).json.count;
    const l = await getJ(t2, '/api/inbox', { size: 10 });
    const mine = (l.json.content || []).filter((x) => x.emailId === s1.id || x.emailId === s2.id);
    c.that('both in inbox unread', mine.length === 2 && mine.every((x) => x.read === false), mine.map((x) => x.subject));
    c.that('envelope regionIds [DEFAULT]', JSON.stringify(l.json.regionIds) === JSON.stringify([D]), l.json.regionIds);
    c.that('unread 2', (await cnt()) === 2);
    const i1 = mine[0].id; const i2 = mine[1].id;
    await rt.api('POST', `/api/inbox/${i1}/read`, { token: t2, body: {} });
    c.that('read -> 1', (await cnt()) === 1);
    await rt.api('POST', `/api/inbox/${i1}/unread`, { token: t2, body: {} });
    c.that('unread -> 2', (await cnt()) === 2);
    const b = await rt.api('POST', '/api/inbox/bulk', { token: t2, body: { action: 'MARK_READ', ids: [i2] } });
    c.that('bulk MARK_READ -> 1', b.status === 200 && (await cnt()) === 1, brief(b));
    const m = await rt.api('POST', '/api/inbox/mark-all-read', { token: t2, body: {} });
    c.that('mark-all-read -> 0', m.status === 200 && (await cnt()) === 0, brief(m));
    return done(c, { codeRef: BE.inbox });
  });

  await tcase({ id: 'REG-NOT-01', feature: 'Notifications', kind: 'API', prd: 'B1',
    title: 'Notifications: POC assignment notices arrive with links; unread count, read one, bulk MARK_UNREAD and mark-all-read',
    steps: 'sales2 mark-all-read; admin creates 2 invoices with Sales POC sales2; sales2 GET /api/notifications, /unread-count; POST /{id}/read; POST /bulk {MARK_UNREAD}; POST /mark-all-read',
    expected: '2 POC_ASSIGNED "You are now Sales POC" with links /invoices/{id}, envelope regionIds [DEFAULT]; unread 2 -> read one -> 1 -> bulk unread -> 2 -> mark all -> 0' }, async () => {
    const c = checks();
    const t2 = T.sales2;
    await rt.api('POST', '/api/notifications/mark-all-read', { token: t2, body: {} });
    const cu = await customer(w, { prefix: 'Notif' });
    const i1 = await invoice(w, cu, { amount: 10, sales: U.sales2.id });
    const i2 = await invoice(w, cu, { amount: 20, sales: U.sales2.id });
    const l = await getJ(t2, '/api/notifications', { size: 10 });
    const mine = (l.json.content || []).filter((x) => x.link === `/invoices/${i1.id}` || x.link === `/invoices/${i2.id}`);
    c.that('2 POC_ASSIGNED unread', mine.length === 2 && mine.every((x) => x.type === 'POC_ASSIGNED' && x.read === false && /Sales POC/.test(x.title)), mine);
    c.that('envelope regionIds [DEFAULT]', JSON.stringify(l.json.regionIds) === JSON.stringify([D]), l.json.regionIds);
    const cnt = async () => (await getJ(t2, '/api/notifications/unread-count')).json.count;
    c.that('unread 2', (await cnt()) === 2);
    await rt.api('POST', `/api/notifications/${mine[0].id}/read`, { token: t2, body: {} });
    c.that('read -> 1', (await cnt()) === 1);
    const b = await rt.api('POST', '/api/notifications/bulk', { token: t2, body: { action: 'MARK_UNREAD', ids: [mine[0].id] } });
    c.that('bulk unread -> 2', b.status === 200 && (await cnt()) === 2, brief(b));
    await rt.api('POST', '/api/notifications/mark-all-read', { token: t2, body: {} });
    c.that('mark all -> 0', (await cnt()) === 0);
    return done(c, { codeRef: BE.notif });
  });

  // ================================================================== DASHBOARD
  await tcase({ id: 'REG-DSH-01', feature: 'Dashboard', kind: 'API', prd: 'B1',
    title: 'Dashboard billed/collected by month move by exactly what is invoiced and paid; cancelled invoices are left out; scope is [DEFAULT]',
    steps: 'read /api/dashboard/billed-by-month and /collected-by-month; create an invoice 1234.00 and a payment 234.00 for it, and a cancelled invoice 999; read again',
    expected: 'current month billed +1234 (not +2233), collected +234; each response carries scope {regionIds [DEFAULT], bookRegionIds [], asOf null} (§6.9); 12 months by default' }, async () => {
    const c = checks();
    const b0 = (await getJ(T.admin, '/api/dashboard/billed-by-month')).json;
    const c0 = (await getJ(T.admin, '/api/dashboard/collected-by-month')).json;
    const cu = await customer(w, { prefix: 'Dash', invoices: [{ amount: 1234 }, { amount: 999 }] });
    await rt.cancelInvoice(T.admin, cu.invoices[1].id);
    await payment(w, cu, { amount: 234, invoiceIds: [cu.invoices[0].id] });
    const b1 = (await getJ(T.admin, '/api/dashboard/billed-by-month')).json;
    const c1 = (await getJ(T.admin, '/api/dashboard/collected-by-month')).json;
    c.that('billed +1234', Math.abs(thisMonth(b1) - thisMonth(b0) - 1234) < 0.001, { before: thisMonth(b0), after: thisMonth(b1) });
    c.that('collected +234', Math.abs(thisMonth(c1) - thisMonth(c0) - 234) < 0.001, { before: thisMonth(c0), after: thisMonth(c1) });
    c.that('12 months', (b1.months || []).length === 12, (b1.months || []).length);
    c.that('scope [DEFAULT]', b1.scope && JSON.stringify(b1.scope.regionIds) === JSON.stringify([D]) && b1.scope.asOf === null && b1.scope.bookRegionIds.length === 0, b1.scope);
    const b3 = await getJ(T.admin, '/api/dashboard/billed-by-month', { months: 3 });
    c.that('months=3 -> 3 points', b3.status === 200 && b3.json.months.length === 3, b3.json && b3.json.months.length);
    return done(c, { codeRef: BE.dash });
  });

  await tcase({ id: 'REG-DSH-02', feature: 'Dashboard', kind: 'API', prd: 'B1',
    title: 'Outstanding by days overdue puts balances in the right buckets; top customers by outstanding and by payments rank correctly',
    steps: 'read /api/dashboard/outstanding-by-age; fresh customer with invoices 5,000,000 due +10 and 70 due -40 (dated -50); payment 3,000,000 on the big one; read ageing, /top-outstanding-customers, /top-paying-customers',
    expected: 'DESIGN §6.9 ageing (D4): "Not yet due" +2,000,000 (5,000,000 less 3,000,000 paid) and "31–60 days" +70; the customer ranks first in top outstanding (2,000,070 over 2 open invoices) and first in top paying (collected 3,000,000)' }, async () => {
    const c = checks();
    const age0 = (await getJ(T.admin, '/api/dashboard/outstanding-by-age')).json;
    const cu = await customer(w, { prefix: 'DashAge' });
    const big = await invoice(w, cu, { amount: 5000000, dueDate: rt.day(10), paymentTerm: 'CUSTOM' });
    const old = await invoice(w, cu, { amount: 70, dueDate: rt.day(-40), invoiceDate: `${rt.day(-50)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    await payment(w, cu, { amount: 3000000, invoiceIds: [big.id] });
    const age1 = (await getJ(T.admin, '/api/dashboard/outstanding-by-age')).json;
    const b = (a, label) => num(((a.buckets || []).find((x) => x.label === label) || {}).amount);
    c.that('Not yet due +2,000,000', Math.abs(b(age1, 'Not yet due') - b(age0, 'Not yet due') - 2000000) < 0.001, { before: b(age0, 'Not yet due'), after: b(age1, 'Not yet due') });
    c.that('31–60 days +70', Math.abs(b(age1, '31–60 days') - b(age0, '31–60 days') - 70) < 0.001, { before: b(age0, '31–60 days'), after: b(age1, '31–60 days') });
    const to = await getJ(T.admin, '/api/dashboard/top-outstanding-customers');
    c.that('top outstanding first', to.status === 200 && to.json.customers[0].customerId === cu.id && num(to.json.customers[0].outstanding) === 2000070 && to.json.customers[0].openInvoices === 2, to.json && to.json.customers[0]);
    const tp = await getJ(T.admin, '/api/dashboard/top-paying-customers');
    c.that('top paying first', tp.status === 200 && tp.json.customers[0].customerId === cu.id && num(tp.json.customers[0].collected) === 3000000, tp.json && tp.json.customers[0]);
    void old;
    return done(c, { codeRef: BE.dash });
  });

  await tcase({ id: 'REG-DSH-03', feature: 'Dashboard', kind: 'API', prd: 'B1',
    title: 'A Sales POC\'s dashboard counts only his own invoices (book), flagged as such in scope',
    steps: 'sales GET /api/dashboard/billed-by-month; compare with the sum of his current-month non-cancelled invoices from GET /api/invoices (his book)',
    expected: 'DESIGN §5.6.4 / §6.9: billed this month = Σ total of sales\' own non-cancelled invoices dated this month; scope.bookRegionIds [DEFAULT]; coverage not ALL-of-everyone (admin figure is larger)' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DashPoc', invoices: [{ amount: 321 }, { amount: 654, sales: U.admin.id }] });
    void cu;
    const d = await getJ(T.sales, '/api/dashboard/billed-by-month');
    const l = await rt.list(T.sales, '/api/invoices');
    const ym = rt.day(0).slice(0, 7);
    const want = (l.json.content || []).filter((i) => i.status !== 'CANCELLED' && String(i.invoiceDate).startsWith(ym)).reduce((s, i) => s + num(i.total), 0);
    const got = thisMonth(d.json);
    c.that('billed = own invoices', d.status === 200 && Math.abs(got - want) < 0.001, { got, want });
    c.that('bookRegionIds [DEFAULT]', d.json && JSON.stringify(d.json.scope.bookRegionIds) === JSON.stringify([D]), d.json && d.json.scope);
    const a = thisMonth((await getJ(T.admin, '/api/dashboard/billed-by-month')).json);
    c.that('admin sees more', a > got, { admin: a, sales: got });
    return done(c, { codeRef: BE.dash });
  });

  // ================================================================== AUDIT
  await tcase({ id: 'REG-AUD-01', feature: 'Audit history', kind: 'API', prd: 'B1',
    title: 'Customer history with related records shows the invoice, payment and customer events with actor names',
    steps: 'fresh customer; invoice; payment; customer edit; GET /api/audit?entityType=CUSTOMER&entityId&includeRelated=true',
    expected: 'rows CUSTOMER_CREATED, CUSTOMER_UPDATED, INVOICE_CREATED, PAYMENT_RECORDED (and PAYMENT_APPLIED) with changedByUsername admin; each has createdAt; newest first' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Aud', invoices: [{ amount: 500 }] });
    await payment(w, cu, { amount: 100 });
    await rt.api('PUT', `/api/customers/${cu.id}`, { token: T.admin, body: { name: `${cu.name} x`, phone: cu.phone, email: cu.email, address: cu.address, paymentTerm: 'NET_30' } });
    const a = await getJ(T.admin, '/api/audit', { entityType: 'CUSTOMER', entityId: cu.id, includeRelated: true });
    const acts = (a.json || []).map((x) => x.action);
    for (const want of ['CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'INVOICE_CREATED', 'PAYMENT_RECORDED']) c.that(`has ${want}`, acts.includes(want), acts);
    c.that('actor admin on created row', (a.json || []).some((x) => x.action === 'INVOICE_CREATED' && x.changedByUsername === 'admin'), (a.json || []).slice(0, 2));
    const times = (a.json || []).map((x) => Date.parse(x.createdAt));
    c.that('newest first', times.every((t, i) => i === 0 || times[i - 1] >= t), times.slice(0, 5));
    return done(c, { codeRef: BE.timeline });
  });

  await tcase({ id: 'REG-AUD-02', feature: 'Audit history', kind: 'API', prd: 'B1',
    title: 'A Sales POC reads the history of his own invoice but gets 404 on an invoice outside his book; a customer login reads its own customer history',
    steps: 'invoice A (Sales POC sales) and B (Sales POC admin); sales GET /api/audit?entityType=INVOICE&entityId=A and =B; customer login GET audit of its customer',
    expected: 'A 200 with INVOICE_CREATED; B 404; customer 200' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'AudPoc', invoices: [{ amount: 100 }, { amount: 100, sales: U.admin.id }] });
    const a = await getJ(T.sales, '/api/audit', { entityType: 'INVOICE', entityId: cu.invoices[0].id });
    const b = await getJ(T.sales, '/api/audit', { entityType: 'INVOICE', entityId: cu.invoices[1].id });
    c.that('own invoice 200', a.status === 200 && (a.json || []).some((x) => x.action === 'INVOICE_CREATED'), brief(a));
    c.that('other invoice 404', b.status === 404, brief(b));
    const ct = await rt.login(cu.username, cu.password);
    const cc = await getJ(ct, '/api/audit', { entityType: 'CUSTOMER', entityId: cu.id });
    c.that('customer own history 200', cc.status === 200, brief(cc));
    return done(c, { codeRef: BE.audit });
  });

  // ================================================================== LIST CONVENTIONS / NEW PARAMS DON'T BREAK OLD CALLS
  await tcase({ id: 'REG-LST-01', feature: 'List conventions', kind: 'API', prd: 'B1',
    title: 'Lists keep their paging and validation contract: sizes 10/20/50 only, bad sort/filter refused, page beyond the end is empty',
    steps: 'GET /api/invoices?size=15; ?sort=nosuch,asc; ?filter=nosuch:eq:1; ?filter=total:gt:abc; ?size=10&page=9999; ?size=20 (defaults)',
    expected: '400 "size must be one of [10, 20, 50]"; 400; 400; 400; 200 empty content with the real totalElements; size 20 page 0 sort invoiceDate,desc' }, async () => {
    const c = checks();
    const q = (query) => getJ(T.admin, '/api/invoices', query);
    const a = await q({ size: 15 }); const b = await q({ sort: 'nosuch,asc' }); const d = await q({ filter: 'nosuch:eq:1' }); const e = await q({ filter: 'total:gt:abc' });
    const f = await q({ size: 10, page: 9999 }); const g = await q({});
    c.that('size 15 400', a.status === 400 && /size must be one of/.test(a.json && a.json.message), brief(a));
    c.that('bad sort 400', b.status === 400, brief(b));
    c.that('bad filter field 400', d.status === 400, brief(d));
    c.that('bad filter value 400', e.status === 400, brief(e));
    c.that('page beyond end empty', f.status === 200 && f.json.content.length === 0 && f.json.totalElements > 0, brief(f));
    c.that('defaults 20/invoiceDate,desc', g.status === 200 && g.json.size === 20 && g.json.page === 0 && g.json.sort === 'invoiceDate,desc', g.json && { size: g.json.size, sort: g.json.sort });
    return done(c, { codeRef: BE.query });
  });

  await tcase({ id: 'REG-LST-02', feature: 'List conventions', kind: 'API', prd: 'B1',
    title: 'The new view-scope params are optional and harmless for a single-region user: regionIds=<DEFAULT> equals no param; junk is 400; an ungranted id answers empty',
    steps: 'GET /api/invoices (no params); ?regionIds=<D>; ?regionIds=abc; ?regionIds=999999',
    expected: 'DESIGN §6.0: same totalElements with and without regionIds=D; abc -> 400 error REGION_IDS_INVALID; 999999 -> 200 totalElements 0, regionIds []' }, async () => {
    const c = checks();
    const a = await getJ(T.admin, '/api/invoices', { size: 10 });
    const b = await getJ(T.admin, '/api/invoices', { size: 10, regionIds: D });
    const x = await getJ(T.admin, '/api/invoices', { size: 10, regionIds: 'abc' });
    const y = await getJ(T.admin, '/api/invoices', { size: 10, regionIds: 999999 });
    c.that('same total', a.status === 200 && b.status === 200 && a.json.totalElements === b.json.totalElements, { a: a.json && a.json.totalElements, b: b.json && b.json.totalElements });
    c.that('abc 400 REGION_IDS_INVALID', x.status === 400 && x.json.error === 'REGION_IDS_INVALID', brief(x));
    c.that('ungranted id empty', y.status === 200 && y.json.totalElements === 0 && y.json.regionIds.length === 0, brief(y));
    return done(c, { codeRef: BE.scope });
  });

  await tcase({ id: 'REG-LST-03', feature: 'List conventions', kind: 'API', prd: 'B3',
    title: 'asOf is optional: asOf=today answers live with X-View-As-Of: live; writes without asOf are unaffected; a write with a past asOf is refused',
    steps: 'GET /api/invoices?asOf=<today>; GET /api/history/range; POST /api/customers?asOf=<yesterday>',
    expected: 'DESIGN §6.0/§6.10/§6.17: 200 with header X-View-As-Of "live" and same total as without asOf; range {latestAsOf yesterday, timezone UTC}; write with asOf=yesterday 400 AS_OF_READ_ONLY (nothing created)' }, async () => {
    const c = checks();
    const a = await getJ(T.admin, '/api/invoices', { size: 10 });
    const t = await getJ(T.admin, '/api/invoices', { size: 10, asOf: rt.day(0) });
    c.that('asOf=today live', t.status === 200 && t.headers['x-view-as-of'] === 'live' && t.json.totalElements === a.json.totalElements, { status: t.status, hdr: t.headers['x-view-as-of'] });
    const r = await getJ(T.admin, '/api/history/range');
    c.that('range', r.status === 200 && r.json.latestAsOf === rt.day(-1) && r.json.timezone === 'UTC', brief(r));
    const name = rt.uniq('AsOfW');
    const wr = await rt.api('POST', '/api/customers', { token: T.admin, query: { asOf: rt.day(-1) }, body: { name, username: name, password: rt.PASSWORD, regionId: D } });
    c.that('write with past asOf 400 AS_OF_READ_ONLY (or AS_OF_BEFORE_HISTORY)', wr.status === 400 && /AS_OF_/.test(wr.json && wr.json.error), brief(wr));
    const l = await getJ(T.admin, '/api/customers', { size: 10, filter: `name:eq:${name}` });
    c.that('nothing created', l.json && l.json.totalElements === 0, l.json && l.json.totalElements);
    return done(c, { codeRef: BE.scope });
  });

  await tcase({ id: 'REG-APR-01', feature: 'Maker-checker off by default', kind: 'API', prd: 'B2',
    title: 'With no thresholds configured, big money moves apply at once for a single-region admin and a POC: nothing is held',
    steps: 'GET /api/regions/<D>/approval-thresholds; admin invoice 9,999,999; admin payment 9,000,000; admin cancel the invoice? (has payment -> refund rule) -> cancel another big invoice; admin promise 5,000,000; sales invoice 8,000,000 in his book; GET /api/approvals/summary',
    expected: 'B2 "any change above a configurable threshold" - none configured: all 8 thresholds amount null; every write 200 (never 202); approvals summary pendingCount 0' }, async () => {
    const c = checks();
    const th = await rt.getThresholds(T.admin, D);
    c.that('all thresholds null', th.status === 200 && th.json.thresholds.length === 8 && th.json.thresholds.every((x) => x.amount === null), th.json && th.json.thresholds.map((x) => `${x.operation}:${x.amount}`));
    const cu = await customer(w, { prefix: 'Big' });
    const i1 = await rt.createInvoice(T.admin, { customerId: cu.id, amount: 9999999, productId: w.product.id, salesPocUserId: U.sales.id });
    c.that('big invoice 200', i1.status === 200 && !i1.held, brief(i1));
    const p = await rt.createPayment(T.admin, { customerId: cu.id, amount: 9000000, invoiceIds: [i1.json.id], collectionPocUserId: U.coll.id });
    c.that('big payment 200', p.status === 200 && !p.held, brief(p));
    const i2 = await rt.createInvoice(T.admin, { customerId: cu.id, amount: 7777777, productId: w.product.id, salesPocUserId: U.sales.id });
    const cx = await rt.cancelInvoice(T.admin, i2.json.id);
    c.that('big cancel 200', cx.status === 200 && cx.json.status === 'CANCELLED', brief(cx));
    const pr = await rt.createPromise(T.admin, { customerId: cu.id, amount: 5000000, collectionPocUserId: U.coll.id });
    c.that('big promise 200', pr.status === 200 && !pr.held, brief(pr));
    const si = await rt.createInvoice(T.sales, { customerId: cu.id, amount: 8000000, productId: w.product.id, salesPocUserId: U.sales.id });
    c.that('POC big invoice 200', si.status === 200 && !si.held, brief(si));
    const s = await getJ(T.admin, '/api/approvals/summary');
    c.that('nothing pending', s.status === 200 && s.json.pendingCount === 0, brief(s));
    return done(c, { codeRef: BE.mc });
  });
};
