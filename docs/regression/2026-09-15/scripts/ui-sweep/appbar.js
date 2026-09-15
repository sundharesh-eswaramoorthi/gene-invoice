// Re-check: phone app bar with a long "username • ROLE" chip. Does a real click on the hamburger open the drawer?
const rt = require('../lib.js');
const S = require('./setup.json');
const DIR = __dirname + '/appbar';
(async () => {
  for (const [who, user] of [['coll', S.coll.username], ['admin', 'admin']]) {
    const token = who === 'admin' ? await rt.adminToken() : await rt.login(user, S.password);
    const app = await rt.openApp({ token, width: 400, height: 820 });
    const nodes = await rt.semantics(app.page);
    const bar = nodes.filter((n) => n.y < 56).map((n) => `[${n.role}] ${n.label || n.text} @(${n.x},${n.y}) w${n.w}`);
    console.log(who, 'app bar nodes:', bar.join(' | '));
    await rt.shot(app.page, DIR, `${who}-bar`);
    await rt.clickAt(app.page, 28, 28, 1500);
    await rt.enableSemantics(app.page);
    const hash = await app.page.evaluate(() => location.hash);
    const drawer = (await rt.semantics(app.page)).some((n) => (n.label || n.text) === 'Customers' && n.x < 300);
    await rt.shot(app.page, DIR, `${who}-after-hamburger-click`);
    console.log(who, 'after real click at hamburger (28,28): hash', JSON.stringify(hash), 'drawer open:', drawer);
    console.log('api', JSON.stringify(app.apiErrors), 'errs', JSON.stringify(app.pageErrors));
    await app.close();
  }
})().catch((e) => { console.error(e); process.exit(1); });
