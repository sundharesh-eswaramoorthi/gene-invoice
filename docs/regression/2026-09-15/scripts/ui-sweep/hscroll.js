// Can a desktop mouse user reach the columns clipped at the right edge of wide tables?
const rt = require('../lib.js');
const DIR = __dirname + '/hscroll';
(async () => {
  const app = await rt.openApp({ token: await rt.adminToken(), width: 1366, height: 900 });
  const { page } = app;
  for (const [hash, name, col] of [['#/promises', 'promises', /Collection POC/], ['#/disputes', 'disputes', /Reason/], ['#/roles', 'roles', /Privileg/], ['#/notifications', 'notifications', /Message/]]) {
    await rt.go(page, hash, 4500);
    const before = (await rt.semantics(page)).filter((n) => col.test(n.label || n.text || ''));
    const headersBefore = (await rt.semantics(page)).filter((n) => n.y < 240 && n.y > 110).map((n) => `${n.label || n.text}@${n.x}`);
    await rt.shot(page, DIR, `${name}-0`);
    // 1) shift + vertical wheel (mouse wheel users)
    await page.mouse.move(800, 500);
    await page.keyboard.down('Shift');
    await page.mouse.wheel(0, 800);
    await page.keyboard.up('Shift');
    await page.waitForTimeout(1000);
    await rt.enableSemantics(page);
    const afterShift = (await rt.semantics(page)).filter((n) => n.y < 240 && n.y > 110).map((n) => `${n.label || n.text}@${n.x}`);
    await rt.shot(page, DIR, `${name}-1-shiftwheel`);
    // 2) horizontal wheel delta (trackpad users)
    await page.mouse.wheel(800, 0);
    await page.waitForTimeout(1000);
    await rt.enableSemantics(page);
    const afterDx = (await rt.semantics(page)).filter((n) => n.y < 240 && n.y > 110).map((n) => `${n.label || n.text}@${n.x}`);
    await rt.shot(page, DIR, `${name}-2-deltax`);
    console.log(name, '\n  before :', headersBefore.join(' | '), '\n  shift  :', afterShift.join(' | '), '\n  deltaX :', afterDx.join(' | '));
  }
  console.log('api', JSON.stringify(app.apiErrors), 'errs', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error(e); process.exit(1); });
