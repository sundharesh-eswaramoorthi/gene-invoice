// Phone-width (400x820) checks: UIS-01, 02, 06, 07, 08, 09, 14.
const rt = require('../lib.js');
const S = require('./setup.json');
const DIR = __dirname + '/phone';
const W = 400, H = 820;
const fmt = (n) => `[${n.role}] ${(n.label || n.text).slice(0, 60)} @(${n.x},${n.y}) w${n.w}`;

async function appBar(page) {
  return (await rt.semantics(page)).filter((n) => n.y < 56);
}

(async () => {
  // ---- UIS-01 / UIS-02: app bar + hamburger, per role --------------------------------
  const roles = [
    ['coll', () => rt.login(S.coll.username, S.password)],
    ['admin', () => rt.adminToken()],
    ['viewer', () => rt.login(S.viewer.username, S.password)],
    ['cust', () => rt.login(S.cust.username, S.password)],
  ];
  for (const [who, tok] of roles) {
    const app = await rt.openApp({ token: await tok(), width: W, height: H });
    const bar = await appBar(app.page);
    console.log(`UIS-01/02 ${who} app bar:`, bar.map(fmt).join(' | '));
    await rt.shot(app.page, DIR, `${who}-bar`);
    // Real mouse click on the hamburger position.
    await rt.clickAt(app.page, 28, 28, 1500);
    await rt.enableSemantics(app.page);
    const hash = await app.page.evaluate(() => location.hash);
    const nodes = await rt.semantics(app.page);
    const drawer = nodes.some((n) => (n.label || n.text) === 'Invoices' && n.x < 300 && n.y > 56 && n.h < 70);
    await rt.shot(app.page, DIR, `${who}-after-hamburger-click`);
    console.log(`UIS-01 ${who} after real click (28,28): hash=${JSON.stringify(hash)} drawerOpen=${drawer}`);
    await app.close();
  }

  // ---- admin phone screens --------------------------------------------------------------
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin, width: W, height: H });
  const p = app.page;

  // UIS-08 customers cards
  await rt.go(p, '#/customers');
  await rt.shot(p, DIR, 'admin-customers');
  const cust = (await rt.semantics(p)).filter((n) => /POC missing|Open|Raise promise/.test(n.label || n.text || ''));
  console.log('UIS-08 customers nodes:', cust.slice(0, 8).map(fmt).join(' | '));

  // UIS-09 invoices list scroll
  await rt.go(p, '#/invoices');
  await rt.shot(p, DIR, 'admin-invoices-top');
  await p.mouse.move(200, 600);
  for (let i = 0; i < 12; i++) { await p.mouse.wheel(0, 600); await p.waitForTimeout(150); }
  await p.waitForTimeout(800);
  await rt.shot(p, DIR, 'admin-invoices-scrolled');
  await rt.enableSemantics(p);
  const inv = await rt.semantics(p);
  console.log('UIS-09 invoices after scroll:', inv.filter((n) => n.y > 56).map(fmt).slice(0, 30).join(' | '));

  // UIS-06 invoice header, UIS-14 raise dispute dialog
  await rt.go(p, `#/invoices/${S.inv1.id}`);
  await rt.shot(p, DIR, 'admin-invoice-top');
  console.log('UIS-06 invoice header nodes:', (await rt.semantics(p)).filter((n) => n.y < 260).map(fmt).join(' | '));
  await rt.tap(p, 'Raise dispute');
  await rt.shot(p, DIR, 'admin-raise-dispute-dialog');
  console.log('UIS-14 dialog nodes:', (await rt.semantics(p)).map(fmt).join(' | ').slice(0, 1500));
  await rt.tap(p, 'Cancel');

  await rt.go(p, `#/invoices/${S.inv2.id}`);
  await rt.shot(p, DIR, 'admin-invoice2-top');
  await rt.go(p, `#/payments/${S.pay.id}`);
  await rt.shot(p, DIR, 'admin-payment-top');

  // UIS-07 raise promise dialog
  await rt.go(p, `#/customers/${S.cust.id}`);
  await rt.tap(p, /Payment Promise/, { role: 'tab' }).catch(async () => rt.tap(p, /Promise/));
  await p.waitForTimeout(1500);
  await rt.enableSemantics(p);
  await rt.tap(p, 'Raise promise');
  await p.waitForTimeout(1500);
  await rt.shot(p, DIR, 'admin-raise-promise-dialog');
  console.log('UIS-07 dialog nodes:', (await rt.semantics(p)).map(fmt).join(' | ').slice(0, 1500));
  console.log('api', JSON.stringify(app.apiErrors), 'errs', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
