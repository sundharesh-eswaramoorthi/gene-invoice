// Verifier: DET-006 (payment Promise tab), DET-016 (malformed ids), HUI-007 (history row a11y), DASH-006.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const res = {};
const hash = (page) => page.evaluate(() => location.hash);
const lbl = (n) => `${n.label || ''} ${n.text || ''}`.trim();

(async () => {
  const token = await rt.adminToken();
  const app = await rt.openApp({ token });
  const { page } = app;
  try {
    // ---------- DASH-006 ----------
    await rt.go(page, '#/', 5000);
    await rt.shot(page, DIR, 'm0-dashboard');
    res.dashboard = (await rt.semantics(page)).map(lbl).filter((s) => /^(Open|Kept|Broken)\b/.test(s));

    // ---------- DET-006 ----------
    await rt.go(page, `#/payments/${S.P1}?tab=promises`, 5000);
    await rt.shot(page, DIR, 'm1-payment-promises-tab');
    const pn = (await rt.semantics(page)).map(lbl);
    res.paymentPromisesTab = { hash: await hash(page), promiseNodes: pn.filter((s) => /PR[12] on V|Promise|₹100|₹60/.test(s)).slice(0, 12) };

    // ---------- HUI-007 ----------
    await rt.go(page, `#/invoices/${S.V1}?tab=history`, 5000);
    await rt.shot(page, DIR, 'm2-invoice-history');
    let nodes = await rt.semantics(page);
    fs.writeFileSync(path.join(DIR, 'sem-history.json'), JSON.stringify(nodes, null, 1));
    const denied = nodes.find((n) => /Dispute denied/i.test(lbl(n)));
    res.historyRow = { node: denied && { role: denied.role, label: lbl(denied).slice(0, 160), x: denied.x, y: denied.y, w: denied.w, h: denied.h },
      separateLinkNode: nodes.filter((n) => new RegExp(`^Dispute #${S.D1}$`).test(lbl(n))).map((n) => `${n.role}@${n.x},${n.y}`) };
    if (denied) {
      // Activate the row the way assistive tech does: a click event on its semantics node.
      await page.locator(`flt-semantics[data-rt="${denied.i}"]`).dispatchEvent('click');
      await page.waitForTimeout(2500);
      res.historyRow.hashAfterActivate = await hash(page);
      await rt.enableSemantics(page);
      await rt.shot(page, DIR, 'm3-after-row-activate');
      const after = (await rt.semantics(page)).map(lbl);
      res.historyRow.expandedTextSeen = after.some((s) => /adminNotes|VD1 denied by admin|After|Before/.test(s));
    }
    // Control: the same row activated by a real mouse click on its title (left side, away from the link)
    await rt.go(page, `#/invoices/${S.V1}?tab=history`, 5000);
    nodes = await rt.semantics(page);
    const d2 = nodes.find((n) => /Dispute denied/i.test(lbl(n)));
    if (d2) {
      await rt.clickAt(page, d2.x - Math.round(d2.w / 2) + 120, d2.y - 12, 2000);
      res.historyRow.mouseTitleClickHash = await hash(page);
      await rt.shot(page, DIR, 'm4-mouse-title-click');
    }

    // ---------- DET-016 ----------
    res.malformed = {};
    for (const h of ['#/invoices/abc', '#/customers/abc', '#/payments/abc', '#/invoices/12abc', '#/invoices/99999999']) {
      await rt.go(page, h, 3500);
      const name = 'm5-' + h.replace(/[#/]/g, '_');
      await rt.shot(page, DIR, name);
      const t = (await rt.semantics(page)).map(lbl);
      res.malformed[h] = { hash: await hash(page), texts: t.slice(0, 25), shot: name };
    }
  } catch (e) {
    res.error = String(e.stack || e);
  } finally {
    res.pageErrors = app.pageErrors.slice(0, 8);
    fs.writeFileSync(path.join(DIR, 'ui-misc.json'), JSON.stringify(res, null, 2));
    console.log(JSON.stringify(res, null, 2));
    await app.close();
  }
})();
