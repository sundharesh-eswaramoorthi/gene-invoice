// API regression tests: customers, customer POC seats, products.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');

const results = [];
function rec(id, title, ok, evidence) {
  results.push({ id, title, status: ok ? 'PASS' : 'FAIL', evidence });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${title} :: ${typeof evidence === 'string' ? evidence : JSON.stringify(evidence)}`.slice(0, 900));
}
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 260)}`;
const enc = encodeURIComponent;

async function main() {
  const A = await rt.adminToken();
  const P = rt.PASSWORD;
  const TAG = rt.uniq('cp');           // shared prefix for all of this run's customers
  const mk = async (suffix, extra = {}) => {
    const username = `${TAG}-${suffix}`;
    const body = { name: `${TAG} ${suffix}`, phone: '555-01', email: `${username}@rt.local`, address: '1 Way', username, password: P, ...extra };
    const r = await rt.api('POST', '/api/customers', { token: A, body });
    if (r.status !== 200) throw new Error('mk ' + snip(r));
    return { ...r.json, username };
  };

  // staff
  const cs1 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpcs');
  const cs2 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpcs');
  const cs3 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpcs');
  const col1 = await rt.createStaff(A, 'COLLECTION_POC', 'cpcol');
  const col2 = await rt.createStaff(A, 'COLLECTION_POC', 'cpcol');
  const sales = await rt.createStaff(A, 'SALES_POC', 'cpsal');
  const viewer = await rt.createStaff(A, 'VIEWER', 'cpview');
  const csInactive = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpcsoff');
  let r = await rt.api('PUT', `/api/users/${csInactive.id}`, { token: A, body: { active: false } });
  console.log('deactivate csInactive', r.status);

  // ---------------- customers: create ----------------
  const c1 = await mk('alpha', { phone: '555-0003', email: `${TAG}-c@rt.local` });
  let cust1Login = null; try { cust1Login = await rt.login(c1.username, P); } catch (e) { cust1Login = String(e); }
  r = await rt.api('GET', `/api/users?filter=${enc('username:eq:' + c1.username)}`, { token: A });
  const lu = r.json?.content?.[0];
  rec('CUS-001', 'Create customer creates customer + CUSTOMER login', c1.id && c1.name === `${TAG} alpha` && c1.username === c1.username
      && typeof cust1Login === 'string' && cust1Login.length > 20 && lu && lu.customerId === c1.id && lu.role === 'CUSTOMER'
      && Number(c1.outstanding) === 0 && Number(c1.creditBalance) === 0 && c1.pocMissing === true,
  { created: { id: c1.id, username: c1.username, outstanding: c1.outstanding, credit: c1.creditBalance, pocMissing: c1.pocMissing }, loginUser: lu && { customerId: lu.customerId, role: lu.role, fullName: lu.fullName } });

  r = await rt.api('POST', '/api/customers', { token: A, body: { name: '  ', username: rt.uniq('cpx'), password: P } });
  rec('CUS-002', 'Create with blank name -> 400 fieldErrors.name', r.status === 400 && !!r.json?.fieldErrors?.name, snip(r));

  const uname3 = rt.uniq('cpx');
  r = await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} nouser`, password: P } });
  const r3b = await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} nopass`, username: uname3 } });
  const chk3 = await rt.api('GET', `/api/customers?filter=${enc('name:contains:' + TAG + ' no')}`, { token: A });
  rec('CUS-003', 'Create without username / password -> 400 with fieldErrors, nothing persisted',
    r.status === 400 && !!r.json?.fieldErrors?.username && r3b.status === 400 && !!r3b.json?.fieldErrors?.password && chk3.json?.totalElements === 0,
    { noUsername: snip(r), noPassword: snip(r3b), persisted: chk3.json?.totalElements });

  r = await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} dup`, username: c1.username, password: P } });
  const chk4 = await rt.api('GET', `/api/customers?filter=${enc('name:eq:' + TAG + ' dup')}`, { token: A });
  r3b.dupAdmin = await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} dupadmin`, username: 'admin', password: P } });
  rec('CUS-004', 'Duplicate username (customer\'s and seeded admin) -> 400 "Username already taken", no orphan customer row',
    r.status === 400 && /Username already taken/.test(r.text) && chk4.json?.totalElements === 0 && r3b.dupAdmin.status === 400,
    { dup: snip(r), orphanRows: chk4.json?.totalElements, dupAdmin: snip(r3b.dupAdmin) });

  // ---------------- GET by id ----------------
  r = await rt.api('GET', `/api/customers/${c1.id}`, { token: A });
  const r404 = await rt.api('GET', `/api/customers/99999999`, { token: A });
  rec('CUS-005', 'GET /api/customers/{id} 200; unknown id 404', r.status === 200 && r.json.id === c1.id && Array.isArray(r.json.successPocs) && r404.status === 404,
    { ok: `${r.status} name=${r.json?.name} username=${r.json?.username}`, missing: snip(r404) });

  // Customer-scoped caller reading another customer
  const c2 = await mk('bravo', { phone: '555-0001', email: `${TAG}-a@rt.local` });
  const custTok = await rt.login(c1.username, P);
  const own = await rt.api('GET', `/api/customers/${c1.id}`, { token: custTok });
  const other = await rt.api('GET', `/api/customers/${c2.id}`, { token: custTok });
  const custList = await rt.api('GET', `/api/customers?size=50`, { token: custTok });
  rec('CUS-006', 'Customer login: own record 200 without POC data; other customer 403; list scoped to self',
    own.status === 200 && own.json.successPocs == null && own.json.pocMissing == null && other.status === 403
    && custList.status === 200 && custList.json.totalElements === 1 && custList.json.content[0].id === c1.id,
    { own: `${own.status} successPocs=${JSON.stringify(own.json?.successPocs)} pocMissing=${own.json?.pocMissing}`, other: snip(other), list: `${custList.status} total=${custList.json?.totalElements}` });

  // ---------------- update ----------------
  r = await rt.api('PUT', `/api/customers/${c1.id}`, { token: A, body: { name: `${TAG} alpha2`, phone: '555-9999', email: `${TAG}-new@rt.local`, address: '2 New Rd' } });
  const g = await rt.api('GET', `/api/customers/${c1.id}`, { token: A });
  const lu2 = (await rt.api('GET', `/api/users?filter=${enc('username:eq:' + c1.username)}`, { token: A })).json?.content?.[0];
  let oldPwStill = false; try { await rt.login(c1.username, P); oldPwStill = true; } catch { oldPwStill = false; }
  rec('CUS-007', 'PUT contact fields persists; linked login fullName/email synced; blank password keeps old password',
    r.status === 200 && g.json.name === `${TAG} alpha2` && g.json.phone === '555-9999' && g.json.email === `${TAG}-new@rt.local` && g.json.address === '2 New Rd'
    && lu2?.fullName === `${TAG} alpha2` && lu2?.email === `${TAG}-new@rt.local` && oldPwStill,
    { put: r.status, get: { name: g.json?.name, phone: g.json?.phone, email: g.json?.email, address: g.json?.address }, login: { fullName: lu2?.fullName, email: lu2?.email }, oldPasswordStillWorks: oldPwStill });

  r = await rt.api('PUT', `/api/customers/${c1.id}`, { token: A, body: { name: `${TAG} alpha2`, phone: '555-0003', email: `${TAG}-c@rt.local`, address: '2 New Rd', password: 'N3wPass!x' } });
  let newOk = false, oldOk = false;
  try { await rt.login(c1.username, 'N3wPass!x'); newOk = true; } catch {}
  try { await rt.login(c1.username, P); oldOk = true; } catch {}
  rec('CUS-008', 'PUT with password changes the customer login password', r.status === 200 && newOk && !oldOk, { put: r.status, newPasswordLogin: newOk, oldPasswordLogin: oldOk });

  r = await rt.api('PUT', `/api/customers/${c1.id}`, { token: A, body: { name: '', phone: 'x' } });
  const g9 = await rt.api('GET', `/api/customers/${c1.id}`, { token: A });
  const r9b = await rt.api('PUT', `/api/customers/99999999`, { token: A, body: { name: 'x' } });
  rec('CUS-009', 'PUT blank name -> 400 fieldErrors.name, nothing changed; PUT unknown id -> 404',
    r.status === 400 && !!r.json?.fieldErrors?.name && g9.json.phone === '555-0003' && r9b.status === 404, { blank: snip(r), phoneAfter: g9.json?.phone, unknown: snip(r9b) });

  // audit of create/update
  r = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${c1.id}`, { token: A });
  const acts = (r.json || []).map((e) => e.action);
  rec('CUS-010', 'Customer create/update written to audit (CUSTOMER_CREATED, CUSTOMER_UPDATED x2) with actor',
    r.status === 200 && acts.includes('CUSTOMER_CREATED') && acts.filter((a) => a === 'CUSTOMER_UPDATED').length >= 2 && (r.json || []).every((e) => e.changedByUsername === 'admin' || e.derived),
    { status: r.status, actions: acts });

  // Cashier (CUSTOMER_MANAGE) can create; viewer cannot
  const cashier = await rt.cashierToken();
  const vTok = await rt.login(viewer.username, P);
  const cc = await rt.api('POST', '/api/customers', { token: cashier, body: { name: `${TAG} bycashier`, username: `${TAG}-bycashier`, password: P, phone: '555-0002', email: `${TAG}-b@rt.local` } });
  const vc = await rt.api('POST', '/api/customers', { token: vTok, body: { name: `${TAG} byviewer`, username: `${TAG}-byviewer`, password: P } });
  const vu = await rt.api('PUT', `/api/customers/${c1.id}`, { token: vTok, body: { name: 'hack' } });
  rec('CUS-011', 'Permissions: CASHIER (CUSTOMER_MANAGE) creates 200; VIEWER create/update -> 403',
    cc.status === 200 && vc.status === 403 && vu.status === 403, { cashier: cc.status, viewerCreate: snip(vc), viewerUpdate: vu.status });
  const c3 = { ...cc.json, username: `${TAG}-bycashier` };

  // ---------------- data for list/sort/tiles ----------------
  // c1: phone 555-0003 email -c ; c2: phone 555-0001 email -a ; c3: phone 555-0002 email -b
  const c4 = await mk('delta', { phone: '', email: '' });
  // product + invoice to give c2 outstanding 300 and c3 outstanding 100
  const prod = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} widget`, price: 100 } });
  const inv = async (cid, qty) => rt.api('POST', '/api/invoices', { token: A, body: { customerId: cid, salesPocUserId: sales.id, items: [{ productId: prod.json.id, quantity: qty }] } });
  const i2 = await inv(c2.id, 3); const i3 = await inv(c3.id, 1);
  console.log('invoices', i2.status, i3.status, i2.status !== 200 ? i2.text : '');
  // credit balance for c4: payment with no outstanding invoices
  const pay = await rt.api('POST', '/api/payments', { token: A, body: { customerId: c4.id, amount: 50, method: 'CASH', collectionPocUserId: col1.id } });
  console.log('payment for credit', pay.status, pay.text.slice(0, 200));
  const c4g = await rt.api('GET', `/api/customers/${c4.id}`, { token: A });
  console.log('c4 credit', c4g.json.creditBalance);

  const F = `filter=${enc('name:contains:' + TAG)}`;
  const listOrder = async (sort) => {
    const x = await rt.api('GET', `/api/customers?size=10&sort=${sort}&${F}`, { token: A });
    return { status: x.status, names: (x.json?.content || []).map((c) => c.name.replace(TAG + ' ', '')), rows: x.json?.content || [], body: x };
  };
  const sorts = {};
  for (const s of ['name,asc', 'name,desc', 'phone,asc', 'email,asc', 'outstanding,desc', 'outstanding,asc', 'creditBalance,desc']) sorts[s] = await listOrder(s);
  const outDesc = sorts['outstanding,desc'].rows.map((c) => Number(c.outstanding));
  const outAsc = sorts['outstanding,asc'].rows.map((c) => Number(c.outstanding));
  const credDesc = sorts['creditBalance,desc'].rows.map((c) => Number(c.creditBalance));
  const isSorted = (a, dir) => a.every((v, i) => i === 0 || (dir > 0 ? a[i - 1] <= v : a[i - 1] >= v));
  const names = sorts['name,asc'].names;
  rec('CUS-012', 'List sort by name/phone/email/outstanding/creditBalance (asc+desc) orders rows correctly',
    names.join() === ['alpha2', 'bravo', 'bycashier', 'delta'].join() && sorts['name,desc'].names.join() === [...names].reverse().join()
    && sorts['email,asc'].names.slice(0, 3).join() === 'bravo,bycashier,alpha2'
    && isSorted(outDesc, -1) && outDesc[0] === 300 && isSorted(outAsc, 1) && isSorted(credDesc, -1) && credDesc[0] === Number(c4g.json.creditBalance) && credDesc[0] > 0,
    { nameAsc: names, nameDesc: sorts['name,desc'].names, phoneAsc: sorts['phone,asc'].names, emailAsc: sorts['email,asc'].names, outstandingDesc: sorts['outstanding,desc'].names.map((n, i) => n + '=' + outDesc[i]), creditDesc: credDesc });

  // paging
  const p0 = await rt.api('GET', `/api/customers?size=10&page=0&sort=name,asc&${F}`, { token: A });
  const pBad = await rt.api('GET', `/api/customers?size=7&${F}`, { token: A });
  const pNeg = await rt.api('GET', `/api/customers?page=-1&${F}`, { token: A });
  const sAddr = await rt.api('GET', `/api/customers?sort=address,asc`, { token: A });
  const sSeat = await rt.api('GET', `/api/customers?sort=successPocUserId,asc`, { token: A });
  const sBogus = await rt.api('GET', `/api/customers?sort=bogus,asc`, { token: A });
  // page 2 of size 10 over all customers must not repeat page 1 rows
  const all1 = await rt.api('GET', `/api/customers?size=10&page=0&sort=name,asc`, { token: A });
  const all2 = await rt.api('GET', `/api/customers?size=10&page=1&sort=name,asc`, { token: A });
  const ids1 = new Set((all1.json?.content || []).map((c) => c.id));
  const overlap = (all2.json?.content || []).filter((c) => ids1.has(c.id)).length;
  rec('CUS-013', 'Paging envelope + validation: size not in [10,20,50] -> 400, page<0 -> 400, non-sortable/unknown sort -> 400; pages do not overlap',
    p0.status === 200 && p0.json.totalElements === 4 && p0.json.totalPages === 1 && p0.json.size === 10 && pBad.status === 400 && pNeg.status === 400
    && sAddr.status === 400 && sSeat.status === 400 && sBogus.status === 400 && overlap === 0,
    { envelope: { total: p0.json?.totalElements, pages: p0.json?.totalPages, size: p0.json?.size, applied: p0.json?.appliedFilters }, size7: snip(pBad), pageNeg: snip(pNeg), sortAddress: snip(sAddr), sortSeat: snip(sSeat), sortBogus: snip(sBogus), overlapPage1vs2: overlap });

  // text filters
  const q = async (...filters) => {
    const qs = filters.map((f) => 'filter=' + enc(f)).join('&');
    const x = await rt.api('GET', `/api/customers?size=50&${qs}`, { token: A });
    return { status: x.status, names: (x.json?.content || []).map((c) => c.name.replace(TAG + ' ', '')).sort(), text: x.text };
  };
  const fContains = await q(`name:contains:${TAG.toUpperCase()} AL`);
  const fEq = await q(`name:eq:${TAG} bravo`);
  const fNeq = await q(`name:contains:${TAG}`, `name:neq:${TAG} bravo`);
  const fPhoneEmpty = await q(`name:contains:${TAG}`, 'phone:isEmpty:');
  const fEmailNotEmpty = await q(`name:contains:${TAG}`, 'email:isNotEmpty:');
  const fAddr = await q(`name:contains:${TAG}`, 'address:contains:new rd');
  const fBadOp = await q('name:gt:x');
  const fUnknownCol = await q('bogus:eq:x');
  const fUnknownOp = await q('name:like:x');
  const fMalformed = await q('name');
  rec('CUS-014', 'Text filter operators contains (case-insensitive)/eq/neq/isEmpty/isNotEmpty and AND-combination; invalid column/op -> 400',
    fContains.names.join() === 'alpha2' && fEq.names.join() === 'bravo' && fNeq.names.join() === 'alpha2,bycashier,delta'
    && fPhoneEmpty.names.join() === 'delta' && fEmailNotEmpty.names.join() === 'alpha2,bravo,bycashier' && fAddr.names.join() === 'alpha2'
    && fBadOp.status === 400 && fUnknownCol.status === 400 && fUnknownOp.status === 400 && fMalformed.status === 400,
    { contains: fContains.names, eq: fEq.names, neq: fNeq.names, phoneIsEmpty: fPhoneEmpty.names, emailIsNotEmpty: fEmailNotEmpty.names, addressContains: fAddr.names, nameGt: fBadOp.status + ' ' + fBadOp.text.slice(0, 120), unknownCol: fUnknownCol.status, unknownOp: fUnknownOp.status, malformed: fMalformed.status });

  // ---------------- POC seats ----------------
  const pocs = async (cid) => (await rt.api('GET', `/api/customers/${cid}/pocs`, { token: A }));
  const add = (cid, type, userId, primary) => rt.api('POST', `/api/customers/${cid}/pocs`, { token: A, body: { pocType: type, userId, ...(primary === undefined ? {} : { primary }) } });

  const a1 = await add(c1.id, 'SUCCESS', cs1.id);
  rec('POC-001', 'First SUCCESS POC added becomes primary automatically', a1.status === 200 && a1.json.primary === true && a1.json.pocType === 'SUCCESS' && a1.json.user.id === cs1.id,
    snip(a1));
  const a2 = await add(c1.id, 'SUCCESS', cs2.id, false);
  let pl = await pocs(c1.id);
  const succ = () => pl.json.filter((p) => p.pocType === 'SUCCESS');
  rec('POC-002', 'Second SUCCESS POC (primary:false) added non-primary; exactly one primary', a2.status === 200 && a2.json.primary === false && succ().length === 2 && succ().filter((p) => p.primary).length === 1,
    { add: snip(a2), seats: succ().map((p) => `${p.user.username}${p.primary ? '*' : ''}`) });
  const a3 = await add(c1.id, 'SUCCESS', cs3.id, true);
  pl = await pocs(c1.id);
  rec('POC-003', 'Adding with primary:true demotes previous primary; still one primary per type', a3.status === 200 && a3.json.primary === true && succ().length === 3 && succ().filter((p) => p.primary).length === 1 && succ().find((p) => p.primary).user.id === cs3.id,
    { seats: succ().map((p) => `${p.user.username}${p.primary ? '*' : ''}`) });
  const aDup = await add(c1.id, 'SUCCESS', cs1.id);
  rec('POC-004', 'Adding the same user twice for the same kind -> 400', aDup.status === 400 && /already/.test(aDup.text), snip(aDup));

  const b1 = await add(c1.id, 'COLLECTION', col1.id);
  const b2 = await add(c1.id, 'COLLECTION', col2.id);
  pl = await pocs(c1.id);
  const coll = () => pl.json.filter((p) => p.pocType === 'COLLECTION');
  const g1 = await rt.api('GET', `/api/customers/${c1.id}`, { token: A });
  rec('POC-005', 'COLLECTION seats independent of SUCCESS: first is primary, second not; customer DTO lists both kinds and pocMissing=false',
    b1.json?.primary === true && b2.json?.primary === false && coll().length === 2 && succ().filter((p) => p.primary).length === 1
    && g1.json.successPocs.length === 3 && g1.json.collectionPocs.length === 2 && g1.json.pocMissing === false,
    { collection: coll().map((p) => `${p.user.username}${p.primary ? '*' : ''}`), dto: { s: g1.json?.successPocs?.length, c: g1.json?.collectionPocs?.length, pocMissing: g1.json?.pocMissing } });

  const secondColl = coll().find((p) => !p.primary);
  const sp = await rt.api('POST', `/api/customers/${c1.id}/pocs/${secondColl.id}/primary`, { token: A });
  pl = await pocs(c1.id);
  rec('POC-006', 'POST .../{pocId}/primary switches primary; only one COLLECTION primary', sp.status === 200 && sp.json.primary === true && coll().filter((p) => p.primary).length === 1 && coll().find((p) => p.primary).user.id === col2.id,
    { resp: snip(sp), collection: coll().map((p) => `${p.user.username}${p.primary ? '*' : ''}`) });

  // remove non-primary success (cs1 seat)
  const cs1Seat = succ().find((p) => p.user.id === cs1.id);
  const rm1 = await rt.api('DELETE', `/api/customers/${c1.id}/pocs/${cs1Seat.id}`, { token: A });
  pl = await pocs(c1.id);
  rec('POC-007', 'Removing a non-primary seat leaves primary unchanged', rm1.status === 200 && succ().length === 2 && succ().find((p) => p.primary)?.user.id === cs3.id,
    { del: rm1.status, seats: succ().map((p) => `${p.user.username}${p.primary ? '*' : ''}`) });

  // remove primary (cs3) -> cs2 promoted
  const cs3Seat = succ().find((p) => p.user.id === cs3.id);
  const rm2 = await rt.api('DELETE', `/api/customers/${c1.id}/pocs/${cs3Seat.id}`, { token: A });
  pl = await pocs(c1.id);
  rec('POC-008', 'Removing the primary promotes the remaining holder to primary (AC-A4)', rm2.status === 200 && succ().length === 1 && succ()[0].primary === true && succ()[0].user.id === cs2.id,
    { del: rm2.status, seats: succ().map((p) => `${p.user.username}${p.primary ? '*' : ''}`) });

  // remove last success -> none, pocMissing true
  const rm3 = await rt.api('DELETE', `/api/customers/${c1.id}/pocs/${succ()[0].id}`, { token: A });
  pl = await pocs(c1.id);
  const g2 = await rt.api('GET', `/api/customers/${c1.id}`, { token: A });
  rec('POC-009', 'Removing the last SUCCESS seat clears it: no SUCCESS seats, pocMissing=true, collection untouched', rm3.status === 200 && succ().length === 0 && coll().length === 2 && g2.json.pocMissing === true,
    { del: rm3.status, success: succ().length, collection: coll().length, pocMissing: g2.json?.pocMissing });

  // invalid adds
  const iSales = await add(c2.id, 'SALES', sales.id);
  const iInactive = await add(c2.id, 'SUCCESS', csInactive.id);
  const iWrongKind = await add(c2.id, 'SUCCESS', col1.id);
  const iCustomer = await add(c2.id, 'COLLECTION', lu.id);
  const iMissingUser = await add(c2.id, 'SUCCESS', 99999999);
  const iNoUser = await rt.api('POST', `/api/customers/${c2.id}/pocs`, { token: A, body: { pocType: 'SUCCESS' } });
  const iBadType = await rt.api('POST', `/api/customers/${c2.id}/pocs`, { token: A, body: { pocType: 'FOO', userId: cs1.id } });
  const iNoCustomer = await add(99999999, 'SUCCESS', cs1.id);
  const afterInvalid = await pocs(c2.id);
  rec('POC-010', 'Invalid seat adds rejected: SALES kind 400, inactive user 400, wrong-privilege user 400, customer account 400, unknown user 404, missing userId 400, bad pocType 400, unknown customer 404; no seat created',
    iSales.status === 400 && iInactive.status === 400 && iWrongKind.status === 400 && iCustomer.status === 400 && iMissingUser.status === 404
    && iNoUser.status === 400 && iBadType.status === 400 && iNoCustomer.status === 404 && afterInvalid.json.length === 0,
    { sales: snip(iSales), inactive: snip(iInactive), wrongKind: snip(iWrongKind), customerAcct: snip(iCustomer), unknownUser: iMissingUser.status, noUser: iNoUser.status, badType: snip(iBadType), unknownCustomer: iNoCustomer.status, seatsLeft: afterInvalid.json?.length });

  // remove/primary with pocId of another customer, unknown pocId
  const c1Coll = coll()[0];
  const xRm = await rt.api('DELETE', `/api/customers/${c2.id}/pocs/${c1Coll.id}`, { token: A });
  const xPr = await rt.api('POST', `/api/customers/${c2.id}/pocs/${c1Coll.id}/primary`, { token: A });
  const xUnknown = await rt.api('DELETE', `/api/customers/${c1.id}/pocs/99999999`, { token: A });
  pl = await pocs(c1.id);
  rec('POC-011', 'pocId of another customer -> 400 (remove and primary), unknown pocId -> 404, seat untouched', xRm.status === 400 && xPr.status === 400 && xUnknown.status === 404 && coll().length === 2,
    { remove: snip(xRm), primary: xPr.status, unknown: xUnknown.status, seatsStill: coll().length });

  // permissions
  const custGet = await rt.api('GET', `/api/customers/${c1.id}/pocs`, { token: custTok });
  const vGet = await rt.api('GET', `/api/customers/${c1.id}/pocs`, { token: vTok });
  const vAdd = await rt.api('POST', `/api/customers/${c1.id}/pocs`, { token: vTok, body: { pocType: 'SUCCESS', userId: cs1.id } });
  const vDel = await rt.api('DELETE', `/api/customers/${c1.id}/pocs/${c1Coll.id}`, { token: vTok });
  const custAssignable = await rt.api('GET', `/api/pocs/assignable?type=SUCCESS`, { token: custTok });
  rec('POC-012', 'Seat permissions: customer login GET pocs 403 and assignable 403; VIEWER (POC_VIEW only) GET 200 but add/remove 403',
    custGet.status === 403 && custAssignable.status === 403 && vGet.status === 200 && vAdd.status === 403 && vDel.status === 403,
    { customerGet: custGet.status, customerAssignable: custAssignable.status, viewerGet: vGet.status, viewerAdd: vAdd.status, viewerDel: vDel.status });

  // assignable list
  const asg = async (type, qq, lim) => rt.api('GET', `/api/pocs/assignable?type=${type}${qq !== undefined ? '&q=' + enc(qq) : ''}${lim ? '&limit=' + lim : ''}`, { token: A });
  const asS = await asg('SUCCESS', 'cpcs');
  const sNames = (asS.json || []).map((u) => u.username);
  const asAllS = await asg('SUCCESS', undefined, 100);
  const allSOk = (asAllS.json || []).every((u) => u.active && ['CUSTOMER_SUCCESS_POC', 'ADMIN'].includes(u.role) || u.active);
  const rolesS = [...new Set((asAllS.json || []).map((u) => u.role))];
  const asC = await asg('COLLECTION', col1.username);
  const asQEmail = await asg('SUCCESS', `${cs2.username}@RT.LOCAL`);
  const asQName = await asg('SUCCESS', cs3.username.toUpperCase());
  const asNone = await asg('SUCCESS', 'zzz-no-such-person');
  const asSalesNotInS = (await asg('SUCCESS', sales.username)).json;
  rec('POC-013', 'GET /api/pocs/assignable returns only active users with matching POC_ASSIGNABLE_* privilege; q matches username/fullName/email case-insensitively',
    asS.status === 200 && sNames.includes(cs1.username) && sNames.includes(cs2.username) && !sNames.includes(csInactive.username)
    && (asAllS.json || []).every((u) => u.active) && !rolesS.includes('COLLECTION_POC') && !rolesS.includes('SALES_POC') && !rolesS.includes('CUSTOMER') && !rolesS.includes('CASHIER')
    && asC.json?.length === 1 && asC.json[0].id === col1.id && asQEmail.json?.length === 1 && asQEmail.json[0].id === cs2.id && asQName.json?.length === 1
    && Array.isArray(asNone.json) && asNone.json.length === 0 && Array.isArray(asSalesNotInS) && asSalesNotInS.length === 0,
    { qCpcs: sNames.length, inactiveExcluded: !sNames.includes(csInactive.username), rolesInSuccessList: rolesS, collectionByUsername: asC.json?.map((u) => u.username), byEmail: asQEmail.json?.map((u) => u.username), byFullNameUpper: asQName.json?.length, noMatch: asNone.json, salesUserInSuccess: asSalesNotInS?.length });

  const asLim = await asg('SUCCESS', undefined, 2);
  const asDefault = await asg('SUCCESS');
  const asBogus = await rt.api('GET', `/api/pocs/assignable?type=bogus`, { token: A });
  const asMissing = await rt.api('GET', `/api/pocs/assignable`, { token: A });
  rec('POC-014', 'assignable: limit honoured (limit=2 -> <=2, default <=25); type=bogus / missing type -> 400 (clean validation error)',
    asLim.json?.length <= 2 && asDefault.json?.length <= 25 && asBogus.status === 400 && asMissing.status === 400,
    { limit2: asLim.json?.length, default: asDefault.json?.length, bogus: snip(asBogus), missing: snip(asMissing) });

  // custom role with POC_ASSIGNABLE_SUCCESS only -> appears in success list
  const roleName = rt.uniq('CPROLE').toUpperCase();
  const role = await rt.api('POST', '/api/roles', { token: A, body: { name: roleName, description: 'rt', privileges: ['CUSTOMER_VIEW', 'POC_ASSIGNABLE_SUCCESS', 'POC_ASSIGNABLE_COLLECTION'] } });
  const cu = await rt.api('POST', '/api/users', { token: A, body: { username: rt.uniq('cpcustom'), email: 'x@rt.local', fullName: 'Custom Both', password: P, roleId: role.json?.id, active: true } });
  const inS = (await asg('SUCCESS', cu.json?.username)).json || [];
  const inC = (await asg('COLLECTION', cu.json?.username)).json || [];
  rec('POC-015', 'A custom role carrying POC_ASSIGNABLE_SUCCESS + _COLLECTION makes its user assignable as both kinds (privilege, not role name)',
    role.status === 200 && cu.status === 200 && inS.length === 1 && inC.length === 1, { role: role.status, user: cu.status, inSuccess: inS.length, inCollection: inC.length });

  // audit of POC changes
  const au = await rt.api('GET', `/api/audit?entityType=CUSTOMER&entityId=${c1.id}`, { token: A });
  const pocRows = (au.json || []).filter((e) => e.action.startsWith('POC_'));
  const count = (a) => pocRows.filter((e) => e.action === a).length;
  const prim = pocRows.find((e) => e.action === 'POC_PRIMARY_CHANGED');
  rec('POC-016', 'Every seat change audited on the customer: 5 POC_ASSIGNED, 3 POC_REMOVED, 1 POC_PRIMARY_CHANGED (with before/after, actor)',
    count('POC_ASSIGNED') === 5 && count('POC_REMOVED') === 3 && count('POC_PRIMARY_CHANGED') === 1 && prim?.beforeJson && prim?.afterJson && pocRows.every((e) => e.changedByUsername === 'admin'),
    { counts: { assigned: count('POC_ASSIGNED'), removed: count('POC_REMOVED'), primaryChanged: count('POC_PRIMARY_CHANGED') }, primaryRow: prim && { before: prim.beforeJson, after: prim.afterJson, reason: prim.reason } });

  // implicit primary changes audited? (auto-promotion on removal of cs3 -> cs2, demotion on add(primary) of cs3)
  const promoRow = pocRows.find((e) => e.action === 'POC_REMOVED' && /cpcs/.test(e.beforeJson || '') && JSON.parse(e.beforeJson).userId === cs3.id);
  const demoteRow = pocRows.find((e) => e.action === 'POC_ASSIGNED' && e.afterJson && JSON.parse(e.afterJson).userId === cs3.id);
  const mentionsCs2Promotion = pocRows.some((e) => (e.afterJson || '').includes(cs2.username) && e.action !== 'POC_ASSIGNED');
  rec('POC-017', 'Implicit primary changes are captured in audit (auto-promotion of cs2 on primary removal; demotion of cs1 when cs3 added as primary) — AC-A7',
    mentionsCs2Promotion && demoteRow && demoteRow.beforeJson != null,
    { removalOfPrimary: promoRow && { before: promoRow.beforeJson, after: promoRow.afterJson, reason: promoRow.reason }, addAsPrimary: demoteRow && { before: demoteRow.beforeJson, after: demoteRow.afterJson }, anyRowRecordingPromotionOfCs2: mentionsCs2Promotion });

  // assignee notification
  const csTok = await rt.login(col2.username, P);
  const notes = await rt.api('GET', `/api/notifications?size=20`, { token: csTok });
  const notif = (notes.json?.content || []).find((n) => n.type === 'POC_ASSIGNED');
  rec('POC-018', 'Newly assigned POC receives a POC_ASSIGNED notification linking to the customer', !!notif && /\/customers\/\d+/.test(notif.link || ''), notif ? { title: notif.title, link: notif.link } : snip(notes));

  // ---------------- seat filters and tiles ----------------
  // c1: collection col1,col2 ; no success. c2: none. c3: success cs1 ; c4: success cs2 + collection col1
  await add(c3.id, 'SUCCESS', cs1.id);
  await add(c4.id, 'SUCCESS', cs2.id);
  await add(c4.id, 'COLLECTION', col1.id);
  const sf = async (f) => (await q(`name:contains:${TAG}`, f));
  const sEq = await sf(`successPocUserId:eq:${cs1.id}`);
  const cEq = await sf(`collectionPocUserId:eq:${col1.id}`);
  const sEmpty = await sf('successPocUserId:isEmpty:');
  const cEmpty = await sf('collectionPocUserId:isEmpty:');
  const cNotEmpty = await sf('collectionPocUserId:isNotEmpty:');
  const cNeq = await sf(`collectionPocUserId:neq:${col2.id}`);
  const sIn = await sf(`successPocUserId:in:${cs1.id},${cs2.id}`);
  const sContains = await sf('successPocUserId:contains:x');
  rec('POC-019', 'Seat filters: eq = holds a seat, isEmpty = POC missing, isNotEmpty, neq, in; contains rejected 400',
    sEq.names.join() === 'bycashier' && cEq.names.join() === 'alpha2,delta' && sEmpty.names.join() === 'alpha2,bravo' && cEmpty.names.join() === 'bravo,bycashier'
    && cNotEmpty.names.join() === 'alpha2,delta' && cNeq.names.join() === 'bravo,bycashier,delta' && sIn.names.join() === 'bycashier,delta' && sContains.status === 400,
    { successEqCs1: sEq.names, collectionEqCol1: cEq.names, successIsEmpty: sEmpty.names, collectionIsEmpty: cEmpty.names, collectionIsNotEmpty: cNotEmpty.names, collectionNeqCol2: cNeq.names, successIn: sIn.names, contains: sContains.status });

  const tiles = async (...filters) => {
    const qs = filters.map((f) => 'filter=' + enc(f)).join('&');
    return rt.api('GET', `/api/customers/summary?${qs}`, { token: A });
  };
  const t1 = await tiles(`name:contains:${TAG}`);
  const credit4 = Number((await rt.api('GET', `/api/customers/${c4.id}`, { token: A })).json.creditBalance);
  rec('TIL-001', 'Summary tiles over my filter: count 4, totalOutstanding 400, totalCreditBalance = c4 credit, missing Success 2, missing Collection 2',
    t1.status === 200 && t1.json.count === 4 && Number(t1.json.totalOutstanding) === 400 && Number(t1.json.totalCreditBalance) === credit4 && credit4 > 0
    && t1.json.missingSuccessPocCount === 2 && t1.json.missingCollectionPocCount === 2, { tiles: t1.json, c4credit: credit4 });
  const t2 = await tiles(`name:contains:${TAG}`, 'collectionPocUserId:isEmpty:');
  const t3 = await tiles(`name:contains:${TAG}`, `name:eq:nobody-${TAG}`);
  const tBad = await tiles('bogus:eq:1');
  rec('TIL-002', 'Tiles follow extra filter (collection isEmpty -> count 2, outstanding 400); empty result -> zeros; bad filter -> 400',
    t2.json?.count === 2 && Number(t2.json?.totalOutstanding) === 400 && t2.json?.missingCollectionPocCount === 2
    && t3.json?.count === 0 && Number(t3.json?.totalOutstanding) === 0 && Number(t3.json?.totalCreditBalance) === 0 && t3.json?.missingSuccessPocCount === 0 && tBad.status === 400,
    { collectionIsEmpty: t2.json, empty: t3.json, bad: tBad.status });

  // pocMissing flag semantics vs doc ("no seat of either kind")
  const lst = await rt.api('GET', `/api/customers?size=10&sort=name,asc&${F}`, { token: A });
  const flags = Object.fromEntries((lst.json?.content || []).map((c) => [c.name.replace(TAG + ' ', ''), c.pocMissing]));
  rec('POC-020', 'pocMissing flag matches its documented meaning (CustomerDtos: "no POC seat of either kind") — alpha2 has collection seats only',
    flags.alpha2 === false && flags.bravo === true && flags.bycashier === false && flags.delta === false,
    { flags, note: 'CustomerService.java:171 computes success.isEmpty() || collection.isEmpty()' });

  // ---------------- bulk ADD_POC ----------------
  const bulk = (body, tok = A) => rt.api('POST', '/api/customers/bulk', { token: tok, body });
  const bk = await bulk({ action: 'ADD_POC', ids: [c1.id, c2.id, c3.id], params: { userId: col1.id, pocType: 'COLLECTION', primary: 'false' } });
  const c2p = (await pocs(c2.id)).json; const c3p = (await pocs(c3.id)).json;
  rec('BLK-001', 'Bulk ADD_POC: per-record result — c1 (already holds col1) skipped with reason, c2/c3 succeeded; seats created (first seat primary)',
    bk.status === 200 && bk.json.requested === 3 && bk.json.succeeded.length === 2 && bk.json.succeeded.includes(c2.id) && bk.json.succeeded.includes(c3.id)
    && bk.json.skipped.length === 1 && bk.json.skipped[0].id === c1.id && /already/.test(bk.json.skipped[0].reason) && bk.json.failed.length === 0
    && c2p.some((p) => p.pocType === 'COLLECTION' && p.user.id === col1.id && p.primary) && c3p.some((p) => p.pocType === 'COLLECTION' && p.user.id === col1.id),
    { result: bk.json });

  const bkAll = await bulk({ action: 'ADD_POC', selectAllMatchingFilter: true, filters: [`name:contains:${TAG}`, 'successPocUserId:isEmpty:'], params: { userId: cs3.id, pocType: 'SUCCESS', primary: true } });
  const tAfter = await tiles(`name:contains:${TAG}`);
  rec('BLK-002', 'Bulk ADD_POC selectAllMatchingFilter applies to the whole filtered set (2 customers missing Success) and tiles update (missingSuccess 0)',
    bkAll.status === 200 && bkAll.json.requested === 2 && bkAll.json.succeeded.length === 2 && tAfter.json.missingSuccessPocCount === 0, { result: bkAll.json, tilesAfter: tAfter.json });

  const bFoo = await bulk({ action: 'ADD_POC', ids: [c1.id], params: { userId: cs1.id, pocType: 'foo' } });
  const bAbc = await bulk({ action: 'ADD_POC', ids: [c1.id], params: { userId: 'abc', pocType: 'SUCCESS' } });
  rec('BLK-003', 'Bulk ADD_POC with invalid params -> clean 400 (pocType=foo, userId=abc), not 500', bFoo.status === 400 && bAbc.status === 400,
    { pocTypeFoo: snip(bFoo), userIdAbc: snip(bAbc) });

  const bSales = await bulk({ action: 'ADD_POC', ids: [c1.id, c2.id], params: { userId: sales.id, pocType: 'SALES' } });
  const bInactive = await bulk({ action: 'ADD_POC', ids: [c1.id, c2.id], params: { userId: csInactive.id, pocType: 'SUCCESS' } });
  rec('BLK-004', 'Bulk ADD_POC with an invalid request (pocType SALES / inactive user) rejected once with 400 rather than every row "skipped"',
    bSales.status === 400 && bInactive.status === 400, { sales: snip(bSales), inactive: snip(bInactive) });

  const bUnknown = await bulk({ action: 'DELETE_ALL', ids: [c1.id] });
  const bNoIds = await bulk({ action: 'ADD_POC', params: { userId: cs1.id, pocType: 'SUCCESS' } });
  const bNoParams = await bulk({ action: 'ADD_POC', ids: [c1.id] });
  rec('BLK-005', 'Bulk: unknown action 400, no ids and no selectAll 400, missing params 400', bUnknown.status === 400 && bNoIds.status === 400 && bNoParams.status === 400,
    { unknown: snip(bUnknown), noIds: snip(bNoIds), noParams: snip(bNoParams) });

  // role with CUSTOMER_MANAGE but no POC_ASSIGN
  const role2 = await rt.api('POST', '/api/roles', { token: A, body: { name: rt.uniq('CPNOASSIGN').toUpperCase(), description: 'rt', privileges: ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'POC_VIEW'] } });
  const nu = await rt.api('POST', '/api/users', { token: A, body: { username: rt.uniq('cpnoassign'), email: 'y@rt.local', fullName: 'No Assign', password: P, roleId: role2.json?.id, active: true } });
  const nTok = await rt.login(nu.json.username, P);
  const bNo = await bulk({ action: 'ADD_POC', ids: [c2.id], params: { userId: cs1.id, pocType: 'SUCCESS' } }, nTok);
  const c2After = (await pocs(c2.id)).json;
  rec('BLK-006', 'Bulk ADD_POC by a role with CUSTOMER_MANAGE but without POC_ASSIGN is refused (expect 403) and changes nothing',
    bNo.status === 403 && !c2After.some((p) => p.user.id === cs1.id && p.pocType === 'SUCCESS'),
    { status: snip(bNo), seatCreated: c2After.some((p) => p.user.id === cs1.id && p.pocType === 'SUCCESS') });

  // bulk with an out-of-scope/nonexistent id
  const bGhost = await bulk({ action: 'ADD_POC', ids: [c2.id, 99999999], params: { userId: cs2.id, pocType: 'SUCCESS' } });
  const accounted = bGhost.json ? bGhost.json.succeeded.length + bGhost.json.failed.length + bGhost.json.skipped.length : -1;
  rec('BLK-007', 'Bulk with a nonexistent id: every requested id accounted for (succeeded/failed/skipped) — AC-D5 "never silently drops rows"',
    bGhost.status === 200 && accounted === 2, { result: bGhost.json, accountedFor: accounted, requestedIds: 2 });

  // ---------------- export ----------------
  const ex = await rt.api('POST', '/api/customers/export', { token: A, body: { action: 'EXPORT', ids: [c1.id, c4.id] } });
  const lines = (ex.text || '').trim().split(/\r?\n/);
  rec('EXP-001', 'Customer export CSV: text/csv, header, only selected ids, POC columns list usernames with (primary)',
    ex.status === 200 && /text\/csv/.test(ex.headers['content-type']) && lines[0].startsWith('Id,Name,Phone,Email,Credit balance,Outstanding,Customer Success POCs,Collection POCs')
    && lines.length === 3 && lines.some((l) => l.includes(`${TAG} delta`) && l.includes(`${cs2.username} (primary)`)) && lines.some((l) => l.includes(`${TAG} alpha2`)),
    { status: ex.status, ct: ex.headers['content-type'], csv: ex.text.slice(0, 700) });
  const exAll = await rt.api('POST', '/api/customers/export', { token: A, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`name:contains:${TAG}`], sort: 'name,desc' } });
  const exLines = (exAll.text || '').trim().split(/\r?\n/).slice(1);
  const exNoIds = await rt.api('POST', '/api/customers/export', { token: A, body: { action: 'EXPORT' } });
  const exCust = await rt.api('POST', '/api/customers/export', { token: custTok, body: { action: 'EXPORT', selectAllMatchingFilter: true } });
  rec('EXP-002', 'Export selectAllMatchingFilter with filter+sort returns exactly my 4 rows in sort order; no ids -> 400; customer login (no EXPORT_DATA) -> 403',
    exAll.status === 200 && exLines.length === 4 && exLines[0].includes(`${TAG} delta`) && exLines[3].includes(`${TAG} alpha2`) && exNoIds.status === 400 && exCust.status === 403,
    { rows: exLines.map((l) => l.slice(0, 60)), noIds: exNoIds.status, customer: exCust.status });

  // ---------------- products ----------------
  const pCreate = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-b`, description: 'desc b', price: 25.5 } });
  rec('PRD-001', 'Create product: 200, active defaults to true, price/description stored', pCreate.status === 200 && pCreate.json.active === true && Number(pCreate.json.price) === 25.5 && pCreate.json.description === 'desc b', snip(pCreate));
  const pZero = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-a`, price: 0, active: false } });
  const prNeg = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-neg`, price: -0.01 } });
  const pNoPrice = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-nop` } });
  const pNoName = await rt.api('POST', '/api/products', { token: A, body: { name: ' ', price: 1 } });
  const pStrPrice = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-str`, price: 'abc' } });
  rec('PRD-002', 'Product validation: price 0 allowed (active:false honoured); negative / missing price and blank name -> 400 fieldErrors; non-numeric price -> 400',
    pZero.status === 200 && pZero.json.active === false && Number(pZero.json.price) === 0 && prNeg.status === 400 && !!prNeg.json?.fieldErrors?.price && pNoPrice.status === 400 && !!pNoPrice.json?.fieldErrors?.price
    && pNoName.status === 400 && !!pNoName.json?.fieldErrors?.name && pStrPrice.status === 400,
    { zero: snip(pZero), negative: snip(prNeg), noPrice: pNoPrice.status, blankName: pNoName.status, stringPrice: snip(pStrPrice) });

  const pUpd = await rt.api('PUT', `/api/products/${pCreate.json.id}`, { token: A, body: { name: `${TAG} P-c`, description: 'desc c', price: 99.99 } });
  const pGet = await rt.api('GET', `/api/products/${pCreate.json.id}`, { token: A });
  const pUpdNeg = await rt.api('PUT', `/api/products/${pCreate.json.id}`, { token: A, body: { name: `${TAG} P-c`, price: -5 } });
  const pUpd404 = await rt.api('PUT', `/api/products/99999999`, { token: A, body: { name: 'x', price: 1 } });
  const pAud = await rt.api('GET', `/api/audit?entityType=PRODUCT&entityId=${pCreate.json.id}`, { token: A });
  rec('PRD-003', 'Update product persists name/description/price; omitted active keeps it; negative price 400; unknown id 404; audited',
    pUpd.status === 200 && pGet.json.name === `${TAG} P-c` && Number(pGet.json.price) === 99.99 && pGet.json.active === true && pUpdNeg.status === 400 && pUpd404.status === 404
    && (pAud.json || []).map((e) => e.action).includes('PRODUCT_UPDATED'),
    { get: pGet.json, negative: pUpdNeg.status, unknown: pUpd404.status, audit: (pAud.json || []).map((e) => e.action) });

  const pD = await rt.api('POST', '/api/products', { token: A, body: { name: `${TAG} P-d`, price: 10 } });
  const PF = `filter=${enc('name:contains:' + TAG + ' P-')}`;
  const pl1 = await rt.api('GET', `/api/products?size=10&sort=price,desc&${PF}`, { token: A });
  const pl2 = await rt.api('GET', `/api/products?size=10&sort=name,asc&${PF}&filter=${enc('active:eq:true')}`, { token: A });
  const pl3 = await rt.api('GET', `/api/products?size=10&${PF}&filter=${enc('price:between:5,50')}`, { token: A });
  const pl4 = await rt.api('GET', `/api/products?sort=description,asc`, { token: A });
  const pl5 = await rt.api('GET', `/api/products?filter=${enc('active:contains:x')}`, { token: A });
  rec('PRD-004', 'Product list: sort price desc, filter active:eq:true, price between; sort on description 400; bad operator 400',
    pl1.json?.content?.map((p) => Number(p.price)).join() === '99.99,10,0' && pl2.json?.content?.map((p) => p.name.slice(-3)).join() === 'P-c,P-d'
    && pl3.json?.content?.map((p) => p.name.slice(-3)).join() === 'P-d' && pl4.status === 400 && pl5.status === 400,
    { priceDesc: pl1.json?.content?.map((p) => p.price), activeTrue: pl2.json?.content?.map((p) => p.name.slice(-3)), between: pl3.json?.content?.map((p) => p.name.slice(-3)), sortDesc: pl4.status, badOp: pl5.status });

  const pb = await rt.api('POST', '/api/products/bulk', { token: A, body: { action: 'DEACTIVATE', ids: [pCreate.json.id, pZero.json.id] } });
  const pbGet = await rt.api('GET', `/api/products/${pCreate.json.id}`, { token: A });
  const pbAud = await rt.api('GET', `/api/audit?entityType=PRODUCT&entityId=${pCreate.json.id}`, { token: A });
  rec('PRD-005', 'Bulk DEACTIVATE: active one succeeds (now inactive, PRODUCT_DEACTIVATED audited), already-inactive one skipped "Already inactive"',
    pb.status === 200 && pb.json.succeeded.join() === String(pCreate.json.id) && pb.json.skipped.length === 1 && pb.json.skipped[0].id === pZero.json.id && /Already inactive/.test(pb.json.skipped[0].reason)
    && pbGet.json.active === false && (pbAud.json || []).some((e) => e.action === 'PRODUCT_DEACTIVATED'), { result: pb.json, activeAfter: pbGet.json?.active });
  const pa = await rt.api('POST', '/api/products/bulk', { token: A, body: { action: 'ACTIVATE', selectAllMatchingFilter: true, filters: [`name:contains:${TAG} P-`, 'active:eq:false'] } });
  const pAfter = await rt.api('GET', `/api/products?size=10&${PF}&filter=${enc('active:eq:false')}`, { token: A });
  const pBadAct = await rt.api('POST', '/api/products/bulk', { token: A, body: { action: 'DELETE', ids: [pD.json.id] } });
  rec('PRD-006', 'Bulk ACTIVATE selectAllMatchingFilter (my inactive products) activates both; none left inactive; unknown action 400',
    pa.status === 200 && pa.json.requested === 2 && pa.json.succeeded.length === 2 && pAfter.json.totalElements === 0 && pBadAct.status === 400, { result: pa.json, inactiveLeft: pAfter.json?.totalElements, badAction: pBadAct.status });

  const pex = await rt.api('POST', '/api/products/export', { token: A, body: { action: 'EXPORT', selectAllMatchingFilter: true, filters: [`name:contains:${TAG} P-`], sort: 'price,desc' } });
  const pexLines = (pex.text || '').trim().split(/\r?\n/);
  rec('PRD-007', 'Product export CSV: header Id,Name,Description,Price,Active; my 3 products; honours the requested sort (price desc)',
    pex.status === 200 && pexLines[0] === 'Id,Name,Description,Price,Active' && pexLines.length === 4 && pexLines[1].includes('P-c') && pexLines[3].includes('P-a'),
    { csv: pex.text.slice(0, 400) });

  const cashP = await rt.api('POST', '/api/products', { token: cashier, body: { name: `${TAG} P-cash`, price: 1 } });
  const cashL = await rt.api('GET', `/api/products?${PF}`, { token: cashier });
  const cashB = await rt.api('POST', '/api/products/bulk', { token: cashier, body: { action: 'DEACTIVATE', ids: [pD.json.id] } });
  const colL = await rt.api('GET', `/api/products`, { token: await rt.login(col1.username, P) });
  rec('PRD-008', 'Product permissions: CASHIER can list (200) but not create/bulk (403); COLLECTION_POC (no PRODUCT_VIEW) list 403',
    cashP.status === 403 && cashL.status === 200 && cashB.status === 403 && colL.status === 403, { cashierCreate: cashP.status, cashierList: cashL.status, cashierBulk: cashB.status, collectionList: colL.status });

  // inactive product and invoices (backend)
  await rt.api('PUT', `/api/products/${pD.json.id}`, { token: A, body: { name: `${TAG} P-d`, price: 10, active: false } });
  const invInactive = await rt.api('POST', '/api/invoices', { token: A, body: { customerId: c2.id, salesPocUserId: sales.id, items: [{ productId: pD.json.id, quantity: 1 }] } });
  rec('PRD-009', 'Backend: invoice with an INACTIVE product is rejected (400)', invInactive.status === 400,
    { status: snip(invInactive), note: 'InvoiceService.create (InvoiceService.java:70) loads the product with findById and never checks active' });
  if (invInactive.status === 200) {
    await rt.api('POST', `/api/invoices/${invInactive.json.id}/cancel`, { token: A, body: { reason: 'rt cleanup' } });
  }

  const out = { TAG, ids: { c1: c1.id, c2: c2.id, c3: c3.id, c4: c4.id, cs1: cs1.id, cs2: cs2.id, cs3: cs3.id, col1: col1.id, col2: col2.id, prodInactive: pD.json.id, prodActive: pCreate.json.id },
    users: { cs1: cs1.username, cs2: cs2.username, cs3: cs3.username, col1: col1.username, col2: col2.username }, results };
  fs.writeFileSync(path.join(__dirname, 'api-results.json'), JSON.stringify(out, null, 2));
  console.log('\nTAG', TAG, 'pass', results.filter((x) => x.status === 'PASS').length, 'fail', results.filter((x) => x.status === 'FAIL').length);
}
main().catch((e) => { console.error('CRASH', e); process.exit(1); });
