// UI 4: Customer Details edit — Save, empty-name field error, unsaved-changes Back dialog (Keep editing / Discard).
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const data = JSON.parse(fs.readFileSync(path.join(D, 'ui-data.json')));
const dump = async (page, name) => {
  const s = await rt.semantics(page);
  fs.mkdirSync(path.join(D, 'sem'), { recursive: true });
  fs.writeFileSync(path.join(D, 'sem', name + '.txt'), s.map((n) => `[${n.role}] ${JSON.stringify(n.label || n.text)} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n'));
  return s;
};
const has = (s, re) => s.some((n) => re.test(n.label || '') || re.test(n.text || ''));
const fill = async (page, x, y, text) => { await rt.clickAt(page, x, y, 400); await rt.typeText(page, text, { clear: true }); };
(async () => {
  const A = await rt.adminToken();
  const id = data.b;
  const app = await rt.openApp({ token: A, width: 1366, height: 1200 });
  const { page } = app;
  const puts = [];
  page.on('request', (r) => { if (r.method() === 'PUT' && r.url().includes(`/api/customers/${id}`)) puts.push(r.postData()); });
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${id}`);
  let s = await dump(page, 'u06-detail-1200');
  console.log('detail 1200 shot', await rt.shot(page, D, 'u06-detail-1200'));
  const save = s.find((n) => n.label === 'Save changes');
  console.log('Save changes node', JSON.stringify(save));

  // ---- edit all four fields and save ----
  const FX = 886;
  await fill(page, FX, 238, `${data.TAG} two edited`);
  await fill(page, FX, 290, '555-2222');
  await fill(page, FX, 342, `${data.TAG}-two-edited@rt.local`);
  await fill(page, FX, 400, '22 Edited Ave');
  s = await dump(page, 'u06-dirty');
  console.log('dirty shot', await rt.shot(page, D, 'u06-dirty'), 'unsavedLabel', has(s, /Unsaved changes/));
  await rt.tap(page, 'Save changes', { wait: 2500 });
  s = await dump(page, 'u06-saved');
  console.log('saved shot', await rt.shot(page, D, 'u06-saved'));
  const g = (await rt.api('GET', `/api/customers/${id}`, { token: A })).json;
  console.log('RESULT save', JSON.stringify({ puts: puts.length, lastPut: puts[puts.length - 1], snackbar: has(s, /Customer saved/), unsavedGone: !has(s, /Unsaved changes/),
    persisted: { name: g.name, phone: g.phone, email: g.email, address: g.address }, titleUpdated: has(s, new RegExp(`${data.TAG} two edited`)) }));

  // ---- empty name ----
  const putsBefore = puts.length;
  await fill(page, FX, 238, '');
  await rt.tap(page, 'Save changes', { wait: 1500 });
  s = await dump(page, 'u07-empty-name');
  console.log('empty-name shot', await rt.shot(page, D, 'u07-empty-name'));
  const g2 = (await rt.api('GET', `/api/customers/${id}`, { token: A })).json;
  console.log('RESULT emptyName', JSON.stringify({ fieldError: has(s, /Name is required/), putsSent: puts.length - putsBefore, nameInDb: g2.name }));

  // ---- unsaved changes -> Back -> Keep editing ----
  await fill(page, FX, 238, `${data.TAG} two UNSAVED`);
  await rt.tap(page, 'Back', { wait: 1500 });
  s = await dump(page, 'u08-back-dialog');
  console.log('back dialog shot', await rt.shot(page, D, 'u08-back-dialog'));
  const dialogShown = has(s, /Discard unsaved changes\?/);
  await rt.tap(page, 'Keep editing', { wait: 1500 });
  s = await dump(page, 'u08-after-keep');
  console.log('after keep shot', await rt.shot(page, D, 'u08-after-keep'));
  const hashAfterKeep = await page.evaluate(() => location.hash);
  console.log('RESULT keep', JSON.stringify({ dialogShown, dialogClosed: !has(s, /Discard unsaved changes\?/), stillOnDetail: hashAfterKeep, saveVisible: has(s, /Save changes/), unsavedStill: has(s, /Unsaved changes/) }));

  // ---- Back -> Discard ----
  await rt.tap(page, 'Back', { wait: 1500 });
  s = await dump(page, 'u09-back-dialog2');
  const dialog2 = has(s, /Discard unsaved changes\?/);
  await rt.tap(page, 'Discard', { wait: 3000 });
  s = await dump(page, 'u09-after-discard');
  console.log('after discard shot', await rt.shot(page, D, 'u09-after-discard'));
  const hashAfterDiscard = await page.evaluate(() => location.hash);
  const g3 = (await rt.api('GET', `/api/customers/${id}`, { token: A })).json;
  console.log('RESULT discard', JSON.stringify({ dialog2, hashAfterDiscard, onList: has(s, /New customer/), nameInDb: g3.name, extraPuts: puts.length - putsBefore }));

  // ---- reopen: edits discarded, no stale state ----
  await rt.go(page, `#/customers/${id}`);
  console.log('reopen shot', await rt.shot(page, D, 'u09-reopen'));
  s = await dump(page, 'u09-reopen');
  console.log('RESULT reopen', JSON.stringify({ unsavedLabel: has(s, /Unsaved changes/) }));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI4 FAILED', e); process.exit(1); });
