// Discovery: what the login, users and roles screens expose (labels + positions).
const rt = require('../lib.js');
const D = __dirname;
const dump = (nodes) => nodes.map((n) => `[${n.role}] ${(n.label || n.text).slice(0, 60)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n');
(async () => {
  let app = await rt.openApp({});
  console.log('--- LOGIN\n' + dump(await rt.semantics(app.page)));
  console.log(await rt.shot(app.page, D, 'x-login'));
  await app.close();
  const admin = await rt.adminToken();
  app = await rt.openApp({ token: admin });
  await rt.go(app.page, '#/users');
  console.log('--- USERS\n' + dump((await rt.semantics(app.page)).slice(0, 60)));
  console.log(await rt.shot(app.page, D, 'x-users'));
  await rt.go(app.page, '#/roles');
  console.log('--- ROLES\n' + dump((await rt.semantics(app.page)).slice(0, 60)));
  console.log(await rt.shot(app.page, D, 'x-roles'));
  console.log('api errors', JSON.stringify(app.apiErrors), 'page errors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
