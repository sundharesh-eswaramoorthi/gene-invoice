// Desktop checks: UIS-03, 04, 05, 10, 11, 12, 13, 15, 16, 17, 18.
const rt = require('../lib.js');
const S = require('./setup.json');
const DIR = __dirname + '/desktop';
const fmt = (n) => `[${n.role}] ${(n.label || n.text).slice(0, 50)} @(${n.x},${n.y}) w${n.w}`;

async function offscreen(page, width) {
  const nodes = await rt.semantics(page);
  return nodes.filter((n) => n.x > width);
}

(async () => {
  const admin = await rt.adminToken();

  // ---- UIS-03: off-screen columns at 1920 and 1366 -------------------------------------
  for (const width of [1920, 1366]) {
    const app = await rt.openApp({ token: admin, width, height: 900 });
    for (const screen of ['#/invoices', '#/users', '#/customers']) {
      await rt.go(app.page, screen);
      const off = await offscreen(app.page, width);
      const labels = [...new Set(off.map((n) => n.label || n.text))];
      console.log(`UIS-03 admin ${width} ${screen}: ${off.length} nodes beyond x=${width}; labels: ${labels.slice(0, 12).join(', ')}; min x ${off.length ? Math.min(...off.map((n) => n.x)) : '-'}`);
      await rt.shot(app.page, DIR, `admin-${width}-${screen.slice(2)}`);
    }
    await app.close();
  }

  // ---- admin 1366 -----------------------------------------------------------------------
  const app = await rt.openApp({ token: admin, width: 1366, height: 900 });
  const p = app.page;

  // UIS-10, UIS-16 dashboard
  await rt.shot(p, DIR, 'admin-dashboard');
  const dash = await rt.semantics(p);
  console.log('UIS-10/16 dashboard:', dash.filter((n) => /New invoice|All invoices|Record payment|Kept|Open|Broken/.test(n.label || n.text || '')).map(fmt).join(' | '));

  // UIS-04, UIS-05 disputes list
  await rt.go(p, '#/disputes');
  await rt.shot(p, DIR, 'admin-disputes');
  const dn = await rt.semantics(p);
  const opens = dn.filter((n) => (n.label || n.text) === 'Open');
  console.log('UIS-04 disputes Open buttons x:', opens.map((n) => n.x).join(','), '| long reason nodes:', dn.filter((n) => /VUILONG|zzzz|yyyy|xxxx/.test(n.label || n.text || '')).map(fmt).join(' | '));
  console.log('UIS-05 target cells:', dn.filter((n) => /INVOICE|PAYMENT/.test(n.label || n.text || '')).slice(0, 6).map(fmt).join(' | '));

  // UIS-04 notifications + UIS-11 nav highlight
  await rt.go(p, '#/notifications');
  await rt.shot(p, DIR, 'admin-notifications');
  const nn = await rt.semantics(p);
  const hdr = nn.filter((n) => ['Type', 'Received', 'Read', 'Message', 'Title'].includes(n.label || n.text));
  console.log('UIS-04 notifications headers:', hdr.map(fmt).join(' | '));
  const navSel = await p.$$eval('flt-semantics', (els) => els.filter((e) => e.getAttribute('aria-selected') === 'true' || e.getAttribute('aria-current')).map((e) => e.getAttribute('aria-label') || e.textContent.trim()));
  console.log('UIS-11 selected/current nodes on #/notifications:', JSON.stringify(navSel));

  // UIS-05, UIS-17 dispute detail
  await rt.go(p, `#/disputes/${S.disp.id}`);
  await rt.shot(p, DIR, 'admin-dispute-detail');
  console.log('UIS-05/17 dispute detail:', (await rt.semantics(p)).filter((n) => /INVOICE|history|JSON|Opened/.test(n.label || n.text || '')).map(fmt).join(' | '));

  // UIS-13 customer detail top, UIS-12 history tab
  await rt.go(p, `#/customers/${S.cust.id}`);
  await rt.shot(p, DIR, 'admin-customer-top');
  console.log('UIS-13 customer nodes:', (await rt.semantics(p)).filter((n) => /POC|Add|Collection|Success/.test(n.label || n.text || '')).map(fmt).join(' | '));
  await rt.tap(p, /History/);
  await p.waitForTimeout(1500);
  await rt.shot(p, DIR, 'admin-customer-history');
  await rt.tap(p, /Payment Promise/).catch(() => {});
  await p.waitForTimeout(1200);
  await rt.shot(p, DIR, 'admin-customer-promise-tab');
  await rt.go(p, '#/payments');
  await rt.shot(p, DIR, 'admin-payments');

  // UIS-15 two raise dispute actions
  await rt.go(p, `#/invoices/${S.inv2.id}`);
  await rt.tap(p, /^Disputes/).catch(() => {});
  await p.waitForTimeout(1500);
  await rt.enableSemantics(p);
  await rt.shot(p, DIR, 'admin-invoice2-disputes-tab');
  console.log('UIS-15 raise dispute nodes:', (await rt.semantics(p)).filter((n) => /Raise dispute/.test(n.label || n.text || '')).map(fmt).join(' | '));

  // UIS-10 dialog buttons: New customer dialog
  await rt.go(p, '#/customers');
  await rt.tap(p, /New customer/).catch((e) => console.log('no New customer', e.message.slice(0, 200)));
  await p.waitForTimeout(1200);
  await rt.shot(p, DIR, 'admin-new-customer-dialog');
  console.log('UIS-10 dialog buttons:', (await rt.semantics(p)).filter((n) => /^(Cancel|Save|Create)$/.test(n.label || n.text || '')).map(fmt).join(' | '));
  await app.close();

  // ---- UIS-18: viewer and sales dashboards ---------------------------------------------
  for (const [who, user] of [['viewer', S.viewer.username], ['sales', S.sales.username]]) {
    const a = await rt.openApp({ token: await rt.login(user, S.password), width: 1366, height: 900 });
    const n = await rt.semantics(a.page);
    console.log(`UIS-18 ${who} dashboard buttons:`, n.filter((x) => /payments|invoices/i.test(x.label || x.text || '')).map(fmt).join(' | '));
    await rt.shot(a.page, DIR, `${who}-dashboard`);
    await rt.tap(a.page, /My payments|All payments/).catch(() => {});
    await a.page.waitForTimeout(2000);
    console.log(`UIS-18 ${who} after tap: hash=${await a.page.evaluate(() => location.hash)}`);
    await rt.shot(a.page, DIR, `${who}-payments`);
    await a.close();
  }

  // ---- UIS-11 customer: notifications + own customer detail -----------------------------
  const c = await rt.openApp({ token: await rt.login(S.cust.username, S.password), width: 1366, height: 900 });
  await rt.go(c.page, '#/notifications');
  await rt.shot(c.page, DIR, 'cust-notifications');
  await rt.go(c.page, `#/customers/${S.cust.id}`);
  await rt.shot(c.page, DIR, 'cust-customer-detail');
  await rt.go(c.page, '#/invoices');
  await rt.shot(c.page, DIR, 'cust-invoices');
  const off = await offscreen(c.page, 1366);
  console.log('UIS-03 cust 1366 invoices offscreen:', [...new Set(off.map((n) => n.label || n.text))].join(', '));
  await rt.go(c.page, `#/disputes/${S.disp.id}`);
  await rt.shot(c.page, DIR, 'cust-dispute-detail');
  await c.close();
})().catch((e) => { console.error(e); process.exit(1); });
