// Final exploratory pass, area "tasks" (A6 tasks, A3 assignees), written from the PRD and DESIGN §5.9 / §6.12 / §7.7.2.
//
//   source <e2e>/runtime/tasks/env.sh ; node final-explore.js
//
// Results: runs/final/tasks-explore.json ; screenshots: runs/final/tasks/shots/explore/.
// Every FAIL is re-run once with fresh fixtures; one that does not reproduce is NOT_TESTED.
// The as-of block ages the whole database (rt.ageWorld) and therefore runs last.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');

const E2E = rt.HOME;
const OUT = path.join(E2E, 'runs', 'final', 'tasks-explore.json');
const SHOTS = path.join(E2E, 'runs', 'final', 'tasks', 'shots', 'explore');
fs.mkdirSync(SHOTS, { recursive: true });

const cases = [];
function rec(c) {
  const status = c.status;
  const row = {
    id: c.id, feature: c.feature || '', kind: c.kind || 'API', prd: c.prd || '', title: c.title || '', status,
    severity: status === 'FAIL' ? (c.severity || 'medium') : '',
    steps: c.steps || '', expected: c.expected || '', actual: typeof c.actual === 'string' ? c.actual : JSON.stringify(c.actual),
    evidence: c.evidence || '', screenshot: c.screenshot || '', codeRef: c.codeRef || '',
  };
  const i = cases.findIndex((x) => x.id === row.id);
  if (i >= 0) cases[i] = row; else cases.push(row);
  console.log(`[${status}] ${row.id} ${row.title}${status === 'PASS' ? '' : '\n    ' + row.actual.slice(0, 600)}`);
}
function save() {
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'tasks', round: 'final-explore', env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
  const n = (s) => cases.filter((c) => c.status === s).length;
  console.log(`${OUT}: ${cases.length} cases, ${n('PASS')} PASS, ${n('FAIL')} FAIL, ${n('BLOCKED')} BLOCKED, ${n('NOT_TESTED')} NOT_TESTED`);
}

/** Named checks: ok when all hold. */
function checks() {
  const items = [];
  const fmt = (d) => (d === undefined ? '' : ` [${(typeof d === 'string' ? d : JSON.stringify(d)).slice(0, 500)}]`);
  return {
    that(label, cond, detail) { items.push({ label, ok: !!cond, detail }); return !!cond; },
    get ok() { return items.length > 0 && items.every((i) => i.ok); },
    summary() { return items.map((i) => `${i.ok ? 'ok' : 'NOT OK'}: ${i.label}${fmt(i.detail)}`).join('; '); },
  };
}

/** Runs one case; a FAIL is re-run once (fresh fixtures); an exception is BLOCKED. */
async function run(meta, fn) {
  const attempt = async (n) => {
    try { return await fn(n); } catch (e) { return { blocked: true, actual: String(e && e.stack || e).slice(0, 1500) }; }
  };
  let r = await attempt(1);
  if (r.blocked) { rec({ ...meta, status: 'BLOCKED', actual: r.actual }); return; }
  if (r.ok) { rec({ ...meta, status: 'PASS', ...strip(r) }); return; }
  const r2 = await attempt(2);
  if (r2.blocked) { rec({ ...meta, status: 'BLOCKED', actual: `attempt 1 FAIL: ${r.actual}; attempt 2 threw: ${r2.actual}` }); return; }
  if (r2.ok) {
    rec({ ...meta, status: 'NOT_TESTED', ...strip(r2), actual: `Did not reproduce. Attempt 1: ${r.actual} | Attempt 2 passed: ${r2.actual}` });
    return;
  }
  rec({ ...meta, status: 'FAIL', ...strip(r2), actual: `(reproduced on 2 attempts) ${r2.actual}`, severity: r2.severity || meta.severity });
}
function strip(r) { const { ok, blocked, ...rest } = r; return rest; }

const j = (x) => JSON.stringify(x);
const brief = (r) => `${r.status} ${(r.text || '').slice(0, 300)}`;

let W; // the seeded world
let ADMIN_ID;

async function task(token, o) {
  const r = await rt.createTask(token, o);
  if (r.status !== 200) throw new Error(`fixture task -> ${brief(r)}`);
  return r.json;
}
async function getTask(token, id, query) { return rt.api('GET', `/api/tasks/${id}`, { token, query }); }
async function freshCustomer(region, collector) {
  const c = await rt.createCustomer(W.tokens.admin, { name: rt.uniq(`X ${region.code}`), regionId: region.id, paymentTerm: 'NET_30' });
  if (collector) await rt.addCustomerPoc(W.tokens.admin, c.id, 'COLLECTION', collector, true);
  return c;
}

// ------------------------------------------------------------------------------------------------------------
// API cases
// ------------------------------------------------------------------------------------------------------------

