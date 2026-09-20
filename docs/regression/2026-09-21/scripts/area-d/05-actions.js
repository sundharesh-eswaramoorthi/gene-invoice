// D-26..D-32 — what the row buttons do: download, delete (with its confirmation), edit — plus
// the tab's loading and error states (AC-C1, AC-C3, AC-C21, US-C4, US-C6).
const fs = require('fs');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });

const list = (token, type, id) =>
  rt.api('GET', `/api/documents?entityType=${type}&entityId=${id}&size=50`, { token });

(async () => {
  const app = await rt.openApp({ token: seed.admin });
  const { page } = app;

  // --- D-26: download hands the browser the file, named as it was uploaded --------------------
  await rt.go(page, `#/payments/${seed.payment.id}?tab=documents`, 4500);
  const dlPromise = page.waitForEvent('download', { timeout: 20000 }).catch(() => null);
  await rt.tap(page, 'Download', { wait: 2500 });
  const download = await dlPromise;
  let saved = null;
  let bytesMatch = null;
  if (download) {
    const p = await download.path();
    if (p) {
      const got = fs.readFileSync(p);
      bytesMatch = got.equals(h.pngBytes());
    }
    saved = download.suggestedFilename();
  }
  const snack26 = await h.seen(page, 'Downloaded d-cheque-image.png');
  const d26 = saved === 'd-cheque-image.png' && bytesMatch === true;
  if (!d26) await h.shot(page, 'D-26-1');
  add({
    id: 'D-26',
    feature: 'Download',
    kind: 'UI',
    ac: 'AC-C1, AC-C13',
    title: 'Download from the tab hands the browser the file under its original name',
    status: d26 ? 'PASS' : 'FAIL',
    severity: d26 ? '' : 'high',
    steps: `Open #/payments/${seed.payment.id}?tab=documents; press Download on d-cheque-image.png`,
    expected: 'The browser is handed a download named d-cheque-image.png with the bytes that were uploaded',
    actual: download
      ? `download fired, suggestedFilename=${saved}, bytes identical to the upload: ${bytesMatch}; `
        + `confirmation shown: ${snack26}`
      : 'No browser download event fired within 20s',
    evidence: d26 ? '' : 'shots/D-26-1.png',
    codeRef: 'frontend/lib/features/documents/document_files_web.dart:52',
  });

  // --- D-27/D-28: delete asks first, then says it is gone ------------------------------------
  const doomed = await h.apiUpload(seed.admin, {
    entityType: 'INVOICE', entityId: seed.emptyInvoice.id,
    name: 'd-delete-me.pdf', bytes: h.pdfBytes('delete'), type: 'application/pdf',
    description: 'to be removed', visibility: 'INTERNAL',
  });
  await rt.go(page, '#/invoices');
  await rt.go(page, `#/invoices/${seed.emptyInvoice.id}?tab=documents`, 4500);
  await rt.tap(page, 'Remove', { wait: 2000 });
  const confirmLabels = (await h.labels(page)).join(' | ');
  const asked = /Remove this document\?/.test(confirmLabels) && /d-delete-me\.pdf/.test(confirmLabels)
    && /Keep it/.test(confirmLabels);
  if (asked) await rt.tap(page, 'Keep it', { wait: 1800 });
  const stillThere = (await list(seed.admin, 'INVOICE', seed.emptyInvoice.id)).json.totalElements;
  const d27 = asked && stillThere === 1;
  if (!d27) await h.shot(page, 'D-27-1');
  add({
    id: 'D-27',
    feature: 'Delete',
    kind: 'UI',
    ac: 'AC-C3, US-C5',
    title: 'Remove asks for confirmation first, and backing out keeps the document',
    status: d27 ? 'PASS' : 'FAIL',
    severity: d27 ? '' : 'high',
    steps: 'Attach d-delete-me.pdf to the second invoice; open its Documents tab; press Remove; then "Keep it"',
    expected: 'A confirmation naming the file appears; declining leaves the document in place',
    actual: asked
      ? `Asked "Remove this document?" naming the file; after "Keep it" the record still has ${stillThere} document(s)`
      : `No confirmation found. On screen: ${confirmLabels.slice(0, 400)}`,
    evidence: d27 ? '' : 'shots/D-27-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:118',
  });

  await rt.tap(page, 'Remove', { wait: 1800 });
  await rt.tap(page, 'Remove', { wait: 2500 });
  const removedSnack = await h.seen(page, 'Document removed');
  const goneFromList = !(await h.seen(page, 'd-delete-me.pdf'));
  const emptyAgain = (await list(seed.admin, 'INVOICE', seed.emptyInvoice.id)).json.totalElements;
  const stillDownloadable = await rt.api('GET', `/api/documents/${doomed.json.id}/download`, { token: seed.admin });
  const d28 = removedSnack && goneFromList && emptyAgain === 0 && stillDownloadable.status >= 400;
  if (!d28) await h.shot(page, 'D-28-1');
  add({
    id: 'D-28',
    feature: 'Delete',
    kind: 'UI',
    ac: 'AC-C3, US-C5',
    title: 'Confirming the removal says so, drops the row and stops the download',
    status: d28 ? 'PASS' : 'FAIL',
    severity: d28 ? '' : 'high',
    steps: 'Press Remove again and confirm with "Remove"',
    expected: '"Document removed", the row disappears, the list is empty and the file no longer downloads',
    actual: `confirmation message shown: ${removedSnack}; row gone: ${goneFromList}; `
      + `documents left on the record: ${emptyAgain}; GET /download after delete -> ${stillDownloadable.status}`,
    evidence: d28 ? '' : 'shots/D-28-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:143',
  });

  // --- D-29: editing description and visibility sticks ----------------------------------------
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${seed.customer.id}?tab=documents`, 4500);
  await rt.tap(page, 'Edit', { wait: 2000 });
  const editOpen = await h.seen(page, 'Edit • d-trade-licence.pdf');
  // Flutter gives an unfocused text field no accessibility node, so the description box is found
  // by its position: it sits one row above the visibility dropdown, which does have one.
  const visibilityNode = await rt.find(page, /Who can see it/);
  await rt.clickAt(page, visibilityNode.x, visibilityNode.y - 53, 900);
  const newDescription = `Trade licence — checked ${new Date().toISOString().slice(11, 19)}`;
  await rt.typeText(page, newDescription, { clear: true });
  // Visibility: SHARED today, move it to Internal only.
  await rt.tap(page, /Who can see it/, { wait: 1200 });
  await rt.tap(page, 'Internal only', { wait: 1200 });
  await rt.tap(page, 'Save', { wait: 2500 });
  const updatedSnack = await h.seen(page, 'Document updated');
  const rowText = (await h.labels(page)).find((l) => /d-trade-licence\.pdf/.test(l)) || '';
  const stored = ((await list(seed.admin, 'CUSTOMER', seed.customer.id)).json.content || [])
    .find((d) => d.filename === 'd-trade-licence.pdf');
  const d29 = !!stored && stored.description === newDescription && stored.visibility === 'INTERNAL'
    && /Internal/.test(rowText) && rowText.includes(newDescription);
  if (!d29) await h.shot(page, 'D-29-1');
  add({
    id: 'D-29',
    feature: 'Edit document',
    kind: 'UI',
    ac: 'AC-C4, US-C6, D7',
    title: 'Editing a document\'s description and visibility saves and shows on the row',
    status: d29 ? 'PASS' : 'FAIL',
    severity: d29 ? '' : 'medium',
    steps: `Customer ${seed.customer.id} > Documents > Edit on d-trade-licence.pdf; replace the description; `
      + 'set "Who can see it" to Internal only; Save',
    expected: `description = "${newDescription}", visibility = INTERNAL, both reflected on the row`,
    actual: `dialog opened: ${editOpen}; confirmation: ${updatedSnack}; API now has description=`
      + `${JSON.stringify(stored?.description)} visibility=${stored?.visibility}; row reads: `
      + `${rowText.replace(/\n/g, ' / ')}`,
    evidence: d29 ? '' : 'shots/D-29-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:413',
  });
  // Put it back the way the seed left it, so a re-run starts from the same place.
  if (stored) {
    await rt.api('PATCH', `/api/documents/${stored.id}`,
      { token: seed.admin, body: { description: 'Trade licence', visibility: 'SHARED' } });
  }

  // --- D-30: the loading state ----------------------------------------------------------------
  await page.route('**/api/documents?**', async (route) => {
    await new Promise((r) => setTimeout(r, 6000));
    await route.continue();
  });
  await rt.go(page, '#/invoices');
  await page.evaluate((h2) => { location.hash = h2; }, `#/invoices/${seed.invoice.id}?tab=documents`);
  await page.waitForTimeout(2200);
  await rt.enableSemantics(page);
  const loadingLabels = await h.labels(page);
  const loadingShot = await h.shot(page, 'D-30-1');
  const headerPlain = loadingLabels.some((l) => l === 'Documents');
  const noRowsYet = !loadingLabels.some((l) => /\.pdf|\.png/.test(l) && /Internal|Shared/.test(l));
  const d30 = headerPlain && noRowsYet;
  add({
    id: 'D-30',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'While the list is loading the tab shows a loading state, not an empty list',
    status: d30 ? 'PASS' : 'FAIL',
    severity: d30 ? '' : 'medium',
    steps: 'Delay GET /api/documents by 6s (route interception); open the invoice Documents tab; look after 2s',
    expected: 'A plain "Documents" header with a progress bar, and no "no documents" empty state',
    actual: `header without a count shown: ${headerPlain}; rows shown yet: ${!noRowsYet}; `
      + `on screen: ${loadingLabels.filter((l) => /Document|No documents/.test(l)).join(' | ') || '(nothing named Documents)'}`,
    evidence: `shots/${loadingShot.split('/').pop()}`,
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:186',
  });
  await page.waitForTimeout(6000);
  await page.unroute('**/api/documents?**');

  // --- D-31: the error state, and the retry that gets out of it -------------------------------
  let failNext = true;
  await page.route('**/api/documents?**', async (route) => {
    if (!failNext) return route.continue();
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({
        timestamp: new Date().toISOString(), status: 503, error: 'Service Unavailable',
        message: 'Document storage is not available', path: '/api/documents',
      }),
    });
  });
  await rt.go(page, '#/invoices');
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 4500);
  const errLabels = (await h.labels(page)).join(' | ');
  const errShown = /Documents unavailable/.test(errLabels);
  const specific = /Document storage is not available/.test(errLabels);
  const retryOffered = /Try again/.test(errLabels);
  const noBlankList = !/No documents on this invoice yet/.test(errLabels);
  if (!(errShown && specific && retryOffered && noBlankList)) await h.shot(page, 'D-31-1');
  add({
    id: 'D-31',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'A failed list shows the server\'s own message with a Try again, not a blank list',
    status: errShown && specific && retryOffered && noBlankList ? 'PASS' : 'FAIL',
    severity: errShown && specific && retryOffered && noBlankList ? '' : 'medium',
    steps: 'Make GET /api/documents answer 503 "Document storage is not available"; open the Documents tab',
    expected: '"Documents unavailable: <server message>" with a Try again button, and no empty state',
    actual: errLabels.split(' | ').filter((l) => /Documents|Try again|No documents/.test(l)).join(' | ')
      || errLabels.slice(0, 300),
    evidence: errShown && specific && retryOffered && noBlankList ? '' : 'shots/D-31-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:192',
  });

  failNext = false;
  await rt.tap(page, 'Try again', { wait: 3500 });
  const recovered = await h.seen(page, /Documents \(\d+\)/);
  const rowsBack = await h.seen(page, 'd-purchase-order.pdf');
  const d32 = recovered && rowsBack;
  if (!d32) await h.shot(page, 'D-32-1');
  add({
    id: 'D-32',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'Try again after a failed list reloads the documents',
    status: d32 ? 'PASS' : 'FAIL',
    severity: d32 ? '' : 'medium',
    steps: 'With the list failing, press Try again once the server is answering normally again',
    expected: 'The list loads and the rows come back',
    actual: `header with a count: ${recovered}; a known row on screen: ${rowsBack}`,
    evidence: d32 ? '' : 'shots/D-32-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:500',
  });
  await page.unroute('**/api/documents?**');

  await app.close();
  h.saveResults('05-actions.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
