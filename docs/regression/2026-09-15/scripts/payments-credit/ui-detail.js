// UI: Payment Details — edit notes/POC + Save, History, discard dialog, nav-away guard,
// allocation link, inactive-POC notes save, Promise tab, customer view.
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
const hash = (page) => page.evaluate(() => location.hash);

(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  let app = await rt.openApp({ token: admin });
  let page = app.page;
  const shot = (n) => rt.shot(page, __dirname, n).then((f) => note('shot', f));
  const notesBox = async () => {
    const i = (await inputs(page)).find((x) => x.tag === 'TEXTAREA');
    if (!i) throw new Error('no notes textarea: ' + JSON.stringify(await inputs(page)));
    await rt.clickAt(page, i.x, i.y, 400);
  };
  const pid = D.pay.id;

  await step('edit notes + save', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'ui notes v2', { clear: true });
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    await shot('e01-dirty');
    note('unsaved indicator', await has(page, /Unsaved changes/));
    const save = (await rt.semantics(page)).find((n) => n.label === 'Save changes');
    note('Save button node (viewport 900 high)', save && { x: save.x, y: save.y, h: save.h });
    // Can a user see Save? scroll the top section.
    await page.mouse.move(800, 300);
    await page.mouse.wheel(0, 300);
    await page.waitForTimeout(800);
    await shot('e02-top-scrolled');
    await rt.enableSemantics(page);
    await rt.tap(page, 'Save changes', { wait: 2500 });
    await rt.enableSemantics(page);
    note('snackbar', await has(page, /Payment saved/));
    await shot('e03-saved');
    note('API notes', (await api('GET', `/api/payments/${pid}`)).json.notes);
  });

  await step('change POC + save + history', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await rt.tap(page, /^Collection POC \*/, { wait: 2000 });
    await page.keyboard.type(D.pocU2.username + ' ', { delay: 30 });
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    await rt.tap(page, new RegExp('^' + D.pocU2.fullName), { wait: 1500 });
    note('POC field now', await has(page, /^Collection POC \*/));
    await rt.tap(page, 'Save changes', { wait: 2500 });
    const g = (await api('GET', `/api/payments/${pid}`)).json;
    note('API POC after save', { poc: g.collectionPoc?.username, expected: D.pocU2.username, notes: g.notes });
    await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
    await rt.enableSemantics(page);
    note('history rows', (await labels(page)).filter((l) => /updated|POC|recorded|Payment #|notes/i.test(l || '')).slice(0, 12));
    await shot('e04-history');
  });

  await step('discard dialog', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'discard me', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.enableSemantics(page);
    note('dialog', await has(page, /Discard unsaved changes|Keep editing|^Discard$/));
    await shot('e05-discard-dialog');
    await rt.tap(page, 'Keep editing', { wait: 1200 });
    note('after Keep editing: hash / textarea', { hash: await hash(page), notes: (await inputs(page)).find((x) => x.tag === 'TEXTAREA')?.value });
    await rt.tap(page, 'Back', { wait: 1500 });
    await rt.tap(page, 'Discard', { wait: 2500 });
    note('after Discard: hash', await hash(page));
    note('API notes after discard', (await api('GET', `/api/payments/${pid}`)).json.notes);
    await shot('e06-after-discard');
  });

  await step('candidate 15: sidebar nav while dirty', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await notesBox();
    await rt.typeText(page, 'lost edit', { clear: true });
    await page.waitForTimeout(500);
    await rt.clickAt(page, 104, 129, 2500); // sidebar "Invoices"
    await rt.enableSemantics(page);
    note('after clicking Invoices nav: hash / discard prompt', { hash: await hash(page), prompt: await has(page, /Discard unsaved/) });
    await shot('e07-nav-away-dirty');
    note('API notes (edit lost?)', (await api('GET', `/api/payments/${pid}`)).json.notes);
  });

  await step('allocation row opens invoice', async () => {
    await rt.go(page, `#/payments/${pid}`);
    await rt.tap(page, new RegExp('^' + D.I1.invoiceNumber), { wait: 3000 });
    await rt.enableSemantics(page);
    note('after allocation click: hash', { hash: await hash(page), expected: `#/invoices/${D.I1.id}`, title: await has(page, new RegExp(D.I1.invoiceNumber)) });
    await shot('e08-allocation-to-invoice');
  });

  await step('inactive POC: notes-only save', async () => {
    await rt.go(page, `#/payments/${D.payInactive.id}`);
    await shot('e09-inactive-poc-detail');
    note('POC field', await has(page, /^Collection POC/));
    await notesBox();
    await rt.typeText(page, 'note after poc left', { clear: true });
    await page.waitForTimeout(500);
    await rt.tap(page, 'Save changes', { wait: 2500 });
    await rt.enableSemantics(page);
    note('error / snackbar', await has(page, /inactive|cannot be assigned|Payment saved/));
    await shot('e10-inactive-poc-save');
    note('API notes', (await api('GET', `/api/payments/${D.payInactive.id}`)).json.notes);
  });

  await step('Payment Promise tab (candidate 37)', async () => {
    await rt.go(page, `#/payments/${pid}?tab=promises`, 4500);
    await rt.enableSemantics(page);
    const linked = (await api('GET', `/api/promises?customerId=${D.cu.id}&size=50`)).json.content
      .map((p) => ({ id: p.id, status: p.status, amount: p.amount, linkedToThisPayment: (p.payments || []).some((x) => x.id === pid) }));
    note('customer promises (API)', linked);
    note('promise rows on tab', (await labels(page)).filter((l) => /₹|promise|Promise/.test(l || '')).slice(0, 12));
    await shot('e11-promise-tab');
  });
  note('admin apiErrors', app.apiErrors);
  note('admin pageErrors', app.pageErrors);
  await app.close();

  await step('customer login view (AC-C7)', async () => {
    const tok = await rt.login(D.cu.username, D.cu.password);
    app = await rt.openApp({ token: tok });
    page = app.page;
    await rt.go(page, `#/payments/${pid}`, 4500);
    await rt.enableSemantics(page);
    note('customer view labels', (await labels(page)).slice(0, 40));
    note('customer view inputs', await inputs(page));
    await rt.shot(page, __dirname, 'e12-customer-view').then((f) => note('shot', f));
    note('customer apiErrors', app.apiErrors);
    await app.close();
  });

  fs.writeFileSync(path.join(__dirname, 'ui-detail-log.json'), JSON.stringify(log, null, 2));
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
