export const meta = {
  name: 'e2e-test-and-fix',
  description: 'Step 3: build an isolated E2E harness (own Postgres container, 18xxx ports), run API+UI test areas, triage and fix defects, rebuild, retest until clean (max 3 rounds)',
  phases: [
    { title: 'Setup', detail: 'harness, isolated Postgres, dist build, smoke' },
    { title: 'Test', detail: 'API + UI test areas, 3 at a time' },
    { title: 'Triage', detail: 'dedupe, verify, package defects' },
    { title: 'Fix', detail: 'backend and frontend fix streams' },
    { title: 'Rebuild', detail: 'rebuild jar + web, smoke' },
  ],
}

const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const DESIGN = `${SP}/design/DESIGN.md`
const B = `${SP}/build`
const E = `${SP}/e2e`
const SNAP = `${B}/snap.sh`
const SDIFF = `${B}/sdiff.sh`
const MAX_ROUNDS = 3
const POOL = 3

const PRD = `PRD — Part A (automation): A1 a rule fires when a Customer, Invoice or Payment is created or updated, or on a daily/weekly schedule; A2 conditions = filters over that entity's fields with AND and OR; A3 actions create a Task, Promise or Dispute, or send an Email; assignees and recipients are picked with the same role-at-a-level model the email To field uses; A4 placeholders {{Customer.Name}}, {{Invoice.Balance}} etc. in subject and body, offered for both levels; a role held by several people resolves to the primary; A5 durable execution: the trigger publishes an event, a consumer performs the action; a rule can never slow down or break the user's save; nothing is lost if the consumer is down; a rule can be scheduled or run from the UI whenever wanted; A6 Tasks on a customer, invoice or payment — title, due date, status, assignees — with somewhere people see their work (My tasks). Part B: B1 regions — every record belongs to exactly one; a user holds different rights in different regions (read in one, manage in another, nothing in a third); the POC book is per region; every list, filter, count and dashboard figure answers "which regions am I asking about"; B2 maker-checker — any change above a configurable threshold doesn't take effect on save, it becomes pending and needs approval from someone else holding the approval right in that record's region; thresholds are per region; a pending change is visible to everyone who can see the record, without having taken effect; B3 as-of reads — every list, report and rule evaluation can be asked as of a date, including which region a record belonged to then and which approvals were outstanding.`

const ISOLATION = `ISOLATION RULES (absolute):
- The user's own services are running on this machine: Docker containers gene-invoice-db (Postgres :5433), gi-verify-db (:55433), dood-srv (:8080), kernel-netguard (:8090), srv-db (:3306), srv-graph (:7474/:7687), llm-service (:50051), plus a Python server on :8081/:8093/:8765 and Java on :8082/:8092. NEVER send a request to, stop, restart, or modify any of them. The ONLY permitted interaction with them is the migration area's read-only pg_dump of gene-invoice-db.
- Everything you start uses ONLY: the Docker container gi-e2e-db (Postgres on host port 18432), backends on ports 18100–18199, web servers on 18200–18299. Only drop/create databases named e2e_* inside gi-e2e-db.
- Never kill a process you did not start (track PIDs in ${E}/runtime/<area>/pids).
- Git: read-only (status/diff/log/show). Never git add/commit/stash/checkout/restore/reset/clean.`

const CONTEXT = `Codebase: ${REPO} — Spring Boot 3.3 / Java 17 backend (backend/), Flutter web frontend (frontend/), mail-service/ (not used here; mail transport is 'none', so outbound emails end NOT_SENT/NOT_CONFIGURED — assert the persisted Email row and its rendered content instead). The PRD below was built in 29 slices from ${DESIGN} (see §6 REST contract, §7 frontend, §8 tests) and hardened in a whole-build fix pass. Build logs: ${B}/LOG-backend.md, ${B}/LOG-frontend.md, ${SP}/harden/FIXLOG-*.md, ${SP}/harden/TRIAGE.md (incl. userDecisions — defaults applied for open product questions; do not report those defaults as defects).

${PRD}`

