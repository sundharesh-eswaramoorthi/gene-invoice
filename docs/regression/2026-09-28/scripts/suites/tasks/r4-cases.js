// Round 4 extensions: one step around the r3 fixes.
//   r3 F1 (RGR-R3-1) changed the shared PlaceholderButton, which is also the button beside a task action's Title and
//   Description in the rule editor -> TAS-UI-24 (insert at the caret, typing continues after the token, saved rule
//   renders into the task).
//   A4 "a role held by several people resolves to the primary" vs the TRIAGE default "a role used as an assignee
//   goes to every holder" -> TAS-API-39 (both on one automation-made task).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, ids, customerIn } = H;
const money = (n) => `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

async function api(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users; const RG = w.regions;

  await tcase({ id: 'TAS-API-39', feature: 'Rule task: role assignee vs role placeholder', kind: 'API', prd: 'A3',
    title: 'A rule task assigned to a customer-level role held by two people goes to both, while {{Customer.CollectionPoc}} in its title names only the primary',
    steps: 'seedWorld s2 (South) Collection POC = sam (primary) + nora; admin MANUAL INVOICE rule, regionIds [SOUTH], condition id:eq:<s2 inv1000>, CREATE_TASK title "Call {{Customer.CollectionPoc}} re {{Invoice.Number}}", description "{{Customer.Name}} owes {{Invoice.Balance}}", assignees [COLLECTION_POC@CUSTOMER]; run {recordIds:[inv]}; wait; GET the task; sam and nora /api/tasks/mine; notifications',
    expected: 'PRD A3/A4 + TRIAGE default: run SUCCEEDED; one task with origin AUTOMATION; title "Call <sam full name> re <inv#>" (placeholder -> primary); description "<customer> owes <balance>"; assignee people = [sam (primary), nora]; both list it in My tasks and both have TASK_ASSIGNED' }, async () => {
    const c = checks();
    const cust = w.customers.s2; const inv = w.invoices.s2[0];
    const sam = (await rt.api('GET', `/api/users/${U.sam.id}`, { token: T.admin })).json;
    const invNow = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: T.admin })).json;
    const body = rt.rule({ trigger: { kind: 'MANUAL', entityType: 'INVOICE' }, regionIds: [RG.SOUTH.id],
      condition: { op: 'AND', children: [{ filter: `id:eq:${inv.id}` }] },
      actions: [rt.action.task({ title: 'Call {{Customer.CollectionPoc}} re {{Invoice.Number}}', description: '{{Customer.Name}} owes {{Invoice.Balance}}',
        assignees: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')] })] });
    const cr = await rt.automation.create(T.admin, body);
    if (!c.that('rule created', cr.status === 200 || cr.status === 201, brief(cr))) return done(c);
    const run = await rt.automation.run(T.admin, cr.json.id, { recordIds: [inv.id] });
    c.that('run queued 202', run.status === 202, brief(run));
    const fin = run.json?.runId ? await rt.waitForRun(T.admin, run.json.runId, { timeoutMs: 45000 }) : null;
    c.that('run SUCCEEDED', fin && fin.status === 'SUCCEEDED', fin && { status: fin.status, counts: fin.counts || fin.results });
    const l = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: inv.id, filter: [`ruleId:eq:${cr.json.id}`] });
    const all = l.json?.content || [];
    c.that('exactly one task from the rule', all.length === 1, all.map((x) => [x.id, x.title]));
    const t = all[0] ? (await rt.api('GET', `/api/tasks/${all[0].id}`, { token: T.admin })).json : null;
    const samName = sam?.fullName || sam?.name;
    c.that('title names the primary only', t && t.title === `Call ${samName} re ${invNow.invoiceNumber}`, t && t.title);
    c.that('description rendered', t && t.description === `${cust.name} owes ${money(invNow.balance ?? invNow.balanceDue ?? invNow.outstanding)}`, t && { d: t.description, inv: { balance: invNow.balance } });
    c.that('origin AUTOMATION with rule', t && t.origin === 'AUTOMATION' && t.rule?.id === cr.json.id, t && { origin: t.origin, rule: t.rule });
    const people = (t?.assignees?.[0]?.people || []);
    c.that('role assignee resolves to both holders, sam primary', people.length === 2 && people.some((p) => p.userId === U.sam.id && p.primary) && people.some((p) => p.userId === U.nora.id && !p.primary), people);
    c.that('sam lists it in My tasks', t && ids(await rt.tasks.mine(T.sam)).includes(t.id));
    c.that('nora lists it in My tasks', t && ids(await rt.tasks.mine(T.nora)).includes(t.id));
    c.that('sam told', t && (await H.notesFor(T.sam, t.id)).length === 1);
    c.that('nora told', t && (await H.notesFor(T.nora, t.id)).length === 1);
    return done(c, { evidence: `rule ${cr.json?.id} run ${run.json?.runId} ${fin && fin.status} task ${t && t.id}`, codeRef: 'backend/src/main/java/com/geneinvoice/automation/action/CreateTaskHandler.java' });
  });
}

async function ui(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users;

  async function settle(page) {
    const v = page.viewportSize();
    await page.setViewportSize({ width: v.width, height: v.height + 1 });
    await page.waitForTimeout(500);
    await page.setViewportSize(v);
    await page.waitForTimeout(700);
    await rt.enableSemantics(page);
  }
  /** Verified typing (same technique as the automation suite). */
  async function fill(page, label, value, nth = 0) {
    for (let i = 0; i < 4; i++) {
      if (!(await rt.exists(page, 'Dismiss'))) await settle(page);
      const n = await rt.find(page, label, { role: 'textbox', nth });
      await page.mouse.click(n.x, n.y);
      await page.waitForTimeout(600);
      const focused = await page.evaluate(() => { const a = document.activeElement; return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA'); });
      if (!focused) { await page.waitForTimeout(800); await rt.enableSemantics(page); continue; }
      if (n.text) { await page.keyboard.press('Meta+A'); await page.keyboard.press('Backspace'); }
      await page.keyboard.type(String(value), { delay: 20 });
      await page.waitForTimeout(400);
      const after = await rt.find(page, label, { role: 'textbox', nth }).catch(() => null);
      if (after && after.text === value) return true;
    }
    return false;
  }
  const fieldText = async (page, label) => ((await rt.semantics(page)).find((n) => n.role === 'textbox' && n.label === label) || {}).text;

  await tcase({ id: 'TAS-UI-24', feature: 'Rule editor: task Title/Description placeholders', kind: 'UI', prd: 'A4',
    title: 'In a rule\'s task action (semantics on), Insert placeholder puts the token at the caret of Title and Description, the field keeps the focus so typing straight on appends, and the saved rule renders it into the task',
    steps: 'admin #/automation/new (Invoice), semantics tree on; Name; Runs "Only when run by hand"; Add action "Create a task"; Title "Chase " + Insert placeholder (beside Title) -> Name; read document.activeElement; type " now" at once; Description "Owes " + Insert placeholder (beside Description) -> Balance; type "!" at once; Create rule; POST /rules/{id}/run {recordIds:[fresh North invoice 800]}; GET the task',
    expected: 'TRIAGE r4 F1 (TSK-R4-1) + r3 F1 + PRD A4: after each pick the field (INPUT/TEXTAREA) is document.activeElement with the caret after the token; Title "Chase {{Customer.Name}} now"; Description "Owes {{Invoice.Balance}}!"; saved CREATE_TASK has those texts; the task created by the run is titled "Chase <customer> now" with description "Owes ₹800.00!"' }, async (attempt) => {
    const c = checks();
    const cu = await customerIn(w, 'NORTH', { payment: false, prefix: `ph${attempt}` });
    const name = rt.uniq('UI-ph-task');
    const app = await rt.openApp({ token: T.admin, height: 1600 });
    const { page } = app;
    let shotPath = '';
    try {
      await rt.go(page, '/automation/new', 4000);
      c.that('name typed', await fill(page, 'Name *', name));
      await rt.tap(page, /^Runs\n/, { wait: 1500 });
      await rt.press(page, 'Only when run by hand');
      await settle(page);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a task');
      await settle(page);
      c.that('title typed', await fill(page, 'Title *', 'Chase '));
      let phs = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder' || n.label === 'Insert placeholder').sort((p, q) => p.y - q.y);
      c.that('two placeholder buttons on the task card', phs.length >= 2, phs.length);
      await rt.clickAt(page, phs[0].x, phs[0].y, 1500);
      await rt.press(page, 'Name', { role: 'menuitem', wait: 1500 });
      // Harness (r4): an unfocused textbox's semantics value reads '' here, so the card header (which echoes the
      // title) is read instead.
      const hdr = async () => ((await rt.screenText(page)).find((x) => x.startsWith('Create a task')) || '');
      const t1 = await hdr();
      c.that('Title after insert = "Chase {{Customer.Name}}" (card header)', t1 === 'Create a task: Chase {{Customer.Name}}', t1);
      // r5 (TSK-R4-1 fixed): with the semantics tree on, the field must have the focus back after the pick, so the
      // next keystrokes go straight after the token without clicking back in.
      const act = () => page.evaluate(() => { const a = document.activeElement; return a ? { tag: a.tagName, value: a.value ?? null, sel: [a.selectionStart ?? null, a.selectionEnd ?? null] } : null; });
      const f1 = await act();
      c.that('semantics on: after the pick the Title <input> has the focus, caret after the token', f1 && f1.tag === 'INPUT' && f1.value === 'Chase {{Customer.Name}}' && f1.sel[0] === 23 && f1.sel[1] === 23, f1);
      await page.keyboard.type(' now', { delay: 30 });
      await page.waitForTimeout(600);
      const t2 = await hdr();
      c.that('typing straight on appends after the token (card header)', t2 === 'Create a task: Chase {{Customer.Name}} now', t2);
      shotPath = await rt.shot(page, R.shotsDir, `TAS-UI-24-${attempt}-title`);
      // Harness (r4): the rule's own Basics "Description" is textbox #0; the task action's is #1.
      c.that('description typed', await fill(page, 'Description', 'Owes ', 1));
      phs = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder' || n.label === 'Insert placeholder').sort((p, q) => p.y - q.y);
      await rt.clickAt(page, phs[1].x, phs[1].y, 1500);
      await rt.press(page, 'Balance', { role: 'menuitem', wait: 1500 });
      const f2 = await act();
      c.that('semantics on: after the pick the Description <textarea> has the focus, caret after the token', f2 && f2.tag === 'TEXTAREA' && f2.value === 'Owes {{Invoice.Balance}}' && f2.sel[0] === 24 && f2.sel[1] === 24, f2);
      await page.keyboard.type('!', { delay: 30 });
      await page.waitForTimeout(600);
      const f3 = await act();
      c.that('typing "!" straight on lands after the token', f3 && f3.value === 'Owes {{Invoice.Balance}}!', f3);
      c.that('Title unchanged by the second insert (card header)', (await hdr()) === 'Create a task: Chase {{Customer.Name}} now', await hdr());
      shotPath = await rt.shot(page, R.shotsDir, `TAS-UI-24-${attempt}-card`);
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const hash = await page.evaluate(() => location.hash);
      shotPath = await rt.shot(page, R.shotsDir, `TAS-UI-24-${attempt}-saved`);
      const l = await rt.list(T.admin, '/api/automation/rules', { filter: [`name:eq:${name}`] });
      const saved = (l.json?.content || [])[0];
      c.that('rule saved, page is its detail', saved && new RegExp(`#/automation/${saved.id}`).test(hash), { hash, saved: saved && saved.id, apiErrors: app.apiErrors });
      if (saved) {
        const full = (await rt.automation.get(T.admin, saved.id)).json;
        const act = full?.actions?.[0];
        c.that('saved action texts', act?.type === 'CREATE_TASK' && act.title === 'Chase {{Customer.Name}} now' && act.description === 'Owes {{Invoice.Balance}}!', act && { title: act.title, description: act.description });
        const run = await rt.automation.run(T.admin, saved.id, { recordIds: [cu.invoice.id] });
        const fin = run.json?.runId ? await rt.waitForRun(T.admin, run.json.runId, { timeoutMs: 45000 }) : null;
        c.that('run SUCCEEDED', fin && fin.status === 'SUCCEEDED', fin && fin.status);
        const tl = await rt.tasks.list(T.admin, { entityType: 'INVOICE', entityId: cu.invoice.id });
        const tk = (tl.json?.content || [])[0];
        const td = tk ? (await rt.api('GET', `/api/tasks/${tk.id}`, { token: T.admin })).json : null;
        c.that('task rendered', td && td.title === `Chase ${cu.name} now` && td.description === `Owes ${money(800)}!`, td && { title: td.title, description: td.description });
        await rt.automation.disable(T.admin, saved.id);
      }
    } finally {
      await app.close();
    }
    return done(c, { screenshot: shotPath, codeRef: 'frontend/lib/features/automation/placeholder_button.dart' });
  });
  await tcase({ id: 'TAS-UI-25', feature: 'Rule editor: task Title/Description placeholders', kind: 'UI', prd: 'A4',
    title: 'After Insert placeholder in a task action\'s Title or Description, the field keeps the focus so typing carries on after the token',
    steps: 'coordinates read from a semantics-enabled page; then a fresh browser page WITHOUT the semantics tree (a sighted mouse user): #/automation/new; click Add action -> Create a task; click Title, type "Chase "; click the Title\'s Insert placeholder button; click "Name"; read document.activeElement; type " now"; same for Description ("Owes " + Balance, then "!"); screenshot',
    expected: 'r3 F1 intent (DESIGN/TRIAGE r3 F1: the placeholder "goes in at the caret ... and leaves the caret there"; placeholder_button.dart doc of focusNode: "Given back its focus once the placeholder is in, so typing carries on where it went"): after the pick the field is focused (the hidden <input>/<textarea> is document.activeElement) with the caret after the token, so " now" gives "Chase {{Customer.Name}} now"' }, async (attempt) => {
    const c = checks();
    const { chromium } = require('playwright-core');
    const out = {};
    {
      const app = await rt.openApp({ token: T.admin, height: 1600 });
      const { page } = app;
      try {
        await rt.go(page, '/automation/new', 4000);
        out.add = await rt.find(page, /Add action/);
        await page.mouse.click(out.add.x, out.add.y); await page.waitForTimeout(1500);
        out.create = await rt.find(page, 'Create a task');
        await page.mouse.click(out.create.x, out.create.y); await page.waitForTimeout(2000);
        await rt.enableSemantics(page);
        out.title = await rt.find(page, 'Title *', { role: 'textbox' });
        out.desc = await rt.find(page, 'Description', { role: 'textbox', nth: 1 });
        const phs = (await rt.semantics(page)).filter((x) => x.text === 'Insert placeholder' || x.label === 'Insert placeholder').sort((p, q) => p.y - q.y);
        out.ph = phs[0]; out.ph2 = phs[1];
        await page.mouse.click(out.ph.x, out.ph.y); await page.waitForTimeout(1500);
        out.name = await rt.find(page, 'Name', { role: 'menuitem' });
        await page.mouse.click(out.name.x, out.name.y); await page.waitForTimeout(1500); await rt.enableSemantics(page);
        await page.mouse.click(out.ph2.x, out.ph2.y); await page.waitForTimeout(1500);
        out.balance = await rt.find(page, 'Balance', { role: 'menuitem' });
      } finally { await app.close(); }
    }
    const browser = await chromium.launch({ executablePath: rt.chromiumPath(), headless: true });
    let shotPath = '';
    try {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 1600 } });
      await ctx.addInitScript((t) => localStorage.setItem('flutter.gene_invoice_token', JSON.stringify(t)), T.admin);
      const page = await ctx.newPage();
      await page.goto(process.env.E2E_WEB);
      await rt.waitForFlutter(page, 6000);
      await page.evaluate(() => { location.hash = '#/automation/new'; }); await page.waitForTimeout(4000);
      const act = () => page.evaluate(() => { const a = document.activeElement; return a ? { tag: a.tagName, value: a.value ?? null, sel: [a.selectionStart ?? null, a.selectionEnd ?? null] } : null; });
      const click = async (p, w = 1500) => { await page.mouse.click(p.x, p.y); await page.waitForTimeout(w); };
      await click(out.add); await click(out.create, 2000);
      await click(out.title, 800);
      await page.keyboard.type('Chase ', { delay: 30 }); await page.waitForTimeout(500);
      const a0 = await act();
      c.that('precondition: Title focused holding "Chase "', a0 && a0.tag === 'INPUT' && a0.value === 'Chase ', a0);
      await click(out.ph); await click(out.name, 1800);
      const a1 = await act();
      c.that('after the pick the Title field has the focus, caret after the token', a1 && a1.tag === 'INPUT' && a1.value === 'Chase {{Customer.Name}}' && a1.sel[0] === 23 && a1.sel[1] === 23, a1);
      await page.keyboard.type(' now', { delay: 30 }); await page.waitForTimeout(800);
      const a2 = await act();
      c.that('typing " now" lands after the token', a2 && a2.value === 'Chase {{Customer.Name}} now', a2);
      shotPath = await rt.shot(page, R.shotsDir, `TAS-UI-25-${attempt}-title`);
      await click(out.desc, 800);
      await page.keyboard.type('Owes ', { delay: 30 }); await page.waitForTimeout(500);
      await click(out.ph2); await click(out.balance, 1800);
      const a3 = await act();
      c.that('after the pick the Description field has the focus, caret after the token', a3 && a3.tag === 'TEXTAREA' && a3.value === 'Owes {{Invoice.Balance}}' && a3.sel[0] === 24 && a3.sel[1] === 24, a3);
      await page.keyboard.type('!', { delay: 30 }); await page.waitForTimeout(800);
      const a4 = await act();
      c.that('typing "!" lands after the token', a4 && a4.value === 'Owes {{Invoice.Balance}}!', a4);
      shotPath = await rt.shot(page, R.shotsDir, `TAS-UI-25-${attempt}-card`);
    } finally { await browser.close(); }
    return done(c, { screenshot: shotPath, severity: 'low',
      evidence: 'document.activeElement after the menu pick; screenshot shows the token inserted but no caret/focus in the field',
      codeRef: 'frontend/lib/features/automation/action_cards.dart:357 (PlaceholderButton gets no focusNode); frontend/lib/features/automation/placeholder_button.dart insertPlaceholder: focusNode?.requestFocus() is a no-op without one' });
  });
}

module.exports = { api, ui };
