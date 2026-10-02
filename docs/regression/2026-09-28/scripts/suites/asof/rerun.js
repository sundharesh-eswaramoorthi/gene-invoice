// Re-runs the cases whose id matches argv[3] against the saved scenario and merges them into runs/r<argv[2]>/asof.json.
//   node rerun.js <round> <idRegex> [api|ui|both]
const fs = require('fs'); const path = require('path'); const rt = require('../../lib.js');
const round = Number(process.argv[2]); process.env.E2E_ROUND = String(round);
const only = new RegExp(process.argv[3]); const which = process.argv[4] || 'both';
const R = rt.results({ area: 'asof', round }); R.shotsDir = path.join(rt.HOME, 'runs', `r${round}`, 'asof', 'shots');
if (fs.existsSync(R.file)) R.cases.push(...JSON.parse(fs.readFileSync(R.file, 'utf8')).cases);
const S = JSON.parse(fs.readFileSync(path.join(process.env.E2E_RUNTIME, 'scenario.json'), 'utf8'));
fs.mkdirSync(R.shotsDir, { recursive: true });
async function check(def, fn) {
  if (!only.test(def.id)) return;
  const base = { kind: 'API', ...def };
  try {
    const o1 = await fn(1);
    if (o1.ok) return R.rec({ ...base, status: 'PASS', actual: o1.actual, evidence: o1.evidence || o1.actual, screenshot: o1.screenshot || '' });
    let o2; try { o2 = await fn(2); } catch (e) { o2 = { ok: false, actual: `second attempt threw: ${e.message}` }; }
    if (o2.ok) return R.rec({ ...base, status: 'NOT_TESTED', actual: `attempt 1 failed (${o1.actual}); attempt 2 passed (${o2.actual}) — not reproducible`, evidence: o1.evidence || '', screenshot: o1.screenshot || '' });
    return R.rec({ ...base, status: 'FAIL', severity: def.severity || 'medium', actual: `${o2.actual} [reproduced 2/2]`, evidence: o2.evidence || o1.evidence || `attempt 1: ${o1.actual}`, screenshot: o2.screenshot || o1.screenshot || '', codeRef: o2.codeRef || def.codeRef || '' });
  } catch (e) {
    return R.rec({ ...base, status: 'BLOCKED', actual: `harness/setup error: ${e.message}`.slice(0, 1500), evidence: (e.stack || '').split('\n').slice(0, 4).join(' | ') });
  }
}
(async () => {
  if (which !== 'ui') await require('./api-cases.js')({ S, check, R });
  if (which !== 'api') await require('./ui-cases.js')({ S, check, R, shotsDir: R.shotsDir });
  R.save();
})();
