// Area E — is a POC really limited to "their POC book", as the implementation doc's matrix says?
// Probes each POC role against custB / invB / payB, records it holds no seat on.
const e = require('./lib-e.js');
const rt = e.rt;

(async () => {
  const w = await e.world();
  const tokens = await e.tokens(w);
  const admin = await rt.adminToken();
  const out = { generatedAt: new Date().toISOString(), foreignRecord: {}, seats: {} };

  // Prove the POCs hold no seat on custB and own neither invB nor payB.
  const seats = await rt.api('GET', `/api/customers/${w.custB.id}/pocs`, { token: admin });
  out.seats.custB = (seats.json || []).map((s) => ({ type: s.pocType, user: s.user?.username }));
  out.seats.invBSalesPoc = (await rt.api('GET', `/api/invoices/${w.invB.id}`, { token: admin })).json?.salesPoc?.username ?? null;
  out.seats.payBCollectionPoc = (await rt.api('GET', `/api/payments/${w.payB.id}`, { token: admin })).json?.collectionPoc?.username ?? null;
  out.seats.probeUsers = {
    SALES_POC: w.users.SALES_POC.username,
    CUSTOMER_SUCCESS_POC: w.users.CUSTOMER_SUCCESS_POC.username,
    COLLECTION_POC: w.users.COLLECTION_POC.username,
  };

  for (const key of ['SALES_POC', 'CUSTOMER_SUCCESS_POC', 'COLLECTION_POC']) {
    const t = tokens[key];
    const r = {};
    for (const [type, id, docId] of [
      ['CUSTOMER', w.custB.id, w.docs.custB_internal.id],
      ['INVOICE', w.invB.id, w.docs.invB_internal.id],
      ['PAYMENT', w.payB.id, w.docs.payB_internal.id],
    ]) {
      const up = await e.upload(t, { entityType: type, entityId: id, filename: `e-foreign-${key}-${type}.pdf` });
      r[type] = {
        list: (await rt.api('GET', `/api/documents?entityType=${type}&entityId=${id}&size=50`, { token: t })).status,
        download: (await e.download(t, docId)).status,
        upload: up.status,
        uploadedId: up.json?.id ?? null,
        deleteOwnUpload: up.json?.id ? (await rt.api('DELETE', `/api/documents/${up.json.id}`, { token: t })).status : null,
      };
    }
    r.record = {
      customer: (await rt.api('GET', `/api/customers/${w.custB.id}`, { token: tokens[key] })).status,
      invoice: (await rt.api('GET', `/api/invoices/${w.invB.id}`, { token: tokens[key] })).status,
      payment: (await rt.api('GET', `/api/payments/${w.payB.id}`, { token: tokens[key] })).status,
    };
    out.foreignRecord[key] = r;
  }

  e.writeOut('pocscope.json', out);
  console.log(JSON.stringify(out, null, 2));
})().catch((err) => { console.error(err); process.exit(1); });
