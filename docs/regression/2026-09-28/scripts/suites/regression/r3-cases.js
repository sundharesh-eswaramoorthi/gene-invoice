// Regression cases added in round 3, next to the partial-edit work (APP-R1-1/2, F2) that changed how the invoice
// page and PATCH /api/invoices/{id} treat absent fields:
//  - REG-INV-11 (API): the pre-existing invoice edit contract still holds field by field -- a notes-only PATCH leaves
//    terms and due date alone (no due-date audit row); a bare dueDate means Custom terms; dueDate with a named term,
//    a due date before the invoice date, a stale version and an edit of a cancelled invoice are refused.
//  - REG-UI-40 (UI): the invoice page sends a notes-only save as {notes} only, and a later terms change as
//    {paymentTerm} only (the saved notes are not re-sent).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, customer, getJ, waitText, allText, openAs } = H;
const BE = { inv: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:199' };
const FE = { inv: 'frontend/lib/features/invoices/invoice_detail_screen.dart:135' };

module.exports = async function r3Cases(w, R, only, { api = true, ui = true } = {}) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens;

  if (api) {
    await tcase({ id: 'REG-INV-11', feature: 'Invoice edit', kind: 'API', prd: 'B2',
      title: 'Invoice edit contract field by field: notes-only keeps terms; a bare dueDate is Custom; bad combinations, stale versions and cancelled invoices are refused',
      steps: 'customer NET_30, invoice A 300 (today, due +30); PATCH A {notes:"only notes"}; GET A; GET /api/audit INVOICE A; PATCH A {dueDate:+45}; PATCH A {paymentTerm:NET_15, dueDate:+20}; PATCH A {dueDate:-5}; PATCH A {notes:"v", version:<current-1>}; cancel invoice B, PATCH B {notes}',
      expected: 'notes-only 200: notes set, paymentTerm NET_30 and dueDate +30 unchanged, no INVOICE_DUE_DATE_CHANGED row; bare dueDate 200: CUSTOM, dueDate +45, one INVOICE_DUE_DATE_CHANGED row; term+dueDate 400 on field dueDate; before invoice date 400 on dueDate; stale version 409; cancelled 400 "Cannot edit a cancelled invoice"; nothing held (no thresholds)' }, async () => {
      const c = checks();
      const cu = await customer(w, { prefix: 'EditContract', invoices: [{ amount: 300 }, { amount: 120 }] });
      const [A, B] = cu.invoices;
      const auditRows = async () => {
        const au = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: A.id });
        return Array.isArray(au.json) ? au.json : (au.json && au.json.content) || [];
      };
      const p1 = await rt.patchInvoice(T.admin, A.id, { notes: 'only notes' });
      c.that('notes-only 200 not held', p1.status === 200 && !p1.held, brief(p1));
      const g1 = (await getJ(T.admin, `/api/invoices/${A.id}`)).json || {};
      c.that('notes set, terms and due date unchanged', g1.notes === 'only notes' && g1.paymentTerm === 'NET_30' && g1.dueDate === rt.day(30), { n: g1.notes, t: g1.paymentTerm, d: g1.dueDate });
      const rows1 = await auditRows();
      c.that('no due-date audit row after a notes-only edit', !rows1.some((r) => r.action === 'INVOICE_DUE_DATE_CHANGED'), rows1.map((r) => r.action));
      const p2 = await rt.patchInvoice(T.admin, A.id, { dueDate: rt.day(45) });
      const g2 = (await getJ(T.admin, `/api/invoices/${A.id}`)).json || {};
      c.that('bare dueDate 200 -> CUSTOM, +45, notes kept', p2.status === 200 && !p2.held && g2.paymentTerm === 'CUSTOM' && g2.dueDate === rt.day(45) && g2.notes === 'only notes', { s: p2.status, t: g2.paymentTerm, d: g2.dueDate, n: g2.notes });
      const rows2 = await auditRows();
      c.that('one due-date audit row now', rows2.filter((r) => r.action === 'INVOICE_DUE_DATE_CHANGED').length === 1, rows2.map((r) => r.action));
      const p3 = await rt.patchInvoice(T.admin, A.id, { paymentTerm: 'NET_15', dueDate: rt.day(20) });
      c.that('named term + dueDate -> 400 on dueDate', p3.status === 400 && /dueDate/.test(p3.text || ''), brief(p3));
      const p4 = await rt.patchInvoice(T.admin, A.id, { dueDate: rt.day(-5) });
      c.that('due date before the invoice date -> 400 on dueDate', p4.status === 400 && /dueDate/.test(p4.text || ''), brief(p4));
      const ver = g2.version;
      if (typeof ver === 'number') {
        const p5 = await rt.patchInvoice(T.admin, A.id, { notes: 'stale', version: ver - 1 });
        c.that('stale version -> 409', p5.status === 409, brief(p5));
      } else {
        c.that('invoice DTO carries a version (for the stale-version check)', ver !== undefined, Object.keys(g2));
      }
      const g3 = (await getJ(T.admin, `/api/invoices/${A.id}`)).json || {};
      c.that('refused edits changed nothing', g3.paymentTerm === 'CUSTOM' && g3.dueDate === rt.day(45) && g3.notes === 'only notes', { t: g3.paymentTerm, d: g3.dueDate, n: g3.notes });
      const cx = await rt.cancelInvoice(T.admin, B.id, { reason: 'regression r3' });
      c.that('cancel B 200', cx.status === 200 && !cx.held, brief(cx));
      const p6 = await rt.patchInvoice(T.admin, B.id, { notes: 'after cancel' });
      c.that('edit of a cancelled invoice -> 400', p6.status === 400 && /cancelled/i.test(p6.text || ''), brief(p6));
      return done(c, { codeRef: BE.inv, evidence: `invoice A=${A.id} B=${B.id}` });
    });
  }

  if (ui) {
    await tcase({ id: 'REG-UI-40', feature: 'Invoice edit', kind: 'UI', prd: 'B2',
      title: 'Invoice page: a notes-only save sends {notes} only; a later terms change sends {paymentTerm} only (the saved notes are not sent again)',
      steps: 'fresh invoice (customer NET_30); admin #/invoices/{id}; type "r3 note" in Internal notes; Save changes (capture PATCH body); then Payment terms Net 30 -> Net 60; Save changes (capture PATCH body)',
      expected: 'F2 / APP-R1-2: PATCH #1 body exactly {notes:"r3 note"}; PATCH #2 body exactly {paymentTerm:"NET_60"}; both 200 with "Invoice saved"; server: notes "r3 note", NET_60, due +60' }, async (a) => {
      const c = checks();
      const cu = await customer(w, { prefix: 'UINotesOnly', invoices: [{ amount: 233 }] });
      const inv = cu.invoices[0];
      const app = await openAs('admin', `/invoices/${inv.id}`);
      const bodies = [];
      app.page.on('request', (r) => { if (r.method() === 'PATCH' && r.url().includes(`/api/invoices/${inv.id}`)) bodies.push(r.postData()); });
      const parse = (s) => { try { return JSON.parse(s || 'null'); } catch { return s; } };
      let shot = '';
      try {
        const { page } = app;
        await waitText(page, 'Save changes', 8000);
        const nodes = await rt.semantics(page);
        const box = nodes.find((n) => n.role === 'textbox' && /Internal notes/.test(`${n.label || ''} ${n.text || ''}`))
          || nodes.filter((n) => n.role === 'textbox').sort((p, q) => q.y - p.y)[0];
        c.that('notes box found', !!box, nodes.filter((n) => n.role === 'textbox').map((n) => n.label));
        if (box) {
          await page.mouse.click(box.x, box.y);
          await page.waitForTimeout(400);
          await page.keyboard.type('r3 note', { delay: 30 });
          await page.waitForTimeout(600);
          await rt.enableSemantics(page);
          await rt.tap(page, 'Save changes', { wait: 2500 });
          await page.waitForTimeout(1000);
          const b0 = parse(bodies[0]);
          c.that('PATCH #1 is exactly {notes}', bodies.length === 1 && b0 && JSON.stringify(Object.keys(b0)) === '["notes"]' && b0.notes === 'r3 note', bodies);
          const g1 = (await getJ(T.admin, `/api/invoices/${inv.id}`)).json || {};
          c.that('server: notes saved, terms unchanged', g1.notes === 'r3 note' && g1.paymentTerm === 'NET_30', { n: g1.notes, t: g1.paymentTerm });
          await rt.enableSemantics(page);
          await rt.tap(page, 'Net 30', { role: 'button', wait: 1500 });
          await rt.press(page, 'Net 60', { role: 'menuitem', wait: 1500 });
          await rt.tap(page, 'Save changes', { wait: 300 });
          // Harness (r3): the snackbar is looked for right after the tap; read after the API checks it had gone.
          const saved2 = !!(await waitText(page, /Invoice saved/, 4000));
          await page.waitForTimeout(1000);
          const b1 = parse(bodies[1]);
          c.that('PATCH #2 is exactly {paymentTerm: NET_60}', bodies.length === 2 && b1 && JSON.stringify(Object.keys(b1)) === '["paymentTerm"]' && b1.paymentTerm === 'NET_60', bodies);
          const g2 = (await getJ(T.admin, `/api/invoices/${inv.id}`)).json || {};
          c.that('server: NET_60, due +60, notes kept', g2.paymentTerm === 'NET_60' && g2.dueDate === rt.day(60) && g2.notes === 'r3 note', { t: g2.paymentTerm, d: g2.dueDate, n: g2.notes });
          c.that('"Invoice saved" shown after the terms save', saved2, (await allText(page)).slice(-300));
        }
      } catch (e) {
        c.that('UI steps completed', false, String(e.message || e).slice(0, 600));
      } finally {
        try { shot = await rt.shot(app.page, R.shotsDir, `REG-UI-40-${a}`); } catch { /* ignore */ }
        c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 5));
        await app.close();
      }
      return { ok: c.ok, actual: c.summary(), screenshot: shot, codeRef: FE.inv, evidence: `PATCH bodies=${JSON.stringify(bodies)}` };
    });

    // REG-UI-41: seen in r3 as REG-UI-23 attempt-1 failures -- the Subject's placeholder insert sometimes replaced the
    // subject already typed. Intermittent (a focus race), so this case repeats the insert and is not retried.
    await tcase({ id: 'REG-UI-41', feature: 'Email compose', kind: 'UI', prd: 'A4',
      title: 'Inserting a placeholder into the email Subject never replaces the subject already typed (repeated 6 times)',
      steps: '6x: fresh invoice; admin #/invoices/{id} -> Send email; tick "Customer emails"; focus Subject, select-all + Backspace, type "Hello "; Insert placeholder (Subject) -> Customer Name; read the subject the dialog previews (POST /api/emails/preview body.subject)',
      expected: 'A4: every time the subject becomes "Hello {{Customer.Name}}" (the token is put at the caret, which is at the end of the typed text)',
      severity: 'low' }, async (a) => {
      const c = checks(); const lost = []; let shot = '';
      for (let i = 0; i < 6; i++) {
        const cu = await customer(w, { prefix: 'UISubj', invoices: [{ amount: 100 }] });
        const app = await openAs('admin', `/invoices/${cu.invoices[0].id}`);
        const subs = [];
        app.page.on('request', (q) => { if (q.method() === 'POST' && q.url().endsWith('/api/emails/preview')) { try { subs.push(JSON.parse(q.postData()).subject); } catch { /* ignore */ } } });
        try {
          const { page } = app;
          await rt.tap(page, 'Send email', { role: 'button', wait: 3000 });
          await rt.tap(page, /^Customer emails/, { wait: 800 });
          await H.fill(page, 'Subject *', 'Hello ');
          const phs = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder').sort((p, q) => p.y - q.y);
          await rt.clickAt(page, phs[0].x, phs[0].y, 1500);
          await rt.press(page, 'Name', { role: 'menuitem', wait: 2500 });
          const got = subs[subs.length - 1];
          if (got !== 'Hello {{Customer.Name}}') { lost.push({ i, got, previews: subs }); shot = await rt.shot(page, R.shotsDir, `REG-UI-41-${a}-${i}`); }
        } catch (e) {
          lost.push({ i, error: String(e.message || e).slice(0, 200) });
        } finally { await app.close(); }
      }
      c.that('subject kept on all 6 inserts', lost.length === 0, lost);
      return { ok: c.ok, actual: c.summary(), screenshot: shot, severity: 'low',
        codeRef: 'frontend/lib/features/automation/placeholder_button.dart:91 (insertPlaceholder uses controller.selection); frontend/lib/features/email/send_email_dialog.dart:644 (single-line Subject TextFormField, selectAllOnFocus defaults true on web)',
        evidence: `lost=${JSON.stringify(lost)}` };
    }, { retry: false });
  }
};
