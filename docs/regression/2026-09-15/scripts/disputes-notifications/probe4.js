// Probe 4: notifications bulk Mark read from the UI, with network logging and API read-state checks.
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
const cbs = (page) => page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e, i) => { const r = e.getBoundingClientRect();
  return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), checked: e.getAttribute('aria-checked'), label: e.getAttribute('aria-label') }; }));
const badge = async (page) => { const n = (await rt.semantics(page)).find((x) => x.y < 50 && /^\d+\+?$/.test(lbl(x))); return n ? lbl(n) : null; };
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'dnprobe4'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('dnpr4P'), price: 100, active: true } })).json;
  const c = await rt.createCustomer(t, 'dnprobe4'); const ct = await rt.login(c.username, c.password);
  for (let k = 0; k < 3; k++) {
    const inv = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
    await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv.id, reason: `probe4 #${k}` } });
  }
  const list = async () => (await rt.api('GET', '/api/notifications?size=20', { token: t })).json.content.map((n) => `${n.id}:${n.type}:${n.read ? 'R' : 'U'}`);
  const count = async () => (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
  console.log('before: api list', JSON.stringify(await list()), 'count', await count());

  const app = await rt.openApp({ token: t }); const page = app.page;
  const log = [];
  page.on('request', (r) => { if (r.url().includes('/api/notifications')) log.push(`${new Date().toISOString().slice(11, 19)} REQ ${r.method()} ${decodeURIComponent(r.url().replace(rt.API, ''))} ${r.postData() || ''}`); });
  page.on('response', async (r) => { if (r.url().includes('/api/notifications/bulk') || r.url().includes('unread-count')) { let b = ''; try { b = (await r.text()).slice(0, 300); } catch {} log.push(`${new Date().toISOString().slice(11, 19)} RES ${r.status()} ${r.url().replace(rt.API, '')} ${b}`); } });
  await rt.go(page, '#/notifications', 5000);
  console.log('badge on open', await badge(page));
  const rows = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220 && !x.label);
  for (const cb of rows.slice(0, 2)) { await page.locator('flt-semantics[role="checkbox"]').nth(cb.i).dispatchEvent('click'); await page.waitForTimeout(800); }
  console.log('selected', JSON.stringify((await cbs(page)).filter((x) => x.x < 400 && !x.label)));
  await rt.tap(page, /^Mark read$/, { wait: 3000 });
  console.log('badge right after bulk', await badge(page), '| api count', await count(), '| api list', JSON.stringify(await list()));
  console.log(await rt.shot(page, DIR, 'p4-after-bulk'));
  await page.waitForTimeout(34000);
  console.log('badge 34s later', await badge(page), '| api count', await count());
  console.log(await rt.shot(page, DIR, 'p4-after-34s'));
  // navigate away and back (dashboard) to see whether the bell recovers
  await rt.go(page, '#/', 3000);
  console.log('badge on dashboard', await badge(page), '| api count', await count());
  console.log(await rt.shot(page, DIR, 'p4-dashboard'));
  console.log('NETWORK\n' + log.join('\n'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
