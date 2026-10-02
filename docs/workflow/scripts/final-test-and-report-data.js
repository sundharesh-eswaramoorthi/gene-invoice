export const meta = {
  name: 'final-test-and-report-data',
  description: 'Step 4 (test only, no fixes): fresh build + unit suites, re-run all 7 E2E suites as written plus an independent exploratory pass per area, then compile API and UI report data',
  phases: [
    { title: 'Build', detail: 'fresh dist from current source, unit suites, snapshot FINAL' },
    { title: 'Test', detail: '7 areas: existing suite as-is + independent exploratory cases' },
    { title: 'Compile', detail: 'report data for the API and UI reports' },
  ],
}

const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const DESIGN = `${SP}/design/DESIGN.md`
const B = `${SP}/build`
const E = `${SP}/e2e`
const F = `${E}/final`
const SNAP = `${B}/snap.sh`
const POOL = 3

const PRD = `PRD — Part A (automation): A1 a rule fires when a Customer, Invoice or Payment is created or updated, or on a daily/weekly schedule; A2 conditions = filters over that entity's fields with AND and OR; A3 actions create a Task, Promise or Dispute, or send an Email; assignees and recipients are picked with the same role-at-a-level model the email To field uses; A4 placeholders {{Customer.Name}}, {{Invoice.Balance}} etc. in subject and body, offered for both levels; a role held by several people resolves to the primary; A5 durable execution: the trigger publishes an event, a consumer performs the action; a rule can never slow down or break the user's save; nothing is lost if the consumer is down; a rule can be scheduled or run from the UI whenever wanted; A6 Tasks on a customer, invoice or payment — title, due date, status, assignees. Part B: B1 regions — every record belongs to exactly one; a user holds different rights in different regions (read in one, manage in another, nothing in a third); the POC book is per region; every list, filter, count and dashboard figure answers "which regions am I asking about"; B2 maker-checker — any change above a configurable threshold doesn't take effect on save, it becomes pending and needs approval from someone else holding the approval right in that record's region; thresholds are per region; a pending change is visible to everyone who can see the record, without having taken effect; B3 as-of reads — every list, report and rule evaluation can be asked as of a date, including which region a record belonged to then and which approvals were outstanding.`

const ISOLATION = `ISOLATION RULES (absolute):
- The user's own services run on this machine: Docker containers gene-invoice-db (:5433), gi-verify-db (:55433), dood-srv (:8080), kernel-netguard (:8090), srv-db (:3306), srv-graph (:7474/:7687), llm-service (:50051), a Python server on :8081/:8093/:8765 and Java on :8082/:8092. NEVER send a request to, stop, restart or modify any of them. The only permitted interaction is the migration area's read-only pg_dump of gene-invoice-db.
- Use only gi-e2e-db (host port 18432; databases e2e_* only), backends on 18100–18199, web servers on 18200–18299. Never kill a process you did not start.
- Git: read-only. Never git add/commit/stash/checkout/restore/reset/clean.`

const FINAL_RULES = `THIS IS THE FINAL TEST-ONLY RUN. NO FIXES OF ANY KIND:
- Never edit anything under ${REPO} (backend/, frontend/, mail-service/, anything).
- Never edit the existing suites under ${E}/suites/** or ${E}/lib.js. If an existing case is broken because of the harness (not the product), record it as BLOCKED with the reason — do not repair it.
- Report what you observe, exactly. A failure you cannot reproduce twice is NOT_TESTED with a note, not FAIL.`

const CONTEXT = `Codebase: ${REPO} (Spring Boot backend, Flutter web frontend). The PRD below was built in 29 slices from ${DESIGN}, hardened, and E2E-tested and fixed over 6 rounds (history: ${E}/runs/r1..r6, fix logs ${E}/FIXLOG-*.md, triage decisions ${E}/runs/*TRIAGE*.md and ${E}/runs/r*/TRIAGE.md and ${SP}/harden/TRIAGE.md — their 'userDecisions' are the defaults applied for open product questions; do not report those defaults as defects, but DO report behaviour that contradicts them). Harness docs: ${E}/README.md, helper library ${E}/lib.js. Mail transport is 'none' (assert persisted Email rows and rendered content).

${PRD}`