async function apiCases() {
  const T = W.tokens; const U = W.users; const C = W.customers; const I = W.invoices; const P = W.payments; const RG = W.regions;

  await run({ id: 'TSK-X-01', feature: 'Assignee rights', kind: 'API', prd: 'A6/B1',
    title: 'A READ-only (South) assignee may start, finish and reopen her task but may not edit or cancel it',
    steps: 'admin POST /api/tasks on South invoice s2[0], assignees [USER nora]; nora (READ South) GET; POST status IN_PROGRESS, DONE, OPEN; PATCH title; POST status CANCELLED',
    expected: 'GET 200 canEdit=false canChangeStatus=true assignedToMe=true; IN_PROGRESS/DONE/OPEN 200; PATCH 403; CANCELLED 403 (§5.9.2 rights)' },
  async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[0].id, assignees: [rt.tok.user(U.nora.id)] });
    const g = await getTask(T.nora, t.id);
    c.that('nora GET 200', g.status === 200, brief(g));
    c.that('flags canEdit=false canChangeStatus=true assignedToMe=true', g.json && g.json.canEdit === false && g.json.canChangeStatus === true && g.json.assignedToMe === true,
      g.json && { canEdit: g.json.canEdit, canChangeStatus: g.json.canChangeStatus, assignedToMe: g.json.assignedToMe });
    let v = g.json.version;
    const s1 = await rt.tasks.setStatus(T.nora, t.id, 'IN_PROGRESS', v); c.that('start 200', s1.status === 200, brief(s1)); v = s1.json?.version;
    const s2 = await rt.tasks.setStatus(T.nora, t.id, 'DONE', v); c.that('done 200 + completedAt', s2.status === 200 && !!s2.json.completedAt, brief(s2)); v = s2.json?.version;
    const s3 = await rt.tasks.setStatus(T.nora, t.id, 'OPEN', v); c.that('reopen 200 + completedAt cleared', s3.status === 200 && s3.json.completedAt === null, brief(s3)); v = s3.json?.version;
    const p = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.nora, body: { title: 'nora edit', version: v } });
    c.that('PATCH 403', p.status === 403, brief(p));
    const x = await rt.tasks.setStatus(T.nora, t.id, 'CANCELLED', v);
    c.that('CANCELLED 403', x.status === 403, brief(x));
    const after = await getTask(T.admin, t.id);
    c.that('task still OPEN with original title', after.json.status === 'OPEN' && after.json.title === t.title, after.json && { status: after.json.status, title: after.json.title });
    return { ok: c.ok, actual: c.summary(), evidence: `task ${t.id}` };
  });

  await run({ id: 'TSK-X-02', feature: 'Region rights', kind: 'API', prd: 'A6/B1',
    title: 'A reader of South who is not an assignee can open a South task but cannot move it or add one there; she can in North',
    steps: 'admin creates unassigned task on s2; nora GET, POST status DONE; nora POST /api/tasks on s2 invoice; nora POST /api/tasks on n2 invoice',
    expected: 'GET 200 canChangeStatus=false; status 403; create in South 403; create in North 200' },
  async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[1].id });
    const g = await getTask(T.nora, t.id);
    c.that('GET 200, canEdit/canChangeStatus false', g.status === 200 && g.json.canEdit === false && g.json.canChangeStatus === false, brief(g));
    const s = await rt.tasks.setStatus(T.nora, t.id, 'DONE', g.json.version);
    c.that('status 403', s.status === 403, brief(s));
    const cs = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: I.s2[1].id });
    c.that('create in South 403', cs.status === 403, brief(cs));
    const cn = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: I.n2[1].id });
    c.that('create in North 200 with regionCode NORTH', cn.status === 200 && cn.json.regionCode === 'NORTH', brief(cn));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-03', feature: 'Assignee visibility', kind: 'API', prd: 'A3/B1',
    title: 'Naming as assignee someone with no right in the record\'s region is a 400 on assignees, and nothing is written',
    steps: 'admin POST /api/tasks on North invoice n2[0] with assignees [USER wendy] (West only); count tasks on the invoice before/after',
    expected: '400 with fieldErrors.assignees; task count unchanged (§6.12, §5.9.1)' },
  async () => {
    const c = checks();
    const before = await rt.api('GET', '/api/tasks/count', { token: T.admin, query: { entityType: 'INVOICE', entityId: I.n2[0].id } });
    const r = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, assignees: [rt.tok.user(U.wendy.id)] });
    c.that('400', r.status === 400, brief(r));
    c.that('fieldErrors.assignees present', r.json && r.json.fieldErrors && Object.keys(r.json.fieldErrors).some((k) => k.startsWith('assignees')), r.json && r.json.fieldErrors);
    const after = await rt.api('GET', '/api/tasks/count', { token: T.admin, query: { entityType: 'INVOICE', entityId: I.n2[0].id } });
    c.that('count unchanged', before.json.count === after.json.count, { before: before.json, after: after.json });
    return { ok: c.ok, actual: c.summary(), evidence: `POST -> ${brief(r)}` };
  });

  await run({ id: 'TSK-X-04', feature: 'Book scope', kind: 'API', prd: 'A3/B1',
    title: 'A BOOK-scoped user can be assigned only on records in his book; a task off his book is invisible to him',
    steps: 'admin: task on n2 invoice with USER bob; task on n1 invoice with USER bob; unassigned task on n2; bob GET mine, GET n2 task',
    expected: 'n2+bob 400 assignees; n1+bob 200 and in bob\'s My tasks; bob GET n2 task 404' },
  async () => {
    const c = checks();
    const off = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, assignees: [rt.tok.user(U.bob.id)] });
    c.that('off-book assignment 400', off.status === 400 && !!(off.json && off.json.fieldErrors), brief(off));
    const on = await task(T.admin, { entityType: 'INVOICE', entityId: I.n1[0].id, assignees: [rt.tok.user(U.bob.id)] });
    const mine = await rt.tasks.mine(T.bob);
    c.that('in bob\'s My tasks', mine.status === 200 && mine.json.content.some((x) => x.id === on.id), brief(mine).slice(0, 200));
    const other = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id });
    const g = await getTask(T.bob, other.id);
    c.that('bob GET off-book task 404', g.status === 404, brief(g));
    const lst = await rt.tasks.list(T.bob, { filter: [`id:eq:${other.id}`] });
    c.that('not in bob\'s list', lst.status === 200 && lst.json.content.length === 0, brief(lst).slice(0, 200));
    return { ok: c.ok, actual: c.summary() };
  });

  let extraUsers = null;
  await run({ id: 'TSK-X-05', feature: 'Assignee limits', kind: 'API', prd: 'A3',
    title: 'Duplicate assignees collapse to one; exactly 10 distinct is accepted, 11 is refused',
    steps: 'admin POST task on n2 with [nora,nora,nora,ROLE COLLECTION_POC@CUSTOMER x2]; then 10 distinct USER tokens (North-granted staff); then 11',
    expected: 'dupes -> 200 with 2 assignees; 10 -> 200 with 10; 11 -> 400 fieldErrors.assignees "At most 10 assignees" (§5.9.1)' },
  async () => {
    const c = checks();
    const d = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id,
      assignees: [rt.tok.user(U.nora.id), rt.tok.user(U.nora.id), rt.tok.user(U.nora.id), rt.tok.role('COLLECTION_POC'), rt.tok.role('COLLECTION_POC')] });
    c.that('dupes 200 with 2 assignees', d.status === 200 && d.json.assignees.length === 2, d.json ? d.json.assignees?.map((a) => a.token) : brief(d));
    if (!extraUsers) {
      extraUsers = [];
      for (let k = 0; k < 9; k++) {
        extraUsers.push(await rt.createStaff(T.admin, 'E2E_REGIONAL', 'xn', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'READ', recordScope: 'ALL' }] }));
      }
    }
    const ids = [U.nora.id, ...extraUsers.map((u) => u.id)]; // 10
    const ten = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, assignees: ids.map((id) => rt.tok.user(id)) });
    c.that('10 -> 200 with 10', ten.status === 200 && ten.json.assignees.length === 10, brief(ten).slice(0, 300));
    const eleven = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, assignees: [...ids, U.sam.id].map((id) => rt.tok.user(id)) });
    c.that('11 -> 400 assignees', eleven.status === 400 && eleven.json && eleven.json.fieldErrors && /10/.test(j(eleven.json.fieldErrors)), brief(eleven));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-06', feature: 'Offered roles', kind: 'API', prd: 'A3',
    title: 'Only the roles the To field offers on that kind of record are accepted as assignees',
    steps: 'admin POST task on payment n1 with SALES_POC@RECORD; with COLLECTION_POC@RECORD; on customer n1 with SALES_POC@RECORD; with {type:CUSTOMER}; with USER 999999',
    expected: 'payment+SALES@RECORD 400; payment+COLLECTION@RECORD 200 resolving to bob (the payment\'s collection POC); customer+SALES@RECORD 400; CUSTOMER token 400 "Assignees are people or roles"; unknown user 400' },
  async () => {
    const c = checks();
    const a = await rt.createTask(T.admin, { entityType: 'PAYMENT', entityId: P.n1[0].id, assignees: [rt.tok.role('SALES_POC', 'RECORD')] });
    c.that('payment SALES_POC@RECORD 400', a.status === 400, brief(a));
    const b = await rt.createTask(T.admin, { entityType: 'PAYMENT', entityId: P.n1[0].id, assignees: [rt.tok.role('COLLECTION_POC', 'RECORD')] });
    const people = b.json && b.json.assignees && b.json.assignees[0] && b.json.assignees[0].people;
    c.that('payment COLLECTION_POC@RECORD 200 -> bob', b.status === 200 && Array.isArray(people) && people.some((p) => p.userId === U.bob.id), b.json ? b.json.assignees : brief(b));
    const d = await rt.createTask(T.admin, { entityType: 'CUSTOMER', entityId: C.n1.id, assignees: [rt.tok.role('SALES_POC', 'RECORD')] });
    c.that('customer SALES_POC@RECORD 400', d.status === 400, brief(d));
    const e = await rt.createTask(T.admin, { entityType: 'CUSTOMER', entityId: C.n1.id, assignees: [rt.tok.customer()] });
    c.that('CUSTOMER token 400 "people or roles"', e.status === 400 && /people or roles/i.test(e.text), brief(e));
    const f = await rt.createTask(T.admin, { entityType: 'CUSTOMER', entityId: C.n1.id, assignees: [rt.tok.user(999999)] });
    c.that('unknown user 400', f.status === 400, brief(f));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-07', feature: 'Status machine', kind: 'API', prd: 'A6',
    title: 'Status moves: same status 400, done->in progress 400, stale version 409, missing version 400; done sets completedAt',
    steps: 'admin: task on n2; POST status OPEN (v0); DONE (v0); IN_PROGRESS (vNew); OPEN with stale v0; POST status without version',
    expected: '400 "already open"; 200 completedAt set; 400 "cannot become"; 409; 400 (§5.9.2, §6.12)' },
  async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[1].id });
    const same = await rt.tasks.setStatus(T.admin, t.id, 'OPEN', t.version);
    c.that('same status 400', same.status === 400 && /already open/i.test(same.text), brief(same));
    const done = await rt.tasks.setStatus(T.admin, t.id, 'DONE', t.version);
    c.that('DONE 200 + completedAt', done.status === 200 && !!done.json.completedAt, brief(done).slice(0, 200));
    const bad = await rt.tasks.setStatus(T.admin, t.id, 'IN_PROGRESS', done.json.version);
    c.that('done -> in progress 400', bad.status === 400 && /cannot become/i.test(bad.text), brief(bad));
    const stale = await rt.tasks.setStatus(T.admin, t.id, 'OPEN', t.version);
    c.that('stale version 409', stale.status === 409, brief(stale));
    const nov = await rt.api('POST', `/api/tasks/${t.id}/status`, { token: T.admin, body: { status: 'OPEN' } });
    c.that('missing version 400', nov.status === 400, brief(nov));
    const bogus = await rt.api('POST', `/api/tasks/${t.id}/status`, { token: T.admin, body: { status: 'ARCHIVED', version: done.json.version } });
    c.that('unknown status 400', bogus.status === 400, brief(bogus));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-08', feature: 'Field limits', kind: 'API', prd: 'A6',
    title: 'Title 200 chars accepted, 201 refused, blank refused and trimmed; description 2000/2001; PATCH blank title refused',
    steps: 'admin POST tasks on n2 with titles of 200, 201, "   ", "  padded  "; description 2000 / 2001; PATCH title "  "',
    expected: '200 / 400 fieldErrors.title / 400 / 200 "padded"; 200 / 400 fieldErrors.description; PATCH 400 fieldErrors.title' },
  async () => {
    const c = checks();
    const e = I.n2[1].id;
    const t200 = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, title: 'a'.repeat(200) });
    c.that('200 chars ok', t200.status === 200 && t200.json.title.length === 200, brief(t200).slice(0, 120));
    const t201 = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, title: 'a'.repeat(201) });
    c.that('201 chars 400 on title', t201.status === 400 && t201.json?.fieldErrors?.title, brief(t201));
    const blank = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, title: '   ' });
    c.that('blank 400', blank.status === 400, brief(blank));
    const pad = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, title: '  padded  ' });
    c.that('trimmed', pad.status === 200 && pad.json.title === 'padded', brief(pad).slice(0, 150));
    const d2000 = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, description: 'd'.repeat(2000) });
    c.that('desc 2000 ok', d2000.status === 200, brief(d2000).slice(0, 120));
    const d2001 = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: e, description: 'd'.repeat(2001) });
    c.that('desc 2001 400', d2001.status === 400 && d2001.json?.fieldErrors?.description, brief(d2001));
    const p = await rt.api('PATCH', `/api/tasks/${pad.json.id}`, { token: T.admin, body: { title: '  ', version: pad.json.version } });
    c.that('PATCH blank title 400', p.status === 400 && p.json?.fieldErrors?.title, brief(p));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-09', feature: 'Due / overdue', kind: 'API', prd: 'A6',
    title: 'Due today is "due today" not overdue; due yesterday is overdue; a done task is never overdue; summary and filter agree',
    steps: 'fresh North customer; admin tasks on it due today, due yesterday, due yesterday then DONE, no due date; GET /api/tasks/summary?customerId; GET /api/tasks?customerId&filter=overdue:eq:true',
    expected: 'summary {count:4, open:3, inProgress:0, overdue:1, dueToday:1}; overdue filter returns only the yesterday OPEN task; DTO overdue flags match (§5.9.4, §6.12)' },
  async () => {
    const c = checks();
    const cu = await freshCustomer(RG.NORTH, U.nora.id);
    const mk = (dueDate, title) => task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title, dueDate });
    const tToday = await mk(rt.day(0), 'today');
    const tYest = await mk(rt.day(-1), 'yesterday');
    const tDone = await mk(rt.day(-1), 'yesterday-done');
    const tNone = await rt.api('POST', '/api/tasks', { token: T.admin, body: { entityType: 'CUSTOMER', entityId: cu.id, title: 'none' } });
    await rt.tasks.setStatus(T.admin, tDone.id, 'DONE', tDone.version);
    c.that('today overdue=false', tToday.overdue === false, tToday.overdue);
    c.that('yesterday overdue=true', tYest.overdue === true, tYest.overdue);
    c.that('no due date ok', tNone.status === 200 && tNone.json.dueDate === null, brief(tNone).slice(0, 120));
    const s = await rt.api('GET', '/api/tasks/summary', { token: T.admin, query: { customerId: cu.id } });
    c.that('summary', s.status === 200 && s.json.count === 4 && s.json.open === 3 && s.json.inProgress === 0 && s.json.overdue === 1 && s.json.dueToday === 1, s.json);
    const o = await rt.tasks.list(T.admin, { customerId: cu.id, filter: ['overdue:eq:true'] });
    c.that('overdue filter = [yesterday]', o.status === 200 && o.json.content.length === 1 && o.json.content[0].id === tYest.id, o.json && o.json.content.map((x) => x.title));
    const gd = await getTask(T.admin, tDone.id);
    c.that('done task overdue=false', gd.json.overdue === false, gd.json.overdue);
    return { ok: c.ok, actual: c.summary(), evidence: `customer ${cu.id}` };
  });

  await run({ id: 'TSK-X-10', feature: 'Patch semantics', kind: 'API', prd: 'A6',
    title: 'PATCH with clearDueDate wins over dueDate; a PATCH that changes nothing writes no version and no audit row',
    steps: 'admin task on n2 due +3; PATCH {dueDate:+5, clearDueDate:true}; PATCH {title:<same>} with current version; count TASK_UPDATED audit rows',
    expected: 'dueDate null; the no-op PATCH answers 200 with the same version and adds no TASK_UPDATED row' },
  async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[1].id, dueDate: rt.day(3) });
    const p1 = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body: { dueDate: rt.day(5), clearDueDate: true, version: t.version } });
    c.that('due cleared', p1.status === 200 && p1.json.dueDate === null, brief(p1).slice(0, 200));
    const auditBefore = Number(rt.sql(`select count(*) from audit_logs where entity_type='TASK' and entity_id=${t.id} and action='TASK_UPDATED'`)[0][0]);
    const p2 = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body: { title: p1.json.title, version: p1.json.version } });
    const auditAfter = Number(rt.sql(`select count(*) from audit_logs where entity_type='TASK' and entity_id=${t.id} and action='TASK_UPDATED'`)[0][0]);
    c.that('no-op 200 same version', p2.status === 200 && p2.json.version === p1.json.version, { status: p2.status, v1: p1.json.version, v2: p2.json && p2.json.version });
    c.that('no new audit row', auditAfter === auditBefore, { auditBefore, auditAfter });
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-11', feature: 'Which regions', kind: 'API', prd: 'B1',
    title: 'Task lists answer "which regions": ungranted/unknown region -> empty 200, junk -> 400 REGION_IDS_INVALID, empty value = no narrowing',
    steps: 'nora GET /api/tasks with regionIds=<WEST>, regionIds=999999, regionIds=abc, regionIds= (empty), no regionIds; also /summary with regionIds=<NORTH>',
    expected: 'WEST: 200 content [] regionIds []; 999999: 200 []; abc: 400 error REGION_IDS_INVALID; empty: same total as no param; summary count = North-only total (§6.0)' },
  async () => {
    const c = checks();
    const west = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50, regionIds: String(RG.WEST.id) } });
    c.that('WEST 200 empty, regionIds []', west.status === 200 && west.json.totalElements === 0 && Array.isArray(west.json.regionIds) && west.json.regionIds.length === 0, west.json && { total: west.json.totalElements, regionIds: west.json.regionIds });
    const unk = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50, regionIds: '999999' } });
    c.that('unknown 200 empty', unk.status === 200 && unk.json.totalElements === 0, brief(unk).slice(0, 200));
    const junk = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50, regionIds: 'abc' } });
    c.that('junk 400 REGION_IDS_INVALID', junk.status === 400 && junk.json && junk.json.error === 'REGION_IDS_INVALID', brief(junk));
    const all = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50 } });
    const empty = await rt.api('GET', '/api/tasks?regionIds=&size=50', { token: T.nora });
    c.that('empty regionIds = no narrowing', empty.status === 200 && empty.json.totalElements === all.json.totalElements, { empty: empty.status + ' ' + (empty.json && empty.json.totalElements), all: all.json.totalElements, text: empty.status !== 200 ? empty.text.slice(0, 200) : '' });
    const north = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50, regionIds: String(RG.NORTH.id) } });
    const sum = await rt.api('GET', '/api/tasks/summary', { token: T.nora, query: { regionIds: String(RG.NORTH.id) } });
    c.that('North list only NORTH rows', north.status === 200 && north.json.content.every((x) => x.regionCode === 'NORTH'), north.json && [...new Set(north.json.content.map((x) => x.regionCode))]);
    c.that('summary count = North total', sum.status === 200 && sum.json.count === north.json.totalElements, { sum: sum.json, north: north.json.totalElements });
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-12', feature: 'Region move', kind: 'API', prd: 'B1/A6',
    title: 'Moving a customer from North to South carries its tasks: the South reader loses edit, the South approver gains it',
    steps: 'fresh North customer (Collection POC nora); admin task on it; nora GET (canEdit true); admin POST move-region to SOUTH; admin/nora/sam GET the task; nora PATCH',
    expected: 'after the move regionCode SOUTH; nora canEdit=false and PATCH 403; sam (APPROVE South) canEdit=true' },
  async () => {
    const c = checks();
    const cu = await freshCustomer(RG.NORTH, U.nora.id);
    const t = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id });
    const g0 = await getTask(T.nora, t.id);
    c.that('before move: nora canEdit', g0.status === 200 && g0.json.canEdit === true, brief(g0).slice(0, 150));
    const mv = await rt.moveCustomerRegion(T.admin, cu.id, RG.SOUTH.id);
    c.that('move 200', mv.status === 200, brief(mv));
    const ga = await getTask(T.admin, t.id);
    c.that('regionCode SOUTH', ga.json && ga.json.regionCode === 'SOUTH', ga.json && ga.json.regionCode);
    const gn = await getTask(T.nora, t.id);
    c.that('nora canEdit false', gn.status === 200 && gn.json.canEdit === false, brief(gn).slice(0, 150));
    const pn = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.nora, body: { title: 'x', version: gn.json.version } });
    c.that('nora PATCH 403', pn.status === 403, brief(pn));
    const gs = await getTask(T.sam, t.id);
    c.that('sam canEdit true', gs.status === 200 && gs.json.canEdit === true, brief(gs).slice(0, 150));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-13', feature: 'Automation x maker-checker', kind: 'API', prd: 'A1/A5/A6/B2',
    title: 'A "task on invoice created" rule does not fire for a held invoice; it fires once the invoice is approved',
    steps: 'admin rule ENTITY_CREATED INVOICE, regionIds [SOUTH], CREATE_TASK "Chase {{Invoice.Number}}" to COLLECTION_POC@CUSTOMER; sam creates 500 invoice on s2 (control) and 12000 invoice on s2 (over South INVOICE_CREATE 10000); wait 12 s; admin approves the pending change',
    expected: 'control invoice -> 1 rule task; held invoice -> 202, no task while pending; after approval -> 1 task on the new invoice, origin AUTOMATION, title has its number, assigned to sam (B2: a held change has not taken effect)' },
  async (attempt) => {
    const c = checks();
    const r = await rt.automation.create(T.admin, rt.rule({ name: rt.uniq('x13'), trigger: { kind: 'ENTITY_CREATED', entityType: 'INVOICE' }, regionIds: [RG.SOUTH.id],
      condition: { op: 'AND', children: [{ filter: 'total:gt:0' }] },
      actions: [rt.action.task({ title: 'Chase {{Invoice.Number}}', assignees: [rt.tok.role('COLLECTION_POC')] })] }));
    if (r.status !== 200) throw new Error('rule create ' + brief(r));
    const ruleId = r.json.id;
    try {
      const ruleTasks = async () => (await rt.tasks.list(T.admin, { filter: [`ruleId:eq:${ruleId}`] })).json.content;
      const ctl = await rt.createInvoice(T.sam, { customerId: C.s2.id, amount: 500, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
      c.that('control invoice 200', ctl.status === 200, brief(ctl).slice(0, 200));
      const ctlTask = await rt.waitFor(async () => (await ruleTasks()).find((x) => x.entityId === ctl.json.id), { timeoutMs: 30000 });
      c.that('control invoice got its task', !!ctlTask, 'none within 30s');
      const held = await rt.createInvoice(T.sam, { customerId: C.s2.id, amount: 12000, dueDate: rt.day(30), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id });
      c.that('held 202', held.held === true, brief(held).slice(0, 200));
      await rt.sleep(12000);
      const whilePending = (await ruleTasks()).filter((x) => x.entityId !== ctl.json.id);
      c.that('no task while pending', whilePending.length === 0, whilePending.map((x) => x.title));
      const ap = await rt.approvals.approve(T.admin, held.pendingChange.id, 'ok');
      c.that('approve 200', ap.status === 200, brief(ap).slice(0, 300));
      const invs = await rt.list(T.admin, '/api/invoices', { filter: [`customerId:eq:${C.s2.id}`] });
      const newInv = invs.json.content.find((x) => Number(x.total) === 12000);
      c.that('approved invoice exists', !!newInv, invs.json.content.map((x) => x.total));
      const t = newInv && await rt.waitFor(async () => (await ruleTasks()).find((x) => x.entityId === newInv.id), { timeoutMs: 40000 });
      c.that('task after approval', !!t, 'none within 40s');
      if (t) {
        c.that('origin AUTOMATION, title has number', t.origin === 'AUTOMATION' && t.title.includes(newInv.invoiceNumber || newInv.number || '#'), { title: t.title, number: newInv.invoiceNumber || newInv.number });
        const full = await getTask(T.admin, t.id);
        c.that('assigned to sam via role', full.json.assignees[0].people.some((p) => p.userId === U.sam.id), full.json.assignees);
        const count = (await ruleTasks()).filter((x) => x.entityId === newInv.id).length;
        c.that('exactly one task', count === 1, count);
      }
      return { ok: c.ok, actual: c.summary(), evidence: `rule ${ruleId}, pending ${held.pendingChange && held.pendingChange.id}` };
    } finally {
      await rt.automation.disable(T.admin, ruleId);
    }
  });

  await run({ id: 'TSK-X-14', feature: 'Customer delete', kind: 'API', prd: 'A6',
    title: 'Deleting a customer cancels its open tasks, marks them entityDeleted, and freezes them',
    steps: 'fresh North customer; admin task on it assigned to nora; nora mine/count; DELETE /api/customers/{id}; GET task; POST status OPEN; nora mine/count',
    expected: 'task CANCELLED, entityDeleted=true, canEdit=false; status change 400 "has been deleted"; nora\'s open count drops by 1' },
  async () => {
    const c = checks();
    const cu = await freshCustomer(RG.NORTH, U.nora.id);
    const t = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, assignees: [rt.tok.user(U.nora.id)] });
    const n0 = (await rt.tasks.mineCount(T.nora)).json.count;
    const del = await rt.api('DELETE', `/api/customers/${cu.id}`, { token: T.admin });
    c.that('delete 2xx', del.ok, brief(del));
    const g = await getTask(T.admin, t.id);
    c.that('CANCELLED + entityDeleted', g.status === 200 && g.json.status === 'CANCELLED' && g.json.entityDeleted === true && g.json.canEdit === false, brief(g).slice(0, 300));
    const s = await rt.tasks.setStatus(T.admin, t.id, 'OPEN', g.json.version);
    c.that('status change 400 deleted', s.status === 400 && /deleted/i.test(s.text), brief(s));
    const n1 = (await rt.tasks.mineCount(T.nora)).json.count;
    c.that('nora count -1', n1 === n0 - 1, { n0, n1 });
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-15', feature: 'Customer login', kind: 'API', prd: 'A6/B1',
    title: 'A customer login has no access to tasks',
    steps: 'cust (s1 customer login) GET /api/tasks, /api/tasks/mine/count, POST /api/tasks on its own customer',
    expected: '403 on each' },
  async () => {
    const c = checks();
    const a = await rt.api('GET', '/api/tasks', { token: W.tokens.cust, query: { size: 20 } });
    const b = await rt.api('GET', '/api/tasks/mine/count', { token: W.tokens.cust });
    const d = await rt.createTask(W.tokens.cust, { entityType: 'CUSTOMER', entityId: C.s1.id });
    c.that('list 403', a.status === 403, brief(a)); c.that('count 403', b.status === 403, brief(b)); c.that('create 403', d.status === 403, brief(d));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-16', feature: 'Bulk', kind: 'API', prd: 'A6/B1',
    title: 'Bulk Mark done changes only tasks in regions where the caller manages; others are left untouched and reported',
    steps: 'admin tasks on n2 (North), s2 (South), w2 (West); sam POST /api/tasks/bulk {action:MARK_DONE, ids:[n,s,w]}; nora bulk on [s]; bulk action FOO',
    expected: 'sam: succeeded [n,s], w not succeeded and still OPEN; nora: s not changed; FOO -> 400' },
  async () => {
    const c = checks();
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id });
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[0].id });
    const tw = await task(T.admin, { entityType: 'INVOICE', entityId: I.w2[0].id });
    const tn2 = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[0].id });
    const b = await rt.api('POST', '/api/tasks/bulk', { token: T.sam, body: { action: 'MARK_DONE', ids: [tn.id, ts.id, tw.id] } });
    c.that('sam bulk 200', b.status === 200, brief(b));
    c.that('succeeded = [n,s]', b.json && j([...b.json.succeeded].sort()) === j([tn.id, ts.id].sort()), b.json);
    const gw = await getTask(T.admin, tw.id);
    c.that('West still OPEN', gw.json.status === 'OPEN', gw.json.status);
    const nb = await rt.api('POST', '/api/tasks/bulk', { token: T.nora, body: { action: 'MARK_DONE', ids: [tn2.id] } });
    const g2 = await getTask(T.admin, tn2.id);
    c.that('nora bulk leaves South task OPEN', g2.json.status === 'OPEN' && (!nb.json || !(nb.json.succeeded || []).includes(tn2.id)), { bulk: brief(nb).slice(0, 300), status: g2.json.status });
    const foo = await rt.api('POST', '/api/tasks/bulk', { token: T.admin, body: { action: 'FOO', ids: [tn2.id] } });
    c.that('FOO 400', foo.status === 400, brief(foo));
    return { ok: c.ok, actual: c.summary(), evidence: `sam bulk -> ${brief(b).slice(0, 400)}` };
  });

  await run({ id: 'TSK-X-17', feature: 'Record-level role', kind: 'API', prd: 'A3/A6',
    title: 'A task on a payment assigned to "Collection POC of this payment" reaches that POC\'s My tasks and no one else\'s',
    steps: 'admin task on payment s2 with COLLECTION_POC@RECORD; sam and nora GET /api/tasks/mine; sam POST status DONE',
    expected: 'people=[sam]; in sam\'s mine, not nora\'s (nora can read South); sam may mark it done (effective assignee)' },
  async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'PAYMENT', entityId: P.s2[0].id, assignees: [rt.tok.role('COLLECTION_POC', 'RECORD')] });
    c.that('people = [sam]', t.assignees[0].people.length === 1 && t.assignees[0].people[0].userId === U.sam.id, t.assignees);
    const ms = await rt.list(T.sam, '/api/tasks/mine');
    const mn = await rt.list(T.nora, '/api/tasks/mine');
    c.that('in sam mine', ms.json.content.some((x) => x.id === t.id));
    c.that('not in nora mine', !mn.json.content.some((x) => x.id === t.id));
    const d = await rt.tasks.setStatus(T.sam, t.id, 'DONE', t.version);
    c.that('sam DONE 200', d.status === 200, brief(d).slice(0, 150));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-18', feature: 'Export', kind: 'API', prd: 'A6/B1',
    title: 'Task CSV export contains only tasks the caller can see',
    steps: 'admin tasks on n2 and w2; sam POST /api/tasks/export {action:EXPORT, ids:[n,w]}; wendy same',
    expected: 'sam CSV: header "Id,Title,..." and the North task only; wendy: the West task only' },
  async () => {
    const c = checks();
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, title: rt.uniq('expN') });
    const tw = await task(T.admin, { entityType: 'INVOICE', entityId: I.w2[0].id, title: rt.uniq('expW') });
    const s = await rt.api('POST', '/api/tasks/export', { token: T.sam, body: { action: 'EXPORT', ids: [tn.id, tw.id] } });
    c.that('sam 200 csv header', s.status === 200 && /^﻿?"?Id"?,"?Title/.test(s.text), s.text.slice(0, 120));
    c.that('sam has N not W', s.text.includes(tn.title) && !s.text.includes(tw.title), s.text.slice(0, 400));
    const wd = await rt.api('POST', '/api/tasks/export', { token: T.wendy, body: { action: 'EXPORT', ids: [tn.id, tw.id] } });
    c.that('wendy has W not N', wd.status === 200 && wd.text.includes(tw.title) && !wd.text.includes(tn.title), wd.text.slice(0, 400));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-19', feature: 'Query validation', kind: 'API', prd: 'A6',
    title: 'Task list refuses a sort on assignee, an odd page size and an unknown status value with 400',
    steps: 'admin GET /api/tasks?sort=assigneeUserId,asc; ?size=25; ?filter=status:eq:BOGUS; ?filter=assigneeUserId:eq:<nora> (control)',
    expected: '400, 400, 400; control 200 with only tasks assigned to nora' },
  async () => {
    const c = checks();
    const a = await rt.api('GET', '/api/tasks', { token: T.admin, query: { size: 20, sort: 'assigneeUserId,asc' } });
    const b = await rt.api('GET', '/api/tasks', { token: T.admin, query: { size: 25 } });
    const d = await rt.api('GET', '/api/tasks', { token: T.admin, query: { size: 20, filter: ['status:eq:BOGUS'] } });
    c.that('sort assignee 400', a.status === 400, brief(a)); c.that('size 25 400', b.status === 400, brief(b)); c.that('bogus status 400', d.status === 400, brief(d));
    const e = await rt.list(T.admin, '/api/tasks', { filter: [`assigneeUserId:eq:${U.nora.id}`] });
    const bad = e.json.content.filter((x) => !x.assignees.some((a2) => a2.token.type === 'ROLE' || a2.token.userId === U.nora.id));
    c.that('assignee filter control', e.status === 200 && e.json.content.length > 0 && bad.length === 0, { n: e.json.content.length, bad: bad.map((x) => x.id) });
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-20', feature: 'Notifications', kind: 'API', prd: 'A6',
    title: 'Assigning notifies the assignee (not the actor); an assignee finishing a user task notifies its creator',
    steps: 'admin task on n2 assigned to [nora, admin]; nora and admin GET /api/notifications; nora DONE; admin GET /api/notifications',
    expected: 'nora TASK_ASSIGNED "Task assigned: <title>" link /tasks/{id}; admin has no TASK_ASSIGNED for it; after DONE admin has TASK_DONE "Task done: <title>"' },
  async () => {
    const c = checks();
    const title = rt.uniq('notif');
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, title, assignees: [rt.tok.user(U.nora.id), rt.tok.user(ADMIN_ID)] });
    const notes = async (tokn) => (await rt.list(tokn, '/api/notifications')).json.content.filter((n) => n.link === `/tasks/${t.id}`);
    const nn = await rt.waitFor(async () => (await notes(T.nora)).find((n) => n.type === 'TASK_ASSIGNED'), { timeoutMs: 8000 });
    c.that('nora TASK_ASSIGNED', nn && nn.title === `Task assigned: ${title}`, nn);
    const an = (await notes(T.admin)).filter((n) => n.type === 'TASK_ASSIGNED');
    c.that('admin (actor) not notified', an.length === 0, an);
    await rt.tasks.setStatus(T.nora, t.id, 'DONE', t.version);
    const ad = await rt.waitFor(async () => (await notes(T.admin)).find((n) => n.type === 'TASK_DONE'), { timeoutMs: 8000 });
    c.that('admin TASK_DONE', ad && ad.title === `Task done: ${title}`, ad);
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-21', feature: 'People picker', kind: 'API', prd: 'A3/B1',
    title: 'The assignee people search offers only staff who hold a grant in the record\'s region',
    steps: 'admin GET /api/assignees/people?q=wendy|nora|bob with entityType=INVOICE&entityId=<n2 inv>, then with <s2 inv>',
    expected: 'North invoice: nora and bob found, wendy not; South invoice: nora and sam found (nora READ South), bob and wendy not (§6.13)' },
  async () => {
    const c = checks();
    const q = async (name, inv) => (await rt.api('GET', '/api/assignees/people', { token: T.admin, query: { q: name, entityType: 'INVOICE', entityId: inv } })).json || [];
    const has = (arr, id) => arr.some((p) => p.userId === id);
    c.that('N: nora', has(await q('nora', I.n2[0].id), U.nora.id));
    c.that('N: bob', has(await q('bob', I.n2[0].id), U.bob.id));
    c.that('N: no wendy', !has(await q('wendy', I.n2[0].id), U.wendy.id));
    c.that('S: nora', has(await q('nora', I.s2[0].id), U.nora.id));
    c.that('S: sam', has(await q('sam', I.s2[0].id), U.sam.id));
    c.that('S: no bob', !has(await q('bob', I.s2[0].id), U.bob.id));
    c.that('S: no wendy', !has(await q('wendy', I.s2[0].id), U.wendy.id));
    return { ok: c.ok, actual: c.summary() };
  });
}

