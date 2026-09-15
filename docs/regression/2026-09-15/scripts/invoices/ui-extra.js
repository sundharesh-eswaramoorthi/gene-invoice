// UI extras: SALES_POC self-preselect on New invoice; detail-to-detail navigation keeps no stale state.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const log = (...a) => console.log(...a);
const txt = (n) => n.label || n.text || '';

(async () => {
  const A = await rt.adminToken();
  // --- SALES_POC preselect ---
  const salesT = await rt.login(S.sales.username, S.sales.password);
  const assignable = await rt.api('GET', '/api/pocs/assignable?type=SALES', { token: salesT });
  const list = Array.isArray(assignable.json) ? assignable.json : (assignable.json?.content || []);
  log('assignable SALES count returned', list.length, 'sales in list?', list.some((u) => u.id === S.sales.id), 'status', assignable.status);
  let app = await rt.openApp({ token: salesT });
  await rt.go(app.page, '#/invoices', 4000);
  await rt.go(app.page, '#/invoices/new', 5000);
  let nodes = await rt.semantics(app.page);
  log('SALES POC FIELD', JSON.stringify(nodes.filter((n) => /Sales POC/.test(txt(n))).map(txt)), 'expected', S.sales.fullName);
  log(await rt.shot(app.page, __dirname, 'x1-sales-new-form'));
  log('sales apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 300));
  await app.close();

  // --- detail -> detail navigation ---
  app = await rt.openApp({ token: A });
  const a = S.eInv[0].id, b = S.inv2.id;
  const ga = (await rt.api('GET', `/api/invoices/${a}`, { token: A })).json;
  const gb = (await rt.api('GET', `/api/invoices/${b}`, { token: A })).json;
  log('expected A', ga.invoiceNumber, JSON.stringify(ga.notes), ga.salesPoc?.fullName, '| B', gb.invoiceNumber, JSON.stringify(gb.notes), gb.salesPoc?.fullName);
  await rt.go(app.page, `#/invoices/${a}`, 5000);
  nodes = await rt.semantics(app.page);
  log('ON A', JSON.stringify(nodes.filter((n) => /INV-2026|Sales POC \*/.test(txt(n))).map(txt)));
  log(await rt.shot(app.page, __dirname, 'x2-detail-A'));
  await rt.go(app.page, `#/invoices/${b}`, 5000);
  nodes = await rt.semantics(app.page);
  log('ON B', JSON.stringify(nodes.filter((n) => /INV-2026|Sales POC \*/.test(txt(n))).map(txt)));
  log(await rt.shot(app.page, __dirname, 'x3-detail-B'));
  log('admin apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors).slice(0, 300));
  await app.close();
})().catch((e) => { console.error('ERR', e); process.exit(1); });
