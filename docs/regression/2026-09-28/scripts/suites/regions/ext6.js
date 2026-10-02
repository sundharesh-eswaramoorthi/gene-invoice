// Round-6 extensions of the regions suite: RGN-R5-1 retest, and one step around its fix
// (PendingChangeScope.isVisible / forList: the record path is judged in the record's own region, with no gate on the
// region the change was asked in; the maker / approver / threshold / named-POC paths stay on the change's region),
// plus the neighbouring frontend fix ASO-R5-1 (approval page shows Record/Customer as plain text when the server says
// "You no longer have access to this record").
const rt = require('../../lib.js');
const { excerpt } = require('./h.js');

const get = (t, p, query) => rt.api('GET', p, { token: t, query });
const NO_ACCESS = 'You no longer have access to this record';

const brief = (d) => ({ status: d.status, pcStatus: d.json?.status, current: d.json?.current == null ? null : 'filled',
  changes: (d.json?.changes || []).length,
  changesCurrent: (d.json?.changes || []).filter((x) => x.current != null || x.currentText != null).length,
  blockedReason: d.json?.blockedReason ?? null, canApprove: d.json?.canApprove, regionId: d.json?.regionId });

async function listIds(token, query = {}, scope = {}) {
  const r = await rt.approvals.list(token, query, scope);
  if (r.status !== 200) return `status ${r.status}`;
  return (r.json.content || []).map((x) => x.id);
}
async function inList(token, pcId, scope = {}) {
  const ids = await listIds(token, { filter: `id:eq:${pcId}` }, scope);
  return Array.isArray(ids) ? ids.includes(pcId) : ids;
}

