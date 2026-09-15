// Group 4a (tables and screens): W-10 POC picker search (D-18); W-11 desktop tables fit beside the
// rail (D-19); W-12 long dispute reason / notification message capped (D-20); W-13 export-only role
// can select rows and reach "Export selected" (D-21). Writes g4a.json.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = {};
const snap = C.recorder(out);

/** Right-most on-screen extent of the table: headers, cells and row-action buttons. */
function tableExtent(nodes, width) {
  const heads = nodes.filter((n) => n.role === 'columnheader');
  const cells = nodes.filter((n) => n.role === 'cell');
  const actions = nodes.filter((n) => n.role === 'button' && /^(Open|Raise promise|Cancel invoice|Edit|Override status)$/.test(C.lab(n)) && n.y > 200);
  const right = (n) => n.x + n.w / 2;
  return {
    headers: heads.map((h) => `${C.lab(h)}@${h.x - h.w / 2}-${right(h)}`),
    lastHeader: heads.length ? C.lab(heads.sort((a, b) => right(b) - right(a))[0]) : null,
    maxHeaderRight: Math.max(0, ...heads.map(right)),
    maxCellRight: Math.max(0, ...cells.map(right)),
    actionButtons: actions.slice(0, 6).map((a) => `${C.lab(a)}@${a.x},${a.y}`),
    actionsOnScreen: actions.length > 0 && actions.every((a) => right(a) <= width),
    width,
  };
}

async function withApp(token, width, height, fn, key) {
  const app = await rt.openApp({ token, width, height });
  try {
    await fn(app.page, app);
  } catch (e) {
    out[key] = out[key] || {};
    out[key].error = (out[key].error || '') + String(e.stack || e);
    await rt.shot(app.page, C.DIR, `${key.toLowerCase()}-error-${width}`);
  } finally {
    out[key] = out[key] || {};
    out[key][`apiErrors_${width}`] = app.apiErrors;
    out[key][`pageErrors_${width}`] = app.pageErrors.slice(0, 5);
    await app.close();
  }
}

