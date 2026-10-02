// B2 maker-checker E2E suite (area "approvals").
//
//   source <e2e>/runtime/approvals/env.sh
//   node run.js <round> [--only api|ui]          (round also from E2E_ROUND; default 1)
//
// Expects a fresh environment (env/up.sh approvals 3 --fresh): the threshold cases change South's limits in
// sequence. run.sh wraps up -> run -> down. Results: runs/r<round>/approvals.json; screenshots:
// runs/r<round>/approvals/shots/.
const rt = require('../../lib.js');
const H = require('./helpers.js');
const apiCases = require('./api-cases.js');
const uiCases = require('./ui-cases.js');
const r4 = require('./r4-cases.js');
const r5 = require('./r5-cases.js');
const r6 = require('./r6-cases.js');

(async () => {
  const args = process.argv.slice(2);
  const round = Number(args.find((a) => /^\d+$/.test(a)) || process.env.E2E_ROUND || 1);
  const onlyIdx = args.indexOf('--only');
  const only = onlyIdx >= 0 ? args[onlyIdx + 1] : null;
  const R = H.makeResults(round);
  const started = Date.now();
  const w = await H.buildWorld();
  try {
    if (!only || only === 'api') await apiCases(w, R);
    if (!only || only === 'api' || only === 'r4') await r4.live(w, R);
    if (!only || only === 'api' || only === 'r5') await r5.live(w, R);
    if (!only || only === 'api' || only === 'r6') await r6.live(w, R);
    if (!only || only === 'ui') await uiCases(w, R);
    if (!only || only === 'ui' || only === 'r4') await r4.liveUi(w, R);
    if (!only || only === 'ui' || only === 'r5') await r5.liveUi(w, R);
    if (!only || only === 'ui' || only === 'r6') await r6.liveUi(w, R);
    // Last: ages the whole database (rt.ageWorld), so nothing may run after it.
    if (!only || only === 'r4' || only === 'r5' || only === 'r6') await r5.prepAsOf(w);
    if (!only || only === 'r4') await r4.asOf(w, R);
    if (only === 'r5' || only === 'r6') rt.ageWorld(10);
    if (!only || only === 'r4' || only === 'r5' || only === 'r6') await r5.asOf(w, R);
    if (!only || only === 'r6') await r6.asOf(w, R);
  } catch (e) {
    R.rec({ id: 'APP-HARNESS', feature: 'Harness', kind: 'API', prd: 'B2', title: 'Suite aborted', status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
  } finally {
    const file = R.save();
    console.log(`done in ${Math.round((Date.now() - started) / 1000)} s -> ${file}`);
  }
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
