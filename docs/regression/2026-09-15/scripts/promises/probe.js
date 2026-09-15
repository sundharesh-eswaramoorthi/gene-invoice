const fx = require('./fx'); const U = require('./uih'); const { rt } = U;
const ids = require('./ui-ids.json');
(async () => {
  const adm = await fx.admin();
  const app = await rt.openApp({ token: adm, height: 1300 });
  const { page } = app;
  await rt.go(page, `#/customers/${ids.cu}?tab=promises`, 5000);
  await U.tapLast(page, 'Raise promise', 'button', 2500);
  const inputs = await page.$$eval('input, textarea', (els) => els.map((e) => { const r = e.getBoundingClientRect(); return { tag: e.tagName, type: e.type, aria: e.getAttribute('aria-label'), ph: e.placeholder, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), parentRole: e.closest('flt-semantics')?.getAttribute('role') }; }));
  console.log('inputs', JSON.stringify(inputs, null, 1));
  const tf = await page.$$eval('flt-semantics', (els) => els.filter((e) => /text/i.test(e.getAttribute('role') || '') || e.querySelector('input,textarea')).map((e) => { const r = e.getBoundingClientRect(); return { role: e.getAttribute('role'), label: e.getAttribute('aria-label'), html: e.outerHTML.slice(0, 200), x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; }));
  console.log('text semantics', JSON.stringify(tf, null, 1));
  console.log(await rt.shot(page, __dirname, 'probe-raise-dialog'));
  await app.close();
})();
