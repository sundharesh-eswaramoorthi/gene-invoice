// UI reproduction, corrected: list filters use the `f` URL param. Guarded so only MY fixture is edited.
const rt = require('../lib.js');
const DIR = __dirname;
const log = (...a) => console.log(...a);
const errText = async (page) => (await rt.semantics(page))
  .filter((n) => /duplicate|already|could not|violates|error/i.test(`${n.label} ${n.text}`))
  .map((n) => (n.label || n.text).slice(0, 260));

async function tabType(page, tabs, text) {
  for (let i = 0; i < tabs; i++) { await page.keyboard.press('Tab'); await page.waitForTimeout(150); }
  await rt.typeText(page, text, { clear: true });
}

(async () => {
  const step = process.argv[2] || 'all';
  const admin = await rt.adminToken();
  const cashierRole = await rt.roleId(admin, 'CASHIER');

  // ---------------- AUR-017: edit/reactivate a user whose email is null ----------------
  if (step === 'all' || step === '017') {
    const name = rt.uniq('vaurne');
    const ne = await rt.api('POST', '/api/users', { token: admin, body: { username: name, password: rt.PASSWORD, roleId: cashierRole, active: false } });
    log('017 fixture', ne.status, 'id', ne.json?.id, 'email', JSON.stringify(ne.json?.email), 'active', ne.json?.active);
    const app = await rt.openApp({ token: admin, width: 1800, height: 1000 });
    const { page } = app;
    await rt.go(page, `#/users?f=${encodeURIComponent('username:contains:' + name)}`);
    const nodes = await rt.semantics(page);
    const all = nodes.map((n) => `${n.label || ''} ${n.text || ''}`).join(' | ');
    const mine = nodes.filter((n) => `${n.label} ${n.text}`.includes(name)).length;
    const onlyOne = /1[–-]1 of 1/.test(all);
    log('017 guard: rows with my name', mine, 'paginator 1-1 of 1:', onlyOne);
    log('017 shot', await rt.shot(page, DIR, '017b-1-list'));
    if (!mine || !onlyOne) { log('017 GUARD FAILED - not editing'); await app.close(); process.exit(2); }
    await rt.tap(page, 'Edit', { wait: 2000 });
    log('017 shot', await rt.shot(page, DIR, '017b-2-edit-open'));
    await rt.tap(page, 'Active', { role: 'switch', wait: 800 });
    log('017 shot', await rt.shot(page, DIR, '017b-3-toggled-on'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('017 shot', await rt.shot(page, DIR, '017b-4-after-save'));
    log('017 dialog error text:', JSON.stringify(await errText(page)));
    log('017 apiErrors:', JSON.stringify(app.apiErrors.filter((e) => e.url.startsWith('/api/users'))));
    const after = await rt.api('GET', `/api/users/${ne.json.id}`, { token: admin });
    log('017 after UI save: active', after.json?.active, 'email', JSON.stringify(after.json?.email));
    await app.close();
  }

  // ---------------- AUR-016: New user with a duplicate email ----------------
  if (step === 'all' || step === '016') {
    const holder = await rt.createStaff(admin, 'CASHIER', 'vaurui');
    const newName = rt.uniq('vaurui');
    const app = await rt.openApp({ token: admin, width: 1800, height: 1000 });
    const { page } = app;
    await rt.go(page, '#/users');
    await rt.tap(page, 'New user', { wait: 2000 });
    await tabType(page, 1, newName);          // Username
    await tabType(page, 1, holder.email);     // Email (duplicate)
    await tabType(page, 1, 'Dup Email');      // Full name
    await tabType(page, 1, rt.PASSWORD);      // Password
    await rt.tap(page, /^Role/, { role: 'button', wait: 1200 });
    await rt.tap(page, 'CASHIER', { wait: 1000 });
    log('016 shot', await rt.shot(page, DIR, '016b-1-filled'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('016 shot', await rt.shot(page, DIR, '016b-2-after-save'));
    log('016 dialog error text:', JSON.stringify(await errText(page)));
    log('016 apiErrors:', JSON.stringify(app.apiErrors.filter((e) => e.url.startsWith('/api/users'))));
    const chk = await rt.api('GET', `/api/users?f=x&filter=${encodeURIComponent('username:contains:' + newName)}`, { token: admin });
    log('016 user created?', (chk.json?.content || []).length);
    await app.close();
  }

  // ---------------- AUR-038: New role with an existing name ----------------
  if (step === 'all' || step === '038') {
    const app = await rt.openApp({ token: admin, width: 1800, height: 1000 });
    const { page } = app;
    await rt.go(page, '#/roles');
    await rt.tap(page, 'New role', { wait: 2000 });
    await tabType(page, 1, 'VIEWER');
    log('038 shot', await rt.shot(page, DIR, '038b-1-filled'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('038 shot', await rt.shot(page, DIR, '038b-2-after-save'));
    log('038 dialog error text:', JSON.stringify(await errText(page)));
    log('038 apiErrors:', JSON.stringify(app.apiErrors.filter((e) => e.url.startsWith('/api/roles'))));
    await app.close();
  }

  // ---------------- AUR-057: column clipping (read-only) ----------------
  if (step === 'all' || step === '057') {
    for (const [w, h] of [[1366, 900], [1920, 1080]]) {
      const app = await rt.openApp({ token: admin, width: w, height: h });
      const { page } = app;
      const pick = (nodes, re) => nodes.filter((n) => re.test(n.label || n.text || '')).slice(0, 2)
        .map((n) => `x${n.x - Math.round(n.w / 2)}..${n.x + Math.round(n.w / 2)}`);
      await rt.go(page, '#/users');
      let nodes = await rt.semantics(page);
      log(`057 @${w} users: Active`, JSON.stringify(pick(nodes, /^Active$/)), 'Edit', JSON.stringify(pick(nodes, /^Edit$/)), 'Role', JSON.stringify(pick(nodes, /^Role$/)));
      log('057 shot', await rt.shot(page, DIR, `057b-users-${w}`));
      await rt.go(page, '#/roles');
      nodes = await rt.semantics(page);
      log(`057 @${w} roles: Privileges`, JSON.stringify(pick(nodes, /^Privileg/)), 'Edit', JSON.stringify(pick(nodes, /^Edit$/)));
      log('057 shot', await rt.shot(page, DIR, `057b-roles-${w}`));
      await app.close();
    }
  }
})().catch((e) => { console.error('FATAL', e.message.slice(0, 1500)); process.exit(1); });
