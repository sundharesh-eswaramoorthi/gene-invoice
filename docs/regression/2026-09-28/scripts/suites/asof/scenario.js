// Builds the as-of world for the "asof" E2E area.
//
//   Phase A (then): seedWorld + extra records and changes, a snapshot of what every list/figure says.
//   ageWorld(AGE)  : everything so far moves AGE days into the past (history included).
//   Phase B (now) : region move, renames, new records, approvals decided, task done, rule revised...
//
// As of T = day(-2) the app must answer with the phase-A state; live it answers with the phase-B state.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');

const AGE = 10;

async function snapshot(tokens) {
  const a = tokens.admin;
  const s = {};
  const inv = rt.need(await rt.list(a, '/api/invoices'), 'snapshot invoices');
  s.invoices = Object.fromEntries(inv.content.map((i) => [i.id, { status: i.status, total: i.total, balance: i.balance, customerName: i.customerName, regionId: i.regionId, invoiceNumber: i.invoiceNumber }]));
  s.invoiceCount = inv.totalElements;
  s.invoiceSummary = rt.need(await rt.api('GET', '/api/invoices/summary', { token: a }), 'inv summary');
  const cus = rt.need(await rt.list(a, '/api/customers'), 'snapshot customers');
  s.customers = Object.fromEntries(cus.content.map((c) => [c.id, { name: c.name, regionId: c.regionId, outstanding: c.outstanding }]));
  s.customerCount = cus.totalElements;
  s.customerSummary = rt.need(await rt.api('GET', '/api/customers/summary', { token: a }), 'cust summary');
  const pay = rt.need(await rt.list(a, '/api/payments'), 'snapshot payments');
  s.paymentIds = pay.content.map((p) => p.id);
  s.dash = {
    billed: rt.need(await rt.api('GET', '/api/dashboard/billed-by-month', { token: a }), 'billed'),
    age: rt.need(await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: a }), 'age'),
    top: rt.need(await rt.api('GET', '/api/dashboard/top-outstanding-customers', { token: a, query: { limit: 10 } }), 'top'),
    collected: rt.need(await rt.api('GET', '/api/dashboard/collected-by-month', { token: a }), 'collected'),
  };
  s.approvalsSummary = rt.need(await rt.approvals.summary(a), 'approvals summary');
  s.tasksSummary = rt.need(await rt.api('GET', '/api/tasks/summary', { token: a }), 'tasks summary');
  s.noraInvoiceIds = (rt.need(await rt.list(tokens.nora, '/api/invoices'), 'nora inv')).content.map((i) => i.id).sort((x, y) => x - y);
  s.wendyInvoiceIds = (rt.need(await rt.list(tokens.wendy, '/api/invoices'), 'wendy inv')).content.map((i) => i.id).sort((x, y) => x - y);
  s.custInvoiceIds = (rt.need(await rt.list(tokens.cust, '/api/invoices'), 'cust inv')).content.map((i) => i.id).sort((x, y) => x - y);
  return s;
}

async function uploadDoc(token, entityType, entityId, name) {
  const fd = new FormData();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
  fd.append('file', new Blob([png], { type: 'image/png' }), `${name}.png`);
  fd.append('entityType', entityType);
  fd.append('entityId', String(entityId));
  fd.append('description', name);
  return rt.api('POST', '/api/documents', { token, body: fd, raw: true });
}

