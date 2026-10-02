// UI cases (Flutter web through the semantics tree): rules list, rule editor (conditions, actions, placeholders,
// schedule), preview, run now, runs and effects, enable/disable/delete, My tasks, approvals, non-owner view.
const rt = require('../../lib.js');
const H = require('./helpers.js');

module.exports = async function uiCases(ctx) {
  const { R, w } = ctx;
  const U = w.users; const N = w.regions.NORTH.id;
  const t = () => ctx.t;
  let shotN = 0;
  const shot = (page, id) => rt.shot(page, R.shotsDir, `${id}-${++shotN}`);
  const text = async (page) => (await rt.screenText(page)).join(' | ');

  /** Opens the app as `who` ('admin' or a seeded user), runs fn(app), always closes. */
  async function withApp(who, fn, { height = 1600 } = {}) {
    const token = who === 'admin' ? t() : (w.tokens[who] = await rt.login(who));
    const app = await rt.openApp({ token, height });
    try { return await fn(app); } finally { await app.close(); }
  }
  const K = (meta, fn) => H.kase(R, { kind: 'UI', feature: 'Automation UI', ...meta }, async (attempt) => {
    let lastShot = '';
    try {
      const res = await fn(attempt, (s) => { lastShot = s; });
      return { screenshot: lastShot, ...res };
    } catch (e) {
      return { status: 'FAIL', actual: `UI step failed: ${String(e && e.message || e).slice(0, 1200)}`, screenshot: lastShot };
    }
  });

  // Fixture shared by the editor cases: a North customer (Collection POC nora) with one invoice.
  const fx = await H.mkCustomer(t(), { regionId: N, collector: U.nora.id, amounts: [4321] });
  const fxInv = fx.invoices[0];
  const ui = {}; // ids created through the UI, for the following cases

  /**
   * Makes Flutter rebuild its semantics overlay at the current layout. After a card is added the overlay can keep
   * old positions, and a click at a field's centre can then land on a stale node (e.g. Cancel). A one-pixel viewport
   * resize forces a new frame and fresh positions.
   */
  async function settle(page) {
    const v = page.viewportSize();
    await page.setViewportSize({ width: v.width, height: v.height + 1 });
    await page.waitForTimeout(500);
    await page.setViewportSize(v);
    await page.waitForTimeout(700);
    await rt.enableSemantics(page);
  }
  /**
   * Types into a text field, verified. Flutter sometimes drops the first click after a layout change; a
   * select-all + Backspace sent while no field has focus reaches the page (and the editor's leave guard), so
   * the field is cleared only once it is known to hold text and to have focus.
   */
  async function fill(page, label, value) {
    for (let i = 0; i < 4; i++) {
      if (!(await rt.exists(page, 'Dismiss'))) await settle(page); // not inside a dialog
      const n = await rt.find(page, label, { role: 'textbox' });
      await page.mouse.click(n.x, n.y);
      await page.waitForTimeout(600);
      const focused = await page.evaluate(() => { const a = document.activeElement; return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA'); });
      if (!focused) { await page.waitForTimeout(800); await rt.enableSemantics(page); continue; }
      if (n.text) { await page.keyboard.press('Meta+A'); await page.keyboard.press('Backspace'); }
      await page.keyboard.type(String(value), { delay: 20 });
      await page.waitForTimeout(400);
      const after = await rt.find(page, label, { role: 'textbox' }).catch(() => null);
      if (after && after.text === value) return;
    }
    throw new Error(`could not type into ${label}`);
  }
  async function addCondition(page, column, op, value) {
    await settle(page);
    await rt.tap(page, 'Add condition', { wait: 1500 });
    await rt.press(page, /^Column/);
    await rt.press(page, column);
    await rt.press(page, /^Condition/);
    await rt.press(page, op);
    await fill(page, 'Value', value);
    await rt.tap(page, 'Apply', { wait: 1500 });
    await settle(page);
  }
  async function addTaskAction(page, title) {
    await rt.tap(page, /Add action/, { wait: 1500 });
    await rt.press(page, 'Create a task');
    await settle(page);
    await fill(page, 'Title *', title);
    await settle(page);
  }
  async function findRuleByName(name) {
    const l = await rt.list(t(), '/api/automation/rules', { filter: [`name:eq:${name}`] });
    return (l.json?.content || [])[0] || null;
  }

  await K({ id: 'AUT-UI-01', prd: 'A5', title: 'The Automation page lists rules with status, trigger and owner, summary tiles and the consumer health card',
    steps: 'create rule "0-UI-list-<tag>" by API; admin opens /automation',
    expected: 'DESIGN §7.7.3 rules_screen: tiles Active/Disabled/Suspended/Failed runs (24 h) with the /rules/summary numbers; the rule row with its status chip, "Invoice · Only when run by hand", "System Administrator"; health card for AUTOMATION_ADMIN' },
  async (a, setShot) => {
    const name = `0-UI-list-${rt.uniq('r')}`;
    const rule = await H.mustRule(t(), { name, trigger: H.manual(), condition: H.and(`id:eq:${fxInv.id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const summary = (await rt.api('GET', '/api/automation/rules/summary', { token: t() })).json;
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation', 5000);
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-01'));
      const tilesOk = ['Active', 'Disabled', 'Suspended', 'Failed runs (24 h)'].every((x) => s.includes(x)) && s.includes(`Active | Disabled | Suspended | Failed runs (24 h) | ${summary.active} | ${summary.disabled} | ${summary.suspended} | ${summary.failedRuns24h}`);
      const rowOk = s.includes(name) && /Invoice · Only when run by hand/.test(s) && s.includes('System Administrator');
      const health = /Rules are being run|waiting/.test(s);
      return { ok: tilesOk && rowOk && health, actual: { summary, tilesOk, rowOk, health, screen: H.short(s, 900) } };
    });
  });

  await K({ id: 'AUT-UI-02', prd: 'A2', title: 'Create a rule in the editor: condition from the list filters, a task action with placeholders and a role assignee; Preview shows the match and the rendered title',
    steps: `admin /automation/new; Name; Add condition "Invoice # is ${fxInv.invoiceNumber}"; Add action "Create a task", Title "Chase {{Invoice.Number}} for {{Customer.Name}}", assignee chip "Collection POC (each customer's)"; Runs = "Only when run by hand"; tap Preview; tap Create rule`,
    expected: 'PRD A2/A3/A4 + DESIGN §7.7.3: preview "1 invoice matches" and "Title: Chase INV-… for <customer>"; after Create the page is /automation/<id>; the API rule has condition invoiceNumber:eq:<num>, CREATE_TASK with that title and assignee COLLECTION_POC@CUSTOMER, trigger MANUAL' },
  async (a, setShot) => {
    const name = rt.uniq('UI-created');
    return withApp('admin', async ({ page, apiErrors }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await rt.tap(page, /^Runs\n/, { wait: 1500 });
      await rt.press(page, 'Only when run by hand');
      await settle(page);
      await addCondition(page, 'Invoice #', 'is', fxInv.invoiceNumber);
      await addTaskAction(page, 'Chase {{Invoice.Number}} for {{Customer.Name}}');
      await rt.tap(page, "Collection POC (each customer's)", { wait: 1200 });
      await settle(page);
      await rt.tap(page, 'Preview', { role: 'button', wait: 4000 });
      const pv = await text(page);
      setShot(await shot(page, 'AUT-UI-02'));
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const hash = await page.evaluate(() => location.hash);
      setShot(await shot(page, 'AUT-UI-02'));
      const saved = await findRuleByName(name);
      const full = saved ? (await rt.automation.get(t(), saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); ui.rule = full; }
      const act = full?.actions?.[0];
      const leaf = JSON.stringify(full?.condition || {});
      const wantTitle = `Title: Chase ${fxInv.invoiceNumber} for ${fx.c.name}`;
      const ok = pv.includes('1 invoice matches') && pv.includes(wantTitle) && full && new RegExp(`#/automation/${full.id}`).test(hash)
        && leaf.includes(`invoiceNumber:eq:${fxInv.invoiceNumber}`) && full.trigger.kind === 'MANUAL' && act?.type === 'CREATE_TASK'
        && act.title === 'Chase {{Invoice.Number}} for {{Customer.Name}}' && (act.assignees || []).some((x) => x.type === 'ROLE' && x.role === 'COLLECTION_POC' && x.level === 'CUSTOMER');
      return { ok, actual: { previewShown: pv.includes('1 invoice matches'), renderedTitleShown: pv.includes(wantTitle), hash, rule: full && { id: full.id, trigger: full.trigger.kind, condition: full.condition, action: act && { type: act.type, title: act.title, assignees: act.assignees } }, apiErrors } };
    });
  });

  await K({ id: 'AUT-UI-03', prd: 'A5', title: 'Run now from the rule page queues a run, and its detail shows what it did (task with a link)',
    steps: 'open the rule of AUT-UI-02; tap Run now; in the dialog keep "Every invoice that matches" + "Now", tap Run now; tap Open run; wait',
    expected: 'DESIGN §7.7.3 run_now_dialog / run_detail_dialog: "Run #<id> Queued", then the run detail "Succeeded", "1 matched · 1 done", an effect "Task #<id>"; the API task title is "Chase INV-… for <customer>" and it resolves to nora' },
  async (a, setShot) => {
    if (!ui.rule) return { status: 'BLOCKED', actual: 'no rule from AUT-UI-02' };
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${ui.rule.id}`, 4500);
      await rt.tap(page, 'Run now', { wait: 2000 });
      const dlg = await text(page);
      setShot(await shot(page, 'AUT-UI-03'));
      await rt.tap(page, 'Run now', { wait: 3000 });
      const queued = await text(page);
      await rt.tap(page, 'Open run', { wait: 7000 });
      const detail = await text(page);
      setShot(await shot(page, 'AUT-UI-03'));
      const runId = Number((queued.match(/Run #(\d+)/) || [])[1]);
      const tasks = await H.tasksOfRule(t(), ui.rule.id);
      const task = tasks[0] ? await H.taskDetail(t(), tasks[0].id) : null;
      ui.task = task;
      const people = (task?.assignees || []).flatMap((x) => x.people.map((p) => p.userId));
      const ok = /Queued/.test(queued) && runId > 0 && /Succeeded/.test(detail) && /1 matched · 1 done/.test(detail) && /Task #\d+/.test(detail)
        && task?.title === `Chase ${fxInv.invoiceNumber} for ${fx.c.name}` && people.includes(U.nora.id);
      return { ok, actual: { dialog: H.short(dlg.slice(dlg.indexOf('Run "')), 300), queued: H.short(queued.slice(queued.indexOf('Run #')), 200), detail: H.short(detail.slice(detail.indexOf('Run #')), 400), task: task && [task.id, task.title, people] } };
    });
  });

  await K({ id: 'AUT-UI-04', prd: 'A5', title: 'The Runs tab lists the rule\'s runs, and a run opens with its effects',
    steps: 'rule of AUT-UI-02, tab Runs',
    expected: 'DESIGN §7.7.3 rule_runs_tab: a row "#<runId>" with status Succeeded, started by System Administrator, results 1 matched / 1 done' },
  async (a, setShot) => {
    if (!ui.rule) return { status: 'BLOCKED', actual: 'no rule from AUT-UI-02' };
    const runs = (await rt.automation.runs(t(), ui.rule.id)).json?.content || [];
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${ui.rule.id}?tab=runs`, 5000);
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-04'));
      const r0 = runs[0];
      return { ok: !!r0 && s.includes(`#${r0.id}`) && /Succeeded/.test(s) && /System Administrator/.test(s), actual: { apiRuns: runs.map((r) => [r.id, r.status]), screen: H.short(s.slice(s.indexOf('Runs')), 700) } };
    });
  });

  await K({ id: 'AUT-UI-05', prd: 'A5', title: 'Disable and Enable from the rule page switch the rule off and on',
    steps: 'rule of AUT-UI-02; tap Disable; tap Enable',
    expected: 'DESIGN §7.7.3: status chip Disabled then Active; API status DISABLED then ACTIVE' },
  async (a, setShot) => {
    if (!ui.rule) return { status: 'BLOCKED', actual: 'no rule from AUT-UI-02' };
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${ui.rule.id}`, 4500);
      await rt.tap(page, 'Disable', { role: 'button', wait: 3000 });
      const off = await text(page);
      const apiOff = (await rt.automation.get(t(), ui.rule.id)).json?.status;
      setShot(await shot(page, 'AUT-UI-05'));
      await rt.tap(page, 'Enable', { role: 'button', wait: 3000 });
      const on = await text(page);
      const apiOn = (await rt.automation.get(t(), ui.rule.id)).json?.status;
      setShot(await shot(page, 'AUT-UI-05'));
      return { ok: apiOff === 'DISABLED' && apiOn === 'ACTIVE' && /\bDisabled\b/.test(off) && /\bActive\b/.test(on) && /Enable/.test(off) && /Disable/.test(on),
        actual: { apiOff, apiOn, offScreen: H.short(off.slice(0, 300)), onScreen: H.short(on.slice(0, 300)) } };
    });
  });

  await K({ id: 'AUT-UI-06', prd: 'A5', title: 'Editing a rule and saving it changes it (new version)',
    steps: 'rule of AUT-UI-02; change Name to "<name> v2"; tap Save changes',
    expected: 'DESIGN §7.7.3: snackbar "Rule saved"; API name "<name> v2" and version + 1' },
  async (a, setShot) => {
    if (!ui.rule) return { status: 'BLOCKED', actual: 'no rule from AUT-UI-02' };
    const before = (await rt.automation.get(t(), ui.rule.id)).json;
    const newName = `${before.name.replace(/ v2$/, '')} v2`;
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${ui.rule.id}`, 4500);
      await fill(page, 'Name *', newName);
      await rt.tap(page, 'Save changes', { wait: 3000 });
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-06'));
      const after = (await rt.automation.get(t(), ui.rule.id)).json;
      return { ok: after.name === newName && after.version === before.version + 1, actual: { before: [before.name, before.version], after: [after.name, after.version], snackbar: /Rule saved/.test(s) } };
    });
  });

  await K({ id: 'AUT-UI-07', prd: 'A1', title: 'A daily schedule can be set up in the editor (time, zone) and the page shows its next run',
    steps: 'admin /automation/new; Name; Runs = "Every day" (default At 09:00); Add action "Create a task" title "Daily {{Invoice.Number}}"; Create rule',
    expected: 'PRD A1 + DESIGN §7.7.3: API trigger SCHEDULE_DAILY time 09:00 with a zone; the rule page shows "Every day…" and "Next run …"' },
  async (a, setShot) => {
    const name = rt.uniq('UI-daily');
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await rt.tap(page, /^Runs\n/, { wait: 1500 });
      await rt.press(page, 'Every day');
      await settle(page);
      const form = await text(page);
      await addCondition(page, 'Invoice #', 'is', fxInv.invoiceNumber);
      await addTaskAction(page, 'Daily {{Invoice.Number}}');
      setShot(await shot(page, 'AUT-UI-07'));
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-07'));
      const saved = await findRuleByName(name);
      const full = saved ? (await rt.automation.get(t(), saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); await rt.automation.disable(t(), full.id); }
      return { ok: full?.trigger?.kind === 'SCHEDULE_DAILY' && full.trigger.time === '09:00' && !!full.trigger.zone && !!full.nextRunAt && /Next run/.test(s) && /At/.test(form),
        actual: { trigger: full?.trigger, nextRunAt: full?.nextRunAt, pageShowsNextRun: /Next run/.test(s), header: H.short(s.slice(0, 400)) } };
    });
  });

  await K({ id: 'AUT-UI-08', prd: 'A2', title: '"Any of these" makes the conditions an OR',
    steps: 'admin /automation/new; Name; Runs "Only when run by hand"; Add condition Total greater than 100000; Add condition "Invoice #" is <fixture>; tap "Any of these"; task action; Preview; Create rule',
    expected: 'PRD A2 (OR): saved condition {op: OR, children: 2 leaves}; preview counts the fixture invoice among the matches' },
  async (a, setShot) => {
    const name = rt.uniq('UI-or');
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await rt.tap(page, /^Runs\n/, { wait: 1500 });
      await rt.press(page, 'Only when run by hand');
      await settle(page);
      await addCondition(page, 'Total', 'greater than', '100000');
      await addCondition(page, 'Invoice #', 'is', fxInv.invoiceNumber);
      await rt.tap(page, 'Any of these', { wait: 1200 });
      await settle(page);
      await addTaskAction(page, 'Or {{Invoice.Number}}');
      await rt.tap(page, 'Preview', { role: 'button', wait: 4000 });
      const pv = await text(page);
      setShot(await shot(page, 'AUT-UI-08'));
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const saved = await findRuleByName(name);
      const full = saved ? (await rt.automation.get(t(), saved.id)).json : null;
      if (full) ctx.cleanup.push(full.id);
      const c = full?.condition;
      const flat = JSON.stringify(c || {});
      return { ok: c?.op === 'OR' && flat.includes('total:gt:100000') && flat.includes(`invoiceNumber:eq:${fxInv.invoiceNumber}`) && /\d+ invoices? match/.test(pv),
        actual: { condition: c, preview: (pv.match(/\d+ invoices? match(es)?/) || [])[0] } };
    });
  });

  await K({ id: 'AUT-UI-09', prd: 'A4', title: 'The placeholder button offers both levels and inserts the token into the field',
    steps: 'admin /automation/new (Invoice); Add action "Create a task"; tap "Insert placeholder" beside Title; read the menu; pick "Balance"',
    expected: 'PRD A4 "offered for both levels": menu has Customer items (Name, Collection POC…) and Invoice items (Number, Balance…); the task card then reads "{{Invoice.Balance}}"' },
  async (a, setShot) => {
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation/new', 4000);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a task');
      await rt.tap(page, 'Insert placeholder', { wait: 1500 });
      const items = (await rt.semantics(page)).filter((n) => n.role === 'menuitem').map((n) => n.label || n.text);
      setShot(await shot(page, 'AUT-UI-09'));
      await rt.press(page, 'Balance', { wait: 1500 });
      const s = await text(page);
      const ok = ['Name', 'Collection POC', 'Number', 'Balance', 'Due date'].every((x) => items.includes(x)) && /\{\{Invoice\.Balance\}\}/.test(s);
      return { ok, actual: { menu: items, inserted: /\{\{Invoice\.Balance\}\}/.test(s) } };
    });
  });

  await K({ id: 'AUT-UI-10', prd: 'A3', title: 'An email action is composed in its dialog (To the customer, subject with placeholders) and saved',
    steps: 'admin /automation/new; Name; Runs "Only when run by hand"; Add action "Send an email"; Edit email…; tick "Customer emails"; Subject "Invoice {{Invoice.Number}} for {{Customer.Name}}"; Done; Create rule',
    expected: 'DESIGN §7.7.3 email_action_dialog: API rule has SEND_EMAIL to [CUSTOMER] with that subject; the page warns that email delivery is not configured (README §6)' },
  async (a, setShot) => {
    const name = rt.uniq('UI-email');
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await rt.tap(page, /^Runs\n/, { wait: 1500 });
      await rt.press(page, 'Only when run by hand');
      await settle(page);
      await addCondition(page, 'Invoice #', 'is', fxInv.invoiceNumber);
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Send an email');
      await settle(page);
      await rt.tap(page, /Edit email/, { wait: 2500 });
      await rt.tap(page, 'Customer emails', { wait: 1000 });
      await fill(page, 'Subject *', 'Invoice {{Invoice.Number}} for {{Customer.Name}}');
      setShot(await shot(page, 'AUT-UI-10'));
      await rt.tap(page, 'Done', { wait: 1500 });
      await settle(page);
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-10'));
      const saved = await findRuleByName(name);
      const full = saved ? (await rt.automation.get(t(), saved.id)).json : null;
      if (full) { ctx.cleanup.push(full.id); ui.emailRule = full; }
      const act = full?.actions?.[0];
      return { ok: act?.type === 'SEND_EMAIL' && (act.to || []).some((x) => x.type === 'CUSTOMER') && act.subject === 'Invoice {{Invoice.Number}} for {{Customer.Name}}',
        actual: { action: act && { type: act.type, to: act.to, subject: act.subject, from: act.from }, warnings: full?.warnings, deliveryWarningShown: /not configured|not connected/i.test(s) } };
    });
  });

  await K({ id: 'AUT-UI-11', prd: 'A4', title: 'An unknown placeholder is refused with the server\'s message and nothing is saved',
    steps: 'admin /automation/new; Name; Add action "Create a task" with Title "Hello {{Foo.Bar}}"; Create rule',
    expected: 'DESIGN §5.10.4: 400 actions[0].title shown on the page (mentions {{Foo.Bar}}); no rule with that name exists' },
  async (a, setShot) => {
    const name = rt.uniq('UI-bad');
    return withApp('admin', async ({ page, apiErrors }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await addTaskAction(page, 'Hello {{Foo.Bar}}');
      await rt.tap(page, 'Create rule', { wait: 3000 });
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-11'));
      const saved = await findRuleByName(name);
      if (saved) ctx.cleanup.push(saved.id);
      return { ok: !saved && /Foo\.Bar/.test(s.replace(/Hello \{\{Foo\.Bar\}\}/g, '')) && apiErrors.some((e) => e.status === 400), actual: { saved: !!saved, errorShown: H.short((s.match(/[^|]*Foo\.Bar[^|]*/g) || []).join(' / '), 300), apiErrors } };
    });
  });

  await K({ id: 'AUT-UI-12', prd: 'A6', title: 'The task an automation made shows in the assignee\'s My tasks',
    steps: 'nora (Collection POC of the fixture customer) opens /my-tasks',
    expected: 'PRD A6 "somewhere people see their work": the task "Chase INV-… for <customer>" from AUT-UI-03 is listed' },
  async (a, setShot) => {
    if (!ui.task) return { status: 'BLOCKED', actual: 'no task from AUT-UI-03' };
    return withApp('nora', async ({ page }) => {
      await rt.go(page, '/my-tasks', 5000);
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-12'));
      return { ok: s.includes(ui.task.title), actual: { want: ui.task.title, screen: H.short(s, 700) } };
    });
  });

  await K({ id: 'AUT-UI-13', prd: 'A5', title: 'Someone who is not the owner (and not an administrator) sees the rule read-only, with no Run now, Disable or Delete',
    steps: 'nora (AUTOMATION_MANAGE, not owner, no AUTOMATION_ADMIN) opens the admin-owned rule of AUT-UI-02',
    expected: 'DESIGN §5.10.7/§7.7.3: rule visible ("Runs as System Administrator"), no Run now / Disable / Delete buttons, Save disabled' },
  async (a, setShot) => {
    if (!ui.rule) return { status: 'BLOCKED', actual: 'no rule from AUT-UI-02' };
    return withApp('nora', async ({ page }) => {
      await rt.go(page, `/automation/${ui.rule.id}`, 5000);
      const nodes = await rt.semantics(page);
      setShot(await shot(page, 'AUT-UI-13'));
      const btn = (l) => nodes.find((n) => n.role === 'button' && (n.text === l || n.label === l));
      const s = nodes.map((n) => n.text || n.label).join(' | ');
      const save = btn('Save changes');
      return { ok: /Runs as System Administrator/.test(s) && !btn('Run now') && !btn('Disable') && !btn('Delete') && (!save || save.disabled),
        actual: { runsAs: /Runs as System Administrator/.test(s), runNow: !!btn('Run now'), disable: !!btn('Disable'), del: !!btn('Delete'), save: save ? (save.disabled ? 'disabled' : 'enabled') : 'absent' } };
    });
  });

  await K({ id: 'AUT-UI-14', prd: 'B2', title: 'A change an automation made above the threshold waits in the approver\'s Approvals list',
    steps: 'admin MANUAL rule on a South invoice: CREATE_PROMISE FIXED 6000 (South PROMISE_CREATE limit 5000); run; sam opens /approvals',
    expected: 'PRD B2: the pending "Create promise" for the customer, ₹6,000.00, requested by System Administrator, is listed for sam' },
  async (a, setShot) => {
    const cust = await H.mkCustomer(t(), { regionId: w.regions.SOUTH.id, collector: U.sam.id, amounts: [2500] });
    const rule = await H.mustRule(t(), { trigger: H.manual(), condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.promise({ amount: { source: 'FIXED', value: 6000 } })] });
    ctx.cleanup.push(rule.id);
    const { effects } = await H.runAndWait(t(), rule.id);
    const pc = effects[0]?.result;
    return withApp('sam', async ({ page }) => {
      await rt.go(page, '/approvals', 5000);
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-14'));
      return { ok: pc?.type === 'PENDING_CHANGE' && s.includes(cust.c.name) && /6,000\.00/.test(s), actual: { effect: pc, listed: s.includes(cust.c.name), amountShown: /6,000\.00/.test(s), screen: H.short(s, 600) } };
    });
  });

  await K({ id: 'AUT-UI-15', prd: 'A5', title: 'Delete from the rule page removes the rule after confirming',
    steps: 'API rule "UI-del-<tag>"; admin opens it; tap Delete; confirm Delete',
    expected: 'DESIGN §7.7.3: back on /automation; the rule is gone from the list; API GET 404' },
  async (a, setShot) => {
    const name = `0-UI-del-${rt.uniq('r')}`;
    const rule = await H.mustRule(t(), { name, enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${rule.id}`, 4500);
      await rt.tap(page, 'Delete', { role: 'button', wait: 1500 });
      const dlg = await text(page);
      setShot(await shot(page, 'AUT-UI-15'));
      const nodes = (await rt.semantics(page)).filter((n) => n.role === 'button' && (n.text === 'Delete' || n.label === 'Delete'));
      await rt.clickAt(page, nodes[nodes.length - 1].x, nodes[nodes.length - 1].y, 3500);
      const hash = await page.evaluate(() => location.hash);
      const lines = await rt.screenText(page);
      setShot(await shot(page, 'AUT-UI-15'));
      const g = await rt.automation.get(t(), rule.id);
      return { ok: /Delete "/.test(dlg) && g.status === 404 && /#\/automation$/.test(hash) && !lines.includes(name), actual: { confirm: /Delete "/.test(dlg), hash, stillListedAsRow: lines.includes(name), snackbar: lines.find((x) => /deleted/.test(x)), apiGet: g.status } };
    });
  });

  await K({ id: 'AUT-UI-16', prd: 'A5', title: 'Run now on chosen records from the dialog',
    steps: 'API MANUAL rule over the fixture customer\'s invoices (2 invoices); open it; Run now; "Chosen records"; the dialog lists the picker',
    expected: 'DESIGN §7.7.3 run_now_dialog: "Chosen records" offers "Add invoice" and "Up to 50 invoices"; text "Every invoice that matches" reads correctly',
    severity: 'low' },
  async (a, setShot) => {
    const rule = await H.mustRule(t(), { trigger: H.manual(), condition: H.and(`id:eq:${fxInv.id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${rule.id}`, 4500);
      await rt.tap(page, 'Run now', { wait: 2000 });
      const first = await text(page);
      await rt.tap(page, 'Chosen records', { wait: 1500 });
      const chosen = await text(page);
      setShot(await shot(page, 'AUT-UI-16'));
      await rt.tap(page, 'Cancel', { wait: 1000 });
      const grammar = /Every invoice that matches/.test(first);
      return { ok: grammar && /Add invoice/.test(chosen) && /Up to 50 invoices/.test(chosen),
        actual: { segmentLabel: (first.match(/Every [a-z]+ that matches/) || [])[0], chosen: H.short(chosen.slice(chosen.indexOf('Which records')), 300) },
        codeRef: grammar ? '' : 'frontend/lib/features/automation/run_now_dialog.dart:233 (label uses the plural noun: \'Every $noun that matches\')' };
    });
  });

  await K({ id: 'AUT-UI-17', prd: 'A1', title: 'A rule made in the editor to run "When one is created" acts when an invoice is created',
    steps: 'admin /automation/new; Name; Runs "When one is created" (default); condition "Customer name" is <fresh customer>; task action "New {{Invoice.Number}}"; Create rule; create an invoice for that customer (API); wait 30 s; open the Runs tab',
    expected: 'PRD A1: a CREATED run for the new invoice is listed (Succeeded) and one task exists', severity: 'high' },
  async (a, setShot) => {
    const cust = await H.mkCustomer(t(), { regionId: N, collector: U.nora.id, amounts: [] });
    const name = rt.uniq('UI-oncreate');
    return withApp('admin', async ({ page }) => {
      await rt.go(page, '/automation/new', 4000);
      await fill(page, 'Name *', name);
      await addCondition(page, 'Customer name', 'is', cust.c.name);
      await addTaskAction(page, 'New {{Invoice.Number}}');
      await rt.tap(page, 'Create rule', { wait: 4000 });
      const saved = await findRuleByName(name);
      if (!saved) return { status: 'BLOCKED', actual: 'the rule was not created through the editor' };
      ctx.cleanup.push(saved.id);
      const inv = (await rt.createInvoice(t(), { customerId: cust.c.id, amount: 750, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: U.nora.id })).json;
      const tasks = await rt.waitFor(async () => { const x = await H.tasksOfRule(t(), saved.id); return x.length ? x : null; }, { timeoutMs: 30000 }) || [];
      await rt.go(page, `/automation/${saved.id}?tab=runs`, 5000);
      const s = await text(page);
      setShot(await shot(page, 'AUT-UI-17'));
      const ev = rt.sql(`select id, kind, status, attempts, coalesce(last_error,'') from automation_events where entity_type='INVOICE' and entity_id=${Number(inv?.id)} order by id`).map((r) => r.join('/'));
      await rt.automation.disable(t(), saved.id);
      return { ok: saved.triggerKind === 'ENTITY_CREATED' && tasks.length === 1 && /Succeeded/.test(s),
        actual: { trigger: saved.triggerKind, invoice: inv?.invoiceNumber, tasks: tasks.length, runsTab: H.short(s.slice(s.indexOf('Runs')), 300), events: ev },
        evidence: `automation_events for the invoice: ${ev.join(' ; ')}`, codeRef: 'backend/src/main/java/com/geneinvoice/automation/rule/AutomationRuleRepository.java:44' };
    });
  });

  await K({ id: 'AUT-UI-18', prd: 'A5', title: 'A rule whose owner lost the right to act shows as Suspended with the reason, and Resume brings it back',
    steps: 'nora\'s North MANUAL rule; admin sets nora to READ in North and South (rule suspends); restore her grants; nora opens the rule; tap Resume; confirm Resume',
    expected: 'DESIGN §5.10.7/§7.7.3: page shows "Suspended: …" and a Resume button (no Run now); after Resume the status is Active (API ACTIVE)' },
  async (a, setShot) => {
    const cust = await H.mkCustomer(t(), { regionId: N, collector: U.nora.id, amounts: [100] });
    w.tokens.nora = await rt.login('nora');
    const rule = await H.mustRule(w.tokens.nora, { trigger: H.manual(), regionIds: [N], condition: H.and(`id:eq:${cust.invoices[0].id}`), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    try {
      await rt.setGrants(t(), U.nora.id, [{ regionId: N, accessLevel: 'READ' }, { regionId: w.regions.SOUTH.id, accessLevel: 'READ' }]);
    } finally {
      await rt.setGrants(t(), U.nora.id, U.nora.grants);
    }
    const st = (await rt.automation.get(t(), rule.id)).json;
    return withApp('nora', async ({ page }) => {
      await rt.go(page, `/automation/${rule.id}`, 5000);
      const s1 = await text(page);
      setShot(await shot(page, 'AUT-UI-18'));
      const hasRunNow = await rt.exists(page, 'Run now', { role: 'button' });
      await rt.tap(page, 'Resume', { role: 'button', wait: 1500 });
      const dlg = await text(page);
      const btns = (await rt.semantics(page)).filter((n) => n.role === 'button' && (n.text === 'Resume' || n.label === 'Resume'));
      await rt.clickAt(page, btns[btns.length - 1].x, btns[btns.length - 1].y, 3000);
      const s2 = await text(page);
      setShot(await shot(page, 'AUT-UI-18'));
      const after = (await rt.automation.get(t(), rule.id)).json;
      return { ok: st.status === 'SUSPENDED' && /Suspended/.test(s1) && !hasRunNow && /Also act on the changes made while it was suspended/.test(dlg) && after.status === 'ACTIVE' && /\bActive\b/.test(s2),
        actual: { apiBefore: [st.status, st.suspendedReason], page: H.short((s1.match(/Suspended[^|]*/g) || []).join(' / '), 300), runNowShown: hasRunNow, dialog: /Also act on/.test(dlg), apiAfter: after.status } };
    });
  });

  await K({ id: 'AUT-UI-19', prd: 'B1', title: 'The editor offers only the regions where the owner can do what the rule does',
    steps: 'nora (MANAGE North, READ South, nothing in West) opens /automation/new; reads the Regions chips; adds a "Create a task" action; reads them again',
    expected: 'DESIGN §7.7.3 Regions: before any action NORTH and SOUTH (where she can see invoices); with a task action only NORTH; never WEST' },
  async (a, setShot) => {
    return withApp('nora', async ({ page }) => {
      await rt.go(page, '/automation/new', 4500);
      const chips = async () => (await rt.semantics(page)).filter((n) => n.role === 'checkbox' && /\n(DEFAULT|NORTH|SOUTH|WEST)$/.test(n.label || '')).map((n) => n.label.split('\n')[1]);
      const before = await chips();
      await rt.tap(page, /Add action/, { wait: 1500 });
      await rt.press(page, 'Create a task');
      await settle(page);
      const after = await chips();
      setShot(await shot(page, 'AUT-UI-19'));
      return { ok: JSON.stringify(before) === JSON.stringify(['NORTH', 'SOUTH']) && JSON.stringify(after) === JSON.stringify(['NORTH']), actual: { before, afterTaskAction: after } };
    });
  });

  // Round 2: AUT-R1-4 on the other two entity types, and booking a run for later from the dialog (PRD A5 "run from the UI whenever wanted").
  await K({ id: 'AUT-UI-20', prd: 'A5', title: 'The Run now dialog names one record of the rule\'s type: "Every customer that matches" and "Every payment that matches"',
    steps: 'API MANUAL CUSTOMER rule and MANUAL PAYMENT rule; open each; tap Run now; read the "Which records" segment; Cancel',
    expected: 'DESIGN §7.7.3 run_now_dialog (AUT-R1-4 fix): "Every customer that matches" / "Every payment that matches"', severity: 'low' },
  async (a, setShot) => {
    const rc = await H.mustRule(t(), { enabled: false, trigger: H.manual('CUSTOMER'), actions: [rt.action.task()] });
    const rp = await H.mustRule(t(), { enabled: false, trigger: H.manual('PAYMENT'), actions: [rt.action.task()] });
    ctx.cleanup.push(rc.id, rp.id);
    return withApp('admin', async ({ page }) => {
      const seg = {};
      for (const [k, r] of [['CUSTOMER', rc], ['PAYMENT', rp]]) {
        await rt.go(page, `/automation/${r.id}`, 4500);
        await rt.tap(page, 'Run now', { wait: 2000 });
        const s = await text(page);
        seg[k] = (s.match(/Every [A-Za-z]+ that matches/) || [])[0] || H.short(s, 200);
        setShot(await shot(page, 'AUT-UI-20'));
        await rt.tap(page, 'Cancel', { wait: 1000 });
      }
      return { ok: seg.CUSTOMER === 'Every customer that matches' && seg.PAYMENT === 'Every payment that matches', actual: seg,
        codeRef: 'frontend/lib/features/automation/run_now_dialog.dart:219-233' };
    });
  });

  await K({ id: 'AUT-UI-21', prd: 'A5', title: 'A run can be booked for later from the Run now dialog (At…), and it waits queued for that time',
    steps: 'API MANUAL INVOICE rule on the fixture invoice; open it; Run now; When "At…"; "Run at" -> date picker OK (default) -> time picker OK (default = now + 1 h); Book run; GET /rules/{id}/runs',
    expected: 'DESIGN §7.7.3 / §6.14 runAt: the dialog confirms the booking; one MANUAL run QUEUED with scheduledFor about one hour ahead (the picked minute); no task yet' },
  async (a, setShot) => {
    const rule = await H.mustRule(t(), { enabled: false, trigger: H.manual('INVOICE'), condition: H.and(`id:eq:${fxInv.id}`), actions: [rt.action.task({ title: 'Booked {{Invoice.Number}}' })] });
    ctx.cleanup.push(rule.id);
    return withApp('admin', async ({ page }) => {
      await rt.go(page, `/automation/${rule.id}`, 4500);
      const t0 = Date.now();
      await rt.tap(page, 'Run now', { wait: 2000 });
      await rt.tap(page, 'At…', { wait: 1200 });
      await rt.tap(page, /^Run at/, { wait: 1500 });
      await rt.tap(page, 'OK', { wait: 1500 }); // date
      await rt.tap(page, 'OK', { wait: 1500 }); // time
      const form = await text(page);
      await rt.tap(page, 'Book run', { wait: 3000 });
      const done = await text(page);
      setShot(await shot(page, 'AUT-UI-21'));
      const runs = (await rt.automation.runs(t(), rule.id)).json?.content || [];
      const r = runs[0];
      const aheadMin = r?.scheduledFor ? (Date.parse(r.scheduledFor) - t0) / 60000 : null;
      const tasks = await H.tasksOfRule(t(), rule.id);
      return { ok: runs.length === 1 && r.status === 'QUEUED' && r.triggerType === 'MANUAL' && aheadMin > 55 && aheadMin < 62 && tasks.length === 0,
        actual: { runs: runs.map((x) => [x.id, x.triggerType, x.status, x.scheduledFor]), aheadMin: aheadMin && Math.round(aheadMin * 10) / 10, tasks: tasks.length,
          dialogBefore: H.short(form.slice(form.indexOf('When')), 200), dialogAfter: H.short(done.slice(done.indexOf('Run "')), 300) } };
    });
  });
};