// ------------------------------------------------------------------ Setup
phase('Setup')
const setup = await agent(`${CONTEXT}

${ISOLATION}

You are the E2E HARNESS ENGINEER. Build a reusable end-to-end test harness under ${E}/ (create it). The team's previous regression harness is in git history — read it first and adapt it (do not restore it into the repo): \`git show ae7e378^:docs/regression/2026-09-21/scripts/TESTER-RULES.md\`, \`git show ae7e378^:docs/regression/2026-09-21/scripts/lib.js\`, \`git show ae7e378^:docs/regression/2026-09-21/scripts/package.json\`, and one UI script e.g. \`git show ae7e378^:docs/regression/2026-09-21/scripts/area-b/02-ui.js\` (Flutter CanvasKit is driven through the accessibility/semantics tree with playwright-core).

Deliverables:
1. ${E}/package.json + \`npm install\` of playwright-core (use the Chromium already cached in ~/Library/Caches/ms-playwright — pass executablePath explicitly if the playwright-core version expects a different revision). Headless.
2. ${E}/build-dist.sh: (a) \`cd ${REPO}/backend && mvn -o -q package -DskipTests\` → copy the jar to ${E}/dist/backend.jar; (b) \`cd ${REPO}/frontend && flutter build web --release --dart-define=API_BASE_URL=http://localhost:18199\` → copy build/web to ${E}/dist/web. Run it now. (Only this setup step and later 'rebuild' steps may build inside ${REPO}; nobody else will be building there while you do.)
3. ${E}/env/up.sh <area> <index> and ${E}/env/down.sh <area>: ensure container gi-e2e-db exists and runs (\`docker run -d --name gi-e2e-db -e POSTGRES_USER=e2e -e POSTGRES_PASSWORD=e2e -p 18432:5432 postgres:16\` if absent; postgres:16 image is already local); drop+create database e2e_<area>; start \`java -Xmx512m -jar ${E}/dist/backend.jar\` with SPRING_PROFILES_ACTIVE=dev, DB_URL=jdbc:postgresql://localhost:18432/e2e_<area>, DB_USER=e2e, DB_PASSWORD=e2e, SERVER_PORT=18100+index, SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5, MAIL_TRANSPORT=none, DOCUMENT_ROOT=${E}/runtime/<area>/documents, logs to ${E}/runtime/<area>/backend.log; copy ${E}/dist/web to ${E}/runtime/<area>/web and replace the baked 'localhost:18199' with 'localhost:<18100+index>' in the compiled JS (same length, verify the replacement count > 0); serve it on 18200+index (a small node or python static server); wait until the API answers POST /api/auth/login for admin/admin123; print API and WEB URLs; record PIDs. down.sh stops only those PIDs and drops the database. Make both idempotent.
4. ${E}/lib.js: adapted helper library reading API/WEB from env (E2E_API, E2E_WEB) with: api(method, path, {token, body, query, headers}) that never throws on status; login; adminToken; uniq; createStaff(token, roleName, prefix) ; createCustomer(token, {name, regionId,...}); openApp({token|username/password, width, height}) → {page, apiErrors, pageErrors, close}; go, semantics, tap, clickAt, typeText, shot(page, dir, name), waitFor(fn, {timeoutMs, intervalMs}) for polling async automation; plus PRD helpers you verify against the real API contract (read DESIGN.md §6 AND the actual controllers): createRegion, setGrants(userId, [{regionId, accessLevel, recordScope}]), setThresholds(regionId, rows), scoped(query, {regionIds, asOf}), createInvoice/payment/promise helpers, and seedWorld(adminToken) that builds a standard world: 3 regions (North, South, West) besides the default; users: 'nora' (MANAGE North, READ South, no West), 'sam' (APPROVE South, MANAGE North), 'bob' (BOOK-scope POC in North), 'wendy' (MANAGE West only), 'cust' a customer login; a couple of customers with invoices and payments in each region; thresholds in South. Return all ids/tokens.
5. ${E}/README.md: tester rules (adapt TESTER-RULES.md: isolation above, helper library, driving Flutter CanvasKit via semantics, how to bring an env up/down, per-area DB isolation so testers never share data, the results JSON format below, evidence and screenshot conventions, 'a defect you cannot reproduce twice is NOT_TESTED, not FAIL').
   Results JSON format (one file per area per round at ${E}/runs/r<N>/<area>.json): {"area","round","env":{"api","web"},"cases":[{"id","feature","kind":"API"|"UI","prd":"B1"|…,"title","status":"PASS"|"FAIL"|"BLOCKED"|"NOT_TESTED","severity":"high"|"medium"|"low"|"","steps","expected","actual","evidence","screenshot","codeRef"}]}.
6. Smoke: up.sh smoke 0 → API login + seedWorld + a UI login as admin with a screenshot of the dashboard and of the region selector → down.sh smoke. Fix the harness until this works end to end.
Return a short summary: what works, the exact commands, any limitation (e.g. something in the UI that semantics can't reach).`,
  { label: 'setup', phase: 'Setup', effort: 'high' })

