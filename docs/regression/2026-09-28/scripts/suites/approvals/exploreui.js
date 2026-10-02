const rt = require('../../lib.js');
const fs = require('fs');
(async () => {
  const [user, route, name] = process.argv.slice(2);
  const pw = user === 'admin' ? 'admin123' : 'Passw0rd!';
  const app = await rt.openApp({ username: user, password: pw }); await rt.go(app.page, route, 5000);
  try {
    const nodes = await rt.semantics(app.page);
    console.log(nodes.map(n => `[${n.role||n.tag}] ${JSON.stringify(n.label||n.text)} @${n.x},${n.y}${n.disabled?' disabled':''}`).join('\n'));
    console.log('ERR', JSON.stringify(app.apiErrors), JSON.stringify(app.pageErrors));
    await rt.shot(app.page, require('path').join(__dirname, 'xshots'), name || 'x');
  } finally { await app.close(); }
})().catch(e => { console.error(e); process.exit(1); });