// ------------------------------------------------------------------------------------------------------------
// UI cases
// ------------------------------------------------------------------------------------------------------------

async function withApp(opts, fn) {
  const app = await rt.openApp(opts);
  try { return await fn(app); } finally { await app.close(); }
}
const texts = (page) => rt.screenText(page);

async function uiCases() {
  const T = W.tokens; const U = W.users; const I = W.invoices; const RG = W.regions;

  await run({ id: 'TSK-X-UI-01', feature: 'Assignee detail', kind: 'UI', prd: 'A6/B1',
    title: 'A READ-only assignee sees Start and Mark done (no Edit, no Cancel task) and can mark the task done from its page',
    steps: 'admin task on s2 assigned to nora; nora opens /tasks/{id}; read buttons; tap "Mark done"',
    expected: 'Start + Mark done present; Edit and Cancel task absent; after tap API status DONE and snackbar "Task done"' },
  async (n) => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[0].id, title: rt.uniq('ui01'), assignees: [rt.tok.user(U.nora.id)] });
    return withApp({ token: T.nora, path: `/tasks/${t.id}` }, async ({ page, pageErrors }) => {
      const s = await texts(page);
      c.that('title shown', s.some((x) => x.includes(t.title)), s.slice(0, 40));
      c.that('Start', await rt.exists(page, 'Start'));
      c.that('Mark done', await rt.exists(page, 'Mark done'));
      c.that('no Edit', !(await rt.exists(page, 'Edit')));
      c.that('no Cancel task', !(await rt.exists(page, 'Cancel task')));
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-01-${n}-a`);
      await rt.tap(page, 'Mark done', { wait: 2500 });
      const g = await getTask(T.admin, t.id);
      c.that('API DONE', g.json.status === 'DONE', g.json.status);
      const shotB = await rt.shot(page, SHOTS, `TSK-X-UI-01-${n}-b`);
      c.that('no page errors', pageErrors.length === 0, pageErrors);
      return { ok: c.ok, actual: c.summary(), screenshot: `${shotA}, ${shotB}` };
    });
  });

  await run({ id: 'TSK-X-UI-02', feature: 'Read-only detail', kind: 'UI', prd: 'A6/B1',
    title: 'A South reader who is not an assignee sees the task read-only: no move buttons, no Edit',
    steps: 'admin unassigned task on s2; nora opens /tasks/{id}',
    expected: 'Title and "Nobody" under Assigned to; no Start / Mark done / Cancel task / Edit' },
  async (n) => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[1].id, title: rt.uniq('ui02') });
    return withApp({ token: T.nora, path: `/tasks/${t.id}` }, async ({ page }) => {
      const s = await texts(page);
      c.that('title shown', s.some((x) => x.includes(t.title)));
      c.that('Nobody', s.includes('Nobody'), s.slice(0, 50));
      for (const b of ['Start', 'Mark done', 'Cancel task', 'Edit']) c.that(`no ${b}`, !(await rt.exists(page, b)));
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-02-${n}`);
      return { ok: c.ok, actual: c.summary(), screenshot: shotA };
    });
  });

  await run({ id: 'TSK-X-UI-03', feature: 'New task validation', kind: 'UI', prd: 'A6',
    title: 'Create task with an empty title shows "Say what needs doing" and sends nothing',
    steps: 'admin opens /invoices/{n2 inv}?tab=tasks; tap New task; tap Create task with empty title',
    expected: 'inline error "Say what needs doing"; no POST /api/tasks made; dialog stays open' },
  async (n) => {
    const c = checks();
    return withApp({ token: T.admin, path: `/invoices/${I.n2[0].id}?tab=tasks` }, async ({ page, requests }) => {
      await rt.tap(page, 'New task', { wait: 2500 });
      await rt.tap(page, 'Create task', { wait: 1500 });
      const s = await texts(page);
      c.that('error text', s.some((x) => x.includes('Say what needs doing')), s.slice(0, 60));
      c.that('no POST', !requests.some((r) => r.method === 'POST' && /^\/api\/tasks(\?|$)/.test(r.url)), requests.filter((r) => r.method === 'POST'));
      c.that('dialog still open', s.some((x) => /^New task/.test(x)));
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-03-${n}`);
      return { ok: c.ok, actual: c.summary(), screenshot: shotA };
    });
  });

  await run({ id: 'TSK-X-UI-04', feature: 'Invisible task', kind: 'UI', prd: 'A6/B1',
    title: 'Opening a task in a region the user has no grant in shows "That task does not exist." (no leak, no crash)',
    steps: 'admin task on n2 with a distinctive title; wendy (West only) opens /tasks/{id}',
    expected: '"That task does not exist." and the title is not shown' },
  async (n) => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, title: rt.uniq('secret') });
    return withApp({ token: T.wendy, path: `/tasks/${t.id}` }, async ({ page, pageErrors }) => {
      const s = await texts(page);
      c.that('not-found text', s.some((x) => x.includes('That task does not exist')), s.slice(0, 40));
      c.that('title not leaked', !s.some((x) => x.includes(t.title)));
      c.that('no page errors', pageErrors.length === 0, pageErrors);
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-04-${n}`);
      return { ok: c.ok, actual: c.summary(), screenshot: shotA };
    });
  });

  let lonely = null;
  await run({ id: 'TSK-X-UI-05', feature: 'Empty state & region column', kind: 'UI', prd: 'A6/B1',
    title: 'A single-region user with no tasks gets the empty state and no Region column; a multi-region user gets the Region column',
    steps: 'admin creates region EMPTY<tag> and a staff user granted MANAGE there only; that user opens /tasks; nora opens /tasks',
    expected: 'lonely: "No tasks match this filter", tiles at 0, no "Region" header; nora: "Region" header (§7.5)' },
  async (n) => {
    const c = checks();
    if (!lonely) {
      const reg = await rt.createRegion(T.admin, { code: `E${Date.now().toString(36).slice(-5).toUpperCase()}`, name: 'Empty land' });
      lonely = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'lonely', { grants: [{ regionId: reg.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    }
    const shotsOut = [];
    await withApp({ username: lonely.username, password: lonely.password, path: '/tasks' }, async ({ page }) => {
      const s = await texts(page);
      c.that('empty message', s.some((x) => /No tasks match this filter|Nothing here yet/.test(x)), s.slice(0, 60));
      c.that('no Region header', !s.includes('Region'));
      shotsOut.push(await rt.shot(page, SHOTS, `TSK-X-UI-05-${n}-lonely`));
    });
    await withApp({ token: T.nora, path: '/tasks' }, async ({ page }) => {
      const s = await texts(page);
      c.that('nora Region header', s.includes('Region'), s.slice(0, 60));
      shotsOut.push(await rt.shot(page, SHOTS, `TSK-X-UI-05-${n}-nora`));
    });
    return { ok: c.ok, actual: c.summary(), screenshot: shotsOut.join(', ') };
  });

  await run({ id: 'TSK-X-UI-06', feature: 'Cancel confirm', kind: 'UI', prd: 'A6',
    title: 'Cancel task asks first: "Keep it" leaves the task open, confirming cancels it',
    steps: 'admin task on n2; admin opens /tasks/{id}; Cancel task -> Keep it; Cancel task -> Cancel task (dialog)',
    expected: 'dialog "Cancel this task?"; after Keep it API status OPEN; after confirm API status CANCELLED and Reopen offered' },
  async (n) => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[1].id, title: rt.uniq('ui06') });
    return withApp({ token: T.admin, path: `/tasks/${t.id}` }, async ({ page }) => {
      await rt.tap(page, 'Cancel task', { wait: 1500 });
      const s1 = await texts(page);
      c.that('confirm dialog', s1.includes('Cancel this task?'), s1.slice(0, 40));
      await rt.tap(page, 'Keep it', { wait: 1500 });
      c.that('still OPEN', (await getTask(T.admin, t.id)).json.status === 'OPEN');
      await rt.tap(page, 'Cancel task', { wait: 1500 });
      const nodes = (await rt.semantics(page)).filter((x) => (x.label === 'Cancel task' || x.text === 'Cancel task'));
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-06-${n}-a`);
      const btn = nodes[nodes.length - 1];
      await rt.clickAt(page, btn.x, btn.y, 2500);
      const g = await getTask(T.admin, t.id);
      c.that('CANCELLED', g.json.status === 'CANCELLED', g.json.status);
      c.that('Reopen offered', await rt.exists(page, 'Reopen'));
      const shotB = await rt.shot(page, SHOTS, `TSK-X-UI-06-${n}-b`);
      return { ok: c.ok, actual: c.summary(), screenshot: `${shotA}, ${shotB}` };
    });
  });

  await run({ id: 'TSK-X-UI-07', feature: 'My tasks x regions', kind: 'UI', prd: 'A6/B1',
    title: 'My tasks lists my tasks from every granted region; narrowing the region selector to North removes the South one and sends regionIds',
    steps: 'admin tasks assigned to nora on n2 and s2; nora opens /my-tasks; region selector -> toggle NORTH only',
    expected: 'both titles listed; after choosing North only the North title is listed and /api/tasks/mine was requested with regionIds=<NORTH>' },
  async (n) => {
    const c = checks();
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: I.n2[0].id, title: rt.uniq('myN'), assignees: [rt.tok.user(U.nora.id)] });
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: I.s2[0].id, title: rt.uniq('myS'), assignees: [rt.tok.user(U.nora.id)] });
    return withApp({ token: T.nora, path: '/my-tasks?sort=id,desc' }, async ({ page, requests }) => {
      const s = await texts(page);
      const joined = s.join('\n');
      c.that('both listed', joined.includes(tn.title) && joined.includes(ts.title), s.slice(0, 60));
      await rt.chooseRegions(page, [/^NORTH/]);
      const s2 = (await texts(page)).join('\n');
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-07-${n}`);
      c.that('North only', s2.includes(tn.title) && !s2.includes(ts.title), s2.slice(0, 600));
      c.that('regionIds sent', requests.some((r) => r.url.startsWith('/api/tasks/mine') && new RegExp(`regionIds=${RG.NORTH.id}(&|$)`).test(r.url)), requests.filter((r) => r.url.startsWith('/api/tasks/mine')).map((r) => r.url));
      return { ok: c.ok, actual: c.summary(), screenshot: shotA };
    });
  });
}

