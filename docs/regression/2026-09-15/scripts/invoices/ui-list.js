// UI: invoices list — tiles, row cancel dialog (Keep it / Cancel invoice), bulk cancel, export, narrow layout.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const log = (...a) => console.log(...a);
const labels = (nodes) => nodes.map((x) => `[${x.role}] ${JSON.stringify(x.label || x.text).slice(0, 110)} @${x.x},${x.y}`).join('\n');
const tapNode = async (page, n, wait = 1500) => { await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click'); await page.waitForTimeout(wait); };
const has = (nodes, re) => nodes.some((n) => re.test(`${n.label || ''} ${n.text || ''}`));

(async () => {
  const A = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: A, body });
  const cust = await rt.createCustomer(A, 'invL');
  const mk = async (qty) => (await api('POST', '/api/invoices', { customerId: cust.id, salesPocUserId: S.sales.id, notes: 'ui list', items: [{ productId: S.p2.id, quantity: qty }] })).json;
  const u1 = await mk(1), u2 = await mk(2), u3 = await mk(3), pd = await mk(4), c1 = await mk(5);
  await api('POST', '/api/payments', { customerId: cust.id, amount: 102, method: 'CASH', invoiceIds: [pd.id], collectionPocUserId: S.collector.id });
  await api('POST', `/api/invoices/${c1.id}/cancel`);
  log('fixtures', { cust: cust.id, u1: u1.invoiceNumber, u2: u2.invoiceNumber, u3: u3.invoiceNumber, pd: pd.invoiceNumber, c1: c1.invoiceNumber });

  const app = await rt.openApp({ token: A, width: 1920, height: 1000 });
  const { page } = app;
  const listUrl = `#/invoices?size=10&sort=invoiceNumber,asc&f=${encodeURIComponent(`customerId:eq:${cust.id}`)}`;
  await rt.go(page, listUrl, 5000);
  let nodes = await rt.semantics(page);
  log('== list\n' + labels(nodes));
  log(await rt.shot(page, __dirname, 'l1-list'));
  const rowY = (nodes, num) => nodes.find((n) => (n.label || n.text) === num)?.y;
  const inRow = (nodes, num, re) => { const y = rowY(nodes, num); return nodes.filter((n) => Math.abs(n.y - y) < 8 && re.test(n.label || n.text || '')); };

  // Raise promise offered on a cancelled row?
  log('CANCELLED ROW ACTIONS', JSON.stringify(inRow(nodes, c1.invoiceNumber, /Open|Raise promise|Cancel invoice/).map((n) => n.label)));
  log('PAID ROW ACTIONS', JSON.stringify(inRow(nodes, pd.invoiceNumber, /Open|Raise promise|Cancel invoice/).map((n) => n.label)));

  // ---- row cancel: Keep it ----
  let btn = inRow(nodes, u3.invoiceNumber, /^Cancel invoice$/)[0];
  await tapNode(page, btn);
  nodes = await rt.semantics(page);
  log('== dialog\n' + labels(nodes.filter((n) => /Cancel|Keep|undone/.test(n.label || n.text || ''))));
  log(await rt.shot(page, __dirname, 'l2-cancel-dialog'));
  await rt.tap(page, 'Keep it', { wait: 2000 });
  nodes = await rt.semantics(page);
  const hashAfterKeep = await page.evaluate(() => location.hash);
  const u3AfterKeep = (await api('GET', `/api/invoices/${u3.id}`)).json.status;
  log('KEEP IT -> dialogGone', !has(nodes, /^Keep it/), 'hash', hashAfterKeep, 'listVisible', !!rowY(nodes, u3.invoiceNumber), 'u3 status', u3AfterKeep);
  log(await rt.shot(page, __dirname, 'l3-after-keep'));

  // ---- row cancel: Cancel invoice ----
  btn = inRow(nodes, u3.invoiceNumber, /^Cancel invoice$/)[0];
  await tapNode(page, btn);
  nodes = await rt.semantics(page);
  const dlgBtns = nodes.filter((n) => (n.label || n.text) === 'Cancel invoice');
  log('dialog Cancel invoice buttons', JSON.stringify(dlgBtns.map((n) => [n.x, n.y, n.role])));
  await tapNode(page, dlgBtns[dlgBtns.length - 1], 3000);
  nodes = await rt.semantics(page);
  const u3After = (await api('GET', `/api/invoices/${u3.id}`)).json.status;
  const rowStatus = inRow(nodes, u3.invoiceNumber, /Cancelled|Unpaid/).map((n) => n.label || n.text);
  log('CANCEL -> u3 status', u3After, 'row shows', JSON.stringify(rowStatus), 'hash', await page.evaluate(() => location.hash), 'dialogGone', !has(nodes, /^Keep it/));
  log('tiles now', JSON.stringify(nodes.filter((n) => /Invoices|Unpaid|Outstanding|Total billed|Partially/.test(n.label || n.text || '') && n.y < 160).map((n) => n.label || n.text)));
  log(await rt.shot(page, __dirname, 'l4-after-cancel'));

  // ---- bulk cancel: u1, u2 (unpaid) + pd (fully paid) ----
  for (const inv of [u1, u2, pd]) {
    const y = rowY(nodes, inv.invoiceNumber);
    const cb = nodes.find((n) => n.role === 'checkbox' && Math.abs(n.y - y) < 8);
    if (cb) await tapNode(page, cb, 800); else await rt.clickAt(page, 290, y, 800);
    nodes = await rt.semantics(page);
  }
  log('== toolbar\n' + labels(nodes.filter((n) => n.y < 330)));
  log(await rt.shot(page, __dirname, 'l5-selected'));
  await rt.tap(page, /Cancel unpaid/, { wait: 1500 });
  nodes = await rt.semantics(page);
  const confirmText = nodes.filter((n) => /This will apply|undone|Cancel unpaid\?/.test(n.label || n.text || '')).map((n) => n.label || n.text);
  log('CONFIRM TEXT', JSON.stringify(confirmText));
  log(await rt.shot(page, __dirname, 'l6-bulk-confirm'));
  await rt.tap(page, 'Confirm', { wait: 3500 });
  nodes = await rt.semantics(page);
  const resultText = nodes.filter((n) => /succeeded|Failed|Skipped|#\d+|result/.test(n.label || n.text || '')).map((n) => n.label || n.text);
  log('RESULT TEXT', JSON.stringify(resultText));
  log(await rt.shot(page, __dirname, 'l7-bulk-result'));
  const st = await Promise.all([u1, u2, pd].map((x) => api('GET', `/api/invoices/${x.id}`)));
  log('AFTER BULK statuses', st.map((x) => x.json.status).join(','));
  try { await rt.tap(page, 'Close', { wait: 2000 }); } catch (e) { log('no Close', e.message.slice(0, 200)); }

  // ---- export one row ----
  nodes = await rt.semantics(page);
  const yPd = rowY(nodes, pd.invoiceNumber);
  const cbPd = nodes.find((n) => n.role === 'checkbox' && Math.abs(n.y - yPd) < 8);
  if (cbPd) await tapNode(page, cbPd, 800); else await rt.clickAt(page, 290, yPd, 800);
  nodes = await rt.semantics(page);
  log('== toolbar 2\n' + labels(nodes.filter((n) => n.y < 330)));
  try {
    await rt.tap(page, /Export/, { wait: 2500 });
    nodes = await rt.semantics(page);
    log('EXPORT DIALOG', JSON.stringify(nodes.filter((n) => /Exported CSV|Invoice #|Copy|Close/.test(n.label || n.text || '')).map((n) => (n.label || n.text).slice(0, 200))));
    log(await rt.shot(page, __dirname, 'l8-export'));
    await rt.tap(page, 'Close', { wait: 1000 });
  } catch (e) { log('EXPORT ERR', e.message.slice(0, 400)); }
  log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();

  // ---- narrow layout ----
  const nar = await rt.openApp({ token: A, width: 400, height: 820 });
  await rt.go(nar.page, listUrl, 5000);
  nodes = await rt.semantics(nar.page);
  log('== narrow\n' + labels(nodes));
  log(await rt.shot(nar.page, __dirname, 'l9-narrow'));
  await nar.page.mouse.wheel(0, 3000); await nar.page.waitForTimeout(1200);
  log(await rt.shot(nar.page, __dirname, 'l10-narrow-scrolled'));
  log('narrow apiErrors', JSON.stringify(nar.apiErrors), 'pageErrors', JSON.stringify(nar.pageErrors));
  await nar.close();
  fs.writeFileSync(path.join(__dirname, 'state-list.json'), JSON.stringify({ cust, u1, u2, u3, pd, c1 }, null, 2));
})().catch((e) => { console.error('ERR', e); process.exit(1); });
