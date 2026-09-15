// UI: Roles screen - create a role with chosen privileges, edit privileges, duplicate name.
const rt = require('../lib.js');
const D = __dirname;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
const checked = (page) => page.$$eval('flt-semantics[role=checkbox]', (els) => els.filter((e) => e.getAttribute('aria-checked') === 'true').map((e) => e.getAttribute('aria-label') || e.textContent.trim()));
const getRole = async (admin, name) => (await rt.api('GET', `/api/roles?size=50&filter=name:eq:${name}`, { token: admin })).json.content[0];
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/roles');
  await rt.tap(page, 'New role');
  await rt.tap(page, 'Save', { wait: 1000 });
  let t = await texts(page);
  console.log('R1 empty name: Required =', t.includes('Required'), await rt.shot(page, D, 'ui-roles-1-empty'));
  const name = rt.uniq('AUR_UI');
  await rt.clickAt(page, 687, 119, 300); await rt.typeText(page, name);
  await rt.clickAt(page, 687, 167, 300); await rt.typeText(page, 'ui created role');
  for (const p of ['INVOICE_VIEW', 'NOTIFICATION_VIEW', 'EXPORT_DATA']) await rt.tap(page, p, { role: 'checkbox', wait: 400 });
  console.log('   ticked:', (await checked(page)).join(','));
  await rt.shot(page, D, 'ui-roles-2a-filled');
  await rt.tap(page, 'Save', { wait: 2500 });
  let r = await getRole(admin, name);
  t = await texts(page);
  console.log('R2 create: API =', JSON.stringify(r), '| listed =', t.includes(name), await rt.shot(page, D, 'ui-roles-2-created'));

  // R3 edit: form pre-ticks privileges; untick EXPORT_DATA, tick CUSTOMER_VIEW
  await rt.tap(page, name, { wait: 1500 });
  const pre = await checked(page);
  console.log('   edit dialog pre-ticked:', pre.join(','));
  await rt.shot(page, D, 'ui-roles-3a-edit-open');
  await rt.tap(page, 'EXPORT_DATA', { role: 'checkbox', wait: 400 });
  await rt.tap(page, 'CUSTOMER_VIEW', { role: 'checkbox', wait: 400 });
  await rt.tap(page, 'Save', { wait: 2500 });
  r = await getRole(admin, name);
  t = await texts(page);
  const rowY = t.includes(name) ? (await rt.find(page, name)).y : -999;
  const row = (await rt.semantics(page)).filter((n) => Math.abs(n.y - rowY) < 5).map((n) => n.label || n.text);
  console.log('R3 edit: pre-ticked matches =', JSON.stringify(pre.sort()) === JSON.stringify(['EXPORT_DATA', 'INVOICE_VIEW', 'NOTIFICATION_VIEW']),
    '| API after =', JSON.stringify(r.privileges), '| row =', row.join(' | '), await rt.shot(page, D, 'ui-roles-3-edited'));

  // R4 duplicate name -> what the user sees
  await rt.tap(page, 'New role');
  await rt.clickAt(page, 687, 119, 300); await rt.typeText(page, name);
  await rt.tap(page, 'Save', { wait: 2000 });
  t = await texts(page);
  console.log('R4 duplicate role name: message =', t.filter((x) => /duplicate|constraint|exists|statement|error/i.test(x)).join(' / ').slice(0, 300), await rt.shot(page, D, 'ui-roles-4-dup'));
  await rt.tap(page, 'Cancel');
  // R5 no delete action offered in UI
  t = await texts(page);
  console.log('R5 delete control on roles screen =', has(t));
  function has(arr) { return arr.some((x) => /delete|remove/i.test(x)); }
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  console.log('cleanup delete role', (await rt.api('DELETE', `/api/roles/${r.id}`, { token: admin })).status);
})().catch((e) => { console.error(e); process.exit(1); });