async function build({ log = console.log } = {}) {
  const admin = await rt.adminToken();
  const me = await rt.me(admin);
  const W = await rt.seedWorld(admin);
  const tag = W.customers.n1.name.split(' ').pop();
  const S = { tag, age: AGE, adminId: me.id, regions: {}, users: {}, customers: {}, invoices: {}, pcs: {}, tasks: {}, rules: {}, docs: {}, runs: {} };
  for (const [k, r] of Object.entries(W.regions)) S.regions[k] = { id: r.id, code: r.code, name: r.name };
  for (const [k, u] of Object.entries(W.users)) S.users[k] = { id: u.id, username: u.username, customerId: u.customerId };
  for (const [k, c] of Object.entries(W.customers)) S.customers[k] = { id: c.id, name: c.name, regionId: c.regionId };
  for (const [k, v] of Object.entries(W.invoices)) S.invoices[k] = v.map((i) => ({ id: i.id, number: i.invoiceNumber, total: i.total }));
  const T = W.tokens;
  const product = W.product;

  // ---------------- phase A (becomes "then") ----------------
  // X: a South invoice of 6000 (below South's 10000 create limit), whose cancel (6000 > 5000) is held: pcD.
  const x = await rt.createInvoice(admin, { customerId: W.customers.s2.id, amount: 6000, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: W.users.sam.id });
  rt.need(x, 'invoice X', [200]);
  S.invoiceX = { id: x.json.id, number: x.json.invoiceNumber };
  const cx = await rt.cancelInvoice(admin, S.invoiceX.id);
  if (!cx.held) throw new Error(`cancel of X was not held: ${cx.status} ${cx.text.slice(0, 300)}`);
  S.pcs.D = { id: cx.pendingChange.id, amount: 6000, op: 'INVOICE_CANCEL' };
  // pcA: a held create of a 20000 invoice on s2 (made by admin; sam approves it later).
  const ya = await rt.createInvoice(admin, { customerId: W.customers.s2.id, amount: 20000, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: W.users.sam.id });
  if (!ya.held) throw new Error(`invoice 20000 not held: ${ya.status} ${ya.text.slice(0, 300)}`);
  S.pcs.A = { id: ya.pendingChange.id, amount: 20000, op: 'INVOICE_CREATE' };
  // pcB: sam's held payment of 6000 on s1 (stays pending throughout).
  const pb = await rt.createPayment(T.sam, { customerId: W.customers.s1.id, amount: 6000, collectionPocUserId: W.users.sam.id });
  if (!pb.held) throw new Error(`payment 6000 not held: ${pb.status} ${pb.text.slice(0, 300)}`);
  S.pcs.B = { id: pb.pendingChange.id, amount: 6000, op: 'PAYMENT_RECORD' };
  // pcE (round 2): admin's held payment of 7000 on s1; sam REJECTS it after T (its note must not show as of T).
  // Round 3: it names s1's 2500 invoice (invoiceIds), so its as-of Changes row must read the invoice number (APP-R2-1).
  const pe = await rt.createPayment(admin, { customerId: W.customers.s1.id, amount: 7000, invoiceIds: [W.invoices.s1[1].id], collectionPocUserId: W.users.sam.id });
  if (!pe.held) throw new Error(`payment 7000 not held: ${pe.status} ${pe.text.slice(0, 300)}`);
  S.pcs.E = { id: pe.pendingChange.id, amount: 7000, op: 'PAYMENT_RECORD', note: `AsOf reject ${tag}`, invoiceId: W.invoices.s1[1].id, invoiceNumber: W.invoices.s1[1].invoiceNumber };
  // pcF (round 2, negative control): a held 12000 invoice on s1, approved by sam BEFORE T (decision must stay visible as of T).
  const pf = await rt.createInvoice(admin, { customerId: W.customers.s1.id, amount: 12000, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: W.users.sam.id });
  if (!pf.held) throw new Error(`invoice 12000 not held: ${pf.status} ${pf.text.slice(0, 300)}`);
  const apF = rt.need(await rt.approvals.approve(T.sam, pf.pendingChange.id, `AsOf early ${tag}`), 'approve pcF');
  if (apF.status !== 'APPROVED') throw new Error(`pcF not applied: ${JSON.stringify(apF).slice(0, 300)}`);
  S.pcs.F = { id: pf.pendingChange.id, amount: 12000, op: 'INVOICE_CREATE', note: `AsOf early ${tag}`, resultId: apF.result && apF.result.entityId };
  // Round 4 (one step around ASO-R3-1): a promise P1 of 1000 on s1 (applied, below 5000), then
  //   pcG: admin's held payment of 5500 on s1 naming P1 in promiseIds (Promises row as of T);
  //   pcH: admin's held promise of 6000 on s1 naming s1's 2500 invoice (PROMISE_CREATE; Invoices row as of T).
  // Both are withdrawn after T, so they are outstanding at T and not live.
  const p1 = await rt.createPromise(admin, { customerId: W.customers.s1.id, amount: 1000, promisedDate: rt.day(12), collectionPocUserId: W.users.sam.id, invoiceIds: [W.invoices.s1[0].id] });
  if (p1.status !== 200 && p1.status !== 201) throw new Error(`promise P1 -> ${p1.status} ${p1.text.slice(0, 300)}`);
  S.promiseP1 = { id: p1.json.id, amount: 1000 };
  const pg = await rt.createPayment(admin, { customerId: W.customers.s1.id, amount: 5500, promiseIds: [S.promiseP1.id], collectionPocUserId: W.users.sam.id });
  if (!pg.held) throw new Error(`payment 5500 not held: ${pg.status} ${pg.text.slice(0, 300)}`);
  S.pcs.G = { id: pg.pendingChange.id, amount: 5500, op: 'PAYMENT_RECORD', promiseId: S.promiseP1.id };
  const ph = await rt.createPromise(admin, { customerId: W.customers.s1.id, amount: 6000, promisedDate: rt.day(14), collectionPocUserId: W.users.sam.id, invoiceIds: [W.invoices.s1[1].id] });
  if (!ph.held) throw new Error(`promise 6000 not held: ${ph.status} ${ph.text.slice(0, 300)}`);
  S.pcs.H = { id: ph.pendingChange.id, amount: 6000, op: 'PROMISE_CREATE', invoiceId: W.invoices.s1[1].id, invoiceNumber: W.invoices.s1[1].invoiceNumber };
  S.product = { id: product.id, nameThen: product.name };

  // Tasks: T1 on n1's first invoice for nora (done later); T3 on n2's first invoice for nora (n2 leaves North later).
  const t1 = await rt.createTask(admin, { entityType: 'INVOICE', entityId: W.invoices.n1[0].id, title: `AsOf T1 ${tag}`, assignees: [rt.tok.user(W.users.nora.id)] });
  S.tasks.T1 = { id: rt.need(t1, 'task T1').id, title: `AsOf T1 ${tag}` };
  const t3 = await rt.createTask(admin, { entityType: 'INVOICE', entityId: W.invoices.n2[0].id, title: `AsOf T3 ${tag}`, assignees: [rt.tok.user(W.users.nora.id)] });
  S.tasks.T3 = { id: rt.need(t3, 'task T3').id, title: `AsOf T3 ${tag}` };

  // Rule R1 (MANUAL, INVOICE): v1 matches total == 6000 and UNPAID. Revised to v2 (7000) after ageing.
  const r1 = await rt.automation.create(admin, rt.rule({
    name: `AsOf Rule v1 ${tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' },
    condition: { op: 'AND', children: [{ filter: 'total:eq:6000' }, { filter: 'status:eq:UNPAID' }] },
    actions: [rt.action.task({ title: 'Rule task {{Invoice.Number}}', assignees: [rt.tok.user(me.id)] })],
  }));
  const r1j = rt.need(r1, 'rule R1');
  S.rules.R1 = { id: r1j.id, v1: `AsOf Rule v1 ${tag}`, v2: `AsOf Rule v2 ${tag}` };
  const run1 = await rt.automation.run(admin, r1j.id, {});
  const run1j = rt.need(run1, 'run R1', [200, 202]);
  S.runs.before = run1j.runId;
  await rt.waitForRun(admin, run1j.runId, { timeoutMs: 60000 });

  // Documents on n1: D1 uploaded now (deleted after ageing).
  const d1 = await uploadDoc(admin, 'CUSTOMER', W.customers.n1.id, `asof-D1-${tag}`);
  S.docs.D1 = rt.need(d1, 'doc D1').id;
  // An email on n1's first invoice before ageing.
  const e1 = await rt.api('POST', '/api/emails', { token: admin, body: { entityType: 'INVOICE', entityId: W.invoices.n1[0].id, to: [rt.tok.customer()], subject: `AsOf E1 ${tag}`, body: 'before' } });
  S.emailBefore = { status: e1.status, id: e1.json && e1.json.id, subject: `AsOf E1 ${tag}` };

  log('[scenario] phase A done; taking the "then" snapshot');
  S.then = await snapshot(T);
  // Pending changes as they were then (admin sees all).
  S.then.pendingIds = (rt.need(await rt.approvals.list(admin, { filter: 'status:eq:PENDING' }), 'pending then')).content.map((p) => p.id).sort((a, b) => a - b);
  S.then.auditN1Inv = (rt.need(await rt.api('GET', '/api/audit', { token: admin, query: { entityType: 'INVOICE', entityId: W.invoices.n1[0].id } }), 'audit')).length;

  // ---------------- age everything ----------------
  rt.ageWorld(AGE);
  log(`[scenario] aged the world by ${AGE} days`);

  // ---------------- phase B (now) ----------------
  // B1: move n2 North -> West (nora has nothing in West; wendy only West).
  const mv = await rt.moveCustomerRegion(admin, W.customers.n2.id, W.regions.WEST.id, 'as-of e2e move');
  rt.need(mv, 'move n2 to WEST');
  S.move = { moved: mv.json.moved, warnings: mv.json.warnings };
  // B2: rename n1.
  const n1 = rt.need(await rt.api('GET', `/api/customers/${W.customers.n1.id}`, { token: admin }), 'get n1');
  S.customers.n1.newName = `Renamed N1 ${tag}`;
  rt.need(await rt.api('PUT', `/api/customers/${n1.id}`, { token: admin, body: { name: S.customers.n1.newName, phone: n1.phone, email: n1.email, address: n1.address, paymentTerm: n1.paymentTerm } }), 'rename n1');
  // B3: move n1's first invoice due date later (North has no thresholds).
  const inv0 = rt.need(await rt.api('GET', `/api/invoices/${W.invoices.n1[0].id}`, { token: admin }), 'get n1 inv0');
  const pt = await rt.patchInvoice(admin, inv0.id, { dueDate: rt.day(45), paymentTerm: 'CUSTOM', version: inv0.version });
  if (pt.status !== 200) throw new Error(`patch n1 inv0 -> ${pt.status} ${pt.text.slice(0, 300)}`);
  S.patchedInvoice = { id: inv0.id, dueDateNow: rt.day(45), dueDateThen: inv0.dueDate };
  // B4: new North invoice N of 6000 (live only).
  const nN = await rt.createInvoice(admin, { customerId: W.customers.n1.id, amount: 6000, dueDate: rt.day(20), paymentTerm: 'CUSTOM', salesPocUserId: W.users.bob.id });
  rt.need(nN, 'invoice N', [200]);
  S.invoiceN = { id: nN.json.id, number: nN.json.invoiceNumber };
  // B5: a payment of 1000 on d1's 2500 invoice (live only).
  const pay = await rt.createPayment(admin, { customerId: W.customers.d1.id, amount: 1000, invoiceIds: [W.invoices.d1[1].id], collectionPocUserId: me.id });
  rt.need(pay, 'payment d1', [200]);
  S.paymentNow = { id: pay.json.id, invoiceId: W.invoices.d1[1].id };
  // B6/B7: sam approves pcD (X cancelled now) and pcA (invoice Y created now).
  const apD = rt.need(await rt.approvals.approve(T.sam, S.pcs.D.id, 'ok'), 'approve pcD');
  if (apD.status !== 'APPROVED') throw new Error(`pcD not applied: ${JSON.stringify(apD).slice(0, 300)}`);
  const apA = rt.need(await rt.approvals.approve(T.sam, S.pcs.A.id, 'ok'), 'approve pcA');
  if (apA.status !== 'APPROVED') throw new Error(`pcA not applied: ${JSON.stringify(apA).slice(0, 300)}`);
  S.invoiceY = { id: apA.result && apA.result.entityId };
  const rjE = rt.need(await rt.approvals.reject(T.sam, S.pcs.E.id, S.pcs.E.note), 'reject pcE');
  if (rjE.status !== 'REJECTED') throw new Error(`pcE not rejected: ${JSON.stringify(rjE).slice(0, 300)}`);
  // Round 4: withdraw pcG and pcH (admin made them); rename the product used by pcA's line.
  for (const k of ['G', 'H']) {
    const w = rt.need(await rt.approvals.withdraw(admin, S.pcs[k].id), `withdraw pc${k}`);
    if (w.status !== 'WITHDRAWN') throw new Error(`pc${k} not withdrawn: ${JSON.stringify(w).slice(0, 300)}`);
  }
  S.product.nameNow = `${S.product.nameThen} Renamed ${tag}`;
  const pr0 = rt.need(await rt.api('GET', `/api/products/${S.product.id}`, { token: admin }), 'get product');
  rt.need(await rt.api('PUT', `/api/products/${S.product.id}`, { token: admin, body: { name: S.product.nameNow, description: pr0.description, price: pr0.price, active: pr0.active } }), 'rename product');
  // A small s1 invoice (applied) for the customer login's live view.
  const s1small = await rt.createInvoice(admin, { customerId: W.customers.s1.id, amount: 300, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: W.users.sam.id });
  rt.need(s1small, 'invoice s1 300', [200]);
  S.invoiceS1Small = { id: s1small.json.id, number: s1small.json.invoiceNumber };
  // B9: new held change pcC now (invoice 15000 on s1).
  const pc = await rt.createInvoice(admin, { customerId: W.customers.s1.id, amount: 15000, dueDate: rt.day(25), paymentTerm: 'CUSTOM', salesPocUserId: W.users.sam.id });
  if (!pc.held) throw new Error(`invoice 15000 not held: ${pc.status}`);
  S.pcs.C = { id: pc.pendingChange.id, amount: 15000, op: 'INVOICE_CREATE' };
  // B10: T1 done; T2 new.
  const t1now = rt.need(await rt.api('GET', `/api/tasks/${S.tasks.T1.id}`, { token: admin }), 'get T1');
  rt.need(await rt.tasks.setStatus(admin, S.tasks.T1.id, 'DONE', t1now.version), 'T1 done');
  const t2 = await rt.createTask(admin, { entityType: 'INVOICE', entityId: W.invoices.n1[0].id, title: `AsOf T2 ${tag}`, assignees: [rt.tok.user(W.users.nora.id)] });
  S.tasks.T2 = { id: rt.need(t2, 'task T2').id, title: `AsOf T2 ${tag}` };
  // B11: R1 -> v2 (7000); R2 new.
  const r1now = rt.need(await rt.automation.get(admin, S.rules.R1.id), 'get R1');
  const upd = await rt.automation.update(admin, S.rules.R1.id, rt.rule({
    name: S.rules.R1.v2, trigger: { kind: 'MANUAL', entityType: 'INVOICE' },
    condition: { op: 'AND', children: [{ filter: 'total:eq:7000' }, { filter: 'status:eq:UNPAID' }] },
    actions: r1now.actions, version: r1now.version,
  }));
  rt.need(upd, 'update R1');
  const run2 = rt.need(await rt.automation.run(admin, S.rules.R1.id, {}), 'run R1 now', [200, 202]);
  S.runs.after = run2.runId;
  await rt.waitForRun(admin, run2.runId, { timeoutMs: 60000 });
  const r2 = await rt.automation.create(admin, rt.rule({
    name: `AsOf Rule R2 ${tag}`, trigger: { kind: 'MANUAL', entityType: 'INVOICE' },
    condition: { op: 'AND', children: [{ filter: 'total:gt:0' }] },
    actions: [rt.action.task({ title: 'R2 {{Invoice.Number}}', assignees: [rt.tok.user(me.id)] })],
  }));
  S.rules.R2 = { id: rt.need(r2, 'rule R2').id, name: `AsOf Rule R2 ${tag}` };
  // B12: D2 uploaded now; D1 deleted now.
  S.docs.D2 = rt.need(await uploadDoc(admin, 'CUSTOMER', W.customers.n1.id, `asof-D2-${tag}`), 'doc D2').id;
  const del = await rt.api('DELETE', `/api/documents/${S.docs.D1}`, { token: admin });
  S.docs.D1deleted = del.status;
  // B13: a SUCCESS seat on n1 now (nora).
  const seat = await rt.api('POST', `/api/customers/${W.customers.n1.id}/pocs`, { token: admin, body: { pocType: 'SUCCESS', userId: W.users.nora.id, primary: true } });
  S.seatNow = { status: seat.status };
  // B14: a promise and a dispute on d1 now.
  const pr = await rt.createPromise(admin, { customerId: W.customers.d1.id, amount: 200, collectionPocUserId: me.id, invoiceIds: [W.invoices.d1[0].id] });
  S.promiseNow = { status: pr.status, id: pr.json && pr.json.id };
  // Disputes are raised by the customer (admin holds no DISPUTE_CREATE).
  const d1tok = await rt.login(W.customers.d1.username);
  const dp = await rt.createDispute(d1tok, { targetType: 'INVOICE', targetId: W.invoices.d1[1].id, reason: `AsOf dispute ${tag}` });
  S.disputeNow = { status: dp.status, id: dp.json && dp.json.id };
  // An email on n1's first invoice now.
  const e2 = await rt.api('POST', '/api/emails', { token: admin, body: { entityType: 'INVOICE', entityId: W.invoices.n1[0].id, to: [rt.tok.customer()], subject: `AsOf E2 ${tag}`, body: 'after' } });
  S.emailAfter = { status: e2.status, id: e2.json && e2.json.id, subject: `AsOf E2 ${tag}` };

  S.tokens = { admin, nora: T.nora, sam: T.sam, bob: T.bob, wendy: T.wendy, cust: T.cust };
  S.custUser = W.users.cust.username;
  return S;
}

function save(S, file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(S, null, 2));
}

module.exports = { build, save, snapshot, uploadDoc, AGE };
