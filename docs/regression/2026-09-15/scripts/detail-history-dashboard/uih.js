// UI helpers for this area.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json')));

function logRequests(page) {
  const reqs = [];
  page.on('request', (r) => {
    if (r.url().startsWith(rt.API)) reqs.push({ t: Date.now(), m: r.method(), u: decodeURIComponent(r.url().slice(rt.API.length)) });
  });
  return reqs;
}
const hash = (page) => page.evaluate(() => location.hash);
async function tabs(page) {
  return page.$$eval('flt-semantics[role=tab]', (els) => els.map((e) => ({ label: (e.getAttribute('aria-label') || e.textContent || '').trim(), selected: e.getAttribute('aria-selected') })));
}
async function selectedTab(page) {
  return (await tabs(page)).filter((t) => t.selected === 'true').map((t) => t.label).join(',');
}
async function allText(page) {
  const n = await rt.semantics(page);
  return n.map((x) => `${x.label || ''} ${x.text || ''}`).join(' | ');
}
async function dump(page) {
  return (await rt.semantics(page)).map((n) => `[${n.role}] ${(n.label || n.text).replace(/\n/g, ' / ').slice(0, 160)} @${n.x},${n.y} ${n.w}x${n.h}`);
}
/** Clicks a link on the second line of a merged history row whose label matches rowMatcher. */
async function clickRowLink(page, rowMatcher, expectHashRe) {
  const tries = [];
  for (const dy of [42, 38, 46, 50, 34]) {
    const nodes = await rt.semantics(page);
    const n = nodes.find((x) => (rowMatcher instanceof RegExp ? rowMatcher.test(x.label || x.text || '') : (x.label || x.text || '').includes(rowMatcher)));
    if (!n) return { ok: false, reason: 'row not found', tries };
    const left = n.x - n.w / 2; const top = n.y - n.h / 2;
    const x = Math.round(left + 52 + 25); const y = Math.round(top + dy);
    await rt.clickAt(page, x, y, 2500);
    const h = await hash(page);
    tries.push({ x, y, h });
    if (expectHashRe.test(h)) return { ok: true, tries, row: n };
  }
  return { ok: false, tries };
}
function results() {
  const list = [];
  return {
    list,
    rec(id, title, pass, detail) {
      list.push({ id, title, pass, detail });
      console.log(`${pass ? 'PASS' : 'FAIL'} ${id} ${title} :: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`.slice(0, 2500));
    },
    save(name) { fs.writeFileSync(path.join(DIR, name), JSON.stringify(list, null, 1)); },
  };
}
module.exports = { rt, S, DIR, logRequests, hash, tabs, selectedTab, allText, dump, clickRowLink, results };
