// Independent verification of reported disputes-notifications API failures.
const rt = require('../lib.js');
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 260)}`;
const num = (v) => (v == null ? null : Number(v));
const log = (...a) => console.log(...a);

async function setup(tag) {
  const seed = await rt.adminToken();
  const a = await rt.createStaff(seed, 'ADMIN', 'vdn' + tag);
  const adm = await rt.login(a.username, a.password);
  const me = (await rt.api('GET', '/api/auth/me', { token: adm })).json;
  const mkProduct = async (price) => (await rt.api('POST', '/api/products', { token: adm, body: { name: rt.uniq('vdnP' + price), price, active: true } })).json;
  const mkCust = async () => {
    const c = await rt.createCustomer(adm, 'vdn' + tag);
    const token = await rt.login(c.username, c.password);
    const cme = (await rt.api('GET', '/api/auth/me', { token })).json;
    return { ...c, token, userId: cme.id };
  };
  const mkInv = async (custId, productId, qty) => {
    const r = await rt.api('POST', '/api/invoices', { token: adm, body: { customerId: custId, salesPocUserId: me.id, items: [{ productId, quantity: qty }] } });
    if (r.status >= 300) throw new Error('inv ' + snip(r));
    return r.json;
  };
  const pay = async (custId, amount, invoiceIds) => {
    const r = await rt.api('POST', '/api/payments', { token: adm, body: { customerId: custId, amount, method: 'CASH', invoiceIds, collectionPocUserId: me.id } });
    if (r.status >= 300) throw new Error('pay ' + snip(r));
    return r.json;
  };
  const open = (token, targetType, targetId, reason, proposed) => rt.api('POST', '/api/disputes', { token, body: { targetType, targetId, reason, ...(proposed ? { proposedChangeJson: JSON.stringify(proposed) } : {}) } });
  const approve = (id, applied) => rt.api('POST', `/api/disputes/${id}/approve`, { token: adm, body: applied === undefined ? {} : { appliedChangeJson: typeof applied === 'string' ? applied : JSON.stringify(applied) } });
  const inv = async (id) => (await rt.api('GET', `/api/invoices/${id}`, { token: adm })).json;
  const payment = async (id) => (await rt.api('GET', `/api/payments/${id}`, { token: adm })).json;
  const credit = async (cid) => num((await rt.api('GET', `/api/customers/${cid}`, { token: adm })).json.creditBalance);
  return { seed, adm, me, mkProduct, mkCust, mkInv, pay, open, approve, inv, payment, credit };
}

(async () => {
  for (const run of [1, 2]) {
    log(`\n===== RUN ${run} =====`);
    const S = await setup('r' + run);
    const p100 = await S.mkProduct(100);
    const p50 = await S.mkProduct(50);

    // ---- DN-API-04 malformed bodies ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 1);
      const badType = await rt.api('POST', '/api/disputes', { token: C.token, body: { targetType: 'BOGUS', targetId: i.id, reason: 'x' } });
      const badId = await rt.api('POST', '/api/disputes', { token: C.token, body: { targetType: 'INVOICE', targetId: 'abc', reason: 'x' } });
      const noBody = await rt.api('POST', '/api/disputes', { token: C.token, headers: { 'Content-Type': 'application/json' } });
      const brokenJson = await fetch(rt.API + '/api/disputes', { method: 'POST', headers: { Authorization: 'Bearer ' + C.token, 'Content-Type': 'application/json' }, body: '{"targetType":' });
      log('DN-API-04 badType:', snip(badType));
      log('DN-API-04 badId:', snip(badId));
      log('DN-API-04 noBody:', snip(noBody));
      log('DN-API-04 brokenJson:', brokenJson.status, (await brokenJson.text()).slice(0, 200));
      // Same on another endpoint (not dispute specific)?
      const invBad = await rt.api('POST', '/api/invoices', { token: S.adm, body: { customerId: 'abc', items: [] } });
      log('DN-API-04 (control) invoices customerId abc:', snip(invBad));
      // dispute count for C must be 0
      const lc = await rt.api('GET', `/api/disputes?filter=${encodeURIComponent('customerId:eq:' + C.id)}`, { token: S.adm });
      log('DN-API-04 disputes created for C:', lc.json.totalElements);
    }

    // ---- DN-API-15 replace_items quantity 0 ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p50.id, 1); // placeholder
      const inv120 = await S.mkInv(C.id, p100.id, 1); // 100
      // make total 120: two lines? use unitPrice: invoice with 1 x 100 + ... simpler: create with product 100 x1 and product 50? 150. Use unitPrice 120
      const r0 = await rt.api('POST', '/api/invoices', { token: S.adm, body: { customerId: C.id, salesPocUserId: S.me.id, items: [{ productId: p100.id, quantity: 1, unitPrice: 120 }] } });
      const target = r0.json;
      await S.pay(C.id, 50, [target.id]);
      const before = await S.inv(target.id);
      const creditBefore = await S.credit(C.id);
      // control: normal invoice creation with qty 0
      const ctl = await rt.api('POST', '/api/invoices', { token: S.adm, body: { customerId: C.id, salesPocUserId: S.me.id, items: [{ productId: p100.id, quantity: 0 }] } });
      log('DN-API-15 control: create invoice qty 0 ->', snip(ctl));
      const d = (await S.open(C.token, 'INVOICE', target.id, 'qty zero test', { action: 'update_notes', notes: 'x' })).json;
      const ap = await S.approve(d.id, { action: 'replace_items', items: [{ productId: p50.id, quantity: 0 }] });
      const after = await S.inv(target.id);
      log(`DN-API-15 before total=${before.total} paid=${before.paidAmount} status=${before.status} credit=${creditBefore}`);
      log('DN-API-15 approve ->', snip(ap));
      log(`DN-API-15 after total=${after.total} paid=${after.paidAmount} status=${after.status} items=${JSON.stringify(after.items.map((x) => [x.quantity, x.lineTotal]))} credit=${await S.credit(C.id)}`);
      // negative quantity too
      const i2 = (await rt.api('POST', '/api/invoices', { token: S.adm, body: { customerId: C.id, salesPocUserId: S.me.id, items: [{ productId: p100.id, quantity: 1 }] } })).json;
      const d2 = (await S.open(C.token, 'INVOICE', i2.id, 'neg qty', { action: 'update_notes', notes: 'x' })).json;
      const ap2 = await S.approve(d2.id, { action: 'replace_items', items: [{ productId: p50.id, quantity: -2 }] });
      const after2 = await S.inv(i2.id);
      log('DN-API-15 negative qty approve ->', ap2.status, `total=${after2.total} status=${after2.status} items=${JSON.stringify(after2.items.map((x) => [x.quantity, x.lineTotal]))}`);
      void i;
    }

    // ---- DN-API-18 update_amount non-numeric ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 1);
      const P = await S.pay(C.id, 40, [i.id]);
      const d = (await S.open(C.token, 'PAYMENT', P.id, 'amount wrong', { action: 'update_meta', notes: 'x' })).json;
      const neg = await S.approve(d.id, { action: 'update_amount', amount: -5 });
      const miss = await S.approve(d.id, { action: 'update_amount' });
      const abc = await S.approve(d.id, { action: 'update_amount', amount: 'abc' });
      log('DN-API-18 neg:', snip(neg));
      log('DN-API-18 missing:', snip(miss));
      log('DN-API-18 abc:', snip(abc));
      const dAfter = (await rt.api('GET', `/api/disputes/${d.id}`, { token: S.adm })).json;
      const pAfter = await S.payment(P.id);
      log(`DN-API-18 dispute still ${dAfter.status}; payment amount=${pAfter.amount} status=${pAfter.status}`);
    }

    // ---- DN-API-23 cancel then void ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 1); // 100
      const P = await S.pay(C.id, 100, [i.id]);
      const c0 = await S.credit(C.id);
      const d1 = (await S.open(C.token, 'INVOICE', i.id, 'cancel it', { action: 'cancel' })).json;
      const a1 = await S.approve(d1.id);
      const c1 = await S.credit(C.id);
      const iAfter = await S.inv(i.id);
      const pMid = await S.payment(P.id);
      const d2 = (await S.open(C.token, 'PAYMENT', P.id, 'void it', { action: 'void' })).json;
      const a2 = await S.approve(d2.id);
      const c2 = await S.credit(C.id);
      const pAfter = await S.payment(P.id);
      const iAfter2 = await S.inv(i.id);
      log(`DN-API-23 credit start=${c0}; cancel approve=${a1.status}; invoice=${iAfter.status} paid=${iAfter.paidAmount}; credit after cancel=${c1}`);
      log(`DN-API-23 payment mid: status=${pMid.status} creditApplied=${pMid.creditApplied} allocations=${JSON.stringify(pMid.allocations)}`);
      log(`DN-API-23 void approve=${a2.status}; payment=${pAfter.status}; credit after void=${c2}; invoice ${iAfter2.status} paid=${iAfter2.paidAmount}`);
      // Control: does the direct (non-dispute) cancel refuse paid invoices? (cancel() requires refund first)
    }

    // ---- DN-API-24 replace_items shrink then void ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 3); // 300
      const P = await S.pay(C.id, 300, [i.id]);
      const d1 = (await S.open(C.token, 'INVOICE', i.id, 'too many', { action: 'replace_items', items: [{ productId: p50.id, quantity: 1 }] })).json;
      const a1 = await S.approve(d1.id);
      const c1 = await S.credit(C.id);
      const iMid = await S.inv(i.id);
      const d2 = (await S.open(C.token, 'PAYMENT', P.id, 'void it', { action: 'void' })).json;
      const a2 = await S.approve(d2.id);
      const c2 = await S.credit(C.id);
      const iAfter = await S.inv(i.id);
      const pAfter = await S.payment(P.id);
      log(`DN-API-24 shrink approve=${a1.status}; invoice total=${iMid.total} paid=${iMid.paidAmount} ${iMid.status}; credit=${c1}`);
      log(`DN-API-24 void approve=${a2.status}; payment=${pAfter.status}; credit=${c2}; invoice paid=${iAfter.paidAmount} ${iAfter.status} balance=${iAfter.balance}`);
      // consequence: can that credit be spent on a new invoice?
      const nInv = await S.mkInv(C.id, p100.id, 2); // 200
      const nAfter = await S.inv(nInv.id);
      log(`DN-API-24 new invoice 200 -> paid=${nAfter.paidAmount} status=${nAfter.status}; credit now=${await S.credit(C.id)}`);
    }

    // ---- DN-API-25 cashier export ----
    {
      const cash = await rt.cashierToken();
      const me = (await rt.api('GET', '/api/auth/me', { token: cash })).json;
      log('DN-API-25 cashier privileges include DISPUTE_VIEW?', JSON.stringify(me).includes('DISPUTE_VIEW'), 'EXPORT_DATA?', JSON.stringify(me).includes('EXPORT_DATA'));
      const l = await rt.api('GET', '/api/disputes', { token: cash });
      const g = await rt.api('GET', '/api/disputes/1', { token: cash });
      const ex = await rt.api('POST', '/api/disputes/export', { token: cash, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
      const exIds = await rt.api('POST', '/api/disputes/export', { token: cash, body: { action: 'EXPORT', ids: [1, 2, 3] } });
      const rows = ex.text.split('\n').filter((x) => x.trim()).length - 1;
      log(`DN-API-25 cashier list=${l.status} get=${g.status} export(all)=${ex.status} ${ex.headers['content-type']} rows=${rows} export(ids)=${exIds.status} rows=${exIds.text.split('\n').filter((x) => x.trim()).length - 1}`);
      log('DN-API-25 CSV head:', ex.text.split('\n').slice(0, 3).join(' || '));
      // A VIEWER has neither: control
      const v = await rt.createStaff(S.adm, 'VIEWER', 'vdnview');
      const vt = await rt.login(v.username, v.password);
      const vex = await rt.api('POST', '/api/disputes/export', { token: vt, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
      log('DN-API-25 VIEWER export:', vex.status);
      // Admin total for comparison
      const all = await rt.api('GET', '/api/disputes?size=1', { token: S.adm });
      log('DN-API-25 admin totalElements', all.json.totalElements);
    }

    // ---- DN-API-29 bulk foreign id ----
    {
      const a2 = await rt.createStaff(S.seed, 'ADMIN', 'vdnU2');
      const u2 = await rt.login(a2.username, a2.password);
      // produce notifications for both admins: a customer opens a dispute
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 1);
      await S.open(C.token, 'INVOICE', i.id, 'notif gen');
      const l1 = await rt.api('GET', `/api/notifications?size=50`, { token: S.adm });
      const l2 = await rt.api('GET', `/api/notifications?size=50`, { token: u2 });
      const n1 = l1.json.content.find((x) => !x.read && x.message === 'notif gen');
      const n2 = l2.json.content.find((x) => !x.read && x.message === 'notif gen');
      if (!n1 || !n2) { log('DN-API-29 setup failed', snip(l1), snip(l2)); throw new Error('no notifs'); }
      const b1 = await rt.api('POST', '/api/notifications/bulk', { token: S.adm, body: { action: 'MARK_READ', ids: [n1.id, n2.id] } });
      const b2 = await rt.api('POST', '/api/notifications/bulk', { token: S.adm, body: { action: 'MARK_READ', ids: [n2.id] } });
      const b3 = await rt.api('POST', '/api/notifications/bulk', { token: S.adm, body: { action: 'MARK_READ', ids: [99999999] } });
      const b4 = await rt.api('POST', '/api/notifications/bulk', { token: S.adm, body: { action: 'MARK_READ', ids: [n1.id] } });
      log(`DN-API-29 own=${n1.id} foreign=${n2.id}`);
      log('DN-API-29 [own, foreign]:', snip(b1));
      log('DN-API-29 [foreign]:', snip(b2));
      log('DN-API-29 [nonexistent]:', snip(b3));
      log('DN-API-29 [own already read]:', snip(b4));
      const n2after = (await rt.api('GET', `/api/notifications?size=50`, { token: u2 })).json.content.find((x) => x.id === n2.id);
      log('DN-API-29 foreign notification read after =', n2after.read);
    }

    // ---- DN-API-31 notification links ----
    {
      const C = await S.mkCust();
      const i = await S.mkInv(C.id, p100.id, 1);
      const d = (await S.open(C.token, 'INVOICE', i.id, 'link test', { action: 'update_notes', notes: 'n' })).json;
      const n = (await rt.api('GET', `/api/notifications?size=50&filter=${encodeURIComponent('type:eq:DISPUTE_OPENED')}`, { token: S.adm })).json.content.find((x) => x.message === 'link test');
      await S.approve(d.id);
      const cn = (await rt.api('GET', `/api/notifications?size=50`, { token: C.token })).json.content;
      log('DN-API-31 DISPUTE_OPENED link:', n && n.link, '| customer links:', JSON.stringify(cn.map((x) => [x.type, x.link])));
    }
  }
})().catch((e) => { console.error(e); process.exit(1); });
