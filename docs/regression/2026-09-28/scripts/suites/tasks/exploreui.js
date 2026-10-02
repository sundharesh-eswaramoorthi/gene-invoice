const rt = require('../../lib.js');
const H = require('./helpers.js');
(async () => {
  const [route, user, ...taps] = process.argv.slice(2);
  const app = await H.openAs(user || 'admin', route);
  const p = app.page;
  for (const t of taps) {
    try { if (t.startsWith('press:')) await rt.press(p, new RegExp(t.slice(6))); else await rt.tap(p, new RegExp(t)); } catch (e) { console.log('TAP FAIL', t, String(e).slice(0, 300)); }
    await p.waitForTimeout(1500);
  }
  const nodes = await rt.semantics(p);
  for (const n of nodes) console.log(`[${n.role || n.tag}] ${JSON.stringify((n.label||'')+ ' || ' + (n.text||'')).slice(0, 200)} @${n.x},${n.y} ${n.checked?'chk='+n.checked:''} ${n.disabled?'DIS':''}`);
  console.log(await rt.shot(p, __dirname + '/xshots', 'explore'));
  console.log('REQ', JSON.stringify(app.requests.slice(-12)));
  console.log('ERR', JSON.stringify(app.apiErrors), app.pageErrors);
  await app.close();
})();
