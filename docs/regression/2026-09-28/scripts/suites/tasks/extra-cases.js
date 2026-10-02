// Round 3 extensions (API): gaps next to the existing coverage.
//   TAS-API-35 record-level Sales POC follows the invoice (live resolution, rights follow the seat)
//   TAS-API-36 losing / regaining a region grant removes / restores a directly assigned task
//   TAS-API-37 every task write is audited under TASK and the audit is region-scoped
//   TAS-API-38 My tasks count and summary answer "which regions"
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, customerIn, task, getTask, brief, ids } = H;

module.exports = async function extraCases(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens;
  const U = w.users;
  const RG = w.regions;

  await tcase({ id: 'TAS-API-35', feature: 'Record-level role follows the record', kind: 'API', prd: 'A6',
    title: 'A task assigned to "Sales POC of this invoice" follows the invoice\'s Sales POC: when it changes, My tasks and the right to work the task move with it',
    steps: 'fresh users X, Y (E2E_TASKVIEW: TASK_VIEW, no TASK_MANAGE; MANAGE/ALL North); fresh North customer (Collection POC X) + invoice with Sales POC X; admin task on the invoice [SALES_POC@RECORD]; X /mine, X START; admin PATCH /api/invoices/{id} {salesPocUserId:Y, version}; X /mine, X MARK DONE; Y /mine, Y DONE; GET task',
    expected: 'DESIGN §5.9.1 (ROLE -> all live holders), §5.9.3 RECORD branch (Invoice.salesPoc = me), §5.9.2 Rights (effective assignee, live): before: X lists it and may START (200); after the POC change: X no longer lists it and gets 403 on status; Y lists it, may set DONE (200); GET assignees people = [Y]' }, async () => {
    const c = checks();
    const grants = [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }];
    const X = await rt.createStaff(T.admin, 'E2E_TASKVIEW', 'spx', { grants });
    const Y = await rt.createStaff(T.admin, 'E2E_TASKVIEW', 'spy', { grants });
    const xt = await rt.login(X.username); const yt = await rt.login(Y.username);
    const cu = await customerIn(w, 'NORTH', { payment: false, poc: X.id, prefix: 'spf' });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('spFollow'), assignees: [rt.tok.role('SALES_POC', 'RECORD')] });
    c.that('X lists it before', ids(await rt.tasks.mine(xt)).includes(t.id));
    c.that('Y does not list it before', !ids(await rt.tasks.mine(yt)).includes(t.id));
    const s1 = await rt.tasks.setStatus(xt, t.id, 'IN_PROGRESS', t.version);
    c.that('X may START (200)', s1.status === 200, brief(s1));
    const invNow = await rt.api('GET', `/api/invoices/${cu.invoice.id}`, { token: T.admin });
    const p = await rt.patchInvoice(T.admin, cu.invoice.id, { salesPocUserId: Y.id, version: invNow.json?.version });
    c.that('invoice Sales POC changed (200, not held)', p.status === 200 && !p.held, brief(p));
    const cur = (await getTask(T.admin, t.id)).json;
    c.that('X no longer lists it', !ids(await rt.tasks.mine(xt)).includes(t.id));
    const s2 = await rt.tasks.setStatus(xt, t.id, 'DONE', cur.version);
    c.that('X may no longer change its status (403)', s2.status === 403, brief(s2));
    c.that('Y lists it now', ids(await rt.tasks.mine(yt)).includes(t.id));
    const s3 = await rt.tasks.setStatus(yt, t.id, 'DONE', cur.version);
    c.that('Y may set DONE (200)', s3.status === 200 && s3.json?.status === 'DONE', brief(s3));
    const a = (await getTask(T.admin, t.id)).json?.assignees || [];
    const people = (a[0]?.people || []).map((x) => x.userId ?? x.id);
    c.that('assignee resolves to Y only', a.length === 1 && people.length === 1 && people[0] === Y.id, a);
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/assignee/AssignedTo.java' });
  });

  await tcase({ id: 'TAS-API-36', feature: 'Assignee loses the region', kind: 'API', prd: 'B1',
    title: 'A person assigned a North task who loses their North grant no longer sees it (My tasks, count, by id, status); regaining the grant brings it back',
    steps: 'fresh user X (E2E_REGIONAL, MANAGE/ALL North); admin task on a fresh North customer assigned USER X; X /mine, /mine/count; admin PUT grants X -> WEST only; X /mine, /mine/count, GET /api/tasks/{id}, POST status; admin restores MANAGE North; X /mine',
    expected: 'PRD B1 (a user holds rights per region; nothing in a region = sees nothing there), DESIGN §5.9.5 (mine = forTasks AND AssignedTo): after the grant is gone: not in /mine, count 0, GET 404, status 404/403; after it is back: listed again' }, async () => {
    const c = checks();
    const X = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'lose', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    let xt = await rt.login(X.username);
    const cu = await customerIn(w, 'NORTH', { payment: false, prefix: 'lose' });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('lose'), assignees: [rt.tok.user(X.id)] });
    c.that('before: listed', ids(await rt.tasks.mine(xt)).includes(t.id));
    c.that('before: count 1', (await rt.tasks.mineCount(xt)).json?.count === 1);
    const g = await rt.setGrants(T.admin, X.id, [{ regionId: RG.WEST.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]);
    c.that('grants -> WEST only', g.ok, brief(g));
    xt = await rt.login(X.username);
    const m = await rt.tasks.mine(xt);
    c.that('after: not listed', m.status === 200 && !ids(m).includes(t.id), brief(m));
    const mc = await rt.tasks.mineCount(xt);
    c.that('after: count 0', mc.status === 200 && mc.json?.count === 0, brief(mc));
    const gt = await getTask(xt, t.id);
    c.that('after: GET 404', gt.status === 404, brief(gt));
    const st = await rt.tasks.setStatus(xt, t.id, 'IN_PROGRESS', t.version);
    c.that('after: status refused (404/403)', st.status === 404 || st.status === 403, brief(st));
    rt.need(await rt.setGrants(T.admin, X.id, [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }]), 'restore grant');
    xt = await rt.login(X.username);
    c.that('restored: listed again', ids(await rt.tasks.mine(xt)).includes(t.id));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskService.java:492' });
  });

  await tcase({ id: 'TAS-API-37', feature: 'Task audit trail', kind: 'API', prd: 'A6',
    title: 'Every task write is audited under TASK (created, updated, assignees changed, status changed), and the audit is readable only where the task is',
    steps: 'admin creates a task on a fresh North invoice; PATCH title; PATCH assignees [USER nora]; POST status DONE; admin GET /api/audit?entityType=TASK&entityId={id}; wendy (West only) GET the same',
    expected: 'DESIGN §5.9.2 General rules: TASK_CREATED, TASK_UPDATED, TASK_ASSIGNEES_CHANGED, TASK_STATUS_CHANGED rows, actor admin; wendy (no North) gets 403/404, never the rows' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH', { payment: false, prefix: 'aud' });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('aud') });
    const p1 = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body: { title: `${t.title}-2`, version: t.version } });
    const p2 = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body: { assignees: [rt.tok.user(U.nora.id)], version: p1.json?.version } });
    const s = await rt.tasks.setStatus(T.admin, t.id, 'DONE', p2.json?.version);
    c.that('writes 200', p1.status === 200 && p2.status === 200 && s.status === 200, [brief(p1), brief(p2), brief(s)]);
    const a = await rt.api('GET', '/api/audit', { token: T.admin, query: { entityType: 'TASK', entityId: t.id } });
    const actions = Array.isArray(a.json) ? a.json.map((e) => e.action) : [];
    for (const k of ['TASK_CREATED', 'TASK_UPDATED', 'TASK_ASSIGNEES_CHANGED', 'TASK_STATUS_CHANGED']) c.that(`audit has ${k}`, actions.includes(k), actions);
    c.that('actor admin', Array.isArray(a.json) && a.json.filter((e) => /^TASK_/.test(e.action)).every((e) => e.changedByUsername === 'admin'), a.json && a.json.map((e) => e.changedByUsername));
    const wa = await rt.api('GET', '/api/audit', { token: T.wendy, query: { entityType: 'TASK', entityId: t.id } });
    c.that('wendy (West only) refused', wa.status === 403 || wa.status === 404, brief(wa));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskService.java:218', evidence: `GET /api/audit?entityType=TASK&entityId=${t.id} -> ${a.status} actions ${JSON.stringify(actions)}; wendy -> ${wa.status}` });
  });

  await tcase({ id: 'TAS-API-38', feature: 'My tasks count by region', kind: 'API', prd: 'B1',
    title: 'My tasks, its summary and its count (the nav badge) answer "which regions": asking for South counts only South tasks',
    steps: 'fresh user X (E2E_REGIONAL, MANAGE/ALL North + South); admin creates a North and a South task (fresh customers) assigned USER X; X GET /api/tasks/mine/count, /mine/summary and /mine without and with regionIds=SOUTH, and with regionIds=NORTH',
    expected: 'PRD B1 ("every list, filter, count and dashboard figure answers which regions am I asking about"), DESIGN §6.0 view scope: all -> 2; SOUTH -> 1 (the South task) for list, summary and count; NORTH -> 1' }, async () => {
    const c = checks();
    const X = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'mcr', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: RG.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const xt = await rt.login(X.username);
    const cn = await customerIn(w, 'NORTH', { payment: false, prefix: 'mcn' });
    const cs = await customerIn(w, 'SOUTH', { payment: false, prefix: 'mcs' });
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: cn.invoice.id, title: rt.uniq('mcN'), assignees: [rt.tok.user(X.id)] });
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: cs.invoice.id, title: rt.uniq('mcS'), assignees: [rt.tok.user(X.id)] });
    const q = (rid) => (rid ? { regionIds: rid } : {});
    const cnt = async (rid) => rt.api('GET', '/api/tasks/mine/count', { token: xt, query: q(rid) });
    const sum = async (rid) => rt.api('GET', '/api/tasks/mine/summary', { token: xt, query: q(rid) });
    const lst = async (rid) => rt.api('GET', '/api/tasks/mine', { token: xt, query: { size: 50, ...q(rid) } });
    const [ca, cS, cN] = [await cnt(), await cnt(RG.SOUTH.id), await cnt(RG.NORTH.id)];
    const [sa, sS] = [await sum(), await sum(RG.SOUTH.id)];
    const [la, lS] = [await lst(), await lst(RG.SOUTH.id)];
    c.that('list all 2', ids(la).includes(tn.id) && ids(la).includes(ts.id), brief(la));
    c.that('list SOUTH = South task only', ids(lS).includes(ts.id) && !ids(lS).includes(tn.id), ids(lS));
    c.that('summary all 2', sa.json?.count === 2, brief(sa));
    c.that('summary SOUTH 1', sS.json?.count === 1, brief(sS));
    c.that('count all 2', ca.json?.count === 2, brief(ca));
    c.that('count SOUTH 1', cS.status === 200 && cS.json?.count === 1, brief(cS));
    c.that('count NORTH 1', cN.status === 200 && cN.json?.count === 1, brief(cN));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskService.java:468',
      evidence: `mine/count all=${ca.text} SOUTH=${cS.text} NORTH=${cN.text}; mine/summary all=${sa.text} SOUTH=${sS.text}` });
  });
};

