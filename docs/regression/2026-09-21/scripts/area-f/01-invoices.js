// Area F / invoices — the pre-existing invoice behaviour, after the due-date work.
const { rt, recorder, money, eq, admin, createProduct, createInvoice } = require('./_h.js');

(async () => {
  const r = recorder('01-invoices');
  const { token, id: adminId } = await admin();
  const cust = await rt.createCustomer(token, 'f');
  const pA = await createProduct(token, 'f-pA', '10.00');
  const pB = await createProduct(token, 'f-pB', '25.50');
  const pC = await createProduct(token, 'f-pC', '3.33');

  // ---- F-01 multi-line create + arithmetic ------------------------------------
  let inv;
  await r.check({
    id: 'F-01', feature: 'Invoice create', kind: 'API',
    title: 'Invoice with several line items still saves every line',
    steps: 'POST /api/invoices with 3 items (2x10.00, 3x25.50, 7x3.33)',
    expected: '3 lines returned, each with the product name, quantity, unit price and line total',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:63',
  }, async () => {
    inv = await createInvoice(token, {
      customerId: cust.id, salesPocUserId: adminId,
      items: [
        { productId: pA.id, quantity: 2, unitPrice: '10.00' },
        { productId: pB.id, quantity: 3, unitPrice: '25.50' },
        { productId: pC.id, quantity: 7, unitPrice: '3.33' },
      ],
    });
    const ok = inv.items.length === 3
      && eq(inv.items[0].lineTotal, 20) && eq(inv.items[1].lineTotal, 76.5) && eq(inv.items[2].lineTotal, 23.31)
      && inv.items.every((i) => i.productName);
    return { ok, actual: `lines=${inv.items.length} totals=${inv.items.map((i) => i.lineTotal).join('/')}` };
  });

  await r.check({
    id: 'F-02', feature: 'Invoice create', kind: 'API',
    title: 'Invoice total is the sum of its line totals, with paid 0 and balance = total',
    steps: 'read the created invoice',
    expected: 'total 119.81, paidAmount 0.00, balance 119.81, status UNPAID',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:89',
  }, async () => {
    const ok = eq(inv.total, 119.81) && eq(inv.paidAmount, 0) && eq(inv.balance, 119.81)
      && inv.status === 'UNPAID';
    return { ok, severity: 'high', actual: `total=${inv.total} paid=${inv.paidAmount} balance=${inv.balance} status=${inv.status}` };
  });

  await r.check({
    id: 'F-03', feature: 'Invoice detail', kind: 'API', ac: 'AC-A3',
    title: 'GET /api/invoices/{id} returns the same invoice, with the pre-existing fields intact',
    steps: 'GET /api/invoices/{id}',
    expected: 'invoiceNumber, customerId/Name, invoiceDate, items, total, status, salesPoc all present and equal to the create response',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:80',
  }, async () => {
    const g = await rt.api('GET', `/api/invoices/${inv.id}`, { token });
    const d = g.json || {};
    const ok = g.status === 200 && d.invoiceNumber === inv.invoiceNumber
      && d.customerId === cust.id && d.customerName === cust.name
      && d.invoiceDate === inv.invoiceDate && eq(d.total, inv.total)
      && d.items.length === 3 && d.salesPoc && d.salesPoc.id === adminId
      && d.status === 'UNPAID';
    return { ok, actual: `status=${g.status} number=${d.invoiceNumber} items=${d.items?.length} poc=${d.salesPoc?.id}` };
  });

  // ---- F-04 invoice number sequence under concurrency (D-05) -------------------
  let numbers = [];
  await r.check({
    id: 'F-04', feature: 'Invoice number sequence', kind: 'API',
    title: 'Twelve invoices created concurrently get twelve distinct numbers (regression of D-05)',
    steps: 'POST /api/invoices x12 in parallel for one customer',
    expected: 'all 12 succeed and every invoiceNumber is unique',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceNumbers.java:44',
  }, async () => {
    const results = await Promise.all(Array.from({ length: 12 }, () => rt.api('POST', '/api/invoices', {
      token,
      body: { customerId: cust.id, salesPocUserId: adminId,
        items: [{ productId: pA.id, quantity: 1, unitPrice: '1.00' }] },
    })));
    const created = results.filter((x) => x.status < 300);
    numbers = created.map((x) => x.json.invoiceNumber);
    const uniques = new Set(numbers);
    const ok = created.length === 12 && uniques.size === 12;
    return { ok, severity: 'high',
      actual: `created=${created.length}/12 distinct=${uniques.size} failures=${results.filter((x) => x.status >= 300).map((x) => x.status + ':' + x.text.slice(0, 120)).join(' | ')}` };
  });

  await r.check({
    id: 'F-05', feature: 'Invoice number sequence', kind: 'API',
    title: 'Invoice numbers keep the INV-yyyyMMdd-NNNN shape and increase',
    steps: 'inspect the 12 numbers from F-04',
    expected: 'every number matches /^INV-\\d{8}-\\d{4}$/, all of the same day, strictly increasing when sorted',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceNumbers.java:24',
  }, async () => {
    const shaped = numbers.filter((n) => /^INV-\d{8}-\d{4}$/.test(n));
    const seqs = numbers.map((n) => Number(n.slice(-4))).sort((a, b) => a - b);
    const contiguous = seqs.every((v, i) => i === 0 || v === seqs[i - 1] + 1);
    return { ok: shaped.length === numbers.length && contiguous,
      actual: `wellFormed=${shaped.length}/${numbers.length} seq=${seqs.join(',')} contiguous=${contiguous}` };
  });

  // ---- F-06 cancel --------------------------------------------------------------
  let toCancel;
  await r.check({
    id: 'F-06', feature: 'Invoice cancel', kind: 'API',
    title: 'Cancelling an unpaid invoice sets CANCELLED and drops it out of the customer outstanding',
    steps: 'POST /api/invoices/{id}/cancel on a fresh unpaid invoice; read the customer',
    expected: 'status CANCELLED; the invoice balance no longer counts towards customer.outstanding',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:196',
  }, async () => {
    const before = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.outstanding;
    toCancel = await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
      items: [{ productId: pB.id, quantity: 4, unitPrice: '25.50' }] });
    const c = await rt.api('POST', `/api/invoices/${toCancel.id}/cancel`, { token });
    const after = (await rt.api('GET', `/api/customers/${cust.id}`, { token })).json.outstanding;
    const ok = c.status === 200 && c.json.status === 'CANCELLED' && eq(before, after);
    return { ok, actual: `cancel=${c.status} status=${c.json?.status} outstanding ${before} -> ${after} (invoice total ${toCancel.total})` };
  });

  await r.check({
    id: 'F-07', feature: 'Invoice cancel', kind: 'API',
    title: 'Cancelling an already-cancelled invoice is refused with a 400',
    steps: 'POST /api/invoices/{id}/cancel twice',
    expected: '400 "Invoice already cancelled"',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:199',
  }, async () => {
    const c = await rt.api('POST', `/api/invoices/${toCancel.id}/cancel`, { token });
    return { ok: c.status === 400 && /already cancelled/i.test(c.text), severity: 'low',
      actual: `${c.status} ${c.text.slice(0, 160)}` };
  });

  // ---- F-08..F-11 list: pagination, sorting, filtering on pre-existing columns ---
  await r.check({
    id: 'F-08', feature: 'Invoice list', kind: 'API',
    title: 'Server-side pagination on the invoice list: page 0 and page 1 are disjoint and add up',
    steps: 'GET /api/invoices?customerId=..&size=10&page=0 then page=1, sorted by id asc',
    expected: 'no id appears on both pages; totalElements equals the number of invoices for the customer; totalPages = ceil(total/size)',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableQuery.java:22',
  }, async () => {
    const p0 = await rt.api('GET', `/api/invoices?customerId=${cust.id}&size=10&page=0&sort=id,asc`, { token });
    const p1 = await rt.api('GET', `/api/invoices?customerId=${cust.id}&size=10&page=1&sort=id,asc`, { token });
    const ids0 = p0.json.content.map((i) => i.id), ids1 = p1.json.content.map((i) => i.id);
    const overlap = ids0.filter((i) => ids1.includes(i));
    const total = p0.json.totalElements;
    const ok = p0.status === 200 && p1.status === 200 && overlap.length === 0
      && ids0.length === 10 && ids0.length + ids1.length === Math.min(total, 20)
      && p0.json.totalPages === Math.ceil(total / 10);
    return { ok, actual: `total=${total} page0=${ids0.length} page1=${ids1.length} overlap=${overlap.length} totalPages=${p0.json.totalPages}` };
  });

  await r.check({
    id: 'F-09', feature: 'Invoice list', kind: 'API',
    title: 'Server-side sorting on pre-existing columns (total asc/desc, invoiceDate)',
    steps: 'GET /api/invoices?customerId=..&sort=total,desc and sort=total,asc and sort=invoiceDate,desc',
    expected: 'each result is ordered by the requested column; the two total orderings are reverses of one another',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableQuery.java:33',
  }, async () => {
    const desc = await rt.api('GET', `/api/invoices?customerId=${cust.id}&sort=total,desc&size=50`, { token });
    const asc = await rt.api('GET', `/api/invoices?customerId=${cust.id}&sort=total,asc&size=50`, { token });
    const dates = await rt.api('GET', `/api/invoices?customerId=${cust.id}&sort=invoiceDate,desc&size=50`, { token });
    const dv = desc.json.content.map((i) => Number(i.total));
    const av = asc.json.content.map((i) => Number(i.total));
    const tv = dates.json.content.map((i) => Date.parse(i.invoiceDate));
    const sortedDesc = dv.every((v, i) => i === 0 || dv[i - 1] >= v);
    const sortedAsc = av.every((v, i) => i === 0 || av[i - 1] <= v);
    const sortedDate = tv.every((v, i) => i === 0 || tv[i - 1] >= v);
    return { ok: sortedDesc && sortedAsc && sortedDate && desc.json.sort === 'total,desc',
      actual: `total desc ordered=${sortedDesc}, total asc ordered=${sortedAsc}, invoiceDate desc ordered=${sortedDate}, sort echo=${desc.json.sort}` };
  });

  await r.check({
    id: 'F-10', feature: 'Invoice list', kind: 'API',
    title: 'Server-side filtering on pre-existing columns (status, customerId, total gte, invoiceNumber contains)',
    steps: 'GET /api/invoices with filter=status:eq:CANCELLED, filter=total:gte:100, filter=invoiceNumber:contains:INV-',
    expected: 'each filter narrows the set correctly and appliedFilters echoes it back',
    codeRef: 'backend/src/main/java/com/geneinvoice/common/query/TableSchemas.java:47',
  }, async () => {
    const cancelled = await rt.api('GET', `/api/invoices?customerId=${cust.id}&filter=status:eq:CANCELLED&size=50`, { token });
    const big = await rt.api('GET', `/api/invoices?customerId=${cust.id}&filter=total:gte:100&size=50`, { token });
    const named = await rt.api('GET', `/api/invoices?customerId=${cust.id}&filter=invoiceNumber:contains:${inv.invoiceNumber}&size=50`, { token });
    const okCancelled = cancelled.json.content.every((i) => i.status === 'CANCELLED') && cancelled.json.content.length === 1;
    const okBig = big.json.content.every((i) => Number(i.total) >= 100) && big.json.content.length >= 1;
    const okNamed = named.json.content.length === 1 && named.json.content[0].id === inv.id;
    return { ok: okCancelled && okBig && okNamed,
      actual: `cancelled=${cancelled.json.content.length}(all CANCELLED=${okCancelled}) total>=100 -> ${big.json.content.length} rows (ok=${okBig}) numberContains -> ${named.json.content.length} rows, applied=${JSON.stringify(cancelled.json.appliedFilters)}` };
  });

  await r.check({
    id: 'F-11', feature: 'Invoice list tiles', kind: 'API', ac: 'AC-A7',
    title: 'Invoice summary tiles are computed server-side over the whole filtered set, not the page',
    steps: 'GET /api/invoices/summary?customerId=..; compare with the full list read at size=50',
    expected: 'count = totalElements; totalBilled = sum of every total (cancelled included, as before); totalPaid = sum of paidAmount; outstanding = sum of the live balances (cancelled counted as zero); per-status counts match the rows',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:330',
  }, async () => {
    const all = [];
    for (let p = 0; ; p++) {
      const res = await rt.api('GET', `/api/invoices?customerId=${cust.id}&size=50&page=${p}`, { token });
      all.push(...res.json.content);
      if (all.length >= res.json.totalElements || res.json.content.length === 0) break;
    }
    const s = (await rt.api('GET', `/api/invoices/summary?customerId=${cust.id}`, { token })).json;
    const billed = all.reduce((a, i) => a + Number(i.total), 0);
    const paid = all.reduce((a, i) => a + Number(i.paidAmount), 0);
    const live = all.filter((i) => i.status !== 'CANCELLED')
      .reduce((a, i) => a + Number(i.balance), 0);
    const counts = { UNPAID: 0, PARTIALLY_PAID: 0, FULLY_PAID: 0, CANCELLED: 0 };
    all.forEach((i) => counts[i.status]++);
    const ok = s.count === all.length && eq(s.totalBilled, billed) && eq(s.totalPaid, paid)
      && eq(s.outstanding, live)
      && s.unpaidCount === counts.UNPAID && s.cancelledCount === counts.CANCELLED
      && s.fullyPaidCount === counts.FULLY_PAID && s.partiallyPaidCount === counts.PARTIALLY_PAID;
    return { ok, severity: 'high',
      actual: `tiles count=${s.count}/${all.length} billed=${s.totalBilled}/${money(billed)} paid=${s.totalPaid}/${money(paid)} outstanding=${s.outstanding}/${money(live)} statuses tiles=${s.unpaidCount}/${s.partiallyPaidCount}/${s.fullyPaidCount}/${s.cancelledCount} vs list=${counts.UNPAID}/${counts.PARTIALLY_PAID}/${counts.FULLY_PAID}/${counts.CANCELLED}` };
  });

  await r.check({
    id: 'F-12', feature: 'Invoice list tiles', kind: 'API',
    title: 'Tiles follow the filter chips, not just the customer',
    steps: 'GET /api/invoices/summary?customerId=..&filter=status:eq:CANCELLED',
    expected: 'count = the cancelled count from the unfiltered tiles; cancelledCount = count',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceController.java:62',
  }, async () => {
    const all = (await rt.api('GET', `/api/invoices/summary?customerId=${cust.id}`, { token })).json;
    const filtered = (await rt.api('GET', `/api/invoices/summary?customerId=${cust.id}&filter=status:eq:CANCELLED`, { token })).json;
    return { ok: filtered.count === all.cancelledCount && filtered.cancelledCount === filtered.count && filtered.count >= 1,
      actual: `unfiltered cancelledCount=${all.cancelledCount}, filtered count=${filtered.count} cancelledCount=${filtered.cancelledCount}` };
  });

  // ---- F-13 validation still in place -----------------------------------------
  await r.check({
    id: 'F-13', feature: 'Invoice validation', kind: 'API',
    title: 'Invoice create still rejects an empty item list and an unknown product',
    steps: 'POST /api/invoices with items:[] and with a non-existent productId',
    expected: '400 for the empty list, 404/400 for the unknown product, neither creates a row',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceDtos.java:32',
  }, async () => {
    const empty = await rt.api('POST', '/api/invoices', { token,
      body: { customerId: cust.id, salesPocUserId: adminId, items: [] } });
    const bogus = await rt.api('POST', '/api/invoices', { token,
      body: { customerId: cust.id, salesPocUserId: adminId,
        items: [{ productId: 99999999, quantity: 1, unitPrice: '1.00' }] } });
    return { ok: empty.status === 400 && bogus.status >= 400 && bogus.status < 500,
      actual: `emptyItems=${empty.status}, unknownProduct=${bogus.status} ${bogus.text.slice(0, 100)}` };
  });

  await r.check({
    id: 'F-14', feature: 'Invoice update', kind: 'API', ac: 'AC-A3',
    title: 'The pre-existing inline edit (notes, Sales POC) still works on an invoice',
    steps: 'PATCH /api/invoices/{id} {notes}',
    expected: '200 and the notes are stored; nothing else moves (total, status, dueDate unchanged)',
    codeRef: 'backend/src/main/java/com/geneinvoice/invoice/InvoiceService.java:150',
  }, async () => {
    const note = 'area-f note ' + Date.now();
    const p = await rt.api('PATCH', `/api/invoices/${inv.id}`, { token, body: { notes: note } });
    const ok = p.status === 200 && p.json.notes === note && eq(p.json.total, inv.total)
      && p.json.status === inv.status && p.json.dueDate === inv.dueDate;
    return { ok, actual: `${p.status} notes="${p.json?.notes}" total=${p.json?.total} dueDate=${p.json?.dueDate} (was ${inv.dueDate})` };
  });

  r.save();
  // Hand the fixtures to the later scripts.
  require('fs').writeFileSync(require('path').join(__dirname, 'parts', 'fixtures.json'),
    JSON.stringify({ customerId: cust.id, customerName: cust.name, customerUser: cust.username,
      adminId, productA: pA.id, productB: pB.id, invoiceId: inv.id }, null, 2));
})();
