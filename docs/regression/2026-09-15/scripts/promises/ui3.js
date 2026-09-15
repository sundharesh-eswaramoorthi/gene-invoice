// UI part B: record-payment links an open promise, row override from the list (stale?), edit after an
// invoice cancel, customer read-only view, mobile width.
const fx = require('./fx'); const { addDays } = fx;
const U = require('./uih'); const { rt } = U;
const { makeHarness } = require('./harness');
const { tc, save } = makeHarness('ui3');
const D = __dirname;
const enc = encodeURIComponent;

(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  // customer whose name sorts first, so it is inside the record-payment customer dropdown
  const cp = await rt.createCustomer(adm, 'aapromui');
  await fx.addPoc(cp.id, coll.id, true);
  const invY = await fx.invoice(cp.id, 100);
  const invZ = await fx.invoice(cp.id, 100);
  const pZ = (await fx.promise({ customerId: cp.id, amount: 100, promisedDate: addDays(9), invoiceIds: [invZ.id] })).json;
  // customer for the list-row override + customer login
  const cr = await rt.createCustomer(adm, 'promisesui');
  await fx.addPoc(cr.id, coll.id, true);
  const invR = await fx.invoice(cr.id, 60);
  const pR = (await fx.promise({ customerId: cr.id, amount: 60, promisedDate: addDays(15), invoiceIds: [invR.id], notes: 'row override' })).json;
  // customer for the edit-after-cancel lead
  const ce = await rt.createCustomer(adm, 'promisesui');
  await fx.addPoc(ce.id, coll.id, true);
  const invM = await fx.invoice(ce.id, 100);
  const invN = await fx.invoice(ce.id, 100);
  await fx.promise({ customerId: ce.id, amount: 200, promisedDate: addDays(6), invoiceIds: [invM.id, invN.id] });
  await rt.api('POST', `/api/invoices/${invN.id}/cancel`, { token: adm });

  const app = await rt.openApp({ token: adm, height: 1300 });
  const { page } = app;
  const T = (id, title, fn) => tc(id, title, async () => { await U.closeDialogs(page); return fn(); });
  const posted = [];
  page.on('request', (r) => { if (r.method() === 'POST' && r.url().endsWith('/api/payments')) posted.push(r.postData()); });

  await T('PRM-UI10', 'Record payment dialog lists the customer\'s open promise and links the payment to it', async () => {
    await rt.go(page, '#/payments', 4000);
    await U.tapLast(page, 'Record payment', 'button', 2500);
    const cf = (await U.waitFor(page, /Customer \*/))[0];
    await U.click(page, cf, 2000);
    let item = (await U.all(page, cp.name)).pop();
    if (!item) {
      const shot = await rt.shot(page, D, 'ui3-10-customer-menu');
      return { ok: false, actual: `customer ${cp.name} not offered in the Customer dropdown`, evidence: shot + '\n' + U.dump(await U.sem(page)).slice(0, 2000) };
    }
    await U.tapNode(page, item, 3000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const openSection = /Open promises/.test(txt);
    const pcb = (await U.all(page, /₹100\.00 by .* • Open/, 'checkbox'))[0];
    const shot1 = await rt.shot(page, D, 'ui3-10a-record-dialog');
    if (pcb) await U.tapNode(page, pcb, 800);
    await U.tapNode(page, (await U.all(page, new RegExp(invZ.invoiceNumber), 'checkbox'))[0], 800);
    const af = await U.field(page, 0);
    await U.click(page, af);
    await rt.typeText(page, '100');
    const shot2 = await rt.shot(page, D, 'ui3-10b-record-filled');
    await U.tapLast(page, 'Record', 'button', 3500);
    const g = await fx.getPromise(pZ.id);
    const body = posted.map((p) => JSON.parse(p)).find((b) => b.customerId === cp.id);
    return { ok: openSection && !!pcb && body && JSON.stringify(body.promiseIds) === JSON.stringify([pZ.id]) && g.json.status === 'KEPT' && g.json.payments.length === 1,
      actual: `'Open promises' section shown=${openSection}; promise checkbox found=${!!pcb}; POST body promiseIds=${JSON.stringify(body?.promiseIds)} invoiceIds=${JSON.stringify(body?.invoiceIds)} poc=${body?.collectionPocUserId}; promise now ${g.json.status} links=${JSON.stringify(g.json.payments.map((x) => x.id))}`, evidence: `${shot1} ; ${shot2}` };
  });

  await T('PRM-UI11', 'Override from a Promises list row updates the row status and the tiles (lead #16)', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cr.id)}`, 5000);
    const shot0 = await rt.shot(page, D, 'ui3-11a-list-before');
    await U.tapLast(page, 'Override status', 'button', 2000);
    const dd = (await U.all(page, /Status/)).filter((n) => n.role === 'button' || /Open/.test(U.lab(n)));
    await U.click(page, dd[dd.length - 1], 1200);
    await U.tapLast(page, 'Kept', null, 1200);
    await U.click(page, await U.field(page, 0));
    await rt.typeText(page, 'Paid by cheque, clearing');
    await U.tapLast(page, 'Override', 'button', 3500);
    const g = await fx.getPromise(pR.id);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot1 = await rt.shot(page, D, 'ui3-11b-list-after');
    const rowKept = (await U.all(page, 'Kept', null)).some((n) => n.role === 'cell' || n.role === null);
    const cellStatuses = (await U.sem(page)).filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    const tv = U.tiles(await U.sem(page));
    const tileOpen = (tv[1] || '').split(' ')[0];
    const tileKept = (tv[2] || '').split(' ')[0];
    return { ok: g.json.status === 'KEPT' && cellStatuses.join() === 'Kept' && tileKept === '1' && tileOpen === '0',
      actual: `API status=${g.json.status}; row status cell(s) on screen=${JSON.stringify(cellStatuses)}; tiles Open=${tileOpen} Kept=${tileKept}`, evidence: `${shot0} ; ${shot1}` };
  });

  await T('PRM-UI12', 'Edit (date only) of a promise one of whose invoices was cancelled (lead #3)', async () => {
    await rt.go(page, `#/customers/${ce.id}?tab=promises`, 5000);
    await U.tapNode(page, await U.cardButton(page, /^₹200\.00 by/, 'Edit'), 2500);
    const boxes = (await U.all(page, /INV-/, 'checkbox')).map((n) => `${U.lab(n).split('\n')[0]}=${n.checked}`);
    await U.click(page, await U.field(page, -1));
    await rt.typeText(page, 'moved');
    await U.tapLast(page, 'Save', 'button', 3000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui3-12-edit-after-invoice-cancel');
    const err = (txt.match(/Invoice [^|]*cancelled[^|]*/) || [''])[0];
    const dialogOpen = (await U.all(page, 'Edit promise')).length > 0;
    if (dialogOpen) await U.tapLast(page, 'Cancel', 'button', 1200);
    return { ok: !dialogOpen && !err, actual: `invoice checkboxes offered=${JSON.stringify(boxes)} (cancelled ${invN.invoiceNumber} not listed so it cannot be unticked); after Save: dialog still open=${dialogOpen}; error shown="${err}"`, evidence: shot };
  });

  await T('PRM-UI13', 'Customer login sees its promises read-only with no POC and no manage buttons', async () => {
    const ct = await rt.login(cr.username, cr.password);
    const capp = await rt.openApp({ token: ct, height: 1100 });
    await rt.go(capp.page, `#/customers/${cr.id}?tab=promises`, 5000);
    const nodes = await U.sem(capp.page);
    const txt = nodes.map(U.lab).join(' | ');
    const shot = await rt.shot(capp.page, D, 'ui3-13-customer-view');
    const card = /₹60\.00 by/.test(txt);
    const bad = ['Raise promise', 'Edit', 'Override status', 'Override…', 'Cancel'].filter((b) => nodes.some((n) => n.role === 'button' && U.lab(n) === b));
    const poc = /POC:/.test(txt) || txt.includes(coll.username.toUpperCase());
    await rt.go(capp.page, '#/promises', 4000);
    const shot2 = await rt.shot(capp.page, D, 'ui3-13b-customer-promises-list');
    const t2 = (await U.sem(capp.page)).map(U.lab).join(' | ');
    await capp.close();
    return { ok: card && bad.length === 0 && !poc, actual: `card visible=${card}; manage buttons present=${JSON.stringify(bad)}; POC identity shown=${poc}; #/promises for customer: ${t2.slice(0, 300)}`, evidence: `${shot} ; ${shot2} ; apiErrors=${JSON.stringify(capp.apiErrors)}` };
  });

  await T('PRM-UI14', 'Promises list and Payment Promise tab are usable at phone width (400px)', async () => {
    const mapp = await rt.openApp({ token: adm, width: 400, height: 860 });
    await rt.go(mapp.page, `#/promises?f=${enc('customerId:eq:' + cr.id)}`, 5000);
    const s1 = await rt.shot(mapp.page, D, 'ui3-14a-mobile-list');
    await rt.go(mapp.page, `#/customers/${cr.id}?tab=promises`, 5000);
    const s2 = await rt.shot(mapp.page, D, 'ui3-14b-mobile-customer-tab');
    const errs = mapp.pageErrors;
    await mapp.close();
    return { ok: errs.length === 0, actual: `page errors=${JSON.stringify(errs).slice(0, 300)} (see screenshots for layout)`, evidence: `${s1} ; ${s2}` };
  });

  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
