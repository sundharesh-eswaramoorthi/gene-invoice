// API regression tests for the list/table framework. Run: node api.js [section...]
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = require('./state.json');
const P = S.P;

const results = [];
const out = path.join(__dirname, 'api-results.json');
function rec(id, feature, title, ok, expected, actual, evidence = '') {
  results.push({ id, feature, title, status: ok ? 'PASS' : 'FAIL', expected, actual, evidence });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${title}${ok ? '' : `\n     expected: ${expected}\n     actual:   ${actual}`}`);
}
function qs(params = {}) {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v)) v.forEach((x) => u.append(k, x)); else u.append(k, String(v));
  }
  const s = u.toString();
  return s ? `?${s}` : '';
}
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 180)}`;
const num = (v) => (v === null || v === undefined ? null : Number(v));
const eqSet = (a, b) => a.length === b.length && [...a].sort((x, y) => x - y).every((v, i) => v === [...b].sort((x, y) => x - y)[i]);
const round2 = (x) => Math.round(x * 100) / 100;

// Instant ISO -> BigInt nanos (keeps sub-ms order)
function toNs(iso) {
  if (iso == null) return null;
  const m = /^(.*?)(?:\.(\d+))?Z$/.exec(iso);
  if (!m) return BigInt(Date.parse(iso)) * 1000000n;
  return BigInt(Date.parse(m[1] + 'Z')) * 1000000n + BigInt((m[2] || '').padEnd(9, '0').slice(0, 9) || '0');
}
const todayUtc = () => new Date(new Date().toISOString().slice(0, 10) + 'T00:00:00Z');
const addDays = (d, n) => new Date(d.getTime() + n * 86400000);
const ymd = (d) => d.toISOString().slice(0, 10);

let admin; let coll; let cust;
const ENT = {
  invoices: { tok: () => admin, own: () => [`customerId:eq:${S.A}`] },
  payments: { tok: () => admin, own: () => [`customerId:eq:${S.A}`] },
  customers: { tok: () => admin, own: () => [`name:contains:${P}`] },
  promises: { tok: () => admin, own: () => [`customerId:eq:${S.A}`] },
  products: { tok: () => admin, own: () => [`name:contains:${P}`] },
  users: { tok: () => admin, own: () => [`username:contains:${P}`] },
  roles: { tok: () => admin, own: () => [`name:contains:${P}`] },
  disputes: { tok: () => admin, own: () => [`customerId:eq:${S.A}`] },
  notifications: { tok: () => coll, own: () => [] },
};
const list = (entity, params, token) => rt.api('GET', `/api/${entity}${qs(params)}`, { token: token || ENT[entity].tok() });

async function all(entity, filters, sort, token) {
  const rows = [];
  let page = 0; let total = null; let pages = 1;
  do {
    const r = await list(entity, { page, size: 50, sort, filter: filters }, token);
    if (r.status !== 200) throw new Error(`${entity} all -> ${snip(r)}`);
    rows.push(...r.json.content);
    total = r.json.totalElements; pages = r.json.totalPages; page++;
  } while (page < pages);
  return { rows, total };
}

// value accessor per sortable column (null => use id order heuristic)
const ACC = {
  invoices: { salesPocName: (r) => r.salesPoc?.fullName ?? null, createdAt: null },
  payments: { collectionPocName: (r) => r.collectionPoc?.fullName ?? null },
  promises: { collectionPocName: (r) => r.collectionPoc?.fullName ?? null },
  users: { roleName: (r) => r.role, createdAt: null },
  products: { createdAt: null },
};
function kindOf(type) { return ({ TEXT: 'text', ENUM: 'text', NUMBER: 'num', MONEY: 'num', DATE: 'date', BOOLEAN: 'bool', REFERENCE: 'num' })[type]; }

function expectedOrder(rows, acc, kind, asc, collate) {
  const val = (r) => {
    const v = acc(r);
    if (v === null || v === undefined) return null;
    if (kind === 'num') return Number(v);
    if (kind === 'bool') return v ? 1 : 0;
    if (kind === 'date') return /T/.test(v) ? toNs(v) : v; // LocalDate strings compare fine
    return String(v);
  };
  const cmpV = (a, b) => {
    if (a === null && b === null) return 0;
    if (a === null) return 1; // nulls "largest": last asc, first desc (Postgres default)
    if (b === null) return -1;
    if (typeof a === 'string' && kind === 'text') return collate(a, b);
    return a < b ? -1 : a > b ? 1 : 0;
  };
  return [...rows].sort((x, y) => {
    let c = cmpV(val(x), val(y));
    if (!asc) c = -c;
    return c !== 0 ? c : y.id - x.id; // id desc tiebreak always
  }).map((r) => r.id);
}
const byteCmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const locCmp = new Intl.Collator('en').compare;
// glibc en_US-like: punctuation/spaces ignored and case folded at first level, then tie-broken
const glibcKey = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
const glibcCmp = (a, b) => { const x = glibcKey(a); const y = glibcKey(b); return x < y ? -1 : x > y ? 1 : locCmp(a, b); };

// -------------------------------------------------------------------------------------------
async function schemas() {
  const r = await rt.api('GET', '/api/table-schemas', { token: admin });
  const expectEntities = ['invoices', 'payments', 'customers', 'promises', 'products', 'users', 'roles', 'disputes', 'notifications'];
  rec('TF-001', 'Schema', 'GET /api/table-schemas lists the 9 entities', r.status === 200 && eqSet(r.json.map((x) => expectEntities.indexOf(x)), expectEntities.map((_, i) => i)),
    '200 with the 9 entities', snip(r));

  // design doc §6 table
  const DOC = {
    invoices: 'id SF|invoiceNumber SF|customerId F|customerName SF|invoiceDate SF|total SF|paidAmount SF|balance SF|status SF|notes F|salesPocUserId F|salesPocName SF|createdAt SF',
    payments: 'id SF|customerId F|customerName SF|amount SF|creditApplied SF|method SF|notes F|paidAt SF|status SF|collectionPocUserId F|collectionPocName SF',
    customers: 'id SF|name SF|phone SF|email SF|address F|creditBalance SF|outstanding SF|successPocUserId F|collectionPocUserId F|createdAt SF',
    promises: 'id SF|customerId F|customerName SF|amount SF|fulfilledAmount SF|remainingAmount SF|promisedDate SF|status SF|statusOverridden SF|collectionPocUserId F|collectionPocName SF|notes F|invoiceId F|createdAt SF',
    products: 'id SF|name SF|description F|price SF|active SF|createdAt SF',
    users: 'id SF|username SF|email SF|fullName SF|active SF|roleName SF|customerId SF|createdAt SF',
    roles: 'id SF|name SF|description F',
    disputes: 'id SF|customerId SF|targetType SF|targetId SF|status SF|reason F|createdAt SF|resolvedAt SF',
    notifications: 'id SF|type SF|title SF|message F|read SF|createdAt SF',
  };
  const OPS = { TEXT: 'contains,eq,neq,isEmpty,isNotEmpty', ENUM: 'eq,neq,in,notIn', BOOLEAN: 'eq', NUMBER: 'eq,neq,gt,gte,lt,lte,between', MONEY: 'eq,neq,gt,gte,lt,lte,between', DATE: 'gte,lte,between,relative', REFERENCE: 'eq,neq,in,isEmpty,isNotEmpty' };
  const diffs = []; const SCH = {};
  for (const e of expectEntities) {
    const s = await rt.api('GET', `/api/table-schemas/${e}`, { token: admin });
    SCH[e] = s.json;
    if (s.status !== 200) { diffs.push(`${e}: ${s.status}`); continue; }
    if (JSON.stringify(s.json.pageSizes) !== '[10,20,50]' || s.json.defaultPageSize !== 20) diffs.push(`${e}: pageSizes ${s.json.pageSizes}/${s.json.defaultPageSize}`);
    const want = DOC[e].split('|').map((x) => x.split(' '));
    const got = s.json.columns.map((c) => [c.name, (c.sortable ? 'S' : '') + (c.filterable ? 'F' : '')]);
    if (JSON.stringify(want) !== JSON.stringify(got)) diffs.push(`${e}: doc ${JSON.stringify(want)} vs api ${JSON.stringify(got)}`);
    for (const c of s.json.columns) if (c.operators.join(',') !== OPS[c.type]) diffs.push(`${e}.${c.name} ops ${c.operators}`);
  }
  rec('TF-002', 'Schema', 'Every schema matches design doc §6 (columns, S/F flags, operators per type, sizes 10/20/50 default 20)',
    diffs.length === 0, 'no differences', diffs.join(' ; ') || 'none');
  const presets = SCH.invoices.datePresets.join(',');
  const u = await rt.api('GET', '/api/table-schemas/bogus', { token: admin });
  rec('TF-003', 'Schema', 'Unknown table schema is rejected; date presets published', u.status === 400 && presets === 'today,yesterday,last7Days,last30Days,thisMonth,lastMonth,thisYear,past,future',
    '400 for /bogus; presets today..future', `bogus -> ${snip(u)}; presets ${presets}`);
  return SCH;
}

async function sizes() {
  const bad = []; const def = [];
  for (const e of Object.keys(ENT)) {
    for (const s of [10, 20, 50]) {
      const r = await list(e, { size: s, filter: ENT[e].own() });
      if (r.status !== 200 || r.json.size !== s) bad.push(`${e} size=${s} -> ${r.status} size ${r.json?.size}`);
    }
    for (const s of [5, 25, 100, 0, -1, 1000]) {
      const r = await list(e, { size: s });
      if (r.status !== 400) bad.push(`${e} size=${s} -> ${snip(r)}`);
    }
    const d = await list(e, { filter: ENT[e].own() });
    if (d.status !== 200 || d.json.size !== 20) def.push(`${e} default size ${d.json?.size} (${d.status})`);
  }
  rec('TF-010', 'Paging', 'size 10/20/50 accepted and echoed; 5/25/100/0/-1/1000 -> 400 on all 9 lists', bad.length === 0, 'all as specified (design §5)', bad.join(' ; ') || 'all correct');
  rec('TF-011', 'Paging', 'Default page size is 20 on all 9 lists', def.length === 0, 'size=20 when omitted', def.join(' ; ') || 'all 20');

  const probes = [];
  for (const [label, p] of [['size=abc', { size: 'abc' }], ['page=abc', { page: 'abc' }], ['page=-1', { page: -1 }], ['page=50000000&size=50', { page: 50000000, size: 50 }], ['page=2147483647', { page: 2147483647, size: 10 }]]) {
    const r = await list('invoices', p);
    probes.push({ label, status: r.status, body: (r.text || '').slice(0, 140) });
  }
  const bad2 = probes.filter((x) => x.label.startsWith('size=abc') || x.label.startsWith('page=abc') || x.label === 'page=-1' ? x.status !== 400 : x.status >= 500);
  rec('TF-012', 'Paging validation', 'Malformed / out-of-range page & size are rejected cleanly (never 500)', bad2.length === 0,
    'size=abc, page=abc, page=-1 -> 400; huge page -> 400 or empty 200, never 500 (AC-D9)',
    probes.map((x) => `${x.label} -> ${x.status} ${x.body}`).join(' | '));
  return probes;
}

async function paging() {
  const issues = [];
  const detail = [];
  for (const e of Object.keys(ENT)) {
    const f = ENT[e].own();
    const first = await list(e, { size: 10, filter: f });
    const total = first.json.totalElements;
    const ids = []; let pages = first.json.totalPages;
    if (pages !== Math.max(1, Math.ceil(total / 10))) issues.push(`${e}: totalPages ${pages} for total ${total}`);
    for (let p = 0; p < pages; p++) {
      const r = await list(e, { size: 10, page: p, filter: f });
      if (r.json.page !== p) issues.push(`${e}: page echo ${r.json.page}!=${p}`);
      if (r.json.content.length !== (p < pages - 1 ? 10 : total - 10 * (pages - 1))) issues.push(`${e}: page ${p} has ${r.json.content.length}`);
      ids.push(...r.json.content.map((x) => x.id));
    }
    const uniq = new Set(ids);
    if (uniq.size !== ids.length || ids.length !== total) issues.push(`${e}: ${ids.length} ids, ${uniq.size} unique, total ${total}`);
    detail.push(`${e}:${total}/${pages}p`);
  }
  rec('TF-020', 'Paging', 'Paging through all pages (size 10) of own filtered data returns every row exactly once; page/totalElements/totalPages correct (9 lists)',
    issues.length === 0, 'union of pages == totalElements, no duplicates (AC-D2)', issues.join(' ; ') || `ok ${detail.join(' ')}`);

  // stable tiebreak with heavy duplicates
  const dupIssues = [];
  for (const [e, sort] of [['invoices', 'total,asc'], ['invoices', 'status,desc'], ['products', 'price,asc'], ['promises', 'status,asc'], ['customers', 'creditBalance,asc'], ['users', 'active,desc'], ['disputes', 'status,asc'], ['notifications', 'read,asc']]) {
    const f = ENT[e].own();
    const full = await all(e, f, sort);
    const ids = [];
    for (let p = 0; p * 10 < full.total; p++) {
      const r = await list(e, { size: 10, page: p, sort, filter: f });
      ids.push(...r.json.content.map((x) => x.id));
    }
    if (JSON.stringify(ids) !== JSON.stringify(full.rows.map((x) => x.id))) dupIssues.push(`${e} ${sort}: size-10 pages != size-50 order`);
    if (new Set(ids).size !== full.total) dupIssues.push(`${e} ${sort}: dup/missing`);
  }
  rec('TF-021', 'Paging', 'Sorting on a column full of ties pages stably (id tiebreak) — size-10 pages concatenate to the size-50 order', dupIssues.length === 0,
    'identical order, each row once (AC-D2)', dupIssues.join(' ; ') || 'identical');

  const r = await list('invoices', { page: 99, size: 10, filter: ENT.invoices.own() });
  rec('TF-022', 'Paging', 'Page beyond the end returns 200 with empty content and the real totals', r.status === 200 && r.json.content.length === 0 && r.json.totalElements === 24 && r.json.page === 99,
    '200, content [], totalElements 24, page 99', `${r.status} content=${r.json?.content?.length} total=${r.json?.totalElements} page=${r.json?.page} totalPages=${r.json?.totalPages}`);
}

async function sorting(SCH) {
  const issues = []; let checked = 0;
  for (const e of Object.keys(ENT)) {
    const f = ENT[e].own();
    for (const c of SCH[e].columns.filter((x) => x.sortable)) {
      for (const dir of ['asc', 'desc']) {
        let full;
        try { full = await all(e, f, `${c.name},${dir}`); } catch (err) { issues.push(`${e}.${c.name},${dir}: ${err.message}`); continue; }
        const got = full.rows.map((x) => x.id);
        let acc = ACC[e] && c.name in ACC[e] ? ACC[e][c.name] : (r) => r[c.name];
        let kind = kindOf(c.type);
        if (acc === null || (full.rows.length && !(c.name in full.rows[0]) && !(ACC[e] && ACC[e][c.name]))) { acc = (r) => r.id; kind = 'num'; } // createdAt not in DTO: creation order == id order for own rows
        const want1 = expectedOrder(full.rows, acc, kind, dir === 'asc', byteCmp);
        const want2 = expectedOrder(full.rows, acc, kind, dir === 'asc', locCmp);
        const want3 = expectedOrder(full.rows, acc, kind, dir === 'asc', glibcCmp);
        checked++;
        if (![want1, want2, want3].some((w) => JSON.stringify(got) === JSON.stringify(w))) {
          issues.push(`${e} sort=${c.name},${dir}: got ${got.slice(0, 8)} want ${want2.slice(0, 8)} (vals ${full.rows.slice(0, 5).map((r) => JSON.stringify(acc(r))).join(',')})`);
        }
      }
    }
  }
  rec('TF-030', 'Sorting', `Every sortable column of all 9 lists sorts asc and desc correctly (${checked} column-directions, id-desc tiebreak)`, issues.length === 0,
    'rows ordered by the column (nulls last asc / first desc), ties by id desc', issues.join(' ; ') || 'all ordered');

  const bad = [];
  for (const e of Object.keys(ENT)) {
    for (const c of SCH[e].columns.filter((x) => !x.sortable)) {
      const r = await list(e, { sort: `${c.name},asc` });
      if (r.status !== 400) bad.push(`${e}.${c.name} -> ${snip(r)}`);
    }
    const u = await list(e, { sort: 'nosuchcol,asc' });
    if (u.status !== 400) bad.push(`${e}.nosuchcol -> ${snip(u)}`);
  }
  rec('TF-031', 'Sorting', 'Sorting on an unsortable or unknown column -> 400 on all 9 lists', bad.length === 0, '400 "Column is not sortable"/"Unknown column"', bad.join(' ; ') || 'all 400');

  const a = await list('invoices', { sort: 'total', filter: ENT.invoices.own() });
  const b = await list('invoices', { sort: 'total,DESC', filter: ENT.invoices.own() });
  const c = await list('invoices', { sort: 'total,sideways', filter: ENT.invoices.own() });
  rec('TF-032', 'Sorting validation', 'Sort direction is validated (total,sideways -> 400); omitted dir -> asc; DESC case-insensitive',
    a.json?.sort === 'total,asc' && b.json?.sort === 'total,desc' && c.status === 400,
    'sort=total -> total,asc; total,DESC -> total,desc; total,sideways -> 400 (AC-D9: sort inputs validated server-side)',
    `total -> ${a.json?.sort}; total,DESC -> ${b.json?.sort}; total,sideways -> ${c.status} sort=${c.json?.sort}`);
}

async function filters() {
  const A = await all('invoices', [`customerId:eq:${S.A}`], 'id,asc');
  const rows = A.rows;
  const T = todayUtc();
  const inRange = (r, from, toIncl) => {
    const t = Date.parse(r.invoiceDate);
    return (from == null || t >= from.getTime()) && (toIncl == null || t < addDays(toIncl, 1).getTime());
  };
  const cases = [
    // [label, filters, predicate]
    ['total:gt:100', (r) => +r.total > 100], ['total:gte:100', (r) => +r.total >= 100], ['total:lt:100', (r) => +r.total < 100],
    ['total:lte:100', (r) => +r.total <= 100], ['total:eq:100', (r) => +r.total === 100], ['total:neq:100', (r) => +r.total !== 100],
    ['total:between:50,100', (r) => +r.total >= 50 && +r.total <= 100], ['total:between:100,50', () => false],
    ['balance:gt:0', (r) => +r.balance > 0], ['paidAmount:gt:0', (r) => +r.paidAmount > 0], ['total:eq:75.50', (r) => +r.total === 75.5],
    ['status:eq:UNPAID', (r) => r.status === 'UNPAID'], ['status:neq:CANCELLED', (r) => r.status !== 'CANCELLED'],
    ['status:in:UNPAID,PARTIALLY_PAID', (r) => ['UNPAID', 'PARTIALLY_PAID'].includes(r.status)],
    ['status:in: UNPAID , PARTIALLY_PAID ,', (r) => ['UNPAID', 'PARTIALLY_PAID'].includes(r.status)],
    ['status:notIn:FULLY_PAID,CANCELLED', (r) => !['FULLY_PAID', 'CANCELLED'].includes(r.status)],
    ['status:eq:unpaid', (r) => r.status === 'UNPAID'],
    [`invoiceDate:gte:${ymd(addDays(T, -10))}`, (r) => inRange(r, addDays(T, -10), null)],
    [`invoiceDate:lte:${ymd(addDays(T, -10))}`, (r) => inRange(r, null, addDays(T, -10))],
    [`invoiceDate:lte:${ymd(addDays(T, -1))}`, (r) => inRange(r, null, addDays(T, -1))],
    [`invoiceDate:between:${ymd(addDays(T, -30))},${ymd(addDays(T, -3))}`, (r) => inRange(r, addDays(T, -30), addDays(T, -3))],
    ['invoiceDate:relative:today', (r) => inRange(r, T, T)],
    ['invoiceDate:relative:yesterday', (r) => inRange(r, addDays(T, -1), addDays(T, -1))],
    ['invoiceDate:relative:last7Days', (r) => inRange(r, addDays(T, -6), T)],
    ['invoiceDate:relative:last30Days', (r) => inRange(r, addDays(T, -29), T)],
    ['invoiceDate:relative:thisMonth', (r) => inRange(r, new Date(Date.UTC(T.getUTCFullYear(), T.getUTCMonth(), 1)), T)],
    ['invoiceDate:relative:lastMonth', (r) => inRange(r, new Date(Date.UTC(T.getUTCFullYear(), T.getUTCMonth() - 1, 1)), new Date(Date.UTC(T.getUTCFullYear(), T.getUTCMonth(), 0)))],
    ['invoiceDate:relative:thisYear', (r) => inRange(r, new Date(Date.UTC(T.getUTCFullYear(), 0, 1)), T)],
    ['invoiceDate:relative:past', (r) => inRange(r, null, addDays(T, -1))],
    ['invoiceDate:relative:future', (r) => inRange(r, addDays(T, 1), null)],
    [`invoiceDate:gte:${addDays(T, -3).toISOString()}`, (r) => Date.parse(r.invoiceDate) >= addDays(T, -3).getTime()],
    ['invoiceNumber:contains:inv-', () => true],
    [`customerName:eq:${P}-cust-01`, () => true], [`customerName:contains:${P.toUpperCase()}`, () => true],
    [`salesPocUserId:eq:${S.sales.id}`, () => true], ['salesPocUserId:isEmpty:', () => false], ['salesPocUserId:isNotEmpty:', () => true],
    [`salesPocUserId:in:${S.sales.id},${S.myAdmin.id}`, () => true], [`salesPocUserId:neq:${S.sales.id}`, () => false],
    [`id:between:${rows[3].id},${rows[8].id}`, (r) => r.id >= rows[3].id && r.id <= rows[8].id],
    ['createdAt:relative:today', () => true],
  ];
  const bad = [];
  for (const [f, pred] of cases) {
    const r = await all('invoices', [`customerId:eq:${S.A}`, f], 'id,asc').catch((e) => ({ err: e.message }));
    if (r.err) { bad.push(`${f} -> ${r.err}`); continue; }
    const want = rows.filter(pred).map((x) => x.id);
    const got = r.rows.map((x) => x.id);
    if (!eqSet(got, want)) bad.push(`${f}: got ${got.length} [${got.filter((i) => !want.includes(i)).slice(0, 4)} extra] want ${want.length} [${want.filter((i) => !got.includes(i)).slice(0, 4)} missing]`);
  }
  rec('TF-040', 'Filters (invoices)', `Money/number/enum/date/relative/text/reference operators on invoices match a local computation (${cases.length} filters)`,
    bad.length === 0, 'each filtered id set equals the locally computed set', bad.join(' ; ') || 'all match');

  // notes text operators
  const nb = [];
  const notesOf = {}; for (const inv of S.invoices) notesOf[inv.id] = inv.notes;
  for (const [f, pred] of [
    ['notes:contains:RUSH', (n) => (n || '').toLowerCase().includes('rush')], ['notes:eq:rush', (n) => n === 'rush'],
    ['notes:neq:rush', (n) => n !== 'rush'], ['notes:isEmpty:', (n) => n == null || n.trim() === ''],
    ['notes:isNotEmpty:', (n) => n != null && n.trim() !== ''], ['notes:contains:ref, 50% off_now', (n) => (n || '').includes('ref, 50% off_now')],
    ['notes:contains:Wire: ref', (n) => (n || '').includes('Wire: ref')], ['notes:contains:%', (n) => (n || '').includes('%')], ['notes:contains:_', (n) => (n || '').includes('_')],
  ]) {
    const r = await all('invoices', [`customerId:eq:${S.A}`, f], 'id,asc');
    const want = rows.filter((x) => pred(notesOf[x.id])).map((x) => x.id);
    if (!eqSet(r.rows.map((x) => x.id), want)) nb.push(`${f}: got ${r.rows.length} want ${want.length}`);
  }
  rec('TF-041', 'Filters (text)', 'Text contains (case-insensitive) / eq / neq (includes nulls) / isEmpty (null or blank) / isNotEmpty on invoice notes', nb.length === 0,
    'matches local computation', nb.join(' ; ') || 'all match');

  // special characters & injection on customers.name
  const sp = S.special; const base = `name:contains:${P}`;
  const sc = [
    ['%', [sp.quote.id, sp.pct.id]], ['_', [sp.quote.id, sp.und.id]], ['off: x', [sp.quote.id]], [`O'Brien, "Q"`, [sp.quote.id]],
    ["' OR 1=1 --", [sp.sqli.id]], ['\\', []], ['%%', []], ['\'); DROP TABLE customers; --', []], ['*', []],
  ];
  const sbad = [];
  for (const [v, want] of sc) {
    const r = await list('customers', { size: 50, filter: [base, `name:contains:${v}`] });
    if (r.status !== 200) { sbad.push(`contains ${JSON.stringify(v)} -> ${snip(r)}`); continue; }
    if (!eqSet(r.json.content.map((x) => x.id), want)) sbad.push(`contains ${JSON.stringify(v)}: got ${r.json.content.map((x) => x.name).join('|')}`);
  }
  const eqr = await list('customers', { filter: [`name:eq:${P} ' OR 1=1 --`] });
  if (!(eqr.status === 200 && eqr.json.totalElements === 1)) sbad.push(`eq sqli -> ${snip(eqr)}`);
  const eqColon = await list('customers', { filter: [`name:eq:${P} O'Brien, "Q" 50%_off: x`] });
  if (!(eqColon.status === 200 && eqColon.json.totalElements === 1)) sbad.push(`eq with colon/comma/quote -> ${snip(eqColon)}`);
  const inj = await list('invoices', { filter: ["status:in:UNPAID') OR 1=1 --"] });
  if (inj.status !== 400) sbad.push(`enum injection -> ${snip(inj)}`);
  const numInj = await list('invoices', { filter: ['total:gt:0 OR 1=1'] });
  if (numInj.status !== 400) sbad.push(`number injection -> ${snip(numInj)}`);
  const refInj = await list('invoices', { filter: ['customerId:eq:1 OR 1=1'] });
  if (refInj.status !== 400) sbad.push(`reference injection -> ${snip(refInj)}`);
  rec('TF-042', 'Filters (special chars)', "Values with % _ : , quotes, backslash and SQL-injection strings are plain data (customers name contains/eq; enum/number/reference injection -> 400)",
    sbad.length === 0, 'exact literal matches only, no 500, no extra rows; bad typed values 400', sbad.join(' ; ') || 'all literal');

  // customers: seat reference filters + phone/email isEmpty
  const cb = [];
  const cust = S.customers.map((c) => c.id);
  const seatCases = [
    [`collectionPocUserId:eq:${S.coll.id}`, cust.slice(0, 15)], ['collectionPocUserId:isEmpty:', cust.slice(15)],
    ['successPocUserId:isNotEmpty:', cust.slice(0, 8)], [`successPocUserId:in:${S.success.id},999999`, cust.slice(0, 8)],
    [`successPocUserId:neq:${S.success.id}`, cust.slice(8)],
    ['phone:isEmpty:', S.customers.filter((_, i) => i % 4 === 0).map((c) => c.id)],
    ['email:isNotEmpty:', S.customers.filter((_, i) => i % 5 !== 0).map((c) => c.id)],
    ['address:contains:Unit', S.customers.filter((_, i) => i % 2).map((c) => c.id)],
    ['outstanding:gt:0', [S.A, S.B]], ['creditBalance:eq:0', cust],
  ];
  for (const [f, want] of seatCases) {
    const r = await all('customers', [`name:contains:${P}-cust-`, f], 'id,asc').catch((e) => ({ err: e.message }));
    if (r.err) { cb.push(`${f} -> ${r.err}`); continue; }
    if (!eqSet(r.rows.map((x) => x.id), want)) cb.push(`${f}: got ${r.rows.length} want ${want.length}`);
  }
  rec('TF-043', 'Filters (customers / seats)', 'Customer seat reference filters (eq/in/neq/isEmpty/isNotEmpty), text isEmpty on phone/email, money filters on outstanding/credit', cb.length === 0,
    'matches seeded seats and fields', cb.join(' ; ') || 'all match');

  // promises: invoiceId reference, promisedDate relative, booleans; products/users/notifications booleans
  const pr = await all('promises', [`customerId:eq:${S.A}`], 'id,asc');
  const pb = [];
  const Ainv = S.invoices.filter((i) => i.customerId === S.A);
  const pcases = [
    [`invoiceId:eq:${Ainv[1].id}`, (p) => p.invoices.some((i) => i.id === Ainv[1].id)], ['invoiceId:isEmpty:', (p) => p.invoices.length === 0],
    [`invoiceId:in:${Ainv[1].id},${Ainv[4].id}`, (p) => p.invoices.some((i) => [Ainv[1].id, Ainv[4].id].includes(i.id))],
    ['promisedDate:relative:past', (p) => p.promisedDate < ymd(T)], ['promisedDate:relative:future', (p) => p.promisedDate > ymd(T)],
    ['promisedDate:relative:today', (p) => p.promisedDate === ymd(T)], [`promisedDate:lte:${ymd(T)}`, (p) => p.promisedDate <= ymd(T)],
    [`promisedDate:between:${ymd(addDays(T, -5))},${ymd(addDays(T, 3))}`, (p) => p.promisedDate >= ymd(addDays(T, -5)) && p.promisedDate <= ymd(addDays(T, 3))],
    ['statusOverridden:eq:false', () => true], ['remainingAmount:gt:0', (p) => +p.remainingAmount > 0], ['notes:contains:"soon"', (p) => (p.notes || '').includes('"soon"')],
    [`collectionPocUserId:eq:${S.coll.id}`, () => true], ['amount:between:50,120', (p) => +p.amount >= 50 && +p.amount <= 120],
  ];
  for (const [f, pred] of pcases) {
    const r = await all('promises', [`customerId:eq:${S.A}`, f], 'id,asc').catch((e) => ({ err: e.message }));
    if (r.err) { pb.push(`${f} -> ${r.err}`); continue; }
    const want = pr.rows.filter(pred).map((x) => x.id);
    if (!eqSet(r.rows.map((x) => x.id), want)) pb.push(`${f}: got ${r.rows.length} want ${want.length}`);
  }
  const inv = await list('promises', { invoiceId: Ainv[1].id, size: 50 });
  if (!(inv.status === 200 && inv.json.totalElements === pr.rows.filter((p) => p.invoices.some((i) => i.id === Ainv[1].id)).length)) pb.push(`?invoiceId= -> ${snip(inv)}`);
  rec('TF-044', 'Filters (promises)', 'Promise invoiceId link-table filter (eq/in/isEmpty, and ?invoiceId= convenience param), promisedDate LocalDate presets/between, boolean, money', pb.length === 0,
    'matches local computation; ?invoiceId=N no longer 400 (candidate 1 fix)', pb.join(' ; ') || 'all match');

  const ob = [];
  const chk = async (e, f, extra, want, tok) => {
    const r = await all(e, [...extra, f], 'id,asc', tok).catch((x) => ({ err: x.message }));
    if (r.err) ob.push(`${e} ${f} -> ${r.err}`); else if (r.total !== want) ob.push(`${e} ${f}: ${r.total} != ${want}`);
  };
  await chk('products', 'active:eq:false', [`name:contains:${P}`], 2);
  await chk('products', 'active:eq:TRUE', [`name:contains:${P}`], 10);
  await chk('products', 'price:eq:60', [`name:contains:${P}`], 2);
  await chk('products', 'description:isEmpty:', [`name:contains:${P}`], 3); // i%3==0 except i=0
  await chk('products', 'description:contains:"deluxe"', [`name:contains:${P}`], 1);
  await chk('users', 'active:eq:false', [`username:contains:${P}u`], 1);
  await chk('users', 'roleName:eq:VIEWER', [`username:contains:${P}`], 10);
  await chk('users', 'customerId:isNotEmpty:', [`username:contains:${P}`], 0); // NUMBER column doesn't support isNotEmpty -> expect error recorded
  await chk('disputes', 'targetType:eq:INVOICE', [`customerId:eq:${S.A}`], 3);
  await chk('disputes', 'status:in:PENDING', [`customerId:eq:${S.A}`], 3);
  await chk('disputes', 'reason:contains:\' OR 1=1 --', [`customerId:eq:${S.A}`], 1);
  await chk('disputes', 'resolvedAt:relative:today', [`customerId:eq:${S.A}`], 0);
  await chk('roles', 'description:isEmpty:', [`name:contains:${P}`], 2);
  await chk('payments', 'method:contains:wire: REF,1', [`customerId:eq:${S.A}`], 1);
  await chk('payments', 'status:eq:ACTIVE', [`customerId:eq:${S.A}`], S.payments.length);
  await chk('payments', 'amount:lte:40', [`customerId:eq:${S.A}`], S.payments.filter((p) => +p.amount <= 40).length);
  await chk('payments', 'paidAt:relative:today', [`customerId:eq:${S.A}`], S.payments.length);
  await chk('notifications', 'type:eq:POC_ASSIGNED', [], null, coll);
  const numbered = ob.filter((x) => !x.includes('customerId:isNotEmpty') && !x.includes('POC_ASSIGNED'));
  const notif = await list('notifications', { size: 50, filter: ['read:eq:false'] }, coll);
  const notifT = await list('notifications', { size: 50, filter: ['title:isNotEmpty:', 'createdAt:relative:last7Days'] }, coll);
  rec('TF-045', 'Filters (other lists)', 'Boolean eq (true/TRUE/false), enum in, text isEmpty/contains, date presets on products, users, disputes, roles, payments, notifications',
    numbered.length === 0 && notif.status === 200 && notifT.status === 200, 'counts match seeded data',
    `${numbered.join(' ; ') || 'all match'}; notif unread=${notif.json?.totalElements}, recent=${notifT.json?.totalElements}; users customerId:isNotEmpty -> ${ob.find((x) => x.includes('customerId:isNotEmpty')) || 'accepted'}`);

  // AND of several filters
  const andF = [`customerId:eq:${S.A}`, 'status:in:UNPAID,PARTIALLY_PAID', 'total:gte:100', `invoiceDate:gte:${ymd(addDays(T, -60))}`];
  const r = await all('invoices', andF, 'id,asc');
  const want = rows.filter((x) => ['UNPAID', 'PARTIALLY_PAID'].includes(x.status) && +x.total >= 100 && Date.parse(x.invoiceDate) >= addDays(T, -60).getTime()).map((x) => x.id);
  const echo = await list('invoices', { filter: andF });
  rec('TF-046', 'Filters', 'Multiple filters AND together; appliedFilters echoes them', eqSet(r.rows.map((x) => x.id), want) && JSON.stringify(echo.json.appliedFilters) === JSON.stringify(andF),
    `ids ${want.length}; appliedFilters = request`, `got ${r.rows.length} ids; appliedFilters ${JSON.stringify(echo.json.appliedFilters)}`);
  return rows;
}

async function filterValidation(SCH) {
  const bad = []; let n = 0;
  const wrongOp = { TEXT: 'gt', ENUM: 'contains', BOOLEAN: 'neq', NUMBER: 'contains', MONEY: 'isEmpty', DATE: 'eq', REFERENCE: 'contains' };
  const okProbe = (c) => ({ TEXT: 'isNotEmpty:', ENUM: `in:${c.enumValues[0]}`, BOOLEAN: 'eq:true', NUMBER: 'gte:0', MONEY: 'gte:0', DATE: 'relative:thisYear', REFERENCE: 'isNotEmpty:' })[c.type];
  for (const e of Object.keys(ENT)) {
    for (const c of SCH[e].columns) {
      const w = await list(e, { filter: [`${c.name}:${wrongOp[c.type]}:1`] });
      n++;
      if (w.status !== 400) bad.push(`${e}.${c.name} ${wrongOp[c.type]} -> ${snip(w)}`);
      const ok = await list(e, { size: 10, filter: [...ENT[e].own(), `${c.name}:${okProbe(c)}`] });
      n++;
      if (ok.status !== 200) bad.push(`${e}.${c.name} ${okProbe(c)} -> ${snip(ok)}`);
    }
    const u = await list(e, { filter: ['nosuch:eq:1'] });
    n++;
    if (u.status !== 400) bad.push(`${e} unknown column -> ${snip(u)}`);
  }
  rec('TF-050', 'Filter validation', `Every column of every list: an operator not allowed for its type -> 400, an allowed operator -> 200; unknown column -> 400 (${n} requests)`,
    bad.length === 0, '400 / 200 as per schema operators (AC-D9)', bad.join(' ; ') || 'all correct');

  const v = [];
  for (const f of ['status', 'status:eq', 'total:like:5', ':eq:1', 'total:between:5', 'total:between:1,2,3', 'total:gt:abc', 'status:eq:FOO', 'invoiceDate:gte:2026-13-45', 'invoiceDate:relative:nextWeek', 'customerId:eq:abc', 'total:in:1,2', 'invoiceDate:gte:yesterday']) {
    const r = await list('invoices', { filter: [f] });
    if (r.status !== 400) v.push(`${f} -> ${snip(r)}`);
  }
  const empty = await list('invoices', { filter: ['status:in:'] });
  if (empty.status !== 400) v.push(`status:in: (no values) -> ${snip(empty)}`);
  rec('TF-051', 'Filter validation', 'Malformed filters (missing parts, unknown operator, wrong arity, bad number/enum/date/preset/id, empty in-list) -> 400 with a message', v.length === 0,
    'all 400', v.join(' ; ') || 'all 400');
}

async function summaries(Arows) {
  const issues = [];
  const sum = (a, f) => round2(a.reduce((s, x) => s + f(x), 0));
  const chkInv = async (filters, label) => {
    const full = await all('invoices', filters, 'id,asc');
    const s = await rt.api('GET', `/api/invoices/summary${qs({ filter: filters })}`, { token: admin });
    const w = {
      count: full.total, totalBilled: sum(full.rows, (x) => +x.total), totalPaid: sum(full.rows, (x) => +x.paidAmount),
      outstanding: sum(full.rows, (x) => (x.status === 'CANCELLED' ? 0 : +x.balance)),
      unpaidCount: full.rows.filter((x) => x.status === 'UNPAID').length, partiallyPaidCount: full.rows.filter((x) => x.status === 'PARTIALLY_PAID').length,
      fullyPaidCount: full.rows.filter((x) => x.status === 'FULLY_PAID').length, cancelledCount: full.rows.filter((x) => x.status === 'CANCELLED').length,
      pocMissingCount: full.rows.filter((x) => !x.salesPoc).length,
    };
    for (const k of Object.keys(w)) if (round2(+s.json[k]) !== w[k]) issues.push(`invoices ${label} ${k}: tile ${s.json[k]} vs list ${w[k]}`);
    return `${label}: ${JSON.stringify(s.json)}`;
  };
  const e1 = await chkInv([`customerId:eq:${S.A}`], 'A');
  const e2 = await chkInv([`customerId:eq:${S.A}`, 'total:gte:100', 'status:neq:FULLY_PAID'], 'A&total>=100&!paid');
  const e3 = await chkInv([`customerId:in:${S.A},${S.B}`, 'invoiceDate:relative:last30Days'], 'A|B last30');

  const pf = [`customerId:eq:${S.A}`];
  const pfull = await all('payments', pf, 'id,asc');
  const ps = await rt.api('GET', `/api/payments/summary${qs({ filter: pf })}`, { token: admin });
  const pw = { count: pfull.total, totalCollected: sum(pfull.rows, (x) => (x.status === 'VOIDED' ? 0 : +x.amount)), creditApplied: sum(pfull.rows, (x) => (x.status === 'VOIDED' ? 0 : +x.creditApplied)),
    activeCount: pfull.rows.filter((x) => x.status === 'ACTIVE').length, voidedCount: pfull.rows.filter((x) => x.status === 'VOIDED').length, pocMissingCount: pfull.rows.filter((x) => !x.collectionPoc).length };
  for (const k of Object.keys(pw)) if (round2(+ps.json[k]) !== pw[k]) issues.push(`payments ${k}: tile ${ps.json[k]} vs list ${pw[k]}`);

  const cf = [`name:contains:${P}-cust-`];
  const cfull = await all('customers', cf, 'id,asc');
  const cs = await rt.api('GET', `/api/customers/summary${qs({ filter: cf })}`, { token: admin });
  const cw = { count: cfull.total, totalOutstanding: sum(cfull.rows, (x) => +x.outstanding), totalCreditBalance: sum(cfull.rows, (x) => +x.creditBalance),
    missingSuccessPocCount: cfull.rows.filter((x) => !(x.successPocs || []).length).length, missingCollectionPocCount: cfull.rows.filter((x) => !(x.collectionPocs || []).length).length };
  for (const k of Object.keys(cw)) if (round2(+cs.json[k]) !== cw[k]) issues.push(`customers ${k}: tile ${cs.json[k]} vs list ${cw[k]}`);

  const prf = [`customerId:eq:${S.A}`];
  const prfull = await all('promises', prf, 'id,asc');
  const prs = await rt.api('GET', `/api/promises/summary${qs({ filter: prf })}`, { token: admin });
  const st = (s) => prfull.rows.filter((x) => x.status === s);
  const prw = { total: prfull.total, openCount: st('OPEN').length, openAmount: sum(st('OPEN'), (x) => +x.amount), keptCount: st('KEPT').length, keptAmount: sum(st('KEPT'), (x) => +x.amount),
    partiallyKeptCount: st('PARTIALLY_KEPT').length, partiallyKeptAmount: sum(st('PARTIALLY_KEPT'), (x) => +x.amount), brokenCount: st('BROKEN').length, brokenAmount: sum(st('BROKEN'), (x) => +x.amount),
    cancelledCount: st('CANCELLED').length, promisedAmount: sum(prfull.rows.filter((x) => x.status !== 'CANCELLED'), (x) => +x.amount), fulfilledAmount: sum(prfull.rows, (x) => +x.fulfilledAmount) };
  for (const k of Object.keys(prw)) if (round2(+prs.json[k]) !== prw[k]) issues.push(`promises ${k}: tile ${prs.json[k]} vs list ${prw[k]}`);

  rec('TF-060', 'Summary tiles', 'invoices/payments/customers/promises /summary honour the same filters: every tile equals the value computed from the full filtered list',
    issues.length === 0, 'tiles == list aggregates (AC-E1/E2)', issues.join(' ; ') || `all equal. ${e1.slice(0, 200)}`);

  const v = [];
  for (const e of ['invoices', 'payments', 'customers', 'promises']) {
    for (const f of ['nosuch:eq:1', 'total:contains:x', 'status']) {
      const r = await rt.api('GET', `/api/${e}/summary${qs({ filter: [f] })}`, { token: admin });
      if (r.status !== 400) v.push(`${e}/summary ${f} -> ${snip(r)}`);
    }
  }
  const convenience = await rt.api('GET', `/api/invoices/summary?customerId=${S.A}`, { token: admin });
  const viaFilter = await rt.api('GET', `/api/invoices/summary${qs({ filter: [`customerId:eq:${S.A}`] })}`, { token: admin });
  if (JSON.stringify(convenience.json) !== JSON.stringify(viaFilter.json)) v.push('customerId= param differs from filter');
  rec('TF-061', 'Summary tiles', 'Summary endpoints validate filters (unknown column / bad operator / malformed -> 400); ?customerId= equals the chip', v.length === 0, '400s; identical tiles', v.join(' ; ') || 'ok');
}

function parseCsv(text) {
  const rows = []; let row = []; let cell = ''; let q = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) {
      if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (ch === '"') q = false; else cell += ch;
    } else if (ch === '"') q = true;
    else if (ch === ',') { row.push(cell); cell = ''; } else if (ch === '\r') { /* skip */ } else if (ch === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; } else cell += ch;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows;
}

