// Regression cases added in round 5, one step around the round-4 fixes:
//  - RGN-R4-1 (PendingChangeScope.canSeeRecord / isVisible now look the record's region up with a Criteria query over
//    Temporal.fromValid, live and as of a date): in the single-region app, book-scoped POCs, a viewer, a customer
//    login and approvers must still see exactly the pending changes whose record they can open (REG-APR-03, REG-UI-44),
//    and as of a date the lookup over version rows must work (REG-APR-04, run after the UI phase because it ages the DB).
//  - TSK-R4-1 (placeholder_button.dart refocuses the field after a pick / a cancel; send_email_dialog.dart now owns a
//    FocusNode per field): the manual Send email dialog gives the field its focus back after a mouse pick, with the
//    semantics tree on and off, so typing carries on after the token (REG-UI-43).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customer, waitText, openAs, allText, brief } = H;
const SCOPE = 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java:102-155';
const FE = 'frontend/lib/features/automation/placeholder_button.dart (_onSelected / onCanceled refocus); frontend/lib/features/email/send_email_dialog.dart (_subjectFocus/_bodyFocus)';
const READ_ONLY = 'Read-only in the as-of view';

const act = (page) => page.evaluate(() => {
  const a = document.activeElement;
  return a ? { tag: a.tagName, value: a.value ?? null, sel: [a.selectionStart ?? null, a.selectionEnd ?? null] } : null;
});
const nowVals = (d) => d && { current: d.current, changes: (d.changes || []).map((x) => [x.field, x.current, x.currentText]) };
const hasNow = (d) => !!(d && d.current && Object.keys(d.current).length && (d.changes || []).some((x) => x.current !== null && x.current !== undefined));
const noNow = (d) => !!(d && (d.current === null || d.current === undefined) && (d.changes || []).every((x) => (x.current === null || x.current === undefined) && (x.currentText === null || x.currentText === undefined)));

/** Single-region approver used to clear a loosening (only ADMIN holds CHANGE_APPROVE, and nobody approves their own). */
async function secondApprover(w) {
  if (w.r5appr) return w.r5appr;
  const u = await rt.createStaff(w.tokens.admin, 'ADMIN', 'r5appr', { grants: [{ regionId: w.D, accessLevel: 'APPROVE', recordScope: 'ALL' }] });
  w.r5appr = { user: u, token: await rt.login(u.username, u.password) };
  return w.r5appr;
}

async function clearThresholds(w, c, ops) {
  const T = w.tokens;
  const t2 = await rt.setThresholds(T.admin, w.D, ops.map((operation) => ({ operation, amount: null })));
  const relax = t2.pendingChange && t2.pendingChange.id;
  c.that('loosening back is held 202 (THRESHOLD_CHANGE)', t2.status === 202 && relax, brief(t2));
  if (relax) {
    const a2 = await secondApprover(w);
    const ap = await rt.approvals.approve(a2.token, relax, 'r5 cleanup');
    c.that('a second approver clears the loosening', ap.status === 200 && ap.json.status === 'APPROVED', brief(ap));
  }
  const th = await rt.getThresholds(T.admin, w.D);
  const live = ((th.json && th.json.thresholds) || []).filter((x) => x.amount !== null && x.amount !== undefined);
  c.that('DEFAULT has no thresholds again', th.status === 200 && live.length === 0, live);
}

