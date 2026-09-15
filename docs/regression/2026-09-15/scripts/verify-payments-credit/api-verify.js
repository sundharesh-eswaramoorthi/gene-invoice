// Independent verification of the payments-credit API failures (PAYCR-012/015/021/022/023/031/034).
const rt = require('../lib.js');
const n = (v) => (v === null || v === undefined ? null : Number(v));
const snip = (r) => ({ status: r.status, body: (r.text || '').slice(0, 260) });
const log = (id, o) => console.log(id, JSON.stringify(o));

(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body, token = admin) => rt.api(m, p, { token, body });
  const poc1 = await rt.createStaff(admin, 'COLLECTION_POC', 'vpaycr');
  const poc2 = await rt.createStaff(admin, 'COLLECTION_POC', 'vpaycr');
  const sales = await rt.createStaff(admin, 'SALES_POC', 'vpaycr');
  const prod = (await api('POST', '/api/products', { name: rt.uniq('vpaycr-prod'), price: 100 })).json;
  const mkInv = async (cid, amount, date) => {
    const r = await api('POST', '/api/invoices', { customerId: cid, invoiceDate: date, salesPocUserId: sales.id,
      items: [{ productId: prod.id, quantity: 1, unitPrice: amount }] });
    if (r.status !== 200) throw new Error('invoice ' + r.status + r.text);
    return r.json;
  };
  const inv = async (id) => (await api('GET', `/api/invoices/${id}`)).json;
  const credit = async (cid) => n((await api('GET', `/api/payments/credits/${cid}`)).json?.creditBalance);
  const pay = (body, token = admin) => api('POST', '/api/payments', body, token);

  // PAYCR-012 unknown invoice id
  {
    const c = await rt.createCustomer(admin, 'vpaycr');
    const H = await mkInv(c.id, 100, '2026-01-01T10:00:00Z');
    const r = await pay({ customerId: c.id, amount: 10, invoiceIds: [99999999], collectionPocUserId: poc1.id });
    const h = await inv(H.id);
    // also: mix of a valid and an unknown id
    const r2 = await pay({ customerId: c.id, amount: 10, invoiceIds: [H.id, 99999998], collectionPocUserId: poc1.id });
    log('PAYCR-012', { unknownOnly: { status: r.status, invoices: r.json?.invoices?.length, creditApplied: r.json?.creditApplied,
      custCredit: r.json?.customerCreditBalance }, H: [h.status, h.paidAmount], mixed: { status: r2.status, invoices: r2.json?.invoices?.map((x) => x.id), creditApplied: r2.json?.creditApplied },
      creditNow: await credit(c.id) });
  }

  // PAYCR-015 3-decimal amount
  {
    const c = await rt.createCustomer(admin, 'vpaycr');
    const r = await pay({ customerId: c.id, amount: 10.555, collectionPocUserId: poc2.id });
    const g = (await api('GET', `/api/payments/${r.json?.id}`)).json;
    const r2 = await pay({ customerId: c.id, amount: 0.001, collectionPocUserId: poc2.id });
    const g2 = r2.json?.id ? (await api('GET', `/api/payments/${r2.json.id}`)).json : null;
    log('PAYCR-015', { post: { status: r.status, amount: r.json?.amount, creditApplied: r.json?.creditApplied, custCredit: r.json?.customerCreditBalance },
      get: { amount: g?.amount, creditApplied: g?.creditApplied }, creditNow: await credit(c.id),
      tiny: { status: r2.status, amount: r2.json?.amount, stored: g2?.amount } });
  }

  // PAYCR-021 notes length
  const cN = await rt.createCustomer(admin, 'vpaycr');
  const pN = (await pay({ customerId: cN.id, amount: 5, collectionPocUserId: poc1.id, notes: 'orig' })).json;
  {
    const r301 = await api('PATCH', `/api/payments/${pN.id}`, { notes: 'x'.repeat(301) });
    const r300 = await api('PATCH', `/api/payments/${pN.id}`, { notes: 'y'.repeat(300) });
    const post301 = await pay({ customerId: cN.id, amount: 1, collectionPocUserId: poc1.id, notes: 'z'.repeat(301) });
    const g = (await api('GET', `/api/payments/${pN.id}`)).json;
    log('PAYCR-021', { patch301: snip(r301), patch300: r300.status, post301: snip(post301), storedLen: g.notes?.length });
  }

  // PAYCR-022 inactive POC
  {
    const poc3 = await rt.createStaff(admin, 'COLLECTION_POC', 'vpaycr');
    const c = await rt.createCustomer(admin, 'vpaycr');
    const p = (await pay({ customerId: c.id, amount: 5, collectionPocUserId: poc3.id, notes: 'seed' })).json;
    const del = await api('DELETE', `/api/users/${poc3.id}`);
    const same = await api('PATCH', `/api/payments/${p.id}`, { notes: 'after POC left', collectionPocUserId: poc3.id });
    const g1 = (await api('GET', `/api/payments/${p.id}`)).json;
    const only = await api('PATCH', `/api/payments/${p.id}`, { notes: 'notes only' });
    const g2 = (await api('GET', `/api/payments/${p.id}`)).json;
    log('PAYCR-022', { del: snip(del), samePoc: snip(same), notesAfterSame: g1.notes, notesOnly: only.status, notesAfterOnly: g2.notes,
      poc: g2.collectionPoc });
  }

  // PAYCR-023 PAYMENT_MANAGE without POC_ASSIGN
  {
    const roleR = await api('POST', '/api/roles', { name: rt.uniq('VPAYCR_NOASSIGN'), description: 'rt',
      privileges: ['PAYMENT_VIEW', 'PAYMENT_MANAGE', 'POC_VIEW', 'CUSTOMER_VIEW', 'INVOICE_VIEW'] });
    const uname = rt.uniq('vpaycr');
    const uR = await api('POST', '/api/users', { username: uname, email: `${uname}@rt.local`, fullName: uname, password: rt.PASSWORD, roleId: roleR.json?.id, active: true });
    const tok = await rt.login(uname, rt.PASSWORD);
    const me = (await api('GET', '/api/auth/me', undefined, tok));
    const a = await api('PATCH', `/api/payments/${pN.id}`, { notes: 'by no-assign (same POC)', collectionPocUserId: poc1.id }, tok);
    const b = await api('PATCH', `/api/payments/${pN.id}`, { notes: 'by no-assign (notes only)' }, tok);
    const cc = await api('PATCH', `/api/payments/${pN.id}`, { collectionPocUserId: poc2.id }, tok);
    const g = (await api('GET', `/api/payments/${pN.id}`)).json;
    log('PAYCR-023', { role: roleR.status, user: uR.status, mePrivs: me.json?.privileges, samePoc: snip(a), notesOnly: snip(b), change: snip(cc),
      final: { notes: g.notes, poc: g.collectionPoc?.id, poc1: poc1.id } });
  }

  // PAYCR-031 void of a payment whose credit was consumed
  {
    const c = await rt.createCustomer(admin, 'vpaycr');
    await pay({ customerId: c.id, amount: 23, collectionPocUserId: poc1.id });
    const cr0 = await credit(c.id);
    const K = await mkInv(c.id, 100, '2026-01-01T10:00:00Z');
    const pOver = (await pay({ customerId: c.id, amount: 200, collectionPocUserId: poc1.id })).json;
    const cr1 = await credit(c.id);
    const L = await mkInv(c.id, 150, '2026-02-01T10:00:00Z');
    const cr2 = await credit(c.id);
    const ctok = await rt.login(c.username, c.password);
    const d = await api('POST', '/api/disputes', { targetType: 'PAYMENT', targetId: pOver.id, reason: 'verify void', proposedChangeJson: '{"action":"void"}' }, ctok);
    const a = await api('POST', `/api/disputes/${d.json?.id}/approve`, { adminNotes: 'ok' });
    const [k, l] = [await inv(K.id), await inv(L.id)];
    const cr3 = await credit(c.id);
    const sum = (await api('GET', `/api/payments/summary?filter=customerId:eq:${c.id}`)).json;
    const pv = (await api('GET', `/api/payments/${pOver.id}`)).json;
    log('PAYCR-031', { creditStart: cr0, K_on_create: [K.paidAmount, K.status], pOver: { alloc: pOver.invoices.map((x) => n(x.allocatedAmount)), creditApplied: pOver.creditApplied },
      creditAfterPay: cr1, L_on_create: [L.paidAmount, L.status], creditAfterL: cr2, dispute: d.status, approve: snip(a), paymentStatus: pv.status,
      K: [k.paidAmount, k.status], L: [l.paidAmount, l.status], creditAfterVoid: cr3,
      paidOnInvoices: n(k.paidAmount) + n(l.paidAmount), activeCollected: sum.totalCollected });
  }

  // PAYCR-034 bulk with an unknown id (and, for comparison, an out-of-scope-like but existing id)
  {
    const c = await rt.createCustomer(admin, 'vpaycr');
    const p = (await pay({ customerId: c.id, amount: 5, collectionPocUserId: poc1.id })).json;
    const r = await api('POST', '/api/payments/bulk', { action: 'REASSIGN_COLLECTION_POC', ids: [p.id, 99999999], params: { userId: poc2.id } });
    const invBulk = await api('POST', '/api/invoices/bulk', { action: 'REASSIGN_SALES_POC', ids: [99999999], params: { userId: sales.id } });
    log('PAYCR-034', { payments: snip(r), invoicesForComparison: snip(invBulk) });
  }
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
