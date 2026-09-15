// API regression tests: disputes and notifications.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const results = [];
function rec(id, title, pass, detail) {
  results.push({ id, title, pass: !!pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title} :: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
}
const num = (v) => (v === null || v === undefined ? null : Number(v));
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 220)}`;
const iso = (daysAgo) => new Date(Date.now() - daysAgo * 86400000).toISOString();

(async () => {
  // ---------- setup ----------
  const seedAdmin = await rt.adminToken();
  const cashier = await rt.cashierToken();
  const myAdmin = await rt.createStaff(seedAdmin, 'ADMIN', 'dnadm');
  const adm = await rt.login(myAdmin.username, myAdmin.password);
  const admMe = (await rt.api('GET', '/api/auth/me', { token: adm })).json;
  const sales = await rt.createStaff(adm, 'SALES_POC', 'dnsales');
  const salesT = await rt.login(sales.username, sales.password);

  const mkProduct = async (price) => {
    const r = await rt.api('POST', '/api/products', { token: adm, body: { name: rt.uniq('dnP' + price), description: 'dn', price, active: true } });
    if (r.status >= 300) throw new Error('product ' + snip(r));
    return r.json;
  };
  const p100 = await mkProduct(100);
  const p50 = await mkProduct(50);

  const mkCust = async (tag) => {
    const c = await rt.createCustomer(adm, 'dn' + tag);
    const token = await rt.login(c.username, c.password);
    const me = (await rt.api('GET', '/api/auth/me', { token })).json;
    return { ...c, token, userId: me.id };
  };
  const mkInv = async (custId, qty, daysAgo, productId = p100.id) => {
    const r = await rt.api('POST', '/api/invoices', { token: adm, body: {
      customerId: custId, invoiceDate: iso(daysAgo), salesPocUserId: admMe.id, notes: 'orig notes',
      items: [{ productId, quantity: qty }] } });
    if (r.status >= 300) throw new Error('invoice ' + snip(r));
    return r.json;
  };
  const pay = async (custId, amount, invoiceIds) => {
    const r = await rt.api('POST', '/api/payments', { token: adm, body: {
      customerId: custId, amount, method: 'CASH', notes: 'orig pay', invoiceIds, collectionPocUserId: admMe.id } });
    if (r.status >= 300) throw new Error('payment ' + snip(r));
    return r.json;
  };
  const inv = async (id) => (await rt.api('GET', `/api/invoices/${id}`, { token: adm })).json;
  const payment = async (id) => (await rt.api('GET', `/api/payments/${id}`, { token: adm })).json;
  const credit = async (cid) => num((await rt.api('GET', `/api/customers/${cid}`, { token: adm })).json.creditBalance);
  const open = (token, targetType, targetId, reason, proposed) => rt.api('POST', '/api/disputes', { token,
    body: { targetType, targetId, reason, ...(proposed !== undefined ? { proposedChangeJson: typeof proposed === 'string' ? proposed : JSON.stringify(proposed) } : {}) } });
  const approve = (id, body, token = adm) => rt.api('POST', `/api/disputes/${id}/approve`, { token, body });
  const deny = (id, body, token = adm) => rt.api('POST', `/api/disputes/${id}/deny`, { token, body });
  const getD = (id, token = adm) => rt.api('GET', `/api/disputes/${id}`, { token });
  const notifs = async (token, filters = [], extra = '') => {
    const q = filters.map((f) => 'filter=' + encodeURIComponent(f)).join('&');
    return rt.api('GET', `/api/notifications?size=50&${q}${extra}`, { token });
  };

  const A = await mkCust('A');
  const B = await mkCust('B');
  const invA = await mkInv(A.id, 2, 5); // 200
  const invB = await mkInv(B.id, 1, 5); // 100
  const payB = await pay(B.id, 40, [invB.id]);

  // ---------- who may open ----------
  let r = await open(adm, 'INVOICE', invA.id, 'staff tries');
  rec('DN-API-01', 'Admin (staff) cannot open a dispute', r.status === 403, snip(r));
  r = await open(cashier, 'INVOICE', invA.id, 'cashier tries');
  rec('DN-API-02a', 'Cashier (no DISPUTE_CREATE) cannot open a dispute', r.status === 403, snip(r));
  r = await open(salesT, 'INVOICE', invA.id, 'sales tries');
  rec('DN-API-02b', 'SALES_POC cannot open a dispute', r.status === 403, snip(r));

  r = await open(A.token, 'INVOICE', invB.id, 'not mine');
  rec('DN-API-03a', "Customer A cannot dispute customer B's invoice", r.status === 403 || r.status === 404, snip(r));
  r = await open(A.token, 'PAYMENT', payB.id, 'not mine');
  rec('DN-API-03b', "Customer A cannot dispute customer B's payment", r.status === 403 || r.status === 404, snip(r));
  r = await open(A.token, 'INVOICE', 99999999, 'ghost');
  rec('DN-API-03c', 'Dispute on non-existent invoice -> 404', r.status === 404, snip(r));
  const listB = await rt.api('GET', `/api/disputes?filter=${encodeURIComponent('customerId:eq:' + B.id)}`, { token: adm });
  rec('DN-API-03d', 'Rejected cross-customer attempts created no dispute rows', listB.json.totalElements === 0, `B disputes=${listB.json.totalElements}`);

  // ---------- validation ----------
  r = await open(A.token, 'INVOICE', invA.id, '   ');
  const v1 = r.status;
  const r2 = await rt.api('POST', '/api/disputes', { token: A.token, body: { targetType: 'INVOICE', targetId: invA.id } });
  const r3 = await rt.api('POST', '/api/disputes', { token: A.token, body: { targetType: 'INVOICE', reason: 'x' } });
  const r4 = await rt.api('POST', '/api/disputes', { token: A.token, body: { targetType: 'BOGUS', targetId: invA.id, reason: 'x' } });
  rec('DN-API-04', 'Validation: blank reason / missing reason / missing targetId -> 400 with fieldErrors',
    v1 === 400 && r2.status === 400 && r3.status === 400 && /reason/.test(r.text) && /targetId/.test(r3.text),
    `blank=${v1} ${r.text.slice(0, 260)} | missing=${r2.status} | noTarget=${r3.status} ${r3.text.slice(0, 200)}`);
  const r5 = await rt.api('POST', '/api/disputes', { token: A.token, body: { targetType: 'INVOICE', targetId: 'abc', reason: 'x' } });
  const r6 = await rt.api('POST', '/api/disputes', { token: A.token, headers: { 'Content-Type': 'application/json' } });
  rec('DN-API-04b', 'Malformed body: unknown targetType / non-numeric targetId / missing body -> 400 (not 500)',
    r4.status === 400 && r5.status === 400 && r6.status === 400,
    `badType=${snip(r4)} | badId=${r5.status} ${r5.text.slice(90, 220)} | noBody=${r6.status}`);

  // ---------- open happy path + notifications ----------
  const beforeAdmOpened = (await notifs(adm, ['type:eq:DISPUTE_OPENED'])).json.totalElements;
  const beforeAdmUnread = (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
  r = await open(A.token, 'INVOICE', invA.id, 'Charged twice for widgets', { action: 'update_notes', notes: 'fixed by dispute' });
  const d1 = r.json;
  rec('DN-API-05', 'Customer opens dispute on own invoice -> 200 PENDING with correct fields',
    r.status === 200 && d1.status === 'PENDING' && d1.customerId === A.id && d1.openedByUserId === A.userId
      && d1.targetType === 'INVOICE' && d1.targetId === invA.id && (d1.targetSummary || '').includes(invA.invoiceNumber)
      && d1.customerName === A.name && d1.resolvedAt === null,
    snip(r));

  r = await open(A.token, 'INVOICE', invA.id, 'again');
  rec('DN-API-06a', 'Second PENDING dispute on same invoice rejected (400)', r.status === 400, snip(r));

  const admOpened = await notifs(adm, ['type:eq:DISPUTE_OPENED']);
  const mine = admOpened.json.content.find((n) => n.link === `/admin/disputes/${d1.id}`);
  const afterAdmUnread = (await rt.api('GET', '/api/notifications/unread-count', { token: adm })).json.count;
  rec('DN-API-07a', 'Own ADMIN user receives DISPUTE_OPENED (title, message=reason, unread, count +1)',
    !!mine && mine.title === `New dispute from ${A.name}` && mine.message === 'Charged twice for widgets' && mine.read === false
      && admOpened.json.totalElements === beforeAdmOpened + 1 && afterAdmUnread >= beforeAdmUnread + 1,
    { mine, before: beforeAdmOpened, after: admOpened.json.totalElements, unreadBefore: beforeAdmUnread, unreadAfter: afterAdmUnread });
  const seedOpened = await notifs(seedAdmin, ['type:eq:DISPUTE_OPENED']);
  rec('DN-API-07b', 'Seeded admin also receives DISPUTE_OPENED for the same dispute',
    !!seedOpened.json.content.find((n) => n.link === `/admin/disputes/${d1.id}`), `seeded admin DISPUTE_OPENED total=${seedOpened.json.totalElements}`);
  const custOpened = await notifs(A.token, ['type:eq:DISPUTE_OPENED']);
  const salesOpened = await notifs(salesT, ['type:eq:DISPUTE_OPENED']);
  rec('DN-API-07c', 'Customer and non-admin staff get no DISPUTE_OPENED', custOpened.json.totalElements === 0 && salesOpened.json.totalElements === 0,
    `cust=${custOpened.json.totalElements} sales=${salesOpened.json.totalElements}`);

  // ---------- scoping ----------
  const dB = (await open(B.token, 'PAYMENT', payB.id, 'B payment wrong', { action: 'update_meta', method: 'CARD', notes: 'B meta' })).json;
  const la = await rt.api('GET', '/api/disputes?size=50', { token: A.token });
  const laB = await rt.api('GET', `/api/disputes?size=50&customerId=${B.id}`, { token: A.token });
  const laBf = await rt.api('GET', `/api/disputes?size=50&filter=${encodeURIComponent('customerId:eq:' + B.id)}`, { token: A.token });
  rec('DN-API-08a', 'Customer list scoped to own disputes (even when asking for customer B)',
    la.status === 200 && la.json.content.length > 0 && la.json.content.every((d) => d.customerId === A.id)
      && laB.json.totalElements === 0 && laBf.json.totalElements === 0,
    `A total=${la.json.totalElements} ids=${la.json.content.map((d) => d.customerId)} | ?customerId=B -> ${laB.status}/${laB.json?.totalElements} | filter B -> ${laBf.status}/${laBf.json?.totalElements}`);
  const lad = await rt.api('GET', `/api/disputes?size=50&filter=${encodeURIComponent('customerId:in:' + A.id + ',' + B.id)}`, { token: adm });
  rec('DN-API-08b', "Admin list includes both customers' disputes",
    lad.json.content.some((d) => d.id === d1.id) && lad.json.content.some((d) => d.id === dB.id), `admin total=${lad.json.totalElements}`);
  const g1 = await getD(d1.id, B.token);
  const g2 = await getD(d1.id, A.token);
  const g3 = await getD(99999999, adm);
  rec('DN-API-08c', "GET by id: owner 200, other customer 403, unknown id 404",
    g2.status === 200 && g1.status === 403 && g3.status === 404, `owner=${g2.status} other=${snip(g1)} unknown=${g3.status}`);
  const gc = await rt.api('GET', '/api/disputes', { token: cashier });
  const gs = await rt.api('GET', '/api/disputes?size=10', { token: salesT });
  const gsd = await getD(d1.id, salesT);
  rec('DN-API-08d', 'Cashier (no DISPUTE_VIEW) 403 on list; SALES_POC (DISPUTE_VIEW) 200 on list and by id',
    gc.status === 403 && gs.status === 200 && gsd.status === 200, `cashier=${gc.status} sales list=${gs.status} sales get=${gsd.status}`);

  // ---------- customer / staff cannot resolve ----------
  const ca = await approve(d1.id, {}, A.token);
  const cd = await deny(d1.id, {}, A.token);
  const sa = await approve(d1.id, {}, salesT);
  rec('DN-API-09', 'Customer and SALES_POC cannot approve/deny (403); dispute still PENDING',
    ca.status === 403 && cd.status === 403 && sa.status === 403 && (await getD(d1.id)).json.status === 'PENDING',
    `cust approve=${ca.status} cust deny=${cd.status} sales approve=${sa.status}`);

  // ---------- approve update_notes (d1) ----------
  const custNotifBefore = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  r = await approve(d1.id, { adminNotes: 'Notes corrected' });
  const invA2 = await inv(invA.id);
  rec('DN-API-12', 'Approve invoice update_notes: notes changed, total/paid untouched, dispute APPROVED with resolver',
    r.status === 200 && r.json.status === 'APPROVED' && r.json.resolvedByUserId === admMe.id && !!r.json.resolvedAt
      && r.json.adminNotes === 'Notes corrected' && invA2.notes === 'fixed by dispute' && num(invA2.total) === 200,
    `${snip(r)} | invoice notes=${invA2.notes} total=${invA2.total}`);
  const cn = await notifs(A.token, ['type:eq:DISPUTE_APPROVED']);
  const cnMine = cn.json.content.find((n) => n.link === `/disputes/${d1.id}`);
  const custNotifAfter = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  rec('DN-API-13', 'Customer receives DISPUTE_APPROVED (message = admin notes, link /disputes/{id}, unread +1)',
    !!cnMine && cnMine.title === 'Dispute approved' && cnMine.message === 'Notes corrected' && custNotifAfter === custNotifBefore + 1,
    { cnMine, custNotifBefore, custNotifAfter });
  const auditInv = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${invA.id}&size=50`, { token: adm });
  const auditRows = auditInv.json?.content || auditInv.json || [];
  rec('DN-API-12b', 'Approval audited on the invoice as DISPUTE_APPROVED', JSON.stringify(auditRows).includes('DISPUTE_APPROVED'),
    `audit status=${auditInv.status} actions=${(Array.isArray(auditRows) ? auditRows : []).map((a) => a.action).join(',')}`);

  // ---------- double resolve ----------
  const again1 = await approve(d1.id, {});
  const again2 = await deny(d1.id, {});
  rec('DN-API-17a', 'Already-approved dispute cannot be approved or denied again (400)', again1.status === 400 && again2.status === 400,
    `approve=${snip(again1)} deny=${snip(again2)}`);

  // after resolution a new dispute on the same target is allowed
  r = await open(A.token, 'INVOICE', invA.id, 'new issue after resolution');
  rec('DN-API-06b', 'After the first dispute is resolved a new dispute on the same invoice is accepted', r.status === 200, snip(r));
  const d1b = r.json;

  // ---------- deny ----------
  const invAbefore = await inv(invA.id);
  r = await deny(d1b.id, { adminNotes: 'No evidence provided' });
  const invAafter = await inv(invA.id);
  const dn = await notifs(A.token, ['type:eq:DISPUTE_DENIED']);
  const dnMine = dn.json.content.find((n) => n.link === `/disputes/${d1b.id}`);
  rec('DN-API-16', 'Deny: DENIED + admin notes, target unchanged, customer gets DISPUTE_DENIED with admin notes',
    r.status === 200 && r.json.status === 'DENIED' && r.json.adminNotes === 'No evidence provided' && !!r.json.resolvedAt
      && JSON.stringify(invAbefore) === JSON.stringify(invAafter) && !!dnMine && dnMine.title === 'Dispute denied' && dnMine.message === 'No evidence provided',
    { status: r.status, body: r.json && { status: r.json.status, adminNotes: r.json.adminNotes }, dnMine });
  const again3 = await approve(d1b.id, { appliedChangeJson: '{"action":"update_notes","notes":"x"}' });
  rec('DN-API-17b', 'Denied dispute cannot be approved afterwards (400), invoice untouched',
    again3.status === 400 && (await inv(invA.id)).notes === invAafter.notes, snip(again3));

  // deny without body (null request body allowed) and resolution message falls back to reason
  const dNoBody = (await open(A.token, 'INVOICE', invA.id, 'deny me without notes')).json;
  r = await rt.api('POST', `/api/disputes/${dNoBody.id}/deny`, { token: adm });
  const dn2 = (await notifs(A.token, ['type:eq:DISPUTE_DENIED'])).json.content.find((n) => n.link === `/disputes/${dNoBody.id}`);
  rec('DN-API-16b', 'Deny with no request body -> 200; customer notification message falls back to the reason',
    r.status === 200 && r.json.status === 'DENIED' && dn2 && dn2.message === 'deny me without notes', `${snip(r)} | notif=${JSON.stringify(dn2)}`);

  // ---------- approve invoice cancel with refund ----------
  const C = await mkCust('C');
  const invC = await mkInv(C.id, 2, 3); // 200
  await pay(C.id, 150, [invC.id]);
  const creditC0 = await credit(C.id);
  const dC = (await open(C.token, 'INVOICE', invC.id, 'I never ordered this', { action: 'cancel' })).json;
  r = await approve(dC.id, {});
  const invC2 = await inv(invC.id);
  const creditC1 = await credit(C.id);
  rec('DN-API-10', 'Approve invoice cancel: CANCELLED, paidAmount 0, paid 150 refunded to customer credit',
    r.status === 200 && r.json.status === 'APPROVED' && invC2.status === 'CANCELLED' && num(invC2.paidAmount) === 0 && creditC1 - creditC0 === 150,
    `${r.status} status=${invC2.status} paid=${invC2.paidAmount} credit ${creditC0} -> ${creditC1}`);
  const dupCancel = (await open(C.token, 'INVOICE', invC.id, 'cancel again', { action: 'cancel' })).json;
  r = await approve(dupCancel.id, {});
  const dupAfter = await getD(dupCancel.id);
  rec('DN-API-10b', 'Approving cancel on an already-cancelled invoice -> 400 and dispute stays PENDING (rolled back)',
    r.status === 400 && dupAfter.json.status === 'PENDING', `${snip(r)} | dispute=${dupAfter.json.status}`);
  await deny(dupCancel.id, { adminNotes: 'cleanup' });

  // ---------- approve replace_items ----------
  const D = await mkCust('D');
  const invD = await mkInv(D.id, 3, 3); // 300
  await pay(D.id, 300, [invD.id]);
  const creditD0 = await credit(D.id);
  const dD = (await open(D.token, 'INVOICE', invD.id, 'Only 1 small item delivered', { action: 'replace_items', items: [{ productId: p50.id, quantity: 1 }] })).json;
  r = await approve(dD.id, {});
  const invD2 = await inv(invD.id);
  const creditD1 = await credit(D.id);
  rec('DN-API-11a', 'Approve replace_items (smaller): total recomputed 300->50, paid 50, FULLY_PAID, excess 250 to credit',
    r.status === 200 && num(invD2.total) === 50 && num(invD2.paidAmount) === 50 && invD2.status === 'FULLY_PAID'
      && invD2.items.length === 1 && invD2.items[0].productId === p50.id && creditD1 - creditD0 === 250,
    `${r.status} total=${invD2.total} paid=${invD2.paidAmount} status=${invD2.status} items=${JSON.stringify(invD2.items.map((i) => [i.productId, i.quantity, i.unitPrice]))} credit ${creditD0}->${creditD1}`);
  // larger + explicit unit price + notes, via appliedChangeJson overriding the proposal
  const dD2 = (await open(D.token, 'INVOICE', invD.id, 'Actually 2 items at 60', { action: 'cancel' })).json;
  r = await approve(dD2.id, { appliedChangeJson: JSON.stringify({ action: 'replace_items', notes: 'repriced', items: [{ productId: p50.id, quantity: 2, unitPrice: 60 }] }) });
  const invD3 = await inv(invD.id);
  rec('DN-API-11b', 'appliedChangeJson overrides proposal; replace_items (larger, unitPrice 60x2) -> total 120, PARTIALLY_PAID, notes set',
    r.status === 200 && num(invD3.total) === 120 && num(invD3.paidAmount) === 50 && invD3.status === 'PARTIALLY_PAID' && invD3.notes === 'repriced',
    `${r.status} total=${invD3.total} paid=${invD3.paidAmount} status=${invD3.status} notes=${invD3.notes}`);
  const dD3 = (await open(D.token, 'INVOICE', invD.id, 'empty items', { action: 'replace_items', items: [] })).json;
  r = await approve(dD3.id, {});
  const rBadProd = await approve(dD3.id, { appliedChangeJson: JSON.stringify({ action: 'replace_items', items: [{ productId: 99999999, quantity: 1 }] }) });
  const dD3s = (await getD(dD3.id)).json.status;
  rec('DN-API-11c', 'replace_items with empty items -> 400; unknown product -> 404; dispute stays PENDING',
    r.status === 400 && rBadProd.status === 404 && dD3s === 'PENDING', `empty=${snip(r)} | badProduct=${snip(rBadProd)} | status=${dD3s}`);
  const rZero = await approve(dD3.id, { appliedChangeJson: JSON.stringify({ action: 'replace_items', items: [{ productId: p50.id, quantity: 0 }] }) });
  const invD4 = await inv(invD.id);
  rec('DN-API-11d', 'replace_items with quantity 0 is rejected (400) — invoice lines require quantity > 0',
    rZero.status === 400, `${snip(rZero)} | invoice total now=${invD4.total} items=${JSON.stringify(invD4.items.map((i) => [i.quantity, i.lineTotal]))} status=${invD4.status}`);
  if ((await getD(dD3.id)).json.status === 'PENDING') await deny(dD3.id, { adminNotes: 'cleanup' });

  // ---------- approve payment void ----------
  const E = await mkCust('E');
  const e1 = await mkInv(E.id, 1, 10); // 100 oldest
  const e2 = await mkInv(E.id, 1, 5); // 100
  const pE = await pay(E.id, 250, null); // 100 + 100 + 50 credit
  const creditE0 = await credit(E.id);
  const dE = (await open(E.token, 'PAYMENT', pE.id, 'Bounced cheque', { action: 'void' })).json;
  r = await approve(dE.id, {});
  const pE2 = await payment(pE.id);
  const e1b = await inv(e1.id); const e2b = await inv(e2.id);
  const creditE1 = await credit(E.id);
  rec('DN-API-14', 'Approve payment void: VOIDED, allocations reversed (invoices back to UNPAID), credit applied (50) removed',
    r.status === 200 && pE2.status === 'VOIDED' && pE2.invoices.length === 0 && num(pE2.creditApplied) === 0
      && num(e1b.paidAmount) === 0 && e1b.status === 'UNPAID' && num(e2b.paidAmount) === 0 && e2b.status === 'UNPAID'
      && creditE0 === 50 && creditE1 === 0,
    `${r.status} pay=${pE2.status} allocs=${pE2.invoices.length} creditApplied=${pE2.creditApplied} e1=${e1b.paidAmount}/${e1b.status} e2=${e2b.paidAmount}/${e2b.status} credit ${creditE0}->${creditE1}`);
  const dE2 = (await open(E.token, 'PAYMENT', pE.id, 'void again', { action: 'void' })).json;
  r = await approve(dE2.id, {});
  const dE2s = (await getD(dE2.id)).json.status;
  rec('DN-API-15c', 'Voiding an already-voided payment -> 400, dispute stays PENDING', r.status === 400 && dE2s === 'PENDING', `${snip(r)} status=${dE2s}`);
  r = await approve(dE2.id, { appliedChangeJson: JSON.stringify({ action: 'update_amount', amount: 10 }) });
  rec('DN-API-15d', 'update_amount on a voided payment -> 400', r.status === 400, snip(r));
  await deny(dE2.id, { adminNotes: 'cleanup' });

  // ---------- approve payment update_amount ----------
  const F = await mkCust('F');
  const f1 = await mkInv(F.id, 1, 10); // 100 oldest
  const f2 = await mkInv(F.id, 1, 5); // 100
  const pF = await pay(F.id, 60, null); // 60 on f1
  const dF = (await open(F.token, 'PAYMENT', pF.id, 'I paid 230 not 60', { action: 'update_amount', amount: 230, method: 'BANK', notes: 'corrected' })).json;
  r = await approve(dF.id, {});
  const pF2 = await payment(pF.id);
  const f1b = await inv(f1.id); const f2b = await inv(f2.id);
  const creditF = await credit(F.id);
  rec('DN-API-15a', 'Approve update_amount 60->230: re-allocated oldest-first (100,100), 30 to credit, method/notes updated',
    r.status === 200 && num(pF2.amount) === 230 && num(f1b.paidAmount) === 100 && f1b.status === 'FULLY_PAID'
      && num(f2b.paidAmount) === 100 && f2b.status === 'FULLY_PAID' && num(pF2.creditApplied) === 30 && creditF === 30
      && pF2.method === 'BANK' && pF2.notes === 'corrected',
    `${r.status} amount=${pF2.amount} f1=${f1b.paidAmount}/${f1b.status} f2=${f2b.paidAmount}/${f2b.status} creditApplied=${pF2.creditApplied} credit=${creditF} method=${pF2.method} notes=${pF2.notes}`);
  const dF2 = (await open(F.token, 'PAYMENT', pF.id, 'actually 120', { action: 'update_amount', amount: 120 })).json;
  r = await approve(dF2.id, {});
  const pF3 = await payment(pF.id); const f1c = await inv(f1.id); const f2c = await inv(f2.id); const creditF2 = await credit(F.id);
  rec('DN-API-15b', 'Approve update_amount 230->120: 100 on oldest, 20 on next (PARTIALLY_PAID), credit back to 0',
    r.status === 200 && num(pF3.amount) === 120 && num(f1c.paidAmount) === 100 && num(f2c.paidAmount) === 20 && f2c.status === 'PARTIALLY_PAID' && creditF2 === 0,
    `${r.status} f1=${f1c.paidAmount} f2=${f2c.paidAmount}/${f2c.status} credit=${creditF2}`);
  const dF3 = (await open(F.token, 'PAYMENT', pF.id, 'negative', { action: 'update_amount', amount: -5 })).json;
  const rNeg = await approve(dF3.id, {});
  const rMissing = await approve(dF3.id, { appliedChangeJson: '{"action":"update_amount"}' });
  const rNaN = await approve(dF3.id, { appliedChangeJson: '{"action":"update_amount","amount":"abc"}' });
  rec('DN-API-15e', 'update_amount validation: negative -> 400, missing amount -> 400, non-numeric -> 400 (not 500)',
    rNeg.status === 400 && rMissing.status === 400 && rNaN.status === 400, `neg=${snip(rNeg)} | missing=${snip(rMissing)} | nan=${snip(rNaN)}`);
  if ((await getD(dF3.id)).json.status === 'PENDING') await deny(dF3.id, { adminNotes: 'cleanup' });

  // ---------- update_meta ----------
  r = await approve(dB.id, {});
  const pB2 = await payment(payB.id);
  rec('DN-API-15f', 'Approve payment update_meta: method and notes changed, amount/allocations unchanged',
    r.status === 200 && pB2.method === 'CARD' && pB2.notes === 'B meta' && num(pB2.amount) === 40 && pB2.invoices.length === 1,
    `${r.status} method=${pB2.method} notes=${pB2.notes} amount=${pB2.amount}`);

  // ---------- bad approvals ----------
  const G = await mkCust('G');
  const invG = await mkInv(G.id, 1, 2);
  const dG = (await open(G.token, 'INVOICE', invG.id, 'weird', { action: 'teleport' })).json;
  r = await approve(dG.id, {});
  let st = (await getD(dG.id)).json.status;
  rec('DN-API-18', 'Approve with unknown action -> 400, dispute stays PENDING', r.status === 400 && st === 'PENDING', `${snip(r)} status=${st}`);
  r = await approve(dG.id, { appliedChangeJson: '{not json' });
  st = (await getD(dG.id)).json.status;
  rec('DN-API-19', 'Approve with malformed applied JSON -> 400, stays PENDING', r.status === 400 && st === 'PENDING', `${snip(r)} status=${st}`);
  const dG2 = (await open(G.token, 'PAYMENT', (await pay(G.id, 10, [invG.id])).id, 'describe only')).json;
  r = await approve(dG2.id, {});
  st = (await getD(dG2.id)).json.status;
  rec('DN-API-20', 'Approve a describe-only dispute with no applied change -> 400 "No change specified", stays PENDING',
    r.status === 400 && st === 'PENDING', `${snip(r)} status=${st}`);
  r = await approve(dG2.id, { appliedChangeJson: '{"action":"cancel"}' });
  rec('DN-API-18b', 'Invoice action on a PAYMENT dispute (cancel) -> 400 unknown payment action', r.status === 400, snip(r));
  r = await approve(99999999, {});
  const rd = await deny(99999999, {});
  rec('DN-API-21', 'Approve/deny unknown dispute id -> 404', r.status === 404 && rd.status === 404, `approve=${r.status} deny=${rd.status}`);
  await deny(dG.id, { adminNotes: 'cleanup' });
  await deny(dG2.id, { adminNotes: 'cleanup' });

  // ---------- edge: cancel via dispute, then void the payment that paid it ----------
  const H = await mkCust('H');
  const invH = await mkInv(H.id, 1, 2); // 100
  const pH = await pay(H.id, 100, [invH.id]);
  const dH1 = (await open(H.token, 'INVOICE', invH.id, 'cancel it', { action: 'cancel' })).json;
  await approve(dH1.id, {});
  const creditH1 = await credit(H.id);
  const dH2 = (await open(H.token, 'PAYMENT', pH.id, 'and void the payment', { action: 'void' })).json;
  r = await approve(dH2.id, {});
  const creditH2 = await credit(H.id);
  const pH2 = await payment(pH.id);
  rec('DN-API-22', 'Cancel invoice (refund 100 to credit) then void the payment that paid it: credit must not keep money from a voided payment',
    creditH2 === 0 || r.status === 400,
    `after cancel credit=${creditH1}; void approve=${r.status}; payment=${pH2.status}; credit after void=${creditH2} (expected 0 or void refused)`);

  // same family via replace_items
  const I = await mkCust('I');
  const invI = await mkInv(I.id, 3, 2); // 300
  const pI = await pay(I.id, 300, [invI.id]);
  const dI1 = (await open(I.token, 'INVOICE', invI.id, 'shrink', { action: 'replace_items', items: [{ productId: p50.id, quantity: 1 }] })).json;
  await approve(dI1.id, {});
  const creditI1 = await credit(I.id);
  const dI2 = (await open(I.token, 'PAYMENT', pI.id, 'void', { action: 'void' })).json;
  r = await approve(dI2.id, {});
  const creditI2 = await credit(I.id); const invI2 = await inv(invI.id);
  rec('DN-API-22b', 'Shrink invoice 300->50 (250 to credit) then void the 300 payment: credit + invoice paid must net to 0',
    (creditI2 + num(invI2.paidAmount)) === 0 || r.status === 400,
    `after shrink credit=${creditI1}; void=${r.status}; credit=${creditI2}; invoice paid=${invI2.paidAmount} status=${invI2.status}`);

  // ---------- notifications API ----------
  const nl = await rt.api('GET', '/api/notifications?page=0&size=10', { token: adm });
  rec('DN-API-30', 'GET /api/notifications is a paged envelope (content/page/size/totalElements/totalPages/sort)',
    nl.status === 200 && Array.isArray(nl.json.content) && nl.json.content.length === 10 && nl.json.size === 10 && nl.json.page === 0
      && typeof nl.json.totalElements === 'number' && nl.json.totalPages === Math.max(1, Math.ceil(nl.json.totalElements / 10)) && nl.json.sort === 'createdAt,desc',
    `${nl.status} keys=${Object.keys(nl.json || {})} total=${nl.json?.totalElements} pages=${nl.json?.totalPages} sort=${nl.json?.sort}`);
  const p2 = await rt.api('GET', '/api/notifications?page=1&size=10', { token: adm });
  const overlap = p2.json.content.some((n) => nl.json.content.some((m) => m.id === n.id));
  rec('DN-API-30b', 'Page 1 returns different rows than page 0', p2.status === 200 && !overlap, `page1 ids=${p2.json.content.map((n) => n.id)} page0 ids=${nl.json.content.map((n) => n.id)}`);

  const allA = (await notifs(A.token)).json;
  const unreadA = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  const unreadRows = (await notifs(A.token, ['read:eq:false'])).json.totalElements;
  rec('DN-API-31', 'Unread count equals rows with read=false (filter read:eq:false)', unreadA === unreadRows && unreadA === allA.content.filter((n) => !n.read).length,
    `count=${unreadA} filtered=${unreadRows} total=${allA.totalElements}`);

  const target = allA.content.find((n) => !n.read);
  r = await rt.api('POST', `/api/notifications/${target.id}/read`, { token: A.token });
  const afterRead = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  const tgt = (await notifs(A.token, [`id:eq:${target.id}`])).json.content[0];
  rec('DN-API-32', 'Mark single notification read -> 200, read=true, unread count -1', r.status === 200 && tgt.read === true && afterRead === unreadA - 1,
    `${r.status} read=${tgt.read} count ${unreadA}->${afterRead}`);

  // foreign notification: B's notification id marked by A
  const bN = (await notifs(B.token)).json.content[0];
  r = await rt.api('POST', `/api/notifications/${bN.id}/read`, { token: A.token });
  const bN2 = (await notifs(B.token, [`id:eq:${bN.id}`])).json.content[0];
  rec('DN-API-33', "Marking another user's notification read has no effect on it", bN2.read === bN.read && bN2.read === false,
    `status=${r.status} (silent no-op) B notif read before=${bN.read} after=${bN2.read}`);
  const readRows = (await notifs(A.token, [`id:eq:${bN.id}`])).json.totalElements;
  const aFilterUser = await rt.api('GET', `/api/notifications?filter=${encodeURIComponent('userId:eq:' + B.userId)}`, { token: A.token });
  rec('DN-API-33b', "A user cannot list another user's notifications (id filter -> 0 rows, userId filter rejected)",
    readRows === 0 && aFilterUser.status === 400, `id filter rows=${readRows}; userId filter=${snip(aFilterUser)}`);

  const ids = allA.content.slice(0, 3).map((n) => n.id);
  await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_UNREAD', ids } });
  const cBulk0 = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  r = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_READ', ids: [...ids, bN.id] } });
  const cBulk1 = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  rec('DN-API-34', 'Bulk MARK_READ own ids -> all succeeded, unread count drops by 3',
    r.status === 200 && ids.every((i) => r.json.succeeded.includes(i)) && cBulk0 - cBulk1 === 3,
    `${snip(r)} | count ${cBulk0}->${cBulk1}`);
  rec('DN-API-34b', "Bulk with another user's id: not acted on AND reported (skipped/failed), per AC-D5 'nothing dropped silently'",
    !r.json.succeeded.includes(bN.id) && (r.json.skipped.some((s) => s.id === bN.id) || r.json.failed.some((s) => s.id === bN.id)),
    `requested=${r.json.requested} succeeded=${r.json.succeeded} skipped=${JSON.stringify(r.json.skipped)} failed=${JSON.stringify(r.json.failed)}`);
  r = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_READ', ids } });
  rec('DN-API-35', 'Bulk MARK_READ on already-read ids -> skipped "Already read"',
    r.status === 200 && r.json.succeeded.length === 0 && r.json.skipped.length === 3 && r.json.skipped.every((s) => /Already read/.test(s.reason)), snip(r));
  r = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_UNREAD', ids } });
  const cBulk2 = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  rec('DN-API-36', 'Bulk MARK_UNREAD -> succeeded, unread count +3', r.status === 200 && r.json.succeeded.length === 3 && cBulk2 === cBulk1 + 3, `${snip(r)} count=${cBulk2}`);
  r = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_READ', selectAllMatchingFilter: true, filters: ['type:eq:DISPUTE_DENIED'] } });
  const deniedUnread = (await notifs(A.token, ['type:eq:DISPUTE_DENIED', 'read:eq:false'])).json.totalElements;
  const approvedUnread = (await notifs(A.token, ['type:eq:DISPUTE_APPROVED', 'read:eq:false'])).json.totalElements;
  rec('DN-API-37', 'Bulk selectAllMatchingFilter with type filter marks only the matching rows',
    r.status === 200 && deniedUnread === 0 && approvedUnread >= 0 && r.json.requested === (await notifs(A.token, ['type:eq:DISPUTE_DENIED'])).json.totalElements,
    `${snip(r)} deniedUnread=${deniedUnread} approvedUnread=${approvedUnread}`);
  const rb1 = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'DELETE', ids } });
  const rb2 = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { action: 'MARK_READ' } });
  const rb3 = await rt.api('POST', '/api/notifications/bulk', { token: A.token, body: { ids } });
  rec('DN-API-38', 'Bulk: unknown action / no ids / missing action -> 400', rb1.status === 400 && rb2.status === 400 && rb3.status === 400,
    `unknown=${snip(rb1)} | noIds=${snip(rb2)} | noAction=${rb3.status}`);
  r = await rt.api('POST', '/api/notifications/mark-all-read', { token: A.token });
  const cAll = (await rt.api('GET', '/api/notifications/unread-count', { token: A.token })).json.count;
  rec('DN-API-39', 'mark-all-read -> {updated:n}, unread count 0', r.status === 200 && typeof r.json.updated === 'number' && cAll === 0, `${snip(r)} count=${cAll}`);
  const s1 = await rt.api('GET', '/api/notifications?sort=title,asc&size=50', { token: A.token });
  const titles = s1.json.content.map((n) => n.title);
  const sorted = [...titles].sort((a, b) => a.localeCompare(b));
  const s2 = await rt.api('GET', '/api/notifications?sort=bogus,asc', { token: A.token });
  rec('DN-API-40', 'Sort by title asc works; sort on unknown column -> 400', s1.status === 200 && JSON.stringify(titles) === JSON.stringify(sorted) && s2.status === 400,
    `titles=${titles.join('|')} | bogus=${snip(s2)}`);
  const u1 = await rt.api('GET', '/api/notifications');
  const u2 = await rt.api('GET', '/api/notifications/unread-count');
  rec('DN-API-41', 'Unauthenticated notification calls -> 401', u1.status === 401 && u2.status === 401, `list=${u1.status} count=${u2.status}`);

  // ---------- links vs frontend router ----------
  const routes = [/^\/$/, /^\/customers(\/\d+)?(\?.*)?$/, /^\/products$/, /^\/invoices(\/new|\/\d+)?(\?.*)?$/, /^\/payments(\/\d+)?(\?.*)?$/,
    /^\/promises(\/\d+)?$/, /^\/disputes(\/\d+)?$/, /^\/notifications$/, /^\/users$/, /^\/roles$/];
  const links = new Map();
  for (const t of [adm, A.token, C.token, D.token, E.token, F.token, B.token]) {
    for (const n of (await notifs(t)).json.content) if (n.link) links.set(n.type + ' ' + n.link.replace(/\d+/g, '{id}'), n.link);
  }
  const bad = [...links.entries()].filter(([, l]) => !routes.some((re) => re.test(l)));
  rec('DN-API-42', 'Every notification link is a route in frontend/lib/core/router.dart',
    bad.length === 0, `links=${[...links.keys()].join(', ')} | not in router: ${bad.map(([k]) => k).join(', ') || 'none'}`);

  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify({ results, ctx: { myAdmin: myAdmin.username, A: A.username, B: B.username } }, null, 2));
  console.log(`\n${results.filter((x) => x.pass).length}/${results.length} passed`);
})().catch((e) => { console.error('API SUITE CRASHED', e); process.exit(1); });
