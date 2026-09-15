// UI Roles screen, continued: edit privileges via row click, duplicate name message, delete control.
const rt = require('../lib.js');
const D = __dirname;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
const checked = (page) => page.$$eval('flt-semantics[role=checkbox]', (els) => els.filter((e) => e.getAttribute('aria-checked') === 'true').map((e) => (e.getAttribute('aria-label') || e.textContent).trim()));
const getRole = async (admin, name) => (await rt.api('GET', `/api/roles?size=50&filter=name:eq:${name}`, { token: admin })).json.content[0];
async function fill(page, label, text) {
  const p = await page.$$eval('input', (els, l) => {
    const e = els.find((x) => x.getAttribute('aria-label') === l);
    if (!e) return null;
    const r = e.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }, label);
  if (!p) throw new Error('no input labelled ' + label);
  await rt.clickAt(page, p.x, p.y, 300);
  await rt.typeText(page, text, { clear: true });
}
(async () => {
  const admin = await rt.adminToken();
  const name = rt.uniq('AUR_UI');
  const c = await rt.api('POST', '/api/roles', { token: admin, body: { name, description: 'ui role', privileges: ['INVOICE_VIEW', 'NOTIFICATION_VIEW', 'EXPORT_DATA'] } });
  console.log('setup role', c.status, c.json?.id);
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/roles');
  const n = await rt.find(page, name);
  await rt.clickAt(page, n.x, n.y, 1800);
  await rt.enableSemantics(page);
  const t0 = await texts(page);
  const pre = (await checked(page)).sort();
  console.log('   edit dialog title =', t0.includes('Edit role'), '| pre-ticked:', pre.join(','));
  await rt.shot(page, D, 'ui-roles-3a-edit-open');
  await rt.tap(page, 'EXPORT_DATA', { role: 'checkbox', wait: 400 });
  await rt.tap(page, 'CUSTOMER_VIEW', { role: 'checkbox', wait: 400 });
  await fill(page, 'Description', 'ui role edited');
  console.log('   ticked before save:', (await checked(page)).sort().join(','));
  await rt.tap(page, 'Save', { wait: 2500 });
  const r = await getRole(admin, name);
  const rowY = (await rt.find(page, name)).y;
  const row = (await rt.semantics(page)).filter((x) => Math.abs(x.y - rowY) < 5).map((x) => x.label || x.text);
  console.log('R3 edit: pre-ticked correct =', JSON.stringify(pre) === JSON.stringify(['EXPORT_DATA', 'INVOICE_VIEW', 'NOTIFICATION_VIEW']),
    '| API after =', JSON.stringify(r.privileges), r.description, '| row =', row.join(' | '), await rt.shot(page, D, 'ui-roles-3-edited'));

  // R4 duplicate name -> what the user sees
  await rt.tap(page, 'New role');
  await fill(page, 'Name', name);
  await rt.tap(page, 'Save', { wait: 2000 });
  const t = await texts(page);
  console.log('R4 duplicate role name: dialog text =', t.filter((x) => /duplicate|constraint|exists|statement|error|failed/i.test(x)).join(' / ').slice(0, 400), await rt.shot(page, D, 'ui-roles-4-dup'));
  await rt.tap(page, 'Cancel');
  // R5 any delete control on the roles screen / edit dialog?
  const listHasDelete = (await texts(page)).some((x) => /delete|remove/i.test(x));
  await rt.clickAt(page, n.x, n.y, 1500); await rt.enableSemantics(page);
  const dlgHasDelete = (await texts(page)).some((x) => /delete|remove/i.test(x));
  console.log('R5 delete control: list =', listHasDelete, 'edit dialog =', dlgHasDelete);
  await rt.tap(page, 'Cancel');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  console.log('cleanup delete role', (await rt.api('DELETE', `/api/roles/${r.id}`, { token: admin })).status);
  // earlier ui-roles.js run left AUR_UI role 47 behind
  const left = (await rt.api('GET', '/api/roles?size=50&filter=name:contains:AUR_UI', { token: admin })).json.content;
  for (const x of left) console.log('cleanup leftover', x.name, (await rt.api('DELETE', `/api/roles/${x.id}`, { token: admin })).status);
})().catch((e) => { console.error(e); process.exit(1); });
