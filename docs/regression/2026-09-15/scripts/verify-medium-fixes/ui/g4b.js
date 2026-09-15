// Group 4b: W-14 page past the end (D-24); W-15 malformed/unknown URLs (D-25); W-16 phone detail
// headers (D-38); W-17 dispute target text (D-39); W-18 bell badge click; regressions (row click,
// bulk Cancel unpaid, phone lists). Writes g4b.json.
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
    await rt.shot(app.page, C.DIR, `${key.toLowerCase()}-error-${width}`);
  } finally {
    out[key][`apiErrors_${width}`] = app.apiErrors;
    out[key][`pageErrors_${width}`] = app.pageErrors.slice(0, 5);
    await app.close();
  }
}

(async () => {
  const T = await rt.adminToken();

  // ---------------- W-14 D-24
  await withApp(T, 1366, 900, 'W14', async (page, o) => {
    await rt.go(page, '#/invoices', 4000);
    await rt.go(page, '#/invoices?page=99', 4500);
    let n = await snap(page, 'W14', 'w14-1-page99');
    o.page99 = C.has(n, /past the end|Go to last page|rows on|of \d|Page \d|No invoices/);
    await rt.tap(page, 'Go to last page', { wait: 4000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W14', 'w14-2-last-page');
    o.lastPage = C.has(n, /past the end|of \d|Page \d|No invoices/);
    o.lastPageRows = n.filter((x) => x.role === 'cell' && /^INV-/.test(C.lab(x))).length;
    o.hashAfter = await hash(page);
    // The defect's own repro: a 2-invoice customer, size 10, page 99.
    await rt.go(page, `#/invoices?page=99&size=10&f=customerId:eq:${S.cDisp.id}`, 4500);
    n = await snap(page, 'W14', 'w14-3-customer-page99');
    o.customerPage99 = C.has(n, /past the end|Go to last page|rows on|of \d|Page \d|No invoices/);
    await rt.tap(page, 'Go to last page', { wait: 4000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'W14', 'w14-4-customer-last');
    o.customerLast = C.has(n, /past the end|of \d|Page \d|INV-/);
  });

  // ---------------- W-15 D-25
  await withApp(T, 1366, 900, 'W15', async (page, o) => {
    const paths = ['#/invoices/abc', '#/customers/abc', '#/payments/abc', '#/invoices/12abc', '#/disputes/abc', '#/admin/disputes/1'];
    for (const p of paths) {
      await rt.go(page, '#/', 3000);
      await rt.go(page, p, 3500);
      const key = p.replace(/[#/]+/g, '_');
      const n = await snap(page, 'W15', `w15${key}`);
      const r = { texts: C.has(n, /does not exist|Go back|Not found|error/i) };
      try {
        await rt.tap(page, 'Go back', { wait: 3000 });
        r.hashAfterGoBack = await hash(page);
      } catch (e) {
        r.goBack = 'no Go back button';
      }
      o[key] = r;
    }
  });

  // ---------------- W-16 D-38 (phone 400x820)
  await withApp(T, 400, 820, 'W16', async (page, o) => {
    const header = (n) => n.filter((x) => x.y > 56 && x.y < 260).map(C.fmt);
    await rt.go(page, '#/invoices', 4000);
    await rt.go(page, `#/invoices/${S.invDisp.id}`, 4500);
    let n = await snap(page, 'W16', 'w16-1-admin-invoice-400');
    o.invoiceHeader = header(n);
    await rt.go(page, '#/customers', 4000);
    await rt.go(page, `#/customers/${S.cDisp.id}`, 4500);
    n = await snap(page, 'W16', 'w16-2-admin-customer-400');
    o.customerHeader = header(n);
    await rt.go(page, '#/payments', 4000);
    await rt.go(page, `#/payments/${S.payDisp.id}`, 4500);
    n = await snap(page, 'W16', 'w16-3-admin-payment-400');
    o.paymentHeader = header(n);
  });
  {
    const ct = await rt.login(S.cDisp.username, S.cDisp.password);
    await withApp(ct, 400, 820, 'W16c', async (page, o) => {
      await rt.go(page, '#/invoices', 4000);
      await rt.go(page, `#/invoices/${S.invDisp2.id}`, 4500);
      const n = await snap(page, 'W16c', 'w16-4-customer-invoice-400');
      o.invoiceHeader = n.filter((x) => x.y > 56 && x.y < 260).map(C.fmt);
    });
  }

  // ---------------- W-17 D-39
  await withApp(T, 1366, 900, 'W17', async (page, o) => {
    await rt.go(page, '#/disputes', 4000);
    await rt.go(page, `#/disputes?f=customerId:eq:${S.cDisp.id}`, 4500);
    let n = await snap(page, 'W17', 'w17-1-disputes-list');
    o.list = C.has(n, /Invoice INV-|Payment #|INVOICE|PAYMENT/);
    await rt.go(page, `#/disputes/${S.dispInv.id}`, 4500);
    n = await snap(page, 'W17', 'w17-2-invoice-dispute-detail');
    o.invoiceDetail = C.has(n, /Invoice INV-|INVOICE|history/i);
    await page.mouse.wheel(0, 2000);
    await page.waitForTimeout(1200);
    await rt.enableSemantics(page);
    n = await snap(page, 'W17', 'w17-3-invoice-dispute-detail-bottom');
    o.invoiceDetailBottom = C.has(n, /history/i);
    await rt.go(page, `#/disputes/${S.dispPay.id}`, 4500);
    n = await snap(page, 'W17', 'w17-4-payment-dispute-detail');
    o.paymentDetail = C.has(n, /Payment #|PAYMENT|history/i);
    await page.mouse.wheel(0, 2000);
    await page.waitForTimeout(1200);
    await rt.enableSemantics(page);
    n = await snap(page, 'W17', 'w17-5-payment-dispute-detail-bottom');
    o.paymentDetailBottom = C.has(n, /history/i);
    await rt.go(page, '#/payments', 3000);
    await rt.go(page, `#/payments/${S.payDisp.id}`, 4500);
    await rt.tap(page, 'Disputes', { role: 'tab', wait: 2500 });
    n = await snap(page, 'W17', 'w17-6-payment-disputes-tab');
    o.paymentTab = C.has(n, /Payment #|PAYMENT/);
    await rt.go(page, '#/invoices', 3000);
    await rt.go(page, `#/invoices/${S.invDisp.id}`, 4500);
    n = await snap(page, 'W17', 'w17-7-invoice-disputes-tab');
    o.invoiceTab = C.has(n, /Invoice INV-|INVOICE/);
  });
  {
    const ct = await rt.login(S.cDisp.username, S.cDisp.password);
    await withApp(ct, 1366, 900, 'W17c', async (page, o) => {
      await rt.go(page, '#/disputes', 4500);
      const n = await snap(page, 'W17c', 'w17-8-customer-disputes');
      o.list = C.has(n, /Invoice INV-|Payment #|INVOICE|PAYMENT/);
    });
  }

  // ---------------- W-18 bell badge
  for (const [w, h] of [[1366, 900], [400, 820]]) {
    await withApp(T, w, h, 'W18', async (page, o) => {
      await rt.go(page, '#/customers', 4000);
      const n = await rt.semantics(page);
      const badge = n.find((x) => /^\d+\+?$/.test(C.lab(x)) && x.y < 50);
      o[`badge_${w}`] = C.fmt(badge);
      o[`shotBefore_${w}`] = await page.screenshot({ path: `${C.DIR}/shots/w18-${w}-before.png`, clip: { x: w - 260, y: 0, width: 260, height: 56 } }) && `${C.DIR}/shots/w18-${w}-before.png`;
      if (badge) {
        await rt.clickAt(page, badge.x + 3, badge.y, 3000);
        o[`hashAfterBadgeClick_${w}`] = await hash(page);
        await rt.enableSemantics(page);
        await snap(page, 'W18', `w18-${w}-after-badge-click`);
      }
    });
  }

  // ---------------- Regressions: row click, bulk cancel, phone lists
  await withApp(T, 1366, 900, 'R1', async (page, o) => {
    await rt.go(page, '#/invoices', 3500);
    await rt.go(page, `#/invoices?f=customerId:eq:${S.cBulk.id}`, 4500);
    let n = await snap(page, 'R1', 'r1-1-bulk-customer-list');
    const cell = n.find((x) => x.role === 'cell' && C.lab(x) === S.invBulk.invoiceNumber);
    o.cell = C.fmt(cell);
    await rt.clickAt(page, cell.x, cell.y, 3500);
    o.hashAfterRowClick = await hash(page);
    await rt.go(page, `#/invoices?f=customerId:eq:${S.cBulk.id}`, 4500);
    n = await rt.semantics(page);
    const box = n.find((x) => x.role === 'checkbox' && Math.abs(x.y - cell.y) < 20);
    await rt.clickAt(page, box.x, box.y, 1500);
    await rt.enableSemantics(page);
    n = await snap(page, 'R1', 'r1-2-selected');
    o.toolbar = C.has(n, /selected|Cancel unpaid|Export selected|Reassign/);
    await rt.tap(page, 'Cancel unpaid', { wait: 2000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1', 'r1-3-confirm');
    o.confirm = n.map(C.lab).filter((t) => /cancel|confirm|invoice/i.test(t)).slice(0, 8);
    await rt.tap(page, 'Confirm', { wait: 4000 });
    await rt.enableSemantics(page);
    n = await snap(page, 'R1', 'r1-4-result');
    o.result = n.map(C.lab).filter((t) => /cancel|succeed|skipp|fail|done|invoice|OK|Close/i.test(t)).slice(0, 10);
    o.apiStatus = (await rt.api('GET', `/api/invoices/${S.invBulk.id}`, { token: T })).json?.status;
  });
  await withApp(T, 400, 820, 'R2', async (page, o) => {
    for (const p of ['invoices', 'customers', 'payments', 'disputes', 'promises']) {
      await rt.go(page, `#/${p}`, 4500);
      const n = await snap(page, 'R2', `r2-400-${p}`);
      o[p] = { nodes: n.length, errors: C.has(n, /error|failed|exception/i) };
    }
  });

  C.write('g4b.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
