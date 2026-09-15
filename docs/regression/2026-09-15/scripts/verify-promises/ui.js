// Independent UI reproduction of PRM-R04 (edit after invoice cancel) and PRM-UI11 (stale list after row override).
const fs = require('fs');
const { rt, addDays, adm, invoice, addPoc, promise, get, brief } = require('./fx');
const D = __dirname;
const RUN = process.argv[2] || '1';
const dump = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  dump[name] = nodes.map((n) => `[${n.role}] ${lab(n).replace(/\n/g, ' / ')} @${n.x},${n.y} ${n.w}x${n.h}`);
  const file = await rt.shot(page, D, `r${RUN}-${name}`);
  return { nodes, file };
}
const last = (nodes, pred) => nodes.filter(pred).slice(-1)[0];
async function tapNode(page, n, wait = 1500) {
  await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
  await page.waitForTimeout(wait);
}

(async () => {
  const T = await adm();
  const coll = await rt.createStaff(T, 'COLLECTION_POC', 'vprm');
  // R04 fixture
  const ce = await rt.createCustomer(T, 'vprm'); await addPoc(ce.id, coll.id, true);
  const a = await invoice(ce.id, 100); const b = await invoice(ce.id, 100);
  const pe = (await promise({ customerId: ce.id, amount: 200, promisedDate: addDays(6), invoiceIds: [a.id, b.id], notes: 'orig' })).json;
  await rt.api('POST', `/api/invoices/${b.id}/cancel`, { token: T });
  // UI11 fixture
  const cr = await rt.createCustomer(T, 'vprm'); await addPoc(cr.id, coll.id, true);
  await invoice(cr.id, 60);
  const pr = (await promise({ customerId: cr.id, amount: 60, promisedDate: addDays(6) })).json;
  const res = { fixtures: { ce: ce.id, a: a.invoiceNumber, b: b.invoiceNumber, pe: pe.id, cr: cr.id, pr: pr.id } };

  const app = await rt.openApp({ token: T, height: 1000 });
  const { page } = app;
  const puts = [];
  page.on('request', (r) => { if (r.method() === 'PUT' && r.url().includes('/api/promises/')) puts.push(r.postData()); });
  try {
    // ---------------- R04 ----------------
    await rt.go(page, `#/customers/${ce.id}?tab=promises`, 5000);
    let s = await snap(page, 'r04-1-card');
    const edit = last(s.nodes, (n) => n.role === 'button' && lab(n) === 'Edit');
    if (!edit) throw new Error('no Edit button');
    await tapNode(page, edit, 3000);
    s = await snap(page, 'r04-2-dialog');
    res.r04_checkboxes = s.nodes.filter((n) => n.role === 'checkbox' || /INV-/.test(lab(n))).map(lab);
    res.r04_dialogText = s.nodes.filter((n) => /Promised .* (more|less) than/.test(lab(n))).map(lab);
    // Notes field: last text field in the dialog
    const fields = s.nodes.filter((n) => n.role === 'textbox' || /Notes/.test(lab(n)));
    res.r04_fields = fields.map((n) => `${n.role}:${lab(n)}@${n.x},${n.y}`);
    // The Notes field is unlabelled in semantics (screenshot: ~683,580); a notes-only edit.
    const notes = last(s.nodes, (n) => /Notes/.test(lab(n))) || { x: 683, y: 580 };
    await rt.clickAt(page, notes.x, notes.y, 800); await rt.typeText(page, ' + notes only edit');
    const save = last(s.nodes, (n) => n.role === 'button' && lab(n) === 'Save');
    await tapNode(page, save, 3500);
    s = await snap(page, 'r04-3-after-save');
    res.r04_after = s.nodes.filter((n) => /cancelled|Edit promise|more than|less than/.test(lab(n))).map(lab);
    res.r04_puts = puts.slice();
    res.r04_apiErrors = app.apiErrors.slice();
    res.r04_promiseAfter = brief((await get(pe.id)).json);
    const cancelBtn = last(s.nodes, (n) => n.role === 'button' && lab(n) === 'Cancel');
    if (res.r04_after.some((t) => /Edit promise/.test(t)) && cancelBtn) await tapNode(page, cancelBtn, 1500);

    // ---------------- UI11 ----------------
    await rt.go(page, `#/promises?f=${encodeURIComponent('customerId:eq:' + cr.id)}`, 5000);
    s = await snap(page, 'ui11-1-before');
    res.ui11_before = s.nodes.filter((n) => /^(Open|Kept|Partially kept|Broken)/.test(lab(n)) || /₹/.test(lab(n))).map(lab);
    const ov = last(s.nodes, (n) => n.role === 'button' && /Override status/.test(lab(n)));
    if (!ov) throw new Error('no Override status row button');
    await tapNode(page, ov, 2500);
    s = await snap(page, 'ui11-2-dialog');
    const dd = last(s.nodes, (n) => /Status/.test(lab(n)) && /Open/.test(lab(n))) || last(s.nodes, (n) => lab(n) === 'Open');
    await rt.clickAt(page, dd.x, dd.y, 1500);
    s = await snap(page, 'ui11-3-dropdown');
    const kept = last(s.nodes, (n) => lab(n) === 'Kept');
    await tapNode(page, kept, 1500);
    s = await snap(page, 'ui11-4-picked');
    // The Reason text field is unlabelled in semantics; it sits below the Status dropdown (screenshot: ~683,526).
    const reason = last(s.nodes, (n) => /Reason/.test(lab(n))) || { x: 683, y: 526 };
    await rt.clickAt(page, reason.x, reason.y, 800);
    await rt.typeText(page, 'paid by cheque, clearing');
    const okBtn = last(s.nodes, (n) => n.role === 'button' && lab(n) === 'Override');
    await tapNode(page, okBtn, 4000);
    s = await snap(page, 'ui11-5-after');
    res.ui11_api = brief((await get(pr.id)).json);
    res.ui11_after = s.nodes.filter((n) => /^(Open|Kept|Partially kept|Broken)/.test(lab(n)) || /₹/.test(lab(n))).map(lab);
    await page.waitForTimeout(4000);
    s = await snap(page, 'ui11-6-after-wait');
    res.ui11_afterWait = s.nodes.filter((n) => /^(Open|Kept|Partially kept|Broken)/.test(lab(n)) || /₹/.test(lab(n))).map(lab);
    await rt.go(page, '#/invoices', 3000);
    await rt.go(page, `#/promises?f=${encodeURIComponent('customerId:eq:' + cr.id)}`, 5000);
    s = await snap(page, 'ui11-7-after-nav');
    res.ui11_afterNav = s.nodes.filter((n) => /^(Open|Kept|Partially kept|Broken)/.test(lab(n)) || /₹/.test(lab(n))).map(lab);
    res.pageErrors = app.pageErrors;
  } catch (e) {
    res.error = String(e.stack || e);
    await rt.shot(page, D, `r${RUN}-error`);
  } finally {
    fs.writeFileSync(`${D}/ui-out-${RUN}.json`, JSON.stringify(res, null, 1));
    fs.writeFileSync(`${D}/ui-sem-${RUN}.json`, JSON.stringify(dump, null, 1));
    await app.close();
  }
  console.log(JSON.stringify(res, null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
