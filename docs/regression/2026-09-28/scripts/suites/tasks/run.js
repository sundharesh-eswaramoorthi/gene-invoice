// A6 tasks E2E suite (area "tasks").
//
//   source <e2e>/runtime/tasks/env.sh
//   node run.js <round> [--only api|ui|asof] [--case <regex>] [--merge]   (round also from E2E_ROUND; default 1)
//
// run.sh wraps: env/up.sh tasks 4 --fresh -> node run.js -> env/down.sh tasks (always).
// Order: API cases, UI cases, then the as-of block, which ages the whole database by 10 days (rt.ageWorld) and so
// must come last. Results: runs/r<round>/tasks.json; screenshots: runs/r<round>/tasks/shots/.
const rt = require('../../lib.js');
const H = require('./helpers.js');
const apiCases = require('./api-cases.js');
const uiCases = require('./ui-cases.js');
const asof = require('./asof-cases.js');
const extraCases = require('./extra-cases.js');
const r4 = require('./r4-cases.js');
const r5 = require('./r5-cases.js');

(async () => {
  const args = process.argv.slice(2);
  const round = Number(args.find((a) => /^\d+$/.test(a)) || process.env.E2E_ROUND || 1);
  const arg = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : null; };
  const only = arg('--only');
  const caseRe = arg('--case') ? new RegExp(arg('--case')) : null;
  const R = H.makeResults(round);
  // --merge keeps the cases already in the results file and replaces only those re-run now.
  if (args.includes('--merge')) {
    try { R.cases.push(...JSON.parse(require('fs').readFileSync(R.file, 'utf8')).cases); } catch { /* no earlier file */ }
  }
  const started = Date.now();
  try {
    const w = await H.buildWorld();
    const copies = (!only || only === 'asof') ? await asof.prepare(w) : null;
    if (!only || only === 'api') await apiCases(w, R, caseRe);
    if (!only || only === 'api' || only === 'extra') await extraCases(w, R, caseRe);
    if (!only || only === 'api' || only === 'r4') await r4.api(w, R, caseRe);
    if (!only || only === 'ui') await uiCases.main(w, R, caseRe);
    if (!only || only === 'ui' || only === 'extra') await extraCases.ui(w, R, caseRe);
    if (!only || only === 'ui' || only === 'r4') await r4.ui(w, R, caseRe);
    if (!only || only === 'ui' || only === 'r5') await r5.ui(w, R, caseRe);
    if (!only || only === 'asof') {
      await asof.run(w, R, copies, caseRe);
      await uiCases.asOf(w, R, copies, caseRe);
    }
  } catch (e) {
    R.rec({ id: 'TAS-HARNESS', feature: 'Harness', kind: 'API', prd: 'A6', title: 'Suite aborted', status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
  } finally {
    const file = R.save();
    console.log(`done in ${Math.round((Date.now() - started) / 1000)} s -> ${file}`);
  }
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