(async () => {
  const T = await rt.adminToken();

  // ---------------- W-10 D-18: POC picker search
  out.W10 = { typed: S.sales.username };
  await withApp(T, 1366, 900, async (page) => {
    const reqs = [];
    page.on('request', (r) => { if (r.url().includes('/api/pocs/assignable')) reqs.push({ t: Date.now(), url: decodeURIComponent(r.url().replace(rt.API, '')) }); });
    await rt.go(page, '#/invoices');
    await rt.go(page, `#/invoices/${S.invPay.id}`, 4000);
    await rt.tap(page, /^Sales POC \*/, { wait: 2500 });
    await rt.enableSemantics(page);
    let n = await snap(page, 'W10', 'w10-1-picker-open');
    out.W10.listBefore = C.has(n, /@/).length;
    const t0 = Date.now();
    await page.keyboard.type(S.sales.username, { delay: 40 });
    const tTyped = Date.now();
    await page.waitForTimeout(700);
    await rt.enableSemantics(page);
    n = await snap(page, 'W10', 'w10-2-700ms');
    out.W10.list700ms = C.has(n, /@/);
    await page.waitForTimeout(2500);
    await rt.enableSemantics(page);
    n = await snap(page, 'W10', 'w10-3-3s');
    out.W10.list3s = C.has(n, /@/);
    out.W10.requests = reqs.map((r) => ({ ms: r.t - tTyped, url: r.url }));
    out.W10.typingMs = tTyped - t0;
    await rt.tap(page, 'Cancel');
  }, 'W10');

  // ---------------- W-11 D-19: tables at 1366 and 1920
  out.W11 = {};
  for (const [w, h] of [[1366, 900], [1920, 1080]]) {
    await withApp(T, w, h, async (page) => {
      const pages = w === 1366 ? ['invoices', 'customers', 'payments', 'users', 'roles', 'promises'] : ['invoices', 'customers', 'users'];
      for (const p of pages) {
        await rt.go(page, `#/${p}`, 4500);
        const n = await snap(page, 'W11', `w11-${w}-${p}`);
        out.W11[`${p}_${w}`] = tableExtent(n, w);
      }
    }, 'W11');
  }
  {
    const ct = await rt.login(S.cDisp.username, S.cDisp.password);
    await withApp(ct, 1366, 900, async (page) => {
      await rt.go(page, '#/invoices', 4500);
      const n = await snap(page, 'W11', 'w11-1366-customer-invoices');
      out.W11.customer_invoices_1366 = tableExtent(n, 1366);
    }, 'W11');
  }

  // ---------------- W-12 D-20: long dispute reason + notification message
  out.W12 = { reasonLengths: S.longReasonLen, reason1500: S.reason1500 };
  await withApp(T, 1366, 900, async (page) => {
    await rt.go(page, '#/disputes');
    await rt.go(page, `#/disputes?f=customerId:eq:${S.cLong.id}`, 4500);
    let n = await snap(page, 'W12', 'w12-1-disputes');
    out.W12.disputes = tableExtent(n, 1366);
    const cells = n.filter((x) => /wm-long-reason|wm-unbroken/.test(C.lab(x)));
    out.W12.reasonCells = cells.map((c) => `${C.lab(c).slice(0, 60)}… len=${C.lab(c).length} @${c.x},${c.y} ${c.w}x${c.h}`);
    if (cells[0]) {
      await page.mouse.move(cells[0].x, cells[0].y);
      await page.waitForTimeout(2000);
      await rt.enableSemantics(page);
      n = await snap(page, 'W12', 'w12-2-disputes-hover');
      out.W12.hoverTexts = C.has(n, /END-OF-REASON/).map((t) => t.slice(0, 60) + ' … ' + t.slice(-60));
    }
    await rt.go(page, '#/notifications', 4500);
    n = await snap(page, 'W12', 'w12-3-notifications');
    out.W12.notifications = tableExtent(n, 1366);
    const msg = n.filter((x) => /wm-long-reason|wm-unbroken/.test(C.lab(x)));
    out.W12.messageCells = msg.slice(0, 4).map((c) => `${C.lab(c).slice(0, 60)}… @${c.x},${c.y} ${c.w}x${c.h}`);
    if (msg[0]) {
      await page.mouse.move(msg[0].x, msg[0].y);
      await page.waitForTimeout(2000);
      await rt.enableSemantics(page);
      n = await snap(page, 'W12', 'w12-4-notifications-hover');
      out.W12.notificationHoverTexts = C.has(n, /END-OF-REASON/).map((t) => t.slice(0, 60) + ' … ' + t.slice(-60));
    }
  }, 'W12');

  // ---------------- W-13 D-21: INVOICE_VIEW + EXPORT_DATA, no INVOICE_MANAGE
  out.W13 = { user: S.exportUser.username };
  {
    const et = await rt.login(S.exportUser.username, S.exportUser.password);
    out.W13.me = (await rt.api('GET', '/api/auth/me', { token: et })).json?.privileges;
    await withApp(et, 1366, 900, async (page) => {
      await rt.go(page, '#/invoices', 4500);
      let n = await snap(page, 'W13', 'w13-1-invoices');
      const boxes = n.filter((x) => x.role === 'checkbox' && x.y > 240);
      out.W13.rowCheckboxes = boxes.length;
      if (boxes[0]) {
        await rt.clickAt(page, boxes[0].x, boxes[0].y, 1500);
        await rt.enableSemantics(page);
        n = await snap(page, 'W13', 'w13-2-selected');
        out.W13.toolbar = C.has(n, /selected|Export|Cancel unpaid|Reassign|Clear/);
        await rt.tap(page, 'Export selected', { wait: 3000 });
        await rt.enableSemantics(page);
        n = await snap(page, 'W13', 'w13-3-export-dialog');
        out.W13.exportDialog = n.map(C.lab).filter((t) => /export|csv|copy|close|invoice/i.test(t)).slice(0, 12).map((t) => t.slice(0, 160));
      }
    }, 'W13');
  }

  C.write('g4a.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
