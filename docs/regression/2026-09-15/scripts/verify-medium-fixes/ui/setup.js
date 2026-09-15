// Creates all data for the UI re-verification of the medium fixes (W-01..W-18) via the API.
// Names are prefixed "wm-". Writes state.json next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const inDays = (d) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 10);

(async () => {
  const T = await rt.adminToken();
  const S = { log: [] };
  const must = (r, what) => {
    S.log.push({ what, status: r.status, body: (r.text || '').slice(0, 200) });
    if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text}`);
    return r.json;
  };
  const seat = (custId, userId, primary) => rt.api('POST', `/api/customers/${custId}/pocs`, {
    token: T, body: { pocType: 'COLLECTION', userId, primary },
  });
  S.prod = (await rt.api('GET', '/api/products?size=10&sort=id,asc&filter=active:eq:true', { token: T })).json.content[0];
  S.sales = await rt.createStaff(T, 'SALES_POC', 'wm-sales');
  S.collA = await rt.createStaff(T, 'COLLECTION_POC', 'wm-collA');
  S.collB = await rt.createStaff(T, 'COLLECTION_POC', 'wm-collB');
  const invoice = async (customerId, unitPrice, what) => must(await rt.api('POST', '/api/invoices', {
    token: T, body: { customerId, salesPocUserId: S.sales.id, notes: 'wm', items: [{ productId: S.prod.id, quantity: 1, unitPrice }] },
  }), what);
  const pay = async (customerId, amount, invoiceIds, what) => must(await rt.api('POST', '/api/payments', {
    token: T, body: { customerId, amount, method: 'Cash', notes: 'wm', collectionPocUserId: S.collA.id, invoiceIds },
  }), what);

  // W-01: a user whose email another user will try to reuse (different case).
  S.dupUser = await rt.createStaff(T, 'VIEWER', 'wm-dup');

  // W-03 / W-06: customer whose primary Collection POC is A, with an open invoice.
  S.cPay = await rt.createCustomer(T, 'wm-pay');
  must(await seat(S.cPay.id, S.collA.id, true), 'seat cPay A');
  S.invPay = await invoice(S.cPay.id, 1000, 'invPay');

  // W-04: customer login (D-22).
  S.c22 = await rt.createCustomer(T, 'wm-d22');
  S.inv22 = await invoice(S.c22.id, 250, 'inv22');

  // W-05 / W-16 / W-17: customer with a 4,51,234.50 invoice and a payment, disputed by the customer.
  S.cDisp = await rt.createCustomer(T, 'wm-disp');
  S.invDisp = await invoice(S.cDisp.id, 451234.5, 'invDisp');
  S.payDisp = await pay(S.cDisp.id, 1000, [S.invDisp.id], 'payDisp');
  S.invDisp2 = await invoice(S.cDisp.id, 300, 'invDisp2'); // no dispute: the customer can raise one in the UI check
  const ct = await rt.login(S.cDisp.username, S.cDisp.password);
  S.dispInv = must(await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'INVOICE', targetId: S.invDisp.id, reason: 'wm D-39 invoice dispute' } }), 'dispInv');
  S.dispPay = must(await rt.api('POST', '/api/disputes', { token: ct, body: { targetType: 'PAYMENT', targetId: S.payDisp.id, reason: 'wm D-39 payment dispute' } }), 'dispPay');

  // W-07: primary Collection POC deactivated, backup active.
  S.p36 = await rt.createStaff(T, 'COLLECTION_POC', 'wm-d36primary');
  S.b36 = await rt.createStaff(T, 'COLLECTION_POC', 'wm-d36backup');
  S.c36 = await rt.createCustomer(T, 'wm-d36');
  must(await seat(S.c36.id, S.p36.id, true), 'seat c36 primary');
  must(await seat(S.c36.id, S.b36.id, false), 'seat c36 backup');
  S.del36 = must(await rt.api('DELETE', `/api/users/${S.p36.id}`, { token: T }), 'delete p36');
  S.c36Pocs = (await rt.api('GET', `/api/customers/${S.c36.id}/pocs`, { token: T })).json;

  // W-08: an OPEN promise for the Promises list override.
  S.c37 = await rt.createCustomer(T, 'wm-d37');
  S.inv37 = await invoice(S.c37.id, 500, 'inv37');
  S.prom37 = must(await rt.api('POST', '/api/promises', {
    token: T, body: { customerId: S.c37.id, amount: 500, promisedDate: inDays(10), collectionPocUserId: S.collA.id, notes: 'wm D-37', invoiceIds: [S.inv37.id] },
  }), 'prom37');

  // W-09: promise on invoice X, X then cancelled (plus a live invoice Y on the same promise).
  S.c9 = await rt.createCustomer(T, 'wm-card');
  S.inv9x = await invoice(S.c9.id, 100, 'inv9x');
  S.inv9y = await invoice(S.c9.id, 200, 'inv9y');
  S.prom9 = must(await rt.api('POST', '/api/promises', {
    token: T, body: { customerId: S.c9.id, amount: 300, promisedDate: inDays(7), collectionPocUserId: S.collA.id, notes: 'wm card', invoiceIds: [S.inv9x.id, S.inv9y.id] },
  }), 'prom9');
  S.cancel9x = must(await rt.api('POST', `/api/invoices/${S.inv9x.id}/cancel`, { token: T, body: { reason: 'wm cancel' } }), 'cancel inv9x');

  // W-12: ~1500-character dispute reasons (one with spaces, one unbroken).
  S.cLong = await rt.createCustomer(T, 'wm-long');
  S.invLong1 = await invoice(S.cLong.id, 120, 'invLong1');
  S.invLong2 = await invoice(S.cLong.id, 130, 'invLong2');
  const lt = await rt.login(S.cLong.username, S.cLong.password);
  // A 1500-character reason is refused with 409 (the reason also becomes the admin notification's
  // message, a 1000-character column), so the longest reason the app accepts, 1000, is used here.
  S.reason1500 = await (async () => {
    const r = await rt.api('POST', '/api/disputes', { token: lt, body: { targetType: 'INVOICE', targetId: S.invLong1.id, reason: 'wm '.repeat(500) } });
    return { status: r.status, body: r.text.slice(0, 200) };
  })();
  const words = ('wm-long-reason the customer says this invoice was billed twice for the same delivery and wants it corrected ').repeat(12).slice(0, 986) + ' END-OF-REASON';
  const unbroken = 'wm-unbroken-' + 'x'.repeat(984) + '-END';
  S.dispLong1 = must(await rt.api('POST', '/api/disputes', { token: lt, body: { targetType: 'INVOICE', targetId: S.invLong1.id, reason: words } }), 'dispLong1');
  S.dispLong2 = must(await rt.api('POST', '/api/disputes', { token: lt, body: { targetType: 'INVOICE', targetId: S.invLong2.id, reason: unbroken } }), 'dispLong2');
  S.longReasonLen = [words.length, unbroken.length];

  // W-13: role with INVOICE_VIEW + EXPORT_DATA + NOTIFICATION_VIEW, no INVOICE_MANAGE.
  const roleName = rt.uniq('wm-exporter');
  S.exportRole = must(await rt.api('POST', '/api/roles', {
    token: T, body: { name: roleName, description: 'wm D-21', privileges: ['INVOICE_VIEW', 'EXPORT_DATA', 'NOTIFICATION_VIEW'] },
  }), 'exportRole');
  const eu = rt.uniq('wm-exporter-user');
  S.exportUser = { ...must(await rt.api('POST', '/api/users', {
    token: T, body: { username: eu, email: `${eu}@rt.local`, fullName: eu, password: rt.PASSWORD, roleId: S.exportRole.id, active: true },
  }), 'exportUser'), username: eu, password: rt.PASSWORD };

  // Regression: an invoice to bulk-cancel from the UI.
  S.cBulk = await rt.createCustomer(T, 'wm-bulk');
  S.invBulk = await invoice(S.cBulk.id, 77, 'invBulk');

  S.adminUnread = (await rt.api('GET', '/api/notifications/unread-count', { token: T })).json;

  fs.writeFileSync(path.join(DIR, 'state.json'), JSON.stringify(S, null, 2));
  console.log(JSON.stringify({
    cPay: S.cPay.id, invPay: S.invPay.id, c22: S.c22.username, cDisp: S.cDisp.id, invDisp: [S.invDisp.id, S.invDisp.invoiceNumber, S.invDisp.total],
    payDisp: S.payDisp.id, dispInv: S.dispInv.id, dispPay: S.dispPay.id, c36: S.c36.id, del36: S.del36,
    c36Pocs: S.c36Pocs, c37: S.c37.id, prom37: [S.prom37.id, S.prom37.status], c9: S.c9.id, prom9: S.prom9.id,
    dispLong: [S.dispLong1.id, S.dispLong2.id], exportUser: S.exportUser.username, invBulk: S.invBulk.id, adminUnread: S.adminUnread,
  }, null, 1));
})().catch((e) => { console.error(e); process.exit(1); });
