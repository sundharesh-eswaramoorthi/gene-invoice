// Automation (PRD A1-A5) E2E suite, area "automation".
//
//   E2E=docs/regression/2026-09-28/scripts
//   $E2E/env/up.sh automation 5 --fresh && source $E2E/runtime/automation/env.sh
//   node run.js <round> [--only api|ui|asof|durable|triggers|...]   (round also from E2E_ROUND; default 1)
//   $E2E/env/down.sh automation
//
// run.sh wraps up -> run -> down. Results: runs/r<round>/automation.json; screenshots: runs/r<round>/automation/shots/.
// The durability part kills (kill -9) and restarts this area's own backend (restart-backend.sh; PID from
// runtime/automation/pids only) on the same database.
const rt = require('../../lib.js');
const H = require('./helpers.js');
const rulesCases = require('./api-rules.js');
const conditionCases = require('./api-conditions.js');
const actionCases = require('./api-actions.js');
const { startSchedules, checkSchedules, execCases } = require('./api-exec.js');
const rightsCases = require('./api-rights.js');
const triggerCases = require('./api-triggers.js');
const { asOfCases, durabilityCases } = require('./api-durable.js');
const uiCases = require('./ui-cases.js');
const { eventCases, sweepCases, durableBurstCases } = require('./api-r2.js');
const { r3Cases, r3UiCases } = require('./api-r3.js');
const { r4Cases, r4AsOfCases, r4UiCases } = require('./api-r4.js');
const { r5Cases, r5AsOfCases, r5UiCases } = require('./api-r5.js');

(async () => {
  const args = process.argv.slice(2);
  const round = Number(args.find((a) => /^\d+$/.test(a)) || process.env.E2E_ROUND || 1);
  process.env.E2E_ROUND = String(round);
  const oi = args.indexOf('--only');
  const only = oi >= 0 ? args[oi + 1].split(',') : null;
  const want = (k) => !only || only.includes(k);
  const R = H.makeResults(round);
  const started = Date.now();
  const t = await rt.adminToken();
  const w = await rt.seedWorld(t);
  const ctx = { R, t, w, adminId: (await rt.me(t)).id, cleanup: [] };
  const step = async (name, fn) => {
    const t0 = Date.now();
    try { await fn(); } catch (e) {
      R.rec({ id: `AUT-HARNESS-${name}`, feature: 'Harness', kind: 'API', prd: 'A5', title: `Suite step "${name}" aborted`, status: 'BLOCKED', actual: String(e && e.stack || e).slice(0, 2000) });
    }
    console.log(`[step] ${name} ${Math.round((Date.now() - t0) / 1000)} s`);
    R.save();
  };
  try {
    if (want('schedules')) await step('startSchedules', () => startSchedules(ctx));
    if (want('api') || want('rules')) await step('rules', () => rulesCases(ctx));
    if (want('api') || want('conditions')) await step('conditions', () => conditionCases(ctx));
    if (want('api') || want('actions')) await step('actions', () => actionCases(ctx));
    if (want('api') || want('exec')) await step('exec', () => execCases(ctx));
    if (want('api') || want('rights')) await step('rights', () => rightsCases(ctx));
    if (want('api') || want('triggers')) await step('triggers', () => triggerCases(ctx));
    if (want('api') || want('events')) await step('events', () => eventCases(ctx));
    if (want('api') || want('r3')) await step('r3', () => r3Cases(ctx));
    if (want('api') || want('r4')) await step('r4', () => r4Cases(ctx));
    if (want('api') || want('r5')) await step('r5', () => r5Cases(ctx));
    if (want('schedules')) await step('checkSchedules', () => checkSchedules(ctx));
    if (want('ui')) await step('ui', () => uiCases(ctx));
    if (want('ui') || want('r3ui')) await step('r3ui', () => r3UiCases(ctx));
    if (want('asof')) await step('asof', () => asOfCases(ctx));
    if (want('durable')) await step('durable', () => durabilityCases(ctx));
    if (want('durable')) await step('durableBurst', () => durableBurstCases(ctx));
    if (want('sweep')) await step('sweep', () => sweepCases(ctx));
    // Round 4: ages the database 10 days (as-of reads need history), so it runs after everything else.
    if (want('asof') || want('r4asof')) await step('r4asof', () => r4AsOfCases(ctx));
    if (want('ui') || want('r4ui')) await step('r4ui', () => r4UiCases(ctx));
    // Round 5: ages another 5 days, after r4asof.
    if (want('asof') || want('r5asof')) await step('r5asof', () => r5AsOfCases(ctx));
    if (want('ui') || want('r5ui')) await step('r5ui', () => r5UiCases(ctx));
  } finally {
    // Stop every rule the suite made, so nothing keeps acting.
    const tok = await rt.adminToken().catch(() => null);
    if (tok) for (const id of [...new Set(ctx.cleanup)]) await rt.automation.disable(tok, id).catch(() => {});
    const file = R.save();
    console.log(`done in ${Math.round((Date.now() - started) / 1000)} s -> ${file}`);
  }
  process.exit(0);
})().catch((e) => { console.error('FATAL', e); process.exit(2); });