// ------------------------------------------------------------------ Areas
const AREAS = [
  { key: 'regions', idx: 1, scope: `B1 regions end to end, API and UI: the read/manage/none matrix across North/South/West for lists, detail, counts, exports, bulk, dashboard figures and record tabs (404 when invisible, 403 when visible but not allowed); the region selector narrowing every list/count/dashboard figure (and the X-View-As-Of / regionIds echo in PageResponse); POC book per region (BOOK scope sees only its book, per region); writes needing MANAGE in the record's region (create/update/delete/bulk); region admin, grants editor, /me regions; customer region move (children follow, history kept); customer logins confined to their customer.` },
  { key: 'asof', idx: 2, scope: `B3 as-of reads end to end, API and UI: lists, counts, detail pages, record tabs, dashboard figures and exports as of past dates (create data, change it, then query as of before/after the change — note history starts when the app first boots, so create and change records within the run and use asOf dates relative to the server clock; if day granularity makes 'before the change' impossible within one run, test via the earliest possible date and the history range endpoint, and record that limitation); region a record belonged to then (move a customer, query before/after); approvals outstanding then; rule preview as of a date; read-only mode (every write refused with AS_OF_READ_ONLY via API, and the UI banner + disabled actions); invalid/future/before-history dates.` },
  { key: 'approvals', idx: 3, scope: `B2 maker-checker end to end, API and UI: per-region thresholds (set via API/UI); each gated operation above threshold returns 202 with a pending change and does NOT take effect (invoice create/update/cancel, payment record/amount change/void, promise create/update/override, dispute approval — whatever DESIGN §5.11 gates); at/below threshold applies immediately; pending visible to everyone who can see the record (banner on detail, 'Awaiting approval' chip on lists, approvals list) and not to those who can't; approve by someone else with APPROVE in the record's region applies it; self-approval refused; approver from another region refused; reject/withdraw; splitting into below-threshold changes (cumulative window); notifications to approvers; threshold loosening held for a second approver; the 202 handling in the UI at every write site (sent-for-approval notice, no crash).` },
  { key: 'tasks', idx: 4, scope: `A6 tasks end to end, API and UI: tasks on customer, invoice and payment (title, due date, status transitions, assignees); assignees as a person or a role at customer level / record level (the email To field's model) incl. several people holding a role; My tasks shows tasks assigned directly and via a role I hold; notifications; region scoping of tasks and of the assignee pickers; Tasks tab on each record and its count badge; permissions (TASK_VIEW/TASK_MANAGE, assignee may change status but not edit); tasks as of a date.` },
  { key: 'automation', idx: 5, scope: `A1–A5 automation end to end, API and UI: create/edit/enable/disable/delete rules in the UI; triggers CREATED/UPDATED on Customer/Invoice/Payment (make a save and poll until the consumer acts); AND/OR condition trees using list-page filter fields (matching and non-matching records); schedules daily/weekly (set the time a minute or two ahead of the server clock, or use run-now at a time, and wait); Run now from the UI; actions CREATE_TASK, CREATE_PROMISE, CREATE_DISPUTE, SEND_EMAIL with placeholders {{Customer.Name}}, {{Invoice.Balance}} etc. at both levels, and role-at-a-level recipients/assignees where several people hold the role → the primary; rule preview (matches + rendered actions); run history and effects in the UI; rules acting only within the owner's rights/regions; an automation-created change above a threshold becomes a pending change; A5 durability: the save is not slowed or broken by a failing rule, and events created while the consumer is down are processed after restart — test by stopping the backend process right after a save (kill -9 your own backend PID), restarting it with up.sh-equivalent start without dropping the DB (write a restart helper under ${E}/env if needed), and asserting the rule still acted exactly once.` },
  { key: 'regression', idx: 6, scope: `Regression of the pre-existing app, API and UI, now that regions/as-of/approvals exist: login/logout, users & roles admin, customers (create/edit/delete, POC seats), products, invoices (create with due dates and payment terms, edit, cancel, ageing), payments (record, allocation, credit), promises, disputes (create, approve/deny), documents (upload/download/visibility INTERNAL vs SHARED), email compose/send (incl. new placeholder support; transport none), inbox, notifications, dashboard charts and tables, audit history panel, list filters/sorting/pagination/export/bulk, sidebar navigation and routing, change password. Everything that worked before must still work for a single-region admin and for a POC user.` },
  { key: 'migration', idx: 7, scope: `Upgrade of EXISTING data (API-focused, plus a UI smoke): take a read-only dump of the user's dev database (\`docker exec gene-invoice-db pg_dump -U geneinvoice geneinvoice\` — if the database or user differs, list them read-only with psql -l first; NEVER write to gene-invoice-db) and restore it into gi-e2e-db as database e2e_migration; start the new backend against it (index 7) and verify: startup succeeds with no schema errors (grep the log), every existing record got a region (default), existing users kept their rights (single default region grant), history baseline created, enum widening applied, existing lists/counts/dashboard totals are the same as before the upgrade (compare against the same queries run on a SECOND restored copy booted with the BASELINE jar — build it from refs/snapshots/baseline into ${E}/baseline-src via \`git archive refs/snapshots/baseline | tar -x -C ...\` and mvn -o package there, on port 18108, database e2e_migration_base), existing logins work in the UI, and nothing references a missing region. Also boot the new jar on the h2 profile (in-memory) once to confirm it still starts. If the user's DB is empty or unavailable, say so and seed a representative dataset with the baseline jar instead, then upgrade that.` },
]

