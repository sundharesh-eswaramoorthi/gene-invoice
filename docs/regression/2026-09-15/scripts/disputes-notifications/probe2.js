// Probe 2: (A) real-mouse Approve click, (B) notification row selection / bulk Mark read vs badge, (C) amount field input.
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
const cbs = (page) => page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e) => { const r = e.getBoundingClientRect();
  return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), checked: e.getAttribute('aria-checked'), disabled: e.getAttribute('aria-disabled'), label: e.getAttribute('aria-label') }; }));
const badge = async (page) => { const n = (await rt.semantics(page)).find((x) => x.y < 50 && /^\d+\+?$/.test(lbl(x))); return n ? lbl(n) : null; };
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'dnprobe2'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('dnpr2P'), price: 100, active: true } })).json;
  const c = await rt.createCustomer(t, 'dnprobe2'); const ct = await rt.login(c.username, c.password);
  const inv = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 2 }] } })).json;
  const inv2 = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
  const pay = (await rt.api('POST', '/api/payments', { token: t, body: { customerId: c.id, amount: 100, method: 'CASH', invoiceIds: [inv.id], collectionPocUserId: me.id } })).json;
  const d = (await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv.id, reason: 'probe2 A', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'probe2-approved' }) } })).json;
  await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv2.id, reason: 'probe2 B' } });

  // (A)
  let app = await rt.openApp({ token: t });
  let page = app.page;
  await rt.go(page, `#/disputes/${d.id}`, 4500);
  const nodes = await rt.semantics(page);
  const ap = nodes.find((n) => lbl(n) === 'Approve'); const dn = nodes.find((n) => lbl(n) === 'Deny');
  console.log('A: Approve node', ap && [ap.x, ap.y, ap.w, ap.h], '| Deny node', dn && [dn.x, dn.y, dn.w, dn.h]);
  for (const x of [290, 330, 380, 430, 480, 540]) await rt.clickAt(page, x, ap.y, 600);
  await page.waitForTimeout(2500);
  console.log('A: hash after real clicks', await page.evaluate(() => location.hash), '| status', (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status);
  console.log(await rt.shot(page, DIR, 'p2-A-after-real-clicks'));

  // (B)
  await rt.go(page, '#/notifications', 4500);
  console.log('B: checkboxes', JSON.stringify((await cbs(page)).filter((x) => x.x < 400)));
  const before = await badge(page); const apiBefore = (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
  const rowCbs = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220);
  for (const cb of rowCbs.slice(0, 2)) await rt.clickAt(page, cb.x, cb.y, 800);
  console.log('B: after real clicks', JSON.stringify((await cbs(page)).filter((x) => x.x < 400)));
  console.log('B: buttons now', (await rt.semantics(page)).filter((n) => n.role === 'button').map(lbl).join(' | '));
  console.log(await rt.shot(page, DIR, 'p2-B-after-row-clicks'));
  // header select-all
  const hdr = (await cbs(page)).find((x) => x.x < 400 && x.y < 220);
  if (hdr) { await rt.clickAt(page, hdr.x, hdr.y, 1000); console.log('B: after header click', JSON.stringify((await cbs(page)).filter((x) => x.x < 400))); }
  console.log('B: buttons now', (await rt.semantics(page)).filter((n) => n.role === 'button').map(lbl).join(' | '));
  console.log(await rt.shot(page, DIR, 'p2-B-after-header-click'));
  const mr = (await rt.semantics(page)).find((n) => /^Mark read/.test(lbl(n)));
  if (mr) {
    await rt.tap(page, /^Mark read/, { wait: 2500 });
    const after = await badge(page); const apiAfter = (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
    console.log(`B: badge ${before} -> ${after} | api ${apiBefore} -> ${apiAfter}`);
    console.log(await rt.shot(page, DIR, 'p2-B-after-bulk-mark-read'));
  } else console.log(`B: no Mark read button; badge=${before} api=${apiBefore}`);
  console.log('pageErrors', JSON.stringify(app.pageErrors));
  await app.close();

  // (C)
  app = await rt.openApp({ token: ct }); page = app.page;
  await rt.go(page, `#/payments/${pay.id}`, 4500);
  await rt.tap(page, /Raise dispute/);
  const title = await rt.find(page, /^Raise dispute •/);
  await rt.clickAt(page, 683, title.y + 75, 600);
  await rt.typeText(page, 'probe2 amount should be 120');
  await rt.tap(page, /What should change\?/);
  await rt.tap(page, 'Correct the amount', { wait: 2000 });
  const dd = await rt.find(page, /What should change\?/);
  console.log('C: dropdown at', dd.y);
  await rt.clickAt(page, 683, dd.y + 47, 1200);
  await rt.typeText(page, '120');
  console.log(await rt.shot(page, DIR, 'p2-C-after-typing-120'));
  await rt.tap(page, 'Submit', { wait: 3000 });
  console.log(await rt.shot(page, DIR, 'p2-C-after-submit'));
  const r = await rt.api('GET', `/api/disputes?targetType=PAYMENT&targetId=${pay.id}`, { token: ct });
  console.log('C: payment disputes', JSON.stringify(r.json.content.map((x) => x.proposedChangeJson)));
  console.log('pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  // cleanup pending
  const st = (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status;
  if (st === 'PENDING') await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: t, body: { adminNotes: 'probe cleanup' } });
})().catch((e) => { console.error(e); process.exit(1); });
