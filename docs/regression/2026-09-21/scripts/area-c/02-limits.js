// AC-C6 / AC-C9 — the size limit is enforced server-side and answered with the app's standard
// validation error, plus the rest of the request validation.
const h = require('./helpers.js');

const RULES = 'backend/src/main/java/com/geneinvoice/document/DocumentRules.java';
const ADVICE = 'backend/src/main/java/com/geneinvoice/document/DocumentUploadAdvice.java';

/** The app's standard validation error: 400, "Validation Failed", fieldErrors keyed by field. */
function isValidationError(r, field = 'file') {
  return r.status === 400 && r.json && r.json.error === 'Validation Failed'
    && r.json.fieldErrors && typeof r.json.fieldErrors[field] === 'string';
}

function describe(r) {
  if (r.status === 0) return `CONNECTION DROPPED: ${r.error}`;
  return `${r.status} ${JSON.stringify(r.json || r.text).slice(0, 300)}`;
}

module.exports = async function limits(ctx, rec) {
  const { admin, A } = ctx;
  const MAX = h.MAX_BYTES; // app.documents.max-size-bytes, DOCUMENT_MAX_BYTES default 10485760

  const under = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(MAX - 1024),
    filename: 'just-under.pdf', contentType: 'application/pdf',
  });
  const underOk = under.status === 201 && under.json.sizeBytes === MAX - 1024;
  if (under.json?.id) ctx.uploaded.push(under.json);
  rec({
    ac: 'AC-C6',
    title: `A file just under the limit (${MAX - 1024} B) is accepted`,
    status: underOk ? 'PASS' : 'FAIL',
    severity: underOk ? '' : 'high',
    steps: `POST /api/documents with a ${MAX - 1024} byte PDF`,
    expected: `201 with sizeBytes ${MAX - 1024}`,
    actual: describe(under),
    codeRef: `${RULES}:60`,
  });

  const exact = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(MAX),
    filename: 'exactly-at.pdf', contentType: 'application/pdf',
  });
  const exactOk = exact.status === 201 && exact.json.sizeBytes === MAX;
  if (exact.json?.id) ctx.uploaded.push(exact.json);
  rec({
    ac: 'AC-C6',
    title: `A file exactly at the limit (${MAX} B) is accepted`,
    status: exactOk ? 'PASS' : 'FAIL',
    severity: exactOk ? '' : 'medium',
    steps: `POST /api/documents with a ${MAX} byte PDF`,
    expected: `201 with sizeBytes ${MAX} — the limit is inclusive`,
    actual: describe(exact),
    codeRef: `${RULES}:62`,
  });

  const over1 = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(MAX + 1),
    filename: 'one-over.pdf', contentType: 'application/pdf',
  });
  const over1Ok = isValidationError(over1) && /larger than/i.test(over1.json.fieldErrors.file);
  rec({
    ac: 'AC-C6',
    title: 'One byte over the limit is refused with the app\'s validation error',
    status: over1Ok ? 'PASS' : 'FAIL',
    severity: over1Ok ? '' : 'high',
    steps: `POST /api/documents with a ${MAX + 1} byte PDF`,
    expected: '400 "Validation Failed" with fieldErrors.file naming the limit — never a 500 or a dropped connection',
    actual: describe(over1),
    codeRef: `${ADVICE}:36`,
  });

  const over12 = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(12 * 1024 * 1024),
    filename: 'twelve-mb.pdf', contentType: 'application/pdf',
  });
  const over12Ok = isValidationError(over12);
  rec({
    ac: 'AC-C6',
    title: 'Well over the limit (12 MB) is still the app\'s validation error, not a container 500',
    status: over12Ok ? 'PASS' : 'FAIL',
    severity: over12Ok ? '' : 'high',
    steps: 'POST /api/documents with a 12 MB PDF (over max-file-size 10 MB)',
    expected: '400 "Validation Failed" with fieldErrors.file',
    actual: describe(over12),
    codeRef: `${ADVICE}:36`,
  });

  const over20 = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(20 * 1024 * 1024),
    filename: 'twenty-mb.pdf', contentType: 'application/pdf',
  });
  const over20Ok = isValidationError(over20);
  rec({
    ac: 'AC-C9',
    title: 'Past the multipart max-request-size (20 MB) the client still gets the app\'s error, not a reset',
    status: over20Ok ? 'PASS' : 'FAIL',
    severity: over20Ok ? '' : 'medium',
    steps: 'POST /api/documents with a 20 MB PDF (over max-request-size 12 MB)',
    expected: '400 "Validation Failed" with fieldErrors.file; the connection is not dropped',
    actual: describe(over20),
    codeRef: 'backend/src/main/resources/application.yml:19',
  });

  const empty = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: Buffer.alloc(0),
    filename: 'empty.pdf', contentType: 'application/pdf',
  });
  const emptyOk = isValidationError(empty) && /choose a file/i.test(empty.json.fieldErrors.file);
  rec({
    ac: 'AC-C6',
    title: 'An empty file is refused with a specific message',
    status: emptyOk ? 'PASS' : 'FAIL',
    severity: emptyOk ? '' : 'low',
    steps: 'POST /api/documents with a zero-byte part',
    expected: '400 with fieldErrors.file = "Choose a file"',
    actual: describe(empty),
    codeRef: `${RULES}:57`,
  });

  const noFile = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, omitFile: true,
  });
  const noFileOk = isValidationError(noFile);
  rec({
    ac: 'AC-C6',
    title: 'A request with no file part is a validation error, not a 500',
    status: noFileOk ? 'PASS' : 'FAIL',
    severity: noFileOk ? '' : 'medium',
    steps: 'POST /api/documents with entityType and entityId but no file part',
    expected: '400 with fieldErrors.file',
    actual: describe(noFile),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentController.java:37',
  });

  const longDesc = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(500),
    filename: 'desc.pdf', contentType: 'application/pdf', description: 'x'.repeat(501),
  });
  const longDescOk = isValidationError(longDesc, 'description');
  rec({
    ac: 'AC-C6',
    title: 'A description past the 500-character field limit is refused',
    status: longDescOk ? 'PASS' : 'FAIL',
    severity: longDescOk ? '' : 'low',
    steps: 'POST /api/documents with a 501 character description',
    expected: '400 with fieldErrors.description',
    actual: describe(longDesc),
    codeRef: `${RULES}:92`,
  });

  const badVis = await h.upload(admin, {
    entityType: 'INVOICE', entityId: A.invoice.id, bytes: h.pdf(500),
    filename: 'vis.pdf', contentType: 'application/pdf', visibility: 'PUBLIC',
  });
  const badVisOk = badVis.status === 400;
  rec({
    ac: 'D7',
    title: 'An unknown visibility value is refused (no silent fallback to SHARED)',
    status: badVisOk ? 'PASS' : 'FAIL',
    severity: badVisOk ? '' : 'high',
    steps: 'POST /api/documents with visibility=PUBLIC',
    expected: '400 naming the allowed values',
    actual: describe(badVis),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentVisibility.java:17',
  });

  const badType = await h.upload(admin, {
    entityType: 'DISPUTE', entityId: A.invoice.id, bytes: h.pdf(500),
    filename: 'x.pdf', contentType: 'application/pdf',
  });
  const badTypeOk = badType.status === 400;
  rec({
    ac: 'D5',
    title: 'An entityType outside CUSTOMER/INVOICE/PAYMENT is refused',
    status: badTypeOk ? 'PASS' : 'FAIL',
    severity: badTypeOk ? '' : 'medium',
    steps: 'POST /api/documents with entityType=DISPUTE',
    expected: '400 listing the three supported types',
    actual: describe(badType),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentEntityType.java:29',
  });

  const ghost = await h.upload(admin, {
    entityType: 'INVOICE', entityId: 999999999, bytes: h.pdf(500),
    filename: 'x.pdf', contentType: 'application/pdf',
  });
  const ghostOk = ghost.status === 404;
  rec({
    ac: 'AC-C10',
    title: 'Uploading to a record that does not exist is a 404',
    status: ghostOk ? 'PASS' : 'FAIL',
    severity: ghostOk ? '' : 'low',
    steps: 'POST /api/documents entityType=INVOICE entityId=999999999',
    expected: '404 Invoice not found',
    actual: describe(ghost),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentTargets.java:104',
  });
};
