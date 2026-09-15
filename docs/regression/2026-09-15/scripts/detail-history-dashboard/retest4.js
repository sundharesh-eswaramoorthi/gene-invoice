// Expand the long-reason dispute row by clicking its title line (not the link line); record link node widths.
const { rt, S, DIR, hash, allText, results } = require('./uih.js');
const R = results();
const WHEN = /\d{4}\s+\d{1,2}:\d{2}/;
async function rowsOnScreen(page) {
  await rt.enableSemantics(page);
  return (await rt.semantics(page)).filter((n) => WHEN.test(n.label || n.text || '') && n.w > 600)
    .map((n) => ({ ...n, l: (n.label || n.text).replace(/\n/g, ' / ') }));
}
(async () => {
  const app = await rt.openApp({ token: await rt.adminToken() });
  const { page } = app;
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 5000);
  let row = null;
  for (let i = 0; i < 30 && !row; i++) {
    const n = (await rowsOnScreen(page)).find((r) => new RegExp(`^Dispute opened / Dispute #${S.D2.id}`).test(r.l));
    if (n && n.y - n.h / 2 > 600 && n.y + n.h / 2 < 820) { row = n; break; }
    await page.mouse.move(800, 800); await page.mouse.wheel(0, n ? Math.max(40, n.y - 680) : 150); await page.waitForTimeout(300);
  }
  const top = row ? row.y - row.h / 2 : 0;
  const linkNodes = row ? (await rt.semantics(page)).filter((n) => /^Dispute #/.test(n.label || n.text || '') && Math.abs(n.y - row.y) < row.h).map((n) => ({ label: n.label || n.text, x: n.x, y: n.y, w: n.w, h: n.h })) : [];
  await rt.shot(page, DIR, 'r4-before-expand');
  let exp = null;
  if (row) {
    await rt.clickAt(page, Math.round(row.x + 200), Math.round(top + 18), 2000);
    await rt.enableSemantics(page);
    const txt = await allText(page);
    exp = { hash: await hash(page), hasNote: /Note: LONG-REASON/.test(txt), hasDetails: /Details/.test(txt), fullInDetails: txt.includes(' END'), ellipsis: /x…/.test(txt) };
    await page.mouse.move(800, 800); await page.mouse.wheel(0, 140); await page.waitForTimeout(700);
    await rt.shot(page, DIR, 'r4-after-expand');
  }
  R.rec('R-LONG-REASON-ROW', 'History row for the 616-char dispute expands (title-line click): Note shown (500-char, ellipsis) and Details snapshot with full reason; no page errors',
    !!exp && /tab=history/.test(exp.hash) && exp.hasNote && exp.hasDetails && !app.pageErrors.length, { row: row?.l?.slice(0, 90), rowBox: row && { y: row.y, h: row.h, w: row.w }, linkNodes, exp, pageErrors: app.pageErrors.slice(0, 3) });
  R.save('retest4.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.stack); R.save('retest4.json'); process.exit(1); });
