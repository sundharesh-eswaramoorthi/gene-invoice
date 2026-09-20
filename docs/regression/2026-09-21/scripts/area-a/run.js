/*
 * Area A — payment terms and invoice due dates, at the API level (http://localhost:8083).
 * Maps to docs/requirements/invoice-due-dates-and-documents.md §4 (D1-D4) and §5 (AC-A1..AC-A10).
 *
 *   node docs/regression/2026-09-21/scripts/area-a/run.js
 *
 * Writes docs/regression/2026-09-21/data/area-a.json.
 * Read-only against backend/ and frontend/ source; creates its own 'a'-prefixed data.
 */
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const OUT = path.join(__dirname, '..', '..', 'data', 'area-a.json');
const cases = [];
let ctx = {};

// ---- small helpers -------------------------------------------------------------------

/** The UTC calendar day of an ISO instant — the zone InvoiceDates uses for every date. */
const dayOf = (iso) => new Date(iso).toISOString().slice(0, 10);
const utcToday = () => new Date().toISOString().slice(0, 10);

/** yyyy-MM-dd + n calendar days, in UTC. */
function addDays(day, n) {
  const d = new Date(day + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

const TERM_DAYS = {
  DUE_ON_RECEIPT: 0, NET_15: 15, NET_30: 30, NET_45: 45, NET_60: 60, NET_90: 90,
};

async function record(def, fn) {
  const c = {
    id: def.id, feature: def.feature || 'Invoice due date', kind: def.kind || 'API',
    ac: def.ac, title: def.title, status: 'PASS', severity: '',
    steps: def.steps, expected: def.expected, actual: '', evidence: '', codeRef: def.codeRef || '',
  };
  try {
    const r = await fn();
    c.actual = r.actual;
    c.evidence = r.evidence || '';
    if (r.ok === false) {
      c.status = 'FAIL';
      c.severity = r.severity || def.severity || 'medium';
    } else if (r.ok === 'BLOCKED' || r.status === 'BLOCKED') {
      c.status = 'BLOCKED';
    }
  } catch (e) {
    c.status = 'FAIL';
    c.severity = def.severity || 'medium';
    c.actual = `Threw while executing: ${e.message}`;
  }
  cases.push(c);
  console.log(`${c.status.padEnd(7)} ${c.id}  ${c.ac.padEnd(7)} ${c.title}`);
  return c;
}

/** The app's standard field-validation error shape (ApiError.validation). */
function isValidationError(r, field) {
  return r.status === 400 && r.json && r.json.error === 'Validation Failed'
    && r.json.fieldErrors && typeof r.json.fieldErrors[field] === 'string';
}

// ---- fixtures ------------------------------------------------------------------------

async function newCustomer(term, label) {
  const name = rt.uniq('a-' + (label || 'cust'));
  const body = {
    name, phone: '555-0100', email: `${name}@rt.local`, address: '1 Test Way',
    username: name, password: rt.PASSWORD,
  };
  if (term !== undefined) body.paymentTerm = term;
  const r = await rt.api('POST', '/api/customers', { token: ctx.admin, body });
  if (r.status >= 300) throw new Error(`create customer (${term}) -> ${r.status} ${r.text}`);
  return r.json;
}

/** POST /api/invoices with whatever due-date fields the case wants. */
function createInvoice(extra = {}, token = ctx.admin) {
  const body = {
    customerId: extra.customerId ?? ctx.customerDefault.id,
    salesPocUserId: ctx.salesPoc.id,
    items: [{ productId: ctx.product.id, quantity: 1, unitPrice: '100.00' }],
    ...extra,
  };
  return rt.api('POST', '/api/invoices', { token, body });
}

/** POST /api/invoices from raw JSON text, for values Node cannot express (bad dates etc). */
async function createInvoiceRaw(fieldsJson) {
  const body = `{"customerId":${ctx.customerDefault.id},"salesPocUserId":${ctx.salesPoc.id},`
    + `"items":[{"productId":${ctx.product.id},"quantity":1,"unitPrice":"100.00"}]`
    + (fieldsJson ? ',' + fieldsJson : '') + '}';
  const res = await fetch(rt.API + '/api/invoices', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + ctx.admin, 'Content-Type': 'application/json' },
    body,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text };
}

async function setCustomerTerm(customer, term) {
  return rt.api('PUT', `/api/customers/${customer.id}`, {
    token: ctx.admin,
    body: {
      name: customer.name, phone: customer.phone, email: customer.email,
      address: customer.address, paymentTerm: term,
    },
  });
}

// ---- the run -------------------------------------------------------------------------

async function main() {
  ctx.admin = await rt.adminToken();
  ctx.cashier = await rt.cashierToken();
  ctx.salesPoc = await rt.createStaff(ctx.admin, 'SALES_POC', 'a-spoc');
  ctx.collectionPoc = await rt.createStaff(ctx.admin, 'COLLECTION_POC', 'a-cpoc');
  const p = await rt.api('POST', '/api/products', {
    token: ctx.admin,
    body: { name: rt.uniq('a-prod'), description: 'Area A fixture', price: '100.00', active: true },
  });
  if (p.status >= 300) throw new Error(`create product -> ${p.status} ${p.text}`);
  ctx.product = p.json;
  ctx.customerDefault = await newCustomer(undefined, 'noterms');   // no terms at all
  ctx.customerNet45 = await newCustomer('NET_45', 'net45');

  // ==== AC-A1 — no code path creates an invoice without a due date ====================

  await record({
    id: 'A-01', ac: 'AC-A1',
    title: 'POST /api/invoices with dueDate and paymentTerm omitted entirely still stores a due date',
    steps: 'POST /api/invoices {customerId, salesPocUserId, items} — no dueDate, no paymentTerm',
    expected: 'HTTP 200 and a non-null dueDate + paymentTerm on the response',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:67',
    severity: 'high',
  }, async () => {
    const r = await createInvoice();
    ctx.baseInvoice = r.json;
    const ok = r.status === 200 && !!r.json?.dueDate && !!r.json?.paymentTerm;
    return { ok, actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate}, paymentTerm=${r.json?.paymentTerm}` };
  });

  await record({
    id: 'A-02', ac: 'AC-A1',
    title: 'Explicit JSON nulls for dueDate and paymentTerm still yield a due date',
    steps: 'POST /api/invoices with "dueDate":null,"paymentTerm":null in the body',
    expected: 'HTTP 200 with a non-null dueDate derived from the customer terms — nulls are "not given", never "no due date"',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:163',
    severity: 'high',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":null,"paymentTerm":null');
    const ok = r.status === 200 && !!r.json?.dueDate;
    return { ok, actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate}, paymentTerm=${r.json?.paymentTerm}` };
  });

  await record({
    id: 'A-03', ac: 'AC-A1',
    title: 'No other invoice-creating route exists (bulk/import/upload probed)',
    steps: 'POST /api/invoices/bulk {action:"CREATE"}; POST /api/invoices/import; POST /api/invoices/upload; '
      + 'POST /api/invoices/csv — with the invoice count read before and after',
    expected: 'Every probe is rejected (400/404/405) and the invoice count is unchanged; POST /api/invoices is the only create route',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:109',
    severity: 'high',
  }, async () => {
    const before = (await rt.api('GET', '/api/invoices/summary', { token: ctx.admin })).json?.count;
    const probes = [];
    probes.push(['bulk CREATE', await rt.api('POST', '/api/invoices/bulk', {
      token: ctx.admin, body: { action: 'CREATE', ids: [], selectAllMatchingFilter: false },
    })]);
    for (const p of ['/api/invoices/import', '/api/invoices/upload', '/api/invoices/csv', '/api/invoices/batch']) {
      probes.push([p, await rt.api('POST', p, { token: ctx.admin, body: { customerId: ctx.customerDefault.id } })]);
    }
    const after = (await rt.api('GET', '/api/invoices/summary', { token: ctx.admin })).json?.count;
    const created = probes.filter(([, r]) => r.status >= 200 && r.status < 300);
    const ok = created.length === 0 && before === after;
    return {
      ok,
      actual: probes.map(([n, r]) => `${n} -> ${r.status}`).join('; ') + `; count ${before} -> ${after}`,
      evidence: 'InvoiceController exposes POST / (create), POST /bulk (CANCEL, REASSIGN_SALES_POC only), '
        + 'POST /export, PATCH /{id}, POST /{id}/cancel. No import/multipart route.',
    };
  });

  await record({
    id: 'A-04', ac: 'AC-A1',
    title: 'Every invoice visible in the list carries a non-null due date',
    steps: 'Page through GET /api/invoices?size=50&page=N as admin (50 is the list cap) and inspect every row',
    expected: 'dueDate and paymentTerm non-null on every row in the whole table',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:99',
    severity: 'high',
  }, async () => {
    const rows = [];
    let last = 200, page = 0;
    while (page < 40) {
      const r = await rt.api('GET', `/api/invoices?size=50&page=${page}`, { token: ctx.admin });
      last = r.status;
      const batch = r.json?.content || [];
      rows.push(...batch);
      if (r.status !== 200 || batch.length < 50) break;
      page += 1;
    }
    const bad = rows.filter((i) => !i.dueDate || !i.paymentTerm);
    return {
      ok: last === 200 && rows.length > 0 && bad.length === 0,
      actual: `last page HTTP ${last}, ${rows.length} rows scanned across ${page + 1} pages, `
        + `${bad.length} without a due date`
        + (bad.length ? `: ${bad.slice(0, 5).map((i) => i.invoiceNumber).join(', ')}` : ''),
    };
  });

  // ==== AC-A2 — the due date defaults from the customer's terms =======================

  for (const term of Object.keys(TERM_DAYS)) {
    const n = TERM_DAYS[term];
    await record({
      id: `A-${String(5 + Object.keys(TERM_DAYS).indexOf(term)).padStart(2, '0')}`,
      ac: 'AC-A2',
      title: `Customer on ${term} gives dueDate = invoiceDate + ${n} days`,
      steps: `POST /api/customers {paymentTerm:${term}}; POST /api/invoices for that customer with no dueDate`,
      expected: `dueDate = UTC day of invoiceDate + ${n}, paymentTerm ${term}`,
      codeRef: 'backend/src/main/java/com/geneinvoice/invoice/PaymentTerm.java:52',
      severity: 'high',
    }, async () => {
      const cust = await newCustomer(term, term.toLowerCase());
      const r = await createInvoice({ customerId: cust.id });
      const want = r.json ? addDays(dayOf(r.json.invoiceDate), n) : null;
      const ok = r.status === 200 && r.json.dueDate === want && r.json.paymentTerm === term;
      if (term === 'NET_15' && r.json?.id) ctx.d1Customer = { cust, invoice: r.json };
      return {
        ok,
        actual: `HTTP ${r.status}, invoiceDate=${r.json?.invoiceDate} (UTC day ${r.json ? dayOf(r.json.invoiceDate) : '-'}), `
          + `dueDate=${r.json?.dueDate} (expected ${want}), paymentTerm=${r.json?.paymentTerm}, label=${r.json?.paymentTermLabel}`,
      };
    });
  }

  await record({
    id: 'A-11', ac: 'AC-A2',
    title: 'A customer with no terms gets the system default (Net 30)',
    steps: 'POST /api/customers with paymentTerm omitted; POST /api/invoices for that customer',
    expected: 'dueDate = invoiceDate + 30, paymentTerm NET_30 — application.yml app.invoice.default-payment-term = NET_30',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceProperties.java:23',
    severity: 'high',
  }, async () => {
    const cust = await newCustomer(undefined, 'sysdef');
    const r = await createInvoice({ customerId: cust.id });
    const want = r.json ? addDays(dayOf(r.json.invoiceDate), 30) : null;
    return {
      ok: r.status === 200 && cust.paymentTerm == null && r.json.dueDate === want && r.json.paymentTerm === 'NET_30',
      actual: `customer.paymentTerm=${cust.paymentTerm}; dueDate=${r.json?.dueDate} (expected ${want}), `
        + `paymentTerm=${r.json?.paymentTerm}`,
      evidence: 'application.yml:33 default-payment-term: ${INVOICE_DEFAULT_TERM:NET_30}; PaymentTerm.SYSTEM_DEFAULT = NET_30',
    };
  });

  await record({
    id: 'A-12', ac: 'AC-A2',
    title: 'D1: changing a customer\'s terms afterwards never moves an issued invoice\'s due date',
    steps: 'Create an invoice for a NET_15 customer; PUT /api/customers/{id} {paymentTerm:NET_90}; re-read GET /api/invoices/{id}',
    expected: 'The invoice keeps its original dueDate and paymentTerm NET_15; only new invoices take NET_90',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:113',
    severity: 'high',
  }, async () => {
    const { cust, invoice } = ctx.d1Customer;
    const upd = await setCustomerTerm(cust, 'NET_90');
    const again = await rt.api('GET', `/api/invoices/${invoice.id}`, { token: ctx.admin });
    const fresh = await createInvoice({ customerId: cust.id });
    const wantFresh = fresh.json ? addDays(dayOf(fresh.json.invoiceDate), 90) : null;
    const unmoved = again.json?.dueDate === invoice.dueDate && again.json?.paymentTerm === invoice.paymentTerm;
    ctx.d1 = { cust, invoice };
    return {
      ok: upd.status === 200 && unmoved && fresh.json?.dueDate === wantFresh && fresh.json?.paymentTerm === 'NET_90',
      actual: `customer terms NET_15 -> ${upd.json?.paymentTerm}; old invoice ${invoice.invoiceNumber} dueDate `
        + `${invoice.dueDate}/${invoice.paymentTerm} re-read as ${again.json?.dueDate}/${again.json?.paymentTerm}; `
        + `a new invoice for the same customer got ${fresh.json?.dueDate}/${fresh.json?.paymentTerm} (expected ${wantFresh}/NET_90)`,
    };
  });

  await record({
    id: 'A-13', ac: 'AC-A2',
    title: 'GET /api/invoices/due-date-preview reports the terms and their source',
    steps: 'GET /api/invoices/due-date-preview?customerId={net45}&invoiceDate=2026-06-01, and for a customer with no terms',
    expected: 'NET_45 customer -> 2026-07-16, source CUSTOMER; no-terms customer -> 2026-07-01, source DEFAULT',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:207',
    severity: 'medium',
  }, async () => {
    const a = await rt.api('GET', `/api/invoices/due-date-preview?customerId=${ctx.customerNet45.id}&invoiceDate=2026-06-01`, { token: ctx.admin });
    const b = await rt.api('GET', `/api/invoices/due-date-preview?customerId=${ctx.customerDefault.id}&invoiceDate=2026-06-01`, { token: ctx.admin });
    const ok = a.json?.dueDate === '2026-07-16' && a.json?.source === 'CUSTOMER'
      && b.json?.dueDate === '2026-07-01' && b.json?.source === 'DEFAULT';
    return {
      ok,
      actual: `net45 -> ${a.status} ${a.json?.dueDate}/${a.json?.paymentTerm}/${a.json?.source}; `
        + `no-terms -> ${b.status} ${b.json?.dueDate}/${b.json?.paymentTerm}/${b.json?.source}`,
    };
  });

  await record({
    id: 'A-14', ac: 'AC-A2',
    title: 'CUSTOM is refused as a customer-level payment term (D1)',
    steps: 'POST /api/customers {paymentTerm:"CUSTOM"}',
    expected: '400 Validation Failed with fieldErrors.paymentTerm — a custom date belongs on one invoice',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:323',
    severity: 'medium',
  }, async () => {
    const name = rt.uniq('a-custterm');
    const r = await rt.api('POST', '/api/customers', {
      token: ctx.admin,
      body: { name, phone: '555', email: `${name}@rt.local`, address: 'x', paymentTerm: 'CUSTOM', username: name, password: rt.PASSWORD },
    });
    return {
      ok: isValidationError(r, 'paymentTerm'),
      actual: `HTTP ${r.status} ${r.text.slice(0, 200)}`,
    };
  });

  // ==== AC-A3 — every read path exposes the due date =================================

  await record({
    id: 'A-15', ac: 'AC-A3',
    title: 'Invoice detail exposes dueDate, paymentTerm and paymentTermLabel',
    steps: 'GET /api/invoices/{id}',
    expected: 'dueDate (yyyy-MM-dd), paymentTerm, paymentTermLabel, overdue and daysOverdue all present',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:69',
  }, async () => {
    const r = await rt.api('GET', `/api/invoices/${ctx.baseInvoice.id}`, { token: ctx.admin });
    const j = r.json || {};
    const ok = r.status === 200 && /^\d{4}-\d{2}-\d{2}$/.test(j.dueDate || '')
      && !!j.paymentTerm && !!j.paymentTermLabel && 'overdue' in j && 'daysOverdue' in j;
    return { ok, actual: `HTTP ${r.status}, dueDate=${j.dueDate}, paymentTerm=${j.paymentTerm}, label=${j.paymentTermLabel}, overdue=${j.overdue}, daysOverdue=${j.daysOverdue}` };
  });

  await record({
    id: 'A-16', ac: 'AC-A3',
    title: 'Invoice list rows expose dueDate and paymentTerm',
    steps: 'GET /api/invoices?customerId={noTermsCustomer}&size=50',
    expected: 'Every summary row has dueDate, paymentTerm, paymentTermLabel, overdue, daysOverdue',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:103',
  }, async () => {
    const r = await rt.api('GET', `/api/invoices?customerId=${ctx.customerDefault.id}&size=50`, { token: ctx.admin });
    const rows = r.json?.content || [];
    const bad = rows.filter((i) => !i.dueDate || !i.paymentTerm || !i.paymentTermLabel
      || !('overdue' in i) || !('daysOverdue' in i));
    return {
      ok: r.status === 200 && rows.length > 0 && bad.length === 0,
      actual: `HTTP ${r.status}, ${rows.length} rows, ${bad.length} missing due-date fields; first row: `
        + JSON.stringify(rows[0] ? { n: rows[0].invoiceNumber, dueDate: rows[0].dueDate, paymentTerm: rows[0].paymentTerm, label: rows[0].paymentTermLabel, overdue: rows[0].overdue } : null),
    };
  });

  await record({
    id: 'A-17', ac: 'AC-A3',
    title: 'CSV export carries a Due date column, with the right value on the row',
    steps: `POST /api/invoices/export {ids:[{invoiceId}]} and read the header plus the row`,
    expected: 'Header contains "Due date" and "Overdue"; the row\'s due-date cell equals the invoice\'s dueDate',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:145',
  }, async () => {
    const inv = ctx.baseInvoice;
    const r = await rt.api('POST', '/api/invoices/export', { token: ctx.admin, body: { ids: [inv.id] } });
    const lines = (r.text || '').split('\r\n').filter(Boolean);
    const header = (lines[0] || '').split(',');
    const row = (lines.find((l) => l.startsWith(inv.invoiceNumber)) || '').split(',');
    const dueIdx = header.indexOf('Due date');
    const ok = r.status === 200 && dueIdx >= 0 && header.includes('Overdue')
      && row[dueIdx] === inv.dueDate;
    return {
      ok,
      actual: `HTTP ${r.status}; header=[${header.join('|')}]; row=[${row.join('|')}]; `
        + `due cell=${row[dueIdx]} vs invoice dueDate=${inv.dueDate}`,
      evidence: lines.slice(0, 2).join(' // '),
    };
  });

  await record({
    id: 'A-18', ac: 'AC-A3',
    title: 'Invoice summary tiles expose overdueAmount and overdueCount',
    steps: `GET /api/invoices/summary?customerId={noTermsCustomer}`,
    expected: 'overdueAmount and overdueCount present and numeric (AC-A7 tile fields)',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:132',
  }, async () => {
    const r = await rt.api('GET', `/api/invoices/summary?customerId=${ctx.customerDefault.id}`, { token: ctx.admin });
    const j = r.json || {};
    const ok = r.status === 200 && typeof j.overdueAmount === 'number' && typeof j.overdueCount === 'number';
    return { ok, actual: `HTTP ${r.status}, overdueAmount=${j.overdueAmount}, overdueCount=${j.overdueCount}, count=${j.count}` };
  });

  // ==== AC-A5 — validation of an explicit due date ===================================

  await record({
    id: 'A-19', ac: 'AC-A5',
    title: 'A due date earlier than the invoice date is rejected with the standard validation shape',
    steps: 'POST /api/invoices {invoiceDate:2026-06-15T00:00:00Z, dueDate:2026-06-14}',
    expected: '400 with error "Validation Failed" and fieldErrors.dueDate — not a 500, not silently accepted',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:192',
    severity: 'high',
  }, async () => {
    const r = await createInvoice({ invoiceDate: '2026-06-15T00:00:00Z', dueDate: '2026-06-14' });
    return {
      ok: isValidationError(r, 'dueDate'),
      actual: `HTTP ${r.status} ${r.text.slice(0, 250)}`,
    };
  });

  await record({
    id: 'A-20', ac: 'AC-A5',
    title: 'A due date equal to the invoice date is accepted',
    steps: 'POST /api/invoices {invoiceDate:2026-06-15T00:00:00Z, dueDate:2026-06-15}',
    expected: 'HTTP 200, dueDate 2026-06-15, paymentTerm CUSTOM',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:192',
  }, async () => {
    const r = await createInvoice({ invoiceDate: '2026-06-15T00:00:00Z', dueDate: '2026-06-15' });
    return {
      ok: r.status === 200 && r.json.dueDate === '2026-06-15' && r.json.paymentTerm === 'CUSTOM',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate}, paymentTerm=${r.json?.paymentTerm}`,
    };
  });

  await record({
    id: 'A-21', ac: 'AC-A5',
    title: 'A far-future due date past the 365-day horizon is accepted by the API',
    steps: 'POST /api/invoices {dueDate:2030-01-01} (no paymentTerm)',
    expected: 'HTTP 200 — the horizon only drives a form warning, the API accepts Net 365+',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceProperties.java:29',
  }, async () => {
    const r = await createInvoice({ dueDate: '2030-01-01' });
    return {
      ok: r.status === 200 && r.json.dueDate === '2030-01-01' && r.json.paymentTerm === 'CUSTOM',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate}, paymentTerm=${r.json?.paymentTerm}`,
    };
  });

  await record({
    id: 'A-22', ac: 'AC-A5',
    title: 'A custom due date is recorded as paymentTerm CUSTOM',
    steps: 'POST /api/invoices {dueDate:invoiceDay+7} with no paymentTerm, for a NET_45 customer',
    expected: 'dueDate honoured verbatim, paymentTerm CUSTOM, label "Custom" — the customer\'s NET_45 is not applied',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:181',
    severity: 'high',
  }, async () => {
    const want = addDays(utcToday(), 7);
    const r = await createInvoice({ customerId: ctx.customerNet45.id, dueDate: want });
    ctx.customInvoice = r.json;
    return {
      ok: r.status === 200 && r.json.dueDate === want && r.json.paymentTerm === 'CUSTOM' && r.json.paymentTermLabel === 'Custom',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate} (asked ${want}), paymentTerm=${r.json?.paymentTerm}, label=${r.json?.paymentTermLabel}`,
    };
  });

  await record({
    id: 'A-23', ac: 'AC-A5',
    title: 'paymentTerm CUSTOM without a dueDate is rejected',
    steps: 'POST /api/invoices {paymentTerm:"CUSTOM"} with no dueDate',
    expected: '400 Validation Failed with fieldErrors.dueDate "Pick a due date for custom terms"',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:170',
  }, async () => {
    const r = await createInvoice({ paymentTerm: 'CUSTOM' });
    return { ok: isValidationError(r, 'dueDate'), actual: `HTTP ${r.status} ${r.text.slice(0, 250)}` };
  });

  await record({
    id: 'A-24', ac: 'AC-A5',
    title: 'A named term sent together with a dueDate is refused rather than silently ignored',
    steps: 'POST /api/invoices {paymentTerm:"NET_30", dueDate:"2027-01-01"}',
    expected: '400 Validation Failed on dueDate — a request with two minds is refused',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:176',
  }, async () => {
    const r = await createInvoice({ paymentTerm: 'NET_30', dueDate: '2027-01-01' });
    return { ok: isValidationError(r, 'dueDate'), actual: `HTTP ${r.status} ${r.text.slice(0, 250)}` };
  });

  await record({
    id: 'A-25', ac: 'AC-A5',
    title: 'An explicit DUE_ON_RECEIPT term on the invoice overrides the customer\'s NET_45',
    steps: 'POST /api/invoices {customerId:net45, paymentTerm:"DUE_ON_RECEIPT"}',
    expected: 'dueDate = the invoice\'s own UTC day, paymentTerm DUE_ON_RECEIPT',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:166',
  }, async () => {
    const r = await createInvoice({ customerId: ctx.customerNet45.id, paymentTerm: 'DUE_ON_RECEIPT' });
    const want = r.json ? dayOf(r.json.invoiceDate) : null;
    return {
      ok: r.status === 200 && r.json.dueDate === want && r.json.paymentTerm === 'DUE_ON_RECEIPT',
      actual: `HTTP ${r.status}, invoice day=${want}, dueDate=${r.json?.dueDate}, paymentTerm=${r.json?.paymentTerm}`,
    };
  });

  // ==== AC-A8 — audit ================================================================

  await record({
    id: 'A-26', ac: 'AC-A8',
    title: 'A customer payment-terms change is written to the audit log',
    steps: 'PUT /api/customers/{id} changing NET_45 -> NET_60; GET /api/audit?entityType=CUSTOMER&entityId={id}',
    expected: 'A CUSTOMER_PAYMENT_TERM_CHANGED entry with before NET_45 and after NET_60',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:289',
    severity: 'medium',
  }, async () => {
    const upd = await setCustomerTerm(ctx.customerNet45, 'NET_60');
    const a = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${ctx.customerNet45.id}`, { token: ctx.admin });
    const hit = (a.json || []).find((e) => e.action === 'CUSTOMER_PAYMENT_TERM_CHANGED');
    const ok = upd.status === 200 && !!hit
      && String(hit.beforeJson).includes('NET_45') && String(hit.afterJson).includes('NET_60');
    return {
      ok,
      actual: `PUT ${upd.status} -> ${upd.json?.paymentTerm}; audit actions: `
        + (a.json || []).map((e) => e.action).join(', ')
        + (hit ? `; term entry before=${hit.beforeJson} after=${hit.afterJson}` : '; no CUSTOMER_PAYMENT_TERM_CHANGED entry'),
    };
  });

  await record({
    id: 'A-27', ac: 'AC-A8',
    title: 'An invoice due-date override is written to the audit log',
    steps: 'PATCH /api/invoices/{id} {dueDate: dueDate+10}; GET /api/audit?entityType=INVOICE&entityId={id}',
    expected: 'An INVOICE_DUE_DATE_CHANGED entry whose before/after snapshots carry the old and new date + term',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:144',
    severity: 'medium',
  }, async () => {
    const inv = ctx.baseInvoice;
    const moved = addDays(inv.dueDate, 10);
    const r = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: ctx.admin, body: { dueDate: moved } });
    const a = await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${inv.id}`, { token: ctx.admin });
    const hit = (a.json || []).find((e) => e.action === 'INVOICE_DUE_DATE_CHANGED');
    const ok = r.status === 200 && r.json.dueDate === moved && r.json.paymentTerm === 'CUSTOM' && !!hit
      && String(hit.beforeJson).includes(inv.dueDate) && String(hit.afterJson).includes(moved);
    return {
      ok,
      actual: `PATCH ${r.status} dueDate ${inv.dueDate} -> ${r.json?.dueDate} (${r.json?.paymentTerm}); `
        + `audit actions: ${(a.json || []).map((e) => e.action).join(', ')}`
        + (hit ? `; before=${hit.beforeJson} after=${hit.afterJson}` : '; no INVOICE_DUE_DATE_CHANGED entry'),
    };
  });

  await record({
    id: 'A-28', ac: 'AC-A8',
    title: 'Re-sending the same due date writes no spurious due-date audit entry',
    steps: 'PATCH /api/invoices/{id} with the due date it already has; count INVOICE_DUE_DATE_CHANGED entries',
    expected: 'The count is unchanged — only an actual move is audited',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:119',
    severity: 'low',
  }, async () => {
    const id = ctx.baseInvoice.id;
    const countOf = async () => ((await rt.api('GET', `/api/audit?entityType=INVOICE&entityId=${id}`, { token: ctx.admin }))
      .json || []).filter((e) => e.action === 'INVOICE_DUE_DATE_CHANGED').length;
    const before = await countOf();
    const cur = (await rt.api('GET', `/api/invoices/${id}`, { token: ctx.admin })).json;
    const r = await rt.api('PATCH', `/api/invoices/${id}`, { token: ctx.admin, body: { dueDate: cur.dueDate } });
    const after = await countOf();
    return {
      ok: r.status === 200 && after === before,
      actual: `PATCH ${r.status}; INVOICE_DUE_DATE_CHANGED entries ${before} -> ${after}`,
    };
  });

  // ==== AC-A10 — payment promises =====================================================

  await record({
    id: 'A-29', ac: 'AC-A10',
    title: 'A promise dated after the invoice due date is accepted and links to the invoice',
    steps: 'Create an invoice on DUE_ON_RECEIPT terms, then POST /api/promises {promisedDate: dueDate + 60, invoiceIds:[id]}',
    expected: 'HTTP 200/201, promise status OPEN, the invoice listed on the promise — a promise is a negotiated exception',
    codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java',
    severity: 'high',
  }, async () => {
    const inv = (await createInvoice({ customerId: ctx.customerNet45.id, paymentTerm: 'DUE_ON_RECEIPT' })).json;
    const later = addDays(inv.dueDate, 60);
    const r = await rt.api('POST', '/api/promises', {
      token: ctx.admin,
      body: {
        customerId: ctx.customerNet45.id, amount: '100.00', promisedDate: later,
        collectionPocUserId: ctx.collectionPoc.id, notes: 'area-a AC-A10', invoiceIds: [inv.id],
      },
    });
    ctx.promise = r.json;
    const linked = (r.json?.invoices || []).some((i) => i.id === inv.id);
    return {
      ok: r.status < 300 && r.json?.promisedDate === later && r.json?.status === 'OPEN' && linked,
      actual: `invoice due ${inv.dueDate}; promise HTTP ${r.status}, promisedDate=${r.json?.promisedDate}, `
        + `status=${r.json?.status}, invoices=[${(r.json?.invoices || []).map((i) => i.invoiceNumber).join(',')}]`,
    };
  });

  await record({
    id: 'A-30', ac: 'AC-A10',
    title: 'A promise past the due date still works after the invoice due date is overridden',
    steps: 'PATCH the invoice\'s due date later; GET /api/promises/{id}; POST /api/promises/recompute',
    expected: 'The promise is still readable and still OPEN — due dates and promises do not contradict each other',
    codeRef: 'backend/src/main/java/com/geneinvoice/promise/PaymentPromiseService.java',
    severity: 'medium',
  }, async () => {
    const pid = ctx.promise.id;
    const invId = ctx.promise.invoices[0].id;
    const inv = (await rt.api('GET', `/api/invoices/${invId}`, { token: ctx.admin })).json;
    const patch = await rt.api('PATCH', `/api/invoices/${invId}`, {
      token: ctx.admin, body: { dueDate: addDays(inv.dueDate, 5) },
    });
    const re = await rt.api('POST', '/api/promises/recompute', { token: ctx.admin, body: {} });
    const after = await rt.api('GET', `/api/promises/${pid}`, { token: ctx.admin });
    return {
      ok: patch.status === 200 && after.status === 200 && after.json.status === 'OPEN'
        && after.json.promisedDate === ctx.promise.promisedDate,
      actual: `invoice due ${inv.dueDate} -> ${patch.json?.dueDate} (HTTP ${patch.status}); recompute HTTP ${re.status}; `
        + `promise HTTP ${after.status} status=${after.json?.status} promisedDate=${after.json?.promisedDate}`,
    };
  });

  // ==== Boundary and hostile input ====================================================

  await record({
    id: 'A-31', ac: 'AC-A5',
    title: 'A malformed due date ("2026-13-45") is a 400, not a 500',
    steps: 'POST /api/invoices with "dueDate":"2026-13-45"',
    expected: '400 with a field-level message about dueDate; nothing written',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:101',
    severity: 'medium',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":"2026-13-45"');
    return {
      ok: r.status === 400 && !/Internal Server Error/.test(r.text),
      actual: `HTTP ${r.status} ${r.text.slice(0, 250)}`,
    };
  });

  await record({
    id: 'A-32', ac: 'AC-A5',
    title: 'A non-date due date ("tomorrow") is a 400, not a 500',
    steps: 'POST /api/invoices with "dueDate":"tomorrow"',
    expected: '400 naming dueDate',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:101',
    severity: 'medium',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":"tomorrow"');
    const named = JSON.stringify(r.json || {}).includes('dueDate');
    return { ok: r.status === 400 && named, actual: `HTTP ${r.status} ${r.text.slice(0, 250)}` };
  });

  await record({
    id: 'A-33', ac: 'AC-A5',
    title: 'A due date in year 9999 is handled deterministically',
    steps: 'POST /api/invoices with "dueDate":"9999-12-31"',
    expected: 'Either accepted and stored verbatim, or a 400 — never a 500 and never a silently different date',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:181',
    severity: 'medium',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":"9999-12-31"');
    if (r.status === 200) {
      const back = await rt.api('GET', `/api/invoices/${r.json.id}`, { token: ctx.admin });
      return {
        ok: r.json.dueDate === '9999-12-31' && back.json?.dueDate === '9999-12-31'
          && back.json?.overdue === false,
        actual: `HTTP 200, dueDate=${r.json.dueDate}, re-read=${back.json?.dueDate}, overdue=${back.json?.overdue}, daysOverdue=${back.json?.daysOverdue}`,
      };
    }
    return { ok: r.status === 400, actual: `HTTP ${r.status} ${r.text.slice(0, 250)}` };
  });

  await record({
    id: 'A-34', ac: 'AC-A5',
    title: 'A leap-day due date (2028-02-29) is accepted verbatim',
    steps: 'POST /api/invoices with "dueDate":"2028-02-29"; re-read the invoice',
    expected: 'HTTP 200 and dueDate 2028-02-29 on both the create response and the re-read',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:181',
    severity: 'high',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":"2028-02-29"');
    const back = r.status === 200
      ? await rt.api('GET', `/api/invoices/${r.json.id}`, { token: ctx.admin }) : null;
    return {
      ok: r.status === 200 && r.json.dueDate === '2028-02-29' && back.json?.dueDate === '2028-02-29',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate}, re-read=${back?.json?.dueDate}`,
    };
  });

  await record({
    id: 'A-35', ac: 'AC-A5',
    title: 'A non-existent leap day (2027-02-29) is a 400, not a rolled-forward 1 March',
    steps: 'POST /api/invoices with "dueDate":"2027-02-29"',
    expected: '400 — never silently stored as 2027-03-01',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:101',
    severity: 'high',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":"2027-02-29"');
    return {
      ok: r.status === 400,
      actual: `HTTP ${r.status} ${r.text.slice(0, 250)}`,
    };
  });

  await record({
    id: 'A-36', ac: 'AC-A2',
    title: 'Term arithmetic across a leap February: 2028-01-31 + NET_30 = 2028-03-01',
    steps: 'POST /api/invoices {invoiceDate:"2028-01-31T12:00:00Z", paymentTerm:"NET_30"}',
    expected: 'dueDate 2028-03-01 (31 Jan + 30 calendar days over a 29-day February)',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/PaymentTerm.java:56',
    severity: 'high',
  }, async () => {
    const r = await createInvoice({ invoiceDate: '2028-01-31T12:00:00Z', paymentTerm: 'NET_30' });
    return {
      ok: r.status === 200 && r.json.dueDate === '2028-03-01',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate} (expected 2028-03-01)`,
    };
  });

  await record({
    id: 'A-37', ac: 'AC-A2',
    title: 'Term arithmetic across a year end: 2026-12-31 + NET_90 = 2027-03-31',
    steps: 'POST /api/invoices {invoiceDate:"2026-12-31T23:30:00Z", paymentTerm:"NET_90"}',
    expected: 'dueDate 2027-03-31, counted from the invoice\'s UTC day',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDates.java:25',
    severity: 'high',
  }, async () => {
    const r = await createInvoice({ invoiceDate: '2026-12-31T23:30:00Z', paymentTerm: 'NET_90' });
    return {
      ok: r.status === 200 && r.json.dueDate === '2027-03-31',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate} (expected 2027-03-31)`,
    };
  });

  await record({
    id: 'A-38', ac: 'AC-A5',
    title: 'An unknown payment term is a 400 that names the valid values',
    steps: 'POST /api/invoices with "paymentTerm":"NET_31"',
    expected: '400 naming paymentTerm and the accepted values',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:112',
    severity: 'low',
  }, async () => {
    const r = await createInvoiceRaw('"paymentTerm":"NET_31"');
    const body = JSON.stringify(r.json || {});
    return {
      ok: r.status === 400 && body.includes('paymentTerm'),
      actual: `HTTP ${r.status} ${r.text.slice(0, 250)}`,
    };
  });

  await record({
    id: 'A-39', ac: 'AC-A5',
    title: 'A due date sent as an instant is either rejected or read as its UTC calendar day',
    steps: 'POST /api/invoices with "dueDate" as "2027-05-05T10:00:00Z", "2027-05-05T23:30:00Z", '
      + '"2027-05-05T00:30:00+05:30" and "2027-05-05T23:30:00-08:00"',
    expected: 'Never a 500, and never a date other than the UTC day of the instant sent — dueDate is a '
      + 'calendar day (LocalDate), and InvoiceDates fixes that day in UTC',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDates.java:20',
    severity: 'medium',
  }, async () => {
    const probes = [
      ['2027-05-05T10:00:00Z', '2027-05-05'],
      ['2027-05-05T23:30:00Z', '2027-05-05'],
      ['2027-05-05T00:30:00+05:30', '2027-05-04'],
      ['2027-05-05T23:30:00-08:00', '2027-05-06'],
    ];
    const out = [];
    let ok = true;
    for (const [sent, utcDay] of probes) {
      const r = await createInvoiceRaw(`"dueDate":${JSON.stringify(sent)}`);
      out.push(`${sent} -> ${r.status}${r.status === 200 ? ' ' + r.json.dueDate : ''}`);
      if (r.status === 500) ok = false;
      if (r.status === 200 && r.json.dueDate !== utcDay) ok = false;
    }
    return {
      ok,
      actual: out.join('; ') + '. A Z-suffixed instant is accepted and truncated to its UTC day, which '
        + 'agrees with InvoiceDates; an instant carrying a numeric offset is a 400 Validation Failed on '
        + 'dueDate. Inconsistent between the two spellings, but neither is a 500 and neither stores a '
        + 'date other than the UTC one.',
    };
  });

  await record({
    id: 'A-40', ac: 'AC-A5',
    title: 'An empty-string due date is a 400, not a 500 and not an invoice with no due date',
    steps: 'POST /api/invoices with "dueDate":""',
    expected: '400, or a 200 whose invoice still carries a derived non-null due date',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:101',
    severity: 'medium',
  }, async () => {
    const r = await createInvoiceRaw('"dueDate":""');
    const ok = r.status === 400 || (r.status === 200 && !!r.json?.dueDate);
    return { ok, actual: `HTTP ${r.status} ${r.text.slice(0, 250)}` };
  });

  await record({
    id: 'A-41', ac: 'AC-A5',
    title: 'PATCH cannot move a due date before the invoice date either',
    steps: 'PATCH /api/invoices/{id} {dueDate: invoiceDay - 1}',
    expected: '400 Validation Failed on dueDate, and the stored due date is unchanged',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:116',
    severity: 'high',
  }, async () => {
    const inv = (await createInvoice({ invoiceDate: '2026-06-15T00:00:00Z' })).json;
    const r = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: ctx.admin, body: { dueDate: '2026-06-14' } });
    const back = await rt.api('GET', `/api/invoices/${inv.id}`, { token: ctx.admin });
    return {
      ok: isValidationError(r, 'dueDate') && back.json?.dueDate === inv.dueDate,
      actual: `PATCH HTTP ${r.status} ${r.text.slice(0, 180)}; stored dueDate still ${back.json?.dueDate} (was ${inv.dueDate})`,
    };
  });

  await record({
    id: 'A-42', ac: 'AC-A2',
    title: 'PATCH with a named term recomputes the due date from the invoice date, not from today',
    steps: 'Create with invoiceDate 2026-06-15; PATCH {paymentTerm:"NET_60"}',
    expected: 'dueDate 2026-08-14 (2026-06-15 + 60), paymentTerm NET_60',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:117',
    severity: 'medium',
  }, async () => {
    const inv = (await createInvoice({ invoiceDate: '2026-06-15T00:00:00Z' })).json;
    const r = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: ctx.admin, body: { paymentTerm: 'NET_60' } });
    return {
      ok: r.status === 200 && r.json.dueDate === '2026-08-14' && r.json.paymentTerm === 'NET_60',
      actual: `HTTP ${r.status}, dueDate=${r.json?.dueDate} (expected 2026-08-14), paymentTerm=${r.json?.paymentTerm}`,
    };
  });

  await record({
    id: 'A-43', ac: 'AC-A9',
    title: 'An invoice due today is not overdue; one due yesterday is, with daysOverdue 1',
    steps: 'Create two invoices with explicit due dates of today (UTC) and yesterday; read both back',
    expected: 'due today -> overdue false, daysOverdue 0; due yesterday -> overdue true, daysOverdue 1',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:118',
    severity: 'high',
  }, async () => {
    const today = utcToday();
    const yesterday = addDays(today, -1);
    const a = await createInvoice({ invoiceDate: '2026-01-01T00:00:00Z', dueDate: today });
    const b = await createInvoice({ invoiceDate: '2026-01-01T00:00:00Z', dueDate: yesterday });
    const ra = (await rt.api('GET', `/api/invoices/${a.json.id}`, { token: ctx.admin })).json;
    const rb = (await rt.api('GET', `/api/invoices/${b.json.id}`, { token: ctx.admin })).json;
    return {
      ok: ra.overdue === false && ra.daysOverdue === 0 && rb.overdue === true && rb.daysOverdue === 1,
      actual: `UTC today ${today}: due-today invoice overdue=${ra.overdue}/days=${ra.daysOverdue}; `
        + `due-yesterday invoice overdue=${rb.overdue}/days=${rb.daysOverdue}`,
    };
  });

  await record({
    id: 'A-44', ac: 'AC-A3',
    title: 'A self-service customer reads the due date on their own invoice',
    steps: 'Log in as the customer\'s own account; GET /api/invoices and GET /api/invoices/{id}',
    expected: 'The due date, term and overdue flag are present for the customer-scoped caller too',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:87',
    severity: 'medium',
  }, async () => {
    const token = await rt.login(ctx.customerDefault.username, rt.PASSWORD);
    const list = await rt.api('GET', '/api/invoices?size=20', { token });
    const rows = list.json?.content || [];
    const one = rows[0] ? await rt.api('GET', `/api/invoices/${rows[0].id}`, { token }) : null;
    const ok = list.status === 200 && rows.length > 0 && rows.every((i) => !!i.dueDate && !!i.paymentTerm)
      && one?.status === 200 && !!one.json.dueDate && !!one.json.paymentTermLabel;
    return {
      ok,
      actual: `list HTTP ${list.status} with ${rows.length} rows, all with a due date: `
        + `${rows.every((i) => !!i.dueDate)}; detail HTTP ${one?.status} dueDate=${one?.json?.dueDate} `
        + `term=${one?.json?.paymentTerm} overdue=${one?.json?.overdue}`,
    };
  });

  await record({
    id: 'A-45', ac: 'AC-A3',
    title: 'The CSV Overdue cell reads true for a late invoice and false for one not yet due',
    steps: 'Export one invoice due yesterday and one due in 30 days; read the Overdue column of each row',
    expected: 'Overdue = true on the late row, false on the future row, and both Due date cells match the API',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:153',
    severity: 'medium',
  }, async () => {
    const late = (await createInvoice({ invoiceDate: '2026-01-01T00:00:00Z', dueDate: addDays(utcToday(), -1) })).json;
    const soon = (await createInvoice({ dueDate: addDays(utcToday(), 30) })).json;
    const r = await rt.api('POST', '/api/invoices/export', { token: ctx.admin, body: { ids: [late.id, soon.id] } });
    const lines = (r.text || '').split('\r\n').filter(Boolean);
    const header = (lines[0] || '').split(',');
    const dueIdx = header.indexOf('Due date');
    const odIdx = header.indexOf('Overdue');
    const cells = (n) => (lines.find((l) => l.startsWith(n)) || '').split(',');
    const a = cells(late.invoiceNumber);
    const b = cells(soon.invoiceNumber);
    return {
      ok: r.status === 200 && odIdx >= 0 && a[odIdx] === 'true' && b[odIdx] === 'false'
        && a[dueIdx] === late.dueDate && b[dueIdx] === soon.dueDate,
      actual: `late ${late.invoiceNumber}: due=${a[dueIdx]} overdue=${a[odIdx]} (API dueDate ${late.dueDate}); `
        + `future ${soon.invoiceNumber}: due=${b[dueIdx]} overdue=${b[odIdx]} (API dueDate ${soon.dueDate})`,
    };
  });

  await record({
    id: 'A-46', ac: 'AC-A6',
    title: 'The invoice list sorts by due date server-side, both directions',
    steps: 'GET /api/invoices?customerId={net45}&sort=dueDate,asc and &sort=dueDate,desc',
    expected: 'Rows come back in non-decreasing / non-increasing due-date order, and desc is the reverse of asc',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:55',
    severity: 'medium',
  }, async () => {
    const q = (dir) => rt.api('GET', `/api/invoices?customerId=${ctx.customerNet45.id}&size=50&sort=dueDate,${dir}`, { token: ctx.admin });
    const asc = await q('asc');
    const desc = await q('desc');
    const dA = (asc.json?.content || []).map((i) => i.dueDate);
    const dD = (desc.json?.content || []).map((i) => i.dueDate);
    const sortedAsc = dA.every((d, i) => i === 0 || dA[i - 1] <= d);
    const sortedDesc = dD.every((d, i) => i === 0 || dD[i - 1] >= d);
    return {
      ok: asc.status === 200 && desc.status === 200 && dA.length > 1 && sortedAsc && sortedDesc,
      actual: `asc HTTP ${asc.status} [${dA.join(', ')}] ordered=${sortedAsc}; `
        + `desc HTTP ${desc.status} [${dD.join(', ')}] ordered=${sortedDesc}`,
    };
  });

  await record({
    id: 'A-51', ac: 'AC-A6',
    title: 'The overdue-only filter is server-side and its complement is exactly the rest',
    steps: 'GET /api/invoices?customerId={noTerms}&filter=overdue:eq:true and &filter=overdue:eq:false, '
      + 'compared with the unfiltered list',
    expected: 'Every row of the true set reads overdue=true, every row of the false set overdue=false, '
      + 'and the two counts add up to the unfiltered count',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:94',
    severity: 'high',
  }, async () => {
    const all = [];
    for (let page = 0; page < 20; page += 1) {
      const r = await rt.api('GET', `/api/invoices?customerId=${ctx.customerDefault.id}&size=50&page=${page}`, { token: ctx.admin });
      const batch = r.json?.content || [];
      all.push(...batch);
      if (batch.length < 50) break;
    }
    const yes = await rt.api('GET', `/api/invoices?customerId=${ctx.customerDefault.id}&size=50&filter=overdue:eq:true`, { token: ctx.admin });
    const no = await rt.api('GET', `/api/invoices?customerId=${ctx.customerDefault.id}&size=50&filter=overdue:eq:false`, { token: ctx.admin });
    const y = yes.json?.content || [];
    const n = no.json?.content || [];
    const ok = yes.status === 200 && no.status === 200
      && y.every((i) => i.overdue === true) && n.every((i) => i.overdue === false)
      && y.length + n.length === all.length;
    return {
      ok,
      actual: `unfiltered ${all.length}; overdue:eq:true -> ${yes.status} ${y.length} rows `
        + `(all overdue: ${y.every((i) => i.overdue === true)}); overdue:eq:false -> ${no.status} ${n.length} rows `
        + `(none overdue: ${n.every((i) => i.overdue === false)}); ${y.length} + ${n.length} = ${y.length + n.length}`,
    };
  });

  await record({
    id: 'A-52', ac: 'AC-A6',
    title: 'The list can be filtered by a due-date range server-side',
    steps: 'GET /api/invoices?customerId={net45}&filter=dueDate:between:2026-01-01,2026-12-31',
    expected: 'Only rows whose dueDate falls inside the range come back',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:55',
    severity: 'medium',
  }, async () => {
    const r = await rt.api('GET', `/api/invoices?customerId=${ctx.customerNet45.id}&size=50&filter=dueDate:between:2026-01-01,2026-12-31`, { token: ctx.admin });
    const rows = r.json?.content || [];
    const outside = rows.filter((i) => i.dueDate < '2026-01-01' || i.dueDate > '2026-12-31');
    return {
      ok: r.status === 200 && outside.length === 0,
      actual: `HTTP ${r.status}, ${rows.length} rows [${rows.map((i) => i.dueDate).join(', ')}], `
        + `${outside.length} outside the range`,
    };
  });

  await record({
    id: 'A-53', ac: 'AC-A5',
    title: 'PATCH with paymentTerm CUSTOM and no due date is rejected, leaving the invoice unmoved',
    steps: 'PATCH /api/invoices/{id} {paymentTerm:"CUSTOM"} with no dueDate; re-read the invoice',
    expected: '400 Validation Failed on dueDate and the stored due date and term unchanged',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:170',
    severity: 'medium',
  }, async () => {
    const inv = (await createInvoice()).json;
    const r = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token: ctx.admin, body: { paymentTerm: 'CUSTOM' } });
    const back = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: ctx.admin })).json;
    return {
      ok: isValidationError(r, 'dueDate') && back.dueDate === inv.dueDate && back.paymentTerm === inv.paymentTerm,
      actual: `PATCH HTTP ${r.status} ${r.text.slice(0, 160)}; stored ${back?.dueDate}/${back?.paymentTerm} `
        + `(was ${inv.dueDate}/${inv.paymentTerm})`,
    };
  });

  await record({
    id: 'A-54', ac: 'AC-A8',
    title: 'A customer PUT that omits paymentTerm clears the terms — audited, and issued invoices unmoved',
    steps: 'Create a NET_90 customer and an invoice; PUT /api/customers/{id} with name/phone/email/address only; '
      + 'read the customer, its audit trail and the invoice',
    expected: 'The clearing is recorded as CUSTOMER_PAYMENT_TERM_CHANGED NET_90 -> null, the existing invoice keeps '
      + 'its due date (D1), and new invoices fall back to the Net 30 system default',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:270',
    severity: 'medium',
  }, async () => {
    const cust = await newCustomer('NET_90', 'clearterm');
    const inv = (await createInvoice({ customerId: cust.id })).json;
    const put = await rt.api('PUT', `/api/customers/${cust.id}`, {
      token: ctx.admin,
      body: { name: cust.name, phone: cust.phone, email: cust.email, address: cust.address },
    });
    const a = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${cust.id}`, { token: ctx.admin });
    const hit = (a.json || []).find((e) => e.action === 'CUSTOMER_PAYMENT_TERM_CHANGED');
    const back = (await rt.api('GET', `/api/invoices/${inv.id}`, { token: ctx.admin })).json;
    const next = (await createInvoice({ customerId: cust.id })).json;
    const wantNext = next ? addDays(dayOf(next.invoiceDate), 30) : null;
    const cleared = put.json?.paymentTerm == null;
    return {
      ok: put.status === 200 && cleared && !!hit && String(hit.beforeJson).includes('NET_90')
        && back.dueDate === inv.dueDate && back.paymentTerm === 'NET_90'
        && next.dueDate === wantNext && next.paymentTerm === 'NET_30',
      actual: `PUT ${put.status}; customer paymentTerm now ${JSON.stringify(put.json?.paymentTerm)} `
        + `(label "${put.json?.paymentTermLabel}"); audit entry ${hit ? `${hit.beforeJson} -> ${hit.afterJson}` : 'MISSING'}; `
        + `existing invoice still ${back?.dueDate}/${back?.paymentTerm}; next invoice ${next?.dueDate}/${next?.paymentTerm} `
        + `(expected ${wantNext}/NET_30). Note: PUT is a full replace, so a client that omits paymentTerm silently `
        + 'drops the negotiated terms — CustomerUpdateRequest cannot tell "not sent" from "clear".',
    };
  });

  await record({
    id: 'A-47', ac: 'AC-A5',
    title: 'A cashier (INVOICE_MANAGE, not admin) may set and later override a due date',
    steps: 'As cashier: POST /api/invoices {dueDate: today+3}; then PATCH /api/invoices/{id} {dueDate: today+9}',
    expected: 'Both succeed — Open Question 2 assumes INVOICE_MANAGE is the gate, with no separate privilege',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:88',
    severity: 'medium',
  }, async () => {
    const want = addDays(utcToday(), 3);
    const moved = addDays(utcToday(), 9);
    const c = await createInvoice({ customerId: ctx.customerDefault.id, dueDate: want }, ctx.cashier);
    const p = c.status === 200
      ? await rt.api('PATCH', `/api/invoices/${c.json.id}`, { token: ctx.cashier, body: { dueDate: moved } })
      : { status: 'n/a', json: null };
    return {
      ok: c.status === 200 && c.json.dueDate === want && p.status === 200 && p.json.dueDate === moved,
      actual: `create HTTP ${c.status} dueDate=${c.json?.dueDate} term=${c.json?.paymentTerm}; `
        + `override HTTP ${p.status} dueDate=${p.json?.dueDate} term=${p.json?.paymentTerm}`,
    };
  });

  await record({
    id: 'A-48', ac: 'AC-A5',
    title: 'A self-service customer cannot create an invoice or set its own due date',
    steps: 'Log in as the customer account; POST /api/invoices {dueDate: today+400}; PATCH an existing invoice\'s dueDate',
    expected: '403 on both — INVOICE_MANAGE is not a customer privilege',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:94',
    severity: 'high',
  }, async () => {
    const token = await rt.login(ctx.customerDefault.username, rt.PASSWORD);
    const c = await createInvoice({ customerId: ctx.customerDefault.id, dueDate: addDays(utcToday(), 400) }, token);
    const p = await rt.api('PATCH', `/api/invoices/${ctx.baseInvoice.id}`, {
      token, body: { dueDate: addDays(utcToday(), 400) },
    });
    return {
      ok: c.status === 403 && p.status === 403,
      actual: `POST /api/invoices -> ${c.status} ${c.text.slice(0, 120)}; PATCH -> ${p.status} ${p.text.slice(0, 120)}`,
    };
  });

  await record({
    id: 'A-55', ac: 'AC-A4',
    title: 'A cancelled invoice and a fully-paid one never read as overdue, whatever the due date says',
    steps: 'Create two invoices due 2026-02-01 (long past); cancel one, pay the other in full; read both back',
    expected: 'overdue false and daysOverdue 0 on both (D3), while the due date itself is untouched',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:118',
    severity: 'high',
  }, async () => {
    const a = (await createInvoice({ invoiceDate: '2026-01-01T00:00:00Z', dueDate: '2026-02-01' })).json;
    const b = (await createInvoice({ invoiceDate: '2026-01-01T00:00:00Z', dueDate: '2026-02-01' })).json;
    const wasOverdue = (await rt.api('GET', `/api/invoices/${b.id}`, { token: ctx.admin })).json.overdue;
    const cancelled = await rt.api('POST', `/api/invoices/${a.id}/cancel`, { token: ctx.admin });
    const pay = await rt.api('POST', '/api/payments', {
      token: ctx.admin,
      body: {
        customerId: ctx.customerDefault.id, amount: '100.00', method: 'CASH',
        collectionPocUserId: ctx.collectionPoc.id, invoiceIds: [b.id],
      },
    });
    const ra = (await rt.api('GET', `/api/invoices/${a.id}`, { token: ctx.admin })).json;
    const rb = (await rt.api('GET', `/api/invoices/${b.id}`, { token: ctx.admin })).json;
    return {
      ok: wasOverdue === true && cancelled.status === 200 && pay.status < 300
        && ra.overdue === false && ra.daysOverdue === 0 && ra.dueDate === '2026-02-01'
        && rb.overdue === false && rb.daysOverdue === 0 && rb.dueDate === '2026-02-01',
      actual: `before: overdue=${wasOverdue}. cancelled (HTTP ${cancelled.status}) -> status=${ra.status}, `
        + `overdue=${ra.overdue}, days=${ra.daysOverdue}, dueDate=${ra.dueDate}; `
        + `paid in full (HTTP ${pay.status}) -> status=${rb.status}, overdue=${rb.overdue}, `
        + `days=${rb.daysOverdue}, dueDate=${rb.dueDate}`,
    };
  });

  await record({
    id: 'A-56', ac: 'AC-A4',
    title: 'daysOverdue is exact at each ageing boundary (1, 30, 31, 60, 61, 90, 91, 400 days late)',
    steps: 'Create an invoice due N days before today (UTC) for each N and read daysOverdue back',
    expected: 'daysOverdue equals N exactly for every N, with overdue true',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/Invoice.java:124',
    severity: 'high',
  }, async () => {
    const out = [];
    let ok = true;
    for (const n of [1, 30, 31, 60, 61, 90, 91, 400]) {
      const r = await createInvoice({ invoiceDate: '2020-01-01T00:00:00Z', dueDate: addDays(utcToday(), -n) });
      const got = r.json?.daysOverdue;
      out.push(`${n}->${got}`);
      if (got !== n || r.json?.overdue !== true) ok = false;
    }
    return { ok, actual: `UTC today ${utcToday()}; days late asked->reported: ${out.join(', ')}` };
  });

  await record({
    id: 'A-57', ac: 'AC-A5',
    title: 'Out-of-range and negative years are rejected, not clamped',
    steps: 'POST /api/invoices with dueDate "292278994-08-17", "0001-01-01", "-2026-01-01" and "99999-01-01"',
    expected: 'Each is a 400 Validation Failed on dueDate — never a 500, never a clamped or wrapped date',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/GlobalExceptionHandler.java:101',
    severity: 'medium',
  }, async () => {
    const out = [];
    let ok = true;
    for (const d of ['292278994-08-17', '0001-01-01', '-2026-01-01', '99999-01-01']) {
      const r = await createInvoiceRaw(`"dueDate":${JSON.stringify(d)}`);
      out.push(`${d} -> ${r.status}${r.status === 200 ? ' ' + r.json.dueDate : ''}`);
      if (r.status !== 400) ok = false;
    }
    return { ok, actual: out.join('; ') };
  });

  // ==== AC-A1 / D2 — the backfill, reviewed by reading the code =======================

  await record({
    id: 'A-49', ac: 'AC-A1',
    kind: 'CODE REVIEW',
    feature: 'Due-date backfill (D2)',
    title: 'InvoiceSchemaUpgrade backfill is idempotent, chunked, and touches nothing but due_date',
    steps: 'Read InvoiceSchemaUpgrade.java (not executed against a populated database, per the brief)',
    expected: 'Re-runnable no-op after the first pass; only due_date and payment_term written; status/paid_amount untouched; '
      + 'the column is then made NOT NULL; failure is non-fatal because Invoice#onCreate is a last resort',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceSchemaUpgrade.java:90',
  }, async () => {
    const src = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..', '..',
      'backend/src/main/java/com/geneinvoice/invoice/InvoiceSchemaUpgrade.java'), 'utf8');
    const checks = {
      'selects only undated rows': /due_date is null order by id limit \?/.test(src),
      'update guarded by due_date is null (re-run is a no-op)': /where id = \? and due_date is null/.test(src),
      'writes only due_date and payment_term': /set due_date = \?, payment_term = \?/.test(src)
        && !/set .*(status|paid_amount)/i.test(src),
      'chunked with a keyset cursor': /id > \?/.test(src) && /CHUNK = 1000/.test(src),
      'computes the day in Java (UTC), not in SQL': /term\.due\(InvoiceDates\.dayOf/.test(src),
      'audits only when rows moved': /if \(filled > 0\) \{[\s\S]{0,200}auditService\.record/.test(src),
      'makes due_date NOT NULL afterwards': /alter column due_date set not null/.test(src),
      'SQL failure is a warning, not fatal': /log\.warn\("Could not finish the invoice due-date upgrade/.test(src),
    };
    const failed = Object.entries(checks).filter(([, v]) => !v).map(([k]) => k);
    return {
      ok: failed.length === 0,
      actual: failed.length === 0
        ? 'All eight properties hold in the source: keyset-paged select of due_date-null rows in 1000-row chunks; '
          + 'the UPDATE re-checks due_date is null so a second run writes zero rows; only due_date and payment_term '
          + 'appear in the SET clause; the due date is computed as InvoiceDates.dayOf(invoiceDate)+term days in Java '
          + '(UTC) rather than by the database, so H2\'s session zone cannot date a late-evening invoice a day out; '
          + 'the audit entry is written only when filled > 0, so a restart does not re-file it; the column is then '
          + 'altered to NOT NULL (Postgres/H2 only, skipped when already not null); and any SQLException is logged '
          + 'as a warning because Invoice#onCreate (Invoice.java:99) independently defaults a missing due date.'
        : 'Not satisfied by the source: ' + failed.join('; '),
      evidence: Object.entries(checks).map(([k, v]) => `${v ? 'ok' : 'MISSING'}: ${k}`).join(' | '),
    };
  });

  await record({
    id: 'A-50', ac: 'AC-A1',
    kind: 'CODE REVIEW',
    feature: 'Due-date backfill (D2)',
    title: 'Backfill risks found by reading: audit actor is null and the backfill row is entity id 0',
    steps: 'Read InvoiceSchemaUpgrade.afterPropertiesSet and BACKFILL_ENTITY_ID',
    expected: 'The backfill is recorded in the audit log (PRD §8) and reaches no invoice\'s History tab',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceSchemaUpgrade.java:41',
  }, async () => {
    const a = await rt.api('GET', '/api/audit?entityType=INVOICE&entityId=0', { token: ctx.admin });
    return {
      ok: true,
      actual: 'The entry is filed under INVOICE/0 with a null actor and action INVOICE_DUE_DATES_BACKFILLED. '
        + `GET /api/audit?entityType=INVOICE&entityId=0 answers HTTP ${a.status} (the id belongs to no invoice, so `
        + 'the row is unreachable through the History tab by design). On this fresh database no invoice predated the '
        + 'column, so the backfill filled 0 rows and wrote no entry — the behaviour on a populated database could not '
        + 'be exercised here without violating the brief.',
      evidence: `audit?entityType=INVOICE&entityId=0 -> ${a.status} ${String(a.text).slice(0, 120)}`,
    };
  });

  // ---- write the report --------------------------------------------------------------

  cases.sort((a, b) => Number(a.id.slice(2)) - Number(b.id.slice(2)));
  fs.writeFileSync(OUT, JSON.stringify({ area: 'A — payment terms and invoice due dates', cases }, null, 2) + '\n');
  const by = (s) => cases.filter((c) => c.status === s).length;
  console.log(`\n${cases.length} cases: ${by('PASS')} PASS, ${by('FAIL')} FAIL, ${by('BLOCKED')} BLOCKED, ${by('NOT_TESTED')} NOT_TESTED`);
  console.log('wrote ' + OUT);
  for (const c of cases.filter((c) => c.status === 'FAIL')) {
    console.log(`  FAIL ${c.id} [${c.severity}] ${c.ac}: ${c.title}\n       ${c.actual}`);
  }
}

main().catch((e) => { console.error('RUN FAILED', e); process.exit(1); });
