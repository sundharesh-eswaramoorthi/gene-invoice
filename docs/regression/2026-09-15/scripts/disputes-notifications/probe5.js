// Probe 5: bulk Mark read WITH confirmation, bell badge vs API; real mouse click on notification checkbox glyph.
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
const cbs = (page) => page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e, i) => { const r = e.getBoundingClientRect();
  return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), checked: e.getAttribute('aria-checked'), label: e.getAttribute('aria-label') }; }));
const badge = async (page) => { const n = (await rt.semantics(page)).find((x) => x.y < 50 && /^\d+\+?$/.test(lbl(x))); return n ? lbl(n) : null; };
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'dnprobe5'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('dnpr5P'), price: 100, active: true } })).json;
  const c = await rt.createCustomer(t, 'dnprobe5'); const ct = await rt.login(c.username, c.password);
  for (let k = 0; k < 3; k++) {
    const inv = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
    await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv.id, reason: `probe5 #${k}` } });
  }
  const count = async () => (await rt.api('GET', '/api/notifications/unread-count', { token: t })).json.count;
  const app = await rt.openApp({ token: t }); const page = app.page;
  const log = [];
  page.on('request', (r) => { if (r.url().includes('/api/notifications') && !r.url().includes('?page')) log.push(`${new Date().toISOString().slice(11, 19)} REQ ${r.method()} ${r.url().replace(rt.API, '')} ${r.postData() || ''}`); });
  page.on('response', async (r) => { if (r.url().includes('/bulk') || r.url().includes('unread-count')) { let b = ''; try { b = (await r.text()).slice(0, 250); } catch {} log.push(`${new Date().toISOString().slice(11, 19)} RES ${r.status()} ${r.url().replace(rt.API, '')} ${b}`); } });
  await rt.go(page, '#/notifications', 5000);
  console.log('badge on open', await badge(page), '| api', await count());
  // real mouse click on the first row's visible checkbox glyph
  let rows = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220 && !x.label);
  await page.mouse.move(317, rows[0].y); await page.waitForTimeout(300);
  await rt.clickAt(page, 317, rows[0].y, 1200);
  let after = (await cbs(page)).filter((x) => x.x < 400 && !x.label);
  console.log('real click at glyph (317,', rows[0].y, ') ->', JSON.stringify(after), '| hash', await page.evaluate(() => location.hash));
  console.log(await rt.shot(page, DIR, 'p5r-real-click-checkbox'));
  if (!after.some((x) => x.checked === 'true')) {
    await rt.go(page, '#/notifications', 4000);
    rows = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220 && !x.label);
    for (const cb of rows.slice(0, 2)) { await page.locator('flt-semantics[role="checkbox"]').nth(cb.i).dispatchEvent('click'); await page.waitForTimeout(800); }
  } else {
    rows = (await cbs(page)).filter((x) => x.x < 400 && x.y > 220 && !x.label && x.checked !== 'true');
    await rt.clickAt(page, 317, rows[0].y, 1200);
  }
  console.log('selected', JSON.stringify((await cbs(page)).filter((x) => x.x < 400 && !x.label)));
  const badgeBefore = await badge(page); const apiBefore = await count();
  await rt.tap(page, /^Mark read$/, { wait: 1500 });
  await rt.tap(page, 'Confirm', { wait: 3000 });
  const badgeAfter = await badge(page); const apiAfter = await count();
  console.log(`after confirm: badge ${badgeBefore} -> ${badgeAfter} | api ${apiBefore} -> ${apiAfter}`);
  console.log(await rt.shot(page, DIR, 'p5r-after-confirm'));
  let t0 = Date.now(); let b = badgeAfter;
  while (Date.now() - t0 < 36000 && String(b) !== String(apiAfter)) { await page.waitForTimeout(3000); b = await badge(page); }
  console.log(`badge caught up to API after ${Math.round((Date.now() - t0) / 1000)}s: badge=${b} api=${await count()}`);
  console.log(await rt.shot(page, DIR, 'p5r-after-wait'));
  console.log('NETWORK\n' + log.join('\n'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
