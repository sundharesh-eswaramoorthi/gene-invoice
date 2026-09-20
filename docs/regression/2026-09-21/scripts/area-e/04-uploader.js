// Area E — the documented "uploader stands in for the record's manage privilege" rule on DELETE
// (§4.2), and whether losing DOCUMENT_MANAGE takes the capability away on the very next request.
// Uses a throwaway role of its own; no seeded role is touched.
const e = require('./lib-e.js');
const rt = e.rt;

(async () => {
  const w = await e.world();
  const admin = await rt.adminToken();
  const out = { generatedAt: new Date().toISOString() };

  const name = rt.uniq('E-UPLOADER');
  const role = await rt.api('POST', '/api/roles', {
    token: admin,
    body: { name, description: 'Area E uploader probe', privileges: ['DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'INVOICE_VIEW', 'INVOICE_MANAGE', 'SCOPE_OVERRIDE'] },
  });
  if (role.status >= 300) throw new Error('role -> ' + role.status + ' ' + role.text);
  const username = rt.uniq('e');
  const u = await rt.api('POST', '/api/users', {
    token: admin,
    body: { username, email: `${username}@rt.local`, fullName: username.toUpperCase(), password: rt.PASSWORD, roleId: role.json.id, active: true },
  });
  if (u.status >= 300) throw new Error('user -> ' + u.status + ' ' + u.text);
  const token = await rt.login(username, rt.PASSWORD);
  out.role = name;

  // Two uploads while the role still has INVOICE_MANAGE.
  const a = await e.upload(token, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-uploader-a.pdf' });
  const b = await e.upload(token, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-uploader-b.pdf' });
  out.uploadWhileManaging = { a: a.status, b: b.status };

  // Take INVOICE_MANAGE away; privileges are read per request, so the next call already feels it.
  const narrowed = await rt.api('PUT', `/api/roles/${role.json.id}`, {
    token: admin,
    body: { name, description: 'Area E uploader probe (narrowed)', privileges: ['DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'INVOICE_VIEW', 'SCOPE_OVERRIDE'] },
  });
  out.narrowRole = narrowed.status;

  out.afterLosingRecordManage = {
    upload: (await e.upload(token, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-uploader-c.pdf' })).status,
    patchOwn: (await rt.api('PATCH', `/api/documents/${a.json.id}`, { token, body: { description: 'narrowed' } })).status,
    deleteOwn: (await rt.api('DELETE', `/api/documents/${a.json.id}`, { token })).status,
    deleteSomebodyElses: (await rt.api('DELETE', `/api/documents/${w.docs.invA_internal.id}`, { token })).status,
    list: (await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invA.id}`, { token })).status,
    listCanDeleteFlags: [...new Set(((await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invA.id}&size=50`, { token })).json?.content || []).map((x) => `${x.id}:${x.canDelete}`))],
  };

  // Now take DOCUMENT_MANAGE away entirely: the endpoint's own gate must close.
  const noDocManage = await rt.api('PUT', `/api/roles/${role.json.id}`, {
    token: admin,
    body: { name, description: 'Area E uploader probe (no DOCUMENT_MANAGE)', privileges: ['DOCUMENT_VIEW', 'INVOICE_VIEW', 'INVOICE_MANAGE', 'SCOPE_OVERRIDE'] },
  });
  out.revokeDocumentManage = noDocManage.status;
  out.afterLosingDocumentManage = {
    upload: (await e.upload(token, { entityType: 'INVOICE', entityId: w.invA.id, filename: 'e-uploader-d.pdf' })).status,
    deleteOwn: (await rt.api('DELETE', `/api/documents/${b.json.id}`, { token })).status,
    patchOwn: (await rt.api('PATCH', `/api/documents/${b.json.id}`, { token, body: { description: 'x' } })).status,
    list: (await rt.api('GET', `/api/documents?entityType=INVOICE&entityId=${w.invA.id}`, { token })).status,
    download: (await e.download(token, b.json.id)).status,
  };

  e.writeOut('uploader.json', out);
  console.log(JSON.stringify(out, null, 2));
})().catch((err) => { console.error(err); process.exit(1); });
