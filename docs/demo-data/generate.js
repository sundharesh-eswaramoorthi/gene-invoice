#!/usr/bin/env node
// Generates a year of themed demo data by driving the real API, in date order.
//
//   node generate.js --api http://localhost:8092 --out run [--seed 20260916] [--plan-only]
//
// Every business rule (allocation, credit, invoice status, POC rules, disputes, history,
// notifications) is applied by the application itself. The API stamps everything with "now", so
// each action is recorded with its simulated time and the real-time window it ran in
// (run/windows.csv); postprocess.sql then moves every timestamp written in that window to the
// simulated time. Promised dates are sent as 2099-MM-DD placeholders so no promise can be judged
// overdue while the history is still being written; postprocess.sql puts the real year back.
// See README.md for the whole procedure.

const fs = require('fs');
const path = require('path');
const C = require('./catalog.js');

// ---- arguments --------------------------------------------------------------------------------

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
  return acc;
}, []));
const API = args.api || 'http://localhost:8092';
const OUT = path.resolve(args.out || 'run');
const SEED = Number(args.seed || 20260916);
const PLAN_ONLY = !!args['plan-only'];
// How loosely payment outcomes follow invoice age: higher mixes in quick payers and old debts.
const FATE_NOISE = Number(args['fate-noise'] || 1.3);
const PASSWORD = 'Demo1234!';
const BOOTSTRAP_PASSWORDS = { admin: 'admin123', cashier: 'cashier123' };

// ---- volumes (the data this replaces had these counts) ----------------------------------------

const TARGET = {
  invoices: 1013,
  payments: 769, // including the one voided cheque and its replacement
  fates: { FULL: 462, PARTIAL: 233 }, // the rest start UNPAID; cancellations come out of those
  promises: { KEPT_SCOPED: 35, KEPT_GENERAL: 35, PARTIAL: 39, BROKEN_UNPAID: 14, BROKEN_LATE: 6, OPEN: 14, CANCELLED: 13 },
  disputes: { pending: 27, denied: 28, cancelUnpaid: 10, cancelPaid: 5, replace: 12, void: 1 },
  directCancels: 38,
  pairs: 20,
  triples: 6,
  successSeats: 101,
  secondaryCollection: 19,
  endCredit: 15,
  midCredit: 10,
  earlyCustomers: 70,
};

// ---- time -------------------------------------------------------------------------------------

const MIN = 60000;
const HOUR = 3600000;
const DAY = 86400000;
const utcDay = (s) => Date.parse(`${s}T00:00:00Z`);
const dayOf = (t) => Math.floor(t / DAY) * DAY;
const ymd = (t) => new Date(t).toISOString().slice(0, 10);
const iso = (t) => new Date(t).toISOString();

const NOW = Date.now();
const TODAY = dayOf(NOW);
// Business hours are 09:30-18:30 IST, i.e. 04:00-13:00 UTC. The simulated year ends at today's
// close of business, or yesterday's if the day has not closed yet.
const END = NOW > TODAY + 14 * HOUR ? TODAY + 13 * HOUR : TODAY - DAY + 13 * HOUR;
const END_DAY = dayOf(END);
const SETUP = utcDay('2025-09-01');
const START = utcDay('2025-09-17');

// ---- randomness -------------------------------------------------------------------------------

function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const R = mulberry32(SEED);
const rint = (lo, hi) => lo + Math.floor(R() * (hi - lo + 1));
const pick = (arr) => arr[Math.floor(R() * arr.length)];
const chance = (p) => R() < p;
const normal = () => Math.sqrt(-2 * Math.log(1 - R())) * Math.cos(2 * Math.PI * R());
function shuffle(arr) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(R() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
/** A random moment in business hours on the given day. */
const businessTime = (day) => dayOf(day) + 4 * HOUR + rint(0, 9 * 60 - 1) * MIN;
/** A business-hours moment in (a, b], or the midpoint when the range holds no business hours. */
function businessBetween(a, b) {
  if (b <= a) return b;
  for (let tries = 0; tries < 40; tries++) {
    const t = businessTime(a + Math.floor(R() * (b - a + DAY)));
    if (t > a && t <= b) return t;
  }
  return a + Math.floor((b - a) / 2);
}
/** A business-hours moment `days` after t, never before `after` and never past END. */
function laterBy(t, days, after = t) {
  let x = businessTime(t + days * DAY);
  if (x <= after) x = businessBetween(after, Math.min(END, after + 3 * DAY));
  return Math.min(x, END);
}
const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '.').replace(/^\.|\.$/g, '');

// ---- plan: people and products ----------------------------------------------------------------

const events = [];
const ev = (t, kind, data = {}) => events.push({ t: Math.round(t), order: events.length, kind, ...data });

const staff = C.STAFF.map((s) => ({ ...s, email: `${s.username}@geneinvoice.local` }));
const byRole = (role) => staff.filter((s) => s.role === role).map((s) => s.username);
const SALES = byRole('SALES_POC');
const COLLECTION = byRole('COLLECTION_POC');

ev(SETUP + 4 * HOUR, 'renameStaff', { username: 'admin', fullName: 'Rajinikanth' });
ev(SETUP + 4 * HOUR + 5 * MIN, 'renameStaff', { username: 'cashier', fullName: 'Amitabh Bachchan' });
staff.filter((s) => !BOOTSTRAP_PASSWORDS[s.username]).forEach((s, i) => {
  ev(businessTime(SETUP + Math.floor(i / 4) * DAY), 'createStaff', { staff: s });
});

const products = C.PRODUCTS.map((p, k) => ({ ...p, k, retireAt: p.retireOn ? utcDay(p.retireOn) + 5 * HOUR : null }));
products.forEach((p) => ev(businessTime(SETUP + DAY), 'createProduct', { k: p.k }));
products.filter((p) => p.retireAt).forEach((p) => ev(p.retireAt, 'retireProduct', { k: p.k }));