async function exports_() {
  const ex = (e, body, tok = admin) => rt.api('POST', `/api/${e}/export`, { token: tok, body: { action: 'EXPORT', ...body } });
  const issues = [];
  const Ainv = await all('invoices', [`customerId:eq:${S.A}`], 'total,asc');
  const pick = [Ainv.rows[2].id, Ainv.rows[5].id, Ainv.rows[9].id];
  const r1 = await ex('invoices', { ids: pick, filters: [`customerId:eq:${S.A}`], sort: 'total,asc' });
  const c1 = parseCsv(r1.text);
  const nums1 = c1.slice(1).map((x) => x[0]);
  const want1 = Ainv.rows.filter((x) => pick.includes(x.id)).map((x) => x.invoiceNumber);
  if (!(r1.status === 200 && /text\/csv/.test(r1.headers['content-type']) && JSON.stringify(nums1) === JSON.stringify(want1))) issues.push(`ids export: ${r1.status} ${r1.headers['content-type']} rows ${nums1} want ${want1}`);
  if (c1[0].join(',') !== 'Invoice #,Customer,Date,Total,Paid,Balance,Status,Sales POC') issues.push(`header ${c1[0]}`);
  const r2 = await ex('invoices', { selectAllMatchingFilter: true, filters: [`customerId:eq:${S.A}`, 'status:eq:UNPAID'], sort: 'total,desc' });
  const unpaid = await all('invoices', [`customerId:eq:${S.A}`, 'status:eq:UNPAID'], 'total,desc');
  const c2 = parseCsv(r2.text).slice(1).map((x) => x[0]);
  if (JSON.stringify(c2) !== JSON.stringify(unpaid.rows.map((x) => x.invoiceNumber))) issues.push(`selectAll export rows ${c2.length} vs list ${unpaid.total} (order must follow sort)`);
  const qInv = S.invoices.find((i) => i.customerId === S.special.quote.id);
  const fInv = S.invoices.find((i) => i.customerId === S.special.formula.id);
  const r3 = await ex('invoices', { ids: [qInv.id, fInv.id], filters: [`customerName:contains:${P}`] });
  const lines3 = r3.text.split('\r\n');
  const qLine = lines3.find((l) => l.includes('Brien'));
  const fLine = lines3.find((l) => l.includes('formula'));
  const wantQ = `"${P} O'Brien, ""Q"" 50%_off: x"`;
  if (!qLine || !qLine.includes(wantQ)) issues.push(`quote escaping: ${qLine}`);
  if (!fLine || !fLine.includes(`'=${P}-formula`)) issues.push(`formula guard: ${fLine}`);
  const r4 = await ex('invoices', { ids: [pick[0], S.invoices.find((i) => i.customerId === S.B).id], filters: [`customerId:eq:${S.A}`] });
  const c4 = parseCsv(r4.text).slice(1);
  rec('TF-070', 'Export (invoices)', 'Invoice export: explicit ids -> exactly those rows (in sort order); selectAll -> exactly the filtered rows; RFC-4180 quoting of comma/quote; leading "=" neutralised; text/csv',
    issues.length === 0, 'CSV rows == selection; "a, ""b""" quoting; =x -> \'=x', issues.join(' ; ') || `ok; quote line: ${qLine}; formula line: ${fLine}`, `r1 CSV:\n${r1.text.slice(0, 400)}`);
  rec('TF-071', 'Export (invoices)', 'Export ids outside the current filter are excluded (ids re-resolved through filter+scope)', r4.status === 200 && c4.length === 1,
    'only the id that matches the filter is exported (design §8), 1 row', `${r4.status} rows=${c4.length}`);

  const perEntity = [];
  const cases = [
    ['customers', [`name:contains:${P}`], 'customers', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Name,Phone,Email,Credit balance,Outstanding,Customer Success POCs,Collection POCs'],
    ['payments', [`customerId:eq:${S.A}`], 'payments', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Payment #,Customer,Paid at,Amount,Credit applied,Method,Status,Collection POC'],
    ['promises', [`customerId:eq:${S.A}`], 'promises', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Customer,Promised amount,Promised by,Status,Fulfilled,Remaining,Invoices,Collection POC,Notes'],
    ['products', [`name:contains:${P}`], 'products', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Name,Description,Price,Active'],
    ['users', [`username:contains:${P}`], 'users', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Username,Full name,Email,Role,Active'],
    ['roles', [`name:contains:${P}`], 'roles', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Name,Description,Privileges'],
    ['disputes', [`customerId:eq:${S.A}`], 'disputes', (rows) => rows.map((x) => String(x.id)), (c) => c[0], 'Id,Customer,Target,Target id,Status,Opened,Resolved,Reason'],
  ];
  for (const [e, f, , idsOf, key, header] of cases) {
    const full = await all(e, f, undefined);
    const r = await ex(e, { selectAllMatchingFilter: true, filters: f });
    const c = parseCsv(r.text);
    const got = c.slice(1).map(key);
    if (r.status !== 200 || !eqSet(got.map(Number), idsOf(full.rows).map(Number)) || c[0].join(',') !== header) perEntity.push(`${e}: ${r.status} rows ${got.length}/${full.total} header ${c[0].join(',')}`);
    const two = full.rows.slice(0, 2).map((x) => x.id);
    const r2x = await ex(e, { ids: two, filters: f });
    const g2 = parseCsv(r2x.text).slice(1).map(key).map(Number);
    if (!eqSet(g2, two)) perEntity.push(`${e} ids export: ${g2} vs ${two}`);
  }
  // escaping spot checks
  const prodCsv = (await ex('products', { selectAllMatchingFilter: true, filters: [`name:contains:${P}-prod-01`] })).text;
  if (!prodCsv.includes('"Widget, ""deluxe"" edition"')) perEntity.push(`product desc escaping: ${prodCsv.split('\r\n')[1]}`);
  const roleCsv = (await ex('roles', { selectAllMatchingFilter: true, filters: [`name:eq:${P}-role-1`] })).text;
  if (!roleCsv.includes('"desc, with ""quotes"""')) perEntity.push(`role desc escaping: ${roleCsv.split('\r\n')[1]}`);
  const payCsv = (await ex('payments', { selectAllMatchingFilter: true, filters: [`customerId:eq:${S.A}`, 'method:eq:Wire: ref,1'] })).text;
  if (!payCsv.includes('"Wire: ref,1"')) perEntity.push(`payment method escaping: ${payCsv}`);
  const dCsv = (await ex('disputes', { selectAllMatchingFilter: true, filters: [`customerId:eq:${S.A}`] })).text;
  if (!dCsv.includes('"Wrong total: see, ""PO 12"""')) perEntity.push(`dispute reason escaping: ${dCsv.slice(0, 300)}`);
  rec('TF-072', 'Export (other lists)', 'customers/payments/promises/products/users/roles/disputes export: selectAll == filtered list, explicit ids == those ids, headers, quoting', perEntity.length === 0,
    'rows equal the selection; values with , and " are quoted', perEntity.join(' ; ') || 'all correct');

  const v = [];
  for (const e of ['invoices', 'payments', 'customers', 'promises', 'products', 'users', 'roles', 'disputes']) {
    const a = await ex(e, { filters: [] });
    const b = await ex(e, { ids: [], filters: [] });
    const c = await ex(e, { selectAllMatchingFilter: true, filters: ['nosuch:eq:1'] });
    if (a.status !== 400 || b.status !== 400 || c.status !== 400) v.push(`${e}: none=${a.status} empty=${b.status} badFilter=${c.status}`);
  }
  rec('TF-073', 'Export validation', 'Export with no ids and no selectAll, empty ids, or an invalid filter -> 400 on all 8 exportable lists', v.length === 0, '400', v.join(' ; ') || 'all 400');
}

async function scoping() {
  const iss = [];
  const r = await all('invoices', [], 'id,asc', cust);
  if (!(r.total === 24 && r.rows.every((x) => x.customerId === S.A))) iss.push(`customer sees ${r.total} rows`);
  const w = await list('invoices', { filter: [`customerId:eq:${S.B}`] }, cust);
  if (!(w.status === 200 && w.json.totalElements === 0)) iss.push(`customerId:eq:B -> ${snip(w)}`);
  const w2 = await list('invoices', { filter: [`customerId:in:${S.A},${S.B}`] }, cust);
  if (!(w2.status === 200 && w2.json.totalElements === 24)) iss.push(`customerId:in:A,B -> ${w2.json?.totalElements}`);
  const sm = await rt.api('GET', '/api/invoices/summary', { token: cust });
  if (sm.json?.count !== 24) iss.push(`summary count ${sm.json?.count}`);
  const bulk = await rt.api('POST', '/api/invoices/bulk', { token: cust, body: { action: 'CANCEL', selectAllMatchingFilter: true, filters: [] } });
  if (bulk.status !== 403) iss.push(`customer bulk -> ${snip(bulk)}`);
  rec('TF-080', 'Scoping (customer)', 'Customer-scoped caller: list/summary confined to own rows; customerId filters cannot widen; bulk forbidden', iss.length === 0,
    '24 own rows; B filter -> 0; in A,B -> 24; summary 24; bulk 403 (AC-D10)', iss.join(' ; ') || 'all confined');

  const sch = await rt.api('GET', '/api/table-schemas/invoices', { token: cust });
  const cols = sch.json.columns.map((c) => c.name);
  const salesName = S.sales.fullName;
  const f1 = await list('invoices', { filter: [`salesPocName:contains:${salesName.slice(0, 6)}`] }, cust);
  const f0 = await list('invoices', { filter: ['salesPocName:contains:zzzz-nobody'] }, cust);
  const s1 = await list('invoices', { sort: 'salesPocName,asc' }, cust);
  const f2 = await list('promises', { filter: [`collectionPocUserId:eq:${S.coll.id}`] }, cust);
  const f3 = await list('promises', { filter: ['collectionPocUserId:eq:999999'] }, cust);
  const leak = f1.status === 200 || s1.status === 200 || f2.status === 200;
  rec('TF-081', 'Scoping (customer) / POC columns', 'Customer-scoped caller cannot filter or sort on POC-restricted columns hidden from its schema',
    !cols.includes('salesPocName') && !leak,
    'schema omits salesPocUserId/salesPocName; filter/sort on them -> 400 (AC-A8, design §6 "stripped from the schema")',
    `schema has POC cols: ${cols.includes('salesPocName')}; salesPocName:contains:<rep> -> ${f1.status} total ${f1.json?.totalElements} vs contains:zzzz -> ${f0.json?.totalElements}; sort salesPocName -> ${s1.status}; promises collectionPocUserId:eq:<rep> -> ${f2.status} total ${f2.json?.totalElements} vs 999999 -> ${f3.json?.totalElements}`);

  // SALES_POC locked scope
  const st = await rt.login(S.sales.username, S.sales.password);
  const sl = await list('invoices', { size: 10 }, st);
  const adm = await list('invoices', { filter: [`salesPocUserId:eq:${S.sales.id}`] });
  const other = await list('invoices', { filter: ['salesPocUserId:isEmpty:'] }, st);
  rec('TF-082', 'Scoping (POC locked book)', 'SALES_POC without SCOPE_OVERRIDE: lockedFilters returned, rows limited to own book, filters cannot widen it',
    sl.status === 200 && JSON.stringify(sl.json.lockedFilters) === JSON.stringify([`salesPocUserId:eq:${S.sales.id}`]) && sl.json.totalElements === adm.json.totalElements && other.json.totalElements === 0,
    `lockedFilters [salesPocUserId:eq:${S.sales.id}], total == admin's count for that rep, isEmpty -> 0`,
    `${sl.status} locked=${JSON.stringify(sl.json?.lockedFilters)} total=${sl.json?.totalElements} admin=${adm.json?.totalElements} isEmpty=${other.json?.totalElements}`);

  const na = await rt.api('GET', '/api/invoices');
  const cash = await rt.cashierToken();
  const cu = await rt.api('GET', '/api/users?size=10', { token: cash });
  const ce = await rt.api('POST', '/api/users/export', { token: cash, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`username:contains:${P}u`] } });
  rec('TF-083', 'Permissions', 'List needs auth (401); export of users requires USER_VIEW, not just EXPORT_DATA',
    na.status === 401 && cu.status === 403 && ce.status === 403,
    'no token -> 401; cashier GET /api/users -> 403; cashier POST /api/users/export -> 403',
    `no token -> ${na.status}; cashier list users -> ${cu.status}; cashier export users -> ${ce.status} (${ce.text.split('\r\n').length - 2} rows: ${ce.text.split('\r\n')[1] || ''})`);
}

async function bulk() {
  const bk = (e, body, tok = admin) => rt.api('POST', `/api/${e}/bulk`, { token: tok, body });
  const pr = S.products;
  // products: ids incl already-inactive and a nonexistent id
  const r1 = await bk('products', { action: 'DEACTIVATE', ids: [pr[0].id, pr[3].id, 999999999], filters: [`name:contains:${P}`] });
  const j = r1.json || {};
  const accounted = (j.succeeded || []).length + (j.failed || []).length + (j.skipped || []).length;
  rec('TF-090', 'Bulk (products)', 'Bulk DEACTIVATE with ids: per-record result (active -> succeeded, already inactive -> skipped with reason)',
    r1.status === 200 && JSON.stringify(j.succeeded) === JSON.stringify([pr[0].id]) && (j.skipped || []).some((s) => s.id === pr[3].id && /Already inactive/.test(s.reason)) && j.truncated === false && j.limit === 5000,
    `succeeded [${pr[0].id}], skipped [${pr[3].id} Already inactive], truncated false, limit 5000`, snip(r1));
  rec('TF-091', 'Bulk (all lists)', 'Every requested id is accounted for — an unknown / out-of-filter id is reported, not silently dropped',
    accounted === 3 && j.requested === 3,
    'requested 3; id 999999999 appears in failed or skipped (AC-D5, design §8 "every requested id lands in exactly one of succeeded/failed/skipped")',
    `requested=${j.requested}; succeeded ${JSON.stringify(j.succeeded)} failed ${JSON.stringify(j.failed)} skipped ${JSON.stringify(j.skipped)}`);
  const lst = await all('products', [`name:contains:${P}`, 'active:eq:false'], 'id,asc');
  const r2 = await bk('products', { action: 'ACTIVATE', selectAllMatchingFilter: true, filters: [`name:contains:${P}`, 'active:eq:false'] });
  const after = await list('products', { filter: [`name:contains:${P}`, 'active:eq:false'] });
  const audit = await rt.api('GET', `/api/audit?entityType=PRODUCT&entityId=${pr[0].id}&size=20`, { token: admin });
  const auditRows = audit.json?.content || audit.json || [];
  const acts = (Array.isArray(auditRows) ? auditRows : []).map((a) => a.action);
  rec('TF-092', 'Bulk (select all matching)', 'selectAllMatchingFilter applies to the whole filtered set; afterwards no row matches; one audit row per record',
    r2.status === 200 && eqSet(r2.json.succeeded, lst.rows.map((x) => x.id)) && r2.json.requested === lst.total && after.json.totalElements === 0 && acts.includes('PRODUCT_DEACTIVATED') && acts.includes('PRODUCT_ACTIVATED'),
    `succeeded == the ${lst.total} inactive ids; active:eq:false -> 0; audit has PRODUCT_DEACTIVATED + PRODUCT_ACTIVATED (AC-D7, AC-D8)`,
    `${snip(r2)}; after=${after.json?.totalElements}; audit(${audit.status}) actions ${acts.join(',')}`);

  // validation
  const v = [];
  for (const [e, body] of [['products', { action: 'DEACTIVATE', filters: [] }], ['products', { action: 'DEACTIVATE', ids: [] }], ['products', { action: 'NUKE', ids: [pr[0].id] }],
    ['invoices', { action: 'NUKE', ids: [1] }], ['products', { ids: [pr[0].id] }], ['users', { action: 'ACTIVATE', selectAllMatchingFilter: true, filters: ['nosuch:eq:1'] }],
    ['notifications', { action: 'MARK_READ' }], ['promises', { action: 'CANCEL', ids: [] }]]) {
    const r = await bk(e, body);
    if (r.status !== 400) v.push(`${e} ${JSON.stringify(body)} -> ${snip(r)}`);
  }
  const bad = await rt.api('POST', '/api/products/bulk', { token: admin, body: undefined, headers: { 'Content-Type': 'application/json' } });
  const bad2 = await fetch(`${rt.API}/api/products/bulk`, { method: 'POST', headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' }, body: '{not json' });
  rec('TF-093', 'Bulk validation', 'Bulk with no ids and no selectAll, empty ids, unknown/missing action, invalid filter -> 400; malformed JSON body -> 400',
    v.length === 0 && bad.status === 400 && bad2.status === 400, 'all 400', `${v.join(' ; ') || 'validation ok'}; no body -> ${bad.status}; malformed JSON -> ${bad2.status}`);

  // invoices CANCEL mix
  const Ainv = S.invoices.filter((i) => i.customerId === S.A);
  const unpaid = Ainv[5].id; const paid = Ainv[0].id; const cancelled = Ainv[7].id;
  const r3 = await bk('invoices', { action: 'CANCEL', ids: [unpaid, paid, cancelled], filters: [`customerId:eq:${S.A}`] });
  const j3 = r3.json || {};
  const st = await rt.api('GET', `/api/invoices/${unpaid}`, { token: admin });
  rec('TF-094', 'Bulk (invoices)', 'Bulk CANCEL on [unpaid, fully-paid, already-cancelled]: unpaid cancelled; others reported per record',
    r3.status === 200 && JSON.stringify(j3.succeeded) === JSON.stringify([unpaid]) && st.json.status === 'CANCELLED' && ((j3.failed || []).length + (j3.skipped || []).length) === 2,
    'succeeded [unpaid] and status CANCELLED; the other two reported with reasons', `${snip(r3)}; unpaid now ${st.json?.status}`);
  rec('TF-095', 'Bulk (invoices)', 'Ineligible invoices (paid / already cancelled) are excluded and reported as skipped, not attempted and counted as failed',
    (j3.skipped || []).length === 2 && (j3.failed || []).length === 0,
    'skipped 2, failed 0 (AC-D6 "ineligible rows are excluded and reported, not attempted"; products/users already use skipped)',
    `failed=${JSON.stringify(j3.failed)} skipped=${JSON.stringify(j3.skipped)}`);

  // reassign sales POC for B via select-all
  const r4 = await bk('invoices', { action: 'REASSIGN_SALES_POC', selectAllMatchingFilter: true, filters: [`customerId:eq:${S.B}`], params: { userId: S.myAdmin.id } });
  const chk4 = await list('invoices', { filter: [`customerId:eq:${S.B}`, `salesPocUserId:eq:${S.myAdmin.id}`] });
  rec('TF-096', 'Bulk (invoices)', 'REASSIGN_SALES_POC with selectAllMatchingFilter reassigns exactly the filtered rows', r4.status === 200 && r4.json.succeeded.length === 3 && chk4.json.totalElements === 3,
    '3 succeeded; list shows 3 with new POC', `${snip(r4)}; now ${chk4.json?.totalElements}`);

  // users bulk with own admin (self skipped)
  const ma = await rt.login(S.myAdmin.username, S.myAdmin.password);
  const u0 = S.users[0].id; const u9 = S.users[9].id;
  const r5 = await bk('users', { action: 'DEACTIVATE', ids: [S.myAdmin.id, u0, u9], filters: [`username:contains:${P}`] }, ma);
  const j5 = r5.json || {};
  const back = await bk('users', { action: 'ACTIVATE', ids: [u0], filters: [] }, ma);
  rec('TF-097', 'Bulk (users)', 'Users DEACTIVATE: own account skipped, active user succeeded, inactive skipped',
    r5.status === 200 && JSON.stringify(j5.succeeded) === JSON.stringify([u0]) && (j5.skipped || []).length === 2 && back.json?.succeeded?.[0] === u0,
    `succeeded [${u0}], skipped [self, ${u9}]`, `${snip(r5)}; reactivate -> ${snip(back)}`);

  // notifications
  const before = await list('notifications', { size: 10, filter: ['read:eq:false'] }, coll);
  const r6 = await bk('notifications', { action: 'MARK_READ', selectAllMatchingFilter: true, filters: ['read:eq:false'] }, coll);
  const uc = await rt.api('GET', '/api/notifications/unread-count', { token: coll });
  const allN = await all('notifications', [], 'id,asc', coll);
  const adminN = await list('notifications', { size: 10 });
  const foreign = adminN.json.content[0]?.id;
  const r7 = await bk('notifications', { action: 'MARK_UNREAD', ids: [allN.rows[0].id, allN.rows[1].id, foreign], filters: [] }, coll);
  const uc2 = await rt.api('GET', '/api/notifications/unread-count', { token: coll });
  rec('TF-098', 'Bulk (notifications)', 'Notifications MARK_READ select-all over read:eq:false marks every unread one; MARK_UNREAD by ids; another user\'s notification is never touched',
    r6.status === 200 && r6.json.succeeded.length === before.json.totalElements && uc.json.count === 0 && r7.json.succeeded.length === 2 && uc2.json.count === 2,
    `MARK_READ succeeded ${before.json.totalElements}; unread-count 0; MARK_UNREAD 2 succeeded; foreign id not changed`,
    `MARK_READ ${r6.json?.succeeded?.length}/${before.json?.totalElements}, unread ${uc.json?.count}; MARK_UNREAD requested=${r7.json?.requested} succ=${r7.json?.succeeded?.length} skipped=${JSON.stringify(r7.json?.skipped)} failed=${JSON.stringify(r7.json?.failed)}; unread ${uc2.json?.count}`);

  // promises CANCEL + customers ADD_POC
  const pid = S.promises[3].id;
  const r8 = await bk('promises', { action: 'CANCEL', ids: [pid], filters: [`customerId:eq:${S.A}`], params: { reason: 'rt bulk' } });
  const p8 = await rt.api('GET', `/api/promises/${pid}`, { token: admin });
  const beforeSeat = await list('customers', { filter: [`successPocUserId:eq:${S.success.id}`] });
  const r9 = await bk('customers', { action: 'ADD_POC', selectAllMatchingFilter: true, filters: [`name:contains:${P}-cust-2`], params: { pocType: 'SUCCESS', userId: S.success.id } });
  const afterSeat = await list('customers', { filter: [`successPocUserId:eq:${S.success.id}`] });
  rec('TF-099', 'Bulk (promises, customers)', 'Promise bulk CANCEL by id; customer bulk ADD_POC over select-all filter (4 customers -cust-20..23)',
    r8.json?.succeeded?.[0] === pid && p8.json.status === 'CANCELLED' && r9.status === 200 && r9.json.succeeded.length === 4 && afterSeat.json.totalElements === beforeSeat.json.totalElements + 4,
    'promise CANCELLED; ADD_POC succeeded 4; seat filter count +4', `${snip(r8)}; promise ${p8.json?.status}; ${snip(r9)}; seats ${beforeSeat.json?.totalElements} -> ${afterSeat.json?.totalElements}`);
}

(async () => {
  admin = await rt.adminToken();
  coll = await rt.login(S.coll.username, S.coll.password);
  cust = await rt.login(S.customers[0].username, S.customers[0].password);
  const only = process.argv.slice(2);
  const run = (n) => only.length === 0 || only.includes(n);
  let SCH;
  SCH = await schemas();
  if (run('sizes')) await sizes();
  if (run('paging')) await paging();
  if (run('sorting')) await sorting(SCH);
  let rows;
  if (run('filters')) rows = await filters();
  if (run('fv')) await filterValidation(SCH);
  if (run('summaries')) await summaries(rows);
  if (run('exports')) await exports_();
  if (run('scoping')) await scoping();
  if (run('bulk')) await bulk();
  const prev = fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, 'utf8')) : {};
  for (const r of results) prev[r.id] = r;
  fs.writeFileSync(out, JSON.stringify(prev, null, 2));
  console.log(`\n${results.filter((r) => r.status === 'PASS').length} pass / ${results.filter((r) => r.status === 'FAIL').length} fail`);
})().catch((e) => { console.error('RUN FAILED', e); process.exit(1); });