async function ext6Cases(F, RUN, { mkCustomer }) {
  const { C, shotsDir } = RUN;
  const { admin, R, T, U } = F;
  const shot = (page, name) => rt.shot(page, shotsDir, name);
  const { sally, tina, nico } = F.r5;

  async function heldTerms(tag) {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r6-${tag}` });
    const inv = c.invoices[0];
    const r = await rt.patchInvoice(T.sam, inv.id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    return { c, inv, r };
  }

  // ---------------- RGN-R5-1 retest, full shape ----------------
  await C({ id: 'REG-160', feature: 'Region move x pending change', prd: 'B2',
    title: 'RGN-R5-1 retest: before the move a North-only reader cannot see the South change; after it she sees the closed change with present values, read-only, and it is in her list (plain, id-filtered, North-scoped)',
    steps: 'new South customer, invoice 2500 (POC sam); sam PATCH {dueDate:+60d, paymentTerm:CUSTOM} -> 202 pc; nico (READ/ALL North) GET /api/approvals/{pc} + list ?filter=id:eq:{pc}; admin POST /api/customers/{cid}/move-region North; nico GET invoice, GET /api/approvals/{pc}, list (no regionIds / id:eq / regionIds=N / regionIds=S)',
    expected: 'before: 404, not listed. After: invoice 200; change 200 CLOSED, current and every changes[].current filled, blockedReason null, canApprove false; listed with no regionIds, with id:eq exactly [pc], with regionIds=N; regionIds=S (not granted) lists nothing (TRIAGE r5 userDecision; DESIGN §5.11.9)',
    severity: 'low', codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:118-127, 232-246' }, async (n) => {
    const h = await heldTerms(`a${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    const b = await rt.approvals.get(nico.token, id); const bl = await inList(nico.token, id);
    const mv = await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r6 move a');
    const inv = await get(nico.token, `/api/invoices/${h.inv.id}`);
    const d = await rt.approvals.get(nico.token, id);
    const lAll = await listIds(nico.token, {}); const lId = await listIds(nico.token, { filter: `id:eq:${id}` });
    const lN = await inList(nico.token, id, { regionIds: [R.N] });
    const lS = await rt.approvals.list(nico.token, { filter: `id:eq:${id}` }, { regionIds: [R.S] });
    const nS = lS.status === 200 ? (lS.json.content || []).length : lS.status;
    const ok = b.status === 404 && bl === false && mv.status === 200 && inv.status === 200
      && d.status === 200 && d.json.status === 'CLOSED' && d.json.current != null && (d.json.changes || []).length > 0
      && d.json.changes.every((x) => x.current != null || x.currentText != null) && d.json.blockedReason == null && d.json.canApprove === false
      && Array.isArray(lAll) && lAll.includes(id) && Array.isArray(lId) && lId.length === 1 && lId[0] === id && lN === true
      && (nS === 0 || lS.status === 400 || lS.status === 403);
    return { ok, actual: { before: { detail: b.status, listed: bl }, move: mv.status, invoice: inv.status, after: brief(d),
      listAll: Array.isArray(lAll) ? lAll.includes(id) : lAll, listId: lId, listNorth: lN, listSouth: [lS.status, nS] },
    evidence: `pc ${id}, invoice ${h.inv.id}, customer ${h.c.id}; nico detail after: ${excerpt(d, 250)}` };
  });

  // ---------------- one step around: the change-region paths and the column filter ----------------
  await C({ id: 'REG-161', feature: 'Region move x pending change', prd: 'B1',
    title: 'Admin lists the moved closed change under North (record path) and under South (approver path); the regionId column/filter and the row still name South, where it was asked',
    steps: 'as REG-160; admin GET /api/approvals?filter=id:eq:{pc}&regionIds=N; ...&regionIds=S; GET /api/approvals?filter=regionId:eq:{S}&filter=id:eq:{pc}; filter=regionId:eq:{N}; detail regionId',
    expected: 'listed under N and under S; regionId:eq:S -> [pc]; regionId:eq:N -> []; row regionId = S (TRIAGE r5 userDecision: "The regionId column and filter still mean the region the change was asked in")' }, async (n) => {
    const h = await heldTerms(`b${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    rt.need(await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r6 move b'), 'move');
    const underN = await inList(admin, id, { regionIds: [R.N] }); const underS = await inList(admin, id, { regionIds: [R.S] });
    const fS = await listIds(admin, { filter: [`regionId:eq:${R.S}`, `id:eq:${id}`] });
    const fN = await listIds(admin, { filter: [`regionId:eq:${R.N}`, `id:eq:${id}`] });
    const row = await rt.approvals.list(admin, { filter: `id:eq:${id}` });
    const rowRegion = row.json?.content?.[0]?.regionId;
    const ok = underN === true && underS === true && Array.isArray(fS) && fS.length === 1 && fS[0] === id
      && Array.isArray(fN) && fN.length === 0 && Number(rowRegion) === Number(R.S);
    return { ok, actual: { underN, underS, regionIdEqS: fS, regionIdEqN: fN, rowRegion }, evidence: `pc ${id}, S=${R.S}, N=${R.N}` };
  });

  await C({ id: 'REG-162', feature: 'Region move x pending change', prd: 'B1',
    title: 'The record path keeps book scope and the regions asked: bob (MANAGE/BOOK North, not the POC) does not see the moved change; nora (MANAGE North, READ South) lists it under North, not under South',
    steps: 'as REG-160; bob GET /api/approvals/{pc} and list; nora list regionIds=N and regionIds=S; nora GET detail',
    expected: 'bob: 404 and not listed (the invoice is outside his book); nora: detail 200 with current, listed under N, not under S (she is neither maker nor approver in South, and the record is not in South any more)' }, async (n) => {
    const h = await heldTerms(`c${n}`);
    if (!h.r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(h.r)}` };
    const id = h.r.pendingChange.id;
    rt.need(await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r6 move c'), 'move');
    const bInv = await get(T.bob, `/api/invoices/${h.inv.id}`);
    const bd = await rt.approvals.get(T.bob, id); const bl = await inList(T.bob, id);
    const nd = await rt.approvals.get(T.nora, id);
    const nN = await inList(T.nora, id, { regionIds: [R.N] }); const nS = await inList(T.nora, id, { regionIds: [R.S] });
    const ok = bInv.status === 404 && bd.status === 404 && bl === false && nd.status === 200 && nd.json.current != null && nN === true && nS === false;
    return { ok, actual: { bobInvoice: bInv.status, bob: bd.status, bobListed: bl, nora: brief(nd), noraUnderN: nN, noraUnderS: nS }, evidence: `pc ${id}, invoice ${h.inv.id}` };
  });

  await C({ id: 'REG-163', feature: 'Region move x pending change', prd: 'B2',
    title: 'A held invoice CREATE left behind by a move follows the customer: the new region\'s reader sees it and lists it; the old region\'s plain reader loses it; the old approver keeps it without access; the maker keeps it',
    steps: 'new South customer; sam POST /api/invoices 12000 (> South INVOICE_CREATE 10000) -> 202 pc (entityId null); before: nico 404, tina 200; admin moves customer to North; nico, tina, sally, sam GET /api/approvals/{pc}; nico/tina list id:eq',
    expected: 'after: nico 200 CLOSED and listed; tina 404, not listed; sally 200 with "You no longer have access to this record"; sam 200 (TRIAGE r5 userDecision, FIXLOG r5 B1 sibling test)' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [], prefix: `r6-cr${n}` });
    const r = await rt.createInvoice(T.sam, { customerId: c.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
    if (!r.held) return { ok: false, actual: `setup: create not held: ${excerpt(r)}` };
    const id = r.pendingChange.id;
    const nb = await rt.approvals.get(nico.token, id); const tb = await rt.approvals.get(tina.token, id);
    const mv = await rt.moveCustomerRegion(admin, c.id, R.N, 'r6 move cr');
    const nd = await rt.approvals.get(nico.token, id); const td = await rt.approvals.get(tina.token, id);
    const sd = await rt.approvals.get(sally.token, id); const md = await rt.approvals.get(T.sam, id);
    const nl = await inList(nico.token, id); const tl = await inList(tina.token, id);
    const ok = nb.status === 404 && tb.status === 200 && mv.status === 200 && nd.status === 200 && nd.json.status === 'CLOSED' && nl === true
      && td.status === 404 && tl === false && sd.status === 200 && sd.json.blockedReason === NO_ACCESS && md.status === 200;
    return { ok, actual: { before: { nico: nb.status, tina: tb.status }, move: mv.status, nico: brief(nd), nicoListed: nl, tina: td.status, tinaListed: tl, sally: brief(sd), sam: md.status },
      evidence: `pc ${id}, customer ${c.id}; entityId ${nd.json?.entityId}` };
  });

  await C({ id: 'REG-164', feature: 'Region move x pending change', prd: 'B2',
    title: 'Counts and summary after the fix: a closed moved change is not "awaiting" anyone; summary and awaiting-me count answer 200 for the new-region reader, the old approver and admin',
    steps: 'after REG-160..163; GET /api/approvals/awaiting-me/count and /api/approvals/summary as nico, sally, admin; list view=AWAITING_ME',
    expected: 'all 200; nico awaiting count 0 (no approval right); none of the moved (CLOSED) changes is in anyone\'s AWAITING_ME list' }, async () => {
    const out = {}; let ok = true;
    for (const [name, t] of [['nico', nico.token], ['sally', sally.token], ['admin', admin]]) {
      const c = await get(t, '/api/approvals/awaiting-me/count'); const s = await rt.approvals.summary(t);
      const aw = await rt.list(t, '/api/approvals', { view: 'AWAITING_ME' });
      const rows = aw.json?.content || [];
      const leaked = rows.filter((x) => x.status !== 'PENDING').map((x) => `${x.id}:${x.status}`);
      out[name] = { count: [c.status, c.json?.count ?? c.json], summary: s.status, awaiting: [aw.status, rows.length], awaitingNotPending: leaked };
      ok = ok && c.status === 200 && s.status === 200 && aw.status === 200 && leaked.length === 0;
    }
    ok = ok && Number(out.nico.count[1]) === 0;
    return { ok, actual: out };
  });

  await C({ id: 'REG-165', feature: 'Region move x pending change', prd: 'B1',
    title: 'The OR of change-region and record-region paths produces no duplicates: admin\'s paged list has unique ids and totalElements equals the rows paged; same for nora',
    steps: 'GET /api/approvals size=10 through all pages as admin and nora; compare ids and totalElements',
    expected: 'no id twice; totalElements = rows' }, async () => {
    const out = {}; let ok = true;
    for (const [name, t] of [['admin', admin], ['nora', T.nora]]) {
      const all = await rt.list(t, '/api/approvals', { size: 10 });
      const idsA = (all.json?.content || []).map((x) => x.id);
      const uniq = new Set(idsA).size;
      out[name] = { status: all.status, rows: idsA.length, unique: uniq, total: all.json?.totalElements };
      ok = ok && idsA.length > 0 && uniq === idsA.length && all.json?.totalElements === idsA.length;
    }
    return { ok, actual: out };
  });

  await C({ id: 'REG-166', feature: 'Region move x pending change', prd: 'B2',
    title: 'A customer login still sees the closed change about its own invoice after its account moved region',
    steps: 'new South customer with login, invoice 2500; sam PATCH terms -> 202; admin moves the customer to North; customer GET /api/approvals/{pc}',
    expected: '200 (customer-login branch: its own record; not an internal source); blockedReason is not the staff access reason' }, async (n) => {
    const c = await mkCustomer(F, 'S', { amounts: [2500], prefix: `r6-cl${n}`, login: true });
    const r = await rt.patchInvoice(T.sam, c.invoices[0].id, { dueDate: rt.day(60), paymentTerm: 'CUSTOM' });
    if (!r.held) return { ok: false, actual: `setup: PATCH not held: ${excerpt(r)}` };
    const id = r.pendingChange.id;
    const before = await rt.approvals.get(c.token, id);
    rt.need(await rt.moveCustomerRegion(admin, c.id, R.N, 'r6 move cl'), 'move');
    const ct = await rt.login(c.username, c.password);
    const d = await rt.approvals.get(ct, id);
    const ok = d.status === 200 && d.json.blockedReason !== NO_ACCESS;
    return { ok, actual: { before: before.status, after: brief(d) }, evidence: `pc ${id}, customer ${c.id}; ${excerpt(d, 200)}` };
  });

  // ---------------- UI: History tab link (the RGN-R5-1 UI step) and the ASO-R5-1 neighbour ----------------
  const hU = await heldTerms('ui');
  const uiId = hU.r.held ? hU.r.pendingChange.id : null;
  if (uiId) rt.need(await rt.moveCustomerRegion(admin, hU.c.id, R.N, 'r6 move ui'), 'move ui');

  await C({ id: 'REG-U23', kind: 'UI', feature: 'Region move x pending change', prd: 'B2',
    title: 'RGN-R5-1 UI retest: nico opens the moved invoice, History tab, clicks "Approval #N" and the change opens (not "That change does not exist")',
    steps: 'as REG-160; nico opens /invoices/{id}?tab=history; taps the "Approval #pc" link',
    expected: 'URL #/approvals/{pc}; page title "Approval #pc"; no "That change does not exist"; no "You no longer have access to this record"',
    severity: 'low', codeRef: 'frontend/lib/features/audit/audit_history_panel.dart:502-504' }, async (n) => {
    if (!uiId) return { status: 'BLOCKED', actual: `setup: PATCH not held: ${excerpt(hU.r)}` };
    const app = await rt.openApp({ token: nico.token });
    try {
      await rt.go(app.page, `/invoices/${hU.inv.id}?tab=history`, 5000);
      const s1 = await shot(app.page, `REG-U23-${n}a`);
      const linkLabel = `Approval #${uiId}`;
      let linkNode = null;
      const has = await rt.exists(app.page, linkLabel);
      if (!has) return { ok: false, actual: { link: false, text: (await rt.screenText(app.page)).slice(0, 60) }, screenshot: s1 };
      // The link sits inside an ExpansionTile; a click at the centre of a wide node expands the tile instead.
      const hits = (await rt.semantics(app.page)).filter((x) => x.label === linkLabel || x.text === linkLabel);
      const nd = hits.sort((a, b) => a.w * a.h - b.w * b.h)[0];
      // The tile's semantics merge the link into one group node: the link text starts 52 px into the tile
      // (after the leading icon) on the tile's middle line, so click ~90 px in (checked against the screenshot).
      const cx = nd.w > 200 ? nd.x - nd.w / 2 + 90 : nd.x;
      await rt.clickAt(app.page, cx, nd.y, 4000);
      linkNode = { x: nd.x, y: nd.y, w: nd.w, h: nd.h, role: nd.role, hits: hits.length, clickedAt: [cx, nd.y] };
      const txt = await rt.screenText(app.page);
      const s2 = await shot(app.page, `REG-U23-${n}b`);
      const url = app.page.url();
      const gone = txt.some((t) => t.includes('does not exist'));
      const noAccess = txt.some((t) => t.includes(NO_ACCESS));
      const ok = url.includes(`#/approvals/${uiId}`) && !gone && !noAccess && txt.some((t) => t.includes(`Approval #${uiId}`));
      return { ok, actual: { url, linkNode, doesNotExist: gone, noAccessNotice: noAccess, text: txt.slice(-25) }, screenshot: s2, evidence: `pc ${uiId}, invoice ${hU.inv.id}; before click ${s1}` };
    } finally { await app.close(); }
  });

  await C({ id: 'REG-U24', kind: 'UI', feature: 'Region move x pending change', prd: 'B2',
    title: 'Approval page after a move: for nico (can see the record) the Record cell links to the invoice; for sally (no access) the Record is plain text and a click stays on the change (ASO-R5-1 neighbour)',
    steps: 'as REG-U23; nico opens /approvals/{pc} and clicks the INV-… Record value; sally opens /approvals/{pc} and clicks the same text',
    expected: 'nico lands on #/invoices/{id}; sally stays on #/approvals/{pc}, sees "You no longer have access to this record", and no "Open INV-…" button',
    severity: 'low', codeRef: 'frontend/lib/features/approvals/approval_detail_screen.dart:150-200' }, async (n) => {
    if (!uiId) return { status: 'BLOCKED', actual: 'setup: PATCH not held' };
    const invNo = (await get(admin, `/api/invoices/${hU.inv.id}`)).json?.invoiceNumber;
    const out = {}; const shots = [];
    for (const [name, t] of [['nico', nico.token], ['sally', sally.token]]) {
      const app = await rt.openApp({ token: t });
      try {
        await rt.go(app.page, `/approvals/${uiId}`, 5000);
        const txt = await rt.screenText(app.page);
        const notice = txt.some((x) => x.includes(NO_ACCESS));
        const openBtn = txt.some((x) => /^Open INV/.test(x));
        let clicked = false;
        const rec = invNo ? await rt.find(app.page, invNo).catch(() => null) : null;
        if (rec) { await rt.clickAt(app.page, rec.x, rec.y, 3500); clicked = true; }
        shots.push(await shot(app.page, `REG-U24-${n}-${name}`));
        out[name] = { notice, openBtn, recordFound: !!rec, clicked, urlAfter: app.page.url().replace(/^.*#/, '#') };
      } finally { await app.close(); }
    }
    const ok = out.nico.recordFound && !out.nico.notice && out.nico.urlAfter.startsWith(`#/invoices/${hU.inv.id}`)
      && out.sally.recordFound && out.sally.notice && !out.sally.openBtn && out.sally.urlAfter.startsWith(`#/approvals/${uiId}`);
    return { ok, actual: { invoiceNumber: invNo, ...out }, screenshot: shots.join(', '), evidence: `pc ${uiId}, invoice ${hU.inv.id}` };
  });

  // For the as-of list case: a pending change that stays pending until the world is aged.
  const pre = await heldTerms('asof');
  F.r6 = { pre: pre.r.held ? pre : null };
}

// Runs after asOfCases aged the world by 4 days; the move happens now.
async function ext6AfterAge(F, RUN) {
  const { C } = RUN;
  const { admin, R } = F;
  const h = F.r6?.pre;
  await C({ id: 'REG-167', feature: 'As-of x region move x pending change', prd: 'B3',
    title: 'Lists as of before the move follow the record\'s region THEN: nico\'s as-of list lacks the change and tina\'s has it; live it is the reverse',
    steps: 'held terms change on a South invoice (pending); ageWorld(4); admin moves the customer to North now; GET /api/approvals?filter=id:eq:{pc}&asOf=<2 days ago> as nico and tina; same live; nico detail as of and live',
    expected: 'asOf: nico not listed, tina listed (statusAt PENDING); live: nico listed and detail 200, tina not listed (FIXLOG r5 B1 "nico\'s as-of list does not contain the pc"; TRIAGE r4/r5 userDecisions)' }, async () => {
    if (!h) return { status: 'BLOCKED', actual: 'no held change from ext6 setup' };
    const id = h.r.pendingChange.id;
    const mv = await rt.moveCustomerRegion(admin, h.c.id, R.N, 'r6 as-of move');
    const tN = await rt.login('nico'); const tT = await rt.login('tina');
    const past = rt.day(-2);
    const aN = await inList(tN, id, { asOf: past }); const aT = await inList(tT, id, { asOf: past });
    const lN = await inList(tN, id); const lT = await inList(tT, id);
    const dA = await rt.approvals.get(tN, id, { asOf: past }); const dL = await rt.approvals.get(tN, id);
    const ok = mv.status === 200 && aN === false && aT === true && lN === true && lT === false && dA.status === 404 && dL.status === 200;
    return { ok, actual: { move: mv.status, asOf: { nicoListed: aN, tinaListed: aT, nicoDetail: dA.status }, live: { nicoListed: lN, tinaListed: lT, nicoDetail: brief(dL) } },
      evidence: `pc ${id}, customer ${h.c.id}, asOf ${past}` };
  });
}

module.exports = { ext6Cases, ext6AfterAge };
