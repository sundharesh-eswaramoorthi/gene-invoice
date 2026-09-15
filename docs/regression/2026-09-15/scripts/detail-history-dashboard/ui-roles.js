// Dashboard per role + quick actions + customer login detail/history checks.
const { rt, S, DIR, hash, allText, dump, clickRowLink, results } = require('./uih.js');
const R = results();

function parseTiles(label) {
  // "Invoices\n246\nTotal billed\n₹4.85L\n..." -> {Invoices:'246', ...}
  const parts = (label || '').split('\n').map((s) => s.trim()).filter(Boolean);
  const o = {};
  for (let i = 0; i + 1 < parts.length; i += 2) o[parts[i]] = parts[i + 1];
  return o;
}
async function readTiles(page) {
  const nodes = await rt.semantics(page);
  const inv = nodes.find((n) => /^Invoices\n\d+/.test(n.label || n.text || ''));
  const pro = nodes.find((n) => /^Open\n/.test(n.label || n.text || ''));
  return { inv: parseTiles(inv?.label || inv?.text), prom: parseTiles(pro?.label || pro?.text) };
}
async function apiNow(token) {
  const [i, p] = await Promise.all([rt.api('GET', '/api/invoices/summary', { token }), rt.api('GET', '/api/promises/summary', { token })]);
  return { i: i.json, p: p.json, st: [i.status, p.status] };
}
function tilesMatch(t, a) {
  const m = [];
  if (a.st[0] === 200) {
    if (String(a.i.count) !== t.inv.Invoices) m.push(`count ${t.inv.Invoices} vs ${a.i.count}`);
    if (String(a.i.unpaidCount) !== t.inv.Unpaid) m.push(`unpaid ${t.inv.Unpaid} vs ${a.i.unpaidCount}`);
    if (String(a.i.partiallyPaidCount) !== t.inv['Partially paid']) m.push(`partial ${t.inv['Partially paid']} vs ${a.i.partiallyPaidCount}`);
  }
  if (a.st[1] === 200 && t.prom.Open) {
    if (!t.prom.Open.startsWith(`${a.p.openCount} •`)) m.push(`open ${t.prom.Open} vs ${a.p.openCount}/${a.p.openAmount}`);
    if (t.prom.Kept !== String(a.p.keptCount)) m.push(`kept ${t.prom.Kept} vs ${a.p.keptCount}`);
    if (!t.prom.Broken.startsWith(`${a.p.brokenCount} •`)) m.push(`broken ${t.prom.Broken} vs ${a.p.brokenCount}`);
  }
  return m;
}

