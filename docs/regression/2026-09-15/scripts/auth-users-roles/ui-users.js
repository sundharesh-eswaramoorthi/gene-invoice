// UI: Users screen - list, New user in toolbar, create/edit form, validation, deactivate.
const rt = require('../lib.js');
const D = __dirname;
const P = rt.PASSWORD;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
const has = (arr, re) => arr.some((t) => re.test(t));
const findUser = async (admin, username) => (await rt.api('GET', `/api/users?filter=username:eq:${username}`, { token: admin })).json.content[0];
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
async function pickRole(page, role) {
  await rt.tap(page, 'Role', { wait: 1200 });
  await rt.tap(page, role, { role: 'menuitem', wait: 900 });
}
(async () => {
  const admin = await rt.adminToken();
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/users');
  const nb = await rt.find(page, 'New user');
  console.log('U1 New user button at', nb.x, nb.y, await rt.shot(page, D, 'ui-users-1-list'));

  // U2 empty form
  await rt.tap(page, 'New user');
  await rt.tap(page, 'Save', { wait: 1000 });
  let t = await texts(page);
  console.log('U2 empty save: Required count =', t.filter((x) => x === 'Required').length, await rt.shot(page, D, 'ui-users-2-empty'));
  await rt.tap(page, 'Cancel');

  // U3 no role picked
  const uname = rt.uniq('aaaurui');
  await rt.tap(page, 'New user');
  await fill(page, 'Username', uname);
  await fill(page, 'Email', `${uname}@rt.local`);
  await fill(page, 'Full name', 'UI Created');
  await fill(page, 'Password', P);
  await rt.tap(page, 'Save', { wait: 1000 });
  t = await texts(page);
  console.log('U3 no role: "Pick a role" =', has(t, /Pick a role/), await rt.shot(page, D, 'ui-users-3-norole'));
  // U4 pick CASHIER and save
  await pickRole(page, 'CASHIER');
  await rt.shot(page, D, 'ui-users-4a-role-picked');
  await rt.tap(page, 'Save', { wait: 2500 });
  let u = await findUser(admin, uname);
  t = await texts(page);
  console.log('U4 create: API user =', JSON.stringify(u && { id: u.id, role: u.role, active: u.active, fullName: u.fullName, email: u.email }),
    '| row visible in list =', t.includes(uname), '| dialog gone =', !t.includes('Pick a role') && !t.includes('Cancel'), await rt.shot(page, D, 'ui-users-4-created'));
  console.log('   new user can log in:', (await rt.api('POST', '/api/auth/login', { body: { username: uname, password: P } })).status);

  // U5 duplicate username
  await rt.tap(page, 'New user');
  await fill(page, 'Username', uname);
  await fill(page, 'Password', P);
  await pickRole(page, 'CASHIER');
  await rt.tap(page, 'Save', { wait: 2000 });
  t = await texts(page);
  console.log('U5 duplicate username: dialog text =', t.filter((x) => !/^(Dismiss|New user|Role|Active|Cancel|Save|CASHIER)$/.test(x)).join(' / ').slice(0, 300), await rt.shot(page, D, 'ui-users-5-dup'));
  await rt.tap(page, 'Cancel');

  // U6 duplicate email -> what the user sees
  await rt.tap(page, 'New user');
  await fill(page, 'Username', rt.uniq('aaaurui'));
  await fill(page, 'Email', `${uname}@rt.local`);
  await fill(page, 'Password', P);
  await pickRole(page, 'CASHIER');
  await rt.tap(page, 'Save', { wait: 2000 });
  t = await texts(page);
  console.log('U6 duplicate email: dialog text =', t.filter((x) => !/^(Dismiss|New user|Role|Active|Cancel|Save|CASHIER)$/.test(x)).join(' / ').slice(0, 400), await rt.shot(page, D, 'ui-users-6-dupemail'));
  await rt.tap(page, 'Cancel');

  // U7 edit via row tap: change full name
  await rt.tap(page, uname, { wait: 1500 });
  const ins = await page.$$eval('input', (els) => els.map((e) => `${e.getAttribute('aria-label')} disabled=${e.disabled}`));
  console.log('   edit inputs:', ins.join(', '), '| nodes:', (await texts(page)).join(' | ').slice(0, 200));
  await rt.shot(page, D, 'ui-users-7a-edit-open');
  await fill(page, 'Full name', 'UI Edited Name');
  await rt.tap(page, 'Save', { wait: 2500 });
  u = await findUser(admin, uname);
  t = await texts(page);
  console.log('U7 edit: API fullName =', u.fullName, 'role =', u.role, '| list shows new name =', t.includes('UI Edited Name'), await rt.shot(page, D, 'ui-users-7-edited'));

  // U8 deactivate via row checkbox + bulk Deactivate
  const cell = await rt.find(page, uname);
  await rt.clickAt(page, 298, cell.y, 1200);
  t = await texts(page);
  console.log('   toolbar:', t.filter((x) => /selected|Activate|Deactivate|Export|Clear/.test(x)).join(' | '));
  await rt.shot(page, D, 'ui-users-8a-selected');
  await rt.tap(page, 'Deactivate', { wait: 1200 });
  let nodes = await rt.semantics(page);
  console.log('   confirm dialog:', nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\n/g, '/')}`).join(' | ').slice(0, 400));
  await rt.shot(page, D, 'ui-users-8b-confirm');
  const btns = nodes.filter((n) => n.role === 'button' && !/^(Cancel|Dismiss)$/.test(n.label || n.text));
  await rt.clickAt(page, btns.at(-1).x, btns.at(-1).y, 2500);
  await rt.enableSemantics(page);
  nodes = await rt.semantics(page);
  console.log('   result:', nodes.map((n) => `[${n.role}] ${(n.label || n.text).replace(/\n/g, '/')}`).join(' | ').slice(0, 500));
  await rt.shot(page, D, 'ui-users-8c-result');
  u = await findUser(admin, uname);
  console.log('U8 bulk deactivate: API active =', u.active, '| login now', (await rt.api('POST', '/api/auth/login', { body: { username: uname, password: P } })).status);
  const closeBtn = nodes.filter((n) => n.role === 'button' && /^(Close|OK|Done)$/.test(n.label || n.text)).at(-1);
  if (closeBtn) await rt.clickAt(page, closeBtn.x, closeBtn.y, 1500);
  await rt.enableSemantics(page);
  t = await texts(page);
  const rowY = (await rt.find(page, uname)).y;
  console.log('   row after:', (await rt.semantics(page)).filter((n) => Math.abs(n.y - rowY) < 5).map((n) => n.label || n.text).join(' | '));
  await rt.shot(page, D, 'ui-users-8d-after');

  // U9 re-activate via edit form Active switch
  await rt.tap(page, uname, { wait: 1500 });
  await rt.tap(page, 'Active', { wait: 600 });
  await rt.tap(page, 'Save', { wait: 2500 });
  u = await findUser(admin, uname);
  console.log('U9 edit-form Active switch: API active =', u.active);

  // U11 edit a VIEWER user whose role may sit outside the first 50 roles the form loads
  const vname = rt.uniq('aaaurv');
  const v = await rt.api('POST', '/api/users', { token: admin, body: { username: vname, password: P, fullName: 'Viewer Edit', roleId: await rt.roleId(admin, 'VIEWER') } });
  await rt.go(page, '#/users');
  await rt.tap(page, vname, { wait: 1800 });
  nodes = await rt.semantics(page);
  const roleNode = nodes.find((n) => /Role/.test(n.label || ''));
  console.log('   edit dialog role node:', JSON.stringify(roleNode && (roleNode.label + ' / ' + roleNode.text)));
  await rt.shot(page, D, 'ui-users-11a-edit-viewer');
  await fill(page, 'Full name', 'Viewer Edited');
  await rt.tap(page, 'Save', { wait: 2500 });
  const va = await findUser(admin, vname);
  console.log('U11 edit VIEWER user (only full name changed): API role after save =', va.role, 'fullName =', va.fullName);
  if (va.role !== 'VIEWER') await rt.api('PUT', `/api/users/${va.id}`, { token: admin, body: { roleId: await rt.roleId(admin, 'VIEWER') } }).catch(() => {});
  await rt.api('DELETE', `/api/users/${va.id}`, { token: admin });

  // U10 horizontal reach of Active column / Edit icon at 1366px
  const hdr = (await rt.semantics(page)).filter((n) => n.role === 'columnheader').map((n) => `${n.label || n.text}@x${n.x} w${n.w}`);
  console.log('U10 headers:', hdr.join(', '));
  await page.mouse.move(800, 500);
  await page.keyboard.down('Shift'); await page.mouse.wheel(0, 600); await page.keyboard.up('Shift');
  await page.waitForTimeout(800);
  await rt.shot(page, D, 'ui-users-10-scrolled');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
