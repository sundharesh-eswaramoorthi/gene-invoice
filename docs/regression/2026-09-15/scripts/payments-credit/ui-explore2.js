// Exploration pass 2: customer dropdown (50-row cap), POC pre-fill, invoices/promises in the dialog.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const inputs = (page) => page.$$eval('input, textarea', (els) => els.map((e) => {
  const r = e.getBoundingClientRect();
  return { tag: e.tagName, label: e.getAttribute('aria-label'), value: e.value, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width) };
}));
const dump = async (page, name) => {
  const nodes = await rt.semantics(page);
  const inp = await inputs(page);
  fs.writeFileSync(path.join(__dirname, 'shots', `${name}.sem.txt`),
    nodes.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n')
    + '\n--inputs--\n' + inp.map((i) => JSON.stringify(i)).join('\n'));
  console.log(name, 'shot:', await rt.shot(page, __dirname, name));
  return nodes;
};
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);
  await rt.tap(page, 'Record payment', { wait: 2500 });
  await rt.tap(page, 'Customer *', { wait: 2000 });
  let nodes = await dump(page, 'x04-dropdown-open');
  const labels = () => nodes.map((n) => n.label || n.text);
  const seen = new Set(labels());
  // scroll the menu to the bottom
  for (let i = 0; i < 12; i++) {
    await page.mouse.move(683, 450);
    await page.mouse.wheel(0, 600);
    await page.waitForTimeout(300);
  }
  await rt.enableSemantics(page);
  nodes = await dump(page, 'x05-dropdown-bottom');
  labels().forEach((l) => seen.add(l));
  const custItems = [...seen].filter((l) => l && /-mu2|smoke|tfw|cp-|inv|perm|prom|paycr/.test(l));
  console.log('dropdown items seen', custItems.length, 'has zz customer:', custItems.includes(D.czz.name), 'has cu:', custItems.includes(D.cu.name));
  console.log('last items', custItems.slice(-5));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(1000);
  await rt.enableSemantics(page);
  // pick cu
  await rt.tap(page, 'Customer *', { wait: 2000 });
  await rt.tap(page, D.cu.name, { wait: 3500 });
  await dump(page, 'x06-customer-selected');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