const STREETS = ['Film Nagar', 'Studio Road', 'Cinema Street', 'Station Road', 'Temple Street', 'Market Road', 'Lake View Road', 'Palace Road'];
const customers = C.CUSTOMERS.map((c, i) => ({
  ...c, ci: i,
  username: slug(c.name),
  email: `${slug(c.name)}@example.com`,
  phone: `+91 55500 ${String(i + 1).padStart(5, '0')}`,
  address: `${rint(1, 250)}, ${pick(STREETS)}, ${c.city}`,
  weight: Math.exp(normal() * 0.6),
  invoices: [],
}));

// 70 customers are on the books from the start; the rest join through to June.
const joinOrder = shuffle(customers.map((c) => c.ci));
joinOrder.forEach((ci, n) => {
  const c = customers[ci];
  c.onboard = n < TARGET.earlyCustomers
    ? businessTime(SETUP + (3 + Math.floor((n / TARGET.earlyCustomers) * 13)) * DAY)
    : businessTime(utcDay('2025-10-01') + Math.floor(R() * (utcDay('2026-06-30') - utcDay('2025-10-01'))));
});

// POCs: collection by region where there is a natural fit, otherwise whoever has the fewest.
const load = Object.fromEntries(staff.map((s) => [s.username, 0]));
const leastLoaded = (names) => names.reduce((a, b) => (load[b] < load[a] ? b : a));
const COLLECTION_BY_INDUSTRY = { Tamil: 'vijay', Telugu: 'prabhas', Malayalam: 'mammootty', Kannada: 'mammootty', Bengali: 'prosenjit.chatterjee', Marathi: 'prosenjit.chatterjee' };
const SALES_BY_INDUSTRY = { Tamil: 'allu.arjun', Telugu: 'allu.arjun', Kannada: 'yash', Malayalam: 'yash', Punjabi: 'diljit.dosanjh' };
const SOUTH = new Set(['Tamil', 'Telugu', 'Malayalam', 'Kannada']);
for (const c of customers) {
  c.collection = COLLECTION_BY_INDUSTRY[c.industry] || null;
  c.sales = SALES_BY_INDUSTRY[c.industry] || null;
  if (c.collection) load[c.collection]++;
  if (c.sales) load[c.sales]++;
}
for (const c of customers) {
  if (!c.collection) { c.collection = leastLoaded(COLLECTION); load[c.collection]++; }
  if (!c.sales) { c.sales = leastLoaded(['shahrukh.khan', 'diljit.dosanjh']); load[c.sales]++; }
  c.success = SOUTH.has(c.industry) ? 'nayanthara' : 'deepika.padukone';
}
const lastJoiners = new Set(joinOrder.slice(-(customers.length - TARGET.successSeats)));
for (const c of customers) {
  ev(c.onboard, 'createCustomer', { ci: c.ci });
  ev(c.onboard + 20 * MIN, 'addPoc', { ci: c.ci, type: 'COLLECTION', username: c.collection, primary: true });
  if (!lastJoiners.has(c.ci)) ev(c.onboard + 40 * MIN, 'addPoc', { ci: c.ci, type: 'SUCCESS', username: c.success, primary: true });
}
for (const c of shuffle(customers).slice(0, TARGET.secondaryCollection)) {
  const other = pick(COLLECTION.filter((u) => u !== c.collection));
  const from = c.onboard + 30 * DAY;
  if (from < END - 20 * DAY) ev(businessTime(from + R() * (END - 20 * DAY - from)), 'addPoc', { ci: c.ci, type: 'COLLECTION', username: other, primary: false });
}

// ---- plan: invoices ---------------------------------------------------------------------------

const invoices = [];
const KIND_WEIGHT = [['car', 0.45], ['bike', 0.35], ['truck', 0.2]];
function pickKind() {
  let x = R();
  for (const [k, w] of KIND_WEIGHT) { if ((x -= w) < 0) return k; }
  return 'car';
}
function makeInvoice(c, t) {
  const n = rint(1, 4);
  const lines = [];
  const taken = new Set();
  for (let tries = 0; lines.length < n && tries < 40; tries++) {
    const kind = pickKind();
    const pool = products.filter((p) => p.kind === kind && !taken.has(p.k) && (!p.retireAt || t < p.retireAt - DAY));
    if (!pool.length) continue;
    const p = pick(pool);
    if (p.retireAt && chance(0.5)) continue; // the old Ambassador sells less often
    taken.add(p.k);
    const qty = 1 + Math.floor(Math.pow(R(), 3) * p.maxQty);
    const unitPrice = chance(0.25) ? Math.round((p.price * (1 - rint(1, 4) / 100)) / 1000) * 1000 : p.price;
    lines.push({ k: p.k, qty, unitPrice });
  }
  const inv = {
    ii: invoices.length, ci: c.ci, t,
    sales: chance(0.85) ? c.sales : pick(SALES),
    notes: chance(0.15) ? pick(C.INVOICE_NOTES) : null,
    lines,
    expected: lines.reduce((s, l) => s + l.qty * l.unitPrice, 0),
    payments: [],
  };
  invoices.push(inv);
  c.invoices.push(inv);
  return inv;
}

const firstDay = (c) => Math.max(dayOf(c.onboard) + DAY, START);
for (const c of customers) {
  for (let k = 0; k < 3; k++) {
    const d0 = firstDay(c);
    makeInvoice(c, businessTime(d0 + Math.floor(R() * ((END_DAY - d0) / DAY + 1)) * DAY));
  }
}
const spanDays = (END_DAY - START) / DAY + 1;
while (invoices.length < TARGET.invoices) {
  const d = Math.floor(R() * spanDays);
  if (R() > (1 + 0.6 * (d / spanDays)) / 1.6) continue; // business grows through the year
  const day = START + d * DAY;
  const eligible = customers.filter((c) => firstDay(c) <= day && c.invoices.length < 22);
  const total = eligible.reduce((s, c) => s + c.weight, 0);
  let x = R() * total;
  const c = eligible.find((e) => (x -= e.weight) < 0) || eligible[eligible.length - 1];
  makeInvoice(c, businessTime(day));
}
for (const c of customers) c.invoices.sort((a, b) => a.t - b.t);
for (const inv of invoices) {
  if (inv.t > END) inv.t = END - rint(30, 240) * MIN;
  ev(inv.t, 'createInvoice', { ii: inv.ii });
}

