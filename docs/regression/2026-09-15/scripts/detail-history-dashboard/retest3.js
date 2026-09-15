// Expand the long-reason dispute row via its chevron; links on Payment applied rows in the customer timeline;
// confirm duplicate-label rows explain the chip count difference.
const { rt, S, DIR, hash, allText, results } = require('./uih.js');
const R = results();
const WHEN = /\d{4}\s+\d{1,2}:\d{2}/;
async function rowsOnScreen(page) {
  await rt.enableSemantics(page);
  return (await rt.semantics(page)).filter((n) => WHEN.test(n.label || n.text || '') && n.w > 600)
    .map((n) => ({ ...n, l: (n.label || n.text).replace(/\n/g, ' / ') }));
}
async function toTop(page) { for (let i = 0; i < 6; i++) { await page.mouse.move(800, 800); await page.mouse.wheel(0, -4000); await page.waitForTimeout(100); } }
async function scrollToRow(page, re) {
  await toTop(page);
  for (let i = 0; i < 45; i++) {
    const n = (await rowsOnScreen(page)).find((r) => re.test(r.l));
    if (n && n.y - n.h / 2 > 600 && n.y + n.h / 2 < 860) return n;
    await page.mouse.move(800, 800); await page.mouse.wheel(0, n ? Math.max(60, n.y - 700) : 200); await page.waitForTimeout(200);
  }
  return null;
}

(async () => {
  const A = await rt.adminToken();
  const h = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${S.custA.id}&includeRelated=true`, { token: A });
  const cust = h.json.filter((e) => e.entityType === 'CUSTOMER');
  R.rec('R-CHIP-CUSTOMER-COUNT', 'Customer chip count 7 = CUSTOMER_CREATED, CUSTOMER_UPDATED, 3x POC_ASSIGNED, POC_PRIMARY_CHANGED, POC_REMOVED (the 3 POC_ASSIGNED rows share one label)',
    cust.length === 7 && cust.filter((e) => e.action === 'POC_ASSIGNED').length === 3, cust.map((e) => e.action));

  const app = await rt.openApp({ token: A });
  const { page } = app;

  // 1. Long reason row expand via chevron (right edge)
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4500);
  const row = await scrollToRow(page, new RegExp(`^Dispute opened / Dispute #${S.D2.id}`));
  let exp = null;
  if (row) {
    const cx = Math.round(row.x + row.w / 2 - 36); const cy = Math.round(row.y);
    await rt.clickAt(page, cx, cy, 2000);
    await rt.enableSemantics(page);
    const txt = await allText(page);
    exp = { click: [cx, cy], hash: await hash(page), hasNote: /Note: LONG-REASON/.test(txt), hasDetails: /Details/.test(txt), endInSnapshot: txt.includes(' END'), noteLen: ((txt.match(/Note: (LONG-REASON[^|]*)/) || [])[1] || '').trim().length };
    await page.mouse.move(800, 800); await page.mouse.wheel(0, 150); await page.waitForTimeout(700);
    await rt.shot(page, DIR, 'r3-long-row-expanded');
  }
  R.rec('R-LONG-REASON-ROW', 'History row of the 616-char dispute expands (chevron) showing Note (truncated 500) + Details snapshot with full reason, no errors',
    !!exp && exp.hasNote && exp.hasDetails && /tab=history/.test(exp.hash) && !app.pageErrors.length, { row: row?.l?.slice(0, 80), exp, pageErrors: app.pageErrors.slice(0, 3) });

  // 2. Payment applied row in customer timeline: which links render?
  await rt.go(page, `#/customers/${S.custA.id}?tab=history`, 4500);
  const pa = await scrollToRow(page, /^Payment applied · ₹50\.00/);
  let links = null;
  if (pa) {
    await rt.shot(page, DIR, 'r3-cust-payment-applied-row');
    const nodes = (await rt.semantics(page)).filter((n) => Math.abs(n.y - pa.y) < pa.h / 2 + 2).map((n) => `[${n.role}] ${(n.label || n.text).replace(/\n/g, ' / ')} @${n.x},${n.y}`);
    const left = pa.x - pa.w / 2; const top = pa.y - pa.h / 2;
    await rt.clickAt(page, Math.round(left + 80), Math.round(top + 42), 2500);
    links = { rowLabel: pa.l, nodesInRow: nodes, afterClickHash: await hash(page) };
  }
  R.rec('R-CUST-APPLIED-LINKS', 'customer timeline "Payment applied" row shows the invoice number link and Payment # link (showRecord + paymentId)',
    !!links && new RegExp(`${S.A1.invoiceNumber}`).test(links.rowLabel) && /Payment #109/.test(links.rowLabel), links);
  R.save('retest3.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.stack); R.save('retest3.json'); process.exit(1); });
