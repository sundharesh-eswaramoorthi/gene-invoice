// UI part C: re-run of the list-row override (lead #16), notification deep link, bulk cancel and export from the list.
const fx = require('./fx'); const { addDays } = fx;
const U = require('./uih'); const { rt } = U;
const { makeHarness } = require('./harness');
const { tc, save } = makeHarness('ui4');
const D = __dirname;
const enc = encodeURIComponent;

(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const collT = await rt.login(coll.username, coll.password);
  const mk = async () => { const c = await rt.createCustomer(adm, 'promisesui'); await fx.addPoc(c.id, coll.id, true); return c; };
  const cr = await mk();
  const invR = await fx.invoice(cr.id, 60);
  const pR = (await fx.promise({ customerId: cr.id, amount: 60, promisedDate: addDays(15), invoiceIds: [invR.id] })).json;
  const cb = await mk();
  await fx.invoice(cb.id, 500);
  const pb1 = (await fx.promise({ customerId: cb.id, amount: 11, promisedDate: addDays(5), notes: 'bulk one' })).json;
  const pb2 = (await fx.promise({ customerId: cb.id, amount: 12, promisedDate: addDays(6), notes: 'bulk two' })).json;
  const cbr = await mk();
  const invBr = await fx.invoice(cbr.id, 40);
  const pBr = (await fx.promise({ customerId: cbr.id, amount: 40, promisedDate: addDays(-1), invoiceIds: [invBr.id] })).json;

  const app = await rt.openApp({ token: adm, height: 1100 });
  const { page } = app;
  const T = (id, title, fn) => tc(id, title, async () => { await U.closeDialogs(page); return fn(); });

  await T('PRM-UI11b', 'Re-run: override from a Promises list row — row status and tiles after the dialog closes (lead #16)', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cr.id)}`, 5000);
    const before = U.tiles(await U.sem(page));
    await U.tapLast(page, 'Override status', 'button', 2000);
    const dd = (await U.all(page, /Status/)).filter((n) => n.role === 'button' || /Open/.test(U.lab(n)));
    await U.click(page, dd[dd.length - 1], 1200);
    await U.tapLast(page, 'Kept', null, 1200);
    await U.click(page, await U.field(page, 0));
    await rt.typeText(page, 'Paid by cheque');
    await U.tapLast(page, 'Override', 'button', 4000);
    const g = await fx.getPromise(pR.id);
    const nodes = await U.sem(page);
    const cells = nodes.filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    const after = U.tiles(nodes);
    const shot = await rt.shot(page, D, 'ui4-11b-row-override-after');
    await rt.go(page, '#/dashboard', 2500);
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cr.id)}`, 5000);
    const nodes2 = await U.sem(page);
    const cells2 = nodes2.filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    return { ok: g.json.status === 'KEPT' && cells.join() === 'Kept' && after[2]?.startsWith('1 '),
      actual: `API status=${g.json.status}; immediately after the dialog: row=${JSON.stringify(cells)} tiles before=${JSON.stringify(before)} after=${JSON.stringify(after)}; after navigating away and back: row=${JSON.stringify(cells2)} tiles=${JSON.stringify(U.tiles(nodes2))}`, evidence: shot };
  });

  await T('PRM-UI15', "'Promise broken' notification link /promises/{id} lands on the customer's Payment Promise tab (AC-B10)", async () => {
    const n = (await fx.notificationsFor(collT)).find((x) => x.type === 'PROMISE_BROKEN' && x.link === `/promises/${pBr.id}`);
    await rt.go(page, `#${n ? n.link : '/promises/' + pBr.id}`, 5000);
    const h = await U.hash(page);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui4-15-deeplink');
    return { ok: !!n && h.includes(`/customers/${cbr.id}`) && h.includes('tab=promises') && /₹40\.00 by/.test(txt) && /Broken/.test(txt),
      actual: `notification found=${!!n} link=${n?.link}; landed on ${h}; broken card visible=${/₹40\.00 by/.test(txt) && /Broken/.test(txt)} (nothing on the tab singles out promise #${pBr.id})`, evidence: shot };
  });

  await T('PRM-UI16', 'Bulk cancel from the Promises list: select rows, toolbar action, confirm with exact count, result', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cb.id)}`, 5000);
    const boxes = (await U.sem(page)).filter((n) => n.role === 'checkbox');
    for (const b of boxes.slice(1)) await U.tapNode(page, b, 600);
    const tb = (await U.sem(page)).map(U.lab).join(' | ');
    const shot1 = await rt.shot(page, D, 'ui4-16a-selection-toolbar');
    await U.tapLast(page, 'Cancel promises', 'button', 1500);
    const confirmTxt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot2 = await rt.shot(page, D, 'ui4-16b-confirm');
    await U.tapLast(page, 'Confirm', 'button', 3500);
    const shot3 = await rt.shot(page, D, 'ui4-16c-result');
    const resTxt = (await U.sem(page)).map(U.lab).join(' | ');
    await U.closeDialogs(page);
    const s1 = (await fx.getPromise(pb1.id)).json.status; const s2 = (await fx.getPromise(pb2.id)).json.status;
    const cells = (await U.sem(page)).filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    return { ok: /2 selected/.test(tb) && /\b2\b/.test(confirmTxt) && s1 === 'CANCELLED' && s2 === 'CANCELLED' && cells.every((c) => c === 'Cancelled'),
      actual: `toolbar: ${(tb.match(/\d+ selected[^|]*/) || ['none'])[0]}; confirm dialog: ${(confirmTxt.match(/[^|]*(Cancel|cancel)[^|]*\d[^|]*/) || [confirmTxt.slice(-200)])[0]}; result: ${(resTxt.match(/[^|]*(updated|succeeded)[^|]*/) || [''])[0]}; API ${s1}/${s2}; rows now ${JSON.stringify(cells)}`, evidence: `${shot1} ; ${shot2} ; ${shot3}` };
  });

  await T('PRM-UI17', 'Export selected promises opens the copyable CSV dialog', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cr.id)}`, 5000);
    const boxes = (await U.sem(page)).filter((n) => n.role === 'checkbox');
    await U.tapNode(page, boxes[boxes.length - 1], 800);
    await U.tapLast(page, 'Export selected', 'button', 3000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui4-17-export');
    const hasCsv = /Id,Customer,Promised amount/.test(txt) && txt.includes(cr.name);
    return { ok: /Exported CSV/.test(txt) && hasCsv, actual: `dialog 'Exported CSV'=${/Exported CSV/.test(txt)}; CSV header + customer row present=${hasCsv}`, evidence: shot };
  });

  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
