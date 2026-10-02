export const meta = {
  name: 'harden-build',
  description: 'Whole-build test-and-fix pass: parallel audits (contract, PRD coverage, security, correctness, carry-forward), triage into fix packages, sequential fixes per stream, regression review',
  phases: [
    { title: 'Audit', detail: 'five read-only auditors over the whole build' },
    { title: 'Triage', detail: 'dedupe, verify, group into fix packages' },
    { title: 'Fix', detail: 'backend and frontend packages, each ending green' },
    { title: 'Regression', detail: 'review the fix diff, run every suite' },
  ],
}

const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const DESIGN = `${SP}/design/DESIGN.md`
const B = `${SP}/build`
const H = `${SP}/harden`
const SNAP = `${B}/snap.sh`
const SDIFF = `${B}/sdiff.sh`

const PRD = `PRD — Part A (automation): A1 triggers on Customer/Invoice/Payment created or updated, or daily/weekly schedule; A2 conditions = filters over that entity's fields with AND and OR; A3 actions create Task/Promise/Dispute or send Email, assignees/recipients via the email To field's role-at-a-level model; A4 placeholders {{Customer.Name}}, {{Invoice.Balance}} etc. in subject/body for both levels, a role held by several people resolves to the primary; A5 durable execution: trigger publishes an event, consumer performs the action, a rule never slows or breaks the user's save, nothing lost if the consumer is down, rules can be scheduled or run from the UI whenever wanted; A6 Tasks on customer/invoice/payment with title, due date, status, assignees. Part B: B1 regions — every record in exactly one; a user holds different rights in different regions (read in one, manage in another, nothing in a third); the POC book is per region; every list, filter, count and dashboard figure answers "which regions am I asking about"; B2 maker-checker — a change above a configurable per-region threshold doesn't take effect on save, it becomes pending and needs approval from someone else holding the approval right in the record's region; a pending change is visible to everyone who can see the record without having taken effect; B3 as-of reads — every list, report and rule evaluation can be asked as of a date, including which region a record belonged to then and which approvals were outstanding.`

const CONTEXT = `Codebase: ${REPO} (Spring Boot 3.3 / Java 17 backend in backend/, Flutter frontend in frontend/, mail-service/ untouched). Branch feature/automation-regions-approvals-asof; nothing committed. The whole PRD was just built in 29 slices (21 backend, 8 frontend) from the design at ${DESIGN}. Build logs (what each slice actually built, deviations, gaps): ${B}/LOG-backend.md and ${B}/LOG-frontend.md. Open items deferred by slices: ${B}/CARRY-FORWARD.md. Per-slice review findings: ${B}/*.review.md. The frontend was built ONLY against DESIGN.md §6 and a fake backend (frontend/test/support/fake_backend.dart); it has never talked to the real backend. The full change set versus the pre-build baseline: \`${SDIFF} baseline <dir> [--stat]\`.

${PRD}`

const GIT_RULES = `Git: read-only commands only (status, diff, log, show). NEVER git add/commit/stash/checkout/restore/reset/clean/rebase/merge. Inspect changes with ${SDIFF} <snapshot> <dir> [--stat]; record progress ONLY with ${SNAP} <name>.`

const FINDING = {
  type: 'object',
  properties: {
    id: { type: 'string', description: 'short unique id, e.g. CT-3' },
    severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
    stream: { type: 'string', enum: ['backend', 'frontend', 'both'] },
    title: { type: 'string' },
    evidence: { type: 'string', description: 'file:line quotes / command output proving it' },
    fix: { type: 'string' },
    needsUserDecision: { type: 'boolean' },
  },
  required: ['id', 'severity', 'stream', 'title', 'evidence', 'fix'],
}
const FINDINGS_SCHEMA = { type: 'object', properties: { findings: { type: 'array', items: FINDING }, notes: { type: 'string' } }, required: ['findings'] }

