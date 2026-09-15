// UI part D: bulk cancel and export from the Promises list, selecting rows by pixel (row checkboxes are not semantics nodes).
const fx = require('./fx'); const { addDays } = fx;
const U = require('./uih'); const { rt } = U;
const { makeHarness } = require('./harness');
const { tc, save } = makeHarness('ui5');
const D = __dirname;
const enc = encodeURIComponent;
(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const cb = await rt.createCustomer(adm, 'promisesui'); await fx.addPoc(cb.id, coll.id, true);
  await fx.invoice(cb.id, 500);
  const pb1 = (await fx.promise({ customerId: cb.id, amount: 11, promisedDate: addDays(5) })).json;
  const pb2 = (await fx.promise({ customerId: cb.id, amount: 12, promisedDate: addDays(6) })).json;
  const ce = await rt.createCustomer(adm, 'promisesui'); await fx.addPoc(ce.id, coll.id, true);
  await fx.promise({ customerId: ce.id, amount: 33, promisedDate: addDays(5), notes: 'export me' });
  const app = await rt.openApp({ token: adm, height: 1100 });
  const { page } = app;
  const T = (id, title, fn) => tc(id, title, async () => { await U.closeDialogs(page); return fn(); });
  const rowYs = async (name) => (await U.sem(page)).filter((n) => n.role === 'cell' && U.lab(n) === name).map((n) => n.y);

  await T('PRM-UI16', 'Bulk cancel from the Promises list: select rows, toolbar action, confirm with exact count, result', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cb.id)}`, 5000);
    const ys = await rowYs(cb.name);
    for (const y of ys) await rt.clickAt(page, 293, y, 700);
    const tb = (await U.sem(page)).map(U.lab).join(' | ');
    const shot1 = await rt.shot(page, D, 'ui5-16a-selection-toolbar');
    await U.tapLast(page, 'Cancel promises', 'button', 1500);
    const confirmTxt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot2 = await rt.shot(page, D, 'ui5-16b-confirm');
    await U.tapLast(page, 'Confirm', 'button', 3500);
    const resTxt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot3 = await rt.shot(page, D, 'ui5-16c-result');
    await U.closeDialogs(page);
    await page.waitForTimeout(1500);
    const s1 = (await fx.getPromise(pb1.id)).json.status; const s2 = (await fx.getPromise(pb2.id)).json.status;
    const nodes = await U.sem(page);
    const cells = nodes.filter((n) => n.role === 'cell' && /^(Open|Kept|Partially kept|Broken|Cancelled)$/.test(U.lab(n))).map(U.lab);
    return { ok: ys.length === 2 && /2 selected/.test(tb) && s1 === 'CANCELLED' && s2 === 'CANCELLED' && cells.length === 2 && cells.every((c) => c === 'Cancelled'),
      actual: `rows clicked=${ys.length}; toolbar: ${(tb.match(/\d+ selected/) || ['none'])[0]} actions=${JSON.stringify(nodes.length ? (tb.match(/Cancel promises|Reassign Collection POC|Export selected/g) || []) : [])}; confirm dialog text: ${confirmTxt.split(' | ').filter((t) => /\d/.test(t) && /promise|record|cancel/i.test(t)).join(' / ').slice(0, 200)}; result: ${resTxt.split(' | ').filter((t) => /succeeded|updated|failed/i.test(t)).join(' / ').slice(0, 200)}; API ${s1}/${s2}; rows now ${JSON.stringify(cells)} tiles=${JSON.stringify(U.tiles(nodes))}`, evidence: `${shot1} ; ${shot2} ; ${shot3}` };
  });

  await T('PRM-UI17', 'Export selected promises opens the copyable CSV dialog', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + ce.id)}`, 5000);
    const ys = await rowYs(ce.name);
    await rt.clickAt(page, 293, ys[0], 800);
    await U.tapLast(page, 'Export selected', 'button', 3000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui5-17-export');
    const hasCsv = /Id,Customer,Promised amount/.test(txt) && txt.includes(ce.name) && txt.includes('export me');
    return { ok: /Exported CSV/.test(txt) && hasCsv, actual: `dialog 'Exported CSV'=${/Exported CSV/.test(txt)}; CSV header + row present=${hasCsv}; ${txt.split(' | ').filter((t) => /Id,Customer/.test(t)).join('').slice(0, 300)}`, evidence: shot };
  });
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
