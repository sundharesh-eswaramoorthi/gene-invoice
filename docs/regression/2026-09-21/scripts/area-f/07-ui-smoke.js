// Area F / UI smoke at 8084 — every main screen loads with no console error and no failed API call.
const path = require('path');
const { rt, recorder, admin, createProduct, createInvoice, recordPayment, isoDay, SHOTS } = require('./_h.js');

// Each screen is recognised by its own content in the accessibility tree — the column headers and
// primary action it owns — rather than by a page title, which CanvasKit does not expose as a node.
const SCREENS = [
  ['F-69', 'Dashboard', '/', /Billed|Outstanding|Collected/i],
  ['F-70', 'Customers', '/customers', /New customer|Credit balance|Outstanding/i],
  ['F-71', 'Invoices', '/invoices', /New invoice|Invoice #|Balance/i],
  ['F-72', 'Invoice form', '/invoices/new', /Customer|Add line|Save|Create/i],
  ['F-73', 'Payments', '/payments', /Record payment|Amount|Credit applied/i],
  ['F-74', 'Promises', '/promises', /Promised|Fulfilled|New promise/i],
  ['F-75', 'Disputes', '/disputes', /Target|Reason|Opened/i],
  ['F-76', 'Products', '/products', /New product|Price|Active/i],
  ['F-77', 'Users', '/users', /New user|Username|Full name/i],
  ['F-78', 'Roles', '/roles', /New role|Privileges/i],
  ['F-79', 'Notifications', '/notifications', /Notification|Mark all read|Received/i],
];

(async () => {
  const r = recorder('07-ui-smoke');
  const { token, id: adminId } = await admin();
  // Something on every screen, so an empty state cannot hide a rendering fault.
  const cust = await rt.createCustomer(token, 'f');
  const custToken = await rt.login(cust.username, cust.password);
  const prod = await createProduct(token, 'f-ui', '75.00');
  const inv = await createInvoice(token, { customerId: cust.id, salesPocUserId: adminId,
    items: [{ productId: prod.id, quantity: 2, unitPrice: '75.00' }] });
  await recordPayment(token, { customerId: cust.id, amount: '50.00', method: 'CASH',
    collectionPocUserId: adminId });
  await rt.api('POST', '/api/promises', { token, body: { customerId: cust.id, amount: '100.00',
    promisedDate: isoDay(5), collectionPocUserId: adminId, invoiceIds: [inv.id] } });
  await rt.api('POST', '/api/disputes', { token: custToken,
    body: { targetType: 'INVOICE', targetId: inv.id, reason: 'area-f ui fixture' } });

  const app = await rt.openApp({ token });
  try {
    for (const [id, name, hash, wanted] of SCREENS) {
      const before = { api: app.apiErrors.length, page: app.pageErrors.length };
      await r.check({
        id, feature: `UI — ${name}`, kind: 'UI',
        title: `${name} screen loads with no console error and no failed API call`,
        steps: `open ${rt.WEB}#${hash} signed in as admin`,
        expected: 'the screen renders its own content; no page error; no API response >= 400',
        codeRef: 'frontend/lib/core/router.dart',
      }, async () => {
        await rt.go(app.page, `#${hash}`, 4000);
        const nodes = await rt.semantics(app.page);
        const labels = nodes.map((n) => `${n.label || ''} ${n.text || ''}`).join(' | ');
        const rendered = wanted.test(labels);
        const newApi = app.apiErrors.slice(before.api);
        const newPage = app.pageErrors.slice(before.page);
        const ok = rendered && newApi.length === 0 && newPage.length === 0;
        if (!ok) await rt.shot(app.page, SHOTS, `${id}-1`);
        return { ok, severity: newPage.length || newApi.length ? 'high' : 'medium',
          actual: `rendered=${rendered}; apiErrors=${JSON.stringify(newApi)}; pageErrors=${JSON.stringify(newPage).slice(0, 300)}; onScreen="${labels.slice(0, 220)}"`,
          evidence: ok ? '' : path.join('report/shots', `${id}-1.png`) };
      });
    }

    // Detail screens, the other half of the blast radius.
    for (const [id, name, hash, wanted] of [
      ['F-80', 'Customer detail', `/customers/${cust.id}`, /Credit|Outstanding|Invoices|Overview/i],
      ['F-81', 'Invoice detail', `/invoices/${inv.id}`, /Invoice|Balance|Items|Overview/i],
    ]) {
      const before = { api: app.apiErrors.length, page: app.pageErrors.length };
      await r.check({
        id, feature: `UI — ${name}`, kind: 'UI',
        title: `${name} loads with no console error and no failed API call`,
        steps: `open ${rt.WEB}#${hash}`,
        expected: 'the detail screen renders; no page error; no API response >= 400',
        codeRef: 'frontend/lib/core/router.dart',
      }, async () => {
        await rt.go(app.page, `#${hash}`, 4500);
        const nodes = await rt.semantics(app.page);
        const labels = nodes.map((n) => `${n.label || ''} ${n.text || ''}`).join(' | ');
        const rendered = wanted.test(labels);
        const newApi = app.apiErrors.slice(before.api);
        const newPage = app.pageErrors.slice(before.page);
        const ok = rendered && newApi.length === 0 && newPage.length === 0;
        if (!ok) await rt.shot(app.page, SHOTS, `${id}-1`);
        return { ok, severity: newPage.length || newApi.length ? 'high' : 'medium',
          actual: `rendered=${rendered}; apiErrors=${JSON.stringify(newApi)}; pageErrors=${JSON.stringify(newPage).slice(0, 300)}; onScreen="${labels.slice(0, 220)}"`,
          evidence: ok ? '' : path.join('report/shots', `${id}-1.png`) };
      });
    }

    await r.check({
      id: 'F-82', feature: 'UI — whole session', kind: 'UI',
      title: 'No uncaught page error and no failed API call across the whole smoke run',
      steps: 'sum of everything collected while walking the 13 screens above',
      expected: 'zero page errors and zero API responses >= 400',
      codeRef: '',
    }, async () => ({
      ok: app.pageErrors.length === 0 && app.apiErrors.length === 0,
      severity: 'high',
      actual: `pageErrors=${app.pageErrors.length} ${JSON.stringify(app.pageErrors).slice(0, 300)}; apiErrors=${app.apiErrors.length} ${JSON.stringify(app.apiErrors).slice(0, 400)}`,
    }));
  } finally {
    await app.close();
  }
  r.save();
})();
