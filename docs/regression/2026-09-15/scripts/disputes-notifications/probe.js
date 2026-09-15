// Real-mouse probe: can an admin see and click Approve / Deny on a pending dispute?
const rt = require('../lib.js');
const DIR = __dirname;
(async () => {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'dnprobe'); const t = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: t })).json;
  const prod = (await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('dnprP'), price: 10, active: true } })).json;
  const c = await rt.createCustomer(t, 'dnprobe'); const ct = await rt.login(c.username, c.password);
  const inv = (await rt.api('POST', '/api/invoices', { token: t, body: { customerId: c.id, salesPocUserId: me.id, items: [{ productId: prod.id, quantity: 1 }] } })).json;
  const d = (await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: inv.id, reason: 'probe', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'probe-approved' }) } })).json;
  for (const [w, h] of [[1366, 900], [1920, 1080]]) {
    const app = await rt.openApp({ token: t, width: w, height: h });
    await rt.go(app.page, `#/disputes/${d.id}`, 4500);
    const nodes = await rt.semantics(app.page);
    const ap = nodes.find((n) => n.label === 'Approve'); const dn = nodes.find((n) => n.label === 'Deny');
    console.log(`${w}x${h} Approve node`, ap && [ap.x, ap.y, ap.w, ap.h], 'Deny node', dn && [dn.x, dn.y, dn.w, dn.h]);
    console.log(await rt.shot(app.page, DIR, `p-${w}-detail`));
    if (w === 1366 && ap) {
      // real clicks along the row where the buttons should be painted (left-aligned under the notes field)
      for (const x of [300, 340, 400, 460, 520]) await rt.clickAt(app.page, x, ap.y, 700);
      await app.page.waitForTimeout(2500);
      console.log('hash after real clicks', await app.page.evaluate(() => location.hash));
      const s = (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status;
      console.log('status after real clicks at the button row =', s);
      console.log(await rt.shot(app.page, DIR, 'p-after-real-clicks'));
    }
    console.log('pageErrors', JSON.stringify(app.pageErrors));
    await app.close();
  }
  const s2 = (await rt.api('GET', `/api/disputes/${d.id}`, { token: t })).json.status;
  if (s2 === 'PENDING') await rt.api('POST', `/api/disputes/${d.id}/deny`, { token: t, body: { adminNotes: 'probe cleanup' } });
})().catch((e) => { console.error(e); process.exit(1); });
