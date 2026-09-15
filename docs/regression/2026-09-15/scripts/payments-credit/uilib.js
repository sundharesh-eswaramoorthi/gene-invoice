// Small shared UI helpers for this area's scripts.
const rt = require('../lib.js');

function make(page, dir, log) {
  const note = (k, v) => { log.push([k, v]); console.log('*', k, typeof v === 'string' ? v : JSON.stringify(v)); };
  const inputs = () => page.$$eval('input, textarea', (els) => els.map((e) => {
    const r = e.getBoundingClientRect();
    return { tag: e.tagName, label: e.getAttribute('aria-label'), value: e.value, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  }));
  const labels = async () => (await rt.semantics(page)).map((n) => n.label || n.text);
  const has = async (re) => (await labels()).filter((l) => re.test(l || ''));
  const shot = (n) => rt.shot(page, dir, n).then((f) => { note('shot', f); return f; });
  const clickInput = async (pred) => {
    const i = (await inputs()).find(pred);
    if (!i) throw new Error('no matching input in ' + JSON.stringify(await inputs()));
    await rt.clickAt(page, i.x, i.y, 400);
  };
  const step = async (name, fn) => {
    try { await fn(); } catch (e) {
      note(`STEP ERROR ${name}`, e.message.slice(0, 500));
      await page.keyboard.press('Escape').catch(() => {});
      await page.waitForTimeout(800);
    }
  };
  const pickerEntries = async () => (await rt.semantics(page))
    .filter((n) => /\n@/.test(n.label || '')).map((n) => n.label.split('\n').slice(1).join(' '));
  /**
   * Opens the Collection POC picker, types the username, waits well past the 250 ms debounce and
   * records what the list shows (candidate 14), then presses one more key if needed and picks the user.
   */
  const pickPoc = async (user, tag) => {
    await rt.tap(page, /^Collection POC \*/, { wait: 2000 });
    await page.keyboard.type(user.username, { delay: 30 });
    await page.waitForTimeout(1500);
    await rt.enableSemantics(page);
    const first = await pickerEntries();
    note(`${tag}: picker entries 1.5 s after typing the full username`, { count: first.length, first: first.slice(0, 4) });
    await shot(`${tag}-picker-after-typing`);
    if (!(first.length === 1 && first[0].includes(user.username))) {
      await page.keyboard.type(' ');
      await page.waitForTimeout(1200);
      await rt.enableSemantics(page);
      const second = await pickerEntries();
      note(`${tag}: picker entries after one extra keystroke`, { count: second.length, first: second.slice(0, 4) });
    }
    await rt.tap(page, new RegExp(user.fullName), { role: 'button', wait: 1500 });
  };
  const hash = () => page.evaluate(() => location.hash);
  return { note, inputs, labels, has, shot, clickInput, step, pickPoc, hash };
}
module.exports = { make };