// Older invoices are more likely to be settled; noise keeps some old debts and some quick payers.
invoices.forEach((inv) => { inv.score = (END - inv.t) * Math.exp(normal() * FATE_NOISE); });
const ranked = invoices.slice().sort((a, b) => b.score - a.score);
ranked.forEach((inv, n) => { inv.fate = n < TARGET.fates.FULL ? 'FULL' : n < TARGET.fates.FULL + TARGET.fates.PARTIAL ? 'PARTIAL' : 'UNPAID'; });

// ---- plan: payments ---------------------------------------------------------------------------

const payments = [];
const lagDays = () => Math.min(150, Math.max(1, Math.round(Math.exp(Math.log(25) + normal() * 0.7))));
function payTimeFor(inv, days = lagDays()) {
  let t = businessTime(inv.t + days * DAY);
  if (t <= inv.t + 10 * MIN || t > END) t = businessBetween(inv.t + 10 * MIN, END);
  return Math.min(t, END);
}
function addPayment(p) {
  const pay = { pi: payments.length, extra: 0, methodRoll: R(), notes: chance(0.15) ? pick(C.PAYMENT_NOTES.filter((n) => !/promise|Advance/.test(n))) : null, ...p };
  payments.push(pay);
  for (const ii of pay.invs) invoices[ii].payments.push(pay.pi);
  return pay;
}

const full = invoices.filter((i) => i.fate === 'FULL');
full.forEach((inv) => { inv.plannedPay = payTimeFor(inv); });

// Some customers settle two or three invoices with one payment.
const grouped = new Set();
let pairs = 0;
let triples = 0;
for (const c of shuffle(customers)) {
  const mine = full.filter((i) => i.ci === c.ci && !grouped.has(i.ii)).sort((a, b) => a.plannedPay - b.plannedPay);
  for (let s = 0; s < mine.length; s++) {
    const want = triples < TARGET.triples && chance(0.3) ? 3 : 2;
    if (want === 2 && pairs >= TARGET.pairs) continue;
    const group = mine.slice(s, s + want);
    if (group.length < want || group.some((g) => grouped.has(g.ii))) continue;
    if (group[want - 1].plannedPay - group[0].plannedPay > 12 * DAY) continue;
    group.forEach((g) => grouped.add(g.ii));
    addPayment({ ci: c.ci, t: group[want - 1].plannedPay, mode: 'full', invs: group.map((g) => g.ii), combined: true });
    if (want === 3) triples++; else pairs++;
    s += want - 1;
    if (pairs >= TARGET.pairs && triples >= TARGET.triples) break;
  }
  if (pairs >= TARGET.pairs && triples >= TARGET.triples) break;
}

// Instalments make up the rest of the payment count: one voided cheque adds a replacement later.
const singles = full.filter((i) => !grouped.has(i.ii));
const needInstalments = (TARGET.payments - 1) - (TARGET.fates.PARTIAL + singles.length + pairs + triples);
const instalmentPool = shuffle(singles.filter((i) => (END - i.t) / DAY >= 25));
const instalments = new Set(instalmentPool.slice(0, Math.max(0, needInstalments)).map((i) => i.ii));
for (const inv of singles) {
  if (instalments.has(inv.ii)) {
    const age = (END - inv.t) / DAY;
    const t1 = payTimeFor(inv, rint(3, Math.max(4, Math.min(40, Math.floor(age) - 15))));
    const first = addPayment({ ci: inv.ci, t: t1, mode: 'first', frac: 0.4 + R() * 0.2, invs: [inv.ii] });
    let t2 = businessTime(t1 + rint(10, 45) * DAY);
    if (t2 > END || t2 <= t1) t2 = businessBetween(t1 + 30 * MIN, END);
    addPayment({ ci: inv.ci, t: Math.min(t2, END), mode: 'full', invs: [inv.ii], after: first.pi, instalment: true });
  } else {
    addPayment({ ci: inv.ci, t: inv.plannedPay, mode: 'full', invs: [inv.ii], single: true });
  }
}
for (const inv of invoices.filter((i) => i.fate === 'PARTIAL')) {
  addPayment({ ci: inv.ci, t: payTimeFor(inv, rint(3, 60)), mode: 'part', frac: 0.25 + R() * 0.5, invs: [inv.ii] });
}

// Credit: fifteen customers finish the year in credit; ten more overpay and use it on a later order.
const ADVANCES = [25000, 50000, 75000, 100000, 150000, 200000, 250000, 500000];
const lastInvoiceT = (c) => Math.max(...c.invoices.map((i) => i.t));
const paymentsOf = (ci) => payments.filter((p) => p.ci === ci).sort((a, b) => a.t - b.t);
const creditCustomers = new Set();
for (const c of shuffle(customers)) {
  if (creditCustomers.size >= TARGET.endCredit) break;
  const last = paymentsOf(c.ci).filter((p) => p.mode === 'full').pop();
  if (!last || last.t <= lastInvoiceT(c)) continue;
  last.extra = pick(ADVANCES);
  last.notes = 'Advance towards the next order';
  creditCustomers.add(c.ci);
}
const midCreditCustomers = new Set();
for (const c of shuffle(customers.filter((x) => !creditCustomers.has(x.ci)))) {
  if (midCreditCustomers.size >= TARGET.midCredit) break;
  const p = paymentsOf(c.ci).find((x) => x.mode === 'full' && c.invoices.some((i) => i.t > x.t && i.t < x.t + 90 * DAY));
  if (!p) continue;
  p.extra = rint(2, 20) * 5000;
  midCreditCustomers.add(c.ci);
}

