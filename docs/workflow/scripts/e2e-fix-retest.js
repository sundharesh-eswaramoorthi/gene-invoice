export const meta = {
  name: 'e2e-fix-retest',
  description: 'Step 3 extension: fix the round-3 E2E defects, rebuild, re-run all 7 API+UI areas from scratch (round 4), one more fix+retest round if needed (max round 5)',
  phases: [
    { title: 'Triage', detail: 'verify and package defects' },
    { title: 'Fix', detail: 'backend and frontend fix streams' },
    { title: 'Rebuild', detail: 'rebuild jar + web, smoke' },
    { title: 'Test', detail: 'all 7 areas from scratch, 3 at a time' },
  ],
}

const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const DESIGN = `${SP}/design/DESIGN.md`
const B = `${SP}/build`
const E = `${SP}/e2e`
const SNAP = `${B}/snap.sh`
const SDIFF = `${B}/sdiff.sh`
const POOL = 3
const { seed = [], startRound = 4, maxRound = 5, areas = null } = args || {}

const PRD = `PRD — Part A (automation): A1 a rule fires when a Customer, Invoice or Payment is created or updated, or on a daily/weekly schedule; A2 conditions = filters over that entity's fields with AND and OR; A3 actions create a Task, Promise or Dispute, or send an Email; assignees and recipients are picked with the same role-at-a-level model the email To field uses; A4 placeholders {{Customer.Name}}, {{Invoice.Balance}} etc. in subject and body, offered for both levels; a role held by several people resolves to the primary; A5 durable execution: the trigger publishes an event, a consumer performs the action; a rule can never slow down or break the user's save; nothing is lost if the consumer is down; a rule can be scheduled or run from the UI whenever wanted; A6 Tasks on a customer, invoice or payment — title, due date, status, assignees — with somewhere people see their work (My tasks). Part B: B1 regions — every record belongs to exactly one; a user holds different rights in different regions (read in one, manage in another, nothing in a third); the POC book is per region; every list, filter, count and dashboard figure answers "which regions am I asking about"; B2 maker-checker — any change above a configurable threshold doesn't take effect on save, it becomes pending and needs approval from someone else holding the approval right in that record's region; thresholds are per region; a pending change is visible to everyone who can see the record, without having taken effect; B3 as-of reads — every list, report and rule evaluation can be asked as of a date, including which region a record belonged to then and which approvals were outstanding.`

const ISOLATION = `ISOLATION RULES (absolute):
- The user's own services are running on this machine: Docker containers gene-invoice-db (Postgres :5433), gi-verify-db (:55433), dood-srv (:8080), kernel-netguard (:8090), srv-db (:3306), srv-graph (:7474/:7687), llm-service (:50051), plus a Python server on :8081/:8093/:8765 and Java on :8082/:8092. NEVER send a request to, stop, restart, or modify any of them. The ONLY permitted interaction with them is the migration area's read-only pg_dump of gene-invoice-db.
- Everything you start uses ONLY: the Docker container gi-e2e-db (Postgres on host port 18432), backends on ports 18100–18199, web servers on 18200–18299. Only drop/create databases named e2e_* inside gi-e2e-db.
- Never kill a process you did not start (track PIDs in ${E}/runtime/<area>/pids).
- Git: read-only (status/diff/log/show). Never git add/commit/stash/checkout/restore/reset/clean.`

const CONTEXT = `Codebase: ${REPO} — Spring Boot 3.3 / Java 17 backend (backend/), Flutter web frontend (frontend/), mail-service/ (not used; mail transport is 'none', so outbound emails end NOT_SENT/NOT_CONFIGURED — assert the persisted Email row and its rendered content). The PRD below was built in 29 slices from ${DESIGN}, hardened in a whole-build fix pass, then E2E-tested and fixed over 3 rounds. Logs: ${B}/LOG-*.md, ${SP}/harden/FIXLOG-*.md, ${SP}/harden/TRIAGE.md, ${E}/FIXLOG-*.md, ${E}/runs/r*/TRIAGE.md (userDecisions there are the defaults applied for open product questions — do not report those defaults as defects; notably: a role used as a task assignee or email To/Cc goes to every active holder who can see the record, while 'resolves to the primary' applies to placeholders, the From role and the promise Collection POC).

${PRD}`

const ALL_AREAS = [
  { key: 'regions', prefix: 'RGN', idx: 1 },
  { key: 'asof', prefix: 'ASO', idx: 2 },
  { key: 'approvals', prefix: 'APP', idx: 3 },
  { key: 'tasks', prefix: 'TSK', idx: 4 },
  { key: 'automation', prefix: 'AUT', idx: 5 },
  { key: 'regression', prefix: 'RGR', idx: 6 },
  { key: 'migration', prefix: 'MIG', idx: 7 },
]
const AREAS = areas ? ALL_AREAS.filter(a => areas.includes(a.key)) : ALL_AREAS

