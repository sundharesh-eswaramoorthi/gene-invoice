// Regression API cases, part 3: rights of the pre-existing roles (VIEWER, CASHIER, POCs, customer login) in a
// single region, POC endpoints, bulk email with per-row placeholders, and POC-scoped exports / bulk "select all".
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, done, brief, ids, num, customer, invoice, payment, getJ } = H;

const BE = {
  scope: 'backend/src/main/java/com/geneinvoice/poc/ScopeResolver.java',
  pocCtl: 'backend/src/main/java/com/geneinvoice/poc/PocController.java',
  cust: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java',
  email: 'backend/src/main/java/com/geneinvoice/email/EmailService.java',
  invCtl: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java',
  grants: 'backend/src/main/java/com/geneinvoice/region/RegionGrantService.java',
  disp: 'backend/src/main/java/com/geneinvoice/dispute/DisputeService.java',
  doc: 'backend/src/main/java/com/geneinvoice/document/DocumentTargets.java',
  schemas: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java',
};

module.exports = async function apiExtra(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const T = w.tokens; const U = w.users; const D = w.D;

  await tcase({ id: 'REG-RGT-01', feature: 'Role rights (VIEWER)', kind: 'API', prd: 'B1',
    title: 'A VIEWER (READ in DEFAULT) reads every list but every write is refused',
    steps: 'viewer GET /api/customers, /api/invoices, /api/payments, /api/promises; POST /api/customers, /api/invoices, /api/payments, /api/promises; PATCH an invoice',
    expected: 'lists 200 (the VIEWER role holds SCOPE_OVERRIDE -> same totals as admin); every write 403 (no MANAGE privilege / READ grant)' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Viewer', invoices: [{ amount: 100 }] });
    for (const p of ['/api/customers', '/api/invoices', '/api/payments', '/api/promises']) {
      const v = await getJ(T.viewer, p, { size: 10 }); const a = await getJ(T.admin, p, { size: 10 });
      c.that(`${p} 200 same total`, v.status === 200 && v.json.totalElements === a.json.totalElements, { viewer: v.json && v.json.totalElements, admin: a.json.totalElements });
    }
    const w1 = await rt.api('POST', '/api/customers', { token: T.viewer, body: { name: 'x', username: rt.uniq('v'), password: rt.PASSWORD, regionId: D } });
    const w2 = await rt.createInvoice(T.viewer, { customerId: cu.id, amount: 5, productId: w.product.id, salesPocUserId: U.sales.id });
    const w3 = await rt.createPayment(T.viewer, { customerId: cu.id, amount: 5, collectionPocUserId: U.coll.id });
    const w4 = await rt.createPromise(T.viewer, { customerId: cu.id, amount: 5, collectionPocUserId: U.coll.id });
    const w5 = await rt.patchInvoice(T.viewer, cu.invoices[0].id, { notes: 'x' });
    for (const [n, r] of [['customer', w1], ['invoice', w2], ['payment', w3], ['promise', w4], ['invoice edit', w5]]) c.that(`${n} write 403`, r.status === 403, brief(r));
    return done(c, { codeRef: BE.scope });
  });

  await tcase({ id: 'REG-RGT-02', feature: 'Role rights (CASHIER)', kind: 'API', prd: 'B1',
    title: 'The seeded cashier keeps day-to-day billing: creates a customer without choosing a region, invoices it and records a payment',
    steps: 'cashier POST /api/customers (no regionId); POST /pocs COLLECTION coll; POST /api/invoices (Sales POC sales); POST /api/payments',
    expected: 'DESIGN §4.1/§6.4: customer 200 in DEFAULT; invoice 200; payment 200 and the invoice FULLY_PAID; cashier cannot open /api/users (403)' }, async () => {
    const c = checks();
    const username = rt.uniq('cashc');
    const cu = await rt.api('POST', '/api/customers', { token: T.cashier, body: { name: `Cashier ${username}`, username, password: rt.PASSWORD, paymentTerm: 'NET_30' } });
    c.that('customer 200 DEFAULT', cu.status === 200 && cu.json.regionId === D, brief(cu));
    const id = cu.json && cu.json.id;
    const seat = await rt.api('POST', `/api/customers/${id}/pocs`, { token: T.cashier, body: { pocType: 'COLLECTION', userId: U.coll.id, primary: true } });
    c.that('seat 200', seat.status === 200, brief(seat));
    const inv = await rt.createInvoice(T.cashier, { customerId: id, amount: 640, productId: w.product.id, salesPocUserId: U.sales.id });
    c.that('invoice 200', inv.status === 200, brief(inv));
    const p = await rt.createPayment(T.cashier, { customerId: id, amount: 640, collectionPocUserId: U.coll.id });
    c.that('payment 200 paid', p.status === 200 && p.json.invoices[0] && p.json.invoices[0].status === 'FULLY_PAID', brief(p));
    const us = await getJ(T.cashier, '/api/users');
    c.that('users 403', us.status === 403, brief(us));
    return done(c, { codeRef: BE.cust });
  });

  await tcase({ id: 'REG-RGT-03', feature: 'POC endpoints', kind: 'API', prd: 'B1',
    title: 'POC endpoints keep answering for a single region: my-scope, assignable, assignable-check, types',
    steps: 'sales GET /api/pocs/my-scope; /api/pocs/assignable?type=SALES&q=<sales name>; /api/invoices/assignable-check; /api/pocs/assignable?type=COLLECTION&customerId=<c>; /api/pocs/types',
    expected: 'DESIGN §6.8: my-scope {clearable false, sales true, collection false, regions:[{DEFAULT, sales true}]}; assignable SALES includes sales; assignable-check {sales:true}; assignable COLLECTION for a customer lists coll and not sales; types lists SALES callerIsAssignable true' }, async () => {
    const c = checks();
    const ms = await getJ(T.sales, '/api/pocs/my-scope');
    c.that('my-scope', ms.status === 200 && ms.json.clearable === false && ms.json.sales === true && ms.json.collection === false && ms.json.regions.length === 1 && ms.json.regions[0].code === 'DEFAULT' && ms.json.regions[0].sales === true, ms.json);
    const as = await getJ(T.sales, '/api/pocs/assignable', { type: 'SALES', q: U.sales.fullName });
    c.that('assignable SALES has sales', as.status === 200 && (as.json || []).some((u) => u.id === U.sales.id), brief(as));
    const ck = await getJ(T.sales, '/api/invoices/assignable-check');
    c.that('assignable-check true', ck.status === 200 && ck.json.sales === true, brief(ck));
    const cu = await customer(w, { prefix: 'PocEp' });
    const ac = await getJ(T.admin, '/api/pocs/assignable', { type: 'COLLECTION', customerId: cu.id, limit: 25, q: 'Reg Coll' });
    c.that('assignable COLLECTION for customer', ac.status === 200 && (ac.json || []).some((u) => u.id === U.coll.id) && !(ac.json || []).some((u) => u.id === U.sales.id), brief(ac));
    const ty = await getJ(T.sales, '/api/pocs/types');
    c.that('types', ty.status === 200 && (ty.json || []).some((t) => t.type === 'SALES' && t.callerIsAssignable === true), brief(ty));
    return done(c, { codeRef: BE.pocCtl });
  });

  await tcase({ id: 'REG-RGT-04', feature: 'POC book (export & bulk)', kind: 'API', prd: 'B1',
    title: 'A Sales POC\'s export and bulk "select all" stay inside his book',
    steps: 'customer with invoices A (sales) and B (admin); sales POST /api/invoices/export {selectAllMatchingFilter, filters [customerId:eq]}; sales POST /api/invoices/bulk {CANCEL, selectAllMatchingFilter, filters [customerId:eq]}',
    expected: 'export has A only; bulk cancels A only (B stays UNPAID): sets honour the book (§5.6.5)' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'PocBulk', invoices: [{ amount: 51 }, { amount: 52, sales: U.admin.id }] });
    const [A, B] = cu.invoices;
    const f = [`customerId:eq:${cu.id}`];
    const e = await rt.api('POST', '/api/invoices/export', { token: T.sales, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: f } });
    c.that('export A only', e.status === 200 && e.text.includes(A.invoiceNumber) && !e.text.includes(B.invoiceNumber), brief(e));
    const b = await rt.api('POST', '/api/invoices/bulk', { token: T.sales, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: f } });
    c.that('bulk 200 A only', b.status === 200 && b.json.succeeded.join() === String(A.id), brief(b));
    const gb = await getJ(T.admin, `/api/invoices/${B.id}`);
    c.that('B untouched', gb.json.status === 'UNPAID', gb.json.status);
    return done(c, { codeRef: BE.invCtl });
  });

  await tcase({ id: 'REG-RGT-05', feature: 'POC book (records)', kind: 'API', prd: 'B1',
    title: 'Outside his book a Sales POC gets 404 on the invoice\'s documents, emails and email context; he cannot resolve disputes',
    steps: 'invoice B (Sales POC admin) with a document; sales GET /api/documents?entityType=INVOICE&entityId=B; GET /api/emails/context?entityType=INVOICE&entityId=B; POST /api/emails on B; customer dispute on his own invoice A -> sales POST /api/disputes/{id}/approve',
    expected: '404 for B\'s documents / context / email send (not visible); dispute approve 403 (SALES_POC has no DISPUTE_MANAGE)' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'PocOut', invoices: [{ amount: 61 }, { amount: 62, sales: U.admin.id }] });
    const [A, B] = cu.invoices;
    rt.need(await H.upload(T.admin, { entityType: 'INVOICE', entityId: B.id }), 'doc');
    const d = await getJ(T.sales, '/api/documents', { entityType: 'INVOICE', entityId: B.id });
    c.that('documents 404', d.status === 404, brief(d));
    const ctx = await getJ(T.sales, '/api/emails/context', { entityType: 'INVOICE', entityId: B.id });
    c.that('email context 404', ctx.status === 404, brief(ctx));
    const s = await rt.api('POST', '/api/emails', { token: T.sales, body: { entityType: 'INVOICE', entityId: B.id, to: [rt.tok.customer()], subject: 's', body: 'b' } });
    c.that('email send 404', s.status === 404, brief(s));
    const ct = await rt.login(cu.username, cu.password);
    const dp = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: A.id, proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'n' }) }), 'dispute');
    const ap = await rt.api('POST', `/api/disputes/${dp.id}/approve`, { token: T.sales, body: {} });
    c.that('approve 403', ap.status === 403, brief(ap));
    return done(c, { codeRef: BE.doc });
  });

  await tcase({ id: 'REG-RGT-06', feature: 'Customer login', kind: 'API', prd: 'B1',
    title: 'A customer login keeps its self-service view: own records only, OWN dashboard, no staff screens, regionIds ignored',
    steps: 'customer login GET /api/customers, /api/invoices, /api/invoices?regionIds=999, /api/dashboard/billed-by-month, another customer\'s /api/customers/{id} and /api/invoices/{id}; /api/users, /api/approvals, /api/tasks',
    expected: 'DESIGN §5.6.4 (customer login: today\'s predicates, ViewScope ignored): list of 1 customer (itself), its invoices only (regionIds=999 changes nothing), dashboard coverage OWN; other customer 403/404; other invoice 403/404; users/approvals/tasks 403' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'CustL', invoices: [{ amount: 91 }, { amount: 92 }] });
    const other = await customer(w, { prefix: 'CustO', invoices: [{ amount: 93 }] });
    const ct = await rt.login(cu.username, cu.password);
    const cs = await getJ(ct, '/api/customers', { size: 10 });
    c.that('customers = itself', cs.status === 200 && ids(cs).join() === String(cu.id), brief(cs));
    const iv = await getJ(ct, '/api/invoices', { size: 10 });
    c.that('own invoices only', iv.status === 200 && ids(iv).sort().join() === cu.invoices.map((i) => i.id).sort().join(), ids(iv));
    const ir = await getJ(ct, '/api/invoices', { size: 10, regionIds: 999 });
    c.that('regionIds ignored', ir.status === 200 && ir.json.totalElements === 2, brief(ir));
    const db = await getJ(ct, '/api/dashboard/billed-by-month');
    c.that('dashboard OWN', db.status === 200 && db.json.coverage === 'OWN', db.json && db.json.coverage);
    const oc = await getJ(ct, `/api/customers/${other.id}`);
    const oi = await getJ(ct, `/api/invoices/${other.invoices[0].id}`);
    c.that('other customer refused', oc.status === 403 || oc.status === 404, brief(oc));
    c.that('other invoice refused', oi.status === 403 || oi.status === 404, brief(oi));
    for (const p of ['/api/users', '/api/approvals', '/api/tasks']) { const r = await getJ(ct, p); c.that(`${p} 403`, r.status === 403, brief(r)); }
    return done(c, { codeRef: BE.scope });
  });

  await tcase({ id: 'REG-RGT-07', feature: 'Users admin (grants)', kind: 'API', prd: 'B1',
    title: 'Admin edits another user\'s DEFAULT access (MANAGE -> READ) and that user loses write rights; nobody edits their own',
    steps: 'fresh CASHIER user (DEFAULT MANAGE/ALL) creates a customer OK; admin PUT /api/users/{id}/region-grants [{DEFAULT, READ, ALL}]; the user tries another customer; admin PUT own grants',
    expected: 'DESIGN §6.3: PUT 200 returning the READ row; the user\'s next write 403 (reads still 200); own grants 400 "You cannot change your own region access"' }, async () => {
    const c = checks();
    const u = await rt.createStaff(T.admin, 'CASHIER', 'rgr', { grants: [{ regionId: D, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const t = await rt.login(u.username);
    const ok = await rt.api('POST', '/api/customers', { token: t, body: { name: rt.uniq('G'), username: rt.uniq('g'), password: rt.PASSWORD } });
    c.that('write while MANAGE 200', ok.status === 200, brief(ok));
    const p = await rt.setGrants(T.admin, u.id, [{ regionId: D, accessLevel: 'READ', recordScope: 'ALL' }]);
    c.that('PUT grants 200 READ', p.status === 200 && JSON.stringify(p.json).includes('"level":"READ"'), brief(p));
    const t2 = await rt.login(u.username);
    const no = await rt.api('POST', '/api/customers', { token: t2, body: { name: rt.uniq('G'), username: rt.uniq('g'), password: rt.PASSWORD, regionId: D } });
    c.that('write after READ 403', no.status === 403, brief(no));
    const rd = await getJ(t2, '/api/customers', { size: 10 });
    c.that('reads still 200', rd.status === 200 && rd.json.totalElements > 0, brief(rd));
    const self = await rt.setGrants(T.admin, U.admin.id, [{ regionId: D, accessLevel: 'READ', recordScope: 'ALL' }]);
    c.that('own grants 400', self.status === 400 && /own region access/.test(self.json && self.json.message), brief(self));
    return done(c, { codeRef: BE.grants });
  });

  await tcase({ id: 'REG-EML-05', feature: 'Email bulk', kind: 'API', prd: 'A4',
    title: 'Bulk email from the invoices list fills placeholders per row and saves one email per invoice',
    steps: 'customer with invoices X (111) and Y (222); POST /api/emails/bulk {action SEND_EMAIL, ids [X,Y], params:{entityType INVOICE, to [CUSTOMER], subject "{{Invoice.Number}} due", body "Balance {{Invoice.Balance}}"}}',
    expected: 'A4 / §5.10.4: 200 succeeded [X,Y]; X\'s email subject "<X number> due", body "Balance ₹111.00"; Y\'s "<Y number> due", "Balance ₹222.00"; both end NOT_SENT (after QUEUED)' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'BulkMail', invoices: [{ amount: 111 }, { amount: 222 }] });
    const [X, Y] = cu.invoices;
    const r = await rt.api('POST', '/api/emails/bulk', { token: T.admin, body: { action: 'SEND_EMAIL', ids: [X.id, Y.id], params: { entityType: 'INVOICE', to: [rt.tok.customer()], subject: '{{Invoice.Number}} due', body: 'Balance {{Invoice.Balance}}' } } });
    c.that('200 both', r.status === 200 && r.json.succeeded.length === 2, brief(r));
    for (const [inv, amt] of [[X, '111.00'], [Y, '222.00']]) {
      // Bulk sends are dispatched after the save: wait for the transport outcome (QUEUED -> NOT_SENT).
      const e = await rt.waitFor(async () => { const x = (await rt.emailsFor(T.admin, 'INVOICE', inv.id)).json.content[0]; return x && x.status !== 'QUEUED' ? x : null; }, { timeoutMs: 15000 })
        || (await rt.emailsFor(T.admin, 'INVOICE', inv.id)).json.content[0];
      c.that(`${inv.invoiceNumber} rendered`, e && e.subject === `${inv.invoiceNumber} due` && e.body === `Balance ₹${amt}` && /NOT_SENT|NOT_CONFIGURED/.test(e.status), e && { s: e.subject, b: e.body, st: e.status });
    }
    return done(c, { codeRef: BE.email });
  });

  await tcase({ id: 'REG-EML-06', feature: 'Email (customer login)', kind: 'API', prd: 'A4',
    title: 'A customer login sends an email about its own invoice from itself, with placeholders; it cannot address staff by name',
    steps: 'customer login POST /api/emails {INVOICE own, to [ROLE COLLECTION_POC CUSTOMER], subject "About {{Invoice.Number}}"}; GET /api/emails/people?q=Reg',
    expected: 'E13: 200, from = the customer, subject rendered, NOT_SENT; people search 403' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'CustMail', invoices: [{ amount: 55 }] });
    const ct = await rt.login(cu.username, cu.password);
    const r = await rt.api('POST', '/api/emails', { token: ct, body: { entityType: 'INVOICE', entityId: cu.invoices[0].id, to: [rt.tok.role('COLLECTION_POC', 'CUSTOMER')], subject: 'About {{Invoice.Number}}', body: 'please check' } });
    c.that('200 rendered', r.status === 200 && r.json.subject === `About ${cu.invoices[0].invoiceNumber}` && /NOT_SENT|NOT_CONFIGURED/.test(r.json.status), brief(r));
    c.that('from the customer', r.json && r.json.from && r.json.from.address === cu.email, r.json && r.json.from);
    const pp = await getJ(ct, '/api/emails/people', { q: 'Reg' });
    c.that('people 403', pp.status === 403, brief(pp));
    return done(c, { codeRef: BE.email });
  });

  await tcase({ id: 'REG-SCH-01', feature: 'Table schemas', kind: 'API', prd: 'B1',
    title: 'List schemas keep their old columns and add the region / payment-term / pending columns',
    steps: 'GET /api/table-schemas/invoices, customers, payments, promises, disputes',
    expected: 'DESIGN §6.5: invoices keep invoiceNumber, customerId, invoiceDate, dueDate, total, paidAmount, balance, status, overdue, salesPocUserId and add regionId (REFERENCE region), regionName, paymentTerm, pendingApproval (BOOLEAN eq); customers add regionId, regionName, paymentTerm, overdueAmount; payments/promises/disputes add regionId and pendingApproval' }, async () => {
    const c = checks();
    const col = async (e) => { const r = await getJ(T.admin, `/api/table-schemas/${e}`); return Object.fromEntries((r.json.columns || []).map((x) => [x.name, x])); };
    const inv = await col('invoices');
    for (const k of ['invoiceNumber', 'customerId', 'invoiceDate', 'dueDate', 'total', 'paidAmount', 'balance', 'status', 'overdue', 'salesPocUserId', 'regionName', 'paymentTerm']) c.that(`invoices.${k}`, !!inv[k]);
    c.that('invoices.regionId REFERENCE region', inv.regionId && inv.regionId.referenceKind === 'region', inv.regionId);
    c.that('invoices.pendingApproval BOOLEAN eq', inv.pendingApproval && inv.pendingApproval.type === 'BOOLEAN' && JSON.stringify(inv.pendingApproval.operators) === '["eq"]', inv.pendingApproval);
    const cus = await col('customers');
    for (const k of ['name', 'regionId', 'regionName', 'paymentTerm', 'overdueAmount']) c.that(`customers.${k}`, !!cus[k]);
    for (const e of ['payments', 'promises', 'disputes']) {
      const s = await col(e);
      c.that(`${e}.regionId + pendingApproval`, !!s.regionId && !!s.pendingApproval, Object.keys(s));
    }
    return done(c, { codeRef: BE.schemas });
  });
  await tcase({ id: 'REG-PRM-03', feature: 'Promises', kind: 'API', prd: 'B1',
    title: 'A promise whose date has passed unpaid is BROKEN and its Collection POC is told (region-aware notice)',
    steps: 'fresh customer + invoice; POST /api/promises {amount 100, promisedDate -2d, collectionPocUserId coll}; coll GET /api/notifications; POST /api/promises/recompute?apply=true',
    expected: 'promise status BROKEN; coll has a PROMISE_BROKEN notification linking /promises/{id} (coll holds DEFAULT, §5.6.7); recompute 200 with no further change for it' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'Broken', invoices: [{ amount: 500 }] });
    const r = await rt.createPromise(T.admin, { customerId: cu.id, amount: 100, promisedDate: rt.day(-2), collectionPocUserId: U.coll.id });
    c.that('BROKEN', r.status === 200 && r.json.status === 'BROKEN', brief(r));
    const n = await rt.list(T.coll, '/api/notifications');
    c.that('coll told', (n.json.content || []).some((x) => x.type === 'PROMISE_BROKEN' && x.link === `/promises/${r.json && r.json.id}`), (n.json.content || []).slice(0, 3).map((x) => `${x.type} ${x.link}`));
    const rc = await rt.api('POST', '/api/promises/recompute', { token: T.admin, query: { apply: true } });
    c.that('recompute 200', rc.status === 200 && !(rc.json || []).some((x) => x.promiseId === (r.json && r.json.id) || x.id === (r.json && r.json.id)), brief(rc));
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java' });
  });

  await tcase({ id: 'REG-CUS-10', feature: 'Customers list', kind: 'API', prd: 'B1',
    title: 'Customers list sorts by outstanding and filters by the new overdueAmount / paymentTerm / regionId columns',
    steps: 'customers P (NET_15, overdue 300) and Q (NET_45, nothing overdue); GET /api/customers?filter=name:contains:<tag>&sort=outstanding,desc; filter overdueAmount:gt:0; filter paymentTerm:eq:NET_45; filter regionId:eq:<D>',
    expected: 'DESIGN §6.5 new columns: outstanding desc puts the bigger first; overdueAmount:gt:0 -> P only; paymentTerm:eq:NET_45 -> Q only; regionId:eq:D -> both' }, async () => {
    const c = checks();
    const tag = `Col${rt.uniq('').replace(/-/g, '')}`;
    const P = await customer(w, { prefix: `${tag}P`, paymentTerm: 'NET_15' });
    await invoice(w, P, { amount: 300, dueDate: rt.day(-5), invoiceDate: `${rt.day(-9)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    const Q = await customer(w, { prefix: `${tag}Q`, paymentTerm: 'NET_45', invoices: [{ amount: 900 }] });
    const q = (filter, sort) => getJ(T.admin, '/api/customers', { size: 10, filter: [`name:contains:${tag}`, ...filter], sort });
    const s = await q([], 'outstanding,desc');
    c.that('outstanding desc Q,P', s.status === 200 && ids(s).join() === `${Q.id},${P.id}`, brief(s));
    const o = await q(['overdueAmount:gt:0']);
    c.that('overdue -> P', o.status === 200 && ids(o).join() === String(P.id), brief(o));
    const t = await q(['paymentTerm:eq:NET_45']);
    c.that('term -> Q', t.status === 200 && ids(t).join() === String(Q.id), brief(t));
    const r = await q([`regionId:eq:${D}`]);
    c.that('region -> both', r.status === 200 && r.json.totalElements === 2, brief(r));
    return done(c, { codeRef: BE.schemas });
  });

  await tcase({ id: 'REG-AUD-03', feature: 'Audit history', kind: 'API', prd: 'B1',
    title: 'Product and user histories stay readable (created/updated rows; grant changes appear on the user)',
    steps: 'create + update a product; GET /api/audit?entityType=PRODUCT; create a user with a DEFAULT grant and change its grant; GET /api/audit?entityType=USER',
    expected: 'PRODUCT_CREATED and PRODUCT_UPDATED; the user\'s history lists USER_REGION_GRANTS_CHANGED (§6.15; user creation itself was never audited on main)' }, async () => {
    const c = checks();
    const name = rt.uniq('AudProd');
    const p = rt.need(await rt.api('POST', '/api/products', { token: T.admin, body: { name, price: 5, active: true } }), 'p');
    rt.need(await rt.api('PUT', `/api/products/${p.id}`, { token: T.admin, body: { name, price: 6, active: true } }), 'pu');
    const ap = await getJ(T.admin, '/api/audit', { entityType: 'PRODUCT', entityId: p.id });
    const pa = (ap.json || []).map((x) => x.action);
    c.that('product rows', pa.includes('PRODUCT_CREATED') && pa.includes('PRODUCT_UPDATED'), pa);
    const u = await rt.createStaff(T.admin, 'VIEWER', 'raud', { grants: [{ regionId: D, accessLevel: 'READ', recordScope: 'ALL' }] });
    rt.need(await rt.setGrants(T.admin, u.id, [{ regionId: D, accessLevel: 'MANAGE', recordScope: 'ALL' }]), 'grants');
    const au = await getJ(T.admin, '/api/audit', { entityType: 'USER', entityId: u.id });
    const ua = (au.json || []).map((x) => x.action);
    c.that('user rows (grant changes)', au.status === 200 && ua.filter((a) => a === 'USER_REGION_GRANTS_CHANGED').length >= 1, ua);
    return done(c, { codeRef: 'backend/src/main/java/com/geneinvoice/audit/AuditController.java' });
  });

  await tcase({ id: 'REG-DSP-05', feature: 'Disputes', kind: 'API', prd: 'B2',
    title: 'Dispute resolutions that change money: replace an invoice\'s lines, change a payment\'s amount (applied, not held)',
    steps: 'customer disputes invoice I (1000) proposing replace_items [Reg Widget x3 @100]; admin approves; customer disputes payment P (400 on another invoice) proposing update_amount 250; admin approves',
    expected: 'I total 300 with one line; P amount 250, fully re-allocated (oldest invoice first), customer outstanding 300+800-250 = 850; both disputes APPROVED' }, async () => {
    const c = checks();
    const cu = await customer(w, { prefix: 'DispMoney', invoices: [{ amount: 1000 }, { amount: 800 }] });
    const ct = await rt.login(cu.username, cu.password);
    const [I, J] = cu.invoices;
    const d1 = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: I.id, reason: 'lines', proposedChangeJson: JSON.stringify({ action: 'replace_items', items: [{ productId: w.product.id, quantity: 3, unitPrice: 100 }] }) }), 'd1');
    const a1 = await rt.api('POST', `/api/disputes/${d1.id}/approve`, { token: T.admin, body: {} });
    c.that('approve lines 200', a1.status === 200 && a1.json.status === 'APPROVED', brief(a1));
    const gi = await getJ(T.admin, `/api/invoices/${I.id}`);
    c.that('invoice total 300, 1 line', num(gi.json.total) === 300 && gi.json.items.length === 1 && gi.json.items[0].quantity === 3, { t: gi.json.total, items: gi.json.items });
    const p = await payment(w, cu, { amount: 400, invoiceIds: [J.id] });
    const d2 = rt.need(await rt.createDispute(ct, { targetType: 'PAYMENT', targetId: p.id, reason: 'amount', proposedChangeJson: JSON.stringify({ action: 'update_amount', amount: 250 }) }), 'd2');
    const a2 = await rt.api('POST', `/api/disputes/${d2.id}/approve`, { token: T.admin, body: {} });
    c.that('approve amount 200', a2.status === 200 && a2.json.status === 'APPROVED', brief(a2));
    const gp = await getJ(T.admin, `/api/payments/${p.id}`);
    // A changed amount is re-applied oldest invoice first across the customer (PaymentService.updateAmount), so the
    // 250 may land on I (older) rather than J: the money is checked in total.
    const alloc = (gp.json.invoices || []).reduce((t, i) => t + num(i.allocatedAmount), 0);
    const gc = await getJ(T.admin, `/api/customers/${cu.id}`);
    c.that('payment 250 fully allocated', num(gp.json.amount) === 250 && alloc === 250 && num(gp.json.creditApplied) === 0, { p: gp.json.amount, alloc, credit: gp.json.creditApplied });
    c.that('customer outstanding 850 (300+800-250)', num(gc.json.outstanding) === 850, gc.json.outstanding);
    return done(c, { codeRef: BE.disp });
  });
};
