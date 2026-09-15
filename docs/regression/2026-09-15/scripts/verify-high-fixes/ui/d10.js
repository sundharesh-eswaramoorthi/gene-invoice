// D-10: edit a promise after one of its invoices (B) was cancelled. Writes d10.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 110)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  out['sem_' + name] = nodes.map(fmt);
  out['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}
const last = (nodes, pred) => nodes.filter(pred).slice(-1)[0];
async function tapNode(page, n, wait = 1500) {
  await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
  await page.waitForTimeout(wait);
}
const checkboxes = (page) => page.$$eval('flt-semantics[role="checkbox"]', (els) => els.map((e) => ({
  label: (e.getAttribute('aria-label') || e.textContent || '').replace(/\n/g, ' / ').slice(0, 100),
  checked: e.getAttribute('aria-checked'),
})));
const brief = (p) => p && { status: p.status, amount: p.amount, notes: p.notes, invoices: (p.invoices || []).map((i) => `${i.invoiceNumber}:${i.status}`) };

(async () => {
  const T = await rt.adminToken();
  out.fixture = { A: S.A.invoiceNumber, B: S.B.invoiceNumber, promise: S.promise.id, before: brief((await rt.api('GET', `/api/promises/${S.promise.id}`, { token: T })).json) };
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  const puts = [];
  page.on('response', async (r) => {
    if (r.request().method() === 'PUT' && r.url().includes('/api/promises/')) {
      puts.push({ status: r.status(), body: r.request().postData(), resp: (await r.text().catch(() => '')).slice(0, 300) });
    }
  });
  try {
    await rt.go(page, '#/customers');
    await rt.go(page, `#/customers/${S.c10.id}?tab=promises`, 5000);
    let n = await snap(page, 'd10-01-promise-tab');
    let edit = last(n, (x) => x.role === 'button' && lab(x) === 'Edit');
    if (!edit) throw new Error('no Edit button');
    await tapNode(page, edit, 3000);
    n = await snap(page, 'd10-02-edit-dialog');
    out.dialog1 = {
      checkboxes: await checkboxes(page),
      hints: n.map(lab).filter((t) => /more than|less than|no longer owed|Cancelled|INV-/.test(t)),
    };
    const notes = last(n, (x) => /^Notes/.test(lab(x)));
    out.notesNode = fmt(notes);
    const nx = notes ? notes.x : 683;
    const ny = notes ? notes.y : 580;
    await rt.clickAt(page, nx, ny, 800);
    await page.keyboard.press('End');
    await rt.typeText(page, ' + vu notes edit');
    await page.waitForTimeout(500);
    n = await snap(page, 'd10-03-notes-typed');
    const save = last(n, (x) => x.role === 'button' && lab(x) === 'Save');
    await tapNode(page, save, 3500);
    n = await snap(page, 'd10-04-after-save1');
    out.afterSave1 = {
      dialogStillOpen: n.some((x) => /Edit promise/.test(lab(x))),
      texts: n.map(lab).filter((t) => /cancelled|error|cannot|Edit promise|more than|less than/i.test(t)),
      promise: brief((await rt.api('GET', `/api/promises/${S.promise.id}`, { token: T })).json),
    };
    if (out.afterSave1.dialogStillOpen) {
      const c = last(n, (x) => x.role === 'button' && lab(x) === 'Cancel');
      if (c) await tapNode(page, c, 1500);
    }

    // Edit again: untick B and save.
    await rt.go(page, `#/customers/${S.c10.id}?tab=promises`, 4000);
    n = await rt.semantics(page);
    edit = last(n, (x) => x.role === 'button' && lab(x) === 'Edit');
    await tapNode(page, edit, 3000);
    n = await snap(page, 'd10-05-edit-dialog2');
    out.dialog2 = { checkboxes: await checkboxes(page), hints: n.map(lab).filter((t) => /more than|less than|no longer owed/.test(t)) };
    const bBox = n.find((x) => x.role === 'checkbox' && lab(x).includes(S.B.invoiceNumber));
    out.bNode = fmt(bBox);
    await tapNode(page, bBox, 1200);
    n = await snap(page, 'd10-06-b-unticked');
    out.dialog2AfterUntick = { checkboxes: await checkboxes(page), hints: n.map(lab).filter((t) => /more than|less than|no longer owed/.test(t)) };
    const save2 = last(n, (x) => x.role === 'button' && lab(x) === 'Save');
    await tapNode(page, save2, 3500);
    n = await snap(page, 'd10-07-after-save2');
    out.afterSave2 = {
      dialogStillOpen: n.some((x) => /Edit promise/.test(lab(x))),
      texts: n.map(lab).filter((t) => /cancelled|error|cannot|Edit promise/i.test(t)),
      promise: brief((await rt.api('GET', `/api/promises/${S.promise.id}`, { token: T })).json),
    };
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd10-error');
  } finally {
    out.puts = puts;
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd10.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(Object.fromEntries(Object.entries(out).filter(([k]) => !k.startsWith('sem_'))), null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
