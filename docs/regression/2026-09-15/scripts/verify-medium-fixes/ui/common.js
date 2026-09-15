// Shared helpers for the medium-fix UI checks (W-01..W-18). Built on ../../lib.js.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');

const DIR = __dirname;
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 120)} @${n.x},${n.y} ${n.w}x${n.h}`;
const state = () => JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));

/** snap(page, key, name): screenshot + semantics dump into out[key]. Returns the nodes. */
function recorder(out) {
  return async (page, key, name) => {
    const nodes = await rt.semantics(page);
    out[key] = out[key] || {};
    out[key]['sem_' + name] = nodes.map(fmt);
    out[key]['shot_' + name] = await rt.shot(page, DIR, name);
    return nodes;
  };
}

/** Every text input/textarea the semantics tree renders, with its label and on-screen centre. */
async function inputs(page) {
  return page.$$eval('input, textarea', (els) => els.map((e, i) => {
    const r = e.getBoundingClientRect();
    return {
      i, tag: e.tagName, label: e.getAttribute('aria-label') || e.getAttribute('placeholder') || '',
      value: e.value, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), h: Math.round(r.height),
    };
  }));
}

const test = (m, s) => (m instanceof RegExp ? m.test(s) : s === m);

/** Clicks the text field whose label matches (input aria-label first, then a semantics node). */
async function focusField(page, matcher) {
  // Read the positions twice: a dialog that is still laying out (a list loading above the field)
  // moves the inputs, and a click on stale coordinates lands in the neighbouring field.
  await inputs(page);
  await page.waitForTimeout(400);
  const list = await inputs(page);
  let hit = list.find((f) => f.w > 0 && test(matcher, f.label));
  if (!hit) {
    const nodes = await rt.semantics(page);
    hit = nodes.find((n) => n.w > 0 && test(matcher, lab(n)));
  }
  if (!hit) throw new Error(`no field ${matcher}; inputs=${JSON.stringify(list)}`);
  await rt.clickAt(page, hit.x, hit.y, 500);
  return hit;
}

/** Types into the field and checks the text landed there (a prefix is accepted for maxLength fields). */
const fillMisses = [];
async function fill(page, matcher, text) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await focusField(page, matcher);
    await rt.typeText(page, text, { clear: true });
    await page.waitForTimeout(400);
    const f = (await inputs(page)).find((x) => x.w > 0 && test(matcher, x.label));
    if (!f) return null; // field known only from a semantics node; nothing to verify against
    if (f.value === text || (f.value && text.startsWith(f.value))) return f.value;
    fillMisses.push({ matcher: String(matcher), wanted: text.slice(0, 40), got: f.value, attempt });
  }
  throw new Error(`could not type into ${matcher}: ${JSON.stringify(fillMisses.slice(-3))}`);
}

const find = (nodes, re) => nodes.filter((n) => re.test(lab(n)));
const has = (nodes, re) => find(nodes, re).map(fmt);

function write(name, out) {
  fs.writeFileSync(path.join(DIR, name), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, (k, v) => (k.startsWith('sem_') ? undefined : v), 1));
}

/** Opens the account menu (top-right) and signs out. */
async function signOut(page) {
  const nodes = await rt.semantics(page);
  const acct = nodes.filter((x) => x.y < 60 && x.x > 900 && x.role === 'button' && !/Notifications/.test(lab(x)))
    .sort((a, b) => b.x - a.x)[0];
  if (!acct) throw new Error('no account button: ' + nodes.filter((x) => x.y < 60).map(fmt).join(' | '));
  await rt.clickAt(page, acct.x, acct.y, 1200);
  await rt.enableSemantics(page);
  await rt.tap(page, /Sign out|Log out|Logout/i, { wait: 3000 });
  await rt.enableSemantics(page);
}

/** Signs in on the login screen of the page that is already open (no reload). */
async function signIn(page, username, password) {
  await rt.enableSemantics(page);
  await fill(page, /Username/, username);
  await fill(page, /Password/, password);
  await rt.tap(page, 'Sign in', { wait: 5000 });
  await rt.enableSemantics(page);
}

module.exports = { DIR, lab, fmt, state, recorder, inputs, focusField, fill, fillMisses, find, has, write, signOut, signIn };
