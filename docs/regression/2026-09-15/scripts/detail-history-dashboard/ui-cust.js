// Customer detail screen as admin: lazy tabs, ?tab URL, History chips/links, stale form, unsaved guard, not-found.
const { rt, S, DIR, logRequests, hash, selectedTab, allText, dump, clickRowLink, results } = require('./uih.js');
const R = results();
const A = S.custA.id; const B = S.custB.id;

(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;
  const reqs = logRequests(page);

  // C1 lazy tabs + default tab
  let mark = reqs.length;
  await rt.go(page, `#/customers/${A}`, 4500);
  const first = reqs.slice(mark).map((r) => r.u);
  const tab0 = await selectedTab(page);
  R.rec('C-LAZY-1', 'customer detail opens on Disputes; only disputes tab data fetched (no audit, no promises list)',
    first.some((u) => u.startsWith('/api/disputes') && u.includes(`customerId=${A}`)) && !first.some((u) => u.startsWith('/api/audit')) && !first.some((u) => /^\/api\/promises\?/.test(u)) && /Disputes/.test(tab0),
    { selected: tab0, requests: first });
  await rt.shot(page, DIR, 'c1-custA-default');

  // C2 open History -> URL ?tab=history, audit fetched, top not refetched
  mark = reqs.length;
  await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
  const h1 = await hash(page);
  const r2 = reqs.slice(mark).map((r) => r.u);
  R.rec('C-TAB-URL', 'tapping History writes ?tab=history, fetches /api/audit once, does not refetch the customer',
    h1 === `#/customers/${A}?tab=history` && r2.filter((u) => u.startsWith('/api/audit')).length === 1 && !r2.some((u) => u === `/api/customers/${A}`),
    { hash: h1, requests: r2 });
  await rt.shot(page, DIR, 'c2-custA-history');
  const d2 = await dump(page);
  const chips = d2.filter((l) => /\((\d+)\)/.test(l) && /(All|Customer|Invoices|Payments|Promises|Disputes) \(/.test(l));
  R.rec('C-HIST-CHIPS', 'History shows filter chips All/Customer/Invoices/Payments/Promises/Disputes with counts', chips.length >= 6, chips);

  // C3 chip narrowing
  const chipRes = {};
  for (const lbl of ['Invoices', 'Payments', 'Promises', 'Disputes', 'Customer']) {
    const nodes = await rt.semantics(page);
    const chip = nodes.find((n) => new RegExp(`^${lbl} \\(\\d+\\)$`).test((n.label || n.text || '').trim()));
    if (!chip) { chipRes[lbl] = 'chip missing'; continue; }
    const want = Number((chip.label || chip.text).match(/\((\d+)\)/)[1]);
    await rt.clickAt(page, chip.x, chip.y, 1500);
    const rows = (await dump(page)).filter((l) => /^\[button\]|^\[null\]/.test(l) && /(created|recorded|applied|reversed|opened|denied|approved|updated|assigned|removed|changed|cancelled)/i.test(l) && / (AM|PM) /.test(l));
    chipRes[lbl] = { want, shownRowsOnScreen: rows.length, sample: rows.slice(0, 3) };
    await rt.shot(page, DIR, `c3-chip-${lbl}`);
  }
  await rt.tap(page, /^All \(\d+\)$/, { wait: 1500 });
  R.rec('C-HIST-FILTER', 'each chip narrows rows to that record type (rows on screen <= count, all of that type)', Object.values(chipRes).every((v) => typeof v === 'object' && v.shownRowsOnScreen > 0 && v.shownRowsOnScreen <= v.want), chipRes);

  // C4 row link navigates
  const nodes4 = await rt.semantics(page);
  const payRow = nodes4.find((n) => /Payment recorded/.test(n.label || '') && new RegExp(`Payment #${S.PA1.id}`).test(n.label || ''));
  let link = { ok: false, reason: 'no payment row visible' };
  if (payRow) link = await clickRowLink(page, new RegExp(`Payment recorded[\\s\\S]*Payment #${S.PA1.id}`), new RegExp(`#/payments/${S.PA1.id}`));
  await rt.shot(page, DIR, 'c4-after-link');
  R.rec('C-HIST-LINK', `clicking "Payment #${S.PA1.id}" in a customer History row opens that payment`, link.ok, link);

  // C5 in-app nav to ?tab=promises opens that tab; then ?tab=history while on the same record
  await rt.go(page, `#/customers/${A}?tab=promises`, 4000);
  const t5 = await selectedTab(page);
  await rt.shot(page, DIR, 'c5-tab-promises');
  await rt.go(page, `#/customers/${A}?tab=history`, 3000);
  const t5b = await selectedTab(page);
  await rt.go(page, `#/customers/${A}?tab=disputes`, 3000);
  const t5c = await selectedTab(page);
  R.rec('C-TAB-DEEP', 'navigating to ?tab=promises opens Payment Promise; changing ?tab on the same record follows it', /Payment Promise/.test(t5) && /History/.test(t5b) && /Disputes/.test(t5c), { promises: t5, history: t5b, disputes: t5c });

  // C6 stale-form regression: A -> B
  await rt.go(page, `#/customers/${A}`, 3500);
  await rt.shot(page, DIR, 'c6a-custA');
  await rt.go(page, `#/customers/${B}`, 3500);
  const txt6 = await allText(page);
  await rt.shot(page, DIR, 'c6b-custB-after-A');
  R.rec('C-STALE', 'moving from customer A to customer B shows B (title + fields; see screenshot c6b)', txt6.includes(S.custB.name) && !txt6.includes(S.custA.name), { hasB: txt6.includes(S.custB.name), hasA: txt6.includes(S.custA.name), shot: 'shots/c6b-custB-after-A.png' });

  // C7 unsaved guard: Back arrow prompts
  await rt.go(page, `#/customers/${A}`, 3500);
  await rt.clickAt(page, 886, 289, 600); // phone field
  await page.keyboard.press('End');
  await rt.typeText(page, '7');
  await page.waitForTimeout(600);
  const dirty = (await allText(page)).includes('Unsaved changes');
  await rt.tap(page, 'Back', { wait: 1500 });
  const dlg = (await allText(page)).includes('Discard unsaved changes?');
  await rt.shot(page, DIR, 'c7-back-prompt');
  let keep = null; let disc = null;
  if (dlg) {
    await rt.tap(page, 'Keep editing', { wait: 1200 });
    keep = { hash: await hash(page), stillDirty: (await allText(page)).includes('Unsaved changes') };
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.tap(page, 'Discard', { wait: 2500 });
    disc = { hash: await hash(page) };
  }
  R.rec('C-GUARD-BACK', 'with unsaved edits the Back arrow prompts; Keep editing stays; Discard goes to /customers', dirty && dlg && keep?.hash === `#/customers/${A}` && keep?.stillDirty && /^#\/customers(\?|$)/.test(disc?.hash || ''), { dirty, dlg, keep, disc });

  // C8 unsaved guard: tab switching keeps edits
  await rt.go(page, `#/customers/${A}`, 3500);
  await rt.clickAt(page, 886, 289, 600);
  await page.keyboard.press('End');
  await rt.typeText(page, '8');
  await page.waitForTimeout(500);
  await rt.tap(page, 'History', { role: 'tab', wait: 2500 });
  const t8 = await allText(page);
  await rt.shot(page, DIR, 'c8-tab-switch-dirty');
  R.rec('C-GUARD-TAB', 'switching tab with unsaved edits keeps the edit and the "Unsaved changes" marker', t8.includes('Unsaved changes'), { hash: await hash(page), unsaved: t8.includes('Unsaved changes') });

  // C9 unsaved guard: sidebar nav
  await rt.clickAt(page, 104, 129, 3000); // sidebar Invoices
  const h9 = await hash(page);
  const dlg9 = (await allText(page)).includes('Discard unsaved changes?');
  await rt.shot(page, DIR, 'c9-sidebar-with-dirty');
  if (dlg9) { await rt.tap(page, 'Keep editing', { wait: 1000 }); }
  R.rec('C-GUARD-SIDEBAR', 'sidebar navigation with unsaved customer edits should prompt (AC-C3)', dlg9, { hashAfterClick: h9, dialogShown: dlg9 });
  // verify edit not saved
  const cust = await rt.api('GET', `/api/customers/${A}`, { token: await rt.adminToken() });
  R.rec('C-GUARD-SIDEBAR-NOSAVE', 'the silently dropped edit was not saved to the server', cust.json.phone === '555-0199', { phone: cust.json.phone });

  // C10 browser back with unsaved edits
  await rt.go(page, `#/customers/${A}`, 3500);
  await rt.clickAt(page, 886, 289, 600);
  await page.keyboard.press('End');
  await rt.typeText(page, '9');
  await page.waitForTimeout(500);
  await page.goBack();
  await page.waitForTimeout(2500);
  await rt.enableSemantics(page);
  const dlg10 = (await allText(page)).includes('Discard unsaved changes?');
  R.rec('C-GUARD-BROWSERBACK', 'browser Back with unsaved customer edits (observation)', dlg10, { hash: await hash(page), dialogShown: dlg10 });
  await rt.shot(page, DIR, 'c10-browser-back');
  if (dlg10) await rt.tap(page, 'Discard', { wait: 1500 });

  // C11 not found / malformed
  await rt.go(page, `#/customers/999999`, 3500);
  const t11 = await allText(page);
  await rt.shot(page, DIR, 'c11-cust-999999');
  R.rec('C-404', '#/customers/999999 shows "That customer does not exist." with Go back', t11.includes('That customer does not exist.') && t11.includes('Go back'), t11.slice(0, 400));
  const pe = app.pageErrors.length;
  await rt.go(page, `#/customers/abc`, 3500);
  const t12 = await allText(page);
  await rt.shot(page, DIR, 'c12-cust-abc');
  R.rec('C-MALFORMED', '#/customers/abc shows a clean not-found state (AC-C8)', /does not exist|not found|Not found/i.test(t12), { text: t12.slice(0, 400), newPageErrors: app.pageErrors.slice(pe) });

  R.rec('C-ERRORS', 'API errors / page errors during customer UI run (info)', true, { apiErrors: app.apiErrors, pageErrors: app.pageErrors.slice(0, 5) });
  R.save('ui-cust.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.message); R.save('ui-cust.json'); process.exit(1); });
