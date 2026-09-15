// UI reproduction: AUR-017 (edit no-email user), AUR-016 (dup email dialog), AUR-038 (dup role dialog),
// AUR-057 (column clipping at 1366px). Own data only.
const rt = require('../lib.js');
const DIR = __dirname;
const log = (...a) => console.log(...a);
const errText = async (page) => (await rt.semantics(page))
  .filter((n) => /duplicate|already|could not|error|exists|violates/i.test(`${n.label} ${n.text}`))
  .map((n) => (n.label || n.text).slice(0, 220));

async function fill(page, label, text) {
  const n = await rt.find(page, label);
  await rt.clickAt(page, n.x, n.y, 400);
  await rt.typeText(page, text, { clear: true });
}

(async () => {
  const admin = await rt.adminToken();
  const cashierRole = await rt.roleId(admin, 'CASHIER');
  const step = process.argv[2] || 'all';

  // Own fixtures
  const noEmailName = rt.uniq('vaurui');
  const ne = await rt.api('POST', '/api/users', { token: admin, body: { username: noEmailName, password: rt.PASSWORD, roleId: cashierRole, active: false } });
  log('fixture no-email inactive user', ne.status, ne.json?.id, 'email=', JSON.stringify(ne.json?.email), 'active=', ne.json?.active);
  const holder = await rt.createStaff(admin, 'CASHIER', 'vaurui');

  // ---------------- AUR-017 ----------------
  if (step === 'all' || step === '017') {
    const app = await rt.openApp({ token: admin, width: 1800, height: 1000 });
    const { page } = app;
    await rt.go(page, `#/users?filter=${encodeURIComponent('username:eq:' + noEmailName)}`);
    let nodes = await rt.semantics(page);
    log('017 list rows mentioning user:', nodes.filter((n) => `${n.label} ${n.text}`.includes(noEmailName)).length);
    log('017 shot', await rt.shot(page, DIR, '017-1-list'));
    await rt.tap(page, 'Edit', { wait: 2000 });
    log('017 shot', await rt.shot(page, DIR, '017-2-edit-open'));
    nodes = await rt.semantics(page);
    log('017 dialog nodes:', nodes.map((n) => `[${n.role}] ${(n.label || n.text).slice(0, 40)}`).join(' | ').slice(0, 900));
    // Toggle Active on (user is inactive) — the reactivation flow
    await rt.tap(page, /Active/, { role: 'switch' }).catch(async () => rt.tap(page, /^Active/));
    log('017 shot', await rt.shot(page, DIR, '017-3-toggled'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('017 shot', await rt.shot(page, DIR, '017-4-after-save'));
    log('017 error text in dialog:', JSON.stringify(await errText(page)));
    log('017 apiErrors:', JSON.stringify(app.apiErrors.filter((e) => e.url.startsWith('/api/users'))));
    const after = await rt.api('GET', `/api/users/${ne.json.id}`, { token: admin });
    log('017 user after UI save: active=', after.json?.active, 'email=', JSON.stringify(after.json?.email));
    await app.close();
  }

  // ---------------- AUR-016 + AUR-038 dialogs ----------------
  if (step === 'all' || step === 'dlg') {
    const app = await rt.openApp({ token: admin, width: 1800, height: 1000 });
    const { page } = app;
    await rt.go(page, '#/users');
    await rt.tap(page, 'New user', { wait: 2000 });
    await fill(page, 'Username', rt.uniq('vaurui'));
    await fill(page, 'Email', holder.email);
    await fill(page, 'Password', rt.PASSWORD);
    const role = await rt.find(page, /Role/);
    await rt.clickAt(page, role.x, role.y, 1200);
    await rt.tap(page, 'CASHIER', { wait: 1000 });
    log('016 shot', await rt.shot(page, DIR, '016-1-filled'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('016 shot', await rt.shot(page, DIR, '016-2-after-save'));
    log('016 error text in dialog:', JSON.stringify(await errText(page)));
    log('016 apiErrors:', JSON.stringify(app.apiErrors));
    await rt.tap(page, 'Cancel').catch(() => {});

    await rt.go(page, '#/roles');
    await rt.tap(page, 'New role', { wait: 2000 });
    await fill(page, 'Name', 'VIEWER');
    log('038 shot', await rt.shot(page, DIR, '038-1-filled'));
    await rt.tap(page, 'Save', { wait: 3000 });
    log('038 shot', await rt.shot(page, DIR, '038-2-after-save'));
    log('038 error text in dialog:', JSON.stringify(await errText(page)));
    log('038 apiErrors:', JSON.stringify(app.apiErrors.filter((e) => e.url.startsWith('/api/roles'))));
    await rt.tap(page, 'Cancel').catch(() => {});
    await app.close();
  }

  // ---------------- AUR-057 layout at 1366 ----------------
  if (step === 'all' || step === '057') {
    for (const [w, h] of [[1366, 900], [1920, 1080]]) {
      const app = await rt.openApp({ token: admin, width: w, height: h });
      const { page } = app;
      await rt.go(page, '#/users');
      let nodes = await rt.semantics(page);
      const pick = (re) => nodes.filter((n) => re.test(n.label || n.text || '')).slice(0, 3).map((n) => `${(n.label || n.text).slice(0, 12)}@x${n.x - Math.round(n.w / 2)}..${n.x + Math.round(n.w / 2)}`);
      log(`057 ${w} users: Active hdr`, JSON.stringify(pick(/^Active/)), 'Edit', JSON.stringify(pick(/^Edit$/)), 'Role hdr', JSON.stringify(pick(/^Role/)));
      log('057 shot', await rt.shot(page, DIR, `057-users-${w}`));
      await rt.go(page, '#/roles');
      nodes = await rt.semantics(page);
      log(`057 ${w} roles: Privileges hdr`, JSON.stringify(pick(/^Privileg/)), 'Edit', JSON.stringify(pick(/^Edit$/)));
      log('057 shot', await rt.shot(page, DIR, `057-roles-${w}`));
      await app.close();
    }
  }
})().catch((e) => { console.error('FATAL', e.message.slice(0, 2000)); process.exit(1); });