// ---- UI extension -----------------------------------------------------------------------------
async function tileValue(page, label) {
  const nodes = await rt.semantics(page);
  const l = nodes.find((n) => n.text === label && n.y < 220);
  if (!l) return null;
  const v = nodes.find((n) => /^\d+$/.test(n.text || '') && Math.abs(n.y - l.y - 28) < 14 && n.x - 110 < l.x && l.x - n.x < 110 && n.x <= l.x + 5);
  return v ? v.text : null;
}

module.exports.ui = async function extraUi(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const RG = w.regions;
  await tcase({ id: 'TAS-UI-23', feature: 'My tasks narrowed by region (UI)', kind: 'UI', prd: 'B1',
    title: 'On "My tasks", narrowing the region selector to South narrows the rows, the tiles and the nav badge request to South',
    steps: 'fresh user X (E2E_REGIONAL, MANAGE/ALL North + South); admin creates a North and a South task assigned USER X; X opens /my-tasks; region selector: untick NORTH',
    expected: 'PRD B1 / DESIGN §7 view scope (task_providers.dart myTasksCountProvider watches viewScopeProvider): before: both rows, tile Tasks 2; after: South row only, tile Tasks 1, requests /api/tasks/mine?..regionIds=<South> and /api/tasks/mine/count?regionIds=<South>' }, async (a) => {
    const c = checks();
    const X = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'uimr', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }, { regionId: RG.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const cn = await customerIn(w, 'NORTH', { payment: false, prefix: 'umn' });
    const cs = await customerIn(w, 'SOUTH', { payment: false, prefix: 'ums' });
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: cn.invoice.id, title: rt.uniq('umN'), assignees: [rt.tok.user(X.id)] });
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: cs.invoice.id, title: rt.uniq('umS'), assignees: [rt.tok.user(X.id)] });
    const app = await H.openAs(X.username, '/my-tasks');
    const { page, requests } = app;
    let shot = '';
    try {
      c.that('both rows before', !!(await H.waitText(page, tn.title, 8000)) && !!(await H.waitText(page, ts.title, 3000)));
      c.that('tile Tasks 2 before', (await tileValue(page, 'Tasks')) === '2', await tileValue(page, 'Tasks'));
      await rt.chooseRegions(page, [/^NORTH/]);
      await page.waitForTimeout(3000);
      c.that('South row shown', !!(await H.waitText(page, ts.title, 6000)));
      c.that('North row gone', !(await rt.exists(page, tn.title)));
      c.that('tile Tasks 1 after', (await tileValue(page, 'Tasks')) === '1', await tileValue(page, 'Tasks'));
      const re = new RegExp(`regionIds=${RG.SOUTH.id}(&|$)`);
      c.that('list request narrowed', requests.some((r) => r.url.startsWith('/api/tasks/mine?') && re.test(r.url)), requests.filter((r) => r.url.startsWith('/api/tasks/mine')).map((r) => r.url).slice(-6));
      c.that('badge request narrowed', requests.some((r) => r.url.startsWith('/api/tasks/mine/count') && re.test(r.url)), requests.filter((r) => r.url.startsWith('/api/tasks/mine/count')).map((r) => r.url));
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 600));
    } finally {
      try { shot = await rt.shot(page, R.shotsDir, `TAS-UI-23-${a}`); } catch { /* ignore */ }
      c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 5));
      await app.close();
    }
    return done(c, { screenshot: shot, codeRef: 'frontend/lib/features/tasks/task_providers.dart:55',
      evidence: `requests=${JSON.stringify(requests.filter((r) => /\/api\/tasks\/mine/.test(r.url)).map((r) => `${r.method} ${r.url} ${r.status}`)).slice(0, 900)}` });
  });
};
