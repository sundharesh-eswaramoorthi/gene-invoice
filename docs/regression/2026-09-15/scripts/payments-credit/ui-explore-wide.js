// Exploration: list at 1920 and 400 px, and Payment Details for the seeded payment.
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
};
(async () => {
  const admin = await rt.adminToken();
  let app = await rt.openApp({ token: admin, width: 1920, height: 1000 });
  await rt.go(app.page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);
  await dump(app.page, 'w01-list-1920');
  // 1366: try a horizontal scroll over the table
  await app.page.setViewportSize({ width: 1366, height: 900 });
  await app.page.waitForTimeout(1500);
  await app.page.mouse.move(800, 300);
  await app.page.keyboard.down('Shift');
  await app.page.mouse.wheel(0, 600);
  await app.page.keyboard.up('Shift');
  await app.page.mouse.wheel(600, 0);
  await app.page.waitForTimeout(1000);
  await rt.enableSemantics(app.page);
  await dump(app.page, 'w02-list-1366-hscroll');
  await rt.go(app.page, `#/payments/${D.pay.id}`);
  await dump(app.page, 'w03-detail-1366');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  app = await rt.openApp({ token: admin, width: 400, height: 860 });
  await rt.go(app.page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);
  await dump(app.page, 'w04-list-400');
  await rt.go(app.page, `#/payments/${D.pay.id}`);
  await dump(app.page, 'w05-detail-400');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
