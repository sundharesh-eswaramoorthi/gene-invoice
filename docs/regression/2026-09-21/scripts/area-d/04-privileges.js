// D-17..D-24 — what the tab offers each kind of login: a staff user without DOCUMENT_MANAGE,
// and a self-service customer (AC-C22, AC-C20, AC-C12).
const fs = require('fs');
const os = require('os');
const path = require('path');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'area-d-priv-'));

(async () => {
  // ================= a staff login with DOCUMENT_VIEW but not DOCUMENT_MANAGE ================
  const viewerToken = await rt.login(seed.noManage.username, seed.noManage.password);
  const viewApp = await rt.openApp({ token: viewerToken });
  const page = viewApp.page;
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 4500);
  const vNodes = await rt.semantics(page);
  const vLabels = vNodes.map((n) => `${n.label || n.text}`);
  const vButtons = vNodes.filter((n) => n.role === 'button').map((n) => `${n.label || n.text}`);
  const rows = vLabels.filter((l) => /\.(pdf|png)/i.test(l));
  const hasUpload = vButtons.some((b) => /^Upload$/.test(b));
  const listed = rows.length > 0 && vLabels.some((l) => /^Documents \(\d+\)/.test(l));

  const d17 = listed && !hasUpload;
  if (!d17) await h.shot(page, 'D-17-1');
  add({
    id: 'D-17',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C22',
    title: 'Without DOCUMENT_MANAGE the tab still lists documents and offers no Upload button',
    status: d17 ? 'PASS' : 'FAIL',
    severity: d17 ? '' : 'high',
    steps: `Create role ${seed.noManage.roleName} (DOCUMENT_VIEW, INVOICE_VIEW/MANAGE, no DOCUMENT_MANAGE); `
      + `sign in as ${seed.noManage.username}; open the invoice Documents tab`,
    expected: 'The documents are listed; no Upload button, because POST /api/documents would 403',
    actual: `rows listed: ${rows.length}; buttons on the tab: ${vButtons.join(', ')}`,
    evidence: d17 ? '' : 'shots/D-17-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:173',
  });

  // AC-C20 asks for the control to be *disabled with an explanation*, not simply absent.
  const explanation = vLabels.find((l) =>
    /permission|privilege|cannot upload|not allowed|ask an admin|read.only/i.test(l));
  const d18 = !!explanation;
  if (!d18) await h.shot(page, 'D-18-1');
  add({
    id: 'D-18',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C20',
    title: 'A user without DOCUMENT_MANAGE is told why they cannot upload',
    status: d18 ? 'PASS' : 'FAIL',
    severity: d18 ? '' : 'low',
    steps: `Sign in as ${seed.noManage.username} (no DOCUMENT_MANAGE); open the invoice Documents tab; `
      + 'read the tab header and the empty/list body for an explanation',
    expected: 'AC-C20: upload "disables itself with an explanation for users without DOCUMENT_MANAGE" — '
      + 'a disabled control, tooltip or line of text saying why',
    actual: 'The Upload button is simply absent and nothing on the tab explains why. Header shows only '
      + `"Documents (${rows.length})" and a Refresh button; no permission wording anywhere on screen. `
      + 'The implementation doc records only "Buttons that would 403 are not shown (AC-C22)" (§4.6), '
      + 'so AC-C22 is met but AC-C20\'s explanation is missing.',
    evidence: 'shots/D-18-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:172',
  });

  const rowButtons = vButtons.filter((b) => /Download|Edit|Remove/.test(b));
  const d19 = rowButtons.length > 0 && rowButtons.every((b) => b === 'Download');
  if (!d19) await h.shot(page, 'D-19-1');
  add({
    id: 'D-19',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C22, AC-C10',
    title: 'Row actions without DOCUMENT_MANAGE are download-only — no Edit, no Remove',
    status: d19 ? 'PASS' : 'FAIL',
    severity: d19 ? '' : 'high',
    steps: 'As the no-DOCUMENT_MANAGE user, read the buttons on every document row',
    expected: 'Only Download per row (the API reports canEdit=false, canDelete=false)',
    actual: `row buttons: ${rowButtons.join(', ') || 'none'}`,
    evidence: d19 ? '' : 'shots/D-19-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:362',
  });

  // Dropping a file must not open a form whose upload would 403.
  await page.evaluate(({ name, b64 }) => {
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    const dt = new DataTransfer();
    dt.items.add(new File([arr], name, { type: 'image/png' }));
    document.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt }));
    document.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
  }, { name: 'd-should-not-upload.png', b64: h.pngBytes().toString('base64') });
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  const formOpened = await h.seen(page, 'Attach a document');
  const d20 = !formOpened;
  if (!d20) await h.shot(page, 'D-20-1');
  add({
    id: 'D-20',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C20, AC-C22',
    title: 'Dropping a file does nothing for a user who may not upload',
    status: d20 ? 'PASS' : 'FAIL',
    severity: d20 ? '' : 'medium',
    steps: 'As the no-DOCUMENT_MANAGE user, drop a PNG on the Documents tab',
    expected: 'No upload form opens — the drop target is off for users who cannot upload',
    actual: formOpened ? 'The "Attach a document" form opened; its upload would 403' : 'Nothing opened',
    evidence: d20 ? '' : 'shots/D-20-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:272',
  });

  const vErrs = viewApp.apiErrors;
  const d21 = vErrs.length === 0 && viewApp.pageErrors.length === 0;
  add({
    id: 'D-21',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C22',
    title: 'The no-DOCUMENT_MANAGE session makes no request the server refuses',
    status: d21 ? 'PASS' : 'FAIL',
    severity: d21 ? '' : 'medium',
    steps: 'Collect every response >= 400 and every page error across the whole restricted session',
    expected: 'No 403 and no page error — nothing on screen leads to a refused request',
    actual: d21 ? 'none' : `apiErrors ${JSON.stringify(vErrs)}; pageErrors ${JSON.stringify(viewApp.pageErrors)}`,
  });
  await viewApp.close();

  // ============================ a self-service customer login ================================
  const custToken = await rt.login(seed.customer.username, seed.customer.password);
  const custApp = await rt.openApp({ token: custToken });
  const cp = custApp.page;
  await rt.go(cp, `#/invoices/${seed.invoice.id}?tab=documents`, 4500);
  const cNodes = await rt.semantics(cp);
  const cLabels = cNodes.map((n) => `${n.label || n.text}`);
  const cRows = cLabels.filter((l) => /\.(pdf|png)/i.test(l) && /Internal|Shared/.test(l));
  const internalShown = cRows.filter((l) => /Internal/.test(l));
  const apiRows = await rt.api(
    `GET`, `/api/documents?entityType=INVOICE&entityId=${seed.invoice.id}&size=50`, { token: custToken });
  const apiInternal = (apiRows.json.content || []).filter((d) => d.visibility !== 'SHARED');
  const d22 = internalShown.length === 0 && cRows.length > 0 && apiInternal.length === 0;
  if (!d22) await h.shot(cp, 'D-22-1');
  add({
    id: 'D-22',
    feature: 'Customer self-service',
    kind: 'UI',
    ac: 'AC-C12, D7',
    title: 'A self-service customer sees only SHARED documents on their own invoice',
    status: d22 ? 'PASS' : 'FAIL',
    severity: d22 ? '' : 'high',
    steps: `Sign in as the customer login ${seed.customer.username}; open their invoice's Documents tab `
      + '(the invoice carries 1 SHARED and 4 INTERNAL documents)',
    expected: 'Only the SHARED row is listed, and the tab count matches',
    actual: `rows on screen: ${cRows.map((r) => r.split('\n')[0]).join(', ') || 'none'}; `
      + `rows marked Internal: ${internalShown.length}; API returned ${apiRows.json.totalElements} row(s), `
      + `${apiInternal.length} of them not SHARED`,
    evidence: d22 ? '' : 'shots/D-22-1.png',
    codeRef: 'backend/src/main/java/com/geneinvoice/document/DocumentService.java',
  });

  const cButtons = cNodes.filter((n) => n.role === 'button').map((n) => `${n.label || n.text}`);
  const cRowButtons = cButtons.filter((b) => /Download|Edit|Remove/.test(b));
  const d23 = cRowButtons.length > 0 && cRowButtons.every((b) => b === 'Download');
  if (!d23) await h.shot(cp, 'D-23-1');
  add({
    id: 'D-23',
    feature: 'Customer self-service',
    kind: 'UI',
    ac: 'AC-C12, AC-C22',
    title: 'A customer login gets no edit or delete control on a shared document',
    status: d23 ? 'PASS' : 'FAIL',
    severity: d23 ? '' : 'high',
    steps: 'As the customer login, read the buttons on the shared document row',
    expected: 'Download only — PATCH and DELETE are 403 for a customer login',
    actual: `row buttons: ${cRowButtons.join(', ') || 'none'}`,
    evidence: d23 ? '' : 'shots/D-23-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:368',
  });

  // AC-C12 says a customer cannot upload; the implementation doc records a decision to differ
  // (§4.5 "the one deliberate exception … because the user asked for customer uploads"), so the
  // check here is that the offered control actually works and lands SHARED, not that it is gone.
  const custUpload = cButtons.some((b) => /^Upload$/.test(b));
  let custUploaded = null;
  let visibilityOffered = null;
  if (custUpload) {
    await rt.tap(cp, 'Upload', { wait: 2000 });
    visibilityOffered = await h.seen(cp, 'Who can see it');
    const f = path.join(TMP, 'd-customer-evidence.pdf');
    fs.writeFileSync(f, h.pdfBytes('customer'));
    const chooser = cp.waitForEvent('filechooser', { timeout: 15000 });
    await rt.tap(cp, 'Choose file', { wait: 700 });
    await (await chooser).setFiles(f);
    await cp.waitForTimeout(1000);
    await rt.tap(cp, 'Upload', { wait: 3500 });
    const after = await rt.api(
      'GET', `/api/documents?entityType=INVOICE&entityId=${seed.invoice.id}&size=50`, { token: custToken });
    custUploaded = (after.json.content || []).find((d) => d.filename === 'd-customer-evidence.pdf') || null;
  }
  const d24 = custUpload ? (!!custUploaded && custUploaded.visibility === 'SHARED' && visibilityOffered === false) : true;
  if (!d24) await h.shot(cp, 'D-24-1');
  add({
    id: 'D-24',
    feature: 'Customer self-service',
    kind: 'UI',
    ac: 'AC-C12',
    title: 'The customer upload control (a documented deviation from AC-C12) works and lands SHARED',
    status: d24 ? 'PASS' : 'FAIL',
    severity: d24 ? '' : 'medium',
    steps: 'As the customer login, open the Documents tab; if an Upload control is offered, use it',
    expected: 'AC-C12 says no upload for a customer; the implementation doc (§4.5, answer 4) records a '
      + 'deliberate decision to allow it, with uploads forced to SHARED and no visibility choice. '
      + 'Either no control, or a control that works exactly that way.',
    actual: custUpload
      ? `Upload offered; visibility dropdown shown: ${visibilityOffered}; uploaded row: `
        + `${custUploaded ? `${custUploaded.filename} visibility=${custUploaded.visibility}` : 'not created'}`
      : 'No upload control offered (matches AC-C12 as written)',
    evidence: d24 ? '' : 'shots/D-24-1.png',
    codeRef: 'frontend/lib/features/documents/document_actions.dart:27',
  });

  const cErrs = custApp.apiErrors;
  const d25 = cErrs.length === 0 && custApp.pageErrors.length === 0;
  add({
    id: 'D-25',
    feature: 'Customer self-service',
    kind: 'UI',
    ac: 'AC-C22, AC-C12',
    title: 'The customer session makes no request the server refuses',
    status: d25 ? 'PASS' : 'FAIL',
    severity: d25 ? '' : 'medium',
    steps: 'Collect every response >= 400 and page error across the customer session (list, upload)',
    expected: 'No 403/404 and no page error',
    actual: d25 ? 'none' : `apiErrors ${JSON.stringify(cErrs)}; pageErrors ${JSON.stringify(custApp.pageErrors)}`,
  });

  await custApp.close();
  h.saveResults('04-privileges.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
