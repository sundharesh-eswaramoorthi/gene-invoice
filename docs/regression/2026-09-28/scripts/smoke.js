// Harness smoke test: API login, seedWorld, region visibility sanity, a UI login through the login
// screen, dashboard + region selector screenshots, an as-of pick. Results -> runs/r0/smoke.json.
// Run via ./smoke.sh (brings env "smoke" up on index 0 and down again).
const rt = require('./lib.js');

(async () => {
  const R = rt.results({ area: 'smoke', round: 0 });
  const t = await rt.adminToken();
  R.rec({ id: 'S-01', feature: 'Harness', kind: 'API', title: 'admin logs in through the API', ok: !!t, steps: 'POST /api/auth/login admin/admin123', expected: 'token', actual: t ? 'token received' : 'no token' });

  const w = await rt.seedWorld(t);
  const ids = Object.fromEntries(Object.entries(w.regions).map(([k, v]) => [k, v.id]));
  R.rec({ id: 'S-02', feature: 'Harness', kind: 'API', title: 'seedWorld builds regions, users, customers, invoices, payments, thresholds',
    ok: Object.keys(w.customers).length === 7 && w.thresholds && Object.keys(w.tokens).length === 6,
    steps: 'rt.seedWorld(adminToken)', expected: '4 regions, 5 users + tokens, 7 customers', actual: `regions=${JSON.stringify(ids)} users=${Object.keys(w.users)} customers=${Object.keys(w.customers)}` });

  // Who sees which regions (B1 sanity for the fixture itself).
  const seen = {};
  for (const u of ['nora', 'sam', 'bob', 'wendy', 'cust']) {
    const r = await rt.list(w.tokens[u], '/api/invoices');
    seen[u] = { status: r.status, total: r.json?.totalElements, regionIds: r.json?.regionIds, bookRegionIds: r.json?.bookRegionIds };
  }
  const ok = seen.nora.regionIds?.sort().join() === [ids.NORTH, ids.SOUTH].sort().join()
    && seen.wendy.regionIds?.join() === String(ids.WEST) && seen.bob.bookRegionIds?.includes(ids.NORTH)
    && seen.cust.status === 200;
  R.rec({ id: 'S-03', feature: 'Harness', kind: 'API', prd: 'B1', title: 'Seeded users answer for the regions their grants give them',
    ok, steps: 'GET /api/invoices as each seeded user', expected: 'nora North+South, wendy West, bob book in North, cust 200', actual: seen });

  // UI: login screen -> dashboard -> region selector.
  const app = await rt.openApp({});
  const { page } = app;
  try {
    await rt.uiLogin(page, 'admin', 'admin123');
    const text = await rt.screenText(page);
    const dash = await rt.shot(page, R.shotsDir, 'S-04-dashboard');
    R.rec({ id: 'S-04', feature: 'Harness', kind: 'UI', title: 'admin signs in through the login screen and lands on the dashboard',
      ok: text.some((s) => /^Welcome, /.test(s)), steps: `open ${rt.WEB}, type Username/Password, tap Sign in`, expected: '"Welcome, System Administrator" on the dashboard',
      actual: text.slice(0, 12).join(' | '), screenshot: dash });

    const sel = await rt.regionSelector(page);
    let selShot = '';
    let items = [];
    if (sel) {
      await rt.clickAt(page, sel.x, sel.y, 1500);
      items = (await rt.semantics(page)).filter((n) => n.role === 'menuitemcheckbox').map((n) => `${n.label}${n.checked === 'true' ? ' [x]' : ''}`);
      selShot = await rt.shot(page, R.shotsDir, 'S-05-region-selector');
      await rt.dismiss(page);
    }
    R.rec({ id: 'S-05', feature: 'Harness', kind: 'UI', prd: 'B1', title: 'The region selector opens and lists every region admin holds',
      ok: items.length === 5, steps: 'tap the app-bar "Regions: ..." button', expected: 'All my regions + DEFAULT, NORTH, SOUTH, WEST', actual: items.join(' | ') || 'selector not found', screenshot: selShot });

    await rt.chooseRegions(page, /^WEST/); // untick West
    const afterSel = await rt.regionSelector(page);
    const lastDash = app.requests.filter((r) => r.url.startsWith('/api/dashboard/')).slice(-1)[0];
    R.rec({ id: 'S-06', feature: 'Harness', kind: 'UI', prd: 'B1', title: 'Unticking a region re-queries the dashboard with regionIds',
      ok: !!lastDash && /regionIds=/.test(lastDash.url), steps: 'region selector -> untick WEST', expected: 'dashboard calls carry regionIds without West',
      actual: `selector="${afterSel && afterSel.text.split('\n')[0]}" last=${lastDash && lastDash.url}`, screenshot: await rt.shot(page, R.shotsDir, 'S-06-without-west') });

    const chip = await rt.chooseAsOf(page, rt.day(-1));
    const lastAsOf = app.requests.filter((r) => /asOf=/.test(r.url)).slice(-1)[0];
    R.rec({ id: 'S-07', feature: 'Harness', kind: 'UI', prd: 'B3', title: 'The as-of chip picks yesterday and the app re-queries with asOf',
      ok: /As of/.test(chip || '') && !!lastAsOf, steps: 'tap the Today chip -> Switch to input -> type date -> View', expected: 'chip "As of <yesterday>", calls carry asOf',
      actual: `chip="${chip}" last=${lastAsOf && lastAsOf.url}`, screenshot: await rt.shot(page, R.shotsDir, 'S-07-as-of') });

    R.rec({ id: 'S-08', feature: 'Harness', kind: 'UI', title: 'No API error or page exception during the UI smoke',
      ok: app.apiErrors.length === 0 && app.pageErrors.length === 0, steps: 'collect responses >= 400 and page errors', expected: 'none',
      actual: { apiErrors: app.apiErrors, pageErrors: app.pageErrors } });
  } finally {
    await app.close();
  }
  R.save();
  process.exit(R.cases.every((c) => c.status === 'PASS') ? 0 : 1);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
