// History UI re-run with a date matcher that tolerates the U+202F narrow no-break space intl puts before AM/PM.
const { rt, S, DIR, hash, allText, results } = require('./uih.js');
const R = results();
const WHEN = /\d{4}\s+\d{1,2}:\d{2}/;

async function rowsOnScreen(page) {
  await rt.enableSemantics(page);
  return (await rt.semantics(page)).filter((n) => WHEN.test(n.label || n.text || '') && n.w > 600)
    .map((n) => ({ ...n, l: (n.label || n.text).replace(/\n/g, ' / ').replace(/ /g, ' ') }));
}
async function toTop(page) {
  for (let i = 0; i < 6; i++) { await page.mouse.move(800, 800); await page.mouse.wheel(0, -4000); await page.waitForTimeout(100); }
}
async function allRows(page) {
  const seen = new Map();
  await toTop(page);
  for (let i = 0; i < 45; i++) {
    for (const r of await rowsOnScreen(page)) if (!seen.has(r.l)) seen.set(r.l, r);
    await page.mouse.move(800, 800); await page.mouse.wheel(0, 250); await page.waitForTimeout(150);
  }
  return [...seen.keys()];
}
async function scrollToRow(page, re) {
  await toTop(page);
  for (let i = 0; i < 45; i++) {
    const n = (await rowsOnScreen(page)).find((r) => re.test(r.l));
    if (n && n.y - n.h / 2 > 600 && n.y + n.h / 2 < 895) return n;
    await page.mouse.move(800, 800); await page.mouse.wheel(0, n ? Math.max(60, n.y - 740) : 200); await page.waitForTimeout(200);
  }
  return null;
}
async function clickLinkInRow(page, re, expect) {
  const n = await scrollToRow(page, re);
  if (!n) return { ok: false, reason: 'row not scrolled into view' };
  const left = n.x - n.w / 2; const top = n.y - n.h / 2;
  const tries = [];
  for (const dy of [42, 38, 46, 34, 50]) {
    await rt.clickAt(page, Math.round(left + 80), Math.round(top + dy), 2500);
    const h = await hash(page);
    tries.push({ x: Math.round(left + 80), y: Math.round(top + dy), h });
    if (expect.test(h)) return { ok: true, row: n.l, tries };
    if (!/tab=history/.test(h)) break;
  }
  return { ok: false, row: n.l, tries };
}

(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;

  // Chips + titles on customer A
  await rt.go(page, `#/customers/${S.custA.id}?tab=history`, 5000);
  const all = await allRows(page);
  const chipOut = {};
  for (const [chip, re] of [['Invoices', /^(Invoice|Payment applied|Payment reversed|Dispute approved \/ INV)/], ['Payments', /^(Payment recorded|Payment updated|Dispute approved \/ Payment)/], ['Promises', /^Promise/], ['Disputes', /^Dispute (opened|denied)/], ['Customer', /^(Customer|POC|Primary POC)/]]) {
    await toTop(page);
    const c = (await rt.semantics(page)).find((n) => new RegExp(`^${chip} \\(\\d+\\)$`).test((n.label || n.text || '').trim()));
    if (!c) { chipOut[chip] = 'chip not found'; continue; }
    const want = Number((c.label || c.text).match(/\((\d+)\)/)[1]);
    await rt.clickAt(page, c.x, c.y, 1800);
    const rows = await allRows(page);
    chipOut[chip] = { want, got: rows.length, allMatchType: rows.every((l) => re.test(l)), offenders: rows.filter((l) => !re.test(l)).slice(0, 3) };
    await rt.shot(page, DIR, `r2-chip-${chip}`);
  }
  await toTop(page);
  await rt.tap(page, /^All \(\d+\)$/, { wait: 1500 });
  R.rec('R-CHIPS', 'customer History chips narrow rows to exactly that type with matching counts',
    Object.values(chipOut).every((v) => typeof v === 'object' && v.want === v.got && v.allMatchType), { totalRows: all.length, chipOut });
  R.rec('R-TITLES', 'customer History row titles (admin): readable titles with amounts, POC events included, newest first',
    all.length === 26 && all.some((l) => /^POC assigned/.test(l)) && all.some((l) => /^Primary POC changed/.test(l)) && all.some((l) => /^POC removed/.test(l))
    && all.some((l) => /^Payment recorded · ₹50\.00/.test(l)) && all.some((l) => /^Invoice created · ₹200\.00/.test(l)) && all.some((l) => /^Promise created · ₹60\.00/.test(l))
    && /^Customer details updated/.test(all[0] || '') && /^Customer created/.test(all[all.length - 1] || ''), all);

  // Links
  const l1 = await clickLinkInRow(page, new RegExp(`^Payment recorded · ₹50\\.00 / Payment #${S.PA1.id}`), new RegExp(`^#/payments/${S.PA1.id}$`));
  await rt.shot(page, DIR, 'r2-cust-link-payment');
  R.rec('R-LINK-CUST', `customer History "Payment #${S.PA1.id}" link opens the payment`, l1.ok, l1);
  await rt.go(page, `#/customers/${S.custA.id}?tab=history`, 4500);
  const l2 = await clickLinkInRow(page, new RegExp(`^Invoice created · ₹200\\.00 / ${S.A1.invoiceNumber}`), new RegExp(`^#/invoices/${S.A1.id}$`));
  R.rec('R-LINK-CUST-INV', `customer History "${S.A1.invoiceNumber}" link opens the invoice`, l2.ok, l2);
  await rt.go(page, `#/customers/${S.custA.id}?tab=history`, 4500);
  const l3 = await clickLinkInRow(page, new RegExp(`^Promise created · ₹60\\.00 by [^/]+ / Promise #${S.PR2.id}`), new RegExp(`#/(promises/${S.PR2.id}|customers/${S.custA.id}\\?tab=promises)`));
  await page.waitForTimeout(2500);
  const fh = await hash(page);
  await rt.shot(page, DIR, 'r2-cust-link-promise');
  R.rec('R-LINK-CUST-PROM', `customer History "Promise #${S.PR2.id}" link lands on the customer's Payment Promise tab`, l3.ok && /tab=promises/.test(fh), { ...l3, finalHash: fh });
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4500);
  const l4 = await clickLinkInRow(page, /^Payment applied · ₹50\.00/, new RegExp(`^#/payments/${S.PA1.id}$`));
  await rt.shot(page, DIR, 'r2-inv-link-payment');
  R.rec('R-LINK-INV', `invoice History "Payment #${S.PA1.id}" link opens the payment`, l4.ok, l4);

  // Long reason row expands
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4500);
  const row = await scrollToRow(page, new RegExp(`^Dispute opened / Dispute #${S.D2.id}`));
  let expanded = null;
  if (row) {
    await rt.clickAt(page, row.x + 300, row.y - 10, 2000);
    await rt.enableSemantics(page);
    const txt = await allText(page);
    expanded = { hasNote: /Note: LONG-REASON/.test(txt), hasEllipsisOrEnd: txt.includes('…') || txt.includes(' END'), hasDetails: /Details/.test(txt), hash: await hash(page) };
    await page.mouse.move(800, 800); await page.mouse.wheel(0, 120); await page.waitForTimeout(600);
    await rt.shot(page, DIR, 'r2-long-row-expanded');
  }
  R.rec('R-LONG-REASON-ROW', 'History row of the 616-char dispute expands to show the Note and snapshot without errors', !!expanded && expanded.hasNote && !app.pageErrors.length, { expanded, pageErrors: app.pageErrors.slice(0, 3) });

  R.save('retest2.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.stack); R.save('retest2.json'); process.exit(1); });
