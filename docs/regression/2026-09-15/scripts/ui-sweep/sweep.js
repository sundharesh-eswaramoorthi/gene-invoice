// Usage: node sweep.js <role> <desktop|phone>
// role: admin | cashier | viewer | sales | coll | cust | login
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = require('./setup.json');

const role = process.argv[2];
const wkey = process.argv[3] || 'desktop';
const [W, H] = wkey === 'phone' ? [400, 820] : [1366, 900];
const DIR = path.join(__dirname, `${role}-${wkey}`);
fs.mkdirSync(DIR, { recursive: true });

const SUSPICIOUS = /error|could not|failed|exception|denied|permission|unable|\bnull\b|NaN|undefined|Instance of|minified|TypeError|RangeError/i;

const sidebar = {
  admin: ['/', '/invoices', '/payments', '/promises', '/disputes', '/customers', '/products', '/users', '/roles', '/notifications'],
  cashier: ['/', '/invoices', '/payments', '/promises', '/customers', '/products', '/notifications'],
  viewer: ['/', '/invoices', '/payments', '/promises', '/customers', '/products', '/notifications'],
  sales: ['/', '/invoices', '/payments', '/promises', '/disputes', '/customers', '/products', '/notifications'],
  coll: ['/', '/invoices', '/payments', '/promises', '/disputes', '/customers', '/notifications'],
  cust: ['/', '/invoices', '/payments', '/promises', '/disputes', '/notifications'],
};
const hasDisputes = ['admin', 'sales', 'coll', 'cust'].includes(role);
const dialogs = {
  newCustomer: ['admin', 'cashier'].includes(role),
  recordPayment: ['admin', 'cashier', 'coll'].includes(role),
  raisePromise: ['admin', 'coll'].includes(role),
  raiseDispute: ['admin', 'cust'].includes(role),
  newInvoice: ['admin', 'cashier', 'sales'].includes(role),
};

async function tokenFor(r) {
  if (r === 'admin') return rt.adminToken();
  if (r === 'cashier') return rt.cashierToken();
  const u = { viewer: S.viewer, sales: S.sales, coll: S.coll, cust: S.cust }[r];
  return rt.login(u.username, S.password);
}

const log = [];
let app;
let apiIdx = 0;
let errIdx = 0;

async function record(name, extra = {}) {
  const page = app.page;
  let nodes = [];
  try { nodes = await rt.semantics(page); } catch (e) { nodes = []; }
  const file = await rt.shot(page, DIR, name);
  const newApi = app.apiErrors.slice(apiIdx);
  const newErr = app.pageErrors.slice(errIdx);
  apiIdx = app.apiErrors.length;
  errIdx = app.pageErrors.length;
  const texts = nodes.map((n) => `[${n.role || ''}] ${(n.label || n.text || '').replace(/\s+/g, ' ')}`);
  const suspicious = texts.filter((t) => SUSPICIOUS.test(t));
  const hash = await page.evaluate(() => location.hash);
  const entry = { name, hash, shot: file, apiErrors: newApi, pageErrors: newErr, suspicious, texts: texts.slice(0, 120), ...extra };
  log.push(entry);
  console.log(`${name} ${hash} api=${JSON.stringify(newApi)} errs=${newErr.length} susp=${JSON.stringify(suspicious).slice(0, 300)}`);
  return entry;
}

async function scrollBy(dy) {
  await app.page.mouse.move(W / 2, H * 0.7);
  await app.page.mouse.wheel(0, dy);
  await app.page.waitForTimeout(900);
  await rt.enableSemantics(app.page);
}

async function closeDialog() {
  try { await rt.tap(app.page, 'Cancel', { wait: 1000 }); } catch {
    await app.page.keyboard.press('Escape');
    await app.page.waitForTimeout(1000);
  }
}

async function tabsOf() {
  const nodes = await rt.semantics(app.page);
  return nodes.filter((n) => n.role === 'tab');
}