const AUDITS = [
  { key: 'contract', prefix: 'CT', prompt: `AUDIT: frontend ↔ backend API contract. The frontend has never called the real backend. For EVERY HTTP call the frontend makes (grep frontend/lib for dio/api client calls: get/post/put/patch/delete, and every model fromJson/toJson), find the backend controller method that serves it and compare: path and method, query params (incl. regionIds, asOf, filter/sort/page conventions), request body field names/types/nullability, response field names/types (money as number vs string vs double, dates, enums and their values), status codes (202 held, 409, 400 coded errors like AS_OF_READ_ONLY and their JSON shape), headers (X-View-As-Of, Location — and whether they are in the CORS exposed headers so a Flutter WEB build can read them), pagination envelope (PageResponse fields). Also check the reverse: backend endpoints the design expects the UI to use but no screen calls. Report each mismatch that would break or silently mis-render in the real app. Where practical, prove a mismatch by running the backend's own serialisation (e.g. read a test that returns the DTO, or write a throwaway JSON sample from the DTO record) — do not edit repository files; you may write scratch files under ${H}/.` },
  { key: 'prd', prefix: 'PC', prompt: `AUDIT: PRD coverage end to end. Split every clause of A1–A6 and B1–B3 finely (e.g. B1: every list / every filter / every count / every dashboard figure / POC book per region / read in one, manage in another, nothing in a third; B3: every list / report / rule evaluation / region then / approvals outstanding then; A5: never slows / never breaks the save / nothing lost when consumer down / scheduled / run from UI). For each: find the implementing code AND a test that proves it, in both backend and (where user-visible) frontend. Report clauses that are unimplemented, only partly implemented, implemented but untested, or where the UI never exposes a backend capability the PRD requires users to have (e.g. a user can't actually create a schedule, run a rule from the UI, see a pending change, pick an as-of date on some list).` },
  { key: 'security', prefix: 'SC', prompt: `AUDIT: security, scope and data consistency across the WHOLE build (slice reviewers only saw one slice each; look for cross-slice holes). Region/book/as-of leaks on any read path (lists, counts, exports, bulk id resolution, dashboard, record tabs, audit timeline incl. includeRelated, email directory and pickers, assignee pickers, POC endpoints, notifications, placeholders rendering data from another region, rule preview, run/effect views); writes without per-region MANAGE; writes accepted in as-of mode; customer-login exposure of internal disputes/tasks/approvals/staff identities; maker-checker bypasses (bulk endpoints, automation actions, dispute approval re-entry, splitting, self-approval via another path, approving outside own region, threshold loosening); automation acting beyond its owner's current rights or regions; run-now/enable by non-owners; outbox insert able to fail a save. Prove each with code references (and a throwaway test run if needed — but do not leave repository edits; scratch under ${H}/).` },
  { key: 'correctness', prefix: 'CR', prompt: `AUDIT: correctness, concurrency and robustness across the whole build. Specifically: (1) investigate the flaky HistoryConcurrencyTest.twoTransactionsRelinkingOnePromiseAtOnceLeaveOneOpenRowPerLink (failed once under full-suite load: a promise link live with no open version) — determine whether it's a real race in link versioning (no owner lock) that could corrupt as-of answers in production, and specify the fix; run it repeatedly (e.g. 20x) to measure; (2) the flaky frontend test region_selector_test 'every dashboard figure' (waits on real time); (3) transaction boundaries and lock order (customer first) across maker-checker apply, automation consumer actions, region move, history writer and outbox; deadlock potential; (4) consumer durability: lease/fencing, retries/backoff/dead-letter, idempotency on effects, schedule catch-up, what happens if the app restarts mid-run; (5) startup on an EXISTING database: boot the app with ddl-auto=update against an H2 FILE database created from the pre-build schema (baseline snapshot) with some data, and confirm migrations/backfills (regions, history baseline, enum widening) work — you may build the baseline jar from 'git show refs/snapshots/baseline' into a temp dir under ${H}/ to create that DB; (6) run the full backend suite 3 times and flutter test 3 times to surface any other flakiness. Do not leave repository edits.` },
  { key: 'carry', prefix: 'CF', prompt: `AUDIT: the carry-forward list ${B}/CARRY-FORWARD.md plus every unfixed low/medium finding in ${B}/*.review.md (check each review's items against the matching '### <slice> review fixes' log sections to see what was already fixed). Many items may have been resolved by later slices. For EACH item, check the CURRENT code and classify: resolved (cite evidence), still-open (report it as a finding with a concrete fix), or accept-as-designed (only if DESIGN.md explicitly chose it; then report as low with needsUserDecision=false and say why). Report only still-open items as findings, but list the resolved/accepted ones in 'notes' with one line each.` },
]

