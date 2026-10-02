// UI cases for A6 tasks (Flutter web through the semantics tree). Fixtures come from the API.
// Expected behaviour: PRD A6/B1/B3, DESIGN §7.3 (nav "My tasks" + badge), §7.7.2 (tasks feature), §6.12/§6.13.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customerIn, task, getTask, openAs, waitText, allText, fill, ids } = H;

const FE = {
  tab: 'frontend/lib/features/tasks/tasks_tab.dart',
  actions: 'frontend/lib/features/tasks/task_actions.dart',
  screen: 'frontend/lib/features/tasks/tasks_screen.dart',
  form: 'frontend/lib/features/tasks/task_form_dialog.dart',
  detail: 'frontend/lib/features/tasks/task_detail_screen.dart',
  providers: 'frontend/lib/features/tasks/task_providers.dart',
  chips: 'frontend/lib/features/assignees/recipient_chips_field.dart',
};

const TASKS_TAB = /(^|\n)Tasks$/;

function uiRunner(R) {
  /** Opens the app, runs body(app, c), always screenshots and closes; adds page/API errors as a no-crash check. */
  return async function ui(id, attempt, user, route, body, { allowStatuses = [] } = {}) {
    const c = checks();
    const app = await openAs(user, route);
    let shot = '';
    try {
      await body(app, c);
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 700));
    } finally {
      try { shot = await rt.shot(app.page, R.shotsDir, `${id}-${attempt}`); } catch { /* ignore */ }
      const errs = app.apiErrors.filter((x) => !allowStatuses.includes(x.status));
      c.that('no page exception (no crash)', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no unexpected API error', errs.length === 0, errs.slice(0, 5));
      await app.close();
    }
    return { c, shot, requests: app.requests };
  };
}
const result = (x, codeRef, evidence = '', extra = {}) => ({ ok: x.c.ok, actual: x.c.summary(), screenshot: x.shot, codeRef,
  evidence: `${evidence}${evidence ? '; ' : ''}requests=${JSON.stringify(x.requests.filter((r) => /\/api\/(tasks|assignees)/.test(r.url)).map((r) => `${r.method} ${r.url} ${r.status}`)).slice(0, 700)}`, ...extra });

/** A summary tile's value: the number node just under the tile's label. */
async function tileValue(page, label) {
  const nodes = await rt.semantics(page);
  const l = nodes.find((n) => n.text === label && n.y < 220);
  if (!l) return null;
  const v = nodes.find((n) => /^\d+$/.test(n.text || '') && Math.abs(n.y - l.y - 28) < 14 && n.x - 110 < l.x && l.x - n.x < 110 && n.x <= l.x + 5);
  return v ? v.text : null;
}

/** Waits for a node and returns its full text (label + text). */
async function textOf(page, m, t = 8000) {
  const n = await waitText(page, m, t);
  return n ? `${n.label || ''} ${n.text || ''}`.trim() : null;
}