module.exports = async function r5Cases(w, R, only, { api = true, ui = true, aged = false } = {}) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens;

  if (api) {
    await tcase({ id: 'REG-APR-03', feature: 'Maker-checker in the single-region app', kind: 'API', prd: 'B2',
      title: 'Single region, around RGN-R4-1: a held terms change on a Sales POC\'s invoice and a held promise create are visible (detail, list, for-record) exactly to those who can open the record, with live values only for them; the maker is told it is his own; the admin approves',
      steps: 'admin PUT DEFAULT thresholds [INVOICE_TERMS 500, PROMISE_CREATE 500]; fresh customer (Collection POC collbook, customer login) with invoices 1000 and 1200 (Sales POC sales); sales PATCH inv1 {paymentTerm NET_90} (held pc1) and inv2 {paymentTerm NET_60} (held pc2, left pending for REG-UI-44 / REG-APR-04); collbook POST /api/promises 800 (held pcP); for admin, sales, sales2, coll, collbook, viewer, customer: GET invoice, GET /api/approvals/{pc}, list, for-record; admin approves pc1, rejects pcP; thresholds cleared (loosening approved by a second admin)',
      expected: 'B2 "a pending change is visible to everyone who can see the record, without having taken effect" + DESIGN 6.11 + TRIAGE r4 B1: detail 200 exactly when the user can open the record (or is the maker / an approver); now-values (current, changes[].current) present exactly when the record is visible; the list and for-record agree with the detail; sales blockedReason "You requested this change"; the invoice due date is unchanged until approval, then moves to Net 90' }, async () => {
      const c = checks();
      const ev = {};
      const t1 = await rt.setThresholds(T.admin, w.D, [{ operation: 'INVOICE_TERMS', amount: 500, windowHours: 24 }, { operation: 'PROMISE_CREATE', amount: 500, windowHours: 24 }]);
      c.that('tighten 200', t1.status === 200, brief(t1));
      try {
        const cu = await customer(w, { prefix: 'R5Held', collection: w.users.collbook.id, invoices: [{ amount: 1000 }, { amount: 1200 }] });
        const [inv1, inv2] = cu.invoices;
        const custTok = await rt.login(cu.username, cu.password);
        const h1 = await rt.patchInvoice(T.sales, inv1.id, { paymentTerm: 'NET_90', version: inv1.version });
        c.that('sales terms change on inv1 held 202', h1.held && h1.pendingChange, brief(h1));
        const h2 = await rt.patchInvoice(T.sales, inv2.id, { paymentTerm: 'NET_60', version: inv2.version });
        c.that('sales terms change on inv2 held 202', h2.held && h2.pendingChange, brief(h2));
        const hp = await rt.createPromise(T.collbook, { customerId: cu.id, amount: 800, collectionPocUserId: w.users.collbook.id, invoiceIds: [inv1.id] });
        c.that('collbook promise 800 held 202', hp.held && hp.pendingChange, brief(hp));
        const pc1 = h1.pendingChange && h1.pendingChange.id;
        const pc2 = h2.pendingChange && h2.pendingChange.id;
        const pcP = hp.pendingChange && hp.pendingChange.id;
        ev.pcs = { pc1, pc2, pcP, cu: cu.id, inv1: inv1.id, inv2: inv2.id };
        w.r5 = { cu, inv1, inv2, pc1, pc2, pcP };
        if (!pc1 || !pcP) return { ok: false, actual: c.summary(), evidence: JSON.stringify(ev) };

        const who = { admin: T.admin, sales: T.sales, sales2: T.sales2, coll: T.coll, collbook: T.collbook, viewer: T.viewer, customer: custTok };
        const table = {};
        for (const [name, tk] of Object.entries(who)) {
          const gi = await rt.api('GET', `/api/invoices/${inv1.id}`, { token: tk });
          const gc = await rt.api('GET', `/api/customers/${cu.id}`, { token: tk });
          const d1 = await rt.approvals.get(tk, pc1);
          const dP = await rt.approvals.get(tk, pcP);
          const fr = await rt.approvals.forRecord(tk, 'INVOICE', inv1.id);
          const row = { inv: gi.status, cust: gc.status, pc1: d1.status, pcP: dP.status, fr: fr.status,
            frHas: fr.status === 200 && Array.isArray(fr.json) && fr.json.some((b) => b.id === pc1),
            pc1Now: hasNow(d1.json), pc1NoNow: noNow(d1.json), pc1Blocked: d1.json && d1.json.blockedReason, pc1Req: d1.json && d1.json.requestedBy };
          if (name !== 'customer') {
            const l = await rt.list(tk, '/api/approvals', { view: 'ALL' });
            const ids = ((l.json && l.json.content) || []).map((x) => x.id);
            row.list = l.status; row.listPc1 = ids.includes(pc1); row.listPcP = ids.includes(pcP);
          }
          table[name] = row;
          const approver = name === 'admin';
          const maker1 = name === 'sales';
          const makerP = name === 'collbook';
          // pc1 is about an existing invoice: seen by the maker, an approver, and whoever can open the invoice.
          const want1 = gi.status === 200 || maker1 || approver;
          c.that(`${name}: pc1 detail ${want1 ? 200 : 404} (invoice ${gi.status})`, d1.status === (want1 ? 200 : 404), row);
          if (d1.status === 200) {
            c.that(`${name}: pc1 now-values ${gi.status === 200 ? 'shown' : 'hidden'} (invoice ${gi.status})`, gi.status === 200 ? hasNow(d1.json) : noNow(d1.json), nowVals(d1.json));
          }
          if (name === 'customer') {
            c.that('customer: requestedBy hidden', d1.status !== 200 || d1.json.requestedBy === null, d1.json && d1.json.requestedBy);
            c.that('customer: no approvals list (403)', (await rt.api('GET', '/api/approvals', { token: tk, query: { size: 10 } })).status === 403);
            c.that('customer: a pending create is not shown (404)', dP.status === 404, dP.status);
          } else {
            c.that(`${name}: list has pc1 exactly when the detail is 200`, row.listPc1 === (d1.status === 200), row);
            // pcP is a create judged by the customer: maker, approver, or whoever sees the customer.
            const wantP = gc.status === 200 || makerP || approver;
            c.that(`${name}: pcP detail ${wantP ? 200 : 404} (customer ${gc.status})`, dP.status === (wantP ? 200 : 404), row);
            c.that(`${name}: list has pcP exactly when the detail is 200`, row.listPcP === (dP.status === 200), row);
          }
          if (gi.status === 200) c.that(`${name}: for-record lists pc1`, row.frHas, row);
          else c.that(`${name}: for-record 404 on an invoice he cannot open`, fr.status === 404, row);
        }
        ev.table = table;
        // Pins for this world, so the invariant above cannot pass vacuously.
        c.that('sales (maker, his book): 200 with now-values, "You requested this change"', table.sales.pc1 === 200 && table.sales.pc1Now && table.sales.pc1Blocked === 'You requested this change', table.sales);
        c.that('sales2 (another Sales POC): invoice 404 and change 404', table.sales2.inv === 404 && table.sales2.pc1 === 404 && !table.sales2.listPc1, table.sales2);
        c.that('viewer (READ): 200 with now-values', table.viewer.pc1 === 200 && table.viewer.pc1Now, table.viewer);
        c.that('customer login: 200 on its own invoice\'s change', table.customer.pc1 === 200, table.customer);
        c.that('collbook (maker of pcP) sees pcP', table.collbook.pcP === 200 && table.collbook.listPcP, table.collbook);
        const g0 = (await H.getJ(T.admin, `/api/invoices/${inv1.id}`)).json || {};
        c.that('inv1 terms unchanged while pending', g0.paymentTerm === 'NET_30' && g0.dueDate === inv1.dueDate, [g0.paymentTerm, g0.dueDate, inv1.dueDate]);
        const s = await rt.approvals.summary(T.admin);
        c.that('admin summary counts the changes awaiting him', s.status === 200 && s.json.awaitingMeCount >= 3, brief(s));
        const ap = await rt.approvals.approve(T.admin, pc1, 'r5');
        c.that('admin approves pc1 -> APPROVED', ap.status === 200 && ap.json.status === 'APPROVED', brief(ap));
        const g1 = (await H.getJ(T.admin, `/api/invoices/${inv1.id}`)).json || {};
        c.that('inv1 now Net 90 with a later due date', g1.paymentTerm === 'NET_90' && g1.dueDate > inv1.dueDate, [g1.paymentTerm, g1.dueDate]);
        const d2 = await rt.approvals.get(T.sales2, pc1);
        c.that('sales2 still 404 on the decided change', d2.status === 404, d2.status);
        const rj = await rt.approvals.reject(T.admin, pcP, 'r5 not needed');
        c.that('admin rejects pcP -> REJECTED', rj.status === 200 && rj.json.status === 'REJECTED', brief(rj));
      } finally {
        await clearThresholds(w, c, ['INVOICE_TERMS', 'PROMISE_CREATE']);
      }
      return { ok: c.ok, actual: c.summary(), evidence: JSON.stringify(ev).slice(0, 3000), codeRef: SCOPE };
    }, { retry: false });
  }

  if (ui) {
    await tcase({ id: 'REG-UI-43', feature: 'Email compose', kind: 'UI', prd: 'A4',
      title: 'Send email (TSK-R4-1 around): after a mouse pick from Insert placeholder the Subject / Message keeps the focus with the caret after the token, so typing carries on; closing the menu without a pick also gives the focus back; with the semantics tree on and off',
      steps: 'fresh invoice; admin #/invoices/{id} -> Send email; tick "Customer emails"; (A, semantics on) click Subject, type "Hello ", click its Insert placeholder, click "Name"; read document.activeElement; type " now"; open the Subject menu and dismiss it; type "?"; click Message, type "Owes ", its Insert placeholder > Number; type "!"; Send. (B) the same Subject/Message steps in a fresh page that never turns semantics on (mouse at the coordinates read in A)',
      expected: 'FIXLOG r4 F1 / TRIAGE r4 F1 (A4): after the pick document.activeElement is the field\'s INPUT (Subject) / TEXTAREA (Message) holding "Hello {{Customer.Name}}" with the caret at 23 / "Owes {{Invoice.Number}}" at 23; typing gives "Hello {{Customer.Name}} now", after cancel "...now?", "Owes {{Invoice.Number}}!"; saved email rendered with status NOT_SENT/NOT_CONFIGURED',
      severity: 'low' }, async (a) => {
      const c = checks(); let shot = '';
      const cu = await customer(w, { prefix: 'UIFocus', invoices: [{ amount: 100 }] });
      const inv = cu.invoices[0];
      const pos = {};
      const trace = {};
      // ---- A: semantics on
      {
        const app = await openAs('admin', `/invoices/${inv.id}`);
        const { page } = app;
        const click = async (p, ms = 1500) => { await page.mouse.click(p.x, p.y); await page.waitForTimeout(ms); };
        try {
          pos.send = await rt.find(page, 'Send email', { role: 'button' });
          await rt.tap(page, 'Send email', { role: 'button', wait: 3000 });
          pos.chip = await rt.find(page, /^Customer emails/);
          await rt.tap(page, /^Customer emails/, { wait: 800 });
          pos.subject = await rt.find(page, 'Subject *', { role: 'textbox' });
          pos.message = await rt.find(page, 'Message', { role: 'textbox' });
          const phs = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder' || n.label === 'Insert placeholder').sort((p, q) => p.y - q.y);
          pos.ph0 = phs[0]; pos.ph1 = phs[1];
          await click(pos.subject, 800);
          await page.keyboard.type('Hello ', { delay: 30 }); await page.waitForTimeout(500);
          trace.a0 = await act(page);
          c.that('A: precondition Subject focused holding "Hello "', trace.a0 && trace.a0.tag === 'INPUT' && trace.a0.value === 'Hello ', trace.a0);
          await click(pos.ph0);
          pos.name = await rt.find(page, 'Name', { role: 'menuitem' });
          await click(pos.name, 1800);
          trace.a1 = await act(page);
          c.that('A: after the pick the Subject has the focus, caret after the token', trace.a1 && trace.a1.tag === 'INPUT' && trace.a1.value === 'Hello {{Customer.Name}}' && trace.a1.sel[0] === 23 && trace.a1.sel[1] === 23, trace.a1);
          await page.keyboard.type(' now', { delay: 30 }); await page.waitForTimeout(700);
          trace.a2 = await act(page);
          c.that('A: typing " now" lands after the token', trace.a2 && trace.a2.value === 'Hello {{Customer.Name}} now', trace.a2);
          await click(pos.ph0);
          c.that('A: the menu opened again', await rt.exists(page, 'Name', { role: 'menuitem' }));
          await rt.dismiss(page);
          await page.waitForTimeout(800);
          trace.a3 = await act(page);
          c.that('A: after closing the menu the Subject has its focus back, text and caret unchanged', trace.a3 && trace.a3.tag === 'INPUT' && trace.a3.value === 'Hello {{Customer.Name}} now' && trace.a3.sel[0] === 27, trace.a3);
          await page.keyboard.type('?', { delay: 30 }); await page.waitForTimeout(700);
          trace.a4 = await act(page);
          c.that('A: typing "?" after the cancel lands at the end', trace.a4 && trace.a4.value === 'Hello {{Customer.Name}} now?', trace.a4);
          await rt.enableSemantics(page);
          const msg = await rt.find(page, 'Message', { role: 'textbox' });
          await click(msg, 800);
          await page.keyboard.type('Owes ', { delay: 30 }); await page.waitForTimeout(500);
          const ph1 = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder' || n.label === 'Insert placeholder').sort((p, q) => p.y - q.y)[1];
          await click(ph1);
          pos.number = await rt.find(page, 'Number', { role: 'menuitem' });
          await click(pos.number, 1800);
          trace.a5 = await act(page);
          c.that('A: after the pick the Message has the focus, caret after the token', trace.a5 && trace.a5.tag === 'TEXTAREA' && trace.a5.value === 'Owes {{Invoice.Number}}' && trace.a5.sel[0] === 23 && trace.a5.sel[1] === 23, trace.a5);
          await page.keyboard.type('!', { delay: 30 }); await page.waitForTimeout(700);
          trace.a6 = await act(page);
          c.that('A: typing "!" lands after the token', trace.a6 && trace.a6.value === 'Owes {{Invoice.Number}}!', trace.a6);
          shot = await rt.shot(page, R.shotsDir, `REG-UI-43-${a}-A`);
          await rt.tap(page, 'Send', { role: 'button', wait: 3500 });
          const req = await H.waitReq(app, (r) => r.method === 'POST' && r.url === '/api/emails', 5000);
          c.that('A: POST /api/emails 200', req && req.status === 200, req);
          const l = await rt.emailsFor(T.admin, 'INVOICE', inv.id);
          const e = ((l.json && l.json.content) || [])[0];
          c.that('A: saved email rendered',
            e && e.subject.trim() === `Hello ${cu.name} now?` && e.body.trim() === `Owes ${inv.invoiceNumber}!` && /NOT_SENT|NOT_CONFIGURED/.test(e.status),
            e && { s: e.subject, b: e.body, st: e.status });
        } catch (e) {
          c.that('A: UI steps completed', false, String(e.message || e).slice(0, 600));
          try { shot = await rt.shot(page, R.shotsDir, `REG-UI-43-${a}-A-err`); } catch { /* ignore */ }
        } finally {
          c.that('A: no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
          await app.close();
        }
      }
      // ---- B: a mouse user, semantics never enabled
      if (pos.send && pos.chip && pos.subject && pos.ph0 && pos.name) {
        const { chromium } = require('playwright-core');
        const browser = await chromium.launch({ executablePath: rt.chromiumPath(), headless: true });
        try {
          const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
          await ctx.addInitScript((t) => localStorage.setItem('flutter.gene_invoice_token', JSON.stringify(t)), T.admin);
          const page = await ctx.newPage();
          // Harness (r5): a deep link in page.goto lands on the dashboard; navigate by hash after boot, as rt.go does.
          await page.goto(process.env.E2E_WEB);
          await rt.waitForFlutter(page, 6000);
          await page.evaluate((h) => { location.hash = h; }, `/invoices/${inv.id}`);
          await page.waitForTimeout(5000);
          const click = async (p, ms = 1500) => { await page.mouse.click(p.x, p.y); await page.waitForTimeout(ms); };
          await click(pos.send, 3000);
          await click(pos.chip, 800);
          await click(pos.subject, 800);
          await page.keyboard.type('Hi ', { delay: 30 }); await page.waitForTimeout(500);
          trace.b0 = await act(page);
          c.that('B: precondition Subject focused holding "Hi "', trace.b0 && trace.b0.tag === 'INPUT' && trace.b0.value === 'Hi ', trace.b0);
          await click(pos.ph0); await click(pos.name, 1800);
          trace.b1 = await act(page);
          c.that('B: after the pick the Subject has the focus, caret after the token', trace.b1 && trace.b1.tag === 'INPUT' && trace.b1.value === 'Hi {{Customer.Name}}' && trace.b1.sel[0] === 20 && trace.b1.sel[1] === 20, trace.b1);
          await page.keyboard.type(' there', { delay: 30 }); await page.waitForTimeout(700);
          trace.b2 = await act(page);
          c.that('B: typing " there" lands after the token', trace.b2 && trace.b2.value === 'Hi {{Customer.Name}} there', trace.b2);
          if (pos.message && pos.ph1 && pos.number) {
            await click(pos.message, 800);
            await page.keyboard.type('Owes ', { delay: 30 }); await page.waitForTimeout(500);
            await click(pos.ph1); await click(pos.number, 1800);
            trace.b3 = await act(page);
            c.that('B: after the pick the Message has the focus, caret after the token', trace.b3 && trace.b3.tag === 'TEXTAREA' && trace.b3.value === 'Owes {{Invoice.Number}}' && trace.b3.sel[0] === 23, trace.b3);
            await page.keyboard.type('!', { delay: 30 }); await page.waitForTimeout(700);
            trace.b4 = await act(page);
            c.that('B: typing "!" lands after the token', trace.b4 && trace.b4.value === 'Owes {{Invoice.Number}}!', trace.b4);
          }
          const sB = await rt.shot(page, R.shotsDir, `REG-UI-43-${a}-B`);
          if (!c.ok) shot = shot || sB;
        } catch (e) {
          c.that('B: UI steps completed', false, String(e.message || e).slice(0, 600));
        } finally { await browser.close(); }
      } else {
        c.that('B: coordinates captured in A', false, Object.keys(pos));
      }
      return { ok: c.ok, actual: c.summary(), screenshot: shot, severity: 'low', codeRef: FE, evidence: `invoice ${inv.id}; activeElement trace ${JSON.stringify(trace)}` };
    });

    await tcase({ id: 'REG-UI-44', feature: 'Maker-checker in the single-region app', kind: 'UI', prd: 'B2',
      title: 'Approvals screen (around RGN-R4-1): the Sales POC sees his own pending terms change with the current and proposed terms and "You requested this change"; another Sales POC gets a not-found page and no leak; the admin sees Approve/Reject',
      steps: 'pc2 from REG-APR-03 (sales: inv2 NET_30 -> NET_60, pending); sales opens #/approvals and #/approvals/{pc2}; sales2 opens #/approvals/{pc2}; admin opens #/approvals/{pc2}',
      expected: 'B2 "visible to everyone who can see the record, without having taken effect"; DESIGN 6.11 / 7.7.1: sales sees the row (invoice number) and the detail with Net 30 -> Net 60 and "You requested this change", no Approve; sales2 not found, no "Net 60"; admin has Approve and Reject' }, async (a) => {
      const c = checks(); let shot = '';
      const s = w.r5;
      if (!s || !s.pc2) return { ok: false, actual: 'REG-APR-03 did not leave pc2 pending' };
      const shots = [];
      const salesUser = { username: w.users.sales.username, password: w.users.sales.password };
      let app = await openAs(salesUser, '/approvals');
      try {
        c.that('sales: list row shows the invoice', !!(await waitText(app.page, new RegExp(s.inv2.invoiceNumber), 10000)), (await allText(app.page)).slice(0, 600));
        await rt.go(app.page, `/approvals/${s.pc2}`, 5000);
        c.that('sales: detail shows "You requested this change"', !!(await waitText(app.page, /You requested this change/, 10000)), (await allText(app.page)).slice(0, 800));
        const t = await allText(app.page);
        c.that('sales: current Net 30 and proposed Net 60 shown', /Net 30/.test(t) && /Net 60/.test(t), t.slice(0, 800));
        c.that('sales: no Approve button', !(await rt.exists(app.page, 'Approve', { role: 'button' })));
      } catch (e) {
        c.that('sales: UI steps completed', false, String(e.message || e).slice(0, 500));
      } finally {
        shots.push(await rt.shot(app.page, R.shotsDir, `REG-UI-44-${a}-sales`));
        c.that('sales: no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        c.that('sales: no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 3));
        await app.close();
      }
      app = await openAs({ username: w.users.sales2.username, password: w.users.sales2.password }, `/approvals/${s.pc2}`);
      try {
        await app.page.waitForTimeout(3000);
        const t = await allText(app.page);
        c.that('sales2: not-found message', /not found|no longer|could not|doesn.t exist|does not exist/i.test(t), t.slice(0, 500));
        c.that('sales2: nothing leaked', !/Net 60/.test(t) && !t.includes(s.inv2.invoiceNumber), t.slice(0, 500));
      } catch (e) {
        c.that('sales2: UI steps completed', false, String(e.message || e).slice(0, 500));
      } finally {
        shots.push(await rt.shot(app.page, R.shotsDir, `REG-UI-44-${a}-sales2`));
        c.that('sales2: no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        await app.close();
      }
      app = await openAs('admin', `/approvals/${s.pc2}`);
      try {
        c.that('admin: Approve and Reject offered', !!(await waitText(app.page, 'Approve', 10000, { role: 'button' })) && (await rt.exists(app.page, 'Reject', { role: 'button' })), (await allText(app.page)).slice(0, 600));
      } catch (e) {
        c.that('admin: UI steps completed', false, String(e.message || e).slice(0, 500));
      } finally {
        shots.push(await rt.shot(app.page, R.shotsDir, `REG-UI-44-${a}-admin`));
        await app.close();
      }
      shot = shots[0];
      return { ok: c.ok, actual: c.summary(), screenshot: shot, evidence: `pc2=${s.pc2}; shots ${shots.join(', ')}`, codeRef: SCOPE };
    });
  }

  if (aged) {
    await tcase({ id: 'REG-APR-04', feature: 'As-of approvals in the single-region app', kind: 'API', prd: 'B3',
      title: 'As of a date (around RGN-R4-1\'s record-region lookup over version rows): a change requested 10 days ago and rejected today is outstanding as of 2 days ago for the maker, a viewer and the customer (read-only, no now-values), still 404 for another Sales POC; the as-of lists agree; live it is REJECTED',
      steps: 'pc2 from REG-APR-03 pending; ageWorld(10); admin rejects pc2 now; for sales, sales2, viewer, customer, admin: GET /api/approvals/{pc2}?asOf=day(-2) and live; GET /api/approvals?asOf=day(-2)&view=ALL; GET /api/invoices/{inv2}?asOf=day(-2)',
      expected: 'B3 "which approvals were outstanding" + DESIGN 6.11 (statusAt, "Read-only in the as-of view"): as-of 200 statusAt PENDING, blockedReason read-only, no approve; sales2 404 (invoice not his) as-of and live; the as-of list holds pc2 for sales/viewer/admin and not for sales2; the invoice is Net 30 then and now' }, async () => {
      const c = checks();
      const s = w.r5;
      if (!s || !s.pc2) return { ok: false, actual: 'REG-APR-03 did not leave pc2 pending' };
      const tabs = rt.ageWorld(10);
      c.that('world aged', tabs.length > 0, tabs.length);
      const AT = rt.day(-2);
      // Harness (r5): ageWorld shifts the invoice's due_date column but not the dates inside the pending change's JSON
      // snapshot, so an approval after ageing FAILS with "Due date was changed by someone else" (the app's stale-change
      // guard working as designed). The change is therefore decided by a rejection, which has no such check.
      const ap = await rt.approvals.reject(T.admin, s.pc2, 'r5 aged');
      c.that('admin rejects pc2 today -> REJECTED', ap.status === 200 && ap.json.status === 'REJECTED', { s: ap.status, st: ap.json && ap.json.status, failureReason: ap.json && ap.json.failureReason });
      const custTok = await rt.login(s.cu.username, s.cu.password);
      const who = { admin: T.admin, sales: T.sales, sales2: T.sales2, viewer: T.viewer, customer: custTok };
      const table = {};
      for (const [name, tk] of Object.entries(who)) {
        const d = await rt.approvals.get(tk, s.pc2, { asOf: AT });
        const dl = await rt.approvals.get(tk, s.pc2);
        const gi = await rt.api('GET', `/api/invoices/${s.inv2.id}`, { token: tk, query: { asOf: AT } });
        const row = { asOf: d.status, statusAt: d.json && d.json.statusAt, blocked: d.json && d.json.blockedReason, canApprove: d.json && d.json.canApprove,
          noNow: noNow(d.json), live: dl.status, liveStatus: dl.json && dl.json.status, inv: gi.status, invTerm: gi.json && gi.json.paymentTerm };
        if (name !== 'customer') {
          const l = await rt.list(tk, '/api/approvals', { view: 'ALL' }, { asOf: AT });
          row.list = l.status; row.listHas = ((l.json && l.json.content) || []).some((x) => x.id === s.pc2);
        }
        table[name] = row;
        if (name === 'sales2') {
          c.that('sales2: 404 as of then and live', d.status === 404 && dl.status === 404, row);
          c.that('sales2: as-of list without pc2', row.list === 200 && !row.listHas, row);
          continue;
        }
        c.that(`${name}: as-of 200, statusAt PENDING, read-only, cannot approve, no now-values`,
          d.status === 200 && d.json.statusAt === 'PENDING' && d.json.blockedReason === READ_ONLY && !d.json.canApprove && noNow(d.json), row);
        c.that(`${name}: live 200 REJECTED`, dl.status === 200 && dl.json.status === 'REJECTED', row);
        c.that(`${name}: invoice as of then 200 and Net 30`, gi.status === 200 && gi.json.paymentTerm === 'NET_30', row);
        if (name !== 'customer') c.that(`${name}: as-of list holds pc2`, row.list === 200 && row.listHas, row);
      }
      const live = (await H.getJ(T.admin, `/api/invoices/${s.inv2.id}`)).json || {};
      c.that('invoice live stays Net 30 (rejected)', live.paymentTerm === 'NET_30', live.paymentTerm);
      return { ok: c.ok, actual: c.summary(), evidence: JSON.stringify(table).slice(0, 3000), codeRef: SCOPE };
    }, { retry: false });
  }
};