// ------------------------------------------------------------------ Build
phase('Build')
const build = await agent(`${CONTEXT}

${ISOLATION}

You prepare the FINAL test run. Do not edit any source. Steps:
1. Rebuild the E2E distribution from the current source: ${E}/build-dist.sh (log to ${F}/build-dist.log). Record dist/BUILT_AT.
2. Record the tested source: run ${SNAP} FINAL (prints the commit object id) and \`git -C ${REPO} diff --stat refs/snapshots/baseline refs/snapshots/FINAL | tail -1\`, plus the per-directory split (backend/, frontend/, mail-service/) of files changed / insertions / deletions.
3. Unit and static checks on that source, inside ${REPO} (no one else builds there now): \`cd backend && mvn -o test\` (counts from target/surefire-reports: tests, failures, errors, skipped, and the number of test classes), \`cd mail-service && mvn -o test\`, \`cd frontend && flutter analyze\` (issue count) and \`cd frontend && flutter test\` (passed/failed). Report exact numbers and any failure verbatim — do not fix anything.
4. Smoke the dist: ${E}/env/up.sh smoke 0 --fresh, API login, one UI login screenshot to ${F}/smoke.png, ${E}/env/down.sh smoke.
Write everything to ${F}/build.json: {builtAt, snapshot, diffstat:{total, backend, frontend, mailService}, unit:{backend:{tests,failures,errors,skipped,classes}, mailService:{...}, flutterAnalyze:{issues}, flutterTest:{passed,failed}}, smoke:"ok"|detail}. Return the same JSON as text.`,
  { label: 'final:build', phase: 'Build' })

// ------------------------------------------------------------------ Test
const AREAS = [
  { key: 'regions', prefix: 'RGN', idx: 1, focus: 'B1' },
  { key: 'asof', prefix: 'ASO', idx: 2, focus: 'B3' },
  { key: 'approvals', prefix: 'APP', idx: 3, focus: 'B2' },
  { key: 'tasks', prefix: 'TSK', idx: 4, focus: 'A6, A3 assignees' },
  { key: 'automation', prefix: 'AUT', idx: 5, focus: 'A1–A5' },
  { key: 'regression', prefix: 'RGR', idx: 6, focus: 'pre-existing features' },
  { key: 'migration', prefix: 'MIG', idx: 7, focus: 'upgrade of existing data' },
]
const RESULTS_SCHEMA = {
  type: 'object',
  properties: {
    suite: { type: 'object', properties: { total: { type: 'number' }, pass: { type: 'number' }, fail: { type: 'number' }, blocked: { type: 'number' }, notTested: { type: 'number' }, api: { type: 'number' }, ui: { type: 'number' }, resultsFile: { type: 'string' } }, required: ['total', 'pass', 'fail', 'resultsFile'] },
    explore: { type: 'object', properties: { total: { type: 'number' }, pass: { type: 'number' }, fail: { type: 'number' }, blocked: { type: 'number' }, notTested: { type: 'number' }, api: { type: 'number' }, ui: { type: 'number' }, resultsFile: { type: 'string' } }, required: ['total', 'pass', 'fail', 'resultsFile'] },
    defects: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' }, severity: { type: 'string', enum: ['high', 'medium', 'low'] }, kind: { type: 'string', enum: ['API', 'UI'] }, prd: { type: 'string' },
      title: { type: 'string' }, steps: { type: 'string' }, expected: { type: 'string' }, actual: { type: 'string' }, evidence: { type: 'string' }, screenshot: { type: 'string' }, codeRef: { type: 'string' },
      source: { type: 'string', enum: ['suite', 'explore'] } }, required: ['id', 'severity', 'kind', 'title', 'steps', 'expected', 'actual', 'source'] } },
    notes: { type: 'string' },
  },
  required: ['suite', 'explore', 'defects'],
}

phase('Test')
async function runPool(items, worker, size) {
  const results = new Array(items.length); let next = 0
  async function lane() { while (next < items.length) { const i = next++; results[i] = await worker(items[i], i) } }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, () => lane()))
  return results
}
const areaResults = await runPool(AREAS, a => agent(`${CONTEXT}

${ISOLATION}

${FINAL_RULES}

You are the FINAL TESTER for area "${a.key}" (focus ${a.focus}; env index ${a.idx}: API http://localhost:${18100 + a.idx}, WEB http://localhost:${18200 + a.idx}). Read ${E}/README.md and ${E}/lib.js. The distribution was rebuilt from the final source just now.

PART 1 — SUITE, AS WRITTEN: run the existing suite at ${E}/suites/${a.key}/ exactly as it is (its run.sh / run.js with round label 'final') on a FRESH database, writing results to ${E}/runs/final/${a.key}.json and screenshots under ${E}/runs/final/${a.key}/shots/. Do not modify it.

PART 2 — INDEPENDENT EXPLORATORY PASS: you did not write that suite. Without reading its case list first, write NEW cases straight from the PRD clauses for your focus and from DESIGN.md §6 (API) / §7 (UI), aiming at what a suite written by someone else might have missed: unusual role/region combinations, boundaries (threshold exactly equal, empty regionIds, today vs yesterday), error messages and status codes, UI states (empty, loading, error, read-only), keyboard/mouse flows, and cross-feature interactions (e.g. automation × maker-checker × regions × as-of). At least 15 cases, both API and UI (at least 5 UI), each citing its PRD clause. Put them in a NEW file ${E}/suites/${a.key}/final-explore.js (you may import ${E}/lib.js) and run them on a fresh database; results to ${E}/runs/final/${a.key}-explore.json (same JSON case format as README), screenshots under ${E}/runs/final/${a.key}/shots/explore/.

Always bring your env down afterwards. For every FAIL give exact steps, expected (PRD/DESIGN reference), actual (observed), evidence (response excerpt, screenshot path, log line) and code file:line when you can find it. Defect ids ${a.prefix}-F-<n>. Return the structured summary (suite counts, explore counts, API/UI split, defects).`,
  { label: `final:${a.key}`, phase: 'Test', schema: RESULTS_SCHEMA, effort: 'high' })
  .then(r => ({ area: a.key, ...(r || { suite: { total: 0, pass: 0, fail: 0, resultsFile: '' }, explore: { total: 0, pass: 0, fail: 0, resultsFile: '' }, defects: [], notes: 'tester returned nothing' }) })), POOL)