// ---- plan: promises, cancellations, disputes --------------------------------------------------

const promises = [];
const invoiceUse = new Map(); // ii -> what claimed it (promise / dispute / cancel)
const claim = (ii, what) => invoiceUse.set(ii, what);
const free = (ii) => !invoiceUse.has(ii);
const lastOf = (arr) => arr[arr.length - 1];

function addPromise(p) {
  const promise = { ri: promises.length, notes: chance(0.75) ? pick(C.PROMISE_NOTES) : null, ...p };
  // Promises that end broken or still open are sent with their real date: judged broken the moment
  // they are made (postprocess-promises.sql dates that to the morning after), a later payment cannot
  // un-break them. The rest must not be judged before their payments arrive, so they get placeholders.
  promise.placeholder = promise.kind === 'BROKEN' || promise.kind === 'OPEN' ? promise.date : `2099-${promise.date.slice(5)}`;
  promises.push(promise);
  if (promise.scoped || promise.basis) promise.invs.forEach((ii) => claim(ii, `promise ${promise.ri}`));
  return promise;
}
/** A promise made some days before a payment, due on or just after the day it arrives. */
function promiseBefore(pay, extra) {
  const invT = Math.max(...pay.invs.map((ii) => invoices[ii].t));
  const prior = pay.after !== undefined ? payments[pay.after].t : invT;
  const gapDays = Math.floor((pay.t - prior) / DAY);
  if (gapDays < 4) return null;
  let t = businessTime(pay.t - rint(2, Math.min(20, gapDays - 2)) * DAY);
  if (t <= prior + HOUR) t = prior + HOUR;
  if (t >= pay.t) return null;
  const date = ymd(pay.t + rint(0, extra.maxLateDays ?? 5) * DAY);
  if (utcDay(date) > TODAY + 60 * DAY) return null;
  return { t, date };
}

const usablePay = (p) => p.invs.every((ii) => free(ii)) && p.promise === undefined && !p.noPromise;
function takePromises(count, candidates, make) {
  let n = 0;
  for (const pay of shuffle(candidates)) {
    if (n >= count) break;
    if (!usablePay(pay)) continue;
    if (make(pay)) n++;
  }
  return n;
}

takePromises(TARGET.promises.KEPT_SCOPED, payments.filter((p) => p.mode === 'full'), (pay) => {
  const w = promiseBefore(pay, {});
  if (!w) return false;
  pay.promise = addPromise({ kind: 'KEPT', scoped: true, ci: pay.ci, invs: pay.invs, ...w }).ri;
  return true;
});
takePromises(TARGET.promises.KEPT_GENERAL, payments.filter((p) => p.mode === 'full' && !p.combined), (pay) => {
  const w = promiseBefore(pay, {});
  if (!w) return false;
  pay.promise = addPromise({ kind: 'KEPT', scoped: false, basis: true, ci: pay.ci, invs: pay.invs, ...w }).ri;
  return true;
});
takePromises(TARGET.promises.PARTIAL, payments.filter((p) => p.mode === 'part'), (pay) => {
  const w = promiseBefore(pay, { maxLateDays: 4 });
  if (!w) return false;
  pay.promise = addPromise({ kind: 'PARTIAL', scoped: true, ci: pay.ci, invs: pay.invs, ...w }).ri;
  return true;
});

const unpaid = () => invoices.filter((i) => i.fate === 'UNPAID' && free(i.ii));
{
  let n = 0;
  for (const inv of shuffle(unpaid().filter((i) => (END - i.t) / DAY >= 45))) {
    if (n >= TARGET.promises.BROKEN_UNPAID) break;
    const t = businessTime(inv.t + rint(8, 25) * DAY);
    const date = ymd(t + rint(7, 20) * DAY);
    if (utcDay(date) >= TODAY - DAY || t >= END) continue;
    addPromise({ kind: 'BROKEN', scoped: true, ci: inv.ci, invs: [inv.ii], t, date });
    n++;
  }
}
{
  let n = 0;
  for (const pay of shuffle(payments.filter((p) => p.single && p.extra === 0))) {
    if (n >= TARGET.promises.BROKEN_LATE) break;
    const inv = invoices[pay.invs[0]];
    if (!usablePay(pay) || (pay.t - inv.t) / DAY < 30) continue;
    const t = businessTime(inv.t + rint(3, 10) * DAY);
    const date = ymd(t + rint(5, 10) * DAY);
    if (utcDay(date) >= dayOf(pay.t) - DAY) continue;
    pay.noPromise = true;
    addPromise({ kind: 'BROKEN', scoped: true, ci: inv.ci, invs: [inv.ii], t, date });
    n++;
  }
}
{
  let n = 0;
  for (const inv of shuffle(unpaid().filter((i) => i.t >= END - 40 * DAY && i.t <= END - 2 * DAY))) {
    if (n >= TARGET.promises.OPEN) break;
    const t = businessBetween(inv.t + HOUR, END);
    addPromise({ kind: 'OPEN', scoped: true, ci: inv.ci, invs: [inv.ii], t, date: ymd(TODAY + rint(3, 40) * DAY) });
    n++;
  }
}
{
  let n = 0;
  const pool = invoices.filter((i) => (i.fate === 'UNPAID' || i.fate === 'PARTIAL') && free(i.ii) && (END - i.t) / DAY >= 20);
  for (const inv of shuffle(pool)) {
    if (n >= TARGET.promises.CANCELLED) break;
    if (inv.payments.some((pi) => payments[pi].promise !== undefined)) continue;
    const t = businessTime(inv.t + rint(3, 15) * DAY);
    const cancelAt = laterBy(t, rint(2, 10));
    if (t >= END - 3 * DAY) continue;
    const scoped = n < 7;
    inv.payments.forEach((pi) => { payments[pi].noPromise = true; });
    const p = addPromise({ kind: 'CANCELLED', scoped, basis: !scoped, ci: inv.ci, invs: [inv.ii], t, date: ymd(t + rint(7, 20) * DAY) });
    p.cancelAt = cancelAt;
    p.cancelReason = pick(C.PROMISE_CANCEL_REASONS);
    n++;
  }
}
for (const p of promises) {
  ev(p.t, 'createPromise', { ri: p.ri });
  if (p.cancelAt) ev(p.cancelAt, 'cancelPromise', { ri: p.ri });
}
for (const pay of payments) {
  if (pay.promise !== undefined && !pay.notes) pay.notes = chance(0.4) ? 'Against the promise' : null;
}

