// Verifier: DET-014 bell part (retest with the bell's own node) and HUI-007 control with the
// accessibility tree OFF (a plain mouse user) vs ON.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const { chromium } = require('playwright-core');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const res = {};
const lbl = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const hash = (page) => page.evaluate(() => location.hash);
const hasDialog = async (page) => (await rt.semantics(page)).map(lbl).join('|').includes('Discard unsaved changes?');

(async () => {
  const token = await rt.adminToken();
  // ---- bell (semantics on) ----
  const app = await rt.openApp({ token });
  const { page } = app;
  try {
    res.bell = [];
    for (const how of ['semantic-tap', 'mouse-lower-half']) {
      await rt.go(page, `#/customers/${S.cust.id}`, 4000);
      await rt.clickAt(page, 886, 289, 600);
      await page.keyboard.press('End');
      await rt.typeText(page, how === 'semantic-tap' ? '1' : '2');
      await page.waitForTimeout(600);
      if (how === 'semantic-tap') await rt.tap(page, 'Notifications', { role: 'button', wait: 2500 });
      else await rt.clickAt(page, 1166, 40, 2500);
      res.bell.push({ how, dialog: await hasDialog(page), hash: await hash(page) });
      await rt.shot(page, DIR, `k-bell-${how}`);
      if (await hasDialog(page)) await rt.tap(page, 'Discard', { wait: 1000 });
    }
  } catch (e) { res.bellError = String(e.stack || e); }
  await app.close();

  // ---- HUI-007 control: accessibility tree OFF ----
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    await ctx.addInitScript((t) => localStorage.setItem('flutter.gene_invoice_token', JSON.stringify(t)), token);
    const p2 = await ctx.newPage();
    await p2.goto(rt.WEB);
    await p2.waitForTimeout(6000);
    await p2.evaluate((h) => { location.hash = h; }, `#/invoices/${S.V1}?tab=history`);
    await p2.waitForTimeout(5000);
    res.semanticsOff = { semanticsNodes: await p2.$$eval('flt-semantics', (e) => e.length) };
    await p2.screenshot({ path: path.join(DIR, 'shots', 'k-hist-off-before.png') });
    await p2.mouse.click(1318, 683); // chevron of the first row (Dispute denied)
    await p2.waitForTimeout(1800);
    res.semanticsOff.chevronHash = await hash(p2);
    await p2.screenshot({ path: path.join(DIR, 'shots', 'k-hist-off-chevron.png') });
    await p2.evaluate((h) => { location.hash = h; }, `#/invoices/${S.V1}?tab=history`);
    await p2.waitForTimeout(4000);
    await p2.mouse.click(700, 662); // title text "Dispute denied", well away from the link
    await p2.waitForTimeout(1800);
    res.semanticsOff.titleHash = await hash(p2);
    await p2.screenshot({ path: path.join(DIR, 'shots', 'k-hist-off-title.png') });
    await p2.evaluate((h) => { location.hash = h; }, `#/invoices/${S.V1}?tab=history`);
    await p2.waitForTimeout(4000);
    await p2.mouse.click(364, 685); // the "Dispute #140" link itself
    await p2.waitForTimeout(2000);
    res.semanticsOff.linkHash = await hash(p2);
  } catch (e) { res.offError = String(e.stack || e); }
  await browser.close();

  fs.writeFileSync(path.join(DIR, 'ui-bell-a11y.json'), JSON.stringify(res, null, 2));
  console.log(JSON.stringify(res, null, 2));
})();