phase('Audit')
const audits = await parallel(AUDITS.map(a => () =>
  agent(`${CONTEXT}\n\nYou are a READ-ONLY auditor (you may write scratch files only under ${H}/; create it if needed). ${GIT_RULES}\n\nOTHER AUDITORS RUN IN PARALLEL. Never run mvn, flutter test/analyze/build or any build inside ${REPO} itself — concurrent builds there corrupt each other's target/ and .dart_tool/. If you need to build or run tests, first make your own private copy: \`mkdir -p ${H}/ws && rsync -a --delete --exclude target --exclude build --exclude .dart_tool --exclude node_modules ${REPO}/ ${H}/ws/${a.key}/\` and work ONLY in ${H}/ws/${a.key}/ (use mvn -o; run 'flutter pub get --offline' there before flutter commands). Read source from ${REPO} or the copy — they are identical.\n\n${a.prompt}\n\nReport only REAL, verified issues with file:line evidence; no style nits, no speculation. Use ids ${a.prefix}-1, ${a.prefix}-2, … . Set stream to where the fix goes (both = needs coordinated backend and frontend change). Set needsUserDecision=true only for genuine product decisions the PRD/design leaves open.`,
    { label: `audit:${a.key}`, phase: 'Audit', schema: FINDINGS_SCHEMA, effort: 'high' })
    .then(r => ({ key: a.key, findings: (r && r.findings) || [], notes: (r && r.notes) || '' }))
))
const all = audits.filter(Boolean).flatMap(a => a.findings.map(f => ({ ...f, audit: a.key })))
log(`audit findings: ${all.length} (critical ${all.filter(f => f.severity === 'critical').length}, high ${all.filter(f => f.severity === 'high').length}, medium ${all.filter(f => f.severity === 'medium').length}, low ${all.filter(f => f.severity === 'low').length})`)

phase('Triage')
const PKG_SCHEMA = {
  type: 'object',
  properties: {
    packages: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string', description: 'B1, B2… for backend, F1, F2… for frontend' },
          stream: { type: 'string', enum: ['backend', 'frontend'] },
          title: { type: 'string' },
          findingIds: { type: 'array', items: { type: 'string' } },
          instructions: { type: 'string', description: 'precise fix instructions per finding, incl. files and the test to add' },
        },
        required: ['id', 'stream', 'title', 'findingIds', 'instructions'],
      },
    },
    rejected: { type: 'array', items: { type: 'string' }, description: 'finding id + reason' },
    userDecisions: { type: 'array', items: { type: 'string' }, description: 'finding id + the question + the default applied' },
  },
  required: ['packages', 'rejected', 'userDecisions'],
}
const triage = await agent(`${CONTEXT}\n\nYou are the TRIAGE LEAD. ${GIT_RULES} Read-only for the repo; write your plan also to ${H}/TRIAGE.md. If you need to run anything, use a private copy under ${H}/ws/triage/ (rsync as the auditors did), never builds inside ${REPO}.\n\nBelow are findings from five independent auditors. (1) Deduplicate (the same defect reported by several auditors becomes one). (2) Verify each against the current code — reject anything wrong or already fixed, with a reason. (3) For needsUserDecision items, choose the default the design/PRD most supports, still fix to that default, and list the question in userDecisions. (4) Split every 'both' finding into a backend part and a frontend part (the frontend part must follow the backend's actual contract after the fix). (5) Group the remaining work into fix PACKAGES: backend packages (B1, B2, …) and frontend packages (F1, F2, …), each a coherent set touching related files, at most ~8 findings or ~1,500 changed lines each, ordered so contract/shared fixes come first. Backend packages will run sequentially, frontend packages sequentially, the two streams in parallel — so a frontend package must not depend on a backend package finishing (write the frontend fix against the contract the backend package will produce, stated explicitly in instructions). Write precise instructions (files, change, the test that proves it). All critical/high/medium must be packaged; include lows too unless clearly not worth it (then list in rejected with reason).\n\nFINDINGS:\n${JSON.stringify(all, null, 1)}`,
  { label: 'triage', phase: 'Triage', schema: PKG_SCHEMA, effort: 'high' })

