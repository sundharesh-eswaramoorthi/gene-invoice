// Area E — permissions, privileges and scoping. Shared world + helpers.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const WORLD_FILE = path.join(__dirname, '.world.json');
const OUT_DIR = path.join(__dirname, 'out');

const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

/** Multipart upload — rt.api only speaks JSON. */
async function upload(token, { entityType, entityId, filename = 'e-doc.pdf', description, visibility, bytes = PDF }) {
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: 'application/pdf' }), filename);
  form.append('entityType', entityType);
  form.append('entityId', String(entityId));
  if (description !== undefined) form.append('description', description);
  if (visibility !== undefined) form.append('visibility', visibility);
  const res = await fetch(rt.API + '/api/documents', {
    method: 'POST',
    headers: token ? { Authorization: 'Bearer ' + token } : {},
    body: form,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text, headers: Object.fromEntries(res.headers) };
}

/** Upload without a file part — used to probe the privilege gate without writing bytes. */
async function uploadNoFile(token, { entityType, entityId }) {
  const form = new FormData();
  form.append('entityType', entityType);
  form.append('entityId', String(entityId));
  const res = await fetch(rt.API + '/api/documents', {
    method: 'POST',
    headers: token ? { Authorization: 'Bearer ' + token } : {},
    body: form,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* */ }
  return { status: res.status, json, text };
}

async function download(token, id) {
  const res = await fetch(rt.API + `/api/documents/${id}/download`, {
    headers: token ? { Authorization: 'Bearer ' + token } : {},
  });
  const text = await res.text();
  return { status: res.status, text, headers: Object.fromEntries(res.headers) };
}

const today = () => new Date().toISOString().slice(0, 10);
const plusDays = (isoDate, n) => {
  const d = new Date(isoDate + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

// ---- world ---------------------------------------------------------------------------

const STAFF_ROLES = ['ADMIN', 'CASHIER', 'VIEWER', 'SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC'];
const ALL_ROLES = [...STAFF_ROLES, 'CUSTOMER'];

async function buildWorld() {
  const admin = await rt.adminToken();
  const w = { roles: {}, users: {} };

  // One staff login per seeded staff role, plus two extra POCs to own the "foreign" records.
  for (const role of STAFF_ROLES) {
    const u = await rt.createStaff(admin, role, 'e');
    w.users[role] = { id: u.id, username: u.username, password: u.password, role };
  }
  const salesB = await rt.createStaff(admin, 'SALES_POC', 'e');
  const collB = await rt.createStaff(admin, 'COLLECTION_POC', 'e');
  w.users.SALES_POC_B = { id: salesB.id, username: salesB.username, password: salesB.password, role: 'SALES_POC' };
  w.users.COLLECTION_POC_B = { id: collB.id, username: collB.username, password: collB.password, role: 'COLLECTION_POC' };

  // A role holding EXPORT_DATA and nothing else, to prove the export conjunction (§8 Exports).
  const exportOnly = await rt.api('POST', '/api/roles', {
    token: admin,
    body: { name: rt.uniq('E-EXPORT-ONLY'), description: 'Area E: EXPORT_DATA without INVOICE_VIEW', privileges: ['EXPORT_DATA'] },
  });
  if (exportOnly.status >= 300) throw new Error('create export-only role -> ' + exportOnly.status + ' ' + exportOnly.text);
  w.roles.EXPORT_ONLY = exportOnly.json;
  const eoUser = rt.uniq('e');
  const eo = await rt.api('POST', '/api/users', {
    token: admin,
    body: { username: eoUser, email: `${eoUser}@rt.local`, fullName: eoUser.toUpperCase(), password: rt.PASSWORD, roleId: exportOnly.json.id, active: true },
  });
  if (eo.status >= 300) throw new Error('create export-only user -> ' + eo.status + ' ' + eo.text);
  w.users.EXPORT_ONLY = { id: eo.json.id, username: eoUser, password: rt.PASSWORD, role: 'EXPORT_ONLY' };

  // Product for invoice lines.
  const prod = await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq('e-prod'), description: 'Area E', price: '100.00', active: true },
  });
  if (prod.status >= 300) throw new Error('create product -> ' + prod.status + ' ' + prod.text);
  w.productId = prod.json.id;

  // custA — the in-book customer, NET_45, with a self-service login (CUSTOMER role).
  const custA = await rt.createCustomer(admin, 'e');
  await rt.api('PUT', `/api/customers/${custA.id}`, {
    token: admin,
    body: { name: custA.name, phone: custA.phone, email: custA.email, address: custA.address, paymentTerm: 'NET_45' },
  });
  w.custA = { id: custA.id, name: custA.name, phone: custA.phone, email: custA.email, address: custA.address };
  w.users.CUSTOMER = { id: null, username: custA.username, password: custA.password, role: 'CUSTOMER', customerId: custA.id };

  // custT — a throwaway customer the payment-terms capability probes write to.
  const custT = await rt.createCustomer(admin, 'e');
  w.custT = { id: custT.id, name: custT.name, phone: custT.phone, email: custT.email, address: custT.address };

  // custB — the foreign customer: no POC seat for any of the primary users.
  const custB = await rt.createCustomer(admin, 'e');
  w.custB = { id: custB.id, name: custB.name, username: custB.username, password: custB.password };

  // POC seats on custA for the success and collection POCs.
  for (const [user, type] of [[w.users.CUSTOMER_SUCCESS_POC, 'SUCCESS'], [w.users.COLLECTION_POC, 'COLLECTION']]) {
    const r = await rt.api('POST', `/api/customers/${custA.id}/pocs`, {
      token: admin, body: { userId: user.id, pocType: type, primary: true },
    });
    if (r.status >= 300) throw new Error(`seat ${type} -> ${r.status} ${r.text}`);
  }
  // And on custB for the "B" users, so custB belongs to somebody else's book.
  for (const [user, type] of [[w.users.COLLECTION_POC_B, 'COLLECTION']]) {
    await rt.api('POST', `/api/customers/${custB.id}/pocs`, {
      token: admin, body: { userId: user.id, pocType: type, primary: true },
    });
  }

  const line = { productId: w.productId, quantity: 1, unitPrice: '100.00' };

  // invA on custA, owned by the primary SALES_POC — in that POC's book.
  const invA = await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: custA.id, salesPocUserId: w.users.SALES_POC.id, items: [line], notes: 'area E invA' },
  });
  if (invA.status >= 300) throw new Error('invA -> ' + invA.status + ' ' + invA.text);
  w.invA = invA.json;

  // An already-overdue invoice on custA, so the overdue filter and the ageing chart have something.
  const overdue = await rt.api('POST', '/api/invoices', {
    token: admin,
    body: {
      customerId: custA.id, salesPocUserId: w.users.SALES_POC.id, items: [line],
      invoiceDate: plusDays(today(), -60) + 'T00:00:00Z',
      paymentTerm: 'CUSTOM', dueDate: plusDays(today(), -40), notes: 'area E overdue',
    },
  });
  if (overdue.status >= 300) throw new Error('invOverdue -> ' + overdue.status + ' ' + overdue.text);
  w.invOverdue = overdue.json;

  // invB on custB, owned by SALES_POC_B — outside the primary SALES_POC's book.
  const invB = await rt.api('POST', '/api/invoices', {
    token: admin,
    body: { customerId: custB.id, salesPocUserId: w.users.SALES_POC_B.id, items: [line], notes: 'area E invB' },
  });
  if (invB.status >= 300) throw new Error('invB -> ' + invB.status + ' ' + invB.text);
  w.invB = invB.json;

  // Payments: payA on custA (collection POC = primary), payB on custB.
  const payA = await rt.api('POST', '/api/payments', {
    token: admin,
    body: { customerId: custA.id, amount: '10.00', method: 'CASH', collectionPocUserId: w.users.COLLECTION_POC.id, invoiceIds: [] },
  });
  if (payA.status >= 300) throw new Error('payA -> ' + payA.status + ' ' + payA.text);
  w.payA = payA.json;
  const payB = await rt.api('POST', '/api/payments', {
    token: admin,
    body: { customerId: custB.id, amount: '10.00', method: 'CASH', collectionPocUserId: w.users.COLLECTION_POC_B.id, invoiceIds: [] },
  });
  if (payB.status >= 300) throw new Error('payB -> ' + payB.status + ' ' + payB.text);
  w.payB = payB.json;

  // Seed documents owned by admin: one INTERNAL and one SHARED on custA, one on each foreign record.
  w.docs = {};
  const mk = async (key, opts) => {
    const r = await upload(admin, opts);
    if (r.status >= 300) throw new Error(`doc ${key} -> ${r.status} ${r.text}`);
    w.docs[key] = r.json;
  };
  await mk('custA_internal', { entityType: 'CUSTOMER', entityId: custA.id, filename: 'e-custA-internal.pdf', description: 'internal' });
  await mk('custA_shared', { entityType: 'CUSTOMER', entityId: custA.id, filename: 'e-custA-shared.pdf', visibility: 'SHARED', description: 'shared' });
  await mk('invA_internal', { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-invA-internal.pdf' });
  await mk('invA_shared', { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-invA-shared.pdf', visibility: 'SHARED' });
  await mk('payA_internal', { entityType: 'PAYMENT', entityId: w.payA.id, filename: 'e-payA-internal.pdf' });
  await mk('custB_internal', { entityType: 'CUSTOMER', entityId: custB.id, filename: 'e-custB-internal.pdf' });
  await mk('invB_internal', { entityType: 'INVOICE', entityId: w.invB.id, filename: 'e-invB-internal.pdf' });
  await mk('payB_internal', { entityType: 'PAYMENT', entityId: w.payB.id, filename: 'e-payB-internal.pdf' });

  fs.writeFileSync(WORLD_FILE, JSON.stringify(w, null, 2));
  return w;
}

async function world() {
  if (fs.existsSync(WORLD_FILE)) return JSON.parse(fs.readFileSync(WORLD_FILE, 'utf8'));
  return buildWorld();
}

/** Logs in every user in the world and returns { ROLE_KEY: token }. */
async function tokens(w) {
  const out = {};
  for (const [key, u] of Object.entries(w.users)) {
    out[key] = await rt.login(u.username, u.password);
  }
  return out;
}

function writeOut(name, payload) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, name), JSON.stringify(payload, null, 2));
}

module.exports = {
  rt, PDF, upload, uploadNoFile, download, today, plusDays,
  STAFF_ROLES, ALL_ROLES, buildWorld, world, tokens, writeOut, OUT_DIR, WORLD_FILE,
};
