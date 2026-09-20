// Area F / customers — CRUD, credit balance, POC seats, list & tiles.
const { rt, recorder, money, eq, admin, createProduct, createInvoice, recordPayment } = require('./_h.js');

(async () => {
  const r = recorder('03-customers');
  const { token, id: adminId } = await admin();
  const prod = await createProduct(token, 'f-cust', '60.00');

  // ---- F-25 create -------------------------------------------------------------
  let cust;
  await r.check({
    id: 'F-25', feature: 'Customer create', kind: 'API',
    title: 'Creating a customer still stores every pre-existing field and its self-service login',
    steps: 'POST /api/customers {name, phone, email, address, username, password}',
    expected: '200 with the fields echoed, creditBalance 0.00, outstanding 0.00, username set; the login works',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:180',
  }, async () => {
    const name = rt.uniq('f-cust');
    const res = await rt.api('POST', '/api/customers', { token,
      body: { name, phone: '555-0101', email: `${name}@rt.local`, address: '2 Test Way',
        username: name, password: rt.PASSWORD } });
    cust = { ...res.json, username: name, password: rt.PASSWORD };
    const loginOk = await rt.login(name, rt.PASSWORD).then(() => true).catch(() => false);
    const ok = res.status === 200 && res.json.name === name && res.json.phone === '555-0101'
      && res.json.address === '2 Test Way' && eq(res.json.creditBalance, 0)
      && eq(res.json.outstanding, 0) && res.json.username === name && loginOk;
    return { ok, severity: 'high',
      actual: `${res.status} name=${res.json?.name} phone=${res.json?.phone} credit=${res.json?.creditBalance} outstanding=${res.json?.outstanding} loginWorks=${loginOk}` };
  });

  // ---- F-26 edit ---------------------------------------------------------------
  await r.check({
    id: 'F-26', feature: 'Customer edit', kind: 'API',
    title: 'Editing a customer saves the changed fields and leaves credit and outstanding alone',
    steps: 'PUT /api/customers/{id} with a new name, phone, email and address',
    expected: '200 with the new values; creditBalance unchanged',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:220',
  }, async () => {
    const name = cust.name + '-edited';
    const res = await rt.api('PUT', `/api/customers/${cust.id}`, { token,
      body: { name, phone: '555-0999', email: `${cust.name}b@rt.local`, address: '3 Changed Road' } });
    const g = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const ok = res.status === 200 && g.name === name && g.phone === '555-0999'
      && g.address === '3 Changed Road' && eq(g.creditBalance, 0);
    cust.name = name;
    return { ok, actual: `${res.status} name=${g.name} phone=${g.phone} address=${g.address} credit=${g.creditBalance}` };
  });

  await r.check({
    id: 'F-27', feature: 'Customer validation', kind: 'API',
    title: 'Customer create still rejects a blank name and a malformed email',
    steps: 'POST /api/customers with name:"" and with email:"not-an-email"',
    expected: '400 with field errors, no row created',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerDtos.java:33',
  }, async () => {
    const u = rt.uniq('f-bad');
    const blank = await rt.api('POST', '/api/customers', { token,
      body: { name: '', username: u, password: rt.PASSWORD } });
    const mail = await rt.api('POST', '/api/customers', { token,
      body: { name: u, email: 'not-an-email', username: u, password: rt.PASSWORD } });
    return { ok: blank.status === 400 && mail.status === 400,
      actual: `blankName=${blank.status}, badEmail=${mail.status} ${mail.text.slice(0, 120)}` };
  });

  // ---- F-28 credit balance on the detail --------------------------------------
  await r.check({
    id: 'F-28', feature: 'Customer credit balance', kind: 'API',
    title: 'The customer detail reports credit balance and outstanding from live invoice data',
    steps: 'raise a 120 invoice, pay 200; read GET /api/customers/{id}',
    expected: 'outstanding 0.00 and creditBalance 80.00',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:404',
  }, async () => {
    await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
      items: [{ productId: prod.id, quantity: 2, unitPrice: '60.00' }] });
    const mid = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    await recordPayment(token, { customerId: cust.id, amount: '200.00', method: 'CASH',
      collectionPocUserId: adminId });
    const end = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    return { ok: eq(mid.outstanding, 120) && eq(end.outstanding, 0) && eq(end.creditBalance, 80),
      severity: 'high',
      actual: `after invoice outstanding=${mid.outstanding}; after payment outstanding=${end.outstanding} credit=${end.creditBalance}` };
  });

  // ---- F-29..F-31 POC seats ----------------------------------------------------
  const success = await rt.createStaff(token, 'CUSTOMER_SUCCESS_POC', 'f');
  const collection = await rt.createStaff(token, 'COLLECTION_POC', 'f');
  const collection2 = await rt.createStaff(token, 'COLLECTION_POC', 'f');
  let seats = [];
  await r.check({
    id: 'F-29', feature: 'Customer POC seats', kind: 'API',
    title: 'Success and Collection POCs can still be assigned to a customer',
    steps: 'POST /api/customers/{id}/pocs for SUCCESS and COLLECTION; GET /api/customers/{id}/pocs',
    expected: 'both seats come back with the right pocType and user; the customer DTO lists them under successPocs / collectionPocs and pocMissing goes false',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerController.java:98',
  }, async () => {
    const a = await rt.api('POST', `/api/customers/${cust.id}/pocs`, { token,
      body: { pocType: 'SUCCESS', userId: success.id, primary: true } });
    const b = await rt.api('POST', `/api/customers/${cust.id}/pocs`, { token,
      body: { pocType: 'COLLECTION', userId: collection.id, primary: true } });
    seats = (await rt.api('GET', `/api/customers/${cust.id}/pocs`, { token })).json;
    const d = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const ok = a.status === 200 && b.status === 200 && seats.length === 2
      && d.successPocs.length === 1 && d.successPocs[0].user.id === success.id
      && d.collectionPocs.length === 1 && d.collectionPocs[0].user.id === collection.id
      && d.pocMissing === false;
    return { ok, actual: `add=${a.status}/${b.status} seats=${seats.length} success=${d.successPocs?.length} collection=${d.collectionPocs?.length} pocMissing=${d.pocMissing}` };
  });

  await r.check({
    id: 'F-30', feature: 'Customer POC seats', kind: 'API',
    title: 'A second Collection POC can be added and made primary; the previous one stops being primary',
    steps: 'POST a second COLLECTION seat, then POST /pocs/{seatId}/primary on it',
    expected: 'exactly one COLLECTION seat is primary, and it is the new one',
    codeRef: 'backend/src/main/java/com/geneinvoice/poc/PocService.java:120',
  }, async () => {
    const add = await rt.api('POST', `/api/customers/${cust.id}/pocs`, { token,
      body: { pocType: 'COLLECTION', userId: collection2.id, primary: false } });
    const p = await rt.api('POST', `/api/customers/${cust.id}/pocs/${add.json.id}/primary`, { token });
    const all = (await rt.api('GET', `/api/customers/${cust.id}/pocs`, { token })).json;
    const coll = all.filter((s) => s.pocType === 'COLLECTION');
    const primaries = coll.filter((s) => s.primary);
    return { ok: add.status === 200 && p.status === 200 && coll.length === 2
        && primaries.length === 1 && primaries[0].user.id === collection2.id,
      actual: `add=${add.status} primary=${p.status} collectionSeats=${coll.length} primaries=${primaries.length} primaryUser=${primaries[0]?.user?.id} expected=${collection2.id}` };
  });

  await r.check({
    id: 'F-31', feature: 'Customer POC seats', kind: 'API',
    title: 'A POC seat can still be removed, and a user with no assignability marker is refused',
    steps: 'DELETE /api/customers/{id}/pocs/{seatId}; POST a SUCCESS seat for a COLLECTION_POC user',
    expected: 'the seat is gone; the mismatched assignment is refused with a 400',
    codeRef: 'backend/src/main/java/com/geneinvoice/poc/PocService.java:60',
  }, async () => {
    const before = (await rt.api('GET', `/api/customers/${cust.id}/pocs`, { token })).json;
    const seat = before.find((s) => s.pocType === 'COLLECTION' && s.user.id === collection.id);
    const del = await rt.api('DELETE', `/api/customers/${cust.id}/pocs/${seat.id}`, { token });
    const after = (await rt.api('GET', `/api/customers/${cust.id}/pocs`, { token })).json;
    const wrong = await rt.api('POST', `/api/customers/${cust.id}/pocs`, { token,
      body: { pocType: 'SUCCESS', userId: collection2.id } });
    return { ok: del.status < 300 && after.length === before.length - 1 && wrong.status >= 400 && wrong.status < 500,
      actual: `delete=${del.status} seats ${before.length} -> ${after.length}; mismatched assign=${wrong.status} ${wrong.text.slice(0, 100)}` };
  });

  // ---- F-32/F-33 list & tiles ---------------------------------------------------
  await r.check({
    id: 'F-32', feature: 'Customer list', kind: 'API',
    title: 'Customers list still paginates, sorts and filters server-side on the pre-existing columns',
    steps: 'GET /api/customers?size=10&sort=name,asc and sort=id,desc; filter=name:contains:<mine>; filter=creditBalance:gt:0',
    expected: 'names ascending (database collation, punctuation-insensitive) and ids descending; the contains filter finds exactly my customer; creditBalance>0 returns only customers in credit',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:136',
  }, async () => {
    const page = await rt.api('GET', '/api/customers?size=10&page=0&sort=name,asc', { token });
    // Postgres's collation ignores punctuation at the primary level, so compare the same way.
    const key = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
    const names = page.json.content.map((c) => key(c.name));
    const ordered = names.every((v, i) => i === 0 || names[i - 1] <= v);
    const byId = await rt.api('GET', '/api/customers?size=10&page=0&sort=id,desc', { token });
    const ids = byId.json.content.map((c) => c.id);
    const idOrdered = ids.every((v, i) => i === 0 || ids[i - 1] > v);
    const mine = await rt.api(
      'GET', `/api/customers?size=10&filter=name:contains:${encodeURIComponent(cust.name)}`, { token });
    const credit = await rt.api('GET', '/api/customers?size=50&filter=creditBalance:gt:0', { token });
    const ok = page.status === 200 && ordered && idOrdered && page.json.size === 10
      && mine.json.content.length === 1 && mine.json.content[0].id === cust.id
      && credit.json.content.every((c) => Number(c.creditBalance) > 0)
      && credit.json.content.some((c) => c.id === cust.id);
    return { ok, actual: `nameOrdered=${ordered} idOrdered=${idOrdered} size=${page.json.size} mineRows=${mine.json.content.length} creditRows=${credit.json.content.length} minePresent=${credit.json.content.some((c) => c.id === cust.id)}` };
  });

  await r.check({
    id: 'F-33', feature: 'Customer list tiles', kind: 'API',
    title: 'Customer tiles are computed server-side over the filtered set',
    steps: 'GET /api/customers/summary?filter=name:contains:<mine>',
    expected: 'count 1; totalCreditBalance and totalOutstanding equal my customer; the missing-POC counts are 0 now both seats exist',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:84',
  }, async () => {
    const t = (await rt.api('GET',
      `/api/customers/summary?filter=name:contains:${encodeURIComponent(cust.name)}`, { token })).json;
    const c = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json;
    const ok = t.count === 1 && eq(t.totalCreditBalance, c.creditBalance)
      && eq(t.totalOutstanding, c.outstanding)
      && t.missingSuccessPocCount === 0 && t.missingCollectionPocCount === 0;
    return { ok, actual: `tiles=${JSON.stringify(t)} customer credit=${c.creditBalance} outstanding=${c.outstanding}` };
  });

  // ---- F-34 delete ---------------------------------------------------------------
  await r.check({
    id: 'F-34', feature: 'Customer delete', kind: 'API',
    title: 'Deleting a customer removes it and its self-service login',
    steps: 'create a throwaway customer; DELETE /api/customers/{id}; GET it back; try its login',
    expected: '2xx on delete, 404 on the read, the login no longer authenticates',
    codeRef: 'backend/src/main/java/com/geneinvoice/customer/CustomerService.java:262',
  }, async () => {
    const doomed = await rt.createCustomer(token, 'f');
    const del = await rt.api('DELETE', `/api/customers/${doomed.id}`, { token });
    const get = await rt.api('GET', `/api/customers/${doomed.id}`, { token });
    const login = await rt.api('POST', '/api/auth/login', {
      body: { username: doomed.username, password: doomed.password } });
    return { ok: del.status < 300 && get.status === 404 && login.status >= 400,
      actual: `delete=${del.status} get=${get.status} login=${login.status}` };
  });

  r.save();
})();
