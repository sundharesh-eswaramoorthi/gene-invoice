// D-01..D-08 — the tab itself: where it appears, its count badge, its URL slug, deep links and
// browser back (AC-C19, AC-C1, AC-C2).
const h = require('./helpers');
const rt = h.rt;
const seed = h.readSeed();
const cases = [];
const add = (c) => cases.push({ evidence: '', codeRef: '', severity: '', ...c });

const hash = (page) => page.evaluate(() => location.hash);

(async () => {
  const app = await rt.openApp({ token: seed.admin });
  const { page } = app;

  // --- D-01/02/03: the tab is on all three detail screens, with a count -----------------------
  for (const [id, type, route, entityId, expected] of [
    ['D-01', 'invoice', `#/invoices/${seed.invoice.id}`, seed.invoice.id, 2],
    ['D-02', 'customer', `#/customers/${seed.customer.id}`, seed.customer.id, 1],
    ['D-03', 'payment', `#/payments/${seed.payment.id}`, seed.payment.id, 1],
  ]) {
    await rt.go(page, route);
    const nodes = await rt.semantics(page);
    const tab = nodes.find((n) => n.role === 'tab' && /Documents/.test(`${n.label || n.text}`));
    const badge = tab ? (`${tab.label || tab.text}`.match(/(\d+)/) || [])[1] : null;
    const ok = !!tab && Number(badge) === expected;
    if (!ok) await h.shot(page, `${id}-1`);
    add({
      id,
      feature: 'Documents tab',
      kind: 'UI',
      ac: 'AC-C19, AC-C1',
      title: `Documents tab, with its count, is on the ${type} detail screen`,
      status: ok ? 'PASS' : 'FAIL',
      severity: ok ? '' : 'high',
      steps: `Sign in as admin; open ${route} (a ${type} with ${expected} document(s)); read the tab bar`,
      expected: `A "Documents" tab is present and badged ${expected}`,
      actual: tab ? `Tab present, badge ${badge ?? 'none'}` : `No Documents tab. Tabs: ${
        nodes.filter((n) => n.role === 'tab').map((n) => `${n.label || n.text}`.replace(/\n/g, ' ')).join(', ')}`,
      evidence: ok ? '' : `shots/${id}-1.png`,
      codeRef: `frontend/lib/features/documents/document_actions.dart:36`,
    });
  }

  // --- D-04: tapping it syncs the URL slug ---------------------------------------------------
  await rt.go(page, `#/invoices/${seed.invoice.id}`);
  await rt.tap(page, /Documents/, { wait: 2500 });
  const afterTap = await hash(page);
  const slugOk = /tab=documents/.test(afterTap);
  if (!slugOk) await h.shot(page, 'D-04-1');
  add({
    id: 'D-04',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19',
    title: 'Selecting the Documents tab syncs the URL slug',
    status: slugOk ? 'PASS' : 'FAIL',
    severity: slugOk ? '' : 'medium',
    steps: `Open #/invoices/${seed.invoice.id}; tap the Documents tab; read location.hash`,
    expected: 'The hash carries the documents slug, e.g. #/invoices/{id}?tab=documents',
    actual: `location.hash = ${afterTap}`,
    evidence: slugOk ? '' : 'shots/D-04-1.png',
    codeRef: 'frontend/lib/shared/widgets/detail_scaffold.dart:74',
  });

  // --- D-05: the slug is a link — a fresh in-app navigation lands on the tab -----------------
  await rt.go(page, '#/invoices');
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 4000);
  const deepOk = await h.seen(page, 'Documents (2)');
  if (!deepOk) await h.shot(page, 'D-05-1');
  add({
    id: 'D-05',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19',
    title: 'The documents slug is linkable — navigating to it opens the tab',
    status: deepOk ? 'PASS' : 'FAIL',
    severity: deepOk ? '' : 'medium',
    steps: `From the invoice list, navigate to #/invoices/${seed.invoice.id}?tab=documents`,
    expected: 'The Documents tab is the selected tab and its list is on screen',
    actual: deepOk ? 'Documents tab open, header "Documents (2)" shown'
      : `Documents panel not shown. On screen: ${(await h.labels(page)).join(' | ').slice(0, 400)}`,
    evidence: deepOk ? '' : 'shots/D-05-1.png',
    codeRef: 'frontend/lib/shared/widgets/detail_scaffold.dart:77',
  });

  // --- D-06: browser back leaves the tab the way the other tabs do ---------------------------
  await rt.go(page, `#/invoices/${seed.invoice.id}`);
  await rt.tap(page, 'History', { wait: 2500 });
  const historyHash = await hash(page);
  await rt.tap(page, /Documents/, { wait: 2500 });
  await page.goBack();
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  const backHash = await hash(page);
  const backShowsDocs = await h.seen(page, 'Documents (2)');
  const backOk = backHash === historyHash && !backShowsDocs;
  if (!backOk) await h.shot(page, 'D-06-1');
  add({
    id: 'D-06',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19',
    title: 'Browser back steps off the Documents tab like any other tab',
    status: backOk ? 'PASS' : 'FAIL',
    severity: backOk ? '' : 'medium',
    steps: 'Open the invoice; select History; select Documents; press browser Back',
    expected: `Back returns to ${historyHash} and the Documents list is no longer shown`,
    actual: `hash after back = ${backHash}; Documents list still on screen: ${backShowsDocs}`,
    evidence: backOk ? '' : 'shots/D-06-1.png',
    codeRef: 'frontend/lib/shared/widgets/detail_scaffold.dart:94',
  });

  // --- D-07: the badge follows the data ------------------------------------------------------
  const extra = await h.apiUpload(seed.admin, {
    entityType: 'INVOICE', entityId: seed.invoice.id,
    name: 'd-badge-check.pdf', bytes: h.pdfBytes('badge'), type: 'application/pdf',
    description: 'badge check', visibility: 'INTERNAL',
  });
  await rt.go(page, '#/invoices');
  await rt.go(page, `#/invoices/${seed.invoice.id}`, 4000);
  const nodes07 = await rt.semantics(page);
  const tab07 = nodes07.find((n) => n.role === 'tab' && /Documents/.test(`${n.label || n.text}`));
  const badge07 = tab07 ? (`${tab07.label || tab07.text}`.match(/(\d+)/) || [])[1] : null;
  const badgeOk = Number(badge07) === 3;
  if (!badgeOk) await h.shot(page, 'D-07-1');
  add({
    id: 'D-07',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C1',
    title: 'The tab count tracks the record — a third document makes it 3, without opening the tab',
    status: badgeOk ? 'PASS' : 'FAIL',
    severity: badgeOk ? '' : 'medium',
    steps: 'Upload a third document to the invoice via the API; reopen the invoice detail screen; read the tab badge',
    expected: 'Documents tab badge reads 3, with the tab never opened',
    actual: `badge = ${badge07 ?? 'none'}`,
    evidence: badgeOk ? '' : 'shots/D-07-1.png',
    codeRef: 'frontend/lib/features/documents/document_providers.dart:33',
  });
  if (extra.status < 300) await rt.api('DELETE', `/api/documents/${extra.json.id}`, { token: seed.admin });

  // --- D-08: each row says what the file is, who put it there and when ------------------------
  await rt.go(page, `#/invoices/${seed.invoice.id}?tab=documents`, 4000);
  const rows = (await rt.semantics(page))
    .filter((n) => n.role === 'group' && /\.(pdf|png)/i.test(`${n.label || n.text}`))
    .map((n) => `${n.label || n.text}`);
  const first = rows[0] || '';
  const rowOk = rows.length === 2
    && /d-signed-delivery-note\.png/.test(first)
    && /Shared/.test(first) && /PNG/.test(first) && /B ·/.test(first)
    && /System Administrator/.test(first) && /2026/.test(first)
    && /d-purchase-order\.pdf/.test(rows[1] || '') && /Internal/.test(rows[1] || '');
  if (!rowOk) await h.shot(page, 'D-08-1');
  add({
    id: 'D-08',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C2, AC-C1',
    title: 'Rows list newest-first with name, kind, size, uploader, time and visibility',
    status: rowOk ? 'PASS' : 'FAIL',
    severity: rowOk ? '' : 'medium',
    steps: 'Open the invoice Documents tab (PO uploaded first, delivery note second)',
    expected: 'Two rows, delivery note (SHARED) above the PO (INTERNAL), each showing kind, size, uploader and time',
    actual: rows.map((r) => r.replace(/\n/g, ' / ')).join('  ||  ') || 'no document rows found',
    evidence: rowOk ? '' : 'shots/D-08-1.png',
    codeRef: 'frontend/lib/features/documents/documents_tab.dart:283',
  });

  // --- D-09: nothing broke along the way ------------------------------------------------------
  const errs = app.apiErrors.filter((e) => !/\/api\/documents\/\d+$/.test(e.url) || e.method !== 'DELETE');
  const clean = errs.length === 0 && app.pageErrors.length === 0;
  add({
    id: 'D-09',
    feature: 'Documents tab',
    kind: 'UI',
    ac: 'AC-C19, AC-C22',
    title: 'Browsing the Documents tab on all three record types raises no console or API error',
    status: clean ? 'PASS' : 'FAIL',
    severity: clean ? '' : 'medium',
    steps: 'One admin session covering D-01..D-08; collect every response >= 400 and every page error',
    expected: 'No failed API call and no page error',
    actual: clean ? 'none' : `apiErrors ${JSON.stringify(errs)}; pageErrors ${JSON.stringify(app.pageErrors)}`,
  });

  await app.close();
  h.saveResults('02-tabs.json', cases);
})().catch((e) => { console.error(e); process.exit(1); });
