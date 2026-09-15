// UI-002 (narrow customers list) and UI-008 (Name-required error persistence).
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const dump = async (page, name) => {
  const s = await rt.semantics(page);
  fs.mkdirSync(path.join(D, 'sem'), { recursive: true });
  fs.writeFileSync(path.join(D, 'sem', name + '.txt'), s.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  return s;
};
const has = (s, re) => s.some((n) => re.test(n.label || '') || re.test(n.text || ''));
(async () => {
  const A = await rt.adminToken();
  const TAG = rt.uniq('vcpui');
  const mk = async (s) => (await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} ${s}`, phone: '555-7', email: `${TAG}-${s}@rt.local`, address: '9 UI St', username: `${TAG}-${s}`, password: rt.PASSWORD } })).json;
  const a = await mk('one'); const b = await mk('two');
  console.log('customers', a.id, a.name, a.pocMissing, b.id);

  // ---- UI-002 narrow list ----
  const n = await rt.openApp({ token: A, width: 400, height: 800 });
  await rt.go(n.page, '#/customers?f=' + encodeURIComponent(`name:contains:${TAG}`), 5000);
  console.log('narrow shot', await rt.shot(n.page, D, 'a-narrow'));
  const sn = await dump(n.page, 'a-narrow');
  console.log('narrow nodes', sn.filter((x) => /POC|vcpui|New customer|Next|Add filter/.test(x.label || x.text || '')).map((x) => `${x.role}|${(x.label || x.text).replace(/\n/g, ' / ').slice(0, 80)}|${x.x},${x.y} ${x.w}x${x.h}`));
  console.log('narrow pageErrors', JSON.stringify(n.pageErrors));
  await n.close();

  // ---- UI-008 ----
  const app = await rt.openApp({ token: A, width: 1366, height: 1200 });
  const { page } = app;
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${a.id}`, 4500);
  let s = await dump(page, 'a-detail');
  console.log('detail shot', await rt.shot(page, D, 'a-detail'));
  const fields = s.filter((x) => /text|input/i.test(x.role || '') || x.label === a.name || x.text === a.name);
  console.log('field candidates', fields.map((x) => `${x.role}|${x.label || x.text}|${x.x},${x.y} ${x.w}x${x.h}`));
  const nameField = fields.find((x) => (x.label === a.name || x.text === a.name)) || { x: 886, y: 238 };
  await rt.clickAt(page, nameField.x, nameField.y, 500);
  await rt.typeText(page, '', { clear: true });
  await page.waitForTimeout(500);
  await rt.tap(page, 'Save changes', { wait: 1500 });
  s = await dump(page, 'a-empty');
  console.log('empty shot', await rt.shot(page, D, 'a-empty'), 'errorShown', has(s, /Name is required/));
  await rt.clickAt(page, nameField.x, nameField.y, 400);
  await rt.typeText(page, `${TAG} valid again`);
  await page.waitForTimeout(1500);
  s = await dump(page, 'a-typed');
  console.log('typed shot', await rt.shot(page, D, 'a-typed'), 'errorStill', has(s, /Name is required/), 'valueShown', has(s, /valid again/));
  await page.waitForTimeout(2000);
  s = await rt.semantics(page);
  console.log('after 3.5s errorStill', has(s, /Name is required/));
  const g = (await rt.api('GET', `/api/customers/${a.id}`, { token: A })).json;
  console.log('db name unchanged', g.name === a.name, g.name);
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI-A FAILED', e); process.exit(1); });