// Disputes. One dispute per invoice; cancellations never touch promised invoices.
const disputes = [];
const hasAmbassador = (inv) => inv.lines.some((l) => products[l.k].retireAt);
function addDispute(d) {
  const text = d.text || pick(C.DISPUTE_TEXT[d.textKey]);
  const dispute = { di: disputes.length, roll: R(), ...d, reason: Array.isArray(text) ? text[0] : text, adminNotes: Array.isArray(text) ? text[1] : null };
  disputes.push(dispute);
  if (dispute.ii !== undefined) claim(dispute.ii, `dispute ${dispute.di}`);
  return dispute;
}

// The returned cheque: voided on approval, paid again by transfer a few days later.
{
  const pay = shuffle(payments.filter((p) => p.single && p.extra === 0 && p.promise === undefined && !p.noPromise))
    .find((p) => free(p.invs[0]) && (END - invoices[p.invs[0]].t) / DAY >= 60 && p.t <= END - 25 * DAY);
  if (!pay) throw new Error('no payment suitable for the returned-cheque dispute');
  pay.forceMethod = 'Cheque';
  pay.noPromise = true;
  const openAt = laterBy(pay.t, rint(2, 5));
  const resolveAt = laterBy(openAt, rint(1, 3));
  claim(pay.invs[0], 'void');
  addDispute({ kind: 'void', target: 'PAYMENT', pi: pay.pi, ci: pay.ci, openAt, resolveAt, propose: true, text: C.DISPUTE_TEXT.void });
  addPayment({ ci: pay.ci, t: laterBy(resolveAt, rint(2, 6)), mode: 'full', invs: pay.invs, forceMethod: 'Bank transfer', notes: 'Replacement for the returned cheque', replacement: true });
}
{
  let n = 0;
  for (const inv of shuffle(unpaid().filter((i) => (END - i.t) / DAY >= 10))) {
    if (n >= TARGET.disputes.cancelUnpaid) break;
    const openAt = laterBy(inv.t, rint(2, Math.min(25, Math.floor((END - inv.t) / DAY) - 5)));
    const resolveAt = laterBy(openAt, rint(2, 10));
    if (resolveAt >= END) continue;
    addDispute({ kind: 'cancel', target: 'INVOICE', ii: inv.ii, ci: inv.ci, openAt, resolveAt, propose: n < 2, textKey: 'cancel' });
    n++;
  }
}
{
  let n = 0;
  for (const pay of shuffle(payments.filter((p) => p.single && p.extra === 0 && p.promise === undefined && !p.noPromise))) {
    if (n >= TARGET.disputes.cancelPaid) break;
    const inv = invoices[pay.invs[0]];
    if (!free(inv.ii) || creditCustomers.has(inv.ci)) continue;
    const openAt = laterBy(pay.t, rint(3, 15));
    const resolveAt = laterBy(openAt, rint(2, 8));
    if (openAt >= END - 5 * DAY || resolveAt >= END) continue;
    addDispute({ kind: 'cancel', target: 'INVOICE', ii: inv.ii, ci: inv.ci, openAt, resolveAt, propose: n < 1, textKey: 'cancel', refund: true });
    n++;
  }
}
{
  let n = 0;
  const pool = invoices.filter((i) => (i.fate === 'UNPAID' || i.fate === 'PARTIAL') && free(i.ii) && !hasAmbassador(i) && (END - i.t) / DAY >= 12);
  for (const inv of shuffle(pool)) {
    if (n >= TARGET.disputes.replace) break;
    const openAt = laterBy(inv.t, rint(2, Math.min(20, Math.floor((END - inv.t) / DAY) - 8)));
    const resolveAt = laterBy(openAt, rint(2, 10));
    if (resolveAt >= END) continue;
    addDispute({ kind: 'replace', target: 'INVOICE', ii: inv.ii, ci: inv.ci, openAt, resolveAt, propose: n < 5, textKey: 'replace_items' });
    n++;
  }
}
{
  let n = 0;
  for (const inv of shuffle(invoices.filter((i) => !invoiceUse.has(i.ii) || /^promise/.test(invoiceUse.get(i.ii))))) {
    if (n >= TARGET.disputes.denied) break;
    if ((END - inv.t) / DAY < 8 || disputes.some((d) => d.ii === inv.ii)) continue;
    const openAt = laterBy(inv.t, rint(2, Math.min(40, Math.floor((END - inv.t) / DAY) - 5)));
    const resolveAt = laterBy(openAt, rint(2, 12));
    if (resolveAt >= END) continue;
    addDispute({ kind: 'deny', target: 'INVOICE', ii: inv.ii, ci: inv.ci, openAt, resolveAt, textKey: 'deny' });
    n++;
  }
}
{
  let n = 0;
  // Customers dispute what they were billed recently.
  const pool = invoices.filter((i) => i.t <= END - 2 * DAY && i.t >= END - 120 * DAY && (!invoiceUse.has(i.ii) || /^promise/.test(invoiceUse.get(i.ii))));
  for (const inv of shuffle(pool)) {
    if (n >= TARGET.disputes.pending) break;
    if (disputes.some((d) => d.ii === inv.ii)) continue;
    const from = Math.max(inv.t + DAY, END - 25 * DAY);
    const openAt = businessBetween(from, END - HOUR);
    if (openAt <= inv.t) continue;
    addDispute({ kind: 'pending', target: 'INVOICE', ii: inv.ii, ci: inv.ci, openAt, textKey: 'pending' });
    n++;
  }
}
for (const d of disputes) {
  ev(d.openAt, 'openDispute', { di: d.di });
  if (d.resolveAt) ev(d.resolveAt, 'resolveDispute', { di: d.di });
}

