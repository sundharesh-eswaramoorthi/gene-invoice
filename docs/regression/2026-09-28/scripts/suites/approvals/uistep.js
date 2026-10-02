// node uistep.js <user> <route> step...   steps: tap:<text|/re/>  press:<..>  fill:<label>=<value>  wait:<ms>  dump  shot:<name>  go:<route> at:x,y
const rt = require('../../lib.js');
const toM = (s) => s.startsWith('/') && s.lastIndexOf('/') > 0 ? new RegExp(s.slice(1, s.lastIndexOf('/')), s.slice(s.lastIndexOf('/') + 1)) : s;
const dump = async (page, tag) => { const nodes = await rt.semantics(page); console.log('---', tag); console.log(nodes.filter(n=>n.role!=='cell'&&n.role!=='columnheader').map(n => `[${n.role||n.tag}] ${JSON.stringify(n.label||'')}${n.text&&n.text!==n.label?' text='+JSON.stringify(n.text):''} @${n.x},${n.y}${n.disabled?' disabled':''}${n.checked?' checked='+n.checked:''}`).join('\n')); };
(async () => {
  const [user, route, ...steps] = process.argv.slice(2);
  const pw = user === 'admin' ? 'admin123' : 'Passw0rd!';
  const app = await rt.openApp({ username: user, password: pw });
  const { page } = app;
  try {
    await rt.go(page, route, 5000);
    for (const st of steps) {
      const i = st.indexOf(':'); const k = i < 0 ? st : st.slice(0, i); const v = i < 0 ? '' : st.slice(i + 1);
      if (k === 'tap') await rt.tap(page, toM(v), { wait: 2000 });
      else if (k === 'tapn') { const [m, n] = v.split('#'); await rt.tap(page, toM(m), { wait: 2000, nth: Number(n) }); }
      else if (k === 'press') await rt.press(page, toM(v), { wait: 2000 });
      else if (k === 'fill') { const j = v.indexOf('='); const n = await rt.find(page, toM(v.slice(0, j)), { role: 'textbox' }); await page.locator(`[data-rt="${n.i}"]`).first().focus(); await page.waitForTimeout(400); await rt.typeText(page, v.slice(j + 1), { clear: true }); await page.waitForTimeout(600); }
      else if (k === 'wait') await page.waitForTimeout(Number(v));
      else if (k === 'dump') await dump(page, v);
      else if (k === 'shot') console.log('shot', await rt.shot(page, __dirname + '/xshots', v));
      else if (k === 'go') await rt.go(page, v, 4000);
      else if (k === 'at') { const [x, y] = v.split(',').map(Number); await rt.clickAt(page, x, y, 2000); }
    }
    console.log('APIERR', JSON.stringify(app.apiErrors), 'PAGEERR', JSON.stringify(app.pageErrors));
    console.log('WRITES', JSON.stringify(app.requests.filter(r => r.method !== 'GET')));
  } finally { await app.close(); }
})().catch(e => { console.error(String(e).slice(0, 3000)); process.exit(1); });
