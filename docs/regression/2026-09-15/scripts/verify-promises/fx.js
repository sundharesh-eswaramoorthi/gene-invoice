// Own fixtures for the verifier (independent of the reporting tester's helpers).
const rt = require('../lib.js');

const addDays = (n) => { const d = new Date(); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
let T, ME, PROD;
async function adm() {
  if (!T) { T = await rt.adminToken(); ME = (await rt.api('GET', '/api/auth/me', { token: T })).json.id; }
  return T;
}
async function meId() { await adm(); return ME; }
async function prod() {
  if (!PROD) {
    const r = await rt.api('POST', '/api/products', { token: await adm(), body: { name: rt.uniq('vprm-prod'), description: 'v', price: 1, active: true } });
    if (r.status >= 300) throw new Error('prod ' + r.text);
    PROD = r.json;
  }
  return PROD;
}
async function invoice(customerId, qty) {
  const r = await rt.api('POST', '/api/invoices', { token: await adm(), body: { customerId, salesPocUserId: await meId(), items: [{ productId: (await prod()).id, quantity: qty }] } });
  if (r.status >= 300) throw new Error('invoice ' + r.status + r.text);
  return r.json;
}
async function addPoc(customerId, userId, primary = true) {
  const r = await rt.api('POST', `/api/customers/${customerId}/pocs`, { token: await adm(), body: { pocType: 'COLLECTION', userId, primary } });
  if (r.status >= 300) throw new Error('addPoc ' + r.text);
  return r.json;
}
async function pay(customerId, amount, extra = {}) {
  return rt.api('POST', '/api/payments', { token: await adm(), body: { customerId, amount, method: 'cash', notes: 'v', collectionPocUserId: await meId(), ...extra } });
}
async function promise(body) { return rt.api('POST', '/api/promises', { token: await adm(), body }); }
async function get(id, token) { return rt.api('GET', `/api/promises/${id}`, { token: token || await adm() }); }
const brief = (p) => p && ({ id: p.id, status: p.status, amt: p.amount, f: p.fulfilledAmount, links: (p.payments || []).map((x) => x.id), inv: (p.invoices || []).map((i) => i.invoiceNumber + '/' + i.status), ov: p.statusOverridden, poc: p.collectionPoc && (p.collectionPoc.username + (p.collectionPoc.active === false ? '(inactive)' : '')) });
module.exports = { rt, addDays, adm, meId, invoice, addPoc, pay, promise, get, brief };
