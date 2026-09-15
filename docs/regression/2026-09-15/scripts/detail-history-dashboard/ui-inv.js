// Invoice + payment detail screens as admin.
const { rt, S, DIR, logRequests, hash, selectedTab, allText, dump, clickRowLink, results } = require('./uih.js');
const R = results();

async function notesField(page) {
  const n = (await rt.semantics(page)).find((x) => (x.label || x.text) === 'Notes');
  return n ? { x: 886, y: n.y + 8 } : null;
}

(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;
  const reqs = logRequests(page);

  // I1 invoice A1 top section
  let mark = reqs.length;
  await rt.go(page, `#/invoices/${S.A1.id}`, 4500);
  const t1 = await allText(page);
  await rt.shot(page, DIR, 'i1-A1');
  const r1 = reqs.slice(mark).map((r) => r.u);
  R.rec('I-TOP', 'invoice A1 top: number, Total 200, Paid 50, Balance 150, tabs; only disputes tab data loaded',
    t1.includes(S.A1.invoiceNumber) && t1.includes('₹200.00') && t1.includes('₹50.00') && t1.includes('₹150.00') && !r1.some((u) => u.startsWith('/api/audit') || /^\/api\/promises\?/.test(u)),
    { tab: await selectedTab(page), requests: r1, text: t1.slice(0, 500) });

  // I2 Payment Promise tab (regression: 400 invoiceId)
  mark = reqs.length;
  await rt.tap(page, 'Payment Promise', { role: 'tab', wait: 3000 });
  const t2 = await allText(page);
  const r2 = reqs.slice(mark).map((r) => r.u);
  await rt.shot(page, DIR, 'i2-A1-promises');
  R.rec('I-PROM-TAB', 'invoice Payment Promise tab loads (no 400) and lists only PR1 (₹100.00), URL ?tab=promises',
    (await hash(page)) === `#/invoices/${S.A1.id}?tab=promises` && t2.includes('₹100.00 by') && !t2.includes('₹60.00 by') && !t2.includes('₹10.00 by') && !app.apiErrors.some((e) => e.url.startsWith('/api/promises')),
    { hash: await hash(page), requests: r2, cards: (await dump(page)).filter((l) => / by /.test(l)), apiErrors: app.apiErrors });

  // I3 History tab
  await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
  const d3 = await dump(page);
  await rt.shot(page, DIR, 'i3-A1-history');
  const t3 = d3.join('\n');
  R.rec('I-HIST', 'invoice History: Invoice created · ₹200.00, Payment applied/reversed with amounts, Promise created, Disputes opened/denied; no A2 rows',
    /Invoice created · ₹200\.00/.test(t3) && /Payment applied · ₹50\.00/.test(t3) && /Payment reversed · ₹20\.00/.test(t3) && /Promise created · ₹100\.00/.test(t3) && /Dispute opened/.test(t3) && /Dispute denied/.test(t3) && !t3.includes(S.A2.invoiceNumber),
    d3.filter((l) => /AM|PM/.test(l)).slice(0, 12));

  // I4 link to payment from Payment applied row
  const link = await clickRowLink(page, new RegExp(`Payment applied · ₹50\\.00`), new RegExp(`#/payments/${S.PA1.id}`));
  await rt.shot(page, DIR, 'i4-after-payment-link');
  R.rec('I-HIST-LINK', `"Payment #${S.PA1.id}" link in invoice History opens the payment`, link.ok, link);

  // I4b link to dispute from invoice History row
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4000);
  const link2 = await clickRowLink(page, new RegExp(`Dispute denied`), new RegExp(`#/disputes/${S.D1.id}`));
  await rt.shot(page, DIR, 'i4b-after-dispute-link');
  R.rec('I-HIST-LINK-DISPUTE', `"Dispute #${S.D1.id}" link in invoice History opens the dispute`, link2.ok, link2);

  // I5 stale form: A1 -> B1
  await rt.go(page, `#/invoices/${S.A1.id}`, 4000);
  await rt.go(page, `#/invoices/${S.B1.id}`, 4000);
  const t5 = await allText(page);
  await rt.shot(page, DIR, 'i5-B1-after-A1');
  R.rec('I-STALE', 'moving from invoice A1 to B1 shows B1 title and totals (notes field: see screenshot i5)', t5.includes(S.B1.invoiceNumber) && t5.includes('₹300.00') && !t5.includes(S.A1.invoiceNumber), { hasB1: t5.includes(S.B1.invoiceNumber), shot: 'shots/i5-B1-after-A1.png' });

  // I6 edit notes on B1 then in-app link navigation (hash change to A1) - observe prompt; then Back arrow prompt
  let nf = await notesField(page);
  if (nf) {
    await rt.clickAt(page, nf.x, nf.y, 600);
    await page.keyboard.press('End');
    await rt.typeText(page, ' EDITED');
    await page.waitForTimeout(500);
  }
  const dirty6 = (await allText(page)).includes('Unsaved changes');
  await rt.shot(page, DIR, 'i6-B1-dirty');
  await rt.tap(page, 'Back', { wait: 1500 });
  const dlg6 = (await allText(page)).includes('Discard unsaved changes?');
  if (dlg6) await rt.tap(page, 'Keep editing', { wait: 1200 });
  R.rec('I-GUARD-BACK', 'invoice: unsaved notes + Back arrow prompts', dirty6 && dlg6, { dirty6, dlg6 });
  // sidebar nav to Payments
  await rt.clickAt(page, 108, 173, 3000);
  const dlg6b = (await allText(page)).includes('Discard unsaved changes?');
  R.rec('I-GUARD-SIDEBAR', 'invoice: unsaved notes + sidebar "Payments" should prompt', dlg6b, { hash: await hash(page), dialog: dlg6b });
  if (dlg6b) await rt.tap(page, 'Discard', { wait: 1500 });
  const b1 = await rt.api('GET', `/api/invoices/${S.B1.id}`, { token: await rt.adminToken() });
  R.rec('I-NOSAVE', 'B1 notes unchanged on server after dropped edit', b1.json.notes === 'B1 notes original', { notes: b1.json.notes });

  // I7 not found / malformed
  await rt.go(page, '#/invoices/999999', 3500);
  const t7 = await allText(page);
  await rt.shot(page, DIR, 'i7-inv-999999');
  R.rec('I-404', '#/invoices/999999 shows "That invoice does not exist." + Go back', t7.includes('That invoice does not exist.') && t7.includes('Go back'), t7.slice(0, 300));
  const pe = app.pageErrors.length;
  await rt.go(page, '#/invoices/abc', 3500);
  const t8 = await allText(page);
  await rt.shot(page, DIR, 'i8-inv-abc');
  R.rec('I-MALFORMED', '#/invoices/abc shows a clean not-found state (AC-C8)', /does not exist|not found/i.test(t8), { text: t8.slice(0, 300), pageErrors: app.pageErrors.slice(pe) });
  await rt.go(page, '#/payments/abc', 3500);
  await rt.shot(page, DIR, 'i8b-pay-abc');
  const t8b = await allText(page);
  R.rec('P-MALFORMED', '#/payments/abc shows a clean not-found state', /does not exist|not found/i.test(t8b), { text: t8b.slice(0, 300) });
  await rt.go(page, '#/payments/999999', 3500);
  const t8c = await allText(page);
  R.rec('P-404', '#/payments/999999 shows "That payment does not exist."', t8c.includes('That payment does not exist.'), t8c.slice(0, 300));

  // I9 Show more on C1 history (106 rows)
  await rt.go(page, `#/invoices/${S.C1.id}?tab=history`, 5000);
  const tab9 = await selectedTab(page);
  // scroll the history list to the bottom
  for (let i = 0; i < 40; i++) { await page.mouse.move(800, 780); await page.mouse.wheel(0, 3000); await page.waitForTimeout(120); }
  await page.waitForTimeout(800);
  await rt.enableSemantics(page);
  const d9 = await dump(page);
  const more = d9.find((l) => /Show \d+ more \(\d+ older\)/.test(l));
  await rt.shot(page, DIR, 'i9-C1-history-bottom');
  let after9 = null;
  if (more) {
    await rt.tap(page, /Show \d+ more/, { wait: 1500 });
    for (let i = 0; i < 20; i++) { await page.mouse.wheel(0, 3000); await page.waitForTimeout(120); }
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    const d9b = await dump(page);
    after9 = { moreStill: d9b.some((l) => /Show \d+ more/.test(l)), lastRows: d9b.filter((l) => /AM|PM/.test(l)).slice(-3) };
    await rt.shot(page, DIR, 'i9b-C1-history-after-more');
  }
  R.rec('I-SHOWMORE', 'invoice with 106 history rows shows "Show 6 more (6 older)"; tapping it reveals the rest', !!more && /Show 6 more \(6 older\)/.test(more) && after9 && !after9.moreStill, { tab: tab9, more, after9 });

  // P1 payment PA1 detail
  mark = reqs.length;
  await rt.go(page, `#/payments/${S.PA1.id}`, 4500);
  const p1 = await allText(page);
  await rt.shot(page, DIR, 'p1-PA1');
  R.rec('P-TOP', 'payment PA1 top: Payment #, Amount ₹50.00, method CASH, allocation to A1', p1.includes(`Payment #${S.PA1.id}`) && p1.includes('₹50.00') && p1.includes('CASH') && p1.includes(S.A1.invoiceNumber), p1.slice(0, 500));
  await rt.tap(page, 'Payment Promise', { role: 'tab', wait: 3000 });
  const p2 = (await dump(page)).filter((l) => / by /.test(l));
  await rt.shot(page, DIR, 'p2-PA1-promises');
  const pr = await rt.api('GET', `/api/promises/${S.PR1.id}`, { token: await rt.adminToken() });
  const pr2 = await rt.api('GET', `/api/promises/${S.PR2.id}`, { token: await rt.adminToken() });
  const pr3 = await rt.api('GET', `/api/promises/${S.PR3.id}`, { token: await rt.adminToken() });
  const linked = [pr.json, pr2.json, pr3.json].filter((p) => (p.payments || []).some((x) => x.id === S.PA1.id)).map((p) => p.id);
  R.rec('P-PROM-TAB', 'payment Payment Promise tab should list only the promises this payment counts towards (C.3)', p2.length === linked.length, { cardsShown: p2, promisesLinkedToPA1: linked });
  await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
  const p3 = await dump(page);
  await rt.shot(page, DIR, 'p3-PA1-history');
  R.rec('P-HIST', 'payment History shows Payment recorded · ₹50.00 and its hash is ?tab=history', p3.some((l) => /Payment recorded · ₹50\.00/.test(l)) && (await hash(page)) === `#/payments/${S.PA1.id}?tab=history`, p3.filter((l) => /AM|PM/.test(l)));

  // P4 stale form PA1 -> PB1
  await rt.go(page, `#/payments/${S.PB1.id}`, 4000);
  const p4 = await allText(page);
  await rt.shot(page, DIR, 'p4-PB1-after-PA1');
  R.rec('P-STALE', 'moving from payment PA1 to PB1 shows PB1 (₹30.00, B1 allocation; notes see screenshot)', p4.includes(`Payment #${S.PB1.id}`) && p4.includes('₹30.00') && p4.includes(S.B1.invoiceNumber) && !p4.includes(S.A1.invoiceNumber), { shot: 'shots/p4-PB1-after-PA1.png' });

  // P5 dirty notes + allocation row tap (in-page navigation)
  nf = await notesField(page);
  if (nf) { await rt.clickAt(page, nf.x, nf.y, 600); await page.keyboard.press('End'); await rt.typeText(page, ' X'); await page.waitForTimeout(500); }
  const dirty5 = (await allText(page)).includes('Unsaved changes');
  await rt.tap(page, new RegExp(S.B1.invoiceNumber), { wait: 3000 });
  const dlg5 = (await allText(page)).includes('Discard unsaved changes?');
  R.rec('P-GUARD-ALLOC', 'payment: unsaved notes + tapping an allocation row should prompt before leaving', dirty5 && dlg5, { dirty5, dialog: dlg5, hash: await hash(page) });
  await rt.shot(page, DIR, 'p5-alloc-tap-dirty');
  if (dlg5) await rt.tap(page, 'Discard', { wait: 1500 });

  // T1 failing tab shows error inside tab (History 500 via route interception)
  await page.route('**/api/audit**', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ status: 500, message: 'rt forced audit failure' }) }));
  await page.route('**/api/promises?**', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ status: 500, message: 'rt forced promise failure' }) }));
  await rt.go(page, `#/invoices/${S.A2.id}`, 4000);
  await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
  const f1 = await allText(page);
  await rt.shot(page, DIR, 't1-history-fail');
  await rt.tap(page, 'Payment Promise', { role: 'tab', wait: 3000 });
  const f2 = await allText(page);
  await rt.shot(page, DIR, 't2-promises-fail');
  R.rec('T-TAB-ERROR', 'a failing tab renders its error inside the tab; top section and other tabs remain', f1.includes('History unavailable') && f1.includes(S.A2.invoiceNumber) && f2.includes('rt forced promise failure') && f2.includes('Retry') && f2.includes(S.A2.invoiceNumber),
    { historyErr: (f1.match(/History unavailable[^|]*/) || [])[0], promErr: f2.includes('rt forced promise failure'), retry: f2.includes('Retry') });
  await page.unroute('**/api/audit**');
  await page.unroute('**/api/promises?**');

  R.rec('I-ERRORS', 'API/page errors during invoice/payment UI run (info)', true, { apiErrors: app.apiErrors.filter((e) => !e.url.includes('rt forced')), pageErrors: app.pageErrors.slice(0, 5) });
  R.save('ui-inv.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.message); R.save('ui-inv.json'); process.exit(1); });
