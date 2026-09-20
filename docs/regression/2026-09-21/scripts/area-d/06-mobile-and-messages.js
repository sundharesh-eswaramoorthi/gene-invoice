// D-33..D-36 — the tab at phone width, and the wording of the two failures a user actually
// meets: a refused upload and a download of something that is no longer there (AC-C21).
const fs = require('fs');
const os = require('os');
const path = require('path');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'area-d-mob-'));
const WIDTH = 400;

/** Every node whose box leaves the viewport sideways. */
const overflowing = (nodes) => nodes
  .filter((n) => n.w > 0 && (n.x - n.w / 2 < -1 || n.x + n.w / 2 > WIDTH + 1))
  .map((n) => `${`${n.label || n.text}`.split('\n')[0].slice(0, 40)} [x${n.x} w${n.w}]`);

(async () => {
  // ============================== phone width ================================================
  const app = await rt.openApp({ token: seed.admin, width: WIDTH, height: 850 });
  const { page } = app;
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 5000);
  // On a narrow screen the detail page is one scroll: bring the list into view.
  await page.mouse.move(200, 500);
  for (let i = 0; i < 8; i++) await page.mouse.wheel(0, 300);
  await page.waitForTimeout(1200);
  await rt.enableSemantics(page);
  const tabNodes = await rt.semantics(page);
  const rows = tabNodes.filter((n) => n.role === 'group' && /\.(pdf|png)/i.test(`${n.label || n.text}`));
  const tabOver = overflowing(tabNodes);
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  const tabShot = await h.shot(page, 'D-33-1');
  const d33 = rows.length > 0 && tabOver.length === 0 && scrollWidth <= WIDTH;
  add({
    id: 'D-33',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19, AC-C21',
    title: 'At 400px the Documents tab and its rows fit without sideways overflow',
    status: d33 ? 'PASS' : 'FAIL',
    severity: d33 ? '' : 'medium',
    steps: `Open the app at 400x850; go to #/invoices/${seed.invoice.id}?tab=documents; scroll to the list`,
    expected: 'Every row, its chips and its buttons sit inside 400px; the page does not scroll sideways',
    actual: `${rows.length} row(s) rendered, widest ${Math.max(0, ...rows.map((r) => r.w))}px; `
      + `document.scrollWidth = ${scrollWidth}; nodes outside the viewport: ${tabOver.join(' | ') || 'none'}`,
    evidence: 'shots/D-33-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:311',
  });
  if (!d33) console.log('D-33 overflow:', tabOver, tabShot);

  await rt.tap(page, 'Upload', { wait: 2200 });
  const upNodes = await rt.semantics(page);
  const upOver = overflowing(upNodes);
  const upShot = await h.shot(page, 'D-34-1');
  const upComplete = ['Choose file', 'Cancel', 'Upload', 'Who can see it']
    .filter((want) => !upNodes.some((n) => `${n.label || n.text}`.includes(want)));
  await rt.tap(page, 'Cancel', { wait: 1500 });
  // The edit form is the same shape, on the widest row.
  await rt.tap(page, 'Edit', { wait: 2200 });
  const editNodes = await rt.semantics(page);
  const editOver = overflowing(editNodes);
  await h.shot(page, 'D-34-2');
  await rt.tap(page, 'Cancel', { wait: 1200 });
  const d34 = upOver.length === 0 && editOver.length === 0 && upComplete.length === 0;
  add({
    id: 'D-34',
    feature: 'Upload dialog',
    kind: 'UI',
    ac: 'AC-C20, AC-C21',
    title: 'At 400px the upload and edit forms fit, with every control reachable',
    status: d34 ? 'PASS' : 'FAIL',
    severity: d34 ? '' : 'medium',
    steps: 'At 400x850 open Upload on the Documents tab, then Edit on a row',
    expected: 'Both dialogs stay inside the viewport (their 460px content box shrinks to fit) and keep '
      + 'their chooser, description, visibility and action buttons',
    actual: `upload form: ${upOver.length === 0 ? 'inside the viewport' : `overflows: ${upOver.join(' | ')}`}`
      + `, missing controls: ${upComplete.join(', ') || 'none'}; `
      + `edit form: ${editOver.length === 0 ? 'inside the viewport' : `overflows: ${editOver.join(' | ')}`}`,
    evidence: 'shots/D-34-1.png',
    codeRef: 'frontend/lib/features/documents/upload_document_dialog.dart:133',
  });
  await app.close();

  // ======================= the wording of a refused upload ====================================
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
  if (doubled) await h.shot(wp, 'D-35-1');
  add({
    id: 'D-35',
    feature: 'Upload',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'The refused-upload message reads as a sentence, not with the field name glued in front',
    status: doubled ? 'FAIL' : 'PASS',
    severity: doubled ? 'low' : '',
    steps: 'Upload a plain-text file named d-not-really.pdf; read the message in the form',
    expected: '"Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)" — the server\'s '
      + 'own sentence, matching the wording the form uses for its own client-side refusal',
    actual: doubled
      ? `The form shows "${msg}" — the field label "File" is prepended to a message that is already a `
        + 'sentence, so it reads "File Files of this kind…". The server body is '
        + '{"fieldErrors":{"file":"Files of this kind cannot be attached (…)"}}. NOTE: the working-tree '
        + 'source already guards against exactly this (api_client.dart _fieldMessage, whose comment names '
        + 'this very string), and that guard is absent from the served main.dart.js (built 00:06, while '
        + 'api_client.dart was last edited 00:12), so the build on :8084 predates the fix — re-check after '
        + 'the next web build before treating it as open.'
      : `The form shows "${msg}"`,
    evidence: doubled ? 'shots/D-35-1.png' : '',
    codeRef: 'frontend/lib/core/api/api_client.dart:60',
  });
  await rt.tap(wp, 'Cancel', { wait: 1200 });

  // =============== downloading a row that is no longer there ==================================
  const doomed = await h.apiUpload(seed.admin, {
    entityType: 'INVOICE', entityId: seed.emptyInvoice.id,
    name: 'd-vanishing.pdf', bytes: h.pdfBytes('vanish'), type: 'application/pdf',
    description: 'removed behind the user\'s back', visibility: 'INTERNAL',
  });
  await rt.go(wp, '#/invoices');
  await rt.go(wp, `#/invoices/${seed.emptyInvoice.id}?tab=documents`, 4500);
  await rt.api('DELETE', `/api/documents/${doomed.json.id}`, { token: seed.admin });
  const before = wide.apiErrors.length;
  await rt.tap(wp, 'Download', { wait: 3000 });
  // The snackbar is a single-line node at the foot of the page — not one of the multi-line rows.
  const snack = (await rt.semantics(wp))
    .filter((n) => !`${n.label || n.text}`.includes('\n') && n.y > 700 && !n.role)
    .map((n) => `${n.label || n.text}`)
    .find((l) => /not found|unavailable|error|status|failed|network|invalid/i.test(l)) || '';
  const got404 = wide.apiErrors.slice(before).some((e) => e.status === 404);
  const specific = /Document not found/i.test(snack);
  if (!specific) await h.shot(wp, 'D-36-1');
  add({
    id: 'D-36',
    feature: 'Download',
    kind: 'UI',
    ac: 'AC-C21',
    title: 'Downloading a row that has since been removed reports the server\'s own reason',
    status: specific ? 'PASS' : 'FAIL',
    severity: specific ? '' : 'low',
    steps: 'Open a Documents tab, delete the listed document through the API, then press Download on the stale row',
    expected: 'A message the user can act on — the server answers 404 {"message":"Document not found"}',
    actual: `server answered 404: ${got404}; the app showed: ${JSON.stringify(snack) || 'nothing'}`
      + (specific ? '' : '. The download asks for bytes, so the JSON error body arrives as bytes and is not '
        + 'decoded before the message is taken. NOTE: the working-tree source adds exactly that decoding '
        + '(api_client.dart _decoded), so the build running on :8084 appears to predate the fix.'),
    evidence: specific ? '' : 'shots/D-36-1.png',
    codeRef: 'frontend/lib/core/api/api_client.dart:60',
  });

  await wide.close();
  h.saveResults('06-mobile-and-messages.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
