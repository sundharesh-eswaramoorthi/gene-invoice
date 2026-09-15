// D-04 follow-up: dashboard, list toolbars and the Record payment dialog at 1366x900. Writes d04b.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  out['sem_' + name] = nodes.map(fmt);
  out['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}

(async () => {
  const T = await rt.adminToken();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  try {
    await rt.go(page, '#/', 4000);
    let n = await snap(page, 'd04b-01-dashboard');
    out.dashboardButtons = n.filter((x) => x.role === 'button').map(fmt);
    await rt.go(page, '#/invoices', 4000);
    n = await snap(page, 'd04b-02-invoices-list');
    out.invoicesToolbar = n.filter((x) => x.role === 'button' && x.y < 260).map(fmt);
    await rt.go(page, '#/payments', 4000);
    n = await snap(page, 'd04b-03-payments-list');
    out.paymentsToolbar = n.filter((x) => x.role === 'button' && x.y < 260).map(fmt);
    await rt.tap(page, 'Record payment', { wait: 2500 });
    n = await snap(page, 'd04b-04-record-payment-dialog');
    out.recordDialogButtons = n.filter((x) => x.role === 'button').map(fmt);
    await rt.tap(page, 'Cancel', { wait: 1500 });
    await rt.go(page, '#/customers', 4000);
    n = await snap(page, 'd04b-05-customers-list');
    out.customersToolbar = n.filter((x) => x.role === 'button' && x.y < 260).map(fmt);
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd04b-error');
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd04b.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(Object.fromEntries(Object.entries(out).filter(([k]) => !k.startsWith('sem_'))), null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
