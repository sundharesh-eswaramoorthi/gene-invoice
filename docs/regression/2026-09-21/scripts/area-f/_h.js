// Area F — blast radius. Shared helpers for the area-f scripts.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const PARTS = path.join(__dirname, 'parts');
const RUN = path.join(__dirname, '..', '..');
const SHOTS = path.join(RUN, 'report');

/** Collects cases for one script and writes them to parts/<name>.json. */
function recorder(name) {
  const cases = [];
  const add = (c) => {
    const row = {
      id: c.id, feature: c.feature, kind: c.kind || 'API', ac: c.ac || '',
      title: c.title, status: c.status, severity: c.severity || '',
      steps: c.steps || '', expected: c.expected || '', actual: c.actual || '',
      evidence: c.evidence || '', codeRef: c.codeRef || '',
    };
    cases.push(row);
    console.log(`${row.id} ${row.status.padEnd(10)} ${row.title}${row.status === 'FAIL' ? '\n      actual: ' + row.actual : ''}`);
    return row;
  };
  /**
   * Runs `fn`, which returns {ok, actual, severity?}. A thrown error is a FAIL with the stack tail.
   */
  const check = async (meta, fn) => {
    try {
      const r = await fn();
      return add({ ...meta, status: r.ok ? 'PASS' : (r.status || 'FAIL'),
        severity: r.ok ? '' : (r.severity || 'medium'), actual: r.actual });
    } catch (e) {
      return add({ ...meta, status: 'FAIL', severity: meta.severity || 'medium',
        actual: `threw: ${e.message}`.slice(0, 600) });
    }
  };
  const save = () => {
    fs.mkdirSync(PARTS, { recursive: true });
    fs.writeFileSync(path.join(PARTS, `${name}.json`), JSON.stringify(cases, null, 2));
    const fails = cases.filter((c) => c.status === 'FAIL').length;
    console.log(`\n[${name}] ${cases.length} cases, ${cases.length - fails} pass, ${fails} fail`);
  };
  return { cases, add, check, save };
}

const money = (v) => Number(v == null ? 0 : v).toFixed(2);
const eq = (a, b) => money(a) === money(b);

/** admin token + admin user id (an ADMIN is assignable as every POC kind). */
async function admin() {
  const token = await rt.adminToken();
  const me = await rt.api('GET', '/api/auth/me', { token });
  return { token, id: me.json.id, me: me.json };
}

async function createProduct(token, prefix, price) {
  const r = await rt.api('POST', '/api/products', {
    token, body: { name: rt.uniq(prefix), description: 'area-f fixture', price, active: true },
  });
  if (r.status >= 300) throw new Error(`create product -> ${r.status} ${r.text}`);
  return r.json;
}

/** Creates an invoice. `items` are {productId, quantity, unitPrice}. */
async function createInvoice(token, { customerId, salesPocUserId, items, invoiceDate, notes, dueDate, paymentTerm }) {
  const r = await rt.api('POST', '/api/invoices', {
    token, body: { customerId, salesPocUserId, items, invoiceDate, notes, dueDate, paymentTerm },
  });
  if (r.status >= 300) throw new Error(`create invoice -> ${r.status} ${r.text}`);
  return r.json;
}

async function recordPayment(token, body) {
  const r = await rt.api('POST', '/api/payments', { token, body });
  if (r.status >= 300) throw new Error(`record payment -> ${r.status} ${r.text}`);
  return r.json;
}

/** Minimal CSV parser good enough for the exports (quoted fields, embedded commas/quotes). */
function parseCsv(text) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; }
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows.filter((r) => r.length > 1 || r[0] !== '');
}

/** Creates a role with exactly these privileges, and a user holding it. */
async function roleWith(token, prefix, privileges) {
  const r = await rt.api('POST', '/api/roles', {
    token, body: { name: rt.uniq(prefix).toUpperCase().replace(/-/g, '_'), description: 'area-f', privileges },
  });
  if (r.status >= 300) throw new Error(`create role -> ${r.status} ${r.text}`);
  const username = rt.uniq(prefix);
  const u = await rt.api('POST', '/api/users', {
    token, body: { username, email: `${username}@rt.local`, fullName: username.toUpperCase(),
      password: rt.PASSWORD, roleId: r.json.id, active: true },
  });
  if (u.status >= 300) throw new Error(`create user -> ${u.status} ${u.text}`);
  return { role: r.json, user: u.json, username, password: rt.PASSWORD };
}

const isoDay = (offsetDays = 0) => {
  const d = new Date(Date.now() + offsetDays * 86400000);
  return d.toISOString().slice(0, 10);
};

module.exports = { rt, recorder, money, eq, admin, createProduct, createInvoice,
  recordPayment, parseCsv, roleWith, isoDay, PARTS, SHOTS };
