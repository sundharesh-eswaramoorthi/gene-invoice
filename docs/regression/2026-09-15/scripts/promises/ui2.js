// UI part A: tiles, raise from customer + invoice details, excess/shortfall, edit, override, clear, cancel (Keep it).
const fx = require('./fx'); const { addDays } = fx;
const U = require('./uih'); const { rt } = U;
const { makeHarness } = require('./harness');
const { tc, save } = makeHarness('ui2');
const D = __dirname;
const enc = encodeURIComponent;

(async () => {
  const adm = await fx.admin();
  const coll = await rt.createStaff(adm, 'COLLECTION_POC', 'promises');
  const cu = await rt.createCustomer(adm, 'promisesui');
  await fx.addPoc(cu.id, coll.id, true);
  const invA = await fx.invoice(cu.id, 100);
  const invB = await fx.invoice(cu.id, 250);
  const invC = await fx.invoice(cu.id, 80);
  await fx.promise({ customerId: cu.id, amount: 80, promisedDate: addDays(10), invoiceIds: [invC.id] });
  await fx.promise({ customerId: cu.id, amount: 250, promisedDate: addDays(12), invoiceIds: [invB.id] });
  await fx.pay(cu.id, 100, { invoiceIds: [invB.id] });
  const list = async () => (await rt.api('GET', `/api/promises?size=50&customerId=${cu.id}`, { token: adm })).json.content;
  console.log('customer', cu.id, cu.name, 'invA', invA.invoiceNumber, 'invB', invB.invoiceNumber, 'invC', invC.invoiceNumber, 'coll', coll.username);

  const app = await rt.openApp({ token: adm, height: 1300 });
  const { page } = app;
  const T = (id, title, fn) => tc(id, title, async () => { await U.closeDialogs(page); return fn(); });

  await T('PRM-UI01', 'Promises list tiles reflect the filtered set (customerId chip) and match the API summary', async () => {
    await rt.go(page, `#/promises?f=${enc('customerId:eq:' + cu.id)}`, 5000);
    const shot = await rt.shot(page, D, 'ui2-01-list-filtered');
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const s = (await rt.api('GET', `/api/promises/summary?customerId=${cu.id}`, { token: adm })).json;
    const tv = U.tiles(await U.sem(page));
    const ok = JSON.stringify(tv) === JSON.stringify(['2', '1 • ₹80.00', '0 • ₹0.00', '1 • ₹250.00', '0 • ₹0.00', '₹330.00']) && txt.includes('1–2 of 2');
    return { ok, actual: `tiles on screen [total, open, kept, partial, broken, promised]=${JSON.stringify(tv)}; pager '1–2 of 2' shown=${txt.includes('1–2 of 2')}; API summary total=${s.total} open=${s.openCount}/${s.openAmount} partial=${s.partiallyKeptCount}/${s.partiallyKeptAmount} promised=${s.promisedAmount}; screen: ${txt.slice(0, 600)}`, evidence: shot };
  });

  let raised;
  await T('PRM-UI02', 'Raise promise from Customer Details -> Payment Promise tab (POC defaults to primary, invoice ticked, excess shown)', async () => {
    await rt.go(page, `#/customers/${cu.id}?tab=promises`, 5000);
    await U.tapLast(page, 'Raise promise', 'button', 2500);
    const f = await U.field(page, 0);
    await U.click(page, f);
    await rt.typeText(page, '120');
    const cb = (await U.all(page, new RegExp(invA.invoiceNumber), 'checkbox'))[0];
    await U.tapNode(page, cb, 1000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui2-02-raise-excess');
    const pocShown = txt.includes(coll.username.toUpperCase()) || txt.includes(coll.username);
    const excess = /Promised ₹20\.00 more than those invoices owe/.test(txt);
    await U.tapLast(page, 'Raise promise', 'button', 3000);
    const l = await list();
    raised = l.find((p) => Number(p.amount) === 120);
    const after = await rt.shot(page, D, 'ui2-02b-after-raise');
    const cardShown = (await U.all(page, /₹120\.00 by/)).length > 0;
    return { ok: pocShown && excess && !!raised && raised.invoices.map((i) => i.id).join() === String(invA.id) && raised.collectionPoc?.id === coll.id && cardShown,
      actual: `dialog: POC default shown=${pocShown}, excess text shown=${excess}; saved=${!!raised} invoices=${raised?.invoices.map((i) => i.invoiceNumber)} poc=${raised?.collectionPoc?.username} date=${raised?.promisedDate} status=${raised?.status}; new card on tab=${cardShown}`, evidence: `${shot} ; ${after}` };
  });

  await T('PRM-UI03', 'Raise dialog shows the shortfall against the ticked invoice; dialog Cancel saves nothing', async () => {
    const before = (await list()).length;
    await U.tapLast(page, 'Raise promise', 'button', 2500);
    const f = await U.field(page, 0);
    await U.click(page, f);
    await rt.typeText(page, '50');
    await U.tapNode(page, (await U.all(page, new RegExp(invB.invoiceNumber), 'checkbox'))[0], 1000);
    const txt = (await U.sem(page)).map(U.lab).join(' | ');
    const shot = await rt.shot(page, D, 'ui2-03-raise-shortfall');
    await U.tapLast(page, 'Cancel', 'button', 1500);
    const after = (await list()).length;
    const stillOnPage = (await U.hash(page)).includes(`/customers/${cu.id}`) && (await U.all(page, 'Raise promise')).length > 0;
    return { ok: /Promised ₹100\.00 less than those invoices owe/.test(txt) && before === after && stillOnPage, actual: `shortfall text present=${/less than those invoices owe/.test(txt)} (${(txt.match(/Promised ₹[\d.,]+ (less|more)[^|]*/) || [''])[0]}); promises before=${before} after=${after}; still on customer page=${stillOnPage}`, evidence: shot };
  });

  await T('PRM-UI04', 'Raise promise from Invoice Details -> Payment Promise tab is pre-scoped to that invoice', async () => {
    await rt.go(page, `#/invoices/${invC.id}?tab=promises`, 5000);
    const errs = app.apiErrors.filter((e) => e.url.includes('/api/promises'));
    await U.tapLast(page, 'Raise promise', 'button', 2500);
    const cb = (await U.waitFor(page, new RegExp(invC.invoiceNumber), { role: 'checkbox' }))[0];
    const others = (await U.all(page, /INV-/, 'checkbox')).map((n) => `${U.lab(n).split('\n')[0]}=${n.checked}`);
    const shot = await rt.shot(page, D, 'ui2-04-invoice-raise');
    const f = await U.field(page, 0);
    await U.click(page, f);
    await rt.typeText(page, '30');
    await U.tapLast(page, 'Raise promise', 'button', 3000);
    const saved = (await list()).find((p) => Number(p.amount) === 30);
    const cards = (await U.all(page, /₹\d[\d.,]* by/, 'group')).map((n) => U.lab(n).split('\n')[0]);
    const after = await rt.shot(page, D, 'ui2-04b-invoice-tab-after');
    return { ok: errs.length === 0 && cb?.checked === 'true' && saved?.invoices.map((i) => i.id).join() === String(invC.id) && cards.some((c) => c.startsWith('₹30.00')) && cards.every((c) => !c.startsWith('₹250.00')),
      actual: `promise-tab API errors=${JSON.stringify(errs)}; invoice ${invC.invoiceNumber} pre-ticked=${cb?.checked}; checkboxes ${JSON.stringify(others)}; saved invoices=${saved?.invoices.map((i) => i.invoiceNumber)}; invoice tab cards=${JSON.stringify(cards)}`, evidence: `${shot} ; ${after}` };
  });

  await T('PRM-UI05', 'Edit a promise from its card (amount 120 -> 130)', async () => {
    await rt.go(page, `#/customers/${cu.id}?tab=promises`, 5000);
    await U.tapNode(page, await U.cardButton(page, /^₹120\.00 by/, 'Edit'), 2500);
    const title = (await U.all(page, 'Edit promise')).length > 0;
    const f = await U.field(page, 0);
    await U.click(page, f);
    await rt.typeText(page, '130', { clear: true });
    const shot = await rt.shot(page, D, 'ui2-05-edit');
    await U.tapLast(page, 'Save', 'button', 3000);
    const p = (await list()).find((x) => x.id === raised.id);
    const card = (await U.all(page, /₹130\.00 by/, 'group')).length > 0;
    return { ok: title && Number(p.amount) === 130 && card, actual: `dialog title Edit promise=${title}; API amount=${p.amount} invoices=${p.invoices.map((i) => i.invoiceNumber)}; card updated=${card}`, evidence: shot };
  });

  await T('PRM-UI06', 'Override status dialog: empty reason blocked; Kept + reason pins status and card shows the reason', async () => {
    await U.tapNode(page, await U.cardButton(page, /₹130\.00 by/, 'Override status'), 2000);
    const d0 = U.dump(await U.sem(page));
    await U.tapLast(page, 'Override', 'button', 1200);
    const reqTxt = (await U.all(page, /A reason is required/)).length > 0;
    const shot1 = await rt.shot(page, D, 'ui2-06a-override-no-reason');
    const dd = (await U.all(page, /Status/)).filter((n) => n.role === 'button' || /Open/.test(U.lab(n)));
    await U.click(page, dd[dd.length - 1], 1200);
    const menu = U.dump(await U.sem(page));
    await U.tapLast(page, 'Kept', null, 1200);
    const rf = await U.field(page, 0);
    await U.click(page, rf);
    await rt.typeText(page, 'Customer confirmed by phone');
    const shot2 = await rt.shot(page, D, 'ui2-06b-override-filled');
    await U.tapLast(page, 'Override', 'button', 3000);
    const p = (await list()).find((x) => x.id === raised.id);
    const cardTxt = (await U.all(page, /₹130\.00 by/, 'group')).map(U.lab).join('');
    const shot3 = await rt.shot(page, D, 'ui2-06c-after-override');
    return { ok: reqTxt && p.statusOverridden && p.status === 'KEPT' && p.overrideReason === 'Customer confirmed by phone' && /Kept/.test(cardTxt) && /Overridden: Customer confirmed by phone/.test(cardTxt),
      actual: `'A reason is required' shown=${reqTxt}; API status=${p.status} overridden=${p.statusOverridden} reason=${p.overrideReason}; card: ${cardTxt.replace(/\n/g, ' / ')}`, evidence: `${shot1} ; ${shot2} ; ${shot3}${reqTxt ? '' : '\nDIALOG:' + d0.slice(0, 1500)}${p.statusOverridden ? '' : '\nMENU:' + menu.slice(0, 1500)}` };
  });

  await T('PRM-UI07', 'Clear override from the Override dialog hands back to automatic status', async () => {
    await U.tapNode(page, await U.cardButton(page, /₹130\.00 by/, /^Override/), 2000);
    await U.tapLast(page, 'Clear override', 'button', 3000);
    const p = (await list()).find((x) => x.id === raised.id);
    const cardTxt = (await U.all(page, /₹130\.00 by/, 'group')).map(U.lab).join('');
    return { ok: !p.statusOverridden && p.status === 'OPEN' && !/Overridden:/.test(cardTxt), actual: `API status=${p.status} overridden=${p.statusOverridden}; card: ${cardTxt.replace(/\n/g, ' / ')}` };
  });

  await T('PRM-UI08', "Cancel dialog: 'Keep it' closes only the dialog (regression) and the promise stays live", async () => {
    const hBefore = await U.hash(page);
    await U.tapNode(page, await U.cardButton(page, /₹130\.00 by/, 'Cancel'), 2000);
    const dlg = (await U.all(page, 'Cancel this promise?')).length > 0;
    const shot1 = await rt.shot(page, D, 'ui2-08a-cancel-dialog');
    await U.tapLast(page, 'Keep it', 'button', 2500);
    const hAfter = await U.hash(page);
    const stillDialog = (await U.all(page, 'Cancel this promise?')).length > 0;
    const tabOk = (await U.all(page, 'Raise promise')).length > 0 && (await U.all(page, /₹130\.00 by/, 'group')).length > 0;
    const p = (await list()).find((x) => x.id === raised.id);
    const shot2 = await rt.shot(page, D, 'ui2-08b-after-keep-it');
    return { ok: dlg && !stillDialog && hAfter === hBefore && tabOk && p.status !== 'CANCELLED', actual: `dialog opened=${dlg}; after Keep it: dialog still open=${stillDialog}; hash ${hBefore} -> ${hAfter}; tab + card still shown=${tabOk}; API status=${p.status}`, evidence: `${shot1} ; ${shot2}` };
  });

  await T('PRM-UI09', 'Cancel promise with a reason: status Cancelled, card loses Edit/Override/Cancel', async () => {
    await U.tapNode(page, await U.cardButton(page, /₹130\.00 by/, 'Cancel'), 2000);
    const rf = await U.field(page, 0);
    await U.click(page, rf);
    await rt.typeText(page, 'Raised in error');
    await U.tapLast(page, 'Cancel promise', 'button', 3000);
    const p = (await list()).find((x) => x.id === raised.id);
    const au = await rt.api('GET', `/api/audit?entityType=PROMISE&entityId=${raised.id}`, { token: adm });
    const cancelRow = (au.json || []).find((x) => x.action === 'PROMISE_CANCELLED');
    const nodes = await U.sem(page);
    const card = nodes.find((n) => n.role === 'group' && /₹130\.00 by/.test(U.lab(n)));
    const btns = card ? nodes.filter((n) => n.role === 'button' && Math.abs(n.y - card.y) <= card.h / 2).map(U.lab) : [];
    const shot = await rt.shot(page, D, 'ui2-09-cancelled');
    return { ok: p.status === 'CANCELLED' && /Cancelled/.test(U.lab(card || {})) && !btns.some((b) => /Edit|Cancel|Override/.test(b)),
      actual: `API status=${p.status}; audit PROMISE_CANCELLED reason=${JSON.stringify(cancelRow?.reason ?? cancelRow?.notes ?? cancelRow)?.slice(0, 160)}; card=${U.lab(card || {}).replace(/\n/g, ' / ')}; card buttons=${JSON.stringify(btns)}`, evidence: shot };
  });

  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  save();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
