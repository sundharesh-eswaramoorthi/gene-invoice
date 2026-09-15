// Verifier: DET-020 (invoice header at 400px).
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const res = {};
(async () => {
  const token = await rt.adminToken();
  const app = await rt.openApp({ token, width: 400, height: 850 });
  const { page } = app;
  try {
    await rt.go(page, `#/invoices/${S.V1}`, 5000);
    await rt.shot(page, DIR, 'x0-invoice-400');
    const nodes = await rt.semantics(page);
    res.header = nodes.filter((n) => /INV-|Raise dispute|Partially|Unpaid|History|Disputes|Payment Promise/.test(`${n.label || ''} ${n.text || ''}`))
      .map((n) => ({ role: n.role, t: `${n.label || n.text}`.slice(0, 60), x: n.x, y: n.y, w: n.w, h: n.h }));
    await rt.go(page, `#/payments/${S.P1}`, 5000);
    await rt.shot(page, DIR, 'x1-payment-400');
    await rt.go(page, `#/customers/${S.cust.id}`, 5000);
    await rt.shot(page, DIR, 'x2-customer-400');
  } catch (e) {
    res.error = String(e.stack || e);
  } finally {
    fs.writeFileSync(path.join(DIR, 'ui-mobile.json'), JSON.stringify(res, null, 2));
    console.log(JSON.stringify(res, null, 2));
    await app.close();
  }
})();