const RESULTS_SCHEMA = {
  type: 'object',
  properties: {
    total: { type: 'number' }, pass: { type: 'number' }, fail: { type: 'number' }, blocked: { type: 'number' }, notTested: { type: 'number' },
    defects: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' }, severity: { type: 'string', enum: ['high', 'medium', 'low'] }, kind: { type: 'string', enum: ['API', 'UI'] },
          prd: { type: 'string' }, title: { type: 'string' }, steps: { type: 'string' }, expected: { type: 'string' }, actual: { type: 'string' },
          evidence: { type: 'string' }, codeRef: { type: 'string' }, stream: { type: 'string', enum: ['backend', 'frontend', 'both', 'harness'] },
        },
        required: ['id', 'severity', 'kind', 'title', 'steps', 'expected', 'actual', 'stream'],
      },
    },
    resultsFile: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['total', 'pass', 'fail', 'defects', 'resultsFile'],
}

function testerPrompt(a, round, prevDefects) {
  const first = round === 1
  return `${CONTEXT}

${ISOLATION}

You are the E2E TESTER for area "${a.key}" (env index ${a.idx}: API http://localhost:${18100 + a.idx}, WEB http://localhost:${18200 + a.idx}), round ${round}. Read ${E}/README.md and ${E}/lib.js first — they are the rules and the helper library. Never edit ${REPO}/backend or ${REPO}/frontend; you only test. Other testers run in parallel on other indices with their own databases.

AREA SCOPE: ${a.scope}

${first
    ? `Write an AUTOMATED, re-runnable suite at ${E}/suites/${a.key}/run.js (plus helper files there) that covers the scope thoroughly with both API cases and UI cases (Playwright via lib.openApp), cites the PRD clause per case, and writes the results JSON to ${E}/runs/r<round>/${a.key}.json (round passed as argv/env) and UI screenshots to ${E}/runs/r<round>/${a.key}/shots/. Expected behaviour comes from the PRD and DESIGN.md (§6 contract, §5 behaviour), not guesses. Aim for depth: every sub-clause of the scope, positive AND negative cases, at least one UI case per user-visible feature.`
    : `The suite at ${E}/suites/${a.key}/run.js exists from earlier rounds. Fixes were made and the app rebuilt. Defects your area reported last round: ${JSON.stringify(prevDefects || [])}. Re-run the WHOLE suite fresh (new database) for round ${round}. For each previous defect add or keep a case that proves it is fixed (or still failing). Investigate every FAIL: is it a real defect, a harness/test bug (fix the test — record it in notes), or flakiness (rerun twice)? Extend the suite where last round's defects suggest nearby gaps.`}

Run: \`${E}/env/up.sh ${a.key} ${a.idx}\` → run the suite → \`${E}/env/down.sh ${a.key}\` (always bring it down, even on failure). Every FAIL must be reproduced twice before it counts; give exact steps, expected (with PRD/DESIGN reference), actual (observed), evidence (response JSON excerpt / screenshot path / log line) and the responsible code (file:line) when you can find it. Classify stream: backend, frontend, both, or harness (a test-harness problem, not a product defect). Return the structured summary; defect ids ${a.key.toUpperCase().slice(0, 3)}-R${round}-<n>.`
}

