// W-03 redo (D-30): Record payment with 10.555 must be refused in the dialog; 10.55 records.
// Every input's value is logged before each Record so the evidence shows what was typed where.
const rt = require('../../lib.js');
const C = require('./common.js');

const S = C.state();
const out = { W03: {} };
const snap = C.recorder(out);
const o = out.W03;

(async () => {
  const T = await rt.adminToken();
  const payments = async () => ((await rt.api('GET', `/api/payments?filter=customerId:eq:${S.cPay.id}&size=50`, { token: T })).json?.content || [])
    .map((p) => ({ id: p.id, amount: p.amount, method: p.method }));
  o.paymentsBefore = await payments();
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  const posts = [];
  page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/api/payments')) posts.push(r.postData()); });
  try {
    await rt.go(page, '#/payments');
    await rt.tap(page, 'Record payment', { wait: 2000 });
    await rt.tap(page, /^Customer/, { wait: 2000 });
    await rt.typeText(page, S.cPay.name);
    await page.waitForTimeout(2500);
    await rt.enableSemantics(page);
    await rt.tap(page, new RegExp(S.cPay.name), { wait: 4000 });
    await rt.enableSemantics(page);
    await snap(page, 'W03', 'w03b-1-customer-picked');

    await C.fill(page, /^Amount/, '10.555');
    o.inputsBefore10555 = (await C.inputs(page)).map((f) => `${f.label}=${f.value}`);
    await rt.tap(page, 'Record', { wait: 2500 });
    let n = await snap(page, 'W03', 'w03b-2-10555');
    o.after10555 = C.has(n, /decimal|Enter an amount|amount/i);
    o.postsAfter10555 = posts.length;
    o.paymentsAfter10555 = await payments();

    await C.fill(page, /^Amount/, '10.55');
    await C.fill(page, /^Method/, 'Cash');
    o.inputsBefore1055 = (await C.inputs(page)).map((f) => `${f.label}=${f.value}`);
    await rt.tap(page, 'Record', { wait: 4000 });
    n = await snap(page, 'W03', 'w03b-3-1055');
    o.dialogStillOpen = C.find(n, /^Record$/).length > 0;
    o.textsAfter1055 = C.has(n, /decimal|Enter an amount/i);
    o.posts = posts;
    o.paymentsAfter1055 = await payments();
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, C.DIR, 'w03b-error');
  } finally {
    out.fillMisses = C.fillMisses;
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  C.write('w03b.json', out);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