async function main(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const ui = uiRunner(R);
  const T = w.tokens; const U = w.users; const RG = w.regions;

  // ---------------------------------------------------------------- record tabs
  await tcase({ id: 'TAS-UI-01', feature: 'Invoice Tasks tab', kind: 'UI', prd: 'A6',
    title: 'An invoice\'s Tasks tab lists its tasks, its badge counts the open ones, and a manager sees "New task"',
    steps: 'fresh North customer; admin creates 2 tasks on its invoice (one then DONE); admin opens /invoices/{id} and taps the Tasks tab',
    expected: 'DESIGN §7.7.2: tab "Tasks" with badge 1 (open); rows with both titles and statuses Open/Done; "New task" button; the tab asks /api/tasks?entityType=INVOICE&entityId=<id>' }, async (a) => {
    const cu = await customerIn(w, 'NORTH');
    const t1 = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('uiA') });
    const t2 = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('uiB') });
    rt.need(await rt.tasks.setStatus(T.admin, t2.id, 'DONE', t2.version), 'done');
    const x = await ui('TAS-UI-01', a, 'admin', `/invoices/${cu.invoice.id}`, async ({ page, requests }, c) => {
      const tab = await waitText(page, TASKS_TAB, 10000);
      c.that('Tasks tab with badge 1', tab && /^1\nTasks$/.test(tab.label || ''), tab && tab.label);
      await rt.tap(page, TASKS_TAB, { role: 'tab', wait: 3000 });
      c.that('row A', !!(await waitText(page, t1.title, 8000)));
      c.that('row B', !!(await waitText(page, t2.title, 3000)));
      const txt = await allText(page);
      c.that('statuses Open and Done', /\| Open \|/.test(txt) && /\| Done \|/.test(txt), txt.slice(0, 300));
      c.that('New task button', await rt.exists(page, 'New task', { role: 'button' }));
      c.that('tab asks by entityType/entityId', requests.some((r) => r.url.startsWith('/api/tasks?') && r.url.includes('entityType=INVOICE') && r.url.includes(`entityId=${cu.invoice.id}`)));
    });
    return result(x, FE.tab);
  });

  await tcase({ id: 'TAS-UI-02', feature: 'Create task in the UI', kind: 'UI', prd: 'A6',
    title: 'The New task dialog creates a task with title, due date and a role assignee chosen from the To-field model',
    steps: 'fresh North customer (Collection seat nora); admin /invoices/{id}?tab=tasks -> New task -> Title, Due date (picker text input +7d), tap "Collection POC · Nora North" chip -> Create task',
    expected: 'DESIGN §7.7.2 task_form_dialog: dialog offers role chips grouped "Customer level"/"Invoice level" with holders; POST /api/tasks 200; row appears; the task has the title, dueDate +7d, assignee ROLE COLLECTION_POC@CUSTOMER' }, async (a) => {
    const cu = await customerIn(w, 'NORTH');
    const title = rt.uniq('uiNew');
    const due = rt.day(7);
    const x = await ui('TAS-UI-02', a, 'admin', `/invoices/${cu.invoice.id}?tab=tasks`, async ({ page, requests }, c) => {
      await waitText(page, 'New task', 8000);
      await rt.tap(page, 'New task', { role: 'button', wait: 2500 });
      c.that('dialog title', !!(await waitText(page, /^New task • /, 5000)));
      c.that('groups Customer level / Invoice level', await rt.exists(page, 'Customer level') && await rt.exists(page, 'Invoice level'));
      await fill(page, 'Title *', title);
      try {
        await rt.tap(page, /Due date/, { wait: 1500 });
        await rt.tap(page, /Switch to input/, { wait: 800 });
        const [y, m, d] = due.split('-');
        await rt.fillField(page, /Enter Date|mm\/dd\/yyyy/i, `${m}/${d}/${y}`);
        await rt.tap(page, 'OK', { wait: 1200 });
      } catch (e) { c.that('due date picked', false, String(e.message).slice(0, 300)); }
      await rt.tap(page, /Collection POC · Nora North/, { wait: 1200 });
      c.that('chip added', !!(await waitText(page, /Collection POC · Nora North/, 3000)));
      await rt.tap(page, 'Create task', { role: 'button', wait: 3500 });
      c.that('POST /api/tasks 200', requests.some((r) => r.method === 'POST' && r.url === '/api/tasks' && r.status === 200));
      c.that('row appears', !!(await waitText(page, title, 8000)));
      const l = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id });
      const t = (l.json.content || []).find((x) => x.title === title);
      c.that('saved with dueDate and ROLE assignee', t && t.dueDate === due && t.assignees.length === 1 && t.assignees[0].token.role === 'COLLECTION_POC' && t.assignees[0].token.level === 'CUSTOMER', t && { dueDate: t.dueDate, assignees: t.assignees.map((z) => z.token) });
    });
    return result(x, FE.form);
  });

  await tcase({ id: 'TAS-UI-03', feature: 'Customer Tasks tab', kind: 'UI', prd: 'A6',
    title: 'A customer\'s Tasks tab gathers the tasks on the customer and on its invoices and payments; badge = open + in progress',
    steps: 'fresh North customer with invoice and payment; admin creates tasks on the customer, the invoice (then IN_PROGRESS) and the payment (then DONE); opens /customers/{id}',
    expected: 'DESIGN §5.9.5/§7.7.2 (customerScope): tab badge 2; the list shows all three titles with a Record column ("Invoice …", "Payment …"); asks /api/tasks?customerId=<id>' }, async (a) => {
    const cu = await customerIn(w, 'NORTH');
    const tc = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('uiC') });
    const ti = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('uiI') });
    const tp = await task(T.admin, { entityType: 'PAYMENT', entityId: cu.payment.id, title: rt.uniq('uiP') });
    rt.need(await rt.tasks.setStatus(T.admin, ti.id, 'IN_PROGRESS', ti.version), 'start');
    rt.need(await rt.tasks.setStatus(T.admin, tp.id, 'DONE', tp.version), 'done');
    const x = await ui('TAS-UI-03', a, 'admin', `/customers/${cu.id}`, async ({ page, requests }, c) => {
      const tab = await waitText(page, TASKS_TAB, 10000);
      c.that('badge 2', tab && /^2\nTasks$/.test(tab.label || ''), tab && tab.label);
      await rt.tap(page, TASKS_TAB, { role: 'tab', wait: 3000 });
      for (const t of [tc, ti, tp]) c.that(`row ${t.title}`, !!(await waitText(page, t.title, 6000)));
      c.that('Record column', await rt.exists(page, 'Record', { role: 'columnheader' }));
      const txt = await allText(page);
      c.that('record texts', /Invoice INV-/.test(txt) && /Payment/.test(txt), txt.slice(0, 200));
      c.that('asks by customerId', requests.some((r) => r.url.startsWith('/api/tasks?') && r.url.includes(`customerId=${cu.id}`)));
    });
    return result(x, FE.tab);
  });

  await tcase({ id: 'TAS-UI-04', feature: 'Payment Tasks tab', kind: 'UI', prd: 'A6',
    title: 'A payment\'s detail has a Tasks tab listing its tasks, with New task for a manager',
    steps: 'fresh North customer; admin creates a task on its payment; admin opens /payments/{id} and taps Tasks',
    expected: 'DESIGN §7.7.2: Tasks tab badge 1; the row; New task; asks entityType=PAYMENT' }, async (a) => {
    const cu = await customerIn(w, 'NORTH');
    const tp = await task(T.admin, { entityType: 'PAYMENT', entityId: cu.payment.id, title: rt.uniq('uiPay') });
    const x = await ui('TAS-UI-04', a, 'admin', `/payments/${cu.payment.id}`, async ({ page, requests }, c) => {
      const tab = await waitText(page, TASKS_TAB, 10000);
      c.that('badge 1', tab && /^1\nTasks$/.test(tab.label || ''), tab && tab.label);
      if (tab) await rt.tap(page, TASKS_TAB, { role: 'tab', wait: 3000 });
      c.that('row', !!(await waitText(page, tp.title, 6000)));
      c.that('New task', await rt.exists(page, 'New task', { role: 'button' }));
      c.that('asks entityType=PAYMENT', requests.some((r) => r.url.startsWith('/api/tasks?') && r.url.includes('entityType=PAYMENT')));
    });
    return result(x, FE.tab);
  });

  // ---------------------------------------------------------------- my tasks
  await tcase({ id: 'TAS-UI-05', feature: 'My tasks page', kind: 'UI', prd: 'A6',
    title: '"My tasks" shows tasks assigned by name and through a role I hold, not others\'; tiles and nav badge count them',
    steps: 'fresh staff X (MANAGE/ALL West) holding the Collection seat of a fresh West customer; admin creates A (USER X, on w2 invoice), B (ROLE COLLECTION_POC@CUSTOMER on the customer), C (USER wendy); X opens /my-tasks',
    expected: 'DESIGN §5.9.5/§7.7.2: header "Tasks assigned to you, by name or through a role you hold"; rows A and B, not C; tile Tasks 2; request /api/tasks/mine; badge from /api/tasks/mine/count = 2 (screenshot: badge on the My tasks nav icon)' }, async (a) => {
    const u = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'uimt', { grants: [{ regionId: RG.WEST.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const cu = await customerIn(w, 'WEST', { invoice: false, payment: false, poc: u.id });
    const A = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.w2[0].id, title: rt.uniq('mineA'), assignees: [rt.tok.user(u.id)] });
    const B = await task(T.admin, { entityType: 'CUSTOMER', entityId: cu.id, title: rt.uniq('mineB'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const C = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.w2[0].id, title: rt.uniq('mineC'), assignees: [rt.tok.user(U.wendy.id)] });
    const x = await ui('TAS-UI-05', a, u.username, '/my-tasks', async ({ page, requests }, c) => {
      c.that('header', !!(await waitText(page, 'Tasks assigned to you, by name or through a role you hold', 8000)));
      c.that('row A', !!(await waitText(page, A.title, 6000)));
      c.that('row B (role)', !!(await waitText(page, B.title, 3000)));
      c.that('no row C', !(await rt.exists(page, C.title)));
      const tiles = { Tasks: await tileValue(page, 'Tasks'), Open: await tileValue(page, 'Open') };
      c.that('tiles Tasks 2, Open 2', tiles.Tasks === '2' && tiles.Open === '2', tiles);
      c.that('asks /api/tasks/mine', requests.some((r) => r.url.startsWith('/api/tasks/mine?')));
      const cnt = requests.find((r) => r.url === '/api/tasks/mine/count');
      c.that('badge source /api/tasks/mine/count asked', !!cnt);
      const mc = await rt.tasks.mineCount(await rt.login(u.username));
      c.that('mine/count = 2', mc.json.count === 2, mc.json);
    });
    return result(x, FE.screen);
  });

  // ---------------------------------------------------------------- task detail and rights
  await tcase({ id: 'TAS-UI-06', feature: 'Assignee works a task in the UI', kind: 'UI', prd: 'A6',
    title: 'An assignee without manage rights sees Start / Mark done but no Edit or Cancel, and can mark it done',
    steps: 'admin creates a task on s1 invoice (South) assigned USER nora (READ South); nora opens /tasks/{id}; taps "Mark done"',
    expected: 'DESIGN §5.9.2 / §7.7.2 (buttons per server flags): Start and Mark done shown; Edit and Cancel task absent; after the tap status chip Done and the task is DONE on the server' }, async (a) => {
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.s1[0].id, title: rt.uniq('uiAsg'), assignees: [rt.tok.user(U.nora.id)] });
    const x = await ui('TAS-UI-06', a, 'nora', `/tasks/${t.id}`, async ({ page, requests }, c) => {
      c.that('title shown', !!(await waitText(page, t.title, 8000)));
      c.that('Start', await rt.exists(page, 'Start', { role: 'button' }));
      c.that('Mark done', await rt.exists(page, 'Mark done', { role: 'button' }));
      c.that('no Edit', !(await rt.exists(page, 'Edit', { role: 'button' })));
      c.that('no Cancel task', !(await rt.exists(page, 'Cancel task', { role: 'button' })));
      const countsBefore = requests.filter((r) => r.url === '/api/tasks/mine/count').length;
      await rt.tap(page, 'Mark done', { role: 'button', wait: 3000 });
      c.that('My tasks badge re-asked after the move', requests.filter((r) => r.url === '/api/tasks/mine/count').length > countsBefore);
      c.that('status POST 200', requests.some((r) => r.method === 'POST' && r.url === `/api/tasks/${t.id}/status` && r.status === 200));
      c.that('chip Done', !!(await waitText(page, 'Done', 5000)));
      c.that('server DONE', (await getTask(T.admin, t.id)).json.status === 'DONE');
      c.that('Reopen offered now', await rt.exists(page, 'Reopen', { role: 'button' }));
    });
    return result(x, FE.detail);
  });

  await tcase({ id: 'TAS-UI-07', feature: 'Edit task in the UI', kind: 'UI', prd: 'A6',
    title: 'A manager edits a task\'s title from its detail page',
    steps: 'admin creates a task on n2 invoice; opens /tasks/{id}; Edit -> change Title -> Save',
    expected: 'DESIGN §7.7.2: "Edit task" dialog prefilled; PATCH /api/tasks/{id} 200; the page shows the new title; server has it' }, async (a) => {
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.n2[0].id, title: rt.uniq('uiEdit') });
    const nt = rt.uniq('uiEdited');
    const x = await ui('TAS-UI-07', a, 'admin', `/tasks/${t.id}`, async ({ page, requests }, c) => {
      await waitText(page, t.title, 8000);
      await rt.tap(page, 'Edit', { role: 'button', wait: 2500 });
      c.that('Edit task dialog', !!(await waitText(page, 'Edit task', 4000)));
      await fill(page, 'Title *', nt);
      await rt.tap(page, 'Save', { role: 'button', wait: 3000 });
      c.that('PATCH 200', requests.some((r) => r.method === 'PATCH' && r.url === `/api/tasks/${t.id}` && r.status === 200));
      c.that('new title shown', !!(await waitText(page, nt, 6000)));
      c.that('server title', (await getTask(T.admin, t.id)).json.title === nt);
    });
    return result(x, FE.form);
  });

  await tcase({ id: 'TAS-UI-08', feature: 'Role assignee holders on the detail', kind: 'UI', prd: 'A6',
    title: 'A task assigned to a role held by several people names all holders, the primary marked',
    steps: 'admin creates a task on s2 invoice assigned ROLE COLLECTION_POC@CUSTOMER (sam primary + nora); opens /tasks/{id}',
    expected: 'DESIGN §7.7.2 _AssigneeTile (A4: primary first): "Collection POC (customer)" with "Sam South (primary), Nora North"' }, async (a) => {
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.s2[0].id, title: rt.uniq('uiRole'), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const x = await ui('TAS-UI-08', a, 'admin', `/tasks/${t.id}`, async ({ page }, c) => {
      await waitText(page, t.title, 8000);
      const n = await textOf(page, /Collection POC \(customer\)/, 5000);
      c.that('role row', !!n, n);
      c.that('holders with primary', !!(await waitText(page, /Sam South \(primary\), Nora North/, 3000)), (await allText(page)).slice(0, 600));
    });
    return result(x, FE.detail);
  });

  await tcase({ id: 'TAS-UI-09', feature: 'Role holders in list rows (TAS-R1-1 re-test)', kind: 'UI', prd: 'A6',
    title: 'A list row\'s "Assigned to" cell never calls a held role unheld ("nobody") on /my-tasks, /tasks, the Tasks tab or a phone card',
    steps: 'admin creates a task on s2 invoice assigned ROLE COLLECTION_POC@CUSTOMER (held by sam primary and nora); nora opens /my-tasks, then /tasks, then /invoices/{s2 inv}?tab=tasks and reads the row\'s Assigned-to cell (tooltip is part of its semantics label); then /my-tasks at 400px width (TaskCard)',
    expected: 'TAS-R1-1 fix (FIXLOG-frontend E2E r1 F2): list rows describe roles briefly (people [], unresolved false), so the tooltip leaves such a role out or names its holders; it never says "nobody". If holders are named, both Sam South and Nora North. DESIGN §6.12 AssigneeDto.unresolved=false means someone holds it' }, async (a) => {
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.s2[0].id, title: rt.uniq('uiTip'), dueDate: rt.day(-6), assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] });
    const cells = {};
    const readCell = async (page, where, c) => {
      const hit = await waitText(page, t.title, 10000);
      const nodes = await rt.semantics(page);
      const row = nodes.find((n) => n.text === t.title || n.label === t.title) || hit;
      const cell = row && nodes.find((n) => n.role === 'cell' && Math.abs(n.y - row.y) < 8 && /Collection POC \(customer\)/.test(n.text || n.label || ''));
      const txt = cell ? `${cell.label || ''}|${cell.text || ''}` : '(cell not found)';
      cells[where] = txt;
      c.that(`${where}: cell found`, !!cell, txt);
      c.that(`${where}: no "nobody" for a held role`, cell && !/nobody/i.test(txt), txt);
      const named = /Collection POC \(customer\): /.test(txt);
      c.that(`${where}: holders left out, or both named`, cell && (!named || (/Sam South/.test(txt) && /Nora North/.test(txt))), txt);
    };
    const x = await ui('TAS-UI-09', a, 'nora', '/my-tasks', async ({ page }, c) => {
      await readCell(page, '/my-tasks', c);
      await rt.go(page, '/tasks', 4000);
      await readCell(page, '/tasks', c);
      await rt.go(page, `/invoices/${w.invoices.s2[0].id}?tab=tasks`, 5000);
      await readCell(page, 'invoice Tasks tab', c);
      const phone = await openAs('nora', '/my-tasks', { width: 400, height: 860 });
      try {
        const hit = await waitText(phone.page, new RegExp(t.title), 10000);
        const txt = (await allText(phone.page));
        cells.phone = txt.slice(0, 400);
        c.that('phone: card shown', !!hit);
        c.that('phone: no "nobody" on screen', !/nobody/i.test(txt), txt.slice(0, 400));
        await rt.shot(phone.page, R.shotsDir, `TAS-UI-09-phone-${a}`);
      } finally { await phone.close(); }
    });
    const l = await rt.tasks.mine(T.nora, { filter: [`id:eq:${t.id}`] });
    const row = (l.json.content || [])[0];
    return result(x, 'frontend/lib/features/tasks/tasks_screen.dart:216', `cells: ${JSON.stringify(cells)}; GET /api/tasks/mine row assignees: ${JSON.stringify(row && row.assignees)}`, { severity: 'low' });
  });

  await tcase({ id: 'TAS-UI-22', feature: 'Unheld role / gone person on the task detail', kind: 'UI', prd: 'A6',
    title: 'The task detail says "Nobody holds this role now" for an unheld role and marks a deactivated person, while list rows make no holder claim',
    steps: 'fresh South customer (sam Collection POC, no Customer Success POC) with invoice; admin creates a staff user X (E2E_REGIONAL, MANAGE South); admin creates a task on the invoice assigned [ROLE CUSTOMER_SUCCESS_POC@CUSTOMER, USER X]; admin DELETE /api/users/X (deactivates); admin opens /tasks/{id}, then /tasks',
    expected: 'DESIGN §6.12 AssigneeDto unresolved=true for both; task_detail_screen _AssigneeTile: "Nobody holds this role now" for the role and "Can no longer see this task" for the person; the /tasks row does not name holders for the brief role and does not crash' }, async (a) => {
    const cu = await customerIn(w, 'SOUTH', { payment: false, prefix: 'unh' });
    const x0 = await rt.createStaff(T.admin, 'E2E_REGIONAL', 'gone', { grants: [{ regionId: RG.SOUTH.id, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('uiUnheld'), dueDate: rt.day(-8), assignees: [rt.tok.role('CUSTOMER_SUCCESS_POC', 'CUSTOMER'), rt.tok.user(x0.id)] });
    const del = await rt.api('DELETE', `/api/users/${x0.id}`, { token: T.admin });
    const d = await getTask(T.admin, t.id);
    const x = await ui('TAS-UI-22', a, 'admin', `/tasks/${t.id}`, async ({ page }, c) => {
      await waitText(page, t.title, 8000);
      c.that('role says nobody holds it', !!(await waitText(page, /Nobody holds this role now/, 5000)), (await allText(page)).slice(0, 600));
      c.that('gone person marked', !!(await waitText(page, /Can no longer see this task/, 3000)), (await allText(page)).slice(0, 600));
      await rt.go(page, '/tasks', 4000);
      c.that('row listed', !!(await waitText(page, t.title, 8000)));
      const nodes = await rt.semantics(page);
      const row = nodes.find((n) => n.text === t.title);
      const cell = row && nodes.find((n) => n.role === 'cell' && Math.abs(n.y - row.y) < 8 && /Success POC/.test(`${n.label || ''} ${n.text || ''}`));
      const txt = cell ? `${cell.label || ''}|${cell.text || ''}` : '(cell not found)';
      c.that('row cell has no false holder claim for the role', cell && !/Customer Success POC \(customer\): [A-Z]/.test(txt), txt);
    });
    return result(x, FE.detail, `DELETE user -> ${del.status} ${String(del.text).slice(0, 120)}; GET /api/tasks/${t.id} assignees: ${JSON.stringify((d.json || {}).assignees)}`);
  });

  // ---------------------------------------------------------------- regions
  await tcase({ id: 'TAS-UI-10', feature: 'Tasks list by region (single-region user)', kind: 'UI', prd: 'B1',
    title: 'A user holding only West sees only West tasks on /tasks',
    steps: 'admin creates a West task and a North task (both due -5d so they sort first); wendy opens /tasks',
    expected: 'B1: West task listed; North task absent; no region selector (one region)' }, async (a) => {
    const tw = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.w1[0].id, title: rt.uniq('uiW'), dueDate: rt.day(-5) });
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.n1[0].id, title: rt.uniq('uiN'), dueDate: rt.day(-5) });
    const x = await ui('TAS-UI-10', a, 'wendy', '/tasks', async ({ page }, c) => {
      c.that('West task listed', !!(await waitText(page, tw.title, 8000)));
      c.that('North task absent', !(await rt.exists(page, tn.title)));
      c.that('no region selector', !(await rt.regionSelector(page)));
    });
    return result(x, FE.screen);
  });

  await tcase({ id: 'TAS-UI-11', feature: 'Region selector on tasks', kind: 'UI', prd: 'B1',
    title: 'Narrowing the region selector to South narrows the tasks list and its request to South',
    steps: 'admin creates a North and a South task (due -5d); nora opens /tasks; region selector: untick NORTH',
    expected: 'DESIGN §6.0/§7 (view scope): request /api/tasks?...regionIds=<South>; South task listed; North task gone; tiles recount' }, async (a) => {
    const ts = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.s1[0].id, title: rt.uniq('uiS'), dueDate: rt.day(-5) });
    const tn = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.n2[0].id, title: rt.uniq('uiN2'), dueDate: rt.day(-5) });
    const x = await ui('TAS-UI-11', a, 'nora', '/tasks', async ({ page, requests }, c) => {
      c.that('both before', !!(await waitText(page, ts.title, 8000)) && !!(await waitText(page, tn.title, 3000)));
      await rt.chooseRegions(page, [/^NORTH/]);
      await page.waitForTimeout(2500);
      c.that('request regionIds=South', requests.some((r) => r.url.startsWith('/api/tasks?') && new RegExp(`regionIds=${RG.SOUTH.id}(&|$)`).test(r.url)), requests.filter((r) => r.url.startsWith('/api/tasks')).map((r) => r.url).slice(-3));
      c.that('summary narrowed too', requests.some((r) => r.url.startsWith('/api/tasks/summary') && r.url.includes(`regionIds=${RG.SOUTH.id}`)));
      c.that('South listed', !!(await waitText(page, ts.title, 6000)));
      c.that('North gone', !(await rt.exists(page, tn.title)));
    });
    return result(x, FE.screen);
  });

  await tcase({ id: 'TAS-UI-12', feature: 'READ-only region in the UI', kind: 'UI', prd: 'B1',
    title: 'In a region the user only reads, the Tasks tab shows tasks but offers no New task and no Cancel',
    steps: 'admin creates a task on s1 invoice (unassigned); nora (READ South) opens /invoices/{s1 inv}?tab=tasks',
    expected: 'DESIGN §7.5 canCreateTaskIn(region): no "New task"; the row has no Start / Mark done / Cancel task (not assignee, no manage); Open only' }, async (a) => {
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: w.invoices.s1[0].id, title: rt.uniq('uiRead'), dueDate: rt.day(-7) });
    const x = await ui('TAS-UI-12', a, 'nora', `/invoices/${w.invoices.s1[0].id}?tab=tasks`, async ({ page }, c) => {
      c.that('row shown', !!(await waitText(page, t.title, 8000)));
      c.that('no New task', !(await rt.exists(page, 'New task', { role: 'button' })));
      const nodes = await rt.semantics(page);
      const row = nodes.find((n) => n.text === t.title);
      const btns = row ? nodes.filter((n) => n.role === 'button' && Math.abs(n.y - row.y) < 6).map((n) => n.text || n.label) : [];
      c.that('row actions = Open only', btns.length === 1 && /Open/.test(btns[0]), btns);
    });
    return result(x, FE.tab);
  });

  // ---------------------------------------------------------------- notifications
  await tcase({ id: 'TAS-UI-13', feature: 'Task notification in the UI', kind: 'UI', prd: 'A6',
    title: 'An assignee finds "Task assigned: <title>" on the Notifications page',
    steps: 'sam creates a task on n2 invoice assigned USER nora; nora opens /notifications',
    expected: 'DESIGN §5.9.2: a TASK_ASSIGNED notification "Task assigned: <title>" with the invoice label' }, async (a) => {
    const t = await task(T.sam, { entityType: 'INVOICE', entityId: w.invoices.n2[0].id, title: rt.uniq('uiNote'), assignees: [rt.tok.user(U.nora.id)] });
    const x = await ui('TAS-UI-13', a, 'nora', '/notifications', async ({ page }, c) => {
      c.that('notification shown', !!(await waitText(page, new RegExp(`Task assigned: ${t.title}`), 8000)), (await allText(page)).slice(0, 400));
    });
    return result(x, 'frontend/lib/features/notifications');
  });

  // ---------------------------------------------------------------- person picker
  await tcase({ id: 'TAS-UI-14', feature: 'Person picker by region', kind: 'UI', prd: 'B1',
    title: 'The New task "Person…" picker offers only staff of the record\'s region; picking one assigns them',
    steps: 'fresh North customer; admin /invoices/{id}?tab=tasks -> New task -> Person… (lists) -> pick Nora North -> Title -> Create task',
    expected: 'DESIGN §6.13 (SC-18): Nora, Sam, Bob listed; Wendy West absent; asks /api/assignees/people?entityType=INVOICE&entityId=<id>; saved task has USER nora' }, async (a) => {
    const cu = await customerIn(w, 'NORTH', { payment: false });
    const title = rt.uniq('uiPer');
    const x = await ui('TAS-UI-14', a, 'admin', `/invoices/${cu.invoice.id}?tab=tasks`, async ({ page, requests }, c) => {
      await waitText(page, 'New task', 8000);
      await rt.tap(page, 'New task', { role: 'button', wait: 2500 });
      await rt.tap(page, /^Person/, { wait: 2500 });
      c.that('Add a person dialog', !!(await waitText(page, 'Add a person', 4000)));
      const txt = await allText(page);
      c.that('North staff listed', /Nora North/.test(txt) && /Sam South/.test(txt) && /Bob Book/.test(txt), txt.slice(0, 500));
      c.that('Wendy absent', !/Wendy West/.test(txt));
      c.that('people asked with the record', requests.some((r) => r.url.startsWith('/api/assignees/people') && r.url.includes(`entityId=${cu.invoice.id}`)));
      await rt.tap(page, /^Nora North\n@nora/, { wait: 1500 });
      await fill(page, 'Title *', title);
      await rt.tap(page, 'Create task', { role: 'button', wait: 3500 });
      const l = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id });
      const t = (l.json.content || []).find((z) => z.title === title);
      c.that('saved with USER nora', t && t.assignees.length === 1 && t.assignees[0].token.userId === U.nora.id, t && t.assignees);
    });
    return result(x, FE.chips);
  });

  await tcase({ id: 'TAS-UI-15', feature: 'Assignee error in the dialog', kind: 'UI', prd: 'A6',
    title: 'Picking someone who cannot see tasks shows the server\'s reason under Assignees and saves nothing',
    steps: 'fresh North customer; admin New task -> Title -> Person… -> Nina Notask (role without TASK_*) -> Create task',
    expected: 'DESIGN §6.12 (400 fieldErrors.assignees) / §7.7.2: dialog stays open with "Nina Notask cannot see tasks here" under Assignees; no task written' }, async (a) => {
    const cu = await customerIn(w, 'NORTH', { payment: false });
    const x = await ui('TAS-UI-15', a, 'admin', `/invoices/${cu.invoice.id}?tab=tasks`, async ({ page }, c) => {
      await waitText(page, 'New task', 8000);
      await rt.tap(page, 'New task', { role: 'button', wait: 2500 });
      await fill(page, 'Title *', rt.uniq('uiNina'));
      await rt.tap(page, /^Person/, { wait: 2500 });
      await rt.tap(page, /^Nina Notask\n@nina/, { wait: 1500 });
      await rt.tap(page, 'Create task', { role: 'button', wait: 3500 });
      c.that('error under Assignees', !!(await waitText(page, /Nina Notask cannot see tasks here/, 5000)), (await allText(page)).slice(0, 500));
      c.that('dialog still open', await rt.exists(page, 'Create task', { role: 'button' }));
      const l = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id });
      c.that('nothing written', l.json.totalElements === 0, l.json.totalElements);
    }, { allowStatuses: [400] });
    return result(x, FE.form);
  });

  await tcase({ id: 'TAS-UI-16', feature: 'Title required', kind: 'UI', prd: 'A6',
    title: 'Creating a task without a title is stopped in the dialog',
    steps: 'admin /invoices/{n2 inv}?tab=tasks -> New task -> Create task with an empty title',
    expected: 'task_form_dialog validator: "Say what needs doing"; no POST' }, async (a) => {
    const x = await ui('TAS-UI-16', a, 'admin', `/invoices/${w.invoices.n2[0].id}?tab=tasks`, async ({ page, requests }, c) => {
      await waitText(page, 'New task', 8000);
      await rt.tap(page, 'New task', { role: 'button', wait: 2500 });
      await rt.tap(page, 'Create task', { role: 'button', wait: 1500 });
      c.that('validation message', !!(await waitText(page, /Say what needs doing/, 3000)));
      c.that('no POST', !requests.some((r) => r.method === 'POST' && r.url === '/api/tasks'));
    });
    return result(x, FE.form);
  });

  // ---------------------------------------------------------------- moves from lists
  await tcase({ id: 'TAS-UI-17', feature: 'Cancel from a list row', kind: 'UI', prd: 'A6',
    title: 'A manager cancels a task from its row after confirming',
    steps: 'fresh North customer; admin task on its invoice; admin /invoices/{id}?tab=tasks -> row "Cancel task" -> dialog "Cancel this task?" -> Cancel task',
    expected: 'DESIGN §7.7.2 (moveTask asks before cancelling): confirm dialog; POST status CANCELLED 200; row shows Cancelled; server CANCELLED' }, async (a) => {
    const cu = await customerIn(w, 'NORTH', { payment: false });
    const t = await task(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id, title: rt.uniq('uiCx') });
    const x = await ui('TAS-UI-17', a, 'admin', `/invoices/${cu.invoice.id}?tab=tasks`, async ({ page, requests }, c) => {
      await waitText(page, t.title, 8000);
      await rt.tap(page, 'Cancel task', { role: 'button', wait: 2000 });
      c.that('confirm dialog', !!(await waitText(page, 'Cancel this task?', 3000)));
      const btns = (await rt.semantics(page)).filter((n) => n.role === 'button' && (n.text === 'Cancel task' || n.label === 'Cancel task'));
      const last = btns[btns.length - 1];
      if (last) await rt.clickAt(page, last.x, last.y, 3000);
      c.that('POST status 200', requests.some((r) => r.method === 'POST' && r.url === `/api/tasks/${t.id}/status` && r.status === 200));
      c.that('row Cancelled', !!(await waitText(page, 'Cancelled', 5000)));
      c.that('server CANCELLED', (await getTask(T.admin, t.id)).json.status === 'CANCELLED');
    });
    return result(x, FE.actions);
  });

  await tcase({ id: 'TAS-UI-18', feature: '"Assigned to me" filter', kind: 'UI', prd: 'A6',
    title: 'On /tasks the "Assigned to me" quick filter asks for tasks assigned to the user (directly or by role)',
    steps: 'nora opens /tasks and taps the "Assigned to me" quick filter',
    expected: 'DESIGN §7.7.2: request carries filter=assigneeUserId:eq:<nora id>; every row is one assigned to her (count equals /api/tasks/mine totalElements)' }, async (a) => {
    const x = await ui('TAS-UI-18', a, 'nora', '/tasks', async ({ page, requests }, c) => {
      await waitText(page, 'Assigned to me', 8000);
      await rt.tap(page, 'Assigned to me', { wait: 3000 });
      c.that('request filter assigneeUserId:eq:nora', requests.some((r) => r.url.startsWith('/api/tasks?') && r.url.includes(`assigneeUserId:eq:${U.nora.id}`)), requests.filter((r) => r.url.startsWith('/api/tasks?')).map((r) => r.url).slice(-2));
      const mine = await rt.tasks.mine(T.nora);
      const txt = await allText(page);
      const m = txt.match(/1–\d+ of (\d+)/);
      c.that('count equals My tasks total', m && Number(m[1]) === mine.json.totalElements, { shown: m && m[1], mine: mine.json.totalElements });
    });
    return result(x, FE.screen);
  });

  // ---------------------------------------------------------------- no TASK_VIEW
  await tcase({ id: 'TAS-UI-19', feature: 'No task privileges', kind: 'UI', prd: 'A6',
    title: 'A user without TASK_VIEW gets no Tasks tab on records and no task requests',
    steps: 'nina (role without TASK_*) opens /invoices/{n1 inv}; then /my-tasks',
    expected: 'DESIGN §7.7.2 (tab presence depends only on TASK_VIEW) / §7.3.4 (myTasksCountProvider asks nothing without taskView): no Tasks tab; no /api/tasks request at all; /my-tasks is not offered (no task list shown)' }, async (a) => {
    const x = await ui('TAS-UI-19', a, 'nina', `/invoices/${w.invoices.n1[0].id}`, async ({ page, requests }, c) => {
      await waitText(page, w.invoices.n1[0].invoiceNumber, 8000);
      c.that('no Tasks tab', !(await rt.exists(page, TASKS_TAB, { role: 'tab' })), (await rt.semantics(page)).filter((n) => n.role === 'tab').map((n) => n.label));
      await rt.go(page, '/my-tasks', 4000);
      c.that('no task list on /my-tasks', !(await rt.exists(page, 'Tasks assigned to you, by name or through a role you hold')));
      c.that('no /api/tasks request', !requests.some((r) => r.url.startsWith('/api/tasks')), requests.filter((r) => r.url.startsWith('/api/tasks')).map((r) => r.url));
    });
    return result(x, FE.actions);
  });
}

