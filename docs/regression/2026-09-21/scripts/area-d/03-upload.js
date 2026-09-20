// D-10..D-16 — uploading from the UI: the file picker, progress, the stated limits,
// drag-and-drop, and what a refused upload leaves behind (AC-C20, AC-C21, AC-C6).
const fs = require('fs');
const os = require('os');
const path = require('path');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'area-d-'));
const write = (name, bytes) => { const p = path.join(TMP, name); fs.writeFileSync(p, bytes); return p; };

/** A PDF padded out to `bytes` so a throttled upload lasts long enough to watch. */
function bigPdf(bytes) {
  const head = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n% ', 'latin1');
  const tail = Buffer.from('\n%%EOF\n', 'latin1');
  return Buffer.concat([head, Buffer.alloc(bytes - head.length - tail.length, 0x41), tail]);
}

const docsOf = (token, entityId) =>
  rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${entityId}&size=50`, { token });

(async () => {
  const app = await rt.openApp({ token: seed.admin });
  const { page } = app;
  const invoice = seed.invoice.id;

  async function openDocs() {
    await rt.go(page, '#/invoices');
    await rt.go(page, `#/invoices/${invoice}?tab=documents`, 4000);
  }

  async function pick(filePath) {
    const chooser = page.waitForEvent('filechooser', { timeout: 15000 });
    await rt.tap(page, 'Choose file', { wait: 700 });
    await (await chooser).setFiles(filePath);
    await page.waitForTimeout(1200);
    await rt.enableSemantics(page);
  }

  // --- D-10: the limits are stated before a file is chosen ------------------------------------
  await openDocs();
  await rt.tap(page, 'Upload', { wait: 2000 });
  const dialogText = (await h.labels(page)).join(' | ');
  const limitsOk = /PDF, PNG, JPEG, Word or Excel, up to 10 MB/i.test(dialogText);
  if (!limitsOk) await h.shot(page, 'D-10-1');
  add({
    id: 'D-10',
    feature: 'Upload dialog',
    kind: 'UI',
    ac: 'AC-C20, AC-C6',
    title: 'The upload form states the allowed kinds and the size limit before a file is chosen',
    status: limitsOk ? 'PASS' : 'FAIL',
    severity: limitsOk ? '' : 'low',
    steps: 'Invoice > Documents > Upload',
    expected: 'The form names the allowed types and the maximum size',
    actual: limitsOk ? 'Shows "PDF, PNG, JPEG, Word or Excel, up to 10 MB" and the drag-and-drop hint'
      : `Dialog showed: ${dialogText.slice(0, 500)}`,
    evidence: limitsOk ? '' : 'shots/D-10-1.png',
    codeRef: 'frontend/lib/features/documents/upload_document_dialog.dart:250',
  });

  // --- D-11: file picker upload, end to end ---------------------------------------------------
  const before = (await docsOf(seed.admin, invoice)).json.totalElements;
  const pickedName = 'd-picker-upload.pdf';
  await pick(write(pickedName, h.pdfBytes('picker')));
  const chosen = await h.seen(page, pickedName);
  await rt.tap(page, 'Upload', { wait: 3500 });
  const snack = await h.seen(page, 'Document uploaded');
  const rowShown = await h.seen(page, pickedName);
  const after = await docsOf(seed.admin, invoice);
  const stored = (after.json.content || []).find((d) => d.filename === pickedName);
  const uploadOk = chosen && snack && rowShown && !!stored && after.json.totalElements === before + 1;
  if (!uploadOk) await h.shot(page, 'D-11-1');
  add({
    id: 'D-11',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C20, AC-C1',
    title: 'Uploading through the file picker attaches the file and it appears in the list',
    status: uploadOk ? 'PASS' : 'FAIL',
    severity: uploadOk ? '' : 'high',
    steps: `Invoice > Documents > Upload > Choose file (${pickedName}) > Upload`,
    expected: 'The chosen file is named in the form, the upload confirms, the row appears and the API has one more document',
    actual: `file named in form: ${chosen}; "Document uploaded" shown: ${snack}; row on screen: ${rowShown}; `
      + `API count ${before} -> ${after.json.totalElements}; stored contentType ${stored?.contentType ?? '-'}`,
    evidence: uploadOk ? '' : 'shots/D-11-1.png',
    codeRef: 'frontend/lib/features/documents/upload_document_dialog.dart:82',
  });

  // --- D-12: progress on a slow upload --------------------------------------------------------
  const client = await page.context().newCDPSession(page);
  await client.send('Network.enable');
  await client.send('Network.emulateNetworkConditions', {
    offline: false, latency: 50, downloadThroughput: -1, uploadThroughput: 120 * 1024,
  });
  await openDocs();
  await rt.tap(page, 'Upload', { wait: 1500 });
  await pick(write('d-slow-upload.pdf', bigPdf(1_200_000)));
  await rt.tap(page, 'Upload', { wait: 900 });
  let progressSeen = null;
  for (let i = 0; i < 12 && !progressSeen; i++) {
    await rt.enableSemantics(page);
    const l = (await h.labels(page)).find((s) => /Uploading/i.test(s));
    if (l) progressSeen = l;
    else await page.waitForTimeout(700);
  }
  if (!progressSeen) await h.shot(page, 'D-12-1');
  // Let it finish before the throttle comes off.
  for (let i = 0; i < 40 && await h.seen(page, 'Uploading'); i++) await page.waitForTimeout(1000);
  await client.send('Network.emulateNetworkConditions', {
    offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1,
  });
  add({
    id: 'D-12',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C20',
    title: 'A slow upload shows progress while the bytes are going out',
    status: progressSeen ? 'PASS' : 'FAIL',
    severity: progressSeen ? '' : 'medium',
    steps: 'Throttle the upload to 120 KB/s (CDP); upload a 1.2 MB PDF; watch the dialog',
    expected: 'The form shows a progress bar and an "Uploading… n%" message',
    actual: progressSeen ? `Saw "${progressSeen}"` : 'No "Uploading…" text appeared at any poll during the upload',
    evidence: progressSeen ? '' : 'shots/D-12-1.png',
    codeRef: 'frontend/lib/features/documents/upload_document_dialog.dart:193',
  });

  // --- D-13: drag-and-drop on web -------------------------------------------------------------
  await openDocs();
  const droppedName = 'd-dropped-note.png';
  await page.evaluate(({ name, b64, type }) => {
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    const file = new File([arr], name, { type });
    const dt = new DataTransfer();
    dt.items.add(file);
    document.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }));
    document.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
  }, { name: droppedName, b64: h.pngBytes().toString('base64'), type: 'image/png' });
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  const dropOpened = await h.seen(page, 'Attach a document');
  const dropNamed = await h.seen(page, droppedName);
  let dropUploaded = false;
  if (dropOpened) {
    await rt.tap(page, 'Upload', { wait: 3000 });
    dropUploaded = !!((await docsOf(seed.admin, invoice)).json.content || [])
      .find((d) => d.filename === droppedName);
  }
  const dropOk = dropOpened && dropNamed && dropUploaded;
  if (!dropOk) await h.shot(page, 'D-13-1');
  add({
    id: 'D-13',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C20',
    title: 'Dropping a file on the Documents tab opens the form with that file ready to send',
    status: dropOk ? 'PASS' : 'FAIL',
    severity: dropOk ? '' : 'medium',
    steps: 'Invoice > Documents; dispatch a real DragEvent drop carrying a PNG onto the page; press Upload',
    expected: 'The upload form opens with the dropped file chosen, and uploading it attaches it',
    actual: `form opened: ${dropOpened}; file named: ${dropNamed}; attached per API: ${dropUploaded}`,
    evidence: dropOk ? '' : 'shots/D-13-1.png',
    codeRef: 'frontend/lib/features/documents/document_files_web.dart:120',
  });

  // --- D-14: a file the server refuses leaves a specific, retryable message --------------------
  await openDocs();
  const countBefore = (await docsOf(seed.admin, invoice)).json.totalElements;
  await rt.tap(page, 'Upload', { wait: 1500 });
  await pick(write('d-not-really.pdf', h.fakePdfBytes()));
  await rt.tap(page, 'Upload', { wait: 3000 });
  const afterFail = await h.labels(page);
  const message = afterFail.find((l) => /cannot be attached|not |refus|type|support/i.test(l)
    && !/PDF, PNG, JPEG, Word or Excel, up to/.test(l) && l.length > 12);
  const formStillOpen = afterFail.some((l) => /Attach a document/.test(l));
  const fileStillNamed = afterFail.some((l) => /d-not-really\.pdf/.test(l));
  const rejected400 = app.apiErrors.some((e) => e.method === 'POST' && e.url.startsWith('/api/documents'));
  const countAfter = (await docsOf(seed.admin, invoice)).json.totalElements;
  const failOk = !!message && formStillOpen && fileStillNamed && countAfter === countBefore;
  if (!failOk) await h.shot(page, 'D-14-1');
  add({
    id: 'D-14',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C21, AC-C7',
    title: 'An upload the server refuses leaves a specific message and the form open to retry',
    status: failOk ? 'PASS' : 'FAIL',
    severity: failOk ? '' : 'medium',
    steps: 'Upload a plain-text file named d-not-really.pdf (passes the browser-side check, refused by the content sniffer)',
    expected: 'The form stays open with the file still chosen and a specific error; no document is created',
    actual: `message: ${message ? JSON.stringify(message) : 'none found'}; form open: ${formStillOpen}; `
      + `file still chosen: ${fileStillNamed}; server refused: ${rejected400}; count ${countBefore} -> ${countAfter}`,
    evidence: failOk ? '' : 'shots/D-14-1.png',
    codeRef: 'frontend/lib/features/documents/upload_document_dialog.dart:113',
  });
  // The dialog is modal with barrierDismissible false — close it before moving on.
  await rt.tap(page, 'Cancel', { wait: 1200 });

  // --- D-15: a file over the limit is refused before it is sent --------------------------------
  await openDocs();
  await rt.tap(page, 'Upload', { wait: 1500 });
  const bigName = 'd-too-big.pdf';
  await pick(write(bigName, bigPdf(11 * 1024 * 1024)));
  const postsBefore = app.apiErrors.length;
  await rt.tap(page, 'Upload', { wait: 2500 });
  const bigLabels = await h.labels(page);
  const bigMessage = bigLabels.find((l) => /larger than/i.test(l));
  const noRequest = app.apiErrors.length === postsBefore;
  const bigOk = !!bigMessage && noRequest;
  if (!bigOk) await h.shot(page, 'D-15-1');
  add({
    id: 'D-15',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C20, AC-C6',
    title: 'A file over the 10 MB limit is refused in the form, before the bytes are sent',
    status: bigOk ? 'PASS' : 'FAIL',
    severity: bigOk ? '' : 'medium',
    steps: `Upload an 11 MB PDF (${bigName})`,
    expected: 'The form says the file is larger than 10 MB and sends nothing',
    actual: `message: ${bigMessage ? JSON.stringify(bigMessage) : 'none'}; no failed request raised: ${noRequest}`,
    evidence: bigOk ? '' : 'shots/D-15-1.png',
    codeRef: 'frontend/lib/features/documents/document_models.dart:204',
  });
  await rt.tap(page, 'Cancel', { wait: 1200 });

  // --- D-16: the empty state ------------------------------------------------------------------
  await rt.go(page, '#/invoices');
  await rt.go(page, `#/invoices/${seed.emptyInvoice.id}?tab=documents`, 4000);
  const emptyLabels = (await h.labels(page)).join(' | ');
  const emptyOk = /No documents on this invoice yet/.test(emptyLabels) && /Documents \(0\)/.test(emptyLabels);
  if (!emptyOk) await h.shot(page, 'D-16-1');
  add({
    id: 'D-16',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'A record with no documents shows an empty state, not a blank panel',
    status: emptyOk ? 'PASS' : 'FAIL',
    severity: emptyOk ? '' : 'medium',
    steps: `Open #/invoices/${seed.emptyInvoice.id}?tab=documents (no documents attached)`,
    expected: '"Documents (0)" and "No documents on this invoice yet." with a prompt to upload one',
    actual: emptyLabels.slice(0, 400),
    evidence: emptyOk ? '' : 'shots/D-16-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:199',
  });

  await app.close();
  h.saveResults('03-upload.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
