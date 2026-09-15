// UI: Record payment dialog — validation, promise/invoice linking, POC pre-fill and edit.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const log = [];
const note = (k, v) => { log.push([k, v]); console.log('*', k, typeof v === 'string' ? v : JSON.stringify(v)); };
const inputs = (page) => page.$$eval('input, textarea', (els) => els.map((e) => {
  const r = e.getBoundingClientRect();
  return { tag: e.tagName, label: e.getAttribute('aria-label'), value: e.value, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
}));
const labels = async (page) => (await rt.semantics(page)).map((n) => n.label || n.text);
const has = async (page, re) => (await labels(page)).filter((l) => re.test(l || ''));
const step = async (name, fn) => { try { await fn(); } catch (e) { note(`STEP ERROR ${name}`, e.message.slice(0, 600)); } };

(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const clickInput = async (label) => {
    const i = (await inputs(page)).find((x) => x.label === label);
    if (!i) throw new Error('no input ' + label + ' in ' + JSON.stringify(await inputs(page)));
    await rt.clickAt(page, i.x, i.y, 400);
  };
  const shot = (n) => rt.shot(page, __dirname, n).then((f) => note('shot', f));
  const pickPoc = async (user) => {
    await rt.tap(page, /^Collection POC \*/, { wait: 2000 });
    await page.keyboard.type(user.username, { delay: 30 });
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    const vis = await has(page, /^UIPC|^[A-Z0-9-]+\n@/);
    note('picker list after typing full username (candidate 14)', vis.slice(0, 8));
    await shot(`d-picker-${user.username}`);
    const found = (await has(page, new RegExp(user.fullName))).length > 0 && vis.length <= 2;
    if (!found) {
      // nudge: one more keystroke forces the dialog to rebuild with the debounced query
      await page.keyboard.type(' ');
      await page.waitForTimeout(1200);
      await rt.enableSemantics(page);
      note('picker list after an extra keystroke', (await has(page, /@/)).slice(0, 8));
    }
    await rt.tap(page, new RegExp('^' + user.fullName), { wait: 1500 });
  };

  await rt.go(page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);
  const sumBefore = (await api('GET', `/api/payments/summary?filter=customerId:eq:${D.cu.id}`)).json;

  await step('open + select customer', async () => {
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await rt.tap(page, 'Customer *', { wait: 2000 });
    await rt.tap(page, D.cu.name, { wait: 3500 });
    await rt.enableSemantics(page);
    await shot('d01-customer-selected');
    note('POC field', await has(page, /^Collection POC \*/));
    note('open promises section', await has(page, /Open promises|by .* • (Open|Partially)/));
    note('invoice checkboxes', await has(page, /^INV-/));
  });

  await step('amount validation', async () => {
    await rt.tap(page, 'Record', { role: 'button', wait: 1200 });
    note('error with empty amount', await has(page, /positive amount|required|Pick a customer/));
    await shot('d02-empty-amount');
    await clickInput('Amount *');
    await rt.typeText(page, '0', { clear: true });
    await rt.tap(page, 'Record', { role: 'button', wait: 1200 });
    note('error with amount 0', await has(page, /positive amount/));
    await clickInput('Amount *');
    await rt.typeText(page, '-5', { clear: true });
    await rt.tap(page, 'Record', { role: 'button', wait: 1200 });
    note('error with amount -5', await has(page, /positive amount/));
    await shot('d03-negative-amount');
  });

  await step('record with promise + invoice', async () => {
    await rt.tap(page, new RegExp('^' + D.I3.invoiceNumber), { role: 'checkbox', wait: 800 });
    await rt.tap(page, /₹77\.00 by/, { role: 'checkbox', wait: 800 });
    await clickInput('Amount *');
    await rt.typeText(page, '77', { clear: true });
    await clickInput('Notes');
    await rt.typeText(page, 'ui recorded', { clear: true });
    await shot('d04-filled');
    note('checked boxes', (await rt.semantics(page)).filter((n) => n.role === 'checkbox').map((n) => n.label));
    await rt.tap(page, 'Record', { role: 'button', wait: 3500 });
    await rt.enableSemantics(page);
    await shot('d05-after-record');
    note('dialog still open?', (await has(page, /^Record payment$/)).length > 0 && (await has(page, /^Customer \*/)).length > 0);
    note('rows now', (await rt.semantics(page)).filter((n) => n.role === 'cell' && /^#\d+/.test(n.label || n.text)).map((n) => n.label || n.text));
    const list = (await api('GET', `/api/payments?filter=customerId:eq:${D.cu.id}&sort=id,desc&size=10`)).json.content;
    const p = list.find((x) => x.notes === 'ui recorded');
    note('API new payment', p && { id: p.id, amount: p.amount, alloc: p.invoices.map((i) => [i.invoiceNumber, i.allocatedAmount]), poc: p.collectionPoc?.username, method: p.method });
    const pr = (await api('GET', `/api/promises/${D.promScoped.id}`)).json;
    note('API scoped promise after', { status: pr.status, fulfilled: pr.fulfilledAmount, payments: (pr.payments || []).map((x) => x.id) });
    const sumAfter = (await api('GET', `/api/payments/summary?filter=customerId:eq:${D.cu.id}`)).json;
    note('summary before/after', { before: sumBefore, after: sumAfter });
    note('tiles on screen', (await rt.semantics(page)).filter((n) => n.y > 100 && n.y < 130).map((n) => n.label || n.text));
    D.uiPayment = p;
  });

  await step('candidate 36: POC picked before customer', async () => {
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await pickPoc(D.pocU2);
    note('POC after manual pick', await has(page, /^Collection POC \*/));
    await rt.tap(page, 'Customer *', { wait: 2000 });
    await rt.tap(page, D.cu.name, { wait: 3500 });
    await rt.enableSemantics(page);
    note('POC after then choosing the customer', await has(page, /^Collection POC \*/));
    await shot('d06-poc-then-customer');
  });

  await step('change POC after pre-fill and record', async () => {
    const cur = (await has(page, /^Collection POC \*/))[0] || '';
    if (!cur.includes(D.pocU2.fullName)) await pickPoc(D.pocU2);
    note('POC before record', await has(page, /^Collection POC \*/));
    await clickInput('Amount *');
    await rt.typeText(page, '3', { clear: true });
    await clickInput('Notes');
    await rt.typeText(page, 'ui poc2', { clear: true });
    await rt.tap(page, 'Record', { role: 'button', wait: 3500 });
    const list = (await api('GET', `/api/payments?filter=customerId:eq:${D.cu.id}&sort=id,desc&size=10`)).json.content;
    const p = list.find((x) => x.notes === 'ui poc2');
    note('API poc2 payment', p && { id: p.id, poc: p.collectionPoc?.username, expected: D.pocU2.username, alloc: p.invoices.map((i) => [i.invoiceNumber, i.allocatedAmount]) });
    await shot('d07-after-poc2');
  });

  note('apiErrors', app.apiErrors);
  note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-dialog-log.json'), JSON.stringify(log, null, 2));
  fs.writeFileSync(path.join(__dirname, 'ui-data.json'), JSON.stringify(D, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
