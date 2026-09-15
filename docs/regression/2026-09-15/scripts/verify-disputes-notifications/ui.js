// Independent UI verification: DN-UI-06, DN-UI-11, DN-UI-16. Usage: node ui.js <part> <run>
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
const part = process.argv[2] || 'all';
const run = process.argv[3] || '1';

async function base(tag) {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'vdnui' + tag);
  const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('vdnuiP'), price: 100, active: true } })).json;
  const mkCust = async () => { const c = await rt.createCustomer(t, 'vdnui' + tag); return { ...c, token: await rt.login(c.username, c.password) }; };
  const mkInv = async (cid) => (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: cid, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
  return { seed, t, me, prod, mkCust, mkInv };
}

async function ui06() {
  const B = await base('06');
  const C1 = await B.mkCust(); const C2 = await B.mkCust();
  const i2 = await B.mkInv(C2.id);
  const d2 = (await rt.api('POST', '/api/disputes', { token: C2.token, body: { targetType: 'INVOICE', targetId: i2.id, reason: 'SECRET-other-customer reason' } })).json;
  const i1 = await B.mkInv(C1.id);
  const d1 = (await rt.api('POST', '/api/disputes', { token: C1.token, body: { targetType: 'INVOICE', targetId: i1.id, reason: 'my own reason' } })).json;
  const apiCheck = await rt.api('GET', `/api/disputes/${d2.id}`, { token: C1.token });
  console.log('UI06 API GET other dispute as C1:', apiCheck.status);
  const app = await rt.openApp({ token: C1.token }); const page = app.page;
  await rt.go(page, `#/disputes/${d1.id}`, 4000);
  console.log('UI06 own dispute nodes:', (await rt.semantics(page)).map(lbl).join(' | ').slice(0, 400));
  await rt.go(page, `#/disputes/${d2.id}`, 4500);
  const nodes = (await rt.semantics(page)).map(lbl);
  console.log('UI06 other dispute nodes:', nodes.join(' | ').slice(0, 800));
  console.log('UI06 leaked secret?', nodes.some((x) => x.includes('SECRET')));
  console.log(await rt.shot(page, DIR, `ui06-other-dispute-r${run}`));
  // control: other customer's invoice by URL -> RecordUnavailable
  await rt.go(page, `#/invoices/${i2.id}`, 4500);
  console.log('UI06 control other invoice nodes:', (await rt.semantics(page)).map(lbl).join(' | ').slice(0, 400));
  console.log(await rt.shot(page, DIR, `ui06-control-other-invoice-r${run}`));
  // nonexistent dispute
  await rt.go(page, '#/disputes/99999999', 4500);
  console.log('UI06 nonexistent dispute nodes:', (await rt.semantics(page)).map(lbl).join(' | ').slice(0, 400));
  console.log(await rt.shot(page, DIR, `ui06-nonexistent-r${run}`));
  console.log('UI06 pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
}

async function ui11() {
  const B = await base('11');
  const C = await B.mkCust();
  const mk = async (reason) => { const i = await B.mkInv(C.id); return (await rt.api('POST', '/api/disputes', { token: C.token, body: { targetType: 'INVOICE', targetId: i.id, reason, proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'v' }) } })).json; };
  const dA = await mk('vdn ui11 A'); const dB = await mk('vdn ui11 B'); const dC = await mk('vdn ui11 C');
  const st = async (d) => (await rt.api('GET', `/api/disputes/${d.id}`, { token: B.t })).json.status;
  for (const [w, h] of [[1366, 900], [1920, 1080]]) {
    const app = await rt.openApp({ token: B.t, width: w, height: h }); const page = app.page;
    await rt.go(page, `#/disputes/${dA.id}`, 4500);
    const sem = await rt.semantics(page);
    const ab = sem.filter((n) => /Approve|Deny|Admin notes|Applied change|history/i.test(lbl(n)));
    console.log(`UI11 ${w}x${h} nodes:`, JSON.stringify(ab.map((n) => ({ role: n.role, l: lbl(n).slice(0, 40), x: n.x, y: n.y, w: n.w, h: n.h }))));
    console.log(await rt.shot(page, DIR, `ui11-${w}-detail-r${run}`));
    await app.close();
  }
  // Real mouse click: Deny at its reported semantics rect, and at the place a Deny would sit next to Approve
  const app = await rt.openApp({ token: B.t }); const page = app.page;
  page.on('request', (r) => { if (/\/approve|\/deny/.test(r.url())) console.log('UI11 REQUEST', r.method(), r.url().replace(rt.API, '')); });
  await rt.go(page, `#/disputes/${dB.id}`, 4500);
  let sem = await rt.semantics(page);
  const deny = sem.find((n) => lbl(n) === 'Deny');
  const appr = sem.find((n) => lbl(n) === 'Approve');
  console.log('UI11 Deny node', JSON.stringify(deny), 'Approve node', JSON.stringify(appr));
  if (deny) { await rt.clickAt(page, deny.x, deny.y, 2500); console.log('UI11 after real click at Deny rect: hash', await page.evaluate(() => location.hash), 'dB status', await st(dB)); }
  // blank area at far right of the Approve row
  await rt.go(page, `#/disputes/${dB.id}`, 4500);
  sem = await rt.semantics(page);
  const appr2 = sem.find((n) => lbl(n) === 'Approve');
  if (appr2 && (await st(dB)) === 'PENDING') {
    await page.mouse.move(1330, appr2.y); await page.waitForTimeout(500);
    console.log(await rt.shot(page, DIR, `ui11-hover-1330-r${run}`));
    await rt.clickAt(page, 1330, appr2.y, 3000);
    console.log('UI11 after ONE real click at x=1330 on row: hash', await page.evaluate(() => location.hash), 'dB status', await st(dB));
  }
  // Keyboard: can Tab reach Deny?
  await rt.go(page, `#/disputes/${dC.id}`, 4500);
  const seen = [];
  for (let k = 0; k < 20; k++) {
    await page.keyboard.press('Tab'); await page.waitForTimeout(200);
    const f = await page.evaluate(() => { const e = document.activeElement; return e ? (e.getAttribute('aria-label') || e.textContent || e.tagName).trim().slice(0, 30) : null; });
    seen.push(f);
  }
  console.log('UI11 tab order:', JSON.stringify(seen));
  console.log('UI11 dC status', await st(dC));
  console.log('UI11 pageErrors', JSON.stringify(app.pageErrors.slice(0, 5)));
  await app.close();
  for (const d of [dA, dB, dC]) if ((await st(d)) === 'PENDING') await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: B.t, body: { adminNotes: 'verify cleanup' } });
}

