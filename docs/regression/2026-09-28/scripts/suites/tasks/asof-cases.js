// As-of cases for tasks (B3 applied to A6): DESIGN §5.9.3 (assignees and seats at T), §6.1 (tasks GETs AWARE),
// §8.5 TaskAsOfTest, §8.x CustomerRegionMoveTest (BE-12 addition), D2 / §6.17 (read-only in historical mode).
//
// prepare(w) builds two copies of the fixtures (one per attempt) BEFORE the world is aged; run(...) ages the world
// once, then each attempt changes its own copy now and reads it as of before the change.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, customerIn, task, getTask, brief, ids } = H;
const CODE = 'backend/src/main/java/com/geneinvoice/task/TaskService.java';

async function prepare(w) {
  const T = w.tokens; const U = w.users;
  const copies = [];
  for (let i = 0; i < 2; i++) {
    const cu = await customerIn(w, 'NORTH', { payment: false, prefix: 'asof' }); // Collection seat: nora
    const tStatus = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('asofS'), dueDate: rt.day(5), assignees: [rt.tok.user(U.nora.id)] });
    const tRole = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('asofR'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const mv = await customerIn(w, 'NORTH', { payment: false, prefix: 'asofM' });
    const tMove = await task(T.admin, { entityType: 'INVOICE', entityId: mv.invoice.id, title: rt.uniq('asofMv') });
    copies.push({ cu, tStatus, tRole, mv, tMove });
  }
  return copies;
}

