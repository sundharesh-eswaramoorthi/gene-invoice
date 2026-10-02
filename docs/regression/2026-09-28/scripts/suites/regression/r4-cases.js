// Regression cases added in round 4, one step around the RGR-R3-1 fix (placeholder_button.dart now captures the
// field's selection on pointer down and guards the caret for 1 s after the insert):
//  - REG-UI-42 (UI): in the manual Send email dialog, a placeholder goes in at a caret in the MIDDLE of the Subject
//    (not only at the end), typing straight on after the insert goes right after the token (the caret guard), a second
//    insert goes after the first, the multi-line Message takes a placeholder mid-text, and cancelling the menu leaves
//    the text alone. The email that is sent carries the rendered subject/body.
// The rule editor's own placeholder fields (action_cards.dart, email_action_dialog.dart) are covered by the
// automation area (AUT-UI-23), so they are not repeated here.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customer, waitText, openAs } = H;
const FE = 'frontend/lib/features/automation/placeholder_button.dart:104 (_onSelected / _guardCaret); frontend/lib/features/email/send_email_dialog.dart:380';

const active = (page) => page.evaluate(() => {
  const a = document.activeElement;
  return a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') ? { v: a.value, s: a.selectionStart, e: a.selectionEnd } : null;
});

module.exports = async function r4Cases(w, R, only, { api = true, ui = true } = {}) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens;

  if (api) {
    // One step around ASO-R3-1 / RGN-R3-1 (PendingChangeViews name look-ups and blockedReason were rewritten): the
    // single-region admin turns maker-checker on for DEFAULT and the live approval page still reads right.
    await tcase({ id: 'REG-APR-02', feature: 'Maker-checker in the single-region app', kind: 'API', prd: 'B2',
      title: 'Single region: with a DEFAULT payment threshold a Collection POC\'s payment is held; the live approval detail names the invoice by number, tells the maker it is his own request, lets the admin approve; loosening back is itself held and a second approver clears it',
      steps: 'admin PUT /api/regions/{DEFAULT}/approval-thresholds [{PAYMENT_RECORD 500, 24h}]; fresh customer + invoice 800; coll POST /api/payments {amount 600, invoiceIds [inv]}; GET /api/approvals/{id} as coll, as admin; viewer GET /api/approvals/for-record?entityType=CUSTOMER; GET invoice; admin approve; GET invoice; admin PUT thresholds [{PAYMENT_RECORD, amount null}]; a second ADMIN-role user with APPROVE in DEFAULT approves that change; GET thresholds',
      expected: 'DESIGN 6.11 + B2: tighten 200; payment 202 PENDING_APPROVAL; detail 200 with changes row label "Invoices" proposedText = invoice number (no promiseIds row); coll blockedReason "You requested this change", canApprove false; admin blockedReason null, canApprove true; the viewer sees the pending change; invoice balance 800 until approved; approve 200 APPROVED; invoice paid 600 / balance 200; loosening 202 held (THRESHOLD_CHANGE); the second approver approves -> DEFAULT has no thresholds' }, async () => {
      const c = checks();
      const D = w.D;
      const t1 = await rt.setThresholds(T.admin, D, [{ operation: 'PAYMENT_RECORD', amount: 500, windowHours: 24 }]);
      c.that('tighten 200', t1.status === 200 && !t1.held, H.brief(t1));
      let pcId = null; let relaxId = null;
      try {
        const cu = await customer(w, { prefix: 'HeldPay', invoices: [{ amount: 800 }] });
        const inv = cu.invoices[0];
        const p = await rt.createPayment(T.coll, { customerId: cu.id, amount: 600, invoiceIds: [inv.id], collectionPocUserId: w.users.coll.id });
        c.that('payment held 202', p.status === 202 && p.held && p.pendingChange, H.brief(p));
        pcId = p.pendingChange && p.pendingChange.id;
        if (pcId) {
          const dm = await rt.approvals.get(T.coll, pcId);
          const rows = (dm.json && dm.json.changes) || [];
          const invRow = rows.find((r) => r.field === 'invoiceIds');
          c.that('detail 200; Invoices row reads the invoice number; no promiseIds row',
            dm.status === 200 && invRow && invRow.label === 'Invoices' && invRow.proposedText === inv.invoiceNumber && !rows.some((r) => r.field === 'promiseIds'),
            { s: dm.status, rows: rows.map((r) => [r.field, r.label, r.proposedText]) });
          c.that('maker: "You requested this change", cannot approve', dm.json && dm.json.blockedReason === 'You requested this change' && dm.json.canApprove === false, dm.json && [dm.json.blockedReason, dm.json.canApprove]);
          const da = await rt.approvals.get(T.admin, pcId);
          c.that('admin: blockedReason null, can approve', da.status === 200 && da.json.blockedReason === null && da.json.canApprove === true, da.json && [da.json.blockedReason, da.json.canApprove]);
          const fv = await rt.approvals.forRecord(T.viewer, 'CUSTOMER', cu.id);
          c.that('viewer (READ) sees the pending change on the customer', fv.status === 200 && Array.isArray(fv.json) && fv.json.some((b) => b.id === pcId), H.brief(fv));
          const g0 = (await H.getJ(T.admin, `/api/invoices/${inv.id}`)).json || {};
          c.that('invoice untouched while pending', Number(g0.balance) === 800, { paid: g0.paidAmount, bal: g0.balance, st: g0.status });
          const ap = await rt.approvals.approve(T.admin, pcId, 'r4 regression');
          c.that('approve 200 APPROVED', ap.status === 200 && ap.json.status === 'APPROVED', H.brief(ap));
          const g1 = (await H.getJ(T.admin, `/api/invoices/${inv.id}`)).json || {};
          c.that('invoice now paid 600, balance 200', Number(g1.balance) === 200, { paid: g1.paidAmount, bal: g1.balance, st: g1.status });
        }
      } finally {
        // Harness (r4): PUT leaves unlisted operations unchanged; a null amount deletes (DESIGN 6.11). Only ADMIN holds
        // CHANGE_APPROVE, so the loosening is approved by a second ADMIN-role user with APPROVE in DEFAULT.
        const t2 = await rt.setThresholds(T.admin, D, [{ operation: 'PAYMENT_RECORD', amount: null }]);
        // Harness (r4): a threshold 202 has the GET shape plus pendingChange (DESIGN 6.11), not status PENDING_APPROVAL, so lib's .held is false.
        c.that('loosening back is held 202 (THRESHOLD_CHANGE)', t2.status === 202 && t2.pendingChange && t2.pendingChange.operation === 'THRESHOLD_CHANGE', H.brief(t2));
        relaxId = t2.pendingChange && t2.pendingChange.id;
        if (relaxId) {
          const a2 = await rt.createStaff(T.admin, 'ADMIN', 'rappr', { grants: [{ regionId: D, accessLevel: 'APPROVE', recordScope: 'ALL' }] });
          const ta2 = await rt.login(a2.username, a2.password);
          const ca = await rt.approvals.approve(ta2, relaxId, 'r4 regression cleanup');
          c.that('a second approver approves the loosening', ca.status === 200 && ca.json.status === 'APPROVED', H.brief(ca));
        }
        const th = await rt.getThresholds(T.admin, D);
        const live = ((th.json && th.json.thresholds) || []).filter((x) => x.amount !== null && x.amount !== undefined);
        c.that('DEFAULT has no thresholds again', th.status === 200 && live.length === 0, H.brief(th));
      }
      return { ok: c.ok, actual: c.summary(), evidence: `pendingChange=${pcId} thresholdChange=${relaxId}`,
        codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeViews.java' };
    });
  }

  if (ui) {
    await tcase({ id: 'REG-UI-42', feature: 'Email compose', kind: 'UI', prd: 'A4',
      title: 'Send email: a placeholder goes in at a mid-text caret of the Subject and the Message, a second pick follows the first, cancelling the menu changes nothing, and typing on after clicking back keeps the text',
      steps: 'fresh invoice; admin #/invoices/{id} -> Send email; tick "Customer emails"; Subject "Dear  team", caret after "Dear " (End + 5x ArrowLeft) -> Insert placeholder (Subject) > Customer Name; then without touching the field Insert placeholder (Subject) > Invoice Number; open the Subject menu and dismiss it; click back at the right end of Subject, End, type "!"; Message "Invoice  is due", caret after "Invoice " (End + 7x ArrowLeft) -> Insert placeholder (Message) > Invoice Number; Send (field values read from the POST /api/emails/preview the dialog makes)',
      expected: 'A4 + TRIAGE r3 RGR-R3-1 (placeholder at the caret the field had when the button was pressed): Subject "Dear {{Customer.Name}} team", then "Dear {{Customer.Name}}{{Invoice.Number}} team"; cancel changes nothing; "!" appended -> "...team!"; Message "Invoice {{Invoice.Number}} is due"; saved email "Dear <customer><number> team!" / "Invoice <number> is due", status NOT_SENT/NOT_CONFIGURED',
      severity: 'low' }, async (a) => {
      const c = checks(); let shot = '';
      const cu = await customer(w, { prefix: 'UISubjMid', invoices: [{ amount: 100 }] });
      const inv = cu.invoices[0];
      const app = await openAs('admin', `/invoices/${inv.id}`);
      const { page } = app;
      const value = async (label) => ((await rt.semantics(page)).find((n) => n.role === 'textbox' && n.label === label) || {}).text;
      const phButton = async (idx) => (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder' || n.label === 'Insert placeholder').sort((p, q) => p.y - q.y)[idx];
      const pick = async (idx, item) => {
        const b = await phButton(idx);
        await rt.clickAt(page, b.x, b.y, 1500);
        await rt.press(page, item, { role: 'menuitem', wait: 1800 });
      };
      const typed = async (label, text) => {
        for (let k = 0; k < 2; k++) {
          await H.fill(page, label, text);
          if ((await value(label)) === text) return true;
        }
        return false;
      };
      const trace = {};
      // The field's value: the semantics tree only carries it while the field is focused, so the value is read
      // from the preview the dialog asks for after every insert/keystroke (POST /api/emails/preview).
      const previews = [];
      page.on('request', (q) => { if (q.method() === 'POST' && q.url().endsWith('/api/emails/preview')) { try { const b = JSON.parse(q.postData()); previews.push({ s: b.subject, b: b.body }); } catch { /* ignore */ } } });
      const lastSubject = async () => { await page.waitForTimeout(900); return previews.length ? previews[previews.length - 1].s : undefined; };
      const lastBody = async () => { await page.waitForTimeout(900); return previews.length ? previews[previews.length - 1].b : undefined; };
      try {
      await rt.tap(page, 'Send email', { role: 'button', wait: 3000 });
      await rt.tap(page, /^Customer emails/, { wait: 800 });
      // 1. mid-text caret in the single-line Subject
      c.that('subject typed', await typed('Subject *', 'Dear  team'));
      await page.keyboard.press('End');
      for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(400);
      trace.caretBefore = await active(page);
      await pick(0, 'Name');
      const s1 = await lastSubject();
      c.that('mid-text insert: "Dear {{Customer.Name}} team"', s1 === 'Dear {{Customer.Name}} team', s1);
      // 2. a second pick without touching the field goes right after the first
      await pick(0, 'Number');
      const s2 = await lastSubject();
      const want2 = 'Dear {{Customer.Name}}{{Invoice.Number}} team';
      c.that(`second insert follows the first: "${want2}"`, s2 === want2, s2);
      // 3. open the menu and cancel it: nothing changes
      const nPrev = previews.length;
      const b = await phButton(0);
      await rt.clickAt(page, b.x, b.y, 1500);
      await rt.dismiss(page);
      await page.waitForTimeout(800);
      await rt.enableSemantics(page);
      c.that('cancelled menu leaves the subject unchanged (no new preview, same text)', previews.length === nPrev || (await lastSubject()) === want2, previews.slice(nPrev));
      // 4. click back into the Subject (right end), type on: the text is kept and "!" goes at the end
      const sf = await rt.find(page, 'Subject *', { role: 'textbox' });
      await page.mouse.click(sf.x + sf.w / 2 - 12, sf.y);
      await page.waitForTimeout(600);
      trace.clickedBack = await active(page);
      await page.keyboard.press('End');
      await page.keyboard.type('!', { delay: 30 });
      await page.waitForTimeout(600);
      trace.afterBang = await active(page);
      const s4 = await lastSubject();
      c.that(`typing on after clicking back keeps the text: "${want2}!"`, s4 === `${want2}!` || (trace.afterBang && trace.afterBang.v === `${want2}!`), { preview: s4, dom: trace.afterBang });
      // 5. multi-line Message, mid-text
      c.that('message typed', await typed('Message', 'Invoice  is due'));
      await page.keyboard.press('End');
      for (let i = 0; i < 7; i++) await page.keyboard.press('ArrowLeft');
      await page.waitForTimeout(400);
      await pick(1, 'Number');
      const m1 = await lastBody();
      c.that('message mid-text insert: "Invoice {{Invoice.Number}} is due"', m1 === 'Invoice {{Invoice.Number}} is due', m1);
      c.that('subject still intact after the message insert', (await lastSubject()) === `${want2}!`, await lastSubject());
      shot = await rt.shot(page, R.shotsDir, `REG-UI-42-${a}`);
      await rt.tap(page, 'Send', { role: 'button', wait: 3500 });
      const req = await H.waitReq(app, (r) => r.method === 'POST' && r.url === '/api/emails', 5000);
      c.that('POST /api/emails 200', req && req.status === 200, req);
      const l = await rt.emailsFor(T.admin, 'INVOICE', inv.id);
      const e = ((l.json && l.json.content) || [])[0];
      c.that('saved email rendered',
        e && e.subject.trim() === `Dear ${cu.name}${inv.invoiceNumber} team!` && e.body.trim() === `Invoice ${inv.invoiceNumber} is due` && /NOT_SENT|NOT_CONFIGURED/.test(e.status),
        e && { s: e.subject, b: e.body, st: e.status });
      trace.previews = previews.map((p) => p.s + ' | ' + p.b);
      } catch (e) {
        c.that('UI steps completed', false, String(e.message || e).slice(0, 600));
        try { shot = await rt.shot(page, R.shotsDir, `REG-UI-42-${a}-err`); } catch { /* ignore */ }
      } finally {
        c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        await app.close();
      }
      return { ok: c.ok, actual: c.summary(), screenshot: shot, severity: 'low', codeRef: FE, evidence: `invoice ${inv.id}; caret trace ${JSON.stringify(trace)}` };
    });
  }
};