async function runPool(items, worker, size) {
  const results = new Array(items.length)
  let next = 0
  async function lane() {
    while (next < items.length) {
      const i = next++
      results[i] = await worker(items[i], i)
    }
  }
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, () => lane()))
  return results
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

const KNOWN = [
  { id: 'RR-1', severity: 'high', kind: 'API', stream: 'backend', title: 'SC-1 fix incomplete: rule preview and rule actions still fill in Customer.* placeholders of a customer the owner/caller cannot open', steps: 'see ' + SP + '/harden regression review', expected: 'Customer.* (except Name) left empty with a warning when the customer is not visible', actual: 'filled in', evidence: 'regression reviewer RR-1' },
  { id: 'RR-2', severity: 'low', kind: 'UI', stream: 'frontend', title: "Admin's Requeue on a MANUAL dead event fails with an API-path message, sometimes 'runs/null/retry'", steps: 'health screen → requeue a dead MANUAL event', expected: 'clear message pointing to the run retry, or hide Requeue for MANUAL events', actual: 'raw path error', evidence: 'regression reviewer RR-2' },
  { id: 'RR-3', severity: 'low', kind: 'API', stream: 'backend', title: 'Dashboard topPaying ties across region parts come out in part order rather than by name', steps: 'two customers with equal totals in different regions', expected: 'ties ordered by name', actual: 'part order', evidence: 'regression reviewer RR-3' },
]

