// Probe 3: (A) Deny reachability by mouse, (B) row selection on Disputes vs Notifications, bulk Mark read vs badge, (C) amount via absolute field position.
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
const cbs = (page) => page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e, i) => { const r = e.getBoundingClientRect();
  return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), checked: e.getAttribute('aria-checked'), label: e.getAttribute('aria-label') }; }));
const badge = async (page) => { const n = (await rt.semantics(page)).find((x) => x.y < 50 && /^\d+\+?$/.test(lbl(x))); return n ? lbl(n) : null; };
const buttons = async (page) => (await rt.semantics(page)).filter((n) => n.role === 'button').map(lbl).join(' | ');
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'dnprobe3'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('dnpr3P'), price: 100, active: true } })).json;
  const c = await rt.createCustomer(t, 'dnprobe3'); const ct = await rt.login(c.username, c.password);
  const mk = async () => (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
  const inv1 = await mk(); const inv2 = await mk(); const inv3 = await mk();
  const pay = (await rt.api('POST', '/api/payments', { token: t, body: { customerId: c.id, amount: 100, method: 'CASH', invoiceIds: [inv3.id], collectionPocUserId: me.id } })).json;
  const d = (await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv1.id, reason: 'probe3 deny reach', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'p3' }) } })).json;
  await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv2.id, reason: 'probe3 second' } });

  let app = await rt.openApp({ token: t }); let page = app.page;
  // (A) where would Deny be? click the right-hand end of the button row once
  await rt.go(page, `#/disputes/${d.id}`, 4500);
  const ap = (await rt.semantics(page)).find((n) => lbl(n) === 'Approve');
  await rt.clickAt(page, 1330, ap.y, 2500);
  console.log('A: after one real click at x=1330 on the button row: hash', await page.evaluate(() => location.hash), 'status', (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status);

  // (B1) disputes list: real click on first row checkbox
  await rt.go(page, '#/disputes', 4500);
  let list = (await cbs(page)).filter((x) => x.x < 400 && x.y > 160 && !x.label);
  console.log('B1: disputes row checkboxes', JSON.stringify(list.slice(0, 3)));
  if (list[0]) await rt.clickAt(page, list[0].x, list[0].y, 1200);
  console.log('B1: after real click', JSON.stringify((await cbs(page)).filter((x) => x.x < 400 && !x.label).slice(0, 3)), '| buttons:', await buttons(page));
  console.log(await rt.shot(page, DIR, 'p3-B1-disputes-select'));

  // (B2) notifications: dispatchEvent click on the row checkbox semantics nodes
  await rt.go(page, '#/notifications', 4500);
  list = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220 && !x.label);
  const before = await badge(page); const apiBefore = (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
  for (const cb of list.slice(0, 2)) { await page.locator('flt-semantics[role="checkbox"]').nth(cb.i).dispatchEvent('click'); await page.waitForTimeout(800); }
  console.log('B2: after semantic clicks', JSON.stringify((await cbs(page)).filter((x) => x.x < 400 && !x.label).slice(0, 4)), '| buttons:', await buttons(page));
  console.log(await rt.shot(page, DIR, 'p3-B2-notifications-select'));
  const mr = (await rt.semantics(page)).find((n) => /^Mark read/.test(lbl(n)));
  if (mr) {
    await rt.tap(page, /^Mark read/, { wait: 2500 });
    const after = await badge(page); const apiAfter = (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
    console.log(`B2: bulk Mark read -> badge ${before} -> ${after} | api ${apiBefore} -> ${apiAfter}`);
    console.log(await rt.shot(page, DIR, 'p3-B2-after-bulk'));
    await page.waitForTimeout(32000);
    console.log(`B2: badge after 32s poll = ${await badge(page)} | api = ${(await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count}`);
  } else console.log(`B2: no Mark read button; badge=${before} api=${apiBefore}`);
  console.log('admin pageErrors', JSON.stringify(app.pageErrors));
  await app.close();

  // (C) amount via absolute position of the "Corrected amount" field in the grown payment dialog
  app = await rt.openApp({ token: ct }); page = app.page;
  await rt.go(page, `#/payments/${pay.id}`, 4500);
  await rt.tap(page, /Raise dispute/);
  await rt.clickAt(page, 683, 400, 600);
  await rt.typeText(page, 'probe3 amount should be 120');
  await rt.tap(page, /What should change\?/);
  await rt.tap(page, 'Correct the amount', { wait: 2000 });
  await rt.clickAt(page, 683, 426, 1000);
  await rt.typeText(page, '120');
  console.log(await rt.shot(page, DIR, 'p3-C-typed-120'));
  await rt.tap(page, 'Submit', { wait: 3000 });
  console.log(await rt.shot(page, DIR, 'p3-C-after-submit'));
  const r = await rt.api('GET', `/api/disputes?targetType=PAYMENT&targetId=${pay.id}`, { token: ct });
  console.log('C: payment disputes', JSON.stringify(r.json.content.map((x) => ({ id: x.id, proposed: x.proposedChangeJson }))));
  console.log('customer pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  for (const x of (await rt.api('GET', `/api/disputes?size=20&filter=${encodeURIComponent('customerId:eq:' + c.id)}&filter=${encodeURIComponent('status:eq:PENDING')}`, { token: t })).json.content) {
    await rt.api('POST', `/api/disputes/${x.id}/deny`, { token: t, body: { adminNotes: 'probe cleanup' } });
  }
})().catch((e) => { console.error(e); process.exit(1); });
