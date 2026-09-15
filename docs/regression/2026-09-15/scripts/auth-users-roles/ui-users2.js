// UI Users screen, continued: edit via row click, bulk deactivate, Active switch, VIEWER edit, wide columns.
const rt = require('../lib.js');
const D = __dirname;
const P = rt.PASSWORD;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
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
/** Real mouse click on a row's cell: a DataCell's onTap does not fire from a semantics click. */
async function openRow(page, label) {
  const n = await rt.find(page, label);
  await rt.clickAt(page, n.x, n.y, 1800);
  await rt.enableSemantics(page);
}
(async () => {
  const admin = await rt.adminToken();
  const cashierRole = await rt.roleId(admin, 'CASHIER');
  const uname = rt.uniq('aaaurui');
  const created = await rt.api('POST', '/api/users', { token: admin, body: { username: uname, email: `${uname}@rt.local`, fullName: 'UI Created', password: P, roleId: cashierRole } });
  console.log('setup user', created.status, created.json?.id);
  const app = await rt.openApp({ token: admin });
  const { page } = app;
  await rt.go(page, '#/users');

  // U7 edit via row click: change full name
  await openRow(page, uname);
  const ins = await page.$$eval('input', (els) => els.map((e) => `${e.getAttribute('aria-label')}${e.disabled ? '(disabled)' : ''}`));
  let t = await texts(page);
  console.log('   edit dialog: title =', t.includes('Edit user'), '| inputs:', ins.join(', '), '| role node:', t.filter((x) => /^Role/.test(x)).join(' / ').replace(/\n/g, ' '));
  await rt.shot(page, D, 'ui-users-7a-edit-open');
  await fill(page, 'Full name', 'UI Edited Name');
  await rt.tap(page, 'Save', { wait: 2500 });
  let u = await findUser(admin, uname);
  t = await texts(page);
  console.log('U7 edit: API fullName =', u.fullName, 'role =', u.role, '| list shows new name =', t.includes('UI Edited Name'), await rt.shot(page, D, 'ui-users-7-edited'));

  // U8 deactivate via row checkbox + bulk Deactivate
  const cell = await rt.find(page, uname);
  await rt.clickAt(page, 298, cell.y, 1200);
  await rt.enableSemantics(page);
  t = await texts(page);
  console.log('   toolbar:', t.filter((x) => /selected|Activate|Deactivate|Export|Clear|matching/.test(x)).join(' | '));
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
  const rowY = (await rt.find(page, uname)).y;
  console.log('   row after:', (await rt.semantics(page)).filter((n) => Math.abs(n.y - rowY) < 5).map((n) => n.label || n.text).join(' | '));
  await rt.shot(page, D, 'ui-users-8d-after');

  // U9 re-activate via edit form Active switch
  await openRow(page, uname);
  await rt.tap(page, 'Active', { role: 'switch', wait: 600 });
  await rt.tap(page, 'Save', { wait: 2500 });
  u = await findUser(admin, uname);
  console.log('U9 edit-form Active switch: API active =', u.active);

  // U11 edit a VIEWER user: which role does the form preselect, and does saving keep it?
  const vname = rt.uniq('aaaurv');
  await rt.api('POST', '/api/users', { token: admin, body: { username: vname, password: P, fullName: 'Viewer Edit', roleId: await rt.roleId(admin, 'VIEWER') } });
  await rt.go(page, '#/users');
  await openRow(page, vname);
  t = await texts(page);
  console.log('   viewer edit dialog role node:', t.filter((x) => /^Role/.test(x)).join(' / ').replace(/\n/g, ' '));
  await rt.shot(page, D, 'ui-users-11a-edit-viewer');
  await fill(page, 'Full name', 'Viewer Edited');
  await rt.tap(page, 'Save', { wait: 2500 });
  const va = await findUser(admin, vname);
  console.log('U11 edit VIEWER user (only full name changed): API role after save =', va.role, 'fullName =', va.fullName);
  await rt.api('DELETE', `/api/users/${va.id}`, { token: admin });

  // U10 horizontal reach of Active column / Edit icon at 1366px
  const hdr = (await rt.semantics(page)).filter((n) => n.role === 'columnheader').map((n) => `${n.label || n.text}@x${n.x} w${n.w}`);
  console.log('U10 headers:', hdr.join(', '));
  await rt.shot(page, D, 'ui-users-10a-before-scroll');
  await page.mouse.move(800, 500);
  await page.keyboard.down('Shift'); await page.mouse.wheel(0, 600); await page.keyboard.up('Shift');
  await page.waitForTimeout(800);
  await page.mouse.wheel(600, 0); await page.waitForTimeout(800);
  await rt.shot(page, D, 'ui-users-10-scrolled');
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
