// Area C helpers — raw multipart building, file fixtures, and a small case recorder.
// Multipart bodies are built by hand (not FormData) so a filename can carry raw bytes —
// newlines, null bytes, path separators — exactly as an attacker would send them.
const crypto = require('crypto');
const rt = require('../lib.js');

const API = rt.API;

// ---- raw multipart --------------------------------------------------------------------

/**
 * parts: [{name, value}] for a field, [{name, filename, contentType, bytes}] for a file.
 * `filename` is written into the Content-Disposition header verbatim — no escaping.
 */
function multipart(parts) {
  const boundary = '----rtC' + crypto.randomBytes(12).toString('hex');
  const chunks = [];
  for (const p of parts) {
    let head = `--${boundary}\r\nContent-Disposition: form-data; name="${p.name}"`;
    if (p.filename !== undefined) head += `; filename="${p.filename}"`;
    head += '\r\n';
    if (p.contentType) head += `Content-Type: ${p.contentType}\r\n`;
    head += '\r\n';
    chunks.push(Buffer.from(head, 'binary'));
    chunks.push(p.bytes !== undefined ? Buffer.from(p.bytes) : Buffer.from(String(p.value), 'utf8'));
    chunks.push(Buffer.from('\r\n', 'binary'));
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`, 'binary'));
  return { body: Buffer.concat(chunks), contentType: `multipart/form-data; boundary=${boundary}` };
}

/**
 * POST /api/documents. Returns {status, json, text, headers} or {status:0, error} when the
 * connection itself failed (which AC-C6 says must not happen).
 */
async function upload(token, {
  entityType, entityId, bytes, filename = 'file.pdf', contentType = 'application/pdf',
  description, visibility, omitFile = false, extraFields = [],
} = {}) {
  const parts = [];
  if (!omitFile) parts.push({ name: 'file', filename, contentType, bytes: bytes ?? Buffer.alloc(0) });
  if (entityType !== undefined) parts.push({ name: 'entityType', value: entityType });
  if (entityId !== undefined) parts.push({ name: 'entityId', value: entityId });
  if (description !== undefined) parts.push({ name: 'description', value: description });
  if (visibility !== undefined) parts.push({ name: 'visibility', value: visibility });
  parts.push(...extraFields);
  const { body, contentType: ct } = multipart(parts);
  try {
    const res = await fetch(API + '/api/documents', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': ct },
      body,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
    return { status: res.status, json, text, headers: Object.fromEntries(res.headers) };
  } catch (e) {
    return { status: 0, json: null, text: '', headers: {}, error: String(e && (e.cause || e.message || e)) };
  }
}

/** GET /api/documents/{id}/download as raw bytes. */
async function download(token, id) {
  try {
    const res = await fetch(`${API}/api/documents/${id}/download`, {
      headers: token ? { Authorization: 'Bearer ' + token } : {},
    });
    const buf = Buffer.from(await res.arrayBuffer());
    return { status: res.status, bytes: buf, text: buf.toString('utf8'), headers: Object.fromEntries(res.headers) };
  } catch (e) {
    return { status: 0, bytes: Buffer.alloc(0), text: '', headers: {}, error: String(e && (e.cause || e.message || e)) };
  }
}

// ---- file fixtures --------------------------------------------------------------------

/** A file whose first bytes say PDF, padded to `size` bytes. */
function pdf(size = 1024) {
  const head = Buffer.from('%PDF-1.7\n% area-c regression fixture\n', 'binary');
  const tail = Buffer.from('\n%%EOF\n', 'binary');
  const pad = Math.max(0, size - head.length - tail.length);
  return Buffer.concat([head, crypto.randomBytes(pad), tail]);
}

/** A real 1x1 PNG. */
function png() {
  return Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64');
}

/** A JPEG: SOI + APP0 JFIF + payload + EOI. */
function jpeg(size = 512) {
  const head = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00,
    0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00]);
  const tail = Buffer.from([0xFF, 0xD9]);
  const pad = Math.max(0, size - head.length - tail.length);
  return Buffer.concat([head, crypto.randomBytes(pad), tail]);
}

// -- a minimal STORED zip writer, so DOCX / XLSX / plain-zip fixtures need no dependency --

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0 ^ -1;
  for (let i = 0; i < buf.length; i++) c = (c >>> 8) ^ CRC_TABLE[(c ^ buf[i]) & 0xFF];
  return (c ^ -1) >>> 0;
}

/** entries: [{name, data}] — written uncompressed, which every ZIP reader accepts. */
function zip(entries) {
  const locals = [];
  const central = [];
  let offset = 0;
  for (const e of entries) {
    const name = Buffer.from(e.name, 'utf8');
    const data = Buffer.from(e.data);
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0, 6);
    lh.writeUInt16LE(0, 8); lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(name.length, 26); lh.writeUInt16LE(0, 28);
    locals.push(lh, name, data);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0, 8); ch.writeUInt16LE(0, 10); ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(name.length, 28); ch.writeUInt16LE(0, 30); ch.writeUInt16LE(0, 32);
    ch.writeUInt16LE(0, 34); ch.writeUInt16LE(0, 36); ch.writeUInt32LE(0, 38);
    ch.writeUInt32LE(offset, 42);
    central.push(ch, name);
    offset += 30 + name.length + data.length;
  }
  const dir = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(0, 4); eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(dir.length, 12); eocd.writeUInt32LE(offset, 16); eocd.writeUInt16LE(0, 20);
  return Buffer.concat([Buffer.concat(locals), dir, eocd]);
}

const CONTENT_TYPES_XML =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
  + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
  + '<Default Extension="xml" ContentType="application/xml"/></Types>';

function docx() {
  return zip([
    { name: '[Content_Types].xml', data: CONTENT_TYPES_XML },
    { name: '_rels/.rels', data: '<?xml version="1.0"?><Relationships/>' },
    { name: 'word/document.xml', data: '<?xml version="1.0"?><document>area-c</document>' },
  ]);
}

function xlsx() {
  return zip([
    { name: '[Content_Types].xml', data: CONTENT_TYPES_XML },
    { name: '_rels/.rels', data: '<?xml version="1.0"?><Relationships/>' },
    { name: 'xl/workbook.xml', data: '<?xml version="1.0"?><workbook/>' },
  ]);
}

/** A plain ZIP — no Open XML manifest, so not an Office file. */
function plainZip() {
  return zip([{ name: 'readme.txt', data: 'area-c plain zip' }]);
}

const HTML_PAYLOAD = Buffer.from(
  '<!doctype html><html><head><title>innocent</title></head><body>'
  + '<script>fetch("/api/customers",{headers:{Authorization:"Bearer "+localStorage.token}})</script>'
  + '</body></html>', 'utf8');

const SVG_PAYLOAD = Buffer.from(
  '<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" onload="alert(document.domain)">'
  + '<script>alert(1)</script></svg>', 'utf8');

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

// ---- case recorder --------------------------------------------------------------------

function recorder(cases) {
  let n = 0;
  return function record(c) {
    n += 1;
    const id = c.id || `C-${String(n).padStart(2, '0')}`;
    const full = {
      id,
      feature: c.feature || 'Documents',
      kind: c.kind || 'API',
      ac: c.ac || '',
      title: c.title,
      status: c.status,
      severity: c.severity || '',
      steps: c.steps || '',
      expected: c.expected || '',
      actual: c.actual || '',
      evidence: c.evidence || '',
      codeRef: c.codeRef || '',
    };
    cases.push(full);
    const mark = full.status === 'PASS' ? 'ok  ' : full.status === 'FAIL' ? 'FAIL' : full.status;
    console.log(`${mark} ${id} ${full.title}`);
    if (full.status !== 'PASS') console.log(`      actual: ${full.actual}`);
    return full;
  };
}

/** Runs psql inside the test database container. */
function sql(query) {
  const { execFileSync } = require('child_process');
  return execFileSync('docker', ['exec', 'gene-invoice-db', 'psql', '-U', 'geneinvoice',
    '-d', 'geneinvoice_rt', '-At', '-F', '|', '-c', query], { encoding: 'utf8' }).trim();
}

module.exports = {
  API, multipart, upload, download,
  pdf, png, jpeg, zip, docx, xlsx, plainZip, HTML_PAYLOAD, SVG_PAYLOAD,
  sha256, recorder, sql,
  STORAGE_ROOT: '/Users/srinivasans/gene-invoice-documents-rt',
  MAX_BYTES: 10 * 1024 * 1024,
};