// ------------------------------------------------------------------------------------------------------------
// As-of block (ages the database; last)
// ------------------------------------------------------------------------------------------------------------

async function asofCases() {
  const T = W.tokens; const U = W.users; const RG = W.regions;
  // Fixtures before ageing.
  const t1 = await task(T.admin, { entityType: 'INVOICE', entityId: W.invoices.n2[0].id, title: rt.uniq('asof'), assignees: [rt.tok.user(U.nora.id)] });
  const cm = await freshCustomer(RG.NORTH, U.nora.id);
  const tm = await task(T.admin, { entityType: 'CUSTOMER', entityId: cm.id, title: rt.uniq('asofMove') });
  rt.ageWorld(5);
  const now1 = await getTask(T.admin, t1.id);
  const done = await rt.tasks.setStatus(T.admin, t1.id, 'DONE', now1.json.version);
  if (done.status !== 200) throw new Error('as-of fixture DONE ' + brief(done));
  const mv = await rt.moveCustomerRegion(T.admin, cm.id, RG.SOUTH.id);
  if (mv.status !== 200) throw new Error('as-of fixture move ' + brief(mv));
  const past = rt.day(-2);

  await run({ id: 'TSK-X-AS-01', feature: 'As-of task', kind: 'API', prd: 'B3/A6',
    title: 'As of a date a task reads as it stood then (OPEN), with no version and no rights; writes with asOf are refused',
    steps: 'task created, ageWorld(5), marked DONE now; GET /api/tasks/{id}?asOf=<-2d>; GET live; POST status with ?asOf=<-2d>; GET ?asOf=<-7d>; GET ?asOf=<+1d>',
    expected: 'as-of: OPEN, version null, canEdit=false, canChangeStatus=false, X-View-As-Of=<date>; live DONE; POST 400 AS_OF_READ_ONLY; -7d 404; +1d 400 AS_OF_FUTURE' },
  async () => {
    const c = checks();
    const a = await getTask(T.admin, t1.id, { asOf: past });
    c.that('as-of OPEN, version null, no rights', a.status === 200 && a.json.status === 'OPEN' && a.json.version === null && a.json.canEdit === false && a.json.canChangeStatus === false,
      a.json && { status: a.json.status, version: a.json.version, canEdit: a.json.canEdit, canChangeStatus: a.json.canChangeStatus });
    c.that('X-View-As-Of header', a.headers['x-view-as-of'] === past, a.headers['x-view-as-of']);
    const l = await getTask(T.admin, t1.id);
    c.that('live DONE', l.json.status === 'DONE');
    const w = await rt.api('POST', `/api/tasks/${t1.id}/status`, { token: T.admin, query: { asOf: past }, body: { status: 'OPEN', version: l.json.version } });
    c.that('write refused AS_OF_READ_ONLY', w.status === 400 && w.json && w.json.error === 'AS_OF_READ_ONLY', brief(w));
    const b = await getTask(T.admin, t1.id, { asOf: rt.day(-7) });
    c.that('before creation 404', b.status === 404, brief(b));
    const f = await getTask(T.admin, t1.id, { asOf: rt.day(1) });
    c.that('future 400 AS_OF_FUTURE', f.status === 400 && f.json && f.json.error === 'AS_OF_FUTURE', brief(f));
    const mine = await rt.api('GET', '/api/tasks/mine', { token: T.nora, query: { size: 50, asOf: past, filter: ['status:eq:OPEN'] } });
    c.that('nora My tasks as-of lists it as OPEN', mine.status === 200 && mine.json.content.some((x) => x.id === t1.id), brief(mine).slice(0, 200));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-AS-02', feature: 'As-of region', kind: 'API', prd: 'B3/B1',
    title: 'As of before a region move, the task list by region answers with the region the task was in then',
    steps: 'customer with a task in North, ageWorld(5), moved to South now; admin GET /api/tasks?regionIds=<NORTH>&asOf=<-2d>, and live; same for SOUTH',
    expected: 'as-of North contains the task (regionCode NORTH); live North does not; live South does; as-of South does not' },
  async () => {
    const c = checks();
    const q = (rid, asOf) => rt.api('GET', '/api/tasks', { token: T.admin, query: rt.scoped({ size: 50, filter: [`id:eq:${tm.id}`] }, { regionIds: [rid], asOf }) });
    const aN = await q(RG.NORTH.id, past); const lN = await q(RG.NORTH.id); const lS = await q(RG.SOUTH.id); const aS = await q(RG.SOUTH.id, past);
    const has = (r) => r.status === 200 && r.json.content.some((x) => x.id === tm.id);
    c.that('as-of North has it (NORTH)', has(aN) && aN.json.content[0].regionCode === 'NORTH', brief(aN).slice(0, 300));
    c.that('live North lacks it', lN.status === 200 && !has(lN), brief(lN).slice(0, 200));
    c.that('live South has it', has(lS));
    c.that('as-of South lacks it', aS.status === 200 && !has(aS), brief(aS).slice(0, 300));
    return { ok: c.ok, actual: c.summary() };
  });

  await run({ id: 'TSK-X-UI-AS-01', feature: 'As-of task UI', kind: 'UI', prd: 'B3/A6',
    title: 'Viewing a task as of a past date shows it as it stood, read only: banner, no Mark done / Reopen / Edit',
    steps: 'admin opens /tasks/{t1} (DONE now); as-of chip -> <-2d>',
    expected: 'banner "Viewing … — read only"; status Open; no Start / Mark done / Reopen / Cancel task / Edit' },
  async (n) => {
    const c = checks();
    return withApp({ token: T.admin, path: `/tasks/${t1.id}` }, async ({ page, requests }) => {
      const live = await texts(page);
      c.that('live shows Reopen', live.includes('Reopen'), live.slice(0, 40));
      await rt.chooseAsOf(page, past);
      const s = await texts(page);
      const shotA = await rt.shot(page, SHOTS, `TSK-X-UI-AS-01-${n}`);
      c.that('read-only banner', s.some((x) => /Viewing .*read only/i.test(x)), s.slice(0, 60));
      c.that('status Open', s.some((x) => /^Open$/.test(x)), s.slice(0, 60));
      for (const b of ['Start', 'Mark done', 'Reopen', 'Cancel task', 'Edit']) c.that(`no ${b}`, !s.includes(b));
      c.that('asOf sent', requests.some((r) => r.url.startsWith(`/api/tasks/${t1.id}`) && r.url.includes(`asOf=${past}`)));
      return { ok: c.ok, actual: c.summary(), screenshot: shotA };
    });
  });
}

(async () => {
  const started = Date.now();
  try {
    W = await rt.seedWorld(await rt.adminToken());
    ADMIN_ID = (await rt.me(W.tokens.admin)).id;
    await apiCases();
    await uiCases();
    await asofCases();
  } catch (e) {
    rec({ id: 'TSK-X-HARNESS', feature: 'Harness', kind: 'API', prd: 'A6', title: 'Explore run aborted', status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
  } finally {
    save();
    console.log(`done in ${Math.round((Date.now() - started) / 1000)} s`);
  }
  process.exit(0);
})();
