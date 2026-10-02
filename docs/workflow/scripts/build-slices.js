export const meta = {
  name: 'build-slices',
  description: 'Implement DESIGN.md slices: backend and frontend streams in parallel; each slice implemented, adversarially reviewed, fixed, verified green and snapshotted',
  phases: [
    { title: 'Backend', detail: 'sequential backend slices: implement → review → fix' },
    { title: 'Frontend', detail: 'sequential frontend slices: implement → review → fix' },
  ],
}

const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const DESIGN = `${SP}/design/DESIGN.md`
const MAP = `${SP}/map`
const B = `${SP}/build`
const SNAP = `${B}/snap.sh`
const SDIFF = `${B}/sdiff.sh`
const { be = [], fe = [], prevBe = 'baseline', prevFe = 'baseline' } = args || {}

const PRD = `A1 triggers on Customer/Invoice/Payment create/update or daily/weekly schedule; A2 AND/OR conditions over the entity's list-page filter fields; A3 actions create Task/Promise/Dispute or send Email, assignees/recipients via the email To field's role-at-a-level model; A4 {{Entity.Field}} placeholders for both levels, a role held by several resolves to the primary; A5 durable execution via event + consumer, never slows/breaks the save, nothing lost when the consumer is down, rules schedulable and runnable from the UI; A6 Tasks (title, due date, status, assignees) on customer/invoice/payment. B1 every record in exactly one region, per-region rights (read/manage/none), POC book per region, every list/filter/count/dashboard figure scoped by "which regions am I asking about"; B2 maker-checker: changes above a per-region threshold become pending until someone else with the approval right in the record's region approves, pending visible to all who can see the record; B3 as-of reads for every list/report/rule evaluation incl. region at the time and approvals outstanding then.`

const logFile = s => s === 'backend' ? `${B}/LOG-backend.md` : `${B}/LOG-frontend.md`
const dirOf = s => s === 'backend' ? 'backend/' : 'frontend/'

const DONE = s => s === 'backend'
  ? '`cd backend && mvn -q -o test` passes with ZERO failures and errors (if offline dependency resolution fails, rerun without -o), and `cd mail-service && mvn -q -o test` still passes. Take counts from backend/target/surefire-reports.'
  : '`cd frontend && flutter analyze` reports 0 issues and `cd frontend && flutter test` passes.'

const RULES = (id, s) => `RULES
1. Implement exactly slice ${id}: create/modify the files it lists plus unavoidable compile fallout. Do not implement later slices' work, and do not leave TODO stubs inside your own slice's scope. Match the surrounding code's style, naming, comment density and idioms.
2. ${s === 'backend'
    ? 'Touch only backend/ (mail-service/ only if the slice says so). Another agent is concurrently working in frontend/ — never touch, revert or reformat anything there.'
    : `Touch only frontend/. Another agent is concurrently working in backend/ — never touch, revert or reformat anything there. Build against the API contract in DESIGN.md §6, overridden by any API deviations recorded in ${B}/LOG-backend.md. Fake the backend in tests with frontend/test/support/fake_backend.dart.`}
3. Git: read-only commands only (status, diff, log, show). NEVER run git add/commit/stash/checkout/restore/reset/clean/rebase/merge or anything that changes HEAD, the index, branches or working-tree files. To inspect what changed use ${SDIFF} <snapshotName> <dir> [--stat]. To record progress use ONLY ${SNAP} <name>.
4. Baseline test files (present at HEAD 321150e) must not be edited except where DESIGN.md §9.0 permits. Never weaken, skip, @Disabled or delete an existing assertion to get green.
5. Definition of done: ${DONE(s)} Every test named in the slice's acceptance list exists, is meaningful, and passes. Iterate until green; hand back red only if genuinely blocked, and then say exactly why.
6. If DESIGN.md is wrong or infeasible somewhere (a cited line moved, an API doesn't exist, two sections conflict, an earlier slice deviated), choose the smallest deviation that preserves the design's intent and the PRD, and record it. Never silently narrow scope.`

const READING = (id, s) => `AUTHORITATIVE SPEC: ${DESIGN}. Read in this order: §1 and §2 (all cross-cutting decisions), §9.0 (rules for every slice), the slice entry "${id}" in §9, then EVERY section that entry references or that defines the classes, tables, endpoints, DTOs, widgets and tests it creates or modifies (§3 data model, §4 rights, §5 module design, §6 API contract, §7 frontend, §8 tests). Then read ${B}/LOG-backend.md and ${B}/LOG-frontend.md (running logs of earlier slices: what was actually built, deviations from DESIGN.md, known gaps) — build on what actually exists, not on what the design assumed. Codebase maps with file:line conventions are in ${MAP}/ (critic.md first); they describe the code before this build started, so the current code is the truth.

The PRD being built (for intent): ${PRD}`

const IMPL = (id, s, prev) => `You are implementing slice ${id} of a large, multi-slice feature build in ${REPO} (git branch feature/automation-regions-approvals-asof; nothing is committed).

${READING(id, s)}

${RULES(id, s)}

WHEN GREEN
- Append to ${logFile(s)} a section "## ${id} — <slice title>" with: what was built (classes, tables/columns, endpoints, widgets/providers), deviations from DESIGN.md with reasons, API-contract changes versus §6 (should be none; the frontend builds against §6), known gaps or deferred items, and final test counts.
- Then run: ${SNAP} ${id}-impl
Return the structured result (status green/red/blocked).`

