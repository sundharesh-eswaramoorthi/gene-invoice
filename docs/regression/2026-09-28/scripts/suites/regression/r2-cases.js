// Regression cases added in round 2, next to the round-1 fixes that touch pre-existing screens:
//  - RR-1 (B4): email placeholders filled "as the caller sees it" -- a sender who cannot open the record's customer
//    gets only Customer.Name and one warning; everyone else keeps the full text (EmailService.placeholders).
//  - APP-R1-1/2 (B3 + F2): an invoice edit carries only what changed; a terms-only edit never touches notes; the
//    invoice page sends only the changed fields and sends nothing when nothing changed.
//  - B1 (LOB reads now @Transactional): audit and dispute reads through derived finders on PostgreSQL.
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, customer, getJ, waitText, allText, openAs } = H;

const HIDDEN = "Customer details are left out: you cannot see this record's customer";
const BE = {
  rc: 'backend/src/main/java/com/geneinvoice/automation/action/RecordContexts.java:136',
  inv: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:218',
  audit: 'backend/src/main/java/com/geneinvoice/audit/AuditLogRepository.java',
  disp: 'backend/src/main/java/com/geneinvoice/dispute/DisputeRepository.java',
};
const FE = { inv: 'frontend/lib/features/invoices/invoice_detail_screen.dart:135' };