(async () => {
  const toks = {
    admin: await rt.adminToken(), cashier: await rt.cashierToken(),
    sales: await rt.login(S.sales.username, S.sales.password), coll: await rt.login(S.coll.username, S.coll.password),
    custA: await rt.login(S.custA.username, S.custA.password),
  };
  for (const [role, tok] of Object.entries(toks)) {
    const app = await rt.openApp({ token: tok });
    const { page } = app;
    await page.waitForTimeout(1500);
    let tiles = await readTiles(page); let a = await apiNow(tok); let mm = tilesMatch(tiles, a);
    if (mm.length) { await rt.go(page, '#/invoices', 2500); await rt.go(page, '#/', 3500); tiles = await readTiles(page); a = await apiNow(tok); mm = tilesMatch(tiles, a); }
    await rt.shot(page, DIR, `d-${role}`);
    const buttons = (await rt.semantics(page)).filter((n) => n.role === 'button').map((n) => n.label || n.text);
    R.rec(`D-${role}`, `dashboard for ${role}: loads without errors, tiles match /summary for the same user`, !mm.length && !app.apiErrors.length && !app.pageErrors.length && tiles.inv.Invoices !== undefined,
      { tiles, api: { inv: a.i && { count: a.i.count, totalBilled: a.i.totalBilled, outstanding: a.i.outstanding, unpaid: a.i.unpaidCount, partial: a.i.partiallyPaidCount }, prom: a.p && { open: a.p.openCount, openAmt: a.p.openAmount, kept: a.p.keptCount, keptAmt: a.p.keptAmount, broken: a.p.brokenCount, brokenAmt: a.p.brokenAmount } }, mismatches: mm, buttons, apiErrors: app.apiErrors, pageErrors: app.pageErrors.slice(0, 3) });

    // Quick actions
    const qa = {};
    const expectMap = { 'New invoice': '#/invoices/new', 'All invoices': '#/invoices', 'My invoices': '#/invoices', 'Record payment': '#/payments', 'My payments': '#/payments', 'Payment promises': '#/promises', Disputes: '#/disputes' };
    for (const b of Object.keys(expectMap)) {
      const nodes = await rt.semantics(page);
      const n = nodes.find((x) => x.role === 'button' && (x.label || x.text) === b && x.x > 260);
      if (!n) continue;
      await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
      await page.waitForTimeout(2500);
      const h = await hash(page);
      qa[b] = h;
      await rt.go(page, '#/', 3000);
    }
    const qaOk = Object.entries(qa).every(([b, h]) => h.split('?')[0] === expectMap[b]);
    R.rec(`D-QA-${role}`, `dashboard quick actions navigate correctly for ${role}`, qaOk && Object.keys(qa).length >= 3, qa);

    if (role === 'custA') {
      // customer opening other customer's records
      const f = {};
      for (const [k, h] of [['customerB', `#/customers/${S.custB.id}`], ['invoiceB1', `#/invoices/${S.B1.id}`], ['paymentPB1', `#/payments/${S.PB1.id}`]]) {
        await rt.go(page, h, 3500);
        f[k] = (await allText(page)).slice(0, 250);
        await rt.shot(page, DIR, `x-custA-${k}`);
      }
      R.rec('X-FORBIDDEN', 'customer A opening B\'s customer/invoice/payment sees a clean forbidden state', Object.values(f).every((t) => /do not have permission|does not exist/.test(t)), f);

      // own invoice detail: no POC, no edit controls; history w/o staff names or POC
      await rt.go(page, `#/invoices/${S.A1.id}`, 4000);
      const own = await allText(page);
      await rt.shot(page, DIR, 'x-custA-own-A1');
      R.rec('X-OWN-INVOICE', 'customer A own invoice: no Sales POC field, no Save changes, notes read-only (AC-C7)', !own.includes('Sales POC') && !own.includes('Save changes') && own.includes(S.A1.invoiceNumber), own.slice(0, 500));
      await rt.tap(page, 'History', { role: 'tab', wait: 3000 });
      const hd = await dump(page);
      const ht = hd.join('\n');
      await rt.shot(page, DIR, 'x-custA-A1-history');
      const staff = [S.sales.username, S.coll.username, S.coll2.username, 'by admin', 'automatic'].filter((s) => ht.includes(s));
      R.rec('X-HIST-CUST', 'customer History (invoice A1): rows present, no staff names/“automatic” on hidden rows, no POC', /Invoice created/.test(ht) && !staff.length && !/POC/i.test(ht), { staffOrAutomatic: staff, rows: hd.filter((l) => /AM|PM/.test(l)).slice(0, 10) });
      // customer's own customer History (may be 403 per sidebar)
      await rt.go(page, `#/customers/${S.custA.id}?tab=history`, 4500);
      const ch = await dump(page);
      await rt.shot(page, DIR, 'x-custA-own-customer-history');
      const cht = ch.join('\n');
      R.rec('X-HIST-CUST-OWN', 'customer own customer screen History: no POC events, no staff usernames (observation; route may be forbidden)', !/POC/i.test(cht.replace(/POC missing/g, '')) && ![S.sales.username, S.coll.username, 'by admin'].some((s) => cht.includes(s)), ch.filter((l) => /AM|PM|permission|Chip|All \(/.test(l)).slice(0, 12));
      // Long reason dispute row in customer history opens (expand)
      const exp = (await rt.semantics(page)).find((n) => /LONG-REASON|Dispute opened/.test(n.label || ''));
      R.rec('X-INFO', 'customer own customer page info', true, { apiErrors: app.apiErrors, pageErrors: app.pageErrors.slice(0, 3), foundDisputeRow: !!exp });
    }
    await app.close();
  }
  R.save('ui-roles.json');
})().catch((e) => { console.error('SCRIPT ERROR', e.message); R.save('ui-roles.json'); process.exit(1); });