const REVIEW = (id, s, prev, impl) => `You are an adversarial reviewer for slice ${id} of a multi-slice build in ${REPO}. Another agent just implemented it. You are READ-ONLY for the repository (you may write files only under ${B}/). Git: read-only commands only.

${READING(id, s)}

The implementer's report: ${JSON.stringify(impl)}

See exactly what this slice changed: run \`${SDIFF} ${prev} ${dirOf(s)} --stat\`, then the full diff \`${SDIFF} ${prev} ${dirOf(s)}\`, and read changed files in full where needed.

Hunt for REAL defects, most severe first:
- Divergence from DESIGN.md / the PRD: parts of the slice missing or stubbed, behaviour different from the spec, API shapes different from §6, acceptance-list tests missing.
- Correctness: transaction boundaries and propagation, lock order (customer first), null handling, concurrency, H2-vs-Postgres SQL portability, ddl-auto migration rules (critic C-5/R9/R10), clock reads not going through the Clock.
- Security and scope: any read path (list, count, export, bulk id resolution, dashboard, record tab, by-id read) that ignores region/book scope; any write without the per-region right; any write allowed in as-of mode; data from another region leaking via DTOs, pickers or placeholders.
- Broken existing behaviour: run the definition of done yourself: ${DONE(s)}
- Tests that don't prove what they claim (assertions too weak, passing trivially, not exercising the risky path).
- ${s === 'frontend' ? 'Flutter: providers not refetching when the view scope changes, regionIds/asOf not sent on some request, 202 not handled, widgets gated on role names instead of server flags, analyzer-clean but wrong.' : 'Backend: exceptions mapped to the wrong status, a background path calling currentUser.require(), a new user-referencing table missing from pocReferenceCount or the test reset order.'}

Verify every finding against the code before reporting it (quote file:line). No style nits, no speculation. Severity: critical (data leak/corruption, red build), high (spec requirement missing or wrong), medium (edge-case bug, or a weak test for a required behaviour), low (minor). Write your findings to ${B}/${id}.review.md and return them.`

const FIX = (id, s, prev, impl, review) => `You are finishing slice ${id} of a multi-slice build in ${REPO} after an adversarial review.

${READING(id, s)}

${RULES(id, s)}

Implementer's report: ${JSON.stringify(impl)}
${impl && impl.status !== 'green' ? 'The implementer did NOT reach green. First make the build green and complete any unfinished part of the slice.' : ''}
Reviewer findings (also in ${B}/${id}.review.md): ${JSON.stringify((review && review.findings) || [])}

For each finding: verify it against the code; if valid, fix it (adding or strengthening a test when it is behavioural); if invalid, say why. Fix low-severity findings too when cheap. Then run the full definition of done. Append a "### ${id} review fixes" subsection to ${logFile(s)} (what was fixed, what was rejected and why, final test counts). Then run: ${SNAP} ${id}
Return the structured result.`

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['green', 'red', 'blocked'] },
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string', description: 'final test counts per suite' },
    deviations: { type: 'array', items: { type: 'string' } },
    gaps: { type: 'array', items: { type: 'string' } },
    blocker: { type: 'string' },
  },
  required: ['status', 'summary', 'tests'],
}
const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          title: { type: 'string' },
          location: { type: 'string' },
          detail: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'title', 'detail'],
      },
    },
    testsRun: { type: 'string' },
  },
  required: ['findings', 'testsRun'],
}
const FIX_SCHEMA = {
  type: 'object',
  properties: {
    status: { type: 'string', enum: ['green', 'red', 'blocked'] },
    fixed: { type: 'array', items: { type: 'string' } },
    rejected: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string' },
    blocker: { type: 'string' },
  },
  required: ['status', 'tests'],
}

async function chain(s, ids, prev) {
  const ph = s === 'backend' ? 'Backend' : 'Frontend'
  const out = []
  for (const id of ids) {
    const impl = await agent(IMPL(id, s, prev), { label: `impl:${id}`, phase: ph, schema: IMPL_SCHEMA, effort: 'high' })
    if (!impl || impl.status === 'blocked') {
      log(`${id}: implementer ${impl ? 'blocked: ' + impl.blocker : 'returned nothing'} — stopping ${s} stream`)
      out.push({ id, stoppedAt: 'impl', impl })
      return { stream: s, results: out, last: prev, complete: false }
    }
    const review = await agent(REVIEW(id, s, prev, impl), { label: `review:${id}`, phase: ph, schema: REVIEW_SCHEMA, effort: 'high' })
    const findings = (review && review.findings) || []
    const actionable = findings.filter(f => f.severity !== 'low')
    let final = { status: impl.status, snapshot: `${id}-impl`, fix: null }
    if (impl.status !== 'green' || actionable.length) {
      const fix = await agent(FIX(id, s, prev, impl, review), { label: `fix:${id}`, phase: ph, schema: FIX_SCHEMA, effort: 'high' })
      final = { status: fix ? fix.status : 'red', snapshot: id, fix }
    }
    const sev = ['critical', 'high', 'medium', 'low'].map(k => `${k} ${findings.filter(f => f.severity === k).length}`).join(', ')
    log(`${id}: impl ${impl.status}; review ${sev}; final ${final.status}`)
    out.push({ id, impl: { status: impl.status, summary: impl.summary, tests: impl.tests, deviations: impl.deviations, gaps: impl.gaps }, findings: findings.map(f => `[${f.severity}] ${f.title}`), final })
    if (final.status !== 'green') {
      log(`${id} ended ${final.status} — stopping ${s} stream`)
      return { stream: s, results: out, last: prev, complete: false }
    }
    prev = final.snapshot
  }
  return { stream: s, results: out, last: prev, complete: true }
}

const streams = await parallel([
  () => be.length ? chain('backend', be, prevBe) : Promise.resolve({ stream: 'backend', results: [], last: prevBe, complete: true }),
  () => fe.length ? chain('frontend', fe, prevFe) : Promise.resolve({ stream: 'frontend', results: [], last: prevFe, complete: true }),
])
return { streams }