const RESULTS_SCHEMA = {
  type: 'object',
  properties: {
    total: { type: 'number' }, pass: { type: 'number' }, fail: { type: 'number' }, blocked: { type: 'number' }, notTested: { type: 'number' },
    defects: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' }, severity: { type: 'string', enum: ['high', 'medium', 'low'] }, kind: { type: 'string', enum: ['API', 'UI'] },
      prd: { type: 'string' }, title: { type: 'string' }, steps: { type: 'string' }, expected: { type: 'string' }, actual: { type: 'string' },
      evidence: { type: 'string' }, codeRef: { type: 'string' }, stream: { type: 'string', enum: ['backend', 'frontend', 'both', 'harness'] } },
      required: ['id', 'severity', 'kind', 'title', 'steps', 'expected', 'actual', 'stream'] } },
    resultsFile: { type: 'string' }, notes: { type: 'string' },
  },
  required: ['total', 'pass', 'fail', 'defects', 'resultsFile'],
}
const PKG_SCHEMA = {
  type: 'object',
  properties: {
    packages: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' }, stream: { type: 'string', enum: ['backend', 'frontend'] }, title: { type: 'string' },
      defectIds: { type: 'array', items: { type: 'string' } }, instructions: { type: 'string' } },
      required: ['id', 'stream', 'title', 'defectIds', 'instructions'] } },
    rejected: { type: 'array', items: { type: 'string' } },
    harnessFixes: { type: 'array', items: { type: 'string' } },
    userDecisions: { type: 'array', items: { type: 'string' } },
  },
  required: ['packages', 'rejected'],
}
const FIX_SCHEMA = {
  type: 'object',
  properties: { status: { type: 'string', enum: ['green', 'red', 'blocked'] }, fixed: { type: 'array', items: { type: 'string' } }, rejected: { type: 'array', items: { type: 'string' } }, tests: { type: 'string' }, blocker: { type: 'string' } },
  required: ['status', 'fixed', 'tests'],
}
const DONE = s => s === 'backend'
  ? '`cd backend && mvn -q -o test` passes with ZERO failures/errors and `cd mail-service && mvn -q -o test` passes'
  : '`cd frontend && flutter analyze` reports 0 issues and `cd frontend && flutter test` passes'

async function runPool(items, worker, size) {
  const results = new Array(items.length)
  let next = 0
  async function lane() { while (next < items.length) { const i = next++; results[i] = await worker(items[i], i) } }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, () => lane()))
  return results
}

