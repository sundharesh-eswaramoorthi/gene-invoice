// UI cases for the regions suite (Flutter web via the semantics tree; lib.openApp).
const zlib = require('zlib');
const rt = require('../../lib.js');

/** RGB of one screen pixel (1x1 PNG clip; every PNG filter reduces to the raw value for a single pixel). */
async function pixel(page, x, y) {
  const buf = await page.screenshot({ clip: { x: Math.round(x), y: Math.round(y), width: 1, height: 1 } });
  let off = 8; const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off); const type = buf.toString('ascii', off + 4, off + 8);
    if (type === 'IDAT') idat.push(buf.subarray(off + 8, off + 8 + len));
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  return [raw[1], raw[2], raw[3]];
}
/** A segmented-button segment is "selected" when its fill is the tinted one (darker, bluer than the page). */
async function segSelected(page, node) {
  const [r, , b] = await pixel(page, node.x - node.w / 2 + 6, node.y);
  return b - r > 20;
}

async function uiCases(F, RUN, { mkCustomer }) {
  const { C, shotsDir } = RUN;
  const { admin, R, U, T, w } = F;
  const shot = (page, name) => rt.shot(page, shotsDir, name);
  const text = async (page) => (await rt.screenText(page));
  const open = async (token, route) => { const app = await rt.openApp({ token }); if (route) await rt.go(app.page, route, 4500); return app; };
  const southNames = [w.customers.s1.name, w.customers.s2.name];
  const westNames = [w.customers.w1.name, w.customers.w2.name];
  const northNames = [w.customers.n1.name, w.customers.n2.name];

  await C({ id: 'REG-U01', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'The region selector offers exactly the user\'s granted regions',
    steps: 'nora opens /invoices; taps the app-bar "Regions: …" button', expected: 'menu items "All my regions", "NORTH · North", "SOUTH · South" (all ticked); no WEST, no DEFAULT (DESIGN §7.3.1)' }, async (n) => {
    const app = await open(T.nora, '/invoices');
    try {
      const sel = await rt.regionSelector(app.page);
      if (!sel) return { ok: false, actual: 'no region selector', screenshot: await shot(app.page, `REG-U01-${n}`) };
      await rt.clickAt(app.page, sel.x, sel.y, 1500);
      const items = (await rt.semantics(app.page)).filter((x) => x.role === 'menuitemcheckbox').map((x) => `${x.label || x.text}${x.checked === 'true' ? ' [x]' : ''}`);
      const s = await shot(app.page, `REG-U01-${n}`);
      await rt.dismiss(app.page);
      const ok = items.length === 3 && items.some((i) => /^All my regions/.test(i)) && items.some((i) => /^NORTH · North \[x\]/.test(i)) && items.some((i) => /^SOUTH · South \[x\]/.test(i)) && !items.some((i) => /WEST|DEFAULT/.test(i));
      return { ok, actual: items.join(' | '), screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U02', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'Unticking South narrows the invoice list and its tiles to North (requests carry regionIds)',
    steps: 'nora /invoices; region selector -> untick SOUTH', expected: 'selector reads "Regions: NORTH"; the list and summary requests carry regionIds=<N>; no South customer on screen (PRD B1; DESIGN D13)' }, async (n) => {
    const app = await open(T.nora, '/invoices');
    try {
      const before = await text(app.page);
      const sawSouth = before.some((t) => southNames.includes(t));
      await rt.chooseRegions(app.page, /^SOUTH/);
      await app.page.waitForTimeout(1500);
      const after = await text(app.page);
      const sel = await rt.regionSelector(app.page);
      const listReq = app.requests.filter((r) => /^\/api\/invoices\?/.test(r.url)).slice(-1)[0];
      const sumReq = app.requests.filter((r) => r.url.startsWith('/api/invoices/summary')).slice(-1)[0];
      const s = await shot(app.page, `REG-U02-${n}`);
      const southAfter = after.filter((t) => southNames.includes(t) || t === 'SOUTH');
      const ok = sawSouth && /NORTH/.test(sel?.text || '') && !/SOUTH/.test(sel?.text || '') && new RegExp(`regionIds=${R.N}($|&)`).test(listReq?.url || '')
        && new RegExp(`regionIds=${R.N}($|&)`).test(sumReq?.url || '') && southAfter.length === 0 && after.some((t) => northNames.includes(t));
      return { ok, actual: { selector: sel?.text, listReq: listReq?.url, sumReq: sumReq?.url, southBefore: sawSouth, southAfter }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U03', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'Narrowing on the dashboard re-queries every figure with regionIds and drops South customers',
    steps: 'nora opens / (dashboard); region selector -> untick SOUTH', expected: 'every /api/dashboard/* call after the pick has regionIds=<N>; the top-customer cards list no South customer (PRD B1 "every dashboard figure")' }, async (n) => {
    const app = await open(T.nora, '/');
    try {
      const mark = app.requests.length;
      await rt.chooseRegions(app.page, /^SOUTH/);
      await app.page.waitForTimeout(3000);
      const dash = app.requests.slice(mark).filter((r) => r.url.startsWith('/api/dashboard/'));
      const bad = dash.filter((r) => !new RegExp(`regionIds=${R.N}($|&)`).test(r.url));
      const t = (await text(app.page)).join('\n');
      const s = await shot(app.page, `REG-U03-${n}`);
      const southOnScreen = southNames.filter((x) => t.includes(x));
      const kinds = new Set(dash.map((r) => r.url.split('?')[0]));
      const ok = kinds.size >= 3 && bad.length === 0 && southOnScreen.length === 0;
      return { ok, actual: { dashboardCalls: dash.map((r) => r.url), withoutRegion: bad.map((r) => r.url), southOnScreen }, screenshot: s };
    } finally { await app.close(); }
  });


  await C({ id: 'REG-U17', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'The region choice holds across screens (customers, payments) for the session',
    steps: 'nora /invoices; untick SOUTH; navigate to /customers and /payments', expected: 'selector still "Regions: NORTH"; the customers and payments list calls carry regionIds=<N>; no South customer shown (DESIGN D13)' }, async (n) => {
    const app = await open(T.nora, '/invoices');
    try {
      await rt.chooseRegions(app.page, /^SOUTH/);
      const mark = app.requests.length;
      await rt.go(app.page, '/customers', 4500); const tc = await text(app.page);
      await rt.go(app.page, '/payments', 4500); const tp = await text(app.page);
      const sel = await rt.regionSelector(app.page); const s = await shot(app.page, `REG-U17-${n}`);
      const lists = app.requests.slice(mark).filter((r) => /^\/api\/(customers|payments)\?/.test(r.url));
      const bad = lists.filter((r) => !new RegExp(`regionIds=${R.N}($|&)`).test(r.url));
      const south = [...tc, ...tp].filter((x) => southNames.includes(x));
      const ok = /NORTH/.test(sel?.text || '') && !/SOUTH/.test(sel?.text || '') && lists.length >= 2 && bad.length === 0 && south.length === 0;
      return { ok, actual: { selector: sel?.text, calls: lists.map((r) => r.url), withoutRegion: bad.map((r) => r.url), south }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U04', kind: 'UI', feature: 'Region selector', prd: 'B1', title: 'A single-region user gets no selector and no Region column; a multi-region user gets the Region column',
    steps: 'wendy opens /invoices; nora opens /invoices', expected: 'wendy: no "Regions:" button, no "Region" header, only West customers; nora: "Region" header present (DESIGN §7.3.1, §7.5)' }, async (n) => {
    const a = await open(T.wendy, '/invoices'); let wt, sel, s1;
    try { wt = await text(a.page); sel = await rt.regionSelector(a.page); s1 = await shot(a.page, `REG-U04-${n}-wendy`); } finally { await a.close(); }
    const b = await open(T.nora, '/invoices'); let nt;
    try { nt = await text(b.page); } finally { await b.close(); }
    const foreign = wt.filter((t) => northNames.includes(t) || southNames.includes(t));
    const ok = !sel && !wt.includes('Region') && wt.some((t) => westNames.includes(t)) && foreign.length === 0 && nt.includes('Region');
    return { ok, actual: { wendySelector: !!sel, wendyRegionHeader: wt.includes('Region'), foreign, noraRegionHeader: nt.includes('Region') }, screenshot: s1 };
  });

  await C({ id: 'REG-U05', kind: 'UI', feature: 'Customer login', prd: 'B1', title: 'A customer login has no region selector or as-of chip and sees only its own invoices',
    steps: 'cust (customer s1) opens /invoices', expected: 'no "Regions:" button; no Today/as-of chip; only customer s1 rows (DESIGN §7.3.1, D17)' }, async (n) => {
    const app = await open(T.cust, '/invoices');
    try {
      const t = await text(app.page); const sel = await rt.regionSelector(app.page); const chip = await rt.asOfChip(app.page);
      const s = await shot(app.page, `REG-U05-${n}`);
      const own = (await rt.list(admin, '/api/invoices', { filter: `customerId:eq:${w.customers.s1.id}` })).json.content.map((i) => i.invoiceNumber);
      const allNums = t.filter((x) => /^INV-/.test(x));
      const foreign = allNums.filter((x) => !own.includes(x));
      const ok = !sel && !chip && allNums.length > 0 && foreign.length === 0;
      return { ok, actual: { selector: !!sel, asOfChip: chip?.text || null, shown: allNums, foreign }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U06', kind: 'UI', feature: 'Detail read matrix', prd: 'B1', title: 'Invoice detail: West deep link says it does not exist; South is read-only; North is editable',
    steps: 'nora opens /invoices/<w1 inv>, /invoices/<s2 inv>, /invoices/<n2 inv>', expected: 'W: "That invoice does not exist."; S: no "Save changes" and no "Send email"; N: both present (D14 canManage, §7.5)' }, async (n) => {
    const app = await open(T.nora);
    try {
      await rt.go(app.page, `/invoices/${w.invoices.w1[0].id}`, 4500); const tw = await text(app.page); const sw = await shot(app.page, `REG-U06-${n}-west`);
      await rt.go(app.page, `/invoices/${w.invoices.s2[0].id}`, 4500); const ts = await text(app.page); await shot(app.page, `REG-U06-${n}-south`);
      await rt.go(app.page, `/invoices/${w.invoices.n2[0].id}`, 4500); const tn = await text(app.page); await shot(app.page, `REG-U06-${n}-north`);
      const ok = tw.some((x) => /does not exist/i.test(x)) && !ts.includes('Save changes') && !ts.includes('Send email') && ts.includes(w.invoices.s2[0].invoiceNumber)
        && tn.includes('Save changes') && tn.includes('Send email');
      return { ok, actual: { west: tw.filter((x) => /exist|not found/i.test(x)), southControls: ts.filter((x) => /Save changes|Send email|Cancel invoice/.test(x)), northControls: tn.filter((x) => /Save changes|Send email/.test(x)) }, screenshot: sw };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U07', kind: 'UI', feature: 'Region move', prd: 'B1', title: '"Move region" is offered only to someone who manages the customer\'s region and another one',
    steps: 'nora and sam open /customers/<n2>', expected: 'nora: no "Move region" (manages North only); sam: "Move region" shown (canMoveRegion; §7.5)' }, async (n) => {
    const a = await open(T.nora, `/customers/${w.customers.n2.id}`); let ta, s;
    try { ta = await text(a.page); s = await shot(a.page, `REG-U07-${n}-nora`); } finally { await a.close(); }
    const b = await open(T.sam, `/customers/${w.customers.n2.id}`); let tb;
    try { tb = await text(b.page); await shot(b.page, `REG-U07-${n}-sam`); } finally { await b.close(); }
    return { ok: !ta.includes('Move region') && tb.includes('Move region') && ta.includes(w.customers.n2.name), actual: { nora: ta.includes('Move region'), sam: tb.includes('Move region') }, screenshot: s };
  });

  await C({ id: 'REG-U08', kind: 'UI', feature: 'Region move', prd: 'B1', title: 'Moving a customer through the dialog moves it and its invoices',
    steps: 'scratch North customer with an invoice; sam opens it, taps "Move region", picks "SOUTH — South", types a reason, taps Move', expected: 'result dialog "<name> is now in …South…"; API: customer and invoice regionId South (DESIGN §7.5 Move customer)' }, async (n) => {
    const c = await mkCustomer(F, 'N', { amounts: [100], prefix: 'uimv' });
    const app = await open(T.sam, `/customers/${c.id}`);
    try {
      await rt.tap(app.page, 'Move region', { wait: 2000 });
      const dd = await rt.find(app.page, /^Move to$/);
      await rt.clickAt(app.page, dd.x, dd.y, 1500);
      await rt.press(app.page, /^SOUTH — South/, { wait: 1200 });
      await rt.fillField(app.page, /Reason/, 'moved by the e2e UI test');
      await rt.tap(app.page, 'Move', { wait: 3500 });
      const t = await text(app.page); const s = await shot(app.page, `REG-U08-${n}`);
      const cr = (await rt.api('GET', `/api/customers/${c.id}`, { token: admin })).json; const ir = (await rt.api('GET', `/api/invoices/${c.invoices[0].id}`, { token: admin })).json;
      const ok = t.some((x) => /is now in/.test(x)) && cr.regionId === R.S && ir.regionId === R.S;
      return { ok, actual: { dialog: t.filter((x) => /is now in|Worth a look|moved/i.test(x)), customerRegion: cr.regionCode, invoiceRegion: ir.regionCode }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U09', kind: 'UI', feature: 'POC book per region', prd: 'B1', title: 'A mixed-view user sees the "Your book only in: SOUTH" strip; a full-view user does not',
    steps: 'mia and nora open /invoices', expected: 'mia: "Your book only in: SOUTH"; nora: no such strip (DESIGN §7.5 locked-filter chips)' }, async (n) => {
    const a = await open(T.mia, '/invoices'); let ta, s;
    try { ta = await text(a.page); s = await shot(a.page, `REG-U09-${n}-mia`); } finally { await a.close(); }
    const b = await open(T.nora, '/invoices'); let tb;
    try { tb = await text(b.page); } finally { await b.close(); }
    return { ok: ta.includes('Your book only in: SOUTH') && !ta.includes('My records only') && !tb.some((x) => /Your book only in/.test(x)), actual: { mia: ta.filter((x) => /book/i.test(x)), nora: tb.filter((x) => /book/i.test(x)) }, screenshot: s };
  });

  await C({ id: 'REG-U10', kind: 'UI', feature: 'Customer create', prd: 'B1', title: 'The New customer dialog asks for the region only when the user manages more than one',
    steps: 'sam (manages N and S) and nora (manages N only) open /customers -> New customer', expected: 'sam: a "Region" field; nora: none (DESIGN §7.5 create flows)' }, async (n) => {
    const a = await open(T.sam, '/customers'); let ta, s;
    try { await rt.tap(a.page, 'New customer', { wait: 2000 }); ta = await text(a.page); s = await shot(a.page, `REG-U10-${n}-sam`); } finally { await a.close(); }
    const b = await open(T.nora, '/customers'); let tb;
    try { await rt.tap(b.page, 'New customer', { wait: 2000 }); tb = await text(b.page); await shot(b.page, `REG-U10-${n}-nora`); } finally { await b.close(); }
    return { ok: ta.includes('New customer') && ta.includes('Region') && tb.includes('Name') && !tb.includes('Region'), actual: { samRegionField: ta.includes('Region'), noraRegionField: tb.includes('Region') }, screenshot: s };
  });

  await C({ id: 'REG-U11', kind: 'UI', feature: 'Region admin', prd: 'B1', title: 'Regions screen lists the regions; a new region is created through the dialog',
    steps: 'admin opens /regions; taps "New region"; types Code and Name; Save', expected: 'DEFAULT, NORTH, SOUTH, WEST listed with a "Default region" marker; after Save the API lists the new code (DESIGN §7.5 Regions admin)' }, async (n) => {
    const code = `UI${Date.now().toString(36).toUpperCase().slice(-6)}`;
    const app = await open(admin, '/regions');
    try {
      const t = await text(app.page);
      await rt.tap(app.page, 'New region', { wait: 1500 });
      await rt.fillField(app.page, 'Code', code);
      await rt.fillField(app.page, 'Name', `UI ${code}`);
      await rt.tap(app.page, 'Save', { wait: 3000 });
      const s = await shot(app.page, `REG-U11-${n}`);
      const all = (await rt.regions(admin)).map((r) => r.code);
      const ok = ['DEFAULT', 'NORTH', 'SOUTH', 'WEST'].every((c) => t.includes(c)) && t.some((x) => /Default region/.test(x)) && all.includes(code);
      return { ok, actual: { listed: t.filter((x) => /^[A-Z]{4,}$/.test(x)), created: all.includes(code) }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U12', kind: 'UI', feature: 'Region admin', prd: 'B1', title: 'A region\'s Grants tab shows who holds which level and scope',
    steps: 'admin opens /regions/<N>', expected: 'Nora North "Manage • All records", Bob Book "Manage • Own book"; Wendy absent (DESIGN §7.5)' }, async (n) => {
    const app = await open(admin, `/regions/${R.N}`);
    try {
      if (await rt.exists(app.page, 'Grants', { role: 'tab' })) await rt.tap(app.page, 'Grants', { role: 'tab', wait: 1500 });
      const nodes = await rt.semantics(app.page);
      const t = nodes.map((x) => `${x.label || ''} ${x.text || ''}`).join('\n');
      const s = await shot(app.page, `REG-U12-${n}`);
      const noraOk = /Nora North[\s\S]{0,80}Manage • All records/.test(t); const bobOk = /Bob Book[\s\S]{0,80}Manage • Own book/.test(t);
      return { ok: noraOk && bobOk && !/Wendy West/.test(t), actual: { noraOk, bobOk, wendy: /Wendy West/.test(t), excerpt: t.replace(/\n/g, ' ').slice(t.indexOf('Grants'), t.indexOf('Grants') + 400) }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U13', kind: 'UI', feature: 'Grants editor', prd: 'B1', title: 'The user\'s Regions tab shows each grant\'s level and scope',
    steps: 'admin opens /users/<nora>; reads the NORTH and SOUTH rows (selected segment = tinted fill)', expected: 'NORTH: Manage + All records selected; SOUTH: Read + All records selected; no WEST row (DESIGN §7.5 Users)' }, async (n) => {
    const app = await open(admin, `/users/${U.nora.id}`);
    try {
      const nodes = await rt.semantics(app.page);
      const rows = nodes.filter((x) => x.role === 'group' && /— /.test(x.label || '')).map((x) => ({ code: (x.label || '').split(' ')[0], y: x.y }));
      const segs = nodes.filter((x) => ['Read', 'Manage', 'Approve', 'All records', 'Own book'].includes(x.text));
      const out = {};
      for (const r of rows) {
        const mine = segs.filter((sg) => sg.y > r.y - 5 && sg.y < r.y + 80);
        out[r.code] = [];
        for (const sg of mine) if (await segSelected(app.page, sg)) out[r.code].push(sg.text);
      }
      const s = await shot(app.page, `REG-U13-${n}`);
      const ok = (out.NORTH || []).sort().join() === 'All records,Manage' && (out.SOUTH || []).sort().join() === 'All records,Read' && !out.WEST && Object.keys(out).length === 2;
      return { ok, actual: out, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U14', kind: 'UI', feature: 'Grants editor', prd: 'B1', title: 'Giving a user access in another region through the editor saves the grant',
    steps: 'admin opens /users/<wendy>; Add region -> NORTH — North; taps Read in the new row; Save access; then GET /api/users/<wendy>/region-grants', expected: 'wendy holds WEST MANAGE/ALL and NORTH READ; restored afterwards (DESIGN §7.5 region_grants_editor)' }, async (n) => {
    rt.need(await rt.setGrants(admin, U.wendy.id, [{ regionId: R.W, accessLevel: 'MANAGE', recordScope: 'ALL' }]), 'reset wendy');
    const app = await open(admin, `/users/${U.wendy.id}`);
    try {
      await rt.tap(app.page, /Add region/, { wait: 1500 });
      await rt.press(app.page, /^NORTH — North/, { wait: 1500 });
      const nodes = await rt.semantics(app.page);
      const north = nodes.find((x) => x.role === 'group' && /^NORTH — /.test(x.label || ''));
      const read = north && nodes.find((x) => x.text === 'Read' && x.y > north.y - 5 && x.y < north.y + 80);
      if (read) await rt.clickAt(app.page, read.x, read.y, 800);
      const s1 = await shot(app.page, `REG-U14-${n}-edited`);
      await rt.tap(app.page, 'Save access', { wait: 2500 });
      const s2 = await shot(app.page, `REG-U14-${n}-saved`);
      const g = await rt.getGrants(admin, U.wendy.id);
      const m = Object.fromEntries(g.map((x) => [x.regionCode, `${x.level}/${x.scope}`]));
      rt.need(await rt.setGrants(admin, U.wendy.id, [{ regionId: R.W, accessLevel: 'MANAGE', recordScope: 'ALL' }]), 'restore wendy');
      return { ok: m.NORTH === 'READ/ALL' && m.WEST === 'MANAGE/ALL', actual: { grantsAfterSave: m, northRowFound: !!north, readFound: !!read, apiErrors: app.apiErrors }, screenshot: s2 || s1 };
    } finally { await app.close(); }
  });


  await C({ id: 'REG-U18', kind: 'UI', feature: 'Detail read matrix', prd: 'B1', title: 'Customer detail: a READ region is read-only, an invisible region "does not exist"',
    steps: 'nora opens /customers/<s2> and /customers/<w2>; wendy opens /customers/<n2>', expected: 's2: name shown, no "Save changes", "Delete customer", "Move region" or "Send email"; w2 and (for wendy) n2: not-found message (D14, D15)' }, async (n) => {
    const app = await open(T.nora);
    let ts, tw, s;
    try {
      await rt.go(app.page, `/customers/${w.customers.s2.id}`, 4500); ts = await text(app.page); s = await shot(app.page, `REG-U18-${n}-south`);
      await rt.go(app.page, `/customers/${w.customers.w2.id}`, 4500); tw = await text(app.page);
    } finally { await app.close(); }
    const b = await open(T.wendy, `/customers/${w.customers.n2.id}`); let tn;
    try { tn = await text(b.page); await shot(b.page, `REG-U18-${n}-wendy`); } finally { await b.close(); }
    const ctrls = ts.filter((x) => ['Save changes', 'Delete customer', 'Move region', 'Send email'].includes(x));
    const ok = ts.includes(w.customers.s2.name) && ctrls.length === 0 && tw.some((x) => /does not exist|not found/i.test(x)) && tn.some((x) => /does not exist|not found/i.test(x));
    return { ok, actual: { southControls: ctrls, west: tw.filter((x) => /exist|found/i.test(x)), wendyNorth: tn.filter((x) => /exist|found/i.test(x)) }, screenshot: s };
  });

  await C({ id: 'REG-U19', kind: 'UI', feature: 'Grants editor', prd: 'B1', title: 'The grants editor only lets a user admin change what they themselves hold',
    steps: 'uma (USER_MANAGE; MANAGE/ALL North only) opens /users/<tina> (tina: READ South); taps "Add region"', expected: 'the SOUTH row says "Only someone with this access can change it"; Add region offers NORTH but not SOUTH/WEST/DEFAULT (DESIGN §7.5 Users)' }, async (n) => {
    rt.need(await rt.setGrants(admin, U.tina.id, [{ regionId: R.S, accessLevel: 'READ', recordScope: 'ALL' }]), 'reset tina');
    const app = await open(T.uma, `/users/${U.tina.id}`);
    try {
      const t = await text(app.page); const s = await shot(app.page, `REG-U19-${n}`);
      let offered = [];
      if (await rt.exists(app.page, /Add region/)) {
        await rt.tap(app.page, /Add region/, { wait: 1500 });
        offered = (await rt.semantics(app.page)).filter((x) => x.role === 'menuitem').map((x) => x.label || x.text);
        await shot(app.page, `REG-U19-${n}-menu`);
        await rt.dismiss(app.page);
      }
      const ok = t.includes('Only someone with this access can change it') && offered.some((o) => /^NORTH/.test(o)) && !offered.some((o) => /^(SOUTH|WEST|DEFAULT)/.test(o));
      return { ok, actual: { readOnlyNote: t.includes('Only someone with this access can change it'), offered }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U15', kind: 'UI', feature: 'Region admin', prd: 'B1', title: 'A REGION_VIEW-only user sees the regions list without create/edit controls',
    steps: 'nora (REGION_VIEW, no REGION_MANAGE) opens /regions and /regions/<N>', expected: 'list shown; no "New region", no Edit/Make default/Delete (DESIGN §6.3 gates)' }, async (n) => {
    const app = await open(T.nora, '/regions');
    try {
      const t = await text(app.page); const s = await shot(app.page, `REG-U15-${n}`);
      await rt.go(app.page, `/regions/${R.N}`, 4000); const d = await text(app.page);
      const ctrls = [...t, ...d].filter((x) => ['New region', 'Edit', 'Make default', 'Delete'].includes(x));
      return { ok: t.includes('NORTH') && ctrls.length === 0, actual: { listed: t.includes('NORTH'), controls: ctrls }, screenshot: s };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U16', kind: 'UI', feature: 'POC book per region', prd: 'B1', title: 'A pure-book user\'s list shows only his book with the "my book" chip',
    steps: 'bob opens /invoices', expected: 'only invoices of his book (Sales POC bob); the locked "My records only" chip is shown (lockedFilters, DESIGN §7.5)' }, async (n) => {
    const app = await open(T.bob, '/invoices');
    try {
      const t = await text(app.page); const s = await shot(app.page, `REG-U16-${n}`);
      const mine = (await rt.list(T.bob, '/api/invoices')).json.content.map((i) => i.invoiceNumber);
      const shown = t.filter((x) => /^INV-/.test(x));
      const foreign = shown.filter((x) => !mine.includes(x));
      return { ok: shown.length > 0 && foreign.length === 0 && t.includes('My records only'), actual: { shown, foreign, lockedChip: t.includes('My records only') }, screenshot: s };
    } finally { await app.close(); }
  });
}

module.exports = { uiCases, pixel };
