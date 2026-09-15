// Re-check of W-12 (D-20) after the second fix (tighter column gaps, Target and Customer capped):
// the Disputes and Notifications tables with 1,000-character reasons must fit beside the sidebar
// at 1366 and 1920 — every header and the Open action on screen without scrolling sideways.
// Uses the long-reason disputes made by setup.js. Writes w12r.json.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = { checks: [] };
const snap = C.recorder(out);

/** Right-most on-screen extent of the table's headers and Open buttons (node x/y are centres). */
function extent(nodes, width) {
  const heads = nodes.filter((n) => n.role === 'columnheader');
  const opens = nodes.filter((n) => n.role === 'button' && C.lab(n) === 'Open' && n.y > 200);
  const right = (n) => n.x + n.w / 2;
  return {
    headers: heads.map((h) => `${C.lab(h)}@${Math.round(h.x - h.w / 2)}-${Math.round(right(h))}`),
    maxHeaderRight: Math.round(Math.max(0, ...heads.map(right))),
    openButtons: opens.length,
    openOnScreen: opens.length > 0 && opens.every((a) => right(a) <= width),
    width,
  };
}

(async () => {
  const T = await rt.adminToken();
  for (const width of [1366, 1920]) {
    const key = `W12r_${width}`;
    out[key] = {};
    const app = await rt.openApp({ token: T, width, height: 900 });
    try {
      const page = app.page;
      await rt.go(page, '#/disputes');
      await rt.go(page, `#/disputes?f=customerId:eq:${S.cLong.id}`, 4500);
      let n = await snap(page, key, `w12r-disputes-${width}`);
      const disputes = extent(n, width);
      const reasons = n.filter((x) => /wm-long-reason|wm-unbroken/.test(C.lab(x)));
      disputes.reasonCells = reasons.map((c) => `len=${C.lab(c).length} @${c.x},${c.y} ${c.w}x${c.h}`);
      if (width === 1366 && reasons[0]) {
        await page.mouse.move(reasons[0].x, reasons[0].y);
        await page.waitForTimeout(2000);
        await rt.enableSemantics(page);
        n = await snap(page, key, 'w12r-disputes-hover');
        disputes.hoverShowsFullText = C.has(n, /END-OF-REASON/).length > 0;
      }
      await rt.go(page, '#/notifications', 4500);
      n = await snap(page, key, `w12r-notifications-${width}`);
      const notifications = extent(n, width);
      out[key].disputes = disputes;
      out[key].notifications = notifications;
      // A table narrower than the space is stretched to it, so its last header ends at the window
      // edge; the semantics boxes round to a pixel either way. 2px of slack is not an overflow
      // (the first run's overflow was ~120px).
      const fits = (e) => e.maxHeaderRight > 0 && e.maxHeaderRight <= width + 2;
      const ok = fits(disputes) && disputes.openOnScreen && fits(notifications)
        && (width !== 1366 || disputes.hoverShowsFullText === true);
      out.checks.push({ id: 'W-12', defect: 'D-20', width, status: ok ? 'PASS' : 'FAIL',
        disputesRight: disputes.maxHeaderRight, openOnScreen: disputes.openOnScreen,
        notificationsRight: notifications.maxHeaderRight });
    } catch (e) {
      out.checks.push({ id: 'W-12', defect: 'D-20', width, status: 'ERROR', detail: String(e.stack || e) });
    } finally {
      out[key].apiErrors = app.apiErrors;
      out[key].pageErrors = app.pageErrors.slice(0, 5);
      await app.close();
    }
  }
  C.write('w12r.json', out);
  for (const c of out.checks) console.log(`${c.status.padEnd(5)} ${c.id} ${c.defect} @${c.width}: disputes right ${c.disputesRight}, Open on screen ${c.openOnScreen}, notifications right ${c.notificationsRight}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
