// Area D — Documents tab in the UI. Shared bits for the area-d scripts.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const RUN = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1/docs/regression/2026-09-21';
const REPORT = path.join(RUN, 'report');
const SEED_FILE = path.join(__dirname, '.seed.json');
const RESULTS = path.join(__dirname, '.results');

/** A tiny but genuinely sniffable PDF. */
function pdfBytes(marker = 'area-d') {
  return Buffer.from(
    `%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n% ${marker}\n%%EOF\n`,
    'latin1',
  );
}

/** A 1x1 PNG. */
function pngBytes() {
  return Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    'base64',
  );
}

/** Plain text under a .pdf name — passes the browser-side check, refused by the server sniffer. */
function fakePdfBytes() {
  return Buffer.from('this is not a pdf at all, just words\n', 'utf8');
}

/** Uploads a file through the API (multipart), as the UI would. */
async function apiUpload(token, { entityType, entityId, name, bytes, type, description, visibility }) {
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: type || 'application/octet-stream' }), name);
  form.append('entityType', entityType);
  form.append('entityId', String(entityId));
  if (description) form.append('description', description);
  if (visibility) form.append('visibility', visibility);
  const res = await fetch(rt.API + '/api/documents', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token },
    body: form,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text };
}

function readSeed() {
  return JSON.parse(fs.readFileSync(SEED_FILE, 'utf8'));
}

function writeSeed(seed) {
  fs.writeFileSync(SEED_FILE, JSON.stringify(seed, null, 2));
}

/** Records one case; scripts call this and then saveResults(). */
function saveResults(file, cases) {
  fs.mkdirSync(RESULTS, { recursive: true });
  fs.writeFileSync(path.join(RESULTS, file), JSON.stringify(cases, null, 2));
  for (const c of cases) console.log(`${c.status.padEnd(10)} ${c.id} ${c.title}`);
}

const shot = (page, id) => rt.shot(page, REPORT, id);

/** Labels of everything on screen, for quick assertions. */
async function labels(page) {
  return (await rt.semantics(page)).map((n) => `${n.label || n.text}`);
}

/** True when any node's label/text contains `needle`. */
async function seen(page, needle) {
  const all = await labels(page);
  const re = needle instanceof RegExp ? needle : new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
  return all.some((l) => re.test(l));
}

module.exports = {
  rt, RUN, REPORT, RESULTS, SEED_FILE,
  pdfBytes, pngBytes, fakePdfBytes, apiUpload, readSeed, writeSeed, saveResults, shot, labels, seen,
};
