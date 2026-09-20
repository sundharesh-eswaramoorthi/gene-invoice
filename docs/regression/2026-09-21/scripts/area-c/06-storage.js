// AC-C13, AC-C16, AC-C18 — how the bytes leave the server, and where they live.
const fs = require('fs');
const path = require('path');
const h = require('./helpers.js');

module.exports = async function storage(ctx, rec) {
  const { admin } = ctx;

  // ---- AC-C13: the download response is inert -------------------------------------------
  const doc = ctx.invoiceDoc;
  const d = await h.download(admin, doc.id);
  const hd = d.headers;
  const checks = {
    'Content-Disposition attachment': /^attachment\s*;/i.test(hd['content-disposition'] || ''),
    'filename is quoted and escaped': /filename="[^"\r\n]*"/.test(hd['content-disposition'] || ''),
    'X-Content-Type-Options nosniff': (hd['x-content-type-options'] || '').toLowerCase() === 'nosniff',
    'Content-Security-Policy present': /default-src\s+'none'/.test(hd['content-security-policy'] || ''),
    'non-renderable Content-Type': (hd['content-type'] || '').startsWith('application/octet-stream'),
    'Cache-Control private, no-store': /no-store/.test(hd['cache-control'] || ''),
  };
  const failed = Object.entries(checks).filter(([, v]) => !v).map(([k]) => k);
  rec({
    ac: 'AC-C13',
    title: 'Download headers make the file undisplayable in the app\'s origin',
    status: failed.length === 0 ? 'PASS' : 'FAIL',
    severity: failed.length === 0 ? '' : 'high',
    steps: `GET /api/documents/${doc.id}/download and read the response headers`,
    expected: 'Content-Disposition: attachment with a quoted filename, X-Content-Type-Options: nosniff, a CSP of default-src \'none\', Content-Type application/octet-stream, Cache-Control no-store',
    actual: failed.length === 0
      ? `all present: ${JSON.stringify({ cd: hd['content-disposition'], ct: hd['content-type'], nosniff: hd['x-content-type-options'], csp: hd['content-security-policy'], cc: hd['cache-control'] })}`
      : `missing/wrong: ${failed.join(', ')} — headers: ${JSON.stringify(hd)}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentController.java:70',
  });

  // The stored content type is never echoed as the response type, for any kind of file.
  const perType = [];
  for (const dd of [ctx.customerDoc, ctx.invoiceDoc, ctx.paymentDoc, ctx.polyglotDoc].filter(Boolean)) {
    const r = await h.download(admin, dd.id);
    perType.push(`${dd.contentType} -> ${r.headers['content-type']}`);
  }
  const inertOk = perType.every((s) => s.endsWith('-> application/octet-stream'));
  rec({
    ac: 'AC-C13',
    title: 'The stored content type is never echoed back as the response Content-Type',
    status: inertOk ? 'PASS' : 'FAIL',
    severity: inertOk ? '' : 'high',
    steps: 'Download a PDF, a PNG, a JPEG and the %PDF- polyglot; compare the stored contentType with the response Content-Type',
    expected: 'every download is application/octet-stream whatever the file is',
    actual: perType.join('; '),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentController.java:75',
  });

  // ---- AC-C16: no bytes in the database --------------------------------------------------
  let cols = '';
  let sizes = '';
  let noBytesOk = false;
  try {
    cols = h.sql("select column_name || ':' || data_type from information_schema.columns where table_name = 'documents' order by column_name");
    const binary = cols.split('\n').filter((c) => /bytea|blob|oid|lo$/i.test(c));
    // The widest row in the table, against the widest file stored.
    sizes = h.sql('select coalesce(max(pg_column_size(d.*)),0), coalesce(max(size_bytes),0) from documents d');
    const [maxRow, maxFile] = sizes.split('|').map(Number);
    noBytesOk = binary.length === 0 && maxRow < 4096 && maxFile > 1024 * 1024;
    sizes = `widest documents row = ${maxRow} B; largest stored file = ${maxFile} B`;
  } catch (e) {
    cols = 'db read failed: ' + e.message;
  }
  rec({
    ac: 'AC-C16',
    title: 'No file bytes are stored in the database',
    status: noBytesOk ? 'PASS' : 'FAIL',
    severity: noBytesOk ? '' : 'high',
    steps: "psql: information_schema.columns for 'documents'; max(pg_column_size(d.*)) against max(size_bytes)",
    expected: 'no bytea/blob/oid column, and the widest row stays a few hundred bytes while a 10 MB file is stored',
    actual: `${sizes}; columns: ${cols.replace(/\n/g, ', ').slice(0, 400)}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/Document.java:73',
  });

  // ---- AC-C18: a row never points at bytes that are not there -----------------------------
  const ids = [...new Set(ctx.uploaded.filter(Boolean).map((x) => x.id))];
  let rows = '';
  const missing = [];
  const wrongSize = [];
  const wrongSum = [];
  try {
    rows = h.sql(`select id, storage_key, size_bytes, checksum, deleted from documents where id in (${ids.join(',')})`);
    for (const line of rows.split('\n').filter(Boolean)) {
      const [id, key, size, checksum] = line.split('|');
      const file = path.join(h.STORAGE_ROOT, key);
      if (!fs.existsSync(file)) { missing.push(`${id} -> ${key}`); continue; }
      const bytes = fs.readFileSync(file);
      if (bytes.length !== Number(size)) wrongSize.push(`${id}: row ${size} B, file ${bytes.length} B`);
      if (h.sha256(bytes) !== checksum) wrongSum.push(`${id}: checksum mismatch`);
    }
  } catch (e) {
    rows = 'db read failed: ' + e.message;
  }
  const intactOk = rows && !rows.startsWith('db read failed')
    && missing.length === 0 && wrongSize.length === 0 && wrongSum.length === 0;
  rec({
    ac: 'AC-C18',
    title: 'Every document row points at bytes that are really there, at the recorded size and checksum',
    status: intactOk ? 'PASS' : 'FAIL',
    severity: intactOk ? '' : 'high',
    steps: `psql: id, storage_key, size_bytes, checksum for the ${ids.length} area-c documents; stat and SHA-256 each file under ${h.STORAGE_ROOT}`,
    expected: 'every storage_key resolves to a file whose length equals size_bytes and whose SHA-256 equals checksum (deleted rows included — the bytes are retained by design)',
    actual: intactOk
      ? `${rows.split('\n').filter(Boolean).length} row(s) checked, all present and matching`
      : `missing: ${missing.join(', ') || 'none'}; wrong size: ${wrongSize.join(', ') || 'none'}; wrong checksum: ${wrongSum.join(', ') || 'none'}; ${rows.slice(0, 200)}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentService.java:120',
  });

  // A refused upload must leave neither a row nor a stray file behind.
  const before = h.sql("select count(*) from documents where filename = 'rejected-leftover.pdf'");
  const rejected = await h.upload(admin, {
    entityType: 'INVOICE', entityId: ctx.A.invoice.id, bytes: h.HTML_PAYLOAD,
    filename: 'rejected-leftover.pdf', contentType: 'application/pdf',
  });
  const after = h.sql("select count(*) from documents where filename = 'rejected-leftover.pdf'");
  const noLeftoverOk = rejected.status === 400 && before === after && after === '0';
  rec({
    ac: 'AC-C18',
    title: 'A refused upload leaves no half-created document row',
    status: noLeftoverOk ? 'PASS' : 'FAIL',
    severity: noLeftoverOk ? '' : 'high',
    steps: "Upload an HTML payload named rejected-leftover.pdf (refused by the allow-list); count rows with that filename before and after",
    expected: '400 and no row written',
    actual: `upload=${rejected.status}; rows before=${before} after=${after}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentRules.java:66',
  });

  // ---- AC-C15/D6: bytes live under the configured root ------------------------------------
  const walk = (dir) => {
    const out = [];
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
    for (const e of entries) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) out.push(...walk(p)); else out.push(p);
    }
    return out;
  };
  const all = walk(h.STORAGE_ROOT);
  const laidOut = all.filter((f) =>
    /\/(customer|invoice|payment)\/\d+\/[0-9a-f-]{36}\.(pdf|png|jpg|docx|xlsx|bin)$/.test(f));
  const stray = all.filter((f) => /\.part$/.test(f));
  const rootOk = laidOut.length >= ids.length && stray.length === 0
    && all.every((f) => path.resolve(f).startsWith(path.resolve(h.STORAGE_ROOT) + path.sep));
  rec({
    ac: 'AC-C15',
    title: 'Bytes land under the configured DOCUMENT_ROOT, and no .part temp file is left behind',
    status: rootOk ? 'PASS' : 'FAIL',
    severity: rootOk ? '' : 'medium',
    steps: `Walk ${h.STORAGE_ROOT} after the run`,
    expected: 'every stored file sits under the configured root as <noun>/<entityId>/<uuid>.<ext>, with no leftover .upload-*.part files',
    actual: `${all.length} file(s) under the root, ${laidOut.length} matching the generated layout, ${stray.length} leftover .part file(s)`,
    evidence: laidOut.slice(0, 3).map((f) => f.replace(h.STORAGE_ROOT, '<root>')).join(' | '),
    codeRef: 'backend/src/main/java/com/geneinvoice/document/LocalDocumentStorage.java:49',
  });
};
