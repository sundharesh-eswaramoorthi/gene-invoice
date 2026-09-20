// Area E — probes every seeded role against every new capability and records the raw answers.
const e = require('./lib-e.js');
const rt = e.rt;

const ROLE_KEYS = ['ADMIN', 'CASHIER', 'VIEWER', 'CUSTOMER', 'SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC'];
const TERM_FOR_ROLE = {
  ADMIN: 'NET_15', CASHIER: 'NET_30', VIEWER: 'NET_60', CUSTOMER: 'NET_90',
  SALES_POC: 'NET_15', CUSTOMER_SUCCESS_POC: 'NET_60', COLLECTION_POC: 'NET_45',
};

(async () => {
  const w = await e.world();
  const tokens = await e.tokens(w);
  const admin = await rt.adminToken();
  const out = { generatedAt: new Date().toISOString(), world: {
    custA: w.custA.id, custT: w.custT.id, custB: w.custB.id,
    invA: w.invA.id, invOverdue: w.invOverdue.id, invB: w.invB.id,
    payA: w.payA.id, payB: w.payB.id,
    docs: Object.fromEntries(Object.entries(w.docs).map(([k, v]) => [k, v.id])),
    users: Object.fromEntries(Object.entries(w.users).map(([k, v]) => [k, v.username])),
  }, rolePrivileges: {}, probes: {} };

  // ---- the privileges each seeded role actually holds -------------------------------
  const roles = await rt.api('GET', '/api/roles?size=50', { token: admin });
  for (const r of roles.json.content || []) out.rolePrivileges[r.name] = r.privileges;

  const line = { productId: w.productId, quantity: 1, unitPrice: '100.00' };

  // A fresh invoice per role for the due-date-override probe, so nothing is shared.
  const overrideTargets = {};
  for (const key of ROLE_KEYS) {
    const r = await rt.api('POST', '/api/invoices', {
      token: admin,
      body: { customerId: w.custA.id, salesPocUserId: w.users.SALES_POC.id, items: [line], notes: 'area E override ' + key },
    });
    overrideTargets[key] = r.json;
  }
  out.overrideTargets = Object.fromEntries(Object.entries(overrideTargets).map(([k, v]) => [k, v.id]));

  for (const key of ROLE_KEYS) {
    const t = tokens[key];
    const p = {};

    // 1. set a customer's payment terms
    const term = TERM_FOR_ROLE[key];
    const termRes = await rt.api('PUT', `/api/customers/${w.custT.id}`, {
      token: t,
      body: { name: w.custT.name, phone: w.custT.phone, email: w.custT.email, address: w.custT.address, paymentTerm: term },
    });
    const afterTerm = await rt.api('GET', `/api/customers/${w.custT.id}`, { token: admin });
    p.setPaymentTerms = { status: termRes.status, sent: term, storedAfter: afterTerm.json?.paymentTerm, body: termRes.json?.message || termRes.json?.paymentTerm || null };

    // 2. create an invoice on custA (NET_45) and check the default due date
    const invDate = e.today();
    const createRes = await rt.api('POST', '/api/invoices', {
      token: t,
      body: { customerId: w.custA.id, salesPocUserId: w.users.SALES_POC.id, invoiceDate: invDate + 'T00:00:00Z', items: [line], notes: 'area E create ' + key },
    });
    p.createInvoice = {
      status: createRes.status,
      dueDate: createRes.json?.dueDate ?? null,
      paymentTerm: createRes.json?.paymentTerm ?? null,
      expectedDueDate: e.plusDays(invDate, 45),
      message: createRes.json?.message ?? null,
    };

    // 3. override an invoice due date
    const target = overrideTargets[key];
    const newDue = e.plusDays(e.today(), 99);
    const patchRes = await rt.api('PATCH', `/api/invoices/${target.id}`, {
      token: t, body: { paymentTerm: 'CUSTOM', dueDate: newDue },
    });
    const afterPatch = await rt.api('GET', `/api/invoices/${target.id}`, { token: admin });
    p.overrideDueDate = {
      status: patchRes.status, sent: newDue,
      storedAfter: afterPatch.json?.dueDate, storedTerm: afterPatch.json?.paymentTerm,
      message: patchRes.json?.message ?? null,
    };

    // 4. ageing endpoint — status, coverage, bucket labels
    const ageing = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: t });
    p.ageing = {
      status: ageing.status,
      coverage: ageing.json?.coverage ?? null,
      buckets: (ageing.json?.buckets || []).map((b) => b.label),
      total: (ageing.json?.buckets || []).reduce((s, b) => s + Number(b.amount || 0), 0),
      message: ageing.json?.message ?? null,
    };

    // 5. filter invoices by overdue (scoped to custA so the answer is deterministic)
    const overdueAll = await rt.api('GET', '/api/invoices?filter=overdue:eq:true&size=50', { token: t });
    const overdueA = await rt.api('GET', `/api/invoices?customerId=${w.custA.id}&filter=overdue:eq:true&size=50`, { token: t });
    p.overdueFilter = {
      status: overdueAll.status,
      totalAll: overdueAll.json?.totalElements ?? null,
      custATotal: overdueA.json?.totalElements ?? null,
      seesSeededOverdue: (overdueA.json?.content || []).some((i) => i.id === w.invOverdue.id),
      allRowsOverdue: (overdueAll.json?.content || []).every((i) => i.overdue === true),
      lockedFilters: overdueAll.json?.lockedFilters ?? null,
      message: overdueAll.json?.message ?? null,
    };

    // 6. documents on a customer, an invoice and a payment
    p.documents = {};
    const entities = [
      ['CUSTOMER', w.custA.id, w.docs.custA_internal.id, w.docs.custA_shared.id],
      ['INVOICE', w.invA.id, w.docs.invA_internal.id, w.docs.invA_shared.id],
      ['PAYMENT', w.payA.id, w.docs.payA_internal.id, null],
    ];
    for (const [type, id, internalDocId, sharedDocId] of entities) {
      const d = {};
      const up = await e.upload(t, { entityType: type, entityId: id, filename: `e-${key}-${type}.pdf`, description: key });
      d.upload = { status: up.status, id: up.json?.id ?? null, visibility: up.json?.visibility ?? null, message: up.json?.message ?? null };

      const list = await rt.api('GET', `/api/documents?entityType=${type}&entityId=${id}&size=50`, { token: t });
      d.list = {
        status: list.status,
        total: list.json?.totalElements ?? null,
        visibilities: [...new Set((list.json?.content || []).map((x) => x.visibility))],
        canEditFlags: [...new Set((list.json?.content || []).map((x) => x.canEdit))],
        canDeleteFlags: [...new Set((list.json?.content || []).map((x) => x.canDelete))],
        message: list.json?.message ?? null,
      };

      const cnt = await rt.api('GET', `/api/documents/count?entityType=${type}&entityId=${id}`, { token: t });
      d.count = { status: cnt.status, count: cnt.json?.count ?? null };

      const dlInternal = await e.download(t, internalDocId);
      d.downloadInternal = { status: dlInternal.status, disposition: dlInternal.headers['content-disposition'] ?? null, contentType: dlInternal.headers['content-type'] ?? null, nosniff: dlInternal.headers['x-content-type-options'] ?? null };
      if (sharedDocId) {
        const dlShared = await e.download(t, sharedDocId);
        d.downloadShared = { status: dlShared.status };
      }

      // Edit / delete: the role's own upload when it has one, otherwise the admin-uploaded doc.
      const editId = d.upload.id || internalDocId;
      d.editedOwn = Boolean(d.upload.id);
      const patch = await rt.api('PATCH', `/api/documents/${editId}`, { token: t, body: { description: 'edited by ' + key, visibility: 'SHARED' } });
      d.edit = { status: patch.status, targetOwn: d.editedOwn, visibilityAfter: patch.json?.visibility ?? null, message: patch.json?.message ?? null };

      const del = await rt.api('DELETE', `/api/documents/${editId}`, { token: t });
      d.delete = { status: del.status, targetOwn: d.editedOwn, message: del.json?.message ?? null };

      p.documents[type] = d;
    }

    // 7. CSV export of invoices
    const exp = await rt.api('POST', '/api/invoices/export', {
      token: t, body: { action: 'EXPORT', ids: [w.invA.id, w.invOverdue.id] },
    });
    const header = (exp.text || '').split('\n')[0] || '';
    p.export = {
      status: exp.status,
      header,
      hasDueDate: /Due date/i.test(header),
      hasOverdue: /Overdue/i.test(header),
      rows: (exp.text || '').trim().split('\n').length - 1,
      message: exp.json?.message ?? null,
    };

    out.probes[key] = p;
    console.log('probed', key);
  }

  // EXPORT_DATA without INVOICE_VIEW — the conjunction on the export endpoint.
  const eo = tokens.EXPORT_ONLY;
  const eoExport = await rt.api('POST', '/api/invoices/export', { token: eo, body: { action: 'EXPORT', ids: [w.invA.id] } });
  const eoInvoices = await rt.api('GET', '/api/invoices', { token: eo });
  out.exportOnly = { export: eoExport.status, invoiceList: eoInvoices.status };

  e.writeOut('probe.json', out);
  console.log('wrote out/probe.json');
})().catch((err) => { console.error(err); process.exit(1); });