// Invoices cancelled soon after they were raised, before any money reached them.
{
  let n = 0;
  const avoid = new Set([...creditCustomers, ...midCreditCustomers, ...disputes.filter((d) => d.refund).map((d) => d.ci)]);
  for (const inv of shuffle(unpaid())) {
    if (n >= TARGET.directCancels) break;
    if (avoid.has(inv.ci)) continue;
    claim(inv.ii, 'cancel');
    ev(laterBy(inv.t, rint(0, 6)), 'cancelInvoice', { ii: inv.ii });
    n++;
  }
}
for (const pay of payments) ev(pay.t, 'pay', { pi: pay.pi });

// ---- order the timeline -----------------------------------------------------------------------

events.sort((a, b) => a.t - b.t || a.order - b.order);
for (let i = 1; i < events.length; i++) {
  if (events[i].t <= events[i - 1].t) events[i].t = events[i - 1].t + 1000;
}

function planSummary() {
  const count = (arr, f) => arr.reduce((m, x) => (m[f(x)] = (m[f(x)] || 0) + 1, m), {});
  const perMonth = count(invoices, (i) => ymd(i.t).slice(0, 7));
  const expectedBilled = invoices.reduce((s, i) => s + i.expected, 0);
  return {
    seed: SEED, now: iso(NOW), simulatedEnd: iso(END),
    events: events.length, eventsByKind: count(events, (e) => e.kind),
    customers: customers.length, staff: staff.length, products: products.length,
    invoices: invoices.length, lineItems: invoices.reduce((s, i) => s + i.lines.length, 0),
    fates: count(invoices, (i) => i.fate),
    payments: payments.length, paymentModes: count(payments, (p) => p.mode), pairs, triples, instalments: instalments.size,
    promises: count(promises, (p) => p.kind + (p.scoped ? '' : '/general')),
    disputes: count(disputes, (d) => d.kind), directCancels: events.filter((e) => e.kind === 'cancelInvoice').length,
    creditCustomers: creditCustomers.size, midCreditCustomers: midCreditCustomers.size,
    invoicesPerMonth: perMonth,
    invoicesPerCustomer: { min: Math.min(...customers.map((c) => c.invoices.length)), max: Math.max(...customers.map((c) => c.invoices.length)) },
    averageInvoiceRupees: Math.round(expectedBilled / invoices.length),
    expectedBilledCrore: Math.round(expectedBilled / 1e7),
    plannedCollectedCrorePerMonth: (() => {
      const m = {};
      for (const p of payments) {
        const exp = p.invs.reduce((s, ii) => s + invoices[ii].expected, 0);
        const amt = p.mode === 'part' || p.mode === 'first' ? exp * p.frac
          : p.after !== undefined ? exp * (1 - payments[p.after].frac) : exp;
        const k = ymd(p.t).slice(0, 7);
        m[k] = (m[k] || 0) + amt / 1e7;
      }
      return Object.fromEntries(Object.entries(m).sort().map(([k, v]) => [k, Math.round(v)]));
    })(),
    openInvoicesByAge: (() => {
      const b = { '0-30': 0, '31-60': 0, '61-90': 0, '90+': 0 };
      for (const i of invoices.filter((x) => x.fate !== 'FULL')) {
        const a = (END - i.t) / DAY;
        b[a <= 30 ? '0-30' : a <= 60 ? '31-60' : a <= 90 ? '61-90' : '90+']++;
      }
      return b;
    })(),
    lastEvent: iso(events[events.length - 1].t),
  };
}

fs.mkdirSync(OUT, { recursive: true });
fs.writeFileSync(path.join(OUT, 'plan-summary.json'), JSON.stringify(planSummary(), null, 2));
if (PLAN_ONLY) {
  console.log(JSON.stringify(planSummary(), null, 2));
  process.exit(0);
}

// ---- execute ----------------------------------------------------------------------------------

