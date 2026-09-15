// Regression: admin bulk "Cancel unpaid" on a test invoice (row checkbox clicked by position,
// since row checkboxes are not exposed in the semantics tree). Writes r1c.json.
const rt = require('../../lib.js');
const C = require('./common.js');
const S = C.state();
const out = { R1c: {} };
const snap = C.recorder(out);
const o = out.R1c;
(async () => {
  const T = await rt.adminToken();
  o.statusBefore = (await rt.api('GET', `/api/invoices/${S.invBulk.id}`, { token: T })).json?.status;
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  try {
    await rt.go(page, '#/invoices', 3500);
    await rt.go(page, `#/invoices?f=customerId:eq:${S.cBulk.id}`, 5000);
    let n = await snap(page, 'R1c', 'r1c-1-list');
    const cell = n.find((x) => x.role === 'cell' && C.lab(x) === S.invBulk.invoiceNumber);
    o.cell = C.fmt(cell);
    await rt.clickAt(page, 290, cell.y, 1500);
    await rt.enableSemantics(page);
    n = await snap(page, 'R1c', 'r1c-2-selected');
    o.toolbar = C.has(n, /selected|Cancel unpaid|Export selected|Reassign|Clear/);
    await rt.tap(page, 'Cancel unpaid', { wait: 2000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1c', 'r1c-3-confirm');
    o.confirm = n.map(C.lab).filter((t) => /cancel|confirm|invoice/i.test(t)).slice(0, 8);
    await rt.tap(page, 'Confirm', { wait: 4000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1c', 'r1c-4-result');
    o.result = n.map(C.lab).filter((t) => /cancel|succeed|skipp|fail|done|invoice|OK|Close|Unpaid/i.test(t)).slice(0, 12);
    o.statusAfter = (await rt.api('GET', `/api/invoices/${S.invBulk.id}`, { token: T })).json?.status;
  } catch (e) {
    o.error = String(e.stack || e);
    await rt.shot(page, C.DIR, 'r1c-error');
  } finally {
    o.apiErrors = app.apiErrors;
    o.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  C.write('r1c.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
