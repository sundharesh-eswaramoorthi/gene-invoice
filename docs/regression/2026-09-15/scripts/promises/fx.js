// Fixtures for the promises area. All data is created fresh with unique names.
const rt = require('../lib.js');

const today = () => new Date().toISOString().slice(0, 10);
const addDays = (n) => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

let _admin, _adminId, _product;
async function admin() {
  if (!_admin) {
    _admin = await rt.adminToken();
    const me = await rt.api('GET', '/api/auth/me', { token: _admin });
    _adminId = me.json?.id;
  }
  return _admin;
}
async function adminId() { await admin(); return _adminId; }

async function product(price = 100) {
  const t = await admin();
  const r = await rt.api('POST', '/api/products', { token: t, body: { name: rt.uniq('promises-prod'), description: 'rt', price, active: true } });
  if (r.status >= 300) throw new Error('product ' + r.status + ' ' + r.text);
  return r.json;
}

async function unitProduct() {
  if (!_product) _product = await product(1);
  return _product;
}

/** Invoice with a total of `amount` (product priced 1, quantity = amount). */
async function invoice(customerId, amount, salesPocUserId) {
  const t = await admin();
  const p = await unitProduct();
  const r = await rt.api('POST', '/api/invoices', {
    token: t,
    body: { customerId, salesPocUserId: salesPocUserId || (await adminId()), items: [{ productId: p.id, quantity: amount }] },
  });
  if (r.status >= 300) throw new Error('invoice ' + r.status + ' ' + r.text);
  return r.json;
}

async function addPoc(customerId, userId, primary = true, pocType = 'COLLECTION') {
  const t = await admin();
  const r = await rt.api('POST', `/api/customers/${customerId}/pocs`, { token: t, body: { pocType, userId, primary } });
  if (r.status >= 300) throw new Error('addPoc ' + r.status + ' ' + r.text);
  return r.json;
}

async function pay(customerId, amount, { invoiceIds, promiseIds, poc, token } = {}) {
  const t = token || (await admin());
  const r = await rt.api('POST', '/api/payments', {
    token: t,
    body: { customerId, amount, method: 'cash', notes: 'rt', collectionPocUserId: poc || (await adminId()), invoiceIds, promiseIds },
  });
  return r;
}

async function promise(body, token) {
  return rt.api('POST', '/api/promises', { token: token || (await admin()), body });
}

async function getPromise(id, token) {
  return rt.api('GET', `/api/promises/${id}`, { token: token || (await admin()) });
}

/** Voids a payment the way the product allows: customer opens a dispute, admin approves. */
async function disputeAndApprove(custLoginToken, targetType, targetId, change) {
  const d = await rt.api('POST', '/api/disputes', {
    token: custLoginToken,
    body: { targetType, targetId, reason: 'rt promises re-entrancy', proposedChangeJson: JSON.stringify(change) },
  });
  if (d.status >= 300) throw new Error('dispute ' + d.status + ' ' + d.text);
  const a = await rt.api('POST', `/api/disputes/${d.json.id}/approve`, { token: await admin(), body: { adminNotes: 'rt' } });
  return { dispute: d, approve: a };
}

async function notificationsFor(token) {
  const r = await rt.api('GET', '/api/notifications?size=50', { token });
  return r.json?.content || [];
}

module.exports = { rt, today, addDays, admin, adminId, product, invoice, addPoc, pay, promise, getPromise, disputeAndApprove, notificationsFor };
