// DN-UI-11 second probe: one real click on blank row (x=1330, x=400) and one at the Deny rect, each on a fresh dispute.
const rt = require('../lib.js');
const DIR = __dirname;
const lbl = (n) => (n.label || n.text || '').trim();
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'vdnui11b'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('vdn11bP'), price: 100, active: true } })).json;
  const c = await rt.createCustomer(t, 'vdnui11b'); const ct = await rt.login(c.username, c.password);
  const mk = async (reason) => { const i = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
    return (await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: i.id, reason, proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'v11b' }) } })).json; };
  const st = async (d) => (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status;
  const ds = [await mk('11b-1330'), await mk('11b-400'), await mk('11b-deny-rect'), await mk('11b-title-text')];
  const app = await rt.openApp({ token: t }); const page = app.page;
  page.on('request', (r) => { if (/\/approve|\/deny/.test(r.url())) console.log('  REQUEST', r.method(), r.url().replace(rt.API, '')); });
  const tries = [[ds[0], 'row', 1330], [ds[1], 'row', 400], [ds[2], 'deny', null], [ds[3], 'xy', [300, 144]]];
  for (const [d, kind, x] of tries) {
    await rt.go(page, `#/disputes/${d.id}`, 4500);
    const sem = await rt.semantics(page);
    const ap = sem.find((n) => lbl(n) === 'Approve'); const dn = sem.find((n) => lbl(n) === 'Deny');
    let px, py;
    if (kind === 'row') { px = x; py = ap.y; }
    else if (kind === 'deny') { px = dn.x; py = dn.y; }
    else { [px, py] = x; }
    await page.mouse.move(px, py); await page.waitForTimeout(400);
    console.log(await rt.shot(page, DIR, `ui11b-${d.id}-before-click`));
    await rt.clickAt(page, px, py, 3000);
    console.log(`dispute ${d.id} (${d.reason}): one real click at (${px},${py}) -> hash ${await page.evaluate(() => location.hash)} status ${await st(d)}`);
  }
  console.log('pageErrors', JSON.stringify(app.pageErrors.slice(0, 3)));
  await app.close();
  for (const d of ds) if ((await st(d)) === 'PENDING') await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: t, body: { adminNotes: 'verify cleanup' } });
})().catch((e) => { console.error(e); process.exit(1); });