async function ui16() {
  const B = await base('16');
  const C = await B.mkCust();
  for (let k = 0; k < 3; k++) { const i = await B.mkInv(C.id); await rt.api('POST', '/api/disputes', { token: C.token, body: { targetType: 'INVOICE', targetId: i.id, reason: `vdn16 #${k}` } }); }
  const count = async () => (await rt.api('GET', '/api/notifications/unread-count', { token: B.t })).json.count;
  const badge = async (page) => { const n = (await rt.semantics(page)).filter((x) => x.y < 60).map(lbl); return n.join(' | '); };
  const app = await rt.openApp({ token: B.t }); const page = app.page;
  const netlog = [];
  page.on('response', (r) => { if (/unread-count|\/bulk/.test(r.url())) netlog.push(`${new Date().toISOString().slice(11, 19)} ${r.request().method()} ${r.url().replace(rt.API, '')} ${r.status()}`); });
  await rt.go(page, '#/notifications', 5000);
  console.log('UI16 api count', await count(), '| top bar nodes:', await badge(page));
  console.log(await rt.shot(page, DIR, `ui16-before-r${run}`));
  const cbs = await page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e, i) => { const r = e.getBoundingClientRect(); return { i, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), label: e.getAttribute('aria-label') }; }));
  console.log('UI16 checkboxes', JSON.stringify(cbs));
  const rows = cbs.filter((x) => !x.label && x.y > 200).slice(0, 3);
  for (const cb of rows) { await page.locator('flt-semantics[role="checkbox"]').nth(cb.i).dispatchEvent('click'); await page.waitForTimeout(700); }
  console.log('UI16 buttons now:', (await rt.semantics(page)).filter((n) => n.role === 'button').map(lbl).join(' | '));
  await rt.tap(page, /^Mark read$/, { wait: 1500 });
  console.log(await rt.shot(page, DIR, `ui16-confirm-dialog-r${run}`));
  await rt.tap(page, 'Confirm', { wait: 2500 });
  const t0 = Date.now();
  console.log('UI16 right after confirm: api count', await count(), '| top bar:', await badge(page));
  console.log(await rt.shot(page, DIR, `ui16-after-confirm-r${run}`));
  let caught = null;
  while (Date.now() - t0 < 40000) {
    await page.waitForTimeout(2000);
    const b = await badge(page);
    if (!/\b3\b/.test(b)) { caught = Math.round((Date.now() - t0) / 1000); console.log('UI16 badge changed after', caught, 's:', b); break; }
  }
  if (caught === null) console.log('UI16 badge did not change in 40s');
  console.log(await rt.shot(page, DIR, `ui16-after-wait-r${run}`));
  // Control: single-row open marks read and refreshes the bell immediately
  console.log('UI16 net:', netlog.join(' ; '));
  await app.close();
}

(async () => {
  if (part === 'all' || part === '06') await ui06();
  if (part === 'all' || part === '11') await ui11();
  if (part === 'all' || part === '16') await ui16();
})().catch((e) => { console.error(e); process.exit(1); });
