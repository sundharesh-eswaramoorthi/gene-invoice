// Mouse-user expansion of the long-reason dispute row: clicks go to the canvas (semantics overlay made click-through),
// so this is what a sighted mouse user gets. Also checks what the accessibility tree does on the same row.
const { rt, S, DIR, hash, allText, results } = require('./uih.js');
const R = results();
const WHEN = /\d{4}\s+\d{1,2}:\d{2}/;
async function rowsOnScreen(page) {
  await rt.enableSemantics(page);
  return (await rt.semantics(page)).filter((n) => WHEN.test(n.label || n.text || '') && n.w > 600)
    .map((n) => ({ ...n, l: (n.label || n.text).replace(/\n/g, ' / ') }));
}
async function findRow(page, re) {
  for (let i = 0; i < 30; i++) {
    const n = (await rowsOnScreen(page)).find((r) => re.test(r.l));
    if (n && n.y - n.h / 2 > 600 && n.y + n.h / 2 < 820) return n;
    await page.mouse.move(800, 800); await page.mouse.wheel(0, n ? Math.max(40, n.y - 680) : 150); await page.waitForTimeout(300);
  }
  return null;
}
const clickThrough = (page) => page.evaluate(() => {
  const s = document.createElement('style');
  s.textContent = 'flt-semantics-host, flt-semantics { pointer-events: none !important; }';
  document.head.appendChild(s);
});

(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 5000);
  const row = await findRow(page, new RegExp(`^Dispute opened / Dispute #${S.D2.id}`));
  let exp = null;
  if (row) {
    const top = row.y - row.h / 2;
    await clickThrough(page);
    await page.mouse.click(Math.round(row.x + 200), Math.round(top + 18));
    await page.waitForTimeout(2000);
    const h = await hash(page);
    await rt.shot(page, DIR, 'r5-mouse-expand');
    await page.mouse.move(800, 800); await page.mouse.wheel(0, 160); await page.waitForTimeout(700);
    await rt.shot(page, DIR, 'r5-mouse-expand-scrolled');
    const txt = await allText(page);
    exp = { hash: h, hasNote: /Note: LONG-REASON/.test(txt), hasDetails: /Details/.test(txt), endInDetails: txt.includes(' END') };
  }
  R.rec('R-LONG-REASON-MOUSE', 'mouse click on the 616-char dispute row title expands it (Note + Details) and stays on the invoice History',
    !!exp && /tab=history/.test(exp.hash) && exp.hasNote && !app.pageErrors.length, { row: row?.l?.slice(0, 80), exp, pageErrors: app.pageErrors.slice(0, 3) });

  // A single-link row (Invoice created) via accessibility tap: does activating it expand or navigate?
  await page.goto(rt.WEB + `#/invoices/${S.A2.id}?tab=history`);
  await page.waitForTimeout(6000);
  await rt.enableSemantics(page);
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4500);
  const r2 = await findRow(page, /^Dispute denied \/ Dispute #/);
  let a11y = null;
  if (r2) {
    await page.locator(`flt-semantics[data-rt="${r2.i}"]`).dispatchEvent('click');
    await page.waitForTimeout(2000);
    a11y = { hash: await hash(page) };
  }
  R.rec('R-A11Y-ROW-ACTIVATE', 'accessibility activation of a dispute History row (one merged node) — expands or follows the link? (observation)', !!a11y && /tab=history/.test(a11y.hash), { row: r2?.l?.slice(0, 80), a11y });
  R.save('retest5.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.stack); R.save('retest5.json'); process.exit(1); });
