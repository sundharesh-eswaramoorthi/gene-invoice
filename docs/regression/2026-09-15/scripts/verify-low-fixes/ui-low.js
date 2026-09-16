// Browser checks for the low-severity UI fixes that can be read off the screen: D-66, D-67,
// D-69, D-71. The rest (D-51, D-58, D-60, D-61, D-64) are layout changes, so this script only
// collects screenshots of them at 1366 and 400 for a human to look at.
// Run: node ui-low.js → prints a summary, writes ui-low.json and shots/ next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const DIR = __dirname;
const out = { checks: [], shots: [] };
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();

function check(id, defect, name, ok, detail) {
  out.checks.push({ id, defect, name, status: ok ? 'PASS' : 'FAIL', detail });
}

async function snap(page, name) {
  await rt.enableSemantics(page);
  const nodes = await rt.semantics(page);
  // rt.shot puts files in a shots/ folder under the directory it is given.
  await rt.shot(page, DIR, name);
  out.shots.push(name);
  return nodes;
}

(async () => {
  const admin = await rt.adminToken();

  // A staff account that may see payments but not record them (D-67), and a dispute to open (D-66).
  const viewer = await rt.createStaff(admin, 'VIEWER', 'lo-ui-viewer');
  const viewerToken = await rt.login(viewer.username, viewer.password);
  const sales = await rt.createStaff(admin, 'SALES_POC', 'lo-ui-sales');
  const cust = await rt.createCustomer(admin, 'lo-ui-cust');
  const prod = (await rt.api('GET', '/api/products?size=10&filter=active:eq:true', { token: admin }))
    .json.content[0];
  const inv = (await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: cust.id, salesPocUserId: sales.id, items: [{ productId: prod.id, quantity: 1, unitPrice: 100 }] },
  })).json;
  const custToken = await rt.login(cust.username, cust.password);
  const dispute = (await rt.api('POST', '/api/disputes', {
    token: custToken,
    body: { targetType: 'INVOICE', targetId: inv.id, reason: 'lo-ui dispute for the detail screen' },
  })).json;

  // ---- admin at 1366 -----------------------------------------------------------------------
  {
    const app = await rt.openApp({ token: admin, width: 1366, height: 900 });
    try {
      const page = app.page;

      // D-69: a page past the end names no page.
      await rt.go(page, '#/invoices');
      await rt.go(page, '#/invoices?page=99', 4500);
      let nodes = await snap(page, 'lo-69-past-the-end-1366');
      const pagerTexts = nodes.map(lab).filter((t) => /page|pages/i.test(t));
      check('L-UI-01', 'D-69', 'past the last page the pager gives the page count, not a page that does not exist',
        pagerTexts.some((t) => /\d+ pages/.test(t)) && !pagerTexts.some((t) => /Page 100 of/.test(t)),
        { pagerTexts: pagerTexts.slice(0, 6) });

      // D-71: an unknown path keeps the app shell.
      await rt.go(page, '#/admin/disputes/1', 4000);
      nodes = await snap(page, 'lo-71-unknown-path-1366');
      const texts = nodes.map(lab);
      check('L-UI-02', 'D-71', 'the "does not exist" page keeps the sidebar and top bar',
        texts.some((t) => /That page does not exist/.test(t))
          // The rail's labels read "Dashboard\nTab 1 of 9" in the accessibility tree.
          && texts.some((t) => /Dashboard/.test(t))
          && texts.some((t) => /Invoices/.test(t)),
        { found: texts.filter((t) => /Dashboard|Invoices|does not exist/.test(t)).slice(0, 6) });

      // D-66: no developer wording on dispute detail.
      await rt.go(page, `#/disputes/${dispute.id}`, 4000);
      nodes = await snap(page, 'lo-66-dispute-detail-1366');
      const disputeTexts = nodes.map(lab);
      check('L-UI-03', 'D-66', 'dispute detail says "Proposed change", not "Proposed change (JSON)"',
        disputeTexts.some((t) => /Proposed change/.test(t))
          && !disputeTexts.some((t) => /Proposed change \(JSON\)/.test(t)),
        { matches: disputeTexts.filter((t) => /Proposed change|history/i.test(t)).slice(0, 4) });

      // D-64 and D-51: layout only — screenshots.
      await rt.go(page, `#/customers/${cust.id}`, 4000);
      await snap(page, 'lo-64-customer-detail-1366');
      await rt.go(page, '#/customers', 4000);
      await snap(page, 'lo-51-customers-1366');
    } finally {
      out.apiErrors_admin = app.apiErrors;
      await app.close();
    }
  }

  // ---- a viewer at 1366 --------------------------------------------------------------------
  {
    const app = await rt.openApp({ token: viewerToken, width: 1366, height: 900 });
    try {
      const page = app.page;
      await rt.go(page, '#/', 4000);
      const nodes = await snap(page, 'lo-67-dashboard-viewer-1366');
      const texts = nodes.map(lab);
      check('L-UI-04', 'D-67', 'staff without PAYMENT_MANAGE are offered "All payments"',
        texts.some((t) => /All payments/.test(t)) && !texts.some((t) => /My payments/.test(t)),
        { buttons: texts.filter((t) => /payments/i.test(t)).slice(0, 4) });

      // D-58: POC seats read-only — screenshot.
      await rt.go(page, `#/customers/${cust.id}`, 4000);
      await snap(page, 'lo-58-poc-chips-viewer-1366');
    } finally {
      out.apiErrors_viewer = app.apiErrors;
      await app.close();
    }
  }

  // ---- phone width: layout screenshots (D-60, D-61) ----------------------------------------
  {
    const app = await rt.openApp({ token: admin, width: 400, height: 780 });
    try {
      const page = app.page;
      await rt.go(page, '#/invoices', 5000);
      await snap(page, 'lo-61-invoices-400');
      await rt.go(page, '#/promises', 5000);
      await snap(page, 'lo-61-promises-400');
      await rt.go(page, `#/customers/${cust.id}?tab=promises`, 5000);
      await snap(page, 'lo-60-customer-promises-400');
    } finally {
      out.apiErrors_phone = app.apiErrors;
      await app.close();
    }
  }

  fs.writeFileSync(path.join(DIR, 'ui-low.json'), JSON.stringify(out, null, 2));
  for (const c of out.checks) console.log(`${c.status.padEnd(5)} ${c.id} ${c.defect} ${c.name}`);
  const bad = out.checks.filter((c) => c.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
  console.log(`\nScreenshots for the layout-only fixes: ${out.shots.length} in shots/`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