async function api(method, p, { token, body } = {}) {
  const res = await fetch(API + p, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text };
}
async function must(promise, what) {
  const r = await promise;
  if (r.status >= 300) throw new Error(`${what} -> ${r.status} ${r.text.slice(0, 300)}`);
  return r.json;
}
const tokens = new Map();
async function tokenFor(username) {
  if (!tokens.has(username)) {
    const r = await must(api('POST', '/api/auth/login', { body: { username, password: BOOTSTRAP_PASSWORDS[username] || PASSWORD } }), `login ${username}`);
    tokens.set(username, r.token);
  }
  return tokens.get(username);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const ids = { staff: {}, roles: {}, products: {}, customers: {}, invoices: {}, payments: {}, promises: {}, disputes: {} };
const skipped = { promises: new Set() };
const warnings = [];
const warn = (e, msg) => warnings.push(`${iso(e.t)} ${e.kind}: ${msg}`);
const money = (x) => Number(x);

async function invoiceNow(ii) {
  return must(api('GET', `/api/invoices/${ids.invoices[ii]}`, { token: await tokenFor('admin') }), `get invoice ${ii}`);
}
function methodFor(pay, amount) {
  if (pay.forceMethod) return pay.forceMethod;
  const x = pay.methodRoll;
  if (amount < 100000) return x < 0.6 ? 'UPI' : 'Cash';
  if (amount < 200000) return x < 0.5 ? 'UPI' : x < 0.8 ? 'Cheque' : 'Cash';
  return x < 0.55 ? 'Bank transfer' : x < 0.9 ? 'Cheque' : 'Demand draft';
}
function replaceChange(inv, roll) {
  const items = inv.items.map((it) => ({ productId: it.productId, quantity: it.quantity, unitPrice: money(it.unitPrice) }));
  const multi = items.findIndex((it) => it.quantity >= 2);
  if (multi >= 0 && roll < 0.6) {
    items[multi].quantity -= 1;
    return JSON.stringify({ action: 'replace_items', items, notes: 'Corrected after dispute: one unit fewer, as delivered' });
  }
  let top = 0;
  items.forEach((it, j) => { if (it.quantity * it.unitPrice > items[top].quantity * items[top].unitPrice) top = j; });
  items[top].unitPrice = Math.round((items[top].unitPrice * 0.96) / 1000) * 1000;
  return JSON.stringify({ action: 'replace_items', items, notes: 'Corrected after dispute: agreed fleet discount applied' });
}

const handlers = {
  async renameStaff(e) {
    const admin = await tokenFor('admin');
    const list = await must(api('GET', '/api/users?size=50', { token: admin }), 'list users');
    const u = list.content.find((x) => x.username === e.username);
    await must(api('PUT', `/api/users/${u.id}`, { token: admin, body: { fullName: e.fullName } }), `rename ${e.username}`);
    ids.staff[e.username] = u.id;
  },
  async createStaff(e) {
    const admin = await tokenFor('admin');
    if (!Object.keys(ids.roles).length) {
      const roles = await must(api('GET', '/api/roles?size=50', { token: admin }), 'roles');
      roles.content.forEach((r) => { ids.roles[r.name] = r.id; });
    }
    const s = e.staff;
    const u = await must(api('POST', '/api/users', { token: admin, body: { username: s.username, email: s.email, fullName: s.fullName, password: PASSWORD, roleId: ids.roles[s.role], active: true } }), `create ${s.username}`);
    ids.staff[s.username] = u.id;
  },
  async createProduct(e) {
    const p = products[e.k];
    const r = await must(api('POST', '/api/products', { token: await tokenFor('admin'), body: { name: p.name, description: p.description, price: p.price, active: true } }), `product ${p.name}`);
    ids.products[e.k] = r.id;
  },
  async retireProduct(e) {
    const p = products[e.k];
    await must(api('PUT', `/api/products/${ids.products[e.k]}`, { token: await tokenFor('admin'), body: { name: p.name, description: p.description, price: p.price, active: false } }), `retire ${p.name}`);
  },
  async createCustomer(e) {
    const c = customers[e.ci];
    const r = await must(api('POST', '/api/customers', { token: await tokenFor('admin'), body: { name: c.name, phone: c.phone, email: c.email, address: c.address, username: c.username, password: PASSWORD } }), `customer ${c.name}`);
    ids.customers[e.ci] = r.id;
  },
  async addPoc(e) {
    const r = await api('POST', `/api/customers/${ids.customers[e.ci]}/pocs`, { token: await tokenFor('admin'), body: { pocType: e.type, userId: ids.staff[e.username], primary: e.primary } });
    if (r.status >= 300) warn(e, `${r.status} ${r.text.slice(0, 160)}`);
  },
  async createInvoice(e) {
    const inv = invoices[e.ii];
    const body = {
      customerId: ids.customers[inv.ci], invoiceDate: iso(e.t), notes: inv.notes, salesPocUserId: ids.staff[inv.sales],
      items: inv.lines.map((l) => ({ productId: ids.products[l.k], quantity: l.qty, unitPrice: l.unitPrice })),
    };
    const r = await must(api('POST', '/api/invoices', { token: await tokenFor('admin'), body }), `invoice ${e.ii}`);
    ids.invoices[e.ii] = r.id;
  },
  async cancelInvoice(e) {
    const inv = await invoiceNow(e.ii);
    if (inv.status !== 'UNPAID' || money(inv.paidAmount) > 0) return warn(e, `invoice ${e.ii} is ${inv.status}, not cancelled`);
    const r = await api('POST', `/api/invoices/${ids.invoices[e.ii]}/cancel`, { token: await tokenFor('admin') });
    if (r.status >= 300) warn(e, `${r.status} ${r.text.slice(0, 160)}`);
  },
  async pay(e) {
    const pay = payments[e.pi];
    const open = [];
    for (const ii of pay.invs) {
      const inv = await invoiceNow(ii);
      if (inv.status !== 'CANCELLED' && money(inv.balance) > 0) open.push({ ii, balance: money(inv.balance) });
    }
    const balance = open.reduce((s, x) => s + x.balance, 0);
    if (!open.length) return warn(e, `payment ${e.pi}: nothing left to pay`);
    let amount;
    if (pay.mode === 'full') {
      amount = balance;
    } else {
      const step = balance > 1000000 ? 100000 : 10000;
      amount = Math.round((balance * pay.frac) / step) * step;
      if (amount <= 0 || amount >= balance) amount = Math.round(balance * pay.frac);
      if (amount <= 0 || amount >= balance) return warn(e, `payment ${e.pi}: balance ${balance} too small to part-pay`);
    }
    amount += pay.extra;
    const promiseIds = pay.promise !== undefined && ids.promises[pay.promise] && !skipped.promises.has(pay.promise) && !promises[pay.promise].cancelled
      ? [ids.promises[pay.promise]] : undefined;
    const c = customers[pay.ci];
    const body = {
      customerId: ids.customers[pay.ci], amount, method: methodFor(pay, amount), notes: pay.notes,
      invoiceIds: open.map((x) => ids.invoices[x.ii]), collectionPocUserId: ids.staff[c.collection], promiseIds,
    };
    const r = await api('POST', '/api/payments', { token: await tokenFor('admin'), body });
    if (r.status >= 300) return warn(e, `payment ${e.pi}: ${r.status} ${r.text.slice(0, 200)}`);
    ids.payments[e.pi] = r.json.id;
  },
  async createPromise(e) {
    const p = promises[e.ri];
    let amount = 0;
    for (const ii of p.invs) {
      const inv = await invoiceNow(ii);
      if (inv.status !== 'CANCELLED') amount += money(inv.balance);
    }
    if (amount <= 0) { skipped.promises.add(e.ri); return warn(e, `promise ${e.ri}: nothing owed`); }
    const c = customers[p.ci];
    const body = {
      customerId: ids.customers[p.ci], amount, promisedDate: p.placeholder, collectionPocUserId: ids.staff[c.collection],
      notes: p.notes, invoiceIds: p.scoped ? p.invs.map((ii) => ids.invoices[ii]) : [],
    };
    const r = await api('POST', '/api/promises', { token: await tokenFor('admin'), body });
    if (r.status >= 300) { skipped.promises.add(e.ri); return warn(e, `promise ${e.ri}: ${r.status} ${r.text.slice(0, 200)}`); }
    ids.promises[e.ri] = r.json.id;
  },
  async cancelPromise(e) {
    const p = promises[e.ri];
    if (!ids.promises[e.ri]) return warn(e, `promise ${e.ri} was never created`);
    const r = await api('POST', `/api/promises/${ids.promises[e.ri]}/cancel`, { token: await tokenFor('admin'), body: { reason: p.cancelReason } });
    if (r.status >= 300) return warn(e, `cancel promise ${e.ri}: ${r.status} ${r.text.slice(0, 160)}`);
    p.cancelled = true;
  },
  async openDispute(e) {
    const d = disputes[e.di];
    const c = customers[d.ci];
    let targetId;
    let proposed = null;
    if (d.target === 'PAYMENT') {
      targetId = ids.payments[d.pi];
      if (!targetId) return warn(e, `dispute ${e.di}: payment ${d.pi} missing`);
      if (d.propose) proposed = JSON.stringify({ action: 'void' });
    } else {
      targetId = ids.invoices[d.ii];
      if (d.propose && d.kind === 'cancel') proposed = JSON.stringify({ action: 'cancel' });
      if (d.propose && d.kind === 'replace') proposed = replaceChange(await invoiceNow(d.ii), d.roll);
    }
    const r = await api('POST', '/api/disputes', { token: await tokenFor(c.username), body: { targetType: d.target, targetId, reason: d.reason, proposedChangeJson: proposed } });
    if (r.status >= 300) return warn(e, `dispute ${e.di}: ${r.status} ${r.text.slice(0, 200)}`);
    ids.disputes[e.di] = r.json.id;
  },
  async resolveDispute(e) {
    const d = disputes[e.di];
    const id = ids.disputes[e.di];
    if (!id) return warn(e, `dispute ${e.di} was never opened`);
    const admin = await tokenFor('admin');
    let r;
    if (d.kind === 'deny') {
      r = await api('POST', `/api/disputes/${id}/deny`, { token: admin, body: { adminNotes: d.adminNotes } });
    } else {
      let applied = null;
      if (!d.propose) {
        if (d.kind === 'void') applied = JSON.stringify({ action: 'void' });
        if (d.kind === 'cancel') applied = JSON.stringify({ action: 'cancel' });
        if (d.kind === 'replace') applied = replaceChange(await invoiceNow(d.ii), d.roll);
      }
      r = await api('POST', `/api/disputes/${id}/approve`, { token: admin, body: { adminNotes: d.adminNotes, appliedChangeJson: applied } });
    }
    if (r.status >= 300) warn(e, `resolve dispute ${e.di}: ${r.status} ${r.text.slice(0, 200)}`);
  },
};

(async () => {
  const health = await fetch(`${API}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }).catch(() => null);
  if (!health) throw new Error(`No backend at ${API}`);
  await tokenFor('admin'); // log in before the first window opens

  const windows = [];
  const genStart = new Date();
  await sleep(5);
  let done = 0;
  for (const e of events) {
    const realStart = Date.now();
    windows.push({ seq: windows.length, sim: e.t, realStart });
    try {
      await handlers[e.kind](e);
    } catch (err) {
      fs.writeFileSync(path.join(OUT, 'windows.csv'), windowsCsv(windows, Date.now()));
      throw err;
    }
    await sleep(3); // keeps every write of this action strictly before the next window opens
    if (++done % 250 === 0) console.log(`${done}/${events.length} ${iso(e.t).slice(0, 10)} warnings=${warnings.length}`);
  }
  const genEnd = Date.now();

  function windowsCsv(ws, endMs) {
    return 'seq,sim,real_start,real_end\n' + ws.map((w, i) => `${w.seq},${iso(w.sim)},${iso(w.realStart)},${iso(i + 1 < ws.length ? ws[i + 1].realStart : endMs)}`).join('\n') + '\n';
  }
  fs.writeFileSync(path.join(OUT, 'windows.csv'), windowsCsv(windows, genEnd));
  fs.writeFileSync(path.join(OUT, 'promise_dates.csv'), 'promise_id,real_date\n' + promises
    .filter((p) => ids.promises[p.ri]).map((p) => `${ids.promises[p.ri]},${p.date}`).join('\n') + '\n');
  const meta = { genStart: genStart.toISOString(), genEnd: iso(genEnd), simSetup: iso(SETUP + 3 * HOUR + 30 * MIN), simEnd: iso(END), today: ymd(TODAY) };
  fs.writeFileSync(path.join(OUT, 'meta.json'), JSON.stringify(meta, null, 2));
  fs.writeFileSync(path.join(OUT, 'ids.json'), JSON.stringify({ ids, planned: { promises: promises.map((p) => ({ ri: p.ri, kind: p.kind, scoped: p.scoped, date: p.date })), disputes: disputes.map((d) => ({ di: d.di, kind: d.kind })) } }, null, 2));
  fs.writeFileSync(path.join(OUT, 'warnings.txt'), warnings.join('\n') + '\n');
  console.log(JSON.stringify({ ...meta, events: events.length, warnings: warnings.length }, null, 2));
  if (warnings.length) console.log(warnings.slice(0, 40).join('\n'));
})().catch((err) => { console.error('GENERATION FAILED:', err.message); process.exit(1); });