const tot = k => areaResults.reduce((s, r) => s + ((r.suite && r.suite[k]) || 0) + ((r.explore && r.explore[k]) || 0), 0)
log(`final: ${tot('pass')}/${tot('total')} pass, ${tot('fail')} fail, ${tot('blocked')} blocked; defects ${areaResults.reduce((s, r) => s + (r.defects || []).length, 0)}`)

// ------------------------------------------------------------------ Compile
phase('Compile')
const compiled = await agent(`${CONTEXT}

You compile the data for two final reports — an API TEST REPORT and a UI TEST REPORT — from the final run. Do not edit source or suites; write only under ${F}/.

Inputs: ${F}/build.json; final results ${E}/runs/final/*.json (suite files <area>.json and exploratory files <area>-explore.json); screenshots under ${E}/runs/final/**/shots/; earlier rounds ${E}/runs/r1..r6/*.json (for the history table); fix logs ${E}/FIXLOG-*.md and ${SP}/harden/FIXLOG-*.md; triage decisions (all 'userDecisions' in ${SP}/harden/TRIAGE.md, ${E}/runs/r*/TRIAGE.md, ${E}/runs/*TRIAGE*.md). Tester summaries from this run: ${JSON.stringify(areaResults.map(r => ({ area: r.area, suite: r.suite, explore: r.explore, defects: r.defects, notes: r.notes })))}

Produce ${F}/report-data.json with:
- meta: {generatedFrom, builtAt, snapshot, environment: {database:'PostgreSQL 16 (isolated container gi-e2e-db)', browser:'Chromium via playwright-core (headless)', mailTransport:'none', ports}, unit: from build.json, diffstat}
- api: {summary:{total,pass,fail,blocked,notTested}, byArea:[{area,total,pass,fail,blocked,notTested}], byPrd:[{clause:'A1'…'B3' plus 'Regression' and 'Migration', title (short clause text), total, pass, fail, status:'PASS'|'FAIL'|'PARTIAL'}], cases:[{id,area,source:'suite'|'explore',prd,title,status,severity,steps,expected,actual,evidence,codeRef}], defects:[...]} — API cases only (kind API).
- ui: same shape for UI cases, plus each case's screenshot path, and 'gallery': up to 40 representative screenshots (at least one per UI feature: region selector, as-of picker + read-only banner, dashboard, regions/grants admin, approvals list/detail/thresholds, pending banner & list chip, tasks tab & My tasks, rule editor with AND/OR conditions, run now, run history, email placeholders, plus every FAIL screenshot). Convert each chosen screenshot to a compressed JPEG max 1280px wide, quality 72 (\`sips -s format jpeg -s formatOptions 72 -Z 1280 in.png --out out.jpg\`) into ${F}/assets/, and list {file, caption, caseId, area, prd}.
- history: per round r1..r6 and final: pass/total per area (read the round JSONs; r6 covered only regions, asof, approvals), and defects found per round.
- decisions: every userDecision, deduplicated, each with the defect/finding id it came from and a one-line plain-language summary.
- knownLimitations: anything testers recorded as a harness limitation or NOT_TESTED/BLOCKED with the reason.
Every number must come from the files (recount, don't trust summaries). Return a short text summary with the headline numbers for API and UI and the list of defects.`,
  { label: 'final:compile', phase: 'Compile', effort: 'high' })

return {
  build,
  areas: areaResults.map(r => ({ area: r.area, suite: r.suite, explore: r.explore, defects: (r.defects || []).map(d => `${d.id} [${d.severity}/${d.kind}/${d.source}] ${d.title}`) })),
  compiled,
}
