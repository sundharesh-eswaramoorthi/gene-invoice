// UI 2: create a customer through the New customer dialog; duplicate username error in the dialog.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const data = JSON.parse(fs.readFileSync(path.join(D, 'ui-data.json')));
const dump = async (page, name) => {
  const s = await rt.semantics(page);
  fs.mkdirSync(path.join(D, 'sem'), { recursive: true });
  fs.writeFileSync(path.join(D, 'sem', name + '.txt'), s.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  return s;
};
const fill = async (page, x, y, text) => { await rt.clickAt(page, x, y, 400); await rt.typeText(page, text, { clear: true }); };
(async () => {
  const A = await rt.adminToken();
  const { TAG } = data;
  const newName = `${TAG} three`;
  const newUser = `${TAG}-three`;
  const app = await rt.openApp({ token: A });
  const { page } = app;
  await rt.go(page, '#/customers?f=' + encodeURIComponent(`name:contains:${TAG}`));
  await rt.tap(page, 'New customer');
  await fill(page, 683, 275, newName);
  await fill(page, 683, 323, '555-3333');
  await fill(page, 683, 371, `${newUser}@rt.local`);
  await fill(page, 683, 431, '3 Dialog Lane');
  await fill(page, 683, 529, newUser);
  await fill(page, 683, 577, 'Dlg0Pass!');
  console.log('filled shot', await rt.shot(page, D, 'u03-filled'));
  await rt.tap(page, 'Save', { wait: 3000 });
  console.log('after save shot', await rt.shot(page, D, 'u03-after-save'));
  const s = await dump(page, 'u03-after-save');
  const dialogStillOpen = s.some((n) => (n.label || n.text) === 'Login credentials (customer can sign in with these)');
  const r = await rt.api('GET', '/api/customers?filter=' + encodeURIComponent(`name:eq:${newName}`), { token: A });
  const c = r.json?.content?.[0];
  let loginOk = false; try { await rt.login(newUser, 'Dlg0Pass!'); loginOk = true; } catch {}
  console.log('RESULT create', JSON.stringify({ dialogStillOpen, found: !!c, c: c && { id: c.id, phone: c.phone, email: c.email, address: c.address, username: c.username }, loginOk,
    rowOnScreen: s.some((n) => (n.label || n.text || '').includes(newName)) }));

  // duplicate username
  await rt.tap(page, 'New customer');
  await fill(page, 683, 275, `${TAG} dupe`);
  await fill(page, 683, 529, `${TAG}-one`);
  await fill(page, 683, 577, 'x');
  await rt.tap(page, 'Save', { wait: 2500 });
  console.log('dup shot', await rt.shot(page, D, 'u04-dup'));
  const s2 = await dump(page, 'u04-dup');
  const r2 = await rt.api('GET', '/api/customers?filter=' + encodeURIComponent(`name:eq:${TAG} dupe`), { token: A });
  console.log('RESULT dup', JSON.stringify({ dialogStillOpen: s2.some((n) => (n.label || n.text) === 'Login credentials (customer can sign in with these)'),
    errorTextInSemantics: s2.filter((n) => /taken|invalid|error/i.test(n.label || n.text || '')).map((n) => n.label || n.text), persisted: r2.json?.totalElements }));
  await rt.tap(page, 'Cancel');
  console.log('after cancel shot', await rt.shot(page, D, 'u04-after-cancel'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  fs.writeFileSync(path.join(D, 'ui-data.json'), JSON.stringify({ ...data, three: c && c.id }, null, 2));
  await app.close();
})().catch((e) => { console.error('UI2 FAILED', e); process.exit(1); });
