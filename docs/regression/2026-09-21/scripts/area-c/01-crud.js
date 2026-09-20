// AC-C1, AC-C2, AC-C3, AC-C4, D7 — upload / list / download / edit / delete on all three
// entity types, byte-exact round trip, paging, soft delete and the audit trail.
const rt = require('../lib.js');
const h = require('./helpers.js');

const SVC = 'backend/src/main/java/com/geneinvoice/document/DocumentService.java';
const CTL = 'backend/src/main/java/com/geneinvoice/document/DocumentController.java';

module.exports = async function crud(ctx, rec) {
  const { admin, A } = ctx;
  const targets = [
    { type: 'CUSTOMER', id: A.customer.id, bytes: h.pdf(4096), name: 'contract.pdf', ct: 'application/pdf', expect: 'application/pdf' },
    { type: 'INVOICE', id: A.invoice.id, bytes: h.png(), name: 'po-scan.png', ct: 'image/png', expect: 'image/png' },
    { type: 'PAYMENT', id: A.payment.id, bytes: h.jpeg(3000), name: 'cheque.jpg', ct: 'image/jpeg', expect: 'image/jpeg' },
  ];

  // ---- AC-C1: upload to each of the three entity types --------------------------------
  for (const t of targets) {
    const r = await h.upload(admin, {
      entityType: t.type, entityId: t.id, bytes: t.bytes, filename: t.name, contentType: t.ct,
    });
    t.doc = r.json;
    const ok = r.status === 201 && r.json?.id && r.json.contentType === t.expect
      && r.json.sizeBytes === t.bytes.length && r.json.filename === t.name
      && r.json.entityType === t.type && r.json.entityId === t.id;
    rec({
      ac: 'AC-C1',
      title: `Upload a document to a ${t.type}`,
      status: ok ? 'PASS' : 'FAIL',
      severity: ok ? '' : 'high',
      steps: `POST /api/documents (multipart) entityType=${t.type} entityId=${t.id} file=${t.name} (${t.bytes.length} B)`,
      expected: `201 with contentType ${t.expect}, sizeBytes ${t.bytes.length}, filename ${t.name}`,
      actual: ok ? `201 id=${r.json.id} contentType=${r.json.contentType} sizeBytes=${r.json.sizeBytes}`
        : `${r.status} ${r.text.slice(0, 300)}`,
      codeRef: `${SVC}:80`,
    });
  }
  if (targets.some((t) => !t.doc?.id)) throw new Error('crud fixtures did not upload');

  // ---- AC-C1: byte-exact round trip ---------------------------------------------------
  const roundTrip = [];
  for (const t of targets) {
    const d = await h.download(admin, t.doc.id);
    roundTrip.push({
      type: t.type, status: d.status, sent: h.sha256(t.bytes), got: h.sha256(d.bytes),
      len: d.bytes.length, want: t.bytes.length,
    });
  }
  const rtOk = roundTrip.every((r) => r.status === 200 && r.sent === r.got && r.len === r.want);
  rec({
    ac: 'AC-C1',
    title: 'Downloaded bytes are identical to the bytes uploaded (all three entity types)',
    status: rtOk ? 'PASS' : 'FAIL',
    severity: rtOk ? '' : 'high',
    steps: 'GET /api/documents/{id}/download for the customer, invoice and payment documents; SHA-256 both sides',
    expected: '200 and a SHA-256 equal to the uploaded bytes, same length',
    actual: roundTrip.map((r) => `${r.type}: ${r.status} ${r.len}/${r.want} B sha ${r.sent === r.got ? 'match' : 'MISMATCH'}`).join('; '),
    codeRef: `${SVC}:163`,
  });

  // ---- AC-C2: listing, newest first, paged --------------------------------------------
  const extra = [];
  for (let i = 0; i < 3; i++) {
    const r = await h.upload(admin, {
      entityType: 'CUSTOMER', entityId: A.customer.id, bytes: h.pdf(600 + i),
      filename: `page-${i}.pdf`, contentType: 'application/pdf',
    });
    extra.push(r.json);
    await new Promise((s) => setTimeout(s, 1100)); // uploadedAt has second-ish granularity in the sort
  }
  const list = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${A.customer.id}`, { token: admin });
  const names = (list.json?.content || []).map((d) => d.filename);
  const newestFirst = names[0] === 'page-2.pdf' && names[1] === 'page-1.pdf' && names[2] === 'page-0.pdf';
  rec({
    ac: 'AC-C2',
    title: 'Listing a record\'s documents is newest-first',
    status: newestFirst ? 'PASS' : 'FAIL',
    severity: newestFirst ? '' : 'medium',
    steps: `Upload page-0/1/2.pdf in order to CUSTOMER ${A.customer.id}; GET /api/documents?entityType=CUSTOMER&entityId=${A.customer.id}`,
    expected: 'page-2.pdf, page-1.pdf, page-0.pdf at the head of the list',
    actual: `first three: ${names.slice(0, 3).join(', ')}`,
    codeRef: `${SVC}:51`,
  });

  // Enough rows for a second page: the app's list endpoints allow sizes 10, 20 and 50 only.
  for (let i = 0; i < 8; i++) {
    const r = await h.upload(admin, {
      entityType: 'CUSTOMER', entityId: A.customer.id, bytes: h.pdf(400),
      filename: `fill-${i}.pdf`, contentType: 'application/pdf',
    });
    if (r.json?.id) extra.push(r.json);
  }
  const p0 = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${A.customer.id}&page=0&size=10`, { token: admin });
  const p1 = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${A.customer.id}&page=1&size=10`, { token: admin });
  const badSize = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${A.customer.id}&size=2`, { token: admin });
  const total = p0.json?.totalElements;
  const firstIds = new Set((p0.json?.content || []).map((d) => d.id));
  const pagedOk = p0.status === 200 && p0.json.content.length === 10
    && p1.status === 200 && p1.json.content.length >= 1
    && total >= 11 && p0.json.totalPages === Math.ceil(total / 10)
    && (p1.json.content || []).every((d) => !firstIds.has(d.id))
    && badSize.status === 400;
  rec({
    ac: 'AC-C2',
    title: 'Listing is paged the way the app\'s other lists are (page/size/totalElements/totalPages)',
    status: pagedOk ? 'PASS' : 'FAIL',
    severity: pagedOk ? '' : 'medium',
    steps: 'Upload 12+ documents on one customer; GET /api/documents?...&page=0&size=10 then page=1&size=10; also GET with size=2',
    expected: '10 rows on page 0, the rest on page 1 with no overlap, totalElements and totalPages consistent, and size=2 refused with 400 (allowed sizes 10/20/50)',
    actual: `page0=${p0.json?.content?.length} page1=${p1.json?.content?.length} totalElements=${total} totalPages=${p0.json?.totalPages} size=2 -> ${badSize.status}`,
    codeRef: `${SVC}:136`,
  });

  const count = await rt.api('GET', `/api/documents/count?entityType=CUSTOMER&entityId=${A.customer.id}`, { token: admin });
  const countOk = count.status === 200 && count.json.count === total;
  rec({
    ac: 'AC-C1',
    title: 'The tab badge count matches the list total',
    status: countOk ? 'PASS' : 'FAIL',
    severity: countOk ? '' : 'low',
    steps: 'GET /api/documents/count?entityType=CUSTOMER&entityId=...',
    expected: `count == list total (${total})`,
    actual: `count=${count.json?.count} total=${total}`,
    codeRef: `${SVC}:148`,
  });

  // ---- D7: INTERNAL by default, SHARED only when asked for -----------------------------
  const def = targets[0].doc;
  rec({
    ac: 'D7',
    title: 'Visibility defaults to INTERNAL when the upload does not set it',
    status: def.visibility === 'INTERNAL' ? 'PASS' : 'FAIL',
    severity: def.visibility === 'INTERNAL' ? '' : 'high',
    steps: 'POST /api/documents with no visibility field',
    expected: 'visibility INTERNAL',
    actual: `visibility=${def.visibility}`,
    codeRef: `${SVC}:85`,
  });

  const sharedUp = await h.upload(admin, {
    entityType: 'CUSTOMER', entityId: A.customer.id, bytes: h.pdf(800),
    filename: 'shared.pdf', contentType: 'application/pdf', visibility: 'SHARED',
    description: 'shared with the customer',
  });
  ctx.sharedDoc = sharedUp.json;
  const sharedOk = sharedUp.status === 201 && sharedUp.json.visibility === 'SHARED'
    && sharedUp.json.description === 'shared with the customer';
  rec({
    ac: 'D7',
    title: 'An explicit visibility=SHARED and a description are stored',
    status: sharedOk ? 'PASS' : 'FAIL',
    severity: sharedOk ? '' : 'medium',
    steps: 'POST /api/documents with visibility=SHARED and a description',
    expected: '201, visibility SHARED, description kept',
    actual: `${sharedUp.status} visibility=${sharedUp.json?.visibility} description=${JSON.stringify(sharedUp.json?.description)}`,
    codeRef: `${SVC}:85`,
  });

  // ---- AC-C1: edit --------------------------------------------------------------------
  const editTarget = extra[0];
  const patched = await rt.api('PATCH', `/api/documents/${editTarget.id}`, {
    token: admin, body: { description: 'edited by area-c', visibility: 'SHARED' },
  });
  const patchOk = patched.status === 200 && patched.json.description === 'edited by area-c'
    && patched.json.visibility === 'SHARED';
  rec({
    ac: 'AC-C1',
    title: 'Description and visibility can be edited after upload',
    status: patchOk ? 'PASS' : 'FAIL',
    severity: patchOk ? '' : 'medium',
    steps: `PATCH /api/documents/${editTarget.id} {description, visibility:SHARED}`,
    expected: '200 with the new description and visibility',
    actual: `${patched.status} ${patched.text.slice(0, 200)}`,
    codeRef: `${SVC}:172`,
  });

  // ---- AC-C3: soft delete ---------------------------------------------------------------
  const victim = extra[2];
  const del = await rt.api('DELETE', `/api/documents/${victim.id}`, { token: admin });
  const afterList = await rt.api('GET', `/api/documents?entityType=CUSTOMER&entityId=${A.customer.id}&size=50`, { token: admin });
  const stillListed = (afterList.json?.content || []).some((d) => d.id === victim.id);
  const delOk = del.status === 204 && !stillListed;
  rec({
    ac: 'AC-C3',
    title: 'Delete returns 204 and the document drops out of the list',
    status: delOk ? 'PASS' : 'FAIL',
    severity: delOk ? '' : 'high',
    steps: `DELETE /api/documents/${victim.id}; re-list the record`,
    expected: '204, and the id is no longer in the record\'s list',
    actual: `${del.status}; still listed: ${stillListed}`,
    codeRef: `${SVC}:193`,
  });

  const gone = await h.download(admin, victim.id);
  const goneOk = gone.status === 404;
  rec({
    ac: 'AC-C3',
    title: 'A deleted document 404s on download (not 500, not the bytes)',
    status: goneOk ? 'PASS' : 'FAIL',
    severity: goneOk ? '' : 'high',
    steps: `GET /api/documents/${victim.id}/download after deleting it`,
    expected: '404 Document not found',
    actual: `${gone.status} ${gone.text.slice(0, 200)}`,
    codeRef: `${SVC}:225`,
  });

  let rowState = '';
  let rowOk = false;
  try {
    rowState = h.sql(`select deleted, deleted_by_user_id is not null, deleted_at is not null, storage_key from documents where id = ${victim.id}`);
    const [deleted, byUser, atSet] = rowState.split('|');
    rowOk = deleted === 't' && byUser === 't' && atSet === 't';
  } catch (e) {
    rowState = 'db read failed: ' + e.message;
  }
  rec({
    ac: 'AC-C3',
    title: 'Delete is soft — the row survives with who deleted it and when',
    status: rowOk ? 'PASS' : 'FAIL',
    severity: rowOk ? '' : 'high',
    steps: `psql: select deleted, deleted_by_user_id, deleted_at from documents where id = ${victim.id}`,
    expected: 'the row is still there with deleted = true, deleted_by_user_id and deleted_at set',
    actual: rowState,
    evidence: rowState,
    codeRef: `${SVC}:203`,
  });

  // ---- AC-C4: audit -------------------------------------------------------------------
  const history = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${A.customer.id}`, { token: admin });
  const actions = (history.json || []).map((e) => e.action);
  for (const [action, ac] of [['DOCUMENT_UPLOADED', 'AC-C4'], ['DOCUMENT_UPDATED', 'AC-C4'], ['DOCUMENT_DELETED', 'AC-C4']]) {
    const entry = (history.json || []).find((e) => e.action === action);
    rec({
      ac,
      title: `${action.replace('DOCUMENT_', 'Document ').toLowerCase()} is written to the audit log`,
      status: entry ? 'PASS' : 'FAIL',
      severity: entry ? '' : 'medium',
      steps: `GET /api/audit?entityType=CUSTOMER&entityId=${A.customer.id}`,
      expected: `an audit row with action ${action}`,
      actual: entry ? `found, changedBy=${entry.changedByUsername}, after/before present=${!!(entry.afterJson || entry.beforeJson)}`
        : `actions seen: ${[...new Set(actions)].join(', ')}`,
      codeRef: `${SVC}:116`,
    });
  }

  ctx.crud = { targets, extra, victim };
  ctx.customerDoc = targets[0].doc;
  ctx.invoiceDoc = targets[1].doc;
  ctx.paymentDoc = targets[2].doc;
  ctx.uploaded = [...targets.map((t) => t.doc), ...extra, sharedUp.json].filter(Boolean);
  void CTL;
};
