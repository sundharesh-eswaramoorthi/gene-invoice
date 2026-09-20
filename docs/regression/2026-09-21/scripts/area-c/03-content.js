// AC-C7 — the allow-list is applied to what the bytes ARE, never to the extension or the
// Content-Type the client declared.
const h = require('./helpers.js');

const SNIFF = 'backend/src/main/java/com/geneinvoice/document/ContentSniffer.java';
const RULES = 'backend/src/main/java/com/geneinvoice/document/DocumentRules.java';

const DISALLOWED = /cannot be attached/i;

function rejected(r) {
  return r.status === 400 && r.json?.fieldErrors?.file && DISALLOWED.test(r.json.fieldErrors.file);
}

function describe(r) {
  if (r.status === 0) return `CONNECTION DROPPED: ${r.error}`;
  return `${r.status} ${JSON.stringify(r.json?.fieldErrors || r.json || r.text).slice(0, 300)}`;
}

module.exports = async function content(ctx, rec) {
  const { admin, A } = ctx;
  const to = { entityType: 'INVOICE', entityId: A.invoice.id };

  // THE critical case: an HTML/script payload wearing a PDF name and a PDF Content-Type.
  const trojan = await h.upload(admin, {
    ...to, bytes: h.HTML_PAYLOAD, filename: 'innocent.pdf', contentType: 'application/pdf',
  });
  const trojanOk = rejected(trojan);
  if (trojan.json?.id) ctx.uploaded.push(trojan.json);
  rec({
    ac: 'AC-C7',
    title: 'HTML/script bytes named innocent.pdf and declared application/pdf are REJECTED',
    status: trojanOk ? 'PASS' : 'FAIL',
    severity: trojanOk ? '' : 'high',
    steps: 'POST /api/documents, part filename="innocent.pdf", Content-Type: application/pdf, body = <html><script>fetch(/api/customers…)</script></html>',
    expected: '400 "Files of this kind cannot be attached" — the bytes decide, not the name or the declared type',
    actual: describe(trojan) + (trojan.status === 201 ? ` STORED as contentType=${trojan.json.contentType}` : ''),
    codeRef: `${RULES}:72`,
  });

  // A real PNG under an executable name and a lying Content-Type: content decides, so it is fine.
  const renamedPng = await h.upload(admin, {
    ...to, bytes: h.png(), filename: 'payload.exe', contentType: 'application/x-msdownload',
  });
  const renamedOk = renamedPng.status === 201 && renamedPng.json.contentType === 'image/png';
  if (renamedPng.json?.id) ctx.uploaded.push(renamedPng.json);
  rec({
    ac: 'AC-C7',
    title: 'A real PNG named payload.exe is accepted and recorded as image/png (extension ignored)',
    status: renamedOk ? 'PASS' : 'FAIL',
    severity: renamedOk ? '' : 'medium',
    steps: 'POST /api/documents, part filename="payload.exe", Content-Type: application/x-msdownload, body = a real 1x1 PNG',
    expected: '201 with contentType image/png — the stored type comes from the bytes',
    actual: describe(renamedPng) + (renamedPng.status === 201 ? ` contentType=${renamedPng.json.contentType}` : ''),
    codeRef: `${SNIFF}:40`,
  });

  const zipAsPdf = await h.upload(admin, {
    ...to, bytes: h.plainZip(), filename: 'report.pdf', contentType: 'application/pdf',
  });
  const zipOk = rejected(zipAsPdf);
  if (zipAsPdf.json?.id) ctx.uploaded.push(zipAsPdf.json);
  rec({
    ac: 'AC-C7',
    title: 'A plain ZIP named report.pdf and declared application/pdf is REJECTED',
    status: zipOk ? 'PASS' : 'FAIL',
    severity: zipOk ? '' : 'high',
    steps: 'POST /api/documents, part filename="report.pdf", Content-Type: application/pdf, body = a ZIP with one readme.txt',
    expected: '400 — a ZIP without the Open XML manifest is not an allowed type',
    actual: describe(zipAsPdf) + (zipAsPdf.status === 201 ? ` STORED as contentType=${zipAsPdf.json.contentType}` : ''),
    codeRef: `${SNIFF}:58`,
  });

  const svg = await h.upload(admin, {
    ...to, bytes: h.SVG_PAYLOAD, filename: 'logo.svg', contentType: 'image/svg+xml',
  });
  const svgOk = rejected(svg);
  if (svg.json?.id) ctx.uploaded.push(svg.json);
  rec({
    ac: 'AC-C7',
    title: 'A script-bearing SVG is REJECTED (image/svg+xml is not on the allow-list)',
    status: svgOk ? 'PASS' : 'FAIL',
    severity: svgOk ? '' : 'high',
    steps: 'POST /api/documents with an SVG carrying onload= and <script>, declared image/svg+xml',
    expected: '400 "Files of this kind cannot be attached"',
    actual: describe(svg),
    codeRef: `${RULES}:72`,
  });

  const office = [];
  for (const [what, bytes, expect] of [
    ['DOCX', h.docx(), 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['XLSX', h.xlsx(), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
  ]) {
    const r = await h.upload(admin, {
      ...to, bytes, filename: `book.${what.toLowerCase()}`, contentType: 'application/octet-stream',
    });
    if (r.json?.id) ctx.uploaded.push(r.json);
    office.push(`${what}: ${r.status} ${r.json?.contentType || r.text.slice(0, 80)}`);
    if (r.status !== 201 || r.json.contentType !== expect) office.push('MISMATCH');
  }
  const officeOk = !office.includes('MISMATCH');
  rec({
    ac: 'AC-C7',
    title: 'Office files are recognised from the ZIP parts even when declared application/octet-stream',
    status: officeOk ? 'PASS' : 'FAIL',
    severity: officeOk ? '' : 'medium',
    steps: 'POST a DOCX and an XLSX, each declared application/octet-stream',
    expected: '201 with the Open XML word/spreadsheet content types',
    actual: office.join('; '),
    codeRef: `${SNIFF}:58`,
  });

  const text = await h.upload(admin, {
    ...to, bytes: Buffer.from('plain text, nothing more\n'.repeat(20)),
    filename: 'notes.pdf', contentType: 'application/pdf',
  });
  const textOk = rejected(text);
  if (text.json?.id) ctx.uploaded.push(text.json);
  rec({
    ac: 'AC-C7',
    title: 'Plain text named notes.pdf is REJECTED',
    status: textOk ? 'PASS' : 'FAIL',
    severity: textOk ? '' : 'medium',
    steps: 'POST /api/documents, filename="notes.pdf", Content-Type: application/pdf, body = plain text',
    expected: '400 "Files of this kind cannot be attached"',
    actual: describe(text),
    codeRef: `${RULES}:72`,
  });

  // A polyglot: real PDF magic, HTML after it. Magic-byte sniffing accepts this by design; the
  // download headers (AC-C13) are what has to stop a browser making anything of it.
  const polyglot = Buffer.concat([Buffer.from('%PDF-1.7\n'), h.HTML_PAYLOAD]);
  const poly = await h.upload(admin, {
    ...to, bytes: polyglot, filename: 'polyglot.pdf', contentType: 'application/pdf',
  });
  if (poly.json?.id) { ctx.uploaded.push(poly.json); ctx.polyglotDoc = poly.json; }
  const dl = poly.json?.id ? await h.download(admin, poly.json.id) : { headers: {}, status: 0 };
  const inert = dl.headers['content-type'] === 'application/octet-stream'
    && dl.headers['x-content-type-options'] === 'nosniff'
    && /default-src 'none'/.test(dl.headers['content-security-policy'] || '')
    && /^attachment;/.test(dl.headers['content-disposition'] || '');
  rec({
    ac: 'AC-C7',
    title: 'A %PDF- polyglot with an HTML/script body is accepted by the sniffer, and served inert',
    status: inert ? 'PASS' : 'FAIL',
    severity: inert ? '' : 'high',
    steps: 'POST a file beginning "%PDF-1.7" and continuing as HTML with a <script>; then download it',
    expected: 'magic-byte sniffing accepts it as a PDF (by design), and the download is attachment + nosniff + CSP default-src \'none\' + application/octet-stream so no script can run in the app\'s origin',
    actual: `upload ${poly.status} contentType=${poly.json?.contentType}; download headers: type=${dl.headers['content-type']} nosniff=${dl.headers['x-content-type-options']} csp=${dl.headers['content-security-policy']} disposition=${(dl.headers['content-disposition'] || '').slice(0, 60)}`,
    evidence: 'Sniffing is by magic bytes only (ContentSniffer.detect), so any file whose first 5 bytes are "%PDF-" passes the allow-list whatever follows. AC-C13 is the control that matters here.',
    codeRef: `${SNIFF}:40`,
  });
};
