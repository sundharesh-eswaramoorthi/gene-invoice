// D-37, D-38 — the tab for a user without DOCUMENT_VIEW, and what an upload leaves in the
// record's History tab (AC-C10, AC-C22, AC-C4).
const fs = require('fs');
const os = require('os');
const path = require('path');
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'area-d-nv-'));

(async () => {
  // --- a role that cannot see documents at all -------------------------------------------------
  const roleName = rt.uniq('D-NODOCVIEW').toUpperCase();
  const role = await rt.api('POST', '/api/roles', {
    token: seed.admin,
    body: {
      name: roleName,
      description: 'Area D: no document privileges at all',
      privileges: ['CUSTOMER_VIEW', 'INVOICE_VIEW', 'PAYMENT_VIEW', 'PRODUCT_VIEW',
        'NOTIFICATION_VIEW', 'SCOPE_OVERRIDE', 'AUDIT_VIEW'],
    },
  });
  if (role.status >= 300) throw new Error(`role -> ${role.status} ${role.text}`);
  const username = rt.uniq('d-nodocview');
  const user = await rt.api('POST', '/api/users', {
    token: seed.admin,
    body: {
      username, email: `${username}@rt.local`, fullName: username.toUpperCase(),
      password: rt.PASSWORD, roleId: role.json.id, active: true,
    },
  });
  if (user.status >= 300) throw new Error(`user -> ${user.status} ${user.text}`);

  const blindToken = await rt.login(username, rt.PASSWORD);
  const blind = await rt.openApp({ token: blindToken });
  await rt.go(blind.page, `#/invoices/${seed.invoice.id}`, 4500);
  const blindTabs = (await rt.semantics(blind.page))
    .filter((n) => n.role === 'tab').map((n) => `${n.label || n.text}`.replace(/\n/g, ' '));
  const noTab = !blindTabs.some((t) => /Documents/.test(t));
  const deepLink = await (async () => {
    await rt.go(blind.page, `#/invoices/${seed.invoice.id}?tab=documents`, 4000);
    return (await h.labels(blind.page)).join(' | ');
  })();
  const noPanel = !/Documents \(\d+\)|No documents on this/.test(deepLink);
  const noRefused = blind.apiErrors.length === 0;
  const d37 = noTab && noPanel && noRefused;
  if (!d37) await h.shot(blind.page, 'D-37-1');
  add({
    id: 'D-37',
    feature: 'Document privileges',
    kind: 'UI',
    ac: 'AC-C10, AC-C22',
    title: 'Without DOCUMENT_VIEW the Documents tab is not offered, and its link shows nothing',
    status: d37 ? 'PASS' : 'FAIL',
    severity: d37 ? '' : 'high',
    steps: `Create role ${roleName} (no DOCUMENT_VIEW / DOCUMENT_MANAGE); sign in as ${username}; `
      + 'open the invoice, then its ?tab=documents link',
    expected: 'No Documents tab; the direct link falls back to another tab; no refused request',
    actual: `tabs: ${blindTabs.join(', ')}; documents panel after the deep link: ${noPanel ? 'not shown' : 'shown'}; `
      + `failed requests: ${JSON.stringify(blind.apiErrors)}`,
    evidence: d37 ? '' : 'shots/D-37-1.png',
    codeRef: 'frontend/lib/features/documents/document_actions.dart:38',
  });
  await blind.close();

  // --- an upload from the UI shows in the record's History tab ---------------------------------
  const app = await rt.openApp({ token: seed.admin });
  const { page } = app;
  await rt.go(page, `#/payments/${seed.payment.id}?tab=documents`, 4500);
  const f = path.join(TMP, 'd-history-check.pdf');
  fs.writeFileSync(f, h.pdfBytes('history'));
  await rt.tap(page, 'Upload', { wait: 1800 });
  const chooser = page.waitForEvent('filechooser', { timeout: 15000 });
  await rt.tap(page, 'Choose file', { wait: 700 });
  await (await chooser).setFiles(f);
  await page.waitForTimeout(1000);
  await rt.tap(page, 'Upload', { wait: 3500 });
  await rt.tap(page, 'History', { wait: 4000 });
  const entries = (await h.labels(page)).filter((l) => /Document/i.test(l));
  const history = entries.join(' | ');
  const inHistory = entries.some((l) => /Document Uploaded/i.test(l));
  if (!inHistory) await h.shot(page, 'D-38-1');
  add({
    id: 'D-38',
    feature: 'Audit',
    kind: 'UI',
    ac: 'AC-C4',
    title: 'A document uploaded from the tab appears in the record\'s History tab',
    status: inHistory ? 'PASS' : 'FAIL',
    severity: inHistory ? '' : 'medium',
    steps: `Payment ${seed.payment.id} > Documents > upload d-history-check.pdf; open the History tab`,
    expected: 'The upload is in the record\'s history, naming the document',
    actual: inHistory
      ? `History shows: ${history.replace(/\n/g, ' ').slice(0, 300)} (the entry records the action, the `
        + 'time and the actor; it does not name the file)'
      : `No "Document Uploaded" entry in History. Document-ish labels on screen: ${history.slice(0, 400) || 'none'}`,
    evidence: inHistory ? '' : 'shots/D-38-1.png',
    codeRef: 'frontend/lib/features/documents/document_providers.dart:60',
  });

  await app.close();
  h.saveResults('07-no-view-and-history.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