module.exports = async function r2Cases(w, R, only, { api = true, ui = true } = {}) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users;

  if (api) {
    await tcase({ id: 'REG-EML-07', feature: 'Email compose', kind: 'API', prd: 'A4',
      title: 'An email about a payment written by a collector who can no longer open its customer fills only the customer\'s name, with one warning; admin gets the full text',
      steps: 'customer K (Collection seat collbook, phone 555-0100, address "1 Test Way"); collbook POST /api/payments 100 for K; admin seats himself primary Collection POC and DELETEs collbook\'s seat; collbook GET /api/customers/K, GET /api/payments/p; collbook POST /api/emails/preview and POST /api/emails {entityType PAYMENT, to [USER collbook], subject "{{Customer.Name}} {{Customer.Phone}}", body "addr={{Customer.Address}} amt={{Payment.Amount}}"}; admin the same',
      expected: 'RR-1 / PC-10 / SC-1: collbook sees the payment (his book) but not K (404); preview 200 warnings contain the customer-hidden text exactly once; the saved email\'s subject is K\'s name without the phone, body "addr= amt=₹100.00"; admin\'s email "K 555-0100" / "addr=1 Test Way amt=₹100.00" with no such warning' }, async () => {
      const c = checks();
      const K = await customer(w, { prefix: 'HidCust', collection: U.collbook.id, invoices: [{ amount: 400 }] });
      const p = await rt.createPayment(T.collbook, { customerId: K.id, amount: 100, collectionPocUserId: U.collbook.id });
      rt.need(p, 'collbook payment', [200]);
      const pid = p.json.id;
      rt.need(await rt.api('POST', `/api/customers/${K.id}/pocs`, { token: T.admin, body: { pocType: 'COLLECTION', userId: U.admin.id, primary: true } }), 'admin seat');
      const pocs = await getJ(T.admin, `/api/customers/${K.id}/pocs`);
      const seat = (Array.isArray(pocs.json) ? pocs.json : []).find((s) => s.userId === U.collbook.id || (s.user && s.user.id === U.collbook.id));
      const del = seat ? await rt.api('DELETE', `/api/customers/${K.id}/pocs/${seat.id}`, { token: T.admin }) : { status: 0, text: brief(pocs) };
      c.that('collbook seat removed', del.status === 200 || del.status === 204, brief(del));
      const gc = await getJ(T.collbook, `/api/customers/${K.id}`);
      const gp = await getJ(T.collbook, `/api/payments/${pid}`);
      c.that('precondition: collbook sees his payment (200) but not K (404)', gp.status === 200 && gc.status === 404, { payment: gp.status, customer: gc.status });
      const body = { entityType: 'PAYMENT', entityId: pid, to: [rt.tok.user(U.collbook.id)],
        subject: '{{Customer.Name}} {{Customer.Phone}}', body: 'addr={{Customer.Address}} amt={{Payment.Amount}}' };
      const pv = await rt.api('POST', '/api/emails/preview', { token: T.collbook, body });
      const hid = ((pv.json && pv.json.warnings) || []).filter((x) => x === HIDDEN).length;
      c.that('collbook preview 200 with the hidden warning once', pv.status === 200 && hid === 1, brief(pv));
      const s = await rt.api('POST', '/api/emails', { token: T.collbook, body });
      c.that('collbook send 200', s.status === 200, brief(s));
      c.that('collbook subject = name only (no phone)', s.json && s.json.subject && s.json.subject.trim() === K.name && !/555-0100/.test(s.json.subject), s.json && s.json.subject);
      c.that('collbook body: address empty, amount filled', s.json && s.json.body === 'addr= amt=₹100.00', s.json && s.json.body);
      const apv = await rt.api('POST', '/api/emails/preview', { token: T.admin, body });
      c.that('admin preview: no hidden warning', apv.status === 200 && !((apv.json.warnings || []).includes(HIDDEN)), brief(apv));
      const as = await rt.api('POST', '/api/emails', { token: T.admin, body });
      c.that('admin email full text', as.status === 200 && as.json.subject === `${K.name} 555-0100` && as.json.body === 'addr=1 Test Way amt=₹100.00', as.json && { s: as.json.subject, b: as.json.body });
      const stored = await getJ(T.admin, `/api/emails/${s.json && s.json.id}`);
      c.that('collbook email persisted as rendered, not sent', stored.status === 200 && stored.json.body === 'addr= amt=₹100.00' && /NOT_SENT|NOT_CONFIGURED/.test(stored.json.status), brief(stored));
      return done(c, { codeRef: BE.rc, evidence: `payment ${pid}; preview warnings=${JSON.stringify(pv.json && pv.json.warnings)}` });
    });

    await tcase({ id: 'REG-INV-10', feature: 'Invoice edit', kind: 'API', prd: 'B2',
      title: 'A terms-only edit changes the terms and due date and leaves the notes alone (null stays null, text stays text); the history has no Notes change',
      steps: 'invoice A (no notes) and B (notes "keep me"), customer NET_30; PATCH A {paymentTerm NET_60}; PATCH B {paymentTerm NET_15}; GET both; GET /api/audit?entityType=INVOICE&entityId=A',
      expected: 'APP-R1-1/2: 200 applied (no threshold); A NET_60, due = invoice day + 60, notes still null/empty; B NET_15, due +15, notes "keep me"; A\'s audit rows mention the due date / terms and none mention notes' }, async () => {
      const c = checks();
      const cu = await customer(w, { prefix: 'TermsOnly', invoices: [{ amount: 150 }, { amount: 160, notes: 'keep me' }] });
      const [A, B] = cu.invoices;
      const pa = await rt.patchInvoice(T.admin, A.id, { paymentTerm: 'NET_60' });
      const pb = await rt.patchInvoice(T.admin, B.id, { paymentTerm: 'NET_15' });
      c.that('both 200, not held', pa.status === 200 && pb.status === 200 && !pa.held && !pb.held, [brief(pa), brief(pb)]);
      const ga = (await getJ(T.admin, `/api/invoices/${A.id}`)).json;
      const gb = (await getJ(T.admin, `/api/invoices/${B.id}`)).json;
      c.that('A NET_60 +60, notes untouched', ga.paymentTerm === 'NET_60' && ga.dueDate === rt.day(60) && (ga.notes === null || ga.notes === undefined || ga.notes === ''), { t: ga.paymentTerm, d: ga.dueDate, n: ga.notes });
      c.that('B NET_15 +15, notes "keep me"', gb.paymentTerm === 'NET_15' && gb.dueDate === rt.day(15) && gb.notes === 'keep me', { t: gb.paymentTerm, d: gb.dueDate, n: gb.notes });
      const au = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: A.id });
      const rows = Array.isArray(au.json) ? au.json : (au.json && au.json.content) || [];
      const later = rows.filter((r) => r.action !== 'INVOICE_CREATED');
      c.that('audit 200 with an update row', au.status === 200 && later.length >= 1, rows.map((r) => r.action));
      const txt = JSON.stringify(later);
      c.that('no Notes change recorded', !/"notes"|Notes/.test(txt), txt.slice(0, 400));
      return done(c, { codeRef: BE.inv });
    });

    await tcase({ id: 'REG-AUD-04', feature: 'Audit history', kind: 'API', prd: 'B3',
      title: 'Histories and dispute lists read on PostgreSQL after the LOB-transaction change: customer timeline with related records, a disputed invoice\'s history, the disputes list filtered by invoice',
      steps: 'customer (login) with invoice 300; customer opens a dispute; admin denies it; GET /api/audit?entityType=CUSTOMER&entityId&includeRelated=true; GET /api/audit?entityType=INVOICE&entityId; GET /api/disputes?filter=targetType:eq:INVOICE&filter=targetId:eq:<id>; GET /api/disputes/{id}; backend.log has no "lob stream" / "auto-commit" error',
      expected: 'all 200; the dispute appears in the list and its detail has the reason; the audit rows carry actor names; no LOB errors in the log' }, async () => {
      const c = checks();
      const cu = await customer(w, { prefix: 'AudLob', invoices: [{ amount: 300 }] });
      const ct = await rt.login(cu.username, cu.password);
      const reason = `lob check ${rt.uniq('r')}`;
      const d = await rt.createDispute(ct, { targetType: 'INVOICE', targetId: cu.invoices[0].id, reason });
      c.that('dispute 200', d.status === 200, brief(d));
      const did = d.json && d.json.id;
      const dn = await rt.api('POST', `/api/disputes/${did}/deny`, { token: T.admin, body: { adminNotes: 'no' } });
      c.that('deny 200', dn.status === 200, brief(dn));
      const a1 = await getJ(T.admin, '/api/audit', { entityType: 'CUSTOMER', entityId: cu.id, includeRelated: true });
      const a2 = await getJ(T.admin, '/api/audit', { entityType: 'INVOICE', entityId: cu.invoices[0].id });
      const rows1 = Array.isArray(a1.json) ? a1.json : (a1.json && a1.json.content) || [];
      c.that('customer timeline 200 with rows', a1.status === 200 && rows1.length >= 2, brief(a1));
      c.that('invoice history 200', a2.status === 200, brief(a2));
      const dl = await getJ(T.admin, '/api/disputes', { size: 10, filter: [`targetType:eq:INVOICE`, `targetId:eq:${cu.invoices[0].id}`] });
      c.that('disputes list has it', dl.status === 200 && H.ids(dl).includes(did), brief(dl));
      const dg = await getJ(T.admin, `/api/disputes/${did}`);
      c.that('dispute detail has reason, DENIED', dg.status === 200 && JSON.stringify(dg.json).includes(reason) && /DENIED|REJECTED/.test(dg.json.status), brief(dg));
      const log = require('fs').readFileSync(require('path').join(process.env.E2E_RUNTIME || '', 'backend.log'), 'utf8');
      const bad = log.split('\n').filter((l) => /lob stream|Large Objects may not be used/i.test(l));
      c.that('no LOB errors in backend.log', bad.length === 0, bad.slice(0, 2));
      return done(c, { codeRef: `${BE.audit}; ${BE.disp}`, evidence: 'grep -i "lob stream\\|Large Objects" runtime/regression/backend.log' });
    });
  }

  if (ui) {
    await tcase({ id: 'REG-UI-39', feature: 'Invoice edit', kind: 'UI', prd: 'B2',
      title: 'Invoice page sends only what changed: a terms-only save PATCHes paymentTerm without notes; a notes edit typed back to the saved text sends nothing ("Nothing has changed")',
      steps: 'fresh invoice without notes (customer NET_30); admin #/invoices/{id}; Payment terms Net 30 -> Net 45; Save changes (capture the PATCH body); type "x" in Notes then delete it; Save changes',
      expected: 'F2 / APP-R1-2: first PATCH body {paymentTerm: NET_45} with no "notes" key, 200; invoice notes still empty; second Save sends no PATCH and shows "Nothing has changed"' }, async (a) => {
      const c = checks();
      const cu = await customer(w, { prefix: 'UIOnlyChg', invoices: [{ amount: 212 }] });
      const inv = cu.invoices[0];
      const app = await openAs('admin', `/invoices/${inv.id}`);
      const bodies = [];
      app.page.on('request', (r) => { if (r.method() === 'PATCH' && r.url().includes(`/api/invoices/${inv.id}`)) bodies.push(r.postData()); });
      let shot = '';
      try {
        const { page } = app;
        await waitText(page, 'Save changes', 6000);
        await rt.tap(page, 'Net 30', { role: 'button', wait: 1500 });
        await rt.press(page, 'Net 45', { role: 'menuitem', wait: 1500 });
        await rt.tap(page, 'Save changes', { wait: 3000 });
        await page.waitForTimeout(1500);
        let b0 = null; try { b0 = JSON.parse(bodies[0] || 'null'); } catch { b0 = bodies[0]; }
        c.that('one PATCH with paymentTerm NET_45 and no notes key', bodies.length === 1 && b0 && b0.paymentTerm === 'NET_45' && !('notes' in b0), bodies);
        const g = (await getJ(T.admin, `/api/invoices/${inv.id}`)).json;
        c.that('saved NET_45, notes still empty', g.paymentTerm === 'NET_45' && !g.notes, { t: g.paymentTerm, n: g.notes });
        // Notes typed and then deleted: dirty, but equal to what is saved.
        const nodes = await rt.semantics(page);
        const box = nodes.find((n) => n.role === 'textbox' && /Internal notes/.test(`${n.label || ''} ${n.text || ''}`))
          || nodes.filter((n) => n.role === 'textbox').sort((p, q) => q.y - p.y)[0];
        c.that('notes box found', !!box, nodes.filter((n) => n.role === 'textbox').map((n) => n.label));
        if (box) {
          await page.mouse.click(box.x, box.y);
          await page.waitForTimeout(400);
          await page.keyboard.type('x', { delay: 30 });
          await page.waitForTimeout(400);
          await page.keyboard.press('Backspace');
          await page.waitForTimeout(600);
          await rt.enableSemantics(page);
          await rt.tap(page, 'Save changes', { wait: 1200 });
          c.that('snackbar "Nothing has changed"', !!(await waitText(page, /Nothing has changed/, 3000)), (await allText(page)).slice(-300));
          c.that('no second PATCH', bodies.length === 1, bodies);
        }
      } catch (e) {
        c.that('UI steps completed', false, String(e.message || e).slice(0, 600));
      } finally {
        try { shot = await rt.shot(app.page, R.shotsDir, `REG-UI-39-${a}`); } catch { /* ignore */ }
        c.that('no page exception', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
        c.that('no API error', app.apiErrors.length === 0, app.apiErrors.slice(0, 5));
        await app.close();
      }
      return { ok: c.ok, actual: c.summary(), screenshot: shot, codeRef: FE.inv, evidence: `PATCH bodies=${JSON.stringify(bodies)}` };
    });
  }
};
