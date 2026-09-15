// UI16 re-run: bulk cancel via the header select-all-on-page checkbox.
const fx = require('./fx'); const { addDays } = fx;
const U = require('./uih'); const { rt } = U;
const { makeHarness } = require('./harness');
const { tc, save } = makeHarness('ui6');
const D = __dirname; const enc = encodeURIComponent;
(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const cb = await rt.createCustomer(adm, 'promisesui'); await fx.addPoc(cb.id, coll.id, true);
  await fx.invoice(cb.id, 500);
  const pb1 = (await fx.promise({ customerId: cb.id, amount: 11, promisedDate: addDays(5) })).json;
  const pb2 = (await fx.promise({ customerId: cb.id, amount: 12, promisedDate: addDays(6) })).json;
  const app = await rt.openApp({ token: adm, height: 1100 });
  const { page } = app;
  await tc('PRM-UI16', 'Bulk cancel from the Promises list: select all on page, toolbar action, confirm with exact count, result', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cb.id)}`, 5000);
    await rt.clickAt(page, 293, 224, 1200);
    const tb = (await U.sem(page)).map(U.lab).join(' | ');
    const shot1 = await rt.shot(page, D, 'ui6-16a-selected');
    await U.tapLast(page, 'Cancel promises', 'button', 1500);
    const confirmTxt = (await U.sem(page)).map(U.lab);
    const shot2 = await rt.shot(page, D, 'ui6-16b-confirm');
    await U.tapLast(page, 'Confirm', 'button', 4000);
    const resTxt = (await U.sem(page)).map(U.lab);
    const shot3 = await rt.shot(page, D, 'ui6-16c-result');
    await U.closeDialogs(page); await page.waitForTimeout(1500);
    const s1 = (await fx.getPromise(pb1.id)).json.status; const s2 = (await fx.getPromise(pb2.id)).json.status;
    const nodes = await U.sem(page);
    const cells = nodes.filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    const shot4 = await rt.shot(page, D, 'ui6-16d-list-after');
    return { ok: /2 selected/.test(tb) && s1 === 'CANCELLED' && s2 === 'CANCELLED' && cells.length === 2 && cells.every((c) => c === 'Cancelled'),
      actual: `toolbar: ${(tb.match(/\d+ selected/) || ['none'])[0]}; confirm dialog: ${JSON.stringify(confirmTxt.filter((t) => /\d/.test(t) || /Confirm|cancel/i.test(t)).slice(-4))}; after confirm: ${JSON.stringify(resTxt.filter((t) => /succeeded|updated|failed|result/i.test(t)))}; API ${s1}/${s2}; rows now ${JSON.stringify(cells)}; tiles ${JSON.stringify(U.tiles(nodes))}`, evidence: `${shot1} ; ${shot2} ; ${shot3} ; ${shot4}` };
  });
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close(); save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