/** Opens a detail page and screenshots the top plus every tab. */
async function detail(kind, hash) {
  await rt.go(app.page, hash, 4500);
  await record(`${kind}-top`);
  if (wkey === 'phone') {
    await scrollBy(400);
    await record(`${kind}-top-scrolled`);
  }
  let tabs = await tabsOf();
  if (!tabs.length && wkey === 'phone') { await scrollBy(1500); tabs = await tabsOf(); }
  const labels = tabs.map((t) => t.label || t.text);
  for (const lab of labels) {
    try {
      await rt.tap(app.page, lab, { role: 'tab', wait: 2500 });
      if (wkey === 'phone') await scrollBy(1500);
      await record(`${kind}-tab-${(lab || 'x').split(/\s|\n/)[0].replace(/[^A-Za-z]/g, '')}`, { tabLabel: lab });
    } catch (e) {
      log.push({ name: `${kind}-tab-${lab}`, failure: String(e).slice(0, 400) });
      console.log('TAB FAIL', kind, lab, String(e).slice(0, 300));
    }
  }
  return labels;
}

async function openDialog(name, hash, buttonLabel, pre) {
  try {
    await rt.go(app.page, hash, 4500);
    if (pre) await pre();
    await rt.tap(app.page, buttonLabel, { wait: 2500 });
    await record(`dialog-${name}`);
    if (wkey === 'phone') {
      await scrollBy(1200);
      await record(`dialog-${name}-scrolled`);
    }
    await closeDialog();
    await record(`dialog-${name}-closed`);
  } catch (e) {
    log.push({ name: `dialog-${name}`, failure: String(e).slice(0, 600) });
    console.log('DIALOG FAIL', name, String(e).slice(0, 400));
    try { await record(`dialog-${name}-fail`); } catch {}
  }
}

(async () => {
  if (role === 'login') {
    app = await rt.openApp({ width: W, height: H });
    await record('login');
    await app.close();
    fs.writeFileSync(path.join(DIR, 'log.json'), JSON.stringify(log, null, 2));
    return;
  }
  const token = await tokenFor(role);
  app = await rt.openApp({ token, width: W, height: H });
  await record('boot');

  if (wkey === 'phone') {
    // The hamburger drawer must work on phone.
    try {
      await rt.tap(app.page, /navigation menu/i, { wait: 1500 });
      await record('drawer-open');
      const target = sidebar[role][1] === '/invoices' ? 'Invoices' : 'Invoices';
      await rt.tap(app.page, target, { wait: 3000 });
      await rt.enableSemantics(app.page);
      await record('drawer-navigated', { expectHash: '#/invoices' });
    } catch (e) {
      log.push({ name: 'drawer', failure: String(e).slice(0, 600) });
      console.log('DRAWER FAIL', String(e).slice(0, 400));
    }
  }

  for (const p of sidebar[role]) {
    await rt.go(app.page, '#' + p, 4500);
    const nm = 'screen-' + (p === '/' ? 'dashboard' : p.slice(1));
    await record(nm);
    if (['/invoices', '/payments', '/customers', '/promises', '/products', '/users', '/roles', '/disputes', '/notifications'].includes(p)) {
      await scrollBy(3000);
      await record(nm + '-bottom');
    }
  }

  // Detail screens
  if (role !== 'cust') await detail('customer', `#/customers/${S.cust.id}`);
  else await detail('customer', `#/customers/${S.cust.id}`);
  await detail('invoice', `#/invoices/${S.inv2.id}`);
  await detail('payment', `#/payments/${S.pay.id}`);
  if (hasDisputes) {
    await rt.go(app.page, `#/disputes/${S.disp.id}`, 4500);
    await record('dispute-detail');
  }

  if (dialogs.newInvoice) {
    await rt.go(app.page, '#/invoices/new', 4500);
    await record('invoice-new');
    await scrollBy(1500);
    await record('invoice-new-bottom');
  }
  if (dialogs.newCustomer) await openDialog('newCustomer', '#/customers', 'New customer');
  if (dialogs.recordPayment) await openDialog('recordPayment', '#/payments', 'Record payment');
  if (dialogs.raiseDispute) await openDialog('raiseDispute', `#/invoices/${S.inv1.id}`, 'Raise dispute');
  if (dialogs.raisePromise) {
    await openDialog('raisePromise', `#/customers/${S.cust.id}?tab=promises`, 'Raise promise', async () => {
      if (wkey === 'phone') await scrollBy(1500);
    });
  }

  await app.close();
  fs.writeFileSync(path.join(DIR, 'log.json'), JSON.stringify(log, null, 2));
  console.log('DONE', role, wkey, 'totalApiErrors', app.apiErrors.length, 'pageErrors', app.pageErrors.length);
})().catch(async (e) => {
  console.error('SWEEP FAILED', e);
  fs.writeFileSync(path.join(DIR, 'log.json'), JSON.stringify(log, null, 2));
  try { await app?.close(); } catch {}
  process.exit(1);
});