const packages = (triage && triage.packages) || []
const bePk = packages.filter(p => p.stream === 'backend')
const fePk = packages.filter(p => p.stream === 'frontend')
log(`packages: backend ${bePk.length}, frontend ${fePk.length}; rejected ${(triage && triage.rejected || []).length}; user decisions ${(triage && triage.userDecisions || []).length}`)

const byId = Object.fromEntries(all.map(f => [f.id, f]))
const DONE = s => s === 'backend'
  ? '`cd backend && mvn -q -o test` passes with ZERO failures/errors (drop -o only if offline resolution fails) and `cd mail-service && mvn -q -o test` passes'
  : '`cd frontend && flutter analyze` reports 0 issues and `cd frontend && flutter test` passes'
const FIX_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['green', 'red', 'blocked'] },
    fixed: { type: 'array', items: { type: 'string' } },
    rejected: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string' },
    blocker: { type: 'string' },
  },
  required: ['status', 'fixed', 'tests'],
}

async function fixChain(s, pkgs) {
  const out = []
  for (const p of pkgs) {
    const fs = p.findingIds.map(id => byId[id] || { id })
    const r = await agent(`${CONTEXT}\n\nYou are fixing package ${p.id} (${s}) — "${p.title}" — of the whole-build test-and-fix pass.\n${GIT_RULES}\nTouch only ${s === 'backend' ? 'backend/ (never frontend/: another agent is fixing frontend/ concurrently)' : 'frontend/ (never backend/: another agent is fixing backend/ concurrently)'}. Match surrounding style. Never weaken, skip or delete an existing assertion to get green; baseline test files (HEAD 321150e) may only gain assertions.\n\nTRIAGE INSTRUCTIONS:\n${p.instructions}\n\nFINDINGS IN THIS PACKAGE:\n${JSON.stringify(fs, null, 1)}\n\nFor each finding: re-verify it against the current code; if valid fix it with a test that fails without the fix (check by temporarily reverting); if invalid, say why. Then the definition of done: ${DONE(s)}. If a test fails that your change did not cause (flaky), rerun it to confirm and report it; do not paper over it. Append a section "## ${p.id} — ${p.title}" to ${H}/FIXLOG-${s}.md (fixed, rejected with reason, tests, final counts). Then run: ${SNAP} HARDEN-${p.id}\nReturn the structured result.`,
      { label: `fix:${p.id}`, phase: 'Fix', schema: FIX_SCHEMA, effort: 'high' })
    out.push({ id: p.id, title: p.title, result: r })
    log(`${p.id}: ${r ? r.status : 'no result'} — fixed ${r && r.fixed ? r.fixed.length : 0}`)
    if (!r || r.status !== 'green') { log(`${p.id} not green — stopping ${s} fix stream`); break }
  }
  return out
}

phase('Fix')
const fixes = await parallel([() => fixChain('backend', bePk), () => fixChain('frontend', fePk)])

phase('Regression')
const regression = await agent(`${CONTEXT}\n\nYou are the REGRESSION REVIEWER for the whole-build fix pass. ${GIT_RULES} Read-only for the repo (scratch under ${H}/). The fixes are in ${H}/FIXLOG-backend.md and ${H}/FIXLOG-frontend.md; the snapshot before fixes is 'BE-19' for backend/ and 'FE-07b' for frontend/ — review the diff: \`${SDIFF} BE-19 backend/\` and \`${SDIFF} FE-07b frontend/\`. Look for regressions, fixes that don't actually fix the finding, weakened tests, and cross-stream contract mismatches introduced by the two parallel fix streams (the frontend fixes were written against the backend's intended post-fix contract — check they match what the backend now actually does). Run: cd backend && mvn -q -o test; cd mail-service && mvn -q -o test; cd frontend && flutter analyze && flutter test. Report the counts and any verified problems (severity + evidence + fix).`,
  { label: 'regression', phase: 'Regression', schema: { type: 'object', properties: { tests: { type: 'string' }, findings: { type: 'array', items: FINDING } }, required: ['tests', 'findings'] }, effort: 'high' })

return {
  auditCounts: audits.filter(Boolean).map(a => ({ audit: a.key, n: a.findings.length })),
  triage: { rejected: triage && triage.rejected, userDecisions: triage && triage.userDecisions, packages: packages.map(p => ({ id: p.id, stream: p.stream, title: p.title, n: p.findingIds.length })) },
  fixes,
  regression,
}
