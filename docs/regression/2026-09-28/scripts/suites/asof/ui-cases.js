// UI cases for B3 (as-of reads) through the Flutter web app (Playwright via lib.openApp).
const rt = require('../../lib.js');

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const label = (iso) => { const [y, m, d] = iso.split('-').map(Number); return `${d} ${MONTHS[m - 1]} ${y}`; };
const inr = (n) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 }).format(n);
const short = (o, n = 400) => String((typeof o === 'string' ? o : JSON.stringify(o)) ?? o).slice(0, n);

module.exports = async function uiCases({ S, check, shotsDir }) {
  const T = rt.day(-2);
  const LBL = label(T);
  const C = S.customers;
  const I = S.invoices;
  let shotN = 0;
  const shot = async (page, id) => rt.shot(page, shotsDir, `${id}-${++shotN}`);
  const texts = async (page) => rt.screenText(page);
  const has = (txt, s) => txt.some((x) => (s instanceof RegExp ? s.test(x) : x === s));
  const hasButton = async (page, name) => (await rt.semantics(page)).some((n) => n.role === 'button' && (n.label === name || n.text === name));

  async function withApp(opts, fn) {
    const app = await rt.openApp(opts);
    try { return await fn(app, app.page); } finally { await app.close(); }
  }
  const admin = { username: 'admin', password: 'admin123' };
  const ui = (d) => ({ kind: 'UI', ...d });

  await check(ui({ id: 'ASO-UI-01', feature: 'As-of chip', prd: 'B3', title: 'Staff see a "Today" chip; picking a past day turns it into "As of <date>" and every request then carries asOf',
    steps: `admin opens the app (dashboard); read the app-bar chip; chooseAsOf(${T}) through the date picker; open /invoices`,
    expected: 'DESIGN §7.3.1: chip "Today" -> "As of 25 Sep 2026"; the history range is fetched only when the picker opens (G13); D13: asOf added to every request' }), async () => withApp(admin, async (app, page) => {
    const before = await rt.asOfChip(page);
    const rangeBefore = app.requests.filter((r) => r.url.startsWith('/api/history/range')).length;
    const after = await rt.chooseAsOf(page, T);
    const rangeAfter = app.requests.filter((r) => r.url.startsWith('/api/history/range')).length;
    const n0 = app.requests.length;
    await rt.go(page, '/invoices');
    const later = app.requests.slice(n0).filter((r) => r.method === 'GET' && /^\/api\/(invoices|dashboard|customers)/.test(r.url));
    const withAsOf = later.filter((r) => r.url.includes(`asOf=${T}`));
    const sp = await shot(page, 'ASO-UI-01');
    const ok = before && /Today/.test(`${before.label} ${before.text}`) && rangeBefore === 0 && rangeAfter >= 1 && after && after.includes(`As of ${LBL}`)
      && later.length > 0 && withAsOf.length === later.length && app.pageErrors.length === 0;
    return { ok, screenshot: sp, actual: `chip before="${before && (before.label || before.text)}" after="${after}"; range fetched before/after pick=${rangeBefore}/${rangeAfter}; list requests=${later.length}, with asOf=${withAsOf.length} (${short(later.map((r) => r.url), 300)}); pageErrors=${app.pageErrors.length}` };
  }));

  await check(ui({ id: 'ASO-UI-02', feature: 'Read-only banner', prd: 'B3', title: 'ASO-R1-1 retest: the read-only banner "Viewing <date> (end of day, UTC) — read only" and its "Back to today" action are exposed to assistive technology',
    steps: `admin chooseAsOf(${T}); read the accessibility (semantics) tree; screenshot`, severity: 'low',
    expected: 'DESIGN §7.3.1 body banner text "Viewing 25 Sep 2026 (end of day, UTC) — read only" with the action "Back to today" (in the semantics tree like every other Text/TextButton)',
    codeRef: 'frontend/lib/shared/widgets/as_of_banner.dart:17-40 (rendered at frontend/lib/shared/widgets/app_shell.dart:216-222)' }), async () => withApp(admin, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, '/customers');
    const txt = await texts(page);
    const want = `Viewing ${LBL} (end of day, UTC) — read only`;
    const bannerBand = await page.evaluate(() => [...document.querySelectorAll('flt-semantics')].map((e) => { const r = e.getBoundingClientRect(); return { y: Math.round(r.y), h: Math.round(r.height), t: e.getAttribute('aria-label') || (e.childElementCount ? '' : e.textContent) }; }).filter((o) => o.y >= 56 && o.y < 100 && o.h > 0 && o.h < 60));
    const backButtons = (await rt.semantics(page)).filter((n) => /Back to today/.test(`${n.label || ''}${n.text || ''}`));
    const sp = await shot(page, 'ASO-UI-02');
    const all = await rt.semantics(page);
    const bannerNode = all.find((n) => `${n.label || ''}\n${n.text || ''}`.includes(want));
    const railCustomers = all.filter((n) => /^Customers$/.test((n.label || n.text || '').trim()) && n.x < 300);
    const ok = has(txt, want) && backButtons.some((b) => b.y > 56) && railCustomers.length > 0;
    return { ok, screenshot: sp, actual: `semantics has banner text=${has(txt, want)} (node y=${bannerNode && bannerNode.y}); "Back to today" nodes=${short(backButtons.map((b) => ({ y: b.y, x: b.x, role: b.role })))}; rail "Customers" nodes=${railCustomers.length}; semantics nodes in the banner band y=56..100: ${short(bannerBand)}`,
      evidence: `screenshot ${sp}; DOM query of flt-semantics between y=56 and y=100 -> ${short(bannerBand)}` };
  }));

  await check(ui({ id: 'ASO-UI-02b', feature: 'Read-only banner', prd: 'B3', severity: 'low', title: 'At a narrow width (700 px, drawer layout) the as-of banner is still in the accessibility tree, and the page below it too',
    steps: `admin opens the app at 700x900; chooseAsOf(${T}); /invoices; read the semantics tree`, expected: 'DESIGN §7.3.1 banner "Viewing … — read only" + "Back to today" exposed (FIXLOG F3 second test: 700 px); the invoice list pager is exposed' }), async () => withApp({ ...admin, width: 700, height: 900 }, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, '/invoices');
    await page.waitForTimeout(1500);
    const txt = await texts(page);
    const want = `Viewing ${LBL} (end of day, UTC) — read only`;
    const back = (await rt.semantics(page)).filter((n) => /Back to today/.test(`${n.label || ''}${n.text || ''}`));
    const sp = await shot(page, 'ASO-UI-02b');
    // The narrow layout lists cards with a "Page 1 of 1" pager instead of the table's "1–n of n".
    const pager = txt.find((x) => /^(1–\d+ of \d+|Page \d+ of \d+)$/.test(x));
    const ok = has(txt, want) && back.length >= 1 && !!pager;
    return { ok, screenshot: sp, actual: `banner in semantics=${has(txt, want)}; Back to today nodes=${short(back.map((b) => ({ x: b.x, y: b.y })))}; pager=${pager}; first lines=${short(txt.slice(0, 8))}` };
  }));

  await check(ui({ id: 'ASO-UI-03', feature: 'Dashboard as of', prd: 'B3', title: 'The dashboard as of T shows the figures of then, says "as of <date>", and hides its create shortcuts',
    steps: `admin on the dashboard live, then chooseAsOf(${T})`, expected: `PRD B3 "every ... dashboard figure"; DESIGN §7.4 header "as of 25 Sep 2026"; Billed ${inr(30500)}, Outstanding ${inr(27000)} (then); "New invoice"/"Record payment" gone` }), async () => withApp(admin, async (app, page) => {
    const liveTxt = await texts(page);
    const liveNew = await hasButton(page, 'New invoice');
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const txt = await texts(page);
    const billed = S.then.dash.billed.months.reduce((a, m) => a + Number(m.amount), 0);
    const outst = S.then.dash.age.buckets.reduce((a, m) => a + Number(m.amount), 0);
    const sp = await shot(page, 'ASO-UI-03');
    const ok = has(txt, new RegExp(`balances and promises are as of ${LBL}`)) && has(txt, inr(billed)) && has(txt, inr(outst)) && liveNew
      && !(await hasButton(page, 'New invoice')) && !(await hasButton(page, 'Record payment')) && !has(liveTxt, new RegExp(`as of ${LBL}`));
    return { ok, screenshot: sp, actual: `header asOf=${has(txt, new RegExp(`as of ${LBL}`))}; billed ${inr(billed)} shown=${has(txt, inr(billed))}; outstanding ${inr(outst)} shown=${has(txt, inr(outst))}; New invoice live=${liveNew} asOf=${await hasButton(page, 'New invoice')}` };
  }));

  await check(ui({ id: 'ASO-UI-04', feature: 'Invoice list as of', prd: 'B3', title: 'The invoice list as of T lists the invoices of then with their then status (X Unpaid, awaiting approval) and offers no create/bulk actions',
    steps: `admin /invoices live; chooseAsOf(${T})`, expected: `"1–${S.then.invoiceCount} of ${S.then.invoiceCount}"; ${S.invoiceX.number} Unpaid + "Awaiting approval"; ${S.invoiceN.number} absent; "New invoice" absent (DESIGN §7.4)` }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, '/invoices');
    const live = await rt.semantics(page);
    const liveX = live.find((n) => n.role === 'cell' && /Cancelled/.test(n.label || n.text || ''));
    const liveNew = await hasButton(page, 'New invoice');
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const nodes = await rt.semantics(page);
    const txt = await texts(page);
    const idx = nodes.findIndex((n) => n.role === 'cell' && (n.label || n.text) === S.invoiceX.number);
    const xStatus = idx >= 0 ? nodes.slice(idx, idx + 9).find((n) => /Unpaid|Cancelled|Partially paid/.test(n.label || n.text || '')) : null;
    const sp = await shot(page, 'ASO-UI-04');
    const n = S.then.invoiceCount;
    const ok = has(txt, `1–${n} of ${n}`) && xStatus && /Unpaid/.test(xStatus.label || xStatus.text) && /Awaiting approval/.test(xStatus.label || xStatus.text)
      && !has(txt, S.invoiceN.number) && !(await hasButton(page, 'New invoice')) && liveNew && !!liveX;
    return { ok, screenshot: sp, actual: `pager ${txt.find((x) => /^1–\d+ of \d+$/.test(x))}; X status cell="${xStatus && (xStatus.label || xStatus.text)}"; N listed=${has(txt, S.invoiceN.number)}; New invoice live=${liveNew} asOf=${await hasButton(page, 'New invoice')}; live had a Cancelled row=${!!liveX}` };
  }));

  await check(ui({ id: 'ASO-UI-05', feature: 'Customer list as of', prd: 'B3', title: 'The customer list as of T shows the old name of n1 and n2 in North',
    steps: `admin chooseAsOf(${T}); /customers`, expected: `"${C.n1.name}" listed, "${C.n1.newName}" not; n2's Region cell North (it moved to West after T)` }), async () => withApp(admin, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, '/customers');
    const nodes = await rt.semantics(page);
    const txt = await texts(page);
    const i2 = nodes.findIndex((n) => n.role === 'cell' && (n.label || n.text || '').split('\n')[0] === C.n2.name);
    const region = i2 >= 0 ? nodes.slice(i2, i2 + 12).find((n) => n.role === 'cell' && /\n(NORTH|WEST|SOUTH|DEFAULT)$/.test(n.label || n.text || '')) : null;
    const sp = await shot(page, 'ASO-UI-05');
    const ok = has(txt, C.n1.name) && !has(txt, C.n1.newName) && region && /NORTH/.test(region.label || region.text);
    return { ok, screenshot: sp, actual: `old name=${has(txt, C.n1.name)} new name=${has(txt, C.n1.newName)} n2 region cell=${region && JSON.stringify(region.label || region.text)}` };
  }));

  await check(ui({ id: 'ASO-UI-06', feature: 'Customer detail as of', prd: 'B3', title: 'Customer n2 as of T shows region North and no write actions (Move region, Delete, Send email, Save)',
    steps: `admin /customers/{n2} live, then chooseAsOf(${T}) and reopen`, expected: 'DESIGN §7.4: every mutating control hidden/disabled while historical; region "NORTH — North" at T, "WEST — West" live' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, `/customers/${C.n2.id}`);
    const liveTxt = await texts(page);
    const liveMove = await hasButton(page, 'Move region');
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/customers/${C.n2.id}`);
    const txt = await texts(page);
    const writes = [];
    for (const b of ['Move region', 'Delete customer', 'Send email', 'Save changes']) if (await hasButton(page, b)) writes.push(b);
    const sp = await shot(page, 'ASO-UI-06');
    const ok = has(txt, 'NORTH — North') && writes.length === 0 && liveMove && has(liveTxt, 'WEST — West');
    return { ok, screenshot: sp, actual: `asOf region North=${has(txt, 'NORTH — North')}; write buttons visible=${writes}; live Move region=${liveMove} live West=${has(liveTxt, 'WEST — West')}` };
  }));

  await check(ui({ id: 'ASO-UI-07', feature: 'Invoice detail as of', prd: 'B3', title: 'Invoice X as of T reads Unpaid with its then-outstanding cancel request, without edit/send actions; live reads Cancelled',
    steps: `admin /invoices/{X} live; chooseAsOf(${T}); reopen`, expected: 'status Unpaid + "Awaiting approval: Cancel invoice …" at T; no Save changes / Send email; live Cancelled' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, `/invoices/${S.invoiceX.id}`);
    const liveTxt = await texts(page);
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/invoices/${S.invoiceX.id}`);
    const txt = await texts(page);
    const writes = [];
    for (const b of ['Save changes', 'Send email', 'Cancel invoice']) if (await hasButton(page, b)) writes.push(b);
    const sp = await shot(page, 'ASO-UI-07');
    const ok = has(txt, 'Unpaid') && has(txt, /^Awaiting approval: Cancel invoice/) && writes.length === 0 && has(liveTxt, 'Cancelled');
    return { ok, screenshot: sp, actual: `asOf Unpaid=${has(txt, 'Unpaid')} awaiting=${has(txt, /^Awaiting approval: Cancel invoice/)} writes=${writes}; live Cancelled=${has(liveTxt, 'Cancelled')}` };
  }));

  await check(ui({ id: 'ASO-UI-08', feature: 'Record did not exist', prd: 'B3', title: 'Opening an invoice created after T while viewing T says it did not exist then',
    steps: `admin chooseAsOf(${T}); /invoices/{N}`, expected: `DESIGN §7.4: RecordUnavailable "This invoice did not exist on ${LBL}."` }), async () => withApp(admin, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/invoices/${S.invoiceN.id}`);
    const txt = await texts(page);
    const sp = await shot(page, 'ASO-UI-08');
    return { ok: has(txt, `This invoice did not exist on ${LBL}.`) && !has(txt, S.invoiceN.number), screenshot: sp, actual: short(txt.slice(5, 12)) };
  }));

  const NP = (S.pcs.E ? 4 : 3) + (S.pcs.G ? 2 : 0); // outstanding at T: A, B, D (+E in round 2, +G,H in round 4)
  const NAMT = 32000 + (S.pcs.E ? S.pcs.E.amount : 0) + (S.pcs.G ? S.pcs.G.amount + S.pcs.H.amount : 0);
  await check(ui({ id: 'ASO-UI-09', feature: 'Approvals as of', prd: 'B3', title: 'The Approvals page as of T lists the 3 changes outstanding then; a decided-later change reads "Awaiting approval" and cannot be approved',
    steps: `admin chooseAsOf(${T}); /approvals -> "All I can see"; /approvals/{pcA}`, expected: `tiles ${NP} / ${inr(NAMT)}; "1–${NP} of ${NP}"; detail "Awaiting approval", "Read-only in the as-of view", no Approve/Reject` }), async () => withApp(admin, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, '/approvals');
    await rt.tap(page, 'All I can see', { wait: 2500 });
    const txt = await texts(page);
    await rt.go(page, `/approvals/${S.pcs.A.id}`);
    const d = await texts(page);
    const acts = [];
    for (const b of ['Approve', 'Reject', 'Withdraw']) if (await hasButton(page, b)) acts.push(b);
    const sp = await shot(page, 'ASO-UI-09');
    const ok = has(txt, `1–${NP} of ${NP}`) && has(txt, inr(NAMT)) && has(d, 'Awaiting approval') && has(d, 'Read-only in the as-of view') && acts.length === 0;
    return { ok, screenshot: sp, actual: `list pager=${txt.find((x) => /^1–\d+ of \d+$/.test(x))} amount ${inr(NAMT)}=${has(txt, inr(NAMT))}; detail awaiting=${has(d, 'Awaiting approval')} readOnly=${has(d, 'Read-only in the as-of view')} actions=${acts}` };
  }));

  await check(ui({ id: 'ASO-UI-10', feature: 'Approvals as of', prd: 'B3', title: 'ASO-R1-2 retest: an approval viewed as of T does not show the decision taken after T (approved A, rejected E); live it does', severity: 'low',
    steps: `admin /approvals/{pcA} live; chooseAsOf(${T}); /approvals/{pcA} (approved by sam on ${label(rt.day(0))}); /approvals/{pcE} (rejected by sam today, note "${S.pcs.E && S.pcs.E.note}")`,
    expected: 'PRD B3 "as of a date … which approvals were outstanding": the page is the state at T — "Awaiting approval", no "Decided" / "Note" rows, no decider or decision date from after T, no "Open …" link to the invoice that only exists after T. Live: "Decided by Sam South on …" and "Open …"',
    codeRef: 'frontend/lib/features/approvals/approval_detail_screen.dart:69-79,142,238-247; backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java:109-137' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, `/approvals/${S.pcs.A.id}`);
    const live = await texts(page);
    const liveOpen = (await rt.semantics(page)).some((n) => n.role === 'button' && /^Open /.test(n.label || n.text || ''));
    const liveDecided = has(live, 'Decided') && has(live, /by Sam South on/);
    await rt.chooseAsOf(page, T);
    const leaks = {};
    let sp = '';
    for (const k of ['A', 'E']) {
      if (!S.pcs[k]) continue;
      await rt.go(page, `/approvals/${S.pcs[k].id}`);
      const d = await texts(page);
      const openBtn = (await rt.semantics(page)).filter((n) => n.role === 'button' && /^Open /.test(n.label || n.text || '')).map((n) => n.label || n.text);
      // (The Changes tab's proposed invoice date is today's: ageWorld does not rewrite the stored proposal JSON, so a
      // bare "today" date match is not a leak; the decision rows are what B3 hides.)
      const leak = d.filter((x) => /^by Sam South on/.test(x) || /^Decided$/.test(x) || x === 'Note' || (S.pcs[k].note && x.includes(S.pcs[k].note)) || /^(Rejected|Approved)$/.test(x)).concat(openBtn);
      if (!has(d, 'Awaiting approval')) leak.push('no "Awaiting approval"');
      leaks[k] = leak;
      sp = await shot(page, `ASO-UI-10-${k}`);
    }
    const api = await rt.approvals.get(S.tokens.admin, S.pcs.A.id, { asOf: T });
    const ok = Object.values(leaks).every((l) => l.length === 0) && liveDecided && liveOpen;
    return { ok, screenshot: sp, actual: `as of ${LBL}: leaks ${short(leaks, 600)}; live A: Decided shown=${liveDecided} Open button=${liveOpen}; API asOf=${T}: statusAt=${api.json.statusAt} decidedBy=${short(api.json.decidedBy)} decidedAt=${api.json.decidedAt} note=${api.json.decisionNote} result=${short(api.json.result)}`,
      evidence: `GET /api/approvals/${S.pcs.A.id}?asOf=${T} -> ${short({ status: api.json.status, statusAt: api.json.statusAt, decidedAt: api.json.decidedAt, decidedBy: api.json.decidedBy, result: api.json.result }, 400)}` };
  }));

  await check(ui({ id: 'ASO-UI-10d', feature: 'Approvals as of: readable changes', prd: 'B3', severity: 'low',
    title: 'Round 3 (near APP-R2-1): the Changes tab of an approval viewed as of T names the invoice and the invoice lines in words, with no JSON',
    steps: `admin chooseAsOf(${T}); /approvals/{pcE} (held payment naming ${S.pcs.E && S.pcs.E.invoiceNumber}); /approvals/{pcA} (held invoice create)`,
    expected: 'TRIAGE r2 APP-R2-1: a row "Invoices" whose Proposed cell reads the invoice number; no "Invoice ids"/"Promise ids" row; the Items row reads "<qty> × <product> at <price>"; no "[", "productId" in the table; Current cells "—" (as of T the record now is not shown)' }), async () => withApp(admin, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/approvals/${S.pcs.E.id}`);
    await page.waitForTimeout(1000);
    const e = await texts(page);
    const sp1 = await shot(page, 'ASO-UI-10d-E');
    await rt.go(page, `/approvals/${S.pcs.A.id}`);
    await page.waitForTimeout(1000);
    const a = await texts(page);
    const sp2 = await shot(page, 'ASO-UI-10d-A');
    const json = (t) => t.filter((x) => /productId|^\[|^\{|"\w+":/.test(x));
    const lineRe = /^\d+(\.\d+)? × .+ at /m;
    const ok = has(e, 'Invoices') && e.some((x) => x.includes(S.pcs.E.invoiceNumber)) && !has(e, /^(Invoice|Promise) ids$/)
      && a.some((x) => lineRe.test(x)) && json(e).length === 0 && json(a).length === 0;
    return { ok, screenshot: sp2, actual: `E: Invoices=${has(e, 'Invoices')} number shown=${e.some((x) => x.includes(S.pcs.E.invoiceNumber))} idRows=${e.filter((x) => /ids$/.test(x))} json=${short(json(e))}; A: line=${short(a.filter((x) => lineRe.test(x)))} json=${short(json(a))}`, evidence: `screenshots ${sp1}, ${sp2}` };
  }));

  await check(ui({ id: 'ASO-UI-10e', feature: 'Approvals as of: readable changes', prd: 'B3',
    title: 'Round 4 (near ASO-R3-1): as of T the Changes tab of a held payment names its promise, a held promise names its invoice, and an invoice line uses the product name of then',
    steps: `admin chooseAsOf(${T}); /approvals/{pcG}; /approvals/{pcH}; /approvals/{pcA}`,
    expected: 'no "Could not load this change"; G: "Promises" row reading "Promise of ₹1,000.00 by …"; H: "Invoices" row with the invoice number; A: "1 × <old product name> at ₹20,000.00"; each "Awaiting approval" and "Read-only in the as-of view"' }), async () => withApp(admin, async (app, page) => {
    if (!S.pcs.G || !S.product) return { ok: false, actual: 'scenario has no pcG/product rename' };
    await rt.chooseAsOf(page, T);
    const got = {}; let sp = '';
    for (const k of ['G', 'H', 'A']) {
      await rt.go(page, `/approvals/${S.pcs[k].id}`);
      await page.waitForTimeout(1000);
      got[k] = await texts(page);
      sp = await shot(page, `ASO-UI-10e-${k}`);
    }
    const common = (t) => !t.some((x) => /Could not load this change/.test(x)) && has(t, 'Awaiting approval') && has(t, 'Read-only in the as-of view');
    const gOk = common(got.G) && has(got.G, 'Promises') && got.G.some((x) => /Promise of ₹1,000\.00 by /.test(x));
    const hOk = common(got.H) && has(got.H, 'Invoices') && got.H.some((x) => x.includes(S.pcs.H.invoiceNumber));
    const aOk = common(got.A) && got.A.some((x) => x.includes(`1 × ${S.product.nameThen} at ₹20,000.00`)) && !got.A.some((x) => x.includes(S.product.nameNow));
    const pick = (t) => t.filter((x) => /Promise|Invoices|INV-| × |Could not|Awaiting|Read-only/.test(x)).slice(0, 10);
    return { ok: gOk && hOk && aOk, screenshot: sp, actual: `G ok=${gOk} ${short(pick(got.G), 300)}; H ok=${hOk} ${short(pick(got.H), 300)}; A ok=${aOk} ${short(pick(got.A), 300)}` };
  }));

  await check(ui({ id: 'ASO-UI-10c', feature: 'Approvals as of', prd: 'B3', title: 'An approval decided before T still shows its decision and its "Open …" link as of T',
    steps: `admin chooseAsOf(${T}); /approvals/{pcF} (approved by sam before ageing, note "${S.pcs.F && S.pcs.F.note}")`,
    expected: 'chip Approved; "Decided" by Sam South; the note; an "Open …" button (the invoice existed at T)' }), async () => withApp(admin, async (app, page) => {
    if (!S.pcs.F) return { ok: false, actual: 'scenario has no pcF' };
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/approvals/${S.pcs.F.id}`);
    const d = await texts(page);
    const open = (await rt.semantics(page)).some((n) => n.role === 'button' && /^Open /.test(n.label || n.text || ''));
    const sp = await shot(page, 'ASO-UI-10c');
    const ok = has(d, 'Approved') && has(d, 'Decided') && has(d, /by Sam South on/) && has(d, S.pcs.F.note) && open && !has(d, 'Awaiting approval');
    return { ok, screenshot: sp, actual: `Approved=${has(d, 'Approved')} Decided=${has(d, 'Decided')} bySam=${has(d, /by Sam South on/)} note=${has(d, S.pcs.F.note)} Open=${open} awaiting=${has(d, 'Awaiting approval')}` };
  }));

  await check(ui({ id: 'ASO-UI-11', feature: 'Tasks as of', prd: 'B3', title: 'The Tasks list as of T shows the tasks of then (T1 open, not T2)',
    steps: `admin /tasks live; chooseAsOf(${T})`, expected: '"1–3 of 3"; T1 title with Open; T2 title absent; live T2 present' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, '/tasks');
    const liveTxt = await texts(page);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const nodes = await rt.semantics(page);
    const txt = await texts(page);
    const i1 = nodes.findIndex((n) => n.role === 'cell' && (n.label || n.text || '').split('\n').includes(S.tasks.T1.title));
    const st = i1 >= 0 ? nodes.slice(i1, i1 + 3).map((n) => n.label || n.text).join('|') : '';
    const sp = await shot(page, 'ASO-UI-11');
    const ok = has(txt, '1–3 of 3') && i1 >= 0 && /Open/.test(st) && !has(txt, S.tasks.T2.title) && has(liveTxt, S.tasks.T2.title);
    return { ok, screenshot: sp, actual: `pager=${txt.find((x) => /^1–\d+ of \d+$/.test(x))} T1 row=${st} T2 asOf=${has(txt, S.tasks.T2.title)} live=${has(liveTxt, S.tasks.T2.title)}` };
  }));

  await check(ui({ id: 'ASO-UI-12', feature: 'My tasks as of', prd: 'B3', title: 'nora\'s My tasks as of T shows T1 and T3 (n2 was North); live T3 is gone',
    steps: `nora /my-tasks live; chooseAsOf(${T})`, expected: 'PRD A6 My tasks + B3' }), async () => withApp({ username: 'nora' }, async (app, page) => {
    await rt.go(page, '/my-tasks');
    const liveTxt = await texts(page);
    await rt.chooseAsOf(page, T);
    await page.waitForTimeout(1500);
    const txt = await texts(page);
    const sp = await shot(page, 'ASO-UI-12');
    const ok = has(txt, S.tasks.T1.title) && has(txt, S.tasks.T3.title) && !has(txt, S.tasks.T2.title) && !has(liveTxt, S.tasks.T3.title) && has(liveTxt, S.tasks.T2.title);
    return { ok, screenshot: sp, actual: `asOf T1=${has(txt, S.tasks.T1.title)} T3=${has(txt, S.tasks.T3.title)} T2=${has(txt, S.tasks.T2.title)}; live T3=${has(liveTxt, S.tasks.T3.title)} T2=${has(liveTxt, S.tasks.T2.title)}` };
  }));

  await check(ui({ id: 'ASO-UI-13', feature: 'Rule as of + preview', prd: 'B3', title: 'The rule editor as of T shows the revision of then, read only, and Preview evaluates on T\'s data',
    steps: `admin chooseAsOf(${T}); /automation/{R1}; press Preview`, expected: `name "${S.rules.R1.v1}"; Save changes disabled; no Run now; preview "1 invoice matches, on ${T}", "Actions are shown only for today's view", ${S.invoiceX.number}` }), async () => withApp({ ...admin, height: 2400 }, async (app, page) => {
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/automation/${S.rules.R1.id}`);
    const nodes = await rt.semantics(page);
    const head = await texts(page);
    // A disabled Flutter text field exposes no value in the semantics tree; the page title carries the name.
    const name = { text: head.includes(S.rules.R1.v1) && !head.includes(S.rules.R1.v2) ? S.rules.R1.v1 : head.find((x) => /AsOf Rule/.test(x)) };
    const save = nodes.find((n) => n.role === 'button' && (n.label || n.text) === 'Save changes');
    const runNow = await hasButton(page, 'Run now');
    await rt.tap(page, 'Preview', { role: 'button', wait: 4000 });
    const txt = await texts(page);
    const sp = await shot(page, 'ASO-UI-13');
    const ok = name && name.text === S.rules.R1.v1 && save && save.disabled && !runNow && has(txt, `1 invoice matches, on ${T}`) && has(txt, "Actions are shown only for today's view") && has(txt, new RegExp(S.invoiceX.number));
    return { ok, screenshot: sp, actual: `name="${name && name.text}" save disabled=${save && save.disabled} runNow=${runNow}; preview=${short(txt.filter((x) => /matches|Actions are shown/.test(x)))}` };
  }));

  await check(ui({ id: 'ASO-UI-14', feature: 'Back to today', prd: 'B3', title: '"Back to today" returns to the live view: chip "Today", live rows, requests without asOf',
    steps: `admin /invoices; chooseAsOf(${T}); tap the chip's "Back to today"`, expected: 'DESIGN §7.3.1: chip "Today"; list back to live count; later requests carry no asOf' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, '/invoices');
    await rt.chooseAsOf(page, T);
    const n0 = app.requests.length;
    await rt.chooseAsOf(page, null);
    await page.waitForTimeout(1500);
    const chipNode = await rt.asOfChip(page);
    const chip = chipNode ? `${chipNode.label || ''} ${chipNode.text || ''}` : null;
    const txt = await texts(page);
    const liveCount = (await rt.api('GET', '/api/invoices', { token: S.tokens.admin, query: { size: 10 } })).json.totalElements;
    const later = app.requests.slice(n0).filter((r) => r.url.startsWith('/api/invoices'));
    const sp = await shot(page, 'ASO-UI-14');
    const ok = /Today/.test(chip || '') && has(txt, `1–${Math.min(20, liveCount)} of ${liveCount}`) && later.length > 0 && later.every((r) => !r.url.includes('asOf='));
    return { ok, screenshot: sp, actual: `chip="${chip}" pager=${txt.find((x) => /^1–\d+ of \d+$/.test(x))} (live ${liveCount}); invoice requests after=${short(later.map((r) => r.url), 300)}` };
  }));

  async function pickerTry(page, iso) {
    const chip = await rt.asOfChip(page);
    await rt.clickAt(page, chip.x, chip.y, 2000);
    await rt.tap(page, /Switch to input/, { wait: 800 });
    const [y, m, d] = iso.split('-');
    await rt.fillField(page, /Enter Date|mm\/dd\/yyyy/i, `${m}/${d}/${y}`);
    await rt.tap(page, 'View', { wait: 2000 });
    return texts(page);
  }

  await check(ui({ id: 'ASO-UI-15', feature: 'Date picker bounds', prd: 'B3', title: 'The as-of picker refuses today and later (latest day = yesterday)',
    steps: `admin taps the chip; switches to input; types today ${rt.day(0)}; View; then Cancel`, expected: 'DESIGN §7.3.1: lastDate = latestAsOf (yesterday), "Today is never sent": "Out of range.", the chip stays "Today", no request with asOf=today' }), async () => withApp(admin, async (app, page) => {
    const txt = await pickerTry(page, rt.day(0));
    const out = has(txt, 'Out of range.');
    const sp = await shot(page, 'ASO-UI-15');
    await rt.tap(page, 'Cancel', { wait: 1200 });
    const chip = await rt.asOfChip(page);
    const sent = app.requests.filter((r) => r.url.includes(`asOf=${rt.day(0)}`));
    return { ok: out && /Today/.test(`${chip && chip.label}`) && sent.length === 0, screenshot: sp, actual: `Out of range shown=${out}; chip after cancel="${chip && chip.label}"; requests with asOf=today=${sent.length}` };
  }));

  await check(ui({ id: 'ASO-UI-16', feature: 'Date picker bounds', prd: 'B3', title: 'The as-of picker starts at the first history day (refuses the day before, accepts the first day)',
    steps: `SQL history_meta.started_at=${rt.day(-12)}T06:00Z; admin taps the chip; types ${rt.day(-13)} -> View; then types ${rt.day(-12)} -> View; restore started_at`,
    expected: `DESIGN §7.3.1: firstDate = historyStartsOn: ${rt.day(-13)} "Out of range."; ${rt.day(-12)} accepted -> chip "As of ${label(rt.day(-12))}"` }), async () => {
    const prevRow = rt.sql("select coalesce(started_at::text, 'NULL') from history_meta where id = 1");
    const prev = prevRow.length && prevRow[0][0] !== 'NULL' ? prevRow[0][0] : null;
    rt.sql(`update history_meta set started_at = '${rt.day(-12)}T06:00:00Z' where id = 1`);
    try {
      return await withApp(admin, async (app, page) => {
        const t1 = await pickerTry(page, rt.day(-13));
        const refused = has(t1, 'Out of range.');
        const sp = await shot(page, 'ASO-UI-16');
        await rt.fillField(page, /Enter Date|mm\/dd\/yyyy/i, (([y, m, d]) => `${m}/${d}/${y}`)(rt.day(-12).split('-')));
        await rt.tap(page, 'View', { wait: 3000 });
        const chip = await rt.asOfChip(page);
        const chipTxt = chip ? `${chip.label || ''} ${chip.text || ''}` : '';
        return { ok: refused && chipTxt.includes(`As of ${label(rt.day(-12))}`), screenshot: sp, actual: `day before refused=${refused}; chip after first day="${chipTxt}"` };
      });
    } finally {
      rt.sql(prev ? `update history_meta set started_at = '${prev}' where id = 1` : 'update history_meta set started_at = null where id = 1');
    }
  });

  await check(ui({ id: 'ASO-UI-17', feature: 'Customer login', prd: 'B3', title: 'A customer login gets no as-of chip (and no region selector)',
    steps: `open the app as the customer login of s1 (${S.custUser})`, expected: 'DESIGN D17/Q22: the as-of chip is staff only' }), async () => withApp({ username: S.custUser }, async (app, page) => {
    const chip = await rt.asOfChip(page);
    const sel = await rt.regionSelector(page);
    const sp = await shot(page, 'ASO-UI-17');
    return { ok: !chip && !sel, screenshot: sp, actual: `chip=${chip && (chip.label || chip.text)} regionSelector=${sel && sel.label}` };
  }));

  await check(ui({ id: 'ASO-UI-19', feature: 'Read-only banner', prd: 'B3', title: 'The banner\'s own "Back to today" action returns to the live view and the banner disappears',
    steps: `admin /invoices; chooseAsOf(${T}); tap the "Back to today" node in the banner (below the app bar)`,
    expected: 'DESIGN §7.3.1: banner action "Back to today" -> live: chip "Today", no "Viewing …" text, live pager' }), async () => withApp(admin, async (app, page) => {
    await rt.go(page, '/invoices');
    await rt.chooseAsOf(page, T);
    const nodes = (await rt.semantics(page)).filter((n) => /Back to today/.test(`${n.label || ''}${n.text || ''}`) && n.y > 56);
    if (!nodes.length) return { ok: false, actual: 'no "Back to today" node in the banner' };
    const b = nodes[0];
    await rt.clickAt(page, b.x, b.y, 2000);
    const txt = await texts(page);
    const chip = await rt.asOfChip(page);
    const liveCount = (await rt.api('GET', '/api/invoices', { token: S.tokens.admin, query: { size: 10 } })).json.totalElements;
    const sp = await shot(page, 'ASO-UI-19');
    const ok = !txt.some((x) => /^Viewing /.test(x)) && /Today/.test(`${chip && chip.label} ${chip && chip.text}`) && has(txt, `1–${Math.min(20, liveCount)} of ${liveCount}`);
    return { ok, screenshot: sp, actual: `banner after tap=${txt.filter((x) => /^Viewing /.test(x))}; chip="${chip && (chip.label || chip.text)}"; pager=${txt.find((x) => /^1–\d+ of \d+$/.test(x))} (live ${liveCount})` };
  }));

  await check(ui({ id: 'ASO-UI-18', feature: 'Region at T in the UI', prd: 'B3', title: 'nora can open customer n2 while viewing T (it was North then) but not live (it is West now)',
    steps: `nora /customers/{n2} live; chooseAsOf(${T}); reopen`, expected: 'live: record unavailable (no name, "Go back"); as of T: the customer page with NORTH — North' }), async () => withApp({ username: 'nora' }, async (app, page) => {
    await rt.go(page, `/customers/${C.n2.id}`);
    const liveTxt = await texts(page);
    await rt.chooseAsOf(page, T);
    await rt.go(page, `/customers/${C.n2.id}`);
    const txt = await texts(page);
    const sp = await shot(page, 'ASO-UI-18');
    const ok = !has(liveTxt, C.n2.name) && has(liveTxt, 'Go back') && has(txt, C.n2.name) && has(txt, 'NORTH — North');
    return { ok, screenshot: sp, actual: `live name=${has(liveTxt, C.n2.name)} goBack=${has(liveTxt, 'Go back')} (${short(liveTxt.slice(4, 8))}); asOf name=${has(txt, C.n2.name)} North=${has(txt, 'NORTH — North')}` };
  }));
};
