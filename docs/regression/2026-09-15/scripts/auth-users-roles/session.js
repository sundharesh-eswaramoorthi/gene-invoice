// UI: a session that expires while the app is open returns to the login screen (regression);
// an already-expired / garbage token at boot lands on the login screen.
const rt = require('../lib.js');
const D = __dirname;
const isLogin = async (page) => {
  const n = await rt.semantics(page);
  return n.some((x) => x.label === 'Sign in' || x.text === 'Sign in') && !n.some((x) => /•/.test(`${x.label} ${x.text}`));
};
const stored = (page) => page.evaluate(() => localStorage.getItem('flutter.gene_invoice_token'));
(async () => {
  const admin = await rt.adminToken();
  const me = await rt.createStaff(admin, 'CASHIER', 'aurses');
  // S1: token expires while the app is open, then the user navigates
  const ttl = 25;
  const t0 = Date.now();
  let app = await rt.openApp({ token: rt.mintToken(me.username, ttl) });
  await rt.go(app.page, '#/invoices');
  const onInvoices = !(await isLogin(app.page));
  console.log('S1 before expiry signed in on invoices:', onInvoices, await rt.shot(app.page, D, 'session-1-before'));
  const wait = ttl * 1000 - (Date.now() - t0) + 4000;
  if (wait > 0) await app.page.waitForTimeout(wait);
  // In-app navigation after expiry, like clicking the Payments nav item
  await rt.clickAt(app.page, 108, 173, 4000);
  await rt.enableSemantics(app.page);
  const s1 = await isLogin(app.page);
  console.log('S1 after expiry + nav click -> login screen:', s1, 'token cleared:', (await stored(app.page)) === null,
    'url:', app.page.url(), 'apiErrors:', JSON.stringify(app.apiErrors), await rt.shot(app.page, D, 'session-1-after'));
  // after returning to login, a fresh sign-in works
  await rt.clickAt(app.page, 683, 465, 500); await rt.typeText(app.page, me.username);
  await rt.clickAt(app.page, 683, 517, 500); await rt.typeText(app.page, me.password);
  await app.page.keyboard.press('Enter'); await app.page.waitForTimeout(4000); await rt.enableSemantics(app.page);
  const relog = !(await isLogin(app.page));
  console.log('S1b sign in again after expiry:', relog, 'url:', app.page.url(), await rt.shot(app.page, D, 'session-1-relogin'));
  await app.close();

  // S2: token expires while the user stays on the same screen and presses Refresh-like action (tab back to dashboard)
  const t1 = Date.now();
  app = await rt.openApp({ token: rt.mintToken(me.username, 20) });
  await rt.go(app.page, '#/customers');
  await app.page.waitForTimeout(Math.max(0, 20000 - (Date.now() - t1) + 3000));
  await rt.go(app.page, '#/');
  const s2 = await isLogin(app.page);
  console.log('S2 expiry then dashboard nav -> login:', s2, 'apiErrors:', JSON.stringify(app.apiErrors), await rt.shot(app.page, D, 'session-2-after'));
  await app.close();

  // S3: boot with an already-expired token
  app = await rt.openApp({ token: rt.mintToken(me.username, -60) });
  const s3 = await isLogin(app.page);
  console.log('S3 boot with expired token -> login:', s3, 'token cleared:', (await stored(app.page)) === null, 'apiErrors:', JSON.stringify(app.apiErrors), await rt.shot(app.page, D, 'session-3-boot-expired'));
  await app.close();
  // S4: boot with garbage token
  app = await rt.openApp({ token: 'garbage.token.value' });
  const s4 = await isLogin(app.page);
  console.log('S4 boot with garbage token -> login:', s4, 'pageErrors:', JSON.stringify(app.pageErrors), await rt.shot(app.page, D, 'session-4-boot-garbage'));
  await app.close();
  // S5: a signed-in user whose privilege is missing hits a 403 screen but stays signed in (cashier -> #/users)
  app = await rt.openApp({ token: await rt.login(me.username, me.password) });
  await rt.go(app.page, '#/users');
  const s5 = !(await isLogin(app.page));
  const txt = (await rt.semantics(app.page)).map((n) => n.label || n.text).join(' | ');
  console.log('S5 cashier opens #/users -> still signed in:', s5, 'apiErrors:', JSON.stringify(app.apiErrors), 'screen:', txt.slice(0, 300), await rt.shot(app.page, D, 'session-5-cashier-users-403'));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
