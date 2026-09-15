// UI helpers on top of rt: semantics with checked state, find-all, wait, card-scoped buttons.
const rt = require('../lib.js');

async function sem(page) {
  return page.$$eval('flt-semantics', (els) => els.map((e, i) => {
    e.setAttribute('data-rt', String(i));
    const r = e.getBoundingClientRect();
    return {
      i, role: e.getAttribute('role'), label: e.getAttribute('aria-label'), checked: e.getAttribute('aria-checked'),
      text: e.querySelector('flt-semantics') ? '' : (e.textContent || '').trim(),
      x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), h: Math.round(r.height),
    };
  }).filter((n) => n.label || n.text));
}
const lab = (n) => `${n.label || ''} ${n.label && n.text && n.label.includes(n.text) ? '' : (n.text || '')}`.trim();
async function all(page, m, role) {
  return (await sem(page)).filter((n) => (m instanceof RegExp ? m.test(lab(n)) : lab(n) === m) && (!role || n.role === role));
}
async function tapNode(page, n, wait = 1500) {
  await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
  await page.waitForTimeout(wait);
}
async function tapLast(page, m, role, wait) {
  const a = await all(page, m, role);
  if (!a.length) throw new Error('no node ' + m + ' on screen:\n' + dump(await sem(page)).slice(0, 3000));
  await tapNode(page, a[a.length - 1], wait);
  return a[a.length - 1];
}
async function waitFor(page, m, { timeout = 10000, role } = {}) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) {
    const a = await all(page, m, role);
    if (a.length) return a;
    await page.waitForTimeout(400);
  }
  return [];
}
/** Button with `label` that sits inside the card whose merged label matches `cardRe`. */
async function cardButton(page, cardRe, label) {
  const nodes = await sem(page);
  const card = nodes.find((n) => n.role === 'group' && cardRe.test(lab(n)));
  if (!card) throw new Error('no card ' + cardRe + '\n' + dump(nodes).slice(0, 3000));
  const top = card.y - card.h / 2 - 4; const bottom = card.y + card.h / 2 + 4;
  const btn = nodes.find((n) => (n.label === label || n.text === label || (label instanceof RegExp && label.test(lab(n)))) && n.y >= top && n.y <= bottom && n.role === 'button');
  if (!btn) throw new Error(`no button ${label} in card ${cardRe} (card y ${top}-${bottom})\n` + dump(nodes).slice(0, 3000));
  return btn;
}
async function click(page, n, wait = 700) { await page.mouse.click(n.x, n.y); await page.waitForTimeout(wait); }
const dump = (nodes) => nodes.map((n) => `[${n.role}${n.checked ? ':' + n.checked : ''}] ${lab(n).replace(/\n/g, ' / ')} @${n.x},${n.y} ${n.w}x${n.h}`).join('\n');
const hash = (page) => page.evaluate(() => location.hash);
/** Text fields (flt-semantics wrapping an input/textarea), restricted to the open dialog if any, top to bottom. */
async function fields(page) {
  return page.$$eval('flt-semantics', (els) => {
    const dlg = document.querySelector('flt-semantics[role="alertdialog"], flt-semantics[role="dialog"]');
    return els.filter((e) => e.querySelector(':scope > input, :scope > textarea') && (!dlg || e.closest('[role="alertdialog"], [role="dialog"]')))
      .map((e) => { const r = e.getBoundingClientRect(); return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), tag: e.querySelector('input,textarea').tagName }; })
      .sort((a, b) => a.y - b.y || a.x - b.x);
  });
}
async function field(page, idx) {
  const f = await fields(page);
  if (!f.length) throw new Error('no text fields on screen');
  return idx < 0 ? f[f.length + idx] : f[idx];
}
async function closeDialogs(page) {
  for (let k = 0; k < 3; k++) {
    const d = await page.$('flt-semantics[role="alertdialog"], flt-semantics[role="dialog"]');
    if (!d) return;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(900);
  }
}
/** The six promise tile values in order: total, open, kept, partially kept, broken, promised total. */
function tiles(nodes) {
  const l = nodes.map(lab);
  const i = l.indexOf('Promised total');
  return i < 0 ? [] : l.slice(i + 1, i + 7);
}
module.exports = { rt, sem, lab, all, tapNode, tapLast, waitFor, cardButton, click, dump, hash, fields, field, closeDialogs, tiles };
