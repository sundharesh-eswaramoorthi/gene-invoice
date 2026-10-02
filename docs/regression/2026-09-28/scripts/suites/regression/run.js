// Regression E2E suite (area "regression", env index 6).
//
//   source <e2e>/runtime/regression/env.sh
//   node run.js <round> [--only api|ui] [--case <regex>] [--merge]   (round also from E2E_ROUND; default 1)
//
// run.sh wraps: env/up.sh regression 6 --fresh -> node run.js -> env/down.sh regression (always).
// The world is single-region (DEFAULT only): the pre-existing app must work unchanged for a single-region admin and
// for POC users. Results: runs/r<round>/regression.json; screenshots: runs/r<round>/regression/shots/.
const fs = require('fs');
const H = require('./helpers.js');
const apiCore = require('./api-core.js');
const apiMore = require('./api-more.js');
const apiExtra = require('./api-extra.js');
const uiCases = require('./ui-cases.js');
const r2Cases = require('./r2-cases.js');
const r3Cases = require('./r3-cases.js');
const r4Cases = require('./r4-cases.js');
const r5Cases = require('./r5-cases.js');

(async () => {
  const args = process.argv.slice(2);
  const round = Number(args.find((a) => /^\d+$/.test(a)) || process.env.E2E_ROUND || 1);
  const arg = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : null; };
  const only = arg('--only');
  const caseRe = arg('--case') ? new RegExp(arg('--case')) : null;
  const R = H.makeResults(round);
  if (args.includes('--merge')) {
    try { R.cases.push(...JSON.parse(fs.readFileSync(R.file, 'utf8')).cases); } catch { /* no earlier file */ }
  }
  const started = Date.now();
  try {
    const w = await H.buildWorld();
    if (!only || only === 'api') {
      await apiCore(w, R, caseRe);
      await apiMore(w, R, caseRe);
      await apiExtra(w, R, caseRe);
      await r2Cases(w, R, caseRe, { ui: false });
      await r3Cases(w, R, caseRe, { ui: false });
      await r4Cases(w, R, caseRe, { ui: false });
      await r5Cases(w, R, caseRe, { ui: false });
      R.save();
    }
    if (!only || only === 'ui') {
      await uiCases(w, R, caseRe);
      await r2Cases(w, R, caseRe, { api: false });
      await r3Cases(w, R, caseRe, { api: false });
      await r4Cases(w, R, caseRe, { api: false });
      await r5Cases(w, R, caseRe, { api: false });
    }
    // Last: ages the whole database (lib.ageWorld), so nothing may run after it.
    await r5Cases(w, R, caseRe, { api: false, ui: false, aged: true });
  } catch (e) {
    R.rec({ id: 'REG-HARNESS', feature: 'Harness', kind: 'API', prd: 'B1', title: 'Suite aborted', status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
  } finally {
    const file = R.save();
    console.log(`done in ${Math.round((Date.now() - started) / 1000)} s -> ${file}`);
  }
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