/** As-of UI cases: run after asof-cases.run (the world is aged and copy 1 changed "now"). */
async function asOf(w, R, copies, only) {
  const tcase = H.caseRunner(R, only);
  const ui = uiRunner(R);
  const T = w.tokens;
  const then = rt.day(-2);
  const cp = copies[0];

  await tcase({ id: 'TAS-UI-20', feature: 'Task detail as of a date', kind: 'UI', prd: 'B3',
    title: 'Viewing a task as of an earlier day shows it as it was then, read only',
    steps: 'task created 10 days ago (aged) OPEN "<title>", assigned nora; since renamed "Renamed later", reassigned to sam and DONE. admin opens /tasks/{id} and picks the as-of chip date = today-2',
    expected: 'DESIGN D2/§7.4: banner "Viewing … — read only"; old title and status Open; no Start/Mark done/Edit/Cancel; request GET /api/tasks/{id}?asOf=<date>' }, async (a) => {
    const x = await ui('TAS-UI-20', a, 'admin', `/tasks/${cp.tStatus.id}`, async ({ page, requests }, c) => {
      c.that('live: renamed + Done', !!(await waitText(page, 'Renamed later', 8000)) && !!(await waitText(page, 'Done', 3000)));
      const chip = await rt.chooseAsOf(page, then);
      c.that('chip shows as-of', /As of/.test(chip || ''), chip);
      await page.waitForTimeout(2500);
      // The banner text ("Viewing … — read only") is painted but not in the semantics tree; its "Back to today" link is.
      c.that('historical banner (Back to today)', !!(await waitText(page, 'Back to today', 5000)), (await allText(page)).slice(0, 300));
      c.that('old title', !!(await waitText(page, cp.tStatus.title, 5000)));
      c.that('status Open', await rt.exists(page, 'Open'));
      for (const b of ['Start', 'Mark done', 'Edit', 'Cancel task', 'Reopen']) c.that(`no ${b}`, !(await rt.exists(page, b, { role: 'button' })));
      c.that('asked with asOf', requests.some((r) => r.url.startsWith(`/api/tasks/${cp.tStatus.id}`) && r.url.includes(`asOf=${then}`)));
    });
    return result(x, FE.detail);
  });

  await tcase({ id: 'TAS-UI-21', feature: 'Tasks tab as of a date', kind: 'UI', prd: 'B3',
    title: 'A record\'s Tasks tab as of an earlier day lists the tasks of that day without "New task"',
    steps: 'same invoice: tasks then = the aged task (Open); a task added today. admin opens /invoices/{id}?tab=tasks, picks as-of today-2',
    expected: 'B3 / DESIGN §6.1 (tasks GETs AWARE): list asks with asOf; shows the old title (Open) and not today\'s task; no New task; badge from /api/tasks/count?asOf' }, async (a) => {
    const late = cp.after && cp.after.late;
    const x = await ui('TAS-UI-21', a, 'admin', `/invoices/${cp.cu.invoice.id}?tab=tasks`, async ({ page, requests }, c) => {
      await waitText(page, 'New task', 8000);
      await rt.chooseAsOf(page, then);
      await page.waitForTimeout(3000);
      c.that('old title listed', !!(await waitText(page, cp.tStatus.title, 6000)), (await allText(page)).slice(0, 400));
      c.that('today\'s task absent', !late || !(await rt.exists(page, late.title)));
      c.that('no New task', !(await rt.exists(page, 'New task', { role: 'button' })));
      c.that('list asked with asOf', requests.some((r) => r.url.startsWith('/api/tasks?') && r.url.includes(`asOf=${then}`)));
      c.that('count asked with asOf', requests.some((r) => r.url.startsWith('/api/tasks/count') && r.url.includes(`asOf=${then}`)));
    });
    return result(x, FE.tab);
  });
}

module.exports = { main, asOf };