async function fixRound(label, toFix) {
  phase('Triage')
  const triage = await agent(`${CONTEXT}\n\n${ISOLATION}\n\nYou are the E2E TRIAGE LEAD for fix round ${label}. Defects found by the E2E testers are below. For each: verify it against the code (reproduce on your own env if needed: \`${E}/env/up.sh triage 9 --fresh\` … \`${E}/env/down.sh triage\`); reject false positives and test bugs with a reason (test bugs → harnessFixes with the exact fix; do NOT edit suites yourself); for genuine product questions pick the default the PRD/DESIGN supports and list it in userDecisions. Note which defects are REGRESSIONS of earlier fixes (see ${E}/FIXLOG-*.md) and make the fix instructions cover the earlier fix's intent too, plus a test that pins both. Split 'both' defects into backend and frontend parts. Group into fix packages (B1…, F1…); backend and frontend streams run in parallel, so state any contract explicitly. Write precise instructions incl. the automated test to add. Write your plan to ${E}/runs/${label}-TRIAGE.md.\n\nDEFECTS:\n${JSON.stringify(toFix, null, 1)}`,
    { label: `triage:${label}`, phase: 'Triage', schema: PKG_SCHEMA, effort: 'high' })
  const pkgs = (triage && triage.packages) || []
  const byId = Object.fromEntries(toFix.map(d => [d.id, d]))

  phase('Fix')
  async function chain(s) {
    const out = []
    for (const p of pkgs.filter(x => x.stream === s)) {
      const r = await agent(`${CONTEXT}\n\nYou are fixing E2E package ${p.id} (${s}) — "${p.title}" — fix round ${label}. Git read-only; record progress ONLY with ${SNAP} <name>; inspect with ${SDIFF}. Touch only ${s === 'backend' ? 'backend/ (another agent fixes frontend/ concurrently)' : 'frontend/ (another agent fixes backend/ concurrently)'}. Do NOT run the E2E harness or start servers; do NOT build into ${E}. Never weaken or delete existing assertions.\n\nINSTRUCTIONS:\n${p.instructions}\n\nDEFECTS:\n${JSON.stringify(p.defectIds.map(id => byId[id] || { id }), null, 1)}\n\nFor each defect: confirm the root cause in code, fix it, add an automated test that fails without the fix. If it is a regression of an earlier fix, keep that fix's behaviour and make sure both are pinned by tests. Definition of done: ${DONE(s)}. Append "## E2E ${label} ${p.id} — ${p.title}" to ${E}/FIXLOG-${s}.md. Then run ${SNAP} E2E-${label}-${p.id}. Return the structured result.`,
        { label: `fix:${label}:${p.id}`, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
      out.push({ id: p.id, status: r ? r.status : 'none', fixed: r ? r.fixed : [] })
      if (!r || r.status !== 'green') { log(`${p.id} not green — stopping ${s} stream`); break }
    }
    return out
  }
  const fixes = await parallel([() => chain('backend'), () => chain('frontend')])

  phase('Rebuild')
  const rebuild = await agent(`${ISOLATION}\n\nRebuild the E2E distribution after fix round ${label}: run ${E}/build-dist.sh (it builds the jar and the web app inside ${REPO}; nothing else is building there now). Smoke it: \`${E}/env/up.sh smoke 0 --fresh\`, API login as admin, one UI login screenshot to ${E}/runs/${label}-rebuild-smoke.png, \`${E}/env/down.sh smoke\`. If the build fails, report the exact error (do not edit source). Apply these harness-level fixes from triage to ${E}/lib.js or ${E}/suites/** (only harness/test logic, never product code): ${JSON.stringify((triage && triage.harnessFixes) || [])}. Return a one-paragraph status.`,
    { label: `rebuild:${label}`, phase: 'Rebuild' })
  return { triage: { rejected: triage && triage.rejected, harnessFixes: triage && triage.harnessFixes, userDecisions: triage && triage.userDecisions, packages: pkgs.map(p => ({ id: p.id, stream: p.stream, title: p.title, n: p.defectIds.length })) }, fixes, rebuild }
}

function testerPrompt(a, round, prevDefects) {
  return `${CONTEXT}

${ISOLATION}

You are the E2E TESTER for area "${a.key}" (env index ${a.idx}: API http://localhost:${18100 + a.idx}, WEB http://localhost:${18200 + a.idx}), round ${round}. Read ${E}/README.md and ${E}/lib.js first. Never edit ${REPO}/backend or ${REPO}/frontend; you only test. Other testers run in parallel on other indices with their own databases.

The suite at ${E}/suites/${a.key}/ exists from earlier rounds (run.js / run.sh). Fixes were made and the app rebuilt. Defects your area reported in the previous round: ${JSON.stringify(prevDefects || [])}. Re-run the WHOLE suite fresh (new database) for round ${round}; results to ${E}/runs/r${round}/${a.key}.json and screenshots under ${E}/runs/r${round}/${a.key}/shots/. For each previous defect add or keep a case proving it is fixed (or still failing). Investigate every FAIL: real defect, harness/test bug (fix the test and record it in notes), or flakiness (rerun twice; not reproducible twice = NOT_TESTED). Because the last round's fixes caused one regression, also look one step around each fixed area for new breakage.

Always bring the env down afterwards. Every FAIL: exact steps, expected (PRD/DESIGN reference), actual, evidence, code file:line when found; stream backend/frontend/both/harness. Defect ids ${a.prefix}-R${round}-<n>. Return the structured summary.`
}

const history = []
let pending = seed
let prevByArea = {}
for (const d of seed) (prevByArea[d.area] ||= []).push(d)
let remaining = []
for (let round = startRound; round <= maxRound; round++) {
  if (pending.length) history.push({ fixRound: `r${round - 1}`, ...(await fixRound(`r${round - 1}`, pending)) })
  phase('Test')
  const res = await runPool(AREAS, a => agent(testerPrompt(a, round, prevByArea[a.key]), { label: `test:r${round}:${a.key}`, phase: 'Test', schema: RESULTS_SCHEMA, effort: 'high' })
    .then(r => ({ area: a.key, ...(r || { total: 0, pass: 0, fail: 0, defects: [], resultsFile: '', notes: 'tester returned nothing' }) })), POOL)
  const defects = res.flatMap(r => (r.defects || []).map(d => ({ ...d, area: r.area })))
  const product = defects.filter(d => d.stream !== 'harness')
  log(`round ${round}: ${res.map(r => `${r.area} ${r.pass}/${r.total}`).join(', ')} — product defects ${product.length}`)
  history.push({ round, summary: res.map(r => ({ area: r.area, total: r.total, pass: r.pass, fail: r.fail, blocked: r.blocked, notTested: r.notTested, resultsFile: r.resultsFile })), defects: defects.map(d => `${d.id} [${d.severity}/${d.stream}] ${d.title}`) })
  remaining = product
  prevByArea = {}
  for (const d of defects) (prevByArea[d.area] ||= []).push(d)
  if (!defects.length) { log(`round ${round} clean`); break }
  if (round === maxRound) { log('max round reached; remaining defects go to the final report'); break }
  pending = defects
}
return { history, remaining: remaining.map(d => `${d.id} [${d.severity}/${d.stream}] ${d.title}`) }
