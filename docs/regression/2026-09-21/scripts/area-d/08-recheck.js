// Re-check after the :8084 web bundle was rebuilt from current source (2026-09-21 00:45).
// Re-runs the two cases that failed against the stale bundle (D-18, D-35) and smoke-tests the
// invoice detail screen + Documents tab on the new build (D-39).
const fs = require('fs');
const os = require('os');
const path = require('path');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'area-d-re-'));
const REBUILD = 'Re-checked 2026-09-21 00:45 against the rebuilt :8084 bundle (main.dart.js 00:45, '
  + '3 832 125 bytes; the earlier run used the 00:10 bundle).';

(async () => {
  // ---- D-35: the refused-upload message on the new build ------------------------------------
  const wide = await rt.openApp({ token: seed.admin });
  const wp = wide.page;
  await rt.go(wp, `#/invoices/${seed.emptyInvoice.id}?tab=documents`, 4500);
  await rt.tap(wp, 'Upload', { wait: 1800 });
  const bogus = path.join(TMP, 'd-not-really.pdf');
  fs.writeFileSync(bogus, h.fakePdfBytes());
  const chooser = wp.waitForEvent('filechooser', { timeout: 15000 });
  await rt.tap(wp, 'Choose file', { wait: 700 });
  await (await chooser).setFiles(bogus);
  await wp.waitForTimeout(1000);
  await rt.tap(wp, 'Upload', { wait: 3500 });
  const msg = (await h.labels(wp)).find((l) => /cannot be attached/.test(l)) || '';
  const doubled = /^File Files of this kind/.test(msg);
  const exact = msg === 'Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)';
  if (doubled || !exact) await h.shot(wp, 'D-35-1');
  add({
    id: 'D-35',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'The refused-upload message reads as a sentence, not with the field name glued in front',
    status: exact ? 'PASS' : 'FAIL',
    severity: exact ? '' : 'low',
    steps: 'Upload a plain-text file named d-not-really.pdf; read the message in the form',
    expected: '"Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)" — the server\'s '
      + 'own sentence, matching the wording the form uses for its own client-side refusal',
    actual: exact
      ? `The form shows "${msg}" — the server\'s sentence exactly, with no "File" label glued in front. `
        + 'The doubled wording seen earlier was a stale-build artefact: the fragment-detection guard '
        + '(api_client.dart _fieldMessage) is present in the rebuilt main.dart.js and absent from the old one.'
      : `The form shows "${msg}"`,
    evidence: exact ? REBUILD : `shots/D-35-1.png. ${REBUILD}`,
    codeRef: 'frontend/lib/core/api/api_client.dart:100',
  });
  await rt.tap(wp, 'Cancel', { wait: 1200 });

  // ---- D-39 (smoke): invoice detail + Documents tab on the new bundle ------------------------
  await rt.go(wp, '#/invoices');
  await rt.go(wp, `#/invoices/${seed.invoice.id}`, 4500);
  const detail = (await h.labels(wp)).join(' | ');
  const detailOk = new RegExp(seed.invoice.number).test(detail) && /Documents/.test(detail)
    && /due /.test(detail);
  await rt.tap(wp, /Documents/, { wait: 3000 });
  const tabLabels = (await h.labels(wp)).join(' | ');
  const tabOk = /Documents \(2\)/.test(tabLabels) && /d-purchase-order\.pdf/.test(tabLabels)
    && /d-signed-delivery-note\.png/.test(tabLabels);
  // Errors raised since this session began, minus the 400 the D-35 upload was meant to provoke.
  const errs = wide.apiErrors.filter((e) => !(e.method === 'POST' && e.url === '/api/documents'));
  const smokeOk = detailOk && tabOk && errs.length === 0 && wide.pageErrors.length === 0;
  const smokeShot = await h.shot(wp, 'D-39-1');
  add({
    id: 'D-39',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19, AC-C21',
    title: 'After the rebuild, invoice detail and the Documents tab still render cleanly',
    status: smokeOk ? 'PASS' : 'FAIL',
    severity: smokeOk ? '' : 'high',
    steps: `On the rebuilt bundle, open #/invoices/${seed.invoice.id}, then its Documents tab; `
      + 'collect every response >= 400 and every page error',
    expected: 'The detail screen (number, due date, tabs) and the documents list render, with no page '
      + 'error and no failed API call',
    actual: `detail screen rendered: ${detailOk}; documents list rendered: ${tabOk}; `
      + `failed API calls: ${JSON.stringify(errs)}; page errors: ${JSON.stringify(wide.pageErrors)}`,
    evidence: `shots/${path.basename(smokeShot)}. ${REBUILD}`,
    codeRef: 'frontend/lib/features/invoices/invoice_detail_screen.dart:199',
  });
  await wide.close();

  // ---- D-18: the user without DOCUMENT_MANAGE on the new build -------------------------------
  const viewerToken = await rt.login(seed.noManage.username, seed.noManage.password);
  const viewApp = await rt.openApp({ token: viewerToken });
  const page = viewApp.page;
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 4500);
  const vNodes = await rt.semantics(page);
  const vLabels = vNodes.map((n) => `${n.label || n.text}`);
  const vButtons = vNodes.filter((n) => n.role === 'button').map((n) => `${n.label || n.text}`);
  const explanation = vLabels.find((l) =>
    /permission|privilege|cannot upload|not allowed|ask an admin|read.only/i.test(l));
  const uploadButton = vNodes.find((n) => n.role === 'button' && /^Upload$/.test(`${n.label || n.text}`));
  const d18 = !!explanation;
  await h.shot(page, 'D-18-1');
  add({
    id: 'D-18',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C20',
    title: 'A user without DOCUMENT_MANAGE is told why they cannot upload',
    status: d18 ? 'PASS' : 'FAIL',
    severity: d18 ? '' : 'low',
    steps: `Sign in as ${seed.noManage.username} (DOCUMENT_VIEW, no DOCUMENT_MANAGE); open the invoice `
      + 'Documents tab; read the tab header and body for an explanation',
    expected: 'AC-C20: upload "disables itself with an explanation for users without DOCUMENT_MANAGE" — '
      + 'a disabled control, tooltip or line of text saying why',
    actual: d18
      ? `The tab explains it: "${explanation}"`
      : 'Unchanged by the rebuild: the Upload button is simply absent and nothing on the tab explains why. '
        + `Buttons on the tab: ${vButtons.join(', ')}; disabled Upload control present: ${!!uploadButton}; `
        + 'no permission wording anywhere on screen. The implementation doc records only "Buttons that would '
        + '403 are not shown (AC-C22)" (§4.6), so AC-C22 is met but AC-C20\'s explanation is still missing.',
    evidence: `shots/D-18-1.png. ${REBUILD}`,
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:172',
  });
  await viewApp.close();

  h.saveResults('08-recheck.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
