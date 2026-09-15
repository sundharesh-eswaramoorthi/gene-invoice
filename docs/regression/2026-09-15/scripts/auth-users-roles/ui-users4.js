// UI Users screen, clean rerun: Active switch (U9) and VIEWER edit preselect (U11) with users that HAVE an
// email (so the empty-email defect does not mask them), plus wide columns (U10) and cleanup.
const rt = require('../lib.js');
const D = __dirname;
const P = rt.PASSWORD;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
const findUser = async (admin, username) => (await rt.api('GET', `/api/users?filter=username:eq:${username}`, { token: admin })).json.content[0];
const switchState = (page) => page.$$eval('flt-semantics[role=switch]', (els) => els.map((e) => `${(e.getAttribute('aria-label') || e.textContent).trim()}=${e.getAttribute('aria-checked')}`).join(','));
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
async function openRow(page, label) {
  const n = await rt.find(page, label);
  await rt.clickAt(page, n.x, n.y, 1800);
  await rt.enableSemantics(page);
}
(async () => {
  const admin = await rt.adminToken();
  // cleanup of users left behind by the crashed ui-users3 run (own prefixes only)
  for (const pre of ['aaaurw-', 'aaaurv-']) {
    const left = (await rt.api('GET', `/api/users?size=50&filter=username:contains:${pre}`, { token: admin })).json.content;
    for (const x of left) console.log('cleanup', x.username, (await rt.api('DELETE', `/api/users/${x.id}`, { token: admin })).status);
  }
  const inactive = rt.uniq('aaaurw');
  const iu = await rt.api('POST', '/api/users', { token: admin, body: { username: inactive, email: `${inactive}@rt.local`, password: P, fullName: 'Inactive One', roleId: await rt.roleId(admin, 'CASHIER'), active: false } });
  const vname = rt.uniq('aaaurv');
  const vu = await rt.api('POST', '/api/users', { token: admin, body: { username: vname, email: `${vname}@rt.local`, password: P, fullName: 'Viewer Edit', roleId: await rt.roleId(admin, 'VIEWER') } });
  console.log('setup', iu.status, iu.json?.active, vu.status);
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/users');

  // U9 re-activate an inactive user with the Active switch
  await openRow(page, inactive);
  console.log('   dialog open =', (await texts(page)).includes('Edit user'), '| switch before:', await switchState(page));
  await rt.tap(page, 'Active', { role: 'switch', wait: 700 });
  console.log('   switch after tap:', await switchState(page));
  await rt.tap(page, 'Save', { wait: 2500 });
  const u = await findUser(admin, inactive);
  const lg = await rt.api('POST', '/api/auth/login', { body: { username: inactive, password: P } });
  const dlg = (await texts(page)).filter((x) => /statement|error|constraint/i.test(x));
  console.log('U9 Active switch -> API active =', u.active, '| login', lg.status, '| dialog error:', dlg.join(' ').slice(0, 120), await rt.shot(page, D, 'ui-users-9b-after'));

  // U11 edit a VIEWER user: preselected role and role after save
  await openRow(page, vname);
  const t = await texts(page);
  console.log('   viewer edit dialog role node:', t.filter((x) => /^Role/.test(x)).join(' / ').replace(/\n/g, ' '));
  await rt.shot(page, D, 'ui-users-11a-edit-viewer');
  await fill(page, 'Full name', 'Viewer Edited');
  await rt.tap(page, 'Save', { wait: 2500 });
  const va = await findUser(admin, vname);
  console.log('U11 edit VIEWER user (only full name changed): API role after save =', va.role, 'fullName =', va.fullName);

  // U10 wide columns at 1366px
  const hdr = (await rt.semantics(page)).filter((n) => n.role === 'columnheader').map((n) => `${n.label || n.text}@x${n.x} w${n.w}`);
  console.log('U10 headers:', hdr.join(', '));
  await page.mouse.move(800, 500);
  await page.mouse.wheel(600, 0); await page.waitForTimeout(900);
  await rt.shot(page, D, 'ui-users-10-scrolled');
  const hdr2 = (await rt.semantics(page)).filter((n) => n.role === 'columnheader').map((n) => `${n.label || n.text}@x${n.x}`);
  console.log('U10 headers after horizontal wheel:', hdr2.join(', '));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
  for (const id of [iu.json.id, vu.json.id]) console.log('cleanup', id, (await rt.api('DELETE', `/api/users/${id}`, { token: admin })).status);
})().catch((e) => { console.error(e); process.exit(1); });
