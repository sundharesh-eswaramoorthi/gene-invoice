// Probe: is the "Save changes" row painted on Payment Details? Compare with Invoice/Customer Details.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const { make } = require('./uilib.js');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const log = [];
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const u = make(page, __dirname, log);
  const node = async (re) => (await rt.semantics(page)).filter((n) => re.test(n.label || n.text || '')).map((n) => ({ l: (n.label || n.text).slice(0, 30), x: n.x, y: n.y, h: n.h }));

  await u.step('invoice detail for comparison', async () => {
    await rt.go(page, `#/invoices/${D.I1.id}`, 4500);
    u.note('invoice Save node', await node(/Save changes/));
    await u.shot('p01-invoice-detail');
  });
  await u.step('customer detail for comparison', async () => {
    await rt.go(page, `#/customers/${D.cu.id}`, 4500);
    u.note('customer Save node', await node(/Save/));
    await u.shot('p02-customer-detail');
  });
  await u.step('payment detail, hover + Tab focus on Save', async () => {
    await rt.go(page, `#/payments/${D.pay.id}`, 4500);
    await u.clickInput((x) => x.tag === 'TEXTAREA');
    await rt.typeText(page, 'probe', { clear: true });
    await page.keyboard.press('Tab'); // move focus off the notes field, towards the Save button
    await page.waitForTimeout(600);
    await page.keyboard.press('Tab');
    await page.waitForTimeout(600);
    await rt.enableSemantics(page);
    const s = await node(/Save changes|Unsaved/);
    u.note('payment Save/Unsaved nodes', s);
    const save = s.find((x) => /Save/.test(x.l));
    if (save) await page.mouse.move(save.x - 400, save.y);
    await page.waitForTimeout(600);
    await u.shot('p03-payment-dirty-hover-tab');
  });
  await u.step('payment detail at 1920x1300', async () => {
    await page.setViewportSize({ width: 1920, height: 1300 });
    await page.waitForTimeout(2000);
    await rt.enableSemantics(page);
    u.note('payment Save/Unsaved nodes @1920x1300', await node(/Save changes|Unsaved/));
    await u.shot('p04-payment-dirty-1920');
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.waitForTimeout(800);
    await rt.tap(page, 'Back', { wait: 1200 });
    await rt.tap(page, 'Discard', { wait: 1500 });
  });
  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-probe-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
