#!/usr/bin/env node
// E2E suite for area "asof" (PRD B3: as-of reads), API + UI.
//
//   source <e2e>/runtime/asof/env.sh
//   node run.js <round>            # builds the scenario on the area's database, runs every case
//   ASOF_REUSE=1 node run.js 1     # reuse runtime/asof/scenario.json (same database, no re-seed)
//   ASOF_ONLY=api|ui|move node run.js 1 # one part only (move = the r5 region-move add-on; it ages the world, run it last)
//
// Results: runs/r<round>/asof.json; screenshots: runs/r<round>/asof/shots/.
// Every FAIL is re-checked once more before it is recorded (a second failure = reproduced; a pass on the
// second attempt = NOT_TESTED, flaky).
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const scenario = require('./scenario.js');

const round = Number(process.argv[2] || process.env.E2E_ROUND || 1);
process.env.E2E_ROUND = String(round);
const R = rt.results({ area: 'asof', round });
// With ASOF_REUSE, keep the cases already recorded this round (a partial re-run merges into them).
if (process.env.ASOF_REUSE && fs.existsSync(R.file)) R.cases.push(...JSON.parse(fs.readFileSync(R.file, 'utf8')).cases);
const shotsDir = path.join(rt.HOME, 'runs', `r${round}`, 'asof', 'shots'); R.shotsDir = shotsDir;
fs.mkdirSync(shotsDir, { recursive: true });
const stateFile = path.join(process.env.E2E_RUNTIME || path.join(rt.HOME, 'runtime', 'asof'), 'scenario.json');

async function check(def, fn) {
  const base = { kind: 'API', ...def };
  try {
    const o1 = await fn(1);
    if (o1.ok) return R.rec({ ...base, status: 'PASS', actual: o1.actual, evidence: o1.evidence || o1.actual, screenshot: o1.screenshot || '' });
    let o2;
    try { o2 = await fn(2); } catch (e) { o2 = { ok: false, actual: `second attempt threw: ${e.message}` }; }
    if (o2.ok) {
      return R.rec({ ...base, status: 'NOT_TESTED', actual: `attempt 1 failed (${o1.actual}); attempt 2 passed (${o2.actual}) — not reproducible`, evidence: o1.evidence || '', screenshot: o1.screenshot || '' });
    }
    return R.rec({ ...base, status: 'FAIL', severity: def.severity || 'medium', actual: `${o2.actual} [reproduced 2/2]`, evidence: o2.evidence || o1.evidence || `attempt 1: ${o1.actual}`, screenshot: o2.screenshot || o1.screenshot || '', codeRef: o2.codeRef || def.codeRef || '' });
  } catch (e) {
    return R.rec({ ...base, status: 'BLOCKED', actual: `harness/setup error: ${e.message}`.slice(0, 1500), evidence: (e.stack || '').split('\n').slice(0, 4).join(' | ') });
  }
}

(async () => {
  let S;
  if (process.env.ASOF_REUSE && fs.existsSync(stateFile)) {
    S = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    console.log(`[asof] reusing scenario ${stateFile} (tag ${S.tag})`);
  } else {
    S = await scenario.build();
    scenario.save(S, stateFile);
    console.log(`[asof] scenario built (tag ${S.tag}); saved ${stateFile}`);
  }
  const only = process.env.ASOF_ONLY;
  try {
    if (!only || only === 'api') await require('./api-cases.js')({ S, check, R });
    if (!only || only === 'ui') await require('./ui-cases.js')({ S, check, R, shotsDir });
    // Round 5: region move with outstanding approvals (near RGN-R4-1). Runs LAST: it ages the world again.
    if (!only || only === 'move') { await require('./move-cases.js')({ S, check, shotsDir }); scenario.save(S, stateFile); }
  } finally {
    const f = R.save();
    console.log(`[asof] results ${f}`);
  }
})().catch((e) => { console.error(e); try { R.save(); } catch { /* ignore */ } process.exit(1); });
