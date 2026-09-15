// UI 5: POC editor on Customer Details — add via picker, set primary, remove (Keep / Remove), History.
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
const tapNode = async (page, n, wait = 1500) => { await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click'); await page.waitForTimeout(wait); };

async function addPoc(page, user, tag) {
  let s = await rt.semantics(page);
  const addChip = s.filter((n) => n.label === 'Add' && n.role !== 'button').sort((a, b) => a.y - b.y)[0];
  await tapNode(page, addChip);
  s = await dump(page, `${tag}-add-dialog`);
  console.log(`${tag} add dialog shot`, await rt.shot(page, D, `${tag}-add-dialog`), 'title', has(s, /Add Customer Success POC/));
  const picker = s.find((n) => /Customer Success POC \*/.test(n.label || n.text || '')) || s.find((n) => /Select…/.test(n.label || n.text || ''));
  if (picker) await tapNode(page, picker); else await rt.clickAt(page, 683, 420);
  await rt.typeText(page, user.username);
  await page.waitForTimeout(1800);
  s = await dump(page, `${tag}-picker`);
  console.log(`${tag} picker shot`, await rt.shot(page, D, `${tag}-picker`));
  const tiles = s.filter((n) => /@\S+/.test(n.label || n.text || '') && /•/.test(n.label || n.text || ''));
  console.log(`RESULT ${tag} picker-after-typing`, JSON.stringify({ typed: user.username, tilesShown: tiles.length, onlyMatch: tiles.length === 1 && (tiles[0].label || tiles[0].text).includes(user.username) }));
  // one extra key (trailing space is trimmed by the picker) forces the dialog to rebuild with the pending query
  await page.keyboard.type(' ', { delay: 20 });
  await page.waitForTimeout(1500);
  s = await dump(page, `${tag}-picker2`);
  console.log(`${tag} picker2 shot`, await rt.shot(page, D, `${tag}-picker2`));
  const tiles2 = s.filter((n) => /@\S+/.test(n.label || n.text || '') && /•/.test(n.label || n.text || ''));
  const hit = s.find((n) => (n.label || n.text || '').includes('@' + user.username));
  console.log(`RESULT ${tag} picker-after-extra-key`, JSON.stringify({ tilesShown: tiles2.map((t) => (t.label || t.text).replace(/\n/g, ' ')), matched: !!hit }));
  if (!hit) throw new Error('user not in picker');
  await tapNode(page, hit);
  s = await dump(page, `${tag}-add-dialog-picked`);
  await rt.tap(page, 'Add', { role: 'button', wait: 2500 });
}

(async () => {
  const A = await rt.adminToken();
  const id = data.three;
  const cs1 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpuics');
  const cs2 = await rt.createStaff(A, 'CUSTOMER_SUCCESS_POC', 'cpuics');
  const seats = async () => (await rt.api('GET', `/api/customers/${id}/pocs`, { token: A })).json.filter((p) => p.pocType === 'SUCCESS');
  const fmt = (arr) => arr.map((p) => `${p.user.username}${p.primary ? '*' : ''}`);

  const app = await rt.openApp({ token: A, width: 1366, height: 1200 });
  const { page } = app;
  const deletes = [];
  page.on('request', (r) => { if (r.method() === 'DELETE' && r.url().includes('/pocs/')) deletes.push(r.url()); });
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${id}`);
  let s = await dump(page, 'u11-start');
  console.log('start shot', await rt.shot(page, D, 'u11-start'));

  await addPoc(page, cs1, 'u12');
  s = await dump(page, 'u12-after-add');
  console.log('after add1 shot', await rt.shot(page, D, 'u12-after-add'));
  console.log('RESULT add1', JSON.stringify({ seats: fmt(await seats()), chipOnScreen: has(s, new RegExp(cs1.fullName)), noneYetGone: !has(s, /No Customer Success POC yet/) }));

  await addPoc(page, cs2, 'u13');
  s = await dump(page, 'u13-after-add');
  console.log('after add2 shot', await rt.shot(page, D, 'u13-after-add'));
  console.log('RESULT add2', JSON.stringify({ seats: fmt(await seats()), chip2OnScreen: has(s, new RegExp(cs2.fullName)) }));

  // set primary by tapping cs2's chip
  const chip2 = s.find((n) => (n.label || n.text || '').includes(cs2.fullName));
  await tapNode(page, chip2, 2500);
  s = await dump(page, 'u14-after-primary');
  console.log('after primary shot', await rt.shot(page, D, 'u14-after-primary'));
  console.log('RESULT primary', JSON.stringify({ seats: fmt(await seats()) }));

  // remove the primary (cs2): Keep first
  const chipNow = s.find((n) => (n.label || n.text || '').includes(cs2.fullName));
  const dels = s.filter((n) => /^(Delete|Remove)/.test(n.label || '') && n.role === 'button' && Math.abs(n.y - chipNow.y) < 15 && n.x > chipNow.x - chipNow.w / 2 - 5)
    .sort((a, b) => a.x - b.x);
  console.log('chip', JSON.stringify(chipNow), 'delete candidates', JSON.stringify(dels));
  const delBtn = dels.find((n) => n.x <= chipNow.x + chipNow.w / 2 + 5) || dels[0];
  const hashBefore = await page.evaluate(() => location.hash);
  if (delBtn) await tapNode(page, delBtn); else await rt.clickAt(page, chipNow.x + chipNow.w / 2 - 14, chipNow.y);
  s = await dump(page, 'u15-remove-dialog');
  console.log('remove dialog shot', await rt.shot(page, D, 'u15-remove-dialog'));
  const dialogShown = has(s, /^Remove /) && has(s, /Keep/);
  const mentionsPrimary = has(s, /primary Customer Success POC/);
  await rt.tap(page, 'Keep', { wait: 2000 });
  s = await dump(page, 'u15-after-keep');
  console.log('after keep shot', await rt.shot(page, D, 'u15-after-keep'));
  const hashAfterKeep = await page.evaluate(() => location.hash);
  console.log('RESULT keep', JSON.stringify({ dialogShown, mentionsPrimary, dialogClosed: !has(s, /Keep/), hashBefore, hashAfterKeep, chipStill: has(s, new RegExp(cs2.fullName)), deletesSent: deletes.length, seats: fmt(await seats()) }));

  // Remove for real
  const chipAgain = s.find((n) => (n.label || n.text || '').includes(cs2.fullName));
  const dels2 = s.filter((n) => /^(Delete|Remove)/.test(n.label || '') && n.role === 'button' && Math.abs(n.y - chipAgain.y) < 15 && n.x > chipAgain.x - chipAgain.w / 2 - 5).sort((a, b) => a.x - b.x);
  const delBtn2 = dels2.find((n) => n.x <= chipAgain.x + chipAgain.w / 2 + 5) || dels2[0];
  if (delBtn2) await tapNode(page, delBtn2); else await rt.clickAt(page, chipAgain.x + chipAgain.w / 2 - 14, chipAgain.y);
  await rt.tap(page, 'Remove', { role: 'button', wait: 3000 });
  s = await dump(page, 'u16-after-remove');
  console.log('after remove shot', await rt.shot(page, D, 'u16-after-remove'));
  const hashAfterRemove = await page.evaluate(() => location.hash);
  console.log('RESULT remove', JSON.stringify({ deletesSent: deletes.length, chipGone: !has(s, new RegExp(cs2.fullName)), cs1Chip: has(s, new RegExp(cs1.fullName)), hashAfterRemove, seats: fmt(await seats()) }));

  // History tab
  await rt.tap(page, 'History', { wait: 3000 });
  s = await dump(page, 'u17-history');
  console.log('history shot', await rt.shot(page, D, 'u17-history'));
  console.log('RESULT history', JSON.stringify({ rows: s.filter((n) => /POC/.test(n.label || n.text || '')).map((n) => (n.label || n.text).replace(/\n/g, ' | ').slice(0, 160)) }));
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI5 FAILED', e); process.exit(1); });
