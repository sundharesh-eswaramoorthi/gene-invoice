// UI: login screen, account menu -> change password dialog, logout.
const rt = require('../lib.js');
const D = __dirname;
const P = rt.PASSWORD;
const texts = async (page) => (await rt.semantics(page)).map((n) => (n.label || n.text || '').trim());
const count = (arr, s) => arr.filter((t) => t === s || t.split('\n').includes(s)).length;
const has = (arr, re) => arr.some((t) => re.test(t));
/** Clicks the text field whose input carries this label (positions move when dialogs re-centre). */
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
const token = (page) => page.evaluate(() => localStorage.getItem('flutter.gene_invoice_token'));
(async () => {
  const admin = await rt.adminToken();
  const me = await rt.createStaff(admin, 'VIEWER', 'aurui');
  const app = await rt.openApp({});
  const { page } = app;

  // L1 empty fields
  await rt.tap(page, 'Sign in', { wait: 1200 });
  let t = await texts(page);
  console.log('L1 empty submit: Required count =', count(t, 'Required'), await rt.shot(page, D, 'ui-login-1-empty'));

  // L2 wrong password (login fields: fixed coordinates, as in the first run)
  await rt.clickAt(page, 683, 465, 400); await rt.typeText(page, me.username);
  await rt.clickAt(page, 683, 517, 400); await rt.typeText(page, 'wrong-password');
  await page.keyboard.press('Enter'); await page.waitForTimeout(2500); await rt.enableSemantics(page);
  t = await texts(page);
  console.log('L2 wrong password: shows "Invalid username or password" =', has(t, /Invalid username or password/), '| still on login =', t.includes('Sign in'), await rt.shot(page, D, 'ui-login-2-wrong'));

  // L3 success
  await rt.clickAt(page, 683, 465, 300); await rt.typeText(page, me.username, { clear: true });
  await rt.clickAt(page, 683, 517, 300); await rt.typeText(page, P, { clear: true });
  await rt.tap(page, 'Sign in', { wait: 4500 }); await rt.enableSemantics(page);
  t = await texts(page);
  console.log('L3 success: account label =', JSON.stringify(t.find((x) => /•/.test(x))), '| url =', page.url(), await rt.shot(page, D, 'ui-login-3-success'));

  // C1 change password dialog, empty submit
  await rt.tap(page, /•/); await rt.tap(page, 'Change password');
  await rt.tap(page, 'Change', { wait: 1000 });
  t = await texts(page);
  console.log('C1 empty submit Required count =', count(t, 'Required'), await rt.shot(page, D, 'ui-chpw-1-empty'));
  await rt.tap(page, 'Cancel');
  // C2 too short (client validation)
  await rt.tap(page, /•/); await rt.tap(page, 'Change password');
  await fill(page, 'Current password', P);
  await fill(page, 'New password', 'abc');
  await fill(page, 'Confirm new password', 'abc');
  await rt.tap(page, 'Change', { wait: 1000 });
  t = await texts(page);
  console.log('C2 short: "At least 6 characters" =', has(t, /At least 6 characters/), await rt.shot(page, D, 'ui-chpw-2-short'));
  await rt.tap(page, 'Cancel');
  // C3 wrong current (server) -> stays signed in
  await rt.tap(page, /•/); await rt.tap(page, 'Change password');
  await fill(page, 'Current password', 'not-my-password');
  await fill(page, 'New password', 'NewPass123');
  await fill(page, 'Confirm new password', 'NewPass123');
  await rt.tap(page, 'Change', { wait: 2500 });
  t = await texts(page);
  console.log('C3 wrong current: message shown =', has(t, /Current password is incorrect/), '| token still stored =', (await token(page)) !== null,
    await rt.shot(page, D, 'ui-chpw-3-wrong-current'));
  // C4 mismatch confirm
  await fill(page, 'Confirm new password', 'Mismatch99');
  await rt.tap(page, 'Change', { wait: 1000 });
  t = await texts(page);
  console.log('C4 mismatch: "Does not match" =', has(t, /Does not match/), await rt.shot(page, D, 'ui-chpw-4-mismatch'));
  // C5 success
  await fill(page, 'Current password', P);
  await fill(page, 'Confirm new password', 'NewPass123');
  await rt.tap(page, 'Change', { wait: 2500 });
  t = await texts(page);
  const oldL = await rt.api('POST', '/api/auth/login', { body: { username: me.username, password: P } });
  const newL = await rt.api('POST', '/api/auth/login', { body: { username: me.username, password: 'NewPass123' } });
  console.log('C5 success message =', has(t, /Password changed successfully/), '| buttons:', t.filter((x) => /^(Close|Cancel|Change)$/.test(x)).join(','),
    '| API old pw', oldL.status, 'new pw', newL.status, await rt.shot(page, D, 'ui-chpw-5-success'));
  await rt.tap(page, /^(Close|Cancel)$/);

  // O1 logout
  await rt.tap(page, /•/); await rt.tap(page, 'Logout', { wait: 2500 }); await rt.enableSemantics(page);
  t = await texts(page);
  console.log('O1 logout: on login =', t.includes('Sign in') && !has(t, /•/), '| url =', page.url(), '| token cleared =', (await token(page)) === null,
    await rt.shot(page, D, 'ui-logout-1'));
  await rt.go(page, '#/users');
  t = await texts(page);
  console.log('O2 after logout, #/users redirects to login =', t.includes('Sign in'), '| url =', page.url());
  // O3 login again with the new password
  await rt.clickAt(page, 683, 465, 300); await rt.typeText(page, me.username, { clear: true });
  await rt.clickAt(page, 683, 517, 300); await rt.typeText(page, 'NewPass123', { clear: true });
  await page.keyboard.press('Enter'); await page.waitForTimeout(4500); await rt.enableSemantics(page);
  t = await texts(page);
  console.log('O3 login with new password =', has(t, /•/), '| url =', page.url(), await rt.shot(page, D, 'ui-login-4-newpw'));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
