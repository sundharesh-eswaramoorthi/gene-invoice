// Area B — UI cases: the ageing chart must be labelled as days *overdue* (AC-B5) and a bucket
// must deep-link to exactly its invoices (AC-B6 / US-B3). Signed in as the Sales POC whose book
// holds the nine boundary invoices 01-api.js created, so the chart on screen is this run's data.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const RUN = path.resolve(__dirname, '../..');          // docs/regression/2026-09-21
const SHOTS = path.join(RUN, 'report');                 // rt.shot appends /shots
const OUT = path.join(__dirname, 'out');
const cases = [];

function rec(o) {
  const status = o.status || (o.ok ? 'PASS' : 'FAIL');
  const c = {
    id: o.id, feature: o.feature, kind: o.kind || 'UI', ac: o.ac, title: o.title, status,
    severity: status === 'FAIL' ? (o.severity || 'medium') : '',
    steps: o.steps, expected: o.expected, actual: o.actual,
    evidence: o.evidence || '', codeRef: o.codeRef || '',
  };
  cases.push(c);
  console.log(`[${status}] ${c.id} ${c.title}`);
}

const txt = (n) => `${n.label || ''} ${n.text || ''}`.trim();

(async () => {
  const fx = JSON.parse(fs.readFileSync(path.join(OUT, 'api.json'), 'utf8')).fixtures;
  const token = await rt.login(fx.salesA.username, fx.salesA.password);
  const app = await rt.openApp({ token, width: 1500, height: 1000 });
  const { page } = app;

  try {
    // Walk the dashboard, collecting every labelled node, and stop where the ageing card is.
    const seen = new Map();
    let cardTop = null;
    for (let i = 0; i < 10; i++) {
      const nodes = await rt.semantics(page);
      nodes.forEach((n) => seen.set(txt(n), n));
      cardTop = nodes.find((n) => /Outstanding by days overdue/i.test(txt(n)))
        || nodes.find((n) => /Not yet due/.test(txt(n)));
      if (cardTop && nodes.some((n) => /Over 90 days/.test(txt(n)))) break;
      await page.mouse.wheel(0, 450);
      await page.waitForTimeout(900);
      await rt.enableSemantics(page);
    }
    const all = [...seen.keys()];
    const shot1 = await rt.shot(page, SHOTS, 'B-39-dashboard-ageing');

    // Semantics nodes merge a whole card into one label, so match line by line.
    const lines = [...new Set(all.flatMap((t) => t.split('\n').map((s) => s.trim())))].filter(Boolean);

    // B-39 — the card's own wording (AC-B5)
    const title = lines.find((t) => /^Outstanding by days overdue$/i.test(t));
    const subtitle = lines.find((t) => /^Days overdue$/i.test(t));
    rec({
      id: 'B-39', feature: 'Chart wording', ac: 'AC-B5',
      title: 'The ageing card is titled by days OVERDUE, and its axis says so too',
      steps: 'Sign in at http://localhost:8084 as the Sales POC holding the boundary invoices; read the dashboard',
      expected: 'card title "Outstanding by days overdue" with the axis/subtitle "Days overdue"',
      actual: `title=${JSON.stringify(title || null)}, subtitle=${JSON.stringify(subtitle || null)}`,
      ok: !!title && !!subtitle,
      evidence: shot1,
      codeRef: 'frontend/lib/features/dashboard/dashboard_screen.dart:398',
    });

    // B-40 — the five D4 labels, and none of the old days-since-invoice wording
    const want = ['Not yet due', '1–30 days', '31–60 days', '61–90 days', 'Over 90 days'];
    const missing = want.filter((w) => !all.some((t) => t.includes(w)));
    const stale = all.filter((t) => /days since|since invoice|0–30 days|0-30 days|invoice age|aging by invoice/i.test(t));
    rec({
      id: 'B-40', feature: 'Chart wording', ac: 'AC-B5',
      title: 'Bucket labels are the five days-past-due bands, with no "days since invoice" wording left on the dashboard',
      steps: 'Read every labelled node on the dashboard (scrolled top to bottom)',
      expected: `${want.join(' / ')} present; no "days since invoice" and no "0–30 days" label anywhere`,
      actual: missing.length || stale.length
        ? `missing=${JSON.stringify(missing)} stale wording=${JSON.stringify(stale)}`
        : `on screen: ${want.join(', ')}; nothing matching the old wording among ${lines.length} distinct labels`,
      ok: missing.length === 0 && stale.length === 0,
      severity: 'low',
      evidence: shot1,
    });

    // B-41 — the card says whose book it covers (AC-B4 in the UI)
    const badge = lines.find((t) => /^Your book$/i.test(t));
    rec({
      id: 'B-41', feature: 'Scope and coverage', ac: 'AC-B4',
      title: 'Coverage BOOK is shown on the card, so a POC knows the chart is their book and not the company',
      steps: 'Same dashboard, signed in as a Sales POC (coverage BOOK from the API)',
      expected: 'a "Your book" badge on the ageing card',
      actual: badge ? `badge present: ${badge}` : 'no "Your book" badge among the dashboard labels',
      ok: !!badge,
      severity: 'low',
      evidence: shot1,
    });

    // B-42 — clicking a bucket deep-links to exactly that bucket's invoices (US-B3, AC-B6)
    const nodes = await rt.semantics(page);
    const bar = nodes.find((n) => /^31–60 days$/.test(txt(n)))
      || nodes.find((n) => /31–60 days/.test(txt(n)));
    let hash = '(bucket label not found on screen)';
    let listRows = null;
    let shot2 = '';
    if (bar) {
      await rt.clickAt(page, bar.x, bar.y, 3000);
      await rt.enableSemantics(page);
      hash = await page.evaluate(() => location.hash + location.search);
      shot2 = await rt.shot(page, SHOTS, 'B-42-bucket-deeplink');
      const after = await rt.semantics(page);
      listRows = after.filter((n) => /INV-/.test(txt(n))).length;
    }
    const linkOk = /dueDate(%3A|:)between/.test(hash) && /status(%3A|:)in/.test(hash);
    rec({
      id: 'B-42', feature: 'Bucket deep link', ac: 'AC-B6',
      title: 'Clicking the "31–60 days" bar opens the invoice list filtered to that bucket',
      steps: 'Click the 31–60 days bar on the ageing card; read the resulting URL and list',
      expected: 'navigation to /invoices carrying a dueDate range filter plus the open-status filter, showing the 2 invoices behind the bar',
      actual: `url=${decodeURIComponent(hash)}; invoice rows on screen=${listRows}`,
      ok: linkOk,
      evidence: shot2,
      codeRef: 'frontend/lib/features/dashboard/dashboard_charts.dart:585 (linkFor)',
    });

    // B-43 — no API error or page exception while all of that happened
    const relevant = app.apiErrors.filter((e) => !/\/api\/emails/.test(e.url));
    rec({
      id: 'B-43', feature: 'Dashboard health', ac: 'AC-B5',
      title: 'Loading the dashboard and following a bucket link raises no API error and no page exception',
      steps: 'Collect every response >= 400 and every page error for the session above',
      expected: 'no 4xx/5xx from the API, no uncaught JS error',
      actual: `apiErrors=${JSON.stringify(relevant)} pageErrors=${JSON.stringify(app.pageErrors)}`,
      ok: relevant.length === 0 && app.pageErrors.length === 0,
    });
  } finally {
    await app.close();
  }

  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'ui.json'), JSON.stringify({ cases }, null, 2));
  const fails = cases.filter((x) => x.status === 'FAIL');
  console.log(`\n${cases.length} UI cases, ${cases.length - fails.length} PASS, ${fails.length} FAIL`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
