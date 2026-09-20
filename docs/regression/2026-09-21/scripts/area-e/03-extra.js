// Area E — the privilege *conjunctions* AC-C10 asks for, and the scope/coverage the ageing
// endpoint hands each role. Uses purpose-built roles so each half of a conjunction can be
// withheld on its own; the seeded roles never carry that combination.
const e = require('./lib-e.js');
const rt = e.rt;

async function roleWith(admin, label, privileges) {
  const name = rt.uniq(label);
  const r = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'Area E probe role', privileges } });
  if (r.status >= 300) throw new Error(`role ${label} -> ${r.status} ${r.text}`);
  const username = rt.uniq('e');
  const u = await rt.api('POST', '/api/users', {
    token: admin,
    body: { username, email: `${username}@rt.local`, fullName: username.toUpperCase(), password: rt.PASSWORD, roleId: r.json.id, active: true },
  });
  if (u.status >= 300) throw new Error(`user ${label} -> ${u.status} ${u.text}`);
  return { role: r.json, username, token: await rt.login(username, rt.PASSWORD) };
}

(async () => {
  const w = await e.world();
  const admin = await rt.adminToken();
  const tokens = await e.tokens(w);
  const out = { generatedAt: new Date().toISOString(), conjunction: {}, coverage: {}, roles: {} };

  // DOCUMENT_VIEW + DOCUMENT_MANAGE but no privilege on any parent record.
  const docsOnly = await roleWith(admin, 'E-DOCS-NO-RECORD', ['DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'SCOPE_OVERRIDE']);
  // INVOICE_VIEW but no DOCUMENT_VIEW.
  const invOnly = await roleWith(admin, 'E-INV-NO-DOCVIEW', ['INVOICE_VIEW', 'SCOPE_OVERRIDE']);
  // DOCUMENT_MANAGE + INVOICE_VIEW but no INVOICE_MANAGE — reads yes, writes no (AC-C10).
  const readOnlyRecord = await roleWith(admin, 'E-DOCMANAGE-NO-INVMANAGE', ['DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'INVOICE_VIEW', 'SCOPE_OVERRIDE']);
  out.roles = { docsOnly: docsOnly.role.name, invOnly: invOnly.role.name, readOnlyRecord: readOnlyRecord.role.name, exportOnly: w.roles?.EXPORT_ONLY?.name ?? null };

  const probe = async (token) => ({
    listInvoiceDocs: (await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invA.id}`, { token })).status,
    countInvoiceDocs: (await rt.api('GET', `/api/documents/count?entityType=INVOICE&entityId=${w.invA.id}`, { token })).status,
    downloadInvoiceDoc: (await e.download(token, w.docs.invA_internal.id)).status,
    uploadInvoiceDoc: (await e.upload(token, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-conj.pdf' })).status,
    deleteInvoiceDoc: (await rt.api('DELETE', `/api/documents/${w.docs.invA_internal.id}`, { token })).status,
    patchInvoiceDoc: (await rt.api('PATCH', `/api/documents/${w.docs.invA_internal.id}`, { token, body: { description: 'x' } })).status,
    getInvoice: (await rt.api('GET', `/api/invoices/${w.invA.id}`, { token })).status,
  });

  out.conjunction.documentPrivilegesButNoRecordPrivilege = await probe(docsOnly.token);
  out.conjunction.recordPrivilegeButNoDocumentPrivilege = await probe(invOnly.token);
  out.conjunction.recordViewButNoRecordManage = await probe(readOnlyRecord.token);

  // The export conjunction: EXPORT_DATA without INVOICE_VIEW, and INVOICE_VIEW without EXPORT_DATA.
  out.conjunction.exportDataWithoutInvoiceView = (await rt.api('POST', '/api/invoices/export', { token: tokens.EXPORT_ONLY, body: { action: 'EXPORT', ids: [w.invA.id] } })).status;
  out.conjunction.invoiceViewWithoutExportData = (await rt.api('POST', '/api/invoices/export', { token: tokens.VIEWER, body: { action: 'EXPORT', ids: [w.invA.id] } })).status;
  out.conjunction.bothPresent = (await rt.api('POST', '/api/invoices/export', { token: tokens.CASHIER, body: { action: 'EXPORT', ids: [w.invA.id] } })).status;

  // Ageing needs INVOICE_VIEW.
  out.conjunction.ageingWithoutInvoiceView = (await rt.api('GET', '/api/dashboard/outstanding-by-age', { token: tokens.EXPORT_ONLY })).status;
  out.conjunction.overdueFilterWithoutInvoiceView = (await rt.api('GET', '/api/invoices?filter=overdue:eq:true', { token: tokens.EXPORT_ONLY })).status;

  // ---- coverage: does the ageing chart only ever add up what the caller may see? ---------
  const reconcile = async (key, token) => {
    const ag = await rt.api('GET', '/api/dashboard/outstanding-by-age', { token });
    const list = await rt.api('GET', '/api/invoices?size=50&sort=id,asc', { token });
    const rows = list.json?.content || [];
    const open = rows.filter((i) => i.status !== 'CANCELLED' && Number(i.balance) > 0);
    out.coverage[key] = {
      ageingStatus: ag.status,
      coverage: ag.json?.coverage ?? null,
      bucketsTotal: Number(((ag.json?.buckets || []).reduce((s, b) => s + Number(b.amount || 0), 0)).toFixed(2)),
      bucketsCount: (ag.json?.buckets || []).reduce((s, b) => s + Number(b.count || 0), 0),
      visibleInvoices: list.json?.totalElements ?? null,
      pageCoversAll: (list.json?.totalElements ?? 0) <= 50,
      visibleOpenBalance: Number(open.reduce((s, i) => s + Number(i.balance), 0).toFixed(2)),
      visibleOpenCount: open.length,
      lockedFilters: list.json?.lockedFilters ?? null,
    };
  };
  await reconcile('SALES_POC', tokens.SALES_POC);
  await reconcile('CUSTOMER', tokens.CUSTOMER);

  e.writeOut('extra.json', out);
  console.log(JSON.stringify(out, null, 2));
})().catch((err) => { console.error(err); process.exit(1); });
