// Re-runs selected UI/API cases against the saved scenario without touching the results file.
const fs = require('fs'); const path = require('path'); const rt = require('../../lib.js');
const S = JSON.parse(fs.readFileSync(path.join(process.env.E2E_RUNTIME, 'scenario.json'), 'utf8'));
const only = new RegExp(process.argv[2]);
const shotsDir = path.join(rt.HOME, 'runs', 'r2', 'shots', 'asof', 'recheck'); fs.mkdirSync(shotsDir, { recursive: true });
const check = async (def, fn) => { if (!only.test(def.id)) return; for (const n of [1, 2]) { const o = await fn(n); console.log(def.id, n, o.ok ? 'PASS' : 'FAIL', o.actual, o.screenshot || ''); } };
(async () => { const f = process.argv[3] || 'ui'; await require(`./${f}-cases.js`)({ S, check, shotsDir }); })();
