// Exploration pass: payments list, Record payment dialog. Dumps semantics + screenshots.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const dump = async (page, name) => {
  const nodes = await rt.semantics(page);
  fs.writeFileSync(path.join(__dirname, 'shots', `${name}.sem.txt`),
    nodes.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  console.log(name, 'shot:', await rt.shot(page, __dirname, name));
  return nodes;
};
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);
  await dump(page, 'x01-list');
  await rt.tap(page, 'Record payment', { wait: 2500 });
  await dump(page, 'x02-dialog');
  await rt.tap(page, 'Record', { role: 'button', wait: 1500 });
  await dump(page, 'x03-dialog-submit-empty');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
