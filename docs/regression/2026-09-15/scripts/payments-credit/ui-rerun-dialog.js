// Rerun of the dialog steps that failed on the picker: candidate 36 and editing the pre-filled POC.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const { make } = require('./uilib.js');
const D = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-data.json')));
const log = [];
(async () => {
  const admin = await rt.adminToken();
  const api = (m, p, body) => rt.api(m, p, { token: admin, body });
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  const u = make(page, __dirname, log);
  await rt.go(page, `#/payments?size=10&f=customerId:eq:${D.cu.id}`);

  await u.step('candidate 36: POC picked before the customer', async () => {
    await rt.tap(page, 'Record payment', { wait: 2500 });
    await u.pickPoc(D.pocU2, 'r1');
    u.note('POC after manual pick', await u.has(/^Collection POC \*/));
    await u.shot('r1-poc-picked');
    await rt.tap(page, 'Customer *', { wait: 2000 });
    await rt.tap(page, D.cu.name, { wait: 3500 });
    await rt.enableSemantics(page);
    u.note('POC after then choosing the customer', await u.has(/^Collection POC \*/));
    await u.shot('r2-poc-then-customer');
  });

  await u.step('edit the pre-filled POC and record', async () => {
    const cur = (await u.has(/^Collection POC \*/))[0] || '';
    if (!cur.includes(D.pocU2.fullName)) await u.pickPoc(D.pocU2, 'r3');
    u.note('POC before record', await u.has(/^Collection POC \*/));
    await u.clickInput((x) => x.label === 'Amount *');
    await rt.typeText(page, '3', { clear: true });
    await u.clickInput((x) => x.label === 'Notes');
    await rt.typeText(page, 'ui poc2', { clear: true });
    await u.shot('r3-before-record');
    await rt.tap(page, 'Record', { role: 'button', wait: 3500 });
    const list = (await api('GET', `/api/payments?filter=customerId:eq:${D.cu.id}&sort=id,desc&size=10`)).json.content;
    const p = list.find((x) => x.notes === 'ui poc2');
    u.note('API payment recorded with the edited POC', p && { id: p.id, poc: p.collectionPoc?.username, expected: D.pocU2.username,
      alloc: p.invoices.map((i) => [i.invoiceNumber, i.allocatedAmount]) });
    await rt.enableSemantics(page);
    await u.shot('r4-after-record');
  });

  u.note('apiErrors', app.apiErrors);
  u.note('pageErrors', app.pageErrors);
  fs.writeFileSync(path.join(__dirname, 'ui-rerun-dialog-log.json'), JSON.stringify(log, null, 2));
  await app.close();
})().catch((e) => { console.error('ERR', e.message.slice(0, 2500)); process.exit(1); });
