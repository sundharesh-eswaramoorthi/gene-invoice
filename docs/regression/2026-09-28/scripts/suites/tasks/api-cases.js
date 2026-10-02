// API cases for A6 tasks (plus the B1 region and book scoping of tasks and of the assignee pickers).
// Expected behaviour: PRD A6/A3/B1, DESIGN §5.9 (assignees, tasks, AssignedTo, My tasks), §6.12, §6.13, §8.5.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, customerIn, task, getTask, notesFor, brief, ids } = H;

const BE = {
  svc: 'backend/src/main/java/com/geneinvoice/task/TaskService.java',
  ctl: 'backend/src/main/java/com/geneinvoice/task/TaskController.java',
  asvc: 'backend/src/main/java/com/geneinvoice/assignee/AssigneeService.java',
  store: 'backend/src/main/java/com/geneinvoice/assignee/AssigneeStore.java',
  assigned: 'backend/src/main/java/com/geneinvoice/assignee/AssignedTo.java',
};

module.exports = async function apiCases(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens;
  const U = w.users;
  const RG = w.regions;
  const inv = (k, i = 0) => w.invoices[k][i];
  const pay = (k) => w.payments[k][0];
  const cust = (k) => w.customers[k];

  // ------------------------------------------------------------------ create on each record type
  await tcase({ id: 'TAS-API-01', feature: 'Create task', kind: 'API', prd: 'A6',
    title: 'A task can be created on a customer, an invoice and a payment, with title, description, due date and assignees',
    steps: 'admin POST /api/tasks {entityType CUSTOMER|INVOICE|PAYMENT, entityId (n1 records), title, description, dueDate +3d, assignees [USER nora]}',
    expected: 'A6 / DESIGN §6.12: 200 TaskDto status OPEN, origin USER, entityLabel set, customerId = n1, region NORTH (the customer\'s), createdBy admin, version 0, canEdit true; assignees echo USER nora' }, async () => {
    const c = checks();
    const targets = [['CUSTOMER', cust('n1').id], ['INVOICE', inv('n1').id], ['PAYMENT', pay('n1').id]];
    const out = [];
    for (const [type, id] of targets) {
      const title = rt.uniq(`t-${type.toLowerCase()}`);
      const r = await rt.createTask(T.admin, { entityType: type, entityId: id, title, description: 'desc ' + type, dueDate: rt.day(3), assignees: [rt.tok.user(U.nora.id)] });
      const j = r.json || {};
      out.push(`${type}:${r.status}:${j.id}:${j.entityLabel}`);
      c.that(`${type}: 200`, r.status === 200, brief(r));
      c.that(`${type}: fields`, j.status === 'OPEN' && j.origin === 'USER' && j.title === title && j.description === 'desc ' + type
        && j.dueDate === rt.day(3) && j.entityType === type && j.entityId === id && j.customerId === cust('n1').id, j);
      c.that(`${type}: region = customer's (NORTH)`, j.regionId === RG.NORTH.id && j.regionCode === 'NORTH', { regionId: j.regionId, regionCode: j.regionCode });
      c.that(`${type}: entityLabel`, typeof j.entityLabel === 'string' && j.entityLabel.length > 0, j.entityLabel);
      c.that(`${type}: createdBy admin, version 0, canEdit`, j.createdBy && j.createdBy.id === U.admin.id && j.version === 0 && j.canEdit === true && j.canChangeStatus === true, { createdBy: j.createdBy, version: j.version });
      c.that(`${type}: assignee nora`, Array.isArray(j.assignees) && j.assignees.length === 1 && j.assignees[0].token.type === 'USER' && j.assignees[0].token.userId === U.nora.id && j.assignees[0].label === 'Nora North', j.assignees);
      const g = await getTask(T.admin, j.id);
      c.that(`${type}: GET by id round-trips`, g.status === 200 && g.json.title === title, brief(g));
    }
    return done(c, { evidence: out.join(', '), codeRef: `${BE.svc}:127` });
  });

  await tcase({ id: 'TAS-API-02', feature: 'Create task validation', kind: 'API', prd: 'A6',
    title: 'Create refuses a blank or over-long title, a missing or unknown record, and a bad entity type',
    steps: 'admin POST /api/tasks with title "  ", 201-char title, no entityType, entityId 999999, entityType "PROMISE"',
    expected: 'DESIGN §3.7/§6.12: 400 fieldErrors.title for blank / >200; 400 for no entityType; 404 for an unknown record; 400 for an unsupported entity type; nothing written' }, async () => {
    const c = checks();
    const id = inv('n2').id;
    const before = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: id })).json.totalElements;
    const blank = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: id, title: '   ' });
    c.that('blank title -> 400 fieldErrors.title', blank.status === 400 && blank.json?.fieldErrors?.title, brief(blank));
    const long = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: id, title: 'x'.repeat(201) });
    c.that('201-char title -> 400 fieldErrors.title', long.status === 400 && long.json?.fieldErrors?.title, brief(long));
    const max = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: id, title: 'y'.repeat(200) });
    c.that('200-char title -> 200', max.status === 200, brief(max));
    const noType = await rt.api('POST', '/api/tasks', { token: T.admin, body: { entityId: id, title: 'no type' } });
    c.that('no entityType -> 400', noType.status === 400, brief(noType));
    const unknown = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: 999999, title: 'ghost' });
    c.that('unknown invoice -> 404', unknown.status === 404, brief(unknown));
    const badType = await rt.api('POST', '/api/tasks', { token: T.admin, body: { entityType: 'PROMISE', entityId: 1, title: 'promise task' } });
    c.that('entityType PROMISE -> 400', badType.status === 400, brief(badType));
    const after = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: id })).json.totalElements;
    c.that('only the valid 200-char one was written', after === before + 1, { before, after });
    return done(c, { codeRef: `${BE.ctl}:108` });
  });

  await tcase({ id: 'TAS-API-03', feature: 'Assignee validation', kind: 'API', prd: 'A3',
    title: 'Assignees are people or roles offered at that level; at most 10; duplicates collapse',
    steps: 'admin POST /api/tasks on n2 records with assignees: [CUSTOMER]; 11 distinct tokens; [SALES_POC@CUSTOMER]; [COLLECTION_POC@RECORD] on a CUSTOMER task; [SALES_POC@RECORD] on a PAYMENT task; [USER 999999]; [USER nora, USER nora]',
    expected: 'DESIGN §5.9.1: 400 "Assignees are people or roles"; 400 at most 10; roles not offered for the type/level -> 400; unknown user -> 400; duplicate removed (1 assignee)' }, async () => {
    const c = checks();
    const mk = (type, id, assignees) => rt.createTask(T.admin, { entityType: type, entityId: id, title: rt.uniq('av'), assignees });
    const r1 = await mk('INVOICE', inv('n2').id, [rt.tok.customer()]);
    c.that('CUSTOMER token -> 400', r1.status === 400, brief(r1));
    const eleven = [rt.tok.user(U.nora.id), rt.tok.user(U.sam.id), rt.tok.user(U.admin.id), rt.tok.user(U.bob.id), rt.tok.role('COLLECTION_POC', 'CUSTOMER'), rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER'), rt.tok.role('SALES_POC', 'RECORD'),
      ...[1, 2, 3, 4].map((i) => rt.tok.user(9000 + i))];
    const r2 = await mk('INVOICE', inv('n2').id, eleven);
    c.that('11 tokens -> 400', r2.status === 400, brief(r2));
    const r3 = await mk('INVOICE', inv('n2').id, [rt.tok.role('SALES_POC', 'CUSTOMER')]);
    c.that('SALES_POC at CUSTOMER level -> 400', r3.status === 400, brief(r3));
    const r4 = await mk('CUSTOMER', cust('n2').id, [rt.tok.role('COLLECTION_POC', 'RECORD')]);
    c.that('COLLECTION_POC@RECORD on a CUSTOMER task -> 400', r4.status === 400, brief(r4));
    const r5 = await mk('PAYMENT', pay('n2').id, [rt.tok.role('SALES_POC', 'RECORD')]);
    c.that('SALES_POC@RECORD on a PAYMENT task -> 400', r5.status === 400, brief(r5));
    const r6 = await mk('INVOICE', inv('n2').id, [rt.tok.user(999999)]);
    c.that('unknown user -> 400', r6.status === 400, brief(r6));
    const r7 = await mk('INVOICE', inv('n2').id, [rt.tok.user(U.nora.id), rt.tok.user(U.nora.id), rt.tok.role('COLLECTION_POC', 'CUSTOMER'), rt.tok.role('COLLECTION_POC', 'CUSTOMER')]);
    c.that('duplicates collapse -> 200 with 2 assignees', r7.status === 200 && r7.json.assignees.length === 2, r7.json && r7.json.assignees);
    const r8 = await mk('PAYMENT', pay('n2').id, [rt.tok.role('COLLECTION_POC', 'RECORD')]);
    c.that('COLLECTION_POC@RECORD on a PAYMENT task -> 200', r8.status === 200, brief(r8));
    return done(c, { codeRef: `${BE.store}:62` });
  });

  // ------------------------------------------------------------------ status transitions
  await tcase({ id: 'TAS-API-04', feature: 'Status transitions', kind: 'API', prd: 'A6',
    title: 'OPEN -> IN_PROGRESS -> OPEN -> DONE -> OPEN -> CANCELLED -> OPEN; DONE stamps completedAt, reopening clears it',
    steps: 'admin creates a task on n2 invoice; POST /api/tasks/{id}/status through each move with the current version',
    expected: 'DESIGN §5.9.2: every allowed move 200 and bumps version; DONE sets completedAt; reopening clears it; audit TASK_STATUS_CHANGED per move' }, async () => {
    const c = checks();
    let t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('flow') });
    const path = ['IN_PROGRESS', 'OPEN', 'DONE', 'OPEN', 'CANCELLED', 'OPEN'];
    const trail = [];
    for (const to of path) {
      const r = await rt.tasks.setStatus(T.admin, t.id, to, t.version);
      trail.push(`${to}:${r.status}`);
      if (!c.that(`-> ${to} 200`, r.status === 200 && r.json.status === to, brief(r))) break;
      c.that(`-> ${to} version bumped`, r.json.version === t.version + 1, { before: t.version, after: r.json.version });
      if (to === 'DONE') c.that('DONE sets completedAt', !!r.json.completedAt, r.json.completedAt);
      if (to === 'OPEN' && t.status === 'DONE') c.that('reopen clears completedAt', r.json.completedAt === null, r.json.completedAt);
      t = r.json;
    }
    const a = await rt.api('GET', '/api/audit', { token: T.admin, query: { entityType: 'TASK', entityId: t.id } });
    const acts = (a.json || []).map((e) => e.action);
    c.that('audit has TASK_CREATED and 6 TASK_STATUS_CHANGED', a.status === 200 && acts.includes('TASK_CREATED') && acts.filter((x) => x === 'TASK_STATUS_CHANGED').length === 6, { status: a.status, acts });
    return done(c, { evidence: trail.join(' '), codeRef: `${BE.svc}:297` });
  });

  await tcase({ id: 'TAS-API-05', feature: 'Status transitions (negative)', kind: 'API', prd: 'A6',
    title: 'Same status, DONE -> IN_PROGRESS / CANCELLED, CANCELLED -> DONE, a stale version and a missing version are refused',
    steps: 'admin: task OPEN -> OPEN; DONE -> IN_PROGRESS; DONE -> CANCELLED; CANCELLED -> DONE; stale version; body without version',
    expected: 'DESIGN §5.9.2/§6.12: 400 same status; 400 disallowed moves; 409 stale version; 400 without version; status unchanged' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('neg') });
    const same = await rt.tasks.setStatus(T.admin, t.id, 'OPEN', t.version);
    c.that('OPEN -> OPEN 400', same.status === 400, brief(same));
    const d = await rt.tasks.setStatus(T.admin, t.id, 'DONE', t.version);
    c.that('OPEN -> DONE 200', d.status === 200, brief(d));
    const ip = await rt.tasks.setStatus(T.admin, t.id, 'IN_PROGRESS', d.json.version);
    c.that('DONE -> IN_PROGRESS 400', ip.status === 400, brief(ip));
    const cx = await rt.tasks.setStatus(T.admin, t.id, 'CANCELLED', d.json.version);
    c.that('DONE -> CANCELLED 400', cx.status === 400, brief(cx));
    const stale = await rt.tasks.setStatus(T.admin, t.id, 'OPEN', t.version);
    c.that('stale version -> 409', stale.status === 409, brief(stale));
    const nov = await rt.api('POST', `/api/tasks/${t.id}/status`, { token: T.admin, body: { status: 'OPEN' } });
    c.that('no version -> 400', nov.status === 400, brief(nov));
    const t2 = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('neg2') });
    const cc = await rt.tasks.setStatus(T.admin, t2.id, 'CANCELLED', t2.version);
    const cd = await rt.tasks.setStatus(T.admin, t2.id, 'DONE', cc.json?.version);
    c.that('CANCELLED -> DONE 400', cc.status === 200 && cd.status === 400, `${brief(cc)} / ${brief(cd)}`);
    const bad = await rt.api('POST', `/api/tasks/${t2.id}/status`, { token: T.admin, body: { status: 'FINISHED', version: cc.json?.version } });
    c.that('unknown status -> 400', bad.status === 400, brief(bad));
    const g = await getTask(T.admin, t.id);
    c.that('first task still DONE', g.json.status === 'DONE', g.json.status);
    return done(c, { codeRef: `${BE.svc}:303` });
  });

  // ------------------------------------------------------------------ edit
  await tcase({ id: 'TAS-API-06', feature: 'Edit task', kind: 'API', prd: 'A6',
    title: 'PATCH changes only what is sent (title, description, due date, clear due date, assignees); stale version is 409',
    steps: 'admin creates a task on n2 payment; PATCH {title}; PATCH {description, dueDate}; PATCH {clearDueDate}; PATCH {assignees [ROLE COLLECTION_POC@CUSTOMER]}; PATCH with an old version; PATCH {title:"  "}',
    expected: 'DESIGN §6.12: 200 each with version bumped; untouched fields kept; clearDueDate removes the date; assignees replaced; stale 409; blank title 400; audit TASK_UPDATED and TASK_ASSIGNEES_CHANGED' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'PAYMENT', entityId: pay('n2').id, title: rt.uniq('edit'), description: 'first', dueDate: rt.day(4), assignees: [rt.tok.user(U.nora.id)] });
    const p = (body) => rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body });
    const a = await p({ title: 'Renamed task', version: t.version });
    c.that('title patched, rest kept', a.status === 200 && a.json.title === 'Renamed task' && a.json.description === 'first' && a.json.dueDate === rt.day(4) && a.json.assignees.length === 1, brief(a));
    const b = await p({ description: 'second', dueDate: rt.day(9), version: a.json?.version });
    c.that('description + due patched', b.status === 200 && b.json.description === 'second' && b.json.dueDate === rt.day(9) && b.json.title === 'Renamed task', brief(b));
    const d = await p({ clearDueDate: true, version: b.json?.version });
    c.that('clearDueDate -> null', d.status === 200 && d.json.dueDate === null, brief(d));
    const e = await p({ assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')], version: d.json?.version });
    c.that('assignees replaced', e.status === 200 && e.json.assignees.length === 1 && e.json.assignees[0].token.type === 'ROLE', e.json && e.json.assignees);
    const stale = await p({ title: 'stale', version: t.version });
    c.that('stale version -> 409', stale.status === 409, brief(stale));
    const blank = await p({ title: '   ', version: e.json?.version });
    c.that('blank title -> 400', blank.status === 400, brief(blank));
    const g = await getTask(T.admin, t.id);
    c.that('final state kept (no stale write)', g.json.title === 'Renamed task', g.json.title);
    const au = await rt.api('GET', '/api/audit', { token: T.admin, query: { entityType: 'TASK', entityId: t.id } });
    const acts = (au.json || []).map((x) => x.action);
    c.that('audit TASK_UPDATED + TASK_ASSIGNEES_CHANGED', acts.includes('TASK_UPDATED') && acts.includes('TASK_ASSIGNEES_CHANGED'), acts);
    return done(c, { codeRef: `${BE.svc}:229` });
  });

  // ------------------------------------------------------------------ assignee rights
  await tcase({ id: 'TAS-API-07', feature: 'Assignee may move, not edit', kind: 'API', prd: 'A6',
    title: 'A named assignee without TASK_MANAGE in the region may start, finish and reopen the task, but not edit or cancel it',
    steps: 'admin creates a task on s1 invoice (South) assigned USER nora (READ South); nora: GET, START, DONE, reopen; CANCEL; PATCH title',
    expected: 'DESIGN §5.9.2 Rights: nora sees canEdit=false, canChangeStatus=true, assignedToMe=true; moves 200; CANCEL 403; PATCH 403; status/title unchanged by the refused calls' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('asg'), assignees: [rt.tok.user(U.nora.id)] });
    const g = await getTask(T.nora, t.id);
    c.that('nora GET 200 with flags', g.status === 200 && g.json.canEdit === false && g.json.canChangeStatus === true && g.json.assignedToMe === true, g.json && { canEdit: g.json.canEdit, canChangeStatus: g.json.canChangeStatus, assignedToMe: g.json.assignedToMe });
    let v = t.version;
    for (const to of ['IN_PROGRESS', 'DONE', 'OPEN']) {
      const r = await rt.tasks.setStatus(T.nora, t.id, to, v);
      c.that(`nora -> ${to} 200`, r.status === 200, brief(r));
      if (r.ok) v = r.json.version;
    }
    const cx = await rt.tasks.setStatus(T.nora, t.id, 'CANCELLED', v);
    c.that('nora CANCEL 403', cx.status === 403, brief(cx));
    const pt = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.nora, body: { title: 'hijack', version: v } });
    c.that('nora PATCH 403', pt.status === 403, brief(pt));
    const g2 = await getTask(T.admin, t.id);
    c.that('still OPEN, title unchanged', g2.json.status === 'OPEN' && g2.json.title === t.title, { status: g2.json.status, title: g2.json.title });
    return done(c, { codeRef: `${BE.svc}:308` });
  });

  await tcase({ id: 'TAS-API-08', feature: 'Non-assignee refused', kind: 'API', prd: 'A6',
    title: 'Someone who can see a task but neither manages its region nor is assigned cannot change its status',
    steps: 'admin creates a task on s1 invoice assigned USER sam; nora (READ South) GET it and POST status DONE',
    expected: 'DESIGN §5.9.2 "Anyone else gets 403": GET 200 with canEdit=false, canChangeStatus=false, assignedToMe=false; status 403' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('notmine'), assignees: [rt.tok.user(U.sam.id)] });
    const g = await getTask(T.nora, t.id);
    c.that('nora sees it read-only', g.status === 200 && g.json.canEdit === false && g.json.canChangeStatus === false && g.json.assignedToMe === false, g.json && { canEdit: g.json.canEdit, canChangeStatus: g.json.canChangeStatus });
    const r = await rt.tasks.setStatus(T.nora, t.id, 'DONE', t.version);
    c.that('nora DONE -> 403', r.status === 403, brief(r));
    return done(c, { codeRef: `${BE.svc}:311` });
  });

  await tcase({ id: 'TAS-API-09', feature: 'TASK_VIEW-only user', kind: 'API', prd: 'A6',
    title: 'A user whose role has TASK_VIEW but not TASK_MANAGE cannot create or edit tasks, but can work one assigned to them',
    steps: 'tara (role without TASK_MANAGE, MANAGE/ALL South): POST /api/tasks on s1 invoice; admin creates a task assigned USER tara; tara PATCH; tara DONE',
    expected: 'DESIGN §6.12 gates: POST 403, PATCH 403 (TASK_MANAGE); status DONE 200 (effective assignee)' }, async () => {
    const c = checks();
    const cr = await rt.createTask(T.tara, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('tara') });
    c.that('tara create 403', cr.status === 403, brief(cr));
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('fortara'), assignees: [rt.tok.user(U.tara.id)] });
    const pt = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.tara, body: { title: 'x', version: t.version } });
    c.that('tara PATCH 403', pt.status === 403, brief(pt));
    const dn = await rt.tasks.setStatus(T.tara, t.id, 'DONE', t.version);
    c.that('tara DONE 200', dn.status === 200 && dn.json.status === 'DONE', brief(dn));
    return done(c, { codeRef: `${BE.ctl}:106` });
  });

  // ------------------------------------------------------------------ roles as assignees
  await tcase({ id: 'TAS-API-10', feature: 'Role held by several people', kind: 'API', prd: 'A6',
    title: 'A customer-level role held by several people: all holders are assignees (primary first), all are told, all see it in My tasks and may work it',
    steps: 's2 (South) Collection POC = sam (primary) + nora (secondary). admin creates a task on s2 invoice assigned ROLE COLLECTION_POC@CUSTOMER; GET; notifications of sam and nora; /api/tasks/mine for each; nora marks it DONE; nora CANCEL',
    expected: 'DESIGN §5.9.1 (ROLE -> all active holders, primary first) / A4 (primary): people=[sam primary:true, nora primary:false]; TASK_ASSIGNED to both; in both /mine; nora (effective assignee via role) DONE 200 but cannot cancel' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s2').id, title: rt.uniq('multi'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const g = await getTask(T.admin, t.id);
    const people = g.json.assignees[0].people;
    c.that('people = sam (primary) then nora', people.length === 2 && people[0].userId === U.sam.id && people[0].primary === true && people[1].userId === U.nora.id && people[1].primary === false, people);
    c.that('label "Collection POC (customer)"', g.json.assignees[0].label === 'Collection POC (customer)', g.json.assignees[0].label);
    const ns = await rt.waitFor(() => notesFor(T.sam, t.id), { timeoutMs: 5000 });
    const nn = await rt.waitFor(() => notesFor(T.nora, t.id), { timeoutMs: 5000 });
    c.that('sam told', ns && ns.length === 1 && ns[0].title === `Task assigned: ${t.title}`, ns);
    c.that('nora told', nn && nn.length === 1, nn);
    const ms = ids(await rt.tasks.mine(T.sam, { filter: [`id:eq:${t.id}`] }));
    const mn = ids(await rt.tasks.mine(T.nora, { filter: [`id:eq:${t.id}`] }));
    c.that('in sam\'s and nora\'s My tasks', ms.includes(t.id) && mn.includes(t.id), { ms, mn });
    const gn = await getTask(T.nora, t.id);
    c.that('nora assignedToMe + canChangeStatus', gn.json.assignedToMe === true && gn.json.canChangeStatus === true && gn.json.canEdit === false, { a: gn.json.assignedToMe, s: gn.json.canChangeStatus, e: gn.json.canEdit });
    const cx = await rt.tasks.setStatus(T.nora, t.id, 'CANCELLED', t.version);
    c.that('nora CANCEL 403', cx.status === 403, brief(cx));
    const d = await rt.tasks.setStatus(T.nora, t.id, 'DONE', t.version);
    c.that('nora DONE 200', d.status === 200, brief(d));
    return done(c, { codeRef: `${BE.asvc}:144` });
  });

  await tcase({ id: 'TAS-API-11', feature: 'Record-level roles', kind: 'API', prd: 'A6',
    title: 'Sales POC of this invoice and Collection POC of this payment resolve to the record\'s own POC, not the customer\'s seat',
    steps: 'fresh North customer: Collection seat nora; invoice with salesPoc sam; payment with collectionPoc sam. admin creates task A on the invoice [SALES_POC@RECORD] and task B on the payment [COLLECTION_POC@RECORD]; /mine for sam and nora',
    expected: 'DESIGN §5.9.3 RECORD branches: A and B resolve to sam (people=[sam]); both in sam\'s My tasks; neither in nora\'s' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH', { invoice: false });
    const iv = rt.need(await rt.createInvoice(T.admin, { customerId: cu.id, amount: 300, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.sam.id }), 'invoice');
    const py = rt.need(await rt.createPayment(T.admin, { customerId: cu.id, amount: 50, invoiceIds: [iv.id], collectionPocUserId: U.sam.id }), 'payment');
    const a = await task(T.admin, { entityType: 'INVOICE', entityId: iv.id, title: rt.uniq('recA'), assignees: [rt.tok.role('SALES_POC', 'RECORD')] });
    const b = await task(T.admin, { entityType: 'PAYMENT', entityId: py.id, title: rt.uniq('recB'), assignees: [rt.tok.role('COLLECTION_POC', 'RECORD')] });
    const ga = await getTask(T.admin, a.id); const gb = await getTask(T.admin, b.id);
    c.that('A -> sam', ga.json.assignees[0].people.map((p) => p.userId).join() === String(U.sam.id) && ga.json.assignees[0].label === 'Sales POC (this invoice)', ga.json.assignees);
    c.that('B -> sam', gb.json.assignees[0].people.map((p) => p.userId).join() === String(U.sam.id) && /this payment/.test(gb.json.assignees[0].label), gb.json.assignees);
    const ms = ids(await rt.tasks.mine(T.sam, { filter: [`customerId:eq:${cu.id}`] }));
    const mn = ids(await rt.tasks.mine(T.nora, { filter: [`customerId:eq:${cu.id}`] }));
    c.that('both in sam\'s My tasks', ms.includes(a.id) && ms.includes(b.id), ms);
    c.that('neither in nora\'s', !mn.includes(a.id) && !mn.includes(b.id), mn);
    return done(c, { codeRef: `${BE.assigned}:116` });
  });

  await tcase({ id: 'TAS-API-12', feature: 'Unheld role', kind: 'API', prd: 'A6',
    title: 'A role nobody holds is kept as an assignee and reported unresolved',
    steps: 'admin creates a task on n2 customer assigned ROLE CUSTOMER_SUCCESS_POC@CUSTOMER (no Success POC seated); GET',
    expected: 'AssigneeDto (§6.12): label "Customer Success POC (customer)", people [], unresolved true' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'CUSTOMER', entityId: cust('n2').id, title: rt.uniq('unheld'), assignees: [rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER')] });
    const a = (await getTask(T.admin, t.id)).json.assignees[0];
    c.that('unresolved, nobody', a.unresolved === true && a.people.length === 0 && /Customer Success POC/.test(a.label), a);
    return done(c, { codeRef: `${BE.asvc}:250` });
  });

  // ------------------------------------------------------------------ my tasks
  await tcase({ id: 'TAS-API-13', feature: 'My tasks', kind: 'API', prd: 'A6',
    title: 'My tasks lists tasks assigned directly, never someone else\'s; the count is OPEN + IN_PROGRESS; lockedFilters is []',
    steps: 'fresh staff user (MANAGE/ALL West); admin creates 3 tasks on w2 records assigned USER him (one then DONE, one IN_PROGRESS) and 1 assigned to wendy; GET /api/tasks/mine, /mine/count, /mine/summary',
    expected: 'DESIGN §5.9.5/§6.12: /mine has his 3 and not wendy\'s; lockedFilters []; /mine/count {count:2}; /mine/summary {count:3, open:1, inProgress:1}' }, async () => {
    const c = checks();
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'mt', { grants: [{ regionId: RG.WEST.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const ut = await rt.login(u.username);
    const mk = (title, who) => task(T.admin, { entityType: 'INVOICE', entityId: inv('w2').id, title, assignees: [rt.tok.user(who)] });
    const a = await mk(rt.uniq('mA'), u.id); const b = await mk(rt.uniq('mB'), u.id); const d = await mk(rt.uniq('mC'), u.id);
    const other = await mk(rt.uniq('mW'), U.wendy.id);
    rt.need(await rt.tasks.setStatus(T.admin, b.id, 'DONE', b.version), 'done');
    rt.need(await rt.tasks.setStatus(ut, d.id, 'IN_PROGRESS', d.version), 'start');
    const m = await rt.tasks.mine(ut);
    const got = ids(m);
    c.that('/mine 200 with exactly his 3', m.status === 200 && got.length === 3 && [a.id, b.id, d.id].every((x) => got.includes(x)) && !got.includes(other.id), got);
    c.that('lockedFilters []', Array.isArray(m.json?.lockedFilters) && m.json.lockedFilters.length === 0, m.json?.lockedFilters);
    c.that('assignedToMe on every row', (m.json?.content || []).every((x) => x.assignedToMe === true));
    const cnt = await rt.tasks.mineCount(ut);
    c.that('/mine/count = 2', cnt.status === 200 && cnt.json.count === 2, brief(cnt));
    const sm = await rt.api('GET', '/api/tasks/mine/summary', { token: ut });
    c.that('/mine/summary shape', sm.status === 200 && sm.json.count === 3 && sm.json.open === 1 && sm.json.inProgress === 1 && 'overdue' in sm.json && 'dueToday' in sm.json, brief(sm));
    return done(c, { codeRef: `${BE.svc}:468` });
  });

  await tcase({ id: 'TAS-API-14', feature: 'My tasks through a seat', kind: 'API', prd: 'A6',
    title: 'A Collection POC seat added after the task makes it appear in My tasks; removing the seat makes it disappear',
    steps: 'fresh North customer (seat nora) + fresh user X (MANAGE/ALL North); admin task on the customer [COLLECTION_POC@CUSTOMER]; X /mine; admin adds X as a second Collection POC; X /mine & /mine/count; admin removes X\'s seat; X /mine',
    expected: 'DESIGN §8.5 MyTasksTest: not listed -> listed (assignedToMe true, count 1) -> not listed' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH', { invoice: false });
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'seat', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const ut = await rt.login(u.username);
    const t = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('seat'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    c.that('before seat: not in /mine', !ids(await rt.tasks.mine(ut)).includes(t.id));
    const seat = await rt.addCustomerPoc(T.admin, cu.id, 'COLLECTION', u.id, false);
    const m = await rt.tasks.mine(ut);
    c.that('after seat: in /mine', ids(m).includes(t.id), ids(m));
    c.that('count 1', (await rt.tasks.mineCount(ut)).json.count === 1);
    const del = await rt.api('DELETE', `/api/customers/${cu.id}/pocs/${seat.id}`, { token: T.admin });
    c.that('seat removed', del.ok, brief(del));
    c.that('after removal: not in /mine', !ids(await rt.tasks.mine(ut)).includes(t.id));
    c.that('count 0', (await rt.tasks.mineCount(ut)).json.count === 0);
    return done(c, { codeRef: `${BE.assigned}:103` });
  });

  // ------------------------------------------------------------------ notifications
  await tcase({ id: 'TAS-API-15', feature: 'Assignment notifications', kind: 'API', prd: 'A6',
    title: 'TASK_ASSIGNED goes to the people newly assigned (not the actor); an edit tells only those added',
    steps: 'sam (MANAGE North) creates a task on n2 invoice assigned [USER sam, USER bob? no - USER admin]; then PATCH adds USER nora; then PATCH re-sends the same list',
    expected: 'DESIGN §5.9.2 Notifications: admin gets 1 TASK_ASSIGNED "Task assigned: <title>" link /tasks/{id}; sam (actor) gets none; after the PATCH nora gets 1 and admin still 1; re-sending the same list notifies nobody again' }, async () => {
    const c = checks();
    const t = await task(T.sam, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('note'), assignees: [rt.tok.user(U.sam.id), rt.tok.user(U.admin.id)] });
    const na = await rt.waitFor(() => notesFor(T.admin, t.id), { timeoutMs: 5000 });
    c.that('admin told once, title and link', na && na.length === 1 && na[0].title === `Task assigned: ${t.title}` && na[0].link === `/tasks/${t.id}`, na);
    c.that('actor sam not told', (await notesFor(T.sam, t.id)).length === 0);
    const p = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.sam, body: { assignees: [rt.tok.user(U.sam.id), rt.tok.user(U.admin.id), rt.tok.user(U.nora.id)], version: t.version } });
    c.that('PATCH 200', p.status === 200, brief(p));
    await rt.sleep(500);
    c.that('nora told once', (await notesFor(T.nora, t.id)).length === 1);
    c.that('admin not told again', (await notesFor(T.admin, t.id)).length === 1);
    const p2 = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.sam, body: { assignees: [rt.tok.user(U.sam.id), rt.tok.user(U.admin.id), rt.tok.user(U.nora.id)], version: p.json?.version } });
    await rt.sleep(300);
    c.that('same list again: nobody re-told', p2.status === 200 && (await notesFor(T.nora, t.id)).length === 1 && (await notesFor(T.admin, t.id)).length === 1, brief(p2));
    return done(c, { codeRef: `${BE.svc}:387` });
  });

  await tcase({ id: 'TAS-API-16', feature: 'Done notification', kind: 'API', prd: 'A6',
    title: 'TASK_DONE tells the creator when someone else completes the task, and nobody when the creator does',
    steps: 'sam creates task X on n2 invoice assigned USER nora; nora marks X DONE; sam creates task Y and marks it DONE himself',
    expected: 'DESIGN §5.9.2: sam gets one TASK_DONE "Task done: <X>" link /tasks/{X}; none for Y' }, async () => {
    const c = checks();
    const x = await task(T.sam, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('dx'), assignees: [rt.tok.user(U.nora.id)] });
    rt.need(await rt.tasks.setStatus(T.nora, x.id, 'DONE', x.version), 'nora done');
    const y = await task(T.sam, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('dy'), assignees: [rt.tok.user(U.nora.id)] });
    rt.need(await rt.tasks.setStatus(T.sam, y.id, 'DONE', y.version), 'sam done');
    await rt.sleep(300);
    const dx = await notesFor(T.sam, x.id, 'TASK_DONE');
    const dy = await notesFor(T.sam, y.id, 'TASK_DONE');
    c.that('sam told X done', dx.length === 1 && dx[0].title === `Task done: ${x.title}`, dx);
    c.that('no TASK_DONE for Y', dy.length === 0, dy);
    return done(c, { codeRef: `${BE.svc}:329` });
  });

  await tcase({ id: 'TAS-API-17', feature: 'Role holder without TASK_VIEW', kind: 'API', prd: 'A6',
    title: 'A seat holder who cannot see tasks is neither an effective assignee nor told; naming them directly is refused',
    steps: 'n2 Collection POC = nora (primary) + nina (role without TASK_*). admin task on n2 invoice [COLLECTION_POC@CUSTOMER]; GET people; nina notifications; nina status DONE; admin POST task naming USER nina',
    expected: 'DESIGN §5.9.1 (PC-2) / FIXLOG CF-9: people = [nora] only; nina no TASK_ASSIGNED; nina status -> 403; USER nina -> 400 fieldErrors.assignees "... cannot see tasks here", nothing written' }, async () => {
    const c = checks();
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('nina'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const people = (await getTask(T.admin, t.id)).json.assignees[0].people.map((p) => p.userId);
    c.that('people = [nora]', people.length === 1 && people[0] === U.nora.id, people);
    await rt.sleep(300);
    c.that('nina not told', (await notesFor(T.nina, t.id)).length === 0);
    const s = await rt.tasks.setStatus(T.nina, t.id, 'DONE', t.version);
    c.that('nina status 403', s.status === 403, brief(s));
    const before = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id })).json.totalElements;
    const r = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('nina2'), assignees: [rt.tok.user(U.nina.id)] });
    c.that('USER nina -> 400 fieldErrors.assignees', r.status === 400 && /cannot see tasks here/.test(r.json?.fieldErrors?.assignees || ''), brief(r));
    const after = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id })).json.totalElements;
    c.that('nothing written', after === before, { before, after });
    return done(c, { codeRef: `${BE.asvc}:123` });
  });

  await tcase({ id: 'TAS-API-18', feature: 'Assignee must see the record', kind: 'API', prd: 'B1',
    title: 'Naming a person who cannot see the record (another region) is a 400 on assignees, on create and on edit',
    steps: 'admin POST /api/tasks on n1 invoice (North) assignees [USER wendy] (West only); admin PATCH an n1 task adding wendy',
    expected: 'DESIGN §6.12: 400 fieldErrors.assignees "Wendy West cannot see this record"; nothing written; PATCH 400 and assignees unchanged' }, async () => {
    const c = checks();
    const before = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: inv('n1').id })).json.totalElements;
    const r = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: inv('n1').id, title: rt.uniq('wendy'), assignees: [rt.tok.user(U.wendy.id)] });
    c.that('create 400 fieldErrors.assignees', r.status === 400 && /Wendy West cannot see this record/.test(r.json?.fieldErrors?.assignees || ''), brief(r));
    const after = (await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: inv('n1').id })).json.totalElements;
    c.that('nothing written', after === before, { before, after });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n1').id, title: rt.uniq('pw'), assignees: [rt.tok.user(U.nora.id)] });
    const p = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.admin, body: { assignees: [rt.tok.user(U.nora.id), rt.tok.user(U.wendy.id)], version: t.version } });
    c.that('PATCH 400 fieldErrors.assignees', p.status === 400 && p.json?.fieldErrors?.assignees, brief(p));
    const g = await getTask(T.admin, t.id);
    c.that('assignees unchanged', g.json.assignees.length === 1 && g.json.version === t.version, g.json.assignees);
    return done(c, { codeRef: `${BE.asvc}:128` });
  });

  // ------------------------------------------------------------------ region scoping
  // Fixtures shared by the region cases: one task per region, made by admin.
  const regionTasks = {};
  for (const k of ['n1', 'n2', 's1', 'w1', 'd1']) {
    regionTasks[k] = await task(T.admin, { entityType: 'INVOICE', entityId: inv(k).id, title: rt.uniq(`rg-${k}`) });
  }

  await tcase({ id: 'TAS-API-19', feature: 'Task lists by region', kind: 'API', prd: 'B1',
    title: 'Task lists, summary and My tasks answer "which regions": only granted regions, narrowed by regionIds',
    steps: 'one task per region (n1, n2, s1, w1, d1). wendy GET /api/tasks; nora GET /api/tasks (all) and ?regionIds=SOUTH and ?regionIds=WEST; nora /summary?regionIds=SOUTH; GET /api/tasks?regionIds=abc',
    expected: 'DESIGN §6.0/§6.1: wendy sees only WEST rows; nora sees NORTH+SOUTH, never WEST/DEFAULT; regionIds=SOUTH -> only SOUTH rows and envelope regionIds [SOUTH]; WEST (ungranted) -> empty, regionIds []; summary narrowed; abc -> 400 REGION_IDS_INVALID' }, async () => {
    const c = checks();
    const wl = await rt.list(T.wendy, '/api/tasks');
    const wr = new Set((wl.json?.content || []).map((x) => x.regionCode));
    c.that('wendy: only WEST', wl.status === 200 && [...wr].every((x) => x === 'WEST') && ids(wl).includes(regionTasks.w1.id), [...wr]);
    const nl = await rt.list(T.nora, '/api/tasks');
    const nr = new Set((nl.json?.content || []).map((x) => x.regionCode));
    c.that('nora: NORTH/SOUTH only', [...nr].every((x) => x === 'NORTH' || x === 'SOUTH') && ids(nl).includes(regionTasks.s1.id) && ids(nl).includes(regionTasks.n1.id) && !ids(nl).includes(regionTasks.w1.id) && !ids(nl).includes(regionTasks.d1.id), [...nr]);
    const ns = await rt.list(T.nora, '/api/tasks', {}, { regionIds: [RG.SOUTH.id] });
    c.that('nora regionIds=SOUTH: only SOUTH, envelope [SOUTH]', (ns.json?.content || []).every((x) => x.regionId === RG.SOUTH.id) && ids(ns).includes(regionTasks.s1.id) && JSON.stringify(ns.json?.regionIds) === JSON.stringify([RG.SOUTH.id]), { regionIds: ns.json?.regionIds, n: ns.json?.content?.length });
    const nw = await rt.api('GET', '/api/tasks', { token: T.nora, query: { size: 50, regionIds: RG.WEST.id } });
    c.that('nora regionIds=WEST: 200 empty, regionIds []', nw.status === 200 && nw.json.totalElements === 0 && nw.json.regionIds.length === 0, brief(nw));
    const all = await rt.api('GET', '/api/tasks/summary', { token: T.nora });
    const sth = await rt.api('GET', '/api/tasks/summary', { token: T.nora, query: { regionIds: RG.SOUTH.id } });
    const southCount = ns.json?.totalElements;
    c.that('summary narrowed by regionIds', sth.status === 200 && sth.json.count === southCount && all.json.count > sth.json.count, { all: all.json, south: sth.json, southCount });
    const bad = await rt.api('GET', '/api/tasks', { token: T.nora, query: { regionIds: 'abc' } });
    c.that('regionIds=abc -> 400 REGION_IDS_INVALID', bad.status === 400 && bad.json?.error === 'REGION_IDS_INVALID', brief(bad));
    return done(c, { codeRef: `${BE.svc}:443` });
  });

  await tcase({ id: 'TAS-API-20', feature: 'Tasks by id and writes across regions', kind: 'API', prd: 'B1',
    title: 'A task in a region the user does not hold is missing (404) for reads and writes; a READ region refuses writes (403)',
    steps: 'wendy GET/POST status/PATCH the n1 task, POST /api/tasks on n1 invoice; nora (READ South) POST /api/tasks on s1 invoice and PATCH the s1 task; nora GET s1 task',
    expected: 'DESIGN §5.9.2 / §6.0: wendy 404 for each; nora create 403 and PATCH 403 in South; nora GET 200 (canEdit false)' }, async () => {
    const c = checks();
    const n1 = regionTasks.n1; const s1 = regionTasks.s1;
    const g = await getTask(T.wendy, n1.id);
    c.that('wendy GET 404', g.status === 404, brief(g));
    const s = await rt.tasks.setStatus(T.wendy, n1.id, 'DONE', 0);
    c.that('wendy status 404', s.status === 404, brief(s));
    const p = await rt.api('PATCH', `/api/tasks/${n1.id}`, { token: T.wendy, body: { title: 'x', version: 0 } });
    c.that('wendy PATCH 404', p.status === 404, brief(p));
    const cr = await rt.createTask(T.wendy, { entityType: 'INVOICE', entityId: inv('n1').id, title: 'x' });
    c.that('wendy create on North 404', cr.status === 404, brief(cr));
    const nc = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('noraS') });
    c.that('nora create in READ South 403', nc.status === 403, brief(nc));
    const np = await rt.api('PATCH', `/api/tasks/${s1.id}`, { token: T.nora, body: { title: 'x', version: s1.version } });
    c.that('nora PATCH in READ South 403', np.status === 403, brief(np));
    const ng = await getTask(T.nora, s1.id);
    c.that('nora GET South task 200, canEdit false', ng.status === 200 && ng.json.canEdit === false, brief(ng));
    const nn = await rt.createTask(T.nora, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('noraN') });
    c.that('nora create in MANAGE North 200', nn.status === 200, brief(nn));
    return done(c, { codeRef: `${BE.svc}:200` });
  });

  await tcase({ id: 'TAS-API-21', feature: 'Tasks and the POC book', kind: 'API', prd: 'B1',
    title: 'A book-only user sees and adds tasks only on customers in their book',
    steps: 'bob (MANAGE/BOOK North; POC of n1 only): GET /api/tasks; GET n1 and n2 tasks; POST task on n1 and n2 invoices',
    expected: 'DESIGN §5.6.4 (task visibility is its record\'s): list has n1 tasks, no n2 tasks; n2 task GET 404; create on n1 200; on n2 404' }, async () => {
    const c = checks();
    const bl = await rt.list(T.bob, '/api/tasks');
    const bi = ids(bl);
    const custs = new Set((bl.json?.content || []).map((x) => x.customerId));
    c.that("bob list: n1 tasks, none of n2 (not his book), only NORTH", bi.includes(regionTasks.n1.id) && !bi.includes(regionTasks.n2.id) && !custs.has(cust("n2").id) && (bl.json?.content || []).every((x) => x.regionCode === "NORTH"), [...custs]);
    c.that('bob GET n2 task 404', (await getTask(T.bob, regionTasks.n2.id)).status === 404);
    c.that('bob GET n1 task 200', (await getTask(T.bob, regionTasks.n1.id)).status === 200);
    const a = await rt.createTask(T.bob, { entityType: 'INVOICE', entityId: inv('n1').id, title: rt.uniq('bob1') });
    c.that('bob create on n1 200', a.status === 200, brief(a));
    const b = await rt.createTask(T.bob, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('bob2') });
    c.that('bob create on n2 404', b.status === 404, brief(b));
    return done(c, { codeRef: `${BE.svc}:413` });
  });

  await tcase({ id: 'TAS-API-22', feature: 'Assignee people picker by region', kind: 'API', prd: 'B1',
    title: 'The person picker offers only staff granted in the record\'s region (or the regions asked about), never the whole directory',
    steps: 'admin GET /api/assignees/people?entityType=INVOICE&entityId=<w1 inv>; ...&entityId=<n1 inv>; wendy GET /api/assignees/people; admin ?regionIds=WEST; wendy ?entityType=INVOICE&entityId=<n1 inv>; customer login GET /api/assignees/people',
    expected: 'DESIGN §6.13 (SC-18): West record -> wendy (+admin), no nora/sam/bob; North record -> nora/sam/bob/admin, no wendy; wendy (West only) -> no nora/sam; regionIds=WEST -> no nora; wendy asking about a North record -> 404; customer -> 403' }, async () => {
    const c = checks();
    const people = async (tok, query) => rt.api('GET', '/api/assignees/people', { token: tok, query });
    const un = (r) => (r.json || []).map((p) => p.username);
    const west = await people(T.admin, { entityType: 'INVOICE', entityId: inv('w1').id });
    c.that('West record: wendy, no nora/sam/bob', west.status === 200 && un(west).includes('wendy') && !['nora', 'sam', 'bob'].some((x) => un(west).includes(x)), un(west));
    const north = await people(T.admin, { entityType: 'INVOICE', entityId: inv('n1').id });
    c.that('North record: nora/sam/bob, no wendy', ['nora', 'sam', 'bob'].every((x) => un(north).includes(x)) && !un(north).includes('wendy'), un(north));
    const wp = await people(T.wendy, {});
    c.that('wendy: no nora/sam', wp.status === 200 && un(wp).includes('wendy') && !un(wp).includes('nora') && !un(wp).includes('sam'), un(wp));
    const aw = await people(T.admin, { regionIds: RG.WEST.id });
    c.that('admin regionIds=WEST: no nora', aw.status === 200 && !un(aw).includes('nora') && un(aw).includes('wendy'), un(aw));
    const q = await people(T.admin, { q: 'nor', entityType: 'INVOICE', entityId: inv('n1').id });
    c.that('q filters by name', q.status === 200 && un(q).includes('nora') && !un(q).includes('sam'), un(q));
    const wn = await people(T.wendy, { entityType: 'INVOICE', entityId: inv('n1').id });
    c.that('wendy on a North record -> 404', wn.status === 404, brief(wn));
    const cu = await people(T.cust, {});
    c.that('customer login -> 403', cu.status === 403, brief(cu));
    return done(c, { codeRef: `${BE.asvc}:404` });
  });

  await tcase({ id: 'TAS-API-23', feature: 'Assignee roles (To-field model)', kind: 'API', prd: 'A3',
    title: 'The assignee context offers the email To field\'s roles by level: customer-level seats everywhere, plus the record\'s own POC',
    steps: 'admin GET /api/assignees/context?entityType=CUSTOMER|INVOICE|PAYMENT&entityId=<s2 records>; customer login GET it',
    expected: 'DESIGN §6.13 / §5.9.1: CUSTOMER -> CUSTOMER_SUCCESS_POC@CUSTOMER, COLLECTION_POC@CUSTOMER; INVOICE adds SALES_POC@RECORD; PAYMENT adds COLLECTION_POC@RECORD; the s2 Collection seat lists sam and nora; customer login 403' }, async () => {
    const c = checks();
    const ctx = async (type, id) => rt.api('GET', '/api/assignees/context', { token: T.admin, query: { entityType: type, entityId: id } });
    const keys = (r) => (r.json?.roles || []).map((x) => `${x.role}@${x.level}`).sort().join(',');
    const cc = await ctx('CUSTOMER', cust('s2').id); const ci = await ctx('INVOICE', inv('s2').id); const cp = await ctx('PAYMENT', pay('s2').id);
    c.that('CUSTOMER roles', keys(cc) === 'COLLECTION_POC@CUSTOMER,CUSTOMER_SUCCESS_POC@CUSTOMER', keys(cc));
    c.that('INVOICE roles', keys(ci) === 'COLLECTION_POC@CUSTOMER,CUSTOMER_SUCCESS_POC@CUSTOMER,SALES_POC@RECORD', keys(ci));
    c.that('PAYMENT roles', keys(cp) === 'COLLECTION_POC@CUSTOMER,COLLECTION_POC@RECORD,CUSTOMER_SUCCESS_POC@CUSTOMER', keys(cp));
    const coll = (cc.json?.roles || []).find((x) => x.role === 'COLLECTION_POC' && x.level === 'CUSTOMER');
    const names = (coll?.people || []).map((p) => p.name);
    c.that('s2 Collection seat holders: sam + nora', names.includes('Sam South') && names.includes('Nora North'), names);
    const cu = await rt.api('GET', '/api/assignees/context', { token: T.cust, query: { entityType: 'INVOICE', entityId: inv('s1').id } });
    c.that('customer login 403', cu.status === 403, brief(cu));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/assignee/AssigneeController.java:33' });
  });

  // ------------------------------------------------------------------ record tabs
  await tcase({ id: 'TAS-API-24', feature: 'Record Tasks tab data', kind: 'API', prd: 'A6',
    title: 'A record\'s tab lists its tasks; the customer\'s also lists tasks on its invoices and payments; count endpoint = {count, open}',
    steps: 'fresh North customer with invoice + payment; admin creates 1 task on the customer, 2 on the invoice (one DONE), 1 on the payment; GET /api/tasks?entityType&entityId for each; ?customerId; /api/tasks/count?entityType=INVOICE&entityId; /summary?customerId',
    expected: 'DESIGN §5.9.5/§6.12: invoice tab 2 rows, payment 1, customer entity 1; customerId -> 4; count {count:2, open:1}; customer summary open+inProgress = 3' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH');
    const mk = (type, id) => task(T.admin, { entityType: type, entityId: id, title: rt.uniq('tab') });
    await mk('CUSTOMER', cu.id); const i1 = await mk('INVOICE', cu.invoice.id); await mk('INVOICE', cu.invoice.id); await mk('PAYMENT', cu.payment.id);
    rt.need(await rt.tasks.setStatus(T.admin, i1.id, 'DONE', i1.version), 'done');
    const l = async (q) => (await rt.tasks.list(T.admin, q)).json;
    const li = await l({ entityType: 'INVOICE', entityId: cu.invoice.id });
    const lp = await l({ entityType: 'PAYMENT', entityId: cu.payment.id });
    const lc = await l({ entityType: 'CUSTOMER', entityId: cu.id });
    const lcu = await l({ customerId: cu.id });
    c.that('invoice tab 2', li.totalElements === 2, li.totalElements);
    c.that('payment tab 1', lp.totalElements === 1, lp.totalElements);
    c.that('customer entity 1', lc.totalElements === 1, lc.totalElements);
    c.that('customerId 4', lcu.totalElements === 4, lcu.totalElements);
    c.that('context params echoed as applied filters', (li.appliedFilters || []).some((f) => /entityType:eq:INVOICE/.test(f)) && (li.appliedFilters || []).some((f) => new RegExp(`entityId:eq:${cu.invoice.id}`).test(f)), li.appliedFilters);
    const cnt = await rt.api('GET', '/api/tasks/count', { token: T.admin, query: { entityType: 'INVOICE', entityId: cu.invoice.id } });
    c.that('count {count:2, open:1}', cnt.status === 200 && cnt.json.count === 2 && cnt.json.open === 1, brief(cnt));
    const sm = await rt.api('GET', '/api/tasks/summary', { token: T.admin, query: { customerId: cu.id } });
    c.that('customer summary open+inProgress = 3', sm.status === 200 && sm.json.open + sm.json.inProgress === 3 && sm.json.count === 4, brief(sm));
    return done(c, { codeRef: `${BE.ctl}:171` });
  });

  // ------------------------------------------------------------------ privileges
  await tcase({ id: 'TAS-API-25', feature: 'TASK_VIEW gate', kind: 'API', prd: 'A6',
    title: 'Without TASK_VIEW (a staff role lacking it, or a customer login) every task endpoint is refused',
    steps: 'nina (no TASK_*) and the customer login: GET /api/tasks, /api/tasks/mine, /mine/count, /api/tasks/{id}, /count; POST /api/tasks',
    expected: 'DESIGN §6.12 gates: 403 for each' }, async () => {
    const c = checks();
    const tid = regionTasks.n1.id;
    for (const [who, tk] of [['nina', T.nina], ['cust', T.cust]]) {
      const rs = await Promise.all([
        rt.api('GET', '/api/tasks', { token: tk }), rt.api('GET', '/api/tasks/mine', { token: tk }), rt.api('GET', '/api/tasks/mine/count', { token: tk }),
        rt.api('GET', `/api/tasks/${tid}`, { token: tk }), rt.api('GET', '/api/tasks/count', { token: tk, query: { entityType: 'INVOICE', entityId: inv('n1').id } }),
        rt.createTask(tk, { entityType: 'INVOICE', entityId: inv(who === 'cust' ? 's1' : 'n1').id, title: 'x' })]);
      c.that(`${who}: all 403`, rs.every((r) => r.status === 403), rs.map((r) => r.status));
    }
    return done(c, { codeRef: `${BE.ctl}:36` });
  });

  // ------------------------------------------------------------------ bulk, export
  await tcase({ id: 'TAS-API-26', feature: 'Bulk moves', kind: 'API', prd: 'A6',
    title: 'Bulk START / MARK_DONE apply to tasks the caller manages, and skip those in a region they only read',
    steps: 'admin creates 2 tasks on n2 invoice and 1 on s2 invoice; nora POST /api/tasks/bulk {action MARK_DONE, ids [all 3]}; admin bulk START on the s2 one; admin bulk FOO',
    expected: 'DESIGN §6.12: nora: 2 North succeeded, the South one not succeeded (and still OPEN); admin START succeeds; unknown action 400' }, async () => {
    const c = checks();
    const a = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('b1') });
    const b = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('b2') });
    const s = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s2').id, title: rt.uniq('b3') });
    const r = await rt.api('POST', '/api/tasks/bulk', { token: T.nora, body: { action: 'MARK_DONE', ids: [a.id, b.id, s.id] } });
    c.that('nora bulk 200: North succeeded, South not', r.status === 200 && r.json.succeeded.includes(a.id) && r.json.succeeded.includes(b.id) && !r.json.succeeded.includes(s.id), r.json);
    c.that('South still OPEN', (await getTask(T.admin, s.id)).json.status === 'OPEN');
    c.that('North DONE', (await getTask(T.admin, a.id)).json.status === 'DONE');
    const st = await rt.api('POST', '/api/tasks/bulk', { token: T.admin, body: { action: 'START', ids: [s.id] } });
    c.that('admin START', st.status === 200 && st.json.succeeded.includes(s.id) && (await getTask(T.admin, s.id)).json.status === 'IN_PROGRESS', brief(st));
    const bad = await rt.api('POST', '/api/tasks/bulk', { token: T.admin, body: { action: 'FOO', ids: [s.id] } });
    c.that('unknown action 400', bad.status === 400, brief(bad));
    return done(c, { codeRef: `${BE.ctl}:125` });
  });

  await tcase({ id: 'TAS-API-27', feature: 'Export', kind: 'API', prd: 'B1',
    title: 'Task export is a CSV of the tasks the caller sees, in their regions only',
    steps: 'wendy POST /api/tasks/export {action:"EXPORT", selectAllMatchingFilter:true}; admin same with filters [id:eq:<n1 task>]',
    expected: 'DESIGN §6.12: 200 text/csv with header "Id,Title,Status..."; wendy\'s has the w1 task and no North/South title; admin\'s filtered one has only that task' }, async () => {
    const c = checks();
    const e = await rt.api('POST', '/api/tasks/export', { token: T.wendy, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
    c.that('wendy 200 csv', e.status === 200 && /text\/csv/.test(e.headers['content-type'] || '') && /^﻿?"?Id"?,/.test(e.text), `${e.status} ${e.headers['content-type']} ${e.text.slice(0, 120)}`);
    c.that('wendy csv: w1 task, no n1/s1', e.text.includes(regionTasks.w1.title) && !e.text.includes(regionTasks.n1.title) && !e.text.includes(regionTasks.s1.title));
    const a = await rt.api('POST', '/api/tasks/export', { token: T.admin, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`id:eq:${regionTasks.n1.id}`] } });
    const lines = (a.text || '').trim().split('\n');
    c.that('admin filtered: header + 1 row', a.status === 200 && lines.length === 2 && a.text.includes(regionTasks.n1.title), lines.length);
    return done(c, { codeRef: `${BE.ctl}:140` });
  });

  // ------------------------------------------------------------------ filters
  await tcase({ id: 'TAS-API-28', feature: 'List filters', kind: 'API', prd: 'A6',
    title: 'The tasks list filters by overdue, status and assignee (directly or by role), and sorts by due date by default',
    steps: 'fresh West customer; admin creates task P due -3d, Q due +5d (assigned USER wendy), R (assigned ROLE COLLECTION_POC@CUSTOMER - wendy holds it), S no due date; GET /api/tasks?customerId&filter=overdue:eq:true; filter=assigneeUserId:eq:<wendy>; default sort',
    expected: 'DESIGN §5.9.4: overdue -> [P] (overdue:true on the row); assigneeUserId:eq:wendy -> Q and R, not P/S; default sort dueDate,asc (P before Q)' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'WEST', { payment: false });
    const P = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('P'), dueDate: rt.day(-3) });
    const Q = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('Q'), dueDate: rt.day(5), assignees: [rt.tok.user(U.wendy.id)] });
    const Rr = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('R'), dueDate: rt.day(6), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const S = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('S'), dueDate: null });
    const od = await rt.tasks.list(T.admin, { customerId: cu.id, filter: ['overdue:eq:true'] });
    c.that('overdue -> [P]', od.status === 200 && JSON.stringify(ids(od)) === JSON.stringify([P.id]) && od.json.content[0].overdue === true, brief(od));
    const as = await rt.tasks.list(T.admin, { customerId: cu.id, filter: [`assigneeUserId:eq:${U.wendy.id}`] });
    const ai = ids(as);
    c.that('assignee wendy -> Q,R', as.status === 200 && ai.includes(Q.id) && ai.includes(Rr.id) && !ai.includes(P.id) && !ai.includes(S.id), brief(as));
    const df = await rt.tasks.list(T.admin, { customerId: cu.id });
    const order = ids(df);
    c.that('default sort dueDate asc', df.json.sort === 'dueDate,asc' && order.indexOf(P.id) < order.indexOf(Q.id) && order.indexOf(Q.id) < order.indexOf(Rr.id), { sort: df.json.sort, order });
    const st = await rt.tasks.list(T.admin, { customerId: cu.id, filter: ['status:eq:OPEN'] });
    c.that('status filter', st.json.totalElements === 4, st.json.totalElements);
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java' });
  });

  // ------------------------------------------------------------------ region move, delete
  await tcase({ id: 'TAS-API-29', feature: 'Task region follows its customer', kind: 'API', prd: 'B1',
    title: 'Moving a customer to another region moves its tasks: the new region\'s rights apply at once',
    steps: 'fresh North customer with an invoice task; admin POST /api/customers/{id}/move-region SOUTH; GET the task; nora (MANAGE North, READ South) PATCH it; nora GET /api/tasks?regionIds=NORTH',
    expected: 'B1 "every record belongs to exactly one" / DESIGN §5.9.2 TaskMoveParticipant: task regionCode SOUTH; nora canEdit false and PATCH 403; not in her NORTH list' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH', { payment: false });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('move') });
    const mv = await rt.moveCustomerRegion(T.admin, cu.id, RG.SOUTH.id);
    c.that('move 200', mv.status === 200, brief(mv));
    const g = await getTask(T.admin, t.id);
    c.that('task now SOUTH', g.json.regionCode === 'SOUTH' && g.json.regionId === RG.SOUTH.id, { regionCode: g.json.regionCode });
    const gn = await getTask(T.nora, t.id);
    c.that('nora canEdit false', gn.status === 200 && gn.json.canEdit === false, brief(gn));
    const p = await rt.api('PATCH', `/api/tasks/${t.id}`, { token: T.nora, body: { title: 'x', version: g.json.version } });
    c.that('nora PATCH 403', p.status === 403, brief(p));
    const nl = ids(await rt.list(T.nora, '/api/tasks', {}, { regionIds: [RG.NORTH.id] }));
    c.that('not in nora\'s NORTH list', !nl.includes(t.id));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskMoveParticipant.java' });
  });

  await tcase({ id: 'TAS-API-30', feature: 'Customer deleted', kind: 'API', prd: 'A6',
    title: 'Deleting a customer keeps its tasks as a record: open ones are cancelled, all are marked entityDeleted and frozen',
    steps: 'fresh DEFAULT customer (no invoices); admin task A OPEN and task B DONE on it; DELETE /api/customers/{id}; GET A, B; POST status on A',
    expected: 'DESIGN §5.9.2 TaskCascade: A CANCELLED + entityDeleted true; B stays DONE + entityDeleted true; status change 400; audit TASK_CANCELLED_WITH_RECORD' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'DEFAULT', { invoice: false, payment: false });
    const A = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('delA') });
    const B = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('delB') });
    rt.need(await rt.tasks.setStatus(T.admin, B.id, 'DONE', B.version), 'B done');
    const del = await rt.api('DELETE', `/api/customers/${cu.id}`, { token: T.admin });
    c.that('customer deleted', del.ok, brief(del));
    const ga = await getTask(T.admin, A.id); const gb = await getTask(T.admin, B.id);
    c.that('A cancelled + entityDeleted', ga.status === 200 && ga.json.status === 'CANCELLED' && ga.json.entityDeleted === true, brief(ga));
    c.that('B DONE + entityDeleted', gb.status === 200 && gb.json.status === 'DONE' && gb.json.entityDeleted === true, brief(gb));
    const s = await rt.tasks.setStatus(T.admin, A.id, 'OPEN', ga.json?.version);
    c.that('reopen refused 400', s.status === 400, brief(s));
    c.that('flags off', ga.json?.canEdit === false, { canEdit: ga.json?.canEdit, canChangeStatus: ga.json?.canChangeStatus });
    const au = await rt.api('GET', '/api/audit', { token: T.admin, query: { entityType: 'TASK', entityId: A.id } });
    c.that('audit TASK_CANCELLED_WITH_RECORD', (au.json || []).some((e) => e.action === 'TASK_CANCELLED_WITH_RECORD'), brief(au));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/task/TaskCascade.java:29' });
  });

  await tcase({ id: 'TAS-API-31', feature: 'Deleted-record task flags', kind: 'API', prd: 'A6',
    title: 'A task on a deleted customer reports canChangeStatus=false to its manager (no move is possible)',
    steps: 'as TAS-API-30: after DELETE /api/customers/{id}, admin GET the cancelled task',
    expected: 'DESIGN §6.12 flags are server-computed; the server refuses every move on it (400), so canChangeStatus should be false' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'DEFAULT', { invoice: false, payment: false });
    const A = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('delF') });
    rt.need(await rt.api('DELETE', `/api/customers/${cu.id}`, { token: T.admin }), 'delete', [200, 204]);
    const g = await getTask(T.admin, A.id);
    const s = await rt.tasks.setStatus(T.admin, A.id, 'OPEN', g.json?.version);
    c.that('move refused (400)', s.status === 400, brief(s));
    c.that('canChangeStatus false', g.json?.canChangeStatus === false, { canEdit: g.json?.canEdit, canChangeStatus: g.json?.canChangeStatus, status: g.json?.status });
    return done(c, { codeRef: `${BE.svc}:583`, severity: 'low', evidence: `GET /api/tasks/${A.id} -> canChangeStatus=${g.json?.canChangeStatus}; POST status OPEN -> ${s.status}` });
  });

  // ------------------------------------------------------------------ automation-created tasks
  await tcase({ id: 'TAS-API-32', feature: 'Tasks made by a rule', kind: 'API', prd: 'A6',
    title: 'A task a rule creates is an ordinary task: origin AUTOMATION with its rule, in its assignee\'s My tasks, assignee told',
    steps: 'admin creates a MANUAL rule on INVOICE restricted to WEST with CREATE_TASK "Chase {{Invoice.Number}}" assigned USER wendy; POST /rules/{id}/run {recordIds:[w2 inv]}; wait for the run; wendy /api/tasks/mine; notifications',
    expected: 'A3/A6, DESIGN §5.10.5: run DONE/SUCCEEDED; a task on the invoice with origin AUTOMATION, rule {id,name}, title with the invoice number; in wendy\'s My tasks; wendy has TASK_ASSIGNED' }, async () => {
    const c = checks();
    const cu = await customerIn(w, 'WEST', { payment: false });
    const body = rt.rule({ trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [RG.WEST.id],
      actions: [rt.action.task({ title: 'Chase {{Invoice.Number}}', assignees: [rt.tok.user(U.wendy.id)] })] });
    const cr = await rt.automation.create(T.admin, body);
    if (!c.that('rule created', cr.status === 200 || cr.status === 201, brief(cr))) return done(c);
    const run = await rt.automation.run(T.admin, cr.json.id, { recordIds: [cu.invoice.id] });
    c.that('run queued 202', run.status === 202, brief(run));
    const fin = run.json?.runId ? await rt.waitForRun(T.admin, run.json.runId, { timeoutMs: 45000 }) : null;
    c.that('run finished', fin && !/FAILED/.test(fin.status), fin);
    const l = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id });
    const t = (l.json?.content || [])[0];
    c.that('task origin AUTOMATION + rule', t && t.origin === 'AUTOMATION' && t.rule && t.rule.id === cr.json.id && t.title === `Chase ${cu.invoice.invoiceNumber}`, t);
    c.that('in wendy /mine', t && ids(await rt.tasks.mine(T.wendy)).includes(t.id));
    c.that('wendy told', t && (await notesFor(T.wendy, t.id)).length === 1);
    return done(c, { evidence: `rule ${cr.json?.id} run ${run.json?.runId} ${fin && fin.status}`, codeRef: 'backend/src/main/java/com/geneinvoice/automation/action/CreateTaskHandler.java' });
  });

  // ------------------------------------------------------------------ assignee lifecycle, notification regions
  await tcase({ id: 'TAS-API-33', feature: 'Assignee deactivated', kind: 'API', prd: 'A6',
    title: 'Deleting a user who is a task assignee deactivates them instead; the task keeps the assignee, now unresolved; they cannot be newly assigned',
    steps: 'fresh staff X (MANAGE/ALL North); admin task on n2 invoice assigned USER X; admin DELETE /api/users/{X}; GET the task; admin creates another task naming USER X',
    expected: 'DESIGN §5.9.2 (UserController.pocReferenceCount counts assignees) / §8.5 TaskApiTest: DELETE -> {deleted:false, deactivated:true}; task assignee USER X unresolved true, people []; new assignment 400 on assignees' }, async () => {
    const c = checks();
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'gone', { grants: [{ regionId: RG.NORTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('gone'), assignees: [rt.tok.user(u.id)] });
    const d = await rt.api('DELETE', `/api/users/${u.id}`, { token: T.admin });
    c.that('deactivated, not deleted', d.ok && d.json && d.json.deleted === false && d.json.deactivated === true, brief(d));
    const a = (await getTask(T.admin, t.id)).json.assignees;
    c.that('assignee kept, unresolved', a.length === 1 && a[0].token.userId === u.id && a[0].unresolved === true && a[0].people.length === 0, a);
    const r = await rt.createTask(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('gone2'), assignees: [rt.tok.user(u.id)] });
    c.that('inactive user cannot be assigned (400)', r.status === 400, brief(r));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/user/UserController.java:280' });
  });

  await tcase({ id: 'TAS-API-34', feature: 'Task notifications by region', kind: 'API', prd: 'B1',
    title: 'Task notifications answer "which regions": asking for South hides a North task\'s notification',
    steps: 'admin creates a North task (n2) and a South task (s1) both assigned USER nora; nora GET /api/notifications?regionIds=SOUTH and ?regionIds=NORTH',
    expected: 'DESIGN §6.1 (notifications honour regionIds): SOUTH view has the South task\'s TASK_ASSIGNED only; NORTH view the North one only' }, async () => {
    const c = checks();
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: inv('n2').id, title: rt.uniq('nN'), assignees: [rt.tok.user(U.nora.id)] });
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: inv('s1').id, title: rt.uniq('nS'), assignees: [rt.tok.user(U.nora.id)] });
    const links = async (rid) => ((await rt.list(T.nora, '/api/notifications', {}, { regionIds: [rid] })).json?.content || []).map((n) => n.link);
    const s = await links(RG.SOUTH.id); const n = await links(RG.NORTH.id);
    c.that('SOUTH: South task only', s.includes(`/tasks/${ts.id}`) && !s.includes(`/tasks/${tn.id}`));
    c.that('NORTH: North task only', n.includes(`/tasks/${tn.id}`) && !n.includes(`/tasks/${ts.id}`));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/notification/NotificationController.java:49' });
  });

  return { regionTasks };
};
