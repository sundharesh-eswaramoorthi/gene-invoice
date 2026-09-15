// UI part 3a: admin bell, notifications screen, notification click, disputes list, approve via UI.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const DIR = __dirname;
const out = {};
const dump = async (page, tag) => {
  const nodes = await rt.semantics(page);
  console.log(`--- ${tag}: ` + nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\s+/g, ' ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`).join(' | '));
  return nodes;
};
const onScreen = async (page, re) => (await rt.semantics(page)).some((n) => re.test(n.label || '') || re.test(n.text || ''));
const step = async (name, fn) => { try { await fn(); } catch (e) { console.log(`STEP ${name} ERROR: ${e.message.slice(0, 600)}`); } };
const hash = (page) => page.evaluate(() => location.hash);

(async () => {
  const seed = await rt.adminToken();
  const myAdmin = await rt.createStaff(seed, 'ADMIN', 'dnui3adm');
  const adm = await rt.login(myAdmin.username, myAdmin.password);
  const admMe = (await rt.api('GET', '/api/auth/me', { token: adm })).json;
  const prod = (await rt.api('POST', '/api/products', { token: adm, body: { name: rt.uniq('dnui3P'), price: 100, active: true } })).json;
  const X = await rt.createCustomer(adm, 'dnui3X');
  const xt = await rt.login(X.username, X.password);
  const mkInv = async (qty) => (await rt.api('POST', '/api/invoices', { token: adm, body: {
    customerId: X.id, salesPocUserId: admMe.id, notes: 'ui3 orig', items: [{ productId: prod.id, quantity: qty }] } })).json;
  const invX1 = await mkInv(2);
  const invX2 = await mkInv(1);
  const payX = (await rt.api('POST', '/api/payments', { token: adm, body: { customerId: X.id, amount: 150, method: 'CASH', invoiceIds: [invX1.id], collectionPocUserId: admMe.id } })).json;
  const open = async (targetType, targetId, reason, proposed) => (await rt.api('POST', '/api/disputes', { token: xt,
    body: { targetType, targetId, reason, proposedChangeJson: JSON.stringify(proposed) } })).json;
  const dX1 = await open('INVOICE', invX1.id, 'UI3 cancel please', { action: 'cancel' });
  const dX2 = await open('PAYMENT', payX.id, 'UI3 deny me', { action: 'update_meta', notes: 'meta ui3' });
  const dX3 = await open('INVOICE', invX2.id, 'UI3 bad json', { action: 'update_notes', notes: 'ui3 notes' });
  const ctx = { myAdmin: myAdmin.username, admId: admMe.id, X: X.username, XId: X.id, XName: X.name, invX1: invX1.id, invX2: invX2.id, payX: payX.id, dX1: dX1.id, dX2: dX2.id, dX3: dX3.id };
  fs.writeFileSync(path.join(DIR, 'ui3-ctx.json'), JSON.stringify(ctx, null, 2));
  console.log('ctx', JSON.stringify(ctx));
  const unread = async () => (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
  out.unread0 = await unread();

  const app = await rt.openApp({ token: adm });
  const { page } = app;

  await step('bell', async () => {
    await rt.go(page, '#/', 4000);
    const nodes = await dump(page, 'dashboard');
    console.log(await rt.shot(page, DIR, 'a01-admin-bell'));
    out.bellNodes = nodes.filter((n) => n.y < 60).map((n) => `[${n.role}] ${n.label || n.text}`);
  });

  await step('notifications', async () => {
    await rt.go(page, '#/notifications', 4500);
    await dump(page, 'notifications');
    console.log(await rt.shot(page, DIR, 'a02-notifications'));
    const n = await rt.find(page, /UI3 cancel please/);
    await rt.clickAt(page, n.x, n.y, 3500);
    out.afterNotifClickHash = await hash(page);
    await dump(page, 'after notification click');
    console.log(await rt.shot(page, DIR, 'a03-after-notification-click'));
    out.unreadAfterClick = await unread();
    const ln = await rt.api('GET', `/api/notifications?size=10&filter=${encodeURIComponent('type:eq:DISPUTE_OPENED')}`, { token: adm });
    out.dX1NotifRead = ln.json.content.find((x) => x.link === `/admin/disputes/${dX1.id}`)?.read;
    await rt.go(page, '#/', 3000);
    console.log(await rt.shot(page, DIR, 'a04-bell-after-click'));
    out.bellNodesAfter = (await rt.semantics(page)).filter((x) => x.y < 60).map((x) => `[${x.role}] ${x.label || x.text}`);
  });

  await step('disputes list', async () => {
    await rt.go(page, '#/disputes', 4500);
    await dump(page, 'admin disputes list');
    console.log(await rt.shot(page, DIR, 'a05-admin-disputes-list'));
    out.listHasX = await onScreen(page, /UI3 cancel please/) && await onScreen(page, /UI3 deny me/);
    const row = await rt.find(page, /UI3 deny me/);
    await rt.clickAt(page, row.x, row.y, 3500);
    out.rowClickHash = await hash(page);
    console.log(await rt.shot(page, DIR, 'a06-row-click-detail'));
  });

  await step('approve', async () => {
    await rt.go(page, `#/disputes/${dX1.id}`, 4500);
    await dump(page, 'detail pending');
    console.log(await rt.shot(page, DIR, 'a07-detail-pending'));
    await rt.tap(page, 'Approve', { wait: 4000 });
    out.afterApproveHash = await hash(page);
    await dump(page, 'after approve');
    console.log(await rt.shot(page, DIR, 'a08-after-approve'));
    const d = (await rt.api('GET', `/api/disputes/${dX1.id}`, { token: adm })).json;
    const inv = (await rt.api('GET', `/api/invoices/${invX1.id}`, { token: adm })).json;
    const cust = (await rt.api('GET', `/api/customers/${X.id}`, { token: adm })).json;
    out.approveEffect = { status: d.status, invStatus: inv.status, paid: inv.paidAmount, credit: cust.creditBalance };
    await rt.go(page, `#/disputes/${dX1.id}`, 4000);
    await dump(page, 'resolved detail');
    console.log(await rt.shot(page, DIR, 'a09-resolved-detail'));
    out.resolvedHasApprove = await onScreen(page, /^Approve$/);
  });

  await step('dX2 layout', async () => {
    await rt.go(page, `#/disputes/${dX2.id}`, 4500);
    await dump(page, 'dX2 detail');
    console.log(await rt.shot(page, DIR, 'a10-dX2-detail'));
  });

  console.log('OUT', JSON.stringify(out, null, 1));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  fs.writeFileSync(path.join(DIR, 'ui3a-out.json'), JSON.stringify({ out, apiErrors: app.apiErrors, pageErrors: app.pageErrors }, null, 2));
  await app.close();
})().catch((e) => { console.error('UI3A CRASHED', e); process.exit(1); });