async function run(w, R, copies, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users; const RG = w.regions;
  rt.ageWorld(10);
  const then = rt.day(-2);
  let attemptNo = 0;
  const copy = (a) => copies[Math.min(a, copies.length) - 1];
  const changed = new Set();

  // Changes made "now" on a copy (once per copy): status DONE + assignee nora -> sam; seat nora -> sam; move N -> S.
  async function changeNow(cp) {
    if (changed.has(cp)) return cp.after;
    changed.add(cp);
    const t0 = (await getTask(T.admin, cp.tStatus.id)).json;
    const p = rt.need(await rt.api('PATCH', `/api/tasks/${t0.id}`, { token: T.admin, body: { assignees: [rt.tok.user(U.sam.id)], title: 'Renamed later', version: t0.version } }), 'patch');
    rt.need(await rt.tasks.setStatus(T.admin, t0.id, 'DONE', p.version), 'done');
    const seats = rt.need(await rt.api('GET', `/api/customers/${cp.cu.id}/pocs`, { token: T.admin }), 'pocs');
    await rt.addCustomerPoc(T.admin, cp.cu.id, 'COLLECTION', U.sam.id, true);
    const noraSeat = seats.find((s) => (s.userId || s.user?.id) === U.nora.id && /COLLECTION/.test(s.pocType));
    if (noraSeat) rt.need(await rt.api('DELETE', `/api/customers/${cp.cu.id}/pocs/${noraSeat.id}`, { token: T.admin }), 'remove seat', [200, 204]);
    const mv = await rt.moveCustomerRegion(T.admin, cp.mv.id, RG.SOUTH.id);
    rt.need(mv, 'move');
    const late = await task(T.admin, { entityType: 'INVOICE', entityId: cp.cu.invoice.id, title: rt.uniq('asofLate') });
    cp.after = { late, noraSeat: !!noraSeat };
    return cp.after;
  }

  await tcase({ id: 'TAS-ASOF-01', feature: 'Task as of a date', kind: 'API', prd: 'B3',
    title: 'A task read as of a date shows its status, title and assignees then; read-only (version null, flags false)',
    steps: 'task created (aged 10 days) OPEN, title X, assigned USER nora; now: PATCH title "Renamed later", assignees [USER sam], status DONE. GET /api/tasks/{id}?asOf=<today-2> and live',
    expected: 'DESIGN §6.12 ("In historical mode version is null and both flags are false"), §8.5 TaskAsOfTest: as-of -> OPEN, title X, assignee USER nora, version null, canEdit/canChangeStatus false, X-View-As-Of = date; live -> DONE, "Renamed later", USER sam' }, async (a) => {
    const c = checks();
    const cp = copy(a); await changeNow(cp);
    const old = await getTask(T.admin, cp.tStatus.id, { asOf: then });
    const j = old.json || {};
    c.that('as-of 200', old.status === 200, brief(old));
    c.that('X-View-As-Of header = date', old.headers['x-view-as-of'] === then, old.headers['x-view-as-of']);
    c.that('status OPEN then', j.status === 'OPEN', j.status);
    c.that('title then', j.title === cp.tStatus.title, j.title);
    c.that('assignee nora then', (j.assignees || []).length === 1 && j.assignees[0].token.userId === U.nora.id, j.assignees);
    c.that('version null, flags false', j.version === null && j.canEdit === false && j.canChangeStatus === false, { version: j.version, canEdit: j.canEdit, canChangeStatus: j.canChangeStatus });
    c.that('overdue computed against the as-of date (due today+5-10 = -5 < then)', j.overdue === true, { dueDate: j.dueDate, overdue: j.overdue });
    const live = (await getTask(T.admin, cp.tStatus.id)).json;
    c.that('live DONE, renamed, sam', live.status === 'DONE' && live.title === 'Renamed later' && live.assignees[0].token.userId === U.sam.id, { s: live.status, t: live.title });
    return done(c, { codeRef: `${CODE}:523` });
  });

  await tcase({ id: 'TAS-ASOF-02', feature: 'My tasks as of a date', kind: 'API', prd: 'B3',
    title: 'My tasks as of a date uses the assignees and the seats of that day',
    steps: 'as TAS-ASOF-01, plus: role task on the customer [COLLECTION_POC@CUSTOMER]; now the seat moves nora -> sam. nora /api/tasks/mine?asOf=<today-2> and live; sam live; GET role task ?asOf',
    expected: 'DESIGN §5.9.3 (seats at T), §8.5 TaskAsOfTest: nora as-of lists both tasks (direct + role); live lists neither; sam live lists both; the role task as-of names nora as holder, live names sam' }, async (a) => {
    const c = checks();
    const cp = copy(a); const af = await changeNow(cp);
    c.that('fixture: nora seat removed', af.noraSeat);
    const mo = await rt.tasks.mine(T.nora, { filter: [`customerId:eq:${cp.cu.id}`] }, { asOf: then });
    c.that('nora as-of: both', mo.status === 200 && ids(mo).includes(cp.tStatus.id) && ids(mo).includes(cp.tRole.id), brief(mo));
    const ml = await rt.tasks.mine(T.nora, { filter: [`customerId:eq:${cp.cu.id}`] });
    c.that('nora live: neither', !ids(ml).includes(cp.tStatus.id) && !ids(ml).includes(cp.tRole.id), ids(ml));
    const sl = await rt.tasks.mine(T.sam, { filter: [`customerId:eq:${cp.cu.id}`] });
    c.that('sam live: both', ids(sl).includes(cp.tStatus.id) && ids(sl).includes(cp.tRole.id), ids(sl));
    const ro = (await getTask(T.admin, cp.tRole.id, { asOf: then })).json;
    const rl = (await getTask(T.admin, cp.tRole.id)).json;
    c.that('role holder then = nora', (ro.assignees[0].people || []).map((p) => p.userId).join() === String(U.nora.id), ro.assignees);
    c.that('role holder now = sam', (rl.assignees[0].people || []).map((p) => p.userId).join() === String(U.sam.id), rl.assignees);
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/assignee/AssignedTo.java:88' });
  });

  await tcase({ id: 'TAS-ASOF-03', feature: 'Task region as of a date', kind: 'API', prd: 'B3',
    title: 'After a customer moves North -> South, tasks as of before the move answer in North, live in South',
    steps: 'customer (aged) in NORTH with an invoice task; now admin moves it to SOUTH. GET /api/tasks?regionIds=NORTH&asOf=<today-2>, ?regionIds=SOUTH&asOf=..., live ?regionIds=SOUTH / NORTH',
    expected: 'DESIGN §8 CustomerRegionMoveTest (BE-12, PC-16): N as-of lists it (regionCode NORTH); S as-of does not; live S lists it; live N does not' }, async (a) => {
    const c = checks();
    const cp = copy(a); await changeNow(cp);
    const q = { filter: [`id:eq:${cp.tMove.id}`] };
    const nO = await rt.tasks.list(T.admin, q, { regionIds: [RG.NORTH.id], asOf: then });
    const sO = await rt.tasks.list(T.admin, q, { regionIds: [RG.SOUTH.id], asOf: then });
    const sL = await rt.tasks.list(T.admin, q, { regionIds: [RG.SOUTH.id] });
    const nL = await rt.tasks.list(T.admin, q, { regionIds: [RG.NORTH.id] });
    c.that('NORTH as-of lists it, regionCode NORTH', ids(nO).includes(cp.tMove.id) && nO.json.content[0].regionCode === 'NORTH', brief(nO));
    c.that('SOUTH as-of does not', sO.status === 200 && !ids(sO).includes(cp.tMove.id), brief(sO));
    c.that('SOUTH live lists it', ids(sL).includes(cp.tMove.id), brief(sL));
    c.that('NORTH live does not', !ids(nL).includes(cp.tMove.id), brief(nL));
    c.that('envelope asOf', nO.json?.asOf === then, nO.json?.asOf);
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskMoveParticipant.java' });
  });

  await tcase({ id: 'TAS-ASOF-04', feature: 'As-of reads are read-only', kind: 'API', prd: 'B3',
    title: 'Task writes are refused while a past date is viewed; bad asOf values are refused',
    steps: 'POST /api/tasks?asOf=<today-2>; POST /api/tasks/{id}/status?asOf=...; GET /api/tasks?asOf=<tomorrow>; ?asOf=2026-13-01; ?asOf=<today-40> (only when /api/history/range has a start day)',
    expected: 'DESIGN §6.17: writes 400 AS_OF_READ_ONLY (nothing written); AS_OF_FUTURE; AS_OF_INVALID; AS_OF_BEFORE_HISTORY' }, async (a) => {
    const c = checks();
    const cp = copy(a);
    const cr = await rt.api('POST', '/api/tasks', { token: T.admin, query: { asOf: then }, body: { entityType: 'INVOICE', entityId: cp.cu.invoice.id, title: 'historic' } });
    c.that('create 400 AS_OF_READ_ONLY', cr.status === 400 && cr.json?.error === 'AS_OF_READ_ONLY', brief(cr));
    const cur = (await getTask(T.admin, cp.tRole.id)).json;
    const st = await rt.api('POST', `/api/tasks/${cp.tRole.id}/status`, { token: T.admin, query: { asOf: then }, body: { status: 'DONE', version: cur.version } });
    c.that('status 400 AS_OF_READ_ONLY', st.status === 400 && st.json?.error === 'AS_OF_READ_ONLY', brief(st));
    c.that('status unchanged', (await getTask(T.admin, cp.tRole.id)).json.status === cur.status);
    const fu = await rt.api('GET', '/api/tasks', { token: T.admin, query: { asOf: rt.day(1) } });
    c.that('future 400 AS_OF_FUTURE', fu.status === 400 && fu.json?.error === 'AS_OF_FUTURE', brief(fu));
    const inv = await rt.api('GET', '/api/tasks', { token: T.admin, query: { asOf: '2026-13-01' } });
    c.that('invalid 400 AS_OF_INVALID', inv.status === 400 && inv.json?.error === 'AS_OF_INVALID', brief(inv));
    // Only meaningful once history has a start day (a fresh e2e database reports historyStartsOn null: README §3.3).
    const range = (await rt.api('GET', '/api/history/range', { token: T.admin })).json || {};
    if (range.historyStartsOn) {
      const bh = await rt.api('GET', '/api/tasks', { token: T.admin, query: { asOf: rt.day(-40) } });
      c.that('before history 400 AS_OF_BEFORE_HISTORY', bh.status === 400 && bh.json?.error === 'AS_OF_BEFORE_HISTORY', brief(bh));
    }
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/common/scope/ViewScopeInterceptor.java' });
  });

  await tcase({ id: 'TAS-ASOF-05', feature: 'Tasks created later', kind: 'API', prd: 'B3',
    title: 'A task created after the as-of date did not exist then: missing by id, absent from lists and counts',
    steps: 'task created today on an aged invoice; GET /api/tasks/{id}?asOf=<today-2>; GET /api/tasks?entityType&entityId&asOf; /api/tasks/count?asOf; live',
    expected: 'DESIGN D9: by id 404 as of then; the tab list and count as of then exclude it; live includes it' }, async (a) => {
    const c = checks();
    const cp = copy(a); const af = await changeNow(cp);
    const g = await getTask(T.admin, af.late.id, { asOf: then });
    c.that('by id as-of 404', g.status === 404, brief(g));
    const lo = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cp.cu.invoice.id }, { asOf: then });
    const ll = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cp.cu.invoice.id });
    c.that('as-of list excludes it, live includes it', !ids(lo).includes(af.late.id) && ids(ll).includes(af.late.id) && ids(lo).includes(cp.tStatus.id), { asof: ids(lo), live: ids(ll) });
    const co = await rt.api('GET', '/api/tasks/count', { token: T.admin, query: { entityType: 'INVOICE', entityId: cp.cu.invoice.id, asOf: then } });
    const cl = await rt.api('GET', '/api/tasks/count', { token: T.admin, query: { entityType: 'INVOICE', entityId: cp.cu.invoice.id } });
    c.that('count as-of {1 total, 1 open}; live {2, 1}', co.json?.count === 1 && co.json?.open === 1 && cl.json?.count === 2 && cl.json?.open === 1, { asof: co.json, live: cl.json });
    return done(c, { codeRef: `${CODE}:476` });
  });

  return { then, copies };
}

module.exports = { prepare, run };
