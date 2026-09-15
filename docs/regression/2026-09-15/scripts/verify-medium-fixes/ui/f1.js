// Follow-ups: R1b bulk Cancel unpaid on a test invoice; W-11b scrolling the wide table sideways;
// W-15b where "Go back" from an unknown path lands; W-13b export-only role re-probe. Writes f1.json.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = {};
const snap = C.recorder(out);
const hash = (page) => page.evaluate(() => location.hash);

async function withApp(token, width, height, key, fn) {
  const app = await rt.openApp({ token, width, height });
  out[key] = out[key] || {};
  try {
    await fn(app.page, out[key]);
  } catch (e) {
    out[key].error = (out[key].error || '') + String(e.stack || e);
    await rt.shot(app.page, C.DIR, `${key.toLowerCase()}-error`);
  } finally {
    out[key].apiErrors = app.apiErrors;
    out[key].pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
}

(async () => {
  const T = await rt.adminToken();

  // ---------------- R1b bulk Cancel unpaid
  await withApp(T, 1366, 900, 'R1b', async (page, o) => {
    o.statusBefore = (await rt.api('GET', `/api/invoices/${S.invBulk.id}`, { token: T })).json?.status;
    await rt.go(page, '#/invoices', 3500);
    await rt.go(page, `#/invoices?f=customerId:eq:${S.cBulk.id}`, 5000);
    let n = await snap(page, 'R1b', 'r1b-1-list');
    const cell = n.find((x) => x.role === 'cell' && C.lab(x) === S.invBulk.invoiceNumber);
    const boxes = n.filter((x) => x.role === 'checkbox');
    o.boxes = boxes.map(C.fmt);
    const box = boxes.filter((b) => b.y > 240).sort((a, b) => Math.abs(a.y - cell.y) - Math.abs(b.y - cell.y))[0];
    await rt.clickAt(page, box.x, box.y, 1500);
    await rt.enableSemantics(page);
    n = await snap(page, 'R1b', 'r1b-2-selected');
    o.toolbar = C.has(n, /selected|Cancel unpaid|Export selected|Reassign|Clear/);
    await rt.tap(page, 'Cancel unpaid', { wait: 2000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1b', 'r1b-3-confirm');
    o.confirm = n.map(C.lab).filter((t) => /cancel|confirm|invoice/i.test(t)).slice(0, 8);
    await rt.tap(page, 'Confirm', { wait: 4000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1b', 'r1b-4-result');
    o.result = n.map(C.lab).filter((t) => /cancel|succeed|skipp|fail|done|invoice|OK|Close/i.test(t)).slice(0, 10);
    o.statusAfter = (await rt.api('GET', `/api/invoices/${S.invBulk.id}`, { token: T })).json?.status;
  });

  // ---------------- W-11b scroll the wide invoices / customers table sideways
  await withApp(T, 1366, 900, 'W11b', async (page, o) => {
    for (const p of ['invoices', 'customers']) {
      await rt.go(page, `#/${p}`, 4500);
      await snap(page, 'W11b', `w11b-${p}-1-start`);
      await page.mouse.move(800, 500);
      await page.mouse.wheel(1500, 0);
      await page.waitForTimeout(1500);
      await rt.enableSemantics(page);
      let n = await snap(page, 'W11b', `w11b-${p}-2-wheel-x`);
      const acts = (nodes) => nodes.filter((x) => x.role === 'button' && /^(Open|Raise promise|Cancel invoice)$/.test(C.lab(x)) && x.y > 240)
        .slice(0, 3).map((a) => `${C.lab(a)}@${a.x},${a.y}`);
      o[`${p}_afterWheel`] = acts(n);
      if (!o[`${p}_afterWheel`].some((a) => +a.split('@')[1].split(',')[0] < 1366)) {
        // Fall back to dragging the always-visible scrollbar thumb along the table's bottom edge.
        await rt.go(page, `#/${p}`, 4000);
        await page.mouse.move(500, 833);
        await page.mouse.down();
        await page.mouse.move(1300, 833, { steps: 12 });
        await page.mouse.up();
        await page.waitForTimeout(1500);
        await rt.enableSemantics(page);
        n = await snap(page, 'W11b', `w11b-${p}-3-drag`);
        o[`${p}_afterDrag`] = acts(n);
      }
    }
  });

  // ---------------- W-15b Go back from an unknown path
  await withApp(T, 1366, 900, 'W15b', async (page, o) => {
    await rt.go(page, '#/customers', 3500);
    await rt.go(page, '#/admin/disputes/1', 3500);
    await rt.tap(page, 'Go back', { wait: 3500 });
    await rt.enableSemantics(page);
    o.hash = await hash(page);
    o.href = await page.evaluate(() => location.href);
    const n = await snap(page, 'W15b', 'w15b-after-go-back');
    o.texts = n.map(C.lab).filter((t) => /Dashboard|Welcome|Invoices|Outstanding|does not exist/.test(t)).slice(0, 6);
  });

  // ---------------- W-13b export-only role, longer wait + dump
  {
    const et = await rt.login(S.exportUser.username, S.exportUser.password);
    await withApp(et, 1366, 900, 'W13b', async (page, o) => {
      await rt.go(page, '#/', 3000);
      await rt.go(page, '#/invoices', 7000);
      const n = await snap(page, 'W13b', 'w13b-1-invoices');
      o.checkboxes = n.filter((x) => x.role === 'checkbox').map(C.fmt);
      o.headers = n.filter((x) => x.role === 'columnheader').map(C.lab);
      o.rows = n.filter((x) => x.role === 'cell' && /^INV-/.test(C.lab(x))).length;
      o.toolbarTexts = C.has(n, /Add filter|Export|selected/);
      // The first row's checkbox, by position (the screenshot shows it at x≈290, y≈276).
      const first = n.find((x) => x.role === 'cell' && /^INV-/.test(C.lab(x)));
      await rt.clickAt(page, 290, first ? first.y : 276, 1500);
      await rt.enableSemantics(page);
      let m = await snap(page, 'W13b', 'w13b-2-selected');
      o.toolbarAfterClick = C.has(m, /selected|Export selected|Cancel unpaid|Reassign|Clear/);
      await rt.tap(page, 'Export selected', { wait: 3000 });
      await rt.enableSemantics(page);
      m = await snap(page, 'W13b', 'w13b-3-export-dialog');
      o.exportDialog = m.map(C.lab).filter((t) => /export|csv|copy|close|invoice/i.test(t)).slice(0, 10).map((t) => t.slice(0, 160));
    });
  }

  // ---------------- W-12b Disputes with the long reasons at 1920
  await withApp(T, 1920, 1080, 'W12b', async (page, o) => {
    await rt.go(page, '#/disputes', 3500);
    await rt.go(page, `#/disputes?f=customerId:eq:${S.cLong.id}`, 4500);
    const n = await snap(page, 'W12b', 'w12b-disputes-1920');
    o.headers = n.filter((x) => x.role === 'columnheader').map((h) => `${C.lab(h)}@${h.x - h.w / 2}-${h.x + h.w / 2}`);
    o.open = n.filter((x) => x.role === 'button' && C.lab(x) === 'Open').map((b) => `${b.x},${b.y}`);
  });

  C.write('f1.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
