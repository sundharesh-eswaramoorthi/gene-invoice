// Area B — the chart's four overdue buckets and the invoice list's own "overdue" definition must
// name the same invoices. They are built by different code (DashboardService#overdueBy vs
// TableSchemas#invoiceOverdue), so a one-day disagreement at the boundary would show up here.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, 'out');
const cases = [];
const c = (x) => Math.round(Number(x) * 100);
const money = (cents) => (cents / 100).toFixed(2);

function rec(o) {
  const status = o.status || (o.ok ? 'PASS' : 'FAIL');
  cases.push({
    id: o.id, feature: o.feature, kind: o.kind || 'API', ac: o.ac, title: o.title, status,
    severity: status === 'FAIL' ? (o.severity || 'high') : '',
    steps: o.steps, expected: o.expected, actual: o.actual,
    evidence: o.evidence || '', codeRef: o.codeRef || '',
  });
  console.log(`[${status}] ${o.id} ${o.title}`);
}

(async () => {
  const fx = JSON.parse(fs.readFileSync(path.join(OUT, 'api.json'), 'utf8')).fixtures;
  const tok = await rt.login(fx.salesA.username, fx.salesA.password);

  const age = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: tok });
  const buckets = age.json.buckets;
  const lateCents = buckets.slice(1).reduce((s, b) => s + c(b.amount), 0);
  const lateCount = buckets.slice(1).reduce((s, b) => s + b.count, 0);

  const tiles = await rt.api('GET', '/api/invoices/summary', { token: tok });
  const list = await rt.api('GET', '/api/invoices?size=50&filter=overdue:eq:true', { token: tok });
  const listIds = (list.json?.content || []).map((r) => r.invoiceNumber).sort();

  rec({
    id: 'B-44', feature: 'Reconciliation', ac: 'AC-B3',
    title: "The four overdue buckets name exactly the invoices the list's own overdue filter names",
    steps: 'GET /api/dashboard/outstanding-by-age, GET /api/invoices/summary and GET /api/invoices?filter=overdue:eq:true as the boundary-set Sales POC',
    expected: 'sum of the 1–30 / 31–60 / 61–90 / Over 90 buckets = tiles.overdueAmount = 5.08 over 7 invoices, and the overdue-only list returns those same 7 rows (the two due today/tomorrow excluded)',
    actual: `buckets: ${money(lateCents)} / ${lateCount}; tiles.overdueAmount=${tiles.json.overdueAmount} overdueCount=${tiles.json.overdueCount}; overdue-only list totalElements=${list.json?.totalElements}`,
    ok: lateCents === c(tiles.json.overdueAmount) && lateCount === tiles.json.overdueCount
      && lateCount === list.json?.totalElements && lateCents === 508 && lateCount === 7,
    evidence: `overdue rows: ${listIds.join(', ')}`,
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:86 (invoiceOverdue) vs DashboardService.java:326 (overdueBy)',
  });

  // The book where one invoice is part-paid, one fully paid and one cancelled: the bucket's own
  // window must fetch the part-paid one and neither of the other two.
  const tokB = await rt.login(fx.salesB.username, rt.PASSWORD);
  const ageB = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: tokB });
  const bk = ageB.json.buckets[2];
  const qs = `?size=50&filter=status:in:UNPAID,PARTIALLY_PAID&filter=dueDate:between:${bk.dueDateFrom},${bk.dueDateTo}`;
  const rows = await rt.api('GET', '/api/invoices' + qs, { token: tokB });
  const content = rows.json?.content || [];
  const sum = content.reduce((s, r) => s + c(r.balance), 0);
  rec({
    id: 'B-45', feature: 'Bucket deep link', ac: 'AC-B6',
    title: 'A bucket window fetches the part-paid invoice behind it, and not the fully-paid or cancelled ones it excluded',
    steps: `GET /api/invoices${qs} as the Sales POC whose book holds one part-paid (12000.00 left), one fully-paid and one cancelled invoice all due ${bk.dueDateFrom}..${bk.dueDateTo}`,
    expected: `${bk.count} row(s) totalling ${bk.amount}, the part-paid invoice among them`,
    actual: `${rows.json?.totalElements} row(s) totalling ${money(sum)} — ${content.map((r) => `${r.invoiceNumber}:${r.status}:${r.balance}`).join(', ')}`,
    ok: rows.json?.totalElements === bk.count && sum === c(bk.amount)
      && content.some((r) => r.status === 'PARTIALLY_PAID'),
    codeRef: 'backend/src/main/java/com/geneinvoice/dashboard/DashboardService.java:348 (open)',
  });

  // How late each boundary invoice reads, straight off the clock, with nothing written since.
  const admin = await rt.adminToken();
  const late = [];
  for (const b of fx.boundary) {
    const r = await rt.api('GET', `/api/invoices/${b.id}`, { token: admin });
    late.push({ off: b.off, due: b.due, overdue: r.json.overdue, days: r.json.daysOverdue });
  }
  const daysOk = late.every((x) => x.overdue === (x.off < 0) && x.days === (x.off < 0 ? -x.off : 0));
  rec({
    id: 'B-46', feature: 'Derived overdue', ac: 'AC-B8',
    title: 'daysOverdue is exact at every boundary: 0 / 0 / 1 / 30 / 31 / 60 / 61 / 90 / 91',
    steps: 'GET each of the nine boundary invoices by id and read overdue / daysOverdue',
    expected: 'due today+1 and today → overdue=false, daysOverdue=0; the rest overdue=true with daysOverdue equal to the days since the due date',
    actual: late.map((x) => `due${x.off >= 0 ? '+' : ''}${x.off} (${x.due}) → overdue=${x.overdue}, daysOverdue=${x.days}`).join('; '),
    ok: daysOk,
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:124 (daysOverdue)',
  });

  fs.writeFileSync(path.join(OUT, 'cross.json'), JSON.stringify({ cases }, null, 2));
  console.log(`\n${cases.length} cases, ${cases.filter((x) => x.status === 'PASS').length} PASS`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
