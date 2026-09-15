// Dashboard tiles vs list-page summary endpoints, per role. The dashboard calls
// /api/invoices/summary and /api/promises/summary with no params; the list pages open unfiltered and
// call the same with the page's (empty) filter. We compare dashboard numbers against list-page totals
// (totalElements of GET /api/invoices / promises) and the per-status counts from filtered lists.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = JSON.parse(fs.readFileSync(path.join(__dirname, 'state.json')));
const out = {};

async function snapshot(token) {
  const g = async (p) => (await rt.api('GET', p, { token }));
  const [is, ps, il, iu, ip, pl, po, pk, pb] = await Promise.all([
    g('/api/invoices/summary'), g('/api/promises/summary'), g('/api/invoices?size=10'),
    g('/api/invoices?size=10&filter=status:eq:UNPAID'), g('/api/invoices?size=10&filter=status:eq:PARTIALLY_PAID'),
    g('/api/promises?size=10'), g('/api/promises?size=10&filter=status:eq:OPEN'), g('/api/promises?size=10&filter=status:eq:KEPT'), g('/api/promises?size=10&filter=status:eq:BROKEN'),
  ]);
  return {
    invSummary: [is.status, is.json], promSummary: [ps.status, ps.json],
    invTotal: [il.status, il.json?.totalElements, il.json?.message], unpaid: [iu.status, iu.json?.totalElements, iu.json?.message], partial: [ip.status, ip.json?.totalElements],
    promTotal: [pl.status, pl.json?.totalElements, pl.json?.message], open: [po.status, po.json?.totalElements], kept: [pk.status, pk.json?.totalElements], broken: [pb.status, pb.json?.totalElements],
  };
}
function compare(s) {
  const inv = s.invSummary[1] || {}; const pr = s.promSummary[1] || {};
  const m = [];
  if (s.invSummary[0] === 200 && s.invTotal[0] === 200) {
    if (inv.count !== s.invTotal[1]) m.push(`invoices count ${inv.count} vs list ${s.invTotal[1]}`);
    if (inv.unpaidCount !== s.unpaid[1]) m.push(`unpaid ${inv.unpaidCount} vs list ${s.unpaid[1]}`);
    if (inv.partiallyPaidCount !== s.partial[1]) m.push(`partial ${inv.partiallyPaidCount} vs list ${s.partial[1]}`);
  }
  if (s.promSummary[0] === 200 && s.promTotal[0] === 200) {
    if (pr.total !== s.promTotal[1]) m.push(`promises total ${pr.total} vs list ${s.promTotal[1]}`);
    if (pr.openCount !== s.open[1]) m.push(`open ${pr.openCount} vs ${s.open[1]}`);
    if (pr.keptCount !== s.kept[1]) m.push(`kept ${pr.keptCount} vs ${s.kept[1]}`);
    if (pr.brokenCount !== s.broken[1]) m.push(`broken ${pr.brokenCount} vs ${s.broken[1]}`);
  }
  return m;
}

(async () => {
  const A = await rt.adminToken();
  const users = {
    admin: A,
    cashier: await rt.cashierToken(),
    sales: await rt.login(S.sales.username, S.sales.password),
    coll: await rt.login(S.coll.username, S.coll.password),
    custA: await rt.login(S.custA.username, S.custA.password),
  };
  for (const [name, tok] of Object.entries(users)) {
    let s = await snapshot(tok); let m = compare(s);
    if (m.length) { s = await snapshot(tok); m = compare(s); } // re-check once (concurrent writers)
    out[name] = { mismatches: m, ...s };
    console.log(name, m.length ? 'MISMATCH ' + m.join('; ') : 'OK', JSON.stringify({ inv: s.invSummary, prom: s.promSummary, invTotal: s.invTotal, promTotal: s.promTotal }).slice(0, 700));
  }
  // customer A: exact values from own data
  const inv = out.custA.invSummary[1];
  console.log('custA expected invoices: A1 200 (paid 50 after void), A2 100 (paid 10)');
  // Exact billed/outstanding for custA by customerId filter (admin) = what dashboard for custA should show
  const adm = await rt.api('GET', `/api/invoices/summary?customerId=${S.custA.id}`, { token: A });
  const admP = await rt.api('GET', `/api/promises/summary?customerId=${S.custA.id}`, { token: A });
  console.log('admin view of custA summary', JSON.stringify(adm.json), JSON.stringify(admP.json));
  console.log('custA dashboard summary', JSON.stringify(inv), JSON.stringify(out.custA.promSummary[1]));
  out.adminViewOfCustA = [adm.json, admP.json];
  fs.writeFileSync(path.join(__dirname, 'dash-api.json'), JSON.stringify(out, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