const rounds = []
let prevByArea = {}
let lastDefects = []
for (let round = 1; round <= MAX_ROUNDS; round++) {
  phase('Test')
  const res = await runPool(AREAS, a => agent(testerPrompt(a, round, prevByArea[a.key]), { label: `test:r${round}:${a.key}`, phase: 'Test', schema: RESULTS_SCHEMA, effort: 'high' })
    .then(r => ({ area: a.key, ...(r || { total: 0, pass: 0, fail: 0, defects: [], resultsFile: '', notes: 'tester returned nothing' }) })), POOL)
  const defects = res.flatMap(r => (r.defects || []).map(d => ({ ...d, area: r.area })))
  const product = defects.filter(d => d.stream !== 'harness')
  log(`round ${round}: ${res.map(r => `${r.area} ${r.pass}/${r.total}`).join(', ')} — product defects ${product.length} (high ${product.filter(d => d.severity === 'high').length})`)
  prevByArea = Object.fromEntries(AREAS.map(a => [a.key, defects.filter(d => d.area === a.key)]))
  const toFix = round === 1 ? [...KNOWN, ...defects] : defects
  rounds.push({ round, summary: res.map(r => ({ area: r.area, total: r.total, pass: r.pass, fail: r.fail, blocked: r.blocked, notTested: r.notTested, resultsFile: r.resultsFile, notes: r.notes })), defects: defects.map(d => `${d.id} [${d.severity}/${d.stream}] ${d.title}`) })
  lastDefects = product
  if (!toFix.filter(d => d.stream !== 'harness').length && !defects.length) { log(`round ${round} clean — stopping`); break }
  if (round === MAX_ROUNDS) { log('max rounds reached; remaining defects go to the final report'); break }

  phase('Triage')
  const triage = await agent(`${CONTEXT}\n\n${ISOLATION}\n\nYou are the E2E TRIAGE LEAD for round ${round}. Defects from the testers (plus known review items in round 1) are below. For each: verify it against the code (and, if needed, reproduce it on your own env: \`${E}/env/up.sh triage 9\` … \`${E}/env/down.sh triage\`); reject false positives and test bugs with a reason (test bugs go in harnessFixes with the exact fix, for the tester to apply next round — do NOT edit suites yourself); for genuine product questions pick the default the PRD/DESIGN supports and list it in userDecisions. Split 'both' defects into backend and frontend parts. Group into fix packages (B1…, F1…; ≤8 defects each; backend and frontend streams run in parallel so a frontend package must not wait on a backend one — state the contract explicitly). Write precise instructions incl. the automated test to add (backend mvn test / flutter widget test). Also write your plan to ${E}/runs/r${round}/TRIAGE.md.\n\nDEFECTS:\n${JSON.stringify(toFix, null, 1)}`,
    { label: `triage:r${round}`, phase: 'Triage', schema: PKG_SCHEMA, effort: 'high' })
  const pkgs = (triage && triage.packages) || []
  const byId = Object.fromEntries(toFix.map(d => [d.id, d]))

  phase('Fix')
  async function chain(s) {
    const out = []
    for (const p of pkgs.filter(x => x.stream === s)) {
      const r = await agent(`${CONTEXT}\n\nYou are fixing E2E package ${p.id} (${s}) — "${p.title}" — round ${round}. Git read-only; record progress ONLY with ${SNAP} <name>; inspect with ${SDIFF}. Touch only ${s === 'backend' ? 'backend/ (another agent fixes frontend/ concurrently)' : 'frontend/ (another agent fixes backend/ concurrently)'}. Do NOT run the E2E harness or start servers; do NOT build into ${E}. Never weaken or delete existing assertions.\n\nINSTRUCTIONS:\n${p.instructions}\n\nDEFECTS:\n${JSON.stringify(p.defectIds.map(id => byId[id] || { id }), null, 1)}\n\nFor each defect: confirm the root cause in code, fix it, add an automated test that fails without the fix. Definition of done: ${DONE(s)}. Append "## E2E r${round} ${p.id} — ${p.title}" to ${E}/FIXLOG-${s}.md. Then run ${SNAP} E2E-r${round}-${p.id}. Return the structured result.`,
        { label: `fix:r${round}:${p.id}`, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
      out.push({ id: p.id, status: r ? r.status : 'none', fixed: r ? r.fixed : [] })
      if (!r || r.status !== 'green') { log(`${p.id} not green — stopping ${s} stream`); break }
    }
    return out
  }
  const fixes = await parallel([() => chain('backend'), () => chain('frontend')])
  rounds[rounds.length - 1].triage = { rejected: triage && triage.rejected, harnessFixes: triage && triage.harnessFixes, userDecisions: triage && triage.userDecisions }
  rounds[rounds.length - 1].fixes = fixes

  phase('Rebuild')
  await agent(`${ISOLATION}\n\nRebuild the E2E distribution after round ${round} fixes: run ${E}/build-dist.sh (it builds the jar and the web app inside ${REPO}; nothing else is building there now). Then smoke it: \`${E}/env/up.sh smoke 0\`, API login as admin, one UI login screenshot to ${E}/runs/r${round}/rebuild-smoke.png, \`${E}/env/down.sh smoke\`. If the build fails, report the exact error (do not edit source). Also apply these harness fixes from triage to ${E}/lib.js or ${E}/suites/** if they are harness-level (not per-area test logic): ${JSON.stringify((triage && triage.harnessFixes) || [])}. Return a one-paragraph status.`,
    { label: `rebuild:r${round}`, phase: 'Rebuild' })
}

return { setup, rounds, remaining: lastDefects.map(d => `${d.id} [${d.severity}/${d.stream}] ${d.title}`) }
